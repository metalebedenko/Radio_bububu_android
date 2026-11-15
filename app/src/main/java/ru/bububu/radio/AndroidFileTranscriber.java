package ru.bububu.radio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import ru.bububu.radio.core.NoteCoordinator;
import ru.bububu.radio.core.PcmWave;
import ru.bububu.radio.core.SegmentedFileTranscript;

/** Installed on-device recognizer, verified saved WAV, bounded raw-file input. Never uses a default recognizer or mic fallback. */
final class AndroidFileTranscriber {
    static final long SUPPORT_TIMEOUT_MS = 10_000, TRANSCRIBE_TIMEOUT_MS = 180_000;
    private final Context context;
    private final Handler main;
    private final File originals;
    private final AudioManager audio;
    private Session active;
    private long generation;

    AndroidFileTranscriber(Context context, Handler main, File originals) {
        this.context = context.getApplicationContext(); this.main = main; this.originals = originals;
        audio = this.context.getSystemService(AudioManager.class);
    }

    void transcribe(String noteId, Consumer<NoteCoordinator.RecognitionResult> callback) {
        requireMain(); cancel();
        Session s = new Session(++generation, callback); active = s;
        if (Build.VERSION.SDK_INT < 33) { finish(s, "UNAVAILABLE", "Распознавание сохранённых файлов требует Android 13 или новее.", null); return; }
        try {
            if (!UUID.fromString(noteId).toString().equals(noteId)) throw new IOException("Invalid original identity");
            s.wave = new File(originals, noteId + ".wav");
            if (!Files.isRegularFile(s.wave.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Original is unavailable");
            s.expectedBytes = PcmWave.readInfo(s.wave).pcmBytes;
            if (s.expectedBytes <= 0) { finish(s, "UNAVAILABLE", "В оригинальной записи нет звука для расшифровки.", null); return; }
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) { finish(s, "UNAVAILABLE", "Установленное распознавание на телефоне недоступно. Оригинал сохранён.", null); return; }
            s.deadline = SystemClock.elapsedRealtime() + SUPPORT_TIMEOUT_MS;
            observe(s);
            if (!alive(s)) return;
            s.recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
            s.recognizer.setRecognitionListener(listener(s));
            ParcelFileDescriptor[] support = ParcelFileDescriptor.createPipe();
            s.supportRead = support[0]; s.supportWrite = support[1];
            if (!close(s.supportWrite)) throw new IOException("Support EOF failed");
            s.supportWrite = null;
            s.timeout = () -> finish(s, "ERROR", "Телефон не ответил на проверку распознавания. Можно повторить.", null);
            main.postDelayed(s.timeout, SUPPORT_TIMEOUT_MS);
            s.recognizer.checkRecognitionSupport(intent(s.supportRead), task -> main.post(task), new RecognitionSupportCallback() {
                @Override public void onSupportResult(RecognitionSupport support) {
                    if (!liveBeforeDeadline(s)) return;
                    main.removeCallbacks(s.timeout);
                    if (!close(s.supportRead)) { finish(s, "ERROR", "Не удалось завершить проверку файла. Оригинал сохранён.", null); return; }
                    s.supportRead = null;
                    boolean russian = false;
                    if (support != null) for (String language : support.getInstalledOnDeviceLanguages())
                        if (language != null && "ru".equals(Locale.forLanguageTag(language).getLanguage())) russian = true;
                    if (!russian || support == null || !support.getOnlineLanguages().isEmpty()) {
                        finish(s, "UNAVAILABLE", "Для файла не подтверждён установленный русский язык. Модели и сетевое распознавание не используются.", null); return;
                    }
                    startFile(s);
                }
                @Override public void onError(int error) { if (alive(s)) finish(s, "UNAVAILABLE", "Телефон не подтвердил распознавание сохранённого файла. Оригинал сохранён; текст можно ввести вручную.", null); }
            });
        } catch (IOException | RuntimeException error) { finish(s, "ERROR", "Не удалось открыть оригинал или подготовить распознавание. Запись сохранена.", null); }
    }

    void cancel() {
        requireMain(); Session s = active; active = null; ++generation;
        if (s != null) dispose(s);
    }

    @android.annotation.TargetApi(33) // Called only after transcribe's file-source API 33 guard.
    private void startFile(Session s) {
        if (!alive(s)) return;
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            s.actualRead = pipe[0]; s.output = pipe[1];
            Os.fcntlInt(s.output.getFileDescriptor(), OsConstants.F_SETFL,
                    Os.fcntlInt(s.output.getFileDescriptor(), OsConstants.F_GETFL, 0) | OsConstants.O_NONBLOCK);
            s.deadline = SystemClock.elapsedRealtime() + TRANSCRIBE_TIMEOUT_MS;
            s.timeout = () -> finish(s, "ERROR", "Расшифровка не завершилась за три минуты. Оригинал сохранён; можно повторить.", null);
            main.postDelayed(s.timeout, TRANSCRIBE_TIMEOUT_MS);
            s.recognizer.startListening(intent(s.actualRead));
            if (!alive(s)) return;
            // Supply bytes immediately. Waiting for onReadyForSpeech can deadlock a file-source engine.
            s.writer = new Thread(() -> writeOriginal(s), "Bububu-file-stt");
            s.writer.setDaemon(true); s.writer.start();
        } catch (IOException | ErrnoException | RuntimeException failure) {
            finish(s, "ERROR", "Телефон не запустил распознавание файла. Оригинал сохранён.", null);
        }
    }

    private void writeOriginal(Session s) {
        boolean ok = false;
        try (RandomAccessFile input = new RandomAccessFile(s.wave, "r")) {
            s.input = input;
            if (PcmWave.readInfo(input).pcmBytes != s.expectedBytes) throw new IOException("Original changed");
            byte[] buffer = new byte[8192];
            while (s.written < s.expectedBytes) {
                checkWriter(s);
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, s.expectedBytes - s.written));
                if (count <= 0) throw new IOException("Original ended early");
                int offset = 0;
                while (offset < count) {
                    checkWriter(s);
                    try {
                        int sent = Os.write(s.output.getFileDescriptor(), buffer, offset, count - offset);
                        if (sent > 0) { offset += sent; s.written += sent; }
                        else Thread.sleep(10);
                    } catch (ErrnoException retry) {
                        if (retry.errno == OsConstants.EAGAIN || retry.errno == OsConstants.EINTR) Thread.sleep(10);
                        else throw retry;
                    }
                }
            }
            if (input.length() != PcmWave.HEADER_BYTES + s.expectedBytes || input.read() != -1) throw new IOException("Original size changed");
            checkWriter(s);
            s.eofBegin = SystemClock.elapsedRealtimeNanos();
            s.output.close(); s.eofClosed = true;
            ok = true;
        } catch (Exception failure) { /* The main owner publishes a bounded error, never partial text. */ }
        finally {
            s.input = null;
            if (!close(s.output)) ok = false;
            s.writerDone = true;
            final boolean complete = ok;
            main.post(() -> {
                if (!alive(s)) return;
                if (!complete) finish(s, "ERROR", "Файл не удалось передать полностью. Оригинал сохранён; можно повторить.", null);
                else maybeComplete(s);
            });
        }
    }

    private void checkWriter(Session s) throws IOException {
        if (s.cancelled.get() || Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= s.deadline)
            throw new IOException("Cancelled or expired input");
    }
    private RecognitionListener listener(Session s) {
        return new RecognitionListener() {
            public void onReadyForSpeech(Bundle params) { }
            public void onBeginningOfSpeech() { }
            public void onRmsChanged(float rms) { }
            public void onBufferReceived(byte[] buffer) { }
            public void onEndOfSpeech() { } // Never terminal for segmented file input.
            public void onPartialResults(Bundle results) { } // Never publish an unfinished transcript.
            public void onEvent(int event, Bundle params) { }
            public void onError(int error) { if (alive(s)) finish(s, "ERROR", "Телефон не смог расшифровать файл. Оригинал сохранён; можно повторить.", null); }
            public void onResults(Bundle results) { if (alive(s)) finish(s, "UNAVAILABLE", "Движок не подтвердил завершённое распознавание файла. Оригинал сохранён.", null); }
            @Override public void onSegmentResults(Bundle results) {
                if (!liveBeforeDeadline(s)) return;
                try {
                    ArrayList<String> choices = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (choices != null && !choices.isEmpty()) s.transcript.segment(choices.get(0));
                } catch (RuntimeException tooLong) { finish(s, "ERROR", "Текст превысил предел заметки. Оригинал сохранён; частичный текст не установлен.", null); }
            }
            @Override public void onEndOfSegmentedSession() {
                if (!liveBeforeDeadline(s)) return;
                s.terminalNanos = SystemClock.elapsedRealtimeNanos(); s.segmentedEnd = true; maybeComplete(s);
            }
        };
    }
    private void maybeComplete(Session s) {
        if (!liveBeforeDeadline(s) || !s.segmentedEnd || !s.writerDone) return;
        try {
            String text = s.transcript.complete(s.expectedBytes, s.written, s.eofClosed, s.eofBegin, s.terminalNanos);
            finish(s, "READY", "Текст готов. Оригинальная запись сохранена.", text);
        } catch (RuntimeException incomplete) { finish(s, "ERROR", "Полный текст не получен. Оригинал сохранён; можно повторить.", null); }
    }
    private Intent intent(ParcelFileDescriptor source) {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU").putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false).putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PcmWave.SAMPLE_RATE)
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
    }
    private void observe(Session s) {
        if (audio == null) throw new IllegalStateException("Recording observer unavailable");
        s.observer = new AudioManager.AudioRecordingCallback() {
            @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) { checkCapture(s, configs); }
        };
        s.observing = true; audio.registerAudioRecordingCallback(s.observer, main);
        s.poll = () -> {
            if (!liveBeforeDeadline(s)) return;
            try { checkCapture(s, audio.getActiveRecordingConfigurations()); }
            catch (RuntimeException unavailable) { finish(s, "UNAVAILABLE", "Нельзя проверить режим распознавания файла. Оригинал сохранён; доступен ручной ввод.", null); }
            if (alive(s)) main.postDelayed(s.poll, 100);
        };
        s.poll.run();
    }
    private void checkCapture(Session s, List<AudioRecordingConfiguration> configs) {
        if (alive(s) && configs != null && !configs.isEmpty())
            finish(s, "UNAVAILABLE", "Во время расшифровки обнаружена запись звука. Распознавание остановлено; оригинал сохранён.", null);
        // Empty public observations are a guard, never a universal no-hidden-mic guarantee.
    }
    private boolean alive(Session s) { return active == s && s.generation == generation && !s.cancelled.get(); }
    private boolean liveBeforeDeadline(Session s) {
        if (!alive(s)) return false;
        if (SystemClock.elapsedRealtime() >= s.deadline) { finish(s, "ERROR", "Время распознавания истекло. Оригинал сохранён; можно повторить.", null); return false; }
        return true;
    }
    private void finish(Session s, String status, String message, String text) {
        requireMain(); if (!alive(s)) return;
        active = null; ++generation;
        boolean clean = dispose(s);
        s.callback.accept(new NoteCoordinator.RecognitionResult(clean ? status : "ERROR",
                clean ? message : "Не удалось полностью завершить распознавание. Оригинал сохранён.", clean ? text : null));
    }
    private boolean dispose(Session s) {
        s.cancelled.set(true);
        if (s.timeout != null) main.removeCallbacks(s.timeout);
        if (s.poll != null) main.removeCallbacks(s.poll);
        boolean clean = true;
        SpeechRecognizer recognizer = s.recognizer; s.recognizer = null;
        if (recognizer != null) { try { recognizer.cancel(); } catch (RuntimeException error) { clean = false; } try { recognizer.destroy(); } catch (RuntimeException error) { clean = false; } }
        if (s.observing) { try { audio.unregisterAudioRecordingCallback(s.observer); } catch (RuntimeException error) { clean = false; } s.observing = false; }
        clean &= close(s.supportRead); clean &= close(s.supportWrite); clean &= close(s.actualRead); clean &= close(s.output);
        RandomAccessFile input = s.input;
        if (input != null) try { input.close(); } catch (IOException error) { clean = false; }
        if (s.writer != null) s.writer.interrupt();
        return clean;
    }
    private static boolean close(ParcelFileDescriptor file) { if (file == null) return true; try { file.close(); return true; } catch (IOException error) { return false; } }
    private void requireMain() { if (Looper.myLooper() != main.getLooper()) throw new IllegalStateException("File recognizer belongs to main owner"); }
    private static final class Session {
        final long generation;
        final Consumer<NoteCoordinator.RecognitionResult> callback;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final SegmentedFileTranscript transcript = new SegmentedFileTranscript();
        File wave;
        long expectedBytes, terminalNanos;
        volatile long written, eofBegin, deadline;
        volatile boolean eofClosed, writerDone;
        boolean segmentedEnd, observing;
        SpeechRecognizer recognizer;
        ParcelFileDescriptor supportRead, supportWrite, actualRead, output;
        volatile RandomAccessFile input;
        Thread writer;
        Runnable timeout, poll;
        AudioManager.AudioRecordingCallback observer;
        Session(long generation, Consumer<NoteCoordinator.RecognitionResult> callback) { this.generation = generation; this.callback = callback; }
    }
}

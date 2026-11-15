package ru.bububu.radio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.IOException;
import java.util.Locale;
import java.util.function.Consumer;

/** Checks the real system candidate. It never starts recognition or supplies a personal file. */
public final class AndroidOnDeviceProbe {
    public enum Status { UNAVAILABLE, ERROR, UNVERIFIED_FILE_SOURCE }
    public static final class Result {
        public final Status status;
        public final String message;
        public Result(Status status, String message) { this.status = status; this.message = message; }
    }

    private final Context context;
    private final Handler main;
    private long generation;
    private SpeechRecognizer recognizer;
    private ParcelFileDescriptor source;
    private Runnable timeout;
    private Consumer<Result> callback;

    public AndroidOnDeviceProbe(Context context, Handler main) {
        this.context = context.getApplicationContext(); this.main = main;
    }

    public void probe(Consumer<Result> consumer) {
        requireMain(); cancel(); callback = consumer;
        final long request = generation;
        if (Build.VERSION.SDK_INT < 33) {
            complete(request, Status.UNAVAILABLE, "Для проверки распознавания файлов нужен Android 13 или новее");
            return;
        }
        try {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                complete(request, Status.UNAVAILABLE, "Системное распознавание на устройстве недоступно");
                return;
            }
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsDb) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onResults(Bundle results) { }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }
                @Override public void onError(int error) {
                    complete(request, Status.ERROR, "Ошибка проверки системного распознавания: " + error);
                }
            });
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            source = pipe[0];
            pipe[1].close(); // Empty EOF, read-only end; no microphone, original, or generated speech.
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                    .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
            timeout = () -> complete(request, Status.ERROR, "Система не ответила на проверку за 10 секунд");
            main.postDelayed(timeout, 10_000);
            recognizer.checkRecognitionSupport(intent, command -> main.post(command), new RecognitionSupportCallback() {
                @Override public void onSupportResult(RecognitionSupport support) {
                    if (request != generation || callback == null) return;
                    if (support == null) {
                        complete(request, Status.ERROR, "Система вернула пустую проверку поддержки");
                        return;
                    }
                    if (!support.getOnlineLanguages().isEmpty()) {
                        complete(request, Status.ERROR, "Ответ on-device проверки содержит сетевые языки; распознавание заблокировано");
                        return;
                    }
                    boolean russian = false;
                    for (String tag : support.getInstalledOnDeviceLanguages()) {
                        if (tag != null && "ru".equals(Locale.forLanguageTag(tag).getLanguage())) russian = true;
                    }
                    if (!russian) {
                        complete(request, Status.UNAVAILABLE, "Установленный русский язык для этого запроса недоступен; модели не загружаются");
                        return;
                    }
                    complete(request, Status.UNVERIFIED_FILE_SOURCE,
                            "Русский on-device кандидат доступен. Чтение сохранённого файла не подтверждено; автотранскрибация заблокирована. Текст можно ввести вручную.");
                }
                @Override public void onError(int error) {
                    complete(request, error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                            || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ? Status.UNAVAILABLE : Status.ERROR,
                            "Системная проверка не подтверждена (код " + error + "); оригинал не передавался");
                }
            });
        } catch (IOException | RuntimeException error) {
            complete(request, Status.ERROR, "Не удалось проверить системный on-device кандидат; оригинал не передавался");
        }
    }

    public void cancel() {
        requireMain(); ++generation; callback = null;
        dispose();
    }

    private void complete(long request, Status status, String message) {
        requireMain();
        if (request != generation || callback == null) return;
        Consumer<Result> result = callback;
        callback = null; ++generation;
        dispose();
        result.accept(new Result(status, message));
    }

    private void dispose() {
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
        SpeechRecognizer old = recognizer; recognizer = null;
        if (old != null) {
            try { old.cancel(); } catch (RuntimeException ignored) { }
            try { old.destroy(); } catch (RuntimeException ignored) { }
        }
        ParcelFileDescriptor oldSource = source; source = null;
        if (oldSource != null) try { oldSource.close(); } catch (IOException ignored) { }
    }

    private void requireMain() {
        if (Looper.myLooper() != main.getLooper()) throw new IllegalStateException("Probe must run on main");
    }
}

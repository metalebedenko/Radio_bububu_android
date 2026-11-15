package ru.bububu.radio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import ru.bububu.radio.core.PcmWave;

/** Activity-visible note audio. The coordinator, including system radio commands, owns exclusion. */
public final class AndroidNoteAudio implements AutoCloseable {
    public interface FocusPort { boolean acquire(boolean recording); void release(); }

    public static final class RecordResult {
        public final String id;
        public final long durationMs;
        public final boolean hasAudio, interrupted, stoppedConfirmed;
        public final boolean discarded;
        public final String message;
        RecordResult(String id, long durationMs, boolean hasAudio, boolean interrupted, String message) {
            this(id, durationMs, hasAudio, interrupted, message, true);
        }
        RecordResult(String id, long durationMs, boolean hasAudio, boolean interrupted, String message, boolean stoppedConfirmed) {
            this(id,durationMs,hasAudio,interrupted,message,stoppedConfirmed,false);
        }
        RecordResult(String id,long durationMs,boolean hasAudio,boolean interrupted,String message,boolean stoppedConfirmed,boolean discarded) {
            this.id = id; this.durationMs = durationMs; this.hasAudio = hasAudio;
            this.interrupted = interrupted; this.message = message;
            this.stoppedConfirmed = stoppedConfirmed;
            this.discarded=discarded;
        }
    }

    public static final class OriginalEvent {
        public enum Kind { READY, PLAYING, ENDED, ERROR }
        public final Kind kind;
        public final String id;
        public final long positionMs, durationMs;
        public final String message;
        OriginalEvent(Kind kind, String id, long positionMs, long durationMs, String message) {
            this.kind = kind; this.id = id; this.positionMs = positionMs;
            this.durationMs = durationMs; this.message = message;
        }
    }

    private static final long START_RESERVE = 10L * 1024 * 1024;
    private static final long WRITE_RESERVE = 5L * 1024 * 1024;
    // Keep unknown native owners reachable and block every replacement Service in this process.
    private static final Set<AndroidNoteAudio> UNKNOWN_OUTPUT =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Object> PENDING_STORAGE =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Handler main;
    private final Context context;
    private final File directory;
    private final FocusPort focus;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private Recording recording;
    private boolean finalizing, focusOwned, closed;
    private long originalGeneration, originalPosition, originalDuration;
    private String originalId;
    private MediaPlayer original;
    private boolean prepared, originalStarted;
    private Runnable prepareTimeout;
    private Consumer<OriginalEvent> originalCallback;

    public AndroidNoteAudio(Context context, Handler main, File directory, FocusPort focus) {
        this.context = context.getApplicationContext();
        this.main = main; this.directory = directory; this.focus = focus;
    }

    public static boolean isOutputBlocked() {
        synchronized (UNKNOWN_OUTPUT) { return !UNKNOWN_OUTPUT.isEmpty(); }
    }
    /** An old Service must finish its original and durable callback before another book is loaded. */
    public static boolean isRecordingStorageBusy() {
        synchronized (PENDING_STORAGE) { return !PENDING_STORAGE.isEmpty(); }
    }

    public void start(String id, Consumer<RecordResult> callback) {
        requireMain(); validateId(id);
        if (isOutputBlocked() || isRecordingStorageBusy() || closed
                || recording != null || finalizing || original != null) {
            throw new IllegalStateException("Another note audio action is active");
        }
        Recording owner = new Recording(id, callback);
        recording = owner;
        markStoragePending(owner);
        try { writer.execute(() -> record(owner)); }
        catch (RuntimeException schedulingError) {
            recording = null; finalizing = false;
            clearStoragePending(owner);
            // No native input or focus was acquired: start may safely throw to roll back its draft.
            throw schedulingError;
        }
    }

    /** USER_STOP is a normal stop. Other reasons preserve the original as interrupted. */
    public void stopRecording(String reason) {
        requireMain();
        Recording owner = recording;
        if (owner == null) return;
        finalizing = true;
        synchronized (owner) {
            if (!owner.stopping) { owner.reason = reason == null ? "INTERRUPTED" : reason; owner.stopping = true; }
            if (owner.audio != null) {
                if (Build.VERSION.SDK_INT >= 29 && owner.captureCallback != null) {
                    try { owner.audio.unregisterAudioRecordingCallback(owner.captureCallback); }
                    catch (RuntimeException ignored) { /* Native release still confirms input shutdown. */ }
                }
                try { owner.audio.stop(); }
                catch (RuntimeException ignored) { /* release below still closes native input. */ }
                try { owner.audio.release(); }
                catch (RuntimeException error) { blockOutput(); throw error; }
                owner.audio = null;
                owner.released = true;
            }
        }
        if (owner.workerDone) {
            // A prior cleanup failure is retried explicitly; never unlock audio on an unknown stop.
            releaseFocus();
            recording = null; finalizing = false;
            clearConfirmedBlock();
            RecordResult prior = owner.completedResult;
            RecordResult confirmed = new RecordResult(owner.id, prior.durationMs, prior.hasAudio,
                    true, "Микрофон освобождён; доступный оригинал сохранён", true);
            RecordResult delivery=finishDiscard(owner,confirmed);
            Object retryCommit = new Object();
            markStoragePending(retryCommit);
            main.post(() -> deliverRecording(owner, delivery, retryCommit));
        }
    }

    public boolean isRecording() { requireMain(); return recording != null && !recording.stopping; }
    public boolean discardableTake(String id){requireMain();Recording o=recording;return o!=null&&o.id.equals(id)&&!o.stopping&&!o.committingOriginal&&o.partIdentity!=null&&!new File(directory,id+".wav").exists();}
    public boolean discardTake(String id,java.util.function.BooleanSupplier authority){
        requireMain();if(!discardableTake(id))return false;Recording o=recording;
        synchronized(o){if(o.stopping||o.committingOriginal)return false;o.discard=true;o.discardAuthority=authority;}
        stopRecording("USER_DISCARD");return true;
    }
    private RecordResult finishDiscard(Recording owner,RecordResult result){
        if(!owner.discardPrepared||!result.stoppedConfirmed)return result;
        try{
            boolean certain=owner.discardAuthority!=null&&owner.discardAuthority.getAsBoolean();
            ru.bububu.radio.core.PendingTake.discard(file(owner.id,".wav.part"),file(owner.id,".wav"),owner.partIdentity,true,certain);
            ru.bububu.radio.models.ModelsController.syncDirectory(directory);
            return new RecordResult(owner.id,0,false,false,"Несохранённая запись отменена",true,true);
        }catch(IOException|RuntimeException failure){return new RecordResult(owner.id,0,false,true,"Отмена не подтверждена; черновик и доступный временный фрагмент сохранены для проверки",true,false);}
    }
    public boolean isFinalizing() {
        requireMain(); return finalizing || recording != null && recording.stopping;
    }

    private void record(Recording owner) {
        PcmWave.Writer file = null;
        AudioRecord audio = null;
        AudioManager.AudioRecordingCallback captureCallback = null;
        long duration = 0;
        boolean hasAudio = false;
        String problem = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create notes directory");
            if (availableBytes() < START_RESERVE) throw new IOException("Недостаточно места для записи");
            file = new PcmWave.Writer(file(owner.id, ".wav.part"));
            try{owner.partIdentity=ru.bububu.radio.core.PendingTake.identity(file(owner.id,".wav.part"));}
            catch(IOException unprovable){owner.partIdentity=null; /* Normal stop/save remains available. */}
            int minimum = AudioRecord.getMinBufferSize(PcmWave.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("Формат микрофона недоступен");
            AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(PcmWave.SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(Math.max(minimum * 4, 6400));
            if (Build.VERSION.SDK_INT >= 30) builder.setPrivacySensitive(true);
            // Permission can be revoked after the Activity grant and before this worker runs.
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Microphone permission unavailable");
            }
            audio = builder.build();
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Микрофон не готов");
            if (Build.VERSION.SDK_INT >= 29) {
                final int audioSession = audio.getAudioSessionId();
                captureCallback = new AudioManager.AudioRecordingCallback() {
                    @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                        if (recording != owner) return;
                        for (AudioRecordingConfiguration config : configs) {
                            if (config.getClientAudioSessionId() == audioSession && config.isClientSilenced()) {
                                try { stopRecording("Микрофон прерван системой"); }
                                catch (RuntimeException ignored) { /* Worker retries; audio remains blocked. */ }
                                return;
                            }
                        }
                    }
                };
                audio.registerAudioRecordingCallback(command -> main.post(command), captureCallback);
            }
            synchronized (owner) {
                owner.audio = audio;
                owner.captureCallback = captureCallback;
            }
            AudioRecord microphone = audio;
            CountDownLatch startDelivered = new CountDownLatch(1);
            boolean queued = main.post(() -> {
                try {
                    synchronized (owner) {
                        if (recording != owner || closed || owner.stopping) return;
                        if (isOutputBlocked()) {
                            owner.reason = "ERROR"; owner.stopping = true;
                            owner.startProblem = "Остановка предыдущего аудио не подтверждена";
                            return;
                        }
                        // The root port checks visible Activity/page here, immediately before native Start.
                        // Count even an attempted acquisition so a partial failure is released afterwards.
                        focusOwned = true;
                        if (!focus.acquire(true)) {
                            owner.reason = "ERROR"; owner.stopping = true;
                            owner.startProblem = "Нет аудиофокуса или страница записи уже закрыта";
                            return;
                        }
                        microphone.startRecording();
                        owner.startedAt = SystemClock.elapsedRealtime();
                    }
                } catch (RuntimeException startError) {
                    synchronized (owner) {
                        owner.reason = "ERROR"; owner.stopping = true;
                        owner.startProblem = startError instanceof SecurityException
                                ? "Нет разрешения на микрофон" : "Не удалось начать запись";
                    }
                } finally { startDelivered.countDown(); }
            });
            if (!queued || !startDelivered.await(10, TimeUnit.SECONDS)) {
                synchronized (owner) {
                    if (!owner.stopping) { owner.reason = "ERROR"; owner.stopping = true; }
                }
                throw new IOException("Запуск микрофона не подтверждён за 10 секунд");
            }
            if (owner.startProblem != null) problem = owner.startProblem;
            long started = owner.startedAt, synced = started;
            short[] samples = new short[1600];
            byte[] buffer = new byte[3200];
            while (!owner.stopping) {
                if (SystemClock.elapsedRealtime() - started >= PcmWave.MAX_DURATION_MS
                        || file.pcmBytes() >= PcmWave.MAX_PCM_BYTES) {
                    synchronized (owner) {
                        if (!owner.stopping) { owner.reason = "LIMIT"; owner.stopping = true; }
                    }
                    break;
                }
                if (availableBytes() < WRITE_RESERVE) throw new IOException("Недостаточно места; сохранён записанный фрагмент");
                int count;
                try { count = audio.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING); }
                catch (RuntimeException readError) {
                    synchronized (owner) {
                        // Native release deliberately races the blocked read; this is a normal Stop.
                        if (owner.stopping) break;
                        owner.reason = "ERROR"; owner.stopping = true;
                    }
                    throw readError;
                }
                if (count > 0) count = PcmWave.encodePcm16(samples, count, buffer);
                if (owner.stopping) {
                    // A completed native read may contain the last valid samples before Stop.
                    int remaining = (int) Math.min(Math.max(0, count - count % 2),
                            PcmWave.MAX_PCM_BYTES - file.pcmBytes());
                    if (remaining > 0) file.append(buffer, 0, remaining);
                    break;
                }
                if (count < 0) {
                    synchronized (owner) {
                        if (owner.stopping) break;
                        owner.reason = "ERROR"; owner.stopping = true;
                    }
                    throw new IOException("Микрофон завершился с ошибкой " + count);
                }
                if (count == 0) { SystemClock.sleep(10); continue; }
                int usable = (int) Math.min(count - count % 2, PcmWave.MAX_PCM_BYTES - file.pcmBytes());
                if (usable > 0) file.append(buffer, 0, usable);
                if (SystemClock.elapsedRealtime() - synced >= 1000) { file.sync(); synced = SystemClock.elapsedRealtime(); }
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            owner.reason = "ERROR"; owner.stopping = true;
            problem = "Запуск записи был прерван; доступный оригинал сохранён";
        } catch (IOException | RuntimeException error) {
            problem = error instanceof SecurityException ? "Нет разрешения на микрофон"
                    : error instanceof IOException ? error.getMessage() : "Не удалось записать аудио";
            owner.reason = "ERROR"; owner.stopping = true;
        } finally {
            try {
                synchronized (owner) {
                    if (audio != null) {
                        if (Build.VERSION.SDK_INT >= 29 && captureCallback != null) {
                            try { audio.unregisterAudioRecordingCallback(captureCallback); } catch (RuntimeException ignored) { }
                        }
                        if (!owner.released) {
                            try { audio.stop(); } catch (RuntimeException ignored) { }
                            audio.release();
                            owner.released = true;
                        }
                        owner.audio = null;
                        owner.captureCallback = null;
                    }
                }
            } catch (RuntimeException releaseError) {
                synchronized (owner) { if (!owner.released) owner.audio = audio; }
                blockOutput();
                problem = "Не подтверждено освобождение микрофона; новое аудио заблокировано";
            }
            if (file != null) {
                try {
                    synchronized(owner){owner.committingOriginal=true;owner.discardPrepared=owner.discard&&(audio==null||owner.released)&&owner.partIdentity!=null;}
                    if(owner.discardPrepared)file.close();
                    else{PcmWave.Info result = file.finish(file(owner.id, ".wav"));duration = result.durationMs; hasAudio = result.pcmBytes > 0;}
                } catch (IOException error) {
                    owner.discardPrepared=false;
                    problem = "Не удалось завершить оригинал; доступный фрагмент оставлен для восстановления";
                    try { file.close(); } catch (IOException ignored) { }
                }
            }
        }
        boolean interrupted = !"USER_STOP".equals(owner.reason) && !"LIMIT".equals(owner.reason);
        String message = problem == null ? "LIMIT".equals(owner.reason) ? "Достигнут предел 2 минуты"
                : interrupted ? "Запись прервана; оригинал сохранён" : "Оригинал сохранён" : problem;
        boolean stoppedConfirmed = audio == null || owner.released;
        if (!stoppedConfirmed) {
            message = "Освобождение микрофона не подтверждено; новое аудио заблокировано. " + message;
        }
        RecordResult result = new RecordResult(owner.id, duration, hasAudio,
                interrupted || problem != null, message, stoppedConfirmed);
        main.post(() -> {
            if (recording != owner) { clearStoragePending(owner); return; }
            owner.workerDone = true;
            RecordResult delivered = result;
            if (result.stoppedConfirmed) {
                try {
                    releaseFocus();
                    delivered=finishDiscard(owner,result);
                    recording = null; finalizing = false;
                    clearConfirmedBlock();
                } catch (RuntimeException releaseError) {
                    finalizing = true;
                    delivered = new RecordResult(owner.id, result.durationMs, result.hasAudio, true,
                            "Освобождение аудиофокуса не подтверждено; новое аудио заблокировано", false);
                }
            } else finalizing = true;
            owner.completedResult = delivered;
            deliverRecording(owner, delivered, owner);
        });
    }

    public void play(String id, long positionMs, Consumer<OriginalEvent> callback) {
        requireMain(); validateId(id);
        if (isOutputBlocked() || isRecordingStorageBusy() || closed || recording != null || finalizing) {
            throw new IllegalStateException("Recording or unconfirmed audio shutdown is active");
        }
        pauseOriginal();
        originalId = id; originalPosition = Math.max(0, positionMs); originalDuration = 0;
        originalCallback = callback;
        long generation = ++originalGeneration;
        try {
            focusOwned = true;
            if (!focus.acquire(false)) { failOriginal(generation, "Аудиофокус недоступен"); return; }
            MediaPlayer player = new MediaPlayer();
            original = player;
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(file(id, ".wav").getAbsolutePath());
            player.setOnPreparedListener(value -> {
                if (!current(generation, value)) return;
                try {
                    prepared = true;
                    originalDuration = value.getDuration();
                    originalPosition = Math.min(originalPosition, originalDuration);
                    callback.accept(event(OriginalEvent.Kind.READY, ""));
                    if (!current(generation, value)) return;
                    if (originalPosition > 0) value.seekTo((int) originalPosition);
                    else startOriginal(generation, value);
                } catch (RuntimeException error) {
                    failOriginal(generation, "Не удалось подготовить оригинал");
                }
            });
            player.setOnSeekCompleteListener(value -> {
                if (current(generation, value)) startOriginal(generation, value);
            });
            player.setOnCompletionListener(value -> {
                if (!current(generation, value)) return;
                OriginalEvent ended = new OriginalEvent(OriginalEvent.Kind.ENDED, originalId,
                        originalDuration, originalDuration, "");
                try { pauseOriginal(); }
                catch (RuntimeException cleanupError) {
                    callback.accept(new OriginalEvent(OriginalEvent.Kind.ERROR, ended.id,
                            ended.positionMs, ended.durationMs,
                            "Остановка оригинала не подтверждена. Повторите Stop; новый звук заблокирован."));
                    return;
                }
                originalPosition = ended.positionMs;
                callback.accept(ended);
            });
            player.setOnErrorListener((value, what, extra) -> {
                if (current(generation, value)) failOriginal(generation, "Не удалось воспроизвести оригинал");
                return true;
            });
            prepareTimeout = () -> failOriginal(generation, "Оригинал не подготовлен за 10 секунд");
            main.postDelayed(prepareTimeout, 10_000);
            player.prepareAsync();
        } catch (IOException | RuntimeException error) {
            failOriginal(generation, "Оригинал недоступен или повреждён");
        }
    }

    private void startOriginal(long generation, MediaPlayer player) {
        if (!current(generation, player)) return;
        try {
            if (!originalStarted) { player.start(); originalStarted = true; }
            if (prepareTimeout != null) main.removeCallbacks(prepareTimeout);
            prepareTimeout = null;
            originalCallback.accept(event(OriginalEvent.Kind.PLAYING, ""));
        } catch (RuntimeException error) { failOriginal(generation, "Не удалось начать оригинал"); }
    }

    public long pauseOriginal() {
        requireMain();
        long position = currentPosition();
        ++originalGeneration;
        if (prepareTimeout != null) main.removeCallbacks(prepareTimeout);
        prepareTimeout = null;
        MediaPlayer player = original;
        if (player != null) {
            // release stops output even in PREPARING; stale prepared/seek callbacks cannot start it.
            try { player.release(); }
            catch (RuntimeException error) { blockOutput(); throw error; }
            original = null;
            prepared = false; originalStarted = false;
        }
        // Allows explicit retry if decoder stopped but abandoning its focus failed.
        if (recording == null) releaseFocus();
        clearConfirmedBlock();
        originalPosition = position;
        return position;
    }

    public void seekOriginal(long positionMs) {
        requireMain();
        originalPosition = Math.max(0, originalDuration > 0 ? Math.min(positionMs, originalDuration) : positionMs);
        if (original != null && prepared) {
            try { original.seekTo((int) originalPosition); }
            catch (RuntimeException error) { failOriginal(originalGeneration, "Не удалось перемотать оригинал"); }
        }
    }

    public long currentPosition() {
        requireMain();
        if (original != null && prepared && originalStarted) {
            try { originalPosition = original.getCurrentPosition(); } catch (IllegalStateException ignored) { }
        }
        return originalPosition;
    }
    public String getOriginalId() { requireMain(); return originalId; }
    public boolean isOriginalPlaying() { requireMain(); return original != null && originalStarted; }

    private void failOriginal(long generation, String message) {
        if (generation != originalGeneration) return;
        Consumer<OriginalEvent> callback = originalCallback;
        OriginalEvent failure = event(OriginalEvent.Kind.ERROR, message);
        try { pauseOriginal(); releaseFocus(); }
        catch (RuntimeException cleanupError) {
            // The synchronous Pause contract throws, but a platform callback must report safely.
            // Its retained decoder/focus and process latch remain available for explicit Stop retry.
            if (callback != null) callback.accept(new OriginalEvent(OriginalEvent.Kind.ERROR, failure.id,
                    failure.positionMs, failure.durationMs,
                    "Остановка оригинала не подтверждена. Повторите Stop; новый звук заблокирован."));
            return;
        }
        if (callback != null) callback.accept(failure);
    }
    private OriginalEvent event(OriginalEvent.Kind kind, String message) {
        return new OriginalEvent(kind, originalId, currentPosition(), originalDuration, message);
    }
    private boolean current(long generation, MediaPlayer player) {
        return !closed && generation == originalGeneration && original == player;
    }
    private void releaseFocus() {
        if (focusOwned) {
            try { focus.release(); }
            catch (RuntimeException error) { blockOutput(); throw error; }
            focusOwned = false;
        }
        clearConfirmedBlock();
    }
    private void blockOutput() {
        synchronized (UNKNOWN_OUTPUT) { UNKNOWN_OUTPUT.add(this); }
    }
    private void markStoragePending(Object token) {
        synchronized (PENDING_STORAGE) { PENDING_STORAGE.add(token); }
    }
    private void clearStoragePending(Object token) {
        synchronized (PENDING_STORAGE) { PENDING_STORAGE.remove(token); }
    }
    private void deliverRecording(Recording owner, RecordResult result, Object token) {
        try { owner.callback.accept(result); }
        finally { clearStoragePending(token); }
    }
    private void clearConfirmedBlock() {
        if (original != null || focusOwned) return;
        Recording owner = recording;
        if (owner != null) {
            synchronized (owner) { if (!owner.released) return; }
        }
        synchronized (UNKNOWN_OUTPUT) { UNKNOWN_OUTPUT.remove(this); }
    }
    private long availableBytes() { return new StatFs(directory.getAbsolutePath()).getAvailableBytes(); }
    private File file(String id, String extension) { validateId(id); return new File(directory, id + extension); }
    private static void validateId(String id) {
        if (id == null || !UUID.fromString(id).toString().equalsIgnoreCase(id)) throw new IllegalArgumentException("Invalid note ID");
    }
    private void requireMain() {
        if (Looper.myLooper() != main.getLooper()) throw new IllegalStateException("Note audio must be controlled on main");
    }
    @Override public void close() {
        requireMain();
        if (closed) return;
        pauseOriginal();
        stopRecording("APP_CLOSED");
        closed = true;
        writer.shutdown(); // The in-flight original still finalizes and reports its durable result.
    }

    private static final class Recording {
        final String id;
        final Consumer<RecordResult> callback;
        AudioRecord audio;
        AudioManager.AudioRecordingCallback captureCallback;
        boolean released;
        boolean workerDone;
        RecordResult completedResult;
        String startProblem;
        long startedAt;
        volatile boolean stopping;
        volatile boolean committingOriginal,discard,discardPrepared;
        volatile Object partIdentity;
        java.util.function.BooleanSupplier discardAuthority;
        volatile String reason = "USER_STOP";
        Recording(String id, Consumer<RecordResult> callback) { this.id = id; this.callback = callback; }
    }
}

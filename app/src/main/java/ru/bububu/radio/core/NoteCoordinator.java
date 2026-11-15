package ru.bububu.radio.core;

import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** One owner thread controls radio/recording/original; recognizer support never consumes mic. */
public final class NoteCoordinator {
    public interface Radio { void pause(); boolean outputStopped(); }
    public interface Recorder { void start(String id, Consumer<RecordingResult> ended); void stop(String reason);default boolean discardable(String id){return false;}default boolean discard(String id,java.util.function.BooleanSupplier authority){return false;} }
    public interface Original {
        void play(String id, long positionMs, Consumer<OriginalEvent> listener);
        long pause(); long position(); void seek(long positionMs);
    }
    public interface Recognition {
        void probe(Consumer<RecognitionResult> result);
        void cancel();
        default boolean supportsFileTranscription() { return false; }
        default boolean immutableLocalFile() { return false; }
        default void transcribe(String noteId, Consumer<RecognitionResult> result) { probe(result); }
        default void selectLocal(boolean selected) { if (selected) throw new IllegalStateException("Local recognition unavailable"); }
    }
    public interface Listener { void changed(); }
    public static final class RecordingResult {
        public final String id, message;
        public final long durationMs;
        public final boolean hasAudio, interrupted, stoppedConfirmed;
        public final boolean discarded;
        public RecordingResult(String id, long durationMs, boolean hasAudio, boolean interrupted, String message) {
            this(id, durationMs, hasAudio, interrupted, message, true);
        }
        public RecordingResult(String id, long durationMs, boolean hasAudio, boolean interrupted, String message, boolean stoppedConfirmed) {
            this(id,durationMs,hasAudio,interrupted,message,stoppedConfirmed,false);
        }
        public RecordingResult(String id,long durationMs,boolean hasAudio,boolean interrupted,String message,boolean stoppedConfirmed,boolean discarded) {
            this.id = id; this.durationMs = durationMs; this.hasAudio = hasAudio;
            this.interrupted = interrupted; this.message = message; this.stoppedConfirmed = stoppedConfirmed;this.discarded=discarded;
        }
    }
    public static final class OriginalEvent {
        public final String kind, message;
        public final long positionMs;
        public OriginalEvent(String kind, long positionMs, String message) {
            this.kind = kind; this.positionMs = positionMs; this.message = message;
        }
    }
    public static final class RecognitionResult {
        public final String status, message, text;
        public RecognitionResult(String status, String message) { this(status, message, null); }
        public RecognitionResult(String status, String message, String text) { this.status = status; this.message = message; this.text = text; }
    }
    private final Thread owner = Thread.currentThread();
    private final NoteBook book;
    private final Radio radio;
    private final Recorder recorder;
    private final Original original;
    private final Recognition recognition;
    private final Listener listener;
    private String recordingId, originalId, error;
    private boolean finalizing, originalPlaying, closed, stopUnconfirmed;
    private long originalGeneration, recognitionGeneration;
    private long audioCommandRevision;
    private String recognitionId;
    private boolean fileTranscribing, autoTranscriptionAllowed;
    private boolean immutableRecognition, discardRequested;
    private long takeRevision,takeCreatedAt;
    private final Set<String> dirtyDrafts = new HashSet<>();

    public NoteCoordinator(NoteBook book, Radio radio, Recorder recorder, Original original,
                           Recognition recognition, Listener listener) {
        this.book = Objects.requireNonNull(book); this.radio = Objects.requireNonNull(radio);
        this.recorder = Objects.requireNonNull(recorder); this.original = Objects.requireNonNull(original);
        this.recognition = Objects.requireNonNull(recognition); this.listener = Objects.requireNonNull(listener);
        if (recognition.supportsFileTranscription()) for (NoteBook.Note note : book.notes())
            if ("TRANSCRIBING".equals(note.recognitionStatus) || "CHECKING".equals(note.recognitionStatus)) {
                try { book.setRecognition(note.id, note.revision, "CANCELLED", "Расшифровка прервана. Оригинал сохранён; можно повторить.", null); }
                catch (RuntimeException failure) { error = "Состояние расшифровки не удалось восстановить. Оригинал сохранён."; }
            }
    }
    public String recordingId() { check(); return recordingId; }
    public String originalId() { check(); return originalId; }
    public String error() { check(); return error; }
    public boolean recordingBusy() { check(); return recordingId != null; }
    public boolean isFinalizing() { check(); return finalizing; }
    public boolean stopUnconfirmed() { check(); return stopUnconfirmed; }
    public boolean originalPlaying() { check(); return originalPlaying; }
    public boolean recognitionBusy() { check(); return fileTranscribing && recognitionId != null; }
    public String recognitionId() { check(); return recognitionId; }
    public boolean originalAllowed(){check();return !fileTranscribing||immutableRecognition;}
    public boolean discardableTake(){check();return recordingId!=null&&!dirtyDrafts.contains(recordingId)&&!finalizing&&!stopUnconfirmed&&book.pendingTakeStub(recordingId,takeRevision,takeCreatedAt)&&recorder.discardable(recordingId);}
    public boolean discardTake(){
        check();if(!discardableTake()){fail("Нельзя отменить уже сохранённую или неподтверждённую запись. Оригинал сохранён.");return false;}
        discardRequested=true;autoTranscriptionAllowed=false;finalizing=true;++audioCommandRevision;
        final String id=recordingId;
        try{if(!recorder.discard(id,()->!closed&&discardRequested&&id.equals(recordingId)&&!dirtyDrafts.contains(id)&&book.pendingTakeStub(id,takeRevision,takeCreatedAt))){discardRequested=false;finalizing=false;fail("Запись уже сохраняется. Дождитесь завершения; оригинал не удалён.");return false;}emit();return true;}
        catch(RuntimeException failure){stopUnconfirmed=true;fail("Остановка не подтверждена; запись сохранена для восстановления.");return false;}
    }

    /** Unsaved edits revoke a result just as a committed revision change does. */
    public void draftChanged(String id, boolean dirty) {
        check();
        if (dirty) { dirtyDrafts.add(id); if (id.equals(recognitionId)) { cancelRecognition(); emit(); } }
        else dirtyDrafts.remove(id);
    }
    public void cancelTranscription() { check(); if (fileTranscribing) { ++audioCommandRevision; cancelRecognition(); emit(); } }
    public boolean selectLocalRecognition(boolean selected) {
        check();
        if (closed || recordingBusy() || stopUnconfirmed) return false;
        cancelRecognition();
        try { recognition.selectLocal(selected); error = null; emit(); return true; }
        catch (RuntimeException unavailable) { fail("Не удалось выбрать распознавание. Прежний выбор сохранён."); return false; }
    }
    /** Passive decoder/recognition/file callbacks never advance this explicit choice revision. */
    public long audioCommandRevision() { check(); return audioCommandRevision; }
    public long originalPosition() {
        check(); return originalId == null ? 0 : original.position();
    }

    public boolean startRecording(String title, String station, String article, String version, long createdAt) {
        check();
        if (closed || recordingBusy()) return false;
        cancelRecognition();
        ++audioCommandRevision;
        if (!pauseOriginal()) return false;
        radio.pause();
        if (!radio.outputStopped()) { fail("Радио не подтвердило остановку. Запись не начата."); return false; }
        try {
            NoteBook.Note note = book.beginRecording(title, station, article, version, createdAt);
            recordingId = note.id; finalizing = false; error = null;
            takeRevision=note.revision;takeCreatedAt=note.createdAt;discardRequested=false;
            autoTranscriptionAllowed = true;
            recorder.start(note.id, this::recordingEnded);
            emit(); return true;
        } catch (RuntimeException failure) {
            if (recordingId != null) {
                String failed = recordingId; recordingId = null; finalizing = false;
                try { book.finishRecording(failed, 0, false, true, "Не удалось начать запись"); }
                catch (RuntimeException ignored) { /* Preserve the durable draft for recovery. */ }
            }
            fail("Не удалось начать или сохранить запись. Прежние данные сохранены."); return false;
        }
    }
    public void stopRecording(String reason) {
        check(); if (recordingId == null || (finalizing && !stopUnconfirmed)) return;
        ++audioCommandRevision;
        finalizing = true; emit();
        try { recorder.stop(reason); }
        catch (RuntimeException failure) { stopUnconfirmed = true; fail("Микрофон не подтвердил остановку. Новый звук заблокирован."); }
    }
    private void recordingEnded(RecordingResult result) {
        check();
        if (result == null || recordingId == null || !recordingId.equals(result.id)) return;
        String id = recordingId;
        if (!result.stoppedConfirmed) {
            finalizing = true; stopUnconfirmed = true;
            try { if(!discardRequested)book.finishRecording(id, result.durationMs, result.hasAudio, true, "Микрофон не подтвердил остановку"); }
            catch (RuntimeException ignored) { /* The original/draft remains on disk. */ }
            fail("Микрофон не подтвердил остановку. Повторите завершение; новый звук заблокирован."); return;
        }
        recordingId = null; finalizing = false; stopUnconfirmed = false;
        try {
            if(discardRequested&&result.discarded&&!result.hasAudio){
                try{if(dirtyDrafts.contains(id))throw new IllegalStateException("Manual draft appeared");book.discardPendingTakeStub(id,takeRevision,takeCreatedAt);error=null;}
                catch(RuntimeException failed){error="Запись отменена, но пустой черновик удалить не удалось. Другие записи не изменены.";}
                discardRequested=false;emit();return;
            }
            discardRequested=false;
            book.finishRecording(id, result.durationMs, result.hasAudio, result.interrupted, result.message);
            error = result.message;
            if (result.hasAudio && !closed) {
                if (recognition.supportsFileTranscription()) {
                    NoteBook.Note saved = book.find(id);
                    if (autoTranscriptionAllowed && saved != null && saved.text.trim().isEmpty() && !dirtyDrafts.contains(id)) transcribe(id, false);
                } else probeRecognition(id);
            }
        } catch (RuntimeException failure) {
            error = result.hasAudio?"Оригинал сохранён отдельно; карточку не удалось обновить. Откройте приложение для восстановления.":"Карточку записи не удалось обновить. Доступные файлы оставлены для восстановления.";
        }
        emit();
    }

    public boolean playOriginal(String id) {
        check();
        if (closed || recordingBusy() || stopUnconfirmed) return false;
        if (fileTranscribing&&!immutableRecognition){fail("Системная расшифровка ещё выполняется. Оригинал можно слушать после завершения.");return false;}
        NoteBook.Note note = book.find(id);
        if (note == null || !note.hasAudio) { fail("Оригинальная запись недоступна."); return false; }
        ++audioCommandRevision;
        if (!pauseOriginal()) return false;
        radio.pause();
        if (!radio.outputStopped()) { fail("Радио не подтвердило остановку. Оригинал не запущен."); return false; }
        long token = ++originalGeneration;
        originalId = id; originalPlaying = true; error = null;
        try {
            original.play(id, note.positionMs, event -> {
                check(); if (closed || token != originalGeneration || !id.equals(originalId)) return;
                if ("ENDED".equals(event.kind) || "ERROR".equals(event.kind)) {
                    originalPlaying = false;
                    try { book.setOriginalPosition(id, event.positionMs); }
                    catch (RuntimeException failure) { error = "Не удалось сохранить место оригинала."; }
                    if ("ERROR".equals(event.kind)) error = event.message;
                }
                emit();
            });
            emit(); return true;
        } catch (RuntimeException failure) {
            pauseOriginal(); fail("Не удалось запустить оригинальную запись."); return false;
        }
    }
    public boolean pauseOriginal() {
        check();
        ++originalGeneration;
        // A mic cleanup failure belongs to the recorder. An empty original player cannot
        // confirm that input stopped, or remove the user's explicit recorder retry action.
        if (originalId == null && (recordingBusy() || !stopUnconfirmed)) return true;
        ++audioCommandRevision;
        String id = originalId;
        try {
            long position = original.pause(); // Confirmation precedes the next audio owner.
            originalPlaying = false;
            if (id != null) book.setOriginalPosition(id, position);
            originalId = null; stopUnconfirmed = false;
            emit(); return true;
        } catch (RuntimeException failure) {
            originalPlaying = false; stopUnconfirmed = true;
            fail("Не удалось остановить оригинал или сохранить его место. Новый звук заблокирован."); return false;
        }
    }
    public void stopOriginal(String id) {
        check(); ++audioCommandRevision; if (!pauseOriginal()) return;
        try { book.setOriginalPosition(id, 0); error = null; emit(); }
        catch (RuntimeException failure) { fail("Не удалось сохранить начало оригинала."); }
    }
    public void seekOriginal(String id, long positionMs) {
        check(); if (recordingBusy()) return;
        ++audioCommandRevision;
        try {
            if (id.equals(originalId)) original.seek(positionMs);
            book.setOriginalPosition(id, positionMs); emit();
        } catch (RuntimeException failure) { fail("Не удалось перемотать оригинал."); }
    }
    /** Every production radio Play path, including preparation and system commands, uses this. */
    public boolean beforeRadioPlay() {
        check(); if (closed || recordingBusy() || stopUnconfirmed) return false;
        if (fileTranscribing) cancelTranscription();
        return pauseOriginal();
    }
    public void leaveNotes() { check(); autoTranscriptionAllowed = false; if (fileTranscribing) cancelTranscription(); stopRecording("Запись завершена при уходе"); pauseOriginal(); }
    public void searchChanged() { check(); if (fileTranscribing) cancelTranscription(); pauseOriginal(); }
    public void interrupt() { check(); autoTranscriptionAllowed = false; if (fileTranscribing) cancelTranscription(); ++audioCommandRevision; stopRecording("Запись прервана системой"); pauseOriginal(); }
    public void edit(String id, String title, String text) {
        check();
        cancelRecognition();
        try { book.edit(id, title, text); dirtyDrafts.remove(id); error = null; emit(); }
        catch (RuntimeException failure) { fail("Текст не сохранён. Проверьте объём и свободное место."); }
    }
    public void probeRecognition(String id) {
        check(); if (closed || recordingBusy()) return;
        NoteBook.Note note = book.find(id); if (note == null) return;
        cancelRecognition();
        long token = ++recognitionGeneration;
        recognitionId = id;
        try {
            book.setRecognition(id, note.revision, "CHECKING", "Проверяем локальное распознавание…", null);
            recognition.probe(result -> {
                check(); if (closed || token != recognitionGeneration) return;
                recognitionId = null;
                try { book.setRecognition(id, note.revision, result.status, result.message, null); }
                catch (RuntimeException failure) { error = "Не удалось сохранить статус распознавания."; }
                emit();
            });
        } catch (RuntimeException failure) {
            cancelRecognition();
            fail("Не удалось проверить локальное распознавание. Оригинал сохранён.");
        }
    }

    /** Exact saved original only; the UI supplies confirmation before replacing existing saved text. */
    public boolean transcribe(String id, boolean replaceConfirmed) {
        check();
        if (closed || recordingBusy() || stopUnconfirmed) return false;
        NoteBook.Note note = book.find(id);
        if (note == null || !note.hasAudio) { fail("Оригинальная запись недоступна."); return false; }
        if (dirtyDrafts.contains(id)) { fail("Сначала сохраните или отмените ручную правку."); return false; }
        if (!note.text.trim().isEmpty() && !replaceConfirmed) { fail("Подтвердите замену сохранённого текста."); return false; }
        if (!recognition.supportsFileTranscription()) { probeRecognition(id); return false; }
        cancelRecognition();
        ++audioCommandRevision;
        if (!pauseOriginal()) return false;
        if (!radio.outputStopped()) radio.pause();
        if (!radio.outputStopped()) { fail("Радио не подтвердило остановку. Расшифровка не начата."); return false; }
        long token = ++recognitionGeneration;
        long expectedRevision = note.revision;
        recognitionId = id; fileTranscribing = true;immutableRecognition=recognition.immutableLocalFile();
        try {
            if (!book.setRecognition(id, expectedRevision, "TRANSCRIBING", "Расшифровываем…", null)) {
                recognitionId = null; fileTranscribing = false; return false;
            }
            recognition.transcribe(id, result -> {
                check();
                if (closed || token != recognitionGeneration || !id.equals(recognitionId)) return;
                recognitionId = null; fileTranscribing = false;
                NoteBook.Note current = book.find(id);
                if (current == null || current.revision != expectedRevision || dirtyDrafts.contains(id)) return;
                try {
                    if (result == null) throw new IllegalArgumentException("Empty recognition result");
                    String text = result.text;
                    if ("READY".equals(result.status) && (text == null || text.trim().isEmpty() || text.length() > NoteBook.MAX_TEXT))
                        book.setRecognition(id, expectedRevision, "ERROR", "Полный текст не получен. Оригинал сохранён; можно повторить.", null);
                    else book.setRecognition(id, expectedRevision, result.status, result.message, "READY".equals(result.status) ? text : null);
                    error = null;
                } catch (RuntimeException failure) {
                    try { book.setRecognition(id, expectedRevision, "ERROR", null, null); } catch (RuntimeException preserved) { }
                    error = "Расшифровку не удалось сохранить. Оригинал сохранён.";
                }
                emit();
            });
            error = null; emit(); return true;
        } catch (RuntimeException failure) {
            cancelRecognition(); fail("Не удалось начать расшифровку. Оригинал сохранён."); return false;
        }
    }
    /** Revoke only the target's delayed recognition/original commands before file staging. */
    public void prepareDeletion(String id) {
        check();if(closed||recordingBusy()||stopUnconfirmed)throw new IllegalStateException("Сначала завершите запись и сохранение оригинала");
        ++audioCommandRevision;
        if(id.equals(recognitionId))cancelRecognition();
        if(id.equals(originalId)&&!pauseOriginal())throw new IllegalStateException("Остановка оригинальной записи не подтверждена");
        if(stopUnconfirmed)throw new IllegalStateException("Остановка оригинальной записи не подтверждена");
    }
    public void deletionCommitted(String id){check();if(book.find(id)!=null)throw new IllegalStateException("Note deletion is not committed");dirtyDrafts.remove(id);emit();}
    public void close() {
        check(); autoTranscriptionAllowed = false; ++audioCommandRevision; stopRecording("Запись прервана закрытием"); pauseOriginal();
        closed = true; cancelRecognition();
    }
    private void cancelRecognition() {
        ++recognitionGeneration; recognition.cancel();
        String id = recognitionId; recognitionId = null;
        boolean wasFile = fileTranscribing; fileTranscribing = false;immutableRecognition=false;
        NoteBook.Note note = id == null ? null : book.find(id);
        if (note != null && ("CHECKING".equals(note.recognitionStatus) || "TRANSCRIBING".equals(note.recognitionStatus))) {
            try { book.setRecognition(id, note.revision, wasFile ? "CANCELLED" : "UNAVAILABLE", wasFile ? "Расшифровка отменена. Оригинал сохранён; можно повторить." : "Проверка отменена; оригинал сохранён", null); }
            catch (RuntimeException failure) { error = "Не удалось сохранить отмену проверки распознавания."; }
        }
    }
    private void fail(String reason) { error = reason; emit(); }
    private void emit() { if (!closed) listener.changed(); }
    private void check() { if (Thread.currentThread() != owner) throw new IllegalStateException("Notes owner thread"); }
}

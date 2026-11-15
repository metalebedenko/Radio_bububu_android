package ru.bububu.radio.core;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Real Book/revision/immutable selections; native recording and installed file engine are the only fake ports. */
public final class NoteFileTranscriptionTest {
    @Test public void savedNewWaveAutoStartsAndCompleteResultInstallsOneRevisionWithoutAudioResume() {
        Rig r = new Rig(); String id = r.recordAndSave();
        assertTrue(r.core.recognitionBusy()); assertEquals(id, r.core.recognitionId());
        assertEquals("TRANSCRIBING", r.book.find(id).recognitionStatus);
        r.file.complete("READY", "Полный русский текст.");
        NoteBook.Note note = r.book.find(id);
        assertEquals("Полный русский текст.", note.text); assertEquals(1, note.revision);
        assertEquals("READY", note.recognitionStatus); assertFalse(r.core.recognitionBusy());
        assertTrue(note.hasAudio); assertEquals(3000, note.durationMs); assertEquals(0, r.original.plays);
        assertEquals(1, r.file.calls.size());
    }

    @Test public void failedSavedMetadataAndLeavingBeforeWaveCompletionNeverStartFileEngine() {
        Rig r = new Rig(); assertTrue(r.core.startRecording("Own", null, null, null, 1)); String id = r.core.recordingId();
        r.storage.fail = true; r.recorder.finish(id);
        assertTrue(r.file.calls.isEmpty()); assertEquals(NoteBook.RECORDING, r.book.find(id).recordingStatus);
        Rig leaving = new Rig(); assertTrue(leaving.core.startRecording("Own", null, null, null, 1)); id = leaving.core.recordingId();
        leaving.core.leaveNotes(); leaving.recorder.finish(id);
        assertTrue(leaving.file.calls.isEmpty()); assertTrue(leaving.book.find(id).hasAudio);
    }

    @Test public void unsavedDraftCancelsAndLateResultCannotOverwriteThenManualSaveRemainsExact() {
        Rig r = new Rig(); String id = r.recordAndSave(); Consumer<NoteCoordinator.RecognitionResult> old = r.file.latest();
        r.core.draftChanged(id, true);
        assertFalse(r.core.recognitionBusy()); assertEquals("CANCELLED", r.book.find(id).recognitionStatus);
        old.accept(new NoteCoordinator.RecognitionResult("READY", "Late", "Старый текст."));
        assertEquals("", r.book.find(id).text); assertEquals(0, r.book.find(id).revision);
        assertFalse(r.core.transcribe(id, true));
        r.core.edit(id, "Моя правка", "Мой несохранённый прежде текст.");
        assertEquals("Мой несохранённый прежде текст.", r.book.find(id).text);
        assertEquals(1, r.book.find(id).revision);
    }

    @Test public void confirmedReplacementPreservesQueuedOldRevisionOriginalPositionAndLink() {
        Rig r = new Rig(); String id = r.recordAndSave(); r.core.cancelTranscription();
        r.core.edit(id, "Own", "Первый ручной текст.");
        r.book.setOriginalPosition(id, 777);
        NoteBook.Material queued = r.book.addToAir(id);
        assertFalse(r.core.transcribe(id, false));
        assertTrue(r.core.transcribe(id, true));
        r.file.complete("READY", "Новый полный текст.");
        NoteBook.Note note = r.book.find(id); assertEquals(2, note.revision); assertEquals("Новый полный текст.", note.text);
        assertEquals(777, note.positionMs); assertEquals("A", note.linkedStation); assertEquals("A1", note.linkedArticle);
        assertEquals(queued.version, r.book.material(queued.stationId, queued.articleId).version);
        assertEquals("Первый ручной текст.", r.book.material(queued.stationId, queued.articleId).text);
    }

    @Test public void repeatedRetryAndNewRecordingInvalidateOldNoteAndGeneration() {
        Rig r = new Rig(); String first = r.recordAndSave(); Consumer<NoteCoordinator.RecognitionResult> old = r.file.latest();
        assertTrue(r.core.transcribe(first, false)); Consumer<NoteCoordinator.RecognitionResult> retry = r.file.latest();
        old.accept(new NoteCoordinator.RecognitionResult("READY", "Old", "Old.")); assertEquals("", r.book.find(first).text);
        assertTrue(r.core.startRecording("Second", null, null, null, 2)); String second = r.core.recordingId();
        retry.accept(new NoteCoordinator.RecognitionResult("READY", "Old retry", "Old retry.")); assertEquals("", r.book.find(first).text);
        r.recorder.finish(second); r.file.complete("READY", "Вторая заметка.");
        assertEquals("", r.book.find(first).text); assertEquals("CANCELLED", r.book.find(first).recognitionStatus);
        assertEquals("Вторая заметка.", r.book.find(second).text);
    }

    @Test public void invalidSuccessAndErrorPartialTextNeverInstallTextOrBumpRevision() {
        Rig r = new Rig(); String id = r.recordAndSave(); r.file.complete("READY", " ");
        assertEquals("ERROR", r.book.find(id).recognitionStatus); assertEquals(0, r.book.find(id).revision);
        assertTrue(r.core.transcribe(id, false)); r.file.complete("READY", "x".repeat(NoteBook.MAX_TEXT + 1));
        assertEquals("", r.book.find(id).text); assertEquals(0, r.book.find(id).revision);
        assertTrue(r.core.transcribe(id, false)); r.file.latest().accept(new NoteCoordinator.RecognitionResult("ERROR", "Ошибка", "Незавершённый текст."));
        assertEquals("", r.book.find(id).text); assertFalse(r.core.recognitionBusy());
    }

    @Test public void explicitRadioAndPageExitCancelFileWhileSystemOriginalWaitsWithoutLateAudio() {
        Rig r = new Rig(); String id = r.recordAndSave(); Consumer<NoteCoordinator.RecognitionResult> old = r.file.latest();
        assertTrue(r.core.beforeRadioPlay()); assertFalse(r.core.recognitionBusy());
        old.accept(new NoteCoordinator.RecognitionResult("READY", "Old", "Old.")); assertEquals("", r.book.find(id).text);
        assertTrue(r.core.transcribe(id, false)); old = r.file.latest(); assertFalse(r.core.playOriginal(id));
        assertTrue(r.core.recognitionBusy()); assertEquals(0, r.original.plays);
        assertFalse(r.core.originalPlaying());
        old = r.file.latest(); r.core.leaveNotes(); old.accept(new NoteCoordinator.RecognitionResult("READY", "Old", "Old."));
        assertEquals("", r.book.find(id).text); assertFalse(r.core.recognitionBusy()); assertFalse(r.core.originalPlaying());
    }

    @Test public void processRestartClearsWorkingStatusWithoutStartingOldRecordings() {
        Rig r = new Rig(); String id = r.recordAndSave(); FileEngine fresh = new FileEngine();
        new NoteCoordinator(new NoteBook(r.storage), r.radio, r.recorder, r.original, fresh, () -> { });
        assertEquals("CANCELLED", new NoteBook(r.storage).find(id).recognitionStatus); assertTrue(fresh.calls.isEmpty());
    }

    @Test public void deletedNoteRevokesItsLateTranscriptAndOriginalButKeepsAnotherNotesRecognition() {
        Rig r=new Rig();String id=r.recordAndSave();Consumer<NoteCoordinator.RecognitionResult> late=r.file.latest();
        r.core.prepareDeletion(id);assertFalse(r.core.recognitionBusy());assertTrue(r.book.applyDeletion(r.book.prepareDeleteNote(id,r.book.find(id).revision,null)));r.core.deletionCommitted(id);
        late.accept(new NoteCoordinator.RecognitionResult("READY","Late removed Note","Нельзя восстановить удалённый текст."));assertNull(r.book.find(id));assertNull(new NoteBook(r.storage).find(id));
        Rig original=new Rig();String originalId=original.recordAndSave();original.core.cancelTranscription();assertTrue(original.core.playOriginal(originalId));
        original.core.prepareDeletion(originalId);assertFalse(original.core.originalPlaying());assertNull(original.core.originalId());assertEquals(777,original.book.find(originalId).positionMs);assertTrue(original.book.applyDeletion(original.book.prepareDeleteNote(originalId,original.book.find(originalId).revision,null)));
        Rig other=new Rig();String recognizing=other.recordAndSave();NoteBook.Note removed=other.book.beginRecording("Other",null,null,null,2);other.book.finishRecording(removed.id,0,false,false,null);
        Consumer<NoteCoordinator.RecognitionResult> valid=other.file.latest();other.core.prepareDeletion(removed.id);assertTrue(other.core.recognitionBusy());assertTrue(other.book.applyDeletion(other.book.prepareDeleteNote(removed.id,removed.revision,null)));other.core.deletionCommitted(removed.id);
        valid.accept(new NoteCoordinator.RecognitionResult("READY","Other owner retained","Сохранён полный текст другой заметки."));assertEquals("Сохранён полный текст другой заметки.",other.book.find(recognizing).text);assertNull(other.book.find(removed.id));
        Rig recording=new Rig();assertTrue(recording.core.startRecording("Busy",null,null,null,3));byte[] before=recording.storage.load();assertThrows(IllegalStateException.class,()->recording.core.prepareDeletion(recording.core.recordingId()));assertArrayEquals(before,recording.storage.load());
    }

    @Test public void backendChoiceDefaultsToSystemAndSwitchCancelsOldResultBeforeLocalRetry() {
        Rig r = new Rig(); assertFalse(r.file.local); String id = r.recordAndSave();
        assertEquals(Boolean.FALSE, r.file.routes.get(0));
        Consumer<NoteCoordinator.RecognitionResult> old = r.file.latest();
        assertTrue(r.core.selectLocalRecognition(true)); assertTrue(r.file.local);
        assertFalse(r.core.recognitionBusy()); assertEquals("CANCELLED", r.book.find(id).recognitionStatus);
        assertTrue(r.core.transcribe(id, false)); assertEquals(Boolean.TRUE, r.file.routes.get(1));
        old.accept(new NoteCoordinator.RecognitionResult("READY", "Old system", "Old system."));
        assertTrue(r.core.recognitionBusy()); assertEquals("", r.book.find(id).text);
        r.file.complete("READY", "Local note."); assertEquals("Local note.", r.book.find(id).text);
        assertTrue(r.book.find(id).hasAudio); assertTrue(r.core.selectLocalRecognition(false)); assertFalse(r.file.local);
    }
    @Test public void localFailureDoesNotRetrySystemOrReplaceOriginalAndChoiceRemainsExplicit() {
        Rig r = new Rig(); assertTrue(r.core.selectLocalRecognition(true)); String id = r.recordAndSave();
        r.file.complete("ERROR", "Ignored partial");
        assertEquals(1, r.file.calls.size()); assertTrue(r.file.local);
        assertEquals("ERROR", r.book.find(id).recognitionStatus); assertEquals("", r.book.find(id).text);
        assertTrue(r.book.find(id).hasAudio); assertEquals(3000, r.book.find(id).durationMs);
        assertTrue(r.core.selectLocalRecognition(false)); assertTrue(r.core.transcribe(id, false));
        assertEquals(Boolean.FALSE, r.file.routes.get(1));
    }
    @Test public void unavailableLocalSelectionAndSelectionDuringRecordingLeavePreviousChoice() {
        Rig r = new Rig(); r.file.unavailable = true;
        assertFalse(r.core.selectLocalRecognition(true)); assertFalse(r.file.local);
        r.file.unavailable = false; assertTrue(r.core.startRecording("Own", null, null, null, 1));
        assertFalse(r.core.selectLocalRecognition(true)); assertFalse(r.file.local);
        r.recorder.finish(r.core.recordingId()); assertEquals(Boolean.FALSE, r.file.routes.get(0));
    }
    @Test public void localLateResultAfterBackendSwitchPageExitAndManualEditCannotOverwrite() {
        Rig r = new Rig(); assertTrue(r.core.selectLocalRecognition(true)); String id = r.recordAndSave();
        Consumer<NoteCoordinator.RecognitionResult> late = r.file.latest();
        r.core.leaveNotes(); late.accept(new NoteCoordinator.RecognitionResult("READY", "Late", "Late."));
        assertEquals("", r.book.find(id).text); assertTrue(r.book.find(id).hasAudio);
        assertTrue(r.core.transcribe(id, false)); late = r.file.latest();
        r.core.draftChanged(id, true); r.core.edit(id, "Own", "Manual text.");
        late.accept(new NoteCoordinator.RecognitionResult("READY", "Late", "Late."));
        assertEquals("Manual text.", r.book.find(id).text); assertEquals(1, r.book.find(id).revision);
        assertEquals(0, r.original.plays);
    }

    private static final class Store implements NoteBook.Storage {
        byte[] bytes; boolean fail;
        public byte[] load() { return bytes == null ? null : bytes.clone(); }
        public void save(byte[] value) { if (fail) throw new IllegalStateException("Disk failure"); bytes = value.clone(); }
    }
    private static final class Radio implements NoteCoordinator.Radio {
        boolean stopped = true;
        public void pause() { stopped = true; }
        public boolean outputStopped() { return stopped; }
    }
    private static final class Recorder implements NoteCoordinator.Recorder {
        Consumer<NoteCoordinator.RecordingResult> callback;
        public void start(String id, Consumer<NoteCoordinator.RecordingResult> callback) { this.callback = callback; }
        public void stop(String reason) { }
        void finish(String id) { callback.accept(new NoteCoordinator.RecordingResult(id, 3000, true, false, "Сохранена", true)); }
    }
    private static final class Original implements NoteCoordinator.Original {
        int plays;
        public void play(String id, long position, Consumer<NoteCoordinator.OriginalEvent> callback) { plays++; }
        public long pause() { return 777; }
        public long position() { return 777; }
        public void seek(long position) { }
    }
    private static final class FileEngine implements NoteCoordinator.Recognition {
        final List<Consumer<NoteCoordinator.RecognitionResult>> calls = new ArrayList<>();
        boolean local, unavailable; final List<Boolean> routes = new ArrayList<>();
        public void selectLocal(boolean selected) { if (selected && unavailable) throw new IllegalStateException("Unavailable"); local = selected; }
        public boolean supportsFileTranscription() { return true; }
        public void transcribe(String id, Consumer<NoteCoordinator.RecognitionResult> callback) { calls.add(callback); routes.add(local); }
        public void probe(Consumer<NoteCoordinator.RecognitionResult> callback) { throw new AssertionError("Production auto must use file path"); }
        public void cancel() { }
        Consumer<NoteCoordinator.RecognitionResult> latest() { return calls.get(calls.size() - 1); }
        void complete(String status, String text) { latest().accept(new NoteCoordinator.RecognitionResult(status, "Result", text)); }
    }
    private static final class Rig {
        final Store storage = new Store(); final NoteBook book = new NoteBook(storage);
        final Radio radio = new Radio(); final Recorder recorder = new Recorder(); final Original original = new Original(); final FileEngine file = new FileEngine();
        final NoteCoordinator core = new NoteCoordinator(book, radio, recorder, original, file, () -> { });
        String recordAndSave() {
            assertTrue(core.startRecording("Own", "A", "A1", "v1", 1)); String id = core.recordingId(); recorder.finish(id); return id;
        }
    }
}

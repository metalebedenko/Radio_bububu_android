package ru.bububu.radio.core;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;

/** Real coordinator/book/codec/player; only native audio, recognition and IO ports are fake. */
public final class NoteCoordinatorTest {
    @Test public void recordingPausesRadioAndBlocksAllRadioAndOriginalStartsThroughFinalization() {
        Rig r = new Rig(); String old = r.player.snapshot().currentRequestId;
        assertTrue(r.record()); assertFalse(r.player.snapshot().playing); assertTrue(r.player.isOutputStopped());
        assertFalse(r.coordinator.beforeRadioPlay()); assertFalse(r.coordinator.playOriginal(r.note.id));
        r.coordinator.stopRecording("USER_STOP"); assertTrue(r.coordinator.isFinalizing());
        assertFalse(r.coordinator.beforeRadioPlay());
        r.finish(true); assertFalse(r.coordinator.recordingBusy()); assertFalse(r.player.snapshot().playing);
        r.player.onStart(old); r.player.onDone(old); assertFalse(r.player.snapshot().playing);
    }
    @Test public void radioUnconfirmedStopDoesNotCreateDraftOrAcquireMicrophone() {
        Rig r = new Rig(); r.speech.stopFails = true;
        assertFalse(r.record()); assertTrue(r.book.notes().isEmpty()); assertEquals(0, r.recorder.starts);
        assertFalse(r.player.isOutputStopped());
    }
    @Test public void durableDraftFailureDoesNotStartRecorder() {
        Rig r = new Rig(); r.storage.fail = true;
        assertFalse(r.record()); assertEquals(0, r.recorder.starts); assertTrue(r.book.notes().isEmpty());
        assertFalse(r.player.snapshot().playing);
    }
    @Test public void savedOriginalSurvivesUnavailableRecognitionAndNeverStartsRadio() {
        Rig r = new Rig(); r.record(); r.finish(true);
        r.recognition.result.accept(new NoteCoordinator.RecognitionResult("UNAVAILABLE", "Русский недоступен"));
        assertTrue(r.book.find(r.note.id).hasAudio); assertEquals("UNAVAILABLE", r.book.find(r.note.id).recognitionStatus);
        assertEquals("", r.book.find(r.note.id).text); assertFalse(r.player.snapshot().playing);
    }
    @Test public void successfulLanguageQueryStillMarksFileSourceUnverified() {
        Rig r = new Rig(); r.record(); r.finish(true);
        r.recognition.result.accept(new NoteCoordinator.RecognitionResult("UNVERIFIED_FILE_SOURCE", "Нет подтверждения файла"));
        assertTrue(r.book.find(r.note.id).hasAudio); assertEquals("", r.book.find(r.note.id).text);
        assertEquals("UNVERIFIED_FILE_SOURCE", r.book.find(r.note.id).recognitionStatus);
    }
    @Test public void lateRecognitionAfterManualEditCannotOverwriteTextOrStatus() {
        Rig r = new Rig(); r.record(); r.finish(true); Consumer<NoteCoordinator.RecognitionResult> old = r.recognition.result;
        r.coordinator.edit(r.note.id, "Своя заметка", "Мой ручной текст.");
        String status = r.book.find(r.note.id).recognitionStatus;
        assertEquals("UNAVAILABLE", status); // An actually canceled query must not remain CHECKING.
        old.accept(new NoteCoordinator.RecognitionResult("ERROR", "Старое событие"));
        assertEquals("Мой ручной текст.", r.book.find(r.note.id).text);
        assertEquals(status, r.book.find(r.note.id).recognitionStatus);
    }
    @Test public void leavingNotesFinishesRecordingAndDoesNotResumeAnyPlayback() {
        Rig r = new Rig(); r.record(); r.coordinator.leaveNotes(); assertEquals("Запись завершена при уходе", r.recorder.reason);
        r.finish(true); assertFalse(r.player.snapshot().playing); assertFalse(r.coordinator.originalPlaying());
    }
    @Test public void wrongRecordingCallbackCannotUnlockCurrentRecording() {
        Rig r = new Rig(); r.record();
        r.recorder.ended.accept(new NoteCoordinator.RecordingResult("another", 3000, true, false, ""));
        assertTrue(r.coordinator.recordingBusy()); assertFalse(r.coordinator.beforeRadioPlay());
        r.finish(true); assertFalse(r.coordinator.recordingBusy());
    }
    @Test public void failedMicReleaseKeepsAudioBlockedUntilExplicitConfirmedRetry() {
        Rig r = new Rig(); r.record(); r.coordinator.stopRecording("USER_STOP");
        r.recorder.ended.accept(new NoteCoordinator.RecordingResult(r.note.id, 900, true, true, "Ошибка", false));
        assertTrue(r.coordinator.stopUnconfirmed()); assertTrue(r.coordinator.recordingBusy());
        assertTrue(r.book.find(r.note.id).hasAudio); assertFalse(r.coordinator.beforeRadioPlay());
        r.coordinator.stopRecording("USER_STOP"); assertEquals(2, r.recorder.stops);
        r.finish(true); assertFalse(r.coordinator.stopUnconfirmed()); assertTrue(r.coordinator.beforeRadioPlay());
    }
    @Test public void finalMetadataFailureLeavesDurableDraftForOriginalRecovery() {
        Rig r = new Rig(); r.record(); byte[] draft = r.storage.bytes.clone(); r.storage.fail = true;
        r.finish(true); assertNotNull(r.coordinator.error()); assertArrayEquals(draft, r.storage.bytes);
        r.storage.fail = false;
        NoteBook restored = new NoteBook(r.storage); assertEquals(NoteBook.RECORDING, restored.find(r.note.id).recordingStatus);
        restored.recoverOriginal(r.note.id, 2, 3000, true, "Восстановлена"); assertTrue(restored.find(r.note.id).hasAudio);
    }
    @Test public void leavingOrInterruptingAfterMicReleaseFailureKeepsRecorderRetryAvailable() {
        Rig r = new Rig(); r.record(); r.coordinator.stopRecording("USER_STOP");
        r.recorder.ended.accept(new NoteCoordinator.RecordingResult(r.note.id, 900, true, true, "Ошибка", false));
        r.coordinator.leaveNotes();
        assertTrue(r.coordinator.stopUnconfirmed()); assertTrue(r.coordinator.recordingBusy());
        assertFalse(r.coordinator.beforeRadioPlay());
        r.coordinator.interrupt(); assertTrue(r.coordinator.stopUnconfirmed());
        assertEquals(3, r.recorder.stops);
        r.finish(true); assertFalse(r.coordinator.stopUnconfirmed());
    }
    @Test public void originalPauseAndRadioPlayHaveIndependentTextAndMillisecondPositions() {
        Rig r = new Rig(); r.record(); r.finish(true); assertTrue(r.coordinator.playOriginal(r.note.id));
        r.original.position = 1200; int sentence = r.player.snapshot().sentenceIndex;
        assertTrue(r.coordinator.beforeRadioPlay()); r.player.play();
        assertEquals(1200, r.book.find(r.note.id).positionMs); assertEquals(sentence, r.player.snapshot().sentenceIndex);
        assertFalse(r.coordinator.originalPlaying()); assertTrue(r.player.snapshot().playing);
    }
    @Test public void originalStopReturnsToZeroWithoutResumingRadio() {
        Rig r = new Rig(); r.record(); r.finish(true); r.coordinator.playOriginal(r.note.id); r.original.position = 1700;
        r.coordinator.stopOriginal(r.note.id); assertEquals(0, r.book.find(r.note.id).positionMs);
        assertFalse(r.coordinator.originalPlaying()); assertFalse(r.player.snapshot().playing);
    }
    @Test public void seekOnPausedOriginalDoesNotStartBackendOrRadio() {
        Rig r = new Rig(); r.record(); r.finish(true); r.coordinator.seekOriginal(r.note.id, 800);
        assertEquals(800, r.book.find(r.note.id).positionMs); assertEquals(0, r.original.plays);
        assertFalse(r.player.snapshot().playing);
    }
    @Test public void oldPreparedOrEndedCallbackCannotAffectAnotherOriginal() {
        Rig r = new Rig(); r.record(); r.finish(true); String first = r.note.id;
        r.coordinator.playOriginal(first); Consumer<NoteCoordinator.OriginalEvent> old = r.original.callback;
        r.original.position = 700; r.coordinator.pauseOriginal();
        r.record(); r.finish(true); String second = r.note.id; r.coordinator.playOriginal(second);
        old.accept(new NoteCoordinator.OriginalEvent("ENDED", 3000, ""));
        assertTrue(r.coordinator.originalPlaying()); assertEquals(second, r.coordinator.originalId());
        assertEquals(700, r.book.find(first).positionMs);
    }
    @Test public void originalReleaseFailureBlocksNextRadioOrRecording() {
        Rig r = new Rig(); r.record(); r.finish(true); r.coordinator.playOriginal(r.note.id); r.original.pauseFails = true;
        assertFalse(r.coordinator.beforeRadioPlay()); assertTrue(r.coordinator.stopUnconfirmed());
        int starts = r.recorder.starts; assertFalse(r.record()); assertEquals(starts, r.recorder.starts);
        r.original.pauseFails = false; r.coordinator.pauseOriginal(); assertTrue(r.coordinator.beforeRadioPlay());
    }
    @Test public void completionAndFocusLossDoNotResumeRadioOrAnotherOriginal() {
        Rig r = new Rig(); r.record(); r.finish(true); r.coordinator.playOriginal(r.note.id);
        r.original.callback.accept(new NoteCoordinator.OriginalEvent("ENDED", 3000, ""));
        assertFalse(r.coordinator.originalPlaying()); assertFalse(r.player.snapshot().playing);
        r.coordinator.interrupt(); assertFalse(r.player.snapshot().playing);
    }
    @Test public void searchPausesHiddenOriginalAndPreservesPosition() {
        Rig r = new Rig(); r.record(); r.finish(true); r.coordinator.playOriginal(r.note.id); r.original.position = 400;
        r.coordinator.searchChanged(); assertFalse(r.coordinator.originalPlaying()); assertEquals(400, r.book.find(r.note.id).positionMs);
    }
    @Test public void closedCoordinatorCommitsRecordingResultWithoutStartingRecognitionOrSound() {
        Rig r = new Rig(); r.record(); r.coordinator.close(); int queries = r.recognition.queries;
        r.finish(true); assertTrue(r.book.find(r.note.id).hasAudio); assertEquals(queries, r.recognition.queries);
        assertFalse(r.player.snapshot().playing); assertFalse(r.coordinator.startRecording("Нет", null, null, null, 4));
    }
    @Test public void queuedApplicationPlayDoesNotPauseMoreRecentOriginalChoice() {
        Rig r = new Rig(); r.record(); r.finish(true);
        ApplicationPlayGate gate = new ApplicationPlayGate(r.coordinator::audioCommandRevision);
        assertTrue(r.coordinator.beforeRadioPlay()); String queued = gate.reserve();
        assertTrue(r.coordinator.playOriginal(r.note.id));
        assertFalse(gate.take(queued));
        assertEquals(r.note.id, r.coordinator.originalId()); assertTrue(r.coordinator.originalPlaying());
        assertFalse(r.player.snapshot().playing); assertEquals(1, r.original.plays);
    }
    @Test public void freshExplicitApplicationPlayAfterOriginalChoiceStillWorks() {
        Rig r = new Rig(); r.record(); r.finish(true);
        ApplicationPlayGate gate = new ApplicationPlayGate(r.coordinator::audioCommandRevision);
        String old = gate.reserve(); assertTrue(r.coordinator.playOriginal(r.note.id));
        r.original.position = 1200;
        assertFalse(gate.take(old)); assertTrue(r.coordinator.beforeRadioPlay());
        String fresh = gate.reserve(); assertTrue(gate.take(fresh));
        assertTrue(r.coordinator.beforeRadioPlay()); r.player.play();
        assertFalse(r.coordinator.originalPlaying()); assertEquals(1200, r.book.find(r.note.id).positionMs);
        assertTrue(r.player.snapshot().playing);
    }
    @Test public void laterOriginalStopAndPausedSeekCancelEarlierApplicationPlay() {
        Rig r = new Rig(); r.record(); r.finish(true);
        ApplicationPlayGate gate = new ApplicationPlayGate(r.coordinator::audioCommandRevision);
        String old = gate.reserve(); r.coordinator.stopOriginal(r.note.id); assertFalse(gate.take(old));
        old = gate.reserve(); r.coordinator.seekOriginal(r.note.id, 800); assertFalse(gate.take(old));
        assertEquals(800, r.book.find(r.note.id).positionMs); assertEquals(0, r.original.plays);
        assertFalse(r.player.snapshot().playing);
    }
    @Test public void newerRecordingMakesQueuedApplicationPlayStaleEvenAfterFinalization() {
        Rig r = new Rig(); ApplicationPlayGate gate = new ApplicationPlayGate(r.coordinator::audioCommandRevision);
        String old = gate.reserve(); assertTrue(r.record()); assertFalse(gate.take(old));
        assertTrue(r.coordinator.recordingBusy()); r.coordinator.stopRecording("USER_STOP"); r.finish(true);
        assertFalse(gate.take(old)); assertFalse(r.player.snapshot().playing);
    }
    @Test public void systemInterruptionAndClosingCancelQueuedApplicationPlay() {
        Rig r = new Rig(); ApplicationPlayGate gate = new ApplicationPlayGate(r.coordinator::audioCommandRevision);
        String old = gate.reserve(); r.coordinator.interrupt(); assertFalse(gate.take(old));
        old = gate.reserve(); r.coordinator.close(); assertFalse(gate.take(old));
        assertFalse(r.coordinator.beforeRadioPlay());
    }
    @Test public void passiveRecognitionAndTextEditsDoNotInvalidateExplicitRadioIntent() {
        Rig r = new Rig(); r.record(); r.finish(true);
        ApplicationPlayGate gate = new ApplicationPlayGate(r.coordinator::audioCommandRevision);
        String id = gate.reserve();
        r.recognition.result.accept(new NoteCoordinator.RecognitionResult("UNAVAILABLE", "Недоступно"));
        r.coordinator.edit(r.note.id, "Ручная правка", "Новый текст.");
        assertTrue(gate.take(id)); assertFalse(r.player.snapshot().playing);
    }
    @Test public void idlePageExitKeepsAlreadyExplicitlyRequestedBackgroundRadioPlay() {
        Rig r = new Rig(); r.record(); r.finish(true); r.coordinator.playOriginal(r.note.id);
        assertTrue(r.coordinator.beforeRadioPlay());
        ApplicationPlayGate gate = new ApplicationPlayGate(r.coordinator::audioCommandRevision);
        String id = gate.reserve(); r.coordinator.leaveNotes();
        assertTrue(gate.take(id)); assertTrue(r.coordinator.beforeRadioPlay()); r.player.play();
        assertTrue(r.player.snapshot().playing); assertFalse(r.coordinator.originalPlaying());
    }
    private static final class Storage implements NoteBook.Storage {
        byte[] bytes; boolean fail;
        @Override public byte[] load() { return bytes; }
        @Override public void save(byte[] value) { if (fail) throw new IllegalStateException("IO"); bytes = value.clone(); }
    }
    private static final class Speech implements PlayerController.SpeechEngine {
        boolean stopFails;
        @Override public void speak(String text, String id) { }
        @Override public void stop() { if (stopFails) throw new IllegalStateException("Native stop failed"); }
    }
    @Test public void immutableFileAsrContinuesDuringOriginalAndItsCompletionDoesNotChangeAudio(){
        Rig r=new Rig();r.recognition.file=true;r.recognition.local=true;r.record();r.finish(true);assertTrue(r.coordinator.recognitionBusy());Consumer<NoteCoordinator.RecognitionResult> done=r.recognition.result;assertTrue(r.coordinator.playOriginal(r.note.id));assertTrue(r.coordinator.recognitionBusy());r.coordinator.stopOriginal(r.note.id);assertTrue(r.coordinator.recognitionBusy());done.accept(new NoteCoordinator.RecognitionResult("READY",null,"Полная расшифровка"));assertEquals("Полная расшифровка",r.book.find(r.note.id).text);assertFalse(r.coordinator.originalPlaying());assertFalse(r.player.snapshot().playing);
    }
    @Test public void systemFileAsrBlocksOriginalAndDirtyRevisionRejectsLateResult(){
        Rig r=new Rig();r.recognition.file=true;r.record();r.finish(true);assertTrue(r.coordinator.recognitionBusy());assertFalse(r.coordinator.originalAllowed());assertFalse(r.coordinator.playOriginal(r.note.id));Consumer<NoteCoordinator.RecognitionResult> done=r.recognition.result;r.coordinator.draftChanged(r.note.id,true);done.accept(new NoteCoordinator.RecognitionResult("READY",null,"Поздний ответ"));assertEquals("",r.book.find(r.note.id).text);assertFalse(r.player.snapshot().playing);
    }
    @Test public void ownedPendingTakeRequiresNoManualDraftAndNoCommittedRevision(){
        Rig r=new Rig();r.recorder.disposable=true;r.record();assertTrue(r.coordinator.discardableTake());r.coordinator.draftChanged(r.note.id,true);assertFalse(r.coordinator.discardableTake());r.coordinator.draftChanged(r.note.id,false);assertTrue(r.coordinator.discardTake());assertTrue(r.recorder.discardAuthority.getAsBoolean());r.coordinator.draftChanged(r.note.id,true);assertFalse(r.recorder.discardAuthority.getAsBoolean());
        Rig clean=new Rig();clean.recorder.disposable=true;clean.record();assertTrue(clean.coordinator.discardTake());clean.recorder.ended.accept(new NoteCoordinator.RecordingResult(clean.note.id,0,false,false,"Отменена",true,true));assertNull(clean.book.find(clean.note.id));assertFalse(clean.coordinator.recordingBusy());assertFalse(clean.player.snapshot().playing);
        Rig edited=new Rig();edited.recorder.disposable=true;edited.record();edited.book.edit(edited.note.id,"Правка","Сохранённый текст");assertFalse(edited.coordinator.discardableTake());assertNotNull(edited.book.find(edited.note.id));
    }
    private static final class Recorder implements NoteCoordinator.Recorder {
        Consumer<NoteCoordinator.RecordingResult> ended; int starts, stops; String reason;boolean disposable;java.util.function.BooleanSupplier discardAuthority;
        @Override public boolean discardable(String id){return disposable;}
        @Override public boolean discard(String id,java.util.function.BooleanSupplier authority){discardAuthority=authority;stops++;return true;}
        @Override public void start(String id, Consumer<NoteCoordinator.RecordingResult> end) { starts++; ended = end; }
        @Override public void stop(String message) { stops++; reason = message; }
    }
    private static final class Original implements NoteCoordinator.Original {
        Consumer<NoteCoordinator.OriginalEvent> callback; long position; int plays; boolean pauseFails;
        @Override public void play(String id, long position, Consumer<NoteCoordinator.OriginalEvent> callback) { plays++; this.position = position; this.callback = callback; }
        @Override public long pause() { if (pauseFails) throw new IllegalStateException("Release"); return position; }
        @Override public long position() { return position; }
        @Override public void seek(long millis) { position = millis; }
    }
    private static final class Recognition implements NoteCoordinator.Recognition {
        Consumer<NoteCoordinator.RecognitionResult> result; int queries;boolean file,local;
        @Override public boolean supportsFileTranscription(){return file;}
        @Override public boolean immutableLocalFile(){return local;}
        @Override public void probe(Consumer<NoteCoordinator.RecognitionResult> result) { queries++; this.result = result; }
        @Override public void cancel() { }
    }
    private static final class Rig {
        final Storage storage = new Storage(); final NoteBook book = new NoteBook(storage);
        final Speech speech = new Speech(); final Recorder recorder = new Recorder();
        final Original original = new Original(); final Recognition recognition = new Recognition();
        final PlayerController player; final NoteCoordinator coordinator; NoteBook.Note note;
        Rig() {
            player = new PlayerController(Arrays.asList(new Station("A", "A", Arrays.asList(Article.fromSentences("A1", "v1", "Заголовок", "Первое.", "Второе.")))),
                    speech, (delay, task) -> () -> { }, new PlayerController.StateStore() {
                        @Override public PlayerController.SavedState load() { return null; }
                        @Override public void save(PlayerController.SavedState state) { }
                    }, state -> { }, 1000);
            coordinator = new NoteCoordinator(book, new NoteCoordinator.Radio() {
                @Override public void pause() { player.pause(); }
                @Override public boolean outputStopped() { return player.isOutputStopped(); }
            }, recorder, original, recognition, () -> { });
            player.play();
        }
        boolean record() {
            boolean started = coordinator.startRecording("Ёлка", "A", "A1", "v1", 1);
            if (started) note = book.find(coordinator.recordingId());
            return started;
        }
        void finish(boolean audio) { recorder.ended.accept(new NoteCoordinator.RecordingResult(note.id, audio ? 3000 : 0, audio, false, "Сохранена")); }
    }
}

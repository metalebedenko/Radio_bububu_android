package ru.bububu.radio;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.NoteBook;
import ru.bububu.radio.core.PcmWave;
import ru.bububu.radio.core.PlayerController;
import ru.bububu.radio.core.Station;

/**
 * Device-only integration inputs. Synthetic PCM contains a quiet tone, no speech or microphone.
 * ActivityScenario creates the only service binding. A Russian local TTS/focus/decoder failure
 * is a failure, never a skip. Probe PASS means safe support checking, never transcription.
 * Run only while the owner makes no concurrent edits; private metadata is restored byte for byte.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class NotesStorageAndAudioTest {
    private static final long STATE_TIMEOUT = 10_000;
    private static final long TTS_TIMEOUT = 25_000;
    private Instrumentation instrumentation;
    private Context context;
    private ActivityScenario<RadioActivity> scenario;
    private RadioActivity activity;
    private RadioService service;
    private File isolatedRoot;
    private AndroidNoteStorage productionStorage;
    private byte[] previousNotes, previousPlayer;
    private boolean backedUp, notesDirectoryExisted;
    private final List<String> ownIds = new ArrayList<>();
    private String label = "notes", currentSyntheticId;

    // Executed R6/R8 regressions transferred from the independent test05 harness.
    // They use only isolatedStorage(), never mic or personal files; faults must be real EACCES.
    @Test public void unreadableExistingMetadataMustFailClosedWithoutReplacingSavedBook() {
        label = "audit-unreadable-metadata";
        main(() -> {
            AndroidNoteStorage storage = isolatedStorage();
            NoteBook book = new NoteBook(storage);
            NoteBook.Note note = book.beginRecording("Синтетический заголовок", "A", "A1", "v1", 123);
            book.edit(note.id, "Сохранённый вымышленный заголовок", "Сохранённый вымышленный текст.");
            writeWave(storage.directory, note.id, 1000);
            book.finishRecording(note.id, 1000, true, false, null);
            NoteBook.Material selected = book.addToAir(note.id);
            byte[] committed = storage.load();
            File metadata = new File(storage.directory, "notes-v1.bin");
            File original = new File(storage.directory, note.id + ".wav");
            byte[] originalHash = hash(original);
            assertTrue(metadata.isFile());
            assertNotNull(committed);
            assertEquals("Fixture must be readable before permission fault", 1, new NoteBook(storage).materials().size());
            int initialMode = android.system.Os.stat(metadata.getAbsolutePath()).st_mode & 0777;
            boolean rejected = false;
            try {
                android.system.Os.chmod(metadata.getAbsolutePath(), initialMode & ~0444);
                assertTrue("The metadata must still exist; this must not test an absent file", metadata.isFile());
                try (FileInputStream unexpected = new FileInputStream(metadata)) {
                    fail("Fault precondition failed: existing metadata is still readable");
                } catch (FileNotFoundException denied) {
                    Throwable cause = denied.getCause();
                    assertTrue("Must observe a real Android EACCES, not another missing-file failure: " + denied,
                            cause instanceof android.system.ErrnoException
                                    && ((android.system.ErrnoException) cause).errno == android.system.OsConstants.EACCES);
                }
                NoteBook loaded = null;
                try { loaded = new NoteBook(storage); }
                catch (IllegalStateException expectedReadFailure) { rejected = true; }
                if (!rejected) {
                    // Follow the exact production service path only in this isolated directory.
                    // On the faulty implementation this adopts the WAV and overwrites metadata.
                    storage.recover(loaded);
                }
            } finally {
                android.system.Os.chmod(metadata.getAbsolutePath(), initialMode);
            }
            NoteBook afterFault = new NoteBook(storage);
            android.os.Bundle storageProof = new android.os.Bundle();
            storageProof.putString("stream", "\nEXISTING_METADATA_FAULT_PROOF eaccesPrecondition=true rejected=" + rejected
                    + " beforeNotes=1 beforeMaterials=1 afterNotes=" + afterFault.notes().size()
                    + " afterMaterials=" + afterFault.materials().size()
                    + " metadataUnchanged=" + Arrays.equals(committed, storage.load())
                    + " audioUnchanged=" + Arrays.equals(originalHash, hash(original)) + "\n");
            instrumentation.sendStatus(0, storageProof);
            assertArrayEquals("Unreadable metadata must not be replaced by an empty/orphan-only book", committed, storage.load());
            assertArrayEquals("The synthetic original must remain unchanged", originalHash, hash(original));
            assertTrue("An existing unreadable metadata file must reject loading, not count as first launch", rejected);
            NoteBook restored = new NoteBook(storage);
            NoteBook.Note saved = restored.find(note.id);
            assertNotNull(saved);
            assertEquals("Сохранённый вымышленный заголовок", saved.title);
            assertEquals("Сохранённый вымышленный текст.", saved.text);
            assertEquals("A", saved.linkedStation);
            assertEquals("A1", saved.linkedArticle);
            assertEquals("v1", saved.linkedVersion);
            assertEquals(1, restored.materials().size());
            assertEquals(selected.articleId, restored.materials().get(0).articleId);
            assertEquals(selected.sequence, restored.materials().get(0).sequence);
        });
    }

    @Test public void transientUnreadableWaveMustBecomePlayableAfterAccessReturnsAndRecoveryRepeats() {
        label = "audit-transient-wav-read";
        main(() -> {
            AndroidNoteStorage storage = isolatedStorage();
            NoteBook book = new NoteBook(storage);
            NoteBook.Note draft = book.beginRecording("Синтетический draft", "A", "A1", "v1", 456);
            book.edit(draft.id, "Сохранённый заголовок draft", "Сохранённый вымышленный текст draft.");
            NoteBook.Material selected = book.addToAir(draft.id);
            writeWave(storage.directory, draft.id, 1000);
            // A real crash window: WAV has finalized, final metadata still says RECORDING.
            File original = new File(storage.directory, draft.id + ".wav");
            assertEquals(1000, PcmWave.readDuration(original));
            assertEquals(NoteBook.RECORDING, new NoteBook(storage).find(draft.id).recordingStatus);
            byte[] originalHash = hash(original);
            int initialMode = android.system.Os.stat(original.getAbsolutePath()).st_mode & 0777;
            try {
                android.system.Os.chmod(original.getAbsolutePath(), initialMode & ~0444);
                assertTrue("The original must still exist during the fault", original.isFile());
                try (FileInputStream unexpected = new FileInputStream(original)) {
                    fail("Fault precondition failed: synthetic WAV is still readable");
                } catch (FileNotFoundException denied) {
                    Throwable cause = denied.getCause();
                    assertTrue("Must observe a real Android EACCES, not another missing-file failure: " + denied,
                            cause instanceof android.system.ErrnoException
                                    && ((android.system.ErrnoException) cause).errno == android.system.OsConstants.EACCES);
                }
                storage.recover(book);
            } finally {
                android.system.Os.chmod(original.getAbsolutePath(), initialMode);
            }
            assertEquals("Read access must really be restored before the second recovery", 1000, PcmWave.readDuration(original));
            NoteBook reopened = new NoteBook(storage);
            storage.recover(reopened);
            NoteBook.Note recovered = reopened.find(draft.id);
            assertNotNull(recovered);
            assertEquals("Сохранённый заголовок draft", recovered.title);
            assertEquals("Сохранённый вымышленный текст draft.", recovered.text);
            assertEquals(1, recovered.revision);
            assertEquals("A", recovered.linkedStation);
            assertEquals("A1", recovered.linkedArticle);
            assertEquals("v1", recovered.linkedVersion);
            assertEquals(1, reopened.materials().size());
            assertEquals(selected.articleId, reopened.materials().get(0).articleId);
            assertEquals(selected.sequence, reopened.materials().get(0).sequence);
            assertArrayEquals("Recovery must preserve synthetic audio bytes", originalHash, hash(original));
            android.os.Bundle waveProof = new android.os.Bundle();
            waveProof.putString("stream", "\nTRANSIENT_WAV_FAULT_PROOF eaccesPrecondition=true readableAgain=true"
                    + " diskDuration=" + PcmWave.readDuration(original)
                    + " cardDuration=" + recovered.durationMs + " hasAudio=" + recovered.hasAudio
                    + " audioUnchanged=" + Arrays.equals(originalHash, hash(original)) + "\n");
            instrumentation.sendStatus(0, waveProof);
            assertTrue("Valid WAV must regain hasAudio after a transient read failure", recovered.hasAudio);
            assertEquals("Card duration must match the recovered WAV", 1000, recovered.durationMs);
            assertEquals(NoteBook.INTERRUPTED, recovered.recordingStatus);
            byte[] recoveredBytes = storage.load();
            storage.recover(reopened);
            assertArrayEquals("Recovery after restored access must be idempotent", recoveredBytes, storage.load());
        });
    }

    /** Real AMS delivery; synthetic tone only. */
    @Test public void queuedApplicationPlayMustNotOverrideLaterOriginalChoice() {
        label = "queued-app-play-original-order";
        launchService();
        showNotes();
        final long durationMs = 90_000;
        final String id = syntheticNote(durationMs);
        final byte[] originalHash = mainValue(() -> hash(originalFile(id)));
        awaitLocalVoice();

        // Establish both real output prerequisites, including accepted focus and native clocks.
        // This first APP_PLAY must finish delivery before the command-order scenario is staged.
        main(() -> {
            assertFalse(activity.isFinishing()); assertFalse(activity.isDestroyed());
            assertTrue((boolean) field(activity, "activityStarted"));
            assertTrue((boolean) field(activity, "bound"));
            assertEquals(3, (int) field(activity, "selectedTab"));
            assertTrue((boolean) field(service, "activityVisible"));
            assertTrue((boolean) field(service, "notesPage"));
            assertSame(service, activity.serviceForNotes());
            assertFalse(noteAudio().isRecording()); assertFalse(noteAudio().isFinalizing());
            service.player().pause(); service.player().selectArticle("B", "B2");
            service.player().seekToFraction(0); service.requestPlay();
        });
        awaitRadioStarted();
        main(() -> {
            service.player().pause();
            assertTrue(service.player().isOutputStopped());
            assertTrue(service.notes().playOriginal(id));
        });
        awaitOriginalStarted(200);
        main(() -> {
            assertNotNull("Original must have acquired a real focus request", field(service, "noteFocus"));
            assertEquals(id, service.notes().originalId());
            assertTrue(noteAudio().currentPosition() < durationMs - 10_000);
            service.notes().stopOriginal(id);
            assertEquals(0, service.notesBook().find(id).positionMs);
            assertFalse(noteAudio().isOriginalPlaying());
            assertNull(service.notes().originalId());
            assertTrue(service.player().isOutputStopped());
        });
        instrumentation.waitForIdleSync();

        final int[] beforeDelivery = new int[1];
        final int[] eventBaseline = new int[1];
        final long[] stagedAt = new long[1];
        // One owner-looper turn: Android cannot deliver onStartCommand between these commands.
        main(() -> {
            ActivityManager.RunningServiceInfo running = ownRunningService();
            assertNotNull(running);
            assertEquals("Only the visible Activity owns the ordinary binding", 1, running.clientCount);
            assertTrue((boolean) field(activity, "activityStarted"));
            assertEquals(3, (int) field(activity, "selectedTab"));
            assertTrue((boolean) field(service, "activityVisible"));
            assertTrue((boolean) field(service, "notesPage"));
            assertFalse((boolean) field(service, "destroyed"));
            assertFalse(AndroidNoteAudio.isOutputBlocked());
            assertFalse(AndroidNoteAudio.isRecordingStorageBusy());
            assertFalse(service.notes().recordingBusy());
            assertFalse(noteAudio().isRecording()); assertFalse(noteAudio().isFinalizing());
            assertTrue(service.player().isOutputStopped());
            ru.bububu.radio.core.StartedRequestOwner starts =
                    (ru.bububu.radio.core.StartedRequestOwner) field(service, "startedRequests");
            beforeDelivery[0] = starts.captureDeliveredId();
            assertTrue("Preflight APP_PLAY must already have been delivered", beforeDelivery[0] > 0);
            eventBaseline[0] = ((List<?>) field(service, "events")).size();
            stagedAt[0] = SystemClock.uptimeMillis();
            service.requestPlay(); // The sole new real startForegroundService request in this scenario.
            assertEquals("APP_PLAY must still be queued in this same main callback",
                    beforeDelivery[0], starts.captureDeliveredId());
            assertTrue("The newer explicit original command must be accepted", service.notes().playOriginal(id));
            assertEquals(id, service.notes().originalId());
            assertTrue(service.notes().originalPlaying());
            assertFalse(service.player().snapshot().playing);
            assertEquals("No manual dispatch or reentrant AMS callback is allowed",
                    beforeDelivery[0], starts.captureDeliveredId());
        });

        await("Actual Android delivery of the queued APP_PLAY startId", STATE_TIMEOUT,
                () -> mainValue(() -> ((ru.bububu.radio.core.StartedRequestOwner)
                        field(service, "startedRequests")).captureDeliveredId() > beforeDelivery[0]));
        final int delivered = mainValue(() -> ((ru.bububu.radio.core.StartedRequestOwner)
                field(service, "startedRequests")).captureDeliveredId());
        evidence("real-app-play-delivered");

        // Do not count a merely scheduled decoder as success. On test05, wait for the wrong TTS
        // onStart as well, so failure evidence distinguishes actual start from a pending request.
        await("An actual advancing original clock or actual erroneous TTS onStart", TTS_TIMEOUT,
                () -> mainValue(() -> {
                    PlayerController.Snapshot radio = service.player().snapshot();
                    return (id.equals(service.notes().originalId()) && service.notes().originalPlaying()
                            && noteAudio().isOriginalPlaying() && noteAudio().currentPosition() >= 200)
                            || (radio.playing && radio.currentRequestId != null
                            && "Читаем на телефоне".equals(radio.status));
                }));
        evidence("audio-start-after-real-delivery");
        main(() -> {
            PlayerController.Snapshot radio = service.player().snapshot();
            android.os.Bundle proof = new android.os.Bundle();
            proof.putString("stream", "\nQUEUED_APP_PLAY_PROOF beforeStartId=" + beforeDelivery[0]
                    + " deliveredStartId=" + delivered
                    + " elapsedMs=" + (SystemClock.uptimeMillis() - stagedAt[0])
                    + " originalSelected=" + id.equals(service.notes().originalId())
                    + " originalEnginePlaying=" + noteAudio().isOriginalPlaying()
                    + " originalPositionMs=" + noteAudio().currentPosition()
                    + " radioPlaying=" + radio.playing
                    + " actualTtsOnStart=" + (radio.playing && radio.currentRequestId != null
                    && "Читаем на телефоне".equals(radio.status))
                    + " recording=" + noteAudio().isRecording() + "\n");
            instrumentation.sendStatus(0, proof);
            assertTrue("A natural 90-second WAV ending cannot explain the result",
                    SystemClock.uptimeMillis() - stagedAt[0] < durationMs - 10_000);
            assertFalse(noteAudio().isRecording()); assertFalse(noteAudio().isFinalizing());
            assertEquals("A previously queued APP_PLAY must not revoke the later original choice",
                    id, service.notes().originalId());
            assertTrue(service.notes().originalPlaying()); assertTrue(noteAudio().isOriginalPlaying());
            assertFalse("Older queued radio must remain paused", radio.playing);
            assertNull(radio.currentRequestId);
            assertTrue(service.player().isOutputStopped());
        });

        assertFor("The newer original retains audio after confirmed APP_PLAY delivery", 2_000,
                () -> mainValue(() -> id.equals(service.notes().originalId())
                        && service.notes().originalPlaying() && noteAudio().isOriginalPlaying()
                        && noteAudio().currentPosition() < durationMs - 10_000
                        && service.player().isOutputStopped()
                        && !noteAudio().isRecording() && !noteAudio().isFinalizing()));
        main(() -> {
            assertEquals("No later unrelated start may satisfy the delivery observation", delivered,
                    ((ru.bububu.radio.core.StartedRequestOwner) field(service, "startedRequests")).captureDeliveredId());
            @SuppressWarnings("unchecked") List<String> events = (List<String>) field(service, "events");
            assertTrue("Scenario trace must not have rolled out", events.size() >= eventBaseline[0]);
            for (String event : events.subList(eventBaseline[0], events.size())) {
                assertFalse("Even a transient old radio request must not occur: " + event, event.contains(" request "));
            }
            assertArrayEquals(originalHash, hash(originalFile(id)));
        });
        evidence("newer-original-retained");
        // The existing unmodified @After performs shutdown, byte-for-byte restore and UUID cleanup.
    }

    @Before public void prepare() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        instrumentation.getUiAutomation().dropShellPermissionIdentity();
    }

    @After public void cleanUp() {
        try {
            if (service != null) evidence("before-cleanup");
            closeService();
            main(() -> {
                if (backedUp) {
                    assertFalse("Unconfirmed native output prevents safe fixture cleanup", AndroidNoteAudio.isOutputBlocked());
                    restoreAtomic(new File(productionStorage.directory, "notes-v1.bin"), previousNotes);
                    restoreAtomic(new File(context.getFilesDir(), "player-state-v1.json"), previousPlayer);
                    for (String id : ownIds) {
                        deleteOwn(new File(productionStorage.directory, id + ".wav"));
                        deleteOwn(new File(productionStorage.directory, id + ".wav.part"));
                    }
                    assertArrayEquals(previousNotes, productionStorage.load());
                    assertArrayEquals(previousPlayer, readAtomic(new File(context.getFilesDir(), "player-state-v1.json")));
                    if (!notesDirectoryExisted) {
                        String[] remaining = productionStorage.directory.list();
                        assertNotNull(remaining);
                        assertEquals("Only test-created files may be present in a new directory", 0, remaining.length);
                        assertTrue(productionStorage.directory.delete());
                    }
                }
                if (isolatedRoot != null) deleteTree(isolatedRoot);
            });
        } finally {
            instrumentation.getUiAutomation().dropShellPermissionIdentity();
        }
    }

    @Test public void atomicMetadataRollsBackAndRecoversUuidPartialOrphanAndMissingOriginals() {
        label = "atomic-wave-recovery";
        main(() -> {
            AndroidNoteStorage storage = isolatedStorage();
            NoteBook book = new NoteBook(storage);
            NoteBook.Note draft = book.beginRecording("Синтетический draft", "A", "A1", "v1", 123);
            book.edit(draft.id, "Ручной заголовок", "Ручной вымышленный текст.");
            File part = new File(storage.directory, draft.id + ".wav.part");
            try (PcmWave.Writer writer = new PcmWave.Writer(part)) {
                byte[] pcm = tone(1000); writer.append(pcm, 0, pcm.length); writer.sync();
            }
            try (RandomAccessFile tail = new RandomAccessFile(part, "rw")) {
                tail.seek(tail.length()); tail.write(42); tail.getFD().sync(); // An incomplete PCM16 sample.
            }
            String orphan = UUID.randomUUID().toString();
            writeWave(storage.directory, orphan, 500);
            NoteBook.Note missing = book.beginRecording("Без samples", null, null, null, 456);
            File invalidName = new File(storage.directory, "not-a-uuid.wav");
            try (FileOutputStream out = new FileOutputStream(invalidName)) { out.write(new byte[]{1, 2, 3}); }

            byte[] committed = storage.load();
            AtomicFile atomic = new AtomicFile(new File(storage.directory, "notes-v1.bin"));
            FileOutputStream abandoned = atomic.startWrite();
            abandoned.write(new byte[]{9, 9, 9}); atomic.failWrite(abandoned);
            assertArrayEquals("AtomicFile failure must retain the committed snapshot", committed, storage.load());

            NoteBook recovered = new NoteBook(storage); storage.recover(recovered);
            NoteBook.Note result = recovered.find(draft.id);
            assertEquals(NoteBook.INTERRUPTED, result.recordingStatus);
            assertEquals("Ручной заголовок", result.title); assertEquals("Ручной вымышленный текст.", result.text);
            assertEquals(1, result.revision); assertEquals("A", result.linkedStation);
            assertEquals(1000, result.durationMs); assertTrue(result.hasAudio); assertEquals(0, result.positionMs);
            File wave = new File(storage.directory, draft.id + ".wav");
            assertFalse(part.exists()); assertEquals(PcmWave.HEADER_BYTES + 32_000, wave.length());
            assertEquals(1000, PcmWave.readDuration(wave));
            assertEquals(NoteBook.INTERRUPTED, recovered.find(orphan).recordingStatus);
            assertEquals(500, recovered.find(orphan).durationMs);
            assertFalse(recovered.find(missing.id).hasAudio);
            assertEquals(NoteBook.INTERRUPTED, recovered.find(missing.id).recordingStatus);
            assertTrue("Unrecognized filenames must be retained rather than adopted or deleted", invalidName.exists());
            assertEquals(3, recovered.notes().size());
            byte[] stable = storage.load(); storage.recover(recovered);
            assertArrayEquals("Recovery must be idempotent", stable, storage.load());

            byte[] originalHash = hash(wave); storage.save(new byte[]{1, 2, 3});
            expectFailure(() -> new NoteBook(storage));
            assertArrayEquals("Corrupt metadata must not be replaced by an empty book", new byte[]{1, 2, 3}, storage.load());
            assertArrayEquals("Corruption must leave the original intact", originalHash, hash(wave));
        });
    }

    @Test public void realServiceRestoresSelectedImmutableVersionDedupAndIndependentNoteEdit() {
        label = "immutable-ledger-restart"; launchService();
        String id = syntheticNote(3000);
        NoteBook.Material first = mainValue(() -> {
            service.notes().edit(id, "Первый заголовок", "Первый вымышленный текст.");
            service.notesBook().setOriginalPosition(id, 750);
            service.addNoteToAir(id); service.addNoteToAir(id);
            NoteBook.Material material = selected(id);
            assertEquals(1, catalogCount(material.stationId, material.articleId));
            service.notes().edit(id, "Второй заголовок", "Второй вымышленный текст.");
            service.addNoteToAir(id);
            assertEquals(material.articleId, selected(id).articleId);
            assertEquals("Первый вымышленный текст.", catalogArticle(material.stationId, material.articleId).text);
            assertFalse(service.player().snapshot().playing);
            return material;
        });
        byte[] before = mainValue(() -> hash(originalFile(id)));
        RadioService old = service; evidence("before-restart"); closeService(); launchService();
        main(() -> {
            assertNotSame("Restart must construct a new real service", old, service);
            NoteBook.Note note = service.notesBook().find(id);
            assertNotNull(note); assertEquals("Второй заголовок", note.title);
            assertEquals("Второй вымышленный текст.", note.text); assertEquals(750, note.positionMs);
            assertEquals(3000, note.durationMs); assertTrue(note.hasAudio);
            NoteBook.Material selected = selected(id);
            assertEquals(first.articleId, selected.articleId); assertEquals(first.sequence, selected.sequence);
            assertEquals("Первый заголовок", selected.title); assertEquals("Первый вымышленный текст.", selected.text);
            assertEquals(1, catalogCount(selected.stationId, selected.articleId));
            assertFalse(service.player().snapshot().playing); assertFalse(noteAudio().isOriginalPlaying());
            assertArrayEquals(before, hash(originalFile(id)));
            service.removeNoteFromAir(id); service.addNoteToAir(id);
            NoteBook.Material updated = selected(id);
            assertTrue(updated.sequence > first.sequence); assertNotEquals(first.articleId, updated.articleId);
            assertEquals("Второй вымышленный текст.", updated.text);
            assertEquals(750, service.notesBook().find(id).positionMs);
            assertArrayEquals(before, hash(originalFile(id)));
        });
        evidence("restored-immutable-version");
    }

    @Test public void productionMediaPlayerPausesSeeksWhilePausedAndStopsWithoutRadioOrMic() {
        label = "original-transport"; launchService(); showNotes(); String id = syntheticNote(8000);
        byte[] before = mainValue(() -> hash(originalFile(id)));
        main(() -> assertTrue(service.notes().playOriginal(id)));
        awaitOriginalStarted(200);
        main(() -> {
            assertTrue(service.notes().pauseOriginal());
            long position = service.notesBook().find(id).positionMs;
            assertTrue("A real decoder clock must advance before Pause", position >= 200 && position < 8000);
            assertFalse(noteAudio().isOriginalPlaying()); assertTrue(service.player().isOutputStopped());
            service.notes().seekOriginal(id, 4000);
            assertEquals(4000, service.notesBook().find(id).positionMs);
        });
        assertFor("Paused seek cannot start a native player or radio", 400, () -> mainValue(() ->
                !noteAudio().isOriginalPlaying() && !service.notes().originalPlaying() && service.player().isOutputStopped()));
        main(() -> assertTrue(service.notes().playOriginal(id)));
        awaitOriginalStarted(4000);
        main(() -> {
            service.notes().stopOriginal(id);
            assertEquals(0, service.notesBook().find(id).positionMs);
            assertFalse(noteAudio().isOriginalPlaying()); assertFalse(service.notes().originalPlaying());
            assertNull(service.notes().originalId()); assertFalse(noteAudio().isRecording());
            assertTrue(service.player().isOutputStopped());
            assertArrayEquals(before, hash(originalFile(id)));
            assertEquals(0, new NoteBook(new AndroidNoteStorage(context)).find(id).positionMs);
        });
        evidence("original-stopped");
    }

    @Test public void realRadioAndOriginalRevokeEachOtherAndPreserveSeparateAnchors() {
        label = "radio-original-exclusion"; launchService(); showNotes(); String id = syntheticNote(8000);
        awaitLocalVoice();
        main(() -> {
            service.player().pause(); service.player().selectArticle("B", "B2");
            service.player().seekToFraction(0); service.requestPlay();
        });
        awaitRadioStarted();
        PlayerController.Snapshot before = mainValue(() -> {
            PlayerController.Snapshot anchor = service.player().snapshot();
            assertTrue(service.notes().playOriginal(id));
            assertRadioAnchor(anchor, service.player().snapshot());
            assertTrue(service.player().isOutputStopped());
            return anchor;
        });
        awaitOriginalStarted(200);
        main(() -> {
            assertTrue(service.player().isOutputStopped()); assertNull(service.player().snapshot().currentRequestId);
            assertFalse(noteAudio().isRecording());
            service.requestPlay();
            assertFalse(noteAudio().isOriginalPlaying()); assertNull(service.notes().originalId());
            assertTrue(service.notesBook().find(id).positionMs >= 200);
        });
        awaitRadioStarted();
        main(() -> {
            assertFalse(noteAudio().isOriginalPlaying()); assertFalse(service.notes().originalPlaying());
            assertFalse(noteAudio().isRecording()); assertFalse(service.notes().recordingBusy());
            assertRadioAnchor(before, service.player().snapshot());
            assertNotEquals("The revoked request must never be reused", before.currentRequestId, service.player().snapshot().currentRequestId);
            service.player().pause();
        });
        evidence("exclusion-confirmed");
    }

    @Test public void realOnDeviceSupportProbeBlocksFileTranscriptionAndPreservesOriginalAndManualText() {
        label = "on-device-support-guard"; launchService(); showNotes(); String id = syntheticNote(2000);
        byte[] before = mainValue(() -> hash(originalFile(id)));
        long revision = mainValue(() -> {
            service.notes().edit(id, "Вымышленный ручной заголовок", "Ручной текст не является расшифровкой.");
            long value = service.notesBook().find(id).revision;
            service.notes().probeRecognition(id); return value;
        });
        await("Real support result", 15_000, () -> mainValue(() -> {
            String status = service.notesBook().find(id).recognitionStatus;
            return !"CHECKING".equals(status);
        }));
        main(() -> {
            NoteBook.Note note = service.notesBook().find(id);
            assertTrue("Only a blocked honest support status is allowed", Arrays.asList("UNAVAILABLE", "ERROR", "UNVERIFIED_FILE_SOURCE").contains(note.recognitionStatus));
            assertNotNull(note.message); assertFalse(note.message.trim().isEmpty());
            assertFalse("A support timeout is a failed observation, not successful support", note.message.contains("10 секунд"));
            assertEquals(revision, note.revision); assertEquals("Ручной текст не является расшифровкой.", note.text);
            assertEquals("Вымышленный ручной заголовок", note.title); assertTrue(note.hasAudio);
            assertEquals(2000, PcmWave.readDuration(originalFile(id))); assertArrayEquals(before, hash(originalFile(id)));
            assertFalse(noteAudio().isRecording()); assertFalse(noteAudio().isFinalizing());
            assertFalse(noteAudio().isOriginalPlaying()); assertFalse(service.notes().recordingBusy());
            assertTrue(service.player().isOutputStopped());
        });
        evidence("support-only-no-transcription");
    }

    private AndroidNoteStorage isolatedStorage() throws IOException {
        isolatedRoot = new File(context.getNoBackupFilesDir(), "notes-storage-test-" + UUID.randomUUID());
        if (!isolatedRoot.mkdirs()) throw new IOException("Cannot create isolated notes test directory");
        return new AndroidNoteStorage(new ContextWrapper(context) {
            @Override public File getNoBackupFilesDir() { return isolatedRoot; }
        });
    }

    private void launchService() {
        if (!backedUp) {
            main(() -> context.stopService(new Intent(context, RadioService.class)));
            await("No pre-existing service binding", STATE_TIMEOUT, () -> mainValue(() -> ownRunningService() == null));
            main(() -> {
                File directory = new File(context.getNoBackupFilesDir(), "notes");
                notesDirectoryExisted = directory.isDirectory();
                productionStorage = new AndroidNoteStorage(context);
                File[] originals = productionStorage.directory.listFiles(); assertNotNull(originals);
                for (File original : originals) assertFalse("Finish pending private recovery before running these tests", original.getName().endsWith(".wav.part"));
                previousNotes = productionStorage.load();
                previousPlayer = readAtomic(new File(context.getFilesDir(), "player-state-v1.json"));
                backedUp = true;
            });
        }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        instrumentation.getUiAutomation().dropShellPermissionIdentity();
        scenario = ActivityScenario.launch(new Intent(context, RadioActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        scenario.onActivity(value -> activity = value);
        await("Activity's normal service connection", STATE_TIMEOUT, () -> mainValue(() -> activity.serviceForNotes() != null));
        main(() -> {
            service = activity.serviceForNotes();
            assertNotNull(service); assertNull("A corrupt pre-existing book must fail this test", service.notesProblem());
            assertNotNull(service.notesBook()); assertNotNull(service.notes());
            assertFalse(AndroidNoteAudio.isOutputBlocked());
        });
        await("Only the Activity binding exists", STATE_TIMEOUT, () -> mainValue(() -> {
            ActivityManager.RunningServiceInfo running = ownRunningService();
            return running != null && running.clientCount == 1;
        }));
    }

    private void showNotes() {
        scenario.onActivity(value -> {
            Button tab = findButton(value.getWindow().getDecorView(), "Заметки");
            assertNotNull("Real Notes navigation must exist", tab); assertTrue(tab.performClick());
        });
    }
    private String syntheticNote(long durationMs) {
        return mainValue(() -> {
            NoteBook.Note note = service.notesBook().beginRecording("Синтетическая проверка", null, null, null, System.currentTimeMillis());
            assertFalse("Generated UUID must not collide with a pre-existing original", new File(service.noteStorage().directory, note.id + ".wav").exists());
            assertFalse("Generated UUID must not collide with a pre-existing partial", new File(service.noteStorage().directory, note.id + ".wav.part").exists());
            ownIds.add(note.id); currentSyntheticId = note.id;
            writeWave(service.noteStorage().directory, note.id, durationMs);
            service.notesBook().finishRecording(note.id, durationMs, true, false, "Синтетический WAV без речи и микрофона");
            return note.id;
        });
    }
    private void closeService() {
        RadioService old = service;
        if (old == null && scenario == null) return;
        if (old != null) main(() -> {
            old.player().pause(); if (old.notes() != null) old.notes().leaveNotes();
            AndroidNoteAudio audio = (AndroidNoteAudio) field(old, "noteAudio");
            if (audio != null) { assertFalse(audio.isRecording()); assertFalse(audio.isFinalizing()); }
        });
        if (scenario != null) { scenario.close(); scenario = null; }
        main(() -> context.stopService(new Intent(context, RadioService.class)));
        await("Full Activity/service shutdown before metadata restore", STATE_TIMEOUT, () -> mainValue(() ->
                ownRunningService() == null && (old == null || (boolean) field(old, "destroyed"))));
        service = null; activity = null;
    }
    private void awaitOriginalStarted(long minimumPosition) {
        await("Native MediaPlayer start and advancing original clock", STATE_TIMEOUT, () -> mainValue(() -> {
            assertNull("Original focus/decoder error cannot count as playback", service.notes().error());
            return noteAudio().isOriginalPlaying() && service.notes().originalPlaying()
                    && noteAudio().currentPosition() >= minimumPosition;
        }));
    }
    private void awaitLocalVoice() {
        await("Real local TTS initialization", TTS_TIMEOUT, () -> mainValue(() -> {
            String voice = service.voiceDescription();
            if (voice.startsWith("Проверяем")) return false;
            assertTrue("Installed local Russian TTS is required; no skip", voice.contains("Голос:")); return true;
        }));
    }
    private void awaitRadioStarted() {
        await("Actual TTS onStart callback", TTS_TIMEOUT, () -> mainValue(() -> {
            PlayerController.Snapshot snapshot = service.player().snapshot();
            assertNull("Radio focus/TTS error cannot count as playback", snapshot.error);
            return snapshot.playing && snapshot.currentRequestId != null && "Читаем на телефоне".equals(snapshot.status);
        }));
    }
    private NoteBook.Material selected(String id) {
        for (NoteBook.Material material : service.notesBook().materials()) if (id.equals(material.noteId)) return material;
        throw new AssertionError("Own synthetic selected material is missing");
    }
    private Article catalogArticle(String station, String article) {
        for (Station item : service.player().stations()) if (item.id.equals(station))
            for (Article candidate : item.articles) if (candidate.id.equals(article)) return candidate;
        throw new AssertionError("Own synthetic article is missing from the live catalog");
    }
    private int catalogCount(String station, String article) {
        int count = 0;
        for (Station item : service.player().stations()) if (item.id.equals(station))
            for (Article candidate : item.articles) if (candidate.id.equals(article)) count++;
        return count;
    }
    private AndroidNoteAudio noteAudio() { return (AndroidNoteAudio) field(service, "noteAudio"); }
    private File originalFile(String id) { return new File(service.noteStorage().directory, id + ".wav"); }
    private static void assertRadioAnchor(PlayerController.Snapshot before, PlayerController.Snapshot after) {
        assertEquals(before.station.id, after.station.id); assertEquals(before.article.id, after.article.id);
        assertEquals(before.sentenceIndex, after.sentenceIndex); assertEquals(before.completedSentences, after.completedSentences);
        assertEquals(before.read, after.read); assertEquals(before.started, after.started);
        assertEquals(before.listened, after.listened); assertEquals(before.passEnded, after.passEnded);
    }
    @SuppressWarnings("deprecation") private ActivityManager.RunningServiceInfo ownRunningService() {
        ComponentName expected = new ComponentName(context, RadioService.class);
        for (ActivityManager.RunningServiceInfo candidate : context.getSystemService(ActivityManager.class).getRunningServices(100))
            if (expected.equals(candidate.service)) return candidate;
        return null;
    }
    private static Button findButton(View view, String caption) {
        if (view instanceof Button && caption.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { Button found = findButton(group.getChildAt(i), caption); if (found != null) return found; }
        }
        return null;
    }
    private static Object field(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError("Required production field unavailable: " + name, error); }
    }
    private static byte[] tone(long millis) {
        int samples = (int) (millis * PcmWave.SAMPLE_RATE / 1000);
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            short sample = (short) (500 * Math.sin(2 * Math.PI * 440 * i / PcmWave.SAMPLE_RATE));
            pcm[i * 2] = (byte) sample; pcm[i * 2 + 1] = (byte) (sample >> 8);
        }
        return pcm;
    }
    private static void writeWave(File directory, String id, long millis) throws IOException {
        try (PcmWave.Writer writer = new PcmWave.Writer(new File(directory, id + ".wav.part"))) {
            byte[] pcm = tone(millis); writer.append(pcm, 0, pcm.length); writer.finish(new File(directory, id + ".wav"));
        }
    }
    private static byte[] hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[4096];
        try (InputStream input = new FileInputStream(file)) { int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); }
        return digest.digest();
    }
    private static byte[] readAtomic(File file) throws IOException {
        try (InputStream input = new AtomicFile(file).openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        } catch (FileNotFoundException absent) { return null; }
    }
    private static void restoreAtomic(File file, byte[] bytes) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        if (bytes == null) { atomic.delete(); return; }
        FileOutputStream output = atomic.startWrite();
        try { output.write(bytes); atomic.finishWrite(output); }
        catch (IOException failure) { atomic.failWrite(output); throw failure; }
    }
    private static void deleteOwn(File file) { if (file.exists()) assertTrue("Own synthetic file could not be removed", file.delete()); }
    private static void deleteTree(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) deleteTree(child);
        assertTrue("Isolated fixture cleanup failed", file.delete());
    }
    private static void expectFailure(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        fail("Corrupt metadata must throw rather than erase or return an empty book");
    }
    private void await(String description, long timeout, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (!condition.getAsBoolean()) {
            if (SystemClock.uptimeMillis() >= deadline) { evidence("timeout"); fail("Timed out: " + description); }
            SystemClock.sleep(50);
        }
    }
    private void assertFor(String description, long duration, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + duration;
        do { assertTrue(description, condition.getAsBoolean()); SystemClock.sleep(50); } while (SystemClock.uptimeMillis() < deadline);
    }
    private void evidence(String phase) {
        main(() -> {
            StringBuilder proof = new StringBuilder("scenario=").append(label).append("\nphase=").append(phase)
                    .append("\nfileSTT=BLOCKED; this is support/transport evidence only\n");
            if (service != null) {
                AndroidNoteAudio audio = noteAudio();
                proof.append("radioPlaying=").append(service.player().snapshot().playing)
                        .append("\nradioOutputStopped=").append(service.player().isOutputStopped())
                        .append("\nnotesAvailable=").append(audio != null).append('\n');
                if (audio != null) proof.append("originalEnginePlaying=").append(audio.isOriginalPlaying())
                        .append("\noriginalPositionMs=").append(audio.currentPosition())
                        .append("\nrecording=").append(audio.isRecording()).append("\nfinalizing=").append(audio.isFinalizing()).append('\n');
                if (currentSyntheticId != null && service.notesBook() != null) {
                    NoteBook.Note own = service.notesBook().find(currentSyntheticId);
                    if (own != null) proof.append("syntheticPositionMs=").append(own.positionMs)
                            .append("\nsyntheticDurationMs=").append(own.durationMs)
                            .append("\nrecognitionStatus=").append(own.recognitionStatus).append('\n');
                }
            }
            File directory = new File(context.getFilesDir(), "notes-test-proof");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot preserve test evidence");
            File output = new File(directory, label + "-" + SystemClock.uptimeMillis() + "-" + UUID.randomUUID() + ".txt");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                stream.write(proof.toString().getBytes(StandardCharsets.UTF_8)); stream.getFD().sync();
            }
        });
    }
    private void main(ThrowingAction action) { mainValue(() -> { action.run(); return null; }); }
    private <T> T mainValue(Callable<T> action) {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        Runnable operation = () -> { try { result.set(action.call()); } catch (Throwable failure) { error.set(failure); } };
        if (Looper.myLooper() == Looper.getMainLooper()) operation.run(); else instrumentation.runOnMainSync(operation);
        Throwable failure = error.get();
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new AssertionError("Main-thread test action failed", failure);
        return result.get();
    }
    private interface ThrowingAction { void run() throws Exception; }
}

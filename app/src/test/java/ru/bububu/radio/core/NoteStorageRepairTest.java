package ru.bububu.radio.core;

import org.junit.Test;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** R6/R8 production reader policy and real book codec; Android syscalls have device reproducers. */
public class NoteStorageRepairTest {
    private static final String[] PATHS = {NoteMetadataReader.BASE, NoteMetadataReader.BACKUP, NoteMetadataReader.PENDING};

    @Test public void firstUseRequiresAccessibleParentAndTwoCompleteAbsentPathInspections() throws IOException {
        Files files = new Files();
        assertNull(NoteMetadataReader.load(files));
        assertEquals(2, files.parentChecks); assertEquals(6, files.inspections); assertEquals(0, files.reads);
    }

    @Test public void absentPathsDoNotOverrideDeniedMissingOrWrongTypeParent() {
        for (String fault : Arrays.asList("EACCES", "ENOENT", "ENOTDIR")) {
            Files files = new Files(); files.parentFailure = new IOException(fault);
            assertThrows(IOException.class, () -> NoteMetadataReader.load(files));
            assertEquals(0, files.reads); assertTrue(files.contents.isEmpty());
        }
    }

    @Test public void everyCandidateInspectionErrorIncludingFileNotFoundFailsClosed() {
        for (String name : PATHS) {
            Files files = new Files(); files.inspectFailures.put(name, new FileNotFoundException("EACCES"));
            assertThrows(FileNotFoundException.class, () -> NoteMetadataReader.load(files));
            assertTrue(files.contents.isEmpty()); assertEquals(0, files.reads);
        }
    }

    @Test public void everyNonRegularMetadataPathFailsClosedRatherThanCountingAsMissing() {
        for (String name : PATHS) {
            Files files = new Files(); files.types.put(name, NoteMetadataReader.Type.OTHER);
            assertThrows(IOException.class, () -> NoteMetadataReader.load(files));
            assertEquals(0, files.reads);
        }
    }

    @Test public void canonicalReadErrorCannotEraseSavedNoteMaterialOrAtomicRecoveryFiles() {
        Files files = new Files(); Storage storage = new Storage(files); NoteBook book = new NoteBook(storage);
        NoteBook.Note note = book.beginRecording("Сохранённая", "A", "A1", "v1", 123);
        book.edit(note.id, "Сохранённый заголовок", "Сохранённый вымышленный текст.");
        book.finishRecording(note.id, 1000, true, false, null);
        NoteBook.Material selected = book.addToAir(note.id);
        files.contents.put(NoteMetadataReader.BACKUP, files.contents.get(NoteMetadataReader.BASE).clone());
        files.contents.put(NoteMetadataReader.PENDING, new byte[]{8, 9});
        files.contents.put(note.id + ".wav", new byte[]{1, 2, 3, 4});
        Map<String,byte[]> before = copy(files.contents); int saves = storage.saves;
        files.readFailures.put(NoteMetadataReader.BASE, new FileNotFoundException("EACCES"));
        assertThrows(IllegalStateException.class, () -> new NoteBook(storage));
        assertEquals(saves, storage.saves); assertFiles(before, files.contents);
        files.readFailures.clear(); NoteBook restored = new NoteBook(storage);
        assertEquals("Сохранённый заголовок", restored.find(note.id).title);
        assertEquals("Сохранённый вымышленный текст.", restored.find(note.id).text);
        assertEquals("A", restored.find(note.id).linkedStation);
        assertEquals(selected.articleId, restored.materials().get(0).articleId);
        assertEquals(selected.sequence, restored.materials().get(0).sequence);
        assertFiles(before, files.contents);
    }

    @Test public void readErrorsInAnyPresentCandidatePreserveBaseBackupAndPending() {
        for (String failed : PATHS) {
            Files files = new Files();
            for (int i=0;i<PATHS.length;i++) files.contents.put(PATHS[i], new byte[]{(byte)i});
            Map<String,byte[]> before = copy(files.contents);
            files.readFailures.put(failed, new IOException("EIO"));
            assertThrows(IOException.class, () -> NoteMetadataReader.load(files));
            assertFiles(before, files.contents);
        }
    }

    @Test public void readableLegacyBackupRetainsPrecedenceWithoutMutatingAtomicLayout() throws IOException {
        Files files = new Files(); files.contents.put(NoteMetadataReader.BASE, new byte[]{1});
        files.contents.put(NoteMetadataReader.BACKUP, new byte[]{2}); files.contents.put(NoteMetadataReader.PENDING, new byte[]{3});
        Map<String,byte[]> before = copy(files.contents);
        assertArrayEquals(new byte[]{2}, NoteMetadataReader.load(files)); assertFiles(before, files.contents);
        files.contents.remove(NoteMetadataReader.BASE);
        assertArrayEquals("A recoverable backup must not count as first launch", new byte[]{2}, NoteMetadataReader.load(files));
    }

    @Test public void readableBaseDoesNotDeletePendingAndPendingAloneBlocksFirstUse() throws IOException {
        Files files = new Files(); files.contents.put(NoteMetadataReader.BASE, new byte[]{1});
        files.contents.put(NoteMetadataReader.PENDING, new byte[]{7});
        assertArrayEquals(new byte[]{1}, NoteMetadataReader.load(files)); assertArrayEquals(new byte[]{7}, files.contents.get(NoteMetadataReader.PENDING));
        files.contents.remove(NoteMetadataReader.BASE);
        assertThrows(IOException.class, () -> NoteMetadataReader.load(files));
        assertArrayEquals(new byte[]{7}, files.contents.get(NoteMetadataReader.PENDING));
    }

    @Test public void directoryAccessLossDuringReadAndChangingLayoutRejectRatherThanReturnEmpty() {
        Files inaccessible = new Files(); inaccessible.failSecondParentCheck = true;
        assertThrows(IOException.class, () -> NoteMetadataReader.load(inaccessible));
        Files changed = new Files(); changed.contents.put(NoteMetadataReader.BASE, new byte[]{1});
        changed.afterRead = () -> changed.contents.remove(NoteMetadataReader.BASE);
        assertThrows(IOException.class, () -> NoteMetadataReader.load(changed));
    }

    @Test public void oversizedOrNullCandidateReadCannotEnterAnEmptyBook() {
        Files files = new Files(); files.contents.put(NoteMetadataReader.BASE, new byte[NoteBook.MAX_METADATA_BYTES + 1]);
        assertThrows(IOException.class, () -> NoteMetadataReader.load(files));
        files.contents.put(NoteMetadataReader.BASE, null); files.types.put(NoteMetadataReader.BASE, NoteMetadataReader.Type.REGULAR);
        assertThrows(IOException.class, () -> NoteMetadataReader.load(files));
    }

    @Test public void availableWaveReconciliationPreservesAllUserSttPositionAndLedgerState() {
        NoteBookTest.MemoryStorage storage = new NoteBookTest.MemoryStorage(); NoteBook book = new NoteBook(storage);
        NoteBook.Note note = book.beginRecording("Связанная", "A", "A1", "v1", 123);
        book.edit(note.id, "Первая версия", "Первый вымышленный текст.");
        NoteBook.Material selected = book.addToAir(note.id);
        book.edit(note.id, "Ручной заголовок", "Ручная вторая версия.");
        book.finishRecording(note.id, 2000, false, true, "Оригинал временно недоступен");
        book.setOriginalPosition(note.id, 750);
        book.setRecognition(note.id, 2, "UNVERIFIED_FILE_SOURCE", "Сохранённый статус проверки STT", null);
        NoteBook.Material removed = book.addSavedMaterial("other", "Другие", NoteBookTest.article("deleted"));
        book.removeSavedMaterial("other", "deleted");
        PlayerController.SavedState cursor = new PlayerController.SavedState("other", Collections.singletonMap("other", "deleted"), Collections.emptyMap());
        Map<String,String> beforeBoundary = book.reconcileState(cursor).stationArticleIds;
        NoteBook.Note previous = book.find(note.id);
        NoteBook.Note available = book.reconcileAvailableOriginal(note.id, 3000);
        assertTrue(available.hasAudio); assertEquals(3000, available.durationMs);
        assertEquals(previous.id, available.id); assertEquals(previous.createdAt, available.createdAt);
        assertEquals(previous.title, available.title); assertEquals(previous.text, available.text);
        assertEquals(previous.revision, available.revision); assertEquals(previous.linkedStation, available.linkedStation);
        assertEquals(previous.linkedArticle, available.linkedArticle); assertEquals(previous.linkedVersion, available.linkedVersion);
        assertEquals(previous.positionMs, available.positionMs); assertEquals(NoteBook.INTERRUPTED, available.recordingStatus);
        assertEquals(previous.recognitionStatus, available.recognitionStatus); assertEquals(previous.message, available.message);
        assertSame(selected, book.materials().get(0));
        assertEquals(beforeBoundary, book.reconcileState(cursor).stationArticleIds);
        assertEquals(removed.boundaryMarker(), book.reconcileState(cursor).stationArticleIds.get("other"));
        NoteBook restored = new NoteBook(storage); NoteBook.Note disk = restored.find(note.id);
        assertTrue(disk.hasAudio); assertEquals(3000, disk.durationMs); assertEquals(750, disk.positionMs);
        assertEquals(previous.recognitionStatus, disk.recognitionStatus); assertEquals(previous.message, disk.message);
        assertEquals(selected.articleId, restored.materials().get(0).articleId);
        assertEquals(selected.text, restored.materials().get(0).text);
        assertEquals(selected.sequence, restored.materials().get(0).sequence);
        assertEquals(2, restored.stationRegistry().size()); assertEquals(beforeBoundary, restored.reconcileState(cursor).stationArticleIds);
    }

    @Test public void waveAvailabilityIsIdempotentAndOnlyRepairsInterruptedMissingAudio() {
        NoteBookTest.MemoryStorage storage = new NoteBookTest.MemoryStorage(); NoteBook book = new NoteBook(storage);
        NoteBook.Note recording = NoteBookTest.draft(book);
        assertSame(recording, book.reconcileAvailableOriginal(recording.id, 1000));
        NoteBook.Note saved = book.finishRecording(recording.id, 0, false, false, null);
        assertSame(saved, book.reconcileAvailableOriginal(saved.id, 1000));
        NoteBook.Note interrupted = book.finishRecording(saved.id, 0, false, true, "Ошибка чтения");
        assertSame(interrupted, book.reconcileAvailableOriginal(saved.id, 0));
        NoteBook.Note restored = book.reconcileAvailableOriginal(saved.id, 1000);
        byte[] committed = storage.bytes.clone(); int saves = storage.saves;
        assertSame(restored, book.reconcileAvailableOriginal(saved.id, 1000));
        assertSame(restored, book.reconcileAvailableOriginal(saved.id, 2000));
        assertEquals(saves, storage.saves); assertArrayEquals(committed, storage.bytes);
    }

    @Test public void waveAvailabilitySaveFailureRollsBackAndRetryRestoresSameCard() {
        NoteBookTest.MemoryStorage storage = new NoteBookTest.MemoryStorage(); NoteBook book = new NoteBook(storage);
        NoteBook.Note draft = NoteBookTest.draft(book);
        NoteBook.Note previous = book.finishRecording(draft.id, 0, false, true, "Чтение временно недоступно");
        byte[] committed = storage.bytes.clone(); storage.fail = true;
        assertThrows(IllegalStateException.class, () -> book.reconcileAvailableOriginal(draft.id, 1000));
        assertSame(previous, book.find(draft.id)); assertArrayEquals(committed, storage.bytes);
        assertFalse(new NoteBook(storage).find(draft.id).hasAudio);
        storage.fail = false; assertTrue(book.reconcileAvailableOriginal(draft.id, 1000).hasAudio);
        assertEquals(previous.message, book.find(draft.id).message);
    }

    @Test public void conflictingDurationDoesNotSilentlyResetSavedAudioPosition() {
        NoteBookTest.MemoryStorage storage = new NoteBookTest.MemoryStorage(); NoteBook book = new NoteBook(storage);
        NoteBook.Note draft = NoteBookTest.draft(book); book.finishRecording(draft.id, 2000, false, true, null);
        book.setOriginalPosition(draft.id, 1500); byte[] committed = storage.bytes.clone();
        assertThrows(IllegalArgumentException.class, () -> book.reconcileAvailableOriginal(draft.id, 1000));
        assertThrows(IllegalArgumentException.class, () -> book.reconcileAvailableOriginal(draft.id, -1));
        assertFalse(book.find(draft.id).hasAudio); assertEquals(1500, book.find(draft.id).positionMs);
        assertArrayEquals(committed, storage.bytes);
    }

    @Test public void waveAvailabilityCannotBeChangedFromForeignThread() throws InterruptedException {
        NoteBook book = new NoteBook(new NoteBookTest.MemoryStorage()); NoteBook.Note draft = NoteBookTest.draft(book);
        book.finishRecording(draft.id, 0, false, true, null); AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { book.reconcileAvailableOriginal(draft.id, 1000); } catch (Throwable error) { failure.set(error); } });
        worker.start(); worker.join(); assertTrue(failure.get() instanceof IllegalStateException); assertFalse(book.find(draft.id).hasAudio);
    }

    private static Map<String,byte[]> copy(Map<String,byte[]> source) {
        Map<String,byte[]> result = new LinkedHashMap<>(); source.forEach((name,bytes) -> result.put(name, bytes == null ? null : bytes.clone())); return result;
    }
    private static void assertFiles(Map<String,byte[]> expected, Map<String,byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet()); expected.forEach((name,bytes) -> assertArrayEquals(name, bytes, actual.get(name)));
    }
    private static final class Files implements NoteMetadataReader.Files {
        final Map<String,byte[]> contents = new LinkedHashMap<>();
        final Map<String,NoteMetadataReader.Type> types = new LinkedHashMap<>();
        final Map<String,IOException> inspectFailures = new LinkedHashMap<>(), readFailures = new LinkedHashMap<>();
        IOException parentFailure; boolean failSecondParentCheck; Runnable afterRead;
        int parentChecks, inspections, reads;
        @Override public void requireAccessibleDirectory() throws IOException {
            parentChecks++; if (parentFailure != null) throw parentFailure;
            if (failSecondParentCheck && parentChecks > 1) throw new IOException("EACCES");
        }
        @Override public NoteMetadataReader.Type inspect(String name) throws IOException {
            inspections++; if (inspectFailures.containsKey(name)) throw inspectFailures.get(name);
            if (types.containsKey(name)) return types.get(name);
            return contents.containsKey(name) ? NoteMetadataReader.Type.REGULAR : NoteMetadataReader.Type.ABSENT;
        }
        @Override public byte[] read(String name) throws IOException {
            reads++; if (readFailures.containsKey(name)) throw readFailures.get(name);
            byte[] bytes = contents.get(name); if (afterRead != null) afterRead.run(); return bytes == null ? null : bytes.clone();
        }
    }
    private static final class Storage implements NoteBook.Storage {
        final Files files; int saves;
        Storage(Files files) { this.files = files; }
        @Override public byte[] load() { try { return NoteMetadataReader.load(files); } catch (IOException failure) { throw new IllegalStateException(failure); } }
        @Override public void save(byte[] bytes) { files.contents.put(NoteMetadataReader.BASE, bytes.clone()); saves++; }
    }
}

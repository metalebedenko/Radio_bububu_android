package ru.bububu.radio.core;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import static org.junit.Assert.*;

public class NoteBookTest {
    @Test public void draftCommitsBeforeReturnAndRealCodecRestoresEveryField() {
        MemoryStorage storage = new MemoryStorage();
        NoteBook book = new NoteBook(storage);
        NoteBook.Note draft = book.beginRecording("Заметка Ё", "a", "a1", "v1", 1234);
        assertEquals(NoteBook.RECORDING, new NoteBook(storage).find(draft.id).recordingStatus);
        assertEquals(1, storage.saves);
        book.finishRecording(draft.id, 9000, true, false, null);
        book.setOriginalPosition(draft.id, 2500);
        book.edit(draft.id, "Новый заголовок", "Текст 🐻. Второе предложение.");
        book.setRecognition(draft.id, 1, "UNVERIFIED_FILE_SOURCE", "Файловый ввод не подтверждён", null);
        NoteBook.Note restored = new NoteBook(storage).find(draft.id);
        assertEquals(draft.id, java.util.UUID.fromString(draft.id).toString());
        assertEquals(1234, restored.createdAt);
        assertEquals("a", restored.linkedStation);
        assertEquals("a1", restored.linkedArticle);
        assertEquals("v1", restored.linkedVersion);
        assertEquals(9000, restored.durationMs);
        assertEquals(2500, restored.positionMs);
        assertTrue(restored.hasAudio);
        assertEquals(1, restored.revision);
        assertEquals("Новый заголовок", restored.title);
        assertEquals("Текст 🐻. Второе предложение.", restored.text);
        assertEquals(NoteBook.SAVED, restored.recordingStatus);
        assertEquals("UNVERIFIED_FILE_SOURCE", restored.recognitionStatus);
        assertEquals("Файловый ввод не подтверждён", restored.message);
    }

    @Test public void failedDraftSaveCreatesNoNoteAndRetryCanCommit() {
        MemoryStorage storage = new MemoryStorage(); NoteBook book = new NoteBook(storage);
        storage.fail = true;
        assertThrows(IllegalStateException.class, () -> draft(book));
        assertTrue(book.notes().isEmpty()); assertNull(storage.bytes);
        storage.fail = false; assertNotNull(draft(book));
    }

    @Test public void everyMetadataMutationRollsBackOnIoFailure() {
        MemoryStorage storage = new MemoryStorage(); NoteBook book = new NoteBook(storage);
        NoteBook.Note note = draft(book); book.edit(note.id, "Тема", "Сохранённый текст.");
        NoteBook.Material material = book.addToAir(note.id);
        byte[] durable = storage.bytes.clone(); NoteBook.Note previous = book.find(note.id);
        storage.fail = true;
        assertThrows(IllegalStateException.class, () -> book.edit(note.id, "Другая", "Правка."));
        assertThrows(IllegalStateException.class, () -> book.finishRecording(note.id, 5000, true, true, "Ошибка"));
        assertThrows(IllegalStateException.class, () -> book.setRecognition(note.id, previous.revision, "ERROR", "Нет языка", null));
        assertThrows(IllegalStateException.class, () -> book.removeFromAir(note.id));
        assertThrows(IllegalStateException.class, () -> book.addSavedMaterial("other", "Другие", article("other")));
        assertSame(previous, book.find(note.id)); assertSame(material, book.materials().get(0));
        assertEquals(1, book.stationRegistry().size()); assertArrayEquals(durable, storage.bytes);
        NoteBook restored = new NoteBook(storage); assertEquals(material.articleId, restored.materials().get(0).articleId);
    }

    @Test public void failedPositionSaveKeepsIndependentAudioAnchor() {
        MemoryStorage storage = new MemoryStorage(); NoteBook book = new NoteBook(storage);
        NoteBook.Note note = draft(book); book.finishRecording(note.id, 10000, true, false, null);
        book.setOriginalPosition(note.id, 2500); storage.fail = true;
        assertThrows(IllegalStateException.class, () -> book.setOriginalPosition(note.id, 4000));
        assertEquals(2500, book.find(note.id).positionMs);
        assertEquals(2500, new NoteBook(storage).find(note.id).positionMs);
    }

    @Test public void manualTitleOrTextEditRejectsAllStaleRecognitionChanges() {
        MemoryStorage storage = new MemoryStorage(); NoteBook book = new NoteBook(storage);
        NoteBook.Note note = draft(book); long callbackRevision = note.revision;
        book.edit(note.id, "Ручной заголовок", "Мой текст"); int saves = storage.saves;
        assertFalse(book.setRecognition(note.id, callbackRevision, "SUCCESS", null, "Поздняя расшифровка"));
        assertFalse(book.setRecognition(note.id, callbackRevision, "ERROR", "Поздняя ошибка", null));
        assertEquals("Мой текст", book.find(note.id).text);
        assertEquals("Ручной заголовок", book.find(note.id).title);
        assertEquals(NoteBook.NOT_REQUESTED, book.find(note.id).recognitionStatus);
        assertEquals(saves, storage.saves);
    }

    @Test public void successfulGuardedTextIsRevisionedAndCannotBeReplacedByDuplicateCallback() {
        MemoryStorage storage = new MemoryStorage(); NoteBook book = new NoteBook(storage);
        NoteBook.Note note = draft(book);
        assertTrue(book.setRecognition(note.id, 0, "SUCCESS", null, "Первый текст"));
        assertEquals(1, book.find(note.id).revision);
        assertFalse(book.setRecognition(note.id, 0, "SUCCESS", null, "Дубликат"));
        assertEquals("Первый текст", book.find(note.id).text);
        assertEquals(note.title, book.find(note.id).title);
    }

    @Test public void unchangedEditDoesNotIncrementRevisionOrWriteStorage() {
        MemoryStorage storage = new MemoryStorage(); NoteBook book = new NoteBook(storage);
        NoteBook.Note note = draft(book); int before = storage.saves;
        assertSame(note, book.edit(note.id, note.title, note.text));
        assertEquals(before, storage.saves); assertEquals(0, note.revision);
    }

    @Test public void searchUsesAllTermsAcrossTitleAndTextWithCaseAndYoNormalization() {
        NoteBook book = new NoteBook(new MemoryStorage());
        NoteBook.Note first = draft(book), second = draft(book);
        book.edit(first.id, "Зелёный ЁЖ", "Живёт возле дома");
        book.edit(second.id, "Ёж", "Другой текст");
        assertEquals(Collections.singletonList(book.find(first.id)), book.search("  ЗЕЛЕНЫЙ  еж   ДОМА "));
        assertEquals(2, book.search(" ").size());
        assertTrue(book.search("еж отсутствует").isEmpty());
        assertEquals(2, book.notes().size());
    }

    @Test public void selectedSnapshotIsImmutableUntilExplicitRemovalAndReadd() {
        MemoryStorage storage = new MemoryStorage(); NoteBook book = new NoteBook(storage);
        NoteBook.Note note = draft(book); book.edit(note.id, "Первая", "Первый текст.");
        NoteBook.Material first = book.addToAir(note.id); int saves = storage.saves;
        assertSame(first, book.addToAir(note.id)); assertEquals(saves, storage.saves);
        book.edit(note.id, "Вторая", "Изменённый текст.");
        assertSame(first, book.addToAir(note.id)); assertEquals("Первый текст.", first.asArticle().text);
        assertTrue(first.articleId.contains(note.id)); assertTrue(first.articleId.endsWith("r1"));
        assertSame(first, book.removeFromAir(note.id)); assertNull(book.removeFromAir(note.id));
        NoteBook.Material second = book.addToAir(note.id);
        assertEquals("Изменённый текст.", second.text); assertTrue(second.sequence > first.sequence);
        assertNotEquals(first.articleId, second.articleId); assertEquals(1, book.notes().size());
    }

    @Test public void generalPortIsIdempotentAndRejectsVersionContentNameAndBoundaryIdChanges() {
        MemoryStorage storage = new MemoryStorage(); NoteBook book = new NoteBook(storage);
        Article article = article("id"); NoteBook.Material material = book.addSavedMaterial("s", "Станция", article);
        int before = storage.saves;
        assertSame(material, book.addSavedMaterial("s", "Станция", article("id")));
        assertThrows(IllegalArgumentException.class, () -> book.addSavedMaterial("s", "Станция", new Article("id", "v2", article.title, article.text)));
        assertThrows(IllegalArgumentException.class, () -> book.addSavedMaterial("s", "Станция", new Article("id", "v1", article.title, "Подменённый текст.")));
        assertThrows(IllegalArgumentException.class, () -> book.addSavedMaterial("s", "Переименована", article));
        assertThrows(IllegalArgumentException.class, () -> book.addSavedMaterial("s", "Станция", new Article("@after:9", "v1", "X", "Текст.")));
        assertEquals(before, storage.saves); assertEquals(1, book.materials().size());
    }

    @Test public void codecPreservesExplicitSentenceBoundariesAndEmptyStationRegistryAndSequence() {
        MemoryStorage storage = new MemoryStorage(); NoteBook book = new NoteBook(storage);
        Article explicit = Article.fromSentences("id", "v1", "Заголовок", "Коротко", "Без точки тоже фраза");
        NoteBook.Material old = book.addSavedMaterial("s", "Станция", explicit);
        assertEquals(explicit.sentenceStarts, new NoteBook(storage).materials().get(0).asArticle().sentenceStarts);
        book.removeSavedMaterial("s", "id"); NoteBook restored = new NoteBook(storage);
        assertTrue(restored.materials().isEmpty()); assertEquals("s", restored.stationRegistry().get(0).id);
        assertTrue(restored.stationRegistry().get(0).articles.isEmpty());
        NoteBook.Material next = restored.addSavedMaterial("s", "Станция", explicit);
        assertTrue(next.sequence > old.sequence);
    }

    @Test public void snapshotsCannotBeModifiedOrChangeAfterLaterTransactions() {
        NoteBook book = new NoteBook(new MemoryStorage()); NoteBook.Note note = draft(book);
        List<NoteBook.Note> notes = book.notes(); List<NoteBook.Material> materials = book.materials();
        assertThrows(UnsupportedOperationException.class, notes::clear);
        assertThrows(UnsupportedOperationException.class, book.stationRegistry()::clear);
        book.edit(note.id, "Новое", "Текст"); book.addToAir(note.id);
        assertEquals(note.title, notes.get(0).title); assertTrue(materials.isEmpty());
    }

    @Test public void originalRecoveryPreservesKnownDraftAndAdoptsUuidOrphanAsInterrupted() {
        MemoryStorage storage = new MemoryStorage(); NoteBook book = new NoteBook(storage);
        NoteBook.Note note = book.beginRecording("Название", "a", "a1", "v1", 12);
        book.edit(note.id, "Ручной", "Сохранённый текст");
        book.recoverOriginal(note.id, 99, 2000, true, "Остановлено после сбоя");
        NoteBook.Note recovered = new NoteBook(storage).find(note.id);
        assertEquals(12, recovered.createdAt); assertEquals("Ручной", recovered.title);
        assertEquals("Сохранённый текст", recovered.text); assertEquals(1, recovered.revision);
        assertEquals("a", recovered.linkedStation); assertEquals(NoteBook.INTERRUPTED, recovered.recordingStatus);
        String orphan = java.util.UUID.randomUUID().toString();
        book.recoverOriginal(orphan, 100, 3000, true, "Восстановлено");
        assertEquals(NoteBook.INTERRUPTED, new NoteBook(storage).find(orphan).recordingStatus);
        assertThrows(IllegalArgumentException.class, () -> book.recoverOriginal("../escape", 0, 1, true, null));
    }

    @Test public void audioDurationClampsOnlyOriginalPositionAndDoesNotEditText() {
        NoteBook book = new NoteBook(new MemoryStorage()); NoteBook.Note note = draft(book);
        book.edit(note.id, "Заголовок", "Текст."); book.finishRecording(note.id, 10000, true, false, null);
        book.setOriginalPosition(note.id, 20000); assertEquals(10000, book.find(note.id).positionMs);
        book.finishRecording(note.id, 5000, true, true, "Прервано");
        assertEquals(5000, book.find(note.id).positionMs); assertEquals("Текст.", book.find(note.id).text);
        assertEquals(1, book.find(note.id).revision);
        assertThrows(IllegalArgumentException.class, () -> book.setOriginalPosition(note.id, -1));
    }

    @Test public void limitsRejectNewDataAndLeavePreviousSnapshotIntact() {
        MemoryStorage storage = new MemoryStorage(); NoteBook book = new NoteBook(storage);
        NoteBook.Note first = draft(book);
        assertThrows(IllegalArgumentException.class, () -> book.edit(first.id, repeat('a',201), "Text"));
        assertThrows(IllegalArgumentException.class, () -> book.edit(first.id, "Title", repeat('a',20001)));
        assertThrows(IllegalArgumentException.class, () -> book.addToAir(first.id));
        for (int i=1;i<100;i++) draft(book);
        byte[] last = storage.bytes.clone();
        assertThrows(IllegalStateException.class, () -> draft(book));
        assertArrayEquals(last, storage.bytes); assertEquals(100, new NoteBook(storage).notes().size());
    }

    @Test public void metadataByteLimitRollsBackAnOversizedLedgerCommit() {
        MemoryStorage storage = new MemoryStorage(); NoteBook book = new NoteBook(storage);
        String text = repeat('a',20000); boolean rejected = false; int accepted = 0;
        for(int i=0;i<300;i++) {
            try { book.addSavedMaterial("s", "Станция", new Article("id"+i,"v1","Title",text)); accepted++; }
            catch(IllegalStateException limit) { rejected=true; break; }
        }
        assertTrue(rejected); assertTrue(storage.bytes.length <= NoteBook.MAX_METADATA_BYTES);
        assertEquals(accepted, book.materials().size()); assertEquals(accepted, new NoteBook(storage).materials().size());
    }

    @Test public void corruptionTruncationTrailingBytesAndOversizedCountFailWithoutWipingStorage() {
        MemoryStorage original = new MemoryStorage(); NoteBook book = new NoteBook(original); draft(book);
        byte[] good=original.bytes.clone(); byte[] flipped=good.clone(); flipped[40]^=1;
        for(byte[] corrupt : Arrays.asList(flipped, Arrays.copyOf(good,good.length-1), new byte[0],
                Arrays.copyOf(good,good.length+1), new byte[NoteBook.MAX_METADATA_BYTES+1])) {
            MemoryStorage storage=new MemoryStorage(); storage.bytes=corrupt;
            assertThrows(IllegalStateException.class, () -> new NoteBook(storage));
            assertSame(corrupt,storage.bytes); assertEquals(0,storage.saves);
        }
        byte[] badCount=good.clone(); ByteBuffer.wrap(badCount).putInt(16,Integer.MAX_VALUE); checksum(badCount);
        MemoryStorage storage=new MemoryStorage(); storage.bytes=badCount;
        assertThrows(IllegalStateException.class, () -> new NoteBook(storage)); assertEquals(0,storage.saves);
    }

    @Test public void bookRejectsForeignThreadWithoutWriting() throws InterruptedException {
        MemoryStorage storage = new MemoryStorage(); NoteBook book = new NoteBook(storage);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread worker=new Thread(() -> { try { draft(book); } catch(Throwable e) { failure.set(e); } });
        worker.start(); worker.join(); assertTrue(failure.get() instanceof IllegalStateException); assertEquals(0,storage.saves);
    }

    static NoteBook.Note draft(NoteBook book) { return book.beginRecording("Заметка",null,null,null,123); }
    static Article article(String id) { return Article.fromSentences(id,"v1","Текст "+id,"Первая фраза.","Вторая фраза."); }
    static String repeat(char value,int count) { char[] chars=new char[count]; Arrays.fill(chars,value); return new String(chars); }
    static void checksum(byte[] bytes) { CRC32 crc=new CRC32(); crc.update(bytes,0,bytes.length-8); ByteBuffer.wrap(bytes).putLong(bytes.length-8,crc.getValue()); }
    static final class MemoryStorage implements NoteBook.Storage {
        byte[] bytes; boolean fail; int saves;
        @Override public byte[] load() { return bytes == null ? null : bytes.clone(); }
        @Override public void save(byte[] bytes) { if(fail) throw new IllegalStateException("IO failure"); this.bytes=bytes.clone(); saves++; }
    }
}

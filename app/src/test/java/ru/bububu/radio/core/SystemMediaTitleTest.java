package ru.bububu.radio.core;

import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.bububu.radio.sources.SourceItem;
import ru.bububu.radio.sources.SourceRecord;
import ru.bububu.radio.sources.SourceVersionRecord;
import static org.junit.Assert.*;

public final class SystemMediaTitleTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static String title(NoteBook book, String station, Article article) {
        return SystemMediaTitle.resolve(book, true, station, article, Collections.emptyList());
    }

    @Test public void selectedPersonalNoteIsRedactedWithoutChangingItsBookOrAirSnapshot() {
        NoteBookTest.MemoryStorage store = new NoteBookTest.MemoryStorage();
        NoteBook book = new NoteBook(store);
        NoteBook.Note note = book.beginRecording("Первый", null, null, null, 1);
        book.finishRecording(note.id, 0, false, false, null);
        book.edit(note.id, "Первый", "Синтетический личный текст.");
        NoteBook.Material selected = book.addToAir(note.id);
        Article article = selected.asArticle();
        byte[] committed = store.load();
        int saves = store.saves;

        assertEquals(SystemMediaTitle.PRIVATE_NOTE, title(book, selected.stationId, article));
        assertEquals(SystemMediaTitle.PRIVATE_NOTE, title(book, selected.stationId, article));
        assertEquals("Первый", article.title);
        assertEquals("Первый", book.find(note.id).title);
        assertSame(selected, book.material(selected.stationId, article.id));
        assertArrayEquals(committed, store.load());
        assertEquals(saves, store.saves);
    }

    private final class News {
        final SourceBookTest.Store store = new SourceBookTest.Store(temporary.newFolder());
        final NoteBook book = new NoteBook(store);
        final SourceRecord source = SourceRecord.create("RSS", "https://example.test/feed", "Новости",
                "https://example.test/feed", "rss1", true);
        final SourceItem item = SourceItem.create(source.id, "first", null,
                "https://example.test/first", "Первый", 0, 0, 1);
        final SourceVersionRecord version;
        final NoteBook.Material selected;
        News() throws Exception {
            book.commitSource(source, List.of(item));
            version = SourceBookTest.make(source, item, "Синтетическая публичная новость.", "full");
            book.saveSourceVersion(version);
            selected = book.selectSourceVersion(version, false);
        }
    }

    @Test public void selectedNewsKeepsItsVerifiedTitleWithoutSavingState() throws Exception {
        News news = new News();
        byte[] committed = news.store.load();
        int saves = news.store.saves;
        assertEquals(news.version.displayTitle, title(news.book, news.source.stationId, news.selected.asArticle()));
        assertArrayEquals(committed, news.store.load());
        assertEquals(saves, news.store.saves);
    }

    @Test public void staleVersionWrongStationAndAlteredTitleCannotBorrowNewsProvenance() throws Exception {
        News news = new News();
        Article exact = news.selected.asArticle();
        assertEquals(SystemMediaTitle.NEUTRAL, title(news.book, news.source.stationId,
                new Article(exact.id, "stale-version", "Частный заголовок", exact.text)));
        assertEquals(SystemMediaTitle.NEUTRAL, title(news.book, "unbound-station", exact));
        assertEquals(SystemMediaTitle.NEUTRAL, title(news.book, news.source.stationId,
                new Article(exact.id, exact.version, "Частный заголовок", exact.text)));
        news.book.removeSavedMaterial(news.source.stationId, exact.id);
        assertEquals(SystemMediaTitle.NEUTRAL, title(news.book, news.source.stationId, exact));
    }

    @Test public void missingAuthorityAndLegacyTextFailClosedEvenWithPublicLookingNames() throws Exception {
        News news = new News();
        Article exact = news.selected.asArticle();
        assertEquals(SystemMediaTitle.NEUTRAL, title(null, news.source.stationId, exact));
        assertEquals(SystemMediaTitle.NEUTRAL, SystemMediaTitle.resolve(news.book, false,
                news.source.stationId, exact, Collections.emptyList()));
        assertEquals(SystemMediaTitle.NEUTRAL, title(news.book, news.source.stationId, null));
        NoteBook legacy = new NoteBook(new NoteBookTest.MemoryStorage());
        Article personal = new Article("source-looking-id", "v1", "Синтетический личный заголовок", "Личный текст.");
        legacy.addSavedMaterial("news-looking-station", "Новости", personal);
        assertEquals(SystemMediaTitle.NEUTRAL, title(legacy, "news-looking-station", personal));
    }

    @Test public void uncertainBookDoesNotPublishEvenPreviouslyVerifiedNews() throws Exception {
        News news = new News();
        news.store.failAfter = true;
        news.store.unreadable = true;
        try {
            news.book.setSourceRead(news.source.id, news.item.itemKey, true);
            fail("Expected an unconfirmed save");
        } catch (IllegalStateException expected) { }
        assertTrue(news.book.storageUncertain());
        assertEquals(SystemMediaTitle.NEUTRAL, title(news.book, news.source.stationId, news.selected.asArticle()));
    }

    @Test public void fixtureTitleRequiresExactBundledContentAndCannotOverrideBookMaterial() {
        NoteBook book = new NoteBook(new NoteBookTest.MemoryStorage());
        Article exact = new Article("fixture", "v1", "Учебная новость", "Вымышленный текст.");
        List<Station> fixtures = List.of(new Station("A", "Тестовая станция", List.of(exact)));
        assertEquals(exact.title, SystemMediaTitle.resolve(book, true, "A", exact, fixtures));
        Article forged = new Article(exact.id, exact.version, "Частный заголовок", exact.text);
        assertEquals(SystemMediaTitle.NEUTRAL, SystemMediaTitle.resolve(book, true, "A", forged, fixtures));
        book.addSavedMaterial("A", "Новости", exact);
        assertEquals(SystemMediaTitle.NEUTRAL, SystemMediaTitle.resolve(book, true, "A", exact, fixtures));
    }
}

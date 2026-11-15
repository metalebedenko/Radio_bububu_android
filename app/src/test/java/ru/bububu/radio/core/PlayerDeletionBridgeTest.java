package ru.bububu.radio.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import ru.bububu.radio.sources.SourceItem;
import ru.bububu.radio.sources.SourceRecord;
import ru.bububu.radio.sources.SourceVersionRecord;
import static org.junit.Assert.*;

/** Actual Book/Player/RadioStateAuthority seam, including the stale-catalog pre-apply failure. */
public final class PlayerDeletionBridgeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void sameStationPreApplyFaultCannotStartDeletedBAndRetryRestartRemainDeleted() throws Exception {
        preApply(false);
    }

    @Test public void nextStationPreApplyFaultKeepsARequestAndCannotStartRetiredB() throws Exception {
        preApply(true);
    }

    private void preApply(boolean nextStation) throws Exception {
        Bridge b = new Bridge(temporary.newFolder(), nextStation, 12);
        b.player.play();
        String request = b.player.snapshot().currentRequestId;
        Task deadline = b.clock.latest();
        PlayerController.SavedProgress oldB = b.saved(b.materialB);
        String cursorB = b.player.savedState().stationArticleIds.get(b.materialB.stationId);
        int stops = b.speech.stops;
        assertTrue(b.deleteB());
        List<Station> malformed = b.catalog();
        malformed.add(malformed.get(0));
        try {
            b.player.projectCommittedDeletion(malformed, b.book.reconcileState(b.authority.persisted()));
            fail("Expected failure before RAM apply");
        } catch (IllegalArgumentException expected) { }
        assertTrue("B is deliberately still in the stale runtime", contains(b.player.stations(), b.materialB));
        assertEquals(request, b.player.snapshot().currentRequestId);
        assertSame(deadline, b.clock.latest());
        assertFalse(deadline.cancelled);
        assertEquals(stops, b.speech.stops);
        b.finishA();
        assertEquals(units(b.materialA.asArticle(), 12), b.speech.requests.size());
        assertFalse(b.player.snapshot().playing);
        assertEquals(b.materialA.articleId, b.player.snapshot().article.id);
        assertSameProgress(oldB, b.saved(b.materialB));
        assertEquals(cursorB, b.player.savedState().stationArticleIds.get(b.materialB.stationId));
        assertEquals(stops, b.speech.stops);
        long sequence = b.book.sequence();
        assertFalse(b.deleteB());
        assertEquals(sequence, b.book.sequence());
        b.project();
        assertFalse(contains(b.player.stations(), b.materialB));
        Bridge restored = b.restart();
        assertFalse(contains(restored.player.stations(), b.materialB));
        assertFalse(restored.player.snapshot().playing);
        assertTrue(restored.speech.requests.isEmpty());
        restored.player.play();
        assertTrue(restored.speech.requests.isEmpty());
    }

    @Test public void sameStationPostApplyJsonFailureKeepsSubmittedAThenBlocksItsNextFragment() throws Exception {
        postApply(false);
    }

    @Test public void nextStationPostApplyJsonFailureKeepsSubmittedAThenBlocksItsNextFragment() throws Exception {
        postApply(true);
    }

    private void postApply(boolean nextStation) throws Exception {
        Bridge b = new Bridge(temporary.newFolder(), nextStation, 12);
        b.player.play();
        String request = b.player.snapshot().currentRequestId;
        Task deadline = b.clock.latest();
        assertTrue(b.deleteB());
        b.radio.failSave = true;
        b.project(); // RAM applies; Player.changed catches the distinct JSON write failure.
        assertFalse(contains(b.player.stations(), b.materialB));
        assertFalse(b.authority.known());
        assertEquals(request, b.player.snapshot().currentRequestId);
        assertSame(deadline, b.clock.latest());
        assertFalse(deadline.cancelled);
        assertEquals(0, b.speech.stops);
        b.player.onStart(request);
        b.player.onDone(request);
        assertEquals("Unknown radio authority cannot submit the next fragment", 1, b.speech.requests.size());
        assertFalse(b.player.snapshot().playing);
        assertNull(b.player.snapshot().currentRequestId);
        assertEquals(0, b.speech.stops);
        b.radio.failSave = false;
        Bridge restored = b.restart();
        assertFalse(contains(restored.player.stations(), b.materialB));
        assertFalse(restored.player.snapshot().playing);
        assertTrue(restored.speech.requests.isEmpty());
        restored.player.play();
        restored.finishA();
        assertEquals(units(restored.materialA.asArticle(), 12), restored.speech.requests.size());
    }

    @Test public void deletedCurrentSourceRestoresChoiceRequiredAndExplicitSelectionKeepsPauseAndCursor() throws Exception {
        Bridge b = new Bridge(temporary.newFolder(), true, 1000);
        b.player.selectStation(b.sourceB.stationId);
        b.player.seekToFraction(1);
        int anchor = b.player.snapshot().sentenceIndex;
        b.player.selectStation(b.sourceA.stationId);
        b.player.play();
        b.player.pause();
        int stopped = b.speech.stops;
        assertTrue(b.book.deleteSource(b.sourceA.id, b.authority.persisted()));
        b.project();
        assertTrue(b.player.snapshot().selectionRequired);
        assertNull(b.player.snapshot().article);
        assertEquals(b.sourceA.stationId, b.player.savedState().currentStationId);
        int submitted = b.speech.requests.size();
        b.player.play();
        assertEquals(submitted, b.speech.requests.size());
        assertEquals(stopped, b.speech.stops);
        Bridge restored = b.restart();
        assertTrue(restored.player.snapshot().selectionRequired);
        restored.player.play();
        assertTrue(restored.speech.requests.isEmpty());
        restored.player.selectStation(b.sourceB.stationId);
        assertFalse(restored.player.snapshot().selectionRequired);
        assertFalse(restored.player.snapshot().playing);
        assertEquals(anchor, restored.player.snapshot().sentenceIndex);
        assertTrue(restored.speech.requests.isEmpty());
        restored.player.play();
        assertEquals(1, restored.speech.requests.size());
    }

    @Test public void unaffectedDragRemapsByIdentityAndCheckpointNeverStopsOtherOutput() throws Exception {
        Bridge b = new Bridge(temporary.newFolder(), true, 1000);
        b.player.selectStation(b.sourceB.stationId);
        b.player.play();
        long drag = b.player.beginSeek();
        int stops = b.speech.stops;
        b.player.checkpoint();
        assertEquals(stops, b.speech.stops);
        assertTrue(b.book.deleteSource(b.sourceA.id, b.authority.persisted()));
        List<Station> reordered = b.catalog();
        Collections.reverse(reordered);
        b.player.projectCommittedDeletion(reordered, b.book.reconcileState(b.authority.persisted()));
        assertTrue(b.player.snapshot().seeking);
        assertEquals(0, b.player.snapshot().stationIndex);
        assertEquals(stops, b.speech.stops);
        b.player.updateSeek(drag, 1);
        b.player.commitSeek(drag);
        assertEquals(b.materialB.articleId, b.player.snapshot().article.id);
        assertEquals(b.materialB.asArticle().sentences.size() - 1, b.player.snapshot().sentenceIndex);
        assertTrue(b.player.snapshot().playing);
    }

    @Test public void lifetimeMembershipIsPureAndWrongPayloadNeverReceivesStartedMark() throws Exception {
        Bridge b = new Bridge(temporary.newFolder(), true, 1000);
        PlaybackMembership gate = b.gate();
        Article exact = b.materialA.asArticle();
        PlaybackMembership.Binding binding = gate.capture(b.materialA.stationId, exact);
        int reads = b.store.reads, saves = b.store.saves, radioReads = b.radio.reads, radioSaves = b.radio.saves;
        for (int i = 0; i < 100; i++) assertEquals(PlaybackMembership.Admission.ALLOWED, gate.check(b.materialA.stationId, exact, binding));
        assertEquals(reads, b.store.reads); assertEquals(saves, b.store.saves);
        assertEquals(radioReads, b.radio.reads); assertEquals(radioSaves, b.radio.saves);
        Article changed = new Article(exact.id, exact.version, exact.title, "Different payload.");
        assertEquals(PlaybackMembership.Admission.NOT_MEMBER, gate.check(b.materialA.stationId, changed, binding));
        assertEquals(PlaybackMembership.Admission.NOT_MEMBER, gate.check(b.materialA.stationId, exact,
                new PlaybackMembership.Binding(binding.sequence + 1, binding.sourceVersionKey, "book")));
        Speech speech = new Speech();
        PlayerController wrong = new PlayerController(Collections.singletonList(new Station(b.sourceA.stationId, b.sourceA.name,
                Collections.singletonList(changed))), speech, b.clock, b.authority, state -> { }, 1000, gate);
        wrong.play();
        assertTrue(speech.requests.isEmpty());
        assertFalse(wrong.savedState().progress.get(PlayerController.progressKey(b.sourceA.stationId, changed.id, changed.version)).started);
    }

    private static int units(Article article, int limit) {
        int count = 0; for (Article.Sentence sentence : article.sentences) count += TextSegments.fragments(sentence.text, limit).size(); return count;
    }
    private static boolean contains(List<Station> stations, NoteBook.Material m) {
        for (Station s : stations) if (s.id.equals(m.stationId)) for (Article a : s.articles)
            if (a.id.equals(m.articleId) && a.version.equals(m.version)) return true;
        return false;
    }
    private static void assertSameProgress(PlayerController.SavedProgress a, PlayerController.SavedProgress b) {
        assertEquals(a.sentenceIndex, b.sentenceIndex); assertEquals(a.passEnded, b.passEnded);
        assertEquals(a.started, b.started); assertEquals(a.read, b.read); assertEquals(a.completedSentences, b.completedSentences);
    }

    private static final class Store extends SourceBookTest.Store {
        int reads;
        Store(File directory) { super(directory); }
        @Override public byte[] load() { reads++; return super.load(); }
        @Override public UpgradeRollbackRef prepareSchema3Upgrade(byte[] baseline, int target) {
            return capsules.prepareSchema3Upgrade(baseline, target, new byte[][] {bytes, null, null});
        }
        @Override public void verifyUpgradeRollback(UpgradeRollbackRef ref) { capsules.verifyUpgradeRollback(ref); }
    }
    private static final class RadioStore implements PlayerController.StateStore {
        PlayerController.SavedState state;
        boolean failSave;
        int reads, saves;
        public PlayerController.SavedState load() { reads++; return state; }
        public void save(PlayerController.SavedState value) {
            saves++; if (failSave) throw new IllegalStateException("JSON save failure after RAM apply"); state = value;
        }
    }
    private static final class Speech implements PlayerController.SpeechEngine {
        final List<String> requests = new ArrayList<>();
        int stops;
        public void speak(String text, String id) { requests.add(text); }
        public void stop() { stops++; }
    }
    private static final class Task implements PlayerController.Cancel {
        boolean cancelled;
        final Runnable action;
        Task(Runnable action) { this.action = action; }
        public void cancel() { cancelled = true; }
    }
    private static final class Clock implements PlayerController.Scheduler {
        final List<Task> tasks = new ArrayList<>();
        public PlayerController.Cancel schedule(long delay, Runnable action) { Task task = new Task(action); tasks.add(task); return task; }
        Task latest() { return tasks.get(tasks.size() - 1); }
    }
    private static final class Bridge {
        final Store store;
        final RadioStore radio;
        final NoteBook book;
        final RadioStateAuthority authority;
        final Speech speech = new Speech();
        final Clock clock = new Clock();
        final SourceRecord sourceA, sourceB;
        final SourceItem itemA, itemB;
        final NoteBook.Material materialA, materialB;
        final boolean nextStation;
        final int limit;
        final PlayerController player;
        Bridge(File directory, boolean nextStation, int limit) {
            this.store = new Store(directory); this.radio = new RadioStore(); this.book = new NoteBook(store);
            this.nextStation = nextStation; this.limit = limit;
            sourceA = SourceRecord.create("RSS", "a", "Source A", "https://example.test/a", "rss1", true);
            sourceB = nextStation ? SourceRecord.create("RSS", "b", "Source B", "https://example.test/b", "rss1", true) : sourceA;
            itemA = SourceItem.create(sourceA.id, "A", null, null, "A", 0, 0, 0);
            itemB = SourceItem.create(sourceB.id, "B", null, null, "B", 0, 0, 0);
            book.commitSource(sourceA, nextStation ? Collections.singletonList(itemA) : java.util.Arrays.asList(itemA, itemB));
            if (nextStation) book.commitSource(sourceB, Collections.singletonList(itemB));
            SourceVersionRecord a = SourceBookTest.make(sourceA, itemA, "Allowed A long sentence with several fragments. Allowed A final sentence.", "full");
            SourceVersionRecord b = SourceBookTest.make(sourceB, itemB, "FORBIDDEN_B payload must never be spoken.", "full");
            book.saveSourceVersion(a); materialA = book.selectSourceVersion(a, false);
            book.saveSourceVersion(b); materialB = book.selectSourceVersion(b, false);
            authority = new RadioStateAuthority(radio, book::reconcileState, book::encodeCursorProvenance);
            player = create(); player.checkpoint();
        }
        private Bridge(Bridge old) {
            store = old.store; radio = old.radio; book = new NoteBook(store);
            nextStation = old.nextStation; limit = old.limit;
            sourceA = old.sourceA; sourceB = old.sourceB; itemA = old.itemA; itemB = old.itemB;
            materialA = old.materialA; materialB = old.materialB;
            authority = new RadioStateAuthority(radio, book::reconcileState, book::encodeCursorProvenance);
            player = create(); player.checkpoint();
        }
        PlayerController create() {
            PlayerController.StateStore bridge = new PlayerController.StateStore() {
                public PlayerController.SavedState load() { return authority.load(); }
                public void save(PlayerController.SavedState value) { authority.save(value); book.captureSourceHistory(value); }
            };
            return new PlayerController(catalog(), speech, clock, bridge, state -> { }, limit, gate());
        }
        PlaybackMembership gate() {
            return new PlaybackMembership() {
                public Binding capture(String station, Article article) {
                    NoteBook.Material m = book.material(station, article.id);
                    return m == null ? null : new Binding(m.sequence, m.sourceVersionKey, "book");
                }
                public Admission check(String station, Article article, Binding binding) {
                    if (binding == null || book.storageUncertain() || !authority.known()) return Admission.UNVERIFIED;
                    return Admission.valueOf(book.queueMembership(station, article, binding.sequence, binding.sourceVersionKey).name());
                }
                public boolean stationRetired(String station) { return book.retiredStation(station); }
            };
        }
        List<Station> catalog() {
            List<Station> result = new ArrayList<>();
            for (Station s : book.stationRegistry()) {
                List<Article> articles = new ArrayList<>();
                for (NoteBook.Material m : book.materials()) if (m.stationId.equals(s.id)) articles.add(m.asArticle());
                result.add(new Station(s.id, s.name, articles));
            }
            return result;
        }
        boolean deleteB() { return nextStation ? book.deleteSource(sourceB.id, authority.persisted())
                : book.deleteSourceItem(sourceB.id, itemB.itemKey, authority.persisted()); }
        void project() { player.projectCommittedDeletion(catalog(), book.reconcileState(authority.persisted())); }
        void finishA() {
            int left = 100;
            while (player.snapshot().playing && left-- > 0) {
                assertEquals(materialA.articleId, player.snapshot().article.id);
                String id = player.snapshot().currentRequestId; assertNotNull(id);
                player.onStart(id); player.onDone(id);
            }
            assertTrue("Completion must be bounded", left > 0);
        }
        PlayerController.SavedProgress saved(NoteBook.Material m) {
            return player.savedState().progress.get(PlayerController.progressKey(m.stationId, m.articleId, m.version));
        }
        Bridge restart() { return new Bridge(this); }
    }
}

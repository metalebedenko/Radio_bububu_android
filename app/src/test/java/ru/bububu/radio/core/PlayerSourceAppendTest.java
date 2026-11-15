package ru.bububu.radio.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** SP-1 exercises the same production controller and its actual first persistence/emit. */
public class PlayerSourceAppendTest {
    private final Article source = Article.fromSentences("source", "v1", "Заголовок", "Раз.", "Два.", "Три.");
    private final Article other = Article.fromSentences("other", "v1", "Другая", "Один.", "Два.", "Три.");

    @Test public void partialRemoveReaddInstallsAllFieldsBeforeFirstSaveAndEmit() {
        Harness h = new Harness(source, other);
        h.p.play(); h.complete(); h.p.pause(); h.p.toggleRead();
        PlayerController.SavedProgress archived = h.progress(source);
        assertEquals(1, archived.sentenceIndex); assertEquals(Arrays.asList(0), archived.completedSentences);
        assertTrue(archived.started); assertTrue(archived.read);
        h.p.removeMaterial("s", source.id, NoteBook.boundaryMarker(1, null));
        h.saved.clear(); h.emitted.clear();
        assertTrue(h.p.appendMaterial("s", "Станция", source, archived));
        assertEquals(1, h.saved.size()); assertEquals(1, h.emitted.size());
        assertProgress(archived, h.saved.get(0).progress.get(PlayerController.progressKey("s", "source", "v1")));
        assertProgress(archived, h.emitted.get(0).progress.get(PlayerController.progressKey("s", "source", "v1")));
        assertFalse(h.p.snapshot().playing);
    }

    @Test public void readdWhileAnotherTextPlaysKeepsRequestWatchdogCursorAndSubmissions() {
        Harness h = new Harness(source, other);
        h.p.play(); h.complete(); h.p.pause();
        PlayerController.SavedProgress archived = h.progress(source);
        h.p.selectArticle("s", "other"); h.p.play(); h.p.onStart(h.p.snapshot().currentRequestId);
        String active = h.p.snapshot().currentRequestId; int stops = h.stops, submissions = h.requests.size();
        int timers = h.tasks.size();
        h.p.removeMaterial("s", source.id, NoteBook.boundaryMarker(1, null));
        h.saved.clear(); h.emitted.clear();
        h.p.appendMaterial("s", "Станция", source, archived);
        assertEquals(active, h.p.snapshot().currentRequestId); assertEquals("other", h.p.snapshot().article.id);
        assertTrue(h.p.snapshot().playing); assertEquals(stops, h.stops);
        assertEquals(submissions, h.requests.size()); assertEquals(timers, h.tasks.size());
        assertProgress(archived, h.progress(source)); assertProgress(archived, h.saved.get(0).progress.get(PlayerController.progressKey("s", "source", "v1")));
        h.p.onDone(active); assertEquals("other", h.p.snapshot().article.id);
        assertEquals(1, h.p.snapshot().sentenceIndex); assertEquals(submissions + 1, h.requests.size());
    }

    @Test public void duplicateCannotRollbackMoreRecentLiveProgress() {
        Harness h = new Harness(other);
        PlayerController.SavedProgress old = seed(source, 1, false, Arrays.asList(0), true, true);
        h.p.appendMaterial("s", "Станция", source, old);
        h.p.selectArticle("s", "source"); h.p.play(); h.complete();
        PlayerController.SavedProgress newer = h.progress(source); int writes = h.saved.size(), events = h.emitted.size();
        String active = h.p.snapshot().currentRequestId;
        assertFalse(h.p.appendMaterial("s", "Станция", source, old));
        assertProgress(newer, h.progress(source)); assertEquals(active, h.p.snapshot().currentRequestId);
        assertEquals(writes, h.saved.size()); assertEquals(events, h.emitted.size());
    }

    @Test public void newVersionStartsAtZeroWhileStickyReadCanRemainTrue() {
        Harness h = new Harness(source);
        h.p.removeMaterial("s", "source", NoteBook.boundaryMarker(1, null));
        Article v2 = Article.fromSentences("source", "v2", "Новое", "Другой текст.", "Продолжение.");
        h.p.appendMaterial("s", "Станция", v2, seed(v2, 0, false, Collections.emptyList(), true, false));
        PlayerController.SavedProgress now = h.progress(v2);
        assertEquals(0, now.sentenceIndex); assertTrue(now.completedSentences.isEmpty());
        assertTrue(now.read); assertFalse(now.started); assertFalse(now.passEnded);
        assertNull(h.p.snapshot().article); assertFalse(h.p.snapshot().playing);
    }

    @Test public void invalidSeedsHaveNoMutationPersistenceOrAudioEffects() {
        Harness h = new Harness(other); h.p.play();
        List<PlayerController.SavedProgress> bad = Arrays.asList(
            new PlayerController.SavedProgress("wrong", "source", "v1", 0, false, Collections.emptyList(), false, false),
            new PlayerController.SavedProgress("s", "wrong", "v1", 0, false, Collections.emptyList(), false, false),
            new PlayerController.SavedProgress("s", "source", "v2", 0, false, Collections.emptyList(), false, false),
            seed(source, -1, false, Collections.emptyList(), false, false),
            seed(source, 3, false, Collections.emptyList(), false, false),
            seed(source, 0, true, Collections.emptyList(), false, true),
            seed(source, 1, false, Arrays.asList(0,0), false, true),
            seed(source, 1, false, Arrays.asList((Integer)null), false, true),
            seed(source, 1, false, Arrays.asList(3), false, true),
            seed(source, 1, false, Arrays.asList(0), false, false));
        int writes = h.saved.size(), emits = h.emitted.size(), requests = h.requests.size(), stops = h.stops;
        String active = h.p.snapshot().currentRequestId;
        for (PlayerController.SavedProgress seed : bad)
            assertThrows(IllegalArgumentException.class, () -> h.p.appendMaterial("s", "Станция", source, seed));
        assertEquals(1, h.p.stations().get(0).articles.size()); assertEquals(active, h.p.snapshot().currentRequestId);
        assertEquals(writes, h.saved.size()); assertEquals(emits, h.emitted.size());
        assertEquals(requests, h.requests.size()); assertEquals(stops, h.stops);
    }

    @Test public void sparseCoverageEndedPassAndPausedSeekAreValidExactSeeds() {
        Harness h = new Harness(other);
        PlayerController.SavedProgress sparse = seed(source, 2, true, Arrays.asList(0,2), true, true);
        h.p.appendMaterial("s", "Станция", source, sparse); assertProgress(sparse, h.progress(source));
        h.p.removeMaterial("s", "source", NoteBook.boundaryMarker(1, null));
        PlayerController.SavedProgress pausedSeek = seed(source, 1, false, Collections.emptyList(), false, false);
        h.p.appendMaterial("s", "Станция", source, pausedSeek); assertProgress(pausedSeek, h.progress(source));
        assertEquals(0, h.requests.size());
    }

    @Test public void failedFirstSaveDoesNotEmitZeroOrLoseInstalledProgress() {
        Harness h = new Harness(other); h.failSave = true;
        PlayerController.SavedProgress partial = seed(source, 1, false, Arrays.asList(0), false, true);
        h.p.appendMaterial("s", "Станция", source, partial);
        assertProgress(partial, h.progress(source)); assertProgress(partial, h.emitted.get(0).progress.get(PlayerController.progressKey("s", "source", "v1")));
        assertNotNull(h.p.snapshot().error);
    }

    @Test public void emptyStationAppendNeverSelectsOrRevokesAndIsIdempotent() {
        Harness h = new Harness(other); h.p.play(); String active = h.p.snapshot().currentRequestId;
        assertTrue(h.p.appendStation("empty", "Пустая")); assertEquals(active, h.p.snapshot().currentRequestId);
        assertEquals("s", h.p.snapshot().station.id); assertEquals(0, h.p.stations().get(1).articles.size());
        int writes = h.saved.size(); assertFalse(h.p.appendStation("empty", "Пустая")); assertEquals(writes,h.saved.size());
        assertThrows(IllegalArgumentException.class, () -> h.p.appendStation("empty", "Подмена"));
    }

    private PlayerController.SavedProgress seed(Article a,int index,boolean ended,List<Integer> completed,boolean read,boolean started) {
        return new PlayerController.SavedProgress("s", a.id, a.version, index, ended, completed, read, started);
    }
    private static void assertProgress(PlayerController.SavedProgress a,PlayerController.SavedProgress b) {
        assertNotNull(b); assertEquals(a.stationId,b.stationId); assertEquals(a.articleId,b.articleId); assertEquals(a.version,b.version);
        assertEquals(a.sentenceIndex,b.sentenceIndex); assertEquals(a.completedSentences,b.completedSentences);
        assertEquals(a.passEnded,b.passEnded); assertEquals(a.started,b.started); assertEquals(a.read,b.read);
    }
    private static final class Harness {
        PlayerController p; int stops; boolean failSave;
        final List<String> requests=new ArrayList<>(); final List<Runnable> tasks=new ArrayList<>();
        final List<PlayerController.SavedState> saved=new ArrayList<>(),emitted=new ArrayList<>();
        Harness(Article...items) {
            p=new PlayerController(Arrays.asList(new Station("s","Станция",Arrays.asList(items))),new PlayerController.SpeechEngine() {
                @Override public void speak(String text,String id) { requests.add(id); }
                @Override public void stop() { stops++; }
            },(delay,action)->{ tasks.add(action); return () -> {}; },new PlayerController.StateStore() {
                @Override public PlayerController.SavedState load() { return null; }
                @Override public void save(PlayerController.SavedState state) { if(failSave) throw new IllegalStateException("disk"); saved.add(state); }
            },snapshot->{ if(p!=null) emitted.add(p.savedState()); },1000);
        }
        void complete() { String id=p.snapshot().currentRequestId; p.onStart(id); p.onDone(id); }
        PlayerController.SavedProgress progress(Article a) { return p.savedState().progress.get(PlayerController.progressKey("s",a.id,a.version)); }
    }
}

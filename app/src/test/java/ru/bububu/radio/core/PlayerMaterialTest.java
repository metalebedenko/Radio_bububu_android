package ru.bububu.radio.core;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** Exercises the real book codec together with the production player and schema1 cursor maps. */
public class PlayerMaterialTest {
    @Test public void appendPreservesActiveRequestCoveragePositionAndStation() {
        Harness h = new Harness(); h.player.play(); h.complete(); h.player.toggleRead();
        String active = h.player.snapshot().currentRequestId; int stops=h.speech.stops, spoken=h.speech.requests.size();
        PlayerController.SavedProgress before=progress(h.player,"a","a1");
        h.append("dyn",NoteBookTest.article("d1"));
        assertEquals("a",h.player.snapshot().station.id); assertEquals("a1",h.player.snapshot().article.id);
        assertEquals(active,h.player.snapshot().currentRequestId); assertTrue(h.player.snapshot().playing);
        assertEquals(stops,h.speech.stops); assertEquals(spoken,h.speech.requests.size());
        assertProgress(before,progress(h.player,"a","a1")); assertEquals("dyn",h.player.stations().get(3).id);
        h.complete(); assertEquals("a2",h.player.snapshot().article.id);
    }

    @Test public void duplicateAndMutatedAppendNeverTouchActiveSpeechOrCatalog() {
        Harness h = new Harness(); NoteBook.Material selected=h.append("dyn",NoteBookTest.article("d1"));
        h.player.play(); String active=h.player.snapshot().currentRequestId;
        assertFalse(h.player.appendMaterial("dyn","Станция dyn",selected.asArticle()));
        assertThrows(IllegalArgumentException.class, () -> h.player.appendMaterial("dyn","Станция dyn",new Article("d1","v2","X","Другой текст.")));
        assertThrows(IllegalArgumentException.class, () -> h.player.appendMaterial("dyn","Станция dyn",new Article("d1","v1",selected.title,"Подмена.")));
        assertThrows(IllegalArgumentException.class, () -> h.player.appendMaterial("dyn","Станция dyn",new Article("@after:1","v1","X","Текст.")));
        assertEquals(active,h.player.snapshot().currentRequestId); assertEquals(1,h.player.stations().get(3).articles.size());
    }

    @Test public void currentMiddleRemovalPausesAndRevokesBeforeReentrantAndLateCallbacks() {
        Harness h=new Harness(); h.addThree(); h.player.selectArticle("dyn","d2"); h.player.play();
        String old=h.player.snapshot().currentRequestId; h.player.onStart(old); Runnable oldTimeout=h.scheduler.latest();
        h.speech.onStop=() -> { h.player.onDone(old); h.player.onError(old,"Поздно"); };
        h.remove("dyn","d2");
        assertEquals("d3",h.player.snapshot().article.id); assertFalse(h.player.snapshot().playing);
        assertNull(h.player.snapshot().currentRequestId); assertTrue(h.player.isOutputStopped());
        h.player.onDone(old); h.player.onStart(old); h.player.onError(old,"Ошибка"); oldTimeout.run();
        assertTrue(h.player.snapshot().completedSentences.isEmpty()); assertEquals(1,h.speech.requests.size());
        h.player.play(); assertEquals("d3",h.player.snapshot().article.id); assertEquals(2,h.speech.requests.size());
        assertFalse(progress(h.player,"dyn","d1").started);
    }

    @Test public void currentLastRemovalKeepsEndDespiteUnfinishedEarlierText() {
        Harness h=new Harness(); h.addThree(); h.player.selectArticle("dyn","d3"); h.player.play();
        NoteBook.Material removed=h.remove("dyn","d3");
        assertNull(h.player.snapshot().article); assertFalse(h.player.snapshot().playing);
        assertEquals(removed.boundaryMarker(),h.player.savedState().stationArticleIds.get("dyn"));
        int before=h.speech.requests.size(); h.player.play();
        assertEquals(before,h.speech.requests.size()); assertTrue(h.player.snapshot().broadcastEnded);
        assertFalse(progress(h.player,"dyn","d1").started); assertFalse(progress(h.player,"dyn","d2").started);
    }

    @Test public void removingEarlierNonCurrentTextPreservesActiveRequestAndShiftedAnchor() {
        Harness h=new Harness(); h.addThree(); h.player.selectArticle("dyn","d3"); h.player.play();
        String active=h.player.snapshot().currentRequestId; int stops=h.speech.stops;
        h.remove("dyn","d1");
        assertEquals("d3",h.player.snapshot().article.id); assertEquals(1,h.player.snapshot().articleIndex);
        assertEquals(active,h.player.snapshot().currentRequestId); assertEquals(stops,h.speech.stops);
        h.complete(); assertEquals(1,h.player.snapshot().sentenceIndex);
    }

    @Test public void deathAfterLedgerCommitBeforePlayerSaveUsesTombstoneSuccessorInPause() {
        Harness h=new Harness(); h.addThree(); h.player.selectArticle("dyn","d2"); h.player.play();
        h.book.removeSavedMaterial("dyn","d2"); // No player mutation or player-state save occurs before death.
        PlayerController restored=h.restart();
        assertEquals("d3",restored.snapshot().article.id); assertFalse(restored.snapshot().playing);
        assertFalse(progress(restored,"dyn","d1").started); assertEquals(0,h.freshSpeech.requests.size());
        PlayerController.SavedState once=h.book.reconcileState(h.store.state);
        PlayerController.SavedState twice=h.book.reconcileState(once);
        assertEquals(once.stationArticleIds,twice.stationArticleIds);
    }

    @Test public void deathAfterBothCommitsRestoresSameMiddleSuccessorAndPausedCoverage() {
        Harness h=new Harness(); h.addThree(); h.player.selectArticle("dyn","d2"); h.player.play();
        h.remove("dyn","d2"); PlayerController restored=h.restart();
        assertEquals("d3",restored.snapshot().article.id); assertFalse(restored.snapshot().playing);
        assertTrue(restored.snapshot().completedSentences.isEmpty()); assertFalse(restored.snapshot().started);
    }

    @Test public void deathAfterLedgerOnlyLastRemovalDoesNotReturnToEarlierUnfinishedText() {
        Harness h=new Harness(); h.addThree(); h.player.selectArticle("dyn","d3"); h.player.play();
        NoteBook.Material removed=h.book.removeSavedMaterial("dyn","d3");
        PlayerController restored=h.restart(); assertNull(restored.snapshot().article);
        assertEquals(removed.boundaryMarker(),restored.savedState().stationArticleIds.get("dyn"));
        restored.play(); assertFalse(restored.snapshot().playing); assertEquals(0,h.freshSpeech.requests.size());
    }

    @Test public void liveAppendAfterRemovedEndKeepsNullCursorUntilExplicitPlay() {
        Harness h=new Harness(); h.addThree(); h.player.selectArticle("dyn","d3");
        NoteBook.Material removed=h.remove("dyn","d3"); int before=h.speech.requests.size();
        h.append("dyn",NoteBookTest.article("d4")); h.append("dyn",NoteBookTest.article("d5"));
        assertNull(h.player.snapshot().article); assertFalse(h.player.snapshot().playing);
        String marker=h.player.savedState().stationArticleIds.get("dyn");
        assertEquals(removed.sequence,NoteBook.boundarySequence(marker)); assertEquals("d4",NoteBook.boundaryCandidate(marker));
        h.player.nextArticle(); h.player.previousArticle();
        assertNull(h.player.snapshot().article);
        assertEquals(before,h.speech.requests.size()); h.player.play();
        assertEquals("d4",h.player.snapshot().article.id); assertTrue(h.player.snapshot().playing);
    }

    @Test public void markerRestoreAfterNewAppendComputesCandidateButKeepsNullUntilPlay() {
        Harness h=new Harness(); h.addThree(); h.player.selectArticle("dyn","d3");
        NoteBook.Material removed=h.remove("dyn","d3");
        h.book.addSavedMaterial("dyn","Станция dyn",NoteBookTest.article("d4")); // Death before live append.
        PlayerController restored=h.restart();
        assertNull(restored.snapshot().article); assertFalse(restored.snapshot().playing);
        assertEquals(removed.sequence,NoteBook.boundarySequence(restored.savedState().stationArticleIds.get("dyn")));
        assertEquals(0,h.freshSpeech.requests.size()); restored.play();
        assertEquals("d4",restored.snapshot().article.id); assertTrue(restored.snapshot().playing);
    }

    @Test public void rawRemovedIdWithAlreadyPresentSuccessorRestoresThatSuccessorInsteadOfMarker() {
        Harness h=new Harness(); h.addThree(); h.player.selectArticle("dyn","d2");
        h.book.removeSavedMaterial("dyn","d2"); h.book.addSavedMaterial("dyn","Станция dyn",NoteBookTest.article("d4"));
        PlayerController restored=h.restart(); assertEquals("d3",restored.snapshot().article.id);
        assertEquals("d3",restored.savedState().stationArticleIds.get("dyn")); assertFalse(restored.snapshot().playing);
    }

    @Test public void unknownDynamicCursorFailsClosedWithDiagnosticAndNeverStartsAtZero() {
        Harness h=new Harness(); h.addThree(); h.player.selectArticle("dyn","d3");
        Map<String,String> cursors=new LinkedHashMap<>(h.store.state.stationArticleIds); cursors.put("dyn","unknown");
        h.store.state=new PlayerController.SavedState("dyn",cursors,h.store.state.progress);
        PlayerController restored=h.restart();
        assertNull(restored.snapshot().article); assertNotNull(h.book.reconciliationMessage());
        restored.play(); assertFalse(restored.snapshot().playing); assertEquals(0,h.freshSpeech.requests.size());
        h.book.addSavedMaterial("dyn","Станция dyn",NoteBookTest.article("d4"));
        PlayerController again=h.restart(); assertNull(again.snapshot().article); again.play();
        assertEquals("d4",again.snapshot().article.id);
    }

    @Test public void removingAllKeepsRegistrySelectedStationAndMarkerAcrossRestart() {
        Harness h=new Harness(); h.append("dyn",NoteBookTest.article("d1")); h.player.selectArticle("dyn","d1");
        h.remove("dyn","d1"); PlayerController restored=h.restart();
        assertEquals("dyn",restored.snapshot().station.id); assertNull(restored.snapshot().article);
        assertEquals(4,restored.stations().size());
        NoteBook.Material added=h.book.addSavedMaterial("dyn","Станция dyn",NoteBookTest.article("d2"));
        assertTrue(restored.appendMaterial(added.stationId,added.stationName,added.asArticle()));
        assertNull(restored.snapshot().article); restored.play(); assertEquals("d2",restored.snapshot().article.id);
    }

    @Test public void appendAfterNaturalBroadcastEndNeverRestartsOrRewindsOnLoad() {
        Harness h=new Harness(); h.player.play(); while(h.player.snapshot().playing) h.complete();
        assertEquals("b2",h.player.snapshot().article.id); int before=h.speech.requests.size();
        h.append("dyn",NoteBookTest.article("d1"));
        assertEquals("b2",h.player.snapshot().article.id); assertFalse(h.player.snapshot().playing);
        assertEquals(before,h.speech.requests.size()); PlayerController restored=h.restart();
        assertEquals("b2",restored.snapshot().article.id); assertFalse(restored.snapshot().playing);
        restored.play(); assertEquals("d1",restored.snapshot().article.id);
    }

    @Test public void reconciliationAndRemovalPreserveEveryFixtureCursorAndProgressField() {
        Harness h=new Harness(); h.player.play(); h.complete(); h.player.toggleRead(); h.player.pause();
        h.player.selectArticle("b","b2"); h.player.seekToFraction(1); h.player.toggleRead();
        PlayerController.SavedState fixture=h.player.savedState();
        h.addThree(); h.player.selectArticle("dyn","d3"); h.remove("dyn","d3");
        PlayerController restored=h.restart();
        for(String station:Arrays.asList("a","b")) assertEquals(fixture.stationArticleIds.get(station),restored.savedState().stationArticleIds.get(station));
        for(Map.Entry<String,PlayerController.SavedProgress> entry:fixture.progress.entrySet())
            assertProgress(entry.getValue(),restored.savedState().progress.get(entry.getKey()));
        assertNull(restored.snapshot().article);
    }

    @Test public void failedLedgerCommitLeavesLiveCatalogAndActiveRequestUnchanged() {
        Harness h=new Harness(); h.append("dyn",NoteBookTest.article("d1")); h.player.selectArticle("dyn","d1"); h.player.play();
        String active=h.player.snapshot().currentRequestId; h.storage.fail=true;
        assertThrows(IllegalStateException.class, () -> h.append("dyn",NoteBookTest.article("d2")));
        assertThrows(IllegalStateException.class, () -> h.remove("dyn","d1"));
        assertEquals(1,h.player.stations().get(3).articles.size()); assertEquals(active,h.player.snapshot().currentRequestId);
        assertTrue(h.player.snapshot().playing);
    }

    @Test public void engineStopFailureAfterDurableRemovalNeverAuthorizesAnotherAudioOwner() {
        Harness h=new Harness(); h.addThree(); h.player.selectArticle("dyn","d2"); h.player.play();
        h.speech.failStop=true; h.remove("dyn","d2");
        assertEquals("d3",h.player.snapshot().article.id); assertFalse(h.player.isOutputStopped());
        h.player.play(); assertFalse(h.player.snapshot().playing); assertEquals(1,h.speech.requests.size());
        h.speech.failStop=false; h.player.pause(); assertTrue(h.player.isOutputStopped());
    }

    @Test public void removingMarkerForwardCandidateMovesOnlyToItsSuccessor() {
        Harness h=new Harness(); h.addThree(); h.player.selectArticle("dyn","d3"); h.remove("dyn","d3");
        h.append("dyn",NoteBookTest.article("d4")); h.append("dyn",NoteBookTest.article("d5"));
        h.remove("dyn","d4"); assertNull(h.player.snapshot().article); h.player.play();
        assertEquals("d5",h.player.snapshot().article.id);
    }

    @Test public void malformedMarkerFailsClosedRatherThanThrowingOrStartingOldText() {
        Harness h=new Harness(); h.addThree(); Map<String,String> cursors=new LinkedHashMap<>();
        cursors.put("dyn","@after:invalid");
        h.store.state=new PlayerController.SavedState("dyn",cursors,Collections.emptyMap());
        PlayerController restored=h.restart(); assertNull(restored.snapshot().article);
        assertNotNull(h.book.reconciliationMessage()); restored.play(); assertFalse(restored.snapshot().playing);
    }

    private static PlayerController.SavedProgress progress(PlayerController player,String station,String article) {
        return player.savedState().progress.get(PlayerController.progressKey(station,article,"v1"));
    }
    private static void assertProgress(PlayerController.SavedProgress expected,PlayerController.SavedProgress actual) {
        assertNotNull(actual); assertEquals(expected.stationId,actual.stationId); assertEquals(expected.articleId,actual.articleId);
        assertEquals(expected.version,actual.version); assertEquals(expected.sentenceIndex,actual.sentenceIndex);
        assertEquals(expected.passEnded,actual.passEnded); assertEquals(expected.completedSentences,actual.completedSentences);
        assertEquals(expected.read,actual.read); assertEquals(expected.started,actual.started);
    }
    private static final class Harness {
        final NoteBookTest.MemoryStorage storage=new NoteBookTest.MemoryStorage();
        NoteBook book=new NoteBook(storage); final MemoryState store=new MemoryState();
        final FakeSpeech speech=new FakeSpeech(); final FakeScheduler scheduler=new FakeScheduler();
        FakeSpeech freshSpeech; final PlayerController player=create(speech);
        List<Station> catalog() {
            List<Station> stations=new ArrayList<>(Arrays.asList(
                    new Station("a","А",Arrays.asList(NoteBookTest.article("a1"),NoteBookTest.article("a2"))),
                    new Station("b","Б",Arrays.asList(NoteBookTest.article("b1"),NoteBookTest.article("b2"))),
                    new Station("c","В",Collections.emptyList())));
            for(Station registered:book.stationRegistry()) {
                List<Article> articles=new ArrayList<>();
                for(NoteBook.Material material:book.materials()) if(material.stationId.equals(registered.id)) articles.add(material.asArticle());
                stations.add(new Station(registered.id,registered.name,articles));
            }
            return stations;
        }
        PlayerController create(FakeSpeech output) {
            PlayerController.StateStore reconciled=new PlayerController.StateStore() {
                @Override public PlayerController.SavedState load() { return book.reconcileState(store.load()); }
                @Override public void save(PlayerController.SavedState state) { store.save(state); }
            };
            return new PlayerController(catalog(),output,scheduler,reconciled,s -> {},1000);
        }
        NoteBook.Material append(String station,Article article) {
            NoteBook.Material material=book.addSavedMaterial(station,"Станция "+station,article);
            player.appendMaterial(material.stationId,material.stationName,material.asArticle()); return material;
        }
        NoteBook.Material remove(String station,String article) {
            NoteBook.Material removed=book.removeSavedMaterial(station,article);
            if(removed!=null) player.removeMaterial(station,article,removed.boundaryMarker()); return removed;
        }
        void addThree() { append("dyn",NoteBookTest.article("d1")); append("dyn",NoteBookTest.article("d2")); append("dyn",NoteBookTest.article("d3")); }
        void complete() { String request=player.snapshot().currentRequestId; player.onStart(request); player.onDone(request); }
        PlayerController restart() { book=new NoteBook(storage); freshSpeech=new FakeSpeech(); return create(freshSpeech); }
    }
    private static final class FakeSpeech implements PlayerController.SpeechEngine {
        final List<String> requests=new ArrayList<>(); int stops; boolean failStop; Runnable onStop;
        @Override public void speak(String text,String requestId) { requests.add(requestId); }
        @Override public void stop() { stops++; if(failStop) throw new IllegalStateException("Cannot stop"); if(onStop!=null) onStop.run(); }
    }
    private static final class FakeScheduler implements PlayerController.Scheduler {
        final List<Runnable> tasks=new ArrayList<>();
        @Override public PlayerController.Cancel schedule(long delay,Runnable action) { tasks.add(action); return () -> {}; }
        Runnable latest() { return tasks.get(tasks.size()-1); }
    }
    private static final class MemoryState implements PlayerController.StateStore {
        PlayerController.SavedState state;
        @Override public PlayerController.SavedState load() { return state; }
        @Override public void save(PlayerController.SavedState value) { state=value; }
    }
}

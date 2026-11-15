package ru.bububu.radio.core;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class C6PlaybackTest {
    static final class Voice implements PlayerController.SpeechEngine {int calls,stops;String id;public void speak(String text,String request){calls++;id=request;}public void stop(){stops++;}}
    static Article a(String id){return Article.fromSentences(id,"1",id,"Первая фраза.","Вторая фраза.");}
    static PlayerController player(Voice voice,PlayerController.SavedState saved){return new PlayerController(List.of(new Station("R","РГ",List.of(a("r1"),a("r2"))),new Station("empty","Пустая",List.of()),new Station("B","Био",List.of(a("b1"),a("b2")))),voice,(ms,task)->()->{},new PlayerController.StateStore(){public PlayerController.SavedState load(){return saved;}public void save(PlayerController.SavedState s){}},s->{},1000);}
    static void route(PlayerController p,AirMix mix){
        List<AirMix.Item> pool=List.of(new AirMix.Item("R","r1","1","rg"),new AirMix.Item("R","r2","1","rg"),new AirMix.Item("B","b1","1","bio"),new AirMix.Item("B","b2","1","bio"));List<String> order=List.of("R","empty","B");
        p.route(new PlayerController.Route(){public void explicitPlay(){mix.explicitPlay();}public void started(String station,Article article){mix.started(new AirMix.Item(station,article.id,article.version,station.equals("R")?"rg":"bio"));}public Boolean canNext(String station,Article article){return mix.hasNextInAir(pool,order,station,article,p.savedState());}public PlayerController.RouteChoice next(String station,Article article){AirMix.Item next=mix.nextInAir(pool,order,station,article,p.savedState());return next==null?PlayerController.RouteChoice.end():PlayerController.RouteChoice.item(next.station,next.article,next.version);}});
    }
    @Test public void explicitNextWrapsToEarlierUnreadAcrossEmptyStationWithoutSound() {
        Voice voice = new Voice();
        PlayerController p = player(voice, null);
        C7AirQueueTest.route(p, new AirMix(new Random(1)));
        for (String next : List.of("R/r2/1", "B/b1/1", "B/b2/1", "R/r1/1")) {
            assertTrue(p.snapshot().canNextArticle);
            p.nextArticle();
            PlayerController.Snapshot state = p.snapshot();
            assertEquals(next, state.station.id + "/" + state.article.id + "/" + state.article.version);
            assertFalse(state.broadcastEnded);
            assertFalse(state.playing);
            assertFalse(state.started);
        }
        assertEquals(0, voice.calls);
    }
    @Test public void automaticCompletionCrossesBlockAndHonoursGlobalEnd(){Voice v=new Voice();PlayerController p=player(v,null);route(p,new AirMix(new Random(1)));p.play();for(int n=0;n<8;n++){String id=v.id;p.onStart(id);p.onDone(id);}assertTrue(p.snapshot().broadcastEnded);assertFalse(p.snapshot().playing);assertEquals(8,v.calls);p.onDone(v.id);assertEquals(8,v.calls);}
    @Test public void explicitShufflePlaysEveryEligibleVersionOnceAndStops() {
        Voice voice = new Voice();
        PlayerController p = player(voice, null);
        AirMix mix = new AirMix(new Random(4));
        C7AirQueueTest.route(p, mix);
        AirMix.Item first = mix.reshuffle(C7AirQueueTest.pool(), p.savedState());
        p.selectArticle(first.station, first.article);
        assertEquals(0, voice.calls);
        p.play();
        Set<String> heard = new HashSet<>();
        for (int article = 0; article < 4; article++) {
            PlayerController.Snapshot state = p.snapshot();
            assertTrue(state.playing);
            assertTrue(heard.add(state.station.id + "/" + state.article.id + "/" + state.article.version));
            for (int sentence = 0; sentence < 2; sentence++) {
                String id = voice.id;
                p.onStart(id);
                p.onDone(id);
            }
        }
        assertEquals(Set.of("R/r1/1", "R/r2/1", "B/b1/1", "B/b2/1"), heard);
        assertEquals(8, voice.calls);
        assertTrue(p.snapshot().broadcastEnded);
        assertFalse(p.snapshot().playing);
    }
    @Test public void newStationChoosesFirstUnfinishedButStartedCursorAndReturnKeepPlace(){
        Map<String,PlayerController.SavedProgress> progress=new HashMap<>();progress.put(PlayerController.progressKey("B","b1","1"),new PlayerController.SavedProgress("B","b1","1",1,true,List.of(0,1),false,true));
        Voice v=new Voice();PlayerController p=player(v,new PlayerController.SavedState("R",Map.of("B","b1"),progress));p.selectStation("B");assertEquals("b2",p.snapshot().article.id);assertEquals(0,p.snapshot().sentenceIndex);p.play();p.onStart(v.id);p.onDone(v.id);p.onStart(v.id);p.pause();p.selectStation("R");p.selectStation("B");assertEquals("b2",p.snapshot().article.id);assertEquals(1,p.snapshot().sentenceIndex);int calls=v.calls;p.selectStation("B");assertEquals(1,p.snapshot().sentenceIndex);assertEquals(calls,v.calls);
    }
    @Test public void unstartedRestoredLaterCursorDoesNotSkipFirstUnfinishedOnFirstVisit(){Voice v=new Voice();PlayerController p=player(v,new PlayerController.SavedState("R",Map.of("B","b2"),Map.of()));p.selectStation("B");assertEquals("b1",p.snapshot().article.id);assertEquals(0,p.snapshot().sentenceIndex);}
    @Test public void nextDuringPlaybackInvalidatesOldCallbacksAndPauseDoesNotRestart(){Voice v=new Voice();PlayerController p=player(v,null);route(p,new AirMix(new Random(1)));p.play();String old=v.id;p.onStart(old);p.nextArticle();assertEquals("r2",p.snapshot().article.id);int calls=v.calls;p.onDone(old);assertEquals(calls,v.calls);p.pause();p.onStart(v.id);p.onDone(v.id);assertEquals(calls,v.calls);assertFalse(p.snapshot().playing);}
    @Test public void primaryColdCursorChoosesFirstUnfinishedAndKeepsStartedSavedPosition(){
        Voice voice=new Voice();PlayerController fresh=player(voice,new PlayerController.SavedState("R",Map.of("R","r2"),Map.of()));fresh.preferUnfinishedCurrent(false);assertEquals("r1",fresh.snapshot().article.id);assertEquals(0,voice.calls);
        PlayerController.SavedProgress prior=new PlayerController.SavedProgress("R","r2","1",1,false,List.of(0),false,true);PlayerController resumed=player(new Voice(),new PlayerController.SavedState("R",Map.of("R","r2"),Map.of(PlayerController.progressKey("R","r2","1"),prior)));resumed.preferUnfinishedCurrent(false);assertEquals("r2",resumed.snapshot().article.id);assertEquals(1,resumed.snapshot().sentenceIndex);
    }
    @Test public void completedGlobalAirDoesNotReplayOnExplicitPlay(){
        Map<String,PlayerController.SavedProgress> done=new HashMap<>();for(String station:List.of("R","B"))for(String id:station.equals("R")?List.of("r1","r2"):List.of("b1","b2"))done.put(PlayerController.progressKey(station,id,"1"),new PlayerController.SavedProgress(station,id,"1",1,true,List.of(0,1),false,true));
        Voice voice=new Voice();PlayerController p=player(voice,new PlayerController.SavedState("B",Map.of("B","b2"),done));route(p,new AirMix(new Random(2)));p.preferUnfinishedCurrent(false);p.play();assertFalse(p.snapshot().playing);assertTrue(p.snapshot().broadcastEnded);assertEquals(0,voice.calls);
    }
    @Test public void staleRoutedVersionIsRefusedWithoutReplacingCurrent(){Voice voice=new Voice();PlayerController p=player(voice,null);p.route((station,article)->PlayerController.RouteChoice.item("B","b1","foreign"));p.nextArticle();assertEquals("R",p.snapshot().station.id);assertFalse(p.snapshot().playing);assertEquals(0,voice.calls);}

    @Test public void explicitArticleChoiceIsNotReplacedByColdStartPreference(){Voice voice=new Voice();PlayerController p=player(voice,null);p.selectArticle("R","r2");p.preferUnfinishedCurrent(true);assertEquals("r2",p.snapshot().article.id);p.play();assertEquals("r2",p.snapshot().article.id);assertEquals(1,voice.calls);}

    @Test public void explicitUnstartedArticleSurvivesStationAwayAndReturnWithoutSpeech(){
        Voice voice=new Voice();PlayerController p=player(voice,null);
        p.selectArticle("R","r2");p.selectStation("B");p.selectStation("R");
        assertEquals("r2",p.snapshot().article.id);assertEquals(0,p.snapshot().sentenceIndex);assertFalse(p.snapshot().started);assertTrue(p.snapshot().completedSentences.isEmpty());assertEquals(0,voice.calls);
    }

}

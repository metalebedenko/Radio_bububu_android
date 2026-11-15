package ru.bububu.radio.core;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class C9AirQueueTest {
    @Test public void previousAtFirstArticleReversesAcrossEmptyStationAndKeepsSavedPause(){
        PlayerController.SavedProgress progress=new PlayerController.SavedProgress("R","r2","1",1,false,List.of(0),false,true);
        C6PlaybackTest.Voice voice=new C6PlaybackTest.Voice();PlayerController p=C6PlaybackTest.player(voice,new PlayerController.SavedState("B",Map.of("B","b1"),Map.of(PlayerController.progressKey("R","r2","1"),progress)));
        C7AirQueueTest.route(p,new AirMix(new Random(1)));assertEquals("b1",p.snapshot().article.id);assertTrue(p.snapshot().canPreviousArticle);p.previousArticle();assertEquals("R",p.snapshot().station.id);assertEquals("r2",p.snapshot().article.id);assertEquals(1,p.snapshot().sentenceIndex);assertEquals(List.of(0),p.snapshot().completedSentences);assertFalse(p.snapshot().playing);assertEquals(0,voice.calls);p.nextArticle();assertEquals("b1",p.snapshot().article.id);
    }
    @Test public void reverseWrapAndRemovedExactVersionsNeverChooseEmptyOrStaleItems(){
        AirMix mix=new AirMix(new Random(1));List<AirMix.Item> pool=new ArrayList<>(C7AirQueueTest.pool());
        assertEquals("b2",mix.previousInAir(pool,List.of("R","empty","B"),"R",C6PlaybackTest.a("r1")).article);
        pool.removeIf(i->i.article.equals("r2"));assertEquals("r1",mix.previousInAir(pool,List.of("R","empty","B"),"B",C6PlaybackTest.a("b1")).article);
        assertNull(mix.previousInAir(pool,List.of("R","empty","B"),"B",Article.fromSentences("b1","stale","stale","Факт.")));
        AirMix.Item only=pool.get(0);assertNull(mix.previousInAir(List.of(only),List.of("R"),"R",C6PlaybackTest.a("r1")));
    }
    @Test public void modeOffPreservesCurrentPlayerForPlayingPausedPreparingAndError(){
        for(String state:List.of("playing","paused","preparing","error")){
            C6PlaybackTest.Voice voice=new C6PlaybackTest.Voice();PlayerController p=C6PlaybackTest.player(voice,null);AirMix mix=new AirMix(new Random(2));C7AirQueueTest.route(p,mix);AirMix.Item first=mix.reshuffle(C7AirQueueTest.pool(),p.savedState());p.selectArticle(first.station,first.article);p.play();if(!state.equals("preparing"))p.onStart(voice.id);if(state.equals("paused"))p.pause();if(state.equals("error"))p.onError(voice.id,"Сохранённая ошибка");
            PlayerController.Snapshot before=p.snapshot();int calls=voice.calls,stops=voice.stops;
            HoldGesture gesture=new HoldGesture(()->fail("Long release shuffled"),()->mix.enabled(false,null));gesture.down(0);gesture.tick(3000);gesture.up(3100);
            PlayerController.Snapshot after=p.snapshot();assertFalse(mix.enabled());assertEquals(before.station.id,after.station.id);assertEquals(before.article.id,after.article.id);assertEquals(before.article.version,after.article.version);assertEquals(before.currentRequestId,after.currentRequestId);assertEquals(before.sentenceIndex,after.sentenceIndex);assertEquals(before.completedSentences,after.completedSentences);assertEquals(before.playing,after.playing);assertEquals(before.error,after.error);assertEquals(calls,voice.calls);assertEquals(stops,voice.stops);
        }
    }
}

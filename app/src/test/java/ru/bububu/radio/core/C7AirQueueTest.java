package ru.bububu.radio.core;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class C7AirQueueTest {
    static List<AirMix.Item> pool(){return List.of(new AirMix.Item("R","r1","1","rg",2),new AirMix.Item("R","r2","1","rg",2),new AirMix.Item("B","b1","1","bio",2),new AirMix.Item("B","b2","1","bio",2));}
    static void route(PlayerController p,AirMix mix){p.route(new PlayerController.Route(){
        public void explicitPlay(){mix.explicitPlay();}
        public void selected(String s,Article a){mix.selected(new AirMix.Item(s,a.id,a.version,s,2));}
        public void started(String s,Article a){mix.started(new AirMix.Item(s,a.id,a.version,s,2));}
        public Boolean canPrevious(String s,Article a){return mix.hasPreviousInAir(pool(),List.of("R","empty","B"),s,a);}
        public PlayerController.RouteChoice previous(String s,Article a){return choice(mix.previousInAir(pool(),List.of("R","empty","B"),s,a));}
        public Boolean canNext(String s,Article a){return mix.hasNextInAir(pool(),List.of("R","empty","B"),s,a,p.savedState());}
        public PlayerController.RouteChoice next(String s,Article a){return next(s,a,false);}
        public PlayerController.RouteChoice next(String s,Article a,boolean explicit){return choice(mix.nextInAir(pool(),List.of("R","empty","B"),s,a,p.savedState(),explicit));}
        PlayerController.RouteChoice choice(AirMix.Item i){return i==null?PlayerController.RouteChoice.end():PlayerController.RouteChoice.item(i.station,i.article,i.version);}
    });}
    @Test public void lastStationContinuesIntoEarlierUnheardAndEndsAfterAllComplete(){
        C6PlaybackTest.Voice v=new C6PlaybackTest.Voice();PlayerController p=C6PlaybackTest.player(v,null);AirMix mix=new AirMix(new Random(3));route(p,mix);p.selectArticle("B","b2");p.play();Set<String> heard=new HashSet<>();int guard=0;
        while(p.snapshot().playing){assertTrue(++guard<=8);heard.add(p.snapshot().article.id);String id=v.id;p.onStart(id);p.onDone(id);}
        assertEquals(Set.of("r1","r2","b1","b2"),heard);assertEquals(8,v.calls);assertTrue(p.snapshot().broadcastEnded);assertFalse(p.snapshot().canNextArticle);
    }
    @Test public void explicitNextAfterLastSourceFindsEarlierEligibleAndPreservesPause(){
        C6PlaybackTest.Voice v=new C6PlaybackTest.Voice();PlayerController p=C6PlaybackTest.player(v,null);route(p,new AirMix(new Random(1)));p.selectArticle("B","b2");assertTrue(p.snapshot().canNextArticle);p.nextArticle();assertEquals("R",p.snapshot().station.id);assertEquals("r1",p.snapshot().article.id);assertFalse(p.snapshot().playing);assertEquals(0,v.calls);
    }
    @Test public void mixedBackForwardUsesOneOrderAndNeverConsumesRandomnessInAvailability(){
        C6PlaybackTest.Voice v=new C6PlaybackTest.Voice();PlayerController p=C6PlaybackTest.player(v,null);AirMix mix=new AirMix(new Random(12));route(p,mix);AirMix.Item first=mix.reshuffle(pool(),p.savedState());p.selectArticle(first.station,first.article);String a=p.snapshot().article.id;p.nextArticle();String b=p.snapshot().article.id;p.nextArticle();String c=p.snapshot().article.id;assertEquals(3,Set.of(a,b,c).size());
        assertTrue(p.snapshot().canPreviousArticle);p.previousArticle();assertEquals(b,p.snapshot().article.id);for(int i=0;i<8;i++)assertTrue(p.snapshot().canNextArticle);p.nextArticle();assertEquals(c,p.snapshot().article.id);p.previousArticle();p.previousArticle();assertEquals(a,p.snapshot().article.id);assertEquals(0,v.calls);
    }
    @Test public void explicitReshuffleExcludesFullyHeardAndReadButRetainsPartialCoverage(){
        PlayerController.SavedProgress done=new PlayerController.SavedProgress("R","r1","1",1,true,List.of(0,1),false,true),partial=new PlayerController.SavedProgress("R","r2","1",1,true,List.of(1),false,true),read=new PlayerController.SavedProgress("B","b1","1",0,false,List.of(),true,false);
        PlayerController.SavedState state=new PlayerController.SavedState("R",Map.of(),Map.of(PlayerController.progressKey("R","r1","1"),done,PlayerController.progressKey("R","r2","1"),partial,PlayerController.progressKey("B","b1","1"),read));
        AirMix mix=new AirMix(new Random(5));for(int attempt=0;attempt<3;attempt++){AirMix.Item first=mix.reshuffle(pool(),state);Set<String> chosen=new HashSet<>();chosen.add(first.article);mix.selected(first);AirMix.Item next=mix.nextInAir(pool(),List.of("R","B"),first.station,C6PlaybackTest.a(first.article),state,true);assertNotNull(next);chosen.add(next.article);assertEquals(Set.of("r2","b2"),chosen);mix.selected(next);assertFalse(mix.hasNextInAir(pool(),List.of("R","B"),next.station,C6PlaybackTest.a(next.article),state));}
    }
    @Test public void completedHistoryIsAvailableToExplicitForwardButNotAutomaticReplay(){
        C6PlaybackTest.Voice v=new C6PlaybackTest.Voice();PlayerController p=C6PlaybackTest.player(v,null);AirMix mix=new AirMix(new Random(8));route(p,mix);AirMix.Item first=mix.reshuffle(pool(),p.savedState());p.selectArticle(first.station,first.article);p.play();Set<String> heard=new HashSet<>();int guard=0;while(p.snapshot().playing){assertTrue(++guard<=8);heard.add(p.snapshot().article.id);String id=v.id;p.onStart(id);p.onDone(id);}assertEquals(4,heard.size());
        String last=p.snapshot().article.id;p.previousArticle();String prior=p.snapshot().article.id;assertNotEquals(last,prior);assertTrue(p.snapshot().canNextArticle);p.nextArticle();assertEquals(last,p.snapshot().article.id);int calls=v.calls;p.onDone(v.id);assertEquals(calls,v.calls);assertFalse(p.snapshot().playing);
    }
    @Test public void pausedMixedNavigationAndCancelledOldCallbacksNeverStartAudio(){
        C6PlaybackTest.Voice v=new C6PlaybackTest.Voice();PlayerController p=C6PlaybackTest.player(v,null);AirMix mix=new AirMix(new Random(2));route(p,mix);AirMix.Item first=mix.reshuffle(pool(),p.savedState());p.selectArticle(first.station,first.article);p.play();String old=v.id;p.onStart(old);p.pause();p.nextArticle();p.previousArticle();int calls=v.calls;p.onDone(old);p.onStart(old);assertEquals(calls,v.calls);assertFalse(p.snapshot().playing);mix.enabled(false,null);assertFalse(mix.enabled());
    }
    @Test public void historyDoesNotBypassRemovedOrReplacedExactVersions(){
        C6PlaybackTest.Voice v=new C6PlaybackTest.Voice();PlayerController p=C6PlaybackTest.player(v,null);AirMix mix=new AirMix(new Random(21));AirMix.Item first=mix.reshuffle(pool(),p.savedState());mix.selected(first);AirMix.Item second=mix.nextInAir(pool(),List.of("R","B"),first.station,C6PlaybackTest.a(first.article),p.savedState(),true);mix.selected(second);List<AirMix.Item> remaining=new ArrayList<>(pool());remaining.removeIf(item->item.key.equals(first.key));assertFalse(mix.hasPreviousInAir(remaining));assertNull(mix.previousInAir(remaining));remaining.add(new AirMix.Item(first.station,first.article,"new-version",first.source,2));assertFalse(mix.hasPreviousInAir(remaining));assertEquals(0,v.calls);
    }

    @Test public void previousAtMixStartIsNoopAndDoesNotStopCurrentRequest(){C6PlaybackTest.Voice v=new C6PlaybackTest.Voice();PlayerController p=C6PlaybackTest.player(v,null);AirMix mix=new AirMix(new Random(11));route(p,mix);AirMix.Item first=mix.reshuffle(pool(),p.savedState());p.selectArticle(first.station,first.article);p.play();p.onStart(v.id);String current=p.snapshot().currentRequestId;int calls=v.calls,stops=v.stops;assertFalse(p.snapshot().canPreviousArticle);p.previousArticle();assertEquals(current,p.snapshot().currentRequestId);assertTrue(p.snapshot().playing);assertEquals(calls,v.calls);assertEquals(stops,v.stops);}

    @Test public void coldAtCompletedLastSourceDoesNotClaimGlobalEndWhenEarlierUnreadExist(){Map<String,PlayerController.SavedProgress> history=new HashMap<>();for(String id:List.of("b1","b2"))history.put(PlayerController.progressKey("B",id,"1"),new PlayerController.SavedProgress("B",id,"1",1,true,List.of(0,1),false,true));C6PlaybackTest.Voice voice=new C6PlaybackTest.Voice();PlayerController p=C6PlaybackTest.player(voice,new PlayerController.SavedState("B",Map.of("B","b2"),history));route(p,new AirMix(new Random(3)));assertTrue(p.snapshot().canNextArticle);assertFalse(p.snapshot().broadcastEnded);assertNotEquals("Эфир завершён",p.snapshot().status);assertEquals(0,voice.calls);}

}

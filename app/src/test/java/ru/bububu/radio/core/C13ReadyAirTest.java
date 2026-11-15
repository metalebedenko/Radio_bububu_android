package ru.bububu.radio.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import ru.bububu.radio.sources.*;

public class C13ReadyAirTest {
    static final class Voice implements PlayerController.SpeechEngine {int calls;public void speak(String text,String id){calls++;}public void stop(){}}
    static final class Fixture {
        final NoteBook book=FirstAirProductTest.book(SourceProfiles45.six());final Voice voice=new Voice();final AirMix mix=new AirMix(new Random(1));final PlayerController p;String retired;
        Fixture(){p=new PlayerController(List.of(new Station(NoteBook.MY_TEXTS,"Мои тексты",List.of())),voice,(ms,r)->()->{},new PlayerController.StateStore(){public PlayerController.SavedState load(){return null;}public void save(PlayerController.SavedState s){}},s->{},1000,new PlaybackMembership(){
            public Binding capture(String station,Article a){NoteBook.Material m=book.material(station,a.id);return m==null?null:new Binding(m.sequence,m.sourceVersionKey,"book");}
            public Admission check(String station,Article a,Binding b){return b==null?Admission.NOT_MEMBER:Admission.valueOf(book.queueMembership(station,a,b.sequence,b.sourceVersionKey).name());}
            public boolean stationRetired(String station){return station.equals(retired)||book.retiredStation(station);}
        });}
        SourceVersionRecord add(String profile,String key,PlayerController.SavedProgress seed)throws Exception{
            SourceProfiles45.Profile config=SourceProfiles45.byId(profile);SourceRecord source=config.seed();SourceItem item=SourceItem.create(source.id,key,null,config.endpoint,"Обзор",-1,-1,1);SourceVersionRecord version=C9AirScopeTest.version(config,item,"Первый факт. Второй факт.");book.mergeSourceItems(source.id,List.of(item.withLatest(version.recordKey)),List.of(version),null,null,1,null);NoteBook.Material m=book.selectSourceVersion(version,false);p.appendMaterial(m.stationId,m.stationName,m.asArticle(),seed);return version;
        }
        List<String> order(){List<String> ids=new ArrayList<>();for(Station s:p.stations())if(OrdinaryRadio.inAir(s,book::retiredStation))ids.add(s.id);return ids;}
        AirMix.Item ready(){return QuickStart.existingAir(mix,OrdinaryRadio.sourceAir(book,mix.enabled(),null,true),order(),p.snapshot(),p.savedState());}
        PlayerController reopen(){PlayerController.SavedState saved=p.savedState();return new PlayerController(OrdinaryRadio.catalog(book),voice,(ms,r)->()->{},new PlayerController.StateStore(){public PlayerController.SavedState load(){return saved;}public void save(PlayerController.SavedState value){}},s->{},1000,new PlaybackMembership(){
            public Binding capture(String station,Article a){NoteBook.Material m=book.material(station,a.id);return m==null?null:new Binding(m.sequence,m.sourceVersionKey,"book");}
            public Admission check(String station,Article a,Binding b){return b==null?Admission.NOT_MEMBER:Admission.valueOf(book.queueMembership(station,a,b.sequence,b.sourceVersionKey).name());}
            public boolean stationRetired(String station){return station.equals(retired)||book.retiredStation(station);}
        });}
    }
    @Test public void quietAddElsewhereIsRealAirAndExplicitListenUsesCachedMaterialBeforeDownload()throws Exception{
        Fixture f=new Fixture();SourceVersionRecord v=f.add("biomolecula-articles","one",null);assertNull(f.p.snapshot().article);assertEquals(0,f.voice.calls);assertFalse(f.p.snapshot().playing);assertTrue(f.p.stations().stream().anyMatch(s->OrdinaryRadio.inAir(s,f.book::retiredStation)));assertEquals(1,f.book.materials().size());AirMix.Item ready=f.ready();assertNotNull(ready);assertEquals(v.articleId,ready.article);assertEquals(v.versionKey,ready.version);
        f.p.selectArticle(ready.station,ready.article);QuickStart q=new QuickStart();QuickStart.Candidate current=new QuickStart.Candidate(ready.station,ready.article,ready.version,ready.source,v.channelReference(),v.kind(),true,true);QuickStart.Ticket ticket=q.begin("explicit","selection","voice",List.of(),null,current);assertFalse("Cached Listen need not wait for another download",QuickStart.waitForBatch(true,true,current));q.ready(ticket,"voice");assertNotNull(q.take(ticket,"selection","voice",x->f.book.material(x.station,x.article)!=null));assertTrue(q.playerEntered(q.awaitingEpoch()));f.p.play();assertEquals(1,f.voice.calls);assertEquals(v.articleId,f.p.snapshot().article.id);assertEquals(v.versionKey,f.p.snapshot().article.version);
    }
    @Test public void renderReadinessDoesNotDependOnSelectingSourcesForAnotherDownload()throws Exception{
        Fixture f=new Fixture();SourceVersionRecord v=f.add("biomolecula-articles","ready-no-fetch",null);PlayerController restored=f.reopen();PlayerController.Snapshot state=restored.snapshot();
        assertNull(state.article);assertFalse(state.selectionRequired);assertTrue(f.book.sources().stream().anyMatch(s->s.enabled));
        boolean selectedStation=OrdinaryRadio.inAir(state.station,f.book::retiredStation),anyAir=!f.order().isEmpty(),canDownload=false;
        assertFalse("C13 render: station="+state.station.id+" selected="+selectedStation+" ended="+state.broadcastEnded,canDownload||!state.selectionRequired&&(state.article!=null||selectedStation||state.broadcastEnded&&anyAir));
        List<AirMix.Item> pool=OrdinaryRadio.sourceAir(f.book,false,null,true);
        assertTrue(QuickStart.hasExistingAir(f.mix,pool,f.order(),state,restored.savedState()));
        assertEquals(v.articleId,QuickStart.existingAir(f.mix,pool,f.order(),state,restored.savedState()).article);assertNull(restored.snapshot().article);assertEquals(0,f.voice.calls);assertFalse(restored.snapshot().playing);
    }
    @Test public void renderReadinessPeekDoesNotShuffleOrConsumeMixAndStillRejectsCurrentSelection()throws Exception{
        Fixture f=new Fixture();f.add("biomolecula-articles","first",null);f.add("elementy-science-explainers","second",null);
        class RandomProbe extends Random {int draws;public int nextInt(int bound){draws++;return super.nextInt(bound);}}
        RandomProbe random=new RandomProbe();AirMix mix=new AirMix(random);mix.enabled(true,null);List<AirMix.Item> pool=OrdinaryRadio.sourceAir(f.book,true,null,true);
        for(int i=0;i<3;i++)assertTrue(QuickStart.hasExistingAir(mix,pool,f.order(),f.p.snapshot(),f.p.savedState()));
        assertEquals(0,random.draws);assertEquals(0,mix.visited());assertNull(f.p.snapshot().article);assertEquals(0,f.voice.calls);
        AirMix.Item chosen=QuickStart.existingAir(mix,pool,f.order(),f.p.snapshot(),f.p.savedState());assertNotNull(chosen);assertTrue(random.draws>0);
        f.p.selectArticle(chosen.station,chosen.article);assertFalse(QuickStart.hasExistingAir(mix,pool,f.order(),f.p.snapshot(),f.p.savedState()));
    }
    @Test public void renderReadinessKeepsHeardDisabledAndNoEligibleDataClosed()throws Exception{
        Fixture f=new Fixture();assertFalse(QuickStart.hasExistingAir(f.mix,List.of(),f.order(),f.p.snapshot(),f.p.savedState()));
        SourceVersionRecord v=f.add("biomolecula-articles","heard",null);SourceRecord source=f.book.source(v.sourceId);
        assertTrue(f.book.materials().stream().anyMatch(m->m.sourceVersionKey!=null)); // Existing hasRealAir is true: ready-model first-setup gate stays closed.
        f.p.setRead(source.stationId,v.articleId,v.versionKey,true);List<AirMix.Item> pool=OrdinaryRadio.sourceAir(f.book,false,null,true);
        assertEquals(1,pool.size());assertFalse(f.p.snapshot().selectionRequired);assertFalse(QuickStart.hasExistingAir(f.mix,pool,f.order(),f.p.snapshot(),f.p.savedState()));
        f.mix.enabled(true,null);assertFalse(QuickStart.hasExistingAir(f.mix,pool,f.order(),f.p.snapshot(),f.p.savedState()));
        f.book.setSourceEnabled(source.id,false);assertTrue(OrdinaryRadio.sourceAir(f.book,true,null,true).isEmpty());
        assertFalse(QuickStart.hasExistingAir(f.mix,OrdinaryRadio.sourceAir(f.book,true,null,true),f.order(),f.p.snapshot(),f.p.savedState()));
        assertNull(f.p.snapshot().article);assertEquals(0,f.voice.calls);
    }
    @Test public void acceptedOrderSkipsCompletedAndPreservesRememberedSentenceOnSelection()throws Exception{
        Fixture f=new Fixture();SourceVersionRecord a=f.add("biomolecula-articles","done",null);f.p.selectArticle(f.book.source(a.sourceId).stationId,a.articleId);f.p.setRead(f.book.source(a.sourceId).stationId,a.articleId,a.versionKey,true);f.p.selectStation(NoteBook.MY_TEXTS);SourceProfiles45.Profile config=SourceProfiles45.byId("elementy-science-explainers");SourceRecord second=config.seed();SourceItem item=SourceItem.create(second.id,"partial",null,config.endpoint,"Обзор",-1,-1,1);SourceVersionRecord v=C9AirScopeTest.version(config,item,"Первый факт. Второй факт.");f.book.mergeSourceItems(second.id,List.of(item.withLatest(v.recordKey)),List.of(v),null,null,1,null);NoteBook.Material m=f.book.selectSourceVersion(v,false);f.p.appendMaterial(m.stationId,m.stationName,m.asArticle(),new PlayerController.SavedProgress(m.stationId,m.articleId,m.version,1,false,List.of(0),false,true));AirMix.Item ready=f.ready();assertEquals(v.articleId,ready.article);f.p.selectArticle(ready.station,ready.article);assertEquals(1,f.p.snapshot().sentenceIndex);assertEquals(List.of(0),f.p.snapshot().completedSentences);assertEquals(0,f.voice.calls);
    }
    @Test public void currentResumeAndRetiredSelectionAreNotOverridden()throws Exception{
        Fixture f=new Fixture();SourceVersionRecord v=f.add("biomolecula-articles","one",null);f.p.selectArticle(f.book.source(v.sourceId).stationId,v.articleId);assertNull(f.ready());assertEquals(v.articleId,f.p.snapshot().article.id);f.retired=f.p.snapshot().station.id;assertTrue(f.p.snapshot().selectionRequired);assertNull(f.ready());assertEquals(0,f.voice.calls);
    }
    @Test public void explicitReadyDoesNotAdmitDownloadedOnlyDataAndDoesNotBroadenMix()throws Exception{
        Fixture f=new Fixture();SourceRecord source=SourceRecord.create(HtmlPageReader.TYPE,"https://example.org/page","Страница","https://example.org/page",HtmlPageReader.ADAPTER,false);String html="<main><h1>Страница</h1><p>Неподтверждённый текст.</p></main>";FeedDocument feed=new HtmlPageReader().read(new SourceTransport.Response(html.getBytes(java.nio.charset.StandardCharsets.UTF_8),source.url,200,"text/html","UTF-8",null,null),new RequestScope(1,0));FeedItem entry=feed.items.get(0);SourceItem item=SourceItem.create(source.id,"page",entry.providerId,entry.url,entry.title,-1,-1,1);SourceVersionRecord v=SourceVersionRecord.source45(source,item,entry.inline,entry.inline.publication,SourcePublication45.Context.UNKNOWN_MANUAL,1);f.book.commitSource(source,List.of(item.withLatest(v.recordKey)),List.of(v));assertTrue(OrdinaryRadio.sourceAir(f.book,false,null,true).isEmpty());NoteBook.Material m=f.book.selectSourceVersion(v,true);f.p.appendMaterial(m.stationId,m.stationName,m.asArticle(),null);assertNotNull(f.ready());assertTrue(OrdinaryRadio.sourceAir(f.book,true,null,true).isEmpty());f.book.updateSource(source.withState(false,null,null,1,null));assertNull(f.ready());assertEquals(0,f.voice.calls);
    }
}

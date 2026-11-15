package ru.bububu.radio.sources;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.*;
import ru.bububu.radio.core.*;

/** Production seed/Book/Coordinator dispatch with pinned saved HTTP inputs; no network or NN. */
public class FirstAirSourceCoordinatorTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void realSixRoundtripStartsAtFirstPublicationBeforeAllSourcesFinish()throws Exception{run(false,false);}
    @Test public void firstPublicationSurvivesUntilModelsReadyAfterBatchFinishes()throws Exception{run(true,false);}
    @Test public void sixFailuresAreTerminalAndNeverPlayBuiltinSamples()throws Exception{run(false,true);}
    private void run(boolean delayedVoice,boolean failAll)throws Exception{
        Source45SavedCorpusTest.Corpus corpus=failAll?null:new Source45SavedCorpusTest.Corpus();Map<String,SourceTransport.Response> saved=new HashMap<>();
        if(corpus!=null){for(SourceProfiles45.Profile p:SourceProfiles45.six()){Map<String,Object> w=corpus.windows.get(p.id);byte[] bytes=Source45SavedCorpusTest.pinned(Path.of(Source45SavedCorpusTest.str(w,"saved_path")),Source45SavedCorpusTest.str(w,"saved_sha256"));saved.put(p.endpoint,new SourceTransport.Response(bytes,p.endpoint,200,p.discoveryKind()==SourceTransport.Kind.JSON?"application/json":p.discoveryKind()==SourceTransport.Kind.HTML?"text/html":"text/xml","UTF-8",null,null));}
            for(Map<String,Object> row:corpus.cases.values()){byte[] bytes=Source45SavedCorpusTest.pinned(Path.of(Source45SavedCorpusTest.str(row,"source_html_path")),Source45SavedCorpusTest.str(row,"source_html_sha256"));saved.put(Source45SavedCorpusTest.str(row,"discovery_url"),new SourceTransport.Response(bytes,Source45SavedCorpusTest.str(row,"url"),200,Source45SavedCorpusTest.str(row,"mime").split(";",2)[0].trim(),Source45SavedCorpusTest.str(row,"charset"),null,null));}}
        List<SourceRecord> seeds=new ArrayList<>();Map<String,String> bindings=new TreeMap<>();Set<String> ids=new LinkedHashSet<>();for(SourceProfiles45.Profile p:SourceProfiles45.six()){SourceRecord s=p.seed();seeds.add(s);bindings.put(p.id,s.id);ids.add(s.id);}
        byte[] target=NoteBook.fresh45Target(seeds,new Seed45Marker("01234567-89ab-4cde-8fab-0123456789ab",Seed45Marker.POLICY,SourceProfiles45.definitionSetHash(),bindings));
        final class Storage implements NoteBook.Storage{byte[] bytes=target.clone();public byte[] load(){return bytes.clone();}public void save(byte[] v){bytes=v.clone();}}
        Storage storage=new Storage();NoteBook book=new NoteBook(storage);book.enable45();assertEquals(6,book.sources().size());assertArrayEquals(target,storage.load());for(SourceRecord s:book.sources())assertNotNull(SourceProfiles45.forSource(s));
        BlockingQueue<Runnable> owner=new LinkedBlockingQueue<>();AtomicReference<PlayerController.SavedState> cursor=new AtomicReference<>();List<String> speech=new ArrayList<>();PlayerController player=new PlayerController(OrdinaryRadio.catalog(book),new PlayerController.SpeechEngine(){public void speak(String text,String id){speech.add(id);}public void stop(){}},(ms,action)->()->{},new PlayerController.StateStore(){public PlayerController.SavedState load(){return null;}public void save(PlayerController.SavedState state){cursor.set(state);}},state->{},256);cursor.set(player.savedState());
        SourceClock clock=new SourceClock(){public long wallMillis(){return java.time.Instant.parse("2026-10-26T12:00:00Z").toEpochMilli();}public long monotonicMillis(){return System.nanoTime()/1000000;}};
        SourceCoordinator.Port port=new SourceCoordinator.Port(){
            public void dispatch(Runnable r){owner.add(r);}public void requireMutable(){}public void requireStorage(long bytes){}public void requireQueue(SourceRecord source,Article article){}
            public void appendStation(SourceRecord s){player.appendStation(s.stationId,s.name);}public void append(NoteBook.Material m){player.appendMaterial(m.stationId,m.stationName,m.asArticle(),m.selectionInitialProgress);}
            public PlayerController.SavedProgress persisted(NoteBook.Material m){return cursor.get().progress.get(PlayerController.progressKey(m.stationId,m.articleId,m.version));}
            public void pauseIfSelected(NoteBook.Material m){}public void remove(NoteBook.Material m){}public void projectRead(SourceRecord s,SourceItem i){}public void captureHistory(){book.captureSourceHistory(cursor.get());}public void changed(){}
            public String currentIdentity(){PlayerController.Snapshot s=player.snapshot();return s.article==null?null:PlayerController.progressKey(s.station.id,s.article.id,s.article.version);}
            public PlayerController.SavedState persistedState(){return cursor.get();}public void checkpointMutation(List<NoteBook.Material> removed,boolean clear){}public void projectDeletion(){player.projectCommittedDeletion(OrdinaryRadio.catalog(book),book.reconcileState(cursor.get()));}
        };
        SourceTransport transport=(url,kind,validators,scope)->{if(failAll)throw new SourceFailure("HTTP_503");SourceTransport.Response response=saved.get(url);if(response==null)throw new SourceFailure("NO_SAVED_RESPONSE");assertEquals(SourceProfiles45.byEndpoint(url)==null?SourceTransport.Kind.HTML:SourceProfiles45.byEndpoint(url).discoveryKind(),kind);return response;};
        QuickStart quick=new QuickStart();QuickStart.Ticket ticket=quick.begin("first-run","selection","voice",ids,null,null);if(!delayedVoice)quick.ready(ticket,"voice");AtomicReference<SourceCoordinator.DownloadProgress> progress=new AtomicReference<>();Set<String> publishedSources=new HashSet<>();int[] publications={0},processedAtFirst={-1};
        try(SourceCoordinator coordinator=new SourceCoordinator(book,transport,new SourceBodyCache(temp.newFolder()),port,clock)){
            coordinator.downloadBatch(ids,3,new SourceCoordinator.AirUpdate("first-run",null,event->{
                publishedSources.add(event.sourceId);publications[0]++;SourceVersionRecord v=event.version;SourceRecord s=book.source(v.sourceId);assertNotEquals(ChannelProfile.Kind.UNKNOWN,v.kind());
                assertTrue(ChannelProfile.automatic(v));quick.published(event.runId,new QuickStart.Candidate(s.stationId,v.articleId,v.versionKey,s.id,v.channelReference(),v.kind(),true,false));
                QuickStart.Candidate chosen=quick.take(ticket,"selection","voice",c->{try{new SourceAdmission45(clock).require(book.sourceVersion(v.recordKey),false);return true;}catch(SourceFailure failure){return false;}});
                if(chosen!=null){processedAtFirst[0]=progress.get().processedSources;player.selectArticle(chosen.station,chosen.article);player.play();}
            }),p->{progress.set(p);if(p.finished&&(p.cancelled||p.stopFailure!=null||p.qualifyingFull==0)&&quick.batchMayRevoke("first-run"))quick.terminal("first-run");});
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(progress.get()==null||!progress.get().finished){assertTrue("Bounded saved-response coordinator",System.nanoTime()<until);Runnable action=owner.poll(100,TimeUnit.MILLISECONDS);if(action!=null)action.run();}
            assertEquals(6,progress.get().processedSources);for(Station s:player.stations())assertFalse(OrdinaryRadio.builtin(s.id));
            if(failAll){assertEquals(0,progress.get().saved);assertEquals(6,progress.get().failedSources.size());assertFalse(quick.pending());assertTrue(book.materials().isEmpty());assertTrue(speech.isEmpty());}
            else{assertEquals("All SIX production routes publish: "+progress.get().message,ids,publishedSources);assertTrue(publications[0]>=6);assertTrue(book.materials().size()>=6);if(delayedVoice){assertTrue(speech.isEmpty());assertTrue(quick.pending());quick.ready(ticket,"voice");QuickStart.Candidate chosen=quick.take(ticket,"selection","voice",c->true);assertNotNull(chosen);player.selectArticle(chosen.station,chosen.article);player.play();}else assertTrue("First article precedes completion of six sources",processedAtFirst[0]<6);assertEquals(1,speech.size());assertNull(quick.take(ticket,"selection","voice",c->true));assertTrue(player.snapshot().playing);assertTrue(book.notes().isEmpty());}
        }
    }
}

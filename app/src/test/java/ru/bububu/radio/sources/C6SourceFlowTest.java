package ru.bububu.radio.sources;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import ru.bububu.radio.core.*;

/** Real seed/Book/Coordinator/QuickStart, synthetic publisher-shaped responses; no network/NN. */
public class C6SourceFlowTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    static void pump(BlockingQueue<Runnable> owner,java.util.function.BooleanSupplier done)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!done.getAsBoolean()){assertTrue("Bounded HOST completion",System.nanoTime()<until);Runnable action=owner.poll(100,TimeUnit.MILLISECONDS);if(action!=null)action.run();}}
    final class Fixture implements AutoCloseable {
        final SourceProfiles45.Profile rg=SourceProfiles45.byId("rg-briefs"),bio=SourceProfiles45.byId("biomolecula-articles");
        final NoteBook book;final SourceBodyCache cache;final PlayerController player;final BlockingQueue<Runnable> owner=new LinkedBlockingQueue<>();final AtomicReference<PlayerController.SavedState> cursor=new AtomicReference<>();final List<String> speech=new ArrayList<>(),requests=new ArrayList<>();final SourceCoordinator.Port port;final SourceCoordinator coordinator;int stops;boolean failRg,emptyBioIndex;String rgSuffix="first";
        Fixture()throws Exception{
            List<SourceRecord> seeds=new ArrayList<>();Map<String,String> ids=new TreeMap<>();for(SourceProfiles45.Profile p:SourceProfiles45.six()){seeds.add(p.seed());ids.put(p.id,p.seed().id);}
            seeds.sort(java.util.Comparator.comparingInt(s->s.id.equals(bio.seed().id)?0:1)); // Cold restore RG remains visible although Bio is fetched first.
            byte[] target=NoteBook.fresh45Target(seeds,new Seed45Marker("01234567-89ab-4cde-8fab-0123456789ab",Seed45Marker.POLICY,SourceProfiles45.definitionSetHash(),ids));
            book=new NoteBook(new NoteBook.Storage(){byte[] bytes=target;public byte[] load(){return bytes.clone();}public void save(byte[] value){bytes=value.clone();}});book.enable45();cache=new SourceBodyCache(temp.newFolder());
            player=new PlayerController(OrdinaryRadio.catalog(book),new PlayerController.SpeechEngine(){public void speak(String text,String id){speech.add(id);}public void stop(){stops++;}},(ms,action)->()->{},new PlayerController.StateStore(){public PlayerController.SavedState load(){return new PlayerController.SavedState(rg.seed().stationId,Map.of(),Map.of());}public void save(PlayerController.SavedState state){cursor.set(state);}},state->{},1000);cursor.set(player.savedState());
        port=new SourceCoordinator.Port(){
            public void dispatch(Runnable r){owner.add(r);}public void requireMutable(){}public void requireStorage(long bytes){}public void requireQueue(SourceRecord source,Article article){}
            public void appendStation(SourceRecord s){player.appendStation(s.stationId,s.name);}public void append(NoteBook.Material m){player.appendMaterial(m.stationId,m.stationName,m.asArticle(),m.selectionInitialProgress);}
            public PlayerController.SavedProgress persisted(NoteBook.Material m){return cursor.get().progress.get(PlayerController.progressKey(m.stationId,m.articleId,m.version));}
            public void pauseIfSelected(NoteBook.Material m){}public void remove(NoteBook.Material m){}public void projectRead(SourceRecord s,SourceItem i){}public void captureHistory(){book.captureSourceHistory(cursor.get());}public void changed(){}
            public String currentIdentity(){PlayerController.Snapshot s=player.snapshot();return s.article==null?null:PlayerController.progressKey(s.station.id,s.article.id,s.article.version);}
            public PlayerController.SavedState persistedState(){return cursor.get();}public void checkpointMutation(List<NoteBook.Material> removed,boolean clear){}public void projectDeletion(){player.projectCommittedDeletion(OrdinaryRadio.catalog(book),book.reconcileState(cursor.get()));}
        };
            SourceTransport transport=(url,kind,validators,scope)->{requests.add(url);if(failRg&&url.equals(rg.endpoint))throw new SourceFailure("HTTP_503");
                if(url.equals(bio.endpoint))return html(url,emptyBioIndex?"<main></main>":"<a href='https://biomolecula.ru/articles/c6'>Статья</a>");
                if(url.equals("https://biomolecula.ru/articles/c6"))return html(url,"<p class='article_date'>26 октября 2026</p><div class='article_content'><p>Текст статьи. Продолжение статьи.</p></div>");
                String story="https://rg.ru/2026/10/26/"+rgSuffix+".html";
                if(url.equals(rg.endpoint)){String xml="<rss version='2.0'><channel><title>Новости</title><item><title>Новость</title><link>"+story+"</link><pubDate>26 Oct 2026 12:00:00 +0000</pubDate></item></channel></rss>";return new SourceTransport.Response(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8),url,200,"text/xml","UTF-8",null,null);}
                if(url.equals(story))return html(url,"<script type='application/ld+json'>{\"@type\":\"NewsArticle\",\"url\":\""+url+"\",\"mainEntityOfPage\":{\"@id\":\""+url+"\"},\"headline\":\"Новость\",\"articleBody\":\"Полный текст новости. Продолжение новости.\"}</script>");
                throw new SourceFailure("HTTP_503");
            };
            coordinator=new SourceCoordinator(book,transport,cache,port,BasicIsoOffset45Test.CLOCK);
        }
        SourceTransport.Response html(String url,String body){return Source45ReaderGuardsTest.html(url,Source45ReaderGuardsTest.page(url,body));}
        SourceCoordinator.DownloadProgress download(Collection<String> ids)throws Exception{AtomicReference<SourceCoordinator.DownloadProgress> result=new AtomicReference<>();coordinator.downloadBatch(ids,3,result::set);pump(owner,()->result.get()!=null&&result.get().finished);return result.get();}
        SourceCoordinator.BulkSnapshot snapshot(SourceCoordinator.DownloadProgress download)throws Exception{AtomicReference<SourceCoordinator.Result<SourceCoordinator.BulkSnapshot>> result=new AtomicReference<>();coordinator.prepareDownloadedSnapshot(download,result::set);pump(owner,()->result.get()!=null);assertTrue(result.get().error,result.get().successful());return result.get().value;}
        SourceCoordinator.EnqueueProgress add(SourceCoordinator.BulkSnapshot snapshot)throws Exception{AtomicReference<SourceCoordinator.EnqueueProgress> result=new AtomicReference<>();coordinator.enqueueBulk(snapshot,false,result::set);pump(owner,()->result.get()!=null&&result.get().finished);return result.get();}
        public void close(){coordinator.close();}
    }
    @Test public void coldVisibleRgIsResolvedByProductionPolicyAndBioArrivingFirstCannotReplaceIt()throws Exception{first(false,false);}
    @Test public void lateVoiceStillStartsOnlyVisibleRgOnce()throws Exception{first(false,true);}
    @Test public void mixRetainsFirstEligibleAcrossSelectedSources()throws Exception{first(true,false);}
    void first(boolean mix,boolean lateVoice)throws Exception{
        try(Fixture f=new Fixture()){
            assertEquals(f.rg.seed().stationId,f.player.snapshot().station.id);assertNull(f.player.snapshot().article);
            Set<String> savedChoice=Set.of(f.bio.seed().id);Set<String> selected=new LinkedHashSet<>(savedChoice);
            String explicit=QuickStart.primarySource(mix,f.player.snapshot().station.id,f.book.sources(),selected);if(mix)selected.add(f.rg.seed().id);
            assertEquals(Set.of(f.bio.seed().id),savedChoice);assertTrue(selected.contains(f.rg.seed().id));
            QuickStart quick=new QuickStart();QuickStart.Ticket ticket=quick.begin("c6","selection","voice",selected,explicit,null);if(!lateVoice)quick.ready(ticket,"voice");
            Runnable take=()->{QuickStart.Candidate chosen=quick.take(ticket,"selection","voice",item->true);if(chosen!=null){f.player.selectArticle(chosen.station,chosen.article);f.player.play();}};
            AtomicReference<SourceCoordinator.DownloadProgress> result=new AtomicReference<>();List<String> published=new ArrayList<>();
            f.coordinator.downloadBatch(selected,3,new SourceCoordinator.AirUpdate("c6",null,event->{published.add(event.sourceId);NoteBook.Material m=f.book.material(f.book.source(event.sourceId).stationId,event.version.articleId);quick.published(event.runId,new QuickStart.Candidate(m.stationId,m.articleId,m.version,event.sourceId,event.version.channelReference(),event.version.kind(),true,false));take.run();}),result::set);
            pump(f.owner,()->result.get()!=null&&result.get().finished);assertEquals(result.get().message,2,result.get().qualifyingFull);assertEquals(f.bio.seed().id,published.get(0));assertEquals(2,f.book.materials().size());
            if(lateVoice){assertTrue(f.speech.isEmpty());quick.ready(ticket,"voice");take.run();}
            assertEquals(mix?f.bio.seed().stationId:f.rg.seed().stationId,f.player.snapshot().station.id);assertEquals(1,f.speech.size());take.run();assertEquals(1,f.speech.size());
        }
    }
    @Test public void successfulEmptyWindowAndQuietRepeatPreserveAirOffers() throws Exception {
        try(Fixture f=new Fixture()){
            f.emptyBioIndex=true;
            SourceCoordinator.DownloadProgress empty=f.download(List.of(f.bio.seed().id));
            assertEquals(0,empty.saved);assertEquals(0,empty.failed);
            assertEquals("Новых материалов нет",DownloadPresentation.outcome(empty));
            SourceCoordinator.DownloadProgress first=f.download(List.of(f.rg.seed().id));
            assertEquals(1,first.newVersions);assertTrue(f.book.materials().isEmpty());
            SourceCoordinator.DownloadProgress repeat=f.download(List.of(f.rg.seed().id));
            assertEquals(first.savedRecordKeys,repeat.savedRecordKeys);assertEquals(0,repeat.newVersions);
            assertEquals("Новых материалов нет",DownloadPresentation.outcome(repeat));
            assertEquals(1,f.add(f.snapshot(repeat)).added);assertTrue(f.speech.isEmpty());
            SourceCoordinator.DownloadProgress third=f.download(List.of(f.rg.seed().id));
            assertEquals(0,third.newVersions);assertEquals(1,f.snapshot(third).alreadyQueued);
            assertEquals(1,f.book.materials().size());
        }
    }
    @Test public void quietDownloadKeepsSavedTextAndAddIsLimitedToLatestSession()throws Exception{
        try(Fixture f=new Fixture()){
            SourceCoordinator.DownloadProgress old=f.download(List.of(f.bio.seed().id));assertEquals(1,old.saved);assertTrue(f.book.materials().isEmpty());assertTrue(f.speech.isEmpty());
            SourceVersionRecord saved=f.book.sourceVersion(old.savedRecordKeys.iterator().next());assertNotNull(f.cache.get(saved)); // Not now: no mutation; still readable.
            SourceCoordinator.DownloadProgress current=f.download(List.of(f.rg.seed().id));assertThrows(IllegalArgumentException.class,()->f.coordinator.prepareDownloadedSnapshot(old,r->{}));
            SourceCoordinator.BulkSnapshot offer=f.snapshot(current);assertEquals(1,offer.versions.size());assertEquals(f.rg.seed().id,offer.versions.get(0).sourceId);
            SourceCoordinator.EnqueueProgress added=f.add(offer);assertEquals(added.message,1,added.added);assertTrue(f.speech.isEmpty());assertEquals(1,f.book.materials().size());assertNotNull(f.cache.get(saved));
        }
    }
    @Test public void addDuringSpeechDoesNotInterruptOrStartAnotherArticle()throws Exception{
        try(Fixture f=new Fixture()){
            f.add(f.snapshot(f.download(List.of(f.bio.seed().id))));NoteBook.Material bio=f.book.materials().get(0);f.player.selectArticle(bio.stationId,bio.articleId);f.player.play();f.player.onStart(f.speech.get(0));String active=f.player.snapshot().currentRequestId;int stops=f.stops,calls=f.speech.size();
            SourceCoordinator.DownloadProgress loaded=f.download(List.of(f.rg.seed().id));assertEquals(1,f.add(f.snapshot(loaded)).added);
            assertEquals(active,f.player.snapshot().currentRequestId);assertEquals(bio.version,f.player.snapshot().article.version);assertEquals(calls,f.speech.size());assertEquals(stops,f.stops);assertTrue(f.player.snapshot().playing);
        }
    }
    @Test public void partialSourceErrorsStillFinishAndOfferOnlySuccessfulBodies()throws Exception{
        try(Fixture f=new Fixture()){
            SourceCoordinator.DownloadProgress loaded=f.download(List.of(SourceProfiles45.byId("interfax-briefs").seed().id,f.rg.seed().id));assertEquals(2,loaded.processedSources);assertEquals(1,loaded.failedSources.size());assertEquals(1,loaded.savedRecordKeys.size());assertTrue(f.speech.isEmpty());assertEquals(1,f.add(f.snapshot(loaded)).added);assertTrue(f.speech.isEmpty());
        }
    }
    @Test public void failedVisibleTargetDoesNotWaitForeverForAnotherPublishersCandidate()throws Exception{
        try(Fixture f=new Fixture()){
            f.failRg=true;Set<String> selected=new LinkedHashSet<>(Set.of(f.bio.seed().id));String scope=QuickStart.primarySource(false,f.player.snapshot().station.id,f.book.sources(),selected);QuickStart q=new QuickStart();QuickStart.Ticket ticket=q.begin("failed","s","v",selected,scope,null);q.ready(ticket,"v");AtomicReference<SourceCoordinator.DownloadProgress> result=new AtomicReference<>();
            f.coordinator.downloadBatch(selected,3,new SourceCoordinator.AirUpdate("failed",null,event->q.published(event.runId,new QuickStart.Candidate(f.book.source(event.sourceId).stationId,event.version.articleId,event.version.versionKey,event.sourceId,event.version.channelReference(),event.version.kind(),true,false))),p->{result.set(p);if(p.finished&&!q.hasCandidate(p.runId))q.terminal(p.runId);});
            pump(f.owner,()->result.get()!=null&&result.get().finished);assertEquals(1,result.get().qualifyingFull);assertFalse(q.pending());assertNull(q.take(ticket,"s","v",c->true));assertTrue(f.speech.isEmpty());assertEquals(f.rg.seed().stationId,f.player.snapshot().station.id);
        }
    }
    @Test public void cancelledTicketRejectsLaterRgEvenAfterVoiceBecomesReady()throws Exception{
        try(Fixture f=new Fixture()){
            Set<String> selected=new LinkedHashSet<>(List.of(f.bio.seed().id,f.rg.seed().id));String scope=QuickStart.primarySource(false,f.player.snapshot().station.id,f.book.sources(),selected);QuickStart q=new QuickStart();QuickStart.Ticket ticket=q.begin("cancel","s","v",selected,scope,null);AtomicReference<SourceCoordinator.DownloadProgress> result=new AtomicReference<>();
            f.coordinator.downloadBatch(selected,3,new SourceCoordinator.AirUpdate("cancel",null,event->{if(event.sourceId.equals(f.bio.seed().id))q.revoke();q.published(event.runId,new QuickStart.Candidate(f.book.source(event.sourceId).stationId,event.version.articleId,event.version.versionKey,event.sourceId,event.version.channelReference(),event.version.kind(),true,false));}),result::set);
            pump(f.owner,()->result.get()!=null&&result.get().finished);q.ready(ticket,"v");assertNull(q.take(ticket,"s","v",c->true));assertFalse(q.pending());assertTrue(f.speech.isEmpty());
        }
    }
    @Test public void cancelledAddSnapshotCannotMutateAirAndSavedBodyRemains()throws Exception{
        try(Fixture f=new Fixture()){SourceCoordinator.DownloadProgress downloaded=f.download(List.of(f.rg.seed().id));SourceCoordinator.BulkSnapshot offer=f.snapshot(downloaded);f.coordinator.cancelUi();assertThrows(IllegalStateException.class,()->f.coordinator.enqueueBulk(offer,false,p->{}));assertTrue(f.book.materials().isEmpty());assertNotNull(f.cache.get(f.book.sourceVersion(downloaded.savedRecordKeys.iterator().next())));assertTrue(f.speech.isEmpty());}
    }
    @Test public void quietAddCleansCompletedOldSourceButPreservesCurrentSpeech()throws Exception{
        try(Fixture f=new Fixture()){
            f.add(f.snapshot(f.download(List.of(f.rg.seed().id))));NoteBook.Material old=f.book.materials().get(0);f.player.selectArticle(old.stationId,old.articleId);f.player.play();int guard=0;
            while(f.player.snapshot().playing){assertTrue(++guard<20);String id=f.speech.get(f.speech.size()-1);f.player.onStart(id);f.player.onDone(id);}
            f.add(f.snapshot(f.download(List.of(f.bio.seed().id))));NoteBook.Material bio=f.book.materials().stream().filter(m->m.stationId.equals(f.bio.seed().stationId)).findFirst().get();f.player.selectArticle(bio.stationId,bio.articleId);f.player.play();f.player.onStart(f.speech.get(f.speech.size()-1));String active=f.player.snapshot().currentRequestId;int stops=f.stops,calls=f.speech.size();
            f.rgSuffix="second";assertEquals(1,f.add(f.snapshot(f.download(List.of(f.rg.seed().id)))).added);assertNull(f.book.material(old.stationId,old.articleId));assertEquals(active,f.player.snapshot().currentRequestId);assertEquals(stops,f.stops);assertEquals(calls,f.speech.size());assertTrue(f.player.snapshot().playing);
        }
    }

}

package ru.bububu.radio.sources;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import ru.bububu.radio.core.*;

/** Actual sequential coordinator workers, Telegram parser, CAS and atomic Book fixtures. */
public final class SourceBatchCoordinatorTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final class Clock implements SourceClock {long time=1;public long monotonicMillis(){return time;}public long wallMillis(){return 1_700_000_000_000L+time;}}
    private final class Store implements NoteBook.MigrationStorage {
        byte[] bytes;boolean unknown,unreadable,rejectNextSave;final MigrationCapsuleStore capsules;
        Store()throws Exception{capsules=new MigrationCapsuleStore(temp.newFolder());}
        public byte[] load(){if(unreadable)throw new IllegalStateException("Unknown authority");return bytes==null?null:bytes.clone();}
        public void save(byte[] value){if(rejectNextSave){rejectNextSave=false;throw new IllegalStateException("Known unchanged save");}if(unknown){unreadable=true;throw new IllegalStateException("Unknown save");}bytes=value.clone();}
        public MigrationStamp prepareMigration(byte[] baseline,int target){return capsules.prepare(baseline,target,new byte[][]{bytes,null,null});}
        public void verifyMigrationStamp(MigrationStamp stamp){capsules.verify(stamp);}
        public UpgradeRollbackRef prepareSchema3Upgrade(byte[] baseline,int target){return capsules.prepareSchema3Upgrade(baseline,target,new byte[][]{bytes,null,null});}
        public void verifyUpgradeRollback(UpgradeRollbackRef ref){capsules.verifyUpgradeRollback(ref);}
        public void verifyUpgradeRollback(UpgradeRollbackRef ref,MigrationStamp origin){capsules.verifyUpgradeRollback(ref,origin);}
    }
    private static final class Transport implements SourceTransport {
        volatile boolean unreadableAll;volatile int posts=21;volatile String failureSource,failureReason="HTTP_503";volatile boolean unreadableLatest,malformed;final AtomicInteger requests=new AtomicInteger();
        public Response fetch(String url,Kind kind,Validators validators,RequestScope scope)throws SourceFailure{
            requests.incrementAndGet();String source=url.substring(url.lastIndexOf('/')+1);if(source.equals(failureSource))throw new SourceFailure(failureReason);
            if(malformed)return new Response("<p>SECRET_MALFORMED</p>".getBytes(java.nio.charset.StandardCharsets.UTF_8),url,200,"text/html","UTF-8",null,null);
            StringBuilder html=new StringBuilder("<div class='tgme_channel_info_header_username'>@"+source+"</div><div class='tgme_channel_info_header_title'>Новости "+source+"</div>");
            for(int i=1;i<=posts;i++){html.append("<div class='tgme_widget_message' data-post='").append(source).append('/').append(i).append("'>");if(!unreadableAll&&(!unreadableLatest||i!=posts))html.append("<div class='tgme_widget_message_text js-message_text'>Пост ").append(i).append(". Второе предложение.</div>");html.append("<a class='tgme_widget_message_date'><time datetime='2026-10-12T12:").append(String.format(Locale.ROOT,"%02d",i)).append(":00Z'></time></a></div>");}
            return new Response(html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),"https://t.me/s/"+source,200,"text/html","UTF-8",null,null);
        }
    }
    private final class Fixture implements AutoCloseable {
        final Clock clock=new Clock();final Store store=new Store();final NoteBook book=new NoteBook(store);
        final SourceBodyCache cache=new SourceBodyCache(temp.newFolder());final Transport transport=new Transport();final BlockingQueue<Runnable> owner=new LinkedBlockingQueue<>();
        final Port port=new Port();final SourceCoordinator coordinator=new SourceCoordinator(book,transport,cache,port,clock);
        Fixture()throws Exception{}
        final class Port implements SourceCoordinator.Port {
            int appends,queueLimit=100;boolean failAppend,failSnapshot;String snapshot;
            public void writeLoadFailureSnapshot(String json){if(failSnapshot)throw new IllegalStateException("SECRET_WRITER");snapshot=json;}
            PlayerController.SavedState state=new PlayerController.SavedState(null,Collections.emptyMap(),Collections.emptyMap());
            public void dispatch(Runnable action){owner.add(action);}
            public void requireMutable(){if(book.storageUncertain())throw new IllegalStateException("Unknown authority");}
            public void requireStorage(long peak){}
            public void requireQueue(SourceRecord source,Article article){if(book.materials().size()>=queueLimit)throw new IllegalStateException("Queue capacity");}
            public void appendStation(SourceRecord source){}
            public void append(NoteBook.Material material){if(failAppend)throw new IllegalStateException("Projection pending");appends++;Map<String,PlayerController.SavedProgress> p=new LinkedHashMap<>(state.progress);p.put(PlayerController.progressKey(material.stationId,material.articleId,material.version),material.selectionInitialProgress);state=new PlayerController.SavedState(material.stationId,Collections.singletonMap(material.stationId,material.articleId),p);}
            public PlayerController.SavedProgress persisted(NoteBook.Material m){return state.progress.get(PlayerController.progressKey(m.stationId,m.articleId,m.version));}
            public void pauseIfSelected(NoteBook.Material m){}
            public void remove(NoteBook.Material m){}
            public void projectRead(SourceRecord source,SourceItem item){}
            public void captureHistory(){}
            public void changed(){}
            public String currentIdentity(){return null;}
            public void checkpointMutation(List<NoteBook.Material> removed,boolean clear){}
            public void checkpointDeletion(String station,String article){}
            public PlayerController.SavedState persistedState(){return state;}
            public void projectDeletion(){state=book.reconcileState(state);}
        }
        void until(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end){Runnable action=owner.poll(100,TimeUnit.MILLISECONDS);if(action!=null)action.run();}assertTrue("Owner completion",condition.getAsBoolean());}
        Runnable queued()throws Exception{Runnable value=owner.poll(3,TimeUnit.SECONDS);assertNotNull(value);return value;}
        <T> T await(AtomicReference<SourceCoordinator.Result<T>> result)throws Exception{until(()->result.get()!=null);assertNull(result.get().error);return result.get().value;}
        SourceRecord connect(String name)throws Exception{AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> result=new AtomicReference<>();coordinator.preview("@"+name,true,null,result::set);return coordinator.connect(await(result),"Станция "+name);}
        SourceCoordinator.DownloadProgress download(Collection<String> ids,int count)throws Exception{AtomicReference<SourceCoordinator.DownloadProgress> result=new AtomicReference<>();coordinator.downloadBatch(ids,count,result::set);until(()->result.get()!=null&&result.get().finished);return result.get();}
        SourceCoordinator.BulkSnapshot snapshot(String filter)throws Exception{AtomicReference<SourceCoordinator.Result<SourceCoordinator.BulkSnapshot>> result=new AtomicReference<>();coordinator.prepareBulkSnapshot(filter,result::set);return await(result);}
        SourceCoordinator.EnqueueProgress enqueue(SourceCoordinator.BulkSnapshot snapshot,boolean limited)throws Exception{AtomicReference<SourceCoordinator.EnqueueProgress> result=new AtomicReference<>();coordinator.enqueueBulk(snapshot,limited,result::set);until(()->result.get()!=null&&result.get().finished);return result.get();}
        public void close(){coordinator.close();}
    }
    private SourceRecord syntheticProfile(Fixture f,ChannelProfile.Kind kind)throws Exception{
        f.transport.posts=3;SourceRecord source=f.connect("profile_fixture");
        SourceRecord trusted=new ChannelProfile("fixture","channel","r1",source.key,source.adapterId,"fixture-full-v1",kind).bind(source);f.book.updateSource(trusted);
        for(SourceItem item:f.book.sourceItems(source.id)){
            SourceVersionRecord full=SourceCoordinator.version(trusted,item,new ExtractionResult("Полный вымышленный текст "+item.providerId+". Второе предложение.",item.title,item.title,"full","","fixture","fixture-full-v1"));f.cache.put(full);f.book.saveSourceVersion(full.withPayload(null));
        }
        f.transport.unreadableAll=true;return trusted;
    }
    private SourceCoordinator.DownloadProgress air(Fixture f,SourceRecord source,String explicit,List<SourceCoordinator.CommittedFull> events)throws Exception{
        AtomicReference<SourceCoordinator.DownloadProgress> result=new AtomicReference<>();f.coordinator.downloadBatch(Collections.singleton(source.id),3,new SourceCoordinator.AirUpdate("run-"+UUID.randomUUID(),explicit,events::add),result::set);f.until(()->result.get()!=null&&result.get().finished);return result.get();
    }
    @Test public void repeatAndCacheRepairKeepKnownTextsEligibleWithoutCallingThemNew() throws Exception {
        try(Fixture f=new Fixture()){
            f.transport.posts=1;
            SourceRecord source=f.connect("freshness");
            // Connect has discovered the exact inline version but has not stored its payload.
            SourceCoordinator.DownloadProgress first=f.download(List.of(source.id),3);
            assertEquals(1,first.saved);assertEquals(0,first.newVersions);assertEquals(1,first.fetchedKnown);
            assertEquals("Получены тексты известных материалов",DownloadPresentation.outcome(first));
            String key=first.savedRecordKeys.iterator().next();
            SourceCoordinator.DownloadProgress repeat=f.download(List.of(source.id),3);
            assertEquals(Set.of(key),repeat.savedRecordKeys);
            assertEquals(0,repeat.newVersions);assertEquals(0,repeat.fetchedKnown);
            assertEquals("Новых материалов нет",DownloadPresentation.outcome(repeat));

            f.cache.discard(f.book.sourceVersion(key)); // Simulate a missing disposable payload, not a new story.
            SourceCoordinator.DownloadProgress repaired=f.download(List.of(source.id),3);
            assertEquals(Set.of(key),repaired.savedRecordKeys);assertEquals(0,repaired.newVersions);assertEquals(1,repaired.fetchedKnown);
            assertNotNull(f.cache.get(f.book.sourceVersion(key)));
            AtomicReference<SourceCoordinator.Result<SourceCoordinator.BulkSnapshot>> offer=new AtomicReference<>();
            f.coordinator.prepareDownloadedSnapshot(repaired,offer::set);
            assertEquals(1,f.enqueue(f.await(offer),true).added);

            f.transport.posts=2;
            SourceCoordinator.DownloadProgress mixed=f.download(List.of(source.id),3);
            assertEquals(2,mixed.saved);assertEquals(1,mixed.newVersions);assertEquals(0,mixed.fetchedKnown);
            assertEquals("Сохранены новые или обновлённые материалы",DownloadPresentation.outcome(mixed));
            assertEquals(1,f.book.materials().size());assertEquals(2,f.book.sourceItems(source.id).size());
            assertEquals(1,f.book.sourceVersions(source.id,f.book.sourceVersion(key).itemKey).size());
        }
    }
    @Test public void quietAddKeepsLimitedPostsForManualReviewAndReportsRealQueueCounts() throws Exception {
        try (Fixture f=new Fixture()) {
            f.transport.posts=1;
            SourceRecord telegram=f.connect("post");
            f.download(List.of(telegram.id),3);
            SourceCoordinator.EnqueueProgress limited=f.enqueue(f.snapshot(telegram.id),false);
            assertTrue(limited.needsReview());
            assertEquals(1,limited.restricted);
            assertEquals(1,limited.skipped);
            assertTrue(limited.downloadResult(f.clock).contains("«В эфир»"));
            assertFalse(limited.downloadResult(f.clock).contains("Слушать"));
            assertTrue(f.book.materials().isEmpty());

            SourceRecord full=syntheticProfile(f,ChannelProfile.Kind.ARTICLE_ANALYSIS);
            SourceCoordinator.EnqueueProgress partial=f.enqueue(f.snapshot(null),false);
            assertTrue(partial.needsReview());
            assertEquals(3,partial.added);
            assertEquals(1,partial.skipped);
            assertEquals(1,partial.restricted);
            assertTrue(partial.downloadResult(f.clock).contains("Добавлено: 3"));
            assertEquals(3,f.book.materials().size());

            SourceCoordinator.EnqueueProgress already=f.enqueue(f.snapshot(full.id),false);
            assertEquals(0,already.added);
            assertEquals(3,already.alreadyQueued);
            assertFalse(already.needsReview());
            assertTrue(already.downloadResult(f.clock).contains("уже в эфире: 3"));
            assertFalse(already.downloadResult(f.clock).contains("Слушать"));
        }
    }
    @Test public void failedQueueAdditionReportsTheStopWithoutInvitingPlayback() throws Exception {
        try (Fixture f=new Fixture()) {
            SourceRecord source=syntheticProfile(f,ChannelProfile.Kind.ARTICLE_ANALYSIS);
            f.port.queueLimit=0;
            SourceCoordinator.EnqueueProgress failure=f.enqueue(f.snapshot(source.id),false);
            assertEquals(0,failure.added);
            assertNotNull(failure.stopFailure);
            assertTrue(failure.downloadResult(f.clock).contains("остановлено"));
            assertFalse(failure.downloadResult(f.clock).contains("Слушать"));
            f.port.queueLimit=3;
            SourceCoordinator.EnqueueProgress complete=f.enqueue(f.snapshot(source.id),false);
            assertEquals(3,complete.added);
            assertEquals(0,complete.skipped);
            assertFalse(complete.needsReview());
            assertTrue(complete.downloadResult(f.clock).contains("Слушать"));
        }
    }
    @Test public void quietAddExplainsAnEmptySnapshotWithoutSuggestingPlay() throws Exception {
        try (Fixture f=new Fixture()) {
            SourceCoordinator.EnqueueProgress empty=f.enqueue(f.snapshot(null),false);
            assertEquals(0,empty.added);
            assertEquals(0,empty.skipped);
            assertEquals(0,empty.alreadyQueued);
            assertTrue(empty.needsReview());
            assertFalse(empty.downloadResult(f.clock).contains("Слушать"));
        }
    }
    @Test public void manualThreeCachesOnlyAndFullPublicationFollowsBookAndValidatedQueue()throws Exception{try(Fixture f=new Fixture()){
        SourceRecord source=syntheticProfile(f,ChannelProfile.Kind.ARTICLE_ANALYSIS);SourceCoordinator.DownloadProgress manual=f.download(Collections.singleton(source.id),3);assertEquals(3,manual.saved);assertTrue(f.book.materials().isEmpty());assertEquals(0,manual.cleanupRemoved);
        List<SourceCoordinator.CommittedFull> events=new ArrayList<>();SourceCoordinator.DownloadProgress updated=air(f,source,null,events);assertEquals(3,updated.qualifyingFull);assertEquals(3,events.size());assertEquals(3,f.book.materials().size());
        for(SourceCoordinator.CommittedFull event:events){assertEquals(ChannelProfile.Kind.ARTICLE_ANALYSIS,event.kind);assertFalse(event.channelReference.isEmpty());NoteBook.Material selected=f.book.material(source.stationId,event.version.articleId);assertEquals(event.recordKey,selected.sourceVersionKey);assertNotNull(f.book.sourceVersion(event.recordKey));assertEquals(NoteBook.Membership.ALLOWED,f.book.queueMembership(selected.stationId,selected.asArticle(),selected.sequence,selected.sourceVersionKey));}
        for(SourceItem item:f.book.sourceItems(source.id))f.book.setSourceRead(source.id,item.itemKey,true);SourceCoordinator.DownloadProgress unchanged=air(f,source,null,new ArrayList<>());assertEquals(0,unchanged.qualifyingFull);assertEquals(0,unchanged.cleanupRemoved);assertEquals(3,f.book.materials().size());
    }}
    @Test public void briefFullIsNotDefaultPoolButExplicitStationMayAdmitIt()throws Exception{try(Fixture f=new Fixture()){
        SourceRecord source=syntheticProfile(f,ChannelProfile.Kind.NEWS_BRIEF);SourceCoordinator.DownloadProgress excluded=air(f,source,null,new ArrayList<>());assertEquals(0,excluded.qualifyingFull);assertTrue(f.book.materials().isEmpty());List<SourceCoordinator.CommittedFull> events=new ArrayList<>();assertEquals(3,air(f,source,source.id,events).qualifyingFull);assertEquals(ChannelProfile.Kind.NEWS_BRIEF,events.get(0).kind);assertEquals("Короткая новость / RSS",ChannelProfile.label(events.get(0).version));
    }}
    @Test public void profileRevisionDoesNotReclassifyPreviouslySavedVersions()throws Exception{try(Fixture f=new Fixture()){
        SourceRecord source=syntheticProfile(f,ChannelProfile.Kind.ARTICLE_ANALYSIS);List<SourceCoordinator.CommittedFull> events=new ArrayList<>();air(f,source,null,events);SourceVersionRecord old=events.get(0).version;
        SourceRecord changed=new ChannelProfile("fixture","feed","r2",source.key,source.adapterId,"fixture-full-v1",ChannelProfile.Kind.NEWS_BRIEF).bind(source);f.book.updateSource(changed);NoteBook restored=new NoteBook(f.store);assertEquals(ChannelProfile.Kind.ARTICLE_ANALYSIS,restored.sourceVersion(old.recordKey).kind());assertEquals(ChannelProfile.Kind.NEWS_BRIEF,restored.source(source.id).kind());assertNotEquals(old.channelReference(),changed.channelReference());assertEquals(ChannelProfile.Kind.UNKNOWN,ChannelProfile.kind("feed-inline-v1"));
    }}
    @Test public void completedCleanupGuardsCurrentPartialContextStaleAndFailedSubset()throws Exception{try(Fixture f=new Fixture()){
        SourceRecord source=syntheticProfile(f,ChannelProfile.Kind.ARTICLE_ANALYSIS);air(f,source,null,new ArrayList<>());List<NoteBook.Material> materials=f.book.materials();for(SourceItem item:f.book.sourceItems(source.id))f.book.setSourceRead(source.id,item.itemKey,true);
        NoteBook.Material current=materials.get(0),linked=materials.get(1);f.book.beginRecordingLinkedSource("Связь",linked.sourceVersionKey,1);
        String identity=PlayerController.progressKey(current.stationId,current.articleId,current.version);NoteBook.CompletedCleanup plan=f.book.prepareCompletedCleanup(Collections.singleton(source.id),f.port.state,identity,4);assertEquals(1,plan.removed);assertEquals(1,plan.contextKept);assertThrows(IllegalStateException.class,()->f.book.applyCompletedCleanup(plan,identity,5));assertEquals(3,f.book.materials().size());
        assertTrue(f.book.applyCompletedCleanup(plan,identity,4));assertFalse(f.book.applyCompletedCleanup(plan,identity,4));assertEquals(2,f.book.materials().size());assertNotNull(f.book.sourceVersion(linked.sourceVersionKey));assertNotNull(new NoteBook(f.store).sourceVersion(linked.sourceVersionKey));
        NoteBook.CompletedCleanup stale=f.book.prepareCompletedCleanup(Collections.singleton(source.id),f.port.state,identity,4);f.book.edit(f.book.notes().get(0).id,"Новая правка","");assertThrows(IllegalStateException.class,()->f.book.applyCompletedCleanup(stale,identity,4));
        assertEquals(0,f.book.prepareCompletedCleanup(Collections.singleton("failed-source"),f.port.state,null,4).removed);
    }}
    @Test public void cleanupRequiresExactPersistedCoverageAndKnownFailedCommitPreservesRestore()throws Exception{try(Fixture f=new Fixture()){
        SourceRecord source=syntheticProfile(f,ChannelProfile.Kind.ARTICLE_ANALYSIS);air(f,source,null,new ArrayList<>());List<NoteBook.Material> materials=f.book.materials();Map<String,PlayerController.SavedProgress> progress=new LinkedHashMap<>(f.port.state.progress);
        for(int i=0;i<materials.size();i++){NoteBook.Material m=materials.get(i);SourceVersionRecord version=f.book.sourceVersion(m.sourceVersionKey);List<Integer> coverage=new ArrayList<>();if(i==2)for(int n=0;n<version.sentenceStarts.size();n++)coverage.add(n);else if(i==0)coverage.add(0);progress.put(PlayerController.progressKey(m.stationId,m.articleId,m.version),new PlayerController.SavedProgress(m.stationId,m.articleId,m.version,version.sentenceStarts.size()-1,i!=1,coverage,false,i!=1));}
        PlayerController.SavedState persisted=new PlayerController.SavedState(source.stationId,f.port.state.stationArticleIds,progress);NoteBook.Material completed=materials.get(2);String current=PlayerController.progressKey(completed.stationId,completed.articleId,completed.version);assertEquals(0,f.book.prepareCompletedCleanup(Collections.singleton(source.id),persisted,current,1).removed);
        NoteBook.CompletedCleanup plan=f.book.prepareCompletedCleanup(Collections.singleton(source.id),persisted,null,1);assertEquals(1,plan.removed);byte[] before=f.store.bytes.clone();f.store.rejectNextSave=true;assertThrows(IllegalStateException.class,()->f.book.applyCompletedCleanup(plan,null,1));assertArrayEquals(before,f.store.bytes);assertEquals(3,new NoteBook(f.store).materials().size());assertFalse(f.book.storageUncertain());
        assertTrue(f.book.applyCompletedCleanup(plan,null,1));assertEquals(2,new NoteBook(f.store).materials().size());
    }}
    @Test public void loadSnapshotDistinguishesFeedParsePublishAndTransportAndClearsOnSuccess()throws Exception {
        for(int mode:new int[]{0,1,2})try(Fixture f=new Fixture()){
            SourceRecord source=f.connect("phase");int before=f.transport.requests.get();
            if(mode==0){f.transport.failureSource="phase";f.transport.failureReason="TIMEOUT";}else if(mode==1)f.transport.malformed=true;else f.store.rejectNextSave=true;
            SourceCoordinator.DownloadProgress result=f.download(Collections.singleton(source.id),5);assertEquals(1,result.failed);assertEquals(before+1,f.transport.requests.get());assertNotNull(f.port.snapshot);
            String expected=mode==0?"BATCH_FEED_GET":mode==1?"BATCH_FEED_PARSE":"BATCH_FEED_PUBLISH";
            assertTrue(f.port.snapshot,f.port.snapshot.contains("\"phase\":\""+expected+"\""));assertFalse(f.port.snapshot.contains("SECRET"));
            if(mode==0)assertEquals("DL1|TIMEOUT|0|TIMEOUT",f.book.source(source.id).lastError);
            f.transport.failureSource=null;f.transport.malformed=false;SourceCoordinator.DownloadProgress success=f.download(Collections.singleton(source.id),5);assertEquals(5,success.saved);assertNull(f.port.snapshot);
        }
    }
    @Test public void snapshotWriterThrowNeverChangesPrimaryDL1OrRequestCounts()throws Exception {
        try(Fixture f=new Fixture()){
            SourceRecord source=f.connect("writer");f.port.failSnapshot=true;f.transport.failureSource="writer";f.transport.failureReason="TIMEOUT";int before=f.transport.requests.get();
            SourceCoordinator.DownloadProgress result=f.download(Collections.singleton(source.id),5);
            assertEquals(1,result.failed);assertEquals(0,result.saved);assertEquals("DL1|TIMEOUT|0|TIMEOUT",f.book.source(source.id).lastError);assertEquals(before+1,f.transport.requests.get());assertFalse(result.cancelled);
        }
    }
    @Test public void countFiveTenTwentyUsesOneFeedPerSourceAndStableLatestOrder()throws Exception{
        for(int count:new int[]{5,10,20})try(Fixture f=new Fixture()){
            SourceRecord first=f.connect("first"),second=f.connect("second");int before=f.transport.requests.get();SourceCoordinator.DownloadProgress result=f.download(Arrays.asList(second.id,first.id),count);
            assertTrue(result.finished);assertFalse(result.cancelled);assertNull(result.stopFailure);assertEquals(count*2,result.saved);assertEquals(0,result.failed);assertEquals(2,f.transport.requests.get()-before);assertEquals(first.id,result.sources.get(0).sourceId);assertEquals(count,result.sources.get(0).candidates);assertEquals(count,result.sources.get(1).loaded);
            SourceCoordinator.BulkSnapshot snapshot=f.snapshot(null);assertEquals(count*2,snapshot.downloaded.size());assertEquals(count*2,snapshot.versions.size());assertEquals(first.id,snapshot.downloaded.get(0).sourceId);assertEquals("21",f.book.sourceItem(first.id,snapshot.downloaded.get(0).itemKey).providerId);assertEquals(count*2,snapshot.limitedCount);assertEquals(0,snapshot.fullCount);assertEquals(before+2,f.transport.requests.get());assertTrue(f.book.materials().isEmpty());for(SourceItem item:f.book.sourceItems(first.id)){assertFalse(item.read);assertNull(item.lastViewedVersion);}
            assertThrows(IllegalArgumentException.class,()->f.coordinator.downloadBatch(Collections.singleton(first.id),6,x->fail()));
        }
    }
    @Test public void fiveDownloadedTextsAmongTwentyOneHeadersEnqueueOnlyExactOfflineConsent()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRecord source=f.connect("five_of_twenty_one");SourceCoordinator.DownloadProgress download=f.download(Collections.singleton(source.id),5);
            assertEquals(21,f.book.sourceItems(source.id).size());assertEquals(5,download.saved);int requests=f.transport.requests.get();
            SourceCoordinator.BulkSnapshot consent=f.snapshot(null);assertEquals(5,consent.downloaded.size());assertEquals(5,consent.versions.size());assertEquals(16,consent.skipped);
            SourceCoordinator.EnqueueProgress result=f.enqueue(consent,true);assertEquals(5,result.added);assertEquals(0,result.skipped);assertEquals(0,result.errors);assertEquals(0,result.remaining);assertEquals(5,f.port.appends);assertEquals(requests,f.transport.requests.get());
            List<Long> sequence=new ArrayList<>();for(NoteBook.Material material:f.book.materials())sequence.add(material.sequence);
            SourceCoordinator.BulkSnapshot repeat=f.snapshot(null);assertEquals(5,repeat.alreadyQueued);assertTrue(repeat.versions.isEmpty());
            SourceCoordinator.EnqueueProgress again=f.enqueue(repeat,true);assertEquals(0,again.added);assertEquals(5,again.alreadyQueued);assertEquals(0,again.skipped);assertEquals(0,again.errors);assertEquals(5,f.book.materials().size());assertEquals(5,f.port.appends);assertEquals(requests,f.transport.requests.get());
            List<Long> after=new ArrayList<>();for(NoteBook.Material material:f.book.materials())after.add(material.sequence);assertEquals(sequence,after);
        }
    }
    @Test public void failedAndDisabledSourcesDoNotDiscardOtherConfirmedTexts()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRecord bad=f.connect("broken"),good=f.connect("good");f.transport.failureSource="broken";SourceCoordinator.DownloadProgress result=f.download(Arrays.asList(bad.id,good.id),5);assertEquals(5,result.saved);assertEquals(1,result.failed);assertTrue(result.sources.get(0).finished);assertEquals(5,result.sources.get(1).loaded);assertNull(result.stopFailure);assertEquals("Часть источников недоступна",DownloadPresentation.outcome(result));
            f.coordinator.enabled(good.id,false);int requests=f.transport.requests.get();SourceCoordinator.DownloadProgress disabled=f.download(Collections.singleton(good.id),5);assertEquals(0,disabled.saved);assertEquals(1,disabled.skipped);assertEquals("Проверка завершена с пропусками",DownloadPresentation.outcome(disabled));assertEquals(requests,f.transport.requests.get());SourceCoordinator.BulkSnapshot local=f.snapshot(good.id);assertEquals(5,local.downloaded.size());assertEquals(5,f.enqueue(local,true).added);assertEquals(requests,f.transport.requests.get());
        }
    }
    @Test public void feedFailureDiagnosticIsDurableWithoutChangingValidatorsSuccessfulTextOrBatchAuthority()throws Exception {
        try(Fixture f=new Fixture()){
            f.transport.posts=5;SourceRecord source=f.connect("diagnostic_feed");f.download(Collections.singleton(source.id),5);SourceCoordinator.BulkSnapshot snapshot=f.snapshot(source.id);f.coordinator.enqueue(snapshot.versions.get(0),true);
            SourceRecord current=f.book.source(source.id);f.book.updateSource(current.withState(true,"saved-etag","saved-modified",1234,null));long generation=f.book.sourceGeneration(source.id),epoch=f.book.authorityEpoch();String material=f.book.materials().get(0).text;int requests=f.transport.requests.get();
            f.transport.failureSource="diagnostic_feed";SourceCoordinator.DownloadProgress result=f.download(Collections.singleton(source.id),5);SourceRecord failed=f.book.source(source.id);
            assertEquals(0,result.saved);assertEquals(1,result.failed);assertEquals(0,result.skipped);assertEquals("Не удалось загрузить материалы",DownloadPresentation.outcome(result));assertTrue(result.finished);assertFalse(result.cancelled);assertNull(result.stopFailure);assertTrue(result.sources.get(0).message.contains("503"));
            assertEquals("DL1|HTTP|503|HTTP_503",failed.lastError);assertEquals("saved-etag",failed.etag);assertEquals("saved-modified",failed.lastModified);assertEquals(1234,failed.lastSuccess);assertEquals(generation,f.book.sourceGeneration(source.id));assertEquals(epoch,f.book.authorityEpoch());assertEquals(material,f.book.materials().get(0).text);assertEquals(5,f.book.sourceItems(source.id).size());assertEquals(requests+1,f.transport.requests.get());
            NoteBook reopened=new NoteBook(f.store);assertEquals(failed.lastError,reopened.source(source.id).lastError);assertEquals(503,f.coordinator.sourceFailure(failed).status);assertEquals(snapshot.downloaded.get(0).spokenText,f.cache.get(snapshot.downloaded.get(0)).spokenText);
            f.transport.failureSource=null;SourceCoordinator.DownloadProgress recovered=f.download(Collections.singleton(source.id),5);assertEquals(5,recovered.saved);assertNull(f.book.source(source.id).lastError);
        }
    }
    @Test public void bodyFailureSurvivesLaterSuccessfulBodiesAndFinalSourceMessage()throws Exception {
        try(Fixture f=new Fixture()){
            f.transport.posts=5;f.transport.unreadableLatest=true;SourceRecord source=f.connect("diagnostic_body");long generation=f.book.sourceGeneration(source.id);
            SourceCoordinator.DownloadProgress result=f.download(Collections.singleton(source.id),5);
            assertEquals(4,result.saved);assertEquals(0,result.failed);assertEquals(1,result.skipped);assertTrue(result.finished);assertFalse(result.cancelled);assertNull(result.stopFailure);assertEquals(5,result.sources.get(0).processed);
            assertEquals("DL1|UNAVAILABLE|0|NO_OWN_TEXT",f.book.source(source.id).lastError);assertEquals(generation,f.book.sourceGeneration(source.id));assertTrue(result.sources.get(0).message.contains(SourceFailureInfo.forReason("NO_OWN_TEXT").message(f.clock)));assertEquals(4,f.snapshot(source.id).downloaded.size());assertTrue(f.book.materials().isEmpty());
        }
    }
    @Test public void lateFeedFailureCannotWriteAfterCancelDeleteDisableOrReplacementRun()throws Exception {
        for(int mode:new int[]{0,1,2,3})try(Fixture f=new Fixture()){
            SourceRecord source=f.connect("diagnostic_late");f.transport.failureSource="diagnostic_late";AtomicReference<SourceCoordinator.DownloadProgress> first=new AtomicReference<>();f.coordinator.downloadBatch(Collections.singleton(source.id),5,first::set);f.queued().run();Runnable late=f.queued();
            AtomicReference<SourceCoordinator.DownloadProgress> replacement=new AtomicReference<>();
            if(mode==1)f.coordinator.deleteSource(source.id);else if(mode==2)f.coordinator.enabled(source.id,false);else f.coordinator.cancelBatch();
            if(mode==3){f.transport.failureSource=null;f.coordinator.downloadBatch(Collections.singleton(source.id),5,replacement::set);}
            byte[] before=f.store.load();late.run();assertNull(f.port.snapshot);assertArrayEquals(before,f.store.load());assertNull(f.book.source(source.id).lastError);assertTrue(first.get().finished);
            if(mode==1)assertTrue(f.book.isSourceRetired(source.id));if(mode==2)assertFalse(f.book.source(source.id).enabled);if(mode==3){f.until(()->replacement.get()!=null&&replacement.get().finished);assertEquals(5,replacement.get().saved);assertNull(f.book.source(source.id).lastError);}
        }
    }
    @Test public void diagnosticWriteFailureKeepsPrimaryCountsAndDoesNotBecomeSuccessOrCancelOtherSources()throws Exception {
        for(boolean unknown:new boolean[]{false,true})try(Fixture f=new Fixture()){
            SourceRecord bad=f.connect("diagnostic_save"),good=f.connect("diagnostic_good");f.transport.failureSource="diagnostic_save";
            if(unknown)f.store.unknown=true;else f.store.rejectNextSave=true;
            SourceCoordinator.DownloadProgress result=f.download(Arrays.asList(bad.id,good.id),5);assertEquals(1,result.failed);assertTrue(result.sources.get(0).message.contains("503"));assertNull(f.book.source(bad.id).lastError);
            if(unknown){assertTrue(f.book.storageUncertain());assertEquals(0,result.saved);assertNotNull(result.stopFailure);}else{assertFalse(result.cancelled);assertNull(result.stopFailure);assertEquals(5,result.saved);assertFalse(f.book.storageUncertain());}
        }
    }
    @Test public void unknownFailurePersistsOnlyFallbackCodeAndNeverCopiesRawContent()throws Exception {
        try(Fixture f=new Fixture()){
            SourceRecord source=f.connect("diagnostic_raw");String raw="https://private.invalid/SECRET_BODY_SENTINEL";f.transport.failureSource="diagnostic_raw";f.transport.failureReason=raw;
            SourceCoordinator.DownloadProgress result=f.download(Collections.singleton(source.id),5);assertEquals(1,result.failed);assertEquals("DL1|UNAVAILABLE|0|SOURCE_UNAVAILABLE",f.book.source(source.id).lastError);assertFalse(result.sources.get(0).message.contains(raw));assertFalse(result.sources.get(0).message.contains("SECRET_BODY_SENTINEL"));
        }
    }
    @Test public void cancelDeleteAndUnknownAuthorityBlockQueuedBodyPublication()throws Exception{
        for(int mode:new int[]{0,1,2})try(Fixture f=new Fixture()){
            SourceRecord source=f.connect("example");AtomicReference<SourceCoordinator.DownloadProgress> progress=new AtomicReference<>();f.coordinator.downloadBatch(Collections.singleton(source.id),5,progress::set);assertThrows(IllegalStateException.class,()->f.coordinator.downloadBatch(Collections.singleton(source.id),5,x->fail()));
            f.until(()->progress.get().sources.get(0).candidates==5);f.queued().run();Runnable body=f.queued();
            if(mode==0)f.coordinator.cancelBatch();else if(mode==1)f.coordinator.deleteSource(source.id);else{f.store.unknown=true;assertThrows(IllegalStateException.class,()->f.book.beginRecording("Неизвестное сохранение",null,null,null,1));}
            body.run();assertTrue(progress.get().finished);assertEquals(0,progress.get().saved);assertFalse(f.coordinator.batchRunning());if(mode==1)assertTrue(f.book.isSourceRetired(source.id));if(mode==2)assertTrue(f.book.storageUncertain());else assertTrue(f.snapshot(null).downloaded.isEmpty());assertEquals(2,f.transport.requests.get());
        }
        try(Fixture f=new Fixture()){
            SourceRecord source=f.connect("partial");AtomicReference<SourceCoordinator.DownloadProgress> progress=new AtomicReference<>();f.coordinator.downloadBatch(Collections.singleton(source.id),5,progress::set);f.until(()->progress.get().saved==1);f.coordinator.cancelBatch();assertTrue(progress.get().cancelled);assertEquals("Загрузка отменена",DownloadPresentation.outcome(progress.get()));assertEquals(1,progress.get().saved);assertEquals(1,f.snapshot(null).downloaded.size());assertTrue(f.book.materials().isEmpty());
        }
    }
    @Test public void allFilterAndLimitedConsentAreOfflineIndependentOfDownloadSelection()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRecord first=f.connect("first"),second=f.connect("second");f.download(Collections.singleton(first.id),5);f.download(Collections.singleton(second.id),5);int requests=f.transport.requests.get();SourceCoordinator.BulkSnapshot all=f.snapshot(null),one=f.snapshot(first.id);assertEquals(10,all.downloaded.size());assertEquals(5,one.downloaded.size());assertEquals(5,one.limitedCount);
            assertEquals(0,f.enqueue(one,false).added);assertTrue(f.book.materials().isEmpty());SourceCoordinator.BulkSnapshot refreshed=f.snapshot(null);SourceCoordinator.EnqueueProgress result=f.enqueue(refreshed,true);assertEquals(10,result.added);assertEquals(requests,f.transport.requests.get());assertEquals(10,f.port.appends);List<Long> sequences=new ArrayList<>();for(NoteBook.Material material:f.book.materials())sequences.add(material.sequence);
            SourceCoordinator.BulkSnapshot repeated=f.snapshot(null);assertEquals(10,repeated.alreadyQueued);assertTrue(repeated.versions.isEmpty());assertEquals(0,f.enqueue(repeated,true).added);List<Long> after=new ArrayList<>();for(NoteBook.Material material:f.book.materials())after.add(material.sequence);assertEquals(sequences,after);assertEquals(requests,f.transport.requests.get());
        }
    }
    @Test public void snapshotVersionChangeIsSkippedAndImmutableChosenVersionIsNeverReplaced()throws Exception{
        try(Fixture f=new Fixture()){
            f.transport.posts=2;SourceRecord source=f.connect("example");f.download(Collections.singleton(source.id),5);SourceCoordinator.BulkSnapshot old=f.snapshot(null);SourceVersionRecord outdated=old.versions.get(0);SourceItem item=f.book.sourceItem(source.id,outdated.itemKey);
            SourceVersionRecord latest=SourceCoordinator.version(source,item,new ExtractionResult("Новая полная версия. Второе предложение.","Заголовок","Образец","full","","fixture","fixture-v1"));f.cache.put(latest);f.book.saveSourceVersion(latest.withPayload(null));SourceCoordinator.EnqueueProgress oldResult=f.enqueue(old,true);assertEquals(1,oldResult.added);assertNull(f.book.material(source.stationId,outdated.articleId));
            SourceCoordinator.BulkSnapshot fresh=f.snapshot(null);assertEquals(1,fresh.fullCount);assertEquals(1,f.enqueue(fresh,false).added);NoteBook.Material chosen=f.book.material(source.stationId,latest.articleId);assertEquals(latest.recordKey,chosen.sourceVersionKey);
            SourceVersionRecord newer=SourceCoordinator.version(source,item,new ExtractionResult("Ещё один новый текст.","Заголовок","Образец","full","","fixture","fixture-v1"));f.cache.put(newer);f.book.saveSourceVersion(newer.withPayload(null));SourceCoordinator.BulkSnapshot conflicting=f.snapshot(null);assertTrue(conflicting.versions.isEmpty());assertEquals(2,conflicting.downloaded.size());assertEquals(0,f.enqueue(conflicting,true).added);assertEquals(chosen.sequence,f.book.material(source.stationId,chosen.articleId).sequence);assertEquals(latest.spokenText,f.book.material(source.stationId,chosen.articleId).text);
        }
    }
    @Test public void capacityProjectionAndUncertainSaveStopWithHonestPartialCounts()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRecord source=f.connect("example");f.download(Collections.singleton(source.id),5);SourceCoordinator.BulkSnapshot snapshot=f.snapshot(null);f.port.queueLimit=2;SourceCoordinator.EnqueueProgress result=f.enqueue(snapshot,true);assertEquals(2,result.added);assertEquals(1,result.errors);assertEquals(2,result.remaining);assertNotNull(result.stopFailure);assertEquals(2,f.book.materials().size());assertFalse(result.uncertain);
            f.port.queueLimit=100;assertEquals(3,f.enqueue(f.snapshot(null),true).added);assertEquals(5,f.book.materials().size());
        }
        try(Fixture f=new Fixture()){
            SourceRecord source=f.connect("example");f.download(Collections.singleton(source.id),5);f.port.failAppend=true;SourceCoordinator.EnqueueProgress result=f.enqueue(f.snapshot(null),true);assertEquals(1,result.added);assertEquals(1,f.book.materials().size());assertNotNull(result.stopFailure);assertEquals(0,f.port.appends);
        }
        try(Fixture f=new Fixture()){
            SourceRecord source=f.connect("example");f.download(Collections.singleton(source.id),5);SourceCoordinator.BulkSnapshot snapshot=f.snapshot(null);f.store.unknown=true;SourceCoordinator.EnqueueProgress result=f.enqueue(snapshot,true);assertEquals(0,result.added);assertEquals(1,result.errors);assertTrue(result.uncertain);assertTrue(f.book.storageUncertain());assertNotNull(result.stopFailure);
        }
        try(Fixture f=new Fixture()){
            SourceRecord source=f.connect("example");f.store.unknown=true;SourceCoordinator.DownloadProgress result=f.download(Collections.singleton(source.id),5);assertEquals(0,result.saved);assertEquals(1,result.failed);assertNotNull(result.stopFailure);assertTrue(f.book.storageUncertain());assertThrows(IllegalStateException.class,()->f.coordinator.prepareBulkSnapshot(null,x->fail("Unknown authority cannot provide a verified snapshot")));
        }
    }
}

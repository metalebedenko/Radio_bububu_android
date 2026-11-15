package ru.bububu.radio.sources;

import okhttp3.Headers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.bububu.radio.core.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Production reader/cache/owner publication bridge; transport counts are per concrete action URL. */
public class SourceActionCooldownTest {
    private static final String FEED="https://feed-action.test/rss",ARTICLE="https://www.interfax.ru/world/123";
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final class Clock implements SourceClock {
        volatile long now=1000;public long monotonicMillis(){return now;}public long wallMillis(){return 1_800_000_000_000L+now;}
    }
    private final BlockingQueue<Runnable> owner=new LinkedBlockingQueue<>();
    private final Clock clock=new Clock();
    private final class Storage implements NoteBook.MigrationStorage {
        byte[] bytes;final MigrationCapsuleStore capsules;
        Storage()throws Exception{capsules=new MigrationCapsuleStore(temporary.newFolder());}
        public byte[] load(){return bytes==null?null:bytes.clone();}public void save(byte[] next){bytes=next.clone();}
        public MigrationStamp prepareMigration(byte[] baseline,int target){return capsules.prepare(baseline,target,new byte[][]{bytes,null,null});}
        public void verifyMigrationStamp(MigrationStamp stamp){capsules.verify(stamp);}
    }
    private final class Port implements SourceCoordinator.Port {
        int appends,changes;public void dispatch(Runnable action){owner.add(action);}public void requireMutable(){}public void requireStorage(long bytes){}
        public void requireQueue(SourceRecord source,Article article){}public void appendStation(SourceRecord source){}public void append(NoteBook.Material material){appends++;}
        public PlayerController.SavedProgress persisted(NoteBook.Material material){return material.selectionInitialProgress;}
        public void pauseIfSelected(NoteBook.Material material){}public void remove(NoteBook.Material material){}public void projectRead(SourceRecord source,SourceItem item){}
        public void captureHistory(){}public void changed(){changes++;}
    }
    private final class Transport implements SourceTransport {
        final Map<String,SourceFailureInfo> paused=new ConcurrentHashMap<>();final Map<String,AtomicReference<Integer>> requests=new ConcurrentHashMap<>();
        final Map<String,String> bodies=new ConcurrentHashMap<>();
        public SourceFailureInfo cooldown(String url){SourceFailureInfo failure=paused.get(url);return failure!=null&&failure.retryBlocked(clock)?failure:null;}
        int count(String url){AtomicReference<Integer> value=requests.get(url);return value==null?0:value.get();}
        int total(){return requests.values().stream().mapToInt(AtomicReference::get).sum();}
        public Response fetch(String url,Kind kind,Validators validators,RequestScope scope)throws SourceFailure {
            requests.computeIfAbsent(url,key->new AtomicReference<>(0)).updateAndGet(value->value+1);
            if(cooldown(url)!=null)throw new SourceFailure("TEST_POINTLESS_REQUEST");
            if(kind==Kind.XML)return new Response(new byte[0],url,304,null,null,"own-etag",null);
            String body="<article itemprop='articleBody'><h1 itemprop='headline'>Own headline</h1><p>"+bodies.getOrDefault(url,"Own fetched text.")+"</p></article>";
            return new Response(body.getBytes(StandardCharsets.UTF_8),url,200,"text/html","UTF-8",null,null);
        }
        void pause(String url,String seconds){
            RetryAfterParser.Result retry=RetryAfterParser.parse(List.of(seconds),clock.wallMillis(),clock.monotonicMillis());
            paused.put(url,SourceFailureInfo.from(new HttpStatusFailure(503,new Headers.Builder().add("Retry-After",seconds).build(),java.net.URI.create(url).getHost(),new RequestScope(1,1,clock,90_000),retry),clock));
        }
    }
    private Storage storage;private NoteBook book;private SourceBodyCache cache;private File cacheDirectory;private Port port;private Transport transport;private SourceCoordinator coordinator;
    @Before public void create()throws Exception {
        storage=new Storage();book=new NoteBook(storage);cacheDirectory=temporary.newFolder();cache=new SourceBodyCache(cacheDirectory);port=new Port();transport=new Transport();
        coordinator=new SourceCoordinator(book,transport,cache,port,clock);
    }
    @After public void close(){coordinator.close();}
    private SourceRecord source(String feed,String name){return SourceRecord.create("rss",feed,name,feed,"feed-generic-v1",false);}
    private SourceItem item(SourceRecord source,String url){return SourceItem.create(source.id,"own-item","own-id",url,"Own headline",-1,-1,1);}
    private SourceVersionRecord version(SourceRecord source,SourceItem item,String body)throws Exception {
        return SourceCoordinator.version(source,item,new ExtractionResult(body,"Own headline","Own headline","full","","article_body","interfax-v1"));
    }
    private <T> SourceCoordinator.Result<T> await(AtomicReference<SourceCoordinator.Result<T>> result)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(result.get()==null&&System.nanoTime()<end){Runnable task=owner.poll(100,TimeUnit.MILLISECONDS);if(task!=null)task.run();}
        assertNotNull("Owner callback required",result.get());return result.get();
    }
    private SourceCoordinator.Result<SourceVersionRecord> load(SourceRecord source,SourceItem item)throws Exception {
        AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> result=new AtomicReference<>();coordinator.loadText(source.id,item.itemKey,result::set);return await(result);
    }
    @Test public void missingReaderUsesDifferentArticleHostCooldownAndPreservesExactSnapshotWithoutFetching()throws Exception {
        SourceRecord source=source(FEED,"Own source");SourceItem item=item(source,ARTICLE);book.commitSource(source,List.of(item));transport.pause(ARTICLE,"5");
        assertNull(coordinator.refreshFailure(source));assertNull(coordinator.sourceFailure(source));assertNull(coordinator.cooldown(FEED,false));
        SourceFailureInfo blocked=coordinator.readerFailure(source.id,item.itemKey);assertNotNull(blocked);assertEquals(503,blocked.status);assertTrue(blocked.retryBlocked(clock));
        byte[] before=storage.bytes.clone();SourceCoordinator.Result<SourceVersionRecord> refused=load(source,item);assertFalse(refused.successful());assertEquals(503,refused.failure.status);assertTrue(refused.failure.retryBlocked(clock));
        assertEquals(0,transport.total());assertArrayEquals(before,storage.bytes);assertFalse(book.sourceItem(source.id,item.itemKey).read);assertFalse(book.sourceItem(source.id,item.itemKey).everListened);
        clock.now+=5000;assertNull(coordinator.readerFailure(source.id,item.itemKey));assertEquals(0,transport.total());
        SourceCoordinator.Result<SourceVersionRecord> manual=load(source,item);assertTrue(manual.successful());assertEquals("Own fetched text.",manual.value.body());assertEquals(1,transport.count(ARTICLE));assertEquals(0,transport.count(FEED));
    }
    @Test public void cachedAndPinnedCurrentTextRemainReadableAndExplicitlyEnqueueableWhileBothHostsPause()throws Exception {
        SourceRecord source=source(FEED,"Own source");SourceItem item=item(source,ARTICLE);SourceVersionRecord version=version(source,item,"Own immutable saved text. Second sentence.");
        book.commitSource(source,List.of(item),List.of(version.withPayload(null)));cache.put(version);transport.pause(FEED,"3600");transport.pause(ARTICLE,"3600");
        assertNotNull(coordinator.refreshFailure(source));assertNull(coordinator.readerFailure(source.id,item.itemKey));
        SourceCoordinator.Result<SourceVersionRecord> loaded=load(source,item);assertTrue(loaded.successful());assertEquals(version.spokenText,loaded.value.spokenText);assertEquals(version.versionKey,loaded.value.versionKey);assertEquals(version.url,loaded.value.url);assertEquals(0,transport.total());
        assertTrue(coordinator.enqueue(loaded.value,false));assertEquals(version.spokenText,book.materials().get(0).text);assertEquals(1,port.appends);
        Files.delete(new File(cacheDirectory,SourceLimits.sha256(version.recordKey)+".cas").toPath());
        assertNull(coordinator.readerFailure(source.id,item.itemKey));assertEquals(version.spokenText,load(source,item).value.spokenText);assertEquals(0,transport.total());
    }
    @Test public void staleSavedPartialRemainsExactWhileCurrentNetworkMissIsBlocked()throws Exception {
        SourceRecord source=source(FEED,"Own source");SourceItem item=item(source,ARTICLE);SourceVersionRecord old=version(source,item,"Own original sentence. Own second sentence.");
        book.commitSource(source,List.of(item),List.of(old.withPayload(null)));cache.put(old);coordinator.enqueue(old,false);
        PlayerController.SavedProgress partial=new PlayerController.SavedProgress(source.stationId,item.articleId,old.versionKey,1,false,List.of(0),true,true);
        book.removeSourceMaterial(source.stationId,item.articleId,partial);
        SourceVersionRecord current=version(source,item,"Own changed current sentence. Current second sentence.");book.saveSourceVersion(current.withPayload(null));transport.pause(ARTICLE,"3600");
        assertEquals(current.recordKey,book.sourceItem(source.id,item.itemKey).latestVersionKey);assertNotNull(coordinator.readerFailure(source.id,item.itemKey));
        byte[] before=storage.bytes.clone();assertFalse(load(source,item).successful());assertArrayEquals(before,storage.bytes);
        AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> result=new AtomicReference<>();coordinator.savedText(old.recordKey,result::set);SourceCoordinator.Result<SourceVersionRecord> saved=await(result);
        assertTrue(saved.successful());assertEquals(old.spokenText,saved.value.spokenText);assertEquals(old.recordKey,saved.value.recordKey);assertEquals(partial.sentenceIndex,book.sourceHistory(old.recordKey).sentenceIndex);assertEquals(partial.completedSentences,book.sourceHistory(old.recordKey).completedSentences);
        assertArrayEquals(before,storage.bytes);assertEquals(0,transport.total());assertTrue(coordinator.enqueue(saved.value,false));assertEquals(old.spokenText,book.materials().get(0).text);assertEquals(1,book.materials().get(0).selectionInitialProgress.sentenceIndex);
    }
    @Test public void corruptLocalCandidateIsAnExplicitLocalFailureAndNeverTurnsIntoNetworkRetry()throws Exception {
        SourceRecord source=source(FEED,"Own source");SourceItem item=item(source,ARTICLE);SourceVersionRecord version=version(source,item,"Own text.");book.commitSource(source,List.of(item),List.of(version.withPayload(null)));
        Files.write(new File(cacheDirectory,SourceLimits.sha256(version.recordKey)+".cas").toPath(),new byte[]{1,2,3,4});transport.pause(ARTICLE,"3600");
        assertNull("Existing local candidate keeps reader available for validation",coordinator.readerFailure(source.id,item.itemKey));byte[] before=storage.bytes.clone();
        assertFalse(load(source,item).successful());assertEquals(0,transport.total());assertArrayEquals(before,storage.bytes);
    }
    @Test public void mixedBulkSkipsKnownBlockedActionContinuesAllowedAndKeepsSuccessfulData()throws Exception {
        SourceRecord blocked=source(FEED,"Own blocked"),allowed=source("https://allowed-action.test/rss","Own allowed");SourceItem blockedItem=item(blocked,ARTICLE);SourceVersionRecord prior=version(blocked,blockedItem,"Own retained version.");
        book.commitSource(blocked,List.of(blockedItem),List.of(prior.withPayload(null)));book.commitSource(allowed,List.of());
        // Successful validators make the 304 fixture legitimate and avoid Android XML stubs on the JVM.
        book.updateSource(blocked.withState(true,"own-etag",null,1,null));book.updateSource(allowed.withState(true,"own-etag",null,1,null));transport.pause(FEED,"5");
        Map<String,SourceCoordinator.Result<SourceRecord>> results=new LinkedHashMap<>();coordinator.refreshAll(results::put);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(results.size()<2&&System.nanoTime()<end){Runnable task=owner.poll(100,TimeUnit.MILLISECONDS);if(task!=null)task.run();}
        assertEquals(2,results.size());assertFalse(results.get(blocked.id).successful());assertEquals(503,results.get(blocked.id).failure.status);assertTrue(results.get(blocked.id).failure.retryBlocked(clock));assertTrue(results.get(allowed.id).successful());
        assertEquals(0,transport.count(FEED));assertEquals(1,transport.count(allowed.url));assertEquals(prior.recordKey,book.sourceItem(blocked.id,blockedItem.itemKey).latestVersionKey);assertEquals(prior.bodyHash,book.sourceVersion(prior.recordKey).bodyHash);assertEquals("own-etag",book.source(allowed.id).etag);
        coordinator.cancelUi();coordinator.close();coordinator=new SourceCoordinator(book,transport,cache,port,clock);assertNotNull(coordinator.refreshFailure(book.source(blocked.id)));
        AtomicReference<SourceCoordinator.Result<SourceRecord>> repeated=new AtomicReference<>();coordinator.refresh(blocked.id,repeated::set);assertFalse(await(repeated).successful());assertEquals(0,transport.count(FEED));
        clock.now+=5000;assertNull(coordinator.refreshFailure(book.source(blocked.id)));assertEquals(1,transport.total());
        AtomicReference<SourceCoordinator.Result<SourceRecord>> manual=new AtomicReference<>();coordinator.refresh(blocked.id,manual::set);assertTrue(await(manual).successful());assertEquals(1,transport.count(FEED));
    }
    @Test public void currentReaderDoesNotPublishStaleCachedPayloadAndCurrentActionUsesItsOwnAvailability()throws Exception {
        SourceRecord source=source(FEED,"Own source");SourceItem item=item(source,ARTICLE);SourceVersionRecord old=version(source,item,"Own old text.");book.commitSource(source,List.of(item),List.of(old.withPayload(null)));cache.put(old);
        AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> result=new AtomicReference<>();coordinator.loadText(source.id,item.itemKey,result::set);Runnable late=owner.poll(2,TimeUnit.SECONDS);assertNotNull(late);
        SourceVersionRecord current=version(source,item,"Own current replacement.");book.saveSourceVersion(current.withPayload(null));transport.pause(ARTICLE,"3600");byte[] before=storage.bytes.clone();late.run();
        assertNotNull(result.get());assertEquals("STALE_ITEM_RETRY",result.get().error);assertArrayEquals(before,storage.bytes);assertNotNull(coordinator.readerFailure(source.id,item.itemKey));assertEquals(0,transport.total());
        cache.put(current);assertNull(coordinator.readerFailure(source.id,item.itemKey));SourceCoordinator.Result<SourceVersionRecord> reader=load(source,item);assertTrue(reader.successful());assertEquals(current.spokenText,reader.value.spokenText);assertEquals(current.recordKey,reader.value.recordKey);assertEquals(0,transport.total());
    }
    @Test public void freshReaderCancelsOldCompletedWorkerAfterUrlChangeEvenWhenBothLatestVersionsAreNull()throws Exception {
        String replacementUrl="https://www.interfax.ru/world/456";SourceRecord source=source(FEED,"Own source");SourceItem original=item(source,ARTICLE);book.commitSource(source,List.of(original));
        transport.bodies.put(ARTICLE,"Own old response.");transport.bodies.put(replacementUrl,"Own fresh response.");
        AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> stale=new AtomicReference<>();coordinator.loadText(source.id,original.itemKey,stale::set);Runnable oldOwner=owner.poll(2,TimeUnit.SECONDS);assertNotNull(oldOwner);
        SourceItem replacement=SourceItem.create(source.id,original.itemKey,original.providerId,replacementUrl,original.title,-1,-1,1);book.mergeSourceItems(source.id,List.of(replacement),List.of(),null,null,1,null);assertNull(book.sourceItem(source.id,original.itemKey).latestVersionKey);
        AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> current=new AtomicReference<>();coordinator.loadText(source.id,original.itemKey,current::set);SourceCoordinator.Result<SourceVersionRecord> fresh=await(current);assertTrue(fresh.successful());
        assertEquals("Own fresh response.",fresh.value.body());assertEquals(replacementUrl,fresh.value.url);byte[] published=storage.bytes.clone();oldOwner.run();
        assertNull("The stable item operation cancels the old owner delivery",stale.get());assertArrayEquals(published,storage.bytes);assertEquals(fresh.value.recordKey,book.sourceItem(source.id,original.itemKey).latestVersionKey);
        assertEquals("Own fresh response.",cache.get(book.sourceVersion(fresh.value.recordKey)).body());assertEquals(1,Objects.requireNonNull(cacheDirectory.listFiles()).length);assertEquals(1,transport.count(ARTICLE));assertEquals(1,transport.count(replacementUrl));
    }
    @Test public void urlChangeWithoutFreshReaderRejectsCompletedOldOwnerBeforeBookOrCachePublication()throws Exception {
        String replacementUrl="https://www.interfax.ru/world/456";SourceRecord source=source(FEED,"Own source");SourceItem original=item(source,ARTICLE);book.commitSource(source,List.of(original));
        AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> stale=new AtomicReference<>();coordinator.loadText(source.id,original.itemKey,stale::set);Runnable oldOwner=owner.poll(2,TimeUnit.SECONDS);assertNotNull(oldOwner);
        SourceItem replacement=SourceItem.create(source.id,original.itemKey,original.providerId,replacementUrl,original.title,-1,-1,1);book.mergeSourceItems(source.id,List.of(replacement),List.of(),null,null,1,null);assertNull(book.sourceItem(source.id,original.itemKey).latestVersionKey);byte[] before=storage.bytes.clone();oldOwner.run();
        assertNotNull(stale.get());assertEquals("STALE_ITEM_RETRY",stale.get().error);assertArrayEquals(before,storage.bytes);assertNull(book.sourceItem(source.id,original.itemKey).latestVersionKey);assertEquals(replacementUrl,book.sourceItem(source.id,original.itemKey).url);
        assertEquals(0,Objects.requireNonNull(cacheDirectory.listFiles()).length);assertEquals(1,transport.count(ARTICLE));assertEquals(0,transport.count(replacementUrl));assertEquals(0,transport.count(FEED));assertEquals(0,port.appends);
    }
}

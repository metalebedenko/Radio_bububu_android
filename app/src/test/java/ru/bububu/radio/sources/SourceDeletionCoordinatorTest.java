package ru.bububu.radio.sources;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import ru.bububu.radio.core.*;

/** Production coordinator workers/parser/CAS/Book with owner delivery deliberately delayed. */
public final class SourceDeletionCoordinatorTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final class Clock implements SourceClock {
        public long monotonicMillis(){return 1;}
        public long wallMillis(){return 1_700_000_000_001L;}
    }
    private final class Storage implements NoteBook.MigrationStorage {
        byte[] bytes;int saves;final File directory;final MigrationCapsuleStore capsules;
        Storage()throws Exception{directory=temporary.newFolder();capsules=new MigrationCapsuleStore(directory);}
        public byte[] load(){return bytes==null?null:bytes.clone();}
        public void save(byte[] value){saves++;bytes=value.clone();}
        public MigrationStamp prepareMigration(byte[] baseline,int target){return capsules.prepare(baseline,target,new byte[][]{bytes,null,null});}
        public void verifyMigrationStamp(MigrationStamp stamp){capsules.verify(stamp);}
        public UpgradeRollbackRef prepareSchema3Upgrade(byte[] baseline,int target){return capsules.prepareSchema3Upgrade(baseline,target,new byte[][]{bytes,null,null});}
        public void verifyUpgradeRollback(UpgradeRollbackRef ref){capsules.verifyUpgradeRollback(ref);}
        public void verifyUpgradeRollback(UpgradeRollbackRef ref,MigrationStamp origin){capsules.verifyUpgradeRollback(ref,origin);}
    }
    private static final class Transport implements SourceTransport {
        volatile boolean fail;volatile String text="Первый вымышленный пост. Второе предложение.",etag;
        final AtomicInteger requests=new AtomicInteger();
        public Response fetch(String url,Kind kind,Validators validators,RequestScope scope)throws SourceFailure{
            requests.incrementAndGet();if(fail)throw new SourceFailure("HTTP_503");
            String username=url.substring(url.lastIndexOf('/')+1);
            String html="<div class='tgme_channel_info_header_username'>@"+username+"</div><div class='tgme_channel_info_header_title'>Пример</div><div class='tgme_widget_message' data-post='"+username+"/12'><div class='tgme_widget_message_text js-message_text'>"+text+"</div><a class='tgme_widget_message_date'><time datetime='2026-10-12T12:00:00Z'></time></a></div>";
            return new Response(html.getBytes(java.nio.charset.StandardCharsets.UTF_8),"https://t.me/s/"+username,200,"text/html","UTF-8",etag,null);
        }
    }
    private final class Fixture implements AutoCloseable {
        final BlockingQueue<Runnable> owner=new LinkedBlockingQueue<>();
        final Storage storage=new Storage();final NoteBook book=new NoteBook(storage);
        final Transport transport=new Transport();final SourceBodyCache cache=new SourceBodyCache(temporary.newFolder());
        final Port port=new Port();final SourceCoordinator coordinator=new SourceCoordinator(book,transport,cache,port,new Clock());
        Fixture()throws Exception{}
        final class Port implements SourceCoordinator.Port {
            int checkpoints,projections,cleanups,appends,changes;boolean failCapacity,failCheckpoint,failProjection;
            PlayerController.SavedState state=new PlayerController.SavedState(null,Collections.emptyMap(),Collections.emptyMap());
            public void dispatch(Runnable action){owner.add(action);}
            public void requireMutable(){if(book.storageUncertain())throw new IllegalStateException("Unknown Book authority");}
            public void requireStorage(long peak){if(failCapacity)throw new IllegalStateException("Capacity preflight");}
            public void requireQueue(SourceRecord source,Article article){}
            public void appendStation(SourceRecord source){}
            public void append(NoteBook.Material material){appends++;Map<String,PlayerController.SavedProgress> progress=new LinkedHashMap<>(state.progress);progress.put(PlayerController.progressKey(material.stationId,material.articleId,material.version),material.selectionInitialProgress);state=new PlayerController.SavedState(material.stationId,Collections.singletonMap(material.stationId,material.articleId),progress);}
            public PlayerController.SavedProgress persisted(NoteBook.Material material){return state.progress.get(PlayerController.progressKey(material.stationId,material.articleId,material.version));}
            public void pauseIfSelected(NoteBook.Material material){}
            public void remove(NoteBook.Material material){}
            public void projectRead(SourceRecord source,SourceItem item){}
            public void captureHistory(){}
            public void changed(){changes++;}
            public void checkpointDeletion(String station,String article){checkpoints++;if(failCheckpoint)throw new IllegalStateException("Checkpoint refused");}
            public PlayerController.SavedState persistedState(){return state;}
            public void projectDeletion(){projections++;if(failProjection)throw new IllegalStateException("Before radio apply");state=book.reconcileState(state);}
            // Keep real CAS files to prove ordinary access is blocked even before physical cleanup.
            public void cleanDeletedBodies(List<SourceVersionRecord> candidates){cleanups++;}
        }
        <T> T await(AtomicReference<SourceCoordinator.Result<T>> result)throws Exception{
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(result.get()==null&&System.nanoTime()<end){Runnable task=owner.poll(100,TimeUnit.MILLISECONDS);if(task!=null)task.run();}
            assertNotNull("Owner result",result.get());assertNull(result.get().error);return result.get().value;
        }
        Runnable queued()throws Exception{Runnable action=owner.poll(3,TimeUnit.SECONDS);assertNotNull("Worker reached owner queue",action);return action;}
        SourceCoordinator.Preview preview()throws Exception{AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> result=new AtomicReference<>();coordinator.preview("@example",true,null,result::set);return await(result);}
        SourceRecord connect()throws Exception{return coordinator.connect(preview(),"Моя станция");}
        SourceVersionRecord load(SourceRecord source)throws Exception{AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> result=new AtomicReference<>();coordinator.loadText(source.id,book.sourceItems(source.id).get(0).itemKey,result::set);return await(result);}
        public void close(){coordinator.close();}
    }
    @Test public void queuedRefreshSuccessAndErrorCannotPublishAfterSourceOrItemDelete()throws Exception{
        for(boolean sourceDelete:new boolean[]{true,false})for(boolean error:new boolean[]{false,true})try(Fixture f=new Fixture()){
            SourceRecord source=f.connect();SourceItem item=f.book.sourceItems(source.id).get(0);String originalEtag=source.etag;long success=source.lastSuccess;
            f.transport.text="Поздний изменённый пост.";f.transport.etag="late-etag";f.transport.fail=error;
            AtomicReference<SourceCoordinator.Result<SourceRecord>> result=new AtomicReference<>();f.coordinator.refresh(source.id,result::set);Runnable delivery=f.queued();
            if(sourceDelete)assertTrue(f.coordinator.deleteSource(source.id));else assertTrue(f.coordinator.deleteItem(source.id,item.itemKey));
            byte[] committed=f.storage.load();long sequence=f.book.sequence();delivery.run();
            assertNull(result.get());assertArrayEquals(committed,f.storage.load());assertEquals(sequence,f.book.sequence());assertEquals(originalEtag,f.book.source(source.id).etag);assertEquals(success,f.book.source(source.id).lastSuccess);assertNull(f.book.source(source.id).lastError);assertTrue(f.book.sourceItems(source.id).isEmpty());assertTrue(f.book.materials().isEmpty());
            assertEquals(3,f.book.schema());assertEquals(sourceDelete,f.book.isSourceRetired(source.id));if(!sourceDelete)assertTrue(f.book.isItemDeleted(source.id,item.itemKey));
        }
    }
    @Test public void queuedSavedCasWorkerCannotDeliverDeletedPayloadBeforeCleanup()throws Exception{
        for(boolean sourceDelete:new boolean[]{true,false})try(Fixture f=new Fixture()){
            SourceRecord source=f.connect();SourceVersionRecord loaded=f.load(source);assertNull(f.book.sourceVersion(loaded.recordKey).spokenText);assertNotNull(f.cache.get(loaded.withPayload(null)));
            AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> result=new AtomicReference<>();f.coordinator.savedText(loaded.recordKey,result::set);Runnable delivery=f.queued();int requests=f.transport.requests.get();
            if(sourceDelete)f.coordinator.deleteSource(source.id);else f.coordinator.deleteItem(source.id,loaded.itemKey);
            byte[] committed=f.storage.load();delivery.run();assertNull(result.get());assertArrayEquals(committed,f.storage.load());assertEquals(requests,f.transport.requests.get());assertNotNull("The actual CAS still exists",f.cache.get(loaded.withPayload(null)));assertThrows(IllegalArgumentException.class,()->f.coordinator.savedText(loaded.recordKey,x->fail("No deleted standalone reader")));
        }
    }
    @Test public void changedProviderWithKnownCanonicalUrlCannotBypassItemTombstone()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRecord source=f.connect();SourceItem original=f.book.sourceItems(source.id).get(0);
            // A previously valid header may have a different opaque ID while its URL remains known.
            SourceItem oldIdentity=new SourceItem(original.sourceId,original.itemKey,original.articleId,"previous-provider-ID",original.url,original.title,Collections.emptyList(),original.publishedAt,original.updatedAt,original.firstSeenAt,original.latestVersionKey,false,false,null,original.publishedRaw,original.updatedRaw,original.statusHint,original.reasonHint,original.methodHint);
            f.book.mergeSourceItems(source.id,Collections.singletonList(oldIdentity),Collections.emptyList(),null,null,source.lastSuccess,null);f.coordinator.deleteItem(source.id,oldIdentity.itemKey);
            f.transport.text="Изменённый текст с providerID12 и прежним URL.";AtomicReference<SourceCoordinator.Result<SourceRecord>> result=new AtomicReference<>();f.coordinator.refresh(source.id,result::set);f.await(result);
            assertTrue(f.book.sourceItems(source.id).isEmpty());assertTrue(f.book.materials().isEmpty());assertTrue(f.book.isItemDeleted(source.id,oldIdentity.itemKey));assertEquals(1,f.book.sourceIdentityItems(source.id).size());assertNull(f.book.provenanceSourceItem(source.id,oldIdentity.itemKey).latestVersionKey);assertEquals(3,new NoteBook(f.storage).schema());
        }
    }
    @Test public void stalePreviewRefusesReconnectAndFreshConfirmationCreatesNewEmptyIncarnation()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRecord old=f.connect();SourceVersionRecord loaded=f.load(old);f.coordinator.enqueue(loaded,true);SourceCoordinator.Preview stale=f.preview();f.coordinator.deleteSource(old.id);
            assertThrows(IllegalStateException.class,()->f.coordinator.connect(stale,"Поздняя форма",true));SourceCoordinator.Preview fresh=f.preview();assertTrue(f.coordinator.requiresReconnect(fresh));byte[] deleted=f.storage.load();assertThrows(IllegalStateException.class,()->f.coordinator.connect(fresh,"Без подтверждения"));assertArrayEquals(deleted,f.storage.load());
            SourceRecord next=f.coordinator.connect(fresh,"Новая станция",true);assertNotEquals(old.id,next.id);assertNotEquals(old.stationId,next.stationId);assertTrue(f.book.isSourceRetired(old.id));assertEquals(1,f.book.sources().size());assertEquals(2,f.book.allSources().size());assertTrue(f.book.materials().isEmpty());assertNull(f.book.sourceHistory(loaded.recordKey));SourceItem header=f.book.sourceItems(next.id).get(0);assertFalse(header.read);assertFalse(header.everListened);assertNull(header.lastViewedVersion);assertNotEquals(loaded.articleId,header.articleId);assertNull(f.book.sourceVersion(header.latestVersionKey).spokenText);
        }
    }
    @Test public void catalogueReconnectRetainsExactRetiredContextAndFreshIdentityAcrossReload()throws Exception{
        try(Fixture f=new Fixture()){
            SourceCatalog.Entry entry=new SourceCatalog.Entry("Вымышленный каталог","@example",true,"Доступный текст");
            AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> result=new AtomicReference<>();
            f.coordinator.preview(entry.input,true,entry.name,result::set);SourceRecord old=f.coordinator.connect(f.await(result),entry.name);assertTrue(old.catalog);
            SourceVersionRecord exact=f.load(old);assertEquals("unknown",exact.completeness);f.coordinator.enqueue(exact,true);
            NoteBook.Note note=f.book.beginRecording("Своя заметка",old.stationId,exact.articleId,exact.versionKey,1);f.book.edit(note.id,note.title,"Моя редакция.");
            f.coordinator.deleteSource(old.id);byte[] deleted=f.storage.load();
            result.set(null);f.coordinator.preview(entry.input,true,entry.name,result::set);SourceCoordinator.Preview fresh=f.await(result);
            assertTrue(f.coordinator.requiresReconnect(fresh));assertThrows(IllegalStateException.class,()->f.coordinator.connect(fresh,entry.name));assertArrayEquals(deleted,f.storage.load());
            SourceRecord next=f.coordinator.connect(fresh,entry.name,true);assertTrue(next.catalog);assertNotEquals(old.id,next.id);assertTrue(f.book.materials().isEmpty());
            NoteBook restored=new NoteBook(f.storage);assertSame(next,SourceCatalog.connected(entry,f.book.sources()));assertTrue(restored.isSourceRetired(old.id));assertEquals(next.id,restored.sources().get(0).id);
            assertEquals("Моя редакция.",restored.find(note.id).text);assertEquals(exact.recordKey,restored.find(note.id).linkedSourceVersionKey);assertEquals(exact.spokenText,restored.sourceVersion(exact.recordKey).spokenText);assertTrue(restored.materials().isEmpty());
            SourceItem header=restored.sourceItems(next.id).get(0);assertNotEquals(exact.articleId,header.articleId);assertEquals("unknown",restored.sourceVersion(header.latestVersionKey).completeness);
            result.set(null);f.coordinator.preview(entry.input,true,entry.name,result::set);SourceRecord duplicate=f.coordinator.connect(f.await(result),"Не переименовывать",true);assertEquals(next.id,duplicate.id);assertEquals(1,f.book.sources().size());assertEquals(entry.name,duplicate.name);
        }
    }
    @Test public void provenDeleteProjectionFailureRetriesWithoutSecondCommitOrSequence()throws Exception{
        for(boolean sourceDelete:new boolean[]{true,false})try(Fixture f=new Fixture()){
            SourceRecord source=f.connect();SourceVersionRecord v=f.load(source);f.coordinator.enqueue(v,true);long before=f.book.sequence();int saves=f.storage.saves;f.port.failProjection=true;
            assertThrows(SourceCoordinator.ProjectionPendingException.class,()->{if(sourceDelete)f.coordinator.deleteSource(source.id);else f.coordinator.deleteItem(source.id,v.itemKey);});assertEquals(3,f.book.schema());assertEquals(before+1,f.book.sequence());assertEquals(saves+1,f.storage.saves);assertTrue(f.book.materials().isEmpty());assertEquals(0,f.port.cleanups);
            byte[] committed=f.storage.load();f.port.failProjection=false;assertFalse(sourceDelete?f.coordinator.deleteSource(source.id):f.coordinator.deleteItem(source.id,v.itemKey));assertArrayEquals(committed,f.storage.load());assertEquals(before+1,f.book.sequence());assertEquals(1,f.book.sourceGeneration(source.id));assertEquals(2,f.port.projections);assertEquals(0,f.port.cleanups);
        }
    }
    @Test public void capacityOrCheckpointFailureHasNoDeletionCapsuleOrCleanup()throws Exception{
        for(boolean sourceDelete:new boolean[]{true,false})try(Fixture f=new Fixture()){
            SourceRecord source=f.connect();SourceVersionRecord v=f.load(source);byte[] before=f.storage.load();long sequence=f.book.sequence();int capsules=new File(f.storage.directory,"notes-migration").list().length;
            f.port.failCapacity=true;assertThrows(IllegalStateException.class,()->{if(sourceDelete)f.coordinator.deleteSource(source.id);else f.coordinator.deleteItem(source.id,v.itemKey);});assertEquals(0,f.port.checkpoints);assertArrayEquals(before,f.storage.load());
            f.port.failCapacity=false;f.port.failCheckpoint=true;assertThrows(IllegalStateException.class,()->{if(sourceDelete)f.coordinator.deleteSource(source.id);else f.coordinator.deleteItem(source.id,v.itemKey);});assertEquals(1,f.port.checkpoints);assertArrayEquals(before,f.storage.load());assertEquals(sequence,f.book.sequence());assertEquals(capsules,new File(f.storage.directory,"notes-migration").list().length);assertEquals(0,f.port.cleanups);assertEquals(0,f.port.projections);assertTrue(f.book.isSourceActive(source.id));assertFalse(f.book.isItemDeleted(source.id,v.itemKey));assertNotNull(f.cache.get(v.withPayload(null)));
        }
    }
}

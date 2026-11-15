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
public final class BulkMutationCoordinatorTest {
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
        final Transport transport=new Transport();final File cacheDirectory=temporary.newFolder();final SourceBodyCache cache=new SourceBodyCache(cacheDirectory);
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
            public void checkpointMutation(List<NoteBook.Material> removed,boolean clear){checkpoints++;if(failCheckpoint)throw new IllegalStateException("Bulk checkpoint refused");}
            public void projectMutation(NoteBook.Material replacement,boolean clear){projectDeletion();}
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
    @Test public void clearPreservesBookOnlyBodyInRealCasAndOfflineReaderReadd()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRecord source=f.connect();SourceVersionRecord version=f.load(source);f.coordinator.enqueue(version,true);
            NoteBook.Note note=f.book.beginRecording("Своя заметка",null,null,null,1);f.book.finishRecording(note.id,0,false,false,null);f.book.edit(note.id,note.title,"Ручная версия.");f.book.addToAir(note.id);
            f.cache.discard(version);assertNull(f.cache.get(version.withPayload(null)));assertNotNull(f.book.sourceVersion(version.recordKey).spokenText);
            int network=f.transport.requests.get();int saves=f.storage.saves;
            assertTrue(f.coordinator.clearAir());assertEquals(saves+1,f.storage.saves);assertTrue(f.book.materials().isEmpty());
            assertNull("Actual Book selection body was pruned",f.book.sourceVersion(version.recordKey).spokenText);
            assertEquals(version.spokenText,f.cache.get(version.withPayload(null)).spokenText);
            assertEquals("Ручная версия.",f.book.find(note.id).text);assertTrue(f.book.isSourceActive(source.id));
            AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> read=new AtomicReference<>();f.coordinator.savedText(version.recordKey,read::set);
            SourceVersionRecord exact=f.await(read);assertEquals(version.spokenText,exact.spokenText);assertEquals(network,f.transport.requests.get());
            assertTrue(f.coordinator.enqueue(exact,true));assertEquals(version.spokenText,f.book.material(source.stationId,version.articleId).text);
        }
    }
    @Test public void failedNoEvictionCopyAndCapacityRefuseBeforeCheckpointOrBookWrite()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRecord source=f.connect();SourceVersionRecord version=f.load(source);f.coordinator.enqueue(version,true);f.cache.discard(version);
            byte[] old=f.storage.load();int saves=f.storage.saves;
            f.port.failCapacity=true;assertThrows(IllegalStateException.class,()->f.coordinator.clearAir());
            assertArrayEquals(old,f.storage.load());assertEquals(0,f.port.checkpoints);assertEquals(saves,f.storage.saves);
            f.port.failCapacity=false;File wrong=new File(f.cacheDirectory,SourceLimits.sha256(version.recordKey)+".cas");assertTrue(wrong.mkdir());
            assertThrows(IllegalStateException.class,()->f.coordinator.clearAir());assertTrue(wrong.isDirectory());
            assertArrayEquals(old,f.storage.load());assertEquals(0,f.port.checkpoints);assertEquals(saves,f.storage.saves);assertEquals(0,f.port.cleanups);
        }
        try(Fixture f=new Fixture()){
            SourceRecord source=f.connect();SourceVersionRecord chosen=f.load(source);f.coordinator.enqueue(chosen,true);f.cache.discard(chosen);
            f.transport.text="Другая скачанная редакция. Сохранённая фраза.";
            AtomicReference<SourceCoordinator.Result<SourceRecord>> refreshed=new AtomicReference<>();f.coordinator.refresh(source.id,refreshed::set);f.await(refreshed);
            SourceVersionRecord other=f.load(source);assertNotEquals(chosen.recordKey,other.recordKey);
            File otherFile=new File(f.cacheDirectory,SourceLimits.sha256(other.recordKey)+".cas");assertTrue(otherFile.isFile());
            File reserve=new File(f.cacheDirectory,"preserved-owner-regular-file");try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(reserve,"rw")){file.setLength(SourceLimits.MAX_CACHE_BYTES-otherFile.length());}
            byte[] before=f.storage.load();assertThrows(IllegalStateException.class,()->f.coordinator.clearAir());
            assertArrayEquals(before,f.storage.load());assertEquals(0,f.port.checkpoints);assertTrue(reserve.isFile());
            assertEquals("An existing exact download is never evicted to clear the queue",other.spokenText,f.cache.get(other.withPayload(null)).spokenText);
        }
    }
    @Test public void clearCancelsRealQueuedWorkerAndStaleBulkIntentButFreshReaddWorks()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRecord source=f.connect();SourceVersionRecord version=f.load(source);f.coordinator.enqueue(version,true);
            AtomicReference<SourceCoordinator.Result<SourceCoordinator.BulkSnapshot>> snapshot=new AtomicReference<>();f.coordinator.prepareBulkSnapshot(null,snapshot::set);SourceCoordinator.BulkSnapshot old=f.await(snapshot);
            AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> reader=new AtomicReference<>();f.coordinator.savedText(version.recordKey,reader::set);Runnable late=f.queued();
            assertTrue(f.coordinator.clearAir());byte[] cleared=f.storage.load();late.run();assertNull(reader.get());assertArrayEquals(cleared,f.storage.load());
            assertThrows(IllegalStateException.class,()->f.coordinator.enqueueBulk(old,true,x->fail("Stale captured bulk cannot repopulate queue")));
            assertTrue(f.coordinator.enqueue(version,true));assertEquals(1,f.book.materials().size());
        }
    }
    @Test public void globalDeletionInvalidatesQueuedRefreshAndFreshSameIdsNeverResurrect()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRecord source=f.connect();SourceVersionRecord version=f.load(source);f.coordinator.enqueue(version,true);
            AtomicReference<SourceCoordinator.Result<SourceRecord>> lateResult=new AtomicReference<>();f.coordinator.refresh(source.id,lateResult::set);Runnable late=f.queued();
            assertTrue(f.coordinator.deleteAllNews());byte[] deleted=f.storage.load();late.run();assertNull(lateResult.get());assertArrayEquals(deleted,f.storage.load());
            assertTrue(f.book.isSourceActive(source.id));assertTrue(f.book.isItemDeleted(source.id,version.itemKey));assertTrue(f.book.materials().isEmpty());
            AtomicReference<SourceCoordinator.Result<SourceRecord>> fresh=new AtomicReference<>();f.coordinator.refresh(source.id,fresh::set);f.await(fresh);
            assertTrue(f.book.sourceItems(source.id).isEmpty());assertThrows(IllegalArgumentException.class,()->f.coordinator.savedText(version.recordKey,x->fail("Deleted standalone access")));
        }
    }
    @Test public void allCommittedMutationsReportProjectionPendingWithoutGcThenRetryNoSecondCommit()throws Exception{
        for(String kind:new String[]{"clear","news","note"})try(Fixture f=new Fixture()){
            SourceRecord source=f.connect();SourceVersionRecord version=f.load(source);f.coordinator.enqueue(version,true);
            NoteBook.Note note=f.book.beginRecording("Своя заметка",null,null,null,1);f.book.finishRecording(note.id,0,false,false,null);f.book.edit(note.id,note.title,"Первая ручная версия.");f.book.addToAir(note.id);f.book.edit(note.id,note.title,"Вторая ручная версия.");
            long revision=f.book.find(note.id).revision;int saves=f.storage.saves;f.port.failProjection=true;
            Runnable action=()->{if(kind.equals("clear"))f.coordinator.clearAir();else if(kind.equals("news"))f.coordinator.deleteAllNews();else f.coordinator.updateNoteInAir(note.id,revision);};
            assertThrows(SourceCoordinator.ProjectionPendingException.class,action::run);assertEquals(saves+1,f.storage.saves);assertEquals(0,f.port.cleanups);
            byte[] committed=f.storage.load();f.port.failProjection=false;action.run();assertArrayEquals(committed,f.storage.load());assertEquals(saves+1,f.storage.saves);
            assertEquals("Вторая ручная версия.",f.book.find(note.id).text);
        }
    }
}

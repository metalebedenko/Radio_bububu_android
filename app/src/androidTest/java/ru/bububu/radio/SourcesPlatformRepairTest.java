package ru.bububu.radio;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.StatFs;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import ru.bububu.radio.core.*;
import ru.bububu.radio.sources.*;

/** Real private directory/provider and production storage composition. Own synthetic files only. */
@RunWith(AndroidJUnit4.class)
public final class SourcesPlatformRepairTest {
    private Context context;private File root;
    @Before public void setup()throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        root=Files.createTempDirectory(app.getCacheDir().toPath(),"sources-platform-test08-").toFile();
        File noBackup=new File(root,"no-backup"),files=new File(root,"files");assertTrue(noBackup.mkdir());assertTrue(files.mkdir());
        context=new ContextWrapper(app){@Override public File getNoBackupFilesDir(){return noBackup;}@Override public File getFilesDir(){return files;}};
    }
    @After public void cleanup()throws Exception {
        if(root!=null)try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())) {
            for(Path path:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.delete(path);
        }
    }
    @Test public void coldProductionTransportCreatesAbsentOwnedChildWithoutTestMkdir()throws Exception {
        Path absent=new File(context.getNoBackupFilesDir(),"source-network").toPath();
        assertFalse(Files.exists(absent,LinkOption.NOFOLLOW_LINKS));
        // This unique directory has no process gate; use the same public production constructor.
        try(SafeHttpTransport transport=new SafeHttpTransport(AndroidSourceDirectories.prepareNetwork(context.getNoBackupFilesDir()),new AndroidSourceClock())) {
            assertTrue(Files.isDirectory(absent,LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.isSymbolicLink(absent));
            assertNotNull(transport);
        }
    }
    @Test public void savedRetryAfterIsPreservedAcrossFreshGateAndTransportComposition()throws Exception {
        AndroidSourceClock clock=new AndroidSourceClock();File directory=AndroidSourceDirectories.prepareNetwork(context.getNoBackupFilesDir());
        HostRequestGate gate=new HostRequestGate(new HostCooldownStore(directory),clock);
        gate.observe("example.org",RetryAfterParser.parse(Collections.singletonList("600"),clock.wallMillis(),clock.monotonicMillis()));
        Path state=new File(directory,"network-cooldowns-v1.bin").toPath();byte[] before=Files.readAllBytes(state);
        File rechecked=AndroidSourceDirectories.prepareNetwork(context.getNoBackupFilesDir());
        HostRequestGate restarted=new HostRequestGate(new HostCooldownStore(rechecked),clock);
        assertTrue(restarted.remaining("example.org")>590_000);
        assertEquals("HOST_COOLDOWN",assertThrows(SourceFailure.class,()->restarted.acquire("example.org",new RequestScope(1,1,clock,90_000),Long.MAX_VALUE)).reason);
        try(SafeHttpTransport ignored=new SafeHttpTransport(rechecked,clock)) {assertArrayEquals(before,Files.readAllBytes(state));}
    }
    @Test public void networkWrongTypeSymlinkAndRealDeniedAccessNeverReplaceOrEraseEvidence()throws Exception {
        File parent=context.getNoBackupFilesDir();Path network=new File(parent,"source-network").toPath();byte[] evidence={2,4,8};Files.write(network,evidence);
        assertThrows(SourceFailure.class,()->AndroidSourceDirectories.prepareNetwork(parent));assertArrayEquals(evidence,Files.readAllBytes(network));Files.delete(network);
        File elsewhere=new File(root,"elsewhere");assertTrue(elsewhere.mkdir());Path retained=elsewhere.toPath().resolve("keep.data");Files.write(retained,evidence);Files.createSymbolicLink(network,elsewhere.toPath());
        assertThrows(SourceFailure.class,()->AndroidSourceDirectories.prepareNetwork(parent));assertTrue(Files.isSymbolicLink(network));assertArrayEquals(evidence,Files.readAllBytes(retained));Files.delete(network);
        File directory=AndroidSourceDirectories.prepareNetwork(parent);Path state=directory.toPath().resolve("network-cooldowns-v1.bin.new");Files.write(state,evidence);int mode=Os.stat(directory.getPath()).st_mode&0777;
        try {Os.chmod(directory.getPath(),0);assertNativeAccessDenied(directory);assertThrows(SourceFailure.class,()->AndroidSourceDirectories.prepareNetwork(parent));}
        finally {Os.chmod(directory.getPath(),mode);}
        assertArrayEquals(evidence,Files.readAllBytes(state));
        HostRequestGate uncertain=new HostRequestGate(new HostCooldownStore(directory),new AndroidSourceClock());
        assertEquals("COOLDOWN_STORAGE",assertThrows(SourceFailure.class,()->uncertain.acquire("example.org",new RequestScope(1,1),Long.MAX_VALUE)).reason);
        assertArrayEquals(evidence,Files.readAllBytes(state));assertFalse(new File(directory,"network-cooldowns-v1.bin").exists());
        int parentMode=Os.stat(parent.getPath()).st_mode&0777;
        try {Os.chmod(parent.getPath(),0);assertNativeAccessDenied(parent);assertThrows(SourceFailure.class,()->AndroidSourceDirectories.prepareNetwork(parent));}
        finally {Os.chmod(parent.getPath(),parentMode);}
        assertArrayEquals(evidence,Files.readAllBytes(state));
    }
    @Test public void nativeDeniedAccessPreconditionRejectsAccessibleDirectory()throws Exception {
        File accessible=context.getNoBackupFilesDir();
        assertTrue("Independently establish native allowed access",Os.access(accessible.getPath(),OsConstants.R_OK|OsConstants.W_OK|OsConstants.X_OK));
        assertThrows(AssertionError.class,()->assertNativeAccessDenied(accessible));
    }
    private static void assertNativeAccessDenied(File directory)throws ErrnoException {
        // Android access denial may return false or throw EACCES; other errno values are not this precondition.
        try {assertFalse("Establish real native access denial",Os.access(directory.getPath(),OsConstants.R_OK|OsConstants.W_OK|OsConstants.X_OK));}
        catch(ErrnoException denied) {assertEquals("Establish exact native permission denial",OsConstants.EACCES,denied.errno);}
    }
    @Test public void realCompatibleCapacityMigratesPresentBookWithOneCommitAndPausedPlayer()throws Exception { realCapacityMigration(true); }
    @Test public void realCompatibleCapacityMigratesProvedAbsentBookWithOneCommitAndPausedPlayer()throws Exception { realCapacityMigration(false); }
    private void realCapacityMigration(boolean present)throws Exception {
        AndroidNoteStorage storage=new AndroidNoteStorage(context);CountingStorage counted=new CountingStorage(storage);NoteBook book=new NoteBook(counted);
        NoteBook.Note note=null;if(present){note=book.beginRecording("Исходная",null,null,null,1);book.edit(note.id,"Сохранённая","Точный исходный текст.");}
        String audioId=present?note.id:UUID.randomUUID().toString();File original=new File(storage.directory,audioId+".wav");
        try(PcmWave.Writer wave=new PcmWave.Writer(new File(storage.directory,audioId+".wav.part"))){wave.append(new byte[32_000],0,32_000);wave.finish(original);}
        byte[] originalBytes=Files.readAllBytes(original.toPath());if(present){book.finishRecording(note.id,1000,true,false,null);book.setOriginalPosition(note.id,321);}
        byte[] baseline=storage.load();assertEquals(!present,baseline==null);assertEquals(1,book.schema());
        assertTrue("Independently establish adequate app-available space",new StatFs(storage.directory.getPath()).getAvailableBytes()>=SourceLimits.MIN_FREE_BYTES);
        assertTrue(storage.directory.getUsableSpace()>=SourceLimits.MIN_FREE_BYTES);counted.saves=0;
        PlayerController player=connectOneSyntheticSource(book,storage);
        assertEquals(1,counted.saves);assertEquals(2,book.schema());assertEquals(1,book.sources().size());assertFalse(player.snapshot().playing);
        MigrationStamp stamp=book.migrationStamp();storage.verifyMigrationStamp(stamp);assertEquals(present?SourceLimits.hash(baseline):null,stamp.baselineHash);assertEquals(present?baseline.length:-1,stamp.baselineLength);assertEquals(!present,stamp.absenceEpoch!=null);
        assertEquals(1,new File(storage.directory,"notes-migration").list().length);assertArrayEquals(originalBytes,Files.readAllBytes(original.toPath()));
        NoteBook restarted=new NoteBook(storage);assertEquals(2,restarted.schema());assertEquals(1,restarted.sources().size());assertNull(restarted.migrationWarning());assertEquals(stamp.capsuleId,restarted.migrationStamp().capsuleId);
        if(present){assertEquals("Точный исходный текст.",restarted.find(note.id).text);assertEquals(321,restarted.find(note.id).positionMs);}
        PlayerController paused=player(context);assertFalse(paused.snapshot().playing);assertEquals(1,counted.saves);
    }
    @Test public void nativeCapacityFloorBoundaryAdmitsPresentAndAbsentAndUsesExactCheckedParent()throws Exception {
        for(boolean present:new boolean[]{false,true})for(long free:new long[]{SourceLimits.MIN_FREE_BYTES,SourceLimits.MIN_FREE_BYTES+1}) {
            Context isolated=isolatedContext();final File[] queried={null};AndroidNoteStorage storage=new AndroidNoteStorage(isolated,parent->{queried[0]=parent;return free;});NoteBook book=new NoteBook(storage);
            if(present){NoteBook.Note note=book.beginRecording("H1",null,null,null,1);book.edit(note.id,"H1","PRESENT.");}
            byte[] baseline=storage.load();book.commitSource(source(),Collections.emptyList());assertEquals(storage.directory,queried[0]);assertEquals(2,book.schema());storage.verifyMigrationStamp(book.migrationStamp());
            assertEquals(present?SourceLimits.hash(baseline):null,book.migrationStamp().baselineHash);assertEquals(!present,book.migrationStamp().absenceEpoch!=null);
        }
    }
    @Test public void nativeCapacityBelowFloorZeroNegativeAndUnavailablePreserveBookCapsulesAndOriginalAndAllowV1Edit()throws Exception {
        for(boolean present:new boolean[]{false,true})for(int scenario=0;scenario<5;scenario++) {
            Context isolated=isolatedContext();final int kind=scenario;final Throwable unavailable=kind==3?new IOException("synthetic unavailable"):new SecurityException("synthetic unavailable");final int[] queries={0};
            AndroidNoteStorage storage=new AndroidNoteStorage(isolated,parent->{queries[0]++;if(kind==3)throw (IOException)unavailable;if(kind==4)throw (SecurityException)unavailable;return kind==0?SourceLimits.MIN_FREE_BYTES-1:kind==1?0:-1;});NoteBook book=new NoteBook(storage);
            NoteBook.Note note=null;if(present){note=book.beginRecording("H1",null,null,null,1);book.edit(note.id,"H1","Retained H1.");}
            byte[] baseline=storage.load();File wav=new File(storage.directory,UUID.randomUUID()+".wav");byte[] original={3,6,9};Files.write(wav.toPath(),original);
            new MigrationCapsuleStore(storage.directory).prepare(baseline,1000,new byte[][]{baseline,null,null});Map<String,String> before=inventory(storage.directory);
            IllegalStateException failure=assertThrows(IllegalStateException.class,()->book.commitSource(source(),Collections.emptyList()));
            if(kind>=3){Throwable cause=failure;while(cause!=null&&cause!=unavailable)cause=cause.getCause();assertSame(unavailable,cause);}
            assertEquals(1,queries[0]);assertEquals(before,inventory(storage.directory));assertArrayEquals(baseline,storage.load());assertArrayEquals(original,Files.readAllBytes(wav.toPath()));assertEquals(1,book.schema());assertFalse(book.storageUncertain());assertTrue(book.sources().isEmpty());
            if(note==null)note=book.beginRecording("H2",null,null,null,2);book.edit(note.id,"H2","Coherent v1 edit after refusal.");assertEquals(1,new NoteBook(storage).schema());assertEquals("Coherent v1 edit after refusal.",new NoteBook(storage).find(note.id).text);assertEquals(1,queries[0]);assertArrayEquals(original,Files.readAllBytes(wav.toPath()));
        }
    }
    private Context isolatedContext()throws Exception {
        File child=Files.createTempDirectory(root.toPath(),"capacity-").toFile(),noBackup=new File(child,"no-backup"),files=new File(child,"files");assertTrue(noBackup.mkdir());assertTrue(files.mkdir());
        return new ContextWrapper(context){@Override public File getNoBackupFilesDir(){return noBackup;}@Override public File getFilesDir(){return files;}};
    }
    private static SourceRecord source(){return SourceRecord.create("rss","https://example.org/feed","Вымышленный источник","https://example.org/feed","feed-generic-v1",false);}
    private PlayerController player(Context target) {
        return new PlayerController(LocalData.fixtures(target),new PlayerController.SpeechEngine(){public void speak(String text,String id){fail("Migration must not autoplay");}public void stop(){fail("Source connect must not revoke speech");}},(delay,task)->()->{},new LocalData(target),snapshot->{},1000);
    }
    private PlayerController connectOneSyntheticSource(NoteBook book,AndroidNoteStorage storage)throws Exception {
        PlayerController player=player(context);BlockingQueue<Runnable> owner=new LinkedBlockingQueue<>();
        SourceTransport fixture=(url,kind,validators,scope)->new SourceTransport.Response("<rss version='2.0'><channel><title>Лента</title><link>https://example.org/</link><description>Описание</description><item><guid>synthetic</guid><title>Статья</title><description>Вымышленный текст.</description></item></channel></rss>".getBytes(java.nio.charset.StandardCharsets.UTF_8),"https://example.org/feed",200,"application/rss+xml","UTF-8",null,null);
        SourceDiskBudget budget=new SourceDiskBudget(storage.directory,new File(context.getNoBackupFilesDir(),"source-cas"),new File(context.getNoBackupFilesDir(),"source-network"),context.getFilesDir());
        SourceCoordinator coordinator=new SourceCoordinator(book,fixture,new SourceBodyCache(new File(context.getNoBackupFilesDir(),"source-cas")),new SourceCoordinator.Port(){
            public void dispatch(Runnable r){owner.add(r);}public void requireMutable(){assertFalse(book.storageUncertain());}public void requireStorage(long n){budget.require(n);}public void appendStation(SourceRecord s){player.appendStation(s.stationId,s.name);}
            public void requireQueue(SourceRecord s,Article a){fail();}public void append(NoteBook.Material m){fail();}public PlayerController.SavedProgress persisted(NoteBook.Material m){throw new AssertionError();}public void pauseIfSelected(NoteBook.Material m){fail();}public void remove(NoteBook.Material m){fail();}public void projectRead(SourceRecord s,SourceItem i){fail();}public void captureHistory(){fail();}public void changed(){}
        },new AndroidSourceClock());
        try {
            AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> result=new AtomicReference<>();coordinator.preview("https://example.org/feed",false,null,result::set);
            long end=SystemClock.elapsedRealtime()+5000;while(result.get()==null&&SystemClock.elapsedRealtime()<end){Runnable r=owner.poll(100,TimeUnit.MILLISECONDS);if(r!=null)r.run();}
            assertNotNull(result.get());assertNull(result.get().error);coordinator.connect(result.get().value,"Источник");assertEquals(4,player.stations().size());assertTrue(book.materials().isEmpty());return player;
        } finally {coordinator.close();}
    }
    private static Map<String,String> inventory(File directory)throws Exception {
        Map<String,String> files=new TreeMap<>();try(java.util.stream.Stream<Path> paths=Files.walk(directory.toPath())) {
            for(Path path:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).collect(java.util.stream.Collectors.toList()))files.put(directory.toPath().relativize(path).toString(),SourceLimits.hash(Files.readAllBytes(path)));
        }return files;
    }
    private static final class CountingStorage implements NoteBook.MigrationStorage {
        final AndroidNoteStorage delegate;int saves;CountingStorage(AndroidNoteStorage delegate){this.delegate=delegate;}
        public byte[] load(){return delegate.load();}public void save(byte[] bytes){saves++;delegate.save(bytes);}
        public MigrationStamp prepareMigration(byte[] expected,int target){return delegate.prepareMigration(expected,target);}public void verifyMigrationStamp(MigrationStamp stamp){delegate.verifyMigrationStamp(stamp);}
    }
}

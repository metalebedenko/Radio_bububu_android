package ru.bububu.radio;

import android.content.Context;
import android.content.ContextWrapper;
import android.system.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import ru.bububu.radio.core.*;
import ru.bububu.radio.sources.*;

/** Real AtomicFile/Os/StatFs/JSON on isolated synthetic files. No network or personal metadata. */
@RunWith(AndroidJUnit4.class)
public class SourcesStorageAndUiTest {
    private Context context;private File root;
    @Before public void setup()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();root=Files.createTempDirectory(app.getCacheDir().toPath(),"sources-test07-").toFile();
        File noBackup=new File(root,"nobackup"),files=new File(root,"files");assertTrue(noBackup.mkdir());assertTrue(files.mkdir());
        context=new ContextWrapper(app){@Override public File getNoBackupFilesDir(){return noBackup;}@Override public File getFilesDir(){return files;}};
    }
    @After public void cleanup()throws Exception{if(root!=null)try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(root.toPath())){for(java.nio.file.Path p:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.delete(p);}}
    private SourceRecord source(){return SourceRecord.create("rss","https://example.org/feed","Вымышленный источник","https://example.org/feed","feed-generic-v1",false);}
    private SourceVersionRecord version(SourceRecord s)throws Exception{
        SourceItem item=SourceItem.create(s.id,"id:synthetic","synthetic","https://example.org/article","Синтетическая статья",-1,-1,2);
        return SourceCoordinatorForTest.version(s,item);
    }
    @Test public void populatedSchemaOneMigratesWithExactRollbackAndOriginalPreserved()throws Exception{
        AndroidNoteStorage storage=new AndroidNoteStorage(context);NoteBook book=new NoteBook(storage);
        NoteBook.Note note=book.beginRecording("Исходная заметка","A","A1","v1",10);book.edit(note.id,"Ручной заголовок","Ручной текст. Ещё предложение.");
        try(PcmWave.Writer wav=new PcmWave.Writer(new File(storage.directory,note.id+".wav.part"))){wav.append(new byte[32_000],0,32_000);wav.finish(new File(storage.directory,note.id+".wav"));}
        book.finishRecording(note.id,1000,true,false,null);book.setOriginalPosition(note.id,321);NoteBook.Material noteText=book.addToAir(note.id);byte[] original=Files.readAllBytes(new File(storage.directory,note.id+".wav").toPath());byte[] baseline=storage.load();assertEquals(1,book.schema());
        SourceRecord source=source();SourceVersionRecord v=version(source);SourceItem item=SourceItem.create(source.id,v.itemKey,"synthetic",v.url,v.displayTitle,-1,-1,2);
        book.commitSource(source,Collections.singletonList(item),Collections.singletonList(v.withPayload(null)));book.selectSourceVersion(v,true);assertEquals(2,book.schema());
        NoteBook restored=new NoteBook(storage);assertEquals(2,restored.schema());assertNull(restored.migrationWarning());assertEquals("Ручной текст. Ещё предложение.",restored.find(note.id).text);assertEquals(321,restored.find(note.id).positionMs);assertEquals(noteText.text,restored.materials().get(0).text);assertEquals(v.spokenText,restored.materials().get(1).text);
        assertArrayEquals(original,Files.readAllBytes(new File(storage.directory,note.id+".wav").toPath()));assertEquals(SourceLimits.hash(baseline),restored.migrationStamp().baselineHash);storage.verifyMigrationStamp(restored.migrationStamp());
        MigrationStamp stamp=restored.migrationStamp();File capsule=new File(new File(storage.directory,"notes-migration"),stamp.capsuleId+".bin");assertEquals(stamp.capsuleHash,SourceLimits.hash(Files.readAllBytes(capsule.toPath())));
    }
    @Test public void missingRollbackCapsuleDoesNotDowngradeAuthoritativeSchemaTwo()throws Exception{
        AndroidNoteStorage storage=new AndroidNoteStorage(context);NoteBook book=new NoteBook(storage);book.commitSource(source(),Collections.emptyList());String id=book.migrationStamp().capsuleId;Files.delete(new File(new File(storage.directory,"notes-migration"),id+".bin").toPath());
        NoteBook restored=new NoteBook(storage);assertEquals(2,restored.schema());assertNotNull(restored.migrationWarning());NoteBook.Note note=restored.beginRecording("После миграции",null,null,null,2);restored.edit(note.id,"После миграции","Текст сохраняется.");assertEquals(2,new NoteBook(storage).schema());assertEquals(1,new NoteBook(storage).sources().size());
    }
    @Test public void onlyUnfinishedRadioJsonIsReadOnlyAndNeverConsolidated()throws Exception{
        File pending=new File(context.getFilesDir(),"player-state-v1.json.new");byte[] evidence="unfinished-synthetic".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(pending.toPath(),evidence);
        LocalData store=new LocalData(context);assertThrows(IllegalStateException.class,store::load);assertTrue(store.restoreFailed());assertThrows(IllegalStateException.class,()->store.save(new PlayerController.SavedState("A",Collections.emptyMap(),Collections.emptyMap())));assertArrayEquals(evidence,Files.readAllBytes(pending.toPath()));assertFalse(new File(context.getFilesDir(),"player-state-v1.json").exists());
    }
    @Test public void realRadioJsonEaccesIsNotAbsenceAndNeitherBaseNorShadowChanges()throws Exception{
        LocalData store=new LocalData(context);store.load();store.save(new PlayerController.SavedState("A",Collections.singletonMap("A","A1"),Collections.emptyMap()));
        File base=new File(context.getFilesDir(),"player-state-v1.json"),pending=new File(base.getPath()+".new");byte[] before=Files.readAllBytes(base.toPath()),shadow="saved-evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(pending.toPath(),shadow);int mode=Os.stat(base.getPath()).st_mode&0777;
        try{Os.chmod(base.getPath(),mode&~0444);try(FileInputStream input=new FileInputStream(base)){fail("Must establish real EACCES");}catch(FileNotFoundException denied){assertTrue(denied.getCause() instanceof ErrnoException);assertEquals(OsConstants.EACCES,((ErrnoException)denied.getCause()).errno);}
            assertThrows(IllegalStateException.class,store::load);assertTrue(store.restoreFailed());assertThrows(IllegalStateException.class,()->store.save(new PlayerController.SavedState("A",Collections.emptyMap(),Collections.emptyMap())));
        }finally{Os.chmod(base.getPath(),mode);}assertArrayEquals(before,Files.readAllBytes(base.toPath()));assertArrayEquals(shadow,Files.readAllBytes(pending.toPath()));
    }
    @Test public void conservativeProgressBoundCoversRealAndroidJsonAtFullCoverage()throws Exception{
        List<Article> articles=new ArrayList<>();Map<String,PlayerController.SavedProgress> progress=new LinkedHashMap<>();
        for(int a=0;a<40;a++){Article article=Article.fromSentences("id"+a,"v1","Название","Один.","Два.","Три.");articles.add(article);progress.put(PlayerController.progressKey("S",article.id,article.version),new PlayerController.SavedProgress("S",article.id,article.version,2,true,Arrays.asList(0,1,2),true,true));}
        List<Station> catalog=Collections.singletonList(new Station("S","Источник",articles));long bound=RadioStateSizeBudget.fullyCompletedBytes(catalog);LocalData store=new LocalData(context);store.load();store.save(new PlayerController.SavedState("S",Collections.singletonMap("S","id0"),progress));assertTrue(Files.size(new File(context.getFilesDir(),"player-state-v1.json").toPath())<=bound);
    }
    @Test public void managedBudgetRejectsUnknownFilesWithoutDeletingOriginalOrEvidence()throws Exception{
        AndroidNoteStorage storage=new AndroidNoteStorage(context);File foreign=new File(storage.directory,"foreign-evidence"),wav=new File(storage.directory,UUID.randomUUID()+".wav");
        try(RandomAccessFile f=new RandomAccessFile(foreign,"rw")){f.setLength(SourceLimits.MAX_MANAGED_BYTES);}Files.write(wav.toPath(),new byte[]{1,2,3});
        SourceDiskBudget budget=new SourceDiskBudget(storage.directory,new File(context.getNoBackupFilesDir(),"source-cas"),new File(context.getNoBackupFilesDir(),"source-network"),context.getFilesDir());assertThrows(IllegalStateException.class,()->budget.require(1));assertEquals(SourceLimits.MAX_MANAGED_BYTES,foreign.length());assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(wav.toPath()));
    }
    @Test public void validatedFeedRedirectAliasesReconnectOneStationWithNewMetadataWithoutPlayback()throws Exception{
        AndroidNoteStorage storage=new AndroidNoteStorage(context);NoteBook book=new NoteBook(storage);LocalData radio=new LocalData(context);
        PlayerController player=new PlayerController(LocalData.fixtures(context),new PlayerController.SpeechEngine(){public void speak(String text,String id){fail("Connecting a source must not speak");}public void stop(){fail("Connecting a source must not revoke speech");}},(delay,task)->()->{},radio,snapshot->{},1000);
        java.util.concurrent.BlockingQueue<Runnable> owner=new java.util.concurrent.LinkedBlockingQueue<>();final String[] finalUrl={"https://feeds.example.org/v1"},body={"Первая версия."};
        SourceTransport transport=(url,kind,validators,scope)->new SourceTransport.Response(("<rss version='2.0'><channel><title>Лента</title><link>https://example.org/</link><description>Описание</description><item><guid>opaque</guid><title>Статья</title><link>https://example.org/article</link><description>"+body[0]+"</description></item></channel></rss>").getBytes(java.nio.charset.StandardCharsets.UTF_8),finalUrl[0],200,"application/rss+xml","UTF-8",null,null);
        SourceDiskBudget budget=new SourceDiskBudget(storage.directory,new File(context.getNoBackupFilesDir(),"source-cas"),new File(context.getNoBackupFilesDir(),"source-network"),context.getFilesDir());
        SourceCoordinator coordinator=new SourceCoordinator(book,transport,new SourceBodyCache(new File(context.getNoBackupFilesDir(),"source-cas")),new SourceCoordinator.Port(){
            public void dispatch(Runnable r){owner.add(r);}public void requireMutable(){assertFalse(book.storageUncertain());}public void requireStorage(long n){budget.require(n);}public void appendStation(SourceRecord s){player.appendStation(s.stationId,s.name);}
            public void requireQueue(SourceRecord s,Article a){fail("Connect must not enqueue");}public void append(NoteBook.Material m){fail("Connect must not enqueue");}public PlayerController.SavedProgress persisted(NoteBook.Material m){throw new AssertionError("Connect must not remove");}public void pauseIfSelected(NoteBook.Material m){fail();}public void remove(NoteBook.Material m){fail();}public void projectRead(SourceRecord s,SourceItem i){fail();}public void captureHistory(){fail();}public void changed(){}
        },new AndroidSourceClock());
        try {
            java.util.concurrent.atomic.AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> result=new java.util.concurrent.atomic.AtomicReference<>();
            coordinator.preview("https://initial.example.org/feed",false,null,result::set);SourceCoordinator.Preview first=awaitPreview(owner,result);SourceRecord connected=coordinator.connect(first,"Первое имя");String itemId=book.sourceItems(connected.id).get(0).articleId,oldVersion=book.sourceItems(connected.id).get(0).latestVersionKey;
            assertTrue(connected.aliases.contains("https://initial.example.org/feed"));assertEquals(finalUrl[0],connected.key);assertTrue(book.materials().isEmpty());assertFalse(player.snapshot().playing);
            finalUrl[0]="https://feeds.example.org/v2";body[0]="Вторая версия.";result.set(null);coordinator.preview("https://initial.example.org/feed",false,null,result::set);SourceRecord second=coordinator.connect(awaitPreview(owner,result),"Новое имя не заменяет первое");
            assertEquals(connected.id,second.id);assertEquals(connected.stationId,second.stationId);assertEquals("Первое имя",second.name);assertEquals(finalUrl[0],second.url);assertEquals(1,book.sources().size());assertEquals(4,player.stations().size());assertEquals(itemId,book.sourceItems(second.id).get(0).articleId);assertNotEquals(oldVersion,book.sourceItems(second.id).get(0).latestVersionKey);assertTrue(book.materials().isEmpty());assertFalse(player.snapshot().playing);
            assertTrue(second.aliases.contains("https://feeds.example.org/v1"));assertTrue(second.aliases.contains("https://initial.example.org/feed"));assertEquals("A",player.snapshot().station.id);assertEquals(1,new NoteBook(storage).sources().size());
        }finally{coordinator.close();}
    }
    private static SourceCoordinator.Preview awaitPreview(java.util.concurrent.BlockingQueue<Runnable> owner,java.util.concurrent.atomic.AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> result)throws Exception{
        long end=android.os.SystemClock.elapsedRealtime()+5000;while(result.get()==null&&android.os.SystemClock.elapsedRealtime()<end){Runnable r=owner.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS);if(r!=null)r.run();}assertNotNull("Actual native XML/owner delivery must finish",result.get());assertNull(result.get().error);return result.get().value;
    }
    /** Framing is the production recipe; this only builds an own synthetic input, no parser stub. */
    static class SourceCoordinatorForTest {
        static SourceVersionRecord version(SourceRecord s,SourceItem item)throws Exception{
            ExtractionResult e=new ExtractionResult("Синтетическое тело. Второе предложение.","Заголовок","Заголовок","unknown","SYNTHETIC","feed","feed-v1");SourceArticleFramer.Framed f=SourceArticleFramer.frame(s.name,e);
            return new SourceVersionRecord(s.id,item.itemKey,item.articleId,f.versionHash,e.displayTitle,e.providerTitle,s.name,item.url,e.completeness,e.reason,e.method,e.adapterVersion,SemanticTextNormalizer.VERSION,SourceArticleFramer.VERSION,f.bodyHash,f.spokenText,f.bodyStart,f.bodyEnd,2);
        }
    }
}

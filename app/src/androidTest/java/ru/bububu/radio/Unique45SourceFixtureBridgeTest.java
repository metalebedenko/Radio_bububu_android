package ru.bububu.radio;

import android.app.*;
import android.content.Context;
import android.content.pm.InstrumentationInfo;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import ru.bububu.radio.core.*;
import ru.bububu.radio.sources.*;
import static org.junit.Assert.*;

/** Explicit one-shot append to a proved fresh SIX, never a snapshot replacement or generic importer. */
@RunWith(AndroidJUnit4.class)
public class Unique45SourceFixtureBridgeTest {
    @Test public void appendOnlySealedSourceV2AfterExactFreshSix()throws Exception{
        Assume.assumeTrue("Only a future ROOT native gate may enable this bridge","true".equals(InstrumentationRegistry.getArguments().getString("enableUnique45SourceFixture")));
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context target=instrumentation.getTargetContext(),test=instrumentation.getContext();
        InstrumentationInfo info=test.getPackageManager().getInstrumentationInfo(instrumentation.getComponentName(),0);
        Unique45FixtureContract.identity(target.getPackageName(),test.getPackageName(),info.targetPackage);
        // No target files/prefs/models touched until all three identities above match.
        byte[] manifest=asset(test,"manifest.json"),longBody=asset(test,"long-body.txt"),unknownBody=asset(test,"unknown-body.txt");
        assertEquals(Unique45FixtureContract.MANIFEST_SHA,SourceLimits.hash(manifest));assertEquals(Unique45FixtureContract.LONG_SHA,SourceLimits.hash(longBody));assertEquals(Unique45FixtureContract.UNKNOWN_SHA,SourceLimits.hash(unknownBody));
        ActivityManager manager=(ActivityManager)target.getSystemService(Context.ACTIVITY_SERVICE);
        for(ActivityManager.RunningServiceInfo service:manager.getRunningServices(Integer.MAX_VALUE))assertNotEquals("Stop the synthetic app normally before this one-shot test",new android.content.ComponentName(target,RadioService.class),service.service);
        instrumentation.waitForIdleSync();assertFalse("Unconfirmed audio owner",AndroidNoteAudio.isOutputBlocked());assertFalse("Original writer still busy",AndroidNoteAudio.isRecordingStorageBusy());
        for(Thread thread:Thread.getAllStackTraces().keySet())assertFalse("Source/file worker still live",thread.isAlive()&&(thread.getName().equals("Bububu-source")||thread.getName().equals("Bububu-file-stt")||thread.getName().startsWith("Bububu-note")));
        Path data=Path.of(target.getApplicationInfo().dataDir),parent=data.resolve("no_backup"),notes=parent.resolve("notes"),base=notes.resolve(NoteMetadataReader.BASE),receipt=notes.resolve(StartupSourceFacts45.RECEIPT);
        requireDirectory(data);requireDirectory(parent);requireDirectory(notes);Unique45EmptyDraftGuard.Snapshot emptyDraft=Unique45EmptyDraftGuard.read(notes);Set<String> allowed=new HashSet<>(Arrays.asList(NoteMetadataReader.BASE,StartupSourceFacts45.RECEIPT,StartupSourceFacts45.RECEIPT+".bak",Unique45EmptyDraftGuard.NAME));
        try(DirectoryStream<Path> files=Files.newDirectoryStream(notes)){for(Path path:files)assertTrue("Unproved notes artifact: "+path.getFileName(),allowed.contains(path.getFileName().toString()));}
        requireEmptyOrAbsent(parent.resolve("source-cas"));requireEmptyOrAbsent(parent.resolve("source-network"));
        byte[] before=read(base,NoteBook.MAX_METADATA_BYTES),receiptBytes=Files.exists(notes.resolve(StartupSourceFacts45.RECEIPT+".bak"),LinkOption.NOFOLLOW_LINKS)?read(notes.resolve(StartupSourceFacts45.RECEIPT+".bak"),65536):read(receipt,65536);
        Seed45Marker marker=Unique45FixtureContract.freshBook(before,receiptBytes);assertArrayEquals(before,read(base,NoteBook.MAX_METADATA_BYTES));
        assertEquals("Sealed fixture manifest",Unique45FixtureContract.MANIFEST_SHA,SourceLimits.hash(manifest));
        emptyDraft.requirePreserved(notes);
        // From here synthetic owner/attempt/state are proved; use only existing append/storage APIs.
        AndroidNoteStorage storage=new AndroidNoteStorage(target);NoteBook book=new NoteBook(storage);book.enable45();assertArrayEquals(before,storage.load());
        Unique45FixtureContract.Fixtures fixture=Unique45FixtureContract.fixtures(manifest,longBody,unknownBody,book);
        SourceBodyCache cache=new SourceBodyCache(parent.resolve("source-cas").toFile());long extra=0;for(SourceVersionRecord v:fixture.versions)extra=Math.addExact(extra,cache.additionalBytes(v));
        new SourceDiskBudget(storage.directory,parent.resolve("source-cas").toFile(),parent.resolve("source-network").toFile(),target.getFilesDir()).requireMetadataPeak(Math.addExact(2L*NoteBook.MAX_METADATA_BYTES,extra));
        for(SourceVersionRecord v:fixture.versions)cache.put(v);
        fixture.appendMetadata(book);for(SourceVersionRecord v:fixture.versions)book.selectSourceVersion(cache.get(v.withPayload(null)),false);
        assertEquals(6,book.sources().size());assertEquals(marker.bindings,book.seed45Marker().bindings);assertEquals(marker.attemptId,book.seed45Marker().attemptId);assertEquals(2,book.materials().size());assertTrue(book.notes().isEmpty());
        NoteBook reopened=new NoteBook(storage);for(SourceVersionRecord v:fixture.versions){assertEquals(v.spokenText,cache.get(reopened.sourceVersion(v.recordKey)).spokenText);assertNotNull(reopened.materials().stream().filter(m->v.recordKey.equals(m.sourceVersionKey)).findFirst().orElse(null));}
        assertArrayEquals(receiptBytes,Files.exists(notes.resolve(StartupSourceFacts45.RECEIPT+".bak"),LinkOption.NOFOLLOW_LINKS)?read(notes.resolve(StartupSourceFacts45.RECEIPT+".bak"),65536):read(receipt,65536));
        emptyDraft.requirePreserved(notes);
        Bundle result=new Bundle();result.putString("qa45SourceFixtureManifestSha",Unique45FixtureContract.MANIFEST_SHA);result.putString("beforeBookSha",SourceLimits.hash(before));result.putString("afterBookSha",SourceLimits.hash(storage.load()));result.putString("seedAttempt",marker.attemptId);instrumentation.sendStatus(0,result);
    }
    @Test public void appendTwoTypedOwnedItemsAfterLegacyOnlyProof()throws Exception{
        Assume.assumeTrue("Future ROOT typed continuation only","true".equals(InstrumentationRegistry.getArguments().getString("enableUnique45TypedAppend")));
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context target=instrumentation.getTargetContext(),test=instrumentation.getContext();
        InstrumentationInfo info=test.getPackageManager().getInstrumentationInfo(instrumentation.getComponentName(),0);Unique45FixtureContract.identity(target.getPackageName(),test.getPackageName(),info.targetPackage);
        byte[] manifest=asset(test,"manifest.json"),longBody=asset(test,"long-body.txt"),unknownBody=asset(test,"unknown-body.txt");
        ActivityManager manager=(ActivityManager)target.getSystemService(Context.ACTIVITY_SERVICE);for(ActivityManager.RunningServiceInfo s:manager.getRunningServices(Integer.MAX_VALUE))assertNotEquals("Stop C3 normally before this append",new android.content.ComponentName(target,RadioService.class),s.service);
        instrumentation.waitForIdleSync();assertFalse(AndroidNoteAudio.isOutputBlocked());assertFalse(AndroidNoteAudio.isRecordingStorageBusy());
        for(Thread thread:Thread.getAllStackTraces().keySet())assertFalse("Source/file/notes worker live",thread.isAlive()&&(thread.getName().equals("Bububu-source")||thread.getName().equals("Bububu-file-stt")||thread.getName().startsWith("Bububu-note")));
        Path data=Path.of(target.getApplicationInfo().dataDir),parent=data.resolve("no_backup"),notes=parent.resolve("notes"),base=notes.resolve(NoteMetadataReader.BASE),receipt=notes.resolve(StartupSourceFacts45.RECEIPT);
        requireDirectory(data);requireDirectory(parent);Unique45EmptyDraftGuard.Snapshot draft=Unique45NotesAsrContract.requireExactEmptyNotes(notes);
        Map<String,byte[]> receipts=new TreeMap<>();for(String suffix:List.of("",".bak")){Path p=notes.resolve(StartupSourceFacts45.RECEIPT+suffix);if(Files.exists(p,LinkOption.NOFOLLOW_LINKS))receipts.put(suffix,read(p,65536));}
        assertFalse(receipts.isEmpty());byte[] receiptBytes=receipts.containsKey(".bak")?receipts.get(".bak"):receipts.get("");byte[] before=read(base,NoteBook.MAX_METADATA_BYTES);
        Unique45NotesAsrContract.Proof old=Unique45NotesAsrContract.proveLegacy(before,receiptBytes,manifest,longBody,unknownBody);Map<String,String> oldCas=old.requireCas(parent.resolve("source-cas"));
        AndroidNoteStorage storage=new AndroidNoteStorage(target);NoteBook book=new NoteBook(storage);book.enable45();assertArrayEquals(before,storage.load());
        Unique45FixtureContract.Fixtures typed=Unique45FixtureContract.typedFixtures(manifest,longBody,unknownBody,book);assertTrue("Do not enable a source as a side effect",typed.source.enabled&&book.isSourceActive(typed.source.id));
        SourceBodyCache cache=new SourceBodyCache(parent.resolve("source-cas").toFile());long extra=0;for(SourceVersionRecord v:typed.versions)extra=Math.addExact(extra,cache.additionalBytes(v));new SourceDiskBudget(storage.directory,parent.resolve("source-cas").toFile(),parent.resolve("source-network").toFile(),target.getFilesDir()).requireMetadataPeak(Math.addExact(2L*NoteBook.MAX_METADATA_BYTES,extra));
        assertArrayEquals(before,storage.load());assertEquals(oldCas,old.requireCas(parent.resolve("source-cas")));draft.requirePreserved(notes);
        // All preconditions above are read-only. Append only the TWO NEW owned identities below.
        for(SourceVersionRecord v:typed.versions)cache.put(v);typed.appendMetadata(book);for(SourceVersionRecord v:typed.versions)book.selectSourceVersion(cache.get(v.withPayload(null)),false);
        byte[] after=storage.load();old.requireOnlyTypedAppend(after,typed);
        Unique45NotesAsrContract.Proof four=Unique45NotesAsrContract.proveTyped(after,receiptBytes,manifest,longBody,unknownBody);Map<String,String> allCas=four.requireCas(parent.resolve("source-cas"));for(Map.Entry<String,String> e:oldCas.entrySet())assertEquals(e.getValue(),allCas.get(e.getKey()));
        for(Map.Entry<String,byte[]> e:receipts.entrySet())assertArrayEquals(e.getValue(),read(notes.resolve(StartupSourceFacts45.RECEIPT+e.getKey()),65536));draft.requirePreserved(notes);assertEquals(Unique45FixtureContract.TYPED_LONG_ARTICLE,four.linkedVersion().articleId);
        Bundle result=new Bundle();result.putString("beforeBookSha",SourceLimits.hash(before));result.putString("afterBookSha",SourceLimits.hash(after));result.putString("typedLongRecord",four.linkedRecord);result.putString("ownState","SIX6/items4/versions4/selected4/CAS4/Notes0/empty8");instrumentation.sendStatus(0,result);
    }
    private static byte[] asset(Context test,String name)throws IOException{try(InputStream in=test.getAssets().open("qa45-source-fixture/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] bytes=new byte[4096];for(int n;(n=in.read(bytes))!=-1;){out.write(bytes,0,n);if(out.size()>65536)throw new IOException("Fixture asset bound");}return out.toByteArray();}}
    private static byte[] read(Path file,int cap)throws IOException{java.nio.file.attribute.BasicFileAttributes a=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!a.isRegularFile()||a.size()>cap)throw new IOException("Unproved bounded file");byte[] value=Files.readAllBytes(file);if(value.length>cap)throw new IOException("Changed file bound");return value;}
    private static void requireDirectory(Path path)throws IOException{if(!Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isDirectory())throw new IOException("Unproved directory");}
    private static void requireEmptyOrAbsent(Path path)throws IOException{try{requireDirectory(path);}catch(NoSuchFileException absent){return;}try(DirectoryStream<Path> files=Files.newDirectoryStream(path)){if(files.iterator().hasNext())throw new IOException("Nonempty synthetic source storage");}}
}

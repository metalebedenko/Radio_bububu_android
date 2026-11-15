package ru.bububu.radio;

import android.Manifest;
import android.app.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.content.pm.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import ru.bububu.radio.core.*;
import ru.bububu.radio.models.ModelsController;
import ru.bububu.radio.sources.*;
import static org.junit.Assert.*;

/** Future ROOT Gate B only. One append, one local ASR request; failures retain the owned Note/original. */
@RunWith(AndroidJUnit4.class)
public final class Unique45NotesAsrAppendTest {
    private Instrumentation instrumentation;private Context target;private ActivityScenario<RadioActivity> scenario;
    private RadioActivity activity;private RadioService service;private NativeFixtureSupport drain;private ModelsController models;
    private String ownId;private int requests,launches,compatibilityAcks;private boolean proved,closedCleanly;private long asrElapsed;
    private final Bundle evidence=new Bundle();
    private static final String COMPAT_TITLE="Совместимость приложений Android";
    private static final String COMPAT_MESSAGE="Это предупреждение означает, что приложение находится в состоянии отладки и сейчас тестируется. Если вы не создатель приложения, попросите разработчика предоставить версию без возможности отладки. Это приложение не поддерживает страницы памяти объемом 16 КБ. Проверка выравнивания в файле ELF вернула ошибку. Следуйте инструкциям на странице https://developer.android.com/16kb-page-size. Следующие библиотеки не выровнены по 16 КБ: • lib/arm64-v8a/libonnxruntime.so: неизвестная ошибка • lib/arm64-v8a/libsherpa-onnx-jni.so: неизвестная ошибка • lib/arm64-v8a/libpytorch_jni.so: сегмент LOAD не выровнен • lib/arm64-v8a/libfbjni.so: сегмент LOAD не выровнен • lib/arm64-v8a/libc++_shared.so: сегмент LOAD не выровнен";

    @Test public void oneApprovedSpeechNoteThroughLocalGigaAmAppendOnly()throws Exception{
        Assume.assumeTrue("Future ROOT Gate B must explicitly enable this method","true".equals(InstrumentationRegistry.getArguments().getString("enableUnique45NotesAsr")));
        instrumentation=InstrumentationRegistry.getInstrumentation();target=instrumentation.getTargetContext();Context test=instrumentation.getContext();
        InstrumentationInfo info=test.getPackageManager().getInstrumentationInfo(instrumentation.getComponentName(),0);
        Unique45FixtureContract.identity(target.getPackageName(),test.getPackageName(),info.targetPackage);
        // The unchanged runner also enforces these identities BEFORE target Application construction.
        byte[] pcm=asset(test,"qa45-notes-asr/synthetic.pcm",Unique45NotesAsrContract.PCM_BYTES);
        byte[] manifest=asset(test,"qa45-source-fixture/manifest.json",65536),longBody=asset(test,"qa45-source-fixture/long-body.txt",65536),unknownBody=asset(test,"qa45-source-fixture/unknown-body.txt",65536);
        assertEquals(Unique45NotesAsrContract.PCM_BYTES,pcm.length);assertEquals(Unique45NotesAsrContract.PCM_SHA,SourceLimits.hash(pcm));
        quietBeforeData();assertMicDenied();Path data=Path.of(target.getApplicationInfo().dataDir),parent=data.resolve("no_backup"),notes=parent.resolve("notes"),base=notes.resolve(NoteMetadataReader.BASE);
        directory(data);directory(parent);directory(notes);Unique45NotesAsrContract.requireExactEmptyNotes(notes);onlyMetadata(notes,null);emptyOrAbsent(parent.resolve("source-network"));
        byte[] before=read(base,NoteBook.MAX_METADATA_BYTES),receipt=receipt(notes);
        Unique45NotesAsrContract.Proof proof=Unique45NotesAsrContract.prove(before,receipt,manifest,longBody,unknownBody,pcm);
        verifyCas(parent.resolve("source-cas"),proof);assertArrayEquals(before,read(base,NoteBook.MAX_METADATA_BYTES));
        Map<String,String> outside=outsideState(data);
        main(()->{models=ModelsController.get(target);assertFalse("Existing model work must finish before Gate B",models.busy());assertNull(models.notesAvailability());return null;});
        assertTrue("No platform fallback/choice change",target.getSharedPreferences("local-model-choice",0).getBoolean("giga-notes",false));
        proved=true;evidence.putString("beforeBookSha",SourceLimits.hash(before));evidence.putString("seedAttempt",proof.marker.attemptId);evidence.putString("pcmSha",Unique45NotesAsrContract.PCM_SHA);
        try{
            launch();NativeFixtureSupport.clickVisible(instrumentation,"Заметки");
            main(()->{assertSilent();assertTrue("GigaAM local choice is required; no platform recognizer fallback",(boolean)field(service,"gigaNotes"));assertNull(service.notesProblem());models=ModelsController.get(target);assertFalse(models.busy());assertNull(models.notesAvailability());return null;});
            assertArrayEquals("Startup must not change the Gate A Book",before,main(()->service.noteStorage().load()));
            assertEquals("Startup must preserve the other durable state",outside,outsideState(data));
            NoteBook.Note original=main(()->{
                assertSilent();NoteBook b=service.notesBook();assertTrue(b.notes().isEmpty());assertFalse(service.notes().recognitionBusy());
                NoteBook.Note note=b.beginRecordingLinkedSource(Unique45NotesAsrContract.TITLE,proof.linkedRecord,System.currentTimeMillis());ownId=note.id;
                File part=new File(service.noteStorage().directory,note.id+".wav.part"),wave=new File(service.noteStorage().directory,note.id+".wav");
                assertFalse(part.exists());assertFalse(wave.exists());
                try(PcmWave.Writer writer=new PcmWave.Writer(part)){writer.append(pcm,0,pcm.length);writer.finish(wave);}
                assertEquals(Unique45NotesAsrContract.WAV_BYTES,wave.length());assertEquals(Unique45NotesAsrContract.WAV_SHA,hash(wave.toPath()));
                return b.finishRecording(note.id,PcmWave.readDuration(wave),true,false,"Синтетический оригинал Gate B без микрофона");
            });
            assertEquals(0,original.revision);assertEquals("NOT_REQUESTED",original.recognitionStatus);assertEquals(proof.linkedRecord,original.linkedSourceVersionKey);
            assertEquals(proof.linkedVersion().articleId,original.linkedArticle);assertEquals(proof.linkedVersion().versionKey,original.linkedVersion);assertEquals(proof.linkedVersion().sourceId,proof.marker.bindings.get("elementy-science-explainers"));
            long started=SystemClock.elapsedRealtime();main(()->{assertSilent();assertFalse(models.busy());assertEquals(0,requests);requests++;assertTrue("Exactly one Notes controller ASR request must start",service.notes().transcribe(ownId,false));return null;});
            await(Unique45NotesAsrContract.ASR_MS,()->main(()->{assertSilent();NoteBook.Note n=service.notesBook().find(ownId);return !service.notes().recognitionBusy()&&!"TRANSCRIBING".equals(n.recognitionStatus)&&!models.busy();}));
            asrElapsed=SystemClock.elapsedRealtime()-started;assertTrue("One ASR <=120000ms",asrElapsed<=Unique45NotesAsrContract.ASR_MS);
            NoteBook.Note ready=main(()->service.notesBook().find(ownId));assertEquals("Enabled positive acceptance requires actual READY","READY",ready.recognitionStatus);
            String normalized=ready.text.toLowerCase(Locale.ROOT).replace('ё','е');assertTrue(normalized.contains("начало проверки"));assertTrue(normalized.contains("конец проверки"));assertEquals(1,ready.revision);sameOriginal(original,ready);
            proof.requireOnlyOwnNoteChange(main(()->service.noteStorage().load()),ownId);assertEquals(1,requests);
            closeAndDrain();checkDurable(notes,data,proof,receipt,outside,ready);
            launch();NativeFixtureSupport.clickVisible(instrumentation,"Заметки");NoteBook.Note reloaded=main(()->service.notesBook().find(ownId));
            assertEquals(ready.text,reloaded.text);assertEquals(ready.revision,reloaded.revision);assertEquals("READY",reloaded.recognitionStatus);sameOriginal(ready,reloaded);
            main(()->{assertSilent();assertFalse("Restart cannot trigger a second ASR",service.notes().recognitionBusy());assertFalse(models.busy());return null;});
            closeAndDrain();checkDurable(notes,data,proof,receipt,outside,reloaded);assertMicDenied();assertEquals(1,requests);
            evidence.putString("status","PASS_BOUNDED_APPEND_ONLY_NOTES_ASR");evidence.putLong("asrElapsedMs",asrElapsed);evidence.putInt("controllerAsrRequests",requests);evidence.putString("ownNoteId",ownId);evidence.putString("originalSha",Unique45NotesAsrContract.WAV_SHA);evidence.putLong("revision",reloaded.revision);
        }catch(Throwable failure){captureUiFailure(failure);throw failure;}finally{
            if(proved)try{closeAndDrain();evidence.putString("cleanup","DRAINED");}catch(Throwable unconfirmed){evidence.putString("cleanup","UNCONFIRMED_RETAIN_OWN_EVIDENCE");instrumentation.sendStatus(-1,evidence);throw unconfirmed;}
            if(proved){if(!evidence.containsKey("status"))evidence.putString("status","FAIL_RETAIN_OWN_NOTE_AND_ORIGINAL");evidence.putInt("controllerAsrRequests",requests);if(ownId!=null)evidence.putString("ownNoteId",ownId);instrumentation.sendStatus(0,evidence);}
        }
    }
    private void checkDurable(Path notes,Path data,Unique45NotesAsrContract.Proof proof,byte[] receipt,Map<String,String> outside,NoteBook.Note ready)throws Exception{
        byte[] after=read(notes.resolve(NoteMetadataReader.BASE),NoteBook.MAX_METADATA_BYTES);proof.requireOnlyOwnNoteChange(after,ownId);assertEquals(Unique45NotesAsrContract.WAV_SHA,hash(notes.resolve(ownId+".wav")));assertArrayEquals(receipt,receipt(notes));onlyMetadata(notes,ownId);assertEquals(outside,outsideState(data));
        NoteBook reopened=Unique45NotesAsrContract.readOnly(after);assertEquals(1,reopened.notes().size());NoteBook.Note n=reopened.find(ownId);assertEquals("READY",n.recognitionStatus);assertEquals(ready.text,n.text);assertEquals(ready.revision,n.revision);sameOriginal(ready,n);SourceVersionRecord context=reopened.sourceVersion(n.linkedSourceVersionKey);assertTrue(proof.linkedVersion().sameMetadata(context));assertEquals(proof.linkedVersion().spokenText,context.spokenText);evidence.putString("afterBookSha",SourceLimits.hash(after));
    }
    private void launch(){assertTrue("Only the two existing legitimate launches",++launches<=2);closedCleanly=false;scenario=ActivityScenario.launch(RadioActivity.class);scenario.onActivity(a->activity=a);await(10000,()->main(()->activity.serviceForNotes()!=null));service=main(activity::serviceForNotes);drain=new NativeFixtureSupport(instrumentation,target,List.of());drain.track(service);acknowledgeKnownCompatibilityDialog();}
    /** Owner calls only. A system informational modal may temporarily own focus before its ONE acknowledgement. */
    private void requireOwnedActivity(boolean focused)throws Exception{
        assertNotNull(activity);assertNotNull(service);assertFalse(activity.isFinishing());assertFalse(activity.isDestroyed());
        assertEquals(new ComponentName(target,RadioActivity.class),activity.getComponentName());assertEquals(Unique45FixtureContract.APP,activity.getPackageName());
        int uid=target.getApplicationInfo().uid;assertEquals(uid,activity.getApplicationInfo().uid);assertEquals(uid,service.getApplicationInfo().uid);assertEquals(uid,android.os.Process.myUid());
        assertSame(service,activity.serviceForNotes());assertEquals(Stage.RESUMED,ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity));assertTrue((boolean)field(activity,"activityStarted"));
        View decor=activity.getWindow().getDecorView();assertTrue(decor.isAttachedToWindow());assertTrue(decor.isShown());
        if(focused)assertTrue("Owned Activity must regain window focus",activity.hasWindowFocus());
    }
    private void acknowledgeKnownCompatibilityDialog(){
        UiAutomation ui=instrumentation.getUiAutomation();AccessibilityServiceInfo info=ui.getServiceInfo();assertNotNull(info);int previous=info.flags;info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ui.setServiceInfo(info);
        boolean acknowledged=false;int acknowledgedWindow=-1;long until=SystemClock.elapsedRealtime()+8000;
        try{
            while(SystemClock.elapsedRealtime()<until){
                evidence.putString("uiStage",acknowledged?"waiting-own-focus-after-known-ack":"checking-owned-launch-window");
                instrumentation.waitForIdleSync();main(()->{requireOwnedActivity(false);return null;});AccessibilityNodeInfo root=ui.getRootInActiveWindow();
                if(root==null){SystemClock.sleep(25);continue;}
                try{
                    String owner=String.valueOf(root.getPackageName());
                    if(Unique45FixtureContract.APP.equals(owner)){
                        if(main(()->activity.hasWindowFocus())){main(()->{requireOwnedActivity(true);return null;});evidence.putInt("compatibilityAcks",compatibilityAcks);evidence.putString("uiStage","own-app-focused-before-existing-notes-click");return;}
                    }else{
                        assertEquals("REFUSE_UNKNOWN_ACTIVE_WINDOW","android",owner);
                        AccessibilityNodeInfo title=null,message=null,ok=null,never=null;
                        try{
                            title=oneControl(root,"android:id/alertTitle",COMPAT_TITLE,false);message=oneControl(root,"android:id/message",COMPAT_MESSAGE,false);
                            ok=oneControl(root,"android:id/button2","ОК",true);never=oneControl(root,"android:id/button1","Больше не показывать",true);requireKnownControls(root,new int[]{128},0);
                            if(acknowledged){assertEquals("REFUSE_REPEATED_COMPATIBILITY_DIALOG",acknowledgedWindow,root.getWindowId());}
                            else{
                                main(()->{requireOwnedActivity(false);return null;});assertTrue("At most two informational ACKs in the entire test",compatibilityAcks<2);
                                acknowledged=true;acknowledgedWindow=root.getWindowId();compatibilityAcks++;evidence.putInt("compatibilityAcks",compatibilityAcks);
                                // Only this already validated button2 node is actionable. Never button1 or a generic OK search.
                                assertTrue("Known informational ACK was not accepted",ok.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                            }
                        }finally{for(AccessibilityNodeInfo n:new AccessibilityNodeInfo[]{title,message,ok,never})if(n!=null)n.recycle();}
                    }
                }finally{root.recycle();}
                SystemClock.sleep(25);
            }
            fail("REFUSE_OWN_APP_FOCUS_OR_DIALOG_DISMISS_TIMEOUT");
        }finally{info.flags=previous;ui.setServiceInfo(info);}
    }
    private static String normalizedUiText(CharSequence text){return String.valueOf(text).replace("\\n","\n").replace('\u00a0',' ').replaceAll("\\s+"," ").trim();}
    private static AccessibilityNodeInfo oneControl(AccessibilityNodeInfo root,String id,String text,boolean button){
        List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByViewId(id);AccessibilityNodeInfo owned=null;
        try{assertEquals("REFUSE_KNOWN_DIALOG_CONTROL_COUNT",1,nodes.size());AccessibilityNodeInfo n=nodes.get(0);assertEquals("android",String.valueOf(n.getPackageName()));assertTrue("REFUSE_KNOWN_DIALOG_TEXT",text.equals(normalizedUiText(n.getText())));assertTrue(n.isVisibleToUser());assertTrue(n.isEnabled());if(button){assertEquals("android.widget.Button",String.valueOf(n.getClassName()));assertTrue(n.isClickable());}owned=AccessibilityNodeInfo.obtain(n);return owned;}
        finally{for(AccessibilityNodeInfo n:nodes)n.recycle();}
    }
    private static void requireKnownControls(AccessibilityNodeInfo node,int[] remaining,int depth){assertTrue("REFUSE_DIALOG_TREE_BOUND",remaining[0]-->0&&depth<32);assertFalse("REFUSE_DIALOG_CHECKBOX",node.isCheckable());if("android.widget.Button".contentEquals(String.valueOf(node.getClassName())))assertTrue("REFUSE_EXTRA_DIALOG_BUTTON",List.of("android:id/button1","android:id/button2").contains(node.getViewIdResourceName()));for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);assertNotNull(child);try{requireKnownControls(child,remaining,depth+1);}finally{child.recycle();}}}
    private void captureUiFailure(Throwable failure){
        evidence.putString("failure",failure.getClass().getSimpleName());evidence.putInt("launches",launches);evidence.putInt("compatibilityAcks",compatibilityAcks);
        try{
            Path dir=target.getNoBackupFilesDir().toPath().resolve("qa45-notes-asr-private");Files.createDirectories(dir);String name="failure-"+SystemClock.elapsedRealtime();
            String metadata="Notes ASR test failure; stage="+evidence.getString("uiStage","")+"; launches="+launches+"; knownCompatibilityAcks="+compatibilityAcks+"; type="+failure.getClass().getSimpleName()+"\n";Files.write(dir.resolve(name+".txt"),metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);evidence.putString("failurePrivateBase",dir.resolve(name).toString());
            Bitmap bitmap=instrumentation.getUiAutomation().takeScreenshot();if(bitmap!=null)try(ByteArrayOutputStream png=new ByteArrayOutputStream()){if(bitmap.getByteCount()<=16*1024*1024&&bitmap.compress(Bitmap.CompressFormat.PNG,100,png)&&png.size()<=2*1024*1024)Files.write(dir.resolve(name+".png"),png.toByteArray(),StandardOpenOption.CREATE_NEW);}finally{bitmap.recycle();}
        }catch(Throwable unavailable){evidence.putString("failureCapture","UNAVAILABLE");}
    }
    private void closeAndDrain()throws Exception{
        if(closedCleanly)return;long start=SystemClock.elapsedRealtime();if(drain==null){drain=new NativeFixtureSupport(instrumentation,target,List.of());drain.track(service);}drain.stopAndDrain(()->{if(scenario!=null){scenario.close();scenario=null;}});
        long left=Unique45NotesAsrContract.CLEANUP_MS-(SystemClock.elapsedRealtime()-start);assertTrue("Cleanup budget",left>0);if(models!=null)await(left,()->main(()->!models.busy()));assertFalse(AndroidNoteAudio.isOutputBlocked());assertFalse(AndroidNoteAudio.isRecordingStorageBusy());
        if(models!=null){Class<?> c=Class.forName("ru.bububu.radio.models.NativeModels");Field f=c.getDeclaredField("blocked");f.setAccessible(true);assertFalse("GigaAM release must be confirmed",f.getBoolean(null));}
        drain=null;service=null;activity=null;closedCleanly=true;
    }
    private void assertSilent()throws Exception{requireOwnedActivity(true);assertTrue("Real Notes page must be selected",(boolean)field(service,"notesPage"));assertTrue((boolean)field(service,"activityVisible"));assertFalse(service.notes().recordingBusy());assertFalse(service.notes().originalPlaying());assertFalse(service.player().snapshot().playing);assertNull(service.player().snapshot().currentRequestId);}
    private void assertMicDenied(){assertNotEquals("No microphone grant allowed",PackageManager.PERMISSION_GRANTED,target.checkSelfPermission(Manifest.permission.RECORD_AUDIO));}
    private void quietBeforeData(){ActivityManager am=(ActivityManager)target.getSystemService(Context.ACTIVITY_SERVICE);for(ActivityManager.RunningServiceInfo s:am.getRunningServices(Integer.MAX_VALUE))assertNotEquals("Gate B requires a normally stopped synthetic app",new ComponentName(target,RadioService.class),s.service);instrumentation.waitForIdleSync();assertFalse(AndroidNoteAudio.isOutputBlocked());assertFalse(AndroidNoteAudio.isRecordingStorageBusy());for(Thread t:Thread.getAllStackTraces().keySet())assertFalse("Live source/STT writer",t.isAlive()&&(t.getName().equals("Bububu-source")||t.getName().equals("Bububu-file-stt")||t.getName().startsWith("Bububu-note")));}
    private static void sameOriginal(NoteBook.Note a,NoteBook.Note b){assertEquals(a.id,b.id);assertEquals(a.title,b.title);assertEquals(a.createdAt,b.createdAt);assertEquals(a.durationMs,b.durationMs);assertEquals(a.positionMs,b.positionMs);assertEquals(a.hasAudio,b.hasAudio);assertEquals(a.recordingStatus,b.recordingStatus);assertEquals(a.linkedStation,b.linkedStation);assertEquals(a.linkedArticle,b.linkedArticle);assertEquals(a.linkedVersion,b.linkedVersion);assertEquals(a.linkedSourceVersionKey,b.linkedSourceVersionKey);}
    private static void verifyCas(Path cas,Unique45NotesAsrContract.Proof proof)throws Exception{proof.requireCas(cas);}
    private static void onlyMetadata(Path notes,String own)throws Exception{Unique45EmptyDraftGuard.read(notes);Set<String> allowed=new HashSet<>(List.of(NoteMetadataReader.BASE,StartupSourceFacts45.RECEIPT,StartupSourceFacts45.RECEIPT+".bak",Unique45EmptyDraftGuard.NAME));if(own!=null)allowed.add(own+".wav");try(DirectoryStream<Path> paths=Files.newDirectoryStream(notes)){for(Path p:paths){assertTrue("Unproved original/draft/Book temporary: "+p.getFileName(),allowed.contains(p.getFileName().toString()));regular(p);}}}
    private static byte[] receipt(Path notes)throws Exception{Path bak=notes.resolve(StartupSourceFacts45.RECEIPT+".bak");return read(Files.exists(bak,LinkOption.NOFOLLOW_LINKS)?bak:notes.resolve(StartupSourceFacts45.RECEIPT),65536);}
    private static void directory(Path p)throws Exception{assertTrue(Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isDirectory());}
    private static void regular(Path p)throws Exception{assertTrue(Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isRegularFile());}
    private static void emptyOrAbsent(Path p)throws Exception{try{directory(p);}catch(NoSuchFileException absent){return;}try(DirectoryStream<Path> files=Files.newDirectoryStream(p)){assertFalse(files.iterator().hasNext());}}
    private static byte[] read(Path p,int cap)throws Exception{regular(p);assertTrue(Files.size(p)<=cap);byte[] b=Files.readAllBytes(p);assertTrue(b.length<=cap);return b;}
    private static byte[] asset(Context c,String name,int cap)throws Exception{try(InputStream in=c.getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;){out.write(b,0,n);assertTrue(out.size()<=cap);}return out.toByteArray();}}
    private static Map<String,String> outsideState(Path data)throws Exception{Map<String,String> out=new TreeMap<>();out.put("notes/"+Unique45EmptyDraftGuard.NAME,Unique45EmptyDraftGuard.read(data.resolve("no_backup/notes")).fact());for(String folder:List.of("shared_prefs","no_backup/source-cas","no_backup/source-network"))inventory(data.resolve(folder),folder,out);modelInventory(data.resolve("files/model-packs"),"model-packs",out);for(String suffix:List.of("",".bak",".new")){Path p=data.resolve("files/player-state-v1.json"+suffix);out.put("player"+suffix,Files.exists(p,LinkOption.NOFOLLOW_LINKS)?hash(p):"ABSENT");}return out;}
    private static void modelInventory(Path p,String key,Map<String,String> out)throws Exception{if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS)){out.put(key,"ABSENT");return;}BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);assertFalse(a.isSymbolicLink());if(a.isDirectory()){out.put(key,"DIR");try(DirectoryStream<Path> children=Files.newDirectoryStream(p)){for(Path child:children)modelInventory(child,key+"/"+child.getFileName(),out);}}else{assertTrue(a.isRegularFile());out.put(key,a.size()<=4096?hash(p):"META:"+a.size()+":"+a.lastModifiedTime()+":"+a.fileKey());}}
    private static void inventory(Path p,String key,Map<String,String> out)throws Exception{if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS)){out.put(key,"ABSENT");return;}BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);assertFalse(a.isSymbolicLink());if(a.isDirectory()){out.put(key,"DIR");try(DirectoryStream<Path> children=Files.newDirectoryStream(p)){for(Path child:children)inventory(child,key+"/"+child.getFileName(),out);}}else{assertTrue(a.isRegularFile());out.put(key,hash(p));}}
    private static String hash(Path p)throws Exception{regular(p);MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(p)){byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private <T>T main(Callable<T> action){AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();instrumentation.runOnMainSync(()->{try{value.set(action.call());}catch(Throwable t){error.set(t);}});if(error.get()!=null)throw new AssertionError("Owner action",error.get());return value.get();}
    private static void await(long ms,java.util.function.BooleanSupplier done){long end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){if(done.getAsBoolean())return;SystemClock.sleep(25);}throw new AssertionError("Bounded Gate B wait exceeded "+ms+"ms");}
}

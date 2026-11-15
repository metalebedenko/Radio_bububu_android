package ru.bububu.radio;

import android.app.*;
import android.content.*;
import android.media.session.*;
import android.os.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import ru.bububu.radio.core.*;
import ru.bububu.radio.sources.*;

/** Real service/requestPlay/system TTS; only own synthetic source text. No HTTP or mic. */
@RunWith(AndroidJUnit4.class)
public class SourcesAudioIntegrationTest {
    private Instrumentation instrumentation;private Context context;private ActivityScenario<RadioActivity> scenario;
    private RadioActivity activity;private RadioService service;private Path backup;private final Map<Path,Path> moved=new LinkedHashMap<>();private boolean isolated;
    @Before public void prepare()throws Exception{
        instrumentation=InstrumentationRegistry.getInstrumentation();context=instrumentation.getTargetContext();
        if(Build.VERSION.SDK_INT>=33)instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);
        instrumentation.getUiAutomation().dropShellPermissionIdentity();context.stopService(new Intent(context,RadioService.class));await(5000,()->running()==null);instrumentation.waitForIdleSync();
        backup=Files.createTempDirectory(context.getCacheDir().toPath(),"source-audio-preserve-");
        int index=0;
        for(File path:managedPaths()) if(Files.exists(path.toPath(),LinkOption.NOFOLLOW_LINKS)){Path preserved=backup.resolve("owned-"+(index++));Files.move(path.toPath(),preserved);moved.put(path.toPath(),preserved);}
        isolated=true;scenario=ActivityScenario.launch(RadioActivity.class);scenario.onActivity(a->{activity=a;});await(10000,()->main(()->activity.serviceForNotes()!=null));service=main(activity::serviceForNotes);
        long elapsedBefore=SystemClock.elapsedRealtime();
        SourceClock coordinatorClock=main(()->(SourceClock)field(service.sources(),"clock"));
        long sourceElapsed=coordinatorClock.monotonicMillis(),elapsedAfter=SystemClock.elapsedRealtime();
        assertTrue("Production source deadlines use Android elapsed-realtime domain",sourceElapsed>=elapsedBefore&&sourceElapsed<=elapsedAfter);
        SourceTransport productionTransport=main(()->(SourceTransport)field(service.sources(),"transport"));
        assertTrue("Synthetic isolation must allow real transport construction",productionTransport instanceof SafeHttpTransport);
        SourceClock hostClock=main(()->(SourceClock)field(field(productionTransport,"gate"),"clock"));
        elapsedBefore=SystemClock.elapsedRealtime();sourceElapsed=hostClock.monotonicMillis();elapsedAfter=SystemClock.elapsedRealtime();
        assertTrue("Scope and shared host gate must use the same Android time domain",sourceElapsed>=elapsedBefore&&sourceElapsed<=elapsedAfter);
        assertNotNull(main(service::notesBook));assertNotNull(main(service::sources));assertNull(main(service::notesProblem));
    }
    @After public void restore()throws Exception{
        if(scenario!=null)scenario.close();if(service!=null)main(()->{MediaSession session=(MediaSession)field(service,"session");if(session!=null)new MediaController(context,session.getSessionToken()).getTransportControls().stop();return null;});
        if(context!=null){context.stopService(new Intent(context,RadioService.class));await(10000,()->running()==null);instrumentation.waitForIdleSync();}
        if(isolated)for(File path:managedPaths())delete(path.toPath());
        for(Map.Entry<Path,Path> entry:moved.entrySet())Files.move(entry.getValue(),entry.getKey());if(backup!=null)delete(backup);
    }
    private List<File> managedPaths(){List<File> paths=new ArrayList<>();for(String name:new String[]{"notes","source-cas","source-network"})paths.add(new File(context.getNoBackupFilesDir(),name));for(String suffix:new String[]{"",".bak",".new"})paths.add(new File(context.getFilesDir(),"player-state-v1.json"+suffix));return paths;}
    private SourceVersionRecord installText()throws Exception{return main(()->{
        SourceRecord source=SourceRecord.create("telegram_public","telegram_public:synthetic_test07","Синтетическая станция","https://t.me/s/synthetic_test07","telegram-public-v1",false);
        SourceItem item=SourceItem.create(source.id,"id:own-post","own-post","https://t.me/synthetic_test07/1","Синтетический пост",-1,-1,1);
        String body="Это вымышленный текст для проверки фонового чтения. Он не содержит реальных новостей и личных данных. ".repeat(60);
        ExtractionResult e=new ExtractionResult(body,"","Синтетический пост","unknown","SYNTHETIC","public_preview_text","telegram-public-v1");SourceArticleFramer.Framed f=SourceArticleFramer.frame(source.name,e);
        SourceVersionRecord v=new SourceVersionRecord(source.id,item.itemKey,item.articleId,f.versionHash,e.displayTitle,e.providerTitle,source.name,item.url,e.completeness,e.reason,e.method,e.adapterVersion,SemanticTextNormalizer.VERSION,SourceArticleFramer.VERSION,f.bodyHash,f.spokenText,f.bodyStart,f.bodyEnd,1);
        service.notesBook().commitSource(source,Collections.singletonList(item),Collections.singletonList(v.withPayload(null)));service.sources().enqueue(v,true);
        int index=-1;for(int i=0;i<service.player().stations().size();i++)if(service.player().stations().get(i).id.equals(source.stationId))index=i;assertTrue(index>=0);final int selected=index;service.command(p->p.selectStation(selected));assertFalse(service.player().snapshot().playing);return v;
    });}
    @Test public void exactSavedSourceReadsThroughProductionPlayAfterOnlyActivityUnbindThenStops()throws Exception{
        SourceVersionRecord v=installText();main(()->{service.requestPlay();return null;});await(25000,()->main(()->service.player().snapshot().playing&&service.player().snapshot().currentRequestId!=null&&"Читаем на телефоне".equals(service.player().snapshot().status)));
        MediaController controller=main(()->new MediaController(context,((MediaSession)field(service,"session")).getSessionToken()));MediaSession.Token token=controller.getSessionToken();
        assertEquals(v.spokenText,main(()->service.player().snapshot().article.text));ActivityManager.RunningServiceInfo before=running();assertNotNull(before);assertTrue(before.started);assertTrue(before.foreground);assertEquals("Only the actual Activity binding",1,before.clientCount);
        main(()->{activity.finish();return null;});await(10000,()->{ActivityManager.RunningServiceInfo info=running();return info!=null&&info.clientCount==0;});
        long deadline=SystemClock.elapsedRealtime()+1500;while(SystemClock.elapsedRealtime()<deadline){ActivityManager.RunningServiceInfo info=running();assertNotNull("New source service must survive unbind",info);assertTrue(info.started);assertTrue(info.foreground);assertEquals(0,info.clientCount);assertTrue(main(()->service.player().snapshot().playing));assertEquals(token,main(()->((MediaSession)field(service,"session")).getSessionToken()));SystemClock.sleep(50);}
        main(()->{controller.getTransportControls().stop();return null;});await(10000,()->running()==null);assertEquals(PlaybackState.STATE_NONE,controller.getPlaybackState()==null?PlaybackState.STATE_NONE:controller.getPlaybackState().getState());
    }
    @Test public void savedPayloadAndPositionRestorePausedWithoutHttpThenExplicitPlayWorks()throws Exception{
        SourceVersionRecord v=installText();main(()->{service.command(p->p.seekToFraction(.35));return null;});PlayerController.SavedProgress before=main(()->service.player().savedState().progress.get(PlayerController.progressKey(service.player().snapshot().station.id,v.articleId,v.versionKey)));assertTrue(before.sentenceIndex>0);assertFalse(before.started);
        scenario.close();scenario=null;await(10000,()->running()==null);service=null;
        scenario=ActivityScenario.launch(RadioActivity.class);scenario.onActivity(a->{activity=a;});await(10000,()->main(()->activity.serviceForNotes()!=null));service=main(activity::serviceForNotes);
        assertFalse(main(()->service.player().snapshot().playing));assertEquals(v.spokenText,main(()->service.player().snapshot().article.text));assertEquals(before.sentenceIndex,(int)main(()->service.player().snapshot().sentenceIndex));assertEquals(v.versionKey,main(()->service.player().snapshot().article.version));assertTrue(main(()->service.notesBook().sources().get(0).enabled));
        main(()->{service.requestPlay();return null;});await(25000,()->main(()->service.player().snapshot().playing&&service.player().snapshot().currentRequestId!=null&&"Читаем на телефоне".equals(service.player().snapshot().status)));main(()->{service.command(PlayerController::pause);return null;});assertFalse(main(()->service.player().snapshot().playing));
    }
    @Test public void bothUiEntrypointsOpenSameCatalogueWithoutConnectingOrEnablingSound(){
        final int count=main(()->service.notesBook().sources().size());
        clickText("Мой эфир");clickText("Выбрать станцию");clickText("Добавить источник");clickText("Каталог");assertCatalogue();
        assertEquals(count,(int)main(()->service.notesBook().sources().size()));assertFalse(main(()->service.player().snapshot().playing));
        NativeDialogActions.backToMainTab(instrumentation,activity,"Источники");
        clickText("Источники");clickText("Каталог");assertCatalogue();
        assertEquals(count,(int)main(()->service.notesBook().sources().size()));assertFalse(main(()->service.player().snapshot().playing));
        NativeDialogActions.backToMainTab(instrumentation,activity,"Источники");
    }
    @Test public void coldServiceProductionCompositionPreparesAbsentNetworkChildWithoutTestMkdir()throws Exception {
        scenario.close();scenario=null;context.stopService(new Intent(context,RadioService.class));await(10000,()->running()==null);service=null;
        Path network=new File(context.getNoBackupFilesDir(),"source-network").toPath();delete(network);
        assertFalse(Files.exists(network,LinkOption.NOFOLLOW_LINKS));
        scenario=ActivityScenario.launch(RadioActivity.class);scenario.onActivity(a->{activity=a;});await(10000,()->main(()->activity.serviceForNotes()!=null));service=main(activity::serviceForNotes);
        assertTrue(Files.isDirectory(network,LinkOption.NOFOLLOW_LINKS));assertFalse(Files.isSymbolicLink(network));
        assertTrue(main(()->field(service.sources(),"transport")) instanceof SafeHttpTransport);assertNull(main(service::notesProblem));assertFalse(main(()->service.player().snapshot().playing));
    }
    private void assertCatalogue(){
        instrumentation.waitForIdleSync();android.view.accessibility.AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();assertNotNull(root);
        try{for(String label:new String[]{"Каталог источников","Своя ссылка"}){List<android.view.accessibility.AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(label);assertFalse("Catalogue must show "+label,nodes.isEmpty());for(android.view.accessibility.AccessibilityNodeInfo n:nodes)n.recycle();}}finally{root.recycle();}
    }
    private void clickText(String label){
        instrumentation.waitForIdleSync();android.view.accessibility.AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();assertNotNull(root);boolean clicked=false;
        try{List<android.view.accessibility.AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(label);for(android.view.accessibility.AccessibilityNodeInfo n:nodes){if(!clicked&&n.isClickable()&&n.isEnabled()&&n.isVisibleToUser()&&label.equalsIgnoreCase(n.getText()==null?"":n.getText().toString()))clicked=n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);n.recycle();}}finally{root.recycle();}
        assertTrue("A real visible clickable control must exist: "+label,clicked);instrumentation.waitForIdleSync();
    }
    private ActivityManager.RunningServiceInfo running(){for(ActivityManager.RunningServiceInfo info:context.getSystemService(ActivityManager.class).getRunningServices(100))if(info.service.equals(new ComponentName(context,RadioService.class)))return info;return null;}
    private <T>T main(Callable<T> task){AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();instrumentation.runOnMainSync(()->{try{value.set(task.call());}catch(Throwable error){failure.set(error);}});if(failure.get()!=null)throw new AssertionError(failure.get());return value.get();}
    private static Object field(Object target,String name)throws Exception{java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void await(long millis,BooleanSupplier condition){long end=SystemClock.elapsedRealtime()+millis;while(SystemClock.elapsedRealtime()<end){if(condition.getAsBoolean())return;SystemClock.sleep(50);}fail("Required runtime precondition did not occur within "+millis+"ms");}
    private static void delete(Path path)throws IOException{if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;try(java.util.stream.Stream<Path> entries=Files.walk(path)){for(Path p:entries.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.delete(p);}}
}

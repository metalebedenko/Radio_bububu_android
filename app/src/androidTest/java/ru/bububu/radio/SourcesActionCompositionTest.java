package ru.bububu.radio;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import okhttp3.Headers;
import org.junit.*;
import org.junit.runner.RunWith;
import ru.bububu.radio.core.*;
import ru.bububu.radio.sources.*;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Actual Activity/Service/SourcesPanel/AddSourceDialog composition, with own synthetic source transport. No HTTP, audio or mic. */
@RunWith(AndroidJUnit4.class)
public class SourcesActionCompositionTest {
    private static final String FEED="https://own-feed-action.test/rss",ARTICLE="https://www.interfax.ru/world/123";
    private Instrumentation instrumentation;private Context context;private ActivityScenario<RadioActivity> scenario;private RadioActivity activity;private RadioService service;
    private Path ownRoot;private Set<String> originalSelection;private boolean selectionExisted;
    private SourceCoordinator originalCoordinator,coordinator;private NoteBook originalBook,book;private SourceBodyCache cache;private MemoryStorage storage;private Transport transport;private SourcesPanel news;private SourcesManagementPanel management;
    private final SourceClock clock=new AndroidSourceClock();private int appends;
    private volatile Queue<Runnable> delayedCallbacks;
    private static final class MemoryStorage implements NoteBook.MigrationStorage {
        byte[] bytes;final MigrationCapsuleStore capsules;MemoryStorage(File directory){capsules=new MigrationCapsuleStore(directory);}
        public byte[] load(){return bytes==null?null:bytes.clone();}public void save(byte[] value){bytes=value.clone();}
        public MigrationStamp prepareMigration(byte[] baseline,int target){return capsules.prepare(baseline,target,new byte[][]{bytes,null,null});}
        public void verifyMigrationStamp(MigrationStamp stamp){capsules.verify(stamp);}
    }
    private final class Transport implements SourceTransport {
        final Map<String,SourceFailureInfo> paused=new ConcurrentHashMap<>();final Map<String,AtomicInteger> requests=new ConcurrentHashMap<>();
        final Map<String,String> actionTargets=new ConcurrentHashMap<>(),nextErrors=new ConcurrentHashMap<>();final Map<String,Response> scripted=new ConcurrentHashMap<>();
        public SourceFailureInfo cooldown(String url){SourceFailureInfo failure=paused.get(url);return failure!=null&&failure.retryBlocked(clock)?failure:null;}
        public SourceFailureInfo actionCooldown(String action){String target=actionTargets.get(action);return target==null?null:cooldown(target);}
        public void associateAction(String action,String targetUrl){actionTargets.put(action,targetUrl);}
        int count(String url){AtomicInteger count=requests.get(url);return count==null?0:count.get();}int total(){return requests.values().stream().mapToInt(AtomicInteger::get).sum();}
        void pause(String url){RetryAfterParser.Result retry=RetryAfterParser.parse(List.of("3600"),clock.wallMillis(),clock.monotonicMillis());paused.put(url,SourceFailureInfo.from(new HttpStatusFailure(503,new Headers.Builder().add("Retry-After","3600").build(),java.net.URI.create(url).getHost(),new RequestScope(1,1,clock,90_000),retry),clock));}
        void feed(String url,String providerId,String article){String xml="<rss version='2.0'><channel><title>Own publisher feed</title><link>https://own-feed-action.test/</link><description>Own test</description><item><guid isPermaLink='false'>"+providerId+"</guid><link>"+article+"</link><title>Own publisher headline</title></item></channel></rss>";scripted.put(url,new Response(xml.getBytes(StandardCharsets.UTF_8),url,200,"application/rss+xml","UTF-8",null,null));}
        public Response fetch(String url,Kind kind,Validators validators,RequestScope scope)throws SourceFailure {
            SourceFailureInfo known=cooldown(url);if(known!=null)throw known.rejection(clock);
            requests.computeIfAbsent(url,key->new AtomicInteger()).incrementAndGet();String next=nextErrors.remove(url);
            if(next!=null){RetryAfterParser.Result retry=RetryAfterParser.parse(List.of(next),clock.wallMillis(),clock.monotonicMillis());HttpStatusFailure failure=new HttpStatusFailure(503,new Headers.Builder().add("Retry-After",next).build(),java.net.URI.create(url).getHost(),scope,retry);paused.put(url,SourceFailureInfo.from(failure,clock));throw failure;}
            Response selected=scripted.get(url);if(selected!=null)return selected;
            String text=kind==Kind.XML?"<rss version='2.0'><channel><title>Own feed</title><link>https://allowed-ui.test/</link><description>Own test</description></channel></rss>":"<article itemprop='articleBody'><h1 itemprop='headline'>Own current</h1><p>Own fetched text.</p></article>";
            return new Response(text.getBytes(StandardCharsets.UTF_8),url,200,kind==Kind.XML?"application/rss+xml":"text/html","UTF-8","own-etag",null);
        }
    }
    @Before public void prepare()throws Exception {
        Instrumentation candidate=InstrumentationRegistry.getInstrumentation();
        Context target=candidate.getTargetContext();
        android.content.pm.InstrumentationInfo info=candidate.getContext().getPackageManager()
                .getInstrumentationInfo(candidate.getComponentName(),0);
        Unique45FixtureContract.identity(target.getPackageName(),candidate.getContext().getPackageName(),info.targetPackage);
        assertEquals("This synthetic source fixture requires a target without INTERNET",
                android.content.pm.PackageManager.PERMISSION_DENIED,target.checkSelfPermission(android.Manifest.permission.INTERNET));
        assertNull("The isolated target must have no ongoing session",running(target));
        // Assign ownership only after all gates: a rejected run must leave the target alone in @After.
        instrumentation=candidate;context=target;
        SharedPreferences ui=context.getSharedPreferences(UiStyle.PREFS,Context.MODE_PRIVATE);
        selectionExisted=ui.contains("batch_source_ids");
        if(selectionExisted)originalSelection=new HashSet<>(ui.getStringSet("batch_source_ids",Collections.emptySet()));
        ownRoot=Files.createTempDirectory(context.getCacheDir().toPath(),"action-ui-own-");
        scenario=ActivityScenario.launch(RadioActivity.class);scenario.onActivity(value->activity=value);await(10000,()->main(()->activity.serviceForNotes()!=null));service=main(activity::serviceForNotes);
        main(()->{
            originalCoordinator=service.sources();originalBook=service.notesBook();assertNotNull(originalCoordinator);assertNotNull(originalBook);
            File capsule=Files.createDirectory(ownRoot.resolve("capsule")).toFile();storage=new MemoryStorage(capsule);book=new NoteBook(storage);cache=new SourceBodyCache(Files.createDirectory(ownRoot.resolve("cache")).toFile());transport=new Transport();
            coordinator=new SourceCoordinator(book,transport,cache,new SourceCoordinator.Port(){
                public void dispatch(Runnable action){Queue<Runnable> held=delayedCallbacks;if(held!=null)held.add(action);else new Handler(Looper.getMainLooper()).post(action);}public void requireMutable(){}public void requireStorage(long bytes){}
                public void requireQueue(SourceRecord source,Article article){}public void appendStation(SourceRecord source){}public void append(NoteBook.Material material){appends++;}
                public PlayerController.SavedProgress persisted(NoteBook.Material material){return material.selectionInitialProgress;}
                public void pauseIfSelected(NoteBook.Material material){}public void remove(NoteBook.Material material){}public void projectRead(SourceRecord source,SourceItem item){}public void captureHistory(){}public void changed(){}
            },clock);
            setField(service,"sources",coordinator);setField(service,"noteBook",book);
            news=(SourcesPanel)field(activity,"newsPage");management=(SourcesManagementPanel)field(activity,"sourcesPage");return null;
        });
    }
    @After public void restore()throws Exception {
        if(instrumentation!=null)main(()->{if(coordinator!=null)coordinator.close();if(service!=null){if(originalCoordinator!=null)setField(service,"sources",originalCoordinator);if(originalBook!=null)setField(service,"noteBook",originalBook);}return null;});
        if(scenario!=null){scenario.close();context.stopService(new Intent(context,RadioService.class));await(10000,()->running()==null);instrumentation.waitForIdleSync();}
        if(ownRoot!=null)delete(ownRoot);
        if(context!=null) {
            SharedPreferences.Editor ui=context.getSharedPreferences(UiStyle.PREFS,Context.MODE_PRIVATE).edit();
            if(selectionExisted)ui.putStringSet("batch_source_ids",originalSelection);
            else ui.remove("batch_source_ids");
            assertTrue(ui.commit());
        }
    }
    private SourceRecord source(String url,String name){return SourceRecord.create("rss",url,name,url,"feed-generic-v1",false);}
    private SourceItem item(SourceRecord source,String suffix){return SourceItem.create(source.id,"own-"+suffix,"own-"+suffix,ARTICLE,"Own "+suffix,-1,-1,1);}
    private SourceVersionRecord version(SourceRecord source,SourceItem item,String body)throws Exception {
        ExtractionResult extracted=new ExtractionResult(body,item.title,item.title,"full","","article_body","interfax-v1");SourceArticleFramer.Framed framed=SourceArticleFramer.frame(source.name,extracted);
        return new SourceVersionRecord(source.id,item.itemKey,item.articleId,framed.versionHash,extracted.displayTitle,extracted.providerTitle,source.name,item.url,extracted.completeness,extracted.reason,extracted.method,extracted.adapterVersion,SemanticTextNormalizer.VERSION,SourceArticleFramer.VERSION,framed.bodyHash,framed.spokenText,framed.bodyStart,framed.bodyEnd,1);
    }
    private void showNews() {
        main(()->{activity.showNewsAll();return null;});
        await(5000,()->main(()->news.isShown() && !(boolean)field(news,"loading") && field(news,"snapshot")!=null));
        if(main(()->!buttons(news,"Показать заголовки без текста").isEmpty()))clickIn(news,"Показать заголовки без текста");
    }
    private void clickIn(View panel,String label){
        main(()->{
            Button target=onlyButton(panel,label);
            assertTrue(label,target.isShown());
            android.graphics.Rect bounds=new android.graphics.Rect();
            target.getDrawingRect(bounds);
            target.requestRectangleOnScreen(bounds,true);
            return null;
        });
        clickVisible(label);
    }
    private void checkRefreshControl(SourceRecord source,boolean enabled){
        main(()->{activity.showSourceManagement();return null;});
        clickIn(management,"Настройки · "+source.name);
        assertEquals(enabled,visibleEnabled("Обновить заголовки"));
        clickVisible("Закрыть");
    }
    private static List<Button> readers(View root){List<Button> result=new ArrayList<>();if(root instanceof Button&&"news-reader".equals(root.getTag()))result.add((Button)root);if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)result.addAll(readers(group.getChildAt(i)));}return result;}
    private Button onlyNewsReader(){List<Button> result=readers(news);assertEquals(1,result.size());return result.get(0);}


    @Test public void actualCombinedReaderBlocksArticleMissAndKeepsCachedReaderAndExplicitEnqueueAvailable()throws Exception {
        SourceVersionRecord saved=main(()->{
            SourceRecord source=source(FEED,"Own source");SourceItem missing=item(source,"missing"),local=item(source,"cached");SourceVersionRecord v=version(source,local,"Own cached body. Exact second sentence.");
            book.commitSource(source,List.of(missing,local),List.of(v.withPayload(null)));cache.put(v);transport.pause(ARTICLE);return v;
        });
        checkRefreshControl(main(()->book.source(saved.sourceId)),true);
        showNews();
        List<Button> readers=main(()->readers(news));assertEquals(2,readers.size());
        Button missing=main(()->onlyButton(news,"Загрузить")),local=main(()->onlyButton(news,"Читать"));
        assertFalse(main(missing::isEnabled));assertTrue(main(local::isEnabled));assertEquals(0,transport.total());
        main(()->{assertFalse(missing.isEnabled());local.performClick();return null;});await(5000,()->activeContains(saved.body()));
        assertEquals(0,transport.total());assertFalse(main(()->book.sourceItem(saved.sourceId,saved.itemKey).read));assertFalse(main(()->book.sourceItem(saved.sourceId,saved.itemKey).everListened));
        clickVisible("Добавить этот текст в эфир");await(5000,()->main(()->book.materials().size()==1));assertEquals(saved.spokenText,main(()->book.materials().get(0).text));assertEquals(1,appends);assertEquals(0,transport.total());
        assertFalse(main(()->service.player().snapshot().playing));
    }
    @Test public void actualBulkButtonSkipsBlockedActionContinuesAllowedAndReportsAllBlockedWithoutRequests()throws Exception {
        SourceRecord[] sources=main(()->{
            SourceRecord blocked=source(FEED,"Own blocked"),allowed=source("https://allowed-ui.test/rss","Own allowed");book.commitSource(blocked,List.of(item(blocked,"retained")));book.commitSource(allowed,List.of());transport.pause(FEED);return new SourceRecord[]{blocked,allowed};
        });
        checkRefreshControl(sources[0],false);
        clickIn(management,"Выбрать все");
        clickIn(management,"Загрузить новости");
        await(5000,()->main(()->service.downloadProgress()!=null&&service.downloadProgress().finished));
        assertEquals(0,transport.count(FEED));assertEquals(1,transport.count(sources[1].url));
        assertTrue(main(()->book.source(sources[1].id).lastSuccess>0));
        assertEquals("Own retained",main(()->book.sourceItems(sources[0].id).get(0).title));
        SourceCoordinator.DownloadProgress first=main(service::downloadProgress);
        assertEquals(2,first.processedSources);assertEquals(1,first.failed);
        assertEquals(Set.of(sources[0].id),first.failedSources);assertEquals(0,first.newVersions);
        assertTrue(activeContains("Не удалось загрузить материалы"));assertTrue(activeContains("Новых или обновлённых: 0"));
        assertTrue(activeContains("Загрузка новостей"));assertTrue(activeContains("С ошибкой: 1 из 2"));

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrumentation.waitForIdleSync();
        assertTrue(main(management::isShown));
        transport.pause(sources[1].url);
        clickIn(management,"Загрузить новости");
        await(5000,()->main(()->service.downloadProgress()!=null&&!service.downloadProgress().runId.equals(first.runId)&&service.downloadProgress().finished));
        SourceCoordinator.DownloadProgress second=main(service::downloadProgress);
        assertEquals(2,second.failed);assertEquals(Set.of(sources[0].id,sources[1].id),second.failedSources);
        assertEquals(1,transport.total());assertEquals(0,transport.count(FEED));assertEquals(0,second.saved);
        assertTrue(activeContains("Не удалось загрузить материалы"));assertTrue(activeContains("С ошибкой: 2 из 2"));
        assertFalse(visibleEnabled("Повторить неудачные"));
        assertTrue(second.sources.stream().allMatch(row->row.finished && row.failed==1));
        View download=main(()->(View)field(activity,"downloadPage"));
        clickIn(download,"Отчёт по источникам");assertTrue(activeContains("503"));
        assertTrue(main(()->book.materials().isEmpty()));assertFalse(main(()->service.player().snapshot().playing));
    }
    @Test public void actualStaleReaderCurrentButtonBlocksMissThenOpensExactAvailableCurrentPayload()throws Exception {
        SourceVersionRecord[] versions=main(()->{
            SourceRecord source=source(FEED,"Own source");SourceItem item=item(source,"versioned");SourceVersionRecord old=version(source,item,"Own old reader body.");book.commitSource(source,List.of(item),List.of(old.withPayload(null)));
            SourceVersionRecord current=version(source,item,"Own exact current body.");book.saveSourceVersion(current.withPayload(null));assertNull(book.sourceVersion(old.recordKey));transport.pause(ARTICLE);return new SourceVersionRecord[]{old,current};
        });showNews();main(()->{SourcesPanel.showReader(activity,service,versions[0]);return null;});clickVisible("Добавить этот текст в эфир");
        assertFalse(visibleEnabled("Показать текущую"));assertEquals(0,transport.total());assertTrue(main(()->book.materials().isEmpty()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrumentation.waitForIdleSync();instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrumentation.waitForIdleSync();
        main(()->{cache.put(versions[1]);SourcesPanel.showReader(activity,service,versions[0]);return null;});clickVisible("Добавить этот текст в эфир");assertTrue(visibleEnabled("Показать текущую"));clickVisible("Показать текущую");
        await(5000,()->activeContains(versions[1].body()));assertEquals(0,transport.total());assertEquals(versions[1].recordKey,main(()->book.sourceItem(versions[1].sourceId,versions[1].itemKey).latestVersionKey));assertTrue(main(()->book.materials().isEmpty()));
    }
    @Test public void actualAddFormCatalogueAndTypedUrlStayBlockedAcrossCancelAndReopen()throws Exception {
        main(()->{transport.pause(FEED);return null;});
        main(()->{activity.openAir();return null;});clickVisible("Выбрать станцию");clickVisible("Добавить источник");
        setVisibleUrl(FEED);await(3000,()->!visibleEnabled("Проверить ссылку"));
        assertTrue(visibleEnabled("Каталог"));assertEquals(0,transport.total());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrumentation.waitForIdleSync();assertEquals(0,transport.total());
        main(()->{activity.showSourceManagement();return null;});clickIn(management,"Своя ссылка");
        setVisibleUrl(FEED);await(3000,()->!visibleEnabled("Проверить ссылку"));
        assertTrue(visibleEnabled("Каталог"));assertEquals(0,transport.total());
        main(()->{transport.paused.remove(FEED);return null;});setVisibleUrl("https://allowed-ui.test/rss");
        await(3000,()->visibleEnabled("Проверить ссылку"));assertEquals(0,transport.total());clickVisible("Проверить ссылку");await(5000,()->transport.count("https://allowed-ui.test/rss")==1);assertEquals(0,transport.count(FEED));assertFalse(main(()->service.player().snapshot().playing));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrumentation.waitForIdleSync();
        int requests=transport.total();
        main(()->{transport.pause(FEED);SourceCatalogDialog.show(activity,List.of(new SourceCatalog.Entry("Вымышленный блокированный источник",FEED,false,"Только тест")));return null;});
        assertFalse(visibleEnabled("Проверить источник"));assertEquals(requests,transport.total());
        clickVisible("Закрыть");
    }
    @Test public void catalogueBackDuringPendingPreviewCannotConnectOrDeliverIntoReopenedForm()throws Exception {
        ThreadPoolExecutor workers=main(()->(ThreadPoolExecutor)field(coordinator,"workers"));
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        int requests=transport.total();byte[] before=main(()->storage.load());
        try {
            for(int i=0;i<2;i++)workers.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            AddSourceDialog form=main(()->{AddSourceDialog value=ownForm(FEED);java.lang.reflect.Method preview=AddSourceDialog.class.getDeclaredMethod("catalogue",String.class,String.class);preview.setAccessible(true);preview.invoke(value,FEED,"Вымышленное издание");return value;});
            AlertDialog pending=main(()->(AlertDialog)field(form,"dialog"));
            RequestScope scope=main(()->(RequestScope)((Map<?,?>)field(coordinator,"operations")).get("preview"));assertNotNull(scope);assertFalse(scope.isCancelled());
            assertTrue(activeContains("Проверяем источник…"));assertEquals(requests,transport.total());
            NativeDialogActions.backUntilDismissed(instrumentation,activity,pending);
            NativeDialogActions.awaitCancelledAfterDismiss(instrumentation,activity,pending,scope);
            assertEquals(requests,transport.total());assertArrayEquals(before,main(()->storage.load()));
            main(()->{AddSourceDialog.show(activity);return null;});
        } finally {release.countDown();}
        await(5000,()->workers.getActiveCount()==0&&workers.getQueue().isEmpty());
        assertEquals(requests,transport.total());assertArrayEquals(before,main(()->storage.load()));
        assertTrue(main(()->book.sources().isEmpty()));assertTrue(main(()->book.materials().isEmpty()));assertFalse(main(()->service.player().snapshot().playing));
        assertFalse(visibleEnabled("Подключить"));assertTrue(main(()->book.sources().isEmpty()));
        clickVisible("Отмена");
    }
    @Test public void connectRequiresCurrentPreviewAndLateOldResultCannotRestoreItAfterInputChange()throws Exception {
        String nextFeed="https://own-feed-action.test/new.xml";
        byte[] before=main(()->storage.load());int stations=main(()->service.player().stations().size());
        Queue<Runnable> held=new ConcurrentLinkedQueue<>();delayedCallbacks=held;
        AddSourceDialog form=main(()->{transport.feed(FEED,"first-preview",ARTICLE);transport.feed(nextFeed,"new-preview",ARTICLE);return ownForm(FEED);});
        AlertDialog dialog=main(()->(AlertDialog)field(form,"dialog"));
        assertEquals(View.GONE,(int)main(()->((EditText)field(form,"name")).getVisibility()));
        assertFalse(visibleEnabled("Подключить"));clickVisible("Проверить ссылку");
        assertFalse(visibleEnabled("Подключить"));assertTrue(activeContains("Проверяем источник…"));
        await(5000,()->held.size()==1);main(()->{held.remove().run();return null;});
        assertTrue(visibleEnabled("Подключить"));assertTrue(activeContains("Найдено заголовков: 1"));
        main(()->{((EditText)field(form,"input")).setText("@other_public_source");return null;});
        assertFalse(visibleEnabled("Подключить"));assertTrue(main(()->field(form,"preview")==null));
        assertEquals("",main(()->((TextView)field(form,"status")).getText().toString()));
        main(()->{((EditText)field(form,"input")).setText(FEED);return null;});
        clickVisible("Проверить ссылку");await(5000,()->held.size()==1);
        main(()->{((EditText)field(form,"input")).setText(nextFeed);return null;});
        assertFalse(visibleEnabled("Подключить"));
        assertEquals(0,(int)main(()->((LinearLayout)field(form,"previews")).getChildCount()));
        assertEquals("",main(()->((EditText)field(form,"name")).getText().toString()));
        main(()->{held.remove().run();return null;});
        assertFalse(visibleEnabled("Подключить"));assertTrue(main(()->field(form,"preview")==null));
        assertEquals("",main(()->((TextView)field(form,"status")).getText().toString()));
        delayedCallbacks=null;clickVisible("Проверить ссылку");
        await(5000,()->main(()->field(form,"preview")!=null));assertTrue(visibleEnabled("Подключить"));
        assertEquals(nextFeed,main(()->((SourceCoordinator.Preview)field(form,"preview")).source.url));
        assertEquals(2,transport.count(FEED));assertEquals(1,transport.count(nextFeed));assertEquals(3,transport.total());
        clickVisible("Отмена");assertFalse(main(dialog::isShowing));
        assertTrue(main(()->book.sources().isEmpty()));assertTrue(main(()->book.materials().isEmpty()));
        assertArrayEquals(before,main(()->storage.load()));assertEquals(stations,(int)main(()->service.player().stations().size()));
        assertFalse(main(()->service.player().snapshot().playing));
    }
    @Test public void duplicateFeedTitlesHaveDistinctVisibleButtonsWithoutAnExtraEditor() throws Exception {
        String site="https://own-feed-action.test/";
        String first=site+"rss?section=science",second=site+"rss?section=culture";
        String html="<html><head><link rel='alternate' type='application/rss+xml' title='Новости' href='"+first+"'>"
                +"<link rel='alternate' type='application/rss+xml' title='Новости' href='"+second+"'></head><body>Издание</body></html>";
        AddSourceDialog form=main(()->{
            transport.scripted.put(site,new SourceTransport.Response(html.getBytes(StandardCharsets.UTF_8),site,200,"text/html","UTF-8",null,null));
            transport.feed(second,"picked",ARTICLE);return ownForm(site);
        });
        clickVisible("Проверить ссылку");await(5000,()->visibleEnabled("1. Новости")&&visibleEnabled("2. Новости"));
        assertEquals(View.GONE,(int)main(()->((EditText)field(form,"name")).getVisibility()));
        assertEquals(0,transport.count(first));assertEquals(0,transport.count(second));
        clickVisible("2. Новости");await(5000,()->visibleEnabled("Подключить"));
        assertEquals(site,main(()->((EditText)field(form,"input")).getText().toString()));
        assertEquals(View.GONE,(int)main(()->((EditText)field(form,"name")).getVisibility()));
        clickVisible("Изменить название");
        assertEquals(View.VISIBLE,(int)main(()->((EditText)field(form,"name")).getVisibility()));
        main(()->{((EditText)field(form,"input")).setText("ftp://invalid.test/");return null;});
        clickVisible("Проверить ссылку");await(5000,()->main(()->!((TextView)field(form,"status")).getText().toString().equals("Проверяем источник…")));
        assertEquals(View.GONE,(int)main(()->((EditText)field(form,"name")).getVisibility()));
        assertFalse(visibleEnabled("Подключить"));assertTrue(main(book::sources).isEmpty());
        assertEquals(0,transport.count(first));assertEquals(1,transport.count(second));clickVisible("Отмена");
    }

    @Test public void singleAdvertisedFeedPreservesInputAndConnectOpensOnlyCheckedPublication() throws Exception {
        String site="https://own-feed-action.test/";
        String discovery="<html><head><link rel='alternate' type='application/rss+xml' title='Новости' href='/rss'></head><body>Издание</body></html>";
        String other="https://www.interfax.ru/world/124";
        String feed="<rss version='2.0'><channel><title>Test feed</title><link>"+site+"</link><description>Two</description>"
                +"<item><guid isPermaLink='false'>other</guid><link>"+other+"</link><title>Other story</title></item>"
                +"<item><guid isPermaLink='false'>picked</guid><link>"+ARTICLE+"</link><title>Selected story</title></item></channel></rss>";
        AddSourceDialog form=main(()->{
            transport.scripted.put(site,new SourceTransport.Response(discovery.getBytes(StandardCharsets.UTF_8),site,200,"text/html","UTF-8",null,null));
            transport.scripted.put(FEED,new SourceTransport.Response(feed.getBytes(StandardCharsets.UTF_8),FEED,200,"application/rss+xml","UTF-8",null,null));
            return ownForm(site);
        });
        clickVisible("Проверить ссылку");
        await(5000,()->main(()->{
            SourceCoordinator.Preview preview=(SourceCoordinator.Preview)field(form,"preview");
            return preview!=null && !preview.discovery();
        }));
        assertEquals(site,main(()->((EditText)field(form,"input")).getText().toString()));
        assertEquals(1,transport.count(site));assertEquals(1,transport.count(FEED));
        assertTrue(main(book::sources).isEmpty());
        clickVisible("Проверить текст: Selected story");
        await(5000,()->visibleEnabled("Сохранить выбранный текст"));
        clickVisible("Сохранить выбранный текст");
        await(5000,()->activeContains("Own fetched text."));
        SourceRecord source=main(()->book.sources().get(0));
        assertEquals(source.id,main(()->field(news,"selectedSource")));
        assertTrue(main(book::materials).isEmpty());
        assertFalse(main(()->service.player().snapshot().playing));
        clickVisible("Добавить этот текст в эфир");
        await(5000,()->visibleEnabled("Открыть эту новость в радио"));
        SourceItem selected=main(()->book.sourceItems(source.id).stream().filter(item->ARTICLE.equals(item.url)).findFirst().get());
        assertEquals(0,transport.count(other));
        assertEquals(1,(int)main(()->book.materials().size()));
        assertEquals(selected.latestVersionKey,main(()->book.material(source.stationId,selected.articleId).sourceVersionKey));
        assertFalse(main(()->service.player().snapshot().playing));
        assertEquals(1,transport.count(ARTICLE));
        clickVisible("Закрыть");
    }

    @Test public void actualPreviewBodyButtonRetriesSameDeliveredPreviewAfterFinitePauseWithoutRefetchingFeed()throws Exception {
        AddSourceDialog form=main(()->{transport.feed(FEED,"own-preview",ARTICLE);transport.nextErrors.put(ARTICLE,"5");return ownForm(FEED);});
        clickVisible("Проверить ссылку");await(5000,()->main(()->field(form,"preview")!=null));
        SourceCoordinator.Preview delivered=main(()->(SourceCoordinator.Preview)field(form,"preview"));assertNotNull(delivered);assertEquals(1,transport.count(FEED));assertEquals(0,transport.count(ARTICLE));
        clickVisible("Проверить текст");await(3000,()->!visibleEnabled("Проверить текст"));
        assertSame(delivered,main(()->field(form,"preview")));assertEquals(1,transport.count(FEED));assertEquals(1,transport.count(ARTICLE));assertNotNull(main(()->coordinator.previewTextFailure(delivered,0)));
        // The actual Android clock and production button timer expire together. Expiry performs no HTTP.
        await(10000,()->visibleEnabled("Проверить текст"));assertSame(delivered,main(()->field(form,"preview")));assertEquals(2,transport.total());
        clickVisible("Проверить текст");await(5000,()->main(()->{SourceCoordinator.Preview current=(SourceCoordinator.Preview)field(form,"preview");return current!=null&&current.loaded!=null;}));
        SourceCoordinator.Preview loaded=main(()->(SourceCoordinator.Preview)field(form,"preview"));assertEquals("Own fetched text.",loaded.loaded.body);assertEquals(1,transport.count(FEED));assertEquals(2,transport.count(ARTICLE));assertEquals(3,transport.total());assertTrue(main(book::sources).isEmpty());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrumentation.waitForIdleSync();
    }
    @Test public void dynamicFeedArticleFailureBlocksOnlyActualReaderActionAndKeepsSavedPayloadAvailable()throws Exception {
        String target="https://ria.ru/20261012/own-article.html";
        SourceItem original=main(()->{
            SourceRecord source=source(FEED,"Own dynamic source");SourceItem item=SourceItem.create(source.id,"own-dynamic","own-dynamic","https://unsupported-old.test/story","Own dynamic",-1,-1,1);
            book.commitSource(source,List.of(item));transport.feed(FEED,"own-dynamic",target);transport.nextErrors.put(target,"3600");return item;
        });
        byte[] before=main(()->storage.bytes.clone());AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> failed=new AtomicReference<>();main(()->{coordinator.loadText(original.sourceId,original.itemKey,failed::set);return null;});await(5000,()->failed.get()!=null);
        assertFalse(failed.get().successful());assertEquals(503,failed.get().failure.status);assertEquals(1,transport.count(FEED));assertEquals(1,transport.count(target));assertArrayEquals(before,main(()->storage.bytes));
        assertNotNull(main(()->coordinator.readerFailure(original.sourceId,original.itemKey)));assertNull(main(()->coordinator.refreshFailure(book.source(original.sourceId))));assertNull(transport.cooldown(FEED));showNews();assertFalse(main(()->onlyNewsReader().isEnabled()));
        AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> repeated=new AtomicReference<>();main(()->{coordinator.cancelUi();coordinator.loadText(original.sourceId,original.itemKey,repeated::set);return null;});await(5000,()->repeated.get()!=null);assertFalse(repeated.get().successful());assertEquals(503,repeated.get().failure.status);assertEquals(2,transport.total());assertArrayEquals(before,main(()->storage.bytes));
        main(()->{
            SourceItem replacement=SourceItem.create(original.sourceId,original.itemKey,original.providerId,ARTICLE,"Own replacement target",-1,-1,1);
            book.mergeSourceItems(original.sourceId,List.of(replacement),List.of(),null,null,1,null);
            assertNull("Old reader fingerprint must not disable a newly published unpaused target",coordinator.readerFailure(original.sourceId,original.itemKey));
            assertNotNull(transport.cooldown(target));news.refresh();return null;
        });assertTrue(main(()->onlyNewsReader().isEnabled()));assertEquals(2,transport.total());
        SourceVersionRecord local=main(()->{SourceVersionRecord version=version(book.source(original.sourceId),book.sourceItem(original.sourceId,original.itemKey),"Own exact local dynamic body.");book.saveSourceVersion(version.withPayload(null));cache.put(version);news.refresh();return version;});
        assertTrue(main(()->onlyNewsReader().isEnabled()));main(()->{onlyNewsReader().performClick();return null;});await(5000,()->activeContains(local.body()));assertEquals(2,transport.total());assertEquals(local.recordKey,main(()->book.sourceItem(original.sourceId,original.itemKey).latestVersionKey));
    }
    @Test public void actualCurrentReaderDialogHandlesSynchronousClosedCoordinatorWithoutCrashingOrFetching()throws Exception {
        SourceVersionRecord[] versions=main(()->{
            SourceRecord source=source(FEED,"Own source");SourceItem item=item(source,"closed-owner");SourceVersionRecord old=version(source,item,"Own old dialog body.");book.commitSource(source,List.of(item),List.of(old.withPayload(null)));
            SourceVersionRecord current=version(source,item,"Own current closed-service body.");book.saveSourceVersion(current.withPayload(null));cache.put(current);return new SourceVersionRecord[]{old,current};
        });showNews();main(()->{SourcesPanel.showReader(activity,service,versions[0]);return null;});clickVisible("Добавить этот текст в эфир");assertTrue(visibleEnabled("Показать текущую"));byte[] before=main(()->storage.bytes.clone());
        main(()->{
            SourceCoordinator.Port fixturePort=(SourceCoordinator.Port)field(coordinator,"port");coordinator.close();coordinator=new SourceCoordinator(book,transport,cache,fixturePort,clock);coordinator.close();setField(service,"sources",coordinator);return null;
        });clickVisible("Показать текущую");instrumentation.waitForIdleSync();
        assertFalse(main(activity::isFinishing));assertSame(service,main(activity::serviceForNotes));assertEquals(0,transport.total());assertArrayEquals(before,main(()->storage.bytes));assertTrue(main(book::materials).isEmpty());
    }
    private AddSourceDialog ownForm(String value)throws Exception {
        Constructor<AddSourceDialog> constructor=AddSourceDialog.class.getDeclaredConstructor(RadioActivity.class,RadioService.class);constructor.setAccessible(true);AddSourceDialog form=constructor.newInstance(activity,service);
        AlertDialog dialog=(AlertDialog)field(form,"dialog");dialog.show();((EditText)field(form,"input")).setText(value);return form;
    }
    private static List<Button> buttons(View root,String label){List<Button> result=new ArrayList<>();if(root instanceof Button&&(matchesLabel(((Button)root).getText(),label)||matchesLabel(root.getContentDescription(),label)))result.add((Button)root);if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)result.addAll(buttons(group.getChildAt(i),label));}return result;}
    private static Button onlyButton(View root,String label){List<Button> values=buttons(root,label);assertEquals(label,1,values.size());return values.get(0);}
    private void setVisibleUrl(String text){
        instrumentation.waitForIdleSync();android.view.accessibility.AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();assertNotNull(root);
        ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> pending=new ArrayDeque<>();pending.add(root);boolean changed=false;
        while(!pending.isEmpty()) {android.view.accessibility.AccessibilityNodeInfo node=pending.remove();for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo child=node.getChild(i);if(child!=null)pending.add(child);}
            if(!changed&&node.isEditable()&&node.getHintText()!=null&&node.getHintText().toString().startsWith("Адрес сайта, ленты")){Bundle args=new Bundle();args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);changed=node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,args);}node.recycle();
        }
        assertTrue("Actual source URL input required",changed);instrumentation.waitForIdleSync();
    }
    private boolean activeContains(String text){instrumentation.waitForIdleSync();android.view.accessibility.AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();if(root==null)return false;try{List<android.view.accessibility.AccessibilityNodeInfo> values=root.findAccessibilityNodeInfosByText(text);boolean found=!values.isEmpty();for(android.view.accessibility.AccessibilityNodeInfo value:values)value.recycle();return found;}finally{root.recycle();}}
    private boolean visibleEnabled(String label){instrumentation.waitForIdleSync();android.view.accessibility.AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();assertNotNull(root);boolean enabled=false;try{List<android.view.accessibility.AccessibilityNodeInfo> values=root.findAccessibilityNodeInfosByText(label);boolean found=false;for(android.view.accessibility.AccessibilityNodeInfo value:values){if(value.isVisibleToUser()&&value.isClickable()&&(matchesLabel(value.getText(),label)||matchesLabel(value.getContentDescription(),label))){found=true;enabled|=value.isEnabled();}value.recycle();}assertTrue("Actual visible control required: "+label,found);return enabled;}finally{root.recycle();}}
    private void clickVisible(String label){instrumentation.waitForIdleSync();android.view.accessibility.AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();assertNotNull(root);boolean clicked=false;try{for(android.view.accessibility.AccessibilityNodeInfo value:root.findAccessibilityNodeInfosByText(label)){if(!clicked&&value.isClickable()&&value.isEnabled()&&value.isVisibleToUser()&&(matchesLabel(value.getText(),label)||matchesLabel(value.getContentDescription(),label)))clicked=value.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);value.recycle();}}finally{root.recycle();}assertTrue("Actual enabled control required: "+label,clicked);instrumentation.waitForIdleSync();}
    private static boolean matchesLabel(CharSequence text,String label){return text!=null&&text.toString().toLowerCase(Locale.ROOT).startsWith(label.toLowerCase(Locale.ROOT));}
    private <T>T main(Callable<T> task){AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();instrumentation.runOnMainSync(()->{try{value.set(task.call());}catch(Throwable failure){error.set(failure);}});if(error.get()!=null)throw new AssertionError(error.get());return value.get();}
    private void await(long millis,BooleanSupplier condition){long end=SystemClock.elapsedRealtime()+millis;while(SystemClock.elapsedRealtime()<end){if(condition.getAsBoolean())return;SystemClock.sleep(50);}fail("Required precondition did not occur within "+millis+"ms");}
    private ActivityManager.RunningServiceInfo running(){return running(context);}
    private ActivityManager.RunningServiceInfo running(Context target){for(ActivityManager.RunningServiceInfo value:target.getSystemService(ActivityManager.class).getRunningServices(100))if(value.service.equals(new ComponentName(target,RadioService.class)))return value;return null;}
    private static Object field(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void setField(Object target,String name,Object value)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static void delete(Path path)throws IOException{if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;try(java.util.stream.Stream<Path> entries=Files.walk(path)){for(Path entry:entries.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.delete(entry);}}
}

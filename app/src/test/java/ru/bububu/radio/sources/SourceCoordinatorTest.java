package ru.bububu.radio.sources;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import ru.bububu.radio.core.*;

/** Executes the production worker/parser/cache/Book publication bridge, never an Android XML stub. */
public class SourceCoordinatorTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final BlockingQueue<Runnable> owner=new LinkedBlockingQueue<>();
    private final MutableClock clock=new MutableClock();
    private static final class MutableClock implements SourceClock { volatile long time=1;public long monotonicMillis(){return time;}public long wallMillis(){return 1_700_000_000_000L+time;} }
    private Storage storage;private SourceCoordinator coordinator;private NoteBook book;private Port port;private Transport transport;private SourceBodyCache cache;
    private final class Storage implements NoteBook.MigrationStorage {
        byte[] bytes;boolean failSave,unreadable;final File folder;final MigrationCapsuleStore capsules;
        Storage()throws Exception{folder=temp.newFolder();capsules=new MigrationCapsuleStore(folder);}
        public byte[] load(){if(unreadable)throw new IllegalStateException("Unreadable outcome");return bytes;}public void save(byte[] value){if(failSave){unreadable=true;throw new IllegalStateException("Unknown save");}bytes=value.clone();}
        public MigrationStamp prepareMigration(byte[] baseline,int target){return capsules.prepare(baseline,target,new byte[][]{bytes,null,null});}
        public void verifyMigrationStamp(MigrationStamp stamp){capsules.verify(stamp);}
        public Format4BaselineRef prepareFormat4Upgrade(byte[] baseline,int target){return capsules.prepareFormat4(baseline,target,new byte[][]{bytes,null,null});}
        public void verifyFormat4Baseline(Format4BaselineRef ref){capsules.verifyFormat4(ref);}
    }
    private final class Port implements SourceCoordinator.Port {
        int stations,appends,removes,reads,changes;boolean refuseWrites,failAppend;PlayerController.SavedProgress persisted;
        public void dispatch(Runnable action){owner.add(action);}public void requireMutable(){if(refuseWrites)throw new IllegalStateException("read only");}public void requireStorage(long bytes){}public void requireQueue(SourceRecord s,Article a){}
        public void appendStation(SourceRecord s){stations++;}public void append(NoteBook.Material m){if(failAppend)throw new IllegalStateException("projection failed");appends++;}public void remove(NoteBook.Material m){removes++;}
        public PlayerController.SavedProgress persisted(NoteBook.Material m){if(persisted==null)throw new IllegalStateException("unknown");return persisted;}
        public void pauseIfSelected(NoteBook.Material m){}public void projectRead(SourceRecord s,SourceItem i){reads++;}public void captureHistory(){}public void changed(){changes++;}
    }
    private final class Transport implements SourceTransport {
        String text="Первый вымышленный пост. Второе предложение.";int count;boolean failExample,failDw;CountDownLatch hold,entered;String cooldownTarget;int cooldownAtRequest=Integer.MAX_VALUE;final Map<String,Response> pages=new ConcurrentHashMap<>();final List<String> requested=Collections.synchronizedList(new ArrayList<>());
        public SourceFailureInfo cooldown(String url){
            cooldownTarget=url;
            return count>=cooldownAtRequest?SourceFailureInfo.from(new CooldownFailure("HOST_COOLDOWN",60_000,clock,false),clock):null;
        }
        public Response fetch(String url,Kind kind,Validators validators,RequestScope scope)throws SourceFailure {
            count++;requested.add(url);if(pages.containsKey(url))return pages.get(url);if(entered!=null)entered.countDown();if(hold!=null)try{if(!hold.await(3,TimeUnit.SECONDS))throw new SourceFailure("TEST_TIMEOUT");}catch(InterruptedException e){throw new SourceFailure("CANCELLED",e);}
            if(failDw&&DwNetworkDiagnostics.INPUT.equals(url))throw new SourceFailure("NETWORK_ERROR",new javax.net.ssl.SSLHandshakeException("PRIVATE_MESSAGE_NOT_FOR_DIAGNOSTICS"));
            if(failExample&&url.endsWith("/example"))throw new SourceFailure("HTTP_503");
            String username=url.substring(url.lastIndexOf('/')+1);
            String html="<div class='tgme_channel_info_header_username'>@"+username+"</div><div class='tgme_channel_info_header_title'>Пример</div><div class='tgme_widget_message' data-post='"+username+"/12'><div class='tgme_widget_message_text js-message_text'>"+text+"</div><a class='tgme_widget_message_date'><time datetime='2026-10-12T12:00:00Z'></time></a></div>";
            return new Response(html.getBytes(java.nio.charset.StandardCharsets.UTF_8),"https://t.me/s/"+username,200,"text/html","UTF-8",null,null);
        }
    }
    @Before public void create()throws Exception{storage=new Storage();book=new NoteBook(storage);port=new Port();transport=new Transport();cache=new SourceBodyCache(temp.newFolder());coordinator=new SourceCoordinator(book,transport,cache,port,clock);}
    @After public void close(){coordinator.close();}
    private <T> T await(AtomicReference<SourceCoordinator.Result<T>> result)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(result.get()==null&&System.nanoTime()<deadline){Runnable task=owner.poll(100,TimeUnit.MILLISECONDS);if(task!=null)task.run();}
        assertNotNull("Owner delivery required",result.get());assertNull(result.get().error);return result.get().value;
    }
    private static final String PROFILE_ARTICLE="https://www.vedomosti.ru/sport/news/2026/11/05/123-test";
    private static final String PROFILE_SITE="https://www.vedomosti.ru/technology";
    private static final String PROFILE_FEED="https://www.vedomosti.ru/rss/news";
    private static SourceTransport.Response fixtureResponse(String url,String mime,String text){
        return new SourceTransport.Response(text.getBytes(java.nio.charset.StandardCharsets.UTF_8),url,200,mime,"UTF-8",null,null);
    }
    private SourceCoordinator.Preview chosenOfficialFeed()throws Exception{
        String rss="<rss version='2.0'><channel><title>Новости</title><link>"+PROFILE_SITE+"</link><description>Тест</description>"
                +"<item><guid isPermaLink='false'>picked</guid><link>"+PROFILE_ARTICLE+"</link><title>Выбранная новость</title>"
                +"<pubDate>Thu, 05 Nov 2026 09:00:00 +0300</pubDate><description>Анонс новости.</description></item></channel></rss>";
        String head="<link rel='alternate' type='application/rss+xml' title='Новости' href='"+PROFILE_FEED+"'>"
                +"<link rel='alternate' type='application/rss+xml' title='Культура' href='/rss/sample'>";
        transport.pages.put(PROFILE_FEED,fixtureResponse(PROFILE_FEED,"application/rss+xml",rss));
        transport.pages.put(PROFILE_SITE,fixtureResponse(PROFILE_SITE,"text/html","<html><head>"+head+"</head><body>Раздел</body></html>"));
        AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> site=new AtomicReference<>();
        coordinator.preview(PROFILE_SITE,false,null,site::set);
        SourceCoordinator.Preview choices=await(site);assertTrue(choices.discovery());
        AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> feed=new AtomicReference<>();
        coordinator.chooseFeed(choices,PROFILE_FEED,null,feed::set);
        return await(feed);
    }
    @Test public void chosenFeedUsesExistingOfficialReaderAndPublishesExactlyTheSelectedVersion()throws Exception{
        book.enable45();SourceRecord official=SourceProfiles45.byId("vedomosti-open-briefs").seed();
        book.commitSource(official,List.of());
        String body="Точный полный текст выбранной новости.";
        String html="<html><head><link rel='canonical' href='"+PROFILE_ARTICLE+"'><script type='application/ld+json'>"
                +"{\"@type\":\"NewsArticle\",\"url\":\""+PROFILE_ARTICLE+"\",\"headline\":\"Выбранная новость\",\"articleBody\":\""+body+"\"}</script></head>"
                +"<body><article><h1>Выбранная новость</h1><p>Общий HTML не должен заменить официальный текст.</p></article></body></html>";
        transport.pages.put(PROFILE_ARTICLE,fixtureResponse(PROFILE_ARTICLE,"text/html",html));
        SourceCoordinator.Preview feed=chosenOfficialFeed();
        assertEquals(official.id,feed.source.id);
        AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> read=new AtomicReference<>();
        coordinator.previewText(feed,0,read::set);SourceCoordinator.Preview selection=await(read);
        assertEquals("source45-jsonld",selection.loaded.method);assertEquals(body,selection.loaded.body);
        SourceRecord persisted=coordinator.connect(selection,"Чужое имя");
        assertEquals(official.id,persisted.id);assertEquals(official.stationId,persisted.stationId);assertEquals(official.name,persisted.name);
        SourceVersionRecord exact=coordinator.connectedPreviewVersion(selection,persisted);assertNotNull(exact);
        AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> disk=new AtomicReference<>();coordinator.savedText(exact.recordKey,disk::set);
        assertEquals(body,await(disk).body());assertEquals(1,book.sources().size());assertTrue(book.materials().isEmpty());
    }
    @Test public void chosenOfficialFeedRejectsReadableGenericHtmlWithoutPublisherEvidenceBeforeConnect()throws Exception{
        book.enable45();SourceRecord official=SourceProfiles45.byId("vedomosti-open-briefs").seed();book.commitSource(official,List.of());
        SourceCoordinator.Preview feed=chosenOfficialFeed();byte[] before=storage.bytes.clone();
        String html="<html><head><link rel='canonical' href='"+PROFILE_ARTICLE+"'></head><body><article><h1>Новость</h1>"
                +"<p>Первый абзац.</p><p>Второй абзац.</p></article></body></html>";
        transport.pages.put(PROFILE_ARTICLE,fixtureResponse(PROFILE_ARTICLE,"text/html",html));
        AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> checked=new AtomicReference<>();coordinator.previewText(feed,0,checked::set);
        while(checked.get()==null){Runnable task=owner.poll(4,TimeUnit.SECONDS);assertNotNull(task);task.run();}
        assertFalse(checked.get().successful());assertNull(checked.get().value);assertEquals("ARTICLEBODY_COUNT",checked.get().error);
        assertArrayEquals(before,storage.bytes);assertTrue(book.sourceItems(official.id).isEmpty());
    }
    @Test public void genericCheckedBodyCannotBeSilentlyMergedIntoAnExistingOfficialStation()throws Exception {
        book.enable45();SourceRecord official=SourceProfiles45.byId("vedomosti-open-briefs").seed();book.commitSource(official,List.of());
        SourceRecord generic=SourceRecord.create("rss",PROFILE_FEED,"Пользовательская лента",PROFILE_FEED,"generic",false);
        FeedItem item=new FeedItem("picked",PROFILE_ARTICLE,"Выбранная новость",null,null,null,null,null);
        String html="<article><h1>Выбранная новость</h1><p>Публичный фрагмент без издательской привязки.</p><p>Второй абзац.</p></article>";
        ExtractionResult extracted=HtmlIndexReader.article(PROFILE_FEED,PROFILE_ARTICLE,fixtureResponse(PROFILE_ARTICLE,"text/html",html),new RequestScope(1,1));
        // Recreate the old selected generic preview, with current Book authority, before any publication.
        SourceCoordinator.Preview mixed=new SourceCoordinator.Preview(generic,new FeedDocument(generic.name,PROFILE_FEED,"rss",List.of(item),false),
                List.of(),0,extracted,0,90_000,book.authorityEpoch(),port.radioEpoch());
        byte[] before=storage.bytes.clone();
        assertThrows(IllegalStateException.class,()->coordinator.connect(mixed,generic.name));
        assertArrayEquals(before,storage.bytes);assertTrue(book.sourceItems(official.id).isEmpty());
    }
    @Test public void manualInputRecognizesOnlyExactConnectedEndpointsAndDeclaredAliases()throws Exception{
        book.enable45();
        SourceProfiles45.Profile rt=SourceProfiles45.byId("rt-briefs");SourceRecord connected=rt.seed();
        book.commitSource(connected,List.of());
        String alias=rt.aliases.get(0);
        transport.pages.put(alias,fixtureResponse(alias,"application/rss+xml","<rss version='2.0'><channel><title>Тест</title></channel></rss>"));
        AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> byAlias=new AtomicReference<>();
        coordinator.preview(alias,false,null,byAlias::set);
        assertEquals(connected.id,await(byAlias).source.id);
        String adjacent="https://russian.rt.com/other-section";
        String page="<article><h1>Другая страница</h1><p>Первый абзац.</p><p>Второй абзац.</p></article>";
        transport.pages.put(adjacent,fixtureResponse(adjacent,"text/html",page));
        AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> other=new AtomicReference<>();
        coordinator.preview(adjacent,false,null,other::set);SourceCoordinator.Preview generic=await(other);
        assertTrue(generic.experimentalPage());assertFalse(generic.source.catalog);assertNotEquals(connected.id,generic.source.id);
        String referral="https://example.org/publisher";
        transport.pages.put(referral,fixtureResponse(alias,"text/html","<div>Непроверенный ответ редиректа</div>"));
        AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> redirect=new AtomicReference<>();
        coordinator.preview(referral,false,null,redirect::set);
        assertEquals(connected.id,await(redirect).source.id);
        assertEquals(Arrays.asList(alias,adjacent,referral,alias),transport.requested);
    }
    private SourceCoordinator.Preview preview()throws Exception{AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> result=new AtomicReference<>();coordinator.preview("@example",true,null,result::set);return await(result);}
    private SourceRecord connect()throws Exception{return coordinator.connect(preview(),"Моя станция");}
    private SourceVersionRecord load(SourceRecord source)throws Exception{AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> result=new AtomicReference<>();coordinator.loadText(source.id,book.sourceItems(source.id).get(0).itemKey,result::set);return await(result);}
    private static final String PAGE="https://any-public.example/read?id=One&lang=ru";
    private void page(String html){transport.pages.put(PAGE,new SourceTransport.Response(html.getBytes(java.nio.charset.StandardCharsets.UTF_8),PAGE,200,"text/html","UTF-8",null,null));}
    private SourceCoordinator.Preview pagePreview()throws Exception{AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> result=new AtomicReference<>();coordinator.preview(PAGE+"#section",false,null,result::set);return await(result);}
    private void refreshPage(SourceRecord source)throws Exception{AtomicReference<SourceCoordinator.Result<SourceRecord>> r=new AtomicReference<>();coordinator.refresh(source.id,r::set);await(r);}
    private void pumpUntil(java.util.function.BooleanSupplier done)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!done.getAsBoolean()&&System.nanoTime()<end){Runnable task=owner.poll(100,TimeUnit.MILLISECONDS);if(task!=null)task.run();}assertTrue("Owner operation finished",done.getAsBoolean());}
    @Test public void c13PreviewAndCooldownShareBareSiteAndTelegramRouting()throws Exception{
        page("<title>Источник</title><link rel='alternate' type='application/rss+xml' href='/feed.xml'><main>Текст.</main>");String bare=PAGE.substring("https://".length());coordinator.cooldown(bare,false);assertEquals(PAGE,transport.cooldownTarget);AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> site=new AtomicReference<>();coordinator.preview(bare,false,null,site::set);assertTrue(await(site).discovery());assertEquals(PAGE,transport.requested.get(0));
        for(String input:List.of("@Example","t.me/Example")){coordinator.cooldown(input,false);assertEquals("https://t.me/s/example",transport.cooldownTarget);AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> tg=new AtomicReference<>();coordinator.preview(input,false,null,tg::set);SourceCoordinator.Preview p=await(tg);assertEquals("telegram_public",p.source.type);assertEquals("telegram_public:example",p.source.key);assertFalse(p.experimentalPage());}assertTrue(book.sources().isEmpty());
    }
    @Test public void c13ExplicitDownloadCancelPublishesTerminalDismissibleStateAndLateReplyIsInert()throws Exception{
        SourceRecord source=connect();transport.entered=new CountDownLatch(1);transport.hold=new CountDownLatch(1);List<SourceCoordinator.DownloadProgress> events=new ArrayList<>();coordinator.downloadBatch(List.of(source.id),3,events::add);pumpUntil(()->transport.entered.getCount()==0);String run=events.get(0).runId;assertFalse(coordinator.cancelDownload("stale-run"));assertTrue(coordinator.batchRunning());assertTrue(coordinator.cancelDownload(run));SourceCoordinator.DownloadProgress cancelled=events.get(events.size()-1);assertTrue(cancelled.finished);assertTrue(cancelled.cancelled);assertFalse(coordinator.batchRunning());assertFalse(coordinator.cancelDownload(run));
        RadioResultState card=new RadioResultState();assertTrue(card.errorVisible(run,cancelled.cancelled));card.dismiss(run);assertFalse(card.visible(run,true,true,false,false));int count=events.size();transport.hold.countDown();Runnable late=owner.poll(2,TimeUnit.SECONDS);if(late!=null)late.run();assertEquals(count,events.size());assertTrue(book.materials().isEmpty());
        transport.entered=null;transport.hold=null;AtomicReference<SourceCoordinator.DownloadProgress> fresh=new AtomicReference<>();coordinator.downloadBatch(List.of(source.id),3,fresh::set);pumpUntil(()->fresh.get()!=null&&fresh.get().finished);assertFalse(fresh.get().cancelled);assertNotEquals(run,fresh.get().runId);assertTrue(fresh.get().saved>0);
    }
    @Test public void c13PartialDownloadSurvivesCancelAndReentrantReplacementCannotBeCancelledByOldTap()throws Exception{
        SourceRecord source=connect();AtomicReference<SourceCoordinator.DownloadProgress> oldTerminal=new AtomicReference<>(),fresh=new AtomicReference<>();boolean[] cancelled={false};coordinator.downloadBatch(List.of(source.id),3,p->{
            if(p.finished&&p.cancelled){oldTerminal.set(p);coordinator.downloadBatch(List.of(source.id),3,fresh::set);}
            else if(p.saved>0&&!cancelled[0]){cancelled[0]=true;assertTrue(coordinator.cancelDownload(p.runId));}
        });pumpUntil(()->oldTerminal.get()!=null&&fresh.get()!=null&&fresh.get().finished);assertTrue(oldTerminal.get().saved>0);assertFalse(oldTerminal.get().savedRecordKeys.isEmpty());assertFalse(coordinator.cancelDownload(oldTerminal.get().runId));assertFalse(fresh.get().cancelled);assertEquals(load(source).recordKey,book.sourceItems(source.id).get(0).latestVersionKey);assertTrue(book.materials().isEmpty());
    }
    @Test public void c13SilentUiTeardownStillDoesNotDeliverTerminalCallbacks()throws Exception{
        SourceRecord source=connect();transport.entered=new CountDownLatch(1);transport.hold=new CountDownLatch(1);List<SourceCoordinator.DownloadProgress> events=new ArrayList<>();coordinator.downloadBatch(List.of(source.id),3,events::add);pumpUntil(()->transport.entered.getCount()==0);int count=events.size();coordinator.cancelUi();assertFalse(coordinator.batchRunning());assertEquals(count,events.size());transport.hold.countDown();Runnable late=owner.poll(2,TimeUnit.SECONDS);if(late!=null)late.run();assertEquals(count,events.size());
    }
    @Test public void c12PagePreviewCancelAndExplicitConfirmAreSeparateDurableSteps()throws Exception{
        page("<title>Страница</title><main><p>Первый текст страницы.</p></main>");SourceCoordinator.Preview p=pagePreview();assertTrue(p.experimentalPage());assertNotNull(p.loaded);assertEquals(0,p.loadedIndex);assertTrue(book.sources().isEmpty());assertEquals(0,port.stations);assertTrue(book.materials().isEmpty());coordinator.invalidatePreview();assertThrows(IllegalStateException.class,()->coordinator.connect(p,"Страница"));assertTrue(book.sources().isEmpty());
        SourceRecord source=coordinator.connect(pagePreview(),"Открытая страница");assertEquals(1,book.sources().size());assertEquals(1,book.sourceItems(source.id).size());assertEquals(PAGE,source.url);assertEquals(PAGE,source.key);assertEquals(1,port.stations);assertEquals(0,port.appends);assertTrue(book.materials().isEmpty());assertEquals("Первый текст страницы.",load(source).body());
    }
    private SourceCoordinator.DownloadProgress quiet(SourceRecord source)throws Exception{
        AtomicReference<SourceCoordinator.DownloadProgress> download=new AtomicReference<>();
        coordinator.downloadBatch(List.of(source.id),3,download::set);
        pumpUntil(()->download.get()!=null&&download.get().finished);
        return download.get();
    }
    @Test public void explicitIndexDownloadUpgradesOldHtmlWithoutChangingTheSelectedVersion()throws Exception{
        book.enable45();
        String url="https://journal.example/story/one",site="https://journal.example/news",title="Первый материал";
        SourceRecord source=SourceRecord.create(HtmlIndexReader.TYPE,site,"Издание",site,HtmlIndexReader.ADAPTER,false);
        FeedItem entry=new FeedItem(null,url,title,null,null,null,null,null);
        SourceItem item=SourceItem.create(source.id,SourceCoordinator.identity(entry),null,url,title,-1,-1,1);
        String legacy="experimental-html50-v1";
        SourcePublication45.Fact unknown=SourcePublication45.unknown("","experimental_html",url,"page");
        SourcePublication45 pub=new SourcePublication45(unknown,List.of(unknown),SourceLimits.sha256(legacy+":semantic-main:public-page:unknown-publication"),
                legacy,"",SourceLimits.sha256("previous HTML"),"","",url,"experimental-html-page");
        ExtractionResult oldBody=new ExtractionResult("Подпись редакции. Старый текст.",title,title,"unknown","EXPERIMENTAL_HTML_PAGE","experimental_html",legacy).withPublication(pub);
        SourceVersionRecord old=SourceVersionRecord.source45(source,item,oldBody,pub,SourcePublication45.Context.UNKNOWN_MANUAL,1);
        cache.put(old);book.commitSource(source,List.of(item.withLatest(old.recordKey)),List.of(old.withPayload(null)));
        assertEquals(old.recordKey,load(source).recordKey);assertEquals(0,transport.count);
        assertThrows(IllegalArgumentException.class,()->coordinator.enqueue(old,false));
        assertTrue(coordinator.enqueue(old,true));
        String listing="<main><h2><a href='"+url+"'>"+title+"</a></h2></main>";
        transport.pages.put(site,fixtureResponse(site,"text/html",listing));
        transport.pages.put(url,fixtureResponse(url,"text/html","<nav>Только меню.</nav>"));
        SourceCoordinator.DownloadProgress failed=quiet(source);
        assertEquals(failed.sources.get(0).message,List.of(site,url),transport.requested);
        assertEquals(0,failed.newVersions);assertTrue(failed.failed+failed.skipped>0);
        assertEquals(old.recordKey,book.sourceItem(source.id,item.itemKey).latestVersionKey);
        assertEquals(old.body(),load(source).body());assertEquals(old.recordKey,book.materials().get(0).sourceVersionKey);

        transport.pages.put(url,fixtureResponse(url,"text/html","<meta property='og:title' content='"+title+"'><main><h2>"+title+"</h2>"
                +"<div class='article-text'><p>Очищенная статья.</p></div></main>"));
        SourceCoordinator.DownloadProgress updated=quiet(source);
        assertEquals(List.of(site,url,site,url),transport.requested);assertEquals(1,updated.newVersions);
        SourceVersionRecord current=load(source);assertEquals(HtmlPageReader.ADAPTER,current.adapterVersion);
        assertEquals("Очищенная статья.",current.body());assertEquals(2,book.sourceVersions(source.id,item.itemKey).size());
        assertEquals(old.recordKey,book.materials().get(0).sourceVersionKey);
        SourceCoordinator.DownloadProgress repeated=quiet(source);
        assertEquals(List.of(site,url,site,url,site),transport.requested);assertEquals(0,repeated.newVersions);assertEquals(1,repeated.loaded);
    }
    @Test public void c12UnchangedRefreshKeepsOneStableItemAndVersionDespiteServiceMarkupChanges()throws Exception{
        page("<title>Статья</title><main><p>Текст страницы.</p></main><nav>Меню</nav>");SourceRecord source=coordinator.connect(pagePreview(),"Страница");SourceItem item=book.sourceItems(source.id).get(0);String original=item.latestVersionKey;
        page("<title>Статья</title><script>new code</script><main><p>Текст страницы.</p></main><nav>Другое меню</nav>");refreshPage(source);assertEquals(1,book.sourceItems(source.id).size());SourceItem same=book.sourceItems(source.id).get(0);assertEquals(item.itemKey,same.itemKey);assertEquals(item.articleId,same.articleId);assertEquals(original,same.latestVersionKey);assertEquals(2,transport.count);assertEquals(List.of(PAGE,PAGE),transport.requested);
    }
    @Test public void newRssExtractionLeavesPriorCachedAndQueuedVersionReadable()throws Exception{
        String feed="https://press.example/rss",url="https://press.example/news/story",title="Название новости";
        String markup="<p>Редакционный текст.</p><p>Материал «<a href='"+url+"'>"+title+"</a>» был впервые опубликован на сайте <a href='/'>Издание</a>.</p>";
        SourceRecord source=SourceRecord.create("rss",feed,"Издание",feed,"feed-generic-v1",false);
        RequestScope scope=new RequestScope(1,0);
        String oldBody=SemanticTextNormalizer.fromDom(DomBudgetGuard.parse(markup,feed,scope).document.body(),scope);
        RssArticleText updated=RssArticleText.read(markup,feed,url,title,scope);
        assertTrue(updated.cleaned);assertEquals("Редакционный текст.",updated.body);
        SourceVersionRecord old=null;
        for(int pass=0;pass<3;pass++){
            boolean first=pass==0;
            ExtractionResult extracted=new ExtractionResult(first?oldBody:updated.body,title,title,"unknown",
                    "CONTENT_COMPLETENESS_UNKNOWN","rss_inline",first?"feed-inline-v1":RssArticleText.REVISION);
            FeedItem entry=new FeedItem("editorial-id",url,title,null,null,null,null,extracted);
            SourceCoordinator.Preview preview=new SourceCoordinator.Preview(source,new FeedDocument(source.name,feed,"rss",List.of(entry),false),
                    List.of(),0,extracted,0,90_000,book.authorityEpoch(),port.radioEpoch());
            source=coordinator.connect(preview,source.name);
            SourceVersionRecord current=coordinator.connectedPreviewVersion(preview,source);
            assertNotNull(current);assertEquals(1,book.sourceItems(source.id).size());
            if(first){old=load(source);assertTrue(coordinator.enqueue(old,true));continue;}
            assertNotEquals(old.recordKey,current.recordKey);
            assertEquals(current.recordKey,book.sourceItem(source.id,old.itemKey).latestVersionKey);
            assertEquals(old.recordKey,book.materials().get(0).sourceVersionKey);
            assertEquals(2,book.sourceVersions(source.id,old.itemKey).size());
            assertEquals(oldBody,cache.get(book.sourceVersion(old.recordKey)).body());
            assertEquals(updated.body,cache.get(current).body());
        }
    }
    @Test public void changedHtmlPageBatchSavesTheSingleCheckedResponseAndKeepsTheSelectedVersion() throws Exception {
        page("<main><h1>Статья</h1><p>Текст первой версии.</p></main>");
        SourceRecord source=coordinator.connect(pagePreview(),"Страница");
        SourceVersionRecord original=load(source);
        coordinator.enqueue(original,true);
        String updated="<main><h1>Статья</h1><p>Изменённый текст второй версии.</p></main>";
        page(updated);
        for (int attempt=0;attempt<2;attempt++) {
            int before=transport.count;
            transport.cooldownAtRequest=before+1; // A new request would be refused after the captured 200.
            AtomicReference<SourceCoordinator.DownloadProgress> download=new AtomicReference<>();
            coordinator.downloadBatch(List.of(source.id),3,download::set);
            pumpUntil(()->download.get()!=null && download.get().finished);
            assertEquals("Один ответ содержит заголовок и тело",before+1,transport.count);
            assertEquals(1,download.get().saved);
            assertEquals(attempt==0?1:0,download.get().newVersions); // New V2 once, then an unchanged copy.
            assertEquals(0,download.get().fetchedKnown);
            assertEquals(0,download.get().failed);
            assertEquals(1,download.get().savedRecordKeys.size());
            SourceVersionRecord version=load(source);
            assertTrue(download.get().savedRecordKeys.contains(version.recordKey));
            assertEquals("Изменённый текст второй версии.",version.body());
            assertEquals(PAGE,version.publication.subjectUrl);
        }
        assertEquals(1,book.sourceItems(source.id).size());
        assertEquals(2,book.sourceVersions(source.id,original.itemKey).size());
        assertEquals(original.recordKey,book.materials().get(0).sourceVersionKey);
    }
    @Test public void c12ChangedRefreshMakesANewVersionOfTheSameItemAndNeverDiscoversNewFeeds()throws Exception{
        page("<title>Старая статья</title><article><p>Первый текст.</p></article>");SourceRecord source=coordinator.connect(pagePreview(),"Страница");SourceItem before=book.sourceItems(source.id).get(0);SourceVersionRecord old=load(source);coordinator.enqueue(old,true);
        page("<head><title>Обновлённая статья</title><link rel='alternate' type='application/rss+xml' href='/many.xml'></head><main><p>Изменённый текст. Дополнение.</p></main>");refreshPage(source);SourceItem after=book.sourceItems(source.id).get(0);assertEquals(1,book.sourceItems(source.id).size());assertEquals(before.itemKey,after.itemKey);assertEquals(before.articleId,after.articleId);assertNotEquals(before.latestVersionKey,after.latestVersionKey);assertNotNull(book.sourceVersion(old.recordKey));assertEquals("Изменённый текст. Дополнение.",load(source).body());assertTrue(transport.requested.stream().allMatch(PAGE::equals));assertEquals(HtmlPageReader.TYPE,book.source(source.id).type);
        assertEquals(old.versionKey,book.material(source.stationId,before.articleId).version);NoteBook restored=new NoteBook(storage);assertEquals(1,restored.sources().size());assertEquals(after.itemKey,restored.sourceItems(source.id).get(0).itemKey);assertNotNull(restored.sourceVersion(after.latestVersionKey).publication);assertEquals(old.versionKey,restored.material(source.stationId,before.articleId).version);
    }
    @Test public void c12SameNormalizedPageDoesNotDuplicateSourceOrMaterial()throws Exception{
        page("<main><h1>Страница</h1><p>Один материал.</p></main>");SourceRecord one=coordinator.connect(pagePreview(),"Страница");SourceRecord again=coordinator.connect(pagePreview(),"Новое предлагаемое имя");assertEquals(one.id,again.id);assertEquals(1,book.sources().size());assertEquals(1,book.sourceItems(one.id).size());assertEquals("Страница",again.name);
    }
    @Test public void explicitArticleWithAlternateFeedUsesItsOwnCheckedBodyAndLeaf() throws Exception {
        page("<title>Точное название</title><link rel='alternate' type='application/rss+xml' href='/feed.xml'><article><header><h1>Точное название</h1></header><p>Именно эта статья.</p></article>");
        SourceCoordinator.Preview preview=pagePreview();
        assertTrue(preview.experimentalPage());assertEquals("Именно эта статья.",preview.loaded.body);
        SourceRecord source=coordinator.connect(preview,"Статья");SourceVersionRecord exact=coordinator.connectedPreviewVersion(preview,source);
        assertNotNull(exact);assertEquals(PAGE,exact.publication.subjectUrl);assertEquals(1,transport.count);
    }
    @Test public void customSectionBindsSelectedVersionKeepsManualAdmissionAndRefreshesAsAnIndex() throws Exception {
        StringBuilder html=new StringBuilder("<title>Издание</title><main><h1>Главная</h1>");
        for(int n=1;n<=22;n++)html.append("<article><h2><a href='/story/").append(n).append("'>Публикация номер ").append(n).append("</a></h2></article>");
        page(html+"</main>");
        SourceCoordinator.Preview index=pagePreview();
        assertTrue(index.experimentalIndex());assertFalse(index.experimentalPage());assertFalse(index.discovery());
        assertEquals("Издание",index.source.name);assertEquals(22,index.feed.items.size());assertNull(index.loaded);
        int selected=19;String url=index.feed.items.get(selected).url;
        transport.pages.put(url,HtmlIndexReaderTest.html(url,"<article><h1>Публикация номер 20</h1><p>Первая строка тела.</p><p>Финал двадцатой статьи.</p></article>"));
        assertThrows(IllegalArgumentException.class,()->coordinator.previewText(index,20,r->fail()));
        AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> loaded=new AtomicReference<>();
        coordinator.previewText(index,selected,loaded::set);SourceCoordinator.Preview checked=await(loaded);
        assertEquals(List.of(PAGE,url),transport.requested);assertTrue(book.sources().isEmpty());assertEquals(selected,checked.loadedIndex);
        SourceRecord source=coordinator.connect(checked,"Раздел");assertEquals(22,book.sourceItems(source.id).size());
        SourceVersionRecord exact=coordinator.connectedPreviewVersion(checked,source);
        assertNotNull(exact);assertEquals(url,exact.url);assertEquals(url,exact.publication.subjectUrl);
        AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> saved=new AtomicReference<>();
        coordinator.savedText(exact.recordKey,saved::set);SourceVersionRecord body=await(saved);
        assertEquals("Первая строка тела.\n\nФинал двадцатой статьи.",body.body());assertEquals("unknown",body.completeness);
        assertEquals(2,transport.count);assertTrue(book.materials().isEmpty());assertThrows(IllegalArgumentException.class,()->coordinator.enqueue(body,false));
        assertTrue(coordinator.enqueue(body,true));assertEquals(1,book.materials().size());
        page("<h2><a href='/story/20'>Та же публикация</a></h2><h2><a href='/story/new'>Свежий заголовок</a></h2>");
        refreshPage(source);assertEquals(23,book.sourceItems(source.id).size());assertEquals(exact.recordKey,book.materials().get(0).sourceVersionKey);
        NoteBook restored=new NoteBook(storage);assertEquals(HtmlIndexReader.TYPE,restored.source(source.id).type);
        assertEquals(23,restored.sourceItems(source.id).size());
    }
    @Test public void manualRssTeaserPrefersCheckedSamePublisherArticleOrFallsBackToItsOriginalExcerpt() throws Exception {
        // Start at the generic feed reader's output boundary (Android's XmlPullParser has its own instrumentation tests).
        String feedUrl="https://journal.example/rss",article="https://journal.example/section/story";
        SourceRecord source=SourceRecord.create("rss",feedUrl,"Издание",feedUrl,"feed-generic-v1",false);
        ExtractionResult teaser=new ExtractionResult("Краткий анонс. Читать далее.","Статья","Статья","summary","RSS_DESCRIPTION","rss_inline","feed-inline-v1");
        FeedItem item=new FeedItem("abc",article,"Статья",null,null,null,null,teaser);
        SourceCoordinator.Preview preview=new SourceCoordinator.Preview(source,new FeedDocument("Издание",feedUrl,"rss",List.of(item),false),
                Collections.emptyList(),-1,null,0,90_000,book.authorityEpoch(),port.radioEpoch());
        transport.pages.put(article,HtmlIndexReaderTest.html(article,"<article><h1>Статья</h1><p>Первый абзац полного текста.</p><p>Окончание публикации.</p></article>"));
        AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> result=new AtomicReference<>();
        coordinator.previewText(preview,0,result::set);
        SourceCoordinator.Preview full=await(result);assertEquals("unknown",full.loaded.completeness);assertTrue(full.loaded.body.endsWith("Окончание публикации."));
        SourceRecord connected=coordinator.connect(full,"Издание");
        assertNotNull(coordinator.connectedPreviewVersion(full,connected));
        transport.pages.put(article,HtmlIndexReaderTest.html(article,"<nav>Нет читаемой статьи</nav>"));
        result.set(null);coordinator.previewText(preview,0,result::set);
        SourceCoordinator.Preview fallback=await(result);assertEquals("summary",fallback.loaded.completeness);assertEquals(teaser.body,fallback.loaded.body);
        assertEquals(List.of(article,article),transport.requested);assertTrue(book.materials().isEmpty());
        // An explicit upgrade of saved HTML must propagate this same publisher failure.
        SourceFailure failedUpgrade=assertThrows(SourceFailure.class,()->coordinator.extract(source,item,new RequestScope(1,0),false));
        assertEquals("HTML_PAGE_EMPTY",failedUpgrade.reason);assertEquals(List.of(article,article,article),transport.requested);
    }
    @Test public void c12InitialDiscoveryPrefersFeedAndDoesNotSaveThePage()throws Exception{
        page("<title>Сайт</title><link rel='alternate' type='application/rss+xml' href='/feed.xml'><main>Текст страницы.</main>");SourceCoordinator.Preview p=pagePreview();assertTrue(p.discovery());assertFalse(p.experimentalPage());assertEquals(1,p.candidates.size());assertTrue(book.sources().isEmpty());
        assertEquals("https://any-public.example/feed.xml",p.candidates.get(0).url);coordinator.invalidatePreview();assertTrue(book.sources().isEmpty());assertEquals(0,port.stations);
    }
    @Test public void c12LargePageUsesExistingSourceV2AndExplicitQueueWithoutAutoplay()throws Exception{
        String text="Рассказ о природе. ".repeat(1800).strip();page("<main><h1>Большой рассказ</h1><p>"+text+"</p></main>");SourceRecord source=coordinator.connect(pagePreview(),"Длинная страница");SourceVersionRecord version=load(source);assertEquals(SourceArticleFramer.VERSION45,version.framingVersion);assertEquals(text,version.body());assertTrue(version.spokenText.length()>SourceLimits.MAX_TEXT);assertEquals(SourceLimits.MAX_SOURCE_SPOKEN_UTF16,version.textLimit());assertEquals(0,port.appends);assertThrows(IllegalArgumentException.class,()->coordinator.enqueue(version,false));assertTrue(book.materials().isEmpty());coordinator.enqueue(version,true);assertEquals(1,port.appends);assertEquals(1,book.materials().size());
    }
    @Test public void c12EmptyRefreshKeepsLastSuccessfulItemAndSavedText()throws Exception{
        page("<title>Страница</title><main>Сохранённый текст.</main>");SourceRecord source=coordinator.connect(pagePreview(),"Страница");SourceVersionRecord saved=load(source);String latest=book.sourceItems(source.id).get(0).latestVersionKey;page("<nav>Только меню</nav><script>code</script>");AtomicReference<SourceCoordinator.Result<SourceRecord>> r=new AtomicReference<>();coordinator.refresh(source.id,r::set);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(r.get()==null&&System.nanoTime()<end){Runnable task=owner.poll(100,TimeUnit.MILLISECONDS);if(task!=null)task.run();}assertNotNull(r.get());assertEquals("HTML_PAGE_EMPTY",r.get().error);assertEquals(1,book.sourceItems(source.id).size());assertEquals(latest,book.sourceItems(source.id).get(0).latestVersionKey);assertEquals(saved.versionKey,load(source).versionKey);assertTrue(book.materials().isEmpty());
    }
    @Test public void previewDoesNotConnectAndConnectPublishesEmptyStation()throws Exception{
        SourceCoordinator.Preview preview=preview();assertTrue(book.sources().isEmpty());assertEquals(0,port.stations);SourceRecord source=coordinator.connect(preview,"Моя станция");
        assertEquals(1,book.sources().size());assertEquals(source.stationId,book.stationRegistry().get(0).id);assertTrue(book.materials().isEmpty());assertEquals(1,port.stations);assertEquals(0,port.appends);
    }
    @Test public void explicitDwDiagnosticCommitsBeforeUiProjectionAndDoesNotChangeFailureOrBook()throws Exception{
        transport.failDw=true;byte[] before=storage.bytes==null?null:storage.bytes.clone();
        AtomicReference<byte[]> safe=new AtomicReference<>();CountDownLatch committed=new CountDownLatch(1);
        DwNetworkDiagnostics diagnostic=new DwNetworkDiagnostics("coordinator-case",clock,bytes->{safe.set(bytes);committed.countDown();});
        AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> result=new AtomicReference<>();
        try(AutoCloseable armed=coordinator.armDwDiagnosticsForTest(diagnostic)){
            coordinator.preview(DwNetworkDiagnostics.INPUT,false,"DW на русском",result::set);
            assertTrue(committed.await(3,TimeUnit.SECONDS));assertNull(result.get());
            while(result.get()==null){Runnable task=owner.poll(3,TimeUnit.SECONDS);assertNotNull(task);task.run();}
            assertEquals("NETWORK_ERROR",result.get().error);assertEquals(SourceFailureInfo.Kind.NETWORK,result.get().failure.kind);
            assertEquals(SourceFailureInfo.forReason("NETWORK_ERROR").message(clock),result.get().failure.message(clock));
            String json=new String(safe.get(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(json.contains("javax.net.ssl.SSLHandshakeException"));assertFalse(json.contains("PRIVATE_MESSAGE"));
            assertTrue(book.sources().isEmpty());assertTrue(book.materials().isEmpty());assertArrayEquals(before,storage.bytes);assertEquals(1,transport.count);
        }
    }
    @Test public void usernameVariantsDoNotCreateSecondSourceOrRenameExistingStation()throws Exception{
        SourceRecord first=connect();AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> r=new AtomicReference<>();coordinator.preview("https://t.me/s/EXAMPLE",true,null,r::set);
        SourceRecord second=coordinator.connect(await(r),"Другое имя");assertEquals(first.id,second.id);assertEquals("Моя станция",second.name);assertEquals(2,port.stations); // Retry projection is harmless/idempotent in production Player.
    }
    @Test public void selectedPublicPostRequiresConsentAndPersistsExactOfflinePayload()throws Exception{
        SourceRecord source=connect();SourceVersionRecord v=load(source);assertNull(book.sourceVersion(v.recordKey).spokenText);assertThrows(IllegalArgumentException.class,()->coordinator.enqueue(v,false));assertTrue(book.materials().isEmpty());
        assertTrue(coordinator.enqueue(v,true));assertEquals(v.spokenText,book.materials().get(0).text);assertEquals(1,port.appends);
        assertFalse(coordinator.enqueue(v,true));assertEquals(1,book.materials().size());int requests=transport.count;
        AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> r=new AtomicReference<>();coordinator.savedText(v.recordKey,r::set);assertEquals(v.spokenText,await(r).spokenText);assertEquals(requests,transport.count);
    }
    @Test public void delayedSuccessfulWorkerCannotPublishAfterUiCancellation()throws Exception{
        transport.hold=new CountDownLatch(1);transport.entered=new CountDownLatch(1);AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> r=new AtomicReference<>();coordinator.preview("@example",true,null,r::set);
        assertTrue(transport.entered.await(2,TimeUnit.SECONDS));coordinator.cancelUi();transport.hold.countDown();Runnable action=owner.poll(2,TimeUnit.SECONDS);assertNotNull(action);action.run();assertNull(r.get());assertTrue(book.sources().isEmpty());assertEquals(0,port.changes);
    }
    @Test public void disablingSourceDoesNotClearProtectedTextAndSavedReaderNeedsNoNetwork()throws Exception{
        SourceRecord source=connect();SourceVersionRecord v=load(source);coordinator.enqueue(v,true);coordinator.enabled(source.id,false);int requests=transport.count;
        AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> r=new AtomicReference<>();coordinator.savedText(v.recordKey,r::set);assertEquals(v.spokenText,await(r).spokenText);assertEquals(requests,transport.count);assertEquals(1,book.materials().size());
    }
    @Test public void removalRefusesUnknownSavedProgressThenArchivesAndRestoresExactPartial()throws Exception{
        SourceRecord source=connect();SourceVersionRecord v=load(source);coordinator.enqueue(v,true);assertThrows(IllegalStateException.class,()->coordinator.remove(source.id,v.articleId));assertEquals(1,book.materials().size());assertEquals(0,port.removes);
        port.persisted=new PlayerController.SavedProgress(source.stationId,v.articleId,v.versionKey,1,false,Collections.singletonList(0),true,true);
        coordinator.remove(source.id,v.articleId);assertTrue(book.materials().isEmpty());assertEquals(1,port.removes);coordinator.enqueue(v,true);assertEquals(1,book.materials().get(0).selectionInitialProgress.sentenceIndex);assertEquals(Collections.singletonList(0),book.materials().get(0).selectionInitialProgress.completedSentences);
    }
    @Test public void manualRefreshRetainsSelectedOldVersionAndMarksNewText()throws Exception{
        SourceRecord source=connect();SourceVersionRecord v=load(source);coordinator.enqueue(v,true);transport.text="Изменённый пост. Новое предложение.";
        AtomicReference<SourceCoordinator.Result<SourceRecord>> r=new AtomicReference<>();coordinator.refresh(source.id,r::set);await(r);SourceItem item=book.sourceItems(source.id).get(0);
        assertNotEquals(v.recordKey,item.latestVersionKey);assertEquals(v.spokenText,book.materials().get(0).text);assertEquals(1,port.appends);
    }
    @Test public void readMarkAloneDoesNotClaimListenedAndOpeningDoesNotMarkRead()throws Exception{
        SourceRecord source=connect();SourceVersionRecord v=load(source);SourceItem item=book.sourceItems(source.id).get(0);assertFalse(item.read);assertFalse(item.everListened);assertEquals(v.recordKey,item.lastViewedVersion);
        coordinator.setRead(source.id,item.itemKey,true);item=book.sourceItem(source.id,item.itemKey);assertTrue(item.read);assertFalse(item.everListened);assertEquals(1,port.reads);
    }
    @Test public void selectedPayloadRemainsReadableWhenNewWritesAndFetchAreBlocked()throws Exception{
        SourceRecord source=connect();SourceVersionRecord v=load(source);coordinator.enqueue(v,true);port.refuseWrites=true;int requests=transport.count;assertEquals(v.spokenText,load(source).spokenText);assertEquals(requests,transport.count);assertEquals(1,book.materials().size());
    }
    @Test public void projectionFailureKeepsOneDurableMaterialAndRetryProjectsSameSeed()throws Exception{
        SourceRecord source=connect();SourceVersionRecord v=load(source);port.failAppend=true;assertThrows(SourceCoordinator.ProjectionPendingException.class,()->coordinator.enqueue(v,true));assertEquals(1,book.materials().size());assertEquals(0,port.appends);
        port.failAppend=false;assertFalse(coordinator.enqueue(v,true));assertEquals(1,port.appends);assertEquals(1,book.materials().size());assertEquals(0,book.materials().get(0).selectionInitialProgress.sentenceIndex);
    }
    @Test public void successfullyDeliveredPreviewCannotCommitAfterNewInputOrFormClose()throws Exception{
        SourceCoordinator.Preview old=preview();coordinator.invalidatePreview();assertThrows(IllegalStateException.class,()->coordinator.connect(old,"Старый источник"));assertTrue(book.sources().isEmpty());
        SourceCoordinator.Preview fresh=preview();coordinator.cancelUi();assertThrows(IllegalStateException.class,()->coordinator.connect(fresh,"Закрытая форма"));assertTrue(book.sources().isEmpty());assertEquals(0,port.stations);
    }
    @Test public void previewBodyTapAndConnectCannotExtendExpiredWholeOperation()throws Exception{
        SourceCoordinator.Preview p=preview();int requests=transport.count;clock.time=90_001;
        assertThrows(IllegalStateException.class,()->coordinator.previewText(p,0,r->fail("Expired operation must not execute")));assertThrows(IllegalStateException.class,()->coordinator.connect(p,"Просроченное"));assertEquals(requests,transport.count);assertTrue(book.sources().isEmpty());
    }
    @Test public void ownerDeliveryAfterDeadlineReportsFailureWithoutCommittingOrSkipping()throws Exception{
        AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> result=new AtomicReference<>();coordinator.preview("@example",true,null,result::set);Runnable task=owner.poll(2,TimeUnit.SECONDS);assertNotNull(task);clock.time=90_001;task.run();assertNotNull(result.get());assertEquals("TIMEOUT",result.get().error);assertTrue(book.sources().isEmpty());assertEquals(0,port.stations);
    }
    @Test public void refreshAllFailureOfOneSourceDoesNotDiscardOrCancelAnother()throws Exception{
        SourceRecord first=connect();AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> p=new AtomicReference<>();coordinator.preview("@second",true,null,p::set);SourceRecord second=coordinator.connect(await(p),"Вторая станция");
        transport.failExample=true;Map<String,SourceCoordinator.Result<SourceRecord>> results=new LinkedHashMap<>();coordinator.refreshAll(results::put);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(results.size()<2&&System.nanoTime()<end){Runnable action=owner.poll(100,TimeUnit.MILLISECONDS);if(action!=null)action.run();}
        assertEquals(2,results.size());assertEquals("HTTP_503",results.get(first.id).error);assertNull(results.get(second.id).error);assertEquals("HTTP_503",book.source(first.id).lastError);assertEquals(1,book.sourceItems(first.id).size());assertEquals(1,book.sourceItems(second.id).size());assertEquals(2,book.sources().size());assertEquals(0,port.appends);
    }
    @Test public void queuedOwnerCannotPublishAcrossUnknownAuthoritativeBookOutcome()throws Exception{
        SourceRecord source=connect();transport.entered=new CountDownLatch(1);transport.hold=new CountDownLatch(1);AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> result=new AtomicReference<>();coordinator.loadText(source.id,book.sourceItems(source.id).get(0).itemKey,result::set);assertTrue(transport.entered.await(2,TimeUnit.SECONDS));
        storage.failSave=true;assertThrows(IllegalStateException.class,()->book.beginRecording("Попытка",null,null,null,2));assertTrue(book.storageUncertain());assertTrue(book.authorityEpoch()>0);transport.hold.countDown();Runnable task=owner.poll(2,TimeUnit.SECONDS);assertNotNull(task);task.run();assertNull(result.get());assertTrue(book.materials().isEmpty());assertNull(book.sourceVersion(book.sourceItems(source.id).get(0).latestVersionKey).spokenText);
    }
    @Test public void cancelledRefreshCannotChangeLatestOrSuccessMetadataOnLateDelivery()throws Exception{
        SourceRecord source=connect();String latest=book.sourceItems(source.id).get(0).latestVersionKey;long success=book.source(source.id).lastSuccess;transport.text="Позднее обновление.";transport.entered=new CountDownLatch(1);transport.hold=new CountDownLatch(1);AtomicReference<SourceCoordinator.Result<SourceRecord>> result=new AtomicReference<>();coordinator.refresh(source.id,result::set);assertTrue(transport.entered.await(2,TimeUnit.SECONDS));coordinator.cancelUi();transport.hold.countDown();Runnable task=owner.poll(2,TimeUnit.SECONDS);assertNotNull(task);task.run();assertNull(result.get());assertEquals(latest,book.sourceItems(source.id).get(0).latestVersionKey);assertEquals(success,book.source(source.id).lastSuccess);assertEquals(0,port.appends);
    }
    @Test public void fallbackIdentityUsesRawPublicationAndBodyButOpaqueIdWins(){
        ExtractionResult body=new ExtractionResult("Текст","","","unknown","","feed","feed-v1");FeedItem a=new FeedItem(null,null,"Заголовок","a",null,null,null,body),b=new FeedItem(null,null,"Заголовок","b",null,null,null,body);
        assertNotEquals(SourceCoordinator.identity(a),SourceCoordinator.identity(b));FeedItem c=new FeedItem("opaque",null,"Другой заголовок","b",null,null,null,body),d=new FeedItem("opaque","https://example.org/a","Третий","c",null,null,null,null);assertEquals(SourceCoordinator.identity(c),SourceCoordinator.identity(d));
    }
}

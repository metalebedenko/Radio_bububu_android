package ru.bububu.radio.sources;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import ru.bububu.radio.core.*;
import static org.junit.Assert.*;

/** Exercises the actual Coordinator fetch predicate and SafeHttpTransport, with no sockets. */
public class Source45MaterialIdentityTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    static final class Memory implements NoteBook.MigrationStorage {
        byte[] bytes;public byte[] load(){return bytes;}public void save(byte[] value){bytes=value.clone();}
        MigrationStamp stamp(byte[] baseline){String id=UUID.randomUUID().toString();return new MigrationStamp(id,baseline==null?null:SourceLimits.hash(baseline),baseline==null?-1:baseline.length,baseline==null?UUID.randomUUID().toString():null,id,1,"a".repeat(64));}
        public MigrationStamp prepareMigration(byte[] baseline,int target){return stamp(baseline);}public void verifyMigrationStamp(MigrationStamp stamp){}
        public Format4BaselineRef prepareFormat4Upgrade(byte[] baseline,int target){return new Format4BaselineRef(NoteBook.legacySchema(baseline),stamp(baseline));}public void verifyFormat4Baseline(Format4BaselineRef ref){}
    }
    static final class Port implements SourceCoordinator.Port {
        final Queue<Runnable> pending=new ConcurrentLinkedQueue<>();public void dispatch(Runnable r){pending.add(r);}public void requireMutable(){}public void requireStorage(long n){}public void requireQueue(SourceRecord s,Article a){}public void appendStation(SourceRecord s){}public void append(NoteBook.Material m){}public PlayerController.SavedProgress persisted(NoteBook.Material m){return new PlayerController.SavedProgress(m.stationId,m.articleId,m.version,0,false,List.of(),false,false);}public void pauseIfSelected(NoteBook.Material m){}public void remove(NoteBook.Material m){}public void projectRead(SourceRecord s,SourceItem i){}public void captureHistory(){}public void changed(){}
        <T>T await(AtomicReference<T> result)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(result.get()==null&&System.nanoTime()<end){Runnable r=pending.poll();if(r!=null)r.run();else Thread.sleep(1);}assertNotNull("owner callback",result.get());return result.get();}
    }
    interface Reply {okhttp3.Response response(Request request)throws IOException;}
    static okhttp3.Response reply(Request request,int status,String mime,String body,String location){okhttp3.Response.Builder b=new okhttp3.Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture").header("Content-Type",mime).body(ResponseBody.create(body,MediaType.get(mime)));if(location!=null)b.header("Location",location);return b.build();}
    SourceCoordinator.Result<SourceVersionRecord> load(SourceProfiles45.Profile p,String a,String provider,Reply response,List<String> requests)throws Exception{
        Memory memory=new Memory();NoteBook book=new NoteBook(memory);SourceRecord source=p.seed();SourceItem item=SourceItem.create(source.id,"fixture-item",provider,a,"Проверка",-1,-1,0,"2026-10-25T10:00:00Z",null);book.commitSource(source,List.of(item));Port port=new Port();
        OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain->{requests.add(chain.request().url().toString());return response.response(chain.request());}).build();AtomicInteger dns=new AtomicInteger();SafeHttpTransport transport=new SafeHttpTransport(client,new PublicIpPolicy(),host->{dns.incrementAndGet();throw new UnknownHostException("No network allowed");},new HostRequestGate(new HostCooldownStore(temp.newFolder()),SourceClock.SYSTEM));
        try(SourceCoordinator coordinator=new SourceCoordinator(book,transport,new SourceBodyCache(temp.newFolder()),port)){
            AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> result=new AtomicReference<>();coordinator.loadText(source.id,item.itemKey,result::set);SourceCoordinator.Result<SourceVersionRecord> value=port.await(result);assertEquals(0,dns.get());return value;
        }
    }
    @Test public void coordinatorRejectsOtherAllowedArticleBeforeRedirectGetAndReaderRechecks()throws Exception{
        SourceProfiles45.Profile p=SourceProfiles45.byId("forbes-open-articles");String a="https://www.forbes.ru/mneniya/123-alpha",b="https://www.forbes.ru/mneniya/124-beta";assertTrue(p.articleUrl(a)&&p.articleUrl(b));List<String> requests=new ArrayList<>();
        SourceCoordinator.Result<SourceVersionRecord> r=load(p,a,null,request->reply(request,302,"text/html","",b),requests);assertFalse(r.successful());assertEquals(List.of(a),requests);assertEquals("PROFILE_TARGET_FORBIDDEN",r.error);
        FeedItem item=new FeedItem(null,a,"Тема",null,null,null,null,null);assertEquals("ARTICLE_SUBJECT_MISMATCH",assertThrows(SourceFailure.class,()->SourceReaders45.extract(p,Source45ReaderGuardsTest.html(b,Source45ReaderGuardsTest.page(b,"<article><div class='_6X6B8'>Чужой текст</div></article>")),item,new RequestScope(1,1))).reason);
    }
    @Test public void foreignExplicitJsonLdSubjectAtCanonicalACannotBecomeFull()throws Exception{
        SourceProfiles45.Profile p=SourceProfiles45.byId("techinsider-briefs");String a="https://www.techinsider.ru/news/news-123-alpha/",b="https://www.techinsider.ru/news/news-124-beta/";
        for(String locator:List.of("\"url\":\""+b+"\"","\"@id\":\""+b+"#article\"","\"mainEntityOfPage\":\""+b+"\"","\"mainEntityOfPage\":{\"@id\":\""+b+"#webpage\"}")){
            String json="{\"@type\":\"Article\",\"articleBody\":\"Чужое содержимое\",\"datePublished\":\"2026-10-25T10:00:00Z\","+locator+"}";String html=Source45ReaderGuardsTest.page(a,"<script type='application/ld+json'>"+json+"</script>");List<String> requests=new ArrayList<>();SourceCoordinator.Result<SourceVersionRecord> r=load(p,a,null,request->reply(request,200,"text/html",html,null),requests);assertFalse(r.successful());assertEquals("ARTICLE_SUBJECT_MISMATCH",r.error);assertEquals(List.of(a),requests);
        }
    }
    static String post(String link){return "[{\"id\":123,\"link\":\""+link+"\",\"categories\":[1814],\"title\":{\"rendered\":\"Тема\"},\"content\":{\"rendered\":\"<p>Текст</p>\",\"protected\":false},\"date_gmt\":\"2026-10-25T10:00:00\",\"date\":\"2026-10-25T13:00:00\",\"modified\":\"2026-10-25T13:00:00\",\"modified_gmt\":\"2026-10-25T10:00:00\"}]";}
    @Test public void wpPostLinkAndIdBindApiBodyBeforeCanonicalEnrichment()throws Exception{
        SourceProfiles45.Profile p=SourceProfiles45.byId("poisknews-biographies");String a="https://poisknews.ru/ponyatno/alpha/",b="https://poisknews.ru/ponyatno/beta/";List<String> requests=new ArrayList<>();
        SourceCoordinator.Result<SourceVersionRecord> r=load(p,a,"123",request->{if(!request.url().toString().equals(p.endpoint))throw new IOException("B must never be requested");return reply(request,200,"application/json",post(b),null);},requests);assertFalse(r.successful());assertEquals("ARTICLE_SUBJECT_MISMATCH",r.error);assertEquals(List.of(p.endpoint),requests);
        FeedItem fromB=SourceReaders45.discover(p,new SourceTransport.Response(post(b).getBytes(StandardCharsets.UTF_8),p.endpoint,200,"application/json","UTF-8",null,null),new RequestScope(1,1)).items.get(0);FeedItem forged=new FeedItem("123",a,"Тема",null,null,null,null,fromB.inline);
        assertEquals("ARTICLE_SUBJECT_MISMATCH",assertThrows(SourceFailure.class,()->SourceReaders45.extract(p,Source45ReaderGuardsTest.html(a,Source45ReaderGuardsTest.page(a,"<p>Страница A</p>")),forged,new RequestScope(1,1))).reason);
        FeedItem wrongId=new FeedItem("124",b,"Тема",null,null,null,null,fromB.inline);assertEquals("WP_SUBJECT_ID_MISMATCH",assertThrows(SourceFailure.class,()->SourceReaders45.extract(p,Source45ReaderGuardsTest.html(b,Source45ReaderGuardsTest.page(b,"<p>Страница B</p>")),wrongId,new RequestScope(1,1))).reason);
    }
    @Test public void rtAndHabrApprovedMappingsPassActualCallerAndNoGeneralAliasesAreGuessed()throws Exception{
        SourceProfiles45.Profile rt=SourceProfiles45.byId("rt-briefs");String a="https://russian.rt.com/world/news/123-alpha?utm_source=rss&utm_medium=rss&utm_campaign=RSS",b="https://russiatoday.ru/world/news/123-alpha",canonical="https://russian.rt.com/world/news/123-alpha";String html=Source45ReaderGuardsTest.page(canonical,"<div class='article__summary_article-page'>Вступление.</div><div class='article__text_article-page'><p>Основной текст.</p></div>");List<String> requests=new ArrayList<>();SourceCoordinator.Result<SourceVersionRecord> r=load(rt,a,null,request->request.url().toString().equals(a)?reply(request,302,"text/html","",b):reply(request,200,"text/html",html,null),requests);assertTrue(r.error,r.successful());assertEquals(List.of(a,b),requests);assertEquals(canonical,r.value.publication.subjectUrl);
        SourceProfiles45.Profile habr=SourceProfiles45.byId("habr-hardware-reviews-retrospectives");String ha="https://habr.com/ru/articles/123/?utm_campaign=123&utm_source=habrahabr&utm_medium=rss",hb="https://habr.com/ru/articles/123/";String hh=Source45ReaderGuardsTest.page(hb,"<div class='publication-label variant-review'>Обзор</div><time datetime='2026-10-25T10:00:00Z'></time><div class='article-body'><p>Проверочный обзор.</p></div>");requests=new ArrayList<>();List<String> recorded=requests;SourceCoordinator.Result<SourceVersionRecord> h=load(habr,ha,null,request->request.url().toString().equals(ha)?reply(request,302,"text/html","",hb):reply(request,200,"text/html",hh,null),recorded);assertTrue(h.error,h.successful());assertEquals(List.of(ha,hb),recorded);assertFalse(MaterialIdentity45.same(habr,ha,"https://habr.com/ru/articles/124/"));assertFalse(MaterialIdentity45.same(habr,hb,hb+"?secret=1"));
    }
}

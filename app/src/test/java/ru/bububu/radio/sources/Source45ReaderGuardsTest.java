package ru.bububu.radio.sources;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class Source45ReaderGuardsTest {
    static SourceTransport.Response html(String url,String body){return new SourceTransport.Response(body.getBytes(StandardCharsets.UTF_8),url,200,"text/html","UTF-8",null,null);}
    static String page(String url,String body){return "<html><head><link rel='canonical' href='"+url+"'></head><body><h1>Заголовок</h1>"+body+"</body></html>";}
    @Test public void exact22ClosedProfilesSixStableBindingsAndNoCustomHostnameGuess(){
        assertEquals(22,SourceProfiles45.all().size());assertEquals(11,SourceProfiles45.all().stream().filter(p->p.kind==ChannelProfile.Kind.ARTICLE_ANALYSIS).count());Set<String> ids=new HashSet<>();
        for(SourceProfiles45.Profile p:SourceProfiles45.six()){SourceRecord a=p.seed(),b=p.seed();assertEquals(a.id,b.id);assertEquals("SRC:"+a.id,a.stationId);assertEquals(p.kind,a.kind());assertTrue(ids.add(a.id));assertEquals(p,SourceProfiles45.forSource(a));}
        assertEquals(4,SourceProfiles45.six().stream().filter(x->x.kind==ChannelProfile.Kind.NEWS_BRIEF).count());assertEquals(2,SourceProfiles45.six().stream().filter(x->x.kind==ChannelProfile.Kind.ARTICLE_ANALYSIS).count());
        SourceProfiles45.Profile p=SourceProfiles45.byId("elementy-science-explainers");SourceRecord custom=SourceRecord.create("rss",p.endpoint,"Свой",p.endpoint,"feed-generic-v1",false);assertNull(SourceProfiles45.forSource(custom));assertNull(SourceProfiles45.byEndpoint("https://elementy.ru/other"));assertFalse(p.allows("https://evil.example/story"));
        assertEquals(SourceTransport.Kind.XML,SourceProfiles45.byId("techinsider-briefs").discoveryKind());assertEquals(SourceTransport.Kind.JSON,SourceProfiles45.byId("poisknews-biographies").discoveryKind());
    }
    @Test public void missingOrMultipleRootsNeverFallBackToPageText()throws Exception{
        SourceProfiles45.Profile p=SourceProfiles45.byId("biomolecula-articles");String url="https://biomolecula.ru/articles/fixture";FeedItem i=new FeedItem(null,url,"Заголовок",null,null,null,null,null);
        for(String body:List.of("<article>Совсем другой текст</article>","<div class='article_content'>Один</div><div class='article_content'>Два</div>"))assertThrows(SourceFailure.class,()->SourceReaders45.extract(p,html(url,page(url,body)),i,new RequestScope(1,1)));
    }
    @Test public void fullRssRequiresExactNamespaceContentAndKnifeGenre()throws Exception{
        SourceProfiles45.Profile p=SourceProfiles45.byId("knife-articles");String head="<rss version='2.0' xmlns:content='http://purl.org/rss/1.0/modules/content/'><channel><title>Журнал</title>";String tail="</channel></rss>";
        for(String middle:List.of("<item><link>https://knife.media/fixture/</link><title>Тема</title><category>Статьи</category><description>Анонс</description></item>","<item><link>https://knife.media/fixture/</link><title>Тема</title><category>Новости</category><content:encoded>Полный текст</content:encoded></item>")){
            SourceTransport.Response r=new SourceTransport.Response((head+middle+tail).getBytes(StandardCharsets.UTF_8),p.endpoint,200,"text/xml","UTF-8",null,null);assertTrue(SourceReaders45.discover(p,r,new RequestScope(1,1)).items.isEmpty());
        }
        String dtd="<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss version='2.0'><channel/></rss>";assertThrows(SourceFailure.class,()->SourceReaders45.discover(p,new SourceTransport.Response(dtd.getBytes(StandardCharsets.UTF_8),p.endpoint,200,"text/xml","UTF-8",null,null),new RequestScope(1,1)));
    }
    @Test public void typedJsonLdBodyIsUniqueAndNoLongestOrSeoFallback()throws Exception{
        SourceProfiles45.Profile p=SourceProfiles45.byId("techinsider-briefs");String url="https://www.techinsider.ru/news/news-123-fixture/";FeedItem i=new FeedItem(null,url,"Тема",null,null,null,null,null);
        for(String json:List.of("{\"@type\":\"WebPage\",\"articleBody\":\"Текст\"}","[{\"@type\":\"Article\",\"articleBody\":\"Один\"},{\"@type\":\"Article\",\"articleBody\":\"Длинный второй\"}]")){
            String body=page(url,"<script type='application/ld+json'>"+json+"</script><meta property='og:description' content='SEO'><p>Другой текст</p>");assertThrows(SourceFailure.class,()->SourceReaders45.extract(p,html(url,body),i,new RequestScope(1,1)));
        }
    }
    @Test public void forbesMissingFixedPrimaryCannotPromoteJsonLdCorroboration()throws Exception{
        SourceProfiles45.Profile p=SourceProfiles45.byId("forbes-open-articles");String url="https://www.forbes.ru/mneniya/123-fixture";FeedItem i=new FeedItem(null,url,"Тема",null,null,null,null,null);
        String body=page(url,"<article><div class='_6X6B8'><p>Содержательный текст.</p></div></article><script type='application/ld+json'>{\"@type\":\"NewsArticle\",\"datePublished\":\"2026-10-25T09:00:00+03:00\"}</script>");ExtractionResult r=SourceReaders45.extract(p,html(url,body),i,new RequestScope(1,1));assertEquals("full",r.completeness);assertEquals(SourcePublication45.Precision.UNKNOWN,r.publication.primary.precision);assertEquals("FORBES_PRIMARY_CONFLICT",r.publication.conflict);assertThrows(SourceFailure.class,()->new SourceAdmission45(SourceClock.SYSTEM).context(r.publication,false));
    }
    @Test public void badPartialDatesBecomeUnknownWithoutInventedUtc(){for(String raw:List.of("0000-02","0000-01-01","2026-02-30","2026-13","2026-10-25T10:00:00")){SourcePublication45.Fact f=Source45PublicationTest.date(raw);assertEquals(raw,SourcePublication45.Precision.UNKNOWN,f.precision);assertNull(f.epochMillis);assertNull(f.offsetSeconds);}}
}

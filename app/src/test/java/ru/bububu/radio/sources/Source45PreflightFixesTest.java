package ru.bububu.radio.sources;

import java.util.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import okhttp3.*;
import static org.junit.Assert.*;

/** No sockets: OkHttp's application interceptor supplies every observed response. */
public class Source45PreflightFixesTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    static final class FakeHttp implements AutoCloseable {
        final List<String> requests=new ArrayList<>();final AtomicInteger dns=new AtomicInteger();final SafeHttpTransport transport;
        FakeHttp(java.io.File dir,String mime,String redirect)throws Exception{
            OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain->{requests.add(chain.request().url().toString());okhttp3.Response.Builder r=new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(redirect==null?200:302).message("fixture").header("Content-Type",mime).body(ResponseBody.create("[]",MediaType.get(mime)));if(redirect!=null)r.header("Location",redirect);return r.build();}).build();
            transport=new SafeHttpTransport(client,new PublicIpPolicy(),host->{dns.incrementAndGet();throw new UnknownHostException("No test DNS/network permitted");},new HostRequestGate(new HostCooldownStore(dir),SourceClock.SYSTEM));
        }
        public void close(){transport.close();}
    }
    @Test public void profileGuardRefusesInitialAndEachRedirectTargetBeforeAnyGet()throws Exception{
        SourceProfiles45.Profile p=SourceProfiles45.byId("forbes-open-articles");String approved="https://www.forbes.ru/mneniya/123-fixture";
        for(String bad:List.of("https://evil.example/story","https://www.forbes.ru/account/login")){
            try(FakeHttp fake=new FakeHttp(temp.newFolder(),"text/html",null)){RequestScope scope=new RequestScope(1,1);scope.targetPolicy(p::articleUrl);SourceFailure failure=assertThrows(SourceFailure.class,()->fake.transport.fetch(bad,SourceTransport.Kind.HTML,SourceTransport.Validators.NONE,scope));assertEquals("PROFILE_TARGET_FORBIDDEN",failure.reason);assertTrue(fake.requests.isEmpty());assertEquals(0,fake.dns.get());}
            try(FakeHttp fake=new FakeHttp(temp.newFolder(),"text/html",bad)){RequestScope scope=new RequestScope(1,1);scope.targetPolicy(p::articleUrl);SourceFailure failure=assertThrows(SourceFailure.class,()->fake.transport.fetch(approved,SourceTransport.Kind.HTML,SourceTransport.Validators.NONE,scope));assertEquals("PROFILE_TARGET_FORBIDDEN",failure.reason);assertEquals(List.of(approved),fake.requests);assertEquals(0,fake.dns.get());}
        }
        for(SourceProfiles45.Profile profile:SourceProfiles45.all())assertTrue(profile.id,profile.discoveryIdentity(SafeHttpTransport.normalizeUrl(profile.endpoint)));
    }
    @Test public void exactApplicationJsonOnlyAtTransportAndReader()throws Exception{
        SourceProfiles45.Profile p=SourceProfiles45.byId("poisknews-biographies");for(String mime:List.of("application/problem+json","application/vendor+json","application/ld+json")){
            try(FakeHttp fake=new FakeHttp(temp.newFolder(),mime,null)){RequestScope scope=new RequestScope(1,1);scope.targetPolicy(p::discoveryIdentity);SourceFailure failure=assertThrows(SourceFailure.class,()->fake.transport.fetch(p.endpoint,SourceTransport.Kind.JSON,SourceTransport.Validators.NONE,scope));assertEquals("MIME_MISMATCH",failure.reason);assertEquals(0,fake.dns.get());}
            SourceTransport.Response r=new SourceTransport.Response("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8),p.endpoint,200,mime,"UTF-8",null,null);assertEquals("PROFILE_JSON_MIME",assertThrows(SourceFailure.class,()->SourceReaders45.discover(p,r,new RequestScope(1,1))).reason);
        }
        try(FakeHttp fake=new FakeHttp(temp.newFolder(),"application/json",null)){RequestScope scope=new RequestScope(1,1);scope.targetPolicy(p::discoveryIdentity);assertEquals("application/json",fake.transport.fetch(p.endpoint,SourceTransport.Kind.JSON,SourceTransport.Validators.NONE,scope).mime);assertEquals(0,fake.dns.get());}
    }
    @Test public void jsonLogicalTokenCapMinusOneCapAndPlusOne()throws Exception{
        assertEquals(100000,BoundedJson45.MAX_TOKENS);
        for(int values:List.of(99998,99999)){String array="["+"0,".repeat(values-1)+"0]";assertEquals(values,BoundedJson45.array(BoundedJson45.parse(array)).size());}
        assertThrows(SourceFailure.class,()->BoundedJson45.parse("["+"0,".repeat(99999)+"0]"));
    }
    @Test public void forbesCorroborationRequiresExactlyOneValidMatchingNewsArticle()throws Exception{
        SourceProfiles45.Profile p=SourceProfiles45.byId("forbes-open-articles");String url="https://www.forbes.ru/mneniya/123-fixture",date="2026-10-25T09:00:00+03:00";FeedItem item=new FeedItem(null,url,"Тема",null,null,null,null,null);
        String valid="{\"@type\":\"NewsArticle\",\"datePublished\":\""+date+"\"}";
        for(String json:List.of("{\"@type\":\"Article\",\"datePublished\":\""+date+"\"}","["+valid+","+valid+"]","{\"@type\":\"NewsArticle\",\"datePublished\":\"invalid\"}","{\"@type\":\"NewsArticle\",\"datePublished\":\"2026-10-24T09:00:00+03:00\"}")){
            ExtractionResult r=forbes(p,url,item,date,json);assertEquals(SourcePublication45.Precision.UNKNOWN,r.publication.primary.precision);assertEquals("FORBES_PRIMARY_CONFLICT",r.publication.conflict);
        }
        ExtractionResult r=forbes(p,url,item,date,valid);assertEquals(SourcePublication45.Precision.INSTANT,r.publication.primary.precision);assertEquals("microdata",r.publication.primary.authority);assertTrue(r.publication.facts.stream().anyMatch(f->f.selector.equals("NewsArticle.datePublished")));
    }
    static ExtractionResult forbes(SourceProfiles45.Profile p,String url,FeedItem i,String date,String json)throws Exception{
        String body=Source45ReaderGuardsTest.page(url,"<article><time itemprop='datePublished' datetime='"+date+"'></time><div class='_6X6B8'><p>Содержательный текст.</p></div></article><script type='application/ld+json'>"+json+"</script>");return SourceReaders45.extract(p,Source45ReaderGuardsTest.html(url,body),i,new RequestScope(1,1));
    }
}

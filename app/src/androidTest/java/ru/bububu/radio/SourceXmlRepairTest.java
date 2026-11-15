package ru.bububu.radio;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import ru.bububu.radio.sources.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Additional actual Android parser regressions. Compilation does not establish a device PASS. */
@RunWith(AndroidJUnit4.class)
public class SourceXmlRepairTest {
    private static final String RIA="https://ria.ru/export/rss2/archive/index.xml";
    private static String ria(String items){return "<rss version='2.0'><channel><title>Own catalogue</title><link>https://ria.ru</link><language>ru</language><copyright>RIA Novosti</copyright>"+items+"</channel></rss>";}
    private static String atom(String entries){return "<feed xmlns='http://www.w3.org/2005/Atom'><title>Own feed</title><id>own-feed</id><updated>2026-10-12T00:00:00Z</updated>"+entries+"</feed>";}
    private static String entry(String id,String body){return "<entry><id>"+id+"</id><title>Own title</title><link href='https://source.test/"+id+"'/>"+body+"</entry>";}
    private static String valid(String id){return entry(id,"<content type='text'>Own valid body.</content>");}
    private static SourceTransport.Response response(String xml,String url){return new SourceTransport.Response(xml.getBytes(StandardCharsets.UTF_8),url,200,"application/xml","UTF-8",null,null);}
    private static FeedDocument parse(String xml)throws Exception{return new XmlFeedReader().parse(response(xml,"https://source.test/feed"),new RequestScope(1,1));}
    private static FeedDocument catalogue(String xml,String url)throws Exception{return new XmlFeedReader().parse(response(xml,url),new RequestScope(1,1),XmlFeedReader.RssPolicy.RIA_CATALOGUE);}
    private interface Checked{void run()throws Exception;}
    private static void failure(String reason,Checked action)throws Exception {try{action.run();fail("Expected "+reason);}catch(SourceFailure expected){assertEquals(reason,expected.reason);}}
    private static void unavailable(FeedItem item,String reason){assertEquals("unavailable",item.inline.completeness);assertEquals(reason,item.inline.reason);assertEquals("",item.inline.body);assertFalse(item.inline.canEnqueue());}
    private static void valid(FeedItem item){assertEquals("Own valid body.",item.inline.body);assertEquals("unknown",item.inline.completeness);assertTrue(item.inline.canEnqueue());}
    @Test public void selectedRiaMissingDescriptionUsesExplicitPolicyAndRetainsItems()throws Exception {
        String xml=ria("<item><guid>own-item</guid><title>Own title</title><description>Own item body.</description></item>");
        FeedDocument feed=catalogue(xml,RIA);assertEquals("rss",feed.type);assertEquals(1,feed.items.size());assertEquals("own-item",feed.items.get(0).providerId);assertEquals("Own item body.",feed.items.get(0).inline.body);assertEquals("unknown",feed.items.get(0).inline.completeness);
        failure("RSS_CHANNEL",()->new XmlFeedReader().parse(response(xml,RIA),new RequestScope(1,1)));
        failure("RSS_CHANNEL",()->catalogue(xml,"https://source.test/feed"));
    }
    @Test public void riaPolicyRejectsChangedMetadataAndNamespacesWithoutWeakeningCustomRss()throws Exception {
        failure("RSS_CHANNEL",()->catalogue(ria("").replace("<language>ru</language>",""),RIA));
        failure("RSS_CHANNEL",()->catalogue(ria("").replace("</copyright>","</copyright><new-block/>"),RIA));
        failure("RSS_CHANNEL",()->catalogue(ria("").replace("<language>","<language xmlns='urn:wrong'>"),RIA));
        failure("UNSUPPORTED_FEED_FORMAT",()->catalogue(ria("").replace("<rss version='2.0'>","<rss xmlns='urn:wrong' version='2.0'>"),RIA));
        failure("RSS_CHANNEL",()->catalogue(ria(""),RIA+"?custom=1"));
    }
    @Test public void riaExceptionKeepsWholeXmlDtdDepthByteCancellationAndDeadlineGuards()throws Exception {
        failure("XML_DTD",()->catalogue("<!DOCTYPE rss SYSTEM 'https://source.test/private'>"+ria(""),RIA));
        failure("XML_ENTITY",()->catalogue(ria("<item><title>Own title</title><description>&unknown;</description></item>"),RIA));
        failure("XML_FORMAT",()->catalogue(ria("").replace("</channel>","</wrong>"),RIA));
        failure("XML_DEPTH",()->catalogue(ria("<x>".repeat(65)+"own"+"</x>".repeat(65)),RIA));
        failure("XML_BYTES_LIMIT",()->catalogue(ria(" ".repeat(SourceLimits.XML_BYTES)),RIA));
        RequestScope cancelled=new RequestScope(1,1);cancelled.cancel();failure("CANCELLED",()->new XmlFeedReader().parse(response(ria(""),RIA),cancelled,XmlFeedReader.RssPolicy.RIA_CATALOGUE));
        class Clock implements SourceClock {long time;public long monotonicMillis(){return time;}public long wallMillis(){return 0;}}
        Clock clock=new Clock();RequestScope expired=new RequestScope(1,1,clock,1000);clock.time=1000;failure("TIMEOUT",()->new XmlFeedReader().parse(response(ria(""),RIA),expired,XmlFeedReader.RssPolicy.RIA_CATALOGUE));
    }
    @Test public void riaOversizedItemDoesNotInvalidateItsValidNeighborOrChannelPolicy()throws Exception {
        String xml=ria("<item><guid>bad</guid><title>Own title</title><description>"+"x".repeat(SourceLimits.MAX_TEXT+1)+"</description></item><item><guid>valid</guid><title>Own title</title><description>Own valid body.</description></item>");
        FeedDocument feed=catalogue(xml,RIA);assertEquals(2,feed.items.size());unavailable(feed.items.get(0),"ITEM_LIMIT");valid(feed.items.get(1));
    }
    @Test public void mixedAtomExternalAndSummaryOnlyUnsupportedEntriesDoNotAbortBatch()throws Exception {
        String badSummary="<summary type='application/octet-stream'>Own unsupported summary.</summary>";
        String external="<content type='text' src='https://127.0.0.1/private'/>";
        FeedDocument feed=parse(atom(valid("first")+entry("bad-external",external+badSummary)+entry("bad-summary",badSummary)+valid("last")));
        assertEquals(4,feed.items.size());valid(feed.items.get(0));unavailable(feed.items.get(1),"UNSUPPORTED_TEXT_CONSTRUCT");unavailable(feed.items.get(2),"UNSUPPORTED_TEXT_CONSTRUCT");valid(feed.items.get(3));
        assertEquals("bad-external",feed.items.get(1).providerId);assertEquals("https://source.test/bad-external",feed.items.get(1).url);
    }
    @Test public void mixedAtomWrongXhtmlSummaryWrapperOrNamespaceLeavesValidNeighbor()throws Exception {
        String external="<content type='text' src='https://source.test/external'/>";
        String wrongWrapper="<summary type='xhtml'><p xmlns='http://www.w3.org/1999/xhtml'>Own invalid wrapper.</p></summary>";
        String wrongNamespace="<summary type='xhtml'><div xmlns='urn:wrong'>Own invalid namespace.</div></summary>";
        FeedDocument feed=parse(atom(entry("wrapper",external+wrongWrapper)+entry("namespace",wrongNamespace)+valid("valid")));
        assertEquals(3,feed.items.size());unavailable(feed.items.get(0),"UNSUPPORTED_XHTML");unavailable(feed.items.get(1),"UNSUPPORTED_XHTML");valid(feed.items.get(2));
    }
    @Test public void mixedAtomKeepsValidExternalSummaryAndBadContentFallbackIsolated()throws Exception {
        String external="<content type='application/octet-stream' src='https://127.0.0.1/private'/>";
        String badContent="<content type='xhtml'><p xmlns='http://www.w3.org/1999/xhtml'>Own invalid wrapper.</p></content>";
        String badSummary="<summary type='application/octet-stream'>Own unsupported summary.</summary>";
        FeedDocument feed=parse(atom(entry("summary",external+"<summary>Own available summary.</summary>")+entry("bad-fallback",badContent+badSummary)+valid("valid")));
        assertEquals(3,feed.items.size());assertEquals("summary",feed.items.get(0).inline.completeness);assertEquals("Own available summary.",feed.items.get(0).inline.body);assertEquals("UNSUPPORTED_ATOM_CONTENT",feed.items.get(0).inline.reason);unavailable(feed.items.get(1),"UNSUPPORTED_TEXT_CONSTRUCT");valid(feed.items.get(2));
    }
    @Test public void atomSemanticItemBoundaryDoesNotSwallowMalformedWholeXmlOrDtd()throws Exception {
        String mixed=atom(entry("unsupported","<summary type='application/octet-stream'>Own summary.</summary>")+valid("valid"));
        failure("XML_FORMAT",()->parse(mixed.replace("</feed>","</wrong>")));
        failure("XML_DTD",()->parse("<!DOCTYPE feed [<!ENTITY external SYSTEM 'https://source.test/private'>]>"+mixed));
    }
}

package ru.bububu.radio;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import ru.bububu.radio.sources.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Real Android XmlPullParser. Compilation is not a runtime result. No mic/network/device setup. */
@RunWith(AndroidJUnit4.class)
public class SourceXmlParserTest {
    private static String rss(String items){return "<?xml version='1.0' encoding='UTF-8'?><rss version='2.0'><channel><title>Источник</title><link>https://source.test/</link><description>Описание</description>"+items+"</channel></rss>";}
    private static String atom(String entries){return "<?xml version='1.0'?><feed xmlns='http://www.w3.org/2005/Atom' xml:base='https://source.test/news/'><title>Источник</title><id>opaque-feed</id><updated>2026-10-12T00:00:00Z</updated>"+entries+"</feed>";}
    private FeedDocument parse(String xml)throws Exception{return parse(xml.getBytes(StandardCharsets.UTF_8),null);}
    private FeedDocument parse(byte[] bytes,String charset)throws Exception {return new XmlFeedReader().parse(new SourceTransport.Response(bytes,"https://source.test/feed",200,"application/xml",charset,null,null),new RequestScope(1,1));}
    private interface Checked{void run()throws Exception;}
    private static void failure(String reason,Checked action)throws Exception{try{action.run();fail("Expected "+reason);}catch(SourceFailure expected){assertEquals(reason,expected.reason);}}
    @Test public void rssDescriptionUnknownGuidOpaqueEntitiesAndParagraphs()throws Exception {
        String item="<item><guid isPermaLink='false'>opaque:123</guid><title>Заголовок</title><link>https://source.test/news/1</link><pubDate>Mon, 12 Oct 2026 03:00:00 +0300</pubDate><description><![CDATA[<p>Первый &amp; второй.</p><p>Конец.</p>]]></description></item>";
        FeedItem parsed=parse(rss(item)).items.get(0);assertEquals("opaque:123",parsed.providerId);assertEquals("unknown",parsed.inline.completeness);assertEquals("Первый & второй.\n\nКонец.",parsed.inline.body);assertNotNull(parsed.publishedAtMillis);
    }
    @Test public void rssCp1251DeclarationReallyDecodedByProductionPath()throws Exception {
        String xml=rss("<item><title>Русский заголовок</title><description>Русское тело.</description></item>").replace("encoding='UTF-8'","encoding='windows-1251'");
        FeedItem item=parse(xml.getBytes(Charset.forName("windows-1251")),null).items.get(0);assertEquals("Русский заголовок",item.title);assertEquals("Русское тело.",item.inline.body);
    }
    @Test public void activeDoctypeExternalEntitiesRejectedBeforeAnyResolution()throws Exception {failure("XML_DTD",()->parse("<!DOCTYPE rss SYSTEM 'https://source.test/private'><rss version='2.0'><channel/></rss>"));}
    @Test public void doctypeLiteralInsideCdataIsSafeText()throws Exception {FeedItem item=parse(rss("<item><title>Заголовок</title><description><![CDATA[&lt;!DOCTYPE harmless&gt;]]></description></item>")).items.get(0);assertEquals("<!DOCTYPE harmless>",item.inline.body);}
    @Test public void undeclaredEntityRefRejectedAndPredefinedNumericEntitiesAccepted()throws Exception {failure("XML_ENTITY",()->parse(rss("<item><title>Заголовок</title><description>&unknown;</description></item>")));assertEquals("Буква А & знак.",parse(rss("<item><title>Заголовок</title><description>Буква &#1040; &amp;amp; знак.</description></item>")).items.get(0).inline.body);}
    @Test public void atomNamespacePrefixAndInheritedXmlBase()throws Exception {
        String xml="<a:feed xmlns:a='http://www.w3.org/2005/Atom' xml:base='https://source.test/news/'><a:title>Источник</a:title><a:id>opaque-feed</a:id><a:updated>2026-10-12T00:00:00Z</a:updated><a:entry xml:base='../'><a:id>opaque-entry</a:id><a:title>Заголовок</a:title><a:link href='post'/><a:content type='text'>Буквально &lt;p&gt;.</a:content></a:entry></a:feed>";
        FeedItem item=parse(xml).items.get(0);assertEquals("opaque-entry",item.providerId);assertEquals("https://source.test/post",item.url);assertEquals("Буквально <p>.",item.inline.body);assertEquals("unknown",item.inline.completeness);
    }
    @Test public void atomHtmlAndXhtmlPreserveMixedContentOrder()throws Exception {
        String entries="<entry><id>html</id><title>Заголовок</title><content type='html'>&lt;p&gt;Перед &lt;a href='https://outside.test'&gt;ссылкой&lt;/a&gt; после.&lt;/p&gt;</content></entry><entry><id>xhtml</id><title>Заголовок</title><content type='xhtml'><div xmlns='http://www.w3.org/1999/xhtml'><p>Перед <a href='https://outside.test'>ссылкой</a> после.</p><blockquote>Цитата<br/>Продолжение</blockquote></div></content></entry>";
        FeedDocument document=parse(atom(entries));assertEquals("Перед ссылкой после.",document.items.get(0).inline.body);assertEquals("Перед ссылкой после.\n\nЦитата\nПродолжение",document.items.get(1).inline.body);
    }
    @Test public void atomExternalContentDoesNotFetchAndKeepsSummary()throws Exception {FeedItem item=parse(atom("<entry><id>opaque</id><title>Заголовок</title><content type='text' src='https://127.0.0.1/private'/><summary>Короткий анонс.</summary></entry>")).items.get(0);assertEquals("summary",item.inline.completeness);assertEquals("UNSUPPORTED_ATOM_CONTENT",item.inline.reason);assertEquals("Короткий анонс.",item.inline.body);}
    @Test public void atomBinaryUnsupportedWithoutInventedFull()throws Exception {FeedItem item=parse(atom("<entry><id>opaque</id><title>Заголовок</title><content type='image/png'>AAAA</content></entry>")).items.get(0);assertFalse(item.inline.canEnqueue());assertEquals("UNSUPPORTED_ATOM_CONTENT",item.inline.reason);}
    @Test public void malformedXhtmlWrapperIsItemErrorNotGuessedBody()throws Exception {FeedItem item=parse(atom("<entry><id>opaque</id><title>Заголовок</title><content type='xhtml'><p xmlns='http://www.w3.org/1999/xhtml'>Неверный wrapper.</p></content></entry>")).items.get(0);assertFalse(item.inline.canEnqueue());assertEquals("UNSUPPORTED_XHTML",item.inline.reason);}
    @Test public void depthGuardAndMalformedTailStillApplyAfterFiveHundredItems()throws Exception {failure("XML_DEPTH",()->parse(rss("<x>".repeat(65)+"text"+"</x>".repeat(65))));String item="<item><guid>id</guid><title>Заголовок</title></item>";String broken=rss(item.repeat(501)).replace("</channel>","</wrong>");failure("XML_FORMAT",()->parse(broken));}
    @Test public void fiveHundredLimitFlagsValidFeedAndDoesNotTruncateXmlValidation()throws Exception {FeedDocument document=parse(rss("<item><title>Заголовок</title></item>".repeat(501)));assertEquals(500,document.items.size());assertTrue(document.itemLimitReached);}
    @Test public void invalidDatePreservedWithoutInventedPublicationTime()throws Exception {FeedItem item=parse(rss("<item><title>Заголовок</title><pubDate>not-a-date</pubDate><description>Текст.</description></item>")).items.get(0);assertEquals("not-a-date",item.publishedRaw);assertNull(item.publishedAtMillis);}
    @Test public void wrongNamespaceAndRdfNotAcceptedAsAtomOrRss()throws Exception {failure("UNSUPPORTED_FEED_FORMAT",()->parse("<feed xmlns='https://wrong.test/Atom'><title>Источник</title></feed>"));failure("UNSUPPORTED_FEED_FORMAT",()->parse("<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'/>"));}
    @Test public void validEmptyIsDifferentFromMalformedFeed()throws Exception {assertTrue(parse(rss("")).items.isEmpty());failure("RSS_CHANNEL",()->parse("<rss version='2.0'><channel/></rss>"));}
    @Test public void charsetConflictAndOversizedItemCannotBecomeFull()throws Exception {String cp=rss("<item><title>Заголовок</title><description>Текст.</description></item>").replace("encoding='UTF-8'","encoding='windows-1251'");failure("CHARSET_CONFLICT",()->parse(cp.getBytes(Charset.forName("windows-1251")),"UTF-8"));FeedItem item=parse(rss("<item><title>Заголовок</title><description>"+"а".repeat(20_001)+"</description></item>")).items.get(0);assertFalse(item.inline.canEnqueue());assertEquals("ITEM_LIMIT",item.inline.reason);}
    @Test public void oversizedRssEncodedUsesWholeSummaryAndPreservesDescriptionLimit()throws Exception {
        String start="<content:encoded xmlns:content='http://purl.org/rss/1.0/modules/content/'>";
        String rejected=start+"<![CDATA["+"E".repeat(20001)+"]]><![CDATA[POISON_PARTIAL_AFTER_OVERFLOW]]></content:encoded>";
        String description="Целый синтетический анонс. Служебный маркер сохраняется.";
        String first="<item><guid>whole-summary</guid><title>Заголовок</title><link>https://source.test/summary</link>"+rejected+"<description><![CDATA[<p>"+description+"</p>]]></description></item>";
        String second="<item><guid>oversized-summary</guid><title>Заголовок</title><link>https://source.test/oversized</link>"+rejected+"<description>"+"D".repeat(20001)+"</description></item>";
        FeedDocument feed=parse(rss(first+second));assertEquals(2,feed.items.size());ExtractionResult good=feed.items.get(0).inline;
        assertEquals(description,good.body);assertFalse(good.body.contains("POISON"));assertEquals("summary",good.completeness);
        assertEquals("OVERSIZED_CONTENT_ENCODED_DESCRIPTION_FALLBACK",good.reason);assertEquals("rss-description-fallback-v1",good.adapterVersion);
        SourceArticleFramer.Framed frame=SourceArticleFramer.frame("Источник",good);assertEquals(description,frame.spokenText.substring(frame.bodyStart,frame.bodyEnd));
        assertFalse(feed.items.get(1).inline.canEnqueue());assertEquals("ITEM_LIMIT",feed.items.get(1).inline.reason);
    }

}

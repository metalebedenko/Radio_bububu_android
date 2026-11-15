package ru.bububu.radio.sources;

import org.junit.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

/** Production conversion only. The actual Android XML frontend is covered by native cases. */
public class SourceFeedConversionRepairTest {
    private static final String ATOM="http://www.w3.org/2005/Atom",XHTML="http://www.w3.org/1999/xhtml",RIA="https://ria.ru/export/rss2/archive/index.xml";
    private static final Class<?> X;
    private static final Constructor<?> CONSTRUCTOR;
    private static final Field ATTRS,CHILDREN,CONTENT,TEXT,LARGE,TEXT_LARGE,ATTRIBUTE_LARGE;
    private static final Method ATOM_ITEM,ACCEPTED_CHANNEL,RSS_ITEM;
    static {
        try {
            X=Class.forName("ru.bububu.radio.sources.XmlFeedReader$X");CONSTRUCTOR=X.getDeclaredConstructor(String.class,String.class,String.class);CONSTRUCTOR.setAccessible(true);
            ATTRS=field("attrs");CHILDREN=field("children");CONTENT=field("content");TEXT=field("text");LARGE=field("large");TEXT_LARGE=field("textLarge");ATTRIBUTE_LARGE=field("attributeLarge");
            RSS_ITEM=XmlFeedReader.class.getDeclaredMethod("rss",X,RequestScope.class);RSS_ITEM.setAccessible(true);
            ATOM_ITEM=XmlFeedReader.class.getDeclaredMethod("atom",X,RequestScope.class);ATOM_ITEM.setAccessible(true);
            ACCEPTED_CHANNEL=XmlFeedReader.class.getDeclaredMethod("acceptedRssChannel",X,String.class,XmlFeedReader.RssPolicy.class);ACCEPTED_CHANNEL.setAccessible(true);
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static Field field(String name)throws ReflectiveOperationException {Field field=X.getDeclaredField(name);field.setAccessible(true);return field;}
    @SuppressWarnings("unchecked")
    private static Object node(String name,String ns,String value)throws Exception {
        Object result=CONSTRUCTOR.newInstance(name,ns,"https://source.test/feed");
        if(value!=null){((StringBuilder)TEXT.get(result)).append(value);((List<Object>)CONTENT.get(result)).add(value);}return result;
    }
    @SuppressWarnings("unchecked") private static Object attr(Object node,String key,String value)throws Exception {((Map<String,String>)ATTRS.get(node)).put("|"+key,value);return node;}
    @SuppressWarnings("unchecked") private static Object child(Object parent,Object child)throws Exception {((List<Object>)CHILDREN.get(parent)).add(child);((List<Object>)CONTENT.get(parent)).add(child);return parent;}
    private static Object channel()throws Exception {
        Object channel=node("channel","",null);
        child(channel,node("title","","Own catalogue"));child(channel,node("link","","https://ria.ru"));child(channel,node("language","","ru"));child(channel,node("copyright","","RIA Novosti"));return channel;
    }
    private static boolean accepted(Object channel,String url,XmlFeedReader.RssPolicy policy)throws Exception {return (Boolean)ACCEPTED_CHANNEL.invoke(null,channel,url,policy);}
    private static Object entry(String id,Object content,Object summary)throws Exception {
        Object entry=node("entry",ATOM,null);child(entry,node("id",ATOM,id));child(entry,node("title",ATOM,"Own title"));
        child(entry,attr(node("link",ATOM,null),"href","https://source.test/"+id));if(content!=null)child(entry,content);if(summary!=null)child(entry,summary);return entry;
    }
    private static FeedItem convert(Object node,RequestScope scope)throws Exception {
        try{return (FeedItem)ATOM_ITEM.invoke(null,node,scope);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw new AssertionError(failure.getCause());}
    }
    private static FeedItem convert(Object node)throws Exception {return convert(node,new RequestScope(1,1));}
    private static Object external()throws Exception {return attr(attr(node("content",ATOM,null),"src","https://127.0.0.1/private"),"type","text");}
    private static Object unsupportedSummary()throws Exception {return attr(node("summary",ATOM,"Own unsupported summary"),"type","application/octet-stream");}
    private static Object wrongXhtmlSummary(String namespace)throws Exception {Object summary=attr(node("summary",ATOM,null),"type","xhtml");child(summary,node("p",namespace,"Own invalid wrapper"));return summary;}
    private static void unavailable(FeedItem item,String reason) {assertEquals("unavailable",item.inline.completeness);assertEquals(reason,item.inline.reason);assertEquals("",item.inline.body);assertFalse(item.inline.canEnqueue());}
    private static void validNeighbor()throws Exception {FeedItem valid=convert(entry("valid",node("content",ATOM,"Own valid body"),null));assertEquals("valid",valid.providerId);assertEquals("Own valid body",valid.inline.body);assertEquals("unknown",valid.inline.completeness);}
    @Test public void missingDescriptionAcceptedOnlyByExplicitRiaCataloguePolicy()throws Exception {
        Object channel=channel();assertTrue(accepted(channel,RIA,XmlFeedReader.RssPolicy.RIA_CATALOGUE));
        assertFalse(accepted(channel,RIA,XmlFeedReader.RssPolicy.STRICT_RSS2));
        for(String url:List.of("https://source.test/feed","https://ria.ru/export/rss2/archive/index.xml?custom=1","https://ria.ru/export/rss2/index.xml","http://ria.ru/export/rss2/archive/index.xml"))assertFalse(accepted(channel,url,XmlFeedReader.RssPolicy.RIA_CATALOGUE));
    }
    @Test public void riaDeviationCannotAdmitNewOrAmbiguousChannelStructure()throws Exception {
        for(Object channel:List.of(child(channel(),node("description","urn:other","Wrong namespace")),child(channel(),node("language","","ru")),child(channel(),node("new-block","",null))))assertFalse(accepted(channel,RIA,XmlFeedReader.RssPolicy.RIA_CATALOGUE));
        Object loose=channel();((StringBuilder)TEXT.get(loose)).append("Additional words");assertFalse(accepted(loose,RIA,XmlFeedReader.RssPolicy.RIA_CATALOGUE));
        Object missing=node("channel","",null);child(missing,node("title","","Own title"));child(missing,node("link","","https://ria.ru"));assertFalse(accepted(missing,RIA,XmlFeedReader.RssPolicy.RIA_CATALOGUE));
    }
    @Test public void riaItemLimitStaysAnItemBoundaryAndStrictDescriptionGateIsPreserved()throws Exception {
        Object channel=channel(),item=node("item","",null);LARGE.setBoolean(item,true);child(channel,item);assertTrue(accepted(channel,RIA,XmlFeedReader.RssPolicy.RIA_CATALOGUE));
        child(channel,node("description","","Own description"));assertTrue(accepted(channel,"https://source.test/feed",XmlFeedReader.RssPolicy.STRICT_RSS2));
    }
    @Test public void unsupportedSummaryOnExternalContentIsUnavailableOnlyForThatEntry()throws Exception {
        FeedItem bad=convert(entry("bad",external(),unsupportedSummary()));unavailable(bad,"UNSUPPORTED_TEXT_CONSTRUCT");assertEquals("bad",bad.providerId);assertEquals("https://source.test/bad",bad.url);validNeighbor();
    }
    @Test public void unsupportedSummaryOnlyIsUnavailableAndValidEntrySurvives()throws Exception {
        unavailable(convert(entry("bad",null,unsupportedSummary())),"UNSUPPORTED_TEXT_CONSTRUCT");validNeighbor();
    }
    @Test public void wrongXhtmlFallbackCannotAbortOtherEntriesOrInventAFullBody()throws Exception {
        for(String ns:List.of(XHTML,"urn:wrong"))unavailable(convert(entry("bad",external(),wrongXhtmlSummary(ns))),"UNSUPPORTED_XHTML");validNeighbor();
    }
    @Test public void validSummaryStillSupportsExternalAndUnsupportedContentFallback()throws Exception {
        for(Object content:List.of(external(),attr(node("content",ATOM,"AAAA"),"type","application/octet-stream"))) {
            FeedItem item=convert(entry("summary",content,node("summary",ATOM,"Own summary")));
            assertEquals("summary",item.inline.completeness);assertEquals("Own summary",item.inline.body);assertEquals("UNSUPPORTED_ATOM_CONTENT",item.inline.reason);
        }
        Object wrong=attr(node("content",ATOM,null),"type","xhtml");child(wrong,node("p",XHTML,"Own invalid wrapper"));
        FeedItem item=convert(entry("fallback",wrong,node("summary",ATOM,"Own summary")));assertEquals("summary",item.inline.completeness);assertEquals("UNSUPPORTED_XHTML",item.inline.reason);
    }
    @Test public void conversionDoesNotConsumeCancellationOrDeadlineFromFallback()throws Exception {
        final RequestScope[] active=new RequestScope[1];
        SourceClock cancelClock=new SourceClock(){int calls;public long wallMillis(){return 0;}public long monotonicMillis(){if(++calls==2)active[0].cancel();return 0;}};
        active[0]=new RequestScope(1,1,cancelClock,90_000);
        try{convert(entry("cancel",external(),node("summary",ATOM,"Own summary")),active[0]);fail();}catch(SourceFailure expected){assertEquals("CANCELLED",expected.reason);}
        SourceClock timeoutClock=new SourceClock(){int calls;public long wallMillis(){return 0;}public long monotonicMillis(){return ++calls>=3?90_000:0;}};
        try{convert(entry("timeout",external(),node("summary",ATOM,"Own summary")),new RequestScope(1,1,timeoutClock,90_000));fail();}catch(SourceFailure expected){assertEquals("TIMEOUT",expected.reason);}
    }
    private static final String CONTENT_NS="http://purl.org/rss/1.0/modules/content/";
    private static Object rssEntry(Object encoded,Object description)throws Exception {
        Object item=node("item","",null);child(item,node("title","","Own RSS title"));
        child(item,node("guid","","opaque-rss"));child(item,node("link","","https://source.test/rss-item"));
        if(encoded!=null)child(item,encoded);if(description!=null)child(item,description);return item;
    }
    private static Object oversizedEncoded()throws Exception {
        Object encoded=node("encoded",CONTENT_NS,"POISON_PARTIAL_AFTER_OVERFLOW");
        LARGE.setBoolean(encoded,true);TEXT_LARGE.setBoolean(encoded,true);return encoded;
    }
    private static FeedItem rssConvert(Object item)throws Exception {return rssConvert(item,new RequestScope(1,1));}
    private static FeedItem rssConvert(Object item,RequestScope scope)throws Exception {
        try{return (FeedItem)RSS_ITEM.invoke(null,item,scope);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw new AssertionError(failure.getCause());}
    }
    @Test public void rssFigureNoneUsesRussianDescriptionAsSummary()throws Exception {
        String summary="Короткое русское описание события.";
        Object item=rssEntry(node("encoded",CONTENT_NS,"<figure><img src='https://images.test/image.jpg'></figure>None"),node("description","",summary));
        child(item,node("pubDate","","Tue, 20 Oct 2026 08:00:00 GMT"));
        FeedItem result=rssConvert(item);
        assertEquals(summary,result.inline.body);assertEquals("summary",result.inline.completeness);
        assertEquals("NONE_CONTENT_ENCODED_DESCRIPTION_FALLBACK",result.inline.reason);
        assertTrue(result.inline.canEnqueue());assertEquals("opaque-rss",result.providerId);assertEquals("https://source.test/rss-item",result.url);
        assertEquals("Tue, 20 Oct 2026 08:00:00 GMT",result.publishedRaw);assertEquals(Long.valueOf(java.time.Instant.parse("2026-10-20T08:00:00Z").toEpochMilli()),result.publishedAtMillis);assertNull(result.updatedRaw);
    }

    @Test public void rssEmptyNormalizedEncodedUsesWholeDescriptionWithoutClaimingFull()throws Exception {
        for(String encoded:List.of("", "  ", "<figure><img src='https://images.test/x'></figure>", "<script>hidden</script>")) {
            FeedItem result=rssConvert(rssEntry(node("encoded",CONTENT_NS,encoded),node("description","","<p>Whole summary.</p>")));
            assertEquals("Whole summary.",result.inline.body);assertEquals("summary",result.inline.completeness);
            assertEquals("EMPTY_CONTENT_ENCODED_DESCRIPTION_FALLBACK",result.inline.reason);assertEquals("rss_description_fallback",result.inline.method);
            assertNotEquals("full",result.inline.completeness);
        }
    }
    @Test public void rssMissingEmptyAndExactNoneDescriptionsAreNeverReadable()throws Exception {
        List<Object> descriptions=new ArrayList<>();descriptions.add(null);
        for(String value:List.of("", "  ", "None", "<b>None</b>", "<figure><img src='https://images.test/x'></figure>"))descriptions.add(node("description","",value));
        for(String encoded:List.of("None","<figure><img src='https://images.test/x'></figure>None","<p></p>"))for(Object description:descriptions) {
            FeedItem result=rssConvert(rssEntry(node("encoded",CONTENT_NS,encoded),description));
            assertEquals("",result.inline.body);assertFalse(result.inline.canEnqueue());assertEquals("unavailable",result.inline.completeness);
            assertTrue(result.inline.reason.endsWith("_FALLBACK_EMPTY"));
        }
        for(String value:List.of("None","<b>None</b>")) {
            FeedItem result=rssConvert(rssEntry(null,node("description","",value)));
            assertEquals("",result.inline.body);assertFalse(result.inline.canEnqueue());assertEquals("DESCRIPTION_PLACEHOLDER_NONE",result.inline.reason);
        }
        FeedItem oversized=rssConvert(rssEntry(oversizedEncoded(),node("description","","None")));
        unavailable(oversized,"DESCRIPTION_FALLBACK_EMPTY");
    }
    @Test public void rssContentfulEncodedAndNonExactWordsKeepPriorityAndAtomIsUnchanged()throws Exception {
        for(String text:List.of("Actual encoded body.","None is part of this sentence.","none","NONE","None.")) {
            FeedItem result=rssConvert(rssEntry(node("encoded",CONTENT_NS,"<p>"+text+"</p>"),node("description","","Ignored description")));
            assertEquals(text,result.inline.body);assertEquals("unknown",result.inline.completeness);
            assertEquals("CONTENT_COMPLETENESS_UNKNOWN",result.inline.reason);assertEquals("feed-inline-v1",result.inline.adapterVersion);
        }
        FeedItem atom=convert(entry("atom",node("content",ATOM,"None"),node("summary",ATOM,"Ignored summary")));
        assertEquals("None",atom.inline.body);assertEquals("unknown",atom.inline.completeness);
        FeedItem description=rssConvert(rssEntry(null,node("description","","Normal description")));
        assertEquals("Normal description",description.inline.body);assertEquals("unknown",description.inline.completeness);
    }
    @Test public void rssEmptyFallbackCannotBypassDomBudgetOrItemLimits()throws Exception {
        String deep="<div>".repeat(600)+"None"+"</div>".repeat(600);
        unavailable(rssConvert(rssEntry(node("encoded",CONTENT_NS,deep),node("description","","Safe summary"))),"DOM_LIMIT");
        unavailable(rssConvert(rssEntry(node("encoded",CONTENT_NS,"None"),node("description","",deep))),"DOM_LIMIT");
        Object limited=rssEntry(node("encoded",CONTENT_NS,"None"),node("description","","Safe summary"));LARGE.setBoolean(limited,true);
        unavailable(rssConvert(limited),"ITEM_LIMIT");
        Object tooLarge=node("description","","Safe summary");LARGE.setBoolean(tooLarge,true);
        unavailable(rssConvert(rssEntry(node("encoded",CONTENT_NS,"None"),tooLarge)),"ITEM_LIMIT");
    }
    @Test public void rssEmptyFallbackPropagatesCancellationAndTimeoutDuringDescriptionConversion()throws Exception {
        final RequestScope[] active=new RequestScope[1];
        SourceClock cancelClock=new SourceClock(){int calls;public long wallMillis(){return 0;}public long monotonicMillis(){if(++calls==5)active[0].cancel();return 0;}};
        active[0]=new RequestScope(1,1,cancelClock,90_000);
        try{rssConvert(rssEntry(node("encoded",CONTENT_NS,"None"),node("description","","Own summary")),active[0]);fail();}
        catch(SourceFailure expected){assertEquals("CANCELLED",expected.reason);}
        SourceClock timeoutClock=new SourceClock(){int calls;public long wallMillis(){return 0;}public long monotonicMillis(){return ++calls>=6?90_000:0;}};
        try{rssConvert(rssEntry(node("encoded",CONTENT_NS,"None"),node("description","","Own summary")),new RequestScope(1,1,timeoutClock,90_000));fail();}
        catch(SourceFailure expected){assertEquals("TIMEOUT",expected.reason);}
    }

    @Test public void rssRejectedFieldUsesWholeSummaryAndStableProductionProvenance()throws Exception {
        for(int length:List.of(667,146,108,223,209)) {
            String body="S".repeat(length);FeedItem item=rssConvert(rssEntry(oversizedEncoded(),node("description","",body)));
            assertEquals(body,item.inline.body);assertEquals("summary",item.inline.completeness);
            assertEquals("OVERSIZED_CONTENT_ENCODED_DESCRIPTION_FALLBACK",item.inline.reason);
            assertEquals("rss-description-fallback-v1",item.inline.adapterVersion);assertEquals("rss_description_fallback",item.inline.method);
            assertFalse(item.inline.body.contains("POISON"));assertTrue(item.inline.canEnqueue());
            SourceArticleFramer.Framed frame=SourceArticleFramer.frame("Fixture source",item.inline);
            assertEquals(body,frame.spokenText.substring(frame.bodyStart,frame.bodyEnd));
        }
    }
    @Test public void rssFallbackCannotHideOversizedAttributeOrUnknownLargeCause()throws Exception {
        Object attributed=oversizedEncoded();ATTRIBUTE_LARGE.setBoolean(attributed,true);
        unavailable(rssConvert(rssEntry(attributed,node("description","","Summary"))),"ITEM_LIMIT");
        Object unknown=node("encoded",CONTENT_NS,"partial");LARGE.setBoolean(unknown,true);
        unavailable(rssConvert(rssEntry(unknown,node("description","","Summary"))),"ITEM_LIMIT");
    }
    @Test public void rssFallbackRejectsOtherMetadataAndProviderTitleIdLimits()throws Exception {
        Object metadata=rssEntry(oversizedEncoded(),node("description","","Summary")),bad=node("category","","partial");LARGE.setBoolean(bad,true);child(metadata,bad);
        unavailable(rssConvert(metadata),"ITEM_LIMIT");
        Object title=rssEntry(oversizedEncoded(),node("description","","Summary"));Object titleNode=((List<?>)CHILDREN.get(title)).get(0);((StringBuilder)TEXT.get(titleNode)).setLength(0);((List<?>)CONTENT.get(titleNode)).clear();((StringBuilder)TEXT.get(titleNode)).append("T".repeat(2001));((List<Object>)CONTENT.get(titleNode)).add("T".repeat(2001));
        unavailable(rssConvert(title),"ITEM_LIMIT");
        Object id=rssEntry(oversizedEncoded(),node("description","","Summary"));Object idNode=((List<?>)CHILDREN.get(id)).get(1);((StringBuilder)TEXT.get(idNode)).setLength(0);((List<?>)CONTENT.get(idNode)).clear();((List<Object>)CONTENT.get(idNode)).add("G".repeat(4097));
        unavailable(rssConvert(id),"ITEM_LIMIT");
    }
    @Test public void rssFallbackRejectsMissingBlankAndOversizedDescription()throws Exception {
        unavailable(rssConvert(rssEntry(oversizedEncoded(),null)),"ITEM_LIMIT");
        unavailable(rssConvert(rssEntry(oversizedEncoded(),node("description","","  "))),"ITEM_LIMIT");
        Object description=node("description","","partial");LARGE.setBoolean(description,true);TEXT_LARGE.setBoolean(description,true);
        unavailable(rssConvert(rssEntry(oversizedEncoded(),description)),"ITEM_LIMIT");
    }
    @Test public void rssFallbackRejectsDuplicateEncodedOrDescriptionAndNestedFields()throws Exception {
        Object duplicateContent=rssEntry(oversizedEncoded(),node("description","","Summary"));child(duplicateContent,node("encoded",CONTENT_NS,"Second body"));unavailable(rssConvert(duplicateContent),"ITEM_LIMIT");
        Object duplicateDescription=rssEntry(oversizedEncoded(),node("description","","Summary"));child(duplicateDescription,node("description","","Second summary"));unavailable(rssConvert(duplicateDescription),"ITEM_LIMIT");
        Object nested=oversizedEncoded();child(nested,node("inner","","Nested"));unavailable(rssConvert(rssEntry(nested,node("description","","Summary"))),"ITEM_LIMIT");
        Object nestedDescription=node("description","",null);child(nestedDescription,node("p","","Nested summary"));unavailable(rssConvert(rssEntry(oversizedEncoded(),nestedDescription)),"ITEM_LIMIT");
    }
    @Test public void rssLegacyFieldsAndSmallDuplicatesKeepOldBodyAndVersion()throws Exception {
        Object legacy=rssEntry(node("encoded",CONTENT_NS,"<p>Old encoded body.</p>"),node("description","","Ignored"));child(legacy,node("encoded",CONTENT_NS,"Second encoded"));child(legacy,node("description","","Second description"));
        FeedItem result=rssConvert(legacy);assertEquals("Old encoded body.",result.inline.body);assertEquals("unknown",result.inline.completeness);assertEquals("feed-inline-v1",result.inline.adapterVersion);assertEquals("CONTENT_COMPLETENESS_UNKNOWN",result.inline.reason);
        FeedItem description=rssConvert(rssEntry(null,node("description","","Old description body.")));assertEquals("Old description body.",description.inline.body);assertEquals("unknown",description.inline.completeness);assertEquals("feed-inline-v1",description.inline.adapterVersion);
    }
    @Test public void rssFallbackCannotUseUnsafeOrOversizedLink()throws Exception {
        for(String url:List.of("https://127.0.0.1/private","https://source.test/"+"x".repeat(4096))) {
            Object item=rssEntry(oversizedEncoded(),node("description","","Summary")),link=((List<?>)CHILDREN.get(item)).get(2);((List<?>)CONTENT.get(link)).clear();((List<Object>)CONTENT.get(link)).add(url);unavailable(rssConvert(item),"ITEM_LIMIT");
        }
    }
    @Test public void rssFallbackKeepsDomBudgetAndCannotPromoteEmptyFilteredBody()throws Exception {
        FeedItem deep=rssConvert(rssEntry(oversizedEncoded(),node("description","","<div>".repeat(600)+"Important"+"</div>".repeat(600))));
        assertFalse(deep.inline.canEnqueue());assertEquals("unavailable",deep.inline.completeness);assertEquals("DESCRIPTION_FALLBACK_REJECTED_DOM_LIMIT",deep.inline.reason);
        FeedItem empty=rssConvert(rssEntry(oversizedEncoded(),node("description","","<script>not-readable</script>")));assertFalse(empty.inline.canEnqueue());assertEquals("DESCRIPTION_FALLBACK_EMPTY",empty.inline.reason);
    }
    private static final String CREDIT="<p>Материал «<a href='/rss-item'>Own RSS title</a>» был впервые опубликован на сайте <a href='/'>Издание</a>.</p>";
    private static FeedItem rich(String article)throws Exception{
        return rssConvert(rssEntry(node("encoded",CONTENT_NS,article),node("description","","Анонс.")));
    }
    @Test public void finalVerifiedPublisherCreditAndDuplicateImageDescriptionLeaveEditorialTextAndAttribution()throws Exception{
        String article="<article><p>Первый абзац.</p><figure><img src='/photo'>"
                +"<figcaption>Подпись к фотографии / ©Ирина</figcaption><div class='image-description'>Подпись к фотографии</div></figure>"
                +"<p>Последний вывод.</p>"+CREDIT+"</article>";
        FeedItem got=rich(article);
        assertEquals("Первый абзац.\n\nПодпись к фотографии / ©Ирина\n\nПоследний вывод.",got.inline.body);
        assertEquals("unknown",got.inline.completeness);assertEquals(RssArticleText.REVISION,got.inline.adapterVersion);
        FeedItem copy=rich("<figure><img src='/graph'><figcaption>Точка на карте.</figcaption>"
                +"<div class='photoDescription'>Точка на карте.</div></figure><p>Текст.</p>");
        assertEquals("Точка на карте.\n\nТекст.",copy.inline.body);
        RssArticleText secondPublisher=RssArticleText.read("<section><p>Editorial text.</p><p>The post "
                +"<a href='https://journal.example/stories/sky'>The sky</a> appeared first on "
                +"<a href='https://journal.example/'>The Journal</a>.</p></section>","https://journal.example/feed",
                "https://journal.example/stories/sky","The sky",new RequestScope(1,0));
        assertTrue(secondPublisher.cleaned);assertEquals("Editorial text.",secondPublisher.body);
    }
    @Test public void similarFinalParagraphsKeepFactsAndUnsafeOrUnmatchedLinks()throws Exception{
        for(String end:List.of(CREDIT.replace("<a href='/'>Издание</a>","Издание"),
                CREDIT.replace("'/rss-item'","'/rss-item?edition=2'"),CREDIT.replace("'/rss-item'","'https://lookalike.example/rss-item'"),
                CREDIT.replace("href='/'","href='https://source.test.example/'"),CREDIT.replace("'/rss-item'","'javascript:alert(1)'"),
                CREDIT.replace("Own RSS title","Похожий заголовок"),CREDIT.replace("Издание</a>.","Издание</a> в 1913 году."),
                "<blockquote>"+CREDIT+"</blockquote>","<figure><figcaption>"+CREDIT+"</figcaption></figure>",
                CREDIT+"Текст после абзаца.",CREDIT+"<p>Другой факт.</p>")){
            FeedItem got=rich("<p>Редакционный текст.</p>"+end);
            assertTrue(end,got.inline.body.contains("был впервые опубликован"));assertEquals("feed-inline-v1",got.inline.adapterVersion);
        }
        assertTrue(rich(CREDIT).inline.body.contains("был впервые опубликован"));
    }
    @Test public void differentDescriptionsAndAttributionsRemainInsideTheSameFigure()throws Exception{
        for(String text:List.of("Другой ракурс.","Снимок показывает осадок.","Снимок / ©Другой автор")){
            String figure="<p>Статья.</p><figure><img src='/photo'><figcaption>Снимок / ©Первый автор</figcaption>"
                    +"<div class='image-description'>"+text+"</div></figure>";
            FeedItem got=rich(figure);assertTrue(got.inline.body.contains("©Первый автор"));assertTrue(got.inline.body.contains(text));
            assertEquals("feed-inline-v1",got.inline.adapterVersion);
        }
    }

}

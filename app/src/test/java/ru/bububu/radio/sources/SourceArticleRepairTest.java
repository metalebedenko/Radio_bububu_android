package ru.bububu.radio.sources;

import org.junit.Test;
import static org.junit.Assert.*;

/** Own minimal structures only; these regressions do not embed publisher articles. */
public class SourceArticleRepairTest {
    private static final String URL="https://www.interfax.ru/world/123";
    private static String logo() {
        return "<div itemprop='logo' itemscope itemtype='https://schema.org/ImageObject'>"
            +"<link rel='url' itemprop='url' href='https://www.interfax.ru/img/logo200.png'>"
            +"<link rel='thumbnail' itemprop='thumbnail' href='https://www.interfax.ru/img/logo200.png'>"
            +"<meta itemprop='width' content='200'><meta itemprop='height' content='60'></div>";
    }
    private static String publisher(String logo) {
        return "<div class='h' itemprop='publisher' itemscope itemtype='https://schema.org/Organization'>"+logo
            +"<meta itemprop='name' content='Интерфакс'><link rel='url' itemprop='url' href='https://www.interfax.ru'></div>";
    }
    private static ExtractionResult article(String body)throws Exception {
        return new InterfaxArticleAdapter().extract(SourceSemanticAdaptersTest.html(URL,SourceSemanticAdaptersTest.interfax(body)),SourceSemanticAdaptersTest.scope());
    }
    @Test public void publisherInlineTelegramClassCannotRemoveWordsFromFull()throws Exception {
        ExtractionResult result=article("<p>First <a class='js-message_reply_text'>lost words</a> last.</p>");
        assertEquals("full",result.completeness);assertEquals("First lost words last.",result.body);
        assertEquals(3,result.extractedTextNodes);assertEquals(0,result.unknownTextNodes);
        assertEquals(SemanticTextNormalizer.hash("First lost words last."),result.bodyHash);
    }
    @Test public void genericSemanticTextKeepsPublisherClassAtEverySupportedBoundary()throws Exception {
        String dom="<p>First <a class='js-message_reply_text'>lost words</a> last.</p><blockquote class='js-message_reply_text'>Own quote</blockquote>";
        String text=SemanticTextNormalizer.fromDom(DomBudgetGuard.parse(dom,URL,SourceSemanticAdaptersTest.scope()).document.body(),SourceSemanticAdaptersTest.scope());
        assertEquals("First lost words last.\n\nOwn quote",text);
    }
    @Test public void newInlineTagRetainsItsTextAndStillBlocksFull()throws Exception {
        ExtractionResult result=article("<p>First <span class='js-message_reply_text'>new words</span> last.</p>");
        assertEquals("unknown",result.completeness);assertEquals("First new words last.",result.body);assertTrue(result.unknownTextNodes>0);
    }
    @Test public void ownTelegramBodyStillPrunesNestedReplyAndDoesNotRepeatDisplayTitle()throws Exception {
        String own="<div class='tgme_widget_message_text js-message_text'>First<p>Own paragraph</p>"
            +"<div class='js-message_reply_text'>Reply words</div><blockquote>Own quote</blockquote></div>";
        FeedDocument feed=new TelegramPreviewAdapter().parse(SourceSemanticAdaptersTest.html("https://t.me/s/rbc_news",SourceSemanticAdaptersTest.tg(SourceSemanticAdaptersTest.post("2",own))),"rbc_news",SourceSemanticAdaptersTest.scope());
        ExtractionResult result=feed.items.get(0).inline;
        assertEquals("First\n\nOwn paragraph\n\nOwn quote",result.body);assertEquals("",result.providerTitle);
        assertEquals("РБК\n\n"+result.body,SourceArticleFramer.frame("РБК",result).spokenText);
    }
    @Test public void exactKnownNestedPublisherLogoAllowsWholeBodyFull()throws Exception {
        ExtractionResult result=article("<p>Own news.</p>"+publisher(logo()));
        assertEquals("full",result.completeness);assertEquals("Own news.",result.body);assertEquals(0,result.unknownTextNodes);
        assertEquals(1,result.extractedTextNodes);
    }
    @Test public void substantivePublisherOrLogoMutationsNeverDisappearBehindFull()throws Exception {
        for(String changed:new String[]{
            publisher(logo().replace("</div>","<p>Additional words.</p></div>")),
            publisher(logo().replace("</div>","Additional words.</div>")),
            publisher(logo()).replace("</div><meta itemprop='name'","</div><p>Additional words.</p><meta itemprop='name'"),
            publisher(logo()).replace("<meta itemprop='name'","Additional words.<meta itemprop='name'")
        }) {
            ExtractionResult result=article("<p>Own news.</p>"+changed);
            assertEquals("unknown",result.completeness);assertEquals("UNSUPPORTED_STRUCTURE",result.reason);
            assertTrue(result.body.contains("Additional words."));assertTrue(result.unknownTextNodes>0);
        }
    }
    @Test public void differentNestedMetadataOrEmptyNewBlockCannotClaimFull()throws Exception {
        for(String changed:new String[]{
            publisher(logo().replace("</div>","<div></div></div>")),
            publisher(logo().replace(" itemscope", "")),
            publisher(logo().replace("https://schema.org/ImageObject","https://schema.org/Organization")),
            publisher(logo().replace("itemprop='logo'","itemprop='image'")),
            publisher(logo().replace("itemprop='logo'","class='new-block' itemprop='logo'")),
            publisher(logo().replace("content='200'","content='201'")),
            publisher(logo().replace("https://www.interfax.ru/img/logo200.png","https://images.test/new.png")),
            publisher(logo()).replace("itemprop='publisher'","itemprop='author'"),
            publisher(logo()).replace("</div><meta itemprop='name'","</div><div></div><meta itemprop='name'")
        }) {
            ExtractionResult result=article("<p>Own news.</p>"+changed);
            assertEquals("unknown",result.completeness);assertEquals("UNSUPPORTED_STRUCTURE",result.reason);
            assertTrue(result.body.contains("Own news."));
        }
    }
}

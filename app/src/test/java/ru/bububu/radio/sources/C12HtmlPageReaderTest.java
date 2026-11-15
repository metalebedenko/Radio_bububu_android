package ru.bububu.radio.sources;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class C12HtmlPageReaderTest {
    static SourceTransport.Response response(String html){return new SourceTransport.Response(html.getBytes(StandardCharsets.UTF_8),"https://arbitrary.example/read?id=17&lang=ru",200,"text/html","UTF-8",null,null);}
    static FeedDocument read(String html)throws Exception{return new HtmlPageReader().read(response(html),new RequestScope(1,0));}
    @Test public void semanticArticleExcludesMenusAdsScriptsAndKeepsEntitiesUnicode()throws Exception {
        FeedDocument f=read("<title>Внешний заголовок</title><nav>МЕНЮ</nav><header>ЛОГО</header><main><article><h1>Новый &amp; точный заголовок</h1><p>Текст&nbsp;о море &lt;океане&gt; 😀.</p><aside>ЛИШНЕЕ</aside><div class='ad-box'>РЕКЛАМА</div><script>СЕКРЕТ</script><p>Вторая строка &copy;.</p><div class='newsletter'>ПОДПИСКА</div></article></main><footer>ПОДВАЛ</footer>");
        assertEquals(HtmlPageReader.TYPE,f.type);assertEquals(1,f.items.size());ExtractionResult t=f.items.get(0).inline;assertEquals("Новый & точный заголовок",t.providerTitle);assertEquals("Текст о море <океане> 😀.\n\nВторая строка ©.",t.body);assertEquals("unknown",t.completeness);assertTrue(t.canEnqueue());assertNotNull(t.publication);assertEquals("https://arbitrary.example/read?id=17&lang=ru",f.finalUrl);
    }
    @Test public void semanticBylineAndTagsAreExcludedWhileEditorInlineLinksAndPhotoCaptionsStay() throws Exception {
        String html="<article><h1>Полевой отчёт</h1>"
                +"<div itemprop='author'>Подпись редакции</div><a rel='external tag' href='/topic/sea'>Рубрика</a>"
                +"<p>Текст <a rel='author' href='/authors/irina'>Ирины</a> о <a rel='tag' href='/tag/sea'>#море</a>.</p>"
                +"<figure><figcaption>Подпись к наблюдению.</figcaption></figure>"
                +"<div>Слова <span itemprop='author'>участника</span> важны.</div></article>";
        ExtractionResult t=read(html).items.get(0).inline;
        assertFalse(t.body.contains("Подпись редакции"));assertFalse(t.body.contains("Рубрика"));
        assertTrue(t.body.contains("Текст Ирины о #море."));assertTrue(t.body.contains("Подпись к наблюдению."));
        assertTrue(t.body.contains("Слова участника важны."));assertEquals("unknown",t.completeness);
    }
    @Test public void metadataControlsAndTaxonomyTabsNeedCorroboratingStructure() throws Exception {
        String html="<main><h1>Дневник</h1><div class='EntryMeta_root'><button aria-label='Масштаб'>Масштаб</button>"
                +"<a class='Index_date' href='/date/2026/11/04'>Вчера</a><div class='HelpTooltip'><span>Подсказка шрифта</span></div></div>"
                +"<div><a class='Byline_author' href='/authors/editor'>Подпись автора</a></div>"
                +"<p>Первый абзац.</p><div class='TopicTabs_main'><button role='tab'>Рубрики</button>"
                +"<a class='Topic_item' href='/topics/sea'>Океаны</a></div><p>Последний абзац.</p></main>";
        ExtractionResult t=read(html).items.get(0).inline;
        assertEquals("Первый абзац.\n\nПоследний абзац.",t.body);
        String mixed="<main><h1>Тема</h1><div class='entry-meta'><button>Масштаб</button><time datetime='2026-11-04'>Дата</time>"
                +"<div>Редакторский текст без абзацной разметки.</div></div>"
                +"<div class='topic-tabs'><button>Темы</button><a rel='tag' href='/tags/sea'>#море</a>"
                +"<div>История внутри вкладки.</div></div></main>";
        String body=read(mixed).items.get(0).inline.body;
        assertTrue(body.contains("Редакторский текст без абзацной разметки."));assertTrue(body.contains("История внутри вкладки."));
    }
    @Test public void matchedHeadlineOwnsItsBodyAndSubtitleInsteadOfNeighboursAndByline() throws Exception {
        String article="<meta property='og:title' content='Выбранная статья'><main><div class='story-entry'>"
                +"<div class='entry-header'><ul><li>Подпись редакции</li></ul><time>5 ноября</time></div>"
                +"<h2 class='entry-title'>Выбранная статья</h2><div class='entry-subtitle'>Содержательный лид.</div>"
                +"<div class='entry-image'><span>Кредит фотографии</span></div>"
                +"<div class='entry-text'><div class='entry-text-wrapper'><p>Первый абзац.</p><p>Последний абзац.</p></div></div></div>"
                +"<div><h2>Соседняя публикация</h2><p>Чужой текст ленты.</p></div></main>";
        ExtractionResult got=read(article).items.get(0).inline;
        assertEquals("Выбранная статья",got.providerTitle);assertEquals("Содержательный лид.\n\nПервый абзац.\n\nПоследний абзац.",got.body);
        String scientific="<meta property='og:type' content='article'><article><header><h1>Опыт</h1></header>"
                +"<div itemprop='description'>Вводная часть.</div><div itemprop='articleBody'><p>Наблюдение.</p>"
                +"<figure><figcaption>Подпись к измерению.</figcaption></figure><p>Вывод.</p></div></article>";
        ExtractionResult sci=read(scientific).items.get(0).inline;
        assertTrue(sci.body.startsWith("Вводная часть."));assertTrue(sci.body.contains("Подпись к измерению."));assertTrue(sci.body.endsWith("Вывод."));
    }
    @Test public void malformedMainForeignAndProductDescriptionRemainReadable()throws Exception {
        for(String html:List.of("<main><h1>Une page</h1><p>Été et Straße — 日本語 😀<p>Encore un paragraphe", "<main><h1>Книга</h1><p>Описание книги и ISBN 978-5-000.</p><form>КУПИТЬ<input value='1'></form><div class='share'>ПОДЕЛИТЬСЯ</div></main>")){
            FeedDocument f=read(html);assertEquals(1,f.items.size());assertTrue(f.items.get(0).inline.canEnqueue());assertFalse(f.items.get(0).inline.body.contains("КУПИТЬ"));assertFalse(f.items.get(0).inline.body.contains("ПОДЕЛИТЬСЯ"));
        }
    }
    @Test public void multipartStoryKeepsBothSectionsAndSkipsMetadataAndRecommendedArticle()throws Exception {
        String story="<meta property='og:title' content='Театр'><main><div class='story'>"
                +"<div><ul><li>Служебная подпись</li></ul><time>5 ноября</time></div><h2>Театр</h2>"
                +"<div class='story-subtitle'>Вводная часть.</div><div>Кредит первого снимка</div>"
                +"<div class='story-text'><div class='story-text-inner'><p>Первый абзац.</p></div></div>"
                +"<div>Кредит второго снимка</div><div class='story-text'><div class='story-text-inner'>"
                +"<h2>Второе действие</h2><p>Слова <a rel='author' href='/authors/a'>автора</a> внутри цитаты.</p>"
                +"<p>Последняя строка.</p></div></div>"
                +"<div class='story-text'><article><h3><a href='/other'>Чужая новость</a></h3>"
                +"<div class='story-text'><p>Анонс другой статьи.</p></div></article></div></div>"
                +"<button>Показать больше</button></main>";
        ExtractionResult got=read(story).items.get(0).inline;
        assertEquals("Театр",got.providerTitle);assertEquals("unknown",got.completeness);
        assertEquals("Вводная часть.\n\nПервый абзац.\n\nВторое действие\n\nСлова автора внутри цитаты.\n\nПоследняя строка.",got.body);
    }
    @Test public void separateStoryHeadingsDoNotProveACombinedArticle()throws Exception {
        String index="<meta property='og:title' content='Театр'><main><h2>Театр</h2>"
                +"<div class='entry-body'><p>Текст первого сообщения.</p></div><h2>Ещё один материал</h2>"
                +"<div class='entry-body'><p>Текст отдельной новости.</p></div></main>";
        RequestScope scope=new RequestScope(1,0);
        assertFalse(HtmlPageReader.confirmedArticle(DomBudgetGuard.parse(response(index),scope).document,scope));
    }
    @Test public void genericContentWithoutSemanticTagsHasOneMaterial()throws Exception {FeedDocument f=read("<title>Путешествие</title><div class='menu'>ПУНКТЫ</div><div class='post-content'><p>Длинный рассказ путешественника.</p><p>Продолжение.</p></div>");assertEquals(1,f.items.size());assertEquals("Путешествие",f.items.get(0).title);assertFalse(f.items.get(0).inline.body.contains("ПУНКТЫ"));}
    @Test public void emptyNavigationOnlyScriptsHiddenAndAccessWallsDoNotCreateReadableText()throws Exception {
        for(String html:List.of("", "<title>Заголовок</title>","<nav>Новости</nav><script>long body</script>","<main><h1>Только заголовок</h1></main>","<main hidden>Скрытый текст</main>","<body><a href='/a'>Только ссылка</a></body>"))try{read(html);fail(html);}catch(SourceFailure expected){assertEquals("HTML_PAGE_EMPTY",expected.reason);}
        for(String html:List.of("<main><p>Текст</p><div class='paywall'>Подписка</div></main>","<div class='g-recaptcha'>Проверка</div><article>Текст</article>"))try{read(html);fail();}catch(SourceFailure expected){assertEquals("HTML_PAGE_ACCESS_REQUIRED",expected.reason);}
    }
    @Test public void noNewTwentyThousandLimitAndExistingSourceV2LimitStillApplies()throws Exception {
        String body="Текст о природе. ".repeat(2000);assertTrue(body.length()>SourceLimits.MAX_TEXT);assertEquals(body.strip(),read("<main><h1>Длинный текст</h1><p>"+body+"</p></main>").items.get(0).inline.body);
        try{read("<main>"+"Т".repeat(SourceLimits.MAX_SOURCE_SPOKEN_UTF16+1)+"</main>");fail();}catch(SourceFailure expected){assertEquals("TEXT_LIMIT",expected.reason);}
    }
    @Test public void responseKindCancellationAndDomGuardsArePreserved()throws Exception {
        SourceTransport.Response bad=new SourceTransport.Response("plain".getBytes(StandardCharsets.UTF_8),"https://example.org/",200,"application/json","UTF-8",null,null);try{new HtmlPageReader().read(bad,new RequestScope(1,0));fail();}catch(SourceFailure e){assertEquals("HTML_PAGE_UNSUPPORTED",e.reason);}
        RequestScope canceled=new RequestScope(1,0);canceled.cancel();try{new HtmlPageReader().read(response("<main>Текст</main>"),canceled);fail();}catch(SourceFailure e){assertEquals("CANCELLED",e.reason);}
        try{read("<div>".repeat(140)+"Текст"+"</div>".repeat(140));fail();}catch(SourceFailure e){assertEquals("DOM_LIMIT",e.reason);}
    }
    @Test public void warningIsExactAndEmptyHasAnActionableMessage(){assertEquals("Экспериментально: текст может быть неполным или содержать лишнее",HtmlPageReader.WARNING);String message=SourceFailureInfo.forReason("HTML_PAGE_EMPTY").message(SourceClock.SYSTEM);assertTrue(message.contains("не найден читаемый основной текст"));assertTrue(message.contains("попробуйте другую"));}
}

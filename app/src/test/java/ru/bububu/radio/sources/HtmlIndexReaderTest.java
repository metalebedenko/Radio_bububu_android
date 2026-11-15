package ru.bububu.radio.sources;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class HtmlIndexReaderTest {
    private static final String SECTION="https://paper.example/news/";
    static SourceTransport.Response html(String url,String html){
        return new SourceTransport.Response(html.getBytes(StandardCharsets.UTF_8),url,200,"text/html","UTF-8",null,null);
    }
    static FeedDocument inspect(String markup,boolean connected)throws Exception{
        RequestScope scope=new RequestScope(1,0);SourceTransport.Response r=html(SECTION,markup);
        return HtmlIndexReader.inspect(r,DomBudgetGuard.parse(r,scope).document,scope,connected);
    }
    @Test public void sectionExtractsPublisherCardsWithCanonicalUrlsAndNoChromeOrCrossOriginTargets() throws Exception {
        String markup="<main><h1>Город</h1><nav><h2><a href='/menu'>Обзор меню сайта</a></h2></nav>"
                +"<article><time datetime='2026-11-05T10:00:00+03:00'></time><h2><a href='one?utm_source=top&amp;id=2#anchor'>Рассказ о первом дне</a></h2></article>"
                +"<article><a href='two'><h3>Вторая публикация дня</h3></a></article>"
                +"<h3><a href='one?id=2&utm_medium=dup'>Дубликат с меткой</a></h3><aside><h3><a href='/promotion'>Заметка партнёра</a></h3></aside>"
                +"<h2><a href='https://other.example/out'>Другая площадка</a></h2><h2><a href='http://127.0.0.1/pr'>Приватный адрес</a></h2>"
                +"<h2><a href='/tags/topic'>Ссылка на тему</a></h2><h2 hidden><a href='/hidden'>Скрытая статья</a></h2></main>";
        FeedDocument feed=inspect(markup,false);
        assertEquals("Город",feed.title);assertEquals(2,feed.items.size());
        assertEquals(SECTION+"one?id=2",feed.items.get(0).url);assertEquals(SECTION+"two",feed.items.get(1).url);
        assertNotNull(feed.items.get(0).publishedAtMillis);assertNull(feed.items.get(1).publishedAtMillis);assertNull(feed.items.get(0).inline);
    }
    @Test public void schemaListAndFlatHeadlinesWorkAcrossLayoutsAndArticleOrMenuIsNotAnIndex() throws Exception {
        assertEquals(1,inspect("<main itemscope itemtype='https://schema.org/ItemList'><ul><li><a rel='bookmark' href='/story'>Первый научный обзор</a></li></ul></main>",false).items.size());
        assertEquals(2,inspect("<main><div class='story-card'><a class='entryTitle' href='/science/one'>Следы на берегу</a></div><div class='story-card'><a class='entryTitle' href='/science/two'>Другой научный опыт</a></div></main>",false).items.size());
        assertNull(inspect("<article><h1>Одна статья</h1><p>Основной текст.</p><h2><a href='/reference'>Ссылка на работу</a></h2><h2><a href='/context'>Вторая ссылка на работу</a></h2></article>",false));
        assertNull(inspect("<header><h2><a href='/video'>Смотреть все видео</a></h2></header><footer><h3><a href='/about'>Узнать больше о нас</a></h3></footer>",false));
        assertEquals("HTML_INDEX_UNREADABLE",assertThrows(SourceFailure.class,()->inspect("<main>Изменилась структура сайта.</main>",true)).reason);
        assertEquals("HTML_PAGE_ACCESS_REQUIRED",assertThrows(SourceFailure.class,()->inspect("<main class='captcha'><h2>Проверка</h2></main>",true)).reason);
    }
    @Test public void selectedHtmlMustMatchTheCheckedArticleAndNeverAnIndexLoginOrAnotherCanonical() throws Exception {
        String selected="https://www.paper.example/news/one?id=2";
        assertTrue(HtmlIndexReader.articleUrl(SECTION,selected));
        assertFalse(HtmlIndexReader.articleUrl(SECTION,"https://paper.example.evil.com/news/one"));
        assertFalse(HtmlIndexReader.articleUrl(SECTION,"http://paper.example/news/one"));
        assertFalse(HtmlIndexReader.articleUrl(SECTION,"https://paper.example:8443/news/one"));
        assertTrue(HtmlIndexReader.sameTarget(selected,"https://paper.example/news/one/?id=2&utm_medium=h"));
        assertFalse(HtmlIndexReader.sameTarget(selected,"https://paper.example/news/one?id=3"));
        assertFalse(HtmlIndexReader.articleUrl(SECTION,"https://paper.example/login?id=2"));
        RequestScope scope=new RequestScope(1,0);
        String article="<article><h1>Новая статья</h1><p>Первый абзац.</p><p>Последний абзац.</p></article>";
        ExtractionResult body=HtmlIndexReader.article(SECTION,selected,html(selected,article),scope);
        assertEquals("unknown",body.completeness);assertEquals("Первый абзац.\n\nПоследний абзац.",body.body);assertEquals(selected,body.publication.subjectUrl);
        assertEquals("HTML_INDEX_ARTICLE_IDENTITY",assertThrows(SourceFailure.class,()->HtmlIndexReader.article(SECTION,selected,html("https://paper.example/login",article),scope)).reason);
        assertEquals("HTML_INDEX_ARTICLE_IDENTITY",assertThrows(SourceFailure.class,()->HtmlIndexReader.article(SECTION,selected,html(selected,"<link rel='canonical' href='/unrelated'>"+article),scope)).reason);
        assertEquals("HTML_INDEX_ARTICLE_IDENTITY",assertThrows(SourceFailure.class,()->HtmlIndexReader.article(SECTION,selected,html(selected,"<h2><a href='/story-1'>Первая публикация</a></h2><h2><a href='/story-2'>Вторая публикация</a></h2>"),scope)).reason);
        scope.cancel();
        assertEquals("CANCELLED",assertThrows(SourceFailure.class,()->HtmlIndexReader.article(SECTION,selected,html(selected,article),scope)).reason);
    }
}

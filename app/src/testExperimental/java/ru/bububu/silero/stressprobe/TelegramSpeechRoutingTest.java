package ru.bububu.silero.stressprobe;

import java.util.List;
import org.junit.Test;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.PlayerController.SpeechSentence;
import ru.bububu.radio.core.PlayerController.SpeechUnit;
import ru.bububu.radio.core.TextSegments;
import ru.bububu.radio.models.SileroSpeechInput;
import ru.bububu.radio.sources.*;
import static org.junit.Assert.*;

public class TelegramSpeechRoutingTest {
    static Article post(String label,String body,String url) throws Exception {
        ExtractionResult extraction = new ExtractionResult(body,"","Новость","unknown",
                "PUBLIC_PREVIEW_COMPLETENESS_UNKNOWN","public_preview_text",TelegramPreviewAdapter.VERSION);
        SourceArticleFramer.Framed f=SourceArticleFramer.frame(label,extraction);
        return new SourceVersionRecord("src","item","article",f.versionHash,"Новость","",label,url,
                "unknown",extraction.reason,extraction.method,extraction.adapterVersion,SemanticTextNormalizer.VERSION,
                SourceArticleFramer.VERSION,f.bodyHash,f.spokenText,f.bodyStart,f.bodyEnd,0).asArticle();
    }
    private static String render(Article a) {
        return render(a,64);
    }
    private static String render(Article a,int limit) {
        StringBuilder builder=new StringBuilder();
        for (int i=0;i<a.sentences.size();i++) {
            SpeechSentence s=new SpeechSentence("station",a,null,i);
            SileroSpeechInput.SentencePlan plan=SileroSpeechInput.forSentence(s);
            List<String> parts=RadioFragments.plan(C7LongAtomTest.data(),plan,limit);
            TextSegments.checkedFragments(s.text(),parts,limit);
            for (int j=0;j<parts.size();j++) {
                String spoken=SourceSpeechPlan45.project(new SpeechUnit(s,parts,j));
                new FrontendPlan(C7LongAtomTest.data(),spoken);
                builder.append(' ').append(spoken);
            }
        }
        return builder.toString().trim().replaceAll("\\s+"," ");
    }
    @Test public void verifiedPostRetainsCanonicalBodyAndAdmitsItsActualNews()throws Exception {
        String body="🟪️ 🔴‍🟢 ▪️Открыли музей на площади.\n\n🚨 12 человек пришли на открытие.\n\n@news_2";
        Article a=post("Редакция",body,"https://t.me/news_2/27");
        String spoken=render(a);
        assertTrue(spoken,spoken.contains("Открыли музей на площади"));
        assertTrue(spoken,spoken.contains("двенадцать человек пришли на открытие"));
        assertTrue(spoken,spoken.contains("канал"));
        assertTrue(spoken,spoken.contains("подчёркивание"));
        assertTrue(spoken,spoken.endsWith("два"));
        assertEquals("Редакция\n\n"+body,a.text);
        assertEquals(SourceLimits.sha256(body),a.sourceVersion.bodyHash);
    }
    @Test public void fallbackAccountInTitleDoesNotBlockTheOnlyNewsSentence()throws Exception {
        Article a=post("@news_2","⚽️Игра закончилась ничьей","https://t.me/news_2/27");
        String spoken=render(a);
        assertTrue(spoken,spoken.startsWith("канал "));
        assertTrue(spoken,spoken.contains("подчёркивание"));
        assertTrue(spoken,spoken.endsWith("Игра закончилась ничьей"));
    }
    @Test public void wholeEditorialLinkAndParagraphControlsProjectOnlyInProvenTelegramBody() throws Exception {
        String link="https://nplus1.ru/news/2026/11/05/petroleum-lubricants-sclerosis";
        String body="\u200bСегодня исследователи опубликовали результаты\ufeff\n"+link;
        Article a=post("Наука",body,"https://t.me/news_2/27");
        assertEquals("Наука Сегодня исследователи опубликовали результаты В тексте есть ссылка",render(a));
        assertEquals(body,a.text.substring(a.sourceVersion.bodyStart,a.sourceVersion.bodyEnd));
        assertEquals(SourceLimits.sha256(body),a.sourceVersion.bodyHash);
        SpeechSentence sentence=new SpeechSentence("station",a,null,0);
        SileroSpeechInput.SentencePlan plan=SileroSpeechInput.forSentence(sentence);
        int begin=sentence.text().indexOf(link), end=begin+link.length();
        assertEquals("В тексте есть ссылка",plan.project(begin,end));
        assertTrue(plan.boundaries().stream().noneMatch(n->n>begin && n<end));
        assertEquals("REJECT:RADIO_CONTEXTUAL_ATOM_SPLIT",
                projectionOrRejection(()->plan.project(begin+1,end)));
        // A bound smaller than the link cannot return a plausible partial article.
        assertThrows(IllegalArgumentException.class,()->RadioFragments.plan(C7LongAtomTest.data(),plan,link.length()-1));
    }
    @Test public void verifiedUriGrammarAndLinkOnlyPostKeepTheRawUri()throws Exception {
        for(String uri:List.of("http://news.ru","HTTPS://xn--e1afmkfd.xn--p1ai:8443/a%20b?a=5&b=3#note",
                "https://пример.рф/публикация", "https://news.ru/a_(b);c?x=2.4&y=%2F#part-1",
                "https://8.8.8.8/a", "http://[2001:4860:4860::8888]/x")) {
            Article a=post("Редакция","\ufeff"+uri+"\u200b","https://t.me/news_2/27");
            assertEquals(uri,"Редакция В тексте есть ссылка",render(a,1000));
            assertEquals("\ufeff"+uri+"\u200b",a.sourceVersion.body());
        }
    }
    @Test public void classifyFullOriginalLineAcrossReviewedSentenceStarts()throws Exception {
        String uri="https://nplus1.ru/2026/11?article=13";
        Article a=post("Редакция","Сводка готова.\n"+uri+"\nСледующий абзац.","https://t.me/news_2/27");
        int split=a.text.indexOf(".ru/")+1;
        Article custom=new Article(a.id,a.version,a.title,a.text,List.of(0,split),a.sourceVersion);
        assertEquals("Редакция Сводка готова. В тексте есть ссылка Следующий абзац.",render(custom));
        // The local slice looks exactly like a URL. The full line still has a comment.
        Article inline=post("Редакция","В статье. https://news.ru/a комментарий.","https://t.me/news_2/27");
        int start=inline.text.indexOf("https:"), end=inline.text.indexOf(" комментарий");
        Article reviewed=new Article(inline.id,inline.version,inline.title,inline.text,List.of(0,start,end),inline.sourceVersion);
        assertTrue(TelegramSpeech.readings(new SpeechSentence("s",reviewed,null,1)).isEmpty());
    }
    @Test public void fenceAcrossSentencesAndInternalZeroWidthAreNotParagraphBoundaries()throws Exception {
        String body="```\nПример кода.\nhttps://news.ru/code-111\n```\n"
                +"Слово\u200bвнутри.\n\ufeffОтдельный абзац.\nhttps://news.ru/actual-222";
        Article a=post("Редакция",body,"https://t.me/news_2/27");
        java.util.ArrayList<String> classified=new java.util.ArrayList<>();
        for(int i=0;i<a.sentences.size();i++) {
            SpeechSentence s=new SpeechSentence("s",a,null,i);
            for(ContextualSpeech.Reading r:TelegramSpeech.readings(s)) classified.add(s.text().substring(r.start,r.end));
        }
        assertEquals(List.of("\ufeff","https://news.ru/actual-222"),classified);
    }
    @Test public void inlineCodeUntrustedUrisAndInternalFormatControlsKeepOriginalPolicy()throws Exception {
        for(String body:List.of("Читайте https://news.ru/2026/11","https://news.ru/2026/11 — подробности",
                "`https://news.ru/2026/11`","```text\nhttps://news.ru/2026/11\n```","~~~\n\ufeffhttps://news.ru/путь\n~~~",
                "news.ru/p/123","https:/","https:///path","https:news.ru","http://news..ru/x",
                "https://news.ru:%/x","https://news.ru:/x","https://news.ru:99999/x","https://news.ru/x%2",
                "https://user:secret@news.ru/x","https://@news.ru/x","https://127.0.0.1/x","http://[::1]/x",
                "https://printer/x","https://router.local/x","http://10.1.2.3/x","https://host.home.arpa/x",
                "https://news.ru/a\u200bb","сло\u200bво","сло\ufeffво","А\u200dБ","2 / 3 = ответ")) {
            Article a=post("Редакция",body,"https://t.me/news_2/27");
            for(int i=0;i<a.sentences.size();i++) {
                SpeechSentence s=new SpeechSentence("station",a,null,i);
                assertTrue(body,TelegramSpeech.readings(s).isEmpty());
                assertEquals(body,projectionOrRejection(()->SileroSpeechInput.project(s.text())),
                        projectionOrRejection(()->SileroSpeechInput.forSentence(s).project(0,s.text().length())));
            }
        }
        String body="\u200bТекст заметки\nhttps://news.ru/2026/11";
        for(Article other:List.of(new Article("a","v","Заметка",body),post("Редакция",body,"https://other.ru/news/27")))
            assertTrue(TelegramSpeech.readings(new SpeechSentence("station",other,null,0)).isEmpty());
    }
    @Test public void separateUnknownPictographIntroducesACompleteNewsSentence()throws Exception {
        String paragraph="В лаборатории завершили проверку нового прибора.";
        for(String icon:List.of("🔬","🦭","👩🏽‍🔬","🛰️")) {
            String body=icon+" "+paragraph;
            Article article=post("ТАСС",body,"https://t.me/news_2/27");
            assertEquals("ТАСС "+paragraph,render(article));
            assertEquals("ТАСС\n\n"+body,article.text);
            assertEquals(SourceLimits.sha256(body),article.sourceVersion.bodyHash);
        }
    }
    @Test public void presentationAndJoinedPictographsIntroduceWholeTelegramNews()throws Exception {
        String body="За день в городе проверили восемнадцать самолётов.";
        // Plane, scissors, railway, text-default per component, ordinary default-presentation
        // and composite emoji exercise Unicode properties rather than article-specific icons.
        for(String prefix:List.of("✈️","✂️","🛤️","☕","☝🏽","🧑🏽‍✈️","👩‍⚕️")) {
            Article a=post("Интерфакс",prefix+" "+body,"https://t.me/news_2/27");
            assertEquals("Интерфакс "+body,render(a));
            assertEquals(prefix+" "+body,a.text.substring(a.sourceVersion.bodyStart));
        }
    }
    private static String projectionOrRejection(java.util.function.Supplier<String> render) {
        try { return render.get(); }
        catch (IllegalArgumentException e) { return "REJECT:" + e.getMessage(); }
    }
    @Test public void decorationScopeDoesNotConsumeSymbolsAsContent()throws Exception {
        for(String text:List.of("Цена ▪️ выросла","🇫🇷 Команда приехала","🔴‍🐈 Текст","⭐ Символ",
                "🇫🇷 Французская команда выиграла матч","🏴‍☠️ Новый корабль прибыл сегодня",
                "🏁 Учёные завершили важную проверку","💲 Новая цена действует сегодня",
                "🔬Результаты подтвердили две группы","🔬 В эфире","🔬 Итог","🔬🔬 Отличная оценка за работу",
                "🔬 2 + 3 = 5","🔬 A B C","🌟 Отличная работа всей команды",
                "Новый 🔬 Сегодня учёные сообщили результаты","🔬︎ Эксперты завершили важную проверку",
                "✈ Новый самолёт прибыл сегодня","✈︎ Новый самолёт прибыл сегодня",
                "✈️ Самолёт","✈️ Новый рейс","✈️За день появились новости",
                "В небе ✈️ Новый рейс уже прибыл","✈️✈️ Прекрасная работа всей команды",
                "✈️ 5 + 4 = 9","➕️ Это значение используется дальше","✖️ Это значение используется дальше",
                "©️ Автор текста опубликовал статью","™️ Название фирмы осталось прежним",
                "1️⃣ Новую линию откроют осенью","↗️ Новое направление указано справа",
                "✈🏽 Новый рейс доступен сегодня","⚽️ = итог","▪️2 + 4","@other","@news_2 — автор реплики","🔴")) {
            Article a=post("Редакция",text,"https://t.me/news_2/27");
            SpeechSentence sentence=new SpeechSentence("station",a,null,0);
            assertTrue(text,TelegramSpeech.readings(sentence).isEmpty());
            assertEquals(projectionOrRejection(()->SileroSpeechInput.project(sentence.text())),
                    projectionOrRejection(()->SileroSpeechInput.forSentence(sentence).project(0,sentence.text().length())));
        }
        for(Article a:List.of(new Article("a","v","Заметка","🔬 Эксперты завершили важную проверку"),
                post("Редакция","▪️Текст","https://other.example/else/27"))) {
            assertTrue(TelegramSpeech.readings(new SpeechSentence("station",a,null,0)).isEmpty());
        }
    }
}

package ru.bububu.silero.stressprobe;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import ru.bububu.radio.sources.SourceSpeechPlan45;
import ru.bububu.radio.core.TextSegments;
import ru.bububu.radio.models.SileroSpeechInput;

public class C6ClauseFragmentsTest {
    static final FrontendPlan.Data DATA=new FrontendPlan.Data();
    static SourceSpeechPlan45.Parts source(String raw){return SourceSpeechPlan45.compile(raw).parts(512,spoken->new FrontendPlan(DATA,spoken));}
    static void complete(String raw,SourceSpeechPlan45.Parts parts){assertEquals(parts.raw,TextSegments.checkedFragments(raw,parts.raw,512));assertEquals(SourceSpeechPlan45.compile(raw).project(0,raw.length()).replaceAll("\\s+"," ").trim(),String.join(" ",parts.spoken).replaceAll("\\s+"," ").trim());for(String spoken:parts.spoken){FrontendPlan plan=new FrontendPlan(DATA,spoken);assertTrue(plan.spoken.length()<=256);assertTrue(plan.accentInputs().size()<=64);}}
    @Test public void supportedWholeSentenceIsNotSplitAtItsComma(){String raw="Здесь целое предложение, которое не нужно дробить.";SourceSpeechPlan45.Parts p=source(raw);assertEquals(List.of(raw),p.raw);complete(raw,p);assertEquals(List.of(raw),RadioFragments.plan(DATA,raw,1000));}
    @Test public void longSentencePrefersValidClauseInsteadOfMaximumWordCut(){String clause="Первая часть длинного предложения содержит важное уточнение,";String raw=clause+" затем продолжается подробное описание обстоятельств события".repeat(12)+" и завершается итогом.";SourceSpeechPlan45.Parts p=source(raw);assertTrue(p.raw.size()>2);assertEquals(clause,p.raw.get(0));complete(raw,p);List<String> legacy=RadioFragments.plan(DATA,raw,1000);assertEquals(clause,legacy.get(0));assertEquals(legacy,TextSegments.checkedFragments(raw,legacy,1000));assertEquals(SileroSpeechInput.project(raw).replaceAll("\\s+"," ").trim(),String.join(" ",legacy.stream().map(SileroSpeechInput::project).toArray(String[]::new)).replaceAll("\\s+"," ").trim());}
    @Test public void noSuitablePunctuationUsesExistingSafeFallbackWithoutDroppingText(){String raw="Подробное описание обстоятельств события продолжается ".repeat(20)+"завершается";SourceSpeechPlan45.Parts p=source(raw);assertTrue(p.raw.size()>2);complete(raw,p);assertEquals(RadioFragments.plan(DATA,raw,1000),TextSegments.checkedFragments(raw,RadioFragments.plan(DATA,raw,1000),1000));}
    @Test public void numbersDatesAndUrlProjectionRemainCompleteAndShortUrlIsWhole(){String raw="Короткая ссылка https://example.org/a, затем в 2026 году было 15 октября и более 1%.";SourceSpeechPlan45.Parts p=source(raw);complete(raw,p);String projected=String.join(" ",p.spoken);assertTrue(projected.contains("процент"));assertTrue(projected.contains("октября"));assertFalse(TextSegments.clauseBoundary("Ссылка https://example.org/a дальше",0,22));assertFalse(TextSegments.clauseBoundary("Автор Г. Х. Андерсен",0,8));}
    @Test public void changedClauseSpanChangesUnitHashWhileCanonicalArticleStaysExact()throws Exception{
        String raw="Первая часть длинного предложения содержит важное уточнение,"+" затем продолжается подробное описание обстоятельств события".repeat(8)+" и завершается итогом.";
        ru.bububu.radio.sources.SourceProfiles45.Profile profile=ru.bububu.radio.sources.SourceProfiles45.byId("biomolecula-articles");ru.bububu.radio.sources.SourceRecord source=profile.seed();ru.bububu.radio.sources.SourceItem item=ru.bububu.radio.sources.SourceItem.create(source.id,"clause",null,"https://biomolecula.ru/articles/c6-clause","Заголовок",-1,-1,1);
        ru.bububu.radio.sources.SourcePublication45 publication=new ru.bububu.radio.sources.SourcePublication45(ru.bububu.radio.sources.SourcePublication45.parse(ru.bububu.radio.sources.SourcePublication45.Role.ORIGINAL_PUBLISHED,"2026-10-26","fixture",item.url,"date",false),List.of(),profile.configSha,"test","","a".repeat(64));
        ru.bububu.radio.sources.SourceVersionRecord version=ru.bububu.radio.sources.SourceVersionRecord.source45(source,item,new ru.bububu.radio.sources.ExtractionResult(raw,"Заголовок","Заголовок","full","","source45-html",ru.bububu.radio.sources.SourceProfiles45.PARSER),publication,ru.bububu.radio.sources.SourcePublication45.Context.DATED,1);
        ru.bububu.radio.core.Article article=version.asArticle();int index=article.sentences.size()-1;ru.bububu.radio.core.PlayerController.SpeechSentence sentence=new ru.bububu.radio.core.PlayerController.SpeechSentence(source.stationId,article,null,index);assertEquals(raw,sentence.text());
        SourceSpeechPlan45.Parts preferred=SourceSpeechPlan45.sentence(sentence).parts(512,spoken->new FrontendPlan(DATA,spoken));List<String> alternative=TextSegments.fragments(raw,180);assertNotEquals(preferred.raw.get(0),alternative.get(0));
        ru.bububu.radio.core.PlayerController.SpeechUnit a=new ru.bububu.radio.core.PlayerController.SpeechUnit(sentence,preferred.raw,0),b=new ru.bububu.radio.core.PlayerController.SpeechUnit(sentence,alternative,0);
        assertNotEquals(SourceSpeechPlan45.hash(a),SourceSpeechPlan45.hash(b));assertEquals(version.versionKey,article.version);assertEquals(raw,version.body());assertEquals(ru.bububu.radio.sources.SourceLimits.sha256(raw),version.bodyHash);
    }

}

package ru.bububu.silero.stressprobe;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import ru.bububu.radio.core.*;
import ru.bububu.radio.models.RadioLookahead;
import ru.bububu.radio.sources.*;

/** Synthetic A154 shape only; no publication payload or audio fixture. */
public class C8SeeAlsoTest {
    static SourceVersionRecord version(String body,String title)throws Exception {
        SourceProfiles45.Profile profile=SourceProfiles45.byId("biomolecula-articles");
        SourceRecord source=profile.seed();
        SourceItem item=SourceItem.create(source.id,"c8-see-also",null,"https://biomolecula.ru/articles/c8-see-also",title,-1,-1,1);
        SourcePublication45 publication=new SourcePublication45(SourcePublication45.parse(SourcePublication45.Role.ORIGINAL_PUBLISHED,"2026-10-27","fixture",item.url,"date",false),List.of(),profile.configSha,"fixture","","a".repeat(64));
        return SourceVersionRecord.source45(source,item,new ExtractionResult(body,title,title,"full","","source45-html",SourceProfiles45.PARSER),publication,SourcePublication45.Context.DATED,1);
    }
    static PlayerController.SpeechSentence sentence(Article article,int index) {
        return new PlayerController.SpeechSentence("SRC:fixture",article,null,index);
    }
    static SourceSpeechPlan45.Parts parts(PlayerController.SpeechSentence sentence) {
        return SourceSpeechPlan45.sentence(sentence).parts(512,s->new FrontendPlan(new FrontendPlan.Data(),s));
    }
    @Test public void a154KeepsRawSpanAndNextSentenceWhileOnlySpokenCopyExpands()throws Exception {
        String body="Факт. ".repeat(153)+"См. также: Обзор.";
        SourceVersionRecord v=version(body,"Обзор");Article article=v.asArticle();
        assertEquals(156,article.sentences.size());
        Article.Sentence raw=article.sentences.get(154);
        assertEquals("См.",raw.text);assertEquals(4,raw.end-raw.start);
        assertEquals("См. ",article.text.substring(raw.start,raw.end));
        PlayerController.SpeechSentence target=sentence(article,154);
        SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.sentence(target);
        SourceSpeechPlan45.Parts parts=parts(target);
        assertEquals("См.",plan.canonical);assertEquals(List.of("См."),parts.raw);
        assertEquals(List.of("Смотрите."),parts.spoken);
        assertEquals(1,plan.atoms.size());assertEquals(0,plan.atoms.get(0).start);assertEquals(3,plan.atoms.get(0).end);
        assertEquals("Смотрите.",SourceSpeechPlan45.project(new PlayerController.SpeechUnit(target,parts.raw,0)));
        assertEquals("также: Обзор.",article.sentences.get(155).text);
        assertEquals(SourceSpeechPlan45.compile("также: Обзор.").project(0,13),String.join(" ",parts(sentence(article,155)).spoken));
        assertEquals(body,v.body());assertEquals(TextSegments.sentenceStarts(v.spokenText),v.sentenceStarts);
        C7LongAtomTest.carrier(new FrontendPlan.Data(),parts.spoken.get(0));
    }
    @Test public void cachedV2RetainsIdentityPayloadOffsetsAndSavedProgressBinding()throws Exception {
        SourceVersionRecord before=version("Факт. ".repeat(153)+"См. также: Обзор.","Обзор");
        Path directory=Files.createTempDirectory("c8-see-also-");
        try {
            SourceBodyCache cache=new SourceBodyCache(directory.toFile());cache.put(before);
            SourceVersionRecord saved=cache.get(before.withPayload(null));assertNotNull(saved);
            PlayerController.SavedProgress progress=new PlayerController.SavedProgress("SRC:fixture",saved.articleId,saved.versionKey,154,false,List.of(152,153),false,true);
            String key=PlayerController.progressKey(progress.stationId,progress.articleId,progress.version);
            SourceSpeechPlan45.Parts spoken=parts(sentence(saved.asArticle(),progress.sentenceIndex));
            assertEquals(List.of("Смотрите."),spoken.spoken);
            assertEquals(before.spokenText,saved.spokenText);assertEquals(before.bodyHash,saved.bodyHash);
            assertEquals(before.versionKey,saved.versionKey);assertEquals(before.recordKey,saved.recordKey);
            assertEquals(before.bodyStart,saved.bodyStart);assertEquals(before.bodyEnd,saved.bodyEnd);
            assertEquals(before.sentenceStarts,saved.sentenceStarts);assertTrue(before.sameMetadata(saved));
            assertEquals("source-frame-v2",saved.framingVersion);
            assertEquals(key,PlayerController.progressKey(progress.stationId,saved.articleId,saved.versionKey));
            assertEquals(List.of(152,153),progress.completedSentences);assertFalse(progress.passEnded);assertFalse(progress.read);
            assertEquals(before.spokenText,cache.get(before.withPayload(null)).spokenText);
        } finally { try(java.util.stream.Stream<Path> files=Files.list(directory)){for(Path file:(Iterable<Path>)files::iterator)Files.delete(file);}Files.delete(directory); }
    }
    @Test public void measurementsMetadataOrdinaryWordsUrlsAndUnboundAbbreviationDoNotExpand()throws Exception {
        for(String body:List.of("Длина 5 см. также: Обзор.","См. Другой факт.","См.","См. также обычный текст.","Смело смотрим. также: Обзор.","Ссылка https://example.org/См. также: Обзор.")) {
            Article article=version(body,"См.").asArticle();
            for(int n=0;n<article.sentences.size();n++)assertFalse(body,String.join(" ",parts(sentence(article,n)).spoken).contains("Смотрите."));
        }
        assertEquals("См.",SourceSpeechPlan45.compile("См.").project(0,3));
        assertEquals("См.",ru.bububu.radio.models.SileroSpeechInput.project("См."));
    }
    @Test public void previousRuntimeCannotSupplyUnexpandedA154Wave()throws Exception {
        Article article=version("Факт. ".repeat(153)+"См. также: Обзор.","Обзор").asArticle();
        PlayerController.SpeechSentence target=sentence(article,154),previous=sentence(article,153);
        PlayerController.SpeechUnit unit=new PlayerController.SpeechUnit(target,parts(target).raw,0);
        String stale=SourceLimits.snapshotVersion("source-spoken-runtime45-v5",SourceNumbers45.REVISION,article.version,"154","0","3","См.");
        assertNotEquals(stale,SourceSpeechPlan45.hash(unit));
        RadioLookahead cache=new RadioLookahead();
        RadioLookahead.Slot slot=cache.reserve(new PlayerController.SpeechUnit(previous,parts(previous).raw,0),SourceSpeechPlan45.RUNTIME_REVISION);
        assertTrue(cache.planned(slot,unit));Path wav=Files.createTempFile("c8-stale-",".wav");Files.write(wav,new byte[46]);
        assertFalse(cache.ready(slot,unit,SourceSpeechPlan45.RUNTIME_REVISION,stale,wav.toFile()));assertFalse(Files.exists(wav));
    }
}

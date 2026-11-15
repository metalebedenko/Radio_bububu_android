package ru.bububu.silero.stressprobe;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import ru.bububu.radio.sources.*;
import ru.bububu.radio.core.*;

public class C7NumberSignTest {
    static SourceVersionRecord version()throws Exception {
        SourceProfiles45.Profile profile=SourceProfiles45.byId("biomolecula-articles");
        SourceRecord source=profile.seed();String title="Обзор #3: новые данные";
        SourceItem item=SourceItem.create(source.id,"number-sign",null,"https://biomolecula.ru/articles/number-sign",title,-1,-1,1);
        SourcePublication45 publication=new SourcePublication45(SourcePublication45.parse(SourcePublication45.Role.ORIGINAL_PUBLISHED,"2026-10-18","fixture",item.url,"date",false),List.of(),profile.configSha,"fixture","","a".repeat(64));
        return SourceVersionRecord.source45(source,item,new ExtractionResult("Первое предложение. Второе предложение.",title,title,"full","","source45-html",SourceProfiles45.PARSER),publication,SourcePublication45.Context.DATED,1);
    }
    @Test public void numberedIssueMetadataSpeaksMarkerAndPreservesCachedCanonical()throws Exception {
        SourceVersionRecord before=version();String raw=before.spokenText,key=before.versionKey,body=before.body();List<Integer> starts=before.sentenceStarts;
        java.io.File directory=java.nio.file.Files.createTempDirectory("c7-number-sign-").toFile();
        try {
            SourceBodyCache cache=new SourceBodyCache(directory);cache.put(before);SourceVersionRecord saved=cache.get(before.withPayload(null));assertNotNull(saved);
            Article article=saved.asArticle();PlayerController.SpeechSentence sentence=new PlayerController.SpeechSentence("SRC:fixture",article,null,0);
            SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.sentence(sentence);FrontendPlan.Data data=C7LongAtomTest.data();
            SourceSpeechPlan45.Parts parts=plan.parts(512,s->new FrontendPlan(data,s));
            String spoken=String.join(" ",parts.spoken);assertTrue(spoken,spoken.contains("номер три"));assertFalse(spoken.contains("#"));
            assertTrue(spoken.contains("Биомолекула."));assertTrue(spoken.contains("новые данные."));assertTrue(spoken.replace("+","").endsWith("года."));
            for(String part:parts.spoken)C7LongAtomTest.carrier(data,part);
            assertEquals(sentence.text().replaceAll("\\s+",""),String.join("",parts.raw).replaceAll("\\s+",""));
            assertEquals(raw,saved.spokenText);assertEquals(key,saved.versionKey);assertEquals(body,saved.body());assertEquals(starts,saved.sentenceStarts);
            assertEquals(before.bodyHash,saved.bodyHash);assertEquals(before.publication.identityDigest(),saved.publication.identityDigest());assertTrue(saved.providerTitle.contains("#3"));
        } finally {for(java.io.File file:directory.listFiles())file.delete();directory.delete();}
    }
    @Test public void numberMarkerContextDoesNotRewriteUrlFragmentsOrEmbeddedIdentifiers() {
        assertEquals(SourceNumbers45.project("№3:"),SourceNumbers45.project("#3:"));
        String raw="Выпуск #3: ссылка https://example.org/a#3 и модель Т34–40.";
        SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.compile(raw);SourceSpeechPlan45.Parts parts=plan.parts(512,s->new FrontendPlan(C7LongAtomTest.data(),s));
        String spoken=String.join(" ",parts.spoken);assertTrue(spoken.contains("номер три"));assertTrue(spoken.contains("решётка три"));
        assertEquals(raw.replaceAll("\\s+",""),String.join("",parts.raw).replaceAll("\\s+",""));
        assertFalse(SourceNumbers45.project("ID#3").contains("номер"));assertFalse(SourceNumbers45.project("#3D").contains("номер"));
        assertThrows(IllegalArgumentException.class,()->SourceSpeechPlan45.compile("ID#3").parts(512,s->new FrontendPlan(C7LongAtomTest.data(),s)));
        assertThrows(IllegalArgumentException.class,()->SourceSpeechPlan45.compile("#3D").parts(512,s->new FrontendPlan(C7LongAtomTest.data(),s)));
    }
    @Test public void previousRuntimeV4HashCannotReuseAStaleWave()throws Exception {
        Article article=C7SpeechProjectionTest.oldVersion().asArticle();PlayerController.SpeechSentence sentence=new PlayerController.SpeechSentence("SRC:fixture",article,null,1);
        SourceSpeechPlan45.Parts parts=SourceSpeechPlan45.sentence(sentence).parts(512,s->new FrontendPlan(C7LongAtomTest.data(),s));
        PlayerController.SpeechUnit unit=new PlayerController.SpeechUnit(sentence,parts.raw,0);
        String old=SourceLimits.snapshotVersion("source-spoken-runtime45-v4","source45-numbers-prose-v3",article.version,"1","0",Integer.toString(parts.raw.get(0).length()),SourceSpeechPlan45.project(unit));
        assertNotEquals(old,SourceSpeechPlan45.hash(unit));
        PlayerController.SpeechSentence metadata=new PlayerController.SpeechSentence("SRC:fixture",article,null,0);
        SourceSpeechPlan45.Parts prefix=SourceSpeechPlan45.sentence(metadata).parts(512,s->new FrontendPlan(C7LongAtomTest.data(),s));
        PlayerController.SpeechUnit after=new PlayerController.SpeechUnit(metadata,prefix.raw,prefix.raw.size()-1);
        ru.bububu.radio.models.RadioLookahead cache=new ru.bububu.radio.models.RadioLookahead();
        ru.bububu.radio.models.RadioLookahead.Slot slot=cache.reserve(after,SourceSpeechPlan45.RUNTIME_REVISION);assertTrue(cache.planned(slot,unit));
        java.io.File file=java.io.File.createTempFile("c7-number-sign-",".wav");java.nio.file.Files.write(file.toPath(),new byte[46]);
        assertFalse(cache.ready(slot,unit,SourceSpeechPlan45.RUNTIME_REVISION,old,file));assertFalse(file.exists());
    }
}

package ru.bububu.silero.stressprobe;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import ru.bububu.radio.sources.*;

public class C9YearAtomTest {
    static FrontendPlan.Data data() {
        FrontendPlan.Data data=C7LongAtomTest.data();
        data.variants.put("года",List.of("г+ода","год+а"));
        return data;
    }
    @Test public void yearGenitiveKeepsNumberAndRawAtomWithOnlyOneUnresolvedHomograph() {
        String raw="Летом 1948 года, вышел обзор.";
        SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.compile(raw);
        SourceSpeechPlan45.Parts parts=plan.parts(512,s->new FrontendPlan(data(),s));
        assertEquals(raw,plan.canonical);
        assertEquals(raw.replaceAll("\\s+",""),String.join("",parts.raw).replaceAll("\\s+",""));
        assertTrue(plan.atoms.stream().anyMatch(a->raw.substring(a.start,a.end).equals("1948 года,")));
        String spoken=String.join(" ",parts.spoken);
        assertTrue(spoken,spoken.contains("тысяча девятьсот сорок восьмого г+ода"));
        for(String part:parts.spoken)C7LongAtomTest.carrier(data(),part);
    }
    @Test public void onlyYearGenitiveHasDeterministicYearStress() {
        assertTrue(SourceNumbers45.project("после 1948 года").contains("г+ода"));
        for(String raw:List.of("года","долгие года","1948 году","1948 годом","1948 год","М1948"))
            assertFalse(raw,SourceNumbers45.project(raw).contains("г+ода"));
        assertTrue(SourceSpeechPlan45.compile("https://example.org/1948-года").atoms.stream().allMatch(a->a.rule.startsWith("literal-url")));
        assertThrows(IllegalArgumentException.class,()->SourceSpeechPlan45.compile("$1.50"));
        assertThrows(FrontendPlan.Rejected.class,()->new FrontendPlan(data(),"сорок года"));
    }
    @Test public void frontendFailureRetainsSpecificReasonWithoutWeakeningAdmission() {
        FrontendPlan.Rejected failure=assertThrows(FrontendPlan.Rejected.class,()->new FrontendPlan(data(),"сорок года"));
        assertEquals("UNSUPPORTED_MULTIPLE_HOMOGRAPHS",failure.getMessage());
        IllegalArgumentException atom=assertThrows(IllegalArgumentException.class,()->SourceSpeechPlan45.compile("сорок-года").parts(512,s->new FrontendPlan(data(),s)));
        assertEquals("UNSUPPORTED_MULTIPLE_HOMOGRAPHS",atom.getMessage());
    }
    @Test public void previousC8YearWaveCannotBeReusedAfterSpokenRevision()throws Exception {
        ru.bububu.radio.core.Article article=C8SeeAlsoTest.version("Летом 1948 года, вышел обзор.","Обзор").asArticle();
        ru.bububu.radio.core.PlayerController.SpeechSentence owner=C8SeeAlsoTest.sentence(article,1);
        SourceSpeechPlan45.Parts parts=SourceSpeechPlan45.sentence(owner).parts(512,s->new FrontendPlan(data(),s));
        ru.bububu.radio.core.PlayerController.SpeechUnit unit=new ru.bububu.radio.core.PlayerController.SpeechUnit(owner,parts.raw,0);
        String old=SourceLimits.snapshotVersion("source-spoken-runtime46-c8-v1","source45-numbers-prose-v4",article.version,"1","0",Integer.toString(parts.raw.get(0).length()),SourceSpeechPlan45.project(unit).replace("г+ода","года"));
        assertNotEquals(old,SourceSpeechPlan45.hash(unit));
        ru.bububu.radio.models.RadioLookahead cache=new ru.bububu.radio.models.RadioLookahead();
        ru.bububu.radio.core.PlayerController.SpeechSentence meta=C8SeeAlsoTest.sentence(article,0);
        SourceSpeechPlan45.Parts prefix=C8SeeAlsoTest.parts(meta);
        ru.bububu.radio.models.RadioLookahead.Slot slot=cache.reserve(new ru.bububu.radio.core.PlayerController.SpeechUnit(meta,prefix.raw,prefix.raw.size()-1),SourceSpeechPlan45.RUNTIME_REVISION);
        assertTrue(cache.planned(slot,unit));java.nio.file.Path wave=java.nio.file.Files.createTempFile("c9-stale-",".wav");java.nio.file.Files.write(wave,new byte[46]);
        assertFalse(cache.ready(slot,unit,SourceSpeechPlan45.RUNTIME_REVISION,old,wave.toFile()));assertFalse(java.nio.file.Files.exists(wave));
    }
}

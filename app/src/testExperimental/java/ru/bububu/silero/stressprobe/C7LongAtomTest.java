package ru.bububu.silero.stressprobe;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import ru.bububu.radio.sources.*;
import ru.bububu.radio.core.*;

public class C7LongAtomTest {
    // Two approved dictionary shapes reproduce the real resource constraint, without article data.
    static FrontendPlan.Data data() {
        FrontendPlan.Data data=new FrontendPlan.Data();
        data.cls=2;data.sep=3;data.unk=1;data.homoStart=4;data.homoEnd=5;
        data.vocab.put("[HOMO]",4);data.vocab.put("[/HOMO]",5);
        data.variants.put("тире",List.of("т+ире","тир+е"));
        data.variants.put("сорок",List.of("с+орок","сор+ок"));
        return data;
    }
    static void carrier(FrontendPlan.Data data,String spoken) {
        FrontendPlan frontend=new FrontendPlan(data,spoken);
        if(frontend.homograph!=null)frontend.acceptHomo(frontend.homograph.equals("сорок")?-1f:1f);
        frontend.accentInputs();int rows=frontend.rawTokens.size();
        float[] stress=new float[rows*10],yo=new float[rows*10];
        for(int row=0;row<rows;row++){stress[row*10]=12;yo[row*10]=12;}
        frontend.acceptAccent(stress,10,yo,10);
        assertTrue(new CorePlan(frontend).length>=3);
        assertTrue(FrontendPlan.basePreserved(frontend.spoken.replace("+",""),frontend.annotated.replace("+","")));
    }
    @Test public void descendingRangesWithTwoHomographsRetainNumbersGovernorsAndRawSpans() {
        String raw="Числа от 300–250 до 50–40 тысяч лет.";
        SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.compile(raw);
        FrontendPlan.Data data=data();
        SourceSpeechPlan45.Parts parts=plan.parts(512,s->new FrontendPlan(data,s));
        assertEquals(raw,plan.canonical);
        assertEquals(raw.replaceAll("\\s+",""),String.join("",parts.raw).replaceAll("\\s+",""));
        assertTrue(plan.atoms.stream().anyMatch(a->raw.substring(a.start,a.end).equals("от 300")));
        assertTrue(plan.atoms.stream().anyMatch(a->raw.substring(a.start,a.end).equals("до 50")));
        String spoken=String.join(" ",parts.spoken).replaceAll("\\s+"," ");
        assertEquals(plan.project(0,raw.length()).replaceAll("\\s+"," "),spoken);
        assertTrue(spoken,spoken.contains("триста тире двести пятьдесят"));
        assertTrue(spoken,spoken.contains("пятьдесят тире сорок"));
        for(int i=0;i<parts.raw.size();i++){assertTrue(parts.raw.get(i).length()<=512);assertTrue(parts.spoken.get(i).length()<=256);carrier(data,parts.spoken.get(i));}
    }
    @Test public void identifiersUrlsSignedValuesAndAtomicNumberBoundsStayProtected() {
        String raw="Значение -15%, модель Т34–40, ссылка https://example.org/a50-40:8";
        SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.compile(raw);
        int start=raw.indexOf("Т34–40"),end=start+"Т34–40".length();
        assertTrue(plan.atoms.stream().anyMatch(a->a.start<=start&&a.end>=end));
        SourceSpeechPlan45.Parts parts=plan.parts(512,s->new FrontendPlan(new FrontendPlan.Data(),s));
        assertEquals(raw.replaceAll("\\s+",""),String.join("",parts.raw).replaceAll("\\s+",""));
        String spoken=String.join(" ",parts.spoken);assertTrue(spoken.contains("минус"));assertTrue(spoken.contains("тире"));assertTrue(spoken.contains("дефис"));assertTrue(spoken.contains("двоеточие"));
        for(String part:parts.spoken)C7SpeechProjectionTest.carrier(part);
        assertThrows(IllegalArgumentException.class,()->SourceSpeechPlan45.compile("300–250").parts(2,s->new FrontendPlan(new FrontendPlan.Data(),s)));
        assertThrows(IllegalArgumentException.class,()->SourceSpeechPlan45.compile("$1.50"));
    }
    @Test public void previousRuntimeV3LookaheadHashCannotReuseAStaleWave()throws Exception {
        Article article=C7SpeechProjectionTest.oldVersion().asArticle();
        PlayerController.SpeechSentence sentence=new PlayerController.SpeechSentence("SRC:fixture",article,null,1);
        SourceSpeechPlan45.Parts parts=SourceSpeechPlan45.sentence(sentence).parts(512,s->new FrontendPlan(new FrontendPlan.Data(),s));
        PlayerController.SpeechUnit unit=new PlayerController.SpeechUnit(sentence,parts.raw,0);
        String old=SourceLimits.snapshotVersion("source-spoken-runtime45-v3",SourceNumbers45.REVISION,article.version,"1","0",Integer.toString(parts.raw.get(0).length()),SourceSpeechPlan45.project(unit));
        assertNotEquals(old,SourceSpeechPlan45.hash(unit));
        PlayerController.SpeechSentence metadata=new PlayerController.SpeechSentence("SRC:fixture",article,null,0);
        SourceSpeechPlan45.Parts prefix=SourceSpeechPlan45.sentence(metadata).parts(512,s->new FrontendPlan(new FrontendPlan.Data(),s));
        PlayerController.SpeechUnit after=new PlayerController.SpeechUnit(metadata,prefix.raw,prefix.raw.size()-1);
        ru.bububu.radio.models.RadioLookahead cache=new ru.bububu.radio.models.RadioLookahead();
        ru.bububu.radio.models.RadioLookahead.Slot slot=cache.reserve(after,SourceSpeechPlan45.RUNTIME_REVISION);assertTrue(cache.planned(slot,unit));
        java.io.File file=java.io.File.createTempFile("c7-long-",".wav");java.nio.file.Files.write(file.toPath(),new byte[46]);
        assertFalse(cache.ready(slot,unit,SourceSpeechPlan45.RUNTIME_REVISION,old,file));assertFalse(file.exists());
    }
}

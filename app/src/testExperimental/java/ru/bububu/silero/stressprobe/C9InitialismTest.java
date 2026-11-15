package ru.bububu.silero.stressprobe;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import ru.bububu.radio.sources.*;

public class C9InitialismTest {
    @Test public void explicitLetterNamesStayTogetherWithPunctuationRatherThanSpokenCommaWord(){
        for(Map.Entry<String,String> row:Map.of("МВД","эм, вэ, дэ","КВН","ка, вэ, эн","ЮФУ","ю, эф, у","ВТ","вэ, тэ").entrySet()){
            SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.compile(row.getKey());
            assertEquals(row.getKey(),plan.canonical);assertEquals(1,plan.atoms.size());
            assertEquals(0,plan.atoms.get(0).start);assertEquals(row.getKey().length(),plan.atoms.get(0).end);
            SourceSpeechPlan45.Parts parts=plan.parts(512,s->new FrontendPlan(new FrontendPlan.Data(),s));
            assertEquals(List.of(row.getKey()),parts.raw);assertEquals(List.of(row.getValue()),parts.spoken);
            assertFalse(parts.spoken.get(0).contains("запятая"));
            FrontendPlan frontend=new FrontendPlan(new FrontendPlan.Data(),parts.spoken.get(0));
            assertEquals(row.getValue(),frontend.normalization.spoken);
            frontend.accentInputs();int rows=frontend.rawTokens.size();float[] st=new float[rows*10],yo=new float[rows*10];for(int n=0;n<rows;n++){st[n*10]=12;yo[n*10]=12;}frontend.acceptAccent(st,10,yo,10);
            CorePlan core=new CorePlan(frontend);assertTrue(core.length>=3);
            assertEquals(row.getValue(),frontend.annotated.replace("+",""));
        }
    }
    @Test public void onlyExplicitUppercaseWordIsExpandedAndUrlsRemainLiteral(){
        for(String raw:List.of("Мвд","мвд","СУПЕРМВД","МВД1","AМВД","НОВОСТИ"))assertEquals(raw,SourceInitialisms45.expand(raw));
        String raw="Отчёт МВД.";SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.compile(raw);assertEquals(raw,plan.canonical);assertTrue(plan.project(0,raw.length()).contains("эм, вэ, дэ."));
        assertTrue(SourceSpeechPlan45.compile("https://example.org/МВД").atoms.stream().allMatch(a->a.rule.startsWith("literal-url")));
    }
}

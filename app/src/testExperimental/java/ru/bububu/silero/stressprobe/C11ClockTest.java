package ru.bububu.silero.stressprobe;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;import ru.bububu.radio.sources.*;
public class C11ClockTest {
    @Test public void explicitTimeContextKeepsAllClockPartsWithoutSpokenPunctuation(){
        String raw="Встреча с 17:40 до 17:45 по местному времени (18:40-18:45 мск).";SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.compile(raw);FrontendPlan.Data data=C7LongAtomTest.data();SourceSpeechPlan45.Parts parts=plan.parts(512,s->new FrontendPlan(data,s));
        assertEquals(raw,plan.canonical);assertEquals(raw.replaceAll("\\s+",""),String.join("",parts.raw).replaceAll("\\s+",""));String spoken=String.join(" ",parts.spoken);for(String forbidden:List.of("двоеточие","тире","дефис"))assertFalse(spoken,spoken.contains(forbidden));assertTrue(spoken.contains("семнадцать сорок"));assertTrue(spoken.contains("семнадцать сорок пять"));assertTrue(spoken.contains("восемнадцать сорок"));assertTrue(spoken.contains("восемнадцать сорок пять"));assertEquals(4,plan.atoms.stream().filter(a->a.rule.equals("source49-clock-v1")).count());assertEquals(1,plan.atoms.stream().filter(a->a.rule.equals("source49-clock-range-v1")&&a.spoken.equals("до")).count());for(String p:parts.spoken)C7LongAtomTest.carrier(data,p);
    }
    @Test public void onlyValidWholeClocksWithTimeCueAreRewritten(){for(String raw:List.of("Формула 19:45-19:51.","-19:45-19:51 мск.","A19:45-19:51 мск.","ID/19:45-19:51 мск.","25:45-19:51 мск.","19:99-19:51 мск.","https://example.org/19:45-19:51 мск."))assertFalse(raw,SourceSpeechPlan45.compile(raw).atoms.stream().anyMatch(a->a.rule.startsWith("source49-clock")));String raw="Отношение 12:34.";assertTrue(SourceSpeechPlan45.compile(raw).project(0,raw.length()).contains("двоеточие"));}
}

package ru.bububu.silero.stressprobe;
import org.junit.Test;import java.util.*;import ru.bububu.radio.models.SileroSpeechInput;import static org.junit.Assert.*;
public final class RadioFragmentsTest {
    @Test public void lfContextSurvivesRealPlanningWithoutChangingCanonicalCutsOrValues() {
        String raw="Синтетическая новость ".repeat(8)+"более\n1% и до\n15%, 15\nоктября, в\n2026\nгоду.";
        String original=raw;List<String> cuts=RadioFragments.plan(new FrontendPlan.Data(),raw,1000);
        String actual=String.join(" ",cuts.stream().map(SileroSpeechInput::project).toArray(String[]::new));
        assertTrue(actual.contains("более одного процента"));assertTrue(actual.contains("до пятнадцати процентов"));
        assertTrue(actual.contains("пятнадцатого октября"));assertTrue(actual.contains("в две тысячи двадцать шестом году"));
        assertEquals(SileroSpeechInput.project(raw).replaceAll("\\s+"," ").trim(),actual.replaceAll("\\s+"," ").trim());
        assertEquals(original,raw);
    }
    @Test public void wholeLongSentenceUsesStrictValidOrderedCanonicalCuts() {
        String raw="Источник\n\nЗаголовок\n\n"+"Синтетическая новость сохраняет слова и порядок ".repeat(45)+"завершена.";
        List<String> cuts=RadioFragments.plan(new FrontendPlan.Data(),raw,1000);assertTrue(cuts.size()>8);
        StringBuilder joined=new StringBuilder();for(String cut:cuts){joined.append(cut.replaceAll("\\s",""));FrontendPlan p=new FrontendPlan(new FrontendPlan.Data(),SileroSpeechInput.project(cut));assertTrue(p.spoken.length()<=256);assertTrue(p.accentInputs().size()<=64);}
        assertEquals(raw.replaceAll("\\s",""),joined.toString());
    }
    @Test public void numericUnitsAndTheirCaseGovernorsStayTogetherAcrossCuts() {
        String raw="Синтетическая новость ".repeat(8)+"более 1% и до 15%, с 1998 года, в 2026 году, на 11,6%.";
        List<String> cuts=RadioFragments.plan(new FrontendPlan.Data(),raw,1000);String spoken=String.join(" ",cuts.stream().map(SileroSpeechInput::project).toArray(String[]::new));
        assertTrue(spoken.contains("более одного процента"));assertTrue(spoken.contains("до пятнадцати процентов"));
        assertTrue(spoken.contains("с тысяча девятьсот девяносто восьмого года"));assertTrue(spoken.contains("в две тысячи двадцать шестом году"));assertTrue(spoken.contains("одиннадцать целых шесть десятых процента"));
    }
    @Test public void unsupportedAtomRejectsWithoutSkippingItOrTrimmingTheArticle() {
        assertThrows(IllegalArgumentException.class,()->RadioFragments.plan(new FrontendPlan.Data(),"Текст 😀 остаётся.",1000));
        assertThrows(IllegalArgumentException.class,()->RadioFragments.plan(new FrontendPlan.Data(),"Текст с -15% остаётся.",1000));
    }
}

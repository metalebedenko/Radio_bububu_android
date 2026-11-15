package ru.bububu.radio.sources;

import java.util.*;
import org.junit.Test;
import ru.bububu.radio.core.TextSegments;
import ru.bububu.radio.models.SileroSpeechInput;
import static org.junit.Assert.*;

public class Source45NumbersTest {
    @Test public void actualSignedAndRangeValuesKeepSignsAndBothUnexpandedEndpoints(){
        assertEquals("триста тире двести",SourceNumbers45.project("300–200"));
        assertEquals("минус пять",SourceNumbers45.project("−5"));
        assertEquals("плюс тридцать",SourceNumbers45.project("+30"));
        assertEquals("плюс один тире десять",SourceNumbers45.project("+1–10"));
        assertEquals("одна тысяча девятьсот пятьдесят тире семьдесят х",SourceNumbers45.project("1950–70-х"));
        assertEquals("каналородопсина дефис два",SourceNumbers45.project("каналородопсина-2"));
    }
    @Test public void actualCalendarGroupedDecimalAndLargeCardinalAreClosedAndExact(){
        assertEquals("двадцать девятого ноября две тысячи двадцать первого года",SourceNumbers45.project("29.11.2021"));
        assertEquals("сто шестьдесят тысяч",SourceNumbers45.project("160 000"));
        assertEquals("десять целых шесть десятых",SourceNumbers45.project("10,6"));
        assertEquals("два точка ноль",SourceNumbers45.project("2.0"));
        assertEquals("двадцать миллионов двести сорок одна тысяча триста шестьдесят три",SourceNumbers45.project("20241363"));
        assertEquals("ноль девять",SourceNumbers45.project("09"));
        assertThrows(IllegalArgumentException.class,()->SourceNumbers45.project("31.02.2024"));
        assertThrows(IllegalArgumentException.class,()->SourceNumbers45.project("1000000000000"));
    }
    @Test public void observedScientificIdentifiersRemainLiteralIncludingLeadingZeroes(){
        assertEquals("эл три",SourceNumbers45.project("L3"));
        assertEquals("три ю джи девять",SourceNumbers45.project("3UG9"));
        assertEquals("эл ай эн си ноль ноль один один шесть",SourceNumbers45.project("LINC00116"));
        assertEquals("эм четыре си восемь четыре ноль",SourceNumbers45.project("m4C840"));
        assertTrue(SourceNumbers45.project("PI3K/Akt").contains("слеш"));
    }
    @Test public void actualHistoricAndCenturyYearsUseDeclaredCasesWithoutLegacyChange(){
        assertEquals("в двухтысячном году",SourceNumbers45.project("В 2000 году"));
        assertEquals("в тысяча восемьсот пятьдесят девятом году",SourceNumbers45.project("в 1859 году"));
        assertEquals("к две тысячи двадцать шестому году",SourceNumbers45.project("К 2026 году"));
        assertEquals("тысяча девятисотого г+ода",SourceNumbers45.project("1900 года"));
        assertThrows(IllegalArgumentException.class,()->SileroSpeechInput.project("В 2000 году"));
        assertThrows(IllegalArgumentException.class,()->SileroSpeechInput.project("К 2026 году"));
        assertThrows(IllegalArgumentException.class,()->SileroSpeechInput.project("300–200"));
    }
    @Test public void observedGenitiveDecimalPercentPreservesExactValue(){
        assertEquals("около трёх целых одной десятой процента",SourceNumbers45.project("около 3,1 %"));
        assertEquals("более одной целой пяти сотых процента",SourceNumbers45.project("более 1,05%"));
        assertEquals("одна целая одна десятая процента",SourceNumbers45.project("1,1%"));
    }
    @Test public void observedForbesDollarAmountsPreserveMagnitudeWithoutMathOrInference(){
        assertEquals("две целых пять десятых миллиона долларов",SourceNumbers45.project("$2,5 млн"));
        assertEquals("сто сорок миллионов долларов",SourceNumbers45.project("$140 млн"));
        assertEquals("четыреста сорок миллионов долларов",SourceNumbers45.project("$440 млн"));
        assertThrows(IllegalArgumentException.class,()->SourceSpeechPlan45.compile("$x"));
        assertTrue(SourceSpeechPlan45.compile("Бюджет $2,5 млн.").parts(512,SourceSpeechPlan45::boundedSpoken).spoken.get(0).contains("миллиона долларов"));
    }
    @Test public void realNumericFormsRemainBoundToCanonicalCutsAndUnchangedCaps(){
        String raw="К 2026 году проверили 160 000 образцов, около 3,1 % от общего числа. В 1859 году значения −5 и +30, диапазон 300–200. Дата 29.11.2021 и код LINC00116.";
        SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.compile(raw);SourceSpeechPlan45.Parts parts=plan.parts(80,SourceSpeechPlan45::boundedSpoken);
        assertEquals(parts.raw,TextSegments.checkedFragments(raw,parts.raw,80));
        for(String spoken:parts.spoken){assertFalse(spoken.matches(".*[0-9].*"));assertTrue(spoken.length()<=256);SourceSpeechPlan45.boundedSpoken(spoken);}
        assertTrue(String.join(" ",parts.spoken).contains("к две тысячи двадцать шестому году"));
    }
}

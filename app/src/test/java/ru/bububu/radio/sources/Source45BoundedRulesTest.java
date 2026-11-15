package ru.bububu.radio.sources;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Bounded R2 rule gains and factual guards; no new holdout or audio claim. */
public class Source45BoundedRulesTest {
    @Test public void explicitUnitsKeepQuantityAndUnitClass(){
        assertEquals("десять тысяч миллиампер часов",SourceNumbers45.project("10 000 мА·ч"));
        assertEquals("один миллиампер час",SourceNumbers45.project("1 мА·ч"));
        assertEquals("два миллиампер часа",SourceNumbers45.project("2 мА·ч"));
        assertEquals("одиннадцать миллиампер часов",SourceNumbers45.project("11 мА·ч"));
        for(int n=11;n<=14;n++)assertTrue(SourceNumbers45.project(n+" мА·ч").endsWith("миллиампер часов"));
        assertEquals("двадцать один мегагерц",SourceNumbers45.project("21 МГц"));
        assertEquals("двадцать два мегагерца",SourceNumbers45.project("22 МГц"));
        assertEquals("двадцать пять мегагерц",SourceNumbers45.project("25 МГц"));
    }
    @Test public void signedDecimalUnitsKeepSignsZerosAndAllDecimalPlaces(){
        assertEquals("десять целых ноль десятых мегагерца",SourceNumbers45.project("10,0 МГц"));
        assertEquals("десять целых ноль сотых мегагерца",SourceNumbers45.project("10,00 МГц"));
        assertEquals("ноль целых пять сотых мегагерца",SourceNumbers45.project("0,05 МГц"));
        assertEquals("минус десять точка ноль ноль мегагерца",SourceNumbers45.project("-10.00 МГц"));
        assertEquals("минус пять мегагерц",SourceNumbers45.project("−5 МГц"));
        assertEquals("плюс два мегагерца",SourceNumbers45.project("+2 МГц"));
        assertEquals("десять тысяч миллиампер часов",SourceNumbers45.project("10\u202f000\u00a0мА·ч"));
    }
    @Test public void ageSuffixIsExplicitAndUsesCompoundPrefixInsteadOfCardinal(){
        assertEquals("восьмидесятидвухлетняя",SourceNumbers45.project("82-летняя"));
        assertEquals("однолетний",SourceNumbers45.project("1-летний"));
        assertEquals("двадцатиоднолетнему",SourceNumbers45.project("21-летнему"));
        assertEquals("одиннадцатилетний",SourceNumbers45.project("11-летний"));
        assertEquals("сорокалетний",SourceNumbers45.project("40-летний"));
        assertEquals("девяностолетний",SourceNumbers45.project("90-летний"));
        assertEquals("девяностопятилетний",SourceNumbers45.project("95-летний"));
        assertEquals("девяностодевятилетних",SourceNumbers45.project("99-летних"));
        assertEquals("До восьмидесятидвухлетней Анны",SourceNumbers45.project("До 82-летней Анны"));
        assertFalse(SourceNumbers45.project("082-летняя").contains("восьмидесятидвухлетняя"));
        assertFalse(SourceNumbers45.project("100-летняя").contains("столетняя"));
    }
    @Test public void ordinalRequiresBothGenitiveSuffixAndNumberNoun(){
        assertEquals("сто двадцать восьмого номера",SourceNumbers45.project("128-го номера"));
        assertEquals("первого номера",SourceNumbers45.project("1-го номера"));
        assertEquals("двухтысячного номера",SourceNumbers45.project("2000-го номера"));
        assertEquals("сотого номера",SourceNumbers45.project("100-го номера"));
        assertEquals("тысячного номера",SourceNumbers45.project("1000-го номера"));
        assertEquals("девять тысяч девятьсот девяносто девятого номера",SourceNumbers45.project("9999-го номера"));
        assertFalse(SourceNumbers45.project("0128-го номера").contains("сто двадцать восьмого"));
        assertFalse(SourceNumbers45.project("128-го места").contains("сто двадцать восьмого"));
    }
    @Test public void suffixesMustBelongToWholeTokensNotFractionsSignsOrOpaqueIdentifiers(){
        for(String raw:List.of("A82-летняя","-82-летняя","1,82-летняя","082-летняя"))assertFalse(raw,SourceNumbers45.project(raw).contains("восьмидесятидвухлетняя"));
        for(String raw:List.of("X128-го номера","0128-го номера","1/128-го номера"))assertFalse(raw,SourceNumbers45.project(raw).contains("сто двадцать восьмого номера"));
    }
    @Test public void newUnitMatchersDoNotConsumeIdentifierSuffixOrWrongSiCase(){
        for(String raw:List.of("LINC00116","КР580ВМ80А","AB-10 МГц","001 100 МГц","0010 МГц","10 мГц","2 МГцABC","2 МГЦ","2 мА⋅ч","2_МГц")){
            try{String spoken=SourceNumbers45.project(raw);assertFalse(raw,spoken.contains("мегагерц"));assertFalse(raw,spoken.contains("миллиампер"));}
            catch(IllegalArgumentException unchangedUnsupported){/* Existing unsupported source stays explicit. */}
        }
        assertFalse(SourceNumbers45.project("около 2 МГц").contains("мегагерц"));
    }
    @Test public void rawOffsetsAndPunctuationSurviveFinalSpokenAtomsWithoutReparse(){
        String raw="82-летняя Анна не меняла ёмкость 10 000 мА·ч. Частота 10,0 МГц. Если нужна проверка, ждём.";
        SourceSpeechPlan45.Plan plan=SourceSpeechPlan45.compile(raw);assertEquals(raw,plan.canonical);
        String spoken=plan.project(0,raw.length());assertTrue(spoken.contains("восьмидесятидвухлетняя Анна не"));assertTrue(spoken.contains("десять тысяч миллиампер часов"));assertTrue(spoken.contains("мегагерца"));assertTrue(spoken.contains("Если нужна проверка"));
        assertEquals(3,spoken.chars().filter(c->c=='.').count());
        SourceSpeechPlan45.Parts parts=plan.parts(80,SourceSpeechPlan45::boundedSpoken);
        assertEquals(parts.raw,ru.bububu.radio.core.TextSegments.checkedFragments(raw,parts.raw,80));
        for(SourceSpeechPlan45.Atom atom:plan.atoms){assertTrue(atom.start>=0&&atom.end<=raw.length());assertFalse(atom.spoken.matches(".*[0-9].*"));}
    }
    @Test public void parenthesizedFactsCurrencyAndSentenceLettersAreNotCleanupTargets(){
        String text="Побед (6), стрелки (1) и (2), цена 19 USD. Пять градусов С. Если нужен витамин А. Но без замены.";
        SourceSpeechPlan45.Plan p=SourceSpeechPlan45.compile(text);assertEquals(text,p.canonical);
        String spoken=p.project(0,text.length());assertTrue(spoken.contains("шесть"));assertTrue(spoken.contains("один"));assertTrue(spoken.contains("два"));assertTrue(spoken.contains("USD")||spoken.contains("ю эс ди")||spoken.toLowerCase(Locale.ROOT).contains("сша")||spoken.contains("эс ша а"));
        assertEquals(4,spoken.chars().filter(c->c=='.').count());assertTrue(spoken.contains("Если"));assertTrue(spoken.contains("Но без замены"));
    }
}

package ru.bububu.radio.models;

import org.junit.Test;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.TextSegments;
import ru.bububu.radio.sources.ExtractionResult;
import ru.bububu.radio.sources.SourceArticleFramer;
import ru.bububu.radio.sources.SourceLimits;
import static org.junit.Assert.*;

public final class SileroSpeechInputTest {
    @Test public void lfActsAsOneSpaceBeforeNumericContextAndDateMatching() {
        for(String raw:new String[]{"более\n1%","до\n15%","15\nоктября","в\n2026\nгоду"})
            assertEquals(SileroSpeechInput.project(raw.replace('\n',' ')),SileroSpeechInput.project(raw));
        assertEquals("более одного процента",SileroSpeechInput.project("более\n1%"));
        assertEquals("до пятнадцати процентов",SileroSpeechInput.project("до\n15%"));
        assertEquals("пятнадцатого октября",SileroSpeechInput.project("15\nоктября"));
    }

    @Test public void lfKeepsGovernorAndDateUnitInsideTheirCanonicalProtectedSpan() {
        for(String raw:new String[]{"более\n1%","до\n15%","15\nоктября","в\n2026\nгоду"}) {
            assertEquals(raw.length(),raw.replace('\n',' ').length());
            assertEquals(SileroSpeechInput.boundaries(raw.replace('\n',' ')),SileroSpeechInput.boundaries(raw));
            assertEquals(java.util.Collections.singletonList(raw.length()),SileroSpeechInput.boundaries(raw));
        }
    }

    @Test public void lfAnalysisDoesNotEraseSignedNumberRejectionOrOtherControls() {
        assertThrows(IllegalArgumentException.class,()->SileroSpeechInput.project("-\n15%"));
        assertTrue(SileroSpeechInput.project("более\r1%").contains("\r"));
        assertTrue(SileroSpeechInput.project("до\t15%").contains("\t"));
    }
    @Test public void ordinaryIntegerBoundariesKeepTheirExactValues() {
        assertEquals("ноль, девятнадцать, двадцать, девяносто девять, сто, девятьсот девяносто девять, одна тысяча, девять тысяч девятьсот девяносто девять",
                SileroSpeechInput.project("0, 19, 20, 99, 100, 999, 1000, 9999"));
    }
    @Test public void ordinaryIntegerDateAndYearValuesHaveExplicitSpokenForms() {
        assertEquals("Найдено двадцать три предмета и одна тысяча девятьсот девяносто восемь деталей.",
                SileroSpeechInput.project("Найдено 23 предмета и 1998 деталей."));
        assertEquals("С тысяча девятьсот девяносто восьмого года, в две тысячи двадцать шестом году, до две тысячи двадцать седьмого года.",
                SileroSpeechInput.project("С 1998 года, в 2026 году, до 2027 года."));
        assertEquals("пятнадцатого октября. Сегодня двадцать седьмое октября.",SileroSpeechInput.project("15 октября. Сегодня 27 октября."));
    }

    @Test public void decimalAndWholePercentRetainValueAndFractionScale() {
        assertEquals("одиннадцать целых шесть десятых процента; сорок процентов; один процент; пятнадцать процентов; ноль целых одна сотая процента",
                SileroSpeechInput.project("11,6%; 40%; 1%; 15%; 0,01%"));
        assertEquals("более одного процента; до пятнадцати процентов; на одиннадцать целых шесть десятых процента",
                SileroSpeechInput.project("более 1%; до 15%; на 11,6%"));
    }

    @Test public void latinSpellingAndDomainDotPreserveEveryLetterWithoutTranslation() {
        assertEquals("эй би си ай эн си. экс точка уай",SileroSpeechInput.project("ABC Inc. X.Y"));
        assertEquals("ар дабл ю зед",SileroSpeechInput.project("RWZ"));
        assertEquals(",Текст, ,пояснение,   по прежнему.",SileroSpeechInput.project("\"Текст\" (пояснение) - по-прежнему."));
    }

    @Test public void ambiguousNumbersAndCasesRejectInsteadOfLosingSignsOrScale() {
        for(String raw:new String[]{"-15%","10-12","3,5 метра","12:30","01 предмет","10000 предметов","к 2026 году","29 февраля"})
            assertThrows(IllegalArgumentException.class,()->SileroSpeechInput.project(raw));
    }

    @Test public void numericDateAndPercentAtomsCannotBeSplitAtTheirInternalSpaces() {
        String raw="В 2026 году будет 11,6% и 15 октября.";
        java.util.List<Integer> cuts=SileroSpeechInput.boundaries(raw);
        assertFalse(cuts.contains(raw.indexOf(" году")));
        assertFalse(cuts.contains(raw.indexOf(" 2026")));
        assertFalse(cuts.contains(raw.indexOf(" октября")));
        assertTrue(cuts.contains(raw.length()));
    }
    @Test public void everyLfBecomesOneSpaceWithoutCollapsingOrTrimming() {
        assertEquals("  Источник    Заголовок  Тело. ",
                SileroSpeechInput.project("\n Источник\n\n  Заголовок\n\nТело.\n"));
    }

    @Test public void allOtherUtf16UnitsIncludingOtherControlsArePreserved() {
        String raw="А\r\n\tБ\u0000\u0085\u2028😀\nВ";
        String projected=SileroSpeechInput.project(raw);
        assertEquals(raw.length(),projected.length());
        for(int i=0;i<raw.length();i++)
            assertEquals(raw.charAt(i)=='\n'?' ':raw.charAt(i),projected.charAt(i));
        assertEquals("А\r \tБ\u0000\u0085\u2028😀 В",projected);
    }

    @Test public void absentLfAndNullArePassedThroughForExistingFrontendGuards() {
        String raw=new String("Короткий текст.\r\t");
        assertSame(raw,SileroSpeechInput.project(raw));
        assertNull(SileroSpeechInput.project(null));
    }

    @Test public void canonicalFramingBodyHashAndSentenceOffsetsStayExact()throws Exception {
        String body="Первая новость. Вторая новость.";
        ExtractionResult result=new ExtractionResult(body,"Заголовок","Заголовок","unknown","FIXTURE","rss_inline","fixture-v1");
        SourceArticleFramer.Framed frame=SourceArticleFramer.frame("Источник",result);
        String canonical=frame.spokenText;Article article=new Article("fixture","version","Заголовок",canonical);
        java.util.List<Integer> starts=article.sentenceStarts;
        String first=TextSegments.fragments(article.sentences.get(0).text,1000).get(0);
        assertTrue(first.contains("\n\n"));
        String local=SileroSpeechInput.project(first);
        assertEquals("Источник  Заголовок  Первая новость.",local);
        assertEquals(first.length(),local.length());
        assertSame(canonical,frame.spokenText);assertSame(canonical,article.text);assertSame(starts,article.sentenceStarts);
        assertEquals(body,canonical.substring(frame.bodyStart,frame.bodyEnd));
        assertEquals(SourceLimits.sha256(body),frame.bodyHash);
        assertEquals(canonical.indexOf("Вторая"),(int)starts.get(1));
        assertEquals("Вторая новость.",SileroSpeechInput.project(article.sentences.get(1).text));
    }
}

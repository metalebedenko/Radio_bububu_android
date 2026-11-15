package ru.bububu.silero.stressprobe;

import java.util.List;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.PlayerController.SpeechSentence;
import ru.bububu.radio.core.PlayerController.SpeechUnit;
import ru.bububu.radio.core.TextSegments;
import ru.bububu.radio.models.SileroSpeechInput;
import ru.bububu.radio.sources.ContextualSpeech;
import ru.bububu.radio.sources.SourceSpeechPlan45;

public class SourceNewsNumbersTest {
    private static void bothPaths(String body, String expected) throws Exception {
        Article framed = C8SeeAlsoTest.version(body,"Обзор").asArticle();
        Article plain = new Article("news","1","Обзор",body);
        for (Article article:List.of(plain,framed)) {
            SpeechSentence sentence = C8SeeAlsoTest.sentence(article,article.sentences.size()-1);
            assertEquals(body,sentence.text());
            for (int limit:List.of(40,512)) {
                List<String> parts=article==plain
                        ? RadioFragments.plan(C7LongAtomTest.data(),SileroSpeechInput.forSentence(sentence),limit)
                        : RadioFragments.source45(C7LongAtomTest.data(),SourceSpeechPlan45.sentence(sentence),limit);
                assertEquals(parts,TextSegments.checkedFragments(body,parts,limit));
                List<String> projections=new ArrayList<>();
                for (int i=0;i<parts.size();i++) {
                    String projection=SourceSpeechPlan45.project(new SpeechUnit(sentence,parts,i));
                    C7LongAtomTest.carrier(C7LongAtomTest.data(),projection);
                    projections.add(projection);
                }
                String spoken=String.join(" ",projections);
                assertTrue(spoken,spoken.contains(expected));
            }
        }
    }
    @Test public void romanQuarterInflectsByItsNounAndKeepsTheCanonicalFragment() throws Exception {
        bothPaths("В III квартале спрос вырос.","третьем квартале");
        bothPaths("К IV кварталу подготовили отчёт.","четвёртому кварталу");
        bothPaths("После II квартала изменили план.","второго квартала");
    }
    @Test public void explicitRangeCarriesTheScaleToBothEndpoints() throws Exception {
        bothPaths("Компания заплатила $20-25 миллионов.","от двадцати миллионов до двадцати пяти миллионов долларов");
        bothPaths("Оценка: € 21–22 тысячи.","от двадцати одной тысячи до двадцати двух тысяч евро");
        bothPaths("Оценка: ₽0—1 миллиард.","от нуля миллиардов до одного миллиарда рублей");
    }
    @Test public void clauseInitialSeasonUsesTheCalendarYearOnlyWithRightHandEvidence() throws Exception {
        String year="две тысячи двадцать седьмого г+ода";
        bothPaths("Летом 2027.",year);
        bothPaths("Летом 2027 выйдет коммерческая версия.",year);
        bothPaths("На весну 2027 планируется крупное обновление.",year);
        bothPaths("Осенью 2024 откроется выставка.","две тысячи двадцать четвёртого г+ода");
    }
    @Test public void quantitiesCodesAndIncompleteDatesSurviveSeasonRecognition(){
        for(String raw:List.of("Летом 2027 автомобилей прошли осмотр.", "Летом 2027 новых автомобилей.",
                "На весну 2027 машин заказали заранее.", "Летом 2027,5 кг бумаги приняли.",
                "На весну 2027.03 выйдет сборка.", "Летом 2027/28.", "Летом 2027–2028.", "Летом 2027-го.",
                "Версия Летом 2027.", "Заказали летом 2027.", "Модель: летом 2027.",
                "Летом 027.", "Летом 2207.", "Летом 2027 года.", "[Летом 2027.]")){
            assertFalse(raw,ContextualSpeech.scan(raw).stream().anyMatch(r->r.kind.equals("season-year")));
        }
    }
    @Test public void explicitScaledMeasuresKeepPrecisionUnitsAndGrammaticalCase() throws Exception {
        bothPaths("Потери эквивалентны выбросам 2,3 миллиарда метрических тонн.",
                "двух целых трёх десятых миллиарда метрических тонн");
        bothPaths("Оказались утрачены 9,8 миллиона акров.","девять целых восемь десятых миллиона акров");
        bothPaths("До −0,050 миллиона гектаров.","минус нуля целых пятидесяти тысячных миллиона гектаров");
        bothPaths("Собрано +2,01 млн литров.","плюс две целых одна сотая миллиона литров");
        bothPaths("К 2,02 миллиона тонн.","двум целым двум сотым миллиона тонн");
    }
    @Test public void incompleteScaledUnitsCodesListsAndAmbiguousGovernorsKeepTheirPriorReadings() {
        for (String raw:List.of("9,8 миллиона неизвестных", "9,8 акров", "9.8 миллиона акров",
                "Код 9,8 миллиона акров.", "ID: «9,8 миллиона акров».", "Версия 2,3 миллиарда метрических тонн.",
                "Q9,8 миллиона акров", "В 9,8 миллиона акров", "С 9,8 миллиона акров",
                "Они 9,8 миллиона акров", "10/9,8 миллиона акров", "1, 9,8 миллиона акров",
                "2,3,4 миллиона акров", "09,8 миллиона акров", "9,1234567 миллиона акров",
                "9,8 миллиона тонн/сутки", "1.02.2025", "[$9,8 миллиона акров]", "$9,8 миллиона акров")) {
            assertFalse(raw,ContextualSpeech.scan(raw).stream().anyMatch(r->r.kind.equals("scaled-decimal-measure")));
        }
    }
    @Test public void temporalRangeUsesGenitiveStartAndInanimateAccusativeEnd() throws Exception {
        bothPaths("С 1990 по 2017 год менялись условия.",
                "тысяча девятьсот девяностого г+ода по две тысячи семнадцатый год");
        bothPaths("Архив датирован с 2000 по 2023 год.",
                "двухтысячного г+ода по две тысячи двадцать третий год");
        bothPaths("С 1917 по 1940 год рос выпуск.",
                "тысяча девятьсот семнадцатого г+ода по тысяча девятьсот сороковой год");
        bothPaths("С 2001 по 2008 год шли работы.","две тысячи первого г+ода по две тысячи восьмой год");
    }
    @Test public void inclusiveDayRangeUsesTheCalendarDayAtBothEnds() throws Exception {
        bothPaths("С 1 января по 10 марта шли занятия.","первого января по десятое марта");
        bothPaths("С 21 по 23 марта открыта выставка.","двадцать первого по двадцать третье марта");
        bothPaths("Со 2 мая по 3 июня шли наблюдения.","второго мая по третье июня");
        bothPaths("С 28 декабря по 5 января работал музей.","двадцать восьмого декабря по пятое января");
        // A single date uses its old form outside the explicit inclusive range.
        bothPaths("Встреча 10 марта состоялась.","десятого марта");
    }
    @Test public void explicitTwoDigitAgeAdjectivesKeepTheirWrittenInflectionOnBothPaths() throws Exception {
        bothPaths("В преддверии 60-летнего юбилея готовили программу.","шестидесятилетнего юбилея");
        bothPaths("О 2-летнем ребёнке рассказали врачу.","двухлетнем ребёнке");
        bothPaths("К 21-летнему участнику подошёл тренер.","двадцатиоднолетнему участнику");
        bothPaths("Несколько 95-летних участников пришли на праздник.","девяностопятилетних участников");
        bothPaths("Открылась 82‑летняя история выставки.","восьмидесятидвухлетняя история");
        bothPaths("С 18‐летними студентами работал тренер.","восемнадцатилетними студентами");
    }
    @Test public void rangesWithoutCalendarEvidenceAndOpaqueAgeLabelsDoNotGainTheseRules() {
        for (String raw:List.of("С 1 до 10 марта.", "С 1 кг по 10 марта.", "С 1 января по 10 кг.",
                "По 10 марта.", "31 декабря.", "С 31 февраля по 10 марта.", "С 1 по 31 апреля.",
                "С 29 февраля по 10 марта 2025 года.", "С 1 января по 29 февраля 2025.", "С 22 по 11 мая.",
                "С 01 января по 03 марта.", "С 1 января по 10 марта/кг.", "ID: с 1 января по 10 марта.",
                "https://example.org/60-летнего", "[60-летнего]", "Код: 60-летнего.", "X60-летний.", "V.60-летний.",
                "012-летняя.", "100-летняя.", "1 60-летние.", "12 — 15-летних.", "− 60-летние.",
                "60-лет.", "60 - летние.", "60-летними/кг", "70-80 кг.")) {
            assertFalse(raw,ContextualSpeech.scan(raw).stream().anyMatch(r->r.kind.startsWith("calendar-day-range")||r.kind.equals("age-compound")));
        }
    }
    @Test public void codeAndQuantityRangesAreNotCalendarPeriods() {
        for (String raw:List.of("с 1990 по 2017 кг в год", "с 1990 по 2017 годовых взносов", "с 1990 по 2017",
                "Код с 1990 по 2017 год", "ID: с 1990 по 2017 год", "ID/с 1990 по 2017 год", "с 0190 по 2017 год", "с −1990 по 2017 год",
                "с 1990.03 по 2017 год", "с 1990 по 2017.04", "с 1990-01-03 по 2017 год",
                "с 1990 по 2017 год/м", "с 2017 по 1900 год", "[с 1990 по 2017 год]")) {
            assertFalse(raw,ContextualSpeech.scan(raw).stream().anyMatch(r->r.kind.startsWith("calendar-range-")));
        }
    }
    @Test public void unsupportedOrAmbiguousNotationDoesNotGainTheseReadings() {
        for(String raw:List.of("Группа III выступила.", "Подпись I квартал.", "В V квартале.", "В iii квартале.",
                "В II кварталу.", "В I–III квартале.", "Проверьте [$20-25 миллионов].",
                "https://site.test/a/$20-25 миллионов.", "Модель X$20-25 миллионов.",
                "Код $20-25 миллионов.", "От $20-25 млн.", "Оценка: -$20-25 млн.",
                "Курс $02-25 млн.", "Диапазон $2,0-2,5 млн.", "Оценка $20-25 млн рублей.", "Оценка $20-25 млн. руб.",
                "Версия 20-25.3.", "Счёт 6:3, 6:4.", "Дата 20-05-2026.", "За 1e-2 кг.")) {
            assertFalse(raw,ContextualSpeech.scan(raw).stream().anyMatch(r->r.kind.equals("roman-quarter")||r.kind.equals("scaled-currency-range")));
        }
    }
}

package ru.bububu.radio.sources;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextualMeasuresTest {
    private static List<ContextualSpeech.Reading> readings(String text) {
        List<ContextualSpeech.Reading> values = new ArrayList<>();
        ContextualMeasures.add(text, values);
        return values;
    }

    private static void expect(String text, String spoken) {
        List<ContextualSpeech.Reading> values = readings(text);
        assertEquals(text, 1, values.size());
        assertEquals(text, spoken, values.get(0).spoken);
    }

    @Test public void genderSignsAndIndeclinableCurrencyFollowTheExplicitUnit() {
        expect("Весит 21 т", "двадцать одна тонна");
        expect("22 т", "двадцать две тонны");
        expect("12 т", "двенадцать тонн");
        expect("9 км", "девять километров");
        expect("2 кг", "два килограмма");
        expect("Память — 16 МБ.", "шестнадцать мегабайтов");
        expect("2 GiB", "два гибибайта");
        expect("€21", "двадцать один евро");
        expect("−22 ₽", "минус двадцать два рубля");
        expect("+2°C", "плюс два градуса Цельсия");
        expect("−11 °С", "минус одиннадцать градусов Цельсия");
        expect("120\u202f005 ₽", "сто двадцать тысяч пять рублей");
        expect("21 202 001 ₽", "двадцать один миллион двести две тысячи один рубль");
    }

    @Test public void everyDecimalPlaceSurvivesIncludingTrailingZeroes() {
        expect("0,010 кг", "ноль целых десять тысячных килограмма");
        expect("-1.01 °C", "минус одна целая одна сотая градуса Цельсия");
        expect("2,000000 ГБ", "две целых ноль миллионных гигабайта");
        expect("€0,002", "ноль целых две тысячных евро");
    }

    @Test public void explicitGovernmentControlsTheNumberAndTheUnit() {
        expect("до 21 т", "двадцати одной тонны");
        expect("от 12 кг", "двенадцати килограммов");
        expect("около 0,21 км", "нуля целых двадцати одной сотой километра");
        expect("к 3 кг", "трём килограммам");
        expect("к 101 т", "ста одной тонне");
        expect("к 2,02 м", "двум целым двум сотым метра");
        String text = "📦: от 2,50 кг.";
        ContextualSpeech.Reading reading = readings(text).get(0);
        assertEquals("2,50 кг", text.substring(reading.start, reading.end));
        assertEquals("от 2,50 кг", text.substring(reading.contextStart, reading.contextEnd));
        assertEquals("двух целых пятидесяти сотых килограмма", reading.spoken);
    }

    @Test public void unknownCasesDatesCurrencySuffixesAndCompoundUnitsRemainUntouched() {
        for (String text : List.of("до 1 г.", "в 2026 г.", "1 г. до н. э.", "1 г до н. э.", "1700 г", "15 т. е.",
                "класс 7 Б", "5 B", "А/Б", "модель 3 кг", "M8-3 кг", "16 мб", "20 MBps", "20 Мб",
                "12 мг/л", "12 мг / л", "10 м²", "2–8 кг", "2 — 8 кг", "1.2.3 кг", "1, 25 кг",
                "2e−3 кг", "− 5 °C", "3 05 кг", "00,1 кг", "1,0000000 кг", "в 2 кг", "с 2 кг",
                "$1.50", "$5.30", "$1,2,3", "$1e3", "$140 млн", "$2,5 млн", "€25 ₽", "$12,15 кг",
                "12 кг м", "12 МГц", "10 мА·ч"))
            assertTrue(text, readings(text).isEmpty());
    }
}

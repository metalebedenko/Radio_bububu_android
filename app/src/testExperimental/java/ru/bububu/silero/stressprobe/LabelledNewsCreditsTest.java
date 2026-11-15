package ru.bububu.silero.stressprobe;

import java.util.List;
import java.util.Locale;
import org.junit.Test;
import ru.bububu.radio.core.TextSegments;
import ru.bububu.radio.models.SileroSpeechInput;
import ru.bububu.radio.sources.ContextualSpeech;
import ru.bububu.radio.sources.SourceSpeechPlan45;
import static org.junit.Assert.*;

public final class LabelledNewsCreditsTest {
    private static List<ContextualSpeech.Reading> slashes(String text) {
        return ContextualSpeech.scan(text).stream().filter(r -> r.kind.equals("photo-agency-divider"))
                .collect(java.util.stream.Collectors.toList());
    }

    private static void both(String text, int count) {
        List<ContextualSpeech.Reading> dividers = slashes(text);
        assertEquals(text, count, dividers.size());
        for (ContextualSpeech.Reading r : dividers) assertEquals("/", text.substring(r.start, r.end));

        SileroSpeechInput.SentencePlan legacy = SileroSpeechInput.forSentence(text);
        List<String> v1 = RadioFragments.plan(new FrontendPlan.Data(), legacy, 1000);
        assertEquals(v1, TextSegments.checkedFragments(text, v1, 1000));
        String spoken = legacy.project(0, text.length()).toLowerCase(Locale.ROOT);
        assertTrue(spoken, spoken.contains("бурматов"));
        assertTrue(spoken, spoken.contains("тасс"));
        assertTrue(spoken, spoken.endsWith("следующий абзац."));

        SourceSpeechPlan45.Parts v2 = SourceSpeechPlan45.compile(text).parts(512, s -> new FrontendPlan(new FrontendPlan.Data(), s));
        assertEquals(v2.raw, TextSegments.checkedFragments(text, v2.raw, 512));
        String actual = String.join(" ", v2.spoken).toLowerCase(Locale.ROOT);
        assertTrue(actual, actual.contains("бурматов"));
        assertTrue(actual, actual.contains("тасс"));
        assertTrue(actual, actual.endsWith("следующий абзац."));
    }

    @Test public void russianAndEnglishBylineFieldsWorkWithoutDroppingEditorialCopy() {
        for (String field : List.of("Фото:", "Автор фото —", "Снимок:", "Фотограф:", "Иллюстрация:",
                "Photo credit:", "Image credits:", "Photographer:", "Автор материала:", "Byline:")) {
            both("Основной текст\n\n" + field + " Николай Бурматов/ТАСС\n\nСледующий абзац.", 1);
        }
        both("Фото: Н. Бурматов / ТАСС. Следующий абзац.", 1);
        both("Фото: ©Николай Бурматов/ТАСС\nСледующий абзац.", 1);
    }

    @Test public void aLabelCanHeadMultipleAdjacentAttributionsButCannotLeakIntoANewParagraph() {
        both("Источник фотографии:\r\n\r\nНиколай Бурматов/ТАСС\nАнна Зорина / РИА Новости\nСледующий абзац.", 2);
        for (String raw : List.of(
                "Фото: тестовая дробь 5/8", "Фото: Документы лежат по адресу https://site.test/Press/Agency",
                "Статистика: Николай Бурматов/ТАСС", "Открыли график Фото: Николай Бурматов/ТАСС",
                "Источники: Константа/Переменная", "Фото: Николай Бурматов/ТАСС.рф")) {
            assertTrue(raw, slashes(raw).isEmpty());
        }
        assertEquals(1, slashes("Фото: Николай Бурматов/ТАСС\n\nАнна Зорина/Компания").size());
        assertEquals(1, slashes("Фото: Николай Бурматов/ТАСС\nИсточник изменился.\nАнна Зорина/Компания").size());
    }
}

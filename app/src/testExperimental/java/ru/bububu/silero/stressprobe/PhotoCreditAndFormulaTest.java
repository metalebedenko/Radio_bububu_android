package ru.bububu.silero.stressprobe;

import java.util.List;
import org.junit.Test;
import ru.bububu.radio.core.TextSegments;
import ru.bububu.radio.models.SileroSpeechInput;
import ru.bububu.radio.sources.ContextualSpeech;
import ru.bububu.radio.sources.SourceSpeechPlan45;
import static org.junit.Assert.*;

public class PhotoCreditAndFormulaTest {
    private static String clean(String s) { return s.replaceAll("\\s+", " ").replace(" ,", ",").trim(); }

    private static String[] both(String raw) {
        SileroSpeechInput.SentencePlan legacy = SileroSpeechInput.forSentence(raw);
        List<String> simple = RadioFragments.plan(new FrontendPlan.Data(), legacy, 1000);
        assertEquals(simple, TextSegments.checkedFragments(raw, simple, 1000));
        SourceSpeechPlan45.Parts parts = SourceSpeechPlan45.compile(raw).parts(512, spoken -> new FrontendPlan(new FrontendPlan.Data(), spoken));
        assertEquals(parts.raw, TextSegments.checkedFragments(raw, parts.raw, 512));
        return new String[]{clean(legacy.project(0, raw.length())), clean(String.join(" ", parts.spoken))};
    }

    @Test public void separatorsInSignedCreditsPreserveBothNamesAndFollowingEditorialText() {
        for (String credit : List.of("© John Orr / AFP via Getty Images", "(Photo by JOHN ORR / AFP)",
                "(Photo by John Orr/agency via Getty Images)")) {
            String raw = credit + "\n\nЛеса поглощают углерод.";
            List<ContextualSpeech.Reading> separators = ContextualSpeech.scan(raw).stream()
                    .filter(r -> r.kind.equals("photo-agency-divider")).collect(java.util.stream.Collectors.toList());
            assertEquals(credit, 1, separators.size());
            assertEquals("/", raw.substring(separators.get(0).start, separators.get(0).end));
            for (String spoken : both(raw)) {
                String lower = spoken.toLowerCase(java.util.Locale.ROOT);
                assertTrue(spoken, lower.contains("оу ар ар,"));
                assertTrue(spoken, lower.contains(credit.contains("agency") ? "эй джи и эн си уай" : "эй эф пи"));
                assertTrue(spoken, lower.endsWith("леса поглощают углерод."));
            }
        }
    }

    @Test public void chemicalElementsAndEveryExplicitIndexKeepTheirOrder() {
        for (String[] pair : new String[][]{
                {"Выбросы CO₂.", "цэ о два"}, {"Свойства H₂O и O₂.", "аш два о и о два"},
                {"Образец NH₃.", "эн аш три"}, {"Химическая формула Na₂SO₄.", "натрий два эс о четыре"},
                {"Формула C₁₂H₂₂O₁₁.", "цэ двенадцать аш двадцать два о одиннадцать"}
        }) {
            for (String spoken : both(pair[0])) assertTrue(spoken, spoken.contains(pair[1]));
        }
        String prefixed = "😀 Уровень CO₂.";
        ContextualSpeech.Reading reading = ContextualSpeech.scan(prefixed).stream()
                .filter(r -> r.kind.equals("chemical-formula")).findFirst().orElseThrow();
        assertEquals("CO₂", prefixed.substring(reading.start, reading.end));
        assertThrows(IllegalArgumentException.class, () -> SileroSpeechInput.forSentence(prefixed).project(reading.start, reading.end - 1));
    }

    @Test public void uncertainChemistryAndUnattributedSlashesNeverBecomeSpecialReadings() {
        for (String raw : List.of("Автор / Компания", "A/B", "© Smith 1/250", "© John Orr\n/ AFP", "(Photo by имя / 2)",
                "https://example.org/A/B", "Имя/agency via Getty", "X₂", "A₂", "100₂", "CO2", "модель CO₂",
                "URL https://foo.test/CO₂", "CO₂/N₂", "CO₂⁺", "CO₂₂₁", "Формула Na₀Cl₂",
                "LINC00116", "PI3K/Akt", "10⁻¹¹–10⁻¹²", "формула C₂₀H₂₁.txt")) {
            assertTrue(raw, ContextualSpeech.scan(raw).stream().noneMatch(r ->
                    r.kind.equals("chemical-formula") || r.kind.equals("photo-agency-divider")));
        }
    }
}

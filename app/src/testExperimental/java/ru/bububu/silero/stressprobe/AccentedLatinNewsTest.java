package ru.bububu.silero.stressprobe;

import java.text.Normalizer;
import java.util.List;
import org.junit.Test;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.PlayerController;
import ru.bububu.radio.core.TextSegments;
import ru.bububu.radio.models.SileroSpeechInput;
import ru.bububu.radio.sources.ContextualSpeech;
import ru.bububu.radio.sources.SourceSpeechPlan45;
import static org.junit.Assert.*;

public final class AccentedLatinNewsTest {
    private static final String BODY = "Суд во Вьетнаме приговорил к тюремному заключению сроком до одного года 11 человек, "
            + "работавших на пиратском сайте Bún Chả TV, который транслировал футбольные матчи.";
    private static final String NAMES = "би ю эн си эйч эй ти ви";

    @Test public void habrSentenceReachesTheFrontendInBothArticleFormats() throws Exception {
        Article legacy = new Article("news", "v1", "Новости", BODY);
        PlayerController.SpeechSentence sentence = C8SeeAlsoTest.sentence(legacy, 0);
        SileroSpeechInput.SentencePlan legacyPlan = SileroSpeechInput.forSentence(sentence);
        List<String> parts = RadioFragments.plan(new FrontendPlan.Data(), legacyPlan, 512);
        assertEquals(List.of(BODY), parts);
        String projected = legacyPlan.project(0, BODY.length());
        assertTrue(projected, projected.contains(NAMES));
        assertTrue(projected, projected.contains("одиннадцать человек"));
        assertTrue(projected, projected.endsWith("который транслировал футбольные матчи."));

        Article sourced = C8SeeAlsoTest.version(BODY, "Новости").asArticle();
        int index = sourced.sentences.size() - 1;
        assertEquals(BODY, sourced.sentences.get(index).text);
        SourceSpeechPlan45.Plan plan = SourceSpeechPlan45.sentence(C8SeeAlsoTest.sentence(sourced, index));
        SourceSpeechPlan45.Parts verification = plan.parts(512, s -> new FrontendPlan(new FrontendPlan.Data(), s));
        assertEquals(List.of(BODY), verification.raw);
        assertTrue(verification.spoken.get(0), verification.spoken.get(0).contains(NAMES));
    }

    @Test public void decomposedNamesUseTheirOriginalOffsetsAndRemainWholeInSmallFragments() {
        String name = Normalizer.normalize("Chả", Normalizer.Form.NFD);
        String raw = "До " + name + " был мост.";
        ContextualSpeech.Reading reading = ContextualSpeech.scan(raw).get(0);
        assertEquals(name, raw.substring(reading.start, reading.end));
        assertEquals("си эйч эй", reading.spoken);
        assertEquals(SileroSpeechInput.project("До Chả был мост."), SileroSpeechInput.project(raw));
        SileroSpeechInput.SentencePlan plan = SileroSpeechInput.forSentence(raw);
        assertThrows(IllegalArgumentException.class, () -> plan.project(reading.start, reading.end - 1));
        assertFalse(plan.boundaries().stream().anyMatch(i -> i > reading.start && i < reading.end));
        List<String> fragments = RadioFragments.plan(new FrontendPlan.Data(), plan, 7);
        assertEquals(fragments, TextSegments.checkedFragments(raw, fragments, 7));
        assertTrue(fragments.stream().anyMatch(fragment -> fragment.contains(name)));
        String prefixed = "😀 " + raw;
        ContextualSpeech.Reading afterPair = ContextualSpeech.scan(prefixed).get(0);
        assertEquals(prefixed.indexOf(name), afterPair.start);
        assertEquals(name, prefixed.substring(afterPair.start, afterPair.end));
    }

    @Test public void otherScriptsUnrecognizedLettersAndOpaqueIdentifiersKeepTheirOriginalContract() {
        for (String raw : List.of("ёлка", "бо́льшую", "άλφα", "việт", "Łódź", "Bún2", "https://example.org/Bún")) {
            assertTrue(raw, ContextualSpeech.scan(raw).isEmpty());
        }
        assertEquals("бо́льшую", SileroSpeechInput.project("бо́льшую"));
        assertEquals("латинская е с акутом", SourceSpeechPlan45.compile("é").project(0, 1));
    }
}

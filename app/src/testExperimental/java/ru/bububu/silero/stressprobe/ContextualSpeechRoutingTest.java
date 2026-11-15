package ru.bububu.silero.stressprobe;

import java.util.List;
import org.junit.Test;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.PlayerController.SpeechSentence;
import ru.bububu.radio.core.PlayerController.SpeechUnit;
import ru.bububu.radio.core.TextSegments;
import ru.bububu.radio.models.SileroSpeechInput;
import ru.bububu.radio.sources.SourceSpeechPlan45;
import static org.junit.Assert.*;

public final class ContextualSpeechRoutingTest {
    @Test(timeout=10000) public void oversizedNumberFromPublisherIsNotRetriedAtEveryDigit() {
        String opaque = "9".repeat(100000);
        assertTrue(ru.bububu.radio.sources.ContextualSpeech.scan(opaque).isEmpty());
    }

    @Test public void ordinaryArticleUsesSentenceContextForAUnitSeparatedFromItsGovernor() {
        Article article = new Article("synthetic", "unchanged", "Учёба", "В 10-м классе.");
        SpeechSentence sentence = C8SeeAlsoTest.sentence(article, 0);
        List<String> raw = List.of("В", "10-м", "классе.");
        SpeechUnit number = new SpeechUnit(sentence, raw, 1);
        assertEquals("десятом", SourceSpeechPlan45.project(number));
        assertEquals("10-м", number.text);
        assertEquals("unchanged", article.version);
    }

    @Test public void frontendAndRenderingAgreeAcrossDifferentFragmentSizes() {
        String text = "В 10-м классе начался новый урок, а на выставку привезли груз массой 2 кг.";
        Article article = new Article("synthetic", "v1", "Учёба", text);
        SpeechSentence sentence = C8SeeAlsoTest.sentence(article, 0);
        String expected = SileroSpeechInput.project(text);
        assertTrue(expected, expected.contains("десятом классе"));
        assertTrue(expected, expected.contains("два килограмма"));
        for (int limit : new int[]{40, 64, 512}) {
            List<String> raw = RadioFragments.plan(C7LongAtomTest.data(), text, limit);
            TextSegments.checkedFragments(text, raw, limit);
            StringBuilder spoken = new StringBuilder();
            for (int i = 0; i < raw.size(); i++) {
                String part = SourceSpeechPlan45.project(new SpeechUnit(sentence, raw, i));
                C7LongAtomTest.carrier(C7LongAtomTest.data(), part);
                if (i > 0) spoken.append(' ');
                spoken.append(part);
            }
            assertEquals(expected, spoken.toString());
        }
    }
}

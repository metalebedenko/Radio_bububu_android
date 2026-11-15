package ru.bububu.silero.stressprobe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.PlayerController;
import ru.bububu.radio.core.TextSegments;
import ru.bububu.radio.models.RadioLookahead;
import ru.bububu.radio.models.SileroSpeechInput;
import ru.bububu.radio.sources.ContextualSpeech;
import ru.bububu.radio.sources.SourcePronunciation48;
import ru.bububu.radio.sources.SourceSpeechPlan45;
import static org.junit.Assert.*;

public class SourceTypographyTest {
    private static String compact(String s) { return s.replaceAll("\\s+", " ").trim(); }

    @Test public void admittedWordHyphensKeepBothWordsAndOffsetsOnBothRoutes() {
        String ascii = "Бета-версия и AI-ассистент.";
        for (char dash : new char[]{'‐','‑'}) {
            String raw = ascii.replace('-', dash);
            SileroSpeechInput.SentencePlan legacy = SileroSpeechInput.forSentence(raw);
            assertEquals(SileroSpeechInput.project(ascii), legacy.project(0, raw.length()));
            assertEquals(List.of(raw), RadioFragments.plan(new FrontendPlan.Data(), legacy, 1000));

            SourceSpeechPlan45.Plan plan = SourceSpeechPlan45.compile(raw);
            SourceSpeechPlan45.Parts fragments = plan.parts(12, s -> new FrontendPlan(new FrontendPlan.Data(), s));
            assertEquals(fragments.raw, TextSegments.checkedFragments(raw, fragments.raw, 12));
            assertEquals(compact(SileroSpeechInput.project(ascii)), compact(String.join(" ", fragments.spoken)));
            String prefixed = "😀 " + raw;
            for (ContextualSpeech.Reading r : ContextualSpeech.scan(prefixed))
                assertEquals(String.valueOf(dash), prefixed.substring(r.start, r.end));
        }
    }

    @Test public void publisherCreditPreservesTheAuthorTheCaptionAndTheEditorialSentence() {
        String raw = "Снимок под микроскопом / ©Getty images\n\nУглерод образует разные соединения.";
        SileroSpeechInput.SentencePlan v1 = SileroSpeechInput.forSentence(raw);
        List<String> parts = RadioFragments.plan(new FrontendPlan.Data(), v1, 1000);
        assertEquals(parts, TextSegments.checkedFragments(raw, parts, 1000));
        String projected = compact(v1.project(0, raw.length()));
        assertTrue(projected, projected.contains("знак авторского права джи и ти ти уай"));
        assertTrue(projected, projected.endsWith("Углерод образует разные соединения."));
        SourceSpeechPlan45.Plan v2 = SourceSpeechPlan45.compile(raw);
        String speech = compact(String.join(" ", v2.parts(512, s -> new FrontendPlan(new FrontendPlan.Data(), s)).spoken));
        assertTrue(speech, speech.contains("знак авторского права джи и ти ти уай"));
        assertTrue(speech, speech.contains("Углерод"));
        assertTrue(speech, speech.endsWith("соединения."));
        ContextualSpeech.Reading credit = ContextualSpeech.scan(raw).get(0);
        assertEquals("/ ©", raw.substring(credit.start, credit.end));
        assertTrue(compact(SileroSpeechInput.project("©Getty")).startsWith("знак авторского права джи"));
    }

    @Test public void rangesPathsOpaqueCodesAndOtherSlashOperandsNeverBecomeProsePunctuation() {
        for (String raw : List.of("12‑15", "X‐Y", "ISO‑IEC", "в коде A1‑B2", "номер: beta‑release",
                "https://example.org/prose‑word", "foo‑bar.example", "a/b", "1 / 2", "20 / ©Publisher",
                "https://example.org/©Getty", "путь/©Getty", "До © после.", "A/B", "©2025")) {
            for (ContextualSpeech.Reading r : ContextualSpeech.scan(raw))
                assertFalse(raw, r.kind.equals("prose-word-hyphen") || r.kind.equals("publisher-credit"));
        }
    }

    @Test public void oldVoiceStampCannotClaimPreparedAudioForEitherArticleFormat() throws Exception {
        String old = "source-spoken-runtime53-contextual-v1:" + SourcePronunciation48.stamp();
        String current = SourceSpeechPlan45.runtimeStamp();
        assertNotEquals(old, current);
        for (Article article : List.of(new Article("qa", "v", "Тема", "Первая. Вторая."),
                C8SeeAlsoTest.version("Первая. Вторая.", "Тема").asArticle())) {
            PlayerController.SpeechSentence a = C8SeeAlsoTest.sentence(article, 0);
            PlayerController.SpeechSentence b = C8SeeAlsoTest.sentence(article, 1);
            PlayerController.SpeechUnit first = new PlayerController.SpeechUnit(a, List.of(a.text()), 0);
            PlayerController.SpeechUnit next = new PlayerController.SpeechUnit(b, List.of(b.text()), 0);
            RadioLookahead cache = new RadioLookahead();
            RadioLookahead.Slot oldSlot = cache.reserve(first, old);
            assertTrue(cache.planned(oldSlot, next));
            Path wav = Files.createTempFile("old-runtime-stamp-", ".wav");
            Files.write(wav, new byte[46]);
            assertFalse(cache.ready(oldSlot, next, current, SourceSpeechPlan45.hash(next), wav.toFile()));
            assertFalse(Files.exists(wav));
        }
    }
}

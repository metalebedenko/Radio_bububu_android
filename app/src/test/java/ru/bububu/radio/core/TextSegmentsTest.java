package ru.bububu.radio.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class TextSegmentsTest {
    @Test public void checkedBackendCutsPreserveUtf16AndFreezeTheSubmittedList() {
        String raw="Источник\n\nТекст 😀😀 сохраняет 17 символов и порядок.";
        List<String> mutable=new java.util.ArrayList<>(TextSegments.fragments(raw,8));
        List<String> checked=TextSegments.checkedFragments(raw,mutable,1000);
        assertEquals(mutable,checked);mutable.clear();assertFalse(checked.isEmpty());
        StringBuilder joined=new StringBuilder();
        for(String fragment:checked) {
            assertFalse(Character.isLowSurrogate(fragment.charAt(0)));
            assertFalse(Character.isHighSurrogate(fragment.charAt(fragment.length()-1)));
            joined.append(fragment);
        }
        assertEquals(raw.replaceAll("\\s", ""),joined.toString().replaceAll("\\s", ""));
        assertThrows(UnsupportedOperationException.class,()->checked.clear());
    }

    @Test public void checkedBackendCutsRejectOmissionDuplicationReorderingAndSplitSurrogates() {
        String raw="А😀 Б17.";
        for(List<String> broken:Arrays.asList(Arrays.asList("А😀 Б1."),Arrays.asList("А😀 Б177."),
                Arrays.asList("Б17. А😀"),Arrays.asList("А😀 Б1 7."),Arrays.asList("А\ud83d","\ude00 Б17."),Arrays.asList("")))
            assertThrows(IllegalArgumentException.class,()->TextSegments.checkedFragments(raw,broken,1000));
        assertThrows(IllegalArgumentException.class,()->TextSegments.checkedFragments(raw,java.util.Collections.emptyList(),1000));
    }

    @Test public void abbreviationsInitialsDecimalsAndQuotesKeepKnownSentences() {
        String text = "В г. Москве А. Б. Лисов проверил 3.5 метра. «Всё готово!» Следующий шаг.";
        List<Integer> starts = TextSegments.sentenceStarts(text);
        assertEquals(Arrays.asList(0, text.indexOf('«'), text.indexOf("Следующий")), starts);
        Article article = new Article("a", "v1", "Тест", text);
        assertEquals(3, article.sentences.size());
        for (Article.Sentence sentence : article.sentences) {
            assertEquals(sentence.text, text.substring(sentence.start, sentence.end).trim());
        }
    }

    @Test public void reviewedFixtureOffsetsRemainInOriginalDisplayedText() {
        Article article = Article.fromSentences("a", "v1", "Тест", "Первая.", "Вторая длиннее.", "Третья.");
        assertEquals(Arrays.asList(0, 8, 24), article.sentenceStarts);
        assertEquals("Вторая длиннее.", article.sentences.get(1).text);
        assertEquals("Третья.", article.text.substring(article.sentenceStarts.get(2)));
    }

    @Test public void nearestAnchorTieChoosesEarlierAndManualHundredPercentIsLastStart() {
        Article article = Article.fromSentences("a", "v1", "Тест", "Первая.", "Вторая.", "Третья.");
        double midpoint = (article.sentenceStarts.get(1) + article.sentenceStarts.get(2)) / 2.0 / article.text.length();
        assertEquals(1, article.nearestSentence(midpoint));
        assertEquals(0, article.nearestSentence(-1));
        assertEquals(2, article.nearestSentence(1));
        assertTrue(article.sentenceStarts.get(article.nearestSentence(1)) < article.text.length());
    }

    @Test public void fragmentsPreserveNonWhitespaceAndDoNotSplitSurrogatePairs() {
        String text = "Длинное слово " + "неразрывнаячасть" + " 😀😀 " + "окончание предложения.";
        List<String> fragments = TextSegments.fragments(text, 7);
        StringBuilder joined = new StringBuilder();
        for (String fragment : fragments) {
            assertFalse(fragment.isEmpty());
            assertTrue(fragment.length() <= 7);
            assertFalse(Character.isLowSurrogate(fragment.charAt(0)));
            assertFalse(Character.isHighSurrogate(fragment.charAt(fragment.length() - 1)));
            joined.append(fragment);
        }
        assertEquals(text.replaceAll("\\s", ""), joined.toString().replaceAll("\\s", ""));
    }

    @Test public void minimumLimitCanSpeakWholeEmoji() {
        assertEquals(Arrays.asList("😀", "😀", "."), TextSegments.fragments("😀😀.", 2));
    }

    @Test(expected = IllegalArgumentException.class) public void explicitOffsetsCannotBisectEmoji() {
        new Article("a", "v1", "Тест", "😀.", Arrays.asList(0, 1));
    }

    @Test(expected = IllegalArgumentException.class) public void offsetZeroIsRequired() {
        new Article("a", "v1", "Тест", "Первая.", Arrays.asList(1));
    }

    @Test(expected = IllegalArgumentException.class) public void nonFiniteSeekIsRejected() {
        Article.fromSentences("a", "v1", "Тест", "Первая.").nearestSentence(Double.NaN);
    }

    @Test(expected = UnsupportedOperationException.class) public void immutableTextOffsetsCannotBeChanged() {
        Article.fromSentences("a", "v1", "Тест", "Первая.").sentenceStarts.add(2);
    }
}

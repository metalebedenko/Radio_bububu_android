package ru.bububu.radio.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservative segmentation; fixtures may supply reviewed offsets instead. */
public final class TextSegments {
    private static final Set<String> ABBREVIATIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "г", "гг", "ул", "д", "рис", "стр", "проф", "др", "им", "т", "е", "н",
            "тыс", "млн", "млрд", "кв", "ч", "мин", "сек", "руб", "коп", "ст", "п")));

    private TextSegments() {}

    public static List<Integer> sentenceStarts(String text) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Empty text");
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char mark = text.charAt(i);
            if (mark != '.' && mark != '!' && mark != '?') continue;
            if (mark == '.' && isInternalPeriod(text, i)) continue;
            int end = i + 1;
            while (end < text.length() && ".!?\"»”’)]}".indexOf(text.charAt(end)) >= 0) end++;
            if (end < text.length() && !Character.isWhitespace(text.charAt(end))) continue;
            int next = end;
            while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
            if (next < text.length()) starts.add(next);
            i = next - 1;
        }
        return Collections.unmodifiableList(starts);
    }

    private static boolean isInternalPeriod(String text, int index) {
        if (index > 0 && index + 1 < text.length() && Character.isDigit(text.charAt(index - 1))
                && Character.isDigit(text.charAt(index + 1))) return true;
        int start = index;
        while (start > 0 && Character.isLetter(text.charAt(start - 1))) start--;
        String token = text.substring(start, index);
        if (token.length() == 1 && Character.isUpperCase(token.charAt(0))) return true;
        return ABBREVIATIONS.contains(token.toLowerCase(Locale.ROOT));
    }

    /** Only a canonical boundary outside a word/URL/number can be a preferred clause cut. */
    public static boolean clauseBoundary(String text,int start,int end){
        if(end<=start||end>text.length()||end<text.length()&&!Character.isWhitespace(text.charAt(end)))return false;
        int at=end-1;while(at>start&&"\"»”’)]}".indexOf(text.charAt(at))>=0)at--;
        char mark=text.charAt(at);
        return ",;:!?—–".indexOf(mark)>=0||mark=='.'&&!isInternalPeriod(text,at);
    }

    /** Each fragment is below the TTS input limit, and cannot bisect a surrogate pair. */
    public static List<String> fragments(String text, int maxChars) {
        if (maxChars < 2) throw new IllegalArgumentException("TTS fragment limit must be at least 2");
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Empty sentence");
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
            if (start == text.length()) break;
            int end = Math.min(text.length(), start + maxChars);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            if (end < text.length()) {
                int boundary = end;
                while (boundary > start && !Character.isWhitespace(text.charAt(boundary - 1))) boundary--;
                if (boundary > start) end = boundary;
            }
            String fragment = text.substring(start, end).trim();
            if (!fragment.isEmpty()) result.add(fragment);
            start = end;
        }
        return Collections.unmodifiableList(result);
    }

    /** Backend cuts must be ordered canonical slices, allowing only whitespace between them. */
    public static List<String> checkedFragments(String text, List<String> fragments, int maxChars) {
        if (text == null || fragments == null || fragments.isEmpty() || maxChars < 2)
            throw new IllegalArgumentException("INVALID_SPEECH_FRAGMENTS");
        List<String> checked = new ArrayList<>();
        int cursor = 0;
        for (String fragment : fragments) {
            if (fragment == null || fragment.trim().isEmpty() || fragment.length() > maxChars
                    || Character.isLowSurrogate(fragment.charAt(0))
                    || Character.isHighSurrogate(fragment.charAt(fragment.length() - 1)))
                throw new IllegalArgumentException("INVALID_SPEECH_FRAGMENTS");
            while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
            if (!text.startsWith(fragment, cursor))
                throw new IllegalArgumentException("SPEECH_FRAGMENT_CONTENT_CHANGED");
            cursor += fragment.length();
            checked.add(fragment);
        }
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
        if (cursor != text.length()) throw new IllegalArgumentException("SPEECH_FRAGMENT_CONTENT_CHANGED");
        return Collections.unmodifiableList(checked);
    }
}

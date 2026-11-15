package ru.bububu.radio.sources;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Spoken punctuation is contextual. Source text, saved versions and offsets stay immutable. */
final class SourceTypography {
    private SourceTypography() {}
    private static final Pattern CODE_LABEL = Pattern.compile(
            "(?iu)(?:^|[^\\p{L}\\p{N}_])(?:код|артикул|модель|индекс|номер|версия|sku|id)\\s*:?\\s*$");
    private static final String SPACE = "[ \\t\\u00a0\\u202f]";
    private static final String NAME = "(?:\\p{Lu}[\\p{L}\\p{M}’'‐‑-]{1,40}|\\p{Lu}\\.)";
    private static final String NAMES = NAME + "(?:" + SPACE + "+" + NAME + "){0,5}";
    private static final Pattern CREDIT_BEFORE_SLASH = Pattern.compile(
            "(?<![^\\n\\r(])" + SPACE + "*(?:©" + SPACE + "*|(?i:Photo" + SPACE + "+by)" + SPACE + "+)"
                    + NAMES + SPACE + "*$");
    private static final String AGENCY = "(?:"
            + NAMES + "(?:" + SPACE + "+(?i:via)" + SPACE + "+" + NAMES + ")?"
            + "|[\\p{Ll}][\\p{L}]{1,39}" + SPACE + "+(?i:via)" + SPACE + "+" + NAMES + ")";
    private static final String CREDIT_END = "(?=[)\\n\\r]|\\.(?![\\p{L}\\p{N}])|$)";
    private static final Pattern AGENCY_AFTER_SLASH = Pattern.compile(SPACE + "*" + AGENCY + SPACE + "*" + CREDIT_END);
    private static final String PHOTO_NOUN = "(?:фото|фотографии|снимка|иллюстрации)";
    private static final Pattern BYLINE_LABEL = Pattern.compile(SPACE + "*(?iu:"
            + "фото(?:графия|графии|граф)?|снимок|иллюстрация"
            + "|автор(?:ы)?(?:" + SPACE + "+(?:" + PHOTO_NOUN + "|текста|материала))?"
            + "|источник" + SPACE + "+" + PHOTO_NOUN
            + "|photo(?:graph)?s?|photographer|(?:photo|image|illustration|editorial)" + SPACE + "+credits?|authors?|byline"
            + ")(?:" + SPACE + "*[:：]|" + SPACE + "+[—–])" + SPACE + "*");
    private static final Pattern BYLINE_ENTRY = Pattern.compile(SPACE + "*(©" + SPACE + "*)?" + NAMES
            + SPACE + "*(/)" + SPACE + "*" + AGENCY + SPACE + "*" + CREDIT_END);

    static boolean present(String text) {
        return text.indexOf('‐') >= 0 || text.indexOf('‑') >= 0 || text.indexOf('©') >= 0 || text.indexOf('/') >= 0;
    }

    static void add(String text, List<ContextualSpeech.Reading> readings) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '‐' || c == '‑') wordHyphen(text, i, readings);
            if (c == '©') credit(text, i, readings);
            if (c == '/') creditSlash(text, i, readings);
        }
        if (text.indexOf('/') >= 0) labelledCredits(text, readings);
    }

    private static void labelledCredits(String text, List<ContextualSpeech.Reading> readings) {
        // A label can introduce a short run of contiguous bylines. Blank/prose lines end that run.
        int entriesLeft = 0;
        boolean allowBlankAfterLabel = false;
        int start = 0;
        while (start < text.length()) {
            int end = start;
            while (end < text.length() && text.charAt(end) != '\r' && text.charAt(end) != '\n') end++;
            Matcher label = BYLINE_LABEL.matcher(text).region(start, end);
            boolean labelled = label.lookingAt();
            int owner = labelled ? label.end() : start;
            if (labelled) {
                entriesLeft = 4;
                allowBlankAfterLabel = true;
            }
            if (entriesLeft > 0) {
                Matcher entry = BYLINE_ENTRY.matcher(text).region(owner, end);
                if (entry.lookingAt()) {
                    if (entry.start(1) >= 0) {
                        int mark = entry.start(1);
                        readings.add(new ContextualSpeech.Reading(mark, mark + 1, start, entry.end(),
                                "знак авторского права" + (entry.end(1) == mark + 1 ? " " : ""), "publisher-credit"));
                    }
                    int slash = entry.start(2);
                    readings.add(new ContextualSpeech.Reading(slash, slash + 1, start, entry.end(), ", ", "photo-agency-divider"));
                    entriesLeft = entry.end() == end ? entriesLeft - 1 : 0;
                    allowBlankAfterLabel = false;
                } else {
                    while (owner < end && horizontal(text.charAt(owner))) owner++;
                    if (owner != end || !allowBlankAfterLabel) entriesLeft = 0;
                    else if (!labelled) allowBlankAfterLabel = false;
                }
            }
            if (end < text.length() && text.charAt(end) == '\r' && end + 1 < text.length() && text.charAt(end + 1) == '\n') end++;
            start = end + 1;
        }
    }

    private static void wordHyphen(String text, int dash, List<ContextualSpeech.Reading> readings) {
        int left = dash;
        while (left > 0 && Character.isLetter(text.codePointBefore(left)))
            left -= Character.charCount(text.codePointBefore(left));
        int right = dash + 1;
        while (right < text.length() && Character.isLetter(text.codePointAt(right)))
            right += Character.charCount(text.codePointAt(right));
        // Single-letter operands and short all-caps references can be symbolic identifiers.
        if (text.codePointCount(left, dash) < 2 || text.codePointCount(dash + 1, right) < 2) return;
        if (!wordBoundary(text, left, true) || !wordBoundary(text, right, false)
                || CODE_LABEL.matcher(text).region(Math.max(0, left - 64), left).find()) return;
        if (!text.substring(left, right).codePoints().anyMatch(Character::isLowerCase)) return;
        readings.add(new ContextualSpeech.Reading(dash, dash + 1, left, right, " ", "prose-word-hyphen"));
    }

    private static boolean wordBoundary(String text, int offset, boolean left) {
        if (left ? offset == 0 : offset == text.length()) return true;
        int c = left ? text.codePointBefore(offset) : text.codePointAt(offset);
        if (Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK
                || "_/:@#+−-=×*^%".indexOf(c) >= 0) return false;
        // A full stop after a word is punctuation; a dot inside a domain or code is significant.
        if (c == '.') {
            int other = left ? offset - 1 : offset + 1;
            return left ? other == 0 || !Character.isLetterOrDigit(text.codePointBefore(other))
                    : other == text.length() || !Character.isLetterOrDigit(text.codePointAt(other));
        }
        return true;
    }

    private static boolean horizontal(char c) { return c == ' ' || c == '\u00a0' || c == '\u202f' || c == '\t'; }

    private static void creditSlash(String text, int slash, List<ContextualSpeech.Reading> readings) {
        // Keep both sides of a signed caption. An unrelated slash never gains a prose reading.
        Matcher owner = CREDIT_BEFORE_SLASH.matcher(text)
                .region(Math.max(0, slash - 256), slash).useTransparentBounds(true);
        Matcher agency = AGENCY_AFTER_SLASH.matcher(text).region(slash + 1, text.length());
        if (owner.find() && owner.end() == slash && agency.lookingAt()) {
            readings.add(new ContextualSpeech.Reading(slash, slash + 1, owner.start(), agency.end(), ", ", "photo-agency-divider"));
        }
    }

    private static void credit(String text, int mark, List<ContextualSpeech.Reading> readings) {
        int author = mark + 1;
        while (author < text.length() && horizontal(text.charAt(author))) author++;
        if (author == text.length() || !Character.isLetter(text.codePointAt(author))) return;

        int before = mark;
        while (before > 0 && horizontal(text.charAt(before - 1))) before--;
        boolean slash = before > 0 && text.charAt(before - 1) == '/';
        if (slash) {
            // An editorial separator, e.g. “caption / ©Publisher”. Never consume an operand, path or fraction.
            int separator = before - 1;
            if (separator == 0 || !horizontal(text.charAt(separator - 1))) return;
            int captionEnd = separator;
            while (captionEnd > 0 && horizontal(text.charAt(captionEnd - 1))) captionEnd--;
            if (captionEnd == 0 || !Character.isLetter(text.codePointBefore(captionEnd))) return;
        } else if (before > 0 && text.charAt(before - 1) != '\n' && text.charAt(before - 1) != '\r') {
            // Without the caption slash, require a byline starting its own line.
            return;
        }
        int start = slash ? before - 1 : mark;
        String spoken = (slash ? ", " : "") + "знак авторского права" + (author == mark + 1 ? " " : "");
        readings.add(new ContextualSpeech.Reading(start, mark + 1, start, author + Character.charCount(text.codePointAt(author)),
                spoken, "publisher-credit"));
    }
}

package ru.bububu.radio.sources;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.PlayerController;

/** Spoken-only treatment of decorations in a verified public post. Offsets address the original text. */
public final class TelegramSpeech {
    private static final Pattern POST = Pattern.compile("https://t\\.me/([A-Za-z0-9_]{1,64})/[1-9][0-9]{0,19}");
    private static final Pattern PROSE = Pattern.compile("[«“„\"]?(\\p{L}+)[\\p{Zs}\\t]+(\\p{L}+)[\\p{Zs}\\t]+(\\p{L}+)(?![\\p{L}\\p{M}\\p{N}_+/=*^])");
    private static final String NEWS_MARKERS = "✔⚽⚖🗣🚨📌❗🛢";
    private static final String[] DIGITS = {"ноль","один","два","три","четыре","пять","шесть","семь","восемь","девять"};

    private TelegramSpeech() { }

    public static List<ContextualSpeech.Reading> readings(PlayerController.SpeechSentence owner) {
        Article article = owner.article;
        SourceVersionRecord version = article.sourceVersion;
        if (version == null || !"public_preview_text".equals(version.method)
                || !TelegramPreviewAdapter.VERSION.equals(ChannelProfile.parser(version.adapterVersion))
                || version.url == null) return Collections.emptyList();
        Matcher post = POST.matcher(version.url);
        if (!post.matches()) return Collections.emptyList();
        String username = post.group(1), text = owner.text();
        // Article.Sentence.text trims its stored slice. Map local readings to the
        // first actual UTF-16 unit even for reviewed boundaries preceding whitespace.
        int base = article.sentences.get(owner.index).start;
        while (article.text.charAt(base) <= ' ') base++;
        List<ContextualSpeech.Reading> result = TelegramPostLines.readings(article, base, base + text.length());
        for (int line = 0; line < text.length();) {
            int newline = text.indexOf('\n', line), end = newline < 0 ? text.length() : newline;
            int lo = line, hi = end;
            while (lo < hi && Character.isWhitespace(text.charAt(lo))) lo++;
            while (hi > lo && Character.isWhitespace(text.charAt(hi-1))) hi--;
            // TextSegments can start a sentence mid-line. Require a true original line boundary.
            boolean onNewLine = base+line == 0 || article.text.charAt(base+line-1) == '\n';
            if (onNewLine && lo < hi) {
                boolean ownHandle = hi-lo == username.length()+1 && text.charAt(lo)=='@'
                        && text.regionMatches(true,lo+1,username,0,username.length());
                boolean sourceHeading = base+lo==0 && version.sourceLabel.equalsIgnoreCase("@"+username);
                boolean finalSignature = base+lo>=version.bodyStart && base+hi==article.text.length();
                if (ownHandle && (sourceHeading || finalSignature)) {
                    // Retain the account identity: "_" is distinct from no underscore.
                    StringBuilder spoken = new StringBuilder("канал");
                    for (int j=lo+1;j<hi;j++) {
                        char c=text.charAt(j);
                        spoken.append(' ').append(c=='_' ? "подчёркивание" : c>='0'&&c<='9' ? DIGITS[c-'0'] : String.valueOf(c));
                    }
                    result.add(new ContextualSpeech.Reading(lo,hi,lo,hi,spoken.toString(),"telegram-own-handle"));
                } else if (base+lo>=version.bodyStart) {
                    int markers = prefixEnd(text, lo, hi);
                    if (markers > lo) {
                        result.add(new ContextualSpeech.Reading(lo,markers,lo,markers," ","telegram-line-marker"));
                    }
                }
            }
            line = end+1;
        }
        result.sort(java.util.Comparator.comparingInt(r -> r.start));
        return result;
    }
    private static int prefixEnd(String text, int start, int limit) {
        int cursor = start, lastMarker = start;
        while (cursor < limit) {
            int end = clusterEnd(text, cursor, limit, true);
            if (end == cursor) break;
            lastMarker = end;
            cursor = afterSpaces(text, end, limit);
        }
        if (lastMarker > start && cursor < limit) {
            if (text.charAt(cursor)>='0' && text.charAt(cursor)<='9') {
                while (cursor<limit && text.charAt(cursor)>='0' && text.charAt(cursor)<='9') cursor++;
                if (cursor >= limit || !Character.isWhitespace(text.charAt(cursor))) return start;
                cursor = afterSpaces(text, cursor, limit);
            }
            if (cursor<limit && Character.isLetter(text.codePointAt(cursor))) return lastMarker;
        }

        // A new pictograph can introduce a news paragraph, but is also content in short
        // reactions, ratings or formulae. Require one complete, space-separated cluster
        // followed by prose. Keep the original meaning undecided outside this layout.
        int icon = clusterEnd(text, start, limit, false);
        int words = afterSpaces(text, icon, limit);
        if (icon == start || words == icon || words >= limit) return start;
        Matcher prose = PROSE.matcher(text).region(words,limit);
        if (!prose.lookingAt()) return start;
        int substantive = 0;
        for (int i=1; i<=3; i++) {
            String word = prose.group(i);
            if (word.codePointCount(0,word.length()) >= 2) substantive++;
        }
        return substantive >= 2 ? icon : start;
    }

    private static int afterSpaces(String text, int at, int limit) {
        while (at<limit && Character.isWhitespace(text.charAt(at))) at++;
        return at;
    }

    /** Only a complete supported cluster can disappear; never consume part of a flag or ZWJ sequence. */
    private static int clusterEnd(String text, int start, int limit, boolean knownOnly) {
        int cursor = start;
        while (cursor < limit) {
            int cp = text.codePointAt(cursor);
            int next = cursor + Character.charCount(cp);
            if (next<limit && text.charAt(next)=='\uFE0E') return start; // explicit text presentation
            boolean emojiStyle = next<limit && text.charAt(next)=='\uFE0F';
            if (emojiStyle) next++;
            if (next<limit) {
                int modifier = text.codePointAt(next);
                if (modifier>=0x1F3FB && modifier<=0x1F3FF) {
                    if (!TelegramEmoji.takesModifier(cp)) return start;
                    emojiStyle=true; // A valid modifier sequence presents as emoji even without VS16.
                    next+=Character.charCount(modifier);
                }
            }
            boolean joined = cursor>start || next<limit && text.charAt(next)=='\u200D';
            if (!marker(cp) && (knownOnly || !TelegramEmoji.pictograph(cp,emojiStyle,joined))) return start;
            cursor=next;
            if (cursor==limit || text.charAt(cursor)!='\u200D') return cursor;
            cursor++;
        }
        return start;
    }

    private static boolean marker(int cp) {
        return cp == 0x2022 || cp == 0x25AA || cp == 0x25AB || cp == 0x25CF || cp == 0x25CB
                || cp == 0x25A0 || cp == 0x25A1 || cp == 0x25B6 || cp == 0x25C0
                || cp >= 0x25FB && cp <= 0x25FE             // monochrome squares
                || cp >= 0x1F534 && cp <= 0x1F53B           // circles, diamonds, triangles
                || cp >= 0x1F7E0 && cp <= 0x1F7EB           // coloured circles and squares
                || NEWS_MARKERS.indexOf(cp) >= 0;
    }

}

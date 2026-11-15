package ru.bububu.radio.sources;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** News expressions whose immediate context supplies the otherwise missing meaning of a number. */
final class SourceNewsNumbers {
    private static final String S = "[ \\u00a0\\u202f]";
    private static final String LEFT = "(?<![\\p{L}\\p{M}\\p{N}_+#@./:$€₽=−–—-])";
    private static final String RIGHT = "(?![\\p{L}\\p{M}\\p{N}_/#:+$€₽=−–—-])(?!\\.[0-9])";
    private static final int RU = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern QUARTER = Pattern.compile(LEFT + "(в|во|к|ко|до|от|после)" + S
            + "+((?-i:IV|III|II|I))" + S + "+(квартале|кварталу|квартала)" + RIGHT, RU);
    private static final Pattern SEASON_YEAR = Pattern.compile(LEFT + "(?:летом|осенью|зимой|весной|на" + S
            + "+(?:весну|лето|осень|зиму))" + S + "+((?:19|20)[0-9]{2})" + RIGHT, RU);
    // A following noun could count units ("летом 2027 автомобилей"). Only explicit event predicates qualify.
    private static final Pattern EVENT = Pattern.compile(S + "+(?:планируется|планируются|ожидается|ожидаются|выйдет|выйдут"
            + "|появится|появятся|начн[её]тся|начнутся|откроется|откроются|состоится|состоятся|стартует|стартуют)"
            + "(?![\\p{L}\\p{M}\\p{N}_])", RU);
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?]?" + S + "*\\z");
    private static final Pattern CALENDAR_RANGE = Pattern.compile(LEFT + "(?:с|со)" + S + "+([1-9][0-9]{3})"
            + S + "+по" + S + "+([1-9][0-9]{3})" + S + "+год" + RIGHT, RU);
    private static final String ENDPOINT = "(0|[1-9][0-9]{0,2})";
    private static final Pattern MONEY = Pattern.compile(LEFT + "([$€₽])" + S + "*" + ENDPOINT + S + "*[-–—]" + S + "*"
            + ENDPOINT + S + "+(миллион(?:ов|а)?|миллиард(?:ов|а)?|тысяч(?:а|и)?|млн|млрд|тыс)" + RIGHT, RU);
    private static final Pattern NEXT_CURRENCY = Pattern.compile("(?:\\." + S + "*|" + S + "+)(?:доллар[а-яё]*|руб(?:л[а-яё]*)?|евро|usd|eur|rub)(?![\\p{L}])", RU);
    private static final Set<String> INCOMPATIBLE = Set.of("в", "во", "к", "ко", "на", "до", "от", "с", "со",
            "из", "без", "по", "за", "между", "около", "свыше", "порядка", "менее", "более");
    private static final Set<String> CODE_LABELS = Set.of("код", "артикул", "версия", "модель", "номер", "индекс", "идентификатор", "id", "sku");

    private SourceNewsNumbers() { }

    static void add(String text, List<ContextualSpeech.Reading> readings) {
        if (text.indexOf('1') >= 0 || text.indexOf('2') >= 0) addSeasonYears(text, readings);
        addCalendarRanges(text, readings);
        if (text.indexOf('I') >= 0) {
            Matcher quarter = QUARTER.matcher(text);
            while (quarter.find()) {
                String prep = quarter.group(1).toLowerCase(Locale.ROOT);
                String noun = quarter.group(3).toLowerCase(Locale.ROOT);
                String gen = SourceWords45.ordinal(roman(quarter.group(2)));
                String spoken;
                if ((prep.equals("в") || prep.equals("во")) && noun.equals("квартале"))
                    spoken = oblique(gen, "ом", "ем");
                else if ((prep.equals("к") || prep.equals("ко")) && noun.equals("кварталу"))
                    spoken = oblique(gen, "ому", "ему");
                else if ((prep.equals("от") || prep.equals("до") || prep.equals("после")) && noun.equals("квартала"))
                    spoken = gen;
                else continue;
                readings.add(new ContextualSpeech.Reading(quarter.start(2), quarter.end(2), quarter.start(),
                        quarter.end(), spoken, "roman-quarter"));
            }
        }

        if (text.indexOf('$') < 0 && text.indexOf('€') < 0 && text.indexOf('₽') < 0) return;
        Matcher range = MONEY.matcher(text);
        while (range.find()) {
            int low = Integer.parseInt(range.group(2)), high = Integer.parseInt(range.group(3));
            if (low > high || incompatibleLeft(text, range.start()) || codeLabelBefore(text, range.start())
                    || NEXT_CURRENCY.matcher(text).region(range.end(), text.length()).lookingAt()) continue;
            String scale = range.group(4).toLowerCase(Locale.ROOT);
            String currency = range.group(1).equals("$") ? "долларов" : range.group(1).equals("€") ? "евро" : "рублей";
            // Each endpoint has the stated scale; "$20-25 миллионов" is not twenty dollars.
            String spoken = "от " + scaledGenitive(low, scale) + " до " + scaledGenitive(high, scale) + " " + currency;
            readings.add(new ContextualSpeech.Reading(range.start(), range.end(), range.start(),
                    range.end(), spoken, "scaled-currency-range"));
        }
    }

    private static void addCalendarRanges(String text, List<ContextualSpeech.Reading> readings) {
        Matcher range = CALENDAR_RANGE.matcher(text);
        while (range.find()) {
            int first = Integer.parseInt(range.group(1)), last = Integer.parseInt(range.group(2));
            if (first > last || incompatibleLeft(text, range.start()) || codeLabelBefore(text, range.start())) continue;
            readings.add(new ContextualSpeech.Reading(range.start(1), range.end(1), range.start(), range.end(),
                    SourceWords45.ordinal(first) + " г+ода", "calendar-range-start"));
            // "по ... год" takes the inanimate accusative, identical in form to the masculine nominative.
            readings.add(new ContextualSpeech.Reading(range.start(2), range.end(2), range.start(), range.end(),
                    masculineOrdinal(last), "calendar-range-end"));
        }
    }

    private static String masculineOrdinal(int year) {
        String gen = SourceWords45.ordinal(year);
        if (gen.endsWith("третьего")) return gen.substring(0, gen.length() - "третьего".length()) + "третий";
        boolean stressed = gen.endsWith("второго") || gen.endsWith("шестого") || gen.endsWith("седьмого")
                || gen.endsWith("восьмого") || gen.endsWith("сорокового");
        return gen.substring(0, gen.length() - 3) + (stressed ? "ой" : "ый");
    }

    private static void addSeasonYears(String text, List<ContextualSpeech.Reading> readings) {
        Matcher year = SEASON_YEAR.matcher(text);
        while (year.find()) {
            int before = year.start();
            while (before > 0 && Character.isSpaceChar(text.charAt(before - 1)) && text.charAt(before - 1) != '\n') before--;
            if (before > 0 && ".!?;\n".indexOf(text.charAt(before - 1)) < 0) continue;
            Matcher event = EVENT.matcher(text).region(year.end(1), text.length());
            int contextEnd;
            if (event.lookingAt()) contextEnd = event.end();
            else if (SENTENCE_END.matcher(text).region(year.end(1), text.length()).matches()) contextEnd = text.length();
            else continue;
            String spoken = SourceWords45.ordinal(Integer.parseInt(year.group(1))) + " г+ода";
            readings.add(new ContextualSpeech.Reading(year.start(1), year.end(1), year.start(), contextEnd, spoken, "season-year"));
        }
    }
    private static int roman(String token) {
        if (token.equals("IV")) return 4;
        return token.length();
    }

    private static String oblique(String genitive, String hard, String soft) {
        return genitive.substring(0, genitive.length() - 3) + (genitive.endsWith("его") ? soft : hard);
    }

    private static boolean incompatibleLeft(String text, int start) {
        int end = start;
        while (end > 0 && Character.isSpaceChar(text.charAt(end - 1))) end--;
        int begin = end;
        while (begin > 0 && Character.isLetter(text.charAt(begin - 1))) begin--;
        if (begin == end) return false;
        String word = text.substring(begin, end);
        return INCOMPATIBLE.contains(word.toLowerCase(Locale.ROOT));
    }
    static boolean codeLabelBefore(String text, int start) {
        int end = start;
        while (end > 0) {
            char ch = text.charAt(end - 1);
            if (!Character.isSpaceChar(ch) && ":«“\"".indexOf(ch) < 0) break;
            end--;
        }
        int begin = end;
        while (begin > 0 && Character.isLetter(text.charAt(begin - 1))) begin--;
        return begin < end && CODE_LABELS.contains(text.substring(begin, end).toLowerCase(Locale.ROOT));
    }

    private static String scaledGenitive(int n, String scale) {
        boolean singular = n % 10 == 1 && n % 100 != 11;
        String words = SourceWords45.genitive(n);
        if (scale.startsWith("тыс")) {
            if (singular) words = words.substring(0, words.length() - "одного".length()) + "одной";
            return words + (singular ? " тысячи" : " тысяч");
        }
        boolean millions = scale.equals("млн") || scale.startsWith("миллион");
        return words + (millions ? (singular ? " миллиона" : " миллионов")
                                 : (singular ? " миллиарда" : " миллиардов"));
    }
}

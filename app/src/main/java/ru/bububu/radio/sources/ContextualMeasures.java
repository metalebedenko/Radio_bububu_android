package ru.bububu.radio.sources;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Number and explicit unit in the same expression. No inference from a subject or previous quantity. */
final class ContextualMeasures {
    private enum Case { COUNTING, GENITIVE, DATIVE }

    private static final class Unit {
        final String one, few, many, dativeOne, dativeMany, qualifier;
        final boolean feminine;

        Unit(String one, String few, String many, String dativeOne, String dativeMany,
             boolean feminine, String qualifier) {
            this.one = one;
            this.few = few;
            this.many = many;
            this.dativeOne = dativeOne;
            this.dativeMany = dativeMany;
            this.feminine = feminine;
            this.qualifier = qualifier;
        }

        String noun(long n, boolean fraction, Case form) {
            String word;
            if (fraction) word = few; // A fraction governs the singular genitive of the unit.
            else if (form == Case.DATIVE) word = singular(n) ? dativeOne : dativeMany;
            else if (form == Case.GENITIVE) word = singular(n) ? few : many;
            else if (singular(n)) word = one;
            else if (n % 10 >= 2 && n % 10 <= 4 && n % 100 / 10 != 1) word = few;
            else word = many;
            return word + qualifier;
        }
    }

    private static final Map<String, Unit> UNITS = new HashMap<>();
    private static final String SPACE = "[ \\u00a0\\u202f]";
    private static final String CURRENCY = "[$€₽]";
    private static final String INTEGER = "(?:[1-9][0-9]{0,2}(?:" + SPACE + "[0-9]{3}){1,2}|0|[1-9][0-9]{0,8})";
    private static final String NUMBER = "[+−-]?" + INTEGER + "(?:[.,][0-9]{1,6})?";
    private static final Pattern QUANTITY;
    private static final Pattern HISTORICAL_ERA = Pattern.compile("[ \\u00a0\\u202f]+(?:до[ \\u00a0\\u202f]+)?н\\.?[ \\u00a0\\u202f]*э(?:\\.|\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final String[] DENOMINATORS = {"", "десятая", "сотая", "тысячная", "десятитысячная", "стотысячная", "миллионная"};
    private static final Unit MILLION = new Unit("миллион", "миллиона", "миллионов", "", "", false, "");
    private static final Unit THOUSAND = new Unit("тысяча", "тысячи", "тысяч", "", "", true, "");
    private static final Unit BILLION = new Unit("миллиард", "миллиарда", "миллиардов", "", "", false, "");
    private static final Unit TRILLION = new Unit("триллион", "триллиона", "триллионов", "", "", false, "");
    private static final String WRITTEN_UNIT = "(?:(?:метрических" + SPACE + "+)?тонн|"
            + "(?:(?:квадратных|кубических)" + SPACE + "+)?(?:метров|километров)|килограммов|акров|гектаров|литров|баррелей|рублей|долларов|евро)";
    // The comma must sit between digits; the scale and its unit must be in the same original sentence.
    private static final Pattern SCALED = Pattern.compile("([+−-]?" + INTEGER + ",[0-9]{1,6})" + SPACE
            + "+(тысячи|миллиона|миллиарда|триллиона|тыс|млн|млрд|трлн)" + SPACE + "+" + WRITTEN_UNIT,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Set<String> COUNTING_PREDICATES = Set.of("составил", "составила", "составило", "составили",
            "составляет", "составляют", "составит", "составят", "занимает", "занимают", "заняла", "заняли",
            "утрачены", "утрачено", "потеряно", "выбросили", "накопили", "получили", "получено", "собрали", "собрано");

    static {
        registerRegular("километр", "км", "km");
        registerRegular("метр", "м", "m");
        registerRegular("сантиметр", "см", "cm");
        registerRegular("миллиметр", "мм", "mm");
        registerRegular("миллиграмм", "мг", "mg");
        registerRegular("грамм", "г", "g");
        registerRegular("килограмм", "кг", "kg");
        register(new Unit("тонна", "тонны", "тонн", "тонне", "тоннам", true, ""), "т");
        registerRegular("литр", "л");
        registerRegular("миллилитр", "мл");
        // Bare «Б»/«B» also denote class letters, not necessarily bytes.
        registerRegular("килобайт", "кБ", "КБ", "kB", "KB");
        registerRegular("мегабайт", "МБ", "MB");
        registerRegular("гигабайт", "ГБ", "GB");
        registerRegular("терабайт", "ТБ", "TB");
        registerRegular("кибибайт", "KiB", "КиБ");
        registerRegular("мебибайт", "MiB", "МиБ");
        registerRegular("гибибайт", "GiB", "ГиБ");
        registerRegular("тебибайт", "TiB", "ТиБ");
        register(new Unit("рубль", "рубля", "рублей", "рублю", "рублям", false, ""), "₽");
        register(new Unit("евро", "евро", "евро", "евро", "евро", false, ""), "€");
        registerRegular("доллар", "$");
        register(new Unit("градус", "градуса", "градусов", "градусу", "градусам", false, " Цельсия"), "°C", "°С");

        String symbols = UNITS.keySet().stream().sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .map(Pattern::quote).collect(Collectors.joining("|"));
        QUANTITY = Pattern.compile("(?:(" + CURRENCY + ")" + SPACE + "*(" + NUMBER + ")|(" + NUMBER + ")(" + SPACE + "*)(" + symbols + "))");
    }

    private static void register(Unit unit, String... symbols) {
        for (String symbol : symbols) UNITS.put(symbol, unit);
    }

    private static void registerRegular(String noun, String... symbols) {
        register(new Unit(noun, noun + "а", noun + "ов", noun + "у", noun + "ам", false, ""), symbols);
    }

    static void add(String text, List<ContextualSpeech.Reading> candidates) {
        if (text.indexOf(',') >= 0) {
            Matcher scaled = SCALED.matcher(text);
            while (scaled.find()) {
                if (!standalone(text, scaled.start(), scaled.end()) || SourceNewsNumbers.codeLabelBefore(text, scaled.start())) continue;
                String multiplier = scaled.group(2).toLowerCase(Locale.ROOT);
                Unit unit = multiplier.startsWith("тыс") ? THOUSAND
                        : multiplier.equals("млн") || multiplier.startsWith("миллион") ? MILLION
                        : multiplier.equals("млрд") || multiplier.startsWith("миллиард") ? BILLION : TRILLION;
                // Only the numeral and scale are projected. "метрических тонн", for example, stays literal.
                addReading(text, scaled.start(), scaled.end(2), scaled.end(), scaled.group(1), unit, true, candidates);
            }
        }
        Matcher m = QUANTITY.matcher(text);
        while (m.find()) {
            boolean prefix = m.group(1) != null;
            String symbol = prefix ? m.group(1) : m.group(5);
            String number = prefix ? m.group(2) : m.group(3);
            if (!prefix && m.group(4).isEmpty() && !symbol.startsWith("°") && !isCurrency(symbol)) continue;
            if (!standalone(text, m.start(), m.end())) continue;
            if (symbol.length() == 1 && !isCurrency(symbol) && text.startsWith(".", m.end())) continue;
            if (symbol.equals("г") && (number.matches("[12][0-9]{3}")
                    || HISTORICAL_ERA.matcher(text).region(m.end(), text.length()).lookingAt())) continue;
            if (prefix && symbol.equals("$") && number.indexOf('.') >= 0) continue;

            addReading(text, m.start(), m.end(), m.end(), number, UNITS.get(symbol), false, candidates);
        }
    }

    private static void addReading(String text, int start, int end, int contextEnd, String number, Unit unit,
                                   boolean scaled, List<ContextualSpeech.Reading> candidates) {
        int previousEnd = skipLeft(text, start), previousStart = previousEnd;
        while (previousStart > 0 && Character.isLetter(text.charAt(previousStart - 1))) previousStart--;
        String previous = text.substring(previousStart, previousEnd).toLowerCase(Locale.ROOT);
        Case form;
        switch (previous) {
            case "до": case "от": case "около": case "более": case "менее": case "больше":
            case "меньше": case "свыше": case "без": case "из": case "для":
            case "после": case "вместо": case "порядка":
                form = Case.GENITIVE; break;
            case "к": case "ко":
                form = Case.DATIVE; break;
            case "в": case "во": case "на": case "с": case "со": case "по": case "о": case "об":
            case "при": case "под": case "над": case "между": case "перед": case "за":
            case "код": case "модель": case "артикул": case "индекс": case "sku": case "id": case "номер":
                return;
            default:
                if (scaled && previous.equals("выбросам")) form = Case.GENITIVE;
                else {
                    if (scaled && !previous.isEmpty() && !COUNTING_PREDICATES.contains(previous)) return;
                    form = Case.COUNTING;
                }
        }
        String spoken = speak(number, unit, form);
        if (spoken != null) candidates.add(new ContextualSpeech.Reading(start, end,
                form == Case.COUNTING && !scaled ? start : previousStart, contextEnd,
                spoken, scaled ? "scaled-decimal-measure" : "measure"));
    }

    private static int skipLeft(String s, int end) {
        while (end > 0 && (Character.isWhitespace(s.charAt(end - 1)) || Character.isSpaceChar(s.charAt(end - 1)))) end--;
        return end;
    }

    private static boolean isCurrency(String s) { return s.equals("$") || s.equals("€") || s.equals("₽"); }

    private static boolean joined(char c) {
        int type = Character.getType(c);
        return Character.isLetterOrDigit(c) || type == Character.NON_SPACING_MARK || type == Character.OTHER_NUMBER
                || type == Character.CURRENCY_SYMBOL || "_/\\%°+−-–—=×*^·±<>#№".indexOf(c) >= 0;
    }

    private static boolean standalone(String s, int start, int end) {
        if (start > 0 && (joined(s.charAt(start - 1)) || ".,:".indexOf(s.charAt(start - 1)) >= 0)) return false;
        if (end < s.length() && (joined(s.charAt(end)) || end + 1 < s.length()
                && (s.charAt(end) == '.' && Character.isLetterOrDigit(s.charAt(end + 1))
                        || s.charAt(end) == ',' && Character.isDigit(s.charAt(end + 1))))) return false;
        int left = skipLeft(s, start) - 1;
        if (left >= 0) {
            char before = s.charAt(left);
            if (Character.isDigit(before) || "/\\+−-=×*^·±<>#№".indexOf(before) >= 0) return false;
            if ("–—,.".indexOf(before) >= 0) {
                int prior = skipLeft(s, left) - 1;
                if (prior >= 0 && Character.isDigit(s.charAt(prior))) return false;
            }
        }
        int right = end;
        while (right < s.length() && (Character.isWhitespace(s.charAt(right)) || Character.isSpaceChar(s.charAt(right)))) right++;
        if (right < s.length() && ("/\\+−-=×*^·±<>°".indexOf(s.charAt(right)) >= 0
                || Character.getType(s.charAt(right)) == Character.CURRENCY_SYMBOL)) return false;
        int wordEnd = right;
        while (wordEnd < s.length() && Character.isLetter(s.charAt(wordEnd))) wordEnd++;
        String nextWord = s.substring(right, wordEnd);
        if (UNITS.containsKey(nextWord)) return false;
        String next = nextWord.toLowerCase(Locale.ROOT);
        return !next.equals("млн") && !next.equals("млрд") && !next.equals("трлн") && !next.equals("тыс")
                && !next.startsWith("тысяч") && !next.startsWith("миллион") && !next.startsWith("миллиард");
    }

    private static boolean singular(long n) { return n % 10 == 1 && n % 100 != 11; }

    private static String cardinal(int n) {
        if (n < 10000) return SourceWords45.cardinal(n);
        int millions = n / 1000000, thousands = n / 1000 % 1000;
        StringBuilder out = new StringBuilder();
        if (millions > 0) out.append(SourceWords45.cardinal(millions)).append(' ')
                .append(MILLION.noun(millions, false, Case.COUNTING));
        if (thousands > 0) {
            if (out.length() > 0) out.append(' ');
            out.append(feminize(SourceWords45.cardinal(thousands), thousands, Case.COUNTING)).append(' ')
                    .append(THOUSAND.noun(thousands, false, Case.COUNTING));
        }
        if (n % 1000 > 0) {
            if (out.length() > 0) out.append(' ');
            out.append(SourceWords45.cardinal(n % 1000));
        }
        return out.toString();
    }

    private static String feminize(String words, int n, Case form) {
        if (singular(n)) {
            String ending = form == Case.COUNTING ? "один" : form == Case.GENITIVE ? "одного" : "одному";
            return words.substring(0, words.length() - ending.length()) + (form == Case.COUNTING ? "одна" : "одной");
        }
        if (form == Case.COUNTING && n % 10 == 2 && n % 100 != 12)
            return words.substring(0, words.length() - 3) + "две";
        return words;
    }

    private static String numeral(int n, Case form, boolean feminine) {
        String value = form == Case.COUNTING ? cardinal(n) : form == Case.GENITIVE ? SourceWords45.genitive(n) : SourceWords45.dative(n);
        return feminine ? feminize(value, n, form) : value;
    }

    private static String speak(String raw, Unit unit, Case form) {
        String number = raw.replace(" ", "").replace("\u00a0", "").replace("\u202f", "");
        String sign = "";
        if ("+−-".indexOf(number.charAt(0)) >= 0) {
            sign = number.charAt(0) == '+' ? "плюс " : "минус ";
            number = number.substring(1);
        }
        String[] parts = number.split("[.,]", 2);
        int whole = Integer.parseInt(parts[0]);
        boolean decimal = parts.length == 2;
        int fraction = decimal ? Integer.parseInt(parts[1]) : 0;
        if (form != Case.COUNTING && (whole > 9999 || fraction > 9999)) return null;
        String value = numeral(whole, form, decimal || unit.feminine);
        if (decimal) {
            String ending = DENOMINATORS[parts[1].length()];
            boolean one = singular(fraction);
            if (!one || form != Case.COUNTING) {
                String suffix = one ? "ой" : form == Case.DATIVE ? "ым" : "ых";
                ending = ending.substring(0, ending.length() - 2) + suffix;
            }
            String wholeNoun = form == Case.DATIVE ? (singular(whole) ? " целой " : " целым ")
                    : singular(whole) ? (form == Case.GENITIVE ? " целой " : " целая ") : " целых ";
            value += wholeNoun + numeral(fraction, form, true) + " " + ending;
        }
        return sign + value + " " + unit.noun(whole, decimal, form);
    }
}

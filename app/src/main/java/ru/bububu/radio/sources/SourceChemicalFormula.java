package ru.bububu.radio.sources;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Literal element/index reading. No conversion to a substance name or formula evaluation. */
final class SourceChemicalFormula {
    private SourceChemicalFormula() {}
    private static final Map<String, String> ELEMENTS = Map.ofEntries(
            Map.entry("C", "цэ"), Map.entry("O", "о"), Map.entry("H", "аш"), Map.entry("N", "эн"),
            Map.entry("P", "пэ"), Map.entry("S", "эс"), Map.entry("F", "эф"), Map.entry("K", "калий"),
            Map.entry("Na", "натрий"), Map.entry("Cl", "хлор"), Map.entry("Ca", "кальций"),
            Map.entry("Mg", "магний"), Map.entry("Al", "алюминий"), Map.entry("Fe", "железо"),
            Map.entry("Cu", "медь"), Map.entry("Zn", "цинк"));
    private static final Set<String> COMMON = Set.of("CO₂", "H₂O", "O₂", "N₂", "NH₃", "CH₄", "SO₂", "NO₂", "H₂O₂", "H₂");
    private static final Pattern TOKENS = Pattern.compile("[\\p{L}\\p{M}\\p{N}_]+");
    private static final Pattern ELEMENT = Pattern.compile("([A-Z][a-z]?)([₀-₉]{0,2})");
    private static final Pattern CHEMISTRY = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(?:химическая формула|формул[аыеу]|молекул[аыу])"
            + "(?:[ \\u00a0\\u202f]+(?:соединения|вещества|газа))?[ \\u00a0\\u202f]*:?[ \\u00a0\\u202f]*$");
    private static final Pattern OPAQUE = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(?:код|артикул|модель|версия|переменная|индекс|sku|id)"
            + "[ \\t:«“\\u00a0\\u202f]*$");

    static boolean hasIndex(String text) { return text.chars().anyMatch(c -> c >= '₀' && c <= '₉'); }

    static void add(String text, List<ContextualSpeech.Reading> readings) {
        Matcher token = TOKENS.matcher(text);
        while (token.find()) {
            if (token.end() - token.start() > 32 || !hasIndex(token.group())) continue;
            if (joined(text, token.start(), true) || joined(text, token.end(), false)) continue;
            int lookback = Math.max(0, token.start() - 100);
            if (OPAQUE.matcher(text).region(lookback, token.start()).find()) continue;
            Matcher cue = CHEMISTRY.matcher(text).region(lookback, token.start());
            boolean explicit = cue.find();
            if (!explicit && !COMMON.contains(token.group())) continue;
            String words = spell(token.group());
            if (words != null) readings.add(new ContextualSpeech.Reading(token.start(), token.end(),
                    explicit ? cue.start() : token.start(), token.end(), words, "chemical-formula"));
        }
    }

    private static boolean joined(String text, int edge, boolean before) {
        if (before ? edge == 0 : edge == text.length()) return false;
        int c = before ? text.codePointBefore(edge) : text.codePointAt(edge);
        if ("_/#@^~+±−-–—=×·⁺⁻".indexOf(c) >= 0) return true;
        if (c != '.') return false;
        int other = before ? edge - 1 : edge + 1;
        return before ? other > 0 && Character.isLetterOrDigit(text.codePointBefore(other))
                : other < text.length() && Character.isLetterOrDigit(text.codePointAt(other));
    }

    private static String spell(String formula) {
        Matcher group = ELEMENT.matcher(formula);
        StringBuilder out = new StringBuilder();
        int cursor = 0, groups = 0;
        while (cursor < formula.length()) {
            group.region(cursor, formula.length());
            if (++groups > 8 || !group.lookingAt()) return null;
            String element = ELEMENTS.get(group.group(1)), index = group.group(2);
            if (element == null || index.startsWith("₀")) return null;
            if (out.length() != 0) out.append(' ');
            out.append(element);
            if (!index.isEmpty()) {
                int count = 0;
                for (int i = 0; i < index.length(); i++) count = 10 * count + index.charAt(i) - '₀';
                out.append(' ').append(SourceWords45.cardinal(count));
            }
            cursor = group.end();
        }
        return out.toString();
    }
}

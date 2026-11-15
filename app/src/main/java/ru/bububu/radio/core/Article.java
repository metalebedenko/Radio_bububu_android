package ru.bububu.radio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** An immutable displayed/spoken text version. Offsets are UTF-16 indices in text. */
public final class Article {
    public final String id;
    public final String version;
    public final String title;
    public final String text;
    public final List<Integer> sentenceStarts;
    public final List<Sentence> sentences;
    public final ru.bububu.radio.sources.SourceVersionRecord sourceVersion;

    public Article(String id, String version, String title, String text) {
        this(id, version, title, text, TextSegments.sentenceStarts(text));
    }

    public Article(String id, String version, String title, String text, List<Integer> starts) {
        this(id,version,title,text,starts,null);
    }
    public Article(String id,String version,String title,String text,List<Integer> starts,ru.bububu.radio.sources.SourceVersionRecord sourceVersion) {
        this.sourceVersion=sourceVersion;
        this.id = requireValue(id, "id");
        this.version = requireValue(version, "version");
        this.title = Objects.requireNonNull(title, "title");
        this.text = requireValue(text, "text");
        Objects.requireNonNull(starts, "sentenceStarts");
        if (starts.isEmpty() || starts.get(0) != 0) {
            throw new IllegalArgumentException("The first sentence must start at offset 0");
        }
        ArrayList<Integer> checked = new ArrayList<>(starts);
        ArrayList<Sentence> units = new ArrayList<>();
        int previous = -1;
        for (int i = 0; i < checked.size(); i++) {
            int start = checked.get(i);
            int end = i + 1 < checked.size() ? checked.get(i + 1) : text.length();
            if (start <= previous || start < 0 || start >= text.length() || end <= start
                    || end > text.length() || (start > 0 && Character.isLowSurrogate(text.charAt(start)))) {
                throw new IllegalArgumentException("Invalid sentence offset: " + start);
            }
            String spoken = text.substring(start, end).trim();
            if (spoken.isEmpty()) throw new IllegalArgumentException("Empty sentence");
            units.add(new Sentence(start, end, spoken));
            previous = start;
        }
        sentenceStarts = Collections.unmodifiableList(checked);
        sentences = Collections.unmodifiableList(units);
    }

    /** Useful for reviewed local fixtures whose sentence boundaries are known in advance. */
    public static Article fromSentences(String id, String version, String title, String... sentences) {
        StringBuilder text = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        for (String sentence : sentences) {
            if (text.length() > 0) text.append(' ');
            offsets.add(text.length());
            text.append(requireValue(sentence, "sentence").trim());
        }
        return new Article(id, version, title, text.toString(), offsets);
    }

    public int nearestSentence(double fraction) {
        if (!Double.isFinite(fraction)) throw new IllegalArgumentException("Non-finite position");
        if (fraction >= 1) return sentences.size() - 1;
        double target = Math.max(0, fraction) * text.length();
        int nearest = 0;
        double distance = Math.abs(target - sentenceStarts.get(0));
        for (int i = 1; i < sentenceStarts.size(); i++) {
            double candidate = Math.abs(target - sentenceStarts.get(i));
            if (candidate < distance - 1e-9) { // Equal distances deliberately keep the earlier anchor.
                nearest = i;
                distance = candidate;
            }
        }
        return nearest;
    }

    private static String requireValue(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " is empty");
        return value;
    }

    public static final class Sentence {
        public final int start;
        public final int end;
        public final String text;

        Sentence(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }
}

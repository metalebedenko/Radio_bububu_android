package ru.bububu.radio.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.bububu.radio.sources.ContextualSpeech;
import ru.bububu.radio.sources.TelegramSpeech;
import ru.bububu.radio.core.PlayerController;

/** Radio-only spoken copy. Canonical source text and the direct frontend remain unchanged. */
public final class SileroSpeechInput {
    private SileroSpeechInput() { }
    private static final String[] SMALL={"ноль","один","два","три","четыре","пять","шесть","семь","восемь","девять","десять","одиннадцать","двенадцать","тринадцать","четырнадцать","пятнадцать","шестнадцать","семнадцать","восемнадцать","девятнадцать"};
    private static final String[] TENS={"","","двадцать","тридцать","сорок","пятьдесят","шестьдесят","семьдесят","восемьдесят","девяносто"};
    private static final String[] HUNDREDS={"","сто","двести","триста","четыреста","пятьсот","шестьсот","семьсот","восемьсот","девятьсот"};
    private static final String[] GEN={"","первого","второго","третьего","четвёртого","пятого","шестого","седьмого","восьмого","девятого","десятого","одиннадцатого","двенадцатого","тринадцатого","четырнадцатого","пятнадцатого","шестнадцатого","семнадцатого","восемнадцатого","девятнадцатого"};
    private static final String[] PREP={"","первом","втором","третьем","четвёртом","пятом","шестом","седьмом","восьмом","девятом","десятом","одиннадцатом","двенадцатом","тринадцатом","четырнадцатом","пятнадцатом","шестнадцатом","семнадцатом","восемнадцатом","девятнадцатом"};
    private static final String[] GEN_TENS={"","","двадцатого","тридцатого","сорокового","пятидесятого","шестидесятого","семидесятого","восьмидесятого","девяностого"};
    private static final String[] PREP_TENS={"","","двадцатом","тридцатом","сороковом","пятидесятом","шестидесятом","семидесятом","восьмидесятом","девяностом"};
    private static final String[] LETTERS={"эй","би","си","ди","и","эф","джи","эйч","ай","джей","кей","эл","эм","эн","оу","пи","кью","ар","эс","ти","ю","ви","дабл ю","экс","уай","зед"};
    private static final String[] CARD_GEN={"нуля","одного","двух","трёх","четырёх","пяти","шести","семи","восьми","девяти","десяти","одиннадцати","двенадцати","тринадцати","четырнадцати","пятнадцати","шестнадцати","семнадцати","восемнадцати","девятнадцати"};
    private static final String[] TENS_GEN={"","","двадцати","тридцати","сорока","пятидесяти","шестидесяти","семидесяти","восьмидесяти","девяноста"};
    private static final String[] HUNDREDS_GEN={"","ста","двухсот","трёхсот","четырёхсот","пятисот","шестисот","семисот","восьмисот","девятисот"};
    private static final String MONTHS="января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря";
    private static final int FLAGS=Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE;
    private static final Pattern NUMBER=Pattern.compile("[0-9]+(?:,[0-9]+)? *%|[0-9]{4} +(года|году)\\b|[0-9]{1,2} +("+MONTHS+")\\b|[0-9]+(?:[.,:/][0-9]+)*",FLAGS);

    public static String project(String text) {
        return text == null ? null : forSentence(text).project(0, text.length());
    }
    /** Keep grammatical context while the frontend evaluates smaller canonical slices. */
    public static SentencePlan forSentence(String text) { return new SentencePlan(text, java.util.Collections.emptyList()); }
    public static SentencePlan forSentence(PlayerController.SpeechSentence source) {
        return new SentencePlan(source.text(), TelegramSpeech.readings(source));
    }
    public static final class SentencePlan {
        private final String canonical;
        private final List<ContextualSpeech.Reading> readings;
        public String text() { return canonical; }
        private SentencePlan(String text, List<ContextualSpeech.Reading> decorations) {
            canonical = text;
            List<ContextualSpeech.Reading> contextual = ContextualSpeech.scan(text);
            if (decorations.isEmpty()) {
                readings = contextual;
            } else {
                // A verified whole structural reading owns its exact raw interval;
                // a number or accented name inside a URL is not a second spoken atom.
                java.util.BitSet covered = new java.util.BitSet(text.length());
                for (ContextualSpeech.Reading r : decorations) covered.set(r.start, r.end);
                List<ContextualSpeech.Reading> combined = new ArrayList<>(decorations);
                for (ContextualSpeech.Reading r : contextual) {
                    int overlap = covered.nextSetBit(r.start);
                    if (overlap < 0 || overlap >= r.end) combined.add(r);
                }
                combined.sort(java.util.Comparator.comparingInt(r -> r.start));
                readings = combined;
            }
        }
        public String project(int start, int end) {
            String raw = canonical.substring(start, end);
            if (readings.isEmpty()) return projectBasic(raw, true);
            StringBuilder rewritten = null;
            int cursor = start;
            for (ContextualSpeech.Reading span : readings) {
                if (span.end <= start) continue;
                if (span.start >= end) break;
                if (span.start < cursor || span.end > end)
                    throw new IllegalArgumentException("RADIO_CONTEXTUAL_ATOM_SPLIT");
                if (rewritten == null) rewritten = new StringBuilder(raw.length());
                rewritten.append(canonical, cursor, span.start).append(span.spoken);
                cursor = span.end;
            }
            if (rewritten != null) raw = rewritten.append(canonical, cursor, end).toString();
            return projectBasic(raw, true);
        }
        public List<Integer> boundaries() { return cuts(canonical, readings); }
    }
    /** Source-v2 classifies complete original tokens before this numeric/punctuation pass. */
    public static String projectWithoutInitialisms(String text){return projectBasic(text,false);}
    /** Render plain spans after the source planner already classified the full sentence. */
    public static String projectWithoutContext(String text){return projectBasic(text,true);}
    private static String projectBasic(String text,boolean initialisms) {
        if(text==null)return null;
        if(initialisms)text=ru.bububu.radio.sources.SourceInitialisms45.expand(text);
        String analysis=text.replace('\n',' '); // One UTF-16 unit per LF; matcher offsets still address canonical text.
        if(Pattern.compile("[+\\-−–—] *[0-9]|[0-9] *[-–—] *[0-9]").matcher(analysis).find())
            throw new IllegalArgumentException("UNSUPPORTED_RADIO_SIGNED_OR_RANGE");
        StringBuilder out=new StringBuilder();Matcher numbers=NUMBER.matcher(analysis);int cursor=0;
        while(numbers.find()) {
            out.append(punctuation(text.substring(cursor,numbers.start())));
            if(numbers.start()>0&&Character.isLetter(analysis.charAt(numbers.start()-1)))
                throw new IllegalArgumentException("UNSUPPORTED_RADIO_NUMBER_CONTEXT");
            String raw=numbers.group(),spoken;
            if(raw.endsWith("%"))spoken=percent(raw.substring(0,raw.length()-1).trim(),analysis.substring(0,numbers.start()).matches("(?isu).*\\b(?:более|менее|до|от|около|свыше|без) +"));
            else if(numbers.group(1)!=null) {
                int year=integer(raw.substring(0,4));boolean prep=numbers.group(1).equalsIgnoreCase("году");
                if(year<1900||year>2099||year%100==0)throw new IllegalArgumentException("UNSUPPORTED_RADIO_YEAR");
                if(prep&&!analysis.substring(0,numbers.start()).matches("(?isu).*\\bв +"))
                    throw new IllegalArgumentException("UNSUPPORTED_RADIO_YEAR_CASE");
                String prefix=cardinal(year/100*100);if(prefix.startsWith("одна "))prefix=prefix.substring(5);
                spoken=prefix+" "+ordinal(year%100,prep)+" "+numbers.group(1).toLowerCase(Locale.ROOT);
            } else if(numbers.group(2)!=null) {
                int day=integer(raw.substring(0,raw.indexOf(' ')));String month=numbers.group(2).toLowerCase(Locale.ROOT);
                int index=java.util.Arrays.asList(MONTHS.split("\\|")).indexOf(month);
                int[] max={31,28,31,30,31,30,31,31,30,31,30,31};
                if(day<1||day>max[index])throw new IllegalArgumentException("UNSUPPORTED_RADIO_DATE");
                String dayWord=ordinal(day,false);
                if(analysis.substring(0,numbers.start()).matches("(?isu).*\\bСегодня +"))
                    dayWord=dayWord.substring(0,dayWord.length()-3)+(dayWord.endsWith("его")?"е":"ое");
                spoken=dayWord+" "+month;
            } else spoken=cardinal(integer(raw));
            out.append(spoken);cursor=numbers.end();
        }
        out.append(punctuation(text.substring(cursor)));
        String projected=out.toString();return projected.equals(text)?text:projected;
    }

    /** Candidate cuts cannot separate a numeric value from its date/year/percent unit. */
    public static List<Integer> boundaries(String text) {
        return forSentence(text).boundaries();
    }
    private static List<Integer> cuts(String text,List<ContextualSpeech.Reading> readings) {
        String analysis=text.replace('\n',' ');
        List<int[]> protectedSpans=new ArrayList<>();Matcher values=NUMBER.matcher(analysis);
        while(values.find())protectedSpans.add(new int[]{values.start(),values.end()});
        Matcher scoped=Pattern.compile("\\b(?:более|менее|до|от|около|свыше|без|в|с|сегодня) +(?:"+NUMBER.pattern()+")",FLAGS).matcher(analysis);
        while(scoped.find())protectedSpans.add(new int[]{scoped.start(),scoped.end()});
        for (ContextualSpeech.Reading r : readings) protectedSpans.add(new int[]{r.start, r.end});
        int[] depth = new int[text.length()+1];
        for (int[] s : protectedSpans) if(s[1] > s[0]+1) { depth[s[0]+1]++; depth[s[1]]--; }
        List<Integer> ends=new ArrayList<>();
        int active=0;
        for(int i=1;i<=text.length();i++) {
            active+=depth[i];
            if(active!=0 || (i<text.length()&&!Character.isWhitespace(text.charAt(i)))) continue;
            if(Character.isWhitespace(text.charAt(i-1)))continue;
            ends.add(i);
        }
        return ends;
    }

    private static String punctuation(String text) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(latin(c)) {
                int end=i;while(end<text.length()&&latin(text.charAt(end)))end++;
                for(int at=i;at<end;at++){if(at>i)out.append(' ');out.append(latinLetterName(text.charAt(at)));}
                i=end-1;
            }
            else if(c=='.'&&i>0&&i+1<text.length()&&latin(text.charAt(i-1))&&latin(text.charAt(i+1)))out.append(" точка ");
            else if(c=='\n')out.append(' ');
            else if(c=='"'||c=='«'||c=='»'||c=='('||c==')')out.append(',');
            else if(c=='-'||c=='–'||c=='—')out.append(' ');
            else out.append(c);
        }
        return out.toString();
    }

    private static boolean latin(char c) { return c>='A'&&c<='Z'||c>='a'&&c<='z'; }
    public static String latinLetterName(char ascii) {
        if (!latin(ascii)) throw new IllegalArgumentException("Expected an ASCII Latin letter");
        return LETTERS[Character.toUpperCase(ascii) - 'A'];
    }

    private static int integer(String raw) {
        if(!raw.matches("0|[1-9][0-9]{0,3}"))throw new IllegalArgumentException("UNSUPPORTED_RADIO_INTEGER");
        return Integer.parseInt(raw);
    }

    private static String cardinal(int value) {
        if(value<0||value>9999)throw new IllegalArgumentException("UNSUPPORTED_RADIO_INTEGER");
        if(value<20)return SMALL[value];
        if(value<100)return TENS[value/10]+(value%10==0?"":" "+SMALL[value%10]);
        if(value<1000)return HUNDREDS[value/100]+(value%100==0?"":" "+cardinal(value%100));
        int thousands=value/1000;String prefix=thousands==1?"одна тысяча":thousands==2?"две тысячи":SMALL[thousands]+(thousands<5?" тысячи":" тысяч");
        return prefix+(value%1000==0?"":" "+cardinal(value%1000));
    }

    private static String feminine(int value) {
        String word=cardinal(value);
        if(value%100!=11&&value%10==1)return word.substring(0,word.length()-4)+"одна";
        if(value%100!=12&&value%10==2)return word.substring(0,word.length()-3)+"две";
        return word;
    }

    private static String ordinal(int value,boolean prep) {
        if(value<1||value>99)throw new IllegalArgumentException("UNSUPPORTED_RADIO_ORDINAL");
        if(value<20)return (prep?PREP:GEN)[value];
        return value%10==0?(prep?PREP_TENS:GEN_TENS)[value/10]:TENS[value/10]+" "+(prep?PREP:GEN)[value%10];
    }

    private static String cardinalGenitive(int value) {
        if(value<20)return CARD_GEN[value];
        if(value<100)return TENS_GEN[value/10]+(value%10==0?"":" "+CARD_GEN[value%10]);
        return HUNDREDS_GEN[value/100]+(value%100==0?"":" "+cardinalGenitive(value%100));
    }

    private static String percent(String raw,boolean genitive) {
        String[] parts=raw.split(",",-1);int whole=integer(parts[0]);
        if(whole>999)throw new IllegalArgumentException("UNSUPPORTED_RADIO_PERCENT");
        if(parts.length==1) {
            if(genitive)return cardinalGenitive(whole)+(whole%100!=11&&whole%10==1?" процента":" процентов");
            String unit=whole%100>=11&&whole%100<=14?"процентов":whole%10==1?"процент":whole%10>=2&&whole%10<=4?"процента":"процентов";
            return cardinal(whole)+" "+unit;
        }
        if(genitive)throw new IllegalArgumentException("UNSUPPORTED_RADIO_GENITIVE_DECIMAL_PERCENT");
        if(parts.length!=2||!parts[1].matches("[0-9]{1,2}"))throw new IllegalArgumentException("UNSUPPORTED_RADIO_DECIMAL_PERCENT");
        int fraction=Integer.parseInt(parts[1]);boolean one=fraction%100!=11&&fraction%10==1;
        String denominator=parts[1].length()==1?(one?"десятая":"десятых"):(one?"сотая":"сотых");
        return feminine(whole)+(whole%100!=11&&whole%10==1?" целая ":" целых ")+feminine(fraction)+" "+denominator+" процента";
    }
}

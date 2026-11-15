package ru.bububu.radio.sources;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import ru.bububu.radio.models.SileroSpeechInput;

/** Shared, transient readings over immutable UTF-16 input. Context is evidence, not replacement text. */
public final class ContextualSpeech {
    private ContextualSpeech() {}
    public static final class Reading {
        public final int start,end,contextStart,contextEnd;
        public final String spoken,kind;
        public Reading(int start,int end,int contextStart,int contextEnd,String spoken,String kind) {
            if(contextStart<0 || start<contextStart || end<=start || contextEnd<end || spoken.isEmpty()) throw new IllegalArgumentException("Invalid reading span");
            this.start=start;this.end=end;this.contextStart=contextStart;this.contextEnd=contextEnd;
            this.spoken=spoken;this.kind=kind;
        }
    }
    private static final int RU=Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE;
    private static final String WS="[ \\u00a0\\u202f]";
    private static final String LEFT="(?<![\\p{L}\\p{M}\\p{N}_:+/#@.$%\\-−–—=<>*^,])";
    private static final String RIGHT="(?![\\p{L}\\p{M}\\p{N}_:/\\-])(?!\\.[0-9])";
    private static final String WORD="[а-яё]{2,48}";
    private static final String NUMBER="[1-9][0-9]{0,3}";
    private static final Pattern ORDINAL=Pattern.compile(LEFT+"("+NUMBER+")-(го|му|я|ю|е|м|й)"+WS+"+("+WORD+")"+RIGHT,RU);
    private static final Pattern CARDINAL=Pattern.compile(LEFT+"("+NUMBER+")"+WS+"+("+WORD+")"+RIGHT,RU);
    private static final Pattern GOVERNOR=Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}_/])([а-яё]{1,48})"+WS+"+$",RU);
    private static final Set<String> PREPOSITIONAL=Set.of("в","во","на","об","о","при");
    private static final Set<String> INSTRUMENTAL=Set.of("под","перед","над","с","со","за");
    private static final Set<String> AMBIGUOUS_NOM=Set.of("с","со","по","между","над","за","под","перед","на","о","при","в","во","об");
    private static final Set<String> GENITIVE=Set.of("до","от","из","без","для","около","свыше","менее","более","после","вместо","порядка");
    private static final Set<String> GENITIVE_HEAD=Set.of("обладатель","обладательница","победитель","победительница","владелец","автор","команда","группа");
    private static final Set<String> OPAQUE_HEAD=Set.of("код","артикул","версия","модель","индекс","номер","sku","id");
    private static final Set<String> MONTHS=Set.of("января","февраля","марта","апреля","мая","июня","июля","августа","сентября","октября","ноября","декабря");
    // Only unambiguous forms of common counting nouns. Endings alone do not distinguish e.g. книги/уроки.
    // The four forms distinguish "одна книга", "две книги", "к одной книге", "одну книгу".
    private static final Map<String,List<String>> FEMININE=new HashMap<>();
    static {
        for(String paradigm:List.of("книга книги книге книгу","коробка коробки коробке коробку",
                "заявка заявки заявке заявку","статья статьи статье статью","неделя недели неделе неделю",
                "модель модели модели модель","страница страницы странице страницу","задача задачи задаче задачу",
                "машина машины машине машину","школа школы школе школу","минута минуты минуте минуту",
                "команда команды команде команду","станция станции станции станцию","страна страны стране страну")) {
            List<String> forms=Arrays.asList(paradigm.split(" "));
            for(String form:forms)FEMININE.put(form,forms);
        }
    }
    private static final Set<String> NEUTER=Set.of("место","число","окно","слово","письмо","село","утро","поле","время","имя","правило","кресло");
    private static final Set<String> MASCULINE=Set.of("этаж","этап","класс","раунд","век","день","месяц","сезон","год","номер","урок","курс","район","том","пункт","выпуск","фестиваль","километр","участник","участнику");
    private static final String SET="(?:6:[0-4]|[0-4]:6|7:[56]|[56]:7)";
    private static final Pattern SET_PAIR=Pattern.compile(SET);
    private static final Pattern TENNIS_CUE=Pattern.compile("(?<![\\p{L}\\p{N}_])(?:матч(?:а|е|ем)?|сет(?:а|ов|е|ах)?|теннис(?:ист(?:ка)?|ного|ный)?|гейм(?:а|ов)?)"+RIGHT,RU);
    private static final Pattern SET_RESULT=Pattern.compile(LEFT+"(?:(со"+WS+"+сч[её]том)"+WS+"+|сетах"+WS+"*[-–—:]"+WS+"*)"
            +"("+SET+"(?:"+WS+"*,"+WS+"*"+SET+"){0,4})"+RIGHT+"(?!"+WS+"*,"+WS+"*[0-9]+:)",RU);
    // Structural safeguards apply to every candidate. Do not give a unit or suffix meaning inside an opaque identity.
    private static final Pattern PROTECTED=Pattern.compile(
            "https?://[^\\s<>«»\"]+|\\[[^\\]\\r\\n]{1,128}\\]|(?<![\\w.+-])[\\w.+-]+@[\\w.-]+"
            +"|(?<![\\p{N}])[0-9]{1,4}[./-][0-9]{1,2}[./-][0-9]{1,4}(?![\\p{N}])"
            +"|[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?|(?<![0-9])[0-9]+(?:[.,][0-9]+)?[eе][+\\-−]?[0-9]+"
            +"|(?<![a-z0-9_])(?:[a-z][a-z0-9_]*[0-9]|[0-9]+[a-z])[a-z0-9_/.-]*",RU);
    private static final Pattern OPAQUE_RANGE=Pattern.compile("(?<![0-9])[0-9]+[–—/-][0-9]+");
    private static final Pattern WORD_TOKEN = Pattern.compile("[\\p{L}\\p{M}\\p{N}_]+");

    public static List<Reading> scan(String text) {
        boolean numbers = text.chars().anyMatch(c -> c >= '0' && c <= '9');
        boolean accents = text.codePoints().anyMatch(c -> c > 127 && (Character.getType(c) == Character.NON_SPACING_MARK
                || Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN));
        boolean typography = SourceTypography.present(text);
        boolean chemicalIndex = SourceChemicalFormula.hasIndex(text);
        if (!numbers && !accents && text.indexOf('I') < 0 && !typography && !chemicalIndex) return Collections.emptyList();
        BitSet occupied=new BitSet(text.length());
        Matcher guard=PROTECTED.matcher(text);
        while(guard.find()) occupied.set(guard.start(),guard.end());
        List<Reading> candidates=new ArrayList<>(),result=new ArrayList<>();
        // Currency plus an explicit multiplier identifies the scale at both ends before the raw range guard.
        SourceNewsNumbers.add(text,candidates);
        if(numbers)SourceDatesAndAges.add(text,candidates);
        accept(candidates,occupied,result);
        candidates.clear();
        Matcher ranges=OPAQUE_RANGE.matcher(text);
        while(ranges.find())occupied.set(ranges.start(),ranges.end());
        // A quantity binds its number and unit before a number alone can claim the same interval.
        if (numbers) {
            ContextualMeasures.add(text,candidates);
            addOrdinals(text,candidates);
            addScores(text,candidates);
            addCardinals(text,candidates);
        }
        if (accents) addAccentedLatinWords(text, candidates);
        if (typography) SourceTypography.add(text, candidates);
        if (chemicalIndex) SourceChemicalFormula.add(text, candidates);
        accept(candidates,occupied,result);
        result.sort(Comparator.comparingInt(r->r.start));
        return Collections.unmodifiableList(result);
    }
    private static void accept(List<Reading> candidates,BitSet occupied,List<Reading> result){
        for(Reading r:candidates){
            int blocked=occupied.nextSetBit(r.start);
            if(blocked>=0 && blocked<r.end)continue;
            occupied.set(r.start,r.end);result.add(r);
        }
    }
    private static void addAccentedLatinWords(String text, List<Reading> candidates) {
        Matcher tokens = WORD_TOKEN.matcher(text);
        while (tokens.find()) {
            // Long literal tokens retain their existing per-character cuts and frontend limits.
            if (tokens.end() - tokens.start() > 32) continue;
            String raw = tokens.group();
            if (raw.charAt(0) > 127 && Character.UnicodeScript.of(raw.codePointAt(0)) != Character.UnicodeScript.LATIN) continue;
            if (raw.chars().allMatch(c -> c < 128)) continue;
            String spoken = accentedLatinSpelling(raw);
            if (spoken != null) candidates.add(new Reading(tokens.start(), tokens.end(), tokens.start(), tokens.end(), spoken, "accented-latin-word"));
        }
    }
    private static String accentedLatinSpelling(String raw) {
        // Normalize only the spoken copy of this token. All offsets stay relative to the original UTF-16.
        String composed = Normalizer.normalize(raw, Normalizer.Form.NFC);
        StringJoiner names = new StringJoiner(" ");
        boolean accented = false;
        for (int at = 0; at < composed.length();) {
            int cp = composed.codePointAt(at);
            at += Character.charCount(cp);
            if (!Character.isLetter(cp) || Character.UnicodeScript.of(cp) != Character.UnicodeScript.LATIN) return null;
            String decomposed = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFD);
            char base = decomposed.charAt(0);
            if (!(base >= 'A' && base <= 'Z' || base >= 'a' && base <= 'z')) return null;
            if (decomposed.length() > 1) {
                if (!decomposed.codePoints().skip(1).allMatch(c -> Character.getType(c) == Character.NON_SPACING_MARK)) return null;
                accented = true;
            }
            // Keep the existing explicit names for the originally supported accented glyphs.
            String known = cp <= Character.MAX_VALUE ? SourceGlyphs45.observedTable().get((char)cp) : null;
            names.add(known != null ? known : SileroSpeechInput.latinLetterName(base));
        }
        return accented ? names.toString() : null;
    }
    private static final class Prior {
        final String word;final int start;
        Prior(String text,int at) {
            Matcher m=GOVERNOR.matcher(text).region(Math.max(0,at-56),at).useTransparentBounds(true);
            boolean matched=m.find();word=matched?m.group(1).toLowerCase(Locale.ROOT):"";start=matched?m.start():at;
        }
    }
    private static boolean groupedTail(String text,int at) {
        while(at>0 && (text.charAt(at-1)==' '||text.charAt(at-1)=='\u00a0'||text.charAt(at-1)=='\u202f')) at--;
        return at>0 && Character.isDigit(text.charAt(at-1));
    }
    private static boolean neuter(String noun) {
        return NEUTER.contains(noun)||noun.endsWith("ние")||noun.endsWith("тие")||noun.endsWith("ство");
    }
    private static void addOrdinals(String text,List<Reading> readings) {
        Matcher m=ORDINAL.matcher(text);
        while(m.find()){
            Prior prior=new Prior(text,m.start());
            if(OPAQUE_HEAD.contains(prior.word)||groupedTail(text,m.start()))continue;
            int n=Integer.parseInt(m.group(1));
            String suffix=m.group(2).toLowerCase(Locale.ROOT),noun=m.group(3).toLowerCase(Locale.ROOT);
            String gen=SourceWords45.ordinal(n),stem=gen.substring(0,gen.length()-3),tail;
            boolean soft=gen.endsWith("его");
            int contextStart=m.start();
            switch(suffix){
                case "го": tail=soft?"его":"ого";break;
                case "му": tail=soft?"ему":"ому";break;
                case "я": tail=soft?"я":"ая";break;
                case "ю": tail=soft?"ю":"ую";break;
                case "е": if(!neuter(noun))continue;tail=soft?"е":"ое";break;
                case "м":
                    if(PREPOSITIONAL.contains(prior.word) && (noun.endsWith("е")||noun.endsWith("и")||noun.endsWith("у"))) tail=soft?"ем":"ом";
                    else if(INSTRUMENTAL.contains(prior.word) && (noun.endsWith("ом")||noun.endsWith("ем"))) tail=soft?"им":"ым";
                    else continue;
                    contextStart=prior.start;break;
                case "й":
                    if(!MASCULINE.contains(noun) || GENITIVE.contains(prior.word) || (AMBIGUOUS_NOM.contains(prior.word)||prior.word.equals("к")))continue;
                    if(soft){stem=stem.substring(0,stem.length()-1);tail="ий";}
                    else tail=stem.matches(".*(?:втор|шест|седьм|восьм|сороков)")?"ой":"ый";
                    break;
                default: continue;
            }
            readings.add(new Reading(m.start(),m.end(2),contextStart,m.end(),stem+tail,"ordinal"));
        }
    }
    private static void addCardinals(String text,List<Reading> readings) {
        Matcher m=CARDINAL.matcher(text);
        while(m.find()){
            int n=Integer.parseInt(m.group(1));
            String noun=m.group(2).toLowerCase(Locale.ROOT);
            Prior prior=new Prior(text,m.start());
            if(groupedTail(text,m.start()) || OPAQUE_HEAD.contains(prior.word) || MONTHS.contains(noun)
                    || (n>=1000 && noun.startsWith("год")))continue;
            boolean one=n%10==1&&n%100!=11, two=n%10==2&&n%100!=12;
            List<String> feminine=FEMININE.get(noun);
            boolean gen=GENITIVE.contains(prior.word) || (GENITIVE_HEAD.contains(prior.word) && (noun.endsWith("ов")||noun.endsWith("ей")||noun.endsWith("ев")));
            boolean dat=prior.word.equals("к")||prior.word.equals("ко");
            if(gen||dat){
                // For "… одному/одной" the head must resolve gender. Other oblique forms coincide.
                if(one && feminine==null && !MASCULINE.contains(noun) && !(dat&&(noun.endsWith("у")||noun.endsWith("ю"))) && !(gen&&(noun.endsWith("а")||noun.endsWith("я")))) continue;
                String spoken=dat?SourceWords45.dative(n):SourceWords45.genitive(n);
                if(one&&feminine!=null)spoken=spoken.replaceFirst("(?:одного|одному)$","одной");
                readings.add(new Reading(m.start(),m.end(1),prior.start,m.end(),spoken,"governed-cardinal"));
            } else if((one||two) && feminine!=null && !AMBIGUOUS_NOM.contains(prior.word)){
                boolean accusative=!feminine.get(0).equals(noun)&&feminine.get(3).equals(noun);
                if(one && !feminine.get(0).equals(noun) && !accusative || two && !feminine.get(1).equals(noun))continue;
                String spoken=SourceWords45.cardinal(n);
                if(one)spoken=spoken.replaceFirst("один$",accusative?"одну":"одна");
                else spoken=spoken.replaceFirst("два$","две");
                readings.add(new Reading(m.start(),m.end(1),m.start(),m.end(),spoken,"gender-cardinal"));
            }
        }
    }
    private static void addScores(String text,List<Reading> readings) {
        Matcher results=SET_RESULT.matcher(text);
        while(results.find()){
            int context=results.start();
            if(results.group(1)!=null){
                int window=context;
                while(window>0 && context-window<120 && ".!?;".indexOf(text.charAt(window-1))<0)window--;
                Matcher cue=TENNIS_CUE.matcher(text).region(window,context).useTransparentBounds(true);
                if(!cue.find())continue;
                context=cue.start();
            }
            Matcher pairs=SET_PAIR.matcher(text).region(results.start(2),results.end(2));
            while(pairs.find()){
                String pair=pairs.group();
                String spoken=SourceWords45.cardinal(pair.charAt(0)-'0')+" "+SourceWords45.cardinal(pair.charAt(2)-'0');
                readings.add(new Reading(pairs.start(),pairs.end(),context,results.end(),spoken,"tennis-score"));
            }
        }
    }
}

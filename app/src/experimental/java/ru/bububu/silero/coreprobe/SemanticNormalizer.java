package ru.bububu.silero.coreprobe;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owned C255 grammar port, not a general Russian news normalizer. UTF-16 offsets. */
public final class SemanticNormalizer {
    private static final String[] SMALL={"ноль","один","два","три","четыре","пять","шесть","семь","восемь","девять","десять","одиннадцать","двенадцать","тринадцать","четырнадцать","пятнадцать","шестнадцать","семнадцать","восемнадцать","девятнадцать"};
    private static final String[] TENS={"","","двадцать","тридцать","сорок","пятьдесят","шестьдесят","семьдесят","восемьдесят","девяносто"};
    private static final String[] NOM={"","первое","второе","третье","четвёртое","пятое","шестое","седьмое","восьмое","девятое","десятое","одиннадцатое","двенадцатое","тринадцатое","четырнадцатое","пятнадцатое","шестнадцатое","семнадцатое","восемнадцатое","девятнадцатое","двадцатое"};
    private static final String[] GEN={"","первого","второго","третьего","четвёртого","пятого","шестого","седьмого","восьмого","девятого","десятого","одиннадцатого","двенадцатого","тринадцатого","четырнадцатого","пятнадцатого","шестнадцатого","семнадцатого","восемнадцатого","девятнадцатого","двадцатого"};
    private static final String[] MONTHS={"января","февраля","марта","апреля","мая","июня","июля","августа","сентября","октября","ноября","декабря"};
    private static final int[] MONTH_MAX={31,28,31,30,31,30,31,31,30,31,30,31};
    private static final String MONTH="(?:"+String.join("|",MONTHS)+")";
    private static final int FLAGS=Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE;
    private static final Pattern NUMBER=Pattern.compile("[0-9]+"),CLAUSE=Pattern.compile("[^,.!?;]+");
    private static final Pattern TODAY=Pattern.compile("Сегодня +([0-9]+) +("+MONTH+")",FLAGS);
    private static final Pattern EVENT=Pattern.compile("(?:Встреча|Собрание|Мероприятие) +состоится +([0-9]+) +("+MONTH+")",FLAGS);
    private static final Pattern DEGREE=Pattern.compile("(?:Температура +)?([0-9]+)( *)(градус(?:а|ов)?)",FLAGS);
    public static final class Rewrite {
        public final int rawStart,rawEnd,spokenStart,spokenEnd,value;
        public final String role,replacement;
        public final boolean separatorAdded;
        private Rewrite(int start,int end,int value,String role,String replacement,boolean separator,int spokenStart) {
            rawStart=start;rawEnd=end;this.value=value;this.role=role;this.replacement=replacement;
            separatorAdded=separator;this.spokenStart=spokenStart;spokenEnd=spokenStart+replacement.length();
        }
    }
    public static final class Outcome {
        public final String status,raw,spoken,code;
        public final int rejectStart,rejectEnd;
        public final List<Rewrite> rewrites;
        private Outcome(String status,String raw,String spoken,List<Rewrite> edits,String code,int start,int end) {
            this.status=status;this.raw=raw;this.spoken=spoken;this.code=code;rejectStart=start;rejectEnd=end;
            rewrites=Collections.unmodifiableList(new ArrayList<>(edits));
        }
        public boolean supported() { return !"UNSUPPORTED".equals(status); }
    }
    private static final class Reject extends RuntimeException {
        final String code;final int start,end;
        Reject(String code,int start,int end) { this.code=code;this.start=start;this.end=end; }
    }
    private static void reject(String code,int start,int end) { throw new Reject(code,start,end); }
    private static void rejectMatch(String raw,String regex,String code) {
        Matcher m=Pattern.compile(regex,FLAGS).matcher(raw);if(m.find()) reject(code,m.start(),m.end());
    }
    public static String cardinal(int n) {
        if(n<0||n>99) throw new IllegalArgumentException("UNSUPPORTED_INTEGER_RANGE");
        return n<20?SMALL[n]:TENS[n/10]+(n%10==0?"":" "+SMALL[n%10]);
    }
    public static String degreeForm(int n) {
        cardinal(n);return n%100>=11&&n%100<=14?"градусов":n%10==1?"градус":n%10>=2&&n%10<=4?"градуса":"градусов";
    }
    private static String ordinal(int n,boolean genitive) {
        if(n<1||n>31) throw new IllegalArgumentException("UNSUPPORTED_DATE_DAY");
        String[] words=genitive?GEN:NOM;
        if(n<=20) return words[n];
        if(n==30) return genitive?"тридцатого":"тридцатое";
        return TENS[n/10]+" "+words[n%10];
    }
    private static void validate(String raw) {
        if(raw==null) reject("UNSUPPORTED_INPUT_TYPE",0,0);
        if(raw.trim().isEmpty()) reject("UNSUPPORTED_EMPTY",0,raw.length());
        if(raw.length()>1000) reject("UNSUPPORTED_INPUT_LIMIT",0,raw.length());
        for(int i=0;i<raw.length();) {
            char ch=raw.charAt(i);int cp=raw.codePointAt(i),width=Character.charCount(cp);
            if(Character.isSurrogate(ch)&&width==1) reject("UNSUPPORTED_UNICODE",i,i+1);
            int type=Character.getType(cp);
            if(type==Character.CONTROL||type==Character.FORMAT||type==Character.PRIVATE_USE||type==Character.UNASSIGNED)
                reject("UNSUPPORTED_CONTROL_CHAR",i,i+width);
            if((type==Character.DECIMAL_DIGIT_NUMBER||type==Character.LETTER_NUMBER||type==Character.OTHER_NUMBER)&&!(cp>='0'&&cp<='9'))
                reject("UNSUPPORTED_DIGIT_SCRIPT",i,i+width);
            i+=width;
        }
        if(!Normalizer.isNormalized(raw,Normalizer.Form.NFC)) reject("UNSUPPORTED_UNICODE_NORMALIZATION",0,raw.length());
        boolean numeric=NUMBER.matcher(raw).find();
        rejectMatch(raw,"[$€₽£¥]","UNSUPPORTED_CURRENCY");
        if(numeric) rejectMatch(raw,"(?<![\\p{L}\\p{M}])(?:руб(?:ль|ля|лей|\\.)|доллар(?:а|ов)?|евро)(?![\\p{L}\\p{M}])","UNSUPPORTED_CURRENCY");
        rejectMatch(raw,"[0-9] *[,./:] *[0-9]|[0-9] *[-–—] *[0-9]|[0-9] *[eE] *[+\\-]?[0-9]|[0-9] +[0-9]","UNSUPPORTED_NUMERIC_FORMAT");
        rejectMatch(raw,"[+\\-−–—] *[0-9]|\\bминус +[0-9]","UNSUPPORTED_SIGNED_NUMBER");
        if(numeric) rejectMatch(raw,"°|%|(?<![а-яё])(?:кг|см|мм|км|мл|квт|гц)(?![а-яё])","UNSUPPORTED_UNIT_FORMAT");
        Matcher numbers=NUMBER.matcher(raw);
        while(numbers.find()) {
            String token=numbers.group();
            if(token.length()>1&&token.startsWith("0")) reject("UNSUPPORTED_LEADING_ZERO",numbers.start(),numbers.end());
            if(token.length()>2) reject("UNSUPPORTED_INTEGER_RANGE",numbers.start(),numbers.end());
        }
        for(int i=0;i<raw.length();i++) {
            char ch=raw.charAt(i);
            if(!(ch>='а'&&ch<='я'||ch>='А'&&ch<='Я'||"ёЁ0123456789 .,!?:;+-".indexOf(ch)>=0))
                reject("UNSUPPORTED_CHARACTER",i,i+Character.charCount(raw.codePointAt(i)));
            if(ch=='+'&&(i+1==raw.length()||"аеёиоуыэюяАЕЁИОУЫЭЮЯ".indexOf(raw.charAt(i+1))<0))
                reject("UNSUPPORTED_STRESS_MARK",i,i+1);
        }
    }
    public static Outcome normalize(String raw) {
        List<Rewrite> edits=new ArrayList<>();
        try {
            validate(raw);Matcher all=NUMBER.matcher(raw);List<int[]> spans=new ArrayList<>();
            while(all.find()) spans.add(new int[]{all.start(),all.end()});
            if(spans.isEmpty()) return new Outcome("PASSTHROUGH",raw,raw,edits,null,-1,-1);
            Matcher clauses=CLAUSE.matcher(raw);int shift=0;
            while(clauses.find()) {
                String chunk=clauses.group();if(!NUMBER.matcher(chunk).find()) continue;
                int left=0,right=chunk.length();while(left<right&&chunk.charAt(left)==' ') left++;
                while(right>left&&chunk.charAt(right-1)==' ') right--;
                String text=chunk.substring(left,right);int origin=clauses.start()+left;
                Matcher date=TODAY.matcher(text);boolean genitive=false;
                if(!date.matches()) { date=EVENT.matcher(text);genitive=true; }
                if(date.matches()) {
                    int value=Integer.parseInt(date.group(1)),start=origin+date.start(1),end=origin+date.end(1);
                    String month=date.group(2).toLowerCase(Locale.ROOT);int mi=java.util.Arrays.asList(MONTHS).indexOf(month);
                    if(value<1||value>31) reject("UNSUPPORTED_DATE_DAY",start,end);
                    if(mi==1&&value==29) reject("UNSUPPORTED_LEAP_YEAR_CONTEXT",start,end);
                    if(value>MONTH_MAX[mi]) reject("UNSUPPORTED_CALENDAR_DAY",start,end);
                    String replacement=ordinal(value,genitive);
                    edits.add(new Rewrite(start,end,value,genitive?"DATE_GENITIVE":"DATE_NOM_NEUTER",replacement,false,start+shift));
                    shift+=replacement.length()-(end-start);continue;
                }
                Matcher degree=DEGREE.matcher(text);
                if(degree.matches()) {
                    int value=Integer.parseInt(degree.group(1)),start=origin+degree.start(1),end=origin+degree.end(1);
                    if(!degree.group(3).toLowerCase(Locale.ROOT).equals(degreeForm(value)))
                        reject("UNSUPPORTED_UNIT_AGREEMENT",origin+degree.start(3),origin+degree.end(3));
                    boolean joined=degree.group(2).isEmpty();String replacement=cardinal(value)+(joined?" ":"");
                    edits.add(new Rewrite(start,end,value,"CARDINAL_DEGREE",replacement,joined,start+shift));
                    shift+=replacement.length()-(end-start);continue;
                }
                Matcher n=NUMBER.matcher(text);if(!n.find()) reject("UNSUPPORTED_INCOMPLETE_REWRITE",0,raw.length());
                reject(Pattern.compile(MONTH,FLAGS).matcher(text).find()?"UNSUPPORTED_DATE_CONTEXT":"UNSUPPORTED_NUMERIC_CONTEXT",origin+n.start(),origin+n.end());
            }
            if(edits.size()!=spans.size()) reject("UNSUPPORTED_INCOMPLETE_REWRITE",0,raw.length());
            StringBuilder spoken=new StringBuilder();int cursor=0;
            for(int i=0;i<edits.size();i++) {
                Rewrite edit=edits.get(i);int[] span=spans.get(i);
                if(edit.rawStart!=span[0]||edit.rawEnd!=span[1]) reject("UNSUPPORTED_INCOMPLETE_REWRITE",0,raw.length());
                spoken.append(raw,cursor,edit.rawStart).append(edit.replacement);cursor=edit.rawEnd;
            }
            spoken.append(raw,cursor,raw.length());
            if(spoken.length()>1000) reject("UNSUPPORTED_OUTPUT_LIMIT",0,raw.length());
            if(NUMBER.matcher(spoken).find()) reject("UNSUPPORTED_RESIDUAL_DIGIT",0,raw.length());
            return new Outcome("SUPPORTED",raw,spoken.toString(),edits,null,-1,-1);
        } catch(Reject e) { return new Outcome("UNSUPPORTED",raw,null,Collections.emptyList(),e.code,e.start,e.end); }
    }
    private SemanticNormalizer() {}
}

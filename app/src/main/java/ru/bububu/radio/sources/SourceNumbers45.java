package ru.bububu.radio.sources;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import ru.bububu.radio.models.SileroSpeechInput;

/** Closed source45 numeric forms observed in the saved nine bodies. No legacy dispatch. */
public final class SourceNumbers45 {
    public static final String REVISION="source45-numbers-prose-v6-r2-bounded-rules";
    private static final int FLAGS=Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE;
    static final String MONTH="января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря";
    private static final String GOV="в|во|к|ко|по|с|со|до|от|после|более|менее|около|свыше|без|сегодня";
    private static final String DATE="[0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{4}(?![0-9])";
    private static final String YEAR="(?:(?:"+GOV+") +)?[0-9]{4} +(года|году|годом|год)\\b";
    private static final String DAY="[0-9]{1,2} +(?:"+MONTH+")\\b";
    private static final String ID="(?<![\\p{L}0-9])(?:[A-Za-z]+[0-9][A-Za-z0-9]*|[0-9]+[A-Za-z][A-Za-z0-9]*|[А-Яа-яЁё][0-9][А-Яа-яЁёA-Za-z0-9]*)(?:[/–-][А-Яа-яЁёA-Za-z0-9]+)*";
    private static final String VALUE="[+\\-−]?(?:[1-9][0-9]{0,2}(?:[ \\u00a0\\u202f][0-9]{3})+|[0-9]+)(?:[.,][0-9]+)?(?: *[%°])?";
    private static final String CURRENCY="\\$[0-9]+(?:,[0-9]+)?(?: +(?:млн|млрд)\\b)?";
    private static final String SPACE="[ \\u00a0\\u202f]";
    private static final String RULE_LEFT="(?<![\\p{L}\\p{M}\\p{N}_/+\\-−–—.,:])";
    private static final String RULE_RIGHT="(?![\\p{L}\\p{M}\\p{N}_/\\-])(?!\\.[0-9])";
    static final String AGE_END="летний|летняя|летнее|летние|летнего|летней|летних|летнему|летнюю|летним|летними|летнем";
    private static final String AGE=RULE_LEFT+"[1-9][0-9]?-(?:"+AGE_END+")"+RULE_RIGHT;
    private static final String NUMBER_ORDINAL=RULE_LEFT+"[1-9][0-9]{0,3}-го"+SPACE+"+номера"+RULE_RIGHT;
    private static final String UNIT_NUMBER="[+\\-−]?(?:[1-9][0-9]{0,2}(?:"+SPACE+"[0-9]{3})+|0|[1-9][0-9]*)(?:[.,][0-9]+)?";
    // Exact SI case and separate numbers only. A neighbouring governor/grouped
    // prefix stays on the existing path instead of guessing a new noun case.
    private static final String QUANTITY=RULE_LEFT+"(?<![0-9]"+SPACE+"{1,8})(?<!\\b(?:"+GOV+")"+SPACE+"{1,8})"+UNIT_NUMBER+SPACE+"+(?-i:мА·ч|МГц)"+RULE_RIGHT;
    private static final String TOKEN=QUANTITY+"|"+AGE+"|"+NUMBER_ORDINAL+"|"+CURRENCY+"|"+DATE+"|"+YEAR+"|"+DAY+"|"+ID+"|"+VALUE;
    static final Pattern BOUNDARIES=Pattern.compile(TOKEN+"|\\b(?:"+GOV+") +(?:"+AGE+"|"+NUMBER_ORDINAL+"|"+DAY+"|"+VALUE+")",FLAGS);
    private static final Pattern TOKENS=Pattern.compile(TOKEN,FLAGS);
    private static final Pattern YEAR_FORM=Pattern.compile("(?:("+GOV+") +)?([0-9]{4}) +(года|году|годом|год)",FLAGS);
    private static final Pattern DAY_FORM=Pattern.compile("([0-9]{1,2}) +("+MONTH+")",FLAGS);
    private static final Pattern AGE_FORM=Pattern.compile("([1-9][0-9]?)-("+AGE_END+")",FLAGS);
    private static final Pattern NUMBER_ORDINAL_FORM=Pattern.compile("([1-9][0-9]{0,3})-го"+SPACE+"+(номера)",FLAGS);
    private static final Pattern QUANTITY_FORM=Pattern.compile("("+UNIT_NUMBER+")"+SPACE+"+(мА·ч|МГц)");
    private SourceNumbers45(){}

    public static String project(String raw){
        return project(raw,true);
    }
    static String projectSource(String raw){return project(raw,false);}
    private static String project(String raw,boolean initialisms){
        if(raw.toLowerCase(Locale.ROOT).contains("doi.org/"))throw new IllegalArgumentException("UNSUPPORTED_SOURCE_UNBOUND_URL");
        validateCurrency(raw);
        String text=raw.replace('\n',' ');Matcher m=TOKENS.matcher(text);StringBuilder out=new StringBuilder();int cursor=0;
        while(m.find()){
            append(out,plain(raw,cursor,m.start(),initialisms));
            String token=m.group(),spoken;Matcher year=YEAR_FORM.matcher(token),day=DAY_FORM.matcher(token),age=AGE_FORM.matcher(token),numberOrdinal=NUMBER_ORDINAL_FORM.matcher(token),quantity=QUANTITY_FORM.matcher(token);
            if(quantity.matches())spoken=quantity(quantity.group(1),quantity.group(2));
            else if(age.matches()){
                spoken=compoundAge(Integer.parseInt(age.group(1)),age.group(2));
            }else if(numberOrdinal.matches())spoken=ordinal(Integer.parseInt(numberOrdinal.group(1)))+" "+numberOrdinal.group(2);
            else if(token.charAt(0)=='$'){
                String[] p=token.substring(1).split(" +");String amount=p[0];spoken=number(amount,false,false);
                if(p.length==2){String unit=p[1].equalsIgnoreCase("млн")?"миллион":"миллиард";spoken+=" "+(amount.indexOf(',')>=0?unit+"а":form(integer(amount),unit,unit+"а",unit+"ов"))+" долларов";}
                else spoken+=" "+(amount.indexOf(',')>=0?"доллара":form(integer(amount),"доллар","доллара","долларов"));
            }
            else if(token.matches(DATE))spoken=date(token);
            else if(year.matches()){
                int n=Integer.parseInt(year.group(2));if(n==0)throw new IllegalArgumentException("UNSUPPORTED_SOURCE_YEAR_ZERO");
                String gov=year.group(1)==null?"":year.group(1).toLowerCase(Locale.ROOT),unit=year.group(3).toLowerCase(Locale.ROOT);
                String value=ordinal(n);if(unit.equals("году"))value=ending(value,gov.matches("к|ко|по")?"ому":"ом",gov.matches("к|ко|по")?"ему":"ем");
                else if(unit.equals("годом"))value=ending(value,"ым","им");
                else if(unit.equals("год"))value=ending(value,"ый","ий");
                // After an ordinal year this is singular genitive, not plural годА.
                spoken=(gov.isEmpty()?"":gov+" ")+value+" "+(unit.equals("года")?"г+ода":unit);
            }else if(day.matches()){
                int n=Integer.parseInt(day.group(1)),month=Arrays.asList(MONTH.split("\\|")).indexOf(day.group(2).toLowerCase(Locale.ROOT))+1;
                try{LocalDate.of(2000,month,n);}catch(RuntimeException invalid){throw new IllegalArgumentException("UNSUPPORTED_SOURCE_CALENDAR_DATE");}
                spoken=ordinal(n)+" "+day.group(2).toLowerCase(Locale.ROOT);
            }else if(token.matches(ID))spoken=identifier(token);
            else{
                String sign="";if(token.charAt(0)=='+'||token.charAt(0)=='-'||token.charAt(0)=='−'){
                    char c=token.charAt(0),previous=m.start()==0?' ':text.charAt(m.start()-1);
                    sign=c=='+'?"плюс":c=='−'?"минус":Character.isDigit(previous)?"тире":Character.isLetter(previous)?"дефис":"минус";token=token.substring(1);
                }
                boolean percent=token.endsWith("%"),degrees=token.endsWith("°");if(percent||degrees)token=token.substring(0,token.length()-1).trim();
                boolean genitive=percent&&text.substring(0,m.start()).matches("(?isu).*\\b(?:более|менее|до|от|около|свыше|без) +");
                spoken=number(token,percent,genitive);if(degrees)spoken+=" "+(token.matches("[0-9]+")?form(integer(token),"градус","градуса","градусов"):"градуса");if(!sign.isEmpty())spoken=sign+" "+spoken;
            }
            append(out,spoken);cursor=m.end();
        }
        append(out,plain(raw,cursor,raw.length(),initialisms));return out.toString().replaceAll("\\s+"," ").trim();
    }
    static void validateCurrency(String raw){for(int at=raw.indexOf('$');at>=0;at=raw.indexOf('$',at+1)){
        Matcher amount=Pattern.compile(CURRENCY,FLAGS).matcher(raw).region(at,raw.length());if(!amount.lookingAt())throw new IllegalArgumentException("UNSUPPORTED_SOURCE_CURRENCY_PROFILE");
        int end=amount.end();if(end<raw.length()){char next=raw.charAt(end);if(Character.isLetterOrDigit(next)||next=='%'||".,:/_".indexOf(next)>=0&&end+1<raw.length()&&Character.isDigit(raw.charAt(end+1)))throw new IllegalArgumentException("UNSUPPORTED_SOURCE_CURRENCY_SUFFIX");}
    }}
    private static String identifier(String raw){List<String> words=new ArrayList<>();for(int n=0;n<raw.length();n++){char c=raw.charAt(n);words.add(c=='–'||c=='—'?"тире":SourceSpeechPlan45.literal(String.valueOf(c)));}return String.join(" ",words);}
    private static void append(StringBuilder out,String value){if(value.isEmpty())return;if(out.length()>0)out.append(' ');out.append(value);}
    private static String plain(String raw,int start,int end,boolean initialisms){
        StringBuilder prose=new StringBuilder();for(int i=start;i<end;i++){
            char c=raw.charAt(i);if(c=='-'||c=='–'||c=='—'||c==':')prose.append(separator(raw,i));
            else if(c=='−')prose.append(" минус ");else if(c=='+')prose.append(" плюс ");else if(c=='/')prose.append(" слеш ");else if(c=='№'||c=='#'&&numberMarker(raw,i))prose.append(" номер ");else prose.append(c);
        }
        return initialisms?SileroSpeechInput.projectWithoutContext(prose.toString()):SileroSpeechInput.projectWithoutInitialisms(prose.toString());
    }
    private static boolean numberMarker(String raw,int at){
        if(at>0&&!Character.isWhitespace(raw.charAt(at-1))&&"([{«\"".indexOf(raw.charAt(at-1))<0)return false;
        int end=at+1;while(end<raw.length()&&raw.charAt(end)>='0'&&raw.charAt(end)<='9')end++;
        return end>at+1&&(end==raw.length()||!Character.isLetterOrDigit(raw.charAt(end))&&raw.charAt(end)!='_');
    }
    static String separator(String raw,int at){
        char c=raw.charAt(at);int left=at-1,right=at+1;while(left>=0&&Character.isWhitespace(raw.charAt(left)))left--;while(right<raw.length()&&Character.isWhitespace(raw.charAt(right)))right++;
        boolean numeric=left>=0&&right<raw.length()&&Character.isDigit(raw.charAt(left))&&Character.isDigit(raw.charAt(right));
        boolean symbolic=left>=0&&right<raw.length()&&Character.isLetter(raw.charAt(left))&&Character.isLetter(raw.charAt(right))&&(left==0||!Character.isLetter(raw.charAt(left-1)))&&(right+1==raw.length()||!Character.isLetter(raw.charAt(right+1)));
        if(numeric||symbolic)return c==':'?" двоеточие ":c=='-'?" дефис ":" тире ";
        if(c==':'||c=='–'||c=='—'||at>0&&at+1<raw.length()&&Character.isWhitespace(raw.charAt(at-1))&&Character.isWhitespace(raw.charAt(at+1)))return ", ";
        return " ";
    }
    private static String ordinal(int n){String s=SourceWords45.year(n);return s.substring(0,s.length()-5);}
    static boolean explicitQuantityAtom(String raw){
        int end=raw.length();while(end>0&&".,!?;".indexOf(raw.charAt(end-1))>=0)end--;
        return end>0&&QUANTITY_FORM.matcher(raw.substring(0,end)).matches();
    }
    private static String quantity(String raw,String unit){
        String sign="";if(raw.charAt(0)=='+'||raw.charAt(0)=='-'||raw.charAt(0)=='−'){sign=raw.charAt(0)=='+'?"плюс ":"минус ";raw=raw.substring(1);}
        boolean fractional=raw.indexOf(',')>=0||raw.indexOf('.')>=0;String noun;
        if(unit.equals("МГц"))noun=fractional?"мегагерца":form(integer(raw.replaceAll(SPACE,"")),"мегагерц","мегагерца","мегагерц");
        else noun="миллиампер "+(fractional?"часа":form(integer(raw.replaceAll(SPACE,"")),"час","часа","часов"));
        // Spoken words are final atom output, never fed back through project().
        return sign+number(raw,false,false)+" "+noun;
    }
    static String compoundAge(int years,String suffix){
        String prefix=SourceWords45.genitive(years).replace(" ","");
        if(years>=90)prefix="девяносто"+prefix.substring("девяноста".length());
        if(prefix.endsWith("одного"))prefix=prefix.substring(0,prefix.length()-6)+"одно";
        return prefix+suffix.toLowerCase(Locale.ROOT);
    }
    private static String ending(String gen,String hard,String soft){if(gen.endsWith("ого"))return gen.substring(0,gen.length()-3)+hard;if(gen.endsWith("его"))return gen.substring(0,gen.length()-3)+soft;throw new IllegalArgumentException("UNSUPPORTED_SOURCE_ORDINAL_CASE");}
    private static String date(String raw){
        String[] p=raw.split("\\.");int day=Integer.parseInt(p[0]),month=Integer.parseInt(p[1]),year=Integer.parseInt(p[2]);
        try{LocalDate.of(year,month,day);if(year<1)throw new IllegalArgumentException();}catch(RuntimeException invalid){throw new IllegalArgumentException("UNSUPPORTED_SOURCE_CALENDAR_DATE");}
        return ordinal(day)+" "+MONTH.split("\\|")[month-1]+" "+SourceWords45.year(year);
    }
    private static String number(String raw,boolean percent,boolean genitive){
        String clean=raw.replaceAll("[ \\u00a0\\u202f]","");String spoken;
        if(clean.indexOf(',')>=0){
            String[] p=clean.split(",",-1);if(p.length!=2||p[1].length()>6)throw new IllegalArgumentException("UNSUPPORTED_SOURCE_DECIMAL_PRECISION");
            long whole=integer(p[0]),fraction=integer(p[1]);String[] singular={"","десятая","сотая","тысячная","десятитысячная","стотысячная","миллионная"};
            String[] plural={"","десятых","сотых","тысячных","десятитысячных","стотысячных","миллионных"};int places=p[1].length();
            if(genitive){if(whole>9999||fraction>9999)throw new IllegalArgumentException("UNSUPPORTED_SOURCE_GENITIVE_DECIMAL_BOUND");spoken=feminineGen((int)whole)+(one(whole)?" целой ":" целых ")+feminineGen((int)fraction)+" "+(one(fraction)?singular[places].substring(0,singular[places].length()-2)+"ой":plural[places]);}
            else spoken=feminine(whole)+(one(whole)?" целая ":" целых ")+feminine(fraction)+" "+(one(fraction)?singular[places]:plural[places]);
            if(percent)spoken+=" процента";
        }else if(clean.indexOf('.')>=0){
            String[] p=clean.split("\\.",-1);if(p.length!=2)throw new IllegalArgumentException("UNSUPPORTED_SOURCE_DOTTED_NUMBER");spoken=cardinal(integer(p[0]))+" точка "+digits(p[1]);if(percent)spoken+=" процента";
        }else{
            long n=integer(clean);spoken=clean.length()>1&&clean.charAt(0)=='0'?digits(clean):genitive&&n<=9999?SourceWords45.genitive((int)n):cardinal(n);
            if(percent)spoken+=genitive?(one(n)?" процента":" процентов"):" "+form(n,"процент","процента","процентов");
        }
        return spoken;
    }
    private static long integer(String raw){if(!raw.matches("[0-9]{1,12}"))throw new IllegalArgumentException("UNSUPPORTED_SOURCE_INTEGER_LENGTH");return Long.parseLong(raw);}
    private static String digits(String raw){List<String> words=new ArrayList<>();for(int n=0;n<raw.length();n++)words.add(SourceWords45.cardinal(raw.charAt(n)-'0'));return String.join(" ",words);}
    private static boolean one(long n){return n%100!=11&&n%10==1;}
    private static String form(long n,String one,String few,String many){return n%100>=11&&n%100<=14?many:n%10==1?one:n%10>=2&&n%10<=4?few:many;}
    private static String feminine(long n){String s=cardinal(n);if(n%100!=11&&n%10==1)return s.substring(0,s.length()-4)+"одна";if(n%100!=12&&n%10==2)return s.substring(0,s.length()-3)+"две";return s;}
    private static String feminineGen(int n){String s=SourceWords45.genitive(n);return one(n)?s.substring(0,s.length()-6)+"одной":s;}
    private static String cardinal(long n){
        if(n<1000)return SourceWords45.cardinal((int)n);List<String> words=new ArrayList<>();long[] scales={1000000000L,1000000L,1000L};String[][] nouns={{"миллиард","миллиарда","миллиардов"},{"миллион","миллиона","миллионов"},{"тысяча","тысячи","тысяч"}};
        for(int i=0;i<scales.length;i++){int part=(int)(n/scales[i]);n%=scales[i];if(part==0)continue;String value=i==2?feminine(part):SourceWords45.cardinal(part);words.add(value+" "+form(part,nouns[i][0],nouns[i][1],nouns[i][2]));}
        if(n>0)words.add(SourceWords45.cardinal((int)n));return String.join(" ",words);
    }
}

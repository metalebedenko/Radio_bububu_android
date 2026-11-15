package ru.bububu.radio.sources;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Closed literal table from the rejected atoms in the saved eighteen starter bodies. */
public final class SourceGlyphs45 {
    public static final String REVISION="source45-observed-glyph-prose-v2";
    private static final Map<Character,String> TABLE=Map.ofEntries(
        Map.entry('&',"амперсанд"),Map.entry('‘',"левая одинарная кавычка"),Map.entry('’',"правая одинарная кавычка"),Map.entry('\'',"апостроф"),
        Map.entry('é',"латинская е с акутом"),Map.entry('É',"латинская прописная е с акутом"),Map.entry('ï',"латинская и с диерезисом"),Map.entry('ß',"эсцет"),
        Map.entry('Δ',"греческая дельта"),Map.entry('…',"многоточие"),Map.entry('℗',"знак охраны прав на фонограмму"),Map.entry('©',"знак авторского права"),
        Map.entry('×',"знак умножения"),Map.entry('„',"нижняя двойная кавычка"),Map.entry('“',"левая двойная кавычка"),Map.entry('”',"правая двойная кавычка"),
        Map.entry('→',"стрелка вправо"),Map.entry('\u0301',"знак ударения"),Map.entry('*',"звёздочка"),Map.entry('=',"равно"),Map.entry('·',"средняя точка"));
    private static final Map<String,String> POWERS=Map.of("10⁻¹⁹","десять верхний индекс минус девятнадцать","10⁻¹¹","десять верхний индекс минус одиннадцать","10⁻¹²","десять верхний индекс минус двенадцать");
    private static final String POWER_FORM = "(?:" + String.join("|", POWERS.keySet()) + ")";
    // A lookahead leaves the second operand available for a following range.
    static final Pattern POWER_RANGE = Pattern.compile(POWER_FORM + "\\s*([–—])\\s*(?=" + POWER_FORM + ")");
    private SourceGlyphs45(){}
    static Map<Character,String> observedTable(){return TABLE;}
    public static boolean needed(String raw){for(int n=0;n<raw.length();n++)if(TABLE.containsKey(raw.charAt(n))||superscript(raw.charAt(n)))return true;return false;}
    private static boolean superscript(char c){return c=='⁻'||c=='¹'||c=='²'||c=='⁹';}
    static List<SourceSpeechPlan45.Atom> atoms(String raw,int base){
        return atoms(raw,base,true);
    }
    static List<SourceSpeechPlan45.Atom> atomsSource(String raw,int base){return atoms(raw,base,false);}
    private static List<SourceSpeechPlan45.Atom> atoms(String raw,int base,boolean initialisms){
        SourceNumbers45.validateCurrency(raw);
        Map<Integer,Integer> numeric=new HashMap<>();Matcher numbers=SourceNumbers45.BOUNDARIES.matcher(raw.replace('\n',' '));while(numbers.find())numeric.put(numbers.start(),numbers.end());
        List<SourceSpeechPlan45.Atom> out=new ArrayList<>();int at=0;
        while(at<raw.length()){
            if(raw.startsWith("бо́льшую",at)){out.add(new SourceSpeechPlan45.Atom(base+at,base+at+8,"большую, знак ударения после буквы о",REVISION+":observed-accent-word"));at+=8;continue;}
            String power=null;for(String candidate:POWERS.keySet())if(raw.startsWith(candidate,at)){power=candidate;break;}
            if(power!=null){out.add(new SourceSpeechPlan45.Atom(base+at,base+at+power.length(),POWERS.get(power),REVISION+":upper-index"));at+=power.length();continue;}
            Integer end=numeric.get(at);if(end!=null){String number=raw.substring(at,end);out.add(new SourceSpeechPlan45.Atom(base+at,base+end,initialisms?SourceNumbers45.project(number):SourceNumbers45.projectSource(number),SourceNumbers45.REVISION));at=end;continue;}
            char c=raw.charAt(at);if(Character.isWhitespace(c)){at++;continue;}
            if(superscript(c))throw new IllegalArgumentException("UNSUPPORTED_SOURCE_SUPERSCRIPT_FORM");
            if(cyrillic(c)){int until=at+1;while(until<raw.length()&&cyrillic(raw.charAt(until)))until++;if(until-at<=32){String word=raw.substring(at,until);out.add(new SourceSpeechPlan45.Atom(base+at,base+until,initialisms?SourceInitialisms45.expand(word):word,REVISION+":cyrillic-word"));at=until;continue;}}
            String spoken=TABLE.get(c);if(spoken==null){if(c=='–'||c=='—'||c=='-'||c==':')spoken=SourceNumbers45.separator(raw,at);else if(c=='«')spoken="левая угловая кавычка";else if(c=='»')spoken="правая угловая кавычка";else if(c=='"')spoken="двойная кавычка";else try{spoken=SourceSpeechPlan45.literal(String.valueOf(c));}catch(IllegalArgumentException unknown){throw new IllegalArgumentException("UNSUPPORTED_SOURCE_LITERAL_U"+Integer.toHexString(c));}}
            out.add(new SourceSpeechPlan45.Atom(base+at,base+at+1,spoken,REVISION));at++;
        }
        return out;
    }
    private static boolean cyrillic(char c){char lower=Character.toLowerCase(c);return lower>='а'&&lower<='я'||lower=='ё';}
}

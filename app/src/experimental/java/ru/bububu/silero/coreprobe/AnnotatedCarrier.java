package ru.bububu.silero.coreprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.regex.*;

/** C194 carrier: mechanical Android API26 compatibility only; no neural frontend or inference dependency. */
public final class AnnotatedCarrier {
    public static final String[] ORDER={"sequence","speaker_ids","sr","symb_durs","durs_rate","pitch_coefs","gt_durs","gt_pitch","device","accent_word","question","type_ids","focus_mask","emphasis"};
    public enum Mode { ORIGINAL_EMPTY_MAP, ANDROID_NONE_ONLY }
    public static final class Rejected extends IllegalArgumentException {
        public final String code;
        Rejected(String code){super(code);this.code=code;}
    }
    public static final class Options {
        public final Mode mode; public final Map<Long,Long> durations;
        public final boolean ssml,focus,emphasis;
        public Options(Mode mode,Map<Long,Long> durations,boolean ssml,boolean focus,boolean emphasis){this.mode=mode;this.durations=durations==null?Collections.emptyMap():copyMap(durations);this.ssml=ssml;this.focus=focus;this.emphasis=emphasis;}
        public static Options plain(Mode mode){return new Options(mode,Collections.emptyMap(),false,false,false);}
    }
    public static final class Arg {
        public final String kind,dtype,scalar; public final int[] shape; public final byte[] bytes;
        private Arg(String kind,String dtype,int[] shape,String scalar,byte[] bytes){this.kind=kind;this.dtype=dtype;this.shape=shape.clone();this.scalar=scalar;this.bytes=bytes.clone();}
        static Arg scalar(String kind,String value){return new Arg(kind,"-",new int[0],value,new byte[0]);}
        static Arg longs(long[] values,int...shape){ByteBuffer b=ByteBuffer.allocate(Math.multiplyExact(values.length,8)).order(ByteOrder.LITTLE_ENDIAN);for(long x:values)b.putLong(x);return new Arg("tensor","int64",shape,"-",b.array());}
        static Arg ones(int length){ByteBuffer b=ByteBuffer.allocate(Math.multiplyExact(length,4)).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<length;i++)b.putFloat(1f);return new Arg("tensor","float32",new int[]{1,length},"-",b.array());}
    }
    private static final Pattern SENTENCE_BREAK=Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern WORD=Pattern.compile("[а-яёa-z]+",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private static final Pattern TAG=Pattern.compile("(?:[,\\s]+(?:правда|верно|да|не\\s+так\\s+ли|не\\s+правда\\s+ли|разве\\s+не\\s+так|ведь\\s+так)\\s*\\?$|[,\\s]+ведь\\s*\\?$|[,\\s]+а\\s*\\?$)",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private static final Set<String> WH=set(("кто кого кому кем ком что чего чему чем чём чё чо где куда откуда когда почему зачем как почём отчего насколько сколько скольких скольким сколькими какой какая какое какие какого каких какому каким какими каком какую чей чья чьё чье чьи чьего чьей чьих чьему чьим чьими чьём чьем чью который которая которое которые которого которой которых которому которым которыми котором которую каков какова каково каковы").split(" "));
    private static final Set<String> FILLERS=set("а","ну","и","так","вот","слышь","слушай","скажите","скажи","пожалуйста","вобще","вообще","типа","короче");
    private final Map<Character,Integer> dictionary;
    private final Map<Integer,Character> inverse;
    public AnnotatedCarrier(Map<Character,Integer> symbols){
        if(symbols.size()!=47||new HashSet<>(symbols.values()).size()!=47)throw new Rejected("DICTIONARY");
        for(int i=0;i<47;i++)if(!symbols.containsValue(i))throw new Rejected("DICTIONARY");
        if(!Objects.equals(symbols.get('|'),2)||!Objects.equals(symbols.get('~'),1)||!Objects.equals(symbols.get('+'),4)||!Objects.equals(symbols.get('ё'),43))throw new Rejected("DICTIONARY");
        dictionary=copyMap(symbols);Map<Integer,Character> reverse=new HashMap<>();symbols.forEach((ch,id)->reverse.put(id,ch));inverse=copyMap(reverse);
    }
    public List<Arg> encode(String annotated,Options options){
        if(options==null||options.mode==null)throw new Rejected("OPTIONS");
        if(options.ssml||options.focus||options.emphasis)throw new Rejected("UNSUPPORTED_FEATURE");
        if(!options.durations.isEmpty())throw new Rejected("NONEMPTY_DURATION_MAP");
        if(annotated==null||strip(annotated).isEmpty())throw new Rejected("EMPTY");
        if(annotated.length()>1000)throw new Rejected("LENGTH");
        // Detect digits/invalid UTF-16 before any symbol rejection. No content-removing filter exists.
        for(int i=0;i<annotated.length();i++){
            char c=annotated.charAt(i);
            if(Character.isHighSurrogate(c)){
                if(i+1>=annotated.length()||!Character.isLowSurrogate(annotated.charAt(i+1)))throw new Rejected("MALFORMED_UTF16");
                int cp=Character.toCodePoint(c,annotated.charAt(++i));if(Character.isDigit(cp))throw new Rejected("RAW_DIGIT");
            }else{if(Character.isLowSurrogate(c))throw new Rejected("MALFORMED_UTF16");if(Character.isDigit(c))throw new Rejected("RAW_DIGIT");}
        }
        for(int i=0;i<annotated.length();i++){
            char c=annotated.charAt(i);
            if(c=='|'||c=='~'||c=='_')throw new Rejected("RESERVED_TOKEN");
            if(c=='<'||c=='>'||c=='*'||c=='^')throw new Rejected("MARKUP");
            if(!dictionary.containsKey(c))throw new Rejected("UNSUPPORTED_SYMBOL");
            if(c=='+'&&(i+1==annotated.length()||"аеёиоуыэюя".indexOf(annotated.charAt(i+1))<0))throw new Rejected("MALFORMED_STRESS");
        }
        int n=annotated.length()+2;long[] sequence=new long[n];sequence[0]=dictionary.get('|');sequence[n-1]=dictionary.get('~');
        for(int i=0;i<annotated.length();i++)sequence[i+1]=dictionary.get(annotated.charAt(i));
        long[] types=sentenceTypes(annotated);Arg none=Arg.scalar("none","None");
        return Collections.unmodifiableList(Arrays.asList(Arg.longs(sequence,1,n),Arg.longs(new long[]{3},1),Arg.scalar("int","24000"),
                options.mode==Mode.ORIGINAL_EMPTY_MAP?Arg.scalar("empty-int-map","{}"):none,
                Arg.ones(n),Arg.ones(n),none,none,Arg.scalar("str","cpu"),Arg.scalar("int","-1"),
                Arg.scalar("bool","false"),Arg.longs(types,1,n),Arg.longs(new long[n],1,n),none));
    }
    public String decode(Arg sequence){
        if(!sequence.kind.equals("tensor")||!sequence.dtype.equals("int64")||sequence.bytes.length%8!=0)throw new Rejected("SEQUENCE");
        ByteBuffer b=ByteBuffer.wrap(sequence.bytes).order(ByteOrder.LITTLE_ENDIAN);StringBuilder text=new StringBuilder();
        while(b.hasRemaining()){long value=b.getLong();Character c=value>=0&&value<=Integer.MAX_VALUE?inverse.get((int)value):null;if(c==null)throw new Rejected("TOKEN_ID");text.append(c);}
        return text.toString();
    }
    private static long[] sentenceTypes(String text){
        List<Integer> ends=new ArrayList<>();Matcher breaks=SENTENCE_BREAK.matcher(text);while(breaks.find())ends.add(breaks.end());ends.add(text.length());
        long[] types=new long[text.length()+2];int start=0;long first=0,last=0;
        for(int index=0;index<ends.size();index++){
            int end=ends.get(index);String sentence=strip(text.substring(start,end));long type=classify(sentence);if(index==0)first=type;last=type;
            // Separator whitespace belongs to the preceding sentence, including in token type IDs.
            for(int j=start;j<end;j++)types[j+1]=type;start=end;
        }
        types[0]=first;types[types.length-1]=last;return types;
    }
    private static int classify(String sentence){
        String s=strip(sentence);
        while(!s.isEmpty()&&"\"«“„".indexOf(s.charAt(0))>=0)s=strip(s.substring(1));
        while(!s.isEmpty()&&"\"»”’".indexOf(s.charAt(s.length()-1))>=0)s=strip(s.substring(0,s.length()-1));
        if(s.endsWith("?!")||s.endsWith("?..")||s.endsWith("?..."))while(s.endsWith(".")||s.endsWith("!"))s=s.substring(0,s.length()-1);
        if(s.endsWith("?")){
            String q=strip(s.replace("+","").replace('ё','е').replace('Ё','Е').replaceAll("\\s+"," "));
            if(TAG.matcher(q).find())return 4;
            Matcher words=WORD.matcher(q.toLowerCase(Locale.ROOT));int scanned=0,content=0;
            while(words.find()&&scanned++<8){String w=words.group();if(FILLERS.contains(w))continue;if(WH.contains(w))return 1;if(++content==4)break;}
            return Pattern.compile("\\bили\\b").matcher(q).find()?3:2;
        }
        return s.endsWith("!")?5:0;
    }
    private static <K,V> Map<K,V> copyMap(Map<K,V> map){return Collections.unmodifiableMap(new HashMap<>(map));}
    private static Set<String> set(String... values){return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));}
    private static String strip(String value){
        int first=0,last=value.length();
        while(first<last&&Character.isWhitespace(value.codePointAt(first)))first+=Character.charCount(value.codePointAt(first));
        while(last>first&&Character.isWhitespace(value.codePointBefore(last)))last-=Character.charCount(value.codePointBefore(last));
        return value.substring(first,last);
    }

}

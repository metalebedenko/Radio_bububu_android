package ru.bububu.radio.sources;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.TextSegments;
import ru.bububu.radio.core.PlayerController.SpeechSentence;
import ru.bububu.radio.core.PlayerController.SpeechUnit;
import ru.bububu.radio.models.SileroSpeechInput;

/** One transient canonical-sentence plan shared by fragment admission, synthesis and hashes. */
public final class SourceSpeechPlan45 {
    public static final String RUNTIME_REVISION="source-spoken-runtime60-news-pronunciation-v1";
    public static String runtimeStamp(){return RUNTIME_REVISION+":"+SourcePronunciation48.stamp();}
    private static final Pattern NUMERIC=SourceNumbers45.BOUNDARIES;
    private static final String CLOCK_BODY="(?:[01][0-9]|2[0-3]):[0-5][0-9]";
    private static final String CLOCK_LEFT="(?<![\\p{L}\\p{M}\\p{N}_:+/\\-−–—=<>*^])",CLOCK_RIGHT="(?![\\p{L}\\p{M}\\p{N}_:+/\\-−–—=<>*^])";
    private static final Pattern CLOCK_RANGE=Pattern.compile(CLOCK_LEFT+"("+CLOCK_BODY+")([-–—])("+CLOCK_BODY+")"+CLOCK_RIGHT),CLOCK=Pattern.compile(CLOCK_LEFT+CLOCK_BODY+CLOCK_RIGHT);
    private static final Pattern TIME_CONTEXT=Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(?:мск|по местному времени)(?![\\p{L}\\p{N}_])");
    private static final Pattern SPECIAL=Pattern.compile("https?://[^\\s<>\"«»]+|\\[([1-9][0-9]{0,3})(?:–([1-9][0-9]{0,3}))?\\]",Pattern.CASE_INSENSITIVE);
    private static final String[] LETTERS={"эй","би","си","ди","и","эф","джи","эйч","ай","джей","кей","эл","эм","эн","оу","пи","кью","ар","эс","ти","ю","ви","дабл ю","экс","уай","зед"};
    private static final String RUS="абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
    private static final String[] RUS_LETTERS={"а","бэ","вэ","гэ","дэ","е","ё","жэ","зэ","и","и краткое","ка","эл","эм","эн","о","пэ","эр","эс","тэ","у","эф","ха","цэ","че","ша","ща","твёрдый знак","ы","мягкий знак","э","ю","я"};
    // Current +two future sentence owners. No unbounded body or per-fragment plan cache.
    private static final LinkedHashMap<Article.Sentence,Plan> ACTIVE=new LinkedHashMap<>(4,0.75f,true);
    private SourceSpeechPlan45(){}
    public static boolean source45(Article article){return article.sourceVersion!=null&&SourceArticleFramer.VERSION45.equals(article.sourceVersion.framingVersion);}
    public static synchronized Plan sentence(SpeechSentence sentence){
        if(!source45(sentence.article))throw new IllegalArgumentException("SOURCE45_BINDING_REQUIRED");SourcePronunciation48.dictionary();Article.Sentence key=sentence.article.sentences.get(sentence.index);Plan plan=ACTIVE.get(key);if(plan==null||!plan.revision.equals(runtimeStamp())){plan=compileSentence(sentence,key);ACTIVE.put(key,plan);while(ACTIVE.size()>3)ACTIVE.remove(ACTIVE.keySet().iterator().next());}return plan;
    }
    /** Reproject a saved v2 prefix; its canonical text, identity and sentence offsets never change. */
    private static Plan compileSentence(SpeechSentence owner,Article.Sentence sentence){
        SourceVersionRecord version=owner.article.sourceVersion;String raw=sentence.text;
        String next=owner.index+1<owner.article.sentences.size()?owner.article.sentences.get(owner.index+1).text:"";
        if(sentence.start>=version.bodyStart)return compile(raw,new SourcePronunciation48.Context(owner.article.text,sentence.start,true,next));
        TreeSet<Integer> ends=new TreeSet<>();int label=version.sourceLabel.length(),title=label+2;
        if(!"public_preview_text".equals(version.method)&&!version.providerTitle.isEmpty())title+=version.providerTitle.length();
        for(int absolute:new int[]{label,title,version.bodyStart})if(absolute>sentence.start&&absolute<sentence.start+raw.length())ends.add(absolute-sentence.start);
        ends.add(raw.length());List<Atom> atoms=new ArrayList<>(),tokens=new ArrayList<>();Set<Integer> phrases=new TreeSet<>();int from=0;
        for(int until:ends){String section=raw.substring(from,until);if(!section.trim().isEmpty()){
            int absolute=sentence.start+from;Plan part=compile(section,new SourcePronunciation48.Context(owner.article.text,absolute,absolute>=version.bodyStart,next));int offset=from;
            for(Atom a:part.atoms)atoms.add(new Atom(offset+a.start,offset+a.end,a.spoken,a.rule));
            for(Atom a:part.tokens)tokens.add(new Atom(offset+a.start,offset+a.end,a.spoken,a.rule));
            if(sentence.start+from<version.bodyStart){int last=atoms.size()-1;Atom a=atoms.get(last);String spoken=a.spoken.trim();if(!spoken.matches(".*[.!?]$"))spoken+=".";atoms.set(last,new Atom(a.start,a.end,spoken,a.rule+":metadata-phrase"));phrases.add(a.end);}
        }from=until;}
        return new Plan(raw,atoms,tokens,phrases);
    }
    public static String project(SpeechUnit unit){if(!source45(unit.sentence.article)){
        int[] range=span(unit);
        return SileroSpeechInput.forSentence(unit.sentence).project(range[0],range[1]);
    }Plan plan=sentence(unit.sentence);int[] span=span(unit);return plan.project(span[0],span[1]);}
    public static String hash(SpeechUnit unit){if(!source45(unit.sentence.article))return SourceLimits.sha256(project(unit));int[] range=span(unit);return SourceLimits.snapshotVersion(runtimeStamp(),SourceNumbers45.REVISION,unit.sentence.article.version,Integer.toString(unit.sentence.index),Integer.toString(range[0]),Integer.toString(range[1]),project(unit));}
    private static int[] span(SpeechUnit unit){String text=unit.sentence.text();int cursor=0;for(int n=0;n<=unit.fragment;n++){while(cursor<text.length()&&Character.isWhitespace(text.charAt(cursor)))cursor++;String fragment=unit.fragments.get(n);if(!text.startsWith(fragment,cursor))throw new IllegalArgumentException("SOURCE45_FRAGMENT_BINDING");int start=cursor;cursor+=fragment.length();if(n==unit.fragment)return new int[]{start,cursor};}throw new IllegalArgumentException("SOURCE45_FRAGMENT_BINDING");}
    public static final class Atom {
        public final int start,end;public final String spoken,rule;
        Atom(int start,int end,String spoken,String rule){this.start=start;this.end=end;this.spoken=spoken;this.rule=rule;}
    }
    public static final class Parts {
        public final List<String> raw,spoken;public final long candidateChecks;
        Parts(List<String> raw,List<String> spoken,long checks){this.raw=Collections.unmodifiableList(raw);this.spoken=Collections.unmodifiableList(spoken);candidateChecks=checks;}
    }
    public static final class Plan {
        public final String canonical,hash,revision;public final List<Atom> atoms,tokens;private final Set<Integer> phraseEnds;
        Plan(String raw,List<Atom> atoms,List<Atom> tokens){this(raw,atoms,tokens,Collections.emptySet());}
        Plan(String raw,List<Atom> atoms,List<Atom> tokens,Set<Integer> phraseEnds){this.phraseEnds=Collections.unmodifiableSet(new TreeSet<>(phraseEnds));canonical=raw;revision=runtimeStamp();this.atoms=Collections.unmodifiableList(atoms);this.tokens=Collections.unmodifiableList(tokens);List<String> fields=new ArrayList<>();fields.add(revision);fields.add(SourceNumbers45.REVISION);fields.add(raw);for(Atom a:atoms){fields.add(a.rule);fields.add(a.start+":"+a.end);fields.add(a.spoken);}hash=SourceLimits.snapshotVersion(fields.toArray(new String[0]));}
        public String project(int start,int end){
            if(start<0||end<start||end>canonical.length())throw new IllegalArgumentException("SOURCE45_PROJECTION_RANGE");int at=first(start);StringBuilder out=new StringBuilder();int previous=start;
            for(;at<atoms.size();at++){Atom a=atoms.get(at);if(a.start>=end)break;if(a.start<start||a.end>end)throw new IllegalArgumentException("SOURCE45_ATOM_CUT");append(out,a.spoken,whitespace(previous,a.start));previous=a.end;}
            return out.toString();
        }
        private int first(int start){int lo=0,hi=atoms.size();while(lo<hi){int mid=(lo+hi)>>>1;if(atoms.get(mid).end<=start)lo=mid+1;else hi=mid;}return lo;}
        private boolean whitespace(int a,int b){for(int n=a;n<b;n++)if(Character.isWhitespace(canonical.charAt(n)))return true;return false;}
        public Parts parts(int maxChars,Consumer<String> frontend){
            if(maxChars<2||maxChars>512)throw new IllegalArgumentException("SOURCE45_RAW_PART_BOUND");List<String> raw=new ArrayList<>(),spoken=new ArrayList<>();int first=0;long checks=0;
            while(first<atoms.size()){
                int best=-1,clause=-1;String bestText=null,clauseText=null,reason="UNSUPPORTED_SOURCE_ATOM";StringBuilder projected=new StringBuilder();int previous=atoms.get(first).start;
                for(int n=first;n<atoms.size();n++){
                    Atom atom=atoms.get(n);if(atom.end-atoms.get(first).start>maxChars)break;append(projected,atom.spoken,whitespace(previous,atom.start));previous=atom.end;if(projected.length()>256)break;checks++;
                    try{String candidate=projected.toString();frontend.accept(candidate);best=n;bestText=candidate;if(TextSegments.clauseBoundary(canonical,atoms.get(first).start,atom.end)){clause=n;clauseText=candidate;}}catch(IllegalArgumentException unsupported){if(unsupported.getMessage()!=null)reason=unsupported.getMessage();}
                    if(phraseEnds.contains(atom.end))break;
                }
                if(best<first)throw new IllegalArgumentException(reason);if(best<atoms.size()-1&&clause>=first){best=clause;bestText=clauseText;}int start=atoms.get(first).start,end=atoms.get(best).end;raw.add(canonical.substring(start,end));spoken.add(bestText);first=best+1;
            }
            TextSegments.checkedFragments(canonical,raw,maxChars);return new Parts(raw,spoken,checks);
        }
    }
    private static void append(StringBuilder out,String text,boolean gap){if(out.length()>0&&!text.isEmpty()&&(gap||Character.isLetterOrDigit(out.charAt(out.length()-1))&&Character.isLetterOrDigit(text.charAt(0))))out.append(' ');out.append(text);}
    private static final class Special {final int start,end;final String rule,spoken;Special(int start,int end,String rule,String spoken){this.start=start;this.end=end;this.rule=rule;this.spoken=spoken;}}
    public static Plan compile(String text){
        return compile(text,null);
    }
    private static Plan compile(String text,SourcePronunciation48.Context context){
        if(text==null||text.isEmpty()||text.length()>SourceLimits.MAX_SOURCE_SPOKEN_UTF16)throw new IllegalArgumentException("SOURCE45_SENTENCE_BOUND");List<Special> specials=new ArrayList<>();Matcher matcher=SPECIAL.matcher(text);
        while(matcher.find()){
            String raw=matcher.group(),spoken,rule;if(raw.toLowerCase(Locale.ROOT).startsWith("http")){rule="literal-url-v1";spoken=url(raw);}else{int a=Integer.parseInt(matcher.group(1));if(matcher.group(2)==null){rule="citation-single-v1";spoken="примечание "+SourceWords45.cardinal(a);}else{int b=Integer.parseInt(matcher.group(2));if(a>b)throw new IllegalArgumentException("UNSUPPORTED_SOURCE_CITATION_RANGE");rule="citation-range-v1";spoken="примечания с "+SourceWords45.genitive(a)+" по "+SourceWords45.cardinal(b);}}
            if(!rule.equals("literal-url-v1")&&(raw.length()>512||spoken.length()>256))throw new IllegalArgumentException("UNSUPPORTED_SOURCE_ATOM_BOUND");specials.add(new Special(matcher.start(),matcher.end(),rule,spoken));
        }
        if(context!=null){for(SourcePronunciation48.Replacement replacement:SourcePronunciation48.source(text,context)){boolean overlap=false;for(Special existing:specials)if(existing.start<replacement.end&&existing.end>replacement.start){overlap=true;break;}if(!overlap)specials.add(new Special(replacement.start,replacement.end,replacement.rule,replacement.spoken));}specials.sort(Comparator.comparingInt(s->s.start));}
        if(TIME_CONTEXT.matcher(text).find()){
            Matcher ranges=CLOCK_RANGE.matcher(text);while(ranges.find())if(clockPrefix(text,ranges.start())&&!overlaps(specials,ranges.start(),ranges.end())){addClock(specials,text,ranges.start(1),ranges.end(1));specials.add(new Special(ranges.start(2),ranges.end(2),"source49-clock-range-v1","до"));addClock(specials,text,ranges.start(3),ranges.end(3));}
            Matcher clocks=CLOCK.matcher(text);while(clocks.find())if(clockPrefix(text,clocks.start())&&!overlaps(specials,clocks.start(),clocks.end()))addClock(specials,text,clocks.start(),clocks.end());specials.sort(Comparator.comparingInt(s->s.start));
        }
        BitSet reserved=new BitSet();
        for (Special special:specials) reserved.set(special.start,special.end);
        for (ContextualSpeech.Reading reading:ContextualSpeech.scan(text)) {
            int blocked=reserved.nextSetBit(reading.start);
            if (blocked<0 || blocked>=reading.end) specials.add(new Special(reading.start,reading.end,reading.kind,reading.spoken));
        }
        specials.sort(Comparator.comparingInt(s->s.start));
        List<int[]> protectedSpans=new ArrayList<>();for(Special s:specials)protectedSpans.add(new int[]{s.start,s.end});Matcher numeric=NUMERIC.matcher(text.replace('\n',' '));int special=0;
        while(numeric.find()){while(special<specials.size()&&specials.get(special).end<=numeric.start())special++;if(special<specials.size()&&specials.get(special).start<numeric.end())continue;protectedSpans.add(new int[]{numeric.start(),numeric.end()});}
        boolean[] inside=new boolean[text.length()+1],ends=new boolean[text.length()+1];
        for(int[] span:protectedSpans)Arrays.fill(inside,span[0]+1,span[1],true);
        for(int at=1;at<=text.length();at++)if((at==text.length()||Character.isWhitespace(text.charAt(at)))&&!inside[at]&&(at==text.length()||!Character.isLowSurrogate(text.charAt(at))))ends[at]=true;
        // Keep each numeric operand/governor intact, but let the existing frontend
        // partition a range whose spoken sign and value are separate homographs.
        for(int at=1;at+1<text.length();at++)if((text.charAt(at)=='–'||text.charAt(at)=='—')
                &&text.charAt(at-1)>='0'&&text.charAt(at-1)<='9'&&text.charAt(at+1)>='0'&&text.charAt(at+1)<='9'
                &&!inside[at]&&!inside[at+1]){ends[at]=true;ends[at+1]=true;}
        BitSet powerDashes = new BitSet();
        if (text.indexOf('⁻') >= 0) {
            Matcher ranges = SourceGlyphs45.POWER_RANGE.matcher(text);
            while (ranges.find()) {
                int dash = ranges.start(1);
                if (inside[dash] || inside[dash + 1]) continue;
                powerDashes.set(dash);
                ends[dash] = ends[dash + 1] = true;
            }
        }
        List<Atom> tokens=new ArrayList<>();for(Special sp:specials){ends[sp.start]=true;ends[sp.end]=true;tokens.add(new Atom(sp.start,sp.end,sp.spoken,sp.rule));}ends[text.length()]=true;
        List<Atom> atoms=new ArrayList<>();int start=0;special=0;
        for(int stop=0;stop<ends.length;stop++){if(!ends[stop])continue;while(start<stop&&Character.isWhitespace(text.charAt(start)))start++;int end=stop;while(end>start&&Character.isWhitespace(text.charAt(end-1)))end--;if(end<=start){start=stop;continue;}while(special<specials.size()&&specials.get(special).end<=start)special++;String spoken,rule;
            if(special<specials.size()&&specials.get(special).start==start&&specials.get(special).end==end){
                Special token=specials.get(special);if(token.rule.equals("literal-url-v1")){for(int c=start;c<end;c++)atoms.add(new Atom(c,c+1,url(text.substring(c,c+1)),"literal-url-char-v1"));start=stop;continue;}spoken=token.spoken;rule=token.rule;
            }
            else if (powerDashes.get(start)) {
                spoken = "тире";
                rule = "source45-power-range-v1";
            }
            else{String raw=text.substring(start,end);if(raw.indexOf('[')>=0||raw.indexOf(']')>=0||raw.indexOf('\\')>=0||raw.indexOf('^')>=0)throw new IllegalArgumentException("UNSUPPORTED_SOURCE_NOTATION");if(SourceGlyphs45.needed(raw)&&!SourceNumbers45.explicitQuantityAtom(raw)){atoms.addAll(context==null?SourceGlyphs45.atoms(raw,start):SourceGlyphs45.atomsSource(raw,start));start=stop;continue;}// A whitespace atom still needs the original neighbours to distinguish prose from signs.
                if(raw.length()==1&&"-–—:".indexOf(raw.charAt(0))>=0){spoken=SourceNumbers45.separator(text,start).trim();if(spoken.isEmpty())spoken=",";rule="source45-context-separator-v1";}
                else{spoken=context==null?SourceNumbers45.project(raw):SourceNumbers45.projectSource(raw);rule=SourceNumbers45.REVISION;}}
            if(spoken.isEmpty())throw new IllegalArgumentException("SOURCE45_EMPTY_ATOM");atoms.add(new Atom(start,end,spoken,rule));start=stop;
        }
        if(atoms.isEmpty())throw new IllegalArgumentException("SOURCE45_EMPTY_SENTENCE");return new Plan(text,atoms,tokens);
    }
    static String literal(String raw){return url(raw);}
    private static boolean overlaps(List<Special> specials,int start,int end){for(Special s:specials)if(s.start<end&&s.end>start)return true;return false;}
    private static boolean clockPrefix(String text,int start){int p=start-1;while(p>=0&&Character.isWhitespace(text.charAt(p)))p--;return p<0||"+-−–—=<>*/^".indexOf(text.charAt(p))<0;}
    private static void addClock(List<Special> specials,String text,int start,int end){String clock=text.substring(start,end);specials.add(new Special(start,end,"source49-clock-v1",SourceNumbers45.projectSource(clock.substring(0,2))+" "+SourceNumbers45.projectSource(clock.substring(3))));}
    private static String url(String raw){
        List<String> words=new ArrayList<>();for(int n=0;n<raw.length();n++){
            char c=raw.charAt(n);if(c>='a'&&c<='z'||c>='A'&&c<='Z'){words.add(LETTERS[Character.toUpperCase(c)-'A']);continue;}if(c>='0'&&c<='9'){words.add(SourceWords45.cardinal(c-'0'));continue;}int ru=RUS.indexOf(Character.toLowerCase(c));if(ru>=0){words.add(RUS_LETTERS[ru]);continue;}
            String word;switch(c){case ':':word="двоеточие";break;case '/':word="слеш";break;case '.':word="точка";break;case '-':word="дефис";break;case '_':word="подчёркивание";break;case '?':word="вопрос";break;case '=':word="равно";break;case '&':word="амперсанд";break;case '%':word="процент";break;case '#':word="решётка";break;case '+':word="плюс";break;case '~':word="тильда";break;case '@':word="собака";break;case '!':word="восклицательный знак";break;case '$':word="доллар";break;case '\'':word="апостроф";break;case '(':word="открывающая скобка";break;case ')':word="закрывающая скобка";break;case '*':word="звёздочка";break;case ',':word="запятая";break;case ';':word="точка с запятой";break;default:throw new IllegalArgumentException("UNSUPPORTED_SOURCE_URL_CHARACTER");}words.add(word);
        }return String.join(" ",words);
    }
    public static void boundedSpoken(String text){if(text.length()>256)throw new IllegalArgumentException("UNSUPPORTED_SPOKEN_BOUND");int parts=0;for(String word:text.split("\\s+")){if(!word.isEmpty()){parts+=2;if(word.replaceAll("[^А-Яа-яёЁ]","").length()>32)throw new IllegalArgumentException("NGRAM_WORD_QUADRATIC_BOUND");}}if(parts>64)throw new IllegalArgumentException("ACCENTOR_TOKEN_BOUND");}
}

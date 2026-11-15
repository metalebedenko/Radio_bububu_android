package ru.bububu.radio.sources;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Bounded offline data and source-only decisions; canonical text is never rewritten. */
public final class SourcePronunciation48 {
    public static final String ASSET="speech/pronunciation48.tsv";
    public static final String REVISION="source-pronunciation48-v2-reference";
    private static final String ALPHABET="АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ";
    private static final String[] NAMES={"а","бэ","вэ","гэ","дэ","е","ё","жэ","зэ","и","и краткое","ка","эл","эм","эн","о","пэ","эр","эс","тэ","у","эф","ха","цэ","че","ша","ща","твёрдый знак","ы","мягкий знак","э","ю","я"};
    private static final Set<String> LEGACY=Set.of("КВН","ВТ","ЮФО","ЮФУ","РФ","США","ДНК","ВПЧ","МВД");
    private static final Set<String> UNITS=Set.of("ГЦ","КГЦ","МГЦ","КГ","МГ","ММ","СМ","КМ","МЛ","МКМ","КВТ","ВТ","МВТ","КВ","МБ","ГБ","ТБ","КБ","МПА","ГПА");
    private static final Map<String,String> INLINE=Map.of("т.е.","то есть","т.к.","так как","ит.д.","и так далее","ит.п.","и тому подобное","идр.","и другие","ипр.","и прочие");
    private static final Pattern TOKEN=Pattern.compile("[\\p{L}\\p{M}\\p{N}_]+");
    public enum Kind { SPELL, WORD, AMBIGUOUS, TEXT }
    public enum ContextRule { ANY, REFERENCE_NEXT_SENTENCE, INLINE, DISABLED }
    public static final class Entry {
        public final Kind kind;public final String key,reading,provenance;public final ContextRule context;public final boolean legacy;
        final Pattern pattern;
        Entry(Kind kind,String key,String reading,ContextRule context,boolean legacy,String provenance){this.kind=kind;this.key=key;this.reading=reading;this.context=context;this.legacy=legacy;this.provenance=provenance;pattern=Pattern.compile(Pattern.quote(key));}
    }
    public static final class Lexicon {
        public final String version,sha256;public final List<Entry> entries;final Map<String,Entry> words;final Pattern legacyPattern;
        Lexicon(String version,String sha,List<Entry> entries){this.version=version;sha256=sha;this.entries=Collections.unmodifiableList(entries);Map<String,Entry> index=new LinkedHashMap<>();List<String> old=new ArrayList<>();for(Entry e:entries){if(e.kind!=Kind.TEXT)index.put(e.key,e);if(e.legacy)old.add(Pattern.quote(e.key));}words=Collections.unmodifiableMap(index);legacyPattern=Pattern.compile("(?<![А-Яа-яЁёA-Za-z0-9])(?:"+String.join("|",old)+")(?![А-Яа-яЁёA-Za-z0-9])");}
    }
    public static final class Context {
        final String full,next;final int offset;final boolean body;
        int lineStart=-1,lineEnd=-1;boolean lineHeading;
        public Context(String full,int offset,boolean body,String next){this.full=Objects.requireNonNull(full);this.offset=offset;this.body=body;this.next=next==null?"":next;}
    }
    public static final class Replacement {
        public final int start,end;public final String spoken,rule;
        Replacement(int start,int end,String spoken,String rule){this.start=start;this.end=end;this.spoken=spoken;this.rule=rule;}
    }
    private static Lexicon current;private static String error;
    private SourcePronunciation48(){}
    /** Application startup installs the asset; failure stays visible until a new process/load. */
    public static synchronized void install(InputStream stream){try{current=parse(stream);error=null;}catch(IOException unavailable){current=null;error="UNSUPPORTED_PRONUNCIATION_DATA_IO";}catch(IllegalArgumentException invalid){current=null;error="UNSUPPORTED_PRONUNCIATION_DATA_FORMAT";}}
    public static synchronized void unavailable(){current=null;error="UNSUPPORTED_PRONUNCIATION_DATA_IO";}
    public static synchronized Lexicon dictionary(){if(current==null&&error==null)install(SourcePronunciation48.class.getResourceAsStream("/"+ASSET));if(error!=null)throw new IllegalArgumentException(error);return current;}
    public static String stamp(){try{Lexicon d=dictionary();return REVISION+":"+d.version+":"+d.sha256;}catch(IllegalArgumentException unavailable){return REVISION+":"+unavailable.getMessage();}}
    public static String status(){try{Lexicon d=dictionary();Map<Kind,Integer> counts=new EnumMap<>(Kind.class);int activeText=0;for(Entry e:d.entries){counts.merge(e.kind,1,Integer::sum);if(e.kind==Kind.TEXT&&e.context!=ContextRule.DISABLED)activeText++;}return "state=ready revision="+REVISION+" dataVersion="+d.version+" sha256="+d.sha256+" records="+d.entries.size()+" spell="+counts.getOrDefault(Kind.SPELL,0)+" word="+counts.getOrDefault(Kind.WORD,0)+" ambiguous="+counts.getOrDefault(Kind.AMBIGUOUS,0)+" text="+counts.getOrDefault(Kind.TEXT,0)+" textActive="+activeText+" legacy=9";}catch(IllegalArgumentException unavailable){return "state=error code="+unavailable.getMessage();}}
    public static Lexicon parse(InputStream input)throws IOException {
        if(input==null)throw new IOException("Pronunciation asset missing");byte[] bytes;
        try(InputStream stream=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];int n;while((n=stream.read(buffer))!=-1){if(out.size()+n>262144)throw new IllegalArgumentException("DATA_SIZE");out.write(buffer,0,n);}bytes=out.toByteArray();}
        String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException bad){throw new IllegalArgumentException("DATA_UTF8");}
        String[] lines=text.replace("\r\n","\n").split("\n",-1);if(lines.length>1024)throw new IllegalArgumentException("DATA_ROWS");
        String[] header=lines[0].split("\t",-1);if(header.length!=3||!header[0].equals("BUBUBU_PRONUNCIATION")||!header[1].equals("1")||!header[2].matches("[a-z0-9_-]{1,48}"))throw new IllegalArgumentException("DATA_VERSION");
        List<Entry> entries=new ArrayList<>();Set<String> keys=new HashSet<>(),legacy=new HashSet<>();boolean see=false;
        for(int row=1;row<lines.length;row++){
            String line=lines[row];if(line.isEmpty()||line.startsWith("#"))continue;String[] f=line.split("\t",-1);if(f.length!=6||line.length()>2048)throw new IllegalArgumentException("DATA_FIELDS");
            Kind kind;ContextRule context;try{kind=Kind.valueOf(f[0]);context=ContextRule.valueOf(f[3]);}catch(RuntimeException bad){throw new IllegalArgumentException("DATA_TYPE");}
            String key=f[1],reading=f[2];if(key.isEmpty()||key.length()>64||!key.equals(key.trim())||!Normalizer.isNormalized(key,Normalizer.Form.NFC)||!keys.add(key)||f[5].trim().isEmpty()||f[5].length()>1000||(!f[4].equals("0")&&!f[4].equals("1")))throw new IllegalArgumentException("DATA_ENTRY");
            boolean old=f[4].equals("1");
            if(kind==Kind.TEXT){
                if(old)throw new IllegalArgumentException("DATA_LEGACY");
                if(context==ContextRule.REFERENCE_NEXT_SENTENCE){if(!(key.equals("См.")||key.equals("см."))||!reading.equals("Смотрите."))throw new IllegalArgumentException("DATA_SEE_ALSO");if(key.equals("См."))see=true;}
                else if(context==ContextRule.INLINE){String expected=INLINE.get(key.replace(" ",""));if(expected==null||!reading.equals(expected))throw new IllegalArgumentException("DATA_INLINE");}
                else if(context==ContextRule.DISABLED){if(!(key.equals("см. также")&&reading.equals("смотрите также")||key.equals("См. также")&&reading.equals("Смотрите также")||key.equals("напр.")&&reading.equals("например")))throw new IllegalArgumentException("DATA_DISABLED");}
                else throw new IllegalArgumentException("DATA_CONTEXT");
            }else{
                if(context!=ContextRule.ANY||!key.matches("[А-Яа-яЁё]{2,16}"))throw new IllegalArgumentException("DATA_WORD");
                if(kind==Kind.SPELL){String names=letters(key);if(reading.equals("-"))reading=names;else if(!reading.equals(names))throw new IllegalArgumentException("DATA_LETTERS");}
                else if(kind==Kind.AMBIGUOUS&&!reading.equals("-"))throw new IllegalArgumentException("DATA_AMBIGUOUS");
                if(old){if(kind!=Kind.SPELL||!LEGACY.contains(key))throw new IllegalArgumentException("DATA_LEGACY");legacy.add(key);}
            }
            if(!reading.equals("-")&&(!reading.matches("[А-Яа-яЁё+ ,.!?]+")||reading.length()>256))throw new IllegalArgumentException("DATA_READING");
            entries.add(new Entry(kind,key,reading,context,old,f[5]));
        }
        if(entries.size()>512||!legacy.equals(LEGACY)||!see)throw new IllegalArgumentException("DATA_REQUIRED");
        return new Lexicon(header[2],SourceLimits.hash(bytes),entries);
    }
    public static String letters(String token){List<String> names=new ArrayList<>();for(char letter:token.toUpperCase(Locale.ROOT).toCharArray()){int index=ALPHABET.indexOf(letter);if(index<0)throw new IllegalArgumentException("UNSUPPORTED_PRONUNCIATION_LETTER");names.add(NAMES[index]);}return String.join(", ",names);}
    static String legacy(String text) {
        Lexicon lexicon = dictionary();
        Matcher matcher = lexicon.legacyPattern.matcher(text);
        if (!matcher.find()) return text;
        StringBuffer expanded = new StringBuffer();
        do {
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(lexicon.words.get(matcher.group()).reading));
        } while (matcher.find());
        matcher.appendTail(expanded);
        return expanded.toString();
    }
    public static List<Replacement> source(String raw,Context context){
        Lexicon d=dictionary();if(context.offset<0||!context.full.startsWith(raw,context.offset))throw new IllegalArgumentException("SOURCE_PRONUNCIATION_BINDING");
        List<Replacement> result=new ArrayList<>();
        List<Entry> textEntries=new ArrayList<>();for(Entry e:d.entries)if(e.kind==Kind.TEXT)textEntries.add(e);textEntries.sort((a,b)->Integer.compare(b.key.length(),a.key.length()));
        for(Entry entry:textEntries)if(context.body){
            if(entry.context==ContextRule.REFERENCE_NEXT_SENTENCE){int at=raw.indexOf(entry.key);if(raw.trim().equals(entry.key)&&reference(context.next)&&!numericBefore(context.full,context.offset+at))result.add(new Replacement(at,at+entry.key.length(),entry.reading,"text-reference:"+d.version));}
            else if(entry.context==ContextRule.INLINE){Matcher match=entry.pattern.matcher(raw);while(match.find())if(boundary(raw,match.start(),match.end())&&!overlap(result,match.start(),match.end())){String spoken=entry.reading;if(match.end()==raw.length()&&raw.endsWith("."))spoken+=".";result.add(new Replacement(match.start(),match.end(),spoken,"text-inline:"+d.version));}}
        }
        Matcher tokens=TOKEN.matcher(raw);while(tokens.find()){
            String token=tokens.group();Entry entry=d.words.get(token);int start=tokens.start(),end=tokens.end();String spoken=null,rule=null;
            if(overlap(result,start,end)||!token.matches("[А-Яа-яЁё]+"))continue;
            boolean protectedToken=code(context.full,context.offset+start,context.offset+end)||unit(token,context.full,context.offset+start);
            if(entry!=null&&entry.kind!=Kind.AMBIGUOUS&&!protectedToken){spoken=entry.reading.equals("-")?token:entry.reading;rule="lexicon-"+entry.kind.name().toLowerCase(Locale.ROOT)+":"+d.version;}
            else if(entry==null&&!protectedToken&&context.body&&token.matches("[А-ЯЁ]{2,6}")&&!token.matches(".*[АЕЁИОУЫЭЮЯЬЪ].*")&&!token.matches("[ХСМ]+")&&!heading(context,context.offset+start)) {spoken=letters(token);rule="source-consonant-caps-v1";}
            // Blocking a legacy token also blocks its later legacy-only normalizer pass.
            else if(protectedToken&&entry!=null&&entry.legacy){spoken=token;rule="source-protected-unit-code-v1";}
            if(spoken!=null)result.add(new Replacement(start,end,spoken,rule));
        }
        result.sort(Comparator.comparingInt(r->r.start));return result;
    }
    private static boolean overlap(List<Replacement> replacements,int start,int end){for(Replacement r:replacements)if(r.start<end&&r.end>start)return true;return false;}
    private static boolean reference(String next){if(next.startsWith("также:"))return true;for(String cue:List.of("далее","выше","ниже"))if(next.startsWith(cue)&&(next.length()==cue.length()||" \t\n:,.!?;".indexOf(next.charAt(cue.length()))>=0))return true;return false;}
    private static boolean numericBefore(String full,int start){int p=start-1;while(p>=0&&(Character.isWhitespace(full.charAt(p))||full.charAt(p)=='.'))p--;return p>=0&&Character.isDigit(full.charAt(p));}
    private static boolean word(int cp){int type=Character.getType(cp);return Character.isLetterOrDigit(cp)||type==Character.NON_SPACING_MARK||type==Character.COMBINING_SPACING_MARK||type==Character.ENCLOSING_MARK||type==Character.CONNECTOR_PUNCTUATION;}
    private static boolean boundary(String text,int start,int end){return (start==0||!word(text.codePointBefore(start)))&&(end==text.length()||!word(text.codePointAt(end)));}
    private static boolean code(String text,int start,int end){return !boundary(text,start,end)||(start>1&&"-/\\".indexOf(text.charAt(start-1))>=0&&word(text.codePointBefore(start-1)))||(end+1<text.length()&&"-/\\".indexOf(text.charAt(end))>=0&&word(text.codePointAt(end+1)));}
    private static boolean unit(String token,String full,int start){if(!UNITS.contains(token))return false;if(!LEGACY.contains(token))return true;int p=start-1;while(p>=0&&Character.isWhitespace(full.charAt(p)))p--;return p>=0&&Character.isDigit(full.charAt(p));}
    private static boolean heading(Context context,int start){if(start>=context.lineStart&&start<context.lineEnd)return context.lineHeading;context.lineStart=context.full.lastIndexOf('\n',start-1)+1;context.lineEnd=context.full.indexOf('\n',start);if(context.lineEnd<0)context.lineEnd=context.full.length();context.lineHeading=true;for(int p=context.lineStart;p<context.lineEnd;p++)if(Character.isLowerCase(context.full.charAt(p))){context.lineHeading=false;break;}return context.lineHeading;}
}

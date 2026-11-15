package ru.bububu.silero.stressprobe;
import java.util.*;
import java.util.regex.*;
import ru.bububu.silero.coreprobe.SemanticNormalizer;

/** Bounded own port for the bounded D1F-002 Russian runtime raw profile; no neural calls. */
final class FrontendPlan {
 static final String VOWELS="аоуыэиеяёю";
 static final class Data {
  final Map<String,Integer> vocab=new LinkedHashMap<>();
  final Map<String,List<String>> variants=new HashMap<>();
  final Map<String,String> phrases=new HashMap<>();
  final Map<String,Map<String,Integer>> phraseGroups=new HashMap<>();
  final Map<String,Pattern> compiledPhrases=new HashMap<>();
  final Map<String,int[]> exceptions=new HashMap<>();
  int cls,sep,unk,homoStart,homoEnd,maxWord=100;
 }
 static final class Rejected extends IllegalArgumentException { final String code;Rejected(String code) { super(code);this.code=code; } }
 static void need(boolean ok,String code) { if(!ok) throw new Rejected(code); }
 final Data data;final SemanticNormalizer.Outcome normalization;final String spoken;
 final String homograph;final int wordStart,wordEnd;final long[] ids;final long markerStart,markerEnd;
 final String baseAnnotated;
 String homoAnnotated,annotated;List<String> rawTokens,cleanTokens;final List<String> decisions=new ArrayList<>();
 FrontendPlan(Data data,String raw) {
  this.data=data;need(raw!=null&&raw.length()<=512,"RAW_TEXT_BOUND");normalization=SemanticNormalizer.normalize(raw);
  need(normalization.supported(),normalization.code==null?"UNSUPPORTED_NORMALIZER":normalization.code);
  spoken=normalization.spoken.toLowerCase(Locale.ROOT);need(spoken.length()<=256,"UNSUPPORTED_SPOKEN_BOUND");
  need(spoken.matches("[а-яё .,!?:;+]+"),"UNSUPPORTED_FRONTEND_CHARACTERS");
  Matcher words=Pattern.compile("[а-яё+]+").matcher(spoken);int start=-1,end=-1,count=0;String target=null,marked=null;
  List<int[]> spans=new ArrayList<>();List<String> resolved=new ArrayList<>();
  while(words.find()) if(data.variants.containsKey(words.group())||data.phrases.containsKey(words.group())) {
   int at=words.start(),until=words.end();String value=words.group();
   String left=cleanContext(spoken.substring(0,at),true),right=cleanContext(spoken.substring(until),false);
   if(left.length()>150) left=left.substring(left.length()-150);if(right.length()>150) right=right.substring(0,150);
   String context=(left+" [HOMO] "+value+" [/HOMO] "+right).trim();
   String deterministic=PhraseRules.resolve(data,value,context);
   if(deterministic!=null){spans.add(new int[]{at,until});resolved.add(deterministic);decisions.add("phrase=APPROVED_UNIQUE");}
   else{count++;start=at;end=until;target=value;marked=context;}
  }
  need(count<=1,"UNSUPPORTED_MULTIPLE_HOMOGRAPHS");
  StringBuilder base=new StringBuilder();int cursor=0,shift=0;
  for(int i=0;i<spans.size();i++){int[] span=spans.get(i);base.append(spoken,cursor,span[0]).append(resolved.get(i));cursor=span[1];if(span[0]<start)shift+=resolved.get(i).length()-(span[1]-span[0]);}
  base.append(spoken,cursor,spoken.length());baseAnnotated=base.toString();accentParts(baseAnnotated);
  if(count==0) { homograph=null;wordStart=-1;wordEnd=-1;ids=new long[0];markerStart=-1;markerEnd=-1;homoAnnotated=baseAnnotated;decisions.add("homo_skip=NO_HOMOGRAPH");return; }
  need(target!=null&&data.variants.containsKey(target),"UNSUPPORTED_HOMOGRAPH_RESOURCE");
  homograph=target;wordStart=start+shift;wordEnd=end+shift;List<String> variants=data.variants.get(target);
  need(variants.size()==2&&variants.get(0).compareTo(variants.get(1))<0,"VARIANT_ORDER");
  ids=tokenize(marked);long a=-1,b=-1;int ac=0,bc=0;
  for(int i=0;i<ids.length;i++) { if(ids[i]==data.homoStart) { a=i;ac++; }if(ids[i]==data.homoEnd) { b=i;bc++; } }
  need(ac==1&&bc==1&&a>=1&&b>a+1&&b<ids.length-1,"HOMO_SPANS");markerStart=a;markerEnd=b;
 }
 static String cleanContext(String s,boolean before) {
  s=s.replaceAll("[^a-zA-Zа-яА-ЯёЁ0-9\\s.!?,\\-]","").replaceAll("\\s+"," ").replaceAll("-{2,}"," - ")
   .replaceAll("([.!?])\\1+","$1").replaceAll(",{2,}",",").replaceAll("\\s+([.,!?])","$1")
   .replaceAll("([.!?])\\1+","$1").replaceAll(",{2,}",",").replaceAll("([.,!?])(?=\\S)","$1 ").replaceAll("\\s+"," ").trim();
  if(before) { s=s.replaceFirst("^[ .,!?-]+","");if(!s.isEmpty()) s=s.substring(0,1).toUpperCase(Locale.ROOT)+s.substring(1).toLowerCase(Locale.ROOT); }
  else if(!s.isEmpty()) { s=s.substring(0,1)+s.substring(1).toLowerCase(Locale.ROOT);if(!s.matches(".*[.!?]$")) s+="."; }
  return s;
 }
 long[] tokenize(String input) {
  need(input.length()<=512&&input.matches("[а-яА-ЯёЁa-zA-Z0-9 \\[\\]/.!?,+\\-]+"),"TOKENIZER_INPUT_PROFILE");
  List<String> basic=new ArrayList<>();
  for(String word:input.trim().split(" +")) {
   if(word.equals("[HOMO]")||word.equals("[/HOMO]")) { basic.add(word);continue; }
   StringBuilder chunk=new StringBuilder();
   for(char c:word.toCharArray()) {
    boolean punct=c>=33&&c<=47||c>=58&&c<=64||c>=91&&c<=96||c>=123&&c<=126||Character.getType(c)==Character.OTHER_PUNCTUATION;
    if(punct) { if(chunk.length()>0) { basic.add(chunk.toString());chunk.setLength(0); }basic.add(String.valueOf(c)); } else chunk.append(c);
   }
   if(chunk.length()>0) basic.add(chunk.toString());
  }
  List<Long> encoded=new ArrayList<>();encoded.add((long)data.cls);
  for(String word:basic) {
   need(word.length()<=data.maxWord,"WORDPIECE_WORD_BOUND");int start=0;List<Integer> pieces=new ArrayList<>();boolean bad=false;
   while(start<word.length()) {
    int end=word.length();Integer id=null;
    while(start<end) { String piece=(start>0?"##":"")+word.substring(start,end);id=data.vocab.get(piece);if(id!=null) break;end--; }
    if(id==null) { bad=true;break; }pieces.add(id);start=end;
   }
   if(bad) encoded.add((long)data.unk);else for(int id:pieces) encoded.add((long)id);
  }
  encoded.add((long)data.sep);need(encoded.size()<=256,"TOKEN_COUNT_BOUND");long[] out=new long[encoded.size()];
  for(int i=0;i<out.length;i++) { out[i]=encoded.get(i);need(out[i]>=0&&out[i]<83830,"TOKEN_ID_RANGE"); }return out;
 }
 static boolean basePreserved(String before,String after) {
  if(before.length()!=after.length()) return false;for(int i=0;i<before.length();i++) if(before.charAt(i)!=after.charAt(i)&&!(before.charAt(i)=='е'&&after.charAt(i)=='ё')) return false;return true;
 }
 boolean needsHomo() { return homograph!=null; }
 void acceptHomo(float logit) {
  need(needsHomo(),"HOMO_NOT_REQUESTED");
  need(Float.isFinite(logit)&&Math.abs(logit)>0.00001f,"HOMO_LOGIT_NONFINITE_OR_AMBIGUOUS");
  float probability=(float)(1.0/(1.0+Math.exp(-logit)));int choice=(int)Math.rint(probability);
  need(choice==0||choice==1,"HOMO_VARIANT_INDEX");String value=data.variants.get(homograph).get(choice);
   need(basePreserved(homograph,value.replace("+","")),"HOMO_BASE_WORD_CHANGED");homoAnnotated=baseAnnotated.substring(0,wordStart)+value+baseAnnotated.substring(wordEnd);
  decisions.add("homo_variant_index="+choice);
 }
 List<String> accentInputs() {
  need(homoAnnotated!=null,"HOMO_OUTPUT_REQUIRED");rawTokens=accentParts(homoAnnotated);cleanTokens=new ArrayList<>();
  for(String token:rawTokens) { need(token.indexOf('-')<0,"HYPHEN_OUTSIDE_CASE_PROFILE");String clean=token.replaceAll("[^А-Яа-яёЁ]","").toLowerCase(Locale.ROOT);need(clean.length()<=32,"NGRAM_WORD_QUADRATIC_BOUND");cleanTokens.add(clean); }
  return Collections.unmodifiableList(cleanTokens);
 }
 static List<String> accentParts(String text) {
  List<String> parts=new ArrayList<>();Matcher delim=Pattern.compile("[\\s.,!?;:<>=()/\\\\]+").matcher(text);int pos=0;
  while(delim.find()){parts.add(text.substring(pos,delim.start()));parts.add(delim.group());pos=delim.end();}
  parts.add(text.substring(pos));need(parts.size()<=64,"ACCENTOR_TOKEN_BOUND");
  for(String part:parts){need(part.indexOf('-')<0,"HYPHEN_OUTSIDE_CASE_PROFILE");need(part.replaceAll("[^А-Яа-яёЁ]","").length()<=32,"NGRAM_WORD_QUADRATIC_BOUND");}
  return parts;
 }
 static long shapeElements(long[] shape,long rows,boolean matrix) {
  long size=1;for(long d:shape) { need(d>0&&d<=4096,"OUTPUT_DIM_BOUND");size*=d;need(size<=4096,"OUTPUT_ELEMENTS_BOUND"); }
  if(matrix) need(shape.length==2&&shape[0]==rows&&shape[1]<=64,"ACCENTOR_MATRIX_SHAPE");
  else need((shape.length==1||shape.length==2)&&shape[0]==1&&size==1,"HOMO_LOGIT_SHAPE");return size;
 }
 static final class Prediction {
  final int index;final float probability;
  Prediction(int index,float probability) { this.index=index;this.probability=probability; }
 }
 static Prediction prediction(float[] logits,int offset,int width) {
  need(width>=1&&width<=64,"CLASS_WIDTH_BOUND");int best=0;float maximum=Float.NEGATIVE_INFINITY;
  for(int i=0;i<width;i++) { float v=logits[offset+i];need(Float.isFinite(v),"LOGIT_NONFINITE");if(v>maximum) { maximum=v;best=i; } }
  double sum=0;for(int i=0;i<width;i++) sum+=Math.exp(logits[offset+i]-maximum);
  float p=(float)(1/sum);return new Prediction(best,p);
 }
 private static String indexFallback(String kind,int row,String clean,int slots,Prediction prediction){
  try{
   byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(clean.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();
   for(byte b:digest)hex.append(String.format(Locale.ROOT,"%02x",b&255));
   return kind+"_index_fallback;row="+row+";chars="+clean.length()+";slots="+slots+";class="+prediction.index+";p="+prediction.probability+";sha256="+hex;
  }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
 }
 String acceptAccent(float[] stress,int stressWidth,float[] yo,int yoWidth) {
  need(rawTokens!=null&&stress.length==rawTokens.size()*stressWidth&&yo.length==rawTokens.size()*yoWidth,"ACCENTOR_OUTPUT_SHAPE");StringBuilder out=new StringBuilder();
  for(int row=0;row<rawTokens.size();row++) {
   String raw=rawTokens.get(row),clean=cleanTokens.get(row);
   if(clean.isEmpty()) { out.append(raw);continue; }
   Prediction st=prediction(stress,row*stressWidth,stressWidth),yp=prediction(yo,row*yoWidth,yoWidth);
   boolean manual=raw.indexOf('+')>=0,hasYo=raw.indexOf('ё')>=0;int[] exception=data.exceptions.get(clean);
   if(manual&&hasYo) { out.append(raw);continue; }
   if(hasYo) { out.append(manual?raw:raw.replace("ё","+ё"));continue; }
   if(exception!=null) {
    need(exception.length==2,"EXCEPTION_SHAPE");String base=raw.replace("+","");int y=exception[1],at=exception[0];
    if(manual) { if(y>=0&&raw.indexOf('+')==y+1) { need(y<base.length()&&base.charAt(y)=='е',"EXCEPTION_YO_RANGE");base=base.substring(0,y)+'ё'+base.substring(y+1); }StringBuilder b=new StringBuilder(base);for(int i=0;i<raw.length();i++) if(raw.charAt(i)=='+') b.insert(i,'+');out.append(b); }
    else { need(at>=0&&at<raw.length()&&VOWELS.indexOf(raw.charAt(at))>=0,"EXCEPTION_STRESS_RANGE");if(y>=0) { need(y<raw.length()&&raw.charAt(y)=='е',"EXCEPTION_YO_RANGE");raw=raw.substring(0,y)+'ё'+raw.substring(y+1); }out.append(raw.substring(0,at)).append('+').append(raw.substring(at)); }
    continue;
   }
   List<Integer> vowels=new ArrayList<>(),yes=new ArrayList<>();for(int i=0;i<raw.length();i++) { if(VOWELS.indexOf(raw.charAt(i))>=0) vowels.add(i);if(raw.charAt(i)=='е') yes.add(i); }
   if(vowels.isEmpty()) { out.append(raw);continue; }
   Set<Integer> stressPositions=new HashSet<>();
   if(manual) { for(int i=0;i<raw.length();i++) if(raw.charAt(i)=='+') { need(i+1<raw.length()&&VOWELS.indexOf(raw.charAt(i+1))>=0,"MANUAL_STRESS_POSITION");stressPositions.add(i+1); } }
   else if(st.index<vowels.size()) stressPositions.add(vowels.get(st.index));
   // Fixed-class model outputs are not masked to this word's vowels. Match the
   // accepted wrapper: retain the word when a learned ordinal has no character slot.
   else if(st.probability>0.5f) decisions.add(indexFallback("stress",row,clean,vowels.size(),st));
   if(yp.index>0&&yp.probability>0.5f) {
    if(yp.index<=yes.size()){int at=yes.get(yp.index-1);if(stressPositions.contains(at))raw=raw.substring(0,at)+'ё'+raw.substring(at+1);}
    else decisions.add(indexFallback("yo",row,clean,yes.size(),yp));
   }
   if(vowels.size()==1) { stressPositions.clear();stressPositions.add(vowels.get(0)); }
   boolean insert=!manual&&(vowels.size()==1||st.probability>0.5f);
   if(insert) { List<Integer> positions=new ArrayList<>(stressPositions);Collections.sort(positions);StringBuilder b=new StringBuilder(raw);int added=0;for(int at:positions) { b.insert(at+added,'+');added++; }raw=b.toString(); }
   decisions.add("word="+clean+";stress="+st.index+";p="+st.probability+";yo="+yp.index+";yp="+yp.probability);out.append(raw);
  }
  annotated=out.toString();need(annotated.length()<=512,"ANNOTATION_BOUND");return annotated;
 }
}

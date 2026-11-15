package ru.bububu.radio;

import java.lang.reflect.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import ru.bububu.radio.core.*;
import ru.bububu.radio.sources.*;

/** Test-only closed fixture/identity contract. Not in either application APK. */
public final class Unique45FixtureContract {
    public static final String APP="ru.bububu.radio.internalqa45.j20261026a03",TEST=APP+".test";
    public static final String MANIFEST_SHA="c2bb92efdfd94ae47ad5c203cca5c0dd89bea7876fdeb89f8af17845ecfd88f2";
    public static final String LONG_SHA="d7cc389ea27550b37e7371dc9a5a4014350bbb184c0220dde26edc741bdba9f0",UNKNOWN_SHA="6a957f49cdd712f4d6a582bcfd665753cea199ae2efc549a15c449818ab0b3be";
    private Unique45FixtureContract(){}
    static void need(boolean value,String reason){if(!value)throw new IllegalStateException("REFUSE_UNIQUE45_"+reason);}
    public static void identity(String target,String instrumentation,String declaredTarget){need(APP.equals(target)&&TEST.equals(instrumentation)&&APP.equals(declaredTarget),"IDENTITY");}
    public static Seed45Marker freshBook(byte[] book,byte[] receipt)throws Exception{
        need(book!=null&&receipt!=null,"ABSENT_PROOF");NoteBook.validateImage(book);
        Class<?> codec=Class.forName("ru.bububu.radio.core.SourceDefaults45$Receipt");Method decode=codec.getDeclaredMethod("decode",byte[].class);decode.setAccessible(true);Object value=decode.invoke(null,(Object)receipt);
        need(APP.equals(field(codec,value,"applicationId"))&&Boolean.TRUE.equals(field(codec,value,"applied")),"RECEIPT_OWNER_OR_STATE");
        freshProof((byte[])field(codec,value,"proof"));
        need(Arrays.equals(book,(byte[])field(codec,value,"target")),"BOOK_NOT_EXACT_FRESH_TARGET");
        Seed45Marker marker=NoteBook.inspectSeed45(book);need(marker!=null&&marker.attemptId.equals(field(codec,value,"attempt"))&&marker.definitionSetHash.equals(SourceProfiles45.definitionSetHash()),"ATTEMPT_OR_DEFINITIONS");
        List<SourceRecord> sources=new ArrayList<>();Map<String,String> bindings=new TreeMap<>();for(SourceProfiles45.Profile p:SourceProfiles45.six()){SourceRecord source=p.seed();sources.add(source);bindings.put(p.id,source.id);}
        need(marker.bindings.equals(bindings)&&Arrays.equals(book,NoteBook.fresh45Target(sources,new Seed45Marker(marker.attemptId,Seed45Marker.POLICY,SourceProfiles45.definitionSetHash(),bindings))),"NON_SYNTHETIC_STATE");
        return marker;
    }
    private static Object field(Class<?> codec,Object value,String name)throws Exception{Field f=codec.getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private static void freshProof(byte[] bytes)throws IOException{
        Set<String> directories=new HashSet<>(List.of("data","files","nobackup","prefs","notes","source-cas","source-network","model-packs")),files=new HashSet<>();
        for(String suffix:List.of("",".bak",".new")){files.add("notes/"+NoteMetadataReader.BASE+suffix);files.add("notes/"+StartupSourceFacts45.RECEIPT+suffix);files.add("files/player-state-v1.json"+suffix);}
        for(String name:List.of("demo_ui_v1","local-model-choice","RadioActivity","ru.bububu.radio.RadioActivity"))for(String suffix:List.of(".xml",".xml.bak"))files.add("prefs/"+name+suffix);
        files.add("model-packs/silero.ready");files.add("model-packs/gigaam.ready");Set<String> expected=new HashSet<>(directories);expected.addAll(files);Set<String> seen=new HashSet<>();
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){need(in.readInt()==1&&in.readInt()==expected.size(),"FRESH_MANIFEST_CODEC");String previous="";for(int n=0;n<expected.size();n++){
            String key=proofString(in),value=proofString(in);need(expected.contains(key)&&seen.add(key)&&key.compareTo(previous)>0,"FRESH_MANIFEST_KEYS");previous=key;
            need(files.contains(key)?value.equals("ABSENT"):value.equals("ABSENT")||value.startsWith("DIR:")&&value.length()>4,"FRESH_MANIFEST_PRIOR_USE");
            if(key.equals("data")||key.equals("files")||key.equals("nobackup"))need(value.startsWith("DIR:"),"FRESH_MANIFEST_ROOT");
        }need(in.read()==-1,"FRESH_MANIFEST_TRAILING");}
    }
    private static String proofString(DataInputStream in)throws IOException{int n=in.readInt();need(n>=1&&n<=512&&n*2<=in.available(),"FRESH_MANIFEST_BOUND");StringBuilder s=new StringBuilder();for(int i=0;i<n;i++)s.append(in.readChar());return s.toString();}
    public static Fixtures fixtures(byte[] manifest,byte[] longBody,byte[] unknownBody,NoteBook book)throws Exception{
        need(SourceLimits.hash(manifest).equals(MANIFEST_SHA)&&SourceLimits.hash(longBody).equals(LONG_SHA)&&SourceLimits.hash(unknownBody).equals(UNKNOWN_SHA),"FIXTURE_SHA");
        SourceRecord source=book.source(SourceProfiles45.byId("elementy-science-explainers").seed().id);need(source!=null,"SEEDED_SOURCE");
        List<SourceItem> items=new ArrayList<>();List<SourceVersionRecord> versions=new ArrayList<>();int index=0;
        for(byte[] bytes:List.of(longBody,unknownBody)){
            boolean longOne=index++==0;String body=new String(bytes,StandardCharsets.UTF_8),title=longOne?"Синтетическая длинная статья":"Синтетическая статья без даты";
            String url="https://elementy.ru/novosti_nauki/999999/qa45_"+(longOne?"long":"unknown");SourceItem item=SourceItem.create(source.id,longOne?"qa45-long":"qa45-unknown",null,url,title,-1,-1,1,null,null);
            SourcePublication45 publication=new SourcePublication45(SourcePublication45.parse(SourcePublication45.Role.ORIGINAL_PUBLISHED,longOne?"2011-02":"","fixture",url,"sealed-qa45",false),longOne?List.of(SourcePublication45.parse(SourcePublication45.Role.REPUBLISHED,"2026-10-25T10:00:00+03:00","fixture",url,"sealed-qa45-republication",false)):List.of(),SourceProfiles45.byId("elementy-science-explainers").configSha,"sealed-qa45","",SourceLimits.hash(bytes));
            SourceVersionRecord v=SourceVersionRecord.source45(source,item,new ExtractionResult(body,title,title,"full","","source45-html","source45-v1"),publication,longOne?SourcePublication45.Context.DATED:SourcePublication45.Context.UNKNOWN_MANUAL,1);
            need(longOne?v.spokenText.length()>20000&&v.spokenText.length()<100000:v.contextClass==SourcePublication45.Context.UNKNOWN_MANUAL,"FIXTURE_SHAPE");items.add(item);versions.add(v);
        }
        return new Fixtures(source,items,versions);
    }
    public static final String TYPED_LONG_ITEM="qa45-c4-typed-long",TYPED_UNKNOWN_ITEM="qa45-c4-typed-unknown";
    public static final String TYPED_LONG_ARTICLE="item:5b49ab5488a6b5be7ac8798fbc8e871358da20f277e9542ed2fd4140c1882122",TYPED_UNKNOWN_ARTICLE="item:ab4916fc6a189240e33ed8a2bea40ffa0d9a32062da56f38cd3e0cbb29ca7f24";
    /** Separate C4 items: the legacy factory above remains the ownership proof for old data. */
    public static Fixtures typedFixtures(byte[] manifest,byte[] longBody,byte[] unknownBody,NoteBook book)throws Exception{
        Fixtures legacy=fixtures(manifest,longBody,unknownBody,book);SourceRecord source=legacy.source;
        List<SourceItem> items=new ArrayList<>();List<SourceVersionRecord> versions=new ArrayList<>();
        for(int n=0;n<2;n++){
            boolean longOne=n==0;String title=longOne?"Проверка источника с датой":"Проверка источника без даты";
            String url="https://elementy.ru/novosti_nauki/999999/qa45_c4_typed_"+(longOne?"long":"unknown");
            SourceItem item=SourceItem.create(source.id,longOne?TYPED_LONG_ITEM:TYPED_UNKNOWN_ITEM,null,url,title,-1,-1,1,null,null);
            need(item.articleId.equals(longOne?TYPED_LONG_ARTICLE:TYPED_UNKNOWN_ARTICLE),"TYPED_PINNED_ID");
            SourceVersionRecord old=legacy.versions.get(n);SourceVersionRecord v=SourceVersionRecord.source45(source,item,new ExtractionResult(old.body(),title,title,old.completeness,old.reason,old.method,SourceProfiles45.PARSER),old.publication,old.contextClass,old.receivedAt);
            need(v.kind()==ChannelProfile.Kind.ARTICLE_ANALYSIS&&!v.recordKey.equals(old.recordKey)&&!v.articleId.equals(old.articleId)&&v.bodyHash.equals(old.bodyHash)&&v.body().equals(old.body()),"TYPED_IDENTITY_OR_PAYLOAD");
            items.add(item);versions.add(v);
        }
        return new Fixtures(source,items,versions);
    }
    public static final class Fixtures {
        public final SourceRecord source;public final List<SourceItem> items;public final List<SourceVersionRecord> versions;
        Fixtures(SourceRecord source,List<SourceItem> items,List<SourceVersionRecord> versions){this.source=source;this.items=Collections.unmodifiableList(items);this.versions=Collections.unmodifiableList(versions);}
        public void appendMetadata(NoteBook book){List<SourceVersionRecord> metadata=new ArrayList<>();for(SourceVersionRecord v:versions)metadata.add(v.withPayload(null));book.mergeSourceItems(source,items,metadata,source.etag,source.lastModified,source.lastSuccess,source.lastError);}
    }
}

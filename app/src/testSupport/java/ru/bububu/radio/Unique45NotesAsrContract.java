package ru.bububu.radio;

import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import ru.bububu.radio.core.*;
import ru.bububu.radio.sources.*;

/** Test-only closed C4 state proof. Detached comparison images are NEVER written to target storage. */
public final class Unique45NotesAsrContract {
    public static final String PCM_SHA="1c251001d6cb5ecd9abd845772b96122e29c6bca7bdf94cb36306f1f138bfcb0";
    public static final String WAV_SHA="af328a9cedfd04bdf69212ea2b9f8e50a7ee79d919da6fd2b89b2fee1182d143";
    public static final int PCM_BYTES=263988,WAV_BYTES=264032;
    public static final long ASR_MS=120000,CLEANUP_MS=30000;
    public static final String TITLE="Синтетическая проверка Notes ASR";
    private Unique45NotesAsrContract(){}
    static void need(boolean ok,String reason){if(!ok)throw new IllegalStateException("REFUSE_NOTES_GATE_B_"+reason);}
    public static Unique45EmptyDraftGuard.Snapshot requireExactEmptyNotes(Path notes)throws Exception{
        need(Files.readAttributes(notes,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isDirectory(),"NOTES_DIRECTORY");Unique45EmptyDraftGuard.Snapshot draft=Unique45EmptyDraftGuard.read(notes);need(draft.present,"EXACT_EMPTY8_REQUIRED");
        Set<String> allowed=new HashSet<>(List.of(NoteMetadataReader.BASE,StartupSourceFacts45.RECEIPT,StartupSourceFacts45.RECEIPT+".bak",Unique45EmptyDraftGuard.NAME));
        try(DirectoryStream<Path> files=Files.newDirectoryStream(notes)){for(Path p:files)need(allowed.contains(p.getFileName().toString())&&Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isRegularFile(),"ORIGINAL_OR_FOREIGN_NOTES_ARTIFACT");}return draft;
    }
    public static Proof prove(byte[] bookBytes,byte[] receipt,byte[] manifest,byte[] longBody,byte[] unknownBody,byte[] pcm)throws Exception{
        need(pcm.length==PCM_BYTES&&SourceLimits.hash(pcm).equals(PCM_SHA),"PCM");
        return proveSources(bookBytes,receipt,manifest,longBody,unknownBody,true);
    }
    public static Proof proveLegacy(byte[] bookBytes,byte[] receipt,byte[] manifest,byte[] longBody,byte[] unknownBody)throws Exception{
        return proveSources(bookBytes,receipt,manifest,longBody,unknownBody,false);
    }
    public static Proof proveTyped(byte[] bookBytes,byte[] receipt,byte[] manifest,byte[] longBody,byte[] unknownBody)throws Exception{
        return proveSources(bookBytes,receipt,manifest,longBody,unknownBody,true);
    }
    private static Proof proveSources(byte[] bookBytes,byte[] receipt,byte[] manifest,byte[] longBody,byte[] unknownBody,boolean typed)throws Exception{
        Class<?> codec=Class.forName("ru.bububu.radio.core.SourceDefaults45$Receipt");Method decode=codec.getDeclaredMethod("decode",byte[].class);decode.setAccessible(true);Object sealed=decode.invoke(null,(Object)receipt);Field target=codec.getDeclaredField("target");target.setAccessible(true);
        Seed45Marker initial=Unique45FixtureContract.freshBook((byte[])target.get(sealed),receipt);
        NoteBook book=readOnly(bookBytes);Seed45Marker marker=book.seed45Marker();need(marker!=null&&marker.attemptId.equals(initial.attemptId)&&marker.policy.equals(initial.policy)&&marker.definitionSetHash.equals(initial.definitionSetHash)&&marker.bindings.equals(initial.bindings),"OWN_ATTEMPT");
        int count=typed?4:2;need(book.schema()==4&&book.notes().isEmpty()&&book.sources().size()==6&&book.materials().size()==count,"SIX_OWN_COUNT_NOTES_ZERO");
        Object state=state(book);need(size(state,"sources")==6&&size(state,"stations")==6&&size(state,"lifecycle")==6&&size(state,"versions")==count&&size(state,"items")==count&&size(state,"hidden")==0&&size(state,"deletedItems")==0&&size(state,"tombstones")==0,"NO_OTHER_BOOK_RECORDS");
        for(SourceProfiles45.Profile p:SourceProfiles45.six()){
            SourceRecord expected=p.seed(),current=book.source(expected.id);need(current!=null&&!book.isSourceRetired(expected.id),"SIX_IDENTITY");
            fieldsEqual(expected,current,Set.of("enabled","etag","lastModified","lastSuccess","lastError"));
        }
        List<Unique45FixtureContract.Fixtures> fixtures=new ArrayList<>();fixtures.add(Unique45FixtureContract.fixtures(manifest,longBody,unknownBody,book));if(typed)fixtures.add(Unique45FixtureContract.typedFixtures(manifest,longBody,unknownBody,book));
        if(!typed)need(fixtures.get(0).source.enabled&&book.isSourceActive(fixtures.get(0).source.id),"APPEND_SOURCE_DISABLED");
        Set<String> expectedKeys=new HashSet<>();List<SourceVersionRecord> versions=new ArrayList<>();
        for(Unique45FixtureContract.Fixtures fixture:fixtures)for(int n=0;n<fixture.versions.size();n++){
            SourceVersionRecord v=fixture.versions.get(n),saved=book.sourceVersion(v.recordKey);need(sameVersion(v,saved),"SEALED_VERSION");expectedKeys.add(v.recordKey);versions.add(v);
            SourceItem item=book.sourceItems(v.sourceId).stream().filter(i->i.itemKey.equals(v.itemKey)).findFirst().orElse(null);need(item!=null,"SEALED_ITEM");
            SourceItem expected=fixture.items.get(n).withLatest(v.recordKey).withHints(v.completeness,v.reason,v.method);fieldsEqual(expected,item,Set.of("read","everListened","lastViewedVersion"));
        }
        Set<String> selected=new HashSet<>();for(NoteBook.Material material:book.materials()){SourceVersionRecord bound=material.asArticle().sourceVersion;need(material.noteId==null&&expectedKeys.contains(material.sourceVersionKey)&&sameVersion(book.sourceVersion(material.sourceVersionKey),bound),"ONLY_OWN_MATERIALS");selected.add(material.sourceVersionKey);}need(selected.equals(expectedKeys),"EXACT_OWN_MATERIALS");
        return new Proof(bookBytes,marker,versions.get(typed?2:0).recordKey,versions);
    }
    static boolean sameVersion(SourceVersionRecord expected,SourceVersionRecord actual){return expected!=null&&actual!=null&&expected.sameMetadata(actual)&&expected.receivedAt==actual.receivedAt&&Objects.equals(expected.cacheLocator,actual.cacheLocator)&&Objects.equals(expected.spokenText,actual.spokenText)&&Arrays.equals(expected.publication.encode(),actual.publication.encode());}
    private static void fieldsEqual(Object expected,Object current,Set<String> mutable)throws Exception{for(Field f:expected.getClass().getFields())if(!Modifier.isStatic(f.getModifiers())&&!mutable.contains(f.getName()))need(Objects.equals(f.get(expected),f.get(current)),"FIELD_"+f.getName());}
    static NoteBook readOnly(byte[] bytes){return new NoteBook(new NoteBook.Storage(){public byte[] load(){return bytes.clone();}public void save(byte[] ignored){throw new AssertionError("Detached read-only image");}});}
    private static Object state(NoteBook book)throws Exception{Field f=NoteBook.class.getDeclaredField("state");f.setAccessible(true);return f.get(book);}
    private static int size(Object state,String name)throws Exception{Field f=state.getClass().getDeclaredField(name);f.setAccessible(true);return ((Map<?,?>)f.get(state)).size();}
    public static final class Proof {
        public final byte[] before;public final Seed45Marker marker;public final String linkedRecord;public final List<SourceVersionRecord> versions;
        public SourceVersionRecord linkedVersion(){return versions.stream().filter(v->linkedRecord.equals(v.recordKey)).findFirst().orElseThrow();}
        Proof(byte[] before,Seed45Marker marker,String linkedRecord,List<SourceVersionRecord> versions){this.before=before.clone();this.marker=marker;this.linkedRecord=linkedRecord;this.versions=List.copyOf(versions);}
        public Map<String,String> requireCas(Path cas)throws Exception{
            need(Files.readAttributes(cas,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isDirectory(),"CAS_DIRECTORY");Map<String,String> facts=new TreeMap<>();Set<String> wanted=new HashSet<>();for(SourceVersionRecord v:versions)wanted.add(SourceLimits.sha256(v.recordKey)+".cas");
            try(DirectoryStream<Path> paths=Files.newDirectoryStream(cas)){for(Path p:paths){BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);need(wanted.remove(p.getFileName().toString())&&a.isRegularFile()&&a.size()<=1000000,"CAS_FOREIGN_OR_UNSAFE");facts.put(p.getFileName().toString(),SourceLimits.hash(Files.readAllBytes(p)));}}need(wanted.isEmpty(),"CAS_MISSING");
            SourceBodyCache cache=new SourceBodyCache(cas.toFile());for(SourceVersionRecord v:versions)need(sameVersion(v,cache.get(v.withPayload(null))),"CAS_PAYLOAD");return facts;
        }
        /** Pure RAM subtraction of exactly the two NEW C4 entries; never restore/save this image. */
        public void requireOnlyTypedAppend(byte[] after,Unique45FixtureContract.Fixtures typed)throws Exception{
            need(versions.size()==2&&typed.versions.size()==2,"APPEND_BASELINE");Object detached=state(readOnly(after));
            for(String table:List.of("items","versions","materials")){
                Field f=detached.getClass().getDeclaredField(table);f.setAccessible(true);Map<?,?> map=(Map<?,?>)f.get(detached);int removed=0;
                for(Object key:new ArrayList<>(map.keySet())){Object value=map.get(key);boolean own=false;for(SourceVersionRecord v:typed.versions){if(value instanceof SourceItem)own|=((SourceItem)value).sourceId.equals(v.sourceId)&&((SourceItem)value).itemKey.equals(v.itemKey);else if(value instanceof SourceVersionRecord)own|=((SourceVersionRecord)value).recordKey.equals(v.recordKey);else if(value instanceof NoteBook.Material)own|=v.recordKey.equals(((NoteBook.Material)value).sourceVersionKey);}if(own){map.remove(key);removed++;}}
                need(removed==2,"APPEND_EXACT_TWO_"+table);
            }
            Object initial=state(readOnly(before));Field sequence=detached.getClass().getDeclaredField("sequence");sequence.setAccessible(true);need(sequence.getLong(detached)==sequence.getLong(initial)+2,"APPEND_SEQUENCE");sequence.setLong(detached,sequence.getLong(initial));
            Method encode=NoteBook.class.getDeclaredMethod("encode",detached.getClass());encode.setAccessible(true);need(Arrays.equals(before,(byte[])encode.invoke(null,detached)),"APPEND_CHANGED_OLD_BOOK_STATE");
        }
        /** Pure RAM-only removal of the single owned Note for an exact comparison of every other Book field. */
        public void requireOnlyOwnNoteChange(byte[] after,String ownNoteId)throws Exception{
            NoteBook copy=readOnly(after);Object detached=state(copy);Field notes=detached.getClass().getDeclaredField("notes");notes.setAccessible(true);Map<?,?> map=(Map<?,?>)notes.get(detached);need(map.size()==1&&map.containsKey(ownNoteId),"ONE_OWN_NOTE");map.remove(ownNoteId);
            Method encode=NoteBook.class.getDeclaredMethod("encode",detached.getClass());encode.setAccessible(true);byte[] without=(byte[])encode.invoke(null,detached);need(Arrays.equals(before,without),"OTHER_BOOK_STATE_CHANGED");
        }
    }
}

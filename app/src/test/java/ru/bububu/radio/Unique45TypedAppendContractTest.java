package ru.bububu.radio;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import ru.bububu.radio.core.*;
import ru.bububu.radio.sources.*;
import static org.junit.Assert.*;

/** Closed synthetic HOST state only. No model, microphone, target app, or network. */
public final class Unique45TypedAppendContractTest {
    static final byte[] EMPTY={0x42,0x42,0x44,0x31,0,0,0,0};
    static byte[] asset(String n)throws Exception{return Unique45FixtureContractTest.asset(n);}
    static final class Setup implements AutoCloseable {
        final Unique45FixtureContractTest.Store store=new Unique45FixtureContractTest.Store();
        final byte[] initial=store.load(),receipt=Unique45FixtureContractTest.receipt(Unique45FixtureContract.APP,initial,true);
        final byte[] manifest=asset("manifest.json"),longBody=asset("long-body.txt"),unknownBody=asset("unknown-body.txt");
        final NoteBook book=new NoteBook(store);final Path root=Files.createTempDirectory("c4-typed-host"),cas=Files.createDirectory(root.resolve("cas")),notes=Files.createDirectory(root.resolve("notes"));
        final SourceBodyCache cache=new SourceBodyCache(cas.toFile());final Unique45FixtureContract.Fixtures legacy;
        Setup()throws Exception{
            book.enable45();legacy=Unique45FixtureContract.fixtures(manifest,longBody,unknownBody,book);legacy.appendMetadata(book);for(SourceVersionRecord v:legacy.versions){cache.put(v);book.selectSourceVersion(cache.get(v.withPayload(null)),false);}
            for(SourceRecord s:book.sources())book.updateSource(s.withState(s.enabled,s.etag,s.lastModified,s.lastSuccess,"DL1|HOST_FAILED_PREPARE|0|HOST_FAILED_PREPARE"));
            Files.write(notes.resolve(NoteMetadataReader.BASE),store.load());Files.write(notes.resolve(StartupSourceFacts45.RECEIPT),receipt);Files.write(notes.resolve(Unique45EmptyDraftGuard.NAME),EMPTY);
        }
        Unique45NotesAsrContract.Proof old()throws Exception{return Unique45NotesAsrContract.proveLegacy(store.load(),receipt,manifest,longBody,unknownBody);}
        Unique45NotesAsrContract.Proof four()throws Exception{return Unique45NotesAsrContract.proveTyped(store.load(),receipt,manifest,longBody,unknownBody);}
        Unique45FixtureContract.Fixtures typed()throws Exception{return Unique45FixtureContract.typedFixtures(manifest,longBody,unknownBody,book);}
        void append()throws Exception{Unique45NotesAsrContract.requireExactEmptyNotes(notes);Unique45NotesAsrContract.Proof p=old();p.requireCas(cas);Unique45FixtureContract.Fixtures f=typed();for(SourceVersionRecord v:f.versions)cache.put(v);f.appendMetadata(book);for(SourceVersionRecord v:f.versions)book.selectSourceVersion(cache.get(v.withPayload(null)),false);p.requireOnlyTypedAppend(store.load(),f);four().requireCas(cas);}
        public void close()throws Exception{try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    @Test public void appendFourRetainsEveryOldBookFieldAndCasAndUsesTypedLong()throws Exception{
        try(Setup s=new Setup()){
            byte[] before=s.store.load();Unique45NotesAsrContract.Proof old=s.old();Map<String,String> oldCas=old.requireCas(s.cas);s.append();
            Unique45NotesAsrContract.Proof four=s.four();assertEquals(4,four.versions.size());assertEquals(4,s.book.materials().size());assertEquals(4,s.book.sourceItems(s.legacy.source.id).size());assertEquals(6,s.book.sources().size());assertTrue(s.book.notes().isEmpty());assertEquals(Unique45FixtureContract.TYPED_LONG_ARTICLE,four.linkedVersion().articleId);
            for(Map.Entry<String,String> e:oldCas.entrySet())assertEquals(e.getValue(),four.requireCas(s.cas).get(e.getKey()));
            for(SourceRecord src:s.book.sources())assertEquals("DL1|HOST_FAILED_PREPARE|0|HOST_FAILED_PREPARE",src.lastError);
            old.requireOnlyTypedAppend(s.store.load(),s.typed());assertFalse(Arrays.equals(before,s.store.load()));assertArrayEquals(s.receipt,Files.readAllBytes(s.notes.resolve(StartupSourceFacts45.RECEIPT)));assertArrayEquals(EMPTY,Files.readAllBytes(s.notes.resolve(Unique45EmptyDraftGuard.NAME)));
            int writes=s.store.writes;assertThrows(Exception.class,s::append);assertEquals(writes,s.store.writes);
        }
    }
    @Test public void typedIdentityAndActualAdmissionKeepExactBodiesAndPublication()throws Exception{
        try(Setup s=new Setup()){
            SourceAdmission45 gate=new SourceAdmission45(new SourceClock(){public long wallMillis(){return java.time.Instant.parse("2026-10-26T12:00:00Z").toEpochMilli();}public long monotonicMillis(){return 1000;}});
            for(int n=0;n<2;n++){SourceVersionRecord old=s.legacy.versions.get(n),v=s.typed().versions.get(n);assertEquals(ChannelProfile.Kind.UNKNOWN,old.kind());assertEquals(ChannelProfile.Kind.ARTICLE_ANALYSIS,v.kind());assertNotEquals(old.itemKey,v.itemKey);assertNotEquals(old.articleId,v.articleId);assertNotEquals(old.versionKey,v.versionKey);assertEquals(old.bodyHash,v.bodyHash);assertEquals(old.body(),v.body());assertArrayEquals(old.publication.encode(),v.publication.encode());
                System.out.println("C4_TYPED\t"+v.itemKey+"\t"+v.articleId+"\t"+v.recordKey+"\t"+v.kind()+"\tcanonicalUTF16="+v.spokenText.length()+"\tbodySpan="+v.bodyStart+":"+v.bodyEnd+"\tbodySHA="+v.bodyHash);
                if(n==0)gate.require(v,false);else{SourceFailure failure=assertThrows(SourceFailure.class,()->gate.require(v,false));assertEquals("PUBLICATION_AUTOMATIC_REFUSED",failure.reason);gate.require(v,true);}
                assertEquals("PUBLICATION_UNTRUSTED_KIND",assertThrows(SourceFailure.class,()->gate.require(old,true)).reason);
            }
        }
    }
    @Test public void foreignOrPreparedReceiptAndOldOnlyCannotReachNotesGate()throws Exception{
        try(Setup s=new Setup()){
            int writes=s.store.writes;byte[] before=s.store.load();
            for(String app:List.of("ru.bububu.radio.test",Unique45FixtureContract.APP))for(boolean applied:new boolean[]{false,true})if(!app.equals(Unique45FixtureContract.APP)||!applied){byte[] r=Unique45FixtureContractTest.receipt(app,s.initial,applied);assertThrows(Exception.class,()->Unique45NotesAsrContract.proveLegacy(before,r,s.manifest,s.longBody,s.unknownBody));}
            assertThrows(Exception.class,s::four);assertEquals(writes,s.store.writes);assertArrayEquals(before,s.store.load());
        }
    }
    @Test public void receivedAtAndCacheLocatorArePartOfTheSealedProof()throws Exception{
        for(String field:List.of("receivedAt","cacheLocator"))try(Setup s=new Setup()){
            SourceVersionRecord v=s.book.sourceVersion(s.legacy.versions.get(0).recordKey);Field f=SourceVersionRecord.class.getField(field);f.setAccessible(true);if(field.equals("receivedAt"))f.setLong(v,v.receivedAt+1);else f.set(v,"foreign-cache-locator");byte[] changed=encode(s.book);int writes=s.store.writes;
            assertThrows(Exception.class,()->Unique45NotesAsrContract.proveLegacy(changed,s.receipt,s.manifest,s.longBody,s.unknownBody));assertEquals(writes,s.store.writes);
        }
    }
    @Test public void foreignNotesAndDirtyOrAbsentDraftRefuseBeforeAnyAppend()throws Exception{
        for(String variant:List.of("absent","dirty","trailing","backup","original","symlink"))try(Setup s=new Setup()){
            Path draft=s.notes.resolve(Unique45EmptyDraftGuard.NAME);if(variant.equals("absent"))Files.delete(draft);else if(variant.equals("dirty"))Files.write(draft,new byte[]{0x42,0x42,0x44,0x31,0,0,0,1});else if(variant.equals("trailing"))Files.write(draft,new byte[9]);else if(variant.equals("backup"))Files.write(s.notes.resolve("editing-drafts.bak"),EMPTY);else if(variant.equals("original"))Files.write(s.notes.resolve("foreign.wav"),new byte[]{1});else{Files.delete(draft);Files.createSymbolicLink(draft,s.notes.resolve(NoteMetadataReader.BASE));}
            byte[] before=s.store.load();int writes=s.store.writes;Map<String,String> cas=s.old().requireCas(s.cas);assertThrows(Exception.class,s::append);assertEquals(writes,s.store.writes);assertArrayEquals(before,s.store.load());assertEquals(cas,s.old().requireCas(s.cas));
        }
    }
    @Test public void missingCorruptForeignOrSymlinkCasRefuseBeforeWrites()throws Exception{
        for(String variant:List.of("missing","corrupt","foreign","symlink","temporary"))try(Setup s=new Setup()){
            Path file=s.cas.resolve(SourceLimits.sha256(s.legacy.versions.get(0).recordKey)+".cas");if(variant.equals("missing"))Files.delete(file);else if(variant.equals("corrupt"))Files.write(file,new byte[]{1,2,3});else if(variant.equals("foreign"))Files.write(s.cas.resolve("foreign.cas"),new byte[]{1});else if(variant.equals("temporary"))Files.write(s.cas.resolve("foreign.new"),new byte[]{1});else{Files.delete(file);Files.createSymbolicLink(file,s.notes.resolve(NoteMetadataReader.BASE));}
            byte[] before=s.store.load();int writes=s.store.writes;assertThrows(Exception.class,s::append);assertEquals(writes,s.store.writes);assertArrayEquals(before,s.store.load());
        }
    }
    @Test public void extraItemOrExistingNoteRefusesBeforeAppend()throws Exception{
        for(boolean note:new boolean[]{false,true})try(Setup s=new Setup()){
            if(note)s.book.beginRecording("HOST чужая заметка",null,null,null,1);else{s.typed().appendMetadata(s.book);}
            byte[] before=s.store.load();int writes=s.store.writes;assertThrows(Exception.class,s::append);assertEquals(writes,s.store.writes);assertArrayEquals(before,s.store.load());
        }
    }
    @Test public void exactFourCountsWithForeignSubstitutionStillRefuse()throws Exception{
        try(Setup s=new Setup()){
            Unique45FixtureContract.Fixtures typed=s.typed();SourceVersionRecord original=typed.versions.get(1);SourceItem foreign=SourceItem.create(typed.source.id,"foreign-c4-item",null,"https://elementy.ru/novosti_nauki/999999/foreign_c4","Чужая запись",-1,-1,1,null,null);
            SourceVersionRecord v=SourceVersionRecord.source45(typed.source,foreign,new ExtractionResult(original.body(),"Чужая запись","Чужая запись","full","","source45-html",SourceProfiles45.PARSER),original.publication,original.contextClass,1);
            Unique45FixtureContract.Fixtures replacement=new Unique45FixtureContract.Fixtures(typed.source,List.of(typed.items.get(0),foreign),List.of(typed.versions.get(0),v));replacement.appendMetadata(s.book);for(SourceVersionRecord value:replacement.versions)s.book.selectSourceVersion(value,false);
            assertEquals(4,s.book.materials().size());assertEquals(4,s.book.sourceItems(typed.source.id).size());int writes=s.store.writes;byte[] before=s.store.load();assertThrows(Exception.class,s::four);assertEquals(writes,s.store.writes);assertArrayEquals(before,s.store.load());
        }
    }
    @Test public void disabledElementyRefusesBeforeCasWritesWithoutEnablingIt()throws Exception{
        try(Setup s=new Setup()){
            SourceRecord e=s.book.source(s.legacy.source.id);s.book.updateSource(e.withState(false,e.etag,e.lastModified,e.lastSuccess,e.lastError));int writes=s.store.writes;byte[] before=s.store.load();assertThrows(Exception.class,s::append);assertEquals(writes,s.store.writes);assertArrayEquals(before,s.store.load());assertFalse(s.book.source(e.id).enabled);
        }
    }
    @Test public void noteDeltaAllowsOnlyOneOwnedTypedLinkedNoteAndNoOtherChange()throws Exception{
        try(Setup s=new Setup()){
            s.append();Unique45NotesAsrContract.Proof four=s.four();NoteBook.Note own=s.book.beginRecordingLinkedSource("HOST synthetic",four.linkedRecord,2);four.requireOnlyOwnNoteChange(s.store.load(),own.id);assertEquals(Unique45FixtureContract.TYPED_LONG_ARTICLE,own.linkedArticle);
            SourceRecord src=s.book.sources().get(0);s.book.updateSource(src.withState(src.enabled,src.etag,src.lastModified,src.lastSuccess,"FOREIGN_CHANGE"));assertThrows(Exception.class,()->four.requireOnlyOwnNoteChange(s.store.load(),own.id));
        }
    }
    static byte[] encode(NoteBook book)throws Exception{Field f=NoteBook.class.getDeclaredField("state");f.setAccessible(true);Object state=f.get(book);Method encode=NoteBook.class.getDeclaredMethod("encode",state.getClass());encode.setAccessible(true);return (byte[])encode.invoke(null,state);}
}

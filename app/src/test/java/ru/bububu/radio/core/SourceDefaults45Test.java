package ru.bububu.radio.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.CRC32;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import ru.bububu.radio.models.PackCatalog;
import ru.bububu.radio.sources.*;
import static org.junit.Assert.*;

public class SourceDefaults45Test {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    static final class IO implements SourceDefaults45.IO {
        final File root,files,noBackup,notes,prefs;final StartupSourceFacts45 probe;
        int bookCalls,receiptCalls;boolean failBook,failAfterBook;int failReceipt;byte[] prepared,lastBook;String app="fixture.app";
        IO(File root)throws Exception{this.root=root;files=new File(root,"files");noBackup=new File(root,"nobackup");notes=new File(noBackup,"notes");prefs=new File(root,"shared_prefs");Files.createDirectory(files.toPath());Files.createDirectory(noBackup.toPath());probe=new StartupSourceFacts45(files,noBackup,prefs);}
        public String applicationId(){return app;}
        public byte[][] readReceipts()throws IOException{return read(StartupSourceFacts45.RECEIPT,65536);}
        public byte[][] readBooks()throws IOException{return read(NoteMetadataReader.BASE,NoteBook.MAX_METADATA_BYTES);}
        byte[][] read(String base,int cap)throws IOException{byte[][] result=new byte[3][];String[] suffix={"",".bak",".new"};for(int i=0;i<3;i++){Path f=new File(notes,base+suffix[i]).toPath();BasicFileAttributes a;try{a=Files.readAttributes(f,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException absent){continue;}if(!a.isRegularFile()||a.size()>cap)throw new IOException("inert fixture candidate");result[i]=Files.readAllBytes(f);}return result;}
        public StartupSourceFacts45.Snapshot facts(byte[] owned,boolean receipt){return probe.capture(owned,receipt);}
        public void ensureEmptyNotesDirectory()throws IOException{Files.createDirectories(notes.toPath());File[] files=notes.listFiles();if(files==null||files.length!=0)throw new IOException("not empty");}
        public void requirePeak(long additional)throws IOException{if(additional<0||additional>4*65536L)throw new IOException("fixture peak bound");}
        public void writeReceipt(byte[] bytes)throws IOException{receiptCalls++;if(prepared==null)prepared=bytes.clone();if(failReceipt==receiptCalls)throw new IOException("injected receipt failure");atomic(StartupSourceFacts45.RECEIPT,bytes);}
        public void writeBook(byte[] bytes)throws IOException{bookCalls++;lastBook=bytes.clone();if(failBook)throw new IOException("before Book");atomic(NoteMetadataReader.BASE,bytes);if(failAfterBook)throw new IOException("after Book");}
        void atomic(String name,byte[] bytes)throws IOException{Path target=new File(notes,name).toPath(),pending=new File(notes,name+".new").toPath();Files.write(pending,bytes);Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        void reset(){bookCalls=receiptCalls=0;failBook=failAfterBook=false;failReceipt=0;}
        NoteBook book(){return new NoteBook(new NoteBook.Storage(){public byte[] load(){try{return readBooks()[0];}catch(IOException e){throw new IllegalStateException(e);}}public void save(byte[] bytes){try{atomic(NoteMetadataReader.BASE,bytes);}catch(IOException e){throw new IllegalStateException(e);}}});}
        File preference(String namespace,String xml)throws Exception{Files.createDirectories(prefs.toPath());File f=new File(prefs,namespace+".xml");Files.write(f.toPath(),xml.getBytes(StandardCharsets.UTF_8));return f;}
    }
    @Test public void oldOwnedSixAppliedAndPreparedReceiptsRemainRecognized()throws Exception{
        for(boolean applied:new boolean[]{false,true}){
            IO io=new IO(temp.newFolder());io.failBook=true;SourceDefaults45.run(io);
            Class<?> type=Class.forName("ru.bububu.radio.core.SourceDefaults45$Receipt");java.lang.reflect.Method decode=type.getDeclaredMethod("decode",byte[].class);decode.setAccessible(true);Object original=decode.invoke(null,(Object)io.readReceipts()[0]);
            java.lang.reflect.Field af=type.getDeclaredField("attempt"),pf=type.getDeclaredField("proof");af.setAccessible(true);pf.setAccessible(true);String attempt=(String)af.get(original);List<SourceRecord> sources=new ArrayList<>();Map<String,String> bindings=new TreeMap<>();for(SourceProfiles45.Profile p:SourceProfiles45.legacySix()){sources.add(p.seed());bindings.put(p.id,p.seed().id);}byte[] target=NoteBook.fresh45Target(sources,new Seed45Marker(attempt,Seed45Marker.POLICY,SourceProfiles45.definitionSetHash(),bindings));
            java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor(String.class,String.class,String.class,byte[].class,byte[].class,boolean.class);ctor.setAccessible(true);Object legacy=ctor.newInstance(io.app,attempt,SourceProfiles45.definitionSetHash(),pf.get(original),target,applied);java.lang.reflect.Method encode=type.getDeclaredMethod("encode");encode.setAccessible(true);io.atomic(StartupSourceFacts45.RECEIPT,(byte[])encode.invoke(legacy));if(applied){io.atomic(NoteMetadataReader.BASE,target);io.preference("demo_ui_v1",map("<set name='batch_source_ids'/>"));}io.reset();
            assertEquals(applied?SourceDefaults45.Status.ALREADY_DECIDED:SourceDefaults45.Status.RECOVERED,SourceDefaults45.run(io).status);assertArrayEquals(target,io.readBooks()[0]);if(applied)assertEquals(0,io.bookCalls+io.receiptCalls);
        }
    }
    static String map(String content){return "<?xml version='1.0' encoding='utf-8'?><map>"+content+"</map>";}
    static byte[] emptyBook()throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream o=new DataOutputStream(b);o.writeInt(0x424e4f54);o.writeInt(1);o.writeLong(0);for(int n=0;n<4;n++)o.writeInt(0);o.flush();CRC32 crc=new CRC32();crc.update(b.toByteArray());o.writeLong(crc.getValue());byte[] bytes=b.toByteArray();NoteBook.validateSchema1(bytes);return bytes;}
    @Test public void everySettingsOnlyValueIncludingFalseAndEmptyPreservesBytesAndWritesNothing()throws Exception{
        List<String[]> cases=new ArrayList<>();for(String key:List.of("silero","giga-notes","bundled-v1"))for(String value:List.of("false","true"))cases.add(new String[]{"local-model-choice","<boolean name='"+key+"' value='"+value+"'/>"});
        for(String skin:List.of("neo","bubble"))cases.add(new String[]{"demo_ui_v1","<string name='skin'>"+skin+"</string>"});
        for(String ns:List.of("RadioActivity","ru.bububu.radio.RadioActivity"))for(String value:List.of("false","true"))cases.add(new String[]{ns,"<boolean name='notificationsAsked' value='"+value+"'/>"});
        for(int count:List.of(3,5,10,20))cases.add(new String[]{"demo_ui_v1","<int name='batch_per_source' value='"+count+"'/>"});cases.add(new String[]{"demo_ui_v1","<set name='batch_source_ids'/>"});cases.add(new String[]{"demo_ui_v1",""});
        for(String[] seed:cases){IO io=new IO(temp.newFolder());File f=io.preference(seed[0],map(seed[1]));byte[] before=Files.readAllBytes(f.toPath());assertEquals(seed[0]+seed[1],StartupSourceFacts45.State.LEGACY_PRESERVE,io.probe.capture().state);assertEquals(SourceDefaults45.Status.LEGACY_PRESERVE,SourceDefaults45.run(io).status);assertEquals(0,io.bookCalls);assertEquals(0,io.receiptCalls);assertArrayEquals(before,Files.readAllBytes(f.toPath()));assertFalse(io.notes.exists());}
    }
    @Test public void invalidPrefsSymlinksUnknownArtifactAndPartialModelAreUncertain()throws Exception{
        for(String content:List.of("<map>",map("<int name='silero' value='0'/>"),"<!DOCTYPE map [<!ENTITY x SYSTEM 'file:///not-readable'>]><map><string name='x'>&x;</string></map>")){
            IO io=new IO(temp.newFolder());File f=io.preference("local-model-choice",content);byte[] before=Files.readAllBytes(f.toPath());assertEquals(StartupSourceFacts45.State.UNCERTAIN,io.probe.capture().state);assertEquals(SourceDefaults45.Status.RECOVERY_REQUIRED,SourceDefaults45.run(io).status);assertArrayEquals(before,Files.readAllBytes(f.toPath()));assertEquals(0,io.bookCalls+io.receiptCalls);
        }
        IO io=new IO(temp.newFolder());Files.createDirectories(io.prefs.toPath());File outside=new File(io.root,"outside");Files.write(outside.toPath(),map("<boolean name='silero' value='false'/>").getBytes(StandardCharsets.UTF_8));Files.createSymbolicLink(new File(io.prefs,"local-model-choice.xml").toPath(),outside.toPath());assertEquals(StartupSourceFacts45.State.UNCERTAIN,io.probe.capture().state);assertEquals(0,SourceDefaults45.run(io).bookWrites);
        IO partial=new IO(temp.newFolder());Files.createDirectories(new File(partial.files,"model-packs/silero-stage-unknown").toPath());assertEquals(StartupSourceFacts45.State.UNCERTAIN,partial.probe.capture().state);assertEquals(SourceDefaults45.Status.RECOVERY_REQUIRED,SourceDefaults45.run(partial).status);assertEquals(0,partial.bookCalls+partial.receiptCalls);
    }
    @Test public void modelOnlyReadyGenerationIsPriorUseWithoutReadingWeightsOrSeeding()throws Exception{
        for(PackCatalog catalog:List.of(PackCatalog.SILERO,PackCatalog.GIGAAM)){
            IO io=new IO(temp.newFolder());File root=new File(io.files,"model-packs"),generation=new File(root,catalog.kind+"-g-"+UUID.randomUUID());Files.createDirectories(generation.toPath());Files.write(new File(generation,"manifest.txt").toPath(),catalog.manifest());
            // Sparse synthetic size-only fixtures prove prior use, never model validity/readiness.
            for(PackCatalog.Entry entry:catalog.files)try(RandomAccessFile file=new RandomAccessFile(new File(generation,entry.name),"rw")){file.setLength(entry.bytes);}
            Files.write(new File(root,catalog.kind+".ready").toPath(),generation.getName().getBytes(StandardCharsets.US_ASCII));assertEquals(StartupSourceFacts45.State.LEGACY_PRESERVE,io.probe.capture().state);assertEquals(SourceDefaults45.Status.LEGACY_PRESERVE,SourceDefaults45.run(io).status);assertEquals(0,io.bookCalls+io.receiptCalls);
        }
    }
    @Test public void allBookLayoutsAndEmptyMetadataCannotBecomeFresh()throws Exception{
        byte[] book=emptyBook();String[] suffix={"",".bak",".new"};for(int bits=0;bits<8;bits++){
            IO io=new IO(temp.newFolder());Files.createDirectory(io.notes.toPath());for(int i=0;i<3;i++)if((bits&(1<<i))!=0)Files.write(new File(io.notes,NoteMetadataReader.BASE+suffix[i]).toPath(),book);
            SourceDefaults45.Result r=SourceDefaults45.run(io);if(bits==0)assertEquals(SourceDefaults45.Status.SEEDED,r.status);else{assertNotEquals(SourceDefaults45.Status.SEEDED,r.status);assertEquals(0,io.bookCalls+io.receiptCalls);if(bits==4)assertEquals(StartupSourceFacts45.State.UNCERTAIN,io.probe.capture().state);}
        }
    }
    @Test public void genuinelyCleanSeedsOnceBeforeBootstrapAndLaterChoicesNeverRefill()throws Exception{
        IO io=new IO(temp.newFolder());assertEquals(StartupSourceFacts45.State.PROVEN_FRESH,io.probe.capture().state);assertEquals(SourceDefaults45.Status.SEEDED,SourceDefaults45.run(io).status);assertEquals(1,io.bookCalls);assertEquals(2,io.receiptCalls);NoteBook book=io.book();assertEquals(4,book.schema());assertEquals(6,book.sources().size());assertEquals(0,book.materials().size());assertEquals(0,book.notes().size());assertNotNull(book.seed45Marker());assertFalse(io.prefs.exists());
        io.preference("local-model-choice",map("<boolean name='silero' value='false'/>"));for(SourceRecord source:new ArrayList<>(book.sources()))book.deleteSource(source.id,new PlayerController.SavedState(null,Map.of(),Map.of()));io.reset();assertEquals(SourceDefaults45.Status.ALREADY_DECIDED,SourceDefaults45.run(io).status);assertEquals(0,io.bookCalls+io.receiptCalls);assertEquals(0,io.book().sources().size());
    }
    @Test public void preparedRecoveryUsesSameAttemptAndExactOwnedBookTemporary()throws Exception{
        for(boolean pending:List.of(false,true)){
            IO io=new IO(temp.newFolder());io.failBook=true;SourceDefaults45.Result failed=SourceDefaults45.run(io);assertEquals(SourceDefaults45.Status.RECOVERY_REQUIRED,failed.status);assertTrue(failed.blockStartup);assertNotNull(io.prepared);byte[] target=io.lastBook;
            if(pending)Files.write(new File(io.notes,NoteMetadataReader.PENDING).toPath(),target);io.reset();assertEquals(SourceDefaults45.Status.RECOVERED,SourceDefaults45.run(io).status);assertEquals(1,io.bookCalls);assertEquals(1,io.receiptCalls);assertArrayEquals(target,io.readBooks()[0]);assertEquals(6,io.book().sources().size());
        }
    }
    @Test public void preparedRecoveryRefusesEveryNewSettingsOrUserFact()throws Exception{
        for(String[] choice:List.of(new String[]{"local-model-choice","<boolean name='silero' value='false'/>"},new String[]{"local-model-choice","<boolean name='giga-notes' value='false'/>"},new String[]{"local-model-choice","<boolean name='bundled-v1' value='true'/>"},new String[]{"demo_ui_v1","<string name='skin'>neo</string>"},new String[]{"ru.bububu.radio.RadioActivity","<boolean name='notificationsAsked' value='false'/>"})){
            IO io=new IO(temp.newFolder());io.failBook=true;SourceDefaults45.run(io);File f=io.preference(choice[0],map(choice[1]));byte[] before=Files.readAllBytes(f.toPath());io.reset();assertEquals(SourceDefaults45.Status.RECOVERY_REQUIRED,SourceDefaults45.run(io).status);assertEquals(0,io.bookCalls+io.receiptCalls);assertArrayEquals(before,Files.readAllBytes(f.toPath()));
        }
        IO io=new IO(temp.newFolder());io.failBook=true;SourceDefaults45.run(io);Files.write(new File(io.notes,NoteMetadataReader.BASE).toPath(),emptyBook());byte[] before=io.readBooks()[0];io.reset();assertEquals(SourceDefaults45.Status.RECOVERY_REQUIRED,SourceDefaults45.run(io).status);assertArrayEquals(before,io.readBooks()[0]);assertEquals(0,io.bookCalls+io.receiptCalls);
    }
    @Test public void committedBookCrashOnlyFinishesReceiptDespiteLaterUserPrefs()throws Exception{
        IO io=new IO(temp.newFolder());io.failReceipt=2;assertEquals(SourceDefaults45.Status.RECOVERY_REQUIRED,SourceDefaults45.run(io).status);byte[] committed=io.readBooks()[0];io.preference("demo_ui_v1",map("<set name='batch_source_ids'/>"));io.reset();assertEquals(SourceDefaults45.Status.ALREADY_DECIDED,SourceDefaults45.run(io).status);assertEquals(0,io.bookCalls);assertEquals(1,io.receiptCalls);assertArrayEquals(committed,io.readBooks()[0]);
    }
    @Test public void loneReceiptNewAndAppliedSiblingNeverAuthorizeBookReplay()throws Exception{
        IO lone=new IO(temp.newFolder());lone.failBook=true;SourceDefaults45.run(lone);Files.move(new File(lone.notes,StartupSourceFacts45.RECEIPT).toPath(),new File(lone.notes,StartupSourceFacts45.RECEIPT+".new").toPath());lone.reset();assertEquals(SourceDefaults45.Status.RECOVERY_REQUIRED,SourceDefaults45.run(lone).status);assertEquals(0,lone.bookCalls+lone.receiptCalls);
        IO io=new IO(temp.newFolder());assertEquals(SourceDefaults45.Status.SEEDED,SourceDefaults45.run(io).status);Files.delete(new File(io.notes,NoteMetadataReader.BASE).toPath());Files.write(new File(io.notes,StartupSourceFacts45.RECEIPT+".bak").toPath(),io.prepared);io.reset();assertEquals(SourceDefaults45.Status.RECOVERY_REQUIRED,SourceDefaults45.run(io).status);assertEquals(0,io.bookCalls+io.receiptCalls);
    }
}

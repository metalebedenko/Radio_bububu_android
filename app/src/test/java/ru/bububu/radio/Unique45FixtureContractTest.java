package ru.bububu.radio;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import ru.bububu.radio.core.*;
import ru.bububu.radio.sources.*;
import static org.junit.Assert.*;

public class Unique45FixtureContractTest {
    static final String ATTEMPT="01234567-89ab-4cde-8fab-0123456789ab";
    static byte[] target(){List<SourceRecord> sources=new ArrayList<>();Map<String,String> bindings=new TreeMap<>();for(SourceProfiles45.Profile p:SourceProfiles45.six()){SourceRecord s=p.seed();sources.add(s);bindings.put(p.id,s.id);}return NoteBook.fresh45Target(sources,new Seed45Marker(ATTEMPT,Seed45Marker.POLICY,SourceProfiles45.definitionSetHash(),bindings));}
    static byte[] receipt(String app,byte[] target,boolean applied)throws Exception{
        Class<?> type=Class.forName("ru.bububu.radio.core.SourceDefaults45$Receipt");Constructor<?> c=type.getDeclaredConstructor(String.class,String.class,String.class,byte[].class,byte[].class,boolean.class);c.setAccessible(true);Object value=c.newInstance(app,ATTEMPT,SourceProfiles45.definitionSetHash(),freshProof(),target,applied);Method encode=type.getDeclaredMethod("encode");encode.setAccessible(true);return (byte[])encode.invoke(value);
    }
    static byte[] freshProof()throws Exception{
        java.nio.file.Path root=java.nio.file.Files.createTempDirectory("qa45-fresh-proof"),files=java.nio.file.Files.createDirectory(root.resolve("files")),noBackup=java.nio.file.Files.createDirectory(root.resolve("no_backup"));
        try{StartupSourceFacts45.Snapshot snapshot=new StartupSourceFacts45(files.toFile(),noBackup.toFile(),root.resolve("shared_prefs").toFile()).capture();assertEquals(StartupSourceFacts45.State.PROVEN_FRESH,snapshot.state);return snapshot.manifest();}finally{java.nio.file.Files.delete(files);java.nio.file.Files.delete(noBackup);java.nio.file.Files.delete(root);}
    }
    static class Store implements NoteBook.Storage{byte[] bytes=target();int writes;public byte[] load(){return bytes.clone();}public void save(byte[] b){writes++;bytes=b.clone();}}
    static byte[] asset(String name)throws Exception{try(InputStream in=Unique45FixtureContractTest.class.getClassLoader().getResourceAsStream("qa45-source-fixture/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()){assertNotNull(in);byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}}
    @Test public void identityRefusesMainStaticQaForeignTargetAndInstrumentPackageBeforeMutation(){
        for(String wrong:List.of("ru.bububu.radio.test","ru.bububu.radio.setupqa","ru.bububu.radio.internalqa45.j20261026a01","ru.bububu.radio.internalqa45.j20261026a02","")){
            assertThrows(IllegalStateException.class,()->Unique45FixtureContract.identity(wrong,Unique45FixtureContract.TEST,wrong));
            assertThrows(IllegalStateException.class,()->Unique45FixtureContract.identity(Unique45FixtureContract.APP,wrong,Unique45FixtureContract.APP));
            assertThrows(IllegalStateException.class,()->Unique45FixtureContract.identity(Unique45FixtureContract.APP,Unique45FixtureContract.TEST,wrong));
        }
        Unique45FixtureContract.identity(Unique45FixtureContract.APP,Unique45FixtureContract.TEST,Unique45FixtureContract.APP);
    }
    @Test public void onlyExactAppliedOwnFreshSixSnapshotIsAcceptedWithoutWrites()throws Exception{
        Store s=new Store();byte[] before=s.load(),receipt=receipt(Unique45FixtureContract.APP,before,true);assertEquals(ATTEMPT,Unique45FixtureContract.freshBook(before,receipt).attemptId);assertEquals(0,s.writes);assertArrayEquals(before,s.load());
        assertThrows(Exception.class,()->Unique45FixtureContract.freshBook(before,receipt(Unique45FixtureContract.APP,before,false)));
        assertThrows(Exception.class,()->Unique45FixtureContract.freshBook(before,receipt("ru.bububu.radio.test",before,true)));
        byte[] corrupt=receipt.clone();corrupt[corrupt.length-1]^=1;assertThrows(Exception.class,()->Unique45FixtureContract.freshBook(before,corrupt));
        assertThrows(Exception.class,()->Unique45FixtureContract.freshBook(null,receipt));assertEquals(0,s.writes);
    }
    @Test public void anyAdditionalBookChoiceOrNoteRefusesRatherThanReplacingSnapshot()throws Exception{
        Store s=new Store();byte[] initial=s.load(),receipt=receipt(Unique45FixtureContract.APP,initial,true);NoteBook b=new NoteBook(s);b.enable45();b.updateSource(b.sources().get(0).withState(false,null,null,0,null));byte[] changed=s.load();int writes=s.writes;assertThrows(Exception.class,()->Unique45FixtureContract.freshBook(changed,receipt));assertEquals(writes,s.writes);assertArrayEquals(changed,s.load());
        Store n=new Store();NoteBook nb=new NoteBook(n);nb.beginRecording("Синтетическая заметка",null,null,null,1);assertThrows(Exception.class,()->Unique45FixtureContract.freshBook(n.load(),receipt));
    }
    @Test public void fixedAssetsAppendOnlyThroughBookApisPreservesSixAttemptAndRejectsSecondIntake()throws Exception{
        Store s=new Store();byte[] initial=s.load(),receipt=receipt(Unique45FixtureContract.APP,initial,true);Seed45Marker marker=Unique45FixtureContract.freshBook(initial,receipt);NoteBook b=new NoteBook(s);b.enable45();
        Unique45FixtureContract.Fixtures fixture=Unique45FixtureContract.fixtures(asset("manifest.json"),asset("long-body.txt"),asset("unknown-body.txt"),b);
        fixture.appendMetadata(b);for(SourceVersionRecord v:fixture.versions){SourceVersionRecord saved=b.sourceVersion(v.recordKey);assertNotNull("metadata "+v.recordKey,saved);List<String> differences=new ArrayList<>();for(Field field:SourceVersionRecord.class.getFields())if(!field.getName().equals("spokenText")&&!field.getName().equals("publication")&&!java.util.Objects.equals(field.get(saved),field.get(v)))differences.add(field.getName());assertTrue(differences.toString(),saved.sameMetadata(v));b.selectSourceVersion(v,false);}
        NoteBook r=new NoteBook(s);assertEquals(6,r.sources().size());assertEquals(marker.bindings,r.seed45Marker().bindings);assertEquals(marker.attemptId,r.seed45Marker().attemptId);assertEquals(2,r.materials().size());assertTrue(r.notes().isEmpty());
        for(SourceVersionRecord v:fixture.versions){assertEquals(v.body(),r.sourceVersion(v.recordKey).body());assertEquals(v.versionKey,r.sourceVersion(v.recordKey).versionKey);}
        assertTrue(fixture.versions.get(0).spokenText.length()>20000);assertTrue(fixture.versions.get(0).spokenText.length()<100000);assertEquals(SourcePublication45.Context.UNKNOWN_MANUAL,fixture.versions.get(1).contextClass);
        int writes=s.writes;assertThrows(Exception.class,()->Unique45FixtureContract.freshBook(s.load(),receipt));assertEquals(writes,s.writes);
    }
    @Test public void alteredAssetRefusesBeforeAnyBookMutation()throws Exception{
        Store s=new Store();byte[] before=s.load(),body=asset("long-body.txt");body[0]^=1;NoteBook book=new NoteBook(s);
        assertThrows(IllegalStateException.class,()->Unique45FixtureContract.fixtures(asset("manifest.json"),body,asset("unknown-body.txt"),book));assertEquals(0,s.writes);assertArrayEquals(before,s.load());
    }
}

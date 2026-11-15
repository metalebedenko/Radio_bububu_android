package ru.bububu.radio.core;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import ru.bububu.radio.sources.*;
import static org.junit.Assert.*;

public class Book45Test {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    static final class Store implements NoteBook.MigrationStorage {
        byte[] bytes;int writes,fail;boolean unreadable;final MigrationCapsuleStore capsules;
        Store(File directory){capsules=new MigrationCapsuleStore(directory,f->1024L*1024*1024);}
        public byte[] load(){if(unreadable)throw new IllegalStateException("injected read error");return bytes==null?null:bytes.clone();}
        public void save(byte[] value){writes++;if(fail==1)throw new IllegalStateException("before commit");bytes=value.clone();if(fail==2)throw new IllegalStateException("after commit");if(fail==3){unreadable=true;throw new IllegalStateException("unknown commit");}}
        byte[][] images(){return new byte[][]{bytes,null,null};}
        public MigrationStamp prepareMigration(byte[] baseline,int size){return capsules.prepare(baseline,size,images());}
        public void verifyMigrationStamp(MigrationStamp s){capsules.verify(s);}
        public UpgradeRollbackRef prepareSchema3Upgrade(byte[] baseline,int size){return capsules.prepareSchema3Upgrade(baseline,size,images());}
        public void verifyUpgradeRollback(UpgradeRollbackRef r,MigrationStamp s){capsules.verifyUpgradeRollback(r,s);}
        public Format4BaselineRef prepareFormat4Upgrade(byte[] baseline,int size){return capsules.prepareFormat4(baseline,size,images());}
        public void verifyFormat4Baseline(Format4BaselineRef r){capsules.verifyFormat4(r);}
    }
    static SourceRecord source(int n){SourceRecord s=SourceRecord.create("rss","https://example.org/feed"+n,"Издание "+n,"https://example.org/feed"+n,"closed45",true);return new ChannelProfile("p"+n,"c"+n,"1",s.key,s.adapterId,"source45-v1",ChannelProfile.Kind.ARTICLE_ANALYSIS).bind(s);}
    static SourceItem item(SourceRecord s){return SourceItem.create(s.id,"item",null,"https://example.org/story","Заголовок",-1,-1,1,null,null);}
    static SourceVersionRecord version(SourceRecord s,SourceItem i,String body)throws Exception{
        SourcePublication45 p=new SourcePublication45(SourcePublication45.parse(SourcePublication45.Role.ORIGINAL_PUBLISHED,"2011-02","fixture",i.url,"footer",false),List.of(),"a".repeat(64),"vokrug45","","b".repeat(64));
        return SourceVersionRecord.source45(s,i,new ExtractionResult(body,"Заголовок","Заголовок","full","","source45-html","source45-v1"),p,SourcePublication45.Context.DATED,1);
    }
    @Test public void readonlyLegacyAndFirstSourceWritePreserveExactBaseline()throws Exception{
        Store store=new Store(temp.newFolder());NoteBook b=new NoteBook(store);NoteBook.Note note=b.beginRecording("Заметка",null,null,null,1);b.edit(note.id,"Заметка","Сохранённый текст.");byte[] baseline=store.load();int writes=store.writes;
        NoteBook loaded=new NoteBook(store);loaded.enable45();assertEquals(1,loaded.schema());assertEquals(writes,store.writes);assertArrayEquals(baseline,store.load());
        SourceRecord source=source(1);loaded.commitSource(source,List.of());assertEquals(4,loaded.schema());assertEquals(1,loaded.format4Baseline().baselineSchema);assertTrue(loaded.format4Baseline().stamp.matches(baseline));store.capsules.verifyFormat4(loaded.format4Baseline());
        NoteBook restart=new NoteBook(store);assertEquals("Сохранённый текст.",restart.find(note.id).text);assertEquals(4,restart.schema());assertNull(restart.migrationWarning());assertEquals(source.id,restart.sources().get(0).id);
    }
    @Test public void schema2And3UpgradeKeepOriginalCapsulesAndRetiredLifecycle()throws Exception{
        for(int schema:List.of(2,3)){
            Store store=new Store(temp.newFolder());NoteBook b=new NoteBook(store);SourceRecord first=source(schema);b.commitSource(first,List.of());MigrationStamp origin=b.migrationStamp();
            if(schema==3)b.deleteSource(first.id,new PlayerController.SavedState(null,Map.of(),Map.of()));assertEquals(schema,b.schema());byte[] baseline=store.load();
            b.enable45();SourceRecord second=source(schema+10);b.commitSource(second,List.of());assertEquals(4,b.schema());assertTrue(b.format4Baseline().stamp.matches(baseline));assertEquals(schema,b.format4Baseline().baselineSchema);assertEquals(origin.capsuleId,b.migrationStamp().capsuleId);store.capsules.verify(origin);
            NoteBook r=new NoteBook(store);assertEquals(4,r.schema());assertEquals(schema==3,r.isSourceRetired(first.id));r.updateSource(second.withState(false,null,null,0,null));assertEquals(4,new NoteBook(store).schema());assertNull(r.migrationWarning());
        }
    }
    @Test public void longSourceBookCasMaterialAndProgressRoundtripRetainV2Binding()throws Exception{
        Store store=new Store(temp.newFolder());NoteBook b=new NoteBook(store);b.enable45();SourceRecord s=source(1);SourceItem i=item(s);b.commitSource(s,List.of(i));
        String body="Длинная статья. "+"а".repeat(68599-16);SourceVersionRecord v=version(s,i,body);b.saveSourceVersion(v.withPayload(null));
        SourceBodyCache cache=new SourceBodyCache(temp.newFolder());cache.put(v);SourceVersionRecord checked=cache.get(v.withPayload(null));assertEquals(v.spokenText,checked.spokenText);
        NoteBook.Material m=b.selectSourceVersion(checked,false);assertEquals(v.recordKey,m.sourceVersionKey);assertEquals(v.versionKey,m.asArticle().sourceVersion.versionKey);assertEquals(body,m.asArticle().sourceVersion.body());
        NoteBook r=new NoteBook(store);SourceVersionRecord saved=r.sourceVersion(v.recordKey);assertEquals(v.versionKey,saved.versionKey);assertArrayEquals(v.publication.encode(),saved.publication.encode());assertEquals(v.bodyStart,saved.bodyStart);assertEquals(v.spokenText,r.materials().get(0).asArticle().text);assertNotNull(r.materials().get(0).asArticle().sourceVersion);
        NoteBook.Note note=r.beginRecordingLinkedSource("Контекст",v.recordKey,2);assertEquals(v.recordKey,note.linkedSourceVersionKey);assertThrows(IllegalArgumentException.class,()->r.edit(note.id,"Контекст","я".repeat(20001)));
        assertThrows(IllegalArgumentException.class,()->new NoteDraftStore.Draft("draft",0,"Текст","я".repeat(20001)));
    }
    @Test public void freshOriginHasNoInventedLegacyImageAndNeverRefillsRemovedSources()throws Exception{
        List<SourceRecord> sources=new ArrayList<>();Map<String,String> bindings=new LinkedHashMap<>();for(int n=0;n<6;n++){SourceRecord s=source(n);sources.add(s);bindings.put("channel"+n,s.id);}
        Seed45Marker marker=new Seed45Marker(UUID.randomUUID().toString(),Seed45Marker.POLICY,"a".repeat(64),bindings);Store store=new Store(temp.newFolder());store.bytes=NoteBook.fresh45Target(sources,marker);byte[] before=store.load();
        NoteBook b=new NoteBook(store);assertEquals(0,store.writes);assertEquals(4,b.schema());assertNull(b.migrationStamp());assertNull(b.format4Baseline());assertEquals(marker.attemptId,b.seed45Marker().attemptId);assertArrayEquals(before,store.load());
        b.deleteSource(sources.get(0).id,new PlayerController.SavedState(null,Map.of(),Map.of()));NoteBook r=new NoteBook(store);assertEquals(5,r.sources().size());assertEquals(marker.attemptId,r.seed45Marker().attemptId);assertEquals(4,r.schema());assertNull(r.migrationWarning());
    }
    @Test public void saveFailuresKeepOldOrNewAuthorityAndUnknownLatches()throws Exception{
        for(int mode:List.of(1,2,3)){
            Store store=new Store(temp.newFolder());NoteBook b=new NoteBook(store);b.beginRecording("Старое",null,null,null,1);byte[] old=store.load();b.enable45();store.fail=mode;
            if(mode==2){b.commitSource(source(mode),List.of());assertEquals(4,b.schema());assertFalse(b.storageUncertain());}
            else{assertThrows(IllegalStateException.class,()->b.commitSource(source(mode),List.of()));if(mode==1){assertArrayEquals(old,store.load());assertEquals(1,b.schema());}else assertTrue(b.storageUncertain());}
        }
    }
    @Test public void missingFormat4CapsuleWarnsWithoutReplacingCommittedUserBook()throws Exception{
        Store store=new Store(temp.newFolder());NoteBook b=new NoteBook(store);b.beginRecording("Сохранено",null,null,null,1);b.enable45();b.commitSource(source(1),List.of());byte[] committed=store.load();Format4BaselineRef ref=b.format4Baseline();
        File[] capsules=temp.getRoot().listFiles();assertNotNull(capsules);for(File directory:capsules){File file=new File(directory,"notes-migration/"+ref.stamp.capsuleId+".bin");if(file.isFile())Files.delete(file.toPath());}
        NoteBook r=new NoteBook(store);assertNotNull(r.migrationWarning());assertArrayEquals(committed,store.load());assertEquals(1,r.notes().size());assertEquals(4,r.schema());
    }
}

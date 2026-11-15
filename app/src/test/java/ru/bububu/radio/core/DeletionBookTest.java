package ru.bububu.radio.core;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.CRC32;
import ru.bububu.radio.sources.*;

/** Real Book and rollback files; these cases do not substitute a prefiltered radio catalog. */
public final class DeletionBookTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    static class Store implements NoteBook.MigrationStorage {
        byte[] bytes,unexpectedAuthority;boolean failBefore,failAfter,unknown,unreadable;int saves;
        final MigrationCapsuleStore capsules;final File directory;
        Store(File directory){this.directory=directory;capsules=new MigrationCapsuleStore(directory);}
        public byte[] load(){if(unreadable)throw new IllegalStateException("Unknown authority");return bytes==null?null:bytes.clone();}
        public void save(byte[] value){saves++;if(failBefore)throw new IllegalStateException("Before save");if(unexpectedAuthority!=null){bytes=unexpectedAuthority.clone();unexpectedAuthority=null;throw new IllegalStateException("Unexpected downgrade");}bytes=value.clone();if(unknown){unreadable=true;throw new IllegalStateException("Unknown outcome");}if(failAfter)throw new IllegalStateException("After save");}
        public MigrationStamp prepareMigration(byte[] baseline,int target){return capsules.prepare(baseline,target,new byte[][]{bytes,null,null});}
        public void verifyMigrationStamp(MigrationStamp stamp){capsules.verify(stamp);}
        public UpgradeRollbackRef prepareSchema3Upgrade(byte[] baseline,int target){return capsules.prepareSchema3Upgrade(baseline,target,new byte[][]{bytes,null,null});}
        public void verifyUpgradeRollback(UpgradeRollbackRef ref){capsules.verifyUpgradeRollback(ref);}
        public void verifyUpgradeRollback(UpgradeRollbackRef ref,MigrationStamp origin){capsules.verifyUpgradeRollback(ref,origin);}
    }
    private static PlayerController.SavedProgress partial(SourceRecord source,SourceVersionRecord v){return new PlayerController.SavedProgress(source.stationId,v.articleId,v.versionKey,1,false,Collections.singletonList(0),true,true);}
    private static PlayerController.SavedState saved(PlayerController.SavedProgress p){return new PlayerController.SavedState(p.stationId,Collections.singletonMap(p.stationId,p.articleId),Collections.singletonMap(PlayerController.progressKey(p.stationId,p.articleId,p.version),p));}
    private static SourceVersionRecord version(SourceRecord s,SourceItem item,String text){return SourceBookTest.make(s,item,text,"full");}
    private static void rejected(Runnable action){try{action.run();fail("Expected refusal");}catch(IllegalArgumentException|IllegalStateException expected){}}
    @Test public void sourceDeletionKeepsOnlyOwnExactLinkedContextAndOriginal()throws Exception{
        Store store=new Store(temporary.newFolder());NoteBook book=new NoteBook(store);
        SourceRecord source=SourceRecord.create("RSS","feed","Источник","https://example.test/feed","rss1",true);
        SourceItem item=SourceItem.create(source.id,"one","provider","https://example.test/one","Новость",0,0,1);book.commitSource(source,Collections.singletonList(item));
        SourceVersionRecord v1=version(source,item,"Первая фраза. Вторая.");book.saveSourceVersion(v1);book.selectSourceVersion(v1,false);
        NoteBook.Note note=book.beginRecordingLinkedSource("Личная",v1.recordKey,2);book.edit(note.id,"Правка","Личный текст.");book.finishRecording(note.id,500,true,false,null);book.setOriginalPosition(note.id,234);book.addToAir(note.id);
        File original=new File(store.directory,note.id+".wav");byte[] wav={1,3,7,9};Files.write(original.toPath(),wav);
        byte[] beforeUnconfirmed=store.load();rejected(()->book.preflightDeleteSource(source.id,null));rejected(()->book.deleteSource(source.id,null));rejected(()->book.preflightDeleteSource(source.id,new PlayerController.SavedState(source.stationId,Collections.emptyMap(),Collections.emptyMap())));assertArrayEquals(beforeUnconfirmed,store.load());assertEquals(2,book.schema());
        PlayerController.SavedProgress old=partial(source,v1);book.removeSourceMaterial(source.stationId,v1.articleId,old);
        SourceVersionRecord v2=version(source,item,"Изменённая фраза. Вторая.");book.saveSourceVersion(v2);book.selectSourceVersion(v2,false);
        byte[] baseline=store.load();MigrationStamp origin=book.migrationStamp();assertTrue(book.preflightDeleteSource(source.id,saved(partial(source,v2)))>0);assertEquals(2,book.schema());assertArrayEquals(baseline,store.load());
        assertTrue(book.deleteSource(source.id,saved(partial(source,v2))));assertEquals(3,book.schema());assertTrue(book.sources().isEmpty());assertEquals(1,book.allSources().size());assertTrue(book.retiredStation(source.stationId));assertEquals(1,book.sourceGeneration(source.id));
        NoteBook restored=new NoteBook(store);assertEquals(origin.capsuleId,restored.migrationStamp().capsuleId);assertTrue(restored.upgradeRollbackRef().stamp.matches(baseline));store.capsules.verify(origin);store.capsules.verifyUpgradeRollback(restored.upgradeRollbackRef());
        assertEquals(v1.spokenText,restored.sourceVersion(v1.recordKey).spokenText);assertEquals(old.sentenceIndex,restored.sourceHistory(v1.recordKey).sentenceIndex);assertNull(restored.sourceVersion(v2.recordKey));assertNull(restored.sourceHistory(v2.recordKey));assertNull(restored.sourceItem(source.id,item.itemKey));
        assertEquals("Личный текст.",restored.find(note.id).text);assertEquals(234,restored.find(note.id).positionMs);assertEquals(v1.recordKey,restored.find(note.id).linkedSourceVersionKey);assertArrayEquals(wav,Files.readAllBytes(original.toPath()));assertEquals(1,restored.materials().size());assertEquals(NoteBook.MY_TEXTS,restored.materials().get(0).stationId);
        PlayerController.SavedState retiredRestore=restored.reconcileState(saved(partial(source,v2)));assertEquals(source.stationId,retiredRestore.currentStationId);assertTrue(retiredRestore.stationArticleIds.get(source.stationId).startsWith(NoteBook.AFTER_PREFIX));
        byte[] authority=store.load();rejected(()->restored.reconcileState(new PlayerController.SavedState("UNKNOWN-STATION",Collections.emptyMap(),Collections.emptyMap())));assertArrayEquals(authority,store.load());assertEquals(source.stationId,restored.reconcileState(saved(partial(source,v2))).currentStationId);
        int saves=store.saves;assertFalse(restored.deleteSource(source.id,null));assertEquals(saves,store.saves);rejected(()->restored.selectSourceVersion(v1,false));
    }
    @Test public void itemDeleteClearsUnlinkedMarksAndRefreshCannotResurrect()throws Exception{
        Store store=new Store(temporary.newFolder());NoteBook book=new NoteBook(store);SourceRecord s=SourceRecord.create("RSS","feed","Лента","https://example.test/feed","rss1",false);SourceItem item=SourceItem.create(s.id,"one","provider","https://example.test/one","Первый",0,0,1);book.commitSource(s,Collections.singletonList(item));SourceVersionRecord v=version(s,item,"Одна. Другая.");book.saveSourceVersion(v);book.setSourceRead(s.id,item.itemKey,true);book.setSourceViewed(s.id,item.itemKey,v.recordKey);book.selectSourceVersion(v,false);
        assertTrue(book.deleteSourceItem(s.id,item.itemKey,saved(partial(s,v))));NoteBook restored=new NoteBook(store);SourceItem tombstone=restored.provenanceSourceItem(s.id,item.itemKey);assertNotNull(tombstone);assertFalse(tombstone.read);assertFalse(tombstone.everListened);assertNull(tombstone.latestVersionKey);assertNull(tombstone.lastViewedVersion);assertNull(restored.sourceVersion(v.recordKey));assertNull(restored.sourceHistory(v.recordKey));assertTrue(restored.isSourceActive(s.id));assertTrue(restored.isItemDeleted(s.id,item.itemKey));assertEquals(NoteBook.Membership.NOT_MEMBER,restored.queueMembership(s.stationId,v.asArticle(),1,v.recordKey));
        restored.mergeSourceItems(s.id,Collections.singletonList(item),Collections.singletonList(v),null,null,10,null);assertTrue(restored.sourceItems(s.id).isEmpty());assertEquals(1,restored.sourceIdentityItems(s.id).size());assertTrue(restored.materials().isEmpty());int saves=store.saves;assertFalse(restored.deleteSourceItem(s.id,item.itemKey,null));assertEquals(saves,store.saves);
    }
    @Test public void schema3RemoveFromAirKeepsOldPartialContractAndWriter()throws Exception{
        Store store=new Store(temporary.newFolder());NoteBook book=new NoteBook(store);SourceRecord s=SourceRecord.create("RSS","feed","Лента","https://example.test/feed","rss1",true);SourceItem first=SourceItem.create(s.id,"one",null,null,"Один",0,0,1),second=SourceItem.create(s.id,"two",null,null,"Два",0,0,1);book.commitSource(s,Arrays.asList(first,second));book.deleteSourceItem(s.id,first.itemKey,null);SourceVersionRecord v=version(s,second,"Одна. Другая.");book.saveSourceVersion(v);NoteBook.Material chosen=book.selectSourceVersion(v,false);assertEquals(NoteBook.Membership.ALLOWED,book.queueMembership(s.stationId,v.asArticle(),chosen.sequence,v.recordKey));PlayerController.SavedProgress p=partial(s,v);book.removeSourceMaterial(s.stationId,v.articleId,p);assertEquals(v.spokenText,book.sourceVersion(v.recordKey).spokenText);assertEquals(p.sentenceIndex,book.selectSourceVersion(v,false).selectionInitialProgress.sentenceIndex);NoteBook.Note note=book.beginRecording("Личный",null,null,null,1);book.edit(note.id,"Личный","Ручная правка.");assertEquals(3,new NoteBook(store).schema());assertNotNull(book.upgradeRollbackRef());
    }
    @Test public void failedUpgradeRetriesCurrentBaselineAndNeverDeletesOrigin()throws Exception{
        Store store=new Store(temporary.newFolder());NoteBook book=new NoteBook(store);NoteBook.Note note=book.beginRecording("H1",null,null,null,1);SourceRecord s=SourceRecord.create("RSS","feed","Лента","https://example.test/feed","rss1",true);book.commitSource(s,Collections.emptyList());String origin=book.migrationStamp().capsuleId;store.failBefore=true;rejected(()->book.deleteSource(s.id,null));assertEquals(2,book.schema());assertTrue(book.isSourceActive(s.id));store.failBefore=false;book.edit(note.id,"H2","Текущая ручная правка.");byte[] current=store.load();book.deleteSource(s.id,null);assertTrue(book.upgradeRollbackRef().stamp.matches(current));assertEquals("Текущая ручная правка.",new NoteBook(store).find(note.id).text);assertTrue(new File(store.directory,"notes-migration/"+origin+".bin").isFile());assertEquals(2,new File(store.directory,"notes-migration").list().length);assertEquals(1000,store.capsules.upgradeAdditionalBytes(current,1000));store.capsules.verifyUpgradeRollback(book.upgradeRollbackRef(),book.migrationStamp());
    }
    @Test public void exactTargetAfterThrowSucceedsAndUnreadableOutcomeBlocksNextWrite()throws Exception{
        Store store=new Store(temporary.newFolder());NoteBook book=new NoteBook(store);SourceRecord s=SourceRecord.create("RSS","feed","Лента","https://example.test/feed","rss1",true);book.commitSource(s,Collections.emptyList());byte[] baseline2=store.load();store.failAfter=true;assertTrue(book.deleteSource(s.id,null));assertEquals(3,book.schema());assertTrue(book.isSourceRetired(s.id));store.failAfter=false;
        SourceRecord next=SourceRecord.create("RSS","feed","Лента новая","https://example.test/feed","rss1",true);book.commitSource(next,Collections.emptyList());assertTrue(book.isSourceActive(next.id));assertNotEquals(s.stationId,next.stationId);store.unknown=true;rejected(()->book.deleteSource(next.id,null));assertTrue(book.storageUncertain());assertEquals(NoteBook.Membership.UNVERIFIED,book.queueMembership("A",new Article("one","v1","Тест","Текст."),1,null));store.unreadable=false;store.unknown=false;rejected(()->book.beginRecording("Нельзя",null,null,null,2));assertTrue(new NoteBook(store).isSourceRetired(next.id));NoteBook sticky=new NoteBook(store);store.unexpectedAuthority=baseline2;rejected(()->sticky.beginRecording("Unexpected rollback",null,null,null,3));assertEquals(3,sticky.schema());assertTrue(sticky.storageUncertain());
    }
    @Test public void populatedRegistryBoundAndStrictFooterArePreserved()throws Exception{
        for(int count:new int[]{1000,1001,10000}){Store store=new Store(temporary.newFolder());NoteBook book=new NoteBook(store);book.beginRecording("Собственная",null,null,null,1);SourceRecord s=SourceRecord.create("RSS","feed","Лента","https://example.test/feed","rss1",true);book.commitSource(s,Collections.emptyList());store.bytes=withStationCount(store.bytes,count);book=new NoteBook(store);assertEquals(count,book.stationRegistry().size());book.deleteSource(s.id,null);assertEquals(count,new NoteBook(store).stationRegistry().size());byte[] valid=store.load(),bad=valid.clone();bad[bad.length-8-4-16-1]=99;checksum(bad);store.bytes=bad;rejected(()->new NoteBook(store));assertArrayEquals(bad,store.load());store.bytes=withStationCountSchema2Origin(book,store,count+1);if(count==10000)rejected(()->new NoteBook(store));}
    }
    private static byte[] withStationCountSchema2Origin(NoteBook book,Store store,int count)throws Exception{
        // Recover the independently valid schema2 image held by capsule2, without restoring it.
        File capsule=new File(store.directory,"notes-migration/"+book.upgradeRollbackRef().stamp.capsuleId+".bin");DataInputStream in=new DataInputStream(new FileInputStream(capsule));in.readInt();in.readInt();in.readUTF();in.readInt();in.readUTF();int length=in.readInt();byte[] baseline=new byte[length];in.readFully(baseline);in.close();return withStationCount(baseline,count);
    }
    private static void skipString(DataInputStream in)throws IOException{int length=in.readInt();if(length>=0)in.skipNBytes(length*2L);}
    private static byte[] withStationCount(byte[] original,int desired)throws Exception{
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(original));in.readInt();assertEquals(2,in.readInt());in.readLong();assertTrue(in.readBoolean());skipString(in);skipString(in);in.readInt();skipString(in);skipString(in);in.readInt();skipString(in);
        int notes=in.readInt();for(int i=0;i<notes;i++){skipString(in);in.readLong();skipString(in);skipString(in);in.readLong();for(int j=0;j<3;j++)skipString(in);in.readLong();in.readLong();in.readBoolean();for(int j=0;j<4;j++)skipString(in);}
        int start=original.length-in.available();int existing=in.readInt();int entries=original.length-in.available();for(int i=0;i<existing;i++){skipString(in);skipString(in);}int end=original.length-in.available();
        ByteArrayOutputStream output=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(output);out.write(original,0,start);out.writeInt(desired);out.write(original,entries,end-entries);for(int i=existing;i<desired;i++){writeString(out,"LEGACY:"+i);writeString(out,"Старая станция "+i);}out.write(original,end,original.length-end-8);out.writeLong(0);out.flush();byte[] bytes=output.toByteArray();checksum(bytes);return bytes;
    }
    private static void writeString(DataOutputStream out,String s)throws IOException{out.writeInt(s.length());for(int i=0;i<s.length();i++)out.writeChar(s.charAt(i));}
    private static void checksum(byte[] bytes){CRC32 crc=new CRC32();crc.update(bytes,0,bytes.length-8);java.nio.ByteBuffer.wrap(bytes).putLong(bytes.length-8,crc.getValue());}
}

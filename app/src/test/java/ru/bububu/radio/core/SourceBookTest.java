package ru.bububu.radio.core;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.bububu.radio.sources.*;

public final class SourceBookTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    static class Store implements NoteBook.MigrationStorage {
        byte[] bytes;boolean failBefore,failAfter,unreadable,brokenCapsule;int saves;
        final File directory;final MigrationCapsuleStore capsules;
        Store(File directory){this.directory=directory;capsules=new MigrationCapsuleStore(directory);}
        public byte[] load(){if(unreadable)throw new IllegalStateException("Read denied");return bytes==null?null:bytes.clone();}
        public void save(byte[] value){saves++;if(failBefore)throw new IllegalStateException("IO before commit");bytes=value.clone();if(failAfter)throw new IllegalStateException("IO after commit");}
        public MigrationStamp prepareMigration(byte[] baseline,int target){return capsules.prepare(baseline,target,new byte[][]{bytes,null,null});}
        public void verifyMigrationStamp(MigrationStamp stamp){if(brokenCapsule)throw new IllegalStateException("Capsule unavailable");capsules.verify(stamp);}
    }
    @Test public void initialSourceAndVersionMetadataPublishTogether()throws Exception {
        Store store=new Store(temporary.newFolder());NoteBook book=new NoteBook(store);SourceRecord s=SourceRecord.create("RSS","feed","Лента","https://example.test/feed","rss1",true);SourceItem item=SourceItem.create(s.id,"one",null,null,"Первый",0,0,1);SourceVersionRecord v=make(s,item,"Первый текст.","full");book.commitSource(s,Collections.singletonList(item.withLatest(v.recordKey)),Collections.singletonList(v));NoteBook restored=new NoteBook(store);assertEquals(v.recordKey,restored.sourceItem(s.id,item.itemKey).latestVersionKey);assertNull(restored.sourceVersion(v.recordKey).spokenText);assertTrue(restored.materials().isEmpty());
    }
    @Test public void redirectCommitPreservesMarksAndDedupAliasesAfterRestart()throws Exception {
        Fixture f=new Fixture();f.book.setSourceRead(f.source.id,f.item.itemKey,true);SourceRecord updated=new SourceRecord(f.source.id,f.source.stationId,f.source.type,"https://example.test/final",f.source.name,"https://example.test/final","rss1","rss2",Collections.singletonList("https://example.test/final"),true,true,null,null,0,null);
        f.book.mergeSourceItems(updated,Collections.emptyList(),Collections.emptyList(),"new-etag",null,100,null);NoteBook restored=new NoteBook(f.store);SourceRecord connection=restored.source(f.source.id);assertEquals(updated.url,connection.url);assertTrue(connection.aliases.contains(f.source.key));assertTrue(restored.sourceItem(f.source.id,f.item.itemKey).read);SourceRecord duplicate=SourceRecord.create("RSS",f.source.key,"Другой label",f.source.url,"rss1",true);assertEquals(f.source.id,restored.commitSource(duplicate,Collections.emptyList()).id);assertEquals(1,restored.sources().size());
    }
    @Test public void conflictingRedirectCannotPublishConnectionOrFeedSubset()throws Exception {
        Fixture f=new Fixture();SourceRecord other=SourceRecord.create("RSS","elsewhere","Другой","https://elsewhere.test","rss1",true);f.book.commitSource(other,Collections.emptyList());SourceRecord conflict=new SourceRecord(f.source.id,f.source.stationId,f.source.type,other.key,f.source.name,other.url,"rss1","rss1",Collections.singletonList(other.key),true,true,null,null,0,null);byte[] before=f.store.load();SourceItem extra=SourceItem.create(f.source.id,"extra",null,null,"Extra",0,0,1);rejects(()->f.book.mergeSourceItems(conflict,Collections.singletonList(extra),Collections.emptyList(),null,null,1,null));assertArrayEquals(before,f.store.load());assertNull(f.book.sourceItem(f.source.id,"extra"));
    }
    @Test public void unsupportedNoBodyHintsAreDurableWithoutFakeVersion()throws Exception {
        Fixture f=new Fixture();SourceItem media=SourceItem.create(f.source.id,"video",null,"https://example.test/video","Видео",-1,-1,50).withHints("unavailable","unsupported_media","public_preview_text");f.book.mergeSourceItems(f.source.id,Collections.singletonList(media),Collections.emptyList(),null,null,1,null);SourceItem restored=new NoteBook(f.store).sourceItem(f.source.id,"video");assertNull(restored.latestVersionKey);assertEquals("unavailable",restored.statusHint);assertEquals("unsupported_media",restored.reasonHint);assertEquals("public_preview_text",restored.methodHint);assertTrue(f.book.materials().isEmpty());
    }
    @Test public void coherentV1ChangedWhilePreparingCapsuleCanRetryAndRetainsEdit()throws Exception {
        final NoteBook[] active={null};final boolean[] inject={true};Store store=new Store(temporary.newFolder()) {
            @Override public MigrationStamp prepareMigration(byte[] baseline,int target){MigrationStamp stamp=super.prepareMigration(baseline,target);if(inject[0]){inject[0]=false;NoteBook.Note note=active[0].notes().get(0);active[0].edit(note.id,"Внутри preparation","H2");}return stamp;}
        };NoteBook book=new NoteBook(store);active[0]=book;NoteBook.Note n=book.beginRecording("H1",null,null,null,1);SourceRecord s=SourceRecord.create("RSS","feed","Лента","https://example.test","rss1",true);rejects(()->book.commitSource(s,Collections.emptyList()));assertEquals(1,book.schema());assertEquals("H2",book.find(n.id).text);assertTrue(book.sources().isEmpty());assertEquals(0,book.authorityEpoch());assertFalse(book.storageUncertain());byte[] edited=store.load();book.commitSource(s,Collections.emptyList());assertEquals(SourceLimits.hash(edited),book.migrationStamp().baselineHash);assertEquals("H2",new NoteBook(store).find(n.id).text);
    }
    @Test public void unreadableSaveOutcomeBlocksFurtherWritesUntilNewBookLoad()throws Exception {
        final boolean[] inject={false};Store store=new Store(temporary.newFolder()) {
            @Override public void save(byte[] value){if(inject[0]){unreadable=true;throw new IllegalStateException("Unknown atomic outcome");}super.save(value);}
        };NoteBook book=new NoteBook(store);NoteBook.Note note=book.beginRecording("Исходный",null,null,null,1);SourceRecord s=SourceRecord.create("RSS","feed","Лента","https://example.test","rss1",true);byte[] old=store.load();inject[0]=true;rejects(()->book.commitSource(s,Collections.emptyList()));assertTrue(book.storageUncertain());assertEquals(1,book.authorityEpoch());store.unreadable=false;inject[0]=false;assertArrayEquals(old,store.load());rejects(()->book.edit(note.id,"Следующий","Текст"));assertEquals(1,book.authorityEpoch());NoteBook restored=new NoteBook(store);restored.edit(note.id,"Следующий","Текст");assertEquals(1,restored.schema());assertEquals(0,restored.authorityEpoch());assertFalse(restored.storageUncertain());
    }
    @Test public void sourceConnectionAliasCeilingRejectsWithoutSilentLoss()throws Exception {
        Fixture f=new Fixture();List<String> aliases=new ArrayList<>();for(int i=0;i<16;i++)aliases.add("alias"+i);SourceRecord update=new SourceRecord(f.source.id,f.source.stationId,f.source.type,"new",f.source.name,"https://new.test","rss1","rss1",aliases,true,true,null,null,0,null);byte[] before=f.store.load();rejects(()->f.book.updateSource(update));assertArrayEquals(before,f.store.load());
    }
    @Test public void repeatAfterFullCoveragePinsPausedPositionUntilExplicitCompletion()throws Exception {
        Fixture f=new Fixture();MaterialHolder h=f.select("Первая. Вторая.");List<Integer> all=all(h.version.sentenceStarts.size());f.book.captureSourceHistory(saved(progress(f.source,h.version,all.size()-1,true,all,true)));assertTrue(f.book.sourceItem(f.source.id,f.item.itemKey).everListened);
        int before=f.store.saves;PlayerController.SavedProgress rewind=progress(f.source,h.version,0,false,all,true);f.book.captureSourceHistory(saved(rewind));assertEquals("Replay fragments do not rewrite completed history",before,f.store.saves);
        f.book.removeSourceMaterial(f.source.stationId,h.version.articleId,rewind);NoteBook restored=new NoteBook(f.store);assertNotNull(restored.sourceVersion(h.version.recordKey).spokenText);assertEquals(0,restored.hideCompletedSourceItems(f.source.id));NoteBook.Material selected=restored.selectSourceVersion(h.version.recordKey,false);assertEquals(0,selected.selectionInitialProgress.sentenceIndex);assertFalse(selected.selectionInitialProgress.passEnded);assertEquals(all,selected.selectionInitialProgress.completedSentences);
    }
    @Test public void nullOrMiskeyedJsonProgressNeverBecomesFirstUseSeed()throws Exception {
        Fixture f=new Fixture();MaterialHolder h=f.select("Текст.");String key=PlayerController.progressKey(f.source.stationId,h.version.articleId,h.version.versionKey);Map<String,PlayerController.SavedProgress> nullValue=new LinkedHashMap<>();nullValue.put(key,null);rejects(()->f.book.reconcileState(new PlayerController.SavedState(f.source.stationId,Collections.emptyMap(),nullValue)));
        PlayerController.SavedProgress p=h.material.selectionInitialProgress;rejects(()->f.book.reconcileState(new PlayerController.SavedState(f.source.stationId,Collections.emptyMap(),Collections.singletonMap("wrong-key",p))));
    }
    @Test public void structurallyCorruptV2WithValidChecksumStillFailsClosed()throws Exception {
        Fixture f=new Fixture();byte[] damaged=f.store.load(),name=f.source.name.getBytes(java.nio.charset.StandardCharsets.UTF_16BE);int offset=-1;
        for(int i=0;i<=damaged.length-name.length;i++)if(Arrays.equals(name,Arrays.copyOfRange(damaged,i,i+name.length))){offset=i;break;}
        assertTrue(offset>=0);damaged[offset+1]^=1;java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(damaged,0,damaged.length-8);java.nio.ByteBuffer.wrap(damaged).putLong(damaged.length-8,crc.getValue());f.store.bytes=damaged;rejects(()->new NoteBook(f.store));assertArrayEquals(damaged,f.store.bytes);
    }
    @Test public void differentCommittedV2IsAdoptedButRequestCannotClaimSuccess()throws Exception {
        final byte[][] replacement={null};final boolean[] inject={false};Store store=new Store(temporary.newFolder()) {
            @Override public void save(byte[] value){if(inject[0]){inject[0]=false;bytes=replacement[0].clone();throw new IllegalStateException("Different committed transaction");}super.save(value);}
        };NoteBook book=new NoteBook(store);NoteBook.Note n=book.beginRecording("До",null,null,null,1);Store elsewhere=new Store(temporary.newFolder());elsewhere.bytes=store.load();NoteBook foreign=new NoteBook(elsewhere);SourceRecord actual=SourceRecord.create("RSS","actual","Фактический","https://example.test/actual","rss1",true);foreign.commitSource(actual,Collections.emptyList());replacement[0]=elsewhere.load();
        SourceRecord requested=SourceRecord.create("RSS","requested","Запрошенный","https://example.test/requested","rss1",true);inject[0]=true;rejects(()->book.commitSource(requested,Collections.emptyList()));assertEquals(2,book.schema());assertEquals(1,book.authorityEpoch());assertFalse(book.storageUncertain());assertEquals(actual.id,book.sources().get(0).id);assertNull(book.source(requested.id));assertNotNull(book.migrationWarning());book.edit(n.id,"После","Не стереть источник.");assertEquals(1,book.authorityEpoch());NoteBook restored=new NoteBook(store);assertEquals(2,restored.schema());assertEquals(actual.id,restored.sources().get(0).id);assertEquals("Не стереть источник.",restored.find(n.id).text);
    }
    @Test public void hundredLinkedVersionsRejectAnotherProtectedPayloadAtomically()throws Exception {
        Fixture f=new Fixture();List<SourceItem> items=new ArrayList<>();for(int i=0;i<101;i++)items.add(SourceItem.create(f.source.id,"pin"+i,null,null,"Первый",0,0,0));f.book.mergeSourceItems(f.source.id,items,Collections.emptyList(),null,null,1,null);
        for(int i=0;i<100;i++){SourceVersionRecord v=make(f.source,items.get(i),"Текст.","full");f.book.saveSourceVersion(v);f.book.selectSourceVersion(v,false);f.book.beginRecording("Связь "+i,f.source.stationId,v.articleId,v.versionKey,i);f.book.removeSourceMaterial(f.source.stationId,v.articleId,null);}
        SourceVersionRecord extra=make(f.source,items.get(100),"Текст.","full");f.book.saveSourceVersion(extra);byte[] before=f.store.load();rejects(()->f.book.selectSourceVersion(extra,false));assertArrayEquals(before,f.store.load());assertNull(f.book.sourceVersion(extra.recordKey).spokenText);assertEquals(100,f.book.notes().size());assertTrue(f.book.materials().isEmpty());NoteBook restored=new NoteBook(f.store);assertEquals(100,restored.notes().size());assertNotNull(restored.sourceVersion(restored.notes().get(0).linkedSourceVersionKey).spokenText);
    }
    @Test public void normalNotesSourceCommitsAndConfirmedTargetKeepAuthorityEpoch()throws Exception {
        Store legacy=new Store(temporary.newFolder());NoteBook ordinary=new NoteBook(legacy);NoteBook.Note initial=ordinary.beginRecording("Обычный",null,null,null,1);ordinary.edit(initial.id,"Правка","Текст.");assertEquals(0,ordinary.authorityEpoch());assertFalse(ordinary.storageUncertain());
        Fixture f=new Fixture();MaterialHolder chosen=f.select("Текст.");NoteBook.Note note=f.book.beginRecording("После источника",null,null,null,2);f.book.edit(note.id,"Правка v2","Текст v2.");f.book.setSourceRead(f.source.id,f.item.itemKey,true);assertEquals(0,f.book.authorityEpoch());f.store.failAfter=true;f.book.setSourceRead(f.source.id,f.item.itemKey,false);assertEquals(0,f.book.authorityEpoch());assertFalse(f.book.storageUncertain());assertEquals(chosen.material.articleId,f.book.materials().get(0).articleId);
    }
    @Test public void unexpectedCoherentV1AdoptionAdvancesEpochWithoutBlockingNotes()throws Exception {
        Store store=new Store(temporary.newFolder());NoteBook book=new NoteBook(store);NoteBook.Note note=book.beginRecording("H1",null,null,null,1);Store external=new Store(temporary.newFolder());external.bytes=store.load();NoteBook other=new NoteBook(external);other.edit(note.id,"H2 внешний","Сохранённая правка.");store.bytes=external.load();SourceRecord source=SourceRecord.create("RSS","feed","Лента","https://example.test","rss1",true);
        rejects(()->book.commitSource(source,Collections.emptyList()));assertEquals(1,book.authorityEpoch());assertFalse(book.storageUncertain());assertEquals(1,book.schema());assertEquals("H2 внешний",book.find(note.id).title);book.edit(note.id,"H3 обычный","Ещё правка.");assertEquals(1,book.authorityEpoch());assertEquals("Ещё правка.",new NoteBook(store).find(note.id).text);
    }
    @Test public void unreadableMigrationBaselineAdvancesEpochBeforeAnyWrite()throws Exception {
        Store store=new Store(temporary.newFolder());NoteBook book=new NoteBook(store);NoteBook.Note note=book.beginRecording("Исходный",null,null,null,1);byte[] before=store.load();int saves=store.saves;SourceRecord source=SourceRecord.create("RSS","feed","Лента","https://example.test","rss1",true);store.unreadable=true;rejects(()->book.commitSource(source,Collections.emptyList()));assertEquals(1,book.authorityEpoch());assertTrue(book.storageUncertain());assertEquals(saves,store.saves);assertArrayEquals(before,store.bytes);store.unreadable=false;rejects(()->book.edit(note.id,"Нельзя","Изменение"));assertEquals(1,book.authorityEpoch());assertEquals("Исходный",new NoteBook(store).find(note.id).title);
    }
    final class Fixture {
        final Store store=new Store(temporary.newFolder());final NoteBook book=new NoteBook(store);
        final SourceRecord source=SourceRecord.create("RSS","https://example.test/feed","Новости","https://example.test/feed","rss1",true);
        final SourceItem item=SourceItem.create(source.id,"first", "opaque-ID", "https://example.test/1","Первый",-1,20,10,"invalid-date","raw-update");
        Fixture()throws Exception{book.commitSource(source,Collections.singletonList(item));}
        SourceVersionRecord version(String body){return make(source,item,body,"full");}
        SourceVersionRecord install(String body){SourceVersionRecord v=version(body);book.saveSourceVersion(v);return v;}
        MaterialHolder select(String body){SourceVersionRecord v=install(body);return new MaterialHolder(v,book.selectSourceVersion(v,false));}
    }
    static final class MaterialHolder {final SourceVersionRecord version;final NoteBook.Material material;MaterialHolder(SourceVersionRecord v,NoteBook.Material m){version=v;material=m;}}
    static SourceVersionRecord make(SourceRecord source,SourceItem item,String body,String quality){
        String title="Первый", reason="checked",spoken=source.name+"\n\n"+title+"\n\n"+body;
        int start=spoken.length()-body.length();String hash=SourceLimits.sha256(body);
        String version=SourceLimits.snapshotVersion(hash,title,source.name,quality,reason,"rss1","semantic-v1","source-frame-v1");
        return new SourceVersionRecord(source.id,item.itemKey,item.articleId,version,title,title,source.name,item.url,quality,reason,"inline","rss1","semantic-v1","source-frame-v1",hash,spoken,start,spoken.length(),100);
    }
    static PlayerController.SavedProgress progress(SourceRecord source,SourceVersionRecord v,int index,boolean ended,List<Integer> coverage,boolean started){return new PlayerController.SavedProgress(source.stationId,v.articleId,v.versionKey,index,ended,coverage,false,started);}
    static PlayerController.SavedState saved(PlayerController.SavedProgress p){return new PlayerController.SavedState("A",Collections.singletonMap("A","old-A"),Collections.singletonMap(PlayerController.progressKey(p.stationId,p.articleId,p.version),p));}
    static void rejects(Runnable action){try{action.run();fail("Expected rejection");}catch(IllegalArgumentException|IllegalStateException expected){}}

    @Test public void populatedV1UpgradesWithNotesAudioLinksAndOldLedger()throws Exception {
        Store store=new Store(temporary.newFolder());NoteBook book=new NoteBook(store);
        NoteBook.Note note=book.beginRecording("Личный","A","a","v1",22);book.edit(note.id,"Правка","Не менять текст.");book.finishRecording(note.id,300,true,false,null);book.setOriginalPosition(note.id,120);NoteBook.Material old=book.addToAir(note.id);byte[] original=store.load();
        SourceRecord source=SourceRecord.create("RSS","feed","Пустой","https://example.test/feed","rss1",true);book.commitSource(source,Collections.emptyList());
        NoteBook restored=new NoteBook(store);assertEquals(2,restored.schema());assertEquals("Правка",restored.find(note.id).title);assertEquals(1,restored.find(note.id).revision);assertTrue(restored.find(note.id).hasAudio);assertEquals(120,restored.find(note.id).positionMs);assertEquals("A",restored.find(note.id).linkedStation);
        assertEquals(old.articleId,restored.materials().get(0).articleId);assertEquals(Arrays.asList(NoteBook.MY_TEXTS,source.stationId),stationIds(restored));assertEquals(SourceLimits.hash(original),restored.migrationStamp().baselineHash);
        restored.edit(note.id,"После","Новый текст.");assertEquals(2,new NoteBook(store).schema());assertEquals(1,new NoteBook(store).sources().size());
    }
    @Test public void failedMigrationThenV1EditBindsRetryToNewBaseline()throws Exception {
        Store store=new Store(temporary.newFolder());NoteBook book=new NoteBook(store);NoteBook.Note note=book.beginRecording("Первый",null,null,null,1);byte[] h1=store.load();
        SourceRecord s=SourceRecord.create("RSS","feed","Лента","https://example.test/feed","rss1",true);store.failBefore=true;rejects(()->book.commitSource(s,Collections.emptyList()));assertEquals(1,book.schema());assertTrue(book.sources().isEmpty());
        store.failBefore=false;book.edit(note.id,"Редакция","Текст H2.");byte[] h2=store.load();assertFalse(Arrays.equals(h1,h2));book.commitSource(s,Collections.emptyList());assertEquals(SourceLimits.hash(h2),book.migrationStamp().baselineHash);assertEquals("Текст H2.",new NoteBook(store).find(note.id).text);
    }
    @Test public void failedAbsentMigrationThenNewNoteUsesPresentCapsule()throws Exception {
        Store store=new Store(temporary.newFolder());NoteBook book=new NoteBook(store);SourceRecord s=SourceRecord.create("RSS","feed","Лента","https://example.test/feed","rss1",true);
        store.failBefore=true;rejects(()->book.commitSource(s,Collections.emptyList()));store.failBefore=false;NoteBook.Note n=book.beginRecording("Между попытками",null,null,null,5);byte[] baseline=store.load();book.commitSource(s,Collections.emptyList());assertNull(book.migrationStamp().absenceEpoch);assertEquals(SourceLimits.hash(baseline),book.migrationStamp().baselineHash);assertNotNull(new NoteBook(store).find(n.id));
    }
    @Test public void saveExceptionAfterAtomicCommitPublishesSameInstanceWriter2()throws Exception {
        Store store=new Store(temporary.newFolder());NoteBook book=new NoteBook(store);SourceRecord s=SourceRecord.create("RSS","feed","Лента","https://example.test/feed","rss1",true);store.failAfter=true;book.commitSource(s,Collections.emptyList());assertEquals(2,book.schema());assertEquals(s.id,book.sources().get(0).id);store.failAfter=false;book.beginRecording("После",null,null,null,1);assertEquals(2,new NoteBook(store).schema());assertEquals(1,new NoteBook(store).sources().size());
    }
    @Test public void committedMissingCapsuleKeepsSchema2Authority()throws Exception {
        Fixture f=new Fixture();f.store.brokenCapsule=true;NoteBook restored=new NoteBook(f.store);assertEquals(2,restored.schema());assertNotNull(restored.migrationWarning());assertEquals(f.source.id,restored.sources().get(0).id);restored.beginRecording("Далее",null,null,null,2);assertEquals(2,new NoteBook(f.store).schema());
    }
    @Test public void metadataOnlyExtractionPromotesBodyAndSeedAtomically()throws Exception {
        Fixture f=new Fixture();SourceVersionRecord v=f.install("Первая фраза. Вторая.");assertNull(f.book.sourceVersion(v.recordKey).spokenText);int before=f.store.saves;
        NoteBook.Material material=f.book.selectSourceVersion(v,false);assertEquals(before+1,f.store.saves);assertEquals(v.recordKey,material.sourceVersionKey);assertEquals(v.spokenText,material.text);assertFalse(material.selectionInitialProgress.started);
        NoteBook restored=new NoteBook(f.store);assertEquals(v.spokenText,restored.sourceVersion(v.recordKey).spokenText);assertEquals(v.spokenText,restored.materials().get(0).asArticle().text);assertEquals(v.recordKey,restored.sourceItem(f.source.id,f.item.itemKey).latestVersionKey);
    }
    @Test public void failedPromotionLeavesNoPayloadQueueOrSeed()throws Exception {
        Fixture f=new Fixture();SourceVersionRecord v=f.install("Проверенный текст.");byte[] before=f.store.load();f.store.failBefore=true;rejects(()->f.book.selectSourceVersion(v,false));assertTrue(f.book.materials().isEmpty());assertNull(f.book.sourceVersion(v.recordKey).spokenText);assertArrayEquals(before,f.store.load());
    }
    @Test public void schema2SaveExceptionAfterCommitPublishesExactSelection()throws Exception {
        Fixture f=new Fixture();SourceVersionRecord v=f.install("Текст.");f.store.failAfter=true;NoteBook.Material chosen=f.book.selectSourceVersion(v,false);assertEquals(chosen.articleId,f.book.materials().get(0).articleId);assertEquals(1,new NoteBook(f.store).materials().size());
    }
    @Test public void dedupIsIdempotentButNewVersionRequiresExplicitRemoval()throws Exception {
        Fixture f=new Fixture();MaterialHolder old=f.select("Версия один.");int saves=f.store.saves;assertSame(old.material,f.book.selectSourceVersion(old.version,false));assertEquals(saves,f.store.saves);
        SourceVersionRecord fresh=f.install("Версия два.");rejects(()->f.book.selectSourceVersion(fresh,false));assertEquals(old.version.spokenText,f.book.materials().get(0).text);f.book.removeSourceMaterial(f.source.stationId,old.version.articleId,null);NoteBook.Material second=f.book.selectSourceVersion(fresh,false);assertTrue(second.sequence>old.material.sequence);assertEquals(fresh.spokenText,second.text);
    }
    @Test public void qualityGateRequiresConsentAndRejectsHeadlines()throws Exception {
        Fixture f=new Fixture();SourceVersionRecord summary=make(f.source,f.item,"Краткое тело.","summary");f.book.saveSourceVersion(summary);rejects(()->f.book.selectSourceVersion(summary,false));assertNotNull(f.book.selectSourceVersion(summary,true));f.book.removeSourceMaterial(f.source.stationId,summary.articleId,null);
        SourceVersionRecord headline=make(f.source,f.item,"Один заголовок.","headline_only");f.book.saveSourceVersion(headline);rejects(()->f.book.selectSourceVersion(headline,true));
    }
    @Test public void manualNoteEditCannotChangeChosenSourceOrLinkSnapshot()throws Exception {
        Fixture f=new Fixture();MaterialHolder h=f.select("Зафиксированное тело.");NoteBook.Note note=f.book.beginRecording("Заметка",f.source.stationId,h.version.articleId,h.version.versionKey,7);assertEquals(h.version.recordKey,note.linkedSourceVersionKey);f.book.edit(note.id,"Редактор","Личный текст.");f.book.finishRecording(note.id,400,true,true,"Interrupted");f.book.setOriginalPosition(note.id,200);
        f.book.removeSourceMaterial(f.source.stationId,h.version.articleId,null);f.install("Свежий текст.");NoteBook restored=new NoteBook(f.store);assertEquals("Зафиксированное тело.",restored.sourceVersion(h.version.recordKey).body());assertEquals("Личный текст.",restored.find(note.id).text);assertEquals(200,restored.find(note.id).positionMs);assertTrue(restored.find(note.id).hasAudio);
    }
    @Test public void sameVersionReaddKeepsPartialPositionCoverageAndRead()throws Exception {
        Fixture f=new Fixture();MaterialHolder h=f.select("Одна. Другая.");int last=h.version.sentenceStarts.size()-1;PlayerController.SavedProgress p=progress(f.source,h.version,last,false,Collections.singletonList(0),true);f.book.setSourceRead(f.source.id,f.item.itemKey,true);
        f.book.removeSourceMaterial(f.source.stationId,h.version.articleId,p);NoteBook restored=new NoteBook(f.store);assertNotNull(restored.sourceVersion(h.version.recordKey).spokenText);assertFalse(restored.sourceItem(f.source.id,f.item.itemKey).everListened);
        NoteBook.Material again=restored.selectSourceVersion(h.version.recordKey,false);assertEquals(last,again.selectionInitialProgress.sentenceIndex);assertEquals(Collections.singletonList(0),again.selectionInitialProgress.completedSentences);assertTrue(again.selectionInitialProgress.read);
    }
    @Test public void newVersionResetsCoverageButKeepsStickyReadAndListened()throws Exception {
        Fixture f=new Fixture();MaterialHolder h=f.select("Одна. Другая.");List<Integer> all=all(h.version.sentenceStarts.size());PlayerController.SavedProgress done=progress(f.source,h.version,all.size()-1,true,all,true);f.book.setSourceRead(f.source.id,f.item.itemKey,true);f.book.removeSourceMaterial(f.source.stationId,h.version.articleId,done);SourceVersionRecord fresh=f.install("Новое тело.");NoteBook.Material selected=f.book.selectSourceVersion(fresh,false);
        assertEquals(0,selected.selectionInitialProgress.sentenceIndex);assertTrue(selected.selectionInitialProgress.completedSentences.isEmpty());assertFalse(selected.selectionInitialProgress.started);assertTrue(selected.selectionInitialProgress.read);assertTrue(f.book.sourceItem(f.source.id,f.item.itemKey).everListened);assertNull(f.book.sourceVersion(h.version.recordKey).spokenText);
    }
    @Test public void skipEndedWithIncompleteCoverageDoesNotMarkListened()throws Exception {
        Fixture f=new Fixture();MaterialHolder h=f.select("Одна. Другая.");PlayerController.SavedProgress p=progress(f.source,h.version,h.version.sentenceStarts.size()-1,true,Collections.singletonList(0),true);f.book.captureSourceHistory(saved(p));assertFalse(f.book.sourceItem(f.source.id,f.item.itemKey).everListened);f.book.removeSourceMaterial(f.source.stationId,h.version.articleId,p);assertNotNull(f.book.sourceVersion(h.version.recordKey).spokenText);
    }
    @Test public void historyBridgeFailureCanRetryWithoutFalseListened()throws Exception {
        Fixture f=new Fixture();MaterialHolder h=f.select("Одна. Другая.");List<Integer> all=all(h.version.sentenceStarts.size());PlayerController.SavedProgress done=progress(f.source,h.version,all.size()-1,true,all,true);f.store.failBefore=true;rejects(()->f.book.captureSourceHistory(saved(done)));assertFalse(f.book.sourceItem(f.source.id,f.item.itemKey).everListened);f.store.failBefore=false;f.book.captureSourceHistory(saved(done));assertTrue(new NoteBook(f.store).sourceItem(f.source.id,f.item.itemKey).everListened);
    }
    @Test public void malformedRemovalProgressCannotDropQueueOrAudioPin()throws Exception {
        Fixture f=new Fixture();MaterialHolder h=f.select("Одна. Другая.");byte[] before=f.store.load();PlayerController.SavedProgress bad=progress(f.source,h.version,0,false,Arrays.asList(0,0),true);rejects(()->f.book.removeSourceMaterial(f.source.stationId,h.version.articleId,bad));assertArrayEquals(before,f.store.load());assertEquals(1,f.book.materials().size());
    }
    @Test public void removalCrashBeforeRadioSaveUsesTombstoneAndArchivedSeed()throws Exception {
        Fixture f=new Fixture();MaterialHolder h=f.select("Одна. Другая.");PlayerController.SavedProgress partial=progress(f.source,h.version,1,false,Collections.singletonList(0),true);PlayerController.SavedState stale=new PlayerController.SavedState(f.source.stationId,Collections.singletonMap(f.source.stationId,h.version.articleId),Collections.singletonMap(PlayerController.progressKey(partial.stationId,partial.articleId,partial.version),partial));
        f.book.removeSourceMaterial(f.source.stationId,h.version.articleId,partial);NoteBook restored=new NoteBook(f.store);PlayerController.SavedState reconciled=restored.reconcileState(stale);assertTrue(reconciled.stationArticleIds.get(f.source.stationId).startsWith(NoteBook.AFTER_PREFIX));assertEquals(1,restored.sourceHistory(h.version.recordKey).sentenceIndex);assertEquals(1,restored.selectSourceVersion(h.version.recordKey,false).selectionInitialProgress.sentenceIndex);
    }
    @Test public void appendCrashBeforeJsonSaveSeedsMissingProgressPreservesFixtures()throws Exception {
        Fixture f=new Fixture();MaterialHolder h=f.select("Одна. Другая.");PlayerController.SavedProgress legacy=new PlayerController.SavedProgress("A","old-A","v1",4,false,Arrays.asList(0,1),true,true);PlayerController.SavedState stale=saved(legacy);NoteBook restored=new NoteBook(f.store);PlayerController.SavedState merged=restored.reconcileState(stale);
        assertEquals("A",merged.currentStationId);assertEquals("old-A",merged.stationArticleIds.get("A"));assertSame(legacy,merged.progress.get(PlayerController.progressKey("A","old-A","v1")));assertEquals(0,merged.progress.get(PlayerController.progressKey(f.source.stationId,h.version.articleId,h.version.versionKey)).sentenceIndex);
    }
    @Test public void provenMissingJsonCanSeedSourcesAndStickyReadOverridesLive()throws Exception {
        Fixture f=new Fixture();MaterialHolder h=f.select("Первая. Вторая.");f.book.setSourceRead(f.source.id,f.item.itemKey,true);PlayerController.SavedState first=f.book.reconcileState(null);assertNotNull(first);PlayerController.SavedProgress p=first.progress.get(PlayerController.progressKey(f.source.stationId,h.version.articleId,h.version.versionKey));assertTrue(p.read);
        PlayerController.SavedState merged=f.book.reconcileState(saved(progress(f.source,h.version,1,false,Collections.emptyList(),false)));assertEquals(1,merged.progress.get(PlayerController.progressKey(p.stationId,p.articleId,p.version)).sentenceIndex);assertTrue(merged.progress.get(PlayerController.progressKey(p.stationId,p.articleId,p.version)).read);
    }
    @Test public void refreshPreservesMarksFirstSeenLatestAndRawBadDates()throws Exception {
        Fixture f=new Fixture();SourceVersionRecord v=f.install("Текст.");f.book.setSourceRead(f.source.id,f.item.itemKey,true);f.book.setSourceViewed(f.source.id,f.item.itemKey,v.recordKey);SourceItem refresh=SourceItem.create(f.source.id,f.item.itemKey,"opaque-ID","https://example.test/changed","Исправление",-1,-1,999,"still-bad","broken-update");f.book.mergeSourceItems(f.source.id,Collections.singletonList(refresh),Collections.emptyList(),"etag",null,20,null);
        SourceItem item=new NoteBook(f.store).sourceItem(f.source.id,f.item.itemKey);assertEquals(10,item.firstSeenAt);assertEquals("still-bad",item.publishedRaw);assertEquals(-1,item.publishedAt);assertTrue(item.read);assertEquals(v.recordKey,item.latestVersionKey);assertEquals(v.recordKey,item.lastViewedVersion);assertEquals("Исправление",item.title);
    }
    @Test public void disableKeepsEmptyRegistryCacheAndQueueButRejectsRefresh()throws Exception {
        Fixture f=new Fixture();MaterialHolder h=f.select("Сохранённый текст.");f.book.setSourceEnabled(f.source.id,false);rejects(()->f.book.mergeSourceItems(f.source.id,Collections.emptyList(),Collections.emptyList(),null,null,99,null));f.book.removeSourceMaterial(f.source.stationId,h.version.articleId,null);assertEquals(f.source.stationId,f.book.stationRegistry().get(0).id);assertNotNull(f.book.selectSourceVersion(h.version,false));assertFalse(new NoteBook(f.store).source(f.source.id).enabled);
    }
    @Test public void cleanupHidesOnlyUnprotectedMarkedItemsAndNeverTouchesNotes()throws Exception {
        Fixture f=new Fixture();MaterialHolder h=f.select("Выбранный текст.");f.book.setSourceRead(f.source.id,f.item.itemKey,true);assertEquals(0,f.book.hideCompletedSourceItems(f.source.id));f.book.removeSourceMaterial(f.source.stationId,h.version.articleId,null);
        NoteBook.Note note=f.book.beginRecording("Оригинал",null,null,null,2);f.book.finishRecording(note.id,100,true,false,null);assertEquals(1,f.book.hideCompletedSourceItems(f.source.id));assertNull(f.book.sourceItem(f.source.id,f.item.itemKey));f.book.mergeSourceItems(f.source.id,Collections.singletonList(f.item),Collections.singletonList(h.version),null,null,200,null);assertTrue(f.book.sourceItems(f.source.id).isEmpty());assertTrue(new NoteBook(f.store).find(note.id).hasAudio);
    }
    @Test public void linkedAndPartialItemsCannotBeCleaned()throws Exception {
        Fixture f=new Fixture();MaterialHolder h=f.select("Одна. Другая.");f.book.beginRecordingLinkedSource("Связь",h.version.recordKey,1);f.book.setSourceRead(f.source.id,f.item.itemKey,true);f.book.removeSourceMaterial(f.source.stationId,h.version.articleId,progress(f.source,h.version,1,false,Collections.singletonList(0),true));assertEquals(0,f.book.hideCompletedSourceItems(f.source.id));assertNotNull(new NoteBook(f.store).sourceVersion(h.version.recordKey).spokenText);
    }
    @Test public void sourceHashCollisionAcrossItemsHasDistinctReferenceIdentity()throws Exception {
        Fixture f=new Fixture();SourceItem second=SourceItem.create(f.source.id,"second",null,f.item.url,f.item.title,-1,20,10);f.book.mergeSourceItems(f.source.id,Collections.singletonList(second),Collections.emptyList(),null,null,20,null);SourceVersionRecord one=f.install("Совпадающее тело."),two=make(f.source,second,"Совпадающее тело.","full");assertEquals(one.versionKey,two.versionKey);assertNotEquals(one.recordKey,two.recordKey);f.book.saveSourceVersion(two);rejects(()->f.book.sourceVersion(one.versionKey));assertNotNull(f.book.selectSourceVersion(one,false));assertNotNull(f.book.selectSourceVersion(two,false));assertEquals(2,new NoteBook(f.store).materials().size());
    }
    @Test public void snapshotsAreImmutableAndDoNotFollowRefresh()throws Exception {
        Fixture f=new Fixture();SourceVersionRecord v=f.install("Тело.");List<SourceItem> old=f.book.sourceItems(f.source.id);List<SourceVersionRecord> versions=f.book.sourceVersions(f.source.id,f.item.itemKey);try{old.clear();fail();}catch(UnsupportedOperationException expected){}try{versions.get(0).sentenceStarts.add(999);fail();}catch(UnsupportedOperationException expected){}f.install("Новое тело.");assertEquals(v.recordKey,old.get(0).latestVersionKey);assertEquals(1,versions.size());assertEquals(v.recordKey,versions.get(0).recordKey);
    }
    @Test public void sourceAndMyTextsStationOrderFollowsFirstAppearance()throws Exception {
        Fixture f=new Fixture();NoteBook.Note note=f.book.beginRecording("Личный",null,null,null,1);f.book.edit(note.id,"Личный","Текст.");f.book.addToAir(note.id);SourceRecord second=SourceRecord.create("ATOM","feed2","Второй","https://example.test/2","atom1",true);f.book.commitSource(second,Collections.emptyList());assertEquals(Arrays.asList(f.source.stationId,NoteBook.MY_TEXTS,second.stationId),stationIds(new NoteBook(f.store)));
    }
    @Test public void countAndTextLimitsRejectWithoutTruncationOrPartialCommit()throws Exception {
        Fixture f=new Fixture();byte[] before=f.store.load();List<SourceItem> items=new ArrayList<>();for(int i=0;i<201;i++)items.add(SourceItem.create(f.source.id,"extra"+i,null,null,"Текст",0,0,0));rejects(()->f.book.mergeSourceItems(f.source.id,items,Collections.emptyList(),null,null,1,null));assertArrayEquals(before,f.store.load());String tooLong=new String(new char[20000]).replace('\0','я');rejects(()->make(f.source,f.item,tooLong,"full"));assertArrayEquals(before,f.store.load());
    }
    @Test public void corruptSchema2CannotWipeOrLoadAnEmptyBook()throws Exception {
        Fixture f=new Fixture();f.select("Личный выбранный текст.");byte[] damaged=f.store.load();damaged[damaged.length/2]^=4;f.store.bytes=damaged;rejects(()->new NoteBook(f.store));assertArrayEquals(damaged,f.store.bytes);
    }
    @Test public void immutableFramingRejectsSubstitutedPrefixBeforePromotion()throws Exception {
        Fixture f=new Fixture();SourceVersionRecord v=f.install("Тело.");String changed="X"+v.spokenText.substring(1);rejects(()->v.withPayload(changed));assertNull(f.book.sourceVersion(v.recordKey).spokenText);assertTrue(f.book.materials().isEmpty());
    }
    @Test public void admissionReservesFullHistoryAndNativeEditsRespectThatReserve()throws Exception {
        Fixture f=new Fixture();String noteText=new String(new char[20000]).replace('\0','н');
        for(int i=0;i<80;i++){NoteBook.Note n=f.book.beginRecording("Личный "+i,null,null,null,i);f.book.edit(n.id,n.title,noteText);}
        StringBuilder body=new StringBuilder();for(int i=0;i<6500;i++)body.append("я. ");String text=body.toString().trim();
        List<SourceItem> incoming=new ArrayList<>();for(int i=0;i<100;i++)incoming.add(SourceItem.create(f.source.id,"budget"+i,null,null,"Заголовок",0,0,0));
        f.book.mergeSourceItems(f.source.id,incoming,Collections.emptyList(),null,null,1,null);
        boolean refused=false;List<SourceVersionRecord> chosen=new ArrayList<>();
        for(SourceItem item:incoming){SourceVersionRecord v=make(f.source,item,text,"full");f.book.saveSourceVersion(v);try{f.book.selectSourceVersion(v,false);chosen.add(v);}catch(IllegalStateException limit){refused=true;break;}}
        assertTrue("Full coverage reserve must reject before 100 full text versions",refused);assertTrue(f.store.bytes.length<NoteBook.MAX_METADATA_BYTES);assertFalse(chosen.isEmpty());
        boolean noteRefused=false;
        for(int i=80;i<90;i++){NoteBook.Note n;try{n=f.book.beginRecording("Ещё "+i,null,null,null,i);}catch(IllegalStateException limit){noteRefused=true;break;}try{f.book.edit(n.id,n.title,noteText);}catch(IllegalStateException limit){assertEquals("",f.book.find(n.id).text);noteRefused=true;break;}}
        assertTrue("Native v2 commits preserve future history space",noteRefused);
        Map<String,PlayerController.SavedProgress> allProgress=new LinkedHashMap<>();for(SourceVersionRecord v:chosen){List<Integer> coverage=all(v.sentenceStarts.size());PlayerController.SavedProgress p=progress(f.source,v,coverage.size()-1,true,coverage,true);allProgress.put(PlayerController.progressKey(p.stationId,p.articleId,p.version),p);}
        f.book.captureSourceHistory(new PlayerController.SavedState(f.source.stationId,Collections.emptyMap(),allProgress));NoteBook restored=new NoteBook(f.store);assertEquals(chosen.size(),restored.materials().size());for(SourceVersionRecord v:chosen)assertTrue(restored.sourceItem(v.sourceId,v.itemKey).everListened);
    }
    static List<Integer> all(int count){List<Integer> all=new ArrayList<>();for(int i=0;i<count;i++)all.add(i);return all;}
    static List<String> stationIds(NoteBook book){List<String> ids=new ArrayList<>();for(Station s:book.stationRegistry())ids.add(s.id);return ids;}
}

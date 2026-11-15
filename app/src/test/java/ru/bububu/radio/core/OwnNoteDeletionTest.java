package ru.bububu.radio.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import ru.bububu.radio.sources.*;
import static org.junit.Assert.*;

/** Actual prepared Note deletion with real rollback capsules and exact durable authority. */
public final class OwnNoteDeletionTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final class Fixture {
        final DeletionBookTest.Store store;final NoteBook book;final SourceRecord source;final SourceItem first,second;final SourceVersionRecord v1,v2;final NoteBook.Note target,other;final NoteBook.Material targetMaterial,otherMaterial;final PlayerController.SavedState persisted;
        Fixture(File directory,boolean selected)throws Exception {
            store=new DeletionBookTest.Store(directory);book=new NoteBook(store);source=SourceRecord.create("RSS","own-delete","Источник","https://example.test/own-delete","rss1",true);first=SourceItem.create(source.id,"one","p-one","https://example.test/one","Первая новость",0,0,1);second=SourceItem.create(source.id,"two","p-two","https://example.test/two","Вторая новость",0,0,1);book.commitSource(source,Arrays.asList(first,second));
            v1=SourceBookTest.make(source,first,"Первый исходный текст. Вторая фраза.","full");v2=SourceBookTest.make(source,second,"Другой исходный текст. Последняя фраза.","full");book.saveSourceVersion(v1);book.saveSourceVersion(v2);book.selectSourceVersion(v1,false);book.selectSourceVersion(v2,false);book.setSourceRead(source.id,first.itemKey,true);book.setSourceViewed(source.id,first.itemKey,v1.recordKey);
            target=note(book,v1.recordKey,"Удаляемая личная",123);other=note(book,v2.recordKey,"Другая личная",456);targetMaterial=selected?book.addToAir(target.id):null;otherMaterial=book.addToAir(other.id);
            Files.write(new File(directory,target.id+".wav").toPath(),new byte[]{1,2,3});Files.write(new File(directory,other.id+".wav").toPath(),new byte[]{4,5,6});
            SourceItem unused=SourceItem.create(source.id,"unused",null,null,"Пустая identity",0,0,1);book.mergeSourceItems(source.id,Collections.singletonList(unused),Collections.emptyList(),null,null,10,null);book.deleteSourceItem(source.id,unused.itemKey,null);
            Map<String,PlayerController.SavedProgress> progress=new LinkedHashMap<>();for(NoteBook.Material material:book.materials()){PlayerController.SavedProgress p=new PlayerController.SavedProgress(material.stationId,material.articleId,material.version,1,false,Collections.singletonList(0),true,true);progress.put(PlayerController.progressKey(p.stationId,p.articleId,p.version),p);}
            Map<String,String> cursors=new LinkedHashMap<>();cursors.put(source.stationId,v1.articleId);cursors.put(NoteBook.MY_TEXTS,selected?targetMaterial.articleId:otherMaterial.articleId);persisted=book.encodeCursorProvenance(new PlayerController.SavedState(NoteBook.MY_TEXTS,cursors,progress));
        }
    }
    private Fixture fixture(boolean selected)throws Exception{return new Fixture(temporary.newFolder(),selected);}
    private static NoteBook.Note note(NoteBook book,String key,String title,long position){NoteBook.Note n=book.beginRecordingLinkedSource(title,key,7);book.finishRecording(n.id,1000,true,false,"Сохранённая запись");book.setOriginalPosition(n.id,position);return book.edit(n.id,title,"Личный ручной текст. Вторая фраза.");}
    private static void rejects(Runnable action){try{action.run();fail("Expected refusal");}catch(IllegalArgumentException|IllegalStateException expected){}}

    @Test public void selectedAndUnselectedDeleteEachCommitOnceReloadAndPreserveOtherOwners()throws Exception {
        for(boolean selected:new boolean[]{false,true}) {
            Fixture f=fixture(selected);SourceRecord connection=f.book.source(f.source.id);SourceItem first=f.book.sourceItem(f.source.id,f.first.itemKey),second=f.book.sourceItem(f.source.id,f.second.itemKey);List<NoteBook.Material> survivors=new ArrayList<>(f.book.materials());if(selected)survivors.remove(f.targetMaterial);byte[] old=f.store.load();int saves=f.store.saves;
            NoteBook.DeletionChange change=f.book.prepareDeleteNote(f.target.id,f.target.revision,f.persisted);assertArrayEquals(old,f.store.load());assertEquals(saves,f.store.saves);assertEquals(selected?1:0,change.removedMaterials.size());assertEquals(selected,change.affectsCurrent);assertTrue(f.book.applyDeletion(change));assertEquals(saves+1,f.store.saves);
            NoteBook restored=new NoteBook(f.store);assertEquals(3,restored.schema());assertNull(restored.find(f.target.id));assertEquals(1,restored.notes().size());exactNote(f.other,restored.find(f.other.id));assertArrayEquals(new byte[]{4,5,6},Files.readAllBytes(new File(f.store.directory,f.other.id+".wav").toPath()));assertEquals(survivors.size(),restored.materials().size());for(NoteBook.Material material:survivors)exactMaterial(material,restored.material(material.stationId,material.articleId));
            assertSame(first,f.book.sourceItem(f.source.id,f.first.itemKey));assertSame(second,f.book.sourceItem(f.source.id,f.second.itemKey));assertEquals(first.latestVersionKey,restored.sourceItem(f.source.id,f.first.itemKey).latestVersionKey);assertEquals(first.lastViewedVersion,restored.sourceItem(f.source.id,f.first.itemKey).lastViewedVersion);assertEquals(first.read,restored.sourceItem(f.source.id,f.first.itemKey).read);assertEquals(f.v1.spokenText,restored.sourceVersion(f.v1.recordKey).spokenText);assertEquals(f.v2.spokenText,restored.sourceVersion(f.v2.recordKey).spokenText);assertEquals(f.source.url,restored.source(f.source.id).url);assertEquals(f.source.enabled,restored.source(f.source.id).enabled);
            exactFields(connection,restored.source(f.source.id));exactFields(first,restored.sourceItem(f.source.id,f.first.itemKey));exactFields(second,restored.sourceItem(f.source.id,f.second.itemKey));
            if(selected){assertEquals(NoteBook.Membership.NOT_MEMBER,restored.queueMembership(f.targetMaterial.stationId,f.targetMaterial.asArticle(),f.targetMaterial.sequence,null));assertEquals(f.otherMaterial.articleId,restored.reconcileState(f.persisted).stationArticleIds.get(NoteBook.MY_TEXTS));}
            assertFalse(f.book.applyDeletion(change));assertEquals(saves+1,f.store.saves);
        }
    }

    @Test public void staleRecordingAndForeignPreparedBaselineRefuseWithoutOverwrite()throws Exception {
        Fixture f=fixture(true);byte[] old=f.store.load();int saves=f.store.saves;rejects(()->f.book.prepareDeleteNote(f.target.id,f.target.revision-1,f.persisted));assertArrayEquals(old,f.store.load());assertEquals(saves,f.store.saves);
        NoteBook.Note recording=f.book.beginRecording("Ещё пишется",null,null,null,20);old=f.store.load();saves=f.store.saves;rejects(()->f.book.prepareDeleteNote(recording.id,recording.revision,f.persisted));assertArrayEquals(old,f.store.load());assertEquals(saves,f.store.saves);
        NoteBook.DeletionChange stale=f.book.prepareDeleteNote(f.target.id,f.target.revision,f.persisted);f.book.edit(f.target.id,"Свежая ручная редакция","Свежий текст.");byte[] newest=f.store.load();saves=f.store.saves;rejects(()->f.book.applyDeletion(stale));assertArrayEquals(newest,f.store.load());assertEquals(saves,f.store.saves);
        NoteBook.DeletionChange prepared=f.book.prepareDeleteNote(f.target.id,f.book.find(f.target.id).revision,f.persisted);NoteBook foreign=new NoteBook(f.store);foreign.setOriginalPosition(f.other.id,789);byte[] actual=f.store.load();saves=f.store.saves;rejects(()->f.book.applyDeletion(prepared));assertArrayEquals(actual,f.store.load());assertEquals(saves,f.store.saves);assertNotNull(f.book.find(f.target.id));assertEquals(789,f.book.find(f.other.id).positionMs);
        rejects(()->foreign.applyDeletion(prepared));assertArrayEquals(actual,f.store.load());
    }

    @Test public void knownOldExactTargetAndUnknownDeletionOutcomesKeepDurableAuthorityHonest()throws Exception {
        for(int outcome=0;outcome<3;outcome++) {
            Fixture f=fixture(true);NoteBook.DeletionChange change=f.book.prepareDeleteNote(f.target.id,f.target.revision,f.persisted);byte[] old=f.store.load();int saves=f.store.saves;f.store.failBefore=outcome==0;f.store.failAfter=outcome==1;f.store.unknown=outcome==2;
            if(outcome==1){assertTrue(f.book.applyDeletion(change));assertNull(f.book.find(f.target.id));assertFalse(f.book.storageUncertain());}
            else {rejects(()->f.book.applyDeletion(change));if(outcome==0){assertArrayEquals(old,f.store.load());assertNotNull(f.book.find(f.target.id));assertFalse(f.book.storageUncertain());}else{assertTrue(f.book.storageUncertain());assertEquals(NoteBook.Membership.UNVERIFIED,f.book.queueMembership(f.targetMaterial.stationId,f.targetMaterial.asArticle(),f.targetMaterial.sequence,null));rejects(()->f.book.prepareDeleteNote(f.other.id,f.other.revision,f.persisted));}}
            assertEquals(saves+1,f.store.saves);f.store.failBefore=false;f.store.failAfter=false;f.store.unknown=false;f.store.unreadable=false;if(outcome==0)assertTrue(f.book.applyDeletion(change));else if(outcome==2)rejects(()->f.book.applyDeletion(change));NoteBook restored=new NoteBook(f.store);assertNull(restored.find(f.target.id));exactNote(f.other,restored.find(f.other.id));assertArrayEquals(new byte[]{4,5,6},Files.readAllBytes(new File(f.store.directory,f.other.id+".wav").toPath()));
        }
    }

    @Test public void removingLastAndSharedNotesPrunesOnlyUnownedRetiredOrDeletedContext()throws Exception {
        for(boolean retire:new boolean[]{true,false}) {
            Fixture f=fixture(false);NoteBook.Note shared=note(f.book,f.v1.recordKey,"Общая exact ссылка",789);f.book.addToAir(shared.id);
            if(retire)f.book.deleteSource(f.source.id,f.persisted);else f.book.deleteAllNews(f.persisted);
            NoteBook retired=new NoteBook(f.store);assertNotNull(retired.sourceVersion(f.v1.recordKey));assertNotNull(retired.sourceVersion(f.v2.recordKey));int saves=f.store.saves;
            assertTrue(retired.applyDeletion(retired.prepareDeleteNote(f.target.id,f.target.revision,f.persisted)));assertEquals(saves+1,f.store.saves);retired=new NoteBook(f.store);assertNull(retired.find(f.target.id));assertEquals(f.v1.spokenText,retired.sourceVersion(f.v1.recordKey).spokenText);exactNote(shared,retired.find(shared.id));exactNote(f.other,retired.find(f.other.id));
            assertTrue(retired.applyDeletion(retired.prepareDeleteNote(shared.id,shared.revision,f.persisted)));retired=new NoteBook(f.store);assertNull(retired.sourceVersion(f.v1.recordKey));assertNull(retired.sourceHistory(f.v1.recordKey));assertEquals(f.v2.spokenText,retired.sourceVersion(f.v2.recordKey).spokenText);if(retire)assertNull(retired.provenanceSourceItem(f.source.id,f.first.itemKey));else {assertTrue(retired.isItemDeleted(f.source.id,f.first.itemKey));assertNull(retired.provenanceSourceItem(f.source.id,f.first.itemKey).latestVersionKey);}
            assertTrue(retired.applyDeletion(retired.prepareDeleteNote(f.other.id,f.other.revision,f.persisted)));retired=new NoteBook(f.store);assertTrue(retired.notes().isEmpty());assertTrue(retired.materials().isEmpty());assertNull(retired.sourceVersion(f.v2.recordKey));assertNull(retired.sourceHistory(f.v2.recordKey));if(retire)assertNull(retired.provenanceSourceItem(f.source.id,f.second.itemKey));else assertTrue(retired.isItemDeleted(f.source.id,f.second.itemKey));assertEquals(f.source.url,retired.source(f.source.id).url);assertEquals(f.source.enabled,retired.source(f.source.id).enabled);
        }
    }

    private static void exactFields(Object expected,Object actual)throws Exception{assertNotNull(actual);for(java.lang.reflect.Field field:expected.getClass().getFields())assertEquals("Unchanged source field "+field.getName(),field.get(expected),field.get(actual));}
    private static void exactMaterial(NoteBook.Material expected,NoteBook.Material actual){assertNotNull(actual);assertEquals(expected.stationId,actual.stationId);assertEquals(expected.articleId,actual.articleId);assertEquals(expected.version,actual.version);assertEquals(expected.sequence,actual.sequence);assertEquals(expected.title,actual.title);assertEquals(expected.text,actual.text);assertEquals(expected.noteId,actual.noteId);assertEquals(expected.sourceVersionKey,actual.sourceVersionKey);assertEquals(expected.asArticle().sentenceStarts,actual.asArticle().sentenceStarts);}
    private static void exactNote(NoteBook.Note expected,NoteBook.Note actual){assertNotNull(actual);assertEquals(expected.id,actual.id);assertEquals(expected.createdAt,actual.createdAt);assertEquals(expected.title,actual.title);assertEquals(expected.text,actual.text);assertEquals(expected.revision,actual.revision);assertEquals(expected.durationMs,actual.durationMs);assertEquals(expected.positionMs,actual.positionMs);assertEquals(expected.hasAudio,actual.hasAudio);assertEquals(expected.recordingStatus,actual.recordingStatus);assertEquals(expected.recognitionStatus,actual.recognitionStatus);assertEquals(expected.message,actual.message);assertEquals(expected.linkedStation,actual.linkedStation);assertEquals(expected.linkedArticle,actual.linkedArticle);assertEquals(expected.linkedVersion,actual.linkedVersion);assertEquals(expected.linkedSourceVersionKey,actual.linkedSourceVersionKey);}
}

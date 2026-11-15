package ru.bububu.radio.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import ru.bububu.radio.sources.*;
import static org.junit.Assert.*;

/** Real prepared Book mutations, real capsule files, and exact byte authority failures. */
public final class BulkBookMutationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    private static final class Fixture {
        final DeletionBookTest.Store store;
        final NoteBook book;
        final SourceRecord first,second;
        final SourceItem one,two;
        final SourceVersionRecord v1,v2;
        final NoteBook.Note note;
        final NoteBook.Material original;
        final PlayerController.SavedState persisted;
        Fixture(File directory) throws Exception {
            store=new DeletionBookTest.Store(directory);book=new NoteBook(store);
            first=SourceRecord.create("RSS","bulk-first","Первый источник","https://example.test/first","rss1",true);
            second=SourceRecord.create("RSS","bulk-second","Второй источник","https://example.test/second","rss1",false);
            one=SourceItem.create(first.id,"one","p-one","https://example.test/one","Первая новость",0,0,1);
            two=SourceItem.create(second.id,"two","p-two","https://example.test/two","Вторая новость",0,0,1);
            book.commitSource(first,Collections.singletonList(one));book.commitSource(second,Collections.singletonList(two));book.setSourceEnabled(second.id,false);
            v1=SourceBookTest.make(first,one,"Первая фраза. Вторая фраза.","full");v2=SourceBookTest.make(second,two,"Другая фраза. Последняя фраза.","full");
            book.saveSourceVersion(v1);book.saveSourceVersion(v2);book.selectSourceVersion(v1,false);book.selectSourceVersion(v2,false);
            book.setSourceRead(first.id,one.itemKey,true);book.setSourceViewed(first.id,one.itemKey,v1.recordKey);
            note=book.beginRecordingLinkedSource("Своя запись",v1.recordKey,2);book.finishRecording(note.id,1000,true,false,null);book.setOriginalPosition(note.id,345);book.edit(note.id,"Своя запись","Первая ручная редакция. Следующая фраза.");
            original=book.addToAir(note.id);book.edit(note.id,"Своя запись","Новая ручная редакция. Следующая фраза.");
            Files.write(new File(directory,note.id+".wav").toPath(),new byte[]{1,3,5,7});
            Map<String,PlayerController.SavedProgress> progress=new LinkedHashMap<>();
            for(NoteBook.Material material:book.materials()) {
                PlayerController.SavedProgress p=new PlayerController.SavedProgress(material.stationId,material.articleId,material.version,1,false,Collections.singletonList(0),true,true);
                progress.put(PlayerController.progressKey(p.stationId,p.articleId,p.version),p);
            }
            Map<String,String> cursors=new LinkedHashMap<>();cursors.put(first.stationId,v1.articleId);cursors.put(second.stationId,v2.articleId);cursors.put(NoteBook.MY_TEXTS,original.articleId);
            persisted=book.encodeCursorProvenance(new PlayerController.SavedState(first.stationId,cursors,progress));
        }
        NoteBook.DeletionChange prepare(int operation) {
            if(operation==0)return book.prepareClearAir(persisted);
            if(operation==1)return book.prepareDeleteAllNews(persisted);
            return book.prepareReplaceNoteInAir(note.id,book.find(note.id).revision,persisted);
        }
    }
    private Fixture fixture() throws Exception {return new Fixture(temporary.newFolder());}
    private static void rejects(Runnable operation){try{operation.run();fail("Expected refusal");}catch(IllegalArgumentException|IllegalStateException expected){}}
    private static NoteBook.Material noteMaterial(NoteBook book,String id){for(NoteBook.Material material:book.materials())if(id.equals(material.noteId))return material;return null;}

    @Test public void clearIsOneCommitRetainsPartialOfflineBodiesMarksAndOwnOriginal() throws Exception {
        Fixture f=fixture();SourceVersionRecord viewed=SourceBookTest.make(f.first,f.one,"Более новая скачанная фраза. Вторая новая фраза.","full");f.book.saveSourceVersion(viewed);f.book.setSourceViewed(f.first.id,f.one.itemKey,viewed.recordKey);
        SourceItem marks=f.book.sourceItem(f.first.id,f.one.itemKey);byte[] before=f.store.load();int saves=f.store.saves;NoteBook.DeletionChange change=f.prepare(0);
        assertArrayEquals(before,f.store.load());assertEquals(saves,f.store.saves);assertTrue(change.changed);assertEquals(3,change.removedMaterials.size());assertEquals(2,change.sourceBodies.size());assertTrue(change.affects(f.first.stationId,f.v1.articleId));assertTrue(change.affectsCurrent);
        assertTrue(f.book.applyDeletion(change));assertEquals(saves+1,f.store.saves);assertTrue(f.book.materials().isEmpty());assertFalse(f.book.applyDeletion(change));assertEquals(saves+1,f.store.saves);
        NoteBook restored=new NoteBook(f.store);assertEquals(f.v1.spokenText,restored.sourceVersion(f.v1.recordKey).spokenText);assertEquals(f.v2.spokenText,restored.sourceVersion(f.v2.recordKey).spokenText);
        assertSame("Clear leaves every immutable item field untouched",marks,f.book.sourceItem(f.first.id,f.one.itemKey));
        assertTrue(restored.sourceItem(f.first.id,f.one.itemKey).read);assertEquals(marks.everListened,restored.sourceItem(f.first.id,f.one.itemKey).everListened);assertEquals(viewed.recordKey,restored.sourceItem(f.first.id,f.one.itemKey).lastViewedVersion);assertEquals(viewed.recordKey,restored.sourceItem(f.first.id,f.one.itemKey).latestVersionKey);
        assertEquals(345,restored.find(f.note.id).positionMs);assertEquals("Новая ручная редакция. Следующая фраза.",restored.find(f.note.id).text);assertEquals(f.v1.recordKey,restored.find(f.note.id).linkedSourceVersionKey);assertArrayEquals(new byte[]{1,3,5,7},Files.readAllBytes(new File(f.store.directory,f.note.id+".wav").toPath()));
        PlayerController.SavedState reconciled=restored.reconcileState(f.persisted);assertTrue(reconciled.stationArticleIds.get(f.first.stationId).startsWith(NoteBook.AFTER_PREFIX));assertEquals(f.persisted.progress.size(),reconciled.progress.size());
        NoteBook.Material readded=restored.selectSourceVersion(f.v2,false);assertTrue(readded.sequence>change.removedMaterials.get(1).sequence);assertEquals(1,readded.selectionInitialProgress.sentenceIndex);assertFalse(restored.isItemDeleted(f.second.id,f.two.itemKey));
        restored.clearAir(f.persisted);int after=f.store.saves;assertFalse(restored.clearAir(f.persisted));assertEquals(after,f.store.saves);
    }

    @Test public void globalNewsDeletePreservesDisabledConnectionsExactNoteContextAndSuppression() throws Exception {
        Fixture f=fixture();List<SourceRecord> connections=f.book.allSources();NoteBook.DeletionChange change=f.prepare(1);int saves=f.store.saves;
        assertEquals(new HashSet<>(Arrays.asList(f.first.id,f.second.id)),change.sourceIds);assertEquals(2,change.removedMaterials.size());assertTrue(f.book.applyDeletion(change));assertEquals(saves+1,f.store.saves);
        NoteBook restored=new NoteBook(f.store);assertEquals(3,restored.schema());assertEquals(2,restored.sources().size());assertTrue(restored.isSourceActive(f.first.id));assertTrue(restored.isSourceActive(f.second.id));assertFalse(restored.source(f.second.id).enabled);assertEquals(connections.get(0).url,restored.source(f.first.id).url);assertEquals(connections.get(1).name,restored.source(f.second.id).name);
        assertEquals(1,restored.sourceGeneration(f.first.id));assertEquals(1,restored.sourceGeneration(f.second.id));assertEquals(restored.lastDeletionSequence(f.first.id),restored.lastDeletionSequence(f.second.id));
        assertTrue(restored.sourceItems(f.first.id).isEmpty());assertTrue(restored.sourceItems(f.second.id).isEmpty());assertTrue(restored.isItemDeleted(f.first.id,f.one.itemKey));assertTrue(restored.isItemDeleted(f.second.id,f.two.itemKey));
        assertEquals(f.v1.spokenText,restored.sourceVersion(f.v1.recordKey).spokenText);assertEquals(1,restored.sourceHistory(f.v1.recordKey).sentenceIndex);assertNull(restored.sourceVersion(f.v2.recordKey));assertNull(restored.sourceHistory(f.v2.recordKey));assertEquals(1,restored.materials().size());assertEquals(f.original.articleId,noteMaterial(restored,f.note.id).articleId);assertEquals(345,restored.find(f.note.id).positionMs);assertEquals(f.v1.recordKey,restored.find(f.note.id).linkedSourceVersionKey);
        rejects(()->restored.selectSourceVersion(f.v1,false));assertEquals(NoteBook.Membership.NOT_MEMBER,restored.queueMembership(f.first.stationId,f.v1.asArticle(),change.removedMaterials.get(0).sequence,f.v1.recordKey));
        SourceItem fresh=SourceItem.create(f.first.id,"fresh","p-fresh","https://example.test/fresh","Новая новость",0,0,10);
        restored.mergeSourceItems(f.first.id,Arrays.asList(f.one,fresh),Collections.singletonList(f.v1),null,null,20,null);assertEquals(1,restored.sourceItems(f.first.id).size());assertEquals("fresh",restored.sourceItems(f.first.id).get(0).itemKey);assertTrue(restored.isItemDeleted(f.first.id,f.one.itemKey));
        restored.deleteAllNews(f.persisted);int finalSaves=f.store.saves;long sequence=restored.sequence();assertFalse(restored.deleteAllNews(f.persisted));assertEquals(finalSaves,f.store.saves);assertEquals(sequence,restored.sequence());
    }

    @Test public void savedNoteReplacementHasNewIdentitySequenceAndOldTombstoneWithoutChangingNote() throws Exception {
        Fixture f=fixture();NoteBook.Note saved=f.book.find(f.note.id);List<NoteBook.Material> before=f.book.materials();int saves=f.store.saves;
        NoteBook.DeletionChange change=f.prepare(2);assertNotNull(change.expectedRevisionHash);assertFalse(change.affectsCurrent);assertTrue(change.affects(NoteBook.MY_TEXTS,f.original.articleId));assertTrue(f.book.applyDeletion(change));assertEquals(saves+1,f.store.saves);
        NoteBook.Material replacement=change.replacement;assertNotEquals(f.original.articleId,replacement.articleId);assertNotEquals(f.original.version,replacement.version);assertTrue(replacement.sequence>f.original.sequence);assertEquals(saved.text,replacement.text);assertEquals(f.note.id,replacement.noteId);
        assertEquals(before.get(0).articleId,f.book.materials().get(0).articleId);assertEquals(before.get(1).articleId,f.book.materials().get(1).articleId);assertEquals(replacement.articleId,f.book.materials().get(2).articleId);
        assertSame(saved,f.book.find(f.note.id));NoteBook restored=new NoteBook(f.store);assertEquals(345,restored.find(f.note.id).positionMs);assertEquals(saved.revision,restored.find(f.note.id).revision);assertEquals(f.v1.recordKey,restored.find(f.note.id).linkedSourceVersionKey);
        assertNotEquals(f.original.articleId,restored.reconcileState(f.persisted).stationArticleIds.get(NoteBook.MY_TEXTS));assertEquals(NoteBook.Membership.NOT_MEMBER,restored.queueMembership(NoteBook.MY_TEXTS,f.original.asArticle(),f.original.sequence,null));assertEquals(NoteBook.Membership.ALLOWED,restored.queueMembership(NoteBook.MY_TEXTS,replacement.asArticle(),replacement.sequence,null));
        int after=f.store.saves;assertFalse(restored.applyDeletion(restored.prepareReplaceNoteInAir(f.note.id,saved.revision,f.persisted)));assertEquals(after,f.store.saves);
        rejects(()->restored.prepareReplaceNoteInAir(f.note.id,saved.revision-1,f.persisted));assertEquals(after,f.store.saves);
    }

    @Test public void allThreeActualPreparedPathsResolveKnownOldExactTargetAndUnknown() throws Exception {
        for(int operation=0;operation<3;operation++)for(int outcome=0;outcome<3;outcome++) {
            Fixture f=fixture();NoteBook.DeletionChange change=f.prepare(operation);byte[] old=f.store.load();int saves=f.store.saves;
            f.store.failBefore=outcome==0;f.store.failAfter=outcome==1;f.store.unknown=outcome==2;
            if(outcome==1){assertTrue(f.book.applyDeletion(change));assertFalse(f.book.storageUncertain());assertTarget(new NoteBook(f.store),f,operation);}
            else {rejects(()->f.book.applyDeletion(change));if(outcome==0){assertArrayEquals(old,f.store.load());assertFalse(f.book.storageUncertain());assertEquals(3,f.book.materials().size());}else{assertTrue(f.book.storageUncertain());assertEquals(NoteBook.Membership.UNVERIFIED,f.book.queueMembership(f.first.stationId,f.v1.asArticle(),f.book.materials().get(0).sequence,f.v1.recordKey));rejects(()->f.book.prepareClearAir(f.persisted));}}
            assertEquals(saves+1,f.store.saves);
            f.store.failBefore=false;f.store.failAfter=false;f.store.unknown=false;f.store.unreadable=false;
            if(outcome==0){assertTrue(f.book.applyDeletion(change));assertTarget(new NoteBook(f.store),f,operation);}else if(outcome==2){assertTarget(new NoteBook(f.store),f,operation);rejects(()->f.book.applyDeletion(change));}
        }
    }
    private static void assertTarget(NoteBook restored,Fixture f,int operation){if(operation==0)assertTrue(restored.materials().isEmpty());else if(operation==1){assertTrue(restored.sourceItems(f.first.id).isEmpty());assertTrue(restored.sourceItems(f.second.id).isEmpty());assertEquals(1,restored.materials().size());assertTrue(restored.isItemDeleted(f.first.id,f.one.itemKey));}else assertEquals(f.book.find(f.note.id).text,noteMaterial(restored,f.note.id).text);assertEquals(345,restored.find(f.note.id).positionMs);}

    @Test public void stalePreparedRevisionAndDifferentDiskAuthorityRefuseWithoutOverwrite() throws Exception {
        for(int operation=0;operation<3;operation++) {
            Fixture f=fixture();NoteBook.DeletionChange stale=f.prepare(operation);f.book.edit(f.note.id,"Самая свежая ручная правка","Последний ручной текст.");byte[] fresh=f.store.load();int saves=f.store.saves;rejects(()->f.book.applyDeletion(stale));assertArrayEquals(fresh,f.store.load());assertEquals(saves,f.store.saves);
            NoteBook.DeletionChange prepared=f.prepare(operation);NoteBook external=new NoteBook(f.store);external.setOriginalPosition(f.note.id,456);byte[] actual=f.store.load();saves=f.store.saves;rejects(()->f.book.applyDeletion(prepared));assertArrayEquals(actual,f.store.load());assertEquals(saves,f.store.saves);assertEquals(456,f.book.find(f.note.id).positionMs);assertFalse(f.book.storageUncertain());
        }
        Fixture downgraded=fixture();byte[] schema2=downgraded.store.load();downgraded.book.deleteAllNews(downgraded.persisted);assertEquals(3,downgraded.book.schema());
        NoteBook.DeletionChange prepared=downgraded.book.prepareClearAir(downgraded.persisted);downgraded.store.bytes=schema2;
        rejects(()->downgraded.book.applyDeletion(prepared));assertTrue(downgraded.book.storageUncertain());assertEquals(3,downgraded.book.schema());assertArrayEquals(schema2,downgraded.store.load());
    }

    @Test public void preflightValidationAndCapFailureNeverPublishAnyPreparedSubset() throws Exception {
        Fixture f=fixture();byte[] old=f.store.load();int saves=f.store.saves;
        rejects(()->f.book.prepareDeleteAllNews(null));assertArrayEquals(old,f.store.load());assertEquals(saves,f.store.saves);
        PlayerController.SavedProgress source=f.persisted.progress.values().iterator().next();PlayerController.SavedState miskeyed=new PlayerController.SavedState(source.stationId,Collections.emptyMap(),Collections.singletonMap("wrong-key",source));
        rejects(()->f.book.prepareClearAir(miskeyed));rejects(()->f.book.prepareDeleteAllNews(miskeyed));rejects(()->f.book.prepareReplaceNoteInAir(f.note.id,-1,f.persisted));assertArrayEquals(old,f.store.load());assertEquals(saves,f.store.saves);
        for(int operation=0;operation<3;operation++) {
            Fixture capped=fixture();capped.book.addSavedMaterial("q","Короткая станция",new Article("live","v1","Текст","Текст."));
            java.lang.reflect.Field stateField=NoteBook.class.getDeclaredField("state");stateField.setAccessible(true);Object state=stateField.get(capped.book);java.lang.reflect.Field tombstonesField=state.getClass().getDeclaredField("tombstones");tombstonesField.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String,Long> tombstones=(Map<String,Long>)tombstonesField.get(state);for(int i=0;i<200000;i++)tombstones.put("1:qx"+i,1L);
            java.lang.reflect.Method encoder=NoteBook.class.getDeclaredMethod("encode",state.getClass());encoder.setAccessible(true);capped.store.bytes=(byte[])encoder.invoke(null,state);
            assertEquals(4,new NoteBook(capped.store).materials().size()); // The populated cap baseline is independently decodable.
            byte[] durable=capped.store.load();int count=capped.store.saves,selectedOperation=operation;rejects(()->capped.prepare(selectedOperation));assertArrayEquals(durable,capped.store.load());assertEquals(count,capped.store.saves);
        }
    }

    @Test public void replacingFirstOfTwoSelectedNotesReloadsAscendingSelectionsAndExactOriginals() throws Exception {
        Fixture f=fixture();populateSchema3(f);
        NoteBook.Note second=f.book.beginRecording("Вторая своя запись",null,null,null,9);f.book.finishRecording(second.id,900,true,false,"Исходная запись");f.book.setOriginalPosition(second.id,222);f.book.edit(second.id,"Вторая своя запись","Второй ручной текст. Вторая фраза.");NoteBook.Material other=f.book.addToAir(second.id);
        File secondWave=new File(f.store.directory,second.id+".wav");byte[] secondBytes={2,4,6,8};Files.write(secondWave.toPath(),secondBytes);
        NoteBook.Note savedFirst=f.book.find(f.note.id),savedSecond=f.book.find(second.id);List<NoteBook.Material> untouched=new ArrayList<>(f.book.materials());untouched.remove(f.original);
        int saves=f.store.saves;NoteBook.DeletionChange change=f.book.prepareReplaceNoteInAir(f.note.id,savedFirst.revision,f.persisted);assertEquals(saves,f.store.saves);assertTrue(f.book.applyDeletion(change));assertEquals(saves+1,f.store.saves);
        NoteBook restored=new NoteBook(f.store);assertEquals(3,restored.schema());assertAscendingMaterials(restored);assertEquals(change.replacement.articleId,restored.materials().get(restored.materials().size()-1).articleId);
        assertNotEquals(f.original.articleId,change.replacement.articleId);assertNotEquals(f.original.version,change.replacement.version);assertTrue(change.replacement.sequence>other.sequence);assertEquals(savedFirst.text,change.replacement.text);
        assertExactNote(savedFirst,restored.find(f.note.id));assertExactNote(savedSecond,restored.find(second.id));assertArrayEquals(new byte[]{1,3,5,7},Files.readAllBytes(new File(f.store.directory,f.note.id+".wav").toPath()));assertArrayEquals(secondBytes,Files.readAllBytes(secondWave.toPath()));assertEquals(f.v1.spokenText,restored.sourceVersion(savedFirst.linkedSourceVersionKey).spokenText);
        for(NoteBook.Material expected:untouched)assertExactMaterial(expected,restored.material(expected.stationId,expected.articleId));
        assertEquals(NoteBook.Membership.NOT_MEMBER,restored.queueMembership(f.original.stationId,f.original.asArticle(),f.original.sequence,null));assertEquals("The old selected marker resolves through its tombstone to the surviving Note",other.articleId,restored.reconcileState(f.persisted).stationArticleIds.get(NoteBook.MY_TEXTS));
        int after=f.store.saves;byte[] target=f.store.load();assertFalse(restored.applyDeletion(restored.prepareReplaceNoteInAir(f.note.id,savedFirst.revision,f.persisted)));assertEquals(after,f.store.saves);assertArrayEquals(target,f.store.load());assertAscendingMaterials(new NoteBook(f.store));
    }

    @Test public void replacingSelectedNoteBeforeSourceReloadsExactSourceSelectionAndLinkedPosition() throws Exception {
        Fixture f=fixture();populateSchema3(f);
        PlayerController.SavedProgress first=f.persisted.progress.get(PlayerController.progressKey(f.first.stationId,f.v1.articleId,f.v1.versionKey));PlayerController.SavedProgress second=f.persisted.progress.get(PlayerController.progressKey(f.second.stationId,f.v2.articleId,f.v2.versionKey));
        f.book.removeSourceMaterial(f.first.stationId,f.v1.articleId,first);f.book.removeSourceMaterial(f.second.stationId,f.v2.articleId,second);NoteBook.Material source=f.book.selectSourceVersion(f.v2,false);assertEquals(f.original.articleId,f.book.materials().get(0).articleId);assertTrue(source.sequence>f.original.sequence);
        NoteBook.Note saved=f.book.find(f.note.id);int saves=f.store.saves;NoteBook.DeletionChange change=f.book.prepareReplaceNoteInAir(f.note.id,saved.revision,f.persisted);assertTrue(f.book.applyDeletion(change));assertEquals(saves+1,f.store.saves);
        NoteBook restored=new NoteBook(f.store);assertEquals(3,restored.schema());assertAscendingMaterials(restored);assertEquals(source.articleId,restored.materials().get(0).articleId);assertEquals(change.replacement.articleId,restored.materials().get(1).articleId);assertTrue(change.replacement.sequence>source.sequence);assertNotEquals(f.original.articleId,change.replacement.articleId);assertNotEquals(f.original.version,change.replacement.version);
        assertExactMaterial(source,restored.material(source.stationId,source.articleId));assertEquals(source.selectionInitialProgress.sentenceIndex,restored.material(source.stationId,source.articleId).selectionInitialProgress.sentenceIndex);assertEquals(source.selectionInitialProgress.completedSentences,restored.material(source.stationId,source.articleId).selectionInitialProgress.completedSentences);
        assertExactNote(saved,restored.find(f.note.id));assertArrayEquals(new byte[]{1,3,5,7},Files.readAllBytes(new File(f.store.directory,f.note.id+".wav").toPath()));assertEquals(f.v1.spokenText,restored.sourceVersion(saved.linkedSourceVersionKey).spokenText);assertEquals(first.sentenceIndex,restored.sourceHistory(f.v1.recordKey).sentenceIndex);assertEquals(second.sentenceIndex,restored.sourceHistory(f.v2.recordKey).sentenceIndex);
        assertEquals(NoteBook.Membership.NOT_MEMBER,restored.queueMembership(f.original.stationId,f.original.asArticle(),f.original.sequence,null));assertEquals("The old selected marker resolves through its tombstone to the new revision",change.replacement.articleId,restored.reconcileState(f.persisted).stationArticleIds.get(NoteBook.MY_TEXTS));
        byte[] target=f.store.load();int after=f.store.saves;assertFalse(restored.applyDeletion(restored.prepareReplaceNoteInAir(f.note.id,saved.revision,f.persisted)));assertEquals(after,f.store.saves);assertArrayEquals(target,f.store.load());assertAscendingMaterials(new NoteBook(f.store));
    }

    private static void populateSchema3(Fixture f){SourceItem obsolete=SourceItem.create(f.first.id,"obsolete",null,null,"Удалённая identity",0,0,1);f.book.mergeSourceItems(f.first.id,Collections.singletonList(obsolete),Collections.emptyList(),null,null,1,null);f.book.deleteSourceItem(f.first.id,obsolete.itemKey,null);assertEquals(3,new NoteBook(f.store).schema());}
    private static void assertAscendingMaterials(NoteBook book){long sequence=0;for(NoteBook.Material material:book.materials()){assertTrue("Durable materials must have strictly ascending sequence",material.sequence>sequence);sequence=material.sequence;}}
    private static void assertExactMaterial(NoteBook.Material expected,NoteBook.Material actual){assertNotNull(actual);assertEquals(expected.stationId,actual.stationId);assertEquals(expected.stationName,actual.stationName);assertEquals(expected.articleId,actual.articleId);assertEquals(expected.version,actual.version);assertEquals(expected.title,actual.title);assertEquals(expected.text,actual.text);assertEquals(expected.sequence,actual.sequence);assertEquals(expected.noteId,actual.noteId);assertEquals(expected.sourceVersionKey,actual.sourceVersionKey);assertEquals(expected.asArticle().sentenceStarts,actual.asArticle().sentenceStarts);}
    private static void assertExactNote(NoteBook.Note expected,NoteBook.Note actual){assertNotNull(actual);assertEquals(expected.id,actual.id);assertEquals(expected.createdAt,actual.createdAt);assertEquals(expected.title,actual.title);assertEquals(expected.text,actual.text);assertEquals(expected.revision,actual.revision);assertEquals(expected.durationMs,actual.durationMs);assertEquals(expected.positionMs,actual.positionMs);assertEquals(expected.hasAudio,actual.hasAudio);assertEquals(expected.linkedStation,actual.linkedStation);assertEquals(expected.linkedArticle,actual.linkedArticle);assertEquals(expected.linkedVersion,actual.linkedVersion);assertEquals(expected.linkedSourceVersionKey,actual.linkedSourceVersionKey);assertEquals(expected.recordingStatus,actual.recordingStatus);assertEquals(expected.recognitionStatus,actual.recognitionStatus);assertEquals(expected.message,actual.message);}
}

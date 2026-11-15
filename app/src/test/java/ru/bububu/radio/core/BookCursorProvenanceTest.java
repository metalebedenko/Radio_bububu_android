package ru.bububu.radio.core;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.bububu.radio.sources.*;

public final class BookCursorProvenanceTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final class Fixture {
        final SourceBookTest.Store store=new SourceBookTest.Store(temporary.newFolder());
        final NoteBook book=new NoteBook(store);
        final SourceRecord source=SourceRecord.create("RSS","feed","Лента","https://example.test/feed","rss1",true);
        final SourceItem a=SourceItem.create(source.id,"a",null,null,"Первый",0,0,0),b=SourceItem.create(source.id,"b",null,null,"Второй",0,0,0);
        final SourceVersionRecord va=SourceBookTest.make(source,a,"Первая. Вторая.","full"),vb=SourceBookTest.make(source,b,"Другой текст.","full");
        final NoteBook.Material ma,mb;
        final PlayerController.SavedProgress pa=SourceBookTest.progress(source,va,1,false,Collections.singletonList(0),true);
        Fixture()throws Exception{book.commitSource(source,Arrays.asList(a,b),Arrays.asList(va,vb));ma=book.selectSourceVersion(va,false);mb=book.selectSourceVersion(vb,false);}
        PlayerController.SavedState logical(String cursor){Map<String,String> cursors=new LinkedHashMap<>();cursors.put(source.stationId,cursor);cursors.put("A","A1");cursors.put("B","B2");cursors.put("C","C3");Map<String,PlayerController.SavedProgress> progress=new LinkedHashMap<>();progress.put(PlayerController.progressKey(pa.stationId,pa.articleId,pa.version),pa);progress.put(PlayerController.progressKey(mb.stationId,mb.articleId,mb.version),mb.selectionInitialProgress);return new PlayerController.SavedState(source.stationId,cursors,progress);}
    }
    @Test public void encodedCursorHasExactSequenceAndDoesNotCommitOrChangeProgress()throws Exception {
        Fixture f=new Fixture();PlayerController.SavedState logical=f.logical(f.va.articleId);int writes=f.store.saves;long revision=f.book.revision();PlayerController.SavedState wire=f.book.encodeCursorProvenance(logical);
        assertEquals(f.ma.sequence,NoteBook.selectedSequence(wire.stationArticleIds.get(f.source.stationId)));assertEquals(f.va.articleId,NoteBook.selectedArticle(wire.stationArticleIds.get(f.source.stationId)));assertEquals(f.source.stationId,wire.currentStationId);assertEquals(logical.progress,wire.progress);assertEquals(f.va.articleId,logical.stationArticleIds.get(f.source.stationId));assertEquals(revision,f.book.revision());assertEquals(writes,f.store.saves);assertEquals("A1",wire.stationArticleIds.get("A"));assertEquals("B2",wire.stationArticleIds.get("B"));assertEquals("C3",wire.stationArticleIds.get("C"));
        assertEquals(wire.stationArticleIds,f.book.encodeCursorProvenance(wire).stationArticleIds);
    }
    @Test public void sameVersionReaddOldWireChoosesFirstSuccessorRatherThanReaddedId()throws Exception {
        Fixture f=new Fixture();PlayerController.SavedState old=f.book.encodeCursorProvenance(f.logical(f.va.articleId));f.book.removeSourceMaterial(f.source.stationId,f.va.articleId,f.pa);NoteBook.Material readded=f.book.selectSourceVersion(f.va,false);assertTrue(readded.sequence>f.mb.sequence);
        PlayerController.SavedState reconciled=new NoteBook(f.store).reconcileState(old);assertEquals(f.vb.articleId,reconciled.stationArticleIds.get(f.source.stationId));assertEquals(1,reconciled.progress.get(PlayerController.progressKey(f.pa.stationId,f.pa.articleId,f.pa.version)).sentenceIndex);assertEquals("A1",reconciled.stationArticleIds.get("A"));
    }
    @Test public void freshExplicitCursorSelectsReaddedSameVersionWithItsSavedPosition()throws Exception {
        Fixture f=new Fixture();PlayerController.SavedState old=f.book.encodeCursorProvenance(f.logical(f.va.articleId));f.book.removeSourceMaterial(f.source.stationId,f.va.articleId,f.pa);NoteBook.Material readded=f.book.selectSourceVersion(f.va,false);PlayerController.SavedState fresh=f.book.encodeCursorProvenance(f.logical(f.va.articleId));assertNotEquals(old.stationArticleIds.get(f.source.stationId),fresh.stationArticleIds.get(f.source.stationId));assertEquals(readded.sequence,NoteBook.selectedSequence(fresh.stationArticleIds.get(f.source.stationId)));
        PlayerController.SavedState reconciled=new NoteBook(f.store).reconcileState(fresh);assertEquals(f.va.articleId,reconciled.stationArticleIds.get(f.source.stationId));assertEquals(1,reconciled.progress.get(PlayerController.progressKey(f.pa.stationId,f.pa.articleId,f.pa.version)).sentenceIndex);
    }
    @Test public void legacyPlainReaddCursorConservativelyUsesRemovalBoundary()throws Exception {
        Fixture f=new Fixture();PlayerController.SavedState old=f.logical(f.va.articleId);f.book.removeSourceMaterial(f.source.stationId,f.va.articleId,f.pa);f.book.selectSourceVersion(f.va,false);assertEquals(f.vb.articleId,new NoteBook(f.store).reconcileState(old).stationArticleIds.get(f.source.stationId));
    }
    @Test public void editedNewVersionReaddUsesOldCursorSequenceAndNewVersionZeroSeed()throws Exception {
        Fixture f=new Fixture();PlayerController.SavedState old=f.book.encodeCursorProvenance(f.logical(f.va.articleId));f.book.removeSourceMaterial(f.source.stationId,f.va.articleId,f.pa);SourceVersionRecord edited=SourceBookTest.make(f.source,f.a,"Исправленный текст.","full");f.book.saveSourceVersion(edited);f.book.selectSourceVersion(edited,false);PlayerController.SavedState restored=new NoteBook(f.store).reconcileState(old);
        assertEquals(f.vb.articleId,restored.stationArticleIds.get(f.source.stationId));PlayerController.SavedProgress seed=restored.progress.get(PlayerController.progressKey(f.source.stationId,edited.articleId,edited.versionKey));assertEquals(0,seed.sentenceIndex);assertFalse(seed.started);assertTrue(seed.completedSentences.isEmpty());
    }
    @Test public void afterBoundaryRemainsAStoppedMarkerWithForwardCandidate()throws Exception {
        Fixture f=new Fixture();String boundary=NoteBook.boundaryMarker(f.ma.sequence,f.vb.articleId);PlayerController.SavedState wire=f.book.encodeCursorProvenance(f.logical(boundary));assertEquals(boundary,wire.stationArticleIds.get(f.source.stationId));String restored=new NoteBook(f.store).reconcileState(wire).stationArticleIds.get(f.source.stationId);assertTrue(restored.startsWith(NoteBook.AFTER_PREFIX));assertEquals(f.vb.articleId,NoteBook.boundaryCandidate(restored));
    }
    @Test public void malformedSelectedMarkerFailsWithoutChangingAnyBookBytes()throws Exception {
        Fixture f=new Fixture();byte[] before=f.store.load();String base=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(f.va.articleId.getBytes(java.nio.charset.StandardCharsets.UTF_8));String[] malformed={"@selected:0:"+base,"@selected:-1:"+base,"@selected:01:"+base,"@selected:1:!","@selected:1:","@selected:99999999999999999999999:"+base,"@selected:3:"+base,"@selected:1:_w"};
        for(String marker:malformed)SourceBookTest.rejects(()->f.book.reconcileState(f.logical(marker)));assertArrayEquals(before,f.store.load());assertEquals(2,f.book.materials().size());
    }
    @Test public void unknownSelectedIdentityFailsClosedAtStationEnd()throws Exception {
        Fixture f=new Fixture();PlayerController.SavedState restored=f.book.reconcileState(f.logical(NoteBook.selectedMarker(1,"unknown")));String cursor=restored.stationArticleIds.get(f.source.stationId);assertEquals(f.mb.sequence,NoteBook.boundarySequence(cursor));assertNull(NoteBook.boundaryCandidate(cursor));assertNotNull(f.book.reconciliationMessage());
    }
    @Test public void unicodeInlineMaterialCursorRoundTripsWithoutSourcesMigration()throws Exception {
        SourceBookTest.Store store=new SourceBookTest.Store(temporary.newFolder());NoteBook book=new NoteBook(store);Article article=new Article("текст:🦉","v1","Проверка","Текст.");NoteBook.Material material=book.addSavedMaterial("NEWS","Новости",article);PlayerController.SavedState logical=new PlayerController.SavedState("NEWS",Collections.singletonMap("NEWS",article.id),Collections.emptyMap());PlayerController.SavedState wire=book.encodeCursorProvenance(logical);assertEquals(1,book.schema());assertEquals(material.sequence,NoteBook.selectedSequence(wire.stationArticleIds.get("NEWS")));assertEquals(article.id,new NoteBook(store).reconcileState(wire).stationArticleIds.get("NEWS"));
    }
}

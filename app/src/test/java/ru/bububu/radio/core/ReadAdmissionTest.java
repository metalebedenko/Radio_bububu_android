package ru.bububu.radio.core;

import java.io.File;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.bububu.radio.sources.*;
import static org.junit.Assert.*;

public final class ReadAdmissionTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    static final class Store extends SourceBookTest.Store {
        Store(File f){super(f);}
        public UpgradeRollbackRef prepareSchema3Upgrade(byte[] bytes,int n){return capsules.prepareSchema3Upgrade(bytes,n,new byte[][]{this.bytes,null,null});}
        public void verifyUpgradeRollback(UpgradeRollbackRef r){capsules.verifyUpgradeRollback(r);}
    }
    @Test public void committedItemDeleteRejectsStaleRuntimeReaderBeforeProjection()throws Exception {
        NoteBook book=new NoteBook(new Store(temporary.newFolder()));
        SourceRecord s=SourceRecord.create("RSS","feed","Own source","https://example.test/feed","rss1",true);
        SourceItem i=SourceItem.create(s.id,"one","provider","https://example.test/one","Own title",1,1,1);book.commitSource(s,Collections.singletonList(i));
        SourceVersionRecord v=SourceBookTest.make(s,i,"Own first sentence. Own second sentence.","full");book.saveSourceVersion(v);NoteBook.Material m=book.selectSourceVersion(v,false);Article stale=m.asArticle();
        assertTrue(ReadAdmission.allowed(book,s.stationId,stale));
        assertTrue(book.deleteSourceItem(s.id,i.itemKey,null));
        // Deliberately retain the untouched Article object: RAM projection never happened.
        assertEquals(v.spokenText,stale.text);assertFalse(ReadAdmission.allowed(book,s.stationId,stale));
        assertTrue(book.deleteSource(s.id,null));assertFalse(ReadAdmission.allowed(book,s.stationId,stale));
    }
    @Test public void wrongPayloadAndUnknownAuthorityCannotGrantSourceReadButFixtureKeepsLocalReader()throws Exception {
        Store store=new Store(temporary.newFolder());NoteBook book=new NoteBook(store);
        SourceRecord s=SourceRecord.create("RSS","feed","Own source","https://example.test/feed","rss1",true);SourceItem i=SourceItem.create(s.id,"one",null,null,"Own",1,1,1);book.commitSource(s,Collections.singletonList(i));
        SourceVersionRecord v=SourceBookTest.make(s,i,"Own body.","full");book.saveSourceVersion(v);NoteBook.Material m=book.selectSourceVersion(v,false);
        assertFalse(ReadAdmission.allowed(book,s.stationId,new Article(m.articleId,m.version,"Changed","Different body.")));
        Article fixture=new Article("A1","v1","Own fixture","Own fixture body.");assertTrue(ReadAdmission.allowed(book,"A",fixture));
        store.failBefore=true;store.unreadable=true;try{book.setSourceRead(s.id,i.itemKey,true);fail("Expected unknown authority");}catch(IllegalStateException expected){}
        assertTrue(book.storageUncertain());assertFalse(ReadAdmission.allowed(book,s.stationId,m.asArticle()));assertFalse(ReadAdmission.allowed(book,"A",fixture));
    }
}

package ru.bububu.radio.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import ru.bububu.radio.sources.*;

public class SourceCap64Test {
    static final class Storage implements NoteBook.Storage {
        byte[] bytes; int writes;
        Storage() {
            List<SourceProfiles45.Profile> profiles=SourceProfiles45.six();
            List<SourceRecord> seeds=new ArrayList<>();Map<String,String> ids=new TreeMap<>();
            for(SourceProfiles45.Profile p:profiles){seeds.add(p.seed());ids.put(p.id,p.seed().id);}
            bytes=NoteBook.fresh45Target(seeds,new Seed45Marker("01234567-89ab-4cde-8fab-0123456789ab",Seed45Marker.POLICY,SourceProfiles45.definitionSetHash(),ids));
        }
        public byte[] load(){return bytes.clone();}
        public void save(byte[] value){bytes=value.clone();writes++;}
    }
    static SourceRecord synthetic(int i){String url="https://example.org/feed/"+i;return SourceRecord.create("rss",url,"Источник "+i,url,"rss",false);}
    static void equalFields(Object before,Object after)throws Exception {
        assertNotNull(after);for(java.lang.reflect.Field f:before.getClass().getFields())assertEquals(f.getName(),f.get(before),f.get(after));
    }
    @Test public void realCatalog22Survives16To17To22AndReopenWithoutDataLoss()throws Exception {
        List<SourceProfiles45.Profile> all=SourceProfiles45.all();assertEquals(22,all.size());
        Storage io=new Storage();NoteBook book=new NoteBook(io);book.enable45();
        for(SourceProfiles45.Profile p:all){if(book.sources().size()==16)break;book.commitSource(p.seed(),List.of());}assertEquals(16,book.sources().size());
        SourceRecord first=book.sources().get(0).withState(true,"fixture-etag","fixture-modified",123,"fixture-error");book.updateSource(first);
        SourceItem item=SourceItem.create(first.id,"kept",null,"https://example.org/story","Сохранённая карточка",17,19,23,"raw-date","raw-modified").withMarks(true,true,null);
        book.mergeSourceItems(first.id,List.of(item),List.of(),first.etag,first.lastModified,first.lastSuccess,first.lastError);
        book.setSourceEnabled(first.id,false);
        NoteBook.Note note=book.beginRecording("Существующая заметка",null,null,null,25);
        book.addSavedMaterial(NoteBook.MY_TEXTS,"Мои тексты",Article.fromSentences("kept-text","1","Свой текст","Сохранённое содержимое."));
        List<SourceRecord> original=new ArrayList<>(book.sources());SourceItem originalItem=book.sourceItem(first.id,item.itemKey);String marker=book.seed45Marker().attemptId;
        SourceProfiles45.Profile seventeenth=all.stream().filter(p->book.source(p.seed().id)==null).findFirst().get();book.commitSource(seventeenth.seed(),List.of());assertEquals(17,book.sources().size());
        for(SourceProfiles45.Profile p:all)book.commitSource(p.seed(),List.of());
        NoteBook reopened=new NoteBook(io);reopened.enable45();assertEquals(22,reopened.sources().size());
        for(SourceProfiles45.Profile p:all){assertNotNull(reopened.source(p.seed().id));assertEquals(p.id,SourceProfiles45.forSource(reopened.source(p.seed().id)).id);}
        for(SourceRecord source:original)equalFields(source,reopened.source(source.id));
        equalFields(originalItem,reopened.sourceItem(first.id,item.itemKey));equalFields(note,reopened.notes().get(0));
        assertEquals(1,reopened.materials().size());assertEquals("Сохранённое содержимое.",reopened.materials().get(0).asArticle().text);
        assertEquals(marker,reopened.seed45Marker().attemptId);assertEquals(4,reopened.schema());
    }
    @Test public void sixtyFourPlusOneRefusesBeforeAnyWriteIncludingPreflight() {
        Storage io=new Storage();NoteBook book=new NoteBook(io);book.enable45();
        for(SourceProfiles45.Profile p:SourceProfiles45.all())book.commitSource(p.seed(),List.of());
        for(int i=22;i<64;i++)book.commitSource(synthetic(i),List.of());
        NoteBook reopened=new NoteBook(io);reopened.enable45();assertEquals(64,reopened.sources().size());byte[] before=io.load();int writes=io.writes;
        SourceRecord extra=synthetic(65);
        assertTrue(assertThrows(IllegalStateException.class,()->reopened.preflightSourceCommit(extra,List.of(),List.of())).getMessage().contains("64"));
        assertTrue(assertThrows(IllegalStateException.class,()->reopened.commitSource(extra,List.of())).getMessage().contains("64"));
        assertArrayEquals(before,io.load());assertEquals(writes,io.writes);assertEquals(64,reopened.sources().size());
        assertEquals(144,SourceLimits.MAX_SOURCE_RECORDS);assertEquals(128,SourceLimits.MAX_RETIRED_SOURCES);
        assertEquals(16000,SourceLimits.MAX_ACTIVE_ITEMS);assertEquals(160000,SourceLimits.MAX_SUPPRESSED_ITEMS);assertEquals(16016,SourceLimits.MAX_ACTION_TARGETS);
    }
    @Test public void totalLifecycle144StillBoundsRetiredAndActiveRecords() {
        Storage io=new Storage();NoteBook book=new NoteBook(io);book.enable45();
        for(SourceRecord s:new ArrayList<>(book.sources()))assertTrue(book.deleteSource(s.id,null));
        for(int i=0;i<75;i++){SourceRecord s=synthetic(i);book.commitSource(s,List.of());assertTrue(book.deleteSource(s.id,null));}
        for(int i=75;i<138;i++)book.commitSource(synthetic(i),List.of());
        NoteBook reopened=new NoteBook(io);reopened.enable45();assertEquals(63,reopened.sources().size());byte[] before=io.load();int writes=io.writes;
        assertTrue(assertThrows(IllegalStateException.class,()->reopened.commitSource(synthetic(144),List.of())).getMessage().contains("144"));
        assertArrayEquals(before,io.load());assertEquals(writes,io.writes);
    }
}

package ru.bububu.radio.sources;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;

public class SourceBodyCacheTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private SourceVersionRecord record()throws Exception{
        SourceRecord source=SourceRecord.create("telegram_public","telegram_public:example","Станция","https://t.me/s/example","telegram-public-v1",false);
        SourceItem item=SourceItem.create(source.id,"id:12","12","https://t.me/example/12","Заголовок",-1,-1,1);
        return SourceCoordinator.version(source,item,new ExtractionResult("Первый абзац.\n\nВторой абзац.","","Первый абзац","unknown","PUBLIC_PREVIEW_COMPLETENESS_UNKNOWN","public_preview_text","telegram-public-v1"));
    }
    @Test public void exactPayloadRoundTripAndMissingMetadataDoesNotInventBody()throws Exception{
        SourceBodyCache cache=new SourceBodyCache(temp.newFolder());SourceVersionRecord v=record();assertNull(cache.get(v.withPayload(null)));cache.put(v);
        assertEquals(v.spokenText,cache.get(v.withPayload(null)).spokenText);assertEquals(v.body(),cache.get(v.withPayload(null)).body());
    }
    @Test public void truncatedCacheIsErrorAndNotAnEmptyText()throws Exception{
        File folder=temp.newFolder();SourceBodyCache cache=new SourceBodyCache(folder);SourceVersionRecord v=record();cache.put(v);
        File file=new File(folder,SourceLimits.sha256(v.recordKey)+".cas");try(RandomAccessFile f=new RandomAccessFile(file,"rw")){f.setLength(9);}
        assertThrows(IOException.class,()->cache.get(v.withPayload(null)));assertEquals(9,file.length());
    }
    @Test public void attackerRechecksChecksumButCannotChangeSpokenSourcePrefix()throws Exception{
        File folder=temp.newFolder();SourceBodyCache cache=new SourceBodyCache(folder);SourceVersionRecord v=record();cache.put(v);
        String substituted="X"+v.spokenText.substring(1);File file=new File(folder,SourceLimits.sha256(v.recordKey)+".cas");
        try(DataOutputStream out=new DataOutputStream(new FileOutputStream(file))){out.writeInt(0x42424331);out.writeInt(substituted.length());for(char c:substituted.toCharArray())out.writeChar(c);out.writeUTF(SourceLimits.sha256(substituted));}
        assertThrows(IOException.class,()->cache.get(v.withPayload(null)));
    }
    @Test public void unknownFilesOverBudgetArePreservedAndNoNewPayloadPublishes()throws Exception{
        File folder=temp.newFolder();SourceBodyCache cache=new SourceBodyCache(folder);File foreign=new File(folder,"keep-evidence");try(RandomAccessFile f=new RandomAccessFile(foreign,"rw")){f.setLength(SourceLimits.MAX_CACHE_BYTES);}
        SourceVersionRecord v=record();assertThrows(IOException.class,()->cache.put(v));assertEquals(SourceLimits.MAX_CACHE_BYTES,foreign.length());assertNull(cache.get(v.withPayload(null)));
    }
    @Test public void symlinkOrDirectoryCacheEntriesAreNeverReadOrDeletedAsPayload()throws Exception{
        File folder=temp.newFolder();SourceBodyCache cache=new SourceBodyCache(folder);SourceVersionRecord v=record();File file=new File(folder,SourceLimits.sha256(v.recordKey)+".cas");assertTrue(file.mkdir());
        assertThrows(IOException.class,()->cache.get(v.withPayload(null)));assertThrows(IOException.class,()->cache.put(v));assertTrue(file.isDirectory());
    }
    @Test public void protectedBookPayloadWorksAfterDisposableCasHasGone()throws Exception{
        SourceBodyCache cache=new SourceBodyCache(temp.newFolder());SourceVersionRecord v=record();assertSame(v,cache.get(v));
    }
}

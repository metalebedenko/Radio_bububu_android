package ru.bububu.radio;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.bububu.radio.sources.SourceLimits;
import static org.junit.Assert.*;

/** The same original must not become managed source bytes while reversible deletion is pending. */
public final class SourceDiskBudgetTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void stagedLargeOriginalKeepsExistingWaveBudgetAndUnknownFilesRemainCounted() throws Exception {
        File directory=temporary.newFolder();String id=UUID.randomUUID().toString();
        long length=SourceLimits.MAX_MANAGED_BYTES+1L;
        File original=new File(directory,id+".wav");try(RandomAccessFile sparse=new RandomAccessFile(original,"rw")){sparse.setLength(length);}
        assertEquals(0,count(directory,true));assertEquals(length,count(directory,false));
        File pending=new File(directory,id+".wav.delete-pending");Files.move(original.toPath(),pending.toPath());
        assertEquals("Staging cannot turn an original into source-budget overflow",0,count(directory,true));assertEquals(length,count(directory,false));
        File partial=new File(directory,id+".wav.part.delete-pending");Files.write(partial.toPath(),new byte[]{1,2});assertEquals(0,count(directory,true));
        File unknown=new File(directory,"unknown.wav.delete-pending");Files.write(unknown.toPath(),new byte[]{3,4,5});assertEquals(3,count(directory,true));
        File metadata=new File(directory,"notes-metadata-test");Files.write(metadata.toPath(),new byte[]{6,7});assertEquals(5,count(directory,true));
    }
    private static long count(File file,boolean originals)throws Exception {Method method=SourceDiskBudget.class.getDeclaredMethod("count",File.class,boolean.class);method.setAccessible(true);return (Long)method.invoke(null,file,originals);}
}

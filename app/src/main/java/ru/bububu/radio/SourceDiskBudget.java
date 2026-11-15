package ru.bububu.radio;

import android.os.StatFs;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import ru.bububu.radio.sources.SourceLimits;

/** Admission counts shared book/recovery/progress and owned caches, preserving original WAVs. */
final class SourceDiskBudget {
    private final File notes,cache,network,files;
    SourceDiskBudget(File notes,File cache,File network,File files) { this.notes=notes; this.cache=cache; this.network=network; this.files=files; }
    void require(long extraPeak) { require(extraPeak,SourceLimits.MIN_FREE_BYTES,5L*1024*1024); }
    // Completion/recovery of an existing original needs actual metadata temp room, not the
    // admission floor for a NEW source download. The original is never an eviction candidate.
    void requireMetadataPeak(long extraPeak) { require(extraPeak,0,64*1024L); }
    private void require(long extraPeak,long floor,long reserve) {
        try {
            long bytes=count(notes,true)+count(cache,false)+count(network,false);
            for(String suffix:new String[]{"",".bak",".new"}) bytes=Math.addExact(bytes,count(new File(files,"player-state-v1.json"+suffix),false));
            long available=new StatFs(notes.getAbsolutePath()).getAvailableBytes();
            if(extraPeak<0 || bytes>SourceLimits.MAX_MANAGED_BYTES-extraPeak || available<floor
                    || available<extraPeak+reserve) throw new IOException("Source disk budget");
        } catch(Exception failure) { throw new IllegalStateException("Недостаточно доступного места для источника; сохранённые данные остаются",failure); }
    }
    private static long count(File file,boolean excludeWav) throws IOException {
        BasicFileAttributes attrs;
        try { attrs=Files.readAttributes(file.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); }
        catch(NoSuchFileException absent) { return 0; }
        if(attrs.isDirectory()) {
            File[] children=file.listFiles(); if(children==null) throw new IOException("Cannot inspect managed directory");
            long bytes=0; for(File child:children) bytes=Math.addExact(bytes,count(child,excludeWav)); return bytes;
        }
        if(!attrs.isRegularFile()) throw new IOException("Unexpected managed path type");
        if(excludeWav && file.getName().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.wav(?:\\.part)?(?:\\.delete-pending)?")) return 0;
        return attrs.size();
    }
}

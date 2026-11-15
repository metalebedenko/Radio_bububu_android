package ru.bububu.radio;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

/** Test-only exact empty production NoteDraftStore image; never decodes with fallback or writes a file. */
public final class Unique45EmptyDraftGuard {
    public static final String NAME="editing-drafts.bin";
    private static final byte[] EMPTY={0x42,0x42,0x44,0x31,0,0,0,0};
    private Unique45EmptyDraftGuard(){}
    public static Snapshot read(Path notes)throws IOException{
        for(String name:new String[]{"editing-drafts.new","editing-drafts.bak",NAME+".new",NAME+".bak"}){
            try{Files.readAttributes(notes.resolve(name),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);throw new IOException("REFUSE_DRAFT_TEMP_OR_BACKUP");}catch(NoSuchFileException absent){}
        }
        Path file=notes.resolve(NAME);BasicFileAttributes a;try{a=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException absent){return new Snapshot(false);}
        if(!a.isRegularFile()||a.size()!=EMPTY.length)throw new IOException("REFUSE_NONCANONICAL_OR_NONEMPTY_DRAFT");
        byte[] bytes=Files.readAllBytes(file);if(!Arrays.equals(bytes,EMPTY))throw new IOException("REFUSE_NONCANONICAL_OR_NONEMPTY_DRAFT");
        return new Snapshot(true);
    }
    public static final class Snapshot {
        public final boolean present;
        private Snapshot(boolean present){this.present=present;}
        public void requirePreserved(Path notes)throws IOException{Snapshot current=read(notes);if(current.present!=present)throw new IOException("DRAFT_PRESENCE_CHANGED");}
        public String fact(){return present?"CANONICAL_EMPTY_4242443100000000":"ABSENT";}
    }
}

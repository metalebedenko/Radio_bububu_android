package ru.bububu.radio.sources;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Disposable exact spoken payloads. Queue/link/partial authority always stays in NoteBook. */
public final class SourceBodyCache {
    private static final int MAGIC=0x42424331;
    private final File directory;
    public SourceBodyCache(File directory) throws IOException {
        this.directory=directory;
        Files.createDirectories(directory.toPath());
        requireDirectory();
    }
    public synchronized SourceVersionRecord get(SourceVersionRecord metadata) throws IOException {
        if(metadata.spokenText!=null) return metadata;
        requireDirectory();
        File file=file(metadata);
        try { requireRegular(file); }
        catch(NoSuchFileException missing) { return null; }
        try(DataInputStream in=new DataInputStream(new FileInputStream(file))) {
            if(in.readInt()!=MAGIC) throw new IOException("Cache format");
            int length=in.readInt(); if(length<1 || length>metadata.textLimit()) throw new IOException("Cache length");
            StringBuilder text=new StringBuilder(length); for(int i=0;i<length;i++) text.append(in.readChar());
            String expected=in.readUTF(); if(in.read()!=-1 || !SourceLimits.sha256(text.toString()).equals(expected)) throw new IOException("Cache checksum");
            try { return metadata.withPayload(text.toString()); }
            catch(RuntimeException corrupt) { throw new IOException("Cache does not match version",corrupt); }
        }
    }
    /** An existing/uncertain local candidate is tried locally before any network admission.
     * The worker still verifies its type, checksum and exact version with get(); only ENOENT
     * denotes a network miss. This query never treats a corrupt file as verified readable text. */
    public synchronized boolean mayHavePayload(SourceVersionRecord metadata) {
        if(metadata.spokenText!=null)return true;
        try { Files.readAttributes(file(metadata).toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return true; }
        catch(NoSuchFileException absent) { return false; }
        catch(IOException|RuntimeException uncertain) { return true; }
    }
    public synchronized void put(SourceVersionRecord record) throws IOException {
        if(record.spokenText==null) throw new IllegalArgumentException("Missing cache payload");
        requireDirectory();
        SourceVersionRecord present=get(record.withPayload(null));if(present!=null){if(!present.spokenText.equals(record.spokenText))throw new IOException("Cache payload collision");return;}
        byte[] bytes=encode(record.spokenText);
        if(bytes.length>1024*1024) throw new IOException("Cache temp limit");
        trim(bytes.length);
        File temp=new File(directory,UUID.randomUUID()+".tmp");
        try {
            try(FileOutputStream out=new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
            Files.move(temp.toPath(),file(record).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp.toPath()); }
    }
    public synchronized long additionalBytes(SourceVersionRecord record)throws IOException{if(record.spokenText==null)throw new IOException("Payload required");return get(record.withPayload(null))==null?encode(record.spokenText).length:0;}
    /** Pre-clear preservation: never evict another downloaded body to save this exact version. */
    public synchronized void preserve(SourceVersionRecord record) throws IOException {
        if(record.spokenText==null)throw new IllegalArgumentException("Missing retained payload");
        requireDirectory();SourceVersionRecord metadata=record.withPayload(null);
        SourceVersionRecord existing=get(metadata);
        if(existing!=null){if(!existing.spokenText.equals(record.spokenText))throw new IOException("Retained payload mismatch");return;}
        byte[] bytes=encode(record.spokenText);if(bytes.length>1024*1024)throw new IOException("Cache temp limit");
        File[] files=directory.listFiles();if(files==null)throw new IOException("Cache directory unreadable");
        long used=0;for(File entry:files)used=Math.addExact(used,requireRegular(entry).size());
        if(used+bytes.length>SourceLimits.MAX_CACHE_BYTES)throw new IOException("No room to retain downloaded texts without eviction");
        File temp=new File(directory,UUID.randomUUID()+".tmp");
        try{
            try(FileOutputStream output=new FileOutputStream(temp)){output.write(bytes);output.getFD().sync();}
            Files.move(temp.toPath(),file(record).toPath(),StandardCopyOption.ATOMIC_MOVE);
            SourceVersionRecord verified=get(metadata);
            if(verified==null||!verified.spokenText.equals(record.spokenText))throw new IOException("Retained copy unverified");
        }finally{Files.deleteIfExists(temp.toPath());}
    }
    /** Caller proved the absence of protected references in all readable recovery images. */
    public synchronized void discard(SourceVersionRecord record) throws IOException {
        requireDirectory();File owned=file(record);
        try{requireRegular(owned);}catch(NoSuchFileException absent){return;}
        Files.delete(owned.toPath());
    }
    private void trim(long additional) throws IOException {
        File[] files=directory.listFiles(); if(files==null) throw new IOException("Cache directory unreadable");
        long total=0;
        for(File file:files) {
            BasicFileAttributes attrs=requireRegular(file); total=Math.addExact(total,attrs.size());
        }
        // Quota refusal preserves every existing candidate. Only the Book/recovery-aware
        // explicit deletion path may reclaim CAS files after its durable transaction.
        if(total+additional>SourceLimits.MAX_CACHE_BYTES)throw new IOException("Cache budget exceeded; existing bodies preserved");
    }
    private File file(SourceVersionRecord record) { return new File(directory,SourceLimits.sha256(record.recordKey)+".cas"); }
    private void requireDirectory() throws IOException {
        if(!Files.readAttributes(directory.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isDirectory()) throw new IOException("Cache path type");
    }
    private static BasicFileAttributes requireRegular(File file) throws IOException {
        BasicFileAttributes attrs=Files.readAttributes(file.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attrs.isRegularFile()) throw new IOException("Cache file type"); return attrs;
    }
    private static byte[] encode(String text) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(text.length()); for(int i=0;i<text.length();i++) out.writeChar(text.charAt(i));
            out.writeUTF(SourceLimits.sha256(text));
        }
        return bytes.toByteArray();
    }
}

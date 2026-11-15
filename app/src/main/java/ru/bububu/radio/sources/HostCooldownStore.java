package ru.bububu.radio.sources;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.CRC32;

/** Only timing state. Unknown or unreadable layouts fail closed without deleting evidence. */
public final class HostCooldownStore {
    private static final int MAGIC=0x42554331, MAX_BYTES=64*1024;
    public static final class Entry {
        public final long notBeforeUtc; public final boolean blocked;
        public Entry(long until,boolean blocked){notBeforeUtc=until;this.blocked=blocked;}
    }
    private final File base,backup,pending;
    public HostCooldownStore(File directory) throws SourceFailure {
        if(directory==null || !directory.isDirectory())throw new SourceFailure("COOLDOWN_STORAGE");
        base=new File(directory,"network-cooldowns-v1.bin");backup=new File(base.getPath()+".bak");pending=new File(base.getPath()+".new");
    }
    public synchronized Map<String,Entry> load() throws SourceFailure {
        try {
            if(Files.exists(pending.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("pending timing layout");
            Map<String,Entry> result=new HashMap<>();
            if(Files.exists(base.toPath(),LinkOption.NOFOLLOW_LINKS))merge(result,decode(read(base)));
            if(Files.exists(backup.toPath(),LinkOption.NOFOLLOW_LINKS))merge(result,decode(read(backup)));
            if(!base.getParentFile().canRead() || result.size()>64)throw new IOException("timing layout");
            return result;
        }catch(IOException|RuntimeException failure){throw new SourceFailure("COOLDOWN_STORAGE",failure);}
    }
    public synchronized void save(Map<String,Entry> values) throws SourceFailure {
        try {
            if(values.size()>64)throw new IOException("timing limit");
            if(Files.exists(pending.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("pending timing layout");
            byte[] payload=encode(values);
            if(Files.exists(backup.toPath(),LinkOption.NOFOLLOW_LINKS))decode(read(backup));
            if(Files.exists(base.toPath(),LinkOption.NOFOLLOW_LINKS)) {
                decode(read(base)); // Refuse type, access, unknown schema and corrupt authoritative data.
                Files.move(base.toPath(),backup.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }
            try(FileOutputStream out=new FileOutputStream(pending)){out.write(payload);out.getFD().sync();}
            Files.move(pending.toPath(),base.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            if(backup.exists() && !backup.delete())throw new IOException("timing shadow retained");
        }catch(IOException|RuntimeException failure){throw new SourceFailure("COOLDOWN_STORAGE",failure);}
    }
    private static void merge(Map<String,Entry> result,Map<String,Entry> other){for(Map.Entry<String,Entry> e:other.entrySet()){Entry old=result.get(e.getKey()),next=e.getValue();result.put(e.getKey(),old==null?next:new Entry(Math.max(old.notBeforeUtc,next.notBeforeUtc),old.blocked||next.blocked));}}
    private static byte[] read(File file)throws IOException {
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS) || file.length()>MAX_BYTES)throw new IOException("timing type/size");
        try(InputStream in=Files.newInputStream(file.toPath());ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(out.size()>MAX_BYTES-n)throw new IOException("timing size");out.write(buffer,0,n);}return out.toByteArray();
        }
    }
    private static byte[] encode(Map<String,Entry> values)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(values.size());
            for(String host:new TreeSet<>(values.keySet())){if(host.isEmpty()||host.length()>253)throw new IOException("host");Entry entry=values.get(host);out.writeUTF(host);out.writeLong(entry.notBeforeUtc);out.writeBoolean(entry.blocked);}
            out.flush();CRC32 crc=new CRC32();crc.update(bytes.toByteArray());out.writeLong(crc.getValue());
        }
        if(bytes.size()>MAX_BYTES)throw new IOException("timing size");return bytes.toByteArray();
    }
    private static Map<String,Entry> decode(byte[] bytes)throws IOException {
        if(bytes.length<16)throw new IOException("timing short");CRC32 crc=new CRC32();crc.update(bytes,0,bytes.length-8);
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=MAGIC)throw new IOException("timing schema");int count=in.readInt();if(count<0||count>64)throw new IOException("timing count");Map<String,Entry> entries=new HashMap<>();
            for(int i=0;i<count;i++){String host=in.readUTF();long time=in.readLong();boolean blocked=in.readBoolean();if(host.isEmpty()||host.length()>253||entries.containsKey(host)||time<0)throw new IOException("timing entry");entries.put(host,new Entry(time,blocked));}
            if(in.readLong()!=crc.getValue()||in.available()!=0)throw new IOException("timing checksum");return entries;
        }
    }
}

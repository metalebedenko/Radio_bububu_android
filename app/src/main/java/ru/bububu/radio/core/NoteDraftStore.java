package ru.bububu.radio.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongConsumer;
import ru.bububu.radio.sources.SourceLimits;

/** Bounded local editing drafts. Never applies text to the committed NoteBook. */
public final class NoteDraftStore {
    public static final class Draft {
        public final String id,title,text; public final long revision;
        public Draft(String id,long revision,String title,String text){
            if(id==null||id.isEmpty()||id.length()>128||revision<0||title==null||text==null
                    ||title.length()>SourceLimits.MAX_DISPLAY_TITLE||text.length()>NoteBook.MAX_TEXT)throw new IllegalArgumentException("Draft bounds");
            this.id=id;this.revision=revision;this.title=title;this.text=text;
        }
    }
    private final File file; private final LongConsumer quota;
    public NoteDraftStore(File directory,LongConsumer quota){file=new File(directory,"editing-drafts.bin");this.quota=quota;}
    public Map<String,Draft> load() throws IOException {
        Map<String,Draft> result=new LinkedHashMap<>();if(!file.exists())return result;
        if(Files.isSymbolicLink(file.toPath())||file.length()>SourceLimits.MAX_BOOK_BYTES)throw new IOException("Draft storage bounds");
        try(DataInputStream in=new DataInputStream(new FileInputStream(file))){
            if(in.readInt()!=0x42424431)throw new IOException("Draft format");int count=in.readInt();
            if(count<0||count>100)throw new IOException("Draft count");
            for(int i=0;i<count;i++){
                Draft draft=new Draft(in.readUTF(),in.readLong(),in.readUTF(),in.readUTF());
                if(result.put(draft.id,draft)!=null)throw new IOException("Duplicate draft");
            }
            if(in.read()!=-1)throw new IOException("Draft trailing bytes");
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid draft",invalid);}
        return result;
    }
    public void save(Collection<Draft> drafts) throws IOException {
        if(drafts.size()>100)throw new IOException("Draft count");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)){
            out.writeInt(0x42424431);out.writeInt(drafts.size());Set<String> ids=new HashSet<>();
            for(Draft d:drafts){if(!ids.add(d.id))throw new IOException("Duplicate draft");out.writeUTF(d.id);out.writeLong(d.revision);out.writeUTF(d.title);out.writeUTF(d.text);}
        }
        if(bytes.size()>SourceLimits.MAX_BOOK_BYTES)throw new IOException("Draft storage bounds");
        quota.accept(bytes.size());File next=new File(file.getParentFile(),"editing-drafts.new");
        if(Files.isSymbolicLink(file.toPath())||Files.isSymbolicLink(next.toPath()))throw new IOException("Unsafe draft path");
        try(FileOutputStream stream=new FileOutputStream(next)){bytes.writeTo(stream);stream.getFD().sync();}
        Files.move(next.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        try(java.nio.channels.FileChannel directory=java.nio.channels.FileChannel.open(file.getParentFile().toPath(),StandardOpenOption.READ)){directory.force(true);}
    }
}

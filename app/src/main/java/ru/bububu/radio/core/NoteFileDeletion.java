package ru.bububu.radio.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Same-directory reversible WAV staging; metadata authority always precedes recovery I/O. */
public final class NoteFileDeletion {
    public enum Presence { PRESENT, ABSENT, UNKNOWN }
    public interface Authority { Presence presence(String id); }
    public interface Operations {
        void move(Path from,Path to) throws IOException;
        void delete(Path file) throws IOException;
        void syncDirectory(Path directory) throws IOException;
    }
    private static final Operations NIO=new Operations(){
        public void move(Path from,Path to)throws IOException{Files.move(from,to);}
        public void delete(Path file)throws IOException{Files.delete(file);}
        public void syncDirectory(Path directory)throws IOException{
            // Some Android/JVM providers do not expose directory channels.
            FileChannel channel;
            try{channel=FileChannel.open(directory,StandardOpenOption.READ);}catch(IOException|UnsupportedOperationException unavailable){return;}
            try(FileChannel opened=channel){opened.force(true);}
        }
    };
    private final Path directory;
    private final Operations operations;
    public NoteFileDeletion(Path directory){this(directory,NIO);}
    public NoteFileDeletion(Path directory,Operations operations){this.directory=Objects.requireNonNull(directory);this.operations=Objects.requireNonNull(operations);}
    public void stage(String id){
        canonical(id);List<Path[]> moved=new ArrayList<>();
        try{
            requireDirectory();List<Path[]> pairs=pairs(id);
            for(Path[] pair:pairs){regularOrAbsent(pair[0]);if(regularOrAbsent(pair[1]))throw new IOException("Deletion staging already exists");}
            for(Path[] pair:pairs)if(regularOrAbsent(pair[0])){
                if(regularOrAbsent(pair[1]))throw new IOException("Deletion staging conflict");
                moved.add(pair);operations.move(pair[0],pair[1]);operations.syncDirectory(directory);
            }
        }catch(IOException|RuntimeException failure){
            for(int i=moved.size()-1;i>=0;i--)try{Path[] pair=moved.get(i);boolean original=regularOrAbsent(pair[0]),pending=regularOrAbsent(pair[1]);if(original&&!pending)continue;if(original||!pending)throw new IOException("Cannot safely roll back staging");operations.move(pair[1],pair[0]);operations.syncDirectory(directory);}catch(IOException|RuntimeException rollback){failure.addSuppressed(rollback);}
            throw new IllegalStateException("Cannot stage Note deletion; originals or staging files preserved",failure);
        }
    }
    public boolean recover(String id,Authority authority){canonical(id);return recoverIds(Collections.singleton(id),authority);}
    public boolean recoverAll(Authority authority){
        try{
            requireDirectory();Set<String> ids=new LinkedHashSet<>();
            try(DirectoryStream<Path> files=Files.newDirectoryStream(directory)){
                for(Path file:files){String name=file.getFileName().toString();String suffix=name.endsWith(".wav.part.delete-pending")?".wav.part.delete-pending":name.endsWith(".wav.delete-pending")?".wav.delete-pending":null;if(suffix==null)continue;String id=name.substring(0,name.length()-suffix.length());canonical(id);ids.add(id);}
            }
            return recoverIds(ids,authority);
        }catch(IOException|RuntimeException failure){throw new IllegalStateException("Cannot inspect Note deletion staging; files preserved",failure);}
    }
    private boolean recoverIds(Collection<String> ids,Authority authority){
        Objects.requireNonNull(authority);Map<String,Presence> known=new LinkedHashMap<>();
        try{
            requireDirectory();
            // Validate the entire recovery set before touching any original or pending file.
            for(String id:ids){Presence presence=authority.presence(id);if(presence==null||presence==Presence.UNKNOWN)throw new IOException("Note metadata authority is unknown");known.put(id,presence);for(Path[] pair:pairs(id)){boolean original=regularOrAbsent(pair[0]),pending=regularOrAbsent(pair[1]);if(original&&(pending||presence==Presence.ABSENT))throw new IOException("Original Note file conflicts with deletion authority");}}
            for(Map.Entry<String,Presence> entry:known.entrySet())for(Path[] pair:pairs(entry.getKey()))if(regularOrAbsent(pair[1])){
                if(regularOrAbsent(pair[0]))throw new IOException("Note recovery conflict");
                Presence current=authority.presence(entry.getKey());if(current!=entry.getValue())throw new IOException("Note metadata authority changed");
                if(current==Presence.PRESENT)operations.move(pair[1],pair[0]);else operations.delete(pair[1]);operations.syncDirectory(directory);
            }
            return true;
        }catch(IOException|RuntimeException failure){throw new IllegalStateException("Note deletion recovery incomplete; remaining files preserved",failure);}
    }
    private List<Path[]> pairs(String id){List<Path[]> result=new ArrayList<>();for(String suffix:new String[]{".wav",".wav.part"}){Path original=directory.resolve(id+suffix);result.add(new Path[]{original,directory.resolve(id+suffix+".delete-pending")});}return result;}
    private void requireDirectory()throws IOException{BasicFileAttributes attributes=Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!attributes.isDirectory()||attributes.isSymbolicLink())throw new IOException("Notes parent is not a regular directory");}
    private static boolean regularOrAbsent(Path file)throws IOException{try{BasicFileAttributes attributes=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!attributes.isRegularFile()||attributes.isSymbolicLink())throw new IOException("Note file is not regular");return true;}catch(NoSuchFileException absent){return false;}}
    private static void canonical(String id){if(id==null||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException("Canonical Note UUID required");}
}

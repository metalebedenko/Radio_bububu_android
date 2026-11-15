package ru.bububu.radio;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import ru.bububu.radio.core.*;

/** Runs once before Activity/Service prefs getters, model controllers and normal recovery. */
public final class SourceBootstrap45 extends Application {
    private SourceDefaults45.Result result;
    @Override public void onCreate(){super.onCreate();try{ru.bububu.radio.sources.SourcePronunciation48.install(getAssets().open(ru.bububu.radio.sources.SourcePronunciation48.ASSET));}catch(IOException unavailable){ru.bububu.radio.sources.SourcePronunciation48.unavailable();}android.util.Log.i("BububuSpeechData",ru.bububu.radio.sources.SourcePronunciation48.status());result=SourceDefaults45.run(new SeedIO(this));}
    static boolean blocked(Context context){Context app=context.getApplicationContext();return app instanceof SourceBootstrap45&&((SourceBootstrap45)app).result!=null&&((SourceBootstrap45)app).result.blockStartup;}
    private static final class SeedIO implements SourceDefaults45.IO {
        final Context context;final File files,noBackup,notes,prefs;final StartupSourceFacts45 probe;
        SeedIO(Context context){this.context=context;files=context.getFilesDir();noBackup=context.getNoBackupFilesDir();notes=new File(noBackup,"notes");prefs=new File(context.getDataDir(),"shared_prefs");probe=new StartupSourceFacts45(files,noBackup,prefs);}
        public String applicationId(){return context.getPackageName();}
        public byte[][] readReceipts()throws IOException{return read(StartupSourceFacts45.RECEIPT,65536);}
        public byte[][] readBooks()throws IOException{return read(NoteMetadataReader.BASE,NoteBook.MAX_METADATA_BYTES);}
        public StartupSourceFacts45.Snapshot facts(byte[] owned,boolean receipt){return probe.capture(owned,receipt);}
        public void ensureEmptyNotesDirectory()throws IOException{
            try{Files.createDirectory(notes.toPath());sync(noBackup);}catch(FileAlreadyExistsException exists){requireDirectory(notes);}
            File[] children=notes.listFiles();if(children==null||children.length!=0)throw new IOException("Fresh directory changed");
        }
        public void requirePeak(long bytes)throws IOException{try{new SourceDiskBudget(notes,new File(noBackup,"source-cas"),new File(noBackup,"source-network"),files).require(bytes);}catch(RuntimeException unavailable){throw new IOException("Seed capacity unavailable",unavailable);}}
        public void writeReceipt(byte[] bytes)throws IOException{write(new File(notes,StartupSourceFacts45.RECEIPT),bytes);}
        public void writeBook(byte[] bytes)throws IOException{write(new File(notes,NoteMetadataReader.BASE),bytes);}
        private byte[][] read(String base,int cap)throws IOException{
            requireAncestor(notes);byte[][] values=new byte[3][];String[] suffix={"",".bak",".new"};for(int i=0;i<3;i++){
                File f=new File(notes,base+suffix[i]);BasicFileAttributes before;try{before=Files.readAttributes(f.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException absent){continue;}
                if(!before.isRegularFile()||before.size()>cap||!f.canRead())throw new IOException("Seed candidate type/access/size");byte[] data=Files.readAllBytes(f.toPath());BasicFileAttributes after=Files.readAttributes(f.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(data.length!=before.size()||data.length>cap||!Objects.equals(before.fileKey(),after.fileKey())||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Arrays.equals(data,Files.readAllBytes(f.toPath())))throw new IOException("Seed candidate changed during read");values[i]=data;
            }requireAncestor(notes);return values;
        }
        private static void requireAncestor(File path)throws IOException{try{requireDirectory(path);}catch(NoSuchFileException absent){if(path.getParentFile()==null)throw new IOException("Missing seed root");requireAncestor(path.getParentFile());}}
        private static void requireDirectory(File path)throws IOException{BasicFileAttributes a=Files.readAttributes(path.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!a.isDirectory()||!path.canRead()||!path.canExecute())throw new IOException("Seed directory type/access");}
        private void write(File file,byte[] bytes)throws IOException{
            requireDirectory(notes);AtomicFile atomic=new AtomicFile(file);FileOutputStream output=null;
            try{output=atomic.startWrite();output.write(bytes);output.getFD().sync();atomic.finishWrite(output);output=null;sync(notes);}catch(Exception failure){if(output!=null)atomic.failWrite(output);throw new IOException("Seed atomic publication unconfirmed",failure);}
        }
        private static void sync(File directory)throws IOException{
            FileDescriptor descriptor=null;try{int flags=OsConstants.O_RDONLY;if(Build.VERSION.SDK_INT>=27)flags|=OsConstants.O_CLOEXEC;descriptor=Os.open(directory.getAbsolutePath(),flags,0);if(!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode))throw new IOException("Seed directory changed type");Os.fsync(descriptor);}catch(Exception failure){throw new IOException("Seed directory sync",failure);}finally{if(descriptor!=null)try{Os.close(descriptor);}catch(Exception failure){throw new IOException("Seed directory close",failure);}}
        }
    }
}

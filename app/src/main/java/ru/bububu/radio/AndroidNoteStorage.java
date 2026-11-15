package ru.bububu.radio;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import ru.bububu.radio.core.NoteBook;
import ru.bububu.radio.core.NoteMetadataReader;
import ru.bububu.radio.core.NoteOriginalRecovery;
import ru.bububu.radio.core.NoteFileDeletion;

/** Private durable originals + atomic metadata. Called only on the notes owner thread. */
final class AndroidNoteStorage implements NoteBook.MigrationStorage {
    final File directory;
    private final AtomicFile metadata;
    private final File playerFiles;
    private final ru.bububu.radio.core.MigrationCapsuleStore.CapacityQuery migrationCapacity;
    AndroidNoteStorage(Context context) {
        this(context,File::getUsableSpace);
    }
    // Package-only boundary-test seam. Normal application construction always uses the real query.
    AndroidNoteStorage(Context context,ru.bububu.radio.core.MigrationCapsuleStore.CapacityQuery migrationCapacity) {
        this.migrationCapacity=java.util.Objects.requireNonNull(migrationCapacity,"migrationCapacity");
        playerFiles = context.getFilesDir();
        directory = new File(context.getNoBackupFilesDir(), "notes");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Notes directory unavailable");
        metadata = new AtomicFile(new File(directory, NoteMetadataReader.BASE));
    }
    private static final class RecoverySnapshot {
        byte[] authority; final byte[][] candidates = new byte[3][];
    }
    @Override public byte[] load() { return readSnapshot().authority; }
    private RecoverySnapshot readSnapshot() {
        RecoverySnapshot snapshot = new RecoverySnapshot();
        try {
            snapshot.authority = NoteMetadataReader.load(new NoteMetadataReader.Files() {
                @Override public void requireAccessibleDirectory() throws IOException {
                    FileDescriptor descriptor = null;
                    try {
                        if (!OsConstants.S_ISDIR(Os.lstat(directory.getAbsolutePath()).st_mode)
                                || !Os.access(directory.getAbsolutePath(), OsConstants.R_OK | OsConstants.X_OK))
                            throw new IOException("Notes parent is not an accessible directory");
                        int flags = OsConstants.O_RDONLY;
                        if (Build.VERSION.SDK_INT >= 27) flags |= OsConstants.O_CLOEXEC;
                        descriptor = Os.open(directory.getAbsolutePath(), flags, 0);
                        if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode))
                            throw new IOException("Notes parent has changed type");
                    } catch (ErrnoException failure) {
                        throw new IOException("Cannot inspect notes parent", failure);
                    } finally {
                        if (descriptor != null) try { Os.close(descriptor); }
                        catch (ErrnoException failure) { throw new IOException("Cannot close notes parent inspection", failure); }
                    }
                }
                @Override public NoteMetadataReader.Type inspect(String name) throws IOException {
                    try {
                        int mode = Os.lstat(new File(directory, name).getAbsolutePath()).st_mode;
                        return OsConstants.S_ISREG(mode) ? NoteMetadataReader.Type.REGULAR : NoteMetadataReader.Type.OTHER;
                    } catch (ErrnoException failure) {
                        if (failure.errno == OsConstants.ENOENT) return NoteMetadataReader.Type.ABSENT;
                        throw new IOException("Cannot inspect notes metadata", failure);
                    }
                }
                @Override public byte[] read(String name) throws IOException {
                    try (FileInputStream input = new FileInputStream(new File(directory, name))) {
                        try {
                            if (!OsConstants.S_ISREG(Os.fstat(input.getFD()).st_mode))
                                throw new IOException("Notes metadata has changed type");
                        } catch (ErrnoException failure) { throw new IOException("Cannot inspect opened metadata", failure); }
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096]; int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (bytes.size() + count > NoteBook.MAX_METADATA_BYTES) throw new IOException("Notes metadata too large");
                            bytes.write(buffer, 0, count);
                        }
                        byte[] value = bytes.toByteArray();
                        int slot = name.equals(NoteMetadataReader.BASE) ? 0 : name.equals(NoteMetadataReader.BACKUP) ? 1 : 2;
                        snapshot.candidates[slot] = value; return value;
                    }
                }
            });
            return snapshot;
        } catch (Exception failure) { throw new IllegalStateException("Cannot read notes; preserved existing files", failure); }
    }
    /** Every recoverable metadata image must prove that this disposable CAS has no protected body. */
    boolean canDiscardSourceBody(String recordKey){
        RecoverySnapshot snapshot=readSnapshot();
        if(snapshot.authority==null)throw new IllegalStateException("Metadata authority absent during cleanup");
        for(byte[] candidate:snapshot.candidates)if(candidate!=null){
            NoteBook image=new NoteBook(new NoteBook.Storage(){public byte[] load(){return candidate;}public void save(byte[] value){throw new IllegalStateException("Read-only recovery scan");}});
            ru.bububu.radio.sources.SourceVersionRecord version=image.sourceVersion(recordKey);
            if(version!=null&&version.spokenText!=null)return false;
        }
        return true;
    }
    @Override public ru.bububu.radio.core.MigrationStamp prepareMigration(byte[] expectedV1, int targetBytes) {
        RecoverySnapshot current = readSnapshot();
        if (!java.util.Arrays.equals(current.authority, expectedV1)) throw new IllegalStateException("Migration baseline changed");
        // All candidates were read without AtomicFile recovery side effects before capsule GC.
        new SourceDiskBudget(directory, new File(directory.getParentFile(), "source-cas"),
                new File(directory.getParentFile(), "source-network"), playerFiles)
                .require((expectedV1 == null ? 0 : expectedV1.length) + 16 * 1024L + targetBytes);
        return new ru.bububu.radio.core.MigrationCapsuleStore(directory,migrationCapacity).prepare(expectedV1, targetBytes, current.candidates);
    }
    @Override public ru.bububu.radio.core.UpgradeRollbackRef prepareSchema3Upgrade(byte[] expectedSchema2,int targetBytes) {
        RecoverySnapshot current=readSnapshot();
        if(!java.util.Arrays.equals(current.authority,expectedSchema2))throw new IllegalStateException("Upgrade baseline changed");
        ru.bububu.radio.core.MigrationCapsuleStore capsules=new ru.bububu.radio.core.MigrationCapsuleStore(directory,migrationCapacity);
        new SourceDiskBudget(directory,new File(directory.getParentFile(),"source-cas"),new File(directory.getParentFile(),"source-network"),playerFiles)
                .require(Math.addExact(capsules.upgradeAdditionalBytes(expectedSchema2,targetBytes),ru.bububu.radio.sources.SourceLimits.MAX_RADIO_BYTES));
        return capsules.prepareSchema3Upgrade(expectedSchema2,targetBytes,current.candidates);
    }
    @Override public void verifyUpgradeRollback(ru.bububu.radio.core.UpgradeRollbackRef ref){new ru.bububu.radio.core.MigrationCapsuleStore(directory).verifyUpgradeRollback(ref);}
    @Override public ru.bububu.radio.core.Format4BaselineRef prepareFormat4Upgrade(byte[] baseline,int targetBytes){
        RecoverySnapshot current=readSnapshot();if(!java.util.Arrays.equals(current.authority,baseline))throw new IllegalStateException("Format4 baseline changed");
        new SourceDiskBudget(directory,new File(directory.getParentFile(),"source-cas"),new File(directory.getParentFile(),"source-network"),playerFiles)
                .require(Math.addExact((baseline==null?0:baseline.length)+16*1024L+targetBytes,ru.bububu.radio.sources.SourceLimits.MAX_RADIO_BYTES));
        return new ru.bububu.radio.core.MigrationCapsuleStore(directory,migrationCapacity).prepareFormat4(baseline,targetBytes,current.candidates);
    }
    @Override public void verifyFormat4Baseline(ru.bububu.radio.core.Format4BaselineRef ref){new ru.bububu.radio.core.MigrationCapsuleStore(directory).verifyFormat4(ref);}
    @Override public void verifyUpgradeRollback(ru.bububu.radio.core.UpgradeRollbackRef ref,ru.bububu.radio.core.MigrationStamp origin){new ru.bububu.radio.core.MigrationCapsuleStore(directory).verifyUpgradeRollback(ref,origin);}
    @Override public void verifyMigrationStamp(ru.bububu.radio.core.MigrationStamp stamp) {
        new ru.bububu.radio.core.MigrationCapsuleStore(directory).verify(stamp);
    }
    @Override public void save(byte[] bytes) {
        FileOutputStream output = null;
        try {
            if (bytes.length > 8 * 1024 * 1024) throw new IllegalStateException("Notes metadata limit");
            new SourceDiskBudget(directory, new File(directory.getParentFile(), "source-cas"),
                    new File(directory.getParentFile(), "source-network"), playerFiles).requireMetadataPeak(bytes.length);
            output = metadata.startWrite(); output.write(bytes); metadata.finishWrite(output); output = null;
            if (!java.util.Arrays.equals(readSnapshot().authority, bytes)) throw new IllegalStateException("Metadata publication not confirmed");
        } catch (Exception failure) {
            if (output != null) metadata.failWrite(output);
            throw new IllegalStateException("Cannot save notes", failure);
        }
    }
    void stageDeletion(String id){fileDeletion().stage(id);}
    boolean recoverDeletion(String id,NoteBook book){return fileDeletion().recover(id,deletionAuthority(book));}
    void recoverDeletions(NoteBook book){book.requireCurrentAuthority();fileDeletion().recoverAll(deletionAuthority(book));}
    private NoteFileDeletion fileDeletion(){return new NoteFileDeletion(directory.toPath(),new NoteFileDeletion.Operations(){
        public void move(java.nio.file.Path from,java.nio.file.Path to)throws IOException{java.nio.file.Files.move(from,to);}
        public void delete(java.nio.file.Path file)throws IOException{java.nio.file.Files.delete(file);}
        public void syncDirectory(java.nio.file.Path parent)throws IOException{
            FileDescriptor descriptor=null;
            try{int flags=OsConstants.O_RDONLY;if(Build.VERSION.SDK_INT>=27)flags|=OsConstants.O_CLOEXEC;descriptor=Os.open(parent.toString(),flags,0);if(!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode))throw new IOException("Notes parent changed type");Os.fsync(descriptor);}
            catch(ErrnoException failure){throw new IOException("Note deletion directory sync failed",failure);}
            finally{if(descriptor!=null)try{Os.close(descriptor);}catch(ErrnoException failure){throw new IOException("Note deletion directory close failed",failure);}}
        }
    });}
    private NoteFileDeletion.Authority deletionAuthority(NoteBook book){return id->{book.requireCurrentAuthority();if(load()==null)throw new IllegalStateException("Missing metadata cannot authorize Note file deletion");return book.find(id)==null?NoteFileDeletion.Presence.ABSENT:NoteFileDeletion.Presence.PRESENT;};}
    void recover(NoteBook book) {
        recoverDeletions(book);
        NoteOriginalRecovery.recover(directory, book);
    }
}

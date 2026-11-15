package ru.bububu.radio.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import ru.bububu.radio.sources.SourceLimits;

/** Sealed rollback evidence. Only the stamp in the primary Book makes a capsule committed. */
public final class MigrationCapsuleStore {
    /** Only the pre-publication capacity estimate for the already checked notes parent. */
    @FunctionalInterface public interface CapacityQuery { long usableBytes(File checkedParent) throws IOException; }
    private static final int MAGIC=0x424d4947, LIMIT=NoteBook.MAX_METADATA_BYTES+16*1024;
    private final Path parent, directory;
    private final CapacityQuery capacity;
    public MigrationCapsuleStore(File notesDirectory) {
        this(notesDirectory,File::getUsableSpace);
    }
    public MigrationCapsuleStore(File notesDirectory,CapacityQuery capacity) {
        parent=notesDirectory.toPath(); directory=parent.resolve("notes-migration");
        this.capacity=java.util.Objects.requireNonNull(capacity,"capacity");
    }
    /** Null candidates mean proven ENOENT, supplied by the platform's inert metadata reader. */
    public MigrationStamp prepare(byte[] baseline,int targetBytes,byte[][] recoveryCandidates) {return prepareInternal(baseline,targetBytes,recoveryCandidates,1);}
    public UpgradeRollbackRef prepareSchema3Upgrade(byte[] baseline,int targetBytes,byte[][] recoveryCandidates){return new UpgradeRollbackRef(2,2,prepareInternal(baseline,targetBytes,recoveryCandidates,2));}
    public Format4BaselineRef prepareFormat4(byte[] baseline,int targetBytes,byte[][] recoveryCandidates){return new Format4BaselineRef(NoteBook.legacySchema(baseline),prepareInternal(baseline,targetBytes,recoveryCandidates,3));}
    public static long format4CapsuleBytes(byte[] baseline){try{String id="00000000-0000-0000-0000-000000000000";return seal(id,baseline==null?id:null,baseline,3).length;}catch(IOException impossible){throw new IllegalStateException(impossible);}}
    private MigrationStamp prepareInternal(byte[] baseline,int targetBytes,byte[][] recoveryCandidates,int codec) {
        if(targetBytes<0||targetBytes>NoteBook.MAX_METADATA_BYTES) throw new IllegalStateException("Migration target exceeds 8 MiB");
        if(codec==1)NoteBook.validateSchema1(baseline);else if(codec==2)NoteBook.validateSchema2(baseline);else NoteBook.legacySchema(baseline);
        if(recoveryCandidates==null||recoveryCandidates.length!=3) throw new IllegalStateException("All three recovery paths must be readable before capsule GC");
        byte[] authoritative=recoveryCandidates[1]!=null?recoveryCandidates[1]:recoveryCandidates[0];
        if(!Arrays.equals(authoritative,baseline)||(authoritative==null&&recoveryCandidates[2]!=null)) throw new IllegalStateException("Capsule baseline is not the proven AtomicFile authority");
        try {
            requireDirectory(parent);
            long extra=codec==2?upgradeAdditionalBytes(baseline,targetBytes):(baseline==null?0:baseline.length)+16*1024L+targetBytes;
            long free;
            try { free=capacity.usableBytes(parent.toFile()); }
            catch(RuntimeException unavailable) { throw new IOException("Migration capacity unavailable",unavailable); }
            if(free<SourceLimits.MIN_FREE_BYTES||free<extra) throw new IOException("Insufficient migration peak space");
            Set<String> referenced=new HashSet<>();
            for(byte[] candidate:recoveryCandidates) if(candidate!=null) {
                MigrationStamp committed=NoteBook.inspectMigrationStamp(candidate);
                if(committed!=null) referenced.add(committed.capsuleId);
                UpgradeRollbackRef upgrade=NoteBook.inspectUpgradeRollback(candidate);if(upgrade!=null)referenced.add(upgrade.stamp.capsuleId);
                Format4BaselineRef format4=NoteBook.inspectFormat4Baseline(candidate);if(format4!=null)referenced.add(format4.stamp.capsuleId);
            }
            try { requireDirectory(directory); }
            catch(java.nio.file.NoSuchFileException missing) { Files.createDirectory(directory); requireDirectory(directory); }
            List<Descriptor> sealed=new ArrayList<>();
            try(java.nio.file.DirectoryStream<Path> entries=Files.newDirectoryStream(directory)) {
                for(Path path:entries) {
                    String name=path.getFileName().toString();
                    if(!name.matches("[0-9a-f-]{36}\\.bin")) throw new IOException("Unknown migration file retained");
                    UUID.fromString(name.substring(0,36));
                    Descriptor descriptor=read(path);
                    if(!descriptor.stamp.capsuleId.equals(name.substring(0,36))) throw new IOException("Capsule filename identity");
                    sealed.add(descriptor);
                }
            }
            // An absence epoch is fresh each time: a stale marker never proves today's absence.
            if(baseline!=null) for(Descriptor capsule:sealed)
                if(capsule.codec==codec&&capsule.stamp.matches(baseline)) { verify(capsule.stamp,codec); return capsule.stamp; }
            while(sealed.size()>=(codec==3?3:2)) {
                Descriptor obsolete=null;for(Descriptor candidate:sealed)if(!referenced.contains(candidate.stamp.capsuleId)){obsolete=candidate;break;}
                if(obsolete==null)throw new IOException("All capsules are referenced by recovery layouts");
                sealed.remove(obsolete);
                Files.delete(directory.resolve(obsolete.stamp.capsuleId+".bin"));
            }
            String attempt=UUID.randomUUID().toString(), epoch=baseline==null?UUID.randomUUID().toString():null;
            byte[] bytes=seal(attempt,epoch,baseline,codec);
            Path temp=directory.resolve(attempt+".tmp"), finalPath=directory.resolve(attempt+".bin");
            boolean created=false;
            try {
                Files.createFile(temp); created=true;
                try(FileOutputStream output=new FileOutputStream(temp.toFile())) { output.write(bytes); output.getFD().sync(); }
                read(temp); // Full v1 validation precedes publication.
                Files.move(temp,finalPath,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                return read(finalPath).stamp;
            } catch(IOException|RuntimeException failure) {
                if(created) try { Files.deleteIfExists(temp); } catch(IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        } catch(IOException|IllegalArgumentException failure) { throw new IllegalStateException("Migration capsule preparation failed; original metadata retained",failure); }
    }
    public void verify(MigrationStamp expected) {verify(expected,1);}
    public void verifyFormat4(Format4BaselineRef expected){
        if(expected==null)throw new IllegalStateException("Missing format4 baseline");verify(expected.stamp,3);
        try{Descriptor d=read(directory.resolve(expected.stamp.capsuleId+".bin"));if(d.baselineSchema!=expected.baselineSchema)throw new IOException("Format4 baseline schema mismatch");}catch(IOException failure){throw new IllegalStateException("Format4 baseline unavailable",failure);}
    }
    public void verifyUpgradeRollback(UpgradeRollbackRef ref){verifyUpgradeRollback(ref,null);}
    public void verifyUpgradeRollback(UpgradeRollbackRef ref,MigrationStamp expectedOrigin){
        if(ref==null)throw new IllegalStateException("Missing upgrade rollback reference");verify(ref.stamp,2);
        if(expectedOrigin!=null)try{Descriptor descriptor=read(directory.resolve(ref.stamp.capsuleId+".bin"));if(descriptor.origin==null||!same(expectedOrigin,descriptor.origin))throw new IOException("Upgrade origin mismatch");}catch(IOException invalid){throw new IllegalStateException("Rollback capsule origin unavailable",invalid);}
    }
    /** Read-only inventory estimate; an exact pending capsule is already included in managed bytes. */
    public long upgradeAdditionalBytes(byte[] baseline,int targetBytes){
        NoteBook.validateSchema2(baseline);if(targetBytes<0||targetBytes>NoteBook.MAX_METADATA_BYTES)throw new IllegalArgumentException("Upgrade target limit");
        try{requireDirectory(parent);try{requireDirectory(directory);}catch(java.nio.file.NoSuchFileException missing){return baseline.length+16*1024L+targetBytes;}
            boolean matching=false;try(java.nio.file.DirectoryStream<Path> entries=Files.newDirectoryStream(directory)){for(Path path:entries){String name=path.getFileName().toString();if(!name.matches("[0-9a-f-]{36}\\.bin"))throw new IOException("Unknown migration file retained");UUID.fromString(name.substring(0,36));Descriptor capsule=read(path);if(!capsule.stamp.capsuleId.equals(name.substring(0,36)))throw new IOException("Capsule identity");if(capsule.codec==2&&capsule.stamp.matches(baseline))matching=true;}}
            return targetBytes+(matching?0:baseline.length+16*1024L);
        }catch(IOException|IllegalArgumentException invalid){throw new IllegalStateException("Upgrade inventory unavailable",invalid);}
    }
    private void verify(MigrationStamp expected,int codec) {
        if(expected==null) throw new IllegalStateException("Missing migration stamp");
        try {
            requireDirectory(parent); requireDirectory(directory);
            Descriptor descriptor=read(directory.resolve(expected.capsuleId+".bin"));
            if(descriptor.codec!=codec)throw new IOException("Capsule codec mismatch");
            MigrationStamp actual=descriptor.stamp;
            if(!same(expected,actual)) throw new IOException("Capsule stamp mismatch");
        } catch(IOException failure) { throw new IllegalStateException("Rollback capsule unavailable",failure); }
    }
    private static boolean same(MigrationStamp a,MigrationStamp b) {
        return a.attemptId.equals(b.attemptId)&&java.util.Objects.equals(a.baselineHash,b.baselineHash)
                &&java.util.Objects.equals(a.absenceEpoch,b.absenceEpoch)&&a.baselineLength==b.baselineLength
                &&a.capsuleLength==b.capsuleLength&&a.capsuleHash.equals(b.capsuleHash);
    }
    private static void requireDirectory(Path path) throws IOException {
        if(!Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isDirectory()) throw new IOException("Migration directory type");
    }
    private static byte[] seal(String attempt,String epoch,byte[] baseline,int codec) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(MAGIC);out.writeInt(codec);out.writeUTF(attempt);
        if(codec==3){out.writeInt(4);out.writeInt(NoteBook.legacySchema(baseline));out.writeBoolean(baseline!=null);if(baseline==null)out.writeUTF(epoch);else{out.writeUTF(SourceLimits.hash(baseline));out.writeInt(baseline.length);out.write(baseline);}}
        else if(codec==2){out.writeInt(2);out.writeUTF(SourceLimits.hash(baseline));out.writeInt(baseline.length);out.write(baseline);}
        else {out.writeBoolean(baseline!=null);
        if(baseline==null) out.writeUTF(epoch); else {out.writeUTF(SourceLimits.hash(baseline));out.writeInt(baseline.length);out.write(baseline);}}
        out.flush();CRC32 crc=new CRC32();crc.update(bytes.toByteArray());out.writeLong(crc.getValue());out.flush();return bytes.toByteArray();
    }
    private static Descriptor read(Path path) throws IOException {
        BasicFileAttributes attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attrs.isRegularFile()||attrs.size()>LIMIT||attrs.size()<16) throw new IOException("Capsule type/size");
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try(java.io.InputStream input=Files.newInputStream(path)) {
            byte[] chunk=new byte[8192];int read;
            while((read=input.read(chunk))!=-1){if(read>LIMIT-buffer.size())throw new IOException("Capsule grew beyond limit");buffer.write(chunk,0,read);}
        }
        byte[] bytes=buffer.toByteArray();if(bytes.length!=attrs.size()||bytes.length>LIMIT) throw new IOException("Capsule changed while reading");
        CRC32 crc=new CRC32();crc.update(bytes,0,bytes.length-8);
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=MAGIC)throw new IOException("Capsule magic");int codec=in.readInt();if(codec!=1&&codec!=2&&codec!=3)throw new IOException("Capsule schema");
        String attempt=ascii(in,36),epoch=null,hash=null;int length=-1; byte[] baseline=null;
        int baselineSchema=codec==2?2:1;
        boolean present;if(codec==3){if(in.readInt()!=4)throw new IOException("Capsule target schema");baselineSchema=in.readInt();if(baselineSchema<1||baselineSchema>3)throw new IOException("Capsule baseline schema");present=in.readBoolean();if(!present&&baselineSchema!=1)throw new IOException("Absent non-schema1 baseline");}else if(codec==2){if(in.readInt()!=2)throw new IOException("Capsule baseline schema");present=true;}else present=in.readBoolean();
        if(present) {
            hash=ascii(in,64);length=in.readInt();
            if(length<0||length>NoteBook.MAX_METADATA_BYTES||length>in.available()-8) throw new IOException("Capsule payload length");
            baseline=new byte[length];in.readFully(baseline);
            if(!hash.equals(SourceLimits.hash(baseline))) throw new IOException("Capsule v1 hash");
            try {if(codec==1)NoteBook.validateSchema1(baseline);else if(codec==2)NoteBook.validateSchema2(baseline);else if(NoteBook.legacySchema(baseline)!=baselineSchema)throw new IllegalArgumentException("Baseline schema");} catch(RuntimeException invalid) {throw new IOException("Capsule v1 decode",invalid);}
        } else epoch=ascii(in,36);
        if(in.available()!=8||in.readLong()!=crc.getValue()) throw new IOException("Capsule CRC/EOF");
        try {return new Descriptor(new MigrationStamp(attempt,hash,length,epoch,attempt,bytes.length,SourceLimits.hash(bytes)),codec,codec==2?NoteBook.inspectMigrationStamp(baseline):null,baselineSchema);}
        catch(RuntimeException invalid) {throw new IOException("Capsule descriptor",invalid);}
    }
    private static String ascii(DataInputStream in,int maximum)throws IOException {
        int length=in.readUnsignedShort();if(length>maximum)throw new IOException("Capsule header string limit");
        byte[] value=new byte[length];in.readFully(value);for(byte b:value)if(b<0||b==0)throw new IOException("Capsule header encoding");
        return new String(value,java.nio.charset.StandardCharsets.US_ASCII);
    }
    private static final class Descriptor {final MigrationStamp stamp;final int codec,baselineSchema;final MigrationStamp origin;Descriptor(MigrationStamp stamp,int codec,MigrationStamp origin,int baselineSchema){this.stamp=stamp;this.codec=codec;this.origin=origin;this.baselineSchema=baselineSchema;}}
}

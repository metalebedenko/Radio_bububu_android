package ru.bububu.radio.core;

import java.io.*;
import java.util.*;
import java.util.zip.CRC32;
import ru.bububu.radio.sources.*;

/** One bounded, startup-only seed operation. Existing choices/data never become a seed target. */
public final class SourceDefaults45 {
    public enum Status { SEEDED, RECOVERED, ALREADY_DECIDED, LEGACY_PRESERVE, RECOVERY_REQUIRED }
    public static final class Result {
        public final Status status;public final boolean blockStartup;public final int bookWrites,receiptWrites;
        Result(Status s,boolean block,int b,int r){status=s;blockStartup=block;bookWrites=b;receiptWrites=r;}
    }
    public interface IO {
        String applicationId();
        byte[][] readReceipts()throws IOException;
        byte[][] readBooks()throws IOException;
        StartupSourceFacts45.Snapshot facts(byte[] ownedBookTemporary,boolean ownedReceipt);
        void ensureEmptyNotesDirectory()throws IOException;
        void requirePeak(long additionalBytes)throws IOException;
        void writeReceipt(byte[] bytes)throws IOException;
        void writeBook(byte[] bytes)throws IOException;
    }
    private static final int MAGIC=0x42534545,LIMIT=65536;
    private SourceDefaults45(){}
    public static Result run(IO io){
        int writes=0,receiptsWritten=0;boolean hasReceipt=true,readableBook=false;
        try{
            byte[][] receiptImages=images(io.readReceipts()),books=images(io.readBooks());hasReceipt=any(receiptImages);Receipt[] receipts=new Receipt[3];Receipt common=null;
            for(int i=0;i<3;i++)if(receiptImages[i]!=null){Receipt r=Receipt.decode(receiptImages[i]);if(!io.applicationId().equals(r.applicationId))throw new IOException("Receipt app identity");validateMarker(r.marker());if(common!=null&&!common.sameAttempt(r))throw new IOException("Conflicting receipt candidates");common=r;receipts[i]=r;}
            for(byte[] book:books)if(book!=null)NoteBook.validateImage(book);byte[] bookAuthority=authority(books);readableBook=bookAuthority!=null;
            Seed45Marker committed=bookAuthority==null?null:NoteBook.inspectSeed45(bookAuthority);Receipt receipt=receipts[1]!=null?receipts[1]:receipts[0];
            if(committed!=null){
                validateMarker(committed);if(common!=null&&!sameMarker(committed,common.marker()))throw new IOException("Book/receipt attempt conflict");
                if(receipt!=null&&!receipt.applied){io.requirePeak(receipt.encode().length*2L);io.writeReceipt(receipt.applied().encode());receiptsWritten++;verifyReceipt(io,receipt.applied());}
                return new Result(Status.ALREADY_DECIDED,false,0,receiptsWritten);
            }
            if(common!=null){
                if(receipt==null)throw new IOException("Uncommitted lone receipt temporary");
                for(Receipt candidate:receipts)if(candidate!=null&&candidate.applied)throw new IOException("Applied decision cannot restore a missing Book");
                if(bookAuthority!=null||books[1]!=null||books[0]!=null||books[2]!=null&&!Arrays.equals(books[2],receipt.target))throw new IOException("Conflicting user Book");
                if(!receipt.definitions.equals(SourceProfiles45.definitionSetHash())||!Arrays.equals(receipt.target,target(receipt.attempt))&&!Arrays.equals(receipt.target,target(receipt.attempt,true)))throw new IOException("Prepared policy/config drift");
                StartupSourceFacts45.Snapshot facts=io.facts(receipt.target,true);if(facts.state!=StartupSourceFacts45.State.PROVEN_FRESH||!Arrays.equals(facts.manifest(),receipt.proof))throw new IOException("Prepared prior-use proof changed");
                io.requirePeak(receipt.encode().length*2L+receipt.target.length*2L);
                if(!Arrays.deepEquals(receiptImages,images(io.readReceipts()))||!Arrays.deepEquals(books,images(io.readBooks())))throw new IOException("Layout changed before recovery");
                io.writeBook(receipt.target);writes++;verifyBook(io,receipt);io.writeReceipt(receipt.applied().encode());receiptsWritten++;verifyReceipt(io,receipt.applied());return new Result(Status.RECOVERED,false,writes,receiptsWritten);
            }
            StartupSourceFacts45.Snapshot initial=io.facts(null,false);
            if(initial.state==StartupSourceFacts45.State.LEGACY_PRESERVE)return new Result(Status.LEGACY_PRESERVE,false,0,0);
            if(initial.state!=StartupSourceFacts45.State.PROVEN_FRESH)throw new IOException("Freshness unproven");
            // Empty-directory creation carries no settings/data. Only this fresh branch may do it.
            io.ensureEmptyNotesDirectory();StartupSourceFacts45.Snapshot fresh=io.facts(null,false);
            if(fresh.state!=StartupSourceFacts45.State.PROVEN_FRESH||any(images(io.readBooks()))||any(images(io.readReceipts())))throw new IOException("Fresh inputs changed");
            Receipt prepared=new Receipt(io.applicationId(),UUID.randomUUID().toString(),SourceProfiles45.definitionSetHash(),fresh.manifest(),null,false);byte[] encoded=prepared.encode();
            io.requirePeak(encoded.length*2L+prepared.target.length*2L);
            StartupSourceFacts45.Snapshot checked=io.facts(null,false);if(checked.state!=StartupSourceFacts45.State.PROVEN_FRESH||!Arrays.equals(checked.manifest(),prepared.proof))throw new IOException("Fresh proof raced");
            hasReceipt=true;receiptsWritten++;io.writeReceipt(encoded);verifyReceipt(io,prepared);
            StartupSourceFacts45.Snapshot beforeBook=io.facts(prepared.target,true);if(beforeBook.state!=StartupSourceFacts45.State.PROVEN_FRESH||!Arrays.equals(beforeBook.manifest(),prepared.proof)||any(images(io.readBooks())))throw new IOException("User state appeared after prepare");
            io.writeBook(prepared.target);writes++;verifyBook(io,prepared);io.writeReceipt(prepared.applied().encode());receiptsWritten++;verifyReceipt(io,prepared.applied());return new Result(Status.SEEDED,false,writes,receiptsWritten);
        }catch(Exception preserve){return new Result(Status.RECOVERY_REQUIRED,hasReceipt&&!readableBook,writes,receiptsWritten);}
    }
    private static byte[][] images(byte[][] images)throws IOException{if(images==null||images.length!=3)throw new IOException("Three inert images required");return images;}
    private static boolean any(byte[][] images){for(byte[] b:images)if(b!=null)return true;return false;}
    private static byte[] authority(byte[][] images){return images[1]!=null?images[1]:images[0];}
    private static boolean sameMarker(Seed45Marker a,Seed45Marker b){return a.attemptId.equals(b.attemptId)&&a.policy.equals(b.policy)&&a.definitionSetHash.equals(b.definitionSetHash)&&a.bindings.equals(b.bindings);}
    private static void validateMarker(Seed45Marker marker)throws IOException{
        Map<String,String> bindings=new TreeMap<>();for(SourceProfiles45.Profile p:SourceProfiles45.six())bindings.put(p.id,p.seed().id);Map<String,String> oldBindings=new TreeMap<>();for(SourceProfiles45.Profile p:SourceProfiles45.legacySix())oldBindings.put(p.id,p.seed().id);if(!(marker.bindings.equals(bindings)||marker.bindings.equals(oldBindings))||!Seed45Marker.POLICY.equals(marker.policy))throw new IOException("Six identity bindings");
    }
    private static byte[] target(String attempt){return target(attempt,false);}
    private static byte[] target(String attempt,boolean legacy){List<SourceRecord> sources=new ArrayList<>();Map<String,String> bindings=new TreeMap<>();for(SourceProfiles45.Profile p:legacy?SourceProfiles45.legacySix():SourceProfiles45.six()){SourceRecord s=p.seed();sources.add(s);bindings.put(p.id,s.id);}return NoteBook.fresh45Target(sources,new Seed45Marker(attempt,Seed45Marker.POLICY,SourceProfiles45.definitionSetHash(),bindings));}
    public static boolean legacyStarterMarker(NoteBook book){Seed45Marker marker=book.seed45Marker();if(marker==null)return false;Map<String,String> old=new TreeMap<>();for(SourceProfiles45.Profile p:SourceProfiles45.legacySix())old.put(p.id,p.seed().id);return marker.bindings.equals(old);}
    public static boolean mayUpgradeStarter(NoteBook book,boolean explicitChoice,boolean pending){return !explicitChoice&&legacyStarterMarker(book)&&(book.sources().size()==6||pending);}
    /** Owner-approved default change: append missing news stations; never replace a receipt or user choice/data. */
    public static Set<String> upgradeLegacyStarter(NoteBook book){
        Seed45Marker marker=book.seed45Marker();if(marker==null)return null;Map<String,String> old=new TreeMap<>();Set<String> allowed=new HashSet<>(),chosen=new LinkedHashSet<>();
        for(SourceProfiles45.Profile p:SourceProfiles45.legacySix()){SourceRecord seed=p.seed();old.put(p.id,seed.id);allowed.add(seed.id);SourceRecord current=book.source(seed.id);if(current==null||!current.enabled||!current.catalog||!current.key.equals(seed.key)||book.isSourceRetired(seed.id))return null;}
        if(!marker.bindings.equals(old))return null;for(SourceProfiles45.Profile p:SourceProfiles45.six()){if(book.isSourceRetired(p.seed().id))return null;allowed.add(p.seed().id);chosen.add(p.seed().id);}
        for(SourceRecord source:book.sources())if(!allowed.contains(source.id)||!source.enabled)return null;
        for(SourceProfiles45.Profile p:SourceProfiles45.six())if(book.source(p.seed().id)==null)book.commitSource(p.seed(),Collections.emptyList());return chosen;
    }
    private static void verifyBook(IO io,Receipt expected)throws IOException{byte[] bytes=authority(images(io.readBooks()));if(bytes==null||!Arrays.equals(bytes,expected.target)||!sameMarker(NoteBook.inspectSeed45(bytes),expected.marker()))throw new IOException("Seed publication not confirmed");}
    private static void verifyReceipt(IO io,Receipt expected)throws IOException{byte[] bytes=authority(images(io.readReceipts()));if(bytes==null||!Arrays.equals(bytes,expected.encode()))throw new IOException("Receipt publication not confirmed");}
    private static final class Receipt {
        final String applicationId,attempt,definitions;final byte[] proof,target;final boolean applied;
        Receipt(String app,String attempt,String definitions,byte[] proof,byte[] target,boolean applied){
            SourceLimits.value(app,200,"receipt app id",false);UUID.fromString(attempt);if(!definitions.matches("[0-9a-f]{64}")||proof.length>16384)throw new IllegalArgumentException("Receipt fields");this.applicationId=app;this.attempt=attempt;this.definitions=definitions;this.proof=proof.clone();this.target=target==null?target(attempt):target.clone();this.applied=applied;
        }
        Receipt applied(){return new Receipt(applicationId,attempt,definitions,proof,target,true);}
        Seed45Marker marker(){Seed45Marker marker=NoteBook.inspectSeed45(target);if(marker==null||!marker.attemptId.equals(attempt)||!marker.definitionSetHash.equals(definitions))throw new IllegalArgumentException("Receipt target marker");return marker;}
        boolean sameAttempt(Receipt r){return applicationId.equals(r.applicationId)&&attempt.equals(r.attempt)&&definitions.equals(r.definitions)&&Arrays.equals(proof,r.proof)&&Arrays.equals(target,r.target);}
        byte[] encode()throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(b);out.writeInt(MAGIC);out.writeInt(1);out.writeBoolean(applied);out.writeUTF(applicationId);out.writeUTF(attempt);out.writeUTF(Seed45Marker.POLICY);out.writeUTF(definitions);out.writeInt(proof.length);out.write(proof);out.writeUTF(SourceLimits.hash(proof));out.writeInt(target.length);out.write(target);out.writeUTF(SourceLimits.hash(target));out.flush();CRC32 crc=new CRC32();crc.update(b.toByteArray());out.writeLong(crc.getValue());out.flush();if(b.size()>LIMIT)throw new IOException("Receipt limit");return b.toByteArray();}
        static Receipt decode(byte[] bytes)throws IOException{
            if(bytes.length<16||bytes.length>LIMIT)throw new IOException("Receipt limit");CRC32 crc=new CRC32();crc.update(bytes,0,bytes.length-8);DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=MAGIC||in.readInt()!=1)throw new IOException("Receipt codec");boolean applied=in.readBoolean();String app=ascii(in,200),attempt=ascii(in,36),policy=ascii(in,128),definitions=ascii(in,64);if(!policy.equals(Seed45Marker.POLICY))throw new IOException("Receipt policy");byte[] proof=block(in,16384);if(!SourceLimits.hash(proof).equals(ascii(in,64)))throw new IOException("Proof checksum");byte[] target=block(in,LIMIT);if(!SourceLimits.hash(target).equals(ascii(in,64))||in.available()!=8||in.readLong()!=crc.getValue())throw new IOException("Receipt CRC");try{NoteBook.validateImage(target);Receipt receipt=new Receipt(app,attempt,definitions,proof,target,applied);receipt.marker();if(!Arrays.equals(bytes,receipt.encode()))throw new IOException("Receipt canonical codec");return receipt;}catch(RuntimeException invalid){throw new IOException("Receipt target",invalid);}
        }
        private static byte[] block(DataInputStream in,int cap)throws IOException{int n=in.readInt();if(n<0||n>cap||n>in.available()-8)throw new IOException("Receipt block bound");byte[] value=new byte[n];in.readFully(value);return value;}
        private static String ascii(DataInputStream in,int cap)throws IOException{int n=in.readUnsignedShort();if(n>cap)throw new IOException("Receipt string bound");byte[] bytes=new byte[n];in.readFully(bytes);for(byte b:bytes)if(b<33||b>126)throw new IOException("Receipt ASCII identity");return new String(bytes,java.nio.charset.StandardCharsets.US_ASCII);}
    }
}

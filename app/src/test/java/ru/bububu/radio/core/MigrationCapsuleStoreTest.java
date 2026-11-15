package ru.bububu.radio.core;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import ru.bububu.radio.sources.*;

public final class MigrationCapsuleStoreTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private byte[] v1(String text) {
        final byte[][] bytes={null};NoteBook book=new NoteBook(new NoteBook.Storage(){public byte[]load(){return bytes[0];}public void save(byte[]b){bytes[0]=b.clone();}});
        NoteBook.Note n=book.beginRecording("Заголовок",null,null,null,3);book.edit(n.id,"Заголовок",text);return bytes[0];
    }
    private static byte[][] layout(byte[] value){return new byte[][]{value,null,null};}
    private static void rejects(Runnable action){SourceBookTest.rejects(action);}
    @Test public void sealedCapsuleContainsExactPopulatedV1AndCanBeVerified()throws Exception {
        File dir=temporary.newFolder();MigrationCapsuleStore store=new MigrationCapsuleStore(dir);byte[] old=v1("Точный текст до миграции.");MigrationStamp stamp=store.prepare(old,10000,layout(old));assertEquals(SourceLimits.hash(old),stamp.baselineHash);assertEquals(old.length,stamp.baselineLength);assertNull(stamp.absenceEpoch);assertEquals(stamp.attemptId,stamp.capsuleId);byte[] raw=Files.readAllBytes(new File(dir,"notes-migration/"+stamp.capsuleId+".bin").toPath());assertEquals(raw.length,stamp.capsuleLength);assertEquals(SourceLimits.hash(raw),stamp.capsuleHash);assertTrue(contains(raw,old));store.verify(stamp);
    }
    @Test public void exactPresentRetryReusesOnlyMatchingCapsule()throws Exception {
        File dir=temporary.newFolder();MigrationCapsuleStore store=new MigrationCapsuleStore(dir);byte[] first=v1("H1.");MigrationStamp one=store.prepare(first,10000,layout(first)),same=store.prepare(first,10000,layout(first));assertEquals(one.capsuleId,same.capsuleId);byte[] second=v1("H2 с ручной правкой.");MigrationStamp changed=store.prepare(second,10000,layout(second));assertNotEquals(one.capsuleId,changed.capsuleId);assertEquals(SourceLimits.hash(second),changed.baselineHash);assertEquals(2,new File(dir,"notes-migration").list().length);
    }
    @Test public void absentRetryUsesFreshProofEpochInsteadOfReusingOldMarker()throws Exception {
        MigrationCapsuleStore store=new MigrationCapsuleStore(temporary.newFolder());MigrationStamp first=store.prepare(null,10000,layout(null)),second=store.prepare(null,10000,layout(null));assertNull(first.baselineHash);assertEquals(-1,first.baselineLength);assertNotEquals(first.absenceEpoch,second.absenceEpoch);assertNotEquals(first.capsuleId,second.capsuleId);store.verify(second);
    }
    @Test public void absentThenPresentNeverTreatsMarkerAsPresentBackup()throws Exception {
        File dir=temporary.newFolder();MigrationCapsuleStore store=new MigrationCapsuleStore(dir);MigrationStamp absent=store.prepare(null,10000,layout(null));byte[] present=v1("Новое личное содержимое.");MigrationStamp next=store.prepare(present,10000,layout(present));assertNotEquals(absent.capsuleId,next.capsuleId);assertNull(next.absenceEpoch);assertEquals(SourceLimits.hash(present),next.baselineHash);
    }
    @Test public void safeUnreferencedGcMaintainsTwoSealedCapsules()throws Exception {
        File dir=temporary.newFolder();MigrationCapsuleStore store=new MigrationCapsuleStore(dir);byte[] one=v1("H1"),two=v1("H2"),three=v1("H3");store.prepare(one,1000,layout(one));store.prepare(two,1000,layout(two));MigrationStamp third=store.prepare(three,1000,layout(three));assertEquals(2,new File(dir,"notes-migration").list().length);store.verify(third);
    }
    @Test public void unknownFileRetainsEveryCapsuleAndRefusesGc()throws Exception {
        File dir=temporary.newFolder();MigrationCapsuleStore store=new MigrationCapsuleStore(dir);byte[] one=v1("H1");MigrationStamp first=store.prepare(one,1000,layout(one));File unknown=new File(dir,"notes-migration/keep-unknown.data");Files.write(unknown.toPath(),new byte[]{2,3,4});byte[] retained=Files.readAllBytes(new File(dir,"notes-migration/"+first.capsuleId+".bin").toPath());byte[] two=v1("H2");rejects(()->store.prepare(two,1000,layout(two)));assertArrayEquals(new byte[]{2,3,4},Files.readAllBytes(unknown.toPath()));assertArrayEquals(retained,Files.readAllBytes(new File(dir,"notes-migration/"+first.capsuleId+".bin").toPath()));
    }
    @Test public void staleWritingTempIsNeverCommittedOrDeletedAsKnownGarbage()throws Exception {
        File dir=temporary.newFolder();File capsules=new File(dir,"notes-migration");assertTrue(capsules.mkdir());File pending=new File(capsules,java.util.UUID.randomUUID()+".tmp");Files.write(pending.toPath(),new byte[]{1,2});MigrationCapsuleStore store=new MigrationCapsuleStore(dir);rejects(()->store.prepare(null,1000,layout(null)));assertArrayEquals(new byte[]{1,2},Files.readAllBytes(pending.toPath()));
    }
    @Test public void corruptedCapsuleCannotBecomeSourceCommitEvidence()throws Exception {
        File dir=temporary.newFolder();MigrationCapsuleStore store=new MigrationCapsuleStore(dir);byte[] old=v1("Текст.");MigrationStamp stamp=store.prepare(old,1000,layout(old));File capsule=new File(dir,"notes-migration/"+stamp.capsuleId+".bin");byte[] corrupt=Files.readAllBytes(capsule.toPath());corrupt[corrupt.length/2]^=2;Files.write(capsule.toPath(),corrupt);rejects(()->store.verify(stamp));rejects(()->store.prepare(old,1000,layout(old)));assertArrayEquals(corrupt,Files.readAllBytes(capsule.toPath()));
    }
    @Test public void missingCapsuleVerificationDoesNotCreateDirectory()throws Exception {
        File dir=temporary.newFolder();MigrationCapsuleStore store=new MigrationCapsuleStore(dir);String id=java.util.UUID.randomUUID().toString();MigrationStamp stamp=new MigrationStamp(id,null,-1,java.util.UUID.randomUUID().toString(),id,100,SourceLimits.sha256("fake"));rejects(()->store.verify(stamp));assertFalse(new File(dir,"notes-migration").exists());
    }
    @Test public void pathTypeAndSymlinkFaultsAreRetained()throws Exception {
        File dir=temporary.newFolder();File path=new File(dir,"notes-migration");Files.write(path.toPath(),new byte[]{8});MigrationCapsuleStore store=new MigrationCapsuleStore(dir);rejects(()->store.prepare(null,1000,layout(null)));assertArrayEquals(new byte[]{8},Files.readAllBytes(path.toPath()));
        File other=temporary.newFolder();Files.createSymbolicLink(new File(other,"notes-migration").toPath(),dir.toPath());MigrationCapsuleStore symlink=new MigrationCapsuleStore(other);rejects(()->symlink.prepare(null,1000,layout(null)));assertTrue(Files.isSymbolicLink(new File(other,"notes-migration").toPath()));
    }
    @Test public void capsuleSymlinkIsNotFollowedForReadOrGc()throws Exception {
        File dir=temporary.newFolder();MigrationCapsuleStore store=new MigrationCapsuleStore(dir);byte[] old=v1("H1");MigrationStamp first=store.prepare(old,1000,layout(old));File real=new File(dir,"notes-migration/"+first.capsuleId+".bin");File elsewhere=temporary.newFile();Files.write(elsewhere.toPath(),new byte[]{7});Files.delete(real.toPath());Files.createSymbolicLink(real.toPath(),elsewhere.toPath());rejects(()->store.verify(first));rejects(()->store.prepare(old,1000,layout(old)));assertArrayEquals(new byte[]{7},Files.readAllBytes(elsewhere.toPath()));assertTrue(Files.isSymbolicLink(real.toPath()));
    }
    @Test public void recoveryCandidatesMustMatchBakPriorityAndProveAllAbsence()throws Exception {
        MigrationCapsuleStore store=new MigrationCapsuleStore(temporary.newFolder());byte[] base=v1("Base"),bak=v1("Committed bak");rejects(()->store.prepare(base,1000,new byte[][]{base,bak,null}));MigrationStamp correct=store.prepare(bak,1000,new byte[][]{base,bak,null});assertEquals(SourceLimits.hash(bak),correct.baselineHash);rejects(()->store.prepare(null,1000,new byte[][]{null,null,base}));rejects(()->store.prepare(null,1000,null));
    }
    @Test public void malformedRecoveryBytesBlockSourcesInsteadOfDroppingFiles()throws Exception {
        File dir=temporary.newFolder();MigrationCapsuleStore store=new MigrationCapsuleStore(dir);byte[] base=v1("Base"),invalid={1,3,5};rejects(()->store.prepare(base,1000,new byte[][]{base,null,invalid}));assertFalse(new File(dir,"notes-migration").exists());
    }
    @Test public void recoverableV2ProtectsCapsuleAndReusesMatchingV1Authority()throws Exception {
        SourceBookTest.Store storage=new SourceBookTest.Store(temporary.newFolder());NoteBook book=new NoteBook(storage);NoteBook.Note n=book.beginRecording("v1",null,null,null,1);byte[] v1=storage.load();SourceRecord source=SourceRecord.create("RSS","feed","Лента","https://example.test","rss1",true);book.commitSource(source,Collections.emptyList());byte[] v2=storage.load();MigrationStamp committed=book.migrationStamp();MigrationStamp retry=storage.capsules.prepare(v1,1000,new byte[][]{v2,v1,null});assertEquals(committed.capsuleId,retry.capsuleId);storage.capsules.verify(committed);rejects(()->storage.capsules.prepare(v1,1000,new byte[][]{v2,null,null}));assertEquals(source.id,new NoteBook(storage).sources().get(0).id);
    }
    @Test public void twoRecoverableV2ReferencesPreventUnsafeCapsuleGc()throws Exception {
        File dir=temporary.newFolder();SourceBookTest.Store first=new SourceBookTest.Store(dir);first.bytes=v1("H1");NoteBook one=new NoteBook(first);one.commitSource(SourceRecord.create("RSS","one","Первый","https://example.test/1","rss1",true),Collections.emptyList());byte[] recoveryOne=first.load();MigrationStamp stampOne=one.migrationStamp();
        SourceBookTest.Store second=new SourceBookTest.Store(dir);second.bytes=v1("H2");NoteBook two=new NoteBook(second);two.commitSource(SourceRecord.create("RSS","two","Второй","https://example.test/2","rss1",true),Collections.emptyList());byte[] recoveryTwo=second.load();MigrationStamp stampTwo=two.migrationStamp();byte[] h3=v1("H3 actual bak");MigrationCapsuleStore capsules=new MigrationCapsuleStore(dir);rejects(()->capsules.prepare(h3,1000,new byte[][]{recoveryOne,h3,recoveryTwo}));capsules.verify(stampOne);capsules.verify(stampTwo);assertEquals(2,new File(dir,"notes-migration").list().length);
    }
    @Test public void oversizedTargetOrInvalidV1WritesNoCapsule()throws Exception {
        File dir=temporary.newFolder();MigrationCapsuleStore store=new MigrationCapsuleStore(dir);rejects(()->store.prepare(null,NoteBook.MAX_METADATA_BYTES+1,layout(null)));rejects(()->store.prepare(new byte[]{2,4},1000,layout(new byte[]{2,4})));assertFalse(new File(dir,"notes-migration").exists());
    }
    @Test public void capacityBoundaryUsesCheckedParentAndAdmitsPresentAndAbsentAtFloor()throws Exception {
        for(boolean present:new boolean[]{false,true})for(long available:new long[]{SourceLimits.MIN_FREE_BYTES,SourceLimits.MIN_FREE_BYTES+1}) {
            File dir=temporary.newFolder();byte[] old=present?v1("PRESENT at boundary."):null;final int[] queries={0};
            MigrationCapsuleStore store=new MigrationCapsuleStore(dir,checked->{assertEquals(dir,checked);queries[0]++;return available;});
            MigrationStamp stamp=store.prepare(old,NoteBook.MAX_METADATA_BYTES,layout(old));store.verify(stamp);assertEquals(1,queries[0]);
            assertEquals(present?SourceLimits.hash(old):null,stamp.baselineHash);assertEquals(present?old.length:-1,stamp.baselineLength);assertEquals(!present,stamp.absenceEpoch!=null);
            assertEquals(1,new File(dir,"notes-migration").list().length);
        }
        // With valid local inputs, the 48 MiB floor dominates the retained computed peak.
        assertTrue(2L*NoteBook.MAX_METADATA_BYTES+16*1024L<SourceLimits.MIN_FREE_BYTES);
    }
    @Test public void capacityBelowFloorZeroAndNegativeLeavePresentAndAbsentInventoryUntouched()throws Exception {
        for(boolean present:new boolean[]{false,true})for(long available:new long[]{SourceLimits.MIN_FREE_BYTES-1,0,-1}) {
            File dir=temporary.newFolder();byte[] old=present?v1("Retained original metadata."):null;File original=new File(dir,java.util.UUID.randomUUID()+".wav");Files.write(original.toPath(),new byte[]{1,2,3});
            rejects(()->new MigrationCapsuleStore(dir,checked->available).prepare(old,NoteBook.MAX_METADATA_BYTES,layout(old)));
            assertFalse(new File(dir,"notes-migration").exists());assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(original.toPath()));
        }
    }
    @Test public void unavailableCapacityRetainsItsCauseAndExistingSealedCapsulesBeforeGc()throws Exception {
        for(boolean security:new boolean[]{false,true}) {
            File dir=temporary.newFolder();byte[] old=v1("H1");MigrationCapsuleStore real=new MigrationCapsuleStore(dir);MigrationStamp retained=real.prepare(old,1000,layout(old));
            File capsule=new File(dir,"notes-migration/"+retained.capsuleId+".bin");byte[] before=Files.readAllBytes(capsule.toPath());byte[] next=v1("H2");
            Throwable unavailable=security?new SecurityException("synthetic capacity unavailable"):new java.io.IOException("synthetic capacity unavailable");
            MigrationCapsuleStore denied=new MigrationCapsuleStore(dir,checked->{if(security)throw (SecurityException)unavailable;throw (java.io.IOException)unavailable;});
            IllegalStateException failure=assertThrows(IllegalStateException.class,()->denied.prepare(next,1000,layout(next)));
            Throwable cause=failure;while(cause!=null&&cause!=unavailable)cause=cause.getCause();assertSame(unavailable,cause);
            assertEquals(1,capsule.getParentFile().list().length);assertArrayEquals(before,Files.readAllBytes(capsule.toPath()));real.verify(retained);
        }
    }
    @Test public void capacityIsNotQueriedForUnprovenParentOrBaseline()throws Exception {
        final int[] queries={0};MigrationCapsuleStore.CapacityQuery query=checked->{queries[0]++;return Long.MAX_VALUE;};
        File dir=temporary.newFolder();byte[] one=v1("H1"),two=v1("H2");
        rejects(()->new MigrationCapsuleStore(dir,query).prepare(one,1000,layout(two)));
        File absent=new File(temporary.newFolder(),"absent");rejects(()->new MigrationCapsuleStore(absent,query).prepare(null,1000,layout(null)));
        File file=temporary.newFile();Files.write(file.toPath(),new byte[]{2});rejects(()->new MigrationCapsuleStore(file,query).prepare(null,1000,layout(null)));
        assertEquals(0,queries[0]);assertFalse(new File(dir,"notes-migration").exists());assertFalse(absent.exists());assertArrayEquals(new byte[]{2},Files.readAllBytes(file.toPath()));
    }
    @Test public void capacityRefusalKeepsCoherentV1EditableAndRetryUsesCurrentPresentBaseline()throws Exception {
        for(boolean present:new boolean[]{false,true}) {
            File dir=temporary.newFolder();final byte[][] bytes={present?v1("H1"):null};final long[] available={SourceLimits.MIN_FREE_BYTES-1};
            MigrationCapsuleStore capsules=new MigrationCapsuleStore(dir,checked->available[0]);
            NoteBook.MigrationStorage storage=new NoteBook.MigrationStorage() {
                public byte[] load(){return bytes[0]==null?null:bytes[0].clone();}public void save(byte[] value){bytes[0]=value.clone();}
                public MigrationStamp prepareMigration(byte[] baseline,int target){return capsules.prepare(baseline,target,layout(bytes[0]));}public void verifyMigrationStamp(MigrationStamp stamp){capsules.verify(stamp);}
            };
            NoteBook book=new NoteBook(storage);byte[] before=storage.load();File original=new File(dir,java.util.UUID.randomUUID()+".wav");Files.write(original.toPath(),new byte[]{2,3});
            SourceRecord source=SourceRecord.create("RSS","capacity-test","Лента","https://example.test/feed","rss1",true);
            rejects(()->book.commitSource(source,Collections.emptyList()));assertArrayEquals(before,storage.load());assertFalse(book.storageUncertain());assertEquals(1,book.schema());assertTrue(book.sources().isEmpty());assertFalse(new File(dir,"notes-migration").exists());
            NoteBook.Note note=present?book.notes().get(0):book.beginRecording("H2",null,null,null,2);book.edit(note.id,"H2","Actual current H2 after refusal.");byte[] current=storage.load();
            available[0]=SourceLimits.MIN_FREE_BYTES;book.commitSource(source,Collections.emptyList());assertEquals(SourceLimits.hash(current),book.migrationStamp().baselineHash);assertNull(book.migrationStamp().absenceEpoch);assertEquals("Actual current H2 after refusal.",new NoteBook(storage).find(note.id).text);assertArrayEquals(new byte[]{2,3},Files.readAllBytes(original.toPath()));
        }
    }
    private static boolean contains(byte[] all,byte[] part){for(int i=0;i<=all.length-part.length;i++)if(Arrays.equals(Arrays.copyOfRange(all,i,i+part.length),part))return true;return false;}
}

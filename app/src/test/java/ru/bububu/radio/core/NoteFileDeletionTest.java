package ru.bububu.radio.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.Assert.*;

public final class NoteFileDeletionTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final byte[] ORIGINAL={1,3,5,7},PART={2,4,6};
    private static final class Operations implements NoteFileDeletion.Operations {
        int moves,deletes,syncs;int failMove,failMoveAfter,failDelete,failSync;
        public void move(Path from,Path to)throws IOException{if(++moves==failMove)throw new IOException("Injected rename refusal");Files.move(from,to);if(moves==failMoveAfter)throw new IOException("Injected post-rename refusal");}
        public void delete(Path file)throws IOException{if(++deletes==failDelete)throw new IOException("Injected cleanup refusal");Files.delete(file);}
        public void syncDirectory(Path directory)throws IOException{if(++syncs==failSync)throw new IOException("Injected directory sync refusal");}
    }
    private static void rejected(Runnable operation){try{operation.run();fail("Expected safe refusal");}catch(IllegalArgumentException|IllegalStateException expected){}}
    private static Path file(Path directory,String id,String suffix){return directory.resolve(id+suffix);}
    private static String id(){return UUID.randomUUID().toString();}
    private static void writePair(Path directory,String id)throws IOException{Files.write(file(directory,id,".wav"),ORIGINAL);Files.write(file(directory,id,".wav.part"),PART);}

    @Test public void knownOldRestoresExactOriginalsAndKnownTargetDeletesOnlyStagedIdentity() throws Exception {
        Path directory=temporary.newFolder().toPath();String target=id(),other=id();writePair(directory,target);writePair(directory,other);Path capsule=directory.resolve("notes-migration");Files.createDirectory(capsule);Files.write(capsule.resolve("private-capsule.bin"),new byte[]{9});
        NoteFileDeletion deletion=new NoteFileDeletion(directory);deletion.stage(target);assertFalse(Files.exists(file(directory,target,".wav")));assertArrayEquals(ORIGINAL,Files.readAllBytes(file(directory,target,".wav.delete-pending")));
        assertTrue(deletion.recover(target,n->NoteFileDeletion.Presence.PRESENT));assertArrayEquals(ORIGINAL,Files.readAllBytes(file(directory,target,".wav")));assertArrayEquals(PART,Files.readAllBytes(file(directory,target,".wav.part")));
        deletion.stage(target);assertTrue(deletion.recoverAll(n->NoteFileDeletion.Presence.ABSENT));assertFalse(Files.exists(file(directory,target,".wav")));assertFalse(Files.exists(file(directory,target,".wav.delete-pending")));assertFalse(Files.exists(file(directory,target,".wav.part.delete-pending")));assertArrayEquals(ORIGINAL,Files.readAllBytes(file(directory,other,".wav")));assertArrayEquals(PART,Files.readAllBytes(file(directory,other,".wav.part")));assertArrayEquals(new byte[]{9},Files.readAllBytes(capsule.resolve("private-capsule.bin")));assertTrue(deletion.recover(target,n->NoteFileDeletion.Presence.ABSENT));
    }

    @Test public void unknownAndUnreadableSiblingAuthorityPreserveEveryStagedFileBeforeCleanup() throws Exception {
        Path directory=temporary.newFolder().toPath();String one=id(),two=id();writePair(directory,one);writePair(directory,two);NoteFileDeletion deletion=new NoteFileDeletion(directory);deletion.stage(one);deletion.stage(two);
        rejected(()->deletion.recoverAll(n->n.equals(two)?NoteFileDeletion.Presence.UNKNOWN:NoteFileDeletion.Presence.ABSENT));
        for(String id:new String[]{one,two}){assertArrayEquals(ORIGINAL,Files.readAllBytes(file(directory,id,".wav.delete-pending")));assertArrayEquals(PART,Files.readAllBytes(file(directory,id,".wav.part.delete-pending")));}
        rejected(()->deletion.recoverAll(n->{if(n.equals(two))throw new IllegalStateException("Unreadable Book");return NoteFileDeletion.Presence.ABSENT;}));assertTrue(Files.exists(file(directory,one,".wav.delete-pending")));assertTrue(Files.exists(file(directory,two,".wav.delete-pending")));
        NoteFileDeletion.Authority missingMetadata=n->NoteFileDeletion.Presence.UNKNOWN;rejected(()->deletion.recoverAll(missingMetadata));for(String id:new String[]{one,two}){assertArrayEquals(ORIGINAL,Files.readAllBytes(file(directory,id,".wav.delete-pending")));assertArrayEquals(PART,Files.readAllBytes(file(directory,id,".wav.part.delete-pending")));}
    }

    @Test public void failedSecondRenameOrSyncRollsBackAndInterruptedStageRestoresBeforeOrphanScan() throws Exception {
        Path directory=temporary.newFolder().toPath();String target=id();writePair(directory,target);Operations io=new Operations();io.failMove=2;NoteFileDeletion deletion=new NoteFileDeletion(directory,io);rejected(()->deletion.stage(target));assertArrayEquals(ORIGINAL,Files.readAllBytes(file(directory,target,".wav")));assertArrayEquals(PART,Files.readAllBytes(file(directory,target,".wav.part")));assertFalse(Files.exists(file(directory,target,".wav.delete-pending")));
        io.failMove=0;io.failSync=io.syncs+1;rejected(()->deletion.stage(target));assertArrayEquals(ORIGINAL,Files.readAllBytes(file(directory,target,".wav")));assertArrayEquals(PART,Files.readAllBytes(file(directory,target,".wav.part")));
        io.failSync=0;io.failMoveAfter=io.moves+1;rejected(()->deletion.stage(target));assertArrayEquals(ORIGINAL,Files.readAllBytes(file(directory,target,".wav")));assertArrayEquals(PART,Files.readAllBytes(file(directory,target,".wav.part")));
        Files.move(file(directory,target,".wav"),file(directory,target,".wav.delete-pending"));assertTrue(new NoteFileDeletion(directory).recoverAll(n->NoteFileDeletion.Presence.PRESENT));assertArrayEquals(ORIGINAL,Files.readAllBytes(file(directory,target,".wav")));assertArrayEquals(PART,Files.readAllBytes(file(directory,target,".wav.part")));
    }

    @Test public void conflictsSymlinksAndNonCanonicalIdentityRefuseWithoutOverwrite() throws Exception {
        Path directory=temporary.newFolder().toPath();String target=id();writePair(directory,target);Path pending=file(directory,target,".wav.delete-pending");Files.write(pending,new byte[]{99});NoteFileDeletion deletion=new NoteFileDeletion(directory);rejected(()->deletion.stage(target));rejected(()->deletion.recoverAll(n->NoteFileDeletion.Presence.ABSENT));assertArrayEquals(ORIGINAL,Files.readAllBytes(file(directory,target,".wav")));assertArrayEquals(new byte[]{99},Files.readAllBytes(pending));
        Files.delete(pending);Files.delete(file(directory,target,".wav.part"));Files.createSymbolicLink(file(directory,target,".wav.part"),file(directory,target,".wav"));rejected(()->deletion.stage(target));assertArrayEquals(ORIGINAL,Files.readAllBytes(file(directory,target,".wav")));assertFalse(Files.exists(pending));
        rejected(()->deletion.stage(target.toUpperCase(java.util.Locale.ROOT)));rejected(()->deletion.stage("../"+target));Files.write(directory.resolve("invalid.wav.delete-pending"),new byte[]{88});rejected(()->deletion.recoverAll(n->NoteFileDeletion.Presence.ABSENT));assertArrayEquals(new byte[]{88},Files.readAllBytes(directory.resolve("invalid.wav.delete-pending")));
    }

    @Test public void failedTargetCleanupKeepsRetryablePendingAndSiblingConflictPreventsAnyCleanup() throws Exception {
        Path directory=temporary.newFolder().toPath();String one=id(),two=id();writePair(directory,one);writePair(directory,two);Operations io=new Operations();NoteFileDeletion deletion=new NoteFileDeletion(directory,io);deletion.stage(one);deletion.stage(two);Files.write(file(directory,two,".wav"),new byte[]{55});
        rejected(()->deletion.recoverAll(n->NoteFileDeletion.Presence.ABSENT));assertEquals(0,io.deletes);assertArrayEquals(ORIGINAL,Files.readAllBytes(file(directory,one,".wav.delete-pending")));Files.delete(file(directory,two,".wav"));
        io.failDelete=1;rejected(()->deletion.recover(one,n->NoteFileDeletion.Presence.ABSENT));assertArrayEquals(ORIGINAL,Files.readAllBytes(file(directory,one,".wav.delete-pending")));assertArrayEquals(PART,Files.readAllBytes(file(directory,one,".wav.part.delete-pending")));io.failDelete=0;assertTrue(deletion.recover(one,n->NoteFileDeletion.Presence.ABSENT));assertTrue(Files.exists(file(directory,two,".wav.delete-pending")));
        Files.write(file(directory,one,".wav"),ORIGINAL);Files.write(file(directory,one,".wav.part.delete-pending"),PART);rejected(()->deletion.recoverAll(n->NoteFileDeletion.Presence.ABSENT));assertArrayEquals(ORIGINAL,Files.readAllBytes(file(directory,one,".wav")));assertArrayEquals(PART,Files.readAllBytes(file(directory,one,".wav.part.delete-pending")));assertTrue(Files.exists(file(directory,two,".wav.delete-pending")));
    }
}

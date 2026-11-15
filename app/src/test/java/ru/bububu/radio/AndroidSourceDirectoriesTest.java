package ru.bububu.radio;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import ru.bububu.radio.sources.*;

public final class AndroidSourceDirectoriesTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void absentOwnedChildIsCreatedAndExistingCooldownSurvivesPreparationAndRestart() throws Exception {
        File parent=temporary.newFolder();File child=new File(parent,"source-network");
        assertFalse(Files.exists(child.toPath(),LinkOption.NOFOLLOW_LINKS));
        assertEquals(child,AndroidSourceDirectories.prepareNetwork(parent));
        HostCooldownStore store=new HostCooldownStore(child);
        store.save(Collections.singletonMap("example.org",new HostCooldownStore.Entry(Long.MAX_VALUE,true)));
        byte[] before=Files.readAllBytes(new File(child,"network-cooldowns-v1.bin").toPath());
        assertEquals(child,AndroidSourceDirectories.prepareNetwork(parent));
        HostRequestGate restarted=new HostRequestGate(new HostCooldownStore(child),SourceClock.SYSTEM);
        SourceFailure denied=assertThrows(SourceFailure.class,()->restarted.acquire("example.org",new RequestScope(1,1),Long.MAX_VALUE));
        assertEquals("COOLDOWN_UNREPRESENTABLE",denied.reason);
        assertArrayEquals(before,Files.readAllBytes(new File(child,"network-cooldowns-v1.bin").toPath()));
    }
    @Test public void existingRegularFileOrSymlinkIsNeverReplaced() throws Exception {
        File parent=temporary.newFolder();Path child=parent.toPath().resolve("source-network");byte[] evidence={1,4,9};Files.write(child,evidence);
        assertEquals("COOLDOWN_STORAGE",assertThrows(SourceFailure.class,()->AndroidSourceDirectories.prepareNetwork(parent)).reason);
        assertArrayEquals(evidence,Files.readAllBytes(child));
        File linkedParent=temporary.newFolder();Path linked=linkedParent.toPath().resolve("source-network");Files.createSymbolicLink(linked,parent.toPath());
        assertThrows(SourceFailure.class,()->AndroidSourceDirectories.prepareNetwork(linkedParent));
        assertTrue(Files.isSymbolicLink(linked));assertArrayEquals(evidence,Files.readAllBytes(child));
    }
    @Test public void absentWrongTypeAndSymlinkParentAreNeverCreatedOrFollowed() throws Exception {
        File root=temporary.newFolder();File absent=new File(root,"absent-parent");
        assertThrows(SourceFailure.class,()->AndroidSourceDirectories.prepareNetwork(absent));assertFalse(absent.exists());
        File regular=temporary.newFile();byte[] evidence={2,8};Files.write(regular.toPath(),evidence);
        assertThrows(SourceFailure.class,()->AndroidSourceDirectories.prepareNetwork(regular));assertArrayEquals(evidence,Files.readAllBytes(regular.toPath()));
        Path linked=root.toPath().resolve("linked-parent");Files.createSymbolicLink(linked,temporary.newFolder().toPath());
        assertThrows(SourceFailure.class,()->AndroidSourceDirectories.prepareNetwork(linked.toFile()));assertTrue(Files.isSymbolicLink(linked));assertFalse(Files.exists(linked.resolve("source-network"),LinkOption.NOFOLLOW_LINKS));
    }
    @Test public void securityInspectionFailureRetainsExistingChildAndCause() throws Exception {
        File actual=temporary.newFolder();File child=AndroidSourceDirectories.prepareNetwork(actual);Path evidence=child.toPath().resolve("keep.data");Files.write(evidence,new byte[]{3,7});
        SecurityException denied=new SecurityException("synthetic inspection unavailable");
        File inaccessible=new File(actual.getPath()) {@Override public Path toPath(){throw denied;}};
        SourceFailure failure=assertThrows(SourceFailure.class,()->AndroidSourceDirectories.prepareNetwork(inaccessible));
        assertSame(denied,failure.getCause());assertArrayEquals(new byte[]{3,7},Files.readAllBytes(evidence));
    }
    @Test public void uncertainCooldownLayoutStaysBlockedAndUnchanged() throws Exception {
        File child=AndroidSourceDirectories.prepareNetwork(temporary.newFolder());Path pending=child.toPath().resolve("network-cooldowns-v1.bin.new");byte[] evidence={5,6,7};Files.write(pending,evidence);
        AndroidSourceDirectories.prepareNetwork(child.getParentFile());
        HostRequestGate gate=new HostRequestGate(new HostCooldownStore(child),SourceClock.SYSTEM);
        assertEquals("COOLDOWN_STORAGE",assertThrows(SourceFailure.class,()->gate.acquire("example.org",new RequestScope(1,1),Long.MAX_VALUE)).reason);
        assertArrayEquals(evidence,Files.readAllBytes(pending));assertFalse(new File(child,"network-cooldowns-v1.bin").exists());
    }
}

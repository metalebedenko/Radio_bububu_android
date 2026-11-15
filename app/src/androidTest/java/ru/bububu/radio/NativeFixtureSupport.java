package ru.bububu.radio;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import ru.bububu.radio.sources.SourceCoordinator;
import static org.junit.Assert.*;

/** Test-only ownership/drain barriers shared by the two demo fixtures. No production callbacks are invoked as UI actions. */
final class NativeFixtureSupport {
    private final Instrumentation instrumentation;
    private final Context context;
    private final List<Path> managed;
    private final Set<RadioService> services = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<SourceCoordinator> coordinators = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ExecutorService> executors = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Path, String> baseline = new LinkedHashMap<>();
    private final Map<Path, Path> originals = new LinkedHashMap<>();
    private Path backup;
    private boolean isolated, drained;

    NativeFixtureSupport(Instrumentation instrumentation, Context context, List<java.io.File> paths) {
        this.instrumentation = instrumentation; this.context = context; managed = new ArrayList<>();
        for (java.io.File file : paths) managed.add(file.toPath());
    }
    void track(RadioService service) { if (service != null) { services.add(service); drained = false; } }
    void track(SourceCoordinator coordinator) { if (coordinator != null) { coordinators.add(coordinator); drained = false; } }
    boolean drained() { return drained; }

    void isolate(String prefix) throws Exception {
        assertTrue("Originals may be moved only after confirmed service/writer shutdown", drained);
        for (Path path : managed) baseline.put(path, exists(path) ? treeHash(path) : null);
        backup = Files.createTempDirectory(context.getCacheDir().toPath(), prefix);
        int index = 0;
        for (Path path : managed) if (baseline.get(path) != null) {
            Path preserved = backup.resolve("original-" + index++);
            Files.move(path, preserved); originals.put(path, preserved);
            assertEquals("Preserved original bytes", baseline.get(path), treeHash(preserved));
        }
        isolated = true;
    }

    /** onDestroy's early destroyed flag is insufficient: its later player.pause still saves AtomicFile. */
    void stopAndDrain(Runnable closeScreen) throws Exception {
        drained = false;
        Throwable failure = null;
        try {
            main(() -> {
                for (RadioService service : services) {
                    SourceCoordinator source = service.sources(); if (source != null) coordinators.add(source);
                    Object audio = field(service, "noteAudio");
                    if (audio != null) executors.add((ExecutorService) field(audio, "writer"));
                    if (!(boolean) field(service, "destroyed")) {
                        service.cancelSourceUi();
                        if (service.notes() != null) { service.notes().cancelTranscription(); service.notes().pauseOriginal(); }
                        if (service.player() != null) service.player().pause();
                    }
                }
                for (SourceCoordinator coordinator : coordinators) {
                    executors.add((ExecutorService) field(coordinator, "workers")); coordinator.close();
                }
                return null;
            });
        } catch (Throwable error) { failure = error; }
        try { if (closeScreen != null) closeScreen.run(); } catch (Throwable error) { failure = append(failure, error); }
        try {
            context.stopService(new Intent(context, RadioService.class));
            long until = SystemClock.elapsedRealtime() + 10000;
            boolean stopped = false;
            while (SystemClock.elapsedRealtime() < until) {
                // The owner barrier cannot run midway through onDestroy on the same main looper.
                stopped = main(() -> serviceAbsent() && allDestroyed());
                if (stopped) break; SystemClock.sleep(50);
            }
            assertTrue("Complete onDestroy owner barrier was not confirmed", stopped);
            long workerUntil = SystemClock.elapsedRealtime() + 10000;
            for (ExecutorService executor : executors) {
                assertTrue("Fixture executor was not shut down; originals retained", executor.isShutdown());
                long remaining = Math.max(1, workerUntil - SystemClock.elapsedRealtime());
                assertTrue("Fixture writer/worker did not terminate; originals retained", executor.awaitTermination(remaining, TimeUnit.MILLISECONDS));
            }
            // File-STT's cancelled session can clear active before its reader thread exits.
            // Source names also cover an initial pre-existing service whose reference is unavailable.
            for (Thread thread : Thread.getAllStackTraces().keySet()) if (ownedWorker(thread)) {
                thread.join(Math.max(1, workerUntil - SystemClock.elapsedRealtime()));
                assertFalse("Known source/file worker is still live; originals retained", thread.isAlive());
            }
            instrumentation.waitForIdleSync();
            main(() -> { requireQuiescentOwners(); return null; });
        } catch (Throwable error) { failure = append(failure, error); }
        if (failure != null) throw retained("Shutdown/drain could not be verified", failure);
        drained = true;
    }

    private boolean allDestroyed() throws Exception {
        for (RadioService service : services) if (!(boolean) field(service, "destroyed")) return false;
        return true;
    }
    private void requireQuiescentOwners() throws Exception {
        assertTrue("A service reappeared during fixture shutdown", serviceAbsent());
        assertTrue("Service destruction did not complete", allDestroyed());
        assertFalse("Unconfirmed native output owner; retain all evidence", AndroidNoteAudio.isOutputBlocked());
        assertFalse("Pending original finalization; retain all evidence", AndroidNoteAudio.isRecordingStorageBusy());
        for (RadioService service : services) {
            if (service.notes() != null) assertTrue("Note callbacks were not closed", (boolean) field(service.notes(), "closed"));
            if (service.player() != null) assertTrue("Radio output stop is unconfirmed", service.player().isOutputStopped());
            if (service.notesBook() != null) assertFalse("Fixture Book publication is uncertain", service.notesBook().storageUncertain());
            Object transcriber = field(service, "fileTranscriber");
            if (transcriber != null) assertNull("File session remains active", field(transcriber, "active"));
        }
        for (SourceCoordinator coordinator : coordinators) assertTrue("Source callback owner remains open", (boolean) field(coordinator, "closed"));
        for (ExecutorService executor : executors) assertTrue("Fixture executor is still live", executor.isTerminated());
        for (Thread thread : Thread.getAllStackTraces().keySet()) if (ownedWorker(thread)) assertFalse("Known file/source worker appeared after drain", thread.isAlive());
    }
    private static boolean ownedWorker(Thread thread) { return "Bububu-file-stt".equals(thread.getName()) || "Bububu-source".equals(thread.getName()); }

    /** Quarantine verified fixture trees; an unexpected/recreated target is never blindly purged. */
    void restore() throws Exception {
        if (backup == null) return;
        if (!drained) throw retained("Restoration refused without a confirmed writer drain", null);
        Map<Path, String> discarded = new LinkedHashMap<>();
        try {
            main(() -> { requireQuiescentOwners(); return null; });
            for (Map.Entry<Path, Path> original : originals.entrySet())
                assertEquals("Preserved backup changed; retain all evidence", baseline.get(original.getKey()), treeHash(original.getValue()));
            if (isolated) {
                int index = 0;
                for (Path path : managed) if (exists(path)) {
                    String digest = treeHash(path);
                    Path testTree = backup.resolve("test-owned-" + index++);
                    Files.move(path, testTree); discarded.put(testTree, digest);
                    assertEquals("Fixture tree changed while quarantining", digest, treeHash(testTree));
                }
                for (Path path : managed) assertFalse("A writer recreated a restoration target; preserve backup and target", exists(path));
            }
            // Check every target before restoring any original; no replacement of an unknown target.
            for (Path path : originals.keySet()) assertFalse("Restoration target exists; retain original backup", exists(path));
            for (Map.Entry<Path, Path> original : originals.entrySet()) {
                assertFalse("Restoration target reappeared; retain original backup", exists(original.getKey()));
                Files.move(original.getValue(), original.getKey());
                assertEquals("Original bytes must be restored exactly", baseline.get(original.getKey()), treeHash(original.getKey()));
            }
            main(() -> { requireQuiescentOwners(); return null; });
            for (Map.Entry<Path, String> original : baseline.entrySet()) {
                if (original.getValue() == null) assertFalse("Originally absent managed target reappeared", exists(original.getKey()));
                else assertEquals("Managed baseline differs after restoration", original.getValue(), treeHash(original.getKey()));
            }
            for (Map.Entry<Path, String> own : discarded.entrySet()) {
                assertEquals("Quarantined fixture changed; retain evidence", own.getValue(), treeHash(own.getKey()));
                deleteOwnedTree(own.getKey());
            }
            // Delete only an empty known directory, never an entire retained backup recursively.
            Files.delete(backup); backup = null;
        } catch (Throwable error) { throw retained("Fixture restoration was not verified", error); }
    }

    static void clickVisible(Instrumentation instrumentation, String caption) {
        AccessibilityServiceInfo info = instrumentation.getUiAutomation().getServiceInfo();
        assertNotNull("Accessibility service must be available", info);
        int previousFlags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        instrumentation.getUiAutomation().setServiceInfo(info);
        try {
        long until = SystemClock.elapsedRealtime() + 8000;
        boolean dialogAction = isDialogCaption(caption);
        while (SystemClock.elapsedRealtime() < until) {
            instrumentation.waitForIdleSync();
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            boolean clicked = false;
            if (root != null) try {
                clicked = clickNode(root, caption, true);
                if (!clicked && !dialogAction) clicked = clickNode(root, caption, false);
                if (!clicked && !dialogAction) scrollForward(root);
            } finally { root.recycle(); }
            if (clicked) { instrumentation.waitForIdleSync(); return; }
            SystemClock.sleep(100); // Keep waiting when a dialog/window is still appearing.
        }
        fail("Real visible enabled control missing after bounded wait: " + caption);
        } finally {
            info.flags = previousFlags;
            instrumentation.getUiAutomation().setServiceInfo(info);
        }
    }
    private static boolean isDialogCaption(String caption) {
        String key = normalized(caption);
        return key.equals("отмена") || key.equals("заменить текст") || key.equals("добавить все доступные") || key.equals("только полные");
    }
    private static boolean clickNode(AccessibilityNodeInfo node, String caption, boolean dialogOnly) {
        String id = node.getViewIdResourceName();
        boolean dialogButton = "android:id/button1".equals(id) || "android:id/button2".equals(id) || "android:id/button3".equals(id);
        boolean role = dialogOnly ? dialogButton : !dialogButton && "android.widget.Button".contentEquals(node.getClassName() == null ? "" : node.getClassName());
        if (role && node.isVisibleToUser() && node.isEnabled() && node.isClickable() && normalized(caption).equals(normalized(node.getText())))
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i); if (child == null) continue;
            try { if (clickNode(child, caption, dialogOnly)) return true; } finally { child.recycle(); }
        }
        return false;
    }
    private static String normalized(CharSequence value) { return value == null ? "" : value.toString().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private static boolean scrollForward(AccessibilityNodeInfo node) {
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child == null) continue; try { if (scrollForward(child)) return true; } finally { child.recycle(); } }
        return false;
    }

    @SuppressWarnings("deprecation") private boolean serviceAbsent() {
        for (ActivityManager.RunningServiceInfo info : context.getSystemService(ActivityManager.class).getRunningServices(100))
            if (info.service.equals(new ComponentName(context, RadioService.class))) return false;
        return true;
    }
    private <T> T main(Callable<T> action) {
        java.util.concurrent.atomic.AtomicReference<T> value = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { value.set(action.call()); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() instanceof Error) throw (Error) error.get(); if (error.get() != null) throw new AssertionError("Fixture owner barrier failed", error.get()); return value.get();
    }
    private static Object field(Object target, String name) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static boolean exists(Path path) throws Exception {
        try { Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return true; }
        catch (NoSuchFileException absent) { return false; } // Access/type failures are never absence.
    }
    private static String treeHash(Path root) throws Exception {
        assertTrue("Expected retained tree is missing: " + root, exists(root));
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); List<Path> entries = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) { stream.sorted().forEach(entries::add); }
        for (Path entry : entries) {
            digest.update(root.relativize(entry).toString().getBytes(StandardCharsets.UTF_8));
            BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) { digest.update((byte) 'L'); digest.update(Files.readSymbolicLink(entry).toString().getBytes(StandardCharsets.UTF_8)); }
            else if (attributes.isDirectory()) digest.update((byte) 'D');
            else if (attributes.isRegularFile()) { digest.update((byte) 'F'); try (InputStream input = Files.newInputStream(entry)) { byte[] buffer = new byte[4096]; int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); } }
            else throw new IllegalStateException("Unknown retained storage type: " + entry);
        }
        StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255)); return hash.toString();
    }
    private static void deleteOwnedTree(Path root) throws Exception {
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            List<Path> entries = stream.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList());
            for (Path path : entries) assertTrue("Unexpected quarantine type; retain evidence", !Files.isSymbolicLink(path) && (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)));
            for (Path path : entries) Files.delete(path);
        }
    }
    private AssertionError retained(String message, Throwable cause) { return new AssertionError(message + "; retained backup: " + backup, cause); }
    private static Throwable append(Throwable previous, Throwable error) { if (previous == null) return error; previous.addSuppressed(error); return previous; }
}

package ru.bububu.radio.models;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

public class BootstrapQueueTest {
    private static final class Result<T> implements ModelWorkQueue.Result<T> {
        int success, errors; boolean alive = true; String error; T value;
        public void success(T v) { if (alive) { success++;value = v; } }
        public void error(String e) { if (alive) { errors++;error = e; } }
    }
    private static final class Host {
        final ArrayDeque<Runnable> worker = new ArrayDeque<>(), owner = new ArrayDeque<>();
        final List<String> events = new ArrayList<>();
        final ModelWorkQueue queue = new ModelWorkQueue(worker::add, owner::add, value -> events.add("discard"));
        int attempts, waits;
        void work() { assertEquals(1, worker.size());worker.remove().run(); }
        void callback() { owner.remove().run();assertTrue(worker.size() <= 1); }
        void drain() { int steps = 0;while (!worker.isEmpty() || !owner.isEmpty()) { assertTrue(++steps < 60);if (!owner.isEmpty()) callback();else work(); } }
        ModelPackStore.Token setup(Result<Void> r) {
            return queue.submitIdle(t -> { attempts++;events.add("verify");return null; }, r, () -> waits++);
        }
        ModelPackStore.Token asr(Result<String> r) {
            return queue.submit(false, t -> { events.add("asr");assertFalse(t.cancelled());return "decoded"; }, r);
        }
    }
    private final byte[] a = {1, 2, 3}, b = {4, 5, 6, 7};
    private final PackCatalog silero = new PackCatalog("silero", 1, new PackCatalog.Entry("core.pt", a.length, ModelPackStore.hash(a)));
    private final PackCatalog giga = new PackCatalog("gigaam", 1, new PackCatalog.Entry("encoder.onnx", b.length, ModelPackStore.hash(b)));
    private byte[] zip(PackCatalog p) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) { put(z, "manifest.txt", p.manifest());put(z, p.files.get(0).name, p == silero ? a : b); }
        return bytes.toByteArray();
    }
    private void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);e.setMethod(ZipEntry.STORED);e.setSize(data.length);CRC32 crc = new CRC32();crc.update(data);e.setCrc(crc.getValue());z.putNextEntry(e);z.write(data);z.closeEntry();
    }
    private ModelPackStore store() throws IOException { return new ModelPackStore(Files.createTempDirectory("bootstrap43").toFile(), d -> {}); }
    private BundledPacks packs(ModelPackStore store, List<String> imports) {
        return new BundledPacks(store, new PackCatalog[]{silero, giga}, p -> { imports.add(p.kind);return new ByteArrayInputStream(zip(p)); }, () -> Long.MAX_VALUE);
    }

    @Test public void ordinaryForegroundBootstrapReproducesObservedBusyPrefix() {
        Host h = new Host();Result<String> user = new Result<>();h.asr(user);Result<Void> oldSetup = new Result<>();
        h.queue.submit(false, t -> null, oldSetup);h.callback();
        assertEquals("Дождитесь завершения текущей операции", oldSetup.error);assertEquals(0, oldSetup.success);
        h.drain();assertEquals(1, user.success);
    }
    @Test public void onResultBeforeRebindWaitsWithoutCancellingUserJob() {
        Host h = new Host();Result<String> user = new Result<>();ModelPackStore.Token asr = h.asr(user);Result<Void> setup = new Result<>();h.setup(setup);
        assertFalse(asr.cancelled());assertEquals(0, h.attempts);assertTrue(h.queue.foregroundBlocked());
        h.drain();assertEquals(Arrays.asList("asr", "verify"), h.events);assertEquals(1, user.success);assertEquals(1, setup.success);assertEquals(0, setup.errors);assertFalse(h.queue.busy());
    }
    @Test public void rebindBeforeOnResultYieldsOnlyAutomaticAttempt() {
        Host h = new Host();Result<Void> setup = new Result<>();ModelPackStore.Token owner = h.setup(setup);Result<String> user = new Result<>();ModelPackStore.Token asr = h.asr(user);
        assertFalse(owner.cancelled());assertFalse(asr.cancelled());h.drain();
        assertEquals(Arrays.asList("asr", "verify"), h.events);assertEquals(1, setup.success);assertEquals(0, setup.errors);assertEquals(1, user.success);assertTrue(h.waits > 0);
    }
    @Test public void userArrivalAfterVerifyBeforeCallbackCannotDuplicateDefaults() {
        Host h = new Host();Result<Void> setup = new Result<>();h.setup(setup);h.work();assertEquals(1, h.attempts);
        Result<String> user = new Result<>();ModelPackStore.Token asr = h.asr(user);h.drain();
        assertFalse(asr.cancelled());assertEquals(2, h.attempts);assertEquals(1, setup.success);assertEquals(0, setup.errors);assertEquals(1, user.success);
    }
    @Test public void userCallbackCanAdmitNextUserBeforeDeferredSetup() {
        Host h = new Host();Result<String> second = new Result<>();
        h.queue.submit(false, t -> { h.events.add("user1");return "first"; }, new ModelWorkQueue.Result<String>() {
            public void success(String value) { h.asr(second); }
            public void error(String value) { fail(value); }
        });
        Result<Void> setup = new Result<>();h.setup(setup);h.drain();
        assertEquals(Arrays.asList("user1", "asr", "verify"), h.events);assertEquals(1, second.success);assertEquals(1, setup.success);
    }
    @Test public void destroyedWaitingOwnerIsReplacedWithoutTouchingActiveAsr() {
        Host h = new Host();Result<String> user = new Result<>();ModelPackStore.Token asr = h.asr(user);
        Result<Void> old = new Result<>();ModelPackStore.Token token = h.setup(old);old.alive = false;assertTrue(token.cancel());
        Result<Void> current = new Result<>();h.setup(current);h.drain();
        assertFalse(asr.cancelled());assertEquals(1, user.success);assertEquals(0, old.success + old.errors);assertEquals(1, current.success);assertEquals(1, h.attempts);
    }
    @Test public void destroyedActiveOwnerCannotPublishLateSuccessIntoNewOwner() {
        Host h = new Host();Result<Void> old = new Result<>();ModelPackStore.Token token = h.setup(old);h.work();
        old.alive = false;assertTrue(token.cancel());Result<Void> current = new Result<>();h.setup(current);h.drain();
        assertEquals(0, old.success + old.errors);assertEquals(1, current.success);assertEquals(0, current.errors);assertEquals(2, h.attempts);
    }
    @Test public void cancelledWaitingSetupDrainsAfterUserCompletionWithoutStarting() {
        Host h = new Host();Result<String> user = new Result<>();h.asr(user);Result<Void> setup = new Result<>();assertTrue(h.setup(setup).cancel());
        h.drain();assertEquals(1, user.success);assertEquals(0, h.attempts);assertEquals(1, setup.errors);assertEquals(0, setup.success);assertFalse(h.queue.busy());
    }
    @Test public void genuineSameSizeHashFailureAfterBusyStillSurfaces() throws Exception {
        ModelPackStore store = store();List<String> imports = new ArrayList<>();packs(store, imports).prepare(new ModelPackStore.Token(), m -> {});
        File file = new File(store.ready(giga), "encoder.onnx");assertTrue(file.setWritable(true, true));Files.write(file.toPath(), new byte[]{9, 9, 9, 9});
        Host h = new Host();Result<String> user = new Result<>();h.asr(user);Result<Void> setup = new Result<>();
        h.queue.submitIdle(t -> { packs(store, imports).prepare(t, m -> {});return null; }, setup, () -> h.waits++);h.drain();
        assertEquals(1, user.success);assertEquals(1, setup.errors);assertEquals("Хэш пакета не совпал", setup.error);assertEquals(0, setup.success);assertEquals(Arrays.asList("silero", "gigaam"), imports);assertFalse(h.queue.busy());
    }
    @Test public void coldIdleSetupStillImportsAndFullyVerifiesBothExactPacks() throws Exception {
        ModelPackStore store = store();List<String> imports = new ArrayList<>();Host h = new Host();Result<Void> setup = new Result<>();
        h.queue.submitIdle(t -> { packs(store, imports).prepare(t, m -> {});return null; }, setup, () -> h.waits++);h.drain();
        assertEquals(Arrays.asList("silero", "gigaam"), imports);assertNotNull(store.ready(silero));assertNotNull(store.ready(giga));assertEquals(1, setup.success);assertEquals(0, setup.errors);
    }
    @Test public void yieldBetweenPackCommitsReusesFirstWithoutReimportOrEarlyDefaults() throws Exception {
        ModelPackStore store = store();List<String> imports = new ArrayList<>();Host h = new Host();Result<String> user = new Result<>();Result<Void> setup = new Result<>();boolean[] injected = {false};
        h.queue.submitIdle(t -> {
            packs(store, imports).prepare(t, message -> {
                if (!injected[0] && message.contains("2/2")) { injected[0] = true;assertNotNull(store.ready(silero));h.asr(user); }
            });return null;
        }, setup, () -> h.waits++);h.drain();
        assertEquals(Arrays.asList("silero", "gigaam"), imports);assertEquals(1, user.success);assertEquals(1, setup.success);assertEquals(0, setup.errors);assertNotNull(store.ready(giga));
    }
    @Test public void repeatedEnsureOfSameOwnerCoalescesOneWorkAndOneCompletion() {
        Host h = new Host();Result<Void> setup = new Result<>();ModelPackStore.Token token = h.setup(setup);assertSame(token, h.setup(setup));
        h.drain();assertEquals(1, h.attempts);assertEquals(1, setup.success);assertEquals(0, setup.errors);
    }
    @Test public void idleSetupDoesNotStealExistingForegroundReservation() {
        Host h = new Host();Result<String> spec = new Result<>();h.queue.submit(true, t -> "ahead", spec);
        Result<String> user = new Result<>();ModelPackStore.Token asr = h.asr(user);Result<Void> setup = new Result<>();h.setup(setup);
        Result<String> extra = new Result<>();h.asr(extra);h.drain();
        assertFalse(asr.cancelled());assertEquals(1, user.success);assertEquals(1, extra.errors);assertEquals(1, setup.success);assertEquals(0, setup.errors);assertEquals(Arrays.asList("asr", "verify"), h.events);
    }
    @Test public void genuineAssetImportFailureIsNotRetriedAsTransientBusy() throws Exception {
        ModelPackStore store = store();Host h = new Host();Result<Void> setup = new Result<>();
        h.queue.submitIdle(t -> {
            new BundledPacks(store, new PackCatalog[]{silero, giga}, p -> { throw new IOException("missing bundled asset"); }, () -> Long.MAX_VALUE).prepare(t, m -> {});return null;
        }, setup, () -> h.waits++);h.drain();
        assertEquals(1, setup.errors);assertEquals("missing bundled asset", setup.error);assertEquals(0, setup.success);assertFalse(h.queue.busy());assertNull(store.ready(silero));
    }
}

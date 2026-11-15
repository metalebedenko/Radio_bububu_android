package ru.bububu.radio.models;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

public class BundledPacksTest {
    private final byte[] silero = {1, 2, 3}, giga = {4, 5, 6, 7, 8};
    private final PackCatalog first = new PackCatalog("silero", 1, new PackCatalog.Entry("core.pt", silero.length, ModelPackStore.hash(silero)));
    private final PackCatalog second = new PackCatalog("gigaam", 1, new PackCatalog.Entry("encoder.onnx", giga.length, ModelPackStore.hash(giga)));
    private ModelPackStore store() throws IOException { return new ModelPackStore(Files.createTempDirectory("bundled-test").toFile(), dir -> {}); }
    private byte[] archive(PackCatalog pack) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "manifest.txt", pack.manifest());
            put(zip, pack.files.get(0).name, pack == first ? silero : giga);
        }
        return bytes.toByteArray();
    }
    private void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);entry.setMethod(ZipEntry.STORED);entry.setSize(data.length);
        CRC32 crc = new CRC32();crc.update(data);entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);zip.write(data);zip.closeEntry();
    }
    private BundledPacks setup(ModelPackStore store, List<String> opens) {
        return new BundledPacks(store, new PackCatalog[]{first, second}, pack -> {
            opens.add(pack.kind);return new ByteArrayInputStream(archive(pack));
        }, () -> Long.MAX_VALUE);
    }
    private void ready(ModelPackStore store, PackCatalog pack) throws IOException {
        store.importPack(pack, new ByteArrayInputStream(archive(pack)), new ModelPackStore.Token());
    }
    private void fails(BundledPacks setup, ModelPackStore.Token token) throws IOException {
        try { setup.prepare(token, message -> {});fail("Must not report both packs ready"); } catch (IOException expected) {}
    }

    @Test public void freshSetupPublishesBothSequentiallyAndLeavesOuterTokenCancellable() throws Exception {
        ModelPackStore store = store();List<String> opens = new ArrayList<>(), progress = new ArrayList<>();
        ModelPackStore.Token token = new ModelPackStore.Token();
        setup(store, opens).prepare(token, message -> {
            progress.add(message);
            if (message.contains("2/2")) { assertNotNull(store.ready(first));assertNull(store.ready(second)); }
        });
        assertEquals(Arrays.asList("silero", "gigaam"), opens);assertEquals(2, progress.size());
        assertArrayEquals(silero, Files.readAllBytes(new File(store.ready(first), "core.pt").toPath()));
        assertArrayEquals(giga, Files.readAllBytes(new File(store.ready(second), "encoder.onnx").toPath()));
        assertTrue(token.cancel()); // A pack's atomic commit never consumes the outer job token.
    }

    @Test public void restartFullyVerifiesReuseWithoutOpeningAssetsOrNeedingStagingSpace() throws Exception {
        ModelPackStore store = store();ready(store, first);ready(store, second);
        File a = store.ready(first), b = store.ready(second);
        new BundledPacks(store, new PackCatalog[]{first, second}, p -> { throw new AssertionError("No reimport"); }, () -> { throw new AssertionError("No staging"); })
                .prepare(new ModelPackStore.Token(), m -> {});
        assertEquals(a, store.ready(first));assertEquals(b, store.ready(second));
    }

    @Test public void sameSizeCorruptionIsNotAcceptedAsReadyAndNeverSilentlyReplaced() throws Exception {
        ModelPackStore store = store();ready(store, first);File dir = store.ready(first), file = new File(dir, "core.pt");
        assertTrue(file.setWritable(true, true));Files.write(file.toPath(), new byte[]{9, 9, 9});
        assertNotNull(store.ready(first)); // Metadata-only readiness is intentionally insufficient.
        List<String> opens = new ArrayList<>();fails(setup(store, opens), new ModelPackStore.Token());
        assertTrue(opens.isEmpty());assertEquals(dir, store.ready(first));assertNull(store.ready(second));
        assertArrayEquals(new byte[]{9, 9, 9}, Files.readAllBytes(file.toPath()));
    }

    @Test public void missingAssetAfterPartialReadyPreservesReadyAndUnrelatedData() throws Exception {
        ModelPackStore store = store();ready(store, first);File old = store.ready(first);
        File note = File.createTempFile("bundled-original-preserved", ".wav");Files.write(note.toPath(), new byte[]{7, 8, 9});
        fails(new BundledPacks(store, new PackCatalog[]{first, second}, p -> { throw new IOException("asset missing"); }, () -> Long.MAX_VALUE), new ModelPackStore.Token());
        assertEquals(old, store.ready(first));assertNull(store.ready(second));assertArrayEquals(new byte[]{7, 8, 9}, Files.readAllBytes(note.toPath()));
    }

    @Test public void cancelBetweenPacksPreservesFirstAndNeverOpensSecond() throws Exception {
        ModelPackStore store = store();List<String> opens = new ArrayList<>();ModelPackStore.Token token = new ModelPackStore.Token();
        try {
            setup(store, opens).prepare(token, message -> { if (message.contains("2/2")) assertTrue(token.cancel()); });fail();
        } catch (IOException expected) {}
        assertEquals(Collections.singletonList("silero"), opens);assertNotNull(store.ready(first));assertNull(store.ready(second));
    }

    @Test public void cancellationDuringCopyReachesChildImportAndCannotPublish() throws Exception {
        ModelPackStore store = store();ModelPackStore.Token token = new ModelPackStore.Token();
        fails(new BundledPacks(store, new PackCatalog[]{first, second}, p -> new ByteArrayInputStream(archive(p)) {
            @Override public synchronized int read(byte[] b, int off, int len) { int n = super.read(b, off, len);token.cancel();return n; }
        }, () -> Long.MAX_VALUE), token);
        assertNull(store.ready(first));assertNull(store.ready(second));
    }

    @Test public void lowSpaceSecondPackCanRetryWithoutRepeatingFirstImport() throws Exception {
        ModelPackStore store = store();AtomicInteger checks = new AtomicInteger();List<String> opens = new ArrayList<>();
        fails(new BundledPacks(store, new PackCatalog[]{first, second}, p -> { opens.add(p.kind);return new ByteArrayInputStream(archive(p)); },
                () -> checks.incrementAndGet() == 1 ? Long.MAX_VALUE : BundledPacks.requiredFree(second) - 1), new ModelPackStore.Token());
        File old = store.ready(first);assertNotNull(old);assertNull(store.ready(second));
        setup(store, opens).prepare(new ModelPackStore.Token(), m -> {});
        assertEquals(Arrays.asList("silero", "gigaam"), opens);assertEquals(old, store.ready(first));assertNotNull(store.ready(second));
    }

    @Test public void truncatedSecondArchiveNeverCompletesButValidRetryReusesFirst() throws Exception {
        ModelPackStore store = store();
        fails(new BundledPacks(store, new PackCatalog[]{first, second}, p -> {
            byte[] data = archive(p);return new ByteArrayInputStream(p == second ? Arrays.copyOf(data, data.length - 1) : data);
        }, () -> Long.MAX_VALUE), new ModelPackStore.Token());
        File old = store.ready(first);assertNotNull(old);assertNull(store.ready(second));
        List<String> opens = new ArrayList<>();setup(store, opens).prepare(new ModelPackStore.Token(), m -> {});
        assertEquals(Collections.singletonList("gigaam"), opens);assertEquals(old, store.ready(first));
    }

    @Test public void preCancelledReuseDoesNotReadOrAlterAnything() throws Exception {
        ModelPackStore store = store();ready(store, first);ready(store, second);File old = store.ready(first);
        ModelPackStore.Token token = new ModelPackStore.Token();assertTrue(token.cancel());List<String> opens = new ArrayList<>();
        fails(setup(store, opens), token);assertTrue(opens.isEmpty());assertEquals(old, store.ready(first));assertNotNull(store.ready(second));
    }

    @Test public void linkedCancellationRespectsAtomicCommitAndDoesNotConsumeParent() throws Exception {
        ModelPackStore.Token parent = new ModelPackStore.Token(), child = new ModelPackStore.Token(parent);
        assertTrue(parent.cancel());assertTrue(child.cancelled());
        try { child.beginCommit();fail(); } catch (IOException expected) {}
        parent = new ModelPackStore.Token();child = new ModelPackStore.Token(parent);child.beginCommit();
        assertTrue(parent.cancel());assertFalse(child.cancelled());child.done();assertFalse(child.cancel());
    }
}

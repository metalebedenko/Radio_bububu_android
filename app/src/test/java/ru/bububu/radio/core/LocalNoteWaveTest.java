package ru.bububu.radio.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real private-file checks and WAV IO; no Android, recorder, model or neural runtime. */
public final class LocalNoteWaveTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private File root() throws IOException { return temporary.getRoot().getCanonicalFile(); }
    private File wave(long durationMs) throws IOException {
        File root = root(); String id = UUID.randomUUID().toString();
        File output = new File(root, id + ".wav");
        try (PcmWave.Writer writer = new PcmWave.Writer(new File(root, id + ".wav.part"))) {
            byte[] block = new byte[3200];
            for (long left = durationMs * 32; left > 0;) {
                int count = (int) Math.min(left, block.length); writer.append(block, 0, count); left -= count;
            }
            writer.finish(output);
        }
        return output;
    }
    private String id(File file) { return file.getName().substring(0, 36); }

    @Test public void exactlyThirtySecondsReturnsExactCompleteWaveAndLeavesOriginalUnchanged() throws Exception {
        File original = wave(30_000); byte[] before = Files.readAllBytes(original.toPath());
        assertEquals(960044, before.length);
        assertArrayEquals(before, PcmWave.readLocalOriginal(root(), id(original), () -> false));
        assertArrayEquals(before, Files.readAllBytes(original.toPath()));
        assertEquals(120_000, PcmWave.MAX_DURATION_MS);
    }
    @Test public void longerNotesThroughFullTwoMinutesReturnEveryByteWithoutTruncation() throws Exception {
        for (long duration : new long[]{30_001, 120_000}) {
            File original = wave(duration); byte[] before = Files.readAllBytes(original.toPath());
            assertArrayEquals(before, PcmWave.readLocalOriginal(root(), id(original), () -> false));
            assertArrayEquals(before, Files.readAllBytes(original.toPath()));
            assertEquals(duration, PcmWave.readDuration(original));
        }
        assertEquals(120_000, PcmWave.LOCAL_TRANSCRIPTION_MS);
        assertEquals(3_840_044, PcmWave.LOCAL_TRANSCRIPTION_BYTES);
    }
    @Test public void oneSampleBeyondTwoMinutesRejectsAndPreservesWholeOriginal() throws Exception {
        File original = wave(120_000);
        try (RandomAccessFile file = new RandomAccessFile(original, "rw")) { file.seek(file.length()); file.writeShort(0); }
        byte[] before = Files.readAllBytes(original.toPath());
        IOException error = assertThrows(IOException.class, () -> PcmWave.readLocalOriginal(root(), id(original), () -> false));
        assertTrue(error.getMessage().contains("120"));
        assertArrayEquals(before, Files.readAllBytes(original.toPath()));
    }
    @Test public void rejectsTraversalMissingDirectoryAndNonCanonicalIdentityWithoutTouchingWave() throws Exception {
        File original = wave(100); byte[] before = Files.readAllBytes(original.toPath());
        for (String invalid : new String[]{"../" + id(original), "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", "not-an-id"})
            assertThrows(IOException.class, () -> PcmWave.readLocalOriginal(root(), invalid, () -> false));
        assertThrows(IOException.class, () -> PcmWave.readLocalOriginal(new File(root(), "missing"), id(original), () -> false));
        assertArrayEquals(before, Files.readAllBytes(original.toPath()));
    }
    @Test public void symlinkToExternalOriginalAndDirectoryMasqueradingAsWaveAreRejected() throws Exception {
        File external = wave(100); byte[] before = Files.readAllBytes(external.toPath());
        File approved = temporary.newFolder("approved").getCanonicalFile();
        Files.createSymbolicLink(new File(approved, external.getName()).toPath(), external.toPath());
        assertThrows(IOException.class, () -> PcmWave.readLocalOriginal(approved, id(external), () -> false));
        File directory = new File(approved, UUID.randomUUID() + ".wav"); assertTrue(directory.mkdir());
        assertThrows(IOException.class, () -> PcmWave.readLocalOriginal(approved, id(directory), () -> false));
        assertArrayEquals(before, Files.readAllBytes(external.toPath()));
    }
    @Test public void malformedFormatEmptyAndTruncatedOriginalAreRejectedAndPreserved() throws Exception {
        File empty = wave(0);
        assertThrows(IOException.class, () -> PcmWave.readLocalOriginal(root(), id(empty), () -> false));
        File damaged = wave(100);
        try (RandomAccessFile file = new RandomAccessFile(damaged, "rw")) { file.seek(22); file.write(2); }
        byte[] before = Files.readAllBytes(damaged.toPath());
        assertThrows(IOException.class, () -> PcmWave.readLocalOriginal(root(), id(damaged), () -> false));
        assertArrayEquals(before, Files.readAllBytes(damaged.toPath()));
        File truncated = wave(100);
        try (RandomAccessFile file = new RandomAccessFile(truncated, "rw")) { file.setLength(20); }
        before = Files.readAllBytes(truncated.toPath());
        assertThrows(IOException.class, () -> PcmWave.readLocalOriginal(root(), id(truncated), () -> false));
        assertArrayEquals(before, Files.readAllBytes(truncated.toPath()));
    }
    @Test public void cancelledBeforeOrDuringReadNeverReturnsAudioAndPreservesOriginal() throws Exception {
        File original = wave(1000); byte[] before = Files.readAllBytes(original.toPath());
        assertThrows(IOException.class, () -> PcmWave.readLocalOriginal(root(), id(original), () -> true));
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> PcmWave.readLocalOriginal(root(), id(original), () -> checks.incrementAndGet() > 1));
        assertTrue(checks.get() > 1); assertArrayEquals(before, Files.readAllBytes(original.toPath()));
    }
}

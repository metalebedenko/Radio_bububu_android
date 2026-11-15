package ru.bububu.radio.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class PcmWaveTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File path(String name) { return new File(temporary.getRoot(), name); }

    @Test public void signedSamplesEncodeLittleEndianWithoutDependingOnPlatform() {
        short[] samples = {0, 32767, -32768, -1, 0x1234};
        byte[] bytes = new byte[10];
        assertEquals(10, PcmWave.encodePcm16(samples, samples.length, bytes));
        assertArrayEquals(new byte[] {0, 0, -1, 127, 0, -128, -1, -1, 0x34, 0x12}, bytes);
        try { PcmWave.encodePcm16(samples, 5, new byte[9]); fail(); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void finalizedHeaderAndSamplesRoundTrip() throws Exception {
        File part = path("note.part"), wave = path("note.wav");
        byte[] samples = new byte[32_000];
        for (int n = 0; n < samples.length; n++) samples[n] = (byte) n;
        PcmWave.Writer writer = new PcmWave.Writer(part);
        writer.append(samples, 0, samples.length);
        PcmWave.Info result = writer.finish(wave);
        assertEquals(1000, result.durationMs);
        assertEquals(32_000, result.pcmBytes);
        assertEquals(1000, PcmWave.readDuration(wave));
        byte[] all = Files.readAllBytes(wave.toPath());
        assertArrayEquals(samples, java.util.Arrays.copyOfRange(all, 44, all.length));
        assertFalse(part.exists());
    }

    @Test public void interruptedHeaderRecoversActualPrefixAndOddTail() throws Exception {
        File part = path("interrupted.part"), wave = path("recovered.wav");
        try (PcmWave.Writer writer = new PcmWave.Writer(part)) { writer.append(new byte[16_000], 0, 16_000); }
        try (RandomAccessFile file = new RandomAccessFile(part, "rw")) {
            file.seek(file.length()); file.write(123);
        }
        assertEquals(500, PcmWave.recover(part, wave).durationMs);
        assertEquals(16_044, wave.length());
        assertEquals(500, PcmWave.readDuration(wave));
    }

    @Test public void exactTwoMinuteLimitAcceptedThenRejectedWithoutDamage() throws Exception {
        File part = path("limit.part"), wave = path("limit.wav");
        PcmWave.Writer writer = new PcmWave.Writer(part);
        byte[] max = new byte[(int) PcmWave.MAX_PCM_BYTES];
        writer.append(max, 0, max.length);
        try { writer.append(new byte[2], 0, 2); fail(); } catch (IOException expected) { }
        assertEquals(PcmWave.MAX_PCM_BYTES, writer.pcmBytes());
        assertEquals(120_000, writer.finish(wave).durationMs);
    }

    @Test public void existingOriginalAndDraftNeverOverwritten() throws Exception {
        File part = path("existing.part"), wave = temporary.newFile("existing.wav");
        PcmWave.Writer writer = new PcmWave.Writer(part);
        writer.append(new byte[100], 0, 100);
        try { new PcmWave.Writer(part); fail(); } catch (IOException expected) { }
        try { writer.finish(wave); fail(); } catch (IOException expected) { }
        assertEquals(144, part.length());
        assertEquals(0, wave.length());
    }

    @Test public void incompleteHeaderCannotBecomeValidOriginal() throws Exception {
        File part = temporary.newFile("short.part"), wave = path("short.wav");
        Files.write(part.toPath(), new byte[43]);
        try { PcmWave.recover(part, wave); fail(); } catch (IOException expected) { }
        assertTrue(part.exists()); assertFalse(wave.exists());
    }

    @Test public void malformedFinalHeaderIsRejected() throws Exception {
        File part = path("bad.part"), wave = path("bad.wav");
        PcmWave.Writer writer = new PcmWave.Writer(part);
        writer.append(new byte[100], 0, 100); writer.finish(wave);
        try (RandomAccessFile file = new RandomAccessFile(wave, "rw")) { file.seek(22); file.write(2); }
        try { PcmWave.readDuration(wave); fail(); } catch (IOException expected) { }
    }

    @Test public void oddWritesRejectedAndEmptyRecordingStaysEmpty() throws Exception {
        File part = path("empty.part"), wave = path("empty.wav");
        PcmWave.Writer writer = new PcmWave.Writer(part);
        try { writer.append(new byte[3], 0, 3); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(0, writer.finish(wave).pcmBytes);
        assertEquals(0, PcmWave.readDuration(wave));
    }

    @Test public void renameFailureKeepsRecoverablePrefix() throws Exception {
        File part = path("move.part");
        PcmWave.Writer writer = new PcmWave.Writer(part);
        writer.append(new byte[320], 0, 320);
        try { writer.finish(new File(path("missing-dir"), "out.wav")); fail(); } catch (IOException expected) { }
        assertTrue(part.exists());
        assertEquals(10, PcmWave.recover(part, path("retry.wav")).durationMs);
    }
}

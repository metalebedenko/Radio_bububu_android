package ru.bububu.radio.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.RandomAccessFile;
import static org.junit.Assert.*;

public final class SegmentedFileTranscriptTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void onlyCompleteEofAndSegmentedEndExposeJoinedText() {
        SegmentedFileTranscript transcript = new SegmentedFileTranscript(); transcript.segment("Начало"); transcript.segment("Конец");
        rejects(() -> transcript.complete(320, 100, false, 0, 10));
        rejects(() -> transcript.complete(320, 320, false, 10, 11));
        rejects(() -> transcript.complete(320, 320, true, 10, 9));
        assertEquals("Начало Конец", transcript.complete(320, 320, true, 10, 11));
    }
    @Test public void emptyOversizedTranscriptAndOversizedInputAreNeverReady() {
        SegmentedFileTranscript transcript = new SegmentedFileTranscript();
        rejects(() -> transcript.complete(320, 320, true, 10, 11));
        transcript.segment("x".repeat(NoteBook.MAX_TEXT));
        rejects(() -> transcript.segment("ещё"));
        rejects(() -> transcript.complete(PcmWave.MAX_PCM_BYTES + 2, PcmWave.MAX_PCM_BYTES + 2, true, 10, 11));
    }
    @Test public void sameValidatedWaveDescriptorStartsAtRawPcmAndRejectsChangedHeader() throws Exception {
        File part = new File(temporary.getRoot(), "own.wav.part"), wave = new File(temporary.getRoot(), "own.wav");
        byte[] pcm = new byte[] {1, 2, 3, 4};
        try (PcmWave.Writer writer = new PcmWave.Writer(part)) { writer.append(pcm, 0, pcm.length); writer.finish(wave); }
        try (RandomAccessFile input = new RandomAccessFile(wave, "r")) {
            assertEquals(4, PcmWave.readInfo(input).pcmBytes); assertEquals(PcmWave.HEADER_BYTES, input.getFilePointer());
            byte[] raw = new byte[4]; input.readFully(raw); assertArrayEquals(pcm, raw); assertEquals(-1, input.read());
        }
        try (RandomAccessFile changed = new RandomAccessFile(wave, "rw")) { changed.seek(24); changed.write(0); }
        try (RandomAccessFile input = new RandomAccessFile(wave, "r")) {
            try { PcmWave.readInfo(input); fail("Changed format must be rejected"); } catch (java.io.IOException expected) { }
        }
    }
    private static void rejects(Runnable action) { try { action.run(); fail("Incomplete protocol must reject"); } catch (IllegalArgumentException | IllegalStateException expected) { } }
}

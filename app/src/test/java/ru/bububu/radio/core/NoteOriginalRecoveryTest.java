package ru.bububu.radio.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class NoteOriginalRecoveryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void orphanPartialKeepsItsDateAndIsAdoptedOnlyOnce() throws Exception {
        File dir = temporary.newFolder();
        String id = UUID.randomUUID().toString();
        File part = new File(dir, id + ".wav.part");
        try (PcmWave.Writer out = new PcmWave.Writer(part)) { out.append(new byte[16000], 0, 16000); }
        long captured = 1600000000000L;
        Files.setLastModifiedTime(part.toPath(), FileTime.fromMillis(captured));

        NoteBookTest.MemoryStorage storage = new NoteBookTest.MemoryStorage();
        NoteBook book = new NoteBook(storage);
        NoteOriginalRecovery.recover(dir, book);
        NoteBook.Note recovered = book.find(id);
        assertEquals(captured, recovered.createdAt);
        assertEquals(500, recovered.durationMs);
        assertTrue(recovered.hasAudio);
        assertEquals(NoteBook.INTERRUPTED, recovered.recordingStatus);
        assertFalse(part.exists());
        File original = new File(dir, id + ".wav");
        byte[] wav = Files.readAllBytes(original.toPath());
        byte[] metadata = storage.load().clone();
        NoteBook reopened = new NoteBook(storage);
        NoteOriginalRecovery.recover(dir, reopened);
        assertEquals(1, reopened.notes().size());
        assertArrayEquals(metadata, storage.load());
        assertArrayEquals(wav, Files.readAllBytes(original.toPath()));
    }

    @Test public void symlinksCannotBeRepairedOrAdoptedAsOriginals() throws Exception {
        File dir = temporary.newFolder();
        String id = UUID.randomUUID().toString();
        File part = new File(dir, id + ".wav.part"), wave = new File(dir, id + ".wav");
        File target = temporary.newFile();
        byte[] untouched = new byte[101];
        java.util.Arrays.fill(untouched, (byte) 37);
        Files.write(target.toPath(), untouched);
        Files.createSymbolicLink(part.toPath(), target.toPath());
        assertThrows(IOException.class, () -> PcmWave.recover(part, wave));
        assertTrue(Files.isSymbolicLink(part.toPath()));
        assertArrayEquals(untouched, Files.readAllBytes(target.toPath()));
        assertFalse(wave.exists());

        File validTarget = new File(temporary.getRoot(), "valid.wav");
        try (PcmWave.Writer out = new PcmWave.Writer(new File(temporary.getRoot(), "valid.part"))) {
            out.append(new byte[3200], 0, 3200);
            out.finish(validTarget);
        }
        Files.createSymbolicLink(wave.toPath(), validTarget.toPath());
        NoteBook book = new NoteBook(new NoteBookTest.MemoryStorage());
        NoteOriginalRecovery.recover(dir, book);
        assertTrue(book.notes().isEmpty());
        assertThrows(IOException.class, () -> PcmWave.readInfo(wave));
        assertTrue(Files.isSymbolicLink(wave.toPath()));
        assertArrayEquals(untouched, Files.readAllBytes(target.toPath()));
    }

    @Test public void danglingDestinationAndSymlinkDirectoryLeaveExistingDataAlone() throws Exception {
        File dir = temporary.newFolder();
        File part = new File(dir, "test.part"), wave = new File(dir, "test.wav");
        try (PcmWave.Writer out = new PcmWave.Writer(part)) { out.append(new byte[100], 0, 100); }
        byte[] untouched = Files.readAllBytes(part.toPath());
        Files.createSymbolicLink(wave.toPath(), new File(dir, "absent").toPath());
        assertThrows(IOException.class, () -> PcmWave.recover(part, wave));
        assertTrue(Files.isSymbolicLink(wave.toPath()));
        Path linkedDir = Files.createSymbolicLink(new File(temporary.getRoot(), "alias").toPath(), dir.toPath());
        assertThrows(IOException.class, () -> PcmWave.recover(linkedDir.resolve("test.part").toFile(), new File(dir, "new.wav")));
        assertThrows(IllegalStateException.class, () -> NoteOriginalRecovery.recover(linkedDir.toFile(), new NoteBook(new NoteBookTest.MemoryStorage())));
        assertArrayEquals(untouched, Files.readAllBytes(part.toPath()));
    }
}

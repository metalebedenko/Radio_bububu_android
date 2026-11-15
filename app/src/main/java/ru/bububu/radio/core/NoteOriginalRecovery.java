package ru.bububu.radio.core;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.UUID;

/** Recover app-private note originals only after the storage owner reconciles pending deletions. */
public final class NoteOriginalRecovery {
    private NoteOriginalRecovery() { }

    public static void recover(File directory, NoteBook book) {
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Unsafe notes directory");
        File[] files = directory.listFiles();
        if (files == null) throw new IllegalStateException("Notes directory unreadable");
        for (File candidate : files) {
            String name = candidate.getName();
            boolean partial = name.endsWith(".wav.part");
            if (!partial && !name.endsWith(".wav")) continue;
            String id = name.substring(0, name.length() - (partial ? 9 : 4));
            try { if (!UUID.fromString(id).toString().equals(id)) continue; }
            catch (IllegalArgumentException invalid) { continue; }
            File wav = new File(directory, id + ".wav");
            NoteBook.Note note = book.find(id);
            long duration, recordedAt;
            try {
                recordedAt = PcmWave.regularAttributes(candidate).lastModifiedTime().toMillis();
                if (partial) PcmWave.recover(candidate, wav);
                duration = PcmWave.readDuration(wav);
            } catch (Exception failure) {
                // Preserve even an unreadable original; do not replace the whole book with empty data.
                if (note != null && NoteBook.RECORDING.equals(note.recordingStatus)) {
                    book.finishRecording(id, 0, false, true, "Часть записи недоступна; файл сохранён для восстановления");
                }
                continue;
            }
            // A metadata failure must escape to the read-only error state, rather than silently
            // hiding a saved original. Audio parsing failures alone are handled above.
            if (partial || note == null || NoteBook.RECORDING.equals(note.recordingStatus)) {
                book.recoverOriginal(id, recordedAt, duration, duration > 0,
                        "Запись восстановлена после прерывания");
            } else if (NoteBook.INTERRUPTED.equals(note.recordingStatus) && !note.hasAudio && duration > 0) {
                book.reconcileAvailableOriginal(id, duration);
            }
        }
        for (NoteBook.Note note : book.notes()) {
            if (NoteBook.RECORDING.equals(note.recordingStatus)
                    && Files.notExists(new File(directory, note.id + ".wav").toPath(), LinkOption.NOFOLLOW_LINKS)
                    && Files.notExists(new File(directory, note.id + ".wav.part").toPath(), LinkOption.NOFOLLOW_LINKS)) {
                book.finishRecording(note.id, 0, false, true, "Запись прервана до сохранения звука");
            }
            if ("CHECKING".equals(note.recognitionStatus)) {
                book.setRecognition(note.id, note.revision, "UNAVAILABLE", "Проверка распознавания прервана; оригинал сохранён", null);
            }
        }
    }
}

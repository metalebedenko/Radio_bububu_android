package ru.bububu.radio.core;

import java.io.IOException;
import java.util.Objects;

/** Inert AtomicFile-layout reader: an IO error never becomes a new, empty metadata book. */
public final class NoteMetadataReader {
    public static final String BASE = "notes-v1.bin";
    public static final String BACKUP = BASE + ".bak";
    public static final String PENDING = BASE + ".new";
    private static final String[] NAMES = {BASE, BACKUP, PENDING};

    public enum Type { ABSENT, REGULAR, OTHER }
    public interface Files {
        /** Must prove the parent is an accessible directory; missing/denied/unknown is an error. */
        void requireAccessibleDirectory() throws IOException;
        /** ABSENT is allowed only for a proven ENOENT. Access and inspection errors must throw. */
        Type inspect(String name) throws IOException;
        byte[] read(String name) throws IOException;
    }

    private NoteMetadataReader() {}

    public static byte[] load(Files files) throws IOException {
        Objects.requireNonNull(files, "files");
        files.requireAccessibleDirectory();
        Type[] layout = inspect(files);
        byte[][] contents = new byte[NAMES.length][];
        // Read before any recovery mutation. In particular, preserve .new/.bak if base read fails.
        for (int i = 0; i < NAMES.length; i++) if (layout[i] == Type.REGULAR) {
            contents[i] = files.read(NAMES[i]);
            if (contents[i] == null || contents[i].length > NoteBook.MAX_METADATA_BYTES)
                throw new IOException("Invalid notes metadata read");
        }
        files.requireAccessibleDirectory();
        Type[] verified = inspect(files);
        for (int i = 0; i < NAMES.length; i++) if (verified[i] != layout[i])
            throw new IOException("Notes metadata layout changed during read");
        // Legacy AtomicFile backups are the last committed value. Keep recovery files intact;
        // AtomicFile's existing save transaction performs its usual consolidation later.
        if (contents[1] != null) return contents[1];
        if (contents[0] != null) return contents[0];
        if (contents[2] != null) throw new IOException("Only unfinished notes metadata is present");
        return null; // All three paths were proved absent under an accessible directory.
    }

    private static Type[] inspect(Files files) throws IOException {
        Type[] result = new Type[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            result[i] = files.inspect(NAMES[i]);
            if (result[i] == null || result[i] == Type.OTHER)
                throw new IOException("Notes metadata path has an unexpected type");
        }
        return result;
    }
}

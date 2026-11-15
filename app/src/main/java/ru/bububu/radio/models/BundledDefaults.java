package ru.bububu.radio.models;

import java.io.IOException;

/** One confirmed preference transaction, then cache adoption; never the per-engine rollback persister. */
public final class BundledDefaults {
    public interface Store {
        Boolean voice();
        Boolean notes();
        boolean commit(boolean voice, boolean notes);
    }
    public interface Selection {
        void check(boolean voice, boolean notes) throws IOException;
        void apply(boolean voice, boolean notes) throws IOException;
    }
    private BundledDefaults() {}
    public static void finish(Store store, Selection selection) throws IOException {
        Boolean savedVoice = store.voice(), savedNotes = store.notes();
        boolean voice = savedVoice == null || savedVoice, notes = savedNotes == null || savedNotes;
        selection.check(voice, notes);
        // A failed Android commit may change memory. Always retry the transaction and adoption.
        if (!store.commit(voice, notes)) throw new IOException("Выбор моделей не сохранён");
        selection.apply(voice, notes);
    }
}

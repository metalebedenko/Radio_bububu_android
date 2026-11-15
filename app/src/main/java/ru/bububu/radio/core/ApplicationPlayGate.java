package ru.bububu.radio.core;

import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/** One-shot application Play, tied to the note-audio choice current at registration.
 * Does not own started-state, playback resources, or retained system-session capabilities.
 */
public final class ApplicationPlayGate {
    private final Thread owner = Thread.currentThread();
    private final LongSupplier noteCommandRevision;
    private Pending pending;

    public ApplicationPlayGate(LongSupplier noteCommandRevision) {
        this.noteCommandRevision = Objects.requireNonNull(noteCommandRevision);
    }

    public String reserve() {
        check();
        pending = new Pending(UUID.randomUUID().toString(), noteCommandRevision.getAsLong());
        return pending.id;
    }

    /** Called before any output/notes mutation. Old, canceled, and duplicate delivery is inert. */
    public boolean take(String requestedId) {
        check();
        Pending candidate = pending;
        if (candidate == null || !candidate.id.equals(requestedId)) return false;
        long revision = noteCommandRevision.getAsLong();
        if (pending != candidate) return false; // Preserve a new request if the supplied port reenters.
        pending = null;
        return candidate.revision == revision;
    }

    /** Registration failure must not revoke a newer request. */
    public boolean abandon(String requestedId) {
        check();
        if (pending == null || !pending.id.equals(requestedId)) return false;
        pending = null; return true;
    }

    public void cancel() { check(); pending = null; }
    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Application Play owner thread");
    }
    private static final class Pending {
        final String id;
        final long revision;
        Pending(String id, long revision) { this.id = id; this.revision = revision; }
    }
}

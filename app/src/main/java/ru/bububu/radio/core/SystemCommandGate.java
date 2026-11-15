package ru.bububu.radio.core;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Revocable identity for commands from a retained system session or notification.
 * All calls and supplied checks belong to the playback owner thread. An explicit
 * Play from the open app uses its separate route; this gate never opens a session.
 */
public final class SystemCommandGate {
    private final BooleanSupplier sessionUsable;
    private final BooleanSupplier closeIfExpired;
    private String sessionId;

    public SystemCommandGate(BooleanSupplier sessionUsable, BooleanSupplier closeIfExpired) {
        this.sessionUsable = Objects.requireNonNull(sessionUsable, "sessionUsable");
        this.closeIfExpired = Objects.requireNonNull(closeIfExpired, "closeIfExpired");
    }

    /** A fresh identity also prevents a PendingIntent from aliasing a recreated service. */
    public String openSession() {
        sessionId = UUID.randomUUID().toString();
        return sessionId;
    }

    public String currentSessionId() { return sessionId; }

    public void revokeSession() { sessionId = null; }

    public boolean accept(String requestedId) {
        // Reject an old capability before checks that could close or mutate the new session.
        if (sessionId == null || !sessionId.equals(requestedId)) return false;
        if (!sessionUsable.getAsBoolean()) return false;
        if (closeIfExpired.getAsBoolean()) return false;
        // Cleanup/check ports may reenter and replace the session synchronously.
        return requestedId.equals(sessionId) && sessionUsable.getAsBoolean();
    }
}

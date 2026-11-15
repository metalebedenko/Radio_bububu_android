package ru.bububu.radio.core;

import java.util.function.LongSupplier;

/** A bounded paused foreground session. Passive callbacks do not extend its deadline. */
public final class PausedSessionLease {
    public static final long IDLE_TIMEOUT_MS = 10 * 60_000L;
    private final PlayerController.Scheduler scheduler;
    private final LongSupplier clock;
    private final Runnable closeSession;
    private PlayerController.Cancel timer;
    private long generation;
    private long expiresAtMs;
    private boolean armed;

    public PausedSessionLease(PlayerController.Scheduler scheduler, LongSupplier clock, Runnable closeSession) {
        this.scheduler = scheduler;
        this.clock = clock;
        this.closeSession = closeSession;
    }

    public void update(boolean foreground, boolean playing) {
        if (!foreground || playing) { cancel(); return; }
        if (armed) return;
        armed = true;
        expiresAtMs = clock.getAsLong() + IDLE_TIMEOUT_MS;
        long token = ++generation;
        timer = scheduler.schedule(IDLE_TIMEOUT_MS, () -> expire(token));
    }

    /** Check the elapsed deadline on interaction as Android may defer a Handler in deep sleep. */
    public boolean closeIfExpired() {
        if (!armed || clock.getAsLong() < expiresAtMs) return false;
        cancel();
        closeSession.run();
        return true;
    }

    private void expire(long token) {
        if (!armed || token != generation) return;
        if (closeIfExpired()) return;
        timer = scheduler.schedule(expiresAtMs - clock.getAsLong(), () -> expire(token));
    }

    public void cancel() {
        armed = false;
        expiresAtMs = 0;
        ++generation;
        if (timer != null) timer.cancel();
        timer = null;
    }
}

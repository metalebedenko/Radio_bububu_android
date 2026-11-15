package ru.bububu.radio.sources;

import java.util.UUID;

/** Cancellation belongs to an operation, never to a shared host cooldown. */
public final class RequestScope {
    public final String id = UUID.randomUUID().toString();
    public final long generation, storeEpoch, radioEpoch;
    public final SourceClock clock;
    public final long deadlineMillis;
    private volatile boolean cancelled;
    private Runnable cancellation;
    private volatile java.util.function.Predicate<String> targetPolicy;
    // Coordinator sets this per synchronous fetch. Transport snapshots it for all redirect hops.
    java.util.function.Predicate<String> targetPolicy(){return targetPolicy;}
    void targetPolicy(java.util.function.Predicate<String> policy){targetPolicy=policy;}
    LoadFailureSnapshot loadFailureSnapshot;
    public RequestScope(long generation, long storeEpoch) { this(generation, storeEpoch, SourceClock.SYSTEM, 90_000); }
    public RequestScope(long generation, long storeEpoch, SourceClock clock, long durationMillis) {
        this(generation, storeEpoch, clock, durationMillis, 0);
    }
    public RequestScope(long generation, long storeEpoch, SourceClock clock, long durationMillis, long radioEpoch) {
        this.radioEpoch = radioEpoch;
        if (clock == null || durationMillis <= 0 || durationMillis > 90_000) throw new IllegalArgumentException("scope");
        this.generation = generation; this.storeEpoch = storeEpoch; this.clock = clock;
        deadlineMillis = SourceClock.saturatedAdd(clock.monotonicMillis(), durationMillis);
    }
    public boolean isCancelled() { return cancelled; }
    public void check() throws SourceFailure {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new SourceFailure("CANCELLED");
        if (clock.monotonicMillis() >= deadlineMillis) throw new SourceFailure("TIMEOUT");
    }
    public void cancel() {
        Runnable action;
        synchronized (this) { cancelled = true; action = cancellation; }
        if (action != null) action.run();
    }
    public void bindCancellation(Runnable action) {
        boolean run;
        synchronized (this) { cancellation = action; run = cancelled; }
        if (run && action != null) action.run();
    }
    public synchronized void clearCancellation(Runnable action) {
        if (cancellation == action) cancellation = null;
    }
}

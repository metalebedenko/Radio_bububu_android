package ru.bububu.radio.core;

import java.util.concurrent.atomic.AtomicLong;

/** A delayed bind, tab callback or stop must not replace the latest started Activity. */
public final class ScreenOwner {
    private static final AtomicLong NEXT = new AtomicLong();
    private long newest;
    private boolean active;

    public static long newStart() { return NEXT.incrementAndGet(); }

    public boolean attach(long generation) {
        if (generation < newest || generation <= 0 || (generation == newest && !active)) return false;
        newest = generation;
        active = true;
        return true;
    }

    public boolean owns(long generation) { return active && generation == newest; }

    public boolean detach(long generation) {
        if (!owns(generation)) return false;
        active = false;
        return true;
    }
}

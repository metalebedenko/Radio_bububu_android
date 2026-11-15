package ru.bububu.radio.core;

import java.util.Objects;

/** Pure owner-thread admission over the current authoritative Book, independent of catalog projection. */
public interface PlaybackMembership {
    enum Admission { ALLOWED, NOT_MEMBER, UNVERIFIED }

    /** Exact immutable queue selection captured when its committed material enters the player. */
    final class Binding {
        public final long sequence;
        public final String sourceVersionKey;
        public final String origin;
        public Binding(long sequence, String sourceVersionKey, String origin) {
            if (sequence < 0) throw new IllegalArgumentException("Negative queue sequence");
            this.sequence = sequence;
            this.sourceVersionKey = sourceVersionKey;
            this.origin = Objects.requireNonNull(origin, "origin");
        }
    }

    Binding capture(String stationId, Article article);
    Admission check(String stationId, Article article, Binding binding);
    boolean stationRetired(String stationId);
}

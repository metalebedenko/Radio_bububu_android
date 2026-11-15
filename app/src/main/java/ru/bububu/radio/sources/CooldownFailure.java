package ru.bububu.radio.sources;

/** Shared gate refusal carries time only, never host names or headers into presentation. */
public final class CooldownFailure extends SourceFailure {
    public final long remainingMillis, receivedMonotonicMillis;
    public final boolean excessive;
    public final int status;
    CooldownFailure(String reason, long remaining, SourceClock clock, boolean excessive) {
        this(reason,remaining,clock,excessive,0);
    }
    CooldownFailure(String reason, long remaining, SourceClock clock, boolean excessive,int status) {
        super(reason);
        remainingMillis = remaining;
        receivedMonotonicMillis = clock.monotonicMillis();
        this.excessive = excessive;
        this.status = status;
    }
}

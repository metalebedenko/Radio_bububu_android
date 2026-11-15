package ru.bububu.radio;

import android.os.SystemClock;
import ru.bububu.radio.sources.SourceClock;

/** Source expiry/rate limits include device sleep; wall time is only for dates/persistence. */
final class AndroidSourceClock implements SourceClock {
    @Override public long monotonicMillis() { return SystemClock.elapsedRealtime(); }
    @Override public long wallMillis() { return System.currentTimeMillis(); }
}

package ru.bububu.radio.sources;

public interface SourceClock {
    long monotonicMillis();
    long wallMillis();
    SourceClock SYSTEM = new SourceClock() {
        public long monotonicMillis() { return System.nanoTime() / 1_000_000L; }
        public long wallMillis() { return System.currentTimeMillis(); }
    };
    static long saturatedAdd(long a, long b) {
        if (b < 0 || a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }
}

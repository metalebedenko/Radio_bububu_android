package ru.bububu.silero.stressprobe;

import android.system.Os;
import android.system.OsConstants;

/** Platform-only startup code. No reference to any third-party runtime type. */
final class PageSizeGate {
    private PageSizeGate() {}

    static final class Result {
        private final long bytes;
        private final String errorType;
        private Result(long bytes, String errorType) {
            this.bytes = bytes;
            this.errorType = errorType;
        }
        long bytes() { return bytes; }
        String errorType() { return errorType; }
        boolean passed() { return bytes == 4096L && errorType.isEmpty(); }
        void requirePassed() {
            if (!passed()) throw new IllegalStateException("STOP_UNSUPPORTED_PAGE_SIZE");
        }
    }

    static Result checkPlatform() {
        try {
            return new Result(Os.sysconf(OsConstants._SC_PAGESIZE), "");
        } catch (RuntimeException | LinkageError failure) {
            return new Result(-1L, failure.getClass().getSimpleName());
        }
    }
}

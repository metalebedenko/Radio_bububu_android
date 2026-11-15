package ru.bububu.radio.sources;

import java.io.IOException;

/** A technical failure whose public message never contains an input URL or body. */
public class SourceFailure extends IOException {
    public final String reason;
    public SourceFailure(String reason) { super(reason); this.reason = reason; }
    public SourceFailure(String reason, Throwable cause) { super(reason, cause); this.reason = reason; }
}

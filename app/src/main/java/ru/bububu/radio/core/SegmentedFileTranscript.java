package ru.bububu.radio.core;

/** Bounded transcript; only a complete segmented EOF protocol can expose its text. */
public final class SegmentedFileTranscript {
    private final StringBuilder text = new StringBuilder();
    public void segment(String value) {
        if (value == null || value.trim().isEmpty()) return;
        String part = value.trim();
        int separator = text.length() == 0 ? 0 : 1;
        if (part.length() > NoteBook.MAX_TEXT - text.length() - separator) throw new IllegalArgumentException("Transcript exceeds 20k UTF-16");
        if (separator != 0) text.append(' ');
        text.append(part);
    }
    public String complete(long expectedBytes, long writtenBytes, boolean eofClosed, long eofBeginNanos, long terminalNanos) {
        if (expectedBytes <= 0 || expectedBytes > PcmWave.MAX_PCM_BYTES || writtenBytes != expectedBytes
                || !eofClosed || eofBeginNanos <= 0 || terminalNanos < eofBeginNanos || text.length() == 0)
            throw new IllegalStateException("Incomplete segmented file session");
        return text.toString();
    }
}

package ru.bububu.radio.sources;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

public final class SourceLimits {
    /** Legacy frame-v1 only. Notes retain their independent NoteBook.MAX_TEXT. */
    public static final int MAX_TEXT=20_000, MAX_SOURCE_SPOKEN_UTF16=100_000, MAX_PUBLICATION_CONTEXT=1024,
            MAX_DISPLAY_TITLE=200, MAX_PROVIDER_TITLE=2000,
            MAX_SOURCE_NAME=200, MAX_URL=4096, MAX_PROVIDER_ID=4096, MAX_PARSED_ITEMS=500,
            MAX_NEW_ITEMS=200, MAX_ITEMS_PER_SOURCE=1000, MAX_SOURCES=64,
            MAX_HIDE_IDS_PER_SOURCE=10000, MAX_PROTECTED_VERSIONS=100, MAX_SOURCE_QUEUE=100;
    // Independent persisted/storage budgets; increasing live sources must not expand these.
    public static final int MAX_SOURCE_RECORDS=144, MAX_RETIRED_SOURCES=128,
            MAX_ACTIVE_ITEMS=16000, MAX_SUPPRESSED_ITEMS=160000, MAX_ACTION_TARGETS=16016;
    public static final int MAX_BOOK_BYTES=8*1024*1024, MAX_CACHE_BYTES=16*1024*1024,
            XML_BYTES=2*1024*1024, HTML_BYTES=3*1024*1024, JSON_BYTES=3*1024*1024, MAX_RADIO_BYTES=16*1024*1024;
    public static final long MAX_MANAGED_BYTES=96L*1024*1024, MIN_FREE_BYTES=48L*1024*1024;
    private SourceLimits() {}
    public static String value(String value,int maximum,String name,boolean nullable) {
        if(value==null) { if(nullable) return null; throw new NullPointerException(name); }
        if(value.length()>maximum) throw new IllegalArgumentException(name+" exceeds limit");
        return value;
    }
    public static String id(String value,String name) {
        value(value,512,name,false);
        if(value.trim().isEmpty()||value.startsWith("@after:")||value.startsWith("@selected:")) throw new IllegalArgumentException(name);
        return value;
    }
    public static String sha256(String text) { return hash(Objects.requireNonNull(text).getBytes(StandardCharsets.UTF_8)); }
    public static String hash(byte[] bytes) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder result=new StringBuilder(64);
            for(byte b:digest) result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return result.toString();
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String snapshotVersion(String... fields) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream output=new DataOutputStream(bytes);
            for(String field:fields) { byte[] encoded=Objects.requireNonNull(field).getBytes(StandardCharsets.UTF_8); output.writeInt(encoded.length); output.write(encoded); }
            return hash(bytes.toByteArray());
        } catch(java.io.IOException impossible) { throw new IllegalStateException(impossible); }
    }
}

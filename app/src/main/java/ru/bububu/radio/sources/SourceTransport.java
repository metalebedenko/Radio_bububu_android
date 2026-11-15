package ru.bububu.radio.sources;

public interface SourceTransport {
    enum Kind { XML, HTML, JSON }
    final class Validators {
        public static final Validators NONE = new Validators(null, null, false);
        public final String etag, lastModified;
        public final boolean hasSuccessfulCache;
        public Validators(String etag, String lastModified, boolean hasSuccessfulCache) {
            this.etag = etag; this.lastModified = lastModified; this.hasSuccessfulCache = hasSuccessfulCache;
        }
    }
    final class Response {
        public final byte[] bytes;
        public final String finalUrl, mime, charset, etag, lastModified;
        public final int status;
        public Response(byte[] bytes, String finalUrl, int status, String mime, String charset, String etag, String lastModified) {
            this.bytes = bytes.clone(); this.finalUrl = finalUrl; this.status = status;
            this.mime = mime; this.charset = charset; this.etag = etag; this.lastModified = lastModified;
        }
    }
    default SourceFailureInfo cooldown(String url) { return null; }
    default SourceFailureInfo actionCooldown(String actionKey) { return null; }
    default void associateAction(String actionKey,String checkedTargetUrl) { }
    Response fetch(String url, Kind kind, Validators validators, RequestScope scope) throws SourceFailure;
}

package ru.bububu.radio.sources;

import okhttp3.Headers;

public final class HttpStatusFailure extends SourceFailure {
    public final int status;
    public final Headers headers;
    public final String host, operationId;
    public final long receivedWallMillis,receivedMonotonicMillis;
    public final RetryAfterParser.Result retryAfter;
    public HttpStatusFailure(int status,Headers headers,String host,RequestScope scope,RetryAfterParser.Result retryAfter) {
        super("HTTP_"+status);this.status=status;this.headers=headers;this.host=host;operationId=scope.id;
        receivedWallMillis=scope.clock.wallMillis();receivedMonotonicMillis=scope.clock.monotonicMillis();this.retryAfter=retryAfter;
    }
}

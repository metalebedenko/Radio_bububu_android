package ru.bububu.radio.sources;

import okhttp3.Interceptor;
import okhttp3.Response;
import java.io.IOException;

/** Runs inside RetryAndFollowUpInterceptor; the original error must not reach followUpRequest. */
public final class ErrorResponseInterceptor implements Interceptor {
    private final HostRequestGate gate;private final RequestScope scope;
    public ErrorResponseInterceptor(HostRequestGate gate,RequestScope scope){this.gate=gate;this.scope=scope;}
    @Override public Response intercept(Chain chain)throws IOException {
        Response response=chain.proceed(chain.request());
        if(response.code()<400)return response;
        RetryAfterParser.Result retry=RetryAfterParser.parse(response.headers().values("Retry-After"),scope.clock.wallMillis(),scope.clock.monotonicMillis());
        HttpStatusFailure failure=new HttpStatusFailure(response.code(),response.headers(),response.request().url().host(),scope,retry);
        if(response.code()==429||response.code()==503)try{gate.observe(failure.host,retry);}catch(SourceFailure persistence){failure.addSuppressed(persistence);}
        try{response.close();}catch(RuntimeException close){failure.addSuppressed(close);}
        throw failure;
    }
}

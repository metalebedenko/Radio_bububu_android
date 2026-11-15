package ru.bububu.radio.sources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLHandshakeException;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class DwNetworkDiagnosticsTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String PRIVATE="PRIVATE_MESSAGE_BODY_HEADERS_CREDENTIAL_URL";
    private static final class Clock implements SourceClock {
        long now;public long monotonicMillis(){return now;}public long wallMillis(){return 1000000+now;}
    }
    private static DwNetworkDiagnostics active(Clock clock,AtomicReference<byte[]> bytes) {
        DwNetworkDiagnostics d=new DwNetworkDiagnostics("test-attempt",clock,v->bytes.set(v.clone()));
        assertTrue(d.consume(DwNetworkDiagnostics.INPUT,false,"DW на русском"));return d;
    }
    private static String text(AtomicReference<byte[]> value){assertNotNull(value.get());return new String(value.get(),StandardCharsets.UTF_8);}
    @Test public void exactCatalogOptInIsSingleUseAndExpiresWithoutCapturingOtherInputs() {
        Clock c=new Clock();AtomicReference<byte[]> bytes=new AtomicReference<>();DwNetworkDiagnostics d=new DwNetworkDiagnostics("opt-in",c,bytes::set);
        assertFalse(d.consume("https://owner.example/"+PRIVATE,false,"DW на русском"));
        assertFalse(d.consume(DwNetworkDiagnostics.INPUT,true,"DW на русском"));assertFalse(d.consume(DwNetworkDiagnostics.INPUT,false,null));
        assertFalse(d.consume(DwNetworkDiagnostics.INPUT+"?private="+PRIVATE,false,"DW на русском"));assertNull(bytes.get());
        assertTrue(d.consume(DwNetworkDiagnostics.INPUT,false,"DW на русском"));assertFalse(d.consume(DwNetworkDiagnostics.INPUT,false,"DW на русском"));
        d.success();assertFalse(text(bytes).contains(PRIVATE));assertTrue(text(bytes).contains("RESULT_SUCCESS"));
        DwNetworkDiagnostics expired=new DwNetworkDiagnostics("expired",c,v->fail("expired-write"));c.now=60001;
        assertFalse(expired.consume(DwNetworkDiagnostics.INPUT,false,"DW на русском"));assertEquals("EXPIRED",expired.captureState());
    }
    @Test public void exceptionCaptureNeverCallsMessageAndBoundsCauseDepthWithoutRawReason() {
        Clock c=new Clock();AtomicReference<byte[]> bytes=new AtomicReference<>();DwNetworkDiagnostics d=active(c,bytes);
        IOException chain=new IOException(PRIVATE);for(int i=0;i<12;i++)chain=new IOException(PRIVATE,chain);
        IOException cause=new IOException(PRIVATE,chain){@Override public String getMessage(){throw new AssertionError("message must not be read");}};
        SourceFailure failure=new SourceFailure(PRIVATE,cause);d.networkFailure(failure);d.failure(failure);
        String json=text(bytes);assertFalse(json.contains(PRIVATE));assertFalse(json.contains("stack"));assertFalse(json.contains("message"));
        java.util.regex.Matcher groups=java.util.regex.Pattern.compile("\\\"exception_types\\\":\\[([^]]*)]").matcher(json);
        int found=0;while(groups.find()){found++;assertTrue(groups.group(1).split(",").length<=DwNetworkDiagnostics.MAX_CAUSES);}assertEquals(2,found);
    }
    @Test public void eventsAndBytesAreBoundedAndCallbacksNeverWriteToSink() {
        Clock c=new Clock();AtomicReference<byte[]> bytes=new AtomicReference<>();DwNetworkDiagnostics d=active(c,bytes);
        for(int i=0;i<1000;i++){d.listener().dnsStart(null,PRIVATE);d.networkFailure(new IOException(PRIVATE));}
        assertNull(bytes.get());d.failure(new SourceFailure("NETWORK_ERROR",new IOException(PRIVATE)));
        String json=text(bytes);assertTrue(bytes.get().length<=DwNetworkDiagnostics.MAX_BYTES);assertTrue(json.contains("\"truncated\":true"));
        assertTrue(json.contains("RESULT_FAILURE"));assertTrue(json.split("\\\"stage\\\"",-1).length-1<=DwNetworkDiagnostics.MAX_EVENTS);assertFalse(json.contains(PRIVATE));
    }
    @Test public void httpStatusExistsOnlyAfterObservedHeadersAndOtherResponseFieldsAreDiscarded() {
        Clock c=new Clock();AtomicReference<byte[]> bytes=new AtomicReference<>();DwNetworkDiagnostics failed=active(c,bytes);
        failed.networkFailure(new SSLHandshakeException(PRIVATE));failed.failure(new SourceFailure("NETWORK_ERROR"));assertFalse(text(bytes).contains("http_status"));
        AtomicReference<byte[]> received=new AtomicReference<>();DwNetworkDiagnostics d=active(c,received);
        d.listener().secureConnectStart(null);d.listener().secureConnectEnd(null,null);
        okhttp3.Response response=new okhttp3.Response.Builder().request(new Request.Builder().url(DwNetworkDiagnostics.INPUT).build())
                .protocol(Protocol.HTTP_1_1).code(403).message(PRIVATE).header("Set-Cookie",PRIVATE).build();
        d.listener().responseHeadersEnd(null,response);d.failure(new SourceFailure("HTTP_403"));
        assertTrue(text(received).contains("\"http_status\":403"));assertFalse(text(received).contains(PRIVATE));assertFalse(text(received).contains("rss.dw.com"));
    }
    @Test public void writerFailureCannotEscapeOrWriteAgain() {
        Clock c=new Clock();AtomicInteger writes=new AtomicInteger();DwNetworkDiagnostics d=new DwNetworkDiagnostics("write-error",c,v->{writes.incrementAndGet();throw new IOException(PRIVATE);});
        assertTrue(d.consume(DwNetworkDiagnostics.INPUT,false,"DW на русском"));d.failure(new SourceFailure("NETWORK_ERROR"));
        assertEquals("WRITE_FAILED",d.captureState());d.success();d.failure(new IOException(PRIVATE));assertEquals(1,writes.get());
    }
    @Test public void explicitDisarmAndInvalidAttemptCannotCapture() {
        Clock c=new Clock();DwNetworkDiagnostics d=new DwNetworkDiagnostics("disarm",c,v->fail("disarmed-write"));d.close();
        assertFalse(d.consume(DwNetworkDiagnostics.INPUT,false,"DW на русском"));d.networkFailure(new IOException(PRIVATE));d.success();assertEquals("DISARMED",d.captureState());
        for(String id:new String[]{"../../owner","bad\"json",PRIVATE+"a".repeat(49),""})
            assertThrows(IllegalArgumentException.class,()->new DwNetworkDiagnostics(id,c,v->{}));
    }
    @Test public void transportKeepsOriginalNetworkFailureAndCapturesSafeTypeBeforeMapping() throws Exception {
        Clock c=new Clock();AtomicReference<byte[]> bytes=new AtomicReference<>();DwNetworkDiagnostics d=active(c,bytes);AtomicInteger dns=new AtomicInteger();
        SSLHandshakeException original=new SSLHandshakeException(PRIVATE);
        OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain->{throw original;}).build();
        try(SafeHttpTransport transport=new SafeHttpTransport(client,new PublicIpPolicy(),host->{dns.incrementAndGet();throw new java.net.UnknownHostException(PRIVATE);},new HostRequestGate(new HostCooldownStore(temporary.getRoot()),c))){
            SourceFailure failure=assertThrows(SourceFailure.class,()->transport.fetchObservedForTest(DwNetworkDiagnostics.INPUT,SourceTransport.Kind.HTML,SourceTransport.Validators.NONE,new RequestScope(1,1,c,90000),d));
            assertEquals("NETWORK_ERROR",failure.reason);assertSame(original,failure.getCause());assertEquals(0,dns.get());d.failure(failure);
            String json=text(bytes);assertTrue(json.contains("javax.net.ssl.SSLHandshakeException"));assertTrue(json.contains("NETWORK_ERROR"));assertFalse(json.contains(PRIVATE));assertFalse(json.contains("TLS_BEFORE"));
        }
    }
    @Test public void cyclicCausesNullReasonsAndClockFaultRemainBounded() {
        Clock c=new Clock();AtomicReference<byte[]> bytes=new AtomicReference<>();DwNetworkDiagnostics d=active(c,bytes);
        IOException cycle=new IOException(PRIVATE){@Override public synchronized Throwable getCause(){return this;}};
        d.networkFailure(cycle);c.now=-1;d.failure(new SourceFailure(null,cycle));String json=text(bytes);
        assertTrue(json.contains("\"clock_valid\":false"));assertFalse(json.contains(PRIVATE));assertTrue(bytes.get().length<=DwNetworkDiagnostics.MAX_BYTES);
    }
}

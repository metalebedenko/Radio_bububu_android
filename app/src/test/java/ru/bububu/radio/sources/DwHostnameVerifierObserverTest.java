package ru.bububu.radio.sources;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Synthetic session traps plus one loopback TLS integration; no external requests. */
public final class DwHostnameVerifierObserverTest {
    @Test public void sessionObservationFailurePreservesSameInputsBooleanAndSingleDelegateCall(){
        for(boolean expected:new boolean[]{true,false}){
            String host=new String("private-session-host");SSLSession session=unreadableSession();AtomicInteger calls=new AtomicInteger(),observations=new AtomicInteger();
            HostnameVerifier verifier=DwHostnameVerifierObserver.wrap((h,s)->{assertSame(host,h);assertSame(session,s);calls.incrementAndGet();return expected;},true,stage->{throw new AssertionError("stage");},(h,s)->{assertSame(host,h);assertSame(session,s);observations.incrementAndGet();assertEquals(1,calls.get());throw new AssertionError("summary");});
            assertEquals(expected,verifier.verify(host,session));assertEquals(1,calls.get());assertEquals(1,observations.get());
        }
    }
    @Test public void sessionObservationAfterThrowPreservesOriginalRuntimeAndErrorInstances(){
        for(Throwable expected:new Throwable[]{new IllegalStateException("private-delegate"),new AssertionError("private-delegate")}){
            AtomicInteger calls=new AtomicInteger(),observations=new AtomicInteger();
            HostnameVerifier verifier=DwHostnameVerifierObserver.wrap((h,s)->{calls.incrementAndGet();if(expected instanceof Error)throw (Error)expected;throw (RuntimeException)expected;},true,stage->{},(h,s)->{observations.incrementAndGet();assertEquals(1,calls.get());throw new AssertionError("summary");});
            assertSame(expected,assertThrows(expected.getClass(),()->verifier.verify("private",unreadableSession())));assertEquals(1,calls.get());assertEquals(1,observations.get());
        }
    }
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String PRIVATE="PRIVATE_HOST_SESSION_CERT_MESSAGE";
    private static final class Clock implements SourceClock {
        long now;public long monotonicMillis(){return now;}public long wallMillis(){return 1000000+now;}
    }
    private static SSLSession unreadableSession(){
        return (SSLSession)Proxy.newProxyInstance(SSLSession.class.getClassLoader(),new Class<?>[]{SSLSession.class},
                (proxy,method,args)->{throw new AssertionError("Observer must not read any session field");});
    }
    private static DwNetworkDiagnostics active(Clock clock,AtomicReference<byte[]> bytes){
        DwNetworkDiagnostics d=new DwNetworkDiagnostics("hostname-test",clock,bytes::set);
        assertTrue(d.consume(DwNetworkDiagnostics.INPUT,false,"DW на русском"));return d;
    }
    @Test public void trueDecisionUsesSameHostAndSessionOnceAndRecordsOnlyFixedStages(){
        String host=new String(PRIVATE);SSLSession session=unreadableSession();AtomicInteger calls=new AtomicInteger();List<DwNetworkDiagnostics.Stage> events=new ArrayList<>();
        HostnameVerifier original=(h,s)->{assertSame(host,h);assertSame(session,s);calls.incrementAndGet();return true;};
        HostnameVerifier observed=DwHostnameVerifierObserver.wrap(original,true,events::add);
        assertTrue(observed.verify(host,session));assertEquals(1,calls.get());
        assertEquals(Arrays.asList(DwNetworkDiagnostics.Stage.HOSTNAME_OBSERVER_ARMED,DwNetworkDiagnostics.Stage.HOSTNAME_VERIFY_ENTER,DwNetworkDiagnostics.Stage.HOSTNAME_VERIFY_TRUE),events);
    }
    @Test public void falseDecisionIsReturnedWithoutSecondVerificationOrAdditionalSessionReads(){
        AtomicInteger calls=new AtomicInteger();List<DwNetworkDiagnostics.Stage> events=new ArrayList<>();
        HostnameVerifier observed=DwHostnameVerifierObserver.wrap((h,s)->{calls.incrementAndGet();return false;},true,events::add);
        assertFalse(observed.verify(PRIVATE,unreadableSession()));assertEquals(1,calls.get());
        assertEquals(Arrays.asList(DwNetworkDiagnostics.Stage.HOSTNAME_OBSERVER_ARMED,DwNetworkDiagnostics.Stage.HOSTNAME_VERIFY_ENTER,DwNetworkDiagnostics.Stage.HOSTNAME_VERIFY_FALSE),events);
    }
    @Test public void delegateRuntimeExceptionAndErrorPropagateAsExactSameInstance(){
        RuntimeException failure=new RuntimeException(PRIVATE){@Override public String getMessage(){throw new AssertionError("message read");}};
        AssertionError fatal=new AssertionError(PRIVATE);AtomicInteger calls=new AtomicInteger();List<DwNetworkDiagnostics.Stage> events=new ArrayList<>();
        HostnameVerifier observed=DwHostnameVerifierObserver.wrap((h,s)->{calls.incrementAndGet();throw failure;},true,events::add);
        assertSame(failure,assertThrows(RuntimeException.class,()->observed.verify(PRIVATE,unreadableSession())));
        assertEquals(Arrays.asList(DwNetworkDiagnostics.Stage.HOSTNAME_OBSERVER_ARMED,DwNetworkDiagnostics.Stage.HOSTNAME_VERIFY_ENTER),events);assertEquals(1,calls.get());
        assertSame(fatal,assertThrows(AssertionError.class,()->DwHostnameVerifierObserver.wrap((h,s)->{throw fatal;},true,events::add).verify(PRIVATE,unreadableSession())));
    }
    @Test public void observerFailureAtEveryStageCannotChangeBooleanOrDelegateException(){
        DwHostnameVerifierObserver.Observer broken=stage->{throw new AssertionError(PRIVATE);};
        for(boolean decision:new boolean[]{true,false}){
            AtomicInteger calls=new AtomicInteger();HostnameVerifier verifier=DwHostnameVerifierObserver.wrap((h,s)->{calls.incrementAndGet();return decision;},true,broken);
            assertEquals(decision,verifier.verify(PRIVATE,unreadableSession()));assertEquals(1,calls.get());
        }
        RuntimeException original=new RuntimeException(PRIVATE);
        assertSame(original,assertThrows(RuntimeException.class,()->DwHostnameVerifierObserver.wrap((h,s)->{throw original;},true,broken).verify(PRIVATE,unreadableSession())));
    }
    @Test public void disabledUnconsumedWrongInputExpiredAndDisarmedKeepOriginalObject(){
        HostnameVerifier original=(h,s)->false;AtomicInteger observations=new AtomicInteger();
        assertSame(original,DwHostnameVerifierObserver.wrap(original,false,stage->observations.incrementAndGet()));assertEquals(0,observations.get());
        Clock c=new Clock();DwNetworkDiagnostics d=new DwNetworkDiagnostics("unconsumed",c,v->fail("unexpected IO"));
        assertSame(original,d.observeHostnameVerifier(original));assertFalse(d.consume(DwNetworkDiagnostics.INPUT+"?private="+PRIVATE,false,"DW на русском"));assertSame(original,d.observeHostnameVerifier(original));
        c.now=60001;assertFalse(d.consume(DwNetworkDiagnostics.INPUT,false,"DW на русском"));assertSame(original,d.observeHostnameVerifier(original));
        DwNetworkDiagnostics disarmed=active(new Clock(),new AtomicReference<>());disarmed.close();assertSame(original,disarmed.observeHostnameVerifier(original));
    }
    @Test public void hostnameEventsRetainPrivacyByteEventBoundsAndNeverWriteInCallback(){
        Clock c=new Clock();AtomicReference<byte[]> bytes=new AtomicReference<>();DwNetworkDiagnostics d=active(c,bytes);AtomicInteger calls=new AtomicInteger();
        HostnameVerifier observed=d.observeHostnameVerifier((h,s)->{calls.incrementAndGet();return false;});
        for(int i=0;i<100;i++)assertFalse(observed.verify(PRIVATE,unreadableSession()));
        assertEquals(100,calls.get());assertNull(bytes.get());d.failure(new SourceFailure("NETWORK_ERROR"));
        String json=new String(bytes.get(),StandardCharsets.UTF_8);assertFalse(json.contains(PRIVATE));assertFalse(json.contains("rss.dw.com"));assertTrue(json.contains("HOSTNAME_OBSERVER_ARMED"));assertTrue(json.contains("HOSTNAME_VERIFY_FALSE"));
        assertTrue(json.contains("\"truncated\":true"));assertTrue(json.contains("RESULT_FAILURE"));assertTrue(bytes.get().length<=DwNetworkDiagnostics.MAX_BYTES);assertTrue(json.split("\\\"stage\\\"",-1).length-1<=DwNetworkDiagnostics.MAX_EVENTS);
        byte[] committed=bytes.get();assertFalse(observed.verify(PRIVATE,unreadableSession()));assertSame(committed,bytes.get());
    }
    @Test public void activeDiagnosticProducesCompleteBooleanSequenceAndUnchangedTerminalResult(){
        AtomicReference<byte[]> bytes=new AtomicReference<>();DwNetworkDiagnostics d=active(new Clock(),bytes);
        assertTrue(d.observeHostnameVerifier((h,s)->true).verify(PRIVATE,unreadableSession()));assertNull(bytes.get());d.success();
        String json=new String(bytes.get(),StandardCharsets.UTF_8);assertEquals("COMMITTED",d.captureState());assertFalse(json.contains("\"truncated\":true"));assertTrue(json.indexOf("HOSTNAME_OBSERVER_ARMED")<json.indexOf("HOSTNAME_VERIFY_ENTER"));assertTrue(json.indexOf("HOSTNAME_VERIFY_ENTER")<json.indexOf("HOSTNAME_VERIFY_TRUE"));assertTrue(json.indexOf("HOSTNAME_VERIFY_TRUE")<json.indexOf("RESULT_SUCCESS"));assertFalse(json.contains(PRIVATE));
    }
    @Test(timeout=20000) public void localTlsRejectsOriginalFalseInBothPathsAndOnlyArmedPathRecordsHostname()throws Exception{
        for(boolean armed:new boolean[]{false,true}){
            Clock c=new Clock();AtomicReference<byte[]> bytes=new AtomicReference<>();AtomicInteger calls=new AtomicInteger(),dns=new AtomicInteger();
            HostnameVerifier original=(h,session)->{assertEquals("sources.test",h);assertNotNull(session);calls.incrementAndGet();return false;};
            okhttp3.tls.HeldCertificate certificate=new okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("sources.test").build();
            okhttp3.tls.HandshakeCertificates serverTls=new okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build();
            okhttp3.tls.HandshakeCertificates clientTls=new okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
            OkHttpClient client=new OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager()).hostnameVerifier(original).build();
            try(mockwebserver3.MockWebServer server=new mockwebserver3.MockWebServer();
                SafeHttpTransport transport=new SafeHttpTransport(client,SafeHttpTransportTest.loopbackPolicy(),host->{dns.incrementAndGet();return List.of(java.net.InetAddress.getByName("127.0.0.1"));},new HostRequestGate(new HostCooldownStore(temporary.newFolder()),c))){
                server.useHttps(serverTls.sslSocketFactory());server.start(java.net.InetAddress.getByName("127.0.0.1"),0);
                java.lang.reflect.Field field=SafeHttpTransport.class.getDeclaredField("base");field.setAccessible(true);OkHttpClient base=(OkHttpClient)field.get(transport);
                assertSame(original,base.hostnameVerifier());assertEquals(List.of(Protocol.HTTP_1_1),base.protocols());assertFalse(base.retryOnConnectionFailure());assertFalse(base.fastFallback());assertEquals(0,base.connectionPool().connectionCount());
                DwNetworkDiagnostics diagnostic=armed?active(c,bytes):null;
                String url=server.url("/feed").newBuilder().host("sources.test").build().toString();
                SourceFailure failure=assertThrows(SourceFailure.class,()->transport.fetchObservedForTest(url,SourceTransport.Kind.XML,SourceTransport.Validators.NONE,new RequestScope(1,1,c,90000),diagnostic));
                assertEquals("NETWORK_ERROR",failure.reason);assertTrue(failure.getCause() instanceof javax.net.ssl.SSLPeerUnverifiedException);
                assertEquals(1,calls.get());assertEquals(1,dns.get());assertEquals(0,server.getRequestCount());assertEquals(0,base.connectionPool().connectionCount());
                if(armed){diagnostic.failure(failure);String json=new String(bytes.get(),StandardCharsets.UTF_8);assertTrue(json.contains("HOSTNAME_OBSERVER_ARMED"));assertTrue(json.contains("HOSTNAME_VERIFY_ENTER"));assertTrue(json.contains("HOSTNAME_VERIFY_FALSE"));assertFalse(json.contains("HOSTNAME_VERIFY_TRUE"));assertFalse(json.contains("sources.test"));}else assertNull(bytes.get());
            }
        }
    }
}

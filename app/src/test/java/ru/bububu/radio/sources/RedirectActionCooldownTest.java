package ru.bububu.radio.sources;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Actual TLS A -> B -> original 503. Operation association and host persistence have different lifetimes. */
public class RedirectActionCooldownTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final class Clock implements SourceClock {
        volatile long now=1000;
        public long monotonicMillis(){return now;}
        public long wallMillis(){return 1_800_000_000_000L+now;}
        void advance(long millis){now+=millis;}
    }
    private static final class Trace extends okhttp3.EventListener {
        final AtomicInteger calls=new AtomicInteger(),ended=new AtomicInteger(),failed=new AtomicInteger();
        public void callStart(Call call){calls.incrementAndGet();}
        public void responseBodyEnd(Call call,long bytes){ended.incrementAndGet();}
        public void callFailed(Call call,IOException error){failed.incrementAndGet();}
    }
    private final class Fixture implements AutoCloseable {
        final MockWebServer server=new MockWebServer();final Clock clock=new Clock();final Trace trace=new Trace();
        final File directory;final HostCooldownStore store;HostRequestGate gate;final OkHttpClient client;
        SafeHttpTransport transport;
        Fixture()throws Exception {
            directory=temporary.newFolder();store=new HostCooldownStore(directory);gate=new HostRequestGate(store,clock);
            HeldCertificate certificate=new HeldCertificate.Builder().addSubjectAlternativeName("initial.test").addSubjectAlternativeName("target.test").build();
            HandshakeCertificates serverTls=new HandshakeCertificates.Builder().heldCertificate(certificate).build();
            HandshakeCertificates clientTls=new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
            server.useHttps(serverTls.sslSocketFactory());server.start(InetAddress.getByName("127.0.0.1"),0);
            client=new OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager()).eventListener(trace).build();
            reopen(false);
        }
        String initial(){return server.url("/private-feed").newBuilder().host("initial.test").build().toString();}
        String target(){return server.url("/private-feed").newBuilder().host("target.test").build().toString();}
        RequestScope scope(){return new RequestScope(1,1,clock,90_000);}
        void reopen(boolean freshProcess){
            if(transport!=null)transport.close();
            if(freshProcess)gate=new HostRequestGate(store,clock);
            PublicIpPolicy policy=new PublicIpPolicy(){public boolean isPublic(InetAddress address){return address.getHostAddress().equals("127.0.0.1");}};
            transport=new SafeHttpTransport(client,policy,host->List.of(InetAddress.getByName("127.0.0.1")),gate);
        }
        void refusedResponse(String retry){
            server.enqueue(redirect());
            server.enqueue(new MockResponse.Builder().code(503).addHeader("Retry-After",retry).addHeader("X-Own-Sentinel","RAW_HEADER_SENTINEL").body("OWN_SYNTHETIC_ERROR_BODY").build());
        }
        MockResponse redirect(){return new MockResponse.Builder().code(302).addHeader("Location",target()).body("own redirect").build();}
        SourceTransport.Response fetch()throws SourceFailure{return transport.fetch(initial(),SourceTransport.Kind.XML,SourceTransport.Validators.NONE,scope());}
        HttpStatusFailure original()throws Exception {
            HttpStatusFailure original=assertThrows(HttpStatusFailure.class,this::fetch);
            assertEquals(503,original.status);assertEquals("target.test",original.host);assertEquals("HTTP_503",original.reason);
            assertEquals(2,server.getRequestCount());assertEquals(2,trace.calls.get());assertEquals(2,trace.ended.get());assertEquals(1,trace.failed.get());
            return original;
        }
        void blockedNoRequest()throws Exception {
            SourceFailure failure=assertThrows(SourceFailure.class,this::fetch);
            SourceFailureInfo info=SourceFailureInfo.from(failure,clock);assertTrue(info.retryBlocked(clock));
            assertEquals(2,server.getRequestCount());assertEquals(2,trace.calls.get());assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS));
        }
        void consumeInitialRequests()throws Exception {
            assertTrue(server.takeRequest(1,TimeUnit.SECONDS).getHeaders().get("Host").startsWith("initial.test:"));
            assertTrue(server.takeRequest(1,TimeUnit.SECONDS).getHeaders().get("Host").startsWith("target.test:"));
        }
        public void close(){if(transport!=null)transport.close();server.close();}
    }
    private static void safe(SourceFailureInfo info,SourceClock clock){
        String message=info.message(clock);
        for(String privateValue:List.of("initial.test","target.test","private-feed","RAW_HEADER_SENTINEL","OWN_SYNTHETIC_ERROR_BODY","Retry-After"))assertFalse(message.contains(privateValue));
    }
    @Test(timeout=15_000) public void finiteRedirectFailureDisablesOriginAndManualRepeatMakesZeroAdditionalRequests()throws Exception {
        try(Fixture f=new Fixture()) {
            f.refusedResponse("5");HttpStatusFailure original=f.original();f.consumeInitialRequests();
            SourceFailureInfo projected=f.transport.cooldown(f.initial());assertNotNull(projected);assertEquals(503,projected.status);
            assertEquals(SourceFailureInfo.from(original,f.clock).retryAtMonotonicMillis,projected.retryAtMonotonicMillis);
            assertTrue(projected.retryBlocked(f.clock));assertTrue(f.transport.cooldown(f.target()).retryBlocked(f.clock));safe(projected,f.clock);
            f.server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type","application/rss+xml").body("UNCONSUMED_SENTINEL").build());
            f.blockedNoRequest();assertEquals(Set.of("target.test"),f.store.load().keySet());
        }
    }
    @Test(timeout=15_000) public void excessiveRedirectDeadlineRemainsBlockedAcrossManualRepeatAndReopen()throws Exception {
        try(Fixture f=new Fixture()) {
            f.refusedResponse("9223372036854775808");HttpStatusFailure original=f.original();f.consumeInitialRequests();
            assertEquals(RetryAfterParser.Kind.EXCESSIVE,original.retryAfter.kind);
            f.clock.advance(TimeUnit.DAYS.toMillis(30));f.reopen(false);
            SourceFailureInfo projected=f.transport.cooldown(f.initial());assertNotNull(projected);assertEquals(503,projected.status);assertEquals(SourceFailureInfo.Kind.EXCESSIVE,projected.kind);safe(projected,f.clock);
            f.blockedNoRequest();assertEquals(Set.of("target.test"),f.store.load().keySet());assertTrue(f.store.load().get("target.test").blocked);
        }
    }
    @Test(timeout=15_000) public void expiryRestoresOnlyManualActionAndDoesNotEmitAutomaticHttp()throws Exception {
        try(Fixture f=new Fixture()) {
            f.refusedResponse("5");f.original();f.consumeInitialRequests();f.clock.advance(4999);
            assertTrue(f.transport.cooldown(f.initial()).retryBlocked(f.clock));f.blockedNoRequest();
            f.clock.advance(1);assertNull(f.transport.cooldown(f.initial()));assertNull(f.transport.cooldown(f.target()));
            assertEquals(2,f.server.getRequestCount());assertEquals(2,f.trace.calls.get());assertNull(f.server.takeRequest(100,TimeUnit.MILLISECONDS));
            f.server.enqueue(f.redirect());f.server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type","application/rss+xml").body("OWN_MANUAL_SUCCESS").build());
            assertEquals("OWN_MANUAL_SUCCESS",new String(f.fetch().bytes,StandardCharsets.UTF_8));assertEquals(4,f.server.getRequestCount());
        }
    }
    @Test(timeout=15_000) public void cancellationAndNewTransportUsingSameGateKeepConfirmedAssociation()throws Exception {
        try(Fixture f=new Fixture()) {
            f.refusedResponse("3600");RequestScope originalScope=f.scope();
            HttpStatusFailure original=assertThrows(HttpStatusFailure.class,()->f.transport.fetch(f.initial(),SourceTransport.Kind.XML,SourceTransport.Validators.NONE,originalScope));
            assertEquals(503,original.status);f.consumeInitialRequests();originalScope.cancel();
            RequestScope cancelled=f.scope();cancelled.cancel();SourceFailure refusal=assertThrows(SourceFailure.class,()->f.transport.fetch(f.initial(),SourceTransport.Kind.XML,SourceTransport.Validators.NONE,cancelled));assertEquals("CANCELLED",refusal.reason);
            f.reopen(false);assertEquals(503,f.transport.cooldown(f.initial()).status);f.blockedNoRequest();
            assertEquals(Set.of("target.test"),f.store.load().keySet());
        }
    }
    @Test(timeout=15_000) public void freshGateRestoresServerHostOnlyAndLearnsOriginAfterOneConfirmedHop()throws Exception {
        try(Fixture f=new Fixture()) {
            f.refusedResponse("3600");f.original();f.consumeInitialRequests();f.reopen(true);
            // A new gate models a new process: URL associations are not in the persisted host format.
            assertNull(f.transport.cooldown(f.initial()));assertTrue(f.transport.cooldown(f.target()).retryBlocked(f.clock));assertEquals(Set.of("target.test"),f.store.load().keySet());
            f.server.enqueue(f.redirect());SourceFailure refused=assertThrows(SourceFailure.class,f::fetch);
            assertTrue(SourceFailureInfo.from(refused,f.clock).retryBlocked(f.clock));assertEquals(3,f.server.getRequestCount());assertTrue(f.server.takeRequest(1,TimeUnit.SECONDS).getHeaders().get("Host").startsWith("initial.test:"));assertNull(f.server.takeRequest(100,TimeUnit.MILLISECONDS));
            assertNotNull(f.transport.cooldown(f.initial()));assertTrue(f.transport.cooldown(f.initial()).retryBlocked(f.clock));
            assertThrows(SourceFailure.class,f::fetch);assertEquals(3,f.server.getRequestCount());assertEquals(3,f.trace.calls.get());
        }
    }
    @Test(timeout=15_000) public void failedActionDoesNotBlockUnrelatedResourceOnTheSameOriginHost()throws Exception {
        try(Fixture f=new Fixture()) {
            f.refusedResponse("3600");f.original();f.consumeInitialRequests();
            String unrelated=f.server.url("/own-unrelated-feed").newBuilder().host("initial.test").build().toString();
            assertNull(f.transport.cooldown(unrelated));assertTrue(f.transport.cooldown(f.initial()).retryBlocked(f.clock));
            f.clock.advance(1000);f.server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type","application/rss+xml").body("OWN_UNRELATED_SUCCESS").build());
            SourceTransport.Response result=f.transport.fetch(unrelated,SourceTransport.Kind.XML,SourceTransport.Validators.NONE,f.scope());
            assertEquals("OWN_UNRELATED_SUCCESS",new String(result.bytes,StandardCharsets.UTF_8));assertEquals(3,f.server.getRequestCount());assertEquals(3,f.trace.calls.get());
            assertNull(f.transport.cooldown(unrelated));assertEquals(503,f.transport.cooldown(f.initial()).status);
            assertThrows(SourceFailure.class,f::fetch);assertEquals(3,f.server.getRequestCount());assertEquals(3,f.trace.calls.get());
        }
    }
    @Test(timeout=15_000) public void namedReaderActionSharesConfirmedRedirectGateAcrossTransportReopenWithoutPersistingRelation()throws Exception {
        try(Fixture f=new Fixture()) {
            f.refusedResponse("3600");f.original();f.consumeInitialRequests();
            String action="body:own-source:own-item",feed=f.server.url("/own-independent-feed").newBuilder().host("initial.test").build().toString();
            f.transport.associateAction(action,f.initial());SourceFailureInfo reader=f.transport.actionCooldown(action);assertNotNull(reader);assertEquals(503,reader.status);assertTrue(reader.retryBlocked(f.clock));safe(reader,f.clock);
            assertNull(f.transport.cooldown(feed));f.reopen(false);assertEquals(503,f.transport.actionCooldown(action).status);assertNull(f.transport.cooldown(feed));f.blockedNoRequest();
            assertEquals(Set.of("target.test"),f.store.load().keySet());f.reopen(true);
            assertNull("Named operation associations are process memory, not a new persistent format",f.transport.actionCooldown(action));
            assertTrue(f.transport.cooldown(f.target()).retryBlocked(f.clock));assertEquals(2,f.server.getRequestCount());assertEquals(Set.of("target.test"),f.store.load().keySet());
        }
    }
}

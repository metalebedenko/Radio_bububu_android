package ru.bububu.radio.sources;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.*;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.Buffer;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import javax.net.SocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;

/** Real TLS socket/server/OkHttp operations; no fake SourceTransport response. */
public class SafeHttpTransportTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    static final class Clock implements SourceClock {
        final long start=System.nanoTime()/1_000_000;final AtomicLong extra=new AtomicLong();
        public long monotonicMillis(){return 1000+System.nanoTime()/1_000_000-start+extra.get();}
        public long wallMillis(){return Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()+System.nanoTime()/1_000_000-start+extra.get();}
        void advance(long millis){extra.addAndGet(millis);}
    }
    static final class Trace extends okhttp3.EventListener {
        final AtomicInteger failed=new AtomicInteger(),ended=new AtomicInteger(),calls=new AtomicInteger(),connects=new AtomicInteger();final List<Long> starts=Collections.synchronizedList(new ArrayList<>());
        @Override public void callStart(Call call){calls.incrementAndGet();}
        @Override public void connectStart(Call call,InetSocketAddress address,Proxy proxy){connects.incrementAndGet();}
        @Override public void callFailed(Call call,IOException error){failed.incrementAndGet();}
        @Override public void responseBodyEnd(Call call,long bytes){ended.incrementAndGet();}
        @Override public void requestHeadersStart(Call call){starts.add(System.nanoTime()/1_000_000);}
    }
    final class Fixture implements AutoCloseable {
        final MockWebServer server=new MockWebServer();final Clock clock=new Clock();final Trace trace=new Trace();final File directory;
        final HostRequestGate gate;final OkHttpClient client;SafeHttpTransport transport;
        Fixture()throws Exception {
            directory=Files.createTempDirectory(temporary.getRoot().toPath(),"gate-").toFile();gate=new HostRequestGate(new HostCooldownStore(directory),clock);
            HeldCertificate cert=new HeldCertificate.Builder().addSubjectAlternativeName("sources.test").build();
            HandshakeCertificates serverTls=new HandshakeCertificates.Builder().heldCertificate(cert).build();
            HandshakeCertificates clientTls=new HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate()).build();
            server.useHttps(serverTls.sslSocketFactory());server.start(InetAddress.getByName("127.0.0.1"),0);
            client=new OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager()).eventListener(trace).build();
            replace(loopbackPolicy(),host->List.of(InetAddress.getByName("127.0.0.1")),client);
        }
        void replace(PublicIpPolicy policy,PublicDns.Resolver resolver,OkHttpClient selected){if(transport!=null)transport.close();transport=new SafeHttpTransport(selected,policy,resolver,gate);}
        String url(String path){return server.url(path).newBuilder().host("sources.test").build().toString();}
        RequestScope scope(){return new RequestScope(1,1,clock,90_000);}
        SourceTransport.Response fetch()throws SourceFailure{return transport.fetch(url("/feed"),SourceTransport.Kind.XML,SourceTransport.Validators.NONE,scope());}
        public void close(){transport.close();server.close();}
    }
    static PublicIpPolicy loopbackPolicy(){return new PublicIpPolicy(){@Override public boolean isPublic(InetAddress address){return address!=null&&address.getHostAddress().equals("127.0.0.1")||super.isPublic(address);}};}
    static MockResponse xml(String body){return new MockResponse.Builder().code(200).addHeader("Content-Type","application/rss+xml; charset=utf-8").body(body).build();}
    static MockResponse error(int status,String retry){MockResponse.Builder response=new MockResponse.Builder().code(status).body("err");if(retry!=null)response.addHeader("Retry-After",retry);return response.build();}
    @Test(timeout=15_000) public void c12DirectPublicHttpUsesTheSameDnsPeerMimeAndQueryGuards()throws Exception {
        try(Fixture f=new Fixture();MockWebServer plain=new MockWebServer()){
            plain.start(InetAddress.getByName("127.0.0.1"),0);plain.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type","text/html; charset=utf-8").body("<main>Open page.</main>").build());
            String url=plain.url("/page?id=One&lang=ru").newBuilder().host("sources.test").build().toString();SourceTransport.Response r=f.transport.fetch(url,SourceTransport.Kind.HTML,SourceTransport.Validators.NONE,f.scope());assertEquals(url,r.finalUrl);assertEquals("text/html",r.mime);assertEquals("/page?id=One&lang=ru",plain.takeRequest(1,TimeUnit.SECONDS).getTarget());
            f.replace(new PublicIpPolicy(),host->List.of(InetAddress.getByName("127.0.0.1")),f.client);SourcePolicyTest.assertFailure("NON_PUBLIC_ADDRESS",()->f.transport.fetch(url,SourceTransport.Kind.HTML,SourceTransport.Validators.NONE,f.scope()));assertEquals(1,plain.getRequestCount());
        }
    }
    @Test(timeout=15_000) public void loadSnapshotTimeoutBeforeHeadersRetainsNoContentAndSingleRequest()throws Exception {
        try(Fixture f=new Fixture()) {
            f.server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type","application/rss+xml").headersDelay(2,TimeUnit.SECONDS).body("SECRET_BODY").build());
            RequestScope scope=new RequestScope(1,1,f.clock,500);LoadFailureSnapshot d=new LoadFailureSnapshot(f.clock,1);scope.loadFailureSnapshot=d;
            try{f.transport.fetch(f.url("/SECRET_URL"),SourceTransport.Kind.XML,SourceTransport.Validators.NONE,scope);fail();}
            catch(SourceFailure error){LoadFailureSnapshot.failureSafe(d,error);SourceFailureInfo primary=SourceFailureInfo.from(error,f.clock);assertEquals("DL1|TIMEOUT|0|TIMEOUT",primary.downloadError());String json=d.json(primary);assertTrue(json.contains("\"received_http_status\":0"));assertTrue(json,json.contains("\"wire_bytes_read\":0"));assertFalse(json.contains("HEADERS_END"));assertFalse(json.contains("SECRET"));}
            assertEquals(1,f.server.getRequestCount());
        }
    }
    @Test(timeout=15_000) public void loadSnapshotReadTimeoutAfter200RetainsPartialByteCountButNotBody()throws Exception {
        try(Fixture f=new Fixture()) {
            f.server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type","application/rss+xml").body("SECRET").throttleBody(3,2,TimeUnit.SECONDS).build());
            RequestScope scope=new RequestScope(1,1,f.clock,700);LoadFailureSnapshot d=new LoadFailureSnapshot(f.clock,1);scope.loadFailureSnapshot=d;
            try{f.transport.fetch(f.url("/SECRET_URL"),SourceTransport.Kind.XML,SourceTransport.Validators.NONE,scope);fail();}
            catch(SourceFailure error){LoadFailureSnapshot.failureSafe(d,error);SourceFailureInfo primary=SourceFailureInfo.from(error,f.clock);assertEquals("DL1|TIMEOUT|0|TIMEOUT",primary.downloadError());String json=d.json(primary);assertTrue(json.contains("\"received_http_status\":200"));assertTrue(json.contains("\"wire_bytes_read\":3"));assertTrue(json.contains("\"network_event\":\"READ\""));assertFalse(json.contains("SECRET"));}
            assertEquals(1,f.server.getRequestCount());
        }
    }
    @Test(timeout=15_000) public void throwingLoadObserverDoesNotChangeResponseRequestsOrCleanup()throws Exception {
        try(Fixture f=new Fixture()) {
            f.server.enqueue(xml("Complete body"));RequestScope scope=f.scope();scope.loadFailureSnapshot=new LoadFailureSnapshot(f.clock,1){@Override synchronized void observe(Event e,int status,long count){throw new IllegalStateException("SECRET_OBSERVER");}};
            SourceTransport.Response result=f.transport.fetch(f.url("/one"),SourceTransport.Kind.XML,SourceTransport.Validators.NONE,scope);
            assertEquals("Complete body",new String(result.bytes,StandardCharsets.UTF_8));assertEquals(1,f.server.getRequestCount());assertFalse(f.gate.failure("sources.test")!=null);
        }
    }
    @Test(timeout=15_000) public void malformedIpv6FetchEntryRejectsBeforeDnsConnectAndRequestsWithDefaultTls()throws Exception {
        Trace trace=new Trace();AtomicInteger lookups=new AtomicInteger();Clock clock=new Clock();
        OkHttpClient defaults=new OkHttpClient.Builder().eventListener(trace).build();
        try(MockWebServer server=new MockWebServer();SafeHttpTransport transport=new SafeHttpTransport(defaults,new PublicIpPolicy(),host->{lookups.incrementAndGet();return List.of(InetAddress.getByName("127.0.0.1"));},new HostRequestGate(new HostCooldownStore(temporary.getRoot()),clock))) {
            server.start(InetAddress.getByName("127.0.0.1"),0);server.enqueue(xml("must-not-be-consumed"));
            // Inspect our own configured client: this rejection test installs no fixture trust or verifier.
            java.lang.reflect.Field field=SafeHttpTransport.class.getDeclaredField("base");field.setAccessible(true);OkHttpClient effective=(OkHttpClient)field.get(transport);
            assertSame(defaults.sslSocketFactory(),effective.sslSocketFactory());assertSame(defaults.hostnameVerifier(),effective.hostnameVerifier());assertEquals(defaults.connectionSpecs(),effective.connectionSpecs());
            for(String value:List.of("https://[1::2::3]/","https://[::1:::]/","https://[::1::]/")) {
                SourcePolicyTest.assertFailure("URL_SYNTAX",()->transport.fetch(value,SourceTransport.Kind.XML,SourceTransport.Validators.NONE,new RequestScope(1,1,clock,90_000)));
                assertEquals(value,0,lookups.get());assertEquals(value,0,trace.calls.get());assertEquals(value,0,trace.connects.get());assertEquals(value,0,server.getRequestCount());
            }
            assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS));
        }
    }
    @Test(timeout=15_000) public void original503ZeroCannotConsumePrepared200AndBodyCloses()throws Exception {
        try(Fixture f=new Fixture()) {
            f.server.enqueue(error(503,"0"));f.server.enqueue(xml("sentinel"));
            try{f.fetch();fail();}catch(HttpStatusFailure original){assertEquals(503,original.status);assertEquals("0",original.headers.get("Retry-After"));assertEquals(RetryAfterParser.Kind.DELAY,original.retryAfter.kind);}
            assertEquals(1,f.trace.failed.get());assertEquals(1,f.trace.ended.get());assertEquals(1,f.server.getRequestCount());assertNotNull(f.server.takeRequest(1,TimeUnit.SECONDS));assertNull(f.server.takeRequest(200,TimeUnit.MILLISECONDS));
            f.clock.advance(1001);assertEquals("sentinel",new String(f.fetch().bytes,StandardCharsets.UTF_8));assertEquals(2,f.server.getRequestCount());
        }
    }
    @Test(timeout=30_000) public void original503NumericDateAbsentInvalidAndHugeAlwaysSingleRequest()throws Exception {
        String[] values={"5","Mon, 12 Oct 2026 00:00:05 GMT","Sun, 06 Nov 1994 08:49:37 GMT",null,"garbage","2147483648","9223372036854775808","9".repeat(4097)};
        for(String value:values)try(Fixture f=new Fixture()) {
            f.server.enqueue(error(503,value));f.server.enqueue(xml("must-not-be-consumed"));
            try{f.fetch();fail("expected original503");}catch(HttpStatusFailure original){assertEquals(503,original.status);assertEquals(value,original.headers.get("Retry-After"));}
            assertEquals(1,f.server.getRequestCount());assertEquals(1,f.trace.failed.get());assertNotNull(f.server.takeRequest(1,TimeUnit.SECONDS));assertNull(f.server.takeRequest(50,TimeUnit.MILLISECONDS));
        }
    }
    @Test(timeout=15_000) public void original429CooldownSurvivesUiCancelAndFacadeRecreation()throws Exception {
        try(Fixture f=new Fixture()) {
            RequestScope scope=f.scope();f.server.enqueue(error(429,"5"));f.server.enqueue(xml("next"));
            try{f.transport.fetch(f.url("/feed"),SourceTransport.Kind.XML,SourceTransport.Validators.NONE,scope);fail();}catch(HttpStatusFailure original){assertEquals(429,original.status);}
            scope.cancel();assertTrue(f.gate.remaining("sources.test")>4000);
            f.replace(loopbackPolicy(),host->List.of(InetAddress.getByName("127.0.0.1")),f.client);
            SourcePolicyTest.assertFailure("HOST_COOLDOWN",f::fetch);assertEquals(1,f.server.getRequestCount());f.clock.advance(5001);assertEquals("next",new String(f.fetch().bytes,StandardCharsets.UTF_8));
        }
    }
    @Test(timeout=15_000) public void excessiveRetryBlocksAfterPersistedRestore()throws Exception {
        try(Fixture f=new Fixture()) {
            f.server.enqueue(error(503,"9223372036854775808"));try{f.fetch();fail();}catch(HttpStatusFailure original){assertEquals(RetryAfterParser.Kind.EXCESSIVE,original.retryAfter.kind);}
            HostRequestGate restored=new HostRequestGate(new HostCooldownStore(f.directory),f.clock);f.transport.close();f.transport=new SafeHttpTransport(f.client,loopbackPolicy(),host->List.of(InetAddress.getByName("127.0.0.1")),restored);
            SourcePolicyTest.assertFailure("COOLDOWN_UNREPRESENTABLE",f::fetch);assertEquals(1,f.server.getRequestCount());
        }
    }
    @Test(timeout=15_000) public void corruptTimingStorePreventsSocketButNotOtherSubsystems()throws Exception {
        try(Fixture f=new Fixture()) {
            Files.write(new File(f.directory,"network-cooldowns-v1.bin").toPath(),new byte[]{1,2});HostRequestGate blocked=new HostRequestGate(new HostCooldownStore(f.directory),f.clock);f.transport.close();f.transport=new SafeHttpTransport(f.client,loopbackPolicy(),host->List.of(InetAddress.getByName("127.0.0.1")),blocked);
            SourcePolicyTest.assertFailure("COOLDOWN_STORAGE",f::fetch);assertEquals(0,f.server.getRequestCount());
        }
    }
    @Test(timeout=15_000) public void productionPrivateAndMixedDnsRejectedBeforeHttpHeaders()throws Exception {
        try(Fixture f=new Fixture()) {
            f.replace(new PublicIpPolicy(),host->List.of(InetAddress.getByName("127.0.0.1")),f.client);SourcePolicyTest.assertFailure("NON_PUBLIC_ADDRESS",f::fetch);assertEquals(0,f.server.getRequestCount());
            f.replace(loopbackPolicy(),host->List.of(InetAddress.getByName("127.0.0.1"),InetAddress.getByName("192.168.1.1")),f.client);SourcePolicyTest.assertFailure("NON_PUBLIC_ADDRESS",f::fetch);assertEquals(0,f.server.getRequestCount());
        }
    }
    @Test(timeout=15_000) public void resolverUsedOnceForActualVettedRouteAndNextLookupRechecked()throws Exception {
        try(Fixture f=new Fixture()) {
            AtomicInteger count=new AtomicInteger();f.replace(loopbackPolicy(),host->List.of(InetAddress.getByName(count.incrementAndGet()==1?"127.0.0.1":"192.168.1.1")),f.client);
            f.server.enqueue(xml("one"));assertEquals("one",new String(f.fetch().bytes,StandardCharsets.UTF_8));assertEquals(1,count.get());f.clock.advance(1001);
            SourcePolicyTest.assertFailure("NON_PUBLIC_ADDRESS",f::fetch);assertEquals(2,count.get());assertEquals(1,f.server.getRequestCount());
        }
    }
    @Test(timeout=15_000) public void actualPeerMismatchRejectedBeforeHttpHeaders()throws Exception {
        try(Fixture f=new Fixture()) {
            SocketFactory forward=new SocketFactory() {
                public Socket createSocket(){return new Socket(){@Override public void connect(SocketAddress ignored,int timeout)throws IOException{super.connect(new InetSocketAddress("127.0.0.1",f.server.getPort()),timeout);}};}
                public Socket createSocket(String h,int p){throw new UnsupportedOperationException();}public Socket createSocket(String h,int p,InetAddress a,int l){throw new UnsupportedOperationException();}public Socket createSocket(InetAddress h,int p){throw new UnsupportedOperationException();}public Socket createSocket(InetAddress h,int p,InetAddress a,int l){throw new UnsupportedOperationException();}
            };
            f.replace(loopbackPolicy(),host->List.of(InetAddress.getByName("8.8.8.8")),f.client.newBuilder().socketFactory(forward).build());
            SourcePolicyTest.assertFailure("PEER_MISMATCH",f::fetch);assertEquals(0,f.server.getRequestCount());assertNull(f.server.takeRequest(100,TimeUnit.MILLISECONDS));
        }
    }
    @Test(timeout=15_000) public void cancelDuringLateDnsCannotConnect()throws Exception {
        try(Fixture f=new Fixture()) {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);f.replace(loopbackPolicy(),host->{entered.countDown();while(release.getCount()!=0)try{release.await();}catch(InterruptedException ignored){/* represents an OS resolver not stopped by interruption */}return List.of(InetAddress.getByName("127.0.0.1"));},f.client);
            ExecutorService executor=Executors.newSingleThreadExecutor();RequestScope scope=f.scope();
            try {
                Future<String> result=executor.submit(()->{try{f.transport.fetch(f.url("/feed"),SourceTransport.Kind.XML,SourceTransport.Validators.NONE,scope);return "UNEXPECTED_SUCCESS";}catch(SourceFailure error){return error.reason;}});
                assertTrue(entered.await(2,TimeUnit.SECONDS));scope.cancel();assertEquals("CANCELLED",result.get(2,TimeUnit.SECONDS));release.countDown();assertNull(f.server.takeRequest(100,TimeUnit.MILLISECONDS));assertEquals(0,f.server.getRequestCount());
            }finally{release.countDown();executor.shutdownNow();}
        }
    }
    @Test(timeout=15_000) public void cancelWhileResponseDelayedAndTransportCloseCannotDeliverSuccess()throws Exception {
        try(Fixture f=new Fixture()) {
            f.server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type","application/rss+xml").headersDelay(5,TimeUnit.SECONDS).body("late").build());RequestScope scope=f.scope();ExecutorService executor=Executors.newSingleThreadExecutor();
            try{Future<String> result=executor.submit(()->{try{f.transport.fetch(f.url("/feed"),SourceTransport.Kind.XML,SourceTransport.Validators.NONE,scope);return "UNEXPECTED_SUCCESS";}catch(SourceFailure error){return error.reason;}});assertNotNull(f.server.takeRequest(2,TimeUnit.SECONDS));scope.cancel();assertEquals("CANCELLED",result.get(2,TimeUnit.SECONDS));f.transport.close();SourcePolicyTest.assertFailure("TRANSPORT_CLOSED",f::fetch);}finally{executor.shutdownNow();}
        }
    }
    @Test(timeout=15_000) public void globalDeadlineIncludesDnsInsteadOfOnlyConnect()throws Exception {
        try(Fixture f=new Fixture()) {
            f.replace(loopbackPolicy(),host->{try{Thread.sleep(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}return List.of(InetAddress.getByName("127.0.0.1"));},f.client);
            RequestScope scope=new RequestScope(1,1,f.clock,150);long begin=System.nanoTime();SourcePolicyTest.assertFailure("TIMEOUT",()->f.transport.fetch(f.url("/feed"),SourceTransport.Kind.XML,SourceTransport.Validators.NONE,scope));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin)<2000);assertEquals(0,f.server.getRequestCount());
        }
    }
    @Test(timeout=15_000) public void gzipDecodedAndWireByteCapsRejectWithoutPartialResult()throws Exception {
        try(Fixture f=new Fixture()) {
            byte[] oversized=new byte[SourceLimits.XML_BYTES+1];Arrays.fill(oversized,(byte)'a');ByteArrayOutputStream packed=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(packed)){gzip.write(oversized);}
            f.server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type","application/rss+xml").addHeader("Content-Encoding","gzip").body(new Buffer().write(packed.toByteArray())).build());SourcePolicyTest.assertFailure("DECODED_LIMIT",f::fetch);assertEquals("gzip",f.server.takeRequest(1,TimeUnit.SECONDS).getHeaders().get("Accept-Encoding"));
            f.clock.advance(1001);f.server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type","application/rss+xml").body(new Buffer().write(oversized)).build());SourcePolicyTest.assertFailure("WIRE_LIMIT",f::fetch);
        }
    }
    @Test(timeout=15_000) public void redirectUsesNewHostGateAndRejectsDowngradePrivateLoop()throws Exception {
        for(String location:List.of("http://sources.test/feed","https://127.0.0.2/feed","/feed"))try(Fixture f=new Fixture()) {
            f.server.enqueue(new MockResponse.Builder().code(302).addHeader("Location",location).build());
            try{f.fetch();fail();}catch(SourceFailure error){assertTrue(Set.of("HTTPS_REQUIRED","NON_PUBLIC_ADDRESS","REDIRECT_LOOP").contains(error.reason));}assertEquals(1,f.server.getRequestCount());
        }
    }
    @Test(timeout=15_000) public void realRedirectRetainsDeadlineAndAtLeastOneSecondHostSpacing()throws Exception {
        try(Fixture f=new Fixture()) {
            f.server.enqueue(new MockResponse.Builder().code(302).addHeader("Location","/actual").build());f.server.enqueue(xml("done"));SourceTransport.Response result=f.fetch();assertEquals(f.url("/actual"),result.finalUrl);assertEquals(2,f.trace.starts.size());assertTrue(f.trace.starts.get(1)-f.trace.starts.get(0)>=950);
        }
    }
    @Test(timeout=15_000) public void redirectedResourceDoesNotInheritConditionalHeadersOrOldCache()throws Exception {
        try(Fixture f=new Fixture()) {
            f.server.enqueue(new MockResponse.Builder().code(302).addHeader("Location","/new-feed").build());f.server.enqueue(new MockResponse.Builder().code(304).build());
            SourcePolicyTest.assertFailure("UNEXPECTED_304",()->f.transport.fetch(f.url("/old-feed"),SourceTransport.Kind.XML,new SourceTransport.Validators("old-etag","Mon, 12 Oct 2026 00:00:00 GMT",true),f.scope()));
            assertEquals("old-etag",f.server.takeRequest(1,TimeUnit.SECONDS).getHeaders().get("If-None-Match"));
            okhttp3.Headers headers=f.server.takeRequest(1,TimeUnit.SECONDS).getHeaders();assertNull(headers.get("If-None-Match"));assertNull(headers.get("If-Modified-Since"));assertEquals(2,f.server.getRequestCount());
        }
    }
    @Test(timeout=15_000) public void original401NoAuthenticationRetryOrCookies()throws Exception {
        try(Fixture f=new Fixture()) {
            f.server.enqueue(new MockResponse.Builder().code(401).addHeader("WWW-Authenticate","Basic realm=fixture").addHeader("Set-Cookie","fixture=secret").body("denied").build());f.server.enqueue(xml("manual"));try{f.fetch();fail();}catch(HttpStatusFailure original){assertEquals(401,original.status);}assertEquals(1,f.server.getRequestCount());f.clock.advance(1001);f.fetch();f.server.takeRequest(1,TimeUnit.SECONDS);okhttp3.Headers headers=f.server.takeRequest(1,TimeUnit.SECONDS).getHeaders();assertNull(headers.get("Cookie"));assertNull(headers.get("Authorization"));
        }
    }
    @Test(timeout=15_000) public void unexpected304AndEmptyBytesDifferFromValidCacheReuse()throws Exception {
        try(Fixture f=new Fixture()) {
            f.server.enqueue(new MockResponse.Builder().code(304).build());SourcePolicyTest.assertFailure("UNEXPECTED_304",f::fetch);f.clock.advance(1001);f.server.enqueue(new MockResponse.Builder().code(304).build());SourceTransport.Response cached=f.transport.fetch(f.url("/feed"),SourceTransport.Kind.XML,new SourceTransport.Validators("etag",null,true),f.scope());assertEquals(304,cached.status);assertEquals(0,cached.bytes.length);
            f.clock.advance(1001);f.server.enqueue(xml(""));SourcePolicyTest.assertFailure("EMPTY_HTTP_BODY",f::fetch);
        }
    }
    @Test(timeout=15_000) public void htmlChallengeOnKnownFeedIsMimeFailureAndDoesNotCreateFakeFeed()throws Exception {try(Fixture f=new Fixture()){f.server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type","text/html").body("<html>Login</html>").build());SourcePolicyTest.assertFailure("MIME_MISMATCH",f::fetch);assertEquals(1,f.server.getRequestCount());}}
}

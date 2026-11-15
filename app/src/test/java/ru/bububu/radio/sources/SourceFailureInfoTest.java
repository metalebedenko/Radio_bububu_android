package ru.bububu.radio.sources;

import okhttp3.Headers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.time.Instant;
import java.util.List;
import static org.junit.Assert.*;

/** Typed timing/UI projection, without HTTP requests or external response content. */
public class SourceFailureInfoTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String HOST="synthetic-private-host.test",RAW="RAW_HEADER_SENTINEL",URL="https://synthetic-private-host.test/private-path";
    private static final class Clock implements SourceClock {
        long mono=1000,wall=Instant.parse("2026-10-12T00:00:00Z").toEpochMilli();
        public long monotonicMillis(){return mono;}public long wallMillis(){return wall;}
        void advance(long millis){mono+=millis;wall+=millis;}
    }
    private static RetryAfterParser.Result retry(List<String> headers,Clock clock){return RetryAfterParser.parse(headers,clock.wall,clock.mono);}
    private static SourceFailureInfo http(int status,List<String> values,Clock clock) {
        Headers.Builder headers=new Headers.Builder().add("Location",URL).add("X-Diagnostic",RAW);
        for(String value:values)headers.add("Retry-After",value);
        return SourceFailureInfo.from(new HttpStatusFailure(status,headers.build(),HOST,new RequestScope(1,1,clock,90_000),retry(values,clock)),clock);
    }
    private static void safe(SourceFailureInfo info,Clock clock) {
        String text=info.message(clock);assertFalse(text.contains(HOST));assertFalse(text.contains(URL));assertFalse(text.contains(RAW));assertFalse(text.contains("Retry-After"));
    }
    @Test public void typedHttpStatusAndRemainingDelaySurviveSafeProjection() {
        Clock clock=new Clock();SourceFailureInfo info=http(503,List.of("5"),clock);
        assertEquals(SourceFailureInfo.Kind.HTTP,info.kind);assertEquals(503,info.status);assertEquals("HTTP_503",info.reason);assertEquals(RetryAfterParser.Kind.DELAY,info.retryAfterKind);
        assertEquals(5000,info.remainingMillis(clock));assertTrue(info.retryBlocked(clock));assertTrue(info.message(clock).contains("503"));assertTrue(info.message(clock).contains("5 сек."));safe(info,clock);
        clock.advance(2000);assertEquals(3000,info.remainingMillis(clock));assertTrue(info.message(clock).contains("3 сек."));
        clock.wall+=10_000_000;assertEquals(3000,info.remainingMillis(clock));
    }
    @Test public void rateLimitStatusRemainsTypedAndExpiresWithoutAutomaticRetry() {
        Clock clock=new Clock();SourceFailureInfo info=http(429,List.of("2"),clock);assertEquals(429,info.status);assertEquals(SourceFailureInfo.Kind.HTTP,info.kind);assertTrue(info.retryBlocked(clock));
        clock.advance(1999);assertEquals(1,info.remainingMillis(clock));assertTrue(info.retryBlocked(clock));assertTrue(info.message(clock).contains("1 сек."));
        clock.advance(1);assertEquals(0,info.remainingMillis(clock));assertFalse(info.retryBlocked(clock));assertTrue(info.message(clock).contains("вручную"));safe(info,clock);
    }
    @Test public void excessiveRetryAfterHasExplicitKindAndNeverExpiresAsZero() {
        Clock clock=new Clock();SourceFailureInfo info=http(503,List.of("9223372036854775808"),clock);
        assertEquals(SourceFailureInfo.Kind.EXCESSIVE,info.kind);assertEquals(503,info.status);assertEquals(RetryAfterParser.Kind.EXCESSIVE,info.retryAfterKind);assertEquals(Long.MAX_VALUE,info.remainingMillis(clock));assertTrue(info.retryBlocked(clock));
        clock.advance(90_000);assertEquals(Long.MAX_VALUE,info.remainingMillis(clock));assertTrue(info.retryBlocked(clock));assertTrue(info.message(clock).contains("невозможно безопасно представить"));safe(info,clock);
    }
    @Test public void invalidRetryAfterKeepsHttpFailureWithoutInventingADelay() {
        Clock clock=new Clock();SourceFailureInfo info=http(503,List.of("not-a-date"),clock);
        assertEquals(SourceFailureInfo.Kind.HTTP,info.kind);assertEquals(503,info.status);assertEquals(RetryAfterParser.Kind.INVALID,info.retryAfterKind);assertEquals(0,info.remainingMillis(clock));assertFalse(info.retryBlocked(clock));assertTrue(info.message(clock).contains("вручную"));safe(info,clock);
    }
    @Test public void absentAndZeroRetryAfterPreserveTheirDifferentKinds() {
        Clock clock=new Clock();SourceFailureInfo absent=http(503,List.of(),clock),zero=http(503,List.of("0"),clock);
        assertEquals(RetryAfterParser.Kind.ABSENT,absent.retryAfterKind);assertEquals(RetryAfterParser.Kind.DELAY,zero.retryAfterKind);
        for(SourceFailureInfo info:List.of(absent,zero)){assertEquals(SourceFailureInfo.Kind.HTTP,info.kind);assertEquals(503,info.status);assertEquals(0,info.remainingMillis(clock));assertFalse(info.retryBlocked(clock));safe(info,clock);}
    }
    @Test public void httpDateAndRepeatedHeadersProjectTheirActualMaximumDeadline() {
        Clock clock=new Clock();SourceFailureInfo date=http(503,List.of("Mon, 12 Oct 2026 00:00:05 GMT"),clock);assertEquals(5000,date.remainingMillis(clock));
        SourceFailureInfo repeated=http(503,List.of("0","invalid","7","2"),clock);assertEquals(RetryAfterParser.Kind.DELAY,repeated.retryAfterKind);assertEquals(7000,repeated.remainingMillis(clock));safe(repeated,clock);
    }
    @Test public void persistedGateReconstructsCountdownAtNewMonotonicOriginAndKeepsStatus()throws Exception {
        Clock original=new Clock();HostRequestGate gate=new HostRequestGate(new HostCooldownStore(temporary.getRoot()),original);gate.observe(HOST,retry(List.of("10"),original));original.advance(3000);
        Clock restarted=new Clock();restarted.mono=100;restarted.wall=original.wall;
        HostRequestGate restored=new HostRequestGate(new HostCooldownStore(temporary.getRoot()),restarted);
        SourceFailureInfo info=restored.failure(HOST).withStatus(503);assertEquals(SourceFailureInfo.Kind.COOLDOWN,info.kind);assertEquals(503,info.status);assertEquals(RetryAfterParser.Kind.DELAY,info.retryAfterKind);assertEquals(7000,info.remainingMillis(restarted));assertTrue(info.retryBlocked(restarted));safe(info,restarted);
        restored.observe(HOST,retry(List.of("0"),restarted));assertEquals(7000,restored.failure(HOST).remainingMillis(restarted));
        RequestScope cancelled=new RequestScope(1,1,restarted,90_000);cancelled.cancel();assertEquals(7000,restored.failure(HOST).remainingMillis(restarted));
        restarted.advance(7000);assertFalse(info.retryBlocked(restarted));assertNull(restored.failure(HOST));
        try(HostRequestGate.Lease ignored=restored.acquire(HOST,new RequestScope(2,1,restarted,90_000),restarted.mono+30_000)){assertEquals(0,restored.remaining(HOST));}
    }
    @Test public void reconstructedExcessiveGateAndTypedRefusalRemainBlocked()throws Exception {
        Clock clock=new Clock();HostRequestGate gate=new HostRequestGate(new HostCooldownStore(temporary.getRoot()),clock);gate.observe(HOST,retry(List.of("9223372036854775808"),clock));clock.advance(90_000);
        HostRequestGate restored=new HostRequestGate(new HostCooldownStore(temporary.getRoot()),clock);SourceFailureInfo info=restored.failure(HOST);
        assertEquals(SourceFailureInfo.Kind.EXCESSIVE,info.kind);assertEquals(RetryAfterParser.Kind.EXCESSIVE,info.retryAfterKind);assertEquals(Long.MAX_VALUE,info.remainingMillis(clock));safe(info,clock);
        try{restored.acquire(HOST,new RequestScope(2,1,clock,90_000),clock.mono+30_000);fail();}catch(CooldownFailure refused){SourceFailureInfo projected=SourceFailureInfo.from(refused,clock);assertEquals(SourceFailureInfo.Kind.EXCESSIVE,projected.kind);assertTrue(projected.retryBlocked(clock));safe(projected,clock);}
    }
    @Test public void presentationRoundingNeverPromisesEarlierThanRemainingDeadline() {
        Clock clock=new Clock();SourceFailureInfo info=http(503,List.of("3601"),clock);assertTrue(info.message(clock).contains("2 ч."));
        clock.advance(3_540_000);assertEquals(61_000,info.remainingMillis(clock));assertTrue(info.message(clock).contains("2 мин."));
        clock.advance(1001);assertEquals(59_999,info.remainingMillis(clock));assertTrue(info.message(clock).contains("1 мин."));safe(info,clock);
    }
    @Test public void downloadDiagnosticRoundtripKeepsOnlyTypedCategoryStatusAndAllowedReason() {
        Clock clock=new Clock();
        for(SourceFailureInfo original:List.of(http(503,List.of("2"),clock),http(429,List.of("9223372036854775808"),clock),SourceFailureInfo.from(new CooldownFailure("HOST_COOLDOWN",2000,clock,false,503),clock),SourceFailureInfo.forReason("MIME_MISMATCH"),SourceFailureInfo.forReason("TIMEOUT"))) {
            String saved=original.downloadError();assertTrue(saved.length()<=128);assertEquals(saved,SourceFailureInfo.downloadDiagnostic(saved));
            SourceFailureInfo restored=SourceFailureInfo.forStoredError(saved);assertEquals(original.kind,restored.kind);assertEquals(original.status,restored.status);assertEquals(original.reason,restored.reason);assertEquals(original.kind==SourceFailureInfo.Kind.EXCESSIVE?Long.MAX_VALUE:0,restored.remainingMillis(clock));safe(restored,clock);
        }
        assertNull(SourceFailureInfo.downloadDiagnostic("HTTP_503"));assertEquals(503,SourceFailureInfo.forStoredError("HTTP_503").status);
    }
    @Test public void diagnosticUnknownMalformedAndRawThrowableContentNeverEscapes() {
        Clock clock=new Clock();
        for(Exception failure:List.of(new SourceFailure(URL+RAW),new SourceFailure("RAW_SECRET_TOKEN"),new SourceFailure("x".repeat(5000)),new java.io.IOException(URL+RAW))) {
            String saved=SourceFailureInfo.from(failure,clock).downloadError();assertEquals("DL1|UNAVAILABLE|0|SOURCE_UNAVAILABLE",saved);assertFalse(saved.contains(RAW));assertFalse(saved.contains(URL));
        }
        for(String invalid:List.of("DL1|HTTP|0|HTTP_503","DL1|HTTP|503|HTTP_429","DL1|HTTP|503|TIMEOUT","DL1|UNAVAILABLE|503|HTTP_503","DL1|TIMEOUT|0|"+URL,"DL1|UNAVAILABLE|0|RAW_SECRET_TOKEN","DL1|TIMEOUT|0|TIMEOUT|EXTRA","DL1|UNKNOWN|0|TIMEOUT")) {
            assertNull(SourceFailureInfo.downloadDiagnostic(invalid));assertEquals("SOURCE_UNAVAILABLE",SourceFailureInfo.forStoredError(invalid).reason);safe(SourceFailureInfo.forStoredError(invalid),clock);
        }
        IllegalStateException hostile=new IllegalStateException(){@Override public String getMessage(){throw new AssertionError("message forbidden");}@Override public synchronized Throwable getCause(){throw new AssertionError("cause forbidden");}};
        assertEquals("DL1|STORAGE|0|STORAGE_OR_LIMIT",SourceFailureInfo.from(hostile,clock).downloadError());
    }
    @Test public void nonHttpErrorProjectionClassifiesWithoutCopyingExceptionMessage() {
        Clock clock=new Clock();
        assertEquals(SourceFailureInfo.Kind.TIMEOUT,SourceFailureInfo.from(new SourceFailure("TIMEOUT"),clock).kind);assertEquals(SourceFailureInfo.Kind.CANCELLED,SourceFailureInfo.from(new SourceFailure("CANCELLED"),clock).kind);
        assertEquals(SourceFailureInfo.Kind.STORAGE,SourceFailureInfo.from(new SourceFailure("COOLDOWN_STORAGE"),clock).kind);assertEquals(SourceFailureInfo.Kind.INVALID,SourceFailureInfo.from(new IllegalArgumentException(URL+RAW),clock).kind);
        for(Exception failure:List.of(new IllegalArgumentException(URL+RAW),new IllegalStateException(URL+RAW),new java.io.IOException(URL+RAW)))safe(SourceFailureInfo.from(failure,clock),clock);
    }
}

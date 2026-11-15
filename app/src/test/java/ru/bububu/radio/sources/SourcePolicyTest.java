package ru.bububu.radio.sources;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.net.InetAddress;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;

public class SourcePolicyTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    static final class Clock implements SourceClock {long mono=1000,wall=Instant.parse("2026-10-12T00:00:00Z").toEpochMilli();public long monotonicMillis(){return mono;}public long wallMillis(){return wall;}void advance(long amount){mono+=amount;wall+=amount;}}
    @Test public void privateSpecialMappedAndTunnelAddressesRejected()throws Exception {
        PublicIpPolicy policy=new PublicIpPolicy();
        for(String ip:List.of("0.1.2.3","10.0.0.1","100.64.0.1","100.127.255.254","127.0.0.1","169.254.1.2","172.16.0.1","172.31.255.255","192.168.1.1","192.0.2.1","198.18.0.1","198.51.100.1","203.0.113.1","224.0.0.1","255.255.255.255","::1","fc00::1","fe80::1","2001:db8::1","3fff::1","2001::1234","::ffff:192.168.1.1","64:ff9b::c0a8:101","64:ff9b:1::1","2002:c0a8:101::1"))assertFalse(ip,policy.isPublic(InetAddress.getByName(ip)));
    }
    @Test public void publicAddressBoundariesAccepted()throws Exception {PublicIpPolicy policy=new PublicIpPolicy();for(String ip:List.of("8.8.8.8","100.63.255.255","100.128.0.1","172.15.255.255","172.32.0.1","2001:4860:4860::8888","::ffff:8.8.8.8","64:ff9b::808:808","2002:0808:0808::1"))assertTrue(ip,policy.isPublic(InetAddress.getByName(ip)));}
    @Test public void retryDeltaZeroPositiveAndBeyondInteger() {
        Clock clock=new Clock();assertEquals(0,retry("0",clock).delayMillis);assertEquals(12_000,retry("\t12 ",clock).delayMillis);
        assertEquals(RetryAfterParser.Kind.DELAY,retry("2147483648",clock).kind);assertEquals(2_147_483_648_000L,retry("2147483648",clock).delayMillis);
    }
    @Test public void retryHugeValuesAreExplicitlyExcessive(){Clock clock=new Clock();for(String value:List.of("9223372036854775808","9".repeat(4097)))assertEquals(RetryAfterParser.Kind.EXCESSIVE,retry(value,clock).kind);}
    @Test public void retryInvalidNeverSchedulesZeroRetry(){Clock clock=new Clock();for(String value:List.of("-1","+1","1.5","0, 10","\n0","١","Mon, 31 Feb 2026 00:00:00 GMT"))assertEquals(value,RetryAfterParser.Kind.INVALID,retry(value,clock).kind);assertEquals(RetryAfterParser.Kind.ABSENT,RetryAfterParser.parse(List.of(),clock.wall,clock.mono).kind);}
    @Test public void retryAllThreeHttpDateFormsAndPastDate(){Clock clock=new Clock();assertEquals(5000,retry("Mon, 12 Oct 2026 00:00:05 GMT",clock).delayMillis);assertEquals(5000,retry("Monday, 12-Oct-26 00:00:05 GMT",clock).delayMillis);assertEquals(5000,retry("Mon Oct 12 00:00:05 2026",clock).delayMillis);assertEquals(0,retry("Sun, 06 Nov 1994 08:49:37 GMT",clock).delayMillis);}
    @Test public void retryRepeatedMaximumMalformedAndOverflow(){Clock clock=new Clock();RetryAfterParser.Result result=RetryAfterParser.parse(List.of("0","invalid","5","3"),clock.wall,clock.mono);assertEquals(5000,result.delayMillis);assertTrue(result.malformed);assertEquals(RetryAfterParser.Kind.EXCESSIVE,RetryAfterParser.parse(List.of("1"),Long.MAX_VALUE-1,clock.mono).kind);}
    @Test public void cooldownUsesMonotonicClockAndSurvivesScopeCancellation()throws Exception {
        Clock clock=new Clock();HostRequestGate gate=new HostRequestGate(new HostCooldownStore(temporary.getRoot()),clock);RequestScope scope=new RequestScope(1,1,clock,90_000);
        gate.observe("source.test",retry("5",clock));scope.cancel();clock.wall+=100_000;
        assertEquals(5000,gate.remaining("source.test"));assertFailure("HOST_COOLDOWN",()->gate.acquire("source.test",new RequestScope(2,1,clock,90_000),clock.mono+30_000));
        clock.mono+=5000;try(HostRequestGate.Lease ignored=gate.acquire("source.test",new RequestScope(3,1,clock,90_000),clock.mono+30_000)){assertEquals(0,gate.remaining("source.test"));}
    }
    @Test public void cooldownPersistedAndBlockedCannotBeShortened()throws Exception {
        Clock clock=new Clock();File directory=temporary.getRoot();HostRequestGate gate=new HostRequestGate(new HostCooldownStore(directory),clock);gate.observe("source.test",retry("10",clock));clock.advance(2000);
        HostRequestGate restored=new HostRequestGate(new HostCooldownStore(directory),clock);assertEquals(8000,restored.remaining("source.test"));restored.observe("source.test",retry("0",clock));assertEquals(8000,restored.remaining("source.test"));
        restored.observe("source.test",retry("9223372036854775808",clock));HostRequestGate again=new HostRequestGate(new HostCooldownStore(directory),clock);assertEquals(Long.MAX_VALUE,again.remaining("source.test"));assertFailure("COOLDOWN_UNREPRESENTABLE",()->again.acquire("source.test",new RequestScope(1,1,clock,90_000),clock.mono+30_000));
    }
    @Test public void corruptAndPendingCooldownStoreFailClosedAndPreserveBytes()throws Exception {
        File file=new File(temporary.getRoot(),"network-cooldowns-v1.bin");byte[] bad={1,2,3};Files.write(file.toPath(),bad);Clock clock=new Clock();HostRequestGate gate=new HostRequestGate(new HostCooldownStore(temporary.getRoot()),clock);
        assertFailure("COOLDOWN_STORAGE",()->gate.acquire("source.test",new RequestScope(1,1,clock,90_000),clock.mono+30_000));assertArrayEquals(bad,Files.readAllBytes(file.toPath()));
    }
    @Test public void persistedCapacityNeverEvictsLiveHost()throws Exception {
        Clock clock=new Clock();HostRequestGate gate=new HostRequestGate(new HostCooldownStore(temporary.getRoot()),clock);for(int i=0;i<64;i++)gate.observe("source"+i+".test",retry("60",clock));
        assertFailure("HOST_LIMIT",()->gate.acquire("other.test",new RequestScope(1,1,clock,90_000),clock.mono+30_000));assertEquals(60_000,gate.remaining("source0.test"));
    }
    @Test public void xmlGuardAllowsLiteralDoctypeButRejectsActiveDeclaration()throws Exception {
        String harmless="<?xml version='1.0'?><rss version='2.0'><channel><description><![CDATA[<!DOCTYPE harmless>]]></description></channel></rss>";
        assertEquals(harmless,XmlDeclarationGuard.check(harmless.getBytes(java.nio.charset.StandardCharsets.UTF_8),null,null).text);
        assertFailure("XML_DTD",()->XmlDeclarationGuard.check("<!DOCTYPE rss SYSTEM 'https://source.test/entity'><rss/>".getBytes(),null,null));
    }
    @Test public void xmlGuardCp1251AndCharsetConflict()throws Exception {
        String value="<?xml version='1.0' encoding='windows-1251'?><rss version='2.0'><channel><title>Русский текст</title></channel></rss>";byte[] bytes=value.getBytes(java.nio.charset.Charset.forName("windows-1251"));
        assertEquals(value,XmlDeclarationGuard.check(bytes,null,null).text);assertFailure("CHARSET_CONFLICT",()->XmlDeclarationGuard.check(bytes,"UTF-8",null));
    }
    @Test public void cancelledScopeCannotBeReboundToContinue(){RequestScope scope=new RequestScope(1,1);scope.cancel();int[] count={0};scope.bindCancellation(()->count[0]++);assertEquals(1,count[0]);try{scope.check();fail();}catch(SourceFailure cancelled){assertEquals("CANCELLED",cancelled.reason);}}
    @Test public void urlNormalizationPreservesMeaningfulPathAndQuery()throws Exception {assertEquals("http://example.com/News?key=One&order=2",SafeHttpTransport.normalizeUrl("HTTP://EXAMPLE.COM:80/News?key=One&order=2#fragment"));assertEquals("https://example.com/News?key=One&order=2",SafeHttpTransport.normalizeUrl("HTTPS://EXAMPLE.COM:443/News?key=One&order=2#fragment"));for(String value:List.of("ftp://example.com/a","file:///private/a","https://u:p@example.com","https://@example.com","https://localhost./","https://example.com\\@127.0.0.1/"))try{SafeHttpTransport.normalizeUrl(value);fail(value);}catch(SourceFailure expected){assertNotNull(expected.reason);}}
    @Test public void privateLiteralLegacyIpAndMalformedLabelsRejectBeforeDns()throws Exception {
        for(String value:List.of("https://127.0.0.1/","https://[::1]/","https://2130706433/","https://0177.0.0.1/","https://a..example/","https://-a.example/"))try{SafeHttpTransport.normalizeUrl(value);fail(value);}catch(SourceFailure expected){assertTrue(value+": "+expected.reason,Set.of("NON_PUBLIC_ADDRESS","URL_HOST").contains(expected.reason));}
    }
    static RetryAfterParser.Result retry(String value,Clock clock){return RetryAfterParser.parse(List.of(value),clock.wall,clock.mono);}
    interface Checked {void run()throws Exception;}
    static void assertFailure(String reason,Checked action)throws Exception{try{action.run();fail("expected "+reason);}catch(SourceFailure expected){assertEquals(reason,expected.reason);}}
}

package ru.bububu.radio.sources;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic provider traps. No real session, certificate identity, network, disk or user data. */
public final class DwSessionSummaryTest {
    private static final String PRIVATE="PRIVATE_DN_SAN_IP_SESSION_SENTINEL";
    private static final class Leaf extends X509Certificate {
        byte[] encoded=PRIVATE.getBytes(StandardCharsets.UTF_8);Collection<List<?>> alternatives;
        int encodedCalls,sanCalls;boolean encodingFailure,sanFailure;
        Leaf(Collection<List<?>> alternatives){this.alternatives=alternatives;}
        public byte[] getEncoded()throws CertificateEncodingException{encodedCalls++;if(encodingFailure)throw new CertificateEncodingException(PRIVATE);return encoded.clone();}
        public Collection<List<?>> getSubjectAlternativeNames()throws CertificateParsingException{sanCalls++;if(sanFailure)throw new CertificateParsingException(PRIVATE);return alternatives;}
        private AssertionError forbidden(){return new AssertionError("FORBIDDEN_GETTER_"+PRIVATE);}
        public void checkValidity(){throw forbidden();}public void checkValidity(Date date){throw forbidden();}
        public int getVersion(){throw forbidden();}public java.math.BigInteger getSerialNumber(){throw forbidden();}
        public Principal getIssuerDN(){throw forbidden();}public Principal getSubjectDN(){throw forbidden();}
        public Date getNotBefore(){throw forbidden();}public Date getNotAfter(){throw forbidden();}
        public byte[] getTBSCertificate(){throw forbidden();}public byte[] getSignature(){throw forbidden();}
        public String getSigAlgName(){throw forbidden();}public String getSigAlgOID(){throw forbidden();}public byte[] getSigAlgParams(){throw forbidden();}
        public boolean[] getIssuerUniqueID(){throw forbidden();}public boolean[] getSubjectUniqueID(){throw forbidden();}public boolean[] getKeyUsage(){throw forbidden();}
        public int getBasicConstraints(){throw forbidden();}public void verify(PublicKey key){throw forbidden();}public void verify(PublicKey key,String provider){throw forbidden();}
        public String toString(){throw forbidden();}public PublicKey getPublicKey(){throw forbidden();}
        public Set<String> getCriticalExtensionOIDs(){throw forbidden();}public Set<String> getNonCriticalExtensionOIDs(){throw forbidden();}
        public boolean hasUnsupportedCriticalExtension(){throw forbidden();}public byte[] getExtensionValue(String oid){throw forbidden();}
    }
    private static final class Session {
        Certificate[] chain;String protocol="TLSv1.3";int protocols,peers;boolean peerFailure,protocolFailure;
        Runnable beforeRead=()->{};
        Session(Certificate...chain){this.chain=chain;}
        SSLSession proxy(){return (SSLSession)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SSLSession.class},(p,m,a)->{
            beforeRead.run();switch(m.getName()){
                case "getProtocol":protocols++;if(protocolFailure)throw new AssertionError(PRIVATE);return protocol;
                case "getPeerCertificates":peers++;if(peerFailure)throw new SSLPeerUnverifiedException(PRIVATE);return chain;
                default:throw new AssertionError("FORBIDDEN_SESSION_GETTER_"+PRIVATE);
            }
        });}
    }
    private static final class Clock implements SourceClock {long now;public long wallMillis(){return now;}public long monotonicMillis(){return now;}}
    private static List<?> san(int type,Object value){return Arrays.asList(type,value);}
    private static String read(Session session){return new DwSessionSummary.Collector().read("rss.dw.com",session.proxy()).json();}
    private static void safe(String json){assertTrue(json.getBytes(StandardCharsets.UTF_8).length<DwSessionSummary.MAX_JSON);assertFalse(json.contains(PRIVATE));}
    private static void unavailable(String json,String reason){safe(json);assertTrue(json,json.contains("\"status\":\"UNAVAILABLE\""));assertTrue(json,json.contains("\"unavailable_reason\":\""+reason+"\""));assertTrue(json.contains("\"leaf_sha256\":\"\""));}

    @Test public void protocolLeafCountsAndTwoAsciiLiteralsAreFactsOnlyAndPrivacySafe(){
        Leaf leaf=new Leaf(Arrays.asList(san(2,"RSS.DW.COM"),san(2,"*.dw.com"),san(7,PRIVATE),san(6,PRIVATE)));
        Session session=new Session(leaf);String json=read(session);safe(json);
        assertTrue(json.contains("\"status\":\"AVAILABLE\""));assertTrue(json.contains("\"tls_protocol\":\"TLS_1_3\""));
        assertTrue(json.contains("\"chain_length\":1"));assertTrue(json.contains("\"leaf_x509\":true"));assertTrue(json.matches(".*\\\"leaf_sha256\\\":\\\"[0-9a-f]{64}\\\".*"));
        assertTrue(json.contains("\"dns_entries\":2"));assertTrue(json.contains("\"ip_entries\":1"));
        assertTrue(json.contains("\"san_literal_rss_dw_com\":true"));assertTrue(json.contains("\"san_literal_wildcard_dw_com\":true"));
        assertEquals(1,session.protocols);assertEquals(1,session.peers);assertEquals(1,leaf.encodedCalls);assertEquals(1,leaf.sanCalls);
        Session tls12=new Session(new Leaf(null));tls12.protocol="TLSv1.2";assertTrue(read(tls12).contains("\"tls_protocol\":\"TLS_1_2\""));
    }
    @Test public void missingSanIsAvailableAbsenceAndOtherProtocolNeverEmitsProviderString(){
        Leaf leaf=new Leaf(null);Session session=new Session(leaf);session.protocol=PRIVATE;String json=read(session);safe(json);
        assertTrue(json.contains("\"status\":\"AVAILABLE\""));assertTrue(json.contains("\"tls_protocol\":\"OTHER\""));assertTrue(json.contains("\"san_parser\":\"ABSENT\""));assertTrue(json.contains("\"san_present\":false"));
    }
    @Test public void emptyAndNonX509PeerChainsAreTypedWithoutOtherCertificateGetters(){
        unavailable(new DwSessionSummary.Collector().read("rss.dw.com",null).json(),"NO_SESSION");unavailable(read(new Session()),"NO_PEER");Certificate other=new Certificate("synthetic"){
            public byte[] getEncoded(){throw new AssertionError(PRIVATE);}public void verify(PublicKey key){throw new AssertionError(PRIVATE);}
            public void verify(PublicKey key,String provider){throw new AssertionError(PRIVATE);}public String toString(){throw new AssertionError(PRIVATE);}public PublicKey getPublicKey(){throw new AssertionError(PRIVATE);}
        };unavailable(read(new Session(other)),"NON_X509");
    }
    @Test public void oversizedChainStopsBeforeAnyLeafGetter(){
        Leaf leaf=new Leaf(null);Certificate[] chain=new Certificate[DwSessionSummary.MAX_CHAIN+1];Arrays.fill(chain,leaf);String json=read(new Session(chain));unavailable(json,"CHAIN_LIMIT");assertTrue(json.contains("\"chain_length\":8"));assertTrue(json.contains("\"chain_truncated\":true"));assertEquals(0,leaf.encodedCalls);assertEquals(0,leaf.sanCalls);
    }
    @Test public void derLimitIsCheckedBeforeHashAndSanAndBoundaryIsAccepted(){
        Leaf leaf=new Leaf(null);leaf.encoded=new byte[DwSessionSummary.MAX_DER+1];AtomicInteger hashes=new AtomicInteger();String json=new DwSessionSummary.Collector(bytes->{hashes.incrementAndGet();return new byte[32];}).read("rss.dw.com",new Session(leaf).proxy()).json();unavailable(json,"DER_LIMIT");assertEquals(0,hashes.get());assertEquals(0,leaf.sanCalls);
        leaf.encoded=new byte[DwSessionSummary.MAX_DER];assertTrue(read(new Session(leaf)).contains("\"status\":\"AVAILABLE\""));
    }
    @Test public void malformedSanEntriesNeverSerializeTheirContents(){
        for(List<?> entry:Arrays.<List<?>>asList(null,List.of(2),Arrays.asList(9,PRIVATE),Arrays.asList(PRIVATE,PRIVATE),Arrays.asList(2,new byte[]{1}),Arrays.asList(7,new byte[]{1}),Arrays.asList(6,new Object(){public String toString(){throw new AssertionError(PRIVATE);}}))){
            Leaf leaf=new Leaf(Collections.singletonList(entry));unavailable(read(new Session(leaf)),"SAN_MALFORMED");
        }
    }
    @Test public void sanCountAndNameCapsHaveExplicitTruncationAndNoUnboundedIteration(){
        AtomicInteger next=new AtomicInteger();Collection<List<?>> many=new AbstractCollection<List<?>>(){
            public int size(){throw new AssertionError("Do not materialize/size entire collection");}
            public Iterator<List<?>> iterator(){return new Iterator<List<?>>(){public boolean hasNext(){return true;}public List<?> next(){next.incrementAndGet();return san(2,PRIVATE);}};}
        };
        String json=read(new Session(new Leaf(many)));unavailable(json,"SAN_LIMIT");assertEquals(32,next.get());assertTrue(json.contains("\"san_truncated\":true"));
        unavailable(read(new Session(new Leaf(List.of(san(2,"a".repeat(254)))))),"SAN_NAME_LIMIT");
        unavailable(read(new Session(new Leaf(List.of(san(6,"a".repeat(254)))))),"SAN_NAME_LIMIT");
        unavailable(read(new Session(new Leaf(List.of(san(0,new byte[DwSessionSummary.MAX_DER+1]))))),"SAN_NAME_LIMIT");
        String boundary=read(new Session(new Leaf(Collections.nCopies(32,san(2,"a".repeat(253))))));safe(boundary);assertTrue(boundary.contains("\"status\":\"AVAILABLE\""));assertTrue(boundary.contains("\"dns_entries\":32"));
    }
    @Test public void thrownProviderGettersAndHasherProduceOnlyTypedUnavailable(){
        Leaf leaf=new Leaf(null);Session session=new Session(leaf);session.protocolFailure=true;unavailable(read(session),"PROTOCOL_FAILURE");assertEquals(0,session.peers);
        session=new Session(leaf);session.peerFailure=true;unavailable(read(session),"PEER_FAILURE");assertEquals(0,leaf.encodedCalls);
        leaf.encodingFailure=true;unavailable(read(new Session(leaf)),"ENCODING_FAILURE");assertEquals(0,leaf.sanCalls);
        leaf.encodingFailure=false;leaf.sanFailure=true;unavailable(read(new Session(leaf)),"SAN_FAILURE");
        leaf.sanFailure=false;unavailable(new DwSessionSummary.Collector(bytes->{throw new AssertionError(PRIVATE);}).read("rss.dw.com",new Session(leaf).proxy()).json(),"HASH_UNAVAILABLE");
        unavailable(new DwSessionSummary.Collector(bytes->new byte[31]).read("rss.dw.com",new Session(leaf).proxy()).json(),"HASH_UNAVAILABLE");
    }
    @Test public void asciiLiteralsAreNotWildcardMatchingOrUnicodeCaseFolding(){
        String json=read(new Session(new Leaf(List.of(san(2,"rſſ.dw.com"),san(2,"other.dw.com"),san(2,"rss.dw.com."),san(2,"*.other.com")))));safe(json);assertTrue(json.contains("\"san_literal_rss_dw_com\":false"));assertTrue(json.contains("\"san_literal_wildcard_dw_com\":false"));
    }
    @Test public void collectorRunsOnceAfterDelegateAndWriterOnlyAfterWorkerOutcome(){
        Clock clock=new Clock();AtomicReference<byte[]> output=new AtomicReference<>();DwNetworkDiagnostics d=DwNetworkDiagnostics.withSessionSummary("summary-one",clock,output::set);assertTrue(d.consume(DwNetworkDiagnostics.INPUT,false,"DW на русском"));
        Leaf leaf=new Leaf(List.of(san(2,PRIVATE)));Session session=new Session(leaf);AtomicBoolean delegateDone=new AtomicBoolean();session.beforeRead=()->assertTrue(delegateDone.get());AtomicInteger calls=new AtomicInteger();
        HostnameVerifier verifier=d.observeHostnameVerifier((h,s)->{calls.incrementAndGet();delegateDone.set(true);return false;});SSLSession actual=session.proxy();
        for(int i=0;i<100;i++)assertFalse(verifier.verify(PRIVATE,actual));assertEquals(100,calls.get());assertEquals(1,session.protocols);assertEquals(1,session.peers);assertEquals(1,leaf.encodedCalls);assertEquals(1,leaf.sanCalls);assertNull(output.get());
        d.failure(new RuntimeException(PRIVATE));byte[] saved=output.get();assertNotNull(saved);assertTrue(saved.length<=DwNetworkDiagnostics.MAX_BYTES);String json=new String(saved,StandardCharsets.UTF_8);assertFalse(json.contains(PRIVATE));assertTrue(json.contains("\"session_summary\""));assertTrue(json.contains("\"truncated\":true"));assertTrue(json.contains("\"host_is_exact_dw\":false"));
        assertFalse(verifier.verify(PRIVATE,actual));assertEquals(1,session.peers);assertSame(saved,output.get());
    }
    @Test public void unarmedExpiredOrClosedSummaryModeCannotReadSession(){
        Clock clock=new Clock();DwNetworkDiagnostics d=DwNetworkDiagnostics.withSessionSummary("summary-off",clock,bytes->{throw new AssertionError(PRIVATE);});HostnameVerifier original=(h,s)->false;
        assertSame(original,d.observeHostnameVerifier(original));assertFalse(d.consume(DwNetworkDiagnostics.INPUT+"?other",false,"DW на русском"));assertSame(original,d.observeHostnameVerifier(original));
        clock.now=60001;assertFalse(d.consume(DwNetworkDiagnostics.INPUT,false,"DW на русском"));assertSame(original,d.observeHostnameVerifier(original));
        d=DwNetworkDiagnostics.withSessionSummary("summary-close",new Clock(),bytes->{throw new AssertionError(PRIVATE);});assertTrue(d.consume(DwNetworkDiagnostics.INPUT,false,"DW на русском"));d.close();assertSame(original,d.observeHostnameVerifier(original));
    }
    @Test public void noHostnameObservationCommitsExplicitNotObservedWithinExistingByteCap(){
        AtomicReference<byte[]> out=new AtomicReference<>();DwNetworkDiagnostics d=DwNetworkDiagnostics.withSessionSummary("summary-none",new Clock(),out::set);assertTrue(d.consume(DwNetworkDiagnostics.INPUT,false,"DW на русском"));d.failure(new RuntimeException(PRIVATE));assertNotNull(out.get());String json=new String(out.get(),StandardCharsets.UTF_8);assertTrue(json.contains("\"status\":\"NOT_OBSERVED\""));assertFalse(json.contains(PRIVATE));assertTrue(out.get().length<DwNetworkDiagnostics.MAX_BYTES);
    }
}

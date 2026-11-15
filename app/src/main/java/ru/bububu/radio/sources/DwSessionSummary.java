package ru.bububu.radio.sources;

import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import javax.net.ssl.SSLSession;

/** Diagnostic facts only; never a certificate or hostname validator. No raw session strings survive. */
final class DwSessionSummary {
    static final int MAX_CHAIN=8,MAX_DER=64*1024,MAX_SAN=32,MAX_NAME=253,MAX_JSON=1024;
    enum Status { NOT_OBSERVED,AVAILABLE,UNAVAILABLE }
    enum Reason { NONE,NO_SESSION,PROTOCOL_FAILURE,PEER_FAILURE,NO_PEER,CHAIN_LIMIT,NON_X509,
        ENCODING_FAILURE,DER_LIMIT,HASH_UNAVAILABLE,SAN_FAILURE,SAN_MALFORMED,SAN_LIMIT,SAN_NAME_LIMIT }
    enum Protocol { TLS_1_2,TLS_1_3,OTHER }
    enum San { NOT_READ,ABSENT,PARSED,UNAVAILABLE }
    interface Hasher { byte[] digest(byte[] encoded); }
    static final class Collector {
        private final Hasher hasher;
        Collector(){Hasher ready;try{MessageDigest digest=MessageDigest.getInstance("SHA-256");ready=digest::digest;}catch(Throwable unavailable){ready=null;}hasher=ready;}
        Collector(Hasher hasher){this.hasher=hasher;}
        DwSessionSummary read(String host,SSLSession session){
            DwSessionSummary result=new DwSessionSummary("rss.dw.com".equals(host));Reason phase=Reason.PROTOCOL_FAILURE;
            try {
                if(session==null)return result.unavailable(Reason.NO_SESSION);
                String protocol=session.getProtocol();
                result.protocol="TLSv1.3".equals(protocol)?Protocol.TLS_1_3:"TLSv1.2".equals(protocol)?Protocol.TLS_1_2:Protocol.OTHER;
                phase=Reason.PEER_FAILURE;Certificate[] chain=session.getPeerCertificates();
                if(chain==null||chain.length==0)return result.unavailable(Reason.NO_PEER);
                result.chainLength=Math.min(chain.length,MAX_CHAIN);result.chainTruncated=chain.length>MAX_CHAIN;
                if(result.chainTruncated)return result.unavailable(Reason.CHAIN_LIMIT);
                if(!(chain[0] instanceof X509Certificate))return result.unavailable(Reason.NON_X509);
                result.leafX509=true;X509Certificate leaf=(X509Certificate)chain[0];
                phase=Reason.ENCODING_FAILURE;byte[] encoded=leaf.getEncoded();
                if(encoded==null||encoded.length==0||encoded.length>MAX_DER)return result.unavailable(Reason.DER_LIMIT);
                phase=Reason.HASH_UNAVAILABLE;if(hasher==null)return result.unavailable(Reason.HASH_UNAVAILABLE);
                byte[] digest=hasher.digest(encoded);if(digest==null||digest.length!=32)return result.unavailable(Reason.HASH_UNAVAILABLE);
                char[] hex=new char[64];char[] alphabet="0123456789abcdef".toCharArray();
                for(int i=0;i<32;i++){hex[i*2]=alphabet[(digest[i]&255)>>>4];hex[i*2+1]=alphabet[digest[i]&15];}result.leafSha256=new String(hex);
                phase=Reason.SAN_FAILURE;Collection<List<?>> alternatives=leaf.getSubjectAlternativeNames();
                if(alternatives==null){result.san=San.ABSENT;result.status=Status.AVAILABLE;return result;}
                result.sanPresent=true;phase=Reason.SAN_MALFORMED;Iterator<List<?>> iterator=alternatives.iterator();
                while(result.sanEntries<MAX_SAN&&iterator.hasNext()){
                    List<?> entry=iterator.next();result.sanEntries++;
                    if(entry==null||entry.size()!=2)return result.unavailable(Reason.SAN_MALFORMED);
                    Object tag=entry.get(0);if(!(tag instanceof Integer))return result.unavailable(Reason.SAN_MALFORMED);
                    int kind=(Integer)tag;if(kind<0||kind>8)return result.unavailable(Reason.SAN_MALFORMED);
                    Object value=entry.get(1);
                    if(value instanceof String){if(((String)value).length()>MAX_NAME)return result.unavailable(Reason.SAN_NAME_LIMIT);}
                    else if(value instanceof byte[]){if(((byte[])value).length>MAX_DER)return result.unavailable(Reason.SAN_NAME_LIMIT);}
                    else return result.unavailable(Reason.SAN_MALFORMED);
                    if(kind!=2&&kind!=7)continue;
                    if(!(value instanceof String))return result.unavailable(Reason.SAN_MALFORMED);
                    String name=(String)value;
                    if(kind==2){result.dnsEntries++;result.literalRss|=asciiLiteral(name,"rss.dw.com");result.literalWildcard|=asciiLiteral(name,"*.dw.com");}
                    else result.ipEntries++;
                }
                if(iterator.hasNext()){result.sanTruncated=true;return result.unavailable(Reason.SAN_LIMIT);}
                result.san=San.PARSED;result.status=Status.AVAILABLE;return result;
            }catch(Throwable unavailable){return result.unavailable(phase);}
        }
    }
    private final boolean hostExact;
    private Status status=Status.NOT_OBSERVED;
    private Reason reason=Reason.NONE;
    private Protocol protocol=Protocol.OTHER;
    private San san=San.NOT_READ;
    private int chainLength,sanEntries,dnsEntries,ipEntries;
    private boolean chainTruncated,leafX509,sanPresent,sanTruncated,literalRss,literalWildcard;
    private String leafSha256="";
    private DwSessionSummary(boolean hostExact){this.hostExact=hostExact;}
    static DwSessionSummary notObserved(){return new DwSessionSummary(false);}
    private DwSessionSummary unavailable(Reason why){status=Status.UNAVAILABLE;reason=why;san=San.UNAVAILABLE;leafSha256="";literalRss=false;literalWildcard=false;return this;}
    private static boolean asciiLiteral(String actual,String expected){
        if(actual.length()!=expected.length())return false;
        for(int i=0;i<actual.length();i++){char c=actual.charAt(i);if(c>='A'&&c<='Z')c=(char)(c+'a'-'A');if(c!=expected.charAt(i))return false;}return true;
    }
    String json(){
        return "{\"status\":\""+status+"\",\"unavailable_reason\":\""+reason+"\",\"host_is_exact_dw\":"+hostExact+
                ",\"tls_protocol\":\""+protocol+"\",\"chain_length\":"+chainLength+",\"chain_truncated\":"+chainTruncated+
                ",\"leaf_x509\":"+leafX509+",\"leaf_sha256\":\""+leafSha256+"\",\"san_parser\":\""+san+
                "\",\"san_present\":"+sanPresent+",\"san_entries\":"+sanEntries+",\"dns_entries\":"+dnsEntries+",\"ip_entries\":"+ipEntries+
                ",\"san_truncated\":"+sanTruncated+",\"san_literal_rss_dw_com\":"+literalRss+",\"san_literal_wildcard_dw_com\":"+literalWildcard+"}";
    }
}

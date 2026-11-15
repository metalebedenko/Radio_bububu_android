package ru.bububu.radio.sources;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.*;
import java.util.List;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;
import okhttp3.*;

/** Optional bounded memory observer. Only the owner publishes one failure outside network callbacks. */
public class LoadFailureSnapshot {
    public static final int MAX_BYTES=2048;
    public enum Phase { BATCH_FEED_GET, BATCH_FEED_PARSE, BATCH_FEED_PUBLISH, BATCH_BODY, BATCH_BODY_PUBLISH }
    enum Event { NONE, DNS_START, DNS_END, CONNECT_START, CONNECT_END, TLS_START, TLS_END, HEADERS_START, HEADERS_END, READ }
    private final SourceClock clock;
    private final long attempt,started;
    private Phase phase=Phase.BATCH_FEED_GET;
    private Event event=Event.NONE;
    private int http;
    private long bytes;
    private String exception="NONE",causeClass="NONE",profile="USER",sourceHash="NONE",parser="UNKNOWN",mime="NONE",finalHost="NONE",finalPath="NONE";
    LoadFailureSnapshot(SourceClock clock,long attempt){this.clock=clock;this.attempt=attempt;started=clock.monotonicMillis();}
    static LoadFailureSnapshot create(SourceClock clock,long attempt){try{return new LoadFailureSnapshot(clock,attempt);}catch(Throwable ignored){return null;}}
    synchronized void begin(Phase value){phase=value;event=Event.NONE;http=0;bytes=0;exception="NONE";causeClass="NONE";mime="NONE";finalHost="NONE";finalPath="NONE";}
    synchronized void phase(Phase value){phase=value;}
    synchronized void observe(Event value,int status,long received){event=value;if(status>=100&&status<=599)http=status;if(received>0)bytes=Math.min(Integer.MAX_VALUE,bytes+Math.min(received,Integer.MAX_VALUE));}
    synchronized void failure(Throwable error){String value=category(error);if(!value.equals("SOURCE")||exception.equals("NONE"))exception=value;for(int i=0;error!=null&&i<12;i++,error=error.getCause()){String name=error.getClass().getSimpleName();causeClass=name.matches("[A-Za-z0-9_$]{1,96}")?name:"OTHER";}}
    synchronized void context(SourceRecord source){SourceProfiles45.Profile p=SourceProfiles45.forSource(source);sourceHash=SourceLimits.sha256(source.id);profile=p==null?"USER":p.id;parser=p==null?(source.type.matches("[a-z0-9_]{1,32}")?source.type:"UNKNOWN"):p.discoveryKind().name()+"/"+SourceProfiles45.PARSER;}
    synchronized void response(int status,String type,String url){
        if(status>=100&&status<=599)http=status;mime=type!=null&&type.matches("[A-Za-z0-9.+-]{1,64}/[A-Za-z0-9.+-]{1,64}")?type:"OTHER";
        try{java.net.URI value=java.net.URI.create(url);String host=value.getHost();finalHost=host==null?"NONE":host.replaceAll("[^A-Za-z0-9.:-]","_");String path=value.getRawPath();finalPath=profile.equals("USER")?"REDACTED_USER_PATH":path==null?"/":path.replaceAll("[^A-Za-z0-9_./%~+@-]","_");if(finalPath.length()>192)finalPath=finalPath.substring(0,192);}catch(RuntimeException invalid){finalHost="INVALID";finalPath="NONE";}
    }
    static void contextSafe(LoadFailureSnapshot d,SourceRecord s){try{if(d!=null)d.context(s);}catch(Throwable ignored){}}
    static void responseSafe(LoadFailureSnapshot d,int status,String mime,String url){try{if(d!=null)d.response(status,mime,url);}catch(Throwable ignored){}}
    private static String category(Throwable error){
        String result="OTHER";
        for(int i=0;error!=null&&i<12;i++,error=error.getCause()){
            if(error instanceof SocketTimeoutException)return "SOCKET_TIMEOUT";
            if(error instanceof InterruptedIOException)return "INTERRUPTED_IO";
            if(error instanceof SSLException)return "SSL";
            if(error instanceof UnknownHostException)result="DNS";
            else if(error instanceof SourceFailure&&result.equals("OTHER"))result="SOURCE";
            else if(error instanceof IOException&&result.equals("OTHER"))result="IO";
            else if(error instanceof IllegalArgumentException&&result.equals("OTHER"))result="ARGUMENT";
            else if(error instanceof IllegalStateException&&result.equals("OTHER"))result="STATE";
        }
        return result;
    }
    synchronized String json(SourceFailureInfo primary){
        long now=clock.monotonicMillis(),elapsed=Math.max(0,now-started);
        String result="{\"schema\":1,\"attempt\":"+attempt+",\"started_monotonic_ms\":"+started+",\"captured_monotonic_ms\":"+now+",\"phase\":\""+phase.name()+"\",\"network_event\":\""+event.name()+"\",\"elapsed_ms\":"+elapsed+",\"exception_category\":\""+exception+"\",\"failure_kind\":\""+primary.kind.name()+"\",\"source_id_sha256\":\""+sourceHash+"\",\"profile\":\""+profile+"\",\"parser\":\""+parser+"\",\"mime\":\""+mime+"\",\"final_host\":\""+finalHost+"\",\"final_path\":\""+finalPath+"\",\"cause_class\":\""+causeClass+"\",\"received_http_status\":"+http+",\"wire_bytes_read\":"+bytes+"}\n";
        if(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalStateException("snapshot bound");return result;
    }
    static void beginSafe(LoadFailureSnapshot d,Phase p){try{if(d!=null)d.begin(p);}catch(Throwable ignored){}}
    static void phaseSafe(LoadFailureSnapshot d,Phase p){try{if(d!=null)d.phase(p);}catch(Throwable ignored){}}
    static void failureSafe(LoadFailureSnapshot d,Throwable e){try{if(d!=null)d.failure(e);}catch(Throwable ignored){}}
    static void eventSafe(LoadFailureSnapshot d,Event e,int status,long count){try{if(d!=null)d.observe(e,status,count);}catch(Throwable ignored){}}
    static void clearSafe(Consumer<String> writer){try{writer.accept(null);}catch(Throwable ignored){}}
    static void storeSafe(LoadFailureSnapshot d,SourceFailureInfo failure,Consumer<String> writer){try{if(d!=null)writer.accept(d.json(failure));}catch(Throwable ignored){}}
    static EventListener listener(LoadFailureSnapshot d){return new EventListener(){
        @Override public void callFailed(Call call,IOException error){failureSafe(d,error);}
        @Override public void dnsStart(Call call,String name){eventSafe(d,Event.DNS_START,0,0);}
        @Override public void dnsEnd(Call call,String name,List<InetAddress> addresses){eventSafe(d,Event.DNS_END,0,0);}
        @Override public void connectStart(Call call,InetSocketAddress address,Proxy proxy){eventSafe(d,Event.CONNECT_START,0,0);}
        @Override public void connectEnd(Call call,InetSocketAddress address,Proxy proxy,Protocol protocol){eventSafe(d,Event.CONNECT_END,0,0);}
        @Override public void secureConnectStart(Call call){eventSafe(d,Event.TLS_START,0,0);}
        @Override public void secureConnectEnd(Call call,Handshake handshake){eventSafe(d,Event.TLS_END,0,0);}
        @Override public void responseHeadersStart(Call call){eventSafe(d,Event.HEADERS_START,0,0);}
        @Override public void responseHeadersEnd(Call call,Response response){eventSafe(d,Event.HEADERS_END,response.code(),0);}
        @Override public void responseBodyStart(Call call){eventSafe(d,Event.READ,0,0);}
    };}
}

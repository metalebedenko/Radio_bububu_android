package ru.bububu.radio.sources;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.Protocol;

/** Explicit test-only, one exact catalog preview. No application startup/intent arms this observer. */
public final class DwNetworkDiagnostics implements AutoCloseable {
    public static final String INPUT="https://rss.dw.com/xml/rss-ru-all";
    public static final int MAX_BYTES=8192,MAX_EVENTS=32,MAX_CAUSES=4,MAX_TYPE=96;
    public interface Writer { void commit(byte[] safeJson) throws IOException; }
    enum Stage { PREVIEW_START,REQUEST_CHECK,HOST_GATE,DNS_BEFORE,DNS_AFTER,CONNECT_BEFORE,CONNECT_AFTER,
        TLS_BEFORE,TLS_AFTER,HOSTNAME_OBSERVER_ARMED,HOSTNAME_VERIFY_ENTER,HOSTNAME_VERIFY_TRUE,HOSTNAME_VERIFY_FALSE,
        HTTP_STATUS,WIRE_BODY_BEFORE,WIRE_BODY_AFTER,BODY_DECODE_BEFORE,BODY_DECODE_AFTER,
        PARSE_BEFORE,PARSE_AFTER,DISCOVERY_BEFORE,DISCOVERY_AFTER,NETWORK_FAILURE,RESULT_SUCCESS,RESULT_FAILURE }
    private enum Reason { NONE,NETWORK_ERROR,TIMEOUT,CANCELLED,HTTP_REFUSAL,COOLDOWN,POLICY_OR_INPUT,PARSE_OR_RESPONSE,OTHER }
    private final String attempt;
    private final SourceClock clock;
    private final Writer writer;
    private final long begin;
    private final List<String> events=new ArrayList<>();
    private final DwSessionSummary.Collector sessionCollector;
    private DwSessionSummary sessionSummary;
    private boolean sessionAttempted;
    private boolean used,closed,truncated,clockValid=true;
    private int bytes;
    private volatile String state="ARMED";

    public DwNetworkDiagnostics(String attempt,SourceClock clock,Writer writer) {
        this(attempt,clock,writer,false);
    }
    /** Explicit diagnostic27-only factory. Digest/provider preparation occurs before the TLS callback. */
    public static DwNetworkDiagnostics withSessionSummary(String attempt,SourceClock clock,Writer writer){return new DwNetworkDiagnostics(attempt,clock,writer,true);}
    private DwNetworkDiagnostics(String attempt,SourceClock clock,Writer writer,boolean includeSession) {
        if(attempt==null||!attempt.matches("[a-z0-9-]{1,48}")||clock==null||writer==null)
            throw new IllegalArgumentException("DIAGNOSTIC_CONTRACT");
        this.attempt=attempt;this.clock=clock;this.writer=writer;begin=clock.monotonicMillis();
        sessionCollector=includeSession?new DwSessionSummary.Collector():null;sessionSummary=includeSession?DwSessionSummary.notObserved():null;
    }
    synchronized boolean consume(String input,boolean telegram,String catalogueName) {
        if(used||closed)return false;
        long age=clock.monotonicMillis()-begin;
        if(age<0||age>60000){closed=true;state="EXPIRED";return false;}
        if(telegram||!INPUT.equals(input)||!"DW на русском".equals(catalogueName))return false;
        used=true;state="CAPTURING";mark(Stage.PREVIEW_START);return true;
    }
    synchronized void mark(Stage stage) { record(stage,Reason.NONE,null,null,false); }
    synchronized HostnameVerifier observeHostnameVerifier(HostnameVerifier original) {
        if(!used||closed)return original;
        try{return DwHostnameVerifierObserver.wrap(original,true,this::mark,sessionCollector==null?null:this::captureSession);}
        catch(Throwable observationFailure){return original;}
    }
    private void captureSession(String host,SSLSession session){
        synchronized(this){if(!used||closed||sessionCollector==null||sessionAttempted)return;sessionAttempted=true;}
        DwSessionSummary captured=sessionCollector.read(host,session);
        synchronized(this){if(!closed)sessionSummary=captured;}
    }
    synchronized void networkFailure(Throwable failure) { record(Stage.NETWORK_FAILURE,Reason.OTHER,failure,null,false); }
    private synchronized void status(int status) {
        if(status>=100&&status<=599)record(Stage.HTTP_STATUS,Reason.NONE,null,status,false);
    }
    private void record(Stage stage,Reason reason,Throwable failure,Integer status,boolean terminal) {
        if(!used||closed)return;
        try {
            if(events.size()>=MAX_EVENTS-(terminal?0:1)){truncated=true;return;}
            long elapsed=clock.monotonicMillis()-begin;
            if(elapsed<0||elapsed>180000){clockValid=false;elapsed=0;}
            String item="{\"stage\":\""+stage.name()+"\",\"elapsed_ms\":"+elapsed+",\"reason\":\""+reason.name()+"\"";
            if(status!=null)item+=",\"http_status\":"+status;
            if(failure!=null)item+=",\"exception_types\":"+types(failure);
            item+="}";
            if(bytes+item.length()>MAX_BYTES-(terminal?512:1536)-(sessionCollector==null?0:DwSessionSummary.MAX_JSON)){truncated=true;return;}
            events.add(item);bytes+=item.length()+1;
        } catch(Throwable observationFailure) { truncated=true; }
    }
    void success(){try{finish(null);}catch(Throwable unavailable){state="CAPTURE_FAILED";}}
    void failure(Throwable failure){try{finish(failure);}catch(Throwable unavailable){state="CAPTURE_FAILED";}}
    private void finish(Throwable failure) {
        final byte[] payload;
        synchronized(this) {
            if(!used||closed)return;
            record(failure==null?Stage.RESULT_SUCCESS:Stage.RESULT_FAILURE,reason(failure),failure,null,true);
            closed=true;
            String json="{\"attempt_id\":\""+attempt+"\",\"source_id\":\"dw-russian\",\"truncated\":"+truncated+
                    ",\"clock_valid\":"+clockValid+",\"events\":["+String.join(",",events)+"]"+
                    (sessionSummary==null?"":",\"session_summary\":"+sessionSummary.json())+"}\n";
            payload=json.getBytes(StandardCharsets.UTF_8);
            if(payload.length>MAX_BYTES){state="BOUND_STOP";return;}
        }
        // No disk IO in OkHttp callbacks. A test-owned writer is called only after worker outcome, before UI projection.
        try{writer.commit(payload);state="COMMITTED";}catch(Throwable writeFailure){state="WRITE_FAILED";}
    }
    public String captureState(){return state;}
    @Override public synchronized void close(){if(!closed){closed=true;state="DISARMED";}}
    private static Reason reason(Throwable error) {
        if(error==null)return Reason.NONE;
        String value=error instanceof SourceFailure?((SourceFailure)error).reason:"";
        if(value==null)value="";
        if("NETWORK_ERROR".equals(value))return Reason.NETWORK_ERROR;
        if("TIMEOUT".equals(value))return Reason.TIMEOUT;
        if("CANCELLED".equals(value))return Reason.CANCELLED;
        if(error instanceof HttpStatusFailure||value.matches("HTTP_[1-5][0-9]{2}"))return Reason.HTTP_REFUSAL;
        if(error instanceof CooldownFailure)return Reason.COOLDOWN;
        if(value.startsWith("URL_")||value.startsWith("HTTPS_")||value.startsWith("DNS_")||value.startsWith("PRIVATE_"))return Reason.POLICY_OR_INPUT;
        if(value.startsWith("XML_")||value.startsWith("MIME_")||value.startsWith("DECODED_")||value.equals("CONTENT_ENCODING")||value.equals("NO_SUPPORTED_FEED"))return Reason.PARSE_OR_RESPONSE;
        return Reason.OTHER;
    }
    private static String types(Throwable error) {
        List<String> values=new ArrayList<>();Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(int i=0;i<MAX_CAUSES&&error!=null&&seen.add(error);i++) {
            String type=error.getClass().getName();
            if(type.length()>MAX_TYPE||!type.matches("[A-Za-z_$][A-Za-z0-9_.$]*"))type="TYPE_OTHER";
            values.add("\""+type+"\"");
            try{error=error.getCause();}catch(Throwable unavailable){break;}
        }
        return "["+String.join(",",values)+"]";
    }
    EventListener listener() {
        return new EventListener() {
            @Override public void dnsStart(Call c,String ignored){mark(Stage.DNS_BEFORE);}
            @Override public void dnsEnd(Call c,String ignored,List<InetAddress> discarded){mark(Stage.DNS_AFTER);}
            @Override public void connectStart(Call c,InetSocketAddress ignored,Proxy discarded){mark(Stage.CONNECT_BEFORE);}
            @Override public void connectEnd(Call c,InetSocketAddress ignored,Proxy discarded,Protocol unused){mark(Stage.CONNECT_AFTER);}
            @Override public void secureConnectStart(Call c){mark(Stage.TLS_BEFORE);}
            @Override public void secureConnectEnd(Call c,Handshake discarded){mark(Stage.TLS_AFTER);}
            @Override public void responseHeadersEnd(Call c,okhttp3.Response response){status(response.code());}
            @Override public void responseBodyStart(Call c){mark(Stage.WIRE_BODY_BEFORE);}
            @Override public void responseBodyEnd(Call c,long discarded){mark(Stage.WIRE_BODY_AFTER);}
            @Override public void callFailed(Call c,IOException failure){networkFailure(failure);}
        };
    }
}

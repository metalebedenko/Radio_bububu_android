package ru.bububu.radio.sources;

import okhttp3.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

public final class SafeHttpTransport implements SourceTransport,AutoCloseable {
    private static final Map<String,HostRequestGate> PROCESS_GATES=new HashMap<>();
    private final OkHttpClient base;
    private final PublicIpPolicy policy;
    private final PublicDns.Resolver resolver;
    private final HostRequestGate gate;
    private final Set<Call> activeCalls=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public SafeHttpTransport(File privateDirectory)throws SourceFailure {
        this(privateDirectory,SourceClock.SYSTEM);
    }
    public SafeHttpTransport(File privateDirectory,SourceClock clock)throws SourceFailure {
        this(defaultClient(),new PublicIpPolicy(),host->Arrays.asList(InetAddress.getAllByName(host)),processGate(privateDirectory,clock));
    }
    // Package-only fixture seam: never selected by an app intent or user URL.
    SafeHttpTransport(OkHttpClient trustedTestClient,PublicIpPolicy policy,PublicDns.Resolver resolver,HostRequestGate gate) {
        this.base=trustedTestClient.newBuilder().proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES).authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).fastFallback(false)
            .protocols(Collections.singletonList(Protocol.HTTP_1_1)).connectionPool(new ConnectionPool(0,1,TimeUnit.NANOSECONDS)).cache(null).build();
        this.policy=policy;this.resolver=resolver;this.gate=gate;
    }
    private static OkHttpClient defaultClient(){return new OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).build();}
    private static synchronized HostRequestGate processGate(File directory,SourceClock clock)throws SourceFailure {
        try{String key=directory.getCanonicalPath();HostRequestGate gate=PROCESS_GATES.get(key);if(gate==null){gate=new HostRequestGate(new HostCooldownStore(directory),clock);PROCESS_GATES.put(key,gate);}return gate;}
        catch(IOException failure){throw new SourceFailure("COOLDOWN_STORAGE",failure);}
    }
    public static String normalizeUrl(String value)throws SourceFailure{return checkedUrl(value).newBuilder().fragment(null).build().toString();}
    private static HttpUrl checkedUrl(String value)throws SourceFailure {
        if(value==null||value.length()>SourceLimits.MAX_URL)throw new SourceFailure("URL_LIMIT");
        for(int i=0;i<value.length();i++)if(value.charAt(i)<=32 || value.charAt(i)==127 || value.charAt(i)=='\\')throw new SourceFailure("URL_SYNTAX");
        try {
            URI uri=new URI(value);if(uri.getRawUserInfo()!=null)throw new SourceFailure("URL_CREDENTIALS");
            // Classify an empty DNS label before HttpUrl's canonical-host rejection.
            String authority=uri.getRawAuthority();
            if(authority!=null&&authority.indexOf('@')<0&&!authority.startsWith("[")) {
                int colon=authority.lastIndexOf(':');String rawHost=colon<0?authority:authority.substring(0,colon);
                if(rawHost.contains(".."))throw new SourceFailure("URL_HOST");
            }
            HttpUrl url=HttpUrl.get(value);
            if(!(url.scheme().equals("https")||url.scheme().equals("http"))||!url.username().isEmpty()||!url.password().isEmpty())throw new SourceFailure("HTTPS_REQUIRED");
            String host=url.host().toLowerCase(Locale.ROOT);String bare=host.endsWith(".")?host.substring(0,host.length()-1):host;
            if(bare.equals("localhost")||bare.endsWith(".localhost")||host.contains("%")||host.length()>253)throw new SourceFailure("URL_HOST");
            if(host.indexOf(':')<0)for(String label:bare.split("\\.",-1))if(!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))throw new SourceFailure("URL_HOST");
            if(isNumeric(host)) {
                if(host.indexOf(':')<0) {
                    String[] octets=host.split("\\.");if(octets.length!=4)throw new SourceFailure("URL_HOST");
                    for(String octet:octets){if(octet.length()>3||Integer.parseInt(octet)>255||octet.length()>1&&octet.charAt(0)=='0')throw new SourceFailure("URL_HOST");}
                }
                try{new PublicIpPolicy().require(InetAddress.getByName(host));}catch(UnknownHostException invalid){throw new SourceFailure("URL_HOST",invalid);}
            }
            return url.newBuilder().fragment(null).build();
        }catch(URISyntaxException|IllegalArgumentException invalid){throw new SourceFailure("URL_SYNTAX",invalid);}
    }
    @Override public SourceFailureInfo cooldown(String raw) {
        try {
            HttpUrl url=checkedUrl(raw);SourceFailureInfo direct=gate.failure(url.host());
            if(direct!=null)return direct;
            SourceFailureInfo associated=gate.actionFailure(actionKey(url));
            return associated==null?direct:associated;
        } catch(SourceFailure invalid) { return null; }
    }
    @Override public SourceFailureInfo actionCooldown(String action) { return gate.actionFailure(SourceLimits.sha256("operation:"+action)); }
    @Override public void associateAction(String action,String checkedTarget) {
        try { HttpUrl target=checkedUrl(checkedTarget);gate.associateAction(SourceLimits.sha256("operation:"+action),actionKey(target),target.host()); }
        catch(SourceFailure invalid) { /* An invalid/unconfirmed target never establishes an association. */ }
    }
    @Override public Response fetch(String raw,Kind kind,Validators validators,RequestScope scope)throws SourceFailure {
        return fetchObservedForTest(raw,kind,validators,scope,null);
    }
    // Only SourceCoordinator's explicitly armed exact-DW test attempt supplies an observer.
    Response fetchObservedForTest(String raw,Kind kind,Validators validators,RequestScope scope,DwNetworkDiagnostics diagnostic)throws SourceFailure {
        if(diagnostic!=null)diagnostic.mark(DwNetworkDiagnostics.Stage.REQUEST_CHECK);
        if(closed)throw new SourceFailure("TRANSPORT_CLOSED");scope.check();if(validators==null)validators=Validators.NONE;
        long deadline=Math.min(scope.deadlineMillis,SourceClock.saturatedAdd(scope.clock.monotonicMillis(),30_000));
        HttpUrl url=checkedUrl(raw);Set<String> visited=new HashSet<>();
        final java.util.function.Predicate<String> targetPolicy=scope.targetPolicy();
        SourceFailureInfo known=cooldown(raw);
        if(known!=null&&known.retryBlocked(scope.clock))throw known.rejection(scope.clock);
        for(int redirect=0;;redirect++) {
            check(scope,deadline);
            if(targetPolicy!=null){boolean approved;try{approved=targetPolicy.test(url.toString());}catch(RuntimeException badPolicy){throw new SourceFailure("PROFILE_TARGET_FORBIDDEN",badPolicy);}if(!approved)throw new SourceFailure("PROFILE_TARGET_FORBIDDEN");}
            if(!visited.add(url.toString()))throw new SourceFailure("REDIRECT_LOOP");
            PublicDns dns=new PublicDns(policy,resolver,scope,deadline);
            if(isNumeric(url.host())) {try{dns.vetLiteral(url.host(),InetAddress.getByName(url.host()));}catch(UnknownHostException invalid){throw new SourceFailure("URL_HOST",invalid);}}
            if(diagnostic!=null)diagnostic.mark(DwNetworkDiagnostics.Stage.HOST_GATE);
            try(HostRequestGate.Lease lease=gate.acquire(url.host(),scope,deadline)) {
                final String host=url.host();
                OkHttpClient.Builder builder=base.newBuilder().dns(dns).connectTimeout(Math.min(10_000,remaining(scope,deadline)),TimeUnit.MILLISECONDS).readTimeout(Math.min(10_000,remaining(scope,deadline)),TimeUnit.MILLISECONDS)
                    .callTimeout(remaining(scope,deadline),TimeUnit.MILLISECONDS)
                    .addNetworkInterceptor(chain->{if(chain.connection()==null)throw new SourceFailure("UNVETTED_PEER");dns.requirePeer(host,chain.connection().socket().getInetAddress());lease.beforeRequest(scope,deadline);return chain.proceed(chain.request());})
                    .addNetworkInterceptor(new ErrorResponseInterceptor(gate,scope));
                if(diagnostic!=null){
                    builder.eventListener(diagnostic.listener());
                    builder.hostnameVerifier(diagnostic.observeHostnameVerifier(base.hostnameVerifier()));
                } else if(scope.loadFailureSnapshot!=null)builder.eventListener(LoadFailureSnapshot.listener(scope.loadFailureSnapshot));
                OkHttpClient client=builder.build();
                Request.Builder request=new Request.Builder().url(url).header("Accept-Encoding","gzip").header("User-Agent","RadioBububuAndroid/0.1");
                if(validators.etag!=null&&!validators.etag.isEmpty())request.header("If-None-Match",header(validators.etag));
                if(validators.lastModified!=null&&!validators.lastModified.isEmpty())request.header("If-Modified-Since",header(validators.lastModified));
                Call call=client.newCall(request.build());Runnable cancel=call::cancel;activeCalls.add(call);scope.bindCancellation(cancel);if(closed)call.cancel();
                try(okhttp3.Response result=call.execute()) {
                    check(scope,deadline);int status=result.code();String observedType=result.header("Content-Type","").split(";",2)[0].trim().toLowerCase(Locale.ROOT);LoadFailureSnapshot.responseSafe(scope.loadFailureSnapshot,status,observedType,url.toString());
                    if(status==301||status==302||status==303||status==307||status==308) {
                        if(redirect>=3)throw new SourceFailure("REDIRECT_LIMIT");String location=result.header("Location");if(location==null||location.length()>SourceLimits.MAX_URL)throw new SourceFailure("REDIRECT_LOCATION");
                        URI next=new URI(url.toString()).resolve(location);HttpUrl redirected=checkedUrl(next.toString());if(url.isHttps()&&!redirected.isHttps())throw new SourceFailure("HTTPS_REQUIRED");url=redirected;
                        // Validators/cache belong to the requested resource, never an unconfirmed redirect target.
                        validators=Validators.NONE;continue;
                    }
                    if(status==304) {
                        if(!validators.hasSuccessfulCache || (validators.etag==null&&validators.lastModified==null))throw new SourceFailure("UNEXPECTED_304");
                        return new Response(new byte[0],url.toString(),304,null,null,result.header("ETag"),result.header("Last-Modified"));
                    }
                    if(status!=200)throw new SourceFailure("HTTP_"+status);
                    String contentType=result.header("Content-Type","");String mime=contentType.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
                    boolean xml=isXmlMime(mime);boolean html=mime.equals("text/html")||mime.equals("application/xhtml+xml");
                    boolean json=mime.equals("application/json");
                    if(kind==Kind.XML&&!xml || kind==Kind.HTML&&!xml&&!html || kind==Kind.JSON&&!json)throw new SourceFailure("MIME_MISMATCH");
                    int cap=xml?SourceLimits.XML_BYTES:SourceLimits.HTML_BYTES;
                    if(result.body()==null)throw new SourceFailure("EMPTY_HTTP_BODY");
                    if(diagnostic!=null)diagnostic.mark(DwNetworkDiagnostics.Stage.BODY_DECODE_BEFORE);
                    InputStream wire=new LimitedInputStream(result.body().byteStream(),cap,scope,deadline,"WIRE_LIMIT",scope.loadFailureSnapshot);
                    String encoding=result.header("Content-Encoding","").trim();
                    InputStream decoded;
                    if(encoding.isEmpty()||encoding.equalsIgnoreCase("identity"))decoded=wire;
                    else if(encoding.equalsIgnoreCase("gzip"))decoded=new GZIPInputStream(wire);
                    else throw new SourceFailure("CONTENT_ENCODING");
                    byte[] bytes=read(new LimitedInputStream(decoded,cap,scope,deadline,"DECODED_LIMIT"));
                    if(diagnostic!=null)diagnostic.mark(DwNetworkDiagnostics.Stage.BODY_DECODE_AFTER);
                    if(bytes.length==0)throw new SourceFailure("EMPTY_HTTP_BODY");
                    check(scope,deadline);
                    return new Response(bytes,url.toString(),200,mime,charset(contentType),header(result.header("ETag")),header(result.header("Last-Modified")));
                }catch(SourceFailure failure){throw failure;}
                catch(URISyntaxException invalid){throw new SourceFailure("REDIRECT_LOCATION",invalid);}
                catch(IOException failure){
                    if(diagnostic!=null)diagnostic.networkFailure(failure);
                    Throwable cause=failure;for(int i=0;i<12&&cause!=null;i++,cause=cause.getCause())if(cause instanceof SourceFailure)throw (SourceFailure)cause;
                    check(scope,deadline);throw new SourceFailure(failure instanceof java.io.InterruptedIOException?"TIMEOUT":"NETWORK_ERROR",failure);
                }catch(IllegalArgumentException invalid){throw new SourceFailure("HEADER_SYNTAX",invalid);}
                finally{activeCalls.remove(call);scope.clearCancellation(cancel);}
            } catch(SourceFailure failure) {
                // Only an actual HTTP refusal or a gate refusal reached through checked redirects
                // establishes this action-to-target relation. Cancellation never clears it.
                if(failure instanceof HttpStatusFailure || failure instanceof CooldownFailure) {
                    int status=failure instanceof HttpStatusFailure?((HttpStatusFailure)failure).status:((CooldownFailure)failure).status;
                    for(String checked:visited)gate.rememberAction(actionKey(HttpUrl.get(checked)),url.host(),status);
                }
                throw failure;
            }
        }
    }
    private static String actionKey(HttpUrl url) { return SourceLimits.sha256(url.newBuilder().fragment(null).build().toString()); }
    public static boolean isXmlMime(String mime){return "text/xml".equals(mime)||"application/xml".equals(mime)||"application/rss+xml".equals(mime)||"application/atom+xml".equals(mime)||"application/rdf+xml".equals(mime);}
    private static boolean isNumeric(String host){if(host.indexOf(':')>=0)return true;return host.matches("[0-9]+(?:\\.[0-9]+){0,3}");}
    private static String header(String value)throws SourceFailure {if(value==null)return null;if(value.length()>4096)throw new SourceFailure("HEADER_LIMIT");for(int i=0;i<value.length();i++)if(value.charAt(i)<32&&value.charAt(i)!='\t'||value.charAt(i)>126)throw new SourceFailure("HEADER_SYNTAX");return value;}
    private static String charset(String value){for(String segment:value.split(";")){String trimmed=segment.trim();if(trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")){String name=trimmed.substring(8).trim();if(name.length()>=2&&name.charAt(0)=='"'&&name.charAt(name.length()-1)=='"')name=name.substring(1,name.length()-1);return name;}}return null;}
    private static long remaining(RequestScope scope,long deadline)throws SourceFailure{check(scope,deadline);return Math.max(1,deadline-scope.clock.monotonicMillis());}
    private static void check(RequestScope scope,long deadline)throws SourceFailure{scope.check();if(scope.clock.monotonicMillis()>=deadline)throw new SourceFailure("TIMEOUT");}
    private static byte[] read(InputStream in)throws IOException {try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=stream.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}}
    private static final class LimitedInputStream extends FilterInputStream {
        private final int cap;private final RequestScope scope;private final long deadline;private final String reason;private int count;private LoadFailureSnapshot observer;
        LimitedInputStream(InputStream in,int cap,RequestScope scope,long deadline,String reason){super(in);this.cap=cap;this.scope=scope;this.deadline=deadline;this.reason=reason;}
        LimitedInputStream(InputStream in,int cap,RequestScope scope,long deadline,String reason,LoadFailureSnapshot observer){this(in,cap,scope,deadline,reason);this.observer=observer;}
        public int read()throws IOException{check(scope,deadline);int result=in.read();if(result>=0)LoadFailureSnapshot.eventSafe(observer,LoadFailureSnapshot.Event.READ,0,1);if(result>=0&&++count>cap)throw new SourceFailure(reason);return result;}
        public int read(byte[] bytes,int offset,int length)throws IOException{check(scope,deadline);int n=in.read(bytes,offset,Math.min(length,Math.max(1,cap-count+1)));if(n>0){LoadFailureSnapshot.eventSafe(observer,LoadFailureSnapshot.Event.READ,0,n);count+=n;if(count>cap)throw new SourceFailure(reason);}return n;}
    }
    @Override public void close(){closed=true;for(Call call:activeCalls)call.cancel();}
}

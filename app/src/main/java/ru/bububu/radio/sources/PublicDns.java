package ru.bububu.radio.sources;

import okhttp3.Dns;
import java.net.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public final class PublicDns implements Dns {
    public interface Resolver { List<InetAddress> lookup(String host)throws UnknownHostException; }
    private static final ThreadPoolExecutor RESOLVERS=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"source-dns");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final PublicIpPolicy policy;private final Resolver resolver;private final RequestScope scope;private final long deadline;
    private final Map<String,List<InetAddress>> vetted=new ConcurrentHashMap<>();
    public PublicDns(PublicIpPolicy policy,Resolver resolver,RequestScope scope,long deadline){this.policy=policy;this.resolver=resolver;this.scope=scope;this.deadline=deadline;}
    @Override public List<InetAddress> lookup(String hostname)throws UnknownHostException {
        Future<List<InetAddress>> future;
        try{future=RESOLVERS.submit(()->resolver.lookup(hostname));}catch(RejectedExecutionException busy){throw unknown("DNS_BUSY",busy);}
        try {
            List<InetAddress> addresses;
            while(true){check();try{addresses=future.get(Math.max(1,Math.min(50,deadline-scope.clock.monotonicMillis())),TimeUnit.MILLISECONDS);break;}catch(TimeoutException wait){/* bounded wait, recheck scope */}}
            check();if(addresses==null||addresses.isEmpty()||addresses.size()>64)throw new SourceFailure("DNS_RESPONSE");
            List<InetAddress> copy=new ArrayList<>(addresses);for(InetAddress address:copy)policy.require(address);
            vetted.put(hostname,Collections.unmodifiableList(copy));return copy;
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw unknown("CANCELLED",cancelled);}
        catch(ExecutionException failure){throw unknown("DNS_FAILURE",failure.getCause());}
        catch(SourceFailure failure){throw unknown(failure.reason,failure);}
        finally{future.cancel(true);}
    }
    public void vetLiteral(String host,InetAddress address)throws SourceFailure{policy.require(address);vetted.put(host,Collections.singletonList(address));}
    public void requirePeer(String host,InetAddress actual)throws SourceFailure {
        policy.require(actual);List<InetAddress> list=vetted.get(host);if(list==null)throw new SourceFailure("UNVETTED_PEER");
        for(InetAddress address:list)if(Arrays.equals(address.getAddress(),actual.getAddress()))return;
        throw new SourceFailure("PEER_MISMATCH");
    }
    private void check()throws SourceFailure{scope.check();if(scope.clock.monotonicMillis()>=deadline)throw new SourceFailure("TIMEOUT");}
    private static UnknownHostException unknown(String reason,Throwable cause){UnknownHostException result=new UnknownHostException(reason);result.initCause(cause);return result;}
}

package ru.bububu.radio.sources;

import java.util.*;

public final class HostRequestGate {
    private static final class Host { boolean busy,blocked; int waiters; long until,lastStart=Long.MIN_VALUE,utc; }
    public final class Lease implements AutoCloseable {
        public final String host; private boolean closed;
        private Lease(String host){this.host=host;}
        public void beforeRequest(RequestScope scope,long fetchDeadline)throws SourceFailure {
            synchronized(HostRequestGate.this){Host state=hosts.get(host);while(true){check(scope,fetchDeadline);long now=clock.monotonicMillis();long interval=state.lastStart==Long.MIN_VALUE?0:Math.max(0,SourceClock.saturatedAdd(state.lastStart,1000)-now);if(interval==0){state.lastStart=now;return;}sleep(scope,fetchDeadline,interval);}}
        }
        public void close(){synchronized(HostRequestGate.this){if(!closed){closed=true;hosts.get(host).busy=false;HostRequestGate.this.notifyAll();}}}
    }
    private final SourceClock clock; private final HostCooldownStore store;
    private final Map<String,Host> hosts=new HashMap<>(); private boolean storageBlocked;
    // Presentation associations are process-local, unlike the persisted server-host gate.
    // Keys are hashes of checked action URLs; this bounded cache never authorizes a request.
    private static final class ActionTarget {
        final String host; final int status;
        ActionTarget(String host,int status){this.host=host;this.status=status;}
    }
    private final LinkedHashMap<String,ActionTarget> actionTargets=new LinkedHashMap<>(16,0.75f,true);
    synchronized void rememberAction(String actionKey,String host,int status) {
        SourceFailureInfo blocked=failure(host);
        if(blocked==null||!blocked.retryBlocked(clock))return;
        actionTargets.put(actionKey,new ActionTarget(host,status));
        int maximum=SourceLimits.MAX_ACTION_TARGETS;
        while(actionTargets.size()>maximum)actionTargets.remove(actionTargets.keySet().iterator().next());
    }
    synchronized void associateAction(String actionKey,String checkedUrlKey,String directHost) {
        ActionTarget confirmed=actionTargets.get(checkedUrlKey);
        if(confirmed!=null) {
            SourceFailureInfo blocked=failure(confirmed.host);
            if(blocked!=null&&blocked.retryBlocked(clock)){rememberAction(actionKey,confirmed.host,confirmed.status);return;}
        }
        rememberAction(actionKey,directHost,0);
    }
    synchronized SourceFailureInfo actionFailure(String actionKey) {
        ActionTarget target=actionTargets.get(actionKey);if(target==null)return null;
        SourceFailureInfo blocked=failure(target.host);
        if(blocked==null||!blocked.retryBlocked(clock)){actionTargets.remove(actionKey);return null;}
        return blocked.withStatus(target.status);
    }
    public HostRequestGate(HostCooldownStore store,SourceClock clock) {
        this.store=store;this.clock=clock;
        try{if(store!=null)for(Map.Entry<String,HostCooldownStore.Entry> e:store.load().entrySet()){Host h=new Host();h.blocked=e.getValue().blocked;h.utc=e.getValue().notBeforeUtc;h.until=SourceClock.saturatedAdd(clock.monotonicMillis(),Math.max(0,h.utc-clock.wallMillis()));hosts.put(e.getKey(),h);}}
        catch(SourceFailure failure){storageBlocked=true;}
    }
    public synchronized Lease acquire(String host,RequestScope scope,long fetchDeadline)throws SourceFailure {
        check(scope,fetchDeadline);if(storageBlocked)throw new SourceFailure("COOLDOWN_STORAGE");
        hosts.entrySet().removeIf(e->!e.getValue().busy&&e.getValue().waiters==0&&!e.getValue().blocked&&e.getValue().until<=clock.monotonicMillis()&&(e.getValue().lastStart==Long.MIN_VALUE||SourceClock.saturatedAdd(e.getValue().lastStart,1000)<=clock.monotonicMillis()));
        Host state=hosts.get(host);if(state==null){if(hosts.size()>=64)throw new SourceFailure("HOST_LIMIT");state=new Host();hosts.put(host,state);}
        state.waiters++;
        try{while(state.busy){sleep(scope,fetchDeadline,50);if(storageBlocked)throw new SourceFailure("COOLDOWN_STORAGE");}}
        finally{state.waiters--;}
        if(state.blocked)throw new CooldownFailure("COOLDOWN_UNREPRESENTABLE",Long.MAX_VALUE,clock,true);
        if(state.until>clock.monotonicMillis())throw new CooldownFailure("HOST_COOLDOWN",state.until-clock.monotonicMillis(),clock,false);
        state.busy=true;return new Lease(host);
    }
    public synchronized void observe(String host,RetryAfterParser.Result result)throws SourceFailure {
        Host state=hosts.computeIfAbsent(host,key->new Host());
        if(result.kind==RetryAfterParser.Kind.EXCESSIVE){state.blocked=true;state.utc=Long.MAX_VALUE;state.until=Long.MAX_VALUE;}
        if(result.kind==RetryAfterParser.Kind.DELAY){state.until=Math.max(state.until,SourceClock.saturatedAdd(clock.monotonicMillis(),result.delayMillis));state.utc=Math.max(state.utc,SourceClock.saturatedAdd(clock.wallMillis(),result.delayMillis));}
        if(store!=null) {
            Map<String,HostCooldownStore.Entry> saved=new HashMap<>();long now=clock.monotonicMillis();
            for(Map.Entry<String,Host> e:hosts.entrySet())if(e.getValue().blocked||e.getValue().until>now)saved.put(e.getKey(),new HostCooldownStore.Entry(e.getValue().utc,e.getValue().blocked));
            try{store.save(saved);}catch(SourceFailure failure){state.blocked=true;storageBlocked=true;throw failure;}
        }
        notifyAll();
    }
    public synchronized SourceFailureInfo failure(String host) {
        if(storageBlocked)return SourceFailureInfo.forReason("COOLDOWN_STORAGE");
        Host state=hosts.get(host);if(state==null)return null;
        if(state.blocked)return SourceFailureInfo.from(new CooldownFailure("COOLDOWN_UNREPRESENTABLE",Long.MAX_VALUE,clock,true),clock);
        long remaining=state.until-clock.monotonicMillis();
        return remaining<=0?null:SourceFailureInfo.from(new CooldownFailure("HOST_COOLDOWN",remaining,clock,false),clock);
    }
    public synchronized long remaining(String host){Host state=hosts.get(host);return state==null?0:state.blocked?Long.MAX_VALUE:Math.max(0,state.until-clock.monotonicMillis());}
    private void check(RequestScope scope,long deadline)throws SourceFailure{scope.check();if(clock.monotonicMillis()>=deadline)throw new SourceFailure("TIMEOUT");}
    private void sleep(RequestScope scope,long deadline,long delay)throws SourceFailure {check(scope,deadline);try{wait(Math.max(1,Math.min(50,delay)));}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new SourceFailure("CANCELLED");}}
}

package ru.bububu.radio.models;
import org.junit.Test;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import static org.junit.Assert.*;
public final class ModelWorkQueueTest {
    @Test public void foregroundUiAdmissionClosesAfterFirstReservationEvenOnDoubleTap() {
        ArrayDeque<Runnable> work=new ArrayDeque<>(),owner=new ArrayDeque<>();ModelWorkQueue q=new ModelWorkQueue(work::add,owner::add,x->{});Reply first=new Reply(),second=new Reply();AtomicInteger foregroundCalls=new AtomicInteger();
        q.submit(true,t->"prefetch",new Reply());assertTrue(q.busy());assertFalse(q.foregroundBlocked());
        q.submit(false,t->{foregroundCalls.incrementAndGet();return "explicit prepare";},first);assertTrue(q.busy());assertTrue(q.foregroundBlocked());
        q.submit(false,t->{foregroundCalls.incrementAndGet();return "double tap";},second);
        work.remove().run();while(!owner.isEmpty())owner.remove().run();assertEquals(1,second.errors.size());assertEquals(1,work.size());assertTrue(q.foregroundBlocked());
        work.remove().run();while(!owner.isEmpty())owner.remove().run();assertEquals(1,foregroundCalls.get());assertEquals(Arrays.asList("explicit prepare"),first.ok);assertFalse(q.busy());assertFalse(q.foregroundBlocked());
    }
    private static final class Reply implements ModelWorkQueue.Result<String>{final List<String> ok=new ArrayList<>(),errors=new ArrayList<>();public void success(String s){ok.add(s);}public void error(String s){errors.add(s);}}
    @Test public void runningSpeculationKeepsBusyUntilFinallyAndForegroundRunsNext()throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();LinkedBlockingQueue<Runnable> owner=new LinkedBlockingQueue<>();List<Object> discarded=new ArrayList<>();
        ModelWorkQueue q=new ModelWorkQueue(worker,owner::add,discarded::add);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();Reply spec=new Reply(),asr=new Reply();
        try {
            ModelPackStore.Token token=q.submit(true,t->{int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);entered.countDown();try{assertTrue(release.await(2,TimeUnit.SECONDS));return "future";}finally{active.decrementAndGet();}},spec);
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertFalse(q.foregroundBlocked());q.submit(false,t->{int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);try{return "ASR";}finally{active.decrementAndGet();}},asr);
            assertTrue(token.cancelled());assertTrue(q.busy());assertTrue(q.foregroundBlocked());assertEquals(1,active.get());assertNull(q.submit(true,t->"extra",new Reply()));
            release.countDown();Runnable first=owner.poll(2,TimeUnit.SECONDS);assertNotNull(first);assertTrue(q.busy());first.run();
            Runnable second=owner.poll(2,TimeUnit.SECONDS);assertNotNull(second);second.run();
            assertEquals(1,peak.get());assertFalse(q.busy());assertEquals(Arrays.asList("ASR"),asr.ok);assertTrue(spec.ok.isEmpty());assertEquals(Arrays.asList("future"),discarded);
        }finally{release.countDown();worker.shutdown();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test public void onlyOneForegroundMayWaitAndCancelledPendingNeverExecutes() {
        ArrayDeque<Runnable> work=new ArrayDeque<>(),owner=new ArrayDeque<>();List<Object> discarded=new ArrayList<>();ModelWorkQueue q=new ModelWorkQueue(work::add,owner::add,discarded::add);AtomicInteger calls=new AtomicInteger();Reply spec=new Reply(),first=new Reply(),second=new Reply();
        q.submit(true,t->"future",spec);ModelPackStore.Token pending=q.submit(false,t->{calls.incrementAndGet();return "first";},first);q.submit(false,t->{calls.incrementAndGet();return "second";},second);
        pending.cancel();while(!owner.isEmpty())owner.remove().run();assertEquals(1,second.errors.size());assertTrue(q.busy());
        work.remove().run();while(!owner.isEmpty())owner.remove().run();assertEquals(0,calls.get());assertFalse(q.busy());assertEquals(1,first.errors.size());assertTrue(work.isEmpty());
    }
    @Test public void exactInFlightAdoptionPromotesInsteadOfSubmittingDuplicateWork() {
        ArrayDeque<Runnable> work=new ArrayDeque<>(),owner=new ArrayDeque<>();ModelWorkQueue q=new ModelWorkQueue(work::add,owner::add,x->{});Reply current=new Reply(),other=new Reply();AtomicInteger calls=new AtomicInteger();
        ModelPackStore.Token token=q.submit(true,t->{calls.incrementAndGet();return "adopted";},current);assertTrue(q.promote(token));q.submit(false,t->"other",other);
        assertFalse(token.cancelled());assertEquals(1,work.size());work.remove().run();while(!owner.isEmpty())owner.remove().run();assertEquals(1,calls.get());assertEquals(Arrays.asList("adopted"),current.ok);assertEquals(1,other.errors.size());
    }
    @Test public void cancelledPendingCanBeReplacedWithoutASecondNativeInFlight() {
        ArrayDeque<Runnable> work=new ArrayDeque<>(),owner=new ArrayDeque<>();ModelWorkQueue q=new ModelWorkQueue(work::add,owner::add,x->{});Reply old=new Reply(),fresh=new Reply();
        q.submit(true,t->"future",new Reply());ModelPackStore.Token pending=q.submit(false,t->"old",old);pending.cancel();q.submit(false,t->"fresh",fresh);
        work.remove().run();while(!owner.isEmpty())owner.remove().run();assertEquals(1,work.size());work.remove().run();while(!owner.isEmpty())owner.remove().run();assertEquals(Arrays.asList("fresh"),fresh.ok);assertTrue(old.ok.isEmpty());assertEquals(1,old.errors.size());
    }
}

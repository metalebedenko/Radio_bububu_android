package ru.bububu.radio.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Headers;
import ru.bububu.radio.sources.*;

/** Worker-to-owner typed failure is preserved independently of persisted legacy error strings. */
public class SourceFailureProjectionTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final class Clock implements SourceClock {long mono=100;public long monotonicMillis(){return mono;}public long wallMillis(){return 1_700_000_000_000L;}}
    @Test public void delayedOwnerRetainsStatusDeadlineAndReopenKeepsGateWithoutRetry()throws Exception {
        Clock clock=new Clock();HostRequestGate gate=new HostRequestGate(null,clock);BlockingQueue<Runnable> owner=new LinkedBlockingQueue<>();int[] requests={0};
        NoteBook book=new NoteBook(new SourceBookTest.Store(temp.newFolder()));SourceRecord source=SourceRecord.create("rss","https://example.org/feed","Example","https://example.org/feed","generic",false);book.commitSource(source,Collections.emptyList());
        SourceTransport transport=new SourceTransport(){
            public SourceFailureInfo cooldown(String url){return gate.failure("example.org");}
            public Response fetch(String u,Kind k,Validators v,RequestScope scope)throws SourceFailure {
                requests[0]++;RetryAfterParser.Result retry=RetryAfterParser.parse(Collections.singletonList("60"),clock.wallMillis(),clock.monotonicMillis());gate.observe("example.org",retry);
                throw new HttpStatusFailure(503,Headers.of("Retry-After","60","Location","https://secret.example/private"),"secret.example",scope,retry);
            }
        };
        SourceCoordinator.Port port=new SourceCoordinator.Port(){
            public void dispatch(Runnable r){owner.add(r);}public void requireMutable(){}public void requireStorage(long n){}public void requireQueue(SourceRecord s,Article a){}public void appendStation(SourceRecord s){}public void append(NoteBook.Material m){}
            public PlayerController.SavedProgress persisted(NoteBook.Material m){throw new AssertionError();}public void pauseIfSelected(NoteBook.Material m){}public void remove(NoteBook.Material m){}public void projectRead(SourceRecord s,SourceItem i){}public void captureHistory(){}public void changed(){}
        };
        SourceBodyCache cache=new SourceBodyCache(temp.newFolder());
        try(SourceCoordinator coordinator=new SourceCoordinator(book,transport,cache,port,clock)){
            AtomicReference<SourceCoordinator.Result<SourceRecord>> result=new AtomicReference<>();coordinator.refresh(source.id,result::set);Runnable callback=owner.poll(3,TimeUnit.SECONDS);assertNotNull(callback);clock.mono+=1000;callback.run();
            assertNotNull(result.get());assertEquals("HTTP_503",result.get().error);assertEquals(503,result.get().failure.status);assertEquals(RetryAfterParser.Kind.DELAY,result.get().failure.retryAfterKind);assertEquals(59000,result.get().failure.remainingMillis(clock));
            String message=result.get().failure.message(clock);assertFalse(message.contains("secret"));assertFalse(message.contains("Location"));assertEquals("HTTP_503",book.source(source.id).lastError);
            SourceFailureInfo persistedProjection=coordinator.sourceFailure(book.source(source.id));assertEquals(503,persistedProjection.status);assertTrue(persistedProjection.retryBlocked(clock));coordinator.cancelUi();assertEquals(59000,coordinator.cooldown(source.url,false).remainingMillis(clock));assertEquals(1,requests[0]);
        }
        try(SourceCoordinator reopened=new SourceCoordinator(book,transport,cache,port,clock)){
            assertTrue(reopened.sourceFailure(book.source(source.id)).retryBlocked(clock));clock.mono+=59000;assertFalse(reopened.sourceFailure(book.source(source.id)).retryBlocked(clock));assertEquals("Time passing/reopening must not retry",1,requests[0]);
        }
    }
}

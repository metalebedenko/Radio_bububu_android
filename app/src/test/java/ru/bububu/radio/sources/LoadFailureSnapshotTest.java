package ru.bububu.radio.sources;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicReference;
public class LoadFailureSnapshotTest {
 @Test public void snapshotIsBoundedNoContentWithReceived200AndPartialBytes(){
  LoadFailureSnapshot d=new LoadFailureSnapshot(SourceClock.SYSTEM,7);d.observe(LoadFailureSnapshot.Event.READ,200,17);
  d.failure(new SourceFailure("TIMEOUT",new SocketTimeoutException("https://SECRET.invalid/query BODY HEADER CERT STACK")));
  String json=d.json(SourceFailureInfo.forReason("TIMEOUT"));assertTrue(json.contains("SOCKET_TIMEOUT"));assertTrue(json.contains("\"received_http_status\":200"));assertTrue(json.contains("\"wire_bytes_read\":17"));
  assertTrue(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=2048);for(String secret:new String[]{"https:","SECRET","BODY","HEADER","CERT","STACK","invalid"})assertFalse(json.contains(secret));
 }
 @Test public void operationResetDoesNotCarryFeedStatusOrBytesIntoLocalBody(){
  LoadFailureSnapshot d=new LoadFailureSnapshot(SourceClock.SYSTEM,1);d.observe(LoadFailureSnapshot.Event.READ,200,100);d.begin(LoadFailureSnapshot.Phase.BATCH_BODY);
  String json=d.json(SourceFailureInfo.forReason("TIMEOUT"));assertTrue(json.contains("BATCH_BODY"));assertTrue(json.contains("\"received_http_status\":0"));assertTrue(json.contains("\"wire_bytes_read\":0"));assertTrue(json.contains("\"network_event\":\"NONE\""));
 }
 @Test public void throwingMemoryObserverAndWriterCannotChangePrimaryFailure(){
  SourceFailureInfo primary=SourceFailureInfo.forReason("TIMEOUT");LoadFailureSnapshot d=new LoadFailureSnapshot(SourceClock.SYSTEM,1){@Override synchronized void observe(Event e,int s,long b){throw new AssertionError("SECRET");}@Override synchronized void failure(Throwable e){throw new IllegalStateException("SECRET");}};
  LoadFailureSnapshot.eventSafe(d,LoadFailureSnapshot.Event.READ,200,3);LoadFailureSnapshot.failureSafe(d,new RuntimeException("SECRET"));LoadFailureSnapshot.storeSafe(d,primary,s->{throw new IllegalStateException("SECRET");});LoadFailureSnapshot.clearSafe(s->{throw new IllegalStateException("SECRET");});assertEquals("DL1|TIMEOUT|0|TIMEOUT",primary.downloadError());
 }
}

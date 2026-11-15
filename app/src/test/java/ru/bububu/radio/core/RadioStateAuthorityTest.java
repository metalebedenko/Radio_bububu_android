package ru.bububu.radio.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import ru.bububu.radio.sources.*;

/** Actual shared authority, Player, Book and source bridge; wire faults model both write outcomes. */
public class RadioStateAuthorityTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final class Wire implements PlayerController.StateStore {
        PlayerController.SavedState disk;int fault;boolean unreadable;
        public PlayerController.SavedState load(){if(unreadable)throw new IllegalStateException("Inert read failed");return disk;}
        public void save(PlayerController.SavedState state){if(fault==2)throw new IllegalStateException("Before publication");disk=state;if(fault==1)throw new IllegalStateException("Published, readback failed");}
    }
    private final class Speech implements PlayerController.SpeechEngine {
        int requests,stops;String id;public void speak(String text,String id){requests++;this.id=id;}public void stop(){stops++;}
    }
    private final class Fixture implements AutoCloseable {
        final SourceBookTest.Store storage;final NoteBook book;final SourceRecord source;final SourceItem item;final SourceVersionRecord version;
        final Wire wire=new Wire();final RadioStateAuthority authority;final Speech speech=new Speech();final PlayerController player;
        final BlockingQueue<Runnable> owner=new LinkedBlockingQueue<>();final SourceCoordinator coordinator;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);boolean hold;
        final List<PlayerController.SavedState> emitted=new ArrayList<>();
        Fixture()throws Exception {
            storage=new SourceBookTest.Store(temp.newFolder());book=new NoteBook(storage);
            source=SourceRecord.create("rss","https://example.org/feed","Example","https://example.org/feed","generic-v1",false);
            item=SourceItem.create(source.id,"one",null,"https://example.org/one","Title",0,0,1);
            version=SourceBookTest.make(source,item,"First sentence. Second sentence. Third sentence.","full");
            book.commitSource(source,Collections.singletonList(item),Collections.singletonList(version));book.setSourceRead(source.id,item.itemKey,true);
            authority=new RadioStateAuthority(wire,book::reconcileState,book::encodeCursorProvenance);
            PlayerController.StateStore combined=new PlayerController.StateStore(){public PlayerController.SavedState load(){return authority.load();}public void save(PlayerController.SavedState s){authority.save(s);book.captureSourceHistory(s);}};
            player=new PlayerController(Arrays.asList(new Station("base","Base",Collections.singletonList(Article.fromSentences("a","v","Live","Other first.","Other second."))),new Station(source.stationId,source.name,Collections.emptyList())),speech,(d,r)->()->{},combined,s->emitted.add(wire.disk),1000);
            coordinator=new SourceCoordinator(book,(u,k,v,s)->{
                entered.countDown();if(hold)try{if(!release.await(3,TimeUnit.SECONDS))throw new SourceFailure("TEST_TIMEOUT");}catch(InterruptedException e){throw new SourceFailure("CANCELLED",e);}
                return new SourceTransport.Response("<rss version='2.0'><channel><title>Example</title><link>https://example.org</link><description>Feed</description></channel></rss>".getBytes(java.nio.charset.StandardCharsets.UTF_8),source.url,200,"application/rss+xml","UTF-8",null,null);
            },new SourceBodyCache(temp.newFolder()),new SourceCoordinator.Port(){
                public void dispatch(Runnable r){owner.add(r);}public void requireMutable(){authority.requireKnown();if(book.storageUncertain())throw new IllegalStateException("Book uncertain");}
                public long radioEpoch(){return authority.epoch();}public void requireStorage(long n){}public void requireQueue(SourceRecord s,Article a){}
                public void appendStation(SourceRecord s){player.appendStation(s.stationId,s.name);}public void append(NoteBook.Material m){player.appendMaterial(m.stationId,m.stationName,m.asArticle(),m.selectionInitialProgress);}
                public PlayerController.SavedProgress persisted(NoteBook.Material m){PlayerController.SavedState state=authority.persisted();if(state==null)throw new IllegalStateException("No JSON");PlayerController.SavedProgress p=state.progress.get(PlayerController.progressKey(m.stationId,m.articleId,m.version));if(p==null)throw new IllegalStateException("No exact version");return p;}
                public void pauseIfSelected(NoteBook.Material m){if(player.snapshot().article!=null&&m.stationId.equals(player.snapshot().station.id)&&m.articleId.equals(player.snapshot().article.id)){player.pause();assertTrue(player.isOutputStopped());}}
                public void remove(NoteBook.Material m){player.removeMaterial(m.stationId,m.articleId,m.boundaryMarker());}public void projectRead(SourceRecord s,SourceItem i){player.setRead(s.stationId,i.articleId,version.versionKey,i.read);}
                public void captureHistory(){book.captureSourceHistory(authority.persisted());}public void changed(){}
            });
            coordinator.enqueue(version,false);
        }
        void partial(){player.selectArticle(source.stationId,version.articleId);player.seekToFraction(.6);player.play();}
        PlayerController.SavedProgress disk(){return wire.disk.progress.get(PlayerController.progressKey(source.stationId,version.articleId,version.versionKey));}
        void preserved(){assertEquals(1,book.materials().size());assertEquals(version.spokenText,book.sourceVersion(version.recordKey).spokenText);assertNotNull(book.sourceItem(source.id,item.itemKey));assertFalse(book.storageUncertain());}
        public void close(){release.countDown();coordinator.close();}
    }
    @Test public void publishedUnacknowledgedStartedStateRefusesRemovalAndCleanup()throws Exception {
        try(Fixture f=new Fixture()){
            assertFalse(f.disk().started);f.wire.fault=1;f.partial();assertTrue(f.disk().started);assertTrue(f.disk().sentenceIndex>0);assertNotNull(f.player.snapshot().error);assertFalse(f.authority.known());
            f.wire.fault=2;assertThrows(IllegalStateException.class,()->f.coordinator.remove(f.source.id,f.version.articleId));assertThrows(IllegalStateException.class,()->f.coordinator.cleanup(f.source.id));f.preserved();
        }
    }
    @Test public void prepublicationFailureAlsoRevokesOldEvidenceWithoutChangingDisk()throws Exception {
        try(Fixture f=new Fixture()){PlayerController.SavedState prior=f.wire.disk;f.wire.fault=2;f.partial();assertSame(prior,f.wire.disk);assertFalse(f.authority.known());assertThrows(IllegalStateException.class,()->f.coordinator.remove(f.source.id,f.version.articleId));f.preserved();}
    }
    @Test public void pauseFailureRevokesAuthorityBetweenInitialGuardAndRemoval()throws Exception {
        try(Fixture f=new Fixture()){f.partial();assertTrue(f.authority.known());PlayerController.SavedState prior=f.wire.disk;f.wire.fault=2;assertThrows(IllegalStateException.class,()->f.coordinator.remove(f.source.id,f.version.articleId));assertFalse(f.player.snapshot().playing);assertFalse(f.authority.known());assertSame(prior,f.wire.disk);f.preserved();}
    }
    @Test public void pausePublicationBeforeExceptionCannotUnpinPayload()throws Exception {
        try(Fixture f=new Fixture()){f.partial();f.wire.fault=1;assertThrows(IllegalStateException.class,()->f.coordinator.remove(f.source.id,f.version.articleId));assertTrue(f.disk().started);assertFalse(f.authority.known());f.preserved();}
    }
    @Test public void verifiedCheckpointReenablesRemovalAndReaddSeedsFirstSaveAndEmit()throws Exception {
        try(Fixture f=new Fixture()){
            f.wire.fault=1;f.partial();PlayerController.SavedProgress actual=f.disk();f.wire.fault=0;f.player.pause();assertTrue(f.authority.known());
            f.coordinator.remove(f.source.id,f.version.articleId);assertTrue(f.book.materials().isEmpty());assertEquals(f.version.spokenText,f.book.sourceVersion(f.version.recordKey).spokenText);assertEquals(0,f.coordinator.cleanup(f.source.id));
            f.coordinator.enqueue(f.version,false);NoteBook.Material m=f.book.materials().get(0);assertEquals(actual.sentenceIndex,m.selectionInitialProgress.sentenceIndex);assertTrue(m.selectionInitialProgress.started);
            assertEquals(actual.sentenceIndex,f.disk().sentenceIndex);assertTrue(f.disk().started);assertSame(f.wire.disk,f.emitted.get(f.emitted.size()-1));
        }
    }
    @Test public void restartReconcilesActualPublishedBytesRatherThanOldSuccess()throws Exception {
        try(Fixture f=new Fixture()){
            f.wire.fault=1;f.partial();PlayerController.SavedProgress actual=f.disk();NoteBook restored=new NoteBook(f.storage);
            RadioStateAuthority restarted=new RadioStateAuthority(f.wire,restored::reconcileState,restored::encodeCursorProvenance);PlayerController.SavedState loaded=restarted.load();
            assertTrue(restarted.known());assertTrue(restarted.persisted().progress.get(PlayerController.progressKey(f.source.stationId,f.version.articleId,f.version.versionKey)).started);assertEquals(actual.sentenceIndex,loaded.progress.get(PlayerController.progressKey(f.source.stationId,f.version.articleId,f.version.versionKey)).sentenceIndex);
            restored.captureSourceHistory(restarted.persisted());restored.removeSourceMaterial(f.source.stationId,f.version.articleId,actual);assertEquals(f.version.spokenText,restored.sourceVersion(f.version.recordKey).spokenText);assertEquals(0,restored.hideCompletedSourceItems(f.source.id));
        }
    }
    @Test public void reconciledSelectionSeedIsNeverMistakenForPersistedJson()throws Exception {
        try(Fixture f=new Fixture()){f.wire.disk=null;RadioStateAuthority fresh=new RadioStateAuthority(f.wire,f.book::reconcileState,f.book::encodeCursorProvenance);assertNotNull(fresh.load());assertNull(fresh.persisted());assertTrue(fresh.known());}
    }
    @Test public void failedRestartLoadCannotRestoreAuthorityFromLiveState()throws Exception {
        try(Fixture f=new Fixture()){f.wire.unreadable=true;assertThrows(IllegalStateException.class,f.authority::load);assertFalse(f.authority.known());assertThrows(IllegalStateException.class,f.authority::persisted);f.preserved();}
    }
    @Test public void lateWorkerCannotPublishAcrossRadioFailureAndRecoveryEpoch()throws Exception {
        try(Fixture f=new Fixture()){f.hold=true;AtomicReference<SourceCoordinator.Result<SourceRecord>> result=new AtomicReference<>();f.coordinator.refresh(f.source.id,result::set);assertTrue(f.entered.await(2,TimeUnit.SECONDS));long epoch=f.authority.epoch();
            f.wire.fault=1;f.partial();f.wire.fault=0;f.player.pause();assertTrue(f.authority.known());assertTrue(f.authority.epoch()>epoch);f.release.countDown();Runnable task=f.owner.poll(2,TimeUnit.SECONDS);assertNotNull(task);task.run();assertNull(result.get());f.preserved();
        }
    }
    @Test public void unrelatedLiveTtsSurvivesSuccessfulSourceHistoryAppendAndRemoval()throws Exception {
        try(Fixture f=new Fixture()){f.partial();f.player.pause();f.player.selectStation(0);f.player.play();String id=f.player.snapshot().currentRequestId;int stops=f.speech.stops,requests=f.speech.requests;
            f.coordinator.cleanup(f.source.id);f.coordinator.remove(f.source.id,f.version.articleId);f.coordinator.enqueue(f.version,false);
            assertTrue(f.player.snapshot().playing);assertEquals(id,f.player.snapshot().currentRequestId);assertEquals(stops,f.speech.stops);assertEquals(requests,f.speech.requests);assertTrue(f.disk().started);assertEquals(0,f.coordinator.cleanup(f.source.id));
        }
    }
}

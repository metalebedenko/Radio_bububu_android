package ru.bububu.radio.sources;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.file.*;
import ru.bububu.radio.core.*;

/** Synthetic public-shape responses, real Book/CAS/Coordinator. No network or owner bodies. */
public class BasicIsoOffsetRefresh45Test {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void explicitBatchReplacesOldInvalidSnapshotWithoutRewritingOldCas()throws Exception{run(false);}
    @Test public void protectedHistoricalFactsAndLinkedNoteRemainExact()throws Exception{run(true);}
    private static void pump(BlockingQueue<Runnable> owner,java.util.function.BooleanSupplier done)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(!done.getAsBoolean()){assertTrue("Bounded coordinator completion",System.nanoTime()<deadline);Runnable r=owner.poll(100,TimeUnit.MILLISECONDS);if(r!=null)r.run();}
    }
    private void run(boolean linked)throws Exception{
        SourceProfiles45.Profile profile=SourceProfiles45.byId("aif-economy-briefs");SourceRecord source=profile.seed();String url="https://aif.ru/money/economy/fixture",raw="2026-10-26T12:37:30+0300";
        String index=Source45ReaderGuardsTest.page(profile.endpoint,"<a href='"+url+"'>Заголовок</a>");
        String html=Source45ReaderGuardsTest.page(url,"<meta property='article:published_time' content='"+raw+"'><meta property='article:modified_time' content='2026-10-26T15:37:30+03:00'><div class='article_text'><p>Проверочный полный текст новости.</p></div>");
        FeedItem feed=SourceReaders45.discover(profile,Source45ReaderGuardsTest.html(profile.endpoint,index),new RequestScope(1,1)).items.get(0);
        ExtractionResult extracted=SourceReaders45.extract(profile,Source45ReaderGuardsTest.html(url,html),feed,new RequestScope(1,1));
        SourcePublication45 corrected=extracted.publication;
        SourcePublication45 legacy=new SourcePublication45(SourcePublication45.unknown(raw,"meta",url,"article:published_time"),corrected.facts,corrected.profileConfigSha,corrected.ruleRevision,"INVALID",corrected.responseSha,corrected.canonicalResponseSha,corrected.linkProvenanceDigest,corrected.subjectUrl,corrected.subjectId);
        SourceItem item=SourceItem.create(source.id,"fixture",null,url,"Заголовок",-1,-1,1);
        SourceVersionRecord old=SourceVersionRecord.source45(source,item,extracted.withPublication(legacy),legacy,SourcePublication45.Context.INVALID_MANUAL,1);
        List<SourceRecord> seeds=new ArrayList<>();Map<String,String> bindings=new TreeMap<>();for(SourceProfiles45.Profile p:SourceProfiles45.six()){seeds.add(p.seed());bindings.put(p.id,p.seed().id);}
        byte[] target=NoteBook.fresh45Target(seeds,new Seed45Marker("01234567-89ab-4cde-8fab-0123456789ab",Seed45Marker.POLICY,SourceProfiles45.definitionSetHash(),bindings));
        final class Storage implements NoteBook.Storage{byte[] bytes=target;public byte[] load(){return bytes.clone();}public void save(byte[] b){bytes=b.clone();}}
        Storage storage=new Storage();NoteBook book=new NoteBook(storage);book.enable45();book.commitSource(source,List.of());book.mergeSourceItems(source.id,List.of(item),List.of(old),null,null,1,null);
        SourceBodyCache cache=new SourceBodyCache(temp.newFolder());cache.put(old);byte[] oldFacts=legacy.encode();
        String noteId=null;
        if(linked){
            // Construct a historical protected ledger fixture through Book only; this is not a UI admission claim.
            book.selectSourceVersion(old,false);NoteBook.Note note=book.beginRecordingLinkedSource("Связанная заметка",old.recordKey,2);noteId=note.id;book.removeSourceMaterial(source.stationId,item.articleId,null);
        }
        BlockingQueue<Runnable> owner=new LinkedBlockingQueue<>();AtomicReference<PlayerController.SavedState> cursor=new AtomicReference<>();
        PlayerController player=new PlayerController(OrdinaryRadio.catalog(book),new PlayerController.SpeechEngine(){public void speak(String text,String id){}public void stop(){}},(ms,action)->()->{},new PlayerController.StateStore(){public PlayerController.SavedState load(){return null;}public void save(PlayerController.SavedState state){cursor.set(state);}},state->{},256);cursor.set(player.savedState());
        SourceClock clock=BasicIsoOffset45Test.CLOCK;
        SourceCoordinator.Port port=new SourceCoordinator.Port(){
            public void dispatch(Runnable r){owner.add(r);}public void requireMutable(){}public void requireStorage(long bytes){}public void requireQueue(SourceRecord source,Article article){}
            public void appendStation(SourceRecord s){player.appendStation(s.stationId,s.name);}public void append(NoteBook.Material m){player.appendMaterial(m.stationId,m.stationName,m.asArticle(),m.selectionInitialProgress);}
            public PlayerController.SavedProgress persisted(NoteBook.Material m){return cursor.get().progress.get(PlayerController.progressKey(m.stationId,m.articleId,m.version));}
            public void pauseIfSelected(NoteBook.Material m){}public void remove(NoteBook.Material m){}public void projectRead(SourceRecord s,SourceItem i){}public void captureHistory(){book.captureSourceHistory(cursor.get());}public void changed(){}
            public String currentIdentity(){PlayerController.Snapshot s=player.snapshot();return s.article==null?null:PlayerController.progressKey(s.station.id,s.article.id,s.article.version);}
            public PlayerController.SavedState persistedState(){return cursor.get();}public void checkpointMutation(List<NoteBook.Material> removed,boolean clear){}public void projectDeletion(){player.projectCommittedDeletion(OrdinaryRadio.catalog(book),book.reconcileState(cursor.get()));}
        };
        AtomicInteger bodies=new AtomicInteger();SourceTransport transport=(request,kind,validators,scope)->{
            if(request.equals(profile.endpoint))return Source45ReaderGuardsTest.html(profile.endpoint,index);
            assertEquals(url,request);bodies.incrementAndGet();return Source45ReaderGuardsTest.html(url,html);
        };
        AtomicReference<SourceCoordinator.DownloadProgress> progress=new AtomicReference<>();
        try(SourceCoordinator coordinator=new SourceCoordinator(book,transport,cache,port,clock)){
            assertTrue(SourcePublication45.needsDateRefresh(old));AtomicReference<SourceCoordinator.Result<SourceVersionRecord>> read=new AtomicReference<>();
            coordinator.loadText(source.id,item.itemKey,read::set);pump(owner,()->read.get()!=null);assertTrue(read.get().successful());assertEquals(old.recordKey,read.get().value.recordKey);assertEquals(0,bodies.get());
            coordinator.downloadBatch(List.of(source.id),3,progress::set);pump(owner,()->progress.get()!=null&&progress.get().finished);
            assertEquals(1,bodies.get());assertEquals(progress.get().message,1,progress.get().saved);
            SourceVersionRecord current=book.sourceVersion(book.sourceItem(source.id,item.itemKey).latestVersionKey);
            assertNotEquals(old.recordKey,current.recordKey);assertEquals(SourcePublication45.Precision.INSTANT,current.publication.primary.precision);assertEquals(raw,current.publication.primary.raw);assertEquals("meta",current.publication.primary.authority);assertEquals("article:published_time",current.publication.primary.selector);assertEquals(SourcePublication45.Context.DATED,current.contextClass);assertFalse(SourcePublication45.needsDateRefresh(current));new SourceAdmission45(clock).require(current,false);
            assertEquals(old.bodyHash,current.bodyHash);assertEquals(old.body(),cache.get(current.withPayload(null)).body());
            SourceVersionRecord preserved=cache.get(old.withPayload(null));assertNotNull(preserved);assertEquals(old.spokenText,preserved.spokenText);assertArrayEquals(oldFacts,preserved.publication.encode());
            if(linked){assertArrayEquals(oldFacts,book.sourceVersion(old.recordKey).publication.encode());assertEquals(noteId,book.notes().get(0).id);assertEquals(old.recordKey,book.notes().get(0).linkedSourceVersionKey);}
            else assertNull("Ordinary pruning of obsolete unprotected metadata remains",book.sourceVersion(old.recordKey));
            progress.set(null);coordinator.downloadBatch(List.of(source.id),3,progress::set);pump(owner,()->progress.get()!=null&&progress.get().finished);assertEquals("Corrected snapshot uses normal cache",1,bodies.get());
        }
        NoteBook reopened=new NoteBook(storage);reopened.enable45();assertEquals(SourcePublication45.Precision.INSTANT,reopened.sourceVersion(reopened.sourceItem(source.id,item.itemKey).latestVersionKey).publication.primary.precision);
        if(linked)assertArrayEquals(oldFacts,reopened.sourceVersion(old.recordKey).publication.encode());
        byte[] valid=storage.load(),publication=reopened.sourceVersion(reopened.sourceItem(source.id,item.itemKey).latestVersionKey).publication.encode();int publicationAt=-1;
        outer:for(int i=9;i<=valid.length-publication.length;i++){for(int k=0;k<publication.length;k++)if(valid[i+k]!=publication[k])continue outer;publicationAt=i;break;}
        assertTrue(publicationAt>9);int extensionAt=publicationAt-9;
        for(int badLength:new int[]{-1,16385,valid.length-extensionAt}){
            byte[] corrupt=valid.clone();java.nio.ByteBuffer.wrap(corrupt).putInt(extensionAt,badLength);java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(corrupt,0,corrupt.length-8);java.nio.ByteBuffer.wrap(corrupt).putLong(corrupt.length-8,crc.getValue());
            assertThrows(IllegalStateException.class,()->new NoteBook(new NoteBook.Storage(){public byte[] load(){return corrupt;}public void save(byte[] ignored){fail("Decode must not mutate storage");}}));
        }
    }
}

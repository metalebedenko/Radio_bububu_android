package ru.bububu.radio.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.util.*;
import ru.bububu.radio.sources.*;
import static org.junit.Assert.*;

/** Actual Book, persisted radio authority and final speech admission across the new mutations. */
public final class PlayerBulkMutationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    @Test public void currentSavedNoteReplacementStopsOldRequestAndSelectsNewIdentityAtBeginning() throws Exception {
        Bridge b=new Bridge(temporary.newFolder());b.player.selectArticle(NoteBook.MY_TEXTS,b.firstNoteMaterial.articleId);b.player.seekToFraction(.5);b.player.play();
        String old=b.player.snapshot().currentRequestId;b.player.onStart(old);Task deadline=b.clock.latest();int requests=b.speech.requests.size();
        b.book.edit(b.firstNote.id,"Своя заметка","Новая ручная первая фраза. Новая вторая фраза. Новая третья фраза.");
        NoteBook.DeletionChange dry=b.book.prepareReplaceNoteInAir(b.firstNote.id,b.book.find(b.firstNote.id).revision,b.authority.persisted());assertTrue(dry.affectsCurrent);
        b.speech.onStop=()->{b.player.onDone(old);b.player.onError(old,"late stop event");};
        b.player.pause();b.player.checkpoint();NoteBook.DeletionChange change=b.book.prepareReplaceNoteInAir(b.firstNote.id,b.book.find(b.firstNote.id).revision,b.authority.persisted());assertTrue(b.book.applyDeletion(change));b.project();
        b.player.selectArticle(change.replacement.stationId,change.replacement.articleId);
        assertEquals(change.replacement.articleId,b.player.snapshot().article.id);assertEquals(change.replacement.version,b.player.snapshot().article.version);assertNotEquals(b.firstNoteMaterial.articleId,b.player.snapshot().article.id);assertEquals(0,b.player.snapshot().sentenceIndex);assertFalse(b.player.snapshot().playing);assertNull(b.player.snapshot().currentRequestId);assertTrue(b.player.isOutputStopped());
        b.player.onStart(old);b.player.onDone(old);b.player.onError(old,"late");deadline.action.run();assertEquals(requests,b.speech.requests.size());assertTrue(b.player.snapshot().completedSentences.isEmpty());
        assertEquals(234,b.book.find(b.firstNote.id).positionMs);assertTrue(b.book.find(b.firstNote.id).hasAudio);assertEquals(b.secondNoteMaterial.articleId,b.book.materials().get(1).articleId);
        assertEquals(change.replacement.articleId,b.book.materials().get(2).articleId);
        int saves=b.store.saves;assertFalse(b.book.applyDeletion(b.book.prepareReplaceNoteInAir(b.firstNote.id,b.book.find(b.firstNote.id).revision,b.authority.persisted())));assertEquals(saves,b.store.saves);
    }

    @Test public void replacingAnotherSavedNotePreservesCurrentNewsRequestVersionAndAnchor() throws Exception {
        Bridge b=new Bridge(temporary.newFolder());b.player.seekToFraction(.5);b.player.play();b.player.onStart(b.player.snapshot().currentRequestId);
        String request=b.player.snapshot().currentRequestId;Task deadline=b.clock.latest();int stops=b.speech.stops,requests=b.speech.requests.size();PlayerController.SavedProgress before=b.saved(b.newsMaterial);
        b.book.edit(b.secondNote.id,"Другая заметка","Более свежая другая редакция. Вторая фраза.");
        NoteBook.DeletionChange change=b.book.prepareReplaceNoteInAir(b.secondNote.id,b.book.find(b.secondNote.id).revision,b.authority.persisted());assertFalse(change.affectsCurrent);assertFalse(change.affects(b.newsMaterial.stationId,b.newsMaterial.articleId));assertTrue(b.book.applyDeletion(change));b.project();
        assertTrue(b.player.snapshot().playing);assertEquals(request,b.player.snapshot().currentRequestId);assertEquals(b.newsMaterial.articleId,b.player.snapshot().article.id);assertEquals(b.newsMaterial.version,b.player.snapshot().article.version);assertEquals(before.sentenceIndex,b.player.snapshot().sentenceIndex);assertEquals(before.completedSentences,b.player.snapshot().completedSentences);
        assertEquals(stops,b.speech.stops);assertEquals(requests,b.speech.requests.size());assertSame(deadline,b.clock.latest());assertFalse(deadline.cancelled);assertEquals(change.replacement.articleId,b.book.materials().get(2).articleId);
        b.player.onDone(request);assertEquals(requests+1,b.speech.requests.size());assertEquals(b.newsMaterial.articleId,b.player.snapshot().article.id);assertEquals(1,b.book.find(b.secondNote.id).positionMs);
    }

    @Test public void committedBulkTargetsAndUnknownOutcomesBlockRemovedSpeechDespiteFailedProjection() throws Exception {
        for(boolean clear:new boolean[]{false,true})for(boolean unknown:new boolean[]{false,true}) {
            Bridge b=new Bridge(temporary.newFolder());b.player.play();String request=b.player.snapshot().currentRequestId;b.player.onStart(request);Task deadline=b.clock.latest();int spoken=b.speech.requests.size();
            b.player.pause();b.player.checkpoint();NoteBook.DeletionChange change=clear?b.book.prepareClearAir(b.authority.persisted()):b.book.prepareDeleteAllNews(b.authority.persisted());
            if(unknown){b.store.unknown=true;rejects(()->b.book.applyDeletion(change));assertTrue(b.book.storageUncertain());}
            else {assertTrue(b.book.applyDeletion(change));List<Station> malformed=b.catalog();malformed.add(malformed.get(0));rejects(()->b.player.projectCommittedDeletion(malformed,b.book.reconcileState(b.authority.persisted())));assertTrue("Old selected news deliberately remains in the stale runtime",b.player.stations().get(0).articles.stream().anyMatch(a->a.id.equals(b.newsMaterial.articleId)));}
            b.player.onDone(request);b.player.onStart(request);b.player.onError(request,"late");deadline.action.run();b.player.play();b.player.nextArticle();b.player.seekToFraction(0);b.player.play();
            assertEquals("No removed or unverified version may submit speech",spoken,b.speech.requests.size());assertFalse(b.player.snapshot().playing);assertNull(b.player.snapshot().currentRequestId);
            if(unknown){b.store.unknown=false;b.store.unreadable=false;assertTrue(new NoteBook(b.store).materials().stream().noneMatch(m->m.articleId.equals(b.newsMaterial.articleId)));assertTrue(b.book.storageUncertain());}
            else {b.project();if(clear)b.player.requireExplicitSelection();assertFalse(b.player.snapshot().playing);assertEquals(spoken,b.speech.requests.size());assertFalse(b.book.applyDeletion(clear?b.book.prepareClearAir(b.authority.persisted()):b.book.prepareDeleteAllNews(b.authority.persisted())));}
        }
    }

    @Test public void ownNoteDeletionStopsCurrentAndRejectsLateSpeechWhileOtherNewsKeepsItsRequest() throws Exception {
        for(boolean current:new boolean[]{true,false}){
            Bridge b=new Bridge(temporary.newFolder());if(current)b.player.selectArticle(NoteBook.MY_TEXTS,b.firstNoteMaterial.articleId);
            b.player.play();String request=b.player.snapshot().currentRequestId;b.player.onStart(request);Task timer=b.clock.latest();int stops=b.speech.stops,requests=b.speech.requests.size();
            NoteBook.DeletionChange dry=b.book.prepareDeleteNote(b.firstNote.id,b.book.find(b.firstNote.id).revision,b.authority.persisted());assertEquals(current,dry.affectsCurrent);
            if(current){b.player.pause();b.player.checkpoint();}
            NoteBook.DeletionChange change=b.book.prepareDeleteNote(b.firstNote.id,b.book.find(b.firstNote.id).revision,b.authority.persisted());assertTrue(b.book.applyDeletion(change));b.project();
            assertNull(b.book.find(b.firstNote.id));assertNull(new NoteBook(b.store).find(b.firstNote.id));assertEquals(1,b.book.find(b.secondNote.id).positionMs);
            assertEquals(NoteBook.Membership.NOT_MEMBER,b.book.queueMembership(b.firstNoteMaterial.stationId,b.firstNoteMaterial.asArticle(),b.firstNoteMaterial.sequence,null));
            if(current){assertFalse(b.player.snapshot().playing);assertTrue(b.player.isOutputStopped());assertNull(b.player.snapshot().currentRequestId);b.player.onStart(request);b.player.onDone(request);b.player.onError(request,"late removed Note");timer.action.run();assertEquals(requests,b.speech.requests.size());}
            else{assertTrue(b.player.snapshot().playing);assertEquals(request,b.player.snapshot().currentRequestId);assertEquals(stops,b.speech.stops);assertEquals(requests,b.speech.requests.size());assertFalse(timer.cancelled);b.player.onDone(request);assertEquals(requests+1,b.speech.requests.size());assertEquals(b.newsMaterial.articleId,b.player.snapshot().article.id);}
        }
    }
    private static void rejects(Runnable action){try{action.run();fail("Expected refusal");}catch(IllegalArgumentException|IllegalStateException expected){}}
    private static final class Speech implements PlayerController.SpeechEngine {
        final List<String> requests=new ArrayList<>();int stops;Runnable onStop;
        public void speak(String text,String id){requests.add(text);}
        public void stop(){stops++;if(onStop!=null)onStop.run();}
    }
    private static final class Task implements PlayerController.Cancel {final Runnable action;boolean cancelled;Task(Runnable action){this.action=action;}public void cancel(){cancelled=true;}}
    private static final class Clock implements PlayerController.Scheduler {final List<Task> tasks=new ArrayList<>();public PlayerController.Cancel schedule(long delay,Runnable action){Task task=new Task(action);tasks.add(task);return task;}Task latest(){return tasks.get(tasks.size()-1);}}
    private static final class Radio implements PlayerController.StateStore {PlayerController.SavedState state;public PlayerController.SavedState load(){return state;}public void save(PlayerController.SavedState value){state=value;}}
    private static final class Bridge {
        final DeletionBookTest.Store store;final NoteBook book;final RadioStateAuthority authority;final PlayerController player;
        final Speech speech=new Speech();final Clock clock=new Clock();final SourceRecord source;final NoteBook.Note firstNote,secondNote;final NoteBook.Material newsMaterial,firstNoteMaterial,secondNoteMaterial;
        Bridge(File directory) {
            store=new DeletionBookTest.Store(directory);book=new NoteBook(store);source=SourceRecord.create("RSS","player-bulk","Новости","https://example.test/bulk","rss1",true);SourceItem item=SourceItem.create(source.id,"news",null,null,"Новость",0,0,1);book.commitSource(source,Collections.singletonList(item));SourceVersionRecord version=SourceBookTest.make(source,item,"Первая новостная фраза. Вторая новостная фраза. Третья новостная фраза.","full");book.saveSourceVersion(version);newsMaterial=book.selectSourceVersion(version,false);
            firstNote=own("Своя заметка","Первая ручная фраза. Вторая ручная фраза. Третья ручная фраза.",234);firstNoteMaterial=book.addToAir(firstNote.id);
            secondNote=own("Другая заметка","Другая ручная первая фраза. Другая вторая фраза.",1);secondNoteMaterial=book.addToAir(secondNote.id);
            authority=new RadioStateAuthority(new Radio(),book::reconcileState,book::encodeCursorProvenance);
            PlaybackMembership gate=new PlaybackMembership(){public Binding capture(String station,Article article){NoteBook.Material material=book.material(station,article.id);return material==null?null:new Binding(material.sequence,material.sourceVersionKey,"book");}public Admission check(String station,Article article,Binding binding){if(binding==null||book.storageUncertain()||!authority.known())return Admission.UNVERIFIED;return Admission.valueOf(book.queueMembership(station,article,binding.sequence,binding.sourceVersionKey).name());}public boolean stationRetired(String station){return book.retiredStation(station);}};
            PlayerController.StateStore state=new PlayerController.StateStore(){public PlayerController.SavedState load(){return authority.load();}public void save(PlayerController.SavedState value){authority.save(value);book.captureSourceHistory(value);}};
            player=new PlayerController(catalog(),speech,clock,state,snapshot->{},1000,gate);player.checkpoint();
        }
        NoteBook.Note own(String title,String text,long position){NoteBook.Note note=book.beginRecording(title,null,null,null,1);book.finishRecording(note.id,1000,true,false,null);book.setOriginalPosition(note.id,position);return book.edit(note.id,title,text);}
        List<Station> catalog(){List<Station> result=new ArrayList<>();for(Station station:book.stationRegistry()){List<Article> articles=new ArrayList<>();for(NoteBook.Material material:book.materials())if(material.stationId.equals(station.id))articles.add(material.asArticle());result.add(new Station(station.id,station.name,articles));}return result;}
        void project(){player.projectCommittedDeletion(catalog(),book.reconcileState(authority.persisted()));}
        PlayerController.SavedProgress saved(NoteBook.Material material){return player.savedState().progress.get(PlayerController.progressKey(material.stationId,material.articleId,material.version));}
    }
}

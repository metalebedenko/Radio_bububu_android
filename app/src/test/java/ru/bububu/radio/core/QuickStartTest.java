package ru.bububu.radio.core;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import ru.bububu.radio.sources.ChannelProfile.Kind;
public final class QuickStartTest {
    private QuickStart.Candidate item(Kind kind,boolean full,String source){return new QuickStart.Candidate("station","article","version",source,"channel",kind,full,false);}
    private QuickStart.Ticket begin(QuickStart q,String run,String explicit){return q.begin(run,"selection","voice",Arrays.asList("one","two"),explicit,null);}
    @Test public void reportingWaitOrChangedChoiceNeverStartsPlayback(){
        QuickStart q=new QuickStart(); QuickStart.Ticket t=begin(q,"run",null);
        assertEquals(QuickStart.Readiness.WAIT_VOICE,t.readiness("selection","voice"));
        q.ready(t,"voice");
        assertEquals(QuickStart.Readiness.WAIT_ARTICLE,t.readiness("selection","voice"));
        assertFalse(q.awaitingAudio());
        assertTrue(q.published("run",item(Kind.NEWS_BRIEF,true,"one")));
        assertEquals(QuickStart.Readiness.READY,t.readiness("selection","voice"));
        assertEquals(QuickStart.Readiness.VOICE_CHANGED,t.readiness("selection","other"));
        assertEquals(QuickStart.Readiness.SELECTION_CHANGED,t.readiness("other","voice"));
        assertFalse(q.awaitingAudio());
        assertNull(q.take(t,"other","voice",x->{throw new AssertionError("stale request reached admission");}));
        assertFalse(q.pending());
    }
    @Test public void fullAndReadyInEitherOrderProduceOneStart(){for(boolean readyFirst:new boolean[]{true,false}){QuickStart q=new QuickStart();QuickStart.Ticket t=begin(q,"run",null);if(readyFirst)q.ready(t,"voice");assertTrue(q.published("run",item(Kind.ARTICLE_ANALYSIS,true,"one")));if(!readyFirst){assertNull(q.take(t,"selection","voice",x->true));q.ready(t,"voice");}assertNotNull(q.take(t,"selection","voice",x->true));assertNull(q.take(t,"selection","voice",x->true));}}
    @Test public void defaultPoolRefusesUnknownLimitedAndForeignRun(){QuickStart q=new QuickStart();QuickStart.Ticket t=begin(q,"run",null);q.ready(t,"voice");for(Kind kind:new Kind[]{Kind.UNKNOWN})assertFalse(q.published("run",item(kind,true,"one")));assertFalse(q.published("run",item(Kind.ARTICLE_ANALYSIS,false,"one")));assertFalse(q.published("old",item(Kind.ARTICLE_ANALYSIS,true,"one")));assertFalse(q.published("run",item(Kind.ARTICLE_ANALYSIS,true,"foreign")));assertNull(q.take(t,"selection","voice",x->true));}
    @Test public void explicitStationPermitsLabelledFullButNoOtherSource(){QuickStart q=new QuickStart();QuickStart.Ticket t=begin(q,"run","two");assertFalse(q.published("run",item(Kind.NEWS_BRIEF,true,"one")));assertTrue(q.published("run",item(Kind.NEWS_BRIEF,true,"two")));q.ready(t,"voice");assertEquals("two",q.take(t,"selection","voice",x->true).source);}
    @Test public void terminalAndCancelCannotResurrectEvenSavedCurrentWaitingForVoice(){for(boolean terminal:new boolean[]{false,true}){QuickStart q=new QuickStart();QuickStart.Candidate current=new QuickStart.Candidate("s","a","v","one","c",Kind.UNKNOWN,true,true);QuickStart.Ticket t=q.begin("run","selection","voice",Arrays.asList("one"),null,current);if(terminal)q.terminal("run");else q.revoke();q.ready(t,"voice");assertFalse(q.published("run",item(Kind.ARTICLE_ANALYSIS,true,"one")));assertNull(q.take(t,"selection","voice",x->true));assertFalse(q.pending());}}
    @Test public void stampsFinalAdmissionAndSupersededTicketsAreTerminal(){for(int mode=0;mode<4;mode++){final int choice=mode;QuickStart q=new QuickStart();QuickStart.Ticket t=begin(q,"run",null);q.published("run",item(Kind.ARTICLE_ANALYSIS,true,"one"));q.ready(t,"voice");if(mode==3)begin(q,"new",null);assertNull(q.take(t,mode==0?"changed":"selection",mode==1?"changed":"voice",x->choice!=2));if(mode<3)assertFalse(q.pending());else assertTrue(q.pending());} }
    @Test public void transferredStartRemainsCancelableUntilActualAudioAndTerminalOwnsOnlyItsRun(){
        QuickStart q=new QuickStart();QuickStart.Ticket t=begin(q,"run",null);q.published("run",item(Kind.ARTICLE_ANALYSIS,true,"one"));q.ready(t,"voice");assertNotNull(q.take(t,"selection","voice",x->true));assertTrue(q.pending());assertTrue(q.awaitingAudio());assertTrue(q.owns("run"));q.terminal("old");assertTrue(q.awaitingAudio());q.terminal("run");assertFalse(q.pending());assertFalse(q.awaitingAudio());assertNull(q.take(t,"selection","voice",x->true));
        t=begin(q,"new",null);q.published("new",item(Kind.ARTICLE_ANALYSIS,true,"one"));q.ready(t,"voice");q.take(t,"selection","voice",x->true);q.actualStarted();assertFalse(q.pending());assertFalse(q.owns("new"));q.terminal("new");assertFalse(q.pending());
    }

    @Test public void cachedClaimSurvivesEmptyOrSixFailedRefreshWhileSpeechIsDelayed(){
        for(int failedSources:new int[]{0,6}){
            DelayedPlayback h=new DelayedPlayback();QuickStart q=new QuickStart();QuickStart.Ticket t=q.begin("cached","selection","voice",List.of("one"),null,h.cached());
            q.ready(t,"voice");assertNotNull(q.take(t,"selection","voice",x->true));h.player.play();String request=h.request;int stops=h.stops;
            assertTrue("Speech has been submitted but fake audio is delayed",h.player.snapshot().playing);assertFalse(h.player.actualRequestStarted());
            // Same terminal batch signal used by RadioService for qualifyingFull=0/failed6.
            if(q.batchMayRevoke("cached")){q.revoke();h.player.pause();}
            assertEquals("failedSources="+failedSources,stops,h.stops);assertEquals(request,h.player.snapshot().currentRequestId);assertTrue(q.awaitingAudio());
            h.player.onStart(request);q.actualStarted();assertTrue(h.player.actualRequestStarted());assertFalse(q.pending());
        }
    }
    @Test public void emptyBatchWithoutCachedTargetIsTerminalAndLateEventsCannotStart(){
        QuickStart q=new QuickStart();QuickStart.Ticket t=begin(q,"empty",null);assertTrue(q.batchMayRevoke("empty"));q.terminal("empty");
        q.ready(t,"voice");assertFalse(q.published("empty",item(Kind.ARTICLE_ANALYSIS,true,"one")));assertNull(q.take(t,"selection","voice",x->true));assertFalse(q.pending());
    }
    @Test public void cachedClaimStillObeysExplicitStopSpeechFailureAndStaleCallbacks(){
        for(boolean speechFailure:new boolean[]{false,true}){
            DelayedPlayback h=new DelayedPlayback();QuickStart q=new QuickStart();QuickStart.Ticket t=q.begin("cached","selection","voice",List.of("one"),null,h.cached());q.ready(t,"voice");q.take(t,"selection","voice",x->true);h.player.play();String request=h.request;
            if(speechFailure)h.player.onError(request,"HOST fake speech failure");else h.player.pause();q.revoke();
            q.ready(t,"voice");assertNull(q.take(t,"selection","voice",x->true));assertFalse(q.published("cached",item(Kind.ARTICLE_ANALYSIS,true,"one")));h.player.onStart(request);assertFalse(h.player.actualRequestStarted());assertFalse(h.player.snapshot().playing);assertFalse(q.batchMayRevoke("cached"));
        }
    }
    @Test public void cachedFinalAdmissionOrSelectionChangeRevokesBeforeSpeech(){
        for(int mode=0;mode<3;mode++){
            QuickStart q=new QuickStart();DelayedPlayback h=new DelayedPlayback();QuickStart.Ticket t=q.begin("cached","selection","voice",List.of("one"),null,h.cached());q.ready(t,"voice");final int m=mode;
            assertNull(q.take(t,mode==0?"new-selection":"selection",mode==1?"new-voice":"voice",x->m!=2));assertFalse(q.pending());assertNull(h.request);
        }
    }
    @Test public void runningBatchBlocksExplicitUpdateAndNonSourceBeforeSpeechButAllowsCachedListen(){
        for(boolean explicitUpdate:new boolean[]{false,true}){
            DelayedPlayback h=new DelayedPlayback();
            // Paused Note/fixture has no source candidate: the shared service guard runs BEFORE its manual-play branch.
            if(!QuickStart.waitForBatch(true,!explicitUpdate,null))h.player.play();assertNull(h.request);assertFalse(h.player.snapshot().playing);
            assertTrue(QuickStart.waitForBatch(true,false,h.cached()));assertFalse(QuickStart.waitForBatch(true,true,h.cached()));assertFalse(QuickStart.waitForBatch(false,false,null));
        }
    }
    @Test public void trustedFullBriefPublishedByAdmissionCheckedBatchStartsOnlyOnce(){QuickStart q=new QuickStart();QuickStart.Ticket t=begin(q,"brief",null);assertTrue(q.published("brief",item(Kind.NEWS_BRIEF,true,"one")));q.ready(t,"voice");assertNotNull(q.take(t,"selection","voice",x->true));assertNull(q.take(t,"selection","voice",x->true));}
    private static final class DelayedPlayback implements PlayerController.SpeechEngine {
        final Article article=Article.fromSentences("article","version","Проверка источника с датой","Первое предложение.","Второе предложение.");
        String request;int stops;
        final PlayerController player=new PlayerController(List.of(new Station("station","Источник",List.of(article))),this,(delay,task)->()->{},new PlayerController.StateStore(){public PlayerController.SavedState load(){return null;}public void save(PlayerController.SavedState state){}},state->{},256);
        public void speak(String text,String id){request=id;}
        public void stop(){stops++;}
        QuickStart.Candidate cached(){return new QuickStart.Candidate("station","article","version","one","channel",Kind.ARTICLE_ANALYSIS,true,true);}
    }
}

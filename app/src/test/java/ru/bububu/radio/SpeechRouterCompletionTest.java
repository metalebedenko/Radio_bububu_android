package ru.bububu.radio;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import ru.bububu.radio.models.PlaybackTicket;

/** Production completion ordering with simulated platform observations; no Android/NN run. */
public class SpeechRouterCompletionTest {
    static final class Fixture {
        final PlaybackTicket gate=new PlaybackTicket();
        final PlaybackTicket.Token token=gate.begin("request");
        final List<String> events=new ArrayList<>();
        Fixture(){assertTrue(gate.ready(token));}
        boolean finish(boolean issued,long position,Runnable release,Runnable started){
            return SpeechRouter.finishPlayback(gate,token,issued,position,release,started);
        }
        boolean finish(boolean issued,long position){return finish(issued,position,()->events.add("release"),()->events.add("start"));}
    }
    @Test public void positiveCompletionBeforePollingReportsStartThenDoneAfterRelease(){
        Fixture f=new Fixture();boolean done=f.finish(true,21);
        if(done)f.events.add("done");
        assertTrue(done);assertEquals(Arrays.asList("release","start","done"),f.events);
        assertFalse(f.gate.current(f.token));assertFalse(f.gate.done(f.token));
    }
    @Test public void zeroAndUnknownPositionNeverAwardStartOrCompletion(){
        for(long position:new long[]{0,-1}){Fixture f=new Fixture();assertFalse(f.finish(true,position));assertEquals(List.of("release"),f.events);assertFalse(f.gate.playing(f.token));}
    }
    @Test public void readyOrCompletionWithoutSuccessfulStartIssuanceCannotAwardProgress(){
        Fixture f=new Fixture();assertFalse(f.finish(false,21));assertEquals(List.of("release"),f.events);assertFalse(f.gate.playing(f.token));
        PlaybackTicket g=new PlaybackTicket();PlaybackTicket.Token preparing=g.begin("preparing");List<String> events=new ArrayList<>();
        assertFalse(SpeechRouter.finishPlayback(g,preparing,true,21,()->events.add("release"),()->events.add("start")));assertEquals(List.of("release"),events);
    }
    @Test public void alreadyConfirmedPlaybackDoesNotDuplicateStartOrNeedFinalPosition(){
        Fixture f=new Fixture();assertTrue(f.gate.audioProgress(f.token,5));assertTrue(f.finish(true,-1));assertEquals(List.of("release"),f.events);
    }
    @Test public void staleCompletionCannotReleaseOrCompleteReplacementWithSameId(){
        Fixture f=new Fixture();PlaybackTicket.Token replacement=f.gate.begin("request");assertTrue(f.gate.ready(replacement));
        assertFalse(f.finish(true,21));assertTrue(f.events.isEmpty());assertTrue(f.gate.current(replacement));assertFalse(f.gate.done(replacement));
    }
    @Test public void releaseFailureNeverEmitsRecoveredStartOrCompletion(){
        Fixture f=new Fixture();assertThrows(IllegalStateException.class,()->f.finish(true,21,()->{f.events.add("release");throw new IllegalStateException("stop not confirmed");},()->f.events.add("start")));
        assertEquals(List.of("release"),f.events);assertTrue(f.gate.current(f.token));assertFalse(f.gate.playing(f.token));
    }
    @Test public void recoveredStartMayCancelOrReplaceWithoutOldCompletion(){
        for(boolean replace:new boolean[]{false,true}){Fixture f=new Fixture();PlaybackTicket.Token[] fresh={null};
            assertFalse(f.finish(true,21,()->f.events.add("release"),()->{f.events.add("start");if(replace){fresh[0]=f.gate.begin("new");f.gate.ready(fresh[0]);}else f.gate.cancel();}));
            assertEquals(Arrays.asList("release","start"),f.events);assertFalse(f.gate.current(f.token));if(replace)assertTrue(f.gate.current(fresh[0]));
        }
    }
    @Test public void replacementDuringReleaseCannotReceiveOldStartOrCompletion(){
        Fixture f=new Fixture();PlaybackTicket.Token[] fresh={null};
        assertFalse(f.finish(true,21,()->{f.events.add("release");fresh[0]=f.gate.begin("new");f.gate.ready(fresh[0]);},()->f.events.add("start")));
        assertEquals(List.of("release"),f.events);assertTrue(f.gate.current(fresh[0]));assertFalse(f.gate.playing(fresh[0]));
    }
    @Test public void startCallbackFailureDoesNotConsumeCompletion(){
        Fixture f=new Fixture();assertThrows(IllegalStateException.class,()->f.finish(true,21,()->f.events.add("release"),()->{f.events.add("start");throw new IllegalStateException("host failure");}));
        assertEquals(Arrays.asList("release","start"),f.events);assertTrue(f.gate.current(f.token));
    }
}

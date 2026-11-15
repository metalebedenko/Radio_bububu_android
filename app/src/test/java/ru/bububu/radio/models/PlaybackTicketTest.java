package ru.bububu.radio.models;
import org.junit.Test;
import static org.junit.Assert.*;
public class PlaybackTicketTest {
 @Test public void generationAndReadyCannotAwardAudioStartOrCompletion(){PlaybackTicket g=new PlaybackTicket();PlaybackTicket.Token t=g.begin("a");assertFalse(g.audioProgress(t,1));assertFalse(g.done(t));assertTrue(g.ready(t));assertFalse(g.audioProgress(t,0));assertFalse(g.done(t));assertTrue(g.audioProgress(t,1));assertFalse(g.audioProgress(t,2));assertTrue(g.done(t));assertFalse(g.done(t));}
 @Test public void cancelLateReadyStartDoneAndReplacementAreRejected(){PlaybackTicket g=new PlaybackTicket();PlaybackTicket.Token old=g.begin("a");g.cancel();assertFalse(g.ready(old));assertFalse(g.audioProgress(old,20));assertFalse(g.done(old));PlaybackTicket.Token fresh=g.begin("b");assertTrue(g.ready(fresh));assertTrue(g.audioProgress(fresh,10));assertFalse(g.current(old));assertTrue(g.done(fresh));}
}

package ru.bububu.radio.core;
import org.junit.Test;import static org.junit.Assert.*;
public class C7RadioResultTest {
 @Test public void successfulDownloadWaitsForDecisionThenDisappears(){RadioResultState s=new RadioResultState();assertTrue(s.visible("one",false,false,false,false));assertTrue(s.visible("one",true,false,false,true));assertFalse(s.visible("one",true,false,false,false));assertTrue(s.visible("two",true,false,false,true));}
 @Test public void automaticListenSuccessDoesNotLeaveFinishedCard(){RadioResultState s=new RadioResultState();assertTrue(s.visible("play",false,false,true,false));assertFalse(s.visible("play",true,false,true,false));}
 @Test public void errorsNeedExplicitPerRunDismissEvenAfterAddDecision(){RadioResultState s=new RadioResultState();assertTrue(s.visible("bad",true,true,false,true));assertTrue(s.visible("bad",true,true,false,false));s.dismiss("bad");assertTrue(s.visible("bad",true,true,false,true));assertFalse(s.errorVisible("bad",true));assertFalse(s.visible("bad",true,true,false,false));assertTrue(s.visible("next-bad",true,true,false,false));}
 @Test public void aNewAddFailureCanBeShownAfterEarlierErrorWasDismissed(){RadioResultState s=new RadioResultState();s.dismiss("one");assertFalse(s.errorVisible("one",true));assertTrue(s.visible("one",true,true,false,true));s.revealErrors("one");assertTrue(s.errorVisible("one",true));assertTrue(s.visible("one",true,true,false,false));}

}

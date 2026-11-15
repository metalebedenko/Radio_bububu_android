package ru.bububu.radio.core;
import org.junit.Test;
import static org.junit.Assert.*;

public class HoldGestureTest {
    @Test public void releaseAt2999ClicksOnceAndOldTimerCannotHold(){int[] calls={0,0};HoldGesture g=new HoldGesture(()->calls[0]++,()->calls[1]++);g.down(100);g.up(3099);g.tick(3100);g.up(3101);assertArrayEquals(new int[]{1,0},calls);}
    @Test public void threshold3000FiresOnceAndReleaseNeverClicks(){int[] calls={0,0};HoldGesture g=new HoldGesture(()->calls[0]++,()->calls[1]++);g.down(100);g.tick(3099);assertTrue(g.active());g.tick(3100);g.tick(6100);g.up(7100);assertArrayEquals(new int[]{0,1},calls);}
    @Test public void delayedTimerStillCannotTurnLongReleaseIntoShuffle(){int[] calls={0,0};HoldGesture g=new HoldGesture(()->calls[0]++,()->calls[1]++);g.down(0);g.up(3000);g.tick(4000);assertArrayEquals(new int[]{0,1},calls);}
    @Test public void cancelSuppressesBothLateTimerAndReleaseAndNewPressIsIndependent(){int[] calls={0,0};HoldGesture g=new HoldGesture(()->calls[0]++,()->calls[1]++);for(int i=0;i<4;i++){g.down(0);g.cancel();g.tick(4000);g.up(5000);}assertArrayEquals(new int[]{0,0},calls);g.down(6000);g.tick(8000);g.up(8100);assertArrayEquals(new int[]{1,0},calls);}
}

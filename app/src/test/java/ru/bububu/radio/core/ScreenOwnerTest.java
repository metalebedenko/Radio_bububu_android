package ru.bububu.radio.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ScreenOwnerTest {
    @Test public void oldStopCannotHideTheReplacementAndLastStopCannotBeUndoneByALateBind(){
        ScreenOwner screen=new ScreenOwner();
        long old=ScreenOwner.newStart(), current=ScreenOwner.newStart();
        assertTrue(screen.attach(old));
        assertTrue(screen.attach(current));
        assertFalse(screen.detach(old));
        assertFalse(screen.attach(old));
        assertTrue(screen.owns(current));
        assertTrue(screen.detach(current));
        assertFalse(screen.owns(current));
        assertFalse(screen.attach(current));
        assertFalse(screen.attach(old));
        long reopen=ScreenOwner.newStart();
        assertTrue(screen.attach(reopen));
        assertFalse(screen.detach(current));
        assertTrue(screen.owns(reopen));
    }
}

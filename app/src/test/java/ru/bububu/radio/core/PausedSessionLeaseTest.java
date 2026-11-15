package ru.bububu.radio.core;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PausedSessionLeaseTest {
    private static final class Rig {
        long now;
        int closed;
        final List<Runnable> tasks = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        final PausedSessionLease lease = new PausedSessionLease((delay, task) -> {
            delays.add(delay); tasks.add(task); return () -> { };
        }, () -> now, () -> closed++);
    }

    @Test public void pauseExpiresAtTenMinutes() {
        Rig r = new Rig(); r.lease.update(true, false);
        assertEquals(Long.valueOf(600_000), r.delays.get(0));
        r.now = 599_999; assertFalse(r.lease.closeIfExpired());
        r.now++; r.tasks.get(0).run(); assertEquals(1, r.closed);
        r.tasks.get(0).run(); assertEquals(1, r.closed);
    }
    @Test public void passiveStateUpdatesDoNotExtendPause() {
        Rig r = new Rig(); r.lease.update(true, false); r.now = 300_000;
        r.lease.update(true, false); assertEquals(1, r.tasks.size());
        r.now = 600_000; assertTrue(r.lease.closeIfExpired());
    }
    @Test public void resumedPlaybackRejectsCanceledDeadline() {
        Rig r = new Rig(); r.lease.update(true, false); Runnable old = r.tasks.get(0);
        r.lease.update(true, true); r.now = 600_001; old.run();
        assertEquals(0, r.closed); assertFalse(r.lease.closeIfExpired());
    }
    @Test public void explicitCloseRejectsAllOldDeadlines() {
        Rig r = new Rig(); r.lease.update(true, false); r.lease.cancel();
        r.now = 900_000; r.tasks.get(0).run(); assertEquals(0, r.closed);
    }
    @Test public void newPauseGetsNewDeadlineAndRejectsPreviousPause() {
        Rig r = new Rig(); r.lease.update(true, false); Runnable old = r.tasks.get(0);
        r.now = 100_000; r.lease.update(true, true); r.lease.update(true, false);
        r.now = 600_000; old.run(); assertEquals(0, r.closed);
        r.now = 700_000; r.tasks.get(1).run(); assertEquals(1, r.closed);
    }
    @Test public void delayedAndroidTimerIsCheckedByNextInteraction() {
        Rig r = new Rig(); r.lease.update(true, false);
        r.now = 900_000; assertTrue(r.lease.closeIfExpired());
        assertEquals(1, r.closed); r.tasks.get(0).run(); assertEquals(1, r.closed);
    }
    @Test public void earlyTimerRearmsRemainingDuration() {
        Rig r = new Rig(); r.lease.update(true, false); r.now = 500_000;
        r.tasks.get(0).run(); assertEquals(0, r.closed);
        assertEquals(Long.valueOf(100_000), r.delays.get(1));
        r.now = 600_000; r.tasks.get(1).run(); assertEquals(1, r.closed);
    }
    @Test public void boundPausedScreenDoesNotStartForegroundLease() {
        Rig r = new Rig(); r.lease.update(false, false); r.now = 600_000;
        assertEquals(0, r.tasks.size()); assertFalse(r.lease.closeIfExpired());
    }
}

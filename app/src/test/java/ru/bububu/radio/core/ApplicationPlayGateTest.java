package ru.bububu.radio.core;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class ApplicationPlayGateTest {
    @Test public void registeredPlayIsOneShotAndUnknownDeliveryDoesNotConsumeIt() {
        ApplicationPlayGate gate = new ApplicationPlayGate(() -> 0);
        String id = gate.reserve();
        assertFalse(gate.take(null)); assertFalse(gate.take("missing"));
        assertTrue(gate.take(id)); assertFalse(gate.take(id));
    }
    @Test public void newerApplicationRequestSupersedesQueuedOlderRequest() {
        ApplicationPlayGate gate = new ApplicationPlayGate(() -> 0);
        String old = gate.reserve(), fresh = gate.reserve();
        assertNotEquals(old, fresh); assertFalse(gate.take(old)); assertTrue(gate.take(fresh));
    }
    @Test public void laterNoteAudioChoiceMakesQueuedPlayPermanentlyStale() {
        long[] revision = {7}; ApplicationPlayGate gate = new ApplicationPlayGate(() -> revision[0]);
        String id = gate.reserve(); revision[0]++;
        assertFalse(gate.take(id)); assertFalse(gate.take(id));
        assertTrue(gate.take(gate.reserve()));
    }
    @Test public void explicitCancellationRevokesQueuedPlayAndAllowsFreshPlay() {
        ApplicationPlayGate gate = new ApplicationPlayGate(() -> 0);
        String id = gate.reserve(); gate.cancel();
        assertFalse(gate.take(id)); assertTrue(gate.take(gate.reserve()));
    }
    @Test public void failedOlderRegistrationCannotAbandonNewerRegisteredPlay() {
        ApplicationPlayGate gate = new ApplicationPlayGate(() -> 0);
        String old = gate.reserve(), fresh = gate.reserve();
        assertFalse(gate.abandon(old)); assertTrue(gate.take(fresh));
    }
    @Test public void failedCurrentRegistrationCannotStartOnUnexpectedLateDelivery() {
        ApplicationPlayGate gate = new ApplicationPlayGate(() -> 0);
        String id = gate.reserve(); assertTrue(gate.abandon(id)); assertFalse(gate.take(id));
    }
    @Test public void replacementServiceDoesNotAcceptOldInstanceApplicationIntent() {
        ApplicationPlayGate old = new ApplicationPlayGate(() -> 0), replacement = new ApplicationPlayGate(() -> 0);
        String oldId = old.reserve(), fresh = replacement.reserve();
        assertFalse(replacement.take(oldId)); assertTrue(replacement.take(fresh));
    }
    @Test public void reentrantNewRequestSurvivesOlderClaimCheck() {
        ApplicationPlayGate[] ref = new ApplicationPlayGate[1]; boolean[] reenter = {false}; String[] fresh = {null};
        ref[0] = new ApplicationPlayGate(() -> {
            if (reenter[0]) { reenter[0] = false; fresh[0] = ref[0].reserve(); }
            return 0;
        });
        String old = ref[0].reserve(); reenter[0] = true;
        assertFalse(ref[0].take(old)); assertTrue(ref[0].take(fresh[0]));
    }
    @Test public void staleDeliveredStartCannotFinishLaterRegisteredForegroundStart() {
        long[] revision = {0}; ApplicationPlayGate gate = new ApplicationPlayGate(() -> revision[0]);
        String old = gate.reserve(); revision[0]++; String fresh = gate.reserve();
        boolean[] started = {true}, foreground = {false}; int latestRegistered = 2;
        List<Integer> stopIds = new ArrayList<>();
        StartedRequestOwner owner = new StartedRequestOwner(id -> {
            stopIds.add(id); if (id != latestRegistered) return false;
            started[0] = false; return true;
        });
        owner.dispatch(1, () -> {
            assertFalse(gate.take(old));
            // Production stale path acknowledges foreground, releases playback, then lets
            // dispatch finish this delivery. It must not cancel the newer pending request.
            foreground[0] = true; foreground[0] = false;
            owner.finishAfterPlaybackRelease(owner.captureDeliveredId());
        }, () -> foreground[0]);
        assertEquals(java.util.Arrays.asList(1), stopIds); assertTrue(started[0]);
        owner.dispatch(2, () -> { assertTrue(gate.take(fresh)); foreground[0] = true; }, () -> foreground[0]);
        assertTrue(started[0]); assertTrue(foreground[0]); assertEquals(java.util.Arrays.asList(1), stopIds);
        foreground[0] = false; owner.finishAfterPlaybackRelease(owner.captureDeliveredId());
        assertFalse(started[0]); assertEquals(java.util.Arrays.asList(1, 2), stopIds);
    }
    @Test public void wrongThreadCannotClaimOrLoseTheOwnersRequest() throws Exception {
        ApplicationPlayGate gate = new ApplicationPlayGate(() -> 0); String id = gate.reserve();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        Thread thread = new Thread(() -> { try { gate.take(id); } catch (Throwable error) { problem.set(error); } });
        thread.start(); thread.join(1000); assertFalse(thread.isAlive());
        assertTrue(problem.get() instanceof IllegalStateException); assertTrue(gate.take(id));
    }
}

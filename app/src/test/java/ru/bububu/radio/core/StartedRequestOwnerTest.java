package ru.bububu.radio.core;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class StartedRequestOwnerTest {
    @Test public void boundServiceWithoutDeliveredStartDoesNotIssueAStop() {
        AndroidStarts android = new AndroidStarts();
        StartedRequestOwner owner = new StartedRequestOwner(android::stopSelfResult);
        assertEquals(0, owner.captureDeliveredId());
        owner.finishAfterPlaybackRelease(owner.captureDeliveredId());
        assertTrue(android.stopAttempts.isEmpty());
        android.unbind();
        assertFalse(android.serviceExists);
    }

    @Test public void queuedExpiredDirectPlayCannotCancelRegisteredFreshAppPlay() {
        Rig rig = new Rig();
        rig.startAndPauseWithProgress();
        String oldSession = rig.gate.currentSessionId();
        PlayerController.Snapshot saved = rig.player.snapshot();
        rig.events.clear();

        // Register both starts before either onStartCommand callback is delivered.
        int directId = rig.android.register();
        int freshId = rig.android.register();
        rig.now = PausedSessionLease.IDLE_TIMEOUT_MS + 1;
        rig.directPlay(directId, oldSession);

        assertEquals(Arrays.asList(directId), rig.android.stopAttempts);
        assertTrue("Android must preserve the later registered start", rig.android.started);
        assertFalse(rig.foreground);
        assertFalse(rig.focused);
        assertFalse(rig.wakeLock);
        assertFalse(rig.player.snapshot().playing);
        assertNull(rig.gate.currentSessionId());
        assertSavedMaterial(saved, rig.player.snapshot());

        rig.appPlay(freshId);
        String freshSession = rig.gate.currentSessionId();
        assertNotEquals(oldSession, freshSession);
        rig.startedSpeech();
        assertEquals(Arrays.asList("reject", "request", "start"), rig.events);
        assertSavedMaterial(saved, rig.player.snapshot());
        assertTrue(rig.player.snapshot().playing);
        assertTrue(rig.foreground);
        assertTrue(rig.notificationVisible);
        assertTrue(rig.android.started);
        rig.android.unbind();
        assertTrue("Fresh started playback survives its only binding", rig.android.serviceExists);
        assertFalse(rig.android.bound);
        assertTrue(rig.gate.accept(freshSession));

        rig.closePlayback(); // Current MediaController Stop, without another start delivery.
        assertEquals(Arrays.asList(directId, freshId), rig.android.stopAttempts);
        assertFalse(rig.android.started);
        assertFalse(rig.android.serviceExists);
        assertFalse(rig.foreground);
        assertFalse(rig.focused);
        assertFalse(rig.wakeLock);
        assertFalse(rig.player.snapshot().playing);
    }

    @Test public void explicitAppDeliveryDefersItsOwnExpiryCleanupUntilReplacementIsPlaying() {
        Rig rig = new Rig();
        rig.startAndPauseWithProgress();
        String expired = rig.gate.currentSessionId();
        rig.now = PausedSessionLease.IDLE_TIMEOUT_MS;
        int freshId = rig.android.register();

        rig.appPlay(freshId);
        assertTrue("Expiry within APP_PLAY must not complete the APP_PLAY start", rig.android.stopAttempts.isEmpty());
        assertNotEquals(expired, rig.gate.currentSessionId());
        assertTrue(rig.foreground);
        assertTrue(rig.player.snapshot().playing);
        rig.android.unbind();
        assertTrue(rig.android.serviceExists);
    }

    @Test public void expiredDirectDeliveryWithoutANewerRegisteredStartStopsWhenUnbound() {
        Rig rig = new Rig();
        rig.startAndPauseWithProgress();
        rig.android.unbind();
        String expired = rig.gate.currentSessionId();
        int directId = rig.android.register();
        rig.now = PausedSessionLease.IDLE_TIMEOUT_MS;

        rig.directPlay(directId, expired);
        assertEquals(Arrays.asList(directId), rig.android.stopAttempts);
        assertFalse(rig.android.started);
        assertFalse(rig.android.serviceExists);
        assertFalse(rig.foreground);
        assertFalse(rig.player.snapshot().playing);
    }

    @Test public void asynchronousCleanupStopsTheLatestDeliveredRequest() {
        AndroidStarts android = new AndroidStarts();
        StartedRequestOwner owner = new StartedRequestOwner(android::stopSelfResult);
        int appId = android.register();
        owner.dispatch(appId, () -> { }, () -> true);
        android.unbind();

        owner.finishAfterPlaybackRelease(owner.captureDeliveredId());
        assertEquals(Arrays.asList(appId), android.stopAttempts);
        assertFalse(android.started);
        assertFalse(android.serviceExists);
    }

    @Test public void controllerStopCannotCancelAnAlreadyRegisteredNewStart() {
        assertAsynchronousCloseProtectsRegisteredPlay(false);
    }

    @Test public void timerExpiryCannotCancelAnAlreadyRegisteredNewStart() {
        assertAsynchronousCloseProtectsRegisteredPlay(true);
    }

    @Test public void broadcastEndCannotCancelAnAlreadyRegisteredNewStart() {
        Rig rig = new Rig();
        rig.startAndPauseWithProgress();
        rig.appPlay(rig.android.register());
        int delivered = rig.owner.captureDeliveredId();
        int newAppId = rig.android.register();

        // Actual player reaches the end and the service's state listener closes playback.
        while (!rig.player.snapshot().broadcastEnded) {
            String request = rig.player.snapshot().currentRequestId;
            assertNotNull(request);
            rig.player.onStart(request);
            rig.player.onDone(request);
        }
        assertEquals(Arrays.asList(delivered), rig.android.stopAttempts);
        assertTrue(rig.android.started);
        assertFalse(rig.foreground);
        rig.appPlay(newAppId);
        // At the end of the shared broadcast a fresh Play stays stopped; it must complete
        // its own current delivery instead of leaving an idle started service behind.
        assertEquals(Arrays.asList(delivered, newAppId), rig.android.stopAttempts);
        assertFalse(rig.android.started);
    }

    @Test public void callbackCleanupIsDeferredAndCompletedOnlyOnceWithItsLocalId() {
        AndroidStarts android = new AndroidStarts();
        StartedRequestOwner owner = new StartedRequestOwner(android::stopSelfResult);
        int stopId = android.register();
        owner.dispatch(stopId, () -> {
            int captured = owner.captureDeliveredId();
            owner.finishAfterPlaybackRelease(captured);
            owner.finishAfterPlaybackRelease(captured);
            assertTrue(android.stopAttempts.isEmpty());
        }, () -> false);

        assertEquals(Arrays.asList(stopId), android.stopAttempts);
        assertFalse(android.started);
    }

    @Test public void rejectedOrUnknownDeliveryFinishesItselfAndNotAFutureRegistration() {
        AndroidStarts android = new AndroidStarts();
        StartedRequestOwner owner = new StartedRequestOwner(android::stopSelfResult);
        int rejectedId = android.register();
        int newPlayId = android.register();
        owner.dispatch(rejectedId, () -> { }, () -> false);

        assertEquals(Arrays.asList(rejectedId), android.stopAttempts);
        assertTrue(android.started);
        owner.dispatch(newPlayId, () -> { }, () -> true);
        android.unbind();
        assertTrue(android.serviceExists);
    }

    @Test public void failedExplicitStartCompletesCurrentDeliveryEvenWhenCommandThrows() {
        AndroidStarts android = new AndroidStarts();
        StartedRequestOwner owner = new StartedRequestOwner(android::stopSelfResult);
        int failedId = android.register();
        RuntimeException failure = new RuntimeException("foreground denied");
        try {
            owner.dispatch(failedId, () -> {
                owner.finishAfterPlaybackRelease(owner.captureDeliveredId());
                throw failure;
            }, () -> false);
            fail("Command failure must propagate");
        } catch (RuntimeException actual) {
            assertSame(failure, actual);
        }
        assertEquals(Arrays.asList(failedId), android.stopAttempts);
        assertFalse(android.started);
    }

    @Test public void failedOldDeliveryCannotCompleteANewerRegisteredStart() {
        AndroidStarts android = new AndroidStarts();
        StartedRequestOwner owner = new StartedRequestOwner(android::stopSelfResult);
        int failedId = android.register();
        android.register();
        try {
            owner.dispatch(failedId, () -> { throw new IllegalStateException("failed old work"); }, () -> false);
            fail("Expected command failure");
        } catch (IllegalStateException expected) {
            assertEquals("failed old work", expected.getMessage());
        }
        assertEquals(Arrays.asList(failedId), android.stopAttempts);
        assertTrue(android.started);
    }

    @Test public void releaseUsesCapturedIdDespiteReentrantDelivery() {
        AndroidStarts android = new AndroidStarts();
        StartedRequestOwner owner = new StartedRequestOwner(android::stopSelfResult);
        int oldId = android.register();
        owner.dispatch(oldId, () -> { }, () -> true);
        int capturedBeforeRelease = owner.captureDeliveredId();
        int newId = android.register();
        owner.dispatch(newId, () -> { }, () -> true); // Synchronous callback from resource release.

        owner.finishAfterPlaybackRelease(capturedBeforeRelease);
        assertEquals(newId, owner.captureDeliveredId());
        assertEquals(Arrays.asList(oldId), android.stopAttempts);
        assertTrue(android.started);
    }

    @Test public void outerFinallyDoesNotSubstituteTheNewerReentrantDeliveredId() {
        AndroidStarts android = new AndroidStarts();
        StartedRequestOwner owner = new StartedRequestOwner(android::stopSelfResult);
        int outerId = android.register();
        int innerId = android.register();
        owner.dispatch(outerId, () -> owner.dispatch(innerId, () -> { }, () -> true), () -> false);

        assertEquals(Arrays.asList(outerId), android.stopAttempts);
        assertEquals(innerId, owner.captureDeliveredId());
        assertTrue(android.started);
    }

    @Test public void canceledOldLeaseTimerCannotStopFreshPlayingRequest() {
        Rig rig = new Rig();
        rig.startAndPauseWithProgress();
        Runnable staleTimer = rig.tasks.get(0);
        rig.now = PausedSessionLease.IDLE_TIMEOUT_MS + 1;
        rig.appPlay(rig.android.register());
        String token = rig.gate.currentSessionId();
        String request = rig.player.snapshot().currentRequestId;

        staleTimer.run();
        assertTrue(rig.android.stopAttempts.isEmpty());
        assertEquals(token, rig.gate.currentSessionId());
        assertEquals(request, rig.player.snapshot().currentRequestId);
        assertTrue(rig.android.started);
        assertTrue(rig.foreground);
        assertTrue(rig.player.snapshot().playing);
    }

    private static void assertAsynchronousCloseProtectsRegisteredPlay(boolean timer) {
        Rig rig = new Rig();
        rig.startAndPauseWithProgress();
        int capturedDeliveredId = rig.owner.captureDeliveredId();
        int freshId = rig.android.register();
        if (timer) {
            rig.now = PausedSessionLease.IDLE_TIMEOUT_MS;
            rig.tasks.get(0).run();
        } else rig.closePlayback();

        assertEquals(Arrays.asList(capturedDeliveredId), rig.android.stopAttempts);
        assertTrue(rig.android.started);
        assertFalse(rig.foreground);
        assertFalse(rig.focused);
        assertFalse(rig.wakeLock);
        rig.appPlay(freshId);
        assertTrue(rig.foreground);
        assertTrue(rig.player.snapshot().playing);
        rig.android.unbind();
        assertTrue(rig.android.serviceExists);
    }

    private static void assertSavedMaterial(PlayerController.Snapshot expected, PlayerController.Snapshot actual) {
        assertEquals(expected.station.id, actual.station.id);
        assertEquals(expected.article.id, actual.article.id);
        assertEquals(expected.sentenceIndex, actual.sentenceIndex);
        assertEquals(expected.textOffset, actual.textOffset);
        assertEquals(expected.completedSentences, actual.completedSentences);
        assertEquals(expected.read, actual.read);
        assertEquals(expected.listened, actual.listened);
        assertEquals(expected.started, actual.started);
        assertEquals(expected.passEnded, actual.passEnded);
    }

    /** A model of registered starts is intentionally separate from delivered callback ids. */
    private static final class AndroidStarts {
        int lastRegisteredId;
        boolean started;
        boolean bound = true;
        boolean serviceExists = true;
        final List<Integer> stopAttempts = new ArrayList<>();

        int register() {
            started = true;
            serviceExists = true;
            return ++lastRegisteredId;
        }

        boolean stopSelfResult(int deliveredId) {
            stopAttempts.add(deliveredId);
            if (deliveredId != lastRegisteredId) return false;
            started = false;
            if (!bound) serviceExists = false;
            return true;
        }

        void unbind() {
            bound = false;
            if (!started) serviceExists = false;
        }
    }

    /** Actual player/gate/lease policies; the Android start port and speech remain JVM models. */
    private static final class Rig {
        long now;
        boolean foreground;
        boolean notificationVisible;
        boolean focused;
        boolean wakeLock;
        final List<Runnable> tasks = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final AndroidStarts android = new AndroidStarts();
        final StartedRequestOwner owner = new StartedRequestOwner(android::stopSelfResult);
        final PausedSessionLease lease;
        final SystemCommandGate gate;
        final PlayerController player;

        Rig() {
            lease = new PausedSessionLease((delay, task) -> {
                tasks.add(task);
                return () -> { }; // A canceled task can still be deliberately delivered.
            }, () -> now, this::closePlayback);
            gate = new SystemCommandGate(() -> foreground && notificationVisible, lease::closeIfExpired);
            Article article = Article.fromSentences("a1", "v1", "Первая статья",
                    "Первое предложение.", "Второе предложение.", "Третье предложение.");
            player = new PlayerController(Arrays.asList(new Station("a", "А", Arrays.asList(article))),
                    new PlayerController.SpeechEngine() {
                        @Override public void speak(String text, String requestId) { events.add("request"); }
                        @Override public void stop() { }
                    }, (delay, task) -> () -> { }, new Store(), state -> {
                        if (foreground && state.broadcastEnded) closePlayback();
                        else lease.update(foreground, state.playing);
                    }, 1000);
        }

        void startAndPauseWithProgress() {
            appPlay(android.register());
            String first = player.snapshot().currentRequestId;
            player.onStart(first);
            player.onDone(first);
            player.toggleRead();
            player.pause();
            focused = false;
            wakeLock = false;
            assertEquals(1, player.snapshot().sentenceIndex);
            assertTrue(player.snapshot().read);
            assertFalse(player.snapshot().playing);
        }

        void appPlay(int startId) {
            owner.dispatch(startId, () -> {
                lease.closeIfExpired();
                if (gate.currentSessionId() == null) gate.openSession();
                foreground = true;
                notificationVisible = true;
                focused = true;
                wakeLock = true;
                player.play();
                if (player.snapshot().broadcastEnded && foreground) closePlayback();
            }, () -> foreground);
        }

        void directPlay(int startId, String sessionId) {
            owner.dispatch(startId, () -> {
                if (!gate.accept(sessionId)) { events.add("reject"); return; }
                player.play();
            }, () -> foreground);
        }

        void startedSpeech() {
            events.add("start");
            player.onStart(player.snapshot().currentRequestId);
        }

        void closePlayback() {
            int captured = owner.captureDeliveredId();
            gate.revokeSession();
            lease.cancel();
            foreground = false;
            notificationVisible = false;
            focused = false;
            wakeLock = false;
            player.pause();
            owner.finishAfterPlaybackRelease(captured);
        }
    }

    private static final class Store implements PlayerController.StateStore {
        PlayerController.SavedState saved;
        @Override public PlayerController.SavedState load() { return saved; }
        @Override public void save(PlayerController.SavedState state) { saved = state; }
    }
}

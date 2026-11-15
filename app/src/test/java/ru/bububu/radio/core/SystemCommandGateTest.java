package ru.bububu.radio.core;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class SystemCommandGateTest {
    @Test public void unopenedGateRejectsCommandsWithoutCallingItsPorts() {
        int[] calls = {0};
        SystemCommandGate gate = new SystemCommandGate(() -> { calls[0]++; return true; },
                () -> { calls[0]++; return false; });
        assertNull(gate.currentSessionId());
        assertFalse(gate.accept(null));
        assertFalse(gate.accept("old-instance"));
        assertEquals(0, calls[0]);
    }

    @Test public void missingAndWrongIdentitiesCannotInspectOrCloseCurrentSession() {
        int[] calls = {0};
        SystemCommandGate gate = new SystemCommandGate(() -> { calls[0]++; return true; },
                () -> { calls[0]++; return false; });
        String current = gate.openSession();
        assertFalse(gate.accept(null));
        assertFalse(gate.accept(""));
        assertFalse(gate.accept("different-session"));
        assertEquals(current, gate.currentSessionId());
        assertEquals(0, calls[0]);
    }

    @Test public void initializedBoundServiceWithoutForegroundSessionIsNotUsable() {
        int[] expiryChecks = {0};
        SystemCommandGate gate = new SystemCommandGate(() -> false,
                () -> { expiryChecks[0]++; return false; });
        String token = gate.openSession();
        assertFalse(gate.accept(token));
        assertEquals(0, expiryChecks[0]);
        assertEquals(token, gate.currentSessionId());
    }

    @Test public void usableCurrentSessionChecksDeadlineSynchronously() {
        List<String> calls = new ArrayList<>();
        SystemCommandGate gate = new SystemCommandGate(() -> { calls.add("usable"); return true; },
                () -> { calls.add("deadline"); return false; });
        String token = gate.openSession();
        assertTrue(gate.accept(token));
        assertEquals(Arrays.asList("usable", "deadline", "usable"), calls);
    }

    @Test public void expiredDeadlineRejectsEvenIfCleanupPortKeepsItsIdentity() {
        SystemCommandGate gate = new SystemCommandGate(() -> true, () -> true);
        String token = gate.openSession();
        assertFalse(gate.accept(token));
    }

    @Test public void revocationAndReplacementNeverGrantThePreviousIdentity() {
        int[] expiryChecks = {0};
        SystemCommandGate gate = new SystemCommandGate(() -> true,
                () -> { expiryChecks[0]++; return false; });
        String first = gate.openSession();
        gate.revokeSession();
        assertNull(gate.currentSessionId());
        assertFalse(gate.accept(first));
        String second = gate.openSession();
        assertNotEquals(first, second);
        assertFalse(gate.accept(first));
        assertEquals(0, expiryChecks[0]);
        assertTrue(gate.accept(second));
        String third = gate.openSession();
        assertNotEquals(second, third);
        assertFalse(gate.accept(second));
        assertTrue(gate.accept(third));
    }

    @Test public void recreatedGateDoesNotAliasAValidIdentityFromPreviousInstance() {
        SystemCommandGate old = new SystemCommandGate(() -> true, () -> false);
        String oldToken = old.openSession();
        SystemCommandGate recreated = new SystemCommandGate(() -> true, () -> false);
        String newToken = recreated.openSession();
        assertNotEquals(oldToken, newToken);
        assertFalse(recreated.accept(oldToken));
        assertTrue(recreated.accept(newToken));
    }

    @Test public void reentrantRevocationRejectsEvenWhenDeadlinePortReturnsFalse() {
        AtomicReference<SystemCommandGate> owner = new AtomicReference<>();
        SystemCommandGate gate = new SystemCommandGate(() -> true, () -> {
            owner.get().revokeSession();
            return false;
        });
        owner.set(gate);
        String token = gate.openSession();
        assertFalse(gate.accept(token));
        assertNull(gate.currentSessionId());
    }

    @Test public void reentrantReplacementCannotAcceptTheOldCommandIntoNewSession() {
        AtomicReference<SystemCommandGate> owner = new AtomicReference<>();
        SystemCommandGate gate = new SystemCommandGate(() -> true, () -> {
            owner.get().openSession();
            return false;
        });
        owner.set(gate);
        String token = gate.openSession();
        assertFalse(gate.accept(token));
        assertNotEquals(token, gate.currentSessionId());
    }

    @Test public void unusableSessionAfterDeadlineCheckCannotAcceptTheCommand() {
        boolean[] usable = {true};
        SystemCommandGate gate = new SystemCommandGate(() -> usable[0], () -> {
            usable[0] = false;
            return false;
        });
        assertFalse(gate.accept(gate.openSession()));
    }

    @Test public void directPlayBeforeDeadlineResumesSavedSentenceInTheSameSession() {
        Rig rig = new Rig();
        rig.startAndPauseWithProgress();
        String token = rig.gate.currentSessionId();
        PlayerController.Snapshot before = rig.player.snapshot();
        int requests = rig.speech.requests.size();
        rig.now = PausedSessionLease.IDLE_TIMEOUT_MS - 1;

        assertTrue(rig.systemPlay(token));
        assertTrue(rig.player.snapshot().playing);
        assertEquals(token, rig.gate.currentSessionId());
        assertEquals(before.sentenceIndex, rig.player.snapshot().sentenceIndex);
        assertEquals(before.completedSentences, rig.player.snapshot().completedSentences);
        assertEquals(before.read, rig.player.snapshot().read);
        assertEquals(requests + 1, rig.speech.requests.size());
        assertEquals(before.article.sentences.get(before.sentenceIndex).text, rig.speech.latest().text);
        assertEquals(0, rig.closes);
    }

    @Test public void directPlayAtDeadlineClosesSilentlyWithoutDeliveringTimer() {
        assertExpiredDirectPlayIsSilent(PausedSessionLease.IDLE_TIMEOUT_MS);
    }

    @Test public void delayedDirectPlayAfterDeadlineClosesSilentlyWithoutDeliveringTimer() {
        assertExpiredDirectPlayIsSilent(PausedSessionLease.IDLE_TIMEOUT_MS + 120_000);
    }

    @Test public void expiredDirectNextCannotChangeArticleOrListeningMarks() {
        Rig rig = new Rig();
        rig.startAndPauseWithProgress();
        String token = rig.gate.currentSessionId();
        PlayerController.Snapshot before = rig.player.snapshot();
        rig.now = PausedSessionLease.IDLE_TIMEOUT_MS + 1;

        assertFalse(rig.systemNext(token));
        assertPausedMaterialUnchanged(before, rig.player.snapshot());
        assertFalse(rig.speech.sounding);
        assertEquals(1, rig.closes);
    }

    @Test public void expiredDirectActionPreservesPersistedPositionAndMarksForRecreation() {
        Rig rig = new Rig();
        rig.startAndPauseWithProgress();
        PlayerController.Snapshot before = rig.player.snapshot();
        String token = rig.gate.currentSessionId();
        rig.now = PausedSessionLease.IDLE_TIMEOUT_MS + 1;
        assertFalse(rig.systemNext(token));
        FakeSpeech freshSpeech = new FakeSpeech();
        PlayerController restored = new PlayerController(rig.player.stations(), freshSpeech,
                (delay, task) -> () -> { }, rig.store, state -> { }, 1000);

        assertPausedMaterialUnchanged(before, restored.snapshot());
        assertEquals(0, freshSpeech.requests.size());
        assertFalse(freshSpeech.sounding);
    }

    @Test public void freshExplicitAppPlayAfterRejectedSystemPlayStartsANewSession() {
        Rig rig = new Rig();
        rig.startAndPauseWithProgress();
        String expiredToken = rig.gate.currentSessionId();
        PlayerController.Snapshot paused = rig.player.snapshot();
        rig.now = PausedSessionLease.IDLE_TIMEOUT_MS + 1;
        assertFalse(rig.systemPlay(expiredToken));
        int requests = rig.speech.requests.size();

        rig.explicitAppPlay();
        String freshToken = rig.gate.currentSessionId();
        assertNotEquals(expiredToken, freshToken);
        assertTrue(rig.player.snapshot().playing);
        assertEquals(paused.sentenceIndex, rig.player.snapshot().sentenceIndex);
        assertEquals(paused.completedSentences, rig.player.snapshot().completedSentences);
        assertEquals(paused.read, rig.player.snapshot().read);
        assertEquals(requests + 1, rig.speech.requests.size());
        assertFalse(rig.systemPlay(expiredToken));
        assertEquals(freshToken, rig.gate.currentSessionId());
        assertTrue(rig.player.snapshot().playing);
        assertEquals(requests + 1, rig.speech.requests.size());
    }

    @Test public void explicitAppPlayAfterExpiryCleansOldLeaseBeforeOpeningSession() {
        Rig rig = new Rig();
        rig.startAndPauseWithProgress();
        String oldToken = rig.gate.currentSessionId();
        rig.now = PausedSessionLease.IDLE_TIMEOUT_MS;

        rig.explicitAppPlay();
        assertEquals(1, rig.closes);
        assertNotEquals(oldToken, rig.gate.currentSessionId());
        assertTrue(rig.player.snapshot().playing);
        assertFalse(rig.systemPlay(oldToken));
        assertTrue(rig.player.snapshot().playing);
    }

    @Test public void staleDirectCommandCannotTouchNewPlaybackOrItsDeadline() {
        Rig rig = new Rig();
        rig.startAndPauseWithProgress();
        String oldToken = rig.gate.currentSessionId();
        rig.closeSystemSession();
        rig.player.selectArticle("b", "b2");
        rig.explicitAppPlay();
        String newToken = rig.gate.currentSessionId();
        PlayerController.Snapshot before = rig.player.snapshot();
        int checks = rig.deadlineChecks;
        int requests = rig.speech.requests.size();
        int stops = rig.speech.stops;
        int closes = rig.closes;
        rig.now = PausedSessionLease.IDLE_TIMEOUT_MS * 2;

        assertFalse(rig.systemNext(oldToken));
        assertFalse(rig.systemPlay(oldToken));
        assertEquals(checks, rig.deadlineChecks);
        assertEquals(requests, rig.speech.requests.size());
        assertEquals(stops, rig.speech.stops);
        assertEquals(closes, rig.closes);
        assertEquals(newToken, rig.gate.currentSessionId());
        assertEquals(before.article.id, rig.player.snapshot().article.id);
        assertEquals(before.station.id, rig.player.snapshot().station.id);
        assertEquals(before.currentRequestId, rig.player.snapshot().currentRequestId);
        assertEquals(before.completedSentences, rig.player.snapshot().completedSentences);
        assertTrue(rig.player.snapshot().playing);
        assertTrue(rig.speech.sounding);
    }

    @Test public void missingIdentityCannotRunCleanupAgainstAnExpiredCurrentSession() {
        Rig rig = new Rig();
        rig.startAndPauseWithProgress();
        String token = rig.gate.currentSessionId();
        int checks = rig.deadlineChecks;
        rig.now = PausedSessionLease.IDLE_TIMEOUT_MS + 1;

        assertFalse(rig.systemPlay(null));
        assertEquals(checks, rig.deadlineChecks);
        assertEquals(0, rig.closes);
        assertEquals(token, rig.gate.currentSessionId());
        assertFalse(rig.player.snapshot().playing);
        assertFalse(rig.systemPlay(token));
        assertEquals(1, rig.closes);
    }

    @Test public void delayedOldTimerCannotCloseNewExplicitPlaybackAfterExpiry() {
        Rig rig = new Rig();
        rig.startAndPauseWithProgress();
        Runnable oldTimer = rig.leaseTasks.get(0);
        String oldToken = rig.gate.currentSessionId();
        rig.now = PausedSessionLease.IDLE_TIMEOUT_MS + 1;
        assertFalse(rig.systemPlay(oldToken));
        rig.explicitAppPlay();
        String newToken = rig.gate.currentSessionId();
        String request = rig.player.snapshot().currentRequestId;

        oldTimer.run();
        assertEquals(1, rig.closes);
        assertEquals(newToken, rig.gate.currentSessionId());
        assertEquals(request, rig.player.snapshot().currentRequestId);
        assertTrue(rig.player.snapshot().playing);
        assertTrue(rig.speech.sounding);
    }

    private static void assertExpiredDirectPlayIsSilent(long now) {
        Rig rig = new Rig();
        rig.startAndPauseWithProgress();
        String token = rig.gate.currentSessionId();
        PlayerController.Snapshot before = rig.player.snapshot();
        int requests = rig.speech.requests.size();
        assertEquals(1, rig.leaseTasks.size());
        rig.now = now; // The scheduled timer remains undelivered, as during device sleep.

        assertFalse(rig.systemPlay(token));
        assertPausedMaterialUnchanged(before, rig.player.snapshot());
        assertEquals(requests, rig.speech.requests.size());
        assertFalse(rig.speech.sounding);
        assertEquals(1, rig.closes);
        assertFalse(rig.foreground);
        assertFalse(rig.notificationVisible);
        assertNull(rig.gate.currentSessionId());
        rig.leaseTasks.get(0).run();
        assertEquals(1, rig.closes);
    }

    private static void assertPausedMaterialUnchanged(PlayerController.Snapshot before,
                                                    PlayerController.Snapshot after) {
        assertFalse(after.playing);
        assertNull(after.currentRequestId);
        assertEquals(before.station.id, after.station.id);
        assertEquals(before.article.id, after.article.id);
        assertEquals(before.sentenceIndex, after.sentenceIndex);
        assertEquals(before.textOffset, after.textOffset);
        assertEquals(before.completedSentences, after.completedSentences);
        assertEquals(before.read, after.read);
        assertEquals(before.listened, after.listened);
        assertEquals(before.started, after.started);
        assertEquals(before.passEnded, after.passEnded);
        assertEquals(before.error, after.error);
    }

    /** Models service routing with the actual gate, lease and player, not Android transport. */
    private static final class Rig {
        long now;
        boolean foreground;
        boolean notificationVisible;
        int closes;
        int deadlineChecks;
        final List<Runnable> leaseTasks = new ArrayList<>();
        final FakeSpeech speech = new FakeSpeech();
        final Store store = new Store();
        final PausedSessionLease lease;
        final SystemCommandGate gate;
        final PlayerController player;

        Rig() {
            lease = new PausedSessionLease((delay, task) -> {
                leaseTasks.add(task);
                return () -> { }; // Tasks can be deliberately delivered after cancellation.
            }, () -> now, this::closeSystemSession);
            gate = new SystemCommandGate(() -> foreground && notificationVisible, () -> {
                deadlineChecks++;
                return lease.closeIfExpired();
            });
            Article a1 = article("a1");
            List<Station> stations = Arrays.asList(
                    new Station("a", "А", Arrays.asList(a1, article("a2"))),
                    new Station("b", "Б", Arrays.asList(article("b1"), article("b2"))));
            player = new PlayerController(stations, speech, (delay, task) -> () -> { },
                    store, state -> lease.update(foreground, state.playing), 1000);
        }

        void startAndPauseWithProgress() {
            explicitAppPlay();
            String first = speech.latest().id;
            player.onStart(first);
            player.onDone(first);
            player.toggleRead();
            player.pause();
            assertEquals(Collections.singletonList(0), player.snapshot().completedSentences);
            assertEquals(1, player.snapshot().sentenceIndex);
            assertTrue(player.snapshot().read);
        }

        void explicitAppPlay() {
            lease.closeIfExpired();
            if (gate.currentSessionId() == null) gate.openSession();
            foreground = true;
            notificationVisible = true;
            player.play();
        }

        boolean systemPlay(String identity) {
            if (!gate.accept(identity)) return false;
            player.play();
            return true;
        }

        boolean systemNext(String identity) {
            if (!gate.accept(identity)) return false;
            player.nextArticle();
            return true;
        }

        void closeSystemSession() {
            closes++;
            foreground = false;
            notificationVisible = false;
            gate.revokeSession();
            lease.cancel();
            player.pause();
        }

        private static Article article(String id) {
            return Article.fromSentences(id, "v1", "Тест " + id,
                    "Первое предложение.", "Второе предложение.", "Третье предложение.");
        }
    }

    private static final class Store implements PlayerController.StateStore {
        PlayerController.SavedState saved;
        @Override public PlayerController.SavedState load() { return saved; }
        @Override public void save(PlayerController.SavedState state) { saved = state; }
    }

    private static final class Request {
        final String text;
        final String id;
        Request(String text, String id) { this.text = text; this.id = id; }
    }

    private static final class FakeSpeech implements PlayerController.SpeechEngine {
        final List<Request> requests = new ArrayList<>();
        boolean sounding;
        int stops;
        @Override public void speak(String text, String requestId) {
            requests.add(new Request(text, requestId));
            sounding = true;
        }
        @Override public void stop() { stops++; sounding = false; }
        Request latest() { return requests.get(requests.size() - 1); }
    }
}

package ru.bububu.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import ru.bububu.radio.core.PlayerController;

/**
 * Device integration checks for R1, with the R3 permission scope correction. The actual Activity creates the only service binding;
 * the test never binds the service, since that would conceal the original lifecycle bug.
 * Engine PLAYING is an Android callback observation, not an acoustic latency measurement.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
@SdkSuppress(minSdkVersion = 29)
public final class RadioServiceLifecycleTest {
    private static final long START_TIMEOUT_MS = 25_000;
    private static final long STATE_TIMEOUT_MS = 5_000;
    private static final long PAUSED_SETTLE_MS = 1_500;
    private Instrumentation instrumentation;
    private Context context;
    private UiAutomation automation;
    private MediaSessionManager sessions;
    private RadioActivity activity;
    // A normal Java reference does not create an Android Service binding or keep it started.
    private RadioService service;
    private MediaController controller;
    // Only active during a synchronous getActiveSessions call on the application's owner looper.
    // UiAutomation adoption is process-wide; keeping it on this looper prevents overlap with
    // application focus requests, transport callbacks and delivered TTS callbacks on that looper.
    private volatile boolean mediaLookupIdentityActive;
    private final AtomicBoolean sessionDestroyed = new AtomicBoolean();
    private MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onSessionDestroyed() { sessionDestroyed.set(true); }
    };

    @Before public void prepare() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        automation = instrumentation.getUiAutomation();
        if (Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        }
        automation.dropShellPermissionIdentity();
        sessions = context.getSystemService(MediaSessionManager.class);
    }

    @After public void cleanUp() {
        try {
            // Restore ordinary identity before any application action, even after lookup failure.
            if (automation != null) automation.dropShellPermissionIdentity();
            mediaLookupIdentityActive = false;
            if (activity != null) main(() -> { if (!activity.isDestroyed()) activity.finish(); });
            if (controller != null) {
                main(() -> {
                    controller.getTransportControls().stop();
                    controller.unregisterCallback(callback);
                });
            }
            // Removes the real started-service instance after a failed assertion as well.
            if (context != null) context.stopService(new Intent(context, RadioService.class));
            if (instrumentation != null) instrumentation.waitForIdleSync();
        } finally {
            if (automation != null) automation.dropShellPermissionIdentity();
            mediaLookupIdentityActive = false;
        }
    }

    @Test public void backgroundPauseResumesThroughSameSessionThenStopClosesEverything() {
        startReadingAndRemoveActivity();
        MediaSession.Token originalToken = controller.getSessionToken();

        pauseAndCheckDetachedService();
        int pausedSentence = mainValue(() -> service.player().snapshot().sentenceIndex);
        assertEquals(originalToken, activeAppController().getSessionToken());
        main(() -> controller.getTransportControls().play());
        awaitPlaying();
        assertEquals("Explicit Play must resume the saved sentence", pausedSentence,
                (int) mainValue(() -> service.player().snapshot().sentenceIndex));
        assertFalse("Pause must not have destroyed the controller's session", sessionDestroyed.get());
        assertEquals("Play must use the same active session", originalToken, activeAppController().getSessionToken());
        assertNoActivityBinding();

        main(() -> controller.getTransportControls().stop());
        awaitCompleteClose();
    }

    @Test public void expiredIdleTaskClosesDetachedSessionAndNotification() {
        startReadingAndRemoveActivity();
        pauseAndCheckDetachedService();
        Runnable expiry = idleCloseTask();
        assertNotNull("Pause must schedule a bounded idle close", expiry);

        // Advances the private policy deadline, then executes the real scheduled endpoint.
        // This checks expiry cleanup without waiting 10 minutes, not Android's wall-clock scheduling.
        main(() -> {
            setField(field(service, "pausedSessionLease"), "expiresAtMs", SystemClock.elapsedRealtime() - 1);
            expiry.run();
        });
        awaitCompleteClose();
    }

    @Test public void revokedIdleTaskCannotClosePlaybackAfterResume() {
        startReadingAndRemoveActivity();
        pauseAndCheckDetachedService();
        Runnable oldExpiry = idleCloseTask();
        assertNotNull(oldExpiry);

        main(() -> controller.getTransportControls().play());
        awaitPlaying();
        main(() -> {
            // Make the old deadline due: an early callback alone could hide a missing cancellation.
            setField(field(service, "pausedSessionLease"), "expiresAtMs", SystemClock.elapsedRealtime() - 1);
            oldExpiry.run();
        });
        assertFor("Revoked idle timer must preserve current playback", PAUSED_SETTLE_MS, () -> {
            int state = playbackState();
            return !sessionDestroyed.get() && (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING)
                    && mainValue(() -> service.player().snapshot().playing) && ownRunningService() != null;
        });
        assertNoActivityBinding();
        main(() -> controller.getTransportControls().stop());
        awaitCompleteClose();
    }

    @Test public void expiredDirectNotificationPlayClosesBeforeReadingThenFreshAppPlayWorks() {
        startReadingAndRemoveActivity();
        pauseAndCheckDetachedService();
        MediaSession.Token expiredToken = controller.getSessionToken();
        PendingIntent expiredPlay = notificationAction("Читать");
        PlayerController.Snapshot paused = mainValue(() -> service.player().snapshot());
        byte[] savedBeforeExpiry = savedStateBytes();
        int requestsBeforeExpiry = requestEventCount();
        int rejectionsBeforeExpiry = rejectedSystemCommandCount(RadioService.PLAY);
        assertNotNull("The real idle timer must exist, but remain undelivered in this scenario", idleCloseTask());

        // Emulate a due elapsed-realtime deadline while Android has not delivered the timer.
        // The real Notification.Action PendingIntent must enforce expiry on receipt itself.
        main(() -> setField(field(service, "pausedSessionLease"), "expiresAtMs", SystemClock.elapsedRealtime() - 1));
        sendNotificationAction(expiredPlay);
        awaitCompleteClose();
        assertTrue("Expired direct Play must actually reach the service's rejection guard",
                rejectedSystemCommandCount(RadioService.PLAY) > rejectionsBeforeExpiry);
        assertEquals("An expired direct Play must not create another TTS request", requestsBeforeExpiry, requestEventCount());
        assertSameSavedAnchorAndMarks(paused, mainValue(() -> service.player().snapshot()));
        assertArrayEquals("An expired direct action must preserve all saved positions and marks", savedBeforeExpiry, savedStateBytes());

        launchFreshActivityAndPlay(paused);
        assertFalse("New explicit Play must use a new session token", expiredToken.equals(controller.getSessionToken()));
        finishCurrentActivityAndCheckDetached();
        main(() -> controller.getTransportControls().stop());
        awaitCompleteClose();
    }

    @Test public void oldNotificationPauseCannotControlNewSessionAfterFreshAppPlay() {
        startReadingAndRemoveActivity();
        PendingIntent oldPause = notificationAction("Пауза");
        MediaSession.Token oldToken = controller.getSessionToken();
        main(() -> controller.getTransportControls().stop());
        awaitCompleteClose();
        PlayerController.Snapshot stopped = mainValue(() -> service.player().snapshot());

        launchFreshActivityAndPlay(stopped);
        MediaSession.Token freshToken = controller.getSessionToken();
        assertFalse("Explicit restart must create a new session", oldToken.equals(freshToken));
        PendingIntent freshPause = notificationAction("Пауза");
        assertFalse("Notification PendingIntents must not alias across sessions", oldPause.equals(freshPause));
        finishCurrentActivityAndCheckDetached();

        int rejectionsBeforeOldPause = rejectedSystemCommandCount(RadioService.PAUSE);
        sendNotificationAction(oldPause);
        await("Old notification Pause reaches the service's rejection guard", STATE_TIMEOUT_MS,
                () -> rejectedSystemCommandCount(RadioService.PAUSE) > rejectionsBeforeOldPause);
        assertFor("A direct Pause from the closed session must not pause or destroy the fresh session", PAUSED_SETTLE_MS, () -> {
            PlayerController.Snapshot current = mainValue(() -> service.player().snapshot());
            assertNull("Real TTS/focus errors must fail the stale-action scenario: " + current.error, current.error);
            int state = playbackState();
            MediaController active = activeAppController();
            ActivityManager.RunningServiceInfo running = ownRunningService();
            return current.playing && !sessionDestroyed.get()
                    && (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING)
                    && active != null && freshToken.equals(active.getSessionToken())
                    && running != null && running.foreground && running.clientCount == 0;
        });
        assertNoActivityBinding();
        main(() -> controller.getTransportControls().stop());
        awaitCompleteClose();
    }

    private void launchFreshActivityAndPlay(PlayerController.Snapshot saved) {
        if (controller != null) main(() -> controller.unregisterCallback(callback));
        Intent launch = new Intent(context, RadioActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = (RadioActivity) instrumentation.startActivitySync(launch);
        await("Fresh Activity's legitimate service connection", STATE_TIMEOUT_MS,
                () -> mainValue(() -> field(activity, "service")) != null);
        service = mainValue(() -> (RadioService) field(activity, "service"));
        PlayerController.Snapshot restored = mainValue(() -> service.player().snapshot());
        assertFalse("Opening the Activity must restore in pause", restored.playing);
        assertSameSavedAnchorAndMarks(saved, restored);
        await("Fresh TTS initialization", START_TIMEOUT_MS,
                () -> !mainValue(service::voiceDescription).startsWith("Проверяем"));
        String voice = mainValue(service::voiceDescription);
        // A voice/focus error after this test has already started is a FAIL, not a new skip.
        assertTrue("Fresh TTS must initialize successfully: " + voice, voice.contains("Голос:"));
        main(service::requestPlay);
        await("Fresh explicit Play's active MediaSession", STATE_TIMEOUT_MS, () -> activeAppController() != null);
        controller = activeAppController();
        assertNotNull(controller);
        MediaSession.Token actualToken = mainValue(() -> ((MediaSession) field(service, "session")).getSessionToken());
        assertEquals("Fresh controller must target the application's actual session", actualToken, controller.getSessionToken());
        sessionDestroyed.set(false);
        callback = new MediaController.Callback() {
            @Override public void onSessionDestroyed() { sessionDestroyed.set(true); }
        };
        main(() -> controller.registerCallback(callback, new Handler(Looper.getMainLooper())));
        awaitPlaying();
        assertEquals("Fresh explicit Play must begin at the saved sentence", saved.sentenceIndex,
                (int) mainValue(() -> service.player().snapshot().sentenceIndex));
    }

    private void finishCurrentActivityAndCheckDetached() {
        main(activity::finish);
        await("Fresh Activity destruction and removal of its service binding", STATE_TIMEOUT_MS, () -> {
            ActivityManager.RunningServiceInfo running = ownRunningService();
            return mainValue(activity::isDestroyed)
                    && mainValue(() -> !(boolean) field(activity, "bound"))
                    && mainValue(() -> field(activity, "service")) == null
                    && running != null && running.clientCount == 0;
        });
        assertNoActivityBinding();
    }

    private PendingIntent notificationAction(String title) {
        return mainValue(() -> {
            for (StatusBarNotification entry : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
                if (entry.getId() != 1 || !context.getPackageName().equals(entry.getPackageName())) continue;
                Notification.Action[] actions = entry.getNotification().actions;
                assertNotNull("The actual playback notification must expose direct actions", actions);
                for (Notification.Action action : actions) {
                    if (title.contentEquals(action.title)) {
                        assertNotNull("The actual notification action must contain a PendingIntent", action.actionIntent);
                        return action.actionIntent;
                    }
                }
            }
            throw new AssertionError("Missing actual notification action: " + title);
        });
    }

    private void sendNotificationAction(PendingIntent action) {
        main(() -> {
            try { action.send(); }
            catch (PendingIntent.CanceledException failure) {
                throw new AssertionError("The retained direct action must dispatch through the service guard", failure);
            }
        });
    }

    private byte[] savedStateBytes() {
        return mainValue(() -> Files.readAllBytes(new File(context.getFilesDir(), "player-state-v1.json").toPath()));
    }

    @SuppressWarnings("unchecked")
    private int requestEventCount() {
        return mainValue(() -> {
            int count = 0;
            for (String event : (List<String>) field(service, "events")) if (event.contains(" request ")) count++;
            return count;
        });
    }

    @SuppressWarnings("unchecked")
    private int rejectedSystemCommandCount(String action) {
        return mainValue(() -> {
            int count = 0;
            for (String event : (List<String>) field(service, "events")) {
                if (event.contains(" system-command-rejected ") && event.endsWith(" " + action)) count++;
            }
            return count;
        });
    }

    private void assertSameSavedAnchorAndMarks(PlayerController.Snapshot expected, PlayerController.Snapshot actual) {
        assertEquals("Station must remain selected", expected.station.id, actual.station.id);
        assertEquals("Article must remain selected", expected.article.id, actual.article.id);
        assertEquals("Sentence anchor must remain unchanged", expected.sentenceIndex, actual.sentenceIndex);
        assertEquals("Text offset must remain unchanged", expected.textOffset, actual.textOffset);
        assertEquals("Completed sentence coverage must remain unchanged", expected.completedSentences, actual.completedSentences);
        assertEquals("Listened mark must remain unchanged", expected.listened, actual.listened);
        assertEquals("Read mark must remain unchanged", expected.read, actual.read);
        assertEquals("Started mark must remain unchanged", expected.started, actual.started);
        assertEquals("Pass-ended mark must remain unchanged", expected.passEnded, actual.passEnded);
    }

    private void startReadingAndRemoveActivity() {
        Intent launch = new Intent(context, RadioActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = (RadioActivity) instrumentation.startActivitySync(launch);
        await("Activity's legitimate service connection", STATE_TIMEOUT_MS,
                () -> mainValue(() -> field(activity, "service")) != null);
        service = mainValue(() -> (RadioService) field(activity, "service"));
        await("TTS initialization", START_TIMEOUT_MS,
                () -> !mainValue(service::voiceDescription).startsWith("Проверяем"));
        String voice = mainValue(service::voiceDescription);
        Assume.assumeFalse("NOT RUN: this lifecycle test requires an installed local Russian voice: " + voice,
                voice.startsWith("Нет установленного локального русского голоса"));
        assertTrue("TTS must initialize successfully before testing resume: " + voice, voice.contains("Голос:"));

        main(() -> {
            service.player().pause();
            service.player().selectArticle("B", "B2");
            service.player().seekToFraction(0);
            service.requestPlay();
        });
        await("Bububu active MediaSession", STATE_TIMEOUT_MS, () -> activeAppController() != null);
        controller = activeAppController();
        assertNotNull(controller);
        MediaSession.Token actualToken = mainValue(() -> ((MediaSession) field(service, "session")).getSessionToken());
        assertEquals("Controller must target the real Bububu session", actualToken, controller.getSessionToken());
        controller.registerCallback(callback, new Handler(Looper.getMainLooper()));
        awaitPlaying();

        main(activity::finish);
        await("Activity destruction and removal of its service binding", STATE_TIMEOUT_MS,
                () -> {
                    ActivityManager.RunningServiceInfo running = ownRunningService();
                    return mainValue(activity::isDestroyed)
                            && mainValue(() -> !(boolean) field(activity, "bound"))
                            && mainValue(() -> field(activity, "service")) == null
                            && running != null && running.clientCount == 0;
                });
        assertNoActivityBinding();
    }

    private void pauseAndCheckDetachedService() {
        main(() -> controller.getTransportControls().pause());
        await("System controller Pause", STATE_TIMEOUT_MS,
                () -> playbackState() == PlaybackState.STATE_PAUSED && !mainValue(() -> service.player().snapshot().playing));
        // Original R1 destroyed the unbound service immediately; a single PAUSED event was insufficient.
        assertFor("Paused unbound service/session must survive", PAUSED_SETTLE_MS, () -> {
            ActivityManager.RunningServiceInfo running = ownRunningService();
            return !sessionDestroyed.get() && playbackState() == PlaybackState.STATE_PAUSED
                    && running != null && running.foreground && running.clientCount == 0
                    && activeAppController() != null && playbackNotificationVisible();
        });
        assertNoActivityBinding();
        main(() -> {
            assertFalse("Pause must release audio focus", (boolean) field(service, "focused"));
            assertNull("Pause must abandon the focus request", field(service, "focus"));
            assertFalse("Pause must release the wake lock", ((PowerManager.WakeLock) field(service, "wakeLock")).isHeld());
            assertFalse(service.player().snapshot().playing);
        });
    }

    private void awaitPlaying() {
        await("Real TTS start through the active media session", START_TIMEOUT_MS, () -> {
            PlayerController.Snapshot snapshot = mainValue(() -> service.player().snapshot());
            assertNull("TTS/focus failure must not pass as silent playback: " + snapshot.error, snapshot.error);
            return playbackState() == PlaybackState.STATE_PLAYING && snapshot.playing
                    && snapshot.currentRequestId != null && snapshot.status.equals("Читаем на телефоне");
        });
    }

    private void awaitCompleteClose() {
        await("Complete service/session/notification close", STATE_TIMEOUT_MS, () -> sessionDestroyed.get()
                && ownRunningService() == null && activeAppController() == null && !playbackNotificationVisible());
        PlaybackState terminal = controller.getPlaybackState();
        assertTrue("Released session must clear the queryable playback state and actions",
                terminal == null || (terminal.getState() == PlaybackState.STATE_NONE && terminal.getActions() == 0));
        assertTrue(mainValue(activity::isDestroyed));
        // A command to the released token must not restore playback behind the user's back.
        main(() -> controller.getTransportControls().play());
        assertFor("Released controller must not revive the service", PAUSED_SETTLE_MS,
                () -> ownRunningService() == null && activeAppController() == null && !playbackNotificationVisible());
    }

    private Runnable idleCloseTask() {
        return mainValue(() -> (Runnable) field(service, "idleCloseTask"));
    }

    private void assertNoActivityBinding() {
        assertTrue("The real Activity must be destroyed", mainValue(activity::isDestroyed));
        assertFalse("Activity must have removed its only service binding", mainValue(() -> (boolean) field(activity, "bound")));
        ActivityManager.RunningServiceInfo running = ownRunningService();
        assertNotNull("Started service must survive without Activity", running);
        assertEquals("Test must not conceal R1 by retaining a binding", 0, running.clientCount);
        assertTrue("Paused/restarted service must retain foreground status during its lease", running.foreground);
    }

    private MediaController activeAppController() {
        // Do not adopt from the instrumentation thread while the application's owner looper
        // continues running. No Play, focus request, await or callback delivery belongs here.
        return mainValue(() -> {
            java.util.List<MediaController> active;
            try {
                automation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL);
                mediaLookupIdentityActive = true;
                active = sessions.getActiveSessions(null);
            } finally {
                automation.dropShellPermissionIdentity();
                mediaLookupIdentityActive = false;
            }
            for (MediaController candidate : active) {
                if (context.getPackageName().equals(candidate.getPackageName())) return candidate;
            }
            return null;
        });
    }

    @SuppressWarnings("deprecation")
    private ActivityManager.RunningServiceInfo ownRunningService() {
        ComponentName expected = new ComponentName(context, RadioService.class);
        for (ActivityManager.RunningServiceInfo candidate : context.getSystemService(ActivityManager.class).getRunningServices(100)) {
            if (expected.equals(candidate.service)) return candidate;
        }
        return null;
    }

    private boolean playbackNotificationVisible() {
        for (StatusBarNotification notification : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (notification.getId() == 1 && context.getPackageName().equals(notification.getPackageName())) return true;
        }
        return false;
    }

    private int playbackState() {
        PlaybackState state = controller.getPlaybackState();
        return state == null ? PlaybackState.STATE_NONE : state.getState();
    }

    private void await(String label, long timeoutMs, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            assertTrue("Timed out: " + label, SystemClock.uptimeMillis() < deadline);
            SystemClock.sleep(50);
        }
    }

    private void assertFor(String label, long durationMs, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + durationMs;
        do {
            assertTrue(label, condition.getAsBoolean());
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
    }

    private void main(Runnable action) { mainValue(() -> { action.run(); return null; }); }

    private <T> T mainValue(Callable<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Runnable operation = () -> {
            try {
                assertFalse("Ordinary application actions must run outside shell identity scope", mediaLookupIdentityActive);
                result.set(action.call());
                assertFalse("Session lookup must restore ordinary identity before returning", mediaLookupIdentityActive);
            }
            catch (Throwable failure) { error.set(failure); }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) operation.run();
        else instrumentation.runOnMainSync(operation);
        if (error.get() != null) throw new AssertionError("Android main-thread assertion/action failed", error.get());
        return result.get();
    }

    private static Object field(Object owner, String name) {
        try {
            Field declared = owner.getClass().getDeclaredField(name);
            declared.setAccessible(true);
            return declared.get(owner);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Missing expected production field: " + name, failure);
        }
    }

    private static void setField(Object owner, String name, Object value) {
        try {
            Field declared = owner.getClass().getDeclaredField(name);
            declared.setAccessible(true);
            declared.set(owner, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot advance expected private policy field: " + name, failure);
        }
    }
}

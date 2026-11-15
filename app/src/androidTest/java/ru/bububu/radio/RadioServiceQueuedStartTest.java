package ru.bububu.radio;

import static org.junit.Assert.assertEquals;
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
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import ru.bububu.radio.core.PlayerController;

/**
 * R5 regression promoted from the executed test03 independent queued-start probe.
 * The actual Activity creates the only service binding;
 * the test never binds the service, since that would conceal the original lifecycle bug.
 * Engine PLAYING is an Android callback observation, not an acoustic latency measurement.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class RadioServiceQueuedStartTest {
    private static final long START_TIMEOUT_MS = 25_000;
    private static final long STATE_TIMEOUT_MS = 5_000;
    private static final long DETACHED_READING_MS = 1_500;
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
        assertTrue("R5 regression requires API 29+ for scoped media-controller lookup", Build.VERSION.SDK_INT >= 29);
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

    /** Only the elapsed deadline is accelerated; starts, intents, TTS and unbind are real. */
    @Test public void expiredDirectPlayCannotClearAlreadyQueuedFreshApplicationStart() {
        startReadingKeepingActivity();
        MediaSession.Token oldToken = controller.getSessionToken();
        main(() -> controller.getTransportControls().pause());
        await("Pause while Activity retains its ordinary binding", STATE_TIMEOUT_MS,
                () -> playbackState() == PlaybackState.STATE_PAUSED && !mainValue(() -> service.player().snapshot().playing));
        PlayerController.Snapshot paused = mainValue(() -> service.player().snapshot());
        main(() -> {
            assertFalse("The sole Activity must remain alive while both starts are registered", activity.isDestroyed());
            assertTrue("The real Activity must hold its ordinary binding", (boolean) field(activity, "bound"));
            assertEquals("The Activity must be bound to this actual service", service, field(activity, "service"));
        });
        ActivityManager.RunningServiceInfo initiallyPaused = ownRunningService();
        assertNotNull("Paused service must exist before the queued-start scenario", initiallyPaused);
        assertEquals("No test-created or second Activity binding may conceal R5", 1, initiallyPaused.clientCount);
        assertTrue("The original service must initially be started", initiallyPaused.started);
        assertTrue("The original service must initially be foreground", initiallyPaused.foreground);
        PendingIntent directPlay = notificationAction("Читать");
        int rejectionsBefore = rejectedSystemCommandCount(RadioService.PLAY);
        int eventBaseline = mainValue(() -> ((List<?>) field(service, "events")).size());

        // Both IPC start requests are registered while this single owner-looper operation runs.
        // The first onStartCommand cannot execute until after the deadline is advanced below.
        main(() -> {
            Object lease = field(service, "pausedSessionLease");
            setField(lease, "expiresAtMs", SystemClock.elapsedRealtime() + 60_000);
            try { directPlay.send(); }
            catch (PendingIntent.CanceledException failure) { throw new AssertionError(failure); }
            service.requestPlay();
            setField(lease, "expiresAtMs", SystemClock.elapsedRealtime() - 1);
        });
        await("Actual direct-command rejection delivery", STATE_TIMEOUT_MS,
                () -> rejectedSystemCommandCount(RadioService.PLAY) > rejectionsBefore);
        await("New explicit Play creates a new active session", STATE_TIMEOUT_MS,
                () -> {
                    MediaController active = activeAppController();
                    return active != null && !oldToken.equals(active.getSessionToken());
                });
        main(() -> controller.unregisterCallback(callback));
        controller = activeAppController();
        assertNotNull("The fresh session must expose an active controller", controller);
        MediaSession.Token actualFreshToken = mainValue(() -> ((MediaSession) field(service, "session")).getSessionToken());
        assertEquals("The fresh controller must target the production MediaSession", actualFreshToken, controller.getSessionToken());
        assertFalse("Cleanup must revoke the old session rather than reuse its token", oldToken.equals(actualFreshToken));
        sessionDestroyed.set(false);
        callback = new MediaController.Callback() {
            @Override public void onSessionDestroyed() { sessionDestroyed.set(true); }
        };
        main(() -> controller.registerCallback(callback, new Handler(Looper.getMainLooper())));
        awaitPlaying();

        List<String> scenarioEvents = mainValue(() -> {
            @SuppressWarnings("unchecked") List<String> all = (List<String>) field(service, "events");
            assertTrue("Queued-start trace must remain available for the precondition check", all.size() >= eventBaseline);
            return new ArrayList<>(all.subList(eventBaseline, all.size()));
        });
        String trace = String.join("\n", scenarioEvents);
        int rejection = eventIndex(scenarioEvents, "system-command-rejected", RadioService.PLAY);
        int request = eventIndex(scenarioEvents, "request", null);
        int start = eventIndex(scenarioEvents, "start", null);
        android.os.Bundle evidence = new android.os.Bundle();
        evidence.putString("stream", "\nQUEUED_START_TRACE\n" + trace + "\n");
        instrumentation.sendStatus(0, evidence);
        assertTrue("PRECONDITION NOT EXERCISED: real direct rejection must precede fresh TTS request and start. " + trace,
                rejection >= 0 && request > rejection && start > request);
        String requestEvent = scenarioEvents.get(request);
        String freshRequestId = requestEvent.substring(requestEvent.lastIndexOf(' ') + 1);
        assertTrue("PRECONDITION NOT EXERCISED: onStart must belong to the fresh request, not an old callback. " + trace,
                scenarioEvents.get(start).endsWith(" " + freshRequestId));
        PlayerController.Snapshot freshReading = mainValue(() -> service.player().snapshot());
        assertEquals("The actual playing request must be the fresh request seen in the trace", freshRequestId,
                freshReading.currentRequestId);
        assertSameSavedAnchorAndMarks(paused, freshReading);
        ActivityManager.RunningServiceInfo beforeDetach = ownRunningService();
        assertNotNull(beforeDetach);
        boolean startedBeforeDetach = beforeDetach.started;
        boolean foregroundBeforeDetach = beforeDetach.foreground;
        int clientsBeforeDetach = beforeDetach.clientCount;
        MediaSession.Token freshToken = controller.getSessionToken();
        evidence = new android.os.Bundle();
        evidence.putString("stream", "QUEUED_START_BEFORE_DETACH started=" + startedBeforeDetach
                + " foreground=" + beforeDetach.foreground + " clientCount=" + beforeDetach.clientCount
                + " playing=" + freshReading.playing + " request=" + freshReading.currentRequestId
                + " newToken=" + !oldToken.equals(freshToken) + "\n");
        instrumentation.sendStatus(0, evidence);

        main(activity::finish);
        await("Actual Activity destruction and removal of binding", STATE_TIMEOUT_MS,
                () -> mainValue(activity::isDestroyed)
                && !mainValue(() -> (boolean) field(activity, "bound"))
                && mainValue(() -> field(activity, "service")) == null);
        instrumentation.waitForIdleSync();
        ActivityManager.RunningServiceInfo afterDetach = ownRunningService();
        MediaController activeAfterDetach = activeAppController();
        PlayerController.Snapshot readingAfterDetach = mainValue(() -> service.player().snapshot());
        android.os.Bundle afterEvidence = new android.os.Bundle();
        afterEvidence.putString("stream", "QUEUED_START_AFTER_DETACH service=" + (afterDetach != null)
                + " started=" + (afterDetach != null && afterDetach.started)
                + " foreground=" + (afterDetach != null && afterDetach.foreground)
                + " clients=" + (afterDetach == null ? -1 : afterDetach.clientCount)
                + " sessionDestroyed=" + sessionDestroyed.get()
                + " activeController=" + (activeAfterDetach != null)
                + " sameFreshToken=" + (activeAfterDetach != null && freshToken.equals(activeAfterDetach.getSessionToken()))
                + " playing=" + readingAfterDetach.playing + " request=" + readingAfterDetach.currentRequestId + "\n");
        instrumentation.sendStatus(0, afterEvidence);
        long detachedObservationStarted = SystemClock.uptimeMillis();
        assertFor("Queued fresh APP_PLAY must survive Activity unbind; startedBeforeDetach=" + startedBeforeDetach,
                DETACHED_READING_MS, () -> {
            ActivityManager.RunningServiceInfo running = ownRunningService();
            MediaController active = activeAppController();
            PlayerController.Snapshot current = mainValue(() -> service.player().snapshot());
            assertNull("Real TTS/focus errors after detach must fail R5: " + current.error, current.error);
            int state = playbackState();
            return startedBeforeDetach && foregroundBeforeDetach && clientsBeforeDetach == 1
                    && running != null && running.started && running.foreground && running.clientCount == 0
                    && active != null && freshToken.equals(active.getSessionToken()) && !sessionDestroyed.get()
                    && mainValue(() -> field(service, "session")) != null && !mainValue(() -> (boolean) field(service, "destroyed"))
                    && current.playing && (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING)
                    && playbackNotificationVisible();
        });
        // Status code 0 is diagnostic only; the runner's final JUnit result decides PASS/FAIL.
        android.os.Bundle sustainedEvidence = new android.os.Bundle();
        sustainedEvidence.putString("stream", "QUEUED_START_SURVIVED_DETACH observationMs="
                + (SystemClock.uptimeMillis() - detachedObservationStarted)
                + " started=true foreground=true clientCount=0 sameFreshToken=true reading=true\n");
        instrumentation.sendStatus(0, sustainedEvidence);
        main(() -> controller.getTransportControls().stop());
        awaitCompleteClose();
    }

    private static int eventIndex(List<String> trace, String name, String id) {
        for (int index = 0; index < trace.size(); index++) {
            String event = trace.get(index);
            if (event.contains(" " + name + " ") && (id == null || event.endsWith(" " + id))) return index;
        }
        return -1;
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

    private void startReadingKeepingActivity() {
        Intent launch = new Intent(context, RadioActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = (RadioActivity) instrumentation.startActivitySync(launch);
        await("Activity's legitimate service connection", STATE_TIMEOUT_MS,
                () -> mainValue(() -> field(activity, "service")) != null);
        service = mainValue(() -> (RadioService) field(activity, "service"));
        await("TTS initialization", START_TIMEOUT_MS,
                () -> !mainValue(service::voiceDescription).startsWith("Проверяем"));
        String voice = mainValue(service::voiceDescription);
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
        main(() -> controller.registerCallback(callback, new Handler(Looper.getMainLooper())));
        awaitPlaying();

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
        assertFor("Released controller must not revive the service", DETACHED_READING_MS,
                () -> ownRunningService() == null && activeAppController() == null && !playbackNotificationVisible());
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
        while (true) {
            assertTrue(label, condition.getAsBoolean());
            if (SystemClock.uptimeMillis() >= deadline) return;
            SystemClock.sleep(50);
        }
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

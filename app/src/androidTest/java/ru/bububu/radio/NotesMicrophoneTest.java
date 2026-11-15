package ru.bububu.radio;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.Field;
import android.content.pm.InstrumentationInfo;
import org.junit.Rule;
import org.junit.rules.TestName;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import ru.bububu.radio.core.NoteBook;
import ru.bububu.radio.core.PcmWave;
import ru.bububu.radio.core.StartedRequestOwner;

/** Real microphone tests. Run only with explicit enableMic=true consent in the originating chat.
 * Diagnostics contain no captured samples or note text. PCM frames prove capture, not speech quality.
 */
@RunWith(AndroidJUnit4.class)
public final class NotesMicrophoneTest {
    private Instrumentation instrumentation;
    private RadioActivity activity;
    private RadioService service;
    private AndroidNoteStorage storage;
    @Rule public final TestName testName = new TestName();
    private static final String CALLBACK_ONLY = "deniedAndLateGrantedPermissionCallbacksDoNotStartRecording";

    @Before public void prepare() throws Exception {
        Instrumentation candidate = InstrumentationRegistry.getInstrumentation();
        String pkg = candidate.getTargetContext().getPackageName();
        InstrumentationInfo info = candidate.getContext().getPackageManager().getInstrumentationInfo(candidate.getComponentName(), 0);
        Unique45FixtureContract.identity(pkg, candidate.getContext().getPackageName(), info.targetPackage);
        assertEquals("Select exactly one method on the agreed isolated QA package",
                getClass().getName() + "#" + testName.getMethodName(), InstrumentationRegistry.getArguments().getString("class"));
        boolean capture = !CALLBACK_ONLY.equals(testName.getMethodName());
        if (capture) assertEquals("Actual microphone capture requires separate consent and enableMic=true",
                "true", InstrumentationRegistry.getArguments().getString("enableMic"));
        assertEquals("Set the agreed microphone permission on the isolated target before the session",
                capture ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED,
                candidate.getTargetContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO));
        if ("realRecordingBlocksControllerPendingIntentAndAppPlayThenSavesOriginal".equals(testName.getMethodName())
                && Build.VERSION.SDK_INT >= 33)
            assertEquals("The notification scenario requires permission on the isolated target",
                    PackageManager.PERMISSION_GRANTED, candidate.getTargetContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));

        instrumentation = candidate; // A rejected preflight must never acquire a teardown owner.
        storage = main(() -> new AndroidNoteStorage(instrumentation.getTargetContext()));
        activity = (RadioActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),
                RadioActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("Activity ordinary binding", 5000, () -> main(activity::serviceForNotes) != null);
        service = main(activity::serviceForNotes);
        assertNull(main(service::notesProblem));
        NativeFixtureSupport.clickVisible(instrumentation, "Заметки");
    }

    @After public void cleanup() {
        if (activity == null) return;
        if (service != null && main(service::notes) != null && !CALLBACK_ONLY.equals(testName.getMethodName())) {
            main(() -> { if (service.notes().recordingBusy()) service.notes().stopRecording("USER_STOP"); return null; });
            await("Recording finalization before leaving the QA target", 10000, () -> !main(() -> service.notes().recordingBusy()));
        }
        main(() -> { if (!activity.isDestroyed()) activity.finish(); return null; });
        instrumentation.waitForIdleSync();
        // Keep the test note in the isolated package. Never replace the book or remove original files.
    }

    @Test public void realRecordingBlocksControllerPendingIntentAndAppPlayThenSavesOriginal() throws Exception {
        await("TTS initialization", 25000, () -> !main(service::voiceDescription).startsWith("Проверяем"));
        main(() -> { service.player().pause(); service.exposeDiagnosticSamples(); service.selectDiagnosticSample("B", "B2");
            service.player().seekToFraction(0); service.requestPlay(); return null; });
        await("Actual TTS start prerequisite", 25000, () -> main(() -> {
            assertNull(service.player().snapshot().error);
            return service.player().snapshot().playing && service.player().snapshot().currentRequestId != null
                    && "Читаем на телефоне".equals(service.player().snapshot().status);
        }));
        main(() -> { service.player().pause(); return null; });
        PendingIntent notificationPlay = main(this::notificationPlay);
        assertNotNull("Real paused notification Play capability", notificationPlay);
        MediaController controller = main(() -> ((MediaSession) field(service, "session")).getController());
        String id = beginRecording();
        await("Positive real PCM prefix", 5000, () -> new File(storage.directory, id + ".wav.part").length() > 44 + 1600);
        int oldDelivery = main(() -> ((StartedRequestOwner) field(service, "startedRequests")).captureDeliveredId());
        int oldRejected = main(() -> rejectedPlays());
        notificationPlaySend(notificationPlay);
        main(() -> { controller.getTransportControls().play(); service.requestPlay(); return null; });
        await("PendingIntent delivery and both system Play rejections", 5000,
                () -> main(() -> ((StartedRequestOwner) field(service, "startedRequests")).captureDeliveredId() > oldDelivery
                        && rejectedPlays() >= oldRejected + 2));
        long until = SystemClock.elapsedRealtime() + 1500;
        do {
            assertTrue("Recording remains sole audio owner", main(() -> service.notes().recordingBusy()));
            assertFalse("All radio starts stay quiet during real recording", main(() -> service.player().snapshot().playing));
            assertFalse(main(() -> service.notes().originalPlaying()));
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < until);
        NativeFixtureSupport.clickVisible(instrumentation, "Остановить и сохранить");
        await("Actual mic released and original finalized", 10000, () -> !main(() -> service.notes().recordingBusy()));
        NoteBook.Note saved = main(() -> service.notesBook().find(id));
        assertTrue(saved.hasAudio); assertTrue(saved.durationMs > 0);
        assertEquals(NoteBook.SAVED, saved.recordingStatus);
        assertTrue(PcmWave.readDuration(new File(storage.directory, id + ".wav")) > 0);
        assertFalse(main(() -> service.player().snapshot().playing));
        assertFalse(AndroidNoteAudio.isOutputBlocked());
        // The original stays in the isolated QA package after this test.
    }

    @Test public void leavingActivityFinishesRealRecordingAndRecoveryNeverAutoStartsSound() {
        String id = beginRecording();
        await("Positive PCM before Activity exit", 5000, () -> new File(storage.directory, id + ".wav.part").length() > 44 + 1600);
        main(() -> { activity.finish(); return null; });
        await("Real Activity unbind", 5000, () -> main(activity::isDestroyed)
                && !main(() -> (boolean) field(activity, "bound")) && main(activity::serviceForNotes) == null);
        await("Original persisted after owner shutdown", 10000, () -> new File(storage.directory, id + ".wav").isFile()
                && main(() -> !service.notes().recordingBusy()));
        NoteBook.Note restored = main(() -> {
            NoteBook book = new NoteBook(storage); storage.recover(book); return book.find(id);
        });
        assertNotNull(restored); assertTrue(restored.hasAudio); assertTrue(restored.durationMs > 0);
        assertEquals(NoteBook.INTERRUPTED, restored.recordingStatus);
        assertFalse(main(() -> service.player().snapshot().playing));
        assertFalse(main(() -> service.notes().originalPlaying()));
        assertFalse(AndroidNoteAudio.isOutputBlocked());
    }

    @Test public void deniedAndLateGrantedPermissionCallbacksDoNotStartRecording() {
        int before = main(() -> service.notesBook().notes().size());
        NativeFixtureSupport.clickVisible(instrumentation, "Записать заметку");
        main(() -> {
            activity.onRequestPermissionsResult(104, new String[]{Manifest.permission.RECORD_AUDIO}, new int[]{PackageManager.PERMISSION_GRANTED});
            assertFalse("Unsolicited callback has no recording intent", service.notes().recordingBusy());
            set(activity, "pendingPermission", field(activity, "permissionEpoch"));
            activity.onRequestPermissionsResult(104, new String[]{Manifest.permission.RECORD_AUDIO}, new int[]{PackageManager.PERMISSION_DENIED});
            assertFalse(service.notes().recordingBusy());
            assertEquals("The denial must consume the actual pending request", -1L, field(activity, "pendingPermission"));
            set(activity, "pendingPermission", field(activity, "permissionEpoch"));
            return null;
        });
        NativeFixtureSupport.clickVisible(instrumentation, "Назад к заметкам"); // Cancels the pending start.
        main(() -> {
            activity.onRequestPermissionsResult(104, new String[]{Manifest.permission.RECORD_AUDIO}, new int[]{PackageManager.PERMISSION_GRANTED});
            assertFalse(service.notes().recordingBusy());
            return null;
        });
        instrumentation.waitForIdleSync();
        assertEquals(before, (int) main(() -> service.notesBook().notes().size()));
        assertFalse(main(() -> service.player().snapshot().playing));
        // This covers actual Activity callback/epoch logic; it is not an OS permission-dialog test.
    }

    private String beginRecording() {
        NativeFixtureSupport.clickVisible(instrumentation, "Записать заметку");
        assertFalse("Opening preparation must leave the microphone idle", main(() -> service.notes().recordingBusy()));
        NativeFixtureSupport.clickVisible(instrumentation, "Начать запись");
        String id = main(() -> {
            assertTrue("Production Activity recording command accepted", service.notes().recordingBusy());
            return service.notes().recordingId();
        });
        return id;
    }
    private PendingIntent notificationPlay() {
        for (StatusBarNotification item : instrumentation.getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications()) {
            Notification.Action[] actions = item.getNotification().actions;
            if (actions != null) for (Notification.Action action : actions) if ("Читать".contentEquals(action.title)) return action.actionIntent;
        }
        return null;
    }
    private int rejectedPlays() {
        int count = 0;
        for (String event : service.diagnostics().split("\n")) if (event.contains(" notes-radio-play-rejected")) count++;
        return count;
    }
    private void notificationPlaySend(PendingIntent intent) { try { intent.send(); } catch (PendingIntent.CanceledException error) { throw new AssertionError(error); } }
    private static Object field(Object object, String name) { try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); } catch (Exception e) { throw new AssertionError(e); } }
    private static void set(Object object, String name, Object value) { try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value); } catch (Exception e) { throw new AssertionError(e); } }
    private <T> T main(Callable<T> action) {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { result.set(action.call()); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get()); return result.get();
    }
    private void await(String label, long timeout, BooleanSupplier condition) {
        long until = SystemClock.elapsedRealtime() + timeout;
        do { if (condition.getAsBoolean()) return; SystemClock.sleep(50); } while (SystemClock.elapsedRealtime() < until);
        fail(label + " was not reached; NOT a skip");
    }
}

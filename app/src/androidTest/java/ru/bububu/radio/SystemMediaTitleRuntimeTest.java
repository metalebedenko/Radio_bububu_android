package ru.bububu.radio;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.NoteBook;
import ru.bububu.radio.core.PlayerController;
import ru.bububu.radio.core.Station;
import ru.bububu.radio.core.SystemMediaTitle;
import ru.bububu.radio.models.ModelsController;
import ru.bububu.radio.models.PackCatalog;
import static org.junit.Assert.*;

/**
 * SoleQA only, on the separately verified frozen APK/test APK pair. Required arguments:
 * r1.expectedPackage, r1.versionCode, r1.versionName, r1.signerSha256.
 * Builds an unposted production Notification and observes the real inactive MediaSession.
 * No Play, permission grants, microphone, network requests, inference, Book replacement or backups.
 * Public news uses the bundled synthetic fixture; source provenance is covered by the HOST test.
 */
@RunWith(AndroidJUnit4.class)
public final class SystemMediaTitleRuntimeTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private RadioService service;

    @Test public void syntheticNoteAndNewsUseSafeSystemTitlesWithoutPlayback() throws Exception {
        requireTargetIdentity(); // Before service/model access or any data mutation.
        assertFalse("Existing service: leave it untouched and arrange a quiet soleQA case", serviceRunning());
        // Refuse missing packs before binding can start automatic bundled preparation.
        main(() -> {
            ModelsController models = ModelsController.get(context);
            assertFalse("A model job is active", models.busy());
            assertNotNull("Silero must already be READY", models.ready(PackCatalog.SILERO));
            assertNotNull("GigaAM must already be READY", models.ready(PackCatalog.GIGAAM));
            return null;
        });
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service = ((RadioService.LocalBinder) binder).service();
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        boolean bound = main(() -> context.bindService(new Intent(context, RadioService.class), connection, Context.BIND_AUTO_CREATE));
        try {
            assertTrue("Service binding was refused", bound);
            assertTrue("Service binding timed out", connected.await(10, TimeUnit.SECONDS));
            long deadline = SystemClock.elapsedRealtime() + 30_000;
            while (!main(() -> (boolean) field(service, "bundledSetupReady"))
                    && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            main(() -> {
                assertTrue("Existing packs did not verify", (boolean) field(service, "bundledSetupReady"));
                assertFalse(ModelsController.get(context).busy());
                assertTrue("Another screen is observing this service", ((List<?>) field(service, "listeners")).isEmpty());
                assertQuiet();
                NoteBook book = service.notesBook();
                assertNotNull(book);
                book.requireCurrentAuthority();
                @SuppressWarnings("unchecked") List<Station> fixtures = (List<Station>) field(service, "diagnosticFixtures");
                Station news = null;
                Article publicArticle = null;
                publicFixture: for (Station candidate : fixtures) {
                    for (Article exact : candidate.articles) {
                        if (book.material(candidate.id, exact.id) == null) {
                            news = candidate;
                            publicArticle = exact;
                            break publicFixture;
                        }
                    }
                }
                assertNotNull("Preflight: no bundled article is free of Book material. Public fixture coverage unavailable; "
                        + "refusing before synthetic note creation. Do not delete or restore existing material.", publicArticle);
                PlayerController.Snapshot original = service.player().snapshot();
                String id = book.beginRecording("R1 synthetic private title", null, null, null, System.currentTimeMillis()).id;
                try {
                    // Metadata only: AndroidNoteAudio/NoteCoordinator.startRecording are never called.
                    book.finishRecording(id, 0, false, false, null);
                    book.edit(id, "R1 synthetic private title", "Synthetic private test text.");
                    NoteBook.Material note = book.addToAir(id);
                    verifySurfaces(snapshot(note.stationId, note.stationName, note.asArticle()), SystemMediaTitle.PRIVATE_NOTE);
                    assertEquals("R1 synthetic private title", book.find(id).title);
                    verifySurfaces(snapshot(news.id, news.name, publicArticle), publicArticle.title);
                    Article stale = new Article(note.articleId, "stale-test-version", "R1 synthetic private title", note.text);
                    verifySurfaces(snapshot(note.stationId, note.stationName, stale), SystemMediaTitle.NEUTRAL);
                } finally {
                    try {
                        NoteBook.Note owned = book.find(id);
                        if (owned != null) {
                            if (NoteBook.RECORDING.equals(owned.recordingStatus)) book.finishRecording(id, 0, false, true, "Synthetic test cleanup");
                            assertTrue("Own synthetic note cleanup failed; do not reset or restore Book", service.deleteNote(id, book.find(id).revision));
                        }
                        assertNull("Own synthetic note remains", book.find(id));
                    } finally {
                        invoke("stateChanged", service.player().snapshot());
                    }
                }
                assertQuiet();
                PlayerController.Snapshot after = service.player().snapshot();
                assertEquals(original.station.id, after.station.id);
                assertEquals(original.article == null ? null : original.article.id, after.article == null ? null : after.article.id);
                assertEquals(original.sentenceIndex, after.sentenceIndex);
                return null;
            });
        } finally {
            if (bound) {
                main(() -> { context.unbindService(connection); return null; });
                long deadline = SystemClock.elapsedRealtime() + 5_000;
                while (serviceRunning() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
                assertFalse("Bound test service did not close; report the gap without force-stop", serviceRunning());
            }
        }
    }

    private void verifySurfaces(PlayerController.Snapshot state, String expected) throws Exception {
        assertFalse(state.playing);
        invoke("stateChanged", state);
        Notification notification = (Notification) invoke("notification", state);
        assertEquals(expected, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        MediaSession session = (MediaSession) field(service, "session");
        assertNotNull(session);
        MediaMetadata metadata = new MediaController(context, session.getSessionToken()).getMetadata();
        assertNotNull(metadata);
        assertEquals(expected, metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
        assertQuiet();
    }

    private void assertQuiet() throws Exception {
        assertNotNull(service.notes());
        assertFalse(service.player().snapshot().playing);
        assertNull(service.player().snapshot().currentRequestId);
        assertFalse(service.waitingToListen());
        assertFalse((boolean) field(service, "foreground"));
        assertFalse((boolean) field(service, "notificationVisible"));
        assertFalse(service.notes().recordingBusy());
        assertFalse(service.notes().recognitionBusy());
        assertFalse(service.notes().originalPlaying());
        assertTrue(service.player().isOutputStopped());
        assertFalse(AndroidNoteAudio.isOutputBlocked());
        assertFalse(AndroidNoteAudio.isRecordingStorageBusy());
    }

    private PlayerController.Snapshot snapshot(String station, String name, Article article) {
        PlayerController controller = new PlayerController(List.of(new Station(station, name, List.of(article))),
                new PlayerController.SpeechEngine() {
                    public void speak(String text, String id) { throw new AssertionError("No speech in this test"); }
                    public void stop() { throw new AssertionError("No audio actions in this test"); }
                }, (delay, action) -> { throw new AssertionError("No scheduled playback in this test"); },
                new PlayerController.StateStore() {
                    public PlayerController.SavedState load() { return null; }
                    public void save(PlayerController.SavedState state) { throw new AssertionError("No snapshot persistence"); }
                }, state -> { }, 1000);
        return controller.snapshot(); // A separate inert controller, never installed into RadioService.
    }

    private void requireTargetIdentity() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String expected = args.getString("r1.expectedPackage");
        assertTrue("Explicit main/preview target required", "ru.bububu.radio.test".equals(expected) || "ru.bububu.radio.preview".equals(expected));
        assertEquals(expected, context.getPackageName());
        assertEquals(expected, BuildConfig.APPLICATION_ID);
        assertTrue(BuildConfig.MODEL_EXPERIMENT);
        PackageInfo info = context.getPackageManager().getPackageInfo(expected, PackageManager.GET_SIGNING_CERTIFICATES);
        assertEquals(Long.toString(BuildConfig.VERSION_CODE), args.getString("r1.versionCode"));
        assertEquals(BuildConfig.VERSION_CODE, info.getLongVersionCode());
        assertEquals(BuildConfig.VERSION_NAME, args.getString("r1.versionName"));
        assertEquals(BuildConfig.VERSION_NAME, info.versionName);
        String signer = args.getString("r1.signerSha256");
        assertTrue("Exact frozen signer required", signer != null && signer.matches("[0-9a-f]{64}"));
        assertNotNull(info.signingInfo);
        assertEquals(1, info.signingInfo.getApkContentsSigners().length);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray());
        StringBuilder actual = new StringBuilder();
        for (byte value : digest) actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        assertEquals(signer, actual.toString());
    }

    private boolean serviceRunning() {
        for (ActivityManager.RunningServiceInfo running : context.getSystemService(ActivityManager.class).getRunningServices(Integer.MAX_VALUE))
            if (context.getPackageName().equals(running.service.getPackageName())
                    && RadioService.class.getName().equals(running.service.getClassName())) return true;
        return false;
    }

    private Object invoke(String name, PlayerController.Snapshot state) throws Exception {
        Method method = RadioService.class.getDeclaredMethod(name, PlayerController.Snapshot.class);
        method.setAccessible(true);
        return method.invoke(service, state);
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private <T> T main(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { result.set(action.call()); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() instanceof Exception) throw (Exception) failure.get();
        if (failure.get() instanceof Error) throw (Error) failure.get();
        return result.get();
    }
}

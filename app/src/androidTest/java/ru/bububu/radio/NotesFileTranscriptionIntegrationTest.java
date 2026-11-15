package ru.bububu.radio;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import ru.bububu.radio.core.NoteBook;
import ru.bububu.radio.core.PcmWave;
import ru.bububu.radio.core.PlayerController;
import static org.junit.Assert.*;

/** Opt-in acceptance of the production file adapter. Only the exact prepared synthetic PCM is accepted.
 * Requires enableSyntheticFileStt=true and files/demo11-stt/synthetic.pcm from test05.
 * No microphone permission grant, HTTP, recognizer fake, model download, or private-original input.
 */
@RunWith(AndroidJUnit4.class)
public final class NotesFileTranscriptionIntegrationTest {
    private static final String FIXTURE_SHA = "1c251001d6cb5ecd9abd845772b96122e29c6bca7bdf94cb36306f1f138bfcb0";
    private Instrumentation instrumentation;
    private Context context;
    private File fixture;
    private ActivityScenario<RadioActivity> scenario;
    private RadioActivity activity;
    private RadioService service;
    private NativeFixtureSupport fixtureSupport;
    private boolean notificationsChanged;

    @Before public void prepare() throws Exception {
        Assume.assumeTrue("Prepared synthetic file-STT acceptance is explicitly enabled",
                "true".equals(InstrumentationRegistry.getArguments().getString("enableSyntheticFileStt")));
        instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        File prepared = new File(target.getFilesDir(), "demo11-stt/synthetic.pcm");
        assertTrue("Explicitly enabled test requires the prepared synthetic PCM", Files.isRegularFile(prepared.toPath(), LinkOption.NOFOLLOW_LINKS));
        assertEquals("Only the known synthetic fixture may enter this test", FIXTURE_SHA, fileHash(prepared.toPath()));
        assertEquals(263988, prepared.length());
        assertNotEquals("This file test requires app microphone permission to remain denied",
                PackageManager.PERMISSION_GRANTED, target.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
        assertTrue("Installed file-source support requires API 33+", Build.VERSION.SDK_INT >= 33);
        // Assign the mutable environment only after all fixture/permission preconditions pass.
        context = target; fixture = prepared;
        instrumentation.getUiAutomation().dropShellPermissionIdentity();
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
            notificationsChanged = true;
        }
        fixtureSupport = new NativeFixtureSupport(instrumentation, context, managedPaths());
        fixtureSupport.stopAndDrain(null);
        fixtureSupport.isolate("demo12-file-stt-preserve-");
        launch();
        assertNull(main(service::notesProblem)); assertNotNull(main(service::notes));
        assertSilent();
    }

    @After public void restore() throws Exception {
        if (context == null) return;
        Throwable failure = null;
        try { closeService(); } catch (Throwable error) { failure = error; }
        if (fixtureSupport != null && fixtureSupport.drained()) {
            try { fixtureSupport.restore(); } catch (Throwable error) { failure = append(failure, error); }
            if (notificationsChanged) try { instrumentation.getUiAutomation().revokeRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS); }
            catch (Throwable error) { failure = append(failure, error); }
        }
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new AssertionError("Restoration failed; remaining original backups retained", failure);
    }

    @Test public void visibleConfirmedFileTranscriptionKeepsOriginalAndQueuedRevisionAcrossRestart() throws Exception {
        clickExact("Заметки");
        String id = main(() -> {
            PlayerController.Snapshot anchor = service.player().snapshot();
            assertNotNull(anchor.article);
            NoteBook.Note note = service.notesBook().beginRecording("Синтетическая проверка файла",
                    anchor.station.id, anchor.article.id, anchor.article.version, 1);
            File wave = new File(service.noteStorage().directory, note.id + ".wav");
            assertEquals(FIXTURE_SHA, fileHash(fixture.toPath()));
            byte[] pcm = Files.readAllBytes(fixture.toPath());
            // The test05 container has extra WAV chunks. Create the recorder's canonical 44-byte WAV.
            try (PcmWave.Writer writer = new PcmWave.Writer(new File(service.noteStorage().directory, note.id + ".wav.part"))) {
                writer.append(pcm, 0, pcm.length); writer.finish(wave);
            }
            assertEquals(263988, PcmWave.readInfo(wave).pcmBytes);
            service.notesBook().finishRecording(note.id, PcmWave.readDuration(wave), true, false, "Синтетический оригинал без микрофона");
            service.notesBook().edit(note.id, note.title, "Сохранённая ручная версия для неизменного эфира.");
            service.notesBook().setOriginalPosition(note.id, 1000);
            service.addNoteToAir(note.id);
            return note.id;
        });
        NoteBook.Note before = main(() -> service.notesBook().find(id));
        NoteBook.Material queued = main(() -> queued(id));
        assertEquals(1, before.revision);
        File wave = new File(main(() -> service.noteStorage().directory), id + ".wav");
        String originalHash = fileHash(wave.toPath());

        byte[] unchanged = main(() -> service.noteStorage().load());
        clickExact("Расшифровать ещё раз"); clickExact("Отмена");
        assertArrayEquals("Cancel replacement dialog has no metadata effect", unchanged, main(() -> service.noteStorage().load()));
        assertFalse(main(() -> service.notes().recognitionBusy()));
        clickExact("Расшифровать ещё раз"); clickExact("Заменить текст");
        await(200000, () -> main(() -> {
            assertSilentOnOwner();
            NoteBook.Note note = service.notesBook().find(id);
            return !service.notes().recognitionBusy() && !"TRANSCRIBING".equals(note.recognitionStatus);
        }));
        NoteBook.Note ready = main(() -> service.notesBook().find(id));
        assertEquals("Unsupported/error file recognition is a failure of enabled acceptance", "READY", ready.recognitionStatus);
        String normalized = ready.text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        assertTrue("Prepared synthetic beginning was recognized", normalized.contains("начало проверки"));
        assertTrue("Prepared synthetic tail was recognized", normalized.contains("конец проверки"));
        assertEquals(before.revision + 1, ready.revision);
        assertPreserved(before, ready);
        assertEquals(originalHash, fileHash(wave.toPath()));
        assertQueuedUnchanged(queued, main(() -> queued(id)));
        assertSilent();

        closeService(); launch();
        NoteBook.Note restored = main(() -> service.notesBook().find(id));
        assertEquals("READY", restored.recognitionStatus); assertEquals(ready.text, restored.text);
        assertEquals(ready.revision, restored.revision); assertPreserved(before, restored);
        assertQueuedUnchanged(queued, main(() -> queued(id)));
        assertEquals(originalHash, fileHash(new File(main(() -> service.noteStorage().directory), id + ".wav").toPath()));
        assertFalse("Old saved notes never automatically transcribe on restart", main(() -> service.notes().recognitionBusy()));
        assertSilent();
        assertNotEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
    }

    private NoteBook.Material queued(String id) {
        for (NoteBook.Material material : service.notesBook().materials()) if (id.equals(material.noteId)) return material;
        throw new AssertionError("Own immutable queued note revision missing");
    }
    private static void assertPreserved(NoteBook.Note before, NoteBook.Note after) {
        assertEquals(before.id, after.id); assertEquals(before.createdAt, after.createdAt); assertEquals(before.title, after.title);
        assertEquals(before.positionMs, after.positionMs); assertEquals(before.durationMs, after.durationMs); assertEquals(before.hasAudio, after.hasAudio);
        assertEquals(before.recordingStatus, after.recordingStatus); assertEquals(before.linkedStation, after.linkedStation);
        assertEquals(before.linkedArticle, after.linkedArticle); assertEquals(before.linkedVersion, after.linkedVersion);
        assertEquals(before.linkedSourceVersionKey, after.linkedSourceVersionKey);
    }
    private static void assertQueuedUnchanged(NoteBook.Material before, NoteBook.Material after) {
        assertEquals(before.stationId, after.stationId); assertEquals(before.articleId, after.articleId);
        assertEquals(before.version, after.version); assertEquals(before.text, after.text); assertEquals(before.sequence, after.sequence);
    }
    private void assertSilent() { main(() -> { assertSilentOnOwner(); return null; }); }
    private void assertSilentOnOwner() {
        assertFalse(service.notes().recordingBusy()); assertFalse(service.notes().originalPlaying());
        assertFalse(service.player().snapshot().playing); assertNull(service.player().snapshot().currentRequestId);
    }
    private void launch() {
        scenario = ActivityScenario.launch(RadioActivity.class); scenario.onActivity(value -> activity = value);
        await(10000, () -> main(() -> activity.serviceForNotes() != null)); service = main(activity::serviceForNotes);
        fixtureSupport.track(service);
    }
    private void closeService() throws Exception {
        if (fixtureSupport != null) fixtureSupport.stopAndDrain(() -> { if (scenario != null) { scenario.close(); scenario = null; } });
        service = null; activity = null;
    }
    private List<File> managedPaths() {
        List<File> paths = new ArrayList<>();
        for (String name : new String[]{"notes", "source-cas", "source-network"}) paths.add(new File(context.getNoBackupFilesDir(), name));
        for (String suffix : new String[]{"", ".bak", ".new"}) paths.add(new File(context.getFilesDir(), "player-state-v1.json" + suffix));
        return paths;
    }
    private void clickExact(String caption) {
        NativeFixtureSupport.clickVisible(instrumentation, caption);
    }
    private <T> T main(Callable<T> action) {
        AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { value.set(action.call()); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() instanceof Error) throw (Error) error.get();
        if (error.get() != null) throw new AssertionError("Owner action failed", error.get()); return value.get();
    }
    private static Object field(Object object, String name) throws Exception { java.lang.reflect.Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private void await(long duration, BooleanSupplier condition) {
        long until = SystemClock.elapsedRealtime() + duration;
        while (SystemClock.elapsedRealtime() < until) { if (condition.getAsBoolean()) return; SystemClock.sleep(50); }
        fail("Enabled file-STT acceptance prerequisite did not occur in " + duration + "ms");
    }
    private static String fileHash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); updateFile(digest, path); return hex(digest.digest());
    }
    private static void updateFile(MessageDigest digest, Path path) throws Exception {
        byte[] buffer = new byte[4096];
        try (InputStream input = Files.newInputStream(path)) { int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); }
    }
    private static String hex(byte[] bytes) { StringBuilder text = new StringBuilder(); for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b & 255)); return text.toString(); }
    private static Throwable append(Throwable previous, Throwable error) { if (previous == null) return error; previous.addSuppressed(error); return previous; }
}

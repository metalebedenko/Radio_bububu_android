package ru.bububu.radio;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import ru.bububu.radio.core.NoteBook;
import ru.bububu.radio.core.PcmWave;
import ru.bububu.radio.core.PlayerController;
import ru.bububu.radio.sources.ExtractionResult;
import ru.bububu.radio.sources.SafeHttpTransport;
import ru.bububu.radio.sources.SemanticTextNormalizer;
import ru.bububu.radio.sources.SourceArticleFramer;
import ru.bububu.radio.sources.SourceBodyCache;
import ru.bububu.radio.sources.SourceItem;
import ru.bububu.radio.sources.SourceRecord;
import ru.bububu.radio.sources.SourceVersionRecord;
import static org.junit.Assert.*;

/** Production Service/Book/storage/coordinator ports and visible dialogs. Own synthetic data; no HTTP or microphone. */
@RunWith(AndroidJUnit4.class)
public class SourcesDeletionIntegrationTest {
    private static final String WARNING = "Источник не проверен командой";
    private Instrumentation instrumentation;
    private Context context;
    private ActivityScenario<RadioActivity> scenario;
    private RadioActivity activity;
    private RadioService service;
    private LinearLayout panel;
    private SourcesPanel news;
    private Path backup;
    private boolean isolated, notificationsChanged;
    private final Map<Path, Path> moved = new LinkedHashMap<>();
    private final Map<Path, String> originalHashes = new LinkedHashMap<>();

    @Before public void prepare() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        instrumentation.getUiAutomation().dropShellPermissionIdentity();
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
            notificationsChanged = true;
        }
        context.stopService(new Intent(context, RadioService.class));
        await(10000, () -> running() == null);
        instrumentation.waitForIdleSync();
        backup = Files.createTempDirectory(context.getCacheDir().toPath(), "source-delete-preserve-");
        int index = 0;
        // Before full isolation succeeds, only already moved originals are restored; unmoved paths are never purged.
        for (File file : managedPaths()) if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Path path = file.toPath(), preserved = backup.resolve("original-" + index++);
            originalHashes.put(path, treeHash(path));
            Files.move(path, preserved); moved.put(path, preserved);
        }
        isolated = true;
        launch();
        assertNull(main(service::notesProblem));
        assertNotNull(main(service::notesBook));
        assertNotNull(main(service::sources));
        assertTrue("Actual production transport is retained; these cases never call fetch", main(() -> field(service.sources(), "transport")) instanceof SafeHttpTransport);
    }

    @After public void restore() throws Exception {
        Throwable failure = null;
        try { closeService(); } catch (Throwable error) { failure = error; }
        // Preserve the backup if the system refuses shutdown; never overwrite original data under a live service.
        if (context != null && running() != null) {
            AssertionError live = new AssertionError("Service still live; originals retained in preservation directory");
            if (failure != null) live.addSuppressed(failure);
            throw live;
        }
        if (isolated) for (File file : managedPaths()) {
            try { delete(file.toPath()); } catch (Throwable error) { failure = append(failure, error); }
        }
        boolean allRestored = true;
        for (Map.Entry<Path, Path> entry : moved.entrySet()) {
            try {
                if (Files.exists(entry.getKey(), LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Restoration target was not cleared");
                Files.move(entry.getValue(), entry.getKey());
                assertEquals("Managed original bytes must be unchanged", originalHashes.get(entry.getKey()), treeHash(entry.getKey()));
            } catch (Throwable error) { allRestored = false; failure = append(failure, error); }
        }
        if (allRestored && backup != null) try { delete(backup); } catch (Throwable error) { failure = append(failure, error); }
        if (notificationsChanged) try {
            instrumentation.getUiAutomation().revokeRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        } catch (Throwable error) { failure = append(failure, error); }
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new AssertionError("Managed-path restoration failed; remaining backups retained", failure);
    }

    @Test public void visibleItemAndSourceDeleteCancelConfirmAndCustomWarningSurvivesReopen() throws Exception {
        Saved saved = install("Синтетический custom", "visible", "Самостоятельный вымышленный текст.", "unknown");
        showPanel(); assertShown(WARNING); assertShownScrolling("Полнота неизвестна"); showPanel();
        clickExact("Другие действия с новостью · Вымышленная новость"); clickExact("Отметить прочитанным");
        assertTrue(main(() -> service.notesBook().sourceItem(saved.source.id, saved.item.itemKey).read));
        clickExact("Другие действия с новостью · Вымышленная новость"); clickExact("Снять отметку «прочитано»");
        assertFalse(main(() -> service.notesBook().sourceItem(saved.source.id, saved.item.itemKey).read));
        byte[] before = main(() -> service.noteStorage().load());
        clickExact("Другие действия с новостью · Вымышленная новость"); clickExact("Удалить новость"); assertShown("Удалить новость?"); clickExact("Отмена");
        assertArrayEquals(before, main(() -> service.noteStorage().load()));
        clickExact("Другие действия с новостью · Вымышленная новость"); clickExact("Удалить новость"); clickExact("Удалить");
        assertTrue(main(() -> service.notesBook().isItemDeleted(saved.source.id, saved.item.itemKey)));
        assertTrue(main(() -> service.notesBook().sourceItems(saved.source.id).isEmpty()));
        assertNull(main(() -> service.notesBook().material(saved.source.stationId, saved.item.articleId)));
        reopen(); showPanel(); assertShown(WARNING);
        assertTrue(main(() -> service.notesBook().isItemDeleted(saved.source.id, saved.item.itemKey)));
        before = main(() -> service.noteStorage().load());
        clickExact("Настройки · " + saved.source.name);
        clickExact("Удалить источник"); assertShown("Связанные заметки и записи останутся"); clickExact("Отмена");
        assertArrayEquals(before, main(() -> service.noteStorage().load()));
        clickExact("Удалить источник"); clickExact("Удалить");
        assertTrue(main(() -> service.notesBook().isSourceRetired(saved.source.id)));
        showPanel(); assertAbsent(saved.source.name);
        assertTrue(main(() -> service.notesBook().sources().isEmpty()));
        reopen(); assertTrue(main(() -> service.notesBook().isSourceRetired(saved.source.id)));
        assertFalse(main(() -> service.player().snapshot().playing));
    }

    @Test public void playingCurrentSourceDeleteRequiresExplicitSurvivorChoiceAndRestartsSilently() throws Exception {
        Saved current = install("Синтетическая текущая", "current", longBody(), "unknown");
        Saved survivor = install("Синтетическая оставшаяся", "survivor", "Первая отдельная фраза для позиции. Вторая отдельная фраза для позиции. Третья отдельная фраза для позиции. Четвёртая отдельная фраза для позиции.", "full");
        main(() -> { service.command(p -> p.selectStation(survivor.source.stationId)); service.command(p -> p.seekToFraction(.4)); return null; });
        int anchor = main(() -> service.player().snapshot().sentenceIndex);
        assertTrue(anchor > 0);
        selectBody(current); startRadio();
        showPanel(); clickExact("Настройки · " + current.source.name); clickExact("Удалить источник"); clickExact("Удалить");
        assertShown("Мой эфир · порядок станций"); assertAbsent(current.source.name);
        assertShown(survivor.source.name); clickExact("Отмена");
        assertTrue(main(() -> service.player().snapshot().selectionRequired));
        assertTrue(main(() -> service.player().isOutputStopped()));
        assertEquals(current.source.stationId, main(() -> service.player().savedState().currentStationId));
        main(() -> { service.requestPlay(); return null; }); assertSilent(700);
        reopen();
        assertTrue(main(() -> service.player().snapshot().selectionRequired));
        main(() -> { service.requestPlay(); return null; }); assertSilent(700);
        main(() -> { activity.chooseStationAfterDeletion(); return null; });
        clickExact(survivor.source.name + " · 1 текстов");
        assertFalse(main(() -> service.player().snapshot().selectionRequired));
        assertFalse(main(() -> service.player().snapshot().playing));
        assertNull(main(() -> service.player().snapshot().currentRequestId));
        assertEquals(survivor.source.stationId, main(() -> service.player().snapshot().station.id));
        assertEquals(anchor, (int) main(() -> service.player().snapshot().sentenceIndex));
    }

    @Test public void linkedExactVersionWaveManualRevisionAndPositionSurviveWithReadOnlyNoteContext() throws Exception {
        Saved saved = install("Синтетический контекст", "linked", "Точный вымышленный контекст версии один.", "unknown");
        final String noteId = main(() -> {
            NoteBook book = service.notesBook();
            NoteBook.Note note = book.beginRecording("Синтетическая заметка", saved.source.stationId, saved.item.articleId, saved.version.versionKey, 10);
            writeWave(service.noteStorage().directory, note.id, 4000);
            book.finishRecording(note.id, 4000, true, false, "Синтетический WAV без микрофона");
            book.edit(note.id, "Ручная версия один", "Первый ручной текст.");
            service.addNoteToAir(note.id);
            book.edit(note.id, "Ручная версия два", "Второй ручной текст.");
            book.setOriginalPosition(note.id, 2345);
            return note.id;
        });
        NoteBook.Note before = main(() -> service.notesBook().find(noteId));
        NoteBook.Material queued = main(() -> {
            for (NoteBook.Material m : service.notesBook().materials()) if (noteId.equals(m.noteId)) return m;
            throw new AssertionError("Own queued revision missing");
        });
        String wavHash = treeHash(new File(main(() -> service.noteStorage().directory), noteId + ".wav").toPath());
        SourceVersionRecord v2 = main(() -> {
            SourceVersionRecord version = version(saved.source, saved.item, "Несвязанная вымышленная версия два.", "full");
            new SourceBodyCache(new File(context.getNoBackupFilesDir(), "source-cas")).put(version);
            service.notesBook().saveSourceVersion(version.withPayload(null));
            return version;
        });
        main(() -> { service.sources().deleteSource(saved.source.id); return null; });
        assertNote(before, main(() -> service.notesBook().find(noteId)));
        assertEquals(wavHash, treeHash(new File(main(() -> service.noteStorage().directory), noteId + ".wav").toPath()));
        assertEquals(saved.version.spokenText, main(() -> service.notesBook().sourceVersion(saved.version.recordKey).spokenText));
        assertNull(main(() -> service.notesBook().sourceVersion(v2.recordKey)));
        main(() -> {
            try { service.sources().savedText(v2.recordKey, result -> fail("Deleted standalone reader must not deliver")); fail("Deleted reader must reject before work"); }
            catch (IllegalArgumentException | IllegalStateException expected) { }
            return null;
        });
        reopen();
        assertNote(before, main(() -> service.notesBook().find(noteId)));
        assertEquals(wavHash, treeHash(new File(main(() -> service.noteStorage().directory), noteId + ".wav").toPath()));
        NoteBook.Material restored = main(() -> service.notesBook().material(queued.stationId, queued.articleId));
        assertNotNull(restored); assertEquals(queued.version, restored.version); assertEquals(queued.text, restored.text);
        main(() -> { SourcesPanel.showLinkedReader(activity, service, noteId); return null; });
        assertShown("Источник/новость удалены · контекст заметки"); assertShown(saved.version.body()); assertShown(WARNING);
        assertAbsent("Добавить этот текст в эфир"); assertAbsent("Изменить отметку"); assertAbsent("Удалить новость");
        clickExact("Закрыть");
    }

    @Test public void deletingOtherSourcePreservesActualARequestAndMediaTokenForLongSubmittedFragment() throws Exception {
        Saved a = install("Синтетическая речь A", "playing-a", longBody(), "full");
        Saved b = install("Синтетическая очередь B", "delete-b", "Вымышленный удаляемый текст B.", "unknown");
        selectBody(a); startRadio();
        String request = main(() -> service.player().snapshot().currentRequestId);
        MediaSession.Token token = main(() -> ((MediaSession) field(service, "session")).getSessionToken());
        main(() -> { service.sources().deleteSource(b.source.id); return null; });
        assertTrue(main(() -> service.notesBook().isSourceRetired(b.source.id)));
        long until = SystemClock.elapsedRealtime() + 1500;
        do {
            assertTrue(main(() -> service.player().snapshot().playing));
            assertEquals(request, main(() -> service.player().snapshot().currentRequestId));
            assertEquals(a.version.versionKey, main(() -> service.player().snapshot().article.version));
            assertEquals(token, main(() -> ((MediaSession) field(service, "session")).getSessionToken()));
            ActivityManager.RunningServiceInfo running = running(); assertNotNull(running); assertTrue(running.foreground); assertTrue(running.started);
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < until);
        main(() -> { service.command(PlayerController::pause); return null; });
    }

    private Saved install(String name, String key, String body, String quality) {
        return main(() -> {
            SourceRecord source = SourceRecord.create("telegram_public", "telegram_public:" + key, name,
                    "https://t.me/s/own_synthetic_" + key.replace('-', '_'), "telegram-public-v1", false);
            SourceItem item = SourceItem.create(source.id, "own:" + key, key, "https://t.me/own_synthetic/1", "Вымышленная новость", -1, -1, 1);
            SourceVersionRecord version = version(source, item, body, quality);
            service.notesBook().commitSource(source, Collections.singletonList(item), Collections.singletonList(version.withPayload(null)));
            service.sources().enqueue(version, !"full".equals(quality));
            return new Saved(source, item, version);
        });
    }
    private static SourceVersionRecord version(SourceRecord source, SourceItem item, String body, String quality) throws Exception {
        ExtractionResult extracted = new ExtractionResult(body, "", "Вымышленная новость", quality, "SYNTHETIC", "public_preview_text", "telegram-public-v1");
        SourceArticleFramer.Framed framed = SourceArticleFramer.frame(source.name, extracted);
        return new SourceVersionRecord(source.id, item.itemKey, item.articleId, framed.versionHash, extracted.displayTitle,
                extracted.providerTitle, source.name, item.url, extracted.completeness, extracted.reason, extracted.method,
                extracted.adapterVersion, SemanticTextNormalizer.VERSION, SourceArticleFramer.VERSION, framed.bodyHash,
                framed.spokenText, framed.bodyStart, framed.bodyEnd, 1);
    }
    private static String longBody() {
        return ("Это полностью вымышленный текст для локальной проверки стабильного чтения и сохранности очереди без настоящих новостей и личных данных ").repeat(50) + ".";
    }
    private void selectBody(Saved saved) {
        main(() -> {
            service.command(p -> p.selectStation(saved.source.stationId));
            double body = (double) saved.version.bodyStart / saved.version.spokenText.length();
            service.command(p -> p.seekToFraction(body)); return null;
        });
    }
    private void startRadio() {
        main(() -> { service.requestPlay(); return null; });
        await(25000, () -> main(() -> {
            PlayerController.Snapshot s = service.player().snapshot();
            assertNull("TTS/focus failure is not playback", s.error);
            return s.playing && s.currentRequestId != null && "Читаем на телефоне".equals(s.status);
        }));
    }
    private void assertSilent(long duration) {
        long until = SystemClock.elapsedRealtime() + duration;
        do {
            assertFalse(main(() -> service.player().snapshot().playing));
            assertNull(main(() -> service.player().snapshot().currentRequestId));
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < until);
    }
    private void showPanel() {
        main(() -> { panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);SourcesManagementPanel management=new SourcesManagementPanel(activity);news=new SourcesPanel(activity);panel.addView(management);panel.addView(news);ScrollView scroll = new ScrollView(activity); scroll.addView(panel); activity.setContentView(scroll);management.refresh();news.refresh(); return null; });
        await(5000, () -> main(() -> !(boolean) field(news, "loading")));
        instrumentation.waitForIdleSync();
    }
    private void launch() {
        scenario = ActivityScenario.launch(RadioActivity.class); scenario.onActivity(value -> activity = value);
        await(10000, () -> main(() -> activity.serviceForNotes() != null)); service = main(activity::serviceForNotes);
    }
    private void reopen() throws Exception { closeService(); launch(); }
    private void closeService() throws Exception {
        if (service != null && !main(() -> (boolean) field(service, "destroyed"))) main(() -> {
            MediaSession session = (MediaSession) field(service, "session");
            if (session != null) new MediaController(context, session.getSessionToken()).getTransportControls().stop();
            service.player().pause(); return null;
        });
        if (scenario != null) { scenario.close(); scenario = null; }
        if (context != null) { context.stopService(new Intent(context, RadioService.class)); await(10000, () -> running() == null); instrumentation.waitForIdleSync(); }
        service = null; activity = null;
    }
    private List<File> managedPaths() {
        List<File> paths = new ArrayList<>();
        for (String name : new String[] {"notes", "source-cas", "source-network"}) paths.add(new File(context.getNoBackupFilesDir(), name));
        for (String suffix : new String[] {"", ".bak", ".new"}) paths.add(new File(context.getFilesDir(), "player-state-v1.json" + suffix));
        return paths;
    }
    @SuppressWarnings("deprecation") private ActivityManager.RunningServiceInfo running() {
        for (ActivityManager.RunningServiceInfo info : context.getSystemService(ActivityManager.class).getRunningServices(100))
            if (info.service.equals(new ComponentName(context, RadioService.class))) return info;
        return null;
    }
    private void assertShown(String text) { assertTrue("Visible UI must contain: " + text, visibleText(text)); }
    private void assertShownScrolling(String text) {
        for (int attempt = 0; attempt < 8; attempt++) {
            if (visibleText(text)) return;
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow(); assertNotNull(root);
            boolean scrolled; try { scrolled = scrollForward(root); } finally { root.recycle(); }
            if (!scrolled) break;
            SystemClock.sleep(100);
        }
        assertShown(text);
    }
    private void assertAbsent(String text) { assertFalse("Active UI must not contain: " + text, visibleText(text)); }
    private boolean visibleText(String text) {
        instrumentation.waitForIdleSync(); AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        assertNotNull(root); boolean found = false;
        try { for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) { found |= node.isVisibleToUser(); node.recycle(); } }
        finally { root.recycle(); } return found;
    }
    private void clickExact(String text) {
        for (int attempt = 0; attempt < 8; attempt++) {
            instrumentation.waitForIdleSync(); AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            assertNotNull(root); boolean clicked = false, scrolled = false;
            try {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                    if (!clicked && node.isVisibleToUser() && node.isEnabled() && node.isClickable()
                            && (text.equalsIgnoreCase(node.getText() == null ? "" : node.getText().toString())
                            || text.equalsIgnoreCase(node.getContentDescription() == null ? "" : node.getContentDescription().toString())))
                        clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    node.recycle();
                }
                if (!clicked) scrolled = scrollForward(root);
            } finally { root.recycle(); }
            if (clicked) { instrumentation.waitForIdleSync(); return; }
            if (!scrolled) break;
            SystemClock.sleep(100);
        }
        fail("Real visible clickable control not found: " + text);
    }
    private static boolean scrollForward(AccessibilityNodeInfo node) {
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i); if (child == null) continue;
            try { if (scrollForward(child)) return true; } finally { child.recycle(); }
        }
        return false;
    }
    private <T> T main(Callable<T> action) {
        AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { value.set(action.call()); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() instanceof Error) throw (Error) error.get();
        if (error.get() != null) throw new AssertionError("Main-thread action failed", error.get()); return value.get();
    }
    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private void await(long duration, BooleanSupplier condition) {
        long until = SystemClock.elapsedRealtime() + duration;
        while (SystemClock.elapsedRealtime() < until) { if (condition.getAsBoolean()) return; SystemClock.sleep(50); }
        fail("Required runtime precondition did not occur in " + duration + "ms");
    }
    private static void assertNote(NoteBook.Note before, NoteBook.Note after) {
        assertNotNull(after); assertEquals(before.id, after.id); assertEquals(before.createdAt, after.createdAt);
        assertEquals(before.title, after.title); assertEquals(before.text, after.text); assertEquals(before.revision, after.revision);
        assertEquals(before.linkedStation, after.linkedStation); assertEquals(before.linkedArticle, after.linkedArticle);
        assertEquals(before.linkedVersion, after.linkedVersion); assertEquals(before.linkedSourceVersionKey, after.linkedSourceVersionKey);
        assertEquals(before.positionMs, after.positionMs); assertEquals(before.durationMs, after.durationMs); assertEquals(before.hasAudio, after.hasAudio);
        assertEquals(before.recordingStatus, after.recordingStatus); assertEquals(before.recognitionStatus, after.recognitionStatus); assertEquals(before.message, after.message);
    }
    private static void writeWave(File directory, String id, long duration) throws Exception {
        byte[] pcm = new byte[(int) (duration * PcmWave.SAMPLE_RATE / 1000) * 2];
        for (int i = 0; i < pcm.length / 2; i++) { short tone = (short) (300 * Math.sin(2 * Math.PI * 440 * i / PcmWave.SAMPLE_RATE)); pcm[2 * i] = (byte) tone; pcm[2 * i + 1] = (byte) (tone >> 8); }
        try (PcmWave.Writer writer = new PcmWave.Writer(new File(directory, id + ".wav.part"))) { writer.append(pcm, 0, pcm.length); writer.finish(new File(directory, id + ".wav")); }
    }
    private static String treeHash(Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> entries = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) { stream.sorted().forEach(entries::add); }
        byte[] buffer = new byte[4096];
        for (Path entry : entries) {
            digest.update(root.relativize(entry).toString().getBytes(StandardCharsets.UTF_8));
            if (Files.isSymbolicLink(entry)) digest.update(Files.readSymbolicLink(entry).toString().getBytes(StandardCharsets.UTF_8));
            else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) try (InputStream input = Files.newInputStream(entry)) { int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); }
        }
        StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return hash.toString();
    }
    private static void delete(Path path) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (java.util.stream.Stream<Path> entries = Files.walk(path)) { for (Path entry : entries.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.delete(entry); }
    }
    private static Throwable append(Throwable previous, Throwable error) { if (previous == null) return error; previous.addSuppressed(error); return previous; }
    private static final class Saved {
        final SourceRecord source; final SourceItem item; final SourceVersionRecord version;
        Saved(SourceRecord source, SourceItem item, SourceVersionRecord version) { this.source = source; this.item = item; this.version = version; }
    }
}

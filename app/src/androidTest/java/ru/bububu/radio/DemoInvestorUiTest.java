package ru.bububu.radio;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.graphics.Rect;
import android.graphics.Point;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import ru.bububu.radio.core.NoteBook;
import ru.bububu.radio.core.PlayerController;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.sources.SourceBodyCache;
import ru.bububu.radio.sources.SourceCoordinator;
import ru.bububu.radio.sources.SourceRecord;
import ru.bububu.radio.sources.SourceTransport;
import ru.bububu.radio.sources.SourceVersionRecord;
import static org.junit.Assert.*;

/** Real five-tab Activity, Book/CAS and Service queue port; synthetic transport never opens HTTP or audio. */
@RunWith(AndroidJUnit4.class)
public final class DemoInvestorUiTest {
    private Instrumentation instrumentation;
    private Context context;
    private ActivityScenario<RadioActivity> scenario;
    private RadioActivity activity;
    private RadioService service;
    private SourceCoordinator originalCoordinator, coordinator;
    private final AtomicInteger requests = new AtomicInteger();
    private NativeFixtureSupport fixtureSupport;
    private boolean notificationsChanged;
    private Map<String, ?> preferences;

    @Before public void prepare() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        instrumentation.getUiAutomation().dropShellPermissionIdentity();
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
            notificationsChanged = true;
        }
        fixtureSupport = new NativeFixtureSupport(instrumentation, context, managedPaths());
        fixtureSupport.stopAndDrain(null);
        preferences = new LinkedHashMap<>(context.getSharedPreferences(UiStyle.PREFS, Context.MODE_PRIVATE).getAll());
        assertTrue(context.getSharedPreferences(UiStyle.PREFS, Context.MODE_PRIVATE).edit().clear().commit());
        fixtureSupport.isolate("demo12-ui-preserve-");
        scenario = ActivityScenario.launch(RadioActivity.class);
        bindActivity();
        main(() -> {
            assertNull(service.notesProblem());
            originalCoordinator = service.sources();
            fixtureSupport.track(originalCoordinator);
            SourceCoordinator.Port actualPort = (SourceCoordinator.Port) field(originalCoordinator, "port");
            SourceTransport transport = (url, kind, validators, scope) -> {
                scope.check(); requests.incrementAndGet();
                String name = url.substring(url.lastIndexOf('/') + 1);
                StringBuilder html = new StringBuilder("<div class='tgme_channel_info_header_username'>@" + name
                        + "</div><div class='tgme_channel_info_header_title'>Вымышленная станция " + name + "</div>");
                for (int i = 1; i <= 5; i++) html.append("<div class='tgme_widget_message' data-post='").append(name).append('/').append(i)
                        .append("'><div class='tgme_widget_message_text js-message_text'>Вымышленная новость ").append(i)
                        .append(". Второе предложение для позиции. Третье предложение для проверки.</div><a class='tgme_widget_message_date'><time datetime='2026-10-12T12:0")
                        .append(i).append(":00Z'></time></a></div>");
                return new SourceTransport.Response(html.toString().getBytes(StandardCharsets.UTF_8), url, 200, "text/html", "UTF-8", null, null);
            };
            coordinator = new SourceCoordinator(service.notesBook(), transport,
                    new SourceBodyCache(new File(context.getNoBackupFilesDir(), "source-cas")), actualPort, new AndroidSourceClock());
            setField(service, "sources", coordinator);
            fixtureSupport.track(coordinator);
            return null;
        });
    }

    @Test public void cleanBookMaterialNoteRejectsHiddenFixtureAndLateClickKeepsRealAndDiagnosticPaths() throws Exception {
        assertTrue(main(()->service.notesBook().sources().isEmpty()));assertTrue(main(()->service.notesBook().notes().isEmpty()));assertTrue(main(()->service.notesBook().materials().isEmpty()));assertEquals(8,ru.bububu.radio.sources.SourceCatalog.entries().size());
        String fixtureTitle=LocalData.fixtures(context).get(0).articles.get(0).title;
        for(String skin:new String[]{"Нео","Баббл"}) {
            tab(4);click(skin);tab(0);assertVisible("Ваш эфир пока пуст");
            byte[] before=main(()->service.noteStorage().load());List<Object> selected=playerTuple();int network=requests.get();
            main(()->{Button note=(Button)field(activity,"noteForMaterial");assertFalse(note.isEnabled());assertFalse(((Button)field(activity,"play")).isEnabled());note.setEnabled(true);assertTrue(note.performClick());assertEquals(0,field(activity,"selectedTab"));assertNull(field(field(activity,"notesPanel"),"articleId"));assertFalse(((TextView)field(field(activity,"notesPanel"),"link")).getText().toString().contains(fixtureTitle));return null;});
            assertArrayEquals(before,main(()->service.noteStorage().load()));assertEquals(selected,playerTuple());assertEquals(network,requests.get());assertPaused();
            tab(2);assertVisible("Источников пока нет");tab(1);await(5000,()->main(()->!(boolean)field(field(activity,"newsPage"),"loading")));assertVisible("Загруженных текстов пока нет.");
            tab(3);assertVisible("Новая независимая заметка");assertTrue(main(()->service.notesBook().notes().isEmpty()));assertTrue(main(()->service.notesBook().sources().isEmpty()));assertTrue(main(()->service.notesBook().materials().isEmpty()));
        }
        SourceRecord source=connect("own_clean_start_material","Вымышленный настоящий путь");AtomicReference<SourceCoordinator.DownloadProgress> loaded=new AtomicReference<>();
        main(()->{coordinator.downloadBatch(Collections.singleton(source.id),5,loaded::set);return null;});await(10000,()->loaded.get()!=null&&loaded.get().finished);SourceVersionRecord exact=snapshot(source.id).versions.get(0);
        main(()->{coordinator.enqueue(exact,true);service.command(p->p.selectArticle(source.stationId,exact.articleId));return null;});tab(0);
        Button retained=main(()->(Button)field(activity,"noteForMaterial"));clickActualButton(retained);assertEquals(3,main(()->field(activity,"selectedTab")));
        main(()->{Object panel=field(activity,"notesPanel");assertEquals(source.stationId,field(panel,"stationId"));assertEquals(exact.articleId,field(panel,"articleId"));assertEquals(exact.versionKey,field(panel,"version"));return null;});assertTrue(main(()->service.notesBook().notes().isEmpty()));
        tab(0);main(()->{coordinator.remove(source.id,exact.articleId);assertFalse(retained.isEnabled());retained.setEnabled(true);assertTrue(retained.performClick());assertEquals(0,field(activity,"selectedTab"));return null;});assertTrue(main(()->service.notesBook().notes().isEmpty()));assertPaused();
        String own=main(()->{NoteBook.Note note=service.notesBook().beginRecording("Вымышленная своя заметка",null,null,null,1);service.notesBook().finishRecording(note.id,0,false,false,null);service.notesBook().edit(note.id,note.title,"Сохранённый собственный текст.");service.addNoteToAir(note.id);NoteBook.Material material=queuedNote(note.id);service.command(p->p.selectArticle(material.stationId,material.articleId));return note.id;});
        clickActualButton(main(()->(Button)field(activity,"noteForMaterial")));assertEquals(3,main(()->field(activity,"selectedTab")));assertEquals(main(()->queuedNote(own).articleId),main(()->field(field(activity,"notesPanel"),"articleId")));assertEquals(1,main(()->service.notesBook().notes().size()).intValue());
        tab(4);click("О приложении / Диагностика");NativeDialogActions.clickListRow(instrumentation,"Встроенные тестовые станции");NativeDialogActions.clickListRow(instrumentation,"Город будущего · 2 текстов");tab(0);
        assertEquals("A",main(()->service.player().snapshot().station.id));assertTrue(main(()->(boolean)field(activity,"diagnosticsFixtures")));clickActualButton(main(()->(Button)field(activity,"noteForMaterial")));assertEquals(3,main(()->field(activity,"selectedTab")));
        main(()->{Object panel=field(activity,"notesPanel");assertEquals("A",field(panel,"stationId"));assertEquals("A1",field(panel,"articleId"));assertTrue(((TextView)field(panel,"link")).getText().toString().contains(fixtureTitle));return null;});assertEquals(1,main(()->service.notesBook().notes().size()).intValue());assertPaused();
    }

    @Test public void fiveTabsSkinAndSearchKeepExactPlayerStateAndUnsavedNoteFields() throws Exception {
        SourceRecord source = connect("own_demo_skin", "Вымышленный эфир");
        AtomicReference<SourceCoordinator.DownloadProgress> download = new AtomicReference<>();
        main(() -> { coordinator.downloadBatch(Collections.singleton(source.id), 5, download::set); return null; });
        await(10000, () -> download.get() != null && download.get().finished);
        assertEquals(5, download.get().saved);
        SourceCoordinator.BulkSnapshot snapshot = snapshot(source.id);
        SourceVersionRecord version = snapshot.versions.get(0);
        main(() -> { coordinator.enqueue(version, true); service.command(p -> p.selectStation(source.stationId)); service.command(p -> p.seekToFraction(.65)); return null; });
        List<Object> exactPlayer = playerTuple();
        assertEquals(source.stationId, exactPlayer.get(0));
        assertTrue(main(() -> service.player().snapshot().sentenceIndex > 0));
        String noteId = main(() -> {
            NoteBook book = service.notesBook();
            NoteBook.Note note = book.beginRecording("Сохранённое название", null, null, null, 1);
            book.finishRecording(note.id, 0, false, false, null);
            book.edit(note.id, note.title, "Сохранённый ручной текст.");
            return note.id;
        });
        byte[] durableBeforeDraft = main(() -> service.noteStorage().load());
        assertFiveTabs();
        assertRadioControlsVisible();
        tab(3);
        Object card = main(() -> ((Map<?, ?>) field(field(activity, "notesPanel"), "views")).get(noteId));
        assertNotNull(card);
        EditText heading = main(() -> (EditText) field(card, "heading"));
        EditText text = main(() -> (EditText) field(card, "text"));
        EditText search = main(() -> (EditText) field(field(activity, "notesPanel"), "search"));
        main(() -> { heading.setText("Несохранённое название"); text.setText("Несохранённая ручная правка."); search.setText("нет такой заметки 11"); return null; });
        assertTrue(main(() -> ((Map<?, ?>) field(field(activity, "notesPanel"), "views")).isEmpty()));
        assertTrue(main(() -> ((Set<?>) field(service.notes(), "dirtyDrafts")).contains(noteId)));
        assertEquals("Слушать запись", main(() -> ((Button) field(card, "play")).getText().toString()));
        assertEquals("Стоп", main(() -> ((Button) field(card, "stop")).getText().toString()));
        assertEquals(main(() -> UiStyle.palette(activity).brand).intValue(), main(() -> ((android.widget.SeekBar) field(card, "track")).getProgressTintList().getDefaultColor()).intValue());
        main(() -> { search.setText(""); return null; });
        assertSame(card, main(() -> ((Map<?, ?>) field(field(activity, "notesPanel"), "views")).get(noteId)));
        assertEquals("Несохранённая ручная правка.", main(() -> text.getText().toString()));
        assertArrayEquals(durableBeforeDraft, main(() -> service.noteStorage().load()));
        tab(4); click("Баббл"); tab(0); assertRadioControlsVisible(); tab(3);
        assertSame(card, main(() -> ((Map<?, ?>) field(field(activity, "notesPanel"), "views")).get(noteId)));
        assertEquals("Несохранённое название", main(() -> heading.getText().toString()));
        assertEquals("Несохранённая ручная правка.", main(() -> text.getText().toString()));
        assertTrue(main(() -> ((Set<?>) field(service.notes(), "dirtyDrafts")).contains(noteId)));
        assertEquals(main(() -> UiStyle.palette(activity).brand).intValue(), main(() -> ((android.widget.SeekBar) field(card, "track")).getProgressTintList().getDefaultColor()).intValue());
        assertArrayEquals(durableBeforeDraft, main(() -> service.noteStorage().load()));
        assertEquals(exactPlayer, playerTuple());
        main(() -> { ((Button) field(card, "save")).performClick(); return null; });
        assertEquals("Несохранённая ручная правка.", main(() -> service.notesBook().find(noteId).text));
        assertFalse(main(() -> ((Set<?>) field(service.notes(), "dirtyDrafts")).contains(noteId)));
        byte[] savedBook = main(() -> service.noteStorage().load());
        scenario.recreate(); bindActivity();
        assertEquals("bubble", context.getSharedPreferences(UiStyle.PREFS, Context.MODE_PRIVATE).getString("skin", "neo"));
        assertEquals(exactPlayer, playerTuple());
        assertArrayEquals(savedBook, main(() -> service.noteStorage().load()));
        assertEquals("Несохранённое название", main(() -> service.notesBook().find(noteId).title));
        assertFiveTabs(); assertPaused();
    }

    @Test public void catalogueBothSkinsOpenCancelAndAlreadyConnectedKeepExactState() throws Exception {
        ru.bububu.radio.sources.SourceCatalog.Entry entry=new ru.bububu.radio.sources.SourceCatalog.Entry("Вымышленный каталог","@own_catalogue",true,"Доступный текст · полнота не подтверждена");
        for(String skin:new String[]{"Нео","Баббл"}) {
            tab(4);click(skin);tab(2);assertVisible("Каталог");
            byte[] before=main(()->service.noteStorage().load());List<Object> playerBefore=playerTuple();int network=requests.get();
            android.app.AlertDialog builtIn=main(()->SourceCatalogDialog.show(activity));
            for(ru.bububu.radio.sources.SourceCatalog.Entry approved:ru.bububu.radio.sources.SourceCatalog.entries()) {
                main(()->{TextView title=null;boolean description=false;for(TextView label:views(builtIn.getWindow().getDecorView(),TextView.class)){if(approved.name.contentEquals(label.getText()))title=label;if(approved.description.contentEquals(label.getText()))description=true;}assertNotNull(title);assertTrue(description);title.requestRectangleOnScreen(new Rect(0,0,title.getWidth(),title.getHeight()),true);return null;});
                instrumentation.waitForIdleSync();assertVisible(approved.name);
            }
            assertEquals(network,requests.get());assertArrayEquals(before,main(()->service.noteStorage().load()));assertEquals(playerBefore,playerTuple());click("Закрыть");
            main(()->{SourceCatalogDialog.show(activity,Collections.singletonList(entry));return null;});
            assertVisible(entry.name);assertEquals(network,requests.get());assertArrayEquals(before,main(()->service.noteStorage().load()));assertEquals(playerBefore,playerTuple());
            click("Закрыть");assertArrayEquals(before,main(()->service.noteStorage().load()));
            main(()->{SourceCatalogDialog.show(activity,Collections.singletonList(entry));return null;});clickDescription("Проверить источник: "+entry.name);
            awaitCataloguePreview();click("Отмена");assertTrue(main(()->service.notesBook().sources().isEmpty()));assertTrue(main(()->service.notesBook().materials().isEmpty()));assertEquals(playerBefore,playerTuple());assertPaused();
        }
        main(()->{SourceCatalogDialog.show(activity,Collections.singletonList(entry));return null;});clickDescription("Проверить источник: "+entry.name);awaitCataloguePreview();click("Подключить");
        await(5000,()->main(()->service.notesBook().sources().size()==1));SourceRecord source=main(()->service.notesBook().sources().get(0));assertTrue(source.catalog);assertTrue(main(()->service.notesBook().materials().isEmpty()));
        for(SourceVersionRecord version:main(()->service.notesBook().sourceVersionsForSource(source.id)))assertEquals("unknown",version.completeness);
        byte[] connected=main(()->service.noteStorage().load());int network=requests.get();List<Object> state=playerTuple();
        android.app.AlertDialog dialog=main(()->SourceCatalogDialog.show(activity,Collections.singletonList(entry)));
        assertVisible("Уже подключён · "+source.name);
        main(()->{List<Button> controls=views(dialog.getWindow().getDecorView(),Button.class);Button duplicate=null;for(Button button:controls)if(("Уже подключён: "+entry.name).contentEquals(button.getContentDescription()==null?"":button.getContentDescription()))duplicate=button;assertNotNull(duplicate);assertFalse(duplicate.isEnabled());return null;});
        assertEquals(network,requests.get());assertArrayEquals(connected,main(()->service.noteStorage().load()));assertEquals(state,playerTuple());click("Закрыть");
        scenario.recreate();bindActivity();assertEquals(source.id,main(()->service.notesBook().sources().get(0).id));assertTrue(main(()->service.notesBook().materials().isEmpty()));assertPaused();
    }

    @Test public void catalogueReconnectCancelThenConfirmKeepsRetiredNoteContextAndRestartsEmpty() throws Exception {
        SourceRecord old=connect("own_catalogue_reconnect","Прежнее имя источника");AtomicReference<SourceCoordinator.DownloadProgress> downloaded=new AtomicReference<>();
        main(()->{coordinator.downloadBatch(Collections.singleton(old.id),5,downloaded::set);return null;});await(10000,()->downloaded.get()!=null&&downloaded.get().finished);
        SourceVersionRecord linked=snapshot(old.id).versions.get(0);main(()->{coordinator.enqueue(linked,true);return null;});
        String note=ownWaveNote("Вымышленный контекст каталога","Личная редакция сохраняется.",linked);List<Object> fields=noteFields(note);String wave=waveHash(note);
        main(()->{NoteBook.Material own=queuedNote(note);service.command(p->p.selectArticle(own.stationId,own.articleId));coordinator.deleteSource(old.id);return null;});
        ru.bububu.radio.sources.SourceCatalog.Entry entry=new ru.bububu.radio.sources.SourceCatalog.Entry("Новая станция каталога","@own_catalogue_reconnect",true,"Доступный текст");
        tab(2);byte[] retired=main(()->service.noteStorage().load());List<Object> selected=playerTuple();
        main(()->{SourceCatalogDialog.show(activity,Collections.singletonList(entry));return null;});assertVisible("Подключить заново");clickDescription("Подключить заново: "+entry.name);awaitCataloguePreview();click("Подключить");assertVisible("Подключить источник заново?");click("Отмена");
        assertArrayEquals(retired,main(()->service.noteStorage().load()));assertTrue(main(()->service.notesBook().sources().isEmpty()));assertEquals(selected,playerTuple());
        click("Подключить");assertVisible("Подключить источник заново?");click("Подключить");await(5000,()->main(()->service.notesBook().sources().size()==1));
        SourceRecord fresh=main(()->service.notesBook().sources().get(0));assertNotEquals(old.id,fresh.id);assertNotEquals(old.stationId,fresh.stationId);assertTrue(fresh.catalog);
        assertTrue(main(()->service.notesBook().isSourceRetired(old.id)));assertEquals(fields,noteFields(note));assertEquals(wave,waveHash(note));assertEquals(linked.spokenText,main(()->service.notesBook().sourceVersion(linked.recordKey).spokenText));assertEquals(selected,playerTuple());
        assertEquals(1,main(()->service.notesBook().materials().size()).intValue());assertNotNull(main(()->queuedNote(note)));assertPaused();
        downloaded.set(null);main(()->{coordinator.downloadBatch(Collections.singleton(fresh.id),5,downloaded::set);return null;});await(10000,()->downloaded.get()!=null&&downloaded.get().finished);assertEquals(5,downloaded.get().saved);assertEquals(1,main(()->service.notesBook().materials().size()).intValue());
        assertNotEquals(linked.articleId,snapshot(fresh.id).downloaded.get(0).articleId);
        fixtureSupport.stopAndDrain(()->{scenario.close();scenario=null;});service=null;scenario=ActivityScenario.launch(RadioActivity.class);bindActivity();
        assertEquals(fresh.id,main(()->service.notesBook().sources().get(0).id));assertTrue(main(()->service.notesBook().isSourceRetired(old.id)));assertEquals(fields,noteFields(note));assertEquals(wave,waveHash(note));assertEquals(linked.spokenText,main(()->service.notesBook().sourceVersion(linked.recordKey).spokenText));assertEquals(1,main(()->service.notesBook().materials().size()).intValue());assertPaused();
    }

    private void awaitCataloguePreview(){await(10000,()->{instrumentation.waitForIdleSync();AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();if(root==null)return false;try{boolean found=false;for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("Найдено заголовков: 5")){found|=node.isVisibleToUser();node.recycle();}return found;}finally{root.recycle();}});}

    @Test public void defaultFiveDownloadsThenAllOrOneFilterRequiresOfflineBulkConsentWithoutDuplicates() throws Exception {
        SourceRecord first = connect("own_demo_a", "Вымышленная станция А");
        SourceRecord second = connect("own_demo_b", "Вымышленная станция Б");
        tab(2);
        View management = main(() -> (View) field(activity, "sourcesPage"));
        Spinner count = main(() -> views(management, Spinner.class).get(0));
        assertEquals("По 5 последних", main(() -> count.getSelectedItem().toString()));
        assertEquals(2L, main(() -> views(management, CheckBox.class).stream().filter(CheckBox::isChecked).count()).longValue());
        click("Загрузить свежие новости");
        await(10000, () -> main(() -> !coordinator.batchRunning()
                && service.notesBook().sourceItems(first.id).stream().filter(i -> i.latestVersionKey != null).count() == 5
                && service.notesBook().sourceItems(second.id).stream().filter(i -> i.latestVersionKey != null).count() == 5));
        assertEquals(4, requests.get());
        assertTrue(main(() -> service.notesBook().materials().isEmpty()));
        assertPaused();
        main(() -> { for (CheckBox box : views(management, CheckBox.class)) if (second.name.equals(box.getText().toString())) { box.setChecked(false); break; } return null; });
        assertEquals(Collections.singleton(first.id), context.getSharedPreferences(UiStyle.PREFS, Context.MODE_PRIVATE).getStringSet("batch_source_ids", Collections.emptySet()));
        click("К новостям · 10 загружено");
        awaitBulk(10);
        int networkBefore = requests.get();
        setNewsFilter(1); awaitBulk(5);
        click("Добавить в эфир (5)");
        assertVisible("Полных: 0 · с ограничениями: 5");
        click("Отмена");
        assertTrue(main(() -> service.notesBook().materials().isEmpty()));
        assertEquals(networkBefore, requests.get());
        setNewsFilter(0); awaitBulk(10);
        click("Добавить в эфир (10)");
        assertVisible("Полных: 0 · с ограничениями: 10");
        click("Добавить все доступные");
        await(10000, () -> main(() -> !coordinator.batchRunning() && service.notesBook().materials().size() == 10));
        awaitBulk(0);
        assertFalse(main(() -> bulkButton().isEnabled()));
        assertEquals(networkBefore, requests.get());
        assertEquals(Collections.singleton(first.id), context.getSharedPreferences(UiStyle.PREFS, Context.MODE_PRIVATE).getStringSet("batch_source_ids", Collections.emptySet()));
        List<Long> sequences = main(() -> { List<Long> values = new ArrayList<>(); for (NoteBook.Material material : service.notesBook().materials()) values.add(material.sequence); return values; });
        tab(2); click("К новостям · 10 загружено"); awaitBulk(0);
        assertEquals(sequences, main(() -> { List<Long> values = new ArrayList<>(); for (NoteBook.Material material : service.notesBook().materials()) values.add(material.sequence); return values; }));
        assertEquals(networkBefore, requests.get()); assertPaused();
    }

    @Test public void opaqueActualButtonStatesAndQueuedEmptyLoadingBulkRemainDistinctAcrossBothSkins() throws Exception {
        SourceRecord source = connect("own_demo_states", "Вымышленная проверка кнопок");
        byte[] emptyBook = main(() -> service.noteStorage().load());
        List<Object> emptyPlayer = playerTuple();
        int emptyRequests = requests.get();
        for (String skin : new String[]{"Нео", "Баббл"}) {
            tab(4); click(skin); tab(2);
            Button active = main(() -> button((View) field(activity, "sourcesPage"), "Загрузить свежие новости"));
            assertTrue(main(active::isEnabled)); int[] normal = readable(active);
            readablePressed(active);
            click("Снять выбор");
            Button disabled = main(() -> button((View) field(activity, "sourcesPage"), "Загрузить свежие новости"));
            assertFalse(main(disabled::isEnabled)); readable(disabled);
            click("Выбрать все");
            Button enabledAgain = main(() -> button((View) field(activity, "sourcesPage"), "Загрузить свежие новости"));
            assertTrue(main(enabledAgain::isEnabled)); assertArrayEquals(normal, readable(enabledAgain));

            // Hold the real coordinator workers so pending local scan is an observable UI state.
            ThreadPoolExecutor workers = main(() -> (ThreadPoolExecutor) field(coordinator, "workers"));
            CountDownLatch entered = new CountDownLatch(workers.getCorePoolSize()), release = new CountDownLatch(1);
            try {
                for (int i = 0; i < workers.getCorePoolSize(); i++) workers.execute(() -> {
                    entered.countDown();
                    try { release.await(15, TimeUnit.SECONDS); } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
                });
                assertTrue("Known workers reached the bounded local-scan hold", entered.await(3, TimeUnit.SECONDS));
                main(() -> { activity.showNewsAll(); return null; });
                awaitBulkCaption("Проверяем загруженные тексты…");
                Button loading = main(this::bulkButton); assertFalse(main(loading::isEnabled)); readable(loading);
            } finally { release.countDown(); }
            awaitBulkCaption("Загрузить из источников");
            Button empty = main(this::bulkButton); assertTrue(main(empty::isEnabled)); readable(empty);
            assertArrayEquals(emptyBook, main(() -> service.noteStorage().load()));
            assertEquals(emptyPlayer, playerTuple()); assertEquals(emptyRequests, requests.get()); assertPaused();
        }

        tab(2); click("Загрузить свежие новости");
        await(10000, () -> main(() -> !coordinator.batchRunning() && service.notesBook().sourceItems(source.id).stream().filter(i -> i.latestVersionKey != null).count() == 5));
        SourceVersionRecord exact = snapshot(source.id).versions.get(0);
        main(() -> { coordinator.enqueue(exact, true); service.command(p -> p.selectStation(source.stationId)); service.command(p -> p.seekToFraction(.4)); return null; });
        NoteBook.Material chosen = main(() -> service.notesBook().material(source.stationId, exact.articleId));
        byte[] readyBook = main(() -> service.noteStorage().load());
        List<Object> readyPlayer = playerTuple(); int savedRequests = requests.get();
        for (String skin : new String[]{"Нео", "Баббл"}) {
            tab(4); click(skin); main(() -> { activity.showNewsAll(); return null; }); awaitBulk(4);
            Button bulk = main(this::bulkButton); assertTrue(main(bulk::isEnabled)); readable(bulk); readablePressed(bulk);
            Button queued = main(() -> button((View) field(activity, "newsPage"), "✓ В эфире"));
            assertNotNull("Current chosen version must have a visible readable queued action", queued);
            assertFalse(main(queued::isEnabled)); readable(queued);
            tab(0);
            if ("Баббл".equals(skin)) assertActualCircleStates();
            else {
                Button play = main(() -> (Button) field(activity, "play")); readable(play);
                main(() -> { assertIconStates(play); return null; });
            }
            assertArrayEquals(readyBook, main(() -> service.noteStorage().load()));
            assertEquals(readyPlayer, playerTuple()); assertEquals(savedRequests, requests.get()); assertPaused();
        }
        main(() -> { activity.showNewsAll(); return null; }); awaitBulk(4);
        click("Добавить в эфир (4)"); click("Отмена");
        assertArrayEquals("Cancel preserves the chosen version and all queue metadata", readyBook, main(() -> service.noteStorage().load()));
        assertEquals(readyPlayer, playerTuple());
        click("Добавить в эфир (4)"); click("Добавить все доступные");
        await(10000, () -> main(() -> !coordinator.batchRunning() && service.notesBook().materials().size() == 5));
        byte[] allBook = main(() -> service.noteStorage().load()); List<Object> allPlayer = playerTuple();
        for (String skin : new String[]{"Нео", "Баббл"}) {
            tab(4); click(skin); main(() -> { activity.showNewsAll(); return null; }); awaitBulk(0);
            Button all = main(this::bulkButton); assertFalse(main(all::isEnabled)); readable(all);
            Button queued = main(() -> button((View) field(activity, "newsPage"), "✓ В эфире"));
            assertNotNull(queued); assertFalse(main(queued::isEnabled)); readable(queued);
            assertArrayEquals(allBook, main(() -> service.noteStorage().load()));
            assertEquals(allPlayer, playerTuple()); assertEquals(savedRequests, requests.get()); assertPaused();
        }
        NoteBook.Material unchanged = main(() -> service.notesBook().material(source.stationId, exact.articleId));
        assertEquals(chosen.sequence, unchanged.sequence); assertEquals(chosen.sourceVersionKey, unchanged.sourceVersionKey); assertEquals(chosen.text, unchanged.text);
    }

    @Test public void attachedReusedNotesAndNavigationKeepUntouchedPaintThroughPollingAndRetheme() throws Exception {
        String id = main(() -> {
            // Metadata-only setup: never press Record or acquire a microphone/STT owner.
            NoteBook book = service.notesBook();
            NoteBook.Note note = book.beginRecording("Повторный стиль", "A", "A1", "v1", 1);
            book.finishRecording(note.id, 0, false, false, null);
            book.edit(note.id, note.title, "Сохранённая ручная версия. Второе предложение.");
            service.addNoteToAir(note.id);
            return note.id;
        });
        tab(2);
        Button sourceAdd = main(() -> button((View) field(activity, "sourcesPage"), "Своя ссылка"));
        waitUntouchedPaint(sourceAdd); waitUntouchedNavigation();
        main(() -> { assertUntouchedPaint(sourceAdd, Color.WHITE, UiStyle.palette(activity).ink); assertUntouchedNavigation(2); return null; });
        Drawable previousSourceBackground = main(sourceAdd::getBackground);
        tab(3);
        Object card = main(() -> ((Map<?, ?>) field(field(activity, "notesPanel"), "views")).get(id));
        Button record = main(() -> (Button) field(field(activity, "notesPanel"), "record"));
        Button linked = main(() -> (Button) field(card, "linkedText"));
        EditText draft = main(() -> (EditText) field(card, "text"));
        main(() -> { draft.setText("Несохранённый черновик остаётся на месте."); return null; });
        waitUntouchedPaint(record); waitUntouchedPaint(linked); waitUntouchedNavigation();
        main(() -> { UiStyle.Palette palette = UiStyle.palette(activity); assertUntouchedPaint(record, palette.brand, Color.WHITE); assertUntouchedPaint(linked, Color.WHITE, palette.ink); assertUntouchedNavigation(3); return null; });
        Drawable previousRecordBackground = main(record::getBackground), previousLinkedBackground = main(linked::getBackground);
        byte[] durable = main(() -> service.noteStorage().load()); List<Object> player = playerTuple();
        NoteBook.Material queued = main(() -> service.notesBook().materials().get(0)); int requestCount = requests.get();

        for (String skin : new String[]{"Баббл", "Нео"}) {
            tab(4); click(skin); tab(1); awaitBulkCaption("Загрузить из источников");
            waitUntouchedNavigation();
            main(() -> { assertUntouchedNavigation(1); return null; });
            tab(2); waitUntouchedPaint(sourceAdd); waitUntouchedNavigation();
            main(() -> { assertSame(sourceAdd, button((View) field(activity, "sourcesPage"), "Своя ссылка")); assertUntouchedPaint(sourceAdd, Color.WHITE, UiStyle.palette(activity).ink); assertUntouchedNavigation(2); return null; });
            Drawable sourceBackground = main(sourceAdd::getBackground);
            assertNotSame("Retheme replaces the reused ordinary control's skin drawable", previousSourceBackground, sourceBackground);
            tab(3); waitUntouchedPaint(record); waitUntouchedPaint(linked); waitUntouchedNavigation();
            main(() -> { assertSame(record, field(field(activity, "notesPanel"), "record")); assertSame(card, ((Map<?, ?>) field(field(activity, "notesPanel"), "views")).get(id)); return null; });
            Drawable recordBackground = main(record::getBackground), linkedBackground = main(linked::getBackground);
            assertNotSame("Retheme replaces the reused primary skin drawable", previousRecordBackground, recordBackground);
            assertNotSame("Retheme replaces the reused ordinary note skin drawable", previousLinkedBackground, linkedBackground);
            Drawable[] navigationBackgrounds = main(() -> { Button[] navigation = (Button[]) field(activity, "navButtons"); Drawable[] backgrounds = new Drawable[navigation.length]; for (int i = 0; i < navigation.length; i++) backgrounds[i] = navigation[i].getBackground(); return backgrounds; });
            long stableUntil = SystemClock.elapsedRealtime() + 2100;
            do {
                main(() -> {
                    UiStyle.Palette palette = UiStyle.palette(activity);
                    assertUntouchedPaint(record, palette.brand, Color.WHITE);
                    assertUntouchedPaint(linked, Color.WHITE, palette.ink);
                    assertUntouchedPaint(sourceAdd, Color.WHITE, palette.ink);
                    assertUntouchedNavigation(3);
                    assertUnchangedBackground(record, recordBackground); assertUnchangedBackground(linked, linkedBackground); assertUnchangedBackground(sourceAdd, sourceBackground);
                    Button[] navigation = (Button[]) field(activity, "navButtons");
                    for (int i = 0; i < navigation.length; i++) assertUnchangedBackground(navigation[i], navigationBackgrounds[i]);
                    return null;
                });
                SystemClock.sleep(200); // Normal NotesPanel's 500ms poll runs; the test never restyles a View.
            } while (SystemClock.elapsedRealtime() < stableUntil);
            assertEquals("Несохранённый черновик остаётся на месте.", main(() -> draft.getText().toString()));
            assertTrue(main(() -> ((Set<?>) field(service.notes(), "dirtyDrafts")).contains(id)));
            assertArrayEquals(durable, main(() -> service.noteStorage().load())); assertEquals(player, playerTuple());
            NoteBook.Material stillQueued = main(() -> service.notesBook().materials().get(0));
            assertEquals(queued.sequence, stillQueued.sequence); assertEquals(queued.version, stillQueued.version); assertEquals(queued.text, stillQueued.text);
            assertEquals(requestCount, requests.get()); assertPaused();
            previousRecordBackground = recordBackground; previousLinkedBackground = linkedBackground; previousSourceBackground = sourceBackground;
        }
    }

    @Test public void sevenActualHitTargetsKeepAbsoluteCoordinatesAcrossContentAndFontSkins() throws Exception {
        Article shortArticle = Article.fromSentences("own-short", "v1", "Коротко", "Первая фраза.", "Вторая фраза.", "Третья фраза.");
        Article longArticle = Article.fromSentences("own-long", "v1", "Намеренно длинный вымышленный заголовок для проверки стабильных координат пульта при переходе между материалами разных размеров", "Длинная вымышленная первая фраза для проверки превью.", "Следующая фраза.");
        main(() -> { service.addSavedMaterial("own-geometry", "Вымышленная станция с длинным читаемым названием для проверки шапки", shortArticle); service.addSavedMaterial("own-geometry", "Вымышленная станция с длинным читаемым названием для проверки шапки", longArticle); return null; });
        String noteId = ownQueuedNote("Собственная заметка", "Короткая ручная заметка. Второе предложение.");
        NoteBook.Material note = main(() -> service.notesBook().materials().stream().filter(m -> noteId.equals(m.noteId)).findFirst().get());
        SourceRecord source = connect("own_geometry_external", "Вымышленный внешний источник");
        AtomicReference<SourceCoordinator.DownloadProgress> download = new AtomicReference<>();
        main(() -> { coordinator.downloadBatch(Collections.singleton(source.id), 5, download::set); return null; });
        await(10000, () -> download.get() != null && download.get().finished); assertEquals(5, download.get().saved);
        SourceVersionRecord external = snapshot(source.id).versions.get(0); main(() -> { coordinator.enqueue(external, true); return null; });
        SourceRecord empty = connect("own_geometry_empty", "Вымышленный пустой источник");
        main(() -> {
            Article kept = Article.fromSentences("boundary-kept", "v1", "Оставшийся текст", "Сохранённая фраза.");
            Article removed = Article.fromSentences("boundary-removed", "v1", "Последний текст", "Последняя фраза.");
            service.addSavedMaterial("own-boundary", "Вымышленная граница", kept); service.addSavedMaterial("own-boundary", "Вымышленная граница", removed);
            service.command(p -> p.selectArticle("own-boundary", removed.id));
            NoteBook.Material retired = service.notesBook().removeSavedMaterial("own-boundary", removed.id);
            service.command(p -> p.removeMaterial(retired.stationId, retired.articleId, retired.boundaryMarker()));
            service.command(p -> p.selectArticle("own-geometry", shortArticle.id)); service.command(p -> p.seekToFraction(.5));
            return null;
        });
        Map<String,List<Object>> anchors = savedAnchors();
        Configuration original = main(() -> new Configuration(activity.getResources().getConfiguration()));
        DisplayMetrics originalMetrics = main(() -> { DisplayMetrics m = new DisplayMetrics(); m.setTo(activity.getResources().getDisplayMetrics()); return m; });
        Rect window = main(() -> { Rect r = new Rect(); activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(r); return r; });
        String[][] scenes = {{"own-geometry", shortArticle.id}, {"own-geometry", longArticle.id}, {note.stationId, note.articleId}, {source.stationId, external.articleId}, {"own-boundary", null}, {empty.stationId, null}};
        try {
            for (float scale : new float[]{1f, 1.3f}) for (String skin : new String[]{"Нео", "Баббл"}) {
                temporaryFont(scale, original, originalMetrics); tab(4); click(skin); tab(0);
                Map<String,Rect> reference = null;
                for (String[] scene : scenes) {
                    main(() -> { if (scene[1] == null) service.command(p -> p.selectStation(scene[0])); else service.command(p -> p.selectArticle(scene[0], scene[1])); return null; });
                    Map<String,Rect> actual = actualControlHitTargets(window);
                    if (reference == null) reference = actual; else assertEquals("Absolute hit targets must stay fixed for " + skin + " font=" + scale + " station=" + scene[0], reference, actual);
                    assertEquals("UI/content selection preserves every saved sentence anchor", anchors, savedAnchors()); assertPaused();
                }
                assertTrue(main(() -> service.notesBook().source(empty.id).enabled));
                assertEquals("A filled boundary is not an empty station", 1L, main(() -> service.player().stations().stream().filter(s -> s.id.equals("own-boundary")).findFirst().get().articles.size()).longValue());
            }
        } finally {
            main(() -> { activity.getResources().updateConfiguration(original, originalMetrics); return null; });
        }
        assertEquals("Короткая ручная заметка. Второе предложение.", main(() -> service.notesBook().find(noteId).text));
        assertEquals(note.version, main(() -> service.notesBook().materials().stream().filter(m -> noteId.equals(m.noteId)).findFirst().get().version));
    }

    @Test public void phoneControlsAndNavigationStayFixedFromTrulyEmptyAirToSelectedNoteAndClear() throws Exception {
        assertTrue(main(()->service.notesBook().materials().isEmpty()));
        for(String skin:new String[]{"Нео","Баббл"}){
            tab(4);click(skin);tab(0);Rect window=main(()->{Rect r=new Rect();activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(r);return r;});
            Map<String,Rect> empty=actualControlHitTargets(window);Object controls=main(()->field(activity,"phoneControls"));int navHeight=main(()->((View)field(activity,"phoneNavigation")).getHeight());
            String id=ownQueuedNote("Вымышленный профиль телефона","Собственный короткий текст без микрофона.");NoteBook.Material material=main(()->queuedNote(id));main(()->{service.command(p->p.selectArticle(material.stationId,material.articleId));return null;});
            assertEquals(empty,actualControlHitTargets(window));assertSame(controls,main(()->field(activity,"phoneControls")));
            for(int page=0;page<5;page++){tab(page);assertEquals(navHeight,main(()->((View)field(activity,"phoneNavigation")).getHeight()).intValue());}tab(0);assertEquals(empty,actualControlHitTargets(window));
            main(()->{service.clearAir();return null;});assertTrue(main(()->service.notesBook().materials().isEmpty()));assertNotNull(main(()->service.notesBook().find(id)));assertEquals(empty,actualControlHitTargets(window));assertSame(controls,main(()->field(activity,"phoneControls")));assertPaused();
        }
    }

    @Test public void normalAirSkipsOnlyEmptyStationsAndKeepsNewNoteBoundaryExplicitlySelectable() throws Exception {
        Article anchor = Article.fromSentences("chooser-anchor", "v1", "Вымышленный первый текст", "Первая фраза.", "Вторая фраза.", "Третья фраза.");
        main(() -> { service.addSavedMaterial("own-chooser-first", "Первая вымышленная станция", anchor); return null; });
        String old = ownQueuedNote("Прежняя заметка", "Прежний ручной текст.");
        main(() -> { service.command(p -> p.selectStation(NoteBook.MY_TEXTS)); service.removeNoteFromAir(old); return null; });
        String newest = ownQueuedNote("Новая заметка на границе", "Новая ручная версия. Второе предложение.");
        SourceRecord empty = connect("own_chooser_empty", "Пустой источник остаётся в Источниках");
        Article last = Article.fromSentences("chooser-last", "v1", "Вымышленный последний текст", "Последняя первая фраза.", "Последняя вторая фраза.");
        main(() -> { service.addSavedMaterial("own-chooser-last", "Последняя вымышленная станция", last); service.command(p -> p.selectArticle("own-chooser-first", anchor.id)); service.command(p -> p.seekToFraction(.5)); return null; });
        Map<String,List<Object>> positions = savedAnchors(); byte[] book = main(() -> service.noteStorage().load());
        tab(0); main(() -> { ((Button) field(activity, "nextStation")).performClick(); return null; });
        assertEquals(NoteBook.MY_TEXTS, main(() -> service.player().snapshot().station.id));
        assertNull("Station switching preserves the explicit append boundary", main(() -> service.player().snapshot().article));
        assertEquals(1, main(() -> service.player().snapshot().station.articles.size()).intValue());
        assertTrue("A filled boundary keeps explicit Play available", main(() -> ((Button) field(activity, "play")).isEnabled()));
        assertFalse(main(() -> ((TextView) field(activity, "title")).getText().toString().contains("пока пуст"))); assertPaused();
        main(() -> { ((Button) field(activity, "nextStation")).performClick(); return null; });
        assertEquals("Next skips the registered empty source", "own-chooser-last", main(() -> service.player().snapshot().station.id));
        main(() -> { ((Button) field(activity, "previousStation")).performClick(); return null; });
        assertEquals(NoteBook.MY_TEXTS, main(() -> service.player().snapshot().station.id)); assertNull(main(() -> service.player().snapshot().article));
        openActualAir(); assertVisible("Мои тексты"); assertNoVisibleStation(empty.name); assertActualChooserControl(); click("Выбрать станцию");
        assertVisible("Мои тексты"); assertNoVisibleStation(empty.name); assertVisible("Последняя вымышленная станция");
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); instrumentation.waitForIdleSync();
        openActualAir(); tapActualTextPrefix("Новая заметка на границе");
        NoteBook.Material chosen = main(() -> service.notesBook().materials().stream().filter(m -> newest.equals(m.noteId)).findFirst().get());
        assertEquals(chosen.articleId, main(() -> service.player().snapshot().article.id));
        assertEquals(chosen.version, main(() -> service.player().snapshot().article.version));
        assertEquals(positions, savedAnchors()); assertArrayEquals(book, main(() -> service.noteStorage().load()));
        assertTrue(main(() -> service.notesBook().source(empty.id).enabled)); assertPaused();
    }

    @Test public void actualGlobalActionsCancelClearWithoutCasAndDeleteAcrossFilterKeepExactNoteContext() throws Exception {
        SourceRecord first=connect("own_global_first","Первый вымышленный источник"),second=connect("own_global_second","Второй вымышленный источник");
        AtomicReference<SourceCoordinator.DownloadProgress> downloaded=new AtomicReference<>();
        main(()->{coordinator.downloadBatch(Arrays.asList(first.id,second.id),5,downloaded::set);return null;});
        await(10000,()->downloaded.get()!=null&&downloaded.get().finished);assertEquals(10,downloaded.get().saved);
        SourceVersionRecord a=snapshot(first.id).versions.get(0),b=snapshot(second.id).versions.get(0);
        main(()->{coordinator.enqueue(a,true);coordinator.enqueue(b,true);service.command(p->p.selectArticle(first.stationId,a.articleId));service.command(p->p.seekToFraction(.4));return null;});
        String id=ownWaveNote("Связанная вымышленная заметка","Ручной текст сохранится.",a);
        List<Object> noteBefore=noteFields(id);String waveBefore=waveHash(id);int network=requests.get();
        SourceBodyCache cache=main(()->(SourceBodyCache)field(coordinator,"cache"));
        java.lang.reflect.Method cacheFile=SourceBodyCache.class.getDeclaredMethod("file",SourceVersionRecord.class);cacheFile.setAccessible(true);
        File lost=(File)cacheFile.invoke(cache,a);assertTrue("Own selected cache fixture existed",lost.isFile());java.nio.file.Files.delete(lost.toPath());
        assertNull("Regression actually lacks the selected CAS body",cache.get(a.withPayload(null)));
        assertEquals(a.spokenText,main(()->service.notesBook().sourceVersion(a.recordKey).spokenText));
        byte[] before=main(()->service.noteStorage().load());List<Object> playerBefore=playerTuple();
        tab(0);openActualAir();click("Очистить эфир");assertVisible("Из всех станций, включая заметки");click("Отмена");
        assertArrayEquals(before,main(()->service.noteStorage().load()));assertEquals(playerBefore,playerTuple());assertNull(cache.get(a.withPayload(null)));
        openActualAir();click("Очистить эфир");click("Очистить эфир");
        await(5000,()->main(()->service.notesBook().materials().isEmpty()));assertPaused();
        assertEquals(noteBefore,noteFields(id));assertEquals(waveBefore,waveHash(id));
        assertEquals(a.spokenText,cache.get(a.withPayload(null)).spokenText);assertEquals(10,snapshot(null).downloaded.size());assertEquals(network,requests.get());
        click("Закрыть");tab(1);setNewsFilter(1);awaitBulk(5);click("Читать");assertVisible(a.body());
        click("В эфир: текст поста");click("Сохранить доступный текст");
        await(5000,()->main(()->service.notesBook().material(first.stationId,a.articleId)!=null));click("Закрыть");
        tab(3);click("Добавить в эфир");await(5000,()->main(()->queuedNote(id)!=null));
        tab(1);setNewsFilter(1);await(5000,()->main(()->!(boolean)field(field(activity,"newsPage"),"loading")));
        before=main(()->service.noteStorage().load());click("Удалить новости всех источников");assertVisible("Из всех источников: 10");click("Отмена");
        assertArrayEquals(before,main(()->service.noteStorage().load()));assertEquals(5,main(()->service.notesBook().sourceItems(first.id).size()).intValue());assertEquals(5,main(()->service.notesBook().sourceItems(second.id).size()).intValue());
        click("Удалить новости всех источников");
        main(()->{service.notes().edit(id,"Связанная вымышленная заметка","Новая ручная правка пока открыт старый диалог.");return null;});
        byte[] afterEdit=main(()->service.noteStorage().load());click("Удалить новости всех источников");
        assertArrayEquals("Stale global confirmation must not delete after another durable edit",afterEdit,main(()->service.noteStorage().load()));
        assertEquals(5,main(()->service.notesBook().sourceItems(second.id).size()).intValue());
        List<Object> currentNote=noteFields(id);NoteBook.Material selectedNote=main(()->queuedNote(id));
        click("Удалить новости всех источников");click("Удалить все новости");
        await(5000,()->main(()->service.notesBook().sourceItems(first.id).isEmpty()&&service.notesBook().sourceItems(second.id).isEmpty()));
        assertTrue(main(()->service.notesBook().source(first.id).enabled));assertTrue(main(()->service.notesBook().source(second.id).enabled));
        assertEquals(currentNote,noteFields(id));assertEquals(waveBefore,waveHash(id));assertEquals(selectedNote.version,main(()->queuedNote(id).version));assertEquals(selectedNote.sequence,main(()->queuedNote(id).sequence).longValue());assertEquals(1,main(()->service.notesBook().materials().size()).intValue());
        assertTrue(main(()->service.notesBook().isItemDeleted(first.id,a.itemKey)));assertTrue(main(()->service.notesBook().isItemDeleted(second.id,b.itemKey)));
        main(()->{assertThrows(IllegalArgumentException.class,()->coordinator.loadText(first.id,a.itemKey,r->fail("Deleted news cannot become an ordinary reader")));return null;});
        tab(3);click("Точный текст связанной новости");assertVisible(a.body());assertVisible("контекст заметки");click("Закрыть");
        AtomicReference<SourceCoordinator.Result<SourceRecord>> refreshed=new AtomicReference<>();main(()->{coordinator.refresh(first.id,refreshed::set);return null;});await(10000,()->refreshed.get()!=null);assertTrue(refreshed.get().successful());assertTrue(main(()->service.notesBook().sourceItems(first.id).isEmpty()));assertPaused();
    }

    @Test public void actualDirtyUpdateOtherAndCurrentThenMyAirRemovePreserveWaveAndLiveRequest() throws Exception {
        String other=ownWaveNote("Вымышленная другая заметка","Прежний текст другой заметки.",null);
        main(()->{service.notes().edit(other,"Вымышленная другая заметка","Новая сохранённая версия другой заметки.");return null;});
        tab(3);Object otherCard=noteCard(other);EditText editor=main(()->(EditText)field(otherCard,"text"));
        main(()->{editor.setText("Несохранённая ручная правка останется в редакторе.");return null;});
        byte[] before=main(()->service.noteStorage().load());NoteBook.Material oldOther=main(()->queuedNote(other));
        click("Обновить в эфире");assertArrayEquals(before,main(()->service.noteStorage().load()));assertEquals(oldOther.version,main(()->queuedNote(other).version));assertEquals("Несохранённая ручная правка останется в редакторе.",main(()->editor.getText().toString()));
        click("Сохранить изменения");
        String current=ownWaveNote("Вымышленная текущая заметка",longNoteBody("Прежняя текущая редакция"),null);
        NoteBook.Material currentBeforeRestart=main(()->queuedNote(current));
        assertTrue("Regression must update a previously selected non-last Note",oldOther.sequence<currentBeforeRestart.sequence);
        List<Object> otherFields=noteFields(other),currentFields=noteFields(current);String otherWave=waveHash(other),currentWave=waveHash(current);
        main(()->{NoteBook.Material material=queuedNote(current);service.command(p->p.selectArticle(material.stationId,material.articleId));service.requestPlay();return null;});awaitActualReading();
        String request=main(()->service.player().snapshot().currentRequestId);Object token=main(()->((android.media.session.MediaSession)field(service,"session")).getSessionToken());
        click("Обновить в эфире");
        assertLiveRequest(request,token,1500);NoteBook.Material updatedOther=main(()->queuedNote(other));assertNotEquals(oldOther.version,updatedOther.version);assertTrue(updatedOther.sequence>oldOther.sequence);assertEquals(otherFields,noteFields(other));assertEquals(otherWave,waveHash(other));
        assertFalse(main(()->service.updateNoteInAir(other,service.notesBook().find(other).revision)));assertEquals(updatedOther.sequence,main(()->queuedNote(other).sequence).longValue());
        RadioService previousService=service;RadioActivity previousActivity=activity;String previousRequest=request;
        // Drain actual owners, then reload the same files; no fixture restore/reset occurs here.
        fixtureSupport.stopAndDrain(()->{scenario.close();scenario=null;});
        byte[] persistedAfterShutdown=main(()->previousService.noteStorage().load());
        assertTrue(main(()->(boolean)field(previousService,"destroyed")));
        scenario=ActivityScenario.launch(RadioActivity.class);bindActivity();
        assertNotSame("Activity must really restart",previousActivity,activity);assertNotSame("Service must really restart",previousService,service);
        fixtureSupport.track(main(service::sources));
        assertNull("The replacement must decode from durable storage",main(service::notesProblem));assertNotNull(main(service::notesBook));
        assertArrayEquals("Relaunch must retain the exact committed Book bytes",persistedAfterShutdown,main(()->service.noteStorage().load()));
        assertEquals(otherFields,noteFields(other));assertEquals(currentFields,noteFields(current));assertEquals(otherWave,waveHash(other));assertEquals(currentWave,waveHash(current));
        NoteBook.Material restoredOther=main(()->queuedNote(other)),restoredCurrent=main(()->queuedNote(current));
        assertNotNull(restoredOther);assertNotNull(restoredCurrent);assertEquals(2,main(()->service.notesBook().materials().size()).intValue());
        assertEquals(updatedOther.sequence,restoredOther.sequence);assertEquals(updatedOther.articleId,restoredOther.articleId);assertEquals(updatedOther.version,restoredOther.version);assertEquals(updatedOther.text,restoredOther.text);
        assertEquals(currentBeforeRestart.sequence,restoredCurrent.sequence);assertEquals(currentBeforeRestart.articleId,restoredCurrent.articleId);assertEquals(currentBeforeRestart.version,restoredCurrent.version);assertEquals(currentBeforeRestart.text,restoredCurrent.text);
        assertEquals(restoredCurrent.articleId,main(()->service.player().snapshot().article.id));assertPaused();
        assertFalse(main(()->service.updateNoteInAir(other,service.notesBook().find(other).revision)));assertEquals(restoredOther.sequence,main(()->queuedNote(other).sequence).longValue());
        tab(3);main(()->{service.requestPlay();return null;});awaitActualReading();request=main(()->service.player().snapshot().currentRequestId);token=main(()->((android.media.session.MediaSession)field(service,"session")).getSessionToken());
        assertNotEquals("A new explicit Play must create a fresh actual TTS request",previousRequest,request);assertLiveRequest(request,token,1500);
        Object currentCard=noteCard(current);main(()->{((EditText)field(currentCard,"text")).setText(longNoteBody("Новая текущая редакция"));return null;});
        // A specific actual save control is brought into view; no hidden mutation callback is invoked.
        clickActualButton(main(()->(Button)field(currentCard,"save")));NoteBook.Material oldCurrent=main(()->queuedNote(current));
        click("Обновить в эфире");await(5000,()->main(()->!service.player().snapshot().playing&&service.player().snapshot().currentRequestId==null));
        NoteBook.Material updatedCurrent=main(()->queuedNote(current));assertNotEquals(oldCurrent.articleId,updatedCurrent.articleId);assertNotEquals(oldCurrent.version,updatedCurrent.version);assertTrue(updatedCurrent.sequence>oldCurrent.sequence);assertEquals(updatedCurrent.articleId,main(()->service.player().snapshot().article.id));assertEquals(0,main(()->service.player().snapshot().sentenceIndex).intValue());assertEquals(currentWave,waveHash(current));assertEquals(currentFields.get(7),noteFields(current).get(7));assertPaused();
        main(()->{service.requestPlay();return null;});awaitActualReading();request=main(()->service.player().snapshot().currentRequestId);token=main(()->((android.media.session.MediaSession)field(service,"session")).getSessionToken());
        tab(0);openActualAir();clickDescription("Убрать из эфира: Вымышленная другая заметка");assertLiveRequest(request,token,1500);assertNull(main(()->queuedNote(other)));assertEquals(otherFields,noteFields(other));assertEquals(otherWave,waveHash(other));
        clickDescription("Убрать из эфира: Вымышленная текущая заметка");await(5000,()->main(()->service.notesBook().materials().isEmpty()));assertPaused();assertNotNull(main(()->service.notesBook().find(current)));assertEquals(currentWave,waveHash(current));assertEquals(currentFields.get(7),noteFields(current).get(7));
    }
    @Test public void actualDeleteNoteCancelStaleOtherContinuityCurrentPauseAndRestartWithoutResurrection() throws Exception {
        SourceRecord source=connect("own_note_delete","Вымышленный источник контекста удаления");
        AtomicReference<SourceCoordinator.DownloadProgress> downloaded=new AtomicReference<>();
        main(()->{coordinator.downloadBatch(Collections.singleton(source.id),5,downloaded::set);return null;});
        await(10000,()->downloaded.get()!=null&&downloaded.get().finished);assertEquals(5,downloaded.get().saved);
        SourceVersionRecord linked=snapshot(source.id).versions.get(0);main(()->{coordinator.enqueue(linked,true);return null;});
        String survivor=ownWaveNote("Сохранить связанную заметку",longNoteBody("Действующая заметка А"),linked);
        String other=ownWaveNote("Удалить другую выбранную заметку",longNoteBody("Другая выбранная заметка"),null);
        String unselected=ownWaveNote("Удалить заметку вне эфира","Сохранённый текст удаляемой заметки вне эфира.",null);
        String current=ownWaveNote("Удалить текущую заметку",longNoteBody("Текущая удаляемая заметка"),null);
        main(()->{service.removeNoteFromAir(unselected);return null;});
        File directory=main(()->service.noteStorage().directory);
        List<Object> survivorFields=noteFields(survivor);String survivorWave=waveHash(survivor),otherWave=waveHash(other),unselectedWave=waveHash(unselected);
        assertEquals(linked.recordKey,main(()->service.notesBook().find(survivor).linkedSourceVersionKey));
        tab(3);byte[] cancelBook=main(()->service.noteStorage().load());
        clickNoteDelete(other);assertVisible("оригинальная запись и выбранная версия");click("Отмена");
        assertArrayEquals(cancelBook,main(()->service.noteStorage().load()));assertNotNull(main(()->queuedNote(other)));assertEquals(otherWave,waveHash(other));
        Object unselectedCard=noteCard(unselected);EditText draft=main(()->(EditText)field(unselectedCard,"text"));
        String draftText="Несохранённая правка будет отброшена только после подтверждения удаления.";
        main(()->{draft.setText(draftText);return null;});
        clickNoteDelete(unselected);assertVisible("Несохранённая ручная правка также будет удалена");click("Отмена");
        assertArrayEquals(cancelBook,main(()->service.noteStorage().load()));assertEquals(draftText,main(()->draft.getText().toString()));assertEquals(unselectedWave,waveHash(unselected));
        // A changed Book with an unchanged target Note also invalidates the old confirmation.
        clickNoteDelete(other);
        main(()->{service.notes().edit(unselected,"Удалить заметку вне эфира","Новая сохранённая версия вне эфира.");return null;});
        byte[] afterOtherEdit=main(()->service.noteStorage().load());click("Удалить заметку");
        assertArrayEquals(afterOtherEdit,main(()->service.noteStorage().load()));assertNotNull(main(()->service.notesBook().find(other)));assertEquals(otherWave,waveHash(other));
        clickNoteDelete(other);
        main(()->{service.notes().edit(other,"Удалить другую выбранную заметку",longNoteBody("Новая сохранённая другая редакция"));return null;});
        byte[] afterTargetEdit=main(()->service.noteStorage().load());click("Удалить заметку");
        assertArrayEquals(afterTargetEdit,main(()->service.noteStorage().load()));assertNotNull(main(()->queuedNote(other)));assertEquals(otherWave,waveHash(other));
        main(()->{NoteBook.Material material=queuedNote(survivor);service.command(p->p.selectArticle(material.stationId,material.articleId));service.requestPlay();return null;});awaitActualReading();
        String request=main(()->service.player().snapshot().currentRequestId);Object token=main(()->((android.media.session.MediaSession)field(service,"session")).getSessionToken());
        clickNoteDelete(unselected);assertVisible("Несохранённая ручная правка также будет удалена");click("Удалить заметку");
        await(5000,()->main(()->service.notesBook().find(unselected)==null));assertNoteFilesAbsent(directory,unselected);assertNull(main(()->queuedNote(unselected)));assertLiveRequest(request,token,1500);
        clickNoteDelete(other);click("Удалить заметку");
        await(5000,()->main(()->service.notesBook().find(other)==null));assertNoteFilesAbsent(directory,other);assertNull(main(()->queuedNote(other)));assertLiveRequest(request,token,1500);
        assertEquals(survivorFields,noteFields(survivor));assertEquals(survivorWave,waveHash(survivor));
        main(()->{NoteBook.Material material=queuedNote(current);service.command(p->p.selectArticle(material.stationId,material.articleId));service.requestPlay();return null;});awaitActualReading();
        clickNoteDelete(current);click("Удалить заметку");
        await(5000,()->main(()->service.notesBook().find(current)==null));assertNoteFilesAbsent(directory,current);assertNull(main(()->queuedNote(current)));assertPaused();
        long silentUntil=SystemClock.elapsedRealtime()+1500;do{assertPaused();SystemClock.sleep(50);}while(SystemClock.elapsedRealtime()<silentUntil);
        RadioService previousService=service;RadioActivity previousActivity=activity;
        fixtureSupport.stopAndDrain(()->{scenario.close();scenario=null;});
        byte[] committed=main(()->previousService.noteStorage().load());
        scenario=ActivityScenario.launch(RadioActivity.class);bindActivity();fixtureSupport.track(main(service::sources));
        assertNotSame(previousActivity,activity);assertNotSame(previousService,service);assertTrue(main(()->(boolean)field(previousService,"destroyed")));assertNull(main(service::notesProblem));
        assertArrayEquals("Same durable files must reload without resurrecting a deleted Note",committed,main(()->service.noteStorage().load()));
        for(String id:Arrays.asList(unselected,other,current)){assertNull(main(()->service.notesBook().find(id)));assertNull(main(()->queuedNote(id)));assertNoteFilesAbsent(directory,id);}
        assertEquals(1,main(()->service.notesBook().notes().size()).intValue());assertEquals(survivorFields,noteFields(survivor));assertEquals(survivorWave,waveHash(survivor));
        assertNotNull(main(()->queuedNote(survivor)));assertEquals(linked.recordKey,main(()->service.notesBook().find(survivor).linkedSourceVersionKey));
        assertEquals(5,main(()->service.notesBook().sourceItems(source.id).size()).intValue());assertEquals(source.name,main(()->service.notesBook().source(source.id).name));assertEquals(linked.spokenText,main(()->service.notesBook().sourceVersion(linked.recordKey).spokenText));assertPaused();
        main(()->{NoteBook.Material material=queuedNote(survivor);service.command(p->p.selectArticle(material.stationId,material.articleId));service.requestPlay();return null;});awaitActualReading();
        assertLiveRequest(main(()->service.player().snapshot().currentRequestId),main(()->((android.media.session.MediaSession)field(service,"session")).getSessionToken()),1500);
    }
    private void clickNoteDelete(String id){Object card=noteCard(id);String description=main(()->{Button button=(Button)field(card,"delete");assertTrue(button.isShown()&&button.isEnabled());button.requestRectangleOnScreen(new Rect(0,0,button.getWidth(),button.getHeight()),true);return button.getContentDescription().toString();});instrumentation.waitForIdleSync();clickDescription(description);}
    private static void assertNoteFilesAbsent(File directory,String id){for(String suffix:Arrays.asList(".wav",".wav.part",".wav.delete-pending",".wav.part.delete-pending"))assertFalse("Deleted Note file survived: "+suffix,new File(directory,id+suffix).exists());}
    private static String longNoteBody(String prefix){return prefix+" "+("Это полностью вымышленное продолжительное предложение для проверки сохранности действующей сессии и отсутствия наложенного воспроизведения ").repeat(20)+".";}
    private String ownWaveNote(String title,String body,SourceVersionRecord link){return main(()->{
        NoteBook.Note note=service.notesBook().beginRecording(title,link==null?null:service.notesBook().source(link.sourceId).stationId,link==null?null:link.articleId,link==null?null:link.versionKey,1);
        File directory=service.noteStorage().directory;try(ru.bububu.radio.core.PcmWave.Writer writer=new ru.bububu.radio.core.PcmWave.Writer(new File(directory,note.id+".wav.part"))){writer.append(new byte[16000],0,16000);writer.finish(new File(directory,note.id+".wav"));}
        service.notesBook().finishRecording(note.id,500,true,false,"Синтетический WAV без микрофона");service.notesBook().edit(note.id,title,body);service.notesBook().setOriginalPosition(note.id,200);service.addNoteToAir(note.id);return note.id;
    });}
    private NoteBook.Material queuedNote(String id){for(NoteBook.Material material:service.notesBook().materials())if(id.equals(material.noteId))return material;return null;}
    private List<Object> noteFields(String id){return main(()->{NoteBook.Note n=service.notesBook().find(id);return Arrays.asList(n.id,n.title,n.text,n.revision,n.linkedStation,n.linkedArticle,n.linkedVersion,n.positionMs,n.durationMs,n.hasAudio,n.linkedSourceVersionKey,n.recordingStatus,n.recognitionStatus);});}
    private String waveHash(String id)throws Exception{File file=main(()->new File(service.noteStorage().directory,id+".wav"));byte[] bytes=java.nio.file.Files.readAllBytes(file.toPath());StringBuilder hex=new StringBuilder();for(byte value:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))hex.append(String.format(Locale.ROOT,"%02x",value&255));return hex.toString();}
    private Object noteCard(String id){await(5000,()->main(()->((Map<?,?>)field(field(activity,"notesPanel"),"views")).containsKey(id)));return main(()->((Map<?,?>)field(field(activity,"notesPanel"),"views")).get(id));}
    private void awaitActualReading(){await(25000,()->main(()->{PlayerController.Snapshot snapshot=service.player().snapshot();assertNull("Actual TTS/focus failure is not a pass",snapshot.error);return snapshot.playing&&snapshot.currentRequestId!=null&&"Читаем на телефоне".equals(snapshot.status);}));}
    private void assertLiveRequest(String request,Object token,long duration){long end=SystemClock.elapsedRealtime()+duration;do{main(()->{PlayerController.Snapshot snapshot=service.player().snapshot();assertTrue(snapshot.playing);assertEquals(request,snapshot.currentRequestId);assertEquals(token,((android.media.session.MediaSession)field(service,"session")).getSessionToken());return null;});SystemClock.sleep(50);}while(SystemClock.elapsedRealtime()<end);}
    private void clickActualButton(Button button){main(()->{assertTrue(button.isShown()&&button.isEnabled());View root=button;while(root.getParent() instanceof View)root=(View)root.getParent();button.requestRectangleOnScreen(new Rect(0,0,button.getWidth(),button.getHeight()),true);return null;});instrumentation.waitForIdleSync();click(button.getText().toString());}
    private void clickDescription(String description){long until=SystemClock.elapsedRealtime()+8000;while(SystemClock.elapsedRealtime()<until){instrumentation.waitForIdleSync();AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();boolean clicked=false;if(root!=null)try{for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(description)){if(!clicked&&description.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())&&node.isVisibleToUser()&&node.isEnabled()&&node.isClickable())clicked=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);node.recycle();}if(!clicked)scrollNodeForward(root);}finally{root.recycle();}if(clicked){instrumentation.waitForIdleSync();return;}SystemClock.sleep(100);}fail("Actual described action unavailable: "+description);}
    private static boolean scrollNodeForward(AccessibilityNodeInfo node){if(node.isScrollable()&&node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;try{if(scrollNodeForward(child))return true;}finally{child.recycle();}}return false;}

    private SourceRecord connect(String id, String name) {
        AtomicReference<SourceCoordinator.Result<SourceCoordinator.Preview>> delivered = new AtomicReference<>();
        main(() -> { coordinator.preview("@" + id, true, null, delivered::set); return null; });
        await(10000, () -> delivered.get() != null);
        assertTrue(delivered.get().successful());
        return main(() -> coordinator.connect(delivered.get().value, name));
    }
    private SourceCoordinator.BulkSnapshot snapshot(String source) {
        AtomicReference<SourceCoordinator.Result<SourceCoordinator.BulkSnapshot>> delivered = new AtomicReference<>();
        main(() -> { coordinator.prepareBulkSnapshot(source, delivered::set); return null; });
        await(10000, () -> delivered.get() != null); assertTrue(delivered.get().successful()); return delivered.get().value;
    }
    private void bindActivity() { scenario.onActivity(value -> activity = value); await(10000, () -> main(() -> activity.serviceForNotes() != null)); service = main(activity::serviceForNotes); fixtureSupport.track(service); }
    private void tab(int index) { main(() -> { Button button = ((Button[]) field(activity, "navButtons"))[index]; assertTrue(button.isShown()); button.performClick(); return null; }); instrumentation.waitForIdleSync(); }
    private void assertFiveTabs() { main(() -> { Button[] tabs = (Button[]) field(activity, "navButtons"); assertEquals(5, tabs.length); String[] labels = {"Радио", "Новости", "Источники", "Заметки", "Настройки"}; for (int i = 0; i < tabs.length; i++) { assertEquals(labels[i], tabs[i].getText().toString()); assertTrue(tabs[i].isShown()); assertTrue(tabs[i].getWidth() >= UiStyle.dp(activity, 48)); assertTrue(tabs[i].getHeight() >= UiStyle.dp(activity, 48)); if (tabs[i].getLayout() != null) for (int line = 0; line < tabs[i].getLayout().getLineCount(); line++) assertEquals(0, tabs[i].getLayout().getEllipsisCount(line)); } return null; }); }
    private void assertRadioControlsVisible() {
        instrumentation.waitForIdleSync();
        main(() -> {
            if (activity.getResources().getConfiguration().fontScale <= 1.25f) {
                View viewport = ((View[]) field(activity, "pages"))[0];
                Rect visiblePage = new Rect(); assertTrue(viewport.getGlobalVisibleRect(visiblePage));
                assertFullyVisible((Button) field(activity, "play"), visiblePage);
            }
            return null;
        });
        openActualAir(); assertActualChooserControl();
        click("Выбрать станцию"); assertVisible("Мой эфир · порядок станций");
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); instrumentation.waitForIdleSync();
    }
    private static void assertFullyVisible(View control, Rect viewport) {
        Point origin = new Point(); Rect visible = new Rect();
        assertTrue("Radio control must be visible", control.getGlobalVisibleRect(visible, origin));
        Rect whole = new Rect(origin.x, origin.y, origin.x + control.getWidth(), origin.y + control.getHeight());
        assertTrue("Radio control must fit inside the visible page without scrolling", viewport.contains(whole));
        assertEquals("Radio control must not be clipped", whole, visible);
    }
    private void setNewsFilter(int index) { main(() -> { List<Spinner> filters = views((View) field(activity, "newsPage"), Spinner.class); assertEquals(1, filters.size()); filters.get(0).setSelection(index); return null; }); instrumentation.waitForIdleSync(); }
    private Button bulkButton() throws Exception { for (Button button : views((View) field(activity, "newsPage"), Button.class)) if ("Действие для загруженных новостей".contentEquals(button.getContentDescription() == null ? "" : button.getContentDescription())) return button; return null; }
    private void awaitBulk(int count) { String caption = count == 0 ? "Всё добавлено в эфир" : "Добавить в эфир (" + count + ")"; await(10000, () -> main(() -> { Button bulk = bulkButton(); return bulk != null && caption.contentEquals(bulk.getText()) && !(boolean) field(field(activity, "newsPage"), "loading"); })); }
    private void awaitBulkCaption(String caption) { await(10000, () -> main(() -> { Button bulk = bulkButton(); return bulk != null && caption.contentEquals(bulk.getText()); })); }
    private int[] readable(Button button) {
        assertNotNull(button); await(3000, () -> main(() -> button.getWidth() > 0 && button.getHeight() > 0 && !button.isLayoutRequested()));
        return main(() -> assertReadableRendered(button));
    }
    private void readablePressed(Button button) { main(() -> { button.setPressed(true); return null; }); try { readable(button); } finally { main(() -> { button.setPressed(false); return null; }); } }
    private void assertActualCircleStates() {
        await(3000, () -> main(() -> {
            List<Button> circles = actualCircles();
            return circles.size() == 7 && circles.stream().allMatch(button -> button.getWidth() > 0 && button.getHeight() > 0 && !button.isLayoutRequested());
        }));
        main(() -> {
            List<Button> circles = actualCircles();
            assertEquals("Bubble retains the actual seven radio controls", 7, circles.size());
            for (Button circle : circles) assertIconStates(circle);
            return null;
        });
    }
    private static void assertIconStates(Button button) {
        boolean enabled = button.isEnabled();
        try { button.setEnabled(true); assertReadableRendered(button); assertRadioIcon(button); button.setPressed(true); assertReadableRendered(button); assertRadioIcon(button); button.setPressed(false); button.setEnabled(false); assertReadableRendered(button); assertRadioIcon(button); button.setEnabled(true); assertReadableRendered(button); assertRadioIcon(button); }
        finally { button.setPressed(false); button.setEnabled(enabled); }
    }
    private List<Button> actualCircles() throws Exception { List<Button> circles = new ArrayList<>(); for (Button button : views((View) field(activity, "root"), Button.class)) if ("ui-circle".equals(button.getTag()) || "ui-circle-primary".equals(button.getTag())) circles.add(button); return circles; }
    private static int[] assertReadableRendered(Button button) {
        assertEquals("Button view opacity", 1f, button.getAlpha(), 0f);
        int foreground = button.getCurrentTextColor(); assertEquals("Resolved text must be opaque", 255, Color.alpha(foreground));
        int fill = renderedFill(button); assertEquals("Rendered interior fill must be opaque", 255, Color.alpha(fill));
        assertTrue("Actual rendered text/fill contrast must be at least 4.5:1", contrast(foreground, fill) >= 4.5);
        Bitmap rendered = Bitmap.createBitmap(button.getWidth(), button.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            button.draw(new Canvas(rendered)); boolean glyph = false;
            for (int y = 0; y < rendered.getHeight() && !glyph; y++) for (int x = 0; x < rendered.getWidth(); x++) if (rendered.getPixel(x, y) == foreground) { glyph = true; break; }
            assertTrue("Actual Button drawing must contain its resolved opaque text color", glyph);
        } finally { rendered.recycle(); }
        return new int[]{foreground, fill};
    }
    private static int renderedFill(Button button) {
        Drawable background = button.getBackground(); assertNotNull(background);
        background.setState(button.getDrawableState()); background.jumpToCurrentState();
        Rect original = new Rect(background.getBounds()); Bitmap sample = Bitmap.createBitmap(button.getWidth(), button.getHeight(), Bitmap.Config.ARGB_8888);
        try { background.setBounds(0, 0, sample.getWidth(), sample.getHeight()); background.draw(new Canvas(sample)); return sample.getPixel(sample.getWidth() / 2, sample.getHeight() / 2); }
        finally { background.setBounds(original); sample.recycle(); }
    }
    private static void assertRadioIcon(Button button) {
        Drawable icon = button.getCompoundDrawables()[1]; assertNotNull("Actual radio control has a real icon", icon);
        icon.setState(button.getDrawableState()); icon.jumpToCurrentState(); Rect original = new Rect(icon.getBounds());
        Bitmap sample = Bitmap.createBitmap(icon.getIntrinsicWidth(), icon.getIntrinsicHeight(), Bitmap.Config.ARGB_8888); int fill = renderedFill(button);
        try {
            icon.setBounds(0, 0, sample.getWidth(), sample.getHeight()); icon.draw(new Canvas(sample)); int opaque = 0;
            for (int y = 0; y < sample.getHeight(); y++) for (int x = 0; x < sample.getWidth(); x++) { int color = sample.getPixel(x, y); if (Color.alpha(color) == 255) { opaque++; assertTrue("Actual opaque icon strokes must contrast with the button fill", contrast(color, fill) >= 4.5); } }
            assertTrue("An icon must contain opaque stroke pixels", opaque > 0);
        } finally { icon.setBounds(original); sample.recycle(); }
    }
    private static double contrast(int first, int second) { double a = luminance(first), b = luminance(second); return (Math.max(a, b) + .05) / (Math.min(a, b) + .05); }
    private static double luminance(int color) { return .2126 * linear(Color.red(color)) + .7152 * linear(Color.green(color)) + .0722 * linear(Color.blue(color)); }
    private static double linear(int channel) { double value = channel / 255.0; return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4); }
    private String ownQueuedNote(String title, String text) {
        return main(() -> { NoteBook.Note note = service.notesBook().beginRecording(title, null, null, null, 1); service.notesBook().finishRecording(note.id, 0, false, false, null); service.notesBook().edit(note.id, title, text); service.addNoteToAir(note.id); return note.id; });
    }
    @SuppressWarnings("deprecation") private void temporaryFont(float scale, Configuration original, DisplayMetrics metrics) {
        main(() -> { Configuration config = new Configuration(original); config.fontScale = scale; DisplayMetrics display = new DisplayMetrics(); display.setTo(metrics); display.scaledDensity = display.density * scale; activity.getResources().updateConfiguration(config, display); return null; });
    }
    private Map<String,List<Object>> savedAnchors() {
        return main(() -> { Map<String,List<Object>> values = new LinkedHashMap<>(); for (Map.Entry<String,PlayerController.SavedProgress> entry : service.player().savedState().progress.entrySet()) { PlayerController.SavedProgress p = entry.getValue(); values.put(entry.getKey(), Arrays.asList(p.stationId, p.articleId, p.version, p.sentenceIndex, p.passEnded, p.completedSentences, p.read, p.started)); } return values; });
    }
    private Map<String,Rect> actualControlHitTargets(Rect window) {
        String[] fields = {"previousStation", "nextStation", "previous", "play", "next", "text", "queue"};
        await(3000, () -> main(() -> { for (String name : fields) { Button b = (Button) field(activity, name); if (b.getWidth() == 0 || b.getHeight() == 0 || b.isLayoutRequested()) return false; } return true; }));
        return main(() -> {
            Rect currentWindow = new Rect(); activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(currentWindow); assertEquals("Font/skin keeps the physical viewport", window, currentWindow);
            ScrollView page = (ScrollView) ((View[]) field(activity, "pages"))[0]; assertEquals("The comparison never scrolls the radio page", 0, page.getScrollY());
            Rect viewport = new Rect(); assertTrue(page.getGlobalVisibleRect(viewport)); Map<String,Rect> hits = new LinkedHashMap<>();
            int minimum = UiStyle.dp(activity, 48);
            for (String name : fields) {
                Button b = (Button) field(activity, name); assertTrue("Actual control is attached/shown: " + name, b.isAttachedToWindow() && b.isShown());
                int[] screen = new int[2]; b.getLocationOnScreen(screen); Rect whole = new Rect(screen[0], screen[1], screen[0] + b.getWidth(), screen[1] + b.getHeight());
                assertTrue("Hit target stays at least 48dp: " + name, whole.width() >= minimum && whole.height() >= minimum);
                assertFullyVisible(b, viewport); assertNotNull(b.getLayout());assertEquals("Icon/text group remains centred",android.view.Gravity.CENTER,b.getGravity());
                assertTrue("Control text height is not clipped: " + name, b.getLayout().getHeight() <= b.getHeight() - b.getCompoundPaddingTop() - b.getCompoundPaddingBottom());
                for (int line = 0; line < b.getLayout().getLineCount(); line++) { assertEquals("Control label is not ellipsized: " + name, 0, b.getLayout().getEllipsisCount(line)); assertTrue("Control label width is not clipped: " + name, b.getLayout().getLineWidth(line) <= b.getWidth() - b.getCompoundPaddingLeft() - b.getCompoundPaddingRight() + 1); }
                Rect localHit = new Rect(); b.getHitRect(localHit); int[] parent = new int[2]; ((View) b.getParent()).getLocationOnScreen(parent); localHit.offset(parent[0], parent[1]); assertEquals("Measured rectangle is the actual hit target", whole, localHit);
                hits.put(name, whole);
            }
            assertTrue("Station pair is above article transport in both skins",hits.get("nextStation").bottom<=hits.get("previous").top);
            TextView header=(TextView)field(activity,"station");assertEquals(android.view.Gravity.CENTER,header.getGravity());assertEquals(2,header.getMaxLines());
            return hits;
        });
    }
    private void openActualAir() { main(() -> { Button air = (Button) field(activity, "queue"); assertTrue(air.isShown()); air.performClick(); return null; }); instrumentation.waitForIdleSync(); }
    private void assertActualChooserControl() {
        await(3000, () -> main(() -> { Button chooser = (Button) field(activity, "stations"); return chooser != null && chooser.isAttachedToWindow() && chooser.getWidth() > 0 && !chooser.isLayoutRequested(); }));
        main(() -> {
            Button chooser = (Button) field(activity, "stations"); assertEquals("station-chooser", chooser.getTag()); assertEquals("Выбрать станцию", chooser.getText().toString());
            assertTrue("Actual My Air chooser stays visible/enabled", chooser.isShown() && chooser.isEnabled());
            Rect viewport = new Rect(); assertTrue(chooser.getRootView().getGlobalVisibleRect(viewport)); assertFullyVisible(chooser, viewport);
            assertTrue(chooser.getWidth() >= UiStyle.dp(activity, 48)); assertTrue(chooser.getHeight() >= UiStyle.dp(activity, 48)); assertNotNull(chooser.getLayout());
            for (int line = 0; line < chooser.getLayout().getLineCount(); line++) assertEquals("Chooser label is not ellipsized", 0, chooser.getLayout().getEllipsisCount(line));
            return null;
        });
    }
    private void assertNoVisibleStation(String text) {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow(); assertNotNull(root); boolean found = false;
        try { for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) { found |= node.isVisibleToUser() && (node.getText() == null ? "" : node.getText().toString()).contains(text); node.recycle(); } } finally { root.recycle(); }
        assertFalse("An empty station must not appear in ordinary Air/chooser", found);
    }
    private void tapActualTextPrefix(String title) {
        Rect target = new Rect(); long until = SystemClock.elapsedRealtime() + 5000; boolean found = false;
        while (SystemClock.elapsedRealtime() < until && !found) {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) try { for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(title)) { String value = node.getText() == null ? "" : node.getText().toString(); if (!found && node.isVisibleToUser() && node.isEnabled() && node.isClickable() && value.startsWith(title + "\n")) { node.getBoundsInScreen(target); found = !target.isEmpty(); } node.recycle(); } } finally { root.recycle(); }
            if (!found) SystemClock.sleep(50);
        }
        assertTrue("The actual visible queued note action must be present", found);
        long time = SystemClock.uptimeMillis(); MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, target.centerX(), target.centerY(), 0), up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, target.centerX(), target.centerY(), 0);
        try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up); } finally { down.recycle(); up.recycle(); } instrumentation.waitForIdleSync();
    }
    /** This regression deliberately does not use the state-synchronizing helpers above. */
    private void waitUntouchedPaint(Button button) {
        assertNotNull(button);
        await(3000, () -> main(() -> button.isAttachedToWindow() && button.getWidth() > 0 && button.getHeight() > 0 && !button.isLayoutRequested()
                && button.getBackground() != null && button.getBackground().getBounds().width() > 0 && button.getBackground().getBounds().height() > 0));
    }
    private void waitUntouchedNavigation() { for (Button button : main(() -> (Button[]) field(activity, "navButtons"))) waitUntouchedPaint(button); }
    private void assertUntouchedNavigation(int selected) throws Exception {
        Button[] navigation = (Button[]) field(activity, "navButtons"); assertEquals(5, navigation.length); int selectedCount = 0;
        for (int i = 0; i < navigation.length; i++) {
            if (navigation[i].isSelected()) selectedCount++;
            assertUntouchedPaint(navigation[i], i == selected ? UiStyle.palette(activity).soft : Color.WHITE, UiStyle.palette(activity).ink);
        }
        assertEquals("Exactly one selected navigation item", 1, selectedCount);
    }
    private static void assertUntouchedPaint(Button button, int expectedFill, int expectedText) {
        int fill = 0;
        try {
            assertTrue("The reused control stays attached", button.isAttachedToWindow());
            assertTrue("The control remains continuously enabled", button.isEnabled());
            Drawable actual = button.getBackground(); assertNotNull(actual);
            Bitmap sample = Bitmap.createBitmap(button.getWidth(), button.getHeight(), Bitmap.Config.ARGB_8888);
            try { actual.draw(new Canvas(sample)); fill = sample.getPixel(sample.getWidth() / 2, sample.getHeight() / 2); }
            finally { sample.recycle(); }
            assertEquals("Untouched actual interior fill", expectedFill, fill);
            assertEquals("Untouched actual text color", expectedText, button.getCurrentTextColor());
            assertEquals(255, Color.alpha(fill)); assertEquals(255, Color.alpha(button.getCurrentTextColor()));
            assertEquals(1f, button.getAlpha(), 0f);
            assertTrue("Untouched text/fill contrast must be at least 4.5", contrast(button.getCurrentTextColor(), fill) >= 4.5);
            // Observe the actual whole View only after the untouched background passed.
            // No enabled/state/bounds synchronization or jump is performed by this sampler.
            Bitmap rendered = Bitmap.createBitmap(button.getWidth(), button.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                button.draw(new Canvas(rendered)); boolean glyph = false;
                for (int y = 0; y < rendered.getHeight() && !glyph; y++) for (int x = 0; x < rendered.getWidth(); x++) if (rendered.getPixel(x, y) == expectedText) { glyph = true; break; }
                assertTrue("Actual untouched View paint contains resolved opaque foreground glyphs", glyph);
            } finally { rendered.recycle(); }
        } catch (Throwable failure) {
            // State reads are diagnostic only, after measurement; never push them back into the drawable.
            throw new AssertionError("Untouched paint mismatch sampled=" + String.format(Locale.ROOT, "#%08x", fill) + " " + untouchedStateDiagnostic(button), failure);
        }
    }
    private static void assertUnchangedBackground(Button button, Drawable expected) {
        if (button.getBackground() != expected) throw new AssertionError("Unchanged skin/role refresh replaced the background after measurement: " + untouchedStateDiagnostic(button));
    }
    private static String untouchedStateDiagnostic(Button button) {
        return "caption=" + button.getText() + " enabled=" + button.isEnabled() + " pressed=" + button.isPressed() + " focused=" + button.isFocused()
                + " viewState=" + Arrays.toString(button.getDrawableState()) + " drawable=" + drawableStateDiagnostic(button.getBackground(), 0);
    }
    private static String drawableStateDiagnostic(Drawable drawable, int depth) {
        if (drawable == null) return "null";
        String state = drawable.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(drawable)) + " state=" + Arrays.toString(drawable.getState()) + " bounds=" + drawable.getBounds();
        if (depth < 3 && drawable instanceof LayerDrawable) { LayerDrawable layers = (LayerDrawable) drawable; for (int i = 0; i < layers.getNumberOfLayers(); i++) state += " child" + i + "={" + drawableStateDiagnostic(layers.getDrawable(i), depth + 1) + "}"; }
        else if (depth < 3 && drawable instanceof StateListDrawable) state += " current={" + drawableStateDiagnostic(drawable.getCurrent(), depth + 1) + "}";
        return state;
    }
    private List<Object> playerTuple() { return main(() -> { PlayerController.Snapshot s = service.player().snapshot(); return Arrays.asList(s.station.id, s.article == null ? null : s.article.id, s.article == null ? null : s.article.version, s.sentenceIndex, s.textOffset, s.passEnded, s.read, s.started, s.listened, s.completedSentences); }); }
    private void assertPaused() { main(() -> { assertFalse(service.player().snapshot().playing); assertNull(service.player().snapshot().currentRequestId); assertFalse(service.notes().recordingBusy()); assertFalse(service.notes().originalPlaying()); return null; }); }
    private static <T extends View> List<T> views(View root, Class<T> type) { List<T> found = new ArrayList<>(); if (type.isInstance(root)) found.add(type.cast(root)); if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) found.addAll(views(((ViewGroup) root).getChildAt(i), type)); return found; }
    private static Button button(View root, String text) { for (Button button : views(root, Button.class)) if (text.equals(button.getText().toString())) return button; return null; }
    private void assertVisible(String text) { instrumentation.waitForIdleSync(); AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow(); assertNotNull(root); boolean visible = false; try { for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) { visible |= node.isVisibleToUser(); node.recycle(); } } finally { root.recycle(); } assertTrue("Visible UI must contain " + text, visible); }
    private void click(String text) {
        NativeFixtureSupport.clickVisible(instrumentation, text);
    }
    private <T> T main(Callable<T> action) { AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>(); instrumentation.runOnMainSync(() -> { try { value.set(action.call()); } catch (Throwable failure) { error.set(failure); } }); if (error.get() instanceof Error) throw (Error) error.get(); if (error.get() != null) throw new AssertionError("Main-thread action failed", error.get()); return value.get(); }
    private void await(long duration, BooleanSupplier condition) { long until = SystemClock.elapsedRealtime() + duration; while (SystemClock.elapsedRealtime() < until) { if (condition.getAsBoolean()) return; SystemClock.sleep(50); } fail("Runtime precondition did not occur in " + duration + "ms"); }
    private static Object field(Object target, String name) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void setField(Object target, String name, Object value) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    private List<File> managedPaths() { List<File> files = new ArrayList<>(); for (String name : new String[] {"notes", "source-cas", "source-network"}) files.add(new File(context.getNoBackupFilesDir(), name)); for (String suffix : new String[] {"", ".bak", ".new"}) files.add(new File(context.getFilesDir(), "player-state-v1.json" + suffix)); return files; }

    @After public void restore() throws Exception {
        Throwable failure = null;
        if (fixtureSupport != null) try { fixtureSupport.stopAndDrain(() -> { if (scenario != null) { scenario.close(); scenario = null; } }); } catch (Throwable error) { failure = error; }
        if (fixtureSupport != null && fixtureSupport.drained()) {
            try { fixtureSupport.restore(); } catch (Throwable error) { failure = append(failure, error); }
            try { restorePreferences(); } catch (Throwable error) { failure = append(failure, error); }
            if (notificationsChanged) try { instrumentation.getUiAutomation().revokeRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS); } catch (Throwable error) { failure = append(failure, error); }
        }
        if (failure instanceof Error) throw (Error) failure; if (failure != null) throw new AssertionError("Fixture restoration failed; remaining backups retained", failure);
    }
    @SuppressWarnings("unchecked") private void restorePreferences() { if (preferences == null) return; SharedPreferences.Editor editor = context.getSharedPreferences(UiStyle.PREFS, Context.MODE_PRIVATE).edit().clear(); for (Map.Entry<String, ?> entry : preferences.entrySet()) { Object value = entry.getValue(); if (value instanceof String) editor.putString(entry.getKey(), (String) value); else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value); else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value); else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value); else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value); else if (value instanceof Set) editor.putStringSet(entry.getKey(), new HashSet<>((Set<String>) value)); } assertTrue(editor.commit()); }
    private static Throwable append(Throwable previous, Throwable error) { if (previous == null) return error; previous.addSuppressed(error); return previous; }
}

package ru.bububu.radio;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import ru.bububu.radio.models.*;

/** Models stay private; import, engine choice and each model check are explicit user actions. */
final class ModelsPanel extends LinearLayout {
    static final int SILERO = 4811, GIGAAM = 4812, WAV = 4813;
    private final RadioActivity activity;
    private final ModelsController models;
    private final PackCard sileroCard, gigaCard;
    private final TextView status, sileroTestReason, gigaTestReason;
    private final EditText input;
    private final Button prepare, listen, transcribe, cancel;
    private ModelPackStore.Token task;
    private File prepared;
    private boolean closed, preparingText;
    private int revision;
    private PackCatalog importing;
    private String importErrorKind = "";

    ModelsPanel(RadioActivity a) {
        super(a);
        activity = a;
        models = ModelsController.get(a);
        status = text("");
        setOrientation(VERTICAL);
        add(UiStyle.heading(activity, "Голос и расшифровка", 22));
        add(text(BuildConfig.MODEL_EXPERIMENT
                ? "Встроенные модели работают на телефоне. Для радио и заметок можно выбрать разные движки."
                : "Для каждой модели: импортируйте ZIP, дождитесь готовности пакета и включите нужный движок. Импорт не меняет выбор и не запускает модели."));
        sileroCard = new PackCard("Голос радио", "Silero · голос Евгения", PackCatalog.SILERO, SILERO);
        gigaCard = new PackCard("Текст голосовых заметок", "GigaAM · расшифровка сохранённой записи до 2 минут", PackCatalog.GIGAAM, GIGAAM);

        LinearLayout checks = UiStyle.column(activity);
        checks.setVisibility(GONE);
        Button disclosure = button("Проверка модели · раскрыть", () -> {});
        disclosure.setOnClickListener(v -> {
            boolean expanded = checks.getVisibility() != VISIBLE;
            checks.setVisibility(expanded ? VISIBLE : GONE);
            disclosure.setText(expanded ? "Проверка модели · свернуть" : "Проверка модели · раскрыть");
            refresh(); // Read state only. Expanding never prepares, plays or recognizes.
        });
        add(disclosure);
        checks.addView(text("Silero · короткий текст"));
        input = new EditText(a);
        input.setHint("Короткий текст для Silero");
        input.setMinLines(2);
        input.setTextSize(16);
        checks.addView(input);
        checks.addView(text("Русский текст до 256 произносимых знаков. Поддержаны отдельные даты и температура 0–99 градусов. Неподдержанный текст остановит подготовку, без замены голоса."));
        sileroTestReason = text("");
        checks.addView(sileroTestReason);
        prepare = button("Подготовить тест Silero", this::prepare);
        checks.addView(prepare);
        listen = button("Слушать подготовленное", this::listen);
        checks.addView(listen);
        checks.addView(button("Остановить прослушивание", () -> {
            RadioService service = activity.serviceForNotes();
            if (service != null) service.command(p -> p.pause());
        }));
        checks.addView(text("GigaAM · тестовый PCM16 mono 16 кГц WAV до 30 секунд. Микрофон не используется."));
        gigaTestReason = text("");
        checks.addView(gigaTestReason);
        transcribe = button("Распознать тестовый WAV", () -> pick(WAV, "audio/*"));
        checks.addView(transcribe);
        add(checks);

        // Import cancellation and its result stay visible even when model checks are collapsed.
        cancel = button("Отменить операцию", () -> {
            if (task != null) {
                boolean accepted = task.cancel();
                status.setText(accepted ? "Отмена… дождитесь завершения операции" : "Завершение операции…");
                refresh();
            }
        });
        add(cancel);
        status.setTextIsSelectable(true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(status);
        if (BuildConfig.MODEL_EXPERIMENT) add(button("Лицензии встроенных моделей",this::showLicenses));
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                revision++;
                if (task != null && preparingText) task.cancel();
                prepared = null;
                listen.setEnabled(false);
            }
            public void afterTextChanged(Editable e) {}
        });
        refresh();
    }

    void showLicenses(){
            try (java.io.InputStream notice = activity.getAssets().open("model-licenses/NOTICE.txt");
                 java.io.InputStream license = activity.getAssets().open("model-licenses/LICENSE.GigaAM-punct")) {
                String value = readNotice(notice) + "\n\n" + readNotice(license);
                new android.app.AlertDialog.Builder(activity).setTitle("Лицензии моделей").setMessage(value).setPositiveButton("Закрыть", null).show();
            } catch (java.io.IOException failure) { status.setText("Не удалось открыть уведомления о лицензиях."); }
    }

    private void add(View view) { UiStyle.spaced(this, view, 10); }
    private static String readNotice(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > 32768) throw new java.io.IOException("Notice too large");
            bytes.write(buffer, 0, count);
        }
        return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
    private TextView text(String value) { return UiStyle.label(activity, value, 15, false); }
    private Button button(String title, Runnable action) { return UiStyle.button(activity, title, action); }

    private final class PackCard {
        final PackCatalog pack;
        final TextView state, compatibility, selected, reason, systemReason;
        final Button importZip, enable, system;

        PackCard(String title, String purpose, PackCatalog catalog, int code) {
            pack = catalog;
            LinearLayout card = UiStyle.card(activity);
            card.addView(UiStyle.heading(activity, title, 19));
            selected = UiStyle.accentLabel(activity, "", 16);
            UiStyle.spaced(card, selected, 8);
            UiStyle.spaced(card, text(purpose), 8);
            state = text("");
            card.addView(state);
            enable = button(pack == PackCatalog.SILERO ? "Выбрать Silero" : "Выбрать GigaAM",
                    () -> { if (pack == PackCatalog.SILERO) select(true); else selectNotes(true); });
            UiStyle.primary(enable);
            UiStyle.spaced(card, enable, 12);
            reason = text("");
            card.addView(reason);
            system = button(pack == PackCatalog.SILERO ? "Использовать голос телефона" : "Использовать системное распознавание",
                    () -> { if (pack == PackCatalog.SILERO) select(false); else selectNotes(false); });
            UiStyle.spaced(card, system, 8);
            systemReason = text("");
            card.addView(systemReason);
            LinearLayout details = UiStyle.column(activity);
            details.setVisibility(GONE);
            Button more = button("Пакет и ручной импорт", () -> {});
            more.setOnClickListener(v -> {
                boolean show = details.getVisibility() != VISIBLE;
                details.setVisibility(show ? VISIBLE : GONE);
                more.setText(show ? "Скрыть сведения о пакете" : "Пакет и ручной импорт");
            });
            UiStyle.spaced(card, more, 12);
            compatibility = text("");
            details.addView(compatibility);
            details.addView(text("Размер пакета: " + android.text.format.Formatter.formatFileSize(activity, pack.bytes)));
            importZip = button("Импортировать ZIP " + name(), () -> pick(code, "application/zip"));
            UiStyle.spaced(details, importZip, 8);
            UiStyle.spaced(card, details, 8);
            add(card);
        }

        String name() { return pack == PackCatalog.SILERO ? "Silero" : "GigaAM"; }

        void refresh(RadioService service, String unavailable, String notesReason) {
            boolean ready = models.ready(pack) != null;
            compatibility.setText(unavailable == null
                    ? "Требования устройства выполнены: ARM64, Android 9+, страницы памяти 4 КБ."
                    : "Локальная модель недоступна: " + unavailable);
            boolean setupPending = service != null && service.bundledSetupStatus() != null;
            String packageState = ready ? setupPending ? "Пакет: найден · подготовка не завершена" : "Пакет: готов" : "Пакет: не установлен";
            if (importing == pack) packageState += " · импорт ZIP…";
            else if (pack.kind.equals(importErrorKind)) packageState += " · последний импорт не завершён";
            state.setText(packageState);
            String importReason = !BuildConfig.MODEL_EXPERIMENT ? "Для импорта нужна experimental ARM64-версия."
                    : operationBlocked() ? "Дождитесь завершения текущей операции." : null;
            importZip.setEnabled(importReason == null);
            String enableReason = unavailable != null ? unavailable
                    : setupPending ? "Дождитесь успешной подготовки обеих моделей. При ошибке используйте «Повторить подготовку моделей»."
                    : !ready ? "Сначала импортируйте ZIP " + name() + " и дождитесь готовности пакета."
                    : service == null ? "Дождитесь подключения сервиса."
                    : pack == PackCatalog.GIGAAM ? notesReason : null;
            enable.setEnabled(enableReason == null);
            String blocked = enableReason != null ? enableReason : importReason;
            reason.setText(blocked);
            reason.setVisibility(blocked == null ? GONE : VISIBLE);
            selected.setText(service == null ? "Выбранный движок: ждём подключения сервиса."
                    : pack == PackCatalog.SILERO ? "Голос радио: " + (service.sileroSelected() ? "Silero · eugene" : "системный голос телефона")
                    : service.notesRecognitionDescription());
            String fallbackReason = service == null ? "Дождитесь подключения сервиса."
                    : pack == PackCatalog.GIGAAM ? notesReason : null;
            system.setEnabled(fallbackReason == null);
            systemReason.setText(fallbackReason == null ? "" : fallbackReason);
            systemReason.setVisibility(fallbackReason == null ? GONE : VISIBLE);
        }
    }

    void refresh() {
        if (closed) return;
        RadioService service = activity.serviceForNotes();
        String unavailable = models.availability();
        String notesReason = service == null ? "Дождитесь подключения сервиса."
                : service.notes() == null ? "Заметки недоступны."
                : service.notes().recordingBusy() || service.notes().stopUnconfirmed()
                ? "Сначала завершите запись и сохранение оригинала." : null;
        sileroCard.refresh(service, unavailable, notesReason);
        gigaCard.refresh(service, unavailable, notesReason);
        String sileroReason = testReason(PackCatalog.SILERO, unavailable);
        String gigaReason = testReason(PackCatalog.GIGAAM, unavailable);
        sileroTestReason.setText(sileroReason == null ? "Подготовка создаёт WAV. Прослушивание запускается отдельной кнопкой." : sileroReason);
        gigaTestReason.setText(gigaReason == null ? "Выберите WAV, чтобы начать проверку распознавания." : gigaReason);
        prepare.setEnabled(sileroReason == null);
        transcribe.setEnabled(gigaReason == null);
        listen.setEnabled(prepared != null && service != null && task == null);
        cancel.setEnabled(task != null);
        cancel.setVisibility(task == null ? GONE : VISIBLE);
    }

    private boolean operationBlocked() { return task != null || models.foregroundBlocked(); }
    private String testReason(PackCatalog pack, String unavailable) {
        if (unavailable != null) return unavailable;
        RadioService service = activity.serviceForNotes();
        if (service != null && service.bundledSetupStatus() != null) return service.bundledSetupStatus();
        if (models.ready(pack) == null) return "Сначала импортируйте ZIP " + (pack == PackCatalog.SILERO ? "Silero" : "GigaAM") + ".";
        return operationBlocked() ? "Дождитесь завершения текущей операции." : null;
    }

    private void selectNotes(boolean local) {
        RadioService service = activity.serviceForNotes();
        if (service == null) { status.setText("Дождитесь подключения сервиса"); return; }
        try {
            service.selectGigaNotes(local);
            refresh();
            status.setText(local ? "GigaAM включён для заметок. Откройте «Заметки», запишите и остановите запись."
                    : "Выбрано системное распознавание заметок.");
        } catch (RuntimeException error) {
            refresh();
            status.setText("Выбор недоступен. Завершите запись и проверьте готовность пакета GigaAM. Прежний выбор сохранён.");
        }
    }

    private void select(boolean silero) {
        RadioService service = activity.serviceForNotes();
        if (service == null) { status.setText("Дождитесь подключения сервиса"); return; }
        try {
            service.selectSilero(silero);
            refresh();
            status.setText(silero ? "Silero включён для радио. Нажмите «Слушать» на экране «Радио»." : "Выбран системный голос телефона.");
        } catch (RuntimeException error) {
            refresh();
            status.setText("Выбор голоса не подтверждён. Проверьте готовность пакета и остановку звука. Прежний выбор сохранён.");
        }
    }

    private void pick(int code, String type) {
        if (operationBlocked()) { status.setText("Дождитесь завершения текущей операции"); return; }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"));
            activity.startActivityForResult(intent, code);
        } catch (RuntimeException error) { status.setText("Выбор файла недоступен"); }
    }

    void result(int code, int result, Intent data) {
        if (closed || result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (operationBlocked()) { status.setText("Файл не обработан: дождитесь завершения текущей операции и выберите его снова."); return; }
        Uri uri = data.getData();
        preparingText = false;
        status.setText(code == WAV ? "Распознавание файла…" : "Импорт пакета… предыдущий готовый пакет сохранён");
        if (code == WAV) {
            task = models.transcribe(uri, new ModelsController.Result<String>() {
                public void success(String value) { if (!closed) { task = null; refresh(); status.setText(value); } }
                public void error(String error) { showError(error); }
            });
        } else {
            PackCatalog pack = code == SILERO ? PackCatalog.SILERO : PackCatalog.GIGAAM;
            importing = pack;
            importErrorKind = "";
            task = models.importPack(pack, uri, new ModelsController.Result<File>() {
                public void success(File value) {
                    if (!closed) {
                        task = null; importing = null; importErrorKind = ""; refresh();
                        status.setText(pack == PackCatalog.SILERO
                                ? "Пакет Silero готов. Выбор голоса сохранён. Для включения используйте кнопку в карточке «Голос радио»."
                                : "Пакет GigaAM готов. Выбор распознавания сохранён. Для включения используйте кнопку в карточке «Расшифровка заметок».");
                    }
                }
                public void error(String error) { showError(error); }
            });
        }
        refresh();
    }

    private void prepare() {
        if (operationBlocked()) { status.setText("Дождитесь завершения текущей операции"); return; }
        RadioService service = activity.serviceForNotes();
        if (service != null) service.command(p -> p.pause());
        int current = revision;
        preparingText = true;
        prepared = null;
        status.setText("Подготовка WAV… звук не включён");
        task = models.prepare(input.getText().toString(), new ModelsController.Result<File>() {
            public void success(File file) {
                if (closed || current != revision) { file.delete(); return; }
                task = null; preparingText = false; prepared = file; refresh();
                status.setText("Подготовлено. Нажмите «Слушать подготовленное».");
            }
            public void error(String error) { showError(error); }
        });
        refresh();
    }

    private void listen() {
        RadioService service = activity.serviceForNotes();
        if (service == null || prepared == null) { status.setText("Сначала подготовьте текст"); return; }
        service.listenPrepared(prepared, message -> { if (!closed) status.setText(message); });
    }

    private void showError(String message) {
        if (!closed) {
            task = null; preparingText = false;
            if (importing != null) { importErrorKind = importing.kind; importing = null; }
            refresh(); status.setText("Ошибка: " + message);
        }
    }

    void close() {
        closed = true; revision++;
        if (task != null) task.cancel();
        RadioService service = activity.serviceForNotes();
        if (service != null) try { service.stopModelPreview(); } catch (RuntimeException ignored) {}
        if (prepared != null) prepared.delete();
    }
}

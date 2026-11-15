package ru.bububu.radio;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.bububu.radio.core.NoteBook;
import ru.bububu.radio.core.NoteCoordinator;
import ru.bububu.radio.core.NoteDraftStore;

/** Neo note cards keep edit widgets stable across asynchronous audio/status updates. */
@SuppressLint("ViewConstructor") // Programmatic owner-aware view; never inflated from XML.
final class NotesPanel extends LinearLayout {
    private final RadioActivity activity;
    private final List<ru.bububu.radio.core.Station> linkedFixtures;
    private final EditText search;
    private final TextView state, link;
    private final Button record, discardTake;
    private NoteDraftStore drafts;
    private final Map<String,NoteDraftStore.Draft> savedDrafts=new LinkedHashMap<>();
    private boolean draftsLoaded,draftsBlocked;
    private final Runnable flushDrafts=()->persistDrafts();
    private final LinearLayout cards,intro,recordingPane;
    private final TextView recordingTimer,recordingLabel,recordingContext,recordingRadio;
    private final Button startRecording, finishRecording;
    private String openedId,lastRecordingId;
    private boolean recordingVisible,recordingPrepared;
    private final Map<String,Summary> summaryViews=new LinkedHashMap<>();
    private final Map<String, Card> views = new LinkedHashMap<>();
    private final Map<String,Card> retainedCards=new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String stationId, articleId, version;
    private boolean visible;
    private int navigationState = -1;
    private String permissionProblem;
    private Button permissionSettings;
    private final Runnable poll = new Runnable() {
        @Override public void run() { if (visible) refresh(); }
    };
    NotesPanel(RadioActivity activity) {
        super(activity);
        this.activity = activity;
        linkedFixtures = LocalData.fixtures(activity);
        setOrientation(VERTICAL);
        setPadding(dp(20), dp(12), dp(20), dp(24));

        intro = UiStyle.column(activity);
        addView(intro);
        link = UiStyle.accentLabel(activity, "", 14);
        intro.addView(link);
        record = UiStyle.action(activity, "Записать заметку", "mic", this::prepareRecording);
        UiStyle.primary(record);
        record.setTag("notes-prepare");
        UiStyle.spaced(intro, record, 8);
        UiStyle.spaced(intro, UiStyle.label(activity, "До 2 минут · на телефоне", 14, true), 10);
        search = new EditText(activity);
        search.setSingleLine(true);
        search.setHint("Поиск по названию и тексту");
        search.setContentDescription("Поиск заметок");
        search.setCompoundDrawablesWithIntrinsicBounds(new UiIcon(activity, "search", UiStyle.palette(activity).muted, 20), null, null, null);
        search.setCompoundDrawablePadding(dp(10));
        search.setMinimumHeight(dp(48));
        search.setPadding(dp(12), dp(12), dp(12), dp(12));
        search.setTag("ui-input");
        UiStyle.spaced(intro, search, 16);
        UiStyle.divider(intro, 12);

        recordingPane = UiStyle.column(activity);
        recordingPane.setVisibility(GONE);
        addView(recordingPane);
        recordingLabel = UiStyle.heading(activity, "", 22);
        recordingLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        // Only Stop occupies this slot while a take is active. A stale tap cannot start another take.
        startRecording = UiStyle.action(activity, "Начать запись", "mic", this::beginRecording);
        UiStyle.primary(startRecording);
        startRecording.setTag("notes-record");
        UiStyle.spaced(recordingPane, startRecording, 8);
        finishRecording = UiStyle.action(activity, "Остановить и сохранить", "stop", this::finishTake);
        UiStyle.primary(finishRecording);
        finishRecording.setTag("notes-record-stop");
        finishRecording.setMinHeight(dp(56));
        finishRecording.setMinimumHeight(dp(56));
        UiStyle.spaced(recordingPane, finishRecording, 8);
        UiStyle.spaced(recordingPane, recordingLabel, 8);
        recordingTimer = UiStyle.heading(activity, "0:00", 40);
        recordingTimer.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        UiStyle.spaced(recordingPane, recordingTimer, 16);
        recordingContext = UiStyle.accentLabel(activity, "", 15);
        UiStyle.spaced(recordingPane, recordingContext, 8);
        TextView limit = UiStyle.label(activity, "До 2 минут · оригинал на телефоне", 14, true);
        UiStyle.spaced(recordingPane, limit, 8);
        recordingRadio = UiStyle.label(activity, "", 14, true);
        UiStyle.spaced(recordingPane, recordingRadio, 8);
        discardTake = button("Отменить запись…", this::discardOrBack);
        UiStyle.spaced(recordingPane, discardTake, 16);
        permissionSettings = button("Разрешения приложения Android", () -> activity.startActivity(new android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", activity.getPackageName(), null))));
        addView(permissionSettings);
        state = UiStyle.label(activity, "", 14, true);
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(state);
        cards = UiStyle.column(activity);
        addView(cards);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence text,int start,int count,int after){}public void onTextChanged(CharSequence text,int start,int before,int count){RadioService service=activity.serviceForNotes();if(service!=null&&service.notes()!=null)service.notes().searchChanged();rebuild();}public void afterTextChanged(Editable text){}});
    }
    private void beginRecording() {
        RadioService owner = activity.serviceForNotes();
        if (!visible || !recordingPrepared || owner == null || owner.notes() == null || owner.notes().recordingBusy()) return;
        if (search.length() != 0) search.setText("");
        activity.requestNoteRecording(stationId, articleId, version);
    }

    private void finishTake() {
        RadioService owner = activity.serviceForNotes();
        if (owner == null || owner.notes() == null || lastRecordingId == null) return;
        if (lastRecordingId.equals(owner.notes().recordingId())) owner.notes().stopRecording("USER_STOP");
    }

    private void discardOrBack() {
        RadioService owner = activity.serviceForNotes();
        if (owner == null || owner.notes() == null) return;
        if (!owner.notes().recordingBusy()) { handleBack(); return; }
        final String id = owner.notes().recordingId();
        new android.app.AlertDialog.Builder(activity).setTitle("Отменить эту запись?")
                .setMessage("Незавершённая запись будет удалена после остановки микрофона.")
                .setPositiveButton("Отменить запись", (d, w) -> {
                    if (owner == activity.serviceForNotes() && id.equals(owner.notes().recordingId())) owner.notes().discardTake();
                }).setNegativeButton("Остаться", null).show();
    }

    void prepareRecording() {
        recordingPrepared = true;
        openedId = null;
        permissionProblem = null;
        refresh();
        scrollToTop();
    }
    boolean recordingScreen() { return recordingVisible || recordingPrepared; }
    boolean recordingActive() { return recordingVisible; }
    boolean preparedForRecording() { return recordingPrepared; }
    boolean detailOpen() { return openedId != null; }

    void showOpenMenu() {
        Card card = retainedCards.get(openedId);
        if (card != null) card.showMenu();
    }

    boolean handleBack() {
        RadioService owner = activity.serviceForNotes();
        if (recordingVisible || owner != null && owner.notes() != null && owner.notes().recordingBusy()) {
            guardNavigation(() -> {});
            return true;
        }
        if (recordingPrepared) {
            activity.cancelNoteStart();
            recordingPrepared = false;
            permissionProblem = null;
            refresh();
            return true;
        }
        Card card = retainedCards.get(openedId);
        if (card == null) return false;
        Runnable back = () -> {
            if (card.uiEditing) { card.uiEditing = false; card.applyMode(); }
            else { openedId = null; rebuild(); scrollToTop(); }
            syncNavigation();
        };
        if (guardNavigation(back)) back.run();
        return true;
    }

    private void openNote(String id) {
        openedId = id;
        rebuild();
        scrollToTop();
    }

    private void scrollToTop() {
        if (getParent() instanceof android.widget.ScrollView) {
            android.widget.ScrollView scroll = (android.widget.ScrollView) getParent();
            scroll.post(() -> scroll.scrollTo(0, 0));
        }
    }
    void microphoneProblem(boolean denied){permissionProblem=denied?"Нет разрешения на микрофон. Запись не начата. Разрешите микрофон в настройках Android или повторите запрос кнопкой записи.":null;refresh();}
    void linked(String station, String article, String contentVersion) {
        stationId = station; articleId = article; version = contentVersion;openedId=null;recordingPrepared=false;rebuild();
        updateRecordingContext();
    }
    private void updateRecordingContext(){setText(link, articleId==null?"":linkedContext(stationId,articleId,version,null));link.setVisibility(articleId==null?GONE:VISIBLE);}
    private String linkedContext(String station, String article, String contentVersion, String sourceVersionKey) {
        RadioService service = activity.serviceForNotes();
        NoteBook book = service == null ? null : service.notesBook();
        if (book != null) {
            ru.bububu.radio.sources.SourceVersionRecord source = sourceVersionKey == null ? null : book.sourceVersion(sourceVersionKey);
            if (source != null && source.articleId.equals(article) && source.versionKey.equals(contentVersion))
                return contextNames(source.displayTitle, source.sourceLabel);
            NoteBook.Material material = book.material(station, article);
            if (material != null && material.version.equals(contentVersion)) return contextNames(material.title, material.stationName);
        }
        for (ru.bububu.radio.core.Station fixture : linkedFixtures) if (fixture.id.equals(station))
            for (ru.bububu.radio.core.Article item : fixture.articles) if (item.id.equals(article) && item.version.equals(contentVersion))
                return contextNames(item.title, fixture.name);
        return "Контекст прежнего материала · название и источник сейчас недоступны.";
    }
    private static String contextNames(String title, String source) {
        return "К материалу «" + (title.trim().isEmpty() ? "Без названия" : title) + "»\n" + source;
    }
    void setPageVisible(boolean show) {
        if (!show) persistDrafts();
        visible = show;
        navigationState = -1;
        handler.removeCallbacks(poll);
        if (show) { UiStyle.themeTree(this); rebuild(); refresh(); }
    }

    private static void setText(TextView view, CharSequence value) {
        if (!android.text.TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    void refresh() {
        if (!visible) return;
        updateRecordingContext();
        RadioService service = activity.serviceForNotes();
        NoteCoordinator coordinator = service == null ? null : service.notes();
        if (coordinator == null) {
            for (Button button : new Button[]{record, startRecording, finishRecording, discardTake}) button.setEnabled(false);
            setText(state, service == null ? "Подключение…" : service.notesProblem());
            state.setVisibility(VISIBLE);
            return;
        }
        ensureDrafts(service);
        boolean busy = coordinator.recordingBusy();
        boolean wasBusy = recordingVisible;
        recordingVisible = busy;
        if (busy) {
            lastRecordingId = coordinator.recordingId();
        } else if (wasBusy && lastRecordingId != null) {
            recordingPrepared = false;
            openedId = service.notesBook().find(lastRecordingId) == null ? null : lastRecordingId;
            lastRecordingId = null;
            rebuild();
        }
        intro.setVisibility(!recordingScreen() && openedId == null ? VISIBLE : GONE);
        recordingPane.setVisibility(recordingScreen() ? VISIBLE : GONE);
        cards.setVisibility(recordingScreen() ? GONE : VISIBLE);
        startRecording.setVisibility(busy ? GONE : VISIBLE);
        startRecording.setEnabled(!busy && !coordinator.stopUnconfirmed());
        finishRecording.setVisibility(busy ? VISIBLE : GONE);
        finishRecording.setEnabled(busy && (!coordinator.isFinalizing() || coordinator.stopUnconfirmed()));
        setText(finishRecording, coordinator.stopUnconfirmed() ? "Повторить остановку" :
                coordinator.isFinalizing() ? "Сохраняем оригинал…" : "Остановить и сохранить");
        discardTake.setEnabled(true);
        discardTake.setVisibility(!busy || coordinator.discardableTake() ? VISIBLE : GONE);
        setText(discardTake, busy ? "Отменить запись…" : "Назад к заметкам");
        record.setEnabled(!busy);
        if (recordingScreen()) {
            NoteBook.Note note = busy ? service.notesBook().find(lastRecordingId) : null;
            setText(recordingContext, note == null ? (articleId == null ? "Независимая заметка" : link.getText())
                    : note.linkedArticle == null ? "Независимая заметка" : linkedContext(note.linkedStation, note.linkedArticle, note.linkedVersion, note.linkedSourceVersionKey));
            setText(recordingTimer, time(note == null ? 0 : Math.max(0, System.currentTimeMillis() - note.createdAt)));
            setText(recordingLabel, busy ? coordinator.stopUnconfirmed() ? "Остановка не подтверждена"
                    : coordinator.isFinalizing() ? "Сохраняем оригинал…" : "Идёт запись"
                    : "Нет доступа к микрофону");
            recordingLabel.setVisibility(busy || permissionProblem != null ? VISIBLE : GONE);
            setText(recordingRadio, service.player().snapshot().playing ? "Радио играет" : "Радио на паузе");
        }
        if (wasBusy != busy) scrollToTop();
        permissionSettings.setVisibility(permissionProblem == null ? GONE : VISIBLE);
        String problem = permissionProblem != null ? permissionProblem : coordinator.error();
        setText(state, problem == null ? "" : problem);
        state.setVisibility(problem == null ? GONE : VISIBLE);
        if (!recordingScreen()) {
            List<NoteBook.Note> found = foundNotes(service);
            if (cards.getChildCount() == 0 || found.size() != views.size() || !found.stream().allMatch(n -> views.containsKey(n.id))) {
                rebuild();
            } else for (NoteBook.Note note : found) {
                if (note.id.equals(openedId)) views.get(note.id).update(note, service);
                Summary summary = summaryViews.get(note.id);
                if (summary != null) summary.update(note);
            }
        }
        syncPaneSpacing();
        syncNavigation();
        handler.removeCallbacks(poll);
        if (busy || coordinator.originalPlaying()) handler.postDelayed(poll, 1100);
    }
    private void syncNavigation() {
        if (!visible) return;
        int state = (recordingScreen() ? 1 : 0) | (recordingActive() ? 2 : 0) | (detailOpen() ? 4 : 0);
        if (navigationState == state) return;
        navigationState = state;
        activity.refreshNotesNavigation();
    }
    private void syncPaneSpacing(){int top=recordingScreen()?12:openedId==null?0:20;if(getPaddingTop()!=dp(top))setPadding(dp(20),dp(top),dp(20),dp(24));}
    private List<NoteBook.Note> foundNotes(RadioService service){List<NoteBook.Note> found=new java.util.ArrayList<>(service.notesBook().search(search.getText().toString()));NoteBook.Note open=openedId==null?null:service.notesBook().find(openedId);if(open!=null&&found.stream().noneMatch(n->n.id.equals(open.id)))found.add(open);return found;}
    private void rebuild(){
        if(cards==null)return;RadioService service=activity.serviceForNotes();if(service==null||service.notesBook()==null)return;ensureDrafts(service);List<NoteBook.Note> found=foundNotes(service);if(openedId!=null&&service.notesBook().find(openedId)==null)openedId=null;
        views.clear();summaryViews.clear();cards.removeAllViews();if(found.isEmpty())cards.addView(UiStyle.label(activity,search.getText().length()==0?"Заметок пока нет. Нажмите «Записать заметку».":"Нет совпадений",14,true));
        for(NoteBook.Note note:found){Card card=retainedCards.get(note.id);if(card==null){card=new Card(note);retainedCards.put(note.id,card);}views.put(note.id,card);card.update(note,service);if(note.id.equals(openedId)){if(card.getParent() instanceof ViewGroup)((ViewGroup)card.getParent()).removeView(card);cards.addView(card);UiStyle.themeTree(card);}else if(openedId==null){Summary summary=new Summary(note);summaryViews.put(note.id,summary);cards.addView(summary);UiStyle.divider(cards,16);}}
        intro.setVisibility(!recordingScreen()&&openedId==null?VISIBLE:GONE);syncPaneSpacing();syncNavigation();
    }
    private final class Summary extends LinearLayout {
        final TextView title, context, status, date;

        Summary(NoteBook.Note note) {
            super(activity);
            setOrientation(HORIZONTAL);
            setGravity(android.view.Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(120));
            LinearLayout copy = UiStyle.column(activity);
            copy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            addView(copy, new LayoutParams(0, -2, 1));
            title = UiStyle.heading(activity, "", 18);
            copy.addView(title);
            context = UiStyle.label(activity, "", 13, false);
            context.setMaxLines(2);
            copy.addView(context);
            status = UiStyle.accentLabel(activity, "", 13);
            status.setMaxLines(2);
            UiStyle.spaced(copy, status, 8);
            date = UiStyle.label(activity, "", 12, true);
            UiStyle.spaced(copy, date, 6);
            android.widget.ImageView arrow = new android.widget.ImageView(activity);
            arrow.setImageDrawable(new UiIcon(activity, "arrow", UiStyle.palette(activity).brand, 20));
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams arrowSpace = new LayoutParams(dp(24), dp(24));
            arrowSpace.setMarginStart(dp(10));
            addView(arrow, arrowSpace);
            setOnClickListener(v -> openNote(note.id));
            setFocusable(true);
            setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x14216a55),
                    new android.graphics.drawable.ColorDrawable(UiStyle.palette(activity).background), null));
            update(note);
        }

        void update(NoteBook.Note note) {
            setText(title, note.title);
            setText(context, note.linkedArticle == null ? "Независимая заметка" :
                    linkedContext(note.linkedStation, note.linkedArticle, note.linkedVersion, note.linkedSourceVersionKey));
            setText(status, noteStatus(note));
            setText(date, new java.text.SimpleDateFormat("d MMMM yyyy · HH:mm", new java.util.Locale("ru")).format(new Date(note.createdAt)));
            setContentDescription(android.text.TextUtils.join("\n", new CharSequence[]{
                    title.getText(), context.getText(), status.getText(), date.getText(), "Открыть заметку"}));
        }
    }

    private static String noteStatus(NoteBook.Note note) {
        String saved = note.hasAudio ? " Запись сохранена." : "";
        if (NoteBook.RECORDING.equals(note.recordingStatus)) return "Идёт запись.";
        switch (note.recognitionStatus) {
            case "CHECKING": return "Проверяем распознавание…";
            case "TRANSCRIBING": return "Расшифровка…";
            case "CANCELLED": return "Расшифровка отменена." + saved;
            case "ERROR":
                if (note.message != null && note.message.contains("Речь не распознана")) return "Речь не распознана." + saved;
                return "Не удалось расшифровать." + saved;
            case "UNAVAILABLE":
            case "UNVERIFIED_FILE_SOURCE": return "Распознавание недоступно." + saved;
            default:
                if (!note.text.trim().isEmpty()) return "Текст сохранён.";
                return note.hasAudio ? "Запись сохранена." : "Текста пока нет.";
        }
    }

    private static String recognitionStatus(NoteBook.Note note) {
        String message = note.message;
        if (message == null || message.isEmpty() || message.contains("Речь не распознана")) return noteStatus(note);
        // Older GigaAM errors append a second acknowledgement to an already complete reason.
        String suffix = " Оригинальная запись сохранена.";
        if (message.endsWith(suffix)) {
            String reason = message.substring(0, message.length() - suffix.length()).trim();
            if (reason.toLowerCase(java.util.Locale.ROOT).contains("оригинал") && !reason.isEmpty())
                return reason.matches(".*[.!?…]$") ? reason : reason + ".";
        }
        return message;
    }
    private void ensureDrafts(RadioService owner){
        if(draftsLoaded||owner.noteStorage()==null)return;draftsLoaded=true;
        drafts=new NoteDraftStore(owner.noteStorage().directory,owner::requireDraftStorage);
        try{savedDrafts.putAll(drafts.load());}catch(Exception failure){draftsBlocked=true;toast("Черновики не удалось прочитать. Файлы сохранены; перезапись заблокирована.");}
    }
    boolean persistDrafts(){
        handler.removeCallbacks(flushDrafts);RadioService owner=activity.serviceForNotes();if(owner==null)return true;
        ensureDrafts(owner);if(draftsBlocked)return false;if(drafts==null)return true;
        for(Card card:retainedCards.values())if(card.dirty){try{savedDrafts.put(card.id,new NoteDraftStore.Draft(card.id,card.seenRevision,card.heading.getText().toString(),card.text.getText().toString()));}catch(IllegalArgumentException bounds){toast("Черновик превышает допустимый размер текста.");return false;}}
        try{drafts.save(savedDrafts.values());return true;}catch(Exception failure){toast("Не удалось сохранить черновик. Ручная правка остаётся на экране.");return false;}
    }
    boolean guardNavigation(Runnable proceed){
        RadioService owner=activity.serviceForNotes();if(owner==null||owner.notes()==null)return !recordingVisible;
        if(owner.notes().recordingBusy()){
            String id = owner.notes().recordingId();
            android.app.AlertDialog.Builder dialog=new android.app.AlertDialog.Builder(activity).setTitle("Запись ещё не завершена").setMessage(owner.notes().isFinalizing()?"Дождитесь подтверждения остановки и сохранения.":"Сначала остановите запись и сохраните оригинал.").setNegativeButton("Остаться",null);
            if(!owner.notes().isFinalizing()||owner.notes().stopUnconfirmed())dialog.setPositiveButton("Остановить и сохранить",(d,w)->{
                if (owner == activity.serviceForNotes() && id.equals(owner.notes().recordingId())) owner.notes().stopRecording("USER_STOP");
            });
            if(owner.notes().discardableTake())dialog.setNeutralButton("Отменить запись",(d,w)->{
                if (owner == activity.serviceForNotes() && id.equals(owner.notes().recordingId())) owner.notes().discardTake();
            });
            dialog.show();return false;
        }
        boolean dirty=false;for(Card card:retainedCards.values())dirty|=card.dirty;
        if(!dirty)return true;persistDrafts();
        new android.app.AlertDialog.Builder(activity).setTitle("Правка не сохранена").setMessage("Сохраните изменения кнопкой в заметке или отмените ручную правку.").setPositiveButton("Отменить правку",(d,w)->{if(discardDrafts(owner))proceed.run();}).setNegativeButton("Остаться",null).show();return false;
    }
    private boolean discardDrafts(RadioService owner){
        Map<String,NoteDraftStore.Draft> remaining=new LinkedHashMap<>(savedDrafts);for(Card card:retainedCards.values())if(card.dirty)remaining.remove(card.id);
        try{if(draftsBlocked||drafts==null)return false;drafts.save(remaining.values());}catch(Exception failure){toast("Отмена черновика не подтверждена. Правка сохранена.");return false;}
        savedDrafts.clear();savedDrafts.putAll(remaining);
        for(Card card:retainedCards.values())if(card.dirty){NoteBook.Note note=owner.notesBook().find(card.id);card.loading=true;if(note!=null){card.heading.setText(note.title);card.text.setText(note.text);card.seenRevision=note.revision;}card.loading=false;card.dirty=false;owner.notes().draftChanged(card.id,false);}return true;
    }
    private final class Card extends LinearLayout {
        final String id;
        final EditText heading, text;
        final TextView info, context, clock, recognition, chosen;
        final SeekBar track;
        final Button play, stop, save, add, remove, probe, cancelStt, linkedText, delete;
        boolean dirty, dragging, loading;
        long seenRevision;
        boolean uiEditing;
        TextView displayTitle,previewText,audioLabel,engineLabel;
        Button editText;
        LinearLayout editor;
        Card(NoteBook.Note note) {
            super(activity); id = note.id; seenRevision = note.revision;
            setOrientation(VERTICAL); setPadding(dp(14), dp(12), dp(14), dp(14));
            GradientDrawable panel = new GradientDrawable(); panel.setColor(Color.WHITE); panel.setCornerRadius(dp(16));
            panel.setStroke(dp(1), Color.rgb(220, 228, 220)); setBackground(panel);setTag("ui-card");
            info = label("", 12); addView(info);
            context = label("", 13); addView(context);
            linkedText = button("Точный текст связанной новости", () -> {
                RadioService service = activity.serviceForNotes();
                if (service == null || service.notesBook() == null) return;
                NoteBook.Note current = service.notesBook().find(id);
                if (current == null || current.linkedArticle == null) return;
                if (current.linkedSourceVersionKey == null) {
                    ru.bububu.radio.core.Article exact = null;
                    for (ru.bububu.radio.core.Station fixture : LocalData.fixtures(activity)) if (fixture.id.equals(current.linkedStation))
                        for (ru.bububu.radio.core.Article article : fixture.articles) if (article.id.equals(current.linkedArticle) && article.version.equals(current.linkedVersion)) exact = article;
                    for (NoteBook.Material m : service.notesBook().materials()) if (m.stationId.equals(current.linkedStation) && m.articleId.equals(current.linkedArticle) && m.version.equals(current.linkedVersion)) exact = m.asArticle();
                    new android.app.AlertDialog.Builder(activity).setTitle(exact == null ? "Связь с новостью" : exact.title)
                            .setMessage(exact == null ? "Точный сохранённый текст этой старой связи недоступен. Сама связь сохранена." : exact.text)
                            .setNegativeButton("Закрыть", null).show(); return;
                }
                ru.bububu.radio.sources.SourceVersionRecord version = service.notesBook().sourceVersion(current.linkedSourceVersionKey);
                if (version != null && version.spokenText != null) SourcesPanel.showLinkedReader(activity, service, id);
            }); addView(linkedText);
            heading = new EditText(activity); heading.setSingleLine(true);heading.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(NoteBook.MAX_TITLE)}); heading.setText(note.title);
            heading.setContentDescription("Название заметки"); addView(heading);
            addView(label("Оригинальная запись · исходный голос", 13));
            LinearLayout transport = new LinearLayout(activity);
            play = button("Слушать запись", () -> {
                RadioService service = activity.serviceForNotes(); if (service == null || service.notes() == null) return;
                if (id.equals(service.notes().originalId()) && service.notes().originalPlaying()) service.notes().pauseOriginal();
                else service.notes().playOriginal(id);
            });
            stop = button("Остановить оригинал", () -> { RadioService s = activity.serviceForNotes(); if (s != null && s.notes() != null) s.notes().stopOriginal(id); });
            LayoutParams playParams=weight();playParams.rightMargin=dp(8);
            transport.addView(play, playParams); transport.addView(stop, weight()); UiStyle.spaced(this,transport,10);
            track = new SeekBar(activity); track.setMax(1000); track.setMinimumHeight(dp(48));
            track.setContentDescription("Позиция оригинального аудио в секундах"); addView(track);
            clock = label("0:00 / 0:00", 13); addView(clock);
            track.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    RadioService s = activity.serviceForNotes(); if (s == null || s.notesBook() == null || !fromUser) return;
                    NoteBook.Note n = s.notesBook().find(id); if (n == null) return;
                    long target = n.durationMs * value / 1000;
                    clock.setText(activity.getString(R.string.original_clock, time(target), time(n.durationMs)));
                    if (!dragging) s.notes().seekOriginal(id, target);
                }
                @Override public void onStopTrackingTouch(SeekBar bar) {
                    dragging = false; RadioService s = activity.serviceForNotes();
                    if (s != null && s.notesBook() != null) {
                        NoteBook.Note n = s.notesBook().find(id); if (n != null) s.notes().seekOriginal(id, n.durationMs * bar.getProgress() / 1000);
                    }
                }
            });
            recognition = label("", 13); addView(recognition);
            text = new EditText(activity); text.setMinLines(3); text.setGravity(android.view.Gravity.TOP);
            text.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(NoteBook.MAX_TEXT)});text.setHint("Текст заметки — можно ввести вручную"); text.setText(note.text);
            text.setContentDescription("Текст заметки"); addView(text);
            TextWatcher edits = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int c, int f) { }
                @Override public void onTextChanged(CharSequence value,int a,int before,int count){if(loading)return;dirty=true;draftTextChanged();RadioService owner=activity.serviceForNotes();if(owner!=null&&owner.notes()!=null)owner.notes().draftChanged(id,true);handler.removeCallbacks(flushDrafts);handler.postDelayed(flushDrafts,500);}
                @Override public void afterTextChanged(Editable e) { }
            };
            heading.addTextChangedListener(edits); text.addTextChangedListener(edits);
            NoteDraftStore.Draft draft=savedDrafts.get(id);if(draft!=null){loading=true;heading.setText(draft.title);text.setText(draft.text);loading=false;seenRevision=draft.revision;dirty=true;RadioService owner=activity.serviceForNotes();if(owner!=null&&owner.notes()!=null)owner.notes().draftChanged(id,true);}
            save = button("Сохранить изменения", () -> {
                RadioService s = activity.serviceForNotes(); if (s == null || s.notes() == null) return;
                NoteBook.Note before=s.notesBook().find(id);if(before==null||before.revision!=seenRevision){toast("Сохранённая заметка изменилась. Черновик сохранён; отмените правку перед редактированием новой версии.");persistDrafts();return;}
                s.notes().edit(id, heading.getText().toString(), text.getText().toString());
                NoteBook.Note saved = s.notesBook().find(id);
                dirty = saved == null || !saved.title.equals(heading.getText().toString()) || !saved.text.equals(text.getText().toString());
                if (!dirty){seenRevision = saved.revision;s.notes().draftChanged(id,false);savedDrafts.remove(id);persistDrafts();uiEditing=false;}refresh();
            }); UiStyle.spaced(this,save,10);
            chosen = label("", 12); addView(chosen);
            add = button("Добавить в эфир", () -> {
                if (dirty) { toast("Сначала сохраните изменения кнопкой «Сохранить изменения»."); return; }
                RadioService s = activity.serviceForNotes();if(s==null||s.notesBook()==null)return;
                NoteBook.Note current=s.notesBook().find(id);if(current==null)return;
                NoteBook.Material selected=null;for(NoteBook.Material material:s.notesBook().materials())if(id.equals(material.noteId))selected=material;
                try{if(selected==null)s.addNoteToAir(id);else s.updateNoteInAir(id,current.revision);refresh();}
                catch(ru.bububu.radio.sources.SourceCoordinator.ProjectionPendingException pending){toast("Версия сохранена в эфире; отображение требует восстановления. Откройте приложение снова.");}
                catch (RuntimeException failure) { toast("Изменение эфира не подтверждено. Проверьте сохранение и объём."); }
            }); UiStyle.spaced(this,add,8);
            remove = button("Убрать текст из эфира", () -> {
                try { RadioService s = activity.serviceForNotes(); if (s != null) s.removeNoteFromAir(id); }
                catch (RuntimeException failure) { toast("Не удалось убрать текст. Оригинал сохранён."); }
            }); UiStyle.spaced(this,remove,8);
            probe=button("Расшифровать запись",()->{
                RadioService owner=activity.serviceForNotes();if(owner==null||owner.notes()==null)return;
                if(dirty){toast("Сначала сохраните ручную правку кнопкой «Сохранить изменения».");return;}
                NoteBook.Note current=owner.notesBook().find(id);if(current==null)return;
                if(current.text.trim().isEmpty()){owner.notes().transcribe(id,false);return;}
                long expected=current.revision;
                new android.app.AlertDialog.Builder(activity).setTitle("Заменить текст расшифровкой?")
                    .setMessage("Существующий текст заметки будет заменён. Оригинальная запись и выбранный текст в эфире сохранятся.")
                    .setPositiveButton("Заменить текст",(d,w)->{
                        RadioService now=activity.serviceForNotes();if(now!=owner||dirty)return;NoteBook.Note target=owner.notesBook().find(id);
                        if(target==null||target.revision!=expected){toast("Текст изменился. Повторите действие.");return;}owner.notes().transcribe(id,true);
                    }).setNegativeButton("Отмена",null).show();
            });UiStyle.spaced(this,probe,8);
            cancelStt=button("Отменить расшифровку",()->{RadioService owner=activity.serviceForNotes();if(owner!=null&&owner.notes()!=null)owner.notes().cancelTranscription();});UiStyle.spaced(this,cancelStt,8);
            delete=button("Удалить заметку",this::confirmDelete);UiStyle.spaced(this,delete,12);composeDetail();
        }
        private void composeDetail() {
            removeAllViews();
            setTag(null);
            setBackgroundColor(UiStyle.palette(activity).background);
            setPadding(0, 0, 0, 0);
            displayTitle = UiStyle.heading(activity, "", 25);
            addView(displayTitle);
            linkedText.setTag(R.id.ui_style_role, "plain");
            linkedText.setPadding(0, dp(8), 0, dp(8));
            linkedText.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            linkedText.setCompoundDrawablesWithIntrinsicBounds(null, null, new UiIcon(activity, "arrow", UiStyle.palette(activity).brand, 20), null);
            addView(linkedText);
            addView(info);
            UiStyle.divider(this, 16);

            addView(UiStyle.heading(activity, "Оригинальная запись", 18));
            LinearLayout audio = UiStyle.row(activity);
            play.setMinWidth(0);
            play.setMinimumWidth(0);
            play.setText("");
            play.setPadding(dp(12), 0, dp(12), 0);
            if (play.getParent() instanceof ViewGroup) ((ViewGroup) play.getParent()).removeView(play);
            audio.addView(play, new LayoutParams(dp(48), dp(48)));
            LinearLayout timeline = UiStyle.column(activity);
            timeline.setPadding(dp(12), 0, 0, 0);
            audioLabel = UiStyle.label(activity, "Слушать запись", 14, false);
            timeline.addView(audioLabel);
            timeline.addView(track, new LayoutParams(-1, dp(48)));
            timeline.addView(clock);
            audio.addView(timeline, new LayoutParams(0, -2, 1));
            UiStyle.spaced(this, audio, 8);
            UiStyle.divider(this, 16);

            LinearLayout recognitionBlock = UiStyle.column(activity);
            recognitionBlock.setTag("ui-soft");
            recognitionBlock.setPadding(dp(14), dp(12), dp(14), dp(12));
            recognitionBlock.setBackground(UiStyle.background(activity, UiStyle.palette(activity).soft, 12));
            engineLabel = UiStyle.label(activity, "", 13, true);
            recognitionBlock.addView(engineLabel);
            UiStyle.spaced(recognitionBlock, recognition, 8);
            UiStyle.spaced(recognitionBlock, probe, 8);
            UiStyle.spaced(recognitionBlock, cancelStt, 8);
            addView(recognitionBlock);

            LinearLayout textTitle = UiStyle.row(activity);
            textTitle.addView(UiStyle.heading(activity, "Текст заметки", 18), new LayoutParams(0, -2, 1));
            editText = UiStyle.action(activity, "Ввести текст", "edit", () -> {
                uiEditing = true;
                applyMode();
                text.requestFocus();
            });
            textTitle.addView(editText);
            UiStyle.spaced(this, textTitle, 16);
            previewText = UiStyle.label(activity, "", 15, false);
            previewText.setTextIsSelectable(true);
            UiStyle.spaced(this, previewText, 8);
            editor = UiStyle.column(activity);
            heading.setTag("ui-input");
            text.setTag("ui-input");
            heading.setPadding(dp(12), dp(12), dp(12), dp(12));
            text.setPadding(dp(12), dp(12), dp(12), dp(12));
            editor.addView(UiStyle.label(activity, "Название", 13, true));
            editor.addView(heading);
            UiStyle.spaced(editor, text, 8);
            UiStyle.spaced(editor, save, 12);
            UiStyle.spaced(this, editor, 8);
            UiStyle.spaced(this, add, 16);
            UiStyle.primary(add);
            UiStyle.spaced(this, chosen, 8);
            uiEditing = dirty;
            applyMode();
        }
        private void draftTextChanged() { if (add != null) add.setEnabled(false); }
        void applyMode(){editor.setVisibility(uiEditing||dirty?VISIBLE:GONE);previewText.setVisibility(uiEditing||dirty?GONE:VISIBLE);}
        void showMenu(){java.util.List<Button> buttons=new java.util.ArrayList<>();if(stop.isEnabled())buttons.add(stop);if(remove.getVisibility()==VISIBLE)buttons.add(remove);buttons.add(delete);String[] labels=new String[buttons.size()];for(int i=0;i<labels.length;i++)labels[i]=buttons.get(i).getText().toString();new android.app.AlertDialog.Builder(activity).setTitle("Действия с заметкой").setItems(labels,(d,i)->{Button button=buttons.get(i);if(button.isEnabled())button.performClick();}).setNegativeButton("Закрыть",null).show();}

        private void confirmDelete() {
            RadioService owner=activity.serviceForNotes();
            if(owner==null||owner.notes()==null||owner.notesBook()==null)return;
            if(owner.notes().recordingBusy()){toast("Сначала завершите запись и сохранение оригинала.");return;}
            NoteBook book=owner.notesBook();NoteBook.Note target=book.find(id);if(target==null)return;
            long expected=target.revision,bookRevision=book.revision(),authority=book.authorityEpoch();
            String draftTitle=heading.getText().toString(),draftText=text.getText().toString();boolean draftDirty=dirty;
            new android.app.AlertDialog.Builder(activity).setTitle("Удалить заметку?")
                    .setMessage("«"+target.title+"»\nБудут удалены текст заметки, оригинальная запись и выбранная версия этой заметки в эфире. Другие материалы останутся."
                            +(draftDirty?"\nНесохранённая ручная правка также будет удалена; она не будет сохранена автоматически.":""))
                    .setPositiveButton("Удалить заметку",(d,w)->{
                        if(activity.serviceForNotes()!=owner||activity.isFinishing()||activity.isDestroyed())return;
                        NoteBook.Note current=book.find(id);
                        if(owner.notesBook()!=book||current==null||current.revision!=expected||book.revision()!=bookRevision||book.authorityEpoch()!=authority
                                ||dirty!=draftDirty||!draftTitle.equals(heading.getText().toString())||!draftText.equals(text.getText().toString())){
                            toast("Заметка или сохранённые данные изменились. Откройте подтверждение заново.");return;
                        }
                        if(owner.notes().recordingBusy()){toast("Сначала завершите запись и сохранение оригинала.");return;}
                        try{
                            boolean deleted=owner.deleteNote(id,expected);
                            if(deleted){retainedCards.remove(id);toast("Заметка и оригинальная запись удалены.");refresh();}
                            else toast("Удаление не подтверждено. Откройте подтверждение заново.");
                        }catch(ru.bububu.radio.sources.SourceCoordinator.ProjectionPendingException pending){
                            if(book.find(id)==null){retainedCards.remove(id);savedDrafts.remove(id);persistDrafts();refresh();}
                            toast(pending.getMessage());
                        }catch(RuntimeException failure){toast("Удаление не подтверждено. Сохранённые данные требуют проверки; попробуйте после восстановления.");}
                    }).setNegativeButton("Отмена",null).show();
        }
        void update(NoteBook.Note note, RadioService service) {
            NoteCoordinator coordinator = service.notes(); if (coordinator == null) return;
            linkedText.setVisibility(note.linkedArticle == null ? View.GONE : View.VISIBLE);
            context.setVisibility(View.GONE);
            if (note.linkedArticle != null) linkedText.setText(linkedContext(note.linkedStation, note.linkedArticle, note.linkedVersion, note.linkedSourceVersionKey));
            setText(displayTitle, note.title);
            setText(previewText, note.text.trim().isEmpty() ? "Сохранённого текста пока нет." : note.text);
            setText(editText, note.text.trim().isEmpty() ? "Ввести текст" : "Изменить текст");
            String engine = service.notesRecognitionDescription().split(" · |, |\\. ", 2)[0];
            setText(engineLabel, engine);
            info.setText(activity.getString(R.string.note_metadata,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(note.createdAt)),
                    note.linkedArticle == null ? "" : "Связана с новостью",
                    NoteBook.INTERRUPTED.equals(note.recordingStatus) ? activity.getString(R.string.note_interrupted) : ""));
            if(!dirty&&note.revision!=seenRevision){loading=true;heading.setText(note.title);text.setText(note.text);loading=false;dirty=false;seenRevision=note.revision;coordinator.draftChanged(id,false);}
            boolean current = id.equals(coordinator.originalId());
            play.setText("");audioLabel.setText(current&&coordinator.originalPlaying()?"Пауза":"Слушать запись");play.setCompoundDrawablesWithIntrinsicBounds(new UiIcon(activity,current&&coordinator.originalPlaying()?"pause":"play",UiStyle.palette(activity).brand,22),null,null,null);
            boolean enabled = note.hasAudio && !coordinator.recordingBusy();
            play.setEnabled(enabled&&coordinator.originalAllowed()); stop.setEnabled(enabled); track.setEnabled(enabled);
            play.setContentDescription(coordinator.originalAllowed()?(current&&coordinator.originalPlaying()?"Пауза оригинальной записи":"Слушать оригинальную запись"):"Системная расшифровка ещё работает; воспроизведение недоступно");
            long position = current ? coordinator.originalPosition() : note.positionMs;
            if (!dragging) {
                track.setProgress(note.durationMs == 0 ? 0 : (int) (Math.min(position, note.durationMs) * 1000 / note.durationMs));
                clock.setText(activity.getString(R.string.original_clock, time(position), time(note.durationMs)));
            }
            setText(recognition, recognitionStatus(note));
            NoteBook.Material selected = null;
            for (NoteBook.Material material : service.notesBook().materials()) if (id.equals(material.noteId)) selected = material;
            boolean selectedCurrent = selected != null && selected.version.equals("note-revision:" + note.revision);
            chosen.setText(selected == null ? "Сохранённый текст можно добавить в эфир голосом телефона."
                    : (selectedCurrent ? "В эфире — текущая сохранённая версия." : "В эфире — предыдущая сохранённая версия.")
                    + (dirty ? "\nРучная правка ещё не сохранена. Нажмите «Сохранить изменения»." : selectedCurrent ? " Новые правки не меняют её автоматически." : " Нажмите «Обновить в эфире», чтобы выбрать сохранённую редакцию. Оригинальная запись остаётся."));
            if (selected == null && note.text.trim().isEmpty()) chosen.setText("Добавление станет доступно после расшифровки или сохранения текста.");
            add.setText(selected == null ? "Добавить текст в эфир" : selectedCurrent ? "В эфире" : "Обновить в эфире");
            add.setEnabled((selected == null || !selectedCurrent) && !note.text.trim().isEmpty() && !coordinator.recordingBusy()&&!dirty);
            remove.setVisibility(selected == null ? View.GONE : View.VISIBLE);
            probe.setText(!note.text.trim().isEmpty() || !NoteBook.NOT_REQUESTED.equals(note.recognitionStatus) ? "Повторить расшифровку" : "Расшифровать запись");
            probe.setEnabled(note.hasAudio&&!coordinator.recordingBusy()&&!coordinator.recognitionBusy());
            cancelStt.setVisibility(coordinator.recognitionBusy()&&id.equals(coordinator.recognitionId())?View.VISIBLE:View.GONE);
            delete.setContentDescription("Удалить заметку: "+note.title);
            delete.setEnabled(!coordinator.recordingBusy());applyMode();
        }
    }
    private TextView label(String text,int size){return UiStyle.label(activity,text,size,false);}
    private Button button(String text,Runnable action){return UiStyle.button(activity,text,action);}
    private LayoutParams weight() { return new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }
    private void toast(String message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
    private static String time(long millis) { long s = Math.max(0, millis) / 1000; return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + s % 60; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}

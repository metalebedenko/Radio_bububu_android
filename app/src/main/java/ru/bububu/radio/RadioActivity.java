package ru.bububu.radio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;
import java.util.function.Consumer;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.PlayerController;
import ru.bububu.radio.core.Station;

/** Native Neo navigation; local radio, notes and explicit audio commands share one Service owner. */
public final class RadioActivity extends Activity {
    private static final int RADIO=0,NOTES=1,SOURCES=2,NEWS=3,SETTINGS=4,DOWNLOAD=5;
    private int settingsOrigin=RADIO,newsOrigin=RADIO;
    private TextView navigationTitle,listenStatus,mixState;
    private Button settingsButton,noteMenu;
    private UiStyle.StatusRow voiceReadiness,notesReadiness;
    private LinearLayout settingsHome,settingsAdvancedPage,downloadPage;
    private boolean settingsAdvanced;
    private int downloadOrigin=SOURCES;
    private Button navigationBack,mix;
    private ru.bububu.radio.core.HoldGesture mixGesture;
    private Runnable mixHold;
    private float mixDownX,mixDownY;
    private static final int INK = Color.rgb(24, 53, 41);
    private static final int MUTED = Color.rgb(85, 101, 92);
    private static final int BRAND = Color.rgb(33, 100, 72);
    private static final int SOFT = Color.rgb(234, 241, 233);
    private RadioService service;
    private boolean bound;
    private long dragToken;
    private LinearLayout root;
    private LinearLayout stationStrip;
    private String stationStripKey;
    private final java.util.Map<Button,String> sourceChipStations=new java.util.HashMap<>();
    private final java.util.List<Button> sourceChips=new java.util.ArrayList<>();
    private Button updateAir;private LinearLayout radioDownload,firstDownload;private AlertDialog refreshDialog;private boolean refreshDialogPlaying;
    private PhoneLayout.Navigation phoneNavigation;
    private TextView station, title, position, status, coverage, sentence, voice;
    private SeekBar seek;
    private Button play, previous, next, text, queue, stations, noteForMaterial;
    private NotesPanel notesPanel;
    private ModelsPanel modelsPanel;
    private LinearLayout modelSetupPanel;
    private TextView modelSetupText;
    private Button modelSetupRetry;
    private View[] pages;
    private SourcesPanel newsPage;
    private SourcesManagementPanel sourcesPage;
    private LinearLayout nextUp;
    private Button[] navButtons;
    private boolean diagnosticsFixtures;
    private int selectedTab;
    private boolean activityStarted;
    private Object platformBackCallback;
    private long permissionEpoch, pendingPermission = -1;
    private String pendingStation, pendingArticle, pendingVersion;
    private final Runnable noteObserver = () -> { if(serviceForNotes()==null)return;refreshBundledSetup();refreshAirState(); SourcesPanel.invalidateReaders(this,service); if (notesPanel != null) notesPanel.refresh(); if (newsPage != null && selectedTab == NEWS) newsPage.refresh(); if(sourcesPage!=null&&selectedTab==2)sourcesPage.refresh(); if(modelsPanel!=null&&selectedTab==4)modelsPanel.refresh(); };
    private final Consumer<PlayerController.Snapshot> observer = state -> { if(serviceForNotes()!=null)render(state); };
    private long screenGeneration;
    private ServiceConnection connection;
    private ServiceConnection connectionFor(long generation) {
        return new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!activityStarted || screenGeneration != generation || isFinishing() || isDestroyed()) return;
                RadioService connected = ((RadioService.LocalBinder) binder).service();
                if (!connected.attachScreen(generation,selectedTab==NOTES)) return;
                service = connected;
                service.addListener(observer);
                service.addNoteListener(noteObserver);
                service.refreshVoice();
                if(modelsPanel!=null)modelsPanel.refresh();
                refreshBundledSetup();
                if (selectedTab == NEWS) renderNews();
                if(selectedTab==2&&sourcesPage!=null)sourcesPage.refresh();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (screenGeneration != generation) return;
                service = null; setControlsEnabled(false); status.setText("Сервис остановлен. Откройте приложение снова.");
                refreshBundledSetup();
                if(modelsPanel!=null)modelsPanel.refresh();
            }
        };
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(SourceBootstrap45.blocked(this)){android.widget.TextView message=new android.widget.TextView(this);message.setText("Не удалось подтвердить состояние источников. Ваши файлы сохранены. Закройте приложение и откройте его снова.");message.setTextSize(18);message.setGravity(android.view.Gravity.CENTER);message.setPadding(32,48,32,48);setContentView(message);return;}
        if(Build.VERSION.SDK_INT>=33){android.window.OnBackInvokedCallback callback=this::goBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);platformBackCallback=callback;}
        PhoneInsets.prepare(this);
        selectedTab=state==null||state.getInt("navigationVersion",0)!=2?RADIO:Math.max(0,Math.min(5,state.getInt("tab",0)));
        if(state!=null){
            settingsOrigin=state.getInt("settingsOrigin",RADIO);
            newsOrigin=state.getInt("newsOrigin",RADIO);
            downloadOrigin=state.getInt("downloadOrigin",SOURCES)==RADIO?RADIO:SOURCES;
            settingsAdvanced=state.getBoolean("settingsAdvanced",false);
        }
        buildUi();
    }
    private void buildUi() {
        cancelMixGesture();
        if(modelsPanel!=null)modelsPanel.close();
        UiStyle.Palette palette=UiStyle.palette(this);
        setTheme(palette.bubble?R.style.BubbleTheme:R.style.NeoTheme);
        LinearLayout shell=column();shell.setBackgroundColor(palette.background);PhoneInsets.install(shell);
        LinearLayout header=row();header.setPadding(dp(20),dp(20),dp(20),dp(12));
        navigationBack=UiStyle.iconButton(this,"back","Назад",this::goBack);UiStyle.plain(navigationBack);header.addView(navigationBack,new LinearLayout.LayoutParams(dp(48),dp(48)));
        navigationTitle=UiStyle.heading(this,"Радио Бубубу",22);header.addView(navigationTitle,new LinearLayout.LayoutParams(0,-2,1));
        updateAir=UiStyle.action(this,"Обновить","refresh",this::confirmRefreshAir);updateAir.setTextSize(12);updateAir.setContentDescription("Обновить эфир");LinearLayout.LayoutParams updateLp=new LinearLayout.LayoutParams(-2,dp(48));updateLp.rightMargin=dp(8);header.addView(updateAir,updateLp);
        settingsButton=UiStyle.iconButton(this,"gear","Настройки",()->{if(selectedTab!=SETTINGS){settingsOrigin=selectedTab;settingsAdvanced=false;switchTab(SETTINGS);}});header.addView(settingsButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        noteMenu=UiStyle.iconButton(this,"more","Действия с заметкой",()->notesPanel.showOpenMenu());noteMenu.setRotation(90);header.addView(noteMenu,new LinearLayout.LayoutParams(dp(48),dp(48)));shell.addView(header);
        modelSetupPanel=UiStyle.card(this);modelSetupPanel.addView(UiStyle.heading(this,"Готовим радио",20));
        modelSetupText=UiStyle.label(this,"Подготовка моделей…",14,false);modelSetupText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);modelSetupPanel.addView(modelSetupText);
        modelSetupRetry=button("Повторить подготовку моделей",()->{if(service!=null)service.retryBundledSetup();});modelSetupPanel.addView(modelSetupRetry);firstDownload=column();modelSetupPanel.addView(firstDownload);shell.addView(modelSetupPanel);
        root=column();root.setPadding(dp(20),dp(10),dp(20),dp(24));
        LinearLayout stripCaption=row();stripCaption.addView(UiStyle.label(this,"СТАНЦИИ",11,true),new LinearLayout.LayoutParams(0,-2,1));mixState=UiStyle.label(this,"",11,true);stripCaption.addView(mixState);root.addView(stripCaption);
        stationStrip=row();stationStripKey=null;sourceChips.clear();
        LinearLayout stationRow=row();mix=UiStyle.action(this,"Микс","shuffle",()->{if(service!=null){service.startMix();render(service.player().snapshot());}});mix.setTextSize(13);mix.setContentDescription("Микс: нажать — перемешать и слушать; удерживать 3 секунды — выключить микс");installMixGesture();mix.setMinWidth(0);mix.setMinimumWidth(0);LinearLayout.LayoutParams fixedMix=new LinearLayout.LayoutParams(dp(88),dp(48));fixedMix.rightMargin=dp(8);stationRow.addView(mix,fixedMix);
        android.widget.HorizontalScrollView stripScroll=new android.widget.HorizontalScrollView(this);stripScroll.setHorizontalScrollBarEnabled(false);stripScroll.setContentDescription("Станции");stripScroll.addView(stationStrip);stationRow.addView(stripScroll,new LinearLayout.LayoutParams(0,-2,1));addSpaced(root,stationRow,11);
        radioDownload=column();addSpaced(root,radioDownload,8);
        LinearLayout card=column();
        title=UiStyle.heading(this,"Ваш эфир пока пуст",26);
        title.setMaxLines(4);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(title);
        status=UiStyle.label(this,"",14,true);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);addSpaced(card,status,8);
        sentence=new TextView(this);sentence.setVisibility(View.GONE);
        seek=new TextSeekBar();seek.setMax(1000);seek.setMinimumHeight(dp(48));UiStyle.accent(seek);seek.setContentDescription("Место в тексте, перемотка по предложениям");addSpaced(card,seek,12);
        LinearLayout timeline=row();
        timeline.setGravity(Gravity.TOP);
        station=UiStyle.accentLabel(this,"Ваш эфир",13);
        station.setMaxLines(2);
        station.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams stationWidth=new LinearLayout.LayoutParams(0,-2,1);
        stationWidth.rightMargin=dp(8);
        timeline.addView(station,stationWidth);
        position=UiStyle.label(this,"0%",12,true);
        position.setGravity(Gravity.END);
        timeline.addView(position,new LinearLayout.LayoutParams(0,-2,1));
        card.addView(timeline);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){if(service!=null)service.command(p->dragToken=p.beginSeek());}
            public void onProgressChanged(SeekBar b,int n,boolean user){if(service==null||!user)return;if(dragToken!=0)service.command(p->p.updateSeek(dragToken,n/1000.0));else service.command(p->p.seekToFraction(n/1000.0));}
            public void onStopTrackingTouch(SeekBar b){long t=dragToken;dragToken=0;if(service!=null&&t!=0){service.command(p->p.commitSeek(t));render(service.player().snapshot());}}
        });
        addSpaced(root,card,20);coverage=new TextView(this);
        previous=UiStyle.iconButton(this,"prev","Предыдущая новость",()->command(PlayerController::previousArticle));next=UiStyle.iconButton(this,"next","Следующая новость",()->command(PlayerController::nextArticle));
        play=UiStyle.action(this,"Слушать","play",this::togglePlay);play.setTextSize(16);UiStyle.primary(play);addSpaced(root,new PhoneLayout.Transport(this,previous,play,next),20);
        listenStatus=UiStyle.label(this,"",14,false);
        listenStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        listenStatus.setVisibility(View.GONE);
        addSpaced(root,listenStatus,8);
        LinearLayout secondary=row();text=UiStyle.action(this,"Текст","news",this::showText);noteForMaterial=UiStyle.action(this,"Записать мысль","mic",this::openMaterialNote);text.setTextSize(14);noteForMaterial.setTextSize(14);LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,-2,1);half.rightMargin=dp(8);secondary.addView(text,half);secondary.addView(noteForMaterial,new LinearLayout.LayoutParams(0,-2,1));addSpaced(root,secondary,24);
        UiStyle.divider(root,20);queue=UiStyle.link(this,"Очередь эфира","queue",this::showQueue);UiStyle.plain(queue);LinearLayout.LayoutParams queueLp=new LinearLayout.LayoutParams(-1,-2);queueLp.topMargin=-dp(8);root.addView(queue,queueLp);UiStyle.divider(root,12);nextUp=null;voice=new TextView(this);
        if(newsPage==null)newsPage=new SourcesPanel(this);else detach(newsPage);
        if(sourcesPage==null)sourcesPage=new SourcesManagementPanel(this);else detach(sourcesPage);
        if(notesPanel==null)notesPanel=new NotesPanel(this);else detach(notesPanel);
        UiStyle.themeTree(newsPage);UiStyle.themeTree(sourcesPage);UiStyle.themeTree(notesPanel);
        LinearLayout settingsPage=column();settingsPage.setPadding(dp(20),dp(12),dp(20),dp(24));settingsHome=column();settingsPage.addView(settingsHome);
        addSpaced(settingsHome,UiStyle.label(this,"Оформление",14,true),12);
        LinearLayout skins=row();
        Button neo=button("Нео",()->changeSkin("neo"));
        Button bubble=button("Баббл",()->changeSkin("bubble"));
        neo.setSelected(!palette.bubble);
        bubble.setSelected(palette.bubble);
        UiStyle.primary(palette.bubble?bubble:neo);
        neo.setTextSize(16);
        bubble.setTextSize(16);
        skins.addView(neo,new LinearLayout.LayoutParams(0,-2,1));
        skins.addView(bubble,new LinearLayout.LayoutParams(0,-2,1));
        addSpaced(settingsHome,skins,8);
        addSpaced(settingsHome,UiStyle.label(this,"Готовность",14,true),24);LinearLayout readiness=UiStyle.card(this);readiness.setPadding(dp(12),dp(4),dp(12),dp(4));voiceReadiness=new UiStyle.StatusRow(this,"Голос радио","mic",this::showSettingsAdvanced);notesReadiness=new UiStyle.StatusRow(this,"Расшифровка заметок","news",this::showSettingsAdvanced);readiness.addView(voiceReadiness);UiStyle.divider(readiness,4);readiness.addView(notesReadiness);addSpaced(settingsHome,readiness,8);
        addSpaced(settingsHome,UiStyle.link(this,"Дополнительно\nМодели и диагностика","gear",this::showSettingsAdvanced),20);
        LinearLayout help=UiStyle.card(this);help.setPadding(dp(12),dp(4),dp(12),dp(4));help.addView(UiStyle.link(this,"Как пользоваться","news",this::showUsageHelp));UiStyle.divider(help,2);help.addView(UiStyle.link(this,"О приложении","radio",this::showAbout));UiStyle.divider(help,2);help.addView(UiStyle.link(this,"Лицензии","news",()->modelsPanel.showLicenses()));addSpaced(settingsHome,help,20);
        settingsAdvancedPage=column();modelsPanel=new ModelsPanel(this);settingsAdvancedPage.addView(modelsPanel);settingsAdvancedPage.addView(button("Источники: загрузка и управление",this::showSourceManagement));settingsAdvancedPage.addView(button("Диагностика приложения",this::showDiagnostics));settingsPage.addView(settingsAdvancedPage);syncSettingsView();
        downloadPage=column();downloadPage.setPadding(dp(20),dp(20),dp(20),dp(24));
        pages=new View[]{scroll(root),scroll(notesPanel),scroll(sourcesPage),scroll(newsPage),scroll(settingsPage),scroll(downloadPage)};for(View page:pages){page.setBackgroundColor(palette.background);shell.addView(page,new LinearLayout.LayoutParams(-1,0,1));}
        String[] captions={"Радио","Заметки","Источники"},icons={"radio","mic","sources"};navButtons=new Button[3];for(int i=0;i<3;i++){final int tab=i;navButtons[i]=PhoneLayout.navigation(this,captions[i],icons[i],()->{if(tab==NOTES&&selectedTab!=NOTES)notesPanel.linked(null,null,null);switchTab(tab);});}
        phoneNavigation=new PhoneLayout.Navigation(this,navButtons);shell.addView(phoneNavigation);setContentView(shell);switchTab(selectedTab);refreshReadiness();
        if(service!=null)render(service.player().snapshot());else setControlsEnabled(false);
    }
    private void syncSettingsView(){if(settingsHome!=null){settingsHome.setVisibility(settingsAdvanced?View.GONE:View.VISIBLE);settingsAdvancedPage.setVisibility(settingsAdvanced?View.VISIBLE:View.GONE);}}
    private void showSettingsAdvanced(){settingsAdvanced=true;syncSettingsView();if(modelsPanel!=null)modelsPanel.refresh();}
    private void refreshReadiness(){
        if(voiceReadiness==null||selectedTab!=SETTINGS)return;ru.bububu.radio.models.ModelsController m=ru.bububu.radio.models.ModelsController.get(this);String preparing=service==null?"Подключение…":service.bundledSetupStatus();
        boolean voicePack=preparing==null&&m.ready(ru.bububu.radio.models.PackCatalog.SILERO)!=null,notePack=preparing==null&&m.ready(ru.bububu.radio.models.PackCatalog.GIGAAM)!=null;
        boolean localVoice=service!=null&&service.sileroSelected();
        boolean localNotes=getSharedPreferences("local-model-choice",MODE_PRIVATE).getBoolean("giga-notes",false);
        boolean voiceReady=voicePack&&localVoice,noteReady=notePack&&localNotes;
        voiceReadiness.bind(preparing!=null?preparing:!localVoice?"Выбран голос телефона":voiceReady?"Silero · готов к чтению":"Пакет Silero не готов",voiceReady);
        notesReadiness.bind(preparing!=null?preparing:!localNotes?"Выбрано системное распознавание":noteReady?"GigaAM · готов к расшифровке":"Пакет GigaAM не готов",noteReady);
    }
    void showDownloadScreen(){if(selectedTab!=DOWNLOAD)downloadOrigin=selectedTab==NEWS?NEWS:selectedTab==SOURCES?SOURCES:RADIO;switchTab(DOWNLOAD);}
    void closeDownloadScreen(){switchTab(downloadOrigin);}
    private void refreshDownloadPage(){if(downloadPage==null)return;downloadPage.removeAllViews();if(service==null)downloadPage.addView(UiStyle.label(this,"Подключение…",16,true));else downloadPage.addView(SourceDownloadUi.page(this,service));}
    void refreshNotesNavigation(){if(selectedTab!=NOTES||notesPanel==null||phoneNavigation==null)return;boolean recording=notesPanel.recordingScreen(),detail=notesPanel.detailOpen();navigationTitle.setText(recording?"Новая заметка":detail?"Заметка":"Заметки");navigationBack.setVisibility((detail||recording)&&!notesPanel.recordingActive()?View.VISIBLE:View.GONE);settingsButton.setVisibility(!recording&&!detail?View.VISIBLE:View.GONE);noteMenu.setVisibility(detail&&!recording?View.VISIBLE:View.GONE);phoneNavigation.setVisibility(recording?View.GONE:View.VISIBLE);}
    private String playCaption(PlayerController.Snapshot state){return service!=null&&service.waitingToListen()?"Отменить запуск":state.playing?"Пауза":state.article!=null&&(state.started||state.broadcastEnded||state.passEnded)?"Продолжить":"Слушать";}
    private void detach(View v){if(v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);}
    private void changeSkin(String skin){getSharedPreferences(UiStyle.PREFS,MODE_PRIVATE).edit().putString("skin",skin).apply();if(service!=null&&dragToken!=0){long t=dragToken;dragToken=0;service.command(p->p.cancelSeek(t));}buildUi();}

    void showNewsAll(){if(newsPage!=null)newsPage.setFilter(null);if(selectedTab!=NEWS)newsOrigin=selectedTab;switchTab(NEWS);}
    void showSourceNews(String sourceId,String checkedRecordKey){
        RadioService owner=serviceForNotes();
        if(owner==null || !owner.notesBook().isSourceActive(sourceId))return;
        newsPage.openSource(sourceId);
        if(selectedTab!=NEWS)newsOrigin=selectedTab;
        switchTab(NEWS);
        if(selectedTab==NEWS && checkedRecordKey!=null)newsPage.openSaved(owner,checkedRecordKey);
    }
    void showDownloadedNews(RadioService owner,ru.bububu.radio.sources.SourceCoordinator.DownloadProgress run){
        if(serviceForNotes()!=owner || owner.downloadProgress()!=run)return;
        newsPage.openResult(run.savedRecordKeys);
        if(selectedTab==DOWNLOAD && downloadOrigin!=NEWS)newsOrigin=DOWNLOAD;
        else if(selectedTab!=NEWS && selectedTab!=DOWNLOAD)newsOrigin=selectedTab;
        switchTab(NEWS);
        if(selectedTab==NEWS && run.savedRecordKeys.size()==1)newsPage.openSaved(owner,run.savedRecordKeys.iterator().next());
    }
    boolean openNewsInRadio(RadioService owner,ru.bububu.radio.sources.SourceVersionRecord version){
        if(serviceForNotes()!=owner || owner.notesBook()==null || owner.notesBook().storageUncertain())return false;
        ru.bububu.radio.sources.SourceRecord source=owner.notesBook().source(version.sourceId);
        if(source==null||!owner.notesBook().isSourceActive(source.id))return false;
        ru.bububu.radio.core.NoteBook.Material material=owner.notesBook().material(source.stationId,version.articleId);
        if(material==null||!version.recordKey.equals(material.sourceVersionKey)||!version.versionKey.equals(material.version))return false;
        for(Station station:owner.player().stations()){
            if(!station.id.equals(source.stationId))continue;
            for(Article article:station.articles){
                if(!article.id.equals(version.articleId)||!article.version.equals(version.versionKey))continue;
                if(owner.notesBook().queueMembership(station.id,article,material.sequence,version.recordKey)
                        !=ru.bububu.radio.core.NoteBook.Membership.ALLOWED)return false;
                owner.command(controller->{
                    if(controller.snapshot().playing)controller.pause();
                    controller.selectArticle(station.id,article.id);
                });
                PlayerController.Snapshot selected=owner.player().snapshot();
                if(selected.article==null||selected.playing||!selected.station.id.equals(station.id)
                        ||!selected.article.id.equals(article.id)||!selected.article.version.equals(article.version))return false;
                switchTab(RADIO);return true;
            }
        }
        return false;
    }
    void downloadOneSource(RadioService owner,String sourceId){sourcesPage.downloadOne(owner,sourceId);}
    void showSourceManagement(){switchTab(2);}
    void openAir(){switchTab(0);showQueue();}
    private void showUsageHelp(){
        UiStyle.Palette palette=UiStyle.palette(this);
        LinearLayout body=column();body.setPadding(dp(18),dp(8),dp(18),dp(16));body.setBackgroundColor(palette.background);
        heading(body,"Начать слушать",18);
        label(body,"Откройте «Источники», отметьте нужные станции и нажмите «Загрузить свежие новости». После загрузки выберите «Добавить в эфир». Перейдите в «Радио» и нажмите «Слушать».",15,palette.ink);
        heading(body,"Управлять чтением",18);
        label(body,"«Пауза» приостанавливает чтение. Чтобы продолжить, нажмите «Слушать» или «Продолжить». Кнопки «Новость назад» и «Новость далее» меняют новость. На первой новости кнопка назад переходит к предыдущей непустой станции, на последней кнопка далее ищет следующую непрослушанную новость эфира. В верхней строке видны только станции, где есть материалы вашего эфира. Нажмите название, чтобы выбрать станцию.",15,palette.ink);
        heading(body,"Микс",18);
        label(body,"Короткое нажатие «Микс» перемешивает подходящие полные непрослушанные статьи вашего эфира и начинает чтение. Повторное нажатие заново перемешивает оставшиеся. Выбор источников для скачивания не меняет уже составленный эфир. Чтобы выключить микс, удерживайте кнопку 3 секунды, не сдвигая палец. Текущая новость и место сохранятся: чтение продолжится, а пауза или ошибка останутся без запуска. Кнопка микса закреплена рядом со строкой станций.",15,palette.ink);
        heading(body,"После загрузки",18);
        label(body,"«Добавить в эфир» добавляет материалы без запуска звука. «Не сейчас» оставляет их на телефоне. 100% означает, что обработка закончена. Кнопка «Отчёт по источникам» показывает ошибки и подробности.",15,palette.ink);
        heading(body,"Записать и найти заметку",18);
        label(body,"В «Заметках» нажмите «Записать заметку», затем «Остановить запись». Дождитесь автоматического сохранения записи. Для текста нажмите «Расшифровать запись»; ручную правку сохраните кнопкой «Сохранить изменения». Найти заметку можно в поле «Поиск по названию и тексту».",15,palette.ink);
        heading(body,"Без интернета",18);
        label(body,"Интернет нужен для получения новых материалов. Загруженный эфир доступен без него. Приложение само готовит встроенные голос и распознавание: после подготовки они работают без интернета. Импортировать их отдельно не нужно.",15,palette.ink);
        heading(body,"Особенности демоверсии",18);
        label(body,"На некоторых коротких фрагментах озвучка может остановиться с сообщением «Не подтверждено начало звука». В этом случае выберите другую новость или источник.",15,palette.ink);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(palette.background);scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("Как пользоваться").setView(scroll).setPositiveButton("Закрыть",null).show();
    }

    private void showAbout(){new AlertDialog.Builder(this).setTitle("О приложении / Диагностика").setItems(new String[]{"Сборка "+BuildConfig.VERSION_NAME+" · голоса и восстановление","Встроенные тестовые станции","Очередь тестовых станций · вымышленные"},(d,which)->{if(which==0)showDiagnostics();else if(which==1)showStations(true);else showQueue(true);}).setNegativeButton("Закрыть",null).show();}

    @Override protected void onStart() {
        super.onStart();
        if(SourceBootstrap45.blocked(this))return;
        activityStarted = true;
        if (notesPanel != null) notesPanel.setPageVisible(selectedTab == NOTES);
        screenGeneration=ru.bububu.radio.core.ScreenOwner.newStart();
        connection=connectionFor(screenGeneration);
        bound = bindService(new Intent(this, RadioService.class), connection, BIND_AUTO_CREATE);
    }

    @Override protected void onPause(){
        cancelMixGesture();
        if(notesPanel!=null && selectedTab==NOTES && serviceForNotes()!=null)notesPanel.persistDrafts();
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focus){if(!focus)cancelMixGesture();super.onWindowFocusChanged(focus);}
    @Override protected void onStop() {
        cancelMixGesture();
        if(SourceBootstrap45.blocked(this)){super.onStop();return;}
        activityStarted = false; ++permissionEpoch;
        if(newsPage!=null)newsPage.invalidatePending();
        if(sourcesPage!=null)sourcesPage.leaveView();
        if (notesPanel != null) notesPanel.setPageVisible(false);
        if (service != null) {
            service.removeNoteListener(noteObserver);
            service.removeListener(observer);
            if (service.ownsScreen(screenGeneration)) {
                service.cancelSourceUi();
                if (dragToken != 0) service.command(p -> p.cancelSeek(dragToken));
                service.detachScreen(screenGeneration);
            }
            dragToken = 0;
        }
        if (bound) { unbindService(connection); bound = false; }
        screenGeneration=0;
        service = null;
        super.onStop();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) { super.onActivityResult(request,result,data);if(modelsPanel!=null&&(request==ModelsPanel.SILERO||request==ModelsPanel.GIGAAM||request==ModelsPanel.WAV))modelsPanel.result(request,result,data); }
    @Override protected void onDestroy() { if(Build.VERSION.SDK_INT>=33&&platformBackCallback!=null){getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback)platformBackCallback);platformBackCallback=null;}if(modelsPanel!=null)modelsPanel.close();super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) { if(notesPanel!=null)notesPanel.persistDrafts();state.putInt("navigationVersion",2);state.putInt("tab", selectedTab);state.putInt("settingsOrigin",settingsOrigin);state.putInt("newsOrigin",newsOrigin);state.putInt("downloadOrigin",downloadOrigin);state.putBoolean("settingsAdvanced",settingsAdvanced);super.onSaveInstanceState(state); }
    RadioService serviceForNotes() { return service!=null && service.ownsScreen(screenGeneration)?service:null; }
    private void refreshBundledSetup(){
        boolean visible=selectedTab==RADIO&&BuildConfig.MODEL_EXPERIMENT&&(service==null||service.bundledSetupStatus()!=null||!service.hasRealAir());
        modelSetupPanel.setVisibility(visible?View.VISIBLE:View.GONE);modelSetupText.setText(service==null?"Подключение радио…":service.preparationFacts());modelSetupRetry.setVisibility(service!=null&&service.bundledSetupRetryAvailable()?View.VISIBLE:View.GONE);
    }
    private String refreshExplanation(boolean playing){return "Загрузим свежие материалы из выбранных источников. Завершённые новости удалятся из эфира и сохранённых текстов только при «Добавить в эфир». При загрузке и выборе «Не сейчас» эфир не очищается. Текущий материал, незавершённые новости, заметки, записи и связанный контекст сохранятся.\n\n"+(playing?"Текущий эфир продолжит играть":"Прослушивание запускается отдельно");}
    private void syncRefreshDialog(){if(refreshDialog==null||!refreshDialog.isShowing()||service==null)return;boolean now=service.player().snapshot().playing;if(now!=refreshDialogPlaying){refreshDialogPlaying=now;refreshDialog.setMessage(refreshExplanation(now));refreshDialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("Загрузить");}}
    private void confirmRefreshAir(){
        if(service==null)return;RadioService owner=service;refreshDialogPlaying=owner.player().snapshot().playing;
        refreshDialog=new AlertDialog.Builder(this).setTitle("Обновить эфир?").setMessage(refreshExplanation(refreshDialogPlaying)).setNegativeButton("Отмена",null).setPositiveButton("Загрузить",null).create();
        refreshDialog.setOnShowListener(d->refreshDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(serviceForNotes()!=owner){refreshDialog.dismiss();return;}if(owner.player().snapshot().playing!=refreshDialogPlaying){syncRefreshDialog();return;}refreshDialog.dismiss();owner.refreshAir();showDownloadScreen();}));refreshDialog.show();
    }
    private void goBack(){if(serviceForNotes()!=null)service.cancelPendingListen();if(selectedTab==DOWNLOAD){closeDownloadScreen();return;}if(selectedTab==SETTINGS&&settingsAdvanced){settingsAdvanced=false;syncSettingsView();return;}if(selectedTab==NOTES&&notesPanel!=null&&notesPanel.handleBack())return;if(selectedTab==SETTINGS)switchTab(settingsOrigin);else if(selectedTab==NEWS)switchTab(newsOrigin);else if(notesPanel==null||selectedTab!=NOTES||notesPanel.guardNavigation(()->finish()))finish();}
    // API 28–32 fallback. API 33+ uses the registered platform callback above.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){goBack();}
    private void refreshAirState(){if(service==null||radioDownload==null)return;refreshBundledSetup();renderStationStrip();syncRefreshDialog();updateAir.setVisibility(selectedTab==RADIO?View.VISIBLE:View.GONE);updateAir.setEnabled(service.sources()!=null&&!service.sources().batchRunning());
        radioDownload.removeAllViews();firstDownload.removeAllViews();LinearLayout slot=modelSetupPanel.getVisibility()==View.VISIBLE?firstDownload:radioDownload;slot.addView(SourceDownloadUi.block(this,service,true));
        String message=service.listenMessage();
        if (!android.text.TextUtils.equals(listenStatus.getText(),message)) listenStatus.setText(message);
        listenStatus.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);
        if(service.downloadProgress()==null&&!service.airMessage().isEmpty()&&message.isEmpty())slot.addView(UiStyle.label(this,service.airMessage(),14,true));
        if(play!=null)play.setText(playCaption(service.player().snapshot()));if(mixState!=null)mixState.setText(service.mixEnabled()?"Микс включён":"Микс выключен");if(selectedTab==DOWNLOAD)refreshDownloadPage();if(selectedTab==SETTINGS)refreshReadiness();}
    private void renderStationStrip(){
        if(stationStrip==null||service==null)return;RadioService owner=service;java.util.List<Station> air=new java.util.ArrayList<>();StringBuilder key=new StringBuilder();for(Station value:owner.player().stations())if(visibleAirStation(value)){air.add(value);key.append(value.id).append(':').append(value.name).append('|');}
        if(!key.toString().equals(stationStripKey)){stationStripKey=key.toString();stationStrip.removeAllViews();sourceChips.clear();sourceChipStations.clear();sourceChips.add(mix);
            for(Station value:air){Button chip=button(value.name,()->{if(service!=owner||owner.player().stations().stream().noneMatch(s->s.id.equals(value.id)&&visibleAirStation(s)))return;try{if(owner.mixEnabled())owner.mixEnabled(false);owner.command(p->p.selectStation(value.id));render(owner.player().snapshot());}catch(RuntimeException unavailable){Toast.makeText(this,"Станция пока недоступна. Откройте источники для восстановления.",Toast.LENGTH_LONG).show();}});sourceChipStations.put(chip,value.id);addStationChip(chip);}
            stations=button("Все станции…",()->showStations(false));addStationChip(stations);
        }
        for(Button chip:sourceChips){boolean selected=chip==mix?owner.mixEnabled():!owner.mixEnabled()&&owner.player().snapshot().station.id.equals(sourceChipStations.get(chip));UiStyle.chip(chip,selected);chip.setTextSize(13);String stationId=sourceChipStations.get(chip);chip.setEnabled(stationId==null||owner.player().stations().stream().anyMatch(value->value.id.equals(stationId)));}
    }
    private void addStationChip(Button chip){chip.setMinHeight(dp(48));chip.setMinimumHeight(dp(48));chip.setMinWidth(dp(80));chip.setMaxWidth(dp(220));chip.setMaxLines(2);chip.setEllipsize(android.text.TextUtils.TruncateAt.END);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.rightMargin=dp(6);stationStrip.addView(chip,lp);sourceChips.add(chip);}
    private void cancelMixGesture(){if(mixGesture!=null)mixGesture.cancel();if(mix!=null){if(mixHold!=null)mix.removeCallbacks(mixHold);mix.setPressed(false);}}
    private void installMixGesture(){
        Button target=mix;
        mixGesture=new ru.bububu.radio.core.HoldGesture(target::performClick,()->{if(service!=null){service.stopMix();render(service.player().snapshot());}});
        mixHold=()->{if(target==mix&&target.isAttachedToWindow()&&target.isEnabled()&&target.hasWindowFocus()&&selectedTab==RADIO)mixGesture.tick(android.os.SystemClock.uptimeMillis());else cancelMixGesture();if(!mixGesture.active())target.setPressed(false);};
        final int slop=android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        target.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View view){}public void onViewDetachedFromWindow(View view){cancelMixGesture();}});
        target.setOnTouchListener((view,event)->{
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:cancelMixGesture();if(!target.isEnabled()||selectedTab!=RADIO)return false;mixDownX=event.getX();mixDownY=event.getY();mixGesture.down(event.getEventTime());target.setPressed(true);target.postDelayed(mixHold,ru.bububu.radio.core.HoldGesture.HOLD_MS);return true;
                case MotionEvent.ACTION_MOVE:if(event.getPointerCount()!=1||Math.abs(event.getX()-mixDownX)>slop||Math.abs(event.getY()-mixDownY)>slop||event.getX()<0||event.getY()<0||event.getX()>=target.getWidth()||event.getY()>=target.getHeight())cancelMixGesture();return true;
                case MotionEvent.ACTION_UP:target.removeCallbacks(mixHold);if(!target.isEnabled()||!target.hasWindowFocus()||selectedTab!=RADIO)mixGesture.cancel();target.setPressed(false);mixGesture.up(event.getEventTime());return true;
                default:cancelMixGesture();return true;
            }
        });
    }

    private void switchTab(int tab) {if(selectedTab==NOTES&&tab!=NOTES&&notesPanel!=null&&!notesPanel.guardNavigation(()->applyTab(tab)))return;applyTab(tab);}
    private void applyTab(int tab) {
        if(tab!=RADIO)cancelMixGesture();
        if (pages == null || (service!=null && serviceForNotes()==null)) return;
        if (selectedTab == NOTES && tab != NOTES) ++permissionEpoch;
        if(selectedTab==NEWS&&tab!=NEWS&&newsPage!=null)newsPage.invalidatePending();
        if(selectedTab==2&&tab!=2&&sourcesPage!=null)sourcesPage.leaveView();
        if ((selectedTab==NEWS||selectedTab==SOURCES||selectedTab==DOWNLOAD)&&tab!=selectedTab&&!((selectedTab==SOURCES||selectedTab==NEWS)&&tab==DOWNLOAD||selectedTab==DOWNLOAD&&(tab==SOURCES||tab==NEWS))&&service!=null)service.cancelSourceUi();
        if(tab!=selectedTab&&tab!=DOWNLOAD&&service!=null)service.cancelPendingListen();selectedTab = tab;
        navigationTitle.setTextSize(tab==NOTES||tab==SOURCES||tab==SETTINGS?25:22);
        phoneNavigation.setVisibility(View.VISIBLE);settingsButton.setVisibility(tab<=SOURCES?View.VISIBLE:View.GONE);noteMenu.setVisibility(View.GONE);navigationBack.setVisibility(tab==NEWS||tab==SETTINGS||tab==DOWNLOAD?View.VISIBLE:View.GONE);navigationTitle.setText(tab==RADIO?"Радио Бубубу":tab==NOTES?"Заметки":tab==SOURCES?"Источники":tab==NEWS?"Новости":tab==DOWNLOAD?"Загрузка новостей":"Настройки");
        for (int i = 0; i < pages.length; i++) pages[i].setVisibility(i == tab ? View.VISIBLE : View.GONE);
        notesPanel.setPageVisible(tab == NOTES && activityStarted);
        if (service != null && activityStarted) service.updateNotesPage(screenGeneration,tab==NOTES);
        if (tab == NEWS) renderNews();
        if (tab == 2 && sourcesPage != null) sourcesPage.refresh();
        if (tab == 4 && modelsPanel != null){modelsPanel.refresh();refreshReadiness();}
        if(tab==DOWNLOAD)refreshDownloadPage();if(tab==NOTES)refreshNotesNavigation();
        if(navButtons!=null)for(int i=0;i<navButtons.length;i++)UiStyle.navigation(navButtons[i],i==rootFor(tab));refreshBundledSetup();if(updateAir!=null)updateAir.setVisibility(tab==RADIO?View.VISIBLE:View.GONE);
    }
    private int rootFor(int tab){
        int parent=tab==SETTINGS?settingsOrigin:tab;
        // NEWS and DOWNLOAD may be entered from one another. Their recorded roots stop at a main tab.
        for(int i=0;i<3;i++){
            if(parent==NEWS)parent=newsOrigin;
            else if(parent==DOWNLOAD)parent=downloadOrigin;
            else break;
        }
        return parent>=RADIO&&parent<=SOURCES?parent:RADIO;
    }
    private void renderNews() { if (newsPage != null) newsPage.refresh(); }
    void requestNoteRecording(String station, String article, String version) {
        if (!activityStarted || selectedTab != NOTES || serviceForNotes() == null || service.notes() == null) return;
        notesPanel.microphoneProblem(false);pendingStation = station; pendingArticle = article; pendingVersion = version;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginNoteRecording();
        else { pendingPermission = ++permissionEpoch; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 104); }
    }
    void cancelNoteStart(){++permissionEpoch;pendingPermission=-1;}
    private void beginNoteRecording() {
        if (!activityStarted || selectedTab != NOTES || serviceForNotes()==null||notesPanel==null||!notesPanel.preparedForRecording()) return;
        if (!service.startNoteRecording("Заметка " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date()),
                pendingStation, pendingArticle, pendingVersion)) Toast.makeText(this, "Запись не начата. Проверьте статус заметок.", Toast.LENGTH_LONG).show();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != 104) return;
        if (pendingPermission != permissionEpoch || !activityStarted || selectedTab != NOTES) return;
        pendingPermission = -1; ++permissionEpoch;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) beginNoteRecording();
        else {notesPanel.microphoneProblem(true);Toast.makeText(this, "Нет разрешения на микрофон. Запись не начата.", Toast.LENGTH_LONG).show();}
    }

    private void togglePlay() {
        if (!activityStarted || !hasWindowFocus() || isFinishing() || isDestroyed() || serviceForNotes()==null) return;
        if(service.waitingToListen()){service.cancelSourceUi();refreshAirState();return;}
        if (service.player().snapshot().playing) service.command(PlayerController::pause);
        else {
            // Starting the service makes it survive unbinding when the screen leaves the foreground.
            service.requestPlay();
        }
    }

    private void command(Consumer<PlayerController> action) {
        if (service != null) service.command(action);
    }

    private boolean hasVisibleMaterial(PlayerController.Snapshot state) {
        return state.article != null && (!fixture(state.station.id)||diagnosticsFixtures||state.playing);
    }

    private void openMaterialNote() {
        RadioService owner=service;if(owner==null||!hasVisibleMaterial(owner.player().snapshot()))return;
        owner.command(PlayerController::pause);
        if(service!=owner)return;
        PlayerController.Snapshot current=owner.player().snapshot();if(!hasVisibleMaterial(current))return;
        notesPanel.linked(current.station.id,current.article.id,current.article.version);notesPanel.prepareRecording();switchTab(NOTES);
    }

    private void render(PlayerController.Snapshot state) {
        setControlsEnabled(service != null);
        if(service==null)return;
        refreshAirState();if(service!=null&&service.takeNotificationPermissionDue()&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(MODE_PRIVATE).getBoolean("notificationsAsked",false)){getPreferences(MODE_PRIVATE).edit().putBoolean("notificationsAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},1);}
        refreshBundledSetup();
        if(modelsPanel!=null&&selectedTab==4)modelsPanel.refresh();
        station.setText((fixture(state.station.id) ? "Тестовая станция · " : "") + state.station.name);
        title.setText(state.selectionRequired ? "Выберите текст в «Моём эфире»" : state.article == null ? state.station.articles.isEmpty() ? "В этой станции пока нет новостей" : "Выбранный эфир завершён" : state.article.title);
        play.setText(playCaption(state));
        play.setCompoundDrawablesWithIntrinsicBounds(new UiIcon(this,state.playing?"pause":"play",UiStyle.iconColors(this,true),22),null,null,null);
        previous.setEnabled(state.canPreviousArticle); next.setEnabled(state.canNextArticle);
        boolean hasText = hasVisibleMaterial(state);
        text.setEnabled(hasText); seek.setEnabled(hasText);noteForMaterial.setEnabled(hasText);
        boolean selectedTexts=service!=null&&visibleAirStation(state.station);
        boolean anyAir=service!=null&&service.player().stations().stream().anyMatch(this::visibleAirStation);
        boolean readyAir=!hasText&&service.hasReadyAirForListen();
        if(!hasText){
            if(!selectedTexts)station.setText("Ваш эфир");
            title.setText(readyAir?"Материалы эфира готовы":!anyAir?"Ваш эфир пока пуст":state.selectionRequired?"Выберите текст в «Моём эфире»":selectedTexts?"В станции выбрано текстов: "+state.station.articles.size():"Выберите текст в «Моём эфире»");
        }
        // Appending intentionally retains a boundary. Explicit Play already resolves its candidate.
        play.setEnabled(service!=null&&(service.waitingToListen()||service.canPrepareAir()||readyAir||!state.selectionRequired&&(hasText||selectedTexts||state.broadcastEnded&&anyAir)));
        double fraction = hasText ? (double) state.textOffset / state.article.text.length() : 0;
        if (dragToken == 0 || !state.seeking) seek.setProgress((int) Math.round(fraction * 1000));
        position.setText(hasText ? String.format(Locale.ROOT, "%.0f%% · предложение %d/%d",
                fraction*100,Math.min(state.sentenceIndex+1,state.article.sentences.size()),state.article.sentences.size()) : "Очередь станции пуста");
        status.setText(service!=null&&service.mixEnabled()&&"Нет непрослушанных статей".equals(service.airMessage())?service.airMessage():state.error == null ? state.status : state.error);if(service!=null&&service.mixEnabled())status.append(" · микс");
        String heard = state.listened ? "Прослушана полностью" : state.started ? "Начата" : "Не прослушана";
        coverage.setText(hasText ? heard + " · " + state.completedSentences.size() + "/" + state.article.sentences.size()
                + (state.read ? " · Прочитана" : " · Не прочитана") : "Ничего не подбирается автоматически");
        if (hasText && service != null && service.notesBook() != null) for (ru.bububu.radio.core.NoteBook.Material m : service.notesBook().materials()) {
            if (m.sourceVersionKey != null && m.stationId.equals(state.station.id) && m.articleId.equals(state.article.id)) {
                ru.bububu.radio.sources.SourceVersionRecord v = service.notesBook().sourceVersion(m.sourceVersionKey);
                if (v != null) {coverage.append(" · " + ru.bububu.radio.sources.ChannelProfile.label(v)+" · "+SourcesPanel.quality(v.completeness));if(v.publication!=null)coverage.append("\n"+ru.bububu.radio.sources.ChannelProfile.publicationLabel(v));}
            }
        }
        if (hasText) {
            int anchor = Math.min(state.sentenceIndex, state.article.sentences.size() - 1);
            String preview = state.article.sentences.get(anchor).text;
            sentence.setText(preview.length() > 230 ? preview.substring(0, 230) + "…" : preview);
        } else sentence.setText(selectedTexts?"Нажмите «Слушать» или выберите текст в «Моём эфире».":anyAir?"В эфире уже есть выбранные материалы.":"Добавьте источник и сохраните выбранный текст в эфир.");
        if (service != null) voice.setText(service.voiceDescription());
        renderNext(state);
    }

    private static boolean fixture(String id) { return id.equals("A") || id.equals("B") || id.equals("C"); }
    private boolean visibleAirStation(Station station){return ru.bububu.radio.core.OrdinaryRadio.inAir(station,service::isRetiredStation);}
    void chooseStationAfterDeletion() { showStations(false); }
    private void showStations() { showStations(false); }
    private void showStations(boolean testStations) {
        if (service == null) return;
        RadioService owner = service;if(testStations)owner.exposeDiagnosticSamples();
        java.util.List<Integer> indices = new java.util.ArrayList<>();
        java.util.List<String> choices = new java.util.ArrayList<>();
        int checked = -1;
        for (int i = 0; i < owner.player().stations().size(); i++) {
            Station s = owner.player().stations().get(i);
            if (fixture(s.id) != testStations || owner.isRetiredStation(s.id) || !testStations&&s.articles.isEmpty()) continue;
            if (i == owner.player().snapshot().stationIndex) checked = choices.size();
            indices.add(i); choices.add(s.name + " · " + s.articles.size() + " текстов");
        }
        AlertDialog.Builder chooser=new AlertDialog.Builder(this).setTitle(testStations ? "Встроенные тестовые станции" : "Мой эфир · порядок станций")
                .setSingleChoiceItems(choices.toArray(new String[0]), checked, (dialog, which) -> {
                    diagnosticsFixtures=testStations;
                    if (service == owner){if(testStations)owner.selectDiagnosticSample(indices.get(which));else {if(owner.mixEnabled())owner.mixEnabled(false);owner.command(p -> p.selectStation(indices.get(which)));render(owner.player().snapshot());}}
                    dialog.dismiss();
                }).setPositiveButton(testStations ? "Закрыть" : "Добавить источник", (d,w) -> { if (!testStations) AddSourceDialog.show(this); })
                .setNegativeButton("Отмена", null);
        if(choices.isEmpty())chooser.setMessage("В эфире пока нет выбранных текстов. Загрузите новости из источников или добавьте собственную заметку.");
        chooser.show();
    }

    private void showQueue() { showQueue(false); }
    private void showQueue(boolean testStations) {
        if (service == null) return;
        RadioService owner = service;if(testStations)owner.exposeDiagnosticSamples();
        LinearLayout list = column();
        list.setPadding(dp(16), dp(6), dp(16), dp(16));
        PlayerController.SavedState saved = owner.player().savedState();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(testStations?"Очередь тестовых станций":"Мой эфир")
                .setView(scroll(list)).setNegativeButton("Закрыть", null).create();
        if(!testStations){
            Button stationChooser=button("Выбрать станцию",()->{dialog.dismiss();showStations();});stationChooser.setTag("station-chooser");stationChooser.setContentDescription("Выбрать станцию");list.addView(stationChooser);
            Button clear=button("Очистить эфир",()->{dialog.dismiss();confirmClearAir(owner);});clear.setEnabled(owner.notesBook()!=null&&!owner.notesBook().materials().isEmpty());addSpaced(list,clear,8);
        }
        int shown=0;
        for (int s = 0; s < owner.player().stations().size(); s++) {
            Station station = owner.player().stations().get(s);
            if (fixture(station.id) != testStations || owner.isRetiredStation(station.id) || !testStations&&station.articles.isEmpty()) continue;
            shown++;heading(list, station.name, 16);
            if (station.articles.isEmpty()) label(list, "Пока нет новостей", 13, MUTED);
            for (int a = 0; a < station.articles.size(); a++) {
                Article article = station.articles.get(a);
                PlayerController.SavedProgress p = saved.progress.get(PlayerController.progressKey(station.id, article.id, article.version));
                String mark = p.completedSentences.size() == article.sentences.size() ? "Прослушана" : p.started ? "Начата" : "Не прослушана";
                Button item = button(article.title + "\n" + mark + " · " + p.completedSentences.size() + "/" + article.sentences.size()
                        + (p.read ? " · Прочитана" : ""), () -> {
                    if (service == owner) {
                        if(testStations)owner.selectDiagnosticSample(station.id,article.id);else owner.command(controller -> controller.selectArticle(station.id, article.id));
                    }
                    dialog.dismiss();
                });
                list.addView(item);
                if(!testStations&&owner.notesBook()!=null){ru.bububu.radio.core.NoteBook.Material selected=owner.notesBook().material(station.id,article.id);
                    if(selected!=null&&selected.noteId!=null){String noteId=selected.noteId;long sequence=selected.sequence;String selectedVersion=selected.version;
                        Button remove=button("Убрать из эфира",()->{
                            if(service!=owner)return;ru.bububu.radio.core.NoteBook.Material current=owner.notesBook().material(station.id,article.id);
                            if(current==null||current.sequence!=sequence||!current.version.equals(selectedVersion)){toast("Выбранная версия изменилась. Откройте эфир заново.");return;}
                            try{owner.removeNoteFromAir(noteId);dialog.dismiss();showQueue();}catch(RuntimeException failure){toast("Удаление из эфира не подтверждено. Заметка и запись сохранены.");}
                        });remove.setContentDescription("Убрать из эфира: "+article.title);addSpaced(list,remove,6);
                    }
                }
                if(owner.notesBook()!=null)for(ru.bububu.radio.sources.SourceRecord source:owner.notesBook().sources())if(source.stationId.equals(station.id))
                    for(ru.bububu.radio.sources.SourceItem sourceItem:owner.notesBook().sourceItems(source.id))if(sourceItem.articleId.equals(article.id))
                        list.addView(button("Удалить новость",()->{dialog.dismiss();SourcesPanel.confirmDeleteItem(this,owner,source.id,sourceItem.itemKey,sourceItem.title);}));
            }
        }
        if(shown==0&&!testStations){
            heading(list,"Ваш эфир пока пуст",18);label(list,"Загрузите новости в «Источниках» и добавьте выбранные тексты в эфир. Можно добавить и собственную заметку.",14,MUTED);
            list.addView(button("Открыть источники",()->{dialog.dismiss();showSourceManagement();}));
        }
        dialog.show();
    }

    private void confirmClearAir(RadioService owner){
        if(service!=owner||owner.notesBook()==null)return;
        ru.bububu.radio.core.NoteBook book=owner.notesBook();int count=book.materials().size();if(count==0)return;
        long revision=book.revision(),authority=book.authorityEpoch();
        new AlertDialog.Builder(this).setTitle("Убрать все тексты из эфира?")
                .setMessage("Выбрано материалов: "+count+". Из всех станций, включая заметки.\nСкачанные новости и ваши заметки останутся.")
                .setPositiveButton("Очистить эфир",(d,w)->{
                    if(service!=owner||isFinishing())return;
                    if(owner.notesBook()!=book||book.revision()!=revision||book.authorityEpoch()!=authority){toast("Состав эфира изменился. Откройте подтверждение заново.");return;}
                    try{boolean changed=owner.clearAir();toast(changed?"Тексты убраны из эфира. Новости и заметки сохранены.":"Эфир уже пуст.");showQueue();}
                    catch(ru.bububu.radio.sources.SourceCoordinator.ProjectionPendingException pending){toast("Изменение сохранено; отображение эфира требует восстановления. Откройте приложение снова.");}
                    catch(RuntimeException failure){toast("Очистка эфира не подтверждена. Сохранённые материалы не удаляйте; попробуйте после восстановления.");}
                }).setNegativeButton("Отмена",null).show();
    }
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}

    private void showText() {
        if (service == null || service.player().snapshot().article == null) return;
        RadioService owner = service;
        PlayerController.Snapshot state = owner.player().snapshot();
        Article article = state.article;
        String stationId = state.station.id;
        if(owner.notesBook()!=null && !ru.bububu.radio.core.ReadAdmission.allowed(owner.notesBook(),stationId,article)) {
            Toast.makeText(this,"Эта новость удалена или её сохранение не подтверждено. Выберите другой текст.",Toast.LENGTH_LONG).show();return;
        }
        if (owner.notesBook() != null) for (ru.bububu.radio.core.NoteBook.Material m : owner.notesBook().materials()) {
            if (m.sourceVersionKey != null && m.stationId.equals(stationId) && m.articleId.equals(article.id)) {
                ru.bububu.radio.sources.SourceVersionRecord version = owner.notesBook().sourceVersion(m.sourceVersionKey);
                if (version != null && version.spokenText != null) { SourcesPanel.showReader(this, owner, version,coverage.getText()+"\nПредложение "+Math.min(state.sentenceIndex+1,state.article.sentences.size())+"/"+state.article.sentences.size()); return; }
            }
        }
        LinearLayout body = column();
        body.setPadding(dp(18), dp(8), dp(18), dp(20));
        label(body,coverage.getText()+"\nПредложение "+Math.min(state.sentenceIndex+1,state.article.sentences.size())+"/"+state.article.sentences.size(),13,UiStyle.palette(this).muted);
        for (Article.Sentence item : article.sentences) label(body, item.text, 16, INK).setTextIsSelectable(true);
        new AlertDialog.Builder(this).setTitle(article.title).setView(scroll(body))
                .setPositiveButton(state.read ? "Снять «прочитана»" : "Отметить прочитанной", (dialog, which) -> {
                    if (service == owner) {
                        // Automatic playback may have moved on while the text dialog was open.
                        owner.setArticleRead(stationId, article, !owner.player().savedState().progress.get(PlayerController.progressKey(stationId, article.id, article.version)).read);
                    }
                }).setNegativeButton("Закрыть", null).show();
    }

    private void showDiagnostics() {
        if (service == null) return;
        String info = service.diagnostics();
        new AlertDialog.Builder(this).setTitle("Проверка сборки и голос").setMessage(info)
                .setPositiveButton("Настройки TTS", (dialog, which) -> {
                    Intent settings = new Intent("com.android.settings.TTS_SETTINGS");
                    if (settings.resolveActivity(getPackageManager()) != null) startActivity(settings);
                    else startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                }).setNeutralButton("Копировать", (dialog, which) -> getSystemService(ClipboardManager.class)
                        .setPrimaryClip(ClipData.newPlainText("Бубубу · диагностика", info)))
                .setNegativeButton("Закрыть", null).show();
    }

    private void setControlsEnabled(boolean enabled) {
        for(Button chip:sourceChips)chip.setEnabled(enabled);
        for (View view : new View[]{play, previous, next, text, queue, stations, seek, noteForMaterial,updateAir,mix}) {
            if (view != null) view.setEnabled(enabled);
        }
    }

    private final class TextSeekBar extends SeekBar {
        TextSeekBar() { super(RadioActivity.this); }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                long token = dragToken; dragToken = 0;
                if (service != null && token != 0) {
                    service.command(p -> p.cancelSeek(token));
                    render(service.player().snapshot());
                }
            }
            boolean handled = super.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            return handled;
        }
        @Override public boolean performClick() { return super.performClick(); }
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private ScrollView scroll(View child) { ScrollView view = new ScrollView(this);view.setFillViewport(true); view.addView(child); return view; }
    private TextView heading(LinearLayout parent, String text, int size) { TextView view = label(parent, text, size, INK); view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view; }
    private TextView label(LinearLayout parent, String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); UiStyle.Palette palette=UiStyle.palette(this);view.setTextColor(color==MUTED||color==palette.muted?palette.muted:color==BRAND||color==palette.brand?palette.brand:palette.ink);
        view.setPadding(0, dp(6), 0, dp(6)); view.setLineSpacing(dp(2), 1f); parent.addView(view); return view;
    }
    private Button button(String text,Runnable action){return UiStyle.button(this,text,action);}
    private void renderNext(PlayerController.Snapshot state){
        if(nextUp==null||service==null)return;nextUp.removeAllViews();heading(nextUp,"В этой станции далее",17);int from=state.articleIndex+1,shown=0;
        if(!fixture(state.station.id)||diagnosticsFixtures)for(int i=Math.max(0,from);i<state.station.articles.size()&&shown<3;i++){
            Article a=state.station.articles.get(i);nextUp.addView(button(a.title,()->service.command(p->p.selectArticle(state.station.id,a.id))));shown++;
        }
        if(shown==0)label(nextUp,"Следующих текстов здесь пока нет. Добавьте новости в эфир.",13,UiStyle.palette(this).muted);
    }
    private void addSpaced(LinearLayout parent, View child, int gap) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); p.topMargin = dp(gap); parent.addView(child, p); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}

package ru.bububu.radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.File;
import ru.bububu.radio.sources.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import ru.bububu.radio.core.PlayerController;
import ru.bububu.radio.core.AirMix;
import ru.bububu.radio.core.QuickStart;
import ru.bububu.radio.core.OrdinaryRadio;
import ru.bububu.radio.core.SourceDefaults45;
import ru.bububu.radio.core.DownloadPresentation;
import ru.bububu.radio.core.PausedSessionLease;
import ru.bububu.radio.core.SystemCommandGate;
import ru.bububu.radio.core.SystemMediaTitle;
import ru.bububu.radio.core.StartedRequestOwner;
import ru.bububu.radio.core.ApplicationPlayGate;
import ru.bububu.radio.core.NoteBook;
import ru.bububu.radio.core.NoteCoordinator;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.Station;
import ru.bububu.radio.core.PlaybackMembership;
import ru.bububu.radio.models.ModelsController;
import ru.bububu.radio.models.ModelPackStore;
import ru.bububu.radio.models.BundledDefaults;
import ru.bububu.radio.sources.SourceLimits;

/** The service, rather than an Activity, owns speech, focus, persistence and the media session. */
public final class RadioService extends Service implements SpeechRouter.Host {
    static final String PLAY = "ru.bububu.radio.PLAY";
    private static final String APP_PLAY = "ru.bububu.radio.APP_PLAY";
    private static final String APP_REQUEST_ID = "ru.bububu.radio.APP_REQUEST_ID";
    private static final String APP_LISTEN_EPOCH = "ru.bububu.radio.APP_LISTEN_EPOCH";
    private static final String SESSION_ID = "ru.bububu.radio.SESSION_ID";
    static final String PAUSE = "ru.bububu.radio.PAUSE";
    static final String PREVIOUS = "ru.bububu.radio.PREVIOUS";
    static final String NEXT = "ru.bububu.radio.NEXT";
    static final String STOP = "ru.bububu.radio.STOP";
    private static final String CHANNEL = "radio-playback";
    private static final int NOTIFICATION_ID = 1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private final List<Consumer<PlayerController.Snapshot>> listeners = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private PlayerController player;
    private List<Station> diagnosticFixtures = java.util.Collections.emptyList();
    private final AirMix airMix=new AirMix(new java.util.Random());
    private final QuickStart quickStart=new QuickStart();
    private String listenCode="",listenMessage="";
    private boolean listenUpdateQueued;
    String listenMessage(){return listenMessage;}
    private void listenStatus(String code,String message) {
        if (code.equals(listenCode) && message.equals(listenMessage)) return;
        listenMessage=message;
        listenCode=code;
        // Fixed codes only. Do not attach source, note, request id or audio text.
        android.util.Log.i("BububuListen",code);
        events.add(SystemClock.elapsedRealtime()+" listen-"+code);
        if(events.size()>200) events.remove(0);
        if(!listenUpdateQueued&&!listeners.isEmpty()){
            listenUpdateQueued=true;
            handler.post(()->{listenUpdateQueued=false;if(!destroyed&&player!=null)emitToScreen(player.snapshot());});
        }
    }
    private long selectionGeneration,voiceGeneration;
    private String explicitAirSource,airMessage="";
    private boolean explicitStartArmed,notificationPermissionDue,manualNonSource,diagnosticSamplesExposed,diagnosticSampleSelected;
    private SourceCoordinator.DownloadProgress lastDownloadProgress;
    private java.util.Set<String> failedAirSources=java.util.Collections.emptySet();
    private SpeechRouter speech;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean focused;
    private int focusGeneration;
    private MediaSession session;
    private PowerManager.WakeLock wakeLock;
    private boolean foreground;
    private boolean notificationVisible;
    private boolean systemClosed;
    private boolean destroyed;
    private int sessionGeneration;
    private SystemCommandGate systemCommands;
    private final StartedRequestOwner startedRequests = new StartedRequestOwner(this::stopSelfResult);
    private PausedSessionLease pausedSessionLease;
    private Runnable idleCloseTask;
    private final Handler notesHandler = new Handler(Looper.getMainLooper());
    private final List<Runnable> noteListeners = new ArrayList<>();
    private AndroidNoteStorage noteStorage;
    private NoteBook noteBook;
    private NoteCoordinator notes;
    private final ApplicationPlayGate applicationPlay = new ApplicationPlayGate(
            () -> notes == null ? 0 : notes.audioCommandRevision());
    private AndroidNoteAudio noteAudio;
    private AndroidOnDeviceProbe noteRecognition;
    private AndroidFileTranscriber fileTranscriber;
    private ModelPackStore.Token noteModelTask;
    private long noteRecognitionEpoch;
    private boolean gigaNotes;
    private ModelPackStore.Token bundledTask;
    private String bundledSetupStatus;
    private boolean bundledSetupReady, bundledSetupFailed, systemVoiceExplicit, systemNotesExplicit;
    private long bundledSetupEpoch;
    private String notesProblem;
    private SourceCoordinator sources;
    private String sourcesProblem;
    private LocalData radioStore;
    private ru.bububu.radio.core.RadioStateAuthority radioAuthority;
    private boolean historyFailed, deletionCurrentAffected;
    private AudioFocusRequest noteFocus;
    private int noteFocusGeneration;
    private final ru.bububu.radio.core.ScreenOwner screenOwner=new ru.bububu.radio.core.ScreenOwner();
    private boolean activityVisible, notesPage;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction()) && player != null) {
                event("headphones-disconnected", "");
                cancelPendingListen();
                player.interrupt("Наушники отключены. Продолжите по Play.");
                if (notes != null) notes.interrupt();
            }
        }
    };

    public final class LocalBinder extends Binder { RadioService service() { return RadioService.this; } }

    @Override public void onCreate() {
        super.onCreate();
        if(SourceBootstrap45.blocked(this)){stopSelf();return;}
        audio = getSystemService(AudioManager.class);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bububu:Reading");
        wakeLock.setReferenceCounted(false);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Чтение радио", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Управление чтением локальных текстов");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        pausedSessionLease = new PausedSessionLease((delay, task) -> {
            idleCloseTask = task;
            handler.postDelayed(task, delay);
            return () -> handler.removeCallbacks(task);
        }, SystemClock::elapsedRealtime, this::stopPlayback);
        systemCommands = new SystemCommandGate(() -> !destroyed && !systemClosed && session != null
                && foreground && notificationVisible, pausedSessionLease::closeIfExpired);
        createMediaSession();
        speech = new SpeechRouter(this, handler, this);
        if (AndroidNoteAudio.isOutputBlocked()) {
            notesProblem = "Остановка предыдущего аудио не подтверждена. Завершите запись или остановите приложение в настройках Android.";
        } else if (AndroidNoteAudio.isRecordingStorageBusy()) {
            notesProblem = "Предыдущая запись ещё сохраняется. Закройте и снова откройте приложение через несколько секунд.";
        } else try {
            noteStorage = new AndroidNoteStorage(this);
            noteBook = new NoteBook(noteStorage);
            noteBook.enable45();
            noteStorage.recover(noteBook);
        } catch (RuntimeException failure) {
            notesProblem = "Хранилище заметок недоступно. Файлы сохранены; новые изменения заблокированы.";
            noteBook = null;
        }
        radioStore = new LocalData(this);
        radioAuthority = new ru.bububu.radio.core.RadioStateAuthority(radioStore, saved -> {
            try { return noteBook == null ? saved : noteBook.reconcileState(saved); }
            catch (RuntimeException invalid) { notesProblem = "Состояние эфира не согласуется с сохранёнными текстами. Файлы сохранены; изменения заблокированы."; throw invalid; }
        }, saved -> {
            if (notesProblem != null || noteBook != null && noteBook.storageUncertain()) throw new IllegalStateException("Preserve state while notes catalog is unreadable or uncertain");
            return noteBook == null ? saved : noteBook.encodeCursorProvenance(saved);
        });
        PlayerController.StateStore combinedState = new PlayerController.StateStore() {
            @Override public PlayerController.SavedState load() {
                return radioAuthority.load();
            }
            @Override public void save(PlayerController.SavedState saved) {
                saved=OrdinaryRadio.preserveLegacy(saved,radioAuthority.persisted());
                radioAuthority.save(saved);
                // The Book bridge sees only completed, successfully written state. TTS onStart
                // and live snapshots never count as listening. Its failure cannot undo JSON.
                if (noteBook != null) try { noteBook.captureSourceHistory(saved); historyFailed = false; }
                catch (RuntimeException failure) { historyFailed = true; sourcesProblem = "Не удалось сохранить историю источников. Очистка временно заблокирована."; }
            }
        };
        android.content.SharedPreferences starterChoice=getSharedPreferences(UiStyle.PREFS,0);
        if(noteBook!=null&&notesProblem==null&&SourceDefaults45.mayUpgradeStarter(noteBook,starterChoice.contains("batch_source_ids"),starterChoice.getBoolean("starter_mix_upgrade_pending",false)))try{
            if(!starterChoice.edit().putBoolean("starter_mix_upgrade_pending",true).commit())throw new IllegalStateException("Starter intent not persisted");
            java.util.Set<String> chosen=SourceDefaults45.upgradeLegacyStarter(noteBook);android.content.SharedPreferences.Editor selection=starterChoice.edit().remove("starter_mix_upgrade_pending");if(chosen!=null)selection.putStringSet("batch_source_ids",chosen);if(!selection.commit())throw new IllegalStateException("Starter choice not persisted");
        }catch(RuntimeException failure){notesProblem="Не удалось сохранить стартовый состав. Данные сохранены; повторите открытие приложения.";}
        player = new PlayerController(localCatalog(), speech, (delay, action) -> {
            handler.postDelayed(action, delay);
            return () -> handler.removeCallbacks(action);
        }, combinedState, this::stateChanged, 1000, playbackMembership());
        player.route(new PlayerController.Route(){
            public void explicitPlay(){airMix.explicitPlay();}
            public void selected(String station,Article article){airMix.selected(mixItem(station,article));}
            public void started(String station,Article article){AirMix.Item item=mixItem(station,article);if(item!=null)airMix.started(item);}
            private boolean localPrevious(String station,Article article){if(airMix.enabled()||article==null)return false;for(Station target:player.stations())if(target.id.equals(station))for(int i=1;i<target.articles.size();i++)if(target.articles.get(i).id.equals(article.id)&&target.articles.get(i).version.equals(article.version))return true;return false;}
            public Boolean canPrevious(String station,Article article){if(!sourceStation(station)||localPrevious(station,article))return null;try{return airMix.hasPreviousInAir(airPool(),airStationOrder(),station,article);}catch(RuntimeException unavailable){return false;}}
            public PlayerController.RouteChoice previous(String station,Article article){if(!sourceStation(station)||localPrevious(station,article))return null;return choice(airMix.previousInAir(airPool(),airStationOrder(),station,article));}
            public Boolean canNext(String station,Article article){if(!sourceStation(station))return null;try{return airMix.hasNextInAir(airPool(),airStationOrder(),station,article,player.savedState());}catch(RuntimeException unavailable){return false;}}
            private PlayerController.RouteChoice choice(AirMix.Item item){return item==null?PlayerController.RouteChoice.end():PlayerController.RouteChoice.item(item.station,item.article,item.version);}
            public PlayerController.RouteChoice next(String station,Article article){return next(station,article,false);}
            public PlayerController.RouteChoice next(String station,Article article,boolean explicit){
                if(!sourceStation(station))return PlayerController.RouteChoice.sourceOnly();
                try{return choice(airMix.nextInAir(airPool(),airStationOrder(),station,article,player.savedState(),explicit));}
                catch(RuntimeException unavailable){airMessage="Состояние эфира не подтверждено. Повторите явный запуск.";return PlayerController.RouteChoice.end();}
            }
            public PlayerController.RouteChoice next(String station,Article article,java.util.Set<String> excluded){
                if(!sourceStation(station))return PlayerController.RouteChoice.sourceOnly();
                try{return choice(airMix.nextInAir(airPool(),airStationOrder(),station,article,player.savedState(),false,excluded));}catch(RuntimeException unavailable){return PlayerController.RouteChoice.end();}
            }
        });
        if (radioStore.restoreFailed()) notesProblem = "Сохранённое состояние радио недоступно. Файлы сохранены; изменения заблокированы.";
        if (noteBook != null) {
            if (notesProblem == null) createNotes();
            createSources();
            if (notesProblem == null) {
                try {
                    boolean needsCheckpoint = false;
                    for (NoteBook.Material m : noteBook.materials()) if (m.sourceVersionKey != null && (radioAuthority.persisted() == null
                            || !radioAuthority.persisted().progress.containsKey(PlayerController.progressKey(m.stationId,m.articleId,m.version)))) needsCheckpoint = true;
                    if (needsCheckpoint) combinedState.save(player.savedState());
                    else if (radioAuthority.persisted() != null) noteBook.captureSourceHistory(radioAuthority.persisted());
                } catch (RuntimeException failure) { historyFailed = true; sourcesProblem = "Восстановленный текст сохранён; повтор сохранения прогресса не подтверждён. Очистку отложите."; }
            }
        }
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, filter);
        if (BuildConfig.MODEL_EXPERIMENT) {
            android.content.SharedPreferences choice = getSharedPreferences("local-model-choice", 0);
            systemVoiceExplicit = choice.contains("silero") && !choice.getBoolean("silero", false);
            systemNotesExplicit = choice.contains("giga-notes") && !choice.getBoolean("giga-notes", false);
            retryBundledSetup();
        }
        stateChanged(player.snapshot());
    }

    private void createMediaSession() {
        final int generation = ++sessionGeneration;
        final String commandId = systemCommands.openSession();
        session = new MediaSession(this, "BububuTTS");
        session.setCallback(new MediaSession.Callback() {
            // A retained session is already in a started foreground service. Play is synchronous
            // with Pause on the owner looper, so a deferred start intent cannot override Pause.
            @Override public void onPlay() { if (acceptSessionCommand(generation, commandId)) play(); }
            @Override public void onPause() { if (acceptSessionCommand(generation, commandId)) player.pause(); }
            @Override public void onStop() { if (acceptSessionCommand(generation, commandId)) stopPlayback(); }
            @Override public void onSkipToNext() { if (acceptSessionCommand(generation, commandId)) player.nextArticle(); }
            @Override public void onSkipToPrevious() { if (acceptSessionCommand(generation, commandId)) player.previousArticle(); }
            @Override public void onFastForward() { if (acceptSessionCommand(generation, commandId)) seekSentence(1); }
            @Override public void onRewind() { if (acceptSessionCommand(generation, commandId)) seekSentence(-1); }
        }, handler);
        session.setSessionActivity(PendingIntent.getActivity(this, 0, new Intent(this, RadioActivity.class), PendingIntent.FLAG_IMMUTABLE));
    }

    private boolean acceptSessionCommand(int generation, String commandId) {
        boolean accepted = generation == sessionGeneration && systemCommands.accept(commandId);
        if (accepted) applicationPlay.cancel(); // A later actual system command supersedes queued app Play.
        return accepted;
    }

    @Override public IBinder onBind(Intent intent) { return SourceBootstrap45.blocked(this)?null:binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if(SourceBootstrap45.blocked(this))return START_NOT_STICKY;
        // AMS may already have registered a newer start while its callback is still queued.
        // Finish only this delivered id, after playback has decided whether to retain it.
        startedRequests.dispatch(startId, () -> handleStartCommand(intent), () -> foreground && !destroyed);
        return START_NOT_STICKY;
    }

    private void handleStartCommand(Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (APP_PLAY.equals(action)) {
            if (!applicationPlay.take(intent.getStringExtra(APP_REQUEST_ID))) {
                event("application-play-rejected", "stale-or-canceled");
                // A stale foreground start still carries an Android startForeground promise.
                // Existing foreground belongs to live playback and must not be released here.
                if (!foreground) { ensureForeground(); closeSystemPlayback(); }
                return;
            }
            if (!radioTargetAvailable()) {
                cancelPendingListen();listenStatus("NOT_IN_AIR","Эта версия статьи больше не входит в эфир.");event("application-play-rejected","removed-or-unverified-target");
                if(!foreground){ensureForeground();closeSystemPlayback();}return;
            }
            if (AndroidNoteAudio.isOutputBlocked() || notesProblem != null || (notes != null && !notes.beforeRadioPlay())) {
                // A queued foreground start must be acknowledged even if recording now owns
                // audio. Release only radio playback and finish this delivered start normally.
                cancelPendingListen();listenStatus("AUDIO_BUSY","Завершите запись или прослушивание заметки и повторите запуск.");ensureForeground(); closeSystemPlayback(); return;
            }
            // This is a fresh explicit application request, never a notification capability.
            // Resource cleanup inside dispatch does not finish this fresh started request.
            pausedSessionLease.closeIfExpired();
            if (ensureForeground()) play(intent.getLongExtra(APP_LISTEN_EPOCH,-1));
            else listenStatus("FOREGROUND_DENIED","Android не разрешил фоновое чтение.");
            if (!player.snapshot().playing) stateChanged(player.snapshot());
            return;
        }
        String commandId = intent == null ? null : intent.getStringExtra(SESSION_ID);
        boolean known = PLAY.equals(action) || PAUSE.equals(action) || PREVIOUS.equals(action)
                || NEXT.equals(action) || STOP.equals(action);
        if (!known || commandId == null || !sessionActionUri(commandId).toString().equals(intent.getDataString())
                || !systemCommands.accept(commandId)) {
            event("system-command-rejected", action == null ? "missing-action" : action);
            // Notification controls use getService, so rejection has no foreground-start promise.
            // Never stop a newer live foreground session because an old PendingIntent arrived.
            return;
        }
        cancelPendingListen(); // Only a valid delivered system action revokes queued app intent.
        if (PLAY.equals(action)) play();
        else if (PAUSE.equals(action)) player.pause();
        else if (PREVIOUS.equals(action)) player.previousArticle();
        else if (NEXT.equals(action)) player.nextArticle();
        else stopPlayback();
        if (!player.snapshot().playing) stateChanged(player.snapshot());
    }

    PlayerController player() { return player; }
    void command(Consumer<PlayerController> command) {
        cancelPendingListen();++selectionGeneration;command.accept(player);SourceRecord source=currentSource();if(source!=null)explicitAirSource=source.id;manualNonSource=source==null;if(!OrdinaryRadio.builtin(player.snapshot().station.id))diagnosticSampleSelected=false;
    }
    void addListener(Consumer<PlayerController.Snapshot> listener) {
        listeners.add(listener); listener.accept(player.snapshot());
    }
    void removeListener(Consumer<PlayerController.Snapshot> listener) { listeners.remove(listener); }
    void refreshVoice() {
        pausedSessionLease.closeIfExpired();
        if (!player.snapshot().playing) speech.refreshIfIdle();
    }

    void cancelPendingListen(){
        if(quickStart.pending()){
            airMessage="Запуск отменён. Для продолжения нажмите «Слушать».";
            listenStatus("CANCELLED",airMessage);
        }
        revokeListen();
    }
    private void revokeListen(){
        boolean awaiting=quickStart.awaitingAudio();
        quickStart.revoke();applicationPlay.cancel();explicitStartArmed=false;
        if(awaiting&&player!=null&&!player.actualRequestStarted()){player.pause();if(foreground)closeSystemPlayback();}
    }
    private void terminalListen(String run,String code,String message){
        if(quickStart.batchMayRevoke(run)){revokeListen();listenStatus(code,message);}
    }
    boolean waitingToListen(){return quickStart.pending();}
    boolean canPrepareAir(){return sources!=null&&!selectedAirSources().isEmpty();}
    boolean hasReadyAirForListen(){
        if(player==null||noteBook==null||!radioAuthority.known())return false;
        return QuickStart.hasExistingAir(airMix,OrdinaryRadio.sourceAir(noteBook,airMix.enabled(),explicitAirSource,true),airStationOrder(),player.snapshot(),player.savedState());
    }
    boolean mixEnabled(){return airMix.enabled();}
    String airMessage(){return airMessage;}
    boolean hasFailedAirSources(){return !failedAirSources.isEmpty();}
    boolean takeNotificationPermissionDue(){boolean due=notificationPermissionDue&&activityVisible;notificationPermissionDue=false;return due;}
    void mixEnabled(boolean value){cancelPendingListen();player.cancelTextRecovery();++selectionGeneration;AirMix.Item current=player.actualRequestStarted()?mixItem(player.snapshot().station.id,player.snapshot().article):null;airMix.enabled(value,current);if(value)explicitAirSource=null;airMessage=value?"Микс: полные статьи из выбранных источников":"Микс выключен: продолжение текущего источника";emitNotes();}
    /** Change mode only. Revoking queued intents must not pause or retry the current request. */
    void stopMix(){player.cancelTextRecovery();if(!airMix.enabled())return;quickStart.revoke();applicationPlay.cancel();++selectionGeneration;airMix.enabled(false,null);airMessage="Микс выключен. Текущая новость и место сохранены.";emitNotes();}
    /** The fixed Mix button is an explicit reshuffle-and-listen action, including repeated presses. */
    void startMix(){
        cancelPendingListen();++selectionGeneration;if(noteBook==null||noteBook.storageUncertain()||!radioAuthority.known()){airMessage="Состояние эфира не подтверждено. Проверьте сохранённые материалы.";emitNotes();return;}player.pause();player.resetTextRecoveryPass();airMix.enabled(true,null);explicitAirSource=null;
        AirMix.Item first=airMix.reshuffle(airPool(),player.savedState());
        if(first==null){airMessage="Нет непрослушанных статей";emitNotes();return;}
        try{player.selectArticle(first.station,first.article);requestPlay();}catch(RuntimeException unavailable){airMessage="Не удалось выбрать статью микса. Проверьте эфир.";emitNotes();}
    }
    private SourceRecord currentSource(){if(noteBook==null||player==null)return null;String station=player.snapshot().station.id;for(SourceRecord source:noteBook.sources())if(source.stationId.equals(station))return source;return null;}
    private boolean sourceStation(String station){if(noteBook==null)return false;for(SourceRecord source:noteBook.sources())if(source.stationId.equals(station))return true;return false;}
    private List<String> airStationOrder(){List<String> result=new ArrayList<>();for(Station station:player.stations())if(OrdinaryRadio.inAir(station,this::isRetiredStation))result.add(station.id);return result;}
    private String currentIdentity(){PlayerController.Snapshot state=player.snapshot();return state.article==null?null:PlayerController.progressKey(state.station.id,state.article.id,state.article.version);}
    private java.util.Set<String> selectedAirSources(){
        java.util.Set<String> result=new java.util.LinkedHashSet<>();if(noteBook==null)return result;
        android.content.SharedPreferences prefs=getSharedPreferences(UiStyle.PREFS,0);java.util.Set<String> chosen=prefs.contains("batch_source_ids")?prefs.getStringSet("batch_source_ids",java.util.Collections.emptySet()):null;
        for(SourceRecord source:noteBook.sources())if(source.enabled&&(chosen==null||chosen.contains(source.id)))result.add(source.id);return result;
    }
    private int airCount(){int count=getSharedPreferences(UiStyle.PREFS,0).getInt("batch_per_source",3);return count==5||count==10||count==20?count:3;}
    private String selectionStamp(){return selectionGeneration+"|"+airMix.enabled()+"|"+explicitAirSource+"|"+new java.util.TreeSet<>(selectedAirSources());}
    private String voiceStamp(){return (BuildConfig.MODEL_EXPERIMENT&&!systemVoiceExplicit?"silero":"system")+"|"+voiceGeneration;}
    private AirMix.Item mixItem(String station,Article article){if(noteBook==null||article==null)return null;NoteBook.Material material=noteBook.material(station,article.id);if(material==null||material.sourceVersionKey==null||!material.version.equals(article.version))return null;SourceVersionRecord version=noteBook.sourceVersion(material.sourceVersionKey);return version==null?null:new AirMix.Item(station,article.id,article.version,version.sourceId,article.sentences.size());}
    private List<AirMix.Item> airPool(){
        if(noteBook==null||noteBook.storageUncertain()||!radioAuthority.known())return new ArrayList<>();
        return OrdinaryRadio.sourceAir(noteBook,airMix.enabled(),explicitAirSource);
    }
    private QuickStart.Candidate candidate(NoteBook.Material material,boolean current){
        if(material==null||material.sourceVersionKey==null)return null;SourceVersionRecord version=noteBook.sourceVersion(material.sourceVersionKey);if(version==null)return null;
        return new QuickStart.Candidate(material.stationId,material.articleId,material.version,version.sourceId,version.channelReference(),version.kind(),"full".equals(version.completeness),current);
    }
    void requestPlay(){updateAir(true,null,true);}
    void refreshAir(){downloadSources(selectedAirSources(),airCount());}
    void retryAirFailures(){cancelPendingListen();if(failedAirSources.isEmpty()){airMessage="Неудачных источников для повторной загрузки нет.";emitNotes();return;}downloadSources(new java.util.LinkedHashSet<>(failedAirSources),airCount());}
    private void updateAir(boolean listen,java.util.Set<String> retry,boolean preferCached){
        cancelPendingListen();if(listen)listenStatus("REQUEST","Готовим воспроизведение…");if(sources==null||noteBook==null){airMessage="Источники недоступны. Проверьте сохранённые данные.";if(listen)listenStatus("STORAGE_UNAVAILABLE",airMessage);emitNotes();return;}
        final String run=java.util.UUID.randomUUID().toString();java.util.Set<String> selected=retry==null?selectedAirSources():new java.util.LinkedHashSet<>(retry);
        if(listen&&preferCached&&!airMix.enabled()&&currentSource()!=null)player.preferUnfinishedCurrent(selectionGeneration!=0);
        QuickStart.Candidate current=null;PlayerController.Snapshot state=player.snapshot();
        if(listen&&preferCached&&state.article==null&&!state.selectionRequired){
            List<AirMix.Item> pool=radioAuthority.known()?OrdinaryRadio.sourceAir(noteBook,airMix.enabled(),explicitAirSource,true):java.util.Collections.emptyList();
            AirMix.Item ready=QuickStart.existingAir(airMix,pool,airStationOrder(),state,player.savedState());
            if(ready!=null){player.selectArticle(ready.station,ready.article);state=player.snapshot();if(state.article==null||!state.station.id.equals(ready.station)||!state.article.id.equals(ready.article)||!state.article.version.equals(ready.version)||state.selectionRequired){airMessage="Состояние эфира изменилось. Выберите материал и повторите «Слушать».";if(listen)listenStatus("SELECTION_CHANGED",airMessage);emitNotes();return;}}
        }
        if(listen&&state.article!=null&&!state.selectionRequired&&!state.passEnded)current=candidate(noteBook.material(state.station.id,state.article.id),true);
        if(listen&&preferCached)explicitAirSource=QuickStart.primarySource(airMix.enabled(),state.station.id,noteBook.sources(),selected);
        if(QuickStart.waitForBatch(sources.batchRunning(),preferCached,current)){airMessage="Дождитесь текущей загрузки или отмените её.";if(listen)listenStatus("BATCH_BUSY",airMessage);emitNotes();return;}
        // Notes and fixtures remain explicit manual selections and never become a delayed quick start.
        if(listen&&state.article!=null&&current==null&&(noteBook.material(state.station.id,state.article.id)!=null||diagnosticSampleSelected&&OrdinaryRadio.builtin(state.station.id))){if(speech.ready())startExplicitPlayback();else {airMessage="Дождитесь готовности голоса и нажмите «Слушать» ещё раз.";listenStatus("VOICE_NOT_READY",airMessage);}listen=false;if(preferCached){emitNotes();return;}}
        boolean cachedOnly=listen&&preferCached&&current!=null;
        if(listen){quickStart.begin(run,selectionStamp(),voiceStamp(),selected,airMix.enabled()?null:explicitAirSource,current);airMessage="Готовим эфир: ждём полный текст и выбранный голос…";}
        if(cachedOnly){tryPendingListen();emitNotes();return;}
        SourceCoordinator.AirUpdate update=new SourceCoordinator.AirUpdate(run,null,event->{
            if(event.storeEpoch!=noteBook.authorityEpoch()||event.radioEpoch!=radioAuthority.epoch()||!noteBook.isSourceActive(event.sourceId)||event.sourceGeneration!=noteBook.sourceGeneration(event.sourceId))return;
            SourceRecord source=noteBook.source(event.sourceId);NoteBook.Material material=source==null?null:noteBook.material(source.stationId,event.version.articleId);
            if(material!=null&&event.recordKey.equals(material.sourceVersionKey)){quickStart.published(event.runId,candidate(material,false));tryPendingListen();}
        });
        tryPendingListen();
        if(selected.isEmpty()){terminalListen(run,"NO_SOURCES","Выберите источники для загрузки.");airMessage=player.snapshot().playing?"Играет сохранённый материал. Источники для обновления не выбраны.":"Выберите источники для загрузки.";emitNotes();return;}
        try{sources.downloadBatch(selected,airCount(),update,progress->{
            recordDownload(progress);if(progress.finished){failedAirSources=progress.failedSources;if(progress.cancelled||progress.stopFailure!=null||progress.qualifyingFull==0||!quickStart.hasCandidate(run)){terminalListen(run,progress.cancelled?"DOWNLOAD_CANCELLED":progress.stopFailure!=null?"DOWNLOAD_FAILED":"NO_FULL_ARTICLE",progress.cancelled?"Загрузка отменена.":"Нет готовой статьи для чтения. Откройте отчёт о загрузке.");}
                airMessage=DownloadPresentation.outcome(progress);
            }else airMessage="Загружаем материалы…";
            emitNotes();
        });}catch(RuntimeException failure){terminalListen(run,"DOWNLOAD_FAILED","Не удалось подготовить статью. Проверьте источники.");airMessage="Подготовка эфира не началась. Проверьте выбор, место и состояние источников.";emitNotes();}
    }
    private void tryPendingListen(){
        QuickStart.Ticket ticket=quickStart.ticket();if(ticket==null||player==null||speech==null)return;
        if(noteBook==null||noteBook.storageUncertain()||!radioAuthority.known()){
            revokeListen();listenStatus("STORAGE_UNAVAILABLE","Сохранённый эфир недоступен. Проверьте состояние приложения.");return;
        }
        if(bundledSetupFailed&&!systemVoiceExplicit||(BuildConfig.MODEL_EXPERIMENT&&!systemVoiceExplicit?ModelsController.get(this).availability()!=null:speech.terminalVoiceError())){
            revokeListen();listenStatus("VOICE_UNAVAILABLE","Голос недоступен. Проверьте настройки озвучки.");return;
        }
        String voice=voiceStamp(),selection=selectionStamp();
        boolean voiceReady=(bundledSetupStatus==null||systemVoiceExplicit)&&speech.ready();
        if(voiceReady)quickStart.ready(ticket,voice);
        switch(ticket.readiness(selection,voice)){
            case SELECTION_CHANGED: listenStatus("SELECTION_CHANGED","Выбран другой материал. Нажмите «Слушать» ещё раз.");break;
            case VOICE_CHANGED: listenStatus("VOICE_CHANGED","Выбран другой голос. Нажмите «Слушать» ещё раз.");break;
            case WAIT_VOICE: listenStatus("WAIT_VOICE","Ждём готовности выбранного голоса…");break;
            case WAIT_ARTICLE: listenStatus("WAIT_ARTICLE","Ждём полный текст статьи…");break;
            default: break;
        }
        QuickStart.Candidate target=quickStart.take(ticket,selection,voice,this::admitListen);
        if(target!=null){try{
            PlayerController.Snapshot current=player.snapshot();
            if(current.article==null||!target.station.equals(current.station.id)||!target.article.equals(current.article.id)||!target.version.equals(current.article.version))player.selectArticle(target.station,target.article);
            airMessage="Эфир готов";
            if(!startExplicitPlayback()){
                revokeListen();
            }
        }catch(RuntimeException failure){cancelPendingListen();listenStatus("START_FAILED","Запуск не подтверждён. Проверьте сохранённый материал.");}}
        else if(!quickStart.pending()&&!player.snapshot().playing){applicationPlay.cancel();if(foreground)closeSystemPlayback();}
    }
    private boolean refuseListen(String code,String message){listenStatus(code,message);return false;}
    private boolean admitListen(QuickStart.Candidate item) {
        if(!activityVisible)return refuseListen("ACTIVITY_HIDDEN","Откройте экран радио и нажмите «Слушать».");
        if(AndroidNoteAudio.isOutputBlocked())return refuseListen("AUDIO_BUSY","Не подтверждена остановка аудио. Завершите запись или прослушивание заметки.");
        if(notesProblem!=null||noteBook==null||noteBook.storageUncertain()||!radioAuthority.known())return refuseListen("STORAGE_UNAVAILABLE","Не удалось проверить сохранённый эфир.");
        NoteBook.Material material=noteBook.material(item.station,item.article);
        if(material==null||!material.version.equals(item.version)||material.sourceVersionKey==null)return refuseListen("VERSION_CHANGED","Выбранная версия статьи изменилась. Выберите статью снова.");
        SourceVersionRecord exact=noteBook.sourceVersion(material.sourceVersionKey);
        if(exact==null||!java.util.Objects.equals(exact.channelReference(),item.channel))return refuseListen("SOURCE_CHANGED","Не удалось проверить источник этой версии статьи.");
        if(!noteBook.isSourceActive(exact.sourceId)||noteBook.source(exact.sourceId)==null||!noteBook.source(exact.sourceId).enabled)return refuseListen("SOURCE_DISABLED","Источник статьи отключён или удалён.");
        if(noteBook.queueMembership(item.station,material.asArticle(),material.sequence,material.sourceVersionKey)!=NoteBook.Membership.ALLOWED)return refuseListen("NOT_IN_AIR","Эта версия статьи больше не входит в эфир.");
        return prepareOutput()==null;
    }

    private boolean startExplicitPlayback() {
        if(!radioTargetAvailable()){event("notes-radio-request-rejected","removed-or-unverified-target");return refuseListen("NOT_IN_AIR","Не удалось подтвердить выбранный материал эфира.");}
        if (AndroidNoteAudio.isOutputBlocked() || notesProblem != null || (notes != null && !notes.beforeRadioPlay())) {
            event("notes-radio-request-rejected", "");return refuseListen("AUDIO_BUSY","Завершите запись или прослушивание заметки и повторите запуск.");
        }
        pausedSessionLease.closeIfExpired();
        explicitStartArmed=true;
        long listenEpoch=quickStart.awaitingEpoch();
        String requestId = applicationPlay.reserve();
        listenStatus("START_SERVICE","Запускаем чтение…");
        try { startForegroundService(new Intent(this, RadioService.class).setAction(APP_PLAY).putExtra(APP_REQUEST_ID, requestId).putExtra(APP_LISTEN_EPOCH,listenEpoch)); }
        catch (RuntimeException denied) {
            if (applicationPlay.abandon(requestId))
                player.interrupt("Android не разрешил запуск. Откройте экран радио и повторите Play.");
            return refuseListen("START_DENIED","Android не разрешил запуск. Откройте экран радио и повторите попытку.");
        }
        return true;
    }

    void play() {
        play(quickStart.awaitingEpoch());
    }
    private void play(long listenEpoch) {
        applicationPlay.cancel();
        if(!radioTargetAvailable()){cancelPendingListen();listenStatus("NOT_IN_AIR","Эта версия статьи больше не входит в эфир.");event("notes-radio-play-rejected","removed-or-unverified-target");return;}
        if (AndroidNoteAudio.isOutputBlocked() || notesProblem != null || (notes != null && !notes.beforeRadioPlay())) {
            cancelPendingListen();listenStatus("AUDIO_BUSY","Завершите запись или прослушивание заметки и повторите запуск.");event("notes-radio-play-rejected", ""); return;
        }
        if (!ensureForeground()){cancelPendingListen();listenStatus("FOREGROUND_DENIED","Android не разрешил фоновое чтение.");return;}
        if(listenEpoch>=0&&!quickStart.playerEntered(listenEpoch)){event("application-play-rejected","stale-listen-epoch");return;}
        if(listenEpoch>=0)listenStatus("PLAYER_ENTERED","Готовим звук…");
        player.play();
        if (!player.snapshot().playing) stateChanged(player.snapshot());
    }

    private boolean ensureForeground() {
        if (destroyed) return false;
        if (foreground) return true;
        try {
            if (session == null) createMediaSession();
            systemClosed = false;
            Notification notification = notification(player == null ? null : player.snapshot());
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFICATION_ID, notification);
            foreground = true;
            notificationVisible = true;
            return true;
        } catch (RuntimeException denied) {
            if (player != null) player.interrupt("Android не разрешил фоновый запуск. Откройте экран радио и нажмите Play.");
            closeSystemPlayback();
            return false;
        }
    }

    private String outputRefusal(String code,String message){
        if(quickStart.ticket()!=null)listenStatus(code,message);
        return message;
    }
    @Override public String prepareOutput() {
        if (bundledSetupStatus != null && !systemVoiceExplicit) return outputRefusal("WAIT_MODELS",bundledSetupStatus);
        if (AndroidNoteAudio.isOutputBlocked() || SpeechRouter.isOutputBlocked()) return outputRefusal("AUDIO_BUSY","Не подтверждена остановка аудио. Повторите завершение записи; если не помогает, остановите приложение в настройках Android.");
        if (notesProblem != null) return outputRefusal("NOTES_UNAVAILABLE",notesProblem);
        if (notes != null && !notes.beforeRadioPlay()) return outputRefusal("AUDIO_BUSY","Завершите запись или остановите оригинал перед радио.");
        if (!ensureForeground()) return outputRefusal("FOREGROUND_DENIED","Не удалось запустить фоновое чтение.");
        if (!focused) {
            final int generation = ++focusGeneration;
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(change -> {
                        if (generation != focusGeneration) return;
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                            event("focus-loss", "");
                            cancelPendingListen();
                            player.interrupt("Звук прерван системой. Продолжите по Play.");
                        }
                        // GAIN never automatically resumes a paused/canceled request.
                    }, handler).build();
            focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!focused) { ++focusGeneration; focus = null; return outputRefusal("FOCUS_DENIED","Аудиофокус недоступен. Повторите Play, когда другое приложение освободит звук."); }
        }
        // Refresh a bounded lock for each fragment. Pause/error/end always release it.
        wakeLock.acquire(20 * 60 * 1000L);
        return null;
    }

    private void releaseOutput() {
        ++focusGeneration;
        if (focus != null) audio.abandonAudioFocusRequest(focus);
        focus = null;
        focused = false;
        if (wakeLock.isHeld()) wakeLock.release();
    }

    private void stateChanged(PlayerController.Snapshot state) {
        if(quickStart.settleStopped(state.playing,state.seeking,state.currentRequestId)){applicationPlay.cancel();explicitStartArmed=false;listenStatus("PLAYER_STOPPED",state.error==null?"Чтение остановлено.":state.error);}
        if (!state.playing) {
            releaseOutput();
        }
        if (destroyed || systemClosed) { emitToScreen(state); return; }
        // A started foreground session survives Activity.onStop/unbind while paused. The lease
        // holds neither audio focus nor a wake lock, and passive callbacks never extend it.
        if (state.broadcastEnded && foreground) {
            closeSystemPlayback();
            emitToScreen(state);
            return;
        }
        pausedSessionLease.update(foreground, state.playing);
        int playback = state.playing ? (state.seeking || state.status.contains("Подготов")
                ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PLAYING) : PlaybackState.STATE_PAUSED;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP;
        if (state.canPreviousArticle) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (state.canNextArticle) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        if (state.article != null) actions |= PlaybackState.ACTION_REWIND | PlaybackState.ACTION_FAST_FORWARD;
        if (session != null) {
            session.setPlaybackState(new PlaybackState.Builder().setState(playback, PlaybackState.PLAYBACK_POSITION_UNKNOWN, state.playing ? 1f : 0f)
                .setActions(actions).build());
            session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, systemMediaTitle(state))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.station.name).build());
            session.setActive(notificationVisible || state.playing);
        }
        if (notificationVisible) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(state));
        emitToScreen(state);
    }

    private void emitToScreen(PlayerController.Snapshot state) {
        for (Consumer<PlayerController.Snapshot> listener : new ArrayList<>(listeners)) listener.accept(state);
    }

    private String systemMediaTitle(PlayerController.Snapshot state) {
        return state == null ? SystemMediaTitle.NEUTRAL : SystemMediaTitle.resolve(noteBook,
                radioAuthority != null && radioAuthority.known(), state.station.id, state.article, diagnosticFixtures);
    }

    private Notification notification(PlayerController.Snapshot state) {
        boolean playing = state != null && state.playing;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(systemMediaTitle(state))
                .setContentText(state == null ? "Подготовка чтения" : state.station.name + " · " + state.status)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, RadioActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setOngoing(true)
                .setSubText(playing ? "Чтение локального текста" : "Пауза · системное управление до 10 мин")
                .setCategory(Notification.CATEGORY_TRANSPORT);
        List<Integer> compact = new ArrayList<>();
        int index = 0;
        if (state != null && state.canPreviousArticle) {
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Предыдущая новость", action(PREVIOUS)).build());
            compact.add(index++);
        }
        builder.addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "Пауза" : "Читать", action(playing ? PAUSE : PLAY)).build());
        compact.add(index++);
        if (state != null && state.canNextArticle) {
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Следующая новость", action(NEXT)).build());
            compact.add(index++);
        }
        builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Закрыть", action(STOP)).build());
        int[] buttons = new int[compact.size()];
        for (int i = 0; i < buttons.length; i++) buttons[i] = compact.get(i);
        if (session != null) builder.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(buttons));
        return builder.build();
    }

    private PendingIntent action(String action) {
        String commandId = systemCommands.currentSessionId();
        Intent intent = new Intent(this, RadioService.class).setAction(action)
                .putExtra(SESSION_ID, commandId).setData(sessionActionUri(commandId));
        // Data participates in PI identity; a new session cannot update an old immutable PI.
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE);
    }

    private static Uri sessionActionUri(String commandId) {
        return new Uri.Builder().scheme("bububu-radio").authority("session").appendPath(commandId).build();
    }

    private void seekSentence(int direction) {
        PlayerController.Snapshot state = player.snapshot();
        if (state.article == null) return;
        int target = Math.max(0, Math.min(state.article.sentences.size() - 1, state.sentenceIndex + direction));
        player.seekToFraction((double) state.article.sentenceStarts.get(target) / state.article.text.length());
    }

    private void stopPlayback() {
        cancelPendingListen();
        int deliveredId = startedRequests.captureDeliveredId();
        systemClosed = true;
        pausedSessionLease.cancel();
        player.pause();
        closeSystemPlayback(deliveredId);
    }

    private void closeSystemPlayback() {
        closeSystemPlayback(startedRequests.captureDeliveredId());
    }

    private void closeSystemPlayback(int deliveredId) {
        // Capture before releasing resources or notifying listeners. Never finish an id that
        // belongs to later work. During onStartCommand completion is deferred to dispatch.
        releaseSystemPlayback();
        if (!destroyed) startedRequests.finishAfterPlaybackRelease(deliveredId);
    }

    private void releaseSystemPlayback() {
        systemClosed = true;
        systemCommands.revokeSession();
        pausedSessionLease.cancel();
        releaseOutput();
        notificationVisible = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        ++sessionGeneration; // Revoke queued commands before releasing this token.
        if (session != null) {
            session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_NONE,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f).setActions(0).build());
            session.setActive(false);
            session.release();
            session = null;
        }
    }

    @Override public void speechDebug(String stage,long epoch,int sentence,int fragment,long value) {
        events.add(SystemClock.elapsedRealtime()+" speech-"+stage+" e="+epoch+" s="+sentence+" f="+fragment+" n="+value);
        if(events.size()>200)events.remove(0);
    }
    @Override public void started(String id) { player.onStart(id);if(id.equals(player.snapshot().currentRequestId)&&player.actualRequestStarted()){if(quickStart.awaitingAudio()||explicitStartArmed||!listenMessage.isEmpty())listenStatus("AUDIO_STARTED","");quickStart.actualStarted();if(explicitStartArmed){explicitStartArmed=false;if(activityVisible)notificationPermissionDue=true;}emitToScreen(player.snapshot());} }
    @Override public void done(String id) { player.onDone(id); }
    @Override public void failed(String id,String reason){deliverSpeechFailure(id,reason,false);}
    @Override public void textFailed(String id,String code){deliverSpeechFailure(id,code,true);}
    private void deliverSpeechFailure(String id,String reason,boolean text){long ticket=quickStart.awaitingEpoch();boolean starting=ticket>=0&&id.equals(player.snapshot().currentRequestId);player.speechFailure(id,reason,text,()->{if(starting&&quickStart.awaitingEpoch()==ticket)cancelPendingListen();});}
    @Override public void voiceChanged() { if(player!=null)tryPendingListen();if (player != null) for (Consumer<PlayerController.Snapshot> l : new ArrayList<>(listeners)) l.accept(player.snapshot()); }
    @Override public void event(String name, String id) {
        PlayerController.Snapshot state = player == null ? null : player.snapshot();
        String material = state == null || state.article == null ? "" : " " + state.station.id + "/" + state.article.id + " sentence=" + state.sentenceIndex;
        events.add(SystemClock.elapsedRealtime() + " " + name + material + (id.isEmpty() ? "" : " " + id));
        if (events.size() > 200) events.remove(0);
    }

    String voiceDescription() { return speech.description(); }
    void stopModelPreview() { speech.stopPreview(); }
    boolean sileroSelected() { return speech.selected(); }
    String bundledSetupStatus() { return bundledSetupStatus; }
    boolean bundledSetupRetryAvailable() { return bundledSetupFailed && ModelsController.get(this).availability() == null; }
    void retryBundledSetup() {
        if (!BuildConfig.MODEL_EXPERIMENT || destroyed || bundledTask != null || bundledSetupReady) return;
        long epoch = ++bundledSetupEpoch;
        ModelsController models = ModelsController.get(this);
        String unavailable = models.availability();
        bundledSetupFailed = false;
        bundledSetupStatus = unavailable == null ? models.busy() ? "Ожидание текущей операции перед проверкой моделей…" : "Подготовка моделей… Интернет не нужен."
                : "Локальные модели недоступны: " + unavailable + ". Системные движки можно выбрать в Настройках.";
        emitNotes();
        if (unavailable != null) return;
        bundledTask = models.prepareBundled(message -> {
            if (!destroyed && epoch == bundledSetupEpoch && bundledTask != null) { bundledSetupStatus = message; emitNotes(); }
        }, new ModelsController.Result<Void>() {
            public void success(Void ignored) {
                if (destroyed || epoch != bundledSetupEpoch || bundledTask == null) return;
                try {
                    // Both exact packs have passed full hashes before any default changes.
                    if (notes == null) throw new IllegalStateException("Заметки недоступны; выбор движков сохранён");
                    android.content.SharedPreferences choice = getSharedPreferences("local-model-choice", 0);
                    BundledDefaults.finish(new BundledDefaults.Store() {
                        public Boolean voice() { return choice.contains("silero") ? choice.getBoolean("silero", false) : null; }
                        public Boolean notes() { return choice.contains("giga-notes") ? choice.getBoolean("giga-notes", false) : null; }
                        public boolean commit(boolean voice, boolean localNotes) {
                            return choice.edit().putBoolean("silero", voice).putBoolean("giga-notes", localNotes)
                                    .putBoolean("bundled-v1", true).commit();
                        }
                    }, new BundledDefaults.Selection() {
                        public void check(boolean voice, boolean localNotes) throws java.io.IOException {
                            if (voice != speech.selected() && !player.isOutputStopped()) throw new java.io.IOException("Остановка радио не подтверждена");
                            if (localNotes != gigaNotes && (notes.recordingBusy() || notes.stopUnconfirmed()))
                                throw new java.io.IOException("Сначала завершите запись и сохранение оригинала");
                        }
                        public void apply(boolean voice, boolean localNotes) {
                            speech.reloadSavedChoice();
                            if (localNotes != gigaNotes) { cancelNoteRecognition(); gigaNotes = localNotes; }
                        }
                    });
                    bundledTask = null;
                    bundledSetupReady = true;
                    bundledSetupStatus = null;
                    bundledSetupFailed = false;
                    tryPendingListen();emitNotes();
                } catch (java.io.IOException | RuntimeException failure) { error("Не удалось завершить выбор моделей. Повторите подготовку; данные сохранены."); }
            }
            public void error(String message) {
                if (destroyed || epoch != bundledSetupEpoch || bundledTask == null) return;
                bundledTask = null;
                bundledSetupFailed = true;
                bundledSetupStatus = message + " Подготовка не завершена. Готовые пакеты и данные сохранены.";
                cancelPendingListen();airMessage="Голос не готов. После исправления нажмите «Слушать» ещё раз.";
                emitNotes();
            }
        });
    }
    String notesRecognitionDescription() { return gigaNotes
            ? "Выбран GigaAM · расшифровка заметок до 120 секунд. Оригинал сохраняется целиком."
            : "Выбрано системное распознавание на телефоне, если оно поддерживается. Оригинал сохраняется отдельно."; }
    void selectGigaNotes(boolean selected) {
        if (notes == null) throw new IllegalStateException("Заметки недоступны");
        if (selected && ModelsController.get(this).notesAvailability() != null)
            throw new IllegalStateException("GigaAM недоступен");
        if (!notes.selectLocalRecognition(selected)) throw new IllegalStateException("Выбор не сохранён или запись ещё не завершена");
        systemNotesExplicit = !selected;
    }
    private void cancelNoteRecognition() {
        ++noteRecognitionEpoch;
        if (noteModelTask != null) { noteModelTask.cancel(); noteModelTask = null; }
        try { if (noteRecognition != null) noteRecognition.cancel(); }
        finally { if (fileTranscriber != null) fileTranscriber.cancel(); }
    }
    void selectSilero(boolean selected) { cancelPendingListen();++voiceGeneration;player.pause();if(!player.isOutputStopped())throw new IllegalStateException("Остановка звука не подтверждена");speech.select(selected);systemVoiceExplicit=!selected; }
    void listenPrepared(java.io.File file, java.util.function.Consumer<String> callback) { cancelPendingListen();player.pause();if(!player.isOutputStopped()){callback.accept("Остановка звука не подтверждена");return;}speech.preview(file,callback); }
    @Override public void previewFinished() { if(player!=null)stateChanged(player.snapshot()); }
    NoteBook notesBook() { return noteBook; }
    NoteCoordinator notes() { return notes; }
    String notesProblem() { return notesProblem; }
    AndroidNoteStorage noteStorage() { return noteStorage; }
    void addNoteListener(Runnable listener) { noteListeners.add(listener); listener.run(); }
    void removeNoteListener(Runnable listener) { noteListeners.remove(listener); }
    private void emitNotes() { for (Runnable listener : new ArrayList<>(noteListeners)) listener.run(); }
    boolean ownsScreen(long generation){return !destroyed && screenOwner.owns(generation);}
    boolean attachScreen(long generation,boolean onNotes){
        boolean previous=activityVisible && !screenOwner.owns(generation);
        if(destroyed || !screenOwner.attach(generation))return false;
        if(previous){
            cancelSourceUi();
            if(player!=null)player.cancelScreenSeek();
            if(notesPage && onNotes && notes!=null)notes.leaveNotes();
        }
        setNotesPage(true,onNotes);
        return true;
    }
    void updateNotesPage(long generation,boolean onNotes){
        if(ownsScreen(generation))setNotesPage(true,onNotes);
    }
    void detachScreen(long generation){
        if(screenOwner.detach(generation))setNotesPage(false,false);
    }
    private void setNotesPage(boolean visible, boolean onNotes) {
        activityVisible = visible; notesPage = onNotes;
        if(!visible)cancelPendingListen();
        if (notes != null && (!visible || !onNotes)) notes.leaveNotes();
    }
    boolean startNoteRecording(String title, String station, String article, String version) {
        cancelPendingListen();return activityVisible && notesPage && notes != null
                && notes.startRecording(title, station, article, version, System.currentTimeMillis());
    }
    void addNoteToAir(String id) {
        if (noteBook == null) throw new IllegalStateException(notesProblem);
        NoteBook.Material material = noteBook.addToAir(id);
        player.appendMaterial(material.stationId, material.stationName, material.asArticle());
        emitNotes();
    }
    void removeNoteFromAir(String id) {
        if (noteBook == null) throw new IllegalStateException(notesProblem);
        NoteBook.Material selected = null;
        for (NoteBook.Material material : noteBook.materials()) if (id.equals(material.noteId)) selected = material;
        if (selected == null) return;
        PlayerController.Snapshot current = player.snapshot();
        if (current.article != null && selected.stationId.equals(current.station.id)
                && selected.articleId.equals(current.article.id)) {
            applicationPlay.cancel();
            player.pause();
            if (!player.isOutputStopped()) throw new IllegalStateException("Речь не подтвердила остановку");
        }
        NoteBook.Material removed = noteBook.removeFromAir(id); // Durable tombstone before catalog.
        if (removed != null) player.removeMaterial(removed.stationId, removed.articleId, removed.boundaryMarker());
        emitNotes();
    }
    boolean clearAir(){requireSourcesMutable();if(sources==null)throw new IllegalStateException(sourcesProblem);return sources.clearAir();}
    boolean deleteAllNews(){requireSourcesMutable();if(sources==null)throw new IllegalStateException(sourcesProblem);return sources.deleteAllNews();}
    boolean updateNoteInAir(String id,long revision){requireSourcesMutable();if(sources==null)throw new IllegalStateException(sourcesProblem);return sources.updateNoteInAir(id,revision);}
    boolean deleteNote(String id,long expectedRevision) {
        requireSourcesMutable();noteBook.requireCurrentAuthority();
        if(noteBook.find(id)==null)return false;
        if(notes==null||notes.recordingBusy()||notes.stopUnconfirmed()||AndroidNoteAudio.isRecordingStorageBusy()||AndroidNoteAudio.isOutputBlocked())
            throw new IllegalStateException("Сначала завершите запись и сохранение оригинала");
        NoteBook.DeletionChange dry=noteBook.prepareDeleteNote(id,expectedRevision,radioAuthority.persisted());
        sourceBudget().require(Math.addExact(dry.targetBytes,SourceLimits.MAX_RADIO_BYTES));
        notes.prepareDeletion(id);
        boolean affected=dry.affectsCurrent;
        if(affected){applicationPlay.cancel();if(player.isOutputStopped())player.checkpoint();else player.pause();
            if(!player.isOutputStopped())throw new IllegalStateException("Остановка речи не подтверждена");radioAuthority.requireKnown();}
        NoteBook.DeletionChange change=noteBook.prepareDeleteNote(id,expectedRevision,radioAuthority.persisted());
        sourceBudget().require(Math.addExact(change.targetBytes,SourceLimits.MAX_RADIO_BYTES));
        noteStorage.stageDeletion(id);
        try { noteBook.applyDeletion(change); }
        catch(RuntimeException failure) {
            // Only a still-present, known Note can restore the staged original on this path.
            if(!noteBook.storageUncertain()&&noteBook.find(id)!=null)try{noteStorage.recoverDeletion(id,noteBook);}
                catch(RuntimeException retained){failure.addSuppressed(retained);}
            throw new IllegalStateException("Удаление не подтверждено. Файлы сохранены; откройте приложение заново для восстановления.",failure);
        }
        notes.deletionCommitted(id);
        try {
            if(affected)closeSystemPlayback();
            radioAuthority.requireKnown();
            player.projectCommittedDeletion(localCatalog(),noteBook.reconcileState(radioAuthority.persisted()));
            radioAuthority.requireKnown();
        } catch(RuntimeException failure) {
            throw new SourceCoordinator.ProjectionPendingException("Заметка удалена из сохранённых данных. Эфир и удаление файла будут восстановлены после перезапуска приложения.",failure);
        }
        try { if(!noteStorage.recoverDeletion(id,noteBook))throw new IllegalStateException("Original cleanup pending"); }
        catch(RuntimeException failure){throw new SourceCoordinator.ProjectionPendingException("Заметка удалена. Очистка файла записи ещё не завершена; она будет повторена после перезапуска приложения.",failure);}
        emitNotes();return true;
    }
    /** Local immutable snapshot seam; later sources use it after obtaining/saving complete text. */
    boolean addSavedMaterial(String stationId, String stationName, Article article) {
        if (noteBook == null) throw new IllegalStateException(notesProblem);
        if (stationId.equals("A") || stationId.equals("B") || stationId.equals("C"))
            throw new IllegalArgumentException("Встроенные тестовые станции не изменяются через порт источников");
        for (Station station : player.stations()) if (station.id.equals(stationId)) {
            if (!station.name.equals(stationName)) throw new IllegalArgumentException("Имя сохранённой станции неизменно");
            for (Article existing : station.articles) if (existing.id.equals(article.id)) {
                if (existing.version.equals(article.version) && existing.text.equals(article.text)
                        && existing.title.equals(article.title) && existing.sentenceStarts.equals(article.sentenceStarts)) return false;
                throw new IllegalArgumentException("В очереди уже сохранена другая версия этого материала");
            }
        }
        NoteBook.Material saved = noteBook.addSavedMaterial(stationId, stationName, article);
        boolean appended = player.appendMaterial(stationId, stationName, saved.asArticle());
        emitNotes(); return appended;
    }
    private boolean radioTargetAvailable(){
        if(player==null||!radioAuthority.known()||noteBook!=null&&noteBook.storageUncertain())return false;
        PlayerController.Snapshot state=player.snapshot();if(state.selectionRequired||OrdinaryRadio.builtin(state.station.id)&&!diagnosticSampleSelected)return false;
        if(noteBook!=null)for(SourceRecord source:noteBook.allSources())if(source.stationId.equals(state.station.id)){
            if(!noteBook.isSourceActive(source.id))return false;
            if(state.article!=null){NoteBook.Material selected=noteBook.material(state.station.id,state.article.id);if(selected==null||!selected.version.equals(state.article.version))return false;}
        }
        return true;
    }
    private PlaybackMembership playbackMembership() {
        final List<Station> fixtures=LocalData.fixtures(this);
        diagnosticFixtures = fixtures;
        return new PlaybackMembership() {
            @Override public Binding capture(String stationId,Article article) {
                if(noteBook!=null){NoteBook.Material material=noteBook.material(stationId,article.id);if(material!=null)return new Binding(material.sequence,material.sourceVersionKey,"book");}
                return new Binding(0,null,"fixture");
            }
            @Override public Admission check(String stationId,Article article,Binding binding) {
                if(binding==null||article==null)return Admission.NOT_MEMBER;
                if(noteBook!=null&&noteBook.storageUncertain()||!radioAuthority.known())return Admission.UNVERIFIED;
                if("book".equals(binding.origin)) {
                    if(noteBook==null)return Admission.UNVERIFIED;
                    return Admission.valueOf(noteBook.queueMembership(stationId,article,binding.sequence,binding.sourceVersionKey).name());
                }
                if(!diagnosticSampleSelected)return Admission.NOT_MEMBER;
                for(Station station:fixtures)if(station.id.equals(stationId))for(Article exact:station.articles)
                    if(exact.id.equals(article.id)&&exact.version.equals(article.version)&&exact.title.equals(article.title)&&exact.text.equals(article.text)&&exact.sentenceStarts.equals(article.sentenceStarts))return Admission.ALLOWED;
                return Admission.NOT_MEMBER;
            }
            @Override public boolean stationRetired(String stationId){return noteBook!=null&&noteBook.retiredStation(stationId);}
        };
    }
    boolean isRetiredStation(String stationId){return noteBook!=null&&noteBook.retiredStation(stationId);}
    private List<Station> localCatalog() {
        List<Station> result=OrdinaryRadio.catalog(noteBook);if(diagnosticSamplesExposed)result.addAll(LocalData.fixtures(this));return result;
    }
    void selectDiagnosticSample(int station){diagnosticSampleSelected=true;command(p->p.selectStation(station));}
    void selectDiagnosticSample(String station,String article){diagnosticSampleSelected=true;command(p->p.selectArticle(station,article));}
    void exposeDiagnosticSamples(){
        diagnosticSamplesExposed=true;for(Station station:LocalData.fixtures(this)){player.appendStation(station.id,station.name);for(Article article:station.articles){PlayerController.SavedProgress seed=radioAuthority.persisted()==null?null:radioAuthority.persisted().progress.get(PlayerController.progressKey(station.id,article.id,article.version));player.appendMaterial(station.id,station.name,article,seed);}}
    }
    SourceCoordinator.DownloadProgress downloadProgress(){return lastDownloadProgress;}
    String sourceDiagnosticDetails(String id){try{File file=new File(getCacheDir(),"last-load-failure-v1.json");if(!file.isFile()||file.length()>LoadFailureSnapshot.MAX_BYTES)return "";String json=new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8);return json.contains(SourceLimits.sha256(id))?json:"";}catch(Exception unavailable){return "";}}

    private final ru.bububu.radio.core.RadioResultState radioResultState=new ru.bububu.radio.core.RadioResultState();
    boolean showRadioDownloadResult(){SourceCoordinator.DownloadProgress p=lastDownloadProgress;return p!=null&&radioResultState.visible(p.runId,p.finished,p.cancelled||p.stopFailure!=null||p.failed>0||!p.failedSources.isEmpty()||downloadOfferError,p.updateAir,canAddDownloaded()||addingDownloaded);}
    boolean radioDownloadErrorVisible(){SourceCoordinator.DownloadProgress p=lastDownloadProgress;return p!=null&&radioResultState.errorVisible(p.runId,p.cancelled||p.stopFailure!=null||p.failed>0||!p.failedSources.isEmpty()||downloadOfferError);}
    void hideRadioDownloadResult(){if(lastDownloadProgress!=null){radioResultState.dismiss(lastDownloadProgress.runId);emitNotes();}}
    private String dismissedDownload,downloadOfferMessage="";private boolean addingDownloaded,downloadOfferError,downloadReviewPending;private long downloadOfferEpoch;
    boolean canAddDownloaded(){return lastDownloadProgress!=null&&lastDownloadProgress.finished&&!lastDownloadProgress.updateAir&&!lastDownloadProgress.savedRecordKeys.isEmpty()&&!lastDownloadProgress.runId.equals(dismissedDownload);}
    boolean addingDownloaded(){return addingDownloaded;}
    boolean downloadReviewPending(){return canAddDownloaded() && downloadReviewPending;}
    String downloadOfferMessage(){return downloadOfferMessage;}
    boolean downloadOfferError(){return downloadOfferError;}
    private void offerFailed(String run,String message){addingDownloaded=false;downloadOfferError=true;radioResultState.revealErrors(run);downloadOfferMessage=message;emitNotes();}
    void leaveDownloadedForLater(){if(canAddDownloaded()&&!addingDownloaded){dismissedDownload=lastDownloadProgress.runId;downloadOfferError=false;if(!downloadReviewPending)downloadOfferMessage="Материалы сохранены в новостях. Их можно добавить в эфир позже.";downloadReviewPending=false;emitNotes();}}
    void addDownloadedToAir(){
        if(!canAddDownloaded()||downloadReviewPending||addingDownloaded||sources.batchRunning())return;final SourceCoordinator.DownloadProgress download=lastDownloadProgress;final long epoch=++downloadOfferEpoch;addingDownloaded=true;downloadOfferError=false;downloadOfferMessage="Проверяем загруженные материалы…";emitNotes();
        try{sources.prepareDownloadedSnapshot(download,result->{
            if(epoch!=downloadOfferEpoch)return;
            if(lastDownloadProgress!=download){addingDownloaded=false;emitNotes();return;}
            if(!result.successful()){offerFailed(download.runId,"Добавление не началось: "+result.failure.message(new AndroidSourceClock()));return;}
            try {
                sources.enqueueBulk(result.value,false,progress->{
                    if (epoch!=downloadOfferEpoch) return;
                    downloadOfferMessage=progress.message;
                    if (progress.finished) {
                        addingDownloaded=false;
                        downloadOfferError=progress.errors>0||progress.stopFailure!=null||progress.cancelled||progress.uncertain;
                        downloadReviewPending=!downloadOfferError && progress.needsReview();
                        if (downloadOfferError) radioResultState.revealErrors(download.runId);
                        else if (!downloadReviewPending) dismissedDownload=download.runId;
                        downloadOfferMessage=progress.downloadResult(new AndroidSourceClock());
                    }
                    emitNotes();
                });
            }
            catch(RuntimeException unavailable){offerFailed(download.runId,"Добавление не подтверждено. Загруженные материалы сохранены.");}
        });}catch(RuntimeException unavailable){offerFailed(download.runId,"Добавление не началось. Загруженные материалы сохранены.");}
    }
    private void recordDownload(SourceCoordinator.DownloadProgress progress){if(lastDownloadProgress==null||!lastDownloadProgress.runId.equals(progress.runId)){++downloadOfferEpoch;addingDownloaded=false;downloadOfferError=false;downloadReviewPending=false;downloadOfferMessage="";}lastDownloadProgress=progress;if(progress.finished)failedAirSources=progress.failedSources;emitNotes();}
    void downloadSources(java.util.Set<String> selected,int count){cancelPendingListen();if(selected.isEmpty()){airMessage="Выберите станции для загрузки.";emitNotes();return;}sources.downloadBatch(selected,count,this::recordDownload);}
    boolean hasRealAir(){if(noteBook==null)return false;for(NoteBook.Material m:noteBook.materials())if(m.sourceVersionKey!=null)return true;return false;}
    String preparationFacts(){
        String voice=bundledSetupStatus!=null?bundledSetupFailed?"Не удалось подготовить":"Готовится…":speech.ready()?"Готов":"Запускается…";
        String notes=bundledSetupStatus!=null?bundledSetupFailed?"Не удалось подготовить":"Готовится…":"Готова";
        String air=hasRealAir()?"Материалы готовы":waitingToListen()?"Готовим новости…":lastDownloadProgress!=null&&lastDownloadProgress.finished?DownloadPresentation.outcome(lastDownloadProgress):canPrepareAir()?"Нажмите «Слушать»":"Выберите станции в «Источниках»";
        return "Голос — "+voice+"\nЗапись заметок — "+notes+"\nПервый эфир — "+air+(bundledSetupFailed?"\n"+bundledSetupStatus:"");
    }

    SourceCoordinator sources() { return sources; }
    String sourcesProblem() { return sourcesProblem; }
    void cancelSourceUi() { cancelPendingListen();++downloadOfferEpoch;if(addingDownloaded){addingDownloaded=false;downloadOfferMessage="Добавление отменено. Загруженные материалы сохранены.";}if (sources != null) sources.cancelUi(); }
    void cancelSourceDownload(String runId){if(sources!=null)sources.cancelDownload(runId);}
    void requireDraftStorage(long bytes){if(noteBook==null||noteBook.storageUncertain()||notesProblem!=null)throw new IllegalStateException("Draft authority is unconfirmed");sourceBudget().require(bytes);}
    private SourceDiskBudget sourceBudget() {
        return new SourceDiskBudget(noteStorage.directory, new File(getNoBackupFilesDir(), "source-cas"),
                new File(getNoBackupFilesDir(), "source-network"), getFilesDir());
    }
    private void requireSourcesMutable() {
        if (notesProblem != null || radioStore.restoreFailed() || noteBook == null || noteBook.storageUncertain()) throw new IllegalStateException("Хранилище недоступно; файлы сохранены");
        radioAuthority.requireKnown();
    }
    private void requireQueue(SourceRecord source, Article article) {
        List<Station> candidate = new ArrayList<>(player.stations());
        boolean found = false;
        for (int i = 0; i < candidate.size(); i++) if (candidate.get(i).id.equals(source.stationId)) {
            Station old = candidate.get(i); List<Article> items = new ArrayList<>(old.articles);
            boolean identical = false;
            for (Article existing : items) if (existing.id.equals(article.id)) {
                if (!existing.version.equals(article.version)) throw new IllegalArgumentException("Сначала уберите выбранную версию из эфира");
                identical = true;
            }
            if (!identical) items.add(article); candidate.set(i, new Station(old.id, old.name, items)); found = true; break;
        }
        if (!found) candidate.add(new Station(source.stationId, source.name, java.util.Collections.singletonList(article)));
        RadioStateSizeBudget.requireFits(candidate);
    }
    private void writeSourceLoadSnapshot(String json) {
        android.util.AtomicFile file = new android.util.AtomicFile(new File(getCacheDir(), "last-load-failure-v1.json"));
        if (json == null) { file.delete(); return; }
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > LoadFailureSnapshot.MAX_BYTES) throw new IllegalArgumentException("Diagnostic size");
        java.io.FileOutputStream output = null;
        try { output = file.startWrite(); output.write(bytes); file.finishWrite(output); }
        catch (Exception failure) { if (output != null) file.failWrite(output); throw new IllegalStateException("Diagnostic unavailable", failure); }
    }
    private void createSources() {
        try {
            SourceClock sourceClock = new AndroidSourceClock();
            SourceTransport transport;
            try { transport = new SafeHttpTransport(AndroidSourceDirectories.prepareNetwork(getNoBackupFilesDir()), sourceClock); }
            catch (SourceFailure unavailable) {
                sourcesProblem = "Сетевые ограничения не удалось прочитать. Новые запросы заблокированы; сохранённые тексты доступны.";
                transport = (url, kind, validators, scope) -> { throw new SourceFailure("COOLDOWN_STORAGE", unavailable); };
            }
            SourceBodyCache cache = new SourceBodyCache(new File(getNoBackupFilesDir(), "source-cas"));
            sources = new SourceCoordinator(noteBook, transport, cache, new SourceCoordinator.Port() {
                @Override public void dispatch(Runnable action) { handler.post(action); }
                @Override public void writeLoadFailureSnapshot(String json) { writeSourceLoadSnapshot(json); }
                @Override public void requireMutable() { requireSourcesMutable(); }
                @Override public long radioEpoch() { return radioAuthority.epoch(); }
                @Override public void beforeSourceSelectionChange(){cancelPendingListen();++selectionGeneration;}
                @Override public String currentIdentity(){return RadioService.this.currentIdentity();}
                @Override public void requireStorage(long extraPeak) { sourceBudget().require(extraPeak); }
                @Override public void requireQueue(SourceRecord source, Article article) { RadioService.this.requireQueue(source, article); }
                @Override public void appendStation(SourceRecord source) { player.appendStation(source.stationId, source.name); }
                @Override public void append(NoteBook.Material m) { player.appendMaterial(m.stationId, m.stationName, m.asArticle(), m.selectionInitialProgress); }
                @Override public PlayerController.SavedProgress persisted(NoteBook.Material m) {
                    radioAuthority.requireKnown();
                    if (radioAuthority.persisted() == null) throw new IllegalStateException("Сохранение версии не подтверждено");
                    PlayerController.SavedProgress p = radioAuthority.persisted().progress.get(PlayerController.progressKey(m.stationId,m.articleId,m.version));
                    if (p == null) throw new IllegalStateException("Сохранение версии не подтверждено");
                    return p;
                }
                @Override public void pauseIfSelected(NoteBook.Material m) {
                    PlayerController.Snapshot current = player.snapshot();
                    if (current.article != null && m.stationId.equals(current.station.id) && m.articleId.equals(current.article.id)) {
                        applicationPlay.cancel(); player.pause();
                        if (!player.isOutputStopped()) throw new IllegalStateException("Остановка речи не подтверждена");
                    }
                }
                @Override public void remove(NoteBook.Material m) { player.removeMaterial(m.stationId,m.articleId,m.boundaryMarker()); }
                @Override public void projectRead(SourceRecord source, SourceItem item) {
                    for (SourceVersionRecord version : noteBook.sourceVersions(source.id,item.itemKey)) player.setRead(source.stationId,item.articleId,version.versionKey,item.read);
                }
                @Override public void captureHistory() {
                    radioAuthority.requireKnown();
                    if (radioAuthority.persisted() == null) throw new IllegalStateException("Сохранение версии не подтверждено");
                    noteBook.captureSourceHistory(radioAuthority.persisted()); historyFailed = false;
                }
                @Override public PlayerController.SavedState persistedState(){radioAuthority.requireKnown();return radioAuthority.persisted();}
                @Override public void checkpointDeletion(String stationId,String articleId){
                    PlayerController.Snapshot current=player.snapshot();
                    deletionCurrentAffected=current.station.id.equals(stationId)&&(articleId==null||current.article!=null&&current.article.id.equals(articleId));
                    if(deletionCurrentAffected){
                        applicationPlay.cancel();
                        if(player.isOutputStopped())player.checkpoint();else player.pause();
                        if(!player.isOutputStopped())throw new IllegalStateException("Остановка речи не подтверждена");
                        radioAuthority.requireKnown();
                    }
                }
                @Override public void checkpointMutation(List<NoteBook.Material> removed,boolean clear){
                    PlayerController.Snapshot current=player.snapshot();
                    deletionCurrentAffected=clear||current.article!=null&&removed.stream().anyMatch(m->m.stationId.equals(current.station.id)&&m.articleId.equals(current.article.id));
                    if(deletionCurrentAffected){
                        applicationPlay.cancel();if(player.isOutputStopped())player.checkpoint();else player.pause();
                        if(!player.isOutputStopped())throw new IllegalStateException("Остановка речи не подтверждена");
                        radioAuthority.requireKnown();
                    }
                }
                @Override public void projectMutation(NoteBook.Material replacement,boolean clear){
                    boolean affected=deletionCurrentAffected;projectDeletion();
                    if(clear)player.requireExplicitSelection();
                    else if(replacement!=null&&affected)player.selectArticle(replacement.stationId,replacement.articleId);
                    radioAuthority.requireKnown();
                }
                @Override public void projectDeletion(){
                    // Only proven current-target Delete revokes old notification/session capabilities.
                    if(deletionCurrentAffected){deletionCurrentAffected=false;closeSystemPlayback();}
                    if(noteBook.storageUncertain()||notesProblem!=null)throw new IllegalStateException("Хранилище недоступно; файлы сохранены");
                    if(!radioAuthority.known())radioAuthority.load();
                    radioAuthority.requireKnown();
                    PlayerController.SavedState reconciled=noteBook.reconcileState(radioAuthority.persisted());
                    player.projectCommittedDeletion(localCatalog(),reconciled);
                    radioAuthority.requireKnown();
                }
                @Override public void cleanDeletedBodies(List<SourceVersionRecord> candidates){
                    if(!radioAuthority.known()||noteBook.storageUncertain())return;
                    try{for(SourceVersionRecord version:candidates)if(noteStorage.canDiscardSourceBody(version.recordKey))cache.discard(version);}
                    catch(Exception preserved){event("source-cache-cleanup-retained","unverified-or-unavailable");}
                }
                @Override public void changed() { emitNotes(); }
            }, sourceClock);
        } catch (Exception failure) { sourcesProblem = "Источники недоступны. Сохранённые тексты и заметки остаются на телефоне."; }
    }
    void setArticleRead(String stationId, Article article, boolean read) {
        if (sources != null && noteBook != null) for (SourceRecord source : noteBook.sources()) if (source.stationId.equals(stationId)) {
            for (SourceItem item : noteBook.sourceItems(source.id)) if (item.articleId.equals(article.id)) { sources.setRead(source.id,item.itemKey,read); return; }
        }
        player.setRead(stationId,article.id,article.version,read);
    }
    private void createNotes() {
        noteAudio = new AndroidNoteAudio(getApplicationContext(), notesHandler, noteStorage.directory, new AndroidNoteAudio.FocusPort() {
            @Override public boolean acquire(boolean recording) { return acquireNoteFocus(recording); }
            @Override public void release() { releaseNoteFocus(); }
        });
        noteRecognition = new AndroidOnDeviceProbe(getApplicationContext(), notesHandler);
        fileTranscriber = new AndroidFileTranscriber(getApplicationContext(), notesHandler, noteStorage.directory);
        gigaNotes = BuildConfig.MODEL_EXPERIMENT && getSharedPreferences("local-model-choice", 0).getBoolean("giga-notes", false);
        notes = new NoteCoordinator(noteBook, new NoteCoordinator.Radio() {
            @Override public void pause() { player.pause(); }
            @Override public boolean outputStopped() { return player.isOutputStopped() && !AndroidNoteAudio.isOutputBlocked(); }
        }, new NoteCoordinator.Recorder() {
            @Override public void start(String id, Consumer<NoteCoordinator.RecordingResult> callback) {
                noteAudio.start(id, result -> callback.accept(new NoteCoordinator.RecordingResult(result.id,
                        result.durationMs, result.hasAudio, result.interrupted, result.message, result.stoppedConfirmed,result.discarded)));
            }
            @Override public void stop(String reason) { noteAudio.stopRecording(reason); }
            @Override public boolean discardable(String id){return noteAudio.discardableTake(id);}
            @Override public boolean discard(String id,java.util.function.BooleanSupplier authority){return noteAudio.discardTake(id,authority);}
        }, new NoteCoordinator.Original() {
            @Override public void play(String id, long position, Consumer<NoteCoordinator.OriginalEvent> callback) {
                if (!activityVisible || !notesPage) throw new IllegalStateException("Original page not visible");
                noteAudio.play(id, position, event -> callback.accept(new NoteCoordinator.OriginalEvent(
                        event.kind.name(), event.positionMs, event.message)));
            }
            @Override public long pause() { return noteAudio.pauseOriginal(); }
            @Override public long position() { return noteAudio.currentPosition(); }
            @Override public void seek(long position) { noteAudio.seekOriginal(position); }
        }, new NoteCoordinator.Recognition() {
            @Override public boolean supportsFileTranscription() { return true; }
            @Override public boolean immutableLocalFile(){return gigaNotes;}
            @Override public void probe(Consumer<NoteCoordinator.RecognitionResult> callback) {
                if (bundledSetupStatus != null && !systemNotesExplicit) {
                    callback.accept(new NoteCoordinator.RecognitionResult("UNAVAILABLE", bundledSetupStatus)); return;
                }
                if (gigaNotes) {
                    String reason = ModelsController.get(RadioService.this).notesAvailability();
                    callback.accept(new NoteCoordinator.RecognitionResult(reason == null ? "READY" : "UNAVAILABLE", reason == null ? "GigaAM готов для сохранённых заметок до 120 секунд" : reason)); return;
                }
                noteRecognition.probe(result -> callback.accept(new NoteCoordinator.RecognitionResult(result.status.name(), result.message)));
            }
            @Override public void transcribe(String id, Consumer<NoteCoordinator.RecognitionResult> callback) {
                if (bundledSetupStatus != null && !systemNotesExplicit) {
                    callback.accept(new NoteCoordinator.RecognitionResult("ERROR", bundledSetupStatus, null)); return;
                }
                if (!activityVisible || !notesPage) { callback.accept(new NoteCoordinator.RecognitionResult("CANCELLED", "Откройте заметки, чтобы расшифровать запись.")); return; }
                if (!gigaNotes) { fileTranscriber.transcribe(id, callback); return; }
                long epoch = ++noteRecognitionEpoch;
                noteModelTask = ModelsController.get(RadioService.this).transcribeNote(noteStorage.directory, id, new ModelsController.Result<String>() {
                    private boolean current() { return !destroyed && epoch == noteRecognitionEpoch && activityVisible && notesPage; }
                    @Override public void success(String text) { if (current()) { noteModelTask = null; callback.accept(new NoteCoordinator.RecognitionResult("READY", "Текст GigaAM готов. Оригинальная запись сохранена.", text)); } }
                    @Override public void error(String message) { if (current()) { noteModelTask = null; callback.accept(new NoteCoordinator.RecognitionResult("ERROR", message + " Оригинальная запись сохранена.", null)); } }
                });
            }
            @Override public void cancel() { cancelNoteRecognition(); }
            @Override public void selectLocal(boolean selected) {
                if (selected && ModelsController.get(RadioService.this).notesAvailability() != null) throw new IllegalStateException("GigaAM недоступен");
                if (!getSharedPreferences("local-model-choice", 0).edit().putBoolean("giga-notes", selected).commit()) {
                    getSharedPreferences("local-model-choice", 0).edit().putBoolean("giga-notes", gigaNotes).commit();
                    throw new IllegalStateException("Выбор не сохранён");
                }
                gigaNotes = selected;
            }
        }, this::emitNotes);
    }
    private boolean acquireNoteFocus(boolean recording) {
        if (recording && bundledSetupStatus != null && !systemNotesExplicit) return false;
        releaseNoteFocus();
        if (!activityVisible || !notesPage) return false;
        int token = ++noteFocusGeneration;
        noteFocus = new AudioFocusRequest.Builder(recording ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setWillPauseWhenDucked(true).setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(change -> {
                    if (token != noteFocusGeneration) return;
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) if (notes != null) notes.interrupt();
                }, notesHandler).build();
        boolean granted = audio.requestAudioFocus(noteFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!granted) releaseNoteFocus();
        return granted;
    }
    private void releaseNoteFocus() {
        ++noteFocusGeneration;
        if (noteFocus != null) audio.abandonAudioFocusRequest(noteFocus);
        noteFocus = null;
    }
    String diagnostics() {
        StringBuilder text = new StringBuilder("Радио Бубубу " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n");
        text.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\nAndroid ")
                .append(Build.VERSION.RELEASE).append(" · API ").append(Build.VERSION.SDK_INT).append("\nПрошивка: ").append(Build.DISPLAY).append('\n')
                .append(speech.description()).append("\n1× · onStart — событие движка, не измерение слышимого звука\n");
        text.append("Пауза: сессия/foreground до 10 мин, без фокуса/wakelock; затем управление закрывается.\n");
        text.append("Заметки: PCM16 mono 16kHz, до 2 мин; оригиналы локально. Расшифровка сохранённого файла — установленным движком телефона, если поддерживается.\n");
        if (notesProblem != null) text.append("Заметки: ").append(notesProblem).append('\n');
        if (noteBook != null && noteBook.reconciliationMessage() != null)
            text.append("Восстановление эфира: ").append(noteBook.reconciliationMessage()).append(". Возврат к началу не выполнялся.\n");
        if (noteBook != null && noteBook.migrationWarning() != null) text.append(noteBook.migrationWarning()).append('\n');
        if (sourcesProblem != null) text.append(sourcesProblem).append('\n');
        for (String event : events) text.append(event).append('\n');
        return text.toString();
    }

    @Override public void onDestroy() {
        if(SourceBootstrap45.blocked(this)){destroyed=true;super.onDestroy();return;}
        cancelPendingListen();airMix.coldReset();
        destroyed = true;
        ++bundledSetupEpoch;
        if (bundledTask != null) { bundledTask.cancel(); bundledTask = null; }
        applicationPlay.cancel();
        activityVisible = false; notesPage = false;
        if (sources != null) sources.close();
        if (notes != null) try { notes.close(); } catch (RuntimeException ignored) { /* Draft/original recovery remains durable. */ }
        try { cancelNoteRecognition(); } catch (RuntimeException ignored) { /* Other audio owners still require cleanup. */ }
        if (noteAudio != null) try { noteAudio.close(); } catch (RuntimeException ignored) { /* Continue releasing radio even after a note error. */ }
        if (noteRecognition != null) try { noteRecognition.cancel(); } catch (RuntimeException ignored) { }
        if (fileTranscriber != null) try { fileTranscriber.cancel(); } catch (RuntimeException ignored) { /* Close file input even if notes shutdown failed. */ }
        try { releaseNoteFocus(); } catch (RuntimeException ignored) { /* Native note owner remains blocked; still close radio. */ }
        noteListeners.clear();
        systemClosed = true;
        pausedSessionLease.cancel();
        if (player != null) player.pause();
        if (speech != null) try { speech.close(); } catch (RuntimeException ignored) { }
        // The framework is already destroying the service; only release playback resources.
        releaseSystemPlayback();
        unregisterReceiver(noisy);
        handler.removeCallbacksAndMessages(null);
        listeners.clear();
        super.onDestroy();
    }
}

package ru.bububu.radio;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import ru.bububu.radio.core.PlayerController;

/** Owns one platform TTS request. Every callback is serialized on the owner looper. */
final class AndroidSpeechEngine implements PlayerController.SpeechEngine {
    interface Host {
        String prepareOutput();
        void started(String id);
        void done(String id);
        void failed(String id, String reason);
        void voiceChanged();
        void event(String name, String id);
    }
    /** The platform boundary also lets JVM tests exercise the real initialization callback path. */
    // Must enqueue on the owner thread, never invoke inline (including during backend creation).
    interface Dispatcher { void post(Runnable callback); }
    interface InitCallback { void completed(int status); }
    interface BackendFactory { Backend create(InitCallback callback); }
    interface UtteranceCallback { void event(String id, String kind, String reason); }
    interface Backend {
        void setUtteranceCallback(UtteranceCallback callback);
        List<VoiceInfo> voices();
        String defaultVoiceName();
        int setVoice(VoiceInfo voice);
        void configureSpeech();
        String engineDescription();
        VoiceInfo currentVoice();
        int maxInputLength();
        int speak(String text, String id);
        int stop();
        void shutdown();
    }
    static final class VoiceInfo {
        final String name;
        final String language;
        final boolean networkRequired;
        final boolean notInstalled;

        VoiceInfo(String name, String language, boolean networkRequired, boolean notInstalled) {
            this.name = name;
            this.language = language;
            this.networkRequired = networkRequired;
            this.notInstalled = notInstalled;
        }

        boolean localRussian() { return "ru".equals(language) && !networkRequired && !notInstalled; }
    }
    private final Dispatcher dispatcher;
    private final BackendFactory backendFactory;
    private final Host host;
    private Backend backend;
    private boolean initializing;
    private boolean closed;
    private int engineGeneration;
    private String activeId;
    private String pendingText;
    // Survives request cancellation until this backend confirms completion or stop.
    private boolean backendMaySpeak;
    private String problem;
    private String voiceDescription = "Проверяем локальный русский голос…";

    AndroidSpeechEngine(Context context, Handler handler, Host host) {
        this(host, callback -> new PlatformBackend(context, callback), handler::post);
    }

    AndroidSpeechEngine(Host host, BackendFactory backendFactory, Dispatcher dispatcher) {
        this.host = host;
        this.backendFactory = backendFactory;
        this.dispatcher = dispatcher;
        initialize();
    }

    private void initialize() {
        initializing = true;
        problem = null;
        voiceDescription = "Проверяем локальный русский голос…";
        final int generation = ++engineGeneration;
        backend = backendFactory.create(status -> dispatcher.post(() -> {
            if (closed || generation != engineGeneration || !initializing) return;
            initializing = false;
            if (status != TextToSpeech.SUCCESS) {
                problem = "Системный TTS не запустился. Проверьте настройки синтеза речи.";
                voiceDescription = problem;
            } else {
                backend.setUtteranceCallback((id, kind, reason) -> dispatcher.post(() -> {
                    if (closed || generation != engineGeneration || id == null || !id.equals(activeId)) return;
                    host.event(kind, id);
                    if (kind.equals("start")) host.started(id);
                    else {
                        activeId = null;
                        pendingText = null;
                        if (kind.equals("done")) {
                            backendMaySpeak = false;
                            host.done(id);
                        } else host.failed(id, reason);
                    }
                }));
                chooseLocalVoice();
            }
            host.voiceChanged();
            if (activeId != null) {
                if (problem != null) failActive(problem);
                else submit(activeId, pendingText);
            }
        }));
    }

    private void chooseLocalVoice() {
        try {
            List<VoiceInfo> voices = backend.voices();
            List<VoiceInfo> local = new ArrayList<>();
            if (voices != null) for (VoiceInfo voice : voices) {
                if (voice.localRussian()) local.add(voice);
            }
            String defaultVoice = backend.defaultVoiceName();
            local.sort(Comparator.comparingInt((VoiceInfo voice) -> voice.name.equals(defaultVoice) ? 0 : 1)
                    .thenComparing(voice -> voice.name));
            if (local.isEmpty()) {
                problem = "Нет установленного локального русского голоса. Откройте настройки TTS и проверьте данные голоса; затем вернитесь и нажмите Play.";
            } else if (backend.setVoice(local.get(0)) != TextToSpeech.SUCCESS) {
                problem = "Не удалось выбрать локальный русский голос. Проверьте настройки TTS.";
            } else {
                backend.configureSpeech();
                voiceDescription = "TTS по умолчанию: " + backend.engineDescription() + "\nГолос: " + local.get(0).name
                        + " · ru · локальный по данным движка · 1×";
                problem = null;
            }
        } catch (RuntimeException failure) {
            problem = "Не удалось проверить локальный голос. Проверьте системный TTS.";
        }
        if (problem != null) voiceDescription = problem;
    }

    boolean ready(){return !closed&&!initializing&&problem==null;}
    boolean terminalError(){return !initializing&&problem!=null;}
    String description() { return voiceDescription; }

    void refreshIfIdle() {
        if (activeId != null || backendMaySpeak || initializing || closed) return;
        // Settings may have changed the system engine; reconnect only while paused.
        if (backend != null) backend.shutdown();
        initialize();
        host.voiceChanged();
    }

    @Override public void speak(String text, String requestId) {
        if (backendMaySpeak) stop();
        activeId = requestId;
        pendingText = text;
        host.event("request", requestId);
        if (closed) { failActive("Движок закрыт. Откройте приложение снова."); return; }
        // Start foreground/focus even while the voice initializes; this does not start sound.
        String outputProblem = host.prepareOutput();
        if (outputProblem != null) { failActive(outputProblem); return; }
        if (initializing) return;
        if (problem != null) { failActive(problem); return; }
        submit(requestId, text);
    }

    private void submit(String id, String text) {
        if (!id.equals(activeId) || closed) return;
        try {
            VoiceInfo voice = backend.currentVoice();
            if (voice == null || !voice.localRussian()) {
                failActive("Локальный русский голос недоступен. Чтение остановлено.");
                return;
            }
            if (text == null || text.length() > backend.maxInputLength()) {
                failActive("Фрагмент превышает ограничение TTS."); return;
            }
            // Even a failure from speak can leave the remote engine's state uncertain.
            backendMaySpeak = true;
            int result = backend.speak(text, id);
            if (result != TextToSpeech.SUCCESS) failActive("TTS отклонил запрос чтения.");
        } catch (RuntimeException failure) {
            failActive("Системный TTS недоступен. Продолжите после проверки настроек.");
        }
    }

    private void failActive(String reason) {
        String id = activeId;
        activeId = null;
        pendingText = null;
        // Defer to avoid reentering the controller from its speak() call.
        if (id != null) dispatcher.post(() -> host.failed(id, reason));
    }

    @Override public void stop() {
        String old = activeId;
        activeId = null;
        pendingText = null;
        if (old != null) host.event("cancel", old);
        if (!backendMaySpeak) return;
        if (backend.stop() != TextToSpeech.SUCCESS) {
            throw new IllegalStateException("TTS did not confirm stop");
        }
        backendMaySpeak = false;
    }

    void close() {
        try { stop(); }
        finally {
            closed = true;
            ++engineGeneration;
            if (backend != null) backend.shutdown();
        }
    }

    /** All Android calls stay here; the adapter's state and callback guards are shared with tests. */
    private static final class PlatformBackend implements Backend {
        private final Context context;
        private final TextToSpeech tts;
        private final java.util.Map<String, Voice> installedVoices = new java.util.HashMap<>();

        PlatformBackend(Context context, InitCallback initialized) {
            this.context = context;
            tts = new TextToSpeech(context, initialized::completed);
        }

        @Override public void setUtteranceCallback(UtteranceCallback callback) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { callback.event(id, "start", null); }
                @Override public void onDone(String id) { callback.event(id, "done", null); }
                @Override public void onError(String id) { callback.event(id, "error", "Ошибка TTS"); }
                @Override public void onError(String id, int code) {
                    callback.event(id, "error", "Ошибка TTS, код " + code);
                }
                @Override public void onStop(String id, boolean interrupted) {
                    callback.event(id, "error", "Движок остановил чтение. Продолжите по Play.");
                }
            });
        }

        @Override public List<VoiceInfo> voices() {
            Set<Voice> voices = tts.getVoices();
            List<VoiceInfo> result = new ArrayList<>();
            installedVoices.clear();
            if (voices != null) for (Voice voice : voices) {
                installedVoices.put(voice.getName(), voice);
                result.add(info(voice));
            }
            return result;
        }

        @Override public String defaultVoiceName() {
            Voice voice = tts.getDefaultVoice();
            return voice == null ? null : voice.getName();
        }
        @Override public int setVoice(VoiceInfo voice) { return tts.setVoice(installedVoices.get(voice.name)); }
        @Override public void configureSpeech() {
            tts.setSpeechRate(1.0f);
            tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        }
        @Override public String engineDescription() {
            String engine = tts.getDefaultEngine();
            String version = "";
            try { version = " " + context.getPackageManager().getPackageInfo(engine, 0).versionName; }
            catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
            return engine + version;
        }
        @Override public VoiceInfo currentVoice() { return info(tts.getVoice()); }
        @Override public int maxInputLength() { return TextToSpeech.getMaxSpeechInputLength(); }
        @Override public int speak(String text, String id) {
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true");
            return tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id);
        }
        @Override public int stop() { return tts.stop(); }
        @Override public void shutdown() { tts.shutdown(); }

        private static VoiceInfo info(Voice voice) {
            if (voice == null) return null;
            Set<String> features = voice.getFeatures();
            return new VoiceInfo(voice.getName(), voice.getLocale().getLanguage(),
                    voice.isNetworkConnectionRequired(),
                    features != null && features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
        }
    }
}

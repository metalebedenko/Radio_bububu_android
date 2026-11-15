package ru.bububu.radio;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Runs the production adapter's callbacks with a fake platform TTS, without an Android runtime. */
public final class AndroidSpeechEngineTest {
    private static final int SUCCESS = 0;
    private static final int ERROR = -1;
    private static final String CHECKING = "Проверяем локальный русский голос…";
    private static final String INIT_ERROR = "Системный TTS не запустился. Проверьте настройки синтеза речи.";

    @Test public void systemBackendReceivesOriginalFramingAndControlsWithoutProjection() {
        Fixture f = new Fixture();
        f.initialize(SUCCESS);
        String original="Источник\n\nЗаголовок\n\nТекст.\r\t";
        f.engine.speak(original,"original");
        assertEquals(Arrays.asList("original: "+original),f.first().requests);
    }

    @Test public void initializationFailureBeforePlayUpdatesDiagnosticAndNotifiesHost() {
        Fixture f = new Fixture();
        assertEquals(CHECKING, f.engine.description());
        f.first().initialized.completed(ERROR);
        assertEquals(CHECKING, f.engine.description()); // Callback must run on the owner dispatcher.
        assertEquals(0, f.host.voiceChanges);
        f.dispatcher.drain();
        assertEquals(INIT_ERROR, f.engine.description());
        assertEquals(1, f.host.voiceChanges);
        assertTrue(f.host.failures.isEmpty());
        assertTrue(f.first().requests.isEmpty());
    }

    @Test public void noInstalledLocalRussianVoiceFinishesCheckingWithSeparateMessage() {
        Fixture f = new Fixture();
        f.first().available = Arrays.asList(voice("en-local", "en", false, false),
                voice("ru-network", "ru", true, false), voice("ru-uninstalled", "ru", false, true));
        f.initialize(SUCCESS);
        assertTrue(f.engine.description().startsWith("Нет установленного локального русского голоса."));
        assertNotEquals(INIT_ERROR, f.engine.description());
        assertEquals(1, f.host.voiceChanges);
        assertEquals(0, f.first().voiceSelections);
        assertTrue(f.host.failures.isEmpty());
    }

    @Test public void successShowsChosenLocalVoiceAndPrefersSystemDefault() {
        Fixture f = new Fixture();
        f.first().available = Arrays.asList(voice("a-local", "ru", false, false),
                voice("z-default", "ru", false, false), voice("cloud", "ru", true, false));
        f.first().defaultName = "z-default";
        f.initialize(SUCCESS);
        assertEquals("TTS по умолчанию: FakeTTS 1\nГолос: z-default · ru · локальный по данным движка · 1×",
                f.engine.description());
        assertEquals("z-default", f.first().selected.name);
        assertEquals(1, f.first().configurationCalls);
        assertEquals(1, f.host.voiceChanges);
        assertTrue(f.first().requests.isEmpty());
    }

    @Test public void rejectedVoiceAndVoiceQueryFailureBothFinishDiagnostic() {
        Fixture rejected = new Fixture();
        rejected.first().setVoiceResult = ERROR;
        rejected.initialize(SUCCESS);
        assertEquals("Не удалось выбрать локальный русский голос. Проверьте настройки TTS.",
                rejected.engine.description());
        assertEquals(1, rejected.host.voiceChanges);

        Fixture queryFailed = new Fixture();
        queryFailed.first().queryFailure = true;
        queryFailed.initialize(SUCCESS);
        assertEquals("Не удалось проверить локальный голос. Проверьте системный TTS.",
                queryFailed.engine.description());
        assertEquals(1, queryFailed.host.voiceChanges);
    }

    @Test public void pendingPlayFailsOnceOnInitErrorWithoutRetryOrLateSpeech() {
        Fixture f = new Fixture();
        f.engine.speak("Тестовая фраза.", "request-1");
        assertEquals(1, f.host.prepareCalls);
        assertTrue(f.first().requests.isEmpty());
        f.initialize(ERROR);
        assertEquals(INIT_ERROR, f.engine.description());
        assertEquals(Arrays.asList("request-1: " + INIT_ERROR), f.host.failures);

        // A second/late init completion of the same engine cannot revive the rejected Play.
        f.initialize(SUCCESS);
        assertEquals(INIT_ERROR, f.engine.description());
        assertEquals(1, f.host.voiceChanges);
        assertEquals(1, f.host.failures.size());
        assertTrue(f.first().requests.isEmpty());
        assertEquals(1, f.factory.backends.size());
    }

    @Test public void pendingPlayStartsOnlyCurrentRequestAfterSuccessfulInit() {
        Fixture f = new Fixture();
        f.engine.speak("Старая фраза.", "old");
        f.engine.stop();
        f.engine.speak("Новая фраза.", "current");
        f.initialize(SUCCESS);
        assertEquals(Arrays.asList("current: Новая фраза."), f.first().requests);
        f.first().utterances.event("old", "start", null);
        f.first().utterances.event("old", "done", null);
        f.dispatcher.drain();
        assertTrue(f.host.starts.isEmpty());
        assertTrue(f.host.completions.isEmpty());
        assertTrue(f.host.failures.isEmpty());
    }

    @Test public void cancellationDuringInitKeepsSilenceAfterSuccessfulCallback() {
        Fixture f = new Fixture();
        f.engine.speak("Отменённая фраза.", "cancelled");
        f.engine.stop();
        f.initialize(SUCCESS);
        assertTrue(f.first().requests.isEmpty());
        assertTrue(f.host.failures.isEmpty());
        assertEquals(0, f.first().stopCalls);
        assertEquals(1, f.host.voiceChanges);
    }

    @Test public void unavailableUnusedSystemVoiceDoesNotBlockSwitchToLocalAudio() {
        Fixture f = new Fixture();
        f.initialize(ERROR);
        f.first().stopResult = ERROR;
        f.engine.stop();
        assertEquals(0, f.first().stopCalls);
        assertTrue(f.first().requests.isEmpty());
    }

    @Test public void uncertainStopSurvivesCancelledCallbackAndBlocksAnotherSubmission() {
        Fixture f = new Fixture();
        f.initialize(SUCCESS);
        f.engine.speak("Первая фраза.", "old");
        f.first().stopResult = ERROR;
        assertThrows(IllegalStateException.class, f.engine::stop);
        f.first().utterances.event("old", "done", null);
        f.dispatcher.drain();
        assertTrue(f.host.completions.isEmpty());
        f.engine.refreshIfIdle();
        assertEquals(0, f.first().shutdownCalls);
        assertThrows(IllegalStateException.class, () -> f.engine.speak("Другая фраза.", "new"));
        assertEquals(List.of("old: Первая фраза."), f.first().requests);
        assertEquals(2, f.first().stopCalls);

        f.first().stopResult = SUCCESS;
        f.engine.stop();
        f.engine.stop();
        assertEquals(3, f.first().stopCalls);
        f.engine.speak("Другая фраза.", "new");
        assertEquals(2, f.first().requests.size());
    }

    @Test public void submissionExceptionStillRequiresConfirmedStop() {
        Fixture f = new Fixture();
        f.initialize(SUCCESS);
        f.first().throwOnSpeak = true;
        f.engine.speak("Тестовая фраза.", "request");
        f.dispatcher.drain();
        assertEquals(1, f.host.failures.size());
        f.first().stopResult = ERROR;
        assertThrows(IllegalStateException.class, f.engine::stop);
        f.first().stopResult = SUCCESS;
        f.engine.stop();
        assertEquals(2, f.first().stopCalls);
    }

    @Test public void lateFailureFromOldGenerationCannotChangeNewSuccess() {
        Fixture f = new Fixture();
        FakeBackend old = f.first();
        f.initialize(SUCCESS);
        f.engine.refreshIfIdle();
        assertEquals(1, old.shutdownCalls);
        f.factory.current().engineName = "ReplacementTTS 2";
        f.factory.current().initialized.completed(SUCCESS);
        f.dispatcher.drain();
        String current = f.engine.description();
        assertTrue(current.contains("ReplacementTTS 2"));
        int changes = f.host.voiceChanges;
        old.initialized.completed(ERROR);
        f.dispatcher.drain();
        assertEquals(current, f.engine.description());
        assertEquals(changes, f.host.voiceChanges);
        assertTrue(f.host.failures.isEmpty());
    }

    @Test public void lateSuccessFromOldGenerationCannotChangeNewFailure() {
        Fixture f = new Fixture();
        FakeBackend old = f.first();
        f.initialize(ERROR);
        f.engine.refreshIfIdle();
        f.factory.current().initialized.completed(ERROR);
        f.dispatcher.drain();
        int changes = f.host.voiceChanges;
        old.initialized.completed(SUCCESS);
        f.dispatcher.drain();
        assertEquals(INIT_ERROR, f.engine.description());
        assertEquals(changes, f.host.voiceChanges);
        assertEquals(0, old.voiceSelections);
        assertEquals(0, f.factory.current().voiceSelections);
    }

    @Test public void staleInitCallbacksDuringReplacementDoNotFinishCurrentChecking() {
        Fixture f = new Fixture();
        FakeBackend old = f.first();
        f.initialize(SUCCESS);
        f.engine.refreshIfIdle();
        int changes = f.host.voiceChanges;
        old.initialized.completed(ERROR);
        old.initialized.completed(SUCCESS);
        f.dispatcher.drain();
        assertEquals(CHECKING, f.engine.description());
        assertEquals(changes, f.host.voiceChanges);
        assertEquals(0, f.factory.current().voiceSelections);
        f.factory.current().initialized.completed(SUCCESS);
        f.dispatcher.drain();
        assertNotEquals(CHECKING, f.engine.description());
        assertEquals(changes + 1, f.host.voiceChanges);
    }

    @Test public void lateSuccessAndFailureAfterCloseDoNotChangeDiagnosticOrStartPendingPlay() {
        Fixture f = new Fixture();
        f.engine.speak("Отменённая фраза.", "pending");
        f.engine.close();
        String before = f.engine.description();
        f.first().initialized.completed(ERROR);
        f.first().initialized.completed(SUCCESS);
        f.dispatcher.drain();
        assertEquals(before, f.engine.description());
        assertEquals(0, f.host.voiceChanges);
        assertTrue(f.host.failures.isEmpty());
        assertTrue(f.first().requests.isEmpty());
        assertEquals(1, f.first().shutdownCalls);
        f.engine.refreshIfIdle();
        assertEquals(1, f.factory.backends.size());
    }

    @Test public void utteranceCallbacksRetainCancellationAndGenerationGuards() {
        Fixture f = new Fixture();
        f.initialize(SUCCESS);
        FakeBackend old = f.first();
        f.engine.speak("Первая фраза.", "old");
        old.utterances.event("old", "start", null);
        f.engine.stop(); // Cancel before the queued callback reaches the owner thread.
        f.dispatcher.drain();
        assertTrue(f.host.starts.isEmpty());
        f.engine.refreshIfIdle();
        f.factory.current().initialized.completed(SUCCESS);
        f.dispatcher.drain();
        f.engine.speak("Вторая фраза.", "current");
        old.utterances.event("current", "start", null);
        old.utterances.event("current", "done", null);
        old.utterances.event("current", "error", "Старая ошибка");
        f.dispatcher.drain();
        assertTrue(f.host.starts.isEmpty());
        assertTrue(f.host.completions.isEmpty());
        assertTrue(f.host.failures.isEmpty());

        f.factory.current().utterances.event("current", "start", null);
        f.factory.current().utterances.event("current", "done", null);
        f.dispatcher.drain();
        assertEquals(Arrays.asList("current"), f.host.starts);
        assertEquals(Arrays.asList("current"), f.host.completions);
    }

    @Test public void playAfterInitErrorFailsWithoutImplicitReinitialization() {
        Fixture f = new Fixture();
        f.initialize(ERROR);
        f.engine.speak("Тестовая фраза.", "request");
        assertTrue(f.host.failures.isEmpty()); // Failure is deferred to avoid controller reentry.
        f.dispatcher.drain();
        assertEquals(Arrays.asList("request: " + INIT_ERROR), f.host.failures);
        assertEquals(1, f.factory.backends.size());
        assertTrue(f.first().requests.isEmpty());
        assertEquals(INIT_ERROR, f.engine.description());
    }

    private static AndroidSpeechEngine.VoiceInfo voice(String name, String language,
                                                       boolean network, boolean notInstalled) {
        return new AndroidSpeechEngine.VoiceInfo(name, language, network, notInstalled);
    }

    private static final class Fixture {
        final ManualDispatcher dispatcher = new ManualDispatcher();
        final FakeHost host = new FakeHost();
        final FakeFactory factory = new FakeFactory();
        final AndroidSpeechEngine engine = new AndroidSpeechEngine(host, factory, dispatcher);
        FakeBackend first() { return factory.backends.get(0); }
        void initialize(int status) { first().initialized.completed(status); dispatcher.drain(); }
    }

    private static final class ManualDispatcher implements AndroidSpeechEngine.Dispatcher {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        @Override public void post(Runnable callback) { queue.add(callback); }
        void drain() {
            int remaining = 100;
            while (!queue.isEmpty()) {
                if (--remaining == 0) throw new AssertionError("Callbacks did not become idle");
                queue.remove().run();
            }
        }
    }

    private static final class FakeFactory implements AndroidSpeechEngine.BackendFactory {
        final List<FakeBackend> backends = new ArrayList<>();
        @Override public FakeBackend create(AndroidSpeechEngine.InitCallback callback) {
            FakeBackend backend = new FakeBackend(callback);
            backends.add(backend);
            return backend;
        }
        FakeBackend current() { return backends.get(backends.size() - 1); }
    }

    private static final class FakeBackend implements AndroidSpeechEngine.Backend {
        final AndroidSpeechEngine.InitCallback initialized;
        AndroidSpeechEngine.UtteranceCallback utterances;
        List<AndroidSpeechEngine.VoiceInfo> available = Arrays.asList(voice("ru-local", "ru", false, false));
        AndroidSpeechEngine.VoiceInfo selected;
        String defaultName = "ru-local";
        String engineName = "FakeTTS 1";
        int setVoiceResult = SUCCESS;
        int stopResult = SUCCESS;
        boolean throwOnSpeak;
        boolean queryFailure;
        int voiceSelections;
        int configurationCalls;
        int stopCalls;
        int shutdownCalls;
        final List<String> requests = new ArrayList<>();
        FakeBackend(AndroidSpeechEngine.InitCallback initialized) { this.initialized = initialized; }
        @Override public void setUtteranceCallback(AndroidSpeechEngine.UtteranceCallback callback) {
            utterances = callback;
        }
        @Override public List<AndroidSpeechEngine.VoiceInfo> voices() {
            if (queryFailure) throw new IllegalStateException("Fake voice query failure");
            return available;
        }
        @Override public String defaultVoiceName() { return defaultName; }
        @Override public int setVoice(AndroidSpeechEngine.VoiceInfo voice) {
            voiceSelections++;
            if (setVoiceResult == SUCCESS) selected = voice;
            return setVoiceResult;
        }
        @Override public void configureSpeech() { configurationCalls++; }
        @Override public String engineDescription() { return engineName; }
        @Override public AndroidSpeechEngine.VoiceInfo currentVoice() { return selected; }
        @Override public int maxInputLength() { return 4000; }
        @Override public int speak(String text, String id) {
            requests.add(id + ": " + text);
            if (throwOnSpeak) throw new IllegalStateException("Submission result unknown");
            return SUCCESS;
        }
        @Override public int stop() { stopCalls++; return stopResult; }
        @Override public void shutdown() { shutdownCalls++; }
    }

    private static final class FakeHost implements AndroidSpeechEngine.Host {
        int prepareCalls;
        int voiceChanges;
        final List<String> starts = new ArrayList<>();
        final List<String> completions = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        @Override public String prepareOutput() { prepareCalls++; return null; }
        @Override public void started(String id) { starts.add(id); }
        @Override public void done(String id) { completions.add(id); }
        @Override public void failed(String id, String reason) { failures.add(id + ": " + reason); }
        @Override public void voiceChanged() { voiceChanges++; }
        @Override public void event(String name, String id) { }
    }
}

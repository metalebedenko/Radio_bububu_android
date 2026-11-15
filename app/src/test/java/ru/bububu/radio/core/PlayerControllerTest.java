package ru.bububu.radio.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class PlayerControllerTest {
    @Test public void nextSentenceCheckedPlanIsReusedOnlyAtActualAdmittedSubmission() {
        Harness h=new Harness();h.core.play();String first=h.speech.latest().id;h.core.onStart(first);PlayerController.SpeechWindow hint=h.speech.hints.get(0);
        PlayerController.SpeechSentence next=new PlayerController.SpeechSentence(hint.current.sentence.stationId,hint.current.sentence.article,hint.current.sentence.binding,1);
        List<String> prepared=TextSegments.fragments(next.text(),8);h.speech.preparedSentence=next;h.speech.preparedPlan=prepared;
        assertEquals(1,h.speech.requests.size());assertTrue(h.core.snapshot().completedSentences.isEmpty());
        h.core.onDone(first);assertEquals(1,h.speech.planHits);assertEquals(prepared.get(0),h.speech.latest().text);assertEquals(Collections.singletonList(0),h.core.snapshot().completedSentences);
        String second=h.speech.latest().id;h.core.onDone(first);h.core.onDone(second);assertEquals(2,h.speech.requests.size());assertEquals(Collections.singletonList(0),h.core.snapshot().completedSentences);
        h.core.onStart(second);h.core.onDone(second);assertEquals(3,h.speech.requests.size());assertEquals(Collections.singletonList(0),h.core.snapshot().completedSentences);
    }
    @Test public void lookaheadIsReadOnlyAndBeginsOnlyAfterCurrentConfirmedStart() {
        Harness h=new Harness();h.core.play();assertTrue(h.speech.hints.isEmpty());String id=h.speech.latest().id;
        h.core.onDone(id);assertTrue(h.speech.hints.isEmpty());assertEquals(1,h.speech.requests.size());
        h.core.onStart(id);assertEquals(1,h.speech.hints.size());PlayerController.SpeechWindow window=h.speech.hints.get(0);
        assertTrue(window.allowed());assertEquals(0,window.current.sentence.index);assertEquals(0,window.current.fragment);assertEquals(1,h.speech.requests.size());assertTrue(h.core.snapshot().completedSentences.isEmpty());
        PlayerController.SpeechSentence next=new PlayerController.SpeechSentence(window.current.sentence.stationId,window.current.sentence.article,window.current.sentence.binding,1);
        PlayerController.SpeechUnit future=new PlayerController.SpeechUnit(next,TextSegments.fragments(next.text(),1000),0);
        assertEquals("Второе предложение.",future.text);assertEquals(0,h.core.snapshot().sentenceIndex);assertTrue(h.core.snapshot().completedSentences.isEmpty());
        h.core.pause();assertFalse(window.allowed());h.core.onDone(id);assertTrue(h.core.snapshot().completedSentences.isEmpty());
    }
    @Test public void speculativeFailureCannotTurnCurrentStartedAudioIntoAnError() {
        Harness h=new Harness();h.speech.rejectHints=true;h.core.play();String id=h.speech.latest().id;h.core.onStart(id);
        assertTrue(h.core.snapshot().playing);assertNull(h.core.snapshot().error);h.core.onDone(id);
        assertEquals(Collections.singletonList(0),h.core.snapshot().completedSentences);assertEquals(2,h.speech.requests.size());
    }
    @Test public void uncertainMembershipDoesNotAuthorizeFutureWork() {
        FakeSpeech speech=new FakeSpeech();FakeScheduler clock=new FakeScheduler();MemoryStore store=new MemoryStore();
        AtomicReference<PlaybackMembership.Admission> state=new AtomicReference<>(PlaybackMembership.Admission.ALLOWED);
        PlaybackMembership membership=new PlaybackMembership(){public Binding capture(String station,Article article){return new Binding(4,"v","source");}public Admission check(String station,Article article,Binding binding){return state.get();}public boolean stationRetired(String station){return false;}};
        PlayerController core=new PlayerController(fixtures(),speech,clock,store,s->{},1000,membership);core.play();String id=speech.latest().id;state.set(PlaybackMembership.Admission.UNVERIFIED);core.onStart(id);
        assertTrue(speech.hints.isEmpty());core.onDone(id);assertFalse(core.snapshot().playing);assertEquals(1,speech.requests.size());
    }
    @Test public void backendSmallerCutsReadWholeLongSentenceBeforeAwardingCoverage() {
        String sentence="Источник\n\nЗаголовок\n\n"+"Синтетический длинный текст сохраняется полностью ".repeat(35)+"завершён.";
        Article article=Article.fromSentences("a1","v1","Заголовок",sentence);
        Harness h=new Harness(Arrays.asList(new Station("a","Источник",Arrays.asList(article))),1000);
        h.speech.backendLimit=80;h.core.play();
        int expected=TextSegments.fragments(sentence,80).size();
        for(int i=0;i<expected;i++) {
            assertEquals(0,h.core.snapshot().completedSentences.size());
            assertTrue(h.speech.latest().text.length()<=80);h.complete();
        }
        assertEquals(expected,h.speech.requests.size());
        StringBuilder spoken=new StringBuilder();for(Spoken request:h.speech.requests)spoken.append(request.text.replaceAll("\\s", ""));
        assertEquals(sentence.replaceAll("\\s", ""),spoken.toString());
        assertEquals(Collections.singletonList(0),h.core.snapshot().completedSentences);
        assertTrue(h.core.snapshot().passEnded);assertEquals(sentence,article.text);
    }

    @Test public void backendUnsupportedAfterPauseRejectsBeforeNewStartAndIgnoresOldCallbacks() {
        Harness h=new Harness();h.speech.backendLimit=8;h.core.play();
        String old=h.speech.latest().id;Task deadline=h.clock.latest();h.core.onStart(old);h.core.pause();
        int before=h.speech.requests.size();h.speech.fragmentFailure="UNSUPPORTED_NUMERIC_CONTEXT";h.core.play();
        assertEquals(before,h.speech.requests.size());assertFalse(h.core.snapshot().playing);
        assertTrue(h.core.snapshot().error.contains("UNSUPPORTED_NUMERIC_CONTEXT"));
        h.core.onDone(old);h.core.onError(old,"Поздняя ошибка");deadline.fireEvenIfCancelled();
        assertTrue(h.core.snapshot().error.contains("UNSUPPORTED_NUMERIC_CONTEXT"));
        assertEquals(0,h.core.snapshot().completedSentences.size());
    }

    @Test public void backendCannotOmitTextAndDefaultSystemFragmentationStaysOriginal() {
        Harness broken=new Harness();broken.speech.omitFragmentText=true;broken.core.play();
        assertTrue(broken.speech.requests.isEmpty());assertFalse(broken.core.snapshot().playing);
        assertFalse(broken.core.savedState().progress.get(PlayerController.progressKey("a","a1","v1")).started);
        assertEquals(0,broken.core.snapshot().completedSentences.size());
        String raw="Источник\n\nЗаголовок\n\nТекст.\r\t";
        Article original=Article.fromSentences("a1","v1","Тест",raw);
        Harness system=new Harness(Arrays.asList(new Station("a","Источник",Arrays.asList(original))),1000);
        system.core.play();assertEquals(original.sentences.get(0).text,system.speech.latest().text);
        assertTrue(system.speech.latest().text.contains("\n\n"));
    }

    @Test public void directReadingSubmitsOnlyFirstSentenceWithoutPreparingWholeArticle() {
        Harness h = new Harness();
        h.core.play();
        assertEquals(1, h.speech.requests.size());
        assertEquals("Первое предложение.", h.speech.latest().text);
        assertTrue(h.core.snapshot().playing);
        assertEquals(0, h.core.snapshot().completedSentences.size());
    }

    @Test public void pauseInvalidatesBeforeStopEvenWhenStopReentersOldCallbacks() {
        Harness h = new Harness();
        h.core.play();
        String old = h.speech.latest().id;
        h.core.onStart(old);
        h.speech.onStop = () -> {
            h.core.onDone(old);
            h.core.onError(old, "Поздняя ошибка");
        };
        h.core.pause();
        assertFalse(h.core.snapshot().playing);
        assertEquals(0, h.core.snapshot().sentenceIndex);
        assertEquals(0, h.core.snapshot().completedSentences.size());
        assertNull(h.core.snapshot().error);
        assertEquals(1, h.speech.requests.size());
    }

    @Test public void pauseRepeatsCurrentSentenceAndNeverErasesPreviousCoverage() {
        Harness h = new Harness();
        h.core.play();
        h.complete();
        String second = h.speech.latest().id;
        h.core.onStart(second);
        h.core.pause();
        h.core.onDone(second);
        h.core.play();
        assertEquals("Второе предложение.", h.speech.latest().text);
        assertEquals(Collections.singletonList(0), h.core.snapshot().completedSentences);
        assertEquals(1, h.core.snapshot().sentenceIndex);
    }

    @Test public void rapidSwitchesIgnoreAllOldCallbacksAndTimeouts() {
        Harness h = new Harness();
        h.core.play();
        String a1 = h.speech.latest().id;
        Task aDeadline = h.clock.latest();
        h.core.nextArticle();
        String a2 = h.speech.latest().id;
        h.core.nextStation();
        String b1 = h.speech.latest().id;
        h.core.onStart(a1);
        h.core.onDone(a1);
        h.core.onError(a2, "Старая ошибка");
        aDeadline.fireEvenIfCancelled();
        assertEquals("b", h.core.snapshot().station.id);
        assertEquals("b1", h.core.snapshot().article.id);
        assertEquals(b1, h.core.snapshot().currentRequestId);
        assertTrue(h.core.snapshot().playing);
        assertEquals(0, h.core.snapshot().completedSentences.size());
        assertNull(h.core.snapshot().error);
        assertFalse(a1.equals(a2));
        assertFalse(a2.equals(b1));
    }

    @Test public void returningToStationRestoresItsOwnSentenceAnchor() {
        Harness h = new Harness();
        h.core.play();
        h.complete();
        h.core.nextStation();
        h.core.previousStation();
        assertEquals("a1", h.core.snapshot().article.id);
        assertEquals(1, h.core.snapshot().sentenceIndex);
        assertEquals("Второе предложение.", h.speech.latest().text);
        assertTrue(h.core.snapshot().playing);
    }

    @Test public void pausedSeekDoesNotSubmitSpeechOrAwardCoverage() {
        Harness h = new Harness();
        h.core.seekToFraction(1);
        assertFalse(h.core.snapshot().playing);
        assertEquals(2, h.core.snapshot().sentenceIndex);
        assertEquals(h.core.snapshot().article.sentenceStarts.get(2).intValue(), h.core.snapshot().textOffset);
        assertFalse(h.core.snapshot().passEnded);
        assertFalse(h.core.snapshot().listened);
        assertEquals(0, h.speech.requests.size());
        h.core.play();
        assertEquals("Третье предложение.", h.speech.latest().text);
    }

    @Test public void seekPreviewStopsSpeechAndSpeaksOnlyCommittedLastTarget() {
        Harness h = new Harness();
        h.core.play();
        String old = h.speech.latest().id;
        long token = h.core.beginSeek();
        h.core.updateSeek(token, 1);
        h.core.updateSeek(token, 0);
        h.core.updateSeek(token, 0.7);
        int expected = h.core.snapshot().article.nearestSentence(0.7);
        assertEquals(1, h.speech.requests.size());
        assertNull(h.core.snapshot().currentRequestId);
        assertTrue(h.core.snapshot().seeking);
        h.core.onStart(old);
        h.core.onDone(old);
        h.core.commitSeek(token);
        assertEquals(2, h.speech.requests.size());
        assertEquals(expected, h.core.snapshot().sentenceIndex);
        assertEquals(h.core.snapshot().article.sentences.get(expected).text, h.speech.latest().text);
    }

    @Test public void pauseDuringDragInhibitsResumeAfterCommit() {
        Harness h = new Harness();
        h.core.play();
        long token = h.core.beginSeek();
        h.core.updateSeek(token, 1);
        h.core.pause();
        h.core.commitSeek(token);
        assertEquals(2, h.core.snapshot().sentenceIndex);
        assertFalse(h.core.snapshot().playing);
        assertEquals(1, h.speech.requests.size());
    }

    @Test public void interruptionDuringDragInhibitsResumeAfterCancel() {
        Harness h = new Harness();
        h.core.play();
        h.complete();
        long token = h.core.beginSeek();
        h.core.updateSeek(token, 1);
        h.core.interrupt("Аудиофокус потерян");
        h.core.cancelSeek(token);
        assertEquals(1, h.core.snapshot().sentenceIndex);
        assertFalse(h.core.snapshot().playing);
        assertEquals(2, h.speech.requests.size());
    }

    @Test public void cancelDragReturnsOriginalAnchorAndResumesOnlyOriginalIntent() {
        Harness h = new Harness();
        h.core.play();
        h.complete();
        long token = h.core.beginSeek();
        h.core.updateSeek(token, 1);
        h.core.cancelSeek(token);
        assertEquals(1, h.core.snapshot().sentenceIndex);
        assertEquals("Второе предложение.", h.speech.latest().text);
        assertTrue(h.core.snapshot().playing);
    }

    @Test public void oldGestureCannotCommitIntoNewArticleOrStation() {
        Harness h = new Harness();
        long token = h.core.beginSeek();
        h.core.updateSeek(token, 1);
        h.core.nextStation();
        h.core.updateSeek(token, 1);
        h.core.commitSeek(token);
        h.core.cancelSeek(token);
        assertEquals("b1", h.core.snapshot().article.id);
        assertEquals(0, h.core.snapshot().sentenceIndex);
        assertFalse(h.core.snapshot().playing);
    }

    @Test public void previewIsNotPersistedAsCommittedPosition() {
        Harness h = new Harness();
        long token = h.core.beginSeek();
        h.core.updateSeek(token, 1);
        PlayerController restored = h.create(h.store, new FakeSpeech());
        assertEquals(0, restored.snapshot().sentenceIndex);
        assertFalse(restored.snapshot().seeking);
    }

    @Test public void everyFragmentMustFinishInOneUninterruptedAttemptToEarnSentence() {
        Article longArticle = Article.fromSentences("long", "v1", "Длинная", "Длинное предложение с несколькими частями для голоса.");
        Harness h = new Harness(Collections.singletonList(new Station("a", "А", Collections.singletonList(longArticle))), 15);
        h.core.play();
        h.complete();
        String abandonedFragment = h.speech.latest().id;
        assertEquals(0, h.core.snapshot().completedSentences.size());
        h.core.pause();
        h.core.play();
        assertEquals(h.speech.requests.get(0).text, h.speech.latest().text);
        h.core.onStart(abandonedFragment);
        h.core.onDone(abandonedFragment);
        assertEquals(0, h.core.snapshot().completedSentences.size());
        while (h.core.snapshot().playing) h.complete();
        assertEquals(Collections.singletonList(0), h.core.snapshot().completedSentences);
        assertTrue(h.core.snapshot().listened);
        assertTrue(h.core.snapshot().passEnded);
    }

    @Test public void skipToLastSentenceEndsPassWithoutClaimingListened() {
        Harness h = new Harness();
        h.core.seekToFraction(1);
        h.core.play();
        h.complete();
        PlayerController.SavedProgress saved = h.saved("a", "a1");
        assertTrue(saved.passEnded);
        assertEquals(Collections.singletonList(2), saved.completedSentences);
        assertTrue(saved.started);
        assertFalse(saved.read);
        assertEquals("a2", h.core.snapshot().article.id);
        h.core.pause();
        h.core.previousArticle();
        assertEquals(0, h.core.snapshot().sentenceIndex);
        assertFalse(h.core.snapshot().passEnded);
        assertFalse(h.core.snapshot().listened);
    }

    @Test public void listenedCoverageAccumulatesIndependentlyOfPassAndRead() {
        Harness h = new Harness();
        h.core.seekToFraction(1);
        h.core.play();
        h.complete();
        h.core.pause();
        h.core.previousArticle();
        h.core.toggleRead();
        h.core.play();
        h.complete();
        h.complete();
        assertTrue(h.core.snapshot().listened); // Last sentence was earned in the previous pass.
        assertFalse(h.core.snapshot().passEnded);
        assertTrue(h.core.snapshot().read);
        h.core.toggleRead();
        assertTrue(h.core.snapshot().listened);
        assertFalse(h.core.snapshot().read);
    }

    @Test public void broadcastTraversesForwardOnceAndStopsWithoutReturningToSkippedItems() {
        Harness h = new Harness();
        h.core.play();
        h.core.nextStation(); // A was skipped intentionally.
        List<String> articles = new ArrayList<>();
        int guard = 30;
        while (h.core.snapshot().playing && guard-- > 0) {
            articles.add(h.core.snapshot().article.id);
            h.complete();
        }
        assertTrue(guard > 0);
        assertTrue(h.core.snapshot().broadcastEnded);
        assertEquals("b2", h.core.snapshot().article.id);
        assertEquals(Arrays.asList("b1", "b1", "b1", "b2", "b2", "b2"), articles);
        assertFalse(h.saved("a", "a1").passEnded);
        assertEquals(0, h.saved("a", "a1").completedSentences.size());
        assertFalse(h.saved("a", "a2").started);
        int submissions = h.speech.requests.size();
        h.core.play();
        assertEquals(submissions, h.speech.requests.size());
        assertFalse(h.core.snapshot().playing);
    }

    @Test public void fullBroadcastIsA1A2B1B2AndEmptyStationIsSkipped() {
        Harness h = new Harness();
        h.core.play();
        List<String> newArticles = new ArrayList<>();
        int guard = 40;
        String previous = null;
        while (h.core.snapshot().playing && guard-- > 0) {
            String current = h.core.snapshot().article.id;
            if (!current.equals(previous)) newArticles.add(current);
            previous = current;
            h.complete();
        }
        assertTrue(guard > 0);
        assertEquals(Arrays.asList("a1", "a2", "b1", "b2"), newArticles);
        assertFalse(h.core.snapshot().playing);
        assertEquals("Эфир завершён", h.core.snapshot().status);
        for (PlayerController.SavedProgress item : h.core.savedState().progress.values()) assertEquals(3, item.completedSentences.size());
    }

    @Test public void manualEmptyOrCompletedStationPausesAndNavigationDoesNotWrap() {
        Harness h = new Harness();
        h.core.previousStation();
        assertEquals(0, h.core.snapshot().stationIndex);
        h.core.play();
        h.core.selectStation(2);
        assertFalse(h.core.snapshot().playing);
        assertNull(h.core.snapshot().article);
        assertEquals("В этой станции пока нет новостей", h.core.snapshot().status);
        h.core.nextStation();
        assertEquals(2, h.core.snapshot().stationIndex);
        assertFalse(h.core.snapshot().canNextStation);
        h.core.previousStation();
        assertFalse(h.core.snapshot().playing);
        h.core.play();
        while (h.core.snapshot().playing) h.complete();
        h.core.selectStation(0);
        h.core.play();
        h.core.selectStation(1);
        assertFalse(h.core.snapshot().playing);
        assertEquals("Очередь станции пройдена", h.core.snapshot().status);
    }

    @Test public void previousArticleAlwaysNavigatesAndBoundariesDoNotRestartCurrent() {
        Harness h = new Harness();
        h.core.nextArticle();
        h.core.seekToFraction(1);
        h.core.previousArticle();
        assertEquals("a1", h.core.snapshot().article.id);
        h.core.seekToFraction(1);
        h.core.previousArticle();
        assertEquals(2, h.core.snapshot().sentenceIndex);
        assertFalse(h.core.snapshot().canPreviousArticle);
    }

    @Test public void missingStartTimesOutWithoutRetryAndLateEventsStayIgnored() {
        Harness h = new Harness();
        h.core.play();
        String request = h.speech.latest().id;
        Task deadline = h.clock.latest();
        assertEquals(10_000, deadline.delay);
        deadline.fireEvenIfCancelled();
        assertFalse(h.core.snapshot().playing);
        assertEquals("Речь не началась за 10 секунд", h.core.snapshot().error);
        h.core.onStart(request);
        h.core.onDone(request);
        h.core.onError(request, "Другая ошибка");
        assertEquals(1, h.speech.requests.size());
        assertEquals(0, h.core.snapshot().completedSentences.size());
        assertEquals("Речь не началась за 10 секунд", h.core.snapshot().error);
    }

    @Test public void doneBeforeStartCannotAwardCoverageOrCancelDeadline() {
        Harness h = new Harness();
        h.core.play();
        Task startDeadline = h.clock.latest();
        h.core.onDone(h.speech.latest().id);
        assertEquals(0, h.core.snapshot().completedSentences.size());
        assertFalse(startDeadline.cancelled);
        startDeadline.fireEvenIfCancelled();
        assertFalse(h.core.snapshot().playing);
    }

    @Test public void cancelledStartTimerAfterStartCannotFailActiveSpeech() {
        Harness h = new Harness();
        h.core.play();
        Task startDeadline = h.clock.latest();
        h.core.onStart(h.speech.latest().id);
        assertTrue(startDeadline.cancelled);
        startDeadline.fireEvenIfCancelled();
        assertTrue(h.core.snapshot().playing);
        assertNull(h.core.snapshot().error);
        assertTrue(h.clock.latest().delay > PlayerController.START_TIMEOUT_MS);
    }

    @Test public void startedButStalledSpeechHasBoundedCompletionWatchdog() {
        Harness h = new Harness();
        h.core.play();
        String request = h.speech.latest().id;
        h.core.onStart(request);
        Task deadline = h.clock.latest();
        assertEquals(60_000 + h.speech.latest().text.length() * 500L, deadline.delay);
        assertTrue(deadline.delay <= 15 * 60_000L);
        deadline.fireEvenIfCancelled();
        assertFalse(h.core.snapshot().playing);
        assertEquals("Движок речи не завершил фразу", h.core.snapshot().error);
        h.core.onDone(request);
        assertEquals(0, h.core.snapshot().completedSentences.size());
    }

    @Test public void validErrorPausesWithoutAdvancingAndExplicitPlayCanRetry() {
        Harness h = new Harness();
        h.core.play();
        String old = h.speech.latest().id;
        h.core.onError(old, "Русский голос недоступен");
        assertFalse(h.core.snapshot().playing);
        assertEquals(0, h.core.snapshot().sentenceIndex);
        assertEquals(1, h.speech.requests.size());
        h.core.play();
        assertEquals(2, h.speech.requests.size());
        assertFalse(old.equals(h.speech.latest().id));
        assertNull(h.core.snapshot().error);
    }

    @Test public void duplicateCompletionDoesNotAdvanceNewRequest() {
        Harness h = new Harness();
        h.core.play();
        String old = h.speech.latest().id;
        h.complete();
        String next = h.speech.latest().id;
        h.core.onDone(old);
        assertEquals(1, h.core.snapshot().sentenceIndex);
        assertEquals(next, h.core.snapshot().currentRequestId);
        assertEquals(Collections.singletonList(0), h.core.snapshot().completedSentences);
    }

    @Test public void restorePreservesStationArticleAnchorCoverageAndReadButAlwaysPauses() {
        Harness h = new Harness();
        h.core.nextStation();
        h.core.nextArticle();
        h.core.toggleRead();
        h.core.play();
        h.complete();
        String old = h.speech.latest().id;
        FakeSpeech freshSpeech = new FakeSpeech();
        PlayerController restored = h.create(h.store, freshSpeech);
        assertEquals("b", restored.snapshot().station.id);
        assertEquals("b2", restored.snapshot().article.id);
        assertEquals(1, restored.snapshot().sentenceIndex);
        assertEquals(Collections.singletonList(0), restored.snapshot().completedSentences);
        assertTrue(restored.snapshot().read);
        assertFalse(restored.snapshot().playing);
        assertEquals(0, freshSpeech.requests.size());
        restored.onStart(old);
        restored.onDone(old);
        assertEquals(1, restored.snapshot().sentenceIndex);
        restored.play();
        assertFalse(old.equals(freshSpeech.latest().id));
    }

    @Test public void textVersionChangeCannotInheritCoverageOrAnchor() {
        Harness h = new Harness();
        h.core.play();
        h.complete();
        h.core.toggleRead();
        Article updated = Article.fromSentences("a1", "v2", "Тест", "Новый текст.", "Иная версия.");
        Harness other = new Harness(Collections.singletonList(new Station("a", "А", Collections.singletonList(updated))), 1000);
        PlayerController restored = other.create(h.store, new FakeSpeech());
        assertEquals(0, restored.snapshot().sentenceIndex);
        assertEquals(0, restored.snapshot().completedSentences.size());
        assertFalse(restored.snapshot().read);
        assertFalse(restored.snapshot().started);
    }

    @Test public void foreignThreadCallbacksAreRejectedWithoutMutatingState() throws InterruptedException {
        Harness h = new Harness();
        h.core.play();
        String id = h.speech.latest().id;
        AtomicReference<Throwable> problem = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { h.core.onStart(id); }
            catch (Throwable failure) { problem.set(failure); }
        });
        worker.start();
        worker.join();
        assertTrue(problem.get() instanceof IllegalStateException);
        assertEquals(0, h.core.snapshot().completedSentences.size());
        assertFalse(h.clock.latest().cancelled);
    }

    @Test public void stopFailureCannotStartAnotherMaterial() {
        Harness h = new Harness();
        h.core.play();
        h.speech.failStop = true;
        h.core.nextStation();
        assertFalse(h.core.snapshot().playing);
        assertEquals(1, h.speech.requests.size());
        assertEquals("Движок речи не подтвердил остановку", h.core.snapshot().error);
        h.core.play();
        assertFalse(h.core.snapshot().playing);
        assertEquals(1, h.speech.requests.size());
        h.speech.failStop = false;
        h.core.play();
        assertTrue(h.core.snapshot().playing);
        assertEquals(2, h.speech.requests.size());
    }

    @Test public void readerMarksShownArticleAfterBroadcastHasMovedOnWithoutTouchingPlayback() {
        Harness h = new Harness();
        h.core.play();
        h.core.nextArticle();
        String active = h.speech.latest().id;
        int submissions = h.speech.requests.size();
        h.core.toggleRead("a", "a1", "v1");
        assertTrue(h.saved("a", "a1").read);
        assertFalse(h.core.snapshot().read);
        assertEquals("a2", h.core.snapshot().article.id);
        assertEquals(0, h.core.snapshot().sentenceIndex);
        assertEquals(active, h.core.snapshot().currentRequestId);
        assertEquals(submissions, h.speech.requests.size());
        h.core.toggleRead("a", "a1", "obsolete");
        assertTrue(h.saved("a", "a1").read);
        h.core.toggleRead("a", "a1", "v1");
        assertFalse(h.saved("a", "a1").read);
        assertEquals(active, h.core.snapshot().currentRequestId);
    }

    @Test public void explicitArticleSelectionAcrossStationsNeverSpeaksIntermediateCursor() {
        Harness h = new Harness();
        h.core.play();
        String old = h.speech.latest().id;
        Task oldDeadline = h.clock.latest();
        h.core.selectArticle("b", "b2");
        String selected = h.speech.latest().id;
        assertEquals(2, h.speech.requests.size());
        assertEquals("b2", h.core.snapshot().article.id);
        assertTrue(h.core.snapshot().playing);
        assertFalse(h.saved("b", "b1").started);
        h.core.onStart(old);
        h.core.onDone(old);
        h.core.onError(old, "Поздняя ошибка");
        oldDeadline.fireEvenIfCancelled();
        assertEquals(selected, h.core.snapshot().currentRequestId);
        assertEquals(0, h.core.snapshot().sentenceIndex);
        assertEquals(2, h.speech.requests.size());
        assertNull(h.core.snapshot().error);
        assertFalse(h.saved("b", "b1").started);
    }

    @Test public void explicitArticleSelectionAcrossStationsKeepsPauseAndPreviousAnchor() {
        Harness h = new Harness();
        h.core.seekToFraction(1);
        h.core.selectArticle("b", "b2");
        assertFalse(h.core.snapshot().playing);
        assertEquals(0, h.speech.requests.size());
        assertEquals("b2", h.core.snapshot().article.id);
        h.core.selectArticle("a", "a1");
        assertEquals(2, h.core.snapshot().sentenceIndex);
        assertFalse(h.core.snapshot().playing);
    }

    @Test public void routeMarksOnlyMatchedStartAndStationOnlyCannotFallThrough(){
        Harness h=new Harness();List<String> starts=new ArrayList<>();h.core.route(new PlayerController.Route(){public void started(String station,Article article){starts.add(station+":"+article.id);}public PlayerController.RouteChoice next(String station,Article article){return PlayerController.RouteChoice.sourceOnly();}});
        h.core.play();assertFalse(h.core.actualRequestStarted());String stale=h.speech.latest().id;h.core.pause();h.core.onStart(stale);assertTrue(starts.isEmpty());h.core.play();String id=h.speech.latest().id;h.core.onStart(id);assertTrue(h.core.actualRequestStarted());assertEquals(Arrays.asList("a:a1"),starts);
        for(int i=0;i<6;i++)h.complete();assertFalse(h.core.snapshot().playing);assertEquals("a",h.core.snapshot().station.id);assertEquals("a2",h.core.snapshot().article.id);assertTrue(h.core.snapshot().broadcastEnded);
    }
    @Test public void routedExactCompletedVersionRestartsWithoutErasingReadOrSkippingFirstSentence(){
        Harness h=new Harness();h.core.play();for(int i=0;i<6;i++)h.complete();h.core.pause();h.core.selectArticle("a","a1");h.core.setRead("a","a1","v1",true);
        h.core.route(new PlayerController.Route(){public PlayerController.RouteChoice next(String station,Article article){return PlayerController.RouteChoice.item("a","a1","v1");}});h.core.play();assertEquals("a1",h.core.snapshot().article.id);assertEquals(0,h.core.snapshot().sentenceIndex);assertEquals("Первое предложение.",h.speech.latest().text);assertTrue(h.core.snapshot().read);
    }
    private static List<Station> fixtures() {
        return Arrays.asList(
                new Station("a", "А", Arrays.asList(article("a1"), article("a2"))),
                new Station("b", "Б", Arrays.asList(article("b1"), article("b2"))),
                new Station("c", "В", Collections.emptyList()));
    }
    private static Article article(String id) {
        return Article.fromSentences(id, "v1", "Тест " + id, "Первое предложение.", "Второе предложение.", "Третье предложение.");
    }
    private static final class Harness {
        final List<Station> stations;
        final int maxChars;
        final FakeSpeech speech = new FakeSpeech();
        final FakeScheduler clock = new FakeScheduler();
        final MemoryStore store = new MemoryStore();
        final List<PlayerController.Snapshot> changes = new ArrayList<>();
        final PlayerController core;
        Harness() { this(fixtures(), 1000); }
        Harness(List<Station> stations, int maxChars) {
            this.stations = stations;
            this.maxChars = maxChars;
            core = create(store, speech);
        }
        PlayerController create(MemoryStore store, FakeSpeech speech) {
            return new PlayerController(stations, speech, clock, store, changes::add, maxChars);
        }
        void complete() {
            String id = speech.latest().id;
            core.onStart(id);
            core.onDone(id);
        }
        PlayerController.SavedProgress saved(String station, String article) {
            return core.savedState().progress.get(PlayerController.progressKey(station, article, "v1"));
        }
    }
    private static final class Spoken {
        final String text;
        final String id;
        Spoken(String text, String id) { this.text = text; this.id = id; }
    }
    private static final class FakeSpeech implements PlayerController.SpeechEngine {
        final List<Spoken> requests = new ArrayList<>();
        final List<PlayerController.SpeechWindow> hints=new ArrayList<>();boolean rejectHints;
        PlayerController.SpeechSentence preparedSentence;List<String> preparedPlan;int planHits;
        @Override public List<String> fragments(PlayerController.SpeechSentence sentence,int maxChars){if(preparedSentence!=null&&preparedSentence.same(sentence)){planHits++;return preparedPlan;}return PlayerController.SpeechEngine.super.fragments(sentence,maxChars);}
        @Override public void lookahead(PlayerController.SpeechWindow window){if(rejectHints)throw new IllegalArgumentException("UNSUPPORTED_FUTURE");hints.add(window);}
        Runnable onStop;
        boolean failStop;
        int backendLimit=Integer.MAX_VALUE;
        String fragmentFailure;
        boolean omitFragmentText;
        @Override public List<String> fragments(String text,int maxChars) {
            if(fragmentFailure!=null)throw new IllegalArgumentException(fragmentFailure);
            if(omitFragmentText)return Collections.singletonList("Пропущенный текст.");
            return backendLimit==Integer.MAX_VALUE?PlayerController.SpeechEngine.super.fragments(text,maxChars)
                    :TextSegments.fragments(text,Math.min(maxChars,backendLimit));
        }
        @Override public void speak(String text, String requestId) { requests.add(new Spoken(text, requestId)); }
        @Override public void stop() {
            if (failStop) throw new IllegalStateException("test stop failure");
            if (onStop != null) onStop.run();
        }
        Spoken latest() { return requests.get(requests.size() - 1); }
    }
    private static final class Task implements PlayerController.Cancel {
        final long delay;
        final Runnable action;
        boolean cancelled;
        Task(long delay, Runnable action) { this.delay = delay; this.action = action; }
        @Override public void cancel() { cancelled = true; }
        void fireEvenIfCancelled() { action.run(); }
    }
    private static final class FakeScheduler implements PlayerController.Scheduler {
        final List<Task> tasks = new ArrayList<>();
        @Override public PlayerController.Cancel schedule(long delayMs, Runnable runnable) {
            Task task = new Task(delayMs, runnable);
            tasks.add(task);
            return task;
        }
        Task latest() { return tasks.get(tasks.size() - 1); }
    }
    private static final class MemoryStore implements PlayerController.StateStore {
        PlayerController.SavedState state;
        @Override public PlayerController.SavedState load() { return state; }
        @Override public void save(PlayerController.SavedState state) { this.state = state; }
    }
}

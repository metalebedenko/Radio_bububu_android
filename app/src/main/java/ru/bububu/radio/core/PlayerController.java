package ru.bububu.radio.core;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-owner-thread state machine for direct TTS. The Android adapter marshals ALL callbacks
 * and deadlines to the same owner thread. Cancellation revokes identity before invoking stop().
 */
public final class PlayerController {
    public interface SpeechEngine {
        void speak(String text, String requestId);
        void stop();
        default List<String> fragments(String text, int maxChars) {
            return TextSegments.fragments(text, maxChars);
        }
        default List<String> fragments(SpeechSentence sentence, int maxChars) { return fragments(sentence.text(), maxChars); }
        default void speak(SpeechUnit unit, String requestId) { speak(unit.text, requestId); }
        default void lookahead(SpeechWindow window) { }
        default void textEvent(String stage,String reason,String requestId,long intent,SpeechSentence sentence,SpeechUnit unit) { }
    }

    public static final class SpeechSentence {
        public final String stationId;
        public final Article article;
        public final PlaybackMembership.Binding binding;
        public final int index;
        public SpeechSentence(String stationId,Article article,PlaybackMembership.Binding binding,int index) {
            this.stationId=Objects.requireNonNull(stationId);this.article=Objects.requireNonNull(article);this.binding=binding;
            if(index<0||index>=article.sentences.size())throw new IllegalArgumentException("Sentence");this.index=index;
        }
        public String text(){return article.sentences.get(index).text;}
        public boolean sameArticle(SpeechSentence other){return other!=null&&stationId.equals(other.stationId)&&article.id.equals(other.article.id)&&article.version.equals(other.article.version)&&article.text.equals(other.article.text)&&article.sentenceStarts.equals(other.article.sentenceStarts)&&sameBinding(binding,other.binding);}
        private static boolean sameBinding(PlaybackMembership.Binding a,PlaybackMembership.Binding b){return a==null?b==null:b!=null&&a.sequence==b.sequence&&Objects.equals(a.sourceVersionKey,b.sourceVersionKey)&&a.origin.equals(b.origin);}
        public boolean same(SpeechSentence other){return sameArticle(other)&&index==other.index;}
    }
    public static final class SpeechUnit {
        public final SpeechSentence sentence;
        public final int fragment;
        public final List<String> fragments;
        public final String text;
        public SpeechUnit(SpeechSentence sentence,List<String> fragments,int fragment) {
            this.sentence=Objects.requireNonNull(sentence);this.fragments=TextSegments.checkedFragments(sentence.text(),fragments,Integer.MAX_VALUE);
            if(fragment<0||fragment>=this.fragments.size())throw new IllegalArgumentException("Fragment");this.fragment=fragment;this.text=this.fragments.get(fragment);
        }
        public boolean same(SpeechUnit other){return other!=null&&sentence.same(other.sentence)&&fragment==other.fragment&&text.equals(other.text)&&fragments.equals(other.fragments);}
    }
    public static final class SpeechWindow {
        public final String requestId;public final SpeechUnit current;public final int maxChars;
        private final java.util.function.BooleanSupplier admission;
        SpeechWindow(String requestId,SpeechUnit current,int maxChars,java.util.function.BooleanSupplier admission){this.requestId=Objects.requireNonNull(requestId);this.current=Objects.requireNonNull(current);this.maxChars=maxChars;this.admission=admission;}
        public boolean allowed(){return admission.getAsBoolean();}
    }

    public interface Cancel { void cancel(); }
    public interface Scheduler { Cancel schedule(long delayMs, Runnable runnable); }
    public interface StateStore {
        SavedState load();
        void save(SavedState state);
    }
    public interface Listener { void onStateChanged(Snapshot snapshot); }
    public interface Route {
        default void explicitPlay() {}
        default void started(String station,Article article) {}
        default void selected(String station,Article article) {}
        default Boolean canPrevious(String station,Article article) { return null; }
        default RouteChoice previous(String station,Article article) { return null; }
        default RouteChoice next(String station,Article article,boolean explicit) { return next(station,article); }
        default RouteChoice next(String station,Article article,Set<String> excluded) { return next(station,article); }
        /** null retains the ordinary station-local button availability. Must not consume route state. */
        default Boolean canNext(String station,Article article) { return null; }
        /** null keeps legacy routing; stationOnly/end never fall through to another source. */
        RouteChoice next(String station,Article article);
    }
    public static final class RouteChoice {
        public final String station,article,version;public final boolean stationOnly;
        private RouteChoice(String s,String a,String v,boolean only){station=s;article=a;version=v;stationOnly=only;}
        public static RouteChoice sourceOnly(){return new RouteChoice(null,null,null,true);}
        public static RouteChoice end(){return new RouteChoice(null,null,null,false);}
        public static RouteChoice item(String s,String a,String v){return new RouteChoice(s,a,v,false);}
    }
    private Route route;
    public void route(Route value){checkOwner();route=value;}
    public boolean actualRequestStarted(){checkOwner();return active!=null&&active.started;}

    public static final long START_TIMEOUT_MS = 10_000;
    private static final String PROCESS_EPOCH = UUID.randomUUID().toString();
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();

    private final Thread owner = Thread.currentThread();
    private final List<Station> stations;
    private final SpeechEngine speech;
    private final Scheduler scheduler;
    private final StateStore store;
    private final Listener listener;
    private final int maxFragmentChars;
    private final PlaybackMembership membership;
    private final Map<String, PlaybackMembership.Binding> bindings = new LinkedHashMap<>();
    private final Map<String, Progress> progress = new LinkedHashMap<>();
    private int[] stationCursors;
    // A marker retains a removed item's durable boundary while keeping the displayed article null.
    private final Map<String, String> stationBoundaries = new LinkedHashMap<>();
    // Live explicit intent, keyed to the exact item/version; cold restored cursors remain implicit.
    private final Map<String, String> explicitArticleCursors = new LinkedHashMap<>();
    private int stationIndex;
    private boolean playing;
    private boolean broadcastEnded;
    private String error;
    private boolean stopFailed;
    private Request active;
    private Drag drag;
    private long dragSequence;
    private boolean selectionRequired;
    private Recovery recovery;
    private long intentGeneration;
    private final Set<String> textGapArticles=new HashSet<>();
    private String recoveryNotice;
    private boolean recoveryEnded;

    // Historical isolated core tests use this overload; production supplies the lifetime Book gate.
    private static final PlaybackMembership COMPATIBILITY_MEMBERSHIP = new PlaybackMembership() {
        public Binding capture(String station, Article article) { return new Binding(0, null, "compatibility"); }
        public Admission check(String station, Article article, Binding binding) { return Admission.ALLOWED; }
        public boolean stationRetired(String station) { return false; }
    };

    public PlayerController(List<Station> stations, SpeechEngine speech, Scheduler scheduler,
                            StateStore store, Listener listener, int maxFragmentChars) {
        this(stations, speech, scheduler, store, listener, maxFragmentChars, COMPATIBILITY_MEMBERSHIP);
    }

    public PlayerController(List<Station> stations, SpeechEngine speech, Scheduler scheduler,
                            StateStore store, Listener listener, int maxFragmentChars,
                            PlaybackMembership membership) {
        if (stations == null || stations.isEmpty()) throw new IllegalArgumentException("No stations");
        if (maxFragmentChars < 2) throw new IllegalArgumentException("Invalid TTS limit");
        this.stations = new ArrayList<>(stations);
        this.speech = Objects.requireNonNull(speech, "speech");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.store = Objects.requireNonNull(store, "store");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.maxFragmentChars = maxFragmentChars;
        this.membership = Objects.requireNonNull(membership, "membership");
        stationCursors = new int[stations.size()];
        Set<String> stationIds = new HashSet<>();
        for (Station station : stations) {
            if (!stationIds.add(station.id)) throw new IllegalArgumentException("Duplicate station ID");
            for (Article article : station.articles) progress.put(progressKey(station.id, article.id,
                    article.version), new Progress());
        }
        try { restore(store.load()); }
        catch (RuntimeException ignored) { error = "Сохранённое состояние недоступно"; }
        // Load can adopt another coherent Book. Capture only after that authority is settled.
        for (Station station : this.stations) for (Article article : station.articles)
            bindings.put(progressKey(station.id, article.id, article.version), capture(station.id, article));
        // A new controller never restores playback intent, pending requests or partial fragments.
        playing = false;
        broadcastEnded = (currentProgress() == null || currentProgress().passEnded)
                && findContinuation() == null;
    }

    public List<Station> stations() { checkOwner(); return Collections.unmodifiableList(new ArrayList<>(stations)); }

    /** Other audio owners must wait for a confirmed stop, including a failed engine.stop(). */
    public boolean isOutputStopped() { checkOwner(); return !playing && active == null && !stopFailed; }

    /** Checkpoint an already stopped radio without touching another note audio owner. */
    public void checkpoint() { checkOwner(); changed(); }
    public boolean selectionRequired() { checkOwner(); return requiresSelection(); }
    /** A cleared user queue requires a new explicit selection; this never starts another station. */
    public void requireExplicitSelection(){
        checkOwner();if(!isOutputStopped())throw new IllegalStateException("Output must be stopped before clearing selection");
        drag=null;playing=false;selectionRequired=true;broadcastEnded=true;changed();
    }

    /** Registry projection of a committed empty station; never changes playback selection. */
    public boolean appendStation(String stationId, String stationName) {
        checkOwner();
        Station checked = new Station(stationId, stationName, Collections.emptyList());
        for (Station old : stations) if (old.id.equals(stationId)) {
            if (!old.name.equals(stationName)) throw new IllegalArgumentException("Station name is immutable");
            return false;
        }
        stations.add(checked);
        stationCursors = java.util.Arrays.copyOf(stationCursors, stations.size());
        changed();
        return true;
    }

    /** The caller commits its durable ledger first. Appending never revokes or replaces active speech. */
    public boolean appendMaterial(String stationId, String stationName, Article article) {
        return appendMaterial(stationId, stationName, article, null);
    }

    /** Install an exact committed version and its complete seed before the first save/emit. */
    public boolean appendMaterial(String stationId, String stationName, Article article, SavedProgress seed) {
        checkOwner();
        Objects.requireNonNull(article, "article");
        if ((article.id.startsWith(NoteBook.AFTER_PREFIX) || article.id.startsWith(NoteBook.SELECTED_PREFIX))) throw new IllegalArgumentException("Reserved article ID");
        Station checked = new Station(stationId, stationName, Collections.singletonList(article));
        if (seed != null) validateSavedProgress(stationId, article, seed);
        Progress initial = new Progress();
        if (seed != null) {
            initial.sentenceIndex = seed.sentenceIndex;
            initial.passEnded = seed.passEnded;
            initial.started = seed.started;
            initial.read = seed.read;
            for (Integer sentence : seed.completedSentences) initial.completed.set(sentence);
        }
        int index = -1;
        for (int s = 0; s < stations.size(); s++) if (stations.get(s).id.equals(stationId)) { index = s; break; }
        if (index < 0) {
            stations.add(checked);
            stationCursors = java.util.Arrays.copyOf(stationCursors, stations.size());
        } else {
            Station station = stations.get(index);
            if (!station.name.equals(stationName)) throw new IllegalArgumentException("Station name is immutable");
            for (Article old : station.articles) if (old.id.equals(article.id)) {
                if (!old.version.equals(article.version) || !old.title.equals(article.title)
                        || !old.text.equals(article.text) || !old.sentenceStarts.equals(article.sentenceStarts))
                    throw new IllegalArgumentException("Selected material is immutable; remove it first");
                return false;
            }
            // Preserve an empty displayed cursor until explicit Play rather than selecting this append.
            String marker = stationBoundaries.get(stationId);
            if (marker != null && NoteBook.boundarySequence(marker) != Long.MAX_VALUE
                    && NoteBook.boundaryCandidate(marker) == null)
                stationBoundaries.put(stationId, NoteBook.boundaryMarker(NoteBook.boundarySequence(marker), article.id));
            else if (station.articles.isEmpty() && index == stationIndex)
                stationBoundaries.put(stationId, NoteBook.boundaryMarker(0, article.id));
            List<Article> items = new ArrayList<>(station.articles);
            items.add(article);
            stations.set(index, new Station(stationId, stationName, items));
        }
        progress.put(progressKey(stationId, article.id, article.version), initial);
        bindings.put(progressKey(stationId, article.id, article.version), capture(stationId, article));
        changed();
        return true;
    }

    /** Strict source seed/archive validation; legacy restore retains its prior compatibility. */
    public static void validateSavedProgress(String stationId, Article article, SavedProgress saved) {
        Objects.requireNonNull(article, "article");
        Objects.requireNonNull(saved, "progress");
        int count = article.sentences.size();
        if (!stationId.equals(saved.stationId) || !article.id.equals(saved.articleId)
                || !article.version.equals(saved.version) || saved.sentenceIndex < 0
                || saved.sentenceIndex >= count || (saved.passEnded && saved.sentenceIndex != count - 1)
                || (!saved.started && (saved.passEnded || !saved.completedSentences.isEmpty())))
            throw new IllegalArgumentException("Progress does not match the exact version");
        Set<Integer> seen = new HashSet<>();
        for (Integer index : saved.completedSentences)
            if (index == null || index < 0 || index >= count || !seen.add(index))
                throw new IllegalArgumentException("Invalid sentence coverage");
    }

    /** A durable tombstone has already committed. Removing the active text always pauses first. */
    public boolean removeMaterial(String stationId, String articleId, String durableBoundaryMarker) {
        checkOwner();
        long boundary = NoteBook.boundarySequence(durableBoundaryMarker);
        for (int s = 0; s < stations.size(); s++) {
            Station station = stations.get(s);
            if (!station.id.equals(stationId)) continue;
            for (int a = 0; a < station.articles.size(); a++) {
                if (!station.articles.get(a).id.equals(articleId)) continue;
                String marker = stationBoundaries.get(stationId);
                boolean removedCurrent = s == stationIndex && marker == null && stationCursors[s] == a;
                if (removedCurrent) {
                    playing = false;
                    drag = null;
                    revokeRequest(); // Revoke identity before synchronous engine callbacks.
                }
                List<Article> items = new ArrayList<>(station.articles);
                Article removed = items.remove(a);
                stations.set(s, new Station(station.id, station.name, items));
                progress.remove(progressKey(stationId, removed.id, removed.version));
                bindings.remove(progressKey(stationId, removed.id, removed.version));
                if (marker != null) {
                    String candidate = NoteBook.boundaryCandidate(marker);
                    if (articleId.equals(candidate)) stationBoundaries.put(stationId,
                            NoteBook.boundaryMarker(Math.max(boundary, NoteBook.boundarySequence(marker)),
                                    a < items.size() ? items.get(a).id : null));
                    stationCursors[s] = items.size();
                } else if (stationCursors[s] == a) {
                    if (a < items.size()) stationCursors[s] = a; // The immediate successor, never an earlier item.
                    else {
                        stationCursors[s] = items.size();
                        stationBoundaries.put(stationId, NoteBook.boundaryMarker(boundary, null));
                    }
                } else if (stationCursors[s] > a) {
                    stationCursors[s]--;
                    if (drag != null && s == stationIndex) drag = new Drag(drag.id, s,
                            stationCursors[s], drag.version, drag.targetSentence, drag.resumeAllowed);
                }
                if (removedCurrent) broadcastEnded = findContinuation() == null;
                changed();
                return true;
            }
            return false;
        }
        return false;
    }

    /** One projection of the proven Book. All validation precedes RAM apply; save failure is reported by the store authority. */
    public void projectCommittedDeletion(List<Station> desiredCatalog, SavedState authoritativeReconciled) {
        checkOwner();
        if (desiredCatalog == null || desiredCatalog.isEmpty()) throw new IllegalArgumentException("No surviving ledger");
        Objects.requireNonNull(authoritativeReconciled, "reconciled state");
        List<Station> desired = new ArrayList<>(desiredCatalog);
        Map<String, Progress> nextProgress = new LinkedHashMap<>();
        Map<String, PlaybackMembership.Binding> nextBindings = new LinkedHashMap<>();
        Map<String, String> nextBoundaries = new LinkedHashMap<>();
        int[] nextCursors = new int[desired.size()];
        Set<String> ids = new HashSet<>();
        String currentId = currentStation().id;
        Article oldCurrent = currentArticle();
        int nextCurrent = -1;
        for (int s = 0; s < desired.size(); s++) {
            Station station = Objects.requireNonNull(desired.get(s), "station");
            if (!ids.add(station.id)) throw new IllegalArgumentException("Duplicate projected station");
            if (station.id.equals(currentId)) nextCurrent = s;
            int oldIndex = stationIndex(station.id);
            if (oldIndex >= 0 && !stations.get(oldIndex).name.equals(station.name))
                throw new IllegalArgumentException("Station name is immutable");
            if (retired(station.id) && !station.articles.isEmpty()) throw new IllegalArgumentException("Retired station has articles");
            Set<String> articles = new HashSet<>();
            for (Article article : station.articles) {
                if (!articles.add(article.id)) throw new IllegalArgumentException("Duplicate projected article");
                String key = progressKey(station.id, article.id, article.version);
                Article previous = oldIndex < 0 ? null : articleById(stations.get(oldIndex), article.id);
                if (previous != null && !sameArticle(previous, article)) throw new IllegalArgumentException("Selected material changed");
                PlaybackMembership.Binding binding = bindings.get(key);
                if (binding == null) binding = capture(station.id, article);
                if (checkAdmission(station.id, article, binding) != PlaybackMembership.Admission.ALLOWED)
                    throw new IllegalStateException("Projected material is not authoritatively playable");
                Progress old = progress.get(key);
                if (old != null) nextProgress.put(key, old);
                else {
                    Progress seed = new Progress();
                    SavedProgress saved = authoritativeReconciled.progress.get(key);
                    if (saved != null) {
                        validateSavedProgress(station.id, article, saved);
                        seed.sentenceIndex = saved.sentenceIndex; seed.passEnded = saved.passEnded;
                        seed.started = saved.started; seed.read = saved.read;
                        for (Integer sentence : saved.completedSentences) seed.completed.set(sentence);
                    }
                    nextProgress.put(key, seed);
                }
                nextBindings.put(key, binding);
            }
            String cursor = authoritativeReconciled.stationArticleIds.get(station.id);
            // Unaffected cursor/drag stays at its live identity; the Book repairs removed cursors.
            if (oldIndex >= 0 && !stationBoundaries.containsKey(station.id)) {
                int oldCursor = stationCursors[oldIndex];
                if (oldCursor < stations.get(oldIndex).articles.size()) {
                    String oldArticle = stations.get(oldIndex).articles.get(oldCursor).id;
                    if (articleIndex(station, oldArticle) >= 0) cursor = oldArticle;
                }
            }
            if (cursor != null && cursor.startsWith(NoteBook.AFTER_PREFIX)) {
                NoteBook.boundarySequence(cursor);
                String candidate = NoteBook.boundaryCandidate(cursor);
                if (candidate != null && articleIndex(station, candidate) < 0) throw new IllegalArgumentException("Unreconciled boundary candidate");
                nextBoundaries.put(station.id, cursor); nextCursors[s] = station.articles.size();
            } else if (cursor != null) {
                int found = articleIndex(station, cursor);
                if (found < 0) throw new IllegalArgumentException("Unreconciled article cursor");
                nextCursors[s] = found;
            }
        }
        // Current deleted source remains an internal empty ledger entry, never a station-0 fallback.
        if (nextCurrent < 0) throw new IllegalArgumentException("Current station missing from ledger");
        Station current = desired.get(nextCurrent);
        Article projectedCurrent = nextBoundaries.containsKey(current.id) || nextCursors[nextCurrent] >= current.articles.size()
                ? null : current.articles.get(nextCursors[nextCurrent]);
        if (active != null && (projectedCurrent == null || !active.stationId.equals(current.id)
                || !active.articleId.equals(projectedCurrent.id) || !active.version.equals(projectedCurrent.version)))
            throw new IllegalStateException("Affected radio output must be stopped before projection");
        Drag nextDrag = null;
        if (drag != null && oldCurrent != null && projectedCurrent != null && sameArticle(oldCurrent, projectedCurrent))
            nextDrag = new Drag(drag.id, nextCurrent, nextCursors[nextCurrent], drag.version, drag.targetSentence, drag.resumeAllowed);
        boolean currentSurvives = oldCurrent != null && projectedCurrent != null && sameArticle(oldCurrent, projectedCurrent);
        stations.clear(); stations.addAll(desired);
        stationCursors = nextCursors;
        stationBoundaries.clear(); stationBoundaries.putAll(nextBoundaries);
        progress.clear(); progress.putAll(nextProgress);
        bindings.clear(); bindings.putAll(nextBindings);
        stationIndex = nextCurrent;
        drag = nextDrag;
        selectionRequired = selectionRequired || retired(current.id);
        if (!currentSurvives || selectionRequired) playing = false;
        broadcastEnded = selectionRequired || (currentProgress() == null || currentProgress().passEnded) && findContinuation() == null;
        changed();
    }

    private int stationIndex(String id) {
        for (int i = 0; i < stations.size(); i++) if (stations.get(i).id.equals(id)) return i;
        return -1;
    }
    private static int articleIndex(Station station, String id) {
        for (int i = 0; i < station.articles.size(); i++) if (station.articles.get(i).id.equals(id)) return i;
        return -1;
    }
    private static Article articleById(Station station, String id) {
        int index = articleIndex(station, id); return index < 0 ? null : station.articles.get(index);
    }
    private static boolean sameArticle(Article a, Article b) {
        return a.id.equals(b.id) && a.version.equals(b.version) && a.title.equals(b.title)
                && a.text.equals(b.text) && a.sentenceStarts.equals(b.sentenceStarts);
    }

    public void play() {
        checkOwner();
        if (requiresSelection()) { pauseForAdmission(PlaybackMembership.Admission.NOT_MEMBER); changed(); return; }
        if (playing && drag == null) return;
        if (drag != null) return; // A Play tap cannot defeat the active finger gesture.
        if(recoveryEnded){textGapArticles.clear();recoveryEnded=false;recoveryNotice=null;}
        if (stopFailed) {
            revokeRequest();
            if (stopFailed) { changed(); return; }
        }
        error = null;
        if(route!=null)route.explicitPlay();
        Progress current = currentProgress();
        if (current == null || current.passEnded) {
            Candidate candidate = findContinuation();
            if (candidate == null) {
                playing = false;
                broadcastEnded = true;
                changed();
                return;
            }
            if (!admitCandidate(candidate)) { changed(); return; }
            selectCandidate(candidate);
        } else if (!admitCurrent()) {
            changed(); return;
        }
        playing = true;
        broadcastEnded = false;
        speakSentence();
        changed();
    }

    public void pause() {
        checkOwner();
        playing = false;
        if (drag != null) drag.resumeAllowed = false;
        revokeRequest();
        changed();
    }

    public void interrupt(String reason) {
        checkOwner();
        playing = false;
        if (drag != null) drag.resumeAllowed = false;
        revokeRequest();
        error = reason;
        changed();
    }

    public void previousStation() { checkOwner(); if (stationIndex > 0) selectStation(stationIndex - 1);else cancelTextRecovery(); }
    public void nextStation() { checkOwner(); if (stationIndex + 1 < stations.size()) selectStation(stationIndex + 1);else cancelTextRecovery(); }
    public void previousArticle() {
        checkOwner();if(route!=null){RouteChoice previous=route.previous(currentStation().id,currentArticle());if(previous!=null&&!previous.stationOnly){if(previous.station!=null)applyExplicitRoute(previous);else cancelTextRecovery();return;}}
        if(currentArticle()!=null&&articleIndex()>0)selectArticle(articleIndex()-1);else cancelTextRecovery();
    }
    private void applyExplicitRoute(RouteChoice choice){
        if(choice.station!=null){for(Station target:stations)if(target.id.equals(choice.station))for(Article item:target.articles)if(item.id.equals(choice.article)&&item.version.equals(choice.version)){selectArticle(choice.station,choice.article);return;}pauseForAdmission(PlaybackMembership.Admission.NOT_MEMBER);changed();return;}
        playing=false;revokeRequest();broadcastEnded=true;changed();
    }
    public void nextArticle() {
        checkOwner();
        if(route!=null){
            RouteChoice next=route.next(currentStation().id,currentArticle(),true);
            if(next!=null&&!next.stationOnly){applyExplicitRoute(next);return;}
        }
        if (currentArticle() != null && articleIndex() + 1 < currentStation().articles.size()) selectArticle(articleIndex() + 1);else cancelTextRecovery();
    }

    public void selectStation(int index) {
        checkOwner();
        if (index < 0 || index >= stations.size()) throw new IllegalArgumentException("Station index");
        if (retired(stations.get(index).id)) { pauseForAdmission(PlaybackMembership.Admission.NOT_MEMBER); changed(); return; }
        Station target = stations.get(index);
        if (index == stationIndex && !requiresSelection()) {cancelTextRecovery();return;}
        int targetCursor=stationCursors[index];
        if(!stationBoundaries.containsKey(target.id)&&targetCursor<target.articles.size()){
            Article rememberedArticle=target.articles.get(targetCursor);
            Progress remembered=progress.get(progressKey(target.id,rememberedArticle.id,rememberedArticle.version));
            if(remembered==null||remembered.passEnded||!explicitCursor(target.id,rememberedArticle)&&!remembered.started&&remembered.sentenceIndex==0&&remembered.completed.isEmpty()){
                Candidate first=firstUnfinished(index,0);if(first!=null)targetCursor=first.article;
            }
        }
        if (!stationBoundaries.containsKey(target.id) && targetCursor < target.articles.size()) {
            PlaybackMembership.Admission allowed = admission(target.id, target.articles.get(targetCursor));
            if (allowed != PlaybackMembership.Admission.ALLOWED) { pauseForAdmission(allowed); changed(); return; }
        }
        boolean wasSelectionRequired = requiresSelection();
        drag = null;
        error = null;
        revokeRequest();
        stationIndex = index;
        stationCursors[index]=targetCursor;
        selectionRequired = false;
        if (wasSelectionRequired) playing = false;
        broadcastEnded = false;
        // A station selection does not explicitly repeat any already completed article.
        if (currentProgress() == null || stationHasNoContinuation()) playing = false;
        else if (currentProgress().passEnded) {
            Candidate candidate = firstUnfinished(stationIndex, articleIndex());
            if (candidate != null && admitCandidate(candidate)) selectCandidate(candidate);
        }
        if (playing) speakSentence();
        changed();
    }

    /** Primary Listen may choose an unfinished item, but never resets a started position. */
    public void preferUnfinishedCurrent(boolean explicitChoice){
        checkOwner();if(explicitChoice||requiresSelection()||stationBoundaries.containsKey(currentStation().id))return;
        Progress remembered=currentProgress();
        if(remembered!=null&&!remembered.passEnded&&(explicitCursor(currentStation().id,currentArticle())||remembered.started||remembered.sentenceIndex>0||!remembered.completed.isEmpty()))return;
        Candidate first=firstUnfinished(stationIndex,0);
        if(first!=null&&first.article!=articleIndex()&&admitCandidate(first)){revokeRequest();selectCandidate(first);changed();}
    }

    public void selectStation(String id) {
        checkOwner();
        for (int i = 0; i < stations.size(); i++) if (stations.get(i).id.equals(id)) { selectStation(i); return; }
        throw new IllegalArgumentException("Unknown station");
    }

    public void selectArticle(int index) {
        checkOwner();
        if (index < 0 || index >= currentStation().articles.size()) throw new IllegalArgumentException("Article index");
        selectArticleAt(stationIndex, index);
    }

    private void selectArticleAt(int selectedStation, int selectedArticle) {
        Article target = stations.get(selectedStation).articles.get(selectedArticle);
        PlaybackMembership.Admission allowed = admission(stations.get(selectedStation).id, target);
        if (allowed != PlaybackMembership.Admission.ALLOWED) { pauseForAdmission(allowed); changed(); return; }
        boolean wasSelectionRequired = requiresSelection();
        drag = null;
        error = null;
        revokeRequest();
        stationIndex = selectedStation;
        selectionRequired = false;
        if (wasSelectionRequired) playing = false;
        stationCursors[stationIndex] = selectedArticle;
        explicitArticleCursors.put(currentStation().id,progressKey(currentStation().id,target.id,target.version));
        if(route!=null)route.selected(currentStation().id,target);
        stationBoundaries.remove(currentStation().id);
        Progress item = currentProgress();
        if (item.passEnded) { item.sentenceIndex = 0; item.passEnded = false; }
        broadcastEnded = false;
        if (playing) speakSentence();
        changed();
    }

    private boolean explicitCursor(String station,Article article){
        return article!=null&&progressKey(station,article.id,article.version).equals(explicitArticleCursors.get(station));
    }

    public void selectArticle(String id) {
        checkOwner();
        List<Article> items = currentStation().articles;
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(id)) { selectArticle(i); return; }
        throw new IllegalArgumentException("Unknown article");
    }

    /** Atomic explicit selection from the entire broadcast list; no intermediate item can speak. */
    public void selectArticle(String stationId, String articleId) {
        checkOwner();
        for (int s = 0; s < stations.size(); s++) {
            Station station = stations.get(s);
            if (!station.id.equals(stationId)) continue;
            for (int a = 0; a < station.articles.size(); a++) {
                if (station.articles.get(a).id.equals(articleId)) { selectArticleAt(s, a); return; }
            }
            throw new IllegalArgumentException("Unknown article");
        }
        throw new IllegalArgumentException("Unknown station");
    }

    public void toggleRead() {
        checkOwner();
        Article article = currentArticle();
        if (article != null) toggleRead(currentStation().id, article.id, article.version);
    }

    /** A reader dialog may show an older article while the live broadcast has moved forward. */
    public void toggleRead(String stationId, String articleId, String version) {
        checkOwner();
        if (stationId == null || articleId == null || version == null) return;
        Progress item = progress.get(progressKey(stationId, articleId, version));
        if (item == null) return; // Never silently apply an old text's action to a different version.
        item.read = !item.read;
        changed();
    }

    /** Projection of a durable source flag. Idempotent and independent of speech ownership. */
    public void setRead(String stationId, String articleId, String version, boolean read) {
        checkOwner();
        Progress item = progress.get(progressKey(stationId, articleId, version));
        if (item == null || item.read == read) return;
        item.read = read;
        changed();
    }

    public void seekToFraction(double fraction) {
        checkOwner();
        long token = beginSeek();
        if (token == 0) return;
        updateSeek(token, fraction);
        commitSeek(token);
    }

    public long beginSeek() {
        checkOwner();
        if (currentArticle() == null) return 0;
        if (!admitCurrent()) { changed(); return 0; }
        if (drag != null) return drag.id;
        revokeRequest();
        drag = new Drag(++dragSequence, stationIndex, articleIndex(), currentArticle().version,
                currentProgress().sentenceIndex, playing);
        emit();
        return drag.id;
    }

    public void updateSeek(long token, double fraction) {
        checkOwner();
        if (!validDrag(token)) return;
        drag.targetSentence = currentArticle().nearestSentence(fraction);
        emit(); // Preview only: process restoration must never restore an uncommitted target.
    }

    public void commitSeek(long token) {
        checkOwner();
        if (!validDrag(token)) return;
        if (!admitCurrent()) { drag = null; changed(); return; }
        boolean resume = drag.resumeAllowed && playing;
        currentProgress().sentenceIndex = drag.targetSentence;
        currentProgress().passEnded = false;
        drag = null;
        if (!stopFailed) error = null;
        broadcastEnded = false;
        playing = resume;
        if (playing) speakSentence();
        changed();
    }

    /** Complete cleanup of the previous screen before its replacement can start a new gesture. */
    public void cancelScreenSeek() {
        checkOwner();
        if (drag != null) cancelSeek(drag.id);
    }

    public void cancelSeek(long token) {
        checkOwner();
        if (!validDrag(token)) return;
        boolean resume = drag.resumeAllowed && playing;
        drag = null;
        playing = resume;
        if (playing) speakSentence();
        changed();
    }

    public void onStart(String requestId) {
        checkOwner();
        Request request = currentRequest(requestId);
        if (request == null || request.started) return;
        request.started = true;
        if(route!=null)route.started(request.stationId,currentArticle());
        cancelTimer(request);
        // A bounded watchdog also covers engines which start but never report completion.
        long deadline = Math.min(15 * 60_000L, 60_000L + request.text.length() * 500L);
        request.timer = scheduler.schedule(deadline, () -> timeout(request.id, false, "Движок речи не завершил фразу"));
        emit();
        if(request.binding!=null&&currentRequest(requestId)==request&&checkAdmission(request.stationId,currentArticle(),request.binding)==PlaybackMembership.Admission.ALLOWED) {
            try { speech.lookahead(new SpeechWindow(request.id,request.unit,maxFragmentChars,
                    ()->{checkOwner();return currentRequest(request.id)==request&&request.started&&checkAdmission(request.stationId,currentArticle(),request.binding)==PlaybackMembership.Admission.ALLOWED;})); }
            catch(RuntimeException ignored) { /* Speculation never changes the admitted current playback. */ }
        }
    }

    public void onDone(String requestId) {
        checkOwner();
        Request request = currentRequest(requestId);
        // An unmatched/early completion cannot award coverage or cancel the start deadline.
        if (request == null || !request.started) return;
        active = null;
        cancelTimer(request);
        Progress item = currentProgress();
        if (request.fragmentIndex + 1 < request.fragments.size()) {
            speakFragment(request.fragments, request.fragmentIndex + 1,request.coverageEligible);
        } else {
            if(request.coverageEligible)item.completed.set(item.sentenceIndex);
            if(advanceSentenceOrArticle()&&playing)speakSentence();
        }
        changed();
    }

    public void onError(String requestId, String message) {
        speechFailure(requestId,message,false,()->{});
    }

    /** The terminal callback belongs to this failed request, never a replacement or recovery. */
    public void speechFailure(String requestId,String message,boolean localText,Runnable terminal) {
        checkOwner();
        Request request=currentRequest(requestId);if(request==null)return;
        if(localText&&LocalTextReject.known(message))recoverText(request.unit.sentence,request,message,"preparation");
        else {textEvent("blocked",LocalTextReject.diagnostic(message),requestId,request.unit.sentence,request.unit);fail(message == null || message.trim().isEmpty() ? "Ошибка локального голоса" : message);}
        if(!playing&&active==null&&recovery==null)terminal.run();
    }

    /** Mode-only actions cancel a queued skip but never stop a currently audible request. */
    public void cancelTextRecovery(){checkOwner();if(recovery!=null){playing=false;revokeRequest();changed();}}
    /** Only an explicit new queue/pass resets exclusions; ordinary pause/resume retains them. */
    public void resetTextRecoveryPass(){checkOwner();if(playing||active!=null||recovery!=null)throw new IllegalStateException("Stop before a new pass");textGapArticles.clear();recoveryEnded=false;recoveryNotice=null;}

    public Snapshot snapshot() {
        checkOwner();
        Station station = currentStation();
        Article article = currentArticle();
        Progress item = currentProgress();
        int sentence = article == null ? 0 : drag != null ? drag.targetSentence : item.sentenceIndex;
        int offset = article == null ? 0 : item.passEnded && drag == null ? article.text.length()
                : article.sentences.get(sentence).start;
        Boolean routedNext=route==null?null:route.canNext(station.id,article);
        Boolean routedPrevious=route==null?null:route.canPrevious(station.id,article);
        boolean visibleEnd=broadcastEnded&&(recoveryEnded||!Boolean.TRUE.equals(routedNext));
        String status = error != null ? error : requiresSelection() ? "Источник удалён. Выберите станцию"
                : station.articles.isEmpty() ? "В этой станции пока нет новостей"
                : recoveryNotice!=null ? recoveryNotice : visibleEnd ? "Эфир завершён" : stationHasNoContinuation() ? "Очередь станции пройдена"
                : drag != null ? "Выберите место в тексте" : playing
                ? (active != null && !active.started ? "Подготовка голоса…" : "Читаем на телефоне") : "Пауза";
        return new Snapshot(station, article, stationIndex, article == null ? -1 : articleIndex(),
                sentence, offset, playing, drag != null, item != null && item.passEnded,
                item != null && item.completed.cardinality() == article.sentences.size(),
                item != null && item.read, item != null && item.started,
                item == null ? Collections.emptyList() : completedList(item.completed),
                stationIndex > 0, stationIndex + 1 < stations.size(), routedPrevious==null?article != null && articleIndex() > 0:routedPrevious,
                routedNext==null?article != null && articleIndex() + 1 < station.articles.size():routedNext, visibleEnd,
                status, error, active == null ? null : active.id, requiresSelection());
    }

    public SavedState savedState() {
        checkOwner();
        Map<String, String> cursors = new LinkedHashMap<>();
        Map<String, SavedProgress> items = new LinkedHashMap<>();
        for (int i = 0; i < stations.size(); i++) {
            Station station = stations.get(i);
            String marker = stationBoundaries.get(station.id);
            if (marker != null) cursors.put(station.id, marker);
            else if (!station.articles.isEmpty() && stationCursors[i] < station.articles.size())
                cursors.put(station.id, station.articles.get(stationCursors[i]).id);
            for (Article article : station.articles) {
                Progress item = progress.get(progressKey(station.id, article.id, article.version));
                items.put(progressKey(station.id, article.id, article.version), new SavedProgress(
                        station.id, article.id, article.version, item.sentenceIndex, item.passEnded,
                        completedList(item.completed), item.read, item.started));
            }
        }
        return new SavedState(currentStation().id, cursors, items);
    }

    public static String progressKey(String stationId, String articleId, String version) {
        return stationId.length() + ":" + stationId + articleId.length() + ":" + articleId + version.length() + ":" + version;
    }

    private void restore(SavedState state) {
        if (state == null) return;
        for (int s = 0; s < stations.size(); s++) {
            Station station = stations.get(s);
            if (station.id.equals(state.currentStationId)) stationIndex = s;
            String currentId = state.stationArticleIds.get(station.id);
            if (currentId != null && currentId.startsWith(NoteBook.AFTER_PREFIX)) {
                try {
                    NoteBook.boundarySequence(currentId);
                    NoteBook.boundaryCandidate(currentId);
                    stationBoundaries.put(station.id, currentId);
                } catch (IllegalArgumentException invalid) {
                    stationBoundaries.put(station.id, NoteBook.boundaryMarker(Long.MAX_VALUE, null));
                    error = "Некорректная сохранённая граница очереди";
                }
                stationCursors[s] = station.articles.size();
            }
            for (int a = 0; a < station.articles.size(); a++) {
                Article article = station.articles.get(a);
                if (!stationBoundaries.containsKey(station.id) && article.id.equals(currentId)) stationCursors[s] = a;
                String key = progressKey(station.id, article.id, article.version);
                SavedProgress saved = state.progress.get(key);
                if (saved == null || !station.id.equals(saved.stationId) || !article.id.equals(saved.articleId)
                        || !article.version.equals(saved.version)) continue;
                Progress item = progress.get(key);
                item.sentenceIndex = Math.max(0, Math.min(article.sentences.size() - 1, saved.sentenceIndex));
                item.passEnded = saved.passEnded && item.sentenceIndex == article.sentences.size() - 1;
                item.read = saved.read;
                item.started = saved.started;
                for (Integer sentence : saved.completedSentences) {
                    if (sentence != null && sentence >= 0 && sentence < article.sentences.size()) item.completed.set(sentence);
                }
            }
        }
    }

    private void speakSentence() {
        if (!playing || drag != null || currentArticle() == null || currentProgress().passEnded) return;
        if (!admitCurrent()) return;
        String text = currentArticle().sentences.get(currentProgress().sentenceIndex).text;
        List<String> fragments;
        SpeechSentence sentence=new SpeechSentence(currentStation().id,currentArticle(),bindings.get(progressKey(currentStation().id,currentArticle().id,currentArticle().version)),currentProgress().sentenceIndex);
        try {
            fragments = TextSegments.checkedFragments(text, speech.fragments(sentence, maxFragmentChars), maxFragmentChars);
        } catch (RuntimeException rejected) {
            String local=LocalTextReject.preflight(rejected);if(local!=null){recoverText(sentence,null,local,"preflight");return;}
            String code = rejected.getMessage();
            fail("Текст не поддерживается локальным голосом"
                    + (code != null && code.matches("[A-Z][A-Z0-9_]{0,79}") ? ": " + code : ""));
            return;
        }
        speakFragment(fragments, 0);
    }

    private void speakFragment(List<String> fragments, int fragment) {
        speakFragment(fragments,fragment,true);
    }
    private void speakFragment(List<String> fragments,int fragment,boolean eligible) {
        if (!playing || drag != null) return;
        if (!admitCurrent()) return; // Before UUID, started mark, timer or engine submission.
        Article article = currentArticle();
        Progress item = currentProgress();
        Request request = new Request(PROCESS_EPOCH + ":" + REQUEST_SEQUENCE.incrementAndGet(),
                currentStation().id, article.id, article.version, item.sentenceIndex,
                fragment, fragments, fragments.get(fragment), bindings.get(progressKey(currentStation().id, article.id, article.version)),
                new SpeechUnit(new SpeechSentence(currentStation().id,article,bindings.get(progressKey(currentStation().id,article.id,article.version)),item.sentenceIndex),fragments,fragment),eligible);
        active = request;
        if(!eligible||recoveryNotice!=null)textEvent("recovery_submit","TEXT_CONTINUATION",request.id,request.unit.sentence,request.unit);
        item.started = true;
        request.timer = scheduler.schedule(START_TIMEOUT_MS, () -> timeout(request.id, true, "Речь не началась за 10 секунд"));
        try { speech.speak(request.unit, request.id); }
        catch (RuntimeException failure) { if (currentRequest(request.id) != null){String code=LocalTextReject.preflight(failure);if(code!=null)recoverText(request.unit.sentence,request,code,"submission");else fail("Не удалось запустить локальный голос");} }
    }

    private boolean advanceSentenceOrArticle(){
        Progress item=currentProgress();if(item.sentenceIndex+1<currentArticle().sentences.size()){if(!admitCurrent())return false;item.sentenceIndex++;return true;}
        item.passEnded=item.started;Candidate next=findContinuation();
        if(next!=null&&playing&&admitCandidate(next)){selectCandidate(next);if(!textGapArticles.isEmpty())recoveryNotice="Часть текста пропущена. Читаем следующую новость.";return true;}
        playing=false;broadcastEnded=next==null;recoveryEnded=next==null&&!textGapArticles.isEmpty();if(recoveryEnded)recoveryNotice="Часть текста пропущена. Доступное чтение закончилось.";return false;
    }
    private void textEvent(String stage,String reason,String id,SpeechSentence sentence,SpeechUnit unit){try{speech.textEvent(stage,reason,id,intentGeneration,sentence,unit);}catch(RuntimeException ignored){}}
    private void recoverText(SpeechSentence sentence,Request rejected,String code,String stage){
        if(!playing||drag!=null||!LocalTextReject.known(code))return;
        String id=rejected==null?"preflight":rejected.id;textEvent(stage+"_reject",code,id,sentence,rejected==null?null:rejected.unit);
        long before=intentGeneration;revokeRequest();
        if(stopFailed){textEvent("blocked_stop","STOP_UNCONFIRMED",id,sentence,rejected==null?null:rejected.unit);changed();return;}
        if(!playing||drag!=null||intentGeneration!=before+1||currentArticle()==null||!currentStation().id.equals(sentence.stationId)||!sameArticle(currentArticle(),sentence.article)||currentProgress().sentenceIndex!=sentence.index)return;
        if(checkAdmission(sentence.stationId,sentence.article,sentence.binding)!=PlaybackMembership.Admission.ALLOWED){pauseForAdmission(PlaybackMembership.Admission.UNVERIFIED);changed();return;}
        textGapArticles.add(progressKey(sentence.stationId,sentence.article.id,sentence.article.version));
        Recovery pending=new Recovery(intentGeneration,sentence,rejected,code,id);recovery=pending;recoveryNotice="Часть текста пропущена. Продолжаем чтение.";
        pending.cancel=scheduler.schedule(0,()->runRecovery(pending));
        changed();
    }
    private void runRecovery(Recovery pending){
        checkOwner();if(recovery!=pending||pending.intent!=intentGeneration||!playing||drag!=null||currentArticle()==null||!currentStation().id.equals(pending.sentence.stationId)||!sameArticle(currentArticle(),pending.sentence.article)||currentProgress().sentenceIndex!=pending.sentence.index)return;
        recovery=null;pending.cancel=null;if(error!=null){fail(error);return;}
        if(checkAdmission(pending.sentence.stationId,pending.sentence.article,pending.sentence.binding)!=PlaybackMembership.Admission.ALLOWED){pauseForAdmission(PlaybackMembership.Admission.UNVERIFIED);changed();return;}
        Request bad=pending.request;boolean fragment=bad!=null&&bad.fragmentIndex+1<bad.fragments.size();
        boolean advance;try{advance=fragment||advanceSentenceOrArticle();}catch(RuntimeException unknown){fail("Не удалось подтвердить продолжение текста");return;}
        textEvent(fragment?"skip_fragment":"skip_sentence",pending.code,pending.id,pending.sentence,bad==null?null:bad.unit);
        changed();if(!advance||!playing||drag!=null||intentGeneration!=pending.intent)return;
        if(error!=null){fail(error);return;}
        if(fragment)speakFragment(bad.fragments,bad.fragmentIndex+1,false);else speakSentence();
    }

    private Request currentRequest(String id) {
        Request request = active;
        Article article = currentArticle();
        if (request == null || id == null || !request.id.equals(id) || !playing || drag != null || article == null) return null;
        if (!request.stationId.equals(currentStation().id) || !request.articleId.equals(article.id)
                || !request.version.equals(article.version) || request.sentenceIndex != currentProgress().sentenceIndex
                || request.fragmentIndex < 0 || request.fragmentIndex >= request.fragments.size()) return null;
        // Uncertainty blocks a new unit, not completion of this already permitted submitted unit.
        if (checkAdmission(request.stationId, article, request.binding) == PlaybackMembership.Admission.NOT_MEMBER) return null;
        return request;
    }

    private void timeout(String id, boolean awaitingStart, String message) {
        checkOwner();
        Request request = currentRequest(id);
        if (request != null && request.started != awaitingStart) fail(message);
    }

    private void fail(String message) {
        playing = false;
        if (drag != null) drag.resumeAllowed = false;
        revokeRequest();
        error = message;
        changed();
    }

    private void revokeRequest() {
        intentGeneration++;Recovery pending=recovery;recovery=null;if(pending!=null&&pending.cancel!=null)pending.cancel.cancel();recoveryNotice=null;recoveryEnded=false;
        Request old = active;
        active = null; // Invalidate first: stop() may synchronously re-enter the callback adapter.
        if (old != null) cancelTimer(old);
        try { speech.stop(); stopFailed = false; }
        catch (RuntimeException ignored) {
            playing = false;
            stopFailed = true;
            error = "Движок речи не подтвердил остановку";
        }
    }

    private void cancelTimer(Request request) {
        if (request.timer != null) { request.timer.cancel(); request.timer = null; }
    }

    private boolean validDrag(long token) {
        return drag != null && currentArticle() != null && drag.id == token && drag.stationIndex == stationIndex
                && drag.articleIndex == articleIndex() && currentArticle().version.equals(drag.version);
    }

    private Candidate findContinuation() {
        if (requiresSelection()) return null;
        boolean stationOnly=false;
        if(route!=null){
            RouteChoice choice=route.next(currentStation().id,currentArticle(),Collections.unmodifiableSet(textGapArticles));
            if(choice!=null){
                if(choice.stationOnly)stationOnly=true;
                else{
                    if(choice.station==null)return null;
                    for(int s=0;s<stations.size();s++)if(stations.get(s).id.equals(choice.station))
                        for(int a=0;a<stations.get(s).articles.size();a++){
                            Article article=stations.get(s).articles.get(a);
                            if(article.id.equals(choice.article)&&article.version.equals(choice.version)&&!textGapArticles.contains(progressKey(choice.station,article.id,article.version)))
                                return new Candidate(s,a,admission(choice.station,article),true);
                        }
                    return null;
                }
            }
        }
        int from = continuationStart(stationIndex) + (currentProgress() != null && currentProgress().passEnded ? 1 : 0);
        Candidate same = firstUnfinished(stationIndex, from,true);
        if (same != null) return same;
        if(stationOnly)return null;
        for (int s = stationIndex + 1; s < stations.size(); s++) {
            // Retired source is a manual-choice barrier, never a bridge to later stations.
            if (retired(stations.get(s).id)) return new Candidate(s, -1, PlaybackMembership.Admission.NOT_MEMBER);
            Candidate next = firstUnfinished(s, continuationStart(s),true);
            if (next != null) return next;
        }
        return null;
    }

    private Candidate firstUnfinished(int s, int from) {
        return firstUnfinished(s,from,false);
    }
    private Candidate firstUnfinished(int s,int from,boolean excludeTextGaps){
        Station station = stations.get(s);
        for (int a = from; a < station.articles.size(); a++) {
            Article article = station.articles.get(a);
            if ((!excludeTextGaps||!textGapArticles.contains(progressKey(station.id,article.id,article.version)))&&!progress.get(progressKey(station.id, article.id, article.version)).passEnded)
                return new Candidate(s, a, admission(station.id, article));
        }
        return null;
    }

    private int continuationStart(int station) {
        String marker = stationBoundaries.get(stations.get(station).id);
        if (marker == null) return stationCursors[station];
        String candidate = NoteBook.boundaryCandidate(marker);
        List<Article> articles = stations.get(station).articles;
        if (candidate != null) for (int i = 0; i < articles.size(); i++) if (articles.get(i).id.equals(candidate)) return i;
        return articles.size();
    }
    private boolean stationHasNoContinuation() {
        Candidate candidate = firstUnfinished(stationIndex, continuationStart(stationIndex));
        return candidate == null || candidate.admission != PlaybackMembership.Admission.ALLOWED;
    }
    private void selectCandidate(Candidate candidate) {
        stationIndex = candidate.station;
        stationCursors[stationIndex] = candidate.article;
        stationBoundaries.remove(currentStation().id);
        Progress p=currentProgress();if(candidate.restart&&p!=null&&p.passEnded){p.sentenceIndex=0;p.passEnded=false;}
        if(route!=null)route.selected(currentStation().id,currentArticle());
    }
    private Station currentStation() { return stations.get(stationIndex); }
    private int articleIndex() { return stationCursors[stationIndex]; }
    private Article currentArticle() {
        return requiresSelection() || stationBoundaries.containsKey(currentStation().id) || articleIndex() >= currentStation().articles.size()
                ? null : currentStation().articles.get(articleIndex());
    }
    private Progress currentProgress() {
        Article article = currentArticle();
        return article == null ? null : progress.get(progressKey(currentStation().id, article.id, article.version));
    }

    private boolean requiresSelection() { return selectionRequired || retired(currentStation().id); }
    private boolean retired(String station) {
        try { return membership.stationRetired(station); }
        catch (RuntimeException unavailable) { return false; }
    }
    private PlaybackMembership.Binding capture(String station, Article article) {
        try { return membership.capture(station, article); }
        catch (RuntimeException unavailable) { return null; }
    }
    private PlaybackMembership.Admission checkAdmission(String station, Article article, PlaybackMembership.Binding binding) {
        if (retired(station)) return PlaybackMembership.Admission.NOT_MEMBER;
        try {
            PlaybackMembership.Admission result = membership.check(station, article, binding);
            return result == null ? PlaybackMembership.Admission.UNVERIFIED : result;
        } catch (RuntimeException unavailable) { return PlaybackMembership.Admission.UNVERIFIED; }
    }
    private PlaybackMembership.Admission admission(String station, Article article) {
        return checkAdmission(station, article, bindings.get(progressKey(station, article.id, article.version)));
    }
    private boolean admitCurrent() {
        Article article = currentArticle();
        PlaybackMembership.Admission result = article == null ? PlaybackMembership.Admission.NOT_MEMBER
                : admission(currentStation().id, article);
        if (result == PlaybackMembership.Admission.ALLOWED) return true;
        pauseForAdmission(result); return false;
    }
    private boolean admitCandidate(Candidate candidate) {
        PlaybackMembership.Admission result = candidate.admission;
        if (result == PlaybackMembership.Admission.ALLOWED && candidate.article >= 0)
            result = admission(stations.get(candidate.station).id, stations.get(candidate.station).articles.get(candidate.article));
        if (result == PlaybackMembership.Admission.ALLOWED) return true;
        pauseForAdmission(result); return false;
    }
    private void pauseForAdmission(PlaybackMembership.Admission result) {
        error = result == PlaybackMembership.Admission.UNVERIFIED
                ? "Сохранение не подтверждено. Откройте эфир снова."
                : "Источник или новость удалены. Выберите станцию";
        // An attempted selection of a denied OTHER target cannot silently abandon live A.
        if (active != null) return;
        playing = false;
        if (drag != null) drag.resumeAllowed = false;
        // No stop: these callers admit a new unit, and another note audio owner is untouched.
    }

    private void changed() {
        try { store.save(savedState()); }
        catch (RuntimeException ignored) { error = "Не удалось сохранить позицию"; }
        emit();
    }
    private void emit() { listener.onStateChanged(snapshot()); }
    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Commands and callbacks must run on the owner thread");
    }
    private static List<Integer> completedList(BitSet completed) {
        List<Integer> result = new ArrayList<>();
        for (int i = completed.nextSetBit(0); i >= 0; i = completed.nextSetBit(i + 1)) result.add(i);
        return Collections.unmodifiableList(result);
    }

    private static final class Progress {
        int sentenceIndex;
        boolean passEnded;
        boolean read;
        boolean started;
        final BitSet completed = new BitSet();
    }
    private static final class Candidate {
        final int station;
        final int article;
        final PlaybackMembership.Admission admission;
        final boolean restart;
        Candidate(int station, int article) { this(station, article, PlaybackMembership.Admission.ALLOWED); }
        Candidate(int station, int article, PlaybackMembership.Admission admission) {this(station,article,admission,false);}
        Candidate(int station, int article, PlaybackMembership.Admission admission,boolean restart) { this.station = station; this.article = article; this.admission = admission;this.restart=restart; }
    }
    private static final class Drag {
        final long id;
        final int stationIndex;
        final int articleIndex;
        final String version;
        int targetSentence;
        boolean resumeAllowed;
        Drag(long id, int station, int article, String version, int sentence, boolean resume) {
            this.id = id; stationIndex = station; articleIndex = article; this.version = version;
            targetSentence = sentence; resumeAllowed = resume;
        }
    }
    private static final class Request {
        final String id;
        final String stationId;
        final String articleId;
        final String version;
        final int sentenceIndex;
        final int fragmentIndex;
        final List<String> fragments;
        final String text;
        final PlaybackMembership.Binding binding;
        final SpeechUnit unit;
        final boolean coverageEligible;
        boolean started;
        Cancel timer;
        Request(String id, String stationId, String articleId, String version, int sentence, int fragment,
                List<String> fragments, String text, PlaybackMembership.Binding binding, SpeechUnit unit,boolean eligible) {
            this.id = id; this.stationId = stationId; this.articleId = articleId; this.version = version;
            sentenceIndex = sentence; fragmentIndex = fragment; this.fragments = fragments; this.text = text;
            this.binding = binding;
            this.unit=unit;
            coverageEligible=eligible;
        }
    }
    private static final class Recovery {
        final long intent;final SpeechSentence sentence;final Request request;final String code,id;Cancel cancel;
        Recovery(long intent,SpeechSentence sentence,Request request,String code,String id){this.intent=intent;this.sentence=sentence;this.request=request;this.code=code;this.id=id;}
    }

    public static final class SavedState {
        public final String currentStationId;
        public final Map<String, String> stationArticleIds;
        public final Map<String, SavedProgress> progress;
        public SavedState(String currentStationId, Map<String, String> stationArticleIds, Map<String, SavedProgress> progress) {
            this.currentStationId = currentStationId;
            this.stationArticleIds = Collections.unmodifiableMap(new LinkedHashMap<>(stationArticleIds));
            this.progress = Collections.unmodifiableMap(new LinkedHashMap<>(progress));
        }
    }
    public static final class SavedProgress {
        public final String stationId;
        public final String articleId;
        public final String version;
        public final int sentenceIndex;
        public final boolean passEnded;
        public final List<Integer> completedSentences;
        public final boolean read;
        public final boolean started;
        public SavedProgress(String stationId, String articleId, String version, int sentenceIndex,
                             boolean passEnded, List<Integer> completedSentences, boolean read, boolean started) {
            this.stationId = stationId; this.articleId = articleId; this.version = version;
            this.sentenceIndex = sentenceIndex; this.passEnded = passEnded;
            this.completedSentences = Collections.unmodifiableList(new ArrayList<>(completedSentences));
            this.read = read; this.started = started;
        }
    }
    public static final class Snapshot {
        public final Station station;
        public final Article article;
        public final int stationIndex;
        public final int articleIndex;
        public final int sentenceIndex;
        public final int textOffset;
        public final boolean playing;
        public final boolean seeking;
        public final boolean passEnded;
        public final boolean listened;
        public final boolean read;
        public final boolean started;
        public final List<Integer> completedSentences;
        public final boolean canPreviousStation;
        public final boolean canNextStation;
        public final boolean canPreviousArticle;
        public final boolean canNextArticle;
        public final boolean broadcastEnded;
        public final String status;
        public final String error;
        public final String currentRequestId;
        public final boolean selectionRequired;
        Snapshot(Station station, Article article, int stationIndex, int articleIndex, int sentenceIndex,
                 int textOffset, boolean playing, boolean seeking, boolean passEnded, boolean listened,
                 boolean read, boolean started, List<Integer> completedSentences, boolean previousStation,
                 boolean nextStation, boolean previousArticle, boolean nextArticle, boolean broadcastEnded,
                 String status, String error, String currentRequestId, boolean selectionRequired) {
            this.station = station; this.article = article; this.stationIndex = stationIndex; this.articleIndex = articleIndex;
            this.sentenceIndex = sentenceIndex; this.textOffset = textOffset; this.playing = playing; this.seeking = seeking;
            this.passEnded = passEnded; this.listened = listened; this.read = read; this.started = started;
            this.completedSentences = completedSentences; canPreviousStation = previousStation; canNextStation = nextStation;
            canPreviousArticle = previousArticle; canNextArticle = nextArticle; this.broadcastEnded = broadcastEnded;
            this.status = status; this.error = error; this.currentRequestId = currentRequestId;
            this.selectionRequired = selectionRequired;
        }
    }
}

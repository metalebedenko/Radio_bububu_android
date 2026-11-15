package ru.bububu.radio.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Arrays;
import ru.bububu.radio.sources.SourceRecord;
import ru.bububu.radio.sources.SourceItem;
import ru.bububu.radio.sources.SourceVersionRecord;
import ru.bububu.radio.sources.SourceLimits;

/** Owner-thread metadata book. Storage.save must atomically replace its entire byte snapshot. */
public final class NoteBook {
    public interface Storage { byte[] load(); void save(byte[] bytes); }
    public interface MigrationStorage extends Storage {
        MigrationStamp prepareMigration(byte[] expectedV1, int encodedTargetBytes);
        void verifyMigrationStamp(MigrationStamp stamp);
        default UpgradeRollbackRef prepareSchema3Upgrade(byte[] expectedSchema2,int encodedTargetBytes) { throw new IllegalStateException("Schema3-capable storage is required"); }
        default void verifyUpgradeRollback(UpgradeRollbackRef ref) { throw new IllegalStateException("Schema3 rollback verification unavailable"); }
        default void verifyUpgradeRollback(UpgradeRollbackRef ref,MigrationStamp origin) {verifyUpgradeRollback(ref);}
        default Format4BaselineRef prepareFormat4Upgrade(byte[] baseline,int targetBytes){throw new IllegalStateException("Format4-capable storage is required");}
        default void verifyFormat4Baseline(Format4BaselineRef ref){throw new IllegalStateException("Format4 rollback verification unavailable");}
    }
    public static final int MAX_NOTES = 100;
    public static final int MAX_TITLE = 200;
    public static final int MAX_TEXT = 20_000;
    public static final int MAX_METADATA_BYTES = 8 * 1024 * 1024;
    public static final String MY_TEXTS = "MY_TEXTS";
    public static final String RECORDING = "RECORDING";
    public static final String SAVED = "SAVED";
    public static final String INTERRUPTED = "INTERRUPTED";
    public static final String NOT_REQUESTED = "NOT_REQUESTED";
    public static final String AFTER_PREFIX = "@after:";
    public static final String SELECTED_PREFIX = "@selected:";
    private static final int MAGIC = 0x424e4f54;
    private final Thread owner = Thread.currentThread();
    private final Storage storage;
    private State state;
    private String reconciliationMessage;
    private String migrationWarning;
    private byte[] legacyBytes;
    private boolean storageUncertain;
    private boolean format45Enabled;
    public void enable45(){checkOwner();format45Enabled=true;}
    public boolean format45Active(){checkOwner();return format45Enabled||state.schema==4;}
    private long revision;
    private long authorityEpoch;

    public NoteBook(Storage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
        byte[] bytes = storage.load();
        state = bytes == null ? new State() : decode(bytes);
        legacyBytes = state.schema == 1 && bytes != null ? bytes.clone() : null;
        verifyRollbackCapsule();
    }

    public List<Note> notes() { checkOwner(); return immutable(state.notes.values()); }
    public Note find(String id) { checkOwner(); return state.notes.get(id); }
    public List<Note> search(String query) {
        checkOwner();
        String normalized = fold(Objects.requireNonNull(query, "query")).trim();
        if (normalized.isEmpty()) return notes();
        String[] terms = normalized.split("\\s+");
        List<Note> result = new ArrayList<>();
        for (Note note : state.notes.values()) {
            String haystack = fold(note.title + " " + note.text);
            boolean match = true;
            for (String term : terms) if (!haystack.contains(term)) { match = false; break; }
            if (match) result.add(note);
        }
        return Collections.unmodifiableList(result);
    }

    public Note beginRecording(String title, String linkedStation, String linkedArticle,
                               String linkedVersion, long createdAt) {
        checkOwner();
        if (state.notes.size() >= MAX_NOTES) throw new IllegalStateException("Достигнут предел 100 заметок");
        limited(title, MAX_TITLE, "title", false);
        checkLinks(linkedStation, linkedArticle, linkedVersion);
        if (createdAt < 0) throw new IllegalArgumentException("createdAt");
        Note note = new Note(UUID.randomUUID().toString(), createdAt, title, "", 0,
                linkedStation, linkedArticle, linkedVersion, 0, 0, false, RECORDING, NOT_REQUESTED, null);
        State next = new State(state);
        Material linked=linkedStation==null?null:next.materials.get(key(linkedStation,linkedArticle));
        if(linked!=null && linked.sourceVersionKey!=null && linked.version.equals(linkedVersion))
            note=note.withSourceLink(linked.sourceVersionKey);
        next.notes.put(note.id, note);
        commit(next); // A durable draft is required before the microphone can start.
        return note;
    }

    public boolean pendingTakeStub(String id,long expectedRevision,long expectedCreatedAt) {
        checkOwner();Note n=state.notes.get(id);
        if(storageUncertain||n==null||n.revision!=expectedRevision||n.createdAt!=expectedCreatedAt||n.hasAudio||!n.text.isEmpty()||!RECORDING.equals(n.recordingStatus))return false;
        for(Material m:state.materials.values())if(id.equals(m.noteId))return false;
        if(n.linkedSourceVersionKey!=null){SourceVersionRecord v=state.versions.get(n.linkedSourceVersionKey);if(v==null||!active(state,v.sourceId))return false;}
        return true;
    }
    public void discardPendingTakeStub(String id,long expectedRevision,long expectedCreatedAt) {
        checkOwner();if(!pendingTakeStub(id,expectedRevision,expectedCreatedAt))throw new IllegalStateException("Take is no longer an owned empty stub");
        State next=new State(state);next.notes.remove(id);commit(next);
    }
    public Note finishRecording(String id, long durationMs, boolean hasAudio, boolean interrupted, String message) {
        checkOwner();
        Note old = required(id);
        if (durationMs < 0) throw new IllegalArgumentException("durationMs");
        limited(message, 2000, "message", true);
        Note note = old.copy(old.title, old.text, old.revision, durationMs,
                Math.min(old.positionMs, durationMs), hasAudio, interrupted ? INTERRUPTED : SAVED,
                old.recognitionStatus, message);
        replace(note);
        return note;
    }

    /** Recovery never starts recording; an orphan is adopted only while the metadata book is valid. */
    public Note recoverOriginal(String id, long createdAt, long durationMs, boolean hasAudio, String message) {
        checkOwner();
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("UUID");
        if (state.notes.containsKey(id)) return finishRecording(id, durationMs, hasAudio, true, message);
        if (state.notes.size() >= MAX_NOTES) throw new IllegalStateException("Достигнут предел 100 заметок");
        if (createdAt < 0 || durationMs < 0) throw new IllegalArgumentException("Recovered time");
        limited(message, 2000, "message", true);
        Note recovered = new Note(id, createdAt, "Восстановленная запись", "", 0, null, null, null,
                durationMs, 0, hasAudio, INTERRUPTED, NOT_REQUESTED, message);
        replace(recovered);
        return recovered;
    }

    /** Restore availability after a transient WAV read failure; no user or STT fields change. */
    public Note reconcileAvailableOriginal(String id, long durationMs) {
        checkOwner();
        Note old = required(id);
        if (durationMs < 0) throw new IllegalArgumentException("durationMs");
        if (!INTERRUPTED.equals(old.recordingStatus) || old.hasAudio || durationMs == 0) return old;
        if (old.positionMs > durationMs) throw new IllegalArgumentException("Original is shorter than its saved position");
        Note restored = old.copy(old.title, old.text, old.revision, durationMs, old.positionMs, true,
                old.recordingStatus, old.recognitionStatus, old.message);
        replace(restored);
        return restored;
    }

    public Note setOriginalPosition(String id, long millis) {
        checkOwner();
        Note old = required(id);
        if (millis < 0) throw new IllegalArgumentException("positionMs");
        long position = Math.min(millis, old.durationMs);
        if (position == old.positionMs) return old;
        Note note = old.copy(old.title, old.text, old.revision, old.durationMs, position,
                old.hasAudio, old.recordingStatus, old.recognitionStatus, old.message);
        replace(note);
        return note;
    }

    public Note edit(String id, String title, String text) {
        checkOwner();
        Note old = required(id);
        limited(title, MAX_TITLE, "title", false);
        limited(text, MAX_TEXT, "text", false);
        if (old.title.equals(title) && old.text.equals(text)) return old;
        Note note = old.copy(title, text, Math.addExact(old.revision, 1), old.durationMs,
                old.positionMs, old.hasAudio, old.recordingStatus, old.recognitionStatus, old.message);
        replace(note);
        return note;
    }

    /** Stale callbacks change nothing, including status; text installation increments the revision. */
    public boolean setRecognition(String id, long expectedRevision, String status, String message, String optionalText) {
        checkOwner();
        Note old = required(id);
        if (old.revision != expectedRevision) return false;
        limited(status, 64, "recognitionStatus", false);
        if (status.trim().isEmpty()) throw new IllegalArgumentException("recognitionStatus");
        limited(message, 2000, "message", true);
        limited(optionalText, MAX_TEXT, "text", true);
        String text = optionalText == null ? old.text : optionalText;
        long revision = old.text.equals(text) ? old.revision : Math.addExact(old.revision, 1);
        replace(old.copy(old.title, text, revision, old.durationMs, old.positionMs, old.hasAudio,
                old.recordingStatus, status, message));
        return true;
    }

    public Material addToAir(String id) {
        checkOwner();
        Note note = required(id);
        for (Material material : state.materials.values()) if (id.equals(material.noteId)) return material;
        if (note.text.trim().isEmpty()) throw new IllegalArgumentException("Пустой текст нельзя добавить в эфир");
        Article article = new Article("note:" + id + ":r" + note.revision, "note-revision:" + note.revision,
                note.title, note.text);
        return addMaterial(MY_TEXTS, "Мои тексты", article, id);
    }

    public Material removeFromAir(String id) {
        checkOwner();
        required(id);
        for (Material material : state.materials.values()) if (id.equals(material.noteId))
            return removeSavedMaterial(material.stationId, material.articleId);
        return null;
    }

    public Material addSavedMaterial(String stationId, String stationName, Article article) {
        checkOwner();
        return addMaterial(stationId, stationName, article, null);
    }

    private Material addMaterial(String stationId, String stationName, Article article, String noteId) {
        validId(stationId, "stationId");
        limited(stationName, MAX_TITLE, "stationName", false);
        if (stationName.trim().isEmpty()) throw new IllegalArgumentException("stationName");
        Objects.requireNonNull(article, "article");
        validId(article.id, "articleId");
        validId(article.version, "version");
        limited(article.title, MAX_TITLE, "title", false);
        limited(article.text, MAX_TEXT, "text", false);
        if (article.text.trim().isEmpty()) throw new IllegalArgumentException("text");
        String registered = state.stations.get(stationId);
        if (registered != null && !registered.equals(stationName)) throw new IllegalArgumentException("Station name is immutable");
        String key = key(stationId, article.id);
        Material old = state.materials.get(key);
        if (old != null) {
            if (!old.version.equals(article.version) || !old.title.equals(article.title)
                    || !old.text.equals(article.text) || !old.starts.equals(article.sentenceStarts)
                    || !Objects.equals(old.noteId, noteId))
                throw new IllegalArgumentException("Selected material is immutable; remove it first");
            return old;
        }
        State next = new State(state);
        if (next.materials.size() >= 10000 || (registered == null && next.stations.size() >= 10000))
            throw new IllegalStateException("Достигнут предел каталога");
        Material material = new Material(stationId, stationName, article.id, article.version, article.title,
                article.text, Math.addExact(next.sequence, 1), noteId, article.sentenceStarts);
        next.sequence = material.sequence;
        next.stations.put(stationId, stationName);
        next.materials.put(key, material);
        commit(next);
        return material;
    }

    public Material removeSavedMaterial(String stationId, String articleId) {
        checkOwner();
        String key = key(stationId, articleId);
        Material material = state.materials.get(key);
        if (material == null) return null;
        if(material.sourceVersionKey!=null) return removeSourceMaterial(stationId,articleId,null);
        State next = new State(state);
        if (!next.tombstones.containsKey(key) && next.tombstones.size() >= 200000)
            throw new IllegalStateException("Достигнут предел истории удалений");
        next.materials.remove(key);
        next.tombstones.put(key, material.sequence);
        commit(next);
        return material;
    }

    public List<Material> materials() { checkOwner(); return immutable(state.materials.values()); }
    public List<SourceRecord> sources() { checkOwner();List<SourceRecord> result=new ArrayList<>();for(SourceRecord source:state.sources.values())if(active(state,source.id))result.add(source);return immutable(result); }
    public List<SourceRecord> allSources() {checkOwner();return immutable(state.sources.values());}
    public boolean isSourceActive(String id){checkOwner();return active(state,id);}
    public boolean isSourceRetired(String id){checkOwner();SourceLifecycle life=state.lifecycle.get(id);return state.sources.containsKey(id)&&life!=null&&life.status==SourceLifecycle.RETIRED;}
    public long sourceGeneration(String id){checkOwner();SourceLifecycle life=state.lifecycle.get(id);return life==null?0:life.generation;}
    public long lastDeletionSequence(String id){checkOwner();SourceLifecycle life=state.lifecycle.get(id);return life==null?0:life.lastDeletionSequence;}
    public long sequence(){checkOwner();return state.sequence;}
    public boolean isItemDeleted(String id,String item){checkOwner();return state.deletedItems.containsKey(key(id,item));}
    public boolean retiredStation(String station){checkOwner();for(SourceRecord source:state.sources.values())if(source.stationId.equals(station))return !active(state,source.id);return false;}
    public Material material(String station,String article){checkOwner();return state.materials.get(key(station,article));}
    public SourceItem provenanceSourceItem(String source,String item){checkOwner();return anyItem(state,source,item);}
    public enum Membership { ALLOWED, NOT_MEMBER, UNVERIFIED }
    public Membership queueMembership(String station,Article exact,long sequence,String sourceVersionKey){
        checkOwner();if(storageUncertain)return Membership.UNVERIFIED;
        Material material=state.materials.get(key(station,exact.id));
        if(material==null||material.sequence!=sequence||!Objects.equals(material.sourceVersionKey,sourceVersionKey)
                ||!material.version.equals(exact.version)||!material.title.equals(exact.title)||!material.text.equals(exact.text)||!material.starts.equals(exact.sentenceStarts))return Membership.NOT_MEMBER;
        if(sourceVersionKey!=null){SourceVersionRecord version=state.versions.get(sourceVersionKey);if(version==null||!active(state,version.sourceId)||state.deletedItems.containsKey(key(version.sourceId,version.itemKey)))return Membership.NOT_MEMBER;}
        return Membership.ALLOWED;
    }
    private static boolean active(State value,String id){SourceLifecycle life=value.lifecycle.get(id);return value.sources.containsKey(id)&&(!lifecycleSchema(value.schema)||(life!=null&&life.status==SourceLifecycle.ACTIVE));}
    private static SourceItem anyItem(State value,String source,String item){String k=key(source,item);SourceItem result=value.items.get(k);if(result==null)result=value.hidden.get(k);if(result==null)result=value.deletedItems.get(k);return result;}
    public SourceRecord source(String id) { checkOwner(); return state.sources.get(id); }
    public List<SourceItem> sourceItems(String sourceId) {
        checkOwner(); List<SourceItem> items=new ArrayList<>();if(!active(state,sourceId))return Collections.emptyList();
        for(SourceItem item:state.items.values()) if(item.sourceId.equals(sourceId)) items.add(item);
        return Collections.unmodifiableList(items);
    }
    /** Identity resolution sees hide tombstones; ordinary UI uses sourceItems, which excludes them. */
    public List<SourceItem> sourceIdentityItems(String sourceId) {
        checkOwner();List<SourceItem> items=new ArrayList<>(sourceItems(sourceId));
        for(SourceItem hidden:state.hidden.values())if(hidden.sourceId.equals(sourceId))items.add(hidden);
        for(SourceItem deleted:state.deletedItems.values())if(deleted.sourceId.equals(sourceId))items.add(deleted);
        return Collections.unmodifiableList(items);
    }
    public SourceItem sourceItem(String sourceId,String itemKey) { checkOwner(); return active(state,sourceId)?state.items.get(key(sourceId,itemKey)):null; }
    public SourceVersionRecord sourceVersion(String recordKeyOrVersion) {
        checkOwner(); return resolveVersion(state,recordKeyOrVersion);
    }
    public List<SourceVersionRecord> sourceVersions(String sourceId,String itemKey) {
        checkOwner(); List<SourceVersionRecord> versions=new ArrayList<>();
        for(SourceVersionRecord version:state.versions.values()) if(version.sourceId.equals(sourceId)&&version.itemKey.equals(itemKey)) versions.add(version);
        return Collections.unmodifiableList(versions);
    }
    public List<SourceVersionRecord> sourceVersionsForSource(String sourceId){checkOwner();List<SourceVersionRecord> result=new ArrayList<>();for(SourceVersionRecord version:state.versions.values())if(version.sourceId.equals(sourceId))result.add(version);return immutable(result);}
    public PlayerController.SavedProgress sourceHistory(String recordKey) { checkOwner(); return state.history.get(recordKey); }

    /** First explicit source commit also appends its empty station, without selecting it. */
    public SourceRecord commitSource(SourceRecord source,List<SourceItem> initialItems) {
        return commitSource(source,initialItems,Collections.emptyList());
    }
    private static final class SourceCommit{final State next;final SourceRecord source;SourceCommit(State next,SourceRecord source){this.next=next;this.source=source;}}
    public SourceRecord commitSource(SourceRecord source,List<SourceItem> items,List<SourceVersionRecord> versions){SourceCommit prepared=sourceCommitCandidate(source,items,versions);if(prepared.next!=null)commit(prepared.next);return prepared.source;}
    public long preflightSourceCommit(SourceRecord source,List<SourceItem> items,List<SourceVersionRecord> versions){SourceCommit prepared=sourceCommitCandidate(source,items,versions);return prepared.next==null?0:mutationPeak(prepared.next);}
    private SourceCommit sourceCommitCandidate(SourceRecord source,List<SourceItem> initialItems,List<SourceVersionRecord> versions) {
        checkOwner(); Objects.requireNonNull(source); Objects.requireNonNull(initialItems);
        for(SourceRecord existing:state.sources.values()) {
            if(!active(state,existing.id)){if(existing.id.equals(source.id))throw new IllegalArgumentException("Retired source identity cannot reconnect");continue;}
            if(existing.id.equals(source.id)) { requireSameSource(existing,source); return new SourceCommit(null,existing); }
            if(sourceConflict(existing,source)) return new SourceCommit(null,existing);
        }
        if(sources().size()>=SourceLimits.MAX_SOURCES) throw new IllegalStateException("Можно подключить не более "+SourceLimits.MAX_SOURCES+" источников");
        if(lifecycleSchema(state.schema)&&state.sources.size()>=SourceLimits.MAX_SOURCE_RECORDS)throw new IllegalStateException("Достигнут общий предел "+SourceLimits.MAX_SOURCE_RECORDS+" записей источников, включая удалённые");
        State next=sourceState();
        if(next.stations.size()>=10000)throw new IllegalStateException("Station registry limit");
        if(next.stations.containsKey(source.stationId)) throw new IllegalArgumentException("Station already exists");
        next.sources.put(source.id,source);next.stations.put(source.stationId,source.name);if(lifecycleSchema(next.schema))next.lifecycle.put(source.id,SourceLifecycle.active());
        mergeItems(next,source.id,initialItems,versions);prunePayloads(next);return new SourceCommit(next,source);
    }
    public void mergeSourceItems(String sourceId,List<SourceItem> items,List<SourceVersionRecord> versions,
                                 String etag,String lastModified,long lastSuccess,String error) {
        checkOwner();
        mergeSourceItems(requiredSource(sourceId),items,versions,etag,lastModified,lastSuccess,error);
    }
    /** Confirmed redirect identities and feed contents publish atomically after the network reply. */
    public void mergeSourceItems(SourceRecord source,List<SourceItem> items,List<SourceVersionRecord> versions,String etag,String modified,long success,String error){commit(sourceMergeCandidate(source,items,versions,etag,modified,success,error));}
    public long preflightSourceMerge(SourceRecord source,List<SourceItem> items,List<SourceVersionRecord> versions,String etag,String modified,long success,String error){return mutationPeak(sourceMergeCandidate(source,items,versions,etag,modified,success,error));}
    private State sourceMergeCandidate(SourceRecord updated,List<SourceItem> items,List<SourceVersionRecord> versions,
                                 String etag,String lastModified,long lastSuccess,String error) {
        String sourceId=updated.id;
        checkOwner(); SourceRecord old=requiredSource(sourceId);
        if(!old.enabled) throw new IllegalStateException("Источник отключён");
        SourceRecord connection=checkedUpdate(old,updated);State next=sourceState();mergeItems(next,sourceId,items,versions);
        next.sources.put(sourceId,connection.withState(old.enabled,etag,lastModified,lastSuccess,error));
        prunePayloads(next);return next;
    }
    public SourceRecord updateSource(SourceRecord updated) {
        checkOwner();SourceRecord old=requiredSource(updated.id);SourceRecord changed=checkedUpdate(old,updated);
        State next=sourceState();next.sources.put(changed.id,changed);commit(next);return changed;
    }
    private SourceRecord checkedUpdate(SourceRecord old,SourceRecord updated) {
        if(!old.id.equals(updated.id)||!old.stationId.equals(updated.stationId)||!old.type.equals(updated.type)||!old.name.equals(updated.name))throw new IllegalArgumentException("Source identity/label is immutable");
        List<String> aliases=new ArrayList<>(old.aliases);
        for(String alias:Arrays.asList(old.key,updated.key))if(!aliases.contains(alias))aliases.add(alias);
        for(String alias:updated.aliases)if(!aliases.contains(alias))aliases.add(alias);
        SourceRecord changed=new SourceRecord(old.id,old.stationId,old.type,updated.key,old.name,updated.url,updated.adapterId,updated.adapterVersion,aliases,updated.enabled,updated.catalog,updated.etag,updated.lastModified,updated.lastSuccess,updated.lastError);
        for(SourceRecord other:state.sources.values())if(active(state,other.id)&&!other.id.equals(old.id)&&sourceConflict(other,changed))throw new IllegalArgumentException("Redirect belongs to another connected source");
        return changed;
    }
    private static boolean sourceConflict(SourceRecord a,SourceRecord b) {
        if(!a.type.equals(b.type))return false;
        if(a.key.equals(b.key)||a.aliases.contains(b.key)||b.aliases.contains(a.key))return true;
        for(String alias:a.aliases)if(b.aliases.contains(alias))return true;return false;
    }
    public void saveSourceVersion(SourceVersionRecord version){commit(sourceVersionCandidate(version));}
    public long preflightSourceVersion(SourceVersionRecord version){return mutationPeak(sourceVersionCandidate(version));}
    private State sourceVersionCandidate(SourceVersionRecord version) {
        checkOwner();requiredSource(version.sourceId);
        if(!state.items.containsKey(key(version.sourceId,version.itemKey))) throw new IllegalArgumentException("Unknown source item");
        State next=sourceState(); installVersion(next,version,false);
        SourceItem item=next.items.get(key(version.sourceId,version.itemKey));
        next.items.put(key(item.sourceId,item.itemKey),item.withLatest(version.recordKey).withHints(version.completeness,version.reason,version.method));
        prunePayloads(next);return next;
    }
    public SourceRecord setSourceEnabled(String id,boolean enabled) {
        checkOwner(); SourceRecord old=requiredSource(id); if(old.enabled==enabled)return old;
        SourceRecord changed=old.withState(enabled,old.etag,old.lastModified,old.lastSuccess,old.lastError);
        State next=sourceState();next.sources.put(id,changed);commit(next);return changed;
    }
    public SourceItem setSourceRead(String sourceId,String itemKey,boolean read) {
        checkOwner(); SourceItem old=requiredItem(sourceId,itemKey); if(old.read==read)return old;
        SourceItem changed=old.withMarks(read,old.everListened,old.lastViewedVersion);
        State next=sourceState();next.items.put(key(sourceId,itemKey),changed);commit(next);return changed;
    }
    public SourceItem setSourceViewed(String sourceId,String itemKey,String recordKey) {
        checkOwner();SourceItem old=requiredItem(sourceId,itemKey);SourceVersionRecord version=resolveVersion(state,recordKey);
        if(version==null||!version.sourceId.equals(sourceId)||!version.itemKey.equals(itemKey))throw new IllegalArgumentException("Viewed version identity");
        if(version.recordKey.equals(old.lastViewedVersion))return old;
        SourceItem changed=old.withMarks(old.read,old.everListened,version.recordKey);State next=sourceState();next.items.put(key(sourceId,itemKey),changed);commit(next);return changed;
    }
    public Material selectSourceVersion(String recordKeyOrVersion,boolean limitedConsent) {
        checkOwner(); SourceVersionRecord version=resolveVersion(state,recordKeyOrVersion);
        if(version==null)throw new IllegalArgumentException("Unknown source version");
        return selectSourceVersion(version,limitedConsent);
    }
    /** Promote the exact checked CAS payload and append its seed in the SAME durable snapshot. */
    private static final class Selection{final State next;final Material material;Selection(State next,Material material){this.next=next;this.material=material;}}
    public Material selectSourceVersion(SourceVersionRecord fullPayload,boolean limitedConsent){Selection selected=sourceSelection(fullPayload,limitedConsent);if(selected.next!=null)commit(selected.next);return selected.material;}
    public long preflightSourceSelection(SourceVersionRecord payload,boolean consent){Selection selected=sourceSelection(payload,consent);return selected.next==null?0:mutationPeak(selected.next);}
    private Selection sourceSelection(SourceVersionRecord fullPayload,boolean limitedConsent) {
        checkOwner();Objects.requireNonNull(fullPayload);
        SourceVersionRecord stored=state.versions.get(fullPayload.recordKey);
        if(stored==null||!stored.sameMetadata(fullPayload))throw new IllegalArgumentException("Source version metadata changed");
        if(!"full".equals(stored.completeness)&&!(limitedConsent&&("summary".equals(stored.completeness)||"unknown".equals(stored.completeness))))
            throw new IllegalArgumentException("Недостаточно текста или подтверждения");
        Article article=fullPayload.asArticle(); SourceRecord source=requiredSource(fullPayload.sourceId);
        SourceItem item=requiredItem(fullPayload.sourceId,fullPayload.itemKey);
        String selectedKey=key(source.stationId,article.id);Material chosen=state.materials.get(selectedKey);
        if(chosen!=null) {
            if(!fullPayload.recordKey.equals(chosen.sourceVersionKey)||!article.text.equals(chosen.text)||!article.sentenceStarts.equals(chosen.starts))
                throw new IllegalArgumentException("Selected material is immutable; remove it first");
            return new Selection(null,chosen);
        }
        int count=0;for(Material m:state.materials.values())if(m.sourceVersionKey!=null)count++;
        if(count>=SourceLimits.MAX_SOURCE_QUEUE)throw new IllegalStateException("Достигнут предел 100 текстов источников в эфире");
        State next=sourceState();installVersion(next,fullPayload,true);
        PlayerController.SavedProgress history=next.history.get(fullPayload.recordKey);
        PlayerController.SavedProgress seed=history==null?new PlayerController.SavedProgress(source.stationId,article.id,article.version,0,false,Collections.emptyList(),item.read,false):withRead(history,item.read);
        PlayerController.validateSavedProgress(source.stationId,article,seed);
        Material material=new Material(source.stationId,source.name,fullPayload,Math.addExact(next.sequence,1),seed);
        next.sequence=material.sequence;next.materials.put(selectedKey,material);
        prunePayloads(next);return new Selection(next,material);
    }
    /** Null archive retains an existing known history; it never manufactures coverage. */
    public Material removeSourceMaterial(String stationId,String articleId,PlayerController.SavedProgress lastPersisted) {
        checkOwner();String selectedKey=key(stationId,articleId);Material material=state.materials.get(selectedKey);
        if(material==null)return null;
        if(material.sourceVersionKey==null)throw new IllegalArgumentException("Not a source material");
        State next=sourceState();
        if(lastPersisted!=null)archive(next,next.versions.get(material.sourceVersionKey),lastPersisted);
        if(!next.tombstones.containsKey(selectedKey)&&next.tombstones.size()>=200000)throw new IllegalStateException("Queue tombstone limit");
        next.materials.remove(selectedKey);next.tombstones.put(selectedKey,material.sequence);
        prunePayloads(next);commit(next);return material;
    }
    /** Caller supplies only the last successfully persisted radio state, never a live snapshot. */
    public void captureSourceHistory(PlayerController.SavedState lastSuccessful) {
        checkOwner();if(lastSuccessful==null)return;State next=sourceState();boolean changed=false;
        for(SourceVersionRecord version:state.versions.values()) {
            SourceRecord source=state.sources.get(version.sourceId);
            if(!active(state,version.sourceId)||state.deletedItems.containsKey(key(version.sourceId,version.itemKey)))continue;
            PlayerController.SavedProgress p=findProgress(lastSuccessful.progress,source.stationId,version.articleId,version.versionKey);
            if(p==null)continue;
            validateProgress(source.stationId,version,p);
            if(!p.passEnded||!fullCoverage(version,p))continue;
            if(!sameProgress(p,next.history.get(version.recordKey))) {archive(next,version,p);changed=true;}
        }
        if(changed){prunePayloads(next);commit(next);}
    }
    public int hideCompletedSourceItems(String sourceId) {
        checkOwner();requiredSource(sourceId);State next=sourceState();int hidden=0;
        for(SourceItem item:sourceItems(sourceId)) {
            if(!(item.read||item.everListened)||itemProtected(next,item))continue;
            if(hiddenCount(next,sourceId)+deletedCount(next,sourceId)>=SourceLimits.MAX_HIDE_IDS_PER_SOURCE)throw new IllegalStateException("Source hide history limit");
            next.hidden.put(key(sourceId,item.itemKey),item);next.items.remove(key(sourceId,item.itemKey));hidden++;
            // Cleanup is explicit. Full history is no longer re-selectable once its item is hidden;
            // sticky identity/read/listened/version marks remain in the hide tombstone.
            for(SourceVersionRecord v:new ArrayList<>(next.versions.values()))if(v.sourceId.equals(sourceId)&&v.itemKey.equals(item.itemKey))next.history.remove(v.recordKey);
        }
        if(hidden>0){prunePayloads(next);commit(next);}return hidden;
    }
    public Note beginRecordingLinkedSource(String title,String recordKey,long createdAt) {
        checkOwner();SourceVersionRecord version=resolveVersion(state,recordKey);if(version==null||version.spokenText==null)throw new IllegalArgumentException("Source payload is unavailable");
        SourceRecord source=requiredSource(version.sourceId);
        if(state.notes.size()>=MAX_NOTES)throw new IllegalStateException("Достигнут предел 100 заметок");
        limited(title,MAX_TITLE,"title",false);if(createdAt<0)throw new IllegalArgumentException("createdAt");
        Note note=new Note(UUID.randomUUID().toString(),createdAt,title,"",0,source.stationId,version.articleId,version.versionKey,0,0,false,RECORDING,NOT_REQUESTED,null).withSourceLink(version.recordKey);
        State next=sourceState();next.notes.put(note.id,note);prunePayloads(next);commit(next);return note;
    }
    public int preflightDeleteSource(String id,PlayerController.SavedState lastPersisted){checkOwner();if(isSourceRetired(id))return 0;State next=deletionCandidate(id,null,lastPersisted);return deletionBytes(next);}
    public int preflightDeleteSourceItem(String id,String item,PlayerController.SavedState lastPersisted){checkOwner();if(isSourceRetired(id)||isItemDeleted(id,item))return 0;State next=deletionCandidate(id,Objects.requireNonNull(item),lastPersisted);return deletionBytes(next);}
    public boolean deleteSource(String id,PlayerController.SavedState lastPersisted){checkOwner();if(isSourceRetired(id))return false;commit(deletionCandidate(id,null,lastPersisted));return true;}
    public boolean deleteSourceItem(String id,String item,PlayerController.SavedState lastPersisted){checkOwner();if(isSourceRetired(id)||isItemDeleted(id,item))return false;commit(deletionCandidate(id,Objects.requireNonNull(item),lastPersisted));return true;}

    /** A dry, bounded target tied to this Book and its exact durable baseline. */
    public static final class DeletionChange {
        public final String kind,expectedRevisionHash;
        public final int targetBytes;
        public final boolean changed,affectsCurrent;
        public final List<Material> removedMaterials;
        public final List<SourceVersionRecord> sourceBodies;
        public final java.util.Set<String> sourceIds;
        public final Material replacement;
        private final NoteBook book;
        private final State target;
        private final byte[] baseline;
        private final long revision,epoch;
        private boolean applied;
        private DeletionChange(NoteBook book,String kind,State target,List<Material> removed,
                               List<SourceVersionRecord> bodies,java.util.Set<String> sources,
                               Material replacement,String expectedHash,PlayerController.SavedState persisted,boolean changed) {
            this.book=book;this.kind=kind;this.target=target;this.replacement=replacement;
            expectedRevisionHash=expectedHash;this.changed=changed;
            removedMaterials=Collections.unmodifiableList(new ArrayList<>(removed));
            sourceBodies=Collections.unmodifiableList(new ArrayList<>(bodies));
            sourceIds=Collections.unmodifiableSet(new java.util.LinkedHashSet<>(sources));
            revision=book.revision;epoch=book.authorityEpoch;
            baseline=book.state.schema==1?(book.legacyBytes==null?null:book.legacyBytes.clone()):encode(book.state);
            targetBytes=book.preparedBytes(target);
            String station=persisted==null?null:persisted.currentStationId;
            String article=station==null?null:persisted.stationArticleIds.get(station);
            if(article!=null&&article.startsWith(SELECTED_PREFIX))article=selectedArticle(article);
            affectsCurrent=affects(station,article);
        }
        public boolean affects(String stationId,String articleId) {
            for(Material material:removedMaterials)if(material.stationId.equals(stationId)&&material.articleId.equals(articleId))return true;
            return false;
        }
    }

    /** Caller preserves sourceBodies in checked local CAS before applying this target. */
    public DeletionChange prepareClearAir(PlayerController.SavedState persisted) {
        checkOwner();requirePreparedAuthority();State next=new State(state);
        List<Material> removed=new ArrayList<>(state.materials.values());
        List<SourceVersionRecord> bodies=new ArrayList<>();java.util.Set<String> sources=new java.util.LinkedHashSet<>();
        for(Material material:removed) {
            if(material.sourceVersionKey!=null) {
                SourceVersionRecord version=next.versions.get(material.sourceVersionKey);bodies.add(version);sources.add(version.sourceId);
                PlayerController.SavedProgress progress=persisted==null?null:findProgress(persisted.progress,material.stationId,material.articleId,material.version);
                if(progress!=null){validateProgress(material.stationId,version,progress);next.history.put(version.recordKey,progress);}
            }
            removePreparedMaterial(next,material);
        }
        if(sourceSchema(next.schema))prunePayloads(next);
        return new DeletionChange(this,"clear-air",next,removed,bodies,sources,null,null,persisted,!removed.isEmpty());
    }

    /** Delete ordinary news globally, retaining connection settings and only exact Note context. */
    public DeletionChange prepareDeleteAllNews(PlayerController.SavedState persisted) {
        checkOwner();requirePreparedAuthority();State next=new State(state);
        java.util.Set<String> sources=new java.util.LinkedHashSet<>();
        for(SourceRecord source:state.sources.values())if(active(state,source.id))sources.add(source.id);
        boolean changed=false;for(SourceItem item:state.items.values())if(sources.contains(item.sourceId)){changed=true;break;}
        if(!changed)for(Material material:state.materials.values())if(material.sourceVersionKey!=null&&sources.contains(state.versions.get(material.sourceVersionKey).sourceId)){changed=true;break;}
        java.util.Set<String> linked=new java.util.HashSet<>();for(Note note:state.notes.values())if(note.linkedSourceVersionKey!=null)linked.add(note.linkedSourceVersionKey);
        if(!changed)for(SourceVersionRecord version:state.versions.values())if(sources.contains(version.sourceId)&&!linked.contains(version.recordKey)){changed=true;break;}
        List<Material> removed=new ArrayList<>();List<SourceVersionRecord> bodies=new ArrayList<>();
        if(changed) {
            if(format45Enabled||next.schema==4)upgradeState45(next);else next.schema=Math.max(3,next.schema);if(state.schema==2)for(String id:state.sources.keySet())next.lifecycle.put(id,SourceLifecycle.active());
            next.sequence=Math.addExact(next.sequence,1);
            for(String id:sources){SourceLifecycle life=next.lifecycle.get(id);next.lifecycle.put(id,new SourceLifecycle(SourceLifecycle.ACTIVE,Math.addExact(life.generation,1),next.sequence));}
            for(Material material:new ArrayList<>(next.materials.values()))if(material.sourceVersionKey!=null) {
                SourceVersionRecord version=next.versions.get(material.sourceVersionKey);if(!sources.contains(version.sourceId))continue;
                PlayerController.SavedProgress progress=persisted==null?null:findProgress(persisted.progress,material.stationId,material.articleId,material.version);
                if(linked.contains(version.recordKey)&&progress==null)throw new IllegalStateException("Linked selected progress is unconfirmed");
                if(progress!=null)validateProgress(material.stationId,version,progress);
                if(linked.contains(version.recordKey)&&progress!=null)next.history.put(version.recordKey,progress);
                removed.add(material);bodies.add(version);removePreparedMaterial(next,material);
            }
            for(SourceVersionRecord version:new ArrayList<>(next.versions.values()))if(sources.contains(version.sourceId)) {
                if(!linked.contains(version.recordKey)){next.history.remove(version.recordKey);next.versions.remove(version.recordKey);}
                else if(persisted!=null){PlayerController.SavedProgress progress=findProgress(persisted.progress,next.sources.get(version.sourceId).stationId,version.articleId,version.versionKey);if(progress!=null){validateProgress(next.sources.get(version.sourceId).stationId,version,progress);next.history.put(version.recordKey,progress);}}
            }
            for(SourceItem item:new ArrayList<>(next.items.values()))if(sources.contains(item.sourceId)) {
                boolean noteLinked=false;for(SourceVersionRecord version:next.versions.values())if(version.sourceId.equals(item.sourceId)&&version.itemKey.equals(item.itemKey)&&linked.contains(version.recordKey)){noteLinked=true;break;}
                String identity=key(item.sourceId,item.itemKey);next.items.remove(identity);next.deletedItems.put(identity,deleteMetadata(item,linked,noteLinked));
            }
            for(Map<String,SourceItem> table:Arrays.asList(next.hidden,next.deletedItems))for(SourceItem item:new ArrayList<>(table.values()))if(sources.contains(item.sourceId))table.put(key(item.sourceId,item.itemKey),deleteMetadata(item,linked,true));
            prunePayloads(next);
        }
        return new DeletionChange(this,"delete-all-news",next,removed,bodies,sources,null,null,persisted,changed);
    }

    /** Replace one selected saved Note revision in one snapshot; the Note itself is untouched. */
    public DeletionChange prepareReplaceNoteInAir(String id,long expectedRevision,PlayerController.SavedState persisted) {
        checkOwner();requirePreparedAuthority();Note note=required(id);
        if(note.revision!=expectedRevision)throw new IllegalStateException("Saved Note revision changed");
        if(note.text.trim().isEmpty())throw new IllegalArgumentException("Пустой текст нельзя добавить в эфир");
        Material old=null;for(Material material:state.materials.values())if(id.equals(material.noteId)){old=material;break;}
        if(old==null)throw new IllegalArgumentException("Note is not selected in air");
        Article article=new Article("note:"+id+":r"+note.revision,"note-revision:"+note.revision,note.title,note.text);
        String hash=SourceLimits.snapshotVersion(id,Long.toString(note.revision),note.title,note.text);
        State next=new State(state);boolean changed=!old.articleId.equals(article.id)||!old.version.equals(article.version)||!old.title.equals(article.title)||!old.text.equals(article.text);
        Material replacement=old;List<Material> removed=new ArrayList<>();
        if(changed) {
            removed.add(old);removePreparedMaterial(next,old);next.sequence=Math.addExact(next.sequence,1);
            replacement=new Material(old.stationId,old.stationName,article.id,article.version,article.title,article.text,next.sequence,id,article.sentenceStarts);
            // The wire order must remain ascending: the fresh maximum sequence belongs last.
            next.materials.put(key(replacement.stationId,replacement.articleId),replacement);
        }
        return new DeletionChange(this,"replace-note",next,removed,Collections.emptyList(),Collections.emptySet(),replacement,hash,persisted,changed);
    }

    /** A single target removes a Note and its selected snapshot, retaining all other owners. */
    public DeletionChange prepareDeleteNote(String id,long expectedRevision,PlayerController.SavedState persisted) {
        checkOwner();requirePreparedAuthority();Note note=required(id);
        if(note.revision!=expectedRevision)throw new IllegalStateException("Saved Note revision changed");
        if(RECORDING.equals(note.recordingStatus))throw new IllegalStateException("Сначала завершите запись и сохранение оригинала");
        State next=new State(state);List<Material> removed=new ArrayList<>();
        for(Material material:new ArrayList<>(next.materials.values()))if(id.equals(material.noteId)){removed.add(material);removePreparedMaterial(next,material);}
        next.notes.remove(id);
        // Retired/deleted source versions can only survive as a live Note's exact context.
        if(lifecycleSchema(next.schema)) {
            java.util.Set<String> discarded=new java.util.HashSet<>();
            for(SourceVersionRecord version:new ArrayList<>(next.versions.values()))
                if((!active(next,version.sourceId)||next.deletedItems.containsKey(key(version.sourceId,version.itemKey)))&&!noteLinked(next,version.recordKey)){
                    discarded.add(version.recordKey);next.versions.remove(version.recordKey);next.history.remove(version.recordKey);
                }
            for(SourceItem item:new ArrayList<>(next.items.values()))if(!active(next,item.sourceId)){
                boolean linked=false;for(SourceVersionRecord version:next.versions.values())if(version.sourceId.equals(item.sourceId)&&version.itemKey.equals(item.itemKey)&&noteLinked(next,version.recordKey)){linked=true;break;}
                if(!linked)next.items.remove(key(item.sourceId,item.itemKey));
            }
            for(Map<String,SourceItem> table:Arrays.asList(next.items,next.hidden,next.deletedItems))for(SourceItem item:new ArrayList<>(table.values()))
                if(discarded.contains(item.latestVersionKey)||discarded.contains(item.lastViewedVersion))table.put(key(item.sourceId,item.itemKey),deleteMetadata(item,next.versions.keySet(),true));
        }
        return new DeletionChange(this,"delete-note",next,removed,Collections.emptyList(),Collections.emptySet(),null,null,persisted,true);
    }
    /** File staging/recovery must consult this exact known disk image, never a stale catalog. */
    public void requireCurrentAuthority(){checkOwner();requirePreparedAuthority();}

    /** Recheck the exact prepared authority before the sole durable commit. */
    public boolean applyDeletion(DeletionChange change) {
        checkOwner();Objects.requireNonNull(change,"change");if(change.book!=this)throw new IllegalArgumentException("Prepared change belongs to another Book");
        if(storageUncertain)throw new IllegalStateException("Storage outcome needs recovery");
        if(change.applied){requirePreparedAuthority();byte[] current=readMigrationAuthority();if(Arrays.equals(current,encode(change.target)))return false;throw new IllegalStateException("Prepared target is no longer authoritative");}
        if(change.revision!=revision||change.epoch!=authorityEpoch)throw new IllegalStateException("Prepared Book revision changed");
        byte[] current=readMigrationAuthority();if(!Arrays.equals(current,change.baseline))preparedAuthorityChanged(current);
        preparedBytes(change.target);if(!change.changed)return false;
        commit(change.target);change.applied=true;return true;
    }
    public static final class CompletedCleanup {
        public final DeletionChange change;
        public final int removed,contextKept,protectedKept;
        public final List<SourceVersionRecord> garbage;
        private final String current;private final long radioEpoch;
        private CompletedCleanup(DeletionChange change,int removed,int context,int protectedCount,List<SourceVersionRecord> garbage,String current,long epoch){
            this.change=change;this.removed=removed;contextKept=context;protectedKept=protectedCount;
            this.garbage=Collections.unmodifiableList(new ArrayList<>(garbage));this.current=current;radioEpoch=epoch;
        }
    }
    /** Completed source-news subset only; no explicit-delete side effects or context snapshots. */
    public CompletedCleanup prepareCompletedCleanup(java.util.Set<String> successfulSources,PlayerController.SavedState persisted,String current,long radioEpoch){
        checkOwner();requirePreparedAuthority();
        if(persisted==null)throw new IllegalStateException("Persisted progress is unconfirmed");
        State next=new State(state);List<Material> removed=new ArrayList<>();List<SourceVersionRecord> garbage=new ArrayList<>();
        int count=0,context=0,protectedCount=0;java.util.Set<String> affected=new java.util.LinkedHashSet<>();
        Map<String,SourceItem> items=new LinkedHashMap<>(state.hidden);items.putAll(state.items);
        for(SourceItem item:items.values()){
            if(!successfulSources.contains(item.sourceId)||!active(state,item.sourceId))continue;
            SourceRecord source=state.sources.get(item.sourceId);Material selected=state.materials.get(key(source.stationId,item.articleId));
            List<SourceVersionRecord> versions=new ArrayList<>();for(SourceVersionRecord v:state.versions.values())if(v.sourceId.equals(item.sourceId)&&v.itemKey.equals(item.itemKey))versions.add(v);
            SourceVersionRecord chosen=selected!=null&&selected.sourceVersionKey!=null?state.versions.get(selected.sourceVersionKey):item.latestVersionKey==null?null:resolveVersion(state,item.latestVersionKey);
            if(chosen==null)continue;
            PlayerController.SavedProgress p=selected==null?state.history.get(chosen.recordKey):findProgress(persisted.progress,source.stationId,chosen.articleId,chosen.versionKey);
            if(selected!=null&&p==null)throw new IllegalStateException("Selected progress is unconfirmed");
            if(p!=null)validateProgress(source.stationId,chosen,p);
            boolean completed=item.read||(p!=null&&p.passEnded&&fullCoverage(chosen,p));
            if(!completed)continue;
            boolean protectedVersion=false,linked=false;
            for(SourceVersionRecord v:versions){
                if(Objects.equals(current,PlayerController.progressKey(source.stationId,v.articleId,v.versionKey)))protectedVersion=true;
                PlayerController.SavedProgress prior=selected!=null&&v.recordKey.equals(selected.sourceVersionKey)?p:state.history.get(v.recordKey);
                if(prior!=null){validateProgress(source.stationId,v,prior);if(partial(v,prior))protectedVersion=true;}
                if(!item.read&&(prior==null||!prior.passEnded||!fullCoverage(v,prior)))protectedVersion=true;
                for(Note note:state.notes.values())if(v.recordKey.equals(note.linkedSourceVersionKey))linked=true;
            }
            if(protectedVersion){protectedCount++;continue;}
            if(linked){context++;continue;}
            if(hiddenCount(next,source.id)+deletedCount(next,source.id)>=SourceLimits.MAX_HIDE_IDS_PER_SOURCE)throw new IllegalStateException("Source deletion history limit");
            if(selected!=null){if(selected.noteId!=null||selected.sourceVersionKey==null){protectedCount++;continue;}removePreparedMaterial(next,selected);removed.add(selected);}
            for(SourceVersionRecord v:versions){next.versions.remove(v.recordKey);next.history.remove(v.recordKey);garbage.add(v);}
            String identity=key(item.sourceId,item.itemKey);next.items.remove(identity);next.hidden.remove(identity);
            next.deletedItems.put(identity,deleteMetadata(item,Collections.emptySet(),true));affected.add(item.sourceId);count++;
        }
        if(count>0)prunePayloads(next);
        DeletionChange change=new DeletionChange(this,"completed-news",next,removed,Collections.emptyList(),affected,null,null,persisted,count>0);
        return new CompletedCleanup(change,count,context,protectedCount,garbage,current,radioEpoch);
    }
    public boolean applyCompletedCleanup(CompletedCleanup plan,String current,long radioEpoch){
        checkOwner();if(!Objects.equals(plan.current,current)||plan.radioEpoch!=radioEpoch)throw new IllegalStateException("Current/session changed; cleanup cancelled");
        return applyDeletion(plan.change);
    }
    public int preflightClearAir(PlayerController.SavedState persisted){return prepareClearAir(persisted).targetBytes;}
    public boolean clearAir(PlayerController.SavedState persisted){return applyDeletion(prepareClearAir(persisted));}
    public int preflightDeleteAllNews(PlayerController.SavedState persisted){return prepareDeleteAllNews(persisted).targetBytes;}
    public boolean deleteAllNews(PlayerController.SavedState persisted){return applyDeletion(prepareDeleteAllNews(persisted));}
    public int preflightUpdateNoteInAir(String id,long expectedRevision,PlayerController.SavedState persisted){return prepareReplaceNoteInAir(id,expectedRevision,persisted).targetBytes;}
    public Material updateNoteInAir(String id,long expectedRevision,PlayerController.SavedState persisted){DeletionChange change=prepareReplaceNoteInAir(id,expectedRevision,persisted);applyDeletion(change);return change.replacement;}
    private void requirePreparedAuthority(){if(storageUncertain)throw new IllegalStateException("Storage outcome needs recovery");byte[] current=readMigrationAuthority();byte[] expected=state.schema==1?legacyBytes:encode(state);if(!Arrays.equals(current,expected))preparedAuthorityChanged(current);}
    private void preparedAuthorityChanged(byte[] current){
        try{State actual=current==null?null:decode(current);if(actual==null||actual.schema<state.schema){markUncertain();throw new IllegalStateException("Prepared authority cannot downgrade or disappear");}}
        catch(RuntimeException invalid){markUncertain();throw new IllegalStateException("Prepared authority is uncertain",invalid);}
        changedAuthority(current);
    }
    private long mutationPeak(State next){try{long bytes=Math.addExact(preparedBytes(next),reservedHistoryBytes(next));if(state.schema<4&&next.schema==4)bytes=Math.addExact(bytes,MigrationCapsuleStore.format4CapsuleBytes(state.schema==1?legacyBytes:encode(state)));return bytes;}catch(IOException failure){throw new IllegalStateException("Source storage preflight",failure);}}
    private int format4ReferenceBytes(){return state.schema==1&&legacyBytes==null?385:441;}
    private int preparedBytes(State next){if(sourceSchema(next.schema))try{validateSources(next);}catch(IOException invalid){throw new IllegalArgumentException("Invalid prepared transaction",invalid);}int bytes=encode(next).length;if((state.schema==2&&next.schema==3&&next.upgrade==null)||(state.schema<4&&next.schema==4&&next.format4==null)){bytes=Math.addExact(bytes,next.schema==4?format4ReferenceBytes():1024);if(bytes>MAX_METADATA_BYTES)throw new IllegalStateException("Schema3 descriptor exceeds book limit");}return bytes;}
    private static void removePreparedMaterial(State next,Material material){String identity=key(material.stationId,material.articleId);if(!next.tombstones.containsKey(identity)&&next.tombstones.size()>=200000)throw new IllegalStateException("Queue tombstone limit");next.materials.remove(identity);next.tombstones.put(identity,material.sequence);}
    private int deletionBytes(State next){if(storageUncertain)throw new IllegalStateException("Storage outcome needs recovery");try{validateSources(next);}catch(IOException invalid){throw new IllegalArgumentException("Invalid deletion",invalid);}int size=encode(next).length;if((state.schema==2&&next.schema==3&&next.upgrade==null)||(state.schema<4&&next.schema==4&&next.format4==null)){size=Math.addExact(size,next.schema==4?format4ReferenceBytes():1024);if(size>MAX_METADATA_BYTES)throw new IllegalStateException("Schema3 descriptor exceeds book limit");}return size;}
    private State deletionCandidate(String id,String itemKey,PlayerController.SavedState lastPersisted){
        requiredSource(id);if(itemKey!=null)requiredItem(id,itemKey);
        State next=sourceState();next.schema=Math.max(3,next.schema);
        if(state.schema==2)for(String source:state.sources.keySet())next.lifecycle.put(source,SourceLifecycle.active());
        next.sequence=Math.addExact(next.sequence,1);SourceLifecycle old=next.lifecycle.get(id);
        next.lifecycle.put(id,new SourceLifecycle(itemKey==null?SourceLifecycle.RETIRED:SourceLifecycle.ACTIVE,Math.addExact(old.generation,1),next.sequence));
        java.util.Set<String> linked=new java.util.HashSet<>();for(Note note:next.notes.values())if(note.linkedSourceVersionKey!=null)linked.add(note.linkedSourceVersionKey);
        for(Material material:new ArrayList<>(next.materials.values()))if(material.sourceVersionKey!=null){
            SourceVersionRecord version=next.versions.get(material.sourceVersionKey);if(!deleteTarget(version,id,itemKey))continue;
            PlayerController.SavedProgress persisted=lastPersisted==null?null:findProgress(lastPersisted.progress,material.stationId,material.articleId,material.version);
            if(linked.contains(version.recordKey)&&persisted==null)throw new IllegalStateException("Linked selected progress is unconfirmed");
            if(persisted!=null)validateProgress(material.stationId,version,persisted);
            if(linked.contains(version.recordKey)&&persisted!=null)next.history.put(version.recordKey,persisted);
            String selectedKey=key(material.stationId,material.articleId);
            if(!next.tombstones.containsKey(selectedKey)&&next.tombstones.size()>=200000)throw new IllegalStateException("Queue tombstone limit");
            next.materials.remove(selectedKey);next.tombstones.put(selectedKey,material.sequence);
        }
        for(SourceVersionRecord version:new ArrayList<>(next.versions.values()))if(deleteTarget(version,id,itemKey)){
            if(!linked.contains(version.recordKey)){next.history.remove(version.recordKey);next.versions.remove(version.recordKey);}
            else if(lastPersisted!=null){PlayerController.SavedProgress persisted=findProgress(lastPersisted.progress,next.sources.get(id).stationId,version.articleId,version.versionKey);if(persisted!=null){validateProgress(next.sources.get(id).stationId,version,persisted);next.history.put(version.recordKey,persisted);}}
        }
        for(SourceItem item:new ArrayList<>(next.items.values()))if(item.sourceId.equals(id)&&(itemKey==null||item.itemKey.equals(itemKey))){
            boolean noteLinked=false;for(SourceVersionRecord v:next.versions.values())if(v.sourceId.equals(id)&&v.itemKey.equals(item.itemKey)&&linked.contains(v.recordKey)){noteLinked=true;break;}
            SourceItem retained=deleteMetadata(item,linked,noteLinked);String k=key(id,item.itemKey);
            if(itemKey!=null){next.items.remove(k);next.deletedItems.put(k,retained);}else if(noteLinked)next.items.put(k,retained);else next.items.remove(k);
        }
        // Existing suppression identities survive source retirement; clear references to removed versions.
        if(itemKey==null)for(Map<String,SourceItem> table:Arrays.asList(next.hidden,next.deletedItems))for(SourceItem item:new ArrayList<>(table.values()))if(item.sourceId.equals(id))table.put(key(id,item.itemKey),deleteMetadata(item,linked,true));
        prunePayloads(next);deletionBytes(next);return next;
    }
    private static boolean deleteTarget(SourceVersionRecord version,String source,String item){return version.sourceId.equals(source)&&(item==null||version.itemKey.equals(item));}
    private static SourceItem deleteMetadata(SourceItem item,java.util.Set<String> linked,boolean keepMarks){return new SourceItem(item.sourceId,item.itemKey,item.articleId,item.providerId,item.url,item.title,item.aliases,item.publishedAt,item.updatedAt,item.firstSeenAt,linked.contains(item.latestVersionKey)?item.latestVersionKey:null,keepMarks&&item.read,keepMarks&&item.everListened,linked.contains(item.lastViewedVersion)?item.lastViewedVersion:null,item.publishedRaw,item.updatedRaw,item.statusHint,item.reasonHint,item.methodHint);}
    private State sourceState(){State next=new State(state);if(format45Enabled||next.schema==4)upgradeState45(next);else if(next.schema!=3)next.schema=2;return next;}
    private static void upgradeState45(State next){if(next.schema<3)for(String id:next.sources.keySet())next.lifecycle.put(id,SourceLifecycle.active());next.schema=4;}

    private SourceRecord requiredSource(String id){SourceRecord source=state.sources.get(id);if(source==null||!active(state,id))throw new IllegalArgumentException("Unknown or retired source");return source;}
    private SourceItem requiredItem(String id,String item){SourceItem value=state.items.get(key(id,item));if(value==null||!active(state,id)||state.deletedItems.containsKey(key(id,item)))throw new IllegalArgumentException("Unknown or deleted source item");return value;}
    private static void requireSameSource(SourceRecord old,SourceRecord next){
        if(!old.stationId.equals(next.stationId)||!old.type.equals(next.type)||!old.key.equals(next.key)||!old.name.equals(next.name)||!old.url.equals(next.url)||!old.adapterId.equals(next.adapterId))throw new IllegalArgumentException("Source identity is immutable");
    }
    private static SourceVersionRecord resolveVersion(State state,String recordKey) {
        SourceVersionRecord exact=state.versions.get(recordKey);if(exact!=null)return exact;
        SourceVersionRecord found=null;for(SourceVersionRecord v:state.versions.values())if(v.versionKey.equals(recordKey)){
            if(found!=null)throw new IllegalArgumentException("Ambiguous version hash; use recordKey");found=v;
        }return found;
    }
    private static void mergeItems(State next,String sourceId,List<SourceItem> incoming,List<SourceVersionRecord> versions) {
        if(incoming.size()>SourceLimits.MAX_PARSED_ITEMS)throw new IllegalArgumentException("Feed item limit");int added=0;
        java.util.Set<String> seen=new java.util.HashSet<>();
        for(SourceItem item:incoming) {
            if(!sourceId.equals(item.sourceId)||!seen.add(item.itemKey))throw new IllegalArgumentException("Feed identity");
            String itemKey=key(sourceId,item.itemKey);if(next.hidden.containsKey(itemKey)||next.deletedItems.containsKey(itemKey))continue;
            SourceItem old=next.items.get(itemKey);
            if(old==null){if(++added>SourceLimits.MAX_NEW_ITEMS)throw new IllegalStateException("New feed item limit");next.items.put(itemKey,new SourceItem(sourceId,item.itemKey,item.articleId,item.providerId,item.url,item.title,item.aliases,item.publishedAt,item.updatedAt,item.firstSeenAt,null,false,false,null,item.publishedRaw,item.updatedRaw,item.statusHint,item.reasonHint,item.methodHint));}
            else {
                if(!old.articleId.equals(item.articleId))throw new IllegalArgumentException("Stable article ID changed");
                List<String> aliases=new ArrayList<>(old.aliases);for(String alias:item.aliases)if(!aliases.contains(alias))aliases.add(alias);
                next.items.put(itemKey,new SourceItem(sourceId,item.itemKey,old.articleId,item.providerId,item.url,item.title,aliases,item.publishedAt,item.updatedAt,old.firstSeenAt,old.latestVersionKey,old.read,old.everListened,old.lastViewedVersion,item.publishedRaw,item.updatedRaw,item.statusHint,item.reasonHint,item.methodHint));
            }
        }
        int active=0;for(SourceItem item:next.items.values())if(sourceId.equals(item.sourceId))active++;
        if(active>SourceLimits.MAX_ITEMS_PER_SOURCE)throw new IllegalStateException("Active source item limit");
        for(SourceVersionRecord version:versions) {
            if(!sourceId.equals(version.sourceId))throw new IllegalArgumentException("Version source");
            if(next.hidden.containsKey(key(sourceId,version.itemKey))||next.deletedItems.containsKey(key(sourceId,version.itemKey)))continue;
            installVersion(next,version,false);
            SourceItem item=next.items.get(key(sourceId,version.itemKey));next.items.put(key(sourceId,version.itemKey),item.withLatest(version.recordKey).withHints(version.completeness,version.reason,version.method));
        }
    }
    private static void installVersion(State next,SourceVersionRecord version,boolean protect) {
        if(version.publication!=null)upgradeState45(next);
        SourceItem item=next.items.get(key(version.sourceId,version.itemKey));
        if(item==null||!item.articleId.equals(version.articleId))throw new IllegalArgumentException("Version item identity");
        SourceVersionRecord old=next.versions.get(version.recordKey);
        if(old!=null&&!old.sameMetadata(version))throw new IllegalArgumentException("Immutable version metadata changed");
        if(old!=null&&old.spokenText!=null&&version.spokenText!=null&&!old.spokenText.equals(version.spokenText))throw new IllegalArgumentException("Immutable payload changed");
        next.versions.put(version.recordKey,protect?version:(old!=null&&old.spokenText!=null?old:version.withPayload(null)));
    }
    private static boolean fullCoverage(SourceVersionRecord version,PlayerController.SavedProgress p){return p.started&&p.completedSentences.size()==version.sentenceStarts.size();}
    private static boolean partial(SourceVersionRecord version,PlayerController.SavedProgress p){return p!=null&&((!p.passEnded&&(p.started||p.sentenceIndex>0))||(p.started&&!fullCoverage(version,p)));}
    private static boolean versionProtected(State state,SourceVersionRecord version){
        for(Material m:state.materials.values())if(version.recordKey.equals(m.sourceVersionKey))return true;
        for(Note n:state.notes.values())if(version.recordKey.equals(n.linkedSourceVersionKey))return true;
        return partial(version,state.history.get(version.recordKey));
    }
    private static boolean itemProtected(State state,SourceItem item){
        for(SourceVersionRecord v:state.versions.values())if(v.sourceId.equals(item.sourceId)&&v.itemKey.equals(item.itemKey)&&versionProtected(state,v))return true;
        return false;
    }
    private static int deletedCount(State value,String sourceId){int count=0;for(SourceItem item:value.deletedItems.values())if(item.sourceId.equals(sourceId))count++;return count;}
    private static int hiddenCount(State state,String sourceId){int count=0;for(SourceItem item:state.hidden.values())if(item.sourceId.equals(sourceId))count++;return count;}
    private static void prunePayloads(State next) {
        int protectedCount=0;List<String> obsolete=new ArrayList<>();
        for(SourceVersionRecord v:new ArrayList<>(next.versions.values())) {
            boolean protectedVersion=versionProtected(next,v);
            if(protectedVersion){if(v.spokenText==null)throw new IllegalStateException("Protected source payload is missing");protectedCount++;}
            else {
                next.versions.put(v.recordKey,v.withPayload(null));SourceItem item=next.items.get(key(v.sourceId,v.itemKey));
                if(!next.history.containsKey(v.recordKey)&&(item==null||!v.recordKey.equals(item.latestVersionKey)))obsolete.add(v.recordKey);
            }
        }
        for(String recordKey:obsolete)next.versions.remove(recordKey);
        if(protectedCount>SourceLimits.MAX_PROTECTED_VERSIONS)throw new IllegalStateException("Достигнут предел 100 защищённых версий");
    }
    private static void archive(State next,SourceVersionRecord version,PlayerController.SavedProgress progress){
        SourceRecord source=next.sources.get(version.sourceId);validateProgress(source.stationId,version,progress);
        next.history.put(version.recordKey,progress);String itemKey=key(version.sourceId,version.itemKey);
        SourceItem item=next.items.get(itemKey);if(item!=null)next.items.put(itemKey,item.withMarks(item.read,item.everListened||fullCoverage(version,progress),version.recordKey));
    }
    private static void validateProgress(String station,SourceVersionRecord version,PlayerController.SavedProgress p){
        if(version.spokenText!=null){PlayerController.validateSavedProgress(station,version.asArticle(),p);return;}
        int count=version.sentenceStarts.size();java.util.Set<Integer> seen=new java.util.HashSet<>();
        if(!station.equals(p.stationId)||!version.articleId.equals(p.articleId)||!version.versionKey.equals(p.version)||p.sentenceIndex<0||p.sentenceIndex>=count||(p.passEnded&&p.sentenceIndex!=count-1)||(!p.started&&(p.passEnded||!p.completedSentences.isEmpty())))throw new IllegalArgumentException("Archived progress identity");
        for(Integer i:p.completedSentences)if(i==null||i<0||i>=count||!seen.add(i))throw new IllegalArgumentException("Archived coverage");
        if(partial(version,p))throw new IllegalStateException("Partial source payload is missing");
    }
    private static PlayerController.SavedProgress withRead(PlayerController.SavedProgress p,boolean read){return new PlayerController.SavedProgress(p.stationId,p.articleId,p.version,p.sentenceIndex,p.passEnded,p.completedSentences,read,p.started);}
    private static PlayerController.SavedProgress findProgress(Map<String,PlayerController.SavedProgress> progress,String station,String article,String version){
        String exact=PlayerController.progressKey(station,article,version);
        if(progress.containsKey(exact)) {
            PlayerController.SavedProgress p=progress.get(exact);
            if(p==null||!station.equals(p.stationId)||!article.equals(p.articleId)||!version.equals(p.version))throw new IllegalArgumentException("Invalid exact source progress entry");
        }
        PlayerController.SavedProgress found=null;
        for(Map.Entry<String,PlayerController.SavedProgress> entry:progress.entrySet()) {
            PlayerController.SavedProgress p=entry.getValue();
            if(p!=null&&station.equals(p.stationId)&&article.equals(p.articleId)&&version.equals(p.version)){
                if(found!=null||!exact.equals(entry.getKey()))throw new IllegalArgumentException("Duplicate or miskeyed source progress");found=p;
            }
        }return found;
    }
    private static boolean sameProgress(PlayerController.SavedProgress a,PlayerController.SavedProgress b){return a==b||(a!=null&&b!=null&&Objects.equals(a.stationId,b.stationId)&&Objects.equals(a.articleId,b.articleId)&&Objects.equals(a.version,b.version)&&a.sentenceIndex==b.sentenceIndex&&a.passEnded==b.passEnded&&a.completedSentences.equals(b.completedSentences)&&a.read==b.read&&a.started==b.started);}
    /** Appeared stations remain registered after removal of their last material. */
    public List<Station> stationRegistry() {
        checkOwner();
        List<Station> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : state.stations.entrySet())
            result.add(new Station(entry.getKey(), entry.getValue(), Collections.emptyList()));
        return Collections.unmodifiableList(result);
    }
    public String reconciliationMessage() { checkOwner(); return reconciliationMessage; }

    /** Add cursor sequence provenance only to the radio wire snapshot; no Book mutation occurs. */
    public PlayerController.SavedState encodeCursorProvenance(PlayerController.SavedState saved) {
        checkOwner();Objects.requireNonNull(saved,"saved");Map<String,String> cursors=new LinkedHashMap<>(saved.stationArticleIds);
        for(Map.Entry<String,String> entry:new ArrayList<>(cursors.entrySet())) {
            String cursor=entry.getValue();if(cursor==null||cursor.startsWith(AFTER_PREFIX))continue;
            if(cursor.startsWith(SELECTED_PREFIX)){selectedSequence(cursor);selectedArticle(cursor);continue;}
            Material material=state.materials.get(key(entry.getKey(),cursor));
            if(material!=null)cursors.put(entry.getKey(),selectedMarker(material.sequence,material.articleId));
        }
        return new PlayerController.SavedState(saved.currentStationId,cursors,saved.progress);
    }

    public PlayerController.SavedState reconcileState(PlayerController.SavedState saved) {
        checkOwner();
        reconciliationMessage = null;
        if (saved == null) {
            boolean sourceSelected=false;for(Material m:state.materials.values())if(m.sourceVersionKey!=null)sourceSelected=true;
            if(!sourceSelected)return null;
            saved=new PlayerController.SavedState(null,Collections.emptyMap(),Collections.emptyMap());
        }
        if(lifecycleSchema(state.schema)&&saved.currentStationId!=null&&!state.stations.containsKey(saved.currentStationId)
                &&!Arrays.asList("A","B","C").contains(saved.currentStationId))
            throw new IllegalArgumentException("Unknown current station has no ledger provenance");
        Map<String, String> cursors = new LinkedHashMap<>(saved.stationArticleIds);
        java.util.Set<String> proven=new java.util.HashSet<>();
        for(Map.Entry<String,String> entry:new ArrayList<>(cursors.entrySet()))if(entry.getValue()!=null&&entry.getValue().startsWith(SELECTED_PREFIX)) {
            String station=entry.getKey(),cursor=entry.getValue();long sequence=selectedSequence(cursor);String article=selectedArticle(cursor);
            if(!state.stations.containsKey(station)||sequence>state.sequence)throw new IllegalArgumentException("Selected cursor has no ledger provenance");
            Material material=state.materials.get(key(station,article));
            if(material!=null&&material.sequence==sequence){cursors.put(station,article);proven.add(station);}
            else {
                boolean known=material!=null||state.tombstones.containsKey(key(station,article));
                long boundary=known?sequence:stationEnd(station);
                if(!known)reconciliationMessage="Неизвестный сохранённый текст: "+station;
                Material successor=after(station,boundary);cursors.put(station,successor==null?boundaryMarker(boundary,null):successor.articleId);proven.add(station);
            }
        }
        Map<String,PlayerController.SavedProgress> progress=new LinkedHashMap<>(saved.progress);
        if(lifecycleSchema(state.schema))for(Map.Entry<String,PlayerController.SavedProgress> entry:new ArrayList<>(progress.entrySet())){PlayerController.SavedProgress p=entry.getValue();if(p==null)continue;for(SourceRecord source:state.sources.values())if(source.stationId.equals(p.stationId)){boolean retained=false;for(SourceVersionRecord version:state.versions.values())if(version.sourceId.equals(source.id)&&version.articleId.equals(p.articleId)&&version.versionKey.equals(p.version)){retained=true;break;}if(!retained&&!active(state,source.id))progress.remove(entry.getKey());else if(active(state,source.id)){for(SourceItem item:state.deletedItems.values())if(item.sourceId.equals(source.id)&&item.articleId.equals(p.articleId)&&!retained)progress.remove(entry.getKey());}}}
        for(Material material:state.materials.values())if(material.sourceVersionKey!=null) {
            SourceVersionRecord version=state.versions.get(material.sourceVersionKey);
            SourceItem item=state.items.get(key(version.sourceId,version.itemKey));
            PlayerController.SavedProgress live=findProgress(progress,material.stationId,material.articleId,material.version);
            if(live==null)live=material.selectionInitialProgress;else PlayerController.validateSavedProgress(material.stationId,material.asArticle(),live);
            progress.put(PlayerController.progressKey(material.stationId,material.articleId,material.version),withRead(live,item.read));
        }
        for (String station : state.stations.keySet()) {
            String cursor = cursors.get(station);
            if (cursor == null) continue;
            if (cursor.startsWith(AFTER_PREFIX)) {
                long boundary;
                try { boundary = boundarySequence(cursor); }
                catch (IllegalArgumentException invalid) { boundary = stationEnd(station); reconciliationMessage = "Некорректная граница очереди: " + station; }
                Material successor = after(station, boundary);
                cursors.put(station, boundaryMarker(boundary, successor == null ? null : successor.articleId));
            } else if (!proven.contains(station) && (!state.materials.containsKey(key(station, cursor))
                    || (state.tombstones.containsKey(key(station,cursor))&&state.materials.get(key(station,cursor)).sequence>state.tombstones.get(key(station,cursor))))) {
                Long deleted = state.tombstones.get(key(station, cursor));
                long boundary = deleted == null ? stationEnd(station) : deleted;
                if (deleted == null) reconciliationMessage = "Неизвестный сохранённый текст: " + station;
                Material successor = after(station, boundary);
                cursors.put(station, successor == null ? boundaryMarker(boundary, null) : successor.articleId);
            }
        }
        return new PlayerController.SavedState(saved.currentStationId, cursors, progress);
    }

    private long stationEnd(String station) {
        long last = 0;
        for (Material material : state.materials.values()) if (station.equals(material.stationId)) last = Math.max(last, material.sequence);
        for (Map.Entry<String, Long> deleted : state.tombstones.entrySet())
            if (deleted.getKey().startsWith(station.length() + ":" + station)) last = Math.max(last, deleted.getValue());
        return last;
    }
    private Material after(String station, long boundary) {
        for (Material material : state.materials.values())
            if (station.equals(material.stationId) && material.sequence > boundary) return material;
        return null;
    }
    public static String boundaryMarker(long sequence, String forwardArticleId) {
        if (sequence < 0) throw new IllegalArgumentException("sequence");
        return AFTER_PREFIX + sequence + (forwardArticleId == null ? "" : ":" + Base64.getUrlEncoder()
                .withoutPadding().encodeToString(forwardArticleId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    public static String selectedMarker(long sequence,String articleId) {
        if(sequence<=0)throw new IllegalArgumentException("Selected sequence");validId(articleId,"selectedArticleId");
        return SELECTED_PREFIX+sequence+":"+Base64.getUrlEncoder().withoutPadding().encodeToString(articleId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public static long selectedSequence(String marker) {
        if(marker==null||!marker.startsWith(SELECTED_PREFIX))throw new IllegalArgumentException("Selected marker");
        int separator=marker.indexOf(':',SELECTED_PREFIX.length());if(separator<0)throw new IllegalArgumentException("Selected marker");
        try{long sequence=Long.parseLong(marker.substring(SELECTED_PREFIX.length(),separator));if(sequence<=0)throw new IllegalArgumentException("Selected sequence");return sequence;}
        catch(NumberFormatException invalid){throw new IllegalArgumentException("Selected sequence",invalid);}
    }
    public static String selectedArticle(String marker) {
        long sequence=selectedSequence(marker);int separator=marker.indexOf(':',SELECTED_PREFIX.length());
        try {
            String article=new String(Base64.getUrlDecoder().decode(marker.substring(separator+1)),java.nio.charset.StandardCharsets.UTF_8);validId(article,"selectedArticleId");
            if(!selectedMarker(sequence,article).equals(marker))throw new IllegalArgumentException("Noncanonical selected marker");return article;
        }catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Selected article encoding",invalid);}
    }
    public static long boundarySequence(String marker) {
        if (marker == null || !marker.startsWith(AFTER_PREFIX)) throw new IllegalArgumentException("marker");
        int suffix = marker.indexOf(':', AFTER_PREFIX.length());
        String value = marker.substring(AFTER_PREFIX.length(), suffix < 0 ? marker.length() : suffix);
        try { long result = Long.parseLong(value); if (result < 0) throw new IllegalArgumentException("marker"); return result; }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("marker", invalid); }
    }
    public static String boundaryCandidate(String marker) {
        boundarySequence(marker);
        int suffix = marker.indexOf(':', AFTER_PREFIX.length());
        if (suffix < 0) return null;
        try {
            String result = new String(Base64.getUrlDecoder().decode(marker.substring(suffix + 1)), java.nio.charset.StandardCharsets.UTF_8);
            validId(result, "forwardArticleId");
            return result;
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("marker candidate", invalid); }
    }

    private Note required(String id) { Note note = state.notes.get(id); if (note == null) throw new IllegalArgumentException("Unknown note"); return note; }
    private void replace(Note note) { State next = new State(state); next.notes.put(note.id, note); commit(next); }
    private void commit(State next) {
        if(storageUncertain) throw new IllegalStateException("Storage outcome needs recovery");
        if(sourceSchema(next.schema))try{validateSources(next);}catch(IOException invalid){throw new IllegalArgumentException("Invalid source transaction",invalid);}
        byte[] previous=state.schema==1?legacyBytes:encode(state);
        byte[] bytes;
        if(state.schema<4&&next.schema==4){
            if(!(storage instanceof MigrationStorage))throw new IllegalStateException("Format4-capable storage is required");
            byte[] current=readMigrationAuthority();if(!Arrays.equals(current,previous))changedAuthority(current);
            try{next.format4=((MigrationStorage)storage).prepareFormat4Upgrade(current,preparedBytes(next));}
            catch(RuntimeException failure){byte[] checked=readMigrationAuthority();if(!Arrays.equals(checked,current))changedAuthority(checked);throw failure;}
            if(!next.format4.stamp.matches(current))throw new IllegalStateException("Format4 baseline mismatch");
            byte[] checked=readMigrationAuthority();if(!Arrays.equals(checked,current))changedAuthority(checked);
            ((MigrationStorage)storage).verifyFormat4Baseline(next.format4);bytes=encode(next);
        } else if(state.schema==1 && sourceSchema(next.schema)) {
            if(!(storage instanceof MigrationStorage)) throw new IllegalStateException("Migration-capable storage is required");
            byte[] current=readMigrationAuthority();
            if(!Arrays.equals(current,legacyBytes))changedAuthority(current);
            bytes=encode(next);
            try {next.migration=((MigrationStorage)storage).prepareMigration(current,bytes.length+1024);}
            catch(RuntimeException preparationFailure) {
                byte[] checked=readMigrationAuthority();if(!Arrays.equals(checked,current))changedAuthority(checked);
                throw preparationFailure;
            }
            if(!next.migration.matches(current))throw new IllegalStateException("Migration stamp baseline mismatch");
            byte[] checked=readMigrationAuthority();if(!Arrays.equals(checked,current))changedAuthority(checked);
            ((MigrationStorage)storage).verifyMigrationStamp(next.migration);
            bytes=encode(next);
        } else if(state.schema==2&&next.schema==3){
            if(!(storage instanceof MigrationStorage))throw new IllegalStateException("Migration-capable storage is required");
            byte[] current=readMigrationAuthority();if(!Arrays.equals(current,previous))changedAuthority(current);
            try{next.upgrade=((MigrationStorage)storage).prepareSchema3Upgrade(current,deletionBytes(next));}
            catch(RuntimeException failure){byte[] checked=readMigrationAuthority();if(!Arrays.equals(checked,current))changedAuthority(checked);throw failure;}
            if(!next.upgrade.stamp.matches(current))throw new IllegalStateException("Upgrade baseline mismatch");
            byte[] checked=readMigrationAuthority();if(!Arrays.equals(checked,current))changedAuthority(checked);
            ((MigrationStorage)storage).verifyUpgradeRollback(next.upgrade,next.migration);bytes=encode(next);
        } else bytes=encode(next);
        try { storage.save(bytes); }
        catch(RuntimeException failure) {
            byte[] authoritative;
            try { authoritative=storage.load(); }
            catch(RuntimeException unreadable) { markUncertain(); throw new IllegalStateException("Storage outcome is unreadable",unreadable); }
            if(Arrays.equals(authoritative,bytes)) { state=next;revision++;legacyBytes=state.schema==1?bytes.clone():null;verifyRollbackCapsule();return; }
            if(Arrays.equals(authoritative,state.schema==1?legacyBytes:encode(state)))throw failure;
            if(!Arrays.equals(authoritative,previous)) {
                State recovered;
                try {recovered=authoritative==null?new State():decode(authoritative);}
                catch(RuntimeException invalid){markUncertain();throw new IllegalStateException("Storage outcome is invalid",invalid);}
                if((recovered.schema>=state.schema)&&(sourceSchema(recovered.schema) || (state.schema==1&&authoritative!=null))){adoptAuthority(recovered,authoritative);}
                else markUncertain();
                throw new IllegalStateException("A different transaction is authoritative",failure);
            }
            throw failure;
        }
        state=next; revision++; legacyBytes=state.schema==1?bytes.clone():null;
        verifyRollbackCapsule();
    }
    private void changedAuthority(byte[] current) {
        if(Arrays.equals(current,state.schema==1?legacyBytes:encode(state)))
            throw new IllegalStateException("Migration inputs changed; retry from the current book");
        try {
            if(current==null)throw new IllegalStateException("Unexpected metadata absence");
            State actual=decode(current);adoptAuthority(actual,current);
        }catch(RuntimeException unreadable){markUncertain();throw new IllegalStateException("Migration baseline is uncertain",unreadable);}
        throw new IllegalStateException("Migration baseline changed; retry from the current book");
    }
    private void markUncertain(){if(!storageUncertain){storageUncertain=true;authorityEpoch++;}}
    private byte[] readMigrationAuthority(){try{return storage.load();}catch(RuntimeException unreadable){markUncertain();throw new IllegalStateException("Migration authority is unreadable",unreadable);}}
    private void adoptAuthority(State actual,byte[] bytes){state=actual;legacyBytes=actual.schema==1?bytes.clone():null;revision++;authorityEpoch++;verifyRollbackCapsule();}
    private void verifyRollbackCapsule() {
        migrationWarning=null;
        if(state.schema==4&&!state.fresh45&&storage instanceof MigrationStorage)try{((MigrationStorage)storage).verifyFormat4Baseline(state.format4);}catch(RuntimeException failure){migrationWarning="Format4 baseline capsule unavailable; schema4 remains authoritative";}
        if(sourceSchema(state.schema) && state.migration!=null && storage instanceof MigrationStorage) try { ((MigrationStorage)storage).verifyMigrationStamp(state.migration); }
        catch(RuntimeException failure) { migrationWarning="Origin rollback capsule unavailable; schema"+state.schema+" remains authoritative"; }
        if(lifecycleSchema(state.schema)&&state.upgrade!=null&&storage instanceof MigrationStorage)try{((MigrationStorage)storage).verifyUpgradeRollback(state.upgrade,state.migration);}catch(RuntimeException failure){migrationWarning=(migrationWarning==null?"":migrationWarning+"; ")+"Schema2 rollback capsule unavailable; schema3 remains authoritative";}
    }
    public Format4BaselineRef format4Baseline(){checkOwner();return state.format4;}
    public Seed45Marker seed45Marker(){checkOwner();return state.seed45;}
    public static Format4BaselineRef inspectFormat4Baseline(byte[] bytes){return bytes==null?null:decode(bytes).format4;}
    public static Seed45Marker inspectSeed45(byte[] bytes){return bytes==null?null:decode(bytes).seed45;}
    public static int legacySchema(byte[] bytes){if(bytes==null)return 1;int n=decode(bytes).schema;if(n<1||n>3)throw new IllegalArgumentException("Not a legacy format");return n;}
    public static void validateImage(byte[] bytes){if(bytes==null)throw new IllegalArgumentException("Absent Book");decode(bytes);}
    public static byte[] fresh45Target(List<SourceRecord> sources,Seed45Marker marker){
        if(sources.size()!=6)throw new IllegalArgumentException("Six sources required");State next=new State();next.schema=4;next.fresh45=true;next.seed45=Objects.requireNonNull(marker);
        for(SourceRecord source:sources){if(!marker.bindings.containsValue(source.id)||!source.enabled||!source.catalog||next.sources.put(source.id,source)!=null)throw new IllegalArgumentException("Seed source binding");next.stations.put(source.stationId,source.name);next.lifecycle.put(source.id,SourceLifecycle.active());}
        try{validateSources(next);}catch(IOException invalid){throw new IllegalArgumentException("Invalid seed",invalid);}byte[] bytes=encode(next);if(bytes.length>65536)throw new IllegalArgumentException("Seed target size");decode(bytes);return bytes;
    }
    public int schema() { checkOwner(); return state.schema; }
    public long revision() { checkOwner(); return revision; }
    public boolean storageUncertain() { checkOwner();return storageUncertain; }
    public long authorityEpoch() { checkOwner();return authorityEpoch; }
    public MigrationStamp migrationStamp() { checkOwner(); return state.migration; }
    public String migrationWarning() { checkOwner(); return migrationWarning; }
    private static boolean sourceSchema(int schema){return schema>=2&&schema<=4;}
    private static boolean lifecycleSchema(int schema){return schema==3||schema==4;}
    public static void validateSchema2(byte[] bytes){if(bytes==null||decode(bytes).schema!=2)throw new IllegalArgumentException("Not schema2");}
    public static UpgradeRollbackRef inspectUpgradeRollback(byte[] bytes){return bytes==null?null:decode(bytes).upgrade;}
    public UpgradeRollbackRef upgradeRollbackRef(){checkOwner();return state.upgrade;}
    public static void validateSchema1(byte[] bytes) { if(bytes!=null && decode(bytes).schema!=1) throw new IllegalArgumentException("Not schema1"); }
    public static MigrationStamp inspectMigrationStamp(byte[] bytes) {return bytes==null?null:decode(bytes).migration;}
    private void checkOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("Notes must run on owner thread"); }
    private static String fold(String value) { return value.toLowerCase(Locale.ROOT).replace('ё', 'е'); }
    private static <T> List<T> immutable(java.util.Collection<T> values) { return Collections.unmodifiableList(new ArrayList<>(values)); }
    private static String key(String station, String id) { Objects.requireNonNull(station); Objects.requireNonNull(id); return station.length() + ":" + station + id; }
    private static void limited(String value, int maximum, String name, boolean nullable) {
        if (value == null) { if (nullable) return; throw new NullPointerException(name); }
        if (value.length() > maximum) throw new IllegalArgumentException(name + " exceeds limit");
    }
    private static void validId(String value, String name) {
        limited(value, 512, name, false);
        if (value.trim().isEmpty() || value.startsWith(AFTER_PREFIX)||value.startsWith(SELECTED_PREFIX)) throw new IllegalArgumentException(name);
    }
    private static void checkLinks(String station, String article, String version) {
        if ((station == null) != (article == null) || (station == null) != (version == null)) throw new IllegalArgumentException("Incomplete news link");
        if (station != null) { validId(station, "linkedStation"); validId(article, "linkedArticle"); validId(version, "linkedVersion"); }
    }

    public static final class Note {
        public final String id;
        public final long createdAt;
        public final String title, text;
        public final long revision;
        public final String linkedStation, linkedArticle, linkedVersion,linkedSourceVersionKey;
        public final long durationMs, positionMs;
        public final boolean hasAudio;
        public final String recordingStatus, recognitionStatus, message;
        private Note(String id, long createdAt, String title, String text, long revision, String station,
                     String article, String version, long duration, long position, boolean audio,
                     String recording, String recognition, String message) {
            this(id,createdAt,title,text,revision,station,article,version,duration,position,audio,recording,recognition,message,null);
        }
        private Note(String id,long createdAt,String title,String text,long revision,String station,String article,String version,long duration,long position,boolean audio,String recording,String recognition,String message,String sourceVersionKey) {
            this.id = id; this.createdAt = createdAt; this.title = title; this.text = text; this.revision = revision;
            linkedStation = station; linkedArticle = article; linkedVersion = version;
            linkedSourceVersionKey=sourceVersionKey;
            durationMs = duration; positionMs = position; hasAudio = audio;
            recordingStatus = recording; recognitionStatus = recognition; this.message = message;
        }
        private Note copy(String title, String text, long revision, long duration, long position,
                          boolean audio, String recording, String recognition, String message) {
            return new Note(id, createdAt, title, text, revision, linkedStation, linkedArticle, linkedVersion,
                    duration, position, audio, recording, recognition, message,linkedSourceVersionKey);
        }
        private Note withSourceLink(String key){return new Note(id,createdAt,title,text,revision,linkedStation,linkedArticle,linkedVersion,durationMs,positionMs,hasAudio,recordingStatus,recognitionStatus,message,key);}
    }
    public static final class Material {
        public final String stationId, stationName, articleId, version, title, text, noteId;
        public final long sequence;
        public final String sourceVersionKey;
        public final PlayerController.SavedProgress selectionInitialProgress;
        private final List<Integer> starts;
        private Material(String station, String name, String article, String version, String title,
                         String text, long sequence, String noteId, List<Integer> starts) {
            stationId = station; stationName = name; articleId = article; this.version = version;
            this.title = title; this.text = text; this.sequence = sequence; this.noteId = noteId;
            this.starts = Collections.unmodifiableList(new ArrayList<>(starts));
            sourceVersionKey=null;selectionInitialProgress=null;sourceVersion=null;
        }
        private final SourceVersionRecord sourceVersion;
        private Material(String station,String name,SourceVersionRecord version,long sequence,PlayerController.SavedProgress seed) {
            sourceVersion=version;
            stationId=station;stationName=name;articleId=version.articleId;this.version=version.versionKey;title=version.displayTitle;text=version.spokenText;
            this.sequence=sequence;noteId=null;sourceVersionKey=version.recordKey;selectionInitialProgress=seed;starts=version.sentenceStarts;
        }
        public Article asArticle() { return sourceVersion==null?new Article(articleId, version, title, text, starts):sourceVersion.asArticle(); }
        public String boundaryMarker() { return NoteBook.boundaryMarker(sequence, null); }
    }
    private static final class State {
        int schema=1;
        MigrationStamp migration;
        UpgradeRollbackRef upgrade;
        Format4BaselineRef format4;
        Seed45Marker seed45;
        boolean fresh45;
        final Map<String,SourceLifecycle> lifecycle=new LinkedHashMap<>();
        final Map<String,SourceItem> deletedItems=new LinkedHashMap<>();
        long sequence;
        final Map<String, Note> notes = new LinkedHashMap<>();
        final Map<String, String> stations = new LinkedHashMap<>();
        final Map<String, Material> materials = new LinkedHashMap<>();
        final Map<String, Long> tombstones = new LinkedHashMap<>();
        final Map<String,SourceRecord> sources=new LinkedHashMap<>();
        final Map<String,SourceItem> items=new LinkedHashMap<>(),hidden=new LinkedHashMap<>();
        final Map<String,SourceVersionRecord> versions=new LinkedHashMap<>();
        final Map<String,PlayerController.SavedProgress> history=new LinkedHashMap<>();
        State() {}
        State(State source) { schema=source.schema;migration=source.migration;upgrade=source.upgrade;format4=source.format4;seed45=source.seed45;fresh45=source.fresh45;lifecycle.putAll(source.lifecycle);deletedItems.putAll(source.deletedItems);sequence = source.sequence; notes.putAll(source.notes); stations.putAll(source.stations); materials.putAll(source.materials); tombstones.putAll(source.tombstones);sources.putAll(source.sources);items.putAll(source.items);versions.putAll(source.versions);history.putAll(source.history);hidden.putAll(source.hidden); }
    }

    private static byte[] encode(State state) {
        try {
            ByteArrayOutputStream bytes = new BoundedBytes();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(state.schema); out.writeLong(state.sequence);
            if(sourceSchema(state.schema))writeStamp(out,state.migration);
            out.writeInt(state.notes.size());
            for (Note n : state.notes.values()) {
                string(out,n.id); out.writeLong(n.createdAt); string(out,n.title); string(out,n.text); out.writeLong(n.revision);
                string(out,n.linkedStation); string(out,n.linkedArticle); string(out,n.linkedVersion);
                out.writeLong(n.durationMs); out.writeLong(n.positionMs); out.writeBoolean(n.hasAudio);
                string(out,n.recordingStatus); string(out,n.recognitionStatus); string(out,n.message);
                if(sourceSchema(state.schema))string(out,n.linkedSourceVersionKey);
            }
            out.writeInt(state.stations.size());
            for (Map.Entry<String,String> station : state.stations.entrySet()) { string(out,station.getKey()); string(out,station.getValue()); }
            if(sourceSchema(state.schema))writeSources(out,state);
            out.writeInt(state.materials.size());
            for (Material m : state.materials.values()) {
                if(sourceSchema(state.schema))out.writeBoolean(m.sourceVersionKey!=null);
                if(m.sourceVersionKey!=null) {
                    string(out,m.stationId);string(out,m.articleId);string(out,m.version);out.writeLong(m.sequence);
                    string(out,m.sourceVersionKey);writeProgress(out,m.selectionInitialProgress);continue;
                }
                string(out,m.stationId); string(out,m.stationName); string(out,m.articleId); string(out,m.version);
                string(out,m.title); string(out,m.text); out.writeLong(m.sequence); string(out,m.noteId);
                out.writeInt(m.starts.size()); for (int start : m.starts) out.writeInt(start);
            }
            out.writeInt(state.tombstones.size());
            for (Map.Entry<String,Long> tombstone : state.tombstones.entrySet()) { string(out,tombstone.getKey()); out.writeLong(tombstone.getValue()); }
            if(state.schema==3)writeFooter(out,state);else if(state.schema==4)writeFooter4(out,state);
            out.flush();
            java.util.zip.CRC32 checksum = new java.util.zip.CRC32();
            byte[] payload = bytes.toByteArray();
            checksum.update(payload);
            out.writeLong(checksum.getValue());
            out.flush();
            if (bytes.size() > MAX_METADATA_BYTES) throw new IllegalStateException("Достигнут предел метаданных 8 МиБ");
            byte[] encoded=bytes.toByteArray();
            if(sourceSchema(state.schema) && encoded.length+reservedHistoryBytes(state)>MAX_METADATA_BYTES)
                throw new IllegalStateException("Недостаточно места для будущей истории прослушивания в пределах 8 МиБ");
            return encoded;
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static long reservedHistoryBytes(State state)throws IOException {
        long reserved=0;
        for(SourceVersionRecord v:state.versions.values())if(versionProtected(state,v)) {
            List<Integer> complete=new ArrayList<>();for(int i=0;i<v.sentenceStarts.size();i++)complete.add(i);
            SourceRecord s=state.sources.get(v.sourceId);
            PlayerController.SavedProgress maximum=new PlayerController.SavedProgress(s.stationId,v.articleId,v.versionKey,complete.size()-1,true,complete,true,true);
            ByteArrayOutputStream worst=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(worst);string(out,v.recordKey);writeProgress(out,maximum);out.flush();
            PlayerController.SavedProgress old=state.history.get(v.recordKey);int existing=0;
            if(old!=null){ByteArrayOutputStream current=new ByteArrayOutputStream();DataOutputStream actual=new DataOutputStream(current);string(actual,v.recordKey);writeProgress(actual,old);actual.flush();existing=current.size();}
            reserved+=Math.max(0,worst.size()-existing);
        }
        return reserved;
    }
    private static void writeStamp(DataOutputStream out,MigrationStamp stamp)throws IOException {
        out.writeBoolean(stamp!=null);if(stamp==null)return;
        string(out,stamp.attemptId);string(out,stamp.baselineHash);out.writeInt(stamp.baselineLength);string(out,stamp.absenceEpoch);
        string(out,stamp.capsuleId);out.writeInt(stamp.capsuleLength);string(out,stamp.capsuleHash);
    }
    private static MigrationStamp readStamp(DataInputStream in)throws IOException{return readStamp(in,false);}
    private static MigrationStamp readStamp(DataInputStream in,boolean optional)throws IOException {
        if(!in.readBoolean()){if(optional)return null;throw new IOException("Schema2 has no migration stamp");}
        return new MigrationStamp(string(in,36,false),string(in,64,true),in.readInt(),string(in,36,true),string(in,36,false),in.readInt(),string(in,64,false));
    }
    private static void writeList(DataOutputStream out,List<String> values)throws IOException{out.writeInt(values.size());for(String value:values)string(out,value);}
    private static List<String> readList(DataInputStream in,int maxCount,int maxLength)throws IOException{int count=count(in,maxCount);List<String> values=new ArrayList<>();for(int i=0;i<count;i++)values.add(string(in,maxLength,false));return values;}
    private static void writeItem(DataOutputStream out,SourceItem item)throws IOException {
        string(out,item.sourceId);string(out,item.itemKey);string(out,item.articleId);string(out,item.providerId);string(out,item.url);string(out,item.title);writeList(out,item.aliases);
        out.writeLong(item.publishedAt);out.writeLong(item.updatedAt);out.writeLong(item.firstSeenAt);string(out,item.latestVersionKey);out.writeBoolean(item.read);out.writeBoolean(item.everListened);string(out,item.lastViewedVersion);
        string(out,item.publishedRaw);string(out,item.updatedRaw);
        string(out,item.statusHint);string(out,item.reasonHint);string(out,item.methodHint);
    }
    private static SourceItem readItem(DataInputStream in)throws IOException {
        String source=string(in,512,false),item=string(in,512,false),article=string(in,512,false),provider=string(in,4096,true),url=string(in,4096,true),title=string(in,2000,false);
        List<String> aliases=readList(in,16,4096);long published=in.readLong(),updated=in.readLong(),seen=in.readLong();String latest=string(in,512,true);boolean read=in.readBoolean(),listened=in.readBoolean();String viewed=string(in,512,true);
        return new SourceItem(source,item,article,provider,url,title,aliases,published,updated,seen,latest,read,listened,viewed,string(in,2000,true),string(in,2000,true),string(in,64,false),string(in,2000,false),string(in,128,false));
    }
    private static void writeSources(DataOutputStream out,State state)throws IOException {
        out.writeInt(state.sources.size());
        for(SourceRecord s:state.sources.values()) {
            string(out,s.id);string(out,s.stationId);string(out,s.type);string(out,s.key);string(out,s.name);string(out,s.url);string(out,s.adapterId);string(out,s.adapterVersion);writeList(out,s.aliases);
            out.writeBoolean(s.enabled);out.writeBoolean(s.catalog);string(out,s.etag);string(out,s.lastModified);out.writeLong(s.lastSuccess);string(out,s.lastError);
        }
        out.writeInt(state.items.size());for(SourceItem item:state.items.values())writeItem(out,item);
        out.writeInt(state.versions.size());
        for(SourceVersionRecord v:state.versions.values()) {
            string(out,v.sourceId);string(out,v.itemKey);string(out,v.articleId);string(out,v.versionKey);string(out,v.displayTitle);string(out,v.providerTitle);string(out,v.sourceLabel);string(out,v.url);
            string(out,v.completeness);string(out,v.reason);string(out,v.method);string(out,v.adapterVersion);string(out,v.normalizerVersion);string(out,v.framingVersion);string(out,v.bodyHash);string(out,v.spokenText);
            out.writeInt(v.bodyStart);out.writeInt(v.bodyEnd);out.writeLong(v.receivedAt);string(out,v.cacheLocator);out.writeInt(v.spokenLength);out.writeInt(v.sentenceStarts.size());for(int start:v.sentenceStarts)out.writeInt(start);
            if(state.schema==4){if(v.publication==null)out.writeInt(0);else{byte[] publication=v.publication.encode();int size=5+publication.length;if(size>16384)throw new IOException("Version extension limit");out.writeInt(size);out.writeInt(1);out.writeByte(v.contextClass.ordinal());out.write(publication);}}
            else if(v.publication!=null)throw new IOException("V2 version requires Book4");
        }
        out.writeInt(state.history.size());for(Map.Entry<String,PlayerController.SavedProgress> history:state.history.entrySet()){string(out,history.getKey());writeProgress(out,history.getValue());}
        out.writeInt(state.hidden.size());for(SourceItem item:state.hidden.values())writeItem(out,item);
    }
    private static void writeFooter(DataOutputStream out,State state)throws IOException{
        out.writeInt(2);out.writeInt(2);writeStamp(out,state.upgrade==null?null:state.upgrade.stamp);
        out.writeInt(state.lifecycle.size());for(Map.Entry<String,SourceLifecycle> entry:state.lifecycle.entrySet()){string(out,entry.getKey());SourceLifecycle life=entry.getValue();out.writeByte(life.status);out.writeLong(life.generation);out.writeLong(life.lastDeletionSequence);}
        out.writeInt(state.deletedItems.size());for(SourceItem item:state.deletedItems.values())writeItem(out,item);
    }
    private static void readFooter(DataInputStream in,State state)throws IOException{
        int baseline=in.readInt(),codec=in.readInt();state.upgrade=new UpgradeRollbackRef(baseline,codec,readStamp(in));
        int count=count(in,SourceLimits.MAX_SOURCE_RECORDS);for(int i=0;i<count;i++){String id=string(in,36,false);SourceLifecycle life=new SourceLifecycle(in.readUnsignedByte(),in.readLong(),in.readLong());if(state.lifecycle.put(id,life)!=null)throw new IOException("Duplicate lifecycle");}
        int deleted=count(in,160000);for(int i=0;i<deleted;i++){SourceItem item=readItem(in);if(state.deletedItems.put(key(item.sourceId,item.itemKey),item)!=null)throw new IOException("Duplicate deleted item");}
    }
    private static void writeFooter4(DataOutputStream out,State state)throws IOException{
        out.writeInt(1);out.writeBoolean(state.upgrade!=null);if(state.upgrade!=null){out.writeInt(2);out.writeInt(2);writeStamp(out,state.upgrade.stamp);}
        out.writeInt(state.lifecycle.size());for(Map.Entry<String,SourceLifecycle> entry:state.lifecycle.entrySet()){string(out,entry.getKey());SourceLifecycle life=entry.getValue();out.writeByte(life.status);out.writeLong(life.generation);out.writeLong(life.lastDeletionSequence);}
        out.writeInt(state.deletedItems.size());for(SourceItem item:state.deletedItems.values())writeItem(out,item);
        out.writeByte(state.fresh45?1:0);out.writeBoolean(state.format4!=null);if(state.format4!=null){out.writeInt(state.format4.baselineSchema);out.writeInt(4);out.writeInt(3);writeStamp(out,state.format4.stamp);}
        out.writeBoolean(state.seed45!=null);if(state.seed45!=null){string(out,state.seed45.attemptId);string(out,state.seed45.policy);string(out,state.seed45.definitionSetHash);out.writeInt(state.seed45.bindings.size());for(Map.Entry<String,String> e:state.seed45.bindings.entrySet()){string(out,e.getKey());string(out,e.getValue());}}
    }
    private static void readFooter4(DataInputStream in,State state)throws IOException{
        if(in.readInt()!=1)throw new IOException("Book4 footer codec");if(in.readBoolean())state.upgrade=new UpgradeRollbackRef(in.readInt(),in.readInt(),readStamp(in));
        int count=count(in,SourceLimits.MAX_SOURCE_RECORDS);for(int i=0;i<count;i++){String id=string(in,36,false);SourceLifecycle life=new SourceLifecycle(in.readUnsignedByte(),in.readLong(),in.readLong());if(state.lifecycle.put(id,life)!=null)throw new IOException("Duplicate lifecycle");}
        int deleted=count(in,160000);for(int i=0;i<deleted;i++){SourceItem item=readItem(in);if(state.deletedItems.put(key(item.sourceId,item.itemKey),item)!=null)throw new IOException("Duplicate deleted item");}
        int origin=in.readUnsignedByte();if(origin>1)throw new IOException("Book4 origin");state.fresh45=origin==1;
        if(in.readBoolean()){int baseline=in.readInt();if(in.readInt()!=4||in.readInt()!=3)throw new IOException("Book4 baseline codec");state.format4=new Format4BaselineRef(baseline,readStamp(in));}
        if(in.readBoolean()){String attempt=string(in,36,false),policy=string(in,128,false),definitions=string(in,64,false);int n=count(in,6);Map<String,String> bindings=new LinkedHashMap<>();for(int i=0;i<n;i++){String channel=string(in,128,false),id=string(in,36,false);if(bindings.put(channel,id)!=null)throw new IOException("Duplicate seed binding");}state.seed45=new Seed45Marker(attempt,policy,definitions,bindings);}
        if(state.fresh45?(state.seed45==null||state.format4!=null||state.migration!=null||state.upgrade!=null):(state.format4==null||state.seed45!=null))throw new IOException("Book4 origin references");
    }
    private static boolean noteLinked(State state,String record){for(Note note:state.notes.values())if(record.equals(note.linkedSourceVersionKey))return true;return false;}
    private static void validateLifecycle(State state)throws IOException{
        if(state.sources.size()>SourceLimits.MAX_SOURCE_RECORDS||state.lifecycle.size()!=state.sources.size())throw new IOException("Lifecycle count");int active=0,retired=0;
        for(String id:state.sources.keySet()){SourceLifecycle life=state.lifecycle.get(id);if(life==null||life.lastDeletionSequence>state.sequence)throw new IOException("Lifecycle reference");if(life.status==SourceLifecycle.ACTIVE)active++;else retired++;}
        if(active>SourceLimits.MAX_SOURCES||retired>SourceLimits.MAX_RETIRED_SOURCES)throw new IOException("Source lifecycle limit");
        int activeItems=0,retiredItems=0;for(SourceItem item:state.items.values())if(active(state,item.sourceId))activeItems++;else{retiredItems++;boolean linked=false;for(SourceVersionRecord v:state.versions.values())if(v.sourceId.equals(item.sourceId)&&v.itemKey.equals(item.itemKey)&&noteLinked(state,v.recordKey)){linked=true;break;}if(!linked)throw new IOException("Unlinked retired item");}
        if(state.items.size()>32000||activeItems>SourceLimits.MAX_ACTIVE_ITEMS||retiredItems>16000||state.hidden.size()+state.deletedItems.size()>SourceLimits.MAX_SUPPRESSED_ITEMS)throw new IOException("Item lifecycle limits");
        Map<String,Integer> suppression=new LinkedHashMap<>();java.util.Set<String> identities=new java.util.HashSet<>(),articles=new java.util.HashSet<>();
        for(SourceItem item:state.items.values()){identities.add(key(item.sourceId,item.itemKey));articles.add(key(item.sourceId,item.articleId));}
        for(Map<String,SourceItem> table:Arrays.asList(state.hidden,state.deletedItems))for(SourceItem item:table.values()){
            int count=suppression.getOrDefault(item.sourceId,0)+1;suppression.put(item.sourceId,count);
            if(!state.sources.containsKey(item.sourceId)||count>10000||!identities.add(key(item.sourceId,item.itemKey))||!articles.add(key(item.sourceId,item.articleId)))throw new IOException("Suppression identity/count");
        }
        for(SourceVersionRecord v:state.versions.values())if(!active(state,v.sourceId)||state.deletedItems.containsKey(key(v.sourceId,v.itemKey)))if(!noteLinked(state,v.recordKey)||v.spokenText==null)throw new IOException("Deleted version outside note context");
        for(SourceItem item:state.deletedItems.values())if(item.latestVersionKey!=null){SourceVersionRecord v=state.versions.get(item.latestVersionKey);if(v==null||!v.sourceId.equals(item.sourceId)||!v.itemKey.equals(item.itemKey)||!v.articleId.equals(item.articleId))throw new IOException("Deleted latest reference");}
    }
    private static void writeProgress(DataOutputStream out,PlayerController.SavedProgress p)throws IOException {
        string(out,p.stationId);string(out,p.articleId);string(out,p.version);out.writeInt(p.sentenceIndex);out.writeBoolean(p.passEnded);out.writeInt(p.completedSentences.size());for(int index:p.completedSentences)out.writeInt(index);out.writeBoolean(p.read);out.writeBoolean(p.started);
    }
    private static PlayerController.SavedProgress readProgress(DataInputStream in,int maximum)throws IOException {
        String station=string(in,512,false),article=string(in,512,false),version=string(in,512,false);int index=in.readInt();boolean ended=in.readBoolean();int count=count(in,maximum);List<Integer> completed=new ArrayList<>();for(int i=0;i<count;i++)completed.add(in.readInt());return new PlayerController.SavedProgress(station,article,version,index,ended,completed,in.readBoolean(),in.readBoolean());
    }
    private static void readSources(DataInputStream in,State result)throws IOException {
        int sources=count(in,lifecycleSchema(result.schema)?SourceLimits.MAX_SOURCE_RECORDS:SourceLimits.MAX_SOURCES);
        java.util.Set<String> stations=new java.util.HashSet<>(),keys=new java.util.HashSet<>();
        for(int i=0;i<sources;i++) {
            SourceRecord s=new SourceRecord(string(in,36,false),string(in,512,false),string(in,64,false),string(in,4096,false),string(in,200,false),string(in,4096,false),string(in,128,false),string(in,128,false),readList(in,16,4096),in.readBoolean(),in.readBoolean(),string(in,2000,true),string(in,2000,true),in.readLong(),string(in,2000,true));
            if(!s.name.equals(result.stations.get(s.stationId))||!stations.add(s.stationId)||(!lifecycleSchema(result.schema)&&!keys.add(key(s.type,s.key)))||result.sources.put(s.id,s)!=null)throw new IOException("Source identity/registry");
        }
        int items=count(in,lifecycleSchema(result.schema)?32000:SourceLimits.MAX_ACTIVE_ITEMS);Map<String,Integer> active=new LinkedHashMap<>();
        java.util.Set<String> articles=new java.util.HashSet<>();
        for(int i=0;i<items;i++) {
            SourceItem item=readItem(in);int perSource=active.getOrDefault(item.sourceId,0)+1;active.put(item.sourceId,perSource);
            if(!result.sources.containsKey(item.sourceId)||perSource>SourceLimits.MAX_ITEMS_PER_SOURCE||!articles.add(key(item.sourceId,item.articleId))||result.items.put(key(item.sourceId,item.itemKey),item)!=null)throw new IOException("Source item identity/count");
        }
        int versions=count(in,176100);
        for(int i=0;i<versions;i++) {
            String source=string(in,512,false),item=string(in,512,false),article=string(in,512,false),version=string(in,512,false),display=string(in,200,false),provider=string(in,2000,false),label=string(in,200,false),url=string(in,4096,true);
            String quality=string(in,64,false),reason=string(in,2000,false),method=string(in,128,false),adapter=string(in,128,false),normalizer=string(in,128,false),framing=string(in,128,false),hash=string(in,64,false),text=string(in,result.schema==4&&ru.bububu.radio.sources.SourceArticleFramer.VERSION45.equals(framing)?SourceLimits.MAX_SOURCE_SPOKEN_UTF16:MAX_TEXT,true);
            int start=in.readInt(),end=in.readInt();long received=in.readLong();String locator=string(in,512,true);int spokenLength=in.readInt(),size=count(in,result.schema==4&&ru.bububu.radio.sources.SourceArticleFramer.VERSION45.equals(framing)?SourceLimits.MAX_SOURCE_SPOKEN_UTF16:MAX_TEXT);List<Integer> starts=new ArrayList<>();for(int j=0;j<size;j++)starts.add(in.readInt());
            ru.bububu.radio.sources.SourcePublication45 publication=null;ru.bububu.radio.sources.SourcePublication45.Context context=null;
            if(result.schema==4){int length=in.readInt();if(length<0||length>16384||length>in.available())throw new IOException("Source extension length");if(length>0){if(length<6||in.readInt()!=1)throw new IOException("Source extension codec");int mode=in.readUnsignedByte();if(mode>=ru.bububu.radio.sources.SourcePublication45.Context.values().length)throw new IOException("Source context enum");context=ru.bububu.radio.sources.SourcePublication45.Context.values()[mode];byte[] data=new byte[length-5];in.readFully(data);publication=ru.bububu.radio.sources.SourcePublication45.decode(data);}}
            SourceVersionRecord v=new SourceVersionRecord(source,item,article,version,display,provider,label,url,quality,reason,method,adapter,normalizer,framing,hash,text,start,end,received,locator,spokenLength,starts,publication,context);
            if(!result.sources.containsKey(source)||result.versions.put(v.recordKey,v)!=null)throw new IOException("Version identity");
        }
        int histories=count(in,176100);for(int i=0;i<histories;i++){String version=string(in,512,false);SourceVersionRecord binding=result.versions.get(version);if(binding==null)throw new IOException("History version missing");if(result.history.put(version,readProgress(in,binding.sentenceStarts.size()))!=null)throw new IOException("Duplicate history");}
        int hidden=count(in,SourceLimits.MAX_SUPPRESSED_ITEMS);Map<String,Integer> hiddenCounts=new LinkedHashMap<>();
        for(int i=0;i<hidden;i++){SourceItem item=readItem(in);String itemKey=key(item.sourceId,item.itemKey);int perSource=hiddenCounts.getOrDefault(item.sourceId,0)+1;hiddenCounts.put(item.sourceId,perSource);if(!result.sources.containsKey(item.sourceId)||result.items.containsKey(itemKey)||result.hidden.put(itemKey,item)!=null||perSource>SourceLimits.MAX_HIDE_IDS_PER_SOURCE)throw new IOException("Hide tombstone identity/count");}
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        out.writeInt(value == null ? -1 : value.length());
        if (value != null) for (int i=0;i<value.length();i++) out.writeChar(value.charAt(i));
    }
    private static String string(DataInputStream in, int maximum, boolean nullable) throws IOException {
        int length = in.readInt();
        if (length == -1 && nullable) return null;
        if (length < 0 || length > maximum || length > in.available()/2) throw new IOException("Invalid string length");
        char[] chars = new char[length];
        for (int i=0;i<length;i++) chars[i]=in.readChar();
        return new String(chars);
    }
    private static int count(DataInputStream in, int maximum) throws IOException {
        int value=in.readInt(); if(value<0 || value>maximum || value>in.available()/4) throw new IOException("Invalid count"); return value;
    }
    private static State decode(byte[] bytes) {
        if (bytes.length > MAX_METADATA_BYTES) throw new IllegalStateException("Corrupt notes metadata: oversized");
        try {
            if (bytes.length < 8) throw new IOException("Truncated metadata");
            java.util.zip.CRC32 checksum = new java.util.zip.CRC32();
            checksum.update(bytes, 0, bytes.length - 8);
            DataInputStream checksumIn = new DataInputStream(new ByteArrayInputStream(bytes, bytes.length - 8, 8));
            if (checksumIn.readLong() != checksum.getValue()) throw new IOException("Metadata checksum");
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 8));
            if (in.readInt()!=MAGIC) throw new IOException("Unknown notes schema");
            State result=new State();result.schema=in.readInt();if(result.schema!=1&&result.schema!=2&&result.schema!=3&&result.schema!=4)throw new IOException("Unknown notes schema");
            result.sequence=in.readLong(); if(result.sequence<0) throw new IOException("sequence");
            if(sourceSchema(result.schema))result.migration=readStamp(in,result.schema==4);
            int notes=count(in,MAX_NOTES);
            for(int i=0;i<notes;i++) {
                String id=string(in,36,false); if(!UUID.fromString(id).toString().equals(id)) throw new IOException("UUID");
                long created=in.readLong(); String title=string(in,MAX_TITLE,false),text=string(in,MAX_TEXT,false); long revision=in.readLong();
                String station=string(in,512,true),article=string(in,512,true),version=string(in,512,true); checkLinks(station,article,version);
                long duration=in.readLong(),position=in.readLong(); boolean audio=in.readBoolean();
                String recording=string(in,64,false),recognition=string(in,64,false),message=string(in,2000,true);
                String sourceVersionKey=sourceSchema(result.schema)?string(in,512,true):null;
                if(created<0 || revision<0 || duration<0 || position<0 || position>duration
                        || !(RECORDING.equals(recording)||SAVED.equals(recording)||INTERRUPTED.equals(recording)) || recognition.trim().isEmpty()) throw new IOException("Invalid note");
                if(result.notes.put(id,new Note(id,created,title,text,revision,station,article,version,duration,position,audio,recording,recognition,message,sourceVersionKey))!=null) throw new IOException("Duplicate note");
            }
            int stations=count(in,10000);
            for(int i=0;i<stations;i++) {
                String id=string(in,512,false),name=string(in,MAX_TITLE,false); validId(id,"stationId");
                if(name.trim().isEmpty()||result.stations.put(id,name)!=null) throw new IOException("Invalid station");
            }
            if(sourceSchema(result.schema))readSources(in,result);
            int materials=count(in,10000); java.util.Set<Long> sequences=new java.util.HashSet<>(); java.util.Set<String> selectedNotes=new java.util.HashSet<>(); long previous=0;
            for(int i=0;i<materials;i++) {
                if(sourceSchema(result.schema)&&in.readBoolean()) {
                    String station=string(in,512,false),article=string(in,512,false),version=string(in,512,false);long sequence=in.readLong();String recordKey=string(in,512,false);SourceVersionRecord binding=result.versions.get(recordKey);if(binding==null)throw new IOException("Material version missing");PlayerController.SavedProgress seed=readProgress(in,binding.sentenceStarts.size());
                    SourceVersionRecord sourceVersion=result.versions.get(recordKey);SourceRecord source=sourceVersion==null?null:result.sources.get(sourceVersion.sourceId);
                    if(source==null||!source.stationId.equals(station)||!sourceVersion.articleId.equals(article)||!sourceVersion.versionKey.equals(version)||sourceVersion.spokenText==null||sequence<=previous||sequence>result.sequence||!sequences.add(sequence))throw new IOException("Source material reference");
                    PlayerController.validateSavedProgress(station,sourceVersion.asArticle(),seed);previous=sequence;
                    if(result.materials.put(key(station,article),new Material(station,source.name,sourceVersion,sequence,seed))!=null)throw new IOException("Duplicate material");continue;
                }
                String station=string(in,512,false),name=string(in,MAX_TITLE,false),id=string(in,512,false),version=string(in,512,false);
                String title=string(in,MAX_TITLE,false),text=string(in,MAX_TEXT,false); long sequence=in.readLong(); String noteId=string(in,36,true);
                validId(id,"articleId"); validId(version,"version");
                int size=count(in,MAX_TEXT); List<Integer> starts=new ArrayList<>(); for(int j=0;j<size;j++) starts.add(in.readInt());
                Article checked=new Article(id,version,title,text,starts);
                if(!name.equals(result.stations.get(station)) || sequence<=previous || sequence>result.sequence || !sequences.add(sequence)
                        || (noteId!=null && (!result.notes.containsKey(noteId) || !MY_TEXTS.equals(station) || !selectedNotes.add(noteId)))) throw new IOException("Invalid material");
                previous=sequence;
                if(result.materials.put(key(station,id),new Material(station,name,id,version,title,text,sequence,noteId,checked.sentenceStarts))!=null) throw new IOException("Duplicate material");
            }
            int tombstones=count(in,200000);
            for(int i=0;i<tombstones;i++) {
                String key=string(in,1030,false); long sequence=in.readLong();
                int colon=key.indexOf(':'); if(colon<=0) throw new IOException("tombstone key");
                int length=Integer.parseInt(key.substring(0,colon)); if(length<=0 || colon+1+length>=key.length()) throw new IOException("tombstone key");
                String station=key.substring(colon+1,colon+1+length),id=key.substring(colon+1+length);
                validId(id,"articleId"); if(!result.stations.containsKey(station)||sequence<=0||sequence>result.sequence||result.tombstones.put(key,sequence)!=null) throw new IOException("Invalid tombstone");
            }
            if(result.schema==3)readFooter(in,result);else if(result.schema==4)readFooter4(in,result);
            if(sourceSchema(result.schema))validateSources(result);
            if(in.available()!=0) throw new IOException("Trailing data");
            return result;
        } catch (IOException|IllegalArgumentException invalid) { throw new IllegalStateException("Corrupt notes metadata; original files retained",invalid); }
    }
    private static void validateSources(State state)throws IOException {
        int queue=0,protectedCount=0;
        java.util.Set<String> stationIds=new java.util.HashSet<>(),articleIds=new java.util.HashSet<>();
        List<SourceRecord> checked=new ArrayList<>();
        if(state.stations.size()>10000||state.materials.size()>10000||state.notes.size()>MAX_NOTES||state.tombstones.size()>200000||state.versions.size()>176100||state.history.size()>176100)throw new IOException("Book counts");
        if(lifecycleSchema(state.schema)){validateLifecycle(state);}else if(state.sources.size()>SourceLimits.MAX_SOURCES)throw new IOException("Source count");
        for(SourceRecord source:state.sources.values()) {
            if(!source.name.equals(state.stations.get(source.stationId))||!stationIds.add(source.stationId))throw new IOException("Source registry");
            for(SourceRecord previous:checked)if(active(state,previous.id)&&active(state,source.id)&&sourceConflict(previous,source))throw new IOException("Duplicate source alias");checked.add(source);
        }
        Map<String,Integer> active=new LinkedHashMap<>();
        for(SourceItem item:state.items.values()) {
            int count=active.getOrDefault(item.sourceId,0)+1;active.put(item.sourceId,count);
            if(!state.sources.containsKey(item.sourceId)||count>SourceLimits.MAX_ITEMS_PER_SOURCE||!articleIds.add(key(item.sourceId,item.articleId)))throw new IOException("Item identity/count");
        }
        for(Material material:state.materials.values()) {
            if(material.sourceVersionKey!=null){SourceVersionRecord v=state.versions.get(material.sourceVersionKey);if(v==null||!active(state,v.sourceId)||state.deletedItems.containsKey(key(v.sourceId,v.itemKey)))throw new IOException("Deleted source material");queue++;}
            else for(SourceRecord source:state.sources.values())if(source.stationId.equals(material.stationId))throw new IOException("Inline source material");
        }
        if(queue>SourceLimits.MAX_SOURCE_QUEUE)throw new IOException("Source queue count");
        for(Note note:state.notes.values())if(note.linkedSourceVersionKey!=null){
            SourceVersionRecord v=state.versions.get(note.linkedSourceVersionKey);SourceRecord s=v==null?null:state.sources.get(v.sourceId);
            if(s==null||!s.stationId.equals(note.linkedStation)||!v.articleId.equals(note.linkedArticle)||!v.versionKey.equals(note.linkedVersion)||v.spokenText==null)throw new IOException("Note source pin");
        }
        for(SourceItem item:state.items.values())if(item.latestVersionKey!=null){
            SourceVersionRecord v=state.versions.get(item.latestVersionKey);
            if(v==null||!v.sourceId.equals(item.sourceId)||!v.itemKey.equals(item.itemKey)||!v.articleId.equals(item.articleId))throw new IOException("Latest version reference");
        }
        for(Map.Entry<String,PlayerController.SavedProgress> history:state.history.entrySet()) {
            SourceVersionRecord v=state.versions.get(history.getKey());if(v==null)throw new IOException("Missing history version");
            validateProgress(state.sources.get(v.sourceId).stationId,v,history.getValue());
        }
        for(SourceVersionRecord version:state.versions.values()) {
            SourceItem item=anyItem(state,version.sourceId,version.itemKey);
            if(item==null||!item.articleId.equals(version.articleId))throw new IOException("Version item reference");
            boolean protectedVersion=versionProtected(state,version);
            if(protectedVersion){if(version.spokenText==null)throw new IOException("Missing protected payload");protectedCount++;}
            else if(version.spokenText!=null)throw new IOException("Unprotected authoritative payload");
        }
        if(protectedCount>SourceLimits.MAX_PROTECTED_VERSIONS)throw new IOException("Protected source version count");
    }
    private static final class BoundedBytes extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) { checkCapacity(1); super.write(value); }
        @Override public synchronized void write(byte[] bytes, int offset, int length) { checkCapacity(length); super.write(bytes, offset, length); }
        private void checkCapacity(int count) {
            if (count > MAX_METADATA_BYTES - size()) throw new IllegalStateException("Достигнут предел метаданных 8 МиБ");
        }
    }
}

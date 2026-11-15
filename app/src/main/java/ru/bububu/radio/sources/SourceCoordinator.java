package ru.bububu.radio.sources;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.DownloadPresentation;
import ru.bububu.radio.core.NoteBook;
import ru.bububu.radio.core.PlayerController;

/** Network workers produce immutable candidates; only the Book owner can publish them. */
public final class SourceCoordinator implements AutoCloseable {
    public interface Port {
        void dispatch(Runnable action);
        void requireMutable();
        default long radioEpoch() { return 0; }
        void requireStorage(long extraPeak);
        void requireQueue(SourceRecord source, Article article);
        void appendStation(SourceRecord source);
        void append(NoteBook.Material material);
        PlayerController.SavedProgress persisted(NoteBook.Material material);
        void pauseIfSelected(NoteBook.Material material);
        void remove(NoteBook.Material material);
        void projectRead(SourceRecord source, SourceItem item);
        void captureHistory();
        void changed();
        default void writeLoadFailureSnapshot(String json) { }
        default void checkpointDeletion(String stationId,String articleId) { throw new UnsupportedOperationException("Deletion checkpoint unavailable"); }
        default PlayerController.SavedState persistedState() { throw new UnsupportedOperationException("Verified state unavailable"); }
        default void projectDeletion() { throw new UnsupportedOperationException("Deletion projection unavailable"); }
        default void cleanDeletedBodies(List<SourceVersionRecord> candidates) { }
        default void checkpointMutation(List<NoteBook.Material> removed,boolean clear) { throw new UnsupportedOperationException("Bulk checkpoint unavailable"); }
        default void projectMutation(NoteBook.Material replacement,boolean clear) { projectDeletion(); }
        default void beforeSourceSelectionChange(){}
        default String currentIdentity(){throw new UnsupportedOperationException("Live current identity unavailable");}
    }
    public static final class ProjectionPendingException extends IllegalStateException {
        public ProjectionPendingException(RuntimeException cause) { super("Текст/источник сохранён; повторите добавление для восстановления эфира",cause); }
        public ProjectionPendingException(String message,RuntimeException cause) { super(message,cause); }
    }
    public static final class Result<T> {
        public final T value; public final String error; public final SourceFailureInfo failure;
        private Result(T value,String error){this(value,error,error==null?null:SourceFailureInfo.forReason(error));}
        private Result(T value,String error,SourceFailureInfo failure){this.value=value;this.error=error;this.failure=failure;}
        public boolean successful(){return error==null;}
    }
    public static final int PREVIEW_ITEM_LIMIT=20;
    public static final class Preview {
        public final SourceRecord source; public final FeedDocument feed;
        public final List<FeedDiscovery.Candidate> candidates;
        public final int loadedIndex; public final ExtractionResult loaded;
        private final long ticket,deadline,storeEpoch,radioEpoch,createdSequence;
        Preview(SourceRecord source,FeedDocument feed,List<FeedDiscovery.Candidate> candidates,int index,ExtractionResult loaded,long ticket,long deadline,long storeEpoch,long radioEpoch){
            this(source,feed,candidates,index,loaded,ticket,deadline,storeEpoch,radioEpoch,0);
        }
        Preview(SourceRecord source,FeedDocument feed,List<FeedDiscovery.Candidate> candidates,int index,ExtractionResult loaded,long ticket,long deadline,long storeEpoch,long radioEpoch,long createdSequence){
            this.source=source;this.feed=feed;this.candidates=Collections.unmodifiableList(new ArrayList<>(candidates));loadedIndex=index;this.loaded=loaded;this.ticket=ticket;this.deadline=deadline;this.storeEpoch=storeEpoch;this.radioEpoch=radioEpoch;this.createdSequence=createdSequence;
        }
        public boolean discovery(){return feed==null;}
        public boolean experimentalPage(){return HtmlPageReader.isPage(source);}
        public boolean experimentalIndex(){return HtmlIndexReader.isIndex(source);}
    }
    private final NoteBook book; private final SourceTransport transport; private final SourceBodyCache cache; private final Port port;
    private final ThreadPoolExecutor workers;
    private final SourceClock clock;
    private final Map<String,RequestScope> operations=new LinkedHashMap<>();
    private static final class SourceOwner {
        final String id;final long generation;
        SourceOwner(String id,long generation){this.id=id;this.generation=generation;}
    }
    private final Map<String,SourceOwner> owners=new LinkedHashMap<>();
    private long generation,previewRevision,loadAttemptSequence; private boolean closed;
    private DwNetworkDiagnostics armedDwDiagnostic;
    public SourceCoordinator(NoteBook book,SourceTransport transport,SourceBodyCache cache,Port port){this(book,transport,cache,port,SourceClock.SYSTEM);}
    public SourceCoordinator(NoteBook book,SourceTransport transport,SourceBodyCache cache,Port port,SourceClock clock){
        this.clock=Objects.requireNonNull(clock);batchAdmission=new SourceAdmission45(clock);
        this.book=Objects.requireNonNull(book);this.transport=Objects.requireNonNull(transport);this.cache=Objects.requireNonNull(cache);this.port=Objects.requireNonNull(port);
        workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"Bububu-source");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }
    public void preview(String input,boolean telegram,String catalogueName,Consumer<Result<Preview>> callback){preview(input,telegram,catalogueName,0,book.sequence(),callback);}
    /** Test seam only: one exact DW catalog preview within60s, no intents/settings/background activation. */
    public synchronized AutoCloseable armDwDiagnosticsForTest(DwNetworkDiagnostics diagnostic){
        Objects.requireNonNull(diagnostic);if(closed||armedDwDiagnostic!=null)throw new IllegalStateException("DIAGNOSTIC_BUSY_OR_CLOSED");
        armedDwDiagnostic=diagnostic;
        return ()->{synchronized(this){if(armedDwDiagnostic==diagnostic)armedDwDiagnostic=null;}diagnostic.close();};
    }
    private synchronized DwNetworkDiagnostics diagnosticForPreview(String input,boolean telegram,String catalogueName){
        if(armedDwDiagnostic==null)return null;
        try{if(!armedDwDiagnostic.consume(input,telegram,catalogueName))return null;}
        catch(Throwable observerFailure){armedDwDiagnostic.close();armedDwDiagnostic=null;return null;}
        DwNetworkDiagnostics diagnostic=armedDwDiagnostic;armedDwDiagnostic=null;return diagnostic;
    }
    public void chooseFeed(Preview discovery,String url,String catalogueName,Consumer<Result<Preview>> callback){
        requirePreview(discovery);if(!discovery.discovery()||discovery.candidates.stream().noneMatch(c->c.url.equals(url)))throw new IllegalArgumentException("Not a discovered candidate");
        preview(url,false,catalogueName,discovery.deadline,discovery.createdSequence,callback);
    }
    private static SourceRecord existingProfile(List<SourceRecord> sources,SourceProfiles45.Profile profile)throws SourceFailure{
        if(profile==null)return null;
        SourceRecord found=null;
        for(SourceRecord source:sources)if(SourceProfiles45.forSource(source)==profile){
            if(found!=null)throw new SourceFailure("AMBIGUOUS_PROFILE_SOURCE");
            found=source;
        }
        return found;
    }
    private Preview profilePreview(SourceProfiles45.Profile profile,String start,SourceRecord existing,String name,
                                   long ticket,RequestScope scope,long createdSequence)throws SourceFailure{
        SourceTransport.Response response=fetchClosed(profile,true,start,profile.discoveryKind(),SourceTransport.Validators.NONE,scope);
        FeedDocument feed=SourceReaders45.discover(profile,response,scope);
        if(existing!=null)return new Preview(existing,feed,Collections.emptyList(),-1,null,ticket,scope.deadlineMillis,scope.storeEpoch,scope.radioEpoch,createdSequence);
        return makePreview(feed,start,start,profile.type(),name,SourceProfiles45.ADAPTER,ticket,scope.deadlineMillis,scope.storeEpoch,scope.radioEpoch,createdSequence);
    }
    private void preview(String input,boolean telegram,String catalogueName,long deadline,long createdSequence,Consumer<Result<Preview>> callback){
        port.requireMutable();port.requireStorage(128*1024);
        final long ticket=++previewRevision; final RequestScope scope=begin("preview",deadline);
        final DwNetworkDiagnostics diagnostic=diagnosticForPreview(input,telegram,catalogueName);
        // Book belongs to the owner thread. Capture connected identities before dispatch to the worker.
        final List<SourceRecord> connectedSources=new ArrayList<>(book.sources());
        submit("preview",scope,callback,()->{
            SourceInput entry=SourceInput.normalize(input,telegram);
            if(entry.telegram){
                TelegramPreviewAdapter.Target target=TelegramPreviewAdapter.normalizeInput(entry.value);
                SourceTransport.Response response=transport.fetch(target.fetchUrl,SourceTransport.Kind.HTML,SourceTransport.Validators.NONE,scope);
                FeedDocument feed=new TelegramPreviewAdapter().parse(response,target.username,scope);
                return makePreview(feed,target.sourceKey,target.fetchUrl,"telegram_public",catalogueName,"telegram-public-v1",ticket,scope.deadlineMillis,scope.storeEpoch,scope.radioEpoch,createdSequence);
            }
            String start=entry.value;
            SourceProfiles45.Profile closed=SourceProfiles45.byEndpoint(start);
            SourceRecord connected=existingProfile(connectedSources,closed);
            if(closed!=null && (catalogueName!=null || connected!=null))return profilePreview(closed,start,connected,catalogueName,ticket,scope,createdSequence);

            SourceTransport.Response response=diagnostic!=null&&transport instanceof SafeHttpTransport
                    ?((SafeHttpTransport)transport).fetchObservedForTest(start,SourceTransport.Kind.HTML,SourceTransport.Validators.NONE,scope,diagnostic)
                    :transport.fetch(start,SourceTransport.Kind.HTML,SourceTransport.Validators.NONE,scope);
            String finalUrl=SafeHttpTransport.normalizeUrl(response.finalUrl);
            if(!start.equals(finalUrl)){
                SourceProfiles45.Profile redirected=SourceProfiles45.byEndpoint(finalUrl);
                SourceRecord current=existingProfile(connectedSources,redirected);
                // A generic redirect cannot vouch for the publisher's bytes. Refetch only the known endpoint.
                if(current!=null)return profilePreview(redirected,finalUrl,current,catalogueName,ticket,scope,createdSequence);
            }
            if(isXml(response)) {
                if(diagnostic!=null)diagnostic.mark(DwNetworkDiagnostics.Stage.PARSE_BEFORE);
                FeedDocument feed=new XmlFeedReader().parse(response,scope,rssPolicy(catalogueName!=null,start));
                if(diagnostic!=null)diagnostic.mark(DwNetworkDiagnostics.Stage.PARSE_AFTER);
                Preview result=makePreview(feed,SafeHttpTransport.normalizeUrl(feed.finalUrl),feed.finalUrl,feed.type,catalogueName,adapter(feed.finalUrl),ticket,scope.deadlineMillis,scope.storeEpoch,scope.radioEpoch,createdSequence);
                if(start.equals(result.source.key))return result;
                SourceRecord s=result.source;List<String> aliases=new ArrayList<>(s.aliases);aliases.add(start);
                SourceRecord redirected=new SourceRecord(s.id,s.stationId,s.type,s.key,s.name,s.url,s.adapterId,s.adapterVersion,aliases,s.enabled,s.catalog,s.etag,s.lastModified,s.lastSuccess,s.lastError);
                return new Preview(redirected,feed,Collections.emptyList(),-1,null,ticket,scope.deadlineMillis,scope.storeEpoch,scope.radioEpoch,createdSequence);
            }
            if(diagnostic!=null)diagnostic.mark(DwNetworkDiagnostics.Stage.DISCOVERY_BEFORE);
            org.jsoup.nodes.Document html=DomBudgetGuard.parse(response,scope).document;
            List<FeedDiscovery.Candidate> choices=new FeedDiscovery().discover(response,scope,html);
            if(diagnostic!=null)diagnostic.mark(DwNetworkDiagnostics.Stage.DISCOVERY_AFTER);
            boolean singleArticle=!choices.isEmpty()&&HtmlPageReader.confirmedArticle(html,scope);
            if(choices.isEmpty()||singleArticle){
                FeedDocument page=singleArticle?null:HtmlIndexReader.inspect(response,html,scope,false);
                boolean index=page!=null;
                if(!index)page=new HtmlPageReader().read(response,scope,html);
                Preview made=makePreview(page,page.finalUrl,page.finalUrl,index?HtmlIndexReader.TYPE:HtmlPageReader.TYPE,null,index?HtmlIndexReader.ADAPTER:HtmlPageReader.ADAPTER,ticket,scope.deadlineMillis,scope.storeEpoch,scope.radioEpoch,createdSequence);
                SourceRecord s=made.source;if(!start.equals(s.key)){List<String> aliases=new ArrayList<>(s.aliases);aliases.add(start);s=new SourceRecord(s.id,s.stationId,s.type,s.key,s.name,s.url,s.adapterId,s.adapterVersion,aliases,s.enabled,s.catalog,s.etag,s.lastModified,s.lastSuccess,s.lastError);}
                if(index)return new Preview(s,page,Collections.emptyList(),-1,null,ticket,scope.deadlineMillis,scope.storeEpoch,scope.radioEpoch,createdSequence);
                ExtractionResult loaded=page.items.get(0).inline;
                requirePageBatch(s,prepareBatch(s,page,Collections.emptyList(),0,loaded));
                return new Preview(s,page,Collections.emptyList(),0,loaded,ticket,scope.deadlineMillis,scope.storeEpoch,scope.radioEpoch,createdSequence);
            }
            return new Preview(null,null,choices,-1,null,ticket,scope.deadlineMillis,scope.storeEpoch,scope.radioEpoch,createdSequence);
        },x->x,x->{},diagnostic);
    }
    private Preview makePreview(FeedDocument feed,String key,String url,String type,String catalogueName,String adapter,long ticket,long deadline,long storeEpoch,long radioEpoch,long createdSequence) {
        String proposed=catalogueName==null?feed.title:catalogueName;
        // A long feed title is only a suggestion. The user must supply a legal name before commit.
        String name=proposed==null||proposed.trim().isEmpty()?"Новый источник":SemanticTextNormalizer.displayTitle(proposed);
        SourceRecord source=SourceRecord.create(type,key,name,url,adapter,catalogueName!=null);
        SourceProfiles45.Profile closed=SourceProfiles45.forSource(source);if(closed!=null)source=closed.bind(source);
        return new Preview(source,feed,Collections.emptyList(),-1,null,ticket,deadline,storeEpoch,radioEpoch,createdSequence);
    }
    public void previewText(Preview preview,int index,Consumer<Result<Preview>> callback){
        requirePreview(preview);port.requireMutable();port.requireStorage(128*1024);
        if(preview.discovery()||index<0||index>=Math.min(PREVIEW_ITEM_LIMIT,preview.feed.items.size()))throw new IllegalArgumentException("Preview selection");
        List<SourceItem> known=book.isSourceActive(preview.source.id)?book.sourceIdentityItems(preview.source.id):Collections.emptyList();
        final long ticket=preview.ticket; RequestScope scope=begin("preview",preview.deadline);
        submit("preview",scope,callback,()->{
            FeedItem item=preview.feed.items.get(index); ExtractionResult body=extract(preview.source,item,scope);
            // Validate this one choice on the same path that will create its saved version. No writes.
            FeedDocument one=new FeedDocument(preview.feed.title,preview.feed.finalUrl,preview.feed.type,Collections.singletonList(item),false);
            if(!hasSelectedVersion(prepareBatch(preview.source,one,known,0,body),item))throw new SourceFailure("SELECTED_TEXT_UNAVAILABLE");
            return new Preview(preview.source,preview.feed,Collections.emptyList(),index,body,ticket,scope.deadlineMillis,scope.storeEpoch,scope.radioEpoch,preview.createdSequence);
        },x->x);
    }
    public boolean requiresReconnect(Preview preview) {
        requirePreview(preview);if(preview.source==null)return false;
        for(SourceRecord previous:book.allSources())if(book.isSourceRetired(previous.id)&&previous.type.equals(preview.source.type)&&aliasesOverlap(previous,preview.source))return true;
        return false;
    }
    public SourceRecord connect(Preview preview,String name){return connect(preview,name,false);}
    public SourceRecord connect(Preview preview,String name,boolean reconnectConfirmed){
        port.requireMutable();requirePreview(preview);
        if(requiresReconnect(preview)&&!reconnectConfirmed)throw new IllegalStateException("Подтвердите повторное подключение удалённого источника");
        if(preview.discovery())throw new IllegalArgumentException("Сначала выберите и проверьте ленту");
        String label=SemanticTextNormalizer.normalize(name).replace('\n',' ');
        SourceRecord p=preview.source;
        SourceRecord source=new SourceRecord(p.id,p.stationId,p.type,p.key,label,p.url,p.adapterId,p.adapterVersion,p.aliases,true,p.catalog,null,null,System.currentTimeMillis(),null);
        SourceRecord existing=null;
        for(SourceRecord connected:book.sources())if(connected.type.equals(source.type)&&aliasesOverlap(connected,source)) {
            if(existing!=null&&!existing.id.equals(connected.id))throw new IllegalArgumentException("Redirect aliases conflict");existing=connected;
        }
        if(existing!=null) {
            if(preview.loaded!=null && (!existing.enabled || SourceProfiles45.forSource(existing)!=null
                    && SourceProfiles45.forSource(existing)!=SourceProfiles45.forSource(preview.source)))
                throw new IllegalStateException("Источник изменился. Сначала проверьте текст по его текущим правилам.");
            SourceRecord redirected;
            try { redirected=confirmedRedirect(existing,source.url); } catch(SourceFailure invalid) { throw new IllegalArgumentException("Invalid confirmed feed redirect",invalid); }
            List<String> aliases=new ArrayList<>(redirected.aliases);for(String alias:source.aliases)if(!aliases.contains(alias))aliases.add(alias);
            redirected=new SourceRecord(existing.id,existing.stationId,existing.type,redirected.key,existing.name,redirected.url,existing.adapterId,ChannelProfile.forRoute(existing.adapterVersion,existing.key,redirected.key),aliases,existing.enabled,existing.catalog,existing.etag,existing.lastModified,existing.lastSuccess,existing.lastError);
            SourceProfiles45.Profile closed=SourceProfiles45.forSource(source);
            if(closed!=null){redirected=new SourceRecord(redirected.id,redirected.stationId,redirected.type,redirected.key,redirected.name,redirected.url,redirected.adapterId,redirected.adapterVersion,redirected.aliases,redirected.enabled,true,redirected.etag,redirected.lastModified,redirected.lastSuccess,redirected.lastError);redirected=closed.bind(redirected);}
            port.requireStorage(8L*1024*1024+128*1024);requirePreview(preview);
            if(existing.enabled) {
                Batch refreshed=prepareBatch(redirected,preview.feed,book.sourceIdentityItems(existing.id),preview.loadedIndex,preview.loaded);
                requirePageBatch(redirected,refreshed);
                requireSelectedVersion(preview,refreshed);
                List<SourceVersionRecord> versions=canonicalMetadata(refreshed.versions);
                if(SourceProfiles45.forSource(redirected)!=null)port.requireStorage(Math.addExact(book.preflightSourceMerge(redirected,refreshed.items,versions,null,null,System.currentTimeMillis(),null),cachePeak(refreshed.versions)));
                if(preview.loaded!=null)for(SourceVersionRecord v:refreshed.versions)if(v.spokenText!=null) { SourceVersionRecord old=book.sourceVersion(v.recordKey);putCache(old==null?v:old.withPayload(v.spokenText)); }
                requirePreview(preview);book.mergeSourceItems(redirected,refreshed.items,versions,null,null,System.currentTimeMillis(),null);
            } else book.updateSource(redirected);
            SourceRecord connected=requiredSource(existing.id);try{port.appendStation(connected);}catch(RuntimeException failure){port.changed();throw new ProjectionPendingException(failure);}port.changed();return connected;
        }
        Batch batch=prepareBatch(source,preview.feed,Collections.emptyList(),preview.loadedIndex,preview.loaded);
        requirePageBatch(source,batch);
        requireSelectedVersion(preview,batch);
        port.requireStorage(SourceProfiles45.forSource(source)==null?8L*1024*1024+128*1024:Math.addExact(book.preflightSourceCommit(source,batch.items,batch.versions),cachePeak(batch.versions)));
        if(preview.loaded!=null&&preview.loadedIndex>=0)for(SourceVersionRecord version:batch.versions)if(version.spokenText!=null)putCache(version);
        // Publication includes an empty station and metadata; it never selects or starts speech.
        requirePreview(preview);SourceRecord connected=book.commitSource(source,batch.items,batch.versions);try{port.appendStation(connected);}catch(RuntimeException failure){port.changed();throw new ProjectionPendingException(failure);}port.changed();return connected;
    }
    /** Do not commit a list of headings in place of the text the user explicitly chose to save. */
    private static void requireSelectedVersion(Preview preview,Batch batch){
        if(preview.loaded==null)return;
        if(preview.loadedIndex<0||preview.loadedIndex>=preview.feed.items.size())throw new IllegalStateException("Выбранная публикация недоступна");
        if(!hasSelectedVersion(batch,preview.feed.items.get(preview.loadedIndex)))throw new IllegalStateException("Выбранный текст не прошёл проверку источника");
    }
    private static boolean hasSelectedVersion(Batch batch,FeedItem selected){
        SourceItem item=exactSelectedItem(batch.items,selected);
        if(item!=null)for(SourceVersionRecord version:batch.versions)
            if(version.spokenText!=null && version.recordKey.equals(item.latestVersionKey))return true;
        return false;
    }
    private static SourceItem exactSelectedItem(List<SourceItem> items,FeedItem selected){
        SourceItem match=null;
        String key=identity(selected);
        for(SourceItem item:items)if(item.itemKey.equals(key)||matches(item,selected)){
            if(match!=null)return null;
            match=item;
        }
        return match;
    }
    /** Resolve only the exact durable version selected in the preview; never substitute the latest text. */
    public SourceVersionRecord connectedPreviewVersion(Preview preview,SourceRecord connected){
        if(preview==null||preview.source==null||preview.feed==null||preview.loaded==null||preview.loadedIndex<0
                ||preview.loadedIndex>=preview.feed.items.size()||connected==null||!book.isSourceActive(connected.id))return null;
        SourceRecord source=book.source(connected.id);
        if(source==null||!source.type.equals(preview.source.type)||!aliasesOverlap(source,preview.source))return null;
        FeedItem selected=preview.feed.items.get(preview.loadedIndex);
        SourceItem item=exactSelectedItem(book.sourceItems(source.id),selected);
        if(item==null)return null;
        try{
            SourceVersionRecord candidate=version(source,item,preview.loaded);
            return candidate.recordKey.equals(item.latestVersionKey)?book.sourceVersion(candidate.recordKey):null;
        }catch(SourceFailure|IllegalArgumentException noExactVersion){return null;}
    }
    public void refreshAll(java.util.function.BiConsumer<String,Result<SourceRecord>> callback){
        port.requireMutable();
        for(SourceRecord source:book.sources())if(source.enabled) {
            SourceFailureInfo blocked=refreshFailure(source);
            if(blocked!=null&&blocked.retryBlocked(clock)){callback.accept(source.id,new Result<>(null,blocked.reason,blocked));continue;}
            try { refresh(source.id,result->callback.accept(source.id,result)); }
            catch(RuntimeException failure) { callback.accept(source.id,new Result<>(null,reason(failure))); }
        }
    }
    public void refresh(String sourceId,Consumer<Result<SourceRecord>> callback){
        port.requireMutable();port.requireStorage(128*1024);SourceRecord source=requiredSource(sourceId);if(!source.enabled)throw new IllegalStateException("Источник отключён");
        SourceFailureInfo blocked=refreshFailure(source);
        if(blocked!=null&&blocked.retryBlocked(clock)){callback.accept(new Result<>(null,blocked.reason,blocked));return;}
        List<SourceItem> known=book.sourceIdentityItems(sourceId);RequestScope scope=beginSource("refresh:"+sourceId,sourceId);
        submit("refresh:"+sourceId,scope,callback,()->{
            SourceTransport.Response response=fetchSource(source,source.url,discoveryKind(source),
                    new SourceTransport.Validators(source.etag,source.lastModified,source.lastSuccess>0),scope);
            if(response.status==304)return new Refresh(source,null,null,response);
            FeedDocument feed=parseConnected(source,response,scope);
            SourceRecord redirect=confirmedRedirect(source,feed.finalUrl);
            Batch batch=prepareBatch(redirect,feed,known,HtmlPageReader.isPage(source)?0:-1,HtmlPageReader.isPage(source)?feed.items.get(0).inline:null);requirePageBatch(redirect,batch);
            return new Refresh(redirect,feed,batch,response);
        },data->{
            port.requireMutable();SourceRecord current=requiredSource(sourceId);if(!current.enabled)throw new IllegalStateException("Источник отключён");
            port.requireStorage(8L*1024*1024);
            if(data.batch==null)book.updateSource(current.withState(true,current.etag,current.lastModified,System.currentTimeMillis(),null));
            else {if(HtmlPageReader.isPage(data.source))for(SourceVersionRecord v:data.batch.versions)if(v.spokenText!=null)putCache(v);book.mergeSourceItems(data.source,data.batch.items,canonicalMetadata(data.batch.versions),data.response.etag,data.response.lastModified,System.currentTimeMillis(),null);}
            port.changed();return requiredSource(sourceId);
        },reason->{
            SourceRecord current=book.source(sourceId);
            if(current!=null&&book.isSourceActive(sourceId)&&current.enabled){try{port.requireMutable();book.updateSource(current.withState(true,current.etag,current.lastModified,current.lastSuccess,reason));port.changed();}catch(RuntimeException preserved){/* Successful data and validators remain durable. */}}
        });
    }
    public void loadText(String sourceId,String itemKey,Consumer<Result<SourceVersionRecord>> callback){
        SourceRecord source=requiredSource(sourceId);SourceItem item=requiredItem(sourceId,itemKey);
        SourceVersionRecord known=item.latestVersionKey==null?null:book.sourceVersion(item.latestVersionKey);
        final boolean mayFetch=networkAdmitted();
        final String networkTarget=readerTarget(source,item);
        final String action=readerAction(source,item);
        final String operation="body:"+sourceId+":"+itemKey;
        RequestScope scope=beginSource(operation,sourceId);
        submit(operation,scope,callback,()->{
            String requestedTarget=networkTarget;
            try {
                if(known!=null){SourceVersionRecord saved=cache.get(known);if(saved!=null)return new LoadedText(saved,true);}
                SourceFailureInfo blocked=transport.actionCooldown(action);
                if(blocked==null)blocked=transport.cooldown(networkTarget);
                if(blocked!=null&&blocked.retryBlocked(clock))throw blocked.rejection(clock);
                if(!mayFetch)throw new SourceFailure("STORAGE_OR_LIMIT");
                if(!source.enabled)throw new SourceFailure("SOURCE_DISABLED_NO_SAVED_TEXT");
                FeedItem candidate;
                if(articleReader(source,item.url))candidate=new FeedItem(item.providerId,item.url,item.title,item.publishedRaw,item.updatedRaw,null,null,null);
                else {
                    SourceTransport.Response response=fetchSource(source,source.url,discoveryKind(source),SourceTransport.Validators.NONE,scope);
                    FeedDocument feed=parseConnected(source,response,scope);candidate=null;
                    for(FeedItem entry:feed.items)if(matches(item,entry)){candidate=entry;break;}
                    if(candidate==null)throw new SourceFailure("ITEM_NOT_IN_CURRENT_WINDOW");
                    SourceProfiles45.Profile material=SourceProfiles45.forSource(source);if(material!=null&&item.url!=null)MaterialIdentity45.require(material,item.url,candidate.url);
                }
                if(articleReader(source,candidate.url)||genericFeedTeaser(source,candidate))requestedTarget=candidate.url;
                ExtractionResult extracted=extract(source,candidate,scope);if(!extracted.canEnqueue())throw new SourceFailure(extracted.reason.isEmpty()?"NO_READABLE_TEXT":extracted.reason);
                SourceVersionRecord version=version(source,item,extracted);
                // Equal snapshot identities keep their original provenance, including URL and date.
                if(known!=null&&known.versionKey.equals(version.versionKey))version=known.withPayload(version.spokenText);
                return new LoadedText(version,false);
            } catch(SourceFailure failure) {
                if(failure instanceof HttpStatusFailure||failure instanceof CooldownFailure)transport.associateAction(action,requestedTarget);
                throw failure;
            }
        },result->{
            SourceVersionRecord loaded=result.version;
            SourceItem currentItem=requiredItem(sourceId,itemKey);
            if (!Objects.equals(currentItem.latestVersionKey,item.latestVersionKey)
                    || !action.equals(readerAction(requiredSource(sourceId),currentItem))) throw new SourceFailure("STALE_ITEM_RETRY");
            SourceVersionRecord current=book.sourceVersion(loaded.recordKey);
            SourceVersionRecord value=current==null?loaded:current.withPayload(loaded.spokenText);
            if (!result.cached) {
                storeBody(value);
            }
            // Reading an already saved text remains possible without free write space. Badge
            // persistence may fail; neither this read nor its failure marks the item read/listened.
            try { port.requireMutable();book.setSourceViewed(sourceId,itemKey,value.recordKey);port.changed(); } catch(RuntimeException preserved) { }
            return value;
        });
    }
    public static final int DEFAULT_DOWNLOAD_COUNT=5;
    private static final long DOWNLOAD_BATCH_MILLIS=10*60_000L;
    /** Verified current local bodies. The checkbox selection used for downloading is not a filter. */
    public static final class BulkSnapshot {
        public final String sourceFilter;
        public final List<SourceVersionRecord> downloaded,versions;
        public final int fullCount,limitedCount,alreadyQueued,skipped;
        public final long totalCharacters;
        private final SourceCoordinator owner;
        private final boolean cleanupCompleted;
        private final long ticket,storeEpoch,radioEpoch,deadline;
        private final Map<String,Long> generations;
        private BulkSnapshot(SourceCoordinator owner,String filter,long ticket,long store,long radio,long deadline,Map<String,Long> generations,List<SourceVersionRecord> downloaded,List<SourceVersionRecord> eligible,int already,int skipped,boolean cleanupCompleted){
            this.cleanupCompleted=cleanupCompleted;this.owner=owner;sourceFilter=filter;this.ticket=ticket;storeEpoch=store;radioEpoch=radio;this.deadline=deadline;this.generations=Collections.unmodifiableMap(new LinkedHashMap<>(generations));
            this.downloaded=Collections.unmodifiableList(new ArrayList<>(downloaded));versions=Collections.unmodifiableList(new ArrayList<>(eligible));alreadyQueued=already;this.skipped=skipped;
            int full=0,limited=0;long characters=0;for(SourceVersionRecord v:eligible){if("full".equals(v.completeness))full++;else limited++;characters=Math.addExact(characters,v.spokenLength);}fullCount=full;limitedCount=limited;totalCharacters=characters;
        }
    }
    public static final class SourceDownloadProgress {
        public final String sourceId,message;
        public final int candidates,processed,loaded,newVersions,fetchedKnown,failed,skipped;
        public final boolean finished;
        private SourceDownloadProgress(DownloadSource s){sourceId=s.source.id;message=s.message;candidates=s.candidates;processed=s.processed;loaded=s.loaded;newVersions=s.newVersions;fetchedKnown=s.fetchedKnown;failed=s.failed;skipped=s.skipped;finished=s.finished;}
    }
    public static final class CommittedFull {
        public final String runId,sourceId,recordKey,channelReference;
        public final ChannelProfile.Kind kind;public final SourceVersionRecord version;
        public final long storeEpoch,radioEpoch,sourceGeneration;
        private CommittedFull(String run,SourceVersionRecord v,long store,long radio,long generation){runId=run;version=v;sourceId=v.sourceId;recordKey=v.recordKey;channelReference=v.channelReference();kind=v.kind();storeEpoch=store;radioEpoch=radio;sourceGeneration=generation;}
    }
    public static final class AirUpdate {
        public final String runId,explicitSource;
        public final Consumer<CommittedFull> publication;
        public AirUpdate(String run,String explicitSource,Consumer<CommittedFull> publication){runId=Objects.requireNonNull(run);this.explicitSource=explicitSource;this.publication=Objects.requireNonNull(publication);}
        boolean accepts(SourceVersionRecord v){return "full".equals(v.completeness)&&(explicitSource==null?ChannelProfile.automatic(v):explicitSource.equals(v.sourceId));}
    }
    public static final class DownloadProgress {
        public final List<SourceDownloadProgress> sources;
        public final String currentSourceId,message;
        /** saved/loaded are the total readable results (including exact versions verified locally). */
        public final int saved,loaded,newVersions,fetchedKnown,failed,skipped,processedSources,totalSources;
        public final boolean finished,cancelled;
        public final SourceFailureInfo stopFailure;
        public final String runId;public final boolean updateAir;
        public final Set<String> successfulSources,failedSources,savedRecordKeys;
        public final int qualifyingFull,cleanupRemoved,contextKept;
        private DownloadProgress(DownloadRun run) {
            List<SourceDownloadProgress> rows=new ArrayList<>();
            Set<String> failures=new LinkedHashSet<>();
            int ready=0,fresh=0,acquired=0,errors=0,omitted=0,complete=0;
            for(DownloadSource row:run.sources){
                rows.add(new SourceDownloadProgress(row));
                ready+=row.loaded;fresh+=row.newVersions;acquired+=row.fetchedKnown;
                errors+=row.failed;omitted+=row.skipped;
                if(row.finished)complete++;
                if(row.failed>0||row.failureMessage!=null)failures.add(row.source.id);
            }
            sources=Collections.unmodifiableList(rows);
            saved=loaded=ready;newVersions=fresh;fetchedKnown=acquired;failed=errors;skipped=omitted;
            processedSources=complete;totalSources=rows.size();currentSourceId=run.currentSourceId;
            finished=run.finished;cancelled=run.cancelled;stopFailure=run.failure;runId=run.id;updateAir=run.update!=null;
            savedRecordKeys=Collections.unmodifiableSet(new LinkedHashSet<>(run.savedKeys));
            successfulSources=Collections.unmodifiableSet(new LinkedHashSet<>(run.successful));
            failedSources=Collections.unmodifiableSet(failures);qualifyingFull=run.qualifying;
            cleanupRemoved=run.cleanupRemoved;contextKept=run.contextKept;
            message=DownloadPresentation.outcome(this)+". "+DownloadPresentation.counts(ready,fresh,acquired);
        }
    }
    public static final class EnqueueProgress {
        public final int added,alreadyQueued,skipped,restricted,errors,remaining;
        public final boolean finished,cancelled,uncertain;
        public final SourceFailureInfo stopFailure;
        public final String message;
        private EnqueueProgress(EnqueueRun run,String message){added=run.added;alreadyQueued=run.already;skipped=run.skipped;restricted=run.restricted;errors=run.errors;remaining=run.snapshot.versions.size()-run.index;finished=run.finished;cancelled=run.cancelled;uncertain=run.uncertain;stopFailure=run.failure;this.message=message;}
        public boolean needsReview() {
            return skipped > 0 || (added == 0 && alreadyQueued == 0);
        }
        public String downloadResult(SourceClock clock) {
            String counts = "Добавлено: " + added + " · уже в эфире: " + alreadyQueued + " · пропущено: " + skipped + ".";
            if (cancelled) return counts + " Добавление отменено.";
            if (uncertain) return counts + " Состояние эфира требует проверки.";
            if (stopFailure != null) return counts + " Добавление остановлено: " + stopFailure.message(clock);
            if (errors > 0) return counts + " Ошибок при добавлении: " + errors + ".";
            if (restricted > 0) counts += " Для автоматического добавления не подошло текстов: " + restricted
                    + " (ограничение полноты или сведений о публикации).";
            if (skipped > restricted) counts += " Часть сохранённых версий недоступна или отличается от выбранной.";
            if (added == 0 && alreadyQueued == 0 && skipped == 0) counts += " Готовых версий для добавления нет.";
            if (needsReview()) return counts + " Откройте «Новости», проверьте нужный текст и нажмите «В эфир», если добавление доступно.";
            return added > 0 ? counts + " Прослушивание — кнопкой «Слушать»." : counts;
        }
    }
    private abstract static class BatchRun {
        long storeEpoch,radioEpoch;final long deadline;
        String currentSourceId,operation;
        RequestScope scope;
        boolean finished,cancelled;
        SourceFailureInfo failure;
        BatchRun(long store,long radio,long deadline){storeEpoch=store;radioEpoch=radio;this.deadline=deadline;}
    }
    private static final class DownloadSource {
        final SourceRecord source;final long generation;
        List<SourceItem> items=Collections.emptyList();FeedDocument feed;
        int candidates,processed,loaded,newVersions,fetchedKnown,failed,skipped;
        final Set<String> introducedByFeed=new HashSet<>();
        boolean finished;String message="Ожидает загрузки",failureMessage;
        DownloadSource(SourceRecord source,long generation){this.source=source;this.generation=generation;}
    }
    private static final class DownloadRun extends BatchRun {
        final List<DownloadSource> sources;final int count;final Consumer<DownloadProgress> callback;
        int sourceIndex;LoadFailureSnapshot diagnostic;final String id;final AirUpdate update;int qualifying,cleanupRemoved,contextKept;final Set<String> successful=new LinkedHashSet<>(),cleaned=new LinkedHashSet<>(),savedKeys=new LinkedHashSet<>();
        DownloadRun(long store,long radio,long deadline,List<DownloadSource> sources,int count,Consumer<DownloadProgress> callback,AirUpdate update){super(store,radio,deadline);this.sources=sources;this.count=count;this.callback=callback;this.update=update;id=update==null?UUID.randomUUID().toString():update.runId;}
    }
    private static final class EnqueueRun extends BatchRun {
        final BulkSnapshot snapshot;final boolean limited;final Consumer<EnqueueProgress> callback;
        int index,added,already,skipped,restricted,errors;boolean uncertain;final Set<String> addedSources=new LinkedHashSet<>();
        EnqueueRun(long store,long radio,long deadline,BulkSnapshot snapshot,boolean limited,Consumer<EnqueueProgress> callback){super(store,radio,deadline);this.snapshot=snapshot;this.limited=limited;this.callback=callback;already=snapshot.alreadyQueued;}
    }
    private static final class LocalCandidate {
        final SourceVersionRecord version;final long generation;
        LocalCandidate(SourceVersionRecord version,long generation){this.version=version;this.generation=generation;}
    }
    private static final class LocalBodies {
        final List<SourceVersionRecord> values=new ArrayList<>();int unavailable;
    }
    private BatchRun activeBatch;
    private DownloadProgress latestDownload;
    private SourceAdmission45 batchAdmission;
    private long bulkPreviewRevision;
    public boolean batchRunning(){return activeBatch!=null&&!activeBatch.finished;}
    public void cancelBatch(){cancelBatch(true);}
    /** A queued tap from an old download must never cancel a replacement or an enqueue pass. */
    public boolean cancelDownload(String runId){if(!(activeBatch instanceof DownloadRun)||!((DownloadRun)activeBatch).id.equals(runId))return false;cancelBatch(true);return true;}
    private void cancelBatch(boolean report){
        BatchRun run=activeBatch;if(run==null)return;run.cancelled=true;run.finished=true;activeBatch=null;
        if(run.operation!=null)cancel(run.operation);
        if(report)emitBatch(run);
    }
    private void emitBatch(BatchRun run){
        try{
            if(run instanceof DownloadRun){
                DownloadRun d=(DownloadRun)run;
                latestDownload=new DownloadProgress(d);
                d.callback.accept(latestDownload);
            }
            else{EnqueueRun e=(EnqueueRun)run;String message=run.failure!=null?"Добавлено: "+e.added+". "+run.failure.message(clock):run.cancelled?"Добавление отменено. Добавлено: "+e.added:run.finished?"Добавление завершено. Добавлено: "+e.added:"Добавляем в эфир… "+e.added;e.callback.accept(new EnqueueProgress(e,message));}
        }catch(RuntimeException detached){if(activeBatch==run)cancelBatch(false);}
    }
    private boolean batchCurrent(BatchRun run){
        if(activeBatch!=run||run.finished||closed)return false;
        if(book.storageUncertain()||run.storeEpoch!=book.authorityEpoch()||run.radioEpoch!=port.radioEpoch()){if(run instanceof EnqueueRun)((EnqueueRun)run).uncertain=book.storageUncertain()||run.storeEpoch!=book.authorityEpoch();finishBatch(run,false,SourceFailureInfo.forReason("STORAGE_OR_LIMIT"));return false;}
        if(clock.monotonicMillis()>=run.deadline){finishBatch(run,false,SourceFailureInfo.forReason("TIMEOUT"));return false;}return true;
    }
    private void finishBatch(BatchRun run,boolean cancelled,SourceFailureInfo failure){if(activeBatch!=run)return;if(run instanceof DownloadRun&&failure!=null&&!cancelled)LoadFailureSnapshot.storeSafe(((DownloadRun)run).diagnostic,failure,port::writeLoadFailureSnapshot);run.finished=true;run.cancelled=cancelled;run.failure=failure;activeBatch=null;if(run.operation!=null)cancel(run.operation);emitBatch(run);}
    private void batchDeliveryLost(String operation,RequestScope scope){BatchRun run=activeBatch;if(run!=null&&Objects.equals(run.operation,operation)&&run.scope==scope)finishBatch(run,true,SourceFailureInfo.forReason(book.storageUncertain()?"STORAGE_OR_LIMIT":"CANCELLED"));}
    private RequestScope batchScope(BatchRun run,String operation,String source){run.operation=operation;RequestScope scope=beginSource(operation,source,run.deadline);run.scope=scope;if(run instanceof DownloadRun)scope.loadFailureSnapshot=((DownloadRun)run).diagnostic;return scope;}
    /** At most one bounded batch. The original executor still limits all workers to two. */
    public void downloadBatch(Collection<String> sourceIds,int perSource,Consumer<DownloadProgress> callback){
        downloadBatch(sourceIds,perSource,null,callback);
    }
    public void downloadBatch(Collection<String> sourceIds,int perSource,AirUpdate update,Consumer<DownloadProgress> callback){
        batchAdmission=new SourceAdmission45(clock);
        if(perSource!=3&&perSource!=5&&perSource!=10&&perSource!=20)throw new IllegalArgumentException("Выберите 3, 5, 10 или 20 новостей");
        if(batchRunning())throw new IllegalStateException("Загрузка или добавление уже выполняется");port.requireMutable();Objects.requireNonNull(sourceIds);Objects.requireNonNull(callback);
        Set<String> selected=new HashSet<>(sourceIds);List<DownloadSource> sources=new ArrayList<>();for(SourceRecord source:book.sources())if(selected.contains(source.id))sources.add(new DownloadSource(source,book.sourceGeneration(source.id)));
        if(sources.isEmpty())throw new IllegalArgumentException("Выберите источники для загрузки");
        DownloadRun run=new DownloadRun(book.authorityEpoch(),port.radioEpoch(),SourceClock.saturatedAdd(clock.monotonicMillis(),DOWNLOAD_BATCH_MILLIS),sources,perSource,callback,update);run.diagnostic=LoadFailureSnapshot.create(clock,++loadAttemptSequence);activeBatch=run;LoadFailureSnapshot.clearSafe(port::writeLoadFailureSnapshot);emitBatch(run);port.dispatch(()->downloadNextSource(run));
    }
    private void downloadFailure(DownloadRun run,DownloadSource row,SourceFailureInfo failure){
        row.failureMessage=failure.message(clock);row.message=row.failureMessage;
        if(activeBatch!=run||run.finished||closed||book.storageUncertain()||run.storeEpoch!=book.authorityEpoch()||run.radioEpoch!=port.radioEpoch())return;
        SourceRecord current=book.source(row.source.id);
        if(current==null||!book.isSourceActive(current.id)||!current.enabled||book.sourceGeneration(current.id)!=row.generation)return;
        LoadFailureSnapshot.storeSafe(run.diagnostic,failure,port::writeLoadFailureSnapshot);
        String saved=failure.downloadError();if(saved.equals(current.lastError))return;
        try{
            port.requireMutable();
            book.updateSource(current.withState(current.enabled,current.etag,current.lastModified,current.lastSuccess,saved));
            port.changed();
        }catch(RuntimeException diagnosticNotCommitted){/* Keep the primary failure; existing authority guards handle uncertain storage. */}
    }
    private void downloadNextSource(DownloadRun run){
        if(!batchCurrent(run))return;if(run.sourceIndex>=run.sources.size()){finishBatch(run,false,null);return;}
        DownloadSource row=run.sources.get(run.sourceIndex);run.currentSourceId=row.source.id;
        if(!book.isSourceActive(row.source.id)||book.sourceGeneration(row.source.id)!=row.generation){row.skipped++;row.finished=true;row.message="Источник изменён или удалён";run.sourceIndex++;emitBatch(run);port.dispatch(()->downloadNextSource(run));return;}
        SourceRecord source=requiredSource(row.source.id);
        if(!source.enabled){row.skipped++;row.finished=true;row.message="Включите обновление этого источника";run.sourceIndex++;emitBatch(run);port.dispatch(()->downloadNextSource(run));return;}
        LoadFailureSnapshot.beginSafe(run.diagnostic,LoadFailureSnapshot.Phase.BATCH_FEED_GET);LoadFailureSnapshot.contextSafe(run.diagnostic,source);
        try{
            port.requireMutable();port.requireStorage(128*1024);SourceFailureInfo gate=refreshFailure(source);if(gate!=null&&gate.retryBlocked(clock)&&!shortBatchInterval(gate))throw gate.rejection(clock);
            List<SourceItem> known=book.sourceIdentityItems(source.id);String operation="batch-feed:"+source.id;RequestScope scope=batchScope(run,operation,source.id);
            submit(operation,scope,result->{
                if(activeBatch!=run||run.finished||closed)return;
                if(!result.successful()){row.failed++;row.finished=true;downloadFailure(run,row,result.failure);if(result.failure.kind==SourceFailureInfo.Kind.STORAGE||book.storageUncertain()){finishBatch(run,false,result.failure);return;}if(!batchCurrent(run))return;run.sourceIndex++;emitBatch(run);port.dispatch(()->downloadNextSource(run));return;}
                if(!batchCurrent(run))return;
                row.feed=result.value.feed;List<SourceItem> items=new ArrayList<>();Set<String> seen=new HashSet<>();for(SourceItem proposed:result.value.batch.items){SourceItem current=book.sourceItem(source.id,proposed.itemKey);if(current!=null&&seen.add(current.itemKey))items.add(current);}if(HtmlIndexReader.isIndex(source))items.sort(Comparator.comparingLong((SourceItem item)->item.publishedAt).reversed());else items.sort(NEWS_ORDER);row.items=Collections.unmodifiableList(new ArrayList<>(items.subList(0,Math.min(run.count,items.size()))));row.candidates=row.items.size();row.message="Проверяем тексты: 0/"+row.candidates;emitBatch(run);port.dispatch(()->downloadNextBody(run,row));
            },()->{
                awaitBatchInterval(transport.cooldown(source.url),scope);
                SourceTransport.Response response=fetchSource(source,source.url,discoveryKind(source),SourceTransport.Validators.NONE,scope);
                LoadFailureSnapshot.phaseSafe(run.diagnostic,LoadFailureSnapshot.Phase.BATCH_FEED_PARSE);if(response.status==304)throw new SourceFailure("NO_FRESH_FEED");FeedDocument feed=parseConnected(source,response,scope);SourceRecord redirect=confirmedRedirect(source,feed.finalUrl);return new Refresh(redirect,feed,prepareBatch(redirect,feed,known,-1,null),response);
            },data->{LoadFailureSnapshot.phaseSafe(run.diagnostic,LoadFailureSnapshot.Phase.BATCH_FEED_PUBLISH);port.requireMutable();port.requireStorage(8L*1024*1024);requiredSource(source.id);
                // Inline feeds publish version metadata before the body worker. Record which keys
                // existed before that merge; an older known key with an empty cache is not a new version.
                for(SourceVersionRecord version:data.batch.versions)
                    if(book.sourceVersion(version.recordKey)==null)row.introducedByFeed.add(version.recordKey);
                book.mergeSourceItems(data.source,data.batch.items,canonicalMetadata(data.batch.versions),
                        data.response.etag,data.response.lastModified,System.currentTimeMillis(),null);
                port.changed();return data;
            });
        }catch(Exception failure){LoadFailureSnapshot.failureSafe(run.diagnostic,failure);row.failed++;row.finished=true;SourceFailureInfo info=SourceFailureInfo.from(failure,clock);downloadFailure(run,row,info);if(info.kind==SourceFailureInfo.Kind.STORAGE||book.storageUncertain()){finishBatch(run,false,info);return;}run.sourceIndex++;emitBatch(run);port.dispatch(()->downloadNextSource(run));}
    }
    private static final Comparator<SourceItem> NEWS_ORDER=Comparator.comparingLong((SourceItem item)->item.publishedAt>=0?item.publishedAt:item.firstSeenAt).reversed().thenComparing(item->item.itemKey);
    private void downloadNextBody(DownloadRun run,DownloadSource row){
        if(!batchCurrent(run))return;
        if(row.processed>=row.items.size()){if(!cleanupCompleted(run,row))return;row.finished=true;row.message=row.failureMessage==null?"Проверка завершена":row.failureMessage;run.sourceIndex++;emitBatch(run);port.dispatch(()->downloadNextSource(run));return;}
        SourceItem captured=row.items.get(row.processed);
        if(!book.isSourceActive(captured.sourceId)||book.sourceGeneration(captured.sourceId)!=row.generation){finishBatch(run,true,SourceFailureInfo.forReason("CANCELLED"));return;}
        SourceItem item=book.sourceItem(captured.sourceId,captured.itemKey);
        if(item==null){row.processed++;row.skipped++;emitBatch(run);port.dispatch(()->downloadNextBody(run,row));return;}
        SourceRecord source=requiredSource(item.sourceId);SourceVersionRecord known=item.latestVersionKey==null?null:book.sourceVersion(item.latestVersionKey);
        FeedItem candidate=null;for(FeedItem entry:row.feed.items)if(matches(item,entry)){candidate=entry;break;}
        final FeedItem feedItem=candidate;String action=readerAction(source,item),target=readerTarget(source,item);String operation="batch-body:"+source.id+":"+item.itemKey;
        LoadFailureSnapshot.beginSafe(run.diagnostic,LoadFailureSnapshot.Phase.BATCH_BODY);
        try{
            port.requireMutable();port.requireStorage(128*1024);RequestScope scope=batchScope(run,operation,source.id);
            submit(operation,scope,result->{
                if(activeBatch!=run||run.finished||closed)return;row.processed++;
                if(result.successful()){if(!batchCurrent(run))return;}else{SourceFailureInfo info=result.failure;downloadFailure(run,row,info);if(info.kind==SourceFailureInfo.Kind.STORAGE||book.storageUncertain()){row.failed++;finishBatch(run,false,info);return;}if(info.kind==SourceFailureInfo.Kind.HTTP||info.kind==SourceFailureInfo.Kind.NETWORK||info.kind==SourceFailureInfo.Kind.TIMEOUT||info.kind==SourceFailureInfo.Kind.COOLDOWN||info.kind==SourceFailureInfo.Kind.EXCESSIVE)row.failed++;else row.skipped++;}
                if(result.successful()&&row.failureMessage==null)row.message="Проверяем тексты: "+row.processed+"/"+row.candidates;emitBatch(run);port.dispatch(()->downloadNextBody(run,row));
            },()->{
                boolean refreshTeaser=known!=null&&feedItem!=null&&genericFeedTeaser(source,feedItem)
                        && !genericHtmlVersion(source,known.url,known.adapterVersion);
                boolean upgradeHtml=run.update==null && known!=null && HtmlPageReader.PREVIOUS_ADAPTER.equals(known.adapterVersion)
                        && genericHtmlVersion(source,known.url,known.adapterVersion);
                if(known!=null&&!refreshTeaser&&!upgradeHtml&&!SourcePublication45.needsDateRefresh(known)){SourceVersionRecord saved=cache.get(known);if(saved!=null)return new LoadedText(saved,true);}
                if (!HtmlPageReader.isPage(source)) {
                    SourceFailureInfo gate=transport.actionCooldown(action);
                    if(gate==null)gate=transport.cooldown(target);
                    awaitBatchInterval(gate,scope);
                }
                if(!source.enabled)throw new SourceFailure("SOURCE_DISABLED_NO_SAVED_TEXT");if(feedItem==null)throw new SourceFailure("ITEM_NOT_IN_CURRENT_WINDOW");
                try{ExtractionResult extracted=extract(source,feedItem,scope,!upgradeHtml);if(!extracted.canEnqueue())throw new SourceFailure(extracted.reason.isEmpty()?"NO_READABLE_TEXT":extracted.reason);SourceVersionRecord value=version(source,item,extracted);if(known!=null&&known.versionKey.equals(value.versionKey))value=known.withPayload(value.spokenText);return new LoadedText(value,false);}
                catch(SourceFailure failure){if(failure instanceof HttpStatusFailure||failure instanceof CooldownFailure)transport.associateAction(action,target);throw failure;}
            },loaded->{
                LoadFailureSnapshot.phaseSafe(run.diagnostic,LoadFailureSnapshot.Phase.BATCH_BODY_PUBLISH);
                SourceItem current=requiredItem(source.id,item.itemKey);if(!Objects.equals(current.latestVersionKey,item.latestVersionKey)||!action.equals(readerAction(requiredSource(source.id),current)))throw new SourceFailure("STALE_ITEM_RETRY");
                SourceVersionRecord stored=book.sourceVersion(loaded.version.recordKey);SourceVersionRecord value=stored==null?loaded.version:stored.withPayload(loaded.version.spokenText);
                if(!loaded.cached)storeBody(value);
                if(run.savedKeys.add(value.recordKey)){
                    row.loaded++;
                    if(stored==null||row.introducedByFeed.contains(value.recordKey))row.newVersions++;
                    else if(!loaded.cached)row.fetchedKnown++;
                }
                // A failure to project into air (or a later cancellation) does not undo a verified body.
                if(!loaded.cached)port.changed();
                publishAir(run,row,value);return value;
            });
        }catch(Exception failure){LoadFailureSnapshot.failureSafe(run.diagnostic,failure);SourceFailureInfo info=SourceFailureInfo.from(failure,clock);downloadFailure(run,row,info);row.failed++;finishBatch(run,false,info);}
    }
    private void publishAir(DownloadRun run,DownloadSource row,SourceVersionRecord value){
        if(run.update==null||!run.update.accepts(value)||!batchCurrent(run)||!automaticEligible(value))return;
        SourceRecord source=requiredSource(value.sourceId);
        // Refresh never replaces a selected exact version, especially the current audio ticket.
        if(book.material(source.stationId,value.articleId)!=null)return;
        if(!enqueueBound(value,false,false)||!batchCurrent(run))return;
        NoteBook.Material bound=book.material(source.stationId,value.articleId);
        if(bound==null||!value.recordKey.equals(bound.sourceVersionKey))throw new IllegalStateException("Full publication is not bound in air");
        run.qualifying++;run.successful.add(value.sourceId);
        run.update.publication.accept(new CommittedFull(run.id,value,book.authorityEpoch(),port.radioEpoch(),row.generation));
    }
    private boolean cleanupCompleted(DownloadRun run,DownloadSource row){
        if(run.update==null||!run.successful.contains(row.source.id)||run.cleaned.contains(row.source.id))return true;
        if(!batchCurrent(run))return false;
        try{
            int[] counts=cleanupSource(run,row.source.id);run.contextKept+=counts[0];run.cleanupRemoved+=counts[1];run.cleaned.add(row.source.id);return true;
        }catch(RuntimeException failure){row.failureMessage="Очистка не завершена; тексты сохранены";row.failed++;finishBatch(run,false,SourceFailureInfo.from(failure,clock));return false;}
    }
    /** Existing guarded completed-item cleanup, shared by refresh and explicit quiet Add. */
    private int[] cleanupSource(BatchRun run,String sourceId){
        port.requireMutable();String current=port.currentIdentity();long radio=port.radioEpoch();
        NoteBook.CompletedCleanup plan=book.prepareCompletedCleanup(Collections.singleton(sourceId),port.persistedState(),current,radio);
        port.requireStorage(plan.change.targetBytes+16L*1024*1024);port.checkpointMutation(plan.change.removedMaterials,false);port.requireMutable();
        boolean changed=book.applyCompletedCleanup(plan,port.currentIdentity(),port.radioEpoch());
        if(changed){port.projectMutation(null,false);port.changed();port.cleanDeletedBodies(plan.garbage);}
        run.storeEpoch=book.authorityEpoch();run.radioEpoch=port.radioEpoch();return new int[]{plan.contextKept,plan.removed};
    }
    private boolean shortBatchInterval(SourceFailureInfo gate){return gate.kind==SourceFailureInfo.Kind.COOLDOWN&&gate.status==0&&gate.remainingMillis(clock)<=1000;}
    private void awaitBatchInterval(SourceFailureInfo gate,RequestScope scope)throws SourceFailure{
        if(gate==null||!gate.retryBlocked(clock))return;if(!shortBatchInterval(gate))throw gate.rejection(clock);
        // This is the first planned request for the next item, never a retry of an HTTP failure.
        scope.check();try{Thread.sleep(Math.max(1,gate.remainingMillis(clock)));}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new SourceFailure("CANCELLED",cancelled);}scope.check();
    }
    /** Asynchronous local-only scan, in station order and stable newest-first item order. */
    public void prepareBulkSnapshot(String sourceFilter,Consumer<Result<BulkSnapshot>> callback){prepareBulkSnapshot(sourceFilter,null,callback);}
    /** Quiet Add consent is limited to exact versions from the currently displayed completed download. */
    public void prepareDownloadedSnapshot(DownloadProgress download,Consumer<Result<BulkSnapshot>> callback){
        if(download==null||download!=latestDownload||!download.finished||download.updateAir)throw new IllegalArgumentException("STALE_DOWNLOAD_RESULT");
        prepareBulkSnapshot(null,download.savedRecordKeys,callback);
    }
    private void prepareBulkSnapshot(String sourceFilter,Set<String> recordKeys,Consumer<Result<BulkSnapshot>> callback){
        if(closed||book.storageUncertain())throw new IllegalStateException("Source authority unavailable");Objects.requireNonNull(callback);final String filter=sourceFilter!=null&&book.isSourceActive(sourceFilter)?sourceFilter:null;
        final long ticket=++bulkPreviewRevision,store=book.authorityEpoch(),radio=port.radioEpoch(),deadline=SourceClock.saturatedAdd(clock.monotonicMillis(),90_000);
        List<LocalCandidate> candidates=new ArrayList<>();Map<String,Long> generations=new LinkedHashMap<>();int noVersion=0;
        for(SourceRecord source:book.sources())if(filter==null||filter.equals(source.id)){long generation=book.sourceGeneration(source.id);generations.put(source.id,generation);List<SourceItem> items=new ArrayList<>(book.sourceItems(source.id));items.sort(NEWS_ORDER);for(SourceItem item:items){SourceVersionRecord version=item.latestVersionKey==null?null:book.sourceVersion(item.latestVersionKey);if(version==null){if(recordKeys==null)noVersion++;}else if(recordKeys==null||recordKeys.contains(version.recordKey))candidates.add(new LocalCandidate(version,generation));}}
        final int missing=recordKeys==null?noVersion:recordKeys.size()-candidates.size();String operation="bulk-preview";RequestScope scope=begin(operation,deadline);
        submit(operation,scope,callback,()->{LocalBodies bodies=new LocalBodies();for(LocalCandidate candidate:candidates){scope.check();try{SourceVersionRecord value=cache.get(candidate.version);if(value==null||!readable(value))bodies.unavailable++;else bodies.values.add(value);}catch(java.io.IOException|RuntimeException unavailable){bodies.unavailable++;}}return bodies;},bodies->{
            if(ticket!=bulkPreviewRevision)throw new SourceFailure("STALE_ITEM_RETRY");List<SourceVersionRecord> downloaded=new ArrayList<>(),eligible=new ArrayList<>();int already=0,skipped=missing+bodies.unavailable;
            for(SourceVersionRecord value:bodies.values){if(!snapshotCurrent(value,generations)){skipped++;continue;}downloaded.add(value);SourceRecord source=requiredSource(value.sourceId);NoteBook.Material chosen=book.material(source.stationId,value.articleId);if(chosen!=null){if(value.recordKey.equals(chosen.sourceVersionKey))already++;else skipped++;}else eligible.add(value);}
            return new BulkSnapshot(this,filter,ticket,store,radio,deadline,generations,downloaded,eligible,already,skipped,recordKeys!=null);
        });
    }
    private static boolean readable(SourceVersionRecord value){return value.spokenText!=null&&!value.spokenText.trim().isEmpty()&&("full".equals(value.completeness)||"summary".equals(value.completeness)||"unknown".equals(value.completeness));}
    private boolean snapshotCurrent(SourceVersionRecord version,Map<String,Long> generations){
        Long generation=generations.get(version.sourceId);if(generation==null||!book.isSourceActive(version.sourceId)||book.sourceGeneration(version.sourceId)!=generation)return false;SourceItem item=book.sourceItem(version.sourceId,version.itemKey);SourceVersionRecord stored=book.sourceVersion(version.recordKey);return item!=null&&version.recordKey.equals(item.latestVersionKey)&&stored!=null&&stored.sameMetadata(version);
    }
    /** Explicit consent applies only to these captured exact versions; each uses normal admission. */
    public void enqueueBulk(BulkSnapshot snapshot,boolean includeLimited,Consumer<EnqueueProgress> callback){
        batchAdmission=new SourceAdmission45(clock);
        if(batchRunning())throw new IllegalStateException("Загрузка или добавление уже выполняется");port.requireMutable();Objects.requireNonNull(callback);
        if(closed||snapshot==null||snapshot.owner!=this||snapshot.ticket!=bulkPreviewRevision||snapshot.storeEpoch!=book.authorityEpoch()||snapshot.radioEpoch!=port.radioEpoch()||clock.monotonicMillis()>=snapshot.deadline)throw new IllegalStateException("Список изменился. Проверьте загруженные тексты снова");
        EnqueueRun run=new EnqueueRun(book.authorityEpoch(),port.radioEpoch(),snapshot.deadline,snapshot,includeLimited,callback);activeBatch=run;emitBatch(run);port.dispatch(()->enqueueNext(run));
    }
    private void enqueueNext(EnqueueRun run){
        if(!batchCurrent(run))return;if(run.index>=run.snapshot.versions.size()){
            if(run.snapshot.cleanupCompleted)try{for(String source:run.addedSources){if(!batchCurrent(run))return;cleanupSource(run,source);}}catch(RuntimeException failure){finishBatch(run,false,SourceFailureInfo.from(failure,clock));return;}
            finishBatch(run,false,null);return;
        }
        SourceVersionRecord value=run.snapshot.versions.get(run.index);run.currentSourceId=value.sourceId;
        boolean current = snapshotCurrent(value,run.snapshot.generations);
        boolean restricted = current && ((!run.limited && !"full".equals(value.completeness)) || !automaticEligible(value));
        if (!current || restricted) {
            if (restricted) run.restricted++;
            run.index++;run.skipped++;emitBatch(run);port.dispatch(()->enqueueNext(run));return;
        }
        SourceRecord source=requiredSource(value.sourceId);NoteBook.Material chosen=book.material(source.stationId,value.articleId);
        if(chosen!=null){run.index++;if(value.recordKey.equals(chosen.sourceVersionKey))run.already++;else run.skipped++;emitBatch(run);port.dispatch(()->enqueueNext(run));return;}
        try{boolean added=enqueueBound(value,run.limited,false);run.index++;if(added){run.added++;run.addedSources.add(value.sourceId);}else run.already++;emitBatch(run);port.dispatch(()->enqueueNext(run));}
        catch(RuntimeException failure){run.index++;if(failure instanceof ProjectionPendingException){NoteBook.Material committed=book.material(source.stationId,value.articleId);if(committed!=null&&value.recordKey.equals(committed.sourceVersionKey))run.added++;else run.errors++;}else run.errors++;run.uncertain=book.storageUncertain();finishBatch(run,false,SourceFailureInfo.from(failure,clock));}
    }

    /** Old saved text is read exactly or reported unavailable. Never refetch it as a newer version. */
    public void savedText(String recordKey,Consumer<Result<SourceVersionRecord>> callback){
        SourceVersionRecord version=book.sourceVersion(recordKey);if(version==null)throw new IllegalArgumentException("Unknown saved version");
        requiredSource(version.sourceId);requiredItem(version.sourceId,version.itemKey);
        String operation="saved:"+recordKey;RequestScope scope=beginSource(operation,version.sourceId);
        submit(operation,scope,callback,()->{SourceVersionRecord result=cache.get(version);if(result==null)throw new SourceFailure("SAVED_TEXT_NOT_AVAILABLE");return result;},x->{requiredSource(version.sourceId);requiredItem(version.sourceId,version.itemKey);return x;});
    }
    private long cachePeak(List<SourceVersionRecord> versions){long total=0;try{for(SourceVersionRecord v:versions)if(v.spokenText!=null)total=Math.addExact(total,cache.additionalBytes(v));return total;}catch(java.io.IOException invalid){throw new IllegalStateException("Не удалось проверить место для текста",invalid);}}
    private void storeBody(SourceVersionRecord value)throws java.io.IOException{port.requireMutable();long peak=value.publication==null?8L*1024*1024+128*1024:Math.addExact(book.preflightSourceVersion(value.withPayload(null)),cache.additionalBytes(value));port.requireStorage(peak);cache.put(value);book.saveSourceVersion(value.withPayload(null));}
    private boolean automaticEligible(SourceVersionRecord v){if(!book.format45Active()&&v.publication==null)return true;try{batchAdmission.require(v,false);return true;}catch(SourceFailure denied){return false;}}
    public boolean enqueue(SourceVersionRecord version,boolean limitedConsent){return enqueueBound(version,limitedConsent,true);}
    private boolean enqueueBound(SourceVersionRecord version,boolean limitedConsent,boolean individualManual){
        port.requireMutable();SourceRecord source=requiredSource(version.sourceId);
        port.requireQueue(source,version.asArticle());if(version.publication==null)port.requireStorage(8L*1024*1024);
        NoteBook.Material selected=null;for(NoteBook.Material m:book.materials())if(source.stationId.equals(m.stationId)&&version.articleId.equals(m.articleId))selected=m;
        if(selected==null&&(book.format45Active()||version.publication!=null))try{
            if(genericHtmlVersion(source,version.url,version.adapterVersion)&&individualManual){if(version.publication==null||!"experimental_html".equals(version.method))throw new SourceFailure("HTML_PAGE_UNSUPPORTED");new SourceAdmission45(clock).context(version.publication,true);}
            else (individualManual?new SourceAdmission45(clock):batchAdmission).require(version,individualManual);
        }catch(SourceFailure denied){throw new IllegalArgumentException(denied.reason);}
        if(version.publication!=null)port.requireStorage(book.preflightSourceSelection(version,limitedConsent));
        NoteBook.Material material=book.selectSourceVersion(version,limitedConsent);try{port.append(material);}catch(RuntimeException failure){port.changed();throw new ProjectionPendingException(failure);}port.changed();return selected==null;
    }
    public void remove(String sourceId,String articleId){
        port.requireMutable();SourceRecord source=requiredSource(sourceId);
        NoteBook.Material selected=null;for(NoteBook.Material m:book.materials())if(source.stationId.equals(m.stationId)&&articleId.equals(m.articleId))selected=m;
        if(selected==null)return;port.pauseIfSelected(selected);
        port.requireMutable(); // Pause can absorb a failed checkpoint and revoke radio authority.
        PlayerController.SavedProgress last=port.persisted(selected); // Unknown persistence is a refusal, not a zero seed.
        NoteBook.Material removed=book.removeSourceMaterial(source.stationId,articleId,last);if(removed!=null)port.remove(removed);port.changed();
    }
    /** One durable deletion, then an idempotent radio projection; never a removeMaterial loop. */
    public boolean deleteSource(String sourceId){
        SourceRecord source=book.source(sourceId);if(source==null)throw new IllegalArgumentException("Unknown source");
        if(book.isSourceRetired(sourceId)){projectDeleted();return false;}
        port.requireMutable();int targetBytes=book.preflightDeleteSource(sourceId,port.persistedState());port.requireStorage(targetBytes+16L*1024*1024);
        port.checkpointDeletion(source.stationId,null);port.requireMutable();
        List<SourceVersionRecord> candidates=book.sourceVersionsForSource(sourceId);
        cancelSource(sourceId);invalidatePreview();
        boolean changed=book.deleteSource(sourceId,port.persistedState());projectDeleted();port.cleanDeletedBodies(candidates);return changed;
    }
    public boolean deleteItem(String sourceId,String itemKey){
        if(book.isItemDeleted(sourceId,itemKey)||book.isSourceRetired(sourceId)){projectDeleted();return false;}
        SourceRecord source=requiredSource(sourceId);SourceItem item=requiredItem(sourceId,itemKey);
        port.requireMutable();int targetBytes=book.preflightDeleteSourceItem(sourceId,itemKey,port.persistedState());port.requireStorage(targetBytes+16L*1024*1024);
        port.checkpointDeletion(source.stationId,item.articleId);port.requireMutable();
        List<SourceVersionRecord> candidates=book.sourceVersions(sourceId,itemKey);
        cancelSource(sourceId);invalidatePreview();
        boolean changed=book.deleteSourceItem(sourceId,itemKey,port.persistedState());projectDeleted();port.cleanDeletedBodies(candidates);return changed;
    }
    public boolean clearAir(){return mutate("clear",null,0);}
    public boolean deleteAllNews(){return mutate("news",null,0);}
    public boolean updateNoteInAir(String id,long expectedRevision){return mutate("note",id,expectedRevision);}
    private NoteBook.DeletionChange prepareMutation(String kind,String id,long revision){
        if("clear".equals(kind))return book.prepareClearAir(port.persistedState());
        if("news".equals(kind))return book.prepareDeleteAllNews(port.persistedState());
        return book.prepareReplaceNoteInAir(id,revision,port.persistedState());
    }
    private boolean mutate(String kind,String id,long revision){
        port.requireMutable();NoteBook.DeletionChange candidate=prepareMutation(kind,id,revision);
        if(!candidate.changed){cancelUi();projectMutation(candidate.replacement,"clear".equals(kind));return false;}
        List<SourceVersionRecord> garbage=new ArrayList<>();if("news".equals(kind))for(String source:candidate.sourceIds)garbage.addAll(book.sourceVersionsForSource(source));
        long copies=0;if("clear".equals(kind))for(SourceVersionRecord body:candidate.sourceBodies)copies=Math.addExact(copies,2L*body.spokenText.length()+128);
        port.requireStorage(Math.addExact(candidate.targetBytes+16L*1024*1024,copies));
        if("clear".equals(kind))try{for(SourceVersionRecord body:candidate.sourceBodies)cache.preserve(body);}
        catch(java.io.IOException failure){throw new IllegalStateException("Не удалось сохранить скачанные тексты; эфир не изменён",failure);}
        port.checkpointMutation(candidate.removedMaterials,"clear".equals(kind));port.requireMutable();
        cancelUi();candidate=prepareMutation(kind,id,revision);port.requireMutable();
        port.requireStorage(candidate.targetBytes+16L*1024*1024);
        boolean changed;
        try{changed=book.applyDeletion(candidate);}
        catch(RuntimeException failed){port.changed();throw failed;}
        projectMutation(candidate.replacement,"clear".equals(kind));
        if("news".equals(kind))port.cleanDeletedBodies(garbage);
        return changed;
    }
    private void projectMutation(NoteBook.Material replacement,boolean clear){
        try{port.projectMutation(replacement,clear);port.changed();}
        catch(RuntimeException failure){port.changed();throw new ProjectionPendingException("Изменение сохранено; эфир требует восстановления. Закройте и откройте приложение",failure);}
    }
    private void projectDeleted(){
        try{port.projectDeletion();port.changed();}
        catch(RuntimeException failure){port.changed();throw new ProjectionPendingException("Удаление сохранено; закройте и откройте приложение или повторите действие для восстановления эфира",failure);}
    }
    public void setRead(String sourceId,String itemKey,boolean read){port.requireMutable();SourceItem item=book.setSourceRead(sourceId,itemKey,read);port.projectRead(requiredSource(sourceId),item);port.changed();}
    public int cleanup(String sourceId){port.requireMutable();port.captureHistory();int hidden=book.hideCompletedSourceItems(sourceId);port.changed();return hidden;}
    public void enabled(String sourceId,boolean enabled){port.beforeSourceSelectionChange();port.requireMutable();if(!enabled)cancelSource(sourceId);book.setSourceEnabled(sourceId,enabled);port.changed();}
    public void invalidatePreview(){previewRevision++;cancel("preview");}
    public void cancelUi(){cancelBatch(false);bulkPreviewRevision++;invalidatePreview();for(String key:new ArrayList<>(operations.keySet()))cancel(key);}
    private void cancelSource(String sourceId){if(activeBatch!=null&&sourceId.equals(activeBatch.currentSourceId))cancelBatch();for(Map.Entry<String,SourceOwner> owner:new ArrayList<>(owners.entrySet()))if(owner.getValue().id.equals(sourceId))cancel(owner.getKey());}
    private void cancel(String key){RequestScope scope=operations.remove(key);owners.remove(key);if(scope!=null)scope.cancel();}
    private void requirePreview(Preview p){
        if(closed||p==null||p.ticket!=previewRevision||p.storeEpoch!=book.authorityEpoch()||p.radioEpoch!=port.radioEpoch()||clock.monotonicMillis()>=p.deadline)throw new IllegalStateException("Preview устарел: проверьте ссылку снова");
        if(p.source!=null)for(SourceRecord previous:book.allSources())if(previous.type.equals(p.source.type)&&aliasesOverlap(previous,p.source)&&book.lastDeletionSequence(previous.id)>p.createdSequence)throw new IllegalStateException("Источник изменён; проверьте ссылку снова");
    }
    private RequestScope beginSource(String operation,String sourceId){return beginSource(operation,sourceId,0);}
    private RequestScope beginSource(String operation,String sourceId,long deadline){
        requiredSource(sourceId);RequestScope scope=begin(operation,deadline);owners.put(operation,new SourceOwner(sourceId,book.sourceGeneration(sourceId)));return scope;
    }
    private RequestScope begin(String operation){return begin(operation,0);}
    private RequestScope begin(String operation,long deadline){
        if(closed)throw new IllegalStateException("Source service closed");
        long remaining=deadline==0?90_000:deadline-clock.monotonicMillis();
        if(remaining<=0)throw new IllegalStateException("Source operation expired");
        cancel(operation);RequestScope scope=new RequestScope(++generation,book.authorityEpoch(),clock,Math.min(90_000,remaining),port.radioEpoch());operations.put(operation,scope);return scope;
    }
    private boolean current(String operation,RequestScope scope){
        SourceOwner source=owners.get(operation);
        return !closed && !scope.isCancelled() && scope.storeEpoch==book.authorityEpoch() && scope.radioEpoch==port.radioEpoch() && operations.get(operation)==scope
                && (source==null||book.isSourceActive(source.id)&&book.sourceGeneration(source.id)==source.generation);
    }
    private interface Work<T>{T run()throws Exception;}
    private interface Publish<T,R>{R run(T value)throws Exception;}
    private <T,R> void submit(String operation,RequestScope scope,Consumer<Result<R>> callback,Work<T> work,Publish<T,R> publish){submit(operation,scope,callback,work,publish,x->{});}
    private <T,R> void submit(String operation,RequestScope scope,Consumer<Result<R>> callback,Work<T> work,Publish<T,R> publish,Consumer<String> failure){
        submit(operation,scope,callback,work,publish,failure,null);
    }
    private <T,R> void submit(String operation,RequestScope scope,Consumer<Result<R>> callback,Work<T> work,Publish<T,R> publish,Consumer<String> failure,DwNetworkDiagnostics diagnostic){
        try{workers.execute(()->{T value=null;SourceFailureInfo reason=null;try{scope.check();value=work.run();scope.check();if(diagnostic!=null)diagnostic.success();}catch(Exception error){LoadFailureSnapshot.failureSafe(scope.loadFailureSnapshot,error);if(diagnostic!=null)diagnostic.failure(error);reason=SourceFailureInfo.from(error,clock);}final T candidate=value;final SourceFailureInfo error=reason;
            port.dispatch(()->{
                if(!current(operation,scope)){if(operations.get(operation)==scope){operations.remove(operation);owners.remove(operation);scope.cancel();}batchDeliveryLost(operation,scope);return;}
                try{scope.check();if(error!=null){failure.accept(error.reason);callback.accept(new Result<>(null,error.reason,error));}else{R committed=publish.run(candidate);callback.accept(new Result<>(committed,null));}}
                catch(Exception invalid){LoadFailureSnapshot.failureSafe(scope.loadFailureSnapshot,invalid);SourceFailureInfo info=SourceFailureInfo.from(invalid,clock);callback.accept(new Result<>(null,info.reason,info));}
                finally{if(operations.get(operation)==scope){operations.remove(operation);owners.remove(operation);}}
            });
        });}catch(RejectedExecutionException busy){if(diagnostic!=null)diagnostic.failure(busy);operations.remove(operation);owners.remove(operation);scope.cancel();callback.accept(new Result<>(null,"BUSY"));}
    }
    public SourceFailureInfo cooldown(String input,boolean telegram) {
        try { return transport.cooldown(SourceInput.normalize(input,telegram).fetchUrl); }
        catch(SourceFailure invalid) { return null; }
    }
    /** The same action projection is used by single refresh, bulk and visible controls. */
    public SourceFailureInfo refreshFailure(SourceRecord source) { return transport.cooldown(source.url); }
    public SourceFailureInfo previewTextFailure(Preview preview,int index) {
        if(preview==null||preview.feed==null||index<0||index>=Math.min(PREVIEW_ITEM_LIMIT,preview.feed.items.size()))return null;
        FeedItem item=preview.feed.items.get(index);
        return articleReader(preview.source,item.url)||genericFeedTeaser(preview.source,item)?transport.cooldown(item.url):null;
    }
    public SourceFailureInfo readerFailure(String sourceId,String itemKey) {
        SourceRecord source=book.source(sourceId);SourceItem item=book.sourceItem(sourceId,itemKey);
        if(source==null||item==null)return null;
        SourceVersionRecord known=item.latestVersionKey==null?null:book.sourceVersion(item.latestVersionKey);
        if(known!=null&&cache.mayHavePayload(known))return null;
        SourceFailureInfo associated=transport.actionCooldown(readerAction(source,item));
        return associated==null?transport.cooldown(readerTarget(source,item)):associated;
    }
    private static String readerAction(SourceRecord source,SourceItem item) {
        // A late failure for an old item snapshot must not disable a newly published target.
        return "body:"+source.id+":"+item.itemKey+":"+SourceLimits.sha256(source.url+"\u0000"+Objects.toString(item.url,"")+"\u0000"+Objects.toString(item.latestVersionKey,""));
    }
    private static String readerTarget(SourceRecord source,SourceItem item) { return articleReader(source,item.url)||customFeedArticle(source,item.url)?item.url:source.url; }
    public SourceFailureInfo sourceFailure(SourceRecord source) {
        SourceFailureInfo gate=transport.cooldown(source.url);
        SourceFailureInfo saved=source.lastError==null?null:SourceFailureInfo.forStoredError(source.lastError);
        return gate==null?saved:saved!=null&&saved.status>0?gate.withStatus(saved.status):gate;
    }
    private List<SourceVersionRecord> canonicalMetadata(List<SourceVersionRecord> incoming) {
        List<SourceVersionRecord> result=new ArrayList<>();
        for(SourceVersionRecord v:incoming) { SourceVersionRecord stored=book.sourceVersion(v.recordKey); result.add(stored==null?v:stored.withPayload(null)); }
        return result;
    }
    private boolean networkAdmitted() { try { port.requireMutable();port.requireStorage(128*1024);return true; } catch(RuntimeException blocked) { return false; } }
    private void putCache(SourceVersionRecord version) { try { cache.put(version); } catch (java.io.IOException failure) { throw new IllegalStateException("Не удалось сохранить preview",failure); } }
    private SourceRecord requiredSource(String id){SourceRecord source=book.source(id);if(source==null||!book.isSourceActive(id))throw new IllegalArgumentException("Источник удалён или недоступен");return source;}
    private SourceItem requiredItem(String source,String key){SourceItem item=book.sourceItem(source,key);if(item==null)throw new IllegalArgumentException("Unknown item");return item;}
    private SourceTransport.Response fetchClosed(SourceProfiles45.Profile profile,boolean discovery,String url,SourceTransport.Kind kind,SourceTransport.Validators validators,RequestScope scope)throws SourceFailure{
        java.util.function.Predicate<String> previous=scope.targetPolicy();scope.targetPolicy(discovery?profile::discoveryIdentity:target->profile.articleTarget(url,target));
        try{return transport.fetch(url,kind,validators,scope);}finally{scope.targetPolicy(previous);}
    }
    private SourceTransport.Response fetchSource(SourceRecord source,String url,SourceTransport.Kind kind,SourceTransport.Validators validators,RequestScope scope)throws SourceFailure{
        if(HtmlIndexReader.isIndex(source))return fetchHtmlTarget(source.url,validators,scope);
        SourceProfiles45.Profile p=SourceProfiles45.forSource(source);return p==null?transport.fetch(url,kind,validators,scope):fetchClosed(p,true,url,kind,validators,scope);
    }
    private static SourceTransport.Kind discoveryKind(SourceRecord source){SourceProfiles45.Profile p=SourceProfiles45.forSource(source);return p==null?("telegram_public".equals(source.type)||HtmlPageReader.isPage(source)||HtmlIndexReader.isIndex(source)?SourceTransport.Kind.HTML:SourceTransport.Kind.XML):p.discoveryKind();}
    private static boolean articleReader(SourceRecord source,String url){if(HtmlPageReader.isPage(source))return false;if(HtmlIndexReader.isIndex(source))return HtmlIndexReader.articleUrl(source.url,url);SourceProfiles45.Profile p=SourceProfiles45.forSource(source);return p==null?supportedArticle(url):!p.family.equals("wp_json")&&!p.family.equals("full_rss")&&p.articleUrl(url);}
    private FeedDocument parseConnected(SourceRecord source,SourceTransport.Response response,RequestScope scope)throws SourceFailure{
        SourceProfiles45.Profile closed=SourceProfiles45.forSource(source);if(closed!=null)return SourceReaders45.discover(closed,response,scope);
        if(HtmlPageReader.isPage(source))return new HtmlPageReader().read(response,scope);
        if(HtmlIndexReader.isIndex(source)){
            if(!HtmlIndexReader.sameTarget(source.url,response.finalUrl))throw new SourceFailure("HTML_INDEX_ARTICLE_IDENTITY");
            return HtmlIndexReader.inspect(response,DomBudgetGuard.parse(response,scope).document,scope,true);
        }
        if("telegram_public".equals(source.type))return new TelegramPreviewAdapter().parse(response,source.key.substring("telegram_public:".length()),scope);
        FeedDocument feed=new XmlFeedReader().parse(response,scope,rssPolicy(source.catalog,source.url));if(!source.type.equals(feed.type))throw new SourceFailure("FEED_TYPE_CHANGED");return feed;
    }
    private static XmlFeedReader.RssPolicy rssPolicy(boolean catalogue,String url) {
        return catalogue && "https://ria.ru/export/rss2/archive/index.xml".equals(url)
                ? XmlFeedReader.RssPolicy.RIA_CATALOGUE : XmlFeedReader.RssPolicy.STRICT_RSS2;
    }
    private ExtractionResult extract(SourceRecord source,FeedItem item,RequestScope scope)throws SourceFailure{
        return extract(source,item,scope,true);
    }
    ExtractionResult extract(SourceRecord source,FeedItem item,RequestScope scope,boolean allowTeaserFallback)throws SourceFailure{
        if (HtmlPageReader.isPage(source)) {
            scope.check();
            ExtractionResult captured = item.inline;
            if (captured == null || captured.publication == null || !HtmlPageReader.ADAPTER.equals(captured.adapterVersion)
                    || !Objects.equals(item.url, captured.publication.subjectUrl)) throw new SourceFailure("HTML_PAGE_UNSUPPORTED");
            return captured;
        }
        SourceProfiles45.Profile closed=SourceProfiles45.forSource(source);
        if(closed!=null){if(closed.family.equals("full_rss")){if(item.inline==null)throw new SourceFailure("RSS_CONTENT_MISSING");return item.inline;}if(!closed.articleUrl(item.url))throw new SourceFailure("PROFILE_ARTICLE_URL");SourceTransport.Response response=fetchClosed(closed,false,item.url,SourceTransport.Kind.HTML,SourceTransport.Validators.NONE,scope);return SourceReaders45.extract(closed,response,item,scope);}

        if(HtmlIndexReader.isIndex(source)){
            if(!HtmlIndexReader.articleUrl(source.url,item.url))throw new SourceFailure("HTML_INDEX_ARTICLE_IDENTITY");
            return HtmlIndexReader.article(source.url,item.url,fetchHtmlTarget(item.url,SourceTransport.Validators.NONE,scope),scope);
        }
        if(supportedArticle(item.url)){
            SourceTransport.Response response=transport.fetch(item.url,SourceTransport.Kind.HTML,SourceTransport.Validators.NONE,scope);
            String host=URI.create(response.finalUrl).getHost();
            if("www.interfax.ru".equals(host))return new InterfaxArticleAdapter().extract(response,scope);
            if("ria.ru".equals(host))return new RiaArticleAdapter().extract(response,scope);
            throw new SourceFailure("ARTICLE_REDIRECT_UNSUPPORTED");
        }
        if(genericFeedTeaser(source,item)){
            try{return HtmlIndexReader.article(source.url,item.url,fetchHtmlTarget(item.url,SourceTransport.Validators.NONE,scope),scope);}
            catch(SourceFailure failure){
                // Only a readable, public excerpt from this very feed can survive an unsupported HTML layout.
                boolean publicExcerpt=Set.of("HTML_PAGE_EMPTY","HTML_PAGE_ACCESS_REQUIRED","HTML_PAGE_UNSUPPORTED","HTML_INDEX_ARTICLE_IDENTITY","NETWORK_ERROR","TIMEOUT").contains(failure.reason)
                        || failure instanceof HttpStatusFailure;
                if(!allowTeaserFallback||item.inline==null||!item.inline.canEnqueue()||!publicExcerpt)throw failure;
                scope.check();
                ExtractionResult e=item.inline;
                return new ExtractionResult(e.body,e.providerTitle,e.displayTitle,"summary","ARTICLE_UNAVAILABLE_FEED_EXCERPT",e.method,e.adapterVersion);
            }
        }
        if(item.inline!=null)return item.inline;throw new SourceFailure("HEADLINE_ONLY_NO_TEXT");
    }
    private SourceTransport.Response fetchHtmlTarget(String selected,SourceTransport.Validators validators,RequestScope scope)throws SourceFailure{
        java.util.function.Predicate<String> previous=scope.targetPolicy();
        scope.targetPolicy(url->HtmlIndexReader.sameTarget(selected,url)&&(previous==null||previous.test(url)));
        try{return transport.fetch(selected,SourceTransport.Kind.HTML,validators,scope);}
        finally{scope.targetPolicy(previous);}
    }
    private static boolean customFeedArticle(SourceRecord source,String url){
        return !source.catalog&&("rss".equals(source.type)||"atom".equals(source.type))&&HtmlIndexReader.articleUrl(source.url,url);
    }
    private static boolean genericFeedTeaser(SourceRecord source,FeedItem item){
        if(!customFeedArticle(source,item.url)||supportedArticle(item.url))return false;
        return item.inline==null||"summary".equals(item.inline.completeness)||"headline_only".equals(item.inline.completeness)
                ||"DESCRIPTION_COMPLETENESS_UNKNOWN".equals(item.inline.reason);
    }
    private static boolean genericHtmlVersion(SourceRecord source,String url,String adapter){
        return (HtmlPageReader.ADAPTER.equals(adapter)||HtmlPageReader.PREVIOUS_ADAPTER.equals(adapter))
                &&(HtmlPageReader.isPage(source)||HtmlIndexReader.isIndex(source)||customFeedArticle(source,url));
    }
    private static boolean supportedArticle(String url){if(url==null)return false;try{URI u=URI.create(url);return "https".equals(u.getScheme())&&("www.interfax.ru".equals(u.getHost())||"ria.ru".equals(u.getHost()));}catch(IllegalArgumentException invalid){return false;}}
    private static String adapter(String url){try{String host=URI.create(url).getHost();if("www.interfax.ru".equals(host))return InterfaxArticleAdapter.VERSION;if("ria.ru".equals(host))return RiaArticleAdapter.VERSION;}catch(IllegalArgumentException ignored){}return "feed-generic-v1";}
    private static boolean isXml(SourceTransport.Response r){return r.mime!=null&&(r.mime.contains("xml"));}
    private static boolean aliasesOverlap(SourceRecord a,SourceRecord b){
        if(a.key.equals(b.key)||a.aliases.contains(b.key)||b.aliases.contains(a.key))return true;
        for(String alias:a.aliases)if(b.aliases.contains(alias))return true;return false;
    }
    private static SourceRecord confirmedRedirect(SourceRecord source,String finalUrl)throws SourceFailure{
        SourceProfiles45.Profile closed=SourceProfiles45.forSource(source);
        if(closed!=null){String finalKey=SafeHttpTransport.normalizeUrl(finalUrl);if(!closed.discoveryIdentity(finalKey))throw new SourceFailure("PROFILE_REDIRECT_OUTSIDE_CONTRACT");List<String> aliases=new ArrayList<>(source.aliases);if(!aliases.contains(source.key))aliases.add(source.key);if(!aliases.contains(finalKey))aliases.add(finalKey);return closed.bind(new SourceRecord(source.id,source.stationId,source.type,finalKey,source.name,finalKey,source.adapterId,source.adapterVersion,aliases,source.enabled,source.catalog,source.etag,source.lastModified,source.lastSuccess,source.lastError));}

        if("telegram_public".equals(source.type))return source;
        String canonical=SafeHttpTransport.normalizeUrl(finalUrl);List<String> aliases=new ArrayList<>(source.aliases);if(!aliases.contains(source.key))aliases.add(source.key);if(!aliases.contains(canonical))aliases.add(canonical);
        return new SourceRecord(source.id,source.stationId,source.type,canonical,source.name,canonical,source.adapterId,ChannelProfile.forRoute(source.adapterVersion,source.key,canonical),aliases,source.enabled,source.catalog,source.etag,source.lastModified,source.lastSuccess,source.lastError);
    }
    static String identity(FeedItem item){
        if(item.providerId!=null&&!item.providerId.isEmpty())return "id:"+SourceLimits.sha256(item.providerId);
        if(item.url!=null&&!item.url.isEmpty())return "url:"+SourceLimits.sha256(item.url);
        return "fallback:"+SourceLimits.sha256(item.title+"\u0000"+Objects.toString(item.publishedRaw,"")+"\u0000"+(item.inline==null?"":item.inline.body));
    }
    private static boolean matches(SourceItem item,FeedItem entry){
        if(item.itemKey.equals(identity(entry)))return true;
        if(entry.providerId!=null&&!entry.providerId.isEmpty()) {
            if(entry.providerId.equals(item.providerId)||item.aliases.contains(entry.providerId))return true;
        }
        return entry.url!=null&&(entry.url.equals(item.url)||item.aliases.contains(entry.url));
    }
    private static Batch prepareBatch(SourceRecord source,FeedDocument feed,List<SourceItem> known,int loadedIndex,ExtractionResult loaded){
        List<SourceItem> items=new ArrayList<>();List<SourceVersionRecord> versions=new ArrayList<>();int newCount=0;Set<String> seen=new HashSet<>();
        for(int index=0;index<feed.items.size();index++){
            FeedItem entry=feed.items.get(index);SourceItem previous=null;String key=identity(entry);
            for(SourceItem old:known)if(old.itemKey.equals(key)){previous=old;break;}
            if(previous==null)for(SourceItem old:known)if(matches(old,entry)){previous=old;key=old.itemKey;break;}
            if(!seen.add(key))continue;if(previous==null&&newCount++>=SourceLimits.MAX_NEW_ITEMS)continue;
            List<String> aliases=previous==null?new ArrayList<>():new ArrayList<>(previous.aliases);
            if(previous!=null){for(String old:new String[]{previous.providerId,previous.url})if(old!=null&&!old.isEmpty()&&!aliases.contains(old)){if(aliases.size()>=16)throw new IllegalArgumentException("Item alias limit");aliases.add(old);}}
            SourceItem item=SourceItem.create(source.id,key,entry.providerId,entry.url,entry.title.length()>2000?SemanticTextNormalizer.displayTitle(entry.title):entry.title,
                    entry.publishedAtMillis==null?-1:entry.publishedAtMillis,entry.updatedAtMillis==null?-1:entry.updatedAtMillis,previous==null?System.currentTimeMillis():previous.firstSeenAt,entry.publishedRaw,entry.updatedRaw);
            if(previous!=null)item=new SourceItem(item.sourceId,item.itemKey,item.articleId,item.providerId,item.url,item.title,aliases,item.publishedAt,item.updatedAt,item.firstSeenAt,previous.latestVersionKey,previous.read,previous.everListened,previous.lastViewedVersion,item.publishedRaw,item.updatedRaw,previous.statusHint,previous.reasonHint,previous.methodHint);
            ExtractionResult extraction=index==loadedIndex?loaded:entry.inline;
            if(extraction!=null){item=item.withHints(extraction.completeness,extraction.reason,extraction.method);
                if(extraction.canEnqueue()&&(index==loadedIndex||!genericFeedTeaser(source,entry)))try{SourceVersionRecord version=version(source,item,extraction);versions.add(index==loadedIndex?version:version.withPayload(null));item=item.withLatest(version.recordKey);}catch(SourceFailure|IllegalArgumentException limit){item=item.withHints("unavailable","TEXT_OR_TITLE_LIMIT",extraction.method);}
            }
            items.add(item);
        }
        return new Batch(items,versions);
    }
    static SourceVersionRecord version(SourceRecord source,SourceItem item,ExtractionResult extracted)throws SourceFailure{
        if(HtmlPageReader.isPage(source)||HtmlIndexReader.isIndex(source)||genericHtmlVersion(source,item.url,extracted.adapterVersion)){if(extracted.publication==null||!HtmlPageReader.ADAPTER.equals(extracted.adapterVersion)||!HtmlIndexReader.sameTarget(item.url,extracted.publication.subjectUrl))throw new SourceFailure("HTML_PAGE_UNSUPPORTED");return SourceVersionRecord.source45(source,item,extracted,extracted.publication,SourcePublication45.Context.UNKNOWN_MANUAL,System.currentTimeMillis());}
        if(extracted.publication!=null){SourceProfiles45.Profile closed=SourceProfiles45.forSource(source);if(closed==null||!closed.configSha.equals(extracted.publication.profileConfigSha))throw new SourceFailure("PROFILE_SNAPSHOT_MISMATCH");MaterialIdentity45.require(closed,item.url,extracted.publication.subjectUrl);if(closed.family.equals("wp_json")&&!Objects.equals(item.providerId,extracted.publication.subjectId))throw new SourceFailure("WP_SUBJECT_ID_MISMATCH");SourceRecord bound=closed.bind(source);return SourceVersionRecord.source45(bound,item,extracted,extracted.publication,new SourceAdmission45(SourceClock.SYSTEM).context(extracted.publication,true),System.currentTimeMillis());}
        extracted=ChannelProfile.snapshot(source,extracted);
        SourceArticleFramer.Framed frame=SourceArticleFramer.frame(source.name,extracted);
        return new SourceVersionRecord(source.id,item.itemKey,item.articleId,frame.versionHash,SemanticTextNormalizer.displayTitle(extracted.displayTitle),extracted.providerTitle,
                SemanticTextNormalizer.normalize(source.name).replace('\n',' '),item.url,extracted.completeness,extracted.reason,extracted.method,extracted.adapterVersion,SemanticTextNormalizer.VERSION,SourceArticleFramer.VERSION,frame.bodyHash,frame.spokenText,frame.bodyStart,frame.bodyEnd,System.currentTimeMillis());
    }
    private static String reason(Exception error){return error instanceof SourceFailure?((SourceFailure)error).reason:error instanceof IllegalArgumentException?"INVALID_OR_LIMIT":error instanceof IllegalStateException?"STORAGE_OR_LIMIT":"SOURCE_UNAVAILABLE";}
    private static void requirePageBatch(SourceRecord source,Batch batch){if(HtmlPageReader.isPage(source)&&(batch.items.size()!=1||batch.versions.size()!=1||batch.versions.get(0).spokenText==null))throw new IllegalArgumentException("HTML page text cannot be saved");}
    private static final class LoadedText { final SourceVersionRecord version;final boolean cached;LoadedText(SourceVersionRecord v,boolean cached){version=v;this.cached=cached;} }
    private static final class Batch{final List<SourceItem> items;final List<SourceVersionRecord> versions;Batch(List<SourceItem>i,List<SourceVersionRecord>v){items=i;versions=v;}}
    private static final class Refresh{final SourceRecord source;final FeedDocument feed;final Batch batch;final SourceTransport.Response response;Refresh(SourceRecord s,FeedDocument f,Batch b,SourceTransport.Response r){source=s;feed=f;batch=b;response=r;}}
    @Override public void close(){synchronized(this){if(armedDwDiagnostic!=null){armedDwDiagnostic.close();armedDwDiagnostic=null;}}cancelBatch(false);bulkPreviewRevision++;closed=true;for(RequestScope scope:operations.values())scope.cancel();operations.clear();owners.clear();workers.shutdownNow();if(transport instanceof AutoCloseable)try{((AutoCloseable)transport).close();}catch(Exception ignored){}}
}

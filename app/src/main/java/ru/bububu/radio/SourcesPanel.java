package ru.bububu.radio;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.*;
import android.view.View;
import java.text.DateFormat;
import java.util.*;
import ru.bububu.radio.core.NoteBook;
import ru.bububu.radio.sources.*;

/** News selection is independent of the station currently being read by radio. */
@SuppressLint("ViewConstructor")
final class SourcesPanel extends LinearLayout {
    private static final Map<RadioActivity,List<OpenReader>> readers=new WeakHashMap<>();
    private static final class OpenReader {
        final String source,item;final java.lang.ref.WeakReference<AlertDialog> dialog;
        OpenReader(String source,String item,AlertDialog dialog){this.source=source;this.item=item;this.dialog=new java.lang.ref.WeakReference<>(dialog);}
    }
    static String sourceBadge(SourceRecord source){return source==null?"":HtmlPageReader.isPage(source)?HtmlPageReader.WARNING:HtmlIndexReader.isIndex(source)?HtmlIndexReader.WARNING:source.catalog?"Каталог":"Пользовательский источник · Источник не проверен командой. Возможны ошибки загрузки и неполный текст";}
    static void invalidateReaders(RadioActivity activity,RadioService owner){
        List<OpenReader> open=readers.get(activity);if(open==null)return;
        for(OpenReader r:new ArrayList<>(open)){
            AlertDialog d=r.dialog.get();
            if(d==null||!d.isShowing()){open.remove(r);continue;}
            if(owner==null||owner.notesBook()==null||!owner.notesBook().isSourceActive(r.source)||owner.notesBook().isItemDeleted(r.source,r.item))d.dismiss();
        }
        if(open.isEmpty())readers.remove(activity);
    }
    private final RadioActivity activity;
    private String selectedSource; // null=ALL; independent of source-download checkboxes
    private Set<String> resultRecords;
    private boolean explicitSource;
    private final LinearLayout list;private final TextView message;
    private SourceCoordinator.BulkSnapshot snapshot;private boolean loading,showUnloaded;private long ticket,viewTicket;private String summary="";
    SourcesPanel(RadioActivity a){super(a);activity=a;setOrientation(VERTICAL);setPadding(dp(20),0,dp(20),dp(32));
        message=UiStyle.label(a,"",14,true);message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);message.setVisibility(GONE);addView(message);list=UiStyle.column(a);addView(list);
    }
    void invalidatePending(){loading=false;++ticket;++viewTicket;}
    void setFilter(String id){if(!Objects.equals(selectedSource,id))summary="";selectedSource=id;explicitSource=false;resultRecords=null;snapshot=null;loading=false;showUnloaded=false;++ticket;++viewTicket;}
    void openSource(String id){
        setFilter(id);explicitSource=true;showUnloaded=true;summary="";
    }
    void openResult(Set<String> keys){
        setFilter(null);resultRecords=new LinkedHashSet<>(keys);summary="";
    }
    void openSaved(RadioService owner,String key){saved(owner,key);}
    void refresh(){
        UiStyle.themeTree(this);
        RadioService owner=activity.serviceForNotes();if(owner==null||owner.notesBook()==null||owner.sources()==null){setMessage("Подключение…");return;}
        invalidateReaders(activity,owner);
        if(selectedSource!=null&&!owner.notesBook().isSourceActive(selectedSource)){
            if(explicitSource){
                list.removeAllViews();setMessage("Этот источник уже недоступен.");
                list.addView(button("Все сохранённые новости",activity::showNewsAll));return;
            }
            setFilter(null);
        }
        render(owner);
        if(loading||owner.sources().batchRunning())return;
        loading=true;long epoch=++ticket;String filter=selectedSource;render(owner);
        try{owner.sources().prepareBulkSnapshot(filter,result->{
            if(activity.serviceForNotes()!=owner||epoch!=ticket)return;loading=false;
            if(result.successful()){snapshot=result.value;render(owner);}else{render(owner);setMessage(result.failure.message(new AndroidSourceClock()));}
        });}catch(RuntimeException unavailable){loading=false;render(owner);setMessage("Список сохранённых текстов сейчас недоступен. Ваши данные сохранены.");}
    }
    private void render(RadioService owner){
        list.removeAllViews();NoteBook book=owner.notesBook();List<SourceRecord> sources=book.sources();setMessage(summary);
        if(resultRecords!=null){
            list.addView(UiStyle.heading(activity,"Материалы этой загрузки",20));
            int shown=0;
            if(snapshot!=null)for(SourceVersionRecord version:snapshot.downloaded){
                if(!resultRecords.contains(version.recordKey))continue;
                SourceRecord source=book.source(version.sourceId);
                SourceItem item=book.sourceItem(version.sourceId,version.itemKey);
                if(source==null||item==null||!version.recordKey.equals(item.latestVersionKey))continue;
                addItemCard(owner,source,item,version);shown++;
            }
            if(snapshot!=null && shown<resultRecords.size())
                list.addView(UiStyle.label(activity,"Некоторые версии уже изменились или недоступны.",14,true));
            UiStyle.spaced(list,button("Все сохранённые новости",activity::showNewsAll),12);return;
        }
        List<String> names=new ArrayList<>(),ids=new ArrayList<>();names.add("Все источники");ids.add(null);for(SourceRecord source:sources){names.add(source.name);ids.add(source.id);}
        Spinner filter=new Spinner(activity);filter.setMinimumHeight(dp(48));filter.setContentDescription("Фильтр новостей");filter.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,names));
        int index=ids.indexOf(selectedSource);filter.setSelection(Math.max(0,index));filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int i,long id){if(!Objects.equals(selectedSource,ids.get(i))){setFilter(ids.get(i));refresh();}}});UiStyle.spaced(list,filter,4);
        SourceRecord scope=selectedSource==null?null:book.source(selectedSource);
        if(scope!=null){
            UiStyle.spaced(list,UiStyle.heading(activity,scope.name,18),12);
            Button fetch=button("Загрузить новости этого источника",()->activity.downloadOneSource(owner,scope.id));
            fetch.setEnabled(scope.enabled && !owner.sources().batchRunning() && !owner.canAddDownloaded() && !owner.addingDownloaded());
            UiStyle.spaced(list,fetch,8);
            if(!scope.enabled)list.addView(UiStyle.label(activity,"Обновление отключено в настройках источника.",13,true));
            else if(owner.canAddDownloaded()||owner.sources().batchRunning())UiStyle.spaced(list,button("Открыть текущий результат загрузки",activity::showDownloadScreen),8);
        }
        int n=snapshot==null?0:snapshot.versions.size(),downloaded=snapshot==null?0:snapshot.downloaded.size();boolean running=owner.sources().batchRunning(),waiting=loading;
        boolean allCurrentQueued=downloaded>0;if(snapshot!=null)for(SourceVersionRecord value:snapshot.downloaded){SourceRecord source=book.source(value.sourceId);NoteBook.Material material=source==null?null:book.material(source.stationId,value.articleId);if(material==null||!Objects.equals(material.sourceVersionKey,value.recordKey)){allCurrentQueued=false;break;}}
        SourceCoordinator.DownloadProgress batch=owner.downloadProgress();
        boolean downloading=running&&batch!=null&&!batch.finished;
        String bulkLabel=running?(downloading?"Загрузка выполняется…":"Добавление выполняется…"):waiting?"Проверяем загруженные тексты…":snapshot==null?"Проверить сохранённые тексты":n>0?"Добавить в эфир ("+n+")":downloaded==0?"Загрузить из источников":allCurrentQueued?"Всё добавлено в эфир":"Выбранные версии уже в эфире";
        Button bulk=button(bulkLabel,()->{if(!running&&!waiting&&snapshot!=null&&downloaded==0)activity.showSourceManagement();else confirmBulk(owner);});bulk.setContentDescription("Действие для загруженных новостей");if(!running&&!waiting&&n>0)UiStyle.primary(bulk);bulk.setEnabled(!running&&!waiting&&(snapshot==null||n>0||downloaded==0));if(!explicitSource||downloaded>0||waiting||snapshot==null)UiStyle.spaced(list,bulk,8);
        if(!waiting&&snapshot!=null)list.addView(UiStyle.label(activity,"Доступно на телефоне: "+downloaded+" · уже в эфире: "+snapshot.alreadyQueued,13,true));
        int globalNews=globalNewsCount(book);Button deleteAll=button("Удалить новости всех источников",()->confirmDeleteAllNews(owner));deleteAll.setEnabled(globalNews>0);deleteAll.setContentDescription("Удалить новости из всех источников, независимо от фильтра");if(!explicitSource)UiStyle.spaced(list,deleteAll,8);
        if(owner.sources().batchRunning())UiStyle.spaced(list,button(downloading?"Отменить загрузку":"Отменить добавление",()->{if(downloading)owner.cancelSourceDownload(batch.runId);else owner.sources().cancelBatch();refresh();}),8);
        if(!summary.isEmpty())UiStyle.spaced(list,button("Открыть эфир",activity::openAir),8);
        Map<String,SourceVersionRecord> local=new LinkedHashMap<>();if(snapshot!=null)for(SourceVersionRecord version:snapshot.downloaded)local.put(version.recordKey,version);
        if(!waiting&&!running&&snapshot!=null&&local.isEmpty())list.addView(UiStyle.label(activity,"Загруженных текстов пока нет.",15,true));
        Map<String,List<SourceItem>> downloadedItems=new LinkedHashMap<>(),headers=new LinkedHashMap<>();int totalHeaders=0;
        for(SourceRecord source:sources){if(selectedSource!=null&&!selectedSource.equals(source.id))continue;
            List<SourceItem> items=new ArrayList<>(book.sourceItems(source.id));items.sort(Comparator.comparingLong((SourceItem i)->i.publishedAt>=0?i.publishedAt:i.firstSeenAt).reversed().thenComparing(i->i.itemKey));
            List<SourceItem> cachedItems=new ArrayList<>(),missing=new ArrayList<>();for(SourceItem item:items){if(item.latestVersionKey!=null&&local.containsKey(item.latestVersionKey))cachedItems.add(item);else missing.add(item);}
            downloadedItems.put(source.id,cachedItems);headers.put(source.id,missing);totalHeaders+=missing.size();
        }
        for(SourceRecord source:sources){List<SourceItem> items=downloadedItems.get(source.id);if(items==null||items.isEmpty())continue;
            list.addView(UiStyle.heading(activity,source.name,18));for(SourceItem item:items)addItemCard(owner,source,item,local.get(item.latestVersionKey));
        }
        if(totalHeaders>0){
            UiStyle.spaced(list,button((showUnloaded?"Скрыть":"Показать")+" заголовки без текста ("+totalHeaders+")",()->{showUnloaded=!showUnloaded;render(owner);}),12);
            list.addView(UiStyle.label(activity,"Заголовки без текста не входят в эфир.",12,true));
            if(showUnloaded){int limit=Math.min(20,totalHeaders),shown=0;list.addView(UiStyle.label(activity,"Заголовки без текста · показано "+limit+" из "+totalHeaders+" · не более 20 в этом фильтре",14,true));
                for(SourceRecord source:sources){List<SourceItem> items=headers.get(source.id);if(items==null||items.isEmpty()||shown>=limit)continue;list.addView(UiStyle.heading(activity,source.name,18));
                    for(SourceItem item:items){if(shown>=limit)break;addItemCard(owner,source,item,null);shown++;}
                }
            }
        }
    }
    private static int globalNewsCount(NoteBook book){int count=0;for(SourceRecord source:book.sources())count+=book.sourceItems(source.id).size();return count;}
    private void confirmDeleteAllNews(RadioService owner){
        if(activity.serviceForNotes()!=owner||owner.notesBook()==null)return;NoteBook book=owner.notesBook();int count=globalNewsCount(book);if(count==0)return;
        long revision=book.revision(),authority=book.authorityEpoch(),view=viewTicket;
        new AlertDialog.Builder(activity).setTitle("Удалить все новости?")
                .setMessage("Из всех источников: "+count+" новостей, независимо от фильтра.\nНовости исчезнут из списка, эфира и отдельного чтения без сети. Источники, ваши заметки и связанные с ними исходные тексты останутся.")
                .setPositiveButton("Удалить все новости",(d,w)->{
                    if(activity.serviceForNotes()!=owner||activity.isFinishing()||view!=viewTicket)return;
                    if(owner.notesBook()!=book||book.revision()!=revision||book.authorityEpoch()!=authority){toast("Список новостей изменился. Откройте подтверждение заново.");return;}
                    try{boolean changed=owner.deleteAllNews();invalidateReaders(activity,owner);snapshot=null;loading=false;++ticket;summary=changed?"Новости всех источников удалены. Ваши заметки сохранены.":"Новостей для удаления нет.";refresh();}
                    catch(SourceCoordinator.ProjectionPendingException pending){invalidateReaders(activity,owner);setMessage("Удаление сохранено; отображение требует восстановления. Откройте приложение снова.");}
                    catch(RuntimeException failure){setMessage("Удаление всех новостей не подтверждено. Данные сохранены; требуется восстановление.");}
                }).setNegativeButton("Отмена",null).show();
    }
    private void addItemCard(RadioService owner,SourceRecord source,SourceItem item,SourceVersionRecord loaded){
        NoteBook book=owner.notesBook();SourceVersionRecord current=item.latestVersionKey==null?null:book.sourceVersion(item.latestVersionKey);NoteBook.Material selected=selected(book,source.stationId,item.articleId);
        LinearLayout card=UiStyle.card(activity);LinearLayout heading=new LinearLayout(activity);heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title=UiStyle.heading(activity,item.title,16);title.setMaxLines(3);title.setEllipsize(android.text.TextUtils.TruncateAt.END);heading.addView(title,new LayoutParams(0,-2,1));
        Button more=button("⋮",()->showItemActions(owner,source,item,selected));more.setTag("news-more");UiStyle.overflow(more);more.setTextSize(24);more.setPadding(0,0,0,0);more.setMinimumWidth(dp(48));more.setMinWidth(dp(48));more.setContentDescription("Другие действия с новостью · "+item.title);heading.addView(more,new LayoutParams(dp(48),dp(48)));card.addView(heading);
        card.addView(UiStyle.label(activity,(loaded==null?"Текст ещё не загружен":"Загружено · "+ChannelProfile.label(loaded)+" · "+quality(loaded.completeness))+(selected==null?"":current!=null&&Objects.equals(selected.sourceVersionKey,current.recordKey)?" · В эфире":" · В эфире сохранён прежний текст")+(item.read?" · Прочитано":""),12,true));
        if(loaded!=null&&loaded.publication!=null)card.addView(UiStyle.label(activity,ChannelProfile.publicationLabel(loaded),12,false));
        if(!source.catalog)card.addView(UiStyle.label(activity,sourceBadge(source),12,true));
        LinearLayout actions=new LinearLayout(activity);
        Button reader=button(loaded==null?"Загрузить":"Читать",()->{if(loaded!=null)showReader(activity,owner,loaded);else load(owner,source,item);});reader.setTag("news-reader");reader.setContentDescription(loaded==null?"Загрузить текст новости":"Читать сохранённый текст новости");SourceRetryUi.bind(reader,loaded==null?"Загрузить":"Читать",()->owner.sources().readerFailure(source.id,item.itemKey),new AndroidSourceClock());LayoutParams readParams=new LayoutParams(0,-2,1);readParams.rightMargin=dp(8);actions.addView(reader,readParams);
        boolean currentQueued=selected!=null&&loaded!=null&&Objects.equals(selected.sourceVersionKey,loaded.recordKey);
        Button add=button(currentQueued?"✓ В эфире":"В эфир",()->{if(loaded!=null)enqueueOne(owner,loaded);});if(!currentQueued)UiStyle.primary(add);add.setEnabled(loaded!=null&&!currentQueued);add.setContentDescription(currentQueued?"Текст этой новости уже выбран в эфир":loaded==null?"Для добавления в эфир сначала загрузите текст":"Добавить этот сохранённый текст в эфир");actions.addView(add,new LayoutParams(0,-2,1));UiStyle.spaced(card,actions,8);if(currentQueued && loaded!=null)UiStyle.spaced(card,
                button("Открыть эту новость в радио",()->{if(!activity.openNewsInRadio(owner,loaded))toast("Эта версия уже недоступна в эфире.");}),8);
        UiStyle.spaced(list,card,10);
    }
    private void showItemActions(RadioService owner,SourceRecord source,SourceItem item,NoteBook.Material selected){
        if(activity.serviceForNotes()!=owner||owner.notesBook()==null||!owner.notesBook().isSourceActive(source.id)||owner.notesBook().isItemDeleted(source.id,item.itemKey))return;
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        labels.add(item.read?"Снять отметку «прочитано»":"Отметить прочитанным");actions.add(()->run(()->owner.sources().setRead(source.id,item.itemKey,!item.read)));
        if(selected!=null){labels.add("Убрать выбранный текст из эфира");actions.add(()->run(()->owner.sources().remove(source.id,item.articleId)));}
        for(SourceVersionRecord version:owner.notesBook().sourceVersions(source.id,item.itemKey))if(!Objects.equals(item.latestVersionKey,version.recordKey)&&version.spokenText!=null){labels.add("Ранее сохранённый текст");actions.add(()->saved(owner,version.recordKey));}
        labels.add("Удалить новость");actions.add(()->confirmDeleteItem(activity,owner,source.id,item.itemKey,item.title));
        new AlertDialog.Builder(activity).setTitle("Действия с новостью").setItems(labels.toArray(new String[0]),(d,index)->{if(activity.serviceForNotes()==owner)actions.get(index).run();}).setNegativeButton("Отмена",null).show();
    }
    private static boolean inAir(RadioService owner,SourceVersionRecord version){
        SourceRecord source=owner.notesBook().source(version.sourceId);
        NoteBook.Material material=source==null?null:owner.notesBook().material(source.stationId,version.articleId);
        return material!=null && version.recordKey.equals(material.sourceVersionKey) && version.versionKey.equals(material.version);
    }
    private void enqueueOne(RadioService owner,SourceVersionRecord value){
        Runnable save=()->{if(activity.serviceForNotes()!=owner)return;try{
            SourceItem item=owner.notesBook().sourceItem(value.sourceId,value.itemKey);
            if(item==null||!Objects.equals(item.latestVersionKey,value.recordKey)){toast("Текст изменился. Откройте новости заново.");return;}
            owner.sources().enqueue(value,!"full".equals(value.completeness));summary="Текст добавлен. Нажмите «Слушать» на вкладке «Радио».";refresh();
        }catch(SourceCoordinator.ProjectionPendingException pending){toast("Текст сохранён. Откройте приложение снова для восстановления эфира.");}
        catch(RuntimeException failure){toast("Добавление не подтверждено. Проверьте место и выбранную версию.");}};
        if("full".equals(value.completeness))save.run();else new AlertDialog.Builder(activity).setTitle("Добавить доступный текст?").setMessage("Полнота текста не подтверждена. В эфир будет добавлен именно показанный доступный текст.").setPositiveButton("Добавить",(d,w)->save.run()).setNegativeButton("Отмена",null).show();
    }
    private void confirmBulk(RadioService owner){
        if(activity.serviceForNotes()!=owner||loading||owner.sources()==null||owner.sources().batchRunning())return;
        loading=true;long epoch=++ticket,view=viewTicket;String filter=selectedSource;setMessage("Проверяем загруженные тексты…");
        try{owner.sources().prepareBulkSnapshot(filter,result->{
            if(!bulkViewCurrent(owner,view,filter)||epoch!=ticket)return;loading=false;
            if(!result.successful()){render(owner);setMessage(result.failure.message(new AndroidSourceClock()));return;}
            SourceCoordinator.BulkSnapshot consent=result.value;snapshot=consent;render(owner);
            if(consent.versions.isEmpty()){setMessage("Новых загруженных текстов для добавления нет. Уже выбранные тексты остаются в эфире.");return;}
            showBulkConsent(owner,consent,epoch,view,filter);
        });render(owner);setMessage("Проверяем загруженные тексты…");}catch(RuntimeException failure){loading=false;render(owner);setMessage("Не удалось проверить локальные тексты. Данные сохранены; попробуйте снова.");}
    }
    private boolean bulkViewCurrent(RadioService owner,long view,String filter){return activity.serviceForNotes()==owner&&!activity.isFinishing()&&view==viewTicket&&Objects.equals(filter,selectedSource)&&(filter==null||owner.notesBook().isSourceActive(filter));}
    private void showBulkConsent(RadioService owner,SourceCoordinator.BulkSnapshot consent,long epoch,long view,String filter){
        String message="Фильтр: "+(filter==null?"Все источники":owner.notesBook().source(filter).name)+"\nПолных: "+consent.fullCount+" · с ограничениями: "+consent.limitedCount+"\nВсего: "+consent.versions.size()+" текстов · "+consent.totalCharacters+" знаков. Уже выбранные версии сохранятся.";
        AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle("Добавить загруженные тексты в эфир?").setMessage(message).setNegativeButton("Отмена",null);
        if(consent.limitedCount>0){dialog.setPositiveButton("Добавить все доступные",(d,w)->enqueue(owner,consent,true,epoch,view,filter));dialog.setNeutralButton("Только полные",(d,w)->enqueue(owner,consent,false,epoch,view,filter));}
        else dialog.setPositiveButton("Добавить в эфир",(d,w)->enqueue(owner,consent,false,epoch,view,filter));dialog.show();
    }
    private void retryBulkConsent(RadioService owner,long view,String filter){
        if(!bulkViewCurrent(owner,view,filter))return;
        new AlertDialog.Builder(activity).setTitle("Подтверждение устарело").setMessage("Список изменился или истёк срок подтверждения. Ничего не добавлено. Проверьте тексты и подтвердите новый список заново.").setPositiveButton("Проверить заново",(d,w)->{if(bulkViewCurrent(owner,view,filter))confirmBulk(owner);}).setNegativeButton("Отмена",null).show();
    }
    private void enqueue(RadioService owner,SourceCoordinator.BulkSnapshot consent,boolean limited,long epoch,long view,String filter){
        if(!bulkViewCurrent(owner,view,filter))return;if(epoch!=ticket){retryBulkConsent(owner,view,filter);return;}
        try{owner.sources().enqueueBulk(consent,limited,progress->{if(activity.serviceForNotes()!=owner)return;summary="Добавлено: "+progress.added+" · уже в эфире: "+progress.alreadyQueued+" · пропущено: "+progress.skipped+" · ошибок: "+progress.errors;
            if(progress.cancelled)summary+="\nДобавление отменено. Сохранённые тексты остались.";if(progress.stopFailure!=null)summary+="\n"+progress.stopFailure.message(new AndroidSourceClock());
            if(progress.finished){snapshot=null;loading=false;++ticket;refresh();}else render(owner);
        });render(owner);}catch(IllegalStateException failure){if("Список изменился. Проверьте загруженные тексты снова".equals(failure.getMessage()))retryBulkConsent(owner,view,filter);else setMessage("Добавление не началось. Проверьте сохранение и состояние эфира.");}
        catch(RuntimeException failure){setMessage("Добавление не началось. Проверьте сохранение и состояние эфира.");}
    }
    private void load(RadioService service,SourceRecord source,SourceItem item){
        setMessage("Проверяем текст…");try{service.sources().loadText(source.id,item.itemKey,result->{if(activity.serviceForNotes()!=service)return;if(result.successful())showReader(activity,service,result.value);else toast(result.failure.message(new AndroidSourceClock()));refresh();});}catch(RuntimeException failure){toast("Текст недоступен");}
    }
    private void saved(RadioService service,String key){long view=viewTicket;try{service.sources().savedText(key,result->{if(activity.serviceForNotes()!=service||view!=viewTicket)return;if(result.successful())showReader(activity,service,result.value);else toast(result.failure.message(new AndroidSourceClock()));});}catch(RuntimeException failure){toast("Сохранённый текст недоступен");}}
    static void showReader(RadioActivity activity,RadioService owner,SourceVersionRecord version){showReader(activity,owner,version,null);}
    static void showReader(RadioActivity activity,RadioService owner,SourceVersionRecord version,String playbackInfo){
        owner.cancelPendingListen();
        if(activity.serviceForNotes()!=owner||owner.notesBook()==null||owner.notesBook().storageUncertain()||!owner.notesBook().isSourceActive(version.sourceId)||owner.notesBook().isItemDeleted(version.sourceId,version.itemKey))return;
        LinearLayout body=new LinearLayout(activity);body.setOrientation(VERTICAL);int pad=Math.round(18*activity.getResources().getDisplayMetrics().density);body.setPadding(pad,pad,pad,pad);
        if(playbackInfo!=null&&!playbackInfo.isEmpty())body.addView(UiStyle.label(activity,playbackInfo,13,true));
        TextView provenance=new TextView(activity);provenance.setText(version.sourceLabel+" · "+sourceBadge(owner.notesBook().source(version.sourceId))+"\n"+ChannelProfile.label(version)+" · "+quality(version.completeness)+" · сохранено "+date(version.receivedAt));body.addView(provenance);
        if(version.publication!=null)body.addView(UiStyle.label(activity,ChannelProfile.publicationLabel(version),13,false));
        TextView text=new TextView(activity);text.setText(version.body());text.setTextSize(16);text.setTextIsSelectable(true);body.addView(text);
        Button enqueue=UiStyle.button(activity,"public_preview_text".equals(version.method)?"В эфир: текст поста":"Добавить этот текст в эфир",()->{});UiStyle.primary(enqueue);
        Button read=new Button(activity);read.setAllCaps(false);read.setText("Изменить отметку «прочитано»");read.setOnClickListener(v->{if(activity.serviceForNotes()!=owner)return;try{SourceItem item=owner.notesBook().sourceItem(version.sourceId,version.itemKey);if(item==null)throw new IllegalStateException("Unknown source item");owner.sources().setRead(version.sourceId,version.itemKey,!item.read);}catch(RuntimeException failure){Toast.makeText(activity,"Отметка не сохранена",Toast.LENGTH_LONG).show();}});body.addView(read);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(version.displayTitle).setNegativeButton("Закрыть",null).create();
        if(inAir(owner,version))enqueue.setText("Открыть эту новость в радио");
        enqueue.setOnClickListener(v->{if(activity.serviceForNotes()!=owner)return;
            if(inAir(owner,version)){
                if(activity.openNewsInRadio(owner,version))dialog.dismiss();
                else Toast.makeText(activity,"Эта версия уже недоступна в эфире.",Toast.LENGTH_LONG).show();
                return;
            }
            SourceItem currentItem=owner.notesBook().sourceItem(version.sourceId,version.itemKey);
            if(owner.notesBook().sourceVersion(version.recordKey)==null&&currentItem!=null&&!Objects.equals(currentItem.latestVersionKey,version.recordKey)) {
                AlertDialog currentDialog=new AlertDialog.Builder(activity).setMessage("В источнике уже другой текст. Показанная версия больше не сохранена в списке. Открыть текущую версию?")
                    .setPositiveButton("Показать текущую",(d,w)->{
                        if(activity.isFinishing()||activity.serviceForNotes()!=owner||owner.sources()==null)return;
                        try { owner.sources().loadText(version.sourceId,version.itemKey,result->{
                            if(activity.serviceForNotes()!=owner||activity.isFinishing())return;
                            if(result.successful())showReader(activity,owner,result.value);else Toast.makeText(activity,result.failure.message(new AndroidSourceClock()),Toast.LENGTH_LONG).show();
                        }); } catch(RuntimeException stale){Toast.makeText(activity,"Источник изменился. Откройте список заново.",Toast.LENGTH_LONG).show();}
                    }).setNegativeButton("Отмена",null).create();
                currentDialog.setOnShowListener(d->SourceRetryUi.bind(currentDialog.getButton(AlertDialog.BUTTON_POSITIVE),"Показать текущую",
                        ()->owner.sources().readerFailure(version.sourceId,version.itemKey),new AndroidSourceClock()));
                currentDialog.show();return;
            }
            Runnable save=()->{try{owner.sources().enqueue(version,!"full".equals(version.completeness));enqueue.setText("Открыть эту новость в радио");}catch(SourceCoordinator.ProjectionPendingException pending){Toast.makeText(activity,"Текст сохранён. Повторите добавление или откройте приложение снова для восстановления эфира.",Toast.LENGTH_LONG).show();}catch(RuntimeException failure){Toast.makeText(activity,"Не удалось добавить: сохранение, предел или уже выбрана другая версия",Toast.LENGTH_LONG).show();}};
            if("full".equals(version.completeness))save.run();else new AlertDialog.Builder(activity).setMessage("Полнота текста не подтверждена. Сохранить именно этот доступный текст в эфир?")
                    .setPositiveButton("Сохранить доступный текст",(d,w)->save.run()).setNegativeButton("Отмена",null).show();});
        Button delete=new Button(activity);delete.setAllCaps(false);delete.setText("Удалить новость");delete.setOnClickListener(v->confirmDeleteItem(activity,owner,version.sourceId,version.itemKey,version.displayTitle));body.addView(delete);
        ScrollView scroll=new ScrollView(activity);scroll.addView(body);LinearLayout outer=UiStyle.column(activity);outer.setPadding(pad,0,pad,0);outer.addView(enqueue,new LayoutParams(-1,-2));
        if(version.url!=null)outer.addView(UiStyle.button(activity,"Оригинал",()->{try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(SafeHttpTransport.normalizeUrl(version.url))));}catch(Exception e){Toast.makeText(activity,"Ссылка недоступна",Toast.LENGTH_LONG).show();}}));
        outer.addView(scroll,new LayoutParams(-1,0,1));dialog.setView(outer);
        OpenReader reader=new OpenReader(version.sourceId,version.itemKey,dialog);readers.computeIfAbsent(activity,x->new ArrayList<>()).add(reader);
        dialog.setOnDismissListener(d->{List<OpenReader> open=readers.get(activity);if(open!=null){open.remove(reader);if(open.isEmpty())readers.remove(activity);}});PhoneInsets.prepare(dialog);dialog.show();dialog.getWindow().getDecorView().post(()->{if(dialog.isShowing())dialog.getWindow().setLayout(-1,Math.round(PhoneInsets.availableHeight(activity)*.88f));});UiStyle.themeTree(outer);
    }
    static void showLinkedReader(RadioActivity activity,RadioService owner,String noteId){
        if(activity.serviceForNotes()!=owner||owner.notesBook()==null)return;
        NoteBook.Note note=owner.notesBook().find(noteId);if(note==null||note.linkedSourceVersionKey==null)return;
        SourceVersionRecord v=owner.notesBook().sourceVersion(note.linkedSourceVersionKey);
        if(v==null||v.spokenText==null||!v.articleId.equals(note.linkedArticle)||!v.versionKey.equals(note.linkedVersion))return;
        boolean deleted=!owner.notesBook().isSourceActive(v.sourceId)||owner.notesBook().isItemDeleted(v.sourceId,v.itemKey);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(VERTICAL);int pad=Math.round(18*activity.getResources().getDisplayMetrics().density);body.setPadding(pad,pad,pad,pad);
        TextView info=new TextView(activity);info.setText((deleted?"Источник/новость удалены · контекст заметки\n":"Контекст заметки\n")+v.sourceLabel+"\n"+sourceBadge(owner.notesBook().source(v.sourceId))+"\n"+quality(v.completeness));body.addView(info);
        if(v.publication!=null)body.addView(UiStyle.label(activity,ChannelProfile.publicationLabel(v),13,false));
        TextView text=new TextView(activity);text.setText(v.body());text.setTextSize(16);text.setTextIsSelectable(true);body.addView(text);ScrollView scroll=new ScrollView(activity);scroll.addView(body);
        NoteBook.Note current=owner.notesBook().find(noteId);if(current==null||!Objects.equals(current.linkedSourceVersionKey,v.recordKey))return;
        new AlertDialog.Builder(activity).setTitle("Исходная новость · "+v.displayTitle).setView(scroll).setNegativeButton("Закрыть",null).show();
    }
    static void confirmDeleteSource(RadioActivity activity,RadioService owner,SourceRecord source){
        new AlertDialog.Builder(activity).setTitle("Удалить источник «"+source.name+"»?")
                .setMessage("Источник, его станция и все новости исчезнут из приложения. Связанные заметки и записи останутся. Если источник сейчас играет, чтение остановится; выберите другую станцию.")
                .setPositiveButton("Удалить",(d,w)->runDeletion(activity,owner,()->owner.sources().deleteSource(source.id)))
                .setNegativeButton("Отмена",null).show();
    }
    static void confirmDeleteItem(RadioActivity activity,RadioService owner,String sourceId,String itemKey,String title){
        new AlertDialog.Builder(activity).setTitle("Удалить новость?").setMessage(title+"\n\nНовость исчезнет из списка и эфира и не вернётся при обновлении этого источника. Связанные заметки и записи останутся. Если новость играет, чтение остановится.")
                .setPositiveButton("Удалить",(d,w)->runDeletion(activity,owner,()->owner.sources().deleteItem(sourceId,itemKey)))
                .setNegativeButton("Отмена",null).show();
    }
    private static void runDeletion(RadioActivity activity,RadioService owner,Runnable action){
        if(activity.serviceForNotes()!=owner||owner.sources()==null)return;
        try{action.run();invalidateReaders(activity,owner);if(owner.player().snapshot().selectionRequired)activity.chooseStationAfterDeletion();}
        catch(SourceCoordinator.ProjectionPendingException pending){invalidateReaders(activity,owner);Toast.makeText(activity,pending.getMessage(),Toast.LENGTH_LONG).show();}
        catch(RuntimeException failure){Toast.makeText(activity,"Сохранение удаления не подтверждено. Файлы сохранены; откройте приложение снова.",Toast.LENGTH_LONG).show();}
    }
    static String quality(String q){switch(q){case "full":return "Полный текст";case "summary":return "Краткий текст";case "unknown":return "Полнота неизвестна";case "headline_only":return "Только заголовок";default:return "Текст недоступен";}}
    private static NoteBook.Material selected(NoteBook book,String station,String article){for(NoteBook.Material m:book.materials())if(m.stationId.equals(station)&&m.articleId.equals(article))return m;return null;}
    private void run(Runnable action){try{action.run();refresh();}catch(RuntimeException failure){toast("Изменение не сохранено. Данные остаются на телефоне.");}}
    private static String date(long millis){return DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(millis));}
    private TextView label(String text,int size){return UiStyle.label(activity,text,size,false);}
    private Button button(String text,Runnable action){return UiStyle.button(activity,text,action);}
    private void setMessage(String text){message.setText(text);message.setVisibility(text==null||text.isEmpty()?GONE:VISIBLE);}
    private void toast(String text){Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

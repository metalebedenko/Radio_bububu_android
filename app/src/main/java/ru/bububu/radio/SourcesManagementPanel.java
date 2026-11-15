package ru.bububu.radio;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.*;
import java.util.*;
import ru.bububu.radio.core.NoteBook;
import ru.bububu.radio.sources.*;

/** Download selection is UI state, independent of enabled, news filter and playback. */
@android.annotation.SuppressLint("ViewConstructor")
final class SourcesManagementPanel extends LinearLayout {
    private final RadioActivity activity;private final LinearLayout list;private final TextView message;private final LinearLayout progressSlot;
    private android.app.AlertDialog settingsDialog;private ScrollView settingsScroll;private String settingsSource;private String summary="";
    SourcesManagementPanel(RadioActivity a){super(a);activity=a;setOrientation(VERTICAL);setPadding(dp(20),0,dp(20),dp(36));
        LinearLayout links=UiStyle.row(a);Button catalog=UiStyle.action(a,"Каталог","news",()->SourceCatalogDialog.show(activity));Button add=UiStyle.action(a,"Своя ссылка","plus",()->AddSourceDialog.show(activity));LayoutParams left=weight();left.rightMargin=dp(8);links.addView(catalog,left);links.addView(add,weight());addView(links);
        message=UiStyle.label(a,"",14,true);message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);message.setVisibility(GONE);addView(message);progressSlot=UiStyle.column(a);addView(progressSlot);list=UiStyle.column(a);addView(list);
    }
    private SharedPreferences prefs(){return activity.getSharedPreferences(UiStyle.PREFS,Context.MODE_PRIVATE);}
    private Set<String> selection(List<SourceRecord> sources){Set<String> known=new LinkedHashSet<>();for(SourceRecord s:sources)known.add(s.id);
        Set<String> selected;if(prefs().contains("batch_source_ids"))selected=new LinkedHashSet<>(prefs().getStringSet("batch_source_ids",Collections.emptySet()));else {selected=new LinkedHashSet<>();for(SourceRecord s:sources)if(s.enabled)selected.add(s.id);}selected.retainAll(known);return selected;
    }
    private void remember(Set<String> ids){RadioService owner=activity.serviceForNotes();if(owner!=null)owner.cancelPendingListen();prefs().edit().putStringSet("batch_source_ids",new HashSet<>(ids)).apply();}
    private int perSource(){int n=prefs().getInt("batch_per_source",3);return n==5||n==10||n==20?n:3;}
    void leaveView(){if(settingsDialog!=null)settingsDialog.dismiss();}
    void refresh(){UiStyle.themeTree(this);list.removeAllViews();RadioService owner=activity.serviceForNotes();if(owner==null||owner.notesBook()==null||owner.sources()==null){setMessage("Подключение источников…");return;}
        progressSlot.removeAllViews();if(owner.downloadProgress()!=null)UiStyle.spaced(progressSlot,UiStyle.link(activity,SourceDownloadUi.outcome(owner.downloadProgress()),"news",activity::showDownloadScreen),12);
        NoteBook book=owner.notesBook();List<SourceRecord> sources=book.sources();setMessage(summary);
        if(sources.isEmpty()){list.addView(UiStyle.label(activity,"Источников пока нет. Откройте «Каталог» или добавьте свою ссылку.",15,true));return;}
        Set<String> selected=selection(sources);int enabled=0;for(SourceRecord source:sources)if(source.enabled&&selected.contains(source.id))enabled++;
        UiStyle.spaced(list,UiStyle.heading(activity,"Для следующей загрузки",18),18);
        LinearLayout choose=UiStyle.row(activity);choose.addView(UiStyle.label(activity,"Выбрано: "+selected.size()+" из "+sources.size(),12,true),weight());Button all=button("Выбрать все",()->{Set<String> ids=new LinkedHashSet<>();for(SourceRecord source:book.sources())ids.add(source.id);remember(ids);refresh();}),none=button("Снять выбор",()->{remember(Collections.emptySet());refresh();});for(Button button:new Button[]{all,none}){button.setTextSize(12);button.setPadding(dp(6),dp(10),dp(6),dp(10));button.setMinWidth(0);button.setMinimumWidth(0);LayoutParams lp=new LayoutParams(dp(96),-2);lp.leftMargin=dp(6);choose.addView(button,lp);}UiStyle.spaced(list,choose,8);
        LinearLayout limit=UiStyle.row(activity);
        limit.addView(UiStyle.label(activity,"Материалов на источник",14,true),weight());Spinner size=new Spinner(activity);size.setMinimumHeight(dp(48));size.setPadding(dp(12),0,dp(12),0);size.setBackground(UiStyle.background(activity,0xffffffff,12));String[] options={"3","5","10","20"};size.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,options));size.setSelection(perSource()==3?0:perSource()==5?1:perSource()==10?2:3);size.setContentDescription("Материалов на источник: 3, 5, 10 или 20");
        size.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int i,long id){int value=new int[]{3,5,10,20}[i];if(value!=perSource()){owner.cancelPendingListen();prefs().edit().putInt("batch_per_source",value).apply();}}});limit.addView(size,new LayoutParams(dp(84),dp(48)));UiStyle.spaced(list,limit,8);
        Button download=button("Загрузить новости",()->start(owner));UiStyle.primary(download);download.setEnabled(enabled>0&&!owner.sources().batchRunning()&&!owner.canAddDownloaded()&&!owner.addingDownloaded());UiStyle.spaced(list,download,12);
        if(enabled==0)list.addView(UiStyle.label(activity,"Выберите хотя бы один источник с включённым обновлением.",13,true));if(owner.canAddDownloaded())list.addView(UiStyle.label(activity,owner.downloadReviewPending()
                ?"Откройте результат загрузки: часть текстов требует отдельного выбора в «Новостях»."
                :"Откройте результат загрузки, чтобы добавить тексты в эфир или оставить их на потом.",13,true));
        UiStyle.spaced(list,UiStyle.link(activity,"Сохранённые новости","news",activity::showNewsAll),6);
        UiStyle.divider(list,8);
        for(SourceRecord source:sources){LinearLayout row=UiStyle.row(activity);CheckBox box=new CheckBox(activity);box.setContentDescription("Выбрать источник: "+source.name);box.setMinWidth(dp(48));box.setMinimumHeight(dp(48));box.setChecked(selected.contains(source.id));UiStyle.accent(box);row.addView(box,new LayoutParams(dp(48),dp(48)));
            LinearLayout caption=UiStyle.column(activity);caption.setMinimumHeight(dp(48));caption.setGravity(android.view.Gravity.CENTER_VERTICAL);caption.addView(UiStyle.label(activity,source.name,15,false));SourceProfiles45.Profile profile=SourceProfiles45.forSource(source);caption.addView(UiStyle.label(activity,(profile!=null?(profile.kind==ChannelProfile.Kind.NEWS_BRIEF?"Короткие новости":"Статьи"):source.catalog?"Каталог":"Пользовательский источник")+(source.enabled?"":" · обновление отключено"),12,true));row.addView(caption,weight());caption.setOnClickListener(v->box.setChecked(!box.isChecked()));
            box.setOnCheckedChangeListener((button,on)->{Set<String> ids=selection(book.sources());if(on)ids.add(source.id);else ids.remove(source.id);remember(ids);refresh();});row.addView(UiStyle.iconButton(activity,"gear","Настройки · "+source.name,()->showSettings(owner,source.id)),new LayoutParams(dp(48),dp(48)));row.setOnClickListener(v->box.setChecked(!box.isChecked()));UiStyle.spaced(list,row,3);UiStyle.divider(list,2);
        }
        refreshSettings(owner);
    }
    private void showSettings(RadioService owner,String sourceId){
        if(activity.serviceForNotes()!=owner||!owner.notesBook().isSourceActive(sourceId))return;
        if(settingsDialog!=null)settingsDialog.dismiss();settingsSource=sourceId;settingsScroll=new ScrollView(activity);
        settingsDialog=new android.app.AlertDialog.Builder(activity).setTitle("Настройки · "+owner.notesBook().source(sourceId).name).setView(settingsScroll).setNegativeButton("Закрыть",null).create();
        android.app.AlertDialog dialog=settingsDialog;dialog.setOnDismissListener(d->{if(settingsDialog==dialog){settingsDialog=null;settingsScroll=null;settingsSource=null;}});dialog.show();refreshSettings(owner);
    }
    private void refreshSettings(RadioService owner){
        if(settingsDialog==null||!settingsDialog.isShowing())return;
        if(activity.serviceForNotes()!=owner||!owner.notesBook().isSourceActive(settingsSource)){settingsDialog.dismiss();return;}
        int scroll=settingsScroll.getScrollY();ScrollView view=settingsScroll;view.removeAllViews();LinearLayout body=sourceDetails(owner,owner.notesBook().source(settingsSource));int pad=dp(18);body.setPadding(pad,pad,pad,pad);view.addView(body);view.post(()->{if(settingsScroll==view)view.scrollTo(0,scroll);});
    }
    private LinearLayout sourceDetails(RadioService owner,SourceRecord source){
        LinearLayout details=UiStyle.column(activity);
        if(!source.catalog)details.addView(UiStyle.label(activity,SourcesPanel.sourceBadge(source),14,true));
        Button refresh=button("Обновить заголовки",()->{try{owner.sources().refresh(source.id,result->{if(activity.serviceForNotes()!=owner)return;summary=result.successful()?"Заголовки обновлены. Для эфира загрузите тексты.":result.failure.message(new AndroidSourceClock());refresh();});}catch(RuntimeException e){setMessage("Обновление сейчас недоступно. Данные сохранены.");}});
        SourceRetryUi.bind(refresh,"Обновить заголовки",()->owner.sources().refreshFailure(source),new AndroidSourceClock());UiStyle.spaced(details,refresh,8);
        UiStyle.spaced(details,button(source.enabled?"Отключить обновление":"Включить обновление",()->{try{owner.sources().enabled(source.id,!source.enabled);refresh();}catch(RuntimeException e){setMessage("Изменение не сохранено.");}}),8);
        UiStyle.spaced(details,button("Убрать прочитанное / прослушанное из списка",()->new android.app.AlertDialog.Builder(activity).setMessage("Убрать отмеченные заголовки? Выбранные тексты и заметки сохранятся.").setPositiveButton("Убрать",(d,w)->{try{owner.sources().cleanup(source.id);refresh();}catch(RuntimeException e){setMessage("Очистка не подтверждена.");}}).setNegativeButton("Отмена",null).show()),8);
        UiStyle.spaced(details,button("Удалить источник",()->{if(settingsDialog!=null)settingsDialog.dismiss();SourcesPanel.confirmDeleteSource(activity,owner,source);}),8);return details;
    }
    private void start(RadioService owner){try{summary="";owner.downloadSources(selection(owner.notesBook().sources()),perSource());activity.showDownloadScreen();refresh();}catch(RuntimeException e){setMessage("Загрузка не началась. Проверьте выбор, место и состояние источников.");}}
    void downloadOne(RadioService owner,String sourceId){
        if(activity.serviceForNotes()!=owner || !owner.notesBook().isSourceActive(sourceId))return;
        if(owner.sources().batchRunning()||owner.canAddDownloaded()||owner.addingDownloaded()){
            activity.showDownloadScreen();return;
        }
        try{
            owner.downloadSources(Collections.singleton(sourceId),perSource());
            activity.showDownloadScreen();
        }catch(RuntimeException unavailable){
            android.widget.Toast.makeText(activity,"Не удалось начать загрузку источника.",android.widget.Toast.LENGTH_LONG).show();
        }
    }
    private void setMessage(String text){message.setText(text);message.setVisibility(text==null||text.isEmpty()?GONE:VISIBLE);}
    private Button button(String text,Runnable run){return UiStyle.button(activity,text,run);}private LayoutParams weight(){return new LayoutParams(0,-2,1);}private int dp(int n){return UiStyle.dp(activity,n);}
}

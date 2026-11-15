package ru.bububu.radio;

import android.app.AlertDialog;
import android.view.View;
import android.text.*;
import android.widget.*;
import ru.bububu.radio.sources.*;

/** Shared explicit preview/connect form for catalogue choices and custom links. */
final class AddSourceDialog {
    private final RadioActivity activity; private final RadioService owner;
    private final LinearLayout content;private final EditText input,name;private final TextView status;private final LinearLayout previews;
    private SourceCoordinator.Preview preview;private AlertDialog dialog;private String catalogueName;private boolean publishing;
    private int visibleItems=3, textTicket;
    private boolean unconfirmedText,editingName;
    private AddSourceDialog(RadioActivity activity,RadioService owner){
        this.activity=activity;this.owner=owner;content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);int p=Math.round(16*activity.getResources().getDisplayMetrics().density);content.setPadding(p,p,p,p);
        TextView heading=new TextView(activity);heading.setText("Вставьте ссылку на статью, сайт, ленту или публичный канал Telegram.");content.addView(heading);
        add("Каталог",()->{dialog.dismiss();SourceCatalogDialog.show(activity);});
        input=new EditText(activity);input.setHint("Адрес сайта, ленты или @канала");input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);content.addView(input);
        Button check=add("Проверить ссылку",()->{catalogueName=null;preview();});
        SourceRetryUi.bind(check,"Проверить ссылку",()->owner.sources().cooldown(input.getText().toString(),false),new AndroidSourceClock());
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int a,int c,int f) { }
            public void onTextChanged(CharSequence s,int a,int before,int count) { clearPreview();owner.sources().invalidatePreview(); }
            public void afterTextChanged(Editable e) { }
        });
        name=new EditText(activity);name.setSingleLine(true);name.setHint("Имя станции · до 200 символов");name.setVisibility(View.GONE);content.addView(name);
        status=new TextView(activity);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(status);previews=new LinearLayout(activity);previews.setOrientation(LinearLayout.VERTICAL);content.addView(previews);
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        dialog=new AlertDialog.Builder(activity).setTitle("Добавить источник").setView(scroll).setPositiveButton("Подключить",null).setNegativeButton("Отмена",null).create();
        dialog.setOnShowListener(d->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->connect());});
        dialog.setOnDismissListener(d->{if(!publishing&&activity.serviceForNotes()==owner)owner.cancelSourceUi();});
    }
    static void show(RadioActivity activity){show(activity,null);}
    static void show(RadioActivity activity,SourceCatalog.Entry entry){RadioService owner=activity.serviceForNotes();if(owner==null||owner.sources()==null){Toast.makeText(activity,"Источники пока недоступны",Toast.LENGTH_LONG).show();return;}AddSourceDialog form=new AddSourceDialog(activity,owner);PhoneInsets.prepare(form.dialog);form.dialog.show();UiStyle.themeTree(form.content);if(entry!=null)form.catalogue(entry.input,entry.name);}
    private boolean live(){return dialog.isShowing()&&!activity.isFinishing()&&activity.serviceForNotes()==owner;}
    private void catalogue(String url,String title){input.setText(url);catalogueName=title;preview();}
    private void clearPreview(){
        ++textTicket;unconfirmedText=false;visibleItems=3;preview=null;
        hideNameEditor();name.setText("");status.setText("");previews.removeAllViews();
        if(dialog!=null && dialog.getButton(AlertDialog.BUTTON_POSITIVE)!=null){
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("Подключить");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        }
    }
    private void hideNameEditor(){
        editingName=false;name.setVisibility(View.GONE);
    }
    private void preview(){
        clearPreview();
        String address=input.getText().toString().trim();
        if(address.matches("(?i)(?:https?://)?t\\.me/(?:s/)?[a-z][a-z0-9_]{2,}/[1-9][0-9]*/?")){
            status.setText("Это ссылка на отдельный пост. Вставьте @имя_канала или t.me/имя_канала и выберите публикацию.");
            return;
        }
        status.setText("Проверяем источник…");
        try {
            owner.sources().preview(address,false,catalogueName,result->{
                if(!live())return;
                if(!result.successful()){status.setText(result.failure.message(new AndroidSourceClock()));return;}
                preview=result.value;
                if(preview.discovery() && preview.candidates.size()==1)chooseFeed(preview,preview.candidates.get(0));
                else display();
            });
        } catch(RuntimeException failure){status.setText("Не удалось начать проверку");}
    }

    private void chooseFeed(SourceCoordinator.Preview discovery,FeedDiscovery.Candidate candidate){
        if(preview!=discovery)return;
        previews.removeAllViews();
        status.setText("Проверяем ленту «"+feedLabel(candidate)+"»…");
        try{
            owner.sources().chooseFeed(discovery,candidate.url,catalogueName,result->{
                if(!live()||preview!=discovery)return;
                if(!result.successful()){
                    clearPreview();status.setText(result.failure.message(new AndroidSourceClock())+" Проверьте ссылку снова.");return;
                }
                preview=result.value;display();
            });
        }catch(RuntimeException expired){
            clearPreview();status.setText("Проверка устарела. Проверьте ссылку заново.");
        }
    }
    private static String feedLabel(FeedDiscovery.Candidate candidate){
        String label=candidate.title;
        if(label==null||label.trim().isEmpty()||label.equals("Без названия")||label.matches("(?i)(?:RSS|ATOM)(?:\\s+(?:feed|лента))?")){
            java.net.URI uri=java.net.URI.create(candidate.url);
            String path=uri.getPath();
            return path==null||path.equals("/")||path.isEmpty()?uri.getHost():uri.getHost()+path;
        }
        return label;
    }
    private void display(){
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        previews.removeAllViews();UiStyle.themeTree(content);
        if(preview.discovery()){
            SourceCoordinator.Preview discovery=preview;
            hideNameEditor();
            status.setText(discovery.candidates.size()==1?"На сайте найдена лента.":"На сайте несколько лент. Выберите раздел.");
            java.util.Set<String> seen=new java.util.HashSet<>();
            boolean repeated=discovery.candidates.stream().map(AddSourceDialog::feedLabel)
                    .map(value->value.toLowerCase(java.util.Locale.ROOT)).anyMatch(value->!seen.add(value));
            for(int i=0;i<discovery.candidates.size();i++){
                FeedDiscovery.Candidate candidate=discovery.candidates.get(i);
                String label=feedLabel(candidate);
                String caption=repeated?(i+1)+". "+label:label;
                Button choose=UiStyle.button(activity,caption,()->chooseFeed(discovery,candidate));
                SourceRetryUi.bind(choose,caption,()->owner.sources().cooldown(candidate.url,false),new AndroidSourceClock());
                previews.addView(choose);
                if(repeated && discovery.candidates.stream().filter(feed->feedLabel(feed).equalsIgnoreCase(label)).count()>1){
                    previews.addView(UiStyle.label(activity,candidate.url,13,true));
                }
            }
            return;
        }
        SourceRecord connected=SourceCatalog.connected(preview.source,owner.notesBook().sources());
        boolean checkedText=preview.loaded!=null&&!unconfirmedText;
        boolean openExisting=connected!=null && (!connected.enabled || !checkedText);
        if(connected!=null || unconfirmedText)hideNameEditor();
        name.setVisibility(editingName?View.VISIBLE:View.GONE);
        if(name.length()==0 && !editingName)name.setText(preview.source.name);
        String kind=preview.experimentalPage()?"Отдельная статья":preview.experimentalIndex()?"Статьи сайта":
                "telegram_public".equals(preview.source.type)?"Публичный Telegram-канал":"Новостная лента";
        String action=openExisting?"Открыть источник":checkedText?"Сохранить выбранный текст":"Подключить";
        Button confirm=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        confirm.setEnabled(!unconfirmedText);confirm.setText(action);
        StringBuilder info=new StringBuilder(kind).append(" · ").append(preview.source.name);
        if(connected!=null)info.append("\nУже подключён · ").append(connected.name);
        if(connected!=null&&!connected.enabled)info.append("\nОбновление отключено.");
        if(!preview.experimentalPage()){
            info.append("\nНайдено заголовков: ").append(preview.feed.items.size());
            if(preview.feed.itemLimitReached)info.append(" · достигнут предел разбора");
            if(preview.experimentalIndex())info.append("\n").append(HtmlIndexReader.WARNING);
        }
        info.append("\n").append(preview.source.url);
        status.setText(info);
        if(connected==null && !unconfirmedText && !editingName){
            Button rename=UiStyle.button(activity,"Изменить название",()->{
                if(!live()||preview==null||unconfirmedText)return;
                editingName=true;display();name.requestFocus();name.selectAll();
            });
            previews.addView(rename);
        }
        int available=Math.min(SourceCoordinator.PREVIEW_ITEM_LIMIT,preview.feed.items.size());
        for(int i=0;i<Math.min(visibleItems,available);i++){
            final int index=i;
            FeedItem item=preview.feed.items.get(i);
            UiStyle.spaced(previews,UiStyle.heading(activity,item.title,15),10);
            if(item.publishedAtMillis!=null){
                java.util.Date date=new java.util.Date(item.publishedAtMillis);
                String published=android.text.format.DateFormat.getDateFormat(activity).format(date)+" · "
                        +android.text.format.DateFormat.getTimeFormat(activity).format(date);
                previews.addView(UiStyle.label(activity,published,13,true));
            }
            if(checkedText && preview.loadedIndex==i){
                String body=preview.loaded.body;
                int end=Math.min(700,body.length());
                if(end>0 && Character.isHighSurrogate(body.charAt(end-1)))end--;
                TextView snippet=UiStyle.label(activity,SourcesPanel.quality(preview.loaded.completeness)+"\n"+
                        body.substring(0,end)+(end<body.length()?"…":""),14,false);
                snippet.setTextIsSelectable(true);previews.addView(snippet);
            } else if(!preview.experimentalPage()&&(connected==null||connected.enabled)){
                Button select=UiStyle.button(activity,"Проверить текст",()->checkText(index));
                select.setContentDescription("Проверить текст: "+item.title);
                SourceRetryUi.bind(select,"Проверить текст",()->owner.sources().previewTextFailure(preview,index),new AndroidSourceClock());
                previews.addView(select);
            }
        }
        if(visibleItems<available)previews.addView(UiStyle.button(activity,"Показать ещё заголовки",()->{
            visibleItems=Math.min(visibleItems+5,available);display();
        }));
        if(preview.feed.items.size()>available)previews.addView(UiStyle.label(activity,
                "Другие заголовки можно открыть в новостях этого источника после подключения.",13,true));
        UiStyle.themeTree(content);
    }
    private void checkText(int index){
        SourceCoordinator.Preview requested=preview;
        int ticket=++textTicket;unconfirmedText=true;hideNameEditor();
        // Do not let the previous selection be committed while another text is being checked.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        status.setText("Проверяем выбранный текст…");
        try {
            owner.sources().previewText(requested,index,result->{
                if(!live()||preview!=requested||ticket!=textTicket)return;
                if(result.successful()){unconfirmedText=false;preview=result.value;display();}
                else {
                    display();
                    String title=requested.feed.items.get(index).title;
                    status.setText("Не удалось проверить текст «"+title+"» в источнике «"+requested.source.name+"». "
                            +result.failure.message(new AndroidSourceClock()));
                }
            });
        } catch(RuntimeException unavailable){display();status.setText("Не удалось проверить этот текст. Проверьте ссылку снова.");}
    }
    private void connect(){
        if(!live())return;if(unconfirmedText||preview==null||preview.discovery()){status.setText("Сначала проверьте и выберите источник");return;}
        SourceRecord existing=SourceCatalog.connected(preview.source,owner.notesBook().sources());
        if(existing!=null && preview.loaded!=null && !existing.enabled){
            clearPreview();status.setText("Обновление источника отключено. Проверьте его настройки и повторите проверку текста.");
            return;
        }
        if(existing!=null && preview.loaded==null){
            publishing=true;dialog.dismiss();activity.showSourceNews(existing.id,null);
            return;
        }
        try{if(owner.sources().requiresReconnect(preview)){
            SourceCoordinator.Preview current=preview;
            new AlertDialog.Builder(activity).setTitle("Подключить источник заново?").setMessage("Будет создана новая пустая станция; прежняя очередь и позиции новостей не восстановятся. Связанные заметки останутся.")
                    .setPositiveButton("Подключить",(d,w)->{if(live()&&preview==current)connect(true);}).setNegativeButton("Отмена",null).show();return;
        }}catch(RuntimeException stale){clearPreview();status.setText("Проверка устарела. Проверьте ссылку заново.");return;}
        connect(false);
    }
    private void connect(boolean confirmed){
        if(!live())return;
        try{
            SourceRecord connected=owner.sources().connect(preview,name.getText().toString(),confirmed);
            SourceVersionRecord checked=owner.sources().connectedPreviewVersion(preview,connected);
            if(preview.loaded!=null && checked==null){
                unconfirmedText=true;display();
                status.setText("Источник сохранён, но выбранная версия не подтверждена. Проверьте публикацию снова.");
                return;
            }
            publishing=true;dialog.dismiss();
            activity.showSourceNews(connected.id,checked==null?null:checked.recordKey);
        }catch(SourceCoordinator.ProjectionPendingException pending){
            hideNameEditor();status.setText("Источник сохранён; повторите подключение для восстановления станции в эфире.");
        }catch(RuntimeException failure){
            hideNameEditor();
            if(preview.loaded!=null)unconfirmedText=true;
            display();
            status.setText(preview.loaded!=null?"Не удалось подтвердить сохранение выбранного текста. Проверьте публикацию снова."
                    :"Подключение не подтверждено. Проверьте имя, место и предел источников.");
        }
    }
    private Button add(String text,Runnable action){Button b=UiStyle.button(activity,text,action);content.addView(b);return b;}
}

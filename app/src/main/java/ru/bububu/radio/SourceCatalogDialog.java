package ru.bububu.radio;

import android.app.AlertDialog;
import android.widget.*;
import java.util.List;
import ru.bububu.radio.sources.*;

/** Opening the catalogue reads local state only; choosing an entry opens explicit preview. */
final class SourceCatalogDialog {
    private SourceCatalogDialog() {}
    static AlertDialog show(RadioActivity activity){return show(activity,SourceCatalog.entries());}

    // Package-only fixture seam; never supplied through an intent or a remote catalogue.
    static AlertDialog show(RadioActivity activity,List<SourceCatalog.Entry> entries) {
        RadioService owner=activity.serviceForNotes();
        if(owner==null||owner.sources()==null||owner.notesBook()==null){Toast.makeText(activity,"Источники пока недоступны",Toast.LENGTH_LONG).show();return null;}
        LinearLayout content=UiStyle.column(activity);int p=UiStyle.dp(activity,16);content.setPadding(p,p,p,p);
        content.addView(UiStyle.label(activity,"Выберите источник и подтвердите подключение. Затем загрузите новости во вкладке «Источники».",14,true));
        if(entries.isEmpty())UiStyle.spaced(content,UiStyle.label(activity,"Каталог пока не заполнен. Можно добавить источник по своей ссылке.",15,true),12);
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Каталог источников").setView(scroll).setNegativeButton("Закрыть",null).create();
        for(SourceCatalog.Entry entry:entries) {
            LinearLayout card=UiStyle.card(activity);card.addView(UiStyle.heading(activity,entry.name,18));
            if(!entry.description.isEmpty())UiStyle.spaced(card,UiStyle.label(activity,entry.description,14,true),6);
            SourceRecord active=SourceCatalog.connected(entry,owner.notesBook().sources());
            boolean retired=active==null&&SourceCatalog.connected(entry,owner.notesBook().allSources())!=null;
            if(active!=null)UiStyle.spaced(card,UiStyle.label(activity,"Уже подключён · "+active.name,14,true),6);
            String label=active!=null?"Уже подключён":retired?"Подключить заново":"Проверить источник";
            Button choose=UiStyle.button(activity,label,()->{
                if(!dialog.isShowing()||activity.isFinishing()||activity.serviceForNotes()!=owner)return;
                if(SourceCatalog.connected(entry,owner.notesBook().sources())!=null){Toast.makeText(activity,"Источник уже подключён",Toast.LENGTH_LONG).show();return;}
                dialog.dismiss();AddSourceDialog.show(activity,entry);
            });
            choose.setContentDescription((active!=null?"Уже подключён: ":retired?"Подключить заново: ":"Проверить источник: ")+entry.name);
            choose.setEnabled(active==null);if(active==null){UiStyle.primary(choose);SourceRetryUi.bind(choose,label,()->owner.sources().cooldown(entry.input,entry.telegram),new AndroidSourceClock());}UiStyle.spaced(card,choose,8);UiStyle.spaced(content,card,12);
        }
        Button custom=UiStyle.button(activity,"Своя ссылка",()->{if(activity.serviceForNotes()!=owner)return;dialog.dismiss();AddSourceDialog.show(activity);});UiStyle.spaced(content,custom,12);
        PhoneInsets.prepare(dialog);dialog.show();UiStyle.themeTree(content);return dialog;
    }
}

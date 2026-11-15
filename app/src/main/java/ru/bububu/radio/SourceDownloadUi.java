package ru.bububu.radio;

import android.app.AlertDialog;
import android.graphics.drawable.*;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import ru.bububu.radio.core.DownloadPresentation;
import ru.bububu.radio.sources.*;

/** One session result, shared by Radio and Sources; details stay in a separate report. */
final class SourceDownloadUi {
    private SourceDownloadUi(){}
    static String outcome(SourceCoordinator.DownloadProgress p){return DownloadPresentation.outcome(p);}
    static View page(RadioActivity activity,RadioService owner){
        SourceCoordinator.DownloadProgress p=owner.downloadProgress();
        LinearLayout page=UiStyle.column(activity);
        if(p==null){
            String status=owner.airMessage();
            page.addView(UiStyle.label(activity,status.isEmpty()?"Выберите источники для первой загрузки.":status,16,true));
            UiStyle.spaced(page,UiStyle.button(activity,"Источники",activity::showSourceManagement),16);
            return page;
        }
        if(!p.finished)return block(activity,owner,false);
        LinearLayout summary=UiStyle.card(activity);
        summary.addView(UiStyle.heading(activity,outcome(p),22));
        UiStyle.spaced(summary,UiStyle.label(activity,DownloadPresentation.counts(p.saved,p.newVersions,p.fetchedKnown),15,false),8);
        if(!p.failedSources.isEmpty()){
            UiStyle.spaced(summary,UiStyle.label(activity,"С ошибкой: "+p.failedSources.size()+" из "+p.totalSources,14,true),8);
        }
        page.addView(summary);
        if(owner.canAddDownloaded()){
            boolean review=owner.downloadReviewPending();
            Button primary=UiStyle.button(activity,review?"Открыть новости":owner.addingDownloaded()?"Добавляем…":"Добавить в эфир",()->{
                if(!current(activity,owner,p))return;
                if(review)activity.showDownloadedNews(owner,p);else owner.addDownloadedToAir();
            });
            UiStyle.primary(primary);
            primary.setEnabled(!owner.addingDownloaded()&&!owner.sources().batchRunning());
            UiStyle.spaced(page,primary,20);
            if(!review)UiStyle.spaced(page,UiStyle.label(activity,"Добавление не включает звук.",13,true),4);
            Button later=UiStyle.button(activity,"Не сейчас",()->{
                if(current(activity,owner,p)){owner.leaveDownloadedForLater();activity.closeDownloadScreen();}
            });
            later.setEnabled(!owner.addingDownloaded());
            UiStyle.spaced(page,later,8);
        }
        if(!p.failedSources.isEmpty()){
            Button retry=UiStyle.button(activity,"Повторить неудачные",()->{
                if(current(activity,owner,p)&&!owner.sources().batchRunning()&&retryWait(owner,p)==0)owner.retryAirFailures();
            });
            if(!owner.canAddDownloaded())UiStyle.primary(retry);
            new RetryState(activity,owner,p,retry).bind();
            UiStyle.spaced(page,retry,12);
        }
        if(!owner.downloadOfferMessage().isEmpty())UiStyle.spaced(page,UiStyle.label(activity,owner.downloadOfferMessage(),15,true),12);
        UiStyle.spaced(page,UiStyle.link(activity,"Отчёт по источникам","news",()->{
            if(current(activity,owner,p))report(activity,owner);
        }),12);
        LinearLayout rows=UiStyle.card(activity);
        for(SourceCoordinator.SourceDownloadProgress row:p.sources){
            SourceRecord source=owner.notesBook().source(row.sourceId);
            if(rows.getChildCount()>0)UiStyle.divider(rows,8);
            rows.addView(UiStyle.heading(activity,source==null?"Источник":source.name,15));
            UiStyle.spaced(rows,UiStyle.label(activity,rowDetail(p,row,source),14,true),4);
        }
        UiStyle.spaced(page,rows,12);
        return page;
    }
    private static String rowDetail(SourceCoordinator.DownloadProgress p,SourceCoordinator.SourceDownloadProgress row,SourceRecord source){
        java.util.List<String> parts=new java.util.ArrayList<>();
        if(!row.finished)parts.add(p.cancelled?"Прервано":"Не завершено");
        if(p.failedSources.contains(row.sourceId)){
            SourceFailureInfo info=source==null||source.lastError==null?null:SourceFailureInfo.forStoredError(source.lastError);
            parts.add(shortFailure(info));
        }
        if(row.newVersions>0)parts.add("Новых или обновлённых: "+row.newVersions);
        int existing=row.loaded-row.newVersions-row.fetchedKnown;
        if(existing>0)parts.add("Уже были доступны: "+existing);
        if(row.fetchedKnown>0)parts.add("Получены известные версии: "+row.fetchedKnown);
        if(row.skipped>0)parts.add("Пропущено: "+row.skipped);
        return parts.isEmpty()?"Новых материалов нет":String.join(" · ",parts);
    }
    private static String shortFailure(SourceFailureInfo info){
        if(info==null)return "Ошибка загрузки";
        switch(info.kind){
            case NETWORK:return "Нет соединения";
            case TIMEOUT:return "Сервер не ответил";
            case HTTP:return "Ошибка сервера";
            case COOLDOWN:case EXCESSIVE:return "Повтор ограничен сервером";
            case STORAGE:return "Ошибка сохранения";
            case INVALID:return "Не удалось проверить данные";
            default:return "Ошибка загрузки";
        }
    }
    // Any eligible failure may retry. The coordinator still checks each source and body at request time.
    private static long retryWait(RadioService owner,SourceCoordinator.DownloadProgress p){
        long earliest=Long.MAX_VALUE;
        AndroidSourceClock clock=new AndroidSourceClock();
        for(String id:p.failedSources){
            SourceRecord source=owner.notesBook().source(id);
            if(source==null||!owner.notesBook().isSourceActive(id)||!source.enabled)continue;
            SourceFailureInfo gate=owner.sources().refreshFailure(source);
            if(gate==null||!gate.retryBlocked(clock))return 0;
            earliest=Math.min(earliest,gate.remainingMillis(clock));
        }
        return earliest;
    }
    private static final class RetryState implements Runnable,View.OnAttachStateChangeListener{
        final RadioActivity activity;final RadioService owner;final SourceCoordinator.DownloadProgress shown;final Button button;
        RetryState(RadioActivity activity,RadioService owner,SourceCoordinator.DownloadProgress shown,Button button){
            this.activity=activity;this.owner=owner;this.shown=shown;this.button=button;
        }
        void bind(){button.addOnAttachStateChangeListener(this);run();}
        public void onViewAttachedToWindow(View view){run();}
        public void onViewDetachedFromWindow(View view){button.removeCallbacks(this);}
        public void run(){
            button.removeCallbacks(this);
            long wait=current(activity,owner,shown)&&!owner.sources().batchRunning()?retryWait(owner,shown):Long.MAX_VALUE;
            button.setEnabled(wait==0);
            if(button.isAttachedToWindow()&&wait>0&&wait<Long.MAX_VALUE)
                button.postDelayed(this,Math.max(1,wait));
        }
    }
    private static boolean current(RadioActivity a,RadioService owner,SourceCoordinator.DownloadProgress shown){
        return a.serviceForNotes()==owner&&owner.downloadProgress()!=null&&shown.runId.equals(owner.downloadProgress().runId);
    }
    static View block(RadioActivity activity,RadioService owner,boolean compact){
        SourceCoordinator.DownloadProgress p=owner.downloadProgress();
        LinearLayout block=UiStyle.column(activity);
        if(p==null||compact&&!owner.showRadioDownloadResult()){block.setVisibility(View.GONE);return block;}
        if(p.finished){
            if(!compact)return page(activity,owner);
            boolean hiddenError=!owner.radioDownloadErrorVisible()&&(p.cancelled||p.stopFailure!=null||!p.failedSources.isEmpty());
            block.addView(UiStyle.link(activity,hiddenError?"Результат загрузки":outcome(p),"news",activity::showDownloadScreen));
            if(owner.radioDownloadErrorVisible())block.addView(UiStyle.button(activity,"Скрыть ошибку",()->{
                if(current(activity,owner,p))owner.hideRadioDownloadResult();
            }));
            return block;
        }
        LinearLayout card=UiStyle.card(activity);
        LinearLayout row=UiStyle.row(activity);
        row.addView(UiStyle.heading(activity,outcome(p),16),new LinearLayout.LayoutParams(0,-2,1));
        int percent=DownloadPresentation.percent(p.processedSources,p.totalSources,false,false,false);
        row.addView(UiStyle.label(activity,percent+"%",14,true));card.addView(row);
        ProgressBar bar=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setContentDescription("Обработано источников: "+p.processedSources+" из "+p.totalSources);
        UiStyle.Palette colors=UiStyle.palette(activity);
        GradientDrawable track=new GradientDrawable(),fill=new GradientDrawable();
        track.setColor(colors.border);fill.setColor(p.failedSources.isEmpty()?colors.brand:colors.muted);
        track.setCornerRadius(UiStyle.dp(activity,4));fill.setCornerRadius(UiStyle.dp(activity,4));
        LayerDrawable layers=new LayerDrawable(new Drawable[]{track,new ClipDrawable(fill,Gravity.LEFT,ClipDrawable.HORIZONTAL)});
        layers.setId(0,android.R.id.background);layers.setId(1,android.R.id.progress);bar.setProgressDrawable(layers);bar.setProgress(percent);
        LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,UiStyle.dp(activity,8));line.topMargin=UiStyle.dp(activity,10);card.addView(bar,line);
        UiStyle.spaced(card,UiStyle.label(activity,DownloadPresentation.counts(p.saved,p.newVersions,p.fetchedKnown),14,false),8);
        UiStyle.spaced(card,UiStyle.button(activity,"Отменить загрузку",()->{if(current(activity,owner,p))owner.cancelSourceDownload(p.runId);}),8);
        if(!compact)UiStyle.spaced(card,UiStyle.link(activity,"Отчёт по источникам","news",()->report(activity,owner)),8);
        return card;
    }
    static void report(RadioActivity activity,RadioService owner){
        SourceCoordinator.DownloadProgress p=owner.downloadProgress();if(p==null)return;LinearLayout body=UiStyle.column(activity);int pad=UiStyle.dp(activity,18);body.setPadding(pad,pad,pad,pad);
        body.addView(UiStyle.heading(activity,outcome(p),18));if(p.stopFailure!=null&&!p.cancelled)body.addView(UiStyle.label(activity,p.stopFailure.message(new AndroidSourceClock()),14,true));body.addView(UiStyle.label(activity,DownloadPresentation.counts(p.saved,p.newVersions,p.fetchedKnown),15,false));if(!owner.downloadOfferMessage().isEmpty())body.addView(UiStyle.label(activity,owner.downloadOfferMessage(),14,true));
        for(SourceCoordinator.SourceDownloadProgress row:p.sources){
            SourceRecord source=owner.notesBook().source(row.sourceId);String name=source==null?"Источник":source.name;LinearLayout card=UiStyle.card(activity);card.addView(UiStyle.heading(activity,name,16));
            card.addView(UiStyle.label(activity,DownloadPresentation.counts(row.loaded,row.newVersions,row.fetchedKnown)+" · пропущено: "+row.skipped+" · ошибок: "+row.failed,14,false));
            String status=!row.finished&&p.finished?"Обработка не завершена":row.message;
            if(source!=null && p.failedSources.contains(source.id)){
                SourceFailureInfo failure=owner.sources().sourceFailure(source);
                if(failure!=null)status=failure.message(new AndroidSourceClock());
            }
            card.addView(UiStyle.label(activity,status,14,true));
            String code=source==null?null:SourceFailureInfo.downloadDiagnostic(source.lastError);if(code!=null){TextView details=UiStyle.label(activity,code+"\n"+owner.sourceDiagnosticDetails(row.sourceId),12,true);details.setVisibility(View.GONE);card.addView(UiStyle.button(activity,"Диагностические детали",()->details.setVisibility(details.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE)));card.addView(details);}UiStyle.spaced(body,card,10);
        }
        ScrollView scroll=new ScrollView(activity);scroll.addView(body);AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle("Отчёт по источникам").setView(scroll).setNegativeButton("Закрыть",null);
        dialog.show();
    }
}

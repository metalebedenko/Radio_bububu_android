package ru.bububu.radio.core;

import ru.bububu.radio.sources.SourceCoordinator.DownloadProgress;

/** Presentation facts only; a completed batch does not certify audible material. */
public final class DownloadPresentation {
    private DownloadPresentation(){}
    public static int percent(int processed,int total,boolean finished,boolean cancelled,boolean stopped){
        if(total<=0)return 0;if(finished&&!cancelled&&!stopped)return 100;return Math.max(0,Math.min(99,(int)(100L*processed/total)));
    }
    public static String outcome(DownloadProgress p){
        if(p.cancelled)return "Загрузка отменена";
        if(p.stopFailure!=null)return "Загрузка остановлена";
        if(!p.finished)return "Загружаем материалы…";
        if(!p.failedSources.isEmpty())return p.saved>0?"Часть источников недоступна":"Не удалось загрузить материалы";
        if(p.skipped>0 || p.processedSources<p.totalSources)return "Проверка завершена с пропусками";
        if(p.newVersions>0)return "Сохранены новые или обновлённые материалы";
        if(p.fetchedKnown>0)return "Получены тексты известных материалов";
        return "Новых материалов нет";
    }
    public static String counts(int available,int newVersions,int fetchedKnown){
        return "Новых или обновлённых: "+newVersions+" · уже были доступны: "+(available-newVersions-fetchedKnown)
                +(fetchedKnown==0?"":" · получены тексты известных версий: "+fetchedKnown);
    }
}

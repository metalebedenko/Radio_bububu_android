package ru.bububu.radio.models;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import ru.bububu.radio.core.PlayerController.SpeechSentence;
import ru.bububu.radio.core.PlayerController.SpeechUnit;

/** Two transient future WAV owners. Readiness never starts audio or advances the player. */
public final class RadioLookahead {
    public static final long MAX_WAV=1048576;
    public static final class Slot {
        public final long epoch;public final String stamp;public final SpeechUnit after;public final int sentence,fragment;
        public ModelPackStore.Token work;public SpeechUnit unit;public String projectedHash,error;public File file;private File transferred;public boolean finished;
        private Slot(long epoch,String stamp,SpeechUnit after,int sentence,int fragment){this.epoch=epoch;this.stamp=stamp;this.after=after;this.sentence=sentence;this.fragment=fragment;}
    }
    private final List<Slot> future=new ArrayList<>();private Slot adopted;private long epoch=1;
    public long epoch(){return epoch;}
    public int size(){return future.size();}
    public long bytes(){long n=0;for(Slot s:future)if(s.file!=null)n+=s.file.length();return n;}
    public boolean valid(Slot s){return s!=null&&s.epoch==epoch&&(future.contains(s)||adopted==s);}
    public Slot reserve(SpeechUnit current,String stamp){
        if(stamp==null||future.size()>=2)return null;
        SpeechUnit after=current;
        if(!future.isEmpty()){Slot last=future.get(future.size()-1);if(!last.finished||last.error!=null||last.unit==null)return null;after=last.unit;}
        int sentence=after.sentence.index,fragment=after.fragment+1;
        if(fragment==after.fragments.size()){sentence++;fragment=0;}
        if(sentence>=after.sentence.article.sentences.size())return null;
        Slot s=new Slot(epoch,stamp,after,sentence,fragment);future.add(s);return s;
    }
    public void abandon(Slot s){if(future.remove(s)){if(s.work!=null)s.work.cancel();delete(s.file);s.file=null;}}
    public boolean planned(Slot s,SpeechUnit unit){
        if(!valid(s)||!matchesPosition(s,unit))return false;
        s.unit=unit;s.projectedHash=hash(unit);return true;
    }
    public boolean ready(Slot s,SpeechUnit unit,String stamp,String projectedHash,File file){
        if(!valid(s)||!s.stamp.equals(stamp)||!matchesPosition(s,unit)||(s.unit!=null&&!s.unit.same(unit))||!hash(unit).equals(projectedHash)
                ||file==null||!file.isFile()||Files.isSymbolicLink(file.toPath())||file.length()<46||file.length()>MAX_WAV){if(file!=null&&!file.equals(s.transferred))delete(file);failure(s,"Подготовленный WAV не подтверждён");return false;}
        s.unit=unit;s.projectedHash=projectedHash;s.file=file;s.finished=true;return true;
    }
    public void failure(Slot s,String error){if(valid(s)){s.error=error;s.finished=true;}}
    public List<String> plan(SpeechSentence sentence,String stamp){
        for(Slot s:future)if(s.epoch==epoch&&s.stamp.equals(stamp)&&s.unit!=null&&s.unit.sentence.same(sentence))return s.unit.fragments;
        if(adopted!=null&&valid(adopted)&&adopted.stamp.equals(stamp)&&adopted.unit!=null&&adopted.unit.sentence.same(sentence))return adopted.unit.fragments;
        return null;
    }
    /** Removes a future slot from the bound. An in-flight exact target becomes the single active owner. */
    public Slot claim(SpeechUnit unit,String stamp){
        for(Slot s:new ArrayList<>(future))if(matchesPosition(s,unit)){
            if(!s.finished&&s.work!=null&&s.work.cancelled()){abandon(s);return null;}
            if(!s.stamp.equals(stamp)||(s.unit!=null&&(!s.unit.same(unit)||!hash(unit).equals(s.projectedHash)))){clear();return null;}
            future.remove(s);if(!s.finished)adopted=s;return s;
        }
        return null;
    }
    public File transfer(Slot s){if(adopted==s)adopted=null;File file=s.file;s.file=null;s.transferred=file;return file;}
    public void clear(){
        epoch++;
        for(Slot s:future){if(s.work!=null)s.work.cancel();delete(s.file);s.file=null;}future.clear();
        if(adopted!=null){if(adopted.work!=null)adopted.work.cancel();delete(adopted.file);adopted.file=null;adopted=null;}
    }
    private static boolean matchesPosition(Slot s,SpeechUnit unit){return s.after.sentence.sameArticle(unit.sentence)&&s.sentence==unit.sentence.index&&s.fragment==unit.fragment;}
    public static String hash(SpeechUnit unit){return ru.bububu.radio.sources.SourceSpeechPlan45.hash(unit);}
    private static void delete(File f){if(f!=null)f.delete();}
}

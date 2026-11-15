package ru.bububu.radio.core;

import java.util.*;
import java.util.function.Predicate;
import ru.bububu.radio.sources.*;

/** The product queue is Book-backed. Old sample progress is retained without granting playback. */
public final class OrdinaryRadio {
    private OrdinaryRadio(){}
    public static boolean builtin(String id){return "A".equals(id)||"B".equals(id)||"C".equals(id);}
    public static boolean inAir(Station station,Predicate<String> retired){return !builtin(station.id)&&!retired.test(station.id)&&!station.articles.isEmpty();}
    /** Exact admitted materials only; download selection is not air membership. */
    public static List<AirMix.Item> sourceAir(NoteBook book,boolean mix,String explicitSource){
        return sourceAir(book,mix,explicitSource,false);
    }
    /** Explicit Play may use an already consented exact item; this never admits new data. */
    public static List<AirMix.Item> sourceAir(NoteBook book,boolean mix,String explicitSource,boolean explicitReady){
        List<AirMix.Item> pool=new ArrayList<>();if(book==null||book.storageUncertain())return pool;
        for(NoteBook.Material material:book.materials()){
            if(material.sourceVersionKey==null)continue;SourceVersionRecord version=book.sourceVersion(material.sourceVersionKey);if(version==null)continue;
            SourceRecord source=book.source(version.sourceId);if(source==null||!source.enabled||!book.isSourceActive(source.id))continue;
            boolean explicit=!mix&&source.id.equals(explicitSource);
            if(!(explicitReady&&!mix||ChannelProfile.automatic(version)||explicit&&"full".equals(version.completeness)))continue;
            if(book.queueMembership(material.stationId,material.asArticle(),material.sequence,material.sourceVersionKey)!=NoteBook.Membership.ALLOWED)continue;
            pool.add(new AirMix.Item(material.stationId,material.articleId,material.version,version.sourceId,version.sentenceStarts.size()));
        }return pool;
    }
    public static List<Station> catalog(NoteBook book){
        List<Station> result=new ArrayList<>();
        if(book!=null)for(Station registered:book.stationRegistry()){
            List<Article> articles=new ArrayList<>();for(NoteBook.Material m:book.materials())if(m.stationId.equals(registered.id))articles.add(m.asArticle());
            result.add(new Station(registered.id,registered.name,articles));
        }
        if(result.isEmpty())result.add(new Station(NoteBook.MY_TEXTS,"Мои тексты",Collections.emptyList()));return result;
    }
    public static PlayerController.SavedState preserveLegacy(PlayerController.SavedState current,PlayerController.SavedState prior){
        if(prior==null)return current;Map<String,String> cursors=new LinkedHashMap<>(current.stationArticleIds);Map<String,PlayerController.SavedProgress> progress=new LinkedHashMap<>(current.progress);
        for(Map.Entry<String,String> p:prior.stationArticleIds.entrySet())if(builtin(p.getKey()))cursors.putIfAbsent(p.getKey(),p.getValue());
        for(Map.Entry<String,PlayerController.SavedProgress> p:prior.progress.entrySet())if(builtin(p.getValue().stationId))progress.putIfAbsent(p.getKey(),p.getValue());
        return new PlayerController.SavedState(current.currentStationId,cursors,progress);
    }
}

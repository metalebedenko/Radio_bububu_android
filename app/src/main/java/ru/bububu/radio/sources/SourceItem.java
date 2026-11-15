package ru.bububu.radio.sources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Identity/read marks belong to an item, while coverage belongs to an immutable version. */
public final class SourceItem {
    public final String sourceId,itemKey,articleId,providerId,url,title,latestVersionKey,lastViewedVersion;
    public final List<String> aliases;
    public final long publishedAt,updatedAt,firstSeenAt;
    public final String publishedRaw,updatedRaw;
    public final String statusHint,reasonHint,methodHint;
    public final boolean read,everListened;
    public SourceItem(String sourceId,String itemKey,String articleId,String providerId,String url,String title,List<String> aliases,
                      long publishedAt,long updatedAt,long firstSeenAt,String latestVersionKey,boolean read,boolean everListened,String lastViewedVersion) {
        this(sourceId,itemKey,articleId,providerId,url,title,aliases,publishedAt,updatedAt,firstSeenAt,latestVersionKey,read,everListened,lastViewedVersion,null,null);
    }
    public SourceItem(String sourceId,String itemKey,String articleId,String providerId,String url,String title,List<String> aliases,
                      long publishedAt,long updatedAt,long firstSeenAt,String latestVersionKey,boolean read,boolean everListened,String lastViewedVersion,String publishedRaw,String updatedRaw) {
        this(sourceId,itemKey,articleId,providerId,url,title,aliases,publishedAt,updatedAt,firstSeenAt,latestVersionKey,read,everListened,lastViewedVersion,publishedRaw,updatedRaw,"headline_only","","feed");
    }
    public SourceItem(String sourceId,String itemKey,String articleId,String providerId,String url,String title,List<String> aliases,
                      long publishedAt,long updatedAt,long firstSeenAt,String latestVersionKey,boolean read,boolean everListened,String lastViewedVersion,String publishedRaw,String updatedRaw,String statusHint,String reasonHint,String methodHint) {
        this.sourceId=SourceLimits.id(sourceId,"sourceId"); this.itemKey=SourceLimits.id(itemKey,"itemKey");
        this.articleId=SourceLimits.id(articleId,"articleId"); this.providerId=SourceLimits.value(providerId,4096,"providerId",true);
        this.url=SourceLimits.value(url,4096,"url",true); this.title=SourceLimits.value(title,2000,"title",false);
        if(aliases.size()>16) throw new IllegalArgumentException("item aliases");
        ArrayList<String> copy=new ArrayList<>(); for(String alias:aliases) copy.add(SourceLimits.value(alias,4096,"alias",false));
        this.aliases=Collections.unmodifiableList(copy);
        if(publishedAt < -1 || updatedAt < -1 || firstSeenAt<0) throw new IllegalArgumentException("item dates");
        this.publishedAt=publishedAt; this.updatedAt=updatedAt; this.firstSeenAt=firstSeenAt;
        this.publishedRaw=SourceLimits.value(publishedRaw,2000,"publishedRaw",true);this.updatedRaw=SourceLimits.value(updatedRaw,2000,"updatedRaw",true);
        this.statusHint=SourceLimits.value(statusHint==null?"headline_only":statusHint,64,"statusHint",false);
        this.reasonHint=SourceLimits.value(reasonHint==null?"":reasonHint,2000,"reasonHint",false);
        this.methodHint=SourceLimits.value(methodHint==null?"feed":methodHint,128,"methodHint",false);
        this.latestVersionKey=latestVersionKey==null?null:SourceLimits.id(latestVersionKey,"latestVersionKey");
        this.read=read; this.everListened=everListened; this.lastViewedVersion=lastViewedVersion==null?null:SourceLimits.id(lastViewedVersion,"lastViewedVersion");
    }
    public static SourceItem create(String sourceId,String itemKey,String providerId,String url,String title,long publishedAt,long updatedAt,long firstSeenAt) {
        return new SourceItem(sourceId,itemKey,"item:"+SourceLimits.sha256(sourceId+"\u0000"+itemKey),providerId,url,title,Collections.emptyList(),publishedAt,updatedAt,firstSeenAt,null,false,false,null);
    }
    public static SourceItem create(String sourceId,String itemKey,String providerId,String url,String title,long publishedAt,long updatedAt,long firstSeenAt,String publishedRaw,String updatedRaw) {
        return new SourceItem(sourceId,itemKey,"item:"+SourceLimits.sha256(sourceId+"\u0000"+itemKey),providerId,url,title,Collections.emptyList(),publishedAt,updatedAt,firstSeenAt,null,false,false,null,publishedRaw,updatedRaw);
    }
    public SourceItem withLatest(String versionKey) { return copy(versionKey,read,everListened,lastViewedVersion); }
    public SourceItem withMarks(boolean read,boolean listened,String viewed) { return copy(latestVersionKey,read,listened,viewed); }
    public SourceItem withHints(String status,String reason,String method) {
        return new SourceItem(sourceId,itemKey,articleId,providerId,url,title,aliases,publishedAt,updatedAt,firstSeenAt,latestVersionKey,read,everListened,lastViewedVersion,publishedRaw,updatedRaw,status,reason,method);
    }
    private SourceItem copy(String latest,boolean read,boolean listened,String viewed) {
        return new SourceItem(sourceId,itemKey,articleId,providerId,url,title,aliases,publishedAt,updatedAt,firstSeenAt,latest,read,listened,viewed,publishedRaw,updatedRaw,statusHint,reasonHint,methodHint);
    }
}

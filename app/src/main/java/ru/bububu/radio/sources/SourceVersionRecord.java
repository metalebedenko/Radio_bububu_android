package ru.bububu.radio.sources;

import java.util.List;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import ru.bububu.radio.core.Article;

/** One immutable spoken payload with a span for the exact body; metadata can outlive a CAS file. */
public final class SourceVersionRecord {
    public final String sourceId,itemKey,articleId,versionKey,displayTitle,providerTitle,sourceLabel,url,
            completeness,reason,method,adapterVersion,normalizerVersion,framingVersion,bodyHash,spokenText,cacheLocator,recordKey;
    public final int bodyStart,bodyEnd;
    public final int spokenLength;
    public final List<Integer> sentenceStarts;
    public final long receivedAt;
    public final SourcePublication45 publication;
    public final SourcePublication45.Context contextClass;
    public int textLimit(){return SourceArticleFramer.VERSION45.equals(framingVersion)?SourceLimits.MAX_SOURCE_SPOKEN_UTF16:SourceLimits.MAX_TEXT;}
    public ChannelProfile.Kind kind(){return ChannelProfile.kind(adapterVersion);}
    public static SourceVersionRecord source45(SourceRecord source,SourceItem item,ExtractionResult extracted,SourcePublication45 publication,SourcePublication45.Context context,long receivedAt)throws SourceFailure{
        extracted=ChannelProfile.snapshot(source,extracted);SourceArticleFramer.Framed f=SourceArticleFramer.frame45(source.name,extracted,publication,context);
        return new SourceVersionRecord(source.id,item.itemKey,item.articleId,f.versionHash,SemanticTextNormalizer.displayTitle(extracted.displayTitle),extracted.providerTitle,SemanticTextNormalizer.normalize(source.name).replace('\n',' '),item.url,extracted.completeness,extracted.reason,extracted.method,extracted.adapterVersion,SourceArticleFramer.NORMALIZER45,SourceArticleFramer.VERSION45,f.bodyHash,f.spokenText,f.bodyStart,f.bodyEnd,receivedAt,f.versionHash,f.spokenText.length(),ru.bububu.radio.core.TextSegments.sentenceStarts(f.spokenText),publication,context);
    }
    public String channelReference(){return ChannelProfile.reference(adapterVersion);}
    public SourceVersionRecord(String sourceId,String itemKey,String articleId,String versionKey,String displayTitle,String providerTitle,
                               String sourceLabel,String url,String completeness,String reason,String method,String adapterVersion,
                               String normalizerVersion,String framingVersion,String bodyHash,String spokenText,int bodyStart,int bodyEnd,long receivedAt) {
        this(sourceId,itemKey,articleId,versionKey,displayTitle,providerTitle,sourceLabel,url,completeness,reason,method,adapterVersion,normalizerVersion,framingVersion,bodyHash,spokenText,bodyStart,bodyEnd,receivedAt,versionKey);
    }
    public SourceVersionRecord(String sourceId,String itemKey,String articleId,String versionKey,String displayTitle,String providerTitle,
                               String sourceLabel,String url,String completeness,String reason,String method,String adapterVersion,
                               String normalizerVersion,String framingVersion,String bodyHash,String spokenText,int bodyStart,int bodyEnd,long receivedAt,String cacheLocator) {
        this(sourceId,itemKey,articleId,versionKey,displayTitle,providerTitle,sourceLabel,url,completeness,reason,method,adapterVersion,normalizerVersion,framingVersion,bodyHash,spokenText,bodyStart,bodyEnd,receivedAt,cacheLocator,
                spokenText==null?bodyEnd:spokenText.length(),spokenText==null?Collections.singletonList(0):ru.bububu.radio.core.TextSegments.sentenceStarts(spokenText));
    }
    public SourceVersionRecord(String sourceId,String itemKey,String articleId,String versionKey,String displayTitle,String providerTitle,
                               String sourceLabel,String url,String completeness,String reason,String method,String adapterVersion,
                               String normalizerVersion,String framingVersion,String bodyHash,String spokenText,int bodyStart,int bodyEnd,long receivedAt,String cacheLocator,int spokenLength,List<Integer> sentenceStarts) {
        this(sourceId,itemKey,articleId,versionKey,displayTitle,providerTitle,sourceLabel,url,completeness,reason,method,adapterVersion,normalizerVersion,framingVersion,bodyHash,spokenText,bodyStart,bodyEnd,receivedAt,cacheLocator,spokenLength,sentenceStarts,null,null);
    }
    public SourceVersionRecord(String sourceId,String itemKey,String articleId,String versionKey,String displayTitle,String providerTitle,
                               String sourceLabel,String url,String completeness,String reason,String method,String adapterVersion,
                               String normalizerVersion,String framingVersion,String bodyHash,String spokenText,int bodyStart,int bodyEnd,long receivedAt,String cacheLocator,int spokenLength,List<Integer> sentenceStarts,SourcePublication45 publication,SourcePublication45.Context contextClass) {
        this.publication=publication;this.contextClass=contextClass;
        boolean v2=SourceArticleFramer.VERSION45.equals(framingVersion);int cap=v2?SourceLimits.MAX_SOURCE_SPOKEN_UTF16:SourceLimits.MAX_TEXT;
        if(v2&&(publication==null||contextClass==null||!SourceArticleFramer.NORMALIZER45.equals(normalizerVersion))||!v2&&(publication!=null||contextClass!=null))throw new IllegalArgumentException("Publication/frame mismatch");
        this.sourceId=SourceLimits.id(sourceId,"sourceId"); this.itemKey=SourceLimits.id(itemKey,"itemKey");
        this.articleId=SourceLimits.id(articleId,"articleId"); this.versionKey=SourceLimits.id(versionKey,"versionKey");
        this.recordKey=SourceLimits.id(sourceId+":"+articleId+":"+versionKey,"recordKey");
        this.displayTitle=SourceLimits.value(displayTitle,200,"displayTitle",false); this.providerTitle=SourceLimits.value(providerTitle,2000,"providerTitle",false);
        this.sourceLabel=SourceLimits.value(sourceLabel,200,"sourceLabel",false); this.url=SourceLimits.value(url,4096,"url",true);
        if(!Arrays.asList("full","summary","unknown","headline_only","unavailable").contains(completeness)) throw new IllegalArgumentException("completeness");
        this.completeness=completeness; this.reason=SourceLimits.value(reason,2000,"reason",false); this.method=SourceLimits.value(method,128,"method",false);
        this.adapterVersion=SourceLimits.value(adapterVersion,128,"adapterVersion",false); this.normalizerVersion=SourceLimits.value(normalizerVersion,128,"normalizerVersion",false);
        this.framingVersion=SourceLimits.value(framingVersion,128,"framingVersion",false); this.bodyHash=SourceLimits.value(bodyHash,64,"bodyHash",false);
        if(!bodyHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("bodyHash");
        String expected=v2?SourceArticleFramer.key45(bodyHash,providerTitle,sourceLabel,completeness,reason,method,adapterVersion,publication,contextClass):SourceLimits.snapshotVersion(bodyHash,providerTitle,sourceLabel,completeness,reason,adapterVersion,normalizerVersion,framingVersion);
        if(!expected.equals(versionKey)) throw new IllegalArgumentException("version hash");
        this.spokenText=SourceLimits.value(spokenText,cap,"spokenText",true);
        if(bodyStart<0 || bodyEnd<bodyStart || bodyEnd>cap || (spokenText!=null && bodyEnd>spokenText.length())) throw new IllegalArgumentException("body span");
        if(spokenText!=null && (!bodyHash.equals(SourceLimits.sha256(spokenText.substring(bodyStart,bodyEnd)))
                || (bodyStart>0 && bodyStart<spokenText.length() && Character.isLowSurrogate(spokenText.charAt(bodyStart)))
                || (bodyEnd>0 && bodyEnd<spokenText.length() && Character.isLowSurrogate(spokenText.charAt(bodyEnd))))) throw new IllegalArgumentException("body payload hash/span");
        // The versioned framing recipe is deliberately pure; no parser/DOM dependency here.
        String prefix=v2?SourceArticleFramer.prefix45(sourceLabel,providerTitle,method,publication,contextClass):sourceLabel+"\n\n"+(!"public_preview_text".equals(method)&&!providerTitle.isEmpty()?providerTitle+"\n\n":"");
        if(sourceLabel.trim().isEmpty()||!v2&&!"source-frame-v1".equals(framingVersion)||bodyStart!=prefix.length()||bodyEnd!=spokenLength||(spokenText!=null&&!spokenText.startsWith(prefix)))
            throw new IllegalArgumentException("Spoken framing does not match immutable provenance");
        this.bodyStart=bodyStart; this.bodyEnd=bodyEnd;
        if(spokenLength<bodyEnd||spokenLength>cap || (spokenText!=null&&spokenLength!=spokenText.length())) throw new IllegalArgumentException("spokenLength");
        ArrayList<Integer> starts=new ArrayList<>(sentenceStarts);
        int previous=-1;
        if(starts.isEmpty()||starts.get(0)!=0) throw new IllegalArgumentException("sentenceStarts");
        for(Integer start:starts) { if(start==null||start<=previous||start<0||start>=Math.max(1,spokenLength)) throw new IllegalArgumentException("sentenceStarts"); previous=start; }
        if(v2&&spokenText!=null&&!ru.bububu.radio.core.TextSegments.sentenceStarts(spokenText).equals(starts))throw new IllegalArgumentException("V2 sentence binding");
        if(spokenText!=null&&!spokenText.trim().isEmpty()) new Article(articleId,versionKey,displayTitle,spokenText,starts);
        this.spokenLength=spokenLength; this.sentenceStarts=Collections.unmodifiableList(starts);
        if(receivedAt<0) throw new IllegalArgumentException("receivedAt"); this.receivedAt=receivedAt;
        this.cacheLocator=cacheLocator==null?null:SourceLimits.id(cacheLocator,"cacheLocator");
    }
    public SourceVersionRecord withPayload(String text) {
        return new SourceVersionRecord(sourceId,itemKey,articleId,versionKey,displayTitle,providerTitle,sourceLabel,url,completeness,reason,method,adapterVersion,normalizerVersion,framingVersion,bodyHash,text,bodyStart,bodyEnd,receivedAt,cacheLocator,spokenLength,sentenceStarts,publication,contextClass);
    }
    public String body() { if(spokenText==null) throw new IllegalStateException("Payload is not available"); return spokenText.substring(bodyStart,bodyEnd); }
    public Article asArticle() { if(spokenText==null||spokenText.trim().isEmpty()) throw new IllegalStateException("Payload is not available"); return new Article(articleId,versionKey,displayTitle,spokenText,sentenceStarts,this); }
    public boolean sameMetadata(SourceVersionRecord other) {
        return other!=null && sourceId.equals(other.sourceId)&&itemKey.equals(other.itemKey)&&articleId.equals(other.articleId)&&versionKey.equals(other.versionKey)
                && displayTitle.equals(other.displayTitle)&&providerTitle.equals(other.providerTitle)&&sourceLabel.equals(other.sourceLabel)&&Objects.equals(url,other.url)
                && completeness.equals(other.completeness)&&reason.equals(other.reason)&&method.equals(other.method)&&adapterVersion.equals(other.adapterVersion)
                && normalizerVersion.equals(other.normalizerVersion)&&framingVersion.equals(other.framingVersion)&&bodyHash.equals(other.bodyHash)
                && bodyStart==other.bodyStart&&bodyEnd==other.bodyEnd&&spokenLength==other.spokenLength&&sentenceStarts.equals(other.sentenceStarts)
                &&contextClass==other.contextClass&&Arrays.equals(publication==null?null:publication.encode(),other.publication==null?null:other.publication.encode());
    }
}

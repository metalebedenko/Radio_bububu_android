package ru.bububu.radio.sources;

import java.util.Objects;

/** Trusted channel configuration, never inferred from publisher, length or completeness.
 * Its immutable fingerprint/kind/parser snapshot fits the existing versioned adapter field;
 * this keeps the Book wire schema and all legacy version identities unchanged. */
public final class ChannelProfile {
    public enum Kind { ARTICLE_ANALYSIS, NEWS_BRIEF, UNKNOWN }
    public final String profileId, channelId, revision, sourceKey, adapterId, parserVersion, fingerprint;
    public final Kind kind;
    public ChannelProfile(String profileId,String channelId,String revision,String sourceKey,
                          String adapterId,String parserVersion,Kind kind) {
        this(profileId,channelId,revision,sourceKey,adapterId,parserVersion,kind,null);
    }
    public ChannelProfile(String profileId,String channelId,String revision,String sourceKey,
                          String adapterId,String parserVersion,Kind kind,String configSha) {
        this.profileId=required(profileId,128);this.channelId=required(channelId,128);
        this.revision=required(revision,128);this.sourceKey=required(sourceKey,4096);
        this.adapterId=required(adapterId,128);this.parserVersion=required(parserVersion,48);
        if(parserVersion.contains("|"))throw new IllegalArgumentException("Parser identity separator");
        this.kind=Objects.requireNonNull(kind);
        fingerprint=configSha==null?SourceLimits.snapshotVersion(profileId,channelId,revision,sourceKey,adapterId,parserVersion,kind.name()):SourceLimits.snapshotVersion(profileId,channelId,revision,sourceKey,adapterId,parserVersion,kind.name(),required(configSha,64));
    }
    private static String required(String value,int cap){String v=SourceLimits.value(value,cap,"channel profile",false);if(v.trim().isEmpty())throw new IllegalArgumentException("Empty channel identity");return v;}
    public SourceRecord bind(SourceRecord source){
        if(!sourceKey.equals(source.key)||!adapterId.equals(source.adapterId))throw new IllegalArgumentException("Profile belongs to another channel/parser");
        String stamp="CH1|"+kind.name().charAt(0)+"|"+fingerprint+"|"+parserVersion;
        SourceLimits.value(stamp,128,"channel snapshot",false);
        return new SourceRecord(source.id,source.stationId,source.type,source.key,source.name,source.url,
                source.adapterId,stamp,source.aliases,source.enabled,source.catalog,source.etag,source.lastModified,source.lastSuccess,source.lastError);
    }
    private static String[] parts(String stamp){
        if(stamp==null)return null;String[] p=stamp.split("\\|",-1);
        return p.length==4&&p[0].equals("CH1")&&p[1].matches("[ANU]")&&p[2].matches("[0-9a-f]{64}")&&!p[3].isEmpty()?p:null;
    }
    public static Kind kind(String stamp){String[] p=parts(stamp);return p==null?Kind.UNKNOWN:p[1].equals("A")?Kind.ARTICLE_ANALYSIS:p[1].equals("N")?Kind.NEWS_BRIEF:Kind.UNKNOWN;}
    public static String reference(String stamp){String[] p=parts(stamp);return p==null?"":p[2];}
    public static String parser(String stamp){String[] p=parts(stamp);return p==null?stamp:p[3];}
    public static String forRoute(String stamp,String before,String after){return Objects.equals(before,after)?stamp:parser(stamp);}
    static ExtractionResult snapshot(SourceRecord source,ExtractionResult result){
        String[] p=parts(source.adapterVersion);
        if(p==null||!p[3].equals(result.adapterVersion))return result;
        return new ExtractionResult(result.body,result.providerTitle,result.displayTitle,result.completeness,result.reason,
                result.method,source.adapterVersion,result.extractedTextNodes,result.excludedTextNodes,result.unknownTextNodes,result.publication);
    }
    public static boolean automatic(SourceVersionRecord version){return "full".equals(version.completeness)&&(version.publication==null?kind(version.adapterVersion)==Kind.ARTICLE_ANALYSIS:(kind(version.adapterVersion)==Kind.ARTICLE_ANALYSIS||kind(version.adapterVersion)==Kind.NEWS_BRIEF)&&version.contextClass==SourcePublication45.Context.DATED);}
    public static String label(SourceVersionRecord version){Kind k=kind(version.adapterVersion);return k==Kind.ARTICLE_ANALYSIS?"Статья / аналитика":k==Kind.NEWS_BRIEF?"Короткая новость / RSS":"Тип материала не подтверждён";}
    public static String publicationLabel(SourceVersionRecord version){return version.publication==null?"":version.publication.label(version.contextClass);}
}

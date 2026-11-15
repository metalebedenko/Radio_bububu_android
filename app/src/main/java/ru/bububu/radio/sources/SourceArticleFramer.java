package ru.bububu.radio.sources;

public final class SourceArticleFramer {
    public static final String VERSION = "source-frame-v1";
    public static final String VERSION45="source-frame-v2", NORMALIZER45="semantic-source45-v1", PROJECTION45="source-speech45-v1";
    public static final class Framed {
        public final String spokenText, versionHash, bodyHash;
        public final int bodyStart, bodyEnd;
        private Framed(String text,int start,String hash,String bodyHash){spokenText=text;bodyStart=start;bodyEnd=text.length();versionHash=hash;this.bodyHash=bodyHash;}
    }
    private SourceArticleFramer() { }
    public static String prefix45(String label,String title,String method,SourcePublication45 publication,SourcePublication45.Context context){
        return label+"\n\n"+(!"public_preview_text".equals(method)&&!title.isEmpty()?title+"\n\n":"")+publication.context(context);
    }
    public static String key45(String bodyHash,String title,String label,String quality,String reason,String method,String adapter,SourcePublication45 publication,SourcePublication45.Context context){
        return SourceLimits.snapshotVersion(VERSION45,bodyHash,title,label,quality,reason,method,adapter,NORMALIZER45,publication.profileConfigSha,SourcePublication45.CODEC,publication.identityDigest(),SourcePublication45.CONTEXT_POLICY,context.name(),publication.context(context),PROJECTION45);
    }
    public static Framed frame45(String sourceLabel,ExtractionResult result,SourcePublication45 publication,SourcePublication45.Context context)throws SourceFailure{
        if(result==null||!result.canEnqueue())throw new SourceFailure("NO_READABLE_TEXT");
        String label=SemanticTextNormalizer.normalize(sourceLabel).replace('\n',' ');if(label.isEmpty()||label.length()>SourceLimits.MAX_SOURCE_NAME)throw new SourceFailure("SOURCE_NAME_LIMIT");
        if(result.providerTitle.length()>SourceLimits.MAX_PROVIDER_TITLE)throw new SourceFailure("TITLE_LIMIT");
        try{String prefix=prefix45(label,result.providerTitle,result.method,publication,context);String spoken=prefix+result.body;if(spoken.length()>SourceLimits.MAX_SOURCE_SPOKEN_UTF16)throw new SourceFailure("TEXT_LIMIT");return new Framed(spoken,prefix.length(),key45(result.bodyHash,result.providerTitle,label,result.completeness,result.reason,result.method,result.adapterVersion,publication,context),result.bodyHash);}
        catch(IllegalArgumentException invalid){throw new SourceFailure("PUBLICATION_CONTEXT_LIMIT");}
    }
    public static Framed frame(String sourceLabel, ExtractionResult result) throws SourceFailure {
        if(result==null || !result.canEnqueue())throw new SourceFailure("NO_READABLE_TEXT");
        String label=SemanticTextNormalizer.normalize(sourceLabel).replace('\n',' ');
        if(label.isEmpty() || label.length()>SourceLimits.MAX_SOURCE_NAME)throw new SourceFailure("SOURCE_NAME_LIMIT");
        if(result.providerTitle.length()>SourceLimits.MAX_PROVIDER_TITLE)throw new SourceFailure("TITLE_LIMIT");
        boolean post="public_preview_text".equals(result.method);
        String prefix=label+"\n\n"+(!post && !result.providerTitle.isEmpty()?result.providerTitle+"\n\n":"");
        String spoken=prefix+result.body;
        if(spoken.length()>SourceLimits.MAX_TEXT)throw new SourceFailure("TEXT_LIMIT");
        String version=SourceLimits.snapshotVersion(result.bodyHash,result.providerTitle,label,result.completeness,result.reason,result.adapterVersion,SemanticTextNormalizer.VERSION,VERSION);
        return new Framed(spoken,prefix.length(),version,result.bodyHash);
    }
}

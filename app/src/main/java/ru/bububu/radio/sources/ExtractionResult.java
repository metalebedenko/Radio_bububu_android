package ru.bububu.radio.sources;

public final class ExtractionResult {
    public final String body, providerTitle, displayTitle, completeness, reason, method, adapterVersion, bodyHash;
    public final String normalizerVersion = SemanticTextNormalizer.VERSION;
    public final String framingVersion = SourceArticleFramer.VERSION;
    public final int extractedTextNodes, excludedTextNodes, unknownTextNodes;
    public final SourcePublication45 publication;
    public ExtractionResult(String body, String providerTitle, String displayTitle, String completeness, String reason, String method, String adapterVersion) {
        this(body, providerTitle, displayTitle, completeness, reason, method, adapterVersion, 0, 0, 0);
    }
    public ExtractionResult(String body, String providerTitle, String displayTitle, String completeness, String reason, String method, String adapterVersion, int extracted, int excluded, int unknown) {
        this(body,providerTitle,displayTitle,completeness,reason,method,adapterVersion,extracted,excluded,unknown,null);
    }
    public ExtractionResult(String body,String providerTitle,String displayTitle,String completeness,String reason,String method,String adapterVersion,int extracted,int excluded,int unknown,SourcePublication45 publication) {
        this.publication=publication;
        this.body = body == null ? "" : body; this.providerTitle = providerTitle == null ? "" : providerTitle;
        this.displayTitle = displayTitle == null ? this.providerTitle : displayTitle;
        this.completeness = completeness; this.reason = reason == null ? "" : reason;
        this.method = method; this.adapterVersion = adapterVersion;
        bodyHash = SemanticTextNormalizer.hash(this.body);
        extractedTextNodes = extracted; excludedTextNodes = excluded; unknownTextNodes = unknown;
    }
    public boolean canEnqueue() { return !body.isEmpty() && ("full".equals(completeness) || "summary".equals(completeness) || "unknown".equals(completeness)); }
    public ExtractionResult withPublication(SourcePublication45 p){return new ExtractionResult(body,providerTitle,displayTitle,completeness,reason,method,adapterVersion,extractedTextNodes,excludedTextNodes,unknownTextNodes,p);}
}

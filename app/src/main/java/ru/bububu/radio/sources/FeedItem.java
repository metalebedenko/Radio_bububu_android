package ru.bububu.radio.sources;

public final class FeedItem {
    public final String providerId, url, title, publishedRaw, updatedRaw, channelUsername, postId;
    public final Long publishedAtMillis, updatedAtMillis;
    public final ExtractionResult inline;
    public FeedItem(String providerId, String url, String title, String publishedRaw, String updatedRaw, Long publishedAtMillis, Long updatedAtMillis, ExtractionResult inline) {
        this(providerId,url,title,publishedRaw,updatedRaw,publishedAtMillis,updatedAtMillis,inline,null,null);
    }
    public FeedItem(String providerId, String url, String title, String publishedRaw, String updatedRaw, Long publishedAtMillis, Long updatedAtMillis, ExtractionResult inline, String channelUsername, String postId) {
        this.providerId=providerId; this.url=url; this.title=title == null ? "" : title;
        this.publishedRaw=publishedRaw; this.updatedRaw=updatedRaw; this.publishedAtMillis=publishedAtMillis;
        this.updatedAtMillis=updatedAtMillis; this.inline=inline; this.channelUsername=channelUsername; this.postId=postId;
    }
}

package ru.bububu.radio.sources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FeedDocument {
    public final String title, finalUrl, type;
    public final List<FeedItem> items;
    public final boolean itemLimitReached;
    public FeedDocument(String title, String finalUrl, String type, List<FeedItem> items, boolean itemLimitReached) {
        this.title=title; this.finalUrl=finalUrl; this.type=type;
        this.items=Collections.unmodifiableList(new ArrayList<>(items)); this.itemLimitReached=itemLimitReached;
    }
}

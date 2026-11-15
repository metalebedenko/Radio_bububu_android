package ru.bububu.radio.sources;

import java.util.List;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.Station;

/** A conservative UTF-8 JSON bound for every sentence completed, before queue admission. */
public final class RadioStateSizeBudget {
    private RadioStateSizeBudget() {}
    public static long fullyCompletedBytes(List<Station> catalog) {
        long bytes=256;
        for(Station station:catalog) {
            // Both the current-station value and station cursor map; @after marker includes ID.
            bytes=add(bytes,4L*quoted(station.id)+1024);
            for(Article article:station.articles) {
                bytes=add(bytes,quoted(station.id)+quoted(article.id)+quoted(article.version)+512);
                for(int i=0;i<article.sentences.size();i++) bytes=add(bytes,Integer.toString(i).length()+1L);
            }
        }
        return bytes;
    }
    public static void requireFits(List<Station> catalog) {
        if(fullyCompletedBytes(catalog)>SourceLimits.MAX_RADIO_BYTES)
            throw new IllegalStateException("Достигнут предел сохранённого прогресса 16 МиБ");
    }
    private static long quoted(String value) { return 2L+6L*value.length(); }
    private static long add(long a,long b) {
        if(b<0 || a>Long.MAX_VALUE-b) throw new IllegalStateException("Progress budget overflow");
        return a+b;
    }
}

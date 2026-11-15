package ru.bububu.radio.core;

import ru.bububu.radio.sources.SourceRecord;
/** A stale runtime source Article never grants standalone reader access after a Book deletion. */
public final class ReadAdmission {
    private ReadAdmission() { }
    public static boolean allowed(NoteBook book,String stationId,Article article) {
        if(book==null||article==null||book.storageUncertain())return false;
        for(SourceRecord source:book.allSources())if(source.stationId.equals(stationId)) {
            if(!book.isSourceActive(source.id))return false;
            NoteBook.Material material=book.material(stationId,article.id);
            return material!=null&&material.sourceVersionKey!=null
                &&book.queueMembership(stationId,article,material.sequence,material.sourceVersionKey)==NoteBook.Membership.ALLOWED;
        }
        return true; // fixtures/own note text keep their existing local reader path
    }
}

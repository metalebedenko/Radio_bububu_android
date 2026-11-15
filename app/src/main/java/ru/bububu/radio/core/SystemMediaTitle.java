package ru.bububu.radio.core;

import java.util.List;
import ru.bububu.radio.sources.SourceRecord;
import ru.bububu.radio.sources.SourceVersionRecord;

/** Read-only title projection for public notifications and MediaSession metadata. */
public final class SystemMediaTitle {
    public static final String NEUTRAL = "Радио Бубубу";
    public static final String PRIVATE_NOTE = "Личная заметка";

    private SystemMediaTitle() { }

    public static String resolve(NoteBook book, boolean authorityKnown, String stationId,
                                 Article article, List<Station> fixtures) {
        if (!authorityKnown || book == null || stationId == null || article == null) return NEUTRAL;
        try {
            if (book.storageUncertain()) return NEUTRAL;
            NoteBook.Material material = book.material(stationId, article.id);
            if (material != null) {
                if (!material.version.equals(article.version)
                        || book.queueMembership(stationId, article, material.sequence, material.sourceVersionKey)
                        != NoteBook.Membership.ALLOWED) return NEUTRAL;
                if (material.noteId != null) return PRIVATE_NOTE;
                // A missing noteId alone does not establish public provenance (e.g. legacy text).
                SourceVersionRecord version = material.sourceVersionKey == null ? null
                        : book.sourceVersion(material.sourceVersionKey);
                SourceRecord source = version == null ? null : book.source(version.sourceId);
                if (version == null || source == null
                        || !material.sourceVersionKey.equals(version.recordKey)
                        || !material.articleId.equals(version.articleId)
                        || !material.version.equals(version.versionKey)
                        || !material.stationId.equals(source.stationId)
                        || !material.title.equals(version.displayTitle)) return NEUTRAL;
                return version.displayTitle;
            }
            // Fixture IDs or labels alone are not enough; match the immutable bundled content.
            if (fixtures != null && OrdinaryRadio.builtin(stationId)) {
                for (Station fixture : fixtures) if (fixture.id.equals(stationId)) {
                    for (Article exact : fixture.articles) {
                        if (exact.id.equals(article.id) && exact.version.equals(article.version)
                                && exact.title.equals(article.title) && exact.text.equals(article.text)
                                && exact.sentenceStarts.equals(article.sentenceStarts)) return exact.title;
                    }
                }
            }
        } catch (RuntimeException unavailable) {
            // An uncertain/stale Book must never make the snapshot's private title public.
        }
        return NEUTRAL;
    }
}

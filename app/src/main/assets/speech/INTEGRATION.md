# C10 offline pronunciation data

Runtime version: c10-speech-data-v2-reference. Final resource: 143 entries = 128 abbreviations (79 SPELL, 35 WORD, 14 AMBIGUOUS) + 15 TEXT. The immutable research package c10-speech-data-v1 supplied 142 entries; the only added record is lowercase см. from the direct owner instruction on 28 October 2026.

The bounded six-column TSV preserves key, reading, case, legacy flag and provenance ID. All 33 letter names follow the project table. SPELL keeps every letter name and actual comma punctuation in one atom; WORD has a supplied whole/hybrid reading; AMBIGUOUS has no spoken substitution. Only source-v2 uses the expanded dictionary and cautious 2–6 pure-Russian consonant-CAPS fallback. Source codes, units, combining sequences, initials and headings are protected. The shared legacy facade still uses exactly nine legacy=true records.

Active TEXT: 10 exact INLINE variants (6 readings) and 2 REFERENCE_NEXT_SENTENCE keys. The latter accept only an entire canonical body sentence см. or См., followed by lowercase далее / выше / ниже or the preserved lowercase также: cue. Numeric context is rejected. Output stays exactly Смотрите. in one atom. This is project-owned context policy, OWNER-2026-10-28-REFERENCE; the source research package was not rewritten. No global splitter change, canonical merge, progress migration or per-letter/per-word NN/WAV procedure is added.

Three supplied TEXT records remain present but DISABLED: см. также, См. также (SEE_ALSO_SAME_SENTENCE) and напр. (EXAMPLE_BEFORE_WORD). Their required following text is normally separated by the existing canonical splitter. No extra workaround is introduced to inflate coverage.

Data changes: compact serialization, renamed closed context enums, the three inactive marks, compact provenance, the explicit owner reference delta, and immutable runtime fingerprint. All supplied pronunciation fields are unchanged. Unknown abbreviations and unsupported native input are not silently skipped.

Attribution/terms accompany the corresponding derived data: NOTICE.md, notices/WIKTIONARY_ATTRIBUTION.tsv, notices/CC-BY-SA-4.0.txt and notices/NEMO_LICENSE.txt. Compact provenance.tsv links IDs/tokens to source evidence; semicolon-separated provenance IDs reference both source/project records. Full research provenance remains private. No raw external list, full article, IPA, generator or executable rule is included. Data license terms do not relicense application code.

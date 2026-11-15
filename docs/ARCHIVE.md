# Archive record

The owner ended development and requested a public portfolio archive on **15 November 2026**. This was a non-commercial Android prototype, never a production service. The final development snapshot is UX3 build 60. No further development is planned.

## Historical verification

| Version | Established result | Boundary |
|---|---|---|
| UX3 54 | Main UI, update over the earlier interface, preservation of the compared state, and three instrumented layout checks on a Samsung Galaxy S24 Ultra. | Did not establish the complete live-download and audio path for every source type. |
| UX3 57 | Five real flows from one website/channel input to a selected stored version and an audible beginning. | Did not establish full-length reading or correctness of every phrase. |
| UX3 58 | On identical original texts, accepted sentences rose from 68/84 to 79/84. | A bounded corpus result, not a general pronunciation score. |
| UX3 59 | All 82 sentences of ten re-extracted texts produced nonempty native PCM. A new five-document set passed text preparation for 34/36 sentences. | One English caption had nonempty audio but no reliable lexical ASR confirmation. Accepted audio is not necessarily natural or correct speech. |
| UX3 60 | 1,039 unit tests passed, 10 were skipped, zero failures/errors. Build, lint, pinned model checks and installation completed on 5 November 2026. Focused text regressions passed. | Final full-length phone audio and the third unseen control set: **NOT_RUN**. |

The 84-to-82 sentence change followed re-extraction of the same publications: a structurally proven duplicate image description and feed syndication footer were removed. Comparing 68/84 directly with 82/82 would therefore mix extraction changes with speech changes. The paired text-preparation result on the same final inputs was 66/82 for the original version and 82/82 for build 59.

The build 60 unit-test total was 1,049, comprising 1,039 passes and 10 skips. This was checked against all 122 JUnit XML result files and the successful build log. Skipped cases required optional historical fixtures that are not part of the public source archive.

Historical APK 60 SHA-256: `3825affd549e7da64efe81b0ac568f3618de468c7fab3b68efe09dbf9223a45b`. This identifies the historical artifact with its original signing key and bundled models. It is not a claim that a fresh archive build will be bit-identical.

## Text-preparation findings

The stored source text is separate from the speech projection. Rules operate on expression classes and context, preserving the canonical text and positional mapping.

The last development cycle addressed these recurring classes:

- typographic word hyphens that rejected entire sentences;
- Roman-numbered quarters and currency ranges with explicit scale;
- calendar ranges, age adjectives and years following seasons;
- decimal quantities with named units and bounded chemical notation;
- structural photo-credit separators;
- Telegram paragraph-leading pictographs, boundary controls and standalone URL lines;
- duplicated content introduced by the RSS/HTML structure, corrected at extraction time.

For example, a quarter written as `III` was previously read as letter names. A compact dollar range caused an entire sentence to be rejected. Nonbreaking hyphens in a technology article rejected five sentences even though the player reached the end of the article. These are different failure modes and were measured separately.

## Earlier ML comparison

An earlier experiment used 120 synthetic cases and 24 public articles, including 63 annotated real-text focuses. Against the then-current baseline, each of a constrained T5 variant and two RuBERT variants gave 0 improvements and 1 regression on those 63 focuses. General-purpose normalizers improved more cases but could lose numbers, signs or parts of identifiers.

The application therefore retained bounded Java rules rather than generative article rewriting. These observations describe one historical evaluation setup. They are not a general benchmark or ranking of the models.

## Measurement method

1. Compare the production text-preparation output for every sentence, fixing the candidate sources, dictionaries and input versions first.
2. Invoke the installed Android Silero path through an isolated test harness. Save fragment status, exact input identity, source offsets and PCM hashes.
3. Check the generated audio using local ASR, with targeted beginning/middle/end and changed-phrase checks.
4. Separately exercise ordinary UX3 playback through the phone speaker and an external microphone. Attribute a result only to the selected article version.

An absent ASR match was not treated as a synthesis failure. PCM and generator status were checked separately. One early microphone capture was 13 seconds shorter than its wall-time window and was excluded as proof of continuous playback. The later capture method checked hardware sample times through CoreAudio.

Once a control set was revealed, it became a regression set. It was no longer described as an independent blind evaluation.

## Unresolved limits

- Foreign words and names can be read as sequences of letter names.
- Unknown characters and complex expressions can still reject a speech fragment.
- Generic HTML and public Telegram previews do not always establish publisher completeness.
- A standalone URL longer than the raw-fragment limit can remain rejected.
- The rules do not resolve every grammatical ambiguity in Russian.
- Full-quality physical microphone dictation, final full-length build 60 playback and 16 KiB Android devices were not accepted.
- Publisher layouts, feeds and network reachability can change after the dated tests.

## Public archive scope

The public package is intended to preserve application sources, own regression tests, pinned build configuration, dependency notices and a concise record of the experiment. It excludes signing secrets, user audio and text, working caches, private HTTP captures and complete copies of third-party articles.

Screenshots must name the actual build they show. Design mockups must be labelled as mockups. A successful source build, a generated WAV and physical sound from the phone are distinct pieces of evidence.

## Earlier interface

The archive release includes `RadioBububu-legacy-ui-source.tar.gz`, a sanitized source snapshot of the interface before UX3. Its original build settings are retained for reference; that historical variant was not rebuilt during this publication. Working journals, signing keys and local metadata are excluded. Use the current `main` branch and BUILDING instructions for the reproducible archive build.

## Archive build verification · 15 November 2026

The sanitized tree built successfully with a standard Android debug identity and no original signing key. Baseline: 931 passed, 7 skipped. Experimental: 1,039 passed, 10 skipped. Both lint tasks, both application APKs, the experimental instrumentation APK and exact model verification passed. Runtime inputs were restored from official distributions and checked against the existing pins. Two previously unlisted Maven metadata files were independently verified and added to the checksum manifest; no version or existing checksum was changed.

See [archive-verification.json](archive-verification.json) for the publication checks. No phone was used during this archival task; these checks do not complete the historical audio acceptance.

The publication recovery check downloaded the public model release, verified its complete SHA-256, restored its inputs into a fresh GitHub clone and successfully assembled the experimental APK again. The public legacy source archive was also downloaded and verified. The original signing key was absent. This verifies archive recovery and buildability, not physical audio quality.

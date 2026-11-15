# Project decisions

## 2026-11-15 · End development and preserve a public archive

The owner ended further development and authorized cleanup, documentation, publication to the existing GitHub repository and removal of local project copies after verifying preservation. The repository is intended as a non-commercial portfolio and research archive.

Own application code uses MIT. Third-party source, model weights and derived runtime packages retain their own terms. Keys, credentials, private recordings, working caches and complete publisher corpora are excluded from the public source tree.

The archive records incomplete acceptance explicitly. Build 60 was built and installed, but its final full-length phone audio evaluation was not run.

## 2026-11-05 · Make UX3 the main interface

UX3 became the primary Android UI while preserving the existing source, playback and note logic. A separate design-preview application was no longer the active product. The previous interface was retained as a historical reference, not maintained as a second product.

## Text preparation · Keep source identity separate from speech

Source text is stored as a versioned record. Speech preparation creates a projection tied to canonical UTF-16 positions. This supports exact playback continuation, regression comparison and attribution of failures without rewriting the stored publication.

Model-based normalization experiments did not justify replacing this path. The implementation uses context-bounded rules and tests for both positive and negative examples.

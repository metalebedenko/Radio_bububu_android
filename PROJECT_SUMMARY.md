# Project summary

Radio Bububu is an archived, non-commercial Android prototype for listening to selected news articles and recording local voice notes. The UI and speech-processing logic are Russian. Development ended in November 2026 before production release.

The final development snapshot is UX3 build 60. Historical checks recorded 1,039 passing unit tests, 10 skips, a successful build and an installation on 5 November 2026. Its final full-length audio evaluation and the third unseen control set were not run. Earlier versions have separate, bounded phone and speech evidence.

The public repository preserves the implementation and research approach. It does not contain the original signing key, private recordings, complete news corpora or local caches. Models and native dependencies retain separate licensing terms.

Start with [README.md](README.md), [docs/BUILDING.md](docs/BUILDING.md) and [docs/ARCHIVE.md](docs/ARCHIVE.md). Build/release verification for the archived publication must be recorded separately from the historical development checks.

Archive publication checks on 15 November 2026: baseline 931 passed / 7 skipped, experimental 1,039 passed / 10 skipped; builds, lint and pinned model checks passed with restored inputs and ordinary debug signing. Publication and fresh GitHub clone verification are recorded in `docs/archive-verification.json`.

# Repository instructions

This is an archived Android research prototype. Do not resume feature development, run device experiments or publish new releases unless explicitly requested.

Read `README.md`, `PROJECT_SUMMARY.md` and the relevant document under `docs/` before making a change. Do not treat historical build or phone results as current acceptance.

Keep these boundaries:

- Preserve original source text and its UTF-16 position mapping when changing speech preparation.
- Keep downloading, adding a stored version to the queue and starting playback as distinct user actions.
- Do not add signing secrets, user audio/text, credentials, private HTTP captures, full publisher articles or local caches to Git.
- Check third-party and model conditions separately from the MIT license of own code.
- Do not silently replace pinned model inputs or disable checksum verification.
- Prefer the smallest justified cleanup. Run checks appropriate to any changed behavior.
- Distinguish text preparation, native PCM generation and sound observed from a physical device in reports.

The `baseline` flavor does not include the native models and cannot establish the full experimental path. Use `docs/BUILDING.md` for archive-specific setup and verification.

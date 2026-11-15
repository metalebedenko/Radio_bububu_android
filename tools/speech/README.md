# Full-article speech-planner audit

This optional research harness freezes production classes, speech data and sources, then compares the text sent to the model for every sentence of immutable article records. It does not synthesize sound or control Android.

Build the experimental Java classes first and restore the pinned model ZIP. Set `JAVA_HOME`, `BUBUBU_HOST_LAYOUTLIB` and `BUBUBU_SPEECH_QA_DIR`. The latter contains `corpus/train` and `corpus/holdout` JSON records accepted by `CorpusSpeechAudit.java`; this archive deliberately does not redistribute full publisher articles.

```sh
python3 tools/speech/host.py snapshot candidate
python3 tools/speech/host.py run candidate train candidate-train
python3 tools/speech/compare.py before-train candidate-train
```

For a held-out run, supply a separately created freeze receipt with `snapshot_manifest_sha256` (and, for a paired baseline, `baseline_snapshot_manifest_sha256`). `--input-set` selects another corpus folder under the same QA root. Never label a previously inspected or tuned-on set as unseen.

Reports distinguish accepted input, rejection and changed pronunciation text. They cannot prove correctness or naturalness of audio. See `docs/ARCHIVE.md` for how native PCM and physical playback were evaluated separately.

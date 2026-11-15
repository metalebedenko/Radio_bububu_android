# Building the archived prototype

## Requirements

- JDK 17 (`JAVA_HOME` or `java` on `PATH`).
- Android SDK platform 36 and Build Tools 36.0.0 (`ANDROID_HOME`, or a local `local.properties` file).
- Python 3.10+ for the optional model/runtime restoration tool.
- Gradle Wrapper 8.13 and Android Gradle Plugin 8.13.2 are pinned. The first build needs network access for the wrapper and Maven dependencies. Later builds can use `--offline` with a populated cache.

No account, API key, private signing key or original developer machine is required.

## Baseline: source-only build

The baseline flavor uses the Android speech engine and omits native Silero/GigaAM. It is useful for studying the source/UI and running the common unit tests. It does not reproduce the full local-model experiment.

```sh
./tools/build-local.sh :app:testBaselineDebugUnitTest :app:lintBaselineDebug :app:assembleBaselineDebug
```

Output: `build/app/outputs/apk/baseline/debug/app-baseline-debug.apk`.

## Experimental: reproduce the local-model configuration

Read [THIRD_PARTY.md](../THIRD_PARTY.md) before obtaining the models. In particular, the Silero model and its exported data use **CC BY-NC-SA 4.0**, separately from the application's MIT license.

```sh
python3 tools/restore-inputs.py
./tools/build-local.sh :app:testExperimentalDebugUnitTest :app:lintExperimentalDebug :app:assembleExperimentalDebug :app:assembleExperimentalDebugAndroidTest :app:verifyBundledModelAssets
```

The helper downloads the model companion archive from this repository's archive release and restores the native runtime from the official Maven and sherpa-onnx distributions. It checks SHA-256 and size before accepting each input. Only the three named arm64 files are taken from the sherpa AAR. No downloaded script is executed. Restored files live under ignored `.private/` and include their license notices.

For an already downloaded model companion archive:

```sh
python3 tools/restore-inputs.py --models-bundle /path/to/RadioBububu-models-archive-2026-11-15.zip
```

The URL, expected size and hash are in [`third_party/model-inputs.json`](../third_party/model-inputs.json). Runtime source URLs and extracted-member hashes are in [`third_party/runtime-inputs.json`](../third_party/runtime-inputs.json). Do not substitute arbitrary latest weights: the model adapter and tests expect these exact files.

Output: `build/app/outputs/apk/experimental/debug/app-experimental-debug.apk`. This configuration requires Android 9+ and `arm64-v8a`. The historical phone had 4 KiB memory pages; 16 KiB devices are not verified.

## Signing and device tests

Both debug flavors use Android's standard local debug signing by default. A fresh key will not update an installed historical APK signed with the original private key. This archive does not distribute that key.

An explicitly supplied `-PbububuSigningProperties=/absolute/path/signing.properties` may select another local identity. Its properties are `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. A relative `storeFile` is resolved against the properties file's directory. Keep both files outside Git. Missing explicit inputs fail rather than silently selecting a different key.

The commands above compile the instrumentation APK but do **not** run it or operate a phone. Device tests can modify the application's state. Select an appropriate test device and scenario before running them. Building the project is not evidence of audible output or microphone transcription.

## Test results and historical research

JUnit XML and HTML reports are under `build/app/test-results/` and `build/app/reports/`. Some historical corpus tests use `Assume` when private external fixtures are absent. A skipped test is not a passed test. The archived physical evaluation is described separately in [ARCHIVE.md](ARCHIVE.md).

`tools/speech/` retains the full-article comparison harness. It requires your own compatible corpus and `BUBUBU_SPEECH_QA_DIR`, `JAVA_HOME` and `BUBUBU_HOST_LAYOUTLIB` (Android Studio's `layoutlib.jar`). Its optional historical default points into ignored `.private/`; the original full news corpus is not distributed. The normal Gradle unit suite is self-contained apart from its explicitly skipped historical cases.

The release contains model inputs and notices, not a production-ready APK. Native dependency downloads are pinned to their official distributions. Anyone redistributing a newly built APK must account for the actual bundled dependency notices as well as the model conditions.

# Third-party components and model provenance

This is an archived, noncommercial research prototype. The application was not released as a production service. The original application code is published under the MIT license in `LICENSE`. Third-party runtime libraries, speech data, and model weights retain their separate licenses. No sponsorship or endorsement by the model authors is implied.

This document records the components used in the historical Android build. Checked against the upstream license documents on 15 November 2026. It does not grant additional rights to third-party materials.

## Silero Russian TTS

- Author: **Silero Team and silero-models contributors**.
- Project: <https://github.com/snakers4/silero-models>.
- Model: **`v5_6_ru`**, voice **`eugene`**, used at 24 kHz.
- Original model URL: <https://models.silero.ai/models/tts/ru/v5_6_ru.pt>.
- Historical original model SHA-256: `04cc9b1d08ce38bcaa9d02c488d80d3e6806d17f7eaa7c95982f36a3cbf50b2e` (147,664,410 bytes).
- Upstream license: **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International (CC BY-NC-SA 4.0)**.
- Upstream license file: <https://github.com/snakers4/silero-models/blob/master/LICENSE>.
- Full legal text: <https://creativecommons.org/licenses/by-nc-sa/4.0/legalcode.en>.

The Android integration uses a local full-JIT export split into `core.pt`, `homosolver.pt`, `accentor.pt`, and `resources.json`. This packaging and export are project modifications. The `eugene` voice was not retrained. The model data and any adaptations distributed by this project remain under CC BY-NC-SA 4.0. A technical format conversion alone need not constitute Adapted Material, but it does not remove the upstream license.

The license permits noncommercial sharing with attribution and license notices. Adapted material must retain the same license elements. Retain the upstream and project modification notices when redistributing. No warranty is provided. Commercial use is not granted by this license. The MIT exception described upstream for **CIS Base** models does not apply to `v5_6_ru`.

The model ZIP supplied with the archive release is the exact historical input required by `PackCatalog` and Gradle verification. Its companion notice applies to every model file inside it. Keep the ZIP and [its companion notices](third_party/model-licenses/NOTICE.txt) together. The archive does not claim that the entire Android application or every synthesized audio file is automatically licensed under the model's CC license. Rights in source articles and other input content are separate.

## GigaAM speech recognition

- Original project: <https://github.com/salute-developers/GigaAM>.
- Original model family: **GigaAM-v3 E2E RNNT**.
- Copyright: **2024 GigaChat Team**.
- License: **MIT**, <https://github.com/salute-developers/GigaAM/blob/main/LICENSE>.
- Used ONNX conversion: <https://huggingface.co/csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16>.
- Exact conversion revision: `a6039be7cee829a9044a69ac0ebaf1c191217c97`.
- Conversion license: <https://huggingface.co/csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16/blob/a6039be7cee829a9044a69ac0ebaf1c191217c97/LICENSE>.

The four inputs are `encoder.int8.onnx`, `decoder.onnx`, `joiner.onnx`, and `tokens.txt`. They are packaged unchanged from the pinned conversion. The conversion's MIT notice must accompany copies. The upstream checkpoint-to-ONNX equivalence was not independently proved by this archive. This is separate from the verified identity of the four conversion files.

## Speech preparation data included in source

See [`app/src/main/assets/speech/NOTICE.md`](app/src/main/assets/speech/NOTICE.md) and the associated provenance table for the scope of each contribution.

| Data | License and attribution retained in the repository |
| --- | --- |
| Selected Russian Wiktionary pronunciation data | CC BY-SA 4.0. Revision-specific attribution is in `app/src/main/assets/speech/notices/WIKTIONARY_ATTRIBUTION.tsv`; license text is `CC-BY-SA-4.0.txt`. The selected and adapted dictionary data retain these terms. |
| Selected NVIDIA NeMo text-normalization entries | Apache-2.0, exact revision `ddadfb2a38d2bc6b8cc6232c4f915eb60f500688`. The source is the Russian `whitelist.tsv`; `NEMO_LICENSE.txt` and local modification/provenance notes are retained. |
| Unicode 17.0 properties used for Telegram emoji | Unicode License v3, Copyright 1991–2026 Unicode, Inc. The pinned generator identifies the three UCD files and their SHA-256 values. `UNICODE_LICENSE.txt` is retained. |

Source links: [NeMo license](https://github.com/NVIDIA/NeMo-text-processing/blob/ddadfb2a38d2bc6b8cc6232c4f915eb60f500688/LICENSE), [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/legalcode.en), [Unicode License v3](https://www.unicode.org/license.txt).

## Runtime and build dependencies

These are separate from the model licenses. Version pins are retained in `app/build.gradle`, `app/gradle.lockfile`, and Gradle verification metadata.

| Component used by the historical experimental build | Upstream license |
| --- | --- |
| PyTorch Android full-JIT 2.1.0 | [BSD-style three-clause license with its original copyright notices](https://github.com/pytorch/pytorch/blob/v2.1.0/LICENSE) |
| fbjni-java-only 0.2.2 | [Apache-2.0](https://github.com/facebookincubator/fbjni/blob/v0.2.2/LICENSE) |
| SoLoader nativeloader 0.10.5 | [Apache-2.0](https://github.com/facebook/SoLoader/blob/v0.10.5/LICENSE) |
| sherpa-onnx 1.13.8 | [Apache-2.0](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/LICENSE) |
| ONNX Runtime 1.28.2, included in that sherpa AAR | [MIT](https://github.com/microsoft/onnxruntime/blob/v1.28.2/LICENSE) |
| Gradle wrapper | Apache-2.0; original copyright headers are retained in `gradlew` and `gradlew.bat` |

The original Android APK contained additional transitive runtime components, including Kotlin, OkHttp, Okio, AndroidX and the C++ runtime. Their licenses are not replaced by the model license. Rebuilding or redistributing a binary requires retaining the applicable notices for the actual packaged dependencies, including third-party code bundled inside native libraries.

## Content and examples

Publisher articles, Telegram posts, photographs, logos, and private test recordings are not project-owned training data. Their availability over HTTP does not grant permission to republish an entire test corpus. The intended public archive contains synthetic test fixtures, links, metadata, and concise factual test results. Raw downloaded articles and test recordings are excluded unless a separate permission applies.

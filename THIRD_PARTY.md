# Third-Party Notices & Software Bill of Materials (BOM)

The LIME (Liuqing Input Method Engine) codebase is open-source under the terms of the **[BSD 3-Clause License](./LICENSE)**.

This project references, links against, or bundles the following third-party open-source components, fonts, lexicon datasets, and model artifacts during build, runtime, or distribution. The tables below detail the origin and licensing of each component. Preserved license texts and distribution verification items are documented at the end of this page.

---

## 1. Upstream Project Heritage

| Project | License | Copyright Holder | Description / Purpose | Upstream URL |
| :--- | :--- | :--- | :--- | :--- |
| **YuyanIme** | BSD-3-Clause | Wang Ying (王莹) | Initial fork baseline and early architecture exploration for LIME | [GitHub](https://github.com/gurecn/YuyanIme) |
| **yuyansdk** | BSD-3-Clause | Wang Ying (王莹) | Initial input method low-level interfaces and prototype reference | [GitHub](https://github.com/gurecn/yuyansdk) |

---

## 2. Native C++ Engine & Libraries

The following native libraries are downloaded by CMake into the git-ignored directory `app/src/main/cpp/deps/` and statically linked into `liblime_engine.so`. Selected licenses are listed below; full texts are preserved within the APK under [`assets/licenses/`](app/src/main/assets/licenses/NOTICE.md).

| Component | Pinned Version | License | Copyright Holder | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| **librime** | 1.17.0 | BSD-3-Clause | RIME Developers | Core RIME input method algorithm engine |
| **librime-lua** | ec52e48 | BSD-3-Clause | librime-lua Developers | RIME Lua scripting extension engine |
| **lua** | 5.4.8 | MIT | Lua.org, PUC-Rio | Embedded scripting runtime environment |
| **librime-predict**| 920bd41 | BSD-3-Clause | RIME Developers | Core phrase pair prediction and association module |
| **leveldb** | 1.23 | BSD-3-Clause | The LevelDB Authors | High-performance local key-value storage (user vocabulary frequency and state) |
| **marisa-trie** | 0.3.1 | BSD-2-Clause | Susumu Yata | Compact trie data structure (used under its BSD-2-Clause license) |
| **yaml-cpp** | 0.8.0 | MIT | Jesse Beder | YAML schema configuration and parser |
| **opencc** | 1.1.9 | Apache-2.0 | BYVoid and OpenCC Contributors | Simplified/Traditional Chinese and variant character conversion |
| **boost** | 1.89.0 | BSL-1.0 | Boost Contributors | C++ standard library extensions and system utilities |

---

## 3. Android & JVM Libraries

Production runtime dependencies use industry-standard permissive licenses (Apache-2.0 / MIT):

| Dependency | Integration Method | License | Origin & Purpose |
| :--- | :--- | :--- | :--- |
| **sherpa-onnx** (1.13.7) | `app/libs/sherpa-onnx-1.13.7.jar` | Apache-2.0 | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) On-device offline speech recognition framework |
| **onnxruntime-android** (1.29.0) | Gradle dependency | MIT | [Microsoft ONNX Runtime](https://github.com/microsoft/onnxruntime) Neural network inference runtime |
| **AndroidX Core Libraries** | Gradle dependency | Apache-2.0 | Google official AndroidX architecture components (Lifecycle, Room, Navigation, etc.) |
| **Google Material Components** | Gradle dependency | Apache-2.0 | Material 3 design system and UI widgets |
| **Google Flexbox** | Gradle dependency | Apache-2.0 | Flexbox layout manager for keyboard keys and candidate bars |
| **Kotlinx Coroutines & Serialization** | Gradle dependency | Apache-2.0 | JetBrains asynchronous coroutines and JSON serialization utilities |
| **Android Image Cropper** (4.7.0) | Gradle dependency | Apache-2.0 | [Vanniktech](https://github.com/CanHub/Android-Image-Cropper) Custom theme background image crop utility |
| **JUnit 4** (4.13.2) | `testImplementation` | EPL-1.0 | Unit testing framework (test only; not packaged into release APK) |

---

## 4. Lexicon & Prediction Assets

| Asset Name | Storage Path | License | Pinned Version / Source | Attribution & Compliance Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Wanxiang Pinyin Lexicon** | `assets/rime/build/pinyin.table.bin` etc. | CC-BY-4.0 | commit `1b66a70` | Derived from [amzxyz/rime-wanxiang](https://github.com/amzxyz/rime-wanxiang). Accompanied by `UPSTREAM_LICENSE` and `NOTICE.txt`. Non-ShareAlike; attribution and modification notice provided on About screen. |
| **System Prediction Dataset** | `assets/predict/system_predict.tsv` | LGPL-3.0 | commit `df0f7a9` | Filtered from `rime/librime-predict` `data-1.0` via script; upstream training references `rime-essay`. Read as an independent static data file, accompanied by `NOTICE.md` and LGPL-3.0 text. Code and data are licensed separately; redistributions must retain data license, origin, change notes, and generation artifacts. |
| **OpenCC Dictionaries & Configs** | `assets/rime/opencc/` | Apache-2.0 | 1.1.9 | Official OpenCC simplified/traditional character mapping configurations and dictionary files. |

*Note: Exact lexicon file hashes and build locks reside in `tools/dictionary/candidates.lock.json`. The historical reference item `frost` is used solely for offline regression baseline comparison and is 100% excluded from production APKs.*

---

## 5. Candidate Fallback Fonts

Bundled in `app/src/main/res/font/`, used exclusively as fallback glyphs when the system font cannot render supplementary CJK planes (Extension B–I):

| Font Artifact | License | Original Glyph Source | Compliance & Attribution Notes |
| :--- | :--- | :--- | :--- |
| **lime_candidate_fallback.ttf** | OFL-1.1 / Hanazono Dual License | Hanazono 20170904 `HanaMinB.ttf` (GlyphWiki Project) | Renamed to avoid Reserved Font Name restrictions. SIL OFL-1.1 Clause 2 explicitly permits bundling with software. |
| **lime_candidate_extra.ttf** | Arphic Public License (APL) | BabelStone Han 16.0.3 (Andrew West / Arphic Technology) | Standalone font retaining APL license, copyright, and NOTICE; application code is distributed under its own license. |

---

## 6. On-Device AI Runtimes & Models

All neural network weights and shared ELF libraries are not bundled statically in the source repository or initial APK. Only when the user explicitly enables the feature in settings are they downloaded over HTTPS from allowlisted endpoints and verified against strict SHA-256 checksums:

| Asset Name | License | Originating Organization | Deployment & Distribution Form |
| :--- | :--- | :--- | :--- |
| **SenseVoice-Small** ASR Model | FunASR Model License | Alibaba Tongyi Lab / ModelScope | Downloaded on user confirmation from official/mirror sources as quantized INT8 model with tokens |
| **PP-OCRv6** Handwriting Recognition Model | Apache-2.0 | Based on Baidu PaddleOCR architecture | Downloaded on user confirmation from GitHub Releases mirror as ONNX weights |
| **16KB ELF Shared Runtimes** | MIT / Apache-2.0 | Microsoft / k2-fsa | Downloaded on user confirmation as 16KB page-aligned `libonnxruntime.so` and `libsherpa-onnx-jni.so` |

---

## 7. Distribution Materials & Verification Checklist

- License texts for Native libraries, Lua, sherpa-onnx, ONNX Runtime, and the project are bundled with the APK under [`assets/licenses/`](app/src/main/assets/licenses/NOTICE.md), eliminating reliance on untracked download directories.
- For font notices, see [`fonts/NOTICE.md`](app/src/main/assets/fonts/NOTICE.md); for Wanxiang lexicon notices, see [`rime/NOTICE.txt`](app/src/main/assets/rime/NOTICE.txt); for prediction data, see [`predict/NOTICE.md`](app/src/main/assets/predict/NOTICE.md).
- The English lexicon currently uses inherited precompiled artifacts; standalone source vocabulary, exact upstream version, and full authorization provenance are being finalized. Do not apply the main Pinyin Wanxiang license to English assets. Prior to publication, trace or replace with clearly sourced materials as outlined in [baseline documentation](tools/dictionary/BASELINE.md).
- Custom runtimes must document exact upstream source versions, compiler flags, linked dependencies, and complete third-party notices; model artifacts must map to specific weight provenance and licensing. Architectural project licenses do not automatically imply permission for converted weights.
- Android/Maven dependencies and their transitive dependencies must be audited against the final Release dependency tree NOTICE. This inventory is not a substitute for a comprehensive legal audit or full SBOM.

The [release guide](docs/releasing.md) (Chinese: [releasing.zh-CN.md](docs/releasing.zh-CN.md)) outlines acceptance verification steps for these materials.

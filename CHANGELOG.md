# Changelog

All notable changes to **LIME (Liuqing Input Method Engine)** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.9.0] - 2026-09-25

### Added
- **Production Baseline Profile**: Generated and bundled production Android AOT baseline profiles (`baseline-prof.txt` & `startup-prof.txt`) reducing cold startup latency and first-keystroke rendering time.
- **T9 Auto Phrase Consolidation**: Supported intra-composition and inter-commit multi-segment Chinese phrase learning for T9 9-key input without greedy sentence composition.
- **Unified AI Module Architecture**: Introduced `AiPackageSpec`, DAG topology downloader, content-addressable storage (`AiBlobStore`), and SHA-256 verified runtime delivery.
- **Offline Handwriting Engine**: Added PP-OCRv6 CTC line-level ink recognition engine powered by 16KB page-aligned ONNX Runtime.
- **Offline Speech Recognition**: Integrated Sherpa-ONNX with SenseVoice-Small multilingual model (Mandarin, Cantonese, English, Japanese, Korean) with real-time waveform visualizer.
- **Comprehensive Third-Party Notices**: Added `THIRD_PARTY.md` cataloging all third-party components, C++ engines, fallback fonts, and open-source models.
- **Full Settings Accessibility & UI Polish**: Added comprehensive TalkBack accessibility (`contentDescription`, `labelFor`) across all preference toggles, key feedback controls, and candidate actions.
- **Enhanced Open Source Governance**: Added `SECURITY.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`, Issue/PR templates, and GitHub Actions CI.

### Changed
- **Lexicon Modernization**: Migrated primary Pinyin dictionary from GPL-based lexicons to `rime-wanxiang` under CC-BY-4.0.
- **Android 12–16 Alignment**: Fully optimized WindowInsets avoidance and eliminated navigation bar chin on Android 15/16 mandatory edge-to-edge mode.
- **Clean Architecture Refactor**: Normalized Gradle root project name to `LIME` and purged all legacy namespace residues.

### Fixed
- **Vowelless Syllable Ranking Protection (`nm`)**: Suppressed full-spelling privilege for vowelless syllables in `script_translator.cc` and erased standalone `^n$` and `^m$` syllables from `pinyin.schema.yaml`, preventing single consonants from suppressing common abbreviations (e.g. `nm` -> `那么` instead of `嗯呒`).
- **Full Spelling vs. Abbreviation Ranking (`sheia`)**: Decoupled full spelling protection from fuzzy flags in candidate comparison, ensuring full-spelling phrases (e.g. `sheia` -> `谁啊`) consistently outrank abbreviation combinations (`是黑啊`, `涉黑案`).
- **T9 Digit Leak Prevention**: Ensured `RimeEngine.editorCompositionText` strictly suppresses raw digits and intermediate segments from leaking to the host application's composing text.
- **Rendering & Focus Stability**: Fixed PopupWindow focus theft on clipboard long-press, eliminated gesture navigation padding miscalculation, and calibrated swipe flick thresholds.
- **Clipboard Robustness**: Fixed duplicate persistence, out-of-order suggestion overwrites, and ensured pinned snippets survive bulk clearing operations.
- **Handwriting Stroke Calibration**: Calibrated handwriting clearance timeout, stroke width preview scale, and modal composition barriers.

---

## [0.1.0] - 2026-08-20

### Added
- **Initial Fork & Modernization**: Initial fork and consolidation from YuyanIme & yuyansdk.
- **Toolchain Upgrades**: Upgraded to Gradle 9.x, AGP 9.x, Kotlin 2.4, and NDK 28 with 16KB memory page alignment.
- **Material 3 UI System**: Complete redesign of keyboard surfaces, candidate bars, and preference screens based on Material 3 guidelines.
- **Single-ZIP Data Governance**: One-tap export and import for preferences, Room database, and user custom phrase lexicons.

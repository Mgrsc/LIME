# Changelog

All notable changes to **LIME (Liuqing Input Method Engine)** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Fixed
- **Concurrent AI Downloads**: Bound handwriting download UI updates to the view lifecycle and released progress dialogs when leaving the page. Shared runtime transfers now replay and broadcast progress to all waiting downloads while preserving transfers needed by other consumers.
- **Adaptive Panels**: Sized emoji, symbol, emoticon, and clipboard grids from their available width, preserving common-symbol pairs and fitting glyphs to constrained panel heights. Kept paged symbol and emoji cell bounds synchronized with the column count from the first layout.
- **Emoji Loading**: Started manually configured emoji metadata loading without an Activity and refreshed visible pages after filtered data becomes available.
- **Compact Layouts**: Added scrollable keyboard mode cards, compact text-edit controls, and a side category list for low-height symbol panels.
- **Voice Controls**: Kept locked-recording completion controls within the overlay and aligned their touch bounds after resizing; fitted preview text to the available width.
- **Panel Rotation**: Restored symbol panel mode and category during configuration changes within the same unrestricted editor session.

---

## [0.9.5] - 2026-11-01

### Changed
- **Keyboard Presentation**: Refined keyboard visual hierarchy, typography, key spacing, and press feedback.
- **AI Runtime Upgrade**: Updated sherpa-onnx to 2.0.1 and ONNX Runtime to 1.31.1, with a matching rebuilt Sherpa JNI bridge and a checksum-verified shared runtime archive. Runtime libraries remain separate from the APK.
- **Voice Preview Cadence**: Added decode-duration cooldown and identical-snapshot suppression to offline previews, skipping new preview requests while a decode is in flight instead of coalescing them.
- **Voice Preparation Guidance**: Clarified that recording can begin while the offline engine loads and cleared the preparing indicator when a valid preview arrives.
- **Download Presentation**: Added a distinct name for shared runtime transfers and clearer model-file wording in voice settings.

### Fixed
- **Keyboard Geometry Refresh**: Rebuilt stale cached key geometry when dimensions or margins change and refreshed existing input layouts on ordinary opens, keeping keycaps and touch bounds aligned with the measured keyboard area while preserving current editor actions and Shift state.
- **Keyboard Gestures**: Tightened symbol slide-up activation and stabilized swipe-delete acceleration and selection anchoring.
- **Composition Handoffs**: Committed pending composition before switching panels and input modes.
- **Quiet and Short Voice Input**: Preserved short audible utterances and quiet speech beyond the previous fallback buffer limit, with bounded segmentation and decode-boundary padding for short final audio.
- **Voice Task Lifecycle**: Preserved valid in-flight previews, invalidated stale results on cancellation, and prevented preview work from being admitted after session finalization.
- **Shared Runtime Downloads**: Preserved shared transfers when one consumer cancels, verified archive entries before installation, and prevented cached libraries from prematurely crediting an archive still needed by another library.

### Known Limitations
- The shared runtime archive is published and verified from both pinned sources. Voice download, runtime loading, and model initialization have been verified in the Debug app; production upgrade and handwriting acceptance remain unverified in this release cycle.
- SenseVoice previews use repeated offline decoding; they are not native streaming recognition. Model initialization latency remains unchanged.
- Short audible fragments are retained using energy-based admission; noise-induced false recognition has not been quantified on-device.

---

## [0.9.2] - 2026-10-12

### Security
- **Backup & Restore Hardening**: Enforced strict path traversal guards, file type whitelisting, and Room database confinement in `UserDataManager` to prevent unauthorized file writes.
- **Theme Path Sanitization**: Sanitized custom background asset paths in `ThemeFilesManager` against directory traversal vulnerabilities.
- **Lua Runtime Restrictions**: Stripped unsafe dynamic loading searchers and privileged OS execution calls in `librime-lua` while maintaining sandbox compatibility with upstream Wanxiang scripts.

### Fixed
- **Chinese Pinyin Capitalization**: Enforced lowercase composition across RIME engine pipelines and suppressed external editor auto-capitalization in Chinese input mode.
- **Swipe-Delete Selection Bounds**: Prevented spurious delete selection and vibration feedback on empty text fields, synchronizing local cursor selection cache.
- **Panel Scroll Position Persistence**: Reset scroll positions to top upon opening Candidate, Symbol, Clipboard, and T9 panels; hardened scroll resets with idle-gated frame locking to eliminate cross-session scroll position leakage.
- **Lexicon Abbreviation Segmentation**: Supplemented core colloquial query phrases to stabilize pinyin abbreviation segmentation without regressing short abbreviation rankings.

---

## [0.9.1] - 2026-09-28

### Added
- **Wanxiang Domain & Entity Lexicon Expansion**: Integrated 8 additional domain dictionaries from `rime-wanxiang` CC-BY-4.0 (`diming`, `yiren`, `mingren`, `renming`, `duoyin`, `yaopin`, `huaxue`, `yixue`), bringing out-of-the-box coverage for prominent figures/celebrities (e.g. 邓紫棋, 周杰伦, 雷军, 刘慈欣), medical/chemical terminology (e.g. 对乙酰氨基酚, 布洛芬), geographic locations (e.g. 楚雄, 郑州, 徐州, 秦皇岛, 呼和浩特), and polyphonic disambiguation.
- **Lexicon Regression CI Gate**: Added automated multi-domain regression verification suite and native candidate lookup assertions in `tools/dictionary/`.

### Changed
- **Celebrity & Person Name Weight Capping**: Enforced a dual-bound weight mechanism (ceiling of 2,000 and floor of 150 against colliding base dictionary entries) for `yiren`, `mingren`, and `renming`. This guarantees everyday high-frequency vocabulary (e.g., `章节` vs `张杰`, `武警` vs `吴京`, `橙汁` vs `程之`, `缉拿` vs `吉娜`) is not displaced at top candidate positions while preventing obscure low-weight entries (e.g., `硫磁锌` 90) from suppressing notable figures (e.g., `刘慈欣` 150).
- **Pinyin Syllabary Hygiene**: Quarantined non-standard Latin phonetic annotations (e.g., `西妥昔单抗β`) to keep the canonical Pinyin syllable inventory strictly at 418.
- **Modern Vocabulary Recovery**: Restored canonical polyphonic pronunciation for `大都` (`dà dū`) in `modern.tsv` attributed to `rime-wanxiang-duoyin` under CC-BY-4.0.

### Fixed
- **Missing Place-Name, Celebrity & Medical Candidates**: Resolved missing regional prefecture/city, celebrity name, and common pharmaceutical candidate predictions across Pinyin and T9 layouts.

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

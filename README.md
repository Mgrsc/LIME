# LIME · Liuqing Input Method Engine (留青输入法)

<p align="center">
  <img src="docs/images/icon.svg" width="108" height="108" alt="LIME Logo" />
</p>

<p align="center">
  <strong>Secure · On-Device Inference · Modern Minimalist Input Engine</strong>
</p>

<p align="center">
  <a href="README.md">English</a> | <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <a href="https://github.com/Mgrsc/LIME"><img src="https://img.shields.io/badge/GitHub-Mgrsc%2FLIME-blue?logo=github" alt="GitHub Repo" /></a>
  <a href="https://github.com/Mgrsc/LIME/releases"><img src="https://img.shields.io/badge/Version-0.9.0-blue.svg" alt="Version 0.9.0" /></a>
  <a href="https://github.com/Mgrsc/LIME/actions/workflows/ci.yml"><img src="https://github.com/Mgrsc/LIME/actions/workflows/ci.yml/badge.svg" alt="CI Status" /></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-BSD--3--Clause-green.svg" alt="License" /></a>
  <a href="./CODE_OF_CONDUCT.md"><img src="https://img.shields.io/badge/Contributor%20Covenant-2.1-4baaaa.svg" alt="Contributor Covenant" /></a>
  <img src="https://img.shields.io/badge/Platform-Android%2012--16-orange.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Page%20Size-16KB%20Aligned-purple.svg" alt="16KB Aligned" />
  <img src="https://img.shields.io/badge/Privacy-Local%20Inference-success.svg" alt="Local Inference" />
</p>

> **LIME (Liuqing Input Method Engine)** is a privacy-first, high-performance on-device input method for Android, featuring offline speech recognition, neural handwriting inference, and zero network telemetry.

---

## 📖 Table of Contents
- [🌿 About LIME](#-about-lime)
- [✨ Key Features](#-key-features)
- [🚀 Quickstart](#-quickstart)
  - [📦 Installation & Enable](#-installation--enable)
  - [🛠️ Build from Source](#️-build-from-source)
  - [✅ Verify Build](#-verify-build)
- [⚙️ Specifications](#️-specifications)
- [🤝 Contributing](#-contributing)
- [🙏 Acknowledgements](#-acknowledgements)
- [📄 License](#-license)

---

## 🌿 About LIME

> **Liuqing** (留青, Bamboo-skin Carving) is a classic Chinese carving art. Craftsmen delicately carve patterns onto the preserved thin green skin of bamboo while leaving the underlying bamboo skin clear, balancing tactile texture with visual restraint.

**LIME (Liuqing Input Method Engine)** takes inspiration from this philosophy of restraint and focus: as an on-device Android input method, it emphasizes data privacy, low-latency responsiveness, and clean modern ergonomics.

Forked from the open-source project [YuyanIme](https://github.com/gurecn/YuyanIme), LIME retains the lightweight core while executing deep architectural modernizations, including Material 3 card-based interaction, on-device AI speech and handwriting engines, T9 phrase consolidation, full-spelling ranking protection, 16KB memory page size alignment, and multi-tier data governance.

---

## ✨ Key Features

- 🛡️ **Privacy-First & Zero Telemetry**: All keystrokes, personal lexicons, clipboard history, and voice/ink data remain strictly on-device. Network access is restricted to user-confirmed one-time model downloads from an HTTPS allowlist; no behavioral tracking or telemetry.
- ⌨️ **Intelligent Pinyin Engine**:
  - **Auto Phrase Learning**: T9 9-key mode automatically learns phrases from multi-segment selections without requiring full-sentence composition;
  - **Disambiguation & Noise Suppression**: Optimizes full-spelling priority and consonant filtering to prevent abbreviations from overriding common words;
  - **Modern Lexicons & Multi-Schema**: Pre-loaded with `rime-wanxiang` dictionary and associative prediction; supports T9, QWERTY, Double Pinyin, English, and Emoji/symbols.
- ⚡ **On-Device Offline AI**:
  - **Offline Speech Recognition**: Powered by Sherpa-ONNX and SenseVoice-Small with real-time waveform visual feedback;
  - **Offline Handwriting Recognition**: Powered by PP-OCRv6 visual model and ONNX Runtime; stroke-order insensitive with real-time Pinyin phonetic hints.
- 📱 **Modern Android Integration**:
  - **Edge-to-Edge & Performance**: Full Android 12~16 Edge-to-Edge WindowInsets avoidance; bundled production Baseline Profiles for minimal startup and typing latency;
  - **16KB Memory Page Size**: Native libraries strictly aligned for Android 15/16 16KB page architecture;
  - **Accessibility**: Comprehensive TalkBack screen-reader support across settings, candidate bars, and menus.
- 🎨 **Ergonomics & Data Governance**:
  - **Sticky Live Theme Studio**: 10 curated light/dark themes with sticky real-time preview for candidate sizes, popups, and corner radius;
  - **Dual-Column Clipboard**: High-density streaming layout with pin-to-top protection and customizable capacity;
  - **Single-ZIP Migration**: One-tap backup and restore of preferences, databases, custom vocabulary, and themes; tiered safety reset options.

---

## 🚀 Quickstart

### 📦 Installation & Enable
Supported device architecture: **Android 12+ (API 31+), arm64-v8a**. 32-bit ARM and x86 builds are not provided.

1. **Download APK**: Download the signed release package from [GitHub Releases](https://github.com/Mgrsc/LIME/releases) and verify the SHA-256 checksum:
   ```bash
   sha256sum -c SHA256SUMS
   ```
2. **Enable Keyboard**: Install and launch LIME, then follow the 3-step setup flow to enable and set it as your default keyboard in system settings;
3. **Model Loading**: Pinyin and English lexicons are bundled. Speech recognition and handwriting prompt for a confirmed one-time download on first use, then operate completely offline.

> **Tip**: The Debug variant uses a distinct application ID and can coexist with the Release package. Updating the Release package requires the same signing key.

---

### 🛠️ Build from Source

#### Prerequisites
- **JDK**: OpenJDK 21
- **Android SDK**: `compileSdk 36`, `minSdk 31`, `targetSdk 36`
- **Android NDK**: `28.0.13004108` (16KB Page Size ELF aligned)
- **CMake**: `3.22.1`
- **Build Tools**: `36.0.0`

Install components via Android SDK `sdkmanager`, and specify the SDK directory in `local.properties`:
```properties
sdk.dir=/path/to/android-sdk
```

#### Build Commands
The initial build automatically downloads Gradle Wrapper, Maven dependencies, and hash-pinned Native sources:

```bash
# 1. Clone repository
git clone https://github.com/Mgrsc/LIME.git
cd LIME

# 2. Build Debug APK
./gradlew assembleDebug

# Output APK: app/build/outputs/apk/debug/app-debug.apk
```

---

### ✅ Verify Build

Run the test suite to verify code health and build integrity:

```bash
# Run JVM unit tests and lint checks
./gradlew testDebugUnitTest lintDebug

# Run lexicon tools and next-word prediction tests
python3 -m unittest discover -s tools/dictionary -p "test_*.py"
```

---

## ⚙️ Specifications

| Dimension | Specification / Technology | Notes |
| :--- | :--- | :--- |
| **Supported OS** | Android 12 ~ Android 16 (API 31 ~ 36) | Aligned with Edge-to-Edge and 16KB paging |
| **Architecture** | `arm64-v8a` | Mandatory 16KB ELF LOAD alignment |
| **Toolchains** | JDK 21 / NDK 28 / CMake 3.22.1 / AGP 9.0+ | Pinned build toolchains |
| **Pinyin Engine** | RIME 1.17.0 | Custom T9 state machine & phrase learning |
| **Lexicons & Models** | `rime-wanxiang` (CC-BY-4.0) + `rime-predict` | Modern Pinyin and word-pair association |
| **Speech ASR** | Sherpa-ONNX 1.13.7 + SenseVoice-Small | On-device multilingual offline ASR |
| **Handwriting** | PaddleOCR PP-OCRv6 + ONNX Runtime 1.29.0 | CTC visual sequence recognition |
| **Orthography** | OpenCC 1.1.9 | Accurate Traditional/Simplified conversion |

---

## 🤝 Contributing

Contributions, feedback, and issue reports are welcome:
- Development workflow and coding guidelines: [CONTRIBUTING.md](CONTRIBUTING.md);
- Release builds, signing verification, and publishing: [Release Guide](docs/releasing.md) (Chinese: [releasing.zh-CN.md](docs/releasing.zh-CN.md));
- Lexicon baseline and rebuild instructions: [BASELINE.md](tools/dictionary/BASELINE.md);
- Community Code of Conduct: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md); Vulnerability disclosure: [SECURITY.md](SECURITY.md).

---

## 🙏 Acknowledgements

**LIME (Liuqing Input Method Engine)** is built upon the foundation of outstanding open-source projects. We express our sincere gratitude to:

* [YuyanIme](https://github.com/gurecn/YuyanIme) — Created by **Wang Ying (王莹)**, providing the foundational architecture and early exploration;
* [RIME (中州韵输入法引擎)](http://rime.im) — Powerful, modular, and open-source input method engine;
* [Sherpa-ONNX (Next-gen Kaldi)](https://github.com/k2-fsa/sherpa-onnx) — High-performance on-device offline speech recognition framework;
* [ONNX Runtime (Microsoft)](https://github.com/microsoft/onnxruntime) — Cross-platform high-performance neural network inference engine;
* [rime-wanxiang](https://github.com/amzxyz/rime-wanxiang) — High-quality modern Chinese Pinyin lexicon maintained by **amzxyz** and contributors;
* [rime-predict](https://github.com/rime/librime-predict) — Official next-word prediction engine and datasets for RIME;
* [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) — Mature input method UX and clipboard architectural references;
* [OpenCC (Open Chinese Convert)](https://github.com/BYVoid/OpenCC) — High-quality conversion between Simplified, Traditional, and regional Chinese variants;
* [GlyphWiki & Hanazono](http://glyphwiki.org/) — Essential glyph data and foundation for candidate fallback font;
* [BabelStone Han](https://www.babelstone.co.uk/Fonts/Han.html) — Comprehensive CJK font by **Andrew West** for rare character candidate display.

---

## 📄 License

LIME codebase is licensed under the **[BSD 3-Clause License](./LICENSE)**:

```text
Copyright (c) 2026, Bitfennec (Mgrsc / LIME Project Contributors)
Copyright (c) 2026, 王莹 (Original YuyanIme Project)
All rights reserved.
```

Third-party components, native libraries, fonts, lexicons, and on-device AI models incorporated into or utilized by LIME remain subject to their respective open-source licenses. For complete details and attribution notices, please refer to **[THIRD_PARTY.md](./THIRD_PARTY.md)**.

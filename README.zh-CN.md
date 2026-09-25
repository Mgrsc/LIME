# LIME · 留青输入法 (Liuqing Input Method Engine)

<p align="center">
  <img src="docs/images/icon.svg" width="108" height="108" alt="LIME Logo" />
</p>

<p align="center">
  <strong>安全 · 本地推理 · 现代极简端侧输入法</strong>
</p>

<p align="center">
  <a href="README.md">English</a> | <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <a href="https://github.com/Mgrsc/LIME"><img src="https://img.shields.io/badge/GitHub-Mgrsc%2FLIME-blue?logo=github" alt="GitHub Repo" /></a>
  <a href="https://github.com/Mgrsc/LIME/releases"><img src="https://img.shields.io/badge/版本-0.9.0-blue.svg" alt="版本 0.9.0" /></a>
  <a href="https://github.com/Mgrsc/LIME/actions/workflows/ci.yml"><img src="https://github.com/Mgrsc/LIME/actions/workflows/ci.yml/badge.svg" alt="CI 状态" /></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-BSD--3--Clause-green.svg" alt="License" /></a>
  <a href="./CODE_OF_CONDUCT.md"><img src="https://img.shields.io/badge/Contributor%20Covenant-2.1-4baaaa.svg" alt="Contributor Covenant" /></a>
  <img src="https://img.shields.io/badge/Platform-Android%2012--16-orange.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Page%20Size-16KB%20Aligned-purple.svg" alt="16KB Aligned" />
  <img src="https://img.shields.io/badge/Privacy-Local%20Inference-success.svg" alt="本地推理" />
</p>

> **留青输入法（LIME）** 是一款注重隐私与性能的现代 Android 端侧输入法，集成本地离线语音识别与手写引擎，零网络遥测，击键与剪贴板数据不出端。

---

## 📖 目录
- [🌿 关于留青](#-关于留青)
- [✨ 核心特性](#-核心特性)
- [🚀 快速开始](#-快速开始)
  - [📦 安装与启用](#-安装与启用)
  - [🛠️ 源码构建](#️-源码构建)
  - [✅ 构建验证](#-构建验证)
- [⚙️ 技术规格](#️-技术规格)
- [🤝 参与贡献](#-参与贡献)
- [🙏 致谢与开源基石](#-致谢与开源基石)
- [📄 开源许可证](#-开源许可证)

---

## 🌿 关于留青

> **留青**（Bamboo-skin Carving），是中国传统竹刻名品。工匠在留存的薄竹青皮上浅刻纹样，底皮留白，兼具质感与克制。

**LIME (Liuqing Input Method Engine)** 取意于这种克制与专注：作为一款面向 Android 平台的端侧输入法，强调数据私密性、低延迟输入响应与现代交互设计。

本项目 Fork 自开源项目 [YuyanIme](https://github.com/gurecn/YuyanIme)，在继承原有轻快架构的基础上，持续开展现代化重构与底层升级，包括统一 Material 3 卡片交互、接入全离线 AI 语音与手写引擎、重构拼音自造词与消歧排序链路、原生支持 16KB 内存分页架构，并建立全链路分级数据治理。

---

## ✨ 核心特性

- 🛡️ **本地隐私与零遥测**：数据全程不出端，击键、自造词、剪贴板与录音均在本地处理；仅在首次使用语音/手写时经用户确认从白名单下载模型，无任何行为追踪或遥测上报。
- ⌨️ **智能拼音与词库生态**：
  - **连续选词自造词**：九键模式下无需整句输入，分段选词或连贯输入均可自动记忆沉淀常用词组；
  - **拼音消歧与声母降噪**：优化全拼优先与辅音降噪机制，防止简拼误抢占常用词；
  - **多方案与现代词库**：预置 `rime-wanxiang` 万象词库与词对联想预测；支持九键、全键、双拼、英文键盘与符号表情。
- ⚡ **离线端侧 AI 引擎**：
  - **离线语音识别**：基于 Sherpa-ONNX 与 SenseVoice 模型，半屏面板即按即录，离线转写并带实时音频波形反馈；
  - **离线手写识别**：基于 PP-OCRv6 视觉模型与 ONNX Runtime，倒笔画不敏感，支持汉字实时拼音注音。
- 📱 **现代 Android 深度适配**：
  - **全景边到边与性能基线**：适配 Android 12~16 强制 Edge-to-Edge 沉浸避让，内嵌 Baseline Profile AOT 预编译优化启动与击键延迟；
  - **16KB 内存分页**：全线 Native 库严格适配 Android 15/16 16KB 页面对齐要求；
  - **无障碍体验**：全界面与候选交互完整支持 TalkBack 焦点读屏。
- 🎨 **主题与数据治理**：
  - **吸顶实时主题**：内置 10 款浅色/深色现代主题，字号、气泡、圆角调节时置顶键盘实时预览；
  - **流式双列剪贴板**：支持高密度排版、重要记录图钉锁定与弹性容量；
  - **单一归档备份**：一键导出/导入包含偏好、数据库、词库与壁纸的单一 `.zip` 备份，提供四级分级安全重置。

---

## 🚀 快速开始 (Quickstart)

### 📦 安装与启用
支持设备架构：**Android 12+ (API 31+), arm64-v8a**。未提供 32 位 ARM 或 x86 架构预编译安装包。

1. **下载安装包**：从 [GitHub Releases](https://github.com/Mgrsc/LIME/releases) 下载正式签名的 APK，并通过校验和文件校验完整性：
   ```bash
   sha256sum -c SHA256SUMS
   ```
2. **启用输入法**：安装并打开 LIME，按照 3 步向导依次在系统设置中启用并选定为默认输入法；
3. **模型加载**：拼音与基础词库随包内置，安装即用；语音识别与离线手写功能在首次使用时弹出模型下载确认，下载完成后完全离线运行。

> **提示**：Debug 变体使用独立应用 ID，可与正式版并存安装。更新正式版包体时需使用相同签名。

---

### 🛠️ 源码构建 (Build from Source)

#### 环境要求 (Prerequisites)
- **JDK**: OpenJDK 21
- **Android SDK**: `compileSdk 36`, `minSdk 31`, `targetSdk 36`
- **Android NDK**: `28.0.13004108`（支持 16KB Page Size ELF 对齐）
- **CMake**: `3.22.1`
- **Build Tools**: `36.0.0`

通过 Android SDK 的 `sdkmanager` 安装上述依赖，并在项目根目录下通过 `local.properties` 指定 SDK 路径：
```properties
sdk.dir=/path/to/android-sdk
```

#### 编译命令
首次构建会自动下载 Gradle Wrapper、Maven 依赖及带有固定哈希的 Native 源码，无需手动配置预置依赖：

```bash
# 1. 克隆代码仓库
git clone https://github.com/Mgrsc/LIME.git
cd LIME

# 2. 编译 Debug 版本 APK
./gradlew assembleDebug

# 产物输出路径为：app/build/outputs/apk/debug/app-debug.apk
```

---

### ✅ 构建验证 (Verify Build)

执行全量单元测试与词库测试，确保构建无异常：

```bash
# 运行 JVM 单元测试与代码检查
./gradlew testDebugUnitTest lintDebug

# 运行词库工具与词对联想单测
python3 -m unittest discover -s tools/dictionary -p "test_*.py"
```

---

## ⚙️ 技术规格 (Specifications)

| 维度 | 技术栈 / 配置规格 | 说明 |
| :--- | :--- | :--- |
| **支持系统** | Android 12 ~ Android 16 (API 31 ~ 36) | 适配 Edge-to-Edge 与 16KB 分页 |
| **目标架构** | `arm64-v8a` | 强制 16KB ELF LOAD 对齐 |
| **构建套件** | JDK 21 / NDK 28 / CMake 3.22.1 / AGP 9.0+ | 严格锁定构建工具链 |
| **拼音核心** | RIME (中州韵) 1.17.0 | 自研 T9 状态机与自造词扩展 |
| **词库与模型** | `rime-wanxiang` (CC-BY-4.0) + `rime-predict` | 现代拼音词库与词对联想库 |
| **语音引擎** | Sherpa-ONNX 1.13.7 + SenseVoice-Small | 端侧多语言离线 ASR |
| **手写引擎** | PaddleOCR PP-OCRv6 + ONNX Runtime 1.29.0 | CTC 视觉序列识别 |
| **简繁转换** | OpenCC 1.1.9 | 异体字与简繁精准转换 |

---

## 🤝 参与贡献 (Contributing)

欢迎提交 Issue 反馈缺陷或发起 Pull Request！
- 开发环境、调试流程与代码规范请参阅 [CONTRIBUTING.md](CONTRIBUTING.md)；
- 生产构建、签名验证与发版步骤请参阅 [发布指南](docs/releasing.zh-CN.md)（英文：[releasing.md](docs/releasing.md)）；
- 词库源数据重构与基线校准说明请参阅 [BASELINE.md](tools/dictionary/BASELINE.md)；
- 行为准则请查阅 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)，安全漏洞披露政策见 [SECURITY.md](SECURITY.md)。

---

## 🙏 致谢与开源基石 (Acknowledgements)

**LIME (留青输入法)** 的演进与发展离不开开源先驱项目的贡献，由衷感谢以下开源社区与项目：

* [YuyanIme](https://github.com/gurecn/YuyanIme) - 由 **王莹** 开发的开源输入法基石，为本项目提供了架构雏形与探索基础；
* [RIME (中州韵输入法引擎)](http://rime.im) - 自由、灵活的现代输入法算法与核心引擎；
* [Sherpa-ONNX (Next-gen Kaldi)](https://github.com/k2-fsa/sherpa-onnx) - 高性能完全离线端侧语音识别框架；
* [ONNX Runtime (Microsoft)](https://github.com/microsoft/onnxruntime) - 跨平台高性能神经网络推理引擎；
* [万象拼音 (rime-wanxiang)](https://github.com/amzxyz/rime-wanxiang) - 由 **amzxyz** 及贡献者维护的高质量现代中文拼音与联想词库；
* [rime-predict (中州韵联想模块)](https://github.com/rime/librime-predict) - 官方下一词联想数据与核心能力参考；
* [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) - Android 平台成熟的输入法交互与剪贴板设计参考；
* [OpenCC (开放中文转换)](https://github.com/BYVoid/OpenCC) - 优质的简繁中文与异体字转换库；
* [GlyphWiki 与花园明朝 (Hanazono)](http://glyphwiki.org/) - 候选冷僻字回退字体的核心字形基石；
* [BabelStone Han](https://www.babelstone.co.uk/Fonts/Han.html) - 由 **Andrew West** 整理的超大字符集汉字字形，用于生僻字补充显示。

---

## 📄 开源许可证 (License)

留青输入法代码库依据 **[BSD 3-Clause License](./LICENSE)** 条款开放源码：

```text
Copyright (c) 2026, Bitfennec (Mgrsc / LIME Project Contributors)
Copyright (c) 2026, 王莹 (Original YuyanIme Project)
All rights reserved.
```

本项目集成或引用的第三方原生库、字体、词库及端侧 AI 模型各自遵循其独立的开源许可协议，详细许可清单与版权声明请参阅 **[THIRD_PARTY.md](./THIRD_PARTY.md)**。

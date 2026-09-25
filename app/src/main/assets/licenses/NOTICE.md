# 随包许可证

本目录由 Android assets 原样打包，供源码及 APK 分发随附许可文本。字体、万象与联想数据的许可仍位于相邻 `fonts/`、`rime/`、`predict/` 目录。

以下 Native 文本复制自 CMake 固定版本、SHA-256 校验的下载源码；来源版本见根目录 `THIRD_PARTY.md` 与 `app/src/main/cpp/cmake/`。marisa-trie 选择 BSD-2-Clause，保留完整上游双许可声明。

| 组件 | 原文件（相对 Native deps） | 本文 SHA-256 |
| --- | --- | --- |
| librime | `librime-1.17.0/LICENSE` | `f67d27a6d2d586fcfed4b4c886a83747095396a39b6641e18e855086be2ec400` |
| librime-lua | `librime-lua-ec52e48/LICENSE` | `c6bd4f58b13ae9d45c5e53adddeea5049114a25862797c6c6f64920431eea249` |
| librime-predict | `librime-predict-920bd41/LICENSE` | `9b1bbdf15a381884b47dfd9422e68f6382170b2841c476afc076f2d448c7a59f` |
| leveldb | `leveldb-1.23/LICENSE` | `ccc19f1da0798ed666609b65a5b44dd8b3abe6fc08b9c0592eb76e82e174db19` |
| marisa-trie | `marisa-trie-0.3.1/COPYING.md` | `edf58dab34c3dc239ba4ba2d5d3d844d8c5b442aa4dd1149fac81b2d8c6cb8d1` |
| yaml-cpp | `yaml-cpp-0.8.0/LICENSE` | `aa6fcc27be034e41e21dd832f9175bfe694a48491d9e14ff0fa278e19ad14f1b` |
| OpenCC | `opencc-1.1.9/LICENSE` | `b534e465949558eec2597b04f5092b5e161236a68dfbfd04d547592ac3964308` |
| boost | `boost-1.89.0/LICENSE_1_0.txt` | `c9bff75738922193e67fa726fa225535870d2aa1059f91452c411736284ad566` |

Lua 文本摘自 `librime-lua-thirdparty-fa40fad/lua5.4/lua.h` 文件末尾版权块（Lua 5.4.8）。

- sherpa-onnx 1.13.7：[原始许可](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/LICENSE)。随仓库 Java JAR 使用此文本。
- ONNX Runtime 1.29.0：[原始许可](https://github.com/microsoft/onnxruntime/blob/v1.29.0/LICENSE)。此处覆盖 ONNX Runtime 自身，不代替定制运行库实际链接组件的 notices。
- `LIME.txt`：项目 BSD-3-Clause 及第三方声明。

这不是完整传递依赖 SBOM。正式分发仍需核对定制运行库、模型与 Android 依赖的版权及 NOTICE，尤其不能用一个 Apache-2.0 通用文本替代各组件要求保留的声明。操作清单见 `docs/releasing.md`。

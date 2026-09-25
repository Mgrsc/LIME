# 候选补字字体

这些字体仅在系统无法绘制候选中的补充平面汉字时使用。候选原文、排序、选词索引及上屏文本不变；正常字仍使用系统字体。字体只影响本输入法，宿主应用使用其自己的字体。

## 来源与许可

- `res/font/lime_candidate_fallback.ttf`：从 Hanazono 20170904 的 `HanaMinB.ttf` 裁剪。Copyright 2008–2017 GlyphWiki Project。原字体采用 Hanazono Font License / SIL Open Font License 1.1 双许可，完整声明见 `LICENSE-Hanazono.txt`。
  原文件：https://sources.debian.org/data/main/f/fonts-hanazono/20170904-2.1/HanaMinB.ttf
- `res/font/lime_candidate_extra.ttf`：从 BabelStone Han 16.0.3 裁剪，仅补足前者未覆盖的字符。Copyright 1994–1999 Arphic Technology Co., Ltd.；Copyright 2009–2025 Andrew West。采用原始 Arphic Public License，见 `LICENSE-Arphic.txt`。
  原始压缩包：https://www.babelstone.co.uk/Fonts/Download/BabelStoneHan-16.0.3.zip
  上游说明：https://www.babelstone.co.uk/Fonts/Han.html

修改日期：2026-09-11。修改内容：仅保留目标字形及其所需组件，移除无关字体表，分别更名为 Lime Candidate Fallback / Lime Candidate Extra；保留原版权和许可记录，不修改字形轮廓。两份字体分别维持各自许可。

## 覆盖与重建

本轮从实际 `pinyin.table.bin`（SHA-256 `75ebfb2024ba72b7df4f1da64ad0a38f4eb019d7383b8e48faffe35a27b79a8e`）反编译，并使用同目录 OpenCC `t2s.json` 转换，取原词面与简体词面中 U+20000–U+3FFFF 的并集。两份字体合计覆盖这批 15,337 个字符；不代表所有 Unicode 汉字、未来新增词库或所有变体序列。字形来源不同，部分生僻字可能呈现不同地区的字形风格。

字典输入、源字体及输出字体的校验值、字符数和大小见 `tools/dictionary/candidate_fonts_coverage.json`。APK 实际大小以打包后的压缩尺寸为准。

使用现有 Rime 反编译器和 OpenCC 生成当前词表，步骤见仓库 `tools/dictionary/BASELINE.md`。将以上固定版本字体下载至 `.dev/scratch/glyph-probe/`，解压 BabelStoneHan.ttf 后，用已安装的 fontTools 重建：

```bash
python3 tools/build_candidate_fonts.py \
  --dictionary .dev/scratch/glyph-probe/pinyin.dict.yaml \
  --dictionary .dev/scratch/glyph-probe/pinyin.simplified.dict.yaml \
  --hanazono .dev/scratch/glyph-probe/HanaMinB.ttf \
  --babelstone .dev/scratch/glyph-probe/BabelStoneHan.ttf \
  --output app/src/main/res/font \
  --report tools/dictionary/candidate_fonts_coverage.json
```

脚本固定校验源字体 SHA-256，遇到缺字会报错，不能静默生成不完整覆盖。词库更新或补字资源更新时重新运行并核对实际设备显示。

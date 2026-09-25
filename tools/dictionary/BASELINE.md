# 词库与联想回归基线

## 范围

`lexicon-regression.tsv` 固定词面与词典编码，检查主表内容；`prediction-regression.tsv` 固定提交上下文，供真实 `PredictEngine` 输出基线。样例是公开常用词与合成文本，不来自用户输入历史。

- 词典编码是完整音节，例如 `yin hang`，不是双拼按键串或九键数字。
- 原始词面不存在不等于用户输入找不到：当前万象主表之外，Rime 组词和运行时繁体转换也会影响显示。报告不得据此直接决定补词。
- 静态词条权重不等于 Rime 运行时排名；主表检测不声称完成三方案体验验收。
- 联想 `dev` 用于调规则；`holdout` 不用于逐项写候选映射。这个小样本是启动基线，不代表完整中文使用分布。
- `rejected` 是明确拒绝候选；`accepted` 是当前小样本的接受候选集合；`allow_empty` 明确是否允许空结果。字段使用 `|` 分隔候选。
- 不把未标注样例计算成 Top-1/Top-3 合理率。有效覆盖、合理率和产品通过阈值必须在完整标注后冻结，再做 P2 对照。

## 生成静态报告

先使用与目标二进制格式相容的 Rime 反编译器，把**当前**主表反编译到 `.dev/scratch/`。检查命令成功后再运行审计，不能复用没有 hash 对应关系的旧反编译文件。

```bash
rime_table_decompiler app/src/main/assets/rime/build/pinyin.table.bin .dev/scratch/lexicon-baseline/pinyin.dict.yaml
opencc -c t2s.json -i .dev/scratch/lexicon-baseline/pinyin.dict.yaml -o .dev/scratch/lexicon-baseline/pinyin.simplified.dict.yaml
python3 tools/dictionary/audit_lexicon.py --dictionary .dev/scratch/lexicon-baseline/pinyin.dict.yaml --simplified-dictionary .dev/scratch/lexicon-baseline/pinyin.simplified.dict.yaml --output .dev/references/lexicon-prediction-baseline.json
```

若系统工具失败，停止使用其输出。仓库内构建的同名工具可替换第一个命令；工具与格式差异记录在 `.dev/references/lexicon-prediction-baseline.md`，不能静默修改二进制适配工具。

`audit_lexicon.py` 检查表格式、反编译行数与 metadata 一致、TSV 权重和重复词对，记录词条、不同词面、不同编码关系、联想分布及资源 SHA-256。行数校验不能证明一个外部文件必然来自当前主表，所以反编译命令、工具版本和 hash 必须一起记录。

上面的简化对照使用宿主安装的 OpenCC `t2s.json` 与词典，需单独记录宿主版本及配置、词典 hash。APK 已移除 `t2s`，这个对照仅用于离线字形分析，不代表应用运行时会进行繁转简。脚本核对转换前后编码和权重不变；简化后存在性仍不等于 Android 候选排名，不覆盖运行时组词。

## 生成真实预测输出

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :app:testDebugUnitTest --tests org.bitfennec.lime.inputmethod.predict.PredictEngineTest.testBundledPredictionBaseline --rerun-tasks
```

输出：`app/build/reports/lexicon/prediction-baseline.tsv`。

基线样例由测试直接读取，不属于 JVM 编译输入；修改样例或重建报告时使用 `--rerun-tasks`，避免误读旧输出。

此检查加载真实系统资源，逐例重置学习上下文，调用生产 `PredictEngine`，使用完整提交上下文，不按字符后缀猜测词边界；输出精确查表与最终候选，保留精确匹配与内置 fallback。测试使用现有注入入口加载候选，**不覆盖 Android AssetManager 的初始化过程**。

当前测试同时断言接受候选、拒绝候选和允许空结果，未知候选会失败。24 条样例已在审查中查看过输出，属于回归门禁，不再作为盲测质量证据；不能从这个集合外推全语料 Top-1／Top-3 合理率。

## 产物来源线索

- 生产词库使用万象来源：`tools/dictionary/build_candidate_dictionary.py` 读取 `candidates.lock.json` 的固定来源及 hash，生成词典；`compile_candidate_bundle.py --publish` 编译并发布至应用资产。
- 生产别名规则为 `edge-syllable-aliases.tsv`；具体输入、构建工具及二进制 hash 以 `app/src/main/assets/rime/BUILD_REPORT.json` 为线索，并与当前文件独立核对。
- 旧 luna、seed 与 Hans 实验构建脚本及 `sources.lock` 已移除，不能用于当前生产主表的复现。
- 当前编译脚本复用运行时 schema 和 OpenCC 资源，资源清单生成到 `app/src/main/assets/rime-assets-manifest.json`。English 词典资产（`english.schema.yaml`、`build/english.schema.yaml`、`build/english.prism.bin`、`build/english.table.bin`）当前为仓库内手工维护的预编译资产，构建脚本直接复用，不从独立的 `english.dict.yaml` 源码生成构建；完整源构建的字节级复现仍需独立验证。
- 系统联想来源、过滤参数（`--thresh-2char=12000 --thresh-multi=2500 --max-candidates=6`）与产物 hash 见 `app/src/main/assets/predict/NOTICE.md`；来源声明、实际产物 hash 和当前运行表现分别核验。

## 原生分类与资源安装检查

九键 `speller/algebra` 修改后，必须通过下文生产流程重新生成并发布资源，使
`build/t9_pinyin.schema.yaml`、`build/t9_pinyin.prism.bin` 与源规则一致，同时更新资源清单。
只更新源 YAML、方案版本或清单 hash 不会重新生成 prism；常规 APK 打包也不能替代此步骤。
禁止手改生成后的 YAML 来冒充完成资源重建。

九键 1.3 源规则增加普通声母简拼。规则放在音节规范化之后、数字映射之前，
避免简拼 `m` / `n` 被改写成完整音节。资源重建前仍使用旧的九键编码索引；
`kou55555` 仅表示候选覆盖的拼音与未解析输入被完整保留，不能据此判定简拼已生效。

```bash
python3 tools/dictionary/check_phrase_code.py --librime-build .dev/scratch/lexicon-baseline/librime
python3 tools/dictionary/generate_assets_manifest.py
```

原生检查从当前 JNI 提取真实查询函数，使用已有宿主 librime 构建检查规范编码、同词异码、空白规范化和查询失败；不替代 Android JNI／userdb 界面验收。

资源安装使用 APK 清单的 SHA-256 作为 marker。安装后、进程首次校验以及文件修改时间变化时核对完整 hash；同进程复用时检查全部清单文件大小／修改时间。主动保留时间戳的同尺寸外部篡改不属于快速检查的检测保证，下次进程首次校验仍会检测。用户配置不参与系统资源 hash 匹配。

`prediction-exclusions.tsv` 只记录已确认的有限词对修正，不用于把保留集逐项调到及格。生成脚本继续从固定原始 predict.txt 重建，不能对已筛选产物再加工充当来源重建。


## 2026-09-13：全新安装词库切换

生产拼音底座改为固定万象 `zi+jichu+lianxiang`，提交见 `candidates.lock.json` 的 `wanxiang` 项。保留 Rime 三方案与独立 English；`modern.tsv` 仅保留原创产品术语，现代词和网络词随上游更新；空的热词入口已删除，旧 Luna 构建脚本仅用于历史对照，不是默认生产流程。上文 Luna 数据属于历史基线。

新流程使用 Python 标准库与项目现有 librime，无上游 Lua、模型或在线热词抓取。每次构建必须使用新的输出目录：

```sh
python3 tools/dictionary/build_candidate_dictionary.py wanxiang --cache .dev/references/lexicon-selection --output .dev/scratch/wanxiang-release
python3 tools/dictionary/compile_candidate_bundle.py .dev/scratch/wanxiang-release --deployer .dev/scratch/lexicon-baseline/librime/bin/rime_deployer --publish
```

`--deployer` 指向按项目当前 librime 源码编译的宿主工具；新环境按下文“宿主工具构建”操作，不依赖旧工程或未发布脚本。无 `--publish` 时只生成隔离包；发布仅允许锁定万象来源，完整编译后替换资产、更新清单，旧资源保留在 `.dev/scratch/lexicon-release-*`，同步失败恢复旧资源及清单。

同词同码取最大权重；语气词覆盖值、源 hash、生成器和编译器 hash 见随包 `BUILD_REPORT.json`。九键将边缘音节投影到习惯码，避免 `ng→64`；嗯嗯只补自身的混合 en/eng 入口。“呃呃呃”在 eee 下优先属于项目偏好，不代表“鹅鹅鹅”“饿饿饿”注音错误。

数据许可独立于应用：随包 `UPSTREAM_LICENSE`、`SOURCE_HEADERS.txt`、`NOTICE.txt`、`PROJECT_WORDS_LICENSE`。保留万象 CC BY 4.0 声明，原创词条采用项目 BSD-3-Clause；没有引入白霜 GPL 数据。原有预测资源继续保留自身许可。根声明不代表对全部第三方语料来源作法律担保，所选固定源未发现具体冲突，来源颗粒度缺口见 `.dev/design/lexicon-source-selection.md`。

用户明确本项目按全新安装验收，不实施旧 userdb 迁移/清理；旧学习记录仍可能保留旧编码。不能以全新安装检查通过声称旧用户迁移通过。

## 万象更新检查

每周及发版前运行一次以下命令；输出目录必须不存在。检查只生成候选快照，不修改当前锁文件或生产资源（若指定的 `--cache` 缺失已批准版本的基准源文件，会自动按锁文件校验哈希后补齐该缓存目录进行预热）：

```sh
python3 tools/dictionary/check_wanxiang_update.py --cache .dev/references/lexicon-selection --output .dev/scratch/wanxiang-update-YYYYMMDD
python3 tools/dictionary/build_candidate_dictionary.py wanxiang --lock .dev/scratch/wanxiang-update-YYYYMMDD/candidates.lock.json --cache .dev/scratch/wanxiang-update-YYYYMMDD/cache --output .dev/scratch/wanxiang-candidate-YYYYMMDD
python3 tools/dictionary/compile_candidate_bundle.py .dev/scratch/wanxiang-candidate-YYYYMMDD --deployer .dev/scratch/lexicon-baseline/librime/bin/rime_deployer
```

检查器将上游 HEAD 一次解析为完整提交，仅取本地锁文件中允许的表与 LICENSE。`UPDATE_REPORT.json` 记录字段/注音校验失败、过期隔离项、许可变化、头部变化和词条规模；各表 `*.diff.tsv` 完整记录新增、删除及调权，同词删旧码添新码计入编码替换数量。许可变化、解析失败、空表、已知语气词错码及新增/删除超过原表20%时检查失败，不产生候选锁。头部变化需要查看声明是否改变；SHA 是固定快照完整性标识，不是授权证明。

长词两条缺权重记录按锁文件精确隔离，不推断权重；上游修复后旧隔离项将触发失败，由维护者核实并删除对应隔离。其他普通更新无需逐词整理。新快照全量重建，同词同码只在本次快照内取最大值，保留上游删词、降权的效果。

候选通过三方案原生回归后，将候选锁替换 `candidates.lock.json`，再用新目录重建并 `--publish`，最后验证 APK manifest 与候选。保留回归保护项 ee/e'e/eee、oo/yine、en/ng、九键和小鹤，补验长词。更新检查成功不等于分发成功；手机仍随 APK 更新。不包含运行时联网、独立热搜源或自动提交。

集中检查使用 `python3 tools/dictionary/test_wanxiang_update.py`，不联网；在已有宿主探针的工作区，可附加 `--probe <probe> --before <previous-bundle> --after app/src/main/assets/rime --apk app/build/outputs/apk/debug/app-debug.apk` 验证候选和打包指纹。定时执行位置尚待确认，目前仅交付检查命令，未启用本机或 CI 定时任务。

## 宿主工具构建（Linux）

普通 APK 构建直接使用跟踪的二进制词库，不要求安装这些宿主开发库。重建词库时，需要 Python 3.12+（下方使用安全 tar 解包过滤器）、CMake、C++17 编译器，以及 Boost、glog、gflags、yaml-cpp、LevelDB、marisa、OpenCC 开发库；具体发行版包名不同，请通过系统包管理器安装。依赖来自宿主系统，因此这里保证重建步骤可执行，不保证跨环境二进制逐字节一致。

先完成一次 Android Native 配置/构建，以获得通过 CMake SHA-256 校验的 `app/src/main/cpp/Downloads/librime-1.17.0.tar.gz`。以下使用独立目录，保留 Android deps 不变；目标 source 目录必须不存在。

```sh
python3 - <<'PYCODE'
from pathlib import Path
import hashlib
import shutil
import tarfile

archive = Path("app/src/main/cpp/Downloads/librime-1.17.0.tar.gz")
expected = "a60274da5d8b8a7187e6c7e9ba5023334ed7bdd182535e93c4e96de8cf188377"
if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
    raise SystemExit("librime archive SHA-256 mismatch")
base = Path(".dev/scratch/lexicon-host")
source = base / "librime-1.17.0"
if source.exists():
    raise SystemExit("Use a fresh source directory")
base.mkdir(parents=True, exist_ok=True)
with tarfile.open(archive) as package:
    package.extractall(base, filter="data")
overrides = Path("app/src/main/cpp/src_override/rime")
for path in overrides.rglob("*"):
    if path.is_file() and path.name != "no_logging.h":
        target = source / "src/rime" / path.relative_to(overrides)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
PYCODE
cmake -S .dev/scratch/lexicon-host/librime-1.17.0 -B .dev/scratch/lexicon-baseline/librime -DCMAKE_BUILD_TYPE=Release -DBUILD_TEST=OFF -DBUILD_DATA=OFF -DBUILD_SAMPLE=OFF -DENABLE_LOGGING=ON
cmake --build .dev/scratch/lexicon-baseline/librime --target rime_deployer rime_table_decompiler --parallel 1
```

`no_logging.h` 是 Android 日志适配，宿主保留上游版本并开启 glog；其余引擎覆写与当前项目同步。如果 build 目录曾用于其他源码路径，改用新的 build 目录，并同步后续 `--deployer` 路径，不复用旧 CMakeCache。

英文 `english.prism.bin` / `english.table.bin` 当前直接复用继承的预编译资产；尚无独立源词表、完整来源版本和重建流程。其许可不能用万象许可代替。发布前应补足可验证来源与授权，或在明确输入体验要求后换成可重建资源；不要为凑齐文件而伪造源词表或许可。

历史追溯线索：英文二进制最早在 `553e7543d66ce0d6bcf3c2eea1fc529c5c4718bc`（rime-frost 更新提交）进入本仓库，随后 `e7c64c8c1cb08f15339af61ee9412bd49d659175` 更换二进制，`a88b182` 迁移到当前路径。提交说明不能单独证明英文语料来源或授权，需继续核对原始输入。当前文件指纹：

- `english.prism.bin`：SHA-256 `70cb8f87b91fa479c9f4e981111ede7b4fda6083cf10868b4c43982e65bfb0d0`。
- `english.table.bin`：SHA-256 `4b9a48113812e205e5779b8b5584aaa2991e8f634513ab0c30671e623c11ca80`。

# 原生引擎覆盖文件维护约束

## 固定上游基线

`app/src/main/cpp/cmake/librime.cmake` 固定 librime **1.17.0**，归档为
`app/src/main/cpp/Downloads/librime-1.17.0.tar.gz`，SHA-256：

```text
a60274da5d8b8a7187e6c7e9ba5023334ed7bdd182535e93c4e96de8cf188377
```

CMake 将 `app/src/main/cpp/src_override/rime/` 整体覆盖到解包源码树。
这些文件属于派生上游源码，不会随上游升级自动合并。升级版本时必须先逐文件
比较新上游，再重放本地差异；不能仅修改版本号并继续覆盖旧头文件。

## 简拼搜索新增的上游头文件

| 覆盖文件 | 1.17.0 原文件 SHA-256 | 本地语义差异 |
| --- | --- | --- |
| `rime/gear/poet.h` | `e1d3453c21777b7b56ec7f6ecb918b90115e903d5991dffc249ae969c9d5bee5` | 增加只读 `has_grammar()`；新增搜索只用于无语法模型路径。 |
| `rime/dict/user_dictionary.h` | `972524e51b3571208d0b9707cf2e5be32686499c779586a89f41bcf4c274ef31` | 引入 `LookupBudget`；`Lookup` 增加默认空预算参数；迭代器增加按值 `Clone()`。末尾注释差异不属于语义变更。 |

用户词典的预算参数必须与 `user_dictionary.cc` 的递归遍历同步维护。
`Clone()` 的按值实现依赖用户迭代器按值持有缓存和位置；系统词典迭代器则必须
深复制可变查询游标，不能照搬用户迭代器的实现。

## 组合代次与预算

`Composition::Reset` 隐藏了非虚的 `Segmentation::Reset`。当前生产调用位于
`ConcreteEngine::Compose`，以 `Composition&` 调用，保证每次重组更新代次；
`ScriptTranslator::Query` 据此更新新增搜索预算。不得改成通过
`Segmentation&` 或 `Segmentation*` 调用组合对象的 `Reset`，否则会绕过代次更新。
上游调整重组入口、静态类型或 `Reset` 调用时，应同时检查这项约束。

用户词库与系统词库拥有独立查词预算；每个来源分别按剩余位置分配，扣除实际
使用量。份额耗尽可以停止新增组合搜索，完整词和原有保底路径继续保留。
这不代表旧保底查词已经具有完整的最坏耗时上界。

## 评分与顺序

无模型路径沿用上游 `Grammar::Evaluate` 的每词加性成本。设该成本为 `P`：
整词分数为 `w + P`，多词句分数为各组件分数之和；新增句的质量值为
`exp(score - P) + initial_quality`。该变换保持分数顺序，不按词数平均。
`SentenceScoreToQuality` 从上游零权重评分取得基础成本，不复制惩罚常数。

候选跨度、学习优先级、完整拼写及旧组句首项保护仍属于另外的定序规则。
原整词质量中的 `quality_len / full_code_length` 也予以保留；质量值不是所有
候选之间唯一的比较依据。本轮不声称已建立新的语言质量评分模型。

## 组句输出配置

简拼新增搜索同样受 `max_sentences` 与 `sentence_cutoff_threshold` 约束。
内部搜索容量不等于输出数量；保留的旧组句首项也占一个输出名额，重复文字
不重复占用名额。数量上限按单个 `ScriptTranslation` 生效，主／补充翻译
分别执行；`MergedTranslation` 未施加整个菜单的组句总上限。不能把单条输入
实测未超限，当作任意图与配置下的全局上限保证。

相对截断比较当前分数与已接纳的前一条组句：仅当
`last_weight - weight > cutoff_threshold * abs(last_weight)` 时停止，
每次接纳后沿用 `Poet::MakeSentences` 的阈值收紧比例。旧组句首项存在时
从该首项开始比较；其分数可能低于新搜索首项，不能用绝对差截掉更优的新句。
候选及比较起点按分数降序排列时，这个单向判据与 Poet 的相对下降规则等价。

上游 `TranslatorOptions` 将 `max_sentences` 归一到 1–100，未配置时为 1。
关闭组句使用 `enable_sentence: false`，不能把 `max_sentences: 0` 当作关闭。
配置回归中的 `max_sentences: 0` 因此必须按一条检查，关闭组句另有独立用例。
搜索回归中需要验证多句召回时，应在隔离方案中显式配置多句上限，不能依赖
搜索绕过生产默认值。默认上限生效后，先前依赖大量新增组句的尾部召回数据
不再适用于当前实现；改变默认数量需另行比较质量与成本。

## 配套验证入口

```sh
python3 tools/check_sentence_search.py --librime-build <host-build> --sanitize
python3 tools/dictionary/check_learning.py --probe <engine-probe>
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
```

原生学习回归使用临时用户目录，检查独立提交不生成合并用户词、同组合学习及
重启保留。Android 候选快照不公开候选类型，因此设备测试只验证独立 T9 提交，
不以“候选文字出现”推断用户词库写入。构建测试 APK 不能替代设备执行。

`--sanitize` 将生产 `sentence_search.cc` 直接编入 ASan／UBSan 单测，覆盖搜索
与输出限制；同次运行中的实际引擎集成测试链接普通宿主库，不代表整个引擎
均已插桩。保留验证记录时应保存编译命令、运行命令和退出状态。

提交时应一并包含新增的搜索／预算头文件、实现文件及验证工具；未跟踪文件
不会自动进入提交。本记录不授权暂存、提交或推送。

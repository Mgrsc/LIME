# 构建与发布

## 环境与验证

使用项目固定的 Gradle Wrapper、JDK 21、Android SDK Platform 36、Build Tools 36.0.0、NDK 28.0.13004108、CMake 3.22.1。使用 Android SDK 自带的 `sdkmanager` 安装所需组件；不需要 RTK：

```sh
sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;28.0.13004108" "cmake;3.22.1"
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease --no-daemon -Pkotlin.incremental=false --max-workers=1
python3 -m unittest discover -s tools/dictionary -p "test_*.py"
```

首次构建需要联网。Native 源码由 CMake 下载并验证固定 SHA-256；`deps/`、`Downloads/`、`.cxx/` 无需提交。若在 GitHub 仓库配置了签名 Secrets，推送与 `versionName` 一致的 `vX.Y.Z` tag 会触发自动编译、正式签名并发布到 GitHub Releases；未配置密钥时本地和 PR CI 生成未签名包，tag 发布会失败而不会上传。CI 的成功也不替代真机验证。

## 签名与产物

0. 在输入法核心热路径（按键分发、候选词渲染、拼音解码）发生变更或版本发布前，连接一台 Android 13+ (API 33+) arm64 物理设备执行 `./gradlew :app:generateReleaseBaselineProfile`，更新 `app/src/release/generated/baselineProfiles/` 内的权威基准配置文件。日常构建无需重复执行。
1. **GitHub CI 自动化发版（推荐）**：在 GitHub 仓库 Settings -> Secrets and variables -> Actions 中配置 `RELEASE_KEYSTORE_BASE64`（keystore 文件的 base64，workflow 解码后写入 `RELEASE_STORE_FILE`）、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。推送与 `versionName` 一致的 tag（如 `v0.9.0`）后，`.github/workflows/release.yml` 会编译、签名、校验并发布 GitHub Release。`v3.0.4-runtime` 这类运行库 tag 不会触发该 workflow。
2. **本地签名出包**：维护者自行保管并备份正式签名密钥。复制 `keystore/keystore.properties.example` 为同目录的 `keystore.properties`，填写实际路径及凭据；不要提交私钥或配置。也可在环境注入 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。不要将密码写入命令历史或输出到日志。
3. 执行 `./gradlew assembleRelease --no-daemon --max-workers=1`。检查 `app/build/outputs/apk/release/`：未配置有效签名时生成 unsigned APK，不得重命名后当作签名包发布。
4. 用 SDK 工具验证正式签名包，并生成校验文件（先将 `ANDROID_HOME` 指向 SDK 根目录；以下路径针对默认签名输出）：

```sh
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk
mkdir -p .dev/release
(cd app/build/outputs/apk/release && sha256sum app-release.apk) > .dev/release/SHA256SUMS
```

记录并公布签名证书 SHA-256 指纹；后续版本必须使用同一签名。`zipalign` 只验证 APK ZIP 对齐，仍需用 NDK 的 `llvm-readelf -lW` 检查各 ELF 的 LOAD 对齐，并在 16KB page-size 环境中验证加载。下载的运行库也在检查范围内。

当前 Release `versionCode` 来自构建时间，不能声称相同源码得到逐字节相同 APK；发布时保存最终 APK、versionCode、Git commit、工具链版本及校验值。Debug 使用独立 application ID，不承担正式版本升级验证。

## 发布材料与设备验收

- 更新双语 README 中的版本信息和 CHANGELOG，确认 tag 指向最终源码。
- Release 附正式签名 APK、SHA256SUMS、签名证书指纹、支持平台（Android 12+ / arm64-v8a）、变化与已知限制。
- 从全新安装验证首次启用、拼音/英文输入，以及语音/手写首次下载；后者必须通过应用真实下载入口成功，手动推送模型不能替代此项。
- 从上一正式版验证升级、数据保留与备份恢复；首次发布注明不含旧版迁移保证。
- 核对 `AiPackageSpec.kt` 中各工件的下载路径、字节数和 SHA-256；正式发布前必须有可访问的对应 Release 工件。应用源码 Release 与 runtime Release tag 是两类产物。
- 核对 APK 内 `assets/licenses/`、`assets/fonts/`、`assets/rime/`、`assets/predict/` 的许可材料。定制 ONNX/sherpa 运行库须附实际源 commit、构建参数与链接依赖 notices；模型须记录具体权重来源及授权。
- 英文预编译资产来源、Android 传递依赖 notices 和定制运行库材料尚需核验，见 [THIRD_PARTY.md](../THIRD_PARTY.md)。这些事项完成前不要标记“全部许可已核验”。

保存最终 Android 运行时依赖树，逐项核对上游版权、LICENSE 与 NOTICE（该输出不包括 CMake 静态库和应用下载的模型/运行库）：

```sh
mkdir -p .dev/release
./gradlew :app:dependencies --configuration releaseRuntimeClasspath --no-daemon --max-workers=1 > .dev/release/runtime-dependencies.txt
```

## GitHub 设置与首次同步

仓库文件不会自动开启 GitHub 设置。维护者在 Settings 中确认：

- Dependabot alerts / security updates、Secret scanning、Push protection、Private vulnerability reporting。
- main ruleset 要求 PR 和通过 `Build, Lint & Unit Tests`，禁止 force push 与删除；首次推送和 CI 运行后再选择实际出现的检查名称。
- 启用 Code scanning 并确认 Kotlin/Java、C++ 覆盖与构建成功；单有开关不代表完成扫描。
- Issue 入口目前允许普通问题；只有实际启用 Discussions 后才添加对应链接。

公开推送前检查全部待公开 Git 历史中的凭据、用户数据和大文件；`.gitignore` 只能防止今后误加，不能清除历史记录。发现凭据先撤销/轮换，再按实际影响决定历史处理。不要直接镜像推送所有 refs。

本地改动经过审阅并提交后，可显式添加 GitHub remote（保留其他托管平台）：

```sh
git remote add github git@github.com:Mgrsc/LIME.git
git push github main
```

以上命令只在确认待发布分支和历史后执行。先等待 GitHub CI 成功，再创建版本 tag 和正式 Release。

## 官方参考

- [GitHub 仓库最佳实践](https://docs.github.com/en/repositories/creating-and-managing-repositories/best-practices-for-repositories)
- [GitHub Actions 安全实践](https://docs.github.com/en/actions/reference/security/secure-use)
- [Gradle Wrapper 校验](https://docs.gradle.org/current/userguide/gradle_wrapper.html#sec:verification)

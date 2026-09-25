# Building and Releasing

## Environment and Verification

Use the pinned Gradle Wrapper, JDK 21, Android SDK Platform 36, Build Tools 36.0.0, NDK 28.0.13004108, and CMake 3.22.1. Install required components using Android SDK's `sdkmanager`; RTK is not required:

```sh
sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;28.0.13004108" "cmake;3.22.1"
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease --no-daemon -Pkotlin.incremental=false --max-workers=1
python3 -m unittest discover -s tools/dictionary -p "test_*.py"
```

An active network connection is required on the initial build. Native source dependencies are downloaded and verified against pinned SHA-256 checksums by CMake; `deps/`, `Downloads/`, and `.cxx/` do not need to be committed. When signing secrets are configured in GitHub repository settings, pushing a `vX.Y.Z` tag that matches `versionName` triggers GitHub Actions to build, sign, and publish the production APK to GitHub Releases. Without those secrets, local builds and pull-request CI produce an unsigned APK, and the tag workflow fails instead of uploading it. Successful CI runs do not replace physical on-device verification.

## Signing and Artifacts

0. Before modifying core input latency paths (key dispatch, candidate rendering, pinyin decoding) or cutting a release, connect a physical Android 13+ (API 33+) arm64 device and run `./gradlew :app:generateReleaseBaselineProfile` to refresh the authoritative baseline profiles in `app/src/release/generated/baselineProfiles/`. Routine builds do not need to repeat this step.
1. **GitHub CI Automated Release (Recommended)**: In GitHub repository Settings -> Secrets and variables -> Actions, set `RELEASE_KEYSTORE_BASE64` (base64 of the keystore file; the workflow decodes it into `RELEASE_STORE_FILE`), `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`. Pushing a tag that matches `versionName` (for example `v0.9.0`) runs `.github/workflows/release.yml`, which builds, signs, verifies, and publishes the GitHub Release. Runtime tags such as `v3.0.4-runtime` do not trigger it.
2. **Local Signing**: Maintainers are responsible for securely storing and backing up the production signing key. Copy `keystore/keystore.properties.example` to `keystore/keystore.properties` in the same directory and fill in actual credentials; never commit private keys or properties. Credentials can also be supplied via environment variables (`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`). Never write passwords into shell history or command output.
3. Run `./gradlew assembleRelease --no-daemon --max-workers=1`. Inspect `app/build/outputs/apk/release/`: when valid signing credentials are not configured, an unsigned APK is produced; never rename an unsigned APK and distribute it as a production package.
4. Verify the production signed package using Android SDK tools and compute checksums (set `ANDROID_HOME` to the SDK root; paths below target default release output):

```sh
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk
mkdir -p .dev/release
(cd app/build/outputs/apk/release && sha256sum app-release.apk) > .dev/release/SHA256SUMS
```

Record and publish the certificate's SHA-256 fingerprint; subsequent releases must use the identical signing key. Note that `zipalign` only validates APK ZIP alignment; maintainers should verify ELF LOAD alignment with NDK `llvm-readelf -lW` and ensure compatibility in 16KB page-size environments. Downloaded runtime libraries are subject to the same requirements.

Release `versionCode` is generated from the build timestamp, so identical source trees will not produce byte-identical APKs across different times. Record the final APK, versionCode, Git commit, toolchain versions, and checksums upon release. Debug builds use an isolated application ID and do not validate production in-place upgrades.

## Release Materials and Device Acceptance

- Update version details and CHANGELOG in the bilingual README files, ensuring the tag points to the final source tree.
- Attach the production signed APK, SHA256SUMS, certificate fingerprint, supported platform requirements (Android 12+ / arm64-v8a), changes, and known limitations to the Release notes.
- From a clean installation, verify initial onboarding, Pinyin / English typing, and first-time on-demand downloads for voice and handwriting; model downloads must succeed through the actual in-app download flow rather than manual push over ADB.
- Verify in-place upgrade, data persistence, and backup restoration from the previous production release; if this is the initial release, explicitly state that legacy migrations are not guaranteed.
- Verify download URLs, byte counts, and SHA-256 hashes in `AiPackageSpec.kt`; accessible release assets must exist prior to publishing. Application source releases and runtime release tags are distinct artifacts.
- Verify license compliance for assets inside `assets/licenses/`, `assets/fonts/`, `assets/rime/`, and `assets/predict/`. Custom ONNX and sherpa runtimes must document actual source commits, build arguments, and linked dependency notices; model weights must specify origin and licensing terms.
- Precompiled English assets, Android transitive dependency notices, and custom runtime materials require audit; see [THIRD_PARTY.md](../THIRD_PARTY.md). Do not mark "all licenses audited" until these items are closed.

Preserve the final Android runtime dependency tree and inspect upstream copyright, LICENSE, and NOTICE files (this output excludes CMake static libraries and on-demand model/runtime downloads):

```sh
mkdir -p .dev/release
./gradlew :app:dependencies --configuration releaseRuntimeClasspath --no-daemon --max-workers=1 > .dev/release/runtime-dependencies.txt
```

## GitHub Repository Settings and Initial Synchronization

Repository files do not automatically configure GitHub settings. Maintainers must verify in Settings:

- Dependabot alerts / security updates, Secret scanning, Push protection, Private vulnerability reporting.
- The `main` ruleset requires pull requests and green `Build, Lint & Unit Tests` status checks; force pushes and deletions must be blocked. Select the exact check names after the initial push and CI run.
- Enable CodeQL / Code scanning and verify Kotlin/Java and C++ coverage and successful runs; a toggle alone does not guarantee completion.
- Issue intake currently allows general bug reports and feature requests; only link Discussions once actually enabled.

Before pushing to a public remote, audit the entire Git history for credentials, user data, and oversized binaries; `.gitignore` only prevents future additions and cannot purge historical commits. If credentials are found, revoke and rotate them immediately before deciding on history rewriting. Never mirror-push all refs blindly.

Once local changes are reviewed and committed, add the explicit GitHub remote (retaining other hosting providers):

```sh
git remote add github git@github.com:Mgrsc/LIME.git
git push github main
```

Execute these commands only after confirming the release branch and commit history. Wait for GitHub CI to pass before tagging and publishing the production Release.

## Official References

- [GitHub Repository Best Practices](https://docs.github.com/en/repositories/creating-and-managing-repositories/best-practices-for-repositories)
- [GitHub Actions Security Best Practices](https://docs.github.com/en/actions/reference/security/secure-use)
- [Gradle Wrapper Verification](https://docs.gradle.org/current/userguide/gradle_wrapper.html#sec:verification)

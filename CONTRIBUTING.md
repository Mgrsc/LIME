# Contributing to LIME

Thank you for your interest in contributing to **LIME (Liuqing Input Method Engine)**! We welcome bug reports, feature suggestions, documentation enhancements, and pull requests.

---

## 📜 Code of Conduct

All contributors and participants are expected to adhere to our **[Code of Conduct](./CODE_OF_CONDUCT.md)**. Please report unacceptable behavior to `mail@occult.ac.cn`.

---

## 🛠️ Development Environment & Prerequisites

Before building or making changes locally, ensure you have:

* **Android SDK**: `minSdk 31`, `targetSdk 36`, `compileSdk 36`
* **JDK**: OpenJDK 21
* **Android NDK**: `28.0.13004108` (Required for 16KB page-aligned ELF native builds)
* **Python**: 3.10+ for dictionary tests; 3.12+ for the documented host-source extraction procedure
* **CMake**: 3.22.1

---

## 🚀 Building & Testing Locally

### 1. Clone & Initialize
```bash
git clone https://github.com/Mgrsc/LIME.git
cd LIME
```

### 2. Build Debug APK
```bash
./gradlew assembleDebug
```
The output APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

### 3. Run Android Unit Tests & Lint
```bash
# Run unit test suite
./gradlew testDebugUnitTest

# Run lint inspection
./gradlew lintDebug
```

### 4. Run Lexicon & Dictionary Tests
```bash
python3 -m unittest discover -s tools/dictionary -p "test_*.py"
```

---

## 🌿 Contribution Workflow

### 1. Pick or Open an Issue
Before submitting a large change, please open an issue to discuss your proposal, rationale, and architecture considerations.

### 2. Branch Naming
Create a topic branch from `main`:
* `feat/your-feature-name`
* `fix/bug-description`
* `docs/documentation-update`
* `refactor/subsystem-name`

### 3. Commit Message Conventions
We strictly enforce **Conventional Commits**:
```text
<type>(<scope>): <short summary in English>

[optional body explaining rationale and context]
```
Common types:
* `feat`: A new user-facing or architectural feature
* `fix`: A bug fix
* `docs`: Documentation updates only (append `[skip ci]` if no code changed)
* `refactor`: Code refactoring without changing observable behavior
* `test`: Adding or correcting tests
* `chore`: Build scripts, dependencies, or auxiliary tool updates

### 4. Code Standards & Style
* **Kotlin**: Follow official Kotlin coding conventions and project `.editorconfig`.
* **Resource IDs**: Prefix resource identifiers clearly (`ime_*`, `view_*`, `ai_*`).
* **Non-Blocking Main Thread**: Never perform heavy file I/O or native engine maintenance on the Android UI main thread.
* **16KB Memory Page Alignment**: Ensure any native C/C++ libraries or ELF binaries maintain 16KB alignment (`-Wl,-z,max-page-size=16384`).
* **Clean Code**: Remove unused imports, dead functions, and temporary debug logging before creating your PR.

---

## 📬 Pull Request Submission Checklist

When opening a Pull Request, please ensure:

- [ ] The PR branches from up-to-date `main`.
- [ ] `./gradlew testDebugUnitTest` and `./gradlew lintDebug` pass with 0 errors and 0 warnings.
- [ ] Dictionary tests (`python3 -m unittest discover -s tools/dictionary -p "test_*.py"`) pass if lexicon tools were touched.
- [ ] You have tested your changes on an Android 12+ device or emulator.
- [ ] Commit messages follow the Conventional Commits format.

## Release & Resource Maintenance

See [the release guide](docs/releasing.md) for SDK setup, signing, checksums and GitHub settings. Dictionary rebuilds are documented in [BASELINE.md](tools/dictionary/BASELINE.md). Ordinary APK builds reuse the tracked dictionary assets.

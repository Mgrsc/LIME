#!/usr/bin/env bash
# Install the SDK packages pinned by this repository.
# GitHub-hosted runners do not preinstall NDK 28.0.13004108 or CMake 3.22.1.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" ]]; then
  echo "ANDROID_SDK_ROOT / ANDROID_HOME is not set" >&2
  exit 1
fi

SM="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SM" ]]; then
  echo "sdkmanager not found: $SM" >&2
  exit 1
fi

# sdkmanager exits after reading the licenses; yes then receives SIGPIPE.
set +o pipefail
yes | "$SM" --licenses >/dev/null || true
set -o pipefail

"$SM" --install \
  "platforms;android-36" \
  "build-tools;36.0.0" \
  "ndk;28.0.13004108" \
  "cmake;3.22.1" < /dev/null

NDK="$SDK/ndk/28.0.13004108"
CMAKE_DIR="$SDK/cmake/3.22.1"
[[ -d "$SDK/platforms/android-36" ]]
[[ -d "$SDK/build-tools/36.0.0" ]]
[[ -d "$NDK" ]]
[[ -d "$CMAKE_DIR" ]]

if [[ -n "${GITHUB_ENV:-}" ]]; then
  {
    echo "ANDROID_SDK_ROOT=$SDK"
    echo "ANDROID_HOME=$SDK"
  } >> "$GITHUB_ENV"
fi

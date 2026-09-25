#!/usr/bin/env bash
# Verify a signed release APK and stage the GitHub Release files.
# Usage: verify-release-apk.sh <apk> <tag> <output-dir>
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <apk> <tag> <output-dir>" >&2
  exit 1
fi

APK=$1
TAG=$2
OUT=$3
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
BT="$SDK/build-tools/36.0.0"
NDK="$SDK/ndk/28.0.13004108"

[[ -f "$APK" ]] || { echo "signed APK missing: $APK" >&2; exit 1; }
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "tag must look like v0.9.0: $TAG" >&2; exit 1; }
[[ -x "$BT/apksigner" && -x "$BT/zipalign" && -x "$BT/aapt" ]] || {
  echo "build-tools 36.0.0 is incomplete under $BT" >&2
  exit 1
}

READELF=$(find "$NDK/toolchains/llvm/prebuilt" -name llvm-readelf -print -quit)
[[ -n "$READELF" && -x "$READELF" ]] || { echo "llvm-readelf not found under $NDK" >&2; exit 1; }

"$BT/zipalign" -c -P 16 4 "$APK"
mkdir -p "$OUT"
"$BT/apksigner" verify --print-certs "$APK" > "$OUT.certs"

BADGING=$("$BT/aapt" dump badging "$APK")
VERSION_NAME=$(printf '%s\n' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
VERSION_CODE=$(printf '%s\n' "$BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")
VERSION_NAME=${VERSION_NAME%%$'\n'*}
VERSION_CODE=${VERSION_CODE%%$'\n'*}
[[ -n "$VERSION_NAME" && -n "$VERSION_CODE" ]] || { echo "aapt could not read version from $APK" >&2; exit 1; }
[[ "$TAG" == "v$VERSION_NAME" ]] || {
  echo "tag $TAG does not match versionName $VERSION_NAME" >&2
  exit 1
}

NATIVE_DIR=$(mktemp -d)
trap 'rm -rf "$NATIVE_DIR"' EXIT
unzip -q "$APK" 'lib/arm64-v8a/*.so' -d "$NATIVE_DIR"
mapfile -t SOS < <(find "$NATIVE_DIR/lib/arm64-v8a" -type f -name '*.so' | sort)
[[ ${#SOS[@]} -gt 0 ]] || { echo "APK has no lib/arm64-v8a/*.so" >&2; exit 1; }

for so in "${SOS[@]}"; do
  readelf_out=$("$READELF" -lW "$so")
  align_ok=0
  while read -r line; do
    trimmed=${line#"${line%%[![:space:]]*}"}
    [[ "$trimmed" == LOAD* ]] || continue
    align=${trimmed##* }
    if ! [[ "$align" =~ ^0x[0-9a-fA-F]+$ ]] || ! (( align >= 0x4000 && (align & (align - 1)) == 0 )); then
      echo "ELF LOAD align $align is not 16KB compatible: $(basename "$so")" >&2
      exit 1
    fi
    align_ok=1
  done <<< "$readelf_out"
  [[ "$align_ok" -eq 1 ]] || { echo "no LOAD segment: $(basename "$so")" >&2; exit 1; }
  echo "16KB $(basename "$so")"
done

ASSET_NAME="lime-${VERSION_NAME}-arm64-v8a.apk"
cp "$APK" "$OUT/$ASSET_NAME"
(
  cd "$OUT"
  sha256sum "$ASSET_NAME" > SHA256SUMS
)
APK_SHA=$(awk 'NR==1 { print $1 }' "$OUT/SHA256SUMS")
CERT_SHA=$(sed -n 's/.*SHA-256 digest: //p' "$OUT.certs" | paste -sd '; ' -)
[[ -n "$CERT_SHA" ]] || { echo "apksigner did not print a SHA-256 certificate digest" >&2; exit 1; }

cat > "$OUT/notes.md" <<EOF
## 留青输入法 ${TAG}

- versionName: ${VERSION_NAME}
- versionCode: ${VERSION_CODE}
- commit: ${GITHUB_SHA:-unknown}
- 平台: Android 12+ / arm64-v8a
- 证书 SHA-256: ${CERT_SHA}
- APK SHA-256: ${APK_SHA}

下载 \`${ASSET_NAME}\` 后执行 \`sha256sum -c SHA256SUMS\`。语音与手写运行库在应用内单独下载。
EOF

echo "verified $ASSET_NAME versionCode=$VERSION_CODE"

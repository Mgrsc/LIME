import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import urllib.request
import zipfile


SHERPA_VERSION = "2.0.1"
ORT_VERSION = "1.31.1"
NDK_VERSION = "28.0.13004108"
LIBRARY_NAMES = ("libonnxruntime.so", "libonnxruntime4j_jni.so", "libsherpa-onnx-jni.so")
DOWNLOADS = {
    "sherpa-source.zip": (
        "https://github.com/k2-fsa/sherpa-onnx/archive/refs/tags/v2.0.1.zip",
        "71397fbf7c30cf81a56247e4c45bd3748abb590e761fbcfa2989a33ca7644282",
    ),
    "sherpa-onnx-2.0.1.aar": (
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar",
        "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7",
    ),
    "onnxruntime-android-1.31.1.aar": (
        "https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.31.1/onnxruntime-android-1.31.1.aar",
        "d4d50660300ed2c4f38ffdfd5ab5bfa3a22f995f282dda24f42ea3ea55467b49",
    ),
    "ort-LICENSE": (
        "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.31.1/LICENSE",
        "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c",
    ),
    "ort-ThirdPartyNotices.txt": (
        "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.31.1/ThirdPartyNotices.txt",
        "143764b952fdb1a7c69ce653bfba74a7744d6a8a573bfb73e235fba356c83de3",
    ),
}


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(directory, name):
    url, expected = DOWNLOADS[name]
    target = directory / name
    if target.is_file() and sha256(target) == expected:
        return target
    partial = directory / f"{name}.partial"
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "Lime-runtime-builder"})
        with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output)
        if sha256(partial) != expected:
            raise ValueError(f"Source checksum mismatch: {name}")
        partial.replace(target)
        return target
    finally:
        partial.unlink(missing_ok=True)


def extract(source, directory, prefixes=()):
    with zipfile.ZipFile(source) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or (prefixes and not entry.filename.startswith(prefixes)):
                continue
            target = directory / entry.filename
            if not target.resolve().is_relative_to(directory.resolve()):
                raise ValueError(f"Archive path escapes destination: {entry.filename}")
            content = archive.read(entry)
            if not target.is_file() or target.read_bytes() != content:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)


def verify_library(path, readelf):
    content = path.read_bytes()
    if content[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", content, 18)[0] != 183:
        raise ValueError(f"Expected arm64 ELF library: {path.name}")
    offset = struct.unpack_from("<Q", content, 32)[0]
    size, count = struct.unpack_from("<HH", content, 54)
    alignments = []
    for index in range(count):
        entry = offset + size * index
        if struct.unpack_from("<I", content, entry)[0] == 1:
            alignment = struct.unpack_from("<Q", content, entry + 48)[0]
            file_offset, address = struct.unpack_from("<QQ", content, entry + 8)
            if alignment < 16384 or file_offset % 16384 != address % 16384:
                raise ValueError(f"Library is not 16 KB compatible: {path.name}")
            alignments.append(alignment)
    if not alignments:
        raise ValueError(f"Missing ELF load segments: {path.name}")
    symbols = subprocess.check_output([str(readelf), "--wide", "--dyn-syms", str(path)], text=True)
    dependencies = subprocess.check_output([str(readelf), "-d", str(path)], text=True)
    required_version = f"VERS_{ORT_VERSION}"
    if path.name == "libonnxruntime.so":
        if f"OrtGetApiBase@@{required_version}" not in symbols:
            raise ValueError("ORT does not export the expected versioned C API")
    elif f"OrtGetApiBase@{required_version}" not in symbols:
        raise ValueError(f"JNI binding requires a different ORT version: {path.name}")
    needed = re.findall(r"Shared library: \[([^\]]+)\]", dependencies)
    allowed = {"libonnxruntime.so", "libandroid.so", "liblog.so", "libm.so", "libdl.so", "libc.so"}
    if set(needed) - allowed:
        raise ValueError(f"Unexpected native dependencies: {path.name}: {needed}")
    return {"sha256": sha256(path), "bytes": path.stat().st_size, "load_alignments": alignments, "needed": needed}


def write_entry(archive, name, content):
    entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    entry.compress_type = zipfile.ZIP_DEFLATED
    entry.external_attr = 0o100644 << 16
    archive.writestr(entry, content)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--download-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--jobs", type=int, default=3)
    args = parser.parse_args()
    work = args.work_dir.resolve()
    downloads = args.download_dir.resolve()
    output = args.output_dir.resolve()
    ndk = args.ndk.resolve()
    if args.jobs < 1 or f"Pkg.Revision = {NDK_VERSION}" not in (ndk / "source.properties").read_text():
        raise ValueError(f"Build requires NDK {NDK_VERSION} and a positive job count")
    for directory in (work, downloads, output):
        directory.mkdir(parents=True, exist_ok=True)
    for name in DOWNLOADS:
        download(downloads, name)
    extract(downloads / "sherpa-source.zip", work)
    ort = downloads / "ort"
    extract(downloads / "onnxruntime-android-1.31.1.aar", ort, ("headers/", "jni/arm64-v8a/"))
    source = work / f"sherpa-onnx-{SHERPA_VERSION}"
    build = work / "build"
    environment = os.environ | {
        "SHERPA_ONNXRUNTIME_INCLUDE_DIR": str(ort / "headers"),
        "SHERPA_ONNXRUNTIME_LIB_DIR": str(ort / "jni/arm64-v8a"),
    }
    configuration = [
        "cmake", "-S", str(source), "-B", str(build), "-G", "Ninja",
        f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
        "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-31",
        "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=ON",
        "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
        "-DSHERPA_ONNX_ENABLE_JNI=ON", "-DSHERPA_ONNX_ENABLE_C_API=OFF",
        "-DSHERPA_ONNX_ENABLE_BINARY=OFF", "-DSHERPA_ONNX_ENABLE_PYTHON=OFF",
        "-DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF", "-DSHERPA_ONNX_ENABLE_TTS=OFF",
        "-DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF",
        "-DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF", "-DSHERPA_ONNX_ENABLE_TESTS=OFF",
    ]
    subprocess.run(configuration, env=environment, check=True)
    subprocess.run(["cmake", "--build", str(build), "--target", "sherpa-onnx-jni", "-j", str(args.jobs)], check=True)
    tools = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    for name in LIBRARY_NAMES[:2]:
        shutil.copyfile(ort / "jni/arm64-v8a" / name, output / name)
    shutil.copyfile(build / "lib/libsherpa-onnx-jni.so", output / LIBRARY_NAMES[2])
    subprocess.run([str(tools / "llvm-strip"), "--strip-all", str(output / LIBRARY_NAMES[2])], check=True)
    with zipfile.ZipFile(downloads / "sherpa-onnx-2.0.1.aar") as archive:
        (output / f"sherpa-onnx-{SHERPA_VERSION}.jar").write_bytes(archive.read("classes.jar"))
    libraries = {name: verify_library(output / name, tools / "llvm-readelf") for name in LIBRARY_NAMES}
    manifest = {
        "sherpa": SHERPA_VERSION,
        "ort": ORT_VERSION,
        "ndk": NDK_VERSION,
        "abi": "arm64-v8a",
        "android_api": 31,
        "sources": {name: {"url": url, "sha256": checksum} for name, (url, checksum) in DOWNLOADS.items()},
        "libraries": libraries,
    }
    bundle = output / f"lime-ai-runtime-sherpa{SHERPA_VERSION}-ort{ORT_VERSION}-arm64-v8a.aar"
    with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in LIBRARY_NAMES:
            write_entry(archive, f"jni/arm64-v8a/{name}", (output / name).read_bytes())
        for directory in [source, *(build / "_deps").glob("*-src")]:
            for path in sorted(directory.rglob("*")):
                if path.is_file() and path.name.upper().startswith(("LICENSE", "COPYING")):
                    write_entry(archive, f"licenses/{directory.name}/{path.relative_to(directory)}", path.read_bytes())
        for name in ("ort-LICENSE", "ort-ThirdPartyNotices.txt"):
            write_entry(archive, f"licenses/ort/{name.removeprefix('ort-')}", download(downloads, name).read_bytes())
    manifest["archive"] = {"name": bundle.name, "sha256": sha256(bundle), "bytes": bundle.stat().st_size}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    artifacts = [*LIBRARY_NAMES, f"sherpa-onnx-{SHERPA_VERSION}.jar", bundle.name, "manifest.json"]
    (output / "SHA256SUMS").write_text("".join(f"{sha256(output / name)}  {name}\n" for name in artifacts))
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()

import argparse
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--librime-build", type=Path, required=True)
    parser.add_argument("--kotlin-stdlib", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    build = args.librime_build.resolve()
    classes = root / "app/build/intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar"
    if not classes.is_file() or not args.kotlin_stdlib.is_file():
        parser.error("build the Debug Kotlin classes and provide the installed Kotlin standard library")
    java = Path(shutil.which("javac")).resolve().parents[1]
    flags = (build / "tools/CMakeFiles/rime_table_decompiler.dir/flags.make").read_text().splitlines()
    compiler_args = []
    for key in ("CXX_DEFINES = ", "CXX_INCLUDES = ", "CXX_FLAGS = "):
        compiler_args.extend(shlex.split(next(line[len(key):] for line in flags if line.startswith(key))))
    scratch = root / ".dev/scratch"
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="preedit-mapping-", dir=scratch) as temporary:
        directory = Path(temporary)
        headers = directory / "android"
        headers.mkdir()
        (headers / "log.h").write_text(
            "#pragma once\n#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\n"
            "#define ANDROID_LOG_ERROR 6\n#define ANDROID_LOG_DEBUG 3\n"
            "inline int __android_log_print(int, const char*, const char*, ...) { return 0; }\n"
            "inline int __android_log_is_loggable(int, const char*, int) { return 0; }\n"
        )
        binary = directory / "preedit_mapping_test"
        subprocess.run([
            "g++", *compiler_args, "-UNDEBUG", "-I" + str(directory),
            "-I" + str(java / "include"), "-I" + str(java / "include/linux"),
            str(root / "tools/preedit_mapping_test.cc"),
            "-L" + str(build / "lib"), "-Wl,-rpath," + str(build / "lib"), "-lrime", "-lopencc",
            "-L" + str(java / "lib/server"), "-Wl,-rpath," + str(java / "lib/server"),
            "-ljvm", "-lglog", "-o", str(binary),
        ], check=True)
        subprocess.run([
            str(binary), str(root / "app/src/main/assets/rime"), str(directory / "user"),
            str(classes) + ":" + str(args.kotlin_stdlib.resolve()),
        ], check=True)


if __name__ == "__main__":
    main()

import argparse
from pathlib import Path
import shlex
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--librime-build", type=Path, required=True)
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    build = args.librime_build.resolve()
    flags = (build / "tools/CMakeFiles/rime_table_decompiler.dir/flags.make").read_text()
    compiler_args = []
    for key in ("CXX_DEFINES = ", "CXX_INCLUDES = ", "CXX_FLAGS = "):
        compiler_args.extend(shlex.split(next(line[len(key):] for line in flags.splitlines()
                                             if line.startswith(key))))
    source = root / "app/src/main/cpp/src_override/rime/gear/sentence_search.cc"
    scratch = root / ".dev/scratch"
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="sentence-search-", dir=scratch) as directory:
        binary = Path(directory) / "sentence_search_test"
        command = ["c++", *compiler_args, str(root / "tools/sentence_search_test.cc")]
        if args.sanitize:
            command += [str(source), "-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        command += ["-L" + str(build / "lib"), "-Wl,-rpath," + str(build / "lib"),
                    "-lrime", "-o", str(binary)]
        subprocess.run(command, check=True)
        subprocess.run([str(binary)], check=True)
        integration = Path(directory) / "abbreviation_engine_test"
        subprocess.run(["c++", *compiler_args,
                        str(root / "tools/dictionary/abbreviation_engine_test.cc"),
                        "-L" + str(build / "lib"), "-Wl,-rpath," + str(build / "lib"),
                        "-lrime", "-o", str(integration)], check=True)
        subprocess.run([str(integration), str(root / "app/src/main/assets/rime"),
                        str(Path(directory) / "user")], check=True)


if __name__ == "__main__":
    main()

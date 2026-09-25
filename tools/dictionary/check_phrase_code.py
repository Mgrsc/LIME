#!/usr/bin/env python3
"""Exercise current JNI table helpers against real assets with an existing host librime build."""
import argparse
from pathlib import Path
import shlex
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--librime-build", type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
build = args.librime_build.resolve()
source = (root / "app/src/main/cpp/jni/lime_jni.cc").read_text()
helpers = source[source.index("template<typename F>\nstatic bool with_system_dict_table_locked"):
                 source.index("// 20. checkWordsInSystemDict")]
flags = (build / "tools/CMakeFiles/rime_table_decompiler.dir/flags.make").read_text().splitlines()
compiler_args = []
for key in ("CXX_DEFINES = ", "CXX_INCLUDES = ", "CXX_FLAGS = "):
    compiler_args.extend(shlex.split(next(line[len(key):] for line in flags if line.startswith(key))))
main = r'''
int main(int argc, char** argv) {
    assert(argc == 2);
    auto& deployer = rime::Service::instance().deployer();
    deployer.user_data_dir = rime::path(argv[1]);
    deployer.shared_data_dir = rime::path(argv[1]);
    rime::Table table(deployer.shared_data_dir / "build" / "pinyin.table.bin");
    assert(table.Load());
    std::unordered_map<std::string, rime::SyllableId> syllables;
    for (size_t i = 0; i < table.metadata()->syllabary->size; ++i)
        syllables[table.GetSyllableById(i)] = i;
    for (const auto& item : std::vector<std::pair<std::string, std::string>>{
            {"你好", "ni hao"}, {"銀行", "yin hang"}, {"嗯", "en"},
            {"阿巴拉契亞", "a ba la qi ya"}}) {
        rime::Code code;
        assert(parse_code_to_syllables("  " + item.second + "  ", syllables, code));
        bool found = false;
        assert(is_entry_in_system_dict_locked("pinyin", item.first, code, found) && found);
        code.push_back(syllables.at("a"));
        assert(is_entry_in_system_dict_locked("pinyin", item.first, code, found) && !found);
    }
    rime::Code code;
    assert(parse_code_to_syllables("ni hao", syllables, code));
    bool found = true;
    assert(!is_entry_in_system_dict_locked("missing_dictionary", "你好", code, found));
    assert(!found);
    assert(!parse_code_to_syllables("invalid_syllable", syllables, code));
    assert(!is_entry_in_system_dict_locked("pinyin", "你好", code, found));
    std::cout << "PASS: real phrase/code lookup, alternate code, whitespace, missing dictionary\n";
}
'''
header = """#include <rime/dict/table.h>
#include <rime/service.h>
#include <unordered_map>
#include <iostream>
#include <cassert>
#include <cstdio>
#define LOGE(...) std::fprintf(stderr, __VA_ARGS__)
"""
scratch = root / ".dev/scratch"
scratch.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="phrase-code-", dir=scratch) as directory:
    cpp = Path(directory) / "check.cc"
    binary = Path(directory) / "check"
    cpp.write_text(header + helpers + main)
    subprocess.run(["g++", *compiler_args, "-UNDEBUG", str(cpp), "-L" + str(build / "lib"),
                    "-Wl,-rpath," + str(build / "lib"), "-lrime", "-o", str(binary)], check=True)
    subprocess.run([str(binary), str(root / "app/src/main/assets/rime")], check=True)

#!/usr/bin/env python3
"""Check composition semantics and production JNI string helpers with a host JVM/librime."""
import argparse
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--librime-build", type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
build = args.librime_build.resolve()
java = Path(shutil.which("javac")).resolve().parents[1]
source = (root / "app/src/main/cpp/jni/lime_jni.cc").read_text()
helpers = source[source.index("static jstring new_utf8_string"):
                 source.index("static void shutdown_rime_locked")]
flags = (build / "tools/CMakeFiles/rime_table_decompiler.dir/flags.make").read_text().splitlines()
compiler_args = []
for key in ("CXX_DEFINES = ", "CXX_INCLUDES = ", "CXX_FLAGS = "):
    compiler_args.extend(shlex.split(next(line[len(key):] for line in flags if line.startswith(key))))
header = """#include <jni.h>
#include <utf8.h>
#include <iterator>
#include <string_view>
#include <vector>
#include <rime/composition.h>
#include <rime/menu.h>
#include <rime/translation.h>
#include <cassert>
#include <iostream>
"""
main = r'''
int main() {
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    JavaVMInitArgs options{};
    options.version = JNI_VERSION_1_8;
    assert(JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &options) == JNI_OK);
    for (const std::string text : {std::string(), std::string("想着va"),
            std::string("𠽝😀"), std::string("a\0b", 3)}) {
        jstring value = new_utf8_string(env, text);
        assert(value && !env->ExceptionCheck());
        std::string restored;
        assert(read_utf8_string(env, value, restored) && restored == text);
        env->DeleteLocalRef(value);
    }
    for (const std::string invalid : {std::string("\xf0\x80"), std::string("\xed\xa0\x80")}) {
        assert(!new_utf8_string(env, invalid) && env->ExceptionCheck());
        env->ExceptionClear();
    }
    const jchar surrogate = 0xd800;
    jstring invalid = env->NewString(&surrogate, 1);
    std::string restored;
    assert(!read_utf8_string(env, invalid, restored) && env->ExceptionCheck());
    env->ExceptionClear();
    env->DeleteLocalRef(invalid);

    rime::Composition composition;
    composition.Reset("xiangzheva");
    rime::Segment selected(0, 8);
    selected.status = rime::Segment::kSelected;
    selected.menu = std::make_shared<rime::Menu>();
    selected.menu->AddTranslation(std::make_shared<rime::UniqueTranslation>(
            std::make_shared<rime::SimpleCandidate>("test", 0, 8, "想着")));
    selected.menu->Prepare(1);
    composition.push_back(selected);
    composition.push_back(rime::Segment(8, 10));
    assert(composition.input() == "xiangzheva");
    assert(literal_composition_text(composition, "xiangzheva") == "想着va");
    assert(literal_composition_text(composition, "xiangzhevabc") == "想着vabc");
    assert(composition.GetScriptText() == "想着va");
    assert(composition.GetCommitText() == "想着va");
    // An unconfirmed highlighted candidate must not be selected by Enter.
    composition.back().menu = std::make_shared<rime::Menu>();
    composition.back().menu->AddTranslation(std::make_shared<rime::UniqueTranslation>(
            std::make_shared<rime::SimpleCandidate>("test", 8, 10, "候选", "", "va")));
    composition.back().menu->Prepare(1);
    assert(composition.GetScriptText() == "想着va");
    assert(composition.GetCommitText() == "想着候选");
    assert(literal_composition_text(composition, "xiangzheva") == "想着va");
    rime::Composition unselected;
    unselected.Reset("nv");
    rime::Segment pending(0, 2);
    pending.menu = std::make_shared<rime::Menu>();
    pending.menu->AddTranslation(std::make_shared<rime::UniqueTranslation>(
            std::make_shared<rime::SimpleCandidate>("test", 0, 2, "女", "", "nü")));
    pending.menu->Prepare(1);
    unselected.push_back(pending);
    assert(unselected.GetScriptText() == "nü");
    assert(literal_composition_text(unselected, "nv") == "nv");
    assert(vm->DestroyJavaVM() == JNI_OK);
    std::cout << "PASS: JNI Unicode round-trip/rejection and partial composition semantics\n";
}
'''
scratch = root / ".dev/scratch"
scratch.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="composition-unicode-", dir=scratch) as directory:
    cpp = Path(directory) / "check.cc"
    binary = Path(directory) / "check"
    cpp.write_text(header + helpers + main)
    subprocess.run(["g++", *compiler_args, "-UNDEBUG", "-I" + str(java / "include"),
                    "-I" + str(java / "include/linux"), str(cpp),
                    "-L" + str(build / "lib"), "-Wl,-rpath," + str(build / "lib"), "-lrime",
                    "-L" + str(java / "lib/server"), "-Wl,-rpath," + str(java / "lib/server"),
                    "-ljvm", "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)

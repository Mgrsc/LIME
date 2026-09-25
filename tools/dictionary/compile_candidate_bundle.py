#!/usr/bin/env python3
"""Compile a generated candidate directory using the project's existing schemas."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--deployer", type=Path, required=True)
    parser.add_argument("--publish", action="store_true", help="Publish the pinned Wanxiang bundle to app assets")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    bundle = args.bundle.resolve()
    if not bundle.is_relative_to(root / ".dev"):
        parser.error("experimental bundles must be inside .dev")
    report_path = bundle / "BUILD_REPORT.json"
    report = json.loads(report_path.read_text())
    if args.publish:
        source = json.loads((root / "tools/dictionary/candidates.lock.json").read_text())["wanxiang"]
        if report["source"] != source:
            parser.error("only the pinned Wanxiang source can be published")
        for name in ("UPSTREAM_LICENSE", "SOURCE_HEADERS.txt", "NOTICE.txt"):
            if not (bundle / name).is_file():
                parser.error(f"missing distribution notice: {name}")
        license_hash = next(item["sha256"] for item in source["files"] if item["path"] == "LICENSE")
        if hashlib.sha256((bundle / "UPSTREAM_LICENSE").read_bytes()).hexdigest() != license_hash:
            parser.error("distribution license hash mismatch")
    dictionary = bundle / "pinyin.dict.yaml"
    if hashlib.sha256(dictionary.read_bytes()).hexdigest() != report["output_sha256"]:
        parser.error("generated dictionary hash mismatch")
    if (bundle / "build").exists():
        parser.error("bundle already compiled or partially compiled; use a new directory")
    assets = root / "app/src/main/assets/rime"
    # Copy only runtime configuration; essay auto-annotation must remain disabled.
    for path in assets.glob("*.yaml"):
        if path.name.endswith(".dict.yaml"):
            continue
        shutil.copy2(path, bundle / path.name)
    shutil.copytree(assets / "opencc", bundle / "opencc")
    build = bundle / "build"
    build.mkdir()
    for path in (assets / "build").glob("english.*"):
        shutil.copy2(path, build / path.name)
    shutil.copy2(assets / "default.yaml", build / "default.yaml")
    report["runtime_inputs"] = {
        str(path.relative_to(bundle)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(bundle.rglob("*"))
        if path.is_file() and (path.suffix == ".yaml" or "opencc" in path.parts or path.name.startswith("english."))
    }
    deployer = args.deployer.resolve()
    report["deployer_sha256"] = hashlib.sha256(deployer.read_bytes()).hexdigest()
    report["compiler_sha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    report["host_libraries"] = {
        path.name: hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted({path.resolve() for path in (deployer.parent.parent / "lib").glob("librime.so*")})
    }
    for schema in ("pinyin", "t9_pinyin", "double_pinyin_flypy"):
        with (bundle / f"{schema}.compile.log").open("w") as log:
            subprocess.run([str(deployer), "--compile", f"{schema}.schema.yaml"],
                           cwd=bundle, stdout=log, stderr=subprocess.STDOUT, check=True)
        prism = build / f"{schema}.prism.bin"
        if not prism.is_file() or prism.stat().st_size == 0:
            raise RuntimeError(f"missing compiled prism: {schema}")
    report["compiled_outputs"] = {
        path.name: {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    "bytes": path.stat().st_size}
        for path in sorted(build.glob("*.bin"))
    }
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    if args.publish:
        # Prepare the complete replacement before touching current assets.
        release = Path(tempfile.mkdtemp(prefix="lexicon-release-", dir=root / ".dev/scratch"))
        staged = release / "rime"
        shutil.copytree(assets, staged)
        shutil.rmtree(staged / "opencc")
        shutil.copytree(bundle / "opencc", staged / "opencc")
        for path in staged.glob("RIME_LUNA_PINYIN_*"):
            path.unlink()
        for path in staged.glob("RIME_ESSAY_*"):
            path.unlink()
        shutil.rmtree(staged / "build")
        (staged / "build").mkdir()
        for path in bundle.glob("*.yaml"):
            if not path.name.endswith(".dict.yaml"):
                shutil.copy2(path, staged / path.name)
        for path in build.iterdir():
            if path.suffix in (".bin", ".yaml"):
                shutil.copy2(path, staged / "build" / path.name)
        for name in ("UPSTREAM_LICENSE", "SOURCE_HEADERS.txt", "NOTICE.txt"):
            shutil.copy2(bundle / name, staged / name)
        shutil.copy2(root / "LICENSE", staged / "PROJECT_WORDS_LICENSE")
        notice = staged / "NOTICE.txt"
        notice.write_text(notice.read_text().replace(
            "实验产物，尚未完成生产分发来源审查；不代表整个应用采用该数据许可。",
            "本应用拼音基础数据采用上述许可；本项目原创增量采用 BSD-3-Clause。"
            "本声明不改变应用代码及其他第三方资源的许可。"))
        release_report = dict(report, status="bundled; fresh-install profile")
        (staged / "BUILD_REPORT.json").write_text(json.dumps(release_report, ensure_ascii=False, indent=2) + "\n")
        manifest = assets.parent / "rime-assets-manifest.json"
        shutil.copy2(manifest, release / manifest.name)
        assets.rename(release / "previous-rime")
        try:
            staged.rename(assets)
            subprocess.run(["python3", str(root / "tools/dictionary/generate_assets_manifest.py")], check=True)
        except BaseException:
            if assets.exists():
                assets.rename(release / "failed-rime")
            (release / "previous-rime").rename(assets)
            shutil.copy2(release / manifest.name, manifest)
            raise
        print(f"Published Wanxiang assets; previous bundle retained at {release}")
    else:
        print(f"Compiled experimental bundle: {bundle}")


if __name__ == "__main__":
    main()

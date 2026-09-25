#!/usr/bin/env python3
"""Self-contained snapshot update checks; no network or third-party packages."""
from contextlib import redirect_stdout
import argparse
import hashlib
import io
import json
from pathlib import Path
import sys
import subprocess
import tempfile
from unittest.mock import patch
import zipfile

import check_wanxiang_update as updater
from build_candidate_dictionary import entries, normalize_code


def main():
    assert normalize_code("nǚ lǜ ǹg") == "nv lv ng"
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        tools = root / "tools/dictionary"
        tools.mkdir(parents=True)
        cache = root / "cache"
        cache.mkdir()
        original = "...\n女\tnǚ\t10\n银行\tyín háng\t20\n嗯\tńg\t30\n哦\té\t1\n吟哦\tyín é\t2\n呃呃\tè è\t100\n"
        (cache / "source.yaml").write_text(original)
        (cache / "license").write_text("licensed source")
        source = {"repository": "https://github.com/amzxyz/rime-wanxiang", "commit": "a" * 40,
                  "files": [{"path": "dicts/jichu.dict.yaml", "cache": "source.yaml"},
                            {"path": "LICENSE", "cache": "license"}]}
        for item in source["files"]:
            item["sha256"] = hashlib.sha256((cache / item["cache"]).read_bytes()).hexdigest()

        def run(name, data, license_text="licensed source", exclusions=()):
            source["files"][0]["excluded_rows"] = list(exclusions)
            (tools / "candidates.lock.json").write_text(json.dumps({"wanxiang": source}))
            output = root / ".dev" / name
            def fetch(url, **kwargs):
                if "api.github.com" in url:
                    value = json.dumps({"sha": "b" * 40})
                elif url.endswith("/LICENSE"):
                    value = license_text
                else:
                    assert "/" + "b" * 40 + "/" in url
                    value = data
                return io.BytesIO(value.encode())
            with patch.object(updater, "ROOT", tools), patch.object(updater.urllib.request, "urlopen", fetch), \
                    patch.object(sys, "argv", ["check", "--cache", str(cache), "--output", str(output)]), \
                    redirect_stdout(io.StringIO()):
                try:
                    updater.main()
                except SystemExit as error:
                    assert error.code == 1
            report = json.loads((output / "UPDATE_REPORT.json").read_text())
            assert (output / "candidates.lock.json").exists() == (not report["blockers"])
            assert (cache / "source.yaml").read_text() == original
            return output, report

        changed = original.replace("女\tnǚ\t10", "女\tnǚ\t3").replace("银行\tyín háng", "银行\tyín xíng")
        output, report = run("valid", changed)
        info = report["files"][0]
        assert not report["blockers"]
        assert (info["added"], info["removed"], info["weight_changed"], info["phrases_with_replaced_codes"]) == (1, 1, 1, 1)
        assert "weight\t女\tnv\t10\t3" in (output / "jichu.dict.diff.tsv").read_text()
        assert report["candidate_commit"] == "b" * 40
        for name, data, license_text, exclusions, reason in (
                ("license", original, "different license", (), "license changed"),
                ("bad-code", original + "哦哦\té é\t50\n", "licensed source", (), "rejected phrase"),
                ("missing-weight", original + "新词\txīn cí\n", "licensed source", (), "invalid entry"),
                ("stale", original, "licensed source", ("absent",), "stale exclusions"),
                ("empty", "...\n", "licensed source", (), "empty source"),
                ("churn", "...\n女\tnǚ\t10\n", "licensed source", (), "20%")):
            _, report = run(name, data, license_text, exclusions)
            assert any(reason in error for error in report["blockers"]), report
        corrupt = cache / "corrupt.yaml"
        corrupt.write_text("...\n坏\thuài\t-1\n")
        assert list(entries(corrupt, ("坏\thuài\t-1",))) == []
    print("PASS fixed snapshot, full diff, downweight/delete semantics, license and malformed-source gates")


def check_native(probe: Path, before: Path, after: Path):
    queries = [("pinyin", q) for q in (
        "e", "ee", "e'e", "eee", "oo", "en", "enen", "eneng", "engen", "engeng", "ng",
        "jiayouo", "jiayoue", "yine", "nv", "nu", "dayuyanmoxing", "lixianyuyinshibie")]
    queries += [("double_pinyin_flypy", q) for q in ("ee", "eeee", "enen", "oooo", "nv", "nu")]
    queries += [("t9_pinyin", q) for q in ("33", "333", "36", "3636", "66", "64")]
    queries += [("pinyin", "zhonghuarenmingongheguo"), ("double_pinyin_flypy", "dayuyjmoxk")]
    queries += [("pinyin", q) for q in ("qingxuxingjinshi", "jiqixuexijishi", "rengongzhinengzhili")]
    snapshots = {}
    for name, bundle in (("before", before), ("after", after)):
        with tempfile.TemporaryDirectory() as user:
            run = subprocess.run([str(probe.resolve()), str(bundle.resolve()), user],
                                 input="".join(f"{s} {q}\n" for s, q in queries),
                                 text=True, capture_output=True, check=True)
        rows = [line.split("\t") for line in run.stdout.splitlines()]
        assert len(rows) == len(queries)
        snapshots[name] = rows
    old, new = [{(r[0], r[1]): r[2:] for r in snapshots[name]} for name in ("before", "after")]
    for query in ("ee", "e'e"):
        assert new["pinyin", query][0] == "呃呃" and "哦哦" not in new["pinyin", query]
    for query, phrase in (("eee", "呃呃呃"), ("oo", "哦哦"), ("yine", "吟哦"),
                          ("en", "嗯"), ("enen", "嗯嗯"), ("eneng", "嗯嗯"), ("engen", "嗯嗯"),
                          ("dayuyanmoxing", "大语言模型"), ("zhonghuarenmingongheguo", "中华人民共和国"),
                          ("qingxuxingjinshi", "情绪性进食"), ("jiqixuexijishi", "机器学习基石"),
                          ("rengongzhinengzhili", "人工智能治理"),
                          ("lixianyuyinshibie", "离线语音识别")):
        assert new["pinyin", query][0] == phrase, (query, new["pinyin", query])
    assert new["double_pinyin_flypy", "eeee"][0] == "呃呃"
    assert "大语言模型" in new["double_pinyin_flypy", "dayuyjmoxk"]
    assert "加油哦" not in new["pinyin", "jiayoue"]
    assert "嗯" not in new["t9_pinyin", "64"]
    for key in old:
        if key[0] == "t9_pinyin":
            assert old[key][:3] == new[key][:3], (key, old[key], new[key])
    print(json.dumps(snapshots, ensure_ascii=False))
    print(f"PASS {len(queries) * 2} native queries: interjections, long words, Flypy and T9 baseline ranking")


def check_apk(bundle: Path, apk_path: Path):
    manifest_path = bundle.parent / "rime-assets-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    report = json.loads((bundle / "BUILD_REPORT.json").read_text())
    assert set(report["additions"]) == {"modern.tsv"}
    assert report["builder_sha256"] == hashlib.sha256(
        (Path(__file__).parent / "build_candidate_dictionary.py").read_bytes()).hexdigest()
    assert any(item["path"] == "dicts/lianxiang.dict.yaml" for item in report["source"]["files"])
    with zipfile.ZipFile(apk_path) as apk:
        assert apk.read("assets/rime-assets-manifest.json") == manifest_path.read_bytes()
        for name, expected in manifest["system_assets"].items():
            data = (bundle / name).read_bytes()
            assert len(data) == expected["bytes"] and hashlib.sha256(data).hexdigest() == expected["sha256"], name
            assert apk.read("assets/rime/" + name) == data, name
        assert not any(name.endswith(".dict.yaml") or name == "assets/rime/essay.txt" for name in apk.namelist())
    print(f"PASS APK manifest and all {len(manifest['system_assets'])} Rime asset fingerprints")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", type=Path, help="Existing host Rime candidate probe")
    parser.add_argument("--before", type=Path)
    parser.add_argument("--after", type=Path)
    parser.add_argument("--apk", type=Path, help="Also verify the packaged production bundle")
    args = parser.parse_args()
    if any((args.probe, args.before)) and not (args.probe and args.before and args.after):
        parser.error("native checks require --probe, --before and --after")
    if args.apk and not args.after:
        parser.error("APK checks require --after")
    main()
    if args.probe:
        check_native(args.probe, args.before, args.after)
    if args.apk:
        check_apk(args.after, args.apk)

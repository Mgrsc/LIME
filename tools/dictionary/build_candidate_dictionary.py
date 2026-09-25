#!/usr/bin/env python3
"""Build an isolated, explicitly annotated dictionary from pinned source data."""
from __future__ import annotations

import argparse
from datetime import date
import hashlib
import json
import re
import unicodedata
import urllib.request
from pathlib import Path

from apply_edge_syllable_aliases import apply_aliases, read_aliases


ROOT = Path(__file__).resolve().parent
REJECTED_PHRASE_CODES = {
    ("哦哦", "e e"), ("加油哦", "jia you e"), ("不错哦", "bu cuo e"),
    ("哦了", "e le"), ("哦耶", "e ye"),
}


def fetch_source(source: dict, item: dict, cache: Path) -> Path:
    cached = cache / item["cache"]
    if not cached.exists():
        repo = source["repository"].removeprefix("https://github.com/")
        url = f"https://raw.githubusercontent.com/{repo}/{source['commit']}/{item['path']}"
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != item["sha256"]:
            raise ValueError(f"source hash mismatch: {url}")
        cached.write_bytes(data)
    if hashlib.sha256(cached.read_bytes()).hexdigest() != item["sha256"]:
        raise ValueError(f"source hash mismatch: {cached}")
    return cached


def normalize_code(code: str) -> str:
    decomposed = unicodedata.normalize("NFD", code)
    if any(unicodedata.category(c) == "Mn" and c not in "\u0304\u0301\u030c\u0300\u0308\u0302" for c in decomposed):
        raise ValueError(f"unsupported pronunciation mark: {code!r}")
    decomposed = decomposed.replace("u\u0308", "v")
    normalized = "".join(c for c in decomposed if unicodedata.category(c) != "Mn")
    if not re.fullmatch(r"[a-z]+(?: [a-z]+)*", normalized):
        raise ValueError(f"invalid pronunciation: {code!r}")
    return normalized


def entries(path: Path, excluded: tuple[str, ...] = ()):
    body = path.suffix == ".tsv"
    remaining = set(excluded)
    for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not body:
            body = line == "..."
            continue
        if not line.strip() or line.startswith("#"):
            continue
        if line in excluded:
            remaining.discard(line)
            continue
        fields = line.split("\t")
        expected = 6 if path.suffix == ".tsv" else 3
        if (len(fields) != expected or not fields[0] or not fields[2].isascii()
                or not fields[2].isdigit()
                or any(unicodedata.category(c).startswith("C") for c in fields[0])):
            raise ValueError(f"invalid entry at {path}:{number}")
        if expected == 6 and (not all(fields[3:]) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", fields[4])):
            raise ValueError(f"missing provenance at {path}:{number}")
        if expected == 6:
            date.fromisoformat(fields[4])
        code = normalize_code(fields[1])
        # Reject interjection 哦 mis-tagged as é; keep 吟哦=yín é.
        if (fields[0], code) in REJECTED_PHRASE_CODES:
            raise ValueError(f"rejected phrase pronunciation at {path}:{number}")
        yield fields[0], code, int(fields[2])
    if not body:
        raise ValueError(f"missing dictionary body: {path}")
    if remaining:
        raise ValueError(f"stale exclusions: {path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", choices=("frost", "wanxiang"))
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--lock", type=Path, default=ROOT / "candidates.lock.json",
                        help="Pinned source lock; update candidates can be built in isolation")
    parser.add_argument("--output", type=Path, required=True,
                        help="New output directory; existing directories are refused")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output directory already exists")
    lock_path = args.lock
    source = json.loads(lock_path.read_text())[args.source]
    args.cache.mkdir(parents=True, exist_ok=True)
    merged: dict[tuple[str, str], int] = {}
    duplicate_count = 0
    for item in source["files"]:
        cached = fetch_source(source, item, args.cache)
        if item["path"] == "LICENSE":
            continue
        for text, code, weight in entries(cached, tuple(item.get("excluded_rows", []))):
            key = (text, code)
            duplicate_count += key in merged
            # Upstream weights may be log-normalized; summing changes their meaning.
            merged[key] = max(merged.get(key, 0), weight)
    additions = {}
    for name in ("modern.tsv",):
        path = ROOT / name
        count = 0
        for text, code, weight in entries(path):
            if (text, code) not in merged:
                merged[text, code] = weight
                count += 1
        additions[name] = {"added": count, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    # Phrase-scoped compatibility: never derive eng from every en syllable.
    hum_weights = [weight for (text, code), weight in merged.items()
                   if text == "嗯嗯" and code in ("en en", "ng ng", "eng eng")]
    if not hum_weights or ("呃呃呃", "e e e") not in merged:
        raise ValueError("missing reviewed interjection source entries")
    priorities = {("嗯嗯", code): str(max(hum_weights))
                  for code in ("en en", "en eng", "eng en", "eng eng")}
    # Minimal lead within this source's scale; native T9 ranking is a release gate.
    priorities["呃呃呃", "e e e"] = str(max(
        weight for (_, code), weight in merged.items() if code == "e e e") + 1)
    args.output.mkdir(parents=True)
    dictionary = args.output / "pinyin.dict.yaml"
    with dictionary.open("w", encoding="utf-8") as output:
        output.write('---\nname: pinyin\nversion: "candidate"\nsort: by_weight\nuse_preset_vocabulary: false\n...\n')
        for (text, code), weight in sorted(merged.items()):
            output.write(f"{text}\t{code}\t{weight}\n")
    aliases = ROOT / "edge-syllable-aliases.tsv"
    alias_report = apply_aliases(dictionary, read_aliases(aliases), priorities)
    (args.output / "UPSTREAM_LICENSE").write_bytes((args.cache / f"{args.source}-LICENSE").read_bytes())
    # Preserve supplied source notices alongside the transformed data.
    source_headers = []
    for item in source["files"]:
        if item["path"] != "LICENSE":
            header = (args.cache / item["cache"]).read_text(encoding="utf-8-sig").split("\n...\n", 1)[0]
            source_headers.append(f"{item['path']}\n{header}\n")
    (args.output / "SOURCE_HEADERS.txt").write_text("\n".join(source_headers), encoding="utf-8")
    report = {"source": source, "normalized_duplicates": duplicate_count,
              "additions": additions, "aliases": alias_report,
              "phrase_overrides": [{"text": text, "code": code, "weight": int(weight)}
                                   for (text, code), weight in priorities.items()],
              "alias_sha256": hashlib.sha256(aliases.read_bytes()).hexdigest(),
              "builder_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "alias_builder_sha256": hashlib.sha256((ROOT / "apply_edge_syllable_aliases.py").read_bytes()).hexdigest(),
              "output_sha256": hashlib.sha256(dictionary.read_bytes()).hexdigest(),
              "status": "experimental; not approved for production distribution"}
    (args.output / "BUILD_REPORT.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    (args.output / "NOTICE.txt").write_text(
        f"数据来源：{source['repository']}\n提交：{source['commit']}\n"
        f"署名：{source['attribution']}\n许可链接：{source['license_url']}\n"
        f"上游许可：{source['license']}，见 UPSTREAM_LICENSE。原始声明保留在锁定源文件中。\n"
        "源文件头部声明副本见 SOURCE_HEADERS.txt。\n"
        "本项目修改：去声调（保留 ü→v）、同键取最大权重、补充独立整理词条及习惯码，"
        "限定嗯嗯混合习惯码及呃呃呃同码领先权重，详情见 BUILD_REPORT。\n"
        "实验产物，尚未完成生产分发来源审查；不代表整个应用采用该数据许可。\n",
        encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()

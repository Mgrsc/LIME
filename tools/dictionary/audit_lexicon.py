#!/usr/bin/env python3
"""Inspect bundled resources and a fresh Rime decompilation; never infer UI ranks."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import struct
from collections import Counter
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", type=Path, required=True)
    parser.add_argument("--simplified-dictionary", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    assets = root / "app/src/main/assets"
    table = assets / "rime/build/pinyin.table.bin"
    binary = table.read_bytes()
    # Metadata layout is defined by the repository's rime/dict/table.h.
    if binary[:32].rstrip(b"\0") != b"Rime::Table/4.0":
        raise ValueError("Unsupported table format; inspect its metadata layout first")
    _, syllable_count, entry_count = struct.unpack_from("<III", binary, 32)

    entries: set[tuple[str, str]] = set()
    phrases: set[str] = set()
    rows = 0
    in_body = False
    for number, line in enumerate(args.dictionary.read_text(encoding="utf-8").splitlines(), 1):
        if line == "...":
            in_body = True
            continue
        if not in_body or not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) < 2 or not fields[0] or not fields[1].strip():
            raise ValueError(f"Invalid dictionary row {number}")
        rows += 1
        entries.add((fields[0], " ".join(fields[1].split())))
        phrases.add(fields[0])
    if rows != entry_count:
        raise ValueError(f"Decompiled rows {rows} differ from table entries {entry_count}")

    raw_lines = args.dictionary.read_text(encoding="utf-8").splitlines()
    simplified_lines = args.simplified_dictionary.read_text(encoding="utf-8").splitlines()
    if len(raw_lines) != len(simplified_lines):
        raise ValueError("Simplification changed the number of dictionary lines")
    simplified_entries: set[tuple[str, str]] = set()
    in_body = False
    for raw, simplified in zip(raw_lines, simplified_lines):
        if raw == "...":
            in_body = True
            continue
        if not in_body or not raw or raw.startswith("#"):
            continue
        fields = simplified.split("\t")
        if fields[1:] != raw.split("\t")[1:] or not fields[0]:
            raise ValueError("Simplification changed a code or weight")
        simplified_entries.add((fields[0], " ".join(fields[1].split())))
    simplified_phrases = {phrase for phrase, _ in simplified_entries}

    with (Path(__file__).parent / "lexicon-regression.tsv").open(encoding="utf-8") as source:
        cases = list(csv.DictReader(source, delimiter="\t"))
    for case in cases:
        case["raw_phrase_present"] = case["phrase"] in phrases
        case["raw_phrase_code_present"] = (case["phrase"], case["code"]) in entries
        case["simplified_phrase_present"] = case["phrase"] in simplified_phrases
        case["simplified_phrase_code_present"] = (case["phrase"], case["code"]) in simplified_entries

    predictions: dict[str, list[dict[str, str | int]]] = {}
    pairs: set[tuple[str, str]] = set()
    for number, line in enumerate((assets / "predict/system_predict.tsv").read_text(encoding="utf-8").splitlines(), 1):
        fields = line.split("\t")
        if len(fields) != 3 or not fields[0] or not fields[1]:
            raise ValueError(f"Invalid prediction row {number}")
        head, tail, raw_weight = fields
        weight = int(raw_weight)
        if weight <= 0 or (head, tail) in pairs:
            raise ValueError(f"Invalid weight or duplicate prediction row {number}")
        pairs.add((head, tail))
        predictions.setdefault(head, []).append({"text": tail, "weight": weight})

    resources = {}
    for directory in (assets / "rime", assets / "predict"):
        for path in sorted(directory.rglob("*")):
            if path.is_file():
                resources[str(path.relative_to(root))] = {
                    "bytes": path.stat().st_size,
                    "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                }
    report = {
        "scope": "raw/simplified table presence and system TSV only; no scheme ranking or device acceptance",
        "dictionary": {
            "syllables": syllable_count,
            "entries": entry_count,
            "unique_phrase_code_pairs": len(entries),
            "unique_raw_phrases": len(phrases),
            "unique_simplified_phrases": len(simplified_phrases),
            "unique_simplified_phrase_code_pairs": len(simplified_entries),
            "decompiled_sha256": hashlib.sha256(args.dictionary.read_bytes()).hexdigest(),
            "simplified_sha256": hashlib.sha256(args.simplified_dictionary.read_bytes()).hexdigest(),
            "regression": cases,
        },
        "predictions": {
            "pairs": len(pairs),
            "heads": len(predictions),
            "successor_count_distribution": dict(sorted(Counter(map(len, predictions.values())).items())),
            "known_contexts": {word: predictions.get(word, []) for word in ("三亚", "上海", "手机", "你好", "今天")},
        },
        "resources": resources,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Audited {entry_count} entries, {len(predictions)} prediction heads; report: {args.output}")


if __name__ == "__main__":
    main()

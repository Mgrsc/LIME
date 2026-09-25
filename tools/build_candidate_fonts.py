#!/usr/bin/env python3
"""Subset the licensed source fonts to supplementary Han used by Rime dictionaries."""

import argparse
import hashlib
import json
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", type=Path, action="append", required=True)
    parser.add_argument("--hanazono", type=Path, required=True)
    parser.add_argument("--babelstone", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    required = set()
    dictionaries = {}
    for path in args.dictionary:
        data = path.read_bytes()
        dictionaries[path.name] = hashlib.sha256(data).hexdigest()
        for line in data.decode("utf-8").splitlines():
            if "\t" in line and not line.startswith("#"):
                required.update(ord(c) for c in line.split("\t", 1)[0]
                                if 0x20000 <= ord(c) <= 0x3FFFF)
    if not required:
        raise ValueError("No supplementary Han found in dictionary input")

    sources = [
        (args.hanazono, "829543436cdf7b4c6730e2af5806fa406f8ddf0d4b656e0cd49c58d4db1c8d3c",
         "lime_candidate_fallback", "Lime Candidate Fallback"),
        (args.babelstone, "d8bb747b3fdccd84a60bd0aa56bb90937270d0bd15f1101cd8f2a5a3709dd0a3",
         "lime_candidate_extra", "Lime Candidate Extra"),
    ]
    remaining = required.copy()
    prepared = []
    for path, expected, filename, family in sources:
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError(f"Unexpected source font checksum: {path}")
        font = TTFont(path, recalcTimestamp=False)
        covered = remaining.intersection(font.getBestCmap())
        remaining -= covered
        prepared.append((font, covered, filename, family, expected))
    if remaining:
        raise ValueError(f"Missing source glyphs: {', '.join(f'U+{c:X}' for c in sorted(remaining))}")

    args.output.mkdir(parents=True, exist_ok=True)
    report = {"dictionaries": dictionaries, "supplementary_han_count": len(required), "fonts": {}}
    for font, covered, filename, family, source_hash in prepared:
        options = subset.Options()
        options.name_IDs = ["*"]
        options.name_legacy = True
        options.name_languages = ["*"]
        options.drop_tables += ["FFTM", "meta"]
        subsetter = subset.Subsetter(options=options)
        subsetter.populate(unicodes=covered)
        subsetter.subset(font)
        for record in font["name"].names:
            if record.nameID in (1, 3, 4, 6, 16, 17, 18):
                value = family.replace(" ", "") if record.nameID == 6 else family
                if record.nameID == 17:
                    value = "Regular"
                record.string = value.encode(record.getEncoding())
        output = args.output / f"{filename}.ttf"
        font.save(output)
        data = output.read_bytes()
        report["fonts"][output.name] = {
            "source_sha256": source_hash,
            "sha256": hashlib.sha256(data).hexdigest(),
            "characters": len(covered),
            "bytes": len(data),
        }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

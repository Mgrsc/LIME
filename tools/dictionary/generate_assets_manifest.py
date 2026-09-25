#!/usr/bin/env python3
"""Generate a verifiable, reproducible asset manifest for bundled Rime resources."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

def compute_sha256(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest()

REQUIRED_FILES = {
    *(f"{schema}.schema.yaml" for schema in ("pinyin", "double_pinyin_flypy", "t9_pinyin", "english")),
    *(f"build/{schema}.schema.yaml" for schema in ("pinyin", "double_pinyin_flypy", "t9_pinyin", "english")),
    *(f"build/{schema}.prism.bin" for schema in ("pinyin", "double_pinyin_flypy", "t9_pinyin", "english")),
    "default.yaml",
    "build/default.yaml",
    "build/pinyin.table.bin",
    "build/english.table.bin",
    "build/pinyin.reverse.bin",
    *(f"opencc/{name}" for name in (
        "s2t.json", "STPhrases.ocd2", "STCharacters.ocd2",
        "emoji.json", "emoji.ocd2", "others.ocd2"
    )),
}

def main() -> None:
    root = Path(__file__).resolve().parents[2]
    rime_assets = root / "app/src/main/assets/rime"
    if not rime_assets.is_dir():
        raise FileNotFoundError(f"Rime assets directory not found: {rime_assets}")

    assets_dict: dict[str, dict[str, object]] = {}
    for path in sorted(rime_assets.rglob("*")):
        if path.is_file() and path.name not in {"user.yaml", "installation.yaml", "custom_phrase.txt"}:
            rel_path = str(path.relative_to(rime_assets))
            assets_dict[rel_path] = {
                "bytes": path.stat().st_size,
                "sha256": compute_sha256(path),
            }

    missing = REQUIRED_FILES - set(assets_dict.keys())
    if missing:
        raise RuntimeError(f"Missing required bundled asset files in manifest: {sorted(missing)}")

    metadata = {
        "rime_version": "1.17.0",
        "table_format": "Rime::Table/4.0",
        "dict_data_version": 2026091301,
    }
    report_path = rime_assets / "BUILD_REPORT.json"
    if report_path.exists():
        report = json.loads(report_path.read_text(encoding="utf-8"))
        metadata = {
            "rime_version": "1.17.0",
            "table_format": "Rime::Table/4.0",
            "dict_data_version": 2026091301,
            "upstream_repository": report["source"]["repository"],
            "upstream_commit": report["source"]["commit"],
            "upstream_license": report["source"]["license"],
            "dictionary_source_sha256": report["output_sha256"],
        }
    manifest = {
        "metadata": metadata,
        "ownership": {
            "preserved_user_files": [
                "user.yaml",
                "installation.yaml",
                "custom_phrase.txt"
            ],
            "runtime_derived_patterns": [
                "*.userdb",
                "*.userdb.kct"
            ],
            "system_assets_count": len(assets_dict),
        },
        "system_assets": assets_dict,
    }

    out_file = root / "app/src/main/assets/rime-assets-manifest.json"
    out_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated manifest with {len(assets_dict)} system assets at {out_file}")

if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Check a fixed Wanxiang snapshot and write an isolated lock and exact diff."""
from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import urllib.parse
import urllib.request

from build_candidate_dictionary import ROOT, entries, fetch_source


def compare_entries(before: Path, after: Path, excluded: tuple[str, ...], output: Path) -> dict:
    def read(path):
        result = {}
        for text, code, weight in entries(path, excluded):
            key = (text, code)
            result[key] = max(result.get(key, 0), weight)
        if not result:
            raise ValueError(f"empty source dictionary: {path}")
        return result

    old, new = read(before), read(after)
    added = new.keys() - old.keys()
    removed = old.keys() - new.keys()
    code_changes = {text for text, _ in added} & {text for text, _ in removed}
    counts = {"before": len(old), "after": len(new), "added": len(added),
              "removed": len(removed), "weight_changed": 0,
              "phrases_with_replaced_codes": len(code_changes)}
    with output.open("w", encoding="utf-8") as stream:
        stream.write("change\tphrase\tcode\told_weight\tnew_weight\n")
        for text, code in sorted(added):
            stream.write(f"added\t{text}\t{code}\t\t{new[text, code]}\n")
        for text, code in sorted(removed):
            stream.write(f"removed\t{text}\t{code}\t{old[text, code]}\t\n")
        for key, weight in old.items():
            if key in new and weight != new[key]:
                counts["weight_changed"] += 1
                stream.write(f"weight\t{key[0]}\t{key[1]}\t{weight}\t{new[key]}\n")
    return counts


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default="HEAD", help="Resolve upstream ref once, then fetch only that commit")
    parser.add_argument("--cache", type=Path, required=True, help="Cache of the currently approved source")
    parser.add_argument("--output", type=Path, required=True, help="New directory inside .dev")
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(ROOT.parents[1] / ".dev") or output.exists():
        parser.error("output must be a new directory inside .dev")
    lock = json.loads((ROOT / "candidates.lock.json").read_text())
    current = lock["wanxiang"]
    repo = current["repository"].removeprefix("https://github.com/")
    # The allowlist and attribution come from the reviewed local lock, never upstream config.
    if repo != "amzxyz/rime-wanxiang":
        parser.error("unexpected source repository")
    output.mkdir(parents=True)
    report = {"checked_at": datetime.now(timezone.utc).isoformat(),
              "previous_commit": current["commit"], "files": [], "blockers": []}
    try:
        url = f"https://api.github.com/repos/{repo}/commits/{urllib.parse.quote(args.ref, safe='')}"
        with urllib.request.urlopen(url, timeout=60) as response:
            commit = json.load(response)["sha"]
        if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
            raise ValueError("invalid resolved commit")
        report["candidate_commit"] = commit
        candidate = deepcopy(current)
        candidate["commit"] = commit
        cache = output / "cache"
        cache.mkdir()
        args.cache.mkdir(parents=True, exist_ok=True)
        # Warm local cache for the approved reference source if missing, validated against lock SHA-256.
        for item, proposed in zip(current["files"], candidate["files"]):
            before = fetch_source(current, item, args.cache)
            with urllib.request.urlopen(
                    f"https://raw.githubusercontent.com/{repo}/{commit}/{item['path']}", timeout=60) as response:
                data = response.read()
            proposed["sha256"] = hashlib.sha256(data).hexdigest()
            after = cache / item["cache"]
            after.write_bytes(data)
            info = {"path": item["path"], "previous_sha256": item["sha256"],
                    "sha256": proposed["sha256"], "bytes": len(data)}
            report["files"].append(info)
            if item["path"] == "LICENSE":
                if proposed["sha256"] != item["sha256"]:
                    report["blockers"].append("upstream license changed; review distribution permission")
                continue
            headers = [p.read_text(encoding="utf-8-sig").split("\n...\n", 1)[0]
                       for p in (before, after)]
            info["header_changed"] = headers[0] != headers[1]
            if info["header_changed"]:
                info["headers"] = {"before": headers[0], "after": headers[1]}
            try:
                counts = compare_entries(before, after, tuple(item.get("excluded_rows", [])),
                                         output / (Path(item["path"]).stem + ".diff.tsv"))
                info.update(counts)
                # yagni: conservative 20% churn gate; tune only after measured update history.
                if max(counts["added"], counts["removed"]) > counts["before"] * 0.2:
                    report["blockers"].append(f"source churn exceeds 20%: {item['path']}")
            except ValueError as error:
                report["blockers"].append(str(error))
        report["source_changed"] = any(f["sha256"] != f["previous_sha256"] for f in report["files"])
        if not report["blockers"]:
            lock["wanxiang"] = candidate
            (output / "candidates.lock.json").write_text(
                json.dumps(lock, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except Exception as error:
        report["blockers"].append(f"{type(error).__name__}: {error}")
    report["status"] = "blocked" if report["blockers"] else "candidate; native regression required before publication"
    (output / "UPDATE_REPORT.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    if report["blockers"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

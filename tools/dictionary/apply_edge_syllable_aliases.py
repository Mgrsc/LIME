#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path


class AliasError(RuntimeError):
    pass


@dataclass(frozen=True)
class DictionaryEntry:
    line_index: int
    text: str
    code: str
    weight: str


@dataclass(frozen=True)
class Weight:
    value: float
    is_relative: bool


def parse_weight(value: str, location: str) -> Weight:
    is_relative = value.endswith("%")
    raw_value = value.removesuffix("%")
    try:
        weight_value = float(raw_value)
    except ValueError as error:
        raise AliasError(f"invalid dictionary weight at {location}") from error
    if weight_value < 0:
        raise AliasError(f"invalid dictionary weight at {location}")
    return Weight(weight_value, is_relative)


def compare_absolute_weights(left: str, right: str, location: str) -> int:
    left_weight = parse_weight(left, location)
    right_weight = parse_weight(right, location)
    if left_weight.is_relative or right_weight.is_relative:
        raise AliasError(f"cannot compare relative dictionary weights at {location}")
    return (left_weight.value > right_weight.value) - (left_weight.value < right_weight.value)


def should_replace_weight(current: str, candidate: str, location: str) -> bool:
    current_weight = parse_weight(current, location)
    candidate_weight = parse_weight(candidate, location)
    if candidate_weight.is_relative:
        raise AliasError(f"generated alias weight must be absolute at {location}")
    if current_weight.is_relative and not candidate_weight.is_relative:
        return True
    return candidate_weight.value > current_weight.value


def stronger_source_weight(left: str, right: str, location: str) -> str:
    return left if compare_absolute_weights(left, right, location) >= 0 else right


def resolved_alias_weight(priority: str | None, source: str, location: str) -> str:
    if priority is None:
        if parse_weight(source, location).is_relative:
            raise AliasError(f"relative generated alias weight requires explicit priority at {location}")
        return source
    source_weight = parse_weight(source, location)
    if source_weight.is_relative:
        return priority
    return priority if compare_absolute_weights(priority, source, location) >= 0 else source


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_code(value: str, location: str) -> tuple[str, ...]:
    syllables = tuple(value.split())
    if not syllables or any(not syllable.isascii() or not syllable.isalpha() for syllable in syllables):
        raise AliasError(f"invalid code at {location}: {value}")
    return syllables


def read_aliases(path: Path) -> dict[str, tuple[str, ...]]:
    aliases: dict[str, tuple[str, ...]] = {}
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) not in {2, 3}:
            raise AliasError(f"invalid alias row at {path}:{line_number}")
        source, *targets = fields
        validate_code(source, f"{path}:{line_number}")
        if len(source.split()) != 1 or any(len(target.split()) != 1 for target in targets):
            raise AliasError(f"aliases must map one syllable at {path}:{line_number}")
        for target in targets:
            validate_code(target, f"{path}:{line_number}")
        if source in aliases:
            raise AliasError(f"duplicate alias source at {path}:{line_number}: {source}")
        aliases[source] = tuple(targets)
    if not aliases:
        raise AliasError(f"no aliases in {path}")
    return aliases


def read_priorities(path: Path) -> dict[tuple[str, str], str]:
    priorities: dict[tuple[str, str], str] = {}
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 3:
            raise AliasError(f"invalid priority row at {path}:{line_number}")
        text, code, raw_weight = fields
        validate_code(code, f"{path}:{line_number}")
        try:
            weight = int(raw_weight)
        except ValueError as error:
            raise AliasError(f"invalid priority weight at {path}:{line_number}") from error
        if not text or weight <= 0:
            raise AliasError(f"invalid priority row at {path}:{line_number}")
        key = (text, code)
        if key in priorities:
            raise AliasError(f"duplicate priority row at {path}:{line_number}")
        priorities[key] = raw_weight
    return priorities


def read_dictionary(path: Path) -> tuple[list[str], list[DictionaryEntry]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    try:
        body_index = lines.index("...")
    except ValueError as error:
        raise AliasError(f"dictionary separator missing: {path}") from error
    entries: list[DictionaryEntry] = []
    seen: set[tuple[str, str]] = set()
    for line_index, line in enumerate(lines[body_index + 1 :], start=body_index + 1):
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) not in {2, 3}:
            raise AliasError(f"invalid dictionary row at {path}:{line_index + 1}")
        text, code = fields[:2]
        raw_weight = fields[2] if len(fields) == 3 else "0"
        validate_code(code, f"{path}:{line_index + 1}")
        parse_weight(raw_weight, f"{path}:{line_index + 1}")
        key = (text, code)
        if key in seen:
            raise AliasError(f"duplicate dictionary entry at {path}:{line_index + 1}: {text}\t{code}")
        seen.add(key)
        entries.append(DictionaryEntry(line_index, text, code, raw_weight))
    return lines, entries


def projected_codes(code: str, aliases: dict[str, tuple[str, ...]]) -> tuple[str, ...]:
    syllables = code.split()
    changed = any(syllable in aliases for syllable in syllables)
    if not changed:
        return ()
    variants: list[str] = []
    max_variant_count = max(len(aliases[syllable]) for syllable in syllables if syllable in aliases)
    # Keep phrase expansion bounded: nth aliases are paired together instead of
    # generating every cross-product combination.
    for variant_index in range(max_variant_count):
        variant = " ".join(
            aliases[syllable][min(variant_index, len(aliases[syllable]) - 1)]
            if syllable in aliases else syllable
            for syllable in syllables
        )
        if variant != code and variant not in variants:
            variants.append(variant)
    return tuple(variants)


def apply_aliases(
    dictionary: Path,
    aliases: dict[str, tuple[str, ...]],
    priorities: dict[tuple[str, str], str],
) -> dict[str, int]:
    lines, entries = read_dictionary(dictionary)
    entry_by_key = {(entry.text, entry.code): entry for entry in entries}
    generated: dict[tuple[str, str], str] = {}
    for entry in entries:
        for alias_code in projected_codes(entry.code, aliases):
            key = (entry.text, alias_code)
            previous_weight = generated.get(key)
            if previous_weight is None:
                generated[key] = entry.weight
                continue
            generated[key] = stronger_source_weight(
                entry.weight,
                previous_weight,
                f"{dictionary}:{entry.line_index + 1}",
            )

    changed_lines: dict[int, str] = {}
    added_entries: dict[tuple[str, str], str] = {}
    for key, generated_weight in generated.items():
        weight = resolved_alias_weight(
            priorities.get(key),
            generated_weight,
            f"{dictionary}:{key[0]}\t{key[1]}",
        )
        original = entry_by_key.get(key)
        if original is None:
            added_entries[key] = weight
            continue
        if should_replace_weight(original.weight, weight, f"{dictionary}:{original.line_index + 1}"):
            changed_lines[original.line_index] = f"{original.text}\t{original.code}\t{weight}"

    for key, priority in priorities.items():
        if key in generated:
            continue
        original = entry_by_key.get(key)
        if original is None:
            existing_weight = added_entries.get(key)
            if existing_weight is None or should_replace_weight(
                existing_weight,
                priority,
                f"{dictionary}:{key[0]}\t{key[1]}",
            ):
                added_entries[key] = priority
            continue
        if should_replace_weight(original.weight, priority, f"{dictionary}:{original.line_index + 1}"):
            changed_lines[original.line_index] = f"{original.text}\t{original.code}\t{priority}"

    for line_index, line in changed_lines.items():
        lines[line_index] = line
    lines.extend(
        f"{text}\t{code}\t{weight}"
        for (text, code), weight in sorted(added_entries.items())
    )
    dictionary.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return {
        "generated_entries": len(generated),
        "added_entries": len(added_entries),
        "updated_entries": len(changed_lines),
    }


def update_report(path: Path, dictionary: Path, summary: dict[str, int]) -> None:
    report = json.loads(path.read_text(encoding="utf-8"))
    report["output_sha256"] = sha256(dictionary)
    report["edge_syllable_aliases"] = summary
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> dict[str, int]:
    parser = argparse.ArgumentParser()
    parser.add_argument("dictionary", type=Path)
    parser.add_argument("--aliases", required=True, type=Path)
    parser.add_argument("--priorities", required=True, type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args(argv)
    summary = apply_aliases(args.dictionary, read_aliases(args.aliases), read_priorities(args.priorities))
    if args.report is not None:
        update_report(args.report, args.dictionary, summary)
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
    return summary


if __name__ == "__main__":
    try:
        main()
    except AliasError as error:
        raise SystemExit(f"edge syllable alias build failed: {error}")

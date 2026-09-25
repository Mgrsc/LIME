#!/usr/bin/env python3
"""
Filter and normalize predict data for Lime/Liuqing IME system prediction asset.
Clean rules:
1. Chinese characters only (\u4e00..\u9fff), length 2..6 for both context and candidate.
2. Filter out non-predicative modifiers ("二手"), ad banners ("看新闻"), and web navigation artifacts ("首页", "列表", "全文", "登录", "注册").
3. Apply tiered frequency thresholds: higher threshold for 2-char heads, lower threshold for specific 3..6 char heads.
4. Deterministic sorting: context alphabetically, candidates by (-weight, candidate_text).
"""

import argparse
import sys
from pathlib import Path
import csv
from collections import defaultdict

STOP_CANDIDATES = frozenset({"二手", "看新闻", "首页", "列表", "全文", "登录", "注册"})


def is_chinese_phrase(s: str) -> bool:
    return 2 <= len(s) <= 6 and all("\u4e00" <= ch <= "\u9fff" for ch in s)


def main():
    parser = argparse.ArgumentParser(description="Filter prediction data")
    parser.add_argument("--thresh-2char", type=int, default=12000, help="Min weight for 2-char context words")
    parser.add_argument("--thresh-multi", type=int, default=2500, help="Min weight for 3-6 char context words")
    parser.add_argument("--max-candidates", type=int, default=6, help="Max candidates per context word")
    parser.add_argument("--input", type=str, default="-", help="Input file (default stdin)")
    parser.add_argument("--output", type=str, default="-", help="Output file (default stdout)")
    args = parser.parse_args()

    if args.thresh_2char <= 0 or args.thresh_multi <= 0 or not 1 <= args.max_candidates <= 8:
        parser.error("Thresholds must be positive and max-candidates must be in 1..8")
    with Path(__file__).with_name("prediction-exclusions.tsv").open(encoding="utf-8") as f:
        exclusions = {(row["context"], row["candidate"]) for row in csv.DictReader(f, delimiter="\t")}
    pairs = defaultdict(dict)

    in_f = sys.stdin if args.input == "-" else open(args.input, "r", encoding="utf-8")
    try:
        for number, line in enumerate(in_f, 1):
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.rstrip("\r\n").split("\t")
            if len(parts) != 3:
                raise ValueError(f"Invalid prediction row {number}")
            w1, w2, wt_str = parts[0], parts[1], parts[2]
            try:
                wt = int(wt_str)
            except ValueError as exc:
                raise ValueError(f"Invalid prediction weight at row {number}") from exc
            if wt <= 0:
                raise ValueError(f"Nonpositive prediction weight at row {number}")
            if not (is_chinese_phrase(w1) and is_chinese_phrase(w2)):
                continue
            if w2 in STOP_CANDIDATES or (w1, w2) in exclusions:
                continue

            if wt > pairs[w1].get(w2, 0):
                pairs[w1][w2] = wt
    finally:
        if in_f is not sys.stdin:
            in_f.close()

    if not any(wt >= (args.thresh_2char if len(head) == 2 else args.thresh_multi)
               for head, tails in pairs.items() for wt in tails.values()):
        raise ValueError("No prediction pairs survived validation")

    out_f = sys.stdout if args.output == "-" else open(args.output, "w", encoding="utf-8", newline="\n")
    try:
        sorted_contexts = sorted(pairs.keys())
        for w1 in sorted_contexts:
            min_thresh = args.thresh_2char if len(w1) == 2 else args.thresh_multi
            valid_cands = [(w2, wt) for w2, wt in pairs[w1].items() if wt >= min_thresh]
            if not valid_cands:
                continue
            valid_cands.sort(key=lambda x: (-x[1], x[0]))
            for w2, wt in valid_cands[: args.max_candidates]:
                out_f.write(f"{w1}\t{w2}\t{wt}\n")
    finally:
        if out_f is not sys.stdout:
            out_f.close()


if __name__ == "__main__":
    main()

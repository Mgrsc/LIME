import argparse
import json
from pathlib import Path
import subprocess
import tempfile


WAKE_PHRASE = "\u6211\u521a\u7761\u9192"
SLEEP_PREFIX = "\u6211\u521a\u7761"
LEARNED_CHARACTER = "\u99a8"
WRONG_PHRASE = SLEEP_PREFIX + LEARNED_CHARACTER
CUSTOM_PHRASE = LEARNED_CHARACTER + "\u9192"


def parse_rows(output):
    rows = []
    for line in output.splitlines():
        fields = line.split("\t")
        if len(fields) < 10:
            raise ValueError("Unexpected probe output")
        entries = [item.split("|") for item in fields[9:] if "|" in item]
        candidates = [item[0] for item in entries]
        commit = next((item[len("commit="):] for item in fields[9:]
                       if item.startswith("commit=")), "")
        rows.append({"input": fields[1], "phase": fields[2],
                     "candidates": candidates, "types": [item[2] for item in entries],
                     "commit": commit})
    return rows


def typed(rows, input_text):
    return [row for row in rows if row["input"] == input_text and row["phase"] == "typed"]


def require_first(rows, input_text, expected):
    matches = typed(rows, input_text)
    assert matches, ("Missing query", input_text)
    for row in matches:
        assert row["candidates"] and row["candidates"][0] == expected, row


def require_no_user_phrase(rows, input_text, phrase):
    matches = typed(rows, input_text)
    assert matches, ("Missing query", input_text)
    for row in matches:
        assert not any(text == phrase and kind == "user_phrase"
                       for text, kind in zip(row["candidates"], row["types"])), row


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--bundle", type=Path,
                        default=Path(__file__).resolve().parents[2] / "app/src/main/assets/rime")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    scratch = root / ".dev/scratch"
    scratch.mkdir(parents=True, exist_ok=True)
    results = []

    with tempfile.TemporaryDirectory(prefix="learning-regression-", dir=scratch) as directory:
        work = Path(directory)

        def run(name, commands, user=None):
            user = user or work / name
            result = subprocess.run(
                [str(args.probe.resolve()), str(args.bundle.resolve()), str(user), "persistent"],
                input="".join(f"{schema} {input_text} {operations}\n"
                              for schema, input_text, operations in commands),
                capture_output=True, text=True, check=True)
            rows = parse_rows(result.stdout)
            results.append({"name": name, "rows": rows})
            return rows

        def query(input_text, operations="none", schema="pinyin"):
            return schema, input_text, operations

        def learn(input_text, text, suffix=""):
            return query(input_text, "pick=" + text + suffix)

        rows = run("single_character", [learn("xin", LEARNED_CHARACTER),
                                         query("wgsx", "clear"), query("xin")])
        require_first(rows, "wgsx", WAKE_PHRASE)
        require_first(rows[2:], "xin", LEARNED_CHARACTER)
        assert [row["commit"] for row in rows if row["commit"]] == [LEARNED_CHARACTER]

        for boundary in ("", ",clear", ",Escape", ",BackSpace"):
            rows = run("separate_commits_" + (boundary[1:] or "direct"), [
                learn("wgs", SLEEP_PREFIX, boundary), learn("xin", LEARNED_CHARACTER),
                query("wgsx")])
            require_first(rows, "wgsx", WAKE_PHRASE)
            require_no_user_phrase(rows, "wgsx", WRONG_PHRASE)

        for first, second in (("kongjian", "jijin"), ("xin", "wgs")):
            first_text = "\u7a7a\u95f4" if first == "kongjian" else LEARNED_CHARACTER
            second_text = "\u57fa\u91d1" if second == "jijin" else SLEEP_PREFIX
            rows = run("independent_" + first, [learn(first, first_text), learn(second, second_text),
                                               query(first + second)])
            require_no_user_phrase(rows, first + second, first_text + second_text)

        t9_user = work / "independent_t9"
        t9_whole = "\u7a7a\u95f4\u57fa\u91d1"
        rows = run("independent_t9_commits", [
            query("56645426", "pick=\u7a7a\u95f4", "t9_pinyin"),
            query("54546", "pick=\u57fa\u91d1", "t9_pinyin"),
            query("5664542654546", "clear", "t9_pinyin"),
            query("5555", "clear", "t9_pinyin")], t9_user)
        assert [row["commit"] for row in rows if row["commit"]] == [
            "\u7a7a\u95f4", "\u57fa\u91d1"], rows
        for input_text in ("5664542654546", "5555"):
            require_no_user_phrase(rows, input_text, t9_whole)
        rows = run("independent_t9_restart", [
            query("5664542654546", "clear", "t9_pinyin"),
            query("5555", "clear", "t9_pinyin")], t9_user)
        for input_text in ("5664542654546", "5555"):
            require_no_user_phrase(rows, input_text, t9_whole)

        user = work / "custom"
        rows = run("custom_composition", [query("xinxing", "pick=" + LEARNED_CHARACTER
                    + ",pick=\u9192"), query("xinxing")], user)
        assert CUSTOM_PHRASE in [row["commit"] for row in rows], rows
        require_first(rows[-1:], "xinxing", CUSTOM_PHRASE)
        rows = run("custom_restart", [query("xinxing")], user)
        require_first(rows, "xinxing", CUSTOM_PHRASE)

        rows = run("learning_disabled", [query("xin", "writes=off,pick=" + LEARNED_CHARACTER),
                                          query("xin", "clear"), query("wgsx")])
        require_first(rows[-3:], "xin", "\u65b0")
        require_first(rows, "wgsx", WAKE_PHRASE)

        rows = run("learning_reenabled", [learn("wgs", SLEEP_PREFIX),
            query("xin", "writes=off,pick=" + LEARNED_CHARACTER),
            query("xin", "writes=on,pick=" + LEARNED_CHARACTER), query("wgsx")])
        require_first(rows, "wgsx", WAKE_PHRASE)

        rows = run("undo_character_learning", [learn("xin", LEARNED_CHARACTER, ",BackSpace"),
                                               query("xin")])
        require_first(rows[-1:], "xin", "\u65b0")

        rows = run("cancelled_composition", [query("xinxing", "pick=" + LEARNED_CHARACTER + ",clear"),
                                             query("xinxing")])
        assert not [row for row in rows if row["commit"]], rows
        require_first(rows[-1:], "xinxing", "\u65b0\u578b")

        rows = run("retained_typo_and_correction", [learn("xin", LEARNED_CHARACTER),
            query("wgsx", "pick=" + SLEEP_PREFIX + ",space"), query("wgsx", "pick=" + WAKE_PHRASE),
            query("wgsx")])
        require_first(rows[-1:], "wgsx", WAKE_PHRASE)
        assert WRONG_PHRASE in rows[-1]["candidates"], rows[-1]

        rows = run("schema_switch", [learn("wgs", SLEEP_PREFIX),
            query("hello", "clear", "english"), learn("xin", LEARNED_CHARACTER), query("wgsx")])
        require_first(rows, "wgsx", WAKE_PHRASE)

    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
    print(f"PASS {len(results)} native learning scenarios")


if __name__ == "__main__":
    main()

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
                     "commit_counts": [int(item[5]) for item in entries],
                     "weights": [float(item[6]) for item in entries],
                     "commit": commit})
    return rows


def typed(rows, input_text):
    return [row for row in rows if row["input"] == input_text and row["phase"] == "typed"]


def user_preferences(row):
    return {text: (count, weight) for text, kind, count, weight in zip(
        row["candidates"], row["types"], row["commit_counts"], row["weights"])
        if kind == "user_phrase"}


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

        rows = run("default_suffix_does_not_create_phrase", [learn("xin", LEARNED_CHARACTER),
            query("wgsx", "pick=" + SLEEP_PREFIX + ",space"), query("wgsx")])
        require_no_user_phrase(rows[-1:], "wgsx", WRONG_PHRASE)

        rows = run("default_prefix_does_not_create_phrase", [
            query("xinxing", "caret=3,accept,pick=\u9192"), query("xinxing")])
        require_no_user_phrase(rows[-1:], "xinxing", "\u65b0\u9192")

        rows = run("reopened_selection_does_not_create_phrase", [
            query("xinxing", "pick=" + LEARNED_CHARACTER +
                  ",BackSpace,caret=3,accept,pick=\u9192"), query("xinxing")])
        require_no_user_phrase(rows[-1:], "xinxing", "\u65b0\u9192")
        require_no_user_phrase(rows[-1:], "xinxing", CUSTOM_PHRASE)

        rows = run("retained_typo_and_correction", [learn("xin", LEARNED_CHARACTER),
            query("wgsx", "pick=" + SLEEP_PREFIX + ",pick=" + LEARNED_CHARACTER),
            query("wgsx", "pick=" + WAKE_PHRASE),
            query("wgsx")])
        require_first(rows[-1:], "wgsx", WAKE_PHRASE)
        assert WRONG_PHRASE in rows[-1]["candidates"], rows[-1]

        rows = run("schema_switch", [learn("wgs", SLEEP_PREFIX),
            query("hello", "clear", "english"), learn("xin", LEARNED_CHARACTER), query("wgsx")])
        require_first(rows, "wgsx", WAKE_PHRASE)

        wrong_gender = "\u7537\u7684\u96be\u9053"
        correct_gender = "\u7537\u7684\u5973\u7684"
        for confirmation in ("space", "accept", "exclam"):
            rows = run("default_confirmation_" + confirmation, [
                *[query("nandenandao", confirmation) for _ in range(5)], query("ndnd")])
            require_no_user_phrase(rows[-1:], "ndnd", wrong_gender)
            require_first(rows[-1:], "ndnd", correct_gender)

            user = work / ("corrected_preference_" + confirmation)
            rows = run("corrected_preference_" + confirmation, [
                learn("nandenandao", wrong_gender), learn("ndnd", correct_gender),
                query("ndnd", "clear"),
                *[query("nandenandao", confirmation) for _ in range(5)],
                query("ndnd", "clear")], user)
            before, after = typed(rows, "ndnd")[-2:]
            require_first([before, after], "ndnd", correct_gender)
            for row in (before, after):
                assert all(text in row["candidates"] for text in
                           (wrong_gender, correct_gender)), row
            assert user_preferences(before) == user_preferences(after), (before, after)
            rows = run("corrected_preference_restart_" + confirmation,
                       [query("ndnd", "clear")], user)
            require_first(rows, "ndnd", correct_gender)
            restarted = typed(rows, "ndnd")[-1]
            assert user_preferences(after) == user_preferences(restarted), (after, restarted)

        user = work / "explicit_first"
        for short_input, full_input, phrase in (
            ("zdjd", "zhendejiade", "\u771f\u7684\u5047\u7684"),
            ("ndnd", "nidenaodai", "\u4f60\u7684\u8111\u888b"),
            ("ggsx", "ganggangshangxian", "\u521a\u521a\u4e0a\u7ebf"),
            ("zdjd", "zhudejiudian", "\u4f4f\u7684\u9152\u5e97"),
        ):
            scope_user = work / ("abbreviated_" + full_input)
            rows = run("abbreviated_selection_" + full_input, [
                learn(short_input, phrase), query(short_input, "clear"),
                query(full_input, "clear")], scope_user)
            require_first(typed(rows, short_input)[-1:], short_input, phrase)
            require_first(typed(rows, full_input), full_input, phrase)
            rows = run("abbreviated_restart_" + full_input,
                       [query(short_input, "clear")], scope_user)
            require_first(rows, short_input, phrase)

        rows = run("abbreviated_correction_overrides_full_spelling_history", [
            *[learn("zhendejuede", "\u771f\u7684\u89c9\u5f97") for _ in range(10)],
            learn("zdjd", "\u771f\u7684\u5047\u7684"), query("zdjd", "clear")])
        require_first(typed(rows, "zdjd")[-1:], "zdjd", "\u771f\u7684\u5047\u7684")

        short_phrase = "\u4f60\u7684\u8111\u888b"
        rows = run("abbreviated_scope_delete_and_relearn", [
            learn("ndnd", short_phrase), query("ndnd", "clear"),
            query("nidenaodai", "drop=" + short_phrase + ",clear"),
            learn("nidenaodai", short_phrase), query("ndnd", "clear"),
            learn("ndnd", short_phrase), query("ndnd", "clear")])
        scoped_queries = typed(rows, "ndnd")
        require_first(scoped_queries[1:2], "ndnd", short_phrase)
        require_first(scoped_queries[2:3], "ndnd", correct_gender)
        require_first(scoped_queries[-1:], "ndnd", short_phrase)

        rows = run("abbreviated_scope_undo", [learn("ndnd", short_phrase, ",BackSpace"),
            query("ndnd", "clear")])
        require_first(typed(rows, "ndnd")[-1:], "ndnd", correct_gender)
        rows = run("abbreviated_scope_writes_disabled", [
            query("ndnd", "writes=off,pick=" + short_phrase), query("ndnd", "clear")])
        require_first(typed(rows, "ndnd")[-1:], "ndnd", correct_gender)

        home_phrase = "\u6211\u521a\u56de\u5bb6"
        rows = run("mixed_spelling_keeps_full_spelling_correction", [
            learn("woganghuijia", home_phrase), query("woganghj", "clear")])
        require_first(typed(rows, "woganghj"), "woganghj", home_phrase)
        mixed_phrase = "\u5c31\u4e0d\u548c\u6211\u73a9\u4e86"
        rows = run("mixed_spelling_segmented_correction", [
            query("jiubuhewowl", "pick=\u5c31\u4e0d,pick=\u548c,pick=\u6211\u73a9\u4e86"),
            query("jiubuhewowl", "clear")])
        require_first(typed(rows, "jiubuhewowl")[-1:], "jiubuhewowl", mixed_phrase)

        rows = run("explicit_first_candidate", [learn("nandenandao", wrong_gender),
            query("nandenandao", "clear"), query("ndnd")], user)
        require_first(typed(rows, "nandenandao")[-1:], "nandenandao", wrong_gender)
        require_first(rows[-1:], "ndnd", correct_gender)
        assert wrong_gender in rows[-1]["candidates"], rows[-1]
        rows = run("explicit_first_restart", [query("ndnd")], user)
        require_first(rows, "ndnd", correct_gender)

        rows = run("default_confirmations_keep_explicit_count", [
            learn("rou", "\u63c9"), *[query("rou", "accept") for _ in range(20)],
            query("rou")])
        last = rows[-1]
        require_first(rows[-1:], "rou", "\u63c9")
        assert last["commit_counts"][0] == 1, last

        rows = run("default_components_do_not_create_preferences", [
            query("jiawodertaiduole", "accept"), query("jiawoder")])
        assert all(kind != "user_phrase" for row in typed(rows, "jiawoder")
                   for kind in row["types"]), rows

        rows = run("deleted_preference_stays_deleted_on_default_confirmation", [
            learn("nandenandao", wrong_gender),
            query("nandenandao", "drop=" + wrong_gender + ",clear"),
            query("nandenandao", "accept"), query("ndnd")])
        require_no_user_phrase(rows[-1:], "ndnd", wrong_gender)
        require_first(rows[-1:], "ndnd", "\u7537\u7684\u5973\u7684")

        rows = run("deleted_preference_can_be_relearned", [
            learn("nandenandao", wrong_gender),
            query("nandenandao", "drop=" + wrong_gender + ",clear"),
            learn("nandenandao", wrong_gender), query("nandenandao", "clear"),
            query("ndnd")])
        require_first(typed(rows, "nandenandao")[-1:], "nandenandao", wrong_gender)
        require_first(rows[-1:], "ndnd", correct_gender)

        for schema, input_text, phrase in (("double_pinyin_flypy", "vsgo", "\u4e2d\u56fd"),
                                           ("t9_pinyin", "64426", "\u4f60\u597d")):
            rows = run(schema + "_default_then_explicit", [
                query(input_text, "accept", schema), query(input_text, "clear", schema),
                query(input_text, "pick=" + phrase, schema), query(input_text, "none", schema)])
            require_no_user_phrase(rows[2:3], input_text, phrase)
            require_first(rows[-1:], input_text, phrase)
            assert rows[-1]["types"][0] == "user_phrase", rows[-1]
            assert rows[-1]["commit_counts"][0] == 1, rows[-1]

    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
    print(f"PASS {len(results)} native learning scenarios")


if __name__ == "__main__":
    main()

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from apply_edge_syllable_aliases import AliasError, main


class ApplyEdgeSyllableAliasesTest(unittest.TestCase):
    def test_projects_all_edge_syllables_and_preserves_canonical_codes(self) -> None:
        with TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            dictionary = root / "pinyin.dict.yaml"
            aliases = root / "aliases.tsv"
            priorities = root / "priorities.tsv"
            report = root / "report.json"
            dictionary.write_text(
                "# Rime dictionary\n---\nname: pinyin\nversion: \"test\"\n...\n"
                "嗯\tn\t10\n嗯\tng\t9\n嗯嗯\tng ng\t3\n呣\tm\t2\n恩\ten\t20\n",
                encoding="utf-8",
            )
            aliases.write_text("m\tmu\nn\ten\teng\nng\ten\teng\nhm\then\nhng\theng\n", encoding="utf-8")
            priorities.write_text("嗯\ten\t100\n嗯\teng\t80\n嗯嗯\ten en\t70\n嗯啊\ten a\t60\n", encoding="utf-8")
            report.write_text("{}\n", encoding="utf-8")

            summary = main([
                str(dictionary),
                "--aliases", str(aliases),
                "--priorities", str(priorities),
                "--report", str(report),
            ])

            entries = {
                tuple(line.split("\t")[:2]): line.split("\t")[2]
                for line in dictionary.read_text(encoding="utf-8").splitlines()
                if "\t" in line
            }
            self.assertEqual("10", entries[("嗯", "n")])
            self.assertEqual("9", entries[("嗯", "ng")])
            self.assertEqual("100", entries[("嗯", "en")])
            self.assertEqual("80", entries[("嗯", "eng")])
            self.assertEqual("70", entries[("嗯嗯", "en en")])
            self.assertEqual("3", entries[("嗯嗯", "eng eng")])
            self.assertEqual("2", entries[("呣", "mu")])
            self.assertEqual("60", entries[("嗯啊", "en a")])
            self.assertNotIn(("恩", "eng"), entries)
            self.assertEqual(6, summary["added_entries"])

            serialized = dictionary.read_bytes()
            updated_report = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(hashlib.sha256(serialized).hexdigest(), updated_report["output_sha256"])
            self.assertEqual(summary, updated_report["edge_syllable_aliases"])

    def test_priority_is_applied_once_when_generated_key_already_exists(self) -> None:
        with TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            dictionary = root / "pinyin.dict.yaml"
            aliases = root / "aliases.tsv"
            priorities = root / "priorities.tsv"
            dictionary.write_text(
                "# Rime dictionary\n---\nname: pinyin\nversion: \"test\"\n...\n"
                "嗯\ten\t90\n嗯\tn\t50\n",
                encoding="utf-8",
            )
            aliases.write_text("n\ten\n", encoding="utf-8")
            priorities.write_text("嗯\ten\t100\n", encoding="utf-8")

            summary = main([str(dictionary), "--aliases", str(aliases), "--priorities", str(priorities)])

            self.assertIn("嗯\ten\t100\n", dictionary.read_text(encoding="utf-8"))
            self.assertEqual(1, summary["updated_entries"])

    def test_generated_weight_can_exceed_priority_for_existing_key(self) -> None:
        with TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            dictionary = root / "pinyin.dict.yaml"
            aliases = root / "aliases.tsv"
            priorities = root / "priorities.tsv"
            dictionary.write_text(
                "# Rime dictionary\n---\nname: pinyin\nversion: \"test\"\n...\n"
                "嗯\ten\t90\n嗯\tn\t200\n",
                encoding="utf-8",
            )
            aliases.write_text("n\ten\n", encoding="utf-8")
            priorities.write_text("嗯\ten\t150\n", encoding="utf-8")

            summary = main([str(dictionary), "--aliases", str(aliases), "--priorities", str(priorities)])

            self.assertIn("嗯\ten\t200\n", dictionary.read_text(encoding="utf-8"))
            self.assertEqual(1, summary["updated_entries"])

    def test_absolute_priority_materializes_relative_generated_weight(self) -> None:
        with TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            dictionary = root / "pinyin.dict.yaml"
            aliases = root / "aliases.tsv"
            priorities = root / "priorities.tsv"
            dictionary.write_text(
                "# Rime dictionary\n---\nname: pinyin\nversion: \"test\"\n...\n"
                "嗯\tn\t99.93%\n",
                encoding="utf-8",
            )
            aliases.write_text("n\ten\n", encoding="utf-8")
            priorities.write_text("嗯\ten\t100\n", encoding="utf-8")

            summary = main([str(dictionary), "--aliases", str(aliases), "--priorities", str(priorities)])

            self.assertIn("嗯\ten\t100\n", dictionary.read_text(encoding="utf-8"))
            self.assertEqual(1, summary["added_entries"])

    def test_relative_generated_weight_without_priority_fails(self) -> None:
        with TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            dictionary = root / "pinyin.dict.yaml"
            aliases = root / "aliases.tsv"
            priorities = root / "priorities.tsv"
            dictionary.write_text(
                "# Rime dictionary\n---\nname: pinyin\nversion: \"test\"\n...\n"
                "呣\tm\t99.93%\n",
                encoding="utf-8",
            )
            aliases.write_text("m\tmu\n", encoding="utf-8")
            priorities.write_text("", encoding="utf-8")

            with self.assertRaisesRegex(AliasError, "relative generated alias weight requires explicit priority"):
                main([str(dictionary), "--aliases", str(aliases), "--priorities", str(priorities)])


if __name__ == "__main__":
    unittest.main()

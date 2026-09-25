#!/usr/bin/env python3
"""One focused generation check: deterministic selection and fail-closed output."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class PredictionFilterTest(unittest.TestCase):
    def test_selection_and_invalid_input_preserves_output(self):
        script = Path(__file__).with_name("filter_prediction_data.py")
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "prediction.tsv"
            command = [sys.executable, str(script), "--output", str(output)]
            data = "电脑\t维修\t12000\n电脑\t维修\t12001\n上海\t浦东\t12000\n你好\t不好\t99999\n"
            subprocess.run(command, input=data, text=True, check=True, capture_output=True)
            expected = "上海\t浦东\t12000\n电脑\t维修\t12001\n"
            self.assertEqual(expected, output.read_text())
            for invalid in ("broken\n", "电脑\t维修\tbad\n", "电脑\t维修\t0\n", ""):
                result = subprocess.run(command, input=invalid, text=True, capture_output=True)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(expected, output.read_text())


if __name__ == "__main__":
    unittest.main()

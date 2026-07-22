import os
from pathlib import Path
import tempfile
import unittest

from experiments.scripts.build_matrices import build_all
from experiments.scripts.common.config import load_design


class MatrixCountTest(unittest.TestCase):
    def test_full_counts_are_exact_and_host_aware(self):
        design = load_design(Path("experiments/configs/paper_q1.yaml"))
        with tempfile.TemporaryDirectory() as directory:
            report = build_all(design, Path(directory))
        threads = sum(value <= (os.cpu_count() or 1) for value in (1, 2, 4, 8, 16, 32))
        expected = {
            "E00": 0, "E01": 6, "E02": 640, "E03": 8400, "E04": 0,
            "E05": 6000, "E06": 6000, "E07": 4800, "E08": 2400,
            "E09": 2040, "E10": 7200, "E11": 4 * 100 * 2 * threads * 3,
            "E12": 3600, "E13": 0,
        }
        self.assertEqual(expected, report["study_counts"])
        self.assertEqual(sum(expected.values()), report["total_jobs"])

    def test_repeat_plan_is_byte_stable(self):
        design = load_design(Path("experiments/configs/paper_smoke.yaml"))
        with tempfile.TemporaryDirectory() as left, tempfile.TemporaryDirectory() as right:
            build_all(design, Path(left))
            build_all(design, Path(right))
            self.assertEqual(
                (Path(left) / "e01.jsonl").read_bytes(),
                (Path(right) / "e01.jsonl").read_bytes(),
            )


if __name__ == "__main__":
    unittest.main()

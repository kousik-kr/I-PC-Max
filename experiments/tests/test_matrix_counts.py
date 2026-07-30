from pathlib import Path
import json
import tempfile
import unittest

from experiments.scripts.build_matrices import build_all
from experiments.scripts.common.config import load_design
from experiments.scripts.validate_results import validate_planned_cells


class MatrixCountTest(unittest.TestCase):
    def test_full_counts_are_exact_and_host_aware(self):
        design = load_design(Path("experiments/configs/paper_q1.yaml"))
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            report = build_all(design, output)
            planned = [
                json.loads(line)
                for path in sorted(output.glob("e*.jsonl"))
                for line in path.read_text(encoding="utf-8").splitlines()
                if line
            ]
            independent = validate_planned_cells(planned, design)
        threads = 6
        expected = {
            "E00": 0, "E01": 6, "E02": 1440, "E03": 7440, "E04": 0,
            "E05": 6000, "E06": 6000, "E07": 4800, "E08": 2400,
            "E09": 2400, "E10": 12000, "E11": 4 * 100 * 2 * threads * 3,
            "E12": 3600, "E13": 0,
        }
        self.assertEqual(expected, report["study_counts"])
        self.assertEqual(sum(expected.values()), report["total_jobs"])
        self.assertEqual(
            report["total_jobs"],
            report["canonical_job_ledger_rows"],
        )
        self.assertEqual(
            64, len(report["canonical_job_ledger_sha256"])
        )
        self.assertTrue(independent["passed"])
        self.assertEqual(sum(expected.values()), independent["expected_formula_cells"])
        self.assertEqual(0, independent["duplicate_planned_cells"])
        self.assertEqual(0, independent["missing_formula_cells"])
        self.assertEqual(0, independent["unexpected_formula_cells"])

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

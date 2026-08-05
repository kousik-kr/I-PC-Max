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
                for path in sorted(output.glob("t*.jsonl"))
                for line in path.read_text(encoding="utf-8").splitlines()
                if line
            ]
            independent = validate_planned_cells(planned, design)
        expected = {
            "T01": 80,
            "T02": 320,
            "T03": 135000,
            "T04": 3360,
            "T05": 360,
            "T06": 0,
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

    def test_five_second_t03_has_matched_135000_record_design(self):
        design = load_design(Path(
            "experiments/configs/paper_q1_server_24c_250g_5s.yaml"
        ))
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            report = build_all(design, output)
            rows = [
                json.loads(line)
                for line in (output / "t03.jsonl").read_text(
                    encoding="utf-8"
                ).splitlines()
                if line
            ]
        counts = {
            algorithm: sum(
                row["algorithm_id"] == algorithm for row in rows
            )
            for algorithm in ("pace-b", "iscope", "allfp")
        }
        self.assertEqual(135000, report["total_jobs"])
        self.assertEqual(
            {"pace-b": 45000, "iscope": 45000, "allfp": 45000},
            counts,
        )
        self.assertTrue(report["disk_budget_passed"])
        self.assertNotIn("pace-x", {row["algorithm_id"] for row in rows})

    def test_scalability_plan_has_exact_small_budget_guard(self):
        design = load_design(
            Path("experiments/configs/pace_ny_scalability_theta.yaml")
        )
        with tempfile.TemporaryDirectory() as directory:
            report = build_all(design, Path(directory))
            planned = [
                json.loads(line)
                for line in (Path(directory) / "e14.jsonl").read_text().splitlines()
                if line
            ]
        self.assertEqual(28, report["total_jobs"])
        self.assertTrue(report["disk_budget_passed"])
        exact = [job for job in planned if job["algorithm_id"] == "pace-x"]
        self.assertEqual(4, len(exact))
        self.assertTrue(all(job["axis"]["budget_overhead"] <= 0.10 for job in exact))
        bounded = [job for job in planned if job["algorithm_id"] == "pace-b"]
        self.assertEqual({1, 2, 3}, {job["axis"]["theta"] for job in bounded})


if __name__ == "__main__":
    unittest.main()

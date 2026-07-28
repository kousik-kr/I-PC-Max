from pathlib import Path
import unittest

from experiments.scripts.common.config import load_design
from experiments.scripts.generate_query_sets import (
    derive_requirements,
    expected_instance_counts,
    inspect_generation_assets,
    plan,
)


class PaperQueryGenerationPipelineTest(unittest.TestCase):
    def setUp(self):
        self.design = load_design(
            Path("experiments/configs/paper_q1_server_24c_250g.yaml")
        )
        self.requirements = derive_requirements(self.design)

    def test_requirements_are_derived_from_the_paper_matrix(self):
        self.assertEqual(
            {"pilot": 20, "warmup": 10, "evaluation": 100},
            self.requirements["split_pairs"],
        )
        self.assertEqual([510, 1110], self.requirements["centers"])
        self.assertEqual(
            [120, 180, 240, 300, 360],
            self.requirements["window_minutes"],
        )
        self.assertEqual(
            [0.10, 0.20, 0.30, 0.40, 0.50],
            self.requirements["budget_overheads"],
        )

    def test_ny_and_cal_require_the_declared_graph_variants(self):
        self.assertEqual(["NY", "FLA", "CAL", "USA"],
                         self.design["datasets"])
        self.assertEqual([], self.requirements["variants"]["FLA"])
        self.assertEqual([], self.requirements["variants"]["USA"])
        suffixes = {
            variant["suffix"]
            for variant in self.requirements["variants"]["NY"]
        }
        self.assertEqual(
            {
                "-SD005",
                "-SD010",
                "-SD020",
                "-SD040",
                "-GS42",
                "-GS43",
                "-GS44",
            },
            suffixes,
        )
        self.assertEqual(
            {"-GS42", "-GS43", "-GS44"},
            {variant["suffix"] for variant in self.requirements["variants"]["CAL"]},
        )

    def test_independent_instance_arithmetic_matches_base_pairs(self):
        ny = expected_instance_counts(
            self.design, self.requirements, "NY"
        )
        fla = expected_instance_counts(
            self.design, self.requirements, "FLA"
        )
        cal = expected_instance_counts(
            self.design, self.requirements, "CAL"
        )

        self.assertEqual(40, ny["pilot"])
        self.assertEqual(20, ny["warmup"])
        self.assertEqual(1800, ny["evaluation_base"])
        self.assertEqual(1100, ny["evaluation_variants"])
        self.assertEqual(2960, ny["combined"])
        self.assertEqual(1860, fla["combined"])
        self.assertEqual(2160, cal["combined"])

    def test_plan_only_derives_without_sampling_or_writing(self):
        report = plan(
            self.design,
            list(self.design["datasets"]),
            self.requirements,
        )

        self.assertTrue(report["passed"])
        self.assertEqual("plan-only", report["mode"])
        self.assertEqual(
            {"pilot": 20, "warmup": 10, "evaluation": 100},
            report["datasets"][0]["base_pairs"],
        )

    def test_missing_cal_seed_assets_are_reported_before_graph_loading(self):
        report = inspect_generation_assets(
            self.design,
            self.requirements,
            list(self.design["datasets"]),
        )
        expected_missing = [
            seed
            for seed in (43, 44)
            if not Path(f"data/input/CAL/variants/seed-{seed}/manifest.json").is_file()
        ]
        self.assertEqual(not expected_missing, report["passed"])
        for seed in expected_missing:
            self.assertTrue(any(
                message.startswith("CAL:") and f"seed-{seed}" in message
                for message in report["errors"]
            ))


if __name__ == "__main__":
    unittest.main()

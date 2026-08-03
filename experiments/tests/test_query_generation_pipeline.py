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
        self.assertEqual(["NY", "FLA", "CAL", "OL", "NY-EXACT"],
                         self.design["datasets"])
        self.assertEqual([], self.requirements["variants"]["FLA"])
        self.assertEqual([], self.requirements["variants"]["OL"])
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
        self.assertEqual([], self.requirements["variants"]["CAL"])

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
        self.assertEqual(5000, ny["evaluation_base"])
        self.assertEqual(280, ny["evaluation_variants"])
        self.assertEqual(5340, ny["combined"])
        self.assertEqual(5060, fla["combined"])
        self.assertEqual(5060, cal["combined"])

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

    def test_supplied_ol_and_ny_exact_assets_pass_preflight(self):
        report = inspect_generation_assets(
            self.design,
            self.requirements,
            list(self.design["datasets"]),
        )
        self.assertTrue(report["passed"])
        self.assertEqual([], report["errors"])


if __name__ == "__main__":
    unittest.main()

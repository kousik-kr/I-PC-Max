from pathlib import Path
import unittest

from experiments.scripts.common.config import load_design


class ConfigContractTest(unittest.TestCase):
    def test_paper_design_has_two_track_datasets_and_all_studies(self):
        design = load_design(Path("experiments/configs/paper_q1.yaml"))
        self.assertEqual(["NY", "FLA", "CAL", "OL", "NY-EXACT"], design["datasets"])
        self.assertNotIn("USA", design["datasets"])
        self.assertEqual([f"T0{index}" for index in range(1, 7)], [row["study_id"] for row in design["study_definitions"]])
        self.assertEqual(2, design["pace_b_defaults"]["theta"])
        self.assertEqual(4, design["pace_b_defaults"]["pivot_limit_l"])
        self.assertEqual(4, design["pace_b_defaults"]["connector_limit_kc"])
        self.assertEqual(2, design["pace_b_defaults"]["frontier_limit_kf"])
        self.assertEqual({"T01", "T02"}, set(design["exact_algorithm_guard"]["allowed_studies"]))

    def test_smoke_design_is_dependency_free_json_compatible_yaml(self):
        design = load_design(Path("experiments/configs/paper_smoke.yaml"))
        self.assertTrue(design["smoke"])
        self.assertEqual([], design["datasets"])

    def test_scalability_profile_is_opt_in_and_theta_sweep_is_explicit(self):
        design = load_design(
            Path("experiments/configs/pace_ny_scalability_theta.yaml")
        )
        self.assertEqual("scalability_pilot", design["profile"])
        self.assertEqual(["NY"], design["datasets"])
        study = design["study_definitions"][0]
        exact = [
            axis
            for algorithm in study["algorithms"]
            if algorithm["id"] == "pace-x"
            for axis in algorithm["axes"]
        ]
        self.assertEqual([{"budget_overhead": 0.10}], exact)
        self.assertEqual(
            {1, 2, 3},
            {
                axis["theta"]
                for algorithm in study["algorithms"]
                if algorithm["id"] == "pace-b"
                for axis in algorithm["axes"]
            },
        )


if __name__ == "__main__":
    unittest.main()

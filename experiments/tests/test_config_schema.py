from pathlib import Path
from collections import Counter
from tempfile import TemporaryDirectory
import json
import unittest

from experiments.scripts.build_matrices import build_all
from experiments.scripts.common.config import algorithm_timeout_seconds, load_design
from experiments.scripts.validate_results import validate_planned_cells
from experiments.scripts.run_all import _preflight_resources_compatible


class ConfigContractTest(unittest.TestCase):
    def test_timeout_policy_rejects_missing_null_and_zero_but_accepts_explicit_unlimited(self):
        self.assertIsNone(algorithm_timeout_seconds(
            {"resources": {"timeout_seconds": "unlimited"}}, "pace-b"))
        self.assertEqual(5, algorithm_timeout_seconds(
            {"resources": {
                "timeout_seconds": "unlimited",
                "algorithm_timeout_seconds": {"iscope": 5},
            }}, "iscope"))
        for resources in (
            {}, {"timeout_seconds": None}, {"timeout_seconds": 0},
        ):
            with self.assertRaises(ValueError):
                algorithm_timeout_seconds({"resources": resources}, "pace-b")

    def test_compact_family_uses_frozen_query_records_and_revised_timeout_policy(self):
        design = load_design(
            Path("experiments/configs/icde2027_compact_v1/compact.yaml")
        )
        self.assertEqual("unlimited", design["resources"]["timeout_seconds"])
        self.assertEqual({
            "pace-b": "unlimited", "iscope": 5,
            "thop-partitioned": "unlimited", "pace-x": "unlimited",
            "sicsp-pref": "unlimited",
        }, design["resources"]["algorithm_timeout_seconds"])
        with TemporaryDirectory() as temporary:
            report = build_all(design, Path(temporary))
            self.assertEqual({"C01": 900, "C02": 80}, report["study_counts"])
            jobs = []
            for path in Path(temporary).glob("*.jsonl"):
                if path.name == "canonical_job_ledger.jsonl":
                    continue
                jobs.extend(
                    json.loads(line)
                    for line in path.read_text(encoding="utf-8").splitlines()
                    if line.strip()
                )
        historical = [job for job in jobs if job["study_id"] == "C01"]
        for dataset in ("NY", "FLA", "CAL"):
            rows = [job for job in historical if job["dataset_id"] == dataset]
            self.assertEqual(300, len(rows))
            self.assertEqual(50, len({job["query_id"] for job in rows}))
            self.assertEqual(
                {"pace-b": 100, "iscope": 100, "thop-partitioned": 100},
                dict(Counter(job["algorithm_id"] for job in rows)),
            )
        self.assertFalse(any(job["algorithm_id"] == "sicsp-pref" for job in historical))
        self.assertTrue(validate_planned_cells(jobs, design)["passed"])
        self.assertEqual(0, validate_planned_cells(jobs, design)["manifest_identity_mismatches"])

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

    def test_mixed_timeout_profile_is_t03_only_and_excludes_exact_algorithms(self):
        design = load_design(Path(
            "experiments/configs/paper_q1_server_24c_250g_5s.yaml"
        ))
        self.assertEqual("scalability_5s", design["profile"])
        self.assertEqual(["NY", "FLA", "CAL"], design["datasets"])
        self.assertEqual(
            ["T03"],
            [row["study_id"] for row in design["study_definitions"]],
        )
        self.assertEqual(
            ["pace-b", "iscope", "allfp"],
            [
                row["id"]
                for row in design["study_definitions"][0]["algorithms"]
            ],
        )
        self.assertEqual(10, design["resources"]["timeout_seconds"])
        self.assertEqual(
            {"pace-b": 10, "iscope": 5, "allfp": 10},
            design["resources"]["algorithm_timeout_seconds"],
        )
        self.assertEqual(
            "TIMEOUT_ONLY_EXCLUDE_FROM_COMPARISON",
            design["resources"]["timeout_result_policy"]["pace-b"],
        )
        self.assertEqual(
            "ANYTIME_PROFILE_WITHIN_CAP",
            design["resources"]["timeout_result_policy"]["iscope"],
        )
        allfp = design["study_definitions"][0]["algorithms"][2]
        self.assertEqual(24, allfp["parameters"]["threads"])
        self.assertTrue(design["implementation_gates"][
            "allfp_budget_independent_search_reuse"
        ])
        self.assertNotEqual("target/pace-bench.jar", design["paths"]["jar"])

    def test_deep_data_preflight_can_cross_only_watchdog_duration_change(self):
        evidence = {
            "timeout_seconds": 10,
            "max_concurrent": 1,
            "memory_limit_mb": 256000,
        }
        requested = {
            "timeout_seconds": 10,
            "algorithm_timeout_seconds": {
                "pace-b": 10,
                "iscope": 5,
                "allfp": 10,
            },
            "timeout_result_policy": {
                "pace-b": "TIMEOUT_ONLY_EXCLUDE_FROM_COMPARISON",
                "iscope": "ANYTIME_PROFILE_WITHIN_CAP",
                "allfp": "TIMEOUT_ONLY_EXCLUDE_FROM_COMPARISON",
            },
            "preprocessing_timeout_seconds": 1800,
            "max_concurrent": 1,
            "memory_limit_mb": 256000,
        }
        self.assertTrue(
            _preflight_resources_compatible(evidence, requested)
        )
        requested["memory_limit_mb"] = 128000
        self.assertFalse(
            _preflight_resources_compatible(evidence, requested)
        )


if __name__ == "__main__":
    unittest.main()

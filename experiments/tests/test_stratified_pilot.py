import unittest

from experiments.scripts.run_stratified_paper_pilot import (
    _execution_metrics,
    _inclusive_iqr,
    _kaplan_meier_median,
)


class StratifiedPilotStatisticsTest(unittest.TestCase):
    def test_inclusive_iqr_uses_completed_observations_only(self) -> None:
        self.assertEqual(
            [168.0, 254.0],
            _inclusive_iqr([99.0, 237.0, 271.0]),
        )

    def test_kaplan_meier_median_is_not_invented_from_censor_times(
        self,
    ) -> None:
        observations = [
            (99.0, True),
            (237.0, True),
            (271.0, True),
        ] + [(300.0, False)] * 13
        self.assertIsNone(
            _kaplan_meier_median(observations)
        )

    def test_execution_metrics_exposes_required_disjoint_phases(
        self,
    ) -> None:
        record = {
            "timing_ns": {
                "preprocessing_total": 2_000_000_000,
                "query_total": 3_000_000_000,
                "corridor_construction": 100_000_000,
                "forward_backward_labeling": 200_000_000,
                "top_l_anchor_selection": 300_000_000,
                "pivot_order_exploration": 400_000_000,
                "connector_generation": 500_000_000,
                "candidate_assembly": 600_000_000,
                "canonical_path_replay_stitching": 700_000_000,
                "profile_merge": 800_000_000,
                "envelope_extraction": 900_000_000,
            },
            "memory_bytes": {"peak_rss": 123},
            "counters": {
                "requested_workers": 24,
                "observed_workers": 2,
                "canonical_replay_cache_hits": 3,
                "canonical_replay_cache_misses": 1,
                "path_edge_count_mean": 4.5,
                "output_checksum": "abc",
            },
        }
        metrics = _execution_metrics(record, 5.5)
        self.assertEqual(2.0, metrics["dataset_startup_seconds"])
        self.assertEqual(
            0.2,
            metrics["phase_runtime_seconds"][
                "forward_backward_labeling"
            ],
        )
        self.assertEqual(5.5, metrics["process_end_to_end_seconds"])
        self.assertTrue(metrics["active_worker_overlap"])
        self.assertEqual(0.75, metrics["replay_cache_hit_rate"])
        self.assertEqual(
            4.5, metrics["path_edge_count"]["mean"])
        self.assertEqual("abc", metrics["output_checksum"])


if __name__ == "__main__":
    unittest.main()

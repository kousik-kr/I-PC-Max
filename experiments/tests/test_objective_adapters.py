from pathlib import Path
import unittest

from experiments.scripts.execute_matrix import _command


class ObjectiveAdapterCommandTest(unittest.TestCase):
    def design(self):
        return {
            "resources": {"memory_limit_mb": 1024, "timeout_seconds": 30},
            "paths": {"jar": "target/pace-bench.jar"},
            "seeds": {"schedule": 42},
            "protocol": {"warmup_passes": 1},
        }

    def test_native_fixture_keeps_weight_bound_separate_from_workload_budget(self):
        job = {
            "job_id": "native-figure1", "study_id": "NATIVE", "algorithm_id": "sicsp-native",
            "dataset_id": "sicsp-figure1", "algorithm_parameters": {
                "sicsp_weights": "experiments/fixtures/sicsp_native_figure1/weights.json",
                "sicsp_weight_bound": 80,
            },
        }
        command = _command(job, self.design(), Path("/tmp/run"), Path("queries.jsonl"), Path("raw.jsonl"))
        self.assertEqual("sicsp-figure1", command[command.index("--dataset") + 1])
        self.assertEqual("80", command[command.index("--sicsp-weight-bound") + 1])
        self.assertIn("--sicsp-weights", command)
        self.assertNotIn("--budget", command)

    def test_thop_flags_forward_exact_index_lifecycle_without_preference_reference(self):
        job = {
            "job_id": "thop", "study_id": "FASTEST", "algorithm_id": "thop-partitioned",
            "dataset_id": "demo", "algorithm_parameters": {
                "thop_index_dir": "/tmp/thop-index", "thop_partitions": "partitions.json",
                "thop_domain_start": 0, "thop_domain_end": 1440, "thop_lifecycle": "load",
            },
        }
        command = _command(job, self.design(), Path("/tmp/run"), Path("queries.jsonl"), Path("raw.jsonl"))
        self.assertEqual("load", command[command.index("--thop-lifecycle") + 1])
        self.assertEqual("1440", command[command.index("--thop-domain-end") + 1])
        self.assertNotIn("--reference-algorithm", command)


if __name__ == "__main__":
    unittest.main()

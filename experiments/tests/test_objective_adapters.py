from pathlib import Path
import json
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from experiments.scripts.execute_matrix import _command, _job_timeout_seconds, execute_one


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

    def test_explicit_unlimited_command_and_runner_have_no_query_watchdog(self):
        with TemporaryDirectory(dir=".") as temporary:
            design = self.design()
            design["resources"].update({
                "timeout_seconds": "unlimited",
                "algorithm_timeout_seconds": {
                    "pace-b": "unlimited", "thop-partitioned": "unlimited"
                },
                "preprocessing_timeout_seconds": 1800,
            })
            design["paths"]["results_root"] = temporary
            design["protocol"].update({
                "warmup_passes": 0,
                "metric_definition_version": "synthetic-runner-test-v1",
            })
            design["pace_b_defaults"] = {
                "theta": 2, "pivot_limit_l": 1,
                "connector_limit_kc": 1, "frontier_limit_kf": 1,
                "connector_expansion_cap_mc": 1000,
                "breakpoint_cap_mb": 1000, "query_work_cap_mq": 10000,
                "threads": 1,
            }
            design["resolved_pace_b_required"] = False

            def fake_run(command, **kwargs):
                if "-jar" not in command:
                    return SimpleNamespace(returncode=0, stdout="", stderr="")
                fake_run.last_command = command
                fake_run.last_timeout = kwargs.get("timeout")
                output = Path(command[command.index("--output-jsonl") + 1])
                output.write_text(json.dumps({
                    "status": {"status_code": "COMPLETED", "completed": True},
                    "output": {},
                }) + "\n", encoding="utf-8")
                return SimpleNamespace(returncode=0, stdout="", stderr="")

            with patch("experiments.scripts.execute_matrix.subprocess.run", fake_run):
                for algorithm_id, parameters in (
                    ("pace-b", {"theta": 2, "resolved_pace_b": False}),
                    ("thop-partitioned", {
                        "thop_index_dir": "data/indices/demo",
                        "thop_partitions": "data/indices/demo/partitions.json",
                        "thop_domain_start": 0, "thop_domain_end": 1440,
                        "thop_lifecycle": "load",
                    }),
                ):
                    job = {
                        "job_id": f"synthetic-no-cap-{algorithm_id}",
                        "input_hash": f"hash-{algorithm_id}",
                        "study_id": "C01", "algorithm_id": algorithm_id,
                        "dataset_id": "demo", "query_id": "tiny-1-4-wide",
                        "trial_id": 0, "manifest": "experiments/manifests/tiny.jsonl",
                        "algorithm_parameters": parameters,
                    }
                    self.assertIsNone(_job_timeout_seconds(job, design))
                    result = execute_one(job, design, "synthetic", "local")
                    self.assertEqual("SUCCESS", result["completion_status"])
                    # The command is written to the mock-produced record's call.
                    # `fake_run` is invoked once by Java after the toolchain probe.
                    # Retain the last command to assert policy and finite memory.
                    command = fake_run.last_command
                    self.assertEqual(
                        "unlimited", command[command.index("--timeout-seconds") + 1]
                    )
                    self.assertEqual(
                        "1024", command[command.index("--memory-limit-mb") + 1]
                    )
                    self.assertIsNone(fake_run.last_timeout)


if __name__ == "__main__":
    unittest.main()

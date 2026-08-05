from __future__ import annotations

import collections
import json
from pathlib import Path
import tempfile
import unittest

from experiments.scripts.audit_t03_5s_pilot import audit
from experiments.scripts.collect_results import read_reused_records
from experiments.scripts.common.atomic_io import write_jsonl
from experiments.scripts.common.config import load_design
from experiments.scripts.execute_matrix import (
    _uniform_trial_groups,
    dry_run_commands,
)
from experiments.scripts.prepare_t03_5s_pilot import select_pilot
from experiments.scripts.t03_5s_progress import progress


class FiveSecondResumeTest(unittest.TestCase):
    def test_stratified_pilot_is_deterministic_matched_and_complete(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jobs: list[dict] = []
            algorithms = ("pace-b", "iscope", "allfp")
            strata = {
                "easy": {"window_minutes": 120, "budget_overhead": 0.10},
                "hard": {"window_minutes": 360, "budget_overhead": 0.50},
            }
            for dataset in ("NY", "FLA", "CAL"):
                manifest = root / f"{dataset}.jsonl"
                queries: list[dict] = []
                for distance_band in range(1, 6):
                    for difficulty, axis in strata.items():
                        query_id = f"{dataset}-{distance_band}-{difficulty}"
                        queries.append({
                            "dataset_id": dataset,
                            "query_id": query_id,
                            "distance_bin": distance_band,
                            "lower_bound_distance": distance_band * 10
                            + (difficulty == "hard"),
                        })
                        for algorithm in algorithms:
                            jobs.append({
                                "study_id": "T03",
                                "dataset_id": dataset,
                                "query_id": query_id,
                                "algorithm_id": algorithm,
                                "trial_id": 0,
                                "job_id": f"{query_id}-{algorithm}",
                                "input_hash": f"hash-{query_id}-{algorithm}",
                                "manifest": manifest.as_posix(),
                                "pair_index": distance_band * 10
                                + (difficulty == "hard"),
                                "time_center": 510,
                                "axis": axis,
                            })
                write_jsonl(manifest, queries)
            execution = root / "execution.jsonl"
            write_jsonl(execution, jobs)

            first, first_report = select_pilot(execution)
            second, second_report = select_pilot(execution)

            self.assertEqual(first, second)
            self.assertEqual(first_report, second_report)
            self.assertEqual(90, len(first))
            self.assertEqual(90, len({job["job_id"] for job in first}))
            self.assertEqual(
                {"pace-b": 30, "iscope": 30, "allfp": 30},
                dict(collections.Counter(job["algorithm_id"] for job in first)),
            )
            self.assertEqual(30, len(first_report["strata"]))
            self.assertEqual(
                {(dataset, band, difficulty)
                 for dataset in ("NY", "FLA", "CAL")
                 for band in range(1, 6)
                 for difficulty in ("easy", "hard")},
                {(row["dataset_id"], row["distance_band"], row["difficulty"])
                 for row in first_report["strata"]},
            )

            raw = root / "raw/T03"
            raw.mkdir(parents=True)
            records = []
            for job in first:
                algorithm = job["algorithm_id"]
                records.append({
                    "job_id": job["job_id"],
                    "input_hash": job["input_hash"],
                    "completion_status": "SUCCESS",
                    "java_record": {
                        "configuration": {
                            "algorithm": algorithm,
                            "timeout_seconds": 5 if algorithm == "iscope" else 10,
                        },
                        "status": {
                            "status_code": "CERTIFIED_COMPLETE",
                            "exactness_scope": "GLOBAL_CERTIFIED",
                        },
                        "timing_ns": {"query_total": 1_000_000},
                        "output": {"profile_checksum": f"profile-{job['job_id']}"},
                        "counters": {
                            "output_validation_contract": (
                                "CANONICAL_EXACT_PROFILE_AND_VERTEX_SIMPLE-v1"
                            ),
                            "output_loopless": True,
                            "output_feasible": True,
                            "preference_score_used_for_search": False,
                            "pcmax_budget_used_for_search": False,
                            "full_interval_coverage": True,
                        },
                    },
                })
            write_jsonl(raw / "pilot.jsonl", records)
            report = audit(execution, raw)
            self.assertTrue(report["passed"], report["errors"])

    def test_remaining_trials_are_partitioned_without_duplication(self) -> None:
        jobs = [
            self._job("query-a", 0),
            self._job("query-a", 2),
            self._job("query-b", 1),
            self._job("query-c", 0),
            self._job("query-c", 2),
        ]
        groups = _uniform_trial_groups(jobs)
        signatures = {
            tuple(sorted({row["trial_id"] for row in group}))
            for _, group in groups
        }
        flattened = [
            (row["query_id"], row["trial_id"])
            for _, group in groups
            for row in group
        ]

        self.assertEqual({(0, 2), (1,)}, signatures)
        self.assertEqual(len(flattened), len(set(flattened)))
        self.assertEqual(
            sorted((row["query_id"], row["trial_id"]) for row in jobs),
            sorted(flattened),
        )

    def test_reused_row_is_projected_only_into_effective_normalized_set(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "historical.jsonl"
            write_jsonl(source, [{
                "run_id": "old-run",
                "study_id": "T03",
                "job_id": "old-job",
                "input_hash": "old-input",
                "completion_status": "SUCCESS",
                "java_record": {"run": {"run_id": "source-record"}},
            }])
            run_root = root / "new-run"
            reconciliation = run_root / "plan/reconciliation"
            reconciliation.mkdir(parents=True)
            write_jsonl(reconciliation / "effective_result_index.jsonl", [{
                "result_origin": "REUSED_HISTORICAL",
                "target_job_id": "new-job",
                "target_input_hash": "new-input",
                "source_record_path": source.as_posix(),
                "source_record_id": "source-record",
            }])

            projected = read_reused_records(run_root)

            self.assertEqual(1, len(projected))
            self.assertEqual("new-run", projected[0]["run_id"])
            self.assertEqual("new-job", projected[0]["job_id"])
            self.assertEqual(
                "REUSED_HISTORICAL", projected[0]["result_origin"]
            )
            self.assertEqual("old-job", json.loads(
                source.read_text(encoding="utf-8")
            )["job_id"])

    def test_progress_is_independent_by_algorithm_and_duplicate_aware(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reconciliation = root / "plan/reconciliation"
            reconciliation.mkdir(parents=True)
            logical_jobs = [
                {"job_id": "p", "algorithm_id": "pace-b"},
                {"job_id": "i", "algorithm_id": "iscope"},
                {"job_id": "a", "algorithm_id": "allfp"},
            ]
            matrices = root / "plan/matrices"
            matrices.mkdir(parents=True)
            write_jsonl(matrices / "t03.jsonl", logical_jobs)
            write_jsonl(
                reconciliation / "execution_manifest.jsonl",
                logical_jobs[1:],
            )
            write_jsonl(reconciliation / "effective_result_index.jsonl", [{
                "target_job_id": "p",
            }])
            raw = root / "raw/T03"
            raw.mkdir(parents=True)
            write_jsonl(raw / "i.jsonl", [{
                "job_id": "i",
                "completion_status": "TIME_CAPPED_NOT_CERTIFIED",
            }])

            report = progress(root)

            self.assertEqual(2, report["completed_effective_total"])
            self.assertEqual(1, report["remaining_total"])
            self.assertEqual(3, report["planned_total"])
            self.assertEqual(2, report["execution_manifest_total"])
            self.assertEqual(
                1, report["algorithms"]["pace-b"]["reused_historical"]
            )
            self.assertEqual(
                1, report["algorithms"]["iscope"]["terminal_unique"]
            )
            self.assertEqual(1, report["algorithms"]["allfp"]["remaining"])
            self.assertTrue(report["passed_identity_audit"])

    def test_pilot_audit_accepts_mixed_timeout_policy_records(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plans = []
            records = []
            index = 0
            for dataset in ("NY", "FLA", "CAL"):
                for algorithm in ("pace-b", "iscope", "allfp"):
                    for query in range(10):
                        job_id = f"{dataset}-{algorithm}-{query}"
                        plans.append({
                            "job_id": job_id,
                            "input_hash": f"input-{index}",
                            "dataset_id": dataset,
                            "algorithm_id": algorithm,
                            "trial_id": 0,
                        })
                        counters = {
                            "output_validation_contract":
                                "CANONICAL_EXACT_PROFILE_AND_VERTEX_SIMPLE-v1",
                            "output_loopless": True,
                            "output_feasible": True,
                        }
                        if algorithm == "allfp":
                            counters.update({
                                "preference_score_used_for_search": False,
                                "pcmax_budget_used_for_search": False,
                            })
                        if algorithm in {"pace-b", "allfp"}:
                            records.append({
                                "job_id": job_id,
                                "input_hash": f"input-{index}",
                                "completion_status": "TIMEOUT",
                                "java_record": None,
                                "error": {
                                    "type": "TIMED_OUT",
                                    "message": "TIMED OUT",
                                },
                            })
                        else:
                            records.append({
                                "job_id": job_id,
                                "input_hash": f"input-{index}",
                                "completion_status": "TIME_CAPPED_NOT_CERTIFIED",
                                "java_record": {
                                    "configuration": {
                                        "algorithm": algorithm,
                                        "timeout_seconds": 5,
                                    },
                                    "status": {
                                        "status_code": "TIME_CAPPED_NOT_CERTIFIED",
                                        "exactness_scope": "NOT_CERTIFIED",
                                    },
                                    "counters": counters,
                                    "timing_ns": {"query_total": 5_000_000_000},
                                    "output": {"profile_checksum": f"profile-{index}"},
                                },
                            })
                        index += 1
            manifest = root / "pilot.jsonl"
            raw = root / "raw"
            raw.mkdir()
            write_jsonl(manifest, plans)
            write_jsonl(raw / "records.jsonl", records)

            self.assertTrue(audit(manifest, raw)["passed"])
            pace_timeout = next(
                row for row in records if "-pace-b-" in row["job_id"]
            )
            pace_timeout["java_record"] = {
                "status": {
                    "status_code": "TIMEOUT",
                    "exactness_scope": "NOT_CERTIFIED",
                },
            }
            write_jsonl(raw / "records.jsonl", records)
            self.assertFalse(audit(manifest, raw)["passed"])
            pace_timeout["java_record"] = None
            write_jsonl(raw / "records.jsonl", [*records, records[0]])
            self.assertFalse(audit(manifest, raw)["passed"])

    def test_pilot_audit_accepts_only_noncertified_empty_iscope_cap(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plans = []
            records = []
            index = 0
            for dataset in ("NY", "FLA", "CAL"):
                for algorithm in ("pace-b", "iscope", "allfp"):
                    for query in range(10):
                        job_id = f"{dataset}-{algorithm}-{query}"
                        plans.append({
                            "job_id": job_id,
                            "input_hash": f"input-{index}",
                            "dataset_id": dataset,
                            "algorithm_id": algorithm,
                            "trial_id": 0,
                        })
                        counters = {
                            "output_validation_contract":
                                "CANONICAL_EXACT_PROFILE_AND_VERTEX_SIMPLE-v1",
                            "output_loopless": True,
                            "output_feasible": True,
                        }
                        if algorithm == "allfp":
                            counters.update({
                                "preference_score_used_for_search": False,
                                "pcmax_budget_used_for_search": False,
                            })
                        wrapper_status = (
                            "TIME_CAPPED_NOT_CERTIFIED"
                            if algorithm == "iscope"
                            else "SUCCESS"
                        )
                        status_code = (
                            "TIME_CAPPED_NOT_CERTIFIED"
                            if algorithm == "iscope"
                            else "COMPLETED"
                        )
                        exactness = (
                            "NOT_CERTIFIED"
                            if algorithm == "iscope"
                            else "RETAINED_FRONTIER"
                        )
                        if algorithm == "iscope" and query == 0:
                            counters.update({
                                "output_feasible": False,
                                "departure_interval_coverage": 0.0,
                            })
                        records.append({
                            "job_id": job_id,
                            "input_hash": f"input-{index}",
                            "completion_status": wrapper_status,
                            "java_record": {
                                "configuration": {
                                    "algorithm": algorithm,
                                    "timeout_seconds": 5 if algorithm == "iscope" else 10,
                                },
                                "status": {
                                    "status_code": status_code,
                                    "exactness_scope": exactness,
                                },
                                "counters": counters,
                                "timing_ns": {
                                    "query_total": (
                                        5_000_000_000
                                        if algorithm == "iscope"
                                        else 1_000_000
                                    )
                                },
                                "output": {"profile_checksum": f"profile-{index}"},
                            },
                        })
                        index += 1
            manifest = root / "pilot.jsonl"
            raw = root / "raw"
            raw.mkdir()
            write_jsonl(manifest, plans)
            write_jsonl(raw / "records.jsonl", records)

            self.assertTrue(audit(manifest, raw)["passed"])
            empty = next(
                row for row in records
                if row["job_id"] == "NY-iscope-0"
            )
            empty["java_record"]["status"]["exactness_scope"] = "GLOBAL_CERTIFIED"
            write_jsonl(raw / "records.jsonl", records)
            self.assertFalse(audit(manifest, raw)["passed"])

    def test_dry_run_builds_five_second_command_without_writes(self) -> None:
        design = load_design(Path(
            "experiments/configs/paper_q1_server_24c_250g_5s.yaml"
        ))
        run_id = "unit-test-dry-run-must-not-exist"
        run_root = Path(design["paths"]["results_root"]) / run_id
        self.assertFalse(run_root.exists())

        commands = dry_run_commands([self._job("query-a", 0)], design, run_id)

        self.assertEqual(1, len(commands))
        command = commands[0]
        self.assertIn("-Xmx250g", command)
        self.assertEqual("10", command[command.index("--timeout-seconds") + 1])
        self.assertIn(
            "target/pace-bench-5s-optimized.jar", " ".join(command)
        )
        self.assertEqual("0", command[command.index("--repetition-indices") + 1])
        self.assertIn("--shared-preprocessing", command)
        self.assertNotIn("--fail-fast", command)
        self.assertFalse(run_root.exists())

    @staticmethod
    def _job(query_id: str, trial_id: int) -> dict:
        return {
            "study_id": "T03",
            "job_id": f"{query_id}-{trial_id}",
            "dataset_id": "NY",
            "query_id": query_id,
            "algorithm_id": "pace-b",
            "variant_id": "aggressive-fastest-l1",
            "algorithm_parameters": {},
            "axis": {},
            "manifest": "fixture.jsonl",
            "reference_algorithm": None,
            "trial_id": trial_id,
        }


if __name__ == "__main__":
    unittest.main()

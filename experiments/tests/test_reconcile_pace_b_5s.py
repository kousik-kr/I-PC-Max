from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from experiments.scripts.build_matrices import expand_study
from experiments.scripts.common.atomic_io import write_jsonl
from experiments.scripts.common.config import load_design
from experiments.scripts.common.hashing import sha256_file
from experiments.scripts.reconcile_pace_b_5s import (
    _expected_pace_configuration,
    _validate_existing,
    _write,
    reconcile,
)


ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / "experiments/configs/paper_q1_server_24c_250g_5s.yaml"
TEST_COUNTS = {"pace-b": 1, "iscope": 0, "allfp": 0}


class PaceBFiveSecondReconciliationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.base_design = load_design(CONFIG)
        study = cls.base_design["study_definitions"][0]
        cls.job = next(
            job
            for job in expand_study(study, cls.base_design)
            if job["algorithm_id"] == "pace-b"
        )
        manifest = ROOT / cls.job["manifest"]
        cls.query = next(
            json.loads(line)
            for line in manifest.read_text(encoding="utf-8").splitlines()
            if json.loads(line)["query_id"] == cls.job["query_id"]
        )

    def test_missing_validation_evidence_fails_closed_and_unsigned_seed_matches(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            design, source, matrix = self._fixture(root)
            record = self._record("source-a", "checksum-a")
            unsigned_seed = int(self.query["query_seed"]) & ((1 << 64) - 1)
            record["java_record"]["query"]["query_seed"] = str(unsigned_seed)
            write_jsonl(source / "raw/T03/a.jsonl", [record])

            result = reconcile(
                design, matrix, [source], expected_counts=TEST_COUNTS
            )
            report = result[3]

            self.assertEqual(0, report["reusable_pace_b"])
            self.assertEqual(1, report["remaining_pace_b"])
            self.assertEqual(
                1,
                report["nonreuse_reasons"][
                    "missing_exact_output_validation_contract"
                ],
            )
            self.assertNotIn("query_mismatch", report["nonreuse_reasons"])

    def test_conflicting_duplicate_outputs_are_flagged_and_scheduled(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            design, source, matrix = self._fixture(root)
            write_jsonl(
                source / "raw/T03/a.jsonl",
                [self._record("source-a", "checksum-a", validated=True)],
            )
            write_jsonl(
                source / "raw/T03/b.jsonl",
                [self._record("source-b", "checksum-b", validated=True)],
            )

            result = reconcile(
                design, matrix, [source], expected_counts=TEST_COUNTS
            )
            report = result[3]

            self.assertEqual(0, report["reusable_pace_b"])
            self.assertEqual(1, report["remaining_pace_b"])
            self.assertEqual(1, report["conflicting_duplicate_jobs"])
            self.assertEqual(
                1,
                report["nonreuse_reasons"]["conflicting_duplicate_outputs"],
            )

    def test_generation_is_byte_identical_and_validate_only_detects_change(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            design, source, matrix = self._fixture(root)
            result = reconcile(
                design, matrix, [source], expected_counts=TEST_COUNTS
            )
            output = root / "reconciliation"
            _write(output, *result)
            first = {
                path.name: sha256_file(path)
                for path in sorted(output.iterdir())
                if path.is_file()
            }
            _write(output, *result)
            second = {
                path.name: sha256_file(path)
                for path in sorted(output.iterdir())
                if path.is_file()
            }
            self.assertEqual(first, second)
            _validate_existing(output, result)

            with (output / "execution_manifest.jsonl").open(
                "a", encoding="utf-8"
            ) as stream:
                stream.write("{}\n")
            with self.assertRaisesRegex(ValueError, "artifact mismatch"):
                _validate_existing(output, result)

    def test_allfp_logical_rows_resolve_to_one_search_per_five_budgets(self) -> None:
        study = self.base_design["study_definitions"][0]
        jobs = [
            job
            for job in expand_study(study, self.base_design)
            if job["algorithm_id"] == "allfp"
            and job["dataset_id"] == "NY"
            and "P001-C510-W120-" in job["query_id"]
            and job["trial_id"] == 0
        ]
        self.assertEqual(5, len(jobs))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source-run"
            (source / "plan/matrices").mkdir(parents=True)
            (source / "raw/T03").mkdir(parents=True)
            write_jsonl(source / "plan/matrices/t03.jsonl", jobs)
            matrix = root / "target.jsonl"
            write_jsonl(matrix, jobs)
            jar = root / "source.jar"
            jar.write_bytes(b"immutable fixture jar")
            design = copy.deepcopy(self.base_design)
            design["reconciliation"]["source_jar"] = jar.as_posix()
            design["reconciliation"]["source_jar_sha256"] = hashlib.sha256(
                jar.read_bytes()
            ).hexdigest()

            report = reconcile(
                design,
                matrix,
                [source],
                expected_counts={"pace-b": 0, "iscope": 0, "allfp": 5},
            )[3]

        self.assertEqual(1, report[
            "allfp_budget_independent_searches_planned"
        ])
        self.assertEqual(4, report[
            "allfp_budget_projection_rows_planned"
        ])
        self.assertEqual({"5": 1}, report[
            "allfp_budget_variant_group_sizes"
        ])
        self.assertTrue(report["allfp_budget_reuse_valid"])

    def _fixture(self, root: Path) -> tuple[dict, Path, Path]:
        source = root / "source-run"
        (source / "plan/matrices").mkdir(parents=True)
        (source / "raw/T03").mkdir(parents=True)
        write_jsonl(source / "plan/matrices/t03.jsonl", [self.job])
        matrix = root / "target.jsonl"
        write_jsonl(matrix, [self.job])
        jar = root / "source.jar"
        jar.write_bytes(b"immutable fixture jar")
        design = copy.deepcopy(self.base_design)
        design["reconciliation"]["source_jar"] = jar.as_posix()
        design["reconciliation"]["source_jar_sha256"] = hashlib.sha256(
            jar.read_bytes()
        ).hexdigest()
        return design, source, matrix

    def _record(
        self,
        source_record_id: str,
        checksum: str,
        validated: bool = False,
    ) -> dict:
        metadata = self.query["metadata"]
        configuration = _expected_pace_configuration(self.job, self.base_design)
        configuration.update(
            {
                "algorithm": "pace-b",
                "deterministic": True,
                "temporal_replay_contract": (
                    "DECLARED_DEPARTURE_GRID_LINEARIZED-v1"
                ),
                "timeout_seconds": 10,
            }
        )
        return {
            "schema_version": 1,
            "job_id": self.job["job_id"],
            "run_id": "historical-run",
            "completion_status": "SUCCESS",
            "java_record": {
                "run": {
                    "run_id": source_record_id,
                    "git_commit": self.base_design["reconciliation"][
                        "source_git_commit"
                    ],
                },
                "status": {"status_code": "COMPLETED", "completed": True},
                "configuration": configuration,
                "query": {
                    key: self.query.get(key)
                    for key in (
                        "query_id",
                        "source",
                        "destination",
                        "interval_start",
                        "interval_end",
                        "budget",
                        "budget_slack",
                        "distance_bin",
                        "query_seed",
                    )
                },
                "dataset": {
                    "dataset_id": self.query["dataset_id"],
                    "dataset_structure_checksum": metadata["dataset_checksum"],
                    "dataset_payload_checksum": metadata[
                        "dataset_payload_checksum"
                    ],
                    "temporal_attribute_checksum": metadata[
                        "temporal_attribute_checksum"
                    ],
                },
                "timing_ns": {"query_total": 4_000_000_000},
                "output": {
                    "feasible": True,
                    "profile_checksum": checksum,
                },
                "counters": {
                    "output_validation_contract": (
                        "CANONICAL_EXACT_PROFILE_AND_VERTEX_SIMPLE-v1"
                        if validated else None
                    ),
                    "output_feasible": True if validated else None,
                    "output_loopless": True if validated else None,
                },
            },
        }


if __name__ == "__main__":
    unittest.main()

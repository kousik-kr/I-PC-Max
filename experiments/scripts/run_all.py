#!/usr/bin/env python3
"""Single entry point for planning, running, resuming, and packaging PACE Q1."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.plots.make_all_plots import main as _unused_plot_main
from experiments.scripts.build_matrices import build_all
from experiments.scripts.collect_results import collect
from experiments.scripts.common.atomic_io import atomic_write_json, atomic_write_text, mark_stage
from experiments.scripts.common.config import filtered_design, load_design, repo_path
from experiments.scripts.common.hashing import sha256_file
from experiments.scripts.common.provenance import git_state, host_environment
from experiments.scripts.common.toolchain import environment, executable
from experiments.scripts.execute_matrix import (
    execute_batched,
    execute_one,
    read_jsonl,
)
from experiments.scripts.executors.local import run_jobs
from experiments.scripts.executors.slurm import submit, write_array_script
from experiments.scripts.generate_queries import validate_all
from experiments.scripts.package_release import package
from experiments.scripts.preflight import run_preflight
from experiments.scripts.resolve_pace_b import resolve
from experiments.scripts.summarize_results import summarize
from experiments.scripts.validate_results import validate, validate_planned_cells


STAGES = (
    "preflight", "build", "data", "queries", "plan", "smoke", "exactness", "main",
    "sensitivity", "ablation", "precomputation", "correctness", "pilot", "parallel",
    "robustness", "scalability", "collect", "validate", "summarize",
    "plot", "table", "package",
)

STAGE_STUDIES = {
    "exactness": ("T01", "T02"),
    "main": ("T03",),
    "ablation": ("T04",),
    "sensitivity": ("T05",),
    "precomputation": ("T06",),
    # Legacy aliases remain available for the opt-in scalability_pilot profile.
    "correctness": ("E01",), "pilot": ("E02",), "parallel": ("E11",),
    "robustness": ("E12",), "scalability": ("E14",),
}


def _prepare_run(
    design: dict[str, Any], run_id: str, backend: str, resume: bool
) -> tuple[Path, dict[str, Any]]:
    if not run_id or run_id in {".", ".."} or "/" in run_id or "\\" in run_id:
        raise ValueError("run ID must be one plain directory name")
    root = repo_path(design["paths"]["results_root"]) / run_id
    identity = {
        "schema_version": 1,
        "run_id": run_id,
        "config_hash": design["config_hash"],
        "git": git_state(),
        "backend": backend,
    }
    identity_path = root / "provenance" / "IMMUTABLE.json"
    if identity_path.is_file():
        existing = json.loads(identity_path.read_text(encoding="utf-8"))
        if existing != identity:
            raise ValueError("run ID already belongs to a different config, commit, dirty state, or backend")
        if not resume:
            raise ValueError("run ID already exists; pass --resume or choose a new run ID")
    elif root.exists() and any(root.iterdir()):
        raise ValueError("run directory exists without an immutable identity")
    for name in ("plan/matrices", "raw", "logs", "work", "normalized", "summaries", "tables", "figures", "provenance", "release", "markers"):
        (root / name).mkdir(parents=True, exist_ok=True)
    atomic_write_json(identity_path, identity)
    atomic_write_json(root / "provenance" / "effective_config.json", design)
    atomic_write_json(root / "provenance" / "environment.json", host_environment())
    return root, identity


def _run_command(command: list[str]) -> None:
    command = [executable(command[0]), *command[1:]]
    completed = subprocess.run(command, cwd=repo_path("."), env=environment(), check=False)
    if completed.returncode != 0:
        raise RuntimeError(f"command failed ({completed.returncode}): {' '.join(command)}")


def _execute_studies(
    design: dict[str, Any], root: Path, run_id: str, backend: str,
    studies: tuple[str, ...], max_concurrent: int,
) -> dict[str, Any]:
    selected = []
    for study in studies:
        path = root / "plan" / "matrices" / f"{study.lower()}.jsonl"
        if path.is_file():
            selected.extend(read_jsonl(path))
    if not selected:
        return {"jobs": 0, "status_counts": {}}
    if backend == "slurm":
        memory = design["resources"].get("memory_limit_mb")
        if memory is None:
            raise ValueError("memory_limit_mb is required for Slurm")
        combined = root / "plan" / "matrices" / f"{'-'.join(studies).lower()}-slurm.jsonl"
        from experiments.scripts.common.atomic_io import write_jsonl
        write_jsonl(combined, selected)
        script = write_array_script(
            root / "plan" / f"{'-'.join(studies).lower()}.sbatch", sys.executable,
            Path(design["config_path"]), run_id, combined, len(selected),
            int(design["resources"]["timeout_seconds"]), int(memory),
        )
        submission = submit(script, wait=True)
        return {"jobs": len(selected), "slurm_script": str(script), "submitted": True, "submission": submission}
    if design["protocol"].get("shared_preprocessing"):
        if max_concurrent != 1:
            raise ValueError(
                "shared preprocessing requires one serial query stream"
            )
        results = execute_batched(
            selected, design, run_id, "local"
        )
    else:
        results = run_jobs(
            selected,
            lambda job: execute_one(job, design, run_id, "local"),
            max_concurrent,
        )
    counts: dict[str, int] = {}
    for result in results:
        counts[result["completion_status"]] = counts.get(result["completion_status"], 0) + 1
    infrastructure = counts.get("INVALID_INPUT", 0) + counts.get("INTERNAL_ERROR", 0) + counts.get("INFRASTRUCTURE_BLOCKED", 0)
    if infrastructure:
        raise RuntimeError(f"{infrastructure} jobs ended in an infrastructure/input failure")
    return {"jobs": len(results), "status_counts": counts}


def _parse_stages(value: str) -> list[str]:
    requested = list(STAGES) if value == "all" else [item.strip() for item in value.split(",") if item.strip()]
    unknown = set(requested) - set(STAGES)
    if unknown:
        raise ValueError(f"unknown stages: {', '.join(sorted(unknown))}")
    return [stage for stage in STAGES if stage in requested]


def _load_reused_preflight(path: Path, design: dict[str, Any]) -> dict[str, Any]:
    """Reuse a previously completed deep preflight for the same filtered design."""
    report = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(report, dict) or not report.get("passed"):
        raise ValueError(f"reused preflight is not passed: {path}")
    # run_preflight validates the selected dataset payloads and the complete
    # implementation-gate configuration; it intentionally does not filter by
    # study.  Accept that dataset-scoped hash when run_all itself is filtered
    # to one or more studies.
    source_design = load_design(repo_path(design["config_path"]))
    preflight_design = filtered_design(
        source_design, set(design["datasets"]), None
    )
    compatible_hashes = {
        design["config_hash"],
        preflight_design["config_hash"],
    }
    if (
        report.get("config_hash") not in compatible_hashes
        and not _preflight_scope_matches(report, design)
    ):
        raise ValueError(
            "reused preflight config hash does not match the filtered design"
        )
    expected = set(design["datasets"])
    actual = {
        item.get("dataset_id")
        for item in report.get("datasets", [])
        if isinstance(item, dict)
    }
    if actual != expected:
        raise ValueError(
            "reused preflight dataset set does not match the filtered design"
        )
    report["reused_from"] = path.as_posix()
    return report


def _preflight_scope_matches(
    report: dict[str, Any], design: dict[str, Any]
) -> bool:
    """Allow study-only changes to reuse immutable data/query validation."""
    if (
        report.get("mode") != "deep"
        or not report.get("checksums_computed")
        or report.get("resources") != design.get("resources")
    ):
        return False
    integrity_rows = {
        row.get("dataset_id"): row
        for row in report.get("dataset_integrity", {}).get("datasets", [])
        if isinstance(row, dict)
    }
    query_rows = {
        row.get("dataset_id"): row
        for row in report.get("queries", {}).get("datasets", [])
        if isinstance(row, dict)
    }
    if set(integrity_rows) != set(design["datasets"]):
        return False
    if set(query_rows) != set(design["datasets"]):
        return False
    manifest_pattern = design["query_generation"]["manifest_pattern"]
    for dataset in design["datasets"]:
        definition = design["dataset_definitions"][dataset]
        integrity = integrity_rows[dataset]
        expected_path = repo_path(definition["path"]).resolve()
        if Path(str(integrity.get("path", ""))).resolve() != expected_path:
            return False
        expected_fields = {
            "nodes": definition.get("expected_nodes"),
            "directed_arcs": definition.get("expected_edges"),
            "support_end": definition.get("required_support_end"),
            "contract_id": definition.get(
                "required_conversion_contract"
            ),
        }
        if any(
            expected is not None and integrity.get(field) != expected
            for field, expected in expected_fields.items()
        ):
            return False
        query_path = repo_path(
            manifest_pattern.format(dataset=dataset)
        ).resolve()
        query = query_rows[dataset]
        if Path(str(query.get("path", ""))).resolve() != query_path:
            return False
        if (
            not query_path.is_file()
            or query.get("checksum") != sha256_file(query_path)
        ):
            return False
    configured_gates = {
        name for name, enabled in design.get("implementation_gates", {}).items()
        if enabled is True
    }
    passed_gates = {
        row.get("gate")
        for row in report.get("implementation_gates", {}).get("gates", [])
        if isinstance(row, dict)
        and row.get("enabled") is True
        and not row.get("errors")
    }
    return configured_gates == passed_gates


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--backend", choices=("local", "slurm"), default="local")
    parser.add_argument("--stages", default="all")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--plan-only", action="store_true")
    parser.add_argument("--max-concurrent", type=int)
    parser.add_argument("--study", action="append")
    parser.add_argument("--dataset", action="append")
    parser.add_argument(
        "--reuse-preflight",
        type=Path,
        help="reuse a previously passed deep-preflight report for this exact filtered design",
    )
    args = parser.parse_args()
    try:
        base_design = load_design(args.config)
        design = filtered_design(
            base_design, set(args.dataset or []) or None, set(args.study or []) or None
        )
        stages = _parse_stages(args.stages)
        max_concurrent = args.max_concurrent or int(design["resources"]["max_concurrent"])
        if max_concurrent < 1:
            raise ValueError("max_concurrent must be positive")
        root, identity = _prepare_run(design, args.run_id, args.backend, args.resume)
        if args.reuse_preflight:
            reused_path = args.reuse_preflight
            if not reused_path.is_absolute():
                reused_path = repo_path(reused_path)
            preflight = _load_reused_preflight(reused_path, design)
        else:
            preflight = run_preflight(
                args.config,
                checksums=not args.plan_only,
                allow_unresolved_resources=args.plan_only,
                planning=args.plan_only,
                datasets_filter=set(args.dataset or []) or None,
            )
        atomic_write_json(root / "provenance" / "preflight.json", preflight)
        plan = build_all(design, root / "plan" / "matrices")
        planned_rows = []
        for matrix_path in sorted((root / "plan" / "matrices").glob("*.jsonl")):
            if matrix_path.name == "canonical_job_ledger.jsonl":
                continue
            planned_rows.extend(read_jsonl(matrix_path))
        independent_cells = validate_planned_cells(planned_rows, design)
        plan["matrix_validation"] = {
            key: value for key, value in independent_cells.items()
            if not key.startswith("_")
        }
        atomic_write_json(
            root / "plan" / "matrices" / "matrix_counts.json",
            plan,
        )
        if not plan["matrix_validation"]["passed"]:
            raise RuntimeError(
                "independent matrix-cell validation failed"
            )
        if args.plan_only:
            output = {
                "run_id": args.run_id,
                "config_hash": design["config_hash"],
                "backend": args.backend,
                "datasets": design["datasets"],
                "job_counts": plan["study_counts"],
                "total_jobs": plan["total_jobs"],
                "logical_cores": plan["logical_cores_used_for_plan"],
                "physical_cores": plan["physical_cores_used_for_plan"],
                "resolved_physical_core_thread_list":
                    plan["resolved_physical_core_thread_list"],
                "configured_timeout_seconds": design["resources"]["timeout_seconds"],
                "configured_memory_limit_mb": design["resources"].get("memory_limit_mb"),
                "estimated_storage_bytes": plan["estimated_storage_bytes"],
                "serial_timeout_upper_bound_seconds":
                    plan["serial_timeout_upper_bound_seconds"],
                "configured_parallel_timeout_upper_bound_seconds":
                    plan["configured_parallel_timeout_upper_bound_seconds"],
                "pace_b_candidate_work_upper_bound":
                    plan["pace_b_candidate_work_upper_bound"],
                "matrix_validation": plan["matrix_validation"],
                "preflight_blockers": preflight["blockers"],
                "preflight_warnings": [preflight["resources"]["warning"]] if preflight["resources"].get("warning") else [],
                "preflight_passed": preflight["passed"],
                "plan_validation_passed": plan["matrix_validation"]["passed"],
                "algorithms_started": False,
            }
            atomic_write_json(root / "plan" / "PLAN_REPORT.json", output)
            lines = [
                "# PACE Q1 Plan Report", "",
                f"Run ID: `{args.run_id}`", "",
                f"- Exact planned jobs: {plan['total_jobs']:,}",
                f"- Estimated storage: {plan['estimated_storage_bytes']:,} bytes",
                f"- Serial timeout upper bound: {plan['serial_timeout_upper_bound_seconds']:,} seconds",
                f"- Configured-parallel timeout upper bound: {plan['configured_parallel_timeout_upper_bound_seconds']:,} seconds",
                f"- Physical cores: {plan['physical_cores_used_for_plan']}",
                "- Resolved thread list: "
                    + ", ".join(map(str, plan["resolved_physical_core_thread_list"])),
                "- Duplicate planned cells: 0",
                "- Missing planned cells: 0",
                "- Algorithms started: no",
                "", "## Study job counts", "",
            ]
            lines.extend(
                f"- {study_id}: {count:,}"
                for study_id, count in sorted(plan["study_counts"].items())
            )
            lines.extend(["", "## Preflight blockers", ""])
            lines.extend(
                [f"- {blocker}" for blocker in preflight["blockers"]]
                or ["- None"]
            )
            atomic_write_text(root / "plan" / "PLAN_REPORT.md", "\n".join(lines) + "\n")
            print(json.dumps(output, indent=2, sort_keys=True))
            return 0
        for stage in stages:
            marker = root / "markers" / f"{stage}.complete.json"
            if marker.is_file() and args.resume:
                print(f"stage_skip={stage}")
                continue
            print(f"stage_start={stage}", flush=True)
            if stage == "preflight":
                if not preflight["passed"]:
                    raise RuntimeError("preflight blockers: " + "; ".join(preflight["blockers"]))
                result = preflight
            elif stage == "build":
                _run_command(["mvn", "-q", "-DskipTests", "package"])
                result = {"jar": design["paths"]["jar"]}
            elif stage == "data":
                result = {"datasets": len(preflight["datasets"]), "passed": preflight["passed"]}
            elif stage == "queries":
                if design.get("smoke"):
                    result = {"fixture_manifest": "experiments/manifests/tiny.jsonl"}
                else:
                    result = validate_all(design)
                    if not result["passed"]:
                        raise RuntimeError("paper query-manifest contract is not satisfied")
            elif stage == "plan":
                result = plan
            elif stage == "smoke":
                _run_command(["mvn", "-q", "-Dtest=PaceCliSmallQueryTest", "test"])
                result = {"test": "PaceCliSmallQueryTest"}
            elif stage in STAGE_STUDIES:
                result = _execute_studies(
                    design, root, args.run_id, args.backend, STAGE_STUDIES[stage], max_concurrent
                )
                if stage == "pilot" and result.get("jobs"):
                    result["resolved_pace_b"] = resolve(root, design)
            elif stage == "collect":
                result = collect(root)
            elif stage == "validate":
                result = validate(root, design)
                if not result["passed"]:
                    raise RuntimeError("result validation failed")
            elif stage == "summarize":
                result = summarize(root, design)
            elif stage == "plot":
                _run_command([sys.executable, "experiments/plots/make_all_plots.py", "--config", str(args.config), "--run-id", args.run_id])
                result = {"figures": 10}
            elif stage == "table":
                _run_command([sys.executable, "experiments/tables/make_all_tables.py", "--config", str(args.config), "--run-id", args.run_id])
                result = {"tables": 12}
            elif stage == "package":
                result = package(root)
            else:
                raise AssertionError(stage)
            mark_stage(root / "markers", stage, {"stage": stage, "identity": identity, "result": result})
            print(f"stage_complete={stage}", flush=True)
        return 0
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError, subprocess.SubprocessError) as failure:
        print(f"run_all: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Run the bounded tiny-instance PACE-B versus continuous PACE-X suite."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import statistics
import subprocess
import sys
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_json
from experiments.scripts.common.hashing import sha256_file


REPO_ROOT = Path(__file__).resolve().parents[2]
QUALITY_FIELDS = (
    "path_agreement_fraction",
    "score_agreement_fraction",
    "feasibility_disagreement_fraction",
    "breakpoint_precision",
    "breakpoint_recall",
    "integrated_score_regret",
    "relative_score_gap_percent",
    "missed_path_switches",
)


def _mean(values: list[float]) -> float | None:
    return statistics.fmean(values) if values else None


def run_suite(output_directory: Path) -> dict[str, Any]:
    output_directory.mkdir(parents=True, exist_ok=True)
    jar = REPO_ROOT / "target/pace-bench.jar"
    manifest = REPO_ROOT / "experiments/manifests/tiny.jsonl"
    if not jar.is_file():
        raise FileNotFoundError(f"missing benchmark JAR: {jar}")
    configurations = (
        {"pivot_limit_l": 2, "connector_limit_kc": 2, "frontier_limit_kf": 1},
        {"pivot_limit_l": 4, "connector_limit_kc": 4, "frontier_limit_kf": 2},
        {"pivot_limit_l": 8, "connector_limit_kc": 8, "frontier_limit_kf": 4},
        {"pivot_limit_l": 16, "connector_limit_kc": 16, "frontier_limit_kf": 8},
    )
    records: list[dict[str, Any]] = []
    commands: list[list[str]] = []
    for index, configuration in enumerate(configurations):
        raw = output_directory / f"pace_b_kf{configuration['frontier_limit_kf']}.jsonl"
        if raw.exists():
            raw.unlink()
        command = [
            "java",
            "-Xmx4g",
            "-jar",
            str(jar),
            "--algorithm",
            "pace-b",
            "--dataset",
            "demo",
            "--query-file",
            str(manifest),
            "--output-jsonl",
            str(raw),
            "--experiment-name",
            f"pace-exact-quality-{index}",
            "--reference-algorithm",
            "pace-x",
            "--theta",
            "4",
            "--pivot-limit-l",
            str(configuration["pivot_limit_l"]),
            "--connector-limit-kc",
            str(configuration["connector_limit_kc"]),
            "--frontier-limit-kf",
            str(configuration["frontier_limit_kf"]),
            "--connector-expansion-cap-mc",
            "1000000",
            "--breakpoint-cap-mb",
            "1000000",
            "--query-work-cap-mq",
            "250000000",
            "--threads",
            "4",
            "--timeout-seconds",
            "120",
            "--memory-limit-mb",
            "4096",
            "--deterministic",
            "--verify-output",
            "--collect-phase-timings",
            "--collect-memory",
            "--collect-internal-counters",
        ]
        commands.append(command)
        subprocess.run(command, cwd=REPO_ROOT, check=True)
        for line in raw.read_text(encoding="utf-8").splitlines():
            if line.strip():
                record = json.loads(line)
                record["_configuration"] = configuration
                records.append(record)

    completed = [
        record
        for record in records
        if record["status"]["status_code"]
        in {"COMPLETED", "NO_FEASIBLE_PATH"}
    ]
    referenced = [
        record for record in completed
        if record["status"]["reference_available"]
    ]
    verified = [
        record for record in referenced
        if record["status"]["output_verified"]
    ]
    aggregates: dict[str, Any] = {}
    for field in QUALITY_FIELDS:
        values = [
            float(record["quality"][field])
            for record in referenced
            if record["quality"].get(field) is not None
        ]
        aggregates[field] = {
            "observations": len(values),
            "mean": _mean(values),
            "minimum": min(values) if values else None,
            "maximum": max(values) if values else None,
        }
    summary = {
        "schema_version": 1,
        "suite": "pace-b-bounded-versus-pace-x-continuous-tiny-v1",
        "scope": "exact-small-instance-quality-only",
        "jar_sha256": sha256_file(jar),
        "manifest_sha256": sha256_file(manifest),
        "configurations": list(configurations),
        "commands": commands,
        "record_count": len(records),
        "completed_count": len(completed),
        "reference_available_count": len(referenced),
        "output_verified_count": len(verified),
        "all_completed": len(completed) == len(records),
        "all_reference_available": len(referenced) == len(records),
        "all_output_verified": len(verified) == len(records),
        "quality": aggregates,
        "raw_files": sorted(
            path.name for path in output_directory.glob("*.jsonl")
        ),
        "full_matrix_launched": False,
    }
    atomic_write_json(output_directory / "summary.json", summary)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(
            "experiments/results/diagnostics/"
            "pace_paper_readiness_20260729/exact_quality"
        ),
    )
    args = parser.parse_args()
    try:
        summary = run_suite(
            args.output if args.output.is_absolute()
            else REPO_ROOT / args.output
        )
    except (OSError, ValueError, subprocess.CalledProcessError) as failure:
        print(f"exact_quality_suite: {failure}", file=sys.stderr)
        return 1
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0 if (
        summary["all_completed"]
        and summary["all_reference_available"]
        and summary["all_output_verified"]
    ) else 1


if __name__ == "__main__":
    raise SystemExit(main())

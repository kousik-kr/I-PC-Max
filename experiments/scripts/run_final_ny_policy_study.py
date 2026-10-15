#!/usr/bin/env python3
"""Run the five-stage bounded-policy study on certified NY B=5 queries."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
EXACT_ROOT = REPO_ROOT / "experiments/results/Final-result/manuscript_missing_runs/final_ny_exact"
RESULT_ROOT = REPO_ROOT / "experiments/results/Final-result/manuscript_missing_runs/final_ny_policy"
ARTIFACT_ROOT = REPO_ROOT / "experiments/results/Final-result/manuscript_artifacts"
DATASET = REPO_ROOT / "data/input/NY-Exact-radius"
JAR = REPO_ROOT / "target/pace-bench.jar"
TRIALS = (0, 1, 2)
LONG_MAX = 9223372036854775807
INT_MAX = 2147483647

STAGES: dict[str, dict[str, Any]] = {
    "+theta": {"theta": 2, "L": INT_MAX, "Kc": INT_MAX, "Kf": INT_MAX, "Mc": LONG_MAX, "Mb": INT_MAX, "Mq": LONG_MAX, "ablation": None},
    "+L": {"theta": 2, "L": 1, "Kc": INT_MAX, "Kf": INT_MAX, "Mc": LONG_MAX, "Mb": INT_MAX, "Mq": LONG_MAX, "ablation": None},
    "+FastConnector": {"theta": 2, "L": 1, "Kc": 1, "Kf": INT_MAX, "Mc": LONG_MAX, "Mb": INT_MAX, "Mq": LONG_MAX, "ablation": "fast-only-connector"},
    "+Kf": {"theta": 2, "L": 1, "Kc": 1, "Kf": 1, "Mc": LONG_MAX, "Mb": INT_MAX, "Mq": LONG_MAX, "ablation": "fast-only-connector"},
    "+Mb": {"theta": 2, "L": 1, "Kc": 1, "Kf": 1, "Mc": 250000, "Mb": 100000, "Mq": 5000000, "ablation": "fast-only-connector"},
}


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def certified_b5_ids() -> set[str]:
    ids: set[str] = set()
    for path in sorted((EXACT_ROOT / "raw/pace-x").glob("trial-*.jsonl")):
        for row in read_jsonl(path):
            if (row.get("run") or {}).get("warmup", False):
                continue
            if row.get("query", {}).get("budget") == 5.0 and (row.get("status") or {}).get("exactness_scope") == "GLOBAL_CERTIFIED":
                ids.add(str(row["query"]["query_id"]))
    return ids


def prepare_manifest() -> tuple[Path, list[dict[str, Any]]]:
    source = EXACT_ROOT / "manifests/final_ny_exact_queries.jsonl"
    ids = certified_b5_ids()
    rows = [row for row in read_jsonl(source) if row["query_id"] in ids and row["budget"] == 5.0]
    rows.sort(key=lambda row: row["query_id"])
    expected = 20
    if len(rows) != expected:
        raise RuntimeError(f"expected {expected} certified NY B=5 queries, found {len(rows)}")
    path = RESULT_ROOT / "manifests/policy_queries_b005.jsonl"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n" for row in rows), encoding="utf-8")
    return path, rows


def run_stage(stage: str, config: dict[str, Any], manifest: Path, rows: list[dict[str, Any]]) -> list[Path]:
    paths: list[Path] = []
    directory = stage.replace("+", "plus-").lower()
    for trial in TRIALS:
        output = RESULT_ROOT / "raw" / directory / f"trial-{trial}.jsonl"
        paths.append(output)
        if output.is_file():
            measured = [row for row in read_jsonl(output) if not (row.get("run") or {}).get("warmup", False)]
            expected = {row["query_id"] for row in rows}
            actual = {row.get("query", {}).get("query_id") for row in measured}
            if len(measured) == len(rows) and actual == expected:
                continue
            output.unlink()
        profile_dir = RESULT_ROOT / "profiles" / directory / f"trial-{trial}"
        command = [
            "java", "-jar", str(JAR), "--algorithm", "pace-b", "--dataset", str(DATASET),
            "--query-file", str(manifest), "--output-jsonl", str(output),
            "--experiment-name", f"FINAL_POLICY_NY_V1:{stage}:trial-{trial}",
            "--repetitions", "1", "--warmup-runs", "1", "--threads", "24",
            "--timeout-seconds", "5", "--preprocessing-timeout-seconds", "300",
            "--memory-limit-mb", "256000", "--theta", str(config["theta"]),
            "--pivot-limit-l", str(config["L"]), "--connector-limit-kc", str(config["Kc"]),
            "--frontier-limit-kf", str(config["Kf"]), "--connector-expansion-cap-mc", str(config["Mc"]),
            "--breakpoint-cap-mb", str(config["Mb"]), "--query-work-cap-mq", str(config["Mq"]),
            "--max-frontier-fragments", str(INT_MAX), "--deterministic", "--collect-phase-timings",
            "--collect-memory", "--collect-internal-counters", "--serialize-profile",
            "--profile-output-dir", str(profile_dir), "--shared-preprocessing",
        ]
        if config["ablation"]:
            command.extend(["--ablation", config["ablation"]])
        log = RESULT_ROOT / "logs" / f"{directory}-trial-{trial}.log"
        log.parent.mkdir(parents=True, exist_ok=True)
        with log.open("w", encoding="utf-8") as stream:
            completed = subprocess.run(command, cwd=REPO_ROOT, stdout=stream, stderr=subprocess.STDOUT, check=False)
        if completed.returncode not in (0, 1):
            raise RuntimeError(f"{stage} trial {trial} failed with exit code {completed.returncode}; see {log}")
        measured = [row for row in read_jsonl(output) if not (row.get("run") or {}).get("warmup", False)]
        if len(measured) != len(rows):
            raise RuntimeError(f"{stage} trial {trial} emitted {len(measured)} rows; expected {len(rows)}")
    return paths


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-run", action="store_true")
    args = parser.parse_args()
    manifest, rows = prepare_manifest()
    stage_paths: dict[str, list[Path]] = {}
    for stage, config in STAGES.items():
        stage_paths[stage] = ([RESULT_ROOT / "raw" / stage.replace("+", "plus-").lower() / f"trial-{trial}.jsonl" for trial in TRIALS]
                             if args.skip_run else run_stage(stage, config, manifest, rows))
    summary = {
        "schema_version": 1, "study_id": "FINAL_POLICY_NY_V1", "dataset": "NY",
        "execution_dataset": "deterministic connected tractable NY induced subset",
        "source_exact_study": str(EXACT_ROOT / "summary.json"),
        "source_exact_study_sha256": sha256(EXACT_ROOT / "summary.json"),
        "query_manifest": str(manifest), "query_manifest_sha256": sha256(manifest),
        "certified_logical_queries": len(rows), "measured_trials": len(TRIALS),
        "new_measured_records": len(rows) * len(TRIALS) * len(STAGES),
        "reference": "PACE-X GLOBAL_CERTIFIED rows from FINAL_EXACT_NY_V1 at B=5",
        "pace_b_timeout_seconds": 5,
        "stages": {stage: {**config, "raw_files": [str(path) for path in stage_paths[stage]]} for stage, config in STAGES.items()},
    }
    RESULT_ROOT.mkdir(parents=True, exist_ok=True)
    (RESULT_ROOT / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    extract_dir = ARTIFACT_ROOT / "extracted"
    extract_dir.mkdir(parents=True, exist_ok=True)
    (extract_dir / "final_ny_policy_study_run_summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

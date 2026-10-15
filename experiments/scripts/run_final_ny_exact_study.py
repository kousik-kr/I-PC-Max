#!/usr/bin/env python3
"""Run the NY-only exact-quality study after deterministic candidate ranking."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
# The exact study runs on a deterministic connected tractable induced subset
# of the canonical NY graph; manuscript-facing values report the parent NY
# dataset and do not expose this implementation path.
DATASET = REPO_ROOT / "data/input/NY-Exact-radius"
RESULT_ROOT = REPO_ROOT / "experiments/results/Final-result/manuscript_missing_runs/final_ny_exact"
ARTIFACT_ROOT = REPO_ROOT / "experiments/results/Final-result/manuscript_artifacts"
JAR = REPO_ROOT / "target/pace-bench.jar"
POOL = RESULT_ROOT / "provenance/candidate_pool.json"
BUDGETS = (5.0, 10.0, 15.0)
CENTERS = (510, 1110)
LONG_MAX = 9223372036854775807
INT_MAX = 2147483647

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]

def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n" for row in rows), encoding="utf-8")

def build_manifest(pair_count: int, pair_start: int, budgets: tuple[float, ...]) -> tuple[Path, list[dict[str, Any]]]:
    pool = json.loads(POOL.read_text(encoding="utf-8"))
    pairs = pool["selected_pairs"][pair_start:pair_start + pair_count]
    rows = []
    for pair in pairs:
        for center in CENTERS:
            for budget in budgets:
                query_id = f"NY-FINAL-{pair['pair_id']}-C{center}-B{int(budget):03d}"
                rows.append({
                    # The runner validates this against the induced execution
                    # dataset.  Publication-facing extraction maps it back to
                    # the parent NY dataset label.
                    "schema_version": 1, "query_id": query_id, "dataset_id": "NY-Exact-radius",
                    "source": pair["source"], "destination": pair["destination"],
                    "interval_start": center - 60, "interval_end": center + 60, "window_length": 120,
                    "budget": budget, "budget_slack": None, "budget_policy": "full-interval-feasible",
                    "distance_bin": pair["distance_band"], "lower_bound_distance": pair["static_fastest_travel_time_min"],
                    "query_seed": int.from_bytes(hashlib.sha256(query_id.encode()).digest()[:8], "big", signed=True),
                    "metadata": {
                        "study": "FINAL_EXACT_NY_V1", "pair_id": pair["pair_id"], "pair_index": pair["pair_index"],
                        "distance_band": f"B{pair['distance_band']}", "interval_center": center, "budget_minutes": budget,
                        "static_fastest_travel_time_min": pair["static_fastest_travel_time_min"], "estimated_hops": pair["estimated_hops"],
                        "candidate_pool_sha256": sha256(POOL), "selection_rule": "pre-quality static base-travel-time ranking",
                    },
                })
    path = RESULT_ROOT / "manifests/final_ny_exact_queries.jsonl"
    write_jsonl(path, rows)
    (RESULT_ROOT / "provenance/selection_used.json").write_text(json.dumps({"dataset": "NY", "pairs": pairs, "centers": list(CENTERS), "budgets_minutes": list(budgets), "logical_queries": len(rows), "candidate_pool_sha256": sha256(POOL)}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return path, rows

def run_algorithm(algorithm: str, manifest: Path, rows: list[dict[str, Any]], trials: tuple[int, ...], theta_x: int) -> list[Path]:
    paths = []
    for trial in trials:
        output = RESULT_ROOT / "raw" / algorithm / f"trial-{trial}.jsonl"
        paths.append(output)
        if output.is_file():
            measured = [r for r in read_jsonl(output) if not (r.get("run") or {}).get("warmup", False)]
            expected = {r["query_id"] for r in rows}
            actual = {r.get("query", {}).get("query_id") for r in measured}
            if len(measured) == len(rows) and actual == expected:
                continue
            output.unlink()
        if algorithm == "pace-x":
            config = {"theta": theta_x, "L": INT_MAX, "Kc": INT_MAX, "Kf": INT_MAX, "Mc": LONG_MAX, "Mb": INT_MAX, "Mq": LONG_MAX, "threads": 1, "timeout": 120}
        else:
            config = {"theta": 2, "L": 1, "Kc": 1, "Kf": 1, "Mc": 250000, "Mb": 100000, "Mq": 5000000, "threads": 24, "timeout": 5}
        profile_dir = RESULT_ROOT / "profiles" / algorithm / f"trial-{trial}"
        command = ["java", "-jar", str(JAR), "--algorithm", algorithm, "--dataset", str(DATASET), "--query-file", str(manifest), "--output-jsonl", str(output), "--experiment-name", f"FINAL_EXACT_NY_V1:{algorithm}:trial-{trial}", "--repetitions", "1", "--warmup-runs", "1", "--threads", str(config["threads"]), "--timeout-seconds", str(config["timeout"]), "--preprocessing-timeout-seconds", "300", "--memory-limit-mb", "256000", "--theta", str(config["theta"]), "--pivot-limit-l", str(config["L"]), "--connector-limit-kc", str(config["Kc"]), "--frontier-limit-kf", str(config["Kf"]), "--connector-expansion-cap-mc", str(config["Mc"]), "--breakpoint-cap-mb", str(config["Mb"]), "--query-work-cap-mq", str(config["Mq"]), "--max-frontier-fragments", str(INT_MAX), "--deterministic", "--collect-phase-timings", "--collect-memory", "--collect-internal-counters", "--serialize-profile", "--profile-output-dir", str(profile_dir), "--shared-preprocessing"]
        log = RESULT_ROOT / "logs" / f"{algorithm}-trial-{trial}.log"
        log.parent.mkdir(parents=True, exist_ok=True)
        with log.open("w", encoding="utf-8") as stream:
            completed = subprocess.run(command, cwd=REPO_ROOT, stdout=stream, stderr=subprocess.STDOUT, check=False)
        if completed.returncode not in (0, 1):
            raise RuntimeError(f"{algorithm} trial {trial} failed with exit code {completed.returncode}; see {log}")
        if not output.is_file():
            raise RuntimeError(f"{algorithm} trial {trial} did not create {output}; see {log}")
        measured = [r for r in read_jsonl(output) if not (r.get("run") or {}).get("warmup", False)]
        if len(measured) != len(rows):
            raise RuntimeError(f"{algorithm} trial {trial} emitted {len(measured)} rows; expected {len(rows)}")
    return paths

def summarize(manifest: Path, rows: list[dict[str, Any]], paths: dict[str, list[Path]], trials: tuple[int, ...], theta_x: int, budgets: tuple[float, ...]) -> dict[str, Any]:
    algorithms = {}
    for algorithm, files in paths.items():
        records = [r for path in files for r in read_jsonl(path) if not (r.get("run") or {}).get("warmup", False)]
        statuses = {}
        scopes = {}
        for record in records:
            status = record.get("status") or {}
            statuses[status.get("status_code")] = statuses.get(status.get("status_code"), 0) + 1
            scopes[status.get("exactness_scope")] = scopes.get(status.get("exactness_scope"), 0) + 1
        algorithms[algorithm] = {"measured_records": len(records), "expected_records": len(rows) * len(trials), "statuses": statuses, "exactness_scopes": scopes, "global_certified_records": scopes.get("GLOBAL_CERTIFIED", 0)}
    summary = {"schema_version": 1, "study_id": "FINAL_EXACT_NY_V1", "dataset": "NY", "execution_dataset": "deterministic connected tractable NY induced subset", "jar": str(JAR), "jar_sha256": sha256(JAR), "query_manifest": str(manifest), "query_manifest_sha256": sha256(manifest), "candidate_pool": str(POOL), "candidate_pool_sha256": sha256(POOL), "pairs": len({r['metadata']['pair_id'] for r in rows}), "logical_queries": len(rows), "interval_queries_per_budget": len({r['metadata']['pair_id'] for r in rows}) * 2, "budgets_minutes": list(budgets), "centers": list(CENTERS), "measured_trials": len(trials), "warmup_runs": 1, "pace_b": {"L": 1, "theta": 2, "Kf": 1, "Mb": 100000, "Kc": 1, "threads": 24, "timeout_seconds": 5}, "pace_x": {"theta": theta_x, "limits": "unbounded", "threads": 1, "timeout_seconds": 120}, "algorithms": algorithms, "raw_files": {key: [str(path) for path in files] for key, files in paths.items()}}
    RESULT_ROOT.mkdir(parents=True, exist_ok=True)
    (RESULT_ROOT / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (ARTIFACT_ROOT / "extracted/final_ny_exact_study_run_summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return summary

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pair-count", type=int, default=10)
    parser.add_argument("--pair-start", type=int, default=0)
    parser.add_argument("--trials", type=int, default=3)
    parser.add_argument("--theta-x", type=int, default=256)
    parser.add_argument("--budgets", default="5,10,15", help="comma-separated budget minutes for diagnostic subsets")
    parser.add_argument("--skip-run", action="store_true")
    args = parser.parse_args()
    if not JAR.is_file() or not POOL.is_file():
        raise SystemExit("benchmark JAR and candidate pool are required")
    budgets = tuple(float(item) for item in args.budgets.split(",") if item.strip())
    manifest, rows = build_manifest(args.pair_count, args.pair_start, budgets)
    trials = tuple(range(args.trials))
    paths = {"pace-x": [RESULT_ROOT / "raw/pace-x" / f"trial-{t}.jsonl" for t in trials], "pace-b": [RESULT_ROOT / "raw/pace-b" / f"trial-{t}.jsonl" for t in trials]}
    if not args.skip_run:
        paths = {"pace-x": run_algorithm("pace-x", manifest, rows, trials, args.theta_x), "pace-b": run_algorithm("pace-b", manifest, rows, trials, args.theta_x)}
    print(json.dumps(summarize(manifest, rows, paths, trials, args.theta_x, budgets), indent=2, sort_keys=True))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())

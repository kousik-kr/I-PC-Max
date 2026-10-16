#!/usr/bin/env python3
"""Read-only validation of archived T03 rows and manuscript aggregation.

Never writes within an input tree.  The explicit output must be outside it.
This validates bytes, identities and recorded outcomes, not missing dirty source.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import statistics
import subprocess
from typing import Any

NORMAL = {"SUCCESS", "COMPLETED", "CERTIFIED_COMPLETE"}
CAPPED = {"TIME_CAPPED_NOT_CERTIFIED", "PATH_CAPPED_NOT_CERTIFIED"}
RESOURCE = {"RESOURCE_LIMIT_EXCEEDED", "RESOURCE_TRUNCATED"}
REPO = Path(__file__).resolve().parents[2]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def tree_inventory(paths: list[Path], root: Path) -> dict[str, Any]:
    """Digest sorted relative-path NUL content-sha256 NUL records, UTF-8."""
    digest = hashlib.sha256()
    size = 0
    for path in sorted(paths):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(b"\0")
        digest.update(sha256_file(path).encode("ascii"))
        digest.update(b"\0")
        size += path.stat().st_size
    return {"files": len(paths), "bytes": size, "sha256": digest.hexdigest(),
            "definition": "sha256(sorted(relative_path UTF8 + NUL + file_sha256 ASCII + NUL))"}


def records(path: Path):
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if line.strip():
                try:
                    value = json.loads(line)
                except json.JSONDecodeError as exc:
                    raise ValueError(f"invalid JSON: {path}:{line_number}") from exc
                if not isinstance(value, dict):
                    raise ValueError(f"non-object row: {path}:{line_number}")
                yield value


def stats(values) -> dict[str, Any]:
    clean = [v for v in values if isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)]
    return {"count": len(clean), "mean": statistics.fmean(clean) if clean else None,
            "median": statistics.median(clean) if clean else None}


def summarize_rows(rows: list[dict]) -> dict[str, Any]:
    complete = [r for r in rows if r["status"] in NORMAL and r["completed"] is True]
    by_status = dict(sorted(Counter(r["status"] for r in rows).items()))
    return {
        "terminal_rows": len(rows), "unique_query_ids": len({r.get("query_id") for r in rows}),
        "query_trial_row_counts": dict(sorted(Counter(Counter(r.get("query_id") for r in rows).values()).items())),
        "completed_rows": len(complete),
        "completion_percentage": 100 * len(complete) / len(rows) if rows else None,
        "status_counts": by_status,
        "time_capped_rows": by_status.get("TIME_CAPPED_NOT_CERTIFIED", 0),
        "path_capped_rows": by_status.get("PATH_CAPPED_NOT_CERTIFIED", 0),
        "timeout_rows": by_status.get("TIMEOUT", 0),
        "resource_truncated_rows": sum(by_status.get(s, 0) for s in RESOURCE),
        "failed_error_rows": sum(n for s, n in by_status.items() if s not in NORMAL | CAPPED | RESOURCE | {"TIMEOUT"}),
        "valid_profile_rows": sum(r.get("profile_present", False) for r in rows),
        "time_capped_valid_profile_rows": sum(r["status"] in CAPPED and r.get("profile_present", False) for r in rows),
        "completed_only_runtime_s": stats(r.get("runtime_s") for r in complete),
        "completed_only_score": stats(r.get("score") for r in complete),
        "completed_only_coverage": stats(r.get("coverage") for r in complete),
        "capped_partial_runtime_s": stats(r.get("runtime_s") for r in rows if r["status"] in CAPPED),
        "capped_partial_score": stats(r.get("score") for r in rows if r["status"] in CAPPED),
        "capped_partial_coverage": stats(r.get("coverage") for r in rows if r["status"] in CAPPED),
        "uncertainty": "Not inferred: original full-matrix summary has no archived confidence-interval definition.",
    }


def _row(raw: dict, ledger: dict) -> dict:
    java = raw.get("java_record") or {}
    out = java.get("output") or {}
    timing = java.get("timing_ns") or {}
    status = java.get("status") or {}
    return {"query_id": ledger.get("query_id"), "trial_id": ledger.get("trial_id"),
            "status": raw.get("completion_status", "UNKNOWN"),
            "completed": status.get("completed") is True,
            "runtime_s": timing["query_total"] / 1e9 if isinstance(timing.get("query_total"), (float, int)) else None,
            "score": out.get("average_selected_score"), "coverage": out.get("feasible_coverage_fraction"),
            "profile_present": out.get("profile_checksum") is not None,
            "configured_timeout_s": (java.get("configuration") or {}).get("timeout_seconds")}


def audit_dataset(root: Path, dataset: str) -> dict[str, Any]:
    ledger_path = root / "plan/matrices/canonical_job_ledger.jsonl"
    ledger_rows = list(records(ledger_path))
    ledger_ids = Counter(r["job_id"] for r in ledger_rows)
    ledger = {r["job_id"]: r for r in ledger_rows}
    expected = defaultdict(set)
    for r in ledger_rows:
        if r.get("dataset_id") == dataset:
            expected[r["algorithm_id"]].add(r["job_id"])
    raw_paths = sorted((root / "raw/T03").glob("*.jsonl"))
    counts, unknown, input_mismatches = Counter(), set(), []
    methods, present = defaultdict(list), defaultdict(set)
    ignored_dataset_counts = Counter()
    for path in raw_paths:
        for raw in records(path):
            job = raw.get("job_id")
            counts[job] += 1
            planned = ledger.get(job)
            if planned is None:
                unknown.add(str(job))
                continue
            if raw.get("input_hash") is not None and planned.get("input_hash") != raw["input_hash"]:
                input_mismatches.append(job)
            if planned.get("dataset_id") != dataset:
                ignored_dataset_counts[planned.get("dataset_id")] += 1
                continue
            algorithm = planned["algorithm_id"]
            java = raw.get("java_record")
            if java:
                if (java.get("dataset") or {}).get("dataset_id") != dataset or (java.get("configuration") or {}).get("algorithm") != algorithm:
                    input_mismatches.append(job)
                if (java.get("query") or {}).get("query_id") != planned.get("query_id"):
                    input_mismatches.append(job)
                if (java.get("run") or {}).get("repetition") != planned.get("trial_id"):
                    input_mismatches.append(job)
            methods[algorithm].append(_row(raw, planned))
            present[algorithm].add(job)
    summaries = {}
    for algorithm in sorted(expected.keys() | methods.keys()):
        summary = summarize_rows(methods[algorithm])
        missing = sorted(expected[algorithm] - present[algorithm])
        summary.update({"planned_rows": len(expected[algorithm]), "unique_job_ids": len(present[algorithm]),
                        "missing_job_id_count": len(missing),
                        "missing_job_ids": missing if len(missing) <= 20 else None,
                        "missing_job_ids_first_20": missing[:20],
                        "missing_job_ids_sha256": hashlib.sha256(json.dumps(missing, separators=(",", ":")).encode()).hexdigest(),
                        "effective_timeout_seconds": sorted({r["configured_timeout_s"] for r in methods[algorithm] if r["configured_timeout_s"] is not None})})
        summaries[algorithm] = summary
    hashes = {}
    for relative in ["provenance/environment.json", "provenance/IMMUTABLE.json", "provenance/effective_config.json", "provenance/preflight.json", "provenance/progress.json", "markers/main.complete.json", "markers/queries.complete.json", "plan/matrices/t03.jsonl"]:
        path = root / relative
        hashes[relative] = sha256_file(path) if path.is_file() else None
    read_json = lambda rel: json.loads((root / rel).read_text()) if (root / rel).is_file() else None
    preflight = read_json("provenance/preflight.json") or {}
    integrity = preflight.get("dataset_integrity") or {}
    effective = read_json("provenance/effective_config.json") or {}
    pattern = effective.get("query_generation", {}).get("manifest_pattern")
    query_identity = None
    if pattern:
        manifest = REPO / pattern.format(dataset=dataset)
        marker_datasets = (read_json("markers/queries.complete.json") or {}).get("result", {}).get("datasets", [])
        recorded = next((r.get("checksum") for r in marker_datasets if r.get("dataset_id") == dataset), None)
        observed = sha256_file(manifest) if manifest.is_file() else None
        query_identity = {"path": str(manifest.relative_to(REPO)), "observed_now_sha256": observed,
                          "recorded_marker_sha256": recorded,
                          "matches_archived_marker": observed == recorded if recorded is not None else None}
    return {
        "dataset": dataset, "raw_tree": tree_inventory(raw_paths, root), "raw_rows_all_datasets": sum(counts.values()),
        "other_dataset_raw_rows": dict(ignored_dataset_counts),
        "ledger_sha256": sha256_file(ledger_path), "ledger_rows_all_datasets": len(ledger_rows),
        "original_main_marker_present": (root / "markers/main.complete.json").is_file(),
        "original_marker": read_json("markers/main.complete.json"),
        "environment": read_json("provenance/environment.json"),
        "run_identity": read_json("provenance/IMMUTABLE.json"),
        "query_manifest_identity": query_identity,
        "recorded_dataset_checksums": [r for r in integrity.get("datasets", []) if r.get("dataset_id") == dataset],
        "artifact_sha256": hashes, "methods": summaries,
        "identity": {"duplicate_job_ids": {k: v for k, v in counts.items() if v > 1},
                     "duplicate_ledger_job_ids": {k: v for k, v in ledger_ids.items() if v > 1},
                     "unknown_job_ids": sorted(unknown), "input_or_serialized_identity_mismatches": sorted(set(input_mismatches))},
        "historical_source_disposition": "HISTORICAL SOURCE NOT FULLY REPRODUCIBLE",
        "scope": "Byte identity, ledger coverage and recorded terminal status only; no source reconstruction or algorithm-output certification.",
    }


def manuscript_reaggregation(base: Path) -> dict[str, Any]:
    """Use the archived extractor's exact selector/two-level median definitions.

    No main() or output-producing function from the archive is invoked. ROOT is
    rebound because this historic extractor was moved into a superseded folder.
    """
    source = base / "manuscript_artifacts/internal_superseded_ol/scripts/00_extract_and_freeze_manuscript_data.py"
    if not source.is_file():
        return {"status": "AGGREGATOR_UNAVAILABLE"}
    spec = importlib.util.spec_from_file_location("archived_extractor", source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.ROOT = base
    rows = []
    inputs = []
    files = [p for ds in ["NY", "FLA", "CAL"] for p in (base / ds / "work").glob("batch-*/java-results.jsonl")]
    included = base / "manuscript_missing_runs/results/fasttrack_scalability_repairs_reuse_final/work"
    files += list(included.glob("batch-*/java-results.jsonl"))
    for path in sorted(files):
        inputs.append(path)
        fasttrack = "manuscript_missing_runs" in path.parts
        parent = path.parents[2].name
        for raw in records(path):
            ds = raw.get("dataset", {}).get("dataset_id")
            algorithm = raw.get("configuration", {}).get("algorithm")
            if ds not in {"NY", "FLA", "CAL"} or (not fasttrack and ds != parent):
                continue
            if algorithm not in {"pace-b", "iscope"} or raw.get("run", {}).get("warmup"):
                continue
            standard = module.raw_to_standard(raw, path, ds)
            if standard.get("window") == 120 and standard.get("rho") is not None and abs(standard["rho"] - 0.30) < 1e-9:
                rows.append(standard)
    frozen = json.loads((base / "manuscript_artifacts/extracted/final_ny_values.json").read_text())
    frozen_by_ds = {r["dataset"]: r for r in frozen["scalability"]}
    summary = []
    for ds in ["NY", "FLA", "CAL"]:
        for algorithm in ["pace-b", "iscope"]:
            selected, filters = module.choose_default_rows(rows, ds, algorithm)
            aggregates = module.aggregate_queries(selected)
            aggregate = aggregates[0] if aggregates else {}
            complete = [r for r in selected if r.get("normal_completion")]
            item = {"dataset": ds, "method": algorithm, "filters": filters,
                    "source_files": sorted({str(Path(r["source_file"]).relative_to(base)) for r in selected}),
                    "trial_status_counts": dict(Counter(r["status"] for r in selected)),
                    "recorded_query_caps_seconds": sorted({r["timeout_seconds"] for r in selected if r.get("timeout_seconds") is not None}),
                    "observed_thread_counts": sorted({r["threads"] for r in selected if r.get("threads") is not None}),
                    "selected_trial_rows": len(selected), "completed_trial_rows": len(complete),
                    "query_count": aggregate.get("query_count"), "completion_pct": aggregate.get("completion_pct"),
                    "runtime_s": aggregate.get("runtime_s"), "score": aggregate.get("avg_score"),
                    "coverage": aggregate.get("coverage"),
                    "completed_only_trial_runtime_s": stats(r.get("runtime_s") for r in complete),
                    "completed_only_trial_score": stats(r.get("avg_score") for r in complete),
                    "definition": "Median of per-query trial medians; score is median of query average_selected_score, not arithmetic mean across queries."}
            if algorithm == "pace-b":
                want = frozen_by_ds[ds]
                item["frozen_values"] = want
                item["runtime_delta"] = item["runtime_s"] - want["pace_runtime_s"] if item["runtime_s"] is not None else None
                item["score_delta"] = item["score"] - want["pace_score"] if item["score"] is not None else None
                item["completion_delta"] = item["completion_pct"] - want["pace_completion_pct"] if item["completion_pct"] is not None else None
            summary.append(item)
    return {"status": "RECOMPUTED_WITH_ARCHIVED_FUNCTIONS", "aggregator_sha256": sha256_file(source),
            "aggregator_path": str(source.relative_to(base)), "input_tree": tree_inventory(inputs, base), "summaries": summary,
            "uncertainty": "No archived uncertainty definition for this scalability table; none invented."}


def recover_source_evidence(base: Path) -> dict[str, Any]:
    """Inventory potential recovery objects without checking out or writing Git."""
    def git(*args: str) -> str:
        return subprocess.run(["git", *args], cwd=REPO, check=True, capture_output=True, text=True).stdout.strip()
    unreachable = [line.split() for line in git("fsck", "--no-reflogs", "--unreachable").splitlines()]
    root_trees = []
    for row in unreachable:
        if len(row) == 3 and row[1] == "tree":
            names = git("ls-tree", "--name-only", row[2]).splitlines()
            if "pom.xml" in names and "src" in names:
                root_trees.append(row[2])
    revisions = []
    for ds in ["NY", "FLA", "CAL"]:
        recorded = json.loads((base / ds / "provenance/environment.json").read_text())["git"]
        commit = recorded["commit"]
        revisions.append({"dataset": ds, "recorded": recorded,
                          "base_commit_tree": git("rev-parse", f"{commit}^{{tree}}"),
                          "complete_dirty_patch_or_untracked_bundle_found": False})
    candidates = []
    for directory in [REPO / "experiments/results/_artifacts", base / "NYC-real-shuttle-v2/provenance/execution"]:
        for path in sorted(directory.glob("*.jar")):
            candidates.append({"path": str(path.relative_to(REPO)), "bytes": path.stat().st_size, "sha256": sha256_file(path)})
    return {"historical_revisions": revisions, "stash": git("stash", "list").splitlines(),
            "worktrees": git("worktree", "list", "--porcelain").splitlines(),
            "unreachable_object_counts": dict(Counter(r[1] for r in unreachable if len(r) == 3)),
            "unreachable_commits": [git("show", "-s", "--format=%H %T %cI %s", r[2]) for r in unreachable if len(r) == 3 and r[1] == "commit"],
            "unreachable_full_root_trees_inventoried": len(root_trees), "archived_jar_candidates": candidates,
            "recovery_limit": "A dirty working_tree_hash is SHA256 of binary diff plus untracked path/content; it is not a Git tree ID. No archived complete dirty patch/untracked bundle or run-bound JAR was found. Unreachable tracked-tree candidates cannot recover missing untracked content.",
            "disposition": "HISTORICAL SOURCE NOT FULLY REPRODUCIBLE"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, default=REPO / "experiments/results/Final-result")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--compare", type=Path, help="Fail if any raw-tree digest differs from a prior attestation.")
    args = parser.parse_args()
    base, output = args.snapshot.resolve(), args.output.resolve()
    if output == base or base in output.parents:
        parser.error("output must be outside the immutable input tree")
    if output.exists():
        parser.error("refusing to overwrite an existing audit output; use a new output name")
    result = {"schema_version": 1, "record_type": "VALIDATED_ATTESTATION",
              "validated_at_utc": datetime.now(timezone.utc).isoformat(),
              "validator_revision": subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO, capture_output=True, text=True, check=True).stdout.strip(),
              "validator_sha256": sha256_file(Path(__file__)),
              "snapshot": str(base), "historical_source_reconstructed": False,
              "datasets": [audit_dataset(base / ds, ds) for ds in ["NY", "FLA", "CAL"]],
              "manuscript_reaggregation": manuscript_reaggregation(base),
              "source_recovery": recover_source_evidence(base)}
    if args.compare:
        before = json.loads(args.compare.read_text())
        old = {d["dataset"]: d["raw_tree"] for d in before["datasets"]}
        changed = [d["dataset"] for d in result["datasets"] if old.get(d["dataset"]) != d["raw_tree"]]
        if changed:
            raise ValueError(f"historical raw tree changed: {changed}")
        result["raw_preservation_comparison"] = {"prior_attestation_sha256": sha256_file(args.compare), "identical": True}
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8") as handle:
        json.dump(result, handle, indent=2, sort_keys=True, allow_nan=False)
        handle.write("\n")
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

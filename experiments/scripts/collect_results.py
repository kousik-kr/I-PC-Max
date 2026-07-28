#!/usr/bin/env python3
"""Collect immutable raw job records into normalized JSONL and CSV."""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import sys
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_json, write_jsonl
from experiments.scripts.common.config import load_design, repo_path
from experiments.scripts.common.hashing import sha256_files


def read_records(directory: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for path in sorted(directory.rglob("*.jsonl")):
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                records.append(json.loads(line))
    return records


def flatten(record: dict[str, Any]) -> dict[str, Any]:
    java = record.get("java_record") or {}
    configuration = java.get("configuration", {})
    status = java.get("status", {})
    counters = java.get("counters", {})
    memory = java.get("memory_bytes", {})
    output = java.get("output", {})
    peak_rss = memory.get("peak_rss")
    start_rss = memory.get("start_rss")
    return {
        "run_id": record["run_id"],
        "study_id": record["study_id"],
        "job_id": record["job_id"],
        "trial_id": record["trial_id"],
        "completion_status": record["completion_status"],
        "dataset_id": java.get("dataset", {}).get("dataset_id"),
        "query_id": java.get("query", {}).get("query_id"),
        "algorithm_id": configuration.get("algorithm"),
        "variant_id": configuration.get("ablation"),
        "pace_engine": configuration.get("pace_engine"),
        "pivot_limit_l": configuration.get("pivot_limit_l"),
        "connector_limit_kc": configuration.get("connector_limit_kc"),
        "frontier_limit_kf": configuration.get("frontier_limit_kf"),
        "connector_expansion_cap_mc": configuration.get("connector_expansion_cap_mc"),
        "breakpoint_cap_mb": configuration.get("breakpoint_cap_mb"),
        "query_work_cap_mq": configuration.get("query_work_cap_mq"),
        "threads": configuration.get("threads"),
        "generation_completion": status.get("generation_completion"),
        "exactness_scope": status.get("exactness_scope"),
        "cap_triggered": ",".join(status.get("cap_triggered") or []),
        "wall_time_ns": java.get("timing_ns", {}).get("query_total"),
        "cpu_time_ns": java.get("timing_ns", {}).get("cpu_total"),
        "peak_rss_bytes": memory.get("peak_rss"),
        "start_rss_bytes": start_rss,
        "end_rss_bytes": memory.get("end_rss"),
        "incremental_rss_bytes": (
            max(0, peak_rss - start_rss)
            if isinstance(peak_rss, (int, float))
            and isinstance(start_rss, (int, float))
            else None
        ),
        "peak_heap_bytes": memory.get("peak_heap"),
        "corridor_nodes": counters.get("corridor_nodes"),
        "corridor_edges": counters.get("corridor_edges"),
        "selected_pivots": counters.get("selected_pivots"),
        "connector_calls": counters.get("connector_calls"),
        "connector_expansions": counters.get("connector_expansions"),
        "valid_connectors": counters.get("valid_connectors"),
        "candidates_generated": counters.get("candidates_generated"),
        "candidates_retained": counters.get("candidates_retained"),
        "total_candidate_work": counters.get("total_candidate_work"),
        "memo_lookups": counters.get("memo_lookups"),
        "memo_hits": counters.get("memo_hits"),
        "memo_misses": counters.get("memo_misses"),
        "memo_waits": counters.get("memo_waits"),
        "observed_workers": counters.get("observed_workers"),
        "profile_cells_total": output.get("final_profile_intervals"),
        "feasible_coverage": output.get("feasible_coverage_fraction"),
        "average_selected_score": output.get("average_selected_score"),
        "best_selected_score": output.get("best_selected_score"),
        "path_agreement": java.get("quality", {}).get("path_agreement_fraction"),
        "score_agreement": java.get("quality", {}).get("score_agreement_fraction"),
        "score_regret": java.get("quality", {}).get("integrated_score_regret"),
        "output_checksum": java.get("output", {}).get("profile_checksum"),
        "input_hash": record["input_hash"],
    }


def collect(run_root: Path) -> dict[str, Any]:
    raw = run_root / "raw"
    records = read_records(raw) if raw.is_dir() else []
    normalized = run_root / "normalized"
    normalized.mkdir(parents=True, exist_ok=True)
    write_jsonl(normalized / "run_records.jsonl", records)
    rows = [flatten(record) for record in records]
    fields = list(rows[0]) if rows else ["run_id"]
    csv_path = normalized / "run_records.csv"
    with csv_path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    raw_files = sorted(raw.rglob("*.jsonl")) if raw.is_dir() else []
    report = {
        "schema_version": 1,
        "records": len(records),
        "raw_files": len(raw_files),
        "raw_checksum": sha256_files(raw_files, raw) if raw_files else None,
        "parquet_written": False,
        "parquet_note": "Install pyarrow for optional Parquet export; canonical JSONL and CSV are complete.",
    }
    atomic_write_json(normalized / "collection_report.json", report)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    try:
        design = load_design(args.config)
        run_root = repo_path(design["paths"]["results_root"]) / args.run_id
        report = collect(run_root)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"collection: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

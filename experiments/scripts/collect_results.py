#!/usr/bin/env python3
"""Collect immutable raw job records into normalized JSONL and CSV."""
from __future__ import annotations

import argparse
import copy
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


def read_reused_records(run_root: Path) -> list[dict[str, Any]]:
    """Project immutable historical rows into the normalized effective set."""
    index_path = (
        run_root / "plan" / "reconciliation" / "effective_result_index.jsonl"
    )
    if not index_path.is_file():
        return []
    references = [
        json.loads(line)
        for line in index_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    projected: list[dict[str, Any]] = []
    for reference in references:
        source_path = Path(str(reference["source_record_path"]))
        if not source_path.is_absolute():
            source_path = repo_path(source_path)
        # Reconciliation records point to one immutable raw file.  Restrict to
        # that file rather than accepting a same-named run ID elsewhere.
        direct_rows = [
            json.loads(line)
            for line in source_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        matching = [
            row
            for row in direct_rows
            if (row.get("java_record") or {}).get("run", {}).get("run_id")
            == reference.get("source_record_id")
        ]
        if len(matching) != 1:
            raise ValueError(
                "reused result reference does not resolve to one source row: "
                + source_path.as_posix()
            )
        record = copy.deepcopy(matching[0])
        record.update({
            "run_id": run_root.name,
            "study_id": "T03",
            "job_id": reference["target_job_id"],
            "input_hash": reference["target_input_hash"],
            "result_origin": "REUSED_HISTORICAL",
            "reuse_provenance": reference,
        })
        projected.append(record)
    return projected


def flatten(record: dict[str, Any]) -> dict[str, Any]:
    java = record.get("java_record") or {}
    configuration = java.get("configuration", {})
    status = java.get("status", {})
    counters = java.get("counters", {})
    memory = java.get("memory_bytes", {})
    output = java.get("output", {})
    peak_rss = memory.get("peak_rss")
    start_rss = memory.get("start_rss")
    algorithm_id = configuration.get("algorithm")
    algorithm_label = {
        "pace-b": "PACE-B",
        "iscope": "iSCOPE",
        "allfp": "allFP",
        "interval-best": "interval-best (legacy)",
        "rpq": "RPQ (historical)",
        "pace-x": "PACE-X",
    }.get(algorithm_id, algorithm_id)
    return {
        "run_id": record["run_id"],
        "study_id": record["study_id"],
        "job_id": record["job_id"],
        "trial_id": record["trial_id"],
        "completion_status": record["completion_status"],
        "result_origin": record.get("result_origin", "NEW_FIVE_SECOND_RUN"),
        "algorithm_status": status.get("status_code"),
        "execution_policy": status.get("execution_policy"),
        "partial_output_policy": status.get("partial_output_policy"),
        "dataset_id": java.get("dataset", {}).get("dataset_id"),
        "query_id": java.get("query", {}).get("query_id"),
        "algorithm_id": algorithm_id,
        "algorithm_label": algorithm_label,
        "reporting_track": (
            "T03-B_PREFERENCE_FREE_REFERENCE"
            if algorithm_id == "allfp"
            else "T03-A_PREFERENCE_AWARE"
            if algorithm_id in {"pace-b", "iscope"}
            else "HISTORICAL_OR_EXACTNESS"
        ),
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
        "lower_bound_time_ns": java.get("timing_ns", {}).get(
            "lower_bound_preprocessing"
        ),
        "functional_composition_time_ns": java.get("timing_ns", {}).get(
            "functional_composition"
        ),
        "functional_search_control_time_ns": java.get("timing_ns", {}).get(
            "functional_search_control"
        ),
        "envelope_time_ns": java.get("timing_ns", {}).get(
            "envelope_extraction"
        ),
        "posthoc_scoring_time_ns": java.get("timing_ns", {}).get(
            "posthoc_scoring"
        ),
        "allfp_budget_projection_time_ns": java.get(
            "timing_ns", {}
        ).get("allfp_budget_projection"),
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
        "distinct_selected_paths": output.get("distinct_selected_paths"),
        "path_changes": output.get("path_changes"),
        "feasible_coverage": output.get("feasible_coverage_fraction"),
        "average_selected_score": output.get("average_selected_score"),
        "average_selected_travel_time": output.get(
            "average_selected_travel_time"
        ),
        "best_selected_score": output.get("best_selected_score"),
        "path_agreement": java.get("quality", {}).get("path_agreement_fraction"),
        "score_agreement": java.get("quality", {}).get("score_agreement_fraction"),
        "score_regret": java.get("quality", {}).get("integrated_score_regret"),
        "output_checksum": java.get("output", {}).get("profile_checksum"),
        "output_feasible": counters.get("output_feasible"),
        "output_loopless": counters.get("output_loopless"),
        "output_validation_contract": counters.get(
            "output_validation_contract"
        ),
        "posthoc_budget_feasible_coverage_fraction": counters.get(
            "posthoc_budget_feasible_coverage_fraction"
        ),
        "allfp_search_executed": counters.get("allfp_search_executed"),
        "allfp_budget_variant_reuse_hit": counters.get(
            "allfp_budget_variant_reuse_hit"
        ),
        "allfp_search_source_query_id": counters.get(
            "allfp_search_source_query_id"
        ),
        "input_hash": record["input_hash"],
    }


def collect(run_root: Path) -> dict[str, Any]:
    raw = run_root / "raw"
    records = read_records(raw) if raw.is_dir() else []
    reused = read_reused_records(run_root)
    raw_ids = {record.get("job_id") for record in records}
    overlap = raw_ids & {record.get("job_id") for record in reused}
    if overlap:
        raise ValueError(
            "jobs have both new raw output and historical reuse references: "
            + ", ".join(sorted(str(value) for value in overlap)[:10])
        )
    records.extend(reused)
    records.sort(key=lambda record: str(record.get("job_id")))
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
        "new_raw_records": len(records) - len(reused),
        "reused_historical_records": len(reused),
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

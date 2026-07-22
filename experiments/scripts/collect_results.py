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
    return {
        "run_id": record["run_id"],
        "study_id": record["study_id"],
        "job_id": record["job_id"],
        "trial_id": record["trial_id"],
        "completion_status": record["completion_status"],
        "dataset_id": java.get("dataset", {}).get("dataset_id"),
        "query_id": java.get("query", {}).get("query_id"),
        "algorithm_id": java.get("configuration", {}).get("algorithm"),
        "variant_id": java.get("configuration", {}).get("ablation"),
        "threads": java.get("configuration", {}).get("threads"),
        "wall_time_ns": java.get("timing_ns", {}).get("query_total"),
        "peak_rss_bytes": java.get("memory_bytes", {}).get("peak_rss"),
        "profile_cells_total": java.get("output", {}).get("final_profile_intervals"),
        "feasible_coverage": java.get("output", {}).get("feasible_coverage_fraction"),
        "path_agreement": java.get("quality", {}).get("path_agreement_fraction"),
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

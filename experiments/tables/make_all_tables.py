#!/usr/bin/env python3
"""Generate T1-T8 CSV/LaTeX tables strictly from validated run artifacts."""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import sys
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_text
from experiments.scripts.common.config import load_design, repo_path


TITLES = {
    "T1": "Hardware, software, seeds, limits, and trials",
    "T2": "Dataset properties and preprocessing",
    "T3": "Query workload and selected PACE-B parameters",
    "T4": "Algorithm capabilities and exactness scope",
    "T5": "Correctness and oracle agreement",
    "T6": "Runtime, memory, and completion",
    "T7": "Profile compactness, coverage, and score",
    "T8": "Ablation effects and internal counters",
}


def _escape(value: Any) -> str:
    return str(value).replace("_", "\\_").replace("%", "\\%").replace("&", "\\&")


def _write(output: Path, table_id: str, rows: list[dict[str, Any]]) -> None:
    rows = rows or [{"status": "no applicable validated rows"}]
    fields = list(dict.fromkeys(field for row in rows for field in row))
    with (output / f"{table_id.lower()}.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    column_spec = "l" * len(fields)
    lines = [
        "\\begin{table}", f"\\caption{{{_escape(TITLES[table_id])}}}",
        f"\\begin{{tabular}}{{{column_spec}}}",
        " & ".join(_escape(field) for field in fields) + " \\\\", "\\hline",
    ]
    lines.extend(" & ".join(_escape(row.get(field, "")) for field in fields) + " \\\\" for row in rows)
    lines.extend(["\\end{tabular}", "\\end{table}", ""])
    atomic_write_text(output / f"{table_id.lower()}.tex", "\n".join(lines))


def _aggregate_rows(aggregates: list[dict[str, Any]], studies: set[str], fields: list[str]) -> list[dict[str, Any]]:
    return [{field: row.get(field) for field in fields} for row in aggregates if row["study_id"] in studies]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    design = load_design(args.config)
    root = repo_path(design["paths"]["results_root"]) / args.run_id
    validation = json.loads((root / "release" / "validation_report.json").read_text(encoding="utf-8"))
    if not validation.get("passed"):
        raise SystemExit("tables are blocked because result validation did not pass")
    aggregates = [json.loads(line) for line in (root / "summaries" / "aggregate_records.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    environment = json.loads((root / "provenance" / "environment.json").read_text(encoding="utf-8"))
    preflight = json.loads((root / "provenance" / "preflight.json").read_text(encoding="utf-8"))
    counts = json.loads((root / "plan" / "matrices" / "matrix_counts.json").read_text(encoding="utf-8"))
    output = root / "tables"
    output.mkdir(parents=True, exist_ok=True)

    t1 = [
        {"item": "git_commit", "value": environment["git"]["commit"]},
        {"item": "git_dirty", "value": environment["git"]["dirty"]},
        {"item": "operating_system", "value": environment["operating_system"]},
        {"item": "processor", "value": environment["processor"]},
        {"item": "logical_cores", "value": environment["logical_cores"]},
        {"item": "java", "value": environment["java"]},
        {"item": "maven", "value": environment["maven"]},
        {"item": "timeout_seconds", "value": design["resources"]["timeout_seconds"]},
        {"item": "memory_limit_mb", "value": design["resources"].get("memory_limit_mb")},
        {"item": "measured_trials", "value": design["protocol"]["measured_trials"]},
    ]
    t2 = [{key: row.get(key) for key in ("dataset_id", "nodes", "edges", "support_end", "disk_bytes", "graph_checksum")} for row in preflight["datasets"]]
    t3 = [
        {"item": "datasets", "value": ",".join(design["datasets"])},
        {"item": "pair_splits", "value": json.dumps(design.get("workload", {}).get("pair_splits"), sort_keys=True)},
        {"item": "time_centers_minutes", "value": json.dumps(design.get("workload", {}).get("time_centers_minutes"))},
        {"item": "total_planned_jobs", "value": counts["total_jobs"]},
    ]
    algorithms = {}
    for study in design["study_definitions"]:
        for algorithm in study.get("algorithms", []):
            algorithms.setdefault(algorithm["id"], set()).add(study["study_id"])
    t4 = [{"algorithm_id": key, "studies": ",".join(sorted(value)), "exactness_note": "scope is serialized per result"} for key, value in sorted(algorithms.items())]
    common = ["study_id", "dataset_id", "algorithm_id", "planned", "successful", "completion_rate"]
    t5 = _aggregate_rows(aggregates, {"E01"}, common + ["median_path_agreement", "median_score_regret"])
    t6 = _aggregate_rows(aggregates, {"E03"}, common + ["median_wall_time_ns", "iqr_wall_time_ns", "bootstrap_95_low_ns", "bootstrap_95_high_ns", "median_peak_rss_bytes", "par2_wall_time_ns"])
    t7 = _aggregate_rows(aggregates, {"E03", "E04"}, common + ["median_profile_cells_total", "median_feasible_coverage", "median_score_regret"])
    t8 = _aggregate_rows(aggregates, {"E10"}, common + ["median_wall_time_ns", "median_peak_rss_bytes"])
    for table_id, rows in zip(TITLES, (t1, t2, t3, t4, t5, t6, t7, t8)):
        _write(output, table_id, rows)
    macros = [
        f"\\newcommand{{\\PaceRunId}}{{{_escape(args.run_id)}}}",
        f"\\newcommand{{\\PacePlannedJobs}}{{{validation['planned_jobs']}}}",
        f"\\newcommand{{\\PaceTerminalRecords}}{{{validation['terminal_records']}}}",
    ]
    atomic_write_text(output / "manuscript_macros.tex", "\n".join(macros) + "\n")
    print(json.dumps({"tables": len(TITLES), "output": str(output)}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

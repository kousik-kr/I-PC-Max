#!/usr/bin/env python3
"""Generate T1-T12 CSV/LaTeX tables strictly from one validated run."""
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
    "T3": "Query counts and selected PACE-B parameters",
    "T4": "Algorithm capabilities and exactness scope",
    "T5": "Correctness and oracle agreement",
    "T6": "Bounded calibration",
    "T7": "Main runtime, memory, and completion",
    "T8": "Profile compactness, coverage, and score",
    "T9": "Candidate-generation diagnostics",
    "T10": "Ablation effects and internal counters",
    "T11": "Parallel scaling",
    "T12": "Robustness across seeds",
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


def _smoke_sample(
    rows: list[dict[str, Any]],
    aggregates: list[dict[str, Any]],
    fields: list[str],
    intended_studies: set[str],
    smoke: bool,
) -> list[dict[str, Any]]:
    if rows or not smoke:
        return rows
    return [
        {
            **{field: row.get(field) for field in fields},
            "sample_only": True,
            "sample_source_study": "E01",
            "intended_studies": ",".join(sorted(intended_studies)),
        }
        for row in aggregates
        if row["study_id"] == "E01"
    ]


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
    planned = []
    for path in sorted((root / "plan" / "matrices").glob("e*.jsonl")):
        planned.extend(
            json.loads(line)
            for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        )
    output = root / "tables"
    output.mkdir(parents=True, exist_ok=True)

    t1 = [
        {"item": "git_commit", "value": environment["git"]["commit"]},
        {"item": "git_dirty", "value": environment["git"]["dirty"]},
        {"item": "operating_system", "value": environment["operating_system"]},
        {"item": "processor", "value": environment["processor"]},
        {"item": "logical_cores", "value": environment["logical_cores"]},
        {"item": "physical_cores", "value": environment.get("physical_cores")},
        {"item": "java", "value": environment["java"]},
        {"item": "maven", "value": environment["maven"]},
        {"item": "timeout_seconds", "value": design["resources"]["timeout_seconds"]},
        {"item": "memory_limit_mb", "value": design["resources"].get("memory_limit_mb")},
        {"item": "measured_trials", "value": design["protocol"]["measured_trials"]},
    ]
    t2 = [{
        key: row.get(key)
        for key in (
            "dataset_id",
            "nodes",
            "edges",
            "support_end",
            "disk_bytes",
            "dataset_payload_checksum",
            "checksum_scope_version",
        )
    } for row in preflight["datasets"]]
    query_groups: dict[tuple[str, str, str], dict[str, set[Any]]] = {}
    for row in planned:
        key = (row["study_id"], row["dataset_id"], row["split"])
        bucket = query_groups.setdefault(key, {"pairs": set(), "queries": set(), "jobs": set()})
        bucket["pairs"].add(row["pair_index"])
        bucket["queries"].add(row["query_id"])
        bucket["jobs"].add(row["job_id"])
    t3 = [
        {
            "study_id": key[0], "dataset_id": key[1], "split": key[2],
            "base_pairs": len(value["pairs"]), "derived_query_instances": len(value["queries"]),
            "planned_jobs": len(value["jobs"]),
        }
        for key, value in sorted(query_groups.items())
    ]
    if not t3:
        t3 = [{"study_id": "none", "dataset_id": "none", "split": "none",
               "base_pairs": 0, "derived_query_instances": 0, "planned_jobs": 0}]
    resolved = root / "provenance" / "resolved_pace_b.yaml"
    if resolved.is_file():
        t3.append({
            "study_id": "PACE-B-default", "dataset_id": "NY-pilot", "split": "pilot",
            "base_pairs": "", "derived_query_instances": "",
            "planned_jobs": "", "resolved_configuration": resolved.read_text(encoding="utf-8").strip(),
        })
    algorithms = {}
    for study in design["study_definitions"]:
        for algorithm in study.get("algorithms", []):
            algorithms.setdefault(algorithm["id"], set()).add(study["study_id"])
    t4 = [{"algorithm_id": key, "studies": ",".join(sorted(value)), "exactness_note": "scope is serialized per result"} for key, value in sorted(algorithms.items())]
    common = ["study_id", "dataset_id", "algorithm_id", "variant_id", "axis_json",
              "planned", "successful", "completion_rate", "timeout_rate", "oom_rate",
              "cap_trigger_rate", "exactness_status_counts"]
    t5 = _aggregate_rows(
        aggregates, {"E01"},
        common + ["median_path_agreement", "median_score_agreement", "median_score_regret",
                  "checksum_equal_across_trials"],
    )
    t6 = _aggregate_rows(
        aggregates, {"E02"},
        common + ["pivot_limit_l", "connector_limit_kc", "frontier_limit_kf",
                  "median_wall_time_ns", "median_peak_rss_bytes",
                  "median_path_agreement", "median_score_agreement",
                  "median_score_regret"],
    )
    t7 = _aggregate_rows(
        aggregates, {"E03"},
        common + ["median_wall_time_ns", "median_cpu_time_ns", "iqr_wall_time_ns",
                  "bootstrap_95_low_ns", "bootstrap_95_high_ns", "par2_wall_time_ns",
                  "median_peak_rss_bytes", "median_incremental_rss_bytes"],
    )
    t8 = _aggregate_rows(
        aggregates, {"E03", "E04"},
        common + ["median_profile_cells_total", "median_feasible_coverage",
                  "median_average_selected_score", "median_best_selected_score",
                  "median_score_regret"],
    )
    t9 = _aggregate_rows(
        aggregates, {"E02", "E09"},
        common + ["diagnostic", "budget_overhead", "median_corridor_nodes",
                  "median_corridor_edges", "median_selected_pivots",
                  "median_connector_calls", "median_connector_expansions",
                  "median_valid_connectors", "median_candidates_generated",
                  "median_candidates_retained", "median_total_candidate_work",
                  "median_memo_lookups", "median_memo_hits", "median_memo_waits",
                  "connector_cap_hit_rate", "query_work_cap_hit_rate"],
    )
    t10 = _aggregate_rows(
        aggregates, {"E10"},
        common + ["median_wall_time_ns", "median_peak_rss_bytes",
                  "median_total_candidate_work", "median_score_regret",
                  "median_memo_hit_rate"],
    )
    t11 = _aggregate_rows(
        aggregates, {"E11"},
        common + ["threads", "median_observed_workers", "speedup",
                  "parallel_efficiency", "worker_utilization",
                  "checksum_equal_across_trials"],
    )
    t12 = _aggregate_rows(
        aggregates, {"E12"},
        common + ["graph_seed", "median_wall_time_ns", "median_peak_rss_bytes",
                  "median_score_regret", "checksum_equal_across_trials"],
    )
    smoke = bool(design.get("smoke"))
    t6 = _smoke_sample(
        t6, aggregates,
        common + ["median_wall_time_ns", "median_peak_rss_bytes",
                  "median_path_agreement", "median_score_regret"],
        {"E02"}, smoke,
    )
    t7 = _smoke_sample(
        t7, aggregates,
        common + ["median_wall_time_ns", "par2_wall_time_ns",
                  "median_peak_rss_bytes", "median_incremental_rss_bytes"],
        {"E03"}, smoke,
    )
    t8 = _smoke_sample(
        t8, aggregates,
        common + ["median_profile_cells_total", "median_feasible_coverage"],
        {"E03", "E04"}, smoke,
    )
    t9 = _smoke_sample(
        t9, aggregates,
        common + ["median_connector_expansions", "median_valid_connectors"],
        {"E02", "E09"}, smoke,
    )
    t10 = _smoke_sample(
        t10, aggregates,
        common + ["median_wall_time_ns", "median_total_candidate_work"],
        {"E10"}, smoke,
    )
    t11 = _smoke_sample(
        t11, aggregates,
        common + ["threads", "speedup", "parallel_efficiency"],
        {"E11"}, smoke,
    )
    t12 = _smoke_sample(
        t12, aggregates,
        common + ["graph_seed", "median_wall_time_ns"],
        {"E12"}, smoke,
    )
    for table_id, rows in zip(
        TITLES, (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12)
    ):
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

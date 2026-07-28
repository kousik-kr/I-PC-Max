#!/usr/bin/env python3
"""Generate the final F1-F10 set from validated aggregate records."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.plots.common import make_figure
from experiments.scripts.common.config import load_design, repo_path


SPECS = (
    ("F1", "Runtime versus dataset", {"E03"}, ("median_wall_time_ns", "par2_wall_time_ns"), True),
    ("F2", "Absolute and incremental memory versus dataset", {"E03"}, ("median_peak_rss_bytes", "median_incremental_rss_bytes"), True),
    ("F3", "Completion, timeout, OOM, and cap rates", {"E03"}, ("completion_rate", "timeout_rate", "oom_rate", "cap_trigger_rate"), False),
    ("F4", "Profile compactness and feasible coverage", {"E03", "E04"}, ("median_profile_cells_total", "median_feasible_coverage"), True),
    ("F5", "Bounded L/Kc/Kf quality-cost Pareto evidence", {"E02"}, ("median_wall_time_ns", "median_peak_rss_bytes", "median_path_agreement", "median_score_regret"), True),
    ("F6", "Window, budget, theta, and density sensitivity", {"E05", "E06", "E07", "E08"}, ("median_wall_time_ns", "cap_trigger_rate", "median_score_regret"), True),
    ("F7", "Connector work and caps versus budget", {"E09"}, ("median_connector_expansions", "median_valid_connectors", "connector_cap_hit_rate", "median_wall_time_ns", "median_peak_rss_bytes", "median_score_regret"), True),
    ("F8", "Component ablation", {"E10"}, ("median_wall_time_ns", "median_total_candidate_work", "median_score_regret"), True),
    ("F9", "Parallel speedup, efficiency, and utilization", {"E11"}, ("speedup", "parallel_efficiency", "worker_utilization"), False),
    ("F10", "Robustness across graph and temporal seeds", {"E12"}, ("median_wall_time_ns", "median_score_regret", "completion_rate"), True),
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    design = load_design(args.config)
    root = repo_path(design["paths"]["results_root"]) / args.run_id
    validation = json.loads((root / "release" / "validation_report.json").read_text(encoding="utf-8"))
    if not validation.get("passed"):
        raise SystemExit("plots are blocked because result validation did not pass")
    summary = root / "summaries" / "aggregate_records.jsonl"
    outputs = []
    for figure_id, title, studies, metrics, log_scale in SPECS:
        sample_only = bool(design.get("smoke"))
        outputs.append(make_figure(
            summary, root / "figures",
            figure_id,
            f"SMOKE FIXTURE SAMPLE — {title}" if sample_only else title,
            {"E01"} if sample_only else studies,
            metrics,
            log_scale=log_scale,
            sample_only=sample_only,
            intended_study_ids=studies,
        ))
    print(json.dumps({"figures": len(outputs), "output": str(root / 'figures')}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

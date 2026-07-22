#!/usr/bin/env python3
"""Generate F1-F8 from validated aggregate records."""
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
    ("F1", "End-to-end runtime", {"E03"}, "median_wall_time_ns"),
    ("F2", "Peak memory", {"E03"}, "median_peak_rss_bytes"),
    ("F3", "Profile compactness and coverage", {"E04", "E03"}, "median_profile_cells_total"),
    ("F4", "PACE-B quality-cost tradeoff", {"E02", "E09"}, "median_wall_time_ns"),
    ("F5", "Parameter sensitivity", {"E05", "E06", "E07", "E08"}, "median_wall_time_ns"),
    ("F6", "Ablation effects", {"E10"}, "median_wall_time_ns"),
    ("F7", "Parallel scaling", {"E11"}, "median_wall_time_ns"),
    ("F8", "Synthetic-seed robustness", {"E12"}, "median_wall_time_ns"),
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
    outputs = [make_figure(summary, root / "figures", *spec) for spec in SPECS]
    print(json.dumps({"figures": len(outputs), "output": str(root / 'figures')}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

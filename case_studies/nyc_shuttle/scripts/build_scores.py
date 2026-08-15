#!/usr/bin/env python3
"""Build MTA schedule-derived active transit-corridor score functions."""

import argparse
import json
import sys
from pathlib import Path

from nyc_case_study.common import CASE_ROOT, REPO_ROOT, CaseStudyError, load_config
from nyc_case_study.mapping import latest_download
from nyc_case_study.temporal import build_scores


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=CASE_ROOT / "config/case_study.yaml")
    args = parser.parse_args()
    try:
        config = load_config(args.config)
        pages = latest_download(CASE_ROOT / "raw/mta_bus_schedules_2026", "csv")
        result = build_scores(
            REPO_ROOT / config["processed_graph"],
            CASE_ROOT / "intermediate/mta_shape_to_dimacs_edges.parquet",
            pages, CASE_ROOT / "reports/score_definition.md", config,
        )
    except (CaseStudyError, OSError, ValueError, ImportError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 2
    print(json.dumps({"status": "COMPLETE", **result}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Build real-DOT-calibrated FIFO functions in the production graph format."""

import argparse
import json
import sys
from pathlib import Path

from nyc_case_study.common import CASE_ROOT, REPO_ROOT, CaseStudyError, load_config
from nyc_case_study.temporal import build_travel_graph


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=CASE_ROOT / "config/case_study.yaml")
    args = parser.parse_args()
    try:
        config = load_config(args.config)
        result = build_travel_graph(
            REPO_ROOT / config["source_graph"],
            CASE_ROOT / "intermediate/dot_traffic_15min.parquet",
            CASE_ROOT / "intermediate/dot_link_to_dimacs_edges.parquet",
            CASE_ROOT / "intermediate/dimacs_centerline_matches.parquet",
            REPO_ROOT / config["processed_graph"],
            CASE_ROOT / "reports/temporal_profile_quality.md", config,
        )
    except (CaseStudyError, OSError, ValueError, ImportError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 2
    print(json.dumps({"status": "COMPLETE", **result}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

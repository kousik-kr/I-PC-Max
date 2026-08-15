#!/usr/bin/env python3
"""Select deterministic, nontrivial terminal pairs from official MTA data."""

import argparse
import json
import sys
from pathlib import Path

from nyc_case_study.common import CASE_ROOT, REPO_ROOT, CaseStudyError, load_config
from nyc_case_study.endpoints import select_terminal_pairs
from nyc_case_study.mapping import latest_download


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=CASE_ROOT / "config/case_study.yaml")
    args = parser.parse_args()
    try:
        config = load_config(args.config)
        result = select_terminal_pairs(
            CASE_ROOT / "intermediate/dimacs_ny_nodes.parquet",
            REPO_ROOT / config["source_graph"] / "edges_static.csv.gz",
            latest_download(CASE_ROOT / "raw/mta_current_bus_routes", "geojson"),
            latest_download(CASE_ROOT / "raw/mta_bus_schedules_2026", "csv"),
            CASE_ROOT / "manifests/nyc_terminal_pairs.csv", config,
        )
    except (CaseStudyError, OSError, ValueError, ImportError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 2
    print(json.dumps({"status": "COMPLETE", **result}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

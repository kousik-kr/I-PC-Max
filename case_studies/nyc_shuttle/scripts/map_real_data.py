#!/usr/bin/env python3
"""Run Centerline, MTA route-shape, and DOT-link mappings independently."""

import argparse
import json
import sys
from pathlib import Path

from nyc_case_study.common import CASE_ROOT, CaseStudyError, load_config
from nyc_case_study.mapping import latest_download, map_centerline, map_dot_links, map_mta_shapes


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=CASE_ROOT / "config/case_study.yaml")
    parser.add_argument("--stage", choices=("all", "centerline", "mta", "dot"), default="all")
    args = parser.parse_args()
    config = load_config(args.config)
    edges = CASE_ROOT / "intermediate/dimacs_ny_edges.parquet"
    completed = {}
    try:
        if args.stage in {"all", "centerline"}:
            completed["centerline"] = map_centerline(
                edges, latest_download(CASE_ROOT / "raw/centerline", "geojson"),
                CASE_ROOT / "intermediate/dimacs_centerline_matches.parquet",
                CASE_ROOT / "reports/centerline_mapping_quality.md", config,
            )
        if args.stage in {"all", "mta"}:
            completed["mta"] = map_mta_shapes(
                edges, latest_download(CASE_ROOT / "raw/mta_current_bus_routes", "geojson"),
                CASE_ROOT / "intermediate/mta_shape_to_dimacs_edges.parquet",
                CASE_ROOT / "reports/mta_route_mapping_quality.md", config,
            )
        if args.stage in {"all", "dot"}:
            completed["dot"] = map_dot_links(
                edges, CASE_ROOT / "intermediate/dot_traffic_15min.parquet",
                CASE_ROOT / "intermediate/dot_link_to_dimacs_edges.parquet",
                CASE_ROOT / "reports/dot_link_mapping_quality.md", config,
            )
    except (CaseStudyError, OSError, ImportError, ValueError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 2
    print(json.dumps({"status": "COMPLETE", "stages": completed}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

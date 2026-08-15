#!/usr/bin/env python3
"""Convert the existing DIMACS NY node/arc tables to GeoParquet."""

import argparse
import json
import sys
from pathlib import Path

from nyc_case_study.common import CASE_ROOT, REPO_ROOT, CaseStudyError, load_config
from nyc_case_study.mapping import prepare_dimacs


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=CASE_ROOT / "config/case_study.yaml")
    parser.add_argument("--source-graph", type=Path)
    parser.add_argument("--output", type=Path, default=CASE_ROOT / "intermediate")
    args = parser.parse_args()
    try:
        config = load_config(args.config)
        source = args.source_graph or REPO_ROOT / config["source_graph"]
        result = prepare_dimacs(source, args.output, scale=int(config["coordinate_scale"]),
                                projected_crs=config["projected_crs"])
    except (CaseStudyError, OSError, ImportError, ValueError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 2
    print(json.dumps({"status": "COMPLETE", **result}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
import argparse
import json
import sys
from pathlib import Path
from nyc_case_study.analysis import analyze
from nyc_case_study.common import CASE_ROOT, CaseStudyError

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=CASE_ROOT / "results/nyc_case_results.jsonl")
    args = parser.parse_args()
    try:
        result = analyze(args.input, CASE_ROOT / "results/summary.json", CASE_ROOT / "results/summary_by_rho.csv")
    except (CaseStudyError, OSError, ValueError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr); return 2
    print(json.dumps({"status": "COMPLETE", **result}, sort_keys=True)); return 0
if __name__ == "__main__": raise SystemExit(main())

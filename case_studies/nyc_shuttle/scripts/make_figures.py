#!/usr/bin/env python3
import json
import sys
from nyc_case_study.analysis import make_figure
from nyc_case_study.common import CASE_ROOT, CaseStudyError

def main() -> int:
    try:
        result = make_figure(CASE_ROOT / "results/nyc_case_results.jsonl",
                             CASE_ROOT / "intermediate/dimacs_ny_edges.parquet",
                             CASE_ROOT / "figures/nyc_representative_query.pdf",
                             CASE_ROOT / "figures/nyc_representative_query.png")
    except (CaseStudyError, OSError, ValueError, ImportError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr); return 2
    print(json.dumps({"status": "COMPLETE", **result}, sort_keys=True)); return 0
if __name__ == "__main__": raise SystemExit(main())

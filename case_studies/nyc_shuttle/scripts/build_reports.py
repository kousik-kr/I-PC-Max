#!/usr/bin/env python3
import json
import sys
from nyc_case_study.analysis import build_reports
from nyc_case_study.common import CASE_ROOT, CaseStudyError

def main() -> int:
    summary = CASE_ROOT / "results/summary.json"
    try: result = build_reports(summary if summary.exists() else None)
    except (CaseStudyError, OSError, ValueError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr); return 2
    print(json.dumps({"status": "COMPLETE", **result}, sort_keys=True)); return 0
if __name__ == "__main__": raise SystemExit(main())

#!/usr/bin/env python3
import json
import sys
from nyc_case_study.analysis import make_tables
from nyc_case_study.common import CASE_ROOT, CaseStudyError

def main() -> int:
    try: result = make_tables(CASE_ROOT / "results/summary.json", CASE_ROOT / "reports/generated")
    except (CaseStudyError, OSError, ValueError, ImportError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr); return 2
    print(json.dumps({"status": "COMPLETE", **result}, sort_keys=True)); return 0
if __name__ == "__main__": raise SystemExit(main())

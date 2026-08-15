#!/usr/bin/env python3
"""Download one official Socrata dataset without silent truncation."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from nyc_case_study.common import CaseStudyError
from nyc_case_study.socrata import SocrataClient, download_dataset, download_geospatial_export


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("--domain", required=True)
    result.add_argument("--dataset-id", required=True)
    result.add_argument("--format", choices=("json", "csv", "geojson"), default="json")
    result.add_argument("--output", type=Path, required=True)
    result.add_argument("--page-size", type=int, default=50000)
    result.add_argument("--where")
    result.add_argument("--select")
    result.add_argument("--app-token-env", default="NYC_SOCRATA_APP_TOKEN")
    result.add_argument("--geospatial-export", action="store_true")
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        client = SocrataClient(args.domain, args.dataset_id, app_token_env=args.app_token_env)
        manifest = download_geospatial_export(client, args.output) if args.geospatial_export else download_dataset(
            client, args.output, output_format=args.format, page_size=args.page_size,
            where=args.where, select=args.select)
    except CaseStudyError as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 2
    print(json.dumps({"status": "COMPLETE", "dataset_id": args.dataset_id,
                      "rows": manifest["row_count"],
                      "checksum": manifest["content_checksum"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

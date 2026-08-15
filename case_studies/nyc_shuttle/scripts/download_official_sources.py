#!/usr/bin/env python3
"""Download configured required Socrata sources from official domains."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from nyc_case_study.common import CASE_ROOT, CaseStudyError, atomic_write_json, load_config
from nyc_case_study.socrata import SocrataClient, download_dataset, download_geospatial_export


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources", type=Path, default=CASE_ROOT / "config/sources.yaml")
    parser.add_argument("--output", type=Path, default=CASE_ROOT / "raw")
    parser.add_argument("--source", action="append", help="configured source key; repeatable")
    parser.add_argument("--page-size", type=int, default=50000)
    args = parser.parse_args()
    config = load_config(args.sources)
    selected = set(args.source or ())
    completed: list[dict[str, object]] = []
    try:
        for key, source in config["sources"].items():
            if selected and key not in selected:
                continue
            method = str(source.get("retrieval_method", ""))
            if not method.startswith("socrata_"):
                continue
            client = SocrataClient(
                source["source_domain"], source.get("resource_view_id", source["view_id"]),
                app_token_env="NYC_SOCRATA_APP_TOKEN",
            )
            if method == "socrata_geospatial_export":
                manifest = download_geospatial_export(client, args.output / key)
            else:
                manifest = download_dataset(
                    client, args.output / key, output_format=source["format"],
                    page_size=args.page_size, where=source.get("where"),
                )
            manifest["configured_catalog_view_id"] = source["view_id"]
            manifest["configured_landing_page_url"] = source["landing_page_url"]
            if source.get("resource_view_id"):
                manifest["official_underlying_resource_view_id"] = source["resource_view_id"]
            manifest_path = Path(manifest["pages"][0]["path"]).parent / "download_manifest.json"
            atomic_write_json(manifest_path, manifest)
            completed.append({"source": key, "rows": manifest["row_count"],
                              "checksum": manifest["content_checksum"]})
    except (KeyError, TypeError, CaseStudyError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 2
    if selected and selected.difference({item["source"] for item in completed}):
        print(f"ERROR: unknown or non-Socrata source(s): {sorted(selected)}", file=sys.stderr)
        return 2
    print(json.dumps({"status": "COMPLETE", "sources": completed}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

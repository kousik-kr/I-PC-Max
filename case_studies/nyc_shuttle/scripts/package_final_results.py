#!/usr/bin/env python3
"""Copy a complete, validated NYC result bundle into the designated final folder."""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from collections import Counter
from pathlib import Path

from nyc_case_study.common import (
    CASE_ROOT, REPO_ROOT, CaseStudyError, atomic_write_json, atomic_write_text,
    git_revision, sha256_file, utc_now,
)


def jsonl_ids(path: Path) -> list[str]:
    return [json.loads(line)["query_id"] for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip()]


def copy_atomic(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.tmp")
    shutil.copy2(source, temporary)
    temporary.replace(destination)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--destination", type=Path,
        default=REPO_ROOT / "experiments/results/Final-result/NYC-real-shuttle")
    args = parser.parse_args()
    result_path = CASE_ROOT / "results/nyc_case_results.jsonl"
    query_path = CASE_ROOT / "manifests/nyc_queries.jsonl"
    try:
        if not result_path.exists() or not query_path.exists():
            raise CaseStudyError("query or result JSONL is missing")
        result_ids = jsonl_ids(result_path)
        query_ids = jsonl_ids(query_path)
        if len(result_ids) != len(set(result_ids)):
            raise CaseStudyError("result JSONL contains duplicate query IDs")
        if set(result_ids) != set(query_ids):
            raise CaseStudyError(
                f"exact batch is incomplete: {len(result_ids)}/{len(query_ids)} query IDs present")
        required = [
            CASE_ROOT / "results/summary.json",
            CASE_ROOT / "results/summary_by_rho.csv",
            CASE_ROOT / "reports/nyc_case_study_data_quality.md",
            CASE_ROOT / "reports/nyc_case_study_findings.md",
            CASE_ROOT / "reports/generated/table_nyc_construction.tex",
            CASE_ROOT / "reports/generated/table_nyc_results.tex",
        ]
        missing = [path for path in required if not path.exists()]
        if missing:
            raise CaseStudyError(f"analysis/report artifacts are missing: {missing}")
        groups = {
            "results": [CASE_ROOT / "results/nyc_case_results.jsonl",
                        CASE_ROOT / "results/summary.json",
                        CASE_ROOT / "results/summary_by_rho.csv"],
            "reports": sorted((CASE_ROOT / "reports").glob("*.md")),
            "tables": sorted((CASE_ROOT / "reports/generated").glob("*.tex")),
            "figures": sorted((CASE_ROOT / "figures").glob("nyc_representative_query.*")),
            "manifests": sorted((CASE_ROOT / "manifests").glob("nyc_*")),
            "config": sorted((CASE_ROOT / "config").glob("*.yaml")),
            "schemas": sorted((CASE_ROOT / "schemas").glob("*.json")),
            "provenance": [CASE_ROOT / "processed/NYC-REAL/manifest.json",
                           REPO_ROOT / "docs/nyc_case_study_audit.md",
                           CASE_ROOT / "README.md", CASE_ROOT / "requirements.txt"],
        }
        artifacts = []
        for group, paths in groups.items():
            for source in paths:
                if not source.is_file() or source.name == ".gitkeep":
                    continue
                destination = args.destination / group / source.name
                copy_atomic(source, destination)
                artifacts.append({
                    "path": str(destination.relative_to(args.destination)),
                    "size_bytes": destination.stat().st_size,
                    "sha256": sha256_file(destination),
                    "source": str(source.relative_to(REPO_ROOT)),
                })
        for source in sorted((CASE_ROOT / "raw").glob("**/download_manifest.json")):
            source_key = source.parent.parent.name
            destination = args.destination / "download_manifests" / f"{source_key}.json"
            copy_atomic(source, destination)
            artifacts.append({
                "path": str(destination.relative_to(args.destination)),
                "size_bytes": destination.stat().st_size,
                "sha256": sha256_file(destination),
                "source": str(source.relative_to(REPO_ROOT)),
            })
        statuses = Counter()
        for line in result_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                statuses[json.loads(line)["status"]] += 1
        package = {
            "schema_version": 1,
            "case_id": "NYC-REAL",
            "packaged_at_utc": utc_now(),
            "repository_revision": git_revision(),
            "query_count": len(query_ids),
            "result_count": len(result_ids),
            "status_counts": dict(sorted(statuses.items())),
            "artifact_count": len(artifacts),
            "artifacts": artifacts,
        }
        atomic_write_json(args.destination / "artifact_manifest.json", package)
        atomic_write_text(
            args.destination / "STATUS.md",
            "# NYC real-shuttle final result\n\n"
            f"- Exact batch records: {len(result_ids)}/{len(query_ids)}\n"
            f"- Status counts: `{dict(sorted(statuses.items()))}`\n"
            "- Analysis, tables, reports, manifests, schemas, and any qualifying figure are bundled.\n"
            "- Raw official downloads and the large processed graph remain in the reproducible case-study workspace.\n")
    except (CaseStudyError, OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 2
    print(json.dumps({"status": "COMPLETE", "destination": str(args.destination),
                      "artifacts": len(artifacts)}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

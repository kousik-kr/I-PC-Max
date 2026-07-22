#!/usr/bin/env python3
"""Package one validated run without inventing claims or measurements."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import sys

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_json, atomic_write_text
from experiments.scripts.common.config import load_design, repo_path
from experiments.scripts.common.hashing import sha256_file


def package(run_root: Path) -> dict:
    release = run_root / "release"
    validation_path = release / "validation_report.json"
    validation = json.loads(validation_path.read_text(encoding="utf-8"))
    if not validation.get("passed"):
        raise ValueError("release packaging is blocked because validation did not pass")
    for name in ("summaries", "tables", "figures"):
        source = run_root / name
        if not source.is_dir():
            raise FileNotFoundError(f"required release directory is missing: {source}")
        shutil.copytree(source, release / name, dirs_exist_ok=True)
    for source, target in (
        (run_root / "provenance" / "effective_config.json", release / "effective_config.yaml"),
        (run_root / "provenance" / "environment.json", release / "environment.json"),
        (run_root / "plan" / "matrices" / "matrix_counts.json", release / "matrix_counts.json"),
        (run_root / "tables" / "manuscript_macros.tex", release / "manuscript_macros.tex"),
    ):
        if source.is_file():
            shutil.copy2(source, target)
    claim_lines = [
        "# Claim Support Matrix", "",
        "This file identifies evidence locations only. It does not infer positive conclusions.", "",
        "| Research question | Evidence | Required interpretation |", "|---|---|---|",
        "| RQ1 correctness | T5 and E01 raw records | State the certified scope and every mismatch. |",
        "| RQ2 efficiency | F1, F2, T6 | Report completion rates with runtime and memory. |",
        "| RQ3 compactness | F3, T7 | Separate feasible and bottom cells. |",
        "| RQ4 parameters | F4, F5 | Treat results as one-factor sensitivity. |",
        "| RQ5 components | F6, T8 | Report paired effects; do not claim interactions. |",
        "| RQ6 parallelism | F7 | Require observed concurrency and checksum equality. |",
        "| RQ7 robustness | F8 | State the synthetic-data external-validity limit. |", "",
    ]
    atomic_write_text(release / "CLAIM_SUPPORT_MATRIX.md", "\n".join(claim_lines))
    files = sorted(path for path in release.rglob("*") if path.is_file() and path.name != "MANIFEST.json")
    manifest = {
        "schema_version": 1,
        "run_id": run_root.name,
        "files": [
            {"path": path.relative_to(release).as_posix(), "size": path.stat().st_size, "sha256": sha256_file(path)}
            for path in files
        ],
    }
    atomic_write_json(release / "MANIFEST.json", manifest)
    return {"files": len(files) + 1, "release": str(release)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    try:
        design = load_design(args.config)
        result = package(repo_path(design["paths"]["results_root"]) / args.run_id)
        print(json.dumps(result, indent=2))
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"release packaging: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

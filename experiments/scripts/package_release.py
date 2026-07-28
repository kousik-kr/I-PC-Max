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
    effective_config = json.loads(
        (run_root / "provenance" / "effective_config.json").read_text(
            encoding="utf-8"
        )
    )
    if not validation.get("passed"):
        raise ValueError("release packaging is blocked because validation did not pass")
    for figure_id in range(1, 11):
        for suffix in (".pdf", ".svg", ".png", ".json"):
            path = run_root / "figures" / f"f{figure_id}{suffix}"
            if not path.is_file():
                raise FileNotFoundError(f"required figure artifact is missing: {path}")
        sidecar = json.loads(
            (run_root / "figures" / f"f{figure_id}.json").read_text(encoding="utf-8")
        )
        if sidecar.get("figure_id") != f"F{figure_id}":
            raise ValueError(f"figure provenance ID mismatch for F{figure_id}")
    for table_id in range(1, 13):
        for suffix in (".csv", ".tex"):
            path = run_root / "tables" / f"t{table_id}{suffix}"
            if not path.is_file():
                raise FileNotFoundError(f"required table artifact is missing: {path}")
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
        (run_root / "provenance" / "resolved_pace_b.yaml", release / "resolved_pace_b.yaml"),
    ):
        if source.is_file():
            shutil.copy2(source, target)
    claim_lines = [
        "# Claim Support Matrix", "",
        "This file identifies evidence locations only. It does not infer positive conclusions.", "",
        "| Research question | Evidence | Required interpretation |", "|---|---|---|",
        "| RQ1 correctness | T5 and E01 raw records | State certified scope, corridor/score-bound checks, deterministic checksums, and every mismatch. |",
        "| RQ2 efficiency | F1-F3 and T7 | Report completion, timeout, OOM, cap rates, runtime/PAR-2, and absolute/incremental RSS together. |",
        "| RQ3 compactness | F4 and T8 | Separate profile cells, paths, feasible coverage, compression, and score outcomes. |",
        "| RQ4 bounded calibration | F5 and T6 | Use only the disjoint NY pilot and frozen resolved_pace_b.yaml; caps are policy, not tuned outcomes. |",
        "| RQ5 parameter sensitivity | F6 and E05-E08 summaries | Treat one-factor sweeps as sensitivity evidence, not interaction evidence. |",
        "| RQ6 connector work bounds | F7 and T9 | Show expansions, valid connectors, cap hits, runtime, memory, and quality across budget. |",
        "| RQ7 components | F8 and T10 | Report paired ablation effects and all failures/caps. |",
        "| RQ8 parallelism | F9 and T11 | Require observed workers, speedup, efficiency, utilization, and checksum identity. |",
        "| RQ9 robustness | F10 and T12 | State the NY/CAL seed scope and fixed-pair design. |",
        "| Reproducibility | T1-T4, VALIDATION_REPORT, MANIFEST | Bind every manuscript value to this single validated run ID. |", "",
    ]
    if effective_config.get("smoke"):
        claim_lines[3:3] = [
            "**SMOKE FIXTURE ONLY:** figures/tables marked `sample_only` validate the "
            "rendering and packaging path and are not paper evidence.",
            "",
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

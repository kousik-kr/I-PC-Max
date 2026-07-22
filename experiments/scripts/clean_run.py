#!/usr/bin/env python3
"""Safely remove one generated experiment run directory."""
from __future__ import annotations

import argparse
from pathlib import Path
import shutil
import sys

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.config import load_design, repo_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path("experiments/configs/paper_q1.yaml"))
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--confirm", action="store_true", help="required before deletion")
    args = parser.parse_args()
    if not args.confirm:
        parser.error("--confirm is required")
    if not args.run_id or args.run_id in {".", ".."} or "/" in args.run_id or "\\" in args.run_id:
        parser.error("run ID must be one plain directory name")
    design = load_design(args.config)
    results_root = repo_path(design["paths"]["results_root"]).resolve()
    target = (results_root / args.run_id).resolve()
    if target.parent != results_root:
        parser.error("resolved target is outside the experiment results root")
    if not target.is_dir():
        print(f"run does not exist: {target}")
        return 0
    shutil.rmtree(target)
    print(f"removed generated run: {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

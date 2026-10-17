#!/usr/bin/env python3
"""Freeze five unchanged existing compact rows per dataset for the pilot."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DATASETS = ("NY", "FLA", "CAL")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def prepare(dataset: str) -> dict:
    directory = ROOT / "experiments/manifests/icde2027_compact_v1/queries" / dataset
    source = directory / "compact_50.jsonl"
    target = directory / "pilot_5.jsonl"
    sidecar = directory / "pilot_manifest.json"
    if not source.is_file():
        raise FileNotFoundError(source)
    if target.exists() or sidecar.exists():
        raise FileExistsError(
            f"refusing to replace a pilot identity: {target if target.exists() else sidecar}"
        )
    lines = source.read_bytes().splitlines(keepends=True)
    rows = [json.loads(line) for line in lines if line.strip()]
    selected: list[tuple[dict, bytes]] = []
    for band in sorted({row["distance_bin"] for row in rows}):
        candidates = [
            (row, line)
            for row, line in zip(
                [json.loads(line) for line in lines if line.strip()],
                [line for line in lines if line.strip()],
            )
            if row.get("distance_bin") == band
            and row.get("metadata", {}).get("time_center") == 510
        ]
        if not candidates:
            raise ValueError(f"no original morning-center query in {dataset} distance band {band}")
        selected.append(min(candidates, key=lambda pair: pair[0]["metadata"]["pair_index"]))
    if len(selected) != 5:
        raise ValueError(f"expected five distinct distance bands in {dataset}")
    selected.sort(key=lambda pair: (pair[0]["distance_bin"], pair[0]["metadata"]["pair_index"]))
    payload = b"".join(line if line.endswith(b"\n") else line + b"\n" for _, line in selected)
    target.write_bytes(payload)
    value = {
        "schema_version": 1,
        "dataset_id": dataset,
        "source_manifest": source.relative_to(ROOT).as_posix(),
        "source_manifest_sha256": sha256(source),
        "pilot_query_manifest": target.relative_to(ROOT).as_posix(),
        "pilot_query_sha256": hashlib.sha256(payload).hexdigest(),
        "pilot_query_count": 5,
        "measured_trials": 1,
        "selection": "lowest pair_index at original time_center 510 in each of distance_bin 1 through 5",
        "selected_queries": [
            {
                "query_id": row["query_id"],
                "pair_id": row["metadata"]["pair_id"],
                "pair_index": row["metadata"]["pair_index"],
                "distance_bin": row["distance_bin"],
                "time_center": row["metadata"]["time_center"],
            }
            for row, _ in selected
        ],
        "unchanged_source_rows": True,
        "selection_frozen_before_execution": True,
    }
    sidecar.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", choices=DATASETS, action="append")
    args = parser.parse_args()
    for dataset in args.dataset or DATASETS:
        print(json.dumps(prepare(dataset), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

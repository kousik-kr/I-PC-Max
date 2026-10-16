#!/usr/bin/env python3
"""Freeze identity-preserving compact subsets from existing query records.

This script only selects and copies existing JSONL records. It never generates
source/destination pairs or rewrites query fields.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "experiments/manifests/icde2027_compact_v1/queries"
DATASETS = ("NY", "FLA", "CAL")
NY_EXACT_CHECKSUM = "6f878449ad1aeb924c2ba7388698ac1f1a407b99bc362dff7b819eb9bfceb683"


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _load_source(dataset: str) -> tuple[Path, list[tuple[str, dict[str, Any]]]]:
    source = ROOT / f"experiments/manifests/queries/{dataset}/paper_q1.jsonl"
    records = []
    for line in source.read_text(encoding="utf-8").splitlines():
        if line.strip():
            records.append((line, json.loads(line)))
    return source, records


def _default_rows(records: list[tuple[str, dict[str, Any]]], dataset: str) -> list[tuple[str, dict[str, Any]]]:
    result = []
    for item in records:
        row = item[1]
        metadata = row.get("metadata", {})
        if (row.get("dataset_id") == dataset
                and row.get("window_length") == 120
                and row.get("budget_slack") == 0.3
                and metadata.get("split") == "evaluation"
                and metadata.get("graph_seed") == 42
                and not metadata.get("variant_kind")
                and not metadata.get("variant_value")):
            result.append(item)
    return result


def _select_pairs(
    rows: list[tuple[str, dict[str, Any]]], pairs_per_band: int
) -> tuple[list[tuple[str, dict[str, Any]]], list[str]]:
    selected_ids: list[str] = []
    for band in range(1, 6):
        representative: dict[str, dict[str, Any]] = {}
        for _, row in rows:
            if row.get("distance_bin") == band:
                representative.setdefault(row["metadata"]["pair_id"], row)
        ordered = sorted(representative.items(), key=lambda item: item[1]["metadata"]["pair_index"])
        if len(ordered) < pairs_per_band:
            raise ValueError(f"distance band {band} has fewer than {pairs_per_band} original OD pairs")
        selected_ids.extend(pair_id for pair_id, _ in ordered[:pairs_per_band])
    selected = [item for item in rows if item[1]["metadata"]["pair_id"] in set(selected_ids)]
    selected.sort(key=lambda item: (
        item[1]["metadata"]["pair_index"], item[1]["metadata"]["time_center"], item[1]["query_id"]
    ))
    if len(selected) != pairs_per_band * 5 * 2:
        raise ValueError(f"selected {len(selected)} rows; expected two centers per selected pair")
    for pair_id in selected_ids:
        pair_rows = [row for _, row in selected if row["metadata"]["pair_id"] == pair_id]
        if len(pair_rows) != 2 or {row["metadata"]["time_center"] for row in pair_rows} != {510, 1110}:
            raise ValueError(f"original pair {pair_id} does not have exactly both default centers")
        if len({(row["source"], row["destination"], row["distance_bin"])
                for row in pair_rows}) != 1:
            raise ValueError(f"original pair {pair_id} differs across departure centers")
    return selected, selected_ids


def _write_rows(path: Path, rows: list[tuple[str, dict[str, Any]]]) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    content = "".join(line + "\n" for line, _ in rows).encode("utf-8")
    path.write_bytes(content)
    return _sha256(content)


def _write_manifest(path: Path, value: dict[str, Any]) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> None:
    for dataset in DATASETS:
        source, records = _load_source(dataset)
        original = _default_rows(records, dataset)
        if len(original) != 200 or len({row["metadata"]["pair_id"] for _, row in original}) != 100:
            raise ValueError(f"{dataset} default historical source is not 200 queries/100 OD pairs")
        compact, pair_ids = _select_pairs(original, 5)
        directory = OUTPUT / dataset
        if directory.exists():
            raise FileExistsError(f"refusing to overwrite frozen query identities: {directory}")
        original_path = directory / "original_default_200.jsonl"
        compact_path = directory / "compact_50.jsonl"
        original_hash = _write_rows(original_path, original)
        compact_hash = _write_rows(compact_path, compact)
        source_hash = _sha256(source.read_bytes())
        _write_manifest(directory / "manifest.json", {
            "schema_version": 1,
            "family": "icde2027_compact_v1",
            "dataset_id": dataset,
            "source_manifest": source.relative_to(ROOT).as_posix(),
            "source_manifest_sha256": source_hash,
            "source_generator_contract": "paper-q1-v1; exact rows filtered from existing manifest",
            "original_default_window_budget_query_count": 200,
            "original_default_query_sha256": original_hash,
            "compact_query_count": 50,
            "compact_query_sha256": compact_hash,
            "compact_pair_count": len(pair_ids),
            "selected_original_pair_ids": pair_ids,
            "selection": "first five existing pair_index values per distance band; both original centers",
            "window_minutes": 120,
            "rho": 0.30,
            "trials_per_query": 2,
            "dataset_graph_checksum": original[0][1]["metadata"]["dataset_checksum"],
            "query_field_policy": "all query rows copied unchanged from source manifest",
        })
        print(f"{dataset}: source={source_hash} original200={original_hash} compact50={compact_hash}")

    exact_source = ROOT / "experiments/manifests/queries/NY-EXACT/paper_q1.jsonl"
    exact_records = []
    for line in exact_source.read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line)
            metadata = row.get("metadata", {})
            if (row.get("dataset_id") == "NY-EXACT" and row.get("window_length") == 120
                    and row.get("budget_slack") == 0.3 and metadata.get("split") == "evaluation"
                    and metadata.get("graph_seed") == 42 and not metadata.get("variant_kind")
                    and not metadata.get("variant_value")):
                exact_records.append((line, row))
    if len(exact_records) != 200:
        raise ValueError("NY-EXACT historical default source is not 200 queries")
    if any(row["metadata"]["dataset_checksum"] != NY_EXACT_CHECKSUM for _, row in exact_records):
        raise ValueError("NY-EXACT source query graph checksum differs from the actual dataset manifest")
    controlled, controlled_pairs = _select_pairs(exact_records, 2)
    controlled_dir = OUTPUT / "NY-EXACT"
    controlled_path = controlled_dir / "controlled_20.jsonl"
    controlled_hash = _write_rows(controlled_path, controlled)
    _write_manifest(controlled_dir / "controlled_manifest.json", {
        "schema_version": 1,
        "family": "icde2027_compact_v1",
        "dataset_id": "NY-EXACT",
        "dataset_graph_checksum": NY_EXACT_CHECKSUM,
        "distinct_NY_CTRL_graph_verified": False,
        "source_manifest": exact_source.relative_to(ROOT).as_posix(),
        "source_manifest_sha256": _sha256(exact_source.read_bytes()),
        "source_default_query_sha256": _sha256("".join(line + "\n" for line, _ in exact_records).encode()),
        "controlled_query_count": 20,
        "controlled_query_sha256": controlled_hash,
        "selected_original_pair_ids": controlled_pairs,
        "selection": "first two existing OD pairs per distance band; both original centers",
        "window_minutes": 120,
        "rho": 0.30,
        "trials_per_query": 1,
        "query_field_policy": "all query rows copied unchanged from NY-EXACT source manifest",
    })
    print(f"NY-EXACT controlled20={controlled_hash}; source_rows=200; pairs={len(controlled_pairs)}")


if __name__ == "__main__":
    main()

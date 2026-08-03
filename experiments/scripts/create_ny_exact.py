#!/usr/bin/env python3
"""Create a deterministic connected NY induced subgraph for PACE-X probes.

This consumes the repository's already-converted canonical payloads.  It does
not parse DIMACS and it does not introduce a second graph representation: the
edge, travel-time, and score records are filtered by their existing directed
arc IDs and retain the declared conversion contract.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import json
from pathlib import Path
from collections import defaultdict, deque
import sys

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_json, atomic_write_text
from experiments.scripts.common.hashing import (
    dataset_checksum,
    graph_checksum,
    temporal_attribute_checksum,
)
from experiments.scripts.common.config import repo_path

REQUIRED = (
    "edges_static.csv.gz",
    "nodes.csv.gz",
    "manifest.json",
    "score_functions.jsonl.gz",
    "travel_time_functions.jsonl.gz",
)


def _gzip_csv(path: Path):
    return gzip.open(path, "rt", encoding="utf-8", newline="")


def _write_gzip_text(path: Path, lines: list[str]) -> None:
    with path.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz:
            gz.write("".join(lines).encode("utf-8"))


def _load_graph(source: Path) -> tuple[dict[int, set[int]], list[dict[str, str]], set[int]]:
    adjacency: dict[int, set[int]] = defaultdict(set)
    edges: list[dict[str, str]] = []
    arc_ids: set[int] = set()
    with _gzip_csv(source / "edges_static.csv.gz") as handle:
        for row in csv.DictReader(handle):
            arc_id = int(row["arc_id"])
            if arc_id in arc_ids:
                raise ValueError(f"duplicate directed arc_id in source: {arc_id}")
            arc_ids.add(arc_id)
            edges.append(row)
            u, v = int(row["u"]), int(row["v"])
            adjacency[u].add(v)
            adjacency[v].add(u)
    return adjacency, edges, arc_ids


def _select_nodes(adjacency: dict[int, set[int]], target: int) -> set[int]:
    if target < 10_000 or target > 50_000:
        raise ValueError("NY-Exact target must be between 10,000 and 50,000 nodes")
    selected: set[int] = set()
    for start in sorted(adjacency):
        if start in selected:
            continue
        queue = deque([start])
        selected.add(start)
        while queue and len(selected) < target:
            vertex = queue.popleft()
            for neighbor in sorted(adjacency[vertex]):
                if neighbor not in selected:
                    selected.add(neighbor)
                    queue.append(neighbor)
                    if len(selected) == target:
                        break
        if len(selected) == target:
            return selected
    raise ValueError(f"source graph has fewer than {target} reachable vertices")


def _filter_jsonl(
    source: Path,
    destination: Path,
    arc_mapping: dict[int, int],
    node_mapping: dict[int, int],
) -> int:
    kept: list[str] = []
    count = 0
    with gzip.open(source, "rt", encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            row = json.loads(line)
            old_arc_id = int(row["arc_id"])
            if old_arc_id in arc_mapping:
                row["arc_id"] = arc_mapping[old_arc_id]
                row["u"] = node_mapping[int(row["u"])]
                row["v"] = node_mapping[int(row["v"])]
                kept.append(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n")
                count += 1
    _write_gzip_text(destination, kept)
    return count


def create(source: Path, output: Path, target_nodes: int, overwrite: bool) -> dict:
    source = source.resolve()
    output = output.resolve()
    missing = [name for name in REQUIRED if not (source / name).is_file()]
    if missing:
        raise FileNotFoundError(f"NY source is missing: {', '.join(missing)}")
    if output.exists() and any(output.iterdir()) and not overwrite:
        raise FileExistsError(f"output exists; use --overwrite: {output}")
    output.mkdir(parents=True, exist_ok=True)
    adjacency, edges, _ = _load_graph(source)
    selected_nodes = _select_nodes(adjacency, target_nodes)
    selected_edges = [row for row in edges if int(row["u"]) in selected_nodes and int(row["v"]) in selected_nodes]
    if not selected_edges:
        raise ValueError("induced subgraph contains no directed arcs")
    selected_arcs = {int(row["arc_id"]) for row in selected_edges}
    if len(selected_arcs) != len(selected_edges):
        raise ValueError("induced subgraph has duplicate directed arc IDs")

    node_mapping = {old: new for new, old in enumerate(sorted(selected_nodes), 1)}
    node_lines = ["node_id,x,y\n"]
    with _gzip_csv(source / "nodes.csv.gz") as handle:
        for row in csv.DictReader(handle):
            if int(row["node_id"]) in selected_nodes:
                node_lines.append(",".join([
                    str(node_mapping[int(row["node_id"])]), row["x"], row["y"]
                ]) + "\n")
    _write_gzip_text(output / "nodes.csv.gz", node_lines)
    arc_mapping = {old: new for new, old in enumerate(sorted(selected_arcs))}
    edge_lines = ["arc_id,u,v,distance,base_travel_time\n"]
    for row in sorted(selected_edges, key=lambda value: arc_mapping[int(value["arc_id"])]):
        edge_lines.append(",".join([
            str(arc_mapping[int(row["arc_id"])]),
            str(node_mapping[int(row["u"])]),
            str(node_mapping[int(row["v"])]),
            row["distance"], row["base_travel_time"],
        ]) + "\n")
    _write_gzip_text(output / "edges_static.csv.gz", edge_lines)
    travel_count = _filter_jsonl(
        source / "travel_time_functions.jsonl.gz",
        output / "travel_time_functions.jsonl.gz",
        arc_mapping,
        node_mapping,
    )
    score_count = _filter_jsonl(
        source / "score_functions.jsonl.gz",
        output / "score_functions.jsonl.gz",
        arc_mapping,
        node_mapping,
    )
    if travel_count != len(selected_edges):
        raise ValueError(f"temporal payload has {travel_count} arcs, expected {len(selected_edges)}")
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    manifest.update({
        "schema_version": 3,
        "num_nodes": len(selected_nodes),
        "num_arcs": len(selected_edges),
        "selected_score_edge_count": score_count,
        "score_edge_fraction": score_count / len(selected_edges),
        "dataset_checksum": dataset_checksum(output),
        "temporal_attribute_checksum": temporal_attribute_checksum(output),
        "derived_from": "NY",
        "induced_subgraph": {
            "selection": "deterministic_connected_bfs_from_smallest_node",
            "target_nodes": target_nodes,
            "selected_nodes": len(selected_nodes),
            "source_dataset_checksum": dataset_checksum(source),
            "source_temporal_attribute_checksum": temporal_attribute_checksum(source),
            "directed_arc_id_policy": "deterministic-remap-in-source-arc-order",
            "node_id_policy": "deterministic-positive-remap-in-source-node-order",
        },
    })
    atomic_write_json(output / "manifest.json", manifest)
    atomic_write_text(output / "README_generated.md", (
        "# NY-Exact\n\n"
        "Deterministic connected induced subgraph of the canonical NY payload.\n"
        "Temporal and score payloads are filtered by canonical directed arc ID;\n"
        "the declared_centisecond_normalization-v1 contract and 10080-minute\n"
        "support are inherited from NY.\n"
    ))
    return {
        "dataset_id": "NY-EXACT",
        "nodes": len(selected_nodes),
        "directed_arcs": len(selected_edges),
        "score_edges": score_count,
        "graph_checksum": graph_checksum(output, REQUIRED),
        "dataset_checksum": manifest["dataset_checksum"],
        "temporal_attribute_checksum": manifest["temporal_attribute_checksum"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("data/input/NY"))
    parser.add_argument("--output", type=Path, default=Path("data/input/NY-Exact"))
    parser.add_argument("--nodes", type=int, default=10000)
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()
    try:
        print(json.dumps(create(repo_path(args.source), repo_path(args.output), args.nodes, args.overwrite), sort_keys=True, indent=2))
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"create_ny_exact: {failure}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

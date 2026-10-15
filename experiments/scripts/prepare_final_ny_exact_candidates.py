#!/usr/bin/env python3
"""Create a deterministic, pre-quality NY exact-study candidate pool."""
from __future__ import annotations

import csv
import gzip
import hashlib
import heapq
import json
import argparse
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
RESULT_ROOT = REPO_ROOT / "experiments/results/Final-result/manuscript_missing_runs/final_ny_exact"
PAIR_TARGET = 10
POOL_PER_BAND = 6
BANDS = ((0.8, 1.5), (1.5, 2.2), (2.2, 2.9), (2.9, 3.6), (3.6, 4.5))

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def load_graph(dataset: Path) -> dict[int, list[tuple[int, float, int]]]:
    graph: dict[int, list[tuple[int, float, int]]] = {}
    with gzip.open(dataset / "edges_static.csv.gz", "rt", encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            graph.setdefault(int(row["u"]), []).append((int(row["v"]), float(row["base_travel_time"]), int(row["arc_id"])))
    for edges in graph.values():
        edges.sort(key=lambda item: (item[1], item[0], item[2]))
    return graph

def dijkstra(graph: dict[int, list[tuple[int, float, int]]], source_node: int) -> dict[int, tuple[float, int]]:
    distances = {source_node: 0.0}
    hops = {source_node: 0}
    queue = [(0.0, 0, source_node)]
    limit = BANDS[-1][1] + 0.25
    while queue:
        distance, hop_count, node = heapq.heappop(queue)
        if distance != distances.get(node) or hop_count != hops.get(node):
            continue
        if distance > limit:
            continue
        for target, weight, _arc_id in graph.get(node, []):
            candidate = distance + weight
            candidate_hops = hop_count + 1
            old = distances.get(target)
            if old is None or (candidate, candidate_hops) < (old, hops[target]):
                distances[target] = candidate
                hops[target] = candidate_hops
                heapq.heappush(queue, (candidate, candidate_hops, target))
    return {node: (distances[node], hops[node]) for node in distances}

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=Path("data/input/NY-Exact"))
    parser.add_argument("--source-node", type=int, default=1)
    args = parser.parse_args()
    dataset = (REPO_ROOT / args.dataset).resolve() if not args.dataset.is_absolute() else args.dataset
    source_node = args.source_node
    graph = load_graph(dataset)
    if source_node not in graph:
        raise RuntimeError(f"source node {source_node} is not present in {dataset}")
    labels = dijkstra(graph, source_node)
    candidates = []
    for band_number, (lower, upper) in enumerate(BANDS, 1):
        band = [
            {"destination": node, "static_fastest_travel_time_min": distance, "estimated_hops": hops, "distance_band": band_number}
            for node, (distance, hops) in labels.items()
            if node != source_node and lower <= distance < upper
        ]
        band.sort(key=lambda row: (row["static_fastest_travel_time_min"], row["estimated_hops"], row["destination"]))
        candidates.extend(band[:POOL_PER_BAND])
    by_band = {band: [row for row in candidates if row["distance_band"] == band] for band in range(1, 6)}
    if any(len(rows) < 2 for rows in by_band.values()):
        raise RuntimeError({band: len(rows) for band, rows in by_band.items()})
    selected = []
    for band in range(1, 6):
        rows = by_band[band]
        midpoint = sum(BANDS[band - 1]) / 2
        rows = sorted(rows, key=lambda row: (abs(row["static_fastest_travel_time_min"] - midpoint), row["estimated_hops"], row["destination"]))
        selected.extend(rows[:2])
    for index, row in enumerate(selected, 1):
        row["pair_index"] = index
        row["pair_id"] = f"NY-FINAL-P{index:03d}"
        row["source"] = source_node
    payload = {
        "dataset": "NY",
        "source_node": source_node,
        "bands": [{"band": index, "lower_exclusive": lower, "upper_exclusive": upper} for index, (lower, upper) in enumerate(BANDS, 1)],
        "candidate_pool": candidates,
        "selected_pairs": selected,
        "selection_rule": "static base-travel-time Dijkstra from fixed source; two midpoint-nearest destinations per band; ties by hops then node ID",
        "quality_blind": True,
        "source_files": {
            "edges_static": str(dataset / "edges_static.csv.gz"),
            "dataset_manifest": str(dataset / "manifest.json"),
        },
        "source_sha256": {"edges_static": sha256(dataset / "edges_static.csv.gz"), "manifest": sha256(dataset / "manifest.json")},
    }
    path = RESULT_ROOT / "provenance/candidate_pool.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"selected_pairs": selected, "candidate_count": len(candidates), "output": str(path)}, indent=2))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())

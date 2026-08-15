"""Deterministic MTA-terminal extraction and road-graph pair selection."""

from __future__ import annotations

import csv
import gzip
import heapq
import math
import random
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from .common import CaseStudyError
from .geometry import line_endpoints
from .mapping import read_geojson_pages
from .temporal import read_schedule_pages


def _flag(value: object) -> bool:
    return str(value or "").strip().lower() in {"1", "true", "yes", "y"}


def _static_graph(path: Path) -> tuple[dict[int, list[tuple[int, float]]], int]:
    adjacency: dict[int, list[tuple[int, float]]] = defaultdict(list)
    count = 0
    with gzip.open(path, "rt", encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            adjacency[int(row["u"])].append((int(row["v"]), float(row["base_travel_time"])))
            count += 1
    return adjacency, count


def static_fastest(adjacency: dict[int, list[tuple[int, float]]], source: int,
                   destination: int) -> float:
    queue = [(0.0, source)]
    distance = {source: 0.0}
    while queue:
        value, node = heapq.heappop(queue)
        if value != distance.get(node):
            continue
        if node == destination:
            return value
        for target, weight in adjacency.get(node, ()):
            candidate = value + weight
            if candidate < distance.get(target, math.inf):
                distance[target] = candidate
                heapq.heappush(queue, (candidate, target))
    return math.inf


def select_terminal_pairs(nodes_path: Path, edges_path: Path, route_pages: list[Path],
                          schedule_pages: list[Path], output: Path,
                          config: dict[str, Any]) -> dict[str, Any]:
    import geopandas as gpd  # type: ignore
    import pandas as pd  # type: ignore
    from shapely.geometry import Point  # type: ignore
    from shapely.strtree import STRtree  # type: ignore

    schedule = read_schedule_pages(schedule_pages)
    required = {"shape_id", "stop_id", "stop_name", "origin", "destination"}
    if not required.issubset(schedule.columns):
        raise CaseStudyError(f"MTA schedule terminal extraction missing {sorted(required - set(schedule.columns))}")
    routes = read_geojson_pages(route_pages).to_crs("EPSG:4326")
    if "shape_id" not in routes.columns:
        raise CaseStudyError("MTA route geometry has no shape_id")
    shape_geometries = {
        str(row.shape_id): row.geometry for row in routes.itertuples(index=False)
        if row.geometry is not None and not row.geometry.is_empty
    }
    terminal_counts: Counter[tuple[str, str, str, str]] = Counter()
    coordinates: dict[tuple[str, str, str, str], tuple[float, float]] = {}
    missing_shapes = 0
    for row in schedule.itertuples(index=False):
        is_origin = _flag(row.origin)
        is_destination = _flag(row.destination)
        if not is_origin and not is_destination:
            continue
        shape_id = str(row.shape_id)
        geometry = shape_geometries.get(shape_id)
        if geometry is None:
            missing_shapes += 1
            continue
        start, end = line_endpoints(geometry)
        stop_id = str(row.stop_id)
        stop_name = str(row.stop_name) if str(row.stop_name).lower() != "nan" else stop_id
        for role, coordinate in (("ORIGIN", start), ("DESTINATION", end)):
            if (role == "ORIGIN" and not is_origin) or (role == "DESTINATION" and not is_destination):
                continue
            key = (stop_id, stop_name, shape_id, role)
            terminal_counts[key] += 1
            coordinates[key] = coordinate
    if not terminal_counts:
        raise CaseStudyError("no schedule first/last stops join current MTA route-shape geometry")

    nodes = gpd.read_parquet(nodes_path).to_crs(config["projected_crs"])
    node_geometries = nodes.geometry.to_numpy()
    tree = STRtree(node_geometries)
    max_snap = float(config["terminal_selection"]["maximum_stop_to_graph_distance_m"])
    candidates: list[dict[str, Any]] = []
    terminal_crs = gpd.GeoSeries(
        [Point(coordinates[key]) for key in terminal_counts], crs="EPSG:4326"
    ).to_crs(config["projected_crs"])
    for key, point in zip(terminal_counts, terminal_crs):
        nearest, distances = tree.query_nearest(point, return_distance=True)
        node_index = int(nearest[0] if hasattr(nearest, "__len__") else nearest)
        distance = float(distances[0] if hasattr(distances, "__len__") else distances)
        if distance > max_snap:
            continue
        lon, lat = coordinates[key]
        candidates.append({
            "stop_id": key[0], "terminal_name": key[1], "shape_id": key[2],
            "terminal_role": key[3], "longitude": lon, "latitude": lat,
            "frequency": terminal_counts[key], "mapped_vertex": int(nodes.iloc[node_index].node_id),
            "snap_distance_m": distance, "projected_point": point,
        })
    candidates.sort(key=lambda item: (-item["frequency"], item["stop_id"], item["shape_id"], item["terminal_role"]))
    # Aggregate duplicate/nearby terminals deterministically by mapped graph vertex and a small separation rule.
    distinct: list[dict[str, Any]] = []
    seen_vertices: set[int] = set()
    separation = float(config["terminal_selection"]["minimum_terminal_separation_m"])
    for item in candidates:
        if item["mapped_vertex"] in seen_vertices:
            continue
        if any(item["projected_point"].distance(other["projected_point"]) < separation for other in distinct):
            continue
        seen_vertices.add(item["mapped_vertex"])
        distinct.append(item)
        if len(distinct) >= int(config["terminal_selection"]["top_terminal_candidates"]):
            break
    if len(distinct) < 2:
        raise CaseStudyError("fewer than two geographically distinct terminal candidates map to the road graph")

    adjacency, arc_count = _static_graph(edges_path)
    combinations = [(left, right) for left in range(len(distinct)) for right in range(len(distinct)) if left != right]
    rng = random.Random(int(config["seed"]))
    rng.shuffle(combinations)
    target = int(config["target_terminal_pairs"])
    min_distance = float(config["terminal_selection"]["minimum_pair_distance_m"])
    min_travel = float(config["terminal_selection"]["minimum_static_fastest_minutes"])
    output_rows: list[dict[str, Any]] = []
    accepted = 0
    for left_index, right_index in combinations:
        left, right = distinct[left_index], distinct[right_index]
        straight = float(left["projected_point"].distance(right["projected_point"]))
        reason = "ACCEPTED"
        fastest = math.nan
        if left["mapped_vertex"] == right["mapped_vertex"]:
            reason = "SAME_VERTEX"
        elif straight < min_distance:
            reason = "PAIR_TOO_CLOSE"
        else:
            fastest = static_fastest(adjacency, left["mapped_vertex"], right["mapped_vertex"])
            if not math.isfinite(fastest):
                reason = "UNREACHABLE"
            elif fastest < min_travel:
                reason = "STATIC_FASTEST_TOO_SHORT"
        selected = reason == "ACCEPTED" and accepted < target
        if reason == "ACCEPTED" and not selected:
            reason = "TARGET_COUNT_REACHED"
        pair_id = f"NYC-{accepted + 1:03d}" if selected else ""
        if selected:
            accepted += 1
        output_rows.append({
            "pair_id": pair_id, "selected": selected,
            "source_stop_id": left["stop_id"], "source_terminal": left["terminal_name"],
            "destination_stop_id": right["stop_id"], "destination_terminal": right["terminal_name"],
            "source_longitude": left["longitude"], "source_latitude": left["latitude"],
            "destination_longitude": right["longitude"], "destination_latitude": right["latitude"],
            "source_vertex": left["mapped_vertex"], "destination_vertex": right["mapped_vertex"],
            "source_snap_distance_m": left["snap_distance_m"],
            "destination_snap_distance_m": right["snap_distance_m"],
            "straight_line_distance_m": straight,
            "static_fastest_travel_time_minutes": fastest if math.isfinite(fastest) else "",
            "selection_reason": reason, "selection_seed": int(config["seed"]),
        })
        if accepted >= target:
            break
    if accepted == 0:
        raise CaseStudyError("terminal-pair filters accepted no feasible nontrivial pair")
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(output_rows[0]))
        writer.writeheader()
        writer.writerows(output_rows)
    return {
        "schedule_terminal_rows_without_shape": missing_shapes,
        "terminal_candidates_before_aggregation": len(candidates),
        "terminal_candidates_after_aggregation": len(distinct),
        "accepted_pairs": accepted, "evaluated_pairs": len(output_rows),
        "graph_arc_count": arc_count,
    }

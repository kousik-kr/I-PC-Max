"""GeoParquet preparation and official-feature-to-DIMACS matching."""

from __future__ import annotations

import gzip
import json
import math
import statistics
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

from .common import CASE_ROOT, REPO_ROOT, CaseStudyError, atomic_write_json, atomic_write_text, require_columns, sha256_file
from .geometry import (
    bearing_degrees,
    centerline_direction_difference,
    dot_geometry,
    endpoint_consistency,
    line_match_metrics,
    line_endpoints,
    ordered_edge_matches,
)


CENTERLINE_ALIASES = {
    "physical_id": ["physicalid", "physical_id"],
    "traffic_direction": ["trafdir", "traffic_direction"],
    "road_type": ["rw_type", "rwtype", "fcc"],
    "posted_speed": ["posted_speed", "posted speed"],
    "lanes": ["number_travel_lanes", "number travel lanes"],
    "street_width": ["street_width", "streetwidth", "street width"],
    "street_name": ["full_street_name", "full street name", "street_name"],
    "borough": ["borough_code", "boroughcode", "borough code", "borocode"],
}

MTA_ALIASES = {
    "route_id": ["route_id", "routeid", "route"],
    "shape_id": ["shape_id", "shapeid"],
}


def latest_download(source_dir: Path, extension: str) -> list[Path]:
    manifests = sorted(source_dir.glob("*/download_manifest.json"))
    if not manifests:
        raise CaseStudyError(f"no complete download manifest under {source_dir}")
    directory = manifests[-1].parent
    manifest = json.loads(manifests[-1].read_text(encoding="utf-8"))
    if manifest.get("status") != "COMPLETE":
        raise CaseStudyError(f"latest source download is incomplete: {manifests[-1]}")
    pages = sorted(directory.glob(f"page-*.{extension}"))
    if not pages:
        raise CaseStudyError(f"no {extension} pages recorded under {directory}")
    return pages


def read_geojson_pages(paths: Iterable[Path]) -> Any:
    import geopandas as gpd  # type: ignore
    import pandas as pd  # type: ignore

    frames = []
    for path in paths:
        frame = gpd.read_file(path)
        if not frame.empty:
            frames.append(frame)
    if not frames:
        raise CaseStudyError("official GeoJSON pages contain no features")
    result = gpd.GeoDataFrame(pd.concat(frames, ignore_index=True), geometry="geometry", crs=frames[0].crs)
    if result.crs is None:
        result = result.set_crs("EPSG:4326")
    return result


def prepare_dimacs(source_graph: Path, output_dir: Path, *, scale: int = 1_000_000,
                   projected_crs: str = "EPSG:32618") -> dict[str, Any]:
    import geopandas as gpd  # type: ignore
    import pandas as pd  # type: ignore
    from shapely.geometry import LineString  # type: ignore

    nodes_path = source_graph / "nodes.csv.gz"
    edges_path = source_graph / "edges_static.csv.gz"
    nodes = pd.read_csv(nodes_path, compression="gzip")
    if list(nodes.columns) != ["node_id", "x", "y"]:
        raise CaseStudyError(f"unexpected DIMACS node schema: {list(nodes.columns)}")
    nodes["longitude"] = nodes["x"] / scale
    nodes["latitude"] = nodes["y"] / scale
    bbox = [float(nodes.longitude.min()), float(nodes.latitude.min()),
            float(nodes.longitude.max()), float(nodes.latitude.max())]
    if not (-76 < bbox[0] < -71 and 39 < bbox[1] < 42 and -76 < bbox[2] < -71 and 39 < bbox[3] < 42):
        raise CaseStudyError(f"converted DIMACS coordinates are not geographically plausible: {bbox}")
    node_geo = gpd.GeoDataFrame(
        nodes, geometry=gpd.points_from_xy(nodes.longitude, nodes.latitude), crs="EPSG:4326"
    )

    edges = pd.read_csv(edges_path, compression="gzip")
    expected = ["arc_id", "u", "v", "distance", "base_travel_time"]
    if list(edges.columns) != expected:
        raise CaseStudyError(f"unexpected DIMACS edge schema: {list(edges.columns)}")
    if not (edges.arc_id.to_numpy() == __import__("numpy").arange(len(edges))).all():
        raise CaseStudyError("DIMACS arc IDs are not consecutive in row order")
    coordinates = nodes.set_index("node_id")[["longitude", "latitude"]]
    tails = coordinates.reindex(edges.u).to_numpy()
    heads = coordinates.reindex(edges.v).to_numpy()
    if __import__("numpy").isnan(tails).any() or __import__("numpy").isnan(heads).any():
        raise CaseStudyError("one or more DIMACS edge endpoints have no node coordinate")
    edges["source_longitude"] = tails[:, 0]
    edges["source_latitude"] = tails[:, 1]
    edges["target_longitude"] = heads[:, 0]
    edges["target_latitude"] = heads[:, 1]
    edge_geo = gpd.GeoDataFrame(
        edges,
        geometry=[LineString((tuple(tail), tuple(head))) for tail, head in zip(tails, heads)],
        crs="EPSG:4326",
    )
    projected = edge_geo.to_crs(projected_crs)
    edge_geo["geometric_length_m"] = projected.length.to_numpy()
    output_dir.mkdir(parents=True, exist_ok=True)
    node_output = output_dir / "dimacs_ny_nodes.parquet"
    edge_output = output_dir / "dimacs_ny_edges.parquet"
    node_geo.to_parquet(node_output, index=False)
    edge_geo.to_parquet(edge_output, index=False)
    manifest = {
        "schema_version": 1,
        "coordinate_formula": f"lon=x/{scale}; lat=y/{scale}",
        "geographic_crs": "EPSG:4326",
        "projected_crs": projected_crs,
        "bbox_lon_lat": bbox,
        "node_count": len(node_geo),
        "arc_count": len(edge_geo),
        "source_sha256": {"nodes": sha256_file(nodes_path), "edges": sha256_file(edges_path)},
        "output_sha256": {"nodes": sha256_file(node_output), "edges": sha256_file(edge_output)},
    }
    atomic_write_json(output_dir / "dimacs_ny_conversion_manifest.json", manifest)
    return manifest


def _candidate_indices(tree: Any, geometry: Any, radius: float) -> list[int]:
    result = tree.query(geometry.buffer(radius))
    return [int(item) for item in result]


def map_centerline(edges_path: Path, centerline_pages: list[Path], output: Path,
                   report: Path, config: dict[str, Any]) -> dict[str, Any]:
    import geopandas as gpd  # type: ignore
    import pandas as pd  # type: ignore
    from shapely.strtree import STRtree  # type: ignore

    projected_crs = config["projected_crs"]
    maximum = float(config["matching"]["centerline_max_distance_m"])
    minimum_overlap = float(config["matching"]["minimum_overlap_ratio"])
    max_angle = float(config["matching"]["maximum_direction_difference_degrees"])
    ambiguity_delta = float(config["matching"]["ambiguity_score_delta"])
    edges = gpd.read_parquet(edges_path).to_crs(projected_crs)
    centerline = read_geojson_pages(centerline_pages).to_crs(projected_crs)
    centerline = centerline[~centerline.geometry.is_empty & centerline.geometry.notna()].reset_index(drop=True)
    fields = require_columns(list(centerline.columns), CENTERLINE_ALIASES, "NYC Centerline schema")
    import numpy as np  # type: ignore
    import shapely  # type: ignore

    centerline_geometries = centerline.geometry.to_numpy()
    edge_geometries = edges.geometry.to_numpy()
    tree = STRtree(centerline_geometries)
    pair_indexes = tree.query(
        edge_geometries, predicate="dwithin", distance=maximum
    )
    edge_indexes = pair_indexes[0].astype(np.int64)
    centerline_indexes = pair_indexes[1].astype(np.int64)
    pair_edges = edge_geometries[edge_indexes]
    pair_centerlines = centerline_geometries[centerline_indexes]
    distance_values = shapely.distance(pair_edges, pair_centerlines)
    centerline_buffers = shapely.buffer(centerline_geometries, min(10.0, maximum / 2))
    overlap_values = np.minimum(
        1.0,
        shapely.length(shapely.intersection(pair_edges, centerline_buffers[centerline_indexes]))
        / np.maximum(shapely.length(pair_edges), 1e-9),
    )

    edge_bearings = np.asarray([bearing_degrees(item) for item in edge_geometries])
    centerline_bearings = np.asarray([bearing_degrees(item) for item in centerline_geometries])
    direct = np.abs(edge_bearings[edge_indexes] - centerline_bearings[centerline_indexes]) % 360.0
    direct = np.minimum(direct, 360.0 - direct)
    reverse = np.abs(edge_bearings[edge_indexes] - ((centerline_bearings[centerline_indexes] + 180.0) % 360.0)) % 360.0
    reverse = np.minimum(reverse, 360.0 - reverse)
    traffic_codes = centerline[fields["traffic_direction"]].fillna("").astype(str).str.upper().to_numpy()[centerline_indexes]
    angle_values = np.where(np.isin(traffic_codes, ["TF", "T", "WITH"]), direct,
                            np.where(np.isin(traffic_codes, ["FT", "F", "AGAINST"]), reverse,
                                     np.minimum(direct, reverse)))

    edge_endpoints = np.asarray([line_endpoints(item) for item in edge_geometries])
    centerline_endpoints = np.asarray([line_endpoints(item) for item in centerline_geometries])
    edge_start = edge_endpoints[edge_indexes, 0, :]
    edge_end = edge_endpoints[edge_indexes, 1, :]
    source_start = centerline_endpoints[centerline_indexes, 0, :]
    source_end = centerline_endpoints[centerline_indexes, 1, :]
    direct_endpoint = np.linalg.norm(edge_start - source_start, axis=1) + np.linalg.norm(edge_end - source_end, axis=1)
    reverse_endpoint = np.linalg.norm(edge_start - source_end, axis=1) + np.linalg.norm(edge_end - source_start, axis=1)
    endpoint_values = np.maximum(
        0.0,
        1.0 - np.minimum(direct_endpoint, reverse_endpoint)
        / np.maximum(shapely.length(pair_edges) + shapely.length(pair_centerlines), 1e-9),
    )
    proximity = np.maximum(0.0, 1.0 - distance_values / maximum)
    score_values = (0.30 * proximity + 0.45 * overlap_values
                    + 0.15 * (1.0 - angle_values / 180.0) + 0.10 * endpoint_values)
    keep = ((distance_values <= maximum) & (angle_values <= max_angle)
            & ((overlap_values >= minimum_overlap) | (endpoint_values >= 0.60)))
    candidates = pd.DataFrame({
        "edge_index": edge_indexes[keep],
        "centerline_index": centerline_indexes[keep],
        "match_distance_m": distance_values[keep],
        "overlap_ratio": overlap_values[keep],
        "direction_difference_degrees": angle_values[keep],
        "endpoint_consistency": endpoint_values[keep],
        "match_score": score_values[keep],
    }).sort_values(["edge_index", "match_score", "centerline_index"], ascending=[True, False, True])
    candidates["rank"] = candidates.groupby("edge_index").cumcount()
    top = candidates[candidates["rank"] == 0].copy()
    second = candidates[candidates["rank"] == 1].set_index("edge_index")["match_score"]
    top["second_score"] = top.edge_index.map(second)
    top["centerline_match_status"] = np.where(
        top.second_score.notna() & ((top.match_score - top.second_score) <= ambiguity_delta),
        "AMBIGUOUS", "MATCHED",
    )
    source_rows = centerline.iloc[top.centerline_index.to_numpy()].reset_index(drop=True)
    top = top.reset_index(drop=True)
    top["arc_id"] = edges.arc_id.to_numpy()[top.edge_index.to_numpy()].astype(int)
    top["centerline_physical_id"] = source_rows[fields["physical_id"]].to_numpy()
    top["direction_consistency"] = top.direction_difference_degrees <= max_angle
    for output_name, semantic in (
        ("posted_speed", "posted_speed"), ("lanes", "lanes"),
        ("street_width", "street_width"), ("road_type", "road_type"),
        ("street_name", "street_name"), ("borough", "borough"),
    ):
        top[output_name] = source_rows[fields[semantic]].to_numpy()
    matched_rows = top[[
        "arc_id", "centerline_match_status", "centerline_physical_id",
        "match_distance_m", "overlap_ratio", "direction_consistency",
        "direction_difference_degrees", "endpoint_consistency", "posted_speed",
        "lanes", "street_width", "road_type", "street_name", "borough",
    ]]
    distances = top.match_distance_m.to_list()
    ambiguous = int((top.centerline_match_status == "AMBIGUOUS").sum())
    result = pd.DataFrame({"arc_id": edges.arc_id.astype(int)})
    result = result.merge(matched_rows, on="arc_id", how="left", validate="one_to_one")
    result["centerline_match_status"] = result["centerline_match_status"].fillna("UNMATCHED")
    output.parent.mkdir(parents=True, exist_ok=True)
    result.to_parquet(output, index=False)
    matched_count = int((result.centerline_match_status != "UNMATCHED").sum())
    stats = {
        "edge_count": len(result), "matched_edges": matched_count,
        "matched_percent": 100 * matched_count / len(result),
        "ambiguous_matches": ambiguous, "unmatched_edges": len(result) - matched_count,
        "distance_median_m": statistics.median(distances) if distances else None,
        "distance_p95_m": sorted(distances)[max(0, math.ceil(0.95 * len(distances)) - 1)] if distances else None,
        "output_sha256": sha256_file(output),
    }
    borough_counts = result.loc[result.centerline_match_status != "UNMATCHED", "borough"].value_counts(dropna=False)
    road_counts = result.loc[result.centerline_match_status != "UNMATCHED", "road_type"].value_counts(dropna=False)
    lines = ["# Centerline mapping quality", ""] + [f"- {key}: {value}" for key, value in stats.items()]
    lines += ["", "## Matched edges by borough", ""] + [f"- {key}: {value}" for key, value in borough_counts.items()]
    lines += ["", "## Matched edges by road class", ""] + [f"- {key}: {value}" for key, value in road_counts.items()]
    atomic_write_text(report, "\n".join(lines) + "\n")
    return stats


def _spatial_edges(edges: Any, source: Any, tree: Any, radius: float) -> list[tuple[int, Any]]:
    return [(int(edges.iloc[index].arc_id), edges.iloc[index].geometry)
            for index in _candidate_indices(tree, source, radius)]


def map_mta_shapes(edges_path: Path, route_pages: list[Path], output: Path,
                   report: Path, config: dict[str, Any]) -> dict[str, Any]:
    import geopandas as gpd  # type: ignore
    import numpy as np  # type: ignore
    import pandas as pd  # type: ignore
    import shapely  # type: ignore
    from shapely.strtree import STRtree  # type: ignore

    crs = config["projected_crs"]
    radius = float(config["matching"]["route_buffer_m"])
    minimum_overlap = float(config["matching"]["minimum_overlap_ratio"])
    max_angle = float(config["matching"]["maximum_direction_difference_degrees"])
    edges = gpd.read_parquet(edges_path).to_crs(crs)
    routes = read_geojson_pages(route_pages).to_crs(crs)
    routes = routes[~routes.geometry.is_empty & routes.geometry.notna()].reset_index(drop=True)
    fields = require_columns(list(routes.columns), MTA_ALIASES, "MTA Current Bus Routes schema")
    edge_geometries = edges.geometry.to_numpy()
    route_geometries = routes.geometry.to_numpy()
    tree = STRtree(edge_geometries)
    pairs = tree.query(route_geometries, predicate="dwithin", distance=radius)
    route_indexes = pairs[0].astype(np.int64)
    edge_indexes = pairs[1].astype(np.int64)
    pair_routes = route_geometries[route_indexes]
    pair_edges = edge_geometries[edge_indexes]
    distances = shapely.distance(pair_edges, pair_routes)
    route_buffers = shapely.buffer(route_geometries, radius)
    overlaps = np.minimum(
        1.0,
        shapely.length(shapely.intersection(pair_edges, route_buffers[route_indexes]))
        / np.maximum(shapely.length(pair_edges), 1e-9),
    )
    edge_bearings = np.asarray([bearing_degrees(item) for item in edge_geometries])
    route_bearings = np.asarray([bearing_degrees(item) for item in route_geometries])
    angles = np.abs(edge_bearings[edge_indexes] - route_bearings[route_indexes]) % 360.0
    angles = np.minimum(angles, 360.0 - angles)
    keep = ((distances <= radius) & (overlaps >= minimum_overlap) & (angles <= max_angle))
    route_indexes = route_indexes[keep]
    edge_indexes = edge_indexes[keep]
    pair_routes = pair_routes[keep]
    pair_edges = pair_edges[keep]
    midpoints = shapely.line_interpolate_point(pair_edges, 0.5, normalized=True)
    positions = shapely.line_locate_point(pair_routes, midpoints)
    result = pd.DataFrame({
        "route_index": route_indexes,
        "arc_id": edges.arc_id.to_numpy()[edge_indexes].astype(int),
        "mapping_distance_m": distances[keep],
        "overlap_ratio": overlaps[keep],
        "direction_difference_degrees": angles[keep],
        "route_position_m": positions,
        "mapped_arc_length_m": shapely.length(pair_edges),
    }).sort_values(["route_index", "route_position_m", "arc_id"])
    result = result.drop_duplicates(["route_index", "arc_id"], keep="first")
    result["arc_order"] = result.groupby("route_index").cumcount()
    source_rows = routes.iloc[result.route_index.to_numpy()].reset_index(drop=True)
    result["route_id"] = source_rows[fields["route_id"]].to_numpy()
    result["shape_id"] = source_rows[fields["shape_id"]].to_numpy()
    result["route_type"] = source_rows["route_type"].to_numpy() if "route_type" in source_rows else None
    direction_field = "direction" if "direction" in source_rows else "direction_id"
    result["direction"] = source_rows[direction_field].to_numpy() if direction_field in source_rows else None
    mapped_route_indexes = int(result.route_index.nunique())
    mapped_by_index = result.groupby("route_index").mapped_arc_length_m.sum()
    route_lengths = routes.geometry.length.to_numpy()
    ratios = [float(value) / float(route_lengths[int(index)])
              for index, value in mapped_by_index.items() if route_lengths[int(index)] > 0]
    result = result[[
        "route_id", "shape_id", "route_type", "direction", "arc_id", "arc_order",
        "mapping_distance_m", "overlap_ratio", "direction_difference_degrees",
        "route_position_m", "mapped_arc_length_m",
    ]]
    if result.empty:
        raise CaseStudyError("no MTA route shape could be mapped under configured thresholds")
    output.parent.mkdir(parents=True, exist_ok=True)
    result.to_parquet(output, index=False)
    mapped_shapes = int(result.shape_id.nunique())
    route_counts = result.groupby("arc_id").route_id.nunique()
    stats = {
        "route_shapes_processed": len(routes), "mapped_shapes": mapped_shapes,
        "mapped_route_direction_records": mapped_route_indexes,
        "usable_mapping_percent": 100 * mapped_route_indexes / len(routes),
        "median_mapped_length_ratio": statistics.median(ratios) if ratios else 0.0,
        "ambiguous_mappings": 0,
        "median_distinct_routes_per_mapped_arc": float(route_counts.median()),
        "max_distinct_routes_per_mapped_arc": int(route_counts.max()),
        "output_sha256": sha256_file(output),
    }
    atomic_write_text(report, "# MTA route-shape mapping quality\n\n" +
                      "\n".join(f"- {key}: {value}" for key, value in stats.items()) + "\n")
    return stats


def map_dot_links(edges_path: Path, traffic_bins_path: Path, output: Path,
                  report: Path, config: dict[str, Any]) -> dict[str, Any]:
    import geopandas as gpd  # type: ignore
    import pandas as pd  # type: ignore
    from shapely.strtree import STRtree  # type: ignore

    crs = config["projected_crs"]
    radius = float(config["matching"]["dot_link_buffer_m"])
    minimum_overlap = float(config["matching"]["minimum_overlap_ratio"])
    max_angle = float(config["matching"]["maximum_direction_difference_degrees"])
    edges = gpd.read_parquet(edges_path).to_crs(crs)
    observations = pd.read_parquet(traffic_bins_path)
    required = {"link_id", "link_points", "encoded_poly_line"}
    if not required.issubset(observations.columns):
        raise CaseStudyError(f"DOT aggregate missing geometry columns: {sorted(required - set(observations.columns))}")
    unique = observations.drop_duplicates("link_id").copy()
    geometries = []
    failures: list[str] = []
    for row in unique.itertuples(index=False):
        try:
            geometries.append(dot_geometry(row.link_points, row.encoded_poly_line))
        except CaseStudyError as failure:
            geometries.append(None)
            failures.append(f"{row.link_id}: {failure}")
    links = gpd.GeoDataFrame(unique, geometry=geometries, crs="EPSG:4326")
    links = links[links.geometry.notna() & ~links.geometry.is_empty].to_crs(crs).reset_index(drop=True)
    tree = STRtree(edges.geometry.to_list())
    rows: list[dict[str, Any]] = []
    coverages: list[float] = []
    for link in links.itertuples(index=False):
        matches = ordered_edge_matches(
            link.geometry, _spatial_edges(edges, link.geometry, tree, radius),
            max_distance_m=radius, overlap_buffer_m=radius,
            minimum_overlap_ratio=minimum_overlap,
            maximum_direction_difference_degrees=max_angle,
        )
        mapped_length = sum(edges.iloc[arc_id].geometry.length for arc_id, _ in matches)
        coverage = min(1.0, mapped_length / link.geometry.length) if link.geometry.length else 0.0
        coverages.append(coverage)
        for order, (arc_id, metrics) in enumerate(matches):
            arc_length = float(edges.iloc[arc_id].geometry.length)
            rows.append({
                "link_id": str(link.link_id), "arc_id": arc_id, "arc_order": order,
                "mapping_distance_m": metrics.distance_m,
                "mapped_arc_length_m": arc_length,
                "observed_link_length_m": float(link.geometry.length),
                "coverage_ratio": coverage,
                "length_allocation_fraction": arc_length / mapped_length if mapped_length else 0.0,
            })
    result = pd.DataFrame.from_records(rows)
    if result.empty:
        raise CaseStudyError("no DOT traffic link could be mapped under configured thresholds")
    output.parent.mkdir(parents=True, exist_ok=True)
    result.to_parquet(output, index=False)
    stats = {
        "dot_links_with_geometry": len(links),
        "dot_links_mapped": int(result.link_id.nunique()),
        "dot_links_unparseable": len(failures),
        "median_coverage_ratio": statistics.median(coverages) if coverages else 0,
        "one_to_many_links": int((result.groupby("link_id").arc_id.nunique() > 1).sum()),
        "output_sha256": sha256_file(output),
    }
    lines = ["# DOT-link mapping quality", ""] + [f"- {key}: {value}" for key, value in stats.items()]
    if failures:
        lines += ["", "## Geometry parse failures", ""] + [f"- {item}" for item in failures[:100]]
    atomic_write_text(report, "\n".join(lines) + "\n")
    return stats

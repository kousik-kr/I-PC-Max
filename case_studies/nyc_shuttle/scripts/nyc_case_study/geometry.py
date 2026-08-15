"""Geometry parsing and direction-aware line matching primitives."""

from __future__ import annotations

import math
import re
from dataclasses import dataclass
from typing import Any, Iterable

from .common import CaseStudyError


def _coordinates(line: Any) -> list[tuple[float, float]]:
    try:
        return list(line.coords)
    except NotImplementedError:
        components = [item for item in getattr(line, "geoms", ()) if hasattr(item, "coords")]
        if not components:
            return []
        return list(max(components, key=lambda item: item.length).coords)


def line_endpoints(line: Any) -> tuple[tuple[float, float], tuple[float, float]]:
    coordinates = _coordinates(line)
    if len(coordinates) < 2:
        raise ValueError("line requires two distinct coordinates")
    return coordinates[0], coordinates[-1]


def bearing_degrees(line: Any) -> float:
    coordinates = _coordinates(line)
    if len(coordinates) < 2:
        raise ValueError("line requires two distinct coordinates")
    start = coordinates[0]
    end = coordinates[-1]
    return math.degrees(math.atan2(end[1] - start[1], end[0] - start[0])) % 360.0


def direction_difference(left: Any, right: Any, reverse_right: bool = False) -> float:
    right_bearing = (bearing_degrees(right) + (180.0 if reverse_right else 0.0)) % 360.0
    difference = abs(bearing_degrees(left) - right_bearing) % 360.0
    return min(difference, 360.0 - difference)


def centerline_direction_difference(edge: Any, centerline: Any, traffic_direction: object) -> float:
    code = str(traffic_direction or "").strip().upper()
    direct = direction_difference(edge, centerline)
    reverse = direction_difference(edge, centerline, reverse_right=True)
    if code in {"TF", "T", "WITH"}:
        return direct
    if code in {"FT", "F", "AGAINST"}:
        return reverse
    return min(direct, reverse)


def endpoint_consistency(left: Any, right: Any) -> float:
    from shapely.geometry import Point  # type: ignore

    left_coords = _coordinates(left)
    right_coords = _coordinates(right)
    direct = Point(left_coords[0]).distance(Point(right_coords[0])) + Point(left_coords[-1]).distance(Point(right_coords[-1]))
    reverse = Point(left_coords[0]).distance(Point(right_coords[-1])) + Point(left_coords[-1]).distance(Point(right_coords[0]))
    scale = max(left.length + right.length, 1e-9)
    return max(0.0, 1.0 - min(direct, reverse) / scale)


@dataclass(frozen=True)
class MatchMetrics:
    distance_m: float
    overlap_ratio: float
    direction_difference_degrees: float
    endpoint_consistency: float
    score: float


def line_match_metrics(edge: Any, source: Any, *, max_distance_m: float,
                       overlap_buffer_m: float, reverse_source: bool = False) -> MatchMetrics:
    if edge.is_empty or source.is_empty or edge.length <= 0 or source.length <= 0:
        return MatchMetrics(math.inf, 0.0, 180.0, 0.0, -math.inf)
    distance = float(edge.distance(source))
    overlap = min(1.0, float(edge.intersection(source.buffer(overlap_buffer_m)).length / edge.length))
    angle = direction_difference(edge, source, reverse_right=reverse_source)
    endpoint = endpoint_consistency(edge, source)
    proximity = max(0.0, 1.0 - distance / max(max_distance_m, 1e-9))
    score = 0.30 * proximity + 0.45 * overlap + 0.15 * (1.0 - angle / 180.0) + 0.10 * endpoint
    return MatchMetrics(distance, overlap, angle, endpoint, score)


def decode_polyline(encoded: str, precision: int = 5) -> list[tuple[float, float]]:
    """Decode a Google encoded polyline into `(longitude, latitude)` pairs."""
    coordinates: list[tuple[float, float]] = []
    index = latitude = longitude = 0
    factor = 10 ** precision
    while index < len(encoded):
        deltas: list[int] = []
        for _ in range(2):
            result = shift = 0
            while True:
                if index >= len(encoded):
                    raise CaseStudyError("truncated encoded polyline")
                value = ord(encoded[index]) - 63
                index += 1
                result |= (value & 0x1F) << shift
                shift += 5
                if value < 0x20:
                    break
            deltas.append(~(result >> 1) if result & 1 else result >> 1)
        latitude += deltas[0]
        longitude += deltas[1]
        coordinates.append((longitude / factor, latitude / factor))
    return coordinates


def parse_link_points(value: object) -> list[tuple[float, float]]:
    """Parse DOT `link_points` variants into lon/lat pairs."""
    text = str(value or "").strip()
    if not text:
        return []
    numbers = [float(item) for item in re.findall(r"[-+]?\d+(?:\.\d+)?", text)]
    if len(numbers) < 4 or len(numbers) % 2:
        raise CaseStudyError(f"unrecognized DOT link_points geometry: {text[:120]}")
    pairs: list[tuple[float, float]] = []
    for first, second in zip(numbers[::2], numbers[1::2]):
        if 39.0 <= first <= 42.0 and -76.0 <= second <= -71.0:
            pairs.append((second, first))
        elif -76.0 <= first <= -71.0 and 39.0 <= second <= 42.0:
            pairs.append((first, second))
        else:
            raise CaseStudyError(f"DOT coordinate outside NYC region: {first},{second}")
    return pairs


def dot_geometry(link_points: object, encoded_poly_line: object) -> Any:
    from shapely.geometry import LineString  # type: ignore

    parse_failure: CaseStudyError | None = None
    try:
        coordinates = parse_link_points(link_points)
    except CaseStudyError as failure:
        coordinates = []
        parse_failure = failure
    if len(coordinates) < 2 and str(encoded_poly_line or "").strip():
        coordinates = decode_polyline(str(encoded_poly_line).strip())
        if not all(-76.0 <= longitude <= -71.0 and 39.0 <= latitude <= 42.0
                   for longitude, latitude in coordinates):
            raise CaseStudyError("decoded DOT polyline lies outside the NYC region")
    if len(coordinates) < 2:
        if parse_failure is not None:
            raise parse_failure
        raise CaseStudyError("DOT link has neither usable link_points nor encoded_poly_line")
    return LineString(coordinates)


def ordered_edge_matches(source_line: Any, edge_lines: Iterable[tuple[int, Any]], *,
                         max_distance_m: float, overlap_buffer_m: float,
                         minimum_overlap_ratio: float,
                         maximum_direction_difference_degrees: float) -> list[tuple[int, MatchMetrics]]:
    """Match one directed source line to a spatially consistent ordered arc list."""
    matches: list[tuple[float, int, MatchMetrics]] = []
    buffered_source = source_line.buffer(overlap_buffer_m)
    for arc_id, edge in edge_lines:
        distance = float(edge.distance(source_line))
        overlap = min(1.0, float(edge.intersection(buffered_source).length / max(edge.length, 1e-9)))
        angle = direction_difference(edge, source_line)
        endpoint = endpoint_consistency(edge, source_line)
        proximity = max(0.0, 1.0 - distance / max(max_distance_m, 1e-9))
        metrics = MatchMetrics(
            distance, overlap, angle, endpoint,
            0.30 * proximity + 0.45 * overlap + 0.15 * (1.0 - angle / 180.0) + 0.10 * endpoint,
        )
        if metrics.distance_m > max_distance_m:
            continue
        if metrics.overlap_ratio < minimum_overlap_ratio:
            continue
        if metrics.direction_difference_degrees > maximum_direction_difference_degrees:
            continue
        midpoint = edge.interpolate(0.5, normalized=True)
        matches.append((float(source_line.project(midpoint)), int(arc_id), metrics))
    matches.sort(key=lambda item: (item[0], item[1]))
    return [(arc_id, metrics) for _, arc_id, metrics in matches]

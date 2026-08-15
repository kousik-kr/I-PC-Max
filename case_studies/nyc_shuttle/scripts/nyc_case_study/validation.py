"""Small case-study contract validators used by preparation and tests."""

from __future__ import annotations

import hashlib
import json
from typing import Any

from .common import CaseStudyError, canonical_json


def validate_query_horizon(interval_start: float, interval_end: float, budget: float,
                           support_start: float, support_end: float) -> None:
    if interval_start < support_start or interval_end > support_end:
        raise CaseStudyError("query departure interval lies outside temporal support")
    if interval_end + budget > support_end:
        raise CaseStudyError("query budget permits traversal beyond temporal support")


def validate_score_intervals(intervals: list[list[int]], support_start: int,
                             support_end: int, cap: int) -> None:
    """Validate a contiguous piecewise-constant score encoding."""
    expected_start = support_start
    for interval in intervals:
        if len(interval) != 3:
            raise CaseStudyError("score interval must be [start, end, value]")
        start, end, value = interval
        if start != expected_start or end <= start:
            raise CaseStudyError("score intervals are not contiguous and nonempty")
        if not isinstance(value, int) or not 0 <= value <= cap:
            raise CaseStudyError(f"score {value!r} is outside integer range [0, {cap}]")
        expected_start = end
    if expected_start != support_end:
        raise CaseStudyError("score intervals do not cover the declared temporal support")


def validate_arc_path(arcs: list[int], source: int, destination: int,
                      edge_endpoints: dict[int, tuple[int, int]],
                      travel_times: dict[int, float], budget: float) -> float:
    current = source
    visited = {source}
    travel = 0.0
    for arc in arcs:
        if arc not in edge_endpoints:
            raise CaseStudyError(f"unknown arc {arc}")
        tail, head = edge_endpoints[arc]
        if tail != current:
            raise CaseStudyError(f"discontinuous path at arc {arc}")
        if head in visited:
            raise CaseStudyError(f"path is not vertex-simple at vertex {head}")
        value = travel_times[arc]
        if value < 0:
            raise CaseStudyError(f"negative travel time on arc {arc}")
        travel += value
        current = head
        visited.add(head)
    if current != destination:
        raise CaseStudyError("path does not reach destination")
    if travel > budget + 1e-9:
        raise CaseStudyError("path exceeds budget")
    return travel


def deterministic_checksum(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()

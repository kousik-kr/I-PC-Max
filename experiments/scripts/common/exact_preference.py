"""Typed loader and rational metrics for exact-preference-envelope-v1 JSON."""
from __future__ import annotations

from dataclasses import dataclass
from fractions import Fraction
import hashlib
import json
from typing import Any


SCHEMA = "exact-preference-envelope-v1"


def _fraction(value: Any, label: str) -> Fraction:
    if not isinstance(value, str):
        raise ValueError(f"{label} must be a rational string")
    try:
        result = Fraction(value)
    except (ValueError, ZeroDivisionError) as exc:
        raise ValueError(f"invalid rational {label}: {value!r}") from exc
    canonical = str(result.numerator) if result.denominator == 1 else f"{result.numerator}/{result.denominator}"
    if value != canonical:
        raise ValueError(f"non-canonical rational {label}: {value!r}")
    return result


@dataclass(frozen=True)
class ExactInterval:
    start: Fraction
    end: Fraction
    start_inclusive: bool
    end_inclusive: bool

    @classmethod
    def from_json(cls, value: Any, label: str) -> "ExactInterval":
        if not isinstance(value, dict):
            raise ValueError(f"{label} must be an interval object")
        if set(value) != {"start", "end", "start_inclusive", "end_inclusive"}:
            raise ValueError(f"{label} has fields outside the exact interval schema")
        start = _fraction(value.get("start"), f"{label}.start")
        end = _fraction(value.get("end"), f"{label}.end")
        left = value.get("start_inclusive")
        right = value.get("end_inclusive")
        if not isinstance(left, bool) or not isinstance(right, bool):
            raise ValueError(f"{label} endpoint ownership must be boolean")
        if start > end or (start == end and not (left and right)):
            raise ValueError(f"{label} is empty or reversed")
        return cls(start, end, left, right)

    def contains(self, point: Fraction) -> bool:
        return ((point > self.start or (point == self.start and self.start_inclusive))
                and (point < self.end or (point == self.end and self.end_inclusive)))


@dataclass(frozen=True)
class ExactPreferenceCell:
    interval: ExactInterval
    selected: bool
    score: int | None
    path: tuple[int, ...] | None
    arrival_slope: Fraction | None
    arrival_intercept: Fraction | None


@dataclass(frozen=True)
class ExactPreferenceMetrics:
    total_measure: Fraction
    feasible_measure: Fraction
    coverage: Fraction
    integrated_preference: Fraction
    average_selected_preference: Fraction | None


@dataclass(frozen=True)
class ExactPreferenceProfile:
    domain: tuple[ExactInterval, ...]
    cells: tuple[ExactPreferenceCell, ...]
    checksum: str
    metrics: ExactPreferenceMetrics


def decode_exact_preference_profile(value: Any, expected_checksum: str | None = None) -> ExactPreferenceProfile:
    """Validate profile schema, partition ownership, arrival relation, and checksum."""
    if not isinstance(value, dict) or value.get("schema") != SCHEMA:
        raise ValueError(f"expected {SCHEMA}")
    if set(value) != {"schema", "domain", "cells"}:
        raise ValueError("exact profile has fields outside the versioned schema")
    raw_domain = value.get("domain")
    raw_cells = value.get("cells")
    if not isinstance(raw_domain, list) or not isinstance(raw_cells, list):
        raise ValueError("exact profile domain and cells must be arrays")
    domain = tuple(ExactInterval.from_json(interval, f"domain[{index}]")
                   for index, interval in enumerate(raw_domain))
    cells: list[ExactPreferenceCell] = []
    for index, raw in enumerate(raw_cells):
        if not isinstance(raw, dict):
            raise ValueError(f"cells[{index}] must be an object")
        state = raw.get("status")
        expected_fields = (
            {"interval", "status", "budget_feasible"}
            if state == "NO_PATH"
            else {
                "interval", "status", "budget_feasible", "score", "path",
                "arrival", "travel_time",
            }
        )
        if set(raw) != expected_fields:
            raise ValueError(f"cells[{index}] has fields outside the exact cell schema")
        interval = ExactInterval.from_json(raw.get("interval"), f"cells[{index}].interval")
        feasible = raw.get("budget_feasible")
        if state == "NO_PATH":
            if feasible is not False:
                raise ValueError("NO_PATH cells must have budget_feasible=false")
            cells.append(ExactPreferenceCell(interval, False, None, None, None, None))
            continue
        if state != "SELECTED" or feasible is not True:
            raise ValueError(f"invalid exact profile cell status at {index}")
        score_text = raw.get("score")
        if not isinstance(score_text, str) or not score_text.isdigit():
            raise ValueError(f"selected score at {index} must be a nonnegative integer string")
        path_value = raw.get("path")
        if not isinstance(path_value, list) or any(type(arc) is not int or arc < 0 for arc in path_value):
            raise ValueError(f"selected path at {index} must be an arc-ID array")
        arrival = raw.get("arrival")
        travel = raw.get("travel_time")
        if not isinstance(arrival, dict) or not isinstance(travel, dict):
            raise ValueError(f"selected cell {index} has no affine arrival/travel profile")
        slope = _fraction(arrival.get("slope"), f"cells[{index}].arrival.slope")
        intercept = _fraction(arrival.get("intercept"), f"cells[{index}].arrival.intercept")
        if (_fraction(travel.get("slope"), f"cells[{index}].travel_time.slope") != slope - 1
                or _fraction(travel.get("intercept"), f"cells[{index}].travel_time.intercept") != intercept):
            raise ValueError(f"selected cell {index} has an inconsistent travel-time relation")
        cells.append(ExactPreferenceCell(interval, True, int(score_text), tuple(path_value), slope, intercept))
    _validate_partition(domain, tuple(cells))
    total = sum((interval.end - interval.start for interval in domain), Fraction())
    feasible_measure = sum((cell.interval.end - cell.interval.start for cell in cells
                            if cell.selected), Fraction())
    integrated = sum(((cell.interval.end - cell.interval.start) * (cell.score or 0)
                      for cell in cells if cell.selected), Fraction())
    if total:
        coverage = feasible_measure / total
    else:
        coverage = Fraction(1 if domain and all(
            interval.start == interval.end and _selected_at(cells, interval.start)
            for interval in domain) else 0)
    average = integrated / feasible_measure if feasible_measure else None
    canonical_value = {
        "schema": SCHEMA,
        "domain": [_canonical_interval(interval) for interval in raw_domain],
        "cells": [_canonical_cell(cell) for cell in raw_cells],
    }
    serialized = json.dumps(
        canonical_value, ensure_ascii=False, separators=(",", ":")
    ) + "\n"
    checksum = hashlib.sha256(serialized.encode("utf-8")).hexdigest()
    if expected_checksum is not None and checksum != expected_checksum:
        raise ValueError("exact preference profile checksum mismatch")
    return ExactPreferenceProfile(domain, tuple(cells), checksum,
                                  ExactPreferenceMetrics(total, feasible_measure,
                                      coverage, integrated, average))


def _canonical_interval(value: dict[str, Any]) -> dict[str, Any]:
    """Use the field order emitted by ExactPreferenceProfileCodec in Java."""
    return {
        "start": value["start"],
        "end": value["end"],
        "start_inclusive": value["start_inclusive"],
        "end_inclusive": value["end_inclusive"],
    }


def _canonical_cell(value: dict[str, Any]) -> dict[str, Any]:
    result = {
        "interval": _canonical_interval(value["interval"]),
        "status": value["status"],
        "budget_feasible": value["budget_feasible"],
    }
    if value["status"] == "SELECTED":
        result.update({
            "score": value["score"],
            "path": value["path"],
            "arrival": {
                "slope": value["arrival"]["slope"],
                "intercept": value["arrival"]["intercept"],
            },
            "travel_time": {
                "slope": value["travel_time"]["slope"],
                "intercept": value["travel_time"]["intercept"],
            },
        })
    return result


def _selected_at(cells: tuple[ExactPreferenceCell, ...], point: Fraction) -> bool:
    matches = [cell for cell in cells if cell.interval.contains(point)]
    return len(matches) == 1 and matches[0].selected


def _domain_contains(domain: tuple[ExactInterval, ...], point: Fraction) -> bool:
    return any(interval.contains(point) for interval in domain)


def _validate_partition(domain: tuple[ExactInterval, ...], cells: tuple[ExactPreferenceCell, ...]) -> None:
    cuts = {endpoint for interval in (*domain, *(cell.interval for cell in cells))
            for endpoint in (interval.start, interval.end)}
    ordered = sorted(cuts)
    probes = set(ordered)
    probes.update((left + right) / 2 for left, right in zip(ordered, ordered[1:]) if left < right)
    for probe in probes:
        expected = _domain_contains(domain, probe)
        matches = sum(cell.interval.contains(probe) for cell in cells)
        if matches > 1 or matches != int(expected):
            raise ValueError(f"exact profile cells do not partition the domain at {probe}")

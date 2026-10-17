"""Exact validation and metrics for the THop fastest-travel-time profile."""
from __future__ import annotations

from dataclasses import dataclass
from fractions import Fraction
import hashlib
import json
import math
from typing import Any


def _fraction(value: Any, label: str) -> Fraction:
    if not isinstance(value, str):
        raise ValueError(f"{label} must be a rational string")
    try:
        parsed = Fraction(value)
    except (ValueError, ZeroDivisionError) as error:
        raise ValueError(f"invalid rational {label}: {value!r}") from error
    canonical = (
        str(parsed.numerator)
        if parsed.denominator == 1
        else f"{parsed.numerator}/{parsed.denominator}"
    )
    if value != canonical:
        raise ValueError(f"non-canonical rational {label}: {value!r}")
    return parsed


def _number_fraction(value: Any, label: str) -> Fraction:
    if isinstance(value, bool) or not isinstance(value, (int, float, str)):
        raise ValueError(f"{label} must be a finite decimal number")
    try:
        parsed_float = float(value)
        if not math.isfinite(parsed_float):
            raise ValueError(f"{label} must be finite")
        return Fraction(str(value))
    except (ValueError, ZeroDivisionError) as error:
        raise ValueError(f"invalid {label}: {value!r}") from error


@dataclass(frozen=True)
class Interval:
    start: Fraction
    end: Fraction
    start_inclusive: bool
    end_inclusive: bool

    def contains(self, point: Fraction) -> bool:
        return (
            (point > self.start or (point == self.start and self.start_inclusive))
            and (point < self.end or (point == self.end and self.end_inclusive))
        )


@dataclass(frozen=True)
class FastestCell:
    interval: Interval
    travel_time_slope: Fraction
    travel_time_intercept: Fraction

    def travel_time_at(self, departure: Fraction) -> Fraction:
        return self.travel_time_slope * departure + self.travel_time_intercept

    @property
    def integrated_travel_time(self) -> Fraction:
        left, right = self.interval.start, self.interval.end
        return self.travel_time_slope * (right * right - left * left) / 2 + self.travel_time_intercept * (right - left)


@dataclass(frozen=True)
class FastestProfile:
    requested_domain: tuple[Interval, ...]
    cells: tuple[FastestCell, ...]
    total_measure: Fraction
    covered_measure: Fraction
    coverage: Fraction
    integrated_travel_time: Fraction
    average_travel_time: Fraction | None
    checksum: str


def _interval(value: Any, label: str, *, rational: bool) -> Interval:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an interval object")
    start_parser = _fraction if rational else _number_fraction
    start = start_parser(value.get("start"), f"{label}.start")
    end = start_parser(value.get("end"), f"{label}.end")
    left, right = value.get("start_inclusive"), value.get("end_inclusive")
    if not isinstance(left, bool) or not isinstance(right, bool):
        raise ValueError(f"{label} endpoint ownership must be boolean")
    if start > end or (start == end and not (left and right)):
        raise ValueError(f"{label} is empty or reversed")
    return Interval(start, end, left, right)


def _inside_domain(cell: Interval, domain: tuple[Interval, ...]) -> bool:
    return any(
        cell.start >= part.start
        and cell.end <= part.end
        and (cell.start != part.start or not cell.start_inclusive or part.start_inclusive)
        and (cell.end != part.end or not cell.end_inclusive or part.end_inclusive)
        for part in domain
    )


def decode_fastest_profile(
    objective: Any,
    expected_checksum: str | None = None,
) -> FastestProfile:
    """Validate exact travel-time cells and compute travel-time-only metrics.

    The payload already describes a fastest travel-time function. No preference scores or
    PACE travel budget are read or inferred here.
    """
    if not isinstance(objective, dict) or objective.get("objective") != "fastest_profile":
        raise ValueError("expected a fastest_profile objective result")
    if objective.get("precision") != "exact_rational":
        raise ValueError("THop profile is not marked exact_rational")
    raw_domain = objective.get("requested_departure_domain")
    raw_cells = objective.get("fastest_travel_time_profile")
    if not isinstance(raw_domain, list) or not raw_domain:
        raise ValueError("fastest profile has no requested departure domain")
    if not isinstance(raw_cells, list):
        raise ValueError("fastest profile cells must be an array")
    domain = tuple(
        _interval(value, f"requested_departure_domain[{index}]", rational=False)
        for index, value in enumerate(raw_domain)
    )
    if any(domain[index].start > domain[index + 1].start for index in range(len(domain) - 1)):
        raise ValueError("requested departure intervals are not ordered")
    for previous, current in zip(domain, domain[1:]):
        if previous.end > current.start or (
            previous.end == current.start
            and previous.end_inclusive
            and current.start_inclusive
        ):
            raise ValueError("requested departure intervals overlap")
    total_measure = sum((part.end - part.start for part in domain), Fraction())

    cells: list[FastestCell] = []
    for index, raw in enumerate(raw_cells):
        interval = _interval(raw, f"fastest_travel_time_profile[{index}]", rational=True)
        if not _inside_domain(interval, domain):
            raise ValueError(f"fastest profile cell {index} lies outside the query domain")
        slope = _fraction(raw.get("slope"), f"fastest_travel_time_profile[{index}].slope")
        intercept = _fraction(raw.get("intercept"), f"fastest_travel_time_profile[{index}].intercept")
        cell = FastestCell(interval, slope, intercept)
        if cell.travel_time_at(interval.start) < 0 or cell.travel_time_at(interval.end) < 0:
            raise ValueError(f"fastest profile cell {index} has negative travel time")
        cells.append(cell)

    cells.sort(key=lambda cell: (cell.interval.start, cell.interval.end))
    for previous, current in zip(cells, cells[1:]):
        if previous.interval.end > current.interval.start or (
            previous.interval.end == current.interval.start
            and previous.interval.end_inclusive
            and current.interval.start_inclusive
        ):
            raise ValueError("fastest profile cells overlap")

    covered_measure = sum(
        (cell.interval.end - cell.interval.start for cell in cells), Fraction()
    )
    if covered_measure > total_measure:
        raise ValueError("fastest profile coverage exceeds the requested domain")
    if total_measure:
        coverage = covered_measure / total_measure
    else:
        points = {part.start for part in domain}
        coverage = Fraction(
            sum(any(cell.interval.contains(point) for cell in cells) for point in points),
            len(points),
        )
    integrated = sum((cell.integrated_travel_time for cell in cells), Fraction())
    average = integrated / covered_measure if covered_measure else None
    if objective.get("feasible") is not bool(cells):
        raise ValueError("fastest profile feasible flag disagrees with its cells")
    serialized = json.dumps(
        objective, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ) + "\n"
    checksum = hashlib.sha256(serialized.encode("utf-8")).hexdigest()
    if expected_checksum is not None and checksum != expected_checksum:
        raise ValueError("fastest profile checksum mismatch")
    return FastestProfile(
        domain, tuple(cells), total_measure, covered_measure, coverage,
        integrated, average, checksum,
    )

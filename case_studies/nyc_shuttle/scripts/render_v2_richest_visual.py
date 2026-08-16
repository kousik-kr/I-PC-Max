#!/usr/bin/env python3
"""Render the first, deterministically selected NYC-REAL-V2 qualitative figure.

This script is deliberately read-only with respect to the frozen V1/V2 bundles.
It selects the richest normally completed 30-second result using the published
lexicographic rule and writes only the requested root-level figure artifacts.
"""

from __future__ import annotations

import argparse
import bisect
import gzip
import hashlib
import json
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_EVEN
from pathlib import Path
from zoneinfo import ZoneInfo

import geopandas as gpd
import matplotlib as mpl
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection
from matplotlib.lines import Line2D
from matplotlib.ticker import MaxNLocator
from matplotlib.transforms import Bbox


ROOT = Path(__file__).resolve().parents[3]
V2 = ROOT / "experiments/results/Final-result/NYC-real-shuttle-v2"
RESULT = V2 / "results/diagnostic_30s.jsonl"
STORY = V2 / "validation/story_diagnostic_30s.jsonl"
DATASET = V2 / "processed/NYC-REAL-V2"
EDGES = ROOT / "case_studies/nyc_shuttle/intermediate/dimacs_ny_edges.parquet"
FIGURES = ROOT / "figures"

COLORS = ("#0072B2", "#E69F00", "#009E73", "#D55E00", "#CC79A7")
INTERVAL_BEST_GREEN = "#138A36"
TIME_QUANTUM = Decimal("0.000000000001")

# Exact final-frontier supports recovered by a deterministic corrected-V2
# diagnostic rerun.  The rerun checksums are asserted before these profiles
# are used, and each supported integer departure is independently replayed
# below.  Keeping the exact continuous endpoints prevents a structural path
# from being drawn outside the domain of the candidate that introduced it.
CORRECTED_CANDIDATE_PROFILES = {
    "NYC-Q012-MORNING-R20": {
        "final_checksum":
            "4ae66017b536f29bf6a77e3cbacdaa49f27182e0c122b05da329d9471000f7d3",
        "paths": (
            {
                "path_sha256":
                    "d0ac4fc69e0cae255f28d59ab5702ee40c84f256c201ccbef62ade929a39e3d4",
                "domain": ((420.0, 540.0),),
                "scores": ((420.0, 540.0, 17),),
            },
            {
                "path_sha256":
                    "dacee30c8df77f1d532f72bc107e87a7f9344149b6d81268f5aa354dbe4c8512",
                "domain": ((460.225405384432, 479.096989970900),),
                "scores": ((460.225405384432, 479.096989970900, 38),),
            },
            {
                "path_sha256":
                    "58131a03d9d2e8177666678a00689a72c0d9a200de9a59b1dea5f4bc6f49e92f",
                "domain": ((461.933054188047, 477.704879320178),),
                "scores": ((461.933054188047, 477.704879320178, 86),),
            },
            {
                "path_sha256":
                    "f3759d4d458104d30777172271255e1c0818fa0f468c59a24a72d62988188fc1",
                "domain": ((461.026743242151, 478.192394711692),),
                "scores": (
                    (461.026743242151, 475.137518946577, 43),
                    (475.137518946577, 478.192394711692, 42),
                ),
            },
            {
                "path_sha256":
                    "92a1eef0c2b9cb16fb588b3cf2ae825b4cb7fc6a1a629b6a05fd94228125e59b",
                "domain": ((517.915691715615, 524.403084026168),),
                "scores": ((517.915691715615, 524.403084026168, 38),),
            },
        ),
    },
    "NYC-Q020-MORNING-R20": {
        "final_checksum":
            "fc5108a6e0d227d8911f7e181cf341e1fbe351b365e68068361f9f019d7cb47b",
        "paths": (
            {
                "path_sha256":
                    "c909c41ad3e556d0c63dca77c3fa7aa477fcf817f85ca233ff8b8d4a77ba99f7",
                "domain": ((420.0, 540.0),),
                "scores": ((420.0, 540.0, 81),),
            },
        ),
    },
}

# Preserve the established page/canvas dimensions while panel (b) is replaced.
EXPORT_DIMENSIONS = {
    "NYC-Q012-MORNING-R20": {
        "pdf_points": (909.427, 418.915),
        "png_pixels": (3789, 1744),
    },
    "NYC-Q020-MORNING-R20": {
        "pdf_points": (865.720, 418.915),
        "png_pixels": (3607, 1744),
    },
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_result(query_id: str | None = None) -> dict:
    completed = []
    with RESULT.open(encoding="utf-8") as stream:
        for line in stream:
            row = json.loads(line)
            if row.get("status") == "COMPLETE" and row.get("pace_b"):
                completed.append(row)
    if not completed:
        raise RuntimeError("diagnostic_30s.jsonl has no normally completed result")
    if query_id is not None:
        matches = [row for row in completed if row["query_id"] == query_id]
        if len(matches) != 1:
            raise RuntimeError(f"expected one completed 30-second result for {query_id}")
        return matches[0]
    return min(
        completed,
        key=lambda row: (
            -int(row["pace_b"]["distinct_path_count"]),
            -int(row["pace_b"]["route_switches"]),
            -int(row["pace_b"]["profile_cell_count"]),
            row["query_id"],
        ),
    )


def line_segments(frame: gpd.GeoDataFrame) -> list[list[tuple[float, float]]]:
    segments: list[list[tuple[float, float]]] = []
    for geometry in frame.geometry:
        if geometry.geom_type == "LineString":
            segments.append(list(geometry.coords))
        elif geometry.geom_type == "MultiLineString":
            segments.extend(list(part.coords) for part in geometry.geoms)
    return segments


def local_clock(minute: int) -> str:
    midnight = datetime(2026, 5, 14, tzinfo=ZoneInfo("America/New_York"))
    return (midnight + timedelta(minutes=minute)).strftime("%H:%M")


def load_story(query_id: str) -> dict:
    with STORY.open(encoding="utf-8") as stream:
        for line in stream:
            row = json.loads(line)
            if row["query_id"] == query_id:
                return row
    raise RuntimeError(f"missing exact-replay story record for {query_id}")


def canonical_time(value: float) -> float:
    """Mirror Domain.canonicalTime's 10^-12-minute HALF_EVEN model."""
    return float(Decimal(str(value)).quantize(TIME_QUANTUM, rounding=ROUND_HALF_EVEN))


def load_exact_edge_functions(
    arc_ids: set[int],
) -> tuple[dict[int, tuple[tuple[float, ...], tuple[float, ...]]],
           dict[int, tuple[tuple[float, float, int], ...]]]:
    travel: dict[int, tuple[tuple[float, ...], tuple[float, ...]]] = {}
    with gzip.open(DATASET / "travel_time_functions.jsonl.gz", "rt", encoding="utf-8") as stream:
        for line in stream:
            row = json.loads(line)
            arc_id = int(row["arc_id"])
            if arc_id not in arc_ids:
                continue
            breakpoints = row["travel_time_breakpoints"]
            travel[arc_id] = (
                tuple(float(point[0]) for point in breakpoints),
                tuple(float(point[1]) for point in breakpoints),
            )
    missing = arc_ids.difference(travel)
    if missing:
        raise RuntimeError(f"missing travel functions for {len(missing)} plotted arcs")

    scores: dict[int, tuple[tuple[float, float, int], ...]] = {}
    with gzip.open(DATASET / "score_functions.jsonl.gz", "rt", encoding="utf-8") as stream:
        for line in stream:
            row = json.loads(line)
            arc_id = int(row["arc_id"])
            if arc_id in arc_ids:
                scores[arc_id] = tuple(
                    (float(start), float(end), int(value))
                    for start, end, value in row["score_intervals"]
                )
    return travel, scores


def travel_time_at(
    function: tuple[tuple[float, ...], tuple[float, ...]], minute: float
) -> float:
    minutes, values = function
    minute = canonical_time(minute)
    index = bisect.bisect_left(minutes, minute)
    if index < len(minutes) and minutes[index] == minute:
        return canonical_time(values[index])
    if index == 0 or index == len(minutes):
        raise RuntimeError(f"travel-time evaluation outside function domain at {minute}")
    left_time, right_time = minutes[index - 1], minutes[index]
    alpha = (minute - left_time) / (right_time - left_time)
    value = values[index - 1] + alpha * (values[index] - values[index - 1])
    return canonical_time(value)


def score_at(
    intervals: tuple[tuple[float, float, int], ...] | None, minute: float
) -> int:
    if intervals is None:
        return 0
    minute = canonical_time(minute)
    for index, (start, end, value) in enumerate(intervals):
        if start <= minute < end:
            return value
        next_starts_here = index + 1 < len(intervals) and intervals[index + 1][0] == minute
        if minute == end and not next_starts_here:
            return value
    raise RuntimeError(f"score evaluation outside function domain at {minute}")


def replay_path(
    path: tuple[int, ...],
    departure: int,
    travel: dict[int, tuple[tuple[float, ...], tuple[float, ...]]],
    scores: dict[int, tuple[tuple[float, float, int], ...]],
) -> tuple[int, float]:
    current = float(departure)
    score = 0
    for arc_id in path:
        current = canonical_time(current)
        score += score_at(scores.get(arc_id), current)
        current = canonical_time(current + travel_time_at(travel[arc_id], current))
    return score, canonical_time(current - departure)


def exact_score_profiles(
    row: dict, path_keys: list[tuple[int, ...]], story: dict
) -> tuple[list[int], list[list[int]], list[list[float]], list[list[bool]], dict]:
    registry = CORRECTED_CANDIDATE_PROFILES.get(row["query_id"])
    if registry is None or row["final_checksum"] != registry["final_checksum"]:
        raise RuntimeError("missing or mismatched corrected candidate registry")
    definitions = registry["paths"]
    if len(definitions) != len(path_keys):
        raise RuntimeError("candidate registry path count mismatch")
    for path, definition in zip(path_keys, definitions):
        digest = hashlib.sha256(
            ",".join(str(arc_id) for arc_id in path).encode("ascii")
        ).hexdigest()
        if digest != definition["path_sha256"]:
            raise RuntimeError("candidate registry path identity mismatch")

    departures = list(range(int(row["interval_start"]), int(row["interval_end"]) + 1))
    required_arcs = {arc_id for path in path_keys for arc_id in path}
    travel, scores = load_exact_edge_functions(required_arcs)
    profiles: list[list[int]] = []
    travel_profiles: list[list[float]] = []
    feasible_masks: list[list[bool]] = []
    for path, definition in zip(path_keys, definitions):
        replayed = [replay_path(path, departure, travel, scores) for departure in departures]
        profiles.append([value[0] for value in replayed])
        travel_profiles.append([value[1] for value in replayed])
        supported = [
            any(left <= departure <= right for left, right in definition["domain"])
            for departure in departures
        ]
        feasible = [
            in_domain and value[1] <= float(row["budget"]) + 1e-9
            for in_domain, value in zip(supported, replayed)
        ]
        if supported != feasible:
            raise RuntimeError("candidate support contains a budget-infeasible replay")
        feasible_masks.append(feasible)
        for departure, (score, _), in_domain in zip(departures, replayed, supported):
            if not in_domain:
                continue
            expected = next(
                (
                    value
                    for piece_index, (left, right, value)
                    in enumerate(definition["scores"])
                    if left <= departure < right
                    or (piece_index == len(definition["scores"]) - 1
                        and departure == right)
                ),
                None,
            )
            if score != expected:
                raise RuntimeError("candidate score metadata disagrees with exact replay")

    # Prove that the replay also reproduces every stored selected-path point.
    departure_index = {departure: index for index, departure in enumerate(departures)}
    path_index = {path: index for index, path in enumerate(path_keys)}
    for point in story["points"]:
        selected_path = tuple(point["pace_path"])
        if selected_path not in path_index:
            raise RuntimeError("stored selected path is absent from plotted path registry")
        p_index = path_index[selected_path]
        d_index = departure_index[int(point["departure"])]
        if profiles[p_index][d_index] != int(point["pace_score"]):
            raise RuntimeError("exact score replay disagrees with stored V2 selected profile")
        if abs(travel_profiles[p_index][d_index] - float(point["pace_travel_time"])) > 1e-9:
            raise RuntimeError("exact travel replay disagrees with stored V2 selected profile")

    interval_best = story["trace_rerun"]["interval_best"]
    if not interval_best.get("found"):
        raise RuntimeError("visual query lacks an interval-best result")
    best_path = tuple(interval_best["path"])
    if best_path not in path_index:
        raise RuntimeError("interval-best path is absent from plotted compact paths")
    best_departure = int(interval_best["departure"])
    best_path_index = path_index[best_path]
    best_departure_index = departure_index[best_departure]
    if profiles[best_path_index][best_departure_index] != int(interval_best["score"]):
        raise RuntimeError("interval-best score disagrees with exact plotted profile")
    if abs(
        travel_profiles[best_path_index][best_departure_index]
        - float(interval_best["travel_time"])
    ) > 1e-9:
        raise RuntimeError("interval-best travel time disagrees with exact replay")
    return departures, profiles, travel_profiles, feasible_masks, {
        "departure": best_departure,
        "path_index": best_path_index,
        "score": int(interval_best["score"]),
    }


def step_runs(axis, departures, values, visible, **style) -> None:
    """Draw minute-level steps without bridging masked candidate domains."""
    start = None
    for index in range(len(departures) + 1):
        active = index < len(departures) and visible[index]
        if active and start is None:
            start = index
        if not active and start is not None:
            shown_departures = departures[start:index]
            shown_values = values[start:index]
            axis.step(
                shown_departures + [shown_departures[-1] + 1],
                shown_values + [shown_values[-1]],
                where="post",
                **style,
            )
            start = None


def style_score_axis(axis, start: int, end: int) -> None:
    ticks = list(range(start, end + 1, 30))
    if ticks[-1] != end:
        ticks.append(end)
    axis.set_xticks(ticks)
    axis.tick_params(axis="x", labelbottom=False, length=0)
    axis.set_xlim(start, end + 1)
    axis.set_ylabel("Score (higher is better)", labelpad=7)
    axis.set_title("(b) Score profiles and resulting path profile", loc="left", pad=8)
    axis.yaxis.set_major_locator(MaxNLocator(nbins=5, integer=True))
    axis.set_axisbelow(True)
    axis.grid(axis="both", color="#e2e5e7", linewidth=0.65)
    axis.spines[["right", "top", "bottom"]].set_visible(False)
    axis.spines["left"].set_color("#777777")


def style_strip_axis(axis, start: int, end: int) -> None:
    ticks = list(range(start, end + 1, 30))
    if ticks[-1] != end:
        ticks.append(end)
    axis.set_xticks(ticks, [local_clock(value) for value in ticks])
    axis.set_xlim(start, end + 1)
    axis.set_ylim(-0.72, 1.64)
    axis.set_yticks([])
    axis.set_xlabel("Local departure time (EDT, 14 May 2026)", labelpad=7)
    axis.spines[["left", "right", "top"]].set_visible(False)
    axis.spines["bottom"].set_color("#777777")
    axis.grid(axis="x", color="#e2e5e7", linewidth=0.65, zorder=0)


def render_role_a_strip(axis, row, pace_cells, path_keys) -> None:
    start = int(row["interval_start"])
    end = int(row["interval_end"])
    right_edges = [
        int(pace_cells[index + 1]["start"])
        if index + 1 < len(pace_cells) else end + 1
        for index in range(len(pace_cells))
    ]
    short_positions = {
        1: (start + 31, 1.08),
        3: (start + 50, 1.45),
        4: (start + 70, 1.08),
        6: (start + 101, 1.12),
    }
    for index, (cell, right) in enumerate(zip(pace_cells, right_edges)):
        path_id = path_keys.index(tuple(cell["arc_ids"])) + 1
        left = int(cell["start"])
        width = right - left
        axis.barh(
            0.42, width, left=left, height=0.58,
            color=COLORS[path_id - 1], edgecolor="white", linewidth=0.9, zorder=3,
        )
        label = (
            f"P{path_id}\nS={float(cell['average_score']):.0f}\n"
            f"T={float(cell['average_travel_time']):.3f} min"
        )
        center = left + width / 2
        if width >= 14:
            axis.text(
                center, 0.42, label, ha="center", va="center", fontsize=6.7,
                linespacing=1.05,
                color="white" if path_id in (1, 3, 4) else "#111111",
                fontweight="bold", zorder=5,
            )
        else:
            label_x, label_y = short_positions.get(index, (center, 1.08))
            axis.annotate(
                label, xy=(center, 0.73), xytext=(label_x, label_y),
                ha="center", va="bottom", fontsize=6.25, linespacing=1.02,
                bbox={"boxstyle": "round,pad=0.18", "facecolor": "white",
                      "edgecolor": COLORS[path_id - 1], "linewidth": 0.8},
                arrowprops={"arrowstyle": "-", "color": COLORS[path_id - 1],
                            "linewidth": 0.8, "shrinkA": 0, "shrinkB": 1},
                zorder=8,
            )
    axis.text(
        (start + end) / 2, -0.48,
        "maximize transit-corridor affinity subject to T ≤ B",
        ha="center", va="top", fontsize=7.2, color="#333333",
    )


def render_role_b_strip(axis, row, pace_cells) -> None:
    start = int(row["interval_start"])
    end = int(row["interval_end"])
    cell = pace_cells[0]
    axis.barh(
        0.42, end + 1 - start, left=start, height=0.58,
        color=COLORS[0], edgecolor="white", linewidth=0.9, zorder=3,
    )
    axis.text(
        (start + end + 1) / 2, 0.42,
        f"P1    S={float(cell['average_score']):.0f}    "
        f"T={float(cell['average_travel_time']):.3f} min",
        ha="center", va="center", fontsize=7.6, color="white",
        fontweight="bold", zorder=5,
    )
    axis.text(
        start, 1.48,
        f"Fastest:  avg S = {float(row['fastest']['average_score']):.3f}"
        f"    avg T = {float(row['fastest']['average_travel_time']):.3f} min\n"
        f"Travel-time premium = {float(row['travel_time_premium_percent']):.4f}%",
        ha="left", va="top", fontsize=7.3, color="#30343B",
    )
    axis.text(
        end, 1.48, f"B = {float(row['budget']):.3f} min",
        ha="right", va="top", fontsize=7.3, color="#333333",
    )
    axis.text(
        (start + end) / 2, -0.48,
        "maximize transit-corridor affinity subject to T ≤ B",
        ha="center", va="top", fontsize=7.2, color="#333333",
    )


def render_score_panel(
    score_axis, strip_axis, row: dict, role: str,
    path_keys: list[tuple[int, ...]], pace_cells: list[dict],
) -> dict:
    story = load_story(row["query_id"])
    departures, profiles, _, feasible_masks, interval_best = exact_score_profiles(
        row, path_keys, story
    )
    plotted_values: list[int] = []
    start, end = departures[0], departures[-1]

    if role == "A":
        for index, profile in enumerate(profiles):
            step_runs(
                score_axis, departures, profile, feasible_masks[index],
                color=COLORS[index], linewidth=1.55, alpha=0.72, zorder=3,
            )
            plotted_values.extend(
                value for value, visible in zip(profile, feasible_masks[index]) if visible
            )
        departure_index = {value: index for index, value in enumerate(departures)}
        for cell in pace_cells:
            path_index = path_keys.index(tuple(cell["arc_ids"]))
            selected_departures = list(range(int(cell["start"]), int(cell["end"]) + 1))
            selected_values = [
                profiles[path_index][departure_index[value]]
                for value in selected_departures
            ]
            score_axis.step(
                selected_departures + [selected_departures[-1] + 1],
                selected_values + [selected_values[-1]], where="post",
                color=COLORS[path_index], linewidth=3.15,
                solid_capstyle="butt", zorder=7,
            )
        render_role_a_strip(strip_axis, row, pace_cells, path_keys)
        score_axis.legend(
            handles=[
                Line2D([0], [0], color=COLORS[index], linewidth=2.2,
                       label=f"P{index + 1}")
                for index in range(len(path_keys))
            ],
            loc="upper right", ncol=3, frameon=True, framealpha=0.96,
            facecolor="white", edgecolor="#c9ced2", handlelength=2.1,
            columnspacing=0.9,
        )
        score_axis.text(
            0.985, 0.79,
            f"Fixed travel-time budget B = {float(row['budget']):.3f} min",
            transform=score_axis.transAxes,
            ha="right", va="top", fontsize=7.4, color="#333333",
        )
    elif role == "B":
        step_runs(
            score_axis, departures, profiles[0], feasible_masks[0],
            color=COLORS[0], linewidth=3.0, zorder=5,
        )
        fastest_scores = [int(point["stored_fastest_score"]) for point in story["points"]]
        fastest_visible = [
            bool(point["stored_fastest_valid"])
            and float(point["stored_fastest_travel_time"]) <= float(row["budget"]) + 1e-9
            for point in story["points"]
        ]
        if len(fastest_scores) != len(departures) or not all(fastest_visible):
            raise RuntimeError("Role B fastest profile is not fully valid and feasible")
        step_runs(
            score_axis, departures, fastest_scores, fastest_visible,
            color="#30343B", linewidth=2.0, linestyle="--", dashes=(5, 3),
            zorder=8,
        )
        plotted_values.extend(profiles[0])
        plotted_values.extend(fastest_scores)
        render_role_b_strip(strip_axis, row, pace_cells)
        score_axis.legend(
            handles=[
                Line2D([0], [0], color=COLORS[0], linewidth=2.5, label="P1"),
                Line2D([0], [0], color="#30343B", linewidth=2.0,
                       linestyle="--", label="Fastest"),
            ],
            loc="upper right", frameon=True, framealpha=0.96,
            facecolor="white", edgecolor="#c9ced2", handlelength=2.6,
        )
    else:
        raise RuntimeError("score-profile panel is defined only for Roles A and B")

    span = max(plotted_values) - min(plotted_values)
    padding = max(4.0, span * 0.10)
    score_axis.set_ylim(max(0.0, min(plotted_values) - padding), max(plotted_values) + padding)
    style_score_axis(score_axis, start, end)
    style_strip_axis(strip_axis, start, end)

    switches = [int(cell["start"]) for cell in pace_cells[1:]]
    for departure in switches:
        for axis in (score_axis, strip_axis):
            axis.axvline(
                departure, color="#aeb5ba", linewidth=0.7,
                linestyle=(0, (1.2, 3.0)), alpha=0.68, zorder=1,
            )

    if role == "A":
        best_departure = interval_best["departure"]
        best_path_id = interval_best["path_index"] + 1
        best_score = interval_best["score"]
        for axis in (score_axis, strip_axis):
            axis.axvline(
                best_departure, color=INTERVAL_BEST_GREEN, linewidth=1.05,
                linestyle=(0, (4, 3)), alpha=0.9, zorder=9,
            )
        score_axis.scatter(
            [best_departure], [best_score], s=38,
            color=INTERVAL_BEST_GREEN, edgecolor="white", linewidth=0.7, zorder=30,
        )
        score_axis.annotate(
            f"Interval-best:\n{local_clock(best_departure)}, P{best_path_id}",
            xy=(best_departure, best_score),
            xytext=(best_departure + 8, best_score + 2.5),
            ha="left", va="center", fontsize=7.5, color=INTERVAL_BEST_GREEN,
            arrowprops={"arrowstyle": "-", "color": INTERVAL_BEST_GREEN, "lw": 0.9},
            zorder=31,
        )
    return interval_best


def render_assignment_panel(
    axis, row: dict, pace_cells: list[dict], path_keys: list[tuple[int, ...]]
) -> None:
    """Preserve the previous Role C assignment-band panel unchanged."""
    start = int(row["interval_start"])
    end = int(row["interval_end"])
    short_positions = {
        1: (start + 31, 1.42),
        3: (start + 50, 1.94),
        4: (start + 70, 1.42),
        6: (start + 101, 1.42),
    }
    for index, cell in enumerate(pace_cells):
        key = tuple(cell["arc_ids"])
        path_id = path_keys.index(key) + 1
        left = int(cell["start"])
        right = int(cell["end"]) + 1
        width = right - left
        axis.barh(
            0.5, width, left=left, height=0.82,
            color=COLORS[path_id - 1], edgecolor="white", linewidth=1.0, zorder=3,
        )
        label = (
            f"P{path_id}\nS={float(cell['average_score']):.0f}\n"
            f"T={float(cell['average_travel_time']):.3f} min"
        )
        center = left + width / 2
        if width >= 14:
            axis.text(
                center, 0.5, label, ha="center", va="center", fontsize=7.4,
                color="white" if path_id in (1, 3, 4) else "#111111",
                fontweight="bold", zorder=5,
            )
        else:
            label_x, label_y = short_positions.get(index, (center, 1.42))
            axis.annotate(
                label, xy=(center, 0.93), xytext=(label_x, label_y),
                ha="center", va="bottom", fontsize=7.1, linespacing=1.05,
                bbox={"boxstyle": "round,pad=0.22", "facecolor": "white",
                      "edgecolor": COLORS[path_id - 1], "linewidth": 0.9},
                arrowprops={"arrowstyle": "-", "color": COLORS[path_id - 1],
                            "linewidth": 0.9, "shrinkA": 0, "shrinkB": 1},
                zorder=8,
            )
    axis.plot(
        [start, end + 1], [2.42, 2.42], color="#333333", linewidth=1.0,
        linestyle=(0, (4, 3)), clip_on=False,
    )
    axis.text(
        (start + end + 1) / 2, 2.48,
        f"Fixed travel-time budget: B = {float(row['budget']):.3f} min",
        ha="center", va="bottom", fontsize=8.6,
    )
    axis.text(
        (start + end + 1) / 2, -0.50,
        "Objective: maximize transit-corridor affinity subject to T ≤ B",
        ha="center", va="top", fontsize=8.0, color="#333333",
    )
    ticks = list(range(start, end + 1, 30))
    if ticks[-1] != end:
        ticks.append(end)
    axis.set_xticks(ticks, [local_clock(value) for value in ticks])
    axis.set_xlim(start, end + 1)
    axis.set_ylim(-0.67, 2.69)
    axis.set_yticks([])
    axis.set_xlabel("Local departure time (EDT, 14 May 2026)", labelpad=8)
    axis.set_title("(b) Departure-time path assignment", loc="left", pad=8)
    axis.spines[["left", "right", "top"]].set_visible(False)
    axis.spines["bottom"].set_color("#777777")
    axis.grid(axis="x", color="#e1e4e6", linewidth=0.6, zorder=0)


def render(row: dict, role: str) -> tuple[Path, Path, dict | None]:
    pace_cells = [cell for cell in row["pace_b"]["cells"] if cell["resolved"]]
    path_keys: list[tuple[int, ...]] = []
    for cell in pace_cells:
        key = tuple(cell["arc_ids"])
        if key not in path_keys:
            path_keys.append(key)
    if len(path_keys) != int(row["pace_b"]["distinct_path_count"]):
        raise RuntimeError("materialized path count disagrees with result summary")

    midpoint = (int(row["interval_start"]) + int(row["interval_end"])) // 2
    distinct_fastest = [
        cell
        for cell in row["fastest"]["cells"]
        if cell["resolved"] and tuple(cell["arc_ids"]) not in path_keys
    ]
    fastest_cell = min(
        distinct_fastest,
        key=lambda cell: (
            -(int(cell["end"]) - int(cell["start"]) + 1),
            abs((int(cell["start"]) + int(cell["end"])) / 2 - midpoint),
            int(cell["start"]),
        ),
        default=None,
    )
    fastest_minute = (
        (int(fastest_cell["start"]) + int(fastest_cell["end"])) // 2
        if fastest_cell is not None
        else None
    )

    all_ids = sorted(
        {arc for key in path_keys for arc in key}
        | (set(fastest_cell["arc_ids"]) if fastest_cell is not None else set())
    )
    edges = gpd.read_parquet(EDGES, columns=["arc_id", "u", "v", "geometry"])
    if edges["arc_id"].duplicated().any():
        raise RuntimeError("DIMACS edge geometry has duplicate arc IDs")
    indexed = edges.set_index("arc_id", drop=False)
    selected_wgs84 = indexed.loc[all_ids]
    west, south, east, north = selected_wgs84.total_bounds
    span = max(east - west, north - south)
    margin = max(span * 0.16, 0.002)
    background_wgs84 = edges.cx[
        west - margin : east + margin, south - margin : north + margin
    ]
    background = background_wgs84.to_crs("EPSG:32618")
    selected = selected_wgs84.to_crs("EPSG:32618").set_index("arc_id", drop=False)

    first_path = selected.loc[list(path_keys[0])]
    if int(first_path.iloc[0]["u"]) != int(row["source"]):
        raise RuntimeError("first profile path does not start at the query source")
    if int(first_path.iloc[-1]["v"]) != int(row["destination"]):
        raise RuntimeError("first profile path does not end at the query destination")
    source_xy = tuple(first_path.geometry.iloc[0].coords[0])
    destination_xy = tuple(first_path.geometry.iloc[-1].coords[-1])

    mpl.rcParams.update(
        {
            "font.family": "DejaVu Sans",
            "font.size": 9,
            "axes.titlesize": 10.5,
            "axes.labelsize": 9.5,
            "legend.fontsize": 8,
            "pdf.fonttype": 42,
            "ps.fonttype": 42,
        }
    )
    figure, axes = plt.subplots(
        1,
        2,
        figsize=(14.2, 6.1),
        gridspec_kw={"width_ratios": [1.18, 1.0], "wspace": 0.13},
    )
    map_ax, time_ax = axes

    map_ax.add_collection(
        LineCollection(
            line_segments(background),
            colors="#d3d7da",
            linewidths=0.34,
            alpha=0.9,
            zorder=1,
        )
    )
    if fastest_cell is not None:
        map_ax.add_collection(
            LineCollection(
                line_segments(selected.loc[list(fastest_cell["arc_ids"])]),
                colors="#31363b",
                linewidths=2.1,
                linestyles="dashed",
                alpha=0.9,
                zorder=2,
            )
        )
    for index, key in enumerate(path_keys):
        map_ax.add_collection(
            LineCollection(
                line_segments(selected.loc[list(key)]),
                colors=COLORS[index],
                linewidths=2.7,
                alpha=0.92,
                zorder=4 + index,
            )
        )
    map_ax.scatter(
        *source_xy,
        marker="o",
        s=76,
        facecolor="white",
        edgecolor="#111111",
        linewidth=1.4,
        zorder=20,
    )
    map_ax.scatter(
        *destination_xy,
        marker="X",
        s=92,
        facecolor="#111111",
        edgecolor="white",
        linewidth=0.8,
        zorder=20,
    )
    bounds = background.total_bounds
    map_ax.set_xlim(bounds[0], bounds[2])
    map_ax.set_ylim(bounds[1], bounds[3])
    map_ax.set_aspect("equal")
    map_ax.set_axis_off()
    map_ax.set_title("(a) Local NYC road context and profile paths", loc="left", pad=8)

    legend = [
        Line2D([0], [0], color=COLORS[i], linewidth=2.7, label=f"P{i + 1}")
        for i in range(len(path_keys))
    ]
    if fastest_cell is not None:
        legend.append(
            Line2D(
                [0],
                [0],
                color="#31363b",
                linewidth=2.0,
                linestyle="--",
                label=f"Fastest at {local_clock(fastest_minute)}",
            )
        )
    legend.extend(
        [
            Line2D(
                [0],
                [0],
                marker="o",
                markerfacecolor="white",
                markeredgecolor="#111111",
                linestyle="None",
                markersize=7,
                label="Source",
            ),
            Line2D(
                [0],
                [0],
                marker="X",
                color="#111111",
                linestyle="None",
                markersize=7,
                label="Destination",
            ),
        ]
    )
    map_ax.legend(
        handles=legend,
        loc="lower left",
        ncol=2,
        frameon=True,
        framealpha=0.94,
        borderpad=0.6,
        columnspacing=1.1,
        handlelength=2.4,
    )
    map_ax.annotate(
        "N",
        xy=(0.955, 0.94),
        xytext=(0.955, 0.84),
        xycoords="axes fraction",
        ha="center",
        va="center",
        fontsize=9,
        arrowprops={"arrowstyle": "-|>", "color": "#222222", "lw": 1.2},
    )

    figure.suptitle(f"NYC shuttle query {row['query_id']}", fontsize=13, y=0.995)
    figure.subplots_adjust(left=0.025, right=0.99, bottom=0.15, top=0.90)
    interval_best = None
    if role in ("A", "B"):
        # The score-axis label adds 3.5 pt to a tight export unless panel (b)
        # yields that amount internally. Keep the established PDF/PNG page
        # dimensions without moving or resizing panel (a).
        position = time_ax.get_position()
        time_ax.remove()
        panel_width = position.width - 0.003435
        strip_height = position.height * 0.24
        gap_height = position.height * 0.07
        score_height = position.height * 0.69
        strip_axis = figure.add_axes(
            [position.x0, position.y0, panel_width, strip_height]
        )
        score_axis = figure.add_axes(
            [
                position.x0,
                position.y0 + strip_height + gap_height,
                panel_width,
                score_height,
            ]
        )
        interval_best = render_score_panel(
            score_axis, strip_axis, row, role, path_keys, pace_cells
        )
    else:
        render_assignment_panel(time_ax, row, pace_cells, path_keys)
    FIGURES.mkdir(parents=True, exist_ok=True)
    stem = FIGURES / f"v2_role_{role.lower()}_{row['query_id'].lower()}"
    pdf = stem.with_suffix(".pdf")
    png = stem.with_suffix(".png")
    dimensions = EXPORT_DIMENSIONS.get(row["query_id"])
    if role in ("A", "B") and dimensions is not None:
        figure.canvas.draw()
        tight = figure.get_tightbbox(figure.canvas.get_renderer())
        established_left = tight.x0 - 0.1
        established_top = tight.y1 + 0.1

        def anchored_bbox(width_inches: float, height_inches: float) -> Bbox:
            return Bbox.from_bounds(
                established_left,
                established_top - height_inches,
                width_inches,
                height_inches,
            )

        pdf_width, pdf_height = dimensions["pdf_points"]
        figure.savefig(
            pdf,
            bbox_inches=anchored_bbox(pdf_width / 72.0, pdf_height / 72.0),
            pad_inches=0,
        )
        png_width, png_height = dimensions["png_pixels"]
        figure.savefig(
            png,
            dpi=300,
            bbox_inches=anchored_bbox(png_width / 300.0, png_height / 300.0),
            pad_inches=0,
        )
    else:
        figure.savefig(pdf, bbox_inches="tight")
        figure.savefig(png, dpi=300, bbox_inches="tight")
    plt.close(figure)
    return pdf, png, interval_best


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--query-id")
    parser.add_argument("--role", choices=("A", "B", "C"), default="A")
    arguments = parser.parse_args()
    row = load_result(arguments.query_id)
    pdf, png, interval_best = render(row, arguments.role)
    print(
        json.dumps(
            {
                "role": arguments.role,
                "selection_rule": (
                    "max distinct paths, then route switches, profile cells, query_id"
                    if arguments.role == "A"
                    else "read from manifests/v2_representative_visual_queries.json"
                ),
                "query_id": row["query_id"],
                "profile_cells_reported": row["pace_b"]["profile_cell_count"],
                "maximal_materialized_cells": len(row["pace_b"]["cells"]),
                "distinct_paths": row["pace_b"]["distinct_path_count"],
                "route_switches": row["pace_b"]["route_switches"],
                "pdf": str(pdf),
                "pdf_sha256": sha256(pdf),
                "png": str(png),
                "png_sha256": sha256(png),
                "interval_best": interval_best,
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

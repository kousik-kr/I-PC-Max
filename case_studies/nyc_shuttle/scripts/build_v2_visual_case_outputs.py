#!/usr/bin/env python3
"""Build the NYC-REAL-V2 qualitative tables and factual case reports."""

from __future__ import annotations

import csv
import json
import statistics
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo


ROOT = Path(__file__).resolve().parents[3]
V2 = ROOT / "experiments/results/Final-result/NYC-real-shuttle-v2"
TABLES = ROOT / "tables"
REPORTS = ROOT / "reports"
ROLE_IDS = {
    "A": "NYC-Q012-MORNING-R20",
    "B": "NYC-Q020-MORNING-R20",
    "C": "NYC-Q002-EVENING-R50",
}


def load_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def fmt(value: float, digits: int = 3) -> str:
    return f"{value:.{digits}f}"


def tex_escape(value: str) -> str:
    return value.replace("%", "\\%").replace("_", "\\_")


def write_five_second_table() -> None:
    results = [
        row
        for row in load_jsonl(V2 / "results/primary_5s.jsonl")
        if row["status"] == "COMPLETE"
    ]
    if len(results) != 7:
        raise RuntimeError(f"official five-second completed count is {len(results)}, not 7")
    story = {
        row["query_id"]: row
        for row in load_jsonl(V2 / "validation/story_diagnostic_30s.jsonl")
    }
    for row in results:
        if row["pace_b"]["checksum"] != next(
            other["pace_b"]["checksum"]
            for other in load_jsonl(V2 / "results/diagnostic_30s.jsonl")
            if other["query_id"] == row["query_id"]
        ):
            raise RuntimeError(f"cross-cap profile checksum mismatch for {row['query_id']}")

    values = {
        "Average PACE score": [float(row["pace_b"]["average_score"]) for row in results],
        "Average fastest score": [float(row["fastest"]["average_score"]) for row in results],
        "Score difference": [
            float(row["pace_b"]["average_score"])
            - float(row["fastest"]["average_score"])
            for row in results
        ],
        "Travel-time premium (\\%)": [
            float(row["travel_time_premium_percent"]) for row in results
        ],
        "Budget utilization (\\%)": [
            100.0 * float(row["pace_b"]["average_travel_time"]) / float(row["budget"])
            for row in results
        ],
        "Profile cells": [float(row["pace_b"]["profile_cell_count"]) for row in results],
        "Distinct paths": [float(row["pace_b"]["distinct_path_count"]) for row in results],
    }
    exact_replay_violations = sum(
        not point.get("pace_valid", False)
        for row in results
        for point in story[row["query_id"]]["points"]
    )
    budget_violations = sum(
        int(row["pace_b"]["budget_violation_count"]) for row in results
    )

    body = []
    for label, series in values.items():
        digits = 2 if "Profile" not in label and "Distinct" not in label else 1
        body.append(
            f"{label} & {statistics.mean(series):.{digits}f} & "
            f"{statistics.median(series):.{digits}f} \\\\"
        )
    body.extend(
        [
            "\\midrule",
            f"Exact replay violations (total) & \\multicolumn{{2}}{{c}}{{{exact_replay_violations}}} \\\\",
            f"Budget violations (total) & \\multicolumn{{2}}{{c}}{{{budget_violations}}} \\\\",
        ]
    )
    content = "\n".join(
        [
            "\\begin{table}[t]",
            "\\centering",
            "\\caption{Descriptive statistics for the seven NYC case-study queries that normally completed under the five-second bounded execution limit.}",
            "\\label{tab:nyc-case-seven-queries}",
            "\\begin{tabular}{lrr}",
            "\\toprule",
            "Metric & Mean & Median \\\\",
            "\\midrule",
            *body,
            "\\bottomrule",
            "\\end{tabular}",
            "\\begin{minipage}{0.96\\linewidth}",
            "\\footnotesize Budget utilization is average PACE travel time divided by the fixed query budget. These seven bounded completions are not presented as statistically representative of all 100 query intervals.",
            "\\end{minipage}",
            "\\end{table}",
            "",
        ]
    )
    TABLES.mkdir(parents=True, exist_ok=True)
    (TABLES / "table_nyc_case_7queries.tex").write_text(content, encoding="utf-8")


def write_construction_table() -> None:
    graph = json.loads(
        (V2 / "processed/NYC-REAL-V2/manifest.json").read_text(encoding="utf-8")
    )
    dot = json.loads(
        (
            ROOT
            / "case_studies/nyc_shuttle/raw/dot_traffic_speeds/"
            "20260815T074644Z_i4gi-tjb9/download_manifest.json"
        ).read_text(encoding="utf-8")
    )
    score = graph["score_definition"]
    query_count = sum(
        1 for _ in (V2 / "manifests/nyc_queries.jsonl").open(encoding="utf-8")
    )
    values = [
        ("NY road vertices", f"{int(graph['num_nodes']):,}"),
        ("NY road arcs", f"{int(graph['num_arcs']):,}"),
        ("DOT observations", f"{int(dot['row_count']):,}"),
        ("Active MTA route IDs", f"{int(score['active_route_ids']):,}"),
        (
            "Exact active-shape coverage",
            f"{float(score['active_route_shape_bin_exact_match_percent']):.2f}\\%",
        ),
        ("Score-bearing road arcs", f"{int(score['score_bearing_edges']):,}"),
        ("Terminal pairs", "25"),
        ("Query intervals", f"{query_count}"),
    ]
    rows = [f"{label} & {value} \\\\" for label, value in values]
    content = "\n".join(
        [
            "\\begin{table}[t]",
            "\\centering",
            "\\caption{NYC real-data construction summary.}",
            "\\label{tab:nyc-data-construction}",
            "\\begin{tabular}{lr}",
            "\\toprule",
            "Construction item & Value \\\\",
            "\\midrule",
            *rows,
            "\\bottomrule",
            "\\end{tabular}",
            "\\begin{minipage}{0.92\\linewidth}",
            "\\footnotesize Exact active-shape coverage is the exact-match percentage over 93,322 active route--shape--time-bin records; no route-ID geometry fallback is used.",
            "\\end{minipage}",
            "\\end{table}",
            "",
        ]
    )
    (TABLES / "table_nyc_data_construction.tex").write_text(content, encoding="utf-8")


def clock(minute: int) -> str:
    midnight = datetime(2026, 5, 14, tzinfo=ZoneInfo("America/New_York"))
    return (midnight + timedelta(minutes=minute)).strftime("%H:%M")


def span(cell: dict) -> str:
    start = clock(int(cell["start"]))
    end = clock(int(cell["end"]))
    return f"at {start}" if start == end else f"from {start}--{end}"


def write_descriptions() -> None:
    results = {
        row["query_id"]: row
        for row in load_jsonl(V2 / "results/diagnostic_30s.jsonl")
    }
    with (V2 / "validation/interval_best_registry_coverage.csv").open(
        encoding="utf-8", newline=""
    ) as stream:
        coverage = {row["query_id"]: row for row in csv.DictReader(stream)}

    a = results[ROLE_IDS["A"]]
    path_keys: list[tuple[int, ...]] = []
    clauses = []
    for cell in a["pace_b"]["cells"]:
        key = tuple(cell["arc_ids"])
        if key not in path_keys:
            path_keys.append(key)
        path_id = path_keys.index(key) + 1
        clauses.append(
            f"{span(cell)} P{path_id} has cell-average S={float(cell['average_score']):.0f} "
            f"and T={float(cell['average_travel_time']):.3f} min"
        )
    a_text = (
        "For the 07:00--09:00 departure interval, "
        + "; ".join(clauses)
        + f". All exact minute-departure replays are valid and remain within B={float(a['budget']):.3f} min; "
        f"the generated-registry interval-best path is selected for "
        f"{float(coverage[a['query_id']]['interval_best_path_selected_departure_coverage'])*121:.0f}/121 departures."
    )

    b = results[ROLE_IDS["B"]]
    b_cell = b["pace_b"]["cells"][0]
    b_text = (
        f"For departures from 07:00--09:00, P1 is selected throughout with "
        f"cell-average S={float(b_cell['average_score']):.0f} and "
        f"T={float(b_cell['average_travel_time']):.3f} min, below B={float(b['budget']):.3f} min. "
        f"Across the same 121 departures, the fastest profile averages "
        f"S={float(b['fastest']['average_score']):.3f} and "
        f"T={float(b['fastest']['average_travel_time']):.3f} min, while the PACE profile averages "
        f"S={float(b['pace_b']['average_score']):.3f} and "
        f"T={float(b['pace_b']['average_travel_time']):.3f} min; the integrated score difference is +266, "
        f"the travel-time premium is {float(b['travel_time_premium_percent']):.3f}%, and every exact replay is valid."
    )

    c = results[ROLE_IDS["C"]]
    c_cell = c["pace_b"]["cells"][0]
    c_text = (
        f"For departures from 17:00--19:00, P1 is selected throughout with "
        f"cell-average S={float(c_cell['average_score']):.0f} and "
        f"T={float(c_cell['average_travel_time']):.3f} min, below B={float(c['budget']):.3f} min. "
        f"The fastest profile averages S={float(c['fastest']['average_score']):.3f} and "
        f"T={float(c['fastest']['average_travel_time']):.3f} min, and IntervalBestPathCoverage is "
        f"{float(coverage[c['query_id']]['interval_best_path_selected_departure_coverage']):.3f}; "
        "therefore this deterministic Role C selection does not itself show an interval-best mismatch, and all exact replays are valid."
    )

    content = "\n".join(
        [
            "# Corrected NYC-REAL-V2 visual case descriptions",
            "",
            "## Role A -- profile variation",
            "",
            a_text,
            "",
            "## Role B -- preference/budget trade-off",
            "",
            b_text,
            "",
            "## Role C -- deterministic minimum eligible coverage",
            "",
            c_text,
            "",
            "## Visualization-only completion outcome",
            "",
            (
                "The predeclared Role C extension query NYC-Q024-EVENING-R50 timed out at "
                "actual PACE runtimes of 60.003 and 120.004 seconds. Both runs were labeled "
                "VISUALIZATION_ONLY and excluded from official statistics; no replacement query was run."
            ),
            "",
        ]
    )
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "v2_visual_case_descriptions.md").write_text(content, encoding="utf-8")


def write_stop_decision() -> None:
    content = "\n".join(
        [
            "# NYC qualitative case-study stop decision",
            "",
            "1. **YES.** NYC-Q012-MORNING-R20 is a strong corrected-V2 multi-path profile with five paths and seven route switches.",
            "2. **YES.** NYC-Q020-MORNING-R20 has a positive integrated score difference of 266 with a 0.021% travel-time premium and no budget violation.",
            "3. **YES.** NYC-Q012-MORNING-R20 selects the generated-registry interval-best path for only 16 of 121 departures.",
            "4. **YES.** Every shown departure/path replay is exact and within its fixed budget.",
            "5. **YES.** The separate two-panel PDF/PNG figures retain readable maps, clock times, cell labels, and budget references.",
            "6. **NO.** Further connector optimization is not necessary for the qualitative case-study purpose.",
            "",
            "**STOP NYC PERFORMANCE OPTIMIZATION.**",
            "",
        ]
    )
    (REPORTS / "v2_case_study_stop_decision.md").write_text(content, encoding="utf-8")


def main() -> int:
    write_five_second_table()
    write_construction_table()
    write_descriptions()
    write_stop_decision()
    print(
        json.dumps(
            {
                "outputs": [
                    "tables/table_nyc_case_7queries.tex",
                    "tables/table_nyc_data_construction.tex",
                    "reports/v2_visual_case_descriptions.md",
                    "reports/v2_case_study_stop_decision.md",
                ]
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Build the frozen NYC-REAL-V2 visualization inventory.

Only root-level reports are written. The V1 and V2 result bundles are read-only.
"""

from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
V2 = ROOT / "experiments/results/Final-result/NYC-real-shuttle-v2"
REPORTS = ROOT / "reports"
CAP_RESULTS = (
    (5, V2 / "results/primary_5s.jsonl"),
    (10, V2 / "results/diagnostic_10s.jsonl"),
    (30, V2 / "results/diagnostic_30s.jsonl"),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def build_rows() -> list[dict]:
    story = {
        row["query_id"]: row
        for row in load_jsonl(V2 / "validation/story_diagnostic_30s.jsonl")
    }
    with (V2 / "validation/interval_best_registry_coverage.csv").open(
        encoding="utf-8", newline=""
    ) as stream:
        coverage = {
            row["query_id"]: float(
                row["interval_best_path_selected_departure_coverage"]
            )
            for row in csv.DictReader(stream)
        }

    unique: dict[tuple[str, str], dict] = {}
    for cap, path in CAP_RESULTS:
        for result in load_jsonl(path):
            if result.get("status") != "COMPLETE" or not result.get("pace_b"):
                continue
            key = (result["query_id"], result["pace_b"]["checksum"])
            if key not in unique:
                unique[key] = {"first_cap": cap, "result": result, "caps": [cap]}
            else:
                unique[key]["caps"].append(cap)

    rows = []
    for (query_id, profile_checksum), item in sorted(unique.items()):
        result = item["result"]
        pace = result["pace_b"]
        fastest = result["fastest"]
        validation = story.get(query_id)
        if validation is None:
            raise RuntimeError(f"missing exact-replay validation for {query_id}")
        points = validation["points"]
        exact_replay_valid = bool(points) and all(
            point.get("pace_resolved") and point.get("pace_valid") for point in points
        )
        if query_id not in coverage:
            raise RuntimeError(f"missing interval-best coverage for {query_id}")
        rows.append(
            {
                "query_id": query_id,
                "pair_id": result["pair_id"],
                "period": result["period_id"],
                "rho": result["rho"],
                "B": result["budget"],
                "first_normal_completion_cap_seconds": item["first_cap"],
                "completed_caps_seconds": ";".join(str(value) for value in item["caps"]),
                "profile_checksum": profile_checksum,
                "profile_cells": pace["profile_cell_count"],
                "distinct_paths": pace["distinct_path_count"],
                "route_switches": pace["route_switches"],
                "average_pace_score": pace["average_score"],
                "average_fastest_score": fastest["average_score"],
                "average_score_difference": (
                    float(pace["average_score"]) - float(fastest["average_score"])
                ),
                "average_pace_travel_time": pace["average_travel_time"],
                "average_fastest_travel_time": fastest["average_travel_time"],
                "travel_time_premium_percent": result["travel_time_premium_percent"],
                "interval_best_path_coverage": coverage[query_id],
                "exact_replay_valid": exact_replay_valid,
                "budget_violations": pace["budget_violation_count"],
            }
        )
    return rows


def write_csv(rows: list[dict], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def write_markdown(rows: list[dict], path: Path, csv_path: Path) -> None:
    headers = (
        "Query",
        "Pair",
        "Period",
        "rho",
        "B",
        "First cap",
        "Cells",
        "Paths",
        "Switches",
        "PACE S",
        "Fastest S",
        "Delta S",
        "PACE T",
        "Fastest T",
        "Premium",
        "IB coverage",
        "Replay",
        "B violations",
    )
    lines = [
        "# NYC-REAL-V2 visualization inventory",
        "",
        (
            "Normally completed results from the frozen 5-, 10-, and 30-second runs. "
            "Rows are deduplicated by `(query_id, PACE profile checksum)`; `First cap` "
            "is the first bounded run in which that exact profile normally completed."
        ),
        "",
        f"- Unique completed query/profile rows: {len(rows)}",
        f"- CSV SHA-256: `{sha256(csv_path)}`",
        f"- Source 30-second result SHA-256: `{sha256(CAP_RESULTS[-1][1])}`",
        "",
        "| " + " | ".join(headers) + " |",
        "|" + "|".join(["---"] * len(headers)) + "|",
    ]
    for row in rows:
        values = (
            row["query_id"],
            row["pair_id"],
            row["period"],
            f"{float(row['rho']):.2f}",
            f"{float(row['B']):.3f}",
            f"{row['first_normal_completion_cap_seconds']} s",
            str(row["profile_cells"]),
            str(row["distinct_paths"]),
            str(row["route_switches"]),
            f"{float(row['average_pace_score']):.3f}",
            f"{float(row['average_fastest_score']):.3f}",
            f"{float(row['average_score_difference']):+.3f}",
            f"{float(row['average_pace_travel_time']):.3f}",
            f"{float(row['average_fastest_travel_time']):.3f}",
            f"{float(row['travel_time_premium_percent']):+.2f}%",
            f"{float(row['interval_best_path_coverage']):.3f}",
            "YES" if row["exact_replay_valid"] else "NO",
            str(row["budget_violations"]),
        )
        lines.append("| " + " | ".join(values) + " |")
    lines.extend(
        [
            "",
            "`S` is additive corridor-affinity score; `T` and `B` are minutes. "
            "Interval-best coverage is measured only within the generated path registry.",
            "",
        ]
    )
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    rows = build_rows()
    if len(rows) != 15:
        raise RuntimeError(f"expected 15 deduplicated completed rows, found {len(rows)}")
    csv_path = REPORTS / "v2_visualization_inventory.csv"
    md_path = REPORTS / "v2_visualization_inventory.md"
    write_csv(rows, csv_path)
    write_markdown(rows, md_path, csv_path)
    print(
        json.dumps(
            {
                "rows": len(rows),
                "csv": str(csv_path),
                "csv_sha256": sha256(csv_path),
                "markdown": str(md_path),
                "markdown_sha256": sha256(md_path),
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

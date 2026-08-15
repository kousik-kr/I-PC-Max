"""Case-study metrics, representative selection, tables, figures, and reports."""

from __future__ import annotations

import csv
import json
import math
import re
import statistics
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from .common import (
    CASE_ROOT, REPO_ROOT, CaseStudyError, atomic_write_json, atomic_write_text,
    git_revision, load_config, sha256_file, software_versions,
)


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        raise CaseStudyError(f"required JSONL does not exist: {path}")
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not rows:
        raise CaseStudyError(f"JSONL is empty: {path}")
    return rows


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return math.nan
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] * (upper - position) + ordered[upper] * (position - lower)


def analyze(result_path: Path, output_json: Path, output_csv: Path) -> dict[str, Any]:
    rows = load_jsonl(result_path)
    schema_path = CASE_ROOT / "schemas/result.schema.json"
    if schema_path.exists():
        try:
            import jsonschema  # type: ignore
        except ImportError as failure:
            raise CaseStudyError("jsonschema is required to validate NYC result records") from failure
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        for row in rows:
            jsonschema.validate(row, schema)
    ids = [row.get("query_id") for row in rows]
    if len(ids) != len(set(ids)):
        raise CaseStudyError("result JSONL contains duplicate query IDs")
    query_path = CASE_ROOT / "manifests/nyc_queries.jsonl"
    if query_path.exists():
        expected_ids = {row["query_id"] for row in load_jsonl(query_path)}
        actual_ids = set(ids)
        if actual_ids != expected_ids:
            missing = sorted(expected_ids - actual_ids)
            unexpected = sorted(actual_ids - expected_ids)
            raise CaseStudyError(
                f"result batch is incomplete or mismatched: missing={len(missing)} "
                f"unexpected={len(unexpected)}; resume the exact batch before analysis"
            )
    violations = sum(
        int((row.get("pace_b") or {}).get("budget_violation_count", 0)) for row in rows
    )
    if violations:
        raise CaseStudyError(f"BUG: PACE exact replay found {violations} budget violations")
    status_counts = Counter(str(row.get("status")) for row in rows)
    summaries: list[dict[str, Any]] = []
    for rho in sorted({float(row["rho"]) for row in rows}):
        group = [row for row in rows if float(row["rho"]) == rho]
        completed = [row for row in group if row.get("status") == "COMPLETE" and row.get("pace_b") is not None]
        runtimes = [float(row["timing_ns"]["pace_b"]) / 1e6 for row in group]
        pace_average_score = (statistics.mean(row["pace_b"]["average_score"] for row in completed)
                              if completed else None)
        fastest_average_score = (statistics.mean(row["fastest"]["average_score"] for row in completed)
                                 if completed else None)
        pace_average_travel = (statistics.mean(row["pace_b"]["average_travel_time"] for row in completed)
                               if completed else None)
        fastest_average_travel = (statistics.mean(row["fastest"]["average_travel_time"] for row in completed)
                                  if completed else None)
        score_gain_absolute = (pace_average_score - fastest_average_score
                               if pace_average_score is not None and fastest_average_score is not None else None)
        score_gain_percent = (100 * score_gain_absolute / max(fastest_average_score, 1e-9)
                              if score_gain_absolute is not None and fastest_average_score is not None else None)
        travel_time_premium_percent = (
            100 * (pace_average_travel - fastest_average_travel) / max(fastest_average_travel, 1e-9)
            if pace_average_travel is not None and fastest_average_travel is not None else None)
        summary = {
            "rho": rho,
            "query_count": len(group),
            "completed_count": len(completed),
            "timeout_count": sum(row.get("status") == "TIMEOUT" for row in group),
            "timeout_rate": sum(row.get("status") == "TIMEOUT" for row in group) / len(group),
            "pace_average_score": pace_average_score,
            # Keep the PACE-vs-fastest score comparison paired on the same completed queries.
            "fastest_average_score": fastest_average_score,
            "score_gain_absolute": score_gain_absolute,
            "score_gain_percent": score_gain_percent,
            "pace_average_travel_time": pace_average_travel,
            "fastest_average_travel_time": fastest_average_travel,
            "travel_time_premium_percent": travel_time_premium_percent,
            "resolved_coverage": statistics.mean(row["pace_b"]["resolved_coverage"] for row in completed) if completed else None,
            "profile_cells": statistics.mean(row["pace_b"]["profile_cell_count"] for row in completed) if completed else None,
            "distinct_paths": statistics.mean(row["pace_b"]["distinct_path_count"] for row in completed) if completed else None,
            "runtime_median_ms": statistics.median(runtimes),
            "runtime_q1_ms": percentile(runtimes, 0.25),
            "runtime_q3_ms": percentile(runtimes, 0.75),
        }
        summaries.append(summary)
    paired_effects = []
    by_family: dict[tuple[str, str], dict[float, dict[str, Any]]] = defaultdict(dict)
    for row in rows:
        by_family[(row["pair_id"], row["period_id"])][float(row["rho"])] = row
    for key, variants in by_family.items():
        if 0.2 not in variants or 0.5 not in variants:
            continue
        low, high = variants[0.2], variants[0.5]
        if (low.get("status") != "COMPLETE" or high.get("status") != "COMPLETE"
                or low.get("pace_b") is None or high.get("pace_b") is None):
            continue
        paired_effects.append({
            "pair_id": key[0], "period_id": key[1],
            "score_change": high["pace_b"]["average_score"] - low["pace_b"]["average_score"],
            "candidate_change": high["candidate_count"] - low["candidate_count"],
            "profile_cell_change": high["pace_b"]["profile_cell_count"] - low["pace_b"]["profile_cell_count"],
        })
    result = {
        "schema_version": 1,
        "result_path": str(result_path),
        "result_sha256": sha256_file(result_path),
        "query_records": len(rows),
        "status_counts": dict(sorted(status_counts.items())),
        "budget_violation_count": violations,
        "by_rho": summaries,
        "paired_budget_effects": {
            "comparable_families": len(paired_effects),
            "mean_score_change": statistics.mean(item["score_change"] for item in paired_effects) if paired_effects else None,
            "mean_candidate_change": statistics.mean(item["candidate_change"] for item in paired_effects) if paired_effects else None,
            "mean_profile_cell_change": statistics.mean(item["profile_cell_change"] for item in paired_effects) if paired_effects else None,
            "observed_score_monotonic_fraction": (
                sum(item["score_change"] >= -1e-9 for item in paired_effects) / len(paired_effects)
                if paired_effects else None
            ),
        },
    }
    atomic_write_json(output_json, result)
    output_csv.parent.mkdir(parents=True, exist_ok=True)
    with output_csv.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(summaries[0]))
        writer.writeheader()
        writer.writerows(summaries)
    return result


def representative_query(rows: list[dict[str, Any]]) -> dict[str, Any]:
    qualifying = [
        row for row in rows
        if row.get("status") == "COMPLETE"
        and row.get("pace_b") is not None
        and row["pace_b"]["resolved_coverage"] == 1.0
        and row["pace_b"]["profile_cell_count"] >= 3
        and row["pace_b"]["average_score"] > row["fastest"]["average_score"]
        and row["pace_b"]["budget_violation_count"] == 0
    ]
    if not qualifying:
        raise CaseStudyError(
            "no result satisfies the predeclared representative-query rule; no figure was fabricated"
        )
    gains = [float(row["score_gain_absolute"]) for row in qualifying]
    median = statistics.median(gains)
    return min(qualifying, key=lambda row: (abs(float(row["score_gain_absolute"]) - median), row["query_id"]))


def make_figure(result_path: Path, edges_path: Path, output_pdf: Path,
                output_png: Path) -> dict[str, Any]:
    import geopandas as gpd  # type: ignore
    import matplotlib.pyplot as plt  # type: ignore
    from matplotlib.collections import LineCollection  # type: ignore
    from matplotlib.patches import Rectangle  # type: ignore

    selected = representative_query(load_jsonl(result_path))
    edges = gpd.read_parquet(edges_path).to_crs("EPSG:32618")
    if "arc_id" not in edges.columns or edges.arc_id.duplicated().any():
        raise CaseStudyError("DIMACS edge geometry must contain unique stable arc_id values")
    edges_by_arc = edges.set_index("arc_id", drop=False)
    pace_cells = [cell for cell in selected["pace_b"]["cells"] if cell["resolved"]]
    distinct: list[dict[str, Any]] = []
    seen: set[tuple[int, ...]] = set()
    for cell in pace_cells:
        key = tuple(cell["arc_ids"])
        if key not in seen:
            seen.add(key)
            distinct.append(cell)
    distinct = distinct[:6]
    if len(distinct) < 2:
        raise CaseStudyError("representative result has fewer than two materialized paths")
    all_arc_ids = sorted({arc for cell in distinct for arc in cell["arc_ids"]})
    selected_edges = edges_by_arc.loc[all_arc_ids]
    bounds = selected_edges.total_bounds
    margin = max(bounds[2] - bounds[0], bounds[3] - bounds[1]) * 0.15
    background = edges.cx[bounds[0] - margin:bounds[2] + margin,
                          bounds[1] - margin:bounds[3] + margin]
    figure, axes = plt.subplots(1, 2, figsize=(12, 5.2), gridspec_kw={"width_ratios": [1.25, 1]})
    background.plot(ax=axes[0], color="#d9d9d9", linewidth=0.25, zorder=1)
    colors = plt.cm.tab10.colors
    for index, cell in enumerate(distinct):
        edges_by_arc.loc[cell["arc_ids"]].plot(
            ax=axes[0], color=colors[index % len(colors)], linewidth=2.0,
            label=f"P{index + 1}", zorder=3 + index)
    fastest_cell = next(cell for cell in selected["fastest"]["cells"] if cell["resolved"])
    edges_by_arc.loc[fastest_cell["arc_ids"]].plot(
        ax=axes[0], color="black", linewidth=0.8, linestyle="--", label="Fastest", zorder=2)
    first_path = edges_by_arc.loc[distinct[0]["arc_ids"]]
    source_point = first_path.geometry.iloc[0].coords[0]
    destination_point = first_path.geometry.iloc[-1].coords[-1]
    axes[0].scatter(*source_point, color="green", marker="o", s=55, label="Source", zorder=20)
    axes[0].scatter(*destination_point, color="red", marker="X", s=65, label="Destination", zorder=20)
    axes[0].set_title("(a) Selected NYC road paths")
    axes[0].set_axis_off()
    axes[0].legend(loc="best", fontsize=8)

    start = selected["interval_start"]
    for index, cell in enumerate(pace_cells):
        width = cell["end"] - cell["start"] + 1
        color_index = next(
            (position for position, item in enumerate(distinct)
             if item["arc_ids"] == cell["arc_ids"]), 0)
        axes[1].add_patch(Rectangle(
            (cell["start"] - start, 0), width, 1,
            facecolor=colors[color_index % len(colors)], alpha=0.75, edgecolor="white"))
        if width >= 8:
            axes[1].text(
                cell["start"] - start + width / 2, 0.5,
                f"P{color_index + 1}\nS={cell['average_score']:.1f}\nT={cell['average_travel_time']:.1f}",
                ha="center", va="center", fontsize=7)
    axes[1].axhline(1.12, color="black", linewidth=1.5)
    axes[1].text(60, 1.16, f"Fixed budget B={selected['budget']:.1f} min", ha="center", fontsize=9)
    axes[1].set_xlim(0, selected["interval_end"] - start + 1)
    axes[1].set_ylim(0, 1.32)
    axes[1].set_yticks([])
    axes[1].set_xlabel("Minutes from interval start")
    axes[1].set_title("(b) Departure-time path profile")
    figure.suptitle(f"Representative query {selected['query_id']} (predeclared median-gain rule)")
    figure.tight_layout()
    output_pdf.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output_pdf, bbox_inches="tight")
    figure.savefig(output_png, dpi=220, bbox_inches="tight")
    plt.close(figure)
    return {"query_id": selected["query_id"], "paths_drawn": len(distinct),
            "pdf_sha256": sha256_file(output_pdf), "png_sha256": sha256_file(output_png)}


def _download_manifests() -> list[dict[str, Any]]:
    result = []
    for path in sorted((CASE_ROOT / "raw").glob("**/download_manifest.json")):
        value = json.loads(path.read_text(encoding="utf-8"))
        value["manifest_path"] = str(path)
        result.append(value)
    return result


def make_tables(summary_path: Path, output_dir: Path) -> dict[str, Any]:
    import pandas as pd  # type: ignore

    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    graph_manifest = json.loads((CASE_ROOT / "processed/NYC-REAL/manifest.json").read_text())
    center = pd.read_parquet(CASE_ROOT / "intermediate/dimacs_centerline_matches.parquet")
    pairs = pd.read_csv(CASE_ROOT / "manifests/nyc_terminal_pairs.csv")
    query_count = sum(1 for _ in (CASE_ROOT / "manifests/nyc_queries.jsonl").open())
    calibration = graph_manifest["traffic_calibration"]["provenance_counts"]
    total_bins = sum(calibration.values())
    score = graph_manifest["score_definition"]
    active_routes = score.get("active_route_ids")
    if active_routes is None:
        score_report = (CASE_ROOT / "reports/score_definition.md").read_text(encoding="utf-8")
        match = re.search(r"^- active_route_ids: (\d+)$", score_report, flags=re.MULTILINE)
        active_routes = int(match.group(1)) if match else "--"
    construction_values = [
        graph_manifest["num_arcs"],
        100 * (center.centerline_match_status != "UNMATCHED").mean(),
        100 * calibration.get("DIRECT", 0) / total_bins,
        100 * (calibration.get("BOROUGH_CLASS_IMPUTED", 0) + calibration.get("CITYWIDE_IMPUTED", 0)) / total_bins,
        active_routes,
        100 * score["score_bearing_edges"] / graph_manifest["num_arcs"],
        int(pairs.selected.sum()), query_count,
    ]
    table_a = """\\begin{tabular}{rrrrrrrr}
\\toprule
Road arcs & Centerline (\\%) & Direct DOT (\\%) & Imputed (\\%) & Active routes & Score arcs (\\%) & Pairs & Queries \\\\
\\midrule
""" + " & ".join([
        f"{construction_values[0]:,}", *(f"{value:.2f}" if isinstance(value, float) else str(value)
                                       for value in construction_values[1:])
    ]) + """ \\\\
\\bottomrule
\\end{tabular}
"""
    rows = []
    for item in summary["by_rho"]:
        def fmt(value: object) -> str:
            return "--" if value is None else f"{float(value):.2f}"
        rows.append(" & ".join([
            f"{item['rho']:.2f}", fmt(item["pace_average_score"]),
            fmt(item["fastest_average_score"]), fmt(item["score_gain_percent"]),
            fmt(item["travel_time_premium_percent"]), fmt(item["resolved_coverage"]),
            fmt(item["profile_cells"]), fmt(item["runtime_median_ms"]),
        ]))
    table_b = """\\begin{tabular}{rrrrrrrr}
\\toprule
$\\rho$ & PACE-B score & Fastest score & Gain (\\%) & Time premium (\\%) & Resolved & Cells & Runtime (ms) \\\\
\\midrule
""" + " \\\\\n".join(rows) + """ \\\\
\\bottomrule
\\end{tabular}
"""
    output_dir.mkdir(parents=True, exist_ok=True)
    atomic_write_text(output_dir / "table_nyc_construction.tex", table_a)
    atomic_write_text(output_dir / "table_nyc_results.tex", table_b)
    return {"table_a": str(output_dir / "table_nyc_construction.tex"),
            "table_b": str(output_dir / "table_nyc_results.tex")}


def build_reports(summary_path: Path | None = None) -> dict[str, Any]:
    graph_manifest = json.loads((CASE_ROOT / "processed/NYC-REAL/manifest.json").read_text())
    sources = load_config(CASE_ROOT / "config/sources.yaml")["sources"]
    source_lines = []
    for manifest in _download_manifests():
        source_key = Path(manifest["manifest_path"]).parent.parent.name
        configured = sources.get(source_key, {})
        total_size = int(manifest["metadata_artifact"]["size_bytes"]) + sum(
            int(page["size_bytes"]) for page in manifest["pages"])
        schema_fields = ", ".join(
            f"{field.get('fieldName')}:{field.get('dataTypeName')}"
            for field in manifest.get("source_schema", [])
        )
        source_lines.extend([
            f"- {configured.get('name', source_key)} — dataset `{manifest['dataset_id']}` on `{manifest['domain']}`",
            f"  - official landing page: {configured.get('landing_page_url', 'NOT RECORDED')}",
            f"  - retrieval: {manifest['retrieved_at_utc']}",
            f"  - rows: {manifest['row_count']:,}",
            f"  - exact raw bytes (metadata + pages): {total_size:,}",
            f"  - content checksum: `{manifest['content_checksum']}`",
            f"  - metadata URL: {manifest['metadata_url']}",
            f"  - captured schema: `{schema_fields}`",
        ])
        for page in manifest["pages"]:
            source_lines.append(
                f"  - page `{Path(page['path']).name}`: {page['row_count']:,} rows, "
                f"{page['size_bytes']:,} bytes, SHA-256 `{page['sha256']}`, URL {page['source_url']}"
            )
    calibration = graph_manifest["traffic_calibration"]["provenance_counts"]
    total_calibration = sum(calibration.values())
    provenance_percent = {
        key: 100 * calibration.get(key, 0) / total_calibration
        for key in ("DIRECT", "BOROUGH_CLASS_IMPUTED", "CITYWIDE_IMPUTED", "STATIC_FALLBACK")
    }
    query_rows = load_jsonl(CASE_ROOT / "manifests/nyc_queries.jsonl")
    exclusion_path = CASE_ROOT / "manifests/nyc_query_exclusions.jsonl"
    exclusions = ([json.loads(line) for line in exclusion_path.read_text(encoding="utf-8").splitlines()
                   if line.strip()] if exclusion_path.exists() else [])
    exclusion_reasons = Counter(str(row.get("reason")) for row in exclusions)
    pairs_path = CASE_ROOT / "manifests/nyc_terminal_pairs.csv"
    with pairs_path.open(encoding="utf-8", newline="") as stream:
        pair_rows = list(csv.DictReader(stream))
    selected_pairs = sum(str(row["selected"]).lower() == "true" for row in pair_rows)
    config = load_config(CASE_ROOT / "config/case_study.yaml")
    quality = [
        "# NYC case-study data quality and reproducibility", "",
        "## Official source artifacts", "", *source_lines, "",
        "## Temporal calibration", "",
        f"- observed support: {graph_manifest['temporal_support']['observed_start_utc']} to {graph_manifest['temporal_support']['observed_end_utc']}",
        f"- bin size: {graph_manifest['traffic_calibration']['bin_minutes']} minutes",
        f"- DOT timestamp convention: Socrata Floating Timestamp localized to `{graph_manifest['traffic_calibration']['source_timezone']}` and normalized to UTC",
        f"- provenance counts: `{calibration}`",
        f"- provenance percentages: `{provenance_percent}`",
        f"- FIFO repaired knots: {graph_manifest['traffic_calibration']['fifo_repaired_knots']}",
        "- day wrapping: none", "- extrapolation: none", "",
        "## Mapping and score", "",
        (CASE_ROOT / "reports/centerline_mapping_quality.md").read_text(),
        (CASE_ROOT / "reports/dot_link_mapping_quality.md").read_text(),
        (CASE_ROOT / "reports/mta_route_mapping_quality.md").read_text(),
        (CASE_ROOT / "reports/score_definition.md").read_text(), "",
        "## Query filtering and deterministic choices", "",
        f"- selected terminal pairs: {selected_pairs}",
        f"- emitted queries: {len(query_rows)}",
        f"- excluded queries: {len(exclusions)}",
        f"- exclusion reasons: `{dict(sorted(exclusion_reasons.items()))}`",
        f"- deterministic seed: {config['seed']}",
        f"- terminal selection target: {config['target_terminal_pairs']}", "",
        "## Determinism and software", "",
        f"- graph-build PACE revision: `{graph_manifest['pace_revision']}`",
        f"- report-generation revision: `{git_revision()}`",
        f"- software: `{software_versions()}`", "",
        "## Reproduction commands", "",
        "```bash",
        "make nyc-setup", "make nyc-download", "make nyc-audit", "make nyc-map",
        "make nyc-build-profiles", "make nyc-build-scores", "make nyc-queries",
        "make nyc-run", "make nyc-analyze", "make nyc-figures", "make nyc-report",
        "make nyc-finalize", "```", "",
        "The long-running `make nyc-collect-traffic NYC_TRAFFIC_DURATION_HOURS=H` target is separate and was not used: the official DOT table supplied multiple historical timestamps for the selected day.",
    ]
    atomic_write_text(CASE_ROOT / "reports/nyc_case_study_data_quality.md", "\n".join(quality) + "\n")

    findings = ["# NYC case-study findings", ""]
    if summary_path is None or not summary_path.exists():
        findings += [
            "Experiment status: NOT YET RUN.", "",
            "Supported findings, failed hypotheses, and manuscript wording are intentionally not populated until validated results exist.",
        ]
    else:
        summary = json.loads(summary_path.read_text())
        findings += ["Experiment status: EXPERIMENT COMPLETED.", "", "## Supported findings", ""]
        completed = sum(item["completed_count"] for item in summary["by_rho"])
        findings.append(
            f"- {completed}/{summary['query_records']} queries returned a complete PACE-B profile within the bounded protocol; "
            f"status counts were `{summary['status_counts']}`."
        )
        findings.append(f"- Exact replay budget violations: {summary['budget_violation_count']}.")
        for item in summary["by_rho"]:
            if item["completed_count"]:
                findings.append(
                    f"- For rho={item['rho']:.2f}, {item['completed_count']}/{item['query_count']} queries completed; "
                    f"mean absolute score gain was {item['score_gain_absolute']:.3f}, mean relative gain was "
                    f"{item['score_gain_percent']:.2f}%, and mean travel-time premium was "
                    f"{item['travel_time_premium_percent']:.2f}%."
                )
        paired = summary["paired_budget_effects"]
        findings.append(
            f"- Across the {paired['comparable_families']} pair/period families completed at both budgets, "
            f"raising rho from 0.20 to 0.50 changed mean score by {paired['mean_score_change']:.3f}, "
            f"candidate count by {paired['mean_candidate_change']:.3f}, and profile cells by "
            f"{paired['mean_profile_cell_change']:.3f}."
        )
        findings += ["", "## Unsupported/failed hypotheses", ""]
        if completed == 0:
            findings.append("- The current bounded configuration did not complete any query within five seconds; no PACE-vs-fastest score-gain claim is supported.")
        else:
            findings.append("- Do not claim score or budget monotonicity beyond the measured paired-family summary.")
        nonpositive = [item for item in summary["by_rho"]
                       if item["score_gain_absolute"] is not None and item["score_gain_absolute"] <= 0]
        if nonpositive:
            values = ", ".join(f"rho={item['rho']:.2f}: {item['score_gain_absolute']:.3f}"
                               for item in nonpositive)
            findings.append(
                f"- A blanket claim that bounded PACE-B improves mean score over fastest routing is unsupported; "
                f"nonpositive completed-query mean absolute gain was observed at {values}."
            )
        findings.append(
            f"- Only {paired['comparable_families']} pair/period families completed at both budgets; "
            "budget-effect conclusions are limited to those paired observations."
        )
        findings.append(
            f"- The five-second protocol timed out on {summary['status_counts'].get('TIMEOUT', 0)}/"
            f"{summary['query_records']} queries, so this configuration does not support a general practical-runtime claim."
        )
        findings += [
            "", "## Data limitations", "",
            "- DOT coverage is limited to instrumented links; most regional arcs use observed citywide multipliers.",
            "- The 2026 schedule day contains historical shape IDs absent from the current route-shape view.",
            "", "## Mapping limitations", "",
            "- Spatial overlap and direction reduce but cannot eliminate ambiguity on parallel roads.",
            "", "## Recommended manuscript statements", "",
            "- Describe the score as an active transit-corridor affinity proxy, not an MTA ground-truth preference.",
            "- Report direct and imputed travel-time proportions alongside results.",
            "- Report score gains only on the completed-query subset, together with timeout and no-feasible-profile counts.",
            "- Use the automatically selected profile figure as evidence that the selected road path can change with departure time, not as evidence of universal score gain.",
            "", "## Statements we should NOT make", "",
            "- Do not claim PACE performs scheduling, assignment, stop sequencing, fleet planning, or VRP optimization.",
            "- Do not claim unmapped or imputed arcs have direct sensor observations.",
            "- Do not claim larger budget monotonically improves this bounded implementation's score or profile complexity.",
            "- Do not claim iSCOPE was evaluated; the optional comparison was not run for this isolated case study.",
        ]
    atomic_write_text(CASE_ROOT / "reports/nyc_case_study_findings.md", "\n".join(findings) + "\n")
    return {"data_quality": str(CASE_ROOT / "reports/nyc_case_study_data_quality.md"),
            "findings": str(CASE_ROOT / "reports/nyc_case_study_findings.md")}

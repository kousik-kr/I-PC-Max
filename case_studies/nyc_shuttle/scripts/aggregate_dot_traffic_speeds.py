#!/usr/bin/env python3
"""Deduplicate captured DOT observations and aggregate robust 15-minute bins."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path
from zoneinfo import ZoneInfo

from nyc_case_study.common import (
    CASE_ROOT, CaseStudyError, atomic_write_json, atomic_write_text,
    sha256_file,
)


def snapshot_paths(source: Path) -> list[Path]:
    paths = sorted(source.glob("dot-traffic-*.json"))
    if not paths:
        paths = sorted(source.glob("**/page-*.json"))
    if not paths:
        raise CaseStudyError(f"no DOT JSON snapshots/pages found under {source}")
    return paths


def load_observations(source: Path) -> tuple[list[dict[str, object]], int]:
    deduplicated: dict[tuple[str, str], dict[str, object]] = {}
    raw_count = 0
    for path in snapshot_paths(source):
        value = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(value, list):
            raise CaseStudyError(f"DOT artifact is not a JSON array: {path}")
        for row in value:
            raw_count += 1
            if not isinstance(row, dict):
                continue
            link = str(row.get("link_id", "")).strip()
            stamp = str(row.get("data_as_of", "")).strip()
            if link and stamp:
                deduplicated.setdefault((link, stamp), row)
    return list(deduplicated.values()), raw_count


def valid_number(value: object, *, positive: bool = False) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(number) or (positive and number <= 0):
        return None
    return number


def observation_instant(value: str, source_timezone: str) -> dt.datetime:
    """Localize Socrata floating timestamps before normalizing to UTC."""
    text = value.strip()
    normalized = text[:-1] + "+00:00" if text.endswith("Z") else text
    instant = dt.datetime.fromisoformat(normalized)
    if instant.tzinfo is None:
        instant = instant.replace(tzinfo=ZoneInfo(source_timezone))
    return instant.astimezone(dt.timezone.utc)


def bin_start(value: str, minutes: int, source_timezone: str) -> str:
    instant = observation_instant(value, source_timezone)
    floored = instant.replace(minute=instant.minute - instant.minute % minutes, second=0, microsecond=0)
    return floored.isoformat().replace("+00:00", "Z")


def aggregate(rows: list[dict[str, object]], minutes: int,
              source_timezone: str = "America/New_York") -> list[dict[str, object]]:
    groups: dict[tuple[str, str], list[dict[str, object]]] = defaultdict(list)
    for row in rows:
        try:
            start = bin_start(str(row["data_as_of"]), minutes, source_timezone)
        except (KeyError, ValueError):
            continue
        groups[(str(row.get("link_id", "")), start)].append(row)
    result: list[dict[str, object]] = []
    for (link, start), values in sorted(groups.items()):
        speeds = [number for row in values if (number := valid_number(row.get("speed"), positive=True)) is not None]
        times = [number for row in values if (number := valid_number(row.get("travel_time"), positive=True)) is not None]
        if not speeds and not times:
            continue
        exemplar = values[0]
        result.append({
            "link_id": link,
            "bin_start_utc": start,
            "median_speed": statistics.median(speeds) if speeds else None,
            "median_travel_time": statistics.median(times) if times else None,
            "observation_count": len(values),
            "valid_speed_count": len(speeds),
            "valid_travel_time_count": len(times),
            "borough": exemplar.get("borough"),
            "owner": exemplar.get("owner"),
            "link_name": exemplar.get("link_name"),
            "link_points": exemplar.get("link_points"),
            "encoded_poly_line": exemplar.get("encoded_poly_line"),
        })
    return result


def diagnostic(rows: list[dict[str, object]], raw_count: int, paths: list[Path],
               source_timezone: str) -> str:
    stamps = sorted({str(row.get("data_as_of")) for row in rows if row.get("data_as_of")})
    links = {str(row.get("link_id")) for row in rows if row.get("link_id")}
    missing_speed = sum(valid_number(row.get("speed")) is None for row in rows)
    zero_speed = sum((number := valid_number(row.get("speed"))) is not None and number <= 0 for row in rows)
    boroughs = Counter(str(row.get("borough") or "MISSING") for row in rows)
    historical = len(stamps) > 1
    lines = [
        "# DOT Traffic Speeds source diagnostic", "",
        f"- Raw rows read: {raw_count:,}",
        f"- Deduplicated `(link_id, data_as_of)` rows: {len(rows):,}",
        f"- Unique `link_id`: {len(links):,}",
        f"- Minimum `data_as_of`: {stamps[0] if stamps else 'MISSING'}",
        f"- Maximum `data_as_of`: {stamps[-1] if stamps else 'MISSING'}",
        f"- Distinct `data_as_of` timestamps: {len(stamps):,}",
        f"- Source timestamp contract: Socrata Floating Timestamp localized to `{source_timezone}` before UTC normalization",
        f"- Missing/non-numeric speed rate: {(100 * missing_speed / len(rows)) if rows else 0:.2f}%",
        f"- Zero/negative speed rate: {(100 * zero_speed / len(rows)) if rows else 0:.2f}%",
        f"- Snapshot classification: {'MULTI-TIMESTAMP OBSERVATIONS' if historical else 'CURRENT/LATEST SNAPSHOT ONLY'}",
        "", "## Borough counts", "",
    ]
    lines.extend(f"- {name}: {count:,}" for name, count in sorted(boroughs.items()))
    lines.extend(["", "## Input artifacts", ""])
    lines.extend(f"- `{path}` — SHA-256 `{sha256_file(path)}`" for path in paths)
    if not historical:
        lines.extend([
            "", "## Required action", "",
            "The available source does not establish a historical time series. Run the separate ",
            "resumable collector over the intended study period; no paper-ready temporal graph may ",
            "be built from this snapshot alone.",
        ])
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=CASE_ROOT / "raw/dot_traffic_snapshots")
    parser.add_argument("--output", type=Path, default=CASE_ROOT / "intermediate/dot_traffic_15min.parquet")
    parser.add_argument("--diagnostic", type=Path, default=CASE_ROOT / "reports/traffic_speed_source_diagnostic.md")
    parser.add_argument("--bin-minutes", type=int, default=15)
    parser.add_argument("--source-timezone", default="America/New_York")
    args = parser.parse_args()
    try:
        paths = snapshot_paths(args.input)
        rows, raw_count = load_observations(args.input)
        # Validate the configured IANA timezone even if all source rows are explicitly offset.
        ZoneInfo(args.source_timezone)
        atomic_write_text(args.diagnostic, diagnostic(rows, raw_count, paths, args.source_timezone))
        bins = aggregate(rows, args.bin_minutes, args.source_timezone)
        if not bins:
            raise CaseStudyError("no valid positive speed/travel-time observations to aggregate")
        import pandas as pd  # type: ignore
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.output.with_suffix(args.output.suffix + ".tmp")
        pd.DataFrame.from_records(bins).to_parquet(temporary, index=False)
        temporary.replace(args.output)
        manifest = {
            "schema_version": 1,
            "input_rows": raw_count,
            "deduplicated_rows": len(rows),
            "output_bins": len(bins),
            "bin_minutes": args.bin_minutes,
            "source_timestamp_type": "Socrata Floating Timestamp",
            "source_timezone": args.source_timezone,
            "normalization_timezone": "UTC",
            "temporal_start_utc": min(row["bin_start_utc"] for row in bins),
            "temporal_end_utc": max(row["bin_start_utc"] for row in bins),
            "input_sha256": {str(path): sha256_file(path) for path in paths},
            "output_sha256": sha256_file(args.output),
        }
        atomic_write_json(args.output.with_suffix(".manifest.json"), manifest)
    except (CaseStudyError, OSError, ValueError, json.JSONDecodeError, ImportError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 2
    print(json.dumps({"status": "COMPLETE", "bins": len(bins),
                      "output": str(args.output)}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

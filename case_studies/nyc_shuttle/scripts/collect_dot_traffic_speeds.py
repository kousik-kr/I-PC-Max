#!/usr/bin/env python3
"""Resumable collector for immutable NYC DOT Traffic Speeds snapshots."""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

from nyc_case_study.common import (
    CASE_ROOT, CaseStudyError, artifact_metadata, atomic_write_json,
    immutable_write, utc_now,
)
from nyc_case_study.socrata import SocrataClient


def records(payload: bytes) -> list[dict[str, object]]:
    value = json.loads(payload)
    if not isinstance(value, list) or not all(isinstance(item, dict) for item in value):
        raise CaseStudyError("DOT snapshot response must be a JSON row array")
    return value


def load_index(path: Path) -> dict[str, object]:
    if not path.exists():
        return {"schema_version": 1, "dataset_id": "i4gi-tjb9", "snapshots": [],
                "observation_keys": [], "duplicate_observations": 0}
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("dataset_id") != "i4gi-tjb9":
        raise CaseStudyError(f"unexpected collector index: {path}")
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--interval-minutes", type=float, default=5)
    parser.add_argument("--duration-hours", type=float, required=True, metavar="H")
    parser.add_argument("--output", type=Path, default=CASE_ROOT / "raw/dot_traffic_snapshots")
    parser.add_argument("--app-token-env", default="NYC_SOCRATA_APP_TOKEN")
    args = parser.parse_args()
    if args.interval_minutes <= 0 or args.duration_hours < 0:
        parser.error("interval must be positive and duration cannot be negative")
    args.output.mkdir(parents=True, exist_ok=True)
    index_path = args.output / "collector_index.json"
    try:
        index = load_index(index_path)
        known = set(str(item) for item in index["observation_keys"])
        duplicate_count = int(index.get("duplicate_observations", 0))
        client = SocrataClient(
            "data.cityofnewyork.us", "i4gi-tjb9", app_token_env=args.app_token_env
        )
        started = time.monotonic()
        duration = args.duration_hours * 3600
        poll = 0
        while True:
            retrieved_at = utc_now()
            url = client._resource_url("json", {"$limit": 50000, "$order": ":id"})
            payload = client._fetch(url)
            rows = records(payload)
            stamp = retrieved_at.replace(":", "").replace("-", "")
            path = args.output / f"dot-traffic-{stamp}-{poll:06d}.json"
            immutable_write(path, payload)
            new_keys = 0
            for row in rows:
                key = f"{row.get('link_id', '')}\u001f{row.get('data_as_of', '')}"
                if key in known:
                    duplicate_count += 1
                else:
                    known.add(key)
                    new_keys += 1
            snapshot = artifact_metadata(
                path, url=url, dataset_id="i4gi-tjb9", retrieved_at=retrieved_at,
                schema={"format": "json", "original_data_as_of_preserved": True},
            )
            snapshot.update({"row_count": len(rows), "new_observation_keys": new_keys})
            index["snapshots"].append(snapshot)
            index["observation_keys"] = sorted(known)
            index["duplicate_observations"] = duplicate_count
            index["updated_at_utc"] = utc_now()
            atomic_write_json(index_path, index)
            poll += 1
            elapsed = time.monotonic() - started
            if elapsed >= duration:
                break
            time.sleep(min(args.interval_minutes * 60, duration - elapsed))
    except (CaseStudyError, OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 2
    print(json.dumps({"status": "COMPLETE", "snapshots": len(index["snapshots"]),
                      "unique_observations": len(known),
                      "duplicates": duplicate_count}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

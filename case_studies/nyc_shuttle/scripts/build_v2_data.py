#!/usr/bin/env python3
"""Build the isolated NYC-real-shuttle-v2 exact-shape score dataset."""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import zipfile
from pathlib import Path

from nyc_case_study.common import (
    CASE_ROOT,
    CaseStudyError,
    atomic_write_json,
    canonical_json,
    load_config,
    sha256_file,
    utc_now,
)
from nyc_case_study.mapping import latest_download, map_mta_gtfs_shapes
from nyc_case_study.temporal import (
    active_route_bins,
    build_scores,
    read_schedule_pages,
)


V2_ROOT = CASE_ROOT / "v2"
V1_GRAPH = CASE_ROOT / "processed/NYC-REAL"
V2_GRAPH = V2_ROOT / "processed/NYC-REAL-V2"
GTFS_SPECS = {
    "bus_company": {
        "mdb_id": "mdb-510",
        "snapshot": "202604180128",
        "service_range": "2026-04-12 through 2026-06-28",
        "archive_url": "https://files.mobilitydatabase.org/mdb-510/mdb-510-202604180128/mdb-510-202604180128.zip",
        "official_producer_url": "http://web.mta.info/developers/data/busco/google_transit.zip",
    },
    "bronx": {
        "mdb_id": "mdb-528",
        "snapshot": "202604090053",
        "service_range": "2026-04-11 through 2026-06-28",
        "archive_url": "https://files.mobilitydatabase.org/mdb-528/mdb-528-202604090053/mdb-528-202604090053.zip",
        "official_producer_url": "http://web.mta.info/developers/data/nyct/bus/google_transit_bronx.zip",
    },
    "brooklyn": {
        "mdb_id": "mdb-512",
        "snapshot": "202604090141",
        "service_range": "2026-04-11 through 2026-06-28",
        "archive_url": "https://files.mobilitydatabase.org/mdb-512/mdb-512-202604090141/mdb-512-202604090141.zip",
        "official_producer_url": "http://web.mta.info/developers/data/nyct/bus/google_transit_brooklyn.zip",
    },
    "manhattan": {
        "mdb_id": "mdb-513",
        "snapshot": "202604090120",
        "service_range": "2026-04-11 through 2026-06-28",
        "archive_url": "https://files.mobilitydatabase.org/mdb-513/mdb-513-202604090120/mdb-513-202604090120.zip",
        "official_producer_url": "http://web.mta.info/developers/data/nyct/bus/google_transit_manhattan.zip",
    },
    "queens": {
        "mdb_id": "mdb-520",
        "snapshot": "202604090051",
        "service_range": "2026-04-11 through 2026-06-28",
        "archive_url": "https://files.mobilitydatabase.org/mdb-520/mdb-520-202604090051/mdb-520-202604090051.zip",
        "official_producer_url": "http://web.mta.info/developers/data/nyct/bus/google_transit_queens.zip",
    },
    "staten_island": {
        "mdb_id": "mdb-514",
        "snapshot": "202604090047",
        "service_range": "2026-04-12 through 2026-06-28",
        "archive_url": "https://files.mobilitydatabase.org/mdb-514/mdb-514-202604090047/mdb-514-202604090047.zip",
        "official_producer_url": "http://web.mta.info/developers/data/nyct/bus/google_transit_staten_island.zip",
    },
}


def prepare_graph() -> dict[str, str]:
    if V2_GRAPH.exists():
        required = {
            "nodes.csv.gz",
            "edges_static.csv.gz",
            "travel_time_functions.jsonl.gz",
            "travel_time_provenance.jsonl.gz",
            "manifest.json",
        }
        missing = sorted(name for name in required
                         if not (V2_GRAPH / name).is_file())
        if missing:
            raise CaseStudyError(
                f"incomplete existing V2 graph; missing {missing}")
    else:
        shutil.copytree(V1_GRAPH, V2_GRAPH)
    immutable = {}
    for name in (
        "nodes.csv.gz",
        "edges_static.csv.gz",
        "travel_time_functions.jsonl.gz",
        "travel_time_provenance.jsonl.gz",
    ):
        source_hash = sha256_file(V1_GRAPH / name)
        copied_hash = sha256_file(V2_GRAPH / name)
        if source_hash != copied_hash:
            raise CaseStudyError(f"V1/V2 immutable travel artifact mismatch: {name}")
        immutable[name] = source_hash
    manifest_path = V2_GRAPH / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest.update({
        "case_id": "NYC-REAL-V2",
        "parent_case_id": "NYC-REAL",
        "parent_graph_manifest_sha256": sha256_file(V1_GRAPH / "manifest.json"),
        "v2_repair_scope": [
            "CONNECTOR_FIX", "MTA_SHAPE_COMPLETENESS", "STATUS_ONLY",
        ],
        "travel_data_byte_identity": immutable,
    })
    provenance_counts = manifest.setdefault(
        "traffic_calibration", {}).setdefault("provenance_counts", {})
    for provenance in (
        "DIRECT",
        "BOROUGH_CLASS_IMPUTED",
        "CITYWIDE_IMPUTED",
        "STATIC_FALLBACK",
    ):
        provenance_counts.setdefault(provenance, 0)
    atomic_write_json(manifest_path, manifest)
    return immutable


def validate_archives() -> tuple[list[Path], dict[str, object]]:
    archives = []
    records = []
    for name, spec in sorted(GTFS_SPECS.items()):
        archive = V2_ROOT / "raw/mta_gtfs_static" / f"{name}.zip"
        if not archive.is_file():
            raise CaseStudyError(f"missing downloaded GTFS archive: {archive}")
        with zipfile.ZipFile(archive) as bundle:
            bad = bundle.testzip()
            names = {item.lower() for item in bundle.namelist()}
        if bad is not None or "shapes.txt" not in names:
            raise CaseStudyError(f"invalid GTFS archive {archive}; bad member={bad}")
        archives.append(archive)
        records.append({
            "feed": name,
            **spec,
            "local_path": str(archive.relative_to(CASE_ROOT)),
            "size_bytes": archive.stat().st_size,
            "sha256": sha256_file(archive),
        })
    manifest = {
        "schema_version": "nyc-v2-mta-gtfs-download-manifest-v1",
        "retrieved_at_utc": utc_now(),
        "target_schedule_date": "2026-05-14",
        "target_schedule_bundle": "2026Apr",
        "selection_rule": (
            "closest archived official-MTA producer feed whose declared "
            "service range contains 2026-05-14"
        ),
        "archive_role": (
            "MobilityDatabase preserves historical bytes fetched from each "
            "listed official MTA producer URL"
        ),
        "feeds": records,
    }
    output = V2_ROOT / "provenance/mta_gtfs_download_manifest.json"
    atomic_write_json(output, manifest)
    return archives, manifest


def write_v2_queries() -> dict[str, object]:
    source = CASE_ROOT / "manifests/nyc_queries.jsonl"
    output = V2_ROOT / "manifests/nyc_queries.jsonl"
    graph_hash = sha256_file(V2_GRAPH / "manifest.json")
    records = []
    for line in source.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        record = json.loads(line)
        record["schema_version"] = "nyc-query-v2"
        record["dataset_id"] = "NYC-REAL-V2"
        record["graph_manifest_sha256"] = graph_hash
        records.append(record)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        "".join(canonical_json(record) + "\n" for record in records),
        encoding="utf-8",
        newline="\n",
    )
    old = [json.loads(line) for line in source.read_text(
        encoding="utf-8").splitlines() if line.strip()]
    fields = (
        "query_id", "pair_id", "source", "destination", "period_id",
        "interval_start", "interval_end", "rho", "budget",
    )
    mismatches = []
    for left, right in zip(old, records):
        for field in fields:
            if left[field] != right[field]:
                mismatches.append({
                    "query_id": left["query_id"], "field": field,
                    "v1": left[field], "v2": right[field],
                })
    comparison = {
        "schema_version": "nyc-v1-v2-query-comparison-v1",
        "v1_query_count": len(old),
        "v2_query_count": len(records),
        "v1_pair_count": len({row["pair_id"] for row in old}),
        "v2_pair_count": len({row["pair_id"] for row in records}),
        "scientific_fields": list(fields),
        "scientific_field_mismatches": mismatches,
        "all_scientific_query_fields_identical":
            len(old) == len(records) and not mismatches,
        "v1_sha256": sha256_file(source),
        "v2_sha256": sha256_file(output),
        "permitted_provenance_changes": [
            "schema_version", "dataset_id", "graph_manifest_sha256",
        ],
    }
    atomic_write_json(
        V2_ROOT / "provenance/query_manifest_comparison.json",
        comparison,
    )
    if not comparison["all_scientific_query_fields_identical"]:
        raise CaseStudyError("V1/V2 scientific query fields differ")
    return comparison


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--config", type=Path,
        default=CASE_ROOT / "config/case_study.yaml")
    args = parser.parse_args()
    try:
        config = load_config(args.config)
        immutable = prepare_graph()
        archives, download_manifest = validate_archives()
        schedule_pages = latest_download(
            CASE_ROOT / "raw/mta_bus_schedules_2026", "csv")
        schedule = read_schedule_pages(schedule_pages)
        support = json.loads((V2_GRAPH / "manifest.json").read_text(
            encoding="utf-8"))["temporal_support"]
        active, _ = active_route_bins(
            schedule,
            support["observed_start_utc"],
            support["observed_end_utc"],
            int(config["time_bin_minutes"]),
        )
        active_shapes = {shape for _, shape, _ in active}
        mapping_stats = map_mta_gtfs_shapes(
            CASE_ROOT / "intermediate/dimacs_ny_edges.parquet",
            archives,
            active_shapes,
            V2_ROOT / "intermediate/mta_gtfs_shape_to_dimacs_edges.parquet",
            V2_ROOT / "reports/mta_gtfs_shape_mapping_quality.md",
            V2_ROOT / "provenance/mta_gtfs_shape_inventory.json",
            config,
        )
        score_stats = build_scores(
            V2_GRAPH,
            V2_ROOT / "intermediate/mta_gtfs_shape_to_dimacs_edges.parquet",
            schedule_pages,
            V2_ROOT / "reports/score_definition.md",
            config,
            V2_ROOT / "provenance/mta_gtfs_shape_inventory.json",
        )
        manifest_path = V2_GRAPH / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["score_functions_sha256"] = score_stats[
            "score_functions_sha256"]
        manifest["mta_gtfs_download_manifest_sha256"] = sha256_file(
            V2_ROOT / "provenance/mta_gtfs_download_manifest.json")
        manifest["mta_gtfs_shape_inventory_sha256"] = sha256_file(
            V2_ROOT / "provenance/mta_gtfs_shape_inventory.json")
        atomic_write_json(manifest_path, manifest)
        comparison = write_v2_queries()
        result = {
            "status": "COMPLETE",
            "case_id": "NYC-REAL-V2",
            "immutable_travel_artifacts": immutable,
            "gtfs_feed_count": len(download_manifest["feeds"]),
            "mapping": mapping_stats,
            "score": score_stats,
            "query_comparison": comparison,
        }
    except (CaseStudyError, OSError, ValueError, KeyError, zipfile.BadZipFile) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

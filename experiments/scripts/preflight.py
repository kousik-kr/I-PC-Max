#!/usr/bin/env python3
"""Read-only dataset, configuration, environment, and horizon preflight."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
import sys
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_json
from experiments.scripts.common.config import load_design, load_document, repo_path
from experiments.scripts.common.hashing import graph_checksum
from experiments.scripts.common.provenance import host_environment


REQUIRED_GRAPH_FILES = (
    "edges_static.csv.gz",
    "nodes.csv.gz",
    "manifest.json",
    "score_functions.jsonl.gz",
    "travel_time_functions.jsonl.gz",
)


def _support_end(manifest: dict[str, Any]) -> int | None:
    support = manifest.get("temporal_support")
    if isinstance(support, dict) and isinstance(support.get("end"), (int, float)):
        return int(support["end"])
    summary = manifest.get("time_window")
    if isinstance(summary, dict) and isinstance(summary.get("end_minute"), (int, float)):
        return int(summary["end_minute"])
    description = str(manifest.get("travel_time_output", {}).get("format", ""))
    match = re.search(r"(?:through|to)\s+([0-9]+)", description)
    return int(match.group(1)) if match else None


def inspect_dataset(dataset_id: str, config_path: Path, checksums: bool) -> dict[str, Any]:
    config = load_document(config_path)
    directory = repo_path(config["path"])
    files = [directory / name for name in REQUIRED_GRAPH_FILES]
    missing = [path.name for path in files if not path.is_file()]
    record: dict[str, Any] = {
        "dataset_id": dataset_id,
        "path": directory.relative_to(repo_path(".")).as_posix(),
        "required_files_present": not missing,
        "missing_files": missing,
        "graph_checksum": None,
    }
    if missing:
        record["errors"] = [f"missing required graph file: {name}" for name in missing]
        return record
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    support_end = _support_end(manifest)
    record.update({
        "nodes": manifest.get("num_nodes"),
        "edges": manifest.get("num_arcs"),
        "graph_seed": manifest.get("seed"),
        "score_density": manifest.get("score_edge_fraction"),
        "support_end": support_end,
        "disk_bytes": sum(path.stat().st_size for path in files),
    })
    errors: list[str] = []
    if record["nodes"] != config.get("expected_nodes"):
        errors.append(f"node count {record['nodes']} != expected {config.get('expected_nodes')}")
    if record["edges"] != config.get("expected_edges"):
        errors.append(f"edge count {record['edges']} != expected {config.get('expected_edges')}")
    required_support = config.get("required_support_end")
    if support_end is None:
        errors.append("manifest does not declare a machine-readable temporal support end")
    elif required_support is not None and support_end < required_support:
        errors.append(
            f"function support ends at {support_end}; paper design requires at least {required_support}"
        )
    if checksums:
        record["graph_checksum"] = graph_checksum(directory, REQUIRED_GRAPH_FILES)
    variants: list[dict[str, Any]] = []
    for percent in config.get("required_score_density_percent", []):
        variant = directory / "variants" / f"score-density-{int(percent):03d}"
        present = all((variant / name).is_file() for name in REQUIRED_GRAPH_FILES)
        variants.append({"kind": "score_density", "value": percent, "path": variant.as_posix(), "present": present})
        if not present:
            errors.append(f"required score-density variant is missing: {percent}%")
    for seed in config.get("required_graph_seeds", []):
        if seed == manifest.get("seed"):
            variants.append({"kind": "graph_seed", "value": seed, "path": directory.as_posix(), "present": True})
            continue
        variant = directory / "variants" / f"seed-{int(seed)}"
        present = all((variant / name).is_file() for name in REQUIRED_GRAPH_FILES)
        variants.append({"kind": "graph_seed", "value": seed, "path": variant.as_posix(), "present": present})
        if not present:
            errors.append(f"required graph-seed variant is missing: {seed}")
    record["variants"] = variants
    record["errors"] = errors
    return record


def run_preflight(
    config_path: Path,
    checksums: bool = True,
    allow_unresolved_resources: bool = False,
) -> dict[str, Any]:
    design = load_design(config_path)
    environment = host_environment()
    datasets = [
        inspect_dataset(dataset, repo_path(design["dataset_configs"][dataset]), checksums)
        for dataset in design["datasets"]
    ]
    blockers = [
        f"{item['dataset_id']}: {message}"
        for item in datasets
        for message in item.get("errors", [])
    ]
    resources = dict(design["resources"])
    if resources.get("require_server_profile_for_full_run") and resources.get("memory_limit_mb") is None:
        message = "full-run memory_limit_mb is unresolved; provide the target server profile"
        if allow_unresolved_resources:
            resources["warning"] = message
        else:
            blockers.append(message)
    for gate, state in design.get("implementation_gates", {}).items():
        if state is not True:
            blockers.append(f"implementation gate is unresolved: {gate}")
    jar = repo_path(design["paths"]["jar"])
    report = {
        "schema_version": 1,
        "config_hash": design["config_hash"],
        "experiment_id": design["experiment_id"],
        "ol_excluded": "OL" not in design["datasets"],
        "environment": environment,
        "resources": resources,
        "jar_present": jar.is_file(),
        "datasets": datasets,
        "checksums_computed": checksums,
        "blockers": blockers,
        "passed": not blockers,
    }
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path("experiments/configs/paper_q1.yaml"))
    parser.add_argument("--skip-checksums", action="store_true")
    parser.add_argument("--allow-unresolved-resources", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = run_preflight(
            args.config, not args.skip_checksums, args.allow_unresolved_resources
        )
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"preflight: {failure}", file=sys.stderr)
        return 2
    if args.output:
        atomic_write_json(args.output, report)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

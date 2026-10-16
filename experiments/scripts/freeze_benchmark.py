#!/usr/bin/env python3
"""Freeze explicit benchmark inputs from a clean release checkout; never run queries."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import platform
import subprocess
import sys

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.benchmark_freeze import sha256_file, source_paths
from experiments.scripts.common.config import REPO_ROOT, load_design, repo_path
from experiments.scripts.common.provenance import host_environment


def create_freeze(design: dict, source_repository: Path, jar: Path,
                  dataset_manifests: dict[str, Path], query_manifests: dict[str, Path],
                  run_series: str, approved: bool = False) -> dict:
    def git(*args):
        return subprocess.run(["git", *args], cwd=source_repository, check=True,
                              capture_output=True, text=True).stdout.strip()
    status = git("status", "--porcelain")
    if status:
        raise ValueError("source repository must be clean before creating a benchmark freeze")
    built_jar = source_repository / "target/pace-bench.jar"
    if not built_jar.is_file() or not jar.is_file() or sha256_file(built_jar) != sha256_file(jar):
        raise ValueError("benchmark JAR must match target/pace-bench.jar in the clean release checkout")
    artifacts = []

    def add(kind, path, dataset=None):
        path = path.resolve()
        if not path.is_file():
            raise ValueError(f"required freeze artifact is missing: {path}")
        artifacts.append({"kind": kind, "dataset": dataset, "path": str(path),
                          "sha256": sha256_file(path), "bytes": path.stat().st_size})

    add("jar", jar)
    datasets = []
    for dataset in design["datasets"]:
        if dataset not in dataset_manifests or dataset not in query_manifests:
            raise ValueError(f"explicit dataset/query manifest paths required for {dataset}")
        manifest = dataset_manifests[dataset]
        data = json.loads(manifest.read_text())
        if not data.get("dataset_checksum") or not data.get("output_files"):
            raise ValueError(f"dataset manifest lacks graph checksum/payload inventory: {manifest}")
        add("dataset_manifest", manifest, dataset)
        for name in data["output_files"]:
            if Path(name).is_absolute() or ".." in Path(name).parts:
                raise ValueError("dataset payload names must be local manifest-relative paths")
            if name != manifest.name:
                add("dataset_payload", manifest.parent / name, dataset)
        add("query_manifest", query_manifests[dataset], dataset)
        datasets.append({"dataset": dataset, "graph_checksum": data["dataset_checksum"],
                         "seed": data.get("seed"), "conversion_contract": data.get("conversion_contract")})
    for study in design["study_definitions"]:
        for algorithm in study["algorithms"]:
            parameters = algorithm.get("parameters", {})
            for key in ["thop_partitions", "sicsp_weights"]:
                if key in parameters:
                    add(key, repo_path(parameters[key]))
            if algorithm["id"] == "thop-partitioned":
                directory = repo_path(parameters["thop_index_dir"])
                index_files = sorted(p for p in directory.rglob("*") if p.is_file())
                if len(index_files) < 2:
                    raise ValueError("THop requires a built, frozen v2 index before the query freeze")
                for path in index_files:
                    add("thop_index", path)
    source = {"commit": git("rev-parse", "HEAD"), "tree": git("rev-parse", "HEAD^{tree}"),
              "clean": True, "files": {p.relative_to(source_repository).as_posix(): sha256_file(p)
                                         for p in source_paths(source_repository)}}
    return {"schema_version": 1, "run_series_id": run_series,
            "created_at_utc": datetime.now(timezone.utc).isoformat(),
            "source": source, "config_hash": design["config_hash"],
            "clean_checkout_built_jar_sha256": sha256_file(built_jar),
            "config_path": design["config_path"], "artifacts": artifacts,
            "dataset_identities": datasets, "resources": design["resources"],
            "protocol": design["protocol"], "seeds": design["seeds"],
            "status_schema_version": "icde2027-objective-specific-v1",
            "numerical_contracts": design.get("numerical_contracts"),
            "runner_sha256": sha256_file(source_repository / "experiments/scripts/execute_matrix.py"),
            "aggregator_sha256": sha256_file(source_repository / "experiments/scripts/summarize_results.py"),
            "environment": host_environment(),
            "readiness": {"approved": approved,
                          "approval_scope": "Prospective configuration only; no benchmark is started by this writer.",
                          "historical_result_reuse": False}}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--design", type=Path, required=True)
    parser.add_argument("--source-repository", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--dataset-manifest", action="append", default=[], metavar="DATASET=PATH")
    parser.add_argument("--query-manifest", action="append", default=[], metavar="DATASET=PATH")
    parser.add_argument("--run-series", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--approved-for-execution", action="store_true",
                        help="Use only after separate explicit authorization for this run series.")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("refusing to overwrite a frozen identity; choose a new run-series file")
    def mappings(values):
        result = {}
        for value in values:
            key, separator, path = value.partition("=")
            if not separator or key in result:
                raise ValueError("manifest arguments require unique DATASET=PATH entries")
            result[key] = Path(path)
        return result
    value = create_freeze(load_design(args.design), args.source_repository.resolve(), args.jar,
                          mappings(args.dataset_manifest), mappings(args.query_manifest),
                          args.run_series, args.approved_for_execution)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x") as handle:
        json.dump(value, handle, indent=2, sort_keys=True, allow_nan=False)
        handle.write("\n")
    print(args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

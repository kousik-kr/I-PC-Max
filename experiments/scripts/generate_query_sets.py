#!/usr/bin/env python3
"""Prepare deterministic graph-backed query sets for the PACE study matrix."""
from __future__ import annotations

import argparse
import collections
from decimal import Decimal, ROUND_HALF_EVEN
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import (
    atomic_write_json,
    atomic_write_text,
    write_jsonl,
)
from experiments.scripts.common.config import (
    filtered_design,
    load_design,
    load_document,
    repo_path,
)
from experiments.scripts.common.hashing import (
    dataset_checksum,
    graph_checksum,
    sha256_file,
    temporal_attribute_checksum,
)
from experiments.scripts.common.toolchain import environment, executable
from experiments.scripts.generate_queries import (
    read_jsonl,
    validate_all,
    validate_manifest,
)


REQUIRED_GRAPH_FILES = (
    "edges_static.csv.gz",
    "nodes.csv.gz",
    "manifest.json",
    "score_functions.jsonl.gz",
    "travel_time_functions.jsonl.gz",
)
SPLITS = ("pilot", "warmup", "evaluation")
REQUIRED_ROW_METADATA = {
    "budget_definition",
    "budget_evidence",
    "conversion_contract_version",
    "dataset_checksum",
    "dataset_path",
    "delta_minutes",
    "distance_band",
    "function_support_end",
    "generation_contract",
    "generator_config_hash",
    "generator_version",
    "graph_checksum",
    "graph_seed",
    "interval_center",
    "pair_id",
    "pair_index",
    "rho",
    "selection_seed",
    "split",
    "split_seed",
    "t_hat_min_delta",
    "temporal_attribute_checksum",
}


class AssetError(ValueError):
    """Raised before Java graph loading when required assets are absent."""


def _study_datasets(
    study: dict[str, Any],
    design: dict[str, Any],
) -> list[str]:
    configured = study.get("datasets", "all")
    if configured == "all":
        return list(design["datasets"])
    return [dataset for dataset in configured if dataset in design["datasets"]]


def derive_requirements(design: dict[str, Any]) -> dict[str, Any]:
    """Derive query axes and variant reuse from the checked E00-E13 matrix."""
    workload = design["workload"]
    default_window = int(workload["default_window_minutes"])
    default_overhead = float(workload["default_budget_overhead"])
    requirements: dict[str, Any] = {
        "split_pairs": dict(workload["pair_splits"]),
        "centers": set(int(value) for value in workload["time_centers_minutes"]),
        "window_minutes": {default_window},
        "budget_overheads": {default_overhead},
        "variants": {dataset: [] for dataset in design["datasets"]},
        "reused_non_query_axes": set(),
    }
    observed_pairs: dict[tuple[str, str], int] = {}
    observed_centers: set[int] = set()
    variant_keys: set[tuple[str, str, str]] = set()
    for study in design["study_definitions"]:
        if study.get("mode") != "execute" or study.get("manifest"):
            continue
        split = study.get("split")
        if split in SPLITS:
            for dataset in _study_datasets(study, design):
                key = (dataset, split)
                observed_pairs[key] = max(
                    observed_pairs.get(key, 0),
                    int(study.get("pairs_per_dataset", 0)),
                )
        observed_centers.update(int(value) for value in study.get("centers", []))
        for algorithm in study.get("algorithms", []):
            requirements["reused_non_query_axes"].update(
                algorithm.get("parameters", {}).keys()
            )
            if algorithm.get("variant"):
                requirements["reused_non_query_axes"].add("ablation")
        for axis in study.get("axes", [{}]):
            if "window_minutes" in axis:
                requirements["window_minutes"].add(int(axis["window_minutes"]))
            if "budget_overhead" in axis:
                requirements["budget_overheads"].add(
                    float(axis["budget_overhead"])
                )
            requirements["reused_non_query_axes"].update(
                key
                for key in axis
                if key not in {"window_minutes", "budget_overhead",
                               "score_density", "graph_seed"}
            )
            for dataset in _study_datasets(study, design):
                if "score_density" in axis:
                    percent = int(round(100 * float(axis["score_density"])))
                    suffix = f"-SD{percent:03d}"
                    key = (dataset, "score_density", suffix)
                    if key not in variant_keys:
                        requirements["variants"][dataset].append({
                            "kind": "score_density",
                            "value": str(percent),
                            "suffix": suffix,
                            "path": design["graph_variants"][
                                "score_density_pattern"
                            ].format(dataset=dataset, percent=percent),
                            "maximum_pairs": int(study["pairs_per_dataset"]),
                        })
                        variant_keys.add(key)
                if "graph_seed" in axis:
                    seed = int(axis["graph_seed"])
                    suffix = f"-GS{seed}"
                    key = (dataset, "graph_seed", suffix)
                    if key not in variant_keys:
                        path = (
                            design["dataset_definitions"][dataset]["path"]
                            if seed == int(design["seeds"]["graph_main"])
                            else design["graph_variants"][
                                "graph_seed_pattern"
                            ].format(dataset=dataset, seed=seed)
                        )
                        requirements["variants"][dataset].append({
                            "kind": "graph_seed",
                            "value": str(seed),
                            "suffix": suffix,
                            "path": path,
                            "maximum_pairs": int(study["pairs_per_dataset"]),
                        })
                        variant_keys.add(key)
    if observed_centers and observed_centers != requirements["centers"]:
        raise ValueError(
            f"study centers {sorted(observed_centers)} disagree with workload "
            f"centers {sorted(requirements['centers'])}"
        )
    for (dataset, split), count in sorted(observed_pairs.items()):
        expected = int(requirements["split_pairs"][split])
        if count > expected:
            raise ValueError(
                f"{dataset} requires {count} {split} pairs; workload defines "
                f"{expected}"
            )
    declared_windows = {
        int(value) for value in workload.get("window_lengths_minutes", [])
    }
    if declared_windows and requirements["window_minutes"] != declared_windows:
        raise ValueError("study and workload window axes disagree")
    declared_overheads = {
        float(value) for value in workload.get("budget_overheads", [])
    }
    if (
        declared_overheads
        and requirements["budget_overheads"] != declared_overheads
    ):
        raise ValueError("study and workload budget axes disagree")
    requirements["centers"] = sorted(requirements["centers"])
    requirements["window_minutes"] = sorted(requirements["window_minutes"])
    requirements["budget_overheads"] = sorted(
        requirements["budget_overheads"]
    )
    requirements["reused_non_query_axes"] = sorted(
        requirements["reused_non_query_axes"]
    )
    for dataset in design["datasets"]:
        requirements["variants"][dataset].sort(
            key=lambda item: item["suffix"]
        )
    return requirements


def expected_instance_counts(
    design: dict[str, Any],
    requirements: dict[str, Any],
    dataset: str,
) -> dict[str, int]:
    """Independent arithmetic for base and matrix-derived query rows."""
    centers = len(requirements["centers"])
    evaluation_cells = centers * (
        len(requirements["window_minutes"])
        + len(requirements["budget_overheads"])
        - 1
    )
    counts = {
        "pilot": int(requirements["split_pairs"]["pilot"]) * centers,
        "warmup": int(requirements["split_pairs"]["warmup"]) * centers,
        "evaluation_base": (
            int(requirements["split_pairs"]["evaluation"]) * evaluation_cells
        ),
    }
    counts["evaluation_variants"] = sum(
        int(variant["maximum_pairs"]) * centers
        for variant in requirements["variants"][dataset]
    )
    counts["evaluation"] = (
        counts["evaluation_base"] + counts["evaluation_variants"]
    )
    counts["combined"] = (
        counts["pilot"] + counts["warmup"] + counts["evaluation"]
    )
    return counts


def _support_end(manifest: dict[str, Any]) -> int | None:
    support = manifest.get("temporal_support")
    if isinstance(support, dict) and isinstance(
        support.get("end"), (int, float)
    ):
        return int(support["end"])
    return None


def _dataset_asset_errors(
    dataset: str,
    design: dict[str, Any],
    requirements: dict[str, Any],
) -> list[str]:
    definition = design["dataset_definitions"][dataset]
    directories = [repo_path(definition["path"])]
    directories.extend(
        repo_path(variant["path"])
        for variant in requirements["variants"][dataset]
    )
    errors: list[str] = []
    required_contract = definition["required_conversion_contract"]
    for directory in directories:
        missing = [
            filename for filename in REQUIRED_GRAPH_FILES
            if not (directory / filename).is_file()
        ]
        if missing:
            errors.append(
                f"{dataset}: {directory}: missing {', '.join(missing)}"
            )
            continue
        try:
            manifest = load_document(directory / "manifest.json")
        except (OSError, ValueError, json.JSONDecodeError) as failure:
            errors.append(f"{dataset}: invalid manifest {directory}: {failure}")
            continue
        if manifest.get("num_nodes") != definition["expected_nodes"]:
            errors.append(f"{dataset}: {directory}: node count mismatch")
        if manifest.get("num_arcs") != definition["expected_edges"]:
            errors.append(f"{dataset}: {directory}: arc count mismatch")
        support_end = _support_end(manifest)
        if (
            support_end is None
            or support_end < definition["required_support_end"]
        ):
            errors.append(f"{dataset}: {directory}: temporal horizon is unsafe")
        if (
            manifest.get("conversion_contract", {}).get("contract_id")
            != required_contract
        ):
            errors.append(
                f"{dataset}: {directory}: conversion contract mismatch"
            )
        structural = dataset_checksum(directory)
        temporal = temporal_attribute_checksum(directory)
        if manifest.get("dataset_checksum") != structural:
            errors.append(f"{dataset}: {directory}: dataset checksum mismatch")
        if manifest.get("temporal_attribute_checksum") != temporal:
            errors.append(
                f"{dataset}: {directory}: temporal checksum mismatch"
            )
    return errors


def inspect_generation_assets(
    design: dict[str, Any],
    requirements: dict[str, Any],
    datasets: list[str],
) -> dict[str, Any]:
    records = []
    errors: list[str] = []
    for dataset in datasets:
        dataset_errors = _dataset_asset_errors(
            dataset, design, requirements
        )
        errors.extend(dataset_errors)
        records.append({
            "dataset_id": dataset,
            "path": design["dataset_definitions"][dataset]["path"],
            "variants": requirements["variants"][dataset],
            "errors": dataset_errors,
        })
    return {"datasets": records, "errors": errors, "passed": not errors}


def _generation_spec(
    dataset: str,
    design: dict[str, Any],
    requirements: dict[str, Any],
) -> dict[str, Any]:
    definition = design["dataset_definitions"][dataset]
    splits = requirements["split_pairs"]
    return {
        "schema_version": 2,
        "contract": design["query_generation"]["required_contract"],
        "conversion_contract_version":
            definition["required_conversion_contract"],
        "dataset_id": dataset,
        "dataset_path": str(repo_path(definition["path"])),
        "query_configuration": str(
            repo_path(design["query_generation"]["configuration"])
        ),
        "generator_config_hash": design["query_generation_config_hash"],
        "selection_seed": int(design["seeds"]["query_evaluation"]),
        "split_seeds": {
            "pilot": int(design["seeds"]["query_pilot"]),
            "warmup": int(design["seeds"]["query_warmup"]),
            "evaluation": int(design["seeds"]["query_evaluation"]),
        },
        "distance_bands": int(design["workload"]["distance_bands"]),
        "pilot_pairs": int(splits["pilot"]),
        "warmup_pairs": int(splits["warmup"]),
        "evaluation_pairs": int(splits["evaluation"]),
        "centers": requirements["centers"],
        "window_minutes": requirements["window_minutes"],
        "budget_overheads": requirements["budget_overheads"],
        "default_window_minutes":
            int(design["workload"]["default_window_minutes"]),
        "default_budget_overhead":
            float(design["workload"]["default_budget_overhead"]),
        "evaluation_grid_minutes":
            int(design["workload"]["evaluation_grid_minutes"]),
        "budget_definition": design["workload"]["budget_definition"],
        "required_support_end": int(definition["required_support_end"]),
        "variants": [
            {**variant, "path": str(repo_path(variant["path"]))}
            for variant in requirements["variants"][dataset]
        ],
    }


def _build_jar(design: dict[str, Any]) -> None:
    completed = subprocess.run(
        [executable("mvn"), "-q", "-DskipTests", "package"],
        cwd=repo_path("."),
        env=environment(),
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"Maven package failed with exit code {completed.returncode}"
        )
    jar = repo_path(design["paths"]["jar"])
    if not jar.is_file():
        raise RuntimeError(f"configured JAR was not created: {jar}")


def _run_java_generator(
    design: dict[str, Any],
    spec_path: Path,
    output_path: Path,
) -> dict[str, Any]:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    memory_limit_mb = design["resources"].get("memory_limit_mb")
    if not isinstance(memory_limit_mb, int) or memory_limit_mb <= 0:
        raise ValueError(
            "query generation requires a positive memory_limit_mb"
        )
    command = [
        executable("java"),
        f"-Xmx{memory_limit_mb}m",
        "-cp",
        str(repo_path(design["paths"]["jar"])),
        design["query_generation"]["paper_java_main_class"],
        "--spec",
        str(spec_path),
        "--output",
        str(output_path),
    ]
    completed = subprocess.run(
        command,
        cwd=repo_path("."),
        env=environment(),
        text=True,
        capture_output=True,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            "Java paper query generator failed: "
            + (completed.stderr.strip() or completed.stdout.strip())
        )
    lines = [line for line in completed.stdout.splitlines() if line.strip()]
    if not lines:
        raise RuntimeError("Java query generator emitted no summary")
    try:
        return json.loads(lines[-1])
    except json.JSONDecodeError as failure:
        raise RuntimeError(
            f"invalid Java query-generator summary: {lines[-1]}"
        ) from failure


def _canonical_time(value: float) -> float:
    return float(
        Decimal(str(value)).quantize(
            Decimal("0.000000000001"),
            rounding=ROUND_HALF_EVEN,
        )
    )


def validate_prepared_manifest(
    path: Path,
    dataset: str,
    design: dict[str, Any],
    expected_counts: dict[str, int],
) -> dict[str, Any]:
    result = validate_manifest(path, dataset, design)
    rows = read_jsonl(path)
    errors = result["errors"]
    pair_endpoints: dict[str, tuple[int, int, str]] = {}
    split_pairs: dict[str, set[str]] = {
        split: set() for split in SPLITS
    }
    checksums: dict[Path, tuple[str, str, str]] = {}
    rows_by_split = collections.Counter()
    for row in rows:
        query_id = row.get("query_id")
        metadata = row.get("metadata", {})
        missing = sorted(REQUIRED_ROW_METADATA - set(metadata))
        if missing:
            errors.append(
                f"query {query_id} lacks metadata fields: {missing}"
            )
            continue
        split = metadata["split"]
        rows_by_split[split] += 1
        pair_id = metadata["pair_id"]
        endpoint = (row["source"], row["destination"], split)
        previous = pair_endpoints.setdefault(pair_id, endpoint)
        if previous != endpoint:
            errors.append(
                f"pair_id {pair_id} maps to multiple endpoints/splits"
            )
        split_pairs[split].add(pair_id)
        if metadata["delta_minutes"] != 1:
            errors.append(f"query {query_id} does not use Delta=1")
        if metadata["interval_center"] * 2 != (
            row["interval_start"] + row["interval_end"]
        ):
            errors.append(f"query {query_id} has wrong interval center")
        expected_budget = _canonical_time(
            (1.0 + float(metadata["rho"]))
            * float(metadata["t_hat_min_delta"])
        )
        if _canonical_time(float(row["budget"])) != expected_budget:
            errors.append(f"query {query_id} has wrong canonical budget")
        if (
            _canonical_time(row["interval_end"] + row["budget"])
            > float(metadata["function_support_end"])
        ):
            errors.append(f"query {query_id} is horizon-unsafe")
        dataset_path = Path(metadata["dataset_path"])
        if dataset_path not in checksums:
            checksums[dataset_path] = (
                graph_checksum(dataset_path, REQUIRED_GRAPH_FILES),
                dataset_checksum(dataset_path),
                temporal_attribute_checksum(dataset_path),
            )
        actual_graph, actual_dataset, actual_temporal = checksums[dataset_path]
        if metadata["graph_checksum"] != actual_graph:
            errors.append(f"query {query_id} graph checksum mismatch")
        if metadata["dataset_checksum"] != actual_dataset:
            errors.append(f"query {query_id} dataset checksum mismatch")
        if metadata["temporal_attribute_checksum"] != actual_temporal:
            errors.append(f"query {query_id} temporal checksum mismatch")
        if metadata["conversion_contract_version"] != (
            design["dataset_definitions"][dataset][
                "required_conversion_contract"
            ]
        ):
            errors.append(f"query {query_id} conversion contract mismatch")
    for left, right in (
        ("pilot", "warmup"),
        ("pilot", "evaluation"),
        ("warmup", "evaluation"),
    ):
        overlap = split_pairs[left] & split_pairs[right]
        if overlap:
            errors.append(
                f"{left}/{right} pair ID leakage: {len(overlap)}"
            )
    expected_rows = {
        "pilot": expected_counts["pilot"],
        "warmup": expected_counts["warmup"],
        "evaluation": expected_counts["evaluation"],
    }
    if dict(rows_by_split) != expected_rows:
        errors.append(
            f"derived row counts {dict(rows_by_split)} != {expected_rows}"
        )
    if len(rows) != expected_counts["combined"]:
        errors.append(
            f"combined rows {len(rows)} != {expected_counts['combined']}"
        )
    result["rows_by_split"] = dict(sorted(rows_by_split.items()))
    result["independent_expected_counts"] = expected_counts
    return result


def _sidecar(
    dataset: str,
    split: str,
    rows: list[dict[str, Any]],
    query_path: Path,
    design: dict[str, Any],
    requirements: dict[str, Any],
    expected_counts: dict[str, int],
) -> dict[str, Any]:
    inputs: dict[tuple[str, str, str], dict[str, Any]] = {}
    for row in rows:
        metadata = row["metadata"]
        key = (
            metadata["dataset_checksum"],
            metadata["temporal_attribute_checksum"],
            metadata["graph_checksum"],
        )
        inputs.setdefault(key, {
            "dataset_path": metadata["dataset_path"],
            "dataset_checksum": key[0],
            "temporal_attribute_checksum": key[1],
            "graph_checksum": key[2],
            "conversion_contract_version":
                metadata["conversion_contract_version"],
            "graph_seed": metadata["graph_seed"],
            "variant_kind": metadata.get("variant_kind"),
            "variant_value": metadata.get("variant_value"),
        })
    pair_ids = {row["metadata"]["pair_id"] for row in rows}
    checksum = sha256_file(query_path)
    return {
        "schema_version": 2,
        "contract": design["query_generation"]["required_contract"],
        "dataset_id": dataset,
        "query_split": split,
        "all_seeds": design["seeds"],
        "generation_parameters": {
            "distance_bands": int(design["workload"]["distance_bands"]),
            "time_centers_minutes": requirements["centers"],
            "window_lengths_minutes": requirements["window_minutes"],
            "budget_overheads": requirements["budget_overheads"],
            "budget_definition": design["workload"]["budget_definition"],
            "delta_minutes":
                int(design["workload"]["evaluation_grid_minutes"]),
            "reused_non_query_axes":
                requirements["reused_non_query_axes"],
        },
        "independent_expected_counts": expected_counts,
        "number_of_queries": len(rows),
        "number_of_unique_pair_ids": len(pair_ids),
        "input_datasets": list(inputs.values()),
        "output_query_file":
            query_path.relative_to(repo_path(".")).as_posix(),
        "output_query_sha256": checksum,
        "manifest_checksum": checksum,
        "generator": design["query_generation"]["paper_java_main_class"],
        "generator_version": (
            rows[0]["metadata"]["generator_version"] if rows else None
        ),
        "generator_config_sha256":
            design["query_generation_config_hash"],
        "generator_command": [
            "java",
            "-cp",
            design["paths"]["jar"],
            design["query_generation"]["paper_java_main_class"],
            "--spec",
            "<generated-spec>",
            "--output",
            "<generated-query-manifest>",
        ],
    }


def _write_outputs(
    dataset: str,
    generated: Path,
    destination: Path,
    design: dict[str, Any],
    requirements: dict[str, Any],
    expected_counts: dict[str, int],
    overwrite: bool,
    resume: bool,
) -> dict[str, Any]:
    rows = read_jsonl(generated)
    validation = validate_prepared_manifest(
        generated, dataset, design, expected_counts
    )
    if validation["errors"]:
        raise RuntimeError(
            f"generated {dataset} manifest is invalid: "
            + "; ".join(validation["errors"])
        )
    if destination.exists() and destination.read_bytes() != generated.read_bytes():
        if not overwrite:
            mode = "--resume" if resume else "generation"
            raise FileExistsError(
                f"{mode}: existing manifest differs; use --overwrite: "
                f"{destination}"
            )
    if not destination.exists() or overwrite:
        atomic_write_text(
            destination, generated.read_text(encoding="utf-8")
        )
    split_rows = {
        split: [
            row for row in rows
            if row["metadata"]["split"] == split
        ]
        for split in SPLITS
    }
    outputs: dict[str, Any] = {}
    for split, selected in split_rows.items():
        path = destination.parent / f"{split}.jsonl"
        write_jsonl(path, selected)
        sidecar = _sidecar(
            dataset,
            split,
            selected,
            path,
            design,
            requirements,
            expected_counts,
        )
        atomic_write_json(path.with_suffix(".manifest.json"), sidecar)
        outputs[split] = {
            "path": path.relative_to(repo_path(".")).as_posix(),
            "rows": len(selected),
            "pairs": sidecar["number_of_unique_pair_ids"],
            "checksum": sidecar["manifest_checksum"],
        }
    combined = _sidecar(
        dataset,
        "combined",
        rows,
        destination,
        design,
        requirements,
        expected_counts,
    )
    atomic_write_json(destination.with_suffix(".manifest.json"), combined)
    outputs["combined"] = {
        "path": destination.relative_to(repo_path(".")).as_posix(),
        "rows": len(rows),
        "pairs": combined["number_of_unique_pair_ids"],
        "checksum": combined["manifest_checksum"],
    }
    return outputs


def _validate_sidecar(
    path: Path,
    dataset: str,
    split: str,
) -> list[str]:
    if not path.is_file():
        return [f"missing query sidecar: {path}"]
    try:
        value = load_document(path)
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        return [f"malformed query sidecar {path}: {failure}"]
    errors = []
    if value.get("schema_version") != 2:
        errors.append(f"{path}: unsupported schema_version")
    if value.get("dataset_id") != dataset:
        errors.append(f"{path}: dataset_id mismatch")
    if value.get("query_split") != split:
        errors.append(f"{path}: split mismatch")
    query_file = value.get("output_query_file")
    if not isinstance(query_file, str):
        return errors + [f"{path}: output_query_file is missing"]
    query_path = repo_path(query_file)
    if not query_path.is_file():
        return errors + [f"{path}: output query file is missing"]
    checksum = sha256_file(query_path)
    if value.get("output_query_sha256") != checksum:
        errors.append(f"{path}: output checksum mismatch")
    if value.get("manifest_checksum") != checksum:
        errors.append(f"{path}: manifest checksum mismatch")
    rows = read_jsonl(query_path)
    if value.get("number_of_queries") != len(rows):
        errors.append(f"{path}: row count mismatch")
    return errors


def validate_sidecars(
    design: dict[str, Any],
    datasets: list[str],
) -> dict[str, Any]:
    records = []
    pattern = design["query_generation"]["manifest_pattern"]
    for dataset in datasets:
        combined = repo_path(pattern.format(dataset=dataset))
        errors = _validate_sidecar(
            combined.with_suffix(".manifest.json"),
            dataset,
            "combined",
        )
        for split in SPLITS:
            errors.extend(_validate_sidecar(
                combined.parent / f"{split}.manifest.json",
                dataset,
                split,
            ))
        records.append({
            "dataset_id": dataset,
            "path": combined.as_posix(),
            "errors": errors,
        })
    return {
        "datasets": records,
        "passed": all(not record["errors"] for record in records),
    }


def plan(
    design: dict[str, Any],
    selected: list[str],
    requirements: dict[str, Any],
) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "mode": "plan-only",
        "datasets": [
            {
                "dataset_id": dataset,
                "manifest": design["query_generation"][
                    "manifest_pattern"
                ].format(dataset=dataset),
                "base_pairs": requirements["split_pairs"],
                "derived_instances": expected_instance_counts(
                    design, requirements, dataset
                ),
                "variants": requirements["variants"][dataset],
            }
            for dataset in selected
        ],
        "passed": True,
    }


def generate(
    config: Path,
    datasets: list[str] | None,
    validate_only: bool,
    overwrite: bool,
    skip_build: bool,
    resume: bool = False,
    plan_only: bool = False,
) -> dict[str, Any]:
    design = load_design(config)
    selected = datasets or list(design["datasets"])
    unknown = sorted(set(selected) - set(design["datasets"]))
    if unknown:
        raise ValueError(f"datasets are not configured: {unknown}")
    requirements = derive_requirements(design)
    if plan_only:
        return plan(design, selected, requirements)
    assets = inspect_generation_assets(design, requirements, selected)
    if not assets["passed"]:
        raise AssetError(
            "query preparation assets are incomplete:\n  - "
            + "\n  - ".join(assets["errors"])
        )
    if not skip_build:
        _build_jar(design)
    outputs: dict[str, Any] = {}
    with tempfile.TemporaryDirectory(
        prefix="pace-paper-querygen-"
    ) as temporary:
        root = Path(temporary)
        for dataset in selected:
            expected = expected_instance_counts(
                design, requirements, dataset
            )
            spec_path = root / f"{dataset}.spec.json"
            atomic_write_json(
                spec_path,
                _generation_spec(dataset, design, requirements),
            )
            generated = root / dataset / "paper_q1.jsonl"
            java_summary = _run_java_generator(
                design, spec_path, generated
            )
            destination = repo_path(
                design["query_generation"]["manifest_pattern"].format(
                    dataset=dataset
                )
            )
            if validate_only:
                if not destination.is_file():
                    raise FileNotFoundError(
                        f"query manifest is missing: {destination}"
                    )
                if destination.read_bytes() != generated.read_bytes():
                    raise RuntimeError(
                        f"{dataset} manifest differs from deterministic "
                        "Java output"
                    )
                validation = validate_prepared_manifest(
                    destination, dataset, design, expected
                )
                if validation["errors"]:
                    raise RuntimeError(
                        f"{dataset} validation failed: "
                        + "; ".join(validation["errors"])
                    )
                outputs[dataset] = {
                    "deterministic_match": True,
                    "validation": validation,
                    "java": java_summary,
                }
            else:
                outputs[dataset] = {
                    "files": _write_outputs(
                        dataset,
                        generated,
                        destination,
                        design,
                        requirements,
                        expected,
                        overwrite,
                        resume,
                    ),
                    "java": java_summary,
                }
    validation_design = (
        design
        if set(selected) == set(design["datasets"])
        else filtered_design(design, set(selected))
    )
    query_validation = validate_all(validation_design)
    stronger_records = []
    for dataset in selected:
        path = repo_path(
            design["query_generation"]["manifest_pattern"].format(
                dataset=dataset
            )
        )
        stronger_records.append(validate_prepared_manifest(
            path,
            dataset,
            design,
            expected_instance_counts(design, requirements, dataset),
        ))
    sidecars = validate_sidecars(design, selected)
    passed = (
        query_validation["passed"]
        and sidecars["passed"]
        and all(not record["errors"] for record in stronger_records)
    )
    return {
        "schema_version": 2,
        "config": design["config_path"],
        "datasets": selected,
        "requirements": requirements,
        "assets": assets,
        "outputs": outputs,
        "query_validation": query_validation,
        "preparation_validation": stronger_records,
        "sidecar_validation": sidecars,
        "passed": passed,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--config",
        type=Path,
        default=Path(
            "experiments/configs/paper_q1_server_24c_250g.yaml"
        ),
    )
    parser.add_argument("--dataset", action="append")
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--plan-only", action="store_true")
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    if sum(int(value) for value in (
        args.validate_only,
        args.overwrite,
        args.resume,
        args.plan_only,
    )) > 1:
        parser.error(
            "--validate-only, --overwrite, --resume, and --plan-only "
            "are mutually exclusive"
        )
    try:
        report = generate(
            args.config,
            args.dataset,
            args.validate_only,
            args.overwrite,
            args.skip_build,
            args.resume,
            args.plan_only,
        )
    except (
        OSError,
        ValueError,
        RuntimeError,
        subprocess.SubprocessError,
    ) as failure:
        print(f"paper query preparation: {failure}", file=sys.stderr)
        return 2
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

"""Load and validate the checked-in Q1 experiment design."""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .hashing import sha256_file, sha256_json


REPO_ROOT = Path(__file__).resolve().parents[3]
SUPPORTED_DATASETS = ("NY", "FLA", "CAL", "USA")


def load_document(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8")
    try:
        value = json.loads(text)
    except json.JSONDecodeError:
        try:
            import yaml  # type: ignore
        except ImportError as exc:
            raise ValueError(
                f"{path} is not JSON-compatible YAML and PyYAML is not installed"
            ) from exc
        value = yaml.safe_load(text)
    if not isinstance(value, dict):
        raise ValueError(f"configuration root must be an object: {path}")
    return value


def repo_path(value: str | Path) -> Path:
    path = Path(value)
    return path if path.is_absolute() else REPO_ROOT / path


def load_design(path: Path) -> dict[str, Any]:
    path = path.resolve()
    design = load_document(path)
    required = {
        "schema_version", "experiment_id", "datasets", "dataset_configs", "studies",
        "seeds", "workload", "protocol", "resources", "paths",
    }
    missing = sorted(required - design.keys())
    if missing:
        raise ValueError(f"paper configuration is missing: {', '.join(missing)}")
    datasets = tuple(design["datasets"])
    if len(datasets) != len(set(datasets)):
        raise ValueError("datasets must be a unique list")
    if not datasets and not design.get("smoke", False):
        raise ValueError("datasets must be nonempty for a paper run")
    unknown = sorted(set(datasets) - set(SUPPORTED_DATASETS))
    if unknown:
        raise ValueError(f"unsupported datasets: {', '.join(unknown)}")
    if "OL" in datasets:
        raise ValueError("OL is intentionally excluded from this experiment design")
    dataset_definitions: dict[str, dict[str, Any]] = {}
    for dataset in datasets:
        if dataset not in design["dataset_configs"]:
            raise ValueError(f"missing dataset configuration for {dataset}")
        dataset_definitions[dataset] = load_document(repo_path(design["dataset_configs"][dataset]))
    studies: list[dict[str, Any]] = []
    study_ids: set[str] = set()
    for reference in design["studies"]:
        study = load_document(repo_path(reference))
        study_id = study.get("study_id")
        if not isinstance(study_id, str) or study_id in study_ids:
            raise ValueError(f"invalid or duplicate study id in {reference}")
        study_ids.add(study_id)
        studies.append(study)
    expected = {"E01"} if design.get("smoke", False) else {f"E{index:02d}" for index in range(14)}
    if study_ids != expected:
        raise ValueError(f"study set mismatch: expected {sorted(expected)}, got {sorted(study_ids)}")
    effective = dict(design)
    effective["config_path"] = path.relative_to(REPO_ROOT).as_posix()
    effective["dataset_definitions"] = dataset_definitions
    effective["study_definitions"] = sorted(studies, key=lambda item: item["study_id"])
    query_config = effective.get("query_generation", {}).get("configuration")
    if query_config:
        effective["query_generation_config_hash"] = sha256_file(repo_path(query_config))
    effective["config_hash"] = sha256_json({key: value for key, value in effective.items() if key != "config_hash"})
    return effective


def filtered_design(
    design: dict[str, Any], datasets: set[str] | None = None, studies: set[str] | None = None
) -> dict[str, Any]:
    if not datasets and not studies:
        return dict(design)
    result = dict(design)
    if datasets:
        invalid = datasets - set(design["datasets"])
        if invalid:
            raise ValueError(f"dataset filter is not configured: {', '.join(sorted(invalid))}")
        result["datasets"] = [item for item in design["datasets"] if item in datasets]
    if studies:
        known = {item["study_id"] for item in design["study_definitions"]}
        invalid = studies - known
        if invalid:
            raise ValueError(f"study filter is not configured: {', '.join(sorted(invalid))}")
        result["study_definitions"] = [
            item for item in design["study_definitions"] if item["study_id"] in studies
        ]
    result["source_config_hash"] = design["config_hash"]
    result["config_hash"] = sha256_json({key: value for key, value in result.items() if key != "config_hash"})
    return result

"""Load and validate the checked-in Q1 experiment design."""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .hashing import sha256_file, sha256_json


REPO_ROOT = Path(__file__).resolve().parents[3]
SUPPORTED_DATASETS = ("NY", "FLA", "CAL", "USA")
FINAL_ABLATIONS = {
    "full",
    "no-safe-corridor",
    "no-pivot-diversification",
    "fast-only-connector",
    "no-connector-cache",
    "no-score-upper-bound",
    "no-memo",
    "no-frontier-compression",
    "theta0",
    "serial",
}


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


def _validate_final_q1(
    design: dict[str, Any],
    studies: list[dict[str, Any]],
) -> None:
    workload = design["workload"]
    if workload.get("budget_definition") != (
        "GRID_LOWER_BOUND_WITNESS_PATH_TRAVEL_TIME"
    ):
        raise ValueError(
            "final Q1 requires the lower-bound witness-path grid "
            "budget definition"
        )
    if (
        workload.get("default_window_minutes") != 120
        or workload.get("default_budget_overhead") != 0.30
    ):
        raise ValueError(
            "final Q1 defaults require W=120 and rho=0.30"
        )
    protocol = design["protocol"]
    if protocol.get("algorithm_comparison_threads") != 1:
        raise ValueError(
            "algorithmic comparisons require one worker"
        )
    defaults = design.get("pace_b_defaults")
    if not isinstance(defaults, dict):
        raise ValueError("paper configuration is missing pace_b_defaults")
    required_defaults = {
        "theta",
        "score_density",
        "threads",
        "pilot_resolved_fields",
        "connector_expansion_cap_mc",
        "breakpoint_cap_mb",
        "query_work_cap_mq",
        "query_work_accounting_contract",
        "resolved_configuration",
    }
    missing = sorted(required_defaults - defaults.keys())
    if missing:
        raise ValueError(
            "pace_b_defaults is missing: " + ", ".join(missing)
        )
    if (
        defaults["theta"] != 2
        or defaults["score_density"] != 0.20
        or defaults["threads"] != 1
    ):
        raise ValueError(
            "final PACE-B defaults require theta=2, density=0.20, threads=1"
        )
    if set(defaults["pilot_resolved_fields"]) != {
        "pivot_limit_l",
        "connector_limit_kc",
        "frontier_limit_kf",
    }:
        raise ValueError(
            "pilot may resolve only L, K_c, and K_f"
        )
    if defaults["query_work_accounting_contract"] != (
        "PACE-MQ-TOTAL-WORK-v3"
    ):
        raise ValueError(
            "final PACE-B requires PACE-MQ-TOTAL-WORK-v3"
        )
    for cap in (
        "connector_expansion_cap_mc",
        "breakpoint_cap_mb",
        "query_work_cap_mq",
    ):
        if not isinstance(defaults[cap], int) or defaults[cap] < 1:
            raise ValueError(f"{cap} must be a positive fixed safety cap")
    by_id = {study["study_id"]: study for study in studies}
    pilot_axes = by_id["E02"].get("axes", [])
    for axis in pilot_axes:
        if set(axis) != {
            "pivot_limit_l",
            "connector_limit_kc",
            "frontier_limit_kf",
        }:
            raise ValueError(
                "E02 axes must tune exactly L, K_c, and K_f"
            )
    variants = {
        algorithm.get("variant", algorithm["id"])
        for algorithm in by_id["E10"].get("algorithms", [])
    }
    if variants != FINAL_ABLATIONS:
        raise ValueError(
            "E10 ablation set mismatch: "
            f"expected {sorted(FINAL_ABLATIONS)}, got {sorted(variants)}"
        )
    if set(by_id["E12"].get("datasets", [])) != {"NY", "CAL"}:
        raise ValueError("E12 must cover NY and CAL")
    seeds = {
        int(axis["graph_seed"])
        for axis in by_id["E12"].get("axes", [])
    }
    if seeds != {42, 43, 44}:
        raise ValueError("E12 must cover graph seeds 42, 43, and 44")
    maximum_threads = int(
        design["resources"].get("max_threads_per_query", 0)
    )
    parallel_threads = {
        int(axis["threads"])
        for axis in by_id["E11"].get("axes", [])
    }
    if maximum_threads != 24 or parallel_threads != {
        1, 2, 4, 8, 16, 24
    }:
        raise ValueError(
            "E11 must use fixed query-internal thread counts "
            "1,2,4,8,16,24 with a 24-thread maximum"
        )


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
    if not design.get("smoke", False):
        _validate_final_q1(design, studies)
        query_generation = design.get("query_generation")
        if not isinstance(query_generation, dict):
            raise ValueError("paper configuration is missing query_generation")
        query_required = {
            "java_main_class", "paper_java_main_class", "configuration",
            "manifest_pattern", "required_contract",
        }
        query_missing = sorted(query_required - query_generation.keys())
        if query_missing:
            raise ValueError(
                "query_generation is missing: " + ", ".join(query_missing)
            )
    effective = dict(design)
    effective["config_path"] = path.relative_to(REPO_ROOT).as_posix()
    effective["dataset_definitions"] = dataset_definitions
    effective["study_definitions"] = sorted(studies, key=lambda item: item["study_id"])
    query_config = effective.get("query_generation", {}).get("configuration")
    if query_config:
        effective["query_generation_config_hash"] = sha256_file(repo_path(query_config))
    dataset_generation_config = effective.get("dataset_generation", {}).get("configuration")
    if dataset_generation_config:
        effective["dataset_generation_config_hash"] = sha256_file(repo_path(dataset_generation_config))
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

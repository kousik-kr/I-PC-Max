"""Load and validate the checked-in Q1 experiment design."""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .hashing import sha256_file, sha256_json


REPO_ROOT = Path(__file__).resolve().parents[3]
SUPPORTED_DATASETS = ("NY", "FLA", "CAL", "USA", "OL", "NY-EXACT")
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


def algorithm_timeout_seconds(
    design: dict[str, Any],
    algorithm_id: str,
) -> int | None:
    """Return a finite query cap, or None for explicit unlimited wall time.

    Per-method omission inherits the explicitly configured family default.
    Null, zero, booleans and missing family defaults are invalid.
    """
    resources = design["resources"]
    per_algorithm = resources.get("algorithm_timeout_seconds")
    value = (per_algorithm[algorithm_id]
             if isinstance(per_algorithm, dict) and algorithm_id in per_algorithm
             else resources.get("timeout_seconds"))
    if value == "unlimited":
        return None
    if type(value) is not int or value < 1:
        raise ValueError(
            f"{algorithm_id} timeout must be a positive integer or explicit 'unlimited'; got {value!r}"
        )
    return value


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


def _validate_two_track(
    design: dict[str, Any],
    studies: list[dict[str, Any]],
) -> None:
    """Validate the explicit PACE-X oracle/PACE-B scalability protocol."""
    expected_studies = {f"T0{index}" for index in range(1, 7)}
    by_id = {study["study_id"]: study for study in studies}
    if set(by_id) != expected_studies:
        raise ValueError(
            "two_track study set must be T01..T06: "
            f"got {sorted(by_id)}"
        )
    if set(design["datasets"]) != {"NY", "FLA", "CAL", "OL", "NY-EXACT"}:
        raise ValueError(
            "two_track requires NY, FLA, CAL, OL, and NY-EXACT"
        )
    defaults = design.get("pace_b_defaults")
    if not isinstance(defaults, dict):
        raise ValueError("two_track is missing pace_b_defaults")
    expected_defaults = {
        "theta": 2,
        "pivot_limit_l": 4,
        "connector_limit_kc": 4,
        "frontier_limit_kf": 2,
        "connector_expansion_cap_mc": 250000,
        "breakpoint_cap_mb": 100000,
        "query_work_cap_mq": 5000000,
    }
    for name, value in expected_defaults.items():
        if defaults.get(name) != value:
            raise ValueError(
                f"two_track PACE-B default {name} must be {value!r}"
            )
    if design["protocol"].get("algorithm_comparison_threads") != 1:
        raise ValueError("PACE-X/PACE-B comparisons must use one worker")
    if int(design["resources"].get("max_threads_per_query", 0)) != 24:
        raise ValueError("two_track requires a 24-thread query maximum")
    guard = design.get("exact_algorithm_guard")
    if not isinstance(guard, dict):
        raise ValueError("two_track requires an exact_algorithm_guard")
    if set(guard.get("allowed_studies", [])) != {"T01", "T02"}:
        raise ValueError("PACE-X must be limited to T01 and T02")
    if set(guard.get("datasets", [])) != {"NY-EXACT", "NY", "FLA", "CAL", "OL"}:
        raise ValueError("PACE-X guard has an unsafe dataset scope")
    per_study_rho = guard.get("max_budget_overhead_by_study", {})
    if float(per_study_rho.get("T01", 1.0)) > 0.10:
        raise ValueError("NY-Exact PACE-X must be limited to very-low-budget rho<=0.10")
    if float(per_study_rho.get("T02", 1.0)) > 0.10:
        raise ValueError("full-network PACE-X probes must be limited to rho<=0.10")
    if set(guard.get("splits", [])) != {"evaluation"}:
        raise ValueError("PACE-X must use evaluation manifests only")


def _validate_scalability_5s(
    design: dict[str, Any],
    studies: list[dict[str, Any]],
) -> None:
    if set(design["datasets"]) != {"NY", "FLA", "CAL"}:
        raise ValueError("T03 scalability requires exactly NY, FLA, and CAL")
    by_id = {study["study_id"]: study for study in studies}
    if set(by_id) != {"T03"}:
        raise ValueError(
            "five-second scalability is a T03-only continuation; "
            "PACE-X exactness studies must use the unchanged two-track config"
        )
    algorithms = [
        algorithm.get("id")
        for algorithm in by_id["T03"].get("algorithms", [])
    ]
    if algorithms != ["pace-b", "iscope", "allfp"]:
        raise ValueError(
            "T03 scalability requires pace-b, iscope, and allfp in that order"
        )
    resources = design["resources"]
    algorithm_timeouts = resources.get("algorithm_timeout_seconds")
    if not isinstance(algorithm_timeouts, dict):
        raise ValueError("T03 scalability requires per-algorithm timeouts")
    expected_timeouts = {"pace-b": 10, "iscope": 5, "allfp": 10}
    for algorithm, timeout in expected_timeouts.items():
        if int(algorithm_timeouts.get(algorithm, 0)) != timeout:
            raise ValueError(
                f"T03 requires {algorithm} timeout_seconds={timeout}"
            )
    timeout_policies = resources.get("timeout_result_policy")
    if not isinstance(timeout_policies, dict):
        raise ValueError("T03 scalability requires timeout result policies")
    expected_policies = {
        "pace-b": "TIMEOUT_ONLY_EXCLUDE_FROM_COMPARISON",
        "iscope": "ANYTIME_PROFILE_WITHIN_CAP",
        "allfp": "TIMEOUT_ONLY_EXCLUDE_FROM_COMPARISON",
    }
    for algorithm, policy in expected_policies.items():
        if timeout_policies.get(algorithm) != policy:
            raise ValueError(
                f"T03 timeout result policy mismatch for {algorithm}"
            )
    reconciliation = design["reconciliation"]
    if (
        int(reconciliation.get("source_timeout_ms", 0)) != 10_000
        or int(reconciliation.get("new_timeout_ms", 0)) != 10_000
    ):
        raise ValueError(
            "T03 mixed-timeout config must declare the frozen 10s source "
            "and current 10s PACE-B policy"
        )
    if reconciliation.get("pace_b_semantic_version") != design.get(
        "pace_b_defaults", {}
    ).get("semantic_version"):
        raise ValueError("PACE-B reconciliation semantic versions do not match")
    if design.get("paths", {}).get("jar") == "target/pace-bench.jar":
        raise ValueError("T03 continuation must not overwrite the historical live JAR")


def validate_icde2027(design: dict[str, Any], studies: list[dict[str, Any]]) -> None:
    """Validate the new prospective family without changing historic profiles."""
    track = design.get("objective_track")
    allowed = {
        "preference": {"pace-b", "iscope", "sicsp-pref"},
        "fastest_profile": {"thop-partitioned"},
        "tiny_preference_reference": {"pace-x", "pace-b", "iscope", "sicsp-pref"},
    }
    if track not in allowed:
        raise ValueError("unknown ICDE2027 objective track")
    methods = {a["id"] for s in studies for a in s.get("algorithms", [])}
    if methods != allowed[track]:
        raise ValueError(f"objective track {track} requires exactly {sorted(allowed[track])}")
    resources = design["resources"]
    timeouts = resources.get("algorithm_timeout_seconds", {})
    policies = resources.get("timeout_result_policy", {})
    for method in methods:
        if method not in timeouts or not isinstance(timeouts[method], int) or timeouts[method] < 1:
            raise ValueError(f"ICDE2027 requires an explicit positive cap for {method}")
        if method not in policies:
            raise ValueError(f"ICDE2027 requires an explicit timeout policy for {method}")
    if "iscope" in methods and timeouts["iscope"] != 5:
        raise ValueError("ICDE2027 iSCOPE requires the explicit five-second cap")
    if design["protocol"].get("algorithm_comparison_threads") != 1:
        raise ValueError("ICDE2027 primary comparisons require one thread")
    for study in studies:
        for algorithm in study.get("algorithms", []):
            if algorithm.get("parameters", {}).get("threads") != 1:
                raise ValueError("ICDE2027 algorithms must explicitly request one thread")
            if track == "preference" and any(k in algorithm for k in ["axes", "pairs_per_dataset", "datasets"]):
                raise ValueError("preference methods must share the study workload")
    if track == "tiny_preference_reference":
        if set(design["datasets"]) != {"NY-EXACT"}:
            raise ValueError("PACE-X reference is restricted to NY-EXACT")
        if design.get("reference_result_policy") != "GLOBAL_CERTIFIED_ONLY":
            raise ValueError("PACE-X reference quality requires GLOBAL_CERTIFIED_ONLY")
    if not design["paths"]["results_root"].startswith("experiments/results/icde2027_v1/"):
        raise ValueError("prospective results require a new ICDE2027 namespace")
    if design.get("historical_result_reuse") is not False:
        raise ValueError("dirty historical result reuse must be explicitly disabled")


def _validate_icde2027_compact(
    design: dict[str, Any], studies: list[dict[str, Any]]
) -> None:
    by_id = {study["study_id"]: study for study in studies}
    if set(by_id) != {"C01", "C02"}:
        raise ValueError("compact family requires the C01 and C02 manifest studies")
    primary, controlled = by_id["C01"], by_id["C02"]
    if set(design["datasets"]) != {"NY", "FLA", "CAL", "NY-EXACT"}:
        raise ValueError("compact family requires NY, FLA, CAL, and NY-EXACT")
    if {item["id"] for item in primary.get("algorithms", [])} != {
        "pace-b", "iscope-optimized", "iscope-basic", "thop-partitioned"
    }:
        raise ValueError("C01 must compare PACE-B, optimized/basic iSCOPE, and THop")
    if {item["id"] for item in controlled.get("algorithms", [])} != {
        "pace-x", "pace-b", "iscope-basic", "sicsp-pref"
    }:
        raise ValueError("C02 must compare all four preference methods")
    if set(primary.get("datasets", [])) != {"NY", "FLA", "CAL"}:
        raise ValueError("C01 must use the three compact historical datasets")
    if set(controlled.get("datasets", [])) != {"NY-EXACT"}:
        raise ValueError("C02 must use the actual NY-EXACT dataset")
    if primary.get("query_manifest_pattern") != (
        "experiments/manifests/icde2027_compact_v1/queries/{dataset}/compact_50.jsonl"
    ):
        raise ValueError("C01 must use the frozen compact_50 manifests")
    if controlled.get("query_manifest_pattern") != (
        "experiments/manifests/icde2027_compact_v1/queries/NY-EXACT/controlled_20.jsonl"
    ):
        raise ValueError("C02 must use the frozen NY-EXACT controlled manifest")
    if primary.get("trials") != 2 or controlled.get("trials") != 1:
        raise ValueError("compact trials must be two primary and one controlled")
    resources = design["resources"]
    expected_timeouts = {
        "pace-b": "unlimited", "iscope": 5,
        "iscope-basic": 5, "iscope-opt1": 5, "iscope-optimized": 5,
        "thop-partitioned": "unlimited", "pace-x": "unlimited",
        "sicsp-pref": "unlimited",
    }
    if resources.get("algorithm_timeout_seconds") != expected_timeouts:
        raise ValueError("compact family must declare its method-specific timeout policy")
    if resources.get("timeout_seconds") != "unlimited":
        raise ValueError("compact family must use explicit unlimited as the base policy")
    for method in expected_timeouts:
        algorithm_timeout_seconds(design, method)
    policies = resources.get("timeout_result_policy", {})
    expected_policies = {
        "pace-b": "NO_WALL_TIMEOUT_REPORT_INTRINSIC_WORK_CAPS",
        "iscope": "ANYTIME_PROFILE_WITHIN_CAP_NOT_CERTIFIED",
        "iscope-basic": "ANYTIME_PROFILE_WITHIN_CAP_NOT_CERTIFIED",
        "iscope-opt1": "EXACT_PARTIAL_FUNCTION_PRUNED_PROFILE",
        "iscope-optimized": "APPROXIMATE_SEARCH_EXACT_RETAINED_ROUTE_PROFILE_NOT_CERTIFIED",
        "thop-partitioned": "NO_WALL_TIMEOUT_REPORT_RESOURCE_FAILURES",
        "pace-x": "GLOBAL_CERTIFICATE_AND_ORACLE_REQUIRED",
        "sicsp-pref": "CONTROLLED_ONLY_LARGE_NETWORK_SCOPE_PILOT_DEPENDENT",
    }
    if policies != expected_policies:
        raise ValueError("compact timeout-result policy must cover every method")
    if design["protocol"].get("algorithm_comparison_threads") != 1:
        raise ValueError("compact algorithm comparisons require one thread")
    if any(
        algorithm.get("parameters", {}).get("threads") != 1
        for study in studies for algorithm in study.get("algorithms", [])
    ):
        raise ValueError("compact algorithm entries must explicitly request one thread")
    if not any(item["id"] == "iscope-optimized" for item in primary["algorithms"]):
        raise ValueError("C01 primary iSCOPE method must explicitly use iscope-optimized")
    optimized = next(item for item in primary["algorithms"] if item["id"] == "iscope-optimized")
    if optimized.get("parameters", {}).get("iscope_opt2_breakpoint_threshold_minutes") != 2:
        raise ValueError("C01 must freeze the documented Opt2 breakpoint threshold")
    if design.get("historical_result_reuse") is not False:
        raise ValueError("compact family must disable historical result reuse")
    if design["paths"]["results_root"] != "experiments/results/icde2027_compact_v1" and not design["paths"]["results_root"].startswith(
        "experiments/results/icde2027_compact_v1/"
    ):
        raise ValueError("compact results require an isolated namespace")
    if design.get("reference_result_policy") != (
        "GLOBAL_CERTIFIED_ONLY_AND_INDEPENDENTLY_VALIDATED"
    ):
        raise ValueError("PACE-X reference results require both gates")
    if design.get("exact_algorithm_guard", {}).get("allowed_studies") != ["C02"]:
        raise ValueError("PACE-X must be restricted to the controlled study")
    if not design["protocol"].get("shared_preprocessing"):
        raise ValueError("compact no-cap runner requires shared-preprocessing local execution")
    if int(resources.get("memory_limit_mb", 0)) < 256:
        raise ValueError("compact methods retain the configured finite memory cap")
    for study_id, pattern, count, sidecar_name in (
        ("C01", primary["query_manifest_pattern"], 50, "manifest.json"),
        ("C02", controlled["query_manifest_pattern"], 20, "controlled_manifest.json"),
    ):
        datasets = primary["datasets"] if study_id == "C01" else controlled["datasets"]
        for dataset in datasets:
            query_path = repo_path(pattern.format(dataset=dataset))
            sidecar = load_document(query_path.parent / sidecar_name)
            expected_hash_key = (
                "compact_query_sha256" if study_id == "C01"
                else "controlled_query_sha256"
            )
            expected_count_key = (
                "compact_query_count" if study_id == "C01"
                else "controlled_query_count"
            )
            if sha256_file(query_path) != sidecar.get(expected_hash_key):
                raise ValueError(f"frozen compact query hash mismatch: {query_path}")
            rows = [
                json.loads(line)
                for line in query_path.read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
            if len(rows) != count or len(rows) != sidecar.get(expected_count_key):
                raise ValueError(f"frozen compact query count mismatch: {query_path}")
            if any(
                row.get("window_length") != 120
                or row.get("budget_slack") != 0.3
                or row.get("metadata", {}).get("split") != "evaluation"
                for row in rows
            ):
                raise ValueError(f"compact rows do not match the declared default workload: {query_path}")
            if study_id == "C01":
                pairs: dict[str, set[tuple[int, int]]] = {}
                bands: dict[int, set[str]] = {}
                for row in rows:
                    metadata = row["metadata"]
                    pairs.setdefault(metadata["pair_id"], set()).add(
                        (metadata["time_center"], row["distance_bin"])
                    )
                    bands.setdefault(row["distance_bin"], set()).add(metadata["pair_id"])
                if len(pairs) != 25 or any(
                    len(values) != 2 or {center for center, _ in values} != {510, 1110}
                    for values in pairs.values()
                ):
                    raise ValueError(f"compact rows must be 25 original pairs at both centers: {query_path}")
                if set(bands) != {1, 2, 3, 4, 5} or any(
                    len(pair_ids) != 5 for pair_ids in bands.values()
                ):
                    raise ValueError(f"compact rows must select five pairs per distance band: {query_path}")


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
    profile = design.get("profile")
    if design.get("smoke", False):
        expected = {"E01"}
    elif profile == "scalability_pilot":
        # The scalability plan is intentionally opt-in and is not the frozen
        # paper E00-E13 matrix.  Its study set is still checked for unique,
        # declarative IDs below; the paper-only gates must not rewrite it.
        expected = set(study_ids)
        if not expected:
            raise ValueError("scalability_pilot requires at least one study")
    elif profile == "two_track":
        expected = {f"T0{index}" for index in range(1, 7)}
    elif profile == "scalability_5s":
        expected = {"T03"}
    elif profile == "icde2027_v1":
        expected = set(study_ids)
        if not expected:
            raise ValueError("ICDE2027 requires explicit objective-specific studies")
    elif profile == "icde2027_compact_v1":
        expected = {"C01", "C02"}
    else:
        expected = {f"E{index:02d}" for index in range(14)}
    if study_ids != expected:
        raise ValueError(f"study set mismatch: expected {sorted(expected)}, got {sorted(study_ids)}")
    if not design.get("smoke", False):
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
    if not design.get("smoke", False) and profile == "icde2027_v1":
        validate_icde2027(design, studies)
    elif not design.get("smoke", False) and profile == "icde2027_compact_v1":
        _validate_icde2027_compact(design, studies)
    elif not design.get("smoke", False) and profile == "scalability_5s":
        _validate_scalability_5s(design, studies)
    elif not design.get("smoke", False) and profile == "two_track":
        _validate_two_track(design, studies)
    elif not design.get("smoke", False) and profile != "scalability_pilot":
        _validate_final_q1(design, studies)
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

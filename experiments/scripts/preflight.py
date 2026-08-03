#!/usr/bin/env python3
"""Read-only dataset, configuration, environment, and horizon preflight."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
import sys
from typing import Any
import xml.etree.ElementTree as ET

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_json
from experiments.scripts.common.config import (
    filtered_design,
    load_design,
    load_document,
    repo_path,
)
from experiments.scripts.common.hashing import graph_checksum
from experiments.scripts.common.provenance import host_environment
from experiments.scripts.generate_dataset_assets import validate_assets
from experiments.scripts.generate_queries import validate_all


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


def _name_payload_checksum_scopes(value: Any) -> Any:
    """Replace legacy ambiguous asset-validator checksum keys recursively."""
    if isinstance(value, list):
        return [_name_payload_checksum_scopes(item) for item in value]
    if not isinstance(value, dict):
        return value
    result = {
        key: _name_payload_checksum_scopes(item)
        for key, item in value.items()
        if key != "graph_checksum"
    }
    if "graph_checksum" in value:
        result.setdefault(
            "dataset_payload_checksum",
            value["graph_checksum"],
        )
        result.setdefault(
            "checksum_scope_version",
            "pace-explicit-dataset-checksum-scopes-v1",
        )
    return result


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
        "dataset_payload_checksum": None,
        "checksum_scope_version":
            "pace-explicit-dataset-checksum-scopes-v1",
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
    expected_nodes = config.get("expected_nodes")
    expected_edges = config.get("expected_edges")
    if expected_nodes is None and not isinstance(record["nodes"], int):
        errors.append("manifest does not declare a valid node count")
    if expected_edges is None and not isinstance(record["edges"], int):
        errors.append("manifest does not declare a valid directed arc count")
    if expected_nodes is not None and record["nodes"] != expected_nodes:
        errors.append(f"node count {record['nodes']} != expected {expected_nodes}")
    if expected_edges is not None and record["edges"] != expected_edges:
        errors.append(f"edge count {record['edges']} != expected {expected_edges}")
    required_support = config.get("required_support_end")
    if support_end is None:
        errors.append("manifest does not declare a machine-readable temporal support end")
    elif required_support is not None and support_end < required_support:
        errors.append(
            f"function support ends at {support_end}; paper design requires at least {required_support}"
        )
    required_contract = config.get("required_conversion_contract")
    declared_contract = manifest.get("conversion_contract", {}).get("contract_id")
    record["conversion_contract"] = declared_contract
    if required_contract is not None and declared_contract != required_contract:
        errors.append(
            f"conversion contract {declared_contract!r} != expected {required_contract!r}"
        )
    if checksums:
        record["dataset_payload_checksum"] = graph_checksum(
            directory, REQUIRED_GRAPH_FILES
        )
    variants: list[dict[str, Any]] = []
    for percent in config.get("required_score_density_percent", []):
        variant = directory / "variants" / f"score-density-{int(percent):03d}"
        present = all((variant / name).is_file() for name in REQUIRED_GRAPH_FILES)
        variant_record: dict[str, Any] = {
            "kind": "score_density",
            "value": percent,
            "path": variant.as_posix(),
            "present": present,
        }
        variants.append(variant_record)
        if not present:
            errors.append(f"required score-density variant is missing: {percent}%")
            continue
        variant_manifest = json.loads((variant / "manifest.json").read_text(encoding="utf-8"))
        variant_record["nodes"] = variant_manifest.get("num_nodes")
        variant_record["edges"] = variant_manifest.get("num_arcs")
        variant_record["support_end"] = _support_end(variant_manifest)
        variant_record["score_density"] = variant_manifest.get("score_edge_fraction")
        variant_record["conversion_contract"] = (
            variant_manifest.get("conversion_contract", {}).get("contract_id")
        )
        if expected_nodes is not None and variant_record["nodes"] != expected_nodes:
            errors.append(
                f"score-density {percent}% variant node count "
                f"{variant_record['nodes']} != expected {expected_nodes}"
            )
        if expected_edges is not None and variant_record["edges"] != expected_edges:
            errors.append(
                f"score-density {percent}% variant edge count "
                f"{variant_record['edges']} != expected {expected_edges}"
            )
        if variant_record["support_end"] is None or (
            required_support is not None
            and variant_record["support_end"] < required_support
        ):
            errors.append(
                f"score-density {percent}% variant function support ends at "
                f"{variant_record['support_end']}; requires at least {required_support}"
            )
        expected_density = float(percent) / 100.0
        if variant_record["score_density"] != expected_density:
            errors.append(
                f"score-density {percent}% variant declares "
                f"{variant_record['score_density']!r}; expected {expected_density}"
            )
        if required_contract is not None and variant_record["conversion_contract"] != required_contract:
            errors.append(
                f"score-density {percent}% variant conversion contract "
                f"{variant_record['conversion_contract']!r} != expected {required_contract!r}"
            )
        if checksums:
            variant_record["dataset_payload_checksum"] = graph_checksum(
                variant, REQUIRED_GRAPH_FILES
            )
            variant_record["checksum_scope_version"] = (
                "pace-explicit-dataset-checksum-scopes-v1"
            )
    for seed in config.get("required_graph_seeds", []):
        if seed == manifest.get("seed"):
            variants.append({
                "kind": "graph_seed",
                "value": seed,
                "path": directory.as_posix(),
                "present": True,
                "nodes": record["nodes"],
                "edges": record["edges"],
                "support_end": support_end,
                "dataset_payload_checksum":
                    record["dataset_payload_checksum"],
                "checksum_scope_version":
                    "pace-explicit-dataset-checksum-scopes-v1",
            })
            continue
        variant = directory / "variants" / f"seed-{int(seed)}"
        present = all((variant / name).is_file() for name in REQUIRED_GRAPH_FILES)
        variant_record = {
            "kind": "graph_seed",
            "value": seed,
            "path": variant.as_posix(),
            "present": present,
        }
        variants.append(variant_record)
        if not present:
            errors.append(f"required graph-seed variant is missing: {seed}")
            continue
        variant_manifest = json.loads((variant / "manifest.json").read_text(encoding="utf-8"))
        variant_record["nodes"] = variant_manifest.get("num_nodes")
        variant_record["edges"] = variant_manifest.get("num_arcs")
        variant_record["support_end"] = _support_end(variant_manifest)
        variant_record["graph_seed"] = variant_manifest.get("seed")
        variant_record["conversion_contract"] = (
            variant_manifest.get("conversion_contract", {}).get("contract_id")
        )
        if expected_nodes is not None and variant_record["nodes"] != expected_nodes:
            errors.append(
                f"graph-seed {seed} variant node count "
                f"{variant_record['nodes']} != expected {expected_nodes}"
            )
        if expected_edges is not None and variant_record["edges"] != expected_edges:
            errors.append(
                f"graph-seed {seed} variant edge count "
                f"{variant_record['edges']} != expected {expected_edges}"
            )
        if variant_record["support_end"] is None or (
            required_support is not None
            and variant_record["support_end"] < required_support
        ):
            errors.append(
                f"graph-seed {seed} variant function support ends at "
                f"{variant_record['support_end']}; requires at least {required_support}"
            )
        if variant_record["graph_seed"] != seed:
            errors.append(
                f"graph-seed {seed} variant declares seed "
                f"{variant_record['graph_seed']!r}"
            )
        if required_contract is not None and variant_record["conversion_contract"] != required_contract:
            errors.append(
                f"graph-seed {seed} variant conversion contract "
                f"{variant_record['conversion_contract']!r} != expected {required_contract!r}"
            )
        if checksums:
            variant_record["dataset_payload_checksum"] = graph_checksum(
                variant, REQUIRED_GRAPH_FILES
            )
            variant_record["checksum_scope_version"] = (
                "pace-explicit-dataset-checksum-scopes-v1"
            )
    record["variants"] = variants
    record["errors"] = errors
    return record


def _surefire_case_passed(class_name: str, case_name: str) -> tuple[bool, str]:
    report = repo_path("target/surefire-reports") / f"TEST-{class_name}.xml"
    if not report.is_file():
        return False, f"missing Maven Surefire report: {report}"
    try:
        root = ET.parse(report).getroot()
    except ET.ParseError as failure:
        return False, f"invalid Maven Surefire report {report}: {failure}"
    for element in root.iter():
        if not element.tag.endswith("testcase") or element.get("name") != case_name:
            continue
        problems = [
            child.tag.rsplit("}", 1)[-1]
            for child in list(element)
            if child.tag.rsplit("}", 1)[-1] in {"failure", "error", "skipped"}
        ]
        if problems:
            return False, f"{class_name}.{case_name} has {', '.join(problems)}"
        return True, f"{class_name}.{case_name}"
    return False, f"missing testcase evidence: {class_name}.{case_name}"


def _source_contains(path: str, pattern: str, description: str) -> tuple[bool, str]:
    source = repo_path(path)
    if not source.is_file():
        return False, f"missing source evidence: {source}"
    if re.search(pattern, source.read_text(encoding="utf-8"), re.MULTILINE):
        return True, description
    return False, f"source evidence not found in {source}: {description}"


def inspect_implementation_gates(design: dict[str, Any]) -> dict[str, Any]:
    checks = {
        "exactness_corpus_has_1000_seeded_graphs": [
            lambda: _source_contains(
                "src/test/java/edu/ipcmax/testoracle/PaceExactOracleDifferentialTest.java",
                r"SEEDED_CORPUS_CASES\s*=\s*1000\b",
                "seeded exactness corpus size is 1000",
            ),
            lambda: _surefire_case_passed(
                "edu.ipcmax.testoracle.PaceExactOracleDifferentialTest",
                "fixedSeedTinyFifoDagsWithParallelArcsMatchCompletePaceXEnvelopes",
            ),
        ],
        "pace_x_uncompressed_variant_available": [
            lambda: _surefire_case_passed(
                "edu.ipcmax.testoracle.PaceExactOracleDifferentialTest",
                "uncompressedPaceXMatchesCompressedPaceXOnSeededCorpus",
            ),
        ],
        "pace_parallelism_observed_and_checksum_stable": [
            lambda: _surefire_case_passed(
                "edu.ipcmax.core.pcmax.PacePublicApiOracleIntegrationTest",
                "parallelConfigurationStartsParallelTasksAndKeepsOutputChecksumStable",
            ),
        ],
        "incremental_frontier_differential_zero_mismatches": [
            lambda: _source_contains(
                "src/test/java/edu/ipcmax/core/pcmax/"
                "IncrementalFrontierDifferentialTest.java",
                r"comparisons\s*>=\s*2_000",
                "incremental differential corpus requires at least "
                "2,000 insertion-prefix comparisons",
            ),
            lambda: _surefire_case_passed(
                "edu.ipcmax.core.pcmax."
                "IncrementalFrontierDifferentialTest",
                "deterministicRandomizedCorpusMatchesAfterEveryInsertion",
            ),
        ],
        "mq_total_work_v3_accounting": [
            lambda: _source_contains(
                "src/main/java/edu/ipcmax/core/pcmax/"
                "PaceWorkLedger.java",
                r'ACCOUNTING_CONTRACT\s*=\s*'
                r'"PACE-MQ-TOTAL-WORK-v3"',
                "M_q uses PACE-MQ-TOTAL-WORK-v3",
            ),
            lambda: _source_contains(
                "src/main/java/edu/ipcmax/core/pcmax/"
                "PaceWorkKind.java",
                r"PIVOT_TASK_ADMISSION[\s\S]*"
                r"TEMPORAL_COMPOSITION[\s\S]*"
                r"PROFILE_MERGE[\s\S]*"
                r"RETENTION_EVALUATION[\s\S]*"
                r"FRAGMENT_RESTRICTION[\s\S]*"
                r"FRAGMENT_MATERIALIZATION",
                "M_q has typed retention/restriction/"
                "materialization units",
            ),
        ],
        "relative_score_gap_metric_available_for_pilot": [
            lambda: _source_contains(
                "src/main/java/edu/ipcmax/experiments/framework/ProfileSupport.java",
                r"relative_score_gap_percent",
                "ProfileSupport emits relative_score_gap_percent",
            ),
            lambda: _surefire_case_passed(
                "edu.ipcmax.experiments.framework.AlgorithmResultExactnessTest",
                "profileQualityIncludesRelativeScoreGapPercent",
            ),
        ],
        "safe_corridor_property_verified": [
            lambda: _surefire_case_passed(
                "edu.ipcmax.core.pcmax.QueryCorridorPropertyTest",
                "everyArcOnEveryLowerBoundFeasibleSimplePathSurvives",
            ),
        ],
        "score_upper_bound_admissible": [
            lambda: _surefire_case_passed(
                "edu.ipcmax.core.pcmax.ScalablePaceCandidateEngineTest",
                "relaxedScoreRateBoundIsHandComputableAndAdmissible",
            ),
        ],
    }
    records = []
    blockers = []
    for gate, state in design.get("implementation_gates", {}).items():
        evidence = []
        errors = []
        if state is not True:
            errors.append("gate is not enabled in configuration")
        for check in checks.get(gate, []):
            passed, detail = check()
            evidence.append({"passed": passed, "detail": detail})
            if not passed:
                errors.append(detail)
        if gate not in checks:
            errors.append("no preflight evidence checker is registered for this gate")
        if errors:
            blockers.append(f"implementation gate is unresolved: {gate}: " + "; ".join(errors))
        records.append({
            "gate": gate,
            "enabled": state is True,
            "evidence": evidence,
            "errors": errors,
        })
    return {"gates": records, "passed": not blockers, "blockers": blockers}


def run_preflight(
    config_path: Path,
    checksums: bool = True,
    allow_unresolved_resources: bool = False,
    planning: bool = False,
    datasets_filter: set[str] | None = None,
) -> dict[str, Any]:
    design = load_design(config_path)
    if datasets_filter:
        design = filtered_design(design, datasets_filter)
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
    asset_integrity = (
        {
            "datasets": [],
            "variants": [],
            "errors": [],
            "passed": True,
            "skipped":
                "plan-only performs manifest-level checks; "
                "paper-preflight performs deep payload validation",
        }
        if planning
        else
        {"datasets": [], "variants": [], "errors": [], "passed": True,
         "skipped": "smoke design"}
        if not design["datasets"]
        else _name_payload_checksum_scopes(
            validate_assets(
                design,
                load_document(repo_path(
                    design["dataset_generation"]["configuration"]
                )),
            )
        )
    )
    blockers.extend(
        f"dataset integrity: {message}"
        for message in asset_integrity.get("errors", [])
    )
    queries = (
        {"datasets": [], "passed": True, "skipped": "smoke design"}
        if design.get("smoke")
        else validate_all(design)
    )
    for item in queries["datasets"]:
        blockers.extend(
            f"{item['dataset_id']} queries: {message}"
            for message in item.get("errors", [])
        )
    resources = dict(design["resources"])
    if resources.get("require_server_profile_for_full_run") and resources.get("memory_limit_mb") is None:
        message = "full-run memory_limit_mb is unresolved; provide the target server profile"
        if allow_unresolved_resources:
            resources["warning"] = message
        else:
            blockers.append(message)
    gate_report = inspect_implementation_gates(design)
    blockers.extend(gate_report["blockers"])
    jar = repo_path(design["paths"]["jar"])
    report = {
        "schema_version": 1,
        "mode": "plan-only" if planning else "deep",
        "config_hash": design["config_hash"],
        "experiment_id": design["experiment_id"],
        "ol_included": "OL" in design["datasets"],
        "environment": environment,
        "resources": resources,
        "jar_present": jar.is_file(),
        "datasets": datasets,
        "dataset_integrity": asset_integrity,
        "queries": queries,
        "implementation_gates": gate_report,
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
    parser.add_argument("--dataset", action="append")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = run_preflight(
            args.config,
            not args.skip_checksums,
            args.allow_unresolved_resources,
            datasets_filter=set(args.dataset or []) or None,
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

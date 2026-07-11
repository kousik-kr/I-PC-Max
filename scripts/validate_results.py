#!/usr/bin/env python3
"""Validate raw PACE JSONL records and cross-run experiment invariants."""
from __future__ import annotations

import argparse
import collections
import json
import math
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
REQUIRED_TOP = {"schema_version", "run", "system", "dataset", "query", "configuration", "status", "timing_ns", "memory_bytes", "counters", "output", "quality", "error"}
STATUSES = {"COMPLETED", "NO_FEASIBLE_PATH", "TIMEOUT", "OUT_OF_MEMORY", "LIMIT_EXCEEDED", "FUNCTION_HORIZON_EXCEEDED", "INVALID_QUERY", "INVALID_CONFIGURATION", "ERROR"}


def paths(inputs: list[Path]) -> list[Path]:
    result: list[Path] = []
    for item in inputs:
        result.extend(sorted(item.glob("*.jsonl")) if item.is_dir() else [item])
    return result


def finite(value: object) -> bool:
    return not isinstance(value, float) or math.isfinite(value)


def builtin_schema_errors(instance: object, schema: dict, root: dict, location: str = "$") -> list[str]:
    """Validate the schema features used by the checked-in PACE schemas without dependencies."""
    if "$ref" in schema:
        target: object = root
        for part in schema["$ref"].removeprefix("#/").split("/"):
            target = target[part]  # type: ignore[index]
        return builtin_schema_errors(instance, target, root, location)  # type: ignore[arg-type]
    errors: list[str] = []
    if "const" in schema and instance != schema["const"]:
        errors.append(f"{location}: expected constant {schema['const']!r}")
    if "enum" in schema and instance not in schema["enum"]:
        errors.append(f"{location}: value {instance!r} is not in the allowed enum")
    expected = schema.get("type")
    expected_types = expected if isinstance(expected, list) else [expected] if expected else []
    type_matches = {
        "object": lambda value: isinstance(value, dict),
        "array": lambda value: isinstance(value, list),
        "string": lambda value: isinstance(value, str),
        "integer": lambda value: isinstance(value, int) and not isinstance(value, bool),
        "number": lambda value: isinstance(value, (int, float)) and not isinstance(value, bool),
        "boolean": lambda value: isinstance(value, bool),
        "null": lambda value: value is None,
    }
    if expected_types and not any(type_matches[name](instance) for name in expected_types):
        return [f"{location}: expected type {expected_types}, got {type(instance).__name__}"]
    if isinstance(instance, (int, float)) and not isinstance(instance, bool):
        if "minimum" in schema and instance < schema["minimum"]:
            errors.append(f"{location}: value is below minimum {schema['minimum']}")
    if isinstance(instance, str) and len(instance) < schema.get("minLength", 0):
        errors.append(f"{location}: string is shorter than minLength")
    if isinstance(instance, dict):
        required = set(schema.get("required", []))
        missing = required - instance.keys()
        if missing:
            errors.append(f"{location}: missing required properties {sorted(missing)}")
        properties = schema.get("properties", {})
        additional = schema.get("additionalProperties", True)
        for key, value in instance.items():
            child = properties.get(key)
            if child is None:
                if additional is False:
                    errors.append(f"{location}: unexpected property {key!r}")
                elif isinstance(additional, dict):
                    child = additional
            if child is not None:
                errors.extend(builtin_schema_errors(value, child, root, f"{location}.{key}"))
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--schema", type=Path, default=ROOT / "experiments/schemas/result_record.schema.json")
    parser.add_argument("--expected-manifest", type=Path)
    args = parser.parse_args()
    errors: list[str] = []
    records: list[dict] = []
    run_ids: set[str] = set()
    schema = json.loads(args.schema.read_text(encoding="utf-8"))
    validator = None
    try:
        import jsonschema  # type: ignore
        validator = jsonschema.Draft202012Validator(schema)
    except ImportError:
        pass
    for path in paths(args.inputs):
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line, parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))
            except Exception as exc:
                errors.append(f"{path}:{line_number}: malformed JSON: {exc}")
                continue
            missing = REQUIRED_TOP - record.keys()
            if missing:
                errors.append(f"{path}:{line_number}: missing top-level fields {sorted(missing)}")
            if validator:
                for error in validator.iter_errors(record):
                    errors.append(f"{path}:{line_number}: schema: {error.message}")
            else:
                for error in builtin_schema_errors(record, schema, schema):
                    errors.append(f"{path}:{line_number}: schema: {error}")
            run_id = record.get("run", {}).get("run_id")
            if not run_id or run_id in run_ids:
                errors.append(f"{path}:{line_number}: missing or duplicate run_id {run_id!r}")
            run_ids.add(run_id)
            status = record.get("status", {})
            code = status.get("status_code")
            if code not in STATUSES:
                errors.append(f"{path}:{line_number}: invalid status {code!r}")
            if code == "NO_FEASIBLE_PATH" and not status.get("completed"):
                errors.append(f"{path}:{line_number}: NO_FEASIBLE_PATH must be completed")
            if any(not finite(value) for section in record.values() if isinstance(section, dict) for value in section.values()):
                errors.append(f"{path}:{line_number}: non-finite numeric value")
            records.append(record)
    deterministic: dict[tuple, set] = collections.defaultdict(set)
    exact: dict[str, dict[str, str]] = collections.defaultdict(dict)
    thread_checks: dict[tuple, set] = collections.defaultdict(set)
    configurations_by_hash: dict[str, str] = {}
    combinations: set[tuple[str, str, str]] = set()
    for record in records:
        if record["run"].get("warmup"):
            continue
        query = record["query"]["query_id"]
        config = record["configuration"]
        config_hash = record["run"].get("config_hash")
        canonical_config = json.dumps(config, sort_keys=True, separators=(",", ":"))
        previous_config = configurations_by_hash.setdefault(config_hash, canonical_config)
        if previous_config != canonical_config:
            errors.append(f"inconsistent effective configuration for config_hash {config_hash}")
        checksum = record["output"].get("profile_checksum")
        method = config["algorithm"]
        status = record["status"]
        expected_completed = status["status_code"] in {"COMPLETED", "NO_FEASIBLE_PATH"}
        if status.get("completed") != expected_completed:
            errors.append(f"invalid completed flag: {query}/{method}")
        if method not in {"pace-x", "pace-b"} and any(config.get(name) is not None for name in ("theta", "anchor_limit", "k")):
            errors.append(f"non-PACE parameters must be null: {query}/{method}")
        if method == "rpq" and not config.get("rpq_step_minutes"):
            errors.append(f"RPQ step missing or zero: {query}")
        if method != "rpq" and config.get("rpq_step_minutes") is not None:
            errors.append(f"RPQ step must be null for {method}: {query}")
        if method == "ksp-profile" and not config.get("baseline_k"):
            errors.append(f"KSP k missing or zero: {query}")
        if method != "ksp-profile" and config.get("baseline_k") is not None:
            errors.append(f"KSP k must be null for {method}: {query}")
        combinations.add((query, method, config["ablation"]))
        if config.get("deterministic") and checksum:
            key = (query, method, config["ablation"], record["run"]["config_hash"])
            deterministic[key].add(checksum)
        if method in {"pace-x", "exh-profile", "pl-exact"} and checksum:
            exact[query][method] = checksum
        if method == "pace-b" and checksum:
            key = (query, config["ablation"], config.get("theta"), config.get("anchor_limit"), config.get("k"))
            thread_checks[key].add(checksum)
    for key, checksums in deterministic.items():
        if len(checksums) > 1:
            errors.append(f"determinism checksum mismatch: {key}")
    for query, methods in exact.items():
        if "pace-x" in methods and "exh-profile" in methods and methods["pace-x"] != methods["exh-profile"]:
            errors.append(f"PACE-X versus EXH exactness discrepancy: {query}")
        if "pl-exact" in methods and "exh-profile" in methods and methods["pl-exact"] != methods["exh-profile"]:
            errors.append(f"PL-Exact versus EXH discrepancy: {query}")
    for key, checksums in thread_checks.items():
        if len(checksums) > 1:
            errors.append(f"thread-count determinism discrepancy: {key}")
    if args.expected_manifest:
        expected = {json.loads(line)["query_id"] for line in args.expected_manifest.read_text(encoding="utf-8").splitlines() if line.strip()}
        methods = {(record["configuration"]["algorithm"], record["configuration"]["ablation"]) for record in records if not record["run"].get("warmup")}
        for query in expected:
            for method, ablation in methods:
                if (query, method, ablation) not in combinations:
                    errors.append(f"missing query-method combination: {query}/{method}/{ablation}")
    for error in errors:
        print(error, file=sys.stderr)
    print(json.dumps({"records": len(records), "errors": len(errors),
                      "schema_validator": "jsonschema" if validator else "builtin"}))
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())

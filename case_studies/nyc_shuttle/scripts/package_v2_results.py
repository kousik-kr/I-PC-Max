#!/usr/bin/env python3
"""Package NYC-real-shuttle-v2 without writing to the V1 bundle."""

from __future__ import annotations

import hashlib
import json
import shutil
import sys
from pathlib import Path
from typing import Any

from nyc_case_study.common import CASE_ROOT, atomic_write_json, utc_now


REPOSITORY = CASE_ROOT.parents[1]
SOURCE = CASE_ROOT / "v2"
DESTINATION = (REPOSITORY
               / "experiments/results/Final-result/NYC-real-shuttle-v2")
V1 = REPOSITORY / "experiments/results/Final-result/NYC-real-shuttle"
V1_EXPECTED_RESULT = (
    "c0427dc91d2e7195e847436841f0faf1463311ee2fc4cabb7208c2cada16045d")
V2_JAR = REPOSITORY / "target/pace-bench.jar"
V1_JAR = Path("/tmp/nyc-v1-connector-runner.jar")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1 << 20):
            digest.update(block)
    return digest.hexdigest()


def content_tree(path: Path) -> str:
    digest = hashlib.sha256()
    for item in sorted(value for value in path.rglob("*") if value.is_file()):
        relative = item.relative_to(path).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(8, "big"))
        digest.update(relative)
        with item.open("rb") as stream:
            while block := stream.read(1 << 20):
                digest.update(block)
    return digest.hexdigest()


def verify_v1() -> dict[str, Any]:
    manifest = json.loads((V1 / "artifact_manifest.json").read_text(
        encoding="utf-8"))
    mismatches = []
    for record in manifest["artifacts"]:
        artifact = V1 / record["path"]
        if (not artifact.is_file()
                or artifact.stat().st_size != record["size_bytes"]
                or sha256(artifact) != record["sha256"]):
            mismatches.append(record["path"])
    official = V1 / "results/nyc_case_results.jsonl"
    proof = {
        "schema_version": "nyc-v1-preservation-proof-v1",
        "v1_path": str(V1.relative_to(REPOSITORY)),
        "v1_file_count": sum(item.is_file() for item in V1.rglob("*")),
        "v1_content_tree_sha256": content_tree(V1),
        "v1_artifact_manifest_sha256": sha256(
            V1 / "artifact_manifest.json"),
        "v1_declared_artifact_count": manifest["artifact_count"],
        "v1_declared_artifacts_verified": len(manifest["artifacts"]),
        "v1_declared_artifact_mismatches": mismatches,
        "v1_official_result_sha256": sha256(official),
        "v1_expected_result_sha256": V1_EXPECTED_RESULT,
        "v1_preservation_pass": (
            not mismatches and sha256(official) == V1_EXPECTED_RESULT),
    }
    if not proof["v1_preservation_pass"]:
        raise RuntimeError("V1 bundle preservation verification failed")
    return proof


def execution_protocol() -> dict[str, Any]:
    common = (
        "--theta 2 --pivot-limit-l 32 --frontier-limit-kf 16 "
        "--breakpoint-cap-mb 1000000 --threads 1 --baseline-threads 8")
    return {
        "schema_version": "nyc-v2-execution-protocol-v1",
        "v2_runner_sha256": sha256(V2_JAR),
        "preserved_v1_control_runner_sha256": sha256(V1_JAR),
        "primary_command": (
            "java -Xmx48g -cp pace-bench-v2.jar "
            "edu.ipcmax.casestudy.nyc.NycShuttleCaseStudyBench "
            "--dataset processed/NYC-REAL-V2 "
            "--query-file manifests/nyc_queries.jsonl "
            "--output results/primary_5s.jsonl "
            f"{common} --timeout-seconds 5"),
        "diagnostic_commands": [
            (
                "java -Xmx48g -cp pace-bench-v2.jar "
                "edu.ipcmax.casestudy.nyc.NycShuttleCaseStudyBench "
                "--dataset processed/NYC-REAL-V2 "
                "--query-file manifests/nyc_queries.jsonl "
                f"--output results/diagnostic_{cap}s.jsonl {common} "
                f"--timeout-seconds {cap}")
            for cap in (10, 30)
        ],
        "connector_contract": (
            "one exact FIFO time-dependent fastest structure at each connected "
            "connector-cell midpoint; canonical exact replay over that cell"),
        "obsolete_forbidden_options": [
            "Kc", "Mc", "Mq", "connector portfolio", "connector cache"],
        "controlled_selection": "manifests/nyc_pace_x_subset.json",
        "controlled_cap_seconds": 30,
    }


def write_query_schema(path: Path) -> None:
    schema = json.loads((CASE_ROOT / "schemas/query.schema.json").read_text(
        encoding="utf-8"))
    schema["title"] = "NYC-real-shuttle-v2 query record"
    schema["properties"]["schema_version"] = {"const": "nyc-query-v2"}
    schema["properties"]["dataset_id"] = {"const": "NYC-REAL-V2"}
    atomic_write_json(path, schema)


def write_checksums(root: Path) -> list[dict[str, Any]]:
    excluded = {"artifact_manifest.json", "CHECKSUMS.sha256"}
    records = []
    for item in sorted(value for value in root.rglob("*") if value.is_file()):
        relative = item.relative_to(root).as_posix()
        if relative in excluded:
            continue
        records.append({
            "path": relative,
            "size_bytes": item.stat().st_size,
            "sha256": sha256(item),
        })
    checksum_path = root / "CHECKSUMS.sha256"
    checksum_path.write_text(
        "".join(f"{record['sha256']}  {record['path']}\n"
                for record in records),
        encoding="utf-8", newline="\n")
    records.append({
        "path": "CHECKSUMS.sha256",
        "size_bytes": checksum_path.stat().st_size,
        "sha256": sha256(checksum_path),
    })
    return sorted(records, key=lambda value: value["path"])


def main() -> int:
    if DESTINATION.exists():
        raise RuntimeError(
            f"refusing to overwrite existing V2 package: {DESTINATION}")
    if not SOURCE.is_dir() or not V2_JAR.is_file() or not V1_JAR.is_file():
        raise RuntimeError("V2 source tree or execution binary is missing")
    before = verify_v1()
    atomic_write_json(SOURCE / "provenance/v1_preservation_proof.json", before)
    shutil.copytree(SOURCE, DESTINATION)
    for directory in ("config", "schemas", "provenance/execution",
                      "provenance/source", "scripts", "manifests"):
        (DESTINATION / directory).mkdir(parents=True, exist_ok=True)
    for name in ("case_study.yaml", "sources.yaml"):
        shutil.copy2(CASE_ROOT / "config" / name,
                     DESTINATION / "config" / name)
    shutil.copy2(CASE_ROOT / "schemas/result.schema.json",
                 DESTINATION / "schemas/result.schema.json")
    write_query_schema(DESTINATION / "schemas/query.schema.json")
    shutil.copy2(V2_JAR,
                 DESTINATION / "provenance/execution/pace-bench-v2.jar")
    shutil.copy2(V1_JAR,
                 DESTINATION / "provenance/execution/pace-bench-v1-control.jar")
    atomic_write_json(
        DESTINATION / "provenance/execution/execution_protocol.json",
        execution_protocol())
    source_files = [
        "src/main/java/edu/ipcmax/core/labeling/PointForwardLabeling.java",
        "src/main/java/edu/ipcmax/core/pcmax/BoundedConnectorGenerator.java",
        "src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java",
        "src/main/java/edu/ipcmax/core/pcmax/PACE.java",
        "src/main/java/edu/ipcmax/core/pcmax/PaceCompletion.java",
        "src/main/java/edu/ipcmax/core/pcmax/PaceGenerationResult.java",
        "src/main/java/edu/ipcmax/core/pcmax/PaceOptions.java",
        "src/main/java/edu/ipcmax/casestudy/nyc/NycShuttleCaseStudyBench.java",
        "src/test/java/edu/ipcmax/core/pcmax/ScalablePaceCandidateEngineTest.java",
    ]
    for relative in source_files:
        source = REPOSITORY / relative
        target = DESTINATION / "provenance/source" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    for name in ("build_v2_data.py", "analyze_v2_results.py",
                 "package_v2_results.py"):
        shutil.copy2(CASE_ROOT / "scripts" / name,
                     DESTINATION / "scripts" / name)
    for name in ("nyc_pace_x_subset.json",):
        shutil.copy2(CASE_ROOT / "manifests" / name,
                     DESTINATION / "manifests" / name)
    shutil.copy2(CASE_ROOT / "requirements.txt",
                 DESTINATION / "provenance/requirements.txt")
    status = {
        "case_id": "NYC-REAL-V2",
        "state": "COMPLETE",
        "primary_result": "results/primary_5s.jsonl",
        "primary_status_counts": {"COMPLETE": 7, "TIMEOUT": 93},
        "diagnostic_status_counts": {
            "10_seconds": {"COMPLETE": 13, "TIMEOUT": 87},
            "30_seconds": {"COMPLETE": 15, "TIMEOUT": 85},
        },
        "manuscript_modified": False,
        "v1_bundle_modified": False,
    }
    (DESTINATION / "STATUS.md").write_text(
        "# NYC-real-shuttle-v2\n\n"
        "Complete isolated V2 package. The V1 bundle was verified and not "
        "modified. See `reports/validation.md`, "
        "`reports/nyc_v1_v2_comparison.md`, and `artifact_manifest.json`.\n",
        encoding="utf-8", newline="\n")
    atomic_write_json(DESTINATION / "provenance/package_status.json", status)
    after = verify_v1()
    if before["v1_content_tree_sha256"] != after["v1_content_tree_sha256"]:
        raise RuntimeError("V1 bundle changed during V2 packaging")
    atomic_write_json(
        DESTINATION / "provenance/v1_preservation_proof.json", after)
    records = write_checksums(DESTINATION)
    manifest = {
        "schema_version": "nyc-v2-artifact-manifest-v1",
        "case_id": "NYC-REAL-V2",
        "packaged_at_utc": utc_now(),
        "artifact_count_excluding_manifest": len(records),
        "artifacts": records,
        "checksum_file_sha256": sha256(DESTINATION / "CHECKSUMS.sha256"),
        "primary_result_sha256": sha256(
            DESTINATION / "results/primary_5s.jsonl"),
        "query_manifest_sha256": sha256(
            DESTINATION / "manifests/nyc_queries.jsonl"),
        "graph_manifest_sha256": sha256(
            DESTINATION / "processed/NYC-REAL-V2/manifest.json"),
        "v1_preservation": after,
    }
    atomic_write_json(DESTINATION / "artifact_manifest.json", manifest)
    final_v1 = verify_v1()
    if final_v1["v1_content_tree_sha256"] != before["v1_content_tree_sha256"]:
        raise RuntimeError("V1 bundle changed while finalizing checksums")
    print(json.dumps({
        "destination": str(DESTINATION),
        "artifact_count_excluding_manifest": len(records),
        "artifact_manifest_sha256": sha256(
            DESTINATION / "artifact_manifest.json"),
        "v1_preservation": final_v1,
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, KeyError, ValueError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        raise SystemExit(2)

"""Prospective benchmark identity: fail closed before dispatching a Java job."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from .config import REPO_ROOT, repo_path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def source_paths(root: Path) -> list[Path]:
    """Execution-affecting source/config files; final report prose is excluded."""
    paths = [root / name for name in ["pom.xml", "Makefile", "pace_bench", "pace_bench.cmd"] if (root / name).is_file()]
    for folder, suffixes in [("src/main", {".java"}),
                             ("experiments/scripts", {".py"}),
                             ("experiments/tools/thop_feasibility", {".java"}),
                             ("experiments/configs", {".yaml", ".json"}),
                             ("experiments/schemas", {".json"}),
                             ("experiments/fixtures", {".json", ".jsonl"})]:
        paths.extend(p for p in (root / folder).rglob("*") if p.is_file() and p.suffix in suffixes)
    return sorted(paths)


_VERIFIED: dict[str, tuple] = {}


def verify_freeze(design: dict[str, Any]) -> dict[str, Any]:
    required = (
        design.get("profile") == "icde2027_v1"
        or design.get("freeze_required_before_execution") is True
    )
    if not required:
        return {}
    configured_path = design.get("freeze_manifest")
    if not configured_path:
        raise ValueError("execution requires an explicit freeze_manifest path")
    path = repo_path(configured_path)
    if not path.is_file():
        raise ValueError(f"prospective benchmark freeze is missing: {path}")
    value = json.loads(path.read_text())
    if value.get("readiness", {}).get("approved") is not True:
        raise ValueError("prospective benchmark freeze is not approved for execution")
    if value.get("schema_version") != 1 or value.get("source", {}).get("clean") is not True:
        raise ValueError("benchmark requires a clean, versioned source freeze")
    source = value["source"]
    if not source.get("files") or any(len(str(source.get(k, ""))) != 40 for k in ["commit", "tree"]):
        raise ValueError("freeze is missing source commit/tree/file identities")
    current_commit = subprocess_run_git("rev-parse", "HEAD")
    current_tree = subprocess_run_git("rev-parse", "HEAD^{tree}")
    current_status = subprocess_run_git("status", "--porcelain")
    if current_commit != source["commit"] or current_tree != source["tree"]:
        raise ValueError("current source commit/tree differs from the approved benchmark freeze")
    if current_status:
        raise ValueError("benchmark execution requires a clean frozen source checkout")
    expected_config = design.get("source_config_hash", design.get("config_hash"))
    if value.get("config_hash") != expected_config:
        raise ValueError("benchmark freeze config identity mismatch")
    if value.get("resources") != design.get("resources"):
        raise ValueError("benchmark freeze resource/cap settings mismatch")
    artifacts = value.get("artifacts", [])
    for dataset in design.get("datasets", []):
        for kind in ["dataset_manifest", "dataset_payload", "query_manifest"]:
            if not any(a.get("kind") == kind and a.get("dataset") == dataset for a in artifacts):
                raise ValueError(f"freeze is missing {kind} identity for {dataset}")
    jars = [a for a in artifacts if a.get("kind") == "jar"]
    if len(jars) != 1:
        raise ValueError("freeze must identify exactly one benchmark JAR")
    if design.get("paths", {}).get("jar") and repo_path(jars[0]["path"]).resolve() != repo_path(design["paths"]["jar"]).resolve():
        raise ValueError("frozen JAR differs from configured JAR")
    files = [(repo_path(a["path"]), a["sha256"]) for a in artifacts]
    files += [(REPO_ROOT / relative, digest) for relative, digest in value["source"].get("files", {}).items()]
    if value["source"].get("files"):
        actual_names = {p.relative_to(REPO_ROOT).as_posix() for p in source_paths(REPO_ROOT)}
        if actual_names != set(value["source"]["files"]):
            raise ValueError("execution source/config file inventory differs from freeze")
    stamps = []
    for artifact, expected in files:
        if not artifact.is_file():
            raise ValueError(f"frozen artifact is missing: {artifact}")
        info = artifact.stat()
        stamps.append((str(artifact.resolve()), expected, info.st_size, info.st_mtime_ns, info.st_ctime_ns))
    # Byte hashes are checked once per unchanged file identity in a runner.
    # The cache belongs to preparation and is invalidated by any file change.
    key = sha256_file(path)
    signature = tuple(stamps)
    if _VERIFIED.get(key) != signature:
        for artifact, expected in files:
            if sha256_file(artifact) != expected:
                raise ValueError(f"frozen artifact checksum mismatch: {artifact}")
        _VERIFIED[key] = signature
    return value


def subprocess_run_git(*arguments: str) -> str:
    """Read the current checkout identity without depending on user shell state."""
    import subprocess

    return subprocess.run(
        ["git", *arguments], cwd=REPO_ROOT, check=True,
        capture_output=True, text=True,
    ).stdout.strip()


def capture_run_freeze(design: dict[str, Any], run_root: Path) -> dict[str, Any]:
    """Archive the validated identity only inside the new run namespace."""
    value = verify_freeze(design)
    if not value:
        return value
    namespace = repo_path(design["paths"]["results_root"]).resolve()
    if namespace not in run_root.resolve().parents:
        raise ValueError("prospective run root must be inside its new results namespace")
    target = run_root / "provenance/benchmark_freeze.json"
    encoded = json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n"
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        with target.open("x", encoding="utf-8") as handle:
            handle.write(encoded)
    except FileExistsError:
        if target.read_text() != encoded:
            raise ValueError("run root already belongs to a different benchmark freeze")
    return value

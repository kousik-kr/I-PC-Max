"""Prospective execution must fail closed for missing identity or changed bytes."""
import json
from pathlib import Path
import pytest

from experiments.scripts.common.benchmark_freeze import verify_freeze


def test_missing_or_unapproved_freeze_blocks_execution(tmp_path):
    design = {"profile": "icde2027_v1", "freeze_manifest": str(tmp_path / "freeze.json")}
    with pytest.raises(ValueError, match="missing"):
        verify_freeze(design)
    (tmp_path / "freeze.json").write_text(json.dumps({"readiness": {"approved": False}}))
    with pytest.raises(ValueError, match="approved"):
        verify_freeze(design)


def test_jar_or_workload_mutation_invalidates_frozen_identity(tmp_path, monkeypatch):
    import hashlib
    jar = tmp_path / "bench.jar"
    query = tmp_path / "queries.jsonl"
    jar.write_bytes(b"frozen jar")
    query.write_bytes(b"frozen queries")
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    source = tmp_path / "pom.xml"
    source.write_text("frozen source")
    import experiments.scripts.common.benchmark_freeze as freeze
    monkeypatch.setattr(freeze, "REPO_ROOT", tmp_path)
    design = {"profile": "icde2027_v1", "config_hash": "design123",
              "freeze_manifest": str(tmp_path / "freeze.json"),
              "resources": {"algorithm_timeout_seconds": {"iscope": 5}},
              "datasets": ["NY"]}
    fixture = {"schema_version": 1, "readiness": {"approved": True},
               "config_hash": "design123", "source": {"clean": True, "commit": "a" * 40,
                                                        "tree": "b" * 40, "files": {"pom.xml": sha(source)}},
               "artifacts": [{"kind": "jar", "path": str(jar), "sha256": sha(jar)},
                             {"kind": "query_manifest", "dataset": "NY", "path": str(query), "sha256": sha(query)},
                             {"kind": "dataset_payload", "dataset": "NY", "path": str(query), "sha256": sha(query)},
                             {"kind": "dataset_manifest", "dataset": "NY", "path": str(query), "sha256": sha(query)}],
               "resources": design["resources"]}
    Path(design["freeze_manifest"]).write_text(json.dumps(fixture))
    verify_freeze(design)
    query.write_bytes(b"changed query")
    with pytest.raises(ValueError, match="checksum"):
        verify_freeze(design)
    query.write_bytes(b"frozen queries")
    jar.write_bytes(b"changed jar")
    with pytest.raises(ValueError, match="checksum"):
        verify_freeze(design)


def test_writer_refuses_dirty_source_before_freezing(tmp_path):
    import subprocess
    from experiments.scripts.freeze_benchmark import create_freeze
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    (tmp_path / "uncommitted.txt").write_text("preserve this work")
    with pytest.raises(ValueError, match="clean"):
        create_freeze({}, tmp_path, tmp_path / "not-built.jar", {}, {}, "fixture")

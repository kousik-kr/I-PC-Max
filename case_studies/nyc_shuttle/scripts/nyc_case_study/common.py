"""Shared deterministic I/O, provenance, and configuration helpers."""

from __future__ import annotations

import contextlib
import datetime as dt
import hashlib
import json
import os
import platform
import subprocess
import tempfile
from pathlib import Path
from typing import Any, Iterator


CASE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = CASE_ROOT.parents[1]
UTC = dt.timezone.utc


class CaseStudyError(RuntimeError):
    """Explicit, user-actionable case-study failure."""


def utc_now() -> str:
    return dt.datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(chunk_size), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def load_config(path: Path) -> dict[str, Any]:
    """Load JSON-compatible YAML, falling back to PyYAML for regular YAML."""
    text = path.read_text(encoding="utf-8")
    try:
        value = json.loads(text)
    except json.JSONDecodeError:
        try:
            import yaml  # type: ignore
        except ImportError as failure:
            raise CaseStudyError(
                f"{path} is not JSON-compatible YAML and PyYAML is not installed"
            ) from failure
        value = yaml.safe_load(text)
    if not isinstance(value, dict):
        raise CaseStudyError(f"configuration root must be an object: {path}")
    return value


@contextlib.contextmanager
def _atomic_target(path: Path) -> Iterator[Path]:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    os.close(descriptor)
    temporary_path = Path(temporary)
    try:
        yield temporary_path
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def atomic_write_bytes(path: Path, payload: bytes) -> None:
    with _atomic_target(path) as temporary:
        temporary.write_bytes(payload)


def atomic_write_text(path: Path, text: str) -> None:
    atomic_write_bytes(path, text.encode("utf-8"))


def atomic_write_json(path: Path, value: Any) -> None:
    atomic_write_text(path, json.dumps(value, indent=2, sort_keys=True) + "\n")


def immutable_write(path: Path, payload: bytes) -> str:
    """Write once; permit idempotent replay but reject changed content."""
    digest = sha256_bytes(payload)
    if path.exists():
        existing = sha256_file(path)
        if existing != digest:
            raise CaseStudyError(
                f"immutable raw artifact already exists with different content: {path} "
                f"(existing {existing}, new {digest})"
            )
        return digest
    atomic_write_bytes(path, payload)
    return digest


def artifact_metadata(path: Path, *, url: str | None = None, dataset_id: str | None = None,
                      retrieved_at: str | None = None, schema: Any = None) -> dict[str, Any]:
    return {
        "path": str(path),
        "source_url": url,
        "dataset_id": dataset_id,
        "retrieved_at_utc": retrieved_at,
        "size_bytes": path.stat().st_size,
        "sha256": sha256_file(path),
        "schema": schema,
    }


def parse_timestamp(value: str) -> dt.datetime:
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    parsed = dt.datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)


def git_revision() -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, check=True,
            text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "UNKNOWN"


def software_versions() -> dict[str, str]:
    result = {
        "python": platform.python_version(),
        "platform": platform.platform(),
    }
    for package in ("pandas", "geopandas", "shapely", "pyproj", "numpy", "pyarrow"):
        try:
            module = __import__(package)
            result[package] = str(getattr(module, "__version__", "UNKNOWN"))
        except ImportError:
            result[package] = "NOT_INSTALLED"
    return result


def require_columns(columns: list[str] | set[str], aliases: dict[str, list[str]], context: str) -> dict[str, str]:
    """Resolve case-insensitive semantic fields and fail with diagnostics."""
    normalized = {str(column).strip().lower(): str(column) for column in columns}
    resolved: dict[str, str] = {}
    missing: list[str] = []
    for semantic, candidates in aliases.items():
        match = next((normalized[item.lower()] for item in candidates if item.lower() in normalized), None)
        if match is None:
            missing.append(f"{semantic} ({' | '.join(candidates)})")
        else:
            resolved[semantic] = match
    if missing:
        raise CaseStudyError(
            f"{context}: required fields are missing: {', '.join(missing)}; "
            f"available fields: {', '.join(sorted(normalized.values()))}"
        )
    return resolved

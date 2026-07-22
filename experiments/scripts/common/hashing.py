"""Canonical hashing helpers used for immutable experiment identities."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import struct
from typing import Any, Iterable


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_text(value: str) -> str:
    return sha256_bytes(value.encode("utf-8"))


def sha256_json(value: Any) -> str:
    return sha256_text(canonical_json(value))


def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_files(paths: Iterable[Path], base: Path | None = None) -> str:
    """Hash filenames and contents in stable path order."""
    resolved = sorted((path.resolve() for path in paths), key=lambda path: path.as_posix())
    digest = hashlib.sha256()
    for path in resolved:
        name = path.relative_to(base.resolve()).as_posix() if base else path.name
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        with path.open("rb") as stream:
            while chunk := stream.read(1024 * 1024):
                digest.update(chunk)
        digest.update(b"\0")
    return digest.hexdigest()


def graph_checksum(directory: Path, filenames: Iterable[str]) -> str:
    """Match ManifestChecksum's domain-, name-, and length-framed graph hash."""
    digest = hashlib.sha256()
    domain = b"PACE-GRAPH-CHECKSUM-v1"
    digest.update(struct.pack(">I", len(domain)))
    digest.update(domain)
    for filename in filenames:
        name = filename.encode("utf-8")
        path = directory / filename
        digest.update(struct.pack(">I", len(name)))
        digest.update(name)
        digest.update(struct.pack(">Q", path.stat().st_size))
        with path.open("rb") as stream:
            while chunk := stream.read(1024 * 1024):
                digest.update(chunk)
    return digest.hexdigest()

"""Capture reproducibility metadata without assuming a Unix host."""
from __future__ import annotations

import os
import platform
from pathlib import Path
import subprocess
import sys
from typing import Any

from .config import REPO_ROOT
from .hashing import sha256_json
from .toolchain import environment, executable


def _command(arguments: list[str]) -> str:
    try:
        arguments = [executable(arguments[0]), *arguments[1:]]
        result = subprocess.run(
            arguments, cwd=REPO_ROOT, env=environment(), check=False, capture_output=True, text=True, timeout=15
        )
        return (result.stdout or result.stderr).strip().splitlines()[0]
    except (OSError, subprocess.SubprocessError, IndexError):
        return "unavailable"


def git_state() -> dict[str, Any]:
    commit = _command(["git", "rev-parse", "HEAD"])
    status = _command(["git", "status", "--porcelain"])
    return {"commit": commit, "dirty": bool(status and status != "unavailable")}


def host_environment() -> dict[str, Any]:
    logical = os.cpu_count() or 1
    value = {
        "operating_system": platform.platform(),
        "machine": platform.machine(),
        "processor": platform.processor() or "unavailable",
        "hostname": platform.node(),
        "logical_cores": logical,
        "python": sys.version.split()[0],
        "java": _command(["java", "-version"]),
        "maven": _command(["mvn", "-version"]),
        "git": git_state(),
    }
    value["fingerprint"] = sha256_json(value)
    return value


def build_fingerprint(paths: list[Path]) -> str:
    values = []
    for path in sorted(paths):
        stat = path.stat()
        values.append({"path": path.relative_to(REPO_ROOT).as_posix(), "size": stat.st_size})
    return sha256_json(values)

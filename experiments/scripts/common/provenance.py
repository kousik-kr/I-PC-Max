"""Capture reproducibility metadata without assuming a Unix host."""
from __future__ import annotations

import os
import platform
from pathlib import Path
import subprocess
import sys
import hashlib
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
    dirty = bool(status and status != "unavailable")
    working_tree_hash = None
    if dirty:
        try:
            digest = hashlib.sha256()
            difference = subprocess.run(
                [executable("git"), "diff", "--binary", "HEAD"],
                cwd=REPO_ROOT,
                env=environment(),
                check=True,
                capture_output=True,
                timeout=30,
            ).stdout
            digest.update(difference)
            untracked = subprocess.run(
                [executable("git"), "ls-files", "--others", "--exclude-standard", "-z"],
                cwd=REPO_ROOT,
                env=environment(),
                check=True,
                capture_output=True,
                timeout=30,
            ).stdout.split(b"\0")
            for encoded in sorted(value for value in untracked if value):
                digest.update(encoded)
                path = REPO_ROOT / encoded.decode("utf-8", "surrogateescape")
                if path.is_file():
                    digest.update(path.read_bytes())
            working_tree_hash = digest.hexdigest()
        except (OSError, subprocess.SubprocessError):
            working_tree_hash = "unavailable"
    return {
        "commit": commit,
        "dirty": dirty,
        "working_tree_hash": working_tree_hash,
    }


def available_logical_cores() -> int:
    """Return logical CPUs available to this process, respecting affinity."""
    try:
        return max(1, len(os.sched_getaffinity(0)))
    except (AttributeError, OSError):
        return os.cpu_count() or 1


def physical_core_count() -> int:
    """Best-effort physical-core count for the current CPU affinity."""
    try:
        cpus = sorted(os.sched_getaffinity(0))
    except (AttributeError, OSError):
        cpus = list(range(os.cpu_count() or 1))
    cores: set[tuple[str, str]] = set()
    for cpu in cpus:
        topology = Path(
            f"/sys/devices/system/cpu/cpu{cpu}/topology"
        )
        try:
            package = (topology / "physical_package_id").read_text(
                encoding="utf-8"
            ).strip()
            core = (topology / "core_id").read_text(
                encoding="utf-8"
            ).strip()
        except OSError:
            return available_logical_cores()
        cores.add((package, core))
    return max(1, len(cores))


def resolved_thread_list(candidates: list[int]) -> list[int]:
    """Resolve the primary scaling curve through the physical-core count."""
    physical = physical_core_count()
    return sorted({
        int(value)
        for value in candidates
        if isinstance(value, int) and not isinstance(value, bool)
        and 1 <= value <= physical
    })


def host_environment() -> dict[str, Any]:
    logical = available_logical_cores()
    physical = physical_core_count()
    value = {
        "operating_system": platform.platform(),
        "machine": platform.machine(),
        "processor": platform.processor() or "unavailable",
        "hostname": platform.node(),
        "physical_cores": physical,
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

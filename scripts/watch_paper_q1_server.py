#!/usr/bin/env python3
"""Persist PACE run progress and stop a run before it exceeds its disk budget."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from experiments.scripts.background_run import _summarize_run
from experiments.scripts.common.atomic_io import atomic_write_json
from experiments.scripts.common.config import load_design, repo_path


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _read_json(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def _directory_bytes(root: Path) -> int:
    total = 0
    if not root.exists():
        return total
    for directory, _, names in os.walk(root):
        for name in names:
            path = Path(directory) / name
            try:
                total += path.stat(follow_symlinks=False).st_size
            except FileNotFoundError:
                pass
    return total


def _append_jsonl(path: Path, record: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


def _process_matches(pid: int, run_id: str) -> bool:
    try:
        command = Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\0", b" ").decode(
            "utf-8", "replace"
        )
    except OSError:
        return False
    return run_id in command and "background_run.py" in command


def _terminate_process_group(launcher: Path, run_id: str, reason: str) -> None:
    state = _read_json(launcher / "state.json") or {}
    worker_pid = state.get("worker_pid")
    record: dict[str, Any] = {
        "schema_version": 1,
        "run_id": run_id,
        "stopped_at_utc": _utc_now(),
        "reason": reason,
        "worker_pid": worker_pid,
        "signal": None,
    }
    if not isinstance(worker_pid, int) or not _process_matches(worker_pid, run_id):
        record["error"] = "live launcher worker could not be verified"
        atomic_write_json(launcher / "disk_guard_stop.json", record)
        return
    try:
        process_group = os.getpgid(worker_pid)
        if process_group != worker_pid:
            raise RuntimeError(
                f"worker PID {worker_pid} is not its process-group leader ({process_group})"
            )
        os.killpg(process_group, signal.SIGTERM)
        record["signal"] = "SIGTERM"
        deadline = time.monotonic() + 15.0
        while time.monotonic() < deadline:
            try:
                os.kill(worker_pid, 0)
            except ProcessLookupError:
                break
            time.sleep(0.25)
        else:
            os.killpg(process_group, signal.SIGKILL)
            record["signal"] = "SIGKILL"
    except (OSError, RuntimeError) as failure:
        record["error"] = str(failure)
    atomic_write_json(launcher / "disk_guard_stop.json", record)


def _paths(config: Path, run_id: str) -> tuple[Path, Path]:
    results_root = repo_path(load_design(config)["paths"]["results_root"])
    return results_root / run_id, results_root / "_launchers" / run_id


def _snapshot(config: Path, run_id: str, disk_limit_bytes: int) -> dict[str, Any]:
    run_root, launcher = _paths(config, run_id)
    summary = _summarize_run(config, run_id, 5)
    run_bytes = _directory_bytes(run_root) + _directory_bytes(launcher)
    usage = shutil.disk_usage(run_root.parent)
    return {
        "schema_version": 1,
        "observed_at_utc": _utc_now(),
        "run_id": run_id,
        "lifecycle": summary["lifecycle"],
        "current_stage": summary["current_stage"],
        "completed_stages": summary["completed_stages"],
        "raw_records": summary["raw_records"],
        "planned_jobs": summary["planned_jobs"],
        "raw_status_counts": summary["raw_status_counts"],
        "worker_pid": summary["worker_pid"],
        "controller_pid": summary["controller_pid"],
        "run_bytes": run_bytes,
        "disk_limit_bytes": disk_limit_bytes,
        "disk_limit_exceeded": run_bytes >= disk_limit_bytes,
        "filesystem_free_bytes": usage.free,
        "stdout_tail": summary["stdout_tail"],
        "stderr_tail": summary["stderr_tail"],
    }


def _watch(config: Path, run_id: str, disk_limit_bytes: int, interval: float) -> int:
    _, launcher = _paths(config, run_id)
    launcher.mkdir(parents=True, exist_ok=True)
    while True:
        snapshot = _snapshot(config, run_id, disk_limit_bytes)
        atomic_write_json(launcher / "progress_latest.json", snapshot)
        _append_jsonl(launcher / "progress_history.jsonl", snapshot)
        if snapshot["disk_limit_exceeded"]:
            _terminate_process_group(
                launcher,
                run_id,
                f"run output reached the configured {disk_limit_bytes}-byte disk limit",
            )
            return 3
        if snapshot["lifecycle"] == "EXITED":
            return 0
        time.sleep(interval)


def _daemonize(
    config: Path, run_id: str, disk_limit_gib: float, interval: float
) -> int:
    _, launcher = _paths(config, run_id)
    launcher.mkdir(parents=True, exist_ok=True)
    stdout_path = launcher / "progress_watch.stdout.log"
    stderr_path = launcher / "progress_watch.stderr.log"
    command = [
        sys.executable,
        str(Path(__file__).resolve()),
        "watch",
        "--config",
        str(config),
        "--run-id",
        run_id,
        "--disk-limit-gib",
        str(disk_limit_gib),
        "--interval-seconds",
        str(interval),
    ]
    with stdout_path.open("a", encoding="utf-8") as stdout, stderr_path.open(
        "a", encoding="utf-8"
    ) as stderr:
        process = subprocess.Popen(
            command,
            cwd=REPO_ROOT,
            stdin=subprocess.DEVNULL,
            stdout=stdout,
            stderr=stderr,
            start_new_session=True,
            text=True,
        )
    atomic_write_json(
        launcher / "progress_watch_state.json",
        {
            "schema_version": 1,
            "run_id": run_id,
            "watcher_pid": process.pid,
            "started_at_utc": _utc_now(),
            "disk_limit_bytes": int(disk_limit_gib * 1024**3),
            "interval_seconds": interval,
            "command": command,
        },
    )
    print(f"watcher_pid={process.pid}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="action", required=True)
    for action in ("launch", "watch", "status"):
        child = subparsers.add_parser(action)
        child.add_argument(
            "--config",
            type=Path,
            default=Path("experiments/configs/paper_q1_server_24c_250g.yaml"),
        )
        child.add_argument("--run-id", required=True)
        child.add_argument("--disk-limit-gib", type=float, default=100.0)
        child.add_argument("--interval-seconds", type=float, default=60.0)
    args = parser.parse_args()
    if args.disk_limit_gib <= 0 or args.interval_seconds <= 0:
        parser.error("disk limit and interval must be positive")
    config = args.config.resolve()
    disk_limit_bytes = int(args.disk_limit_gib * 1024**3)
    if args.action == "launch":
        return _daemonize(config, args.run_id, args.disk_limit_gib, args.interval_seconds)
    if args.action == "watch":
        return _watch(config, args.run_id, disk_limit_bytes, args.interval_seconds)
    print(json.dumps(_snapshot(config, args.run_id, disk_limit_bytes), indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

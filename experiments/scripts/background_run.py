#!/usr/bin/env python3
"""Launch and inspect a PACE experiment controller in the background."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_json
from experiments.scripts.common.config import load_design, repo_path


STAGES = (
    "preflight", "build", "data", "queries", "plan", "smoke", "correctness", "pilot",
    "main", "sensitivity", "ablation", "parallel", "robustness", "collect", "validate",
    "summarize", "plot", "table", "package",
)


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _results_root(config: Path) -> Path:
    return repo_path(load_design(config)["paths"]["results_root"])


def _launcher_dir(config: Path, run_id: str) -> Path:
    return _results_root(config) / "_launchers" / run_id


def _run_root(config: Path, run_id: str) -> Path:
    return _results_root(config) / run_id


def _read_json(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def _pid_alive(pid: int | None) -> bool:
    if not pid or pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _line_count(path: Path) -> int:
    try:
        return sum(1 for line in path.read_text(encoding="utf-8").splitlines() if line.strip())
    except UnicodeDecodeError:
        return 0


def _tail(path: Path, lines: int) -> list[str]:
    if lines <= 0 or not path.is_file():
        return []
    data = path.read_text(encoding="utf-8", errors="replace").splitlines()
    return data[-lines:]


def _stage_order(stages: str) -> list[str]:
    requested = STAGES if stages == "all" else tuple(item.strip() for item in stages.split(",") if item.strip())
    return [stage for stage in STAGES if stage in requested]


def _summarize_run(config: Path, run_id: str, tail_lines: int) -> dict[str, Any]:
    launcher = _launcher_dir(config, run_id)
    root = _run_root(config, run_id)
    state = _read_json(launcher / "state.json") or {}
    exit_record = _read_json(launcher / "exit.json")
    pid = state.get("worker_pid")
    controller_pid = state.get("controller_pid")
    process_running = _pid_alive(int(pid)) if isinstance(pid, int) else False
    controller_running = _pid_alive(int(controller_pid)) if isinstance(controller_pid, int) else False
    if exit_record:
        lifecycle = "EXITED"
    elif process_running or controller_running:
        lifecycle = "RUNNING"
    elif state:
        lifecycle = "UNKNOWN"
    else:
        lifecycle = "NOT_LAUNCHED"

    markers_dir = root / "markers"
    completed_stages = sorted(
        (path.name.removesuffix(".complete.json") for path in markers_dir.glob("*.complete.json")),
        key=lambda stage: STAGES.index(stage) if stage in STAGES else len(STAGES),
    ) if markers_dir.is_dir() else []
    selected_stages = _stage_order(str(state.get("stages", "all")))
    current_stage = next((stage for stage in selected_stages if stage not in completed_stages), None)

    matrix_counts = {}
    matrix_dir = root / "plan" / "matrices"
    if matrix_dir.is_dir():
        for matrix in sorted(matrix_dir.glob("*.jsonl")):
            matrix_counts[matrix.stem] = _line_count(matrix)
    planned_jobs = sum(matrix_counts.values())

    raw_status_counts: dict[str, int] = {}
    raw_records = 0
    raw_dir = root / "raw"
    if raw_dir.is_dir():
        for raw in sorted(raw_dir.glob("*/*.jsonl")):
            for line in raw.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                raw_records += 1
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    raw_status_counts["INVALID_JSON"] = raw_status_counts.get("INVALID_JSON", 0) + 1
                    continue
                status = str(row.get("completion_status", "UNKNOWN"))
                raw_status_counts[status] = raw_status_counts.get(status, 0) + 1

    stdout_path = Path(state.get("stdout_path", launcher / "run_all.stdout.log"))
    stderr_path = Path(state.get("stderr_path", launcher / "run_all.stderr.log"))
    if not stdout_path.is_absolute():
        stdout_path = repo_path(stdout_path)
    if not stderr_path.is_absolute():
        stderr_path = repo_path(stderr_path)

    return {
        "run_id": run_id,
        "lifecycle": lifecycle,
        "exit": exit_record,
        "worker_pid": pid,
        "controller_pid": controller_pid,
        "completed_stages": completed_stages,
        "current_stage": current_stage,
        "planned_jobs": planned_jobs,
        "matrix_counts": matrix_counts,
        "raw_records": raw_records,
        "raw_status_counts": raw_status_counts,
        "run_root": root.relative_to(repo_path(".")).as_posix(),
        "launcher_dir": launcher.relative_to(repo_path(".")).as_posix(),
        "stdout_log": stdout_path.relative_to(repo_path(".")).as_posix(),
        "stderr_log": stderr_path.relative_to(repo_path(".")).as_posix(),
        "stdout_tail": _tail(stdout_path, tail_lines),
        "stderr_tail": _tail(stderr_path, tail_lines),
    }


def _worker(args: argparse.Namespace) -> int:
    launcher = _launcher_dir(args.config, args.run_id)
    launcher.mkdir(parents=True, exist_ok=True)
    stdout_path = launcher / "run_all.stdout.log"
    stderr_path = launcher / "run_all.stderr.log"
    exit_path = launcher / "exit.json"
    command = [
        sys.executable, "experiments/scripts/run_all.py",
        "--config", str(args.config),
        "--run-id", args.run_id,
        "--backend", args.backend,
        "--stages", args.stages,
    ]
    if args.resume:
        command.append("--resume")
    if args.plan_only:
        command.append("--plan-only")
    if args.max_concurrent is not None:
        command.extend(["--max-concurrent", str(args.max_concurrent)])
    for study in args.study or []:
        command.extend(["--study", study])
    for dataset in args.dataset or []:
        command.extend(["--dataset", dataset])

    state = {
        "schema_version": 1,
        "run_id": args.run_id,
        "config": str(args.config),
        "backend": args.backend,
        "stages": args.stages,
        "resume": args.resume,
        "plan_only": args.plan_only,
        "max_concurrent": args.max_concurrent,
        "study": args.study or [],
        "dataset": args.dataset or [],
        "worker_pid": os.getpid(),
        "controller_pid": None,
        "started_at_utc": _utc_now(),
        "command": command,
        "stdout_path": stdout_path.relative_to(repo_path(".")).as_posix(),
        "stderr_path": stderr_path.relative_to(repo_path(".")).as_posix(),
    }
    atomic_write_json(launcher / "state.json", state)
    environment = dict(os.environ)
    environment["PYTHONUNBUFFERED"] = "1"
    with stdout_path.open("w", encoding="utf-8") as stdout, stderr_path.open("w", encoding="utf-8") as stderr:
        process = subprocess.Popen(
            command, cwd=repo_path("."), env=environment,
            stdout=stdout, stderr=stderr, text=True,
        )
        state["controller_pid"] = process.pid
        atomic_write_json(launcher / "state.json", state)
        return_code = process.wait()
    exit_record = {
        "schema_version": 1,
        "run_id": args.run_id,
        "finished_at_utc": _utc_now(),
        "exit_code": return_code,
    }
    atomic_write_json(exit_path, exit_record)
    state["finished_at_utc"] = exit_record["finished_at_utc"]
    state["exit_code"] = return_code
    atomic_write_json(launcher / "state.json", state)
    return return_code


def _launch(args: argparse.Namespace) -> int:
    launcher = _launcher_dir(args.config, args.run_id)
    launcher.mkdir(parents=True, exist_ok=True)
    state = _read_json(launcher / "state.json") or {}
    existing_pid = state.get("worker_pid")
    if isinstance(existing_pid, int) and _pid_alive(existing_pid) and not args.allow_existing:
        print(f"background run is already active: run_id={args.run_id} pid={existing_pid}", file=sys.stderr)
        return 2
    command = [
        sys.executable, str(Path(__file__).resolve()), "worker",
        "--config", str(args.config),
        "--run-id", args.run_id,
        "--backend", args.backend,
        "--stages", args.stages,
    ]
    if args.resume:
        command.append("--resume")
    if args.plan_only:
        command.append("--plan-only")
    if args.max_concurrent is not None:
        command.extend(["--max-concurrent", str(args.max_concurrent)])
    for study in args.study or []:
        command.extend(["--study", study])
    for dataset in args.dataset or []:
        command.extend(["--dataset", dataset])
    with (launcher / "worker.stdout.log").open("w", encoding="utf-8") as stdout, (launcher / "worker.stderr.log").open("w", encoding="utf-8") as stderr:
        process = subprocess.Popen(
            command, cwd=repo_path("."), stdout=stdout, stderr=stderr,
            stdin=subprocess.DEVNULL, start_new_session=True, text=True,
        )
    state = {
        "schema_version": 1,
        "run_id": args.run_id,
        "config": str(args.config),
        "backend": args.backend,
        "stages": args.stages,
        "resume": args.resume,
        "plan_only": args.plan_only,
        "max_concurrent": args.max_concurrent,
        "study": args.study or [],
        "dataset": args.dataset or [],
        "worker_pid": process.pid,
        "controller_pid": None,
        "started_at_utc": _utc_now(),
        "launcher_command": command,
        "stdout_path": (launcher / "run_all.stdout.log").relative_to(repo_path(".")).as_posix(),
        "stderr_path": (launcher / "run_all.stderr.log").relative_to(repo_path(".")).as_posix(),
    }
    atomic_write_json(launcher / "state.json", state)
    print(json.dumps(_summarize_run(args.config, args.run_id, args.tail), indent=2, sort_keys=True))
    return 0


def _status(args: argparse.Namespace) -> int:
    summary = _summarize_run(args.config, args.run_id, args.tail)
    if args.json:
        print(json.dumps(summary, indent=2, sort_keys=True))
    else:
        print(f"run_id={summary['run_id']}")
        print(f"lifecycle={summary['lifecycle']}")
        print(f"worker_pid={summary['worker_pid']} controller_pid={summary['controller_pid']}")
        print(f"run_root={summary['run_root']}")
        print(f"launcher_dir={summary['launcher_dir']}")
        print(f"completed_stages={','.join(summary['completed_stages']) or '-'}")
        print(f"current_stage={summary['current_stage'] or '-'}")
        print(f"raw_records={summary['raw_records']} planned_jobs={summary['planned_jobs']}")
        print(f"raw_status_counts={json.dumps(summary['raw_status_counts'], sort_keys=True)}")
        if summary["exit"]:
            print(f"exit_code={summary['exit']['exit_code']} finished_at_utc={summary['exit']['finished_at_utc']}")
        if summary["stderr_tail"]:
            print("stderr_tail:")
            for line in summary["stderr_tail"]:
                print(line)
        if summary["stdout_tail"]:
            print("stdout_tail:")
            for line in summary["stdout_tail"]:
                print(line)
    return 0 if summary["lifecycle"] != "NOT_LAUNCHED" else 1


def _stop(args: argparse.Namespace) -> int:
    state = _read_json(_launcher_dir(args.config, args.run_id) / "state.json") or {}
    pid = state.get("worker_pid")
    controller_pid = state.get("controller_pid")
    targets = [value for value in (controller_pid, pid) if isinstance(value, int) and _pid_alive(value)]
    if not targets:
        print(f"no live process found for run_id={args.run_id}")
        return 0
    signal_value = signal.SIGKILL if args.kill else signal.SIGTERM
    for target in targets:
        os.kill(target, signal_value)
    print(f"sent {signal_value.name} to {len(targets)} process(es) for run_id={args.run_id}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    def add_common(target: argparse.ArgumentParser) -> None:
        target.add_argument("--config", type=Path, default=Path("experiments/configs/paper_q1.yaml"))
        target.add_argument("--run-id", required=True)

    launch = subparsers.add_parser("launch", help="start run_all.py in a detached worker")
    add_common(launch)
    launch.add_argument("--backend", choices=("local", "slurm"), default="local")
    launch.add_argument("--stages", default="all")
    launch.add_argument("--resume", action="store_true")
    launch.add_argument("--plan-only", action="store_true")
    launch.add_argument("--max-concurrent", type=int)
    launch.add_argument("--study", action="append")
    launch.add_argument("--dataset", action="append")
    launch.add_argument("--allow-existing", action="store_true")
    launch.add_argument("--tail", type=int, default=12)
    launch.set_defaults(func=_launch)

    worker = subparsers.add_parser("worker", help=argparse.SUPPRESS)
    add_common(worker)
    worker.add_argument("--backend", choices=("local", "slurm"), default="local")
    worker.add_argument("--stages", default="all")
    worker.add_argument("--resume", action="store_true")
    worker.add_argument("--plan-only", action="store_true")
    worker.add_argument("--max-concurrent", type=int)
    worker.add_argument("--study", action="append")
    worker.add_argument("--dataset", action="append")
    worker.set_defaults(func=_worker)

    status = subparsers.add_parser("status", help="inspect launcher state, stage markers, and raw records")
    add_common(status)
    status.add_argument("--tail", type=int, default=12)
    status.add_argument("--json", action="store_true")
    status.set_defaults(func=_status)

    stop = subparsers.add_parser("stop", help="terminate the background worker and controller")
    add_common(stop)
    stop.add_argument("--kill", action="store_true")
    stop.set_defaults(func=_stop)

    args = parser.parse_args()
    try:
        return args.func(args)
    except (OSError, ValueError, json.JSONDecodeError, subprocess.SubprocessError) as failure:
        print(f"background_run: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

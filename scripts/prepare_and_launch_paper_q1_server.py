#!/usr/bin/env python3
"""Resume required preparation, preflight, and the full PACE Q1 server run."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from experiments.scripts.background_run import _summarize_run
from experiments.scripts.common.atomic_io import atomic_write_json
from experiments.scripts.common.config import (
    filtered_design,
    load_design,
    repo_path,
)
from experiments.scripts.generate_queries import validate_all


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _read_json(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def _pid_alive(pid: int | None) -> bool:
    if not isinstance(pid, int) or pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _paths(config: Path, run_id: str) -> tuple[Path, Path]:
    results_root = repo_path(load_design(config)["paths"]["results_root"])
    return results_root / run_id, results_root / "_launchers" / run_id


def _state(
    launcher: Path,
    run_id: str,
    status: str,
    stage: str,
    **extra: Any,
) -> None:
    previous = _read_json(launcher / "preparation_state.json") or {}
    record = {
        **previous,
        "schema_version": 1,
        "run_id": run_id,
        "status": status,
        "current_stage": stage,
        "updated_at_utc": _utc_now(),
        **extra,
    }
    atomic_write_json(launcher / "preparation_state.json", record)


def _command(arguments: list[str]) -> None:
    print("command=" + " ".join(arguments), flush=True)
    completed = subprocess.run(arguments, cwd=REPO_ROOT, check=False)
    if completed.returncode != 0:
        raise RuntimeError(
            f"command exited with {completed.returncode}: "
            + " ".join(arguments)
        )


def _queries_ready(config: Path, dataset: str) -> bool:
    design = filtered_design(load_design(config), {dataset})
    return bool(validate_all(design)["passed"])


def _run_pipeline(
    config: Path,
    run_id: str,
    cpu_list: str,
    max_concurrent: int,
    disk_limit_gib: float,
    datasets: list[str],
) -> int:
    _, launcher = _paths(config, run_id)
    launcher.mkdir(parents=True, exist_ok=True)
    try:
        _state(
            launcher,
            run_id,
            "RUNNING",
            "assets",
            pipeline_pid=os.getpid(),
            started_at_utc=(
                _read_json(launcher / "preparation_state.json") or {}
            ).get("started_at_utc", _utc_now()),
            cpu_list=cpu_list,
            max_concurrent=max_concurrent,
            disk_limit_gib=disk_limit_gib,
            datasets=datasets,
        )
        _command([
            sys.executable,
            "experiments/scripts/generate_dataset_assets.py",
            "--config",
            str(config),
            "--resume",
        ])
        _state(launcher, run_id, "RUNNING", "build")
        _command(["mvn", "-q", "-DskipTests", "package"])
        completed_datasets = []
        for dataset in datasets:
            _state(
                launcher,
                run_id,
                "RUNNING",
                f"queries:{dataset}",
                completed_query_datasets=completed_datasets,
            )
            if not _queries_ready(config, dataset):
                _command([
                    sys.executable,
                    "experiments/scripts/generate_query_sets.py",
                    "--config",
                    str(config),
                    "--dataset",
                    dataset,
                    "--overwrite",
                    "--skip-build",
                ])
            completed_datasets.append(dataset)
        _state(
            launcher,
            run_id,
            "RUNNING",
            "preflight",
            completed_query_datasets=completed_datasets,
        )
        preflight_command = [
            sys.executable,
            "experiments/scripts/preflight.py",
            "--config",
            str(config),
            "--output",
            str(launcher / "deep_preflight.json"),
        ]
        for dataset in datasets:
            preflight_command.extend(["--dataset", dataset])
        _command(preflight_command)
        _state(launcher, run_id, "RUNNING", "plan")
        plan_command = [
            "taskset",
            "-c",
            cpu_list,
            sys.executable,
            "experiments/scripts/run_all.py",
            "--config",
            str(config),
            "--run-id",
            run_id,
            "--backend",
            "local",
            "--stages",
            "all",
            "--resume",
            "--plan-only",
            "--max-concurrent",
            str(max_concurrent),
        ]
        for dataset in datasets:
            plan_command.extend(["--dataset", dataset])
        _command(plan_command)
        _state(launcher, run_id, "RUNNING", "experiment_launch")
        launch_command = [
            "taskset",
            "-c",
            cpu_list,
            sys.executable,
            "experiments/scripts/background_run.py",
            "launch",
            "--config",
            str(config),
            "--run-id",
            run_id,
            "--backend",
            "local",
            "--stages",
            "all",
            "--resume",
            "--max-concurrent",
            str(max_concurrent),
        ]
        for dataset in datasets:
            launch_command.extend(["--dataset", dataset])
        _command(launch_command)
        _command([
            sys.executable,
            "scripts/watch_paper_q1_server.py",
            "launch",
            "--config",
            str(config),
            "--run-id",
            run_id,
            "--disk-limit-gib",
            str(disk_limit_gib),
            "--interval-seconds",
            "60",
        ])
        _state(
            launcher,
            run_id,
            "COMPLETE",
            "experiment_running",
            completed_query_datasets=completed_datasets,
            finished_at_utc=_utc_now(),
        )
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as failure:
        _state(
            launcher,
            run_id,
            "FAILED",
            "blocked",
            error={"type": type(failure).__name__, "message": str(failure)},
            failed_at_utc=_utc_now(),
        )
        print(f"PACE preparation pipeline: {failure}", file=sys.stderr)
        return 2


def _launch(args: argparse.Namespace) -> int:
    _, launcher = _paths(args.config, args.run_id)
    launcher.mkdir(parents=True, exist_ok=True)
    state_path = launcher / "preparation_state.json"
    state = _read_json(state_path) or {}
    existing_pid = state.get("pipeline_pid")
    if _pid_alive(existing_pid):
        print(
            f"preparation pipeline is already active: "
            f"run_id={args.run_id} pid={existing_pid}"
        )
        return 0
    stdout_path = launcher / "preparation.stdout.log"
    stderr_path = launcher / "preparation.stderr.log"
    command = [
        "taskset",
        "-c",
        args.cpu_list,
        sys.executable,
        str(Path(__file__).resolve()),
        "run",
        "--config",
        str(args.config),
        "--run-id",
        args.run_id,
        "--cpu-list",
        args.cpu_list,
        "--max-concurrent",
        str(args.max_concurrent),
        "--disk-limit-gib",
        str(args.disk_limit_gib),
    ]
    for dataset in args.datasets:
        command.extend(["--dataset", dataset])
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
    _state(
        launcher,
        args.run_id,
        "RUNNING",
        "starting",
        pipeline_pid=process.pid,
        started_at_utc=_utc_now(),
        command=command,
        stdout_path=stdout_path.relative_to(REPO_ROOT).as_posix(),
        stderr_path=stderr_path.relative_to(REPO_ROOT).as_posix(),
    )
    print(f"run_id={args.run_id}")
    print(f"pipeline_pid={process.pid}")
    return 0


def _status(args: argparse.Namespace) -> int:
    _, launcher = _paths(args.config, args.run_id)
    preparation = _read_json(launcher / "preparation_state.json") or {}
    experiment = _summarize_run(args.config, args.run_id, args.tail)
    output = {
        "run_id": args.run_id,
        "preparation": preparation,
        "experiment": experiment,
    }
    print(json.dumps(output, indent=2, sort_keys=True))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="action", required=True)
    for action in ("launch", "run", "status"):
        child = subparsers.add_parser(action)
        child.add_argument(
            "--config",
            type=Path,
            default=Path(
                "experiments/configs/paper_q1_server_24c_250g.yaml"
            ),
        )
        child.add_argument("--run-id", required=True)
        child.add_argument("--cpu-list", default="0-23")
        child.add_argument("--max-concurrent", type=int, default=24)
        child.add_argument("--disk-limit-gib", type=float, default=100.0)
        child.add_argument("--tail", type=int, default=10)
        child.add_argument(
            "--dataset",
            dest="datasets",
            action="append",
            choices=("NY", "FLA", "CAL", "USA"),
            help="restrict preparation and experiment execution to this dataset (repeatable)",
        )
    args = parser.parse_args()
    args.config = args.config.resolve()
    configured_datasets = list(load_design(args.config)["datasets"])
    args.datasets = args.datasets or configured_datasets
    if len(args.datasets) != len(set(args.datasets)):
        parser.error("--dataset values must be unique")
    if args.max_concurrent < 1 or args.disk_limit_gib <= 0:
        parser.error("concurrency and disk limit must be positive")
    if args.action == "launch":
        return _launch(args)
    if args.action == "run":
        return _run_pipeline(
            args.config,
            args.run_id,
            args.cpu_list,
            args.max_concurrent,
            args.disk_limit_gib,
            args.datasets,
        )
    return _status(args)


if __name__ == "__main__":
    raise SystemExit(main())

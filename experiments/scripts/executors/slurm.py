"""Generate a deterministic Slurm array submission script."""
from __future__ import annotations

from pathlib import Path
import subprocess
from typing import Any

from ..common.atomic_io import atomic_write_text


def write_array_script(
    path: Path,
    python_executable: str,
    config: Path,
    run_id: str,
    matrix: Path,
    job_count: int,
    timeout_seconds: int,
    memory_limit_mb: int,
) -> Path:
    if job_count < 1:
        raise ValueError("cannot create an empty Slurm array")
    minutes = max(1, (timeout_seconds + 59) // 60)
    text = f"""#!/usr/bin/env bash
#SBATCH --job-name=pace-{run_id}
#SBATCH --array=0-{job_count - 1}
#SBATCH --time={minutes}
#SBATCH --mem={memory_limit_mb}M
#SBATCH --output=experiments/results/{run_id}/logs/slurm-%A_%a.out
set -euo pipefail
{python_executable} experiments/scripts/execute_matrix.py \\
  --config {config.as_posix()} --run-id {run_id} --matrix {matrix.as_posix()} \\
  --backend local --job-index "$SLURM_ARRAY_TASK_ID" --max-concurrent 1 --resume
"""
    atomic_write_text(path, text)
    return path


def submit(path: Path, wait: bool = False) -> str:
    command = ["sbatch"]
    if wait:
        command.append("--wait")
    command.append(str(path))
    completed = subprocess.run(command, check=True, capture_output=True, text=True)
    return completed.stdout.strip()

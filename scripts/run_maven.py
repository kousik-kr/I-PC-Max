#!/usr/bin/env python3
"""Run Maven with the active JDK even when the inherited JAVA_HOME is stale."""
from __future__ import annotations

from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from experiments.scripts.common.toolchain import environment, executable


if __name__ == "__main__":
    raise SystemExit(subprocess.run([executable("mvn"), *sys.argv[1:]], cwd=ROOT, env=environment(), check=False).returncode)

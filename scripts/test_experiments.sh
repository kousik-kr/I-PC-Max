#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:---all}"
case "$mode" in
  --unit) python "$ROOT/scripts/run_maven.py" -f "$ROOT/pom.xml" -Dtest='edu.ipcmax.core.**' test ;;
  --integration) python "$ROOT/scripts/run_maven.py" -f "$ROOT/pom.xml" -Dtest='*IntegrationTest,*Cli*Test,*FrameworkTest' test ;;
  --smoke) python "$ROOT/scripts/run_matrix.py" --config "$ROOT/experiments/configs/smoke.yaml" --jobs 1 ;;
  --exactness) python "$ROOT/scripts/run_matrix.py" --config "$ROOT/experiments/configs/exactness.yaml" --jobs 1 ;;
  --determinism) python "$ROOT/scripts/run_maven.py" -f "$ROOT/pom.xml" -Dtest='*Determinism*Test' test ;;
  --all) python "$ROOT/scripts/run_maven.py" -f "$ROOT/pom.xml" test; python "$ROOT/scripts/run_matrix.py" --config "$ROOT/experiments/configs/smoke.yaml" --jobs 1 ;;
  *) echo "usage: $0 [--unit|--integration|--smoke|--exactness|--determinism|--all]" >&2; exit 2 ;;
esac

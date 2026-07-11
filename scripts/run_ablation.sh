#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
args=(--algorithm pace-b)
while (($#)); do
  case "$1" in
    --queries) args+=(--query-file "$2"); shift 2 ;;
    --output) args+=(--output-jsonl "$2"); shift 2 ;;
    --algorithm) echo "run_ablation.sh fixes --algorithm pace-b" >&2; exit 2 ;;
    *) args+=("$1"); shift ;;
  esac
done
exec "$ROOT/scripts/run_candidate.sh" "${args[@]}"

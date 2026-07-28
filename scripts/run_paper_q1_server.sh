#!/usr/bin/env bash
set -euo pipefail

CONFIG="${PAPER_SERVER_CONFIG:-experiments/configs/paper_q1_server_24c_250g.yaml}"
RUN_ID="${PAPER_SERVER_RUN_ID:-pace_q1_server_24c_250g}"
BACKEND="${BACKEND:-local}"
MAX_CONCURRENT="${PAPER_SERVER_MAX_CONCURRENT:-24}"

exec python3 experiments/scripts/background_run.py launch \
  --config "$CONFIG" \
  --run-id "$RUN_ID" \
  --backend "$BACKEND" \
  --stages all \
  --resume \
  --max-concurrent "$MAX_CONCURRENT" \
  "$@"

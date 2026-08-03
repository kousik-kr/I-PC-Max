#!/usr/bin/env bash
set -euo pipefail

CONFIG="${PAPER_SERVER_CONFIG:-experiments/configs/paper_q1_server_24c_250g.yaml}"
RUN_ID="${PAPER_SERVER_RUN_ID:-pace_q1_two_track_server_24c_250g}"

exec python3 experiments/scripts/background_run.py status \
  --config "$CONFIG" \
  --run-id "$RUN_ID" \
  "$@"

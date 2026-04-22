#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

for bench in Carter01Bad Dbcp65; do
  run_script="$SCRIPT_DIR/$bench/run.sh"
  if [ ! -f "$run_script" ]; then
    echo "SKIP (no run.sh) — $bench"
    continue
  fi
  echo ""
  echo "=========================================="
  echo "  $bench"
  echo "=========================================="
  chmod +x "$run_script"
  bash "$run_script" || true
done

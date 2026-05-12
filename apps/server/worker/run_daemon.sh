#!/usr/bin/env sh
set -eu

STACK_ROOT="${STACK_ROOT:-/var/www/vhosts/agitated-engelbart.74-208-203-194.plesk.page/adfree-stack}"
STORAGE_ROOT="${APP_STORAGE:-$STACK_ROOT/storage}"
LOG_DIR="$STACK_ROOT/logs"
LOCK_DIR="$STACK_ROOT/worker.lock"

mkdir -p "$LOG_DIR"

if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  echo "Worker already running or lock exists: $LOCK_DIR"
  exit 0
fi
trap 'rmdir "$LOCK_DIR"' EXIT INT TERM

export APP_STORAGE="$STORAGE_ROOT"
export FFMPEG_BIN="${FFMPEG_BIN:-$STACK_ROOT/runtime/bin/ffmpeg}"
export FFPROBE_BIN="${FFPROBE_BIN:-$STACK_ROOT/runtime/bin/ffprobe}"
export PROCESSOR_MODE="${PROCESSOR_MODE:-adcutforge}"
export ADCUTFORGE_ROOT="${ADCUTFORGE_ROOT:-$STACK_ROOT/adcutforge}"
export ADCUTFORGE_PYTHON="${ADCUTFORGE_PYTHON:-/usr/bin/python3}"
export PARAKEET_MODEL="${PARAKEET_MODEL:-nvidia/parakeet-tdt-0.6b-v3}"

printf '\n[%s] worker daemon start\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
/usr/bin/python3 "$STACK_ROOT/worker/process_jobs.py"

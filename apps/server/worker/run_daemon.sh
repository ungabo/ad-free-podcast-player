#!/usr/bin/env sh
set -eu

STACK_ROOT="${STACK_ROOT:-/var/www/vhosts/agitated-engelbart.74-208-203-194.plesk.page/adfree-stack}"
STORAGE_ROOT="${APP_STORAGE:-$STACK_ROOT/storage}"
LOG_DIR="$STACK_ROOT/logs"
LOCK_DIR="$STACK_ROOT/worker.lock"

mkdir -p "$LOG_DIR"

if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  exit 0
fi
trap 'rm -rf "$LOCK_DIR"' EXIT INT TERM

export APP_STORAGE="$STORAGE_ROOT"
if [ -f "$STACK_ROOT/.env" ]; then
  set -a
  . "$STACK_ROOT/.env"
  set +a
fi
export ADCUTFORGE_CACHE_DIR="${ADCUTFORGE_CACHE_DIR:-$STORAGE_ROOT/adcutforge-cache}"
export LOCAL_PROCESSOR_BASE_URL="${LOCAL_PROCESSOR_BASE_URL:-http://127.0.0.1:8081/adfree-api}"
export LOCAL_PROCESSOR_TIMEOUT_SECONDS="${LOCAL_PROCESSOR_TIMEOUT_SECONDS:-14400}"

printf '\n[%s] worker daemon start\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
/usr/bin/python3 "$STACK_ROOT/worker/process_jobs.py"

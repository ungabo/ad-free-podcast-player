#!/usr/bin/env sh
set -eu

STACK_ROOT="${STACK_ROOT:-/var/www/vhosts/agitated-engelbart.74-208-203-194.plesk.page/adfree-stack}"
STORAGE_ROOT="${APP_STORAGE:-$STACK_ROOT/storage}"
LOG_DIR="$STACK_ROOT/logs"
LOCK_DIR="$STACK_ROOT/worker.lock"
LOCK_PID_FILE="$LOCK_DIR/pid"
WORKER_SCRIPT="$STACK_ROOT/worker/process_jobs.py"

mkdir -p "$LOG_DIR"

is_pid_alive() {
  pid="$1"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

worker_is_running() {
  if command -v pgrep >/dev/null 2>&1; then
    pgrep -f "$WORKER_SCRIPT" >/dev/null 2>&1
    return $?
  fi

  ps -ef | grep "$WORKER_SCRIPT" | grep -v grep >/dev/null 2>&1
}

while ! mkdir "$LOCK_DIR" 2>/dev/null; do
  existing_pid=""
  if [ -f "$LOCK_PID_FILE" ]; then
    existing_pid="$(cat "$LOCK_PID_FILE" 2>/dev/null || true)"
  fi

  if is_pid_alive "$existing_pid"; then
    exit 0
  fi
  if [ -z "$existing_pid" ] && worker_is_running; then
    exit 0
  fi

  printf '[%s] removing stale worker lock%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "${existing_pid:+ from pid $existing_pid}" >> "$LOG_DIR/worker-cron.log"
  rm -rf "$LOCK_DIR"
done

printf '%s\n' "$$" > "$LOCK_PID_FILE"
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

printf '\n[%s] worker tick\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
/usr/bin/python3 "$WORKER_SCRIPT" --once

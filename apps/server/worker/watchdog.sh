#!/usr/bin/env sh
set -eu

STACK_ROOT="${STACK_ROOT:-/var/www/vhosts/agitated-engelbart.74-208-203-194.plesk.page/adfree-stack}"
LOG_DIR="$STACK_ROOT/logs"
WORKER_SCRIPT="$STACK_ROOT/worker/process_jobs.py"
DAEMON_SCRIPT="$STACK_ROOT/worker/run_daemon.sh"

mkdir -p "$LOG_DIR"

worker_is_running() {
  if command -v pgrep >/dev/null 2>&1; then
    pgrep -f "$WORKER_SCRIPT" >/dev/null 2>&1
    return $?
  fi

  ps -ef | grep "$WORKER_SCRIPT" | grep -v grep >/dev/null 2>&1
}

if worker_is_running; then
  exit 0
fi

printf '[%s] watchdog starting worker daemon\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" >> "$LOG_DIR/worker-watchdog.log"
nohup "$DAEMON_SCRIPT" >> "$LOG_DIR/worker-daemon.log" 2>&1 &

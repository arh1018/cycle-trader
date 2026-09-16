#!/usr/bin/env bash
# Stop cycle-trader cleanly. Uses SIGTERM: a background job started from a script has SIGINT
# ignored, so Ctrl+C-style signals do nothing to it.
set -euo pipefail
cd "$(dirname "$0")/.."
PIDFILE=logs/cycle-trader.pid
if [ ! -f "$PIDFILE" ]; then
  echo "no pid file"
  exit 1
fi
PID=$(cat "$PIDFILE")
if ! kill -0 "$PID" 2>/dev/null; then
  echo "not running"
  rm -f "$PIDFILE"
  exit 0
fi
kill -TERM "$PID"
for _ in $(seq 1 20); do
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "stopped"
    rm -f "$PIDFILE"
    exit 0
  fi
  sleep 1
done
echo "did not exit in 20s; forcing"
kill -KILL "$PID"
rm -f "$PIDFILE"

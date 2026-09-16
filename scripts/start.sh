#!/usr/bin/env bash
# Start cycle-trader detached. Logs to logs/cycle-trader.log, pid in logs/cycle-trader.pid.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p logs
if [ -f logs/cycle-trader.pid ] && kill -0 "$(cat logs/cycle-trader.pid)" 2>/dev/null; then
  echo "already running (pid $(cat logs/cycle-trader.pid))"
  exit 1
fi
JAR=target/cycle-trader.jar
if [ ! -f "$JAR" ]; then
  echo "building..."
  mvn -q package
fi
nohup java -jar "$JAR" "${1:-config/config.yaml}" >> logs/cycle-trader.log 2>&1 &
echo $! > logs/cycle-trader.pid
echo "started pid $(cat logs/cycle-trader.pid); tail -f logs/cycle-trader.log"

#!/usr/bin/env zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PID_DIR="$ROOT_DIR/.local/pids"

stop_pid_file() {
  local name="$1"
  local pid_file="$PID_DIR/$name.pid"
  if [[ ! -f "$pid_file" ]]; then
    echo "$name 未记录运行 PID。"
    return
  fi

  local pid
  pid="$(cat "$pid_file")"
  if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
    kill "$pid" || true
    echo "已停止 $name，PID: $pid"
  else
    echo "$name 未运行。"
  fi
  rm -f "$pid_file"
}

stop_pid_file worker
stop_pid_file frontend
stop_pid_file backend

echo ""
echo "已停止本地 worker、前端和后端。"
echo "MySQL 容器默认保留运行，避免下次启动慢。"
echo "如需停止 MySQL：docker stop aitest-mysql"

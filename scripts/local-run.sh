#!/usr/bin/env zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.local"
LOG_DIR="$RUN_DIR/logs"
FRONTEND_DIR="${AITEST_FRONTEND_DIR:-$ROOT_DIR/frontend-react}"
MYSQL_CONTAINER="aitest-mysql"
MYSQL_VOLUME="aitest_mysql_data"
MYSQL_IMAGE="mysql:8.4"
MAVEN_REPO="${AITEST_MAVEN_REPO:-$RUN_DIR/m2/repository}"

BACKEND_PID=""
FRONTEND_PID=""
WORKER_PID=""

mkdir -p "$LOG_DIR"

print_step() {
  echo ""
  echo "==> $1"
}

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "缺少命令：$1"
    echo "请先安装 $2 后重试。"
    exit 1
  fi
}

cleanup() {
  echo ""
  echo "正在停止本地体验环境..."
  [[ -n "$WORKER_PID" ]] && kill "$WORKER_PID" >/dev/null 2>&1 || true
  [[ -n "$FRONTEND_PID" ]] && kill "$FRONTEND_PID" >/dev/null 2>&1 || true
  [[ -n "$BACKEND_PID" ]] && kill "$BACKEND_PID" >/dev/null 2>&1 || true
  echo "已停止。MySQL 容器默认保留运行，避免下次启动慢。"
}

wait_http() {
  local url="$1"
  local name="$2"
  local max_seconds="${3:-60}"
  local i
  for i in $(seq 1 "$max_seconds"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "$name 已就绪：$url"
      return
    fi
    sleep 1
  done
  echo "$name 启动超时，请查看日志目录：$LOG_DIR"
  exit 1
}

http_ready() {
  local url="$1"
  curl -fsS "$url" >/dev/null 2>&1
}

read_worker_local_token() {
  local config_path="$HOME/Library/Application Support/AI-Test-Worker/config.json"
  [[ -f "$config_path" ]] || return
  node -e 'const fs=require("fs"); const p=process.argv[1]; const c=JSON.parse(fs.readFileSync(p,"utf8")); if (c.localToken) console.log(c.localToken);' "$config_path" 2>/dev/null || true
}

trap cleanup INT TERM EXIT

print_step "检查本地依赖"
need_cmd docker "Docker 或 Colima"
need_cmd java "Java 17"
need_cmd mvn "Maven"
need_cmd node "Node.js 18+"
need_cmd npm "npm"
need_cmd curl "curl"

print_step "启动 MySQL"
if docker ps --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; then
  echo "MySQL 容器已运行：$MYSQL_CONTAINER"
elif docker ps -a --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; then
  docker start "$MYSQL_CONTAINER" >/dev/null
  echo "已启动已有 MySQL 容器：$MYSQL_CONTAINER"
else
  docker run -d \
    --name "$MYSQL_CONTAINER" \
    -e MYSQL_ROOT_PASSWORD=root \
    -e MYSQL_DATABASE=aitest \
    -p 3306:3306 \
    -v "$MYSQL_VOLUME:/var/lib/mysql" \
    "$MYSQL_IMAGE" >/dev/null
  echo "已创建 MySQL 容器：$MYSQL_CONTAINER"
fi

echo "等待 MySQL 就绪..."
for i in $(seq 1 90); do
  if docker exec "$MYSQL_CONTAINER" mysqladmin ping -uroot -proot --silent >/dev/null 2>&1; then
    echo "MySQL 已就绪。"
    break
  fi
  [[ "$i" == "90" ]] && echo "MySQL 启动超时，请查看：docker logs $MYSQL_CONTAINER" && exit 1
  sleep 1
done

print_step "准备前端和 worker 依赖"
[[ -d "$FRONTEND_DIR/node_modules" ]] || (cd "$FRONTEND_DIR" && npm install)
[[ -d "$ROOT_DIR/local-browser-worker/node_modules" ]] || (cd "$ROOT_DIR/local-browser-worker" && npm install)
(cd "$ROOT_DIR/local-browser-worker" && npm run build)

print_step "启动主平台"
if http_ready "http://localhost:8080/api/auth/init-status"; then
  echo "后端已有可用服务，复用：http://localhost:8080"
else
  (cd "$ROOT_DIR/backend" && mvn -Dmaven.repo.local="$MAVEN_REPO" spring-boot:run >"$LOG_DIR/backend.log" 2>&1) &
  BACKEND_PID=$!
fi

if http_ready "http://localhost:5173"; then
  echo "前端已有可用服务，复用：http://localhost:5173"
else
  (cd "$FRONTEND_DIR" && npm run dev -- --host 127.0.0.1 >"$LOG_DIR/frontend.log" 2>&1) &
  FRONTEND_PID=$!
fi

wait_http "http://localhost:8080/api/auth/init-status" "后端" 90
wait_http "http://localhost:5173" "前端" 60

WORKER_CONFIG="$HOME/Library/Application Support/AI-Test-Worker/config.json"
if [[ -f "$WORKER_CONFIG" ]] && grep -q '"bindStatus"[[:space:]]*:[[:space:]]*"BOUND"' "$WORKER_CONFIG"; then
  print_step "启动已绑定的本地采集器"
  if http_ready "http://127.0.0.1:17321/health"; then
    echo "本地采集器已有可用服务，复用：http://127.0.0.1:17321"
  else
    (cd "$ROOT_DIR/local-browser-worker" && node dist/cli/index.js start >"$LOG_DIR/worker.log" 2>&1) &
    WORKER_PID=$!
  fi
  wait_http "http://127.0.0.1:17321/health" "本地采集器" 30
  WORKER_LOCAL_TOKEN="$(read_worker_local_token)"
  if [[ -n "$WORKER_LOCAL_TOKEN" ]]; then
    echo "本地访问令牌：$WORKER_LOCAL_TOKEN"
    echo "请填到前端“执行轨迹 -> 快速采集 -> 本地令牌”。"
  fi
else
  echo ""
  echo "本地采集器尚未绑定，进入前端“执行轨迹”页面生成绑定码后，另开终端执行："
  echo "  ./scripts/local-worker.sh <绑定码>"
fi

echo ""
echo "本地体验环境正在运行。请保持此终端窗口打开。"
echo ""
echo "访问地址："
echo "  前端：http://localhost:5173"
echo "  后端：http://localhost:8080"
echo "  Worker：http://127.0.0.1:17321/health"
echo ""
echo "日志目录：$LOG_DIR"
echo "停止方式：按 Ctrl+C"

while true; do
  if [[ -n "$BACKEND_PID" ]] && ! kill -0 "$BACKEND_PID" >/dev/null 2>&1; then
    echo "后端进程已退出，请查看 $LOG_DIR/backend.log"
    exit 1
  fi
  if [[ -n "$FRONTEND_PID" ]] && ! kill -0 "$FRONTEND_PID" >/dev/null 2>&1; then
    echo "前端进程已退出，请查看 $LOG_DIR/frontend.log"
    exit 1
  fi
  if [[ -n "$WORKER_PID" ]] && ! kill -0 "$WORKER_PID" >/dev/null 2>&1; then
    echo "本地采集器进程已退出，请查看 $LOG_DIR/worker.log"
    exit 1
  fi
  sleep 2
done

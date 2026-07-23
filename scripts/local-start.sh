#!/usr/bin/env zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.local"
LOG_DIR="$RUN_DIR/logs"
PID_DIR="$RUN_DIR/pids"
FRONTEND_DIR="${AITEST_FRONTEND_DIR:-$ROOT_DIR/frontend-react}"
MYSQL_CONTAINER="aitest-mysql"
MYSQL_VOLUME="aitest_mysql_data"
MYSQL_IMAGE="mysql:8.4"
MAVEN_REPO="${AITEST_MAVEN_REPO:-${TMPDIR:-/tmp}/aitest-m2/repository}"
LOCAL_ENV_FILE="$RUN_DIR/dev-secrets.env"

mkdir -p "$LOG_DIR" "$PID_DIR"

ensure_local_secrets() {
  if [[ ! -f "$LOCAL_ENV_FILE" ]]; then
    need_cmd openssl "OpenSSL"
    umask 077
    cat > "$LOCAL_ENV_FILE" <<EOF
MYSQL_ROOT_PASSWORD=$(openssl rand -hex 24)
APP_SECRET_KEY=$(openssl rand -base64 32 | tr -d '\n')
JWT_SECRET=$(openssl rand -hex 48)
EOF
  fi
  source "$LOCAL_ENV_FILE"
}

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

is_pid_alive() {
  local pid="$1"
  [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1
}

start_bg() {
  local name="$1"
  local workdir="$2"
  local health_url="$3"
  shift 3
  local pid_file="$PID_DIR/$name.pid"
  local log_file="$LOG_DIR/$name.log"

  if http_ready "$health_url"; then
    echo "$name 已有可用服务，复用：$health_url"
    rm -f "$pid_file"
    return
  fi

  if [[ -f "$pid_file" ]] && is_pid_alive "$(cat "$pid_file")"; then
    echo "$name 已在运行，PID: $(cat "$pid_file")"
    return
  fi

  echo "启动 $name，日志：$log_file"
  (
    cd "$workdir"
    nohup "$@" >"$log_file" 2>&1 </dev/null &
    echo $! >"$pid_file"
  )
}

http_ready() {
  local url="$1"
  curl -fsS "$url" >/dev/null 2>&1
}

wait_http() {
  local url="$1"
  local name="$2"
  local max_seconds="${3:-60}"
  local i
  for i in $(seq 1 "$max_seconds"); do
    if http_ready "$url"; then
      echo "$name 已就绪：$url"
      return
    fi
    sleep 1
  done
  echo "$name 启动超时，请查看日志目录：$LOG_DIR"
  exit 1
}

print_step "检查本地依赖"
need_cmd docker "Docker 或 Colima"
need_cmd java "Java 17"
need_cmd mvn "Maven"
need_cmd node "Node.js 18+"
need_cmd npm "npm"
need_cmd curl "curl"
ensure_local_secrets

print_step "启动 MySQL"
if docker ps --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; then
  echo "MySQL 容器已运行：$MYSQL_CONTAINER"
elif docker ps -a --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; then
  docker start "$MYSQL_CONTAINER" >/dev/null
  echo "已启动已有 MySQL 容器：$MYSQL_CONTAINER"
else
  docker run -d \
    --name "$MYSQL_CONTAINER" \
    -e MYSQL_ROOT_PASSWORD="$MYSQL_ROOT_PASSWORD" \
    -e MYSQL_DATABASE=aitest \
    -p 3306:3306 \
    -v "$MYSQL_VOLUME:/var/lib/mysql" \
    "$MYSQL_IMAGE" >/dev/null
  echo "已创建 MySQL 容器：$MYSQL_CONTAINER"
fi

echo "等待 MySQL 就绪..."
for i in $(seq 1 90); do
  if docker exec "$MYSQL_CONTAINER" mysqladmin ping -uroot -p"$MYSQL_ROOT_PASSWORD" --silent >/dev/null 2>&1; then
    echo "MySQL 已就绪。"
    break
  fi
  if [[ "$i" == "90" ]]; then
    echo "MySQL 启动超时，请查看：docker logs $MYSQL_CONTAINER"
    exit 1
  fi
  sleep 1
done

print_step "安装前端依赖"
if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
  (cd "$FRONTEND_DIR" && npm install)
else
  echo "$FRONTEND_DIR/node_modules 已存在，跳过 npm install。"
fi

print_step "安装并编译本地采集器"
if [[ ! -d "$ROOT_DIR/local-browser-worker/node_modules" ]]; then
  (cd "$ROOT_DIR/local-browser-worker" && npm install)
else
  echo "local-browser-worker/node_modules 已存在，跳过 npm install。"
fi
(cd "$ROOT_DIR/local-browser-worker" && npm run build)

print_step "启动后端和前端"
start_bg backend "$ROOT_DIR/backend" "http://localhost:8080/api/auth/init-status" env DB_PASSWORD="$MYSQL_ROOT_PASSWORD" APP_SECRET_KEY="$APP_SECRET_KEY" JWT_SECRET="$JWT_SECRET" mvn -Dmaven.repo.local="$MAVEN_REPO" spring-boot:run
start_bg frontend "$FRONTEND_DIR" "http://localhost:5173" npm run dev -- --host 127.0.0.1

wait_http "http://localhost:8080/api/auth/init-status" "后端" 90
wait_http "http://localhost:5173" "前端" 60

WORKER_CONFIG="$HOME/Library/Application Support/AI-Test-Worker/config.json"
if [[ -f "$WORKER_CONFIG" ]] && grep -q '"bindStatus"[[:space:]]*:[[:space:]]*"BOUND"' "$WORKER_CONFIG"; then
  print_step "启动已绑定的本地采集器"
  start_bg worker "$ROOT_DIR/local-browser-worker" "http://127.0.0.1:17321/health" node dist/cli/index.js start
  wait_http "http://127.0.0.1:17321/health" "本地采集器" 30
  echo "本地采集器已启动。本地访问令牌不会输出到终端日志。"
else
  echo ""
  echo "本地采集器尚未绑定，暂不自动启动。"
fi

echo ""
echo "本地体验环境已启动。"
echo ""
echo "访问地址："
echo "  前端：http://localhost:5173"
echo "  后端：http://localhost:8080"
echo ""
echo "首次使用："
echo "  1. 打开前端，初始化管理员账号。"
echo "  2. 创建项目，进入项目工作台。"
echo "  3. 如需试执行轨迹采集器，在页面生成绑定码后执行："
echo "     ./scripts/local-worker.sh <绑定码>"
echo "     已绑定过的机器下次执行 ./scripts/local-start.sh 会自动启动 worker。"
echo ""
echo "日志目录：$LOG_DIR"
echo "停止环境：./scripts/local-stop.sh"

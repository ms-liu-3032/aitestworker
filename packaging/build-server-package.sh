#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_ROOT="$ROOT_DIR/release/server"
BUILD_ROOT="$OUT_ROOT/aitest-server-package"
DIST_NAME="aitest-server-package-$(date +%Y%m%d-%H%M%S)"
DIST_DIR="$OUT_ROOT/$DIST_NAME"
FRONTEND_DIR="${AITEST_FRONTEND_DIR:-$ROOT_DIR/frontend-react}"
FRONTEND_DIST="$FRONTEND_DIR/dist"
BACKEND_TARGET="$ROOT_DIR/backend/target"
JAR_NAME="aitest-backend.jar"

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "缺少命令：$1"
    exit 1
  fi
}

echo "==> 检查打包依赖"
need_cmd java
need_cmd mvn
need_cmd node
need_cmd npm
need_cmd docker
need_cmd tar
need_cmd openssl

echo "==> 构建前端静态资源"
(
  cd "$FRONTEND_DIR"
  npm run build
)

echo "==> 构建后端 Jar"
(
  cd "$ROOT_DIR/backend"
  mvn -DskipTests package
)

JAR_PATH="$(find "$BACKEND_TARGET" -maxdepth 1 -type f -name '*.jar' ! -name '*original*' | head -n 1)"
if [[ -z "$JAR_PATH" ]]; then
  echo "未找到后端 Jar 产物。"
  exit 1
fi

rm -rf "$BUILD_ROOT" "$DIST_DIR"
mkdir -p "$BUILD_ROOT/app/frontend-dist" "$BUILD_ROOT/app/backend" "$BUILD_ROOT/docs" "$BUILD_ROOT/data"

cp "$JAR_PATH" "$BUILD_ROOT/app/backend/$JAR_NAME"
cp -R "$FRONTEND_DIST"/. "$BUILD_ROOT/app/frontend-dist/"

cat > "$BUILD_ROOT/.env.example" <<'EOF'
APP_NAME=aitest-server
MYSQL_ROOT_PASSWORD=replace-with-a-unique-mysql-root-password
MYSQL_DATABASE=aitest
MYSQL_PORT=3306
REDIS_PORT=6379
WEAVIATE_PORT=8081
NEO4J_HTTP_PORT=7474
NEO4J_BOLT_PORT=7687
MINIO_API_PORT=9000
MINIO_CONSOLE_PORT=9001
WEB_PORT=5173
BACKEND_PORT=8080
APP_SECRET_KEY=replace-with-base64-encoded-32-byte-key
JWT_SECRET=replace-with-at-least-32-random-bytes
# Optional. When omitted, APP_SECRET_KEY also encrypts model API keys.
APP_MODEL_SECRET_KEY=
MINIO_ROOT_USER=replace-with-a-minio-admin-user
MINIO_ROOT_PASSWORD=replace-with-a-unique-minio-password
NEO4J_AUTH=neo4j/replace-with-a-unique-neo4j-password
EOF

cat > "$BUILD_ROOT/generate-env.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_FILE="${1:-$ROOT_DIR/.env}"

if [[ -f "$OUT_FILE" ]]; then
  echo "拒绝覆盖已存在的 $OUT_FILE"
  exit 1
fi

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "缺少命令：$1"; exit 1; }
}
need_cmd openssl

random_hex() { openssl rand -hex "$1"; }
random_base64() { openssl rand -base64 "$1" | tr -d '\n'; }

cat > "$OUT_FILE" <<ENV
APP_NAME=aitest-server
MYSQL_ROOT_PASSWORD=$(random_hex 24)
MYSQL_DATABASE=aitest
MYSQL_PORT=3306
REDIS_PORT=6379
WEAVIATE_PORT=8081
NEO4J_HTTP_PORT=7474
NEO4J_BOLT_PORT=7687
MINIO_API_PORT=9000
MINIO_CONSOLE_PORT=9001
WEB_PORT=5173
BACKEND_PORT=8080
APP_SECRET_KEY=$(random_base64 32)
JWT_SECRET=$(random_hex 48)
MINIO_ROOT_USER=aitest-admin
MINIO_ROOT_PASSWORD=$(random_hex 24)
NEO4J_AUTH=neo4j/$(random_hex 24)
ENV

chmod 600 "$OUT_FILE" 2>/dev/null || true
echo "已生成唯一部署密钥：$OUT_FILE"
EOF
chmod +x "$BUILD_ROOT/generate-env.sh"

cat > "$BUILD_ROOT/docker-compose.yml" <<'EOF'
services:
  mysql:
    image: mysql:8.4
    container_name: ${APP_NAME}-mysql
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ${MYSQL_DATABASE}
      TZ: Asia/Shanghai
    ports:
      - "${MYSQL_PORT}:3306"
    volumes:
      - ./data/mysql:/var/lib/mysql
      - /etc/localtime:/etc/localtime:ro

  redis:
    image: redis:7.4
    container_name: ${APP_NAME}-redis
    environment:
      TZ: Asia/Shanghai
    ports:
      - "${REDIS_PORT}:6379"
    volumes:
      - ./data/redis:/data
      - /etc/localtime:/etc/localtime:ro

  weaviate:
    image: semitechnologies/weaviate:1.26.6
    container_name: ${APP_NAME}-weaviate
    ports:
      - "${WEAVIATE_PORT}:8080"
    environment:
      QUERY_DEFAULTS_LIMIT: 25
      AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED: "true"
      PERSISTENCE_DATA_PATH: /var/lib/weaviate
      DEFAULT_VECTORIZER_MODULE: none
      ENABLE_MODULES: ""
      CLUSTER_HOSTNAME: node1
      TZ: Asia/Shanghai
    volumes:
      - ./data/weaviate:/var/lib/weaviate
      - /etc/localtime:/etc/localtime:ro

  neo4j:
    image: neo4j:5.24
    container_name: ${APP_NAME}-neo4j
    ports:
      - "${NEO4J_HTTP_PORT}:7474"
      - "${NEO4J_BOLT_PORT}:7687"
    environment:
      NEO4J_AUTH: ${NEO4J_AUTH}
      TZ: Asia/Shanghai
    volumes:
      - ./data/neo4j:/data
      - /etc/localtime:/etc/localtime:ro

  minio:
    image: minio/minio:RELEASE.2024-10-13T13-34-11Z
    container_name: ${APP_NAME}-minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
      TZ: Asia/Shanghai
    ports:
      - "${MINIO_API_PORT}:9000"
      - "${MINIO_CONSOLE_PORT}:9001"
    volumes:
      - ./data/minio:/data
      - /etc/localtime:/etc/localtime:ro

  backend:
    image: eclipse-temurin:17-jre
    container_name: ${APP_NAME}-backend
    working_dir: /app
    command: ["java", "-jar", "/app/backend/aitest-backend.jar"]
    depends_on:
      - mysql
    environment:
      DB_URL: jdbc:mysql://mysql:3306/${MYSQL_DATABASE}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
      DB_USERNAME: root
      DB_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      APP_SECRET_KEY: ${APP_SECRET_KEY}
      JWT_SECRET: ${JWT_SECRET}
      FILE_STORAGE_ROOT: /app/data/files
      TZ: Asia/Shanghai
      JAVA_TOOL_OPTIONS: "-Duser.timezone=Asia/Shanghai"
    ports:
      - "${BACKEND_PORT}:8080"
    volumes:
      - ./app:/app
      - ./data/files:/app/data/files
      - /etc/localtime:/etc/localtime:ro

  web:
    image: nginx:1.27-alpine
    container_name: ${APP_NAME}-web
    depends_on:
      - backend
    environment:
      TZ: Asia/Shanghai
    ports:
      - "${WEB_PORT}:80"
    volumes:
      - ./app/frontend-dist:/usr/share/nginx/html:ro
      - ./nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - /etc/localtime:/etc/localtime:ro
EOF

cat > "$BUILD_ROOT/nginx.conf" <<'EOF'
server {
  listen 80;
  server_name _;

  root /usr/share/nginx/html;
  index index.html;

  location /api/ {
    proxy_pass http://backend:8080/api/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }

  location = /index.html {
    add_header Cache-Control "no-store, no-cache, must-revalidate, proxy-revalidate";
    try_files /index.html =404;
  }

  location = / {
    add_header Cache-Control "no-store, no-cache, must-revalidate, proxy-revalidate";
    try_files /index.html =404;
  }

  location /assets/ {
    add_header Cache-Control "public, max-age=31536000, immutable";
    try_files $uri =404;
  }

  location / {
    add_header Cache-Control "no-store, no-cache, must-revalidate, proxy-revalidate";
    try_files $uri $uri/ /index.html;
  }
}
EOF

cat > "$BUILD_ROOT/install.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "缺少命令：$1"
    exit 1
  fi
}

need_cmd docker

compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
    return
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
    return
  fi
  echo "当前环境不可用 Docker Compose（docker compose / docker-compose）。"
  exit 1
}

# 修复宿主机时区到 Asia/Shanghai。容器通过 /etc/localtime:ro 复用宿主机时区，
# 宿主机 TZ 错了的话容器 TZ 一样会错。CentOS 7 默认 UTC，需要先修。
echo "==> 修复宿主机时区为 Asia/Shanghai"
if command -v timedatectl >/dev/null 2>&1; then
  timedatectl set-timezone Asia/Shanghai 2>/dev/null || true
fi
if [[ ! -L /etc/localtime ]] || ! readlink /etc/localtime | grep -q 'Asia/Shanghai'; then
  if [[ -f /usr/share/zoneinfo/Asia/Shanghai ]]; then
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime 2>/dev/null || \
      sudo ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime 2>/dev/null || \
      echo "提示：无法写入 /etc/localtime，请手动以 root 执行 ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime"
  fi
fi

cd "$ROOT_DIR"
if [[ ! -f .env ]]; then
  ./generate-env.sh .env
fi
mkdir -p data/mysql data/redis data/weaviate data/neo4j data/minio data/files
compose_cmd up -d

echo ""
echo "服务端安装完成。"
echo "前端地址: http://localhost:5173"
echo "后端地址: http://localhost:8080"
echo "停止命令: ./stop.sh"
echo "启动命令: ./start.sh"
EOF

cat > "$BUILD_ROOT/start.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
    return
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
    return
  fi
  echo "当前环境不可用 Docker Compose（docker compose / docker-compose）。"
  exit 1
}

cd "$ROOT_DIR"
compose_cmd up -d
echo "服务已启动。"
EOF

cat > "$BUILD_ROOT/stop.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
    return
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
    return
  fi
  echo "当前环境不可用 Docker Compose（docker compose / docker-compose）。"
  exit 1
}

cd "$ROOT_DIR"
compose_cmd down
echo "服务已停止。数据目录保留在 ./data"
EOF

cat > "$BUILD_ROOT/status.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
    return
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
    return
  fi
  echo "当前环境不可用 Docker Compose（docker compose / docker-compose）。"
  exit 1
}

cd "$ROOT_DIR"
compose_cmd ps
EOF

cat > "$BUILD_ROOT/docs/INSTALL.md" <<'EOF'
# 智能测试平台服务端安装说明

## 1. 目标

本安装包用于在一台服务器或开发机上一键拉起：

1. 前端静态站点
2. 主平台后端
3. MySQL
4. Redis
5. Weaviate
6. Neo4j
7. MinIO

## 2. 前置要求

只要求：

1. Docker
2. Docker Compose（`docker compose` 或 `docker-compose` 二选一即可）

## 3. 安装步骤

```bash
chmod +x install.sh start.sh stop.sh status.sh
./install.sh
```

`install.sh` 会顺带把宿主机时区切到 `Asia/Shanghai`（容器走 `/etc/localtime:ro`
复用宿主机时区，宿主机错了容器一定错）。

## 4. 默认地址

- 前端：http://localhost:5173
- 后端：http://localhost:8080
- Weaviate：http://localhost:8081
- Neo4j：http://localhost:7474
- MinIO：http://localhost:9001

## 5. 初始化管理员

首次打开前端后，先初始化管理员账号，再登录使用。

## 6. 目录说明

- `app/backend/aitest-backend.jar`：后端 Jar
- `app/frontend-dist/`：前端静态资源
- `data/`：数据库和文件持久化目录
- `.env`：安装时实际使用的配置

## 7. 启停

```bash
./start.sh
./stop.sh
./status.sh
```

## 8. CentOS 7 部署注意事项

### 8.1 yum 源失效（CentOS 7 已 EOL）

CentOS 7 已停止维护，官方源 `mirrorlist.centos.org` 已下线，会导致 `yum install docker` 报
`Could not retrieve mirrorlist`。先切到阿里云归档源：

```bash
sudo mv /etc/yum.repos.d/CentOS-Base.repo /etc/yum.repos.d/CentOS-Base.repo.backup
sudo curl -o /etc/yum.repos.d/CentOS-Base.repo https://mirrors.aliyun.com/repo/Centos-7.repo
sudo yum clean all && sudo yum makecache
```

之后再安装 Docker：

```bash
sudo yum install -y yum-utils
sudo yum-config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo
sudo yum install -y docker-ce docker-ce-cli containerd.io
sudo systemctl enable --now docker
```

### 8.2 时区

CentOS 7 默认 UTC，容器会跟着错 8 小时（授权码过期时间、轨迹时间、JVM
`Etc/UTC` 等都会受影响）。`install.sh` 会自动尝试用 `timedatectl set-timezone Asia/Shanghai`
和 `ln -sf` 修宿主机时区，所有容器通过挂载 `/etc/localtime:ro` 同步。

容器内额外保险：

- 所有服务都已带 `TZ=Asia/Shanghai` 环境变量
- backend 还带 `JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai`，避免 JVM 自检退回到 `Etc/UTC`

排查命令：

```bash
# 宿主机
date
readlink /etc/localtime

# 容器
docker exec aitest-server-backend date
docker exec aitest-server-backend java -XshowSettings:locale 2>&1 | grep timezone
docker exec aitest-server-mysql date
```

期望都是北京时间，JVM `default timezone` 应为 `Asia/Shanghai` 而不是 `Etc/UTC`。
EOF

chmod +x "$BUILD_ROOT/install.sh" "$BUILD_ROOT/start.sh" "$BUILD_ROOT/stop.sh" "$BUILD_ROOT/status.sh"

mkdir -p "$DIST_DIR"
cp -R "$BUILD_ROOT"/. "$DIST_DIR/"

tar -czf "$OUT_ROOT/$DIST_NAME.tar.gz" -C "$OUT_ROOT" "$DIST_NAME"

echo ""
echo "服务端安装包已生成："
echo "目录：$DIST_DIR"
echo "压缩包：$OUT_ROOT/$DIST_NAME.tar.gz"

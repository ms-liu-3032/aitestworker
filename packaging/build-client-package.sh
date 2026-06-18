#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_ROOT="$ROOT_DIR/release/client"
DIST_NAME="ai-test-worker-macos-package-$(date +%Y%m%d-%H%M%S)"
DIST_DIR="$OUT_ROOT/$DIST_NAME"
STAGE_DIR="$OUT_ROOT/.stage-worker"
WORKER_DIR="$ROOT_DIR/local-browser-worker"
CACHE_DIR="$OUT_ROOT/.cache"
PLAYWRIGHT_CACHE_SRC="$HOME/Library/Caches/ms-playwright"

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "缺少命令：$1"
    exit 1
  fi
}

echo "==> 检查打包依赖"
need_cmd node
need_cmd npm
need_cmd cp
need_cmd tar
need_cmd curl

# ---------------------------------------------------------------------------
# 确定 Node.js 版本与架构，下载官方 portable tarball
# ---------------------------------------------------------------------------
NODE_VERSION="$(node --version)"          # e.g. v22.22.3
NODE_ARCH="$(uname -m)"
case "$NODE_ARCH" in
  arm64) NODE_ARCH="arm64" ;;
  x86_64) NODE_ARCH="x64" ;;
  *)
    echo "不支持的架构：$NODE_ARCH"
    exit 1
    ;;
esac

NODE_TARBALL="node-${NODE_VERSION}-darwin-${NODE_ARCH}.tar.gz"
NODE_URL="https://nodejs.org/dist/${NODE_VERSION}/${NODE_TARBALL}"

mkdir -p "$CACHE_DIR"

if [[ ! -f "$CACHE_DIR/$NODE_TARBALL" ]]; then
  echo "==> 下载官方 Node.js 运行时：${NODE_TARBALL}"
  curl -fL --progress-bar -o "$CACHE_DIR/$NODE_TARBALL" "$NODE_URL"
else
  echo "==> 使用缓存的 Node.js 运行时：${NODE_TARBALL}"
fi

echo "==> 解压 Node.js 运行时"
rm -rf "$CACHE_DIR/node-extracted"
mkdir -p "$CACHE_DIR/node-extracted"
tar -xzf "$CACHE_DIR/$NODE_TARBALL" -C "$CACHE_DIR/node-extracted"
NODE_EXTRACTED_DIR="$(echo "$CACHE_DIR/node-extracted"/node-*)"

# ---------------------------------------------------------------------------
# 编译 worker
# ---------------------------------------------------------------------------
echo "==> 安装并编译 local-browser-worker"
(
  cd "$WORKER_DIR"
  [[ -d node_modules ]] || npm install
  npm run build
  has_ffmpeg=0
  shopt -s nullglob
  for candidate in "$PLAYWRIGHT_CACHE_SRC"/ffmpeg-*/ffmpeg-mac; do
    if [[ -x "$candidate" ]]; then
      has_ffmpeg=1
      break
    fi
  done
  shopt -u nullglob
  if [[ "$has_ffmpeg" -eq 0 ]]; then
    echo "==> 本地未发现 Playwright ffmpeg，自动安装"
    node node_modules/playwright-core/cli.js install ffmpeg
  fi
)

# ---------------------------------------------------------------------------
# 组装安装包
# ---------------------------------------------------------------------------
rm -rf "$STAGE_DIR" "$DIST_DIR"
mkdir -p "$STAGE_DIR/app" "$STAGE_DIR/runtime" "$STAGE_DIR/resources" "$STAGE_DIR/docs"

# 复制官方 Node.js 的 bin 和 lib（保留 rpath 结构：bin/node 可找到 ../lib/libnode.*.dylib）
cp -R "$NODE_EXTRACTED_DIR/bin" "$STAGE_DIR/runtime/bin"
cp -R "$NODE_EXTRACTED_DIR/lib" "$STAGE_DIR/runtime/lib"

cp -R "$WORKER_DIR/dist" "$STAGE_DIR/app/"
cp "$WORKER_DIR/package.json" "$STAGE_DIR/app/"

echo "==> 准备运行时依赖"
cp -R "$WORKER_DIR/node_modules" "$STAGE_DIR/app/"

if [[ -d "$PLAYWRIGHT_CACHE_SRC" ]]; then
  echo "==> 复制本地 Playwright Chromium 缓存"
  cp -R "$PLAYWRIGHT_CACHE_SRC" "$STAGE_DIR/resources/ms-playwright"
else
  echo "==> 未发现本地 Playwright Chromium 缓存，客户端将优先使用系统 Chrome"
fi

# ---------------------------------------------------------------------------
# 安装脚本
# ---------------------------------------------------------------------------
cat > "$STAGE_DIR/install.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

PACKAGE_DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALL_ROOT="$HOME/AI-Test-Worker"
DATA_DIR="$HOME/Library/Application Support/AI-Test-Worker"
CACHE_DIR="$HOME/Library/Caches/AI-Test-Worker"
PW_CACHE_DIR="$HOME/Library/Caches/ms-playwright"

mkdir -p "$INSTALL_ROOT" "$DATA_DIR" "$CACHE_DIR"
rm -rf "$INSTALL_ROOT/app" "$INSTALL_ROOT/runtime" "$INSTALL_ROOT/resources"
cp -R "$PACKAGE_DIR/app" "$INSTALL_ROOT/"
cp -R "$PACKAGE_DIR/runtime" "$INSTALL_ROOT/"
[[ -d "$PACKAGE_DIR/resources" ]] && cp -R "$PACKAGE_DIR/resources" "$INSTALL_ROOT/" || true

if [[ -d "$INSTALL_ROOT/resources/ms-playwright" ]]; then
  mkdir -p "$PW_CACHE_DIR"
  rsync -a "$INSTALL_ROOT/resources/ms-playwright"/ "$PW_CACHE_DIR"/
fi

ensure_ffmpeg() {
  local found=0
  shopt -s nullglob
  for candidate in "$PW_CACHE_DIR"/ffmpeg-*/ffmpeg-mac; do
    if [[ -x "$candidate" ]]; then
      found=1
      break
    fi
  done
  shopt -u nullglob
  if [[ "$found" -eq 0 ]]; then
    echo "==> 检测到缺少 Playwright ffmpeg，正在自动安装..."
    (
      cd "$INSTALL_ROOT/app"
      "$INSTALL_ROOT/runtime/bin/node" node_modules/playwright-core/cli.js install ffmpeg
    )
  fi
}

ensure_ffmpeg

cat > "$INSTALL_ROOT/start.command" <<CMD
#!/usr/bin/env bash
set -euo pipefail
INSTALL_ROOT="$INSTALL_ROOT"
NODE="\$INSTALL_ROOT/runtime/bin/node"
CONFIG="\$HOME/Library/Application Support/AI-Test-Worker/config.json"
PW_CACHE_DIR="\$HOME/Library/Caches/ms-playwright"

# 避免 Ctrl+Z 将采集器挂起后持续占用端口
trap '' TSTP

cd "\$INSTALL_ROOT/app"

ensure_ffmpeg() {
  local found=0
  shopt -s nullglob
  for candidate in "\$PW_CACHE_DIR"/ffmpeg-*/ffmpeg-mac; do
    if [[ -x "\$candidate" ]]; then
      found=1
      break
    fi
  done
  shopt -u nullglob
  if [[ "\$found" -eq 0 ]]; then
    echo ""
    echo "缺少 Playwright ffmpeg，正在自动安装..."
    "\$NODE" node_modules/playwright-core/cli.js install ffmpeg
    echo ""
  fi
}

# 首次启动：config.serverUrl 为空 → 提示用户输入并保存
if [[ ! -f "\$CONFIG" ]] || ! grep -q '"serverUrl": "http' "\$CONFIG"; then
  echo ""
  echo "首次启动，需要配置主平台服务器地址。"
  printf '%s' "请输入主平台地址（例如 http://192.168.1.10:8080，回车默认 http://localhost:8080）: "
  IFS= read -r URL
  URL="\${URL:-http://localhost:8080}"
  printf '%s' "请输入前端地址（用于页面检测本地采集器，可留空跳过）: "
  IFS= read -r FRONTEND_URL
  if [[ -n "\${FRONTEND_URL:-}" ]]; then
    "\$NODE" dist/cli/index.js set-server "\$URL" --frontend-url "\$FRONTEND_URL"
  else
    "\$NODE" dist/cli/index.js set-server "\$URL"
  fi
  echo ""
fi

ensure_ffmpeg

"\$NODE" dist/cli/index.js start &
CHILD_PID=\$!

forward_stop() {
  if kill -0 "\$CHILD_PID" 2>/dev/null; then
    kill -TERM "\$CHILD_PID" 2>/dev/null || true
    wait "\$CHILD_PID" || true
  fi
}

trap forward_stop EXIT INT TERM HUP
wait "\$CHILD_PID"
STATUS=\$?
trap - EXIT INT TERM HUP
exit "\$STATUS"
CMD

cat > "$INSTALL_ROOT/doctor.command" <<CMD
#!/usr/bin/env bash
cd "$INSTALL_ROOT/app"
exec "$INSTALL_ROOT/runtime/bin/node" dist/cli/index.js doctor
CMD

cat > "$INSTALL_ROOT/logs.command" <<CMD
#!/usr/bin/env bash
cd "$INSTALL_ROOT/app"
exec "$INSTALL_ROOT/runtime/bin/node" dist/cli/index.js logs
CMD

cat > "$INSTALL_ROOT/clean.command" <<CMD
#!/usr/bin/env bash
cd "$INSTALL_ROOT/app"
exec "$INSTALL_ROOT/runtime/bin/node" dist/cli/index.js clean
CMD

cat > "$INSTALL_ROOT/bind.command" <<'CMD'
#!/usr/bin/env bash
set -euo pipefail
INSTALL_ROOT="$HOME/AI-Test-Worker"
NODE="$INSTALL_ROOT/runtime/bin/node"
CONFIG="$HOME/Library/Application Support/AI-Test-Worker/config.json"

# 避免 Ctrl+Z 将采集器挂起后持续占用端口
trap '' TSTP

cd "$INSTALL_ROOT/app"

# 从命令行解析 --code / --server-url / --frontend-url
CODE=""
SERVER_URL=""
FRONTEND_URL=""
NEXT=""
for arg in "$@"; do
  if [[ -n "$NEXT" ]]; then
    case "$NEXT" in
      code) CODE="$arg" ;;
      server) SERVER_URL="$arg" ;;
      frontend) FRONTEND_URL="$arg" ;;
    esac
    NEXT=""
    continue
  fi
  case "$arg" in
    --code) NEXT="code" ;;
    --server-url|--server) NEXT="server" ;;
    --frontend-url|--frontend) NEXT="frontend" ;;
    --code=*) CODE="${arg#--code=}" ;;
    --server-url=*) SERVER_URL="${arg#--server-url=}" ;;
    --server=*) SERVER_URL="${arg#--server=}" ;;
    --frontend-url=*) FRONTEND_URL="${arg#--frontend-url=}" ;;
    --frontend=*) FRONTEND_URL="${arg#--frontend=}" ;;
  esac
done

# 没显式给 --server-url 时，始终弹问一次（默认回车 = 上次保存的地址）
if [[ -z "${SERVER_URL:-}" ]]; then
  CURRENT_URL=""
  if [[ -f "$CONFIG" ]]; then
    CURRENT_URL="$(sed -n 's/.*"serverUrl"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$CONFIG" | head -n1)"
  fi
  echo ""
  if [[ -n "${CURRENT_URL:-}" ]]; then
    printf '%s' "请输入主平台地址（回车使用上次的 ${CURRENT_URL:-}）: "
    IFS= read -r URL
    SERVER_URL="${URL:-$CURRENT_URL}"
  else
    printf '%s' "请输入主平台地址（例如 http://192.168.1.10:8080）: "
    IFS= read -r URL
    SERVER_URL="${URL:-http://localhost:8080}"
  fi
fi

if [[ -z "${FRONTEND_URL:-}" ]]; then
  CURRENT_FRONTEND_URL=""
  if [[ -f "$CONFIG" ]]; then
    CURRENT_FRONTEND_URL="$("$NODE" -e 'const fs=require("fs");const p=process.argv[1];try{const raw=JSON.parse(fs.readFileSync(p,"utf8"));const list=Array.isArray(raw.allowedOrigins)?raw.allowedOrigins:[];const first=list.find((item)=>typeof item==="string"&&item.trim());if(first)process.stdout.write(first);}catch{}' "$CONFIG")"
  fi
  if [[ -n "${CURRENT_FRONTEND_URL:-}" ]]; then
    printf '%s' "请输入前端地址（用于页面检测本地采集器，回车使用上次的 ${CURRENT_FRONTEND_URL:-}）: "
    IFS= read -r URL
    FRONTEND_URL="${URL:-$CURRENT_FRONTEND_URL}"
  else
    printf '%s' "请输入前端地址（用于页面检测本地采集器，例如 http://192.168.1.10:5173，可留空跳过）: "
    IFS= read -r URL
    FRONTEND_URL="${URL:-}"
  fi
fi

# 没传 --code 时也补一次提问
if [[ -z "${CODE:-}" ]]; then
  printf '%s' "请输入绑定码: "
  IFS= read -r CODE
fi

if [[ -n "${FRONTEND_URL:-}" ]]; then
  "$NODE" dist/cli/index.js set-server "$SERVER_URL" --frontend-url "$FRONTEND_URL"
else
  "$NODE" dist/cli/index.js set-server "$SERVER_URL"
fi
"$NODE" dist/cli/index.js bind --code "$CODE" ${FRONTEND_URL:+"--frontend-url"} ${FRONTEND_URL:+"$FRONTEND_URL"}

echo ""
echo "==> 绑定成功，正在启动采集器..."
exec "$INSTALL_ROOT/start.command"
CMD

chmod +x \
  "$INSTALL_ROOT/start.command" \
  "$INSTALL_ROOT/doctor.command" \
  "$INSTALL_ROOT/logs.command" \
  "$INSTALL_ROOT/clean.command" \
  "$INSTALL_ROOT/bind.command"

echo ""
echo "客户端安装完成。安装目录：$INSTALL_ROOT"
echo "建议先执行：$INSTALL_ROOT/doctor.command"
echo "绑定命令：$INSTALL_ROOT/bind.command"
echo "启动命令：$INSTALL_ROOT/start.command"
EOF

cat > "$STAGE_DIR/install.command" <<'EOF'
#!/usr/bin/env bash
cd "$(dirname "$0")"
exec ./install.sh
EOF

cat > "$STAGE_DIR/docs/INSTALL.md" <<'EOF'
# AI Test Worker 客户端安装说明（macOS）

## 1. 包含内容

1. Worker 运行时代码
2. Node Runtime（官方 portable 版本，自包含，无需系统预装 Node）
3. 本地命令封装
4. 可选 Playwright Chromium / ffmpeg 缓存

## 2. 安装

双击 `install.command`，或终端执行：

```bash
chmod +x install.sh install.command
./install.sh
```

安装目录：`~/AI-Test-Worker`

## 3. 日常使用

只需要一个命令：

```bash
~/AI-Test-Worker/start.command
```

或直接双击 `~/AI-Test-Worker/start.command`。

- 首次启动会提示输入主平台后端地址，例如 `http://192.168.1.10:8080`。
- 如需让内测/远端前端页面检测本地采集器，会继续提示输入前端访问地址，例如 `http://192.168.1.10:5173`。
- 后续启动不再询问，直接读取已保存的配置。
- 如本机缺少 Playwright `ffmpeg` 运行时，安装或首次启动时会自动补装，用于录屏能力。

## 4. 设备绑定

1. 启动 Worker 后，打开主平台 Web UI（例如 `http://192.168.1.10:5173`）
2. 在轨迹页面点击「生成绑定码」，UI 会给出一行命令，类似：

   ```bash
   ~/AI-Test-Worker/bind.command --code QLCUCJVZ
   ```

3. 新开一个终端，粘贴执行即可完成绑定；若当前前端不是 `localhost:5173`，脚本会额外询问前端访问地址并写入本地采集器白名单。
4. 绑定成功后，Worker 即可与主平台联动。

## 5. 其他命令

```bash
~/AI-Test-Worker/doctor.command   # 环境诊断
~/AI-Test-Worker/logs.command     # 查看日志
~/AI-Test-Worker/clean.command    # 清理缓存
```

## 6. 配置文件位置

- `~/Library/Application Support/AI-Test-Worker/config.json`

修改主平台地址：

```bash
~/AI-Test-Worker/runtime/bin/node ~/AI-Test-Worker/app/dist/cli/index.js set-server "http://新地址:8080"
```

连同前端访问地址一起保存：

```bash
~/AI-Test-Worker/runtime/bin/node ~/AI-Test-Worker/app/dist/cli/index.js set-server "http://后端地址:8080" --frontend-url "http://前端地址:5173"
```

## 7. 常见问题

### 绑定时报 `fetch failed` / `ECONNREFUSED`

worker 无法连接到主平台。检查：

1. 主平台后端是否已启动
2. 服务器地址 / IP / 端口是否拼写正确（特别留意末尾位数）
3. 客户端机器能否 ping 通服务器（防火墙、跨网段、VPN）

### 启动后浏览器没弹出

启动 Worker 不会自动开浏览器；浏览器是在主平台 UI 触发"打开身份空间"时由 Worker 拉起的。
EOF

chmod +x "$STAGE_DIR/install.sh" "$STAGE_DIR/install.command"

# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------
mkdir -p "$DIST_DIR"
cp -R "$STAGE_DIR"/. "$DIST_DIR/"
tar -czf "$OUT_ROOT/$DIST_NAME.tar.gz" -C "$OUT_ROOT" "$DIST_NAME"

echo ""
echo "客户端安装包已生成："
echo "目录：$DIST_DIR"
echo "压缩包：$OUT_ROOT/$DIST_NAME.tar.gz"

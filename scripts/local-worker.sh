#!/usr/bin/env zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CODE="${1:-}"

if [[ ! -d "$ROOT_DIR/local-browser-worker/dist" ]]; then
  echo "本地采集器尚未编译，正在编译..."
  (cd "$ROOT_DIR/local-browser-worker" && npm install && npm run build)
fi

if [[ -n "$CODE" ]]; then
  echo "使用绑定码绑定本地采集器..."
  (cd "$ROOT_DIR/local-browser-worker" && node dist/cli/index.js bind --code "$CODE" --server-url http://localhost:8080)
else
  echo "未提供绑定码，跳过绑定。"
  echo "如果尚未绑定，请先在前端“执行轨迹”页面生成绑定码，然后执行："
  echo "  ./scripts/local-worker.sh <绑定码>"
  echo ""
fi

echo "启动本地采集器。请不要关闭此窗口，关闭后采集器会停止。"
(cd "$ROOT_DIR/local-browser-worker" && node dist/cli/index.js start)

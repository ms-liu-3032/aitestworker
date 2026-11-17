#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PLUGIN_DIR="$ROOT_DIR/dsh-aitesthub"
OUT_DIR="$ROOT_DIR/release/dsh-plugin"

for command in node npm tar; do
  command -v "$command" >/dev/null 2>&1 || { echo "缺少命令：$command" >&2; exit 1; }
done
test -f "$PLUGIN_DIR/package.json" || { echo "缺少 DSH 插件源码" >&2; exit 1; }

version="$(node -p "require('$PLUGIN_DIR/package.json').version")"
package_name="aitesthub-dsh-plugin-$version.tgz"

(cd "$PLUGIN_DIR" && npm run package)
source_package="$PLUGIN_DIR/release/$package_name"
test -s "$source_package" || { echo "插件包未生成：$source_package" >&2; exit 1; }

for required in package/dist/index.js package/skills/aitesthub/SKILL.md package/node_modules/local-browser-worker/dist/index.js; do
  if ! tar -tzf "$source_package" | grep -Fxq "$required"; then
    echo "插件包缺少 $required" >&2
    exit 1
  fi
done

mkdir -p "$OUT_DIR"
cp "$source_package" "$OUT_DIR/$package_name"
cat > "$OUT_DIR/INSTALL.md" <<EOF
# AITestHub DSH 插件安装

插件包：$package_name

1. 安装本机 DeepSeek Harness Desktop，版本须满足插件包中的 \`engines.dsh\` 要求。
2. 在 DSH 插件页选择“添加插件”，选择此 \`.tgz\` 文件；也可使用 CLI：

   \`dsh plugin --profile desktop add /absolute/path/to/$package_name\`

3. 重启 DSH，在插件页确认 AITestHub 已启用。
4. 不连接 Hub 时，直接用自然语言要求“本地设计用例”或“本地执行 UI 自动化”，结果与报告只保存在本机。
5. 需要项目 TOM/Wiki、正式用例、审核或证据回写时，在 AITestHub Web 登录并生成一次性绑定码，再把页面给出的完整绑定命令复制到 DSH。服务地址由该命令指定，插件包不包含任何账号、绑定码或服务地址。

插件已包含 Skill 和浏览器采集代码，不需要另行启动本地 worker。浏览器执行需要本机可用的 Chrome 或 Playwright Chromium。
EOF

echo "DSH 插件交付包：$OUT_DIR/$package_name"
echo "安装说明：$OUT_DIR/INSTALL.md"

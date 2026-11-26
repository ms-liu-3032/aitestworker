#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PLUGIN_DIR="$ROOT_DIR/dsh-aitestwork"
OUT_DIR="$ROOT_DIR/release/dsh-plugin"

for command in node npm tar; do
  command -v "$command" >/dev/null 2>&1 || { echo "缺少命令：$command" >&2; exit 1; }
done
test -f "$PLUGIN_DIR/package.json" || { echo "缺少 DSH 插件源码" >&2; exit 1; }

version="$(node -p "require('$PLUGIN_DIR/package.json').version")"
package_name="aitestwork-dsh-plugin-$version.tgz"

(cd "$PLUGIN_DIR" && npm run package)
source_package="$PLUGIN_DIR/release/$package_name"
test -s "$source_package" || { echo "插件包未生成：$source_package" >&2; exit 1; }

for required in package/dist/index.js package/dist/tool-boundaries.js package/skills/aitestwork/SKILL.md package/node_modules/local-browser-worker/dist/index.js; do
  if ! tar -tzf "$source_package" | grep -Fxq "$required"; then
    echo "插件包缺少 $required" >&2
    exit 1
  fi
done

mkdir -p "$OUT_DIR"
cp "$source_package" "$OUT_DIR/$package_name"
cat > "$OUT_DIR/INSTALL.md" <<EOF
# AITestWork DSH 插件安装

插件包：$package_name

1. 安装本机 DeepSeek Harness Desktop，版本须满足插件包中的 \`engines.dsh\` 要求。
2. 在 DSH 插件页选择“添加插件”，选择此 \`.tgz\` 文件；也可使用 CLI：

   \`dsh plugin --profile desktop add /absolute/path/to/$package_name\`

3. 重启 DSH，在插件页确认 AITestWork 已启用。
4. 不连接 AITestWork 时，直接用自然语言要求“本地设计用例”或“本地执行 UI 自动化”，结果与报告只保存在本机。
   UI 映射支持 scroll 滚轮和 drag 指针拖拽；控件定位、位移和选中结果断言保存在对应自动化资产中。完成的 UI/API 报告会更新同一个本地报告首页。
5. 需要项目 TOM/Wiki、正式用例、审核或证据回写时，在 AITestWork Web 登录并生成一次性绑定码，再把页面给出的完整绑定命令复制到 DSH。服务地址由该命令指定，插件包不包含任何账号、绑定码或服务地址。

升级须先部署同版本后端与 Web，再安装插件、重新绑定并新开 DSH 会话。旧凭据和旧本地资产不会自动读取；迁移前请保留备份。

首次准备 AITestWork 用例时，DSH 会请使用者确认 TOM 模式；首次对某项目和目标站点执行 UI/API/本地测试或启动采集时，会请使用者确认执行范围。无审批服务、拒绝审批时执行不会开始。同一连接/项目/站点的执行许可最多 8 小时，换站点重新确认。

日志连接由用户提供完整平台根 URL（含路径），默认原样打开让平台自行跳转登录；可另提供完整同源 loginUrl。不推导被测域名或固定部署/登录路径。UI/API 日志结果回写须升级现有后端（Work V74）及 Web；凭据只在独立登录窗口输入。

插件已包含 Skill 和浏览器采集代码，不需要另行启动本地 worker。浏览器执行需要本机可用的 Chrome 或 Playwright Chromium。
EOF

echo "DSH 插件交付包：$OUT_DIR/$package_name"
echo "安装说明：$OUT_DIR/INSTALL.md"

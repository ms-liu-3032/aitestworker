#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_ROOT="$ROOT_DIR/release/client"
DIST_NAME="ai-test-worker-windows-package-$(date +%Y%m%d-%H%M%S)"
DIST_DIR="$OUT_ROOT/$DIST_NAME"
STAGE_DIR="$OUT_ROOT/.stage-worker-win"
WORKER_DIR="$ROOT_DIR/local-browser-worker"
CACHE_DIR="$OUT_ROOT/.cache"

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "缺少命令：$1"
    exit 1
  fi
}

echo "==> 检查打包依赖"
need_cmd node
need_cmd npm
need_cmd curl
need_cmd tar

# ---------------------------------------------------------------------------
# 确定 Node.js 版本，下载官方 Windows 运行时
# ---------------------------------------------------------------------------
NODE_VERSION="$(node --version)"          # e.g. v22.22.3
NODE_ZIP_NAME="node-${NODE_VERSION}-win-x64.zip"
NODE_URL="https://nodejs.org/dist/${NODE_VERSION}/${NODE_ZIP_NAME}"

mkdir -p "$CACHE_DIR"

if [[ ! -f "$CACHE_DIR/$NODE_ZIP_NAME" ]]; then
  echo "==> 下载 Windows Node 运行时：${NODE_ZIP_NAME}"
  curl -fL --progress-bar -o "$CACHE_DIR/$NODE_ZIP_NAME" "$NODE_URL"
else
  echo "==> 使用缓存的 Windows Node 运行时：${NODE_ZIP_NAME}"
fi

# 验证下载完整性（zip 文件至少应大于 10MB）
ZIP_SIZE=$(stat -f%z "$CACHE_DIR/$NODE_ZIP_NAME" 2>/dev/null || stat -c%s "$CACHE_DIR/$NODE_ZIP_NAME" 2>/dev/null || echo 0)
if [[ "$ZIP_SIZE" -lt 10000000 ]]; then
  echo "错误：下载的 Node.js zip 异常（${ZIP_SIZE} bytes），请删除缓存后重试"
  echo "缓存路径：$CACHE_DIR/$NODE_ZIP_NAME"
  exit 1
fi

echo "==> 解压 Windows Node 运行时"
NODE_EXTRACT_DIR="$CACHE_DIR/node-win-extracted"
rm -rf "$NODE_EXTRACT_DIR"
mkdir -p "$NODE_EXTRACT_DIR"

# 使用 macOS 自带 unzip 解压（brew 安装的 unzip 也可用）
if command -v unzip >/dev/null 2>&1; then
  unzip -qo "$CACHE_DIR/$NODE_ZIP_NAME" -d "$NODE_EXTRACT_DIR"
else
  # 回退到 python3
  python3 -c "
import zipfile
zipfile.ZipFile('$CACHE_DIR/$NODE_ZIP_NAME').extractall('$NODE_EXTRACT_DIR')
"
fi

NODE_SRC_DIR="$NODE_EXTRACT_DIR/node-${NODE_VERSION}-win-x64"
if [[ ! -f "$NODE_SRC_DIR/node.exe" ]]; then
  echo "错误：解压后未找到 node.exe，路径：$NODE_SRC_DIR"
  exit 1
fi

echo "==> Windows Node 运行时：$(ls -lh "$NODE_SRC_DIR/node.exe" | awk '{print $5}')"

# ---------------------------------------------------------------------------
# 编译 worker
# ---------------------------------------------------------------------------
echo "==> 安装并编译 local-browser-worker"
(
  cd "$WORKER_DIR"
  [[ -d node_modules ]] || npm install
  npm run build
)

# ---------------------------------------------------------------------------
# 组装安装包目录
# ---------------------------------------------------------------------------
rm -rf "$STAGE_DIR" "$DIST_DIR"
mkdir -p "$STAGE_DIR/app" "$STAGE_DIR/runtime/node" "$STAGE_DIR/docs"

# 复制 worker 产物
cp -R "$WORKER_DIR/dist" "$STAGE_DIR/app/"
cp "$WORKER_DIR/package.json" "$STAGE_DIR/app/"
cp -R "$WORKER_DIR/node_modules" "$STAGE_DIR/app/"

# 复制 Windows Node 运行时（node.exe + npm/npx/cmd + node_modules/npm）
cp -R "$NODE_SRC_DIR"/. "$STAGE_DIR/runtime/node/"

echo "==> Node 运行时文件："
ls "$STAGE_DIR/runtime/node/node.exe"

# ---------------------------------------------------------------------------
# 辅助函数：写入 CRLF 换行的 Windows 批处理文件
# ---------------------------------------------------------------------------
write_bat() {
  local dest="$1"
  # 将 LF 转为 CRLF 写入
  perl -pe 's/\n/\r\n/' > "$dest"
  echo "  -> $(basename "$dest")"
}

# ---------------------------------------------------------------------------
# 生成各个 .bat 文件（由 bash 直接生成，不做嵌套）
# ---------------------------------------------------------------------------
echo "==> 生成 Windows 命令脚本"

write_bat "$STAGE_DIR/install.bat" <<'BAT'
@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

set "PACKAGE_DIR=%~dp0"
set "INSTALL_ROOT=%USERPROFILE%\AI-Test-Worker"
set "APPDATA_DIR=%APPDATA%\AI-Test-Worker"
set "CACHE_DIR=%LOCALAPPDATA%\AI-Test-Worker"

echo.
echo AI Test Worker - 客户端安装
echo 安装目录: %INSTALL_ROOT%
echo.

if not exist "%INSTALL_ROOT%" mkdir "%INSTALL_ROOT%"
if not exist "%APPDATA_DIR%" mkdir "%APPDATA_DIR%"
if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"

if exist "%INSTALL_ROOT%\app" rmdir /s /q "%INSTALL_ROOT%\app"
if exist "%INSTALL_ROOT%\runtime" rmdir /s /q "%INSTALL_ROOT%\runtime"

echo 正在复制文件...
xcopy "%PACKAGE_DIR%app" "%INSTALL_ROOT%\app\" /e /i /y >nul
xcopy "%PACKAGE_DIR%runtime" "%INSTALL_ROOT%\runtime\" /e /i /y >nul

REM 同时把 start / bind / doctor / logs / clean 等命令脚本复制到安装目录
copy /y "%PACKAGE_DIR%start.bat" "%INSTALL_ROOT%\start.bat" >nul
copy /y "%PACKAGE_DIR%start.ps1" "%INSTALL_ROOT%\start.ps1" >nul
copy /y "%PACKAGE_DIR%bind.bat" "%INSTALL_ROOT%\bind.bat" >nul
copy /y "%PACKAGE_DIR%doctor.bat" "%INSTALL_ROOT%\doctor.bat" >nul
copy /y "%PACKAGE_DIR%logs.bat" "%INSTALL_ROOT%\logs.bat" >nul
copy /y "%PACKAGE_DIR%clean.bat" "%INSTALL_ROOT%\clean.bat" >nul

echo.
echo 客户端安装完成。
echo.
echo 下一步：执行 "%INSTALL_ROOT%\start.bat" 启动采集器。
echo 首次启动会提示输入主平台后端地址，并可选保存前端访问地址。
echo.

pause
BAT

# start.bat
write_bat "$STAGE_DIR/start.bat" <<'BAT'
@echo off
chcp 65001 >nul
setlocal
set "INSTALL_ROOT=%USERPROFILE%\AI-Test-Worker"
if not exist "%INSTALL_ROOT%\start.ps1" (
  echo 缺少 PowerShell 启动脚本：%INSTALL_ROOT%\start.ps1
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%INSTALL_ROOT%\start.ps1"
pause
BAT

# start.ps1
cat > "$STAGE_DIR/start.ps1" <<'PS1'
$ErrorActionPreference = 'Stop'

$installRoot = Join-Path $env:USERPROFILE 'AI-Test-Worker'
$node = Join-Path $installRoot 'runtime\node\node.exe'
$config = Join-Path $env:APPDATA 'AI-Test-Worker\config.json'
$appDir = Join-Path $installRoot 'app'

Set-Location $appDir

if (-not (Test-Path $config) -or -not (Select-String -Path $config -Pattern '"serverUrl"\s*:\s*"http' -Quiet -ErrorAction SilentlyContinue)) {
  Write-Host ''
  Write-Host '首次启动，需要配置主平台服务器地址。'
  $url = Read-Host '请输入主平台后端地址（例如 http://192.168.1.10:8080，回车默认 http://localhost:8080）'
  if ([string]::IsNullOrWhiteSpace($url)) { $url = 'http://localhost:8080' }
  $frontendUrl = Read-Host '请输入前端地址（用于页面检测本地采集器，可留空跳过）'
  if (-not [string]::IsNullOrWhiteSpace($frontendUrl)) {
    & $node 'dist\cli\index.js' 'set-server' $url '--frontend-url' $frontendUrl
  } else {
    & $node 'dist\cli\index.js' 'set-server' $url
  }
  Write-Host ''
}

$child = Start-Process -FilePath $node -ArgumentList @('dist\cli\index.js', 'start') -WorkingDirectory $appDir -NoNewWindow -PassThru
$cleanup = {
  if ($script:child -and -not $script:child.HasExited) {
    try { Stop-Process -Id $script:child.Id -Force -ErrorAction SilentlyContinue } catch {}
  }
}
$script:child = $child
$null = Register-EngineEvent PowerShell.Exiting -Action $cleanup
Wait-Process -Id $child.Id
exit $child.ExitCode
PS1

# doctor.bat
write_bat "$STAGE_DIR/doctor.bat" <<'BAT'
@echo off
chcp 65001 >nul
set "INSTALL_ROOT=%USERPROFILE%\AI-Test-Worker"
cd /d "%INSTALL_ROOT%\app"
"%INSTALL_ROOT%\runtime\node\node.exe" dist\cli\index.js doctor
pause
BAT

# logs.bat
write_bat "$STAGE_DIR/logs.bat" <<'BAT'
@echo off
chcp 65001 >nul
set "INSTALL_ROOT=%USERPROFILE%\AI-Test-Worker"
cd /d "%INSTALL_ROOT%\app"
"%INSTALL_ROOT%\runtime\node\node.exe" dist\cli\index.js logs
pause
BAT

# clean.bat
write_bat "$STAGE_DIR/clean.bat" <<'BAT'
@echo off
chcp 65001 >nul
set "INSTALL_ROOT=%USERPROFILE%\AI-Test-Worker"
cd /d "%INSTALL_ROOT%\app"
"%INSTALL_ROOT%\runtime\node\node.exe" dist\cli\index.js clean
pause
BAT

# bind.bat —— 始终先问主平台地址（除非 --server-url 已给出），绑定成功后自动 start
write_bat "$STAGE_DIR/bind.bat" <<'BAT'
@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
set "INSTALL_ROOT=%USERPROFILE%\AI-Test-Worker"
set "NODE=%INSTALL_ROOT%\runtime\node\node.exe"
set "CONFIG=%APPDATA%\AI-Test-Worker\config.json"
cd /d "%INSTALL_ROOT%\app"

REM 解析参数：--code、--server-url、--frontend-url
set "CODE="
set "SERVER_URL="
set "FRONTEND_URL="
set "NEXT="
for %%a in (%*) do (
  if defined NEXT (
    if "!NEXT!"=="code" set "CODE=%%~a"
    if "!NEXT!"=="server" set "SERVER_URL=%%~a"
    if "!NEXT!"=="frontend" set "FRONTEND_URL=%%~a"
    set "NEXT="
  ) else (
    if /I "%%~a"=="--code" set "NEXT=code"
    if /I "%%~a"=="--server-url" set "NEXT=server"
    if /I "%%~a"=="--server" set "NEXT=server"
    if /I "%%~a"=="--frontend-url" set "NEXT=frontend"
    if /I "%%~a"=="--frontend" set "NEXT=frontend"
  )
)

REM 没显式给 --server-url 时，始终弹问一次（默认回车 = 上次保存的地址）
if not defined SERVER_URL (
  set "CURRENT_URL="
  if exist "%CONFIG%" (
    for /f "usebackq delims=" %%u in (`powershell -NoProfile -Command "try { (Get-Content -Raw -ErrorAction Stop '%CONFIG%' ^| ConvertFrom-Json).serverUrl } catch {}"`) do set "CURRENT_URL=%%u"
  )
  echo.
  if defined CURRENT_URL (
    set /p "URL=请输入主平台地址（回车使用上次的 !CURRENT_URL!）: "
    if not defined URL set "URL=!CURRENT_URL!"
  ) else (
    set /p "URL=请输入主平台地址（例如 http://192.168.1.10:8080）: "
    if not defined URL set "URL=http://localhost:8080"
  )
  set "SERVER_URL=!URL!"
)

if not defined FRONTEND_URL (
  set "CURRENT_FRONTEND_URL="
  if exist "%CONFIG%" (
    for /f "usebackq delims=" %%u in (`powershell -NoProfile -Command "try { $raw = Get-Content -Raw -ErrorAction Stop '%CONFIG%' ^| ConvertFrom-Json; $list = @($raw.allowedOrigins); $first = $list ^| Where-Object { $_ -is [string] -and $_.Trim() } ^| Select-Object -First 1; if ($first) { $first } } catch {}"`) do set "CURRENT_FRONTEND_URL=%%u"
  )
  if defined CURRENT_FRONTEND_URL (
    set /p "URL=请输入前端地址（用于页面检测本地采集器，回车使用上次的 !CURRENT_FRONTEND_URL!）: "
    if not defined URL set "URL=!CURRENT_FRONTEND_URL!"
  ) else (
    set /p "URL=请输入前端地址（用于页面检测本地采集器，例如 http://192.168.1.10:5173，可留空跳过）: "
  )
  if defined URL set "FRONTEND_URL=!URL!"
)

REM 没传 --code 时也补一次提问
if not defined CODE (
  set /p "CODE=请输入绑定码: "
)

if defined FRONTEND_URL (
  "%NODE%" dist\cli\index.js set-server "!SERVER_URL!" --frontend-url "!FRONTEND_URL!"
) else (
  "%NODE%" dist\cli\index.js set-server "!SERVER_URL!"
)
if defined FRONTEND_URL (
  "%NODE%" dist\cli\index.js bind --code "!CODE!" --frontend-url "!FRONTEND_URL!"
) else (
  "%NODE%" dist\cli\index.js bind --code "!CODE!"
)
if errorlevel 1 (
  pause
  exit /b 1
)

echo.
echo ==^> 绑定成功，正在启动采集器...
"%INSTALL_ROOT%\start.bat"
pause
BAT

# install.ps1 (PowerShell 入口)
cat > "$STAGE_DIR/install.ps1" <<'PS1'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
cmd /c "`"$scriptDir\install.bat`""
PS1

# ---------------------------------------------------------------------------
# 文档
# ---------------------------------------------------------------------------
cat > "$STAGE_DIR/docs/INSTALL.md" <<'EOF'
# AI Test Worker Windows 客户端安装说明

## 1. 包含内容

1. Worker 运行时代码
2. Windows Node Runtime（官方 portable 版，v22.x，无需系统预装 Node）
3. 本地命令封装（start / bind / doctor / logs / clean）

## 2. 安装

双击 `install.bat`。安装完成后，`%USERPROFILE%\AI-Test-Worker` 下会有：

- `start.bat`、`bind.bat`、`doctor.bat`、`logs.bat`、`clean.bat`
- `app\`（worker 代码）、`runtime\node\`（Node Runtime）

## 3. 日常使用

只需要一个命令：

```bat
%USERPROFILE%\AI-Test-Worker\start.bat
```

或直接双击。

- 首次启动会提示输入主平台后端地址，例如 `http://192.168.1.10:8080`。
- 如需让内测/远端前端页面检测本地采集器，会继续提示输入前端访问地址，例如 `http://192.168.1.10:5173`。
- 后续启动不再询问，直接读取已保存的配置。

## 4. 设备绑定

1. 启动 Worker 后，浏览器打开主平台 Web UI（例如 `http://192.168.1.10:5173`）。
2. 在轨迹页面点击「生成绑定码」，UI 会给出一行命令，类似：

   ```bat
   "%USERPROFILE%\AI-Test-Worker\bind.bat" --code QLCUCJVZ
   ```

3. 新开一个命令行（cmd / PowerShell），粘贴执行即可完成绑定；若当前前端不是 `localhost:5173`，脚本会额外询问前端访问地址并写入本地采集器白名单。
4. 绑定成功后，Worker 即可与主平台联动。

## 5. 其他命令

| 命令 | 用途 |
|------|------|
| `doctor.bat` | 环境诊断 |
| `logs.bat` | 查看日志 |
| `clean.bat` | 清理缓存 |

## 6. 配置文件位置

- `%APPDATA%\AI-Test-Worker\config.json`

修改主平台地址：

```bat
"%USERPROFILE%\AI-Test-Worker\runtime\node\node.exe" "%USERPROFILE%\AI-Test-Worker\app\dist\cli\index.js" set-server "http://新地址:8080"
```

连同前端访问地址一起保存：

```bat
"%USERPROFILE%\AI-Test-Worker\runtime\node\node.exe" "%USERPROFILE%\AI-Test-Worker\app\dist\cli\index.js" set-server "http://后端地址:8080" --frontend-url "http://前端地址:5173"
```

## 7. 常见问题

### 安装时窗口出现乱码

所有 `.bat` 已经在脚本顶部 `chcp 65001 >nul`，把 cmd 切到 UTF-8 codepage 再解析。
如果仍然乱码，请确认操作系统是 Windows 10 1809 及以上（自带的 cmd 才稳定支持 UTF-8）。

### 绑定时报 `fetch failed` / `ECONNREFUSED`

worker 无法连接到主平台。检查：

1. 主平台后端是否已启动
2. 服务器地址 / IP / 端口是否拼写正确
3. 客户端机器能否访问该地址（防火墙、跨网段、VPN）

### node.exe 提示缺少 VCRUNTIME

安装 Visual C++ Redistributable：

```
https://aka.ms/vs/17/release/vc_redist.x64.exe
```

## 8. 注意事项

1. Windows 10 / Windows 11 x64 均可使用
2. 优先使用系统已安装的 Chrome
3. 绑定前需要在主平台生成绑定码
4. 启动后请保持命令窗口打开
EOF

# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------
mkdir -p "$DIST_DIR"
cp -R "$STAGE_DIR"/. "$DIST_DIR/"

tar -czf "$OUT_ROOT/$DIST_NAME.tar.gz" -C "$OUT_ROOT" "$DIST_NAME"

echo ""
echo "Windows 客户端安装包已生成："
echo "目录：  $DIST_DIR"
echo "压缩包：$OUT_ROOT/$DIST_NAME.tar.gz"
echo ""
echo "包内内容："
find "$DIST_DIR" -maxdepth 2 -not -path '*/node_modules/*' -not -path '*/dist/*' | sort

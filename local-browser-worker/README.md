# local-browser-worker

AI 测试工作者 - 本地浏览器测试执行轨迹采集器。

## 简介

`local-browser-worker` 是一个 Node.js + TypeScript CLI 工具，运行在测试人员本地机器上，负责：

- 自动检测本地浏览器环境
- 通过 Playwright 控制 Chrome 浏览器
- 采集测试执行过程中的操作轨迹、网络请求、截图
- 将采集数据上传到 AI 测试平台

## 环境要求

- Node.js >= 18.0.0（推荐 20 LTS）
- npm >= 9.0.0
- Chrome 浏览器 或 Playwright Chromium

## 安装

```bash
cd local-browser-worker
npm install
npm run build
```

## 命令

| 命令 | 说明 |
|------|------|
| `ai-test-worker install` | 运行安装向导 |
| `ai-test-worker install --cn` | 使用中国区镜像安装 |
| `ai-test-worker doctor` | 检查本地环境 |
| `ai-test-worker start` | 启动采集服务 |
| `ai-test-worker bind --code <码>` | 绑定到平台服务器 |
| `ai-test-worker unbind` | 解除绑定 |
| `ai-test-worker clean` | 清理本地缓存 |
| `ai-test-worker logs` | 查看服务日志 |

## 开发

```bash
npm install        # 安装依赖
npm run build      # 编译 TypeScript
node dist/cli/index.js <command>  # 运行命令
```

## 目录结构

```
src/
  cli/             # CLI 入口和命令定义
    index.ts       # 主入口
    commands/      # 各命令实现
  config/          # 配置模块
  doctor/          # 环境检查模块
  platform/        # 平台检测模块
  browser/         # 浏览器管理模块
  security/        # 安全模块
  utils/           # 工具函数
```

## 许可

Private

# 打包与安装说明

本目录负责两件事：

1. 生成服务端一键安装包
2. 生成客户端一键安装包

## 目录说明

- [build-server-package.sh](./build-server-package.sh)
  - 生成服务端安装包
  - 包含后端 Jar、前端静态资源、Docker Compose、MySQL/Redis/Weaviate/Neo4j/MinIO 依赖编排
- [build-client-package.sh](./build-client-package.sh)
  - 生成 macOS 客户端安装包
  - 包含 local-browser-worker、运行时 Node、命令封装、可选 Playwright Chromium 缓存
- [build-client-package-windows.sh](./build-client-package-windows.sh)
  - 生成 Windows 客户端安装包
  - 包含 local-browser-worker、Windows Node Runtime、命令封装

## 输出目录

默认输出到：

`release/`

## 打包顺序

### 服务端

```bash
cd ..
./packaging/build-server-package.sh
```

### 客户端

```bash
cd ..
./packaging/build-client-package.sh
```

### 客户端（Windows）

```bash
cd ..
./packaging/build-client-package-windows.sh
```

## 当前策略

### 服务端

使用 Docker Compose 作为一键安装底座：

1. MySQL
2. Redis
3. Weaviate
4. Neo4j
5. MinIO
6. 后端
7. Nginx（前端静态站点）

优点：

- 安装简单
- 依赖完整
- 数据库与依赖一致性高

### 客户端

使用“自带 Node Runtime + Worker 产物 + 命令封装”的方式：

1. 不依赖本机预装 Node
2. 不依赖本机再执行 npm install
3. 绑定、启动、doctor、日志、清理都有现成命令
4. 当前已支持 macOS 和 Windows x64 两套客户端交付

## 交付标准

打包脚本提交前至少保证：

1. 能生成产物目录
2. 能生成压缩包
3. 包内有安装说明
4. 包内脚本有执行权限

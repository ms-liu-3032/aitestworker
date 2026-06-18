# AI Test Worker

通用 AI 驱动的智能测试平台：从轨迹采集、清洗、摘要、语义沉淀到用例生成的完整闭环。

平台默认启动时为纯通用内核，不绑定任何特定业务。业务能力通过项目使用过程中的 TOM、页面画像、轨迹摘要、规则包和 business pack 自动沉淀并持续演化。

## 核心能力

### 轨迹采集与清洗

- 执行轨迹采集器：身份空间、采集组、浏览器打开、事件采集、网络摘要。
- 通用轨迹清洗引擎：基于 JSON 规则包的输入、变更、点击描述规范化。
- 工作流处理器：对话输入确认流、入口导航提交流等可扩展流程。
- 轨迹规则包：默认不加载业务样例，支持全局 / 项目级 `trace_rule_pack_config` 数据库配置；classpath JSON 仅作为兼容模式。

### 语义沉淀

- 项目语义上下文：从 TOM、页面画像、步骤模板、业务摘要、business pack 自动构建。
- 语义包快照：`semantic_pack` / `semantic_pack_item` 自动刷新。
- 业务包（Business Pack）：
  - 自动沉淀：从项目资产自动生成 draft。
  - 生命周期：DRAFT → ACTIVE → INACTIVE → ARCHIVED。
  - 来源追溯：条目可追溯到 TOM、页面画像、轨迹摘要等项目资产。
  - 绑定关系：与规则引擎、扫描、TOM、语义包正式关联。
  - 版本快照：每次状态变更自动记录。
  - 包间关系：支持依赖、包含、联动、补充。
  - 消费记录：追踪哪些链路消费了业务包。
  - 自动刷新诊断：记录自动沉淀成功/失败、输入资产规模、生成包和推断关系数量。
  - 可视化：前端可查看来源构成、来源跳转、绑定覆盖、消费链路、刷新诊断和关系图谱。

### 测试对象模型（TOM）

- 候选抽取：从轨迹摘要、使用手册、测试资产自动抽取。
- 人工确认：候选 → 生效 → 弃用 → 恢复完整生命周期。
- 测试范围分析：基于 TOM 的需求影响分析，支持 LLM 语义匹配。
- 项目级 / 系统级：支持 TOM 升级为系统级共享资产。

### 用例生成

- 对话式生成工作台：需求输入 → 需求分析 → 草稿生成 → 本地整理 → 正式库。
- 分析证据链：需求分析会召回 TOM、页面画像、business pack、轨迹摘要，并写入 `evidence_summary`。
- 测试点来源：测试点带 `source_basis`、`source_refs`、`coverage_status`、`unsupported_items`。
- 草稿追溯：草稿写入 `source_refs_json`，包含分析版本、证据摘要、来源测试点、逐条生成依据和 quality gate。
- 逐条质量门禁：
  - `PASS`：证据和结构通过。
  - `PARTIAL`：部分覆盖或存在非阻塞警告。
  - `LOW_EVIDENCE`：证据不足、结构异常或需要人工确认。
- 确定性校验：
  - 来源测试点必须匹配当前分析测试点。
  - 生成依据必须命中当前项目 evidence summary。
  - 步骤数和预期结果数会自动对齐检查。
  - 空步骤、空预期、默认模块、模型原文兜底草稿会降级为低证据。
- 前端可视化：分析面板展示证据命中；草稿列表展示证据数量和质量状态；草稿详情展示来源证据链、本条用例依据、置信度、未支持项、步骤/预期数量。

### 页面扫描

- 配置化扫描源：支持全局 / 项目级扫描源配置；项目页只管理项目级扫描源，全局源只读复用。
- URL 链接扫描：输入页面链接自动提取页面画像。
- 扫描摘要：LLM 驱动的页面画像结构化摘要。
- 管理界面：支持创建、编辑、启停、删除项目级扫描源和项目级轨迹规则包；全局配置在项目页只读展示。

### 管理后台

- 用户管理：创建、检索、统计。
- 模型配置：多模型、多提供商、编辑、删除。
- 提示词库：版本管理、审核、内容详情。
- TOM 管理：候选审核、批量操作、引用查询。
- 资产管理：草稿 / 正式用例、Skill / Tool 模板。

## 快速开始

### Docker Compose（推荐）

```bash
# 启动基础服务
docker compose up -d

# 构建并打包
cd packaging
./build-server-package.sh

# 启动完整平台
cd ../release/server/<最新包目录>
./install.sh
```

启动后访问：

- 前端：`http://localhost:35173`
- 后端：`http://localhost:38080`

### 本地开发

```bash
# 启动基础服务
docker compose up -d mysql redis weaviate neo4j minio

# 后端
cd backend
mvn spring-boot:run

# 前端
cd frontend-react
npm install
npm run dev
```

默认前端地址：`http://localhost:5173`

## 脚本说明

| 脚本 | 用途 |
|------|------|
| `scripts/local-run.sh` | 一键启动本地开发环境（前端 + 后端） |
| `scripts/local-start.sh` | 启动后端服务 |
| `scripts/local-stop.sh` | 停止后端服务 |
| `scripts/local-worker.sh` | 启动采集器 worker |
| `packaging/build-server-package.sh` | 构建服务器发布包 |
| `packaging/build-client-package.sh` | 构建客户端发布包（macOS） |
| `packaging/build-client-package-windows.sh` | 构建客户端发布包（Windows） |

## 架构

```text
backend/          Spring Boot 3.x 后端
frontend-react/   React 18 + TypeScript + Vite 前端
packaging/        打包与部署脚本
scripts/          开发与运维脚本
docs/             项目文档
```

### 技术栈

- 后端：Spring Boot 3.3.5 + MySQL + Flyway + JDBC
- 前端：React 18 + TypeScript + Tailwind CSS + Vite
- LLM：OpenAI 兼容 API（同步调用）
- 文档解析：PDFBox 3.0.3 + POI 5.2.5

### 数据库

Flyway 迁移文件位于 `backend/src/main/resources/db/migration/`，按版本号递增管理。

## 业务接入

平台不依赖 Java 硬编码业务包类。新业务接入方式：

1. **创建项目**：在项目大厅新建项目。
2. **导入资料**：上传使用手册、导入页面链接。
3. **采集轨迹**：通过采集器录制真实操作轨迹。
4. **自动沉淀**：系统根据项目资产自动生成 business pack draft。
5. **人工审核**：审核业务包、调整条目、激活后进入主链路。
6. **生成用例**：通过 TOM / business pack / 页面画像 / 轨迹摘要约束分析和用例生成。
7. **持续演化**：后续使用过程中持续更新 TOM、规则包和 business pack。

## 开发验证

```bash
# 后端编译
cd backend && mvn -q -DskipTests compile

# 后端测试
cd backend && mvn test

# 前端构建
cd frontend-react && npm run build
```

## 开源说明

- 默认运行态不包含特定业务规则包、业务扫描源或业务域 provider。
- 新业务不需要新增 Java 业务包类。
- TOM、规则包、扫描源和 business pack 均以项目资产或数据库配置方式沉淀。

## 开源协议

本项目采用 [MIT License](LICENSE) 开源协议。

# 受控智能 AI 测试平台

一个基于 Playwright 轨迹采集、Mini-TOM 测试对象模型、LLM Wiki 知识包、项目语义上下文和大语言模型的智能测试平台，用于把真实操作、页面结构、业务规则和测试经验沉淀为可追溯资产，并在需求分析、测试点拆解与用例草稿生成链路中持续复用这些证据。

平台默认启动时为纯通用内核，不绑定任何特定业务。业务能力通过项目使用过程中自动沉淀的 business pack 持续演化。

## 快速开始

### Docker Compose（推荐）

```bash
cd packaging
./build-server-package.sh
cd ../release/server/<最新包目录>
./install.sh
```

服务器打包脚本会先执行后端 `mvn clean package`，并检查 Jar 中是否存在重复的 Flyway 版本；请勿用未清理 `target` 的增量 Jar 覆盖生产环境。

安装包首次执行 `install.sh` 时会生成仅当前部署使用的 `.env` 密钥文件，并设置为仅当前用户可读；不要把 `.env` 提交到仓库或复制到聊天、工单和日志中。源码方式启动前，请先复制 `.env.example` 为 `.env` 并替换所有占位值。

启动后访问：

- 前端：`http://localhost:35173`
- 后端：`http://localhost:38080`

### 本地开发

```bash
# 后端
docker compose up -d mysql redis weaviate neo4j minio
cd backend
mvn spring-boot:run

# 前端
cd frontend-react
npm install
npm run dev
```

## 核心能力

### 轨迹采集与清洗
- 执行轨迹采集器：身份空间、采集组、Chrome 打开、事件采集、网络摘要
- 通用轨迹清洗引擎：基于 JSON 规则包的输入/变更/点击描述规范化
- 工作流处理器：对话输入确认流、入口导航提交流等可扩展流程
- 轨迹规则包：默认不加载业务样例，支持全局 / 项目级 `trace_rule_pack_config` 数据库配置，classpath JSON 仅作为兼容模式

### 语义沉淀
- 项目语义上下文：从 TOM、页面画像、步骤模板、业务摘要自动构建
- 语义包快照：`semantic_pack` / `semantic_pack_item` 自动刷新
- LLM Wiki：上传资料、已确认需求分析、澄清结论、已确认测试点和 Loop 回灌统一进入项目级待审核知识候选；候选条目与知识包均审核通过并激活后，才进入原有项目语义上下文消费链路
- Wiki 三层管理：项目工作区的“知识库”只维护本项目知识；管理后台“正式测试资产库 → Wiki”统一查看和管理项目级、跨项目复用级、系统级知识包，并完成条目审核、知识包审核和启停控制
- 自动沉淀治理：候选带来源类型、来源对象、内容摘要和版本号；同一内容不会重复沉淀，内容变化形成新版本，失败任务可追踪并自动重试
- 业务包（Business Pack）：从项目资产自动生成，支持：
  - 自动沉淀：项目使用过程中持续演化
  - 生命周期：DRAFT → ACTIVE → INACTIVE → ARCHIVED
  - 来源追溯：每个条目可追溯到 TOM、页面画像、轨迹摘要等项目资产
  - 绑定关系：与规则引擎、扫描、TOM、语义包正式关联
  - 版本快照：每次状态变更自动记录快照
  - 包间关系：支持依赖、包含、联动、补充关系
  - 消费记录：追踪哪些链路消费了业务包
  - 自动刷新诊断：记录自动沉淀成功/失败、输入资产规模、生成包和推断关系数量
  - 可视化：前端可查看来源构成、来源跳转、绑定覆盖、消费链路、刷新诊断和关系图谱

### 测试对象模型（TOM）
- 候选抽取：从轨迹摘要、使用手册、测试资产自动抽取
- 需求资产回灌：人工确认后的需求原子与测试点自动形成项目级 TOM 候选；新版本确认后同候选旧版本自动退役
- 人工确认：候选 → 生效 → 弃用 → 恢复完整生命周期
- 测试范围分析：基于 TOM 的需求影响分析，支持 LLM 语义匹配
- 项目级 / 系统级：支持 TOM 升级为系统级共享资产

### 用例生成
- 对话式生成工作台：需求输入 → 分析 → 草稿生成 → 本地整理 → 正式库
- 草稿治理：编辑、确认、弃用、提交正式库完整链路
- 来源追溯：需求生成 / 轨迹回放双来源可见
- LLM 调用治理：统一网关、错误分类、超时/重试、JSON 清洗、调用日志、异步生成任务和重复点击幂等保护

### 需求分析与用例生成主链路

平台在"对话式用例生成工作台"中实现了完整的分析-澄清-生成闭环：

**流程**：需求输入 → 需求理解 → 风险扫描 → 需要澄清 → 用户补充 → 重新分析 → 测试点拆解 → 生成用例

**核心能力**：
- **需求理解**：自动识别需求类型（规则/表单/UI/状态/数据/混合），理解业务目标和影响范围
- **风险扫描**：评审前自动识别异常场景、边界条件、审批前置问题，问题带来源依据
- **澄清问题前置**：分析阶段发现信息不足时主动反问，用户补充后自动重新分析，直到信息充分才进入生成
- **类型化测试点拆解**：按需求类型选拆解骨架（规则型→条件组合/边界值；表单型→字段校验/必填；UI 型→交互流程/状态切换；状态型→流转/异常恢复；数据型→一致性/并发/幂等）
- **功能用例设计治理**：功能用例不等于全部正向用例；按测试点特征调度场景法、等价类、边界值、判定表、因果图、状态迁移和正交实验，覆盖正向、反向、边界、组合、状态与恢复场景
- **覆盖与可执行性门禁**：同一验证目标可聚合为可维护用例，但每个测试点、设计方法和覆盖义务必须有实际用例承接；步骤与预期必须一一对应并保留来源追溯
- **输入源识别**：区分 PRD 文本、蓝湖/原型设计稿、TOM 模型等来源，蓝湖/原型类输入自动追加风险提示
- **证据约束**：TOM、页面画像、业务包、轨迹摘要作为证据来源，无证据的内容标记 `LOW_EVIDENCE` 和 `needs_confirmation`，不允许伪装成已确认事实
- **低证据保护**：低置信度结果不会冒充高置信完整结果，测试点明确标注覆盖状态和待确认项

**状态机**：
- 用户输入"生成用例"才进入生成阶段
- 用户补充后自动重新分析，直到信息充分
- "重新分析"可显式触发全量重新分析

### 页面扫描
- 配置化扫描源：支持全局 / 项目级扫描源配置；项目页只管理项目级扫描源，全局源只读复用，并兼容 `BuiltinScanSourceProvider` 扩展
- URL 链接扫描：输入页面链接自动提取页面画像
- 扫描摘要：LLM 驱动的页面画像结构化摘要
- 管理界面：支持创建、编辑、启停、删除项目级扫描源和项目级轨迹规则包，全局配置在项目页只读展示；规则包表单提供模板、结构检查和预览
- 前端加载：页面路由使用 lazy load 拆包，避免开源默认构建出现大 chunk 警告

### 管理后台
- 用户管理：创建、检索、统计
- 模型配置：多模型、多提供商、编辑、删除
- 提示词库：版本管理、审核、内容详情
- TOM 管理：候选审核、批量操作、引用查询
- 资产管理：草稿/正式用例、测试点、知识片段、轨迹摘要、Skill/Tool 模板和三层 Wiki 知识包

## 架构

```text
backend/          Spring Boot 3.x 后端
frontend-react/   React 18 + TypeScript + Vite 前端
packaging/        打包与部署脚本
```

### 技术栈

- 后端：Spring Boot 3.3.5 + MySQL + Flyway + JDBC
- 前端：React 18 + TypeScript + Tailwind CSS + Vite
- LLM：OpenAI 兼容 API（统一网关 + 异步任务）
- 文档解析：PDFBox 3.0.3 + POI 5.2.5

### LLM 调用治理配置

后端通过统一 LLM 网关调用模型，生成结果默认先进入草稿，正式用例库仍需人工确认。常用环境变量：

```bash
AITEST_LLM_CONNECT_TIMEOUT_SECONDS=20
AITEST_LLM_REQUEST_TIMEOUT_SECONDS=420
AITEST_LLM_MAX_CONCURRENT=2
AITEST_LLM_MAX_RETRIES=2
AITEST_LLM_RETRY_BACKOFF_MILLIS=1500
AITEST_LLM_JSON_RESPONSE_FORMAT_ENABLED=true
AITEST_LLM_QUOTA_ENABLED=true
AITEST_LLM_QUOTA_MODE=MEMORY
AITEST_LLM_QUOTA_USER_PER_MINUTE=120
AITEST_LLM_QUOTA_PROJECT_PER_MINUTE=600
AITEST_LLM_BUDGET_ENABLED=false
AITEST_LLM_BUDGET_USER_DAILY_TOKENS=0
AITEST_LLM_BUDGET_PROJECT_DAILY_TOKENS=0
AITEST_GENERATION_ASYNC_THREADS=2
# 异步任务在此时长内没有产生新的节点、检查点或草稿时才超时，不是总运行时长。
AITEST_GENERATION_TASK_TIMEOUT_SECONDS=1800
# 独立的失控保护上限；完整长需求可在持续有进展时跨越前一个时长继续运行。
AITEST_GENERATION_TASK_MAX_RUNTIME_SECONDS=14400
```

长耗时生成可走异步任务接口，后端会返回 `taskId`，前端可轮询 `/api/projects/{projectId}/generation/tasks/{taskId}` 查看 `PENDING / RUNNING / SUCCEEDED / FAILED / TIMEOUT / CANCELED`。

`AITEST_LLM_QUOTA_MODE` 默认为 `MEMORY`，适合单实例部署；多实例部署可设置为 `DB`，系统会使用 `llm_quota_usage_minute` 做分钟窗口计数，避免不同后端实例各自放量。网关会对明显的 API Key、Token、密码类内容做基础脱敏；检测到疑似 Prompt Injection 时会记录 `security_event_log` 并追加系统防护提示，不默认阻断业务请求。

如需控制每日 token 用量，可开启 `AITEST_LLM_BUDGET_ENABLED=true`，并配置用户级或项目级日 token 上限。系统会使用 `llm_quota_usage_day` 累计成功调用的 token；OpenAI 兼容接口返回 `usage.prompt_tokens/completion_tokens` 时优先采用 provider usage，未返回时回退到本地估算。超过预算时会返回可识别的限流错误，不再继续请求模型。

### 凭证与接口安全

- 模型 Provider API Key 仅由管理员通过同源后端配置接口提交，前端不保存 Provider Key，也不直连模型服务；服务端使用 `APP_MODEL_SECRET_KEY` 加密保存。未设置该变量时回退使用 `APP_SECRET_KEY`，两者都缺失时拒绝保存模型 Key。
- `APP_SECRET_KEY`、`JWT_SECRET`、数据库及中间件密码必须通过环境变量或部署 `.env` 配置。仓库与安装模板不提供可预测的默认生产密钥。
- LLM 网关已按用户和项目限流。单实例可使用内存窗口，多实例部署请设置 `AITEST_LLM_QUOTA_MODE=DB`。
- 文件列表和上传响应不会返回服务器物理存储路径；设置页不会显示会话 JWT。浏览器的开发者工具、前端日志和截图中都不应出现模型 Key 或会话令牌。

### 数据库

Flyway 迁移文件位于 `backend/src/main/resources/db/migration/`，按版本号递增管理。

## 业务接入

平台不依赖 Java 硬编码业务包类。新业务接入方式：

1. **创建项目**：在项目大厅新建项目
2. **导入资料**：上传使用手册、导入页面链接
3. **采集轨迹**：通过采集器录制真实操作轨迹
4. **自动沉淀**：系统根据项目资产自动生成 business pack draft
5. **人工审核**：确认、合并、拆分业务包
6. **持续演化**：后续使用过程中持续更新

自动沉淀不会直接污染正式知识：Wiki 候选需要先审核条目、再审核知识包并激活；TOM 候选需要人工确认。部署本版本时 Flyway 会执行 `V48__knowledge_deposition_governance.sql`、`V49__expand_generation_task_mode.sql` 和 `V50__harden_generation_workflow_column_capacity.sql`，分别补齐知识沉淀治理字段、兼容完整的 TOM 使用模式写入异步任务，并统一扩展需求分析、异步任务、会话阶段和 LLM 审计等工作流控制字段。

## 开发

```bash
# 后端编译
cd backend && mvn -q -DskipTests compile

# 后端测试
cd backend && mvn test

# 前端构建
cd frontend-react && npm run build
```

## 许可证

MIT

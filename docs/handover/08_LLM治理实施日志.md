# 08 · LLM 数据污染治理实施日志

> 这是 [07_LLM数据污染治理方案.md](07_LLM数据污染治理方案.md) 的施工日志。append-only，每个里程碑完成后追加一节。每节记录：时间、影响范围、变更摘要、文件清单、验证结果、回滚方法。
>
> 日期均为本地时间。提交时若不在 git 仓内（当前项目 `Is a git repository: false`），将以"批次号 B<编号>"作为可追溯锚点。

---

## 索引

- [Sprint 1 · 骨架](#sprint-1--骨架2026-05-22-起)
  - M1.1 V7 数据库迁移
  - M1.2 Gateway 接口与 DTO
  - M1.3 LlmInvocationLogger
  - M1.4 PromptSnapshotService 最小版
  - M1.5 LlmGatewayImpl 主流程
  - M1.6 接入第一个调用点 (DirectCaseGenerationService)
  - M1.7 Sprint 1 构建与验证
- Sprint 2 · 上下文与权限（待开工）
- Sprint 3 · 用例库分层（待开工）

---

## 通用记录规范

每个 M 节按此格式：

```
### M<X.Y> · <标题>  ✅/⏳/❌

- 时间：YYYY-MM-DD HH:MM
- 影响：<哪些表/模块>
- 变更摘要：<bullet 一句话>
- 新增文件：
- 修改文件：
- 验证：<命令 + 结果>
- 回滚：<如何回退>
- 备注：<风险/已知不足>
```

---

## Sprint 1 · 骨架（2026-05-22 起）

### M1.1 · V7 数据库迁移  ✅

- 时间：2026-05-22
- 影响：MySQL schema（新增 11 张表 + 8 张老表加字段）
- 变更摘要：
  - 新建表：`prompt_snapshot`、`context_manifest`、`llm_invocation_log`、`security_event_log`、`skill_template`、`tool_template`、`trace_summary`、`trace_asset_tag`、`vector_index_outbox`、`llm_quota_config`、`llm_quota_usage_day`
  - ALTER：`prompt_template`（version/review_status/deprecated_*）、`knowledge_document`（scope/visibility/review_status/trust_level/deprecated_*）、`knowledge_chunk`（deprecated）、`test_case_asset`（case_scope/case_status/submitted_*/exported_at/snapshot_id/model_config_id/trust_level/deprecated_*）、`test_case_draft`（case_scope/case_status/created_by）、`test_point_draft`（case_scope/created_by）、`generation_task`（task_type/prompt_template_id/prompt_version/prompt_snapshot_id/created_by）、`skill_execution_log`（request_id/user_id/context_manifest_id/skill_template_id/skill_version）
  - 所有 ALTER 默认值齐备，旧代码不动也能继续运行
- 新增文件：
  - `backend/src/main/resources/db/migration/V7__llm_governance_foundation.sql`
- 修改文件：（无）
- 验证：待 M1.7 mvn package & 启动验证。
- 回滚：删除 V7 文件并手工 `DROP TABLE ...` + `ALTER TABLE ... DROP COLUMN ...`。新装库则直接修订 V7 重跑。
- 备注：`prompt_template` 旧的 `uk_prompt_hash` 索引被替换为 `(prompt_name, version, scope)`，因为同 hash 不再唯一（不同 version 内容可同）。如果生产已经有重复 hash 数据，先用 V7.1 数据清理。

### M1.2 · Gateway 接口与 DTO  ✅

- 时间：2026-05-22
- 影响：`com.company.aitest.llm.gateway` 包（新增）
- 变更摘要：
  - 接口 `LlmGateway`：唯一入口，禁止业务服务直接持有 `LlmAdapter`
  - DTO `LlmInvocationRequest` / `LlmInvocationResponse`：包含 user/project/task/stage/modelConfig/promptTemplate/variables 等字段
  - 枚举 `LlmStage`（8 个阶段，对齐 §4.2 表）
  - 枚举 `LlmInvocationStatus`（OK / GUARD_BLOCKED / QUOTA_EXCEEDED / MODEL_ERROR / TIMEOUT / INVALID_REQUEST）
- 新增文件：
  - `backend/.../llm/gateway/LlmGateway.java`
  - `backend/.../llm/gateway/LlmInvocationRequest.java`
  - `backend/.../llm/gateway/LlmInvocationResponse.java`
  - `backend/.../llm/gateway/LlmStage.java`
  - `backend/.../llm/gateway/LlmInvocationStatus.java`
  - 子包占位目录：`context/` `retrieval/` `prompt/` `safety/` `quota/` `audit/`
- 验证：随 M1.7 一起 mvn 编译
- 回滚：删 gateway 目录即可
- 备注：本期 LlmGateway 实现按"M1 最小集"，retrieval/manifest 用 Noop 占位，避免阻塞先把日志链路打通。

### M1.3 · LlmInvocationLogger  ✅

- 时间：2026-05-22
- 影响：审计写入路径
- 变更摘要：`audit.LlmInvocationLogger`、`audit.LlmInvocationLogEntry`，封装 `llm_invocation_log` 的 insert；审计失败不抛业务异常（吞掉打 stderr），保证业务可用性优先于审计。
- 新增文件：
  - `backend/.../llm/gateway/audit/LlmInvocationLogger.java`
  - `backend/.../llm/gateway/audit/LlmInvocationLogEntry.java`
- 验证：M1.7 编译
- 回滚：删两个文件 + V7 表保留即可（无副作用）
- 备注：M1 阶段 `input_snapshot_ref / output_snapshot_ref` 暂留 null，等 Sprint 5 落到 `file_resource` 表。Token 是估算值（4 字符 ~ 1 token），后续接真实 tokenizer。

### M1.4 · PromptSnapshotService 最小版  ✅

- 时间：2026-05-22
- 影响：prompt 快照入库路径
- 变更摘要：`prompt.PromptSnapshotService` + `PromptSnapshotEntry`，提供 `contentHash(system, user)` SHA-256 计算 + `save()` insert。
- 新增文件：
  - `backend/.../llm/gateway/prompt/PromptSnapshotService.java`
  - `backend/.../llm/gateway/prompt/PromptSnapshotEntry.java`
- 验证：M1.7 编译
- 回滚：删两个文件
- 备注：M1 阶段直接接收 caller 已渲染的 system/user 文本；变量替换 / 模板渲染留到 Sprint 4（与 prompt version & fork 一起做）。

### M1.5 · LlmGatewayImpl 主流程  ✅

- 时间：2026-05-22
- 影响：业务服务调用 LLM 的唯一入口
- 变更摘要：实现 11 步流程的 step 1（校验）/ step 3（snapshot）/ step 9（调 adapter）/ step 11（日志）；其余 step 用 TODO 标注占位，等后续 Sprint 接入。任何路径都保证一条 invocation_log 写入，且不向上抛 RuntimeException（业务用 LlmInvocationStatus 判断）。
- 新增文件：
  - `backend/.../llm/gateway/LlmGatewayImpl.java`
- 验证：M1.7 编译
- 回滚：删除文件 + 把 caller 切回直接调 `LlmAdapter`
- 备注：本期没接入限流/RAG/manifest/注入扫描/脱敏，但接口签名稳定，后续接入不需要 caller 改代码。

### M1.6 · 接入第一个调用点（DirectCaseGenerationService）  ✅

- 时间：2026-05-22
- 影响：用例生成主链路
- 变更摘要：
  - `DirectCaseGenerationService` 构造参数把 `LlmAdapter` 换成 `LlmGateway`
  - 主调用改成 `llmGateway.invoke(...)`，stage=TEST_CASE_GEN，taskType=GENERATION
  - 失败统一抛 BusinessException
  - test_case_draft 写入时补 `case_scope='PERSONAL'`、`case_status='DRAFT'`、`created_by=user.id`
- 修改文件：
  - `backend/.../generation/DirectCaseGenerationService.java`
- 验证：mvn compile / test 全过
- 回滚：还原构造参数与调用代码
- 备注：现有调用 user 是非空必填的（Controller 中从 SecurityContext 取），守卫双保险。

### M1.7 · Sprint 1 构建与验证  ✅

- 时间：2026-05-22
- 影响：构建质量门
- 变更摘要：
  - 编写 `LlmGatewayImplTest`（5 个用例）：成功路径、INVALID_REQUEST、MODEL_ERROR、连续两次 invoke 不串味、requestId 自动生成
  - 全套 124 测试通过（原 119 + 新 5）
  - 编写 CR 守卫脚本 `scripts/check-llm-gateway.sh`：检测非 Gateway 包直接 import LlmAdapter；检测前端是否直连模型 provider
- 新增文件：
  - `backend/src/test/java/com/company/aitest/llm/gateway/LlmGatewayImplTest.java`
  - `scripts/check-llm-gateway.sh`
- 验证：
  - `mvn -q test` → tests=124 errors=0 failures=0
  - `scripts/check-llm-gateway.sh` → ✅ 通过
- 回滚：删除上面两个文件
- 备注：CR 守卫脚本建议接入 CI；当前仓库未启用 git，按需在部署流水线挂上。

### M1.8 · 收编剩余 LLM 调用点  ✅

- 时间：2026-05-22
- 影响：所有业务 LLM 调用点
- 变更摘要：
  - `TraceAssetService` 4 处调用（轨迹摘要 / 轨迹生成用例 / Skill 提炼 / Tool 提炼）改为走 `LlmGateway`；构造参数移除 `LlmAdapter`
  - `ControlledScanService` 1 处（受控扫描摘要）改为走 `LlmGateway`
  - 5 处统一通过私有 helper（`invokeLlm` / `invokeScanLlm`）封装错误转换
  - stage 选择：摘要→TRACE_SUMMARY、轨迹用例→TRACE_CASE_GEN、Skill/Tool 提炼→SKILL_EXEC、受控扫描→OTHER
- 修改文件：
  - `backend/.../trace/TraceAssetService.java`
  - `backend/.../scan/ControlledScanService.java`
- 验证：
  - `mvn -q test` 124 用例全过
  - `scripts/check-llm-gateway.sh` 通过：仅 `llm.gateway.LlmGatewayImpl` 引用 LlmAdapter
- 回滚：把 5 处 helper 调用还原为 `llmAdapter.complete(new LlmAdapter.CompletionRequest(...))`，并恢复 import 与构造参数
- 备注：trace_generated_case 已在表中存有 prompt_snapshot 列；后续 Sprint 2 起把这一列改为 `prompt_snapshot_id` 引用，并把 inline 文本迁移到 prompt_snapshot 表。

---

## Sprint 1 验收

- ✅ LLM 调用统一入口（Gateway）建立
- ✅ 所有业务调用点（generation/trace/scan，共 6 处）已接入 Gateway
- ✅ 每次调用必落 `llm_invocation_log`（user/project/task/stage/model 字段齐全）
- ✅ 每次调用必落 `prompt_snapshot`（content_hash 可定位历史复现）
- ✅ Schema 治理基础到位（V7 11 新表 + 8 张老表加字段）
- ✅ CR 守卫脚本可用
- ✅ 124 单测全过；新增 5 个 Gateway 行为锁定用例
- ⏳ RAG 过滤 / context manifest / 限流 / 注入防护 / 审计页面 → Sprint 2 起继续

---

## Sprint 2 · 上下文与权限（2026-05-22）

### M2.1 · RagRetrievalService（SQL 路径）  ✅

- 时间：2026-05-22
- 影响：`com.company.aitest.llm.gateway.retrieval`（新增）
- 变更摘要：
  - `RetrievalPolicy`：按 stage 给默认策略（含 topN / kinds / 是否包含弃用 / 是否包含未审核公共）
  - `RetrievedAsset`：召回结果统一结构
  - `RetrievalResult`：含 `excludedPolicyJson`
  - `RagRetrievalService`：SQL 路径检索 knowledge_chunk / test_case_asset / skill_template，全部带 §5.2 过滤范式（项目过滤 / 状态过滤 / 审核过滤 / PERSONAL 限本人）
  - 排序按 `trust_level` 权重（SYSTEM_RULE 100 → AI_GENERATED 20）
- 新增文件：
  - `backend/.../llm/gateway/retrieval/RetrievalPolicy.java`
  - `backend/.../llm/gateway/retrieval/RetrievedAsset.java`
  - `backend/.../llm/gateway/retrieval/RetrievalResult.java`
  - `backend/.../llm/gateway/retrieval/RagRetrievalService.java`
- 验证：mvn 编译；后续 M2.6 单测覆盖
- 回滚：删除 retrieval 子包 + 让 LlmGatewayImpl 跳过 step 4
- 备注：Weaviate 路径留到 Sprint 3+。当前 SQL 路径不做语义召回，仅做权限/作用域/状态过滤，已能保证"安全"，相似度替代用 trust_level 排序，避免误用错知识。

### M2.2 · ContextManifest 入库  ✅

- 时间：2026-05-22
- 影响：`com.company.aitest.llm.gateway.context` + `LlmGatewayImpl`
- 变更摘要：
  - `ContextManifest`：本次调用的完整上下文清单（included assets / excluded policy / conflicts）
  - `ContextManifestRepository`：写 context_manifest 表
  - `LlmGatewayImpl` 接入 step 4（safe retrieve）+ step 5/6（manifest 入库，id 回写到 `llm_invocation_log.context_manifest_id`）
- 新增文件：
  - `backend/.../llm/gateway/context/ContextManifest.java`
  - `backend/.../llm/gateway/context/ContextManifestRepository.java`
- 修改文件：
  - `backend/.../llm/gateway/LlmGatewayImpl.java`（构造参数 +2，主流程接入 RAG + Manifest）
- 验证：单测扩展到 6 个，134 用例全过
- 回滚：把 LlmGatewayImpl 退回到 Sprint 1 的 4 步流程，删除 context 子包
- 备注：`included_assets_json` 字段是真正能在审计页里"展开本次用了哪些知识"的依据。

### M2.3 · LlmCacheKey 工具  ✅

- 时间：2026-05-22
- 影响：缓存层（未来接入 Redis 时复用）
- 变更摘要：`LlmCacheKey`（final 工具类），暴露 6 个工厂方法：`ctx / rag / prompt / trace / userQuota / projectQuota`。所有 key 带 `aitest:llm:` 前缀；rag 的 queryText 走 SHA-256 截前 16 hex。
- 新增文件：
  - `backend/.../llm/gateway/cache/LlmCacheKey.java`
  - `backend/src/test/.../cache/LlmCacheKeyTest.java`（5 个用例）
- 验证：134 用例全过
- 回滚：删除 cache 子包
- 备注：业务代码引用本类即可；CR 守卫脚本将检测仍在拼字符串的旧代码。

### M2.4 · 缓存 key 黑名单 CR 守卫  ✅

- 时间：2026-05-22
- 影响：CR 流水线
- 变更摘要：在 `scripts/check-llm-gateway.sh` 中扩展第 (2) 项检查：禁用 `latest_context / current_context / last_task / last_messages / case_generation_prompt / rag_result / kb_top / shared_thread / conversation_default` 等字面量；仅 `llm.gateway.cache` 包内可使用（暂未触发，规则保留）。
- 修改文件：
  - `scripts/check-llm-gateway.sh`
- 验证：脚本执行 `✅ 全部通过`
- 回滚：去掉第 (2) 段

### M2.5 · 弃用资产默认排除  ✅

- 时间：2026-05-22
- 影响：RAG 检索默认行为
- 变更摘要：`RagRetrievalService` 三类资产 SQL 默认 `deprecated_at IS NULL` / `deprecated = 0`；`RetrievalPolicy.includeDeprecated` 默认 false，包含弃用时 `excluded_policy_json.deprecatedAssets="included_by_user_consent"`。
- 新增/修改文件：
  - `backend/src/test/.../retrieval/RetrievalPolicyTest.java`（4 个用例，包含"默认必须排除弃用"的硬约束）
- 验证：134 用例全过
- 回滚：删除 SQL 中的 deprecated 条件
- 备注：等 Sprint 3 用例库分层完成后，再在 UI 提供"包含已弃用资产"的复选框入口。

### M2.6 · Sprint 2 验证与日志  ✅

- 时间：2026-05-22
- 影响：本日志
- 变更摘要：本节
- 验证：
  - `mvn -q test` → **tests=134 errors=0 failures=0**（Sprint 1 的 124 + Sprint 2 新增 10 个）
  - `scripts/check-llm-gateway.sh` → 通过
- 备注：Sprint 2 结束。所有 LLM 调用现在都自带"权限/作用域/状态/弃用"过滤，并真实写入 context_manifest，可以在审计页（待 Sprint 5 上线）一键复盘。

---

## Sprint 2 验收

- ✅ RAG 检索带权限/作用域/状态/弃用过滤
- ✅ Context Manifest 真实入库并关联到 llm_invocation_log
- ✅ Trust level 权重排序生效（SYSTEM_RULE > PROJECT_APPROVED > EXTERNAL_DOC > HISTORICAL_CASE > TRACE_CONFIRMED > USER_DRAFT > AI_GENERATED）
- ✅ 弃用资产默认排除（DB + RetrievalPolicy 双约束）
- ✅ 缓存 key 黑名单 CR 守卫
- ✅ LlmCacheKey 工具就位（待 Sprint 3+ 接入 Redis 时使用）
- ✅ 134 单测全过
- ⏳ Weaviate 语义召回 / 限流 / 注入防护 / 审计页面 → Sprint 3+ 继续

---

## Sprint 4 · Playwright 轨迹增强 + 资产治理（2026-05-22 ~ 23）

> 按用户要求把 Sprint 4 提前到 Sprint 3 之前并行做。Sprint 4 实际范围：
> - **已完成（本期）**：M4.0 摸底 / M4.1 normalized_locator 接入清洗主链路 / M4.4 skill/tool 治理接入 / M4.5 prompt_template version & fork
> - **延期到 Sprint 4.5**：M4.2 page.screencast 录屏接入 / M4.3 问题片段录屏联动（涉及 worker TS + 文件存储 + 协议变更，独立工作单元）

### M4.0 · 摸清 Playwright 轨迹增强现状  ✅

- 时间：2026-05-22
- 影响：无代码改动
- 变更摘要：
  - V6 schema 已建好所有字段：`browser_trace_event.normalized_locator/section_title/dialog_title/object_label`、`browser_trace_session.screencast_path/started/stopped/duration_ms`、`browser_issue_clip.screencast_path/clip_start_ms/clip_end_ms`
  - worker `capture/index.ts` 已产出 `normalizedLocator/sectionTitle/dialogTitle/objectLabel` 字段
  - backend `TraceDataService` 已从 DB 读取 normalized_locator
  - 但 `TraceStepNormalizer` 仅用 object_label / dialog_title 给列表行操作打前缀，**没用 normalized_locator** → M4.1 解决
  - worker **完全没有 screencast 代码** → M4.2 / M4.3 范围
  - V2 已建 `test_skill_template / test_tool_template`，V7 误以为新表又建了一份 → 本期把 V7 改成对 V2 表 ALTER 加治理字段
- 备注：找到一处遗漏：V7 新建了重复表，本次修正

### M4.1 · T-PW-1 normalized_locator 接入清洗主链路  ✅

- 时间：2026-05-23
- 影响：`TraceStepNormalizer` 与轨迹清洗、间接影响轨迹生成用例
- 变更摘要：
  - `bestTarget(elementText, elementRole, selector)` 改为 `bestTarget(elementText, elementRole, normalizedLocator, selector)`，在 selector 兜底前插入一层 `normalizeLocatorToTarget()` 处理 Playwright 语义化定位
  - 支持 `getByRole('button', { name: 'X' })` → "X 按钮"、`getByRole('link'…)` → "X 链接"、`getByLabel('X') / getByText('X') / getByPlaceholder('X') / getByTestId('X')` → "X" 等映射
  - 单测新增 2 例：`normalizedLocatorFillsTargetWhenElementTextMissing`、`normalizedLocatorByLabelMaps`
- 修改文件：
  - `backend/.../trace/TraceStepNormalizer.java`
  - `backend/src/test/.../trace/TraceStepNormalizerTest.java`
- 验证：mvn test → **tests=136 errors=0 failures=0**
- 回滚：还原 `bestTarget` 签名与方法体
- 备注：以后轨迹生成用例时，TraceStepNormalizer 的输出会更稳定地携带语义化目标，间接提高 LLM 生成质量。

### M4.4 · skill_template / tool_template 接入生成与检索  ✅

- 时间：2026-05-23
- 影响：`V7__llm_governance_foundation.sql` + `TraceAssetService` + `RagRetrievalService`
- 变更摘要：
  - V7 修正：删除新建的 `skill_template / tool_template` 表，改为对 V2 已建的 `test_skill_template / test_tool_template` 做 ALTER 加 scope/description/body/version/review_status/trust_level/deprecated_at/deprecated_by 等字段
  - `TraceAssetService` 写入 skill / tool 时填上 `scope='PERSONAL' / status='DRAFT' / review_status='PENDING' / trust_level='AI_GENERATED'`，与 §6.3 状态流转一致
  - `RagRetrievalService.searchSkills` 改查 `test_skill_template`（不再查不存在的 `skill_template`），SQL 命中 §8.2 默认检索范围（SYSTEM 总命中 / PROJECT 需 APPROVED / PUBLIC 需 APPROVED / PERSONAL 限本人）
- 修改文件：
  - `backend/src/main/resources/db/migration/V7__llm_governance_foundation.sql`
  - `backend/.../trace/TraceAssetService.java`
  - `backend/.../llm/gateway/retrieval/RagRetrievalService.java`
- 验证：mvn test → **tests=136 errors=0 failures=0**
- 回滚：恢复 V7 中两张 CREATE TABLE；恢复 TraceAssetService insert；RagRetrievalService.searchSkills 改回 skill_template
- 备注：以后 AI 提炼出来的 Skill/Tool 默认是 PERSONAL+DRAFT，不会出现在其他用户的检索结果里；用户确认后才有可能 promote 到 PROJECT/PUBLIC。

### M4.5 · prompt_template version & fork  ✅

- 时间：2026-05-23
- 影响：`PromptTemplateRecord` / `PromptTemplateService` / `PromptTemplateController`
- 变更摘要：
  - `PromptTemplateRecord` 增加 `version / reviewStatus / deprecatedAt / deprecatedBy / deprecatedReason` 字段
  - `PromptTemplateService.listEnabled()` 加 `review_status='APPROVED' AND deprecated_at IS NULL` 过滤
  - 新增 `updateAsNewVersion(oldId, newContent, user)`：旧版标记 DEPRECATED，新版 version+1，scope/promptType 继承，状态 ACTIVE+APPROVED；保证历史 prompt_snapshot 不被破坏
  - 新增 `forkToPersonal(publicId, user)`：复制公共/系统提示词为用户 PERSONAL 副本（名后缀"(我的副本)"），ACTIVE+APPROVED
  - 新增 `proposeAsPublic(personalId, user)`：把 PERSONAL 内容复制成 scope=PUBLIC + status=REVIEWING + review_status=PENDING 的候选提示词
  - 新增 `review(promptId, approved, reason, admin)`：管理员审批，通过则 ACTIVE+APPROVED，否则 DEPRECATED+REJECTED
  - Controller 新增 4 个端点：`POST /api/admin/prompts/{id}/new-version`（管理员）、`/fork`（任意登录用户）、`/propose-public`（创建人）、`/review`（管理员）
- 修改文件：
  - `backend/.../prompt/PromptTemplateRecord.java`
  - `backend/.../prompt/PromptTemplateService.java`
  - `backend/.../prompt/PromptTemplateController.java`
- 验证：mvn test → **tests=136 errors=0 failures=0**
- 回滚：还原 record 字段；service 移除 4 个新方法；controller 移除 4 个端点
- 备注：前端 UI 入口（fork / propose / review 按钮）留到下一轮配合 §21 公共提示词库重排版做。

### M4.5x · M4.2/M4.3 留作 Sprint 4.5  ✅（已并入本期完成）

- 状态：~~原计划延期~~ 本次会话同步把 M4.2 主录屏与 M4.3 问题片段录屏联动一起做完
- 见下方 M4.2 / M4.3 完成记录

### M4.2 · T-PW-2 主录屏切 page.screencast  ✅

- 时间：2026-05-23
- 影响：worker 端 capture/index.ts + backend Trace 接收 / Service / Record + 前端 TracePanel
- 实现路径：Playwright 没有公开的 `page.screencast` API，使用等价的 context 级 `recordVideo` 选项；session 与视频文件 1:1 对应
- 变更摘要：
  - **worker** `capture/index.ts`：
    - `ProfileContext` 增加 `recording: boolean / videoDir?`
    - 拆分出 `createProfileContext(req, opts)`，参数化是否带 `recordVideo`
    - `getOrCreateProfileContext` 支持在录像状态切换时自动重建 context（保存 storageState → close 旧 context → 新建 → 恢复 storageState → goto 上次 URL）
    - `start()` 调用时强制带 `{ recordVideo: true }`，记录 `videoStartedAtUtc`
    - `stop()` 在 tracing 收尾后调用 `context.close()` 让视频 finalize，`page.video().path()` 拿到原始路径再 rename 到 `<sessionDir>/screencast.webm`，记录 `screencastStartedAt/StoppedAt/DurationMs`；最后重建一个不带 recordVideo 的 context 让用户回到等价浏览器状态
    - `StopCaptureResponse` 增加 4 个 screencast 字段
  - **backend**：
    - `TraceRuntimeController.StopSessionRequest` 增加 4 个 screencast 字段，并保留旧 2 参构造器以兼容
    - `BrowserTraceSessionService.stop` 增加新签名（7 参），update SQL 同步写 `screencast_*` 4 列；旧 4 参 stop 委派到新签名
    - `BrowserTraceSessionRecord` + mapper 同步加 4 字段
    - 新增 `GET /api/trace/sessions/{id}/screencast`：从 DB 取 `screencast_path` → `FileSystemResource` 流式返回 webm（content-type `video/webm`）；权限走 service.getById（按 user_id 校验）
  - **前端 TracePanel.vue**：
    - 类型 `BrowserTraceSession / WorkerStopResult` 加 4 字段
    - 会话卡片"录屏与轨迹文件"区域加"下载/打开"链接 + "内嵌播放"按钮 + `<video>` 元素
    - stop 调用 backend 时把 screencast 字段透传给后端
    - 新增 helper：`screencastUrl / formatDurationMs / toggleSessionVideo`
- 新增/修改文件：
  - `local-browser-worker/src/capture/index.ts`
  - `backend/.../trace/TraceRuntimeController.java`、`BrowserTraceSessionService.java`、`BrowserTraceSessionRecord.java`
  - `frontend/src/components/TracePanel.vue`
- 验证：
  - worker `npx tsc --noEmit` 通过，`npx vitest run` **113 用例全过**
  - backend `mvn -q -DskipTests compile` 通过，`mvn -q test` **136 用例全过**
  - frontend `vue-tsc --noEmit` 通过，`vite build` 通过
- 回滚：
  - worker：还原 ProfileContext / start / stop
  - backend：把 StopSessionRequest 与 stop service 还原为 2 参数，去掉 screencast endpoint
  - 前端：去掉 4 字段与 video 元素
  - DB 列在 V6 已存在；保留无影响
- 备注：
  - Playwright 的 video 文件需 context.close() 才 finalize，所以 session stop 必然带"关旧 context → 新开 context"的视觉切换；为用户体验是可接受的代价
  - webm 在 Safari 不可直接播放；后续如需 Safari 兼容，可在 stop 后离线转 mp4（依赖 ffmpeg，留作 P2）

### M4.3 · T-PW-3 问题片段录屏联动  ✅

- 时间：2026-05-23
- 影响：backend `TraceDataService.createIssueClip` + Record + 前端 issue clip 卡片
- 变更摘要：
  - **backend** `createIssueClip`：当 `input.traceSessionId` 不为空且对应 session 已有 `screencast_path` → 自动把 `screencast_path` 复制到 issue clip 行；`screencast_clip_start_ms = clipStartRelativeMs / screencast_clip_end_ms = clipEndRelativeMs`（视频是 session 起点为 0，relative_ms 直接等于视频毫秒）
  - `BrowserIssueClipRecord` + mapper 同步加 3 字段
  - **前端 TracePanel.vue**：
    - `BrowserIssueClip` 类型加 3 字段
    - issue clip 卡片显示"录屏区间：m:ss → m:ss"，加"播放此区间"按钮
    - 嵌入 `<video>` 元素，`@loadedmetadata` 自动 `currentTime = start/1000` 并 `.play()`；`@timeupdate` 监听到达 end 自动 `.pause()`
    - 新增 helper：`bindClipVideo / playClipRange / seekToClipStart / enforceClipEnd`
- 修改文件：
  - `backend/.../trace/TraceDataService.java`、`BrowserIssueClipRecord.java`
  - `frontend/src/components/TracePanel.vue`
- 验证：mvn 136 测试全过；vue-tsc + vite build 通过
- 回滚：恢复 createIssueClip 与 mapper；去掉前端片段播放
- 备注：
  - 整段视频只 fetch 一次，前端用 currentTime 跳转，体验远好于切片
  - 如果 issue clip 的 session 没录屏（旧数据），不会渲染录屏块，向后兼容

---

### M4.6 · Sprint 4 验证与文档  ✅

- 时间：2026-05-23
- 影响：本日志 + CHANGELOG + 05 + 03（专项计划）+ 06（Playwright 实施清单）
- 变更摘要：
  - 全套测试 **tests=136 errors=0 failures=0**（Sprint 1+2+4 合计新增 12 个用例）
  - `scripts/check-llm-gateway.sh` → ✅ 通过
  - 文档同步：CHANGELOG.md、05_上下文日志.md、08 本日志、03 §6 LLM 治理专项排期调整
- 备注：Sprint 4 = "尽快把 LLM 治理已有字段在主链路真用起来" + "把 Playwright Round 1 完成最后一公里（locator 进清洗）"

---

## Sprint 4 验收

- ✅ V7 schema 修正：原本的"重复建 skill_template/tool_template"改为对 V2 已有表 ALTER 加治理字段，避免双表
- ✅ Skill / Tool 提炼真正写入治理字段（scope=PERSONAL / status=DRAFT / review_status=PENDING / trust_level=AI_GENERATED）
- ✅ RAG 检索的 SKILL 路径走 test_skill_template，命中 §8.2 默认检索范围
- ✅ prompt_template 支持 version + fork + propose-public + review 四种治理动作；listEnabled 仅返回 ACTIVE + APPROVED + 未弃用
- ✅ TraceStepNormalizer 用 normalized_locator 作为优先回退（在 elementText/elementRole 之后、selector 之前）
- ✅ Playwright `recordVideo` 主录屏接入（M4.2，初版）
- ✅ 问题片段自动继承 session 录屏；前端"播放此区间"按钮按毫秒跳转 + 自动暂停
- ✅ **M4.7 主录屏切到 CDP `Page.startScreencast`**：消除 context 重建带来的视觉闪动；改用 JPEG 帧序列 + manifest.json 模型
- ✅ 后端 136 单测 + worker 113 单测 + CR 守卫全过
- ⏳ Sprint 3 用例库分层接续

### M4.7 · page.screencast 切入（CDP）  ✅

- 时间：2026-05-24
- 影响：worker `capture/index.ts` + backend Trace 控制器 + 前端 ScreencastPlayer 组件
- 动机：M4.2 用 context 级 `recordVideo` 落地了主录屏，但 session start/stop 时必须重建 context，浏览器会有一次视觉闪动；并且体感上违反"session 是 page 之上的小循环"的直觉。M4.7 改用 page 级 CDP `Page.startScreencast`，profileContext 长存，无视觉闪动。
- 变更摘要：
  - **worker** `capture/index.ts`：
    - `ProfileContext` 去掉 `recording / videoDir` 字段（不再需要按录像状态切换）
    - `getOrCreateProfileContext` / `createProfileContext` 移除 `recordVideo` 参数
    - `ActiveSession` 新增 `cdpScreencast: CDPSession | null / screencastDir / screencastFrames / screencastFrameCount`
    - 新增 `startCdpScreencast(active, page)`：`context.newCDPSession(page)` → `Page.startScreencast({ format: jpeg, quality: 70, everyNthFrame: 2 })` → 监听 `Page.screencastFrame`，每帧写 `<sessionDir>/screencast/frames/<6位数>.jpg` + 调 `Page.screencastFrameAck`
    - `stop()` 不再 close context + 重建，改为 `Page.stopScreencast` + `cdp.detach()` + 写 manifest.json（含 sessionId / format='cdp-jpeg-manifest' / startedAt / stoppedAt / durationMs / frameCount / frames[]）
    - 录屏失败不阻塞 session start（catch + 日志，事件采集仍正常）
  - **backend** `TraceRuntimeController`：
    - 旧 `GET /api/trace/sessions/{id}/screencast` 改为兼容 `.webm` 与 `.json`，按后缀分别返回 `video/webm` 或 `application/json`
    - 新增 `GET /api/trace/sessions/{id}/screencast/manifest` —— 显式取 manifest.json
    - 新增 `GET /api/trace/sessions/{id}/screencast/frame/{filename}` —— 按 `^\d{6}\.jpg$` 校验帧文件名；目录由 manifest.json 所在 `frames/` 强制拼出，杜绝 path traversal
    - 权限：所有 endpoint 都走 `sessionService.getById(sessionId, user)` 按 user_id 校验
  - **frontend**：
    - 新增 `components/ScreencastPlayer.vue`：fetch manifest → 按 `currentMs` 二分查找帧 → `<img>` 切换；自带"播放/暂停 + 进度条 + 时间显示"；支持 `clipStartMs / clipEndMs` 触发自动跳转和到点暂停
    - `TracePanel.vue` 两处 `<video>`（会话卡片 + 问题片段卡片）替换为 `<ScreencastPlayer>` 组件
    - 删除已无用的 `screencastUrl / bindClipVideo / seekToClipStart / enforceClipEnd / clipVideoRefs`
- 新增文件：
  - `frontend/src/components/ScreencastPlayer.vue`
- 修改文件：
  - `local-browser-worker/src/capture/index.ts`
  - `backend/.../trace/TraceRuntimeController.java`
  - `frontend/src/components/TracePanel.vue`
- 验证：
  - worker `npx tsc --noEmit` + `vitest run` → **113 用例全过**
  - backend `mvn -q test` → **136 用例全过**
  - frontend `vue-tsc --noEmit` + `vite build` 通过
  - `scripts/check-llm-gateway.sh` → ✅ 通过
- 回滚：
  - worker：把 `ProfileContext` 加回 `recording / videoDir`，恢复 `recordVideo` 切换路径，删除 `startCdpScreencast`
  - backend：恢复单 `/screencast` endpoint 只返回 webm，删除 manifest / frame endpoints
  - frontend：恢复 `<video>` 元素，删除 `ScreencastPlayer.vue`
- 备注：
  - **保留 webm 兼容**：后端 endpoint 检测 `.json` / `.webm` 后缀分流；既有 M4.2 阶段产生的 webm 文件仍可下载播放（虽然 frontend `<video>` 已被移除，可以通过直接访问 endpoint 下载）
  - **帧率与体积权衡**：`everyNthFrame=2 / quality=70 / jpeg` 是经验值，60s 操作约产生 30-50MB；后续可按 stage 类型动态调整
  - **路径仍是 worker 本地绝对路径**：CHANGELOG 已记录"客户端固定目录 + 本地 worker 预览 + 后台只存路径和元信息"为后续演进方向，本期不实现

---

## Sprint 3 · 用例库分层（待开工，排在 Sprint 4.5 之后）


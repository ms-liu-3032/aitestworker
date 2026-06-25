# Loop + LLM Wiki + Mini-TOM 整合与 Playwright 升级方案

> 更新时间：2026-06-24  
> 适用仓库：`aitestworker`  
> 目标：升级轨迹采集底座，并引入知识层、结构化事实层和持续学习层，系统性优化测试用例生成与轨迹记录。

---

## 1. 方案摘要

本方案分成两条主线，按优先级推进：

1. **Playwright 升级到最新稳定版本**
   - 当前本地采集器使用 `playwright-core ^1.60.0`
   - 建议先升级到最新稳定 `1.61.0`
   - 目标是提升采集稳定性、兼容性和底层能力边界

2. **接入 Loop + LLM Wiki + Mini-TOM**
   - **LLM Wiki**：知识与解释层
   - **Mini-TOM**：结构化测试事实层
   - **Loop**：持续学习与回灌层
   - 目标是让平台从“单次 prompt 驱动”升级为“项目资产持续沉淀驱动”

一句话定义：

- **LLM Wiki 负责沉淀项目知识、复用参考知识、系统标准知识、规则经验、设计背景和历史交接**
- **Mini-TOM 负责沉淀测试生成直接消费的结构化业务事实**
- **Loop 负责把真实使用中的补充、修正、冲突和高频问题持续回灌成新资产**
- **Loop 不是默认常驻能力，必须由后台管理员统一配置，默认关闭**

---

## 2. 当前现状判断

### 2.1 轨迹采集现状

当前轨迹采集并不是“只依赖 Playwright 原生录制”，而是已经叠加了较多平台增强能力：

- `normalized_locator` 语义定位增强
- 事件清洗
- 轨迹摘要
- TOM / business pack / 语义上下文参与后续消费
- CDP page-level screencast / 帧清单相关能力

关键文件：

- [local-browser-worker/package.json](../../local-browser-worker/package.json)
- [local-browser-worker/src/capture/index.ts](../../local-browser-worker/src/capture/index.ts)
- [backend/src/main/java/com/company/aitest/trace/TraceStepNormalizer.java](../../backend/src/main/java/com/company/aitest/trace/TraceStepNormalizer.java)

结论：

- 升级 Playwright 是有价值的
- 但当前轨迹准确性的主要来源，已经是平台自己的采集增强与后处理链路
- 因此升级 Playwright 的定位应为：**底层增稳、能力补丁、兼容性提升**

### 2.2 知识与生成现状

当前平台已经具备以下基础：

- `knowledge_document / knowledge_chunk`
- `trace_summary`
- `context_manifest`
- `llm_invocation_log`
- `prompt_snapshot`
- `business_pack`
- 项目级 / 系统级 TOM 分层
- RAG 检索中的 `scope / review_status / trust_level` 过滤框架

关键文件：

- [backend/src/main/java/com/company/aitest/llm/gateway/retrieval/RagRetrievalService.java](../../backend/src/main/java/com/company/aitest/llm/gateway/retrieval/RagRetrievalService.java)
- [backend/src/main/java/com/company/aitest/semantic/ProjectSemanticContextService.java](../../backend/src/main/java/com/company/aitest/semantic/ProjectSemanticContextService.java)
- [backend/src/main/java/com/company/aitest/trace/TraceSummaryService.java](../../backend/src/main/java/com/company/aitest/trace/TraceSummaryService.java)

结论：

- Loop + LLM Wiki + Mini-TOM 不是从零起步
- 平台已有很多可复用底座
- 当前主要欠缺的是：
  - Wiki 三层范围定义
  - Loop 后台统一开关
  - Loop 四类能力闭环
  - Wiki / TOM / Business Pack 的稳定协同

---

## 3. 目标问题

本方案要解决四类核心问题：

### 3.1 轨迹记录问题

- 采集底座版本落后，长期运行稳定性和兼容性有提升空间
- 事件、录屏、页面语义、摘要之间仍存在进一步对齐空间

### 3.2 用例生成问题

- 仅靠原始需求 + TOM 仍可能缺少规则背景和历史经验
- 对复杂业务，生成结果容易“结构对了，但业务味不够”
- 用户补充、冲突澄清、人工修正还没有形成稳定学习链路

### 3.3 知识沉淀问题

- 设计决策、历史交接、业务例外、兼容规则缺乏统一承载层
- 这些内容不适合直接塞进 TOM，但又不能丢失
- 只做项目级和系统级会导致“可复用但未系统化”的中间层缺失

### 3.4 学习回灌问题

- 用户补充、澄清问答、人工修改、轨迹修正没有形成系统性沉淀闭环
- 同类问题反复出现，系统无法稳定变准
- Loop 需要可控，不适合默认全局常开

---

## 4. 总体架构分层

### 4.1 分层定义

#### A. LLM Wiki：知识解释层

负责沉淀：

- 项目知识
- 设计决策
- 历史交接
- 业务规则说明
- 实现说明
- 已知限制
- 兼容口径
- 特殊例外
- FAQ / 历史经验

回答的问题：

- 为什么这样做
- 这里有哪些历史背景
- 这条规则有哪些例外
- 这个模块以前踩过什么坑
- 原型上有，但本期是不是要做

#### A-1. LLM Wiki 三层范围

LLM Wiki 不应只分项目级和系统级，应使用三层：

1. **项目级（PROJECT）**
   - 当前项目自己的知识沉淀
   - 优先级最高
   - 可直接参与当前项目分析与生成

2. **复用参考级（REUSABLE）**
   - 介于项目级与系统级之间
   - 表示“在多个项目可复用，但还不是系统标准”
   - 用于承载跨项目经验、规则模板、通用设计口径、常见实现模式
   - 默认只能作为参考，不能直接覆盖项目级确认内容

3. **系统级（SYSTEM）**
   - 平台级标准知识
   - 必须经过审核
   - 仅在项目级与复用参考级不足时补充

#### B. Mini-TOM：结构化事实层

负责沉淀：

- 业务对象
- 页面
- 字段
- 流程
- 状态
- 角色
- 动作
- 断言
- 页面关系
- 对象关系

回答的问题：

- 测什么
- 在哪测
- 围绕谁测
- 哪些状态和断言要覆盖

#### C. Business Pack：业务能力聚合层

负责把 TOM、页面画像、轨迹摘要、Wiki 等资产组织成业务域能力包。

回答的问题：

- 这是一个什么业务块
- 它依赖哪些页面、流程、规则、对象
- 在测试生成和分析时应该整体参考什么

#### D. Loop：持续学习层

负责把使用过程中暴露出来的缺口回灌成候选资产。

来源包括：

- 用户补充需求
- 澄清问答
- 人工修改分析
- 人工修改用例
- 轨迹修正
- 摘要修正
- 冲突确认结果
- 低证据高频问题

回答的问题：

- 系统哪里总理解错
- 哪些规则经常漏
- 哪些知识值得升级成 Wiki / TOM / Business Pack

#### D-1. Loop 的四类核心能力

本方案中的 Loop 主要聚焦四类子能力：

1. **生成质量回归 Loop**
2. **TOM 使用策略评估 Loop**
3. **轨迹摘要质量检查 Loop**
4. **状态中文化检查 Loop**

---

## 5. 边界规则

### 5.1 什么进入 LLM Wiki

应进入：

- 长文本规则说明
- 背景说明
- 设计原因
- 历史决策
- 兼容策略
- 例外逻辑
- 已知限制
- 风险经验

不应直接进入：

- 页面、字段、状态、角色、流程这类稳定结构对象本体
- 需要直接参与测试范围分析的结构化事实

### 5.2 什么进入 Mini-TOM

应进入：

- 稳定、可枚举、可引用、可被测试生成直接消费的结构对象

不应直接进入：

- 长篇背景
- 设计讨论
- 模糊经验
- 未确认的传闻式规则

### 5.3 Wiki 与 TOM 的关系

- Wiki 可以产出 TOM 候选
- TOM 可以引用 Wiki 作为来源说明
- Wiki 不能直接替代 TOM
- TOM 不存大段解释文本，只保留结构化结果和来源引用

### 5.4 生成时的消费规则

生成与分析时按以下逻辑消费：

1. **Mini-TOM 优先定结构范围**
2. **Business Pack 补业务聚合**
3. **LLM Wiki 补规则解释、边界、历史经验**
4. **Loop 在开启时补高频缺口提醒**

### 5.5 LLM Wiki 三层消费优先级

知识召回优先顺序固定为：

1. 项目级 Wiki
2. 复用参考级 Wiki
3. 系统级 Wiki

规则：

- 项目级优先
- 复用参考级只作补充，不抢占项目级
- 系统级作为兜底标准
- 外部经验不直接当事实
- 冲突时必须反问

---

## 6. Playwright 升级方案

### 6.1 升级目标

把本地采集器从 `playwright-core ^1.60.0` 升级到 `^1.61.0`。

### 6.2 升级定位

升级收益应定义为：

- 提升采集稳定性
- 改善浏览器兼容性
- 改善 artifacts / storage / CDP / WebSocket 等底层能力边界
- 为后续轨迹增强提供更好的基础版本

不应定义为：

- 单靠版本升级直接让轨迹准确率大幅跃迁

### 6.3 涉及文件

- [local-browser-worker/package.json](../../local-browser-worker/package.json)
- [packaging/build-client-package.sh](../../packaging/build-client-package.sh)
- [local-browser-worker/src/capture/index.ts](../../local-browser-worker/src/capture/index.ts)
- [local-browser-worker/src/browser/index.ts](../../local-browser-worker/src/browser/index.ts)

### 6.4 升级步骤

#### Step A：版本升级

- 升 `playwright-core`
- 更新 lockfile
- 重新准备本地 runtime / 打包缓存

#### Step B：采集回归验证

必须验证：

1. 登录态保持 / `storageState`
2. 页面打开与跳转
3. 弹窗、抽屉、表格行操作
4. 上传、下载、新开页
5. screencast / 帧时间轴
6. `normalized_locator` 输出
7. 后端清洗和摘要是否稳定

### 6.5 升级验收指标

至少对比以下指标：

- 事件丢失率
- 录屏/帧与事件时间偏差
- 定位歧义率
- 页面标题 / 区块标题 / 对象标签命中率
- 摘要后 TOM 命中率

### 6.6 升级结论标准

满足以下条件即可通过：

- 本地采集器启动、绑定、采集、停止均正常
- 不引入新的崩溃、闪退、严重兼容问题
- 采集结果与当前清洗/摘要链路兼容
- 至少不劣于升级前质量

---

## 7. Loop + LLM Wiki + Mini-TOM 主链路方案

### 7.1 用例生成链路

主链路：

1. 用户输入需求
2. 系统召回：
   - 项目级 Mini-TOM
   - 系统级 Mini-TOM
   - 项目级 Business Pack
   - 项目级 LLM Wiki
   - 复用参考级 LLM Wiki
   - 系统级 LLM Wiki
3. 输出：
   - 需求理解
   - 风险扫描
   - 评审前需确认问题
   - 澄清问题
   - 测试点拆解
4. 用户补充后自动重新分析
5. 用户输入“生成用例”才正式生成
6. 草稿带证据来源与低证据标记
7. Loop 开启时参与质量回归与回灌

### 7.2 轨迹记录链路

主链路：

1. Worker 采集轨迹
2. 轨迹清洗
3. 轨迹摘要生成
4. 人工确认摘要
5. 从摘要中提取：
   - Wiki 候选
   - TOM 候选
   - Business Pack 候选
6. 审核后生效
7. 后续分析和生成可继续消费
8. Loop 开启时参与摘要质量检查与状态中文化检查

### 7.3 Loop 回灌链路

可作为 Loop 输入的事件：

- 用户补充内容
- 用户回答澄清问题
- 用户人工改分析
- 用户人工改草稿
- 人工修正轨迹摘要
- 人工修正轨迹步骤
- 冲突确认结果
- 低证据命中但用户继续保留的内容

处理方式：

1. 记录 learning event
2. 聚类相似问题
3. 产出候选资产：
   - `wiki_candidate`
   - `tom_candidate`
   - `business_pack_candidate`
4. 人工审核后升级

---

## 8. 数据模型规划

### 8.1 LLM Wiki

建议新增：

#### `wiki_pack`

- `id`
- `project_id`
- `scope` (`PROJECT` / `REUSABLE` / `SYSTEM`)
- `name`
- `status` (`DRAFT` / `ACTIVE` / `INACTIVE` / `ARCHIVED`)
- `review_status`
- `trust_level`
- `source_type`
- `created_by`
- `created_at`
- `updated_at`

#### `wiki_entry`

- `id`
- `pack_id`
- `entry_type` (`RULE` / `DECISION` / `HISTORY` / `IMPLEMENTATION` / `EXCEPTION` / `FAQ`)
- `title`
- `content`
- `keywords_json`
- `source_refs_json`
- `review_status`
- `confidence`
- `effective_status`
- `created_by`
- `created_at`
- `updated_at`

#### `wiki_entry_relation`

- `id`
- `entry_id`
- `related_tom_id`
- `related_business_pack_id`
- `relation_type`

建议补充：

- `reusable_candidate`
- `promotion_status`（`NONE / TO_REUSABLE / TO_SYSTEM / REJECTED`）

### 8.2 Mini-TOM

沿用现有 TOM 模型，但补充：

- `source_refs_json`
- `wiki_refs_json`

目标：

- 每个 TOM 节点可追溯到 Wiki、轨迹摘要、页面扫描、业务包等来源

### 8.3 Loop

建议新增：

#### `learning_loop_event`

- `id`
- `project_id`
- `event_type`
- `source_stage`
- `raw_input`
- `normalized_issue`
- `suggested_asset_type` (`WIKI` / `TOM` / `BUSINESS_PACK`)
- `source_refs_json`
- `status`
- `created_by`
- `created_at`

#### `learning_loop_cluster`

- `id`
- `project_id`
- `theme`
- `event_count`
- `suggested_action`
- `target_asset_type`
- `status`
- `created_at`
- `updated_at`

#### `system_feature_toggle`

建议复用或新增统一系统开关表，以后台管理员控制 Loop 是否启用。

字段建议：

- `id`
- `feature_key`（如 `LOOP_ENGINE`）
- `enabled`（默认 `0`）
- `config_json`
- `updated_by`
- `updated_at`

#### `loop_module_config`

如需精细控制，可增加子模块配置：

- `module_key`
  - `GEN_CASE_QUALITY`
  - `TOM_STRATEGY_EVAL`
  - `TRACE_SUMMARY_QUALITY`
  - `STATUS_ZH_CHECK`
- `enabled`
- `config_json`

原则：

- 总开关优先级最高
- 总开关关闭时，子模块配置无效

---

## 9. 检索与优先级规划

### 9.1 检索顺序

1. 项目级 Mini-TOM
2. 系统级 Mini-TOM
3. 项目级 Business Pack
4. 项目级 LLM Wiki
5. 复用参考级 LLM Wiki
6. 系统级 LLM Wiki
7. 外部经验候选（默认不直接进入主上下文）

### 9.2 优先级规则

- 项目级 > 复用参考级 > 系统级
- 已确认 > 未确认
- ACTIVE > DRAFT
- TOM > Wiki
- 项目内知识 > 外部经验

### 9.3 冲突处理规则

当出现以下冲突时：

- 项目级 Wiki 与复用参考级 Wiki 冲突
- 项目级 Wiki 与系统级 Wiki 冲突
- 复用参考级 Wiki 与系统级 Wiki 冲突
- Wiki 与 TOM 冲突
- 项目级与系统级 TOM 冲突

系统行为：

1. 标记冲突
2. 输出澄清问题
3. 不伪装成已确认事实
4. 仅允许保守生成低置信草稿

---

## 10. 治理规则

### 10.1 生效规则

- 候选 Wiki 不生效
- 候选 TOM 不生效
- 候选 Business Pack 不生效
- 未审核系统级资产不生效
- 复用参考级资产在未审核时只能作低权重参考
- Loop 总开关关闭时，不允许任何 Loop 结果进入默认主链路

### 10.2 来源规则

所有资产必须带来源：

- 需求输入
- 轨迹摘要
- 页面画像
- 文档导入
- 人工补充
- 系统推断
- 外部资料

### 10.3 信任等级建议

- `PROJECT_CONFIRMED`
- `REUSABLE_APPROVED`
- `SYSTEM_APPROVED`
- `TRACE_CONFIRMED`
- `DOC_IMPORTED`
- `AI_INFERRED`
- `EXTERNAL_REFERENCE`

### 10.4 外部知识规则

- 可参考
- 不直接当事实
- 不可直接写入 TOM ACTIVE
- 不可直接覆盖项目级已确认知识

### 10.5 Loop 开关规则

Loop 必须具备统一后台开关：

1. **默认关闭**
2. **仅后台管理员可配置**
3. **关闭时系统不启用 Loop**
4. **打开时系统使用 Loop**
5. 可选支持子模块分别开关，但总开关优先级最高

总开关关闭时，以下行为全部禁用：

- 生成质量回归 Loop
- TOM 使用策略评估 Loop
- 轨迹摘要质量检查 Loop
- 状态中文化检查 Loop
- Loop 候选生成
- Loop 驱动的额外召回

### 10.6 Loop 四类能力定义

#### A. 生成质量回归 Loop

用途：

- 检查“分析 -> 测试点 -> 用例草稿”是否一致
- 识别草稿像测试数据、像字段列表、与测试点不对应等质量退化

触发点：

- 用例生成完成后
- 用户人工修改草稿后

输出：

- 质量问题记录
- 回灌候选规则

#### B. TOM 使用策略评估 Loop

用途：

- 评估本次分析/生成是否正确使用了项目级和系统级 TOM
- 识别 TOM 命中过少、命中错层、错把系统级当项目级等问题

触发点：

- 需求分析完成后
- 用例生成完成后

输出：

- TOM 使用偏差记录
- TOM 候选补全建议

#### C. 轨迹摘要质量检查 Loop

用途：

- 评估轨迹摘要是否丢步骤、丢业务目标、过度泛化、误抽象

触发点：

- 轨迹摘要生成后
- 用户修正摘要后

输出：

- 摘要质量问题
- Wiki / TOM 候选补充

#### D. 状态中文化检查 Loop

用途：

- 检查状态、动作、页面词是否已转换成稳定中文业务表达
- 减少“代码词 / 英文状态 / 技术词”直接流入用例和摘要

触发点：

- 轨迹清洗后
- 分析输出后
- 用例草稿生成后

输出：

- 中文化问题记录
- 规则包或 Wiki 候选

---

## 11. 分阶段实施规划

### Phase 1：Playwright 升级

目标：先补底层稳定性

交付物：

- Playwright 升级到 `1.61.0`
- 回归验证记录
- 升级影响结论

### Phase 2：LLM Wiki 数据层落地

目标：把知识解释层立起来

交付物：

- `wiki_pack / wiki_entry / wiki_entry_relation`
- 后台可查
- 基础审核状态
- 来源追溯字段
- 三层 scope：`PROJECT / REUSABLE / SYSTEM`

### Phase 3：主生成链路接入 LLM Wiki

目标：让需求分析、测试点拆解、用例生成消费 Wiki

交付物：

- `ProjectSemanticContextService` 接入 Wiki
- 分析 prompt 加 Wiki 规则
- 生成 prompt 加 TOM > Wiki 的约束
- `context_manifest` 可追踪本次使用的 Wiki 条目
- 召回优先级：项目级 > 复用参考级 > 系统级

### Phase 4：Loop 回灌层落地

目标：让系统持续学习，但保持可控

交付物：

- `system_feature_toggle` / Loop 后台控制
- `learning_loop_event`
- 候选聚类
- 自动生成 wiki/tom/business_pack 候选
- 审核入口
- 四类 Loop 子能力落地：
  - 生成质量回归
  - TOM 使用策略评估
  - 轨迹摘要质量检查
  - 状态中文化检查

### Phase 5：前端产品化

目标：形成可运营的知识包与学习回灌能力

交付物：

- Wiki 包管理页
- 来源追溯
- 冲突视图
- 候选审核视图
- 项目级 / 复用参考级 / 系统级切换
- Loop 总开关与子模块配置页

---

## 12. 验收标准

### 12.1 Playwright 升级验收

- 本地采集器可正常启动、绑定、采集、停止
- 轨迹、录屏、帧、摘要链路兼容
- 无新增严重回归
- 核心页面采集质量不劣于当前版本

### 12.2 LLM Wiki 验收

- 可创建项目级、复用参考级、系统级知识包
- 可标注来源、审核状态、信任等级
- 可被召回进入 `context_manifest`

### 12.3 TOM + Wiki 联动验收

- Wiki 可生成 TOM 候选
- TOM 可引用 Wiki 来源
- 生成时 TOM 优先、Wiki 辅助成立

### 12.4 Loop 验收

- 默认关闭
- 后台管理员可统一配置开启/关闭
- 开启后四类 Loop 子能力按配置生效
- 用户补充、澄清、修正可记录
- 可聚类相似问题
- 可生成候选资产

---

## 13. 推荐执行顺序

建议按以下顺序推进，不要并发做太多层：

1. **先做 Playwright 升级**
2. **再立 LLM Wiki 三层数据模型**
3. **再把 Wiki 接入需求分析和用例生成链路**
4. **最后补带后台开关的 Loop 回灌**

原因：

- 升级 Playwright 风险最小，收益最快
- LLM Wiki 是后续知识治理的基础
- 中间复用参考层必须先定义，否则项目级知识会被错误抬升到系统级
- Loop 必须在知识层和事实层定义清楚后再做，否则容易回灌进错误目标

---

## 14. 最终产品口径

建议统一对外描述为：

- **LLM Wiki 是项目知识、复用参考知识和系统标准知识的解释层，负责沉淀解释、背景、决策、例外和历史经验。**
- **Mini-TOM 是测试生成的结构化事实层，负责沉淀对象、页面、字段、流程、状态和断言。**
- **Business Pack 是业务能力聚合层，负责把事实和知识组织成可复用业务块。**
- **Loop 是持续学习层，默认关闭，由后台管理员统一配置；开启后负责把真实使用中的补充、修正和冲突回灌成新资产。**
- **生成链路中，Mini-TOM 负责定范围，LLM Wiki 负责补解释，Loop 在开启时负责持续纠偏。**

---

## 15. 下一步建议

如果按实施效率优先，建议立刻启动以下四个动作：

1. 把 `playwright-core` 升级到 `1.61.0` 并做回归
2. 新建 LLM Wiki 三层数据表与基础管理接口
3. 设计并落地 Loop 后台总开关
4. 在 `ProjectSemanticContextService` 中增加 Wiki signal 构建与检索注入

完成这四步后，平台就从“只有结构化事实层”正式升级为“知识层 + 事实层 + 聚合层 + 可控学习层”的可演化形态。

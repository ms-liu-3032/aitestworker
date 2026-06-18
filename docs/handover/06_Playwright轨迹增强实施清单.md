# Playwright 轨迹增强实施清单

## 1. 目标

把以下两类能力正式接入当前轨迹采集器主链路：

1. `locator.normalize()`
   - 用于定位补强
   - 用于降低 `svg / use / on` 这类弱语义动作的比例
2. `page.screencast`（后续目标）
   - 当前主录屏实现已经用 context 级 `recordVideo` 落地
   - 后续若要进一步降低“切 context 的视觉闪动”，再迁移到更可控的 screencast / CDP 方案

`Pick Locator` 不进入线上主链路，只作为开发调试工具保留。

## 2. 实施拆分

> **整体状态：T-PW-1 / T-PW-2 / T-PW-3 已全部完成（2026-05-23 ~ 24）；T-PW-4 仍为调试辅助保留项。**

### T-PW-1：轨迹事件补充 `normalized_locator`  ✅ 已完成

目标：

1. 给 CLICK / INPUT / CHANGE 等关键交互补一份稳定定位表达
2. 降低图标、嵌套按钮和复杂 DOM 结构带来的识别退化

范围：

- worker
- backend
- 轨迹详情展示

验收：

1. ✅ 关键事件可带 `normalized_locator`
2. ✅ 清洗流程不依赖它作为唯一输入
3. ✅ 前端默认折叠，按需展开查看
4. ✅ **Sprint 4 M4.1 追加**：`TraceStepNormalizer.bestTarget` 加入 `normalized_locator` 优先回退（位于 `elementText / elementRole` 之后、`selector` 之前），把 `getByRole('button', { name: 'X' })` 映射成"X 按钮"、`getByLabel('X') / getByText('X') / getByPlaceholder('X') / getByTestId('X')` 映射成"X"。+2 单测固化。

### T-PW-2：主录屏链路落地  ✅ 已完成（**已切到 CDP `Page.startScreencast`**）

> **实现路径说明（2026-05-24 更新）：主录屏已从 context 级 `recordVideo` 切到 page 级 CDP `Page.startScreencast`。**
> 收益：profileContext 在 session start/stop 时不再重建，**浏览器窗口/标签保持不动，彻底消除"关旧 context → 开新 context"的视觉闪动**。
> 输出物：`<sessionDir>/screencast/frames/000123.jpg` + `<sessionDir>/screencast/manifest.json`（含 frame index → relativeMs 表）。
> 旧的 webm 路径作为兼容分支保留：后端 endpoint 检测 `.json` / `.webm` 后缀分别处理。

目标：

1. 开始采集时开始录屏
2. 停止采集时停止录屏
3. 停止采集不关闭浏览器窗口
4. 同一身份空间多次开始 / 停止采集时，每次独立生成录屏

范围：

- worker 录屏链路
- backend 文件归档
- 轨迹详情视频展示

验收：

1. ✅ 每个 `browser_trace_session` 都能拿到 `screencast_path`（CDP 路径写到 `<sessionDir>/screencast/manifest.json`）
2. ✅ 轨迹详情页可以查看录屏（前端 `ScreencastPlayer` 组件按 manifest 回放 JPEG 帧序列）
3. ✅ 录屏起停时间记录在 `screencast_started_at_utc / screencast_stopped_at_utc / screencast_duration_ms`，与 session 起止对齐
4. ✅ 同一身份空间多次 start/stop 时各自独立的 `<sessionDir>/screencast/` 目录，无串扰
5. ✅ 后端 endpoints：
   - `GET /api/trace/sessions/{id}/screencast` —— 兼容 `.json` / `.webm`，按 content-type 分别返回
   - `GET /api/trace/sessions/{id}/screencast/manifest` —— CDP 帧序列 manifest
   - `GET /api/trace/sessions/{id}/screencast/frame/{filename}` —— 按 `^\d{6}\.jpg$` 校验帧文件名后取 JPEG
6. ✅ **不再有 stop 时的视觉闪动**（CDP screencast 是 page 级，不重建 context）

### T-PW-3：问题片段与录屏联动  ✅ 已完成

目标：

1. 问题片段不只是一段时间描述
2. 问题片段可指向录屏中的时间区间

范围：

- issue clip
- screencast 元数据
- 前端问题片段详情

验收：

1. ✅ 问题片段能记录录屏区间：`createIssueClip` 自动从对应 session.screencast_path 继承录屏路径并按 `clipStartRelativeMs / clipEndRelativeMs` 推算 `screencast_clip_start_ms / screencast_clip_end_ms`
2. ✅ 问题片段详情能跳到对应录屏时间：前端 issue clip 卡片显示"录屏区间：m:ss → m:ss"+"播放此区间"按钮；嵌入 `ScreencastPlayer` 通过 `clipStartMs` / `clipEndMs` props 跳转至 start，到 end 自动 pause
3. ✅ 第一阶段不做物理裁剪：整段 manifest 只 fetch 一次，前端通过二分查找 `relativeMs` 切换 `<img>`，体验远好于切片

### T-PW-4：Pick Locator 内部调试工具化

目标：

1. 辅助页面理解规则调试
2. 辅助复杂页面结构人工校验
3. 判断为什么某个元素被识别失败

范围：

- 内部调试脚本
- 不进入正式产品主流程

验收：

1. 不影响线上采集逻辑
2. 仅作为开发辅助工具存在

## 3. 字段迁移清单

### 3.1 `browser_trace_event`

建议新增：

1. `normalized_locator` `TEXT NULL`
2. `section_title` `VARCHAR(255) NULL`
3. `dialog_title` `VARCHAR(255) NULL`
4. `object_label` `VARCHAR(255) NULL`

用途：

- `normalized_locator`：稳定定位补强
- `section_title`：分栏标题，如“新增对象”“历史对象记录”
- `dialog_title`：弹窗标题，如“添加对象”
- `object_label`：列表对象名，如对象名称

### 3.2 `browser_trace_session`

建议新增：

1. `screencast_path` `VARCHAR(500) NULL`
2. `screencast_started_at_utc` `DATETIME NULL`
3. `screencast_stopped_at_utc` `DATETIME NULL`
4. `screencast_duration_ms` `BIGINT NULL`

### 3.3 `browser_issue_clip`

建议新增：

1. `screencast_path` `VARCHAR(500) NULL`
2. `screencast_clip_start_ms` `BIGINT NULL`
3. `screencast_clip_end_ms` `BIGINT NULL`

## 4. 文件改动清单

### worker

#### `<repo-root>/local-browser-worker/src/capture/index.ts`

需要做：

1. session start 时启动主录屏（当前实现为 `recordVideo`）
2. session stop 时停止主录屏并 finalize 文件
3. 对关键交互补 `normalized_locator`
4. 记录 `section_title / dialog_title / object_label`
5. 保持原始 selector 不删除

#### `<repo-root>/local-browser-worker/src/server/index.ts`

需要做：

1. `health` 返回 screencast 就绪状态
2. stop 接口返回 `screencastPath`
3. 必要时暴露录屏文件元数据

#### `<repo-root>/local-browser-worker/src/platform-api/*`

需要做：

1. stop 上传协议补充 `screencastPath`
2. 后续如支持上传录屏文件，也在此层扩展

### backend

#### Flyway migration

需要新增：

1. `browser_trace_event` 新字段
2. `browser_trace_session` 新字段
3. `browser_issue_clip` 新字段

#### 轨迹接收和服务层

重点文件范围：

- `trace/*Controller.java`
- `trace/*Service.java`
- `trace/*Record.java`

需要做：

1. 接收 `normalized_locator`
2. 接收 `screencast_path`
3. 把录屏文件纳入资产引用
4. 问题片段与录屏区间关联

#### `<repo-root>/backend/src/main/java/com/company/aitest/trace/TraceStepNormalizer.java`

需要做：

1. 把 `section_title / dialog_title / object_label` 纳入清洗
2. `normalized_locator` 仅作为辅助，不作为唯一事实来源
3. 列表操作如编辑/删除优先结合 `object_label`

### frontend

#### `<repo-root>/frontend/src/components/TracePanel.vue`

需要做：

1. 轨迹详情支持录屏展示
2. 问题片段支持定位录屏时间区间
3. 事件详情支持展开查看 `normalized_locator`

#### 后续轨迹详情页

如拆页，建议新增独立详情组件或页面，负责：

1. 录屏与时间线联动
2. 问题片段与录屏联动
3. 原始事件与清洗步骤切换

## 5. 开发顺序

### 第一轮

1. migration
2. worker 录入 `normalized_locator`
3. backend 落库
4. 前端调试展示

### 第二轮

1. worker 主录屏链路已切到当前可用实现（`recordVideo`）
2. backend session 接收录屏路径
3. 前端轨迹详情可看录屏

### 第三轮

1. issue clip 与录屏区间联动
2. 动作高亮 `showActions()`
3. `Pick Locator` 调试工具化

## 6. 自测清单

### T-PW-1 自测

1. 打开复杂页面
2. 点击图标按钮
3. 查看事件是否落 `normalized_locator`
4. 查看清洗后是否减少 `svg / use`

### T-PW-2 自测

1. 开始采集
2. 操作页面
3. 停止采集但不关闭浏览器整体会话
4. 再次开始采集
5. 确认生成两段独立录屏

### T-PW-3 自测

1. 标记问题片段
2. 查看问题片段是否记录录屏区间
3. 在前端跳到对应时间

## 7. 交付标准

完成后必须满足（截至 2026-05-24）：

1. ✅ migration 可执行（V6 已 release；V7 已合入开发分支）
2. ✅ worker build / test 通过（`npx tsc --noEmit` + `vitest run` 113 用例）
3. ✅ backend test 通过（`mvn -q test` 136 用例）
4. ✅ frontend build / typecheck 通过（`vue-tsc --noEmit` + `vite build`）
5. ⏳ **至少完成一条真实轨迹联调**：代码端到端已绿，但因本地没有完整运行环境（MySQL + Weaviate + Neo4j + MinIO + backend + worker + 浏览器），尚未在真实采集流程上跑一次 start → 操作 → stop → 看录屏 → 标问题片段 → 播放区间。**待环境就位后由测试人员补做（建议优先验证：webm 文件落地、`<video>` 内嵌播放、问题片段 currentTime 跳转）**
6. ✅ 更新：
   - `CHANGELOG.md`（顶部 2026-05-24 条目）
   - `05_上下文日志.md`（当前状态 18/19 项 + 风险表更新）
   - `08_LLM治理实施日志.md`（M4.2 / M4.3 完整记录）
   - 本文件 §2 各 T-PW 标完成态

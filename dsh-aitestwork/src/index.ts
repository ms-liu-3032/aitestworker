import type { Context } from '@deepseek-ai/cordis'
import '@deepseek-ai/dsh-commands'
import '@deepseek-ai/dsh-skill'
import '@deepseek-ai/dsh-system-prompt'
import { defineTool } from '@deepseek-ai/dsh-tools'
import { readFileSync } from 'node:fs'
import { BINDING_KEY, callApi, callUserApi, currentBinding, parseServerUrl, projectPath, type Binding } from './client.js'
import { CaptureRuntime } from './capture.js'
import { downloadCaptureEvidence } from './evidence.js'
import { executeAutomationAsset, type Mapping } from './automation.js'
import { executeApiAutomationAsset } from './api-automation.js'
import { LocalDesignStore, localDesignRules, type LocalDesignInput } from './local-design.js'
import { OfflineAutomation } from './offline-automation.js'
import { LocalCoverageStore } from './local-coverage.js'
import { registerAdminLogTools } from './admin-log-tools.js'
import { copyUiAssetVersion, findAutoAssetCandidates, type MappingEdit } from './asset-reuse.js'
import { displayReport } from './report-display.js'
import { designModeApproval, executionScope, EXECUTION_TOOLS, SCOPE_TTL_MS } from './rule-guard.js'
import { unclassifiedTool } from './tool-boundaries.js'

export const name = 'aitestwork'
export const inject = ['tools', 'commands', 'credentials', 'skills', 'systemPrompt']

function asText(value: unknown): string {
  return JSON.stringify(value)
}

function asVisibleReport(value: { reportPath?: string; result?: string }, kind: string): string {
  if (process.env.NODE_TEST_CONTEXT) return asText(value)
  return asText({ ...value, ...displayReport(value, kind) })
}

function bindingPrincipal(binding: Binding): string {
  return JSON.stringify([binding.serverUrl, binding.userId, binding.connectionId])
}

function renderAutomationResult(value: string): Array<{ type: 'text'; text: string }> {
  try {
    const run = JSON.parse(value) as { result?: string; status?: string; reportPath?: string; reportViewerPath?: string;
      reportError?: string; windowCloseError?: string; browserWindowClosed?: boolean }
    const result = run.result === 'PASS' ? '单条用例通过；整轮规则覆盖须查看任务判定' : run.result ? `未通过（${run.result}）` : '未执行'
    const storage = run.status === 'LOCAL_ONLY' ? '仅本机保存，未回写 AITestWork'
      : run.status === 'LOCAL_CAPTURE_INCOMPLETE' ? '本机采集未完成，未回写 AITestWork'
      : run.status === 'RECORDED' ? '已回写 AITestWork'
      : run.status === 'EVIDENCE_PENDING' ? '证据待上传，运行尚未入库'
        : run.status === 'RUN_WRITE_PENDING' ? '证据已上传，运行结果待回写' : '状态待确认'
    const viewer = run.reportViewerPath ? `\n报告列表：${run.reportViewerPath}` : ''
    const report = run.reportPath ? `本地测试报告：${run.reportPath}${viewer}`
      : run.reportError ? `本地测试报告生成失败：${run.reportError}` : '本次没有生成本地测试报告'
    const window = run.browserWindowClosed === undefined ? '' : run.browserWindowClosed
      ? '浏览器窗口回收：已完成\n' : `浏览器窗口回收：失败（${run.windowCloseError || '原因待查'}）\n`
    return [{ type: 'text', text: `执行结果：${result}\n回写状态：${storage}\n${window}${report}\n${value}` }]
  } catch { return [{ type: 'text', text: value }] }
}

function stableJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>
    return `{${Object.keys(object).sort().map(key => `${JSON.stringify(key)}:${stableJson(object[key])}`).join(',')}}`
  }
  return JSON.stringify(value)
}

export function apply(ctx: Context) {
  if (typeof ctx.tools.guard === 'function') ctx.tools.guard(exec => unclassifiedTool(exec.name))
  const adminLogs = registerAdminLogTools(ctx)
  ctx.systemPrompt.section({
    name: 'aitestwork:local-test-mode',
    order: 89,
    text: '仅用户要求本地测试或未绑定且请求通用测试时使用本地工具；AITestWork 项目任务须先准备 AITestWork 资产，连接失败不可静默降级。完整方法见 aitestwork Skill。',
  })
  ctx.systemPrompt.section({
    name: 'aitestwork:case-design-preflight',
    order: 90,
    text: 'AITestWork 项目用例设计先取得用户选择的 TOM 模式并调用 aitestwork_prepare_case_design；创建任务后，补查范围由该任务保存的初始模式决定。读取返回规则并加载 aitestwork Skill。准备失败即停止 AITestWork 用例写入。',
  })
  ctx.systemPrompt.section({
    name: 'aitestwork:complete-batch-automation',
    order: 91,
    text: '大批任务先估算能否一轮完成；不能时自行拆分并可用 workflow，无须用户输入 workflow 关键词。先固定目标 ID，末尾核对完成项、失败项及证据，遗漏继续处理。细节见 aitestwork Skill。',
  })
  ctx.systemPrompt.section({
    name: 'aitestwork:reuse-before-ui-automation',
    order: 92,
    text: '每次准备 UI/API 自动化先用 aitestwork_prepare_auto_execution 搜索目标项目、跨项目复用版和系统级版；修改相似 UI 场景再用 aitestwork_find_auto_asset_candidates 补查已有映射。确无适用候选才重新生成。细节见 aitestwork Skill。',
  })
  const skillFile = new URL('../skills/aitestwork/SKILL.md', import.meta.url)
  const skillText = readFileSync(skillFile, 'utf8')
  const skillBody = skillText.replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n/, '')
  ctx.skills.register({
    name: 'aitestwork',
    description: 'Design and execute tests with local-only DSH tools or connected AITestWork project assets.',
    whenToUse: '用户要求本地测试，或基于 AITestWork 项目资产设计和执行测试时使用。',
    source: 'runtime',
    content: skillBody,
  })
  const localDesigns = new LocalDesignStore()
  const localCoverage = new LocalCoverageStore()
  const offlineAutomation = new OfflineAutomation(undefined, undefined, adminLogs)
  ctx.tools.register(defineTool({
    name: 'aitestwork_local_coverage_start',
    description: '无需 AITestWork，建立本机 UI 规则覆盖任务并返回完整通用规则快照。单用例 PASS 不代表任务完成。',
    parameters: { targetOrigin: { type: 'string', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ targetOrigin }) { return asText(await localCoverage.start(targetOrigin)) },
  }))
  ctx.tools.register(defineTool({
    name: 'aitestwork_local_coverage_decide',
    description: '逐条登记本机规则是否适用及来源理由；未知规则保持 UNKNOWN，不能全量通过。',
    parameters: { taskId: { type: 'string', required: true }, ruleCode: { type: 'string', required: true },
      applicability: { type: 'string', required: true }, reason: { type: 'string', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ taskId, ruleCode, applicability, reason }) {
      return asText(await localCoverage.decide(taskId, ruleCode,
        applicability as 'UNKNOWN' | 'APPLICABLE' | 'NOT_APPLICABLE', reason))
    },
  }))
  ctx.tools.register(defineTool({
    name: 'aitestwork_local_coverage_add_rule',
    description: '为当前本机任务增加跨业务可复用的规则类型；在确认清单前使用，规则需有可验证的检查方式。',
    parameters: { taskId: { type: 'string', required: true }, ruleCode: { type: 'string', required: true },
      title: { type: 'string', required: true }, checkKind: { type: 'string', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ taskId, ruleCode, title, checkKind }) {
      return asText(await localCoverage.addRule(taskId, ruleCode, title, checkKind as 'FIELD_PAIR' | 'RUN_ASSERTION'))
    },
  }))
  ctx.tools.register(defineTool({
    name: 'aitestwork_local_coverage_add_check',
    description: '为已适用的本机 UI 规则登记独立场景、页面和事实来源。',
    parameters: { taskId: { type: 'string', required: true }, ruleCode: { type: 'string', required: true },
      subjectKey: { type: 'string', required: true }, page: { type: 'string', required: true },
      label: { type: 'string', required: true }, sourceBasis: { type: 'string', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ taskId, ruleCode, subjectKey, page, label, sourceBasis }) {
      return asText(await localCoverage.addItem(taskId, ruleCode, subjectKey, page, label, sourceBasis))
    },
  }))
  ctx.tools.register(defineTool({
    name: 'aitestwork_local_coverage_confirm',
    description: '请本机 DSH 使用者确认本轮 UI 规则及场景清单。宿主审批拒绝或不可用时不得确认；Agent 不能自行确认。',
    parameters: { taskId: { type: 'string', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ taskId }) { return asText(await localCoverage.confirm(taskId)) },
  }))
  ctx.tools.register(defineTool({
    name: 'aitestwork_local_coverage_attach',
    description: '关联本机真实 PASS 运行的采集会话 ID。FIELD_PAIR 必须分别传正反两个会话，RUN_ASSERTION 只传正向会话。',
    parameters: { taskId: { type: 'string', required: true }, itemId: { type: 'string', required: true },
      positiveSessionId: { type: 'number', required: true }, negativeSessionId: { type: 'number' } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ taskId, itemId, positiveSessionId, negativeSessionId }) {
      return asText(await localCoverage.attach(taskId, itemId, positiveSessionId, negativeSessionId))
    },
  }))
  ctx.tools.register(defineTool({
    name: 'aitestwork_local_coverage_result',
    description: '读取本机整轮 UI 规则覆盖判定。只有 verdict.result=PASS 才可报告已确认规则与场景覆盖完成。',
    parameters: { taskId: { type: 'string', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ taskId }) { return asText(await localCoverage.get(taskId)) },
  }))
  ctx.tools.register(defineTool({
    name: 'aitestwork_local_design_rules',
    description: '无需 AITestWork 绑定，读取插件内的通用测试点、独立用例和本地保存规则；此结果不包含项目知识。',
    parameters: {},
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute() { return asText(localDesignRules) },
  }))
  ctx.tools.register(defineTool({
    name: 'aitestwork_local_save_design',
    description: '本机保存测试设计草稿。先生成测试点，可先传空 cases；补写用例时带原设计 ID、expectedVersion 和已有用例 ID 原位更新。不会写 AITestWork。',
    parameters: { design: { type: 'object', required: true, additionalProperties: true,
      description: 'requirementText、testPoints[{id,title,pointType,sourceBasis}]、cases[{id?,title,moduleName,precondition?,steps,expectedResult,sourceTestPointRefs,sourceBasis,mapping?}]；更新时加 id 和 expectedVersion' } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ design }) { return asText(await localDesigns.save(design as LocalDesignInput)) },
  }))
  ctx.tools.register(defineTool({
    name: 'aitestwork_local_list_designs',
    description: '列出本机测试设计及用例数量；不查询 AITestWork。',
    parameters: {},
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute() { return asText(await localDesigns.list()) },
  }))
  ctx.tools.register(defineTool({
    name: 'aitestwork_local_get_design',
    description: '读取本机测试点、完整用例和执行映射。',
    parameters: { designId: { type: 'string', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ designId }) { return asText(await localDesigns.get(designId)) },
  }))
  ctx.tools.register(defineTool({
    name: 'aitestwork_local_set_mapping',
    description: '为本机设计中的一条用例保存 Playwright 页面、步骤、定位与断言映射；同一用例 ID 原位修订，不调用 AITestWork。',
    parameters: { designId: { type: 'string', required: true }, caseId: { type: 'string', required: true },
      expectedVersion: { type: 'number', required: true },
      mapping: { type: 'object', required: true, additionalProperties: true,
        description: 'page、steps[{type,locator?,data?,waitBefore?,waitForResponse?}]、assertions[{by,value,expected?}]；无可观察状态的异步重置可用 {type:"wait",timeoutMs:500}（100–30000 毫秒，整份映射累计最多 60000 毫秒，无 locator）；滚轮用 scroll + deltaY，拖拽用 drag + deltaX/deltaY；其他动作需 locator，最后仍须断言' } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ designId, caseId, expectedVersion, mapping }) {
      return asText(await localDesigns.setMapping(designId, caseId, expectedVersion, mapping as Mapping))
    },
  }))
  ctx.tools.register(defineTool({
    name: 'aitestwork_run_local_ui',
    description: '无需 AITestWork 绑定，在本机 Playwright 执行已保存用例映射并生成含截图的 HTML 报告和原始 trace；执行结果仅保存在本机。',
    parameters: { designId: { type: 'string', required: true }, caseId: { type: 'string', required: true },
      targetUrl: { type: 'string', required: true, description: '被测站点 HTTP/HTTPS 根地址' },
      params: { type: 'object', additionalProperties: true, description: '运行时字符串测试数据' },
      headless: { type: 'boolean', description: '默认有界面；true 为无头执行' } },
    output: { schema: { type: 'string' }, render: (_args, value) => renderAutomationResult(value) },
    async execute({ designId, caseId, targetUrl, params, headless }) {
      const design = await localDesigns.get(designId)
      const testCase = design.cases.find(item => item.id === caseId)
      if (!testCase?.mapping) throw new Error('本地用例尚未保存 UI 执行映射')
      return asVisibleReport(await offlineAutomation.run(testCase.mapping, targetUrl,
        (params || {}) as Record<string, string>, headless || false, testCase.title), '本地 UI')
    },
  }))
  const reviewedDrafts = new Map<string, string>()
  const reviewedStages = new Map<string, string>()
  const reviewedStyleCandidates = new Map<string, string>()
  const preparedDesigns = new Map<string, number>()
  const validatedCases = new Map<string, Set<string>>()
  const approvedExecutionScopes = new Map<string, number>()
  const preparationKey = (projectId: number, tomMode: string, requirementText: string) =>
    JSON.stringify([projectId, tomMode, requirementText])
  const sessionKey = (projectId: number, workSessionId: number) => `${projectId}:${workSessionId}`
  const capture = new CaptureRuntime()
  if (typeof ctx.effect === 'function') ctx.effect(() => async () => { await capture.shutdown() })
  if (typeof ctx.on === 'function') ctx.on('tools/pre-execute', async (exec, next) => {
    const unclassified = unclassifiedTool(exec.name)
    if (unclassified) return { kind: 'deny' as const, reason: unclassified }
    if (exec.name === 'aitestwork_local_coverage_confirm') {
      const values = exec.arguments as Record<string, unknown> | undefined
      if (!values || typeof values.taskId !== 'string')
        return { kind: 'deny' as const, reason: '本地规则任务 ID 无效' }
      const task = await localCoverage.get(values.taskId)
      return { kind: 'ask' as const, reason: `请核对本机 UI 任务 ${task.id} 对 ${task.targetOrigin} 的完整规则和场景清单：${task.rules.map(rule => `${rule.code}=${rule.applicability}`).join('；')}。确认后 DSH 才会允许该清单进入全量覆盖判定。` }
    }
    if (exec.name === 'aitestwork_confirm_ui_coverage') {
      try {
        const values = exec.arguments as Record<string, unknown> | undefined
        const projectId = Number(values?.projectId)
        const taskId = Number(values?.taskId)
        if (!Number.isSafeInteger(projectId) || projectId < 1 || !Number.isSafeInteger(taskId) || taskId < 1)
          throw new Error('规则覆盖项目或任务 ID 无效')
        const task = await callApi<{ taskKey: string; targetOrigin: string;
          rules: { ruleCode: string; applicability: string; reason?: string }[];
          obligations: { ruleCode: string; fieldLabel: string; sourceBasis: string }[] }>(
            await currentBinding(ctx), `${projectPath(projectId)}/ui-coverage/${taskId}`)
        if (task.obligations.length > 20) throw new Error('覆盖项超过 20 条，请在 AITestWork Web 完整审核后确认')
        const rules = task.rules.map(rule => `${rule.ruleCode}=${rule.applicability}（${rule.reason || '无依据'}）`).join('；')
        const items = task.obligations.map(item => `${item.ruleCode}:${item.fieldLabel}（${item.sourceBasis}）`).join('；')
        return { kind: 'ask' as const, reason: `请确认项目 #${projectId} 的 UI 任务 ${task.taskKey} 对 ${task.targetOrigin} 的全部规则与场景清单。规则：${rules}。场景：${items}。确认后才能判定整轮覆盖。` }
      } catch (error) {
        return { kind: 'deny' as const, reason: error instanceof Error ? error.message : '规则清单读取失败' }
      }
    }
    if (exec.name === 'aitestwork_prepare_case_design') {
      try { return { kind: 'ask' as const, reason: designModeApproval(exec.name, exec.arguments)! } }
      catch (error) { return { kind: 'deny' as const, reason: error instanceof Error ? error.message : 'TOM 模式无效' } }
    }
    if (!EXECUTION_TOOLS.has(exec.name)) return next()
    try {
      const principal = exec.name === 'aitestwork_run_local_ui' ? 'LOCAL'
        : bindingPrincipal(await currentBinding(ctx))
      const scope = executionScope(exec.name, exec.arguments, principal)!
      if ((approvedExecutionScopes.get(scope.key) || 0) > Date.now()) return next()
      return { kind: 'ask' as const, reason: scope.reason }
    } catch (error) {
      return { kind: 'deny' as const, reason: error instanceof Error ? error.message : '执行范围无效' }
    }
  })
  if (typeof ctx.on === 'function') ctx.on('tools/execute', async (exec, next) => {
    if (EXECUTION_TOOLS.has(exec.name)) {
      const principal = exec.name === 'aitestwork_run_local_ui' ? 'LOCAL'
        : bindingPrincipal(await currentBinding(ctx))
      const scope = executionScope(exec.name, exec.arguments, principal)!
      if ((approvedExecutionScopes.get(scope.key) || 0) <= Date.now())
        approvedExecutionScopes.set(scope.key, Date.now() + SCOPE_TTL_MS)
    }
    if (exec.name.startsWith('aitestwork_local_') || exec.name === 'aitestwork_run_local_ui') return next()
    if (!exec.name.startsWith('aitestwork_')) return next()
    const started = Date.now()
    async function report(state: string, errorCode?: string) {
      try {
        const binding = await currentBinding(ctx)
        await fetch(`${binding.serverUrl}/api/aitestwork/dsh/tools/telemetry`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${binding.agentToken}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ toolName: exec.name, callId: String(exec.callId), state,
            durationMs: state === 'STARTED' ? null : Date.now() - started, errorCode }),
          signal: AbortSignal.timeout(2_000),
        })
      } catch { /* telemetry must not block testing */ }
    }
    await report('STARTED')
    try {
      const result = await next()
      await report(exec.signal.aborted ? 'ABORTED' : result.isError ? 'FAILED' : 'SUCCEEDED',
        result.isError ? result.error.info?.code : undefined)
      return result
    } catch (error) {
      await report(exec.signal.aborted ? 'ABORTED' : 'FAILED', error instanceof Error ? error.name : 'UNKNOWN')
      throw error
    }
  })
  ctx.commands.register({
    name: 'aitestwork-bind',
    description: '将当前本机 DSH 绑定到 AITestWork 账户',
    input: { hint: '<服务地址> <Web 页面生成的 12 位绑定码>' },
    recordInput: false,
    async handler({ rawInput }) {
      try {
        if (capture.hasActive()) throw new Error('请先停止并上传当前浏览器采集，再切换绑定')
        const [rawUrl, code, ...extra] = rawInput.trim().split(/\s+/)
        if (!rawUrl || !code || extra.length) throw new Error('用法：/aitestwork-bind <服务地址> <绑定码>')
        const serverUrl = parseServerUrl(rawUrl)
        const normalizedCode = code.replace(/-/g, '').toUpperCase()
        if (!/^[A-HJ-NP-Z2-9]{12}$/.test(normalizedCode)) {
          throw new Error('绑定码应为 Web 设置页生成的 12 位大写字母/数字；请重新生成并复制完整命令')
        }
        const response = await fetch(`${serverUrl}/api/aitestwork/dsh/bind`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ code: normalizedCode, deviceName: `DSH ${process.platform}` }),
          signal: AbortSignal.timeout(20_000),
        })
        const parsed = await response.json() as { success?: boolean; data?: { userToken: string; agentToken: string; userId: number; connectionId: number }; message?: string }
        if (response.status === 401) throw new Error('服务拒绝 DSH 绑定（HTTP 401）；请确认地址指向已部署 DSH 接口的 AITestWork 后端，并检查网关认证')
        if (!response.ok || !parsed.success || !parsed.data?.userToken || !parsed.data.agentToken) throw new Error(parsed.message || '绑定失败')
        const binding: Binding = { serverUrl, ...parsed.data }
        await ctx.credentials.set(BINDING_KEY, JSON.stringify(binding))
        reviewedDrafts.clear()
        reviewedStages.clear()
        reviewedStyleCandidates.clear()
        preparedDesigns.clear()
        validatedCases.clear()
        approvedExecutionScopes.clear()
        return { kind: 'success' as const, text: `成功：AITestWork 已绑定，用户 #${binding.userId}。${serverUrl.startsWith('http://') ? '当前使用 HTTP，连接凭据在网络中明文传输，请仅在可信网络使用。' : ''}` }
      } catch (error) {
        return { kind: 'error' as const, text: `失败：${error instanceof Error ? error.message : '绑定失败'}` }
      }
    },
  })

  ctx.commands.register({
    name: 'aitestwork-unbind',
    description: '移除本机 AITestWork 连接凭据；可在 Web 页面撤销连接',
    recordInput: false,
    async handler() {
      if (capture.hasActive()) return { kind: 'error' as const, text: '请先停止并上传当前浏览器采集，再解绑' }
      await ctx.credentials.unset(BINDING_KEY)
      reviewedDrafts.clear()
      reviewedStages.clear()
      reviewedStyleCandidates.clear()
      preparedDesigns.clear()
      validatedCases.clear()
      approvedExecutionScopes.clear()
      return { kind: 'success' as const, text: '本机 AITestWork 凭据已移除。' }
    },
  })

  ctx.commands.register({
    name: 'aitestwork-status',
    description: '查看本机 AITestWork 服务地址、绑定用户和连接状态',
    async handler() {
      try {
        const binding = await currentBinding(ctx)
        const projects = await callApi<unknown[]>(binding, '/api/aitestwork/dsh/tools/projects')
        return { kind: 'success' as const,
          text: `成功：AITestWork 已连接\n服务地址：${binding.serverUrl}\n用户 ID：${binding.userId}\n可访问项目：${projects.length} 个` }
      } catch (error) {
        return { kind: 'error' as const,
          text: `失败：${error instanceof Error ? error.message : 'AITestWork 连接检查失败'}` }
      }
    },
  })

  ctx.commands.register({
    name: 'aitestwork-project-create',
    description: '本人在 AITestWork 创建测试项目，返回项目 ID',
    input: { hint: '<项目名称> [| 项目说明]' },
    async handler({ rawInput }) {
      try {
        const separator = rawInput.indexOf('|')
        const projectName = (separator < 0 ? rawInput : rawInput.slice(0, separator)).trim()
        const description = separator < 0 ? '' : rawInput.slice(separator + 1).trim()
        if (!projectName) throw new Error('用法：/aitestwork-project-create <项目名称> [| 项目说明]')
        const project = await callUserApi<{ id: number; projectName: string }>(await currentBinding(ctx),
          '/api/aitestwork/dsh/user/projects', 'POST', { projectName, description })
        return { kind: 'success' as const, text: `成功：项目「${project.projectName}」已创建，项目 ID：${project.id}。` }
      } catch (error) {
        return { kind: 'error' as const, text: `失败：${error instanceof Error ? error.message : '创建项目失败'}` }
      }
    },
  })

  ctx.commands.register({
    name: 'aitestwork-stage',
    description: '本人查看本轮测试任务的某个阶段完整内容',
    input: { hint: '<项目 ID> <任务 ID> <阶段名>' },
    async handler({ rawInput }) {
      try {
        const [project, session, stageKey] = rawInput.trim().split(/\s+/)
        const projectId = Number(project)
        const sessionId = Number(session)
        if (!stageKey || !Number.isSafeInteger(projectId) || !Number.isSafeInteger(sessionId)) {
          throw new Error('用法：/aitestwork-stage <项目 ID> <任务 ID> <阶段名>')
        }
        const stages = await callUserApi<Array<{ stageKey: string; payloadJson: string; contentHash: string; status: string }>>(
          await currentBinding(ctx), `/api/aitestwork/dsh/user/projects/${projectId}/work-sessions/${sessionId}/stages`)
        const found = stages.find(item => item.stageKey === stageKey)
        if (!found) throw new Error('该阶段尚未保存')
        reviewedStages.set(`${projectId}:${sessionId}:${stageKey}`, found.contentHash)
        return { kind: 'success' as const, text: `${stageKey} (${found.status})\n${JSON.stringify(JSON.parse(found.payloadJson), null, 2)}` }
      } catch (error) {
        return { kind: 'error' as const, text: error instanceof Error ? error.message : '读取阶段失败' }
      }
    },
  })

  ctx.commands.register({
    name: 'aitestwork-confirm-stage',
    description: '本人确认已经查看的需求、测试点或覆盖阶段',
    input: { hint: '<项目 ID> <任务 ID> <阶段名>' },
    async handler({ rawInput }) {
      try {
        const [project, session, stageKey] = rawInput.trim().split(/\s+/)
        const projectId = Number(project)
        const sessionId = Number(session)
        const reviewedHash = reviewedStages.get(`${projectId}:${sessionId}:${stageKey}`)
        if (!reviewedHash) throw new Error('请先运行 /aitestwork-stage 查看该阶段')
        await callUserApi(await currentBinding(ctx),
          `/api/aitestwork/dsh/user/projects/${projectId}/work-sessions/${sessionId}/stages/${stageKey}/confirm`,
          'POST', { reviewedHash })
        reviewedStages.delete(`${projectId}:${sessionId}:${stageKey}`)
        return { kind: 'success' as const, text: `${stageKey} 已由本人确认。` }
      } catch (error) {
        return { kind: 'error' as const, text: error instanceof Error ? error.message : '确认阶段失败' }
      }
    },
  })

  ctx.commands.register({
    name: 'aitestwork-drafts',
    description: '列出本人在项目中的草稿，供审核和提交',
    input: { hint: '<项目 ID>' },
    async handler({ rawInput }) {
      try {
        const projectId = Number(rawInput.trim())
        const data = await callUserApi(await currentBinding(ctx), `/api/aitestwork/dsh/user/projects/${projectId}/drafts`)
        return { kind: 'success' as const, text: JSON.stringify(data, null, 2) }
      } catch (error) {
        return { kind: 'error' as const, text: error instanceof Error ? error.message : '读取草稿失败' }
      }
    },
  })

  ctx.commands.register({
    name: 'aitestwork-draft',
    description: '由本人查看个人草稿完整内容，以便审核',
    input: { hint: '<项目 ID> <草稿 ID>' },
    async handler({ rawInput }) {
      try {
        const [projectId, draftId] = rawInput.trim().split(/\s+/).map(Number)
        if (!Number.isSafeInteger(draftId) || draftId < 1) throw new Error('用法：/aitestwork-draft <项目 ID> <草稿 ID>')
        const data = await callUserApi<{ draft: unknown; fingerprint: string }>(await currentBinding(ctx), `/api/aitestwork/dsh/user/projects/${projectId}/drafts/${draftId}`)
        reviewedDrafts.set(`${projectId}:${draftId}`, data.fingerprint)
        return { kind: 'success' as const, text: JSON.stringify(data.draft, null, 2) }
      } catch (error) {
        return { kind: 'error' as const, text: error instanceof Error ? error.message : '读取草稿失败' }
      }
    },
  })

  ctx.commands.register({
    name: 'aitestwork-submit',
    description: '本人核对草稿后，输入完整标题显式提交正式用例',
    input: { hint: '<项目 ID> <草稿 ID> <审核过的完整标题>' },
    async handler({ rawInput }) {
      try {
        const matched = rawInput.trim().match(/^(\d+)\s+(\d+)\s+(.+)$/)
        if (!matched) throw new Error('用法：/aitestwork-submit <项目 ID> <草稿 ID> <审核过的完整标题>')
        const [, projectId, draftId, reviewedTitle] = matched
        const reviewedFingerprint = reviewedDrafts.get(`${projectId}:${draftId}`)
        if (!reviewedFingerprint) throw new Error('请先运行 /aitestwork-draft 查看并审核该草稿')
        await callUserApi(await currentBinding(ctx), `/api/aitestwork/dsh/user/projects/${Number(projectId)}/drafts/${Number(draftId)}/submit`, 'POST', { reviewedTitle, reviewedFingerprint })
        reviewedDrafts.delete(`${projectId}:${draftId}`)
        return { kind: 'success' as const, text: `草稿 #${draftId} 已由本人提交到正式用例库。` }
      } catch (error) {
        return { kind: 'error' as const, text: error instanceof Error ? error.message : '提交失败' }
      }
    },
  })

  ctx.commands.register({
    name: 'aitestwork-style',
    description: '本人设置项目内的用例写作偏好；留空可清除',
    input: { hint: '<项目 ID> <写作偏好；留空清除>' },
    async handler({ rawInput }) {
      try {
        const matched = rawInput.trim().match(/^(\d+)(?:\s+([\s\S]*))?$/)
        if (!matched) throw new Error('用法：/aitestwork-style <项目 ID> <写作偏好>')
        const projectId = Number(matched[1])
        await callUserApi(await currentBinding(ctx), `/api/aitestwork/dsh/user/projects/${projectId}/preference`, 'PUT', { styleText: matched[2] || '' })
        return { kind: 'success' as const, text: matched[2] ? '个人用例写作偏好已保存。' : '个人用例写作偏好已清除。' }
      } catch (error) {
        return { kind: 'error' as const, text: error instanceof Error ? error.message : '保存失败' }
      }
    },
  })

  ctx.commands.register({
    name: 'aitestwork-style-candidates',
    description: '本人查看 DSH 提出的个人写作习惯候选及依据',
    input: { hint: '<项目 ID>' },
    async handler({ rawInput }) {
      try {
        const projectId = Number(rawInput.trim())
        if (!Number.isSafeInteger(projectId) || projectId < 1) throw new Error('用法：/aitestwork-style-candidates <项目 ID>')
        const candidates = await callUserApi<Array<{ id: number; contentHash: string; status: string }>>(
          await currentBinding(ctx), `/api/aitestwork/dsh/user/projects/${projectId}/preference-candidates`)
        for (const item of candidates) {
          if (item.status === 'PENDING') reviewedStyleCandidates.set(`${projectId}:${item.id}`, item.contentHash)
        }
        return { kind: 'success' as const, text: JSON.stringify(candidates, null, 2) }
      } catch (error) {
        return { kind: 'error' as const, text: error instanceof Error ? error.message : '读取偏好候选失败' }
      }
    },
  })

  for (const decision of ['accept', 'reject'] as const) {
    ctx.commands.register({
      name: `aitest-style-${decision}`,
      description: decision === 'accept' ? '本人确认已查看的偏好候选并使其生效' : '本人拒绝已查看的偏好候选',
      input: { hint: '<项目 ID> <候选 ID>' },
      async handler({ rawInput }) {
        try {
          const parts = rawInput.trim().split(/\s+/)
          const projectId = Number(parts[0])
          const candidateId = Number(parts[1])
          if (parts.length !== 2 || !Number.isSafeInteger(projectId) || projectId < 1
              || !Number.isSafeInteger(candidateId) || candidateId < 1) {
            throw new Error(`用法：/aitestwork-style-${decision} <项目 ID> <候选 ID>`)
          }
          const key = `${projectId}:${candidateId}`
          const reviewedHash = reviewedStyleCandidates.get(key)
          if (!reviewedHash) throw new Error('请先运行 /aitestwork-style-candidates 查看候选全文和依据')
          await callUserApi(await currentBinding(ctx),
            `/api/aitestwork/dsh/user/projects/${projectId}/preference-candidates/${candidateId}/${decision}`,
            'POST', { reviewedHash })
          reviewedStyleCandidates.delete(key)
          return { kind: 'success' as const, text: decision === 'accept' ? '个人写作偏好已由本人确认并生效。' : '偏好候选已拒绝。' }
        } catch (error) {
          return { kind: 'error' as const, text: error instanceof Error ? error.message : '处理偏好候选失败' }
        }
      },
    })
  }

  ctx.commands.register({
    name: 'aitestwork-capture-start',
    description: '本人打开带被动轨迹采集的浏览器窗口',
    input: { hint: '<项目 ID> <HTTP/HTTPS 地址> [任务 ID] [名称]' },
    async handler({ rawInput }) {
      try {
        const match = rawInput.trim().match(/^(\d+)\s+(https?:\/\/\S+)(?:\s+(\d+))?(?:\s+(.+))?$/)
        if (!match) throw new Error('用法：/aitestwork-capture-start <项目 ID> <URL> [名称]')
        const start = await capture.start(await currentBinding(ctx), Number(match[1]), match[2], match[4],
          match[3] ? Number(match[3]) : undefined)
        return { kind: 'success' as const, text: `采集 #${start.sessionId} 已开始。请在打开的浏览器中操作；结束后运行 /aitestwork-capture-stop ${match[1]} ${start.sessionId}` }
      } catch (error) {
        return { kind: 'error' as const, text: error instanceof Error ? error.message : '启动采集失败' }
      }
    },
  })

  ctx.commands.register({
    name: 'aitestwork-capture-stop',
    description: '停止本机浏览器采集并将原始证据上传到 AITestWork',
    input: { hint: '<项目 ID> <采集会话 ID>' },
    async handler({ rawInput }) {
      try {
        const [projectId, sessionId] = rawInput.trim().split(/\s+/).map(Number)
        if (!Number.isSafeInteger(projectId) || !Number.isSafeInteger(sessionId)) throw new Error('用法：/aitestwork-capture-stop <项目 ID> <会话 ID>')
        const result = await capture.stop(await currentBinding(ctx), projectId, sessionId)
        return { kind: 'success' as const, text: `采集 #${sessionId} 已回写：${result.eventCount} 条事件、${result.networkCount} 条网络记录。` }
      } catch (error) {
        return { kind: 'error' as const, text: error instanceof Error ? error.message : '证据回写失败；可重试停止命令' }
      }
    },
  })

  ctx.commands.register({
    name: 'aitestwork-capture-evidence',
    description: '查看已回写采集会话的证据文件和校验摘要',
    input: { hint: '<项目 ID> <采集会话 ID>' },
    async handler({ rawInput }) {
      try {
        const [projectId, sessionId] = rawInput.trim().split(/\s+/).map(Number)
        if (!Number.isSafeInteger(projectId) || !Number.isSafeInteger(sessionId)) throw new Error('用法：/aitestwork-capture-evidence <项目 ID> <会话 ID>')
        const data = await callUserApi(await currentBinding(ctx), `/api/aitestwork/dsh/user/projects/${projectId}/captures/${sessionId}/artifacts`)
        return { kind: 'success' as const, text: JSON.stringify(data, null, 2) }
      } catch (error) {
        return { kind: 'error' as const, text: error instanceof Error ? error.message : '证据读取失败' }
      }
    },
  })

  ctx.commands.register({
    name: 'aitestwork-capture-download',
    description: '本人下载并校验原始证据，生成本地录屏帧查看页',
    input: { hint: '<项目 ID> <采集会话 ID>' },
    async handler({ rawInput }) {
      try {
        const [projectId, sessionId, ...extra] = rawInput.trim().split(/\s+/).map(Number)
        if (extra.length || !Number.isSafeInteger(projectId) || !Number.isSafeInteger(sessionId)) {
          throw new Error('用法：/aitestwork-capture-download <项目 ID> <会话 ID>')
        }
        const result = await downloadCaptureEvidence(await currentBinding(ctx), projectId, sessionId)
        return { kind: 'success' as const, text: `已下载 ${result.count} 份原始证据（${result.frames} 帧），校验通过。查看页：${result.viewer}` }
      } catch (error) {
        return { kind: 'error' as const, text: error instanceof Error ? error.message : '证据下载失败' }
      }
    },
  })

  ctx.tools.register(defineTool({
    name: 'aitestwork_capture_start',
    description: '在本机启动带 DOM、网络、截图和录屏证据的 Chromium 采集会话；模型后续可在同一窗口执行 UI 操作。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      targetUrl: { type: 'string', required: true, description: '被测系统 URL' },
      name: { type: 'string', description: '采集名称' },
      workSessionId: { type: 'number', description: '本轮测试任务 ID；关联用例和证据时填写' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, targetUrl, name, workSessionId }) {
      return asText(await capture.start(await currentBinding(ctx), projectId, targetUrl, name, workSessionId))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_browser_action',
    description: '在同一被动采集窗口中执行 UI 动作，返回当前页面文本；动作也进入原始轨迹。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      sessionId: { type: 'number', required: true, description: '采集会话 ID' },
      action: { type: 'string', required: true, description: 'navigate、click、fill、press、select、check、uncheck、assertText、assertVisible 或 inspect' },
      value: { type: 'string', description: 'URL 或 Playwright 定位器' },
      text: { type: 'string', description: 'fill 输入值、press 按键名、select 选项值或 assertText 预期文本' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, sessionId, action, value, text }) {
      return asText(await capture.action(await currentBinding(ctx), projectId, sessionId, action, value, text))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_capture_stop',
    description: '停止 UI 采集并上传原始事件、网络、截图、Playwright trace 和录屏帧，返回远程会话 ID。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      sessionId: { type: 'number', required: true, description: '采集会话 ID' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, sessionId }) {
      return asText(await capture.stop(await currentBinding(ctx), projectId, sessionId))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_prepare_case_design',
    description: '开始 AITestWork 需求分析或用例设计前必须调用：验证后端可用，并一次读取本项目事实、原有设计规则、所选 TOM、历史正式用例和本人写作偏好。失败时不得离线编写 AITestWork 用例。',
    parameters: {
      projectId: { type: 'number', required: true, description: '已确认的项目 ID' },
      requirementText: { type: 'string', required: true, description: '用户给出的本轮原始需求全文，不要改写' },
      tomMode: { type: 'string', required: true, description: '用户明确选择：DIRECT、PROJECT_TOM 或 PROJECT_AND_SYSTEM_TOM' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, requirementText, tomMode }) {
      if (!requirementText?.trim() || !['DIRECT', 'PROJECT_TOM', 'PROJECT_AND_SYSTEM_TOM'].includes(tomMode)) {
        throw new Error('请先取得原始需求和用户明确选择的 TOM 模式')
      }
      const binding = await currentBinding(ctx)
      const path = projectPath(projectId)
      const [designContext, designRules, writingStandard, importedHistoricalExamples, personalStyle] = await Promise.all([
        callApi(binding, `${path}/design-context`, 'POST', { requirementText, tomMode }),
        callApi(binding, '/api/aitestwork/dsh/tools/design-rules'),
        callApi(binding, '/api/aitestwork/dsh/tools/case-writing-standard'),
        callApi(binding, `${path}/historical-style-examples?limit=8`),
        callApi(binding, `${path}/preference`),
      ])
      const bundle = designContext as Record<string, unknown>
      const wiki = bundle?.wikiSearchFallback as Record<string, unknown> | undefined
      if (!bundle?.project || !Array.isArray(designRules) || designRules.length === 0 || !writingStandard
          || !Array.isArray(bundle?.tomNodes) || !Array.isArray(bundle?.tomRelations)
          || !Array.isArray(bundle?.wikiLinked) || !Array.isArray(bundle?.formalCases)
          || !wiki || !('availableByScope' in wiki)) {
        throw new Error('AITestWork 未返回 TOM 关系、Wiki 正文/概况和设计规则，请停止本轮用例编写')
      }
      if (preparedDesigns.size >= 20) preparedDesigns.clear()
      preparedDesigns.set(preparationKey(projectId, tomMode, requirementText), Date.now())
      const formalCases = Array.isArray(bundle.formalCases) ? bundle.formalCases : []
      const contextSummary = {
        tomAvailableCount: bundle.tomAvailableCount, tomSelectedCount: bundle.tomSelectedCount,
        tomTypeCoverage: bundle.tomTypeCoverage,
        tomConfirmedRelationAvailableCount: bundle.tomConfirmedRelationAvailableCount,
        wikiFallbackQuery: bundle.wikiFallbackQuery, wikiFallbackQueries: bundle.wikiFallbackQueries,
        formalCaseMatchTerm: bundle.formalCaseMatchTerm, selectionNotice: bundle.selectionNotice,
      }
      return asText({ ready: true, projectId, tomMode, preparedAt: new Date().toISOString(),
        context: { project: bundle.project }, designRules, writingStandard, tomCount: bundle.tomAvailableCount, tom: bundle.tomNodes,
        tomRelations: bundle.tomRelations, linkedWiki: bundle.wikiLinked, wiki,
        wikiFallbackQuery: bundle.wikiFallbackQuery, designContext: contextSummary, formalCases,
        importedHistoricalExamples, personalStyle,
        styleExampleNotice: '预取样例按导入时间排列，未按当前模块过滤。写每个业务模块前，用 aitestwork_search_historical_style_examples 按模块词补查完整步骤；只模仿表达方式，不复制事实。' })
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_search_historical_style_examples',
    description: '按当前业务模块词检索基础项目或本项目已导入且有完整步骤/预期的历史用例。用于学习人类写法，不能复制样例的业务事实；空结果时如实说明。',
    parameters: { projectId: { type: 'number', required: true },
      query: { type: 'string', required: true, description: '当前项目已确认的业务模块或页面名称' },
      limit: { type: 'number', description: '最多 12 条，建议 5 条' } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, query, limit }) {
      if (typeof query !== 'string' || query.trim().length < 2 || query.trim().length > 80)
        throw new Error('请用已确认的业务模块词检索历史用例，长度为 2 至 80 字')
      const count = typeof limit === 'number' && Number.isSafeInteger(limit) ? Math.max(1, Math.min(12, limit)) : 5
      return asText(await callApi(await currentBinding(ctx),
        `${projectPath(projectId)}/historical-style-examples?query=${encodeURIComponent(query.trim())}&limit=${count}`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_start_work',
    description: 'aitestwork_prepare_case_design 成功后，创建持久测试任务并保存同一原始需求与 TOM 选择；不能跳过准备或自行默认 DIRECT。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      requirementText: { type: 'string', required: true, description: '本轮原始需求全文' },
      tomMode: { type: 'string', required: true, description: '用户选择：DIRECT、PROJECT_TOM 或 PROJECT_AND_SYSTEM_TOM' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, requirementText, tomMode }) {
      const preparedAt = preparedDesigns.get(preparationKey(projectId, tomMode, requirementText))
      if (!preparedAt || Date.now() - preparedAt > 30 * 60_000) {
        throw new Error('请先调用 aitestwork_prepare_case_design 成功读取本项目最新规则与资产，再开始编写')
      }
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/work-sessions`, 'POST', { requirementText, tomMode }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_tom_context',
    description: '按本轮测试任务最初保存的 TOM 模式读取已启用 TOM；DIRECT 返回空列表。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      workSessionId: { type: 'number', required: true, description: '已创建的本轮测试任务 ID' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, workSessionId }) {
      if (!Number.isSafeInteger(workSessionId) || workSessionId < 1) throw new Error('请先创建本轮测试任务')
      const binding = await currentBinding(ctx)
      const work = await callApi<{ tomMode: string }>(binding,
        `${projectPath(projectId)}/work-sessions/${workSessionId}`)
      const tomMode = work.tomMode
      if (!['DIRECT', 'PROJECT_TOM', 'PROJECT_AND_SYSTEM_TOM'].includes(tomMode))
        throw new Error('本轮任务未保存有效的 TOM 模式')
      return asText(await callApi(binding, `${projectPath(projectId)}/tom?mode=${encodeURIComponent(tomMode)}`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_design_rules',
    description: '读取 AITestWork 原有功能测试设计政策中的测试点类型、设计方法及覆盖义务；用例设计前必须调用。',
    parameters: {},
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute() {
      return asText(await callApi(await currentBinding(ctx), '/api/aitestwork/dsh/tools/design-rules'))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_validate_case_batch',
    description: '按 AITestWork 原有设计规则提前校验 1 至 5 个测试点的一组候选用例，不写入草稿。生成大量用例时必须先分组校验，再汇总保存 CASE_DESIGN。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      workSessionId: { type: 'number', required: true, description: '本轮任务 ID' },
      pointIdsJson: { type: 'string', required: true, description: '本批覆盖的测试点编号 JSON 数组，最多 5 个，例如 ["TP1","TP2"]' },
      payloadJson: { type: 'string', required: true, description: '本批候选用例 JSON 对象，包含 cases 数组，字段与 CASE_DESIGN 一致' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, workSessionId, pointIdsJson, payloadJson }) {
      const pointIds: unknown = JSON.parse(pointIdsJson)
      if (!Array.isArray(pointIds) || pointIds.length < 1 || pointIds.length > 5
          || !pointIds.every(id => typeof id === 'string' && /^TP[1-9][0-9]*$/.test(id))) {
        throw new Error('每批请选择 1 至 5 个有效测试点编号')
      }
      const candidate = JSON.parse(payloadJson) as { cases?: unknown[] }
      if (!Array.isArray(candidate.cases) || candidate.cases.length === 0) {
        throw new Error('本批候选用例必须包含非空 cases 数组')
      }
      const result = await callApi(await currentBinding(ctx),
        `${projectPath(projectId)}/work-sessions/${workSessionId}/case-design/validate`,
        'POST', { pointIds, payloadJson })
      const key = sessionKey(projectId, workSessionId)
      const saved = validatedCases.get(key) ?? new Set<string>()
      candidate.cases.forEach(item => saved.add(stableJson(item)))
      validatedCases.set(key, saved)
      return asText(result)
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_save_stage',
    description: '保存 DSH 推演的结构化阶段快照。阶段名可为 REQUIREMENT_ANALYSIS、CLARIFICATION、TEST_POINTS、COVERAGE_PLAN、CASE_DESIGN、QUALITY_REVIEW；保存不等于本人确认。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      workSessionId: { type: 'number', required: true, description: '本轮任务 ID' },
      stageKey: { type: 'string', required: true, description: '阶段名' },
      payloadJson: { type: 'string', required: true, description: '结构化 JSON 对象字符串' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, workSessionId, stageKey, payloadJson }) {
      const key = sessionKey(projectId, workSessionId)
      if (stageKey === 'CASE_DESIGN') {
        const design = JSON.parse(payloadJson) as { cases?: unknown[] }
        const checked = validatedCases.get(key)
        if (!Array.isArray(design.cases) || design.cases.length === 0
            || !checked || !design.cases.every(item => checked.has(stableJson(item)))) {
          throw new Error('请先用 aitestwork_validate_case_batch 校验每组候选用例，再保存完整用例设计')
        }
      }
      const result = await callApi(await currentBinding(ctx),
        `${projectPath(projectId)}/work-sessions/${workSessionId}/stages`, 'POST', { stageKey, payloadJson })
      if (stageKey === 'TEST_POINTS' || stageKey === 'COVERAGE_PLAN') validatedCases.delete(key)
      return asText(result)
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_work_stages',
    description: '读取本轮测试任务各阶段的最新快照和确认状态，继续任务时先调用。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      workSessionId: { type: 'number', required: true, description: '本轮任务 ID' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, workSessionId }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/work-sessions/${workSessionId}/stages`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_list_projects',
    description: '列出当前用户有权访问的 AITestWork 测试项目。',
    parameters: {},
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute() {
      return asText(await callApi(await currentBinding(ctx), '/api/aitestwork/dsh/tools/projects'))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_create_project',
    description: '仅在用户明确要求创建项目时，使用绑定用户身份在 AITestWork 创建项目，并返回项目 ID。',
    parameters: {
      projectName: { type: 'string', required: true, description: '用户指定的项目名称' },
      description: { type: 'string', description: '用户给出的项目说明' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectName, description }) {
      if (!projectName.trim()) throw new Error('项目名称不能为空')
      return asText(await callUserApi(await currentBinding(ctx), '/api/aitestwork/dsh/user/projects', 'POST',
        { projectName: projectName.trim(), description: description || '' }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_list_my_drafts',
    description: '列出绑定用户在指定项目中的个人用例草稿，供自然语言汇报与复核。',
    parameters: { projectId: { type: 'number', required: true, description: '项目 ID' } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId }) {
      return asText(await callUserApi(await currentBinding(ctx), `/api/aitestwork/dsh/user/projects/${projectId}/drafts`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_my_draft',
    description: '读取绑定用户的一条个人草稿全文，供本人在对话中审阅；读取不等于正式提交。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      draftId: { type: 'number', required: true, description: '草稿 ID' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, draftId }) {
      return asText(await callUserApi(await currentBinding(ctx), `/api/aitestwork/dsh/user/projects/${projectId}/drafts/${draftId}`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_capture_evidence',
    description: '读取已上传的浏览器采集原始证据清单和校验摘要，供自然语言说明测试结果。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      sessionId: { type: 'number', required: true, description: '采集会话 ID' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, sessionId }) {
      return asText(await callUserApi(await currentBinding(ctx), `/api/aitestwork/dsh/user/projects/${projectId}/captures/${sessionId}/artifacts`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_project_context',
    description: '读取项目可信语义上下文。需求理解和用例设计前调用，明确事实来源与待确认项。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      focus: { type: 'string', description: '本轮需求或模块焦点' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, focus }) {
      const path = `${projectPath(projectId)}/context?focus=${encodeURIComponent(focus || '')}`
      return asText(await callApi(await currentBinding(ctx), path))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_search_wiki',
    description: '检索已审核且生效的项目、共享及系统 Wiki 知识包，返回可见条目总数、命中数和带来源 ID 的少量结果；无命中不等于知识包为空。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      query: { type: 'string', description: '具体业务词；留空时只看可用知识概况和样例' },
      limit: { type: 'number', description: '最多 20 条' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, query, limit }) {
      const params = new URLSearchParams({ query: query || '', limit: String(limit || 8) })
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/wiki/context?${params}`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_design_context',
    description: '按本轮任务最初保存的 TOM 模式补查逻辑骨架与 Wiki 正文。语义不足时可用更具体的业务描述再次检索；模式由任务决定，不能自行切换。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      workSessionId: { type: 'number', required: true, description: '已创建的本轮测试任务 ID' },
      requirementText: { type: 'string', required: true, description: '需求原文或本次补充的具体业务描述' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, workSessionId, requirementText }) {
      if (!Number.isSafeInteger(workSessionId) || workSessionId < 1) throw new Error('请先创建本轮测试任务')
      const binding = await currentBinding(ctx)
      const work = await callApi<{ tomMode: string }>(binding,
        `${projectPath(projectId)}/work-sessions/${workSessionId}`)
      const tomMode = work.tomMode
      if (!['DIRECT', 'PROJECT_TOM', 'PROJECT_AND_SYSTEM_TOM'].includes(tomMode))
        throw new Error('本轮任务未保存有效的 TOM 模式')
      return asText(await callApi(binding, `${projectPath(projectId)}/design-context`, 'POST', { requirementText, tomMode }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_wiki_entry',
    description: '按 aitestwork_search_wiki 返回的 entryId 读取已审核且生效的 Wiki 完整正文和来源。引用 Wiki 规则前必须核对正文；不可见条目由后端拒绝。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      entryId: { type: 'number', required: true, description: 'Wiki 检索结果中的 entryId' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, entryId }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/wiki/entries/${entryId}`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_propose_wiki',
    description: '把已核实的实现事实提交为项目级 Wiki 待审核知识包。不会发布或升级为共享/系统知识；entries 每项含 type(RULE/FLOW/PAGE/DIALOG/STATE/IMPLEMENTATION/DECISION)、title、content、source。',
    parameters: {
      projectId: { type: 'number', required: true },
      workSessionId: { type: 'number', required: true },
      packName: { type: 'string', required: true },
      entries: { type: 'array', required: true, items: { type: 'object', additionalProperties: true } },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, workSessionId, packName, entries }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/asset-proposals/wiki`, 'POST',
        { workSessionId, packName, entries }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_propose_tom',
    description: '提交项目级 TOM 待审核节点，不自动激活。nodes 每项含 type(MODULE/PAGE/FIELD/ROLE/ACTION/FLOW/STATE/ASSERTION)、name、description、evidence。',
    parameters: {
      projectId: { type: 'number', required: true },
      workSessionId: { type: 'number', required: true },
      nodes: { type: 'array', required: true, items: { type: 'object', additionalProperties: true } },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, workSessionId, nodes }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/asset-proposals/tom`, 'POST',
        { workSessionId, nodes }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_propose_tom_relations',
    description: '提交同项目 TOM 关系候选。先调用 aitestwork_get_tom_relation_types 读取合法 type 与 fromType→toType；不能使用通用 CONTAINS/DEPENDS_ON。两端可为待审节点，但须先审核激活节点才能审核关系。每项含 fromModelId、type、toModelId、evidence。',
    parameters: {
      projectId: { type: 'number', required: true },
      workSessionId: { type: 'number', required: true },
      relations: { type: 'array', required: true, items: { type: 'object', additionalProperties: true } },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, workSessionId, relations }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/asset-proposals/tom-relations`, 'POST',
        { workSessionId, relations }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_tom_relation_types',
    description: '读取 AITestWork 当前允许的 TOM 关系枚举及节点方向；创建关系前先核对两端类型。',
    parameters: { projectId: { type: 'number', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/asset-proposals/tom-relation-types`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_propose_locator_map',
    description: '将实测控件定位映射提交为待审核项目资产；不是可执行脚本。locators 每项含 page、control、strategy(TEST_ID/ROLE/LABEL/PLACEHOLDER/TEXT/CSS)、selector、evidence。',
    parameters: {
      projectId: { type: 'number', required: true },
      workSessionId: { type: 'number', required: true },
      name: { type: 'string', required: true },
      locators: { type: 'array', required: true, items: { type: 'object', additionalProperties: true } },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, workSessionId, name, locators }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/asset-proposals/locators`, 'POST',
        { workSessionId, name, locators }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_locator_map',
    description: '读取本项目已审核生效的控件定位映射；执行前仍要验证定位是否适用于当前页面版本。',
    parameters: {
      projectId: { type: 'number', required: true },
      query: { type: 'string', description: '定位表名称关键词' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, query }) {
      const params = new URLSearchParams({ query: query || '' })
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/asset-proposals/locators?${params}`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_create_auto_asset',
    description: '为用例创建执行映射 v1。先检索目标项目及已发布的复用/系统级候选；有候选须先检查适配。UI 支持 click/fill/press/select/check/uncheck/waitFor/wait/scroll/drag；优先用 waitFor 或 waitForResponse 等可观察条件，只有无可观察状态的异步重置才用 {type:"wait",timeoutMs:500}（100–30000 毫秒、累计最多 60000 毫秒，无 locator）；滚轮用 scroll + locator + deltaY；拖拽用 drag + locator + 非零 deltaX/deltaY（可选 dragSteps=2–50）；等待后仍须断言核对结果。同名按钮用 locator.within 限定抽屉或弹窗。assetType=API 时使用 steps:[{method:"GET|POST|PUT|PATCH|DELETE|HEAD",path:"/相对路径",headers?:{},body?:{},extract?:{变量:"$.data.id"}}] 和 assertions:[{stepIndex:0,target:"STATUS|HEADER|JSON",path?:"$.data.id",operator:"EQ|CONTAINS|EXISTS",expected?:200}]。API 密钥只能用 {secretRef:"NAME"} 或 ${secret:NAME} 引用，不能写明文。caseNo 须等于归属用例编号。',
    parameters: {
      projectId: { type: 'number', required: true },
      workSessionId: { type: 'number', required: true },
      requestKey: { type: 'string', required: true, description: '重试时保持不变的幂等键' },
      caseKind: { type: 'string', required: true, description: 'DRAFT 或 FORMAL' },
      caseId: { type: 'number', required: true },
      assetType: { type: 'string', description: 'UI 或 API；省略时为 UI' },
      mapping: { type: 'object', required: true, additionalProperties: true },
      freshReason: { type: 'string', description: '有候选但全部不适用时说明原因' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, workSessionId, requestKey, caseKind, caseId, assetType, mapping, freshReason }) {
      const binding = await currentBinding(ctx)
      const detail = caseKind === 'DRAFT'
        ? await callUserApi<{ draft?: { caseTitle?: string; moduleName?: string }; caseTitle?: string; moduleName?: string }>(
          binding, `/api/aitestwork/dsh/user/projects/${projectId}/drafts/${caseId}`)
        : await callApi<{ caseTitle?: string; moduleName?: string }>(binding, `${projectPath(projectId)}/cases/${caseId}`)
      const item = ('draft' in detail && detail.draft ? detail.draft : detail) as { caseTitle?: string; moduleName?: string }
      const scenario = [item.caseTitle, item.moduleName].filter(Boolean).join(' · ')
      if (!scenario) throw new Error('归属用例缺少标题，无法先查复用资产')
      const params = new URLSearchParams({ projectId: String(projectId), query: scenario,
        assetType: assetType || 'UI', offset: '0', limit: '50' })
      const candidates = await callApi<Array<{ scope: string; assetId: number; publicationId?: number }>>(
        binding, `/api/aitestwork/dsh/tools/automation-publications/candidates?${params}`)
      if (candidates.length && (!freshReason || freshReason.trim().length < 20))
        throw new Error(`发现 ${candidates.length} 个可复用候选，请先读取并验证。确实不适用时说明候选编号和原因再重新生成：${JSON.stringify(candidates.slice(0, 5))}`)
      return asText(await callApi(binding, `${projectPath(projectId)}/automation-assets`, 'POST',
        { workSessionId, requestKey, caseKind, caseId, assetType, mapping }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_find_auto_asset_candidates',
    description: '按本次需求变化查找可复用的项目 UI 自动化映射，合并本人草稿、正式用例标题与可访问资产，返回候选资产 ID/版本；新会话修改已有 UI 流程时应先调用，再读取完整映射。结果受资产列表与权限范围限制，空结果不代表全库没有。',
    parameters: {
      projectId: { type: 'number', required: true, description: '当前 AITestWork 项目 ID' },
      change: { type: 'string', required: true, description: '本次业务/页面变化的自然语言描述，最多 500 字' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, change }) {
      return asText(await findAutoAssetCandidates(await currentBinding(ctx), projectId, change))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_copy_ui_asset_version',
    description: '从已验证的 UI 映射复制原页面、步骤、定位和断言，仅应用明确的差异，保存为同一用例的待验证新版本或新用例的 v1 资产。业务条件变化用此工具，不能伪装成只改 locator 的自愈；保存后仍须真实运行并按规则审核。',
    parameters: {
      projectId: { type: 'number', required: true },
      sourceAssetId: { type: 'number', required: true },
      sourceVersionNo: { type: 'number', required: true },
      mode: { type: 'string', required: true, description: 'REVISE（同一用例）或 NEW_CASE（新用例）' },
      requestKey: { type: 'string', required: true, description: '重试保持不变的幂等键' },
      changeReason: { type: 'string', required: true, description: '业务修订依据，至少 10 字' },
      edits: { type: 'array', required: true, items: { type: 'object', additionalProperties: true },
        description: '1–20 项差异，每项 {operation:replace|insert|remove,section:page|steps|assertions|logAssertions,index?:从0开始,value?:完整步骤/断言对象或页面路径}；未列出的旧步骤及UI/API/日志断言自动保留，改业务预期须重新验证审核' },
      expectedVersion: { type: 'number', description: 'REVISE 必填：来源资产 currentVersion' },
      targetCaseKind: { type: 'string', description: 'NEW_CASE 必填：DRAFT 或 FORMAL' },
      targetCaseId: { type: 'number', description: 'NEW_CASE 必填：新用例 ID' },
      targetCaseNo: { type: 'string', description: 'NEW_CASE 必填：新用例编号' },
      workSessionId: { type: 'number', description: 'NEW_CASE 必填：当前 AITestWork 测试任务 ID' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute(input) {
      return asText(await copyUiAssetVersion(await currentBinding(ctx), {
        ...input, mode: input.mode as 'REVISE' | 'NEW_CASE',
        targetCaseKind: input.targetCaseKind as 'DRAFT' | 'FORMAL' | undefined,
        edits: input.edits as MappingEdit[],
      }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_list_auto_assets',
    description: '列出项目中可访问的 UI/API 自动化资产、当前版本、审核状态和自愈次数；只有已验证且已审核的映射可日常复跑。',
    parameters: { projectId: { type: 'number', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/automation-assets`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_auto_asset_version',
    description: '读取指定 UI/API 自动化资产版本的完整步骤、参数和断言。',
    parameters: {
      projectId: { type: 'number', required: true },
      assetId: { type: 'number', required: true },
      versionNo: { type: 'number', required: true },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, assetId, versionNo }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/automation-assets/${assetId}/versions/${versionNo}`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_revise_auto_asset',
    description: '按唯一资产 ID 修订执行映射，生成待验证的新版本，不覆盖旧版本。先读取当前版本和完整映射；个人草稿的创建人或项目管理者可修订。修改业务步骤或断言时必须说明依据；不得把断言失败伪装成定位自愈。',
    parameters: {
      projectId: { type: 'number', required: true },
      assetId: { type: 'number', required: true },
      requestKey: { type: 'string', required: true, description: '重试保持相同值的幂等键' },
      expectedVersion: { type: 'number', required: true, description: '读取到的 currentVersion' },
      changeReason: { type: 'string', required: true, description: '至少 10 字的修订依据' },
      mapping: { type: 'object', required: true, additionalProperties: true },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, assetId, requestKey, expectedVersion, changeReason, mapping }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/automation-assets/${assetId}/versions`,
        'POST', { requestKey, expectedVersion, changeReason, mapping }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_delete_auto_asset',
    description: '按唯一资产 ID 舍弃自动化资产。仅可舍弃本人草稿资产或有管理权限的项目资产；保留历史版本、运行与证据。用户明确要求删除或舍弃此资产时才调用，必须说明原因。已发布复用版不能直接舍弃。',
    parameters: {
      projectId: { type: 'number', required: true },
      assetId: { type: 'number', required: true },
      reason: { type: 'string', required: true, description: '2-512 字舍弃原因' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, assetId, reason }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/automation-assets/${assetId}/archive`,
        'POST', { reason }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_discard_auto_asset_candidate',
    description: '按唯一资产 ID 和版本号舍弃尚未验证的修订候选版，保留当前有效版本。仅在用户明确要求撤销该修订时调用；不能舍弃当前生效版。',
    parameters: {
      projectId: { type: 'number', required: true },
      assetId: { type: 'number', required: true },
      versionNo: { type: 'number', required: true },
      reason: { type: 'string', required: true },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, assetId, versionNo, reason }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/automation-assets/${assetId}/versions/${versionNo}/discard`,
        'POST', { reason }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_auto_runs',
    description: '读取自动化资产最近 100 次运行结果、耗时、失败原因及原始证据会话 ID。',
    parameters: {
      projectId: { type: 'number', required: true },
      assetId: { type: 'number', required: true },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, assetId }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/automation-assets/${assetId}/runs`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_search_reusable_automation',
    description: '检索经 Web 发布的跨项目复用执行映射；结果引用冻结版本，不会跟随源项目后续自愈变化。',
    parameters: { query: { type: 'string', description: '用例编号关键词' } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ query }) {
      const params = new URLSearchParams({ query: query || '' })
      return asText(await callApi(await currentBinding(ctx), `/api/aitestwork/dsh/tools/automation-publications?${params}`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_prepare_auto_execution',
    description: '自动化任务的第一步：按自然语言场景查目标项目和所有已发布复用版，项目有效资产优先，支持 UI/API 分页。返回来源与冻结版本；有候选时先验证适配，不能直接从头生成。',
    parameters: {
      projectId: { type: 'number', required: true },
      scenario: { type: 'string', required: true, description: '用户的测试场景原话，不需要用户提供关键词' },
      assetType: { type: 'string', required: true, description: 'UI 或 API' },
      offset: { type: 'number', description: '从 0 开始的分页偏移' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, scenario, assetType, offset }) {
      const params = new URLSearchParams({ projectId: String(projectId), query: scenario,
        assetType, offset: String(offset || 0), limit: '50' })
      return asText(await callApi(await currentBinding(ctx), `/api/aitestwork/dsh/tools/automation-publications/candidates?${params}`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_adopt_reusable_automation',
    description: '把已发布的冻结 UI/API 映射复制为目标项目用例的待验证资产。来源不变，目标必须真实执行、保存证据并经 Web 审核，不能复制后直接标记通过。',
    parameters: {
      projectId: { type: 'number', required: true }, publicationId: { type: 'number', required: true },
      workSessionId: { type: 'number', required: true }, requestKey: { type: 'string', required: true },
      caseKind: { type: 'string', required: true, description: 'DRAFT 或 FORMAL' },
      caseId: { type: 'number', required: true, description: '目标项目中的用例 ID' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, publicationId, workSessionId, requestKey, caseKind, caseId }) {
      return asText(await callApi(await currentBinding(ctx),
        `/api/aitestwork/dsh/tools/automation-publications/${publicationId}/adopt?projectId=${projectId}`, 'POST',
        { workSessionId, requestKey, caseKind, caseId }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_reusable_automation',
    description: '按发布编号读取跨项目复用映射的完整冻结版本；复制到目标项目后须重新验证环境、页面和数据。',
    parameters: { publicationId: { type: 'number', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ publicationId }) {
      return asText(await callApi(await currentBinding(ctx), `/api/aitestwork/dsh/tools/automation-publications/${publicationId}`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_run_auto_asset',
    description: '在本机执行 UI 浏览器映射并回写 trace/截图/录屏和结果。结束后必须把返回的 reportPath 本地测试报告路径告知用户。headless 可选，默认有界面；首次或候选验证须显式传 versionNo。环境故障候选可重试；仅定位失败可自愈。',
    parameters: {
      projectId: { type: 'number', required: true },
      assetId: { type: 'number', required: true },
      targetUrl: { type: 'string', required: true, description: '被测系统根地址' },
      params: { type: 'object', additionalProperties: true, description: '运行时受控测试数据；避免在映射中写真实个人信息' },
      versionNo: { type: 'number', description: '首次验证或自愈候选版本号；日常省略以使用当前已验证版' },
      headless: { type: 'boolean', description: '是否无界面执行；默认 false。切换模式会关闭空闲的旧浏览器窗口，运行中的采集会话不会被切换' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => renderAutomationResult(value) },
    async execute({ projectId, assetId, targetUrl, params, versionNo, headless }) {
      const values = (params || {}) as Record<string, unknown>
      if (Object.values(values).some(value => typeof value !== 'string')) throw new Error('运行时测试数据必须是字符串')
      return asVisibleReport(await executeAutomationAsset(await currentBinding(ctx), capture, projectId, assetId,
        targetUrl, values as Record<string, string>, versionNo, headless || false, adminLogs), 'AITestWork UI')
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_start_ui_coverage',
    description: '为一轮 UI 测试建立服务端规则覆盖任务，快照当前系统/项目有效规则。单条用例 PASS 不代表任务完成；每条规则须判定适用性并关联证据。相同 taskKey 重试复用任务。',
    parameters: { projectId: { type: 'number', required: true }, taskKey: { type: 'string', required: true },
      targetOrigin: { type: 'string', required: true, description: '被测站点的 HTTP/HTTPS 根地址' } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, taskKey, targetOrigin }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/ui-coverage`, 'POST',
        { taskKey, targetOrigin }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_ui_execution_rules',
    description: '读取当前系统与项目有效的通用 UI 执行规则及检查方式。任务须逐条判定适用性；未知或缺证据时不能称全量完成。',
    parameters: { projectId: { type: 'number', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/ui-coverage/rules`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_propose_ui_execution_rule',
    description: '提出可跨业务复用的 UI 执行规则候选，项目创建者/管理员在 Web 审核后才进入新任务。支持 FIELD_PAIR（正反运行）或 RUN_ASSERTION（有断言的真实运行）；规则不含业务选择器。',
    parameters: { projectId: { type: 'number', required: true }, ruleCode: { type: 'string', required: true },
      title: { type: 'string', required: true }, checkKind: { type: 'string', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, ruleCode, title, checkKind }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/ui-coverage/rules`, 'POST',
        { ruleCode, title, checkKind }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_decide_ui_rule',
    description: '对本轮有效 UI 规则逐条登记 APPLICABLE、NOT_APPLICABLE 或 UNKNOWN，并给出处置依据。模型的选择只供项目创建者/管理员在 Web 确认，不能自行确认任务范围。',
    parameters: { projectId: { type: 'number', required: true }, taskId: { type: 'number', required: true },
      ruleCode: { type: 'string', required: true }, applicability: { type: 'string', required: true },
      reason: { type: 'string', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, taskId, ruleCode, applicability, reason }) {
      return asText(await callApi(await currentBinding(ctx),
        `${projectPath(projectId)}/ui-coverage/${taskId}/rules/${ruleCode}`, 'POST', { applicability, reason }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_add_ui_rule_check',
    description: '为本轮适用规则新增可核对的覆盖项，填写规则编号、场景标识、页面、名称与来源。无法确认适用性时填 UNKNOWN。清单需在 AITestWork Web 确认后才允许关联证据。',
    parameters: { projectId: { type: 'number', required: true }, taskId: { type: 'number', required: true },
      ruleCode: { type: 'string', required: true },
      fieldKey: { type: 'string', required: true }, pagePath: { type: 'string', required: true },
      fieldLabel: { type: 'string', required: true }, sourceBasis: { type: 'string', required: true },
      applicability: { type: 'string', required: true, description: 'REQUIRED 或 UNKNOWN' } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, taskId, ruleCode, fieldKey, pagePath, fieldLabel, sourceBasis, applicability }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/ui-coverage/${taskId}/obligations`,
        'POST', { ruleCode, fieldKey, pagePath, fieldLabel, sourceBasis, applicability }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_attach_ui_rule_runs',
    description: '把覆盖项的真实 UI 运行 ID 关联到任务。FIELD_PAIR 需正向与单项留空两条；RUN_ASSERTION 只传 positiveRunId。后端核对归属、站点、映射声明和已存证据。',
    parameters: { projectId: { type: 'number', required: true }, taskId: { type: 'number', required: true },
      obligationId: { type: 'number', required: true }, positiveRunId: { type: 'number', required: true },
      negativeRunId: { type: 'number' } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, taskId, obligationId, positiveRunId, negativeRunId }) {
      return asText(await callApi(await currentBinding(ctx),
        `${projectPath(projectId)}/ui-coverage/${taskId}/obligations/${obligationId}/evidence`, 'POST',
        { positiveRunId, negativeRunId }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_confirm_ui_coverage',
    description: '由本机 DSH 宿主审批界面展示本轮完整 UI 规则清单，使用者确认后才通过本人 AITestWork 凭据锁定范围；模型不能自行确认。超过 20 项请在 AITestWork Web 完整审核。',
    parameters: { projectId: { type: 'number', required: true }, taskId: { type: 'number', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, taskId }) {
      return asText(await callUserApi(await currentBinding(ctx),
        `/api/aitestwork/dsh/user/projects/${projectId}/ui-coverage/${taskId}/confirm`, 'POST'))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_ui_coverage',
    description: '读取后端对本轮全部适用 UI 规则的最终判定。只有 result=PASS 才能报告已确认规则清单覆盖完成；INCOMPLETE 或 INVENTORY_UNCONFIRMED 均不可称全量完成。',
    parameters: { projectId: { type: 'number', required: true }, taskId: { type: 'number', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, taskId }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/ui-coverage/${taskId}`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_run_api_asset',
    description: '用本机 DSH 直接执行已保存的 API 映射并回写脱敏请求证据和结果；每次结束后把 reportPath 本地测试报告路径告知用户。不启动浏览器，不逐步调模型。首次或修订候选验证须指定 versionNo。targetUrl 是被测接口根地址，params 只放普通测试数据；密钥从本机 AITESTWORK_API_SECRET_<NAME> 读取，不要通过聊天传入。',
    parameters: {
      projectId: { type: 'number', required: true },
      assetId: { type: 'number', required: true },
      targetUrl: { type: 'string', required: true },
      params: { type: 'object', additionalProperties: true },
      versionNo: { type: 'number', description: '首次或候选验证的版本号；日常复跑省略' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => renderAutomationResult(value) },
    async execute({ projectId, assetId, targetUrl, params, versionNo }) {
      const values = (params || {}) as Record<string, unknown>
      if (Object.values(values).some(value => typeof value !== 'string')) throw new Error('接口测试参数必须是字符串')
      return asVisibleReport(await executeApiAutomationAsset(await currentBinding(ctx), projectId, assetId,
        targetUrl, values as Record<string, string>, versionNo, undefined, adminLogs), 'AITestWork API')
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_record_api_evidence',
    description: '仅用于接口证据回写中断后的恢复；使用 aitestwork_run_api_asset 返回的原始 evidencePayload 幂等重试，成功后把返回的 id 设为 runPayload.apiEvidenceId 再调用 aitestwork_record_auto_run。',
    parameters: {
      projectId: { type: 'number', required: true },
      assetId: { type: 'number', required: true },
      evidencePayload: { type: 'object', required: true, additionalProperties: true },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, assetId, evidencePayload }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/automation-assets/${assetId}/api-evidence`,
        'POST', evidencePayload))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_api_evidence',
    description: '按证据 ID 读取接口自动化运行的脱敏请求摘要、响应摘要、哈希和逐项断言结果。',
    parameters: { projectId: { type: 'number', required: true }, assetId: { type: 'number', required: true },
      evidenceId: { type: 'number', required: true } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, assetId, evidenceId }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/automation-assets/${assetId}/api-evidence/${evidenceId}`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_propose_auto_heal',
    description: '仅针对 UI 浏览器映射当前版本的定位失效提出修订；接口映射不使用定位器自愈。新映射只能改 locator，步骤动作、数据和断言预期必须不变。AITestWork 会保留最多 4 个去重的旧定位备用分支，候选版须有原始证据且跑通后才生效。',
    parameters: {
      projectId: { type: 'number', required: true },
      assetId: { type: 'number', required: true },
      requestKey: { type: 'string', required: true },
      failedRunId: { type: 'number', required: true },
      diagnosis: { type: 'string', required: true },
      mapping: { type: 'object', required: true, additionalProperties: true },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, assetId, requestKey, failedRunId, diagnosis, mapping }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/automation-assets/${assetId}/heals`,
        'POST', { requestKey, failedRunId, diagnosis, mapping }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_record_auto_run',
    description: '仅用于运行结果回写中断后的恢复：UI 先完成采集上传；API 先保存接口证据并填入 apiEvidenceId；再用原 runPayload 和原 requestKey 幂等记录运行结果。',
    parameters: {
      projectId: { type: 'number', required: true },
      assetId: { type: 'number', required: true },
      runPayload: { type: 'object', required: true, additionalProperties: true },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, assetId, runPayload }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/automation-assets/${assetId}/runs`,
        'POST', runPayload))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_search_test_assets',
    description: '按具体业务词检索 AITestWork 项目知识文档、正式用例和 SKILL 模板；空词只返回少量候选样例。Wiki 知识包请调用 aitestwork_search_wiki。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      query: { type: 'string', required: true, description: '检索词' },
      limit: { type: 'number', description: '最多 30 条' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, query, limit }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/assets/search`, 'POST', { query, limit }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_test_cases',
    description: '按关键词读取已提交的正式历史用例，结果包含完整步骤与预期；用最后一条 id 作为 beforeId 继续翻页。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      query: { type: 'string', description: '可选：用例编号、标题或模块关键词，最多 200 字' },
      beforeId: { type: 'number', description: '可选：上一页最后一条用例 ID，读取更早用例' },
      limit: { type: 'number', description: '最多 50 条' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, query, beforeId, limit }) {
      const params = new URLSearchParams({ limit: String(limit ?? 20) })
      if (query) params.set('query', query)
      if (beforeId !== undefined) params.set('beforeId', String(beforeId))
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/cases?${params}`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_test_case_detail',
    description: '按正式用例 ID 单独读取完整标题、模块、前置条件、步骤、预期及来源；只对当前项目可见的已提交用例开放。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      caseId: { type: 'number', required: true, description: '正式用例列表中的 id' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, caseId }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/cases/${caseId}`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_save_case_draft',
    description: '将 DSH 生成的用例保存到当前用户个人草稿库；不会提交正式用例。每条草稿使用稳定的幂等键。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      workSessionId: { type: 'number', required: true, description: '本轮任务 ID，关联已保存的测试点范围' },
      idempotencyKey: { type: 'string', required: true, description: '同一用例重试时保持不变的唯一键' },
      caseTitle: { type: 'string', required: true, description: '用例名称：业务对象、操作与预期结果' },
      moduleName: { type: 'string', required: true, description: '所属模块：/页面/功能/子功能' },
      precondition: { type: 'string', description: '前置条件：执行前已具备的开关、人员或数据；无则留空' },
      steps: { type: 'string', required: true, description: '步骤描述：从 1 连续编号，原子检查允许一步' },
      expectedResult: { type: 'string', required: true, description: '预期结果：逐条对应步骤，写可观察的界面、提示或状态' },
      priority: { type: 'string', description: 'P0/P1/P2/P3' },
      pointType: { type: 'string', description: '测试点类型' },
      sourceRefsJson: { type: 'string', description: '来源引用 JSON 字符串数组；只填写资产检索返回的 sourceType/sourceRefId，不要编造来源' },
      captureSessionId: { type: 'number', description: '已完成且属于本轮任务的浏览器采集会话 ID' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, workSessionId, sourceRefsJson, ...draft }) {
      const refs: unknown = sourceRefsJson ? JSON.parse(sourceRefsJson) : []
      if (!Array.isArray(refs) || !refs.every(ref => typeof ref === 'string')) throw new Error('sourceRefsJson 必须是字符串数组')
      const work = await callApi<{ requirementText: string }>(await currentBinding(ctx),
        `${projectPath(projectId)}/work-sessions/${workSessionId}`)
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/drafts`, 'POST',
        { ...draft, workSessionId, requirementText: work.requirementText, sourceRefs: refs }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_save_case_batch',
    description: '一次提交 1–100 条独立用例草稿，可同时创建各自的 UI/API 执行映射；整批原子写入，失败不留半批。只创建草稿和待验证映射，不执行浏览器，也不自动审核。',
    parameters: {
      projectId: { type: 'number', required: true },
      workSessionId: { type: 'number', required: true },
      items: { type: 'array', required: true, items: { type: 'object', additionalProperties: true },
        description: '每项含 draft:{idempotencyKey,caseTitle,moduleName,precondition?,steps,expectedResult,priority?,pointType?,sourceRefs?:字符串数组}；可选 assetRequestKey、assetType(UI/API)、mapping。批内键须唯一。' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, workSessionId, items }) {
      if (!Array.isArray(items) || items.length < 1 || items.length > 100) throw new Error('每批须有 1–100 条用例')
      const binding = await currentBinding(ctx)
      const work = await callApi<{ requirementText: string }>(binding,
        `${projectPath(projectId)}/work-sessions/${workSessionId}`)
      const prepared = items.map((raw, index) => {
        const item = raw as Record<string, unknown>
        const draft = item.draft as Record<string, unknown> | undefined
        if (!draft || typeof draft !== 'object' || (draft.sourceRefs !== undefined
            && (!Array.isArray(draft.sourceRefs) || !draft.sourceRefs.every(ref => typeof ref === 'string')))) {
          throw new Error(`第 ${index + 1} 条草稿格式或来源引用无效`)
        }
        return { draft: { ...draft, workSessionId, requirementText: work.requirementText },
          assetRequestKey: item.assetRequestKey, assetType: item.assetType, mapping: item.mapping }
      })
      return asText(await callApi(binding, `${projectPath(projectId)}/case-batch`, 'POST',
        { workSessionId, items: prepared }))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_my_style',
    description: '读取本人针对该项目的用例写作偏好。偏好仅影响表达，不可覆盖事实或强制规则。',
    parameters: { projectId: { type: 'number', required: true, description: '项目 ID' } },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/preference`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_case_draft_revision',
    description: '读取本人用例草稿当前内容和 versionNo；修改前先读，避免把不同修订写成多条草稿。',
    parameters: {
      projectId: { type: 'number', required: true },
      draftId: { type: 'number', required: true },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, draftId }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/drafts/${draftId}/revision`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_get_local_case',
    description: '按本地用例库唯一 ID 读取本人用例全文和 versionNo。普通草稿 ID 为正数，轨迹草稿 ID 为负数；不能按标题猜 ID。',
    parameters: {
      projectId: { type: 'number', required: true },
      localCaseId: { type: 'number', required: true },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, localCaseId }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/local-cases/${localCaseId}`))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_update_local_case',
    description: '按唯一 localCaseId 原位更新本人未提交的本地用例，不创建新记录。先读取 versionNo，传 expectedVersion 和稳定的 requestKey；仅传需要修改的字段。普通草稿 ID 为正数，轨迹草稿 ID 为负数。',
    parameters: {
      projectId: { type: 'number', required: true },
      localCaseId: { type: 'number', required: true },
      expectedVersion: { type: 'number', required: true },
      requestKey: { type: 'string', required: true },
      caseTitle: { type: 'string' },
      moduleName: { type: 'string' },
      precondition: { type: 'string' },
      steps: { type: 'string' },
      expectedResult: { type: 'string' },
      priority: { type: 'string' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, localCaseId, ...body }) {
      return asText(await callApi(await currentBinding(ctx),
        `${projectPath(projectId)}/local-cases/${localCaseId}`, 'PATCH', body))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_revise_case_draft',
    description: '原位修订本人尚未提交的用例草稿，服务端递增版本并保留修订记录；须先读取当前 versionNo，不能重复新建。',
    parameters: {
      projectId: { type: 'number', required: true },
      draftId: { type: 'number', required: true },
      workSessionId: { type: 'number', required: true },
      expectedVersion: { type: 'number', required: true },
      idempotencyKey: { type: 'string', required: true },
      caseTitle: { type: 'string', required: true },
      moduleName: { type: 'string', required: true },
      precondition: { type: 'string' },
      steps: { type: 'string', required: true },
      expectedResult: { type: 'string', required: true },
      priority: { type: 'string' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, draftId, ...revision }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/drafts/${draftId}/revision`,
        'POST', revision))
    },
  }))

  ctx.tools.register(defineTool({
    name: 'aitestwork_propose_style',
    description: '根据用户多次修订或明确反馈提出个人用例写作偏好候选；只创建待本人确认候选，不改变生效偏好。',
    parameters: {
      projectId: { type: 'number', required: true, description: '项目 ID' },
      styleText: { type: 'string', required: true, description: '建议生效的完整个人偏好文本' },
      reason: { type: 'string', required: true, description: '提议依据，必须具体说明用户反馈或修订' },
      sourceDraftId: { type: 'number', description: '作为依据的本人草稿 ID' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute({ projectId, styleText, reason, sourceDraftId }) {
      return asText(await callApi(await currentBinding(ctx), `${projectPath(projectId)}/preference-candidates`,
        'POST', { styleText, reason, sourceDraftId }))
    },
  }))
}

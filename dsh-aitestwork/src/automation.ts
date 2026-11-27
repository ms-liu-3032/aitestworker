import { LogAssertionRun, type LogRule, type LogEvidence, type Observation } from './log-assertions.js'
import type { AdminLogRuntime } from './admin-logs.js'
import { randomUUID } from 'node:crypto'
import type { Binding } from './client.js'
import { callApi, projectPath } from './client.js'
import { validateLocalMapping } from './local-design.js'
import type { CaptureRuntime } from './capture.js'

type LocatorSpec = { by: 'testId' | 'role' | 'label' | 'placeholder' | 'text' | 'css' | 'nth';
  value?: string; name?: string; base?: string; index?: number; fallback?: LocatorSpec[]; within?: LocatorSpec }
type ResponseSpec = { path: string; method?: string; status?: number; timeout?: number }
type Step = { type: 'click' | 'fill' | 'press' | 'select' | 'check' | 'uncheck' | 'waitFor' | 'wait' | 'scroll' | 'drag'; locator?: LocatorSpec;
  data?: string; waitBefore?: number; state?: 'visible' | 'hidden'; expected?: string; timeout?: number;
  timeoutMs?: number; waitForResponse?: ResponseSpec; deltaX?: number; deltaY?: number; repeat?: number; dragSteps?: number }
type Assertion = LocatorSpec & { locator?: LocatorSpec; expected?: string; match?: 'contains' | 'equals';
  timeout?: number; waitBefore?: number; state?: 'visible' | 'hidden' }
export type Mapping = { caseNo: string; page: string; steps: Step[]; assertions: Assertion[]; logAssertions?: LogRule[];
  uiCoverage?: { ruleCode: string; fieldKey: string; role: 'POSITIVE' | 'NEGATIVE'; fieldStepIndex: number;
    submitStepIndex: number; errorAssertionIndex?: number; noSuccessAssertionIndex?: number };
  ruleCoverage?: { ruleCode: string; subjectKey: string; assertionIndex: number } }
type Asset = { id: number; assetType?: string; status: string; currentVersion: number; latestVersion: number;
  caseStale?: boolean; caseMissing?: boolean; reviewStatus?: string }
type Version = { versionNo: number; status: string; revisionKind: string; sourceRunId?: number; mapping: Mapping }
type RunPayload = { requestKey: string; versionNo: number; result: string; durationMs: number;
  evidenceSessionId: number; logEvidence?: LogEvidence[]; failureStepIndex?: number; failureReason?: string }
type BrowserPage = ReturnType<CaptureRuntime['pageForAutomation']>
type BrowserLocator = ReturnType<BrowserPage['locator']>

export type UiMappingResult = { result: string; logEvidence?: LogEvidence[]; failureStepIndex?: number; failureReason?: string;
  failureContext?: { url: string; body: string } }

export function resolveTargetPage(targetUrl: string, pagePath: string): URL {
  const target = new URL(targetUrl)
  if (!['http:', 'https:'].includes(target.protocol)) throw new Error('被测地址必须是 HTTP/HTTPS')
  const pageUrl = new URL(pagePath, target.origin)
  if (pageUrl.origin !== target.origin) throw new Error('映射页面不能跳出被测站点')
  return pageUrl
}

function locator(page: BrowserPage, spec: LocatorSpec): BrowserLocator {
  if (!spec || typeof spec !== 'object') throw new Error('定位器为空')
  const root: BrowserPage | BrowserLocator = spec.within ? locator(page, spec.within) : page
  switch (spec.by) {
    case 'testId': return root.getByTestId(spec.value || '')
    case 'role': return root.getByRole((spec.value || '') as Parameters<typeof page.getByRole>[0],
      spec.name ? { name: spec.name, exact: true } : undefined)
    case 'label': return root.getByLabel(spec.value || '', { exact: true })
    case 'placeholder': return root.getByPlaceholder(spec.value || '', { exact: true })
    case 'text': return root.getByText(spec.value || '', { exact: true })
    case 'css': return root.locator(spec.value || '')
    case 'nth': return root.locator(spec.base || '').nth(spec.index ?? 0)
    default: throw new Error('不支持的定位方式')
  }
}

function resolveData(template: string | undefined, params: Record<string, string>): string {
  if (template === undefined) throw new Error('输入步骤缺少数据')
  return template.replace(/\$\{([A-Za-z][A-Za-z0-9_]*)\}/g, (_whole, key: string) => {
    const value = params[key]
    if (typeof value !== 'string') throw new Error(`缺少运行时测试数据参数：${key}`)
    return value
  })
}

function failureReason(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error)
  return message.split('\n')[0].slice(0, 1000)
}

function locatorFailure(error: unknown): boolean {
  return /timeout|waiting for|not visible|intercepts pointer|strict mode|element is not attached|no node found/i
    .test(error instanceof Error ? error.message : String(error))
}

async function performStep(page: BrowserPage, target: BrowserLocator, step: Step, data?: string, observe = false): Promise<Observation | undefined> {
  const action = async () => {
    switch (step.type) {
      case 'click': await target.click({ timeout: 15_000 }); break
      case 'fill': await target.fill(data!, { timeout: 15_000 }); break
      case 'press': await target.press(data!, { timeout: 15_000 }); break
      case 'select': await target.selectOption(data!, { timeout: 15_000 }); break
      case 'check': await target.check({ timeout: 15_000 }); break
      case 'uncheck': await target.uncheck({ timeout: 15_000 }); break
      case 'scroll': {
        if (typeof step.deltaY !== 'number' || !Number.isInteger(step.deltaY) || step.deltaY === 0 || Math.abs(step.deltaY) > 2000
            || !Number.isInteger(step.deltaX ?? 0) || Math.abs(step.deltaX ?? 0) > 2000
            || !Number.isInteger(step.repeat ?? 1) || (step.repeat ?? 1) < 1 || (step.repeat ?? 1) > 10) {
          throw new Error('滚动步骤需要 deltaY（-2000 至 2000，不能为 0）、可选 deltaX 和 1–10 次 repeat')
        }
        await target.scrollIntoViewIfNeeded({ timeout: 15_000 })
        const box = await target.boundingBox()
        if (!box || box.width <= 0 || box.height <= 0) throw new Error('滚动目标没有可见区域')
        await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
        for (let i = 0; i < (step.repeat ?? 1); i++) await page.mouse.wheel(step.deltaX ?? 0, step.deltaY!)
        break
      }
      case 'drag': {
        const dx = step.deltaX === undefined ? 0 : step.deltaX
        const dy = step.deltaY === undefined ? 0 : step.deltaY
        const moves = step.dragSteps === undefined ? 12 : step.dragSteps
        if (!Number.isInteger(dx) || Math.abs(dx) > 2000 || !Number.isInteger(dy) || Math.abs(dy) > 2000
            || (dx === 0 && dy === 0) || !Number.isInteger(moves) || moves < 2 || moves > 50) {
          throw new Error('拖拽步骤需要非零 deltaX 或 deltaY（各在 ±2000 内），dragSteps 须为 2–50')
        }
        await target.scrollIntoViewIfNeeded({ timeout: 15_000 })
        const box = await target.boundingBox()
        if (!box || box.width <= 0 || box.height <= 0) throw new Error('拖拽目标没有可见区域')
        const x = box.x + box.width / 2
        const y = box.y + box.height / 2
        await page.mouse.move(x, y)
        await page.mouse.down()
        try { await page.mouse.move(x + dx, y + dy, { steps: moves }) }
        finally { await page.mouse.up() }
        break
      }
      default: throw new Error('映射步骤动作无效')
    }
  }
  const spec = step.waitForResponse
  if (!spec) { await action(); return }
  if (!spec.path.startsWith('/') || spec.path.startsWith('//') || spec.path.includes('?') || spec.path.includes('#')) {
    throw new Error('业务响应路径须为站内绝对路径，不含查询参数')
  }
  const expectedPath = new URL(spec.path, page.url()).pathname
  const response = page.waitForResponse(candidate => {
    const url = new URL(candidate.url())
    return url.origin === new URL(page.url()).origin && url.pathname === expectedPath
      && candidate.request().method() === (spec.method || 'POST')
      && candidate.status() === (spec.status || 200)
  }, { timeout: spec.timeout || 15_000 }).catch(() => {
    throw new Error(`业务请求未按预期返回：${spec.method || 'POST'} ${expectedPath}`)
  })
  // Register the listener before the action, so fast requests are not missed.
  const [, received] = await Promise.all([action(), response])
  if (!observe) return
  // Bound extraction. Do not return response bodies or authentication headers as evidence.
  const headers: Record<string, string> = {}
  for (const name of ['x-trace-id', 'x-request-id']) { const value = await received.headerValue(name); if (value) headers[name] = value }
  let body: unknown
  try {
    const bytes = await received.body()
    if (bytes.length <= 1_000_000) { try { body = JSON.parse(bytes.toString('utf8')) } catch { /* Optional JSON. */ } }
  } catch { /* Missing correlation becomes INCONCLUSIVE in the log runner. */ }
  return { headers, body }
}

/** One Playwright action/assertion path shared by AITestWork-backed and local-only runs. */
export async function runUiMapping(page: BrowserPage, mapping: Mapping, pageUrl: URL,
  params: Record<string, string> = {}, adminLogs?: AdminLogRuntime): Promise<UiMappingResult> {
  if (mapping.logAssertions?.length && adminLogs) adminLogs.useTarget(pageUrl.href)
  const logs = new LogAssertionRun(adminLogs, mapping)
  let result = 'PASS'
  let failureStepIndex: number | undefined
  let reason: string | undefined
  let failureContext: { url: string; body: string } | undefined
  let stage: 'NAVIGATION' | 'STEP' | 'ASSERTION' = 'NAVIGATION'
  let fixedWaitTotalMs = 0
  try {
    const response = await page.goto(pageUrl.href, { waitUntil: 'domcontentloaded', timeout: 30_000 })
    if (response && response.status() >= 500) throw new Error(`被测服务 HTTP ${response.status()}`)
    stage = 'STEP'
    for (let index = 0; index < mapping.steps.length; index++) {
      const step = mapping.steps[index]
      await logs.beforeStep(index)
      let observation: Observation | undefined
      failureStepIndex = index
      const data = ['fill', 'press', 'select'].includes(step.type) ? resolveData(step.data, params) : undefined
      if (mapping.uiCoverage?.role === 'POSITIVE' && index === mapping.uiCoverage.fieldStepIndex
          && !data?.trim()) throw new Error('必填项正向运行未提供有效输入')
      if (step.type === 'wait') {
        if (Object.keys(step).some(key => key !== 'type' && key !== 'timeoutMs')
            || !Number.isInteger(step.timeoutMs) || step.timeoutMs! < 100 || step.timeoutMs! > 30_000)
          throw new Error('wait 仅接受 type 和 timeoutMs，等待时长须为 100-30000 毫秒')
        fixedWaitTotalMs += step.timeoutMs!
        if (fixedWaitTotalMs > 60_000) throw new Error('固定等待总时长不能超过 60000 毫秒')
        await page.waitForTimeout(step.timeoutMs!)
        continue
      }
      const candidates = [step.locator!, ...(step.locator!.fallback || [])]
      for (let attempt = 0; attempt < candidates.length; attempt++) {
        try {
          const targetLocator = locator(page, candidates[attempt])
          if (step.type === 'waitFor') {
            const timeout = step.timeout || 15_000
            if (step.expected !== undefined) await targetLocator.filter({ hasText: step.expected }).waitFor({ state: 'visible', timeout })
            else await targetLocator.waitFor({ state: step.state || 'visible', timeout })
            break
          }
          await targetLocator.waitFor({ state: 'visible', timeout: step.waitBefore || 15_000 })
          observation = await performStep(page, targetLocator, step, data, !!mapping.logAssertions?.some(rule => rule.stepIndex === index))
          break
        } catch (error) {
          if (attempt === candidates.length - 1 || !locatorFailure(error)) throw error
        }
      }
      if (mapping.logAssertions?.some(rule => rule.stepIndex === index)) await logs.afterStep(index, observation || { headers: {} })
    }
    failureStepIndex = undefined
    stage = 'ASSERTION'
    for (const assertion of mapping.assertions) {
      const targetLocator = locator(page, assertion.locator || assertion)
      await targetLocator.waitFor({ state: assertion.state || 'visible', timeout: assertion.timeout || assertion.waitBefore || 15_000 })
      if (assertion.state === 'hidden') continue
      const count = await targetLocator.count()
      if (count !== 1) throw new Error(`断言定位到 ${count} 个元素；请限定作用域或使用已核实的 nth`)
      if (assertion.expected !== undefined) {
        const actual = await targetLocator.innerText({ timeout: assertion.timeout || 15_000 })
        const matches = assertion.match === 'equals' ? actual.trim() === assertion.expected.trim()
          : actual.includes(assertion.expected)
        if (!matches) throw new Error('断言内容与预期不符')
      }
    }
  } catch (error) {
    reason = failureReason(error)
    try {
      failureContext = { url: page.url(), body: await page.locator('body').innerText()
        .then(text => text.slice(0, 3000)).catch(() => '') }
    } catch { /* The local trace and report still proceed after a closed page. */ }
    result = /^LOG_(?:INCONCLUSIVE|PRECHECK_FAILED)/.test(reason) ? 'ENVIRONMENT_FAILURE'
      : /^LOG_FAIL/.test(reason) ? 'ASSERTION_FAILURE'
      : stage === 'NAVIGATION' || /Target page, context or browser has been closed|browser.*disconnected|page has been closed/i.test(reason)
      ? 'ENVIRONMENT_FAILURE'
      : stage === 'ASSERTION' ? 'ASSERTION_FAILURE'
      : /缺少运行时测试数据参数|输入步骤缺少数据/.test(reason) ? 'DATA_FAILURE'
      : locatorFailure(error) ? 'LOCATOR_FAILURE' : 'OTHER_FAILURE'
  }
  logs.discard()
  return { result, failureStepIndex, failureReason: reason, failureContext, logEvidence: logs.evidence }
}

/** Executes a stored mapping directly. The agent is called only if a failed locator needs a proposed heal. */
export async function executeAutomationAsset(binding: Binding, capture: CaptureRuntime, projectId: number,
  assetId: number, targetUrl: string, params: Record<string, string> = {}, requestedVersion?: number,
  headless = false, adminLogs?: AdminLogRuntime) {
  const base = `${projectPath(projectId)}/automation-assets/${assetId}`
  const asset = await callApi<Asset>(binding, base)
  if (asset.assetType === 'API') throw new Error('该资产是接口映射，请使用接口执行工具')
  if (asset.status === 'ARCHIVED') throw new Error('自动化资产已舍弃，不能执行')
  if (asset.caseMissing) throw new Error('关联用例已不存在，不能执行；请舍弃旧资产并在现有用例上建立映射')
  if (asset.status === 'BLOCKED') throw new Error('自动化资产已阻断，请先在 AITestWork Web 人工确认')
  const versionNo = requestedVersion ?? asset.currentVersion
  if (asset.caseStale && versionNo === asset.currentVersion) {
    throw new Error('归属用例已有新版本，请项目管理者修订执行映射，不能直接复跑旧版本')
  }
  const version = await callApi<Version>(binding, `${base}/versions/${versionNo}`)
  validateLocalMapping(version.mapping)
  if (requestedVersion === undefined && asset.status !== 'VERIFIED') {
    throw new Error('只有已验证映射允许日常直接复跑；首次验证或自愈候选须明确版本号')
  }
  if (requestedVersion === undefined && asset.reviewStatus !== 'APPROVED') {
    throw new Error('当前执行映射尚未在 AITestWork Web 审核通过，不能日常复跑')
  }
  if (version.status === 'REJECTED' || (versionNo !== asset.currentVersion
    && (versionNo <= asset.currentVersion || version.status !== 'DRAFT'))) {
    throw new Error('版本不可执行')
  }
  const pageUrl = resolveTargetPage(targetUrl, version.mapping.page)
  const started = Date.now()
  const captureRun = await capture.start(binding, projectId, pageUrl.origin,
    `自动化 ${version.mapping.caseNo} v${versionNo}`, undefined, headless)
  let mappingRun: UiMappingResult
  try {
    mappingRun = await runUiMapping(capture.pageForAutomation(binding, projectId, captureRun.sessionId),
      version.mapping, pageUrl, params, adminLogs)
  } catch (error) {
    mappingRun = { result: 'ENVIRONMENT_FAILURE', failureReason: failureReason(error) }
  }
  const { result, failureStepIndex, failureReason: reason, failureContext } = mappingRun
  const runPayload: RunPayload = { requestKey: randomUUID(), versionNo, result,
    durationMs: Date.now() - started, evidenceSessionId: captureRun.sessionId,
    failureStepIndex, failureReason: reason, logEvidence: mappingRun.logEvidence }
  const localReport = async (storageStatus: 'RECORDED' | 'EVIDENCE_PENDING' | 'RUN_WRITE_PENDING') => {
    try {
      return { reportPath: await capture.writeLocalReport({ projectId, assetId,
        caseNo: version.mapping.caseNo, versionNo, sessionId: captureRun.sessionId, result,
        durationMs: runPayload.durationMs, stepCount: version.mapping.steps.length,
        assertionCount: version.mapping.assertions.length, failureStepIndex,
        failureReason: reason, storageStatus, startedAt: started, logEvidence: mappingRun.logEvidence }) }
    } catch (error) {
      return { reportError: failureReason(error) }
    }
  }
  let windowCloseError: string | undefined
  let browserWindowClosed: boolean | undefined
  try {
    const stopped = await capture.stop(binding, projectId, captureRun.sessionId)
    windowCloseError = stopped?.windowCloseError
    browserWindowClosed = stopped?.browserWindowClosed
  } catch (error) {
    const report = await localReport('EVIDENCE_PENDING')
    return { status: 'EVIDENCE_PENDING', assetId, sessionId: captureRun.sessionId,
      result, failureReason: reason, failureContext, runPayload, uploadError: failureReason(error),
      windowCloseError: capture.windowCloseError?.(captureRun.sessionId),
      browserWindowClosed: capture.windowClosed?.(captureRun.sessionId), ...report }
  }
  let stored: unknown
  try {
    stored = await callApi(binding, `${base}/runs`, 'POST', runPayload)
  } catch (error) {
    const report = await localReport('RUN_WRITE_PENDING')
    return { status: 'RUN_WRITE_PENDING', assetId, sessionId: captureRun.sessionId,
      result, failureReason: reason, failureContext, runPayload, writeError: failureReason(error),
      windowCloseError, browserWindowClosed, ...report }
  }
  const report = await localReport('RECORDED')
  return { status: 'RECORDED', assetId, sessionId: captureRun.sessionId, result, run: stored,
    selfHealAllowed: result === 'LOCATOR_FAILURE', failureStepIndex, failureReason: reason,
    failureContext, windowCloseError, browserWindowClosed, ...report }
}

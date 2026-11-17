import { randomUUID } from 'node:crypto'
import type { Binding } from './client.js'
import { callApi, projectPath } from './client.js'
import type { CaptureRuntime } from './capture.js'

type LocatorSpec = { by: 'testId' | 'role' | 'label' | 'placeholder' | 'text' | 'css' | 'nth';
  value?: string; name?: string; base?: string; index?: number; fallback?: LocatorSpec[]; within?: LocatorSpec }
type ResponseSpec = { path: string; method?: string; status?: number; timeout?: number }
type Step = { type: 'click' | 'fill' | 'press' | 'select' | 'check' | 'uncheck' | 'waitFor'; locator: LocatorSpec;
  data?: string; waitBefore?: number; state?: 'visible' | 'hidden'; expected?: string; timeout?: number;
  waitForResponse?: ResponseSpec }
type Assertion = LocatorSpec & { locator?: LocatorSpec; expected?: string; match?: 'contains' | 'equals';
  timeout?: number; waitBefore?: number }
type Mapping = { caseNo: string; page: string; steps: Step[]; assertions: Assertion[] }
type Asset = { id: number; assetType?: string; status: string; currentVersion: number; latestVersion: number;
  caseStale?: boolean; caseMissing?: boolean; reviewStatus?: string }
type Version = { versionNo: number; status: string; revisionKind: string; sourceRunId?: number; mapping: Mapping }
type RunPayload = { requestKey: string; versionNo: number; result: string; durationMs: number;
  evidenceSessionId: number; failureStepIndex?: number; failureReason?: string }
type BrowserPage = ReturnType<CaptureRuntime['pageForAutomation']>
type BrowserLocator = ReturnType<BrowserPage['locator']>

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

async function performStep(page: BrowserPage, target: BrowserLocator, step: Step, data?: string): Promise<void> {
  const action = async () => {
    switch (step.type) {
      case 'click': await target.click({ timeout: 15_000 }); break
      case 'fill': await target.fill(data!, { timeout: 15_000 }); break
      case 'press': await target.press(data!, { timeout: 15_000 }); break
      case 'select': await target.selectOption(data!, { timeout: 15_000 }); break
      case 'check': await target.check({ timeout: 15_000 }); break
      case 'uncheck': await target.uncheck({ timeout: 15_000 }); break
      default: throw new Error('映射步骤动作无效')
    }
  }
  const spec = step.waitForResponse
  if (!spec) return action()
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
  await Promise.all([action(), response])
}

/** Executes a stored mapping directly. The agent is called only if a failed locator needs a proposed heal. */
export async function executeAutomationAsset(binding: Binding, capture: CaptureRuntime, projectId: number,
  assetId: number, targetUrl: string, params: Record<string, string> = {}, requestedVersion?: number,
  headless = false) {
  const base = `${projectPath(projectId)}/automation-assets/${assetId}`
  const asset = await callApi<Asset>(binding, base)
  if (asset.assetType === 'API') throw new Error('该资产是接口映射，请使用接口执行工具')
  if (asset.status === 'ARCHIVED') throw new Error('自动化资产已舍弃，不能执行')
  if (asset.caseMissing) throw new Error('关联用例已不存在，不能执行；请舍弃旧资产并在现有用例上建立映射')
  if (asset.status === 'BLOCKED') throw new Error('自动化资产已阻断，请先在 Hub Web 人工确认')
  const versionNo = requestedVersion ?? asset.currentVersion
  if (asset.caseStale && versionNo === asset.currentVersion) {
    throw new Error('归属用例已有新版本，请项目管理者修订执行映射，不能直接复跑旧版本')
  }
  const version = await callApi<Version>(binding, `${base}/versions/${versionNo}`)
  if (requestedVersion === undefined && asset.status !== 'VERIFIED') {
    throw new Error('只有已验证映射允许日常直接复跑；首次验证或自愈候选须明确版本号')
  }
  if (requestedVersion === undefined && asset.reviewStatus !== 'APPROVED') {
    throw new Error('当前执行映射尚未在 Hub Web 审核通过，不能日常复跑')
  }
  if (version.status === 'REJECTED' || (versionNo !== asset.currentVersion
    && (versionNo <= asset.currentVersion || version.status !== 'DRAFT'))) {
    throw new Error('版本不可执行')
  }
  const target = new URL(targetUrl)
  if (!['http:', 'https:'].includes(target.protocol)) throw new Error('被测地址必须是 HTTP/HTTPS')
  const pageUrl = new URL(version.mapping.page, target.origin)
  if (pageUrl.origin !== target.origin) throw new Error('映射页面不能跳出被测站点')
  const started = Date.now()
  const captureRun = await capture.start(binding, projectId, target.origin, `自动化 ${version.mapping.caseNo} v${versionNo}`,
    undefined, headless)
  let result = 'PASS'
  let failureStepIndex: number | undefined
  let reason: string | undefined
  let failureContext: { url: string; body: string } | undefined
  let stage: 'NAVIGATION' | 'STEP' | 'ASSERTION' = 'NAVIGATION'
  try {
    const page = capture.pageForAutomation(binding, projectId, captureRun.sessionId)
    const response = await page.goto(pageUrl.href, { waitUntil: 'domcontentloaded', timeout: 30_000 })
    if (response && response.status() >= 500) throw new Error(`被测服务 HTTP ${response.status()}`)
    stage = 'STEP'
    for (let index = 0; index < version.mapping.steps.length; index++) {
      const step = version.mapping.steps[index]
      failureStepIndex = index
      const data = ['fill', 'press', 'select'].includes(step.type) ? resolveData(step.data, params) : undefined
      const candidates = [step.locator, ...(step.locator.fallback || [])]
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
          await performStep(page, targetLocator, step, data)
          break
        } catch (error) {
          if (attempt === candidates.length - 1 || !locatorFailure(error)) throw error
        }
      }
    }
    failureStepIndex = undefined
    stage = 'ASSERTION'
    for (const assertion of version.mapping.assertions) {
      const targetLocator = locator(page, assertion.locator || assertion)
      await targetLocator.waitFor({ state: 'visible', timeout: assertion.timeout || assertion.waitBefore || 15_000 })
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
      const page = capture.pageForAutomation(binding, projectId, captureRun.sessionId)
      failureContext = { url: page.url(), body: await page.locator('body').innerText()
        .then(text => text.slice(0, 3000)).catch(() => '') }
    } catch { /* Evidence upload still proceeds when the page has closed. */ }
    result = stage === 'NAVIGATION' || /Target page, context or browser has been closed|browser.*disconnected|page has been closed/i.test(reason)
      ? 'ENVIRONMENT_FAILURE'
      : stage === 'ASSERTION' ? 'ASSERTION_FAILURE'
      : /缺少运行时测试数据参数|输入步骤缺少数据/.test(reason) ? 'DATA_FAILURE'
      : locatorFailure(error) ? 'LOCATOR_FAILURE' : 'OTHER_FAILURE'
  }
  const runPayload: RunPayload = { requestKey: randomUUID(), versionNo, result,
    durationMs: Date.now() - started, evidenceSessionId: captureRun.sessionId,
    failureStepIndex, failureReason: reason }
  const localReport = async (storageStatus: 'RECORDED' | 'EVIDENCE_PENDING' | 'RUN_WRITE_PENDING') => {
    try {
      return { reportPath: await capture.writeLocalReport({ projectId, assetId,
        caseNo: version.mapping.caseNo, versionNo, sessionId: captureRun.sessionId, result,
        durationMs: runPayload.durationMs, stepCount: version.mapping.steps.length,
        assertionCount: version.mapping.assertions.length, failureStepIndex,
        failureReason: reason, storageStatus, startedAt: started }) }
    } catch (error) {
      return { reportError: failureReason(error) }
    }
  }
  try {
    await capture.stop(binding, projectId, captureRun.sessionId)
  } catch (error) {
    const report = await localReport('EVIDENCE_PENDING')
    return { status: 'EVIDENCE_PENDING', assetId, sessionId: captureRun.sessionId,
      result, failureReason: reason, failureContext, runPayload, uploadError: failureReason(error), ...report }
  }
  let stored: unknown
  try {
    stored = await callApi(binding, `${base}/runs`, 'POST', runPayload)
  } catch (error) {
    const report = await localReport('RUN_WRITE_PENDING')
    return { status: 'RUN_WRITE_PENDING', assetId, sessionId: captureRun.sessionId,
      result, failureReason: reason, failureContext, runPayload, writeError: failureReason(error), ...report }
  }
  const report = await localReport('RECORDED')
  return { status: 'RECORDED', assetId, sessionId: captureRun.sessionId, result, run: stored,
    selfHealAllowed: result === 'LOCATOR_FAILURE', failureStepIndex, failureReason: reason, failureContext, ...report }
}

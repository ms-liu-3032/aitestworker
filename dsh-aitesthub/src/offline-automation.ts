import { randomInt, randomUUID } from 'node:crypto'
import { createRequire } from 'node:module'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { mkdir, writeFile } from 'node:fs/promises'
import type { CaptureManager as CaptureManagerType, StopCaptureResponse } from 'local-browser-worker'
import { resolveTargetPage, runUiMapping, type Mapping, type UiMappingResult } from './automation.js'
import { validateLocalMapping } from './local-design.js'
import { writeLocalAutomationReport } from './report.js'

const require = createRequire(import.meta.url)
const { CaptureManager, resolveBrowser, DEFAULT_CONFIG } = require('local-browser-worker') as typeof import('local-browser-worker')

export class OfflineAutomation {
  private active = false

  constructor(private readonly root = join(homedir(), '.dsh', 'aitesthub-local'),
              private readonly providedManager?: CaptureManagerType) {}

  async run(mapping: Mapping, targetUrl: string, params: Record<string, string> = {},
            headless = false, caseTitle?: string): Promise<Record<string, unknown>> {
    if (this.active) throw new Error('已有本地 UI 自动化正在执行，请等待结束')
    validateLocalMapping(mapping)
    if (Object.values(params).some(value => typeof value !== 'string')) throw new Error('运行时测试数据必须是字符串')
    const pageUrl = resolveTargetPage(targetUrl, mapping.page)
    const browser = this.providedManager ? null : resolveBrowser(DEFAULT_CONFIG, process.platform)
    if (!this.providedManager && !browser) throw new Error('找不到 Chrome 或 Playwright Chromium；请先安装浏览器')
    const manager = this.providedManager || new CaptureManager(browser, process.platform, this.root)
    const sessionId = Date.now() * 1000 + randomInt(1000)
    const startedAt = Date.now()
    this.active = true
    let execution: UiMappingResult = { result: 'ENVIRONMENT_FAILURE', failureReason: '浏览器未启动' }
    let evidence: StopCaptureResponse | undefined
    let captureError: string | undefined
    let windowCloseError: string | undefined
    try {
      await manager.start({ traceGroupId: sessionId, sessionId, profileId: sessionId,
        profileName: caseTitle || mapping.caseNo, targetHost: pageUrl.origin, headless })
      execution = await runUiMapping(manager.pageForSession(sessionId), mapping, pageUrl, params)
    } catch (error) {
      execution = { result: 'ENVIRONMENT_FAILURE', failureReason: error instanceof Error ? error.message : String(error) }
    } finally {
      try { evidence = await manager.stop(sessionId) }
      catch (error) { captureError = error instanceof Error ? error.message : String(error) }
      await manager.shutdown().catch(() => {
        windowCloseError = '本次浏览器窗口回收失败，请检查本机浏览器进程'
      })
      this.active = false
    }
    const status = evidence ? 'LOCAL_ONLY' : 'LOCAL_CAPTURE_INCOMPLETE'
    const durationMs = Date.now() - startedAt
    let reportPath: string | undefined
    let reportError: string | undefined
    try {
      reportPath = await writeLocalAutomationReport(this.root, {
        caseNo: mapping.caseNo, caseTitle, versionNo: 1, sessionId, result: execution.result,
        durationMs, stepCount: mapping.steps.length, assertionCount: mapping.assertions.length,
        failureStepIndex: execution.failureStepIndex, failureReason: execution.failureReason,
        storageStatus: status, startedAt,
      })
    } catch (error) { reportError = error instanceof Error ? error.message : String(error) }
    const summary = { id: randomUUID(), mode: 'LOCAL_ONLY', status, sessionId, caseNo: mapping.caseNo,
      caseTitle, result: execution.result, durationMs, failureStepIndex: execution.failureStepIndex,
      failureReason: execution.failureReason, failureContext: execution.failureContext,
      reportPath, reportError, captureError, windowCloseError,
      browserWindowClosed: !windowCloseError,
      evidence: evidence ? { eventCount: evidence.events.length, networkCount: evidence.networks.length,
        screenshotPath: evidence.videoPath, tracePath: evidence.traceFilePath,
        screencastPath: evidence.screencastPath } : undefined }
    const directory = join(this.root, 'traces', String(sessionId))
    await mkdir(directory, { recursive: true, mode: 0o700 })
    await writeFile(join(directory, 'run.json'), JSON.stringify(summary), { mode: 0o600 })
    return summary
  }
}

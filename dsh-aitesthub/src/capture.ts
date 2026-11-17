import { createRequire } from 'node:module'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { readFile, writeFile, mkdir, rename, unlink } from 'node:fs/promises'
import type { Binding } from './client.js'
import { callApi, callUserApi, projectPath, uploadEvidence } from './client.js'
import { uploadCaptureBundle, clearCaptureBundle } from './capture-bundle.js'
import { writeLocalAutomationReport, type LocalReportInput } from './report.js'
import type { CaptureManager as CaptureManagerType, StopCaptureResponse } from 'local-browser-worker'

const require = createRequire(import.meta.url)
const { CaptureManager, resolveBrowser, DEFAULT_CONFIG } = require('local-browser-worker') as typeof import('local-browser-worker')

type CaptureStart = { sessionId: number; groupId: number; profileId: number; workSessionId?: number | null; targetUrl: string }
type Evidence = { id: number; kind: string }
type Running = { projectId: number; ownerUserId: number; binding: Binding; start: CaptureStart }

function localTime(value: string | null): string | null {
  return value ? value.slice(0, 19) : null
}

export class CaptureRuntime {
  private manager: CaptureManagerType | undefined
  private readonly running = new Map<number, Running>()
  private readonly pending = new Map<number, StopCaptureResponse>()
  private readonly uploadedBySession = new Map<number, Map<string, number>>()

  constructor(manager?: CaptureManagerType,
              private readonly recoveryRoot = join(homedir(), '.dsh', 'aitesthub-capture', 'pending'),
              private readonly captureRoot = join(homedir(), '.dsh', 'aitesthub-capture')) {
    this.manager = manager
  }

  hasActive(): boolean { return this.running.size > 0 }

  pageForAutomation(binding: Binding, projectId: number, sessionId: number) {
    const run = this.owned(binding, projectId, sessionId)
    return this.getManager().pageForSession(run.start.sessionId)
  }

  private getManager(): CaptureManagerType {
    if (!this.manager) {
      const browser = resolveBrowser(DEFAULT_CONFIG, process.platform)
      if (!browser) throw new Error('找不到 Chrome 或 Playwright Chromium；请先安装浏览器')
      this.manager = new CaptureManager(browser, process.platform, this.captureRoot)
    }
    return this.manager
  }

  async writeLocalReport(input: LocalReportInput): Promise<string> {
    return writeLocalAutomationReport(this.captureRoot, input)
  }

  async start(binding: Binding, projectId: number, targetUrl: string, name?: string,
              workSessionId?: number, headless = false): Promise<CaptureStart> {
    if (!/^https?:\/\/\S+$/.test(targetUrl)) throw new Error('目标必须是 HTTP 或 HTTPS 地址')
    const manager = this.getManager()
    const start = await callApi<CaptureStart>(binding, `${projectPath(projectId)}/captures`, 'POST',
      { targetUrl, name, workSessionId })
    try {
      await manager.start({ traceGroupId: start.groupId, sessionId: start.sessionId,
        profileId: start.profileId, profileName: name, targetHost: targetUrl, headless })
    } catch (error) {
      await callApi(binding, `${projectPath(projectId)}/captures/${start.sessionId}/finish`, 'POST',
        { events: [], networks: [] }).catch(() => undefined)
      throw error
    }
    this.running.set(start.sessionId, { projectId, ownerUserId: binding.userId, binding, start })
    return start
  }

  async action(binding: Binding, projectId: number, sessionId: number, action: string,
               value?: string, text?: string): Promise<{ url: string; title: string; body: string }> {
    const run = this.owned(binding, projectId, sessionId)
    if (this.pending.has(sessionId)) throw new Error('采集已经停止，请先完成证据上传')
    const page = this.getManager().pageForSession(run.start.sessionId)
    switch (action) {
      case 'navigate':
        if (!value || !/^https?:\/\/\S+$/.test(value)) throw new Error('navigate 需要 HTTP/HTTPS URL')
        await page.goto(value, { waitUntil: 'domcontentloaded', timeout: 30_000 })
        break
      case 'click':
        if (!value) throw new Error('click 需要定位器')
        await page.locator(value).first().click({ timeout: 15_000 })
        break
      case 'fill':
        if (!value || text === undefined) throw new Error('fill 需要定位器和值')
        await page.locator(value).first().fill(text, { timeout: 15_000 })
        break
      case 'press':
        if (!value || !text) throw new Error('press 需要定位器和按键名')
        await page.locator(value).first().press(text, { timeout: 15_000 })
        break
      case 'select':
        if (!value || text === undefined) throw new Error('select 需要定位器和选项值')
        await page.locator(value).first().selectOption(text, { timeout: 15_000 })
        break
      case 'check':
        if (!value) throw new Error('check 需要定位器')
        await page.locator(value).first().check({ timeout: 15_000 })
        break
      case 'uncheck':
        if (!value) throw new Error('uncheck 需要定位器')
        await page.locator(value).first().uncheck({ timeout: 15_000 })
        break
      case 'assertText': {
        if (!value || text === undefined) throw new Error('assertText 需要定位器和预期文本')
        const actual = await page.locator(value).first().innerText({ timeout: 15_000 })
        if (!actual.includes(text)) throw new Error(`文本断言失败：${value} 未包含预期内容 ${JSON.stringify(text)}`)
        break
      }
      case 'assertVisible':
        if (!value) throw new Error('assertVisible 需要定位器')
        await page.locator(value).first().waitFor({ state: 'visible', timeout: 15_000 })
        break
      case 'inspect': break
      default: throw new Error('支持 navigate、click、fill、press、select、check、uncheck、assertText、assertVisible、inspect')
    }
    return { url: page.url(), title: await page.title(), body: (await page.locator('body').innerText()).slice(0, 15_000) }
  }

  async stop(binding: Binding, projectId: number, sessionId: number): Promise<{ sessionId: number; eventCount: number; networkCount: number; alreadyFinished: boolean;
    bundle: { id: number; sha256: string; sizeBytes: number; summary: { artifactCount: number; sourceBytes: number; eventCount: number; networkCount: number; byKind: Record<string, number> } } }> {
    await this.restore(binding, projectId, sessionId)
    this.owned(binding, projectId, sessionId)
    let result = this.pending.get(sessionId)
    if (!result) {
      result = await this.getManager().stop(sessionId)
      await this.persist(binding, projectId, sessionId, result)
    }
    this.pending.set(sessionId, result)
    const base = `${projectPath(projectId)}/captures/${sessionId}`
    const uploaded = this.uploadedBySession.get(sessionId) ?? new Map<string, number>()
    this.uploadedBySession.set(sessionId, uploaded)
    const send = async (kind: string, path: string | null): Promise<number | null> => {
      if (!path) return null
      const key = `${kind}:${path}`
      if (uploaded.has(key)) return uploaded.get(key)!
      const evidence = await uploadEvidence<Evidence>(binding, `${base}/artifacts/${kind}`, path)
      uploaded.set(key, evidence.id)
      await this.persist(binding, projectId, sessionId, result, uploaded)
      return evidence.id
    }
    const events = []
    for (const item of result.events) {
      const screenshotArtifactId = await send('SCREENSHOT', item.screenshotPath)
      const { screenshotPath: _localPath, ...event } = item
      events.push({ event: { ...event,
        happenedAtUtc: localTime(event.happenedAtUtc),
        happenedAtLocal: localTime(event.happenedAtLocal) }, screenshotArtifactId })
    }
    const networks = result.networks.map(item => ({ ...item,
      requestStartedAtUtc: localTime(item.requestStartedAtUtc),
      requestStartedAtLocal: localTime(item.requestStartedAtLocal),
      responseEndedAtUtc: localTime(item.responseEndedAtUtc),
      responseEndedAtLocal: localTime(item.responseEndedAtLocal) }))
    const videoArtifactId = await send('SCREENSHOT', result.videoPath)
    const traceArtifactId = await send('TRACE', result.traceFilePath)
    if (result.screencastPath) {
      const manifest = JSON.parse(await readFile(result.screencastPath, 'utf8')) as { frames?: { filename: string }[] }
      const frames = manifest.frames ?? []
      for (let offset = 0; offset < frames.length; offset += 8) {
        const batch = frames.slice(offset, offset + 8)
        const results = await Promise.allSettled(batch.map(async frame => {
          if (!/^\d{6}\.jpg$/.test(frame.filename)) throw new Error('录屏帧文件名无效')
          const path = join(result.screencastPath!, '..', 'frames', frame.filename)
          const key = `FRAME:${path}`
          if (uploaded.has(key)) return null
          const evidence = await uploadEvidence<Evidence>(binding, `${base}/artifacts/FRAME`, path)
          return { key, id: evidence.id }
        }))
        for (const item of results) if (item.status === 'fulfilled' && item.value) {
          uploaded.set(item.value.key, item.value.id)
        }
        if (results.some(item => item.status === 'fulfilled' && item.value)) {
          await this.persist(binding, projectId, sessionId, result, uploaded)
        }
        const failure = results.find(item => item.status === 'rejected')
        if (failure?.status === 'rejected') throw failure.reason
      }
    }
    const manifestArtifactId = await send('MANIFEST', result.screencastPath)
    const bundle = await uploadCaptureBundle(binding, projectId, sessionId, result, this.recoveryRoot)
    try {
      const finished = await callApi<{ sessionId: number; eventCount: number; networkCount: number; alreadyFinished: boolean }>(
        binding, `${base}/finish`, 'POST', { events, networks, videoArtifactId, traceArtifactId, manifestArtifactId,
          screencastStartedAtUtc: localTime(result.screencastStartedAtUtc),
          screencastStoppedAtUtc: localTime(result.screencastStoppedAtUtc),
          screencastDurationMs: result.screencastDurationMs })
      await this.clear(sessionId)
      return { ...finished, bundle }
    } catch (error) {
      const remote = await callUserApi<{ status: string }>(binding,
        `/api/dsh/user/projects/${projectId}/captures/${sessionId}`).catch(() => null)
      if (remote?.status === 'FINISHED') {
        await this.clear(sessionId)
        return { sessionId, eventCount: events.length, networkCount: networks.length, alreadyFinished: true, bundle }
      }
      throw error
    }
  }

  async shutdown(): Promise<void> {
    for (const [sessionId, run] of this.running) {
      try { await this.stop(run.binding, run.projectId, sessionId) } catch { /* local files remain for recovery */ }
    }
    await this.manager?.shutdown()
    this.running.clear()
    this.pending.clear()
    this.uploadedBySession.clear()
  }

  private async clear(sessionId: number): Promise<void> {
    this.running.delete(sessionId)
    this.pending.delete(sessionId)
    this.uploadedBySession.delete(sessionId)
    await unlink(this.recoveryFile(sessionId)).catch(() => undefined)
    await clearCaptureBundle(this.recoveryRoot, sessionId)
  }

  private recoveryFile(sessionId: number): string { return join(this.recoveryRoot, `${sessionId}.json`) }

  private async persist(binding: Binding, projectId: number, sessionId: number,
                        result: StopCaptureResponse, uploaded = new Map<string, number>()): Promise<void> {
    await mkdir(this.recoveryRoot, { recursive: true, mode: 0o700 })
    const file = this.recoveryFile(sessionId)
    const temporary = `${file}.tmp`
    await writeFile(temporary, JSON.stringify({ projectId, ownerUserId: binding.userId,
      connectionId: binding.connectionId, result, uploaded: [...uploaded] }), { mode: 0o600 })
    await rename(temporary, file)
  }

  private async restore(binding: Binding, projectId: number, sessionId: number): Promise<void> {
    if (this.running.has(sessionId)) return
    let saved: { projectId: number; ownerUserId: number; connectionId: number;
      result: StopCaptureResponse; uploaded?: [string, number][] }
    try { saved = JSON.parse(await readFile(this.recoveryFile(sessionId), 'utf8')) }
    catch { return }
    if (saved.projectId !== projectId || saved.ownerUserId !== binding.userId || saved.connectionId !== binding.connectionId) {
      throw new Error('该采集恢复文件不属于当前 AITestHub 连接')
    }
    this.running.set(sessionId, { projectId, ownerUserId: binding.userId, binding,
      start: { sessionId, groupId: 0, profileId: 0, targetUrl: '' } })
    this.pending.set(sessionId, saved.result)
    this.uploadedBySession.set(sessionId, new Map(saved.uploaded ?? []))
  }

  private owned(binding: Binding, projectId: number, sessionId: number): Running {
    const run = this.running.get(sessionId)
    if (!run || run.projectId !== projectId || run.ownerUserId !== binding.userId || run.binding.connectionId !== binding.connectionId) {
      throw new Error('本机没有属于当前连接的活动采集会话')
    }
    return run
  }
}

import { createRequire } from 'node:module'
import { randomUUID, createHash } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'
import type { Browser, Page } from 'playwright-core'

type Reply = { status: number; contentType: string; range: string; text: string; truncated: boolean; bytesBase64?: string }
export interface LogTransport { get(path: string, range?: string): Promise<Reply>; close(): Promise<void> }
type Checkpoint = { instanceId: string; offset: number; anchorStart: number; anchor: string; createdAt: string }
class LogError extends Error {
  constructor(readonly code: string) { super(code) }
}
function instance(value: string) {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(value)) throw new LogError('INVALID_INSTANCE')
  return value
}
function redact(value: string): string {
  return value.replace(/(["']?(?:password|passwd|secret|token|authorization|cookie|api[_-]?key)["']?\s*[=:]\s*)(?:"[^"]*"|'[^']*'|[^\s,;]+)/gi, '$1[REDACTED]')
    .replace(/(?<![0-9])1[3-9][0-9]{9}(?![0-9])/g, '[PHONE_REDACTED]')
    .replace(/Bearer\s+[^\s,"']+/gi, 'Bearer [REDACTED]')
}
function range(reply: Reply) {
  if ([301, 302, 303, 307, 308, 401, 403].includes(reply.status) || /text\/html/i.test(reply.contentType)) throw new LogError('LOGIN_OR_PERMISSION_REQUIRED')
  if (reply.status === 416 && reply.range === 'bytes */0') return { start: 0, end: -1, total: 0 }
  if (reply.status !== 206) throw new LogError(`RANGE_UNSUPPORTED_HTTP_${reply.status}`)
  if (!/^text\/plain(?:;|$)/i.test(reply.contentType)) throw new LogError('UNEXPECTED_LOG_CONTENT_TYPE')
  const m = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(reply.range)
  if (!m) throw new LogError('INVALID_CONTENT_RANGE')
  const [start, end, total] = m.slice(1).map(Number)
  if (!Number.isSafeInteger(total) || start > end || end >= total) throw new LogError('INVALID_CONTENT_RANGE')
  if (reply.truncated || payload(reply).length !== end - start + 1) throw new LogError('INCOMPLETE_LOG_READ')
  return { start, end, total }
}
function payload(reply: Reply): Buffer { return reply.bytesBase64 ? Buffer.from(reply.bytesBase64, 'base64') : Buffer.from(reply.text) }

/** Dedicated unrecorded login window. No cookies, passwords or storage state are exported. */
export class AdminLogRuntime {
  private transport?: LogTransport
  private browser?: Browser
  private page?: Page
  private readonly checkpoints = new Map<string, Checkpoint>()
  private readonly busy = new Set<string>()
  private targetOrigin?: string
  private platformUrl?: string
  private loginUrl?: string
  get baseUrl(): string | undefined { return this.platformUrl }
  configure(platformUrl: string, loginUrl = platformUrl) {
    const platform = new URL(platformUrl)
    const login = new URL(loginUrl)
    for (const url of [platform, login]) if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new LogError('INVALID_PLATFORM_URL')
    if (platform.origin !== login.origin) throw new LogError('LOGIN_ORIGIN_MISMATCH')
    const base = platform.href.replace(/\/$/, '')
    if (this.platformUrl && (this.platformUrl !== base || this.loginUrl !== login.href)) throw new LogError('LOG_PLATFORM_CHANGED_DISCONNECT_FIRST')
    this.platformUrl = base
    this.loginUrl = login.href
  }
  constructor(transport?: LogTransport, private readonly evidenceRoot = join(homedir(), '.dsh', 'aitestwork-log-evidence'),
    platformUrl?: string) {
    this.transport = transport
    if (platformUrl) this.configure(platformUrl)
  }
  useTarget(targetUrl: string) {
    const target = new URL(targetUrl)
    if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password) throw new LogError('INVALID_TARGET_URL')
    if (this.targetOrigin && this.targetOrigin !== target.origin) throw new LogError('LOG_ENVIRONMENT_CHANGED_DISCONNECT_FIRST')
    this.targetOrigin = target.origin
  }
  async connect(platformUrl?: string, loginUrl?: string) {
    if (platformUrl) this.configure(platformUrl, loginUrl)
    if (!this.transport) {
      if (!this.baseUrl) throw new LogError('LOG_PLATFORM_URL_REQUIRED')
      const require = createRequire(import.meta.url)
      const { resolveBrowser, DEFAULT_CONFIG } = require('local-browser-worker') as typeof import('local-browser-worker')
      const found = resolveBrowser(DEFAULT_CONFIG, process.platform)
      if (!found) throw new LogError('BROWSER_UNAVAILABLE')
      const { chromium } = await import('playwright-core')
      this.browser = await chromium.launch({ executablePath: found.executablePath, headless: false })
      const context = await this.browser.newContext()
      this.page = await context.newPage()
      await this.page.goto(this.loginUrl!, { waitUntil: 'domcontentloaded', timeout: 30_000 })
      const page = this.page
      const baseUrl = this.baseUrl!
      this.transport = {
        async get(path, requestedRange) {
          if (!/^\/instances(?:\/[A-Za-z0-9_-]{1,128}(?:\/actuator\/logfile)?)?$/.test(path)) throw new LogError('READ_PATH_NOT_ALLOWED')
          if (new URL(page.url()).origin !== new URL(baseUrl).origin) throw new LogError('LOGIN_OR_PERMISSION_REQUIRED')
          try {
            return await page.evaluate(async ({ url, requestedRange }) => {
              const response = await fetch(url, { credentials: 'include', redirect: 'manual',
                headers: { Accept: requestedRange ? 'text/plain' : 'application/json', ...(requestedRange ? { Range: requestedRange } : {}) },
                signal: AbortSignal.timeout(15_000) })
              const reader = response.body?.getReader()
              const parts: Uint8Array[] = []; let bytes = 0; let truncated = false
              try {
                while (reader) {
                  const { done, value } = await reader.read(); if (done) break
                  bytes += value.byteLength
                  if (bytes > 262144) { truncated = true; await reader.cancel(); break }
                  parts.push(value)
                }
              } finally { reader?.releaseLock() }
              const joined = new Uint8Array(parts.reduce((n, p) => n + p.byteLength, 0)); let offset = 0
              for (const p of parts) { joined.set(p, offset); offset += p.byteLength }
              let binary = ''
              for (let i = 0; i < joined.length; i += 8192) binary += String.fromCharCode(...joined.subarray(i, i + 8192))
              return { status: response.status || 302, contentType: response.headers.get('content-type') || '',
                range: response.headers.get('content-range') || '', text: new TextDecoder().decode(joined),
                bytesBase64: btoa(binary), truncated }
            }, { url: `${baseUrl}${path}`, requestedRange })
          } catch { throw new LogError('LOG_CONNECTION_FAILED') }
        },
        async close() { await context.close() },
      }
      // Do not probe while a human is entering credentials or the page is navigating.
      return { status: 'LOGIN_REQUIRED', platform: this.baseUrl,
        message: '独立窗口已打开。请先完成登录，告知已登录后再调用服务列表；登录过程不录屏、不导出Cookie。' }
    }
    try { await this.instances(); return { status: 'CONNECTED', platform: this.baseUrl } }
    catch (error) {
      if (error instanceof LogError && error.code === 'LOGIN_OR_PERMISSION_REQUIRED') return { status: 'LOGIN_REQUIRED', platform: this.baseUrl,
        message: '请在独立浏览器窗口登录，随后重新读取服务列表；不要把密码交给 Agent。登录不会录屏或导出 Cookie。' }
      throw error
    }
  }
  async instances() {
    if (!this.transport) throw new LogError('CONNECT_FIRST')
    const r = await this.transport.get('/instances')
    if ([301, 302, 303, 307, 308, 401, 403].includes(r.status) || /html/i.test(r.contentType)) throw new LogError('LOGIN_OR_PERMISSION_REQUIRED')
    if (r.status !== 200 || !/json/i.test(r.contentType) || r.truncated) throw new LogError('INSTANCE_LIST_UNAVAILABLE')
    let rows: unknown
    try { rows = JSON.parse(r.text) } catch { throw new LogError('INSTANCE_LIST_UNAVAILABLE') }
    if (!Array.isArray(rows)) throw new LogError('INSTANCE_LIST_UNAVAILABLE')
    return rows.map((r: any) => ({ instanceId: String(r.id), serviceName: String(r.registration?.name || ''),
      status: String(r.statusInfo?.status || 'UNKNOWN'), logfileAvailable: Array.isArray(r.endpoints) && r.endpoints.some((e: any) => e.id === 'logfile') }))
  }
  async checkpoint(instanceId: string) {
    instance(instanceId)
    if (!this.transport) throw new LogError('CONNECT_FIRST')
    for (const [id, saved] of this.checkpoints) if (Date.now() - Date.parse(saved.createdAt) > 600_000) this.checkpoints.delete(id)
    if (this.checkpoints.size >= 100) throw new LogError('TOO_MANY_OPEN_CHECKPOINTS')
    const r = await this.transport.get(`/instances/${instanceId}/actuator/logfile`, 'bytes=-1024')
    const meta = range(r)
    if (meta.end !== meta.total - 1) throw new LogError('INCOMPLETE_LOG_READ')
    const checkpointId = randomUUID()
    this.checkpoints.set(checkpointId, { instanceId, offset: meta.total, anchorStart: meta.start,
      anchor: payload(r).toString('base64'), createdAt: new Date().toISOString() })
    return { checkpointId, instanceId, offset: meta.total, createdAt: this.checkpoints.get(checkpointId)!.createdAt,
      message: '现在执行测试动作，再用唯一 requestId/业务ID检查新增日志。此检查点不包含历史匹配。' }
  }
  async assertContains(checkpointId: string, correlation: string, expected: string, timeoutMs = 15_000) {
    if (this.busy.has(checkpointId)) throw new LogError('LOG_ASSERTION_BUSY')
    const c = this.checkpoints.get(checkpointId)
    if (!c || !this.transport) throw new LogError('CHECKPOINT_NOT_FOUND')
    if (Date.now() - Date.parse(c.createdAt) > 600_000) throw new LogError('CHECKPOINT_EXPIRED')
    if (!correlation || correlation.length < 4 || correlation.length > 256 || /[\r\n]/.test(correlation)
      || !expected.trim() || expected.length > 512 || /[\r\n]/.test(expected)) throw new LogError('INVALID_ASSERTION')
    if (!Number.isInteger(timeoutMs) || timeoutMs < 0 || timeoutMs > 60_000) throw new LogError('INVALID_TIMEOUT')
    this.busy.add(checkpointId)
    const started = Date.now(); let result = 'INCONCLUSIVE'; let reason = ''; let match = ''; let checkedBytes = 0
    try {
      do {
        const r = await this.transport.get(`/instances/${c.instanceId}/actuator/logfile`, `bytes=${c.anchorStart}-${c.anchorStart + 262143}`)
        const meta = range(r)
        const data = payload(r); const anchor = Buffer.from(c.anchor, 'base64')
        if (meta.total < c.offset || meta.start !== c.anchorStart || !data.subarray(0, anchor.length).equals(anchor)) throw new LogError('LOG_ROTATED_OR_CHANGED')
        if (meta.end !== meta.total - 1) throw new LogError('LOG_WINDOW_TOO_LARGE')
        const fresh = data.subarray(c.offset - c.anchorStart).toString('utf8')
        checkedBytes = data.length - (c.offset - c.anchorStart)
        // Only a complete new line with BOTH values may satisfy this positive assertion.
        match = fresh.split('\n').slice(0, -1).find(line => {
          const escaped = correlation.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
          return new RegExp(`(?<![A-Za-z0-9_-])${escaped}(?![A-Za-z0-9_-])`).test(line) && line.includes(expected)
        }) || ''
        if (match) { result = 'PASS'; reason = 'CORRELATED_NEW_LOG_MATCH'; break }
        if (Date.now() - started >= timeoutMs) { result = 'FAIL'; reason = 'EXPECTED_EVENT_NOT_OBSERVED_WITHIN_WINDOW'; break }
        await new Promise(resolve => setTimeout(resolve, Math.min(500, timeoutMs - (Date.now() - started))))
      } while (true)
    } catch (error) { reason = error instanceof LogError ? error.code : 'LOG_READ_FAILED' }
    finally { this.busy.delete(checkpointId); this.checkpoints.delete(checkpointId) }
    const evidenceId = randomUUID()
    const evidence = { kind: 'ADMIN_LOG_ASSERTION', evidenceId, checkpointId, instanceId: c.instanceId,
      platformPath: this.baseUrl ? new URL(this.baseUrl).pathname : undefined, startedAt: c.createdAt, completedAt: new Date().toISOString(),
      rule: { type: 'NEW_LINE_CONTAINS', correlation: redact(correlation), expected: redact(expected) },
      result, reason, checkedBytes, matchedLine: redact(match).slice(0, 2000), matchedLineExcerptTruncated: redact(match).length > 2000,
      matchedLineSha256: match ? createHash('sha256').update(match).digest('hex') : null, storage: 'LOCAL_ONLY' }
    await mkdir(this.evidenceRoot, { recursive: true, mode: 0o700 })
    const evidencePath = join(this.evidenceRoot, `${evidenceId}.json`)
    await writeFile(evidencePath, JSON.stringify(evidence, null, 2), { mode: 0o600 })
    return { ...evidence, evidencePath, message: '仅日志规则结果，未回写 AITestWork；整条用例还需汇总 UI/API 与其他必需断言。' }
  }
  discard(checkpointId: string) { this.checkpoints.delete(checkpointId) }
  async close() {
    await this.transport?.close().catch(() => undefined)
    await this.browser?.close().catch(() => undefined)
    this.transport = undefined; this.browser = undefined; this.page = undefined; this.targetOrigin = undefined; this.platformUrl = undefined; this.loginUrl = undefined; this.checkpoints.clear()
  }
}

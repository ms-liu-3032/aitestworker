import { createHash, randomUUID } from 'node:crypto'
import { isDeepStrictEqual } from 'node:util'
import { homedir } from 'node:os'
import { join } from 'node:path'
import type { Binding } from './client.js'
import { callApi, projectPath } from './client.js'
import { writeLocalApiAutomationReport } from './report.js'

type HeaderValue = string | { secretRef: string }
type Step = { method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD'; path: string;
  headers?: Record<string, HeaderValue>; body?: unknown; extract?: Record<string, string>;
  timeoutMs?: number; retryCount?: number }
type Check = { stepIndex: number; target: 'STATUS' | 'HEADER' | 'JSON'; path?: string;
  operator: 'EQ' | 'CONTAINS' | 'EXISTS'; expected?: unknown }
type Mapping = { caseNo: string; steps: Step[]; assertions: Check[] }
type Asset = { id: number; assetType: string; status: string; reviewStatus: string;
  currentVersion: number; caseStale?: boolean; caseMissing?: boolean }
type Version = { versionNo: number; status: string; mapping: Mapping }
type EvidenceStep = { method: string; path: string; statusCode?: number; durationMs: number;
  requestBodySha256?: string; responseBodySha256?: string; responseBytes?: number;
  responseTruncated?: boolean; error?: string }
type EvidenceCheck = { stepIndex: number; target: string; path?: string; operator: string; passed: boolean }

class DataIssue extends Error {}
class AssertionIssue extends Error {}

const digest = (value: string) => createHash('sha256').update(value).digest('hex')

function secret(name: string): string {
  if (!/^[A-Z][A-Z0-9_]{0,63}$/.test(name)) throw new DataIssue('本机密钥引用名称无效')
  const value = process.env[`AITESTHUB_API_SECRET_${name}`]
  if (!value) throw new DataIssue(`本机缺少接口测试密钥 ${name}；请配置 DSH 的运行环境`)
  return value
}

function render(value: string, vars: Record<string, string>, encode = false): string {
  const result = value.replace(/\$\{(secret:)?([A-Za-z][A-Za-z0-9_]*)\}/g, (_match, prefix: string | undefined, name: string) => {
    const resolved = prefix ? secret(name) : vars[name]
    if (resolved === undefined) throw new DataIssue(`缺少接口测试参数 ${name}`)
    return encode ? encodeURIComponent(resolved) : resolved
  })
  if (result.includes('${')) throw new DataIssue('接口模板含无法解析的参数')
  return result
}

function renderBody(value: unknown, vars: Record<string, string>): unknown {
  if (typeof value === 'string') return render(value, vars)
  if (Array.isArray(value)) return value.map(item => renderBody(item, vars))
  if (value && typeof value === 'object') return Object.fromEntries(
    Object.entries(value).map(([key, item]) => [key, renderBody(item, vars)]))
  return value
}

function jsonAt(value: unknown, path: string): unknown {
  if (!/^\$(?:\.[A-Za-z_][A-Za-z0-9_]*|\[[0-9]{1,4}\])*$/.test(path)) throw new DataIssue('JSON 路径无效')
  const parts = path.slice(1).match(/\.[A-Za-z_][A-Za-z0-9_]*|\[[0-9]{1,4}\]/g) || []
  return parts.reduce<unknown>((current, part) => {
    if (current === null || typeof current !== 'object') return undefined
    const key = part.startsWith('.') ? part.slice(1) : Number(part.slice(1, -1))
    return (current as Record<string | number, unknown>)[key]
  }, value)
}

async function readResponse(response: Response): Promise<{ text: string; bytes: number; truncated: boolean }> {
  const reader = response.body?.getReader()
  if (!reader) return { text: '', bytes: 0, truncated: false }
  const chunks: Uint8Array[] = []
  let bytes = 0
  let truncated = false
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      bytes += value.byteLength
      if (bytes > 1_000_000) { truncated = true; await reader.cancel(); break }
      chunks.push(value)
    }
  } finally { reader.releaseLock() }
  const joined = new Uint8Array(chunks.reduce((size, chunk) => size + chunk.byteLength, 0))
  let offset = 0
  for (const chunk of chunks) { joined.set(chunk, offset); offset += chunk.byteLength }
  return { text: new TextDecoder().decode(joined), bytes, truncated }
}

function checkResponse(check: Check, response: Response, body: unknown): boolean {
  const actual = check.target === 'STATUS' ? response.status
    : check.target === 'HEADER' ? response.headers.get(check.path || '') ?? undefined
    : jsonAt(body, check.path || '')
  if (check.operator === 'EXISTS') return actual !== undefined && actual !== null
  if (check.operator === 'EQ') return isDeepStrictEqual(actual, check.expected)
  return actual !== undefined && actual !== null && String(actual).includes(String(check.expected))
}

function safePath(template: string): string {
  const [pathname, query] = template.split('?', 2)
  return `${pathname}${query ? `?${[...new URLSearchParams(query).keys()].map(key => `${key}=[REDACTED]`).join('&')}` : ''}`
}

/** Runs a stored API mapping locally. No model call and no browser session are involved. */
export async function executeApiAutomationAsset(binding: Binding, projectId: number, assetId: number,
  targetUrl: string, params: Record<string, string> = {}, requestedVersion?: number,
  reportRoot = join(homedir(), '.dsh', 'aitesthub-capture')) {
  const base = `${projectPath(projectId)}/automation-assets/${assetId}`
  const asset = await callApi<Asset>(binding, base)
  if (asset.assetType !== 'API') throw new Error('该资产是浏览器 UI 映射，请使用 UI 执行工具')
  if (asset.caseMissing) throw new Error('关联用例已不存在，不能执行；请舍弃旧资产并在现有用例上建立映射')
  if (asset.status === 'ARCHIVED' || asset.status === 'BLOCKED') throw new Error('接口资产已舍弃或阻断')
  const versionNo = requestedVersion ?? asset.currentVersion
  if (asset.caseStale && versionNo === asset.currentVersion) throw new Error('归属用例已更新，请先修订接口映射')
  if (requestedVersion === undefined && (asset.status !== 'VERIFIED' || asset.reviewStatus !== 'APPROVED'))
    throw new Error('日常复跑须先完成执行验证和 Web 审核；首次验证请指定 versionNo')
  const version = await callApi<Version>(binding, `${base}/versions/${versionNo}`)
  if (version.status === 'REJECTED' || (versionNo !== asset.currentVersion
    && (versionNo <= asset.currentVersion || version.status !== 'DRAFT'))) throw new Error('映射版本不可执行')
  const target = new URL(targetUrl)
  if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password)
    throw new Error('被测接口根地址须为无账号密码的 HTTP/HTTPS 地址')
  const values = { ...params }
  const steps: EvidenceStep[] = []
  const checks: EvidenceCheck[] = []
  let result = 'PASS'
  let failureReason: string | undefined
  let failureStepIndex: number | undefined
  const started = Date.now()
  for (let index = 0; index < version.mapping.steps.length; index++) {
    const step = version.mapping.steps[index]
    const stepStart = Date.now()
    let detail: EvidenceStep = { method: step.method, path: '[UNRESOLVED]', durationMs: 0 }
    try {
      if (step.path.includes('${secret:')) throw new DataIssue('接口路径不能包含密钥')
      const path = render(step.path, values, true)
      if (!path.startsWith('/') || path.startsWith('//')) throw new DataIssue('接口路径必须是站内相对路径')
      const url = new URL(path, target.origin)
      if (url.origin !== target.origin) throw new DataIssue('接口路径不能跳出被测站点')
      detail.path = safePath(step.path)
      const headers: Record<string, string> = {}
      for (const [name, value] of Object.entries(step.headers || {}))
        headers[name] = typeof value === 'string' ? render(value, values) : secret(value.secretRef)
      if (Object.values(headers).some(value => value.includes('\r') || value.includes('\n') || value.length > 2000))
        throw new DataIssue('接口请求头参数格式无效')
      const renderedBody = step.body === undefined ? undefined : renderBody(step.body, values)
      const body = renderedBody === undefined ? undefined : typeof renderedBody === 'string'
        ? renderedBody : JSON.stringify(renderedBody)
      if (body !== undefined && !Object.keys(headers).some(key => key.toLowerCase() === 'content-type'))
        headers['Content-Type'] = 'application/json'
      if (body !== undefined) detail.requestBodySha256 = digest(body)
      let response: Response | undefined
      let networkError: unknown
      for (let attempt = 0; attempt <= (step.retryCount || 0); attempt++) {
        try {
          response = await fetch(url, { method: step.method, headers, body, redirect: 'manual',
            signal: AbortSignal.timeout(step.timeoutMs || 10_000) })
          break
        } catch (error) { networkError = error }
      }
      if (!response) throw networkError || new Error('接口请求失败')
      const received = await readResponse(response)
      detail.statusCode = response.status
      detail.responseBytes = received.bytes
      detail.responseTruncated = received.truncated
      detail.responseBodySha256 = digest(received.text)
      if (received.truncated) throw new DataIssue('响应体超过 1MB 限制，无法完整断言')
      let parsed: unknown
      try { parsed = received.text ? JSON.parse(received.text) : undefined } catch { parsed = undefined }
      for (const check of version.mapping.assertions.filter(item => item.stepIndex === index)) {
        const passed = !received.truncated && checkResponse(check, response, parsed)
        checks.push({ stepIndex: index, target: check.target, path: check.path,
          operator: check.operator, passed })
        if (!passed) throw new AssertionIssue(`第 ${index + 1} 步 ${check.target} 断言不符`)
      }
      if (step.extract) for (const [name, jsonPath] of Object.entries(step.extract)) {
        const extracted = jsonAt(parsed, jsonPath)
        if (extracted === undefined || extracted === null || typeof extracted === 'object')
          throw new DataIssue(`第 ${index + 1} 步提取变量 ${name} 失败`)
        values[name] = String(extracted)
      }
    } catch (error) {
      result = error instanceof DataIssue ? 'DATA_FAILURE'
        : error instanceof AssertionIssue ? 'ASSERTION_FAILURE' : 'ENVIRONMENT_FAILURE'
      failureReason = error instanceof DataIssue || error instanceof AssertionIssue
        ? error.message : `第 ${index + 1} 步网络请求失败或超时`
      failureStepIndex = index
      detail.error = failureReason
    } finally {
      detail.durationMs = Date.now() - stepStart
      steps.push(detail)
    }
    if (result !== 'PASS') break
  }
  const evidencePayload = { requestKey: randomUUID(), versionNo, result,
    evidence: { steps, assertions: checks, targetOrigin: target.origin } }
  const runPayload: { requestKey: string; versionNo: number; result: string; durationMs: number;
    apiEvidenceId?: number; failureStepIndex?: number; failureReason?: string } = {
    requestKey: randomUUID(), versionNo, result, durationMs: Date.now() - started,
    failureStepIndex, failureReason,
  }
  const localReport = async (storageStatus: 'RECORDED' | 'EVIDENCE_PENDING' | 'RUN_WRITE_PENDING',
    evidenceId?: number) => {
    try {
      return { reportPath: await writeLocalApiAutomationReport(reportRoot, { projectId, assetId,
        caseNo: version.mapping.caseNo, versionNo, requestKey: runPayload.requestKey, result,
        durationMs: runPayload.durationMs, failureStepIndex, failureReason, storageStatus,
        evidenceId, startedAt: started, steps, assertions: checks }) }
    } catch (error) {
      return { reportError: error instanceof Error ? error.message : String(error) }
    }
  }
  let evidenceId: number
  try {
    const saved = await callApi<{ id: number }>(binding, `${base}/api-evidence`, 'POST', evidencePayload)
    evidenceId = saved.id
  } catch (error) {
    const report = await localReport('EVIDENCE_PENDING')
    return { status: 'EVIDENCE_PENDING', assetId, result, failureReason,
      evidencePayload, runPayload, uploadError: error instanceof Error ? error.message : String(error), ...report }
  }
  runPayload.apiEvidenceId = evidenceId
  try {
    const run = await callApi(binding, `${base}/runs`, 'POST', runPayload)
    const report = await localReport('RECORDED', evidenceId)
    return { status: 'RECORDED', assetId, versionNo, result, failureReason, evidenceId, run, ...report }
  } catch (error) {
    const report = await localReport('RUN_WRITE_PENDING', evidenceId)
    return { status: 'RUN_WRITE_PENDING', assetId, result, failureReason, evidenceId,
      runPayload, writeError: error instanceof Error ? error.message : String(error), ...report }
  }
}

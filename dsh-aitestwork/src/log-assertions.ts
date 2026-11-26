import { createHash } from 'node:crypto'
import type { AdminLogRuntime } from './admin-logs.js'

export type LogRule = { id: string; stepIndex: number; serviceName?: string;
  purpose: 'REQUEST_ASSOCIATION' | 'BUSINESS_EVENT'; expected: string; timeoutMs?: number;
  correlation?: { target: 'JSON' | 'HEADER'; path: string } }
export type LogMapping = { steps: { type?: string; path?: string; waitForResponse?: { path: string } }[]; logAssertions?: LogRule[] }
export type Observation = { body?: unknown; headers: Record<string, string> }
type Source = { instanceId: string; serviceName: string; status: string; logfileAvailable: boolean }
type Proof = { instanceId: string; result: string; reason: string; startedAt: string; completedAt: string;
  checkedBytes: number; matchedLineSha256: string | null }
export type LogEvidence = { ruleId: string; stepIndex: number; purpose: LogRule['purpose']; expected: string;
  serviceName?: string; result: string; reason: string; correlationSha256?: string; instances: Proof[] }

export function validateLogMapping(mapping: LogMapping) {
  const rules = mapping.logAssertions
  if (rules === undefined) return
  if (!Array.isArray(rules) || rules.length > 10) throw new Error('INVALID_LOG_RULES')
  const ids = new Set<string>()
  for (const rule of rules) {
    if (!rule || Object.keys(rule).some(key => !['id', 'stepIndex', 'serviceName', 'purpose', 'expected', 'timeoutMs', 'correlation'].includes(key))
      || typeof rule.id !== 'string' || !/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(rule.id) || ids.has(rule.id)
      || !Number.isInteger(rule.stepIndex) || rule.stepIndex < 0 || rule.stepIndex >= mapping.steps.length
      || !['REQUEST_ASSOCIATION', 'BUSINESS_EVENT'].includes(rule.purpose)
      || typeof rule.expected !== 'string' || !rule.expected.trim() || rule.expected.length > 512 || /[\r\n]/.test(rule.expected)
      || (rule.serviceName !== undefined && (typeof rule.serviceName !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(rule.serviceName)))
      || (rule.timeoutMs !== undefined && (!Number.isInteger(rule.timeoutMs) || rule.timeoutMs < 0 || rule.timeoutMs > 60_000))) throw new Error('INVALID_LOG_RULE')
    const step = mapping.steps[rule.stepIndex]
    if (!step.path && (!step.waitForResponse || !['click', 'press'].includes(step.type || ''))) throw new Error('UI_LOG_RULE_REQUIRES_RESPONSE')
    if (rule.correlation && (Object.keys(rule.correlation).some(key => !['target', 'path'].includes(key))
      || !(rule.correlation.target === 'JSON' && rule.correlation.path?.length <= 256
        && /^\$(?:\.[A-Za-z_][A-Za-z0-9_]*|\[[0-9]{1,4}\])*$/.test(rule.correlation.path)
        || rule.correlation.target === 'HEADER' && ['x-trace-id', 'x-request-id'].includes(rule.correlation.path?.toLowerCase())))) throw new Error('INVALID_CORRELATION_RULE')
    ids.add(rule.id)
  }
}

/** Only exact normalized service/path-segment matches. Ambiguous services must be calibrated before action. */
export function selectLogSources(rows: Source[], path: string, serviceName?: string): Source[] {
  const normalize = (value: string) => value.toLowerCase().replace(/_/g, '-')
  const segments = path.split(/[/?#]/).filter(Boolean).map(normalize)
  const names = [...new Set(rows.filter(row => serviceName
    ? normalize(row.serviceName) === normalize(serviceName) : segments.includes(normalize(row.serviceName)))
    .map(row => row.serviceName))]
  if (names.length !== 1) throw new Error(names.length ? 'LOG_SERVICE_AMBIGUOUS' : 'LOG_SERVICE_NOT_FOUND')
  const selected = rows.filter(row => row.serviceName === names[0])
  if (selected.length > 8 || selected.some(row => row.status !== 'UP' || !row.logfileAvailable))
    throw new Error('LOG_SERVICE_NOT_FULLY_READABLE')
  return selected
}

export function responseCorrelation(observation: Observation, rule: LogRule): string {
  const at = (path: string): unknown => {
    if (!/^\$(?:\.[A-Za-z_][A-Za-z0-9_]*|\[[0-9]{1,4}\])*$/.test(path)) throw new Error('INVALID_CORRELATION_PATH')
    return (path.slice(1).match(/\.[A-Za-z_][A-Za-z0-9_]*|\[[0-9]{1,4}\]/g) || []).reduce<unknown>((value, part) => {
      if (!value || typeof value !== 'object') return undefined
      return (value as Record<string, unknown>)[part[0] === '.' ? part.slice(1) : part.slice(1, -1)]
    }, observation.body)
  }
  const header = (path: string) => Object.entries(observation.headers).find(([key]) => key.toLowerCase() === path.toLowerCase())?.[1]
  const candidates = rule.correlation
    ? [rule.correlation.target === 'HEADER' ? header(rule.correlation.path) : at(rule.correlation.path)]
    : [header('x-trace-id'), header('x-request-id'), at('$.traceId'), at('$.requestId')]
  const values = [...new Set(candidates.filter(value => typeof value === 'string' || typeof value === 'number').map(String))]
  if (values.length !== 1 || !/^[A-Za-z0-9_-]{4,128}$/.test(values[0]))
    throw new Error(values.length > 1 ? 'LOG_CORRELATION_AMBIGUOUS' : 'LOG_CORRELATION_MISSING')
  return values[0]
}

/** One run owns its checkpoints; never establish a replacement baseline after the business action. */
export class LogAssertionRun {
  readonly evidence: LogEvidence[] = []
  private prepared = new Map<string, { rule: LogRule; sources: Awaited<ReturnType<AdminLogRuntime['checkpoint']>>[]; serviceName: string }>()
  constructor(private readonly runtime: AdminLogRuntime | undefined, private readonly mapping: LogMapping) { validateLogMapping(mapping) }
  async beforeStep(stepIndex: number) {
    const rules = (this.mapping.logAssertions || []).filter(rule => rule.stepIndex === stepIndex)
    if (!rules.length) return
    try {
      if (!this.runtime) throw new Error('LOG_CONNECTION_REQUIRED')
      const rows = await this.runtime.instances()
      for (const rule of rules) {
        const path = this.mapping.steps[stepIndex].waitForResponse?.path || this.mapping.steps[stepIndex].path || ''
        const sources = selectLogSources(rows, path, rule.serviceName)
        const checkpoints: Awaited<ReturnType<AdminLogRuntime['checkpoint']>>[] = []
        this.prepared.set(rule.id, { rule, sources: checkpoints, serviceName: sources[0].serviceName })
        for (const source of sources) checkpoints.push(await this.runtime.checkpoint(source.instanceId))
      }
    } catch (error) {
      for (const rule of rules) this.record(rule, 'INCONCLUSIVE', error instanceof Error ? error.message : 'LOG_PRECHECK_FAILED')
      this.discard(stepIndex)
      throw new Error('LOG_PRECHECK_FAILED: 测试动作尚未执行，请先连接日志平台或校准服务映射')
    }
  }
  async afterStep(stepIndex: number, observation: Observation) {
    for (const rule of (this.mapping.logAssertions || []).filter(rule => rule.stepIndex === stepIndex)) {
      const prepared = this.prepared.get(rule.id)
      if (!prepared || !this.runtime) throw new Error('LOG_CHECKPOINT_MISSING')
      try {
        const current = selectLogSources(await this.runtime.instances(), '', prepared.serviceName)
        if (current.map(item => item.instanceId).sort().join(',') !== prepared.sources.map(item => item.instanceId).sort().join(',')) throw new Error('LOG_INSTANCE_SET_CHANGED')
        const correlation = responseCorrelation(observation, rule)
        const proofs = await Promise.all(prepared.sources.map(source =>
          this.runtime!.assertContains(source.checkpointId, correlation, rule.expected, rule.timeoutMs ?? 15_000)))
        const passed = proofs.some(proof => proof.result === 'PASS')
        const result = passed ? 'PASS' : proofs.some(proof => proof.result !== 'FAIL') ? 'INCONCLUSIVE' : 'FAIL'
        this.evidence.push({ ruleId: rule.id, stepIndex, purpose: rule.purpose, expected: rule.expected,
          serviceName: prepared.serviceName, result, reason: passed ? 'CORRELATED_NEW_LOG_MATCH' : result === 'FAIL'
            ? 'EXPECTED_EVENT_NOT_OBSERVED_WITHIN_WINDOW' : 'LOG_READ_INCOMPLETE',
          correlationSha256: createHash('sha256').update(correlation).digest('hex'),
          instances: proofs.map(({ instanceId, result, reason, startedAt, completedAt, checkedBytes, matchedLineSha256 }) =>
            ({ instanceId, result, reason, startedAt, completedAt, checkedBytes, matchedLineSha256 })) })
      } catch (error) {
        this.record(rule, 'INCONCLUSIVE', error instanceof Error && /^[A-Z0-9_]{1,100}$/.test(error.message) ? error.message : 'LOG_READ_FAILED')
      } finally { this.discardRule(rule.id) }
    }
    const failed = this.evidence.filter(item => item.stepIndex === stepIndex).find(item => item.result !== 'PASS')
    if (failed) throw new Error(`LOG_${failed.result}: ${failed.reason}`)
  }
  private record(rule: LogRule, result: string, reason: string) {
    this.evidence.push({ ruleId: rule.id, stepIndex: rule.stepIndex, purpose: rule.purpose,
      expected: rule.expected, result, reason, instances: [] })
  }
  private discardRule(id: string) {
    for (const source of this.prepared.get(id)?.sources || []) this.runtime?.discard(source.checkpointId)
    this.prepared.delete(id)
  }
  discard(stepIndex?: number) {
    for (const [id, prepared] of this.prepared) if (stepIndex === undefined || prepared.rule.stepIndex === stepIndex) this.discardRule(id)
  }
}

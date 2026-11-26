import { randomUUID } from 'node:crypto'
import { mkdir, readFile, rename, stat, writeFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'
import type { Mapping } from './automation.js'

export type CheckKind = 'FIELD_PAIR' | 'RUN_ASSERTION'
export type Rule = { code: string; title: string; kind: CheckKind; applicability: 'UNKNOWN' | 'APPLICABLE' | 'NOT_APPLICABLE'; reason?: string }
export type Item = { id: string; ruleCode: string; subjectKey: string; page: string; label: string;
  sourceBasis: string; positiveSessionId?: number; negativeSessionId?: number }
export type LocalCoverageTask = { id: string; targetOrigin: string; rules: Rule[]; items: Item[];
  confirmedAt?: string; createdAt: string; updatedAt: string }

export const DEFAULT_UI_RULES: { code: string; title: string; kind: CheckKind }[] = [
  { code: 'UI_REQUIRED_FIELD', title: '业务必填条件逐项正反验证', kind: 'FIELD_PAIR' },
  { code: 'UI_EXPECTED_BEHAVIOR', title: '业务主流程和可观察预期', kind: 'RUN_ASSERTION' },
  { code: 'UI_ERROR_HANDLING', title: '异常路径与错误反馈', kind: 'RUN_ASSERTION' },
  { code: 'UI_STATE_TRANSITION', title: '状态迁移与结果可见性', kind: 'RUN_ASSERTION' },
  { code: 'UI_PERMISSION_BOUNDARY', title: '角色与权限边界', kind: 'RUN_ASSERTION' },
]

type Proof = { page: string; ruleCode: string; subjectKey: string; role: 'POSITIVE' | 'NEGATIVE' | 'RULE';
  fieldAction?: string; targetCleared?: boolean; submitAction?: string; errorState?: string;
  noSuccessState?: string; assertionState?: string }
type LocalRun = { result: string; status: string; sessionId: number; targetOrigin?: string;
  startedAt?: number; coverageProof?: Proof;
  evidence?: { screenshotPath?: string; tracePath?: string } }

export function coverageProof(mapping: Mapping): Proof | undefined {
  if (mapping.uiCoverage) {
    const proof = mapping.uiCoverage
    return { page: mapping.page, ruleCode: proof.ruleCode, subjectKey: proof.fieldKey, role: proof.role,
      fieldAction: mapping.steps[proof.fieldStepIndex]?.type,
      targetCleared: mapping.steps[proof.fieldStepIndex]?.data === '',
      submitAction: mapping.steps[proof.submitStepIndex]?.type,
      errorState: mapping.assertions[proof.errorAssertionIndex ?? -1]?.state || 'visible',
      noSuccessState: mapping.assertions[proof.noSuccessAssertionIndex ?? -1]?.state }
  }
  if (mapping.ruleCoverage) {
    const proof = mapping.ruleCoverage
    return { page: mapping.page, ruleCode: proof.ruleCode, subjectKey: proof.subjectKey,
      role: 'RULE', assertionState: mapping.assertions[proof.assertionIndex]?.state || 'visible' }
  }
  return undefined
}

export function assessLocalCoverage(task: LocalCoverageTask): { result: string; covered: number; required: number;
  rules: { code: string; status: string }[] } {
  const required = task.items.length
  const covered = task.items.filter(item => {
    const kind = task.rules.find(rule => rule.code === item.ruleCode)?.kind
    return item.positiveSessionId !== undefined && (kind === 'RUN_ASSERTION' || item.negativeSessionId !== undefined)
  }).length
  const rules = task.rules.map(rule => {
    const items = task.items.filter(item => item.ruleCode === rule.code)
    const status = rule.applicability === 'NOT_APPLICABLE' ? 'NOT_APPLICABLE'
      : rule.applicability === 'UNKNOWN' ? 'UNKNOWN'
      : items.length && items.every(item => item.positiveSessionId !== undefined
        && (rule.kind === 'RUN_ASSERTION' || item.negativeSessionId !== undefined)) ? 'COVERED' : 'PENDING'
    return { code: rule.code, status }
  })
  const result = !task.confirmedAt ? 'INVENTORY_UNCONFIRMED'
    : !task.rules.some(rule => rule.applicability === 'APPLICABLE') || !required
      || rules.some(rule => !['COVERED', 'NOT_APPLICABLE'].includes(rule.status)) ? 'INCOMPLETE' : 'PASS'
  return { result, covered, required, rules }
}

export class LocalCoverageStore {
  constructor(private readonly root = join(homedir(), '.dsh', 'aitestwork-local')) {}

  private path(id: string) {
    if (!/^[0-9a-f-]{36}$/i.test(id)) throw new Error('本地覆盖任务 ID 无效')
    return join(this.root, 'coverage', `${id}.json`)
  }

  async start(targetOrigin: string): Promise<LocalCoverageTask> {
    let url: URL
    try { url = new URL(targetOrigin) } catch { throw new Error('目标站点地址无效') }
    if (!['http:', 'https:'].includes(url.protocol) || url.origin !== targetOrigin || url.username || url.password)
      throw new Error('目标站点须为 HTTP/HTTPS 根地址')
    const now = new Date().toISOString()
    const task: LocalCoverageTask = { id: randomUUID(), targetOrigin, createdAt: now, updatedAt: now,
      rules: DEFAULT_UI_RULES.map(rule => ({ ...rule, applicability: 'UNKNOWN' })), items: [] }
    await this.write(task)
    return task
  }

  async get(id: string): Promise<LocalCoverageTask & { verdict: ReturnType<typeof assessLocalCoverage> }> {
    const task = JSON.parse(await readFile(this.path(id), 'utf8')) as LocalCoverageTask
    if (task.id !== id) throw new Error('本地覆盖任务文件不匹配')
    return { ...task, verdict: assessLocalCoverage(task) }
  }

  private async update(id: string, change: (task: LocalCoverageTask) => Promise<void> | void) {
    const lock = `${this.path(id)}.lock`
    for (let attempt = 0; attempt < 50; attempt++) {
      try { await mkdir(lock); break }
      catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'EEXIST' || attempt === 49) throw error
        await new Promise(resolve => setTimeout(resolve, 20))
      }
    }
    try {
      const task = await this.get(id)
      await change(task)
      task.updatedAt = new Date().toISOString()
      await this.write(task)
      return { ...task, verdict: assessLocalCoverage(task) }
    } finally {
      const { rmdir } = await import('node:fs/promises')
      await rmdir(lock).catch(() => {})
    }
  }

  private async write(task: LocalCoverageTask) {
    await mkdir(join(this.root, 'coverage'), { recursive: true, mode: 0o700 })
    const path = this.path(task.id)
    const temp = `${path}.${randomUUID()}.tmp`
    await writeFile(temp, JSON.stringify(task), { mode: 0o600 })
    await rename(temp, path)
  }

  async decide(id: string, code: string, applicability: Rule['applicability'], reason: string) {
    return this.update(id, task => {
      if (task.confirmedAt) throw new Error('规则范围已确认，不能修改')
      if (!['UNKNOWN', 'APPLICABLE', 'NOT_APPLICABLE'].includes(applicability) || !reason.trim())
        throw new Error('规则适用性和依据不能为空')
      const rule = task.rules.find(item => item.code === code)
      if (!rule) throw new Error('规则不存在')
      if (applicability === 'NOT_APPLICABLE' && task.items.some(item => item.ruleCode === code))
        throw new Error('已有场景的规则不能标为不适用')
      rule.applicability = applicability
      rule.reason = reason.slice(0, 1000)
    })
  }

  async addRule(id: string, code: string, title: string, kind: CheckKind) {
    return this.update(id, task => {
      if (task.confirmedAt) throw new Error('规则范围已确认，不能修改')
      if (!/^[A-Z][A-Z0-9_]{2,63}$/.test(code) || !title.trim() || !['FIELD_PAIR', 'RUN_ASSERTION'].includes(kind)
          || task.rules.some(rule => rule.code === code)) throw new Error('规则编号、名称或检查方式无效')
      task.rules.push({ code, title: title.slice(0, 255), kind, applicability: 'UNKNOWN' })
    })
  }

  async addItem(id: string, ruleCode: string, subjectKey: string, page: string, label: string, sourceBasis: string) {
    return this.update(id, task => {
      if (task.confirmedAt) throw new Error('清单已确认，不能修改')
      if (!task.rules.some(rule => rule.code === ruleCode && rule.applicability === 'APPLICABLE')
          || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(subjectKey)
          || !page.startsWith('/') || page.startsWith('//') || !label.trim() || !sourceBasis.trim()
          || task.items.some(item => item.ruleCode === ruleCode && item.subjectKey === subjectKey))
        throw new Error('覆盖项缺少已适用规则、唯一标识、页面、名称或来源')
      task.items.push({ id: randomUUID(), ruleCode, subjectKey, page, label, sourceBasis })
    })
  }

  async confirm(id: string) {
    return this.update(id, task => {
      if (task.rules.some(rule => rule.applicability === 'UNKNOWN'
          || rule.applicability === 'APPLICABLE' && !task.items.some(item => item.ruleCode === rule.code))
          || !task.items.length) throw new Error('规则或场景清单未核实完整')
      task.confirmedAt = new Date().toISOString()
    })
  }

  async attach(id: string, itemId: string, positiveSessionId: number, negativeSessionId?: number) {
    return this.update(id, async task => {
      if (!task.confirmedAt) throw new Error('清单未获本机 DSH 审批确认')
      const item = task.items.find(candidate => candidate.id === itemId)
      if (!item) throw new Error('覆盖项不存在')
      const kind = task.rules.find(rule => rule.code === item.ruleCode)?.kind
      if (kind === 'FIELD_PAIR' && (!negativeSessionId || negativeSessionId === positiveSessionId))
        throw new Error('必填项需要独立的正向与单项留空运行')
      if (kind === 'RUN_ASSERTION' && negativeSessionId) throw new Error('单运行规则不接受反向运行')
      await this.verifyRun(task, item, positiveSessionId, kind === 'FIELD_PAIR' ? 'POSITIVE' : 'RULE')
      if (kind === 'FIELD_PAIR') await this.verifyRun(task, item, negativeSessionId!, 'NEGATIVE')
      item.positiveSessionId = positiveSessionId
      item.negativeSessionId = negativeSessionId
    })
  }

  private async verifyRun(task: LocalCoverageTask, item: Item, sessionId: number, role: Proof['role']) {
    if (!Number.isSafeInteger(sessionId) || sessionId < 1) throw new Error('运行会话 ID 无效')
    const run = JSON.parse(await readFile(join(this.root, 'traces', String(sessionId), 'run.json'), 'utf8')) as LocalRun
    const proof = run.coverageProof
    if (run.sessionId !== sessionId || run.targetOrigin !== task.targetOrigin
        || !run.startedAt || run.startedAt < Date.parse(task.createdAt)
        || run.status !== 'LOCAL_ONLY' || run.result !== 'PASS'
        || !run.evidence?.tracePath || !run.evidence?.screenshotPath
        || !proof || proof.page !== item.page || proof.ruleCode !== item.ruleCode
        || proof.subjectKey !== item.subjectKey || proof.role !== role) throw new Error('本地运行缺少匹配的 PASS 与原始证据')
    if (!(await stat(run.evidence.tracePath).catch(() => null))?.isFile()
        || !(await stat(run.evidence.screenshotPath).catch(() => null))?.isFile())
      throw new Error('本地原始证据文件不存在')
    if (role === 'RULE' && proof.assertionState !== 'visible') throw new Error('规则运行缺少可观察断言')
    if (role !== 'RULE' && (proof.fieldAction !== 'fill' || proof.submitAction !== 'click'
        || proof.targetCleared !== (role === 'NEGATIVE'))) throw new Error('字段正反运行步骤不匹配')
    if (role === 'NEGATIVE' && (proof.errorState !== 'visible' || proof.noSuccessState !== 'hidden'))
      throw new Error('单项留空运行缺少错误提示和无成功结果断言')
  }
}

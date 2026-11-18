import { randomUUID } from 'node:crypto'
import { mkdir, readFile, readdir, rename, rmdir, stat, writeFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'
import type { Mapping } from './automation.js'

export type LocalTestPoint = { id: string; title: string; pointType: string; sourceBasis: string[] }
export type LocalCase = { id: string; title: string; moduleName: string; tags?: string[];
  precondition?: string; remarks?: string; steps: string; expectedResult: string;
  sourceTestPointRefs: string[]; sourceBasis: string[]; mapping?: Mapping }
export type LocalDesign = { id: string; versionNo: number; requirementText: string;
  testPoints: LocalTestPoint[]; cases: LocalCase[]; createdAt: string; updatedAt: string }
export type LocalDesignInput = { id?: string; expectedVersion?: number; requirementText: string;
  testPoints: LocalTestPoint[]; cases: (Omit<LocalCase, 'id'> & { id?: string })[] }

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const POINT_TYPES = new Set(['MAIN_FLOW', 'BRANCH', 'BOUNDARY', 'EXCEPTION', 'STATE', 'DATA', 'AUTH', 'CONCURRENCY', 'IDEMPOTENT'])
const CASE_DEPENDENCY = /(?:上一条|前一条|上一个|前一个|前面|之前|第\d+条).{0,12}(?:测试)?用例|(?:测试)?用例.{0,12}(?:执行|通过|完成).{0,5}(?:后|结果)/
const GENERIC_EXPECTATIONS = new Set(['成功', '操作成功', '页面正常', '显示正常', '功能正常', '符合预期', '结果正确', '校验通过', '保存成功'])

export const localDesignRules = {
  mode: 'LOCAL_ONLY',
  stages: ['理解需求与澄清阻断问题', '生成测试点和覆盖计划', '按测试点设计独立用例', '逐条质检并保存本地草稿'],
  columns: ['用例名称', '所属模块', '标签', '前置条件', '备注', '步骤描述', '预期结果'],
  sourcePolicy: '仅使用本次用户提供的需求、明确澄清及实际观察到的页面/API；没有 Hub 的 TOM、Wiki、正式用例和审核规则。未证实的界面词、状态与业务规则必须标成待确认。',
  independence: '每条用例都能单独执行；前置条件说明可独立准备的账号、数据与初始状态，或在本条步骤中完成准备。不能依赖上一条用例的结果。',
  steps: '从已确认入口按真实顺序写，每一步是一项可执行动作；预期结果使用同序号，写该步后可观察的页面、记录或外部结果。',
  coverage: '根据主流程、分支、等价类、边界、异常、状态、权限、并发和幂等设计必要场景；涉及表单时，依据需求和实际页面确认每个业务必填条件，设计其他输入有效而单项留空的独立校验场景，并核对错误提示和未生成成功记录。未确认的必填规则标待确认，未执行不得报告完整覆盖。一条完整流程可覆盖多个测试点，不能机械地每点一例。',
  storage: '本地草稿与执行映射仅保存在本机 DSH 数据目录；不自动写入 Hub，也不代表通过 Hub 审核。',
}

function requireText(value: unknown, label: string): string {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${label}不能为空`)
  return value.trim()
}

function requireSources(value: unknown, label: string): string[] {
  if (!Array.isArray(value) || value.length === 0 || value.some(item => typeof item !== 'string' || !item.trim())) {
    throw new Error(`${label}必须列出已知来源`)
  }
  return value.map(item => item.trim())
}

function numbered(value: string): string[] {
  return value.replace(/<br\s*\/?\s*>/gi, '\n').split(/\r?\n/).filter(line => line.trim())
    .map((line, index) => {
      const match = line.match(/^\s*(\d+)[.、)]\s*(.+)$/)
      if (!match || Number(match[1]) !== index + 1) throw new Error('步骤与预期必须从 1 连续编号')
      return match[2].trim()
    })
}

export function validateLocalMapping(mapping: Mapping): void {
  if (!mapping || typeof mapping !== 'object' || typeof mapping.page !== 'string' || !mapping.page.startsWith('/')
      || mapping.page.startsWith('//') || !Array.isArray(mapping.steps) || mapping.steps.length === 0
      || mapping.steps.length > 100 || !Array.isArray(mapping.assertions) || mapping.assertions.length === 0
      || mapping.assertions.length > 30) throw new Error('本地 UI 映射需要站内页面、1–100 个步骤和 1–30 个断言')
  for (const step of mapping.steps) {
    if (!['click', 'fill', 'press', 'select', 'check', 'uncheck', 'waitFor'].includes(step.type)
        || !step.locator || typeof step.locator !== 'object') throw new Error('本地 UI 映射步骤无效')
  }
  for (const assertion of mapping.assertions) {
    if (!assertion || typeof assertion !== 'object' || !(assertion.locator || assertion).by) {
      throw new Error('本地 UI 映射断言无效')
    }
  }
}

function validateDesign(input: LocalDesignInput): void {
  requireText(input.requirementText, '需求原文')
  if (!Array.isArray(input.testPoints) || input.testPoints.length === 0) throw new Error('请先生成测试点')
  if (!Array.isArray(input.cases)) throw new Error('cases 必须是数组；可先保存测试点，再补用例')
  const pointIds = new Set<string>()
  for (const point of input.testPoints) {
    if (!/^TP[1-9][0-9]*$/.test(point.id) || pointIds.has(point.id)) throw new Error('测试点 ID 必须唯一且形如 TP1')
    pointIds.add(point.id)
    requireText(point.title, '测试点名称')
    if (!POINT_TYPES.has(point.pointType)) throw new Error('测试点类型无效')
    requireSources(point.sourceBasis, '测试点来源')
  }
  const titles = new Set<string>()
  for (const item of input.cases) {
    const title = requireText(item.title, '用例名称')
    if (titles.has(title)) throw new Error('同一设计中用例名称不能重复')
    titles.add(title)
    if (!/^\/[^/\s]+(?:\/[^/\s]+)*$/.test(requireText(item.moduleName, '所属模块'))) {
      throw new Error('所属模块须写成 /页面/功能 的层级路径')
    }
    const steps = numbered(requireText(item.steps, '步骤描述'))
    const expected = numbered(requireText(item.expectedResult, '预期结果'))
    if (!steps.length || steps.length !== expected.length) throw new Error('每一步必须对应一项同序号预期')
    if (expected.some(value => GENERIC_EXPECTATIONS.has(value.replace(/[。！!，,\s]/g, '')))) {
      throw new Error('预期结果必须写可观察的具体结果')
    }
    if (CASE_DEPENDENCY.test(`${item.precondition || ''}\n${item.steps}\n${item.expectedResult}`)) {
      throw new Error('每条用例必须独立执行，不得依赖前一条用例')
    }
    if (!Array.isArray(item.sourceTestPointRefs) || !item.sourceTestPointRefs.length
        || item.sourceTestPointRefs.some(id => !pointIds.has(id))) throw new Error('用例必须引用本轮测试点')
    requireSources(item.sourceBasis, '用例来源')
    if (item.mapping) validateLocalMapping(item.mapping)
  }
}

export class LocalDesignStore {
  constructor(private readonly root = join(homedir(), '.dsh', 'aitesthub-local', 'designs')) {}

  private path(id: string): string {
    if (!UUID.test(id)) throw new Error('本地设计 ID 无效')
    return join(this.root, `${id}.json`)
  }

  async get(id: string): Promise<LocalDesign> {
    const design = JSON.parse(await readFile(this.path(id), 'utf8')) as LocalDesign
    if (design.id !== id) throw new Error('本地设计文件 ID 不匹配')
    return design
  }

  async list(): Promise<{ id: string; versionNo: number; requirementSummary: string;
    testPointCount: number; caseCount: number; updatedAt: string }[]> {
    const files = await readdir(this.root).catch(error => {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') return []
      throw error
    })
    const designs = await Promise.all(files.filter(name => UUID.test(name.replace(/\.json$/, '')) && name.endsWith('.json'))
      .map(name => this.get(name.slice(0, -5))))
    return designs.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).map(item => ({
      id: item.id, versionNo: item.versionNo, requirementSummary: item.requirementText.slice(0, 120),
      testPointCount: item.testPoints.length, caseCount: item.cases.length, updatedAt: item.updatedAt,
    }))
  }

  async save(input: LocalDesignInput): Promise<LocalDesign> {
    validateDesign(input)
    await mkdir(this.root, { recursive: true, mode: 0o700 })
    const id = input.id || randomUUID()
    this.path(id)
    const lock = join(this.root, `${id}.lock`)
    try { await mkdir(lock) } catch {
      const age = Date.now() - (await stat(lock)).mtimeMs
      if (age < 60_000) throw new Error('本地设计正在更新，请稍后重试')
      await rmdir(lock)
      await mkdir(lock)
    }
    try {
      const previous = input.id ? await this.get(id) : null
      if (previous && input.expectedVersion !== previous.versionNo) throw new Error('本地设计版本已变化，请重新读取后更新')
      if (!previous && input.expectedVersion !== undefined) throw new Error('新建本地设计不需要 expectedVersion')
      const oldCaseIds = new Set(previous?.cases.map(item => item.id) ?? [])
      const caseIds = new Set<string>()
      const cases = input.cases.map(item => {
        const caseId = item.id || randomUUID()
        if (!UUID.test(caseId) || caseIds.has(caseId) || (item.id && !oldCaseIds.has(caseId))) {
          throw new Error('用例 ID 无效、重复或不属于当前设计')
        }
        caseIds.add(caseId)
        const prior = previous?.cases.find(record => record.id === caseId)
        const semanticChanged = prior && ['title', 'moduleName', 'precondition', 'steps', 'expectedResult']
          .some(key => (prior as unknown as Record<string, unknown>)[key] !== (item as unknown as Record<string, unknown>)[key])
        const mapping = semanticChanged ? undefined : item.mapping
        return { ...item, id: caseId, mapping: mapping ? { ...mapping, caseNo: `LOCAL-${caseId.slice(0, 8)}` } : undefined }
      })
      const now = new Date().toISOString()
      const design: LocalDesign = { id, versionNo: (previous?.versionNo ?? 0) + 1,
        requirementText: input.requirementText, testPoints: input.testPoints, cases,
        createdAt: previous?.createdAt ?? now, updatedAt: now }
      const path = this.path(id)
      const temporary = `${path}.${randomUUID()}.tmp`
      await writeFile(temporary, JSON.stringify(design), { mode: 0o600, flag: 'wx' })
      await rename(temporary, path)
      return design
    } finally { await rmdir(lock).catch(() => undefined) }
  }

  async setMapping(designId: string, caseId: string, expectedVersion: number, mapping: Mapping): Promise<LocalDesign> {
    validateLocalMapping(mapping)
    const design = await this.get(designId)
    if (!design.cases.some(item => item.id === caseId)) throw new Error('本地设计中没有该用例 ID')
    return this.save({ id: designId, expectedVersion, requirementText: design.requirementText,
      testPoints: design.testPoints, cases: design.cases.map(item => item.id === caseId ? { ...item, mapping } : item) })
  }
}

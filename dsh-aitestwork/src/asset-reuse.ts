import { callApi, callUserApi, projectPath, type Binding } from './client.js'

type Asset = { id: number; caseKind: string; caseId: number; caseNo: string; assetType: string;
  status: string; currentVersion: number; latestVersion: number; reviewStatus?: string;
  caseStale?: boolean; caseMissing?: boolean }
type CaseSummary = { id: number; caseNo: string; caseTitle: string; moduleName: string }
type Mapping = { caseNo: string; page: string; steps: Record<string, unknown>[];
  assertions: Record<string, unknown>[]; logAssertions?: Record<string, unknown>[]; [key: string]: unknown }
type Version = { mapping: Mapping; status: string; versionNo: number }
export type MappingEdit = { operation: 'replace' | 'insert' | 'remove'; section: 'page' | 'steps' | 'assertions' | 'logAssertions';
  index?: number; value?: unknown }

function terms(value: string): string[] {
  const normalized = value.toLocaleLowerCase().normalize('NFKC')
  const found: string[] = []
  for (const token of normalized.match(/[\p{Script=Han}]+|[a-z0-9_-]+/gu) || []) {
    if (/^[\p{Script=Han}]+$/u.test(token)) {
      for (const length of [3, 2]) {
        for (let index = 0; index + length <= token.length; index++) found.push(token.slice(index, index + length))
      }
    } else if (token.length >= 2) found.push(token)
  }
  return found
}

function score(queryTerms: string[], item: CaseSummary, asset: Asset): number {
  const haystack = `${item.caseTitle} ${item.moduleName} ${item.caseNo} ${asset.id}`
    .toLocaleLowerCase().normalize('NFKC')
  return [...new Set(queryTerms)].reduce((total, term) => total + (haystack.includes(term) ? term.length : 0), 0)
}

export async function findAutoAssetCandidates(binding: Binding, projectId: number, change: string) {
  if (typeof change !== 'string' || !change.trim() || change.length > 500) {
    throw new Error('请提供不超过 500 字的本次需求变化')
  }
  const path = projectPath(projectId)
  const [assets, drafts] = await Promise.all([
    callApi<Asset[]>(binding, `${path}/automation-assets`),
    callUserApi<CaseSummary[]>(binding, `/api/aitestwork/dsh/user/projects/${projectId}/drafts`),
  ])
  const queryTerms = terms(change)
  const frequencies = new Map<string, number>()
  for (const term of queryTerms) frequencies.set(term, (frequencies.get(term) || 0) + 1)
  const searchTerms = [...frequencies].sort((a, b) => b[1] - a[1] || b[0].length - a[0].length)
    .slice(0, 4).map(([term]) => term)
  const formalResults = assets.some(asset => asset.caseKind === 'FORMAL')
    ? await Promise.all(searchTerms.map(async term => {
      const params = new URLSearchParams({ query: term, limit: '50' })
      return callApi<CaseSummary[]>(binding, `${path}/cases?${params}`)
    })) : []
  const cases = new Map<string, CaseSummary>()
  for (const item of drafts) cases.set(`DRAFT:${item.id}`, item)
  for (const result of formalResults) for (const item of result) cases.set(`FORMAL:${item.id}`, item)
  const candidates = assets.filter(asset => asset.assetType === 'UI')
    .map(asset => ({ asset, item: cases.get(`${asset.caseKind}:${asset.caseId}`) }))
    .filter((entry): entry is { asset: Asset; item: CaseSummary } => !!entry.item)
    .map(({ asset, item }) => ({
      assetId: asset.id, caseKind: asset.caseKind, caseId: asset.caseId, caseNo: asset.caseNo,
      caseTitle: item.caseTitle, moduleName: item.moduleName, status: asset.status,
      currentVersion: asset.currentVersion, latestVersion: asset.latestVersion,
      reviewStatus: asset.reviewStatus, caseStale: !!asset.caseStale, caseMissing: !!asset.caseMissing,
      matchScore: score(queryTerms, item, asset),
    }))
    .filter(item => item.matchScore > 0)
    .sort((a, b) => b.matchScore - a.matchScore || b.assetId - a.assetId)
    .slice(0, 12)
  return {
    change, scannedAssetCount: assets.length, scannedDraftCount: drafts.length,
    assetListMayBeTruncated: assets.length >= 200,
    formalSearchMayBeTruncated: formalResults.some(result => result.length >= 50),
    candidates,
    nextAction: candidates.length
      ? '先读取候选资产的完整映射和归属用例，比较需求差异；同一用例修订原资产版本，新用例复制相关映射后绑定新用例并验证。业务断言变化不能作为定位自愈。'
      : '未在本次可见候选中命中；检查项目、权限与检索范围，不要把本结果当作全库不存在。',
  }
}

export function applyMappingEdits(source: Mapping, edits: MappingEdit[], targetCaseNo: string): Mapping {
  if (!Array.isArray(edits) || edits.length < 1 || edits.length > 20) {
    throw new Error('请给出 1–20 项明确的映射差异')
  }
  if (typeof targetCaseNo !== 'string' || !targetCaseNo.trim()) throw new Error('目标用例编号不能为空')
  const mapping = structuredClone(source)
  if (!Array.isArray(mapping.steps) || !Array.isArray(mapping.assertions)) throw new Error('原映射缺少步骤或断言')
  for (const edit of edits) {
    if (!edit || !['replace', 'insert', 'remove'].includes(edit.operation)
        || !['page', 'steps', 'assertions', 'logAssertions'].includes(edit.section)) throw new Error('映射差异操作无效')
    if (edit.section === 'page') {
      if (edit.operation !== 'replace' || edit.index !== undefined
          || typeof edit.value !== 'string' || !edit.value.startsWith('/')) throw new Error('页面路径只能替换为站内路径')
      mapping.page = edit.value
      continue
    }
    if (edit.section === 'logAssertions' && !mapping.logAssertions) mapping.logAssertions = []
    const entries = mapping[edit.section]!
    if (!Number.isSafeInteger(edit.index) || edit.index! < 0
        || edit.index! > (edit.operation === 'insert' ? entries.length : entries.length - 1)) {
      throw new Error(`${edit.section} 的差异序号无效`)
    }
    if (edit.operation === 'remove') entries.splice(edit.index!, 1)
    else {
      if (!edit.value || typeof edit.value !== 'object' || Array.isArray(edit.value)) {
        throw new Error(`${edit.section} 的差异值须为完整步骤或断言对象`)
      }
      if (edit.operation === 'insert') entries.splice(edit.index!, 0, structuredClone(edit.value) as Record<string, unknown>)
      else entries[edit.index!] = structuredClone(edit.value) as Record<string, unknown>
    }
  }
  if (!mapping.steps.length || !mapping.assertions.length) throw new Error('修订后须保留步骤与断言')
  mapping.caseNo = targetCaseNo.trim()
  return mapping
}

export async function copyUiAssetVersion(binding: Binding, input: {
  projectId: number; sourceAssetId: number; sourceVersionNo: number; mode: 'REVISE' | 'NEW_CASE';
  requestKey: string; changeReason: string; edits: MappingEdit[]; expectedVersion?: number;
  targetCaseKind?: 'DRAFT' | 'FORMAL'; targetCaseId?: number; targetCaseNo?: string; workSessionId?: number
}) {
  const path = projectPath(input.projectId)
  if (!Number.isSafeInteger(input.sourceAssetId) || input.sourceAssetId < 1
      || !Number.isSafeInteger(input.sourceVersionNo) || input.sourceVersionNo < 1) {
    throw new Error('来源资产 ID 和版本号必须是正整数')
  }
  if (!input.requestKey?.trim() || !input.changeReason?.trim() || input.changeReason.trim().length < 10) {
    throw new Error('请提供稳定的幂等键和至少 10 字的修订依据')
  }
  const [asset, version] = await Promise.all([
    callApi<Asset>(binding, `${path}/automation-assets/${input.sourceAssetId}`),
    callApi<Version>(binding, `${path}/automation-assets/${input.sourceAssetId}/versions/${input.sourceVersionNo}`),
  ])
  if (asset.assetType !== 'UI') throw new Error('只能复制 UI 自动化映射')
  if (version.status !== 'VERIFIED') throw new Error('来源版本尚未验证，不能作为复用基线')
  if (input.mode === 'REVISE') {
    if (asset.currentVersion !== input.sourceVersionNo || input.expectedVersion !== asset.currentVersion) {
      throw new Error('来源版本已变化，请重新读取当前有效映射')
    }
    const mapping = applyMappingEdits(version.mapping, input.edits, asset.caseNo)
    const saved = await callApi(binding, `${path}/automation-assets/${asset.id}/versions`, 'POST', {
      requestKey: input.requestKey, expectedVersion: input.expectedVersion,
      changeReason: input.changeReason, mapping,
    })
    return { mode: input.mode, sourceAssetId: asset.id, sourceVersionNo: version.versionNo,
      changedSections: input.edits.map(edit => `${edit.operation}:${edit.section}:${edit.index ?? ''}`), saved }
  }
  if (input.mode !== 'NEW_CASE' || !['DRAFT', 'FORMAL'].includes(input.targetCaseKind || '')
      || !Number.isSafeInteger(input.targetCaseId) || input.targetCaseId! < 1
      || !Number.isSafeInteger(input.workSessionId) || input.workSessionId! < 1
      || !input.targetCaseNo?.trim()) throw new Error('新用例需提供归属用例、编号和当前测试任务 ID')
  if (input.targetCaseKind === asset.caseKind && input.targetCaseId === asset.caseId) {
    throw new Error('目标就是原用例，请使用 REVISE 模式')
  }
  const mapping = applyMappingEdits(version.mapping, input.edits, input.targetCaseNo)
  const saved = await callApi(binding, `${path}/automation-assets`, 'POST', {
    workSessionId: input.workSessionId, requestKey: input.requestKey,
    caseKind: input.targetCaseKind, caseId: input.targetCaseId, assetType: 'UI', mapping,
  })
  return { mode: input.mode, sourceAssetId: asset.id, sourceVersionNo: version.versionNo,
    changedSections: input.edits.map(edit => `${edit.operation}:${edit.section}:${edit.index ?? ''}`), saved }
}

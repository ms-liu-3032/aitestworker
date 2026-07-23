import { useState, useEffect, useCallback, useMemo } from 'react'
import { useParams, Link } from 'react-router-dom'
import {
  listBusinessPacks,
  listBusinessPackItems,
  generateBusinessPacks,
  activateBusinessPack,
  deactivateBusinessPack,
  archiveBusinessPack,
  deleteBusinessPackItem,
  listBusinessPackSnapshots,
  listBusinessPackRelations,
  createBusinessPackRelation,
  listRuleBindings,
  listScanBindings,
  listTomBindings,
  listConsumptionLogs,
  listBusinessPackRefreshDiagnostics,
  getAvailableTransitions,
  inferBusinessPackRelations,
  type BusinessPack,
  type BusinessPackItem,
  type BusinessPackSnapshot,
  type BusinessPackRelation,
  type RuleBinding,
  type ScanBinding,
  type TomBinding,
  type ConsumptionLog,
  type BusinessPackRefreshDiagnostic
} from '../../services/api'
import { displayLabel, statusLabel as uiStatusLabel } from '../../utils/displayLabels'

const STATUS_COLORS: Record<string, string> = {
  DRAFT: 'bg-yellow-100 text-yellow-800',
  ACTIVE: 'bg-green-100 text-green-800',
  INACTIVE: 'bg-gray-100 text-gray-600',
  ARCHIVED: 'bg-red-100 text-red-700'
}

const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿', ACTIVE: '生效', INACTIVE: '停用', ARCHIVED: '归档'
}

const ITEM_TYPE_LABELS: Record<string, string> = {
  TOM: '测试对象', PAGE: '页面', FIELD: '字段', ACTION: '操作',
  FLOW: '流程', STATE: '状态', ASSERTION: '断言', TERM: '术语', RULE: '规则'
}

const RELATION_TYPE_LABELS: Record<string, string> = {
  DEPENDS_ON: '依赖', CONTAINS: '包含', LINKED: '联动', SUPPLEMENTS: '补充'
}

const SOURCE_TYPE_LABELS: Record<string, string> = {
  TOM: 'TOM',
  PAGE_SCAN: '页面画像',
  TRACE_SUMMARY: '轨迹摘要',
  TRACE_PATTERN: '步骤模板',
  TRACE_CORRECTION: '步骤模板',
  MANUAL_IMPORT: '资料导入',
  AUTO_GENERATED: '自动生成'
}

type DetailTab = 'overview' | 'items' | 'snapshots' | 'relations' | 'bindings' | 'consumption' | 'diagnostics'

type SourceLink = {
  label: string
  to: string
}

function sourceTypeLabel(value?: string | null) {
  if (!value) return '未标记来源'
  return SOURCE_TYPE_LABELS[value] || value
}

function formatPercent(value?: number | null) {
  if (value === null || value === undefined) return '-'
  return `${(value * 100).toFixed(0)}%`
}

function formatDateTime(value?: string | null) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN')
}

function sourceLinkForItem(projectId: number, item: BusinessPackItem): SourceLink | null {
  if (!item.sourceRefId && !item.sourceType) return null
  switch (item.sourceType) {
    case 'TOM':
      return { label: '查看 TOM', to: `/projects/${projectId}/mini-tom?tomId=${item.sourceRefId || ''}` }
    case 'PAGE_SCAN':
      return { label: '查看页面画像', to: `/admin/scan?projectId=${projectId}&profileId=${item.sourceRefId || ''}` }
    case 'TRACE_CORRECTION':
      return { label: '查看步骤模板', to: `/projects/${projectId}/trace?candidateId=${item.sourceRefId || ''}` }
    case 'TRACE_SUMMARY':
      return { label: '查看轨迹摘要', to: `/projects/${projectId}/trace?summaryId=${item.sourceRefId || ''}` }
    default:
      return null
  }
}

function latestRefreshStatus(diagnostics: BusinessPackRefreshDiagnostic[]) {
  if (diagnostics.length === 0) {
    return {
      label: '未记录',
      className: 'bg-gray-100 text-gray-600',
      description: '暂无自动刷新诊断记录',
    }
  }
  const latest = diagnostics[0]
  if (latest.status === 'SUCCESS') {
    return {
      label: '正常',
      className: 'bg-emerald-50 text-emerald-700',
      description: `最近刷新生成 ${latest.generatedPackCount} 个包，推断 ${latest.inferredRelationCount} 个关系`,
    }
  }
  return {
    label: '失败',
    className: 'bg-red-50 text-red-700',
    description: latest.errorMessage || '最近一次自动刷新失败',
  }
}

export default function BusinessPacks() {
  const { projectId } = useParams<{ projectId: string }>()
  const pid = Number(projectId)

  const [packs, setPacks] = useState<BusinessPack[]>([])
  const [selectedPack, setSelectedPack] = useState<BusinessPack | null>(null)
  const [items, setItems] = useState<BusinessPackItem[]>([])
  const [snapshots, setSnapshots] = useState<BusinessPackSnapshot[]>([])
  const [relations, setRelations] = useState<BusinessPackRelation[]>([])
  const [ruleBindings, setRuleBindings] = useState<RuleBinding[]>([])
  const [scanBindings, setScanBindings] = useState<ScanBinding[]>([])
  const [tomBindings, setTomBindings] = useState<TomBinding[]>([])
  const [consumptionLogs, setConsumptionLogs] = useState<ConsumptionLog[]>([])
  const [refreshDiagnostics, setRefreshDiagnostics] = useState<BusinessPackRefreshDiagnostic[]>([])
  const [transitions, setTransitions] = useState<string[]>([])
  const [loading, setLoading] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [inferring, setInferring] = useState(false)
  const [filter, setFilter] = useState<string>('')
  const [notice, setNotice] = useState<string | null>(null)
  const [detailTab, setDetailTab] = useState<DetailTab>('items')
  const [showRelationModal, setShowRelationModal] = useState(false)
  const [relationTarget, setRelationTarget] = useState<number>(0)
  const [relationType, setRelationType] = useState<string>('LINKED')

  const loadPacks = useCallback(async () => {
    setLoading(true)
    try {
      const [packData, diagnosticsData] = await Promise.all([
        listBusinessPacks(pid, filter || undefined),
        listBusinessPackRefreshDiagnostics(pid).catch(() => []),
      ])
      setPacks(packData)
      setRefreshDiagnostics(diagnosticsData)
    } finally {
      setLoading(false)
    }
  }, [pid, filter])

  useEffect(() => { loadPacks() }, [loadPacks])

  const loadPackDetails = async (pack: BusinessPack) => {
    const [itemsData, snapshotsData, relationsData, ruleData, scanData, tomData, consumptionData, transitionsData] =
      await Promise.all([
        listBusinessPackItems(pid, pack.id),
        listBusinessPackSnapshots(pid, pack.id),
        listBusinessPackRelations(pid, pack.id),
        listRuleBindings(pid, pack.id),
        listScanBindings(pid, pack.id),
        listTomBindings(pid, pack.id),
        listConsumptionLogs(pid, pack.id),
        getAvailableTransitions(pid, pack.id),
      ])
    setItems(itemsData)
    setSnapshots(snapshotsData)
    setRelations(relationsData)
    setRuleBindings(ruleData)
    setScanBindings(scanData)
    setTomBindings(tomData)
    setConsumptionLogs(consumptionData)
    setTransitions(transitionsData)
  }

  const handleSelectPack = async (pack: BusinessPack) => {
    setSelectedPack(pack)
    setDetailTab('overview')
    await loadPackDetails(pack)
  }

  const handleGenerate = async () => {
    setGenerating(true)
    try {
      const data = await generateBusinessPacks(pid)
      setPacks(data)
      const diagnosticsData = await listBusinessPackRefreshDiagnostics(pid).catch(() => [])
      setRefreshDiagnostics(diagnosticsData)
      setNotice(`已生成 ${data.length} 个业务包 draft`)
      setTimeout(() => setNotice(null), 4000)
    } finally {
      setGenerating(false)
    }
  }

  const handleInferRelations = async () => {
    setInferring(true)
    try {
      const result = await inferBusinessPackRelations(pid)
      setNotice(`自动推断了 ${result.created} 个包间关系`)
      if (selectedPack) await loadPackDetails(selectedPack)
      setTimeout(() => setNotice(null), 4000)
    } finally {
      setInferring(false)
    }
  }

  const handleActivate = async (packId: number) => {
    await activateBusinessPack(pid, packId)
    await loadPacks()
    if (selectedPack?.id === packId) {
      const updated = await listBusinessPacks(pid, filter || undefined)
      const found = updated.find(p => p.id === packId)
      if (found) { setSelectedPack(found); await loadPackDetails(found) }
    }
    setNotice('业务包已激活')
    setTimeout(() => setNotice(null), 3000)
  }

  const handleDeactivate = async (packId: number) => {
    await deactivateBusinessPack(pid, packId)
    await loadPacks()
    if (selectedPack?.id === packId) {
      const updated = await listBusinessPacks(pid, filter || undefined)
      const found = updated.find(p => p.id === packId)
      if (found) { setSelectedPack(found); await loadPackDetails(found) }
    }
    setNotice('业务包已停用')
    setTimeout(() => setNotice(null), 3000)
  }

  const handleArchive = async (packId: number) => {
    await archiveBusinessPack(pid, packId)
    await loadPacks()
    if (selectedPack?.id === packId) setSelectedPack(null)
    setNotice('业务包已归档')
    setTimeout(() => setNotice(null), 3000)
  }

  const handleDeleteItem = async (itemId: number) => {
    if (!selectedPack) return
    await deleteBusinessPackItem(pid, selectedPack.id, itemId)
    await loadPackDetails(selectedPack)
    setNotice('条目已删除')
    setTimeout(() => setNotice(null), 3000)
  }

  const handleCreateRelation = async () => {
    if (!selectedPack || !relationTarget) return
    await createBusinessPackRelation(pid, selectedPack.id, {
      targetPackId: relationTarget, relationType
    })
    await loadPackDetails(selectedPack)
    setShowRelationModal(false)
    setNotice('关系已创建')
    setTimeout(() => setNotice(null), 3000)
  }

  const activeCount = packs.filter(p => p.status === 'ACTIVE').length
  const draftCount = packs.filter(p => p.status === 'DRAFT').length
  const totalItems = packs.reduce((sum, p) => sum + p.itemCount, 0)
  const sourceBreakdown = useMemo(() => {
    const counts = new Map<string, number>()
    for (const item of items) {
      const key = sourceTypeLabel(item.sourceType || item.itemType)
      counts.set(key, (counts.get(key) || 0) + 1)
    }
    return Array.from(counts.entries())
      .map(([label, count]) => ({ label, count }))
      .sort((a, b) => b.count - a.count)
  }, [items])
  const consumerBreakdown = useMemo(() => {
    const counts = new Map<string, number>()
    for (const log of consumptionLogs) {
      counts.set(log.consumerType, (counts.get(log.consumerType) || 0) + log.signalCount)
    }
    return Array.from(counts.entries())
      .map(([label, count]) => ({ label, count }))
      .sort((a, b) => b.count - a.count)
  }, [consumptionLogs])
  const coverageRows = [
    { label: 'TOM 绑定', value: tomBindings.length },
    { label: '页面绑定', value: scanBindings.length },
    { label: '规则绑定', value: ruleBindings.length },
    { label: '消费记录', value: consumptionLogs.length },
  ]
  const sourceTraceItems = items
    .filter(item => item.sourceType || item.sourceRefId)
    .slice(0, 8)
  const refreshStatus = latestRefreshStatus(refreshDiagnostics)
  const latestDiagnostic = refreshDiagnostics[0]

  return (
    <div className="min-h-screen min-w-0 overflow-x-hidden bg-gray-50">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {/* Header */}
        <div className="mb-8 flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
          <div className="min-w-0">
            <div className="mb-1 flex flex-wrap items-center gap-3">
              <Link to={`/projects/${pid}/overview`} className="text-gray-400 hover:text-gray-600 text-sm">返回概览</Link>
              <span className="text-gray-300">|</span>
              <h1 className="text-2xl font-semibold text-gray-900">业务包管理</h1>
            </div>
            <p className="text-sm text-gray-500 mt-1">从项目资产自动沉淀的业务能力集合，支持审核、合并、启用/停用</p>
          </div>
          <div className="flex flex-wrap items-center gap-3 lg:justify-end">
            <select value={filter} onChange={e => setFilter(e.target.value)}
              className="text-sm border border-gray-300 rounded-lg px-3 py-1.5 bg-white">
              <option value="">全部状态</option>
              <option value="DRAFT">草稿</option>
              <option value="ACTIVE">生效</option>
              <option value="INACTIVE">停用</option>
              <option value="ARCHIVED">归档</option>
            </select>
            <button onClick={handleInferRelations} disabled={inferring}
              className="min-h-10 shrink-0 px-4 py-2 bg-purple-600 text-white text-sm font-medium rounded-lg hover:bg-purple-700 disabled:opacity-50">
              {inferring ? '推断中...' : '推断关系'}
            </button>
            <button onClick={handleGenerate} disabled={generating}
              className="min-h-10 shrink-0 px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 disabled:opacity-50">
              {generating ? '生成中...' : '自动生成'}
            </button>
          </div>
        </div>

        {notice && (
          <div className="mb-4 px-4 py-3 bg-green-50 border border-green-200 rounded-lg text-sm text-green-700">{notice}</div>
        )}

        {/* Stats */}
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
          <div className="bg-white rounded-xl p-4 border border-gray-200">
            <div className="text-sm text-gray-500">业务包总数</div>
            <div className="text-2xl font-bold text-gray-900 mt-1">{packs.length}</div>
          </div>
          <div className="bg-white rounded-xl p-4 border border-gray-200">
            <div className="text-sm text-gray-500">生效中</div>
            <div className="text-2xl font-bold text-green-600 mt-1">{activeCount}</div>
          </div>
          <div className="bg-white rounded-xl p-4 border border-gray-200">
            <div className="text-sm text-gray-500">待审核</div>
            <div className="text-2xl font-bold text-yellow-600 mt-1">{draftCount}</div>
          </div>
          <div className="bg-white rounded-xl p-4 border border-gray-200">
            <div className="text-sm text-gray-500">总条目数</div>
            <div className="text-2xl font-bold text-blue-600 mt-1">{totalItems}</div>
          </div>
        </div>

        <div className="bg-white rounded-xl border border-gray-200 px-4 py-3 mb-6">
          <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-3">
            <div className="flex min-w-0 items-start gap-3">
              <span className={`text-xs px-2 py-0.5 rounded-full ${refreshStatus.className}`}>
                自动沉淀 {refreshStatus.label}
              </span>
              <div className="min-w-0">
                <div className="text-sm text-gray-900">{refreshStatus.description}</div>
                <div className="text-xs text-gray-500 mt-1">
                  最近诊断：{latestDiagnostic ? formatDateTime(latestDiagnostic.createdAt) : '暂无记录'}
                </div>
              </div>
            </div>
            {latestDiagnostic && (
              <div className="grid grid-cols-3 sm:grid-cols-6 gap-2 text-xs">
                <div className="rounded-lg bg-gray-50 px-3 py-2">
                  <div className="font-semibold text-gray-900">{latestDiagnostic.tomCount}</div>
                  <div className="text-gray-500 mt-0.5">TOM</div>
                </div>
                <div className="rounded-lg bg-gray-50 px-3 py-2">
                  <div className="font-semibold text-gray-900">{latestDiagnostic.pageProfileCount}</div>
                  <div className="text-gray-500 mt-0.5">页面</div>
                </div>
                <div className="rounded-lg bg-gray-50 px-3 py-2">
                  <div className="font-semibold text-gray-900">{latestDiagnostic.patternCount}</div>
                  <div className="text-gray-500 mt-0.5">模板</div>
                </div>
                <div className="rounded-lg bg-gray-50 px-3 py-2">
                  <div className="font-semibold text-gray-900">{latestDiagnostic.summaryCount}</div>
                  <div className="text-gray-500 mt-0.5">摘要</div>
                </div>
                <div className="rounded-lg bg-gray-50 px-3 py-2">
                  <div className="font-semibold text-gray-900">{latestDiagnostic.generatedPackCount}</div>
                  <div className="text-gray-500 mt-0.5">生成包</div>
                </div>
                <div className="rounded-lg bg-gray-50 px-3 py-2">
                  <div className="font-semibold text-gray-900">{latestDiagnostic.inferredRelationCount}</div>
                  <div className="text-gray-500 mt-0.5">关系</div>
                </div>
              </div>
            )}
          </div>
        </div>

        <div className="flex flex-col gap-6 xl:flex-row">
          {/* Left: Pack List */}
          <div className="w-full flex-shrink-0 xl:w-96">
            <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
              <div className="px-4 py-3 border-b border-gray-200 bg-gray-50">
                <span className="text-sm font-medium text-gray-700">业务包列表</span>
                <span className="text-xs text-gray-400 ml-2">{packs.length}</span>
              </div>
              {loading ? (
                <div className="p-8 text-center text-gray-400 text-sm">加载中...</div>
              ) : packs.length === 0 ? (
                <div className="p-8 text-center text-gray-400 text-sm">暂无业务包，点击"自动生成"从项目资产沉淀</div>
              ) : (
                <div className="divide-y divide-gray-100 max-h-[600px] overflow-y-auto">
                  {packs.map(pack => (
                    <div key={pack.id} onClick={() => handleSelectPack(pack)}
                      className={`px-4 py-3 cursor-pointer hover:bg-gray-50 transition-colors ${
                        selectedPack?.id === pack.id ? 'bg-blue-50 border-l-2 border-l-blue-500' : ''}`}>
                      <div className="mb-1 flex items-start justify-between gap-3">
                        <span className="min-w-0 truncate text-sm font-medium text-gray-900">{pack.packName}</span>
                        <span className={`shrink-0 text-xs px-2 py-0.5 rounded-full ${STATUS_COLORS[pack.status] || 'bg-gray-100'}`}>
                          {STATUS_LABELS[pack.status] || uiStatusLabel(pack.status)}
                        </span>
                      </div>
                      <div className="flex flex-wrap items-center gap-3 text-xs text-gray-500">
                        <span>{pack.itemCount} 条目</span>
                        {pack.confidenceAvg && <span>置信度 {(pack.confidenceAvg * 100).toFixed(0)}%</span>}
                        {pack.businessDomain && <span className="px-1.5 py-0.5 bg-gray-100 rounded text-gray-600">{pack.businessDomain}</span>}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>

          {/* Right: Pack Detail */}
          <div className="flex-1 min-w-0">
            {selectedPack ? (
              <div className="bg-white rounded-xl border border-gray-200">
                {/* Pack Header */}
                <div className="px-6 py-4 border-b border-gray-200">
                  <div className="mb-3 flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                    <div className="min-w-0">
                      <h2 className="break-words text-lg font-semibold text-gray-900">{selectedPack.packName}</h2>
                      <div className="mt-1 flex flex-wrap items-center gap-3 text-sm text-gray-500">
                        <span className={`px-2 py-0.5 rounded-full text-xs ${STATUS_COLORS[selectedPack.status] || 'bg-gray-100'}`}>
                          {STATUS_LABELS[selectedPack.status] || uiStatusLabel(selectedPack.status)}
                        </span>
                        <span>v{selectedPack.version}</span>
                        {selectedPack.businessDomain && <span>业务域：{selectedPack.businessDomain}</span>}
                        <span>{selectedPack.itemCount} 条目</span>
                      </div>
                    </div>
                    <div className="flex flex-wrap items-center gap-2 lg:justify-end">
                      {transitions.includes('ACTIVE') && (
                        <button onClick={() => handleActivate(selectedPack.id)}
                          className="px-3 py-1.5 bg-green-600 text-white text-sm rounded-lg hover:bg-green-700">激活</button>
                      )}
                      {transitions.includes('INACTIVE') && (
                        <button onClick={() => handleDeactivate(selectedPack.id)}
                          className="px-3 py-1.5 bg-gray-500 text-white text-sm rounded-lg hover:bg-gray-600">停用</button>
                      )}
                      {transitions.includes('ARCHIVED') && (
                        <button onClick={() => handleArchive(selectedPack.id)}
                          className="px-3 py-1.5 bg-red-50 text-red-600 text-sm rounded-lg hover:bg-red-100">归档</button>
                      )}
                    </div>
                  </div>
                  {selectedPack.description && <p className="break-words text-sm text-gray-600">{selectedPack.description}</p>}
                  {selectedPack.builtAt && (
                    <p className="text-xs text-gray-400 mt-2">自动生成于 {new Date(selectedPack.builtAt).toLocaleString()}</p>
                  )}
                </div>

                {/* Tabs */}
                <div className="px-6 border-b border-gray-200">
                  <div className="flex gap-6 -mb-px overflow-x-auto">
                    {(['overview', 'items', 'snapshots', 'relations', 'bindings', 'consumption', 'diagnostics'] as DetailTab[]).map(tab => (
                    <button key={tab} onClick={() => setDetailTab(tab)}
                        className={`shrink-0 py-3 text-sm font-medium border-b-2 transition-colors ${
                          detailTab === tab ? 'border-blue-500 text-blue-600' : 'border-transparent text-gray-500 hover:text-gray-700'}`}>
                        {tab === 'overview' ? '总览' : tab === 'items' ? '条目' : tab === 'snapshots' ? '快照' : tab === 'relations' ? '关系' : tab === 'bindings' ? '绑定' : tab === 'consumption' ? '消费记录' : '诊断'}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Tab Content */}
                <div className="px-6 py-4">
                  {detailTab === 'overview' && (
                    <div className="space-y-5">
                      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
                        <div className="rounded-lg border border-gray-200 p-4">
                          <div className="text-xs font-medium text-gray-500 mb-3">来源构成</div>
                          {sourceBreakdown.length === 0 ? (
                            <div className="text-sm text-gray-400">暂无来源条目</div>
                          ) : (
                            <div className="space-y-3">
                              {sourceBreakdown.slice(0, 5).map(source => (
                                <div key={source.label}>
	                                  <div className="mb-1 flex items-start justify-between gap-2 text-xs">
	                                    <span className="min-w-0 break-words text-gray-600">{source.label}</span>
	                                    <span className="shrink-0 font-medium text-gray-900">{source.count}</span>
                                  </div>
                                  <div className="h-1.5 rounded-full bg-gray-100 overflow-hidden">
                                    <div
                                      className="h-full rounded-full bg-gray-900"
                                      style={{ width: `${Math.max(8, (source.count / Math.max(1, items.length)) * 100)}%` }}
                                    />
                                  </div>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>

                        <div className="rounded-lg border border-gray-200 p-4">
                          <div className="text-xs font-medium text-gray-500 mb-3">绑定覆盖</div>
	                          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                            {coverageRows.map(row => (
                              <div key={row.label} className="rounded-lg bg-gray-50 px-3 py-3">
                                <div className="text-lg font-semibold text-gray-900">{row.value}</div>
                                <div className="text-xs text-gray-500 mt-1">{row.label}</div>
                              </div>
                            ))}
                          </div>
                        </div>

                        <div className="rounded-lg border border-gray-200 p-4">
                          <div className="text-xs font-medium text-gray-500 mb-3">主链路消费</div>
                          {consumerBreakdown.length === 0 ? (
                            <div className="text-sm text-gray-400">暂无消费记录</div>
                          ) : (
                            <div className="space-y-2">
                              {consumerBreakdown.slice(0, 5).map(consumer => (
	                                <div key={consumer.label} className="flex items-start justify-between gap-2 rounded-lg bg-gray-50 px-3 py-2">
	                                  <span className="min-w-0 break-words text-xs text-gray-600">{consumer.label}</span>
	                                  <span className="shrink-0 text-xs font-semibold text-gray-900">{consumer.count} 信号</span>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      </div>

	                      <div className="rounded-lg border border-gray-200 p-4">
	                        <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                          <div className="min-w-0">
                            <div className="text-sm font-semibold text-gray-900">关系图谱</div>
                            <div className="text-xs text-gray-500 mt-1">展示当前业务包与其他业务包的依赖、包含、联动和补充关系。</div>
                          </div>
                          <button onClick={() => setShowRelationModal(true)}
                            className="h-8 shrink-0 px-3 rounded-lg border border-gray-300 bg-white text-xs font-medium text-gray-700 hover:bg-gray-50">
                            添加关系
                          </button>
                        </div>
                        {relations.length === 0 ? (
                          <div className="rounded-lg border border-dashed border-gray-300 px-4 py-8 text-center text-sm text-gray-400">
                            暂无关系。可点击“推断关系”自动识别跨业务联动，也可手工添加。
                          </div>
                        ) : (
                          <div className="flex flex-wrap items-center gap-3">
                            <div className="max-w-full rounded-lg border border-gray-900 bg-gray-900 px-4 py-3 text-white">
                              <div className="break-words text-sm font-semibold">{selectedPack.packName}</div>
                              <div className="text-xs text-gray-300 mt-1">当前业务包</div>
                            </div>
                            {relations.map(rel => (
                              <div key={rel.id} className="flex min-w-0 items-center gap-3">
                                <div className="text-xs text-gray-400">→</div>
                                <div className="min-w-0 rounded-lg border border-gray-200 bg-white px-4 py-3">
                                  <div className="flex items-center gap-2">
                                    <span className="text-xs px-2 py-0.5 bg-purple-50 text-purple-700 rounded-full">
                                      {RELATION_TYPE_LABELS[rel.relationType] || rel.relationType}
                                    </span>
                                    <span className="text-xs text-gray-500">{formatPercent(rel.confidence)}</span>
                                  </div>
                                  <div className="text-sm font-medium text-gray-900 mt-2">包 #{rel.targetPackId}</div>
                                  {rel.description && <div className="mt-1 break-words text-xs text-gray-500">{rel.description}</div>}
                                </div>
                              </div>
                            ))}
                          </div>
	                        )}
	                      </div>

	                      <div className="rounded-lg border border-gray-200 p-4">
	                        <div className="mb-3">
	                          <div className="text-sm font-semibold text-gray-900">来源追溯</div>
	                          <div className="text-xs text-gray-500 mt-1">展示业务包条目来自哪个 TOM、页面画像、步骤模板或轨迹摘要。</div>
	                        </div>
	                        {sourceTraceItems.length === 0 ? (
	                          <div className="rounded-lg border border-dashed border-gray-300 px-4 py-8 text-center text-sm text-gray-400">
	                            暂无可追溯来源
	                          </div>
	                        ) : (
	                          <div className="divide-y divide-gray-100">
	                            {sourceTraceItems.map(item => {
	                              const sourceLink = sourceLinkForItem(pid, item)
	                              return (
	                                <div key={item.id} className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 py-3">
	                                  <div className="min-w-0 flex-1">
	                                    <div className="flex flex-wrap items-center gap-2">
	                                      <span className="text-[11px] px-2 py-0.5 rounded-full bg-gray-100 text-gray-600">
	                                        {sourceTypeLabel(item.sourceType || item.itemType)}
	                                      </span>
	                                      <span className="min-w-0 truncate text-sm font-medium text-gray-900">{item.itemKey}</span>
	                                    </div>
	                                    {item.itemValue && <div className="mt-1 break-words text-xs text-gray-500 line-clamp-1">{item.itemValue}</div>}
	                                  </div>
	                                  {sourceLink ? (
	                                    <Link to={sourceLink.to} className="text-xs text-blue-600 hover:text-blue-700 flex-shrink-0">
	                                      {sourceLink.label}
	                                    </Link>
	                                  ) : (
	                                    <span className="text-xs text-gray-400 flex-shrink-0">无详情入口</span>
	                                  )}
	                                </div>
	                              )
	                            })}
	                          </div>
	                        )}
	                      </div>

	                      <div className="rounded-lg border border-gray-200 p-4">
                        <div className="text-sm font-semibold text-gray-900 mb-3">生命周期</div>
                        <div className="grid grid-cols-1 sm:grid-cols-4 gap-3 text-xs">
                          <div className="rounded-lg bg-gray-50 px-3 py-3">
                            <div className="text-gray-500">版本</div>
                            <div className="font-semibold text-gray-900 mt-1">v{selectedPack.version}</div>
                          </div>
                          <div className="rounded-lg bg-gray-50 px-3 py-3">
                            <div className="text-gray-500">平均置信度</div>
                            <div className="font-semibold text-gray-900 mt-1">{formatPercent(selectedPack.confidenceAvg)}</div>
                          </div>
                          <div className="rounded-lg bg-gray-50 px-3 py-3">
                            <div className="text-gray-500">生成时间</div>
                            <div className="font-semibold text-gray-900 mt-1">{formatDateTime(selectedPack.builtAt)}</div>
                          </div>
                          <div className="rounded-lg bg-gray-50 px-3 py-3">
                            <div className="text-gray-500">激活时间</div>
                            <div className="font-semibold text-gray-900 mt-1">{formatDateTime(selectedPack.activatedAt)}</div>
                          </div>
                        </div>
                      </div>
                    </div>
                  )}

                  {detailTab === 'items' && (
                    items.length === 0 ? <div className="text-center text-gray-400 text-sm py-8">暂无条目</div> :
                    <div className="space-y-2 max-h-[500px] overflow-y-auto">
	                      {items.map(item => {
	                        const sourceLink = sourceLinkForItem(pid, item)
	                        return (
	                          <div key={item.id} className="flex flex-col gap-2 p-3 bg-gray-50 rounded-lg sm:flex-row sm:items-start sm:justify-between">
	                            <div className="flex-1 min-w-0">
	                              <div className="mb-1 flex flex-wrap items-center gap-2">
	                                <span className="text-xs px-2 py-0.5 bg-blue-100 text-blue-700 rounded-full">{ITEM_TYPE_LABELS[item.itemType] || item.itemType}</span>
	                                <span className="text-sm font-medium text-gray-900 truncate">{item.itemKey}</span>
	                                <span className="text-xs text-gray-400">{(item.confidence * 100).toFixed(0)}%</span>
	                              </div>
	                              {item.itemValue && <p className="break-words text-xs text-gray-500 line-clamp-2">{item.itemValue}</p>}
	                              {item.sourceType && (
	                                <div className="flex flex-wrap items-center gap-2 mt-1 text-xs">
	                                  <span className="text-gray-400">来源：{sourceTypeLabel(item.sourceType)}</span>
	                                  {sourceLink && (
	                                    <Link to={sourceLink.to} className="text-blue-600 hover:text-blue-700">
	                                      {sourceLink.label}
	                                    </Link>
	                                  )}
	                                </div>
	                              )}
	                            </div>
	                            <button onClick={() => handleDeleteItem(item.id)} className="shrink-0 text-left text-xs text-gray-400 hover:text-red-500 sm:ml-2">删除</button>
	                          </div>
	                        )
	                      })}
                    </div>
                  )}

                  {detailTab === 'snapshots' && (
                    snapshots.length === 0 ? <div className="text-center text-gray-400 text-sm py-8">暂无快照</div> :
                    <div className="space-y-2 max-h-[500px] overflow-y-auto">
                      {snapshots.map(snap => (
                        <div key={snap.id} className="p-3 bg-gray-50 rounded-lg">
                          <div className="mb-1 flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between">
                            <span className="text-sm font-medium text-gray-900">快照 #{snap.snapshotNo}</span>
                            <span className="text-xs text-gray-400">{new Date(snap.createdAt).toLocaleString()}</span>
                          </div>
                          <p className="text-xs text-gray-500">{snap.changeSummary}</p>
                          <p className="text-xs text-gray-400 mt-1">{snap.itemCount} 条目 · {uiStatusLabel(snap.status)}</p>
                        </div>
                      ))}
                    </div>
                  )}

                  {detailTab === 'relations' && (
                    <div>
	                      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                        <span className="text-sm text-gray-500">{relations.length} 个关系</span>
                        <button onClick={() => setShowRelationModal(true)}
                          className="text-xs text-blue-600 hover:text-blue-700">+ 添加关系</button>
                      </div>
                      {relations.length === 0 ? <div className="text-center text-gray-400 text-sm py-8">暂无关系</div> :
                      <div className="space-y-2 max-h-[500px] overflow-y-auto">
                        {relations.map(rel => (
                          <div key={rel.id} className="p-3 bg-gray-50 rounded-lg">
                          <div className="mb-1 flex flex-wrap items-center gap-2">
                              <span className="text-xs px-2 py-0.5 bg-purple-100 text-purple-700 rounded-full">{RELATION_TYPE_LABELS[rel.relationType] || rel.relationType}</span>
                              <span className="text-sm text-gray-900">→ 包 #{rel.targetPackId}</span>
                              <span className="text-xs text-gray-400">{(rel.confidence * 100).toFixed(0)}%</span>
                            </div>
                            {rel.description && <p className="text-xs text-gray-500">{rel.description}</p>}
                            <p className="text-xs text-gray-400 mt-1">来源：{displayLabel(rel.sourceType, '未知来源')}</p>
                          </div>
                        ))}
                      </div>}
                    </div>
                  )}

                  {detailTab === 'bindings' && (
                    <div className="space-y-4">
                      <div>
                        <h4 className="text-sm font-medium text-gray-700 mb-2">规则绑定 ({ruleBindings.length})</h4>
                        {ruleBindings.length === 0 ? <p className="text-xs text-gray-400">暂无</p> :
                        <div className="space-y-1">{ruleBindings.map(b => (
                          <div key={b.id} className="text-xs p-2 bg-gray-50 rounded">{b.ruleType}：{b.ruleRef}</div>
                        ))}</div>}
                      </div>
                      <div>
                        <h4 className="text-sm font-medium text-gray-700 mb-2">扫描绑定 ({scanBindings.length})</h4>
                        {scanBindings.length === 0 ? <p className="text-xs text-gray-400">暂无</p> :
                        <div className="space-y-1">{scanBindings.map(b => (
                          <div key={b.id} className="text-xs p-2 bg-gray-50 rounded">{b.pageLabel}（{b.routePath}）</div>
                        ))}</div>}
                      </div>
                      <div>
                        <h4 className="text-sm font-medium text-gray-700 mb-2">TOM 绑定 ({tomBindings.length})</h4>
                        {tomBindings.length === 0 ? <p className="text-xs text-gray-400">暂无</p> :
                        <div className="space-y-1">{tomBindings.map(b => (
                          <div key={b.id} className="text-xs p-2 bg-gray-50 rounded">{b.tomName}（{b.tomType}）</div>
                        ))}</div>}
                      </div>
                    </div>
                  )}

	                  {detailTab === 'consumption' && (
	                    consumptionLogs.length === 0 ? <div className="text-center text-gray-400 text-sm py-8">暂无消费记录</div> :
	                    <div className="space-y-2 max-h-[500px] overflow-y-auto">
                      {consumptionLogs.map(log => (
                        <div key={log.id} className="p-3 bg-gray-50 rounded-lg">
                          <div className="mb-1 flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between">
                            <span className="text-sm font-medium text-gray-900">{log.consumerType}</span>
                            <span className="text-xs text-gray-400">{new Date(log.consumedAt).toLocaleString()}</span>
                          </div>
                          <p className="text-xs text-gray-500">{log.signalCount} 个信号 · {log.consumerRef || '-'}</p>
                        </div>
	                      ))}
	                    </div>
	                  )}

	                  {detailTab === 'diagnostics' && (
	                    refreshDiagnostics.length === 0 ? <div className="text-center text-gray-400 text-sm py-8">暂无自动刷新诊断</div> :
	                    <div className="space-y-2 max-h-[500px] overflow-y-auto">
	                      {refreshDiagnostics.map(row => (
	                        <div key={row.id} className="p-3 bg-gray-50 rounded-lg">
	                          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 mb-2">
	                            <div className="flex items-center gap-2">
	                              <span className={`text-xs px-2 py-0.5 rounded-full ${
	                                row.status === 'SUCCESS' ? 'bg-emerald-50 text-emerald-700' : 'bg-red-50 text-red-700'
	                              }`}>
	                                {row.status === 'SUCCESS' ? '成功' : '失败'}
	                              </span>
	                              <span className="text-sm font-medium text-gray-900">{formatDateTime(row.createdAt)}</span>
	                            </div>
	                            <span className="text-xs text-gray-500">
	                              生成 {row.generatedPackCount} 包 · 推断 {row.inferredRelationCount} 关系
	                            </span>
	                          </div>
	                          <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-xs">
	                            <div className="rounded bg-white px-2 py-1.5 border border-gray-100">TOM {row.tomCount}</div>
	                            <div className="rounded bg-white px-2 py-1.5 border border-gray-100">页面 {row.pageProfileCount}</div>
	                            <div className="rounded bg-white px-2 py-1.5 border border-gray-100">模板 {row.patternCount}</div>
	                            <div className="rounded bg-white px-2 py-1.5 border border-gray-100">摘要 {row.summaryCount}</div>
	                          </div>
	                          {row.errorMessage && <div className="mt-2 text-xs text-red-600">{row.errorMessage}</div>}
	                        </div>
	                      ))}
	                    </div>
	                  )}
	                </div>
              </div>
            ) : (
              <div className="bg-white rounded-xl border border-gray-200 p-12 text-center">
                <div className="text-gray-400 text-lg mb-2">选择业务包查看详情</div>
                <div className="text-gray-400 text-sm">业务包从项目资产（TOM、页面画像、轨迹摘要等）自动生成</div>
              </div>
            )}
          </div>
        </div>

        {/* Relation Modal */}
        {showRelationModal && (
          <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
            <div className="mx-4 w-full max-w-sm rounded-xl bg-white p-5 sm:p-6">
              <h3 className="text-lg font-semibold mb-4">添加包间关系</h3>
              <div className="space-y-3">
                <div>
                  <label className="text-sm text-gray-600">目标业务包</label>
                  <select value={relationTarget} onChange={e => setRelationTarget(Number(e.target.value))}
                    className="w-full mt-1 border border-gray-300 rounded-lg px-3 py-2 text-sm">
                    <option value={0}>选择目标包</option>
                    {packs.filter(p => p.id !== selectedPack?.id).map(p => (
                      <option key={p.id} value={p.id}>{p.packName}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="text-sm text-gray-600">关系类型</label>
                  <select value={relationType} onChange={e => setRelationType(e.target.value)}
                    className="w-full mt-1 border border-gray-300 rounded-lg px-3 py-2 text-sm">
                    <option value="LINKED">联动</option>
                    <option value="DEPENDS_ON">依赖</option>
                    <option value="CONTAINS">包含</option>
                    <option value="SUPPLEMENTS">补充</option>
                  </select>
                </div>
              </div>
              <div className="mt-6 flex flex-wrap justify-end gap-2">
                <button onClick={() => setShowRelationModal(false)} className="px-4 py-2 text-sm text-gray-600 hover:text-gray-800">取消</button>
                <button onClick={handleCreateRelation} disabled={!relationTarget}
                  className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50">创建</button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

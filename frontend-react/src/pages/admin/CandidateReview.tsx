import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useApp } from '../../context/AppContext';
import {
  batchConfirmCandidates,
  batchRejectCandidates,
  confirmCandidate,
  listCandidatesPage,
  listAllProjects,
  rejectCandidate,
  type Project,
} from '../../services/api';

type TabKey = 'all' | 'tom' | 'wiki_pack' | 'wiki_entry' | 'automation' | 'summary' | 'correction' | 'skill' | 'tool';

const tabs: { key: TabKey; label: string; type?: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'tom', label: 'Mini-TOM', type: 'tom' },
  { key: 'wiki_pack', label: 'Wiki 包', type: 'wiki_pack' },
  { key: 'wiki_entry', label: 'Wiki 条目', type: 'wiki_entry' },
  { key: 'automation', label: '自动化映射', type: 'automation' },
  { key: 'summary', label: '轨迹摘要', type: 'summary' },
  { key: 'correction', label: '修正候选', type: 'correction' },
  { key: 'skill', label: 'Skill', type: 'skill' },
  { key: 'tool', label: 'Tool', type: 'tool' },
];

type CandidateItem = {
  candidateType: string;
  id: number;
  projectId?: number;
  status?: string;
  createdAt?: string;
  description?: string;
  name?: string;
  overview?: string;
  skillName?: string;
  toolName?: string;
  fieldName?: string;
  suggestedValue?: string;
  summaryScope?: string;
  operationSteps?: string;
  body?: string;
  sourceType?: string;
  sourceId?: number;
  scope?: string;
  packId?: number;
  versionNo?: number;
};

const pendingStatuses = new Set(['CANDIDATE', 'DRAFT', 'PENDING']);

function canReviewHere(candidate: CandidateItem) {
  return candidate.candidateType !== 'automation' && candidate.candidateType !== 'automation_system';
}

function reviewDetailUrl(candidate: CandidateItem) {
  if (candidate.candidateType === 'automation' && candidate.projectId) {
    return `/projects/${candidate.projectId}/automation-assets?assetId=${candidate.id}`;
  }
  if (candidate.candidateType === 'automation_system' && candidate.projectId && candidate.sourceId) {
    return `/projects/${candidate.projectId}/automation-assets?assetId=${candidate.sourceId}`;
  }
  if (candidate.candidateType === 'wiki_pack') {
    return `/admin/asset-center?tab=catalog&assetType=wiki&packId=${candidate.id}`;
  }
  if (candidate.candidateType === 'wiki_entry' && candidate.packId) {
    return `/admin/asset-center?tab=catalog&assetType=wiki&packId=${candidate.packId}&entryId=${candidate.id}`;
  }
  if (candidate.candidateType === 'tom') return `/admin/asset-center?tab=tom&modelId=${candidate.id}`;
  return null;
}

function candidateKey(candidate: Pick<CandidateItem, 'candidateType' | 'id'>) {
  return `${candidate.candidateType}:${candidate.id}`;
}

export default function CandidateReview({ embedded = false }: { embedded?: boolean }) {
  const { showToast } = useApp();
  const [tab, setTab] = useState<TabKey>('all');
  const [statusFilter, setStatusFilter] = useState<'pending' | 'rejected'>('pending');
  const [projectFilter, setProjectFilter] = useState<string>('');
  const [keyword, setKeyword] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [rejectReason, setRejectReason] = useState('');
  const [projects, setProjects] = useState<Project[]>([]);
  const [candidates, setCandidates] = useState<CandidateItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [actingKey, setActingKey] = useState<string | null>(null);
  const [batchActing, setBatchActing] = useState<'confirm' | 'reject' | null>(null);
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const requestSequence = useRef(0);

  const selectedType = tabs.find(t => t.key === tab)?.type;

  const loadCandidates = async () => {
    const request = ++requestSequence.current;
    setLoading(true);
    setLoadError('');
    try {
      const data = await listCandidatesPage({
        type: selectedType,
        projectId: projectFilter ? Number(projectFilter) : undefined,
        status: statusFilter === 'rejected' ? 'REJECTED' : undefined,
        keyword: searchTerm,
        page,
        size: 50,
      });
      if (request === requestSequence.current) {
        setCandidates(data.items as CandidateItem[]);
        setTotal(data.total);
      }
    } catch (error: any) {
      if (request === requestSequence.current) {
        setCandidates([]);
        setTotal(0);
        setLoadError(error.message || '加载候选失败');
      }
    } finally {
      if (request === requestSequence.current) setLoading(false);
    }
  };

  useEffect(() => {
    listAllProjects()
      .then(setProjects)
      .catch(error => showToast(error.message || '项目列表加载失败', 'error'));
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => { setPage(1); setSearchTerm(keyword.trim()); }, 250);
    return () => clearTimeout(timer);
  }, [keyword]);

  useEffect(() => {
    setSelectedKeys([]);
    void loadCandidates();
    return () => { requestSequence.current++; };
  }, [tab, statusFilter, projectFilter, searchTerm, page]);

  const filteredCandidates = candidates;

  const selectedItems = useMemo(() => {
    const selectedSet = new Set(selectedKeys);
    return filteredCandidates.filter(candidate => canReviewHere(candidate) && selectedSet.has(candidateKey(candidate)));
  }, [filteredCandidates, selectedKeys]);

  const selectableCandidates = filteredCandidates.filter(canReviewHere);
  const allFilteredSelected = selectableCandidates.length > 0 &&
    selectableCandidates.every(candidate => selectedKeys.includes(candidateKey(candidate)));

  const projectNameMap = useMemo(
    () => new Map(projects.map(project => [project.id, project.projectName])),
    [projects]
  );

  const clearSelection = () => setSelectedKeys([]);

  const removeFromList = (type: string, id: number) => {
    setCandidates(prev => prev.filter(candidate => !(candidate.candidateType === type && candidate.id === id)));
    setSelectedKeys(prev => prev.filter(key => key !== `${type}:${id}`));
    setTotal(prev => Math.max(0, prev - 1));
  };

  const handleConfirm = async (candidate: CandidateItem) => {
    const key = candidateKey(candidate);
    setActingKey(key);
    try {
      await confirmCandidate(candidate.candidateType, candidate.id);
      removeFromList(candidate.candidateType, candidate.id);
      showToast('已确认');
    } catch (error: any) {
      showToast(error.message || '操作失败', 'error');
    } finally {
      setActingKey(null);
    }
  };

  const handleReject = async (candidate: CandidateItem) => {
    const key = candidateKey(candidate);
    setActingKey(key);
    try {
      await rejectCandidate(candidate.candidateType, candidate.id, rejectReason.trim() || undefined);
      removeFromList(candidate.candidateType, candidate.id);
      showToast('已驳回');
    } catch (error: any) {
      showToast(error.message || '操作失败', 'error');
    } finally {
      setActingKey(null);
    }
  };

  const handleBatchConfirm = async () => {
    if (selectedItems.length === 0) return;
    setBatchActing('confirm');
    try {
      const count = await batchConfirmCandidates(
        selectedItems.map(candidate => ({ type: candidate.candidateType, id: candidate.id }))
      );
      const selectedSet = new Set(selectedKeys);
      setCandidates(prev => prev.filter(candidate => !selectedSet.has(candidateKey(candidate))));
      setTotal(prev => Math.max(0, prev - count));
      clearSelection();
      showToast(`已批量确认 ${count} 条`);
    } catch (error: any) {
      showToast(error.message || '批量确认失败', 'error');
    } finally {
      setBatchActing(null);
    }
  };

  const handleBatchReject = async () => {
    if (selectedItems.length === 0) return;
    setBatchActing('reject');
    try {
      const count = await batchRejectCandidates(
        selectedItems.map(candidate => ({ type: candidate.candidateType, id: candidate.id })),
        rejectReason.trim() || undefined
      );
      const selectedSet = new Set(selectedKeys);
      setCandidates(prev => prev.filter(candidate => !selectedSet.has(candidateKey(candidate))));
      setTotal(prev => Math.max(0, prev - count));
      clearSelection();
      showToast(`已批量驳回 ${count} 条`);
    } catch (error: any) {
      showToast(error.message || '批量驳回失败', 'error');
    } finally {
      setBatchActing(null);
    }
  };

  const handleToggleSelectAll = () => {
    if (allFilteredSelected) {
      clearSelection();
      return;
    }
    setSelectedKeys(selectableCandidates.map(candidate => candidateKey(candidate)));
  };

  const handleToggleSelect = (candidate: CandidateItem) => {
    const key = candidateKey(candidate);
    setSelectedKeys(prev => (prev.includes(key) ? prev.filter(item => item !== key) : [...prev, key]));
  };

  const getDisplayName = (candidate: CandidateItem) => {
    return candidate.name || candidate.overview || candidate.skillName || candidate.toolName || candidate.fieldName || candidate.suggestedValue || `#${candidate.id}`;
  };

  const getTypeLabel = (type: string) => {
    const map: Record<string, string> = { tom: 'TOM', wiki_pack: 'Wiki 包', wiki_entry: 'Wiki 条目', automation: '自动化映射', automation_system: '系统级自动化申请', summary: '摘要', correction: '修正', skill: 'Skill', tool: 'Tool' };
    return map[type] || type;
  };

  const getStatusLabel = (status?: string) => {
    if (!status || pendingStatuses.has(status)) return '待审核';
    if (status === 'REJECTED') return '已驳回';
    return status;
  };

  return (
    <div className="p-4 animate-fade-in sm:p-6">
      <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          {embedded ? <h2 className="text-lg font-semibold text-gray-900">候选资产</h2> :
            <h1 className="text-xl font-bold text-gray-900 tracking-tight">候选资产审核</h1>}
          <p className="text-sm text-gray-500 mt-1">按项目和状态处理候选；自动化映射需检查运行证据后在项目页审核。</p>
        </div>
        <button
          onClick={loadCandidates}
          className="min-h-10 shrink-0 rounded-lg border border-gray-200 px-3 py-2 text-sm font-medium text-gray-700 transition-colors hover:border-gray-300 hover:bg-gray-50"
        >
          刷新列表
        </button>
      </div>

      <div className="mb-4 flex w-fit max-w-full flex-wrap items-center gap-1 rounded-lg bg-gray-100 p-0.5">
        {tabs.map(t => (
          <button
            key={t.key}
            onClick={() => { setTab(t.key); setPage(1); }}
            className={`px-3 py-1.5 text-xs font-medium rounded-md transition-all ${
              tab === t.key ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {loadError && <div role="alert" className="mb-4 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-800">
        {loadError}。请刷新列表重试。
      </div>}

      <div className="bg-white rounded-xl border border-gray-200 p-4 mb-4">
        <div className="grid grid-cols-1 md:grid-cols-4 gap-3">
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">状态</label>
            <select
              value={statusFilter}
              onChange={e => { setStatusFilter(e.target.value as 'pending' | 'rejected'); setPage(1); }}
              className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            >
              <option value="pending">待审核</option>
              <option value="rejected">已驳回</option>
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">项目</label>
            <select
              value={projectFilter}
              onChange={e => { setProjectFilter(e.target.value); setPage(1); }}
              className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            >
              <option value="">全部项目</option>
              {projects.map(project => (
                <option key={project.id} value={String(project.id)}>
                  {project.projectName}
                </option>
              ))}
            </select>
          </div>
          <div className="md:col-span-2">
            <label className="block text-xs font-medium text-gray-500 mb-1">关键词</label>
            <input
              type="text"
              value={keyword}
              onChange={e => setKeyword(e.target.value)}
              placeholder="搜索名称、摘要、字段名、建议值..."
              className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            />
          </div>
        </div>
      </div>

      <div className="bg-white rounded-xl border border-gray-200 p-4 mb-4">
        <div className="flex flex-col lg:flex-row lg:items-center gap-3 justify-between">
          <div className="flex items-center gap-3 flex-wrap">
            <label className="inline-flex items-center gap-2 text-sm text-gray-600">
              <input
                type="checkbox"
                checked={allFilteredSelected}
                onChange={handleToggleSelectAll}
                className="rounded border-gray-300 text-slate-900 focus:ring-slate-400"
              />
              全选当前页
            </label>
            <span className="text-sm text-gray-500">
              已选 {selectedItems.length} / 本页 {filteredCandidates.length} · 总计 {total}
            </span>
          </div>
          <div className="flex flex-col sm:flex-row gap-3 sm:items-center">
            <input
              type="text"
              value={rejectReason}
              onChange={e => setRejectReason(e.target.value)}
              placeholder="驳回原因（可选，单条/批量共用）"
              className="w-full sm:w-72 h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            />
            <div className="flex items-center gap-2">
              <button
                onClick={handleBatchConfirm}
                disabled={selectedItems.length === 0 || batchActing !== null}
                className="px-3 py-2 text-sm font-medium text-green-700 bg-green-50 rounded-lg hover:bg-green-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {batchActing === 'confirm' ? '批量确认中...' : '批量确认'}
              </button>
              <button
                onClick={handleBatchReject}
                disabled={selectedItems.length === 0 || batchActing !== null}
                className="px-3 py-2 text-sm font-medium text-red-600 bg-red-50 rounded-lg hover:bg-red-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {batchActing === 'reject' ? '批量驳回中...' : '批量驳回'}
              </button>
            </div>
          </div>
        </div>
      </div>

      {loading ? (
        <div className="space-y-2">
          {[1, 2, 3].map(i => <div key={i} className="animate-pulse bg-gray-100 rounded h-16 w-full" />)}
        </div>
      ) : filteredCandidates.length === 0 ? (
        <div className="bg-white rounded-xl border border-gray-200 p-12 text-center text-gray-400 text-sm">
          暂无匹配的候选资产
        </div>
      ) : (
        <div className="space-y-2">
          {filteredCandidates.map(candidate => {
            const key = candidateKey(candidate);
            const busy = actingKey === key;
            return (
              <div key={key} className="flex flex-col gap-3 rounded-xl border border-gray-200 bg-white p-4 sm:flex-row sm:items-start sm:gap-4">
                <input
                  type="checkbox"
                  checked={selectedKeys.includes(key)}
                  disabled={!canReviewHere(candidate)}
                  onChange={() => handleToggleSelect(candidate)}
                  className="mt-1 rounded border-gray-300 text-slate-900 focus:ring-slate-400"
                />
                <span className="text-[11px] font-medium px-2 py-0.5 rounded bg-gray-100 text-gray-600 mt-0.5">
                  {getTypeLabel(candidate.candidateType)}
                </span>
                <div className="flex-1 min-w-0">
                  <div className="text-sm font-medium text-gray-900 break-all">{getDisplayName(candidate)}</div>
                  <div className="flex flex-wrap items-center gap-2 mt-1 text-xs text-gray-500">
                    {candidate.projectId ? (
                      <span>项目：{projectNameMap.get(candidate.projectId) || `#${candidate.projectId}`}</span>
                    ) : null}
                    {candidate.summaryScope ? <span>范围：{candidate.summaryScope}</span> : null}
                    {candidate.description ? <span className="max-w-full break-words sm:max-w-[500px]">{candidate.description}</span> : null}
                    {candidate.sourceType ? <span>来源：{candidate.sourceType}{candidate.sourceId ? ` #${candidate.sourceId}` : ''}</span> : null}
                  </div>
                  {reviewDetailUrl(candidate) && <Link to={reviewDetailUrl(candidate)!}
                    className="mt-2 inline-block text-xs font-medium text-blue-700 hover:underline">
                    {candidate.candidateType === 'automation' || candidate.candidateType === 'automation_system' ? '检查发布记录并审核' : '查看来源详情'}
                  </Link>}
                  {candidate.candidateType === 'tool' && candidate.body ? (
                    <div className="mt-2">
                      <button type="button" onClick={() => setExpandedKeys(prev => prev.includes(key)
                        ? prev.filter(item => item !== key) : [...prev, key])}
                        className="text-xs font-medium text-blue-700 hover:underline">
                        {expandedKeys.includes(key) ? '收起定位详情' : '查看定位详情'}
                      </button>
                      {expandedKeys.includes(key) ? (
                        <div className="mt-2 rounded-lg border border-gray-200 bg-gray-50 p-3 text-xs">
                          <p className="mb-2 text-gray-600">来源任务：{candidate.sourceId ?? '未记录'}；审核后仍需在当前页面版本验证定位</p>
                          <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-all text-gray-800">{(() => {
                            try { return JSON.stringify(JSON.parse(candidate.body), null, 2); }
                            catch { return candidate.body; }
                          })()}</pre>
                        </div>
                      ) : null}
                    </div>
                  ) : null}
                </div>
                <div className="flex shrink-0 flex-row flex-wrap items-center gap-2 sm:flex-col sm:items-end">
                  <span className={`text-[11px] font-medium px-2 py-0.5 rounded ${
                    pendingStatuses.has(candidate.status || '') ? 'bg-yellow-50 text-yellow-700' : 'bg-gray-100 text-gray-600'
                  }`}>
                    {getStatusLabel(candidate.status)}
                  </span>
                  <span className="text-xs font-mono text-gray-400">
                    {candidate.createdAt ? new Date(candidate.createdAt).toLocaleDateString('zh-CN') : '-'}
                  </span>
                  {statusFilter === 'pending' && canReviewHere(candidate) ? (
                    <div className="flex flex-wrap items-center gap-2">
                      <button
                        onClick={() => handleConfirm(candidate)}
                        disabled={busy || batchActing !== null}
                        className="px-3 py-1.5 text-xs font-medium text-green-700 bg-green-50 rounded hover:bg-green-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                      >
                        {busy ? '处理中...' : '确认'}
                      </button>
                      <button
                        onClick={() => handleReject(candidate)}
                        disabled={busy || batchActing !== null}
                        className="px-3 py-1.5 text-xs font-medium text-red-600 bg-red-50 rounded hover:bg-red-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                      >
                        {busy ? '处理中...' : '驳回'}
                      </button>
                    </div>
                  ) : null}
                </div>
              </div>
            );
          })}
        </div>
      )}
      <div className="mt-4 flex items-center justify-between text-xs text-gray-600">
        <span>第 {page} 页 · 共 {total} 条</span>
        <div className="flex gap-2">
          <button type="button" disabled={page <= 1 || loading} onClick={() => setPage(value => value - 1)} className="rounded border px-3 py-1 disabled:opacity-40">上一页</button>
          <button type="button" disabled={page * 50 >= total || loading} onClick={() => setPage(value => value + 1)} className="rounded border px-3 py-1 disabled:opacity-40">下一页</button>
        </div>
      </div>
    </div>
  );
}

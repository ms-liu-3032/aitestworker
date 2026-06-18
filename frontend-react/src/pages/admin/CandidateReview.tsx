import { useEffect, useMemo, useState } from 'react';
import { useApp } from '../../context/AppContext';
import {
  batchConfirmCandidates,
  batchRejectCandidates,
  confirmCandidate,
  listCandidates,
  listProjects,
  rejectCandidate,
  type Project,
} from '../../services/api';

type TabKey = 'all' | 'tom' | 'summary' | 'correction' | 'skill' | 'tool';

const tabs: { key: TabKey; label: string; type?: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'tom', label: 'Mini-TOM', type: 'tom' },
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
};

const pendingStatuses = new Set(['CANDIDATE', 'DRAFT']);

function candidateKey(candidate: Pick<CandidateItem, 'candidateType' | 'id'>) {
  return `${candidate.candidateType}:${candidate.id}`;
}

export default function CandidateReview() {
  const { showToast } = useApp();
  const [tab, setTab] = useState<TabKey>('all');
  const [statusFilter, setStatusFilter] = useState<'pending' | 'rejected'>('pending');
  const [projectFilter, setProjectFilter] = useState<string>('');
  const [keyword, setKeyword] = useState('');
  const [rejectReason, setRejectReason] = useState('');
  const [projects, setProjects] = useState<Project[]>([]);
  const [candidates, setCandidates] = useState<CandidateItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [actingKey, setActingKey] = useState<string | null>(null);
  const [batchActing, setBatchActing] = useState<'confirm' | 'reject' | null>(null);
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);

  const selectedType = tabs.find(t => t.key === tab)?.type;

  const loadCandidates = async () => {
    setLoading(true);
    try {
      const data = await listCandidates({
        type: selectedType,
        projectId: projectFilter ? Number(projectFilter) : undefined,
        status: statusFilter === 'rejected' ? 'REJECTED' : undefined,
      });
      setCandidates(data as CandidateItem[]);
    } catch (error: any) {
      showToast(error.message || '加载候选失败', 'error');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    listProjects({ page: 1, size: 200 })
      .then(result => setProjects(result.items))
      .catch(() => {});
  }, []);

  useEffect(() => {
    setSelectedKeys([]);
    loadCandidates();
  }, [tab, statusFilter, projectFilter]);

  const filteredCandidates = useMemo(() => {
    const term = keyword.trim().toLowerCase();
    if (!term) return candidates;
    return candidates.filter(candidate => {
      const values = [
        candidate.name,
        candidate.overview,
        candidate.skillName,
        candidate.toolName,
        candidate.fieldName,
        candidate.suggestedValue,
        candidate.description,
        candidate.summaryScope,
        candidate.candidateType,
      ];
      return values.some(value => value && value.toLowerCase().includes(term));
    });
  }, [candidates, keyword]);

  const selectedItems = useMemo(() => {
    const selectedSet = new Set(selectedKeys);
    return filteredCandidates.filter(candidate => selectedSet.has(candidateKey(candidate)));
  }, [filteredCandidates, selectedKeys]);

  const allFilteredSelected = filteredCandidates.length > 0 &&
    filteredCandidates.every(candidate => selectedKeys.includes(candidateKey(candidate)));

  const projectNameMap = useMemo(
    () => new Map(projects.map(project => [project.id, project.projectName])),
    [projects]
  );

  const clearSelection = () => setSelectedKeys([]);

  const removeFromList = (type: string, id: number) => {
    setCandidates(prev => prev.filter(candidate => !(candidate.candidateType === type && candidate.id === id)));
    setSelectedKeys(prev => prev.filter(key => key !== `${type}:${id}`));
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
    setSelectedKeys(filteredCandidates.map(candidate => candidateKey(candidate)));
  };

  const handleToggleSelect = (candidate: CandidateItem) => {
    const key = candidateKey(candidate);
    setSelectedKeys(prev => (prev.includes(key) ? prev.filter(item => item !== key) : [...prev, key]));
  };

  const getDisplayName = (candidate: CandidateItem) => {
    return candidate.name || candidate.overview || candidate.skillName || candidate.toolName || candidate.fieldName || candidate.suggestedValue || `#${candidate.id}`;
  };

  const getTypeLabel = (type: string) => {
    const map: Record<string, string> = { tom: 'TOM', summary: '摘要', correction: '修正', skill: 'Skill', tool: 'Tool' };
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
          <h1 className="text-xl font-bold text-gray-900 tracking-tight">候选资产审核</h1>
          <p className="text-sm text-gray-500 mt-1">支持按项目、状态和关键词筛选，并批量确认或驳回候选资产。</p>
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
            onClick={() => setTab(t.key)}
            className={`px-3 py-1.5 text-xs font-medium rounded-md transition-all ${
              tab === t.key ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div className="bg-white rounded-xl border border-gray-200 p-4 mb-4">
        <div className="grid grid-cols-1 md:grid-cols-4 gap-3">
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">状态</label>
            <select
              value={statusFilter}
              onChange={e => setStatusFilter(e.target.value as 'pending' | 'rejected')}
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
              onChange={e => setProjectFilter(e.target.value)}
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
              全选当前结果
            </label>
            <span className="text-sm text-gray-500">
              已选 {selectedItems.length} / 当前结果 {filteredCandidates.length}
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
                  </div>
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
                  {statusFilter === 'pending' ? (
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
    </div>
  );
}

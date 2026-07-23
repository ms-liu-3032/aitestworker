import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import Modal from '../../components/Modal';
import MultiSelectFilter from '../../components/MultiSelectFilter';
import { displayLabel } from '../../utils/displayLabels';
import { useParams } from 'react-router-dom';
import { useApp } from '../../context/AppContext';
import RichList from '../../components/RichList';
import {
  confirmLocalCase,
  batchConfirmLocalCases,
  batchDeprecateLocalCases,
  batchSubmitLocalCases,
  deprecateLocalCase,
  duplicateLocalCase,
  exportLocalCasesToXmind,
  getLocalCase,
  listLocalCasesPage,
  submitLocalCase,
  updateLocalCase,
  type LocalCaseDraft,
} from '../../services/api';

const priorityConfig: Record<string, { bg: string; text: string }> = {
  P0: { bg: 'bg-red-50', text: 'text-red-600' },
  P1: { bg: 'bg-orange-50', text: 'text-orange-600' },
  P2: { bg: 'bg-yellow-50', text: 'text-yellow-600' },
  P3: { bg: 'bg-gray-100', text: 'text-gray-500' },
};

function normalizeStatus(status?: string) {
  return status || 'DRAFT';
}

export default function LocalCaseLibrary() {
  const { projectId } = useParams<{ projectId: string }>();
  const { showToast } = useApp();
  const [cases, setCases] = useState<LocalCaseDraft[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const pageSize = 50;
  const [submittingId, setSubmittingId] = useState<number | null>(null);
  const [confirmingId, setConfirmingId] = useState<number | null>(null);
  const [deprecatingId, setDeprecatingId] = useState<number | null>(null);
  const [duplicatingId, setDuplicatingId] = useState<number | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [batchingAction, setBatchingAction] = useState<'confirm' | 'deprecate' | 'submit' | null>(null);
  const [exporting, setExporting] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [moduleFilter, setModuleFilter] = useState<string[]>([]);
  const [priorityFilter, setPriorityFilter] = useState<string[]>([]);
  const [statusFilter, setStatusFilter] = useState<string[]>([]);
  const [sourceFilter, setSourceFilter] = useState<string[]>([]);
  const [scenarioFilter, setScenarioFilter] = useState<string[]>([]);
  const [editingCase, setEditingCase] = useState<LocalCaseDraft | null>(null);
  const [moduleOptions, setModuleOptions] = useState<string[]>([]);
  const loadRequestId = useRef(0);
  const [savingEdit, setSavingEdit] = useState(false);
  const [detailCase, setDetailCase] = useState<LocalCaseDraft | null>(null);
  const [actionNotice, setActionNotice] = useState<{ title: string; body: string } | null>(null);
  const [editForm, setEditForm] = useState({
    caseTitle: '',
    moduleName: '',
    precondition: '',
    steps: '',
    expectedResult: '',
    priority: 'P2',
  });

  const loadCases = async (nextPage = page) => {
    if (!projectId) return;
    const requestId = ++loadRequestId.current;
    setLoading(true);
    try {
      const data = await listLocalCasesPage(Number(projectId), nextPage, pageSize, {
        keyword,
        modules: moduleFilter,
        priorities: priorityFilter,
        statuses: statusFilter,
        sources: sourceFilter,
        scenarioTypes: scenarioFilter,
      });
      if (requestId !== loadRequestId.current) return;
      setCases(data.items);
      setTotal(data.total);
      setPage(data.page);
      setModuleOptions(data.moduleOptions || []);
      setSelectedIds(new Set());
    } catch (error: any) {
      showToast(error.message || '加载本地用例失败', 'error');
    } finally {
      if (requestId === loadRequestId.current) setLoading(false);
    }
  };

  const updateCaseInState = (updated: LocalCaseDraft) => {
    setCases(prev => prev.map(item => (item.id === updated.id ? updated : item)));
  };

  useEffect(() => {
    loadRequestId.current += 1;
    setPage(0);
    setLoading(true);
    const timer = window.setTimeout(() => void loadCases(0), keyword.trim() ? 250 : 0);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, keyword, moduleFilter, priorityFilter, statusFilter, sourceFilter, scenarioFilter]);

  const openDetail = async (draft: LocalCaseDraft) => {
    if (!projectId) return;
    try {
      setDetailCase(await getLocalCase(Number(projectId), draft.id));
    } catch (error: any) {
      showToast(error.message || '加载用例详情失败', 'error');
    }
  };

  const handleDuplicate = async (draft: LocalCaseDraft) => {
    if (!projectId) return;
    setDuplicatingId(draft.id);
    try {
      await duplicateLocalCase(Number(projectId), draft.id);
      showToast('已复制为新的本地草稿');
      await loadCases(0);
    } catch (error: any) {
      showToast(error.message || '复制失败', 'error');
    } finally {
      setDuplicatingId(null);
    }
  };

  const handleSubmit = async (id: number) => {
    if (!projectId) return;
    setSubmittingId(id);
    try {
      await submitLocalCase(Number(projectId), id);
      setCases(prev => prev.map(item => item.id === id ? { ...item, caseStatus: 'SUBMITTED' } : item));
      setActionNotice({
        title: '草稿已提交到正式用例库',
        body: '这条用例已经进入正式库。你可以继续留在这里整理其他草稿，或直接去正式用例库检查状态。',
      });
      showToast('已提交到正式用例库');
    } catch (error: any) {
      showToast(error.message || '提交失败', 'error');
    } finally {
      setSubmittingId(null);
    }
  };

  const handleConfirm = async (draft: LocalCaseDraft) => {
    if (!projectId) return;
    setConfirmingId(draft.id);
    try {
      const updated = await confirmLocalCase(Number(projectId), draft.id);
      updateCaseInState(updated);
      setActionNotice({
        title: '草稿已确认',
        body: '这条本地用例现在处于“已确认”，适合继续提交到正式库，或再做一次内容润色后再提交。',
      });
      showToast('草稿已确认');
    } catch (error: any) {
      showToast(error.message || '确认失败', 'error');
    } finally {
      setConfirmingId(null);
    }
  };

  const handleDeprecate = async (draft: LocalCaseDraft) => {
    if (!projectId) return;
    setDeprecatingId(draft.id);
    try {
      await deprecateLocalCase(Number(projectId), draft.id);
      setCases(prev => prev.filter(item => item.id !== draft.id));
      setTotal(prev => Math.max(0, prev - 1));
      setActionNotice({
        title: '草稿已舍弃',
        body: '这条用例已从本地用例库和当前会话草稿中删除，不会再参与后续提交。',
      });
      showToast('草稿已舍弃');
    } catch (error: any) {
      showToast(error.message || '舍弃失败', 'error');
    } finally {
      setDeprecatingId(null);
    }
  };

  const toggleSelect = (id: number) => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleSelectAll = () => {
    if (selectedIds.size === cases.length) {
      setSelectedIds(new Set());
      return;
    }
    setSelectedIds(new Set(cases.map(item => item.id)));
  };

  const runBatchAction = async (action: 'confirm' | 'deprecate' | 'submit') => {
    if (!projectId) return;
    const selected = cases.filter(item => selectedIds.has(item.id));
    const eligible = selected.filter(item => {
      const status = normalizeStatus(item.caseStatus);
      if (action === 'confirm') return status !== 'SUBMITTED' && status !== 'CONFIRMED';
      if (action === 'deprecate') return status !== 'SUBMITTED';
      return status !== 'SUBMITTED' && status !== 'DEPRECATED';
    });
    if (eligible.length === 0) {
      showToast(action === 'confirm' ? '所选用例均无需确认' : action === 'deprecate' ? '所选用例均不能舍弃' : '所选用例均不能提交', 'error');
      return;
    }
    const actionLabel = action === 'confirm' ? '确认' : action === 'deprecate' ? '舍弃' : '提交到正式库';
    if (!window.confirm(`确定批量${actionLabel} ${eligible.length} 条本地用例吗？`)) return;
    setBatchingAction(action);
    try {
      const ids = eligible.map(item => item.id);
      const result = action === 'confirm'
        ? await batchConfirmLocalCases(Number(projectId), ids)
        : action === 'deprecate'
          ? await batchDeprecateLocalCases(Number(projectId), ids)
          : await batchSubmitLocalCases(Number(projectId), ids);
      showToast(`已批量${actionLabel} ${result.affectedCount} 条用例`);
      setActionNotice({
        title: `批量${actionLabel}完成`,
        body: `${result.affectedCount} 条本地用例已完成处理。`,
      });
      await loadCases(page);
    } catch (error: any) {
      showToast(error.message || `批量${actionLabel}失败`, 'error');
    } finally {
      setBatchingAction(null);
    }
  };

  const selectedExportableIds = useMemo(() => cases
    .filter(item => selectedIds.has(item.id))
    .filter(item => ['CONFIRMED', 'SUBMITTED'].includes(normalizeStatus(item.caseStatus)))
    .map(item => item.id), [cases, selectedIds]);

  const handleExport = async () => {
    if (!projectId) return;
    const exportingSelection = selectedIds.size > 0;
    if (exportingSelection && selectedExportableIds.length === 0) {
      showToast('所选用例中没有已确认或已提交的用例', 'error');
      return;
    }
    setExporting(true);
    try {
      const blob = await exportLocalCasesToXmind(
        Number(projectId), exportingSelection ? selectedExportableIds : undefined);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = '本地用例库.xmind';
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
      const ignored = exportingSelection ? selectedIds.size - selectedExportableIds.length : 0;
      showToast(ignored > 0
        ? `导出完成，已忽略 ${ignored} 条未确认用例`
        : '本地用例导出完成');
    } catch (error: any) {
      showToast(error.message || '导出失败', 'error');
    } finally {
      setExporting(false);
    }
  };

  const openEditModal = async (draft: LocalCaseDraft) => {
    if (!projectId) return;
    try {
      const fullDraft = await getLocalCase(Number(projectId), draft.id);
      setEditingCase(fullDraft);
      setEditForm({
        caseTitle: fullDraft.caseTitle || '',
        moduleName: fullDraft.moduleName || '',
        precondition: fullDraft.precondition || '',
        steps: fullDraft.steps || '',
        expectedResult: fullDraft.expectedResult || '',
        priority: fullDraft.priority || 'P2',
      });
    } catch (error: any) {
      showToast(error.message || '加载用例详情失败', 'error');
    }
  };

  const handleSaveEdit = async () => {
    if (!projectId || !editingCase) return;
    if (!editForm.caseTitle.trim() || !editForm.steps.trim() || !editForm.expectedResult.trim()) {
      showToast('请至少填写用例标题、步骤和预期结果', 'error');
      return;
    }
    setSavingEdit(true);
    try {
      const updated = await updateLocalCase(Number(projectId), editingCase.id, {
        caseTitle: editForm.caseTitle.trim(),
        moduleName: editForm.moduleName.trim() || undefined,
        precondition: editForm.precondition.trim() || undefined,
        steps: editForm.steps.trim(),
        expectedResult: editForm.expectedResult.trim(),
        priority: editForm.priority,
      });
      updateCaseInState(updated);
      setEditingCase(null);
      setActionNotice({
        title: '草稿已保存',
        body: '修改已经生效。你可以继续确认这条草稿，或等内容稳定后提交到正式用例库。',
      });
      showToast('草稿已保存');
    } catch (error: any) {
      showToast(error.message || '保存失败', 'error');
    } finally {
      setSavingEdit(false);
    }
  };

  const counts = useMemo(() => ({
    total,
    draft: cases.filter(item => normalizeStatus(item.caseStatus) === 'DRAFT').length,
    confirmed: cases.filter(item => normalizeStatus(item.caseStatus) === 'CONFIRMED').length,
    submitted: cases.filter(item => normalizeStatus(item.caseStatus) === 'SUBMITTED').length,
    trace: cases.filter(item => item.sourceType === 'TRACE').length,
  }), [cases]);

  const columns = [
    { key: 'select', label: '', width: '40px' },
    { key: 'title', label: '用例名称', width: '320px' },
    { key: 'type', label: '类型', width: '100px' },
    { key: 'priority', label: '优先级', width: '80px' },
    { key: 'status', label: '状态', width: '100px' },
    { key: 'updated', label: '生成时间', width: '120px' },
    { key: 'actions', label: '操作', width: '280px' },
  ];

  return (
    <div className="p-4 animate-fade-in sm:p-6">
      <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-bold text-gray-900 tracking-tight">本地用例库</h1>
          <p className="text-sm text-gray-500 mt-1">统一查看需求生成和轨迹回放沉淀的草稿，并继续整理后提交到正式用例库。</p>
        </div>
        <div className="flex shrink-0 flex-wrap gap-2">
          <button
            onClick={() => void handleExport()}
            disabled={exporting || (selectedIds.size > 0 && selectedExportableIds.length === 0)}
            className="min-h-10 rounded-lg border border-blue-200 px-3 py-2 text-sm font-medium text-blue-700 transition-colors hover:bg-blue-50 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {exporting
              ? '导出中...'
              : selectedIds.size > 0
                ? `导出已确认/已提交 (${selectedExportableIds.length})`
                : '导出全部已确认/已提交'}
          </button>
          <button
            onClick={() => void loadCases(page)}
            className="min-h-10 rounded-lg border border-gray-200 px-3 py-2 text-sm font-medium text-gray-700 transition-colors hover:border-gray-300 hover:bg-gray-50"
          >
            刷新列表
          </button>
        </div>
      </div>

      {actionNotice && (
        <div className="mb-4 rounded-xl border border-sky-200 bg-sky-50 px-4 py-3">
          <div className="text-sm font-semibold text-sky-900">{actionNotice.title}</div>
          <div className="text-xs text-sky-800 mt-1">{actionNotice.body}</div>
          <div className="mt-3 flex flex-wrap items-center gap-3 text-xs">
            <Link to={`/projects/${projectId}/formal-cases`} className="text-sky-700 hover:text-sky-900 transition-colors">
              去正式用例库
            </Link>
            <Link to={`/projects/${projectId}/generation`} className="text-sky-700 hover:text-sky-900 transition-colors">
              回生成页
            </Link>
            <Link to={`/projects/${projectId}/trace`} className="text-sky-700 hover:text-sky-900 transition-colors">
              回轨迹页
            </Link>
            <button onClick={() => setActionNotice(null)} className="text-sky-700 hover:text-sky-900 transition-colors">
              收起提示
            </button>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-5 gap-3 mb-4">
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">全部草稿</div>
          <div className="text-2xl font-semibold text-gray-900 mt-1">{counts.total}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">当前页草稿</div>
          <div className="text-2xl font-semibold text-yellow-700 mt-1">{counts.draft}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">当前页已确认</div>
          <div className="text-2xl font-semibold text-green-700 mt-1">{counts.confirmed}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">当前页已提交</div>
          <div className="text-2xl font-semibold text-blue-700 mt-1">{counts.submitted}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">当前页轨迹来源</div>
          <div className="text-2xl font-semibold text-purple-700 mt-1">{counts.trace}</div>
        </div>
      </div>

      <div className="bg-white rounded-xl border border-gray-200 p-4 mb-4">
        <div className="grid grid-cols-1 gap-3 md:grid-cols-3 xl:grid-cols-6">
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">关键词</label>
            <input
              type="text"
              value={keyword}
              onChange={e => setKeyword(e.target.value)}
              placeholder="搜索用例名称、模块、类型..."
              className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            />
          </div>
          <MultiSelectFilter label="模块" options={moduleOptions} value={moduleFilter} onChange={setModuleFilter} />
          <MultiSelectFilter label="优先级" options={['P0', 'P1', 'P2', 'P3']} value={priorityFilter} onChange={setPriorityFilter} />
          <MultiSelectFilter label="状态" options={['DRAFT', 'CONFIRMED', 'SUBMITTED', 'DEPRECATED']} value={statusFilter} onChange={setStatusFilter} />
          <MultiSelectFilter label="来源" options={['GENERATION', 'TRACE', 'MANUAL']} value={sourceFilter} onChange={setSourceFilter} />
          <MultiSelectFilter label="场景" options={['POSITIVE', 'NEGATIVE', 'BOUNDARY', 'COMBINATION', 'STATE', 'RECOVERY']} value={scenarioFilter} onChange={setScenarioFilter} />
        </div>
      </div>

      <div className="mb-2 flex flex-wrap items-center gap-2">
        <input
          type="checkbox"
          checked={selectedIds.size === cases.length && cases.length > 0}
          onChange={toggleSelectAll}
          className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
        />
        <span className="text-xs text-gray-500">全选当前页筛选结果</span>
        {selectedIds.size > 0 && <span className="text-xs text-blue-600">已选 {selectedIds.size} 条</span>}
        {selectedIds.size > 0 && (
          <>
            <button onClick={() => void runBatchAction('confirm')} disabled={batchingAction !== null} className="rounded border border-emerald-200 px-2.5 py-1.5 text-xs font-medium text-emerald-700 hover:bg-emerald-50 disabled:opacity-50">
              {batchingAction === 'confirm' ? '确认中...' : '批量确认'}
            </button>
            <button onClick={() => void runBatchAction('submit')} disabled={batchingAction !== null} className="rounded border border-blue-200 px-2.5 py-1.5 text-xs font-medium text-blue-700 hover:bg-blue-50 disabled:opacity-50">
              {batchingAction === 'submit' ? '提交中...' : '批量提交正式库'}
            </button>
            <button onClick={() => void runBatchAction('deprecate')} disabled={batchingAction !== null} className="rounded border border-amber-200 px-2.5 py-1.5 text-xs font-medium text-amber-700 hover:bg-amber-50 disabled:opacity-50">
              {batchingAction === 'deprecate' ? '舍弃中...' : '批量舍弃'}
            </button>
          </>
        )}
      </div>

      <RichList
        items={cases}
        columns={columns}
        loading={loading}
        emptyText="暂无匹配的本地用例"
        renderRow={(testCase: LocalCaseDraft) => {
          const status = normalizeStatus(testCase.caseStatus);
          const submitted = status === 'SUBMITTED';
          const deprecated = status === 'DEPRECATED';
          const confirmed = status === 'CONFIRMED';
          const sourceType = testCase.sourceType === 'TRACE' ? '轨迹回放' : '需求生成';
          const sourceClass = testCase.sourceType === 'TRACE'
            ? 'bg-purple-50 text-purple-700'
            : 'bg-sky-50 text-sky-700';
          return (
            <>
              <div className="flex shrink-0 items-center" style={{ width: '40px' }}>
                <input
                  type="checkbox"
                  checked={selectedIds.has(testCase.id)}
                  onChange={() => toggleSelect(testCase.id)}
                  className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
              </div>
              <div style={{ width: '320px' }}>
                <button onClick={() => void openDetail(testCase)} className="block max-w-full truncate text-left text-sm font-medium text-gray-900 hover:text-blue-600">{testCase.caseTitle}</button>
                <div className="text-xs text-gray-500 truncate flex items-center gap-2">
                  <span className={`inline-flex items-center px-2 py-0.5 rounded font-medium ${sourceClass}`}>{displayLabel(sourceType, '未知来源')}</span>
                  <span className="truncate">{testCase.moduleName || '未分模块'} · {testCase.caseScope || testCase.caseType || '未分类'}</span>
                </div>
              </div>
              <div className="text-sm text-gray-600" style={{ width: '150px' }}>
                <div>{displayLabel(testCase.scenarioType, '未分类场景')}</div>
                <div className="truncate text-xs text-gray-400" title={testCase.designMethod || ''}>{testCase.designMethod || '-'}</div>
              </div>
              <div style={{ width: '80px' }}>
                {testCase.priority && (
                  <span className={`text-[11px] font-semibold px-2 py-0.5 rounded ${priorityConfig[testCase.priority]?.bg || 'bg-gray-100'} ${priorityConfig[testCase.priority]?.text || 'text-gray-500'}`}>
                    {testCase.priority}
                  </span>
                )}
              </div>
              <div style={{ width: '100px' }}>
                <span className={`text-[11px] font-medium px-2 py-0.5 rounded ${
                  status === 'CONFIRMED' ? 'bg-green-50 text-green-700' :
                  status === 'SUBMITTED' ? 'bg-blue-50 text-blue-700' :
                  status === 'DEPRECATED' ? 'bg-gray-100 text-gray-600' :
                  'bg-yellow-50 text-yellow-700'
                }`}>
                  {status === 'DRAFT' ? '草稿' : status === 'CONFIRMED' ? '已确认' : status === 'SUBMITTED' ? '已提交' : status === 'DEPRECATED' ? '已弃用' : status}
                </span>
              </div>
              <div className="text-xs text-gray-500 font-mono" style={{ width: '120px' }}>
                {new Date(testCase.createdAt).toLocaleDateString('zh-CN')}
              </div>
              <div style={{ width: '280px', flex: 1 }}>
                <div className="flex flex-wrap items-center gap-3 text-xs">
                  <button onClick={() => void openDetail(testCase)} className="text-sky-600 hover:text-sky-700 font-medium">详情</button>
                  {!submitted && (
                    <button
                      onClick={() => void openEditModal(testCase)}
                      className="text-slate-700 hover:text-slate-900 font-medium"
                    >
                      编辑
                    </button>
                  )}
                  {!submitted && !confirmed && (
                    <button
                      onClick={() => void handleConfirm(testCase)}
                      disabled={confirmingId === testCase.id}
                      className="text-emerald-600 hover:text-emerald-700 font-medium disabled:opacity-50"
                    >
                      {confirmingId === testCase.id ? '确认中...' : '确认'}
                    </button>
                  )}
                  {!submitted && !deprecated && (
                    <button
                      onClick={() => void handleDeprecate(testCase)}
                      disabled={deprecatingId === testCase.id}
                      className="text-amber-600 hover:text-amber-700 font-medium disabled:opacity-50"
                    >
                      {deprecatingId === testCase.id ? '舍弃中...' : '舍弃'}
                    </button>
                  )}
                  {!submitted && !deprecated && (
                    <button
                      onClick={() => void handleDuplicate(testCase)}
                      disabled={duplicatingId === testCase.id}
                      className="text-violet-600 hover:text-violet-700 font-medium disabled:opacity-50"
                    >
                      {duplicatingId === testCase.id ? '复制中...' : '复制'}
                    </button>
                  )}
                  {submitted ? (
                    <span className="text-xs text-gray-400">已进入正式库</span>
                  ) : (
                    !deprecated && (
                      <button
                        onClick={() => void handleSubmit(testCase.id)}
                        disabled={submittingId === testCase.id}
                        className="text-blue-600 hover:text-blue-700 font-medium disabled:opacity-50"
                      >
                        {submittingId === testCase.id ? '提交中...' : '提交正式库'}
                      </button>
                    )
                  )}
                </div>
              </div>
            </>
          );
        }}
      />

      <div className="mt-4 flex items-center justify-between gap-3 text-sm">
        <span className="text-gray-500">
          {loading ? '正在统计筛选结果...' : `第 ${page + 1} / ${Math.max(1, Math.ceil(total / pageSize))} 页，共 ${total} 条`}
        </span>
        <div className="flex gap-2">
          <button onClick={() => void loadCases(page - 1)} disabled={page === 0 || loading} className="rounded-lg border border-gray-200 px-3 py-2 text-gray-700 disabled:opacity-40">上一页</button>
          <button onClick={() => void loadCases(page + 1)} disabled={(page + 1) * pageSize >= total || loading} className="rounded-lg border border-gray-200 px-3 py-2 text-gray-700 disabled:opacity-40">下一页</button>
        </div>
      </div>

      {detailCase && (
        <Modal title="本地用例详情" onClose={() => setDetailCase(null)} footer={<button onClick={() => setDetailCase(null)} className="px-4 py-2 text-sm text-gray-600">关闭</button>}>
          <div className="space-y-4 text-sm">
            <div><div className="text-xs text-gray-500">用例名称</div><div className="mt-1 break-words font-medium">{detailCase.caseTitle}</div></div>
            <div className="grid grid-cols-2 gap-3"><div><div className="text-xs text-gray-500">模块</div><div>{detailCase.moduleName || '-'}</div></div><div><div className="text-xs text-gray-500">优先级</div><div>{detailCase.priority || '-'}</div></div></div>
            <div className="grid grid-cols-2 gap-3"><div><div className="text-xs text-gray-500">场景类型</div><div>{displayLabel(detailCase.scenarioType, '未分类场景')}</div></div><div><div className="text-xs text-gray-500">设计方法</div><div>{detailCase.designMethod || '-'}</div></div></div>
            {detailCase.precondition && <div><div className="text-xs text-gray-500">前置条件</div><div className="mt-1 whitespace-pre-wrap break-words">{detailCase.precondition}</div></div>}
            <div><div className="text-xs text-gray-500">测试步骤</div><div className="mt-1 whitespace-pre-wrap break-words rounded-lg bg-gray-50 p-3">{detailCase.steps}</div></div>
            <div><div className="text-xs text-gray-500">预期结果</div><div className="mt-1 whitespace-pre-wrap break-words rounded-lg bg-gray-50 p-3">{detailCase.expectedResult}</div></div>
          </div>
        </Modal>
      )}

      {editingCase && (
        <Modal
          title="编辑本地用例草稿"
          onClose={() => !savingEdit && setEditingCase(null)}
          footer={(
            <div className="flex flex-wrap justify-end gap-2">
              <button
                onClick={() => setEditingCase(null)}
                disabled={savingEdit}
                className="px-4 py-2 text-sm font-medium text-gray-700 border border-gray-200 rounded-lg hover:bg-gray-50 disabled:opacity-60"
              >
                取消
              </button>
              <button
                onClick={() => void handleSaveEdit()}
                disabled={savingEdit}
                className="px-4 py-2 text-sm font-medium text-white bg-slate-900 rounded-lg hover:bg-slate-800 disabled:opacity-60"
              >
                {savingEdit ? '保存中...' : '保存修改'}
              </button>
            </div>
          )}
        >
          <div className="space-y-4 text-sm">
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">用例标题</label>
              <input
                type="text"
                value={editForm.caseTitle}
                onChange={e => setEditForm(prev => ({ ...prev, caseTitle: e.target.value }))}
                className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
              />
            </div>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div>
                <label className="block text-xs font-medium text-gray-500 mb-1">模块</label>
                <input
                  type="text"
                  value={editForm.moduleName}
                  onChange={e => setEditForm(prev => ({ ...prev, moduleName: e.target.value }))}
                  className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-gray-500 mb-1">优先级</label>
                <select
                  value={editForm.priority}
                  onChange={e => setEditForm(prev => ({ ...prev, priority: e.target.value }))}
                  className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
                >
                  <option value="P0">P0</option>
                  <option value="P1">P1</option>
                  <option value="P2">P2</option>
                  <option value="P3">P3</option>
                  <option value="P4">P4</option>
                </select>
              </div>
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">前置条件</label>
              <textarea
                rows={2}
                value={editForm.precondition}
                onChange={e => setEditForm(prev => ({ ...prev, precondition: e.target.value }))}
                className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none resize-none"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">步骤</label>
              <textarea
                rows={5}
                value={editForm.steps}
                onChange={e => setEditForm(prev => ({ ...prev, steps: e.target.value }))}
                className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none resize-none"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">预期结果</label>
              <textarea
                rows={4}
                value={editForm.expectedResult}
                onChange={e => setEditForm(prev => ({ ...prev, expectedResult: e.target.value }))}
                className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none resize-none"
              />
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}

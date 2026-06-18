import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import Modal from '../../components/Modal';
import MultiSelectFilter from '../../components/MultiSelectFilter';
import { useParams } from 'react-router-dom';
import { useApp } from '../../context/AppContext';
import RichList from '../../components/RichList';
import {
  confirmLocalCase,
  deprecateLocalCase,
  listLocalCases,
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
  const [submittingId, setSubmittingId] = useState<number | null>(null);
  const [confirmingId, setConfirmingId] = useState<number | null>(null);
  const [deprecatingId, setDeprecatingId] = useState<number | null>(null);
  const [keyword, setKeyword] = useState('');
  const [moduleFilter, setModuleFilter] = useState<string[]>([]);
  const [priorityFilter, setPriorityFilter] = useState<string[]>([]);
  const [statusFilter, setStatusFilter] = useState<string[]>([]);
  const [sourceFilter, setSourceFilter] = useState<string[]>([]);  const [editingCase, setEditingCase] = useState<LocalCaseDraft | null>(null);
  const [savingEdit, setSavingEdit] = useState(false);
  const [actionNotice, setActionNotice] = useState<{ title: string; body: string } | null>(null);
  const [editForm, setEditForm] = useState({
    caseTitle: '',
    moduleName: '',
    precondition: '',
    steps: '',
    expectedResult: '',
    priority: 'P2',
  });

  const loadCases = async () => {
    if (!projectId) return;
    setLoading(true);
    try {
      const data = await listLocalCases(Number(projectId));
      setCases(data);
    } catch (error: any) {
      showToast(error.message || '加载本地用例失败', 'error');
    } finally {
      setLoading(false);
    }
  };

  const updateCaseInState = (updated: LocalCaseDraft) => {
    setCases(prev => prev.map(item => (item.id === updated.id ? updated : item)));
  };

  useEffect(() => {
    void loadCases();
  }, [projectId]);

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
      const updated = await deprecateLocalCase(Number(projectId), draft.id);
      updateCaseInState(updated);
      setActionNotice({
        title: '草稿已弃用',
        body: '这条用例已从当前可提交状态中退出。若后续需要恢复，可以在生成源头重新整理，或根据规则再生成一版。',
      });
      showToast('草稿已弃用');
    } catch (error: any) {
      showToast(error.message || '弃用失败', 'error');
    } finally {
      setDeprecatingId(null);
    }
  };

  const openEditModal = (draft: LocalCaseDraft) => {
    setEditingCase(draft);
    setEditForm({
      caseTitle: draft.caseTitle || '',
      moduleName: draft.moduleName || '',
      precondition: draft.precondition || '',
      steps: draft.steps || '',
      expectedResult: draft.expectedResult || '',
      priority: draft.priority || 'P2',
    });
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

  const moduleOptions = useMemo(
    () => Array.from(new Set(cases.map(item => item.moduleName).filter(Boolean))) as string[],
    [cases]
  );

  const filteredCases = useMemo(() => {
    const term = keyword.trim().toLowerCase();
    return cases.filter(item => {
      if (moduleFilter.length > 0 && !moduleFilter.includes(item.moduleName || '')) return false;
      if (priorityFilter.length > 0 && !priorityFilter.includes(item.priority || '')) return false;
      if (statusFilter.length > 0 && !statusFilter.includes(normalizeStatus(item.caseStatus))) return false;
      if (sourceFilter.length > 0 && !sourceFilter.includes(item.sourceType || '')) return false;
      if (!term) return true;
      return [
        item.caseTitle,
        item.moduleName || '',
        item.caseType || '',
        item.caseScope || '',
        item.sourceType || '',
      ].some(value => value.toLowerCase().includes(term));
    });
  }, [cases, keyword, moduleFilter, priorityFilter, statusFilter, sourceFilter]);

  const counts = useMemo(() => ({
    total: cases.length,
    draft: cases.filter(item => normalizeStatus(item.caseStatus) === 'DRAFT').length,
    confirmed: cases.filter(item => normalizeStatus(item.caseStatus) === 'CONFIRMED').length,
    submitted: cases.filter(item => normalizeStatus(item.caseStatus) === 'SUBMITTED').length,
    trace: cases.filter(item => item.sourceType === 'TRACE').length,
  }), [cases]);

  const columns = [
    { key: 'title', label: '用例名称', width: '320px' },
    { key: 'type', label: '类型', width: '100px' },
    { key: 'priority', label: '优先级', width: '80px' },
    { key: 'status', label: '状态', width: '100px' },
    { key: 'updated', label: '生成时间', width: '120px' },
    { key: 'actions', label: '操作', width: '260px' },
  ];

  return (
    <div className="p-4 animate-fade-in sm:p-6">
      <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-bold text-gray-900 tracking-tight">本地用例库</h1>
          <p className="text-sm text-gray-500 mt-1">统一查看需求生成和轨迹回放沉淀的草稿，并继续整理后提交到正式用例库。</p>
        </div>
        <button
          onClick={() => void loadCases()}
          className="min-h-10 shrink-0 rounded-lg border border-gray-200 px-3 py-2 text-sm font-medium text-gray-700 transition-colors hover:border-gray-300 hover:bg-gray-50"
        >
          刷新列表
        </button>
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
          <div className="text-xs text-gray-500">草稿</div>
          <div className="text-2xl font-semibold text-yellow-700 mt-1">{counts.draft}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">已确认</div>
          <div className="text-2xl font-semibold text-green-700 mt-1">{counts.confirmed}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">已提交</div>
          <div className="text-2xl font-semibold text-blue-700 mt-1">{counts.submitted}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">轨迹回放来源</div>
          <div className="text-2xl font-semibold text-purple-700 mt-1">{counts.trace}</div>
        </div>
      </div>

      <div className="bg-white rounded-xl border border-gray-200 p-4 mb-4">
        <div className="grid grid-cols-1 md:grid-cols-5 gap-3">
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
        </div>
      </div>

      <RichList
        items={filteredCases}
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
              <div style={{ width: '320px' }}>
                <div className="text-sm font-medium text-gray-900 truncate">{testCase.caseTitle}</div>
                <div className="text-xs text-gray-500 truncate flex items-center gap-2">
                  <span className={`inline-flex items-center px-2 py-0.5 rounded font-medium ${sourceClass}`}>{sourceType}</span>
                  <span className="truncate">{testCase.moduleName || '未分模块'} · {testCase.caseScope || testCase.caseType || '未分类'}</span>
                </div>
              </div>
              <div className="text-sm text-gray-600" style={{ width: '100px' }}>{testCase.caseType || '-'}</div>
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
              <div style={{ width: '260px', flex: 1 }}>
                <div className="flex flex-wrap items-center gap-3 text-xs">
                  {!submitted && (
                    <button
                      onClick={() => openEditModal(testCase)}
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
                      {deprecatingId === testCase.id ? '弃用中...' : '弃用'}
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

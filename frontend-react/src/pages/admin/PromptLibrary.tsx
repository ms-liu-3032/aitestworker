import { useEffect, useMemo, useState } from 'react';
import Drawer from '../../components/Drawer';
import RichList from '../../components/RichList';
import { useApp } from '../../context/AppContext';
import {
  createPromptNewVersion,
  createPromptTemplate,
  listPromptTemplates,
  reviewPromptTemplate,
  type PromptTemplateRecord,
} from '../../services/api';

type FilterScope = '' | 'PUBLIC' | 'SYSTEM' | 'PERSONAL';
type FilterStatus = '' | 'ACTIVE' | 'DEPRECATED' | 'REVIEWING';
type FilterReview = '' | 'APPROVED' | 'PENDING' | 'REJECTED';

const scopeLabels: Record<string, string> = {
  PUBLIC: '公共',
  SYSTEM: '系统',
  PERSONAL: '私有',
};

const reviewLabels: Record<string, string> = {
  APPROVED: '已通过',
  PENDING: '待审核',
  REJECTED: '已驳回',
};

export default function PromptLibrary() {
  const { showToast } = useApp();
  const [prompts, setPrompts] = useState<PromptTemplateRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [scopeFilter, setScopeFilter] = useState<FilterScope>('');
  const [statusFilter, setStatusFilter] = useState<FilterStatus>('');
  const [reviewFilter, setReviewFilter] = useState<FilterReview>('');
  const [form, setForm] = useState({ promptName: '', promptType: 'GENERAL', content: '' });
  const [selectedPrompt, setSelectedPrompt] = useState<PromptTemplateRecord | null>(null);
  const [draftContent, setDraftContent] = useState('');
  const [reviewReason, setReviewReason] = useState('');
  const [savingNewVersion, setSavingNewVersion] = useState(false);
  const [reviewing, setReviewing] = useState<'approve' | 'reject' | null>(null);

  const loadPrompts = async () => {
    setLoading(true);
    try {
      const data = await listPromptTemplates();
      setPrompts(data);
    } catch (error: any) {
      showToast(error.message || '加载提示词失败', 'error');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadPrompts();
  }, []);

  const handleCreate = async () => {
    if (!form.promptName || !form.content) return;
    try {
      const prompt = await createPromptTemplate(form);
      setPrompts(prev => [prompt, ...prev]);
      setForm({ promptName: '', promptType: 'GENERAL', content: '' });
      setShowForm(false);
      showToast('提示词已创建');
    } catch (error: any) {
      showToast(error.message || '创建失败', 'error');
    }
  };

  const filteredPrompts = useMemo(() => {
    const term = keyword.trim().toLowerCase();
    return prompts.filter(prompt => {
      if (scopeFilter && prompt.scope !== scopeFilter) return false;
      if (statusFilter && prompt.status !== statusFilter) return false;
      if (reviewFilter && prompt.reviewStatus !== reviewFilter) return false;
      if (!term) return true;
      return [
        prompt.promptName,
        prompt.promptType,
        prompt.scope,
        prompt.status,
        prompt.reviewStatus,
        prompt.contributorUsername || '',
        prompt.content,
      ].some(value => value.toLowerCase().includes(term));
    });
  }, [prompts, keyword, scopeFilter, statusFilter, reviewFilter]);

  const counts = useMemo(() => ({
    total: prompts.length,
    pending: prompts.filter(prompt => prompt.reviewStatus === 'PENDING').length,
    active: prompts.filter(prompt => prompt.status === 'ACTIVE').length,
  }), [prompts]);

  const columns = [
    { key: 'name', label: '名称', width: '220px' },
    { key: 'type', label: '类型', width: '120px' },
    { key: 'scope', label: '范围', width: '100px' },
    { key: 'version', label: '版本', width: '80px' },
    { key: 'status', label: '状态', width: '100px' },
    { key: 'review', label: '审核', width: '100px' },
    { key: 'updated', label: '更新时间', width: '120px' },
    { key: 'actions', label: '操作', width: '220px' },
  ];

  const openPrompt = (prompt: PromptTemplateRecord) => {
    setSelectedPrompt(prompt);
    setDraftContent(prompt.content);
    setReviewReason('');
    setSavingNewVersion(false);
    setReviewing(null);
  };

  const closeDrawer = () => {
    setSelectedPrompt(null);
    setDraftContent('');
    setReviewReason('');
    setSavingNewVersion(false);
    setReviewing(null);
  };

  const handleCreateNewVersion = async () => {
    if (!selectedPrompt) return;
    if (!draftContent.trim() || draftContent.trim() === selectedPrompt.content.trim()) {
      showToast('请输入新内容后再创建版本', 'error');
      return;
    }
    setSavingNewVersion(true);
    try {
      const updated = await createPromptNewVersion(selectedPrompt.id, draftContent);
      setPrompts(prev => [updated, ...prev.map(item => item.id === selectedPrompt.id ? { ...item, status: 'DEPRECATED' } : item)]);
      setSelectedPrompt(updated);
      setDraftContent(updated.content);
      showToast('已创建新版本');
    } catch (error: any) {
      showToast(error.message || '创建新版本失败', 'error');
    } finally {
      setSavingNewVersion(false);
    }
  };

  const handleReview = async (approved: boolean) => {
    if (!selectedPrompt) return;
    setReviewing(approved ? 'approve' : 'reject');
    try {
      const updated = await reviewPromptTemplate(selectedPrompt.id, approved, reviewReason.trim() || undefined);
      setPrompts(prev => prev.map(item => item.id === updated.id ? updated : item));
      setSelectedPrompt(updated);
      showToast(approved ? '已审核通过' : '已驳回候选');
    } catch (error: any) {
      showToast(error.message || '审核失败', 'error');
    } finally {
      setReviewing(null);
    }
  };

  return (
    <div className="p-4 animate-fade-in sm:p-6">
      <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-bold text-gray-900 tracking-tight">公共提示词库</h1>
          <p className="text-sm text-gray-500 mt-1">支持检索、查看详情、创建新版本，以及审核待发布候选提示词。</p>
        </div>
        <button
          onClick={() => setShowForm(!showForm)}
          className="min-h-10 shrink-0 rounded-lg bg-slate-900 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-slate-800"
        >
          + 新建提示词
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-3 mb-4">
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">总数</div>
          <div className="text-2xl font-semibold text-gray-900 mt-1">{counts.total}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">待审核候选</div>
          <div className="text-2xl font-semibold text-yellow-700 mt-1">{counts.pending}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">活跃版本</div>
          <div className="text-2xl font-semibold text-green-700 mt-1">{counts.active}</div>
        </div>
      </div>

      <div className="bg-white rounded-xl border border-gray-200 p-4 mb-4">
        <div className="grid grid-cols-1 md:grid-cols-4 gap-3">
          <div className="md:col-span-2">
            <label className="block text-xs font-medium text-gray-500 mb-1">关键词</label>
            <input
              type="text"
              value={keyword}
              onChange={e => setKeyword(e.target.value)}
              placeholder="搜索名称、类型、贡献人或内容..."
              className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">范围</label>
            <select
              value={scopeFilter}
              onChange={e => setScopeFilter(e.target.value as FilterScope)}
              className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            >
              <option value="">全部范围</option>
              <option value="PUBLIC">公共</option>
              <option value="SYSTEM">系统</option>
              <option value="PERSONAL">私有</option>
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">状态</label>
            <select
              value={statusFilter}
              onChange={e => setStatusFilter(e.target.value as FilterStatus)}
              className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            >
              <option value="">全部状态</option>
              <option value="ACTIVE">活跃</option>
              <option value="DEPRECATED">已弃用</option>
              <option value="REVIEWING">审核中</option>
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">审核</label>
            <select
              value={reviewFilter}
              onChange={e => setReviewFilter(e.target.value as FilterReview)}
              className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            >
              <option value="">全部审核态</option>
              <option value="APPROVED">已通过</option>
              <option value="PENDING">待审核</option>
              <option value="REJECTED">已驳回</option>
            </select>
          </div>
        </div>
      </div>

      {showForm && (
        <div className="bg-white rounded-xl border border-gray-200 p-5 mb-4">
          <div className="mb-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">名称</label>
              <input type="text" value={form.promptName} onChange={e => setForm({ ...form, promptName: e.target.value })} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">类型</label>
              <input type="text" value={form.promptType} onChange={e => setForm({ ...form, promptType: e.target.value })} placeholder="如：SUMMARY, CASE_GEN" className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
            </div>
          </div>
          <div className="mb-4">
            <label className="block text-xs font-medium text-gray-500 mb-1">内容</label>
            <textarea value={form.content} onChange={e => setForm({ ...form, content: e.target.value })} rows={6} className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm font-mono focus:border-gray-400 outline-none resize-none" />
          </div>
          <div className="flex flex-wrap justify-end gap-2">
            <button onClick={() => setShowForm(false)} className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700">取消</button>
            <button onClick={handleCreate} className="px-4 py-2 bg-slate-900 text-white text-sm font-semibold rounded-lg hover:bg-slate-800">创建</button>
          </div>
        </div>
      )}

      <RichList
        items={filteredPrompts}
        columns={columns}
        loading={loading}
        emptyText="暂无提示词"
        renderRow={(prompt: PromptTemplateRecord) => (
          <>
            <div style={{ width: '220px' }}>
              <div className="text-sm font-medium text-gray-900 truncate">{prompt.promptName}</div>
              {prompt.contributorUsername ? (
                <div className="text-[11px] text-gray-500 mt-1 truncate">贡献人：{prompt.contributorUsername}</div>
              ) : null}
            </div>
            <div className="text-sm text-gray-600" style={{ width: '120px' }}>{prompt.promptType}</div>
            <div style={{ width: '100px' }}>
              <span className={`text-[11px] font-medium px-2 py-0.5 rounded ${
                prompt.scope === 'PUBLIC' ? 'bg-blue-50 text-blue-700' :
                prompt.scope === 'SYSTEM' ? 'bg-purple-50 text-purple-700' :
                'bg-gray-100 text-gray-600'
              }`}>
                {scopeLabels[prompt.scope] || prompt.scope}
              </span>
            </div>
            <div className="font-mono text-sm text-gray-600" style={{ width: '80px' }}>v{prompt.version}</div>
            <div style={{ width: '100px' }}>
              <span className={`text-[11px] font-medium px-2 py-0.5 rounded ${
                prompt.status === 'ACTIVE' ? 'bg-green-50 text-green-700' :
                prompt.status === 'REVIEWING' ? 'bg-yellow-50 text-yellow-700' :
                'bg-gray-100 text-gray-600'
              }`}>
                {prompt.status === 'ACTIVE' ? '活跃' : prompt.status === 'DEPRECATED' ? '已弃用' : prompt.status}
              </span>
            </div>
            <div style={{ width: '100px' }}>
              <span className={`text-[11px] font-medium px-2 py-0.5 rounded ${
                prompt.reviewStatus === 'APPROVED' ? 'bg-green-50 text-green-700' :
                prompt.reviewStatus === 'PENDING' ? 'bg-yellow-50 text-yellow-700' :
                'bg-gray-100 text-gray-600'
              }`}>
                {reviewLabels[prompt.reviewStatus] || prompt.reviewStatus}
              </span>
            </div>
            <div className="text-xs text-gray-500 font-mono" style={{ width: '120px' }}>
              {new Date(prompt.updatedAt).toLocaleDateString('zh-CN')}
            </div>
            <div style={{ width: '220px' }} className="flex justify-end">
              <button
                onClick={() => openPrompt(prompt)}
                className="px-3 py-1.5 text-xs font-medium text-slate-700 bg-slate-50 rounded hover:bg-slate-100 transition-colors"
              >
                查看详情
              </button>
            </div>
          </>
        )}
      />

      <Drawer
        open={!!selectedPrompt}
        onClose={closeDrawer}
        title={selectedPrompt ? `${selectedPrompt.promptName} · v${selectedPrompt.version}` : '提示词详情'}
        width="680px"
        footer={selectedPrompt ? (
          <>
            <button onClick={closeDrawer} className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700">关闭</button>
            {selectedPrompt.reviewStatus === 'PENDING' ? (
              <>
                <button
                  onClick={() => handleReview(false)}
                  disabled={reviewing !== null}
                  className="px-4 py-2 text-sm font-medium text-red-600 bg-red-50 rounded-lg hover:bg-red-100 disabled:opacity-50"
                >
                  {reviewing === 'reject' ? '驳回中...' : '驳回候选'}
                </button>
                <button
                  onClick={() => handleReview(true)}
                  disabled={reviewing !== null}
                  className="px-4 py-2 bg-slate-900 text-white text-sm font-semibold rounded-lg hover:bg-slate-800 disabled:opacity-50"
                >
                  {reviewing === 'approve' ? '通过中...' : '审核通过'}
                </button>
              </>
            ) : (selectedPrompt.scope === 'PUBLIC' || selectedPrompt.scope === 'SYSTEM') ? (
              <button
                onClick={handleCreateNewVersion}
                disabled={savingNewVersion}
                className="px-4 py-2 bg-slate-900 text-white text-sm font-semibold rounded-lg hover:bg-slate-800 disabled:opacity-50"
              >
                {savingNewVersion ? '创建中...' : '创建新版本'}
              </button>
            ) : null}
          </>
        ) : null}
      >
        {selectedPrompt ? (
          <div className="space-y-5">
            <div className="grid grid-cols-1 gap-4 text-sm sm:grid-cols-2">
              <div>
                <div className="text-xs font-medium text-gray-500 mb-1">类型</div>
                <div className="text-gray-900">{selectedPrompt.promptType}</div>
              </div>
              <div>
                <div className="text-xs font-medium text-gray-500 mb-1">范围</div>
                <div className="text-gray-900">{scopeLabels[selectedPrompt.scope] || selectedPrompt.scope}</div>
              </div>
              <div>
                <div className="text-xs font-medium text-gray-500 mb-1">状态</div>
                <div className="text-gray-900">{selectedPrompt.status}</div>
              </div>
              <div>
                <div className="text-xs font-medium text-gray-500 mb-1">审核态</div>
                <div className="text-gray-900">{reviewLabels[selectedPrompt.reviewStatus] || selectedPrompt.reviewStatus}</div>
              </div>
            </div>

            {selectedPrompt.reviewStatus === 'PENDING' ? (
              <div>
                <label className="block text-xs font-medium text-gray-500 mb-1">审核备注（驳回时可填）</label>
                <input
                  type="text"
                  value={reviewReason}
                  onChange={e => setReviewReason(e.target.value)}
                  placeholder="例如：命名不清晰、缺少上下文..."
                  className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
                />
              </div>
            ) : null}

            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">
                {selectedPrompt.scope === 'PUBLIC' || selectedPrompt.scope === 'SYSTEM' ? '版本内容（可修改后创建新版本）' : '提示词内容'}
              </label>
              <textarea
                value={draftContent}
                onChange={e => setDraftContent(e.target.value)}
                rows={18}
                readOnly={!(selectedPrompt.scope === 'PUBLIC' || selectedPrompt.scope === 'SYSTEM') || selectedPrompt.reviewStatus === 'PENDING'}
                className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm font-mono focus:border-gray-400 outline-none resize-none read-only:text-gray-600"
              />
            </div>

            {selectedPrompt.deprecatedReason ? (
              <div className="bg-amber-50 border border-amber-200 rounded-lg px-4 py-3 text-sm text-amber-800">
                弃用原因：{selectedPrompt.deprecatedReason}
              </div>
            ) : null}
          </div>
        ) : null}
      </Drawer>
    </div>
  );
}

import { useState, useEffect } from 'react';
import { useApp } from '../../context/AppContext';
import RichList from '../../components/RichList';
import Modal from '../../components/Modal';
import { listModelConfigs, createModelConfig, updateModelConfig, deleteModelConfig, type ModelConfigRecord } from '../../services/api';

export default function ModelConfig() {
  const { showToast } = useApp();
  const [configs, setConfigs] = useState<ModelConfigRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState({ configName: '', provider: '', modelName: '', endpoint: '', apiKey: '' });
  const [editingId, setEditingId] = useState<number | null>(null);
  const [savingEdit, setSavingEdit] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [pendingDelete, setPendingDelete] = useState<ModelConfigRecord | null>(null);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    setLoading(true);
    listModelConfigs()
      .then(setConfigs)
      .catch(() => showToast('加载模型配置失败'))
      .finally(() => setLoading(false));
  }, []);

  const handleCreate = async () => {
    if (!form.configName || !form.provider || !form.modelName) return;
    try {
      const config = await createModelConfig(form);
      setConfigs(prev => [...prev, config]);
      setForm({ configName: '', provider: '', modelName: '', endpoint: '', apiKey: '' });
      setShowForm(false);
      showToast('模型配置已创建');
    } catch (e: any) {
      showToast(e.message || '创建失败', 'error');
    }
  };

  const handleStartEdit = (config: ModelConfigRecord) => {
    setEditingId(config.id);
    setShowForm(false);
    setForm({
      configName: config.configName,
      provider: config.provider,
      modelName: config.modelName,
      endpoint: config.endpoint || '',
      apiKey: '',
    });
  };

  const handleCancelEdit = () => {
    setEditingId(null);
    setForm({ configName: '', provider: '', modelName: '', endpoint: '', apiKey: '' });
  };

  const handleUpdate = async () => {
    if (!editingId || !form.configName || !form.provider || !form.modelName) return;
    setSavingEdit(true);
    try {
      const updated = await updateModelConfig(editingId, form);
      setConfigs(prev => prev.map(item => (item.id === updated.id ? updated : item)));
      handleCancelEdit();
      showToast('模型配置已更新');
    } catch (e: any) {
      showToast(e.message || '更新失败', 'error');
    } finally {
      setSavingEdit(false);
    }
  };

  const handleDelete = async (id: number) => {
    setDeleting(true);
    try {
      await deleteModelConfig(id);
      setConfigs(prev => prev.filter(c => c.id !== id));
      setPendingDelete(null);
      showToast('已删除');
    } catch {
      showToast('删除失败', 'error');
    } finally {
      setDeleting(false);
    }
  };

  const filteredConfigs = configs.filter(config => {
    if (!keyword.trim()) return true;
    const haystack = `${config.configName} ${config.provider} ${config.modelName} ${config.endpoint || ''}`.toLowerCase();
    return haystack.includes(keyword.trim().toLowerCase());
  });

  const columns = [
    { key: 'name', label: '配置名称', width: '180px' },
    { key: 'provider', label: '提供商', width: '120px' },
    { key: 'model', label: '模型', width: '150px' },
    { key: 'status', label: '状态', width: '100px' },
    { key: 'actions', label: '操作', width: '140px' },
  ];

  return (
    <div className="p-4 animate-fade-in sm:p-6">
      <div className="mb-5 flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-bold text-gray-900 tracking-tight">模型配置</h1>
          <p className="mt-1 text-sm text-gray-500">支持新增、编辑和删除模型配置，避免每次变更都靠删掉重建。</p>
        </div>
        <div className="flex w-full flex-col gap-3 sm:flex-row lg:w-auto lg:justify-end">
          <input
            type="text"
            value={keyword}
            onChange={e => setKeyword(e.target.value)}
            placeholder="搜索配置、提供商或模型..."
            className="h-10 w-full rounded-lg border border-gray-200 bg-white px-3 text-sm text-gray-700 outline-none transition-colors focus:border-gray-400 sm:w-64"
          />
          <button
            onClick={() => {
              setShowForm(!showForm);
              setEditingId(null);
              if (showForm) {
                setForm({ configName: '', provider: '', modelName: '', endpoint: '', apiKey: '' });
              }
            }}
            className="min-h-10 shrink-0 rounded-lg bg-slate-900 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-slate-800"
          >
            + 添加模型
          </button>
        </div>
      </div>

      {showForm && (
        <div className="bg-white rounded-xl border border-gray-200 p-5 mb-4">
          <div className="mb-4 grid grid-cols-1 gap-4 md:grid-cols-3">
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">配置名称</label>
              <input type="text" value={form.configName} onChange={e => setForm({ ...form, configName: e.target.value })} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">提供商</label>
              <input type="text" value={form.provider} onChange={e => setForm({ ...form, provider: e.target.value })} placeholder="如：DeepSeek" className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">模型名称</label>
              <input type="text" value={form.modelName} onChange={e => setForm({ ...form, modelName: e.target.value })} placeholder="如：deepseek-chat" className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
            </div>
          </div>
          <div className="mb-4 grid grid-cols-1 gap-4 md:grid-cols-2">
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">API Endpoint</label>
              <input type="text" value={form.endpoint} onChange={e => setForm({ ...form, endpoint: e.target.value })} placeholder="https://api.example.com" className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm font-mono focus:border-gray-400 outline-none" />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">API Key</label>
              <input type="password" value={form.apiKey} onChange={e => setForm({ ...form, apiKey: e.target.value })} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
            </div>
          </div>
          <div className="flex flex-wrap justify-end gap-2">
            <button onClick={() => setShowForm(false)} className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700">取消</button>
            <button onClick={handleCreate} className="px-4 py-2 bg-slate-900 text-white text-sm font-semibold rounded-lg hover:bg-slate-800">创建</button>
          </div>
        </div>
      )}

      {editingId !== null && (
        <div className="bg-white rounded-xl border border-blue-200 p-5 mb-4 shadow-sm">
          <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div className="min-w-0">
              <h2 className="text-sm font-semibold text-gray-900">编辑模型配置</h2>
              <p className="mt-1 text-xs text-gray-500">不修改 API Key 时可留空，系统会继续保留原密钥。</p>
            </div>
            <button onClick={handleCancelEdit} className="text-sm text-gray-500 hover:text-gray-700">取消编辑</button>
          </div>
          <div className="mb-4 grid grid-cols-1 gap-4 md:grid-cols-3">
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">配置名称</label>
              <input type="text" value={form.configName} onChange={e => setForm({ ...form, configName: e.target.value })} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">提供商</label>
              <input type="text" value={form.provider} onChange={e => setForm({ ...form, provider: e.target.value })} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">模型名称</label>
              <input type="text" value={form.modelName} onChange={e => setForm({ ...form, modelName: e.target.value })} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
            </div>
          </div>
          <div className="mb-4 grid grid-cols-1 gap-4 md:grid-cols-2">
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">API Endpoint</label>
              <input type="text" value={form.endpoint} onChange={e => setForm({ ...form, endpoint: e.target.value })} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm font-mono focus:border-gray-400 outline-none" />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">API Key</label>
              <input type="password" value={form.apiKey} onChange={e => setForm({ ...form, apiKey: e.target.value })} placeholder="留空表示保持原值" className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
            </div>
          </div>
          <div className="flex flex-wrap justify-end gap-2">
            <button onClick={handleCancelEdit} className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700">取消</button>
            <button onClick={handleUpdate} disabled={savingEdit} className="px-4 py-2 bg-slate-900 text-white text-sm font-semibold rounded-lg hover:bg-slate-800 disabled:opacity-50">
              {savingEdit ? '保存中...' : '保存修改'}
            </button>
          </div>
        </div>
      )}

      {pendingDelete && (
        <Modal
          title="删除模型配置"
          onClose={() => !deleting && setPendingDelete(null)}
          footer={
            <>
              <button
                onClick={() => setPendingDelete(null)}
                disabled={deleting}
                className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 transition-colors disabled:opacity-50"
              >
                取消
              </button>
              <button
                onClick={() => handleDelete(pendingDelete.id)}
                disabled={deleting}
                className="px-4 py-2 bg-red-600 text-white text-sm font-semibold rounded-lg hover:bg-red-700 transition-colors disabled:opacity-50"
              >
                {deleting ? '删除中...' : '确认删除'}
              </button>
            </>
          }
        >
          <div className="space-y-3">
            <p className="text-sm text-gray-700">
              这会删除模型配置 <span className="break-all font-semibold text-gray-900">{pendingDelete.configName}</span>，
              后续引用它的前端页面将不会再把它列为可选模型。
            </p>
            <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-xs text-red-700">
              <div>提供商：{pendingDelete.provider}</div>
              <div className="break-all">模型：{pendingDelete.modelName}</div>
              <div className="mt-1">请确认当前没有团队成员还在依赖这条配置执行生成、摘要或扫描任务。</div>
            </div>
          </div>
        </Modal>
      )}

      <RichList
        items={filteredConfigs}
        columns={columns}
        loading={loading}
        emptyText="暂无模型配置"
        renderRow={(c: ModelConfigRecord) => (
          <>
            <div style={{ width: '180px' }}>
              <div className="text-sm font-medium text-gray-900">{c.configName}</div>
            </div>
            <div className="text-sm text-gray-600" style={{ width: '120px' }}>{c.provider}</div>
            <div className="font-mono text-sm text-gray-600" style={{ width: '150px' }}>{c.modelName}</div>
            <div style={{ width: '100px' }}>
              <span className={`text-[11px] font-medium px-2 py-0.5 rounded ${c.status === 'ACTIVE' ? 'bg-green-50 text-green-700' : 'bg-gray-100 text-gray-600'}`}>
                {c.status === 'ACTIVE' ? '活跃' : c.status}
              </span>
            </div>
            <div className="opacity-0 group-hover:opacity-100 transition-opacity flex items-center gap-3" style={{ width: '140px', flex: 1 }}>
              <button onClick={() => handleStartEdit(c)} className="text-xs text-blue-600 hover:text-blue-700 font-medium">编辑</button>
              <button onClick={() => setPendingDelete(c)} className="text-xs text-red-500 hover:text-red-600 font-medium">删除</button>
            </div>
          </>
        )}
      />
    </div>
  );
}

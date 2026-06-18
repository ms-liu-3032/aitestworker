import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useApp } from '../../context/AppContext';
import { getProject, updateProject, deleteProject } from '../../services/api';

export default function ProjectSettings() {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const { showToast } = useApp();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [form, setForm] = useState({ projectName: '', description: '' });
  const [originalForm, setOriginalForm] = useState({ projectName: '', description: '' });
  const [deleteInput, setDeleteInput] = useState('');

  useEffect(() => {
    if (!projectId) return;
    setLoading(true);
    getProject(Number(projectId))
      .then(p => {
        const next = { projectName: p.projectName, description: p.description || '' };
        setForm(next);
        setOriginalForm(next);
      })
      .catch(() => showToast('加载项目失败'))
      .finally(() => setLoading(false));
  }, [projectId]);

  const trimmedProjectName = form.projectName.trim();
  const hasChanges = form.projectName !== originalForm.projectName || form.description !== originalForm.description;
  const canDelete = deleteInput.trim() === originalForm.projectName;

  const handleSave = async () => {
    if (!projectId || !trimmedProjectName) return;
    setSaving(true);
    try {
      const next = { projectName: trimmedProjectName, description: form.description };
      await updateProject(Number(projectId), next);
      setForm(next);
      setOriginalForm(next);
      showToast('项目已更新');
    } catch {
      showToast('更新失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!projectId || !canDelete) return;
    setDeleting(true);
    try {
      await deleteProject(Number(projectId));
      showToast('项目已删除');
      navigate('/');
    } catch {
      showToast('删除失败', 'error');
    } finally {
      setDeleting(false);
    }
  }

  if (loading) {
    return (
      <div className="p-4 animate-fade-in sm:p-6">
        <h1 className="text-xl font-bold text-gray-900 tracking-tight mb-4">项目设置</h1>
        <div className="animate-pulse bg-gray-100 rounded h-48 w-full max-w-xl" />
      </div>
    );
  }

  return (
    <div className="p-4 animate-fade-in sm:p-6">
      <div className="mb-6">
        <h1 className="text-xl font-bold text-gray-900 tracking-tight">项目设置</h1>
        <p className="text-sm text-gray-500 mt-1">维护当前项目的基本信息，并在需要时安全地下线项目。</p>
      </div>

      <div className="max-w-xl space-y-6">
        <div className="bg-white rounded-xl border border-gray-200 p-5">
          <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div className="min-w-0">
              <h2 className="text-sm font-semibold text-gray-900">基本信息</h2>
              <p className="text-xs text-gray-500 mt-1">这里的名称和说明会出现在项目大厅、概览页和相关管理列表中。</p>
            </div>
            <span className={`w-fit shrink-0 text-[11px] px-2 py-0.5 rounded font-medium ${hasChanges ? 'bg-amber-50 text-amber-700' : 'bg-emerald-50 text-emerald-700'}`}>
              {hasChanges ? '有未保存修改' : '已保存'}
            </span>
          </div>
          <div className="space-y-4">
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">项目名称</label>
              <input
                type="text"
                value={form.projectName}
                onChange={e => setForm({ ...form, projectName: e.target.value })}
                className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
              />
              <div className="mt-1 text-[11px] text-gray-400">{trimmedProjectName ? `${trimmedProjectName.length} 字` : '项目名称必填'}</div>
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">项目描述</label>
              <textarea
                value={form.description}
                onChange={e => setForm({ ...form, description: e.target.value })}
                rows={3}
                className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none resize-none"
              />
              <div className="mt-1 text-[11px] text-gray-400">用于帮助成员快速判断项目范围和用途。</div>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <button
                onClick={handleSave}
                disabled={saving || !trimmedProjectName || !hasChanges}
                className="bg-slate-900 text-white px-4 py-2 rounded-lg text-sm font-semibold hover:bg-slate-800 transition-colors disabled:opacity-50"
              >
                {saving ? '保存中...' : '保存'}
              </button>
              <button
                onClick={() => setForm(originalForm)}
                disabled={saving || !hasChanges}
                className="px-4 py-2 border border-gray-200 text-gray-700 text-sm font-medium rounded-lg hover:bg-gray-50 transition-colors disabled:opacity-50"
              >
                撤销修改
              </button>
            </div>
          </div>
        </div>

        <div className="bg-white rounded-xl border border-red-200 p-5 space-y-4">
          <div>
            <h2 className="text-sm font-semibold text-red-600 mb-2">危险区域</h2>
            <p className="text-xs text-gray-500">删除项目后，所有相关数据会被标记为已删除，并从默认项目列表中移除。后续如需找回，请到项目大厅的“已删除项目”里恢复。</p>
          </div>

          {!showDeleteConfirm ? (
            <button
              onClick={() => setShowDeleteConfirm(true)}
              className="px-4 py-2 border border-red-300 text-red-600 text-sm font-medium rounded-lg hover:bg-red-50 transition-colors"
            >
              删除项目
            </button>
          ) : (
            <div className="rounded-lg border border-red-200 bg-red-50/50 p-4 space-y-3">
              <div className="text-sm font-medium text-red-700">请再次确认删除项目</div>
              <p className="text-xs text-red-600">
                为避免误删，请输入项目名称 <span className="break-all font-semibold">{originalForm.projectName}</span> 后再执行删除。
              </p>
              <input
                type="text"
                value={deleteInput}
                onChange={e => setDeleteInput(e.target.value)}
                placeholder="输入项目名称以确认删除"
                className="w-full h-9 px-3 bg-white border border-red-200 rounded-lg text-sm focus:border-red-400 outline-none"
              />
              <div className="flex flex-wrap items-center gap-2">
                <button
                  onClick={handleDelete}
                  disabled={!canDelete || deleting}
                  className="px-4 py-2 bg-red-600 text-white text-sm font-semibold rounded-lg hover:bg-red-700 transition-colors disabled:opacity-50"
                >
                  {deleting ? '删除中...' : '确认删除'}
                </button>
                <button
                  onClick={() => {
                    setShowDeleteConfirm(false);
                    setDeleteInput('');
                  }}
                  disabled={deleting}
                  className="px-4 py-2 border border-gray-200 text-gray-700 text-sm font-medium rounded-lg hover:bg-white transition-colors disabled:opacity-50"
                >
                  取消
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

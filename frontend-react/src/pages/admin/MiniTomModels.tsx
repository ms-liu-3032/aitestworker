import { useEffect, useMemo, useState } from 'react';
import Modal from '../../components/Modal';
import { useApp } from '../../context/AppContext';
import {
  confirmAdminTomModel,
  deprecateAdminTomModel,
  getAdminTomModelReferences,
  listAdminTomModels,
  listProjects,
  rejectAdminTomModel,
  restoreAdminTomModel,
  type Project,
  type TestObjectModel,
} from '../../services/api';

const modelTypeOptions = ['MODULE', 'PAGE', 'FIELD', 'ROLE', 'ACTION', 'FLOW', 'STATE', 'ASSERTION'];
const sourceTypeOptions = ['TRACE_SUMMARY', 'MANUAL_IMPORT', 'PATTERN'];
const statusOptions = ['CANDIDATE', 'ACTIVE', 'DEPRECATED', 'REJECTED'];

type ActionMode = 'reject' | 'deprecate' | null;

function formatDateTime(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}

function scopeLabel(scope?: string | null) {
  return scope === 'SYSTEM' ? '系统级' : '项目级';
}

function modelTypeLabel(value?: string | null) {
  const labels: Record<string, string> = {
    MODULE: '模块',
    PAGE: '页面',
    FIELD: '字段',
    ROLE: '角色',
    ACTION: '动作',
    FLOW: '流程',
    STATE: '状态',
    ASSERTION: '断言',
  };
  return value ? labels[value] || value : '-';
}

function sourceTypeLabel(value?: string | null) {
  const labels: Record<string, string> = {
    TRACE_SUMMARY: '轨迹摘要',
    MANUAL_IMPORT: '手工导入',
    PATTERN: '模式沉淀',
  };
  return value ? labels[value] || value : '-';
}

function statusLabel(value?: string | null) {
  const labels: Record<string, string> = {
    CANDIDATE: '待确认',
    ACTIVE: '生效中',
    DEPRECATED: '已弃用',
    REJECTED: '已驳回',
  };
  return value ? labels[value] || value : '-';
}

function statusBadgeClass(status?: string | null) {
  switch (status) {
    case 'ACTIVE':
      return 'bg-emerald-50 text-emerald-700';
    case 'CANDIDATE':
      return 'bg-amber-50 text-amber-700';
    case 'DEPRECATED':
      return 'bg-gray-100 text-gray-700';
    case 'REJECTED':
      return 'bg-rose-50 text-rose-700';
    default:
      return 'bg-gray-100 text-gray-600';
  }
}

type ReferenceItem = {
  taskId: number;
  taskName: string;
  status: string;
  createdAt: string;
};

export default function MiniTomModels() {
  const { showToast } = useApp();
  const [projects, setProjects] = useState<Project[]>([]);
  const [models, setModels] = useState<TestObjectModel[]>([]);
  const [loading, setLoading] = useState(true);
  const [referencesLoading, setReferencesLoading] = useState(false);
  const [references, setReferences] = useState<ReferenceItem[]>([]);
  const [keyword, setKeyword] = useState('');
  const [projectFilter, setProjectFilter] = useState('');
  const [modelTypeFilter, setModelTypeFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [sourceTypeFilter, setSourceTypeFilter] = useState('');
  const [businessDomainFilter, setBusinessDomainFilter] = useState('');
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [actingId, setActingId] = useState<number | null>(null);
  const [actionMode, setActionMode] = useState<ActionMode>(null);
  const [actionReason, setActionReason] = useState('');

  const loadModels = async (silent = false) => {
    if (!silent) {
      setLoading(true);
    }
    try {
      const result = await listAdminTomModels({
        projectId: projectFilter ? Number(projectFilter) : undefined,
        modelType: modelTypeFilter || undefined,
        status: statusFilter || undefined,
        sourceType: sourceTypeFilter || undefined,
        businessDomain: businessDomainFilter.trim() || undefined,
      });
      setModels(result);
      setSelectedId(prev => {
        if (result.some(item => item.id === prev)) return prev;
        return result[0]?.id ?? null;
      });
    } catch (error: any) {
      showToast(error.message || '加载 TOM 模型失败', 'error');
    } finally {
      if (!silent) {
        setLoading(false);
      }
    }
  };

  useEffect(() => {
    listProjects({ page: 1, size: 200 })
      .then(result => setProjects(result.items))
      .catch(() => {});
  }, []);

  useEffect(() => {
    void loadModels();
  }, [projectFilter, modelTypeFilter, statusFilter, sourceTypeFilter, businessDomainFilter]);

  const filteredModels = useMemo(() => {
    const term = keyword.trim().toLowerCase();
    if (!term) return models;
    return models.filter(model => {
      const values = [
        model.name,
        model.modelType,
        model.description || '',
        model.scope,
        model.businessDomain || '',
        model.sourceType || '',
        model.validityLabel || '',
        statusLabel(model.status),
      ];
      return values.some(value => value.toLowerCase().includes(term));
    });
  }, [models, keyword]);

  const selectedModel = filteredModels.find(model => model.id === selectedId)
    || models.find(model => model.id === selectedId)
    || null;

  useEffect(() => {
    if (!selectedModel) {
      setReferences([]);
      return;
    }
    setReferencesLoading(true);
    getAdminTomModelReferences(selectedModel.id)
      .then(setReferences)
      .catch(() => setReferences([]))
      .finally(() => setReferencesLoading(false));
  }, [selectedModel?.id]);

  const totalCount = models.length;
  const candidateCount = models.filter(model => model.status === 'CANDIDATE').length;
  const activeCount = models.filter(model => model.status === 'ACTIVE').length;
  const deprecatedCount = models.filter(model => model.status === 'DEPRECATED').length;
  const projectNameMap = useMemo(
    () => new Map(projects.map(project => [project.id, project.projectName])),
    [projects]
  );

  const updateModelInState = (updated: TestObjectModel) => {
    setModels(prev => prev.map(item => (item.id === updated.id ? { ...item, ...updated } : item)));
  };

  const selectNextCandidate = (excludeId: number) => {
    const idx = filteredModels.findIndex(m => m.id === excludeId);
    const candidates = filteredModels.filter(m => m.status === 'CANDIDATE' && m.id !== excludeId);
    if (candidates.length > 0) {
      const next = candidates.find(m => filteredModels.indexOf(m) > idx) || candidates[0];
      setSelectedId(next.id);
    } else {
      setSelectedId(null);
    }
  };

  const handleConfirm = async (model: TestObjectModel) => {
    setActingId(model.id);
    try {
      const updated = await confirmAdminTomModel(model.id);
      updateModelInState(updated);
      selectNextCandidate(model.id);
      showToast(`已确认「${model.name}」`);
    } catch (error: any) {
      showToast(error.message || '确认失败', 'error');
    } finally {
      setActingId(null);
    }
  };

  const handleRestore = async (model: TestObjectModel) => {
    setActingId(model.id);
    try {
      const updated = await restoreAdminTomModel(model.id);
      const prevId = model.id;
      updateModelInState(updated);
      selectNextCandidate(prevId);
      showToast(`已恢复「${model.name}」`);
    } catch (error: any) {
      showToast(error.message || '恢复失败', 'error');
    } finally {
      setActingId(null);
    }
  };

  const handleSubmitAction = async () => {
    if (!selectedModel || !actionMode) return;
    setActingId(selectedModel.id);
    try {
      const updated = actionMode === 'reject'
        ? await rejectAdminTomModel(selectedModel.id, actionReason.trim() || undefined)
        : await deprecateAdminTomModel(selectedModel.id, actionReason.trim() || undefined);
      const prevId = selectedModel.id;
      updateModelInState(updated);
      selectNextCandidate(prevId);
      showToast(actionMode === 'reject' ? `已驳回「${selectedModel.name}」` : `已弃用「${selectedModel.name}」`);
      setActionMode(null);
      setActionReason('');
    } catch (error: any) {
      showToast(error.message || '操作失败', 'error');
    } finally {
      setActingId(null);
    }
  };

  return (
    <div className="space-y-5 p-4 animate-fade-in sm:p-6">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-bold text-gray-900 tracking-tight">Mini-TOM 公共模型</h1>
          <p className="text-sm text-gray-500 mt-1">
            在这里按项目、类型、状态和来源盘点 TOM 模型，并对候选、弃用模型做确认、驳回、恢复等管理动作。
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2 lg:justify-end">
          <button
            onClick={() => {
              setProjectFilter('');
              setModelTypeFilter('');
              setStatusFilter('');
              setSourceTypeFilter('');
              setBusinessDomainFilter('');
              setKeyword('');
            }}
            className="min-h-10 shrink-0 rounded-lg border border-gray-200 px-3 py-2 text-sm font-medium text-gray-700 transition-colors hover:border-gray-300 hover:bg-gray-50"
          >
            重置筛选
          </button>
          <button
            onClick={() => void loadModels()}
            className="min-h-10 shrink-0 rounded-lg border border-gray-200 px-3 py-2 text-sm font-medium text-gray-700 transition-colors hover:border-gray-300 hover:bg-gray-50"
          >
            刷新列表
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs font-medium text-gray-500">模型总数</div>
          <div className="text-2xl font-bold text-gray-900 mt-2">{totalCount}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs font-medium text-gray-500">待确认候选</div>
          <div className="text-2xl font-bold text-amber-700 mt-2">{candidateCount}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs font-medium text-gray-500">生效模型</div>
          <div className="text-2xl font-bold text-emerald-700 mt-2">{activeCount}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs font-medium text-gray-500">已弃用</div>
          <div className="text-2xl font-bold text-gray-700 mt-2">{deprecatedCount}</div>
        </div>
      </div>

      <div className="bg-white rounded-xl border border-gray-200 p-4">
        <div className="grid grid-cols-1 md:grid-cols-3 xl:grid-cols-6 gap-3">
          <select
            value={projectFilter}
            onChange={e => setProjectFilter(e.target.value)}
            className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
          >
            <option value="">全部项目</option>
            {projects.map(project => (
              <option key={project.id} value={project.id}>{project.projectName}</option>
            ))}
          </select>
          <select
            value={modelTypeFilter}
            onChange={e => setModelTypeFilter(e.target.value)}
            className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
          >
            <option value="">全部类型</option>
            {modelTypeOptions.map(option => (
              <option key={option} value={option}>{modelTypeLabel(option)}</option>
            ))}
          </select>
          <select
            value={statusFilter}
            onChange={e => setStatusFilter(e.target.value)}
            className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
          >
            <option value="">全部状态</option>
            {statusOptions.map(option => (
              <option key={option} value={option}>{statusLabel(option)}</option>
            ))}
          </select>
          <select
            value={sourceTypeFilter}
            onChange={e => setSourceTypeFilter(e.target.value)}
            className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
          >
            <option value="">全部来源</option>
            {sourceTypeOptions.map(option => (
              <option key={option} value={option}>{sourceTypeLabel(option)}</option>
            ))}
          </select>
          <input
            type="text"
            value={businessDomainFilter}
            onChange={e => setBusinessDomainFilter(e.target.value)}
            placeholder="按业务领域筛选"
            className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
          />
          <input
            type="text"
            value={keyword}
            onChange={e => setKeyword(e.target.value)}
            placeholder="搜索名称、描述、有效性标签..."
            className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
          />
        </div>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-[1.25fr_1fr] gap-6">
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
          <div className="flex items-center justify-between gap-3 border-b border-gray-100 px-5 py-4">
            <h2 className="text-sm font-semibold text-gray-900">模型列表</h2>
            <span className="text-xs text-gray-400">{filteredModels.length} / {models.length}</span>
          </div>

          {loading ? (
            <div className="divide-y divide-gray-100">
              {Array.from({ length: 6 }).map((_, index) => (
                <div key={index} className="px-5 py-4 animate-pulse">
                  <div className="h-4 bg-gray-100 rounded w-1/3 mb-2" />
                  <div className="h-3 bg-gray-100 rounded w-2/3 mb-2" />
                  <div className="h-3 bg-gray-100 rounded w-1/2" />
                </div>
              ))}
            </div>
          ) : filteredModels.length === 0 ? (
            <div className="px-5 py-12 text-center text-gray-400 text-sm">
              {models.length === 0 ? '当前筛选条件下还没有 TOM 模型' : '没有匹配的 TOM 模型，请调整筛选条件'}
            </div>
          ) : (
            <div className="divide-y divide-gray-100">
              {filteredModels.map(model => {
                const active = selectedModel?.id === model.id;
                const projectName = model.projectId ? projectNameMap.get(model.projectId) : null;
                return (
                  <button
                    key={model.id}
                    onClick={() => setSelectedId(model.id)}
                    className={`w-full text-left px-5 py-4 transition-colors ${active ? 'bg-gray-50' : 'hover:bg-gray-50/70'}`}
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0 flex-1">
                        <div className="text-sm font-semibold text-gray-900 truncate">{model.name}</div>
                        <div className="text-xs text-gray-500 mt-1 truncate">
                          {modelTypeLabel(model.modelType)} · {sourceTypeLabel(model.sourceType)} · {scopeLabel(model.scope)}
                        </div>
                      </div>
                      <span className={`text-[11px] font-medium px-2 py-0.5 rounded flex-shrink-0 ${statusBadgeClass(model.status)}`}>
                        {statusLabel(model.status)}
                      </span>
                    </div>
                    <div className="mt-2 break-words text-xs text-gray-500 line-clamp-2">{model.description || '暂无描述'}</div>
                    <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-gray-400">
                      <span>{model.businessDomain || '未标注业务领域'}</span>
                      <span>{projectName || (model.projectId ? `项目 #${model.projectId}` : '系统级公共模型')}</span>
                      <span>{formatDateTime(model.updatedAt)}</span>
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
          <div className="flex flex-col gap-3 border-b border-gray-100 px-5 py-4 sm:flex-row sm:items-start sm:justify-between">
            <h2 className="text-sm font-semibold text-gray-900">模型详情</h2>
            {selectedModel && (
              <div className="flex flex-wrap items-center gap-2 sm:justify-end">
                {selectedModel.status === 'CANDIDATE' && (
                  <>
                    <button
                      onClick={() => void handleConfirm(selectedModel)}
                      disabled={actingId === selectedModel.id}
                      className="px-3 py-1.5 text-xs font-medium text-white bg-emerald-600 rounded-lg hover:bg-emerald-700 disabled:opacity-60"
                    >
                      {actingId === selectedModel.id ? '确认中...' : '确认'}
                    </button>
                    <button
                      onClick={() => {
                        setActionMode('reject');
                        setActionReason('');
                      }}
                      disabled={actingId === selectedModel.id}
                      className="px-3 py-1.5 text-xs font-medium text-rose-700 border border-rose-200 rounded-lg hover:bg-rose-50 disabled:opacity-60"
                    >
                      驳回
                    </button>
                  </>
                )}
                {selectedModel.status === 'ACTIVE' && (
                  <button
                    onClick={() => {
                      setActionMode('deprecate');
                      setActionReason(selectedModel.deprecatedReason || '');
                    }}
                    disabled={actingId === selectedModel.id}
                    className="px-3 py-1.5 text-xs font-medium text-amber-700 border border-amber-200 rounded-lg hover:bg-amber-50 disabled:opacity-60"
                  >
                    弃用
                  </button>
                )}
                {selectedModel.status === 'DEPRECATED' && (
                  <button
                    onClick={() => void handleRestore(selectedModel)}
                    disabled={actingId === selectedModel.id}
                    className="px-3 py-1.5 text-xs font-medium text-gray-700 border border-gray-200 rounded-lg hover:bg-gray-50 disabled:opacity-60"
                  >
                    {actingId === selectedModel.id ? '恢复中...' : '恢复'}
                  </button>
                )}
              </div>
            )}
          </div>

          {!selectedModel ? (
            <div className="px-5 py-12 text-center text-gray-400 text-sm">
              从左侧选择一个模型后，可在这里查看详细属性、引用关系和当前可执行的管理动作。
            </div>
          ) : (
            <div className="p-5 space-y-4">
              <div>
                <div className="flex flex-wrap items-center gap-2">
                  <div className="break-words text-lg font-semibold text-gray-900">{selectedModel.name}</div>
                  <span className={`text-[11px] font-medium px-2 py-0.5 rounded ${statusBadgeClass(selectedModel.status)}`}>
                    {statusLabel(selectedModel.status)}
                  </span>
                </div>
                <div className="mt-1 break-words text-sm text-gray-500">{selectedModel.description || '暂无描述'}</div>
              </div>

              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <div className="bg-gray-50 rounded-lg p-3">
                  <div className="text-[11px] text-gray-500">模型类型</div>
                  <div className="text-sm font-medium text-gray-900 mt-1">{modelTypeLabel(selectedModel.modelType)}</div>
                </div>
                <div className="bg-gray-50 rounded-lg p-3">
                  <div className="text-[11px] text-gray-500">来源类型</div>
                  <div className="text-sm font-medium text-gray-900 mt-1">{sourceTypeLabel(selectedModel.sourceType)}</div>
                </div>
                <div className="bg-gray-50 rounded-lg p-3">
                  <div className="text-[11px] text-gray-500">生效范围</div>
                  <div className="text-sm font-medium text-gray-900 mt-1">{scopeLabel(selectedModel.scope)}</div>
                </div>
                <div className="bg-gray-50 rounded-lg p-3">
                  <div className="text-[11px] text-gray-500">业务领域</div>
                  <div className="text-sm font-medium text-gray-900 mt-1">{selectedModel.businessDomain || '-'}</div>
                </div>
              </div>

              <div className="space-y-3 text-sm">
                <div className="flex items-start justify-between gap-3">
                  <span className="text-gray-500">所属项目</span>
                  <span className="break-words text-right text-gray-900">
                    {selectedModel.projectId ? projectNameMap.get(selectedModel.projectId) || `项目 #${selectedModel.projectId}` : '系统级公共模型'}
                  </span>
                </div>
                <div className="flex items-start justify-between gap-3">
                  <span className="text-gray-500">有效性标签</span>
                  <span className="break-words text-right text-gray-900">{selectedModel.validityLabel || '-'}</span>
                </div>
                <div className="flex items-start justify-between gap-3">
                  <span className="text-gray-500">优先级</span>
                  <span className="text-gray-900 text-right">{selectedModel.priority ?? '-'}</span>
                </div>
                <div className="flex items-start justify-between gap-3">
                  <span className="text-gray-500">需人工确认</span>
                  <span className="text-gray-900 text-right">{selectedModel.requiresHumanConfirm ? '是' : '否'}</span>
                </div>
                <div className="flex items-start justify-between gap-3">
                  <span className="text-gray-500">来源引用</span>
                  <span className="break-all text-right text-gray-900">{selectedModel.sourceRefId ?? '-'}</span>
                </div>
                <div className="flex items-start justify-between gap-3">
                  <span className="text-gray-500">最近更新</span>
                  <span className="text-gray-900 text-right">{formatDateTime(selectedModel.updatedAt)}</span>
                </div>
              </div>

              {selectedModel.deprecatedReason && (
                <div className="rounded-lg border border-amber-200 bg-amber-50 p-3">
                  <div className="text-[11px] font-medium text-amber-700 mb-1">弃用说明</div>
                  <div className="text-xs text-amber-800 whitespace-pre-wrap break-words">{selectedModel.deprecatedReason}</div>
                </div>
              )}

              {selectedModel.sourceContext && (
                <div className="rounded-lg border border-gray-200 bg-gray-50 p-3">
                  <div className="text-[11px] font-medium text-gray-500 mb-1">来源上下文</div>
                  <div className="text-xs text-gray-700 whitespace-pre-wrap break-words">{selectedModel.sourceContext}</div>
                </div>
              )}

              {selectedModel.propertiesJson && (
                <div className="rounded-lg border border-gray-200 bg-gray-50 p-3">
                  <div className="text-[11px] font-medium text-gray-500 mb-1">属性结构</div>
                  <pre className="text-xs text-gray-700 whitespace-pre-wrap break-words font-mono">{selectedModel.propertiesJson}</pre>
                </div>
              )}

              <div className="rounded-lg border border-gray-200 bg-white">
                <div className="flex items-center justify-between gap-3 border-b border-gray-100 px-3 py-2">
                  <div className="text-[11px] font-medium text-gray-500">引用任务</div>
                  <button
                    onClick={() => selectedModel && void getAdminTomModelReferences(selectedModel.id).then(setReferences).catch(() => showToast('刷新引用失败', 'error'))}
                    className="text-[11px] text-gray-500 hover:text-gray-700"
                  >
                    刷新引用
                  </button>
                </div>
                {referencesLoading ? (
                  <div className="px-3 py-6 text-center text-xs text-gray-400">正在加载引用任务...</div>
                ) : references.length === 0 ? (
                  <div className="px-3 py-6 text-center text-xs text-gray-400">当前还没有发现引用这个模型的生成任务。</div>
                ) : (
                  <div className="divide-y divide-gray-100">
                    {references.slice(0, 6).map(reference => (
                      <div key={reference.taskId} className="px-3 py-3">
                        <div className="break-words text-sm font-medium text-gray-900">{reference.taskName || `任务 #${reference.taskId}`}</div>
                        <div className="text-[11px] text-gray-500 mt-1">
                          #{reference.taskId} · {statusLabel(reference.status)} · {formatDateTime(reference.createdAt)}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      </div>

      {actionMode && selectedModel && (
        <Modal
          title={actionMode === 'reject' ? '驳回 TOM 候选' : '弃用 TOM 模型'}
          onClose={() => {
            if (!actingId) {
              setActionMode(null);
              setActionReason('');
            }
          }}
          footer={(
            <div className="flex justify-end gap-2">
              <button
                onClick={() => {
                  setActionMode(null);
                  setActionReason('');
                }}
                disabled={actingId === selectedModel.id}
                className="px-4 py-2 text-sm font-medium text-gray-700 border border-gray-200 rounded-lg hover:bg-gray-50 disabled:opacity-60"
              >
                取消
              </button>
              <button
                onClick={() => void handleSubmitAction()}
                disabled={actingId === selectedModel.id}
                className={`px-4 py-2 text-sm font-medium text-white rounded-lg disabled:opacity-60 ${actionMode === 'reject' ? 'bg-rose-600 hover:bg-rose-700' : 'bg-amber-600 hover:bg-amber-700'}`}
              >
                {actingId === selectedModel.id ? '提交中...' : actionMode === 'reject' ? '确认驳回' : '确认弃用'}
              </button>
            </div>
          )}
        >
          <div className="space-y-4 text-sm text-gray-600">
            <div className="rounded-lg border border-gray-200 bg-gray-50 p-3">
              <div className="text-xs text-gray-500">目标模型</div>
              <div className="text-sm font-semibold text-gray-900 mt-1">{selectedModel.name}</div>
              <div className="text-xs text-gray-500 mt-1">
                {modelTypeLabel(selectedModel.modelType)} · {statusLabel(selectedModel.status)} · {sourceTypeLabel(selectedModel.sourceType)}
              </div>
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">
                {actionMode === 'reject' ? '驳回原因（可选）' : '弃用说明（可选）'}
              </label>
              <textarea
                rows={4}
                value={actionReason}
                onChange={e => setActionReason(e.target.value)}
                placeholder={actionMode === 'reject' ? '例如：命名含糊、属性不完整、与现有模型重复' : '例如：已有更高质量版本、业务范围已迁移'}
                className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none resize-none"
              />
            </div>
            <p className="text-xs text-gray-500">
              {actionMode === 'reject'
                ? '驳回后，这条候选不会进入生效目录，但仍可保留审核痕迹。'
                : '弃用后，这条模型会从“生效中”进入“已弃用”，便于后续回滚或替换。'}
            </p>
          </div>
        </Modal>
      )}
    </div>
  );
}

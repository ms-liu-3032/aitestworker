import { useEffect, useMemo, useState } from 'react';
import { useApp } from '../../context/AppContext';
import {
  deprecateAdminAsset,
  listAdminFormalCases,
  listAdminKnowledge,
  listAdminSkills,
  listAdminSummaries,
  listAdminTestPoints,
  listAdminTools,
  listProjects,
  restoreAdminAsset,
  type Project,
} from '../../services/api';
import WikiAssetManager from './WikiAssetManager';
import { statusLabel } from '../../utils/displayLabels';

type TabKey = 'cases' | 'points' | 'knowledge' | 'wiki' | 'summaries' | 'skills' | 'tools';

const tabs: { key: TabKey; label: string; apiType: string }[] = [
  { key: 'cases', label: '正式用例', apiType: 'formal-cases' },
  { key: 'points', label: '测试点', apiType: 'test-points' },
  { key: 'knowledge', label: '知识片段', apiType: 'knowledge' },
  { key: 'wiki', label: 'Wiki', apiType: 'wiki' },
  { key: 'summaries', label: '轨迹摘要', apiType: 'summaries' },
  { key: 'skills', label: 'Skill', apiType: 'skills' },
  { key: 'tools', label: 'Tool', apiType: 'tools' },
];

type AssetItem = {
  id: number;
  projectId?: number;
  caseNo?: string;
  caseTitle?: string;
  moduleName?: string;
  priority?: string;
  pointContent?: string;
  title?: string;
  assetRefType?: string;
  overview?: string;
  summaryScope?: string;
  validityLabel?: string;
  confidenceLabel?: string;
  skillName?: string;
  toolName?: string;
  description?: string;
  status?: string;
  caseStatus?: string;
  createdAt?: string;
  updatedAt?: string;
};

const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'ACTIVE', label: '有效' },
  { value: 'DEPRECATED', label: '已弃用' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'CANDIDATE', label: '候选' },
  { value: 'REJECTED', label: '已驳回' },
];

function getDisplayName(item: AssetItem) {
  return item.caseTitle || item.skillName || item.toolName || item.overview || item.title || item.pointContent || `#${item.id}`;
}

function getSubline(item: AssetItem) {
  return item.description || item.moduleName || item.summaryScope || item.assetRefType || item.caseNo || '';
}

function getItemStatus(item: AssetItem) {
  return item.status || item.caseStatus || '未知';
}

function formatDate(value?: string) {
  return value ? new Date(value).toLocaleDateString('zh-CN') : '-';
}

function asArray<T>(value: unknown): T[] {
  return Array.isArray(value) ? value : [];
}

export default function AssetLibrary() {
  const { showToast } = useApp();
  const [tab, setTab] = useState<TabKey>('cases');
  const [data, setData] = useState<AssetItem[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);
  const [actingId, setActingId] = useState<number | null>(null);
  const [projectFilter, setProjectFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [keyword, setKeyword] = useState('');

  const activeTab = tabs.find(item => item.key === tab)!;

  useEffect(() => {
    listProjects({ page: 1, size: 200 })
      .then(result => setProjects(asArray<Project>((result as any)?.items)))
      .catch(() => {});
  }, []);

  const loadData = async () => {
    if (tab === 'wiki') {
      setData([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const loader = {
        cases: listAdminFormalCases,
        points: listAdminTestPoints,
        knowledge: listAdminKnowledge,
        summaries: listAdminSummaries,
        skills: listAdminSkills,
        tools: listAdminTools,
        wiki: listAdminKnowledge,
      }[tab];

      const result = await loader({
        projectId: projectFilter ? Number(projectFilter) : undefined,
        status: statusFilter || undefined,
      } as any);
      setData(asArray<AssetItem>(result));
    } catch (error: any) {
      showToast(error.message || '加载失败', 'error');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [tab, projectFilter, statusFilter]);

  const filteredData = useMemo(() => {
    const term = keyword.trim().toLowerCase();
    if (!term) return data;
    return data.filter(item => {
      const values = [
        getDisplayName(item),
        getSubline(item),
        item.priority,
        item.validityLabel,
        item.confidenceLabel,
      ];
      return values.some(value => value && value.toLowerCase().includes(term));
    });
  }, [data, keyword]);

  const projectNameMap = useMemo(
    () => new Map(projects.map(project => [project.id, project.projectName])),
    [projects]
  );

  const handleDeprecate = async (item: AssetItem) => {
    setActingId(item.id);
    try {
      await deprecateAdminAsset(activeTab.apiType, item.id);
      setData(prev =>
        prev.map(entry =>
          entry.id === item.id
            ? {
                ...entry,
                status: 'DEPRECATED',
                caseStatus: 'DEPRECATED',
              }
            : entry
        )
      );
      showToast('已弃用');
    } catch (error: any) {
      showToast(error.message || '弃用失败', 'error');
    } finally {
      setActingId(null);
    }
  };

  const handleRestore = async (item: AssetItem) => {
    setActingId(item.id);
    try {
      await restoreAdminAsset(activeTab.apiType, item.id);
      setData(prev =>
        prev.map(entry =>
          entry.id === item.id
            ? {
                ...entry,
                status: 'ACTIVE',
                caseStatus: 'ACTIVE',
              }
            : entry
        )
      );
      showToast('已恢复');
    } catch (error: any) {
      showToast(error.message || '恢复失败', 'error');
    } finally {
      setActingId(null);
    }
  };

  return (
    <div className="p-4 animate-fade-in sm:p-6">
      <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-bold text-gray-900 tracking-tight">正式测试资产库</h1>
          <p className="text-sm text-gray-500 mt-1">按资产类型集中查看、筛选并管理正式资产状态。</p>
        </div>
        <button
          onClick={loadData}
          className="min-h-10 shrink-0 rounded-lg border border-gray-200 px-3 py-2 text-sm font-medium text-gray-700 transition-colors hover:border-gray-300 hover:bg-gray-50"
        >
          刷新列表
        </button>
      </div>

      <div className="mb-4 flex w-fit max-w-full flex-wrap items-center gap-1 rounded-lg bg-gray-100 p-0.5">
        {tabs.map(item => (
          <button
            key={item.key}
            onClick={() => setTab(item.key)}
            className={`px-3 py-1.5 text-xs font-medium rounded-md transition-all ${
              tab === item.key ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'
            }`}
          >
            {item.label}
          </button>
        ))}
      </div>

      {tab === 'wiki' ? <WikiAssetManager /> : <>

      <div className="bg-white rounded-xl border border-gray-200 p-4 mb-4">
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
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
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">状态</label>
            <select
              value={statusFilter}
              onChange={e => setStatusFilter(e.target.value)}
              className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            >
              {statusOptions.map(option => (
                <option key={option.value || 'all'} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">关键词</label>
            <input
              type="text"
              value={keyword}
              onChange={e => setKeyword(e.target.value)}
              placeholder="搜索名称、描述、模块、范围..."
              className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            />
          </div>
        </div>
      </div>

      {loading ? (
        <div className="space-y-2">
          {[1, 2, 3].map(i => <div key={i} className="animate-pulse bg-gray-100 rounded h-12 w-full" />)}
        </div>
      ) : filteredData.length === 0 ? (
        <div className="bg-white rounded-xl border border-gray-200 p-12 text-center text-gray-400 text-sm">
          暂无匹配资产
        </div>
      ) : (
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
          {filteredData.map(item => {
            const status = getItemStatus(item);
            const deprecated = status === 'DEPRECATED';
            return (
              <div key={item.id} className="flex flex-col gap-3 px-5 py-3 border-b border-gray-100 last:border-b-0 sm:flex-row sm:items-center sm:gap-4">
                <div className="flex-1 min-w-0">
                  <div className="text-sm font-medium text-gray-900 truncate">{getDisplayName(item)}</div>
                  <div className="flex flex-wrap items-center gap-2 mt-1 text-xs text-gray-500">
                    {item.projectId ? <span>项目：{projectNameMap.get(item.projectId) || `#${item.projectId}`}</span> : null}
                    {getSubline(item) ? <span className="max-w-full break-words sm:max-w-[420px]">{getSubline(item)}</span> : null}
                    {item.priority ? <span>优先级：{item.priority}</span> : null}
                    {item.validityLabel ? <span>有效性：{item.validityLabel}</span> : null}
                    {item.confidenceLabel ? <span>置信度：{item.confidenceLabel}</span> : null}
                  </div>
                </div>
                <span className={`w-fit shrink-0 text-[11px] font-medium px-2 py-0.5 rounded ${
                  deprecated ? 'bg-red-50 text-red-700' : 'bg-gray-100 text-gray-600'
                }`}>
                  {statusLabel(status)}
                </span>
                <span className="shrink-0 text-xs font-mono text-gray-400">
                  {formatDate(item.updatedAt || item.createdAt)}
                </span>
                <button
                  onClick={() => (deprecated ? handleRestore(item) : handleDeprecate(item))}
                  disabled={actingId === item.id}
                  className={`w-fit shrink-0 px-3 py-1.5 text-xs font-medium rounded transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
                    deprecated
                      ? 'text-green-700 bg-green-50 hover:bg-green-100'
                      : 'text-red-600 bg-red-50 hover:bg-red-100'
                  }`}
                >
                  {actingId === item.id ? '处理中...' : deprecated ? '恢复' : '弃用'}
                </button>
              </div>
            );
          })}
        </div>
      )}
      </>}
    </div>
  );
}

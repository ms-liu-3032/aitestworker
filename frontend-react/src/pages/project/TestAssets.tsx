import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useApp } from '../../context/AppContext';
import {
  listFormalCases,
  listLocalCases,
  submitLocalCase,
  listAdminSummaries,
  listAdminSkills,
  listAdminTools,
  type FormalCase,
  type LocalCaseDraft,
} from '../../services/api';

type TabKey = 'summaries' | 'drafts' | 'formal' | 'skills' | 'tools';

type SummaryAsset = {
  id: number;
  projectId: number;
  traceGroupId?: number;
  summaryScope?: string;
  overview?: string;
  status?: string;
  validityLabel?: string;
  confidenceLabel?: string;
  createdAt?: string;
};

type SkillAsset = {
  id: number;
  projectId: number;
  skillName: string;
  description?: string;
  status?: string;
  createdAt?: string;
};

type ToolAsset = {
  id: number;
  projectId: number;
  toolName: string;
  description?: string;
  status?: string;
  createdAt?: string;
};

const tabs: Array<{ key: TabKey; label: string }> = [
  { key: 'summaries', label: '轨迹摘要' },
  { key: 'drafts', label: '草稿用例' },
  { key: 'formal', label: '正式用例' },
  { key: 'skills', label: 'Skill 模板' },
  { key: 'tools', label: 'Tool 模板' },
];

function fmtDate(value?: string | null) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}

function StatCard({ title, value, subtitle }: { title: string; value: string; subtitle: string }) {
  return (
    <div className="bg-white rounded-xl border border-gray-200 p-4">
      <div className="text-[11px] uppercase tracking-wider text-gray-400 font-semibold mb-1">{title}</div>
      <div className="text-lg font-bold text-gray-900">{value}</div>
      <div className="text-xs text-gray-500 mt-1">{subtitle}</div>
    </div>
  );
}

function localCaseSourceLabel(sourceType?: string | null) {
  return sourceType === 'TRACE' ? '轨迹回放' : '需求生成';
}

function localCaseSourceClass(sourceType?: string | null) {
  return sourceType === 'TRACE'
    ? 'bg-purple-50 text-purple-700'
    : 'bg-sky-50 text-sky-700';
}

function asArray<T>(value: unknown): T[] {
  return Array.isArray(value) ? value : [];
}

export default function TestAssets() {
  const { projectId } = useParams<{ projectId: string }>();
  const { showToast } = useApp();
  const pid = Number(projectId);

  const [tab, setTab] = useState<TabKey>('summaries');
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [actingCaseId, setActingCaseId] = useState<number | null>(null);
  const [actionNotice, setActionNotice] = useState<{ title: string; body: string } | null>(null);

  const [localCases, setLocalCases] = useState<LocalCaseDraft[]>([]);
  const [formalCases, setFormalCases] = useState<FormalCase[]>([]);
  const [summaries, setSummaries] = useState<SummaryAsset[]>([]);
  const [skills, setSkills] = useState<SkillAsset[]>([]);
  const [tools, setTools] = useState<ToolAsset[]>([]);

  const loadAssets = async () => {
    if (!pid) return;
    setLoading(true);
    try {
      const [draftsRes, formalRes, summaryRes, skillRes, toolRes] = await Promise.all([
        listLocalCases(pid).catch(() => []),
        listFormalCases(pid).catch(() => []),
        listAdminSummaries({ projectId: pid }).catch(() => []),
        listAdminSkills({ projectId: pid }).catch(() => []),
        listAdminTools({ projectId: pid }).catch(() => []),
      ]);
      setLocalCases(asArray<LocalCaseDraft>(draftsRes));
      setFormalCases(asArray<FormalCase>(formalRes));
      setSummaries(asArray<SummaryAsset>(summaryRes));
      setSkills(asArray<SkillAsset>(skillRes));
      setTools(asArray<ToolAsset>(toolRes));
    } catch (error: any) {
      showToast(error.message || '加载测试资产失败', 'error');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadAssets();
  }, [pid]);

  const activeItems = useMemo(() => {
    switch (tab) {
      case 'summaries':
        return summaries;
      case 'drafts':
        return localCases;
      case 'formal':
        return formalCases;
      case 'skills':
        return skills;
      case 'tools':
        return tools;
      default:
        return [];
    }
  }, [tab, summaries, localCases, formalCases, skills, tools]);

  const filteredItems = useMemo(() => {
    const term = keyword.trim().toLowerCase();
    if (!term) return activeItems;
    return activeItems.filter((item: any) => {
      const values = [
        item.caseTitle,
        item.caseNo,
        item.moduleName,
        item.overview,
        item.summaryScope,
        item.skillName,
        item.toolName,
        item.description,
        item.priority,
        item.status,
        item.caseStatus,
      ];
      return values.some(value => value && String(value).toLowerCase().includes(term));
    });
  }, [activeItems, keyword]);

  const assetStats = useMemo(() => ({
    summaries: summaries.length,
    drafts: localCases.length,
    formal: formalCases.length,
    skills: skills.length,
    tools: tools.length,
  }), [summaries, localCases, formalCases, skills, tools]);

  const handleSubmitDraft = async (caseId: number) => {
    setActingCaseId(caseId);
    try {
      await submitLocalCase(pid, caseId);
      await loadAssets();
      showToast('已提交到正式用例库');
      setTab('formal');
      setActionNotice({
        title: '草稿已进入正式用例库',
        body: '你现在可以继续在正式用例库里检查状态，或回到轨迹页继续沉淀更多摘要、Skill 和 Tool 模板。',
      });
    } catch (error: any) {
      showToast(error.message || '提交失败', 'error');
    } finally {
      setActingCaseId(null);
    }
  };

  const emptyText = {
    summaries: '暂无轨迹摘要，先去测试执行轨迹里生成或确认摘要。',
    drafts: '暂无草稿用例，先去用例生成页或轨迹页生成草稿。',
    formal: '暂无正式用例，确认有效的草稿后会沉淀到这里。',
    skills: '暂无 Skill 模板，确认过的稳定操作链路更适合沉淀到这里。',
    tools: '暂无 Tool 模板，工具型操作流程沉淀后会出现在这里。',
  }[tab];

  return (
    <div className="p-4 animate-fade-in sm:p-6">
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-bold text-gray-900 tracking-tight">测试资产沉淀</h1>
          <p className="text-sm text-gray-500 mt-1">在项目维度查看摘要、草稿、正式用例、Skill 和 Tool 模板的最新沉淀。</p>
        </div>
        <div className="flex flex-wrap items-center gap-3 sm:justify-end">
          <Link to={pid ? `/projects/${pid}/trace` : '#'} className="text-sm text-gray-500 hover:text-gray-900 transition-colors">
            返回轨迹页
          </Link>
          <button
            onClick={loadAssets}
            className="min-h-10 shrink-0 rounded-lg border border-gray-200 px-3 py-2 text-sm font-medium text-gray-700 transition-colors hover:border-gray-300 hover:bg-gray-50"
          >
            刷新资产
          </button>
        </div>
      </div>

      <div className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-5">
        <StatCard title="轨迹摘要" value={String(assetStats.summaries)} subtitle="已沉淀摘要" />
        <StatCard title="草稿用例" value={String(assetStats.drafts)} subtitle="待整理草稿" />
        <StatCard title="正式用例" value={String(assetStats.formal)} subtitle="已提交正式库" />
        <StatCard title="Skill 模板" value={String(assetStats.skills)} subtitle="可复用操作模板" />
        <StatCard title="Tool 模板" value={String(assetStats.tools)} subtitle="可复用工具模板" />
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
            {item.label} <span className="text-[10px] text-gray-400 ml-1">{assetStats[item.key]}</span>
          </button>
        ))}
      </div>

      {actionNotice && (
        <div className="mb-4 rounded-xl border border-sky-200 bg-sky-50 px-4 py-3">
          <div className="text-sm font-semibold text-sky-900">{actionNotice.title}</div>
          <div className="text-xs text-sky-800 mt-1">{actionNotice.body}</div>
          <div className="mt-3 flex flex-wrap items-center gap-3 text-xs">
            <Link to={`/projects/${pid}/formal-cases`} className="text-sky-700 hover:text-sky-900 transition-colors">
              去正式用例库
            </Link>
            <Link to={`/projects/${pid}/trace`} className="text-sky-700 hover:text-sky-900 transition-colors">
              返回轨迹页
            </Link>
            <button onClick={() => setActionNotice(null)} className="text-sky-700 hover:text-sky-900 transition-colors">
              收起提示
            </button>
          </div>
        </div>
      )}

      <div className="bg-white rounded-xl border border-gray-200 p-4 mb-4">
        <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
          <div>
            <div className="text-sm font-semibold text-gray-900">{tabs.find(item => item.key === tab)?.label}</div>
            <div className="text-xs text-gray-500 mt-1">
              当前展示 {filteredItems.length} / {assetStats[tab]} 条，按关键词快速定位需要的资产。
            </div>
          </div>
          <input
            type="text"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="搜索名称、模块、描述、状态..."
            className="w-full md:w-80 h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
          />
        </div>
      </div>

      {loading ? (
        <div className="space-y-2">
          {[1, 2, 3].map(i => <div key={i} className="animate-pulse bg-gray-100 rounded h-16 w-full" />)}
        </div>
      ) : filteredItems.length === 0 ? (
        <div className="bg-white rounded-xl border border-gray-200 p-12 text-center text-gray-400 text-sm">
          {emptyText}
        </div>
      ) : (
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
          {tab === 'summaries' && filteredItems.map((item: any) => (
            <div key={item.id} className="px-5 py-4 border-b border-gray-100 last:border-b-0">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium text-gray-900">{item.overview || `摘要 #${item.id}`}</div>
                  <div className="flex flex-wrap items-center gap-2 mt-1 text-xs text-gray-500">
                    {item.summaryScope && <span>范围：{item.summaryScope}</span>}
                    {item.validityLabel && <span>有效性：{item.validityLabel}</span>}
                    {item.confidenceLabel && <span>置信度：{item.confidenceLabel}</span>}
                    {item.traceGroupId && <span>采集组 #{item.traceGroupId}</span>}
                  </div>
                </div>
                  <div className="flex shrink-0 flex-wrap items-center gap-3 sm:justify-end">
                    <span className="text-[11px] font-medium px-2 py-0.5 rounded bg-gray-100 text-gray-700">{item.status || 'UNKNOWN'}</span>
                    {item.traceGroupId && (
                      <Link
                        to={`/projects/${pid}/trace`}
                        className="text-xs text-sky-600 hover:text-sky-800 transition-colors"
                      >
                        去轨迹页查看
                      </Link>
                    )}
                    <span className="text-xs font-mono text-gray-400">{fmtDate(item.createdAt)}</span>
                  </div>
                </div>
              </div>
          ))}

          {tab === 'drafts' && filteredItems.map((item: any) => (
            <div key={item.id} className="px-5 py-4 border-b border-gray-100 last:border-b-0">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium text-gray-900">{item.caseTitle}</div>
                  <div className="flex flex-wrap items-center gap-2 mt-1 text-xs text-gray-500">
                    <span className={`inline-flex items-center px-2 py-0.5 rounded font-medium ${localCaseSourceClass(item.sourceType)}`}>
                      {localCaseSourceLabel(item.sourceType)}
                    </span>
                    {item.moduleName && <span>模块：{item.moduleName}</span>}
                    {item.priority && <span>优先级：{item.priority}</span>}
                    {item.caseType && <span>类型：{item.caseType}</span>}
                    {item.sessionId && <span>生成会话 #{item.sessionId}</span>}
                  </div>
                  {item.expectedResult && <div className="text-xs text-gray-500 mt-2 line-clamp-2">预期：{item.expectedResult}</div>}
                </div>
                  <div className="flex shrink-0 flex-wrap items-center gap-3 sm:justify-end">
                    <span
                      className={`text-[11px] font-medium px-2 py-0.5 rounded ${
                        item.caseStatus === 'CONFIRMED'
                          ? 'bg-green-50 text-green-700'
                          : item.caseStatus === 'DEPRECATED'
                            ? 'bg-gray-100 text-gray-600'
                            : item.caseStatus === 'SUBMITTED'
                              ? 'bg-blue-50 text-blue-700'
                              : 'bg-yellow-50 text-yellow-700'
                      }`}
                    >
                      {item.caseStatus || 'DRAFT'}
                    </span>
                    {item.caseStatus === 'DEPRECATED' ? (
                      <span className="text-xs text-gray-400">已弃用，不再进入正式库</span>
                    ) : item.caseStatus === 'SUBMITTED' ? (
                      <span className="text-xs text-gray-400">已进入正式库</span>
                    ) : (
                      <button
                        onClick={() => handleSubmitDraft(item.id)}
                        disabled={actingCaseId === item.id}
                        className="px-3 py-1.5 text-xs font-medium rounded bg-green-50 text-green-700 hover:bg-green-100 transition-colors disabled:opacity-50"
                      >
                        {actingCaseId === item.id ? '提交中...' : '提交正式库'}
                      </button>
                    )}
                    <Link
                      to={item.sourceType === 'TRACE' ? `/projects/${pid}/trace` : `/projects/${pid}/generation`}
                      className="text-xs text-gray-500 hover:text-gray-800 transition-colors"
                    >
                      {item.sourceType === 'TRACE' ? '回轨迹页' : '回生成页'}
                    </Link>
                    <Link to={`/projects/${pid}/local-cases`} className="text-xs text-gray-500 hover:text-gray-800 transition-colors">
                      去本地库
                    </Link>
                  </div>
                </div>
            </div>
          ))}

          {tab === 'formal' && filteredItems.map((item: any) => (
            <div key={item.id} className="px-5 py-4 border-b border-gray-100 last:border-b-0">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium text-gray-900">{item.caseTitle}</div>
                  <div className="flex flex-wrap items-center gap-2 mt-1 text-xs text-gray-500">
                    <span>{item.caseNo}</span>
                    {item.moduleName && <span>模块：{item.moduleName}</span>}
                    {item.priority && <span>优先级：{item.priority}</span>}
                    {item.sourceTraceGroupId && <span>来源采集组 #{item.sourceTraceGroupId}</span>}
                  </div>
                  {item.expectedResult && <div className="text-xs text-gray-500 mt-2 line-clamp-2">预期：{item.expectedResult}</div>}
                </div>
                <div className="flex shrink-0 flex-wrap items-center gap-3 sm:justify-end">
                  <span className="text-[11px] font-medium px-2 py-0.5 rounded bg-green-50 text-green-700">{item.caseStatus || 'ACTIVE'}</span>
                  <Link to={`/projects/${pid}/formal-cases`} className="text-xs text-gray-500 hover:text-gray-800 transition-colors">
                    去正式库
                  </Link>
                  <span className="text-xs font-mono text-gray-400">{fmtDate(item.updatedAt)}</span>
                </div>
              </div>
            </div>
          ))}

          {tab === 'skills' && filteredItems.map((item: any) => (
            <div key={item.id} className="px-5 py-4 border-b border-gray-100 last:border-b-0">
                <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                  <div className="min-w-0 flex-1">
                    <div className="text-sm font-medium text-gray-900">{item.skillName}</div>
                    <div className="text-xs text-gray-500 mt-1">{item.description || '暂无说明'}</div>
                  </div>
                  <div className="flex shrink-0 flex-wrap items-center gap-3 sm:justify-end">
                    <span className="text-[11px] font-medium px-2 py-0.5 rounded bg-gray-100 text-gray-700">{item.status || 'ACTIVE'}</span>
                    <Link to={`/projects/${pid}/trace`} className="text-xs text-sky-600 hover:text-sky-800 transition-colors">
                      去轨迹页继续沉淀
                    </Link>
                    <span className="text-xs font-mono text-gray-400">{fmtDate(item.createdAt)}</span>
                  </div>
                </div>
              </div>
          ))}

          {tab === 'tools' && filteredItems.map((item: any) => (
            <div key={item.id} className="px-5 py-4 border-b border-gray-100 last:border-b-0">
                <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                  <div className="min-w-0 flex-1">
                    <div className="text-sm font-medium text-gray-900">{item.toolName}</div>
                    <div className="text-xs text-gray-500 mt-1">{item.description || '暂无说明'}</div>
                  </div>
                  <div className="flex shrink-0 flex-wrap items-center gap-3 sm:justify-end">
                    <span className="text-[11px] font-medium px-2 py-0.5 rounded bg-gray-100 text-gray-700">{item.status || 'ACTIVE'}</span>
                    <Link to={`/projects/${pid}/trace`} className="text-xs text-sky-600 hover:text-sky-800 transition-colors">
                      去轨迹页继续沉淀
                    </Link>
                    <span className="text-xs font-mono text-gray-400">{fmtDate(item.createdAt)}</span>
                  </div>
                </div>
              </div>
          ))}
        </div>
      )}
    </div>
  );
}

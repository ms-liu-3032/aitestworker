import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { listFormalCases, listLocalCases, listTraceGroups, type FormalCase, type LocalCaseDraft, type TraceGroup } from '../../services/api';
import { statusLabel } from '../../utils/displayLabels';

const priorityConfig: Record<string, { bg: string; text: string }> = {
  P0: { bg: 'bg-red-50', text: 'text-red-600' },
  P1: { bg: 'bg-orange-50', text: 'text-orange-600' },
  P2: { bg: 'bg-yellow-50', text: 'text-yellow-600' },
  P3: { bg: 'bg-gray-100', text: 'text-gray-500' },
};

function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleDateString('zh-CN') : '-';
}

export default function ProjectOverview() {
  const { projectId } = useParams<{ projectId: string }>();
  const [groups, setGroups] = useState<TraceGroup[]>([]);
  const [formalCases, setFormalCases] = useState<FormalCase[]>([]);
  const [localCases, setLocalCases] = useState<LocalCaseDraft[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!projectId) return;
    const pid = Number(projectId);
    setLoading(true);
    Promise.all([
      listTraceGroups(pid).catch(() => []),
      listFormalCases(pid).catch(() => []),
      listLocalCases(pid).catch(() => []),
    ]).then(([g, f, lc]) => {
      setGroups(g);
      setFormalCases(f);
      setLocalCases(lc);
    }).finally(() => setLoading(false));
  }, [projectId]);

  const runningGroups = groups.filter(group => group.status === 'RUNNING');
  const stoppedGroups = groups.filter(group => group.status === 'STOPPED');
  const submittedLocalCases = localCases.filter(item => item.caseStatus === 'SUBMITTED');
  const pendingLocalCases = localCases.filter(item => item.caseStatus !== 'SUBMITTED' && item.caseStatus !== 'DEPRECATED');

  const stats = [
    { label: '采集组', value: groups.length, color: 'text-gray-900', sub: `${runningGroups.length} 采集中` },
    { label: '正式用例', value: formalCases.length, color: 'text-blue-600', sub: '已沉淀' },
    { label: '本地草稿', value: pendingLocalCases.length, color: 'text-green-600', sub: `${submittedLocalCases.length} 已提交` },
    { label: '已完成采集', value: stoppedGroups.length, color: 'text-purple-600', sub: '已停止' },
  ];

  const visibleLocalCases = localCases.filter(item => item.caseStatus !== 'DEPRECATED');

  const localCaseStatusLabel = (status?: string) => {
    switch (status) {
      case 'CONFIRMED':
        return '已确认';
      case 'SUBMITTED':
        return '已提交';
      case 'DEPRECATED':
        return '已弃用';
      case 'DRAFT':
      default:
        return '草稿';
    }
  };

  const localCaseStatusClass = (status?: string) => {
    switch (status) {
      case 'CONFIRMED':
        return 'bg-blue-50 text-blue-700';
      case 'SUBMITTED':
        return 'bg-green-50 text-green-700';
      case 'DEPRECATED':
        return 'bg-gray-100 text-gray-500';
      case 'DRAFT':
      default:
        return 'bg-yellow-50 text-yellow-700';
    }
  };

  const localCaseSourceLabel = (sourceType?: string | null) => {
    return sourceType === 'TRACE' ? '轨迹回放' : '需求生成';
  };

  const localCaseSourceClass = (sourceType?: string | null) => {
    return sourceType === 'TRACE'
      ? 'bg-purple-50 text-purple-700'
      : 'bg-sky-50 text-sky-700';
  };

  const quickActions = useMemo(() => [
    {
      title: '开始采集',
      description: '进入轨迹页，录制新流程并产出摘要、用例和规则学习样本。',
      href: projectId ? `/projects/${projectId}/trace` : '#',
      label: '进入轨迹',
    },
    {
      title: '生成用例',
      description: '从需求、附件或会话出发生成测试用例草稿，并直接进入本地用例库。',
      href: projectId ? `/projects/${projectId}/generation` : '#',
      label: '进入生成',
    },
    {
      title: '整理 TOM',
      description: '维护业务模型和测试范围分析结果，帮助后续生成与扫描更准。',
      href: projectId ? `/projects/${projectId}/mini-tom` : '#',
      label: '进入 Mini-TOM',
    },
  ], [projectId]);

  if (loading) {
    return (
      <div className="p-4 animate-fade-in sm:p-6">
        <h1 className="text-xl font-bold text-gray-900 tracking-tight mb-6">项目概览</h1>
        <div className="grid grid-cols-1 xl:grid-cols-4 gap-4 mb-8">
          {[1, 2, 3, 4].map(i => (
            <div key={i} className="bg-white rounded-xl border border-gray-200 p-5">
              <div className="animate-pulse bg-gray-100 rounded h-4 w-16 mb-3" />
              <div className="animate-pulse bg-gray-100 rounded h-8 w-12 mb-2" />
              <div className="animate-pulse bg-gray-100 rounded h-3 w-20" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="p-4 animate-fade-in sm:p-6">
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-bold text-gray-900 tracking-tight">项目概览</h1>
          <p className="text-sm text-gray-500 mt-1">从这里快速进入采集、生成和用例沉淀主链路，同时查看最近进展。</p>
        </div>
        <div className="flex flex-wrap items-center gap-2 sm:justify-end">
          <Link
            to={projectId ? `/projects/${projectId}/trace` : '#'}
            className="min-h-10 shrink-0 rounded-lg border border-gray-200 px-3 py-2 text-sm font-medium text-gray-700 transition-colors hover:border-gray-300 hover:bg-gray-50"
          >
            打开轨迹页
          </Link>
          <Link
            to={projectId ? `/projects/${projectId}/generation` : '#'}
            className="min-h-10 shrink-0 rounded-lg bg-slate-900 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-slate-800"
          >
            去生成用例
          </Link>
        </div>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-4 gap-4 mb-8">
        {stats.map(stat => (
          <div key={stat.label} className="bg-white rounded-xl border border-gray-200 p-5">
            <div className="text-[11px] uppercase tracking-wider text-gray-400 font-semibold mb-2">{stat.label}</div>
            <div className={`text-3xl font-bold ${stat.color}`}>{stat.value}</div>
            <div className="text-xs text-gray-500 mt-1">{stat.sub}</div>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-3 gap-4 mb-8">
        {quickActions.map(action => (
          <Link
            key={action.title}
            to={action.href}
            className="bg-white rounded-xl border border-gray-200 p-5 hover:border-gray-300 hover:shadow-sm transition-all"
          >
            <div className="text-sm font-semibold text-gray-900">{action.title}</div>
            <div className="text-sm text-gray-500 mt-2 leading-6">{action.description}</div>
            <div className="mt-4 text-sm font-medium text-slate-700">{action.label}</div>
          </Link>
        ))}
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
          <div className="px-5 py-3 border-b border-gray-100 bg-gray-50/50 flex items-center justify-between gap-3">
            <h2 className="text-sm font-semibold text-gray-900">最近采集组</h2>
            <Link to={projectId ? `/projects/${projectId}/trace` : '#'} className="text-xs text-gray-500 hover:text-gray-700">
              查看全部
            </Link>
          </div>
          {groups.length === 0 ? (
            <div className="px-5 py-12 text-center text-gray-400 text-sm">暂无采集组</div>
          ) : (
            <div>
              {groups.slice(0, 5).map(group => (
                <div key={group.id} className="flex flex-col gap-2 px-5 py-3 border-b border-gray-100 last:border-b-0 sm:flex-row sm:items-center sm:gap-4">
                  <div className="flex-1 min-w-0">
                    <div className="text-sm font-medium text-gray-900 truncate">{group.groupName}</div>
                    <div className="text-xs text-gray-500 truncate">{group.description || '无描述'}</div>
                  </div>
                  <span className={`inline-flex w-fit shrink-0 items-center gap-1.5 text-[11px] font-medium px-2 py-0.5 rounded ${
                    group.status === 'RUNNING' ? 'bg-green-50 text-green-700' :
                    group.status === 'STOPPED' ? 'bg-gray-100 text-gray-600' :
                    'bg-blue-50 text-blue-700'
                  }`}>
                    <span className={`w-1.5 h-1.5 rounded-full ${group.status === 'RUNNING' ? 'bg-green-500' : 'bg-gray-400'}`} />
                    {group.status === 'RUNNING' ? '采集中' : statusLabel(group.status)}
                  </span>
                  <span className="shrink-0 text-xs font-mono text-gray-400">{formatDate(group.createdAt)}</span>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
          <div className="px-5 py-3 border-b border-gray-100 bg-gray-50/50 flex items-center justify-between gap-3">
            <h2 className="text-sm font-semibold text-gray-900">最近正式用例</h2>
            <Link to={projectId ? `/projects/${projectId}/formal-cases` : '#'} className="text-xs text-gray-500 hover:text-gray-700">
              查看全部
            </Link>
          </div>
          {formalCases.length === 0 ? (
            <div className="px-5 py-12 text-center text-gray-400 text-sm">暂无正式用例</div>
          ) : (
            <div>
              {formalCases.slice(0, 5).map(testCase => (
                <div key={testCase.id} className="flex flex-col gap-2 px-5 py-3 border-b border-gray-100 last:border-b-0 sm:flex-row sm:items-center sm:gap-4">
                  <div className="flex-1 min-w-0">
                    <div className="text-sm font-medium text-gray-900 truncate">{testCase.caseTitle}</div>
                    <div className="text-xs text-gray-500 truncate">
                      {testCase.caseNo || `#${testCase.id}`}{testCase.moduleName ? ` · ${testCase.moduleName}` : ''}
                    </div>
                  </div>
                  {testCase.priority ? (
                    <span className={`w-fit shrink-0 text-[11px] font-semibold px-2 py-0.5 rounded ${priorityConfig[testCase.priority]?.bg || 'bg-gray-100'} ${priorityConfig[testCase.priority]?.text || 'text-gray-500'}`}>
                      {testCase.priority}
                    </span>
                  ) : null}
                  <span className="shrink-0 text-xs font-mono text-gray-400">{formatDate(testCase.updatedAt)}</span>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="xl:col-span-2 bg-white rounded-xl border border-gray-200 overflow-hidden">
          <div className="px-5 py-3 border-b border-gray-100 bg-gray-50/50 flex items-center justify-between gap-3">
            <h2 className="text-sm font-semibold text-gray-900">最近本地草稿</h2>
            <Link to={projectId ? `/projects/${projectId}/local-cases` : '#'} className="text-xs text-gray-500 hover:text-gray-700">
              查看全部
            </Link>
          </div>
          {visibleLocalCases.length === 0 ? (
            <div className="px-5 py-12 text-center text-gray-400 text-sm">暂无本地草稿</div>
          ) : (
            <div>
              {visibleLocalCases.slice(0, 6).map(testCase => (
                <div key={testCase.id} className="flex flex-col gap-2 px-5 py-3 border-b border-gray-100 last:border-b-0 sm:flex-row sm:items-center sm:gap-4">
                  <div className="flex-1 min-w-0">
                    <div className="text-sm font-medium text-gray-900 truncate">{testCase.caseTitle}</div>
                    <div className="text-xs text-gray-500 truncate flex items-center gap-2">
                      <span className={`inline-flex items-center px-2 py-0.5 rounded font-medium ${localCaseSourceClass(testCase.sourceType)}`}>
                        {localCaseSourceLabel(testCase.sourceType)}
                      </span>
                      <span className="truncate">{testCase.moduleName || testCase.caseType || '未分类'}</span>
                    </div>
                  </div>
                  {testCase.priority ? (
                    <span className={`w-fit shrink-0 text-[11px] font-semibold px-2 py-0.5 rounded ${priorityConfig[testCase.priority]?.bg || 'bg-gray-100'} ${priorityConfig[testCase.priority]?.text || 'text-gray-500'}`}>
                      {testCase.priority}
                    </span>
                  ) : null}
                  <span className={`w-fit shrink-0 text-[11px] font-medium px-2 py-0.5 rounded ${localCaseStatusClass(testCase.caseStatus)}`}>
                    {localCaseStatusLabel(testCase.caseStatus)}
                  </span>
                  <span className="shrink-0 text-xs font-mono text-gray-400">{formatDate(testCase.updatedAt || testCase.createdAt)}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

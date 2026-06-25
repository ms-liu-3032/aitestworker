import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useApp } from '../../context/AppContext';
import RichList from '../../components/RichList';
import StatusBadge from '../../components/StatusBadge';
import NewProjectModal from '../../components/NewProjectModal';
import { listProjects, listFormalCases, listLocalCases, restoreProject, type Project } from '../../services/api';

const avatarColors = [
  'bg-blue-50 text-blue-600',
  'bg-emerald-50 text-emerald-600',
  'bg-violet-50 text-violet-600',
  'bg-amber-50 text-amber-600',
  'bg-rose-50 text-rose-600',
  'bg-cyan-50 text-cyan-600',
];

interface ProjectBadges {
  caseCount: number;
  draftCount: number;
}

export default function ProjectList() {
  const navigate = useNavigate();
  const { showToast } = useApp();
  const [projects, setProjects] = useState<Project[]>([]);
  const [badges, setBadges] = useState<Record<number, ProjectBadges>>({});
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ACTIVE' | '' | 'DELETED'>('ACTIVE');
  const [showNewProject, setShowNewProject] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize] = useState(20);
  const [total, setTotal] = useState(0);

  useEffect(() => {
    loadProjects();
  }, [keyword, statusFilter, page]);

  const loadProjects = () => {
    setLoading(true);
    listProjects({ keyword, status: statusFilter || undefined, page, size: pageSize })
      .then(res => {
        setProjects(res.items);
        setTotal(res.total);
        // 异步加载每个项目的 badges
        res.items.forEach(p => {
          if (p.status === 'ACTIVE') {
            loadBadges(p.id);
          }
        });
      })
      .catch(() => showToast('加载项目列表失败'))
      .finally(() => setLoading(false));
  };

  const loadBadges = async (projectId: number) => {
    try {
      const [formal, localCases] = await Promise.all([
        listFormalCases(projectId).catch(() => []),
        listLocalCases(projectId).catch(() => []),
      ]);
      setBadges(prev => ({
        ...prev,
        [projectId]: {
          caseCount: formal.length,
          draftCount: localCases.filter(item => item.caseStatus !== 'SUBMITTED' && item.caseStatus !== 'DEPRECATED').length,
        },
      }));
    } catch {}
  };

  const handleRestore = async (projectId: number) => {
    try {
      await restoreProject(projectId);
      showToast('项目已恢复');
      loadProjects();
    } catch {
      showToast('恢复失败', 'error');
    }
  };

  const columns = [
    { key: 'identity', label: '项目标识', width: '280px' },
    { key: 'badges', label: '业务动态', width: '220px' },
    { key: 'status', label: '状态', width: '100px' },
    { key: 'updated', label: '最后更新', width: '120px' },
    { key: 'actions', label: '操作', width: '160px' },
  ];

  return (
    <div className="mx-auto max-w-[1200px] px-4 pt-14 pb-6 animate-fade-in sm:px-6">
      {/* 顶部操作栏 */}
      <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-2xl font-bold text-gray-900 tracking-tight">项目大厅</h1>
          <p className="text-sm text-gray-500 mt-1">管理和进入你的测试项目</p>
        </div>
        <button
          onClick={() => setShowNewProject(true)}
          className="flex min-h-10 shrink-0 items-center justify-center gap-1.5 rounded-lg bg-slate-900 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-slate-800"
        >
          <span className="text-base leading-none">+</span>
          新建项目
        </button>
      </div>

      {/* 筛选栏 */}
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="flex w-fit flex-wrap items-center gap-0.5 rounded-lg bg-gray-100 p-0.5">
          {([
            { value: 'ACTIVE' as const, label: '有效' },
            { value: '' as const, label: '全部' },
            { value: 'DELETED' as const, label: '已删除' },
          ]).map(opt => (
            <button
              key={opt.value}
              onClick={() => {
                setStatusFilter(opt.value);
                setPage(1);
              }}
              className={`px-3 py-1.5 text-xs font-medium rounded-md transition-all duration-150 ${
                statusFilter === opt.value
                  ? 'bg-white text-gray-900 shadow-sm'
                  : 'text-gray-500 hover:text-gray-700'
              }`}
            >
              {opt.label}
            </button>
          ))}
        </div>

        <div className="w-full min-w-0 sm:max-w-xs">
          <div className="relative">
            <svg className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              value={keyword}
              onChange={e => {
                setKeyword(e.target.value);
                setPage(1);
              }}
              placeholder="搜索项目..."
              className="w-full h-8 pl-8 pr-3 bg-white border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none transition-colors"
            />
            {keyword && (
              <button
                onClick={() => {
                  setKeyword('');
                  setPage(1);
                }}
                className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
              >
                ✕
              </button>
            )}
          </div>
        </div>

        <span className="text-xs text-gray-400 font-mono sm:ml-auto">
          共 {total} 个项目
        </span>
      </div>

      {/* 项目列表 */}
      <RichList
        items={projects}
        columns={columns}
        loading={loading}
        renderRow={(project: Project) => {
          const colorIdx = project.id % avatarColors.length;
          const b = badges[project.id];
          return (
            <>
              {/* 项目标识 */}
              <div className="flex items-center gap-3" style={{ width: '280px' }}>
                <div className={`w-8 h-8 rounded-lg flex items-center justify-center text-xs font-bold ${avatarColors[colorIdx]}`}>
                  {project.projectName.charAt(0)}
                </div>
                <div className="min-w-0">
                  <div className="font-semibold text-sm text-gray-900 truncate">{project.projectName}</div>
                  <div className="font-mono text-[11px] text-gray-400 truncate">ID: {project.id}</div>
                </div>
              </div>

              {/* 业务动态 Badges */}
              <div className="flex items-center gap-1.5" style={{ width: '220px' }}>
                <span className="inline-flex items-center gap-1 text-[11px] font-medium bg-gray-100 text-gray-600 px-2 py-0.5 rounded">
                  用例 {b?.caseCount ?? '-'}
                </span>
                {b && b.draftCount > 0 && (
                  <span className="inline-flex items-center gap-1 text-[11px] font-medium bg-red-50 text-red-600 px-2 py-0.5 rounded">
                    草稿 {b.draftCount}
                  </span>
                )}
                <span className={`inline-flex items-center gap-1 text-[11px] font-medium px-2 py-0.5 rounded ${
                  project.status === 'ACTIVE' ? 'bg-green-50 text-green-700' : 'bg-gray-100 text-gray-600'
                }`}>
                  <span className={`w-1.5 h-1.5 rounded-full ${project.status === 'ACTIVE' ? 'bg-green-500' : 'bg-gray-400'}`} />
                  {project.status === 'ACTIVE' ? '活跃' : '已删除'}
                </span>
              </div>

              {/* 状态 */}
              <div style={{ width: '100px' }}>
                <StatusBadge status={project.status === 'ACTIVE' ? 'online' : 'offline'} label={project.status === 'ACTIVE' ? '有效' : '已删除'} />
              </div>

              {/* 最后更新 */}
              <div className="text-xs text-gray-500 font-mono" style={{ width: '120px' }}>
                {new Date(project.updatedAt).toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })}
              </div>

              {/* 操作 */}
              <div className="flex items-center justify-end gap-1 opacity-0 group-hover:opacity-100 transition-opacity duration-150" style={{ width: '160px', flex: 1 }}>
                {project.status === 'DELETED' ? (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      handleRestore(project.id);
                    }}
                    className="px-2.5 py-1 text-xs font-medium text-emerald-700 bg-emerald-50 hover:bg-emerald-100 rounded transition-colors"
                  >
                    恢复
                  </button>
                ) : (
                  <>
                    <button
                      onClick={(e) => { e.stopPropagation(); navigate(`/projects/${project.id}/settings`); }}
                      className="px-2.5 py-1 text-xs text-gray-500 hover:text-gray-900 hover:bg-gray-100 rounded transition-colors"
                    >
                      编辑
                    </button>
                    <button
                      onClick={(e) => { e.stopPropagation(); navigate(`/projects/${project.id}`); }}
                      className="px-2.5 py-1 text-xs font-medium text-white bg-slate-900 hover:bg-slate-800 rounded transition-colors"
                    >
                      进入
                    </button>
                  </>
                )}
              </div>
            </>
          );
        }}
      />

      {total > pageSize && (
        <div className="mt-4 flex flex-col gap-2 px-1 sm:flex-row sm:items-center sm:justify-between">
          <span className="text-xs text-gray-500">
            共 {total} 条，第 {page} 页
          </span>
          <div className="flex flex-wrap items-center gap-2">
            <button
              onClick={() => setPage(p => Math.max(1, p - 1))}
              disabled={page <= 1}
              className="px-3 py-1.5 text-xs border border-gray-200 rounded-lg hover:border-gray-400 transition-colors disabled:opacity-30"
            >
              上一页
            </button>
            <button
              onClick={() => setPage(p => p + 1)}
              disabled={page * pageSize >= total}
              className="px-3 py-1.5 text-xs border border-gray-200 rounded-lg hover:border-gray-400 transition-colors disabled:opacity-30"
            >
              下一页
            </button>
          </div>
        </div>
      )}

      {projects.length === 0 && !loading && (
        <div className="flex flex-col items-center justify-center py-20">
          <div className="w-14 h-14 bg-gray-100 rounded-xl mb-4 flex items-center justify-center">
            <svg className="w-7 h-7 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
            </svg>
          </div>
          <p className="text-gray-500 text-sm mb-1">暂无项目</p>
          <p className="text-gray-400 text-xs">点击「新建项目」开始</p>
        </div>
      )}

      {showNewProject && (
        <NewProjectModal
          onClose={() => setShowNewProject(false)}
          onSuccess={() => { setShowNewProject(false); loadProjects(); }}
          showToast={showToast}
        />
      )}
    </div>
  );
}

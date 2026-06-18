import { useState, useEffect } from 'react';
import { useParams, useNavigate, useLocation, Outlet } from 'react-router-dom';
import { getProject, type Project } from '../services/api';
import { PROJECT_NAV_ITEMS } from '../types';

const avatarColors = [
  'bg-blue-50 text-blue-600',
  'bg-emerald-50 text-emerald-600',
  'bg-violet-50 text-violet-600',
  'bg-amber-50 text-amber-600',
  'bg-rose-50 text-rose-600',
  'bg-cyan-50 text-cyan-600',
];

export default function ProjectLayout() {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const [project, setProject] = useState<Project | null>(null);
  const [loading, setLoading] = useState(true);

  // 从 URL 提取当前 tab
  const pathParts = location.pathname.split('/');
  const currentTab = pathParts[4] || 'overview'; // /projects/:id/:tab

  useEffect(() => {
    if (!projectId) return;
    setLoading(true);
    getProject(Number(projectId))
      .then(setProject)
      .catch(() => setProject(null))
      .finally(() => setLoading(false));
  }, [projectId]);

  if (loading) {
    return (
      <div className="flex h-[calc(100vh-56px)] min-w-0 items-center justify-center overflow-hidden">
        <div className="flex flex-col items-center gap-3">
          <div className="w-8 h-8 border-2 border-gray-200 border-t-gray-600 rounded-full animate-spin" />
          <span className="text-sm text-gray-400">加载中...</span>
        </div>
      </div>
    );
  }

  if (!project) {
    return (
      <div className="flex h-[calc(100vh-56px)] min-w-0 items-center justify-center overflow-hidden">
        <div className="text-center">
          <div className="w-14 h-14 bg-gray-100 rounded-xl mb-4 flex items-center justify-center mx-auto">
            <span className="text-2xl">😕</span>
          </div>
          <p className="text-gray-500 text-sm mb-3">项目不存在或已被删除</p>
          <button
            onClick={() => navigate('/')}
            className="text-sm text-blue-600 hover:text-blue-700 font-medium transition-colors"
          >
            ← 返回工作台
          </button>
        </div>
      </div>
    );
  }

  const colorIdx = project.id % avatarColors.length;

  return (
    <div className="flex min-h-[calc(100vh-56px)] min-w-0 flex-col overflow-hidden lg:h-[calc(100vh-56px)] lg:flex-row">
      {/* 左侧边栏 */}
      <aside className="flex max-h-80 w-full flex-shrink-0 flex-col overflow-hidden border-b border-gray-200 bg-white lg:max-h-none lg:w-60 lg:border-b-0 lg:border-r">
        {/* 返回按钮 */}
        <div className="px-4 pt-4 pb-2">
          <button
            onClick={() => navigate('/')}
            className="flex items-center gap-1.5 text-gray-400 hover:text-gray-600 transition-colors group"
          >
            <span className="text-sm group-hover:-translate-x-0.5 transition-transform">←</span>
            <span className="text-xs font-medium">工作台</span>
          </button>
        </div>

        {/* 项目信息 */}
        <div className="px-4 pb-4 border-b border-gray-100">
          <div className="flex items-center gap-3">
            <div className={`w-10 h-10 rounded-xl flex items-center justify-center text-base font-bold ${avatarColors[colorIdx]}`}>
              {project.projectName.charAt(0)}
            </div>
            <div className="min-w-0 flex-1">
              <div className="text-sm font-bold text-gray-900 truncate">{project.projectName}</div>
              <div className="flex items-center gap-1.5 mt-0.5">
                <span className={`inline-block w-1.5 h-1.5 rounded-full ${project.status === 'ACTIVE' ? 'bg-green-500' : 'bg-gray-400'}`} />
                <span className="text-[11px] text-gray-500">{project.status === 'ACTIVE' ? '运行中' : '已归档'}</span>
              </div>
            </div>
          </div>
        </div>

        {/* 导航菜单 */}
        <nav className="flex-1 overflow-y-auto py-2 px-2">
          <div className="text-[10px] uppercase tracking-wider text-gray-400 font-semibold px-2 mb-1">工作区</div>
          {PROJECT_NAV_ITEMS.map(item => {
            const isActive = currentTab === item.key;
            return (
              <button
                key={item.key}
                onClick={() => navigate(`/projects/${projectId}/${item.key}`)}
                className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-lg text-sm transition-all duration-150 mb-0.5 ${
                  isActive
                    ? 'bg-gray-100 text-gray-900 font-medium'
                    : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
                }`}
              >
                <span className="text-base w-5 text-center">{item.icon}</span>
                <span className="truncate">{item.label}</span>
                {isActive && (
                  <span className="ml-auto w-1.5 h-1.5 rounded-full bg-slate-900" />
                )}
              </button>
            );
          })}
        </nav>

        {/* 底部信息 */}
        <div className="px-4 py-3 border-t border-gray-100">
          <div className="text-[10px] font-mono text-gray-400">
            ID: {project.id} · 更新于 {new Date(project.updatedAt).toLocaleDateString('zh-CN')}
          </div>
        </div>
      </aside>

      {/* 右侧内容区 */}
      <main className="min-w-0 flex-1 overflow-y-auto overflow-x-hidden bg-gray-50">
        <Outlet />
      </main>
    </div>
  );
}

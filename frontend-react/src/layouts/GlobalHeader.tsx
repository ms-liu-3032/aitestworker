import { useState, useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useApp } from '../context/AppContext';
import { useAuth } from '../context/AuthContext';
import CommandPalette from '../components/CommandPalette';
import type { CommandItem, AppContext } from '../types';
import { listProjects, listWorkerDevices, type Project, type WorkerDevice } from '../services/api';
import { displayLabel } from '../utils/displayLabels';

export default function GlobalHeader() {
  const navigate = useNavigate();
  const location = useLocation();
  const { setCommandOpen } = useApp();
  const { user, logout } = useAuth();
  const [projects, setProjects] = useState<Project[]>([]);
  const [devices, setDevices] = useState<WorkerDevice[]>([]);
  const [workspaceOpen, setWorkspaceOpen] = useState(false);
  const [adminOpen, setAdminOpen] = useState(false);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [collectorExpanded, setCollectorExpanded] = useState(false);

  // 判断当前上下文
  const currentContext: AppContext = location.pathname.startsWith('/admin') ? 'admin' : 'workspace';

  useEffect(() => {
    listProjects({ size: 100 }).then(res => setProjects(res.items)).catch(() => {});
    listWorkerDevices().then(setDevices).catch(() => {});
  }, []);

  const onlineCount = devices.filter(d => d.bindStatus === 'BOUND').length;

  const commands: CommandItem[] = [
    ...projects.map(p => ({
      id: `proj-${p.id}`,
      label: `${p.projectName} (#${p.id})`,
      group: '项目',
      action: () => navigate(`/projects/${p.id}`),
    })),
    {
      id: 'cmd-workspace',
      label: '返回工作台',
      group: '导航',
      shortcut: '⌘H',
      action: () => navigate('/'),
    },
    {
      id: 'cmd-collectors',
      label: '采集器管理',
      group: '导航',
      shortcut: '⌘C',
      action: () => navigate('/collectors'),
    },
    {
      id: 'cmd-admin',
      label: '管理后台',
      group: '导航',
      action: () => navigate('/admin'),
    },
    {
      id: 'cmd-settings',
      label: '系统设置',
      group: '导航',
      shortcut: '⌘,',
      action: () => navigate('/settings'),
    },
  ];

  return (
    <>
      <nav className="sticky top-0 z-50 flex h-14 min-w-0 items-center gap-2 overflow-x-hidden border-b border-gray-200 bg-white px-3 sm:px-4">
        {/* 左侧：上下文切换器 */}
        <div className="flex min-w-0 shrink-0 items-center gap-1 sm:gap-2">
          {/* 工作空间切换 */}
          <div className="relative">
            <button
              onClick={() => { setWorkspaceOpen(!workspaceOpen); setAdminOpen(false); }}
              className={`flex min-w-0 items-center gap-2 hover:bg-gray-50 rounded-lg px-2.5 py-1.5 transition-colors ${currentContext === 'workspace' ? 'bg-gray-100' : ''}`}
            >
              <div className="w-6 h-6 bg-slate-900 text-white rounded flex items-center justify-center text-[10px] font-bold">
                A
              </div>
              <span className="hidden text-sm font-medium text-gray-900 sm:inline">测试工作空间</span>
              <svg className="w-3.5 h-3.5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            {workspaceOpen && (
              <div className="fixed left-0 top-14 w-56 bg-white rounded-b-xl shadow-lg border border-gray-200 py-1 z-[60]">
                <div className="px-3 py-1.5 text-[11px] uppercase tracking-wider text-gray-400 font-semibold">工作空间</div>
                <button
                  onClick={() => { navigate('/'); setWorkspaceOpen(false); }}
                  className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
                >
                  <span className="w-5 h-5 bg-blue-50 text-blue-600 rounded flex items-center justify-center text-[10px] font-bold">P</span>
                  项目大厅
                </button>
                <button
                  onClick={() => { navigate('/collectors'); setWorkspaceOpen(false); }}
                  className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
                >
                  <span className="w-5 h-5 bg-green-50 text-green-600 rounded flex items-center justify-center text-[10px] font-bold">C</span>
                  采集器管理
                </button>
              </div>
            )}
          </div>

          {/* 管理后台切换 */}
          <div className="relative">
            <button
              onClick={() => { setAdminOpen(!adminOpen); setWorkspaceOpen(false); }}
              className={`flex min-w-0 items-center gap-2 hover:bg-gray-50 rounded-lg px-2.5 py-1.5 transition-colors ${currentContext === 'admin' ? 'bg-gray-100' : ''}`}
            >
              <div className="w-6 h-6 bg-purple-600 text-white rounded flex items-center justify-center text-[10px] font-bold">
                M
              </div>
              <span className="hidden text-sm font-medium text-gray-900 sm:inline">管理后台</span>
              <svg className="w-3.5 h-3.5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            {adminOpen && (
              <div className="fixed left-0 top-14 w-56 bg-white rounded-b-xl shadow-lg border border-gray-200 py-1 z-[60]">
                <div className="px-3 py-1.5 text-[11px] uppercase tracking-wider text-gray-400 font-semibold">管理后台</div>
                <button
                  onClick={() => { navigate('/admin'); setAdminOpen(false); }}
                  className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
                >
                  <span className="w-5 h-5 bg-purple-50 text-purple-600 rounded flex items-center justify-center text-[10px] font-bold">A</span>
                  系统管理
                </button>
              </div>
            )}
          </div>
        </div>

        {/* 中央：命令中心 */}
        <div className="hidden min-w-0 flex-1 justify-center px-2 md:flex lg:px-8">
          <button
            onClick={() => setCommandOpen(true)}
            className="flex min-w-0 w-full max-w-md items-center gap-2 rounded-lg bg-gray-100 px-3 py-1.5 transition-colors hover:bg-gray-200"
          >
            <svg className="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <span className="min-w-0 flex-1 truncate text-left text-sm text-gray-400">搜索项目、用例或输入命令...</span>
            <kbd className="font-mono text-[11px] bg-white border border-gray-200 rounded px-1.5 py-0.5 text-gray-500">
              ⌘K
            </kbd>
          </button>
        </div>

        {/* 右侧：灵动岛（采集器状态 + 用户头像） */}
        <div className="ml-auto flex shrink-0 items-center gap-2 sm:gap-3">
          {/* 采集器状态胶囊 */}
          <div
            className="relative"
            onMouseEnter={() => setCollectorExpanded(true)}
            onMouseLeave={() => setCollectorExpanded(false)}
          >
            <div className="flex items-center gap-2 rounded-full border border-green-200 bg-green-50 px-2.5 py-1 cursor-pointer sm:px-3">
              <span className="relative flex h-2 w-2">
                <span className="absolute inline-flex h-2 w-2 animate-ping rounded-full bg-green-500 opacity-20" />
                <span className="relative inline-flex h-2 w-2 rounded-full bg-green-500" />
              </span>
              <span className="hidden text-xs font-medium text-green-700 sm:inline">
                {onlineCount > 0 ? '采集器在线' : '采集器离线'}
              </span>
            </div>
            {collectorExpanded && (
              <div className="fixed right-0 top-14 w-72 bg-white rounded-b-xl shadow-lg border border-gray-200 py-2 z-[60]">
                <div className="px-3 py-1.5 text-[11px] uppercase tracking-wider text-gray-400 font-semibold">
                  采集器状态
                </div>
                {devices.map(dev => (
                  <div key={dev.id} className="flex items-center justify-between gap-3 px-3 py-2 hover:bg-gray-50">
                    <div className="min-w-0">
                      <div className="truncate text-sm text-gray-900">{dev.deviceName}</div>
                      <div className="truncate text-xs font-mono text-gray-500">{dev.platform}/{dev.arch}</div>
                    </div>
                    <span className={`shrink-0 text-xs font-medium ${dev.bindStatus === 'BOUND' ? 'text-green-600' : 'text-gray-400'}`}>
                      {dev.bindStatus === 'BOUND' ? '在线' : '离线'}
                    </span>
                  </div>
                ))}
                {devices.length === 0 && (
                  <div className="px-3 py-2 text-xs text-gray-400">暂无采集器</div>
                )}
                <div className="border-t border-gray-200 mt-1 pt-1">
                  <button
                    onClick={() => { navigate('/collectors'); setCollectorExpanded(false); }}
                    className="w-full text-left px-3 py-2 text-xs text-blue-600 hover:bg-gray-50"
                  >
                    管理采集器 →
                  </button>
                </div>
              </div>
            )}
          </div>

          {/* 用户头像 */}
          <div className="relative">
            <button
              onClick={() => setUserMenuOpen(!userMenuOpen)}
              className="w-7 h-7 rounded-full bg-gray-200 border-2 border-white ring-1 ring-gray-200 flex items-center justify-center text-xs font-medium text-gray-600 hover:ring-gray-300 transition-all"
              title={user?.displayName || user?.username || '用户'}
            >
              {(user?.displayName || user?.username || '用').charAt(0)}
            </button>
            {userMenuOpen && (
              <div className="fixed right-0 top-14 w-48 bg-white rounded-b-xl shadow-lg border border-gray-200 py-1 z-[60]">
                <div className="px-3 py-2 border-b border-gray-100">
                  <div className="truncate text-sm font-medium text-gray-900">{user?.displayName || user?.username}</div>
                  <div className="truncate text-xs text-gray-500">{displayLabel(user?.roleCode, '普通用户')}</div>
                </div>
                <button
                  onClick={() => { navigate('/settings'); setUserMenuOpen(false); }}
                  className="w-full text-left px-3 py-2 text-sm text-gray-700 hover:bg-gray-50"
                >
                  设置
                </button>
                <button
                  onClick={() => { logout(); setUserMenuOpen(false); }}
                  className="w-full text-left px-3 py-2 text-sm text-red-600 hover:bg-red-50"
                >
                  退出登录
                </button>
              </div>
            )}
          </div>
        </div>
      </nav>

      <CommandPalette commands={commands} />
    </>
  );
}

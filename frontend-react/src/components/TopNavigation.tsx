import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useApp } from '../context/AppContext';
import { useAuth } from '../context/AuthContext';
import CommandPalette from './CommandPalette';
import type { CommandItem } from '../types';
import { listProjects, listWorkerDevices, type Project, type WorkerDevice } from '../services/api';

function CollectorPill() {
  const navigate = useNavigate();
  const [expanded, setExpanded] = useState(false);
  const [devices, setDevices] = useState<WorkerDevice[]>([]);

  useEffect(() => {
    listWorkerDevices().then(setDevices).catch(() => {});
  }, []);

  const onlineCount = devices.filter(d => d.bindStatus === 'BOUND').length;

  return (
    <div
      className="relative"
      onMouseEnter={() => setExpanded(true)}
      onMouseLeave={() => setExpanded(false)}
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
      {expanded && (
        <div className="absolute right-0 top-full mt-2 w-64 bg-white rounded-xl shadow-lg border border-gray-200 py-2 z-50">
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
              onClick={() => { navigate('/collectors'); setExpanded(false); }}
              className="w-full text-left px-3 py-2 text-xs text-blue-600 hover:bg-gray-50"
            >
              管理采集器 →
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

export default function TopNavigation() {
  const navigate = useNavigate();
  const { setCommandOpen } = useApp();
  const { user, logout } = useAuth();
  const [workspaceOpen, setWorkspaceOpen] = useState(false);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [projects, setProjects] = useState<Project[]>([]);

  useEffect(() => {
    listProjects({ size: 100 }).then(res => setProjects(res.items)).catch(() => {});
  }, []);

  const commands: CommandItem[] = [
    ...projects.map(p => ({
      id: `proj-${p.id}`,
      label: `${p.projectName} (#${p.id})`,
      group: '项目',
      action: () => navigate(`/projects/${p.id}`),
    })),
    {
      id: 'cmd-collectors',
      label: '采集器管理',
      group: '命令',
      shortcut: '⌘C',
      action: () => navigate('/collectors'),
    },
    {
      id: 'cmd-settings',
      label: '系统设置',
      group: '命令',
      shortcut: '⌘,',
      action: () => navigate('/settings'),
    },
    {
      id: 'cmd-workspace',
      label: '返回工作台',
      group: '命令',
      shortcut: '⌘H',
      action: () => navigate('/'),
    },
  ];

  return (
    <>
      <nav className="sticky top-0 z-50 flex h-14 min-w-0 items-center gap-2 overflow-x-hidden border-b border-gray-200 bg-white px-3 sm:px-4">
        {/* Left: Workspace Switcher */}
        <div className="relative">
          <button
            onClick={() => setWorkspaceOpen(!workspaceOpen)}
            className="flex min-w-0 items-center gap-2 rounded-lg px-2 py-1.5 transition-colors hover:bg-gray-50"
          >
            <div className="w-7 h-7 bg-slate-900 text-white rounded flex items-center justify-center text-xs font-bold">
              A
            </div>
            <span className="hidden text-sm font-medium text-gray-900 sm:inline">测试资产空间</span>
            <svg className="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
            </svg>
          </button>
          {workspaceOpen && (
            <div className="absolute left-0 top-full mt-1 w-48 bg-white rounded-xl shadow-lg border border-gray-200 py-1 z-50">
              <button
                onClick={() => { navigate('/'); setWorkspaceOpen(false); }}
                className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
              >
                <span className="w-5 h-5 bg-blue-50 text-blue-600 rounded flex items-center justify-center text-[10px] font-bold">P</span>
                项目空间
              </button>
              <button
                onClick={() => { navigate('/collectors'); setWorkspaceOpen(false); }}
                className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
              >
                <span className="w-5 h-5 bg-green-50 text-green-600 rounded flex items-center justify-center text-[10px] font-bold">C</span>
                采集器管理
              </button>
              <div className="border-t border-gray-200 my-1" />
              <button
                onClick={() => { navigate('/settings'); setWorkspaceOpen(false); }}
                className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
              >
                <span className="w-5 h-5 bg-gray-100 text-gray-600 rounded flex items-center justify-center text-[10px] font-bold">S</span>
                设置
              </button>
            </div>
          )}
        </div>

        {/* Center: Command Bar */}
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

        {/* Right: Collector + Avatar */}
        <div className="ml-auto flex shrink-0 items-center gap-2 sm:gap-3">
          <CollectorPill />
          <div className="relative">
            <button
              onClick={() => setUserMenuOpen(!userMenuOpen)}
              className="w-7 h-7 rounded-full bg-gray-200 border-2 border-white ring-1 ring-gray-200 flex items-center justify-center text-xs font-medium text-gray-600 hover:ring-gray-300 transition-all"
              title={user?.displayName || user?.username || '用户'}
            >
              {(user?.displayName || user?.username || '用').charAt(0)}
            </button>
            {userMenuOpen && (
              <div className="absolute right-0 top-full mt-2 w-48 bg-white rounded-xl shadow-lg border border-gray-200 py-1 z-50">
                <div className="px-3 py-2 border-b border-gray-100">
                  <div className="truncate text-sm font-medium text-gray-900">{user?.displayName || user?.username}</div>
                  <div className="truncate text-xs text-gray-500">{user?.roleCode}</div>
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

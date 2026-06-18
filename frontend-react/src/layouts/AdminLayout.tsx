import { useNavigate, useLocation, Outlet } from 'react-router-dom';
import { ADMIN_NAV_ITEMS } from '../types';

export default function AdminLayout() {
  const navigate = useNavigate();
  const location = useLocation();

  // 从 URL 提取当前 tab
  const pathParts = location.pathname.split('/');
  const currentTab = pathParts[2] || 'users'; // /admin/:tab

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

        {/* 管理后台标题 */}
        <div className="px-4 pb-4 border-b border-gray-100">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-purple-100 text-purple-600 flex items-center justify-center text-base font-bold">
              M
            </div>
            <div className="min-w-0">
              <div className="text-sm font-bold text-gray-900">管理后台</div>
              <div className="flex items-center gap-1.5 mt-0.5">
                <span className="inline-block w-1.5 h-1.5 rounded-full bg-purple-500" />
                <span className="text-[11px] text-gray-500">系统级配置</span>
              </div>
            </div>
          </div>
        </div>

        {/* 导航菜单 */}
        <nav className="flex-1 overflow-y-auto py-2 px-2">
          <div className="text-[10px] uppercase tracking-wider text-gray-400 font-semibold px-2 mb-1">系统管理</div>
          {ADMIN_NAV_ITEMS.map(item => {
            const isActive = currentTab === item.key;
            return (
              <button
                key={item.key}
                onClick={() => navigate(`/admin/${item.key}`)}
                className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-lg text-sm transition-all duration-150 mb-0.5 ${
                  isActive
                    ? 'bg-purple-50 text-purple-900 font-medium'
                    : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
                }`}
              >
                <span className="text-base w-5 text-center">{item.icon}</span>
                <span className="truncate">{item.label}</span>
                {isActive && (
                  <span className="ml-auto w-1.5 h-1.5 rounded-full bg-purple-600" />
                )}
              </button>
            );
          })}
        </nav>

        {/* 底部信息 */}
        <div className="px-4 py-3 border-t border-gray-100">
          <div className="text-[10px] text-gray-400">
            需要管理员权限
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

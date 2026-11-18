import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

function formatRole(roleCode?: string) {
  switch (roleCode) {
    case 'ADMIN':
      return '系统管理员';
    case 'SUB_ADMIN':
      return '子管理员';
    case 'MEMBER':
      return '普通成员';
    default:
      return roleCode || '未知角色';
  }
}

export default function Settings() {
  const navigate = useNavigate();
  const { user, logout } = useAuth();

  const quickActions = useMemo(() => {
    const actions = [
      { label: '返回项目大厅', desc: '查看项目列表、搜索项目、恢复已删除项目。', action: () => navigate('/') },
      { label: '采集器管理', desc: '查看在线采集器、生成绑定码、处理解绑。', action: () => navigate('/collectors') },
    ];
    if (user?.roleCode === 'ADMIN' || user?.roleCode === 'SUB_ADMIN') {
      actions.push(
        { label: '用户管理', desc: '创建用户、分配角色、继续系统级运维。', action: () => navigate('/admin/users') },
        { label: '模型配置', desc: '维护模型配置与可用模型列表。', action: () => navigate('/admin/models') },
        { label: '系统配置', desc: '查看扫描入口与系统运行配置。', action: () => navigate('/admin/scan') },
      );
    }
    return actions;
  }, [navigate, user?.roleCode]);

  return (
    <div className="mx-auto max-w-[980px] px-4 py-8 animate-fade-in sm:px-6">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900">账户中心</h1>
        <p className="mt-2 text-sm text-gray-500">
          这里展示当前登录账户和会话状态。系统级配置入口已经分别收敛到项目大厅、采集器管理和管理后台，不再保留没有后端支撑的假设置项。
        </p>
      </div>

      <div className="grid gap-6 lg:grid-cols-[1.1fr_0.9fr]">
        <section className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="text-lg font-semibold text-gray-900">当前账户</h2>
          <div className="mt-5 grid gap-4 sm:grid-cols-2">
            <div>
              <div className="text-xs font-medium uppercase tracking-wide text-gray-400">显示名称</div>
              <div className="mt-1 break-words text-sm text-gray-900">{user?.displayName || user?.username || '未登录'}</div>
            </div>
            <div>
              <div className="text-xs font-medium uppercase tracking-wide text-gray-400">用户名</div>
              <div className="mt-1 break-all text-sm text-gray-900">{user?.username || '未登录'}</div>
            </div>
            <div>
              <div className="text-xs font-medium uppercase tracking-wide text-gray-400">角色</div>
              <div className="mt-1 text-sm text-gray-900">{formatRole(user?.roleCode)}</div>
            </div>
            <div>
              <div className="text-xs font-medium uppercase tracking-wide text-gray-400">登录状态</div>
              <div className="mt-1 inline-flex items-center gap-2 text-sm text-emerald-700">
                <span className="inline-flex h-2 w-2 rounded-full bg-emerald-500" />
                {user ? '已登录' : '未登录'}
              </div>
            </div>
          </div>

          <div className="mt-6 rounded-lg bg-gray-50 border border-gray-200 p-4">
            <div className="text-xs font-medium uppercase tracking-wide text-gray-400">会话安全</div>
            <p className="mt-2 text-xs text-gray-500">
              当前会话已建立。为避免截图、录屏或旁观时泄露凭证，页面不会显示或导出会话令牌。
            </p>
          </div>
        </section>

        <section className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="text-lg font-semibold text-gray-900">快捷入口</h2>
          <div className="mt-4 space-y-3">
            {quickActions.map(item => (
              <button
                key={item.label}
                onClick={item.action}
                className="w-full rounded-lg border border-gray-200 px-4 py-3 text-left transition-colors hover:border-slate-900 hover:bg-gray-50"
              >
                <div className="text-sm font-medium text-gray-900">{item.label}</div>
                <div className="mt-1 text-xs text-gray-500">{item.desc}</div>
              </button>
            ))}
          </div>

          <div className="mt-6 rounded-lg border border-amber-200 bg-amber-50 p-4">
            <div className="text-sm font-medium text-amber-900">当前页能力边界</div>
            <p className="mt-2 text-xs leading-6 text-amber-900/80">
              这页现在只承载真实可用的账户与会话信息。后续如果补齐用户资料、通知偏好等后端接口，我们再把对应表单接回来，不再保留“看起来能配、实际不落库”的演示项。
            </p>
          </div>

          <button
            onClick={logout}
            className="mt-6 w-full rounded-lg bg-slate-900 px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-slate-800"
          >
            退出登录
          </button>
        </section>
      </div>
    </div>
  );
}

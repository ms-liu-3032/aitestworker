import { useEffect, useMemo, useState } from 'react';
import RichList from '../../components/RichList';
import { useApp } from '../../context/AppContext';
import { createUser, listUsers, type UserRecord } from '../../services/api';
import { statusLabel } from '../../utils/displayLabels';

type RoleFilter = '' | 'ADMIN' | 'SUB_ADMIN' | 'MEMBER';
type StatusFilter = '' | 'ACTIVE';

export default function UserManagement() {
  const { showToast } = useApp();
  const [users, setUsers] = useState<UserRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [saving, setSaving] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [roleFilter, setRoleFilter] = useState<RoleFilter>('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ACTIVE');
  const [form, setForm] = useState({ username: '', password: '', displayName: '', roleCode: 'MEMBER' });

  const loadUsers = async () => {
    setLoading(true);
    try {
      const data = await listUsers();
      setUsers(data);
    } catch (error: any) {
      showToast(error.message || '加载用户列表失败', 'error');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadUsers();
  }, []);

  const filteredUsers = useMemo(() => {
    const term = keyword.trim().toLowerCase();
    return users.filter(user => {
      if (roleFilter && user.roleCode !== roleFilter) return false;
      if (statusFilter && user.status !== statusFilter) return false;
      if (!term) return true;
      return [user.username, user.displayName || '', user.roleCode, user.status].some(value =>
        value.toLowerCase().includes(term)
      );
    });
  }, [users, keyword, roleFilter, statusFilter]);

  const counts = useMemo(() => ({
    total: users.length,
    admins: users.filter(user => user.roleCode === 'ADMIN').length,
    subAdmins: users.filter(user => user.roleCode === 'SUB_ADMIN').length,
    active: users.filter(user => user.status === 'ACTIVE').length,
  }), [users]);

  const handleCreate = async () => {
    if (!form.username.trim() || !form.password.trim()) {
      showToast('请输入账号和密码', 'error');
      return;
    }
    if (form.password.trim().length < 6) {
      showToast('密码至少 6 位', 'error');
      return;
    }
    setSaving(true);
    try {
      const user = await createUser({
        username: form.username.trim(),
        password: form.password,
        displayName: form.displayName.trim(),
        roleCode: form.roleCode,
      });
      setUsers(prev => [user, ...prev]);
      setForm({ username: '', password: '', displayName: '', roleCode: 'MEMBER' });
      setShowForm(false);
      showToast('用户已创建');
    } catch (error: any) {
      showToast(error.message || '创建失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  const columns = [
    { key: 'name', label: '用户', width: '220px' },
    { key: 'username', label: '账号', width: '160px' },
    { key: 'role', label: '角色', width: '100px' },
    { key: 'status', label: '状态', width: '100px' },
    { key: 'created', label: '创建时间', width: '140px' },
  ];

  return (
    <div className="p-4 animate-fade-in sm:p-6">
      <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-bold text-gray-900 tracking-tight">用户管理</h1>
          <p className="text-sm text-gray-500 mt-1">集中查看当前账号结构，并快速创建管理员、子管理员或普通成员。</p>
        </div>
        <div className="flex flex-wrap items-center gap-2 sm:justify-end">
          <button
            onClick={loadUsers}
            className="min-h-10 shrink-0 rounded-lg border border-gray-200 px-3 py-2 text-sm font-medium text-gray-700 transition-colors hover:border-gray-300 hover:bg-gray-50"
          >
            刷新列表
          </button>
          <button
            onClick={() => setShowForm(!showForm)}
            className="min-h-10 shrink-0 rounded-lg bg-slate-900 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-slate-800"
          >
            + 新建用户
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-3 mb-4">
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">总用户数</div>
          <div className="text-2xl font-semibold text-gray-900 mt-1">{counts.total}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">管理员</div>
          <div className="text-2xl font-semibold text-purple-700 mt-1">{counts.admins}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">子管理员</div>
          <div className="text-2xl font-semibold text-blue-700 mt-1">{counts.subAdmins}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">活跃用户</div>
          <div className="text-2xl font-semibold text-green-700 mt-1">{counts.active}</div>
        </div>
      </div>

      <div className="bg-white rounded-xl border border-gray-200 p-4 mb-4">
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">关键词</label>
            <input
              type="text"
              value={keyword}
              onChange={e => setKeyword(e.target.value)}
              placeholder="搜索账号、显示名或角色..."
              className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">角色</label>
            <select
              value={roleFilter}
              onChange={e => setRoleFilter(e.target.value as RoleFilter)}
              className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            >
              <option value="">全部角色</option>
              <option value="ADMIN">管理员</option>
              <option value="SUB_ADMIN">子管理员</option>
              <option value="MEMBER">普通用户</option>
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">状态</label>
            <select
              value={statusFilter}
              onChange={e => setStatusFilter(e.target.value as StatusFilter)}
              className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            >
              <option value="">全部状态</option>
              <option value="ACTIVE">活跃</option>
            </select>
          </div>
        </div>
      </div>

      {showForm && (
        <div className="bg-white rounded-xl border border-gray-200 p-5 mb-4">
          <div className="grid grid-cols-1 lg:grid-cols-4 gap-4 mb-3">
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">账号</label>
              <input type="text" value={form.username} onChange={e => setForm({ ...form, username: e.target.value })} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">密码</label>
              <input type="password" value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">显示名</label>
              <input type="text" value={form.displayName} onChange={e => setForm({ ...form, displayName: e.target.value })} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">角色</label>
              <select value={form.roleCode} onChange={e => setForm({ ...form, roleCode: e.target.value })} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none">
                <option value="MEMBER">普通用户</option>
                <option value="SUB_ADMIN">子管理员</option>
                <option value="ADMIN">管理员</option>
              </select>
            </div>
          </div>
          <div className="text-xs text-gray-500 mb-4">
            新账号创建后默认立即生效。当前后端还未提供编辑、停用或重置密码接口，因此这页先聚焦“检索 + 创建 + 结构查看”。
          </div>
          <div className="flex flex-wrap justify-end gap-2">
            <button onClick={() => setShowForm(false)} className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700">取消</button>
            <button onClick={handleCreate} disabled={saving} className="px-4 py-2 bg-slate-900 text-white text-sm font-semibold rounded-lg hover:bg-slate-800 disabled:opacity-50">
              {saving ? '创建中...' : '创建'}
            </button>
          </div>
        </div>
      )}

      <RichList
        items={filteredUsers}
        columns={columns}
        loading={loading}
        emptyText="暂无匹配用户"
        renderRow={(user: UserRecord) => (
          <>
            <div style={{ width: '220px' }}>
              <div className="text-sm font-medium text-gray-900">{user.displayName || user.username}</div>
            </div>
            <div className="font-mono text-sm text-gray-600" style={{ width: '160px' }}>{user.username}</div>
            <div style={{ width: '100px' }}>
              <span className={`text-[11px] font-medium px-2 py-0.5 rounded ${
                user.roleCode === 'ADMIN' ? 'bg-purple-50 text-purple-700' :
                user.roleCode === 'SUB_ADMIN' ? 'bg-blue-50 text-blue-700' :
                'bg-gray-100 text-gray-600'
              }`}>
                {user.roleCode === 'ADMIN' ? '管理员' : user.roleCode === 'SUB_ADMIN' ? '子管理员' : '用户'}
              </span>
            </div>
            <div style={{ width: '100px' }}>
              <span className={`text-[11px] font-medium px-2 py-0.5 rounded ${user.status === 'ACTIVE' ? 'bg-green-50 text-green-700' : 'bg-gray-100 text-gray-600'}`}>
                {statusLabel(user.status)}
              </span>
            </div>
            <div className="text-xs text-gray-500 font-mono" style={{ width: '140px' }}>
              {new Date(user.createdAt).toLocaleDateString('zh-CN')}
            </div>
          </>
        )}
      />
    </div>
  );
}

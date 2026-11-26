// ===== 路由上下文 =====
export type AppContext = 'workspace' | 'admin';

// ===== 路由状态 =====
export type PageRoute =
  | { name: 'workspace' }
  | { name: 'project'; projectId: number; tab?: string }
  | { name: 'collectors' }
  | { name: 'settings' }
  | { name: 'admin'; tab?: string };

// ===== 命令面板项 =====
export interface CommandItem {
  id: string;
  label: string;
  shortcut?: string;
  action: () => void;
  group: string;
}

// ===== Toast =====
export interface ToastMessage {
  id: string;
  message: string;
  type: 'success' | 'error' | 'info';
}

// ===== 项目侧边栏导航 =====
export interface ProjectNavItem {
  key: string;
  label: string;
  icon: string;
}

// ===== 管理后台侧边栏导航 =====
export interface AdminNavItem {
  key: string;
  label: string;
  icon: string;
}

// ===== 项目侧边栏菜单定义 =====
export const PROJECT_NAV_ITEMS: ProjectNavItem[] = [
  { key: 'overview', label: '项目概览', icon: '📊' },
  { key: 'generation', label: '测试用例生成', icon: '🤖' },
  { key: 'trace', label: '测试执行轨迹', icon: '🎯' },
  { key: 'assets', label: '资产总览', icon: '💎' },
  { key: 'mini-tom', label: '业务骨架 TOM', icon: '🧩' },
  { key: 'wiki', label: '业务知识 Wiki', icon: '📚' },
  { key: 'local-cases', label: '本地用例库', icon: '📋' },
  { key: 'formal-cases', label: '正式用例库', icon: '✅' },
  { key: 'automation-assets', label: '自动化资产', icon: '🧪' },
  { key: 'tools', label: '测试小工具', icon: '🔧' },
  { key: 'settings', label: '项目设置', icon: '⚙️' },
];

// ===== 管理后台菜单定义 =====
export const ADMIN_NAV_ITEMS: AdminNavItem[] = [
  { key: 'users', label: '用户管理', icon: '👥' },
  { key: 'models', label: '模型配置', icon: '🧠' },
  { key: 'prompts', label: '公共提示词库', icon: '💬' },
  { key: 'asset-center', label: '资产中心', icon: '📦' },
  { key: 'scan', label: '系统配置', icon: '🔍' },
  { key: 'loop', label: '学习回灌', icon: '🔄' },
  { key: 'diagnostics', label: '运行诊断', icon: '🩺' },
];

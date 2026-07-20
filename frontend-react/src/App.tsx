import { Component, lazy, Suspense, type ReactNode } from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { AppProvider } from './context/AppContext';
import { AuthProvider, useAuth } from './context/AuthContext';
import GlobalHeader from './layouts/GlobalHeader';
import ProjectLayout from './layouts/ProjectLayout';
import AdminLayout from './layouts/AdminLayout';
import LoginPage from './pages/LoginPage';

// 模块 A：Workspace Hub
const ProjectList = lazy(() => import('./pages/workspace/ProjectList'));
const CollectorManager = lazy(() => import('./pages/CollectorManager'));
const Settings = lazy(() => import('./pages/Settings'));

// 模块 B：Project Context
const ProjectOverview = lazy(() => import('./pages/project/ProjectOverview'));
const TestCaseGeneration = lazy(() => import('./pages/project/TestCaseGeneration'));
const TracePanelPage = lazy(() => import('./pages/project/TracePanelPage'));
const LocalCaseLibrary = lazy(() => import('./pages/project/LocalCaseLibrary'));
const FormalCaseLibrary = lazy(() => import('./pages/project/FormalCaseLibrary'));
const TestAssets = lazy(() => import('./pages/project/TestAssets'));
const MiniTom = lazy(() => import('./pages/project/MiniTom'));
const TestTools = lazy(() => import('./pages/project/TestTools'));
const ProjectSettings = lazy(() => import('./pages/project/ProjectSettings'));
const BusinessPacks = lazy(() => import('./pages/project/BusinessPacks'));
const WikiPackages = lazy(() => import('./pages/project/WikiPackages'));

// 模块 C：Management Console
const UserManagement = lazy(() => import('./pages/admin/UserManagement'));
const ModelConfig = lazy(() => import('./pages/admin/ModelConfig'));
const PromptLibrary = lazy(() => import('./pages/admin/PromptLibrary'));
const MiniTomModels = lazy(() => import('./pages/admin/MiniTomModels'));
const AssetLibrary = lazy(() => import('./pages/admin/AssetLibrary'));
const CandidateReview = lazy(() => import('./pages/admin/CandidateReview'));
const SystemConfig = lazy(() => import('./pages/admin/SystemConfig'));
const LoopConfig = lazy(() => import('./pages/admin/LoopConfig'));
const RuntimeDiagnostics = lazy(() => import('./pages/admin/RuntimeDiagnostics'));

function PageLoading() {
  return (
    <div className="p-6">
      <div className="animate-pulse bg-gray-200 rounded h-8 w-40" />
    </div>
  );
}

class RouteErrorBoundary extends Component<{ children: ReactNode }, { hasError: boolean; message: string }> {
  state = { hasError: false, message: '' };

  static getDerivedStateFromError(error: unknown) {
    return { hasError: true, message: errorMessage(error) };
  }

  componentDidCatch(error: unknown) {
    console.error('Route render failed', error);
    const message = errorMessage(error);
    if (isChunkLoadError(message) && !sessionStorage.getItem('aitest:chunk-reload-once')) {
      sessionStorage.setItem('aitest:chunk-reload-once', '1');
      window.location.reload();
      return;
    }
    if (!isChunkLoadError(message)) {
      sessionStorage.removeItem('aitest:chunk-reload-once');
    }
  }

  render() {
    if (!this.state.hasError) return this.props.children;
    return (
      <div className="p-6">
        <div className="rounded-xl border border-red-100 bg-red-50 px-4 py-3">
          <div className="text-sm font-semibold text-red-900">页面加载失败</div>
          <div className="mt-1 text-xs text-red-700">
            {isChunkLoadError(this.state.message)
              ? '前端资源版本已更新或缓存不一致，请点击下方按钮重新加载页面。'
              : '请重新点击左侧菜单或刷新页面。'}
          </div>
          {this.state.message && (
            <div className="mt-2 break-words rounded-lg bg-white/70 px-2 py-1 font-mono text-[11px] text-red-800">
              {this.state.message}
            </div>
          )}
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="mt-3 rounded-lg bg-red-600 px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-red-700"
          >
            重新加载页面
          </button>
        </div>
      </div>
    );
  }
}

function errorMessage(error: unknown) {
  if (error instanceof Error) return error.message;
  if (typeof error === 'string') return error;
  try {
    return JSON.stringify(error);
  } catch {
    return 'unknown error';
  }
}

function isChunkLoadError(message: string) {
  const value = message.toLowerCase();
  return value.includes('failed to fetch dynamically imported module')
    || value.includes('loading chunk')
    || value.includes('chunkloaderror')
    || value.includes('importing a module script failed');
}

function RouteShell({ children }: { children: ReactNode }) {
  const location = useLocation();
  return (
    <RouteErrorBoundary key={location.pathname}>
      <Suspense fallback={<PageLoading />}>{children}</Suspense>
    </RouteErrorBoundary>
  );
}

function ProtectedApp() {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <div className="animate-pulse bg-gray-200 rounded h-8 w-32" />
      </div>
    );
  }

  if (!user) {
    return <LoginPage />;
  }

  return (
    <div className="min-h-screen min-w-0 overflow-x-hidden bg-gray-50">
      <GlobalHeader />
      <RouteShell>
        <Routes>
          {/* 模块 A：Workspace Hub */}
          <Route path="/" element={<ProjectList />} />
          <Route path="/collectors" element={<CollectorManager />} />
          <Route path="/settings" element={<Settings />} />

          {/* 模块 B：Project Context（带侧边栏） */}
          <Route path="/projects/:projectId" element={<ProjectLayout />}>
            <Route index element={<ProjectOverview />} />
            <Route path="overview" element={<ProjectOverview />} />
            <Route path="generation" element={<TestCaseGeneration />} />
            <Route path="trace" element={<TracePanelPage />} />
            <Route path="local-cases" element={<LocalCaseLibrary />} />
            <Route path="formal-cases" element={<FormalCaseLibrary />} />
            <Route path="assets" element={<TestAssets />} />
            <Route path="mini-tom" element={<MiniTom />} />
            <Route path="tools" element={<TestTools />} />
            <Route path="settings" element={<ProjectSettings />} />
            <Route path="business-packs" element={<BusinessPacks />} />
            <Route path="wiki" element={<WikiPackages />} />
          </Route>

          {/* 模块 C：Management Console（带侧边栏） */}
          <Route path="/admin" element={<AdminLayout />}>
            <Route index element={<UserManagement />} />
            <Route path="users" element={<UserManagement />} />
            <Route path="models" element={<ModelConfig />} />
            <Route path="prompts" element={<PromptLibrary />} />
            <Route path="mini-tom" element={<MiniTomModels />} />
            <Route path="assets" element={<AssetLibrary />} />
            <Route path="candidates" element={<CandidateReview />} />
            <Route path="scan" element={<SystemConfig />} />
            <Route path="loop" element={<LoopConfig />} />
            <Route path="diagnostics" element={<RuntimeDiagnostics />} />
          </Route>

          {/* 兜底路由 */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </RouteShell>
    </div>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AppProvider>
        <AuthProvider>
          <ProtectedApp />
        </AuthProvider>
      </AppProvider>
    </BrowserRouter>
  );
}

import { lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
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

function PageLoading() {
  return (
    <div className="p-6">
      <div className="animate-pulse bg-gray-200 rounded h-8 w-40" />
    </div>
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
      <Suspense fallback={<PageLoading />}>
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
          </Route>

          {/* 兜底路由 */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
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

import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import Landing from './pages/landing/Landing';
import Login from './pages/Login';
import DashboardLayout from './pages/dashboard/DashboardLayout';
import CasesPanel from './pages/dashboard/CasesPanel';
import RunsPanel from './pages/dashboard/RunsPanel';
import AgentPanel from './pages/dashboard/AgentPanel';
import DatasetsPanel from './pages/dashboard/DatasetsPanel';
import ApprovalsPanel from './pages/dashboard/ApprovalsPanel';
import { useSession } from './lib/useSession';

/**
 * 没登录就送去登录页，并记住原来要去哪。
 *
 * 读接口其实是放行的，dashboard 不登录也能看 —— 但写侧（新建 / 编辑 / 提交）
 * 需要 token，而 401 会清掉登录态。与其让人点了按钮才发现，不如进门就登。
 */
function RequireAuth({ children }: { children: ReactNode }) {
  const session = useSession();
  const location = useLocation();

  if (!session) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return <>{children}</>;
}

/** 五个面板各有自己的 URL —— 演示时可以直接把某一屏的链接发出去 */
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Landing />} />
      <Route path="/login" element={<Login />} />
      <Route
        path="/dashboard"
        element={
          <RequireAuth>
            <DashboardLayout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="cases" replace />} />
        <Route path="cases" element={<CasesPanel />} />
        <Route path="runs" element={<RunsPanel />} />
        <Route path="agent" element={<AgentPanel />} />
        <Route path="datasets" element={<DatasetsPanel />} />
        <Route path="approvals" element={<ApprovalsPanel />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

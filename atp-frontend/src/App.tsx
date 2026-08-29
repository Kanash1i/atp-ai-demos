import { Navigate, Route, Routes } from 'react-router-dom';
import Landing from './pages/landing/Landing';
import DashboardLayout from './pages/dashboard/DashboardLayout';
import CasesPanel from './pages/dashboard/CasesPanel';
import RunsPanel from './pages/dashboard/RunsPanel';
import AgentPanel from './pages/dashboard/AgentPanel';
import DatasetsPanel from './pages/dashboard/DatasetsPanel';
import ApprovalsPanel from './pages/dashboard/ApprovalsPanel';

/** 五个面板各有自己的 URL —— 演示时可以直接把某一屏的链接发出去 */
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Landing />} />
      <Route path="/dashboard" element={<DashboardLayout />}>
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

import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { useAuthStore } from './store/authStore';
import Login from './pages/auth/Login';
import ChangePasswordPage from './pages/ChangePasswordPage';
import DashboardPage from './pages/DashboardPage';
import Phase1Stub from './pages/Phase1Stub';
import OrgSetupPage from './pages/OrgSetupPage';
import { Shell } from './components/Shell';

function RequireAuth({ children }: { children: React.ReactNode }) {
  const token = useAuthStore((s) => s.token);
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function RequirePasswordChanged({ children }: { children: React.ReactNode }) {
  const token = useAuthStore((s) => s.token);
  const user  = useAuthStore((s) => s.user);
  if (!token) return <Navigate to="/login" replace />;
  if (user?.mustChangePassword) return <Navigate to="/change-password" replace />;
  return <>{children}</>;
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* Public */}
        <Route path="/login" element={<Login />} />

        {/* Auth required, password-change gate */}
        <Route
          path="/change-password"
          element={
            <RequireAuth>
              <ChangePasswordPage />
            </RequireAuth>
          }
        />

        {/* Shell layout — all authenticated pages */}
        <Route
          element={
            <RequirePasswordChanged>
              <Shell />
            </RequirePasswordChanged>
          }
        >
          {/* Phase 1 — real pages (per-role Phase 2 items are caught by Shell → ComingInPhase) */}
          <Route path="/dashboard"    element={<DashboardPage />} />
          <Route path="/attendance"   element={<Phase1Stub />} />
          <Route path="/leave"        element={<Phase1Stub />} />
          <Route path="/help"         element={<Phase1Stub />} />
          <Route path="/approvals"    element={<Phase1Stub />} />
          <Route path="/employees"    element={<Phase1Stub />} />
          <Route path="/organization" element={<OrgSetupPage />} />
          <Route path="/access"       element={<Phase1Stub />} />
          <Route path="/masters"      element={<OrgSetupPage />} />
        </Route>

        <Route path="/"  element={<Navigate to="/dashboard" replace />} />
        <Route path="*"  element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

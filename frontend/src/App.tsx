import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { useAuthStore } from './store/authStore';
import Login from './pages/auth/Login';
import ForgotPasswordPage from './pages/auth/ForgotPasswordPage';
import ChangePasswordPage from './pages/ChangePasswordPage';
import DashboardPage from './pages/DashboardPage';
import AttendancePage from './pages/AttendancePage';
import SuperAdminRegularizationPage from './pages/SuperAdminRegularizationPage';
import Phase1Stub from './pages/Phase1Stub';
import OrgSetupPage from './pages/OrgSetupPage';
import EmployeeMasterPage from './pages/EmployeeMasterPage';
import ExceptionDashboardPage from './pages/ExceptionDashboardPage';
import UserManagementPage from './pages/UserManagementPage';
import ProfilePage from './pages/ProfilePage';
import NotificationsPage from './pages/NotificationsPage';
import DirectoryPage from './pages/DirectoryPage';
import HierarchyPage from './pages/HierarchyPage';
import LeavePage from './pages/LeavePage';
import ApprovalsPage from './pages/ApprovalsPage';
import MyRequestsPage from './pages/MyRequestsPage';
import AssetsExpensesPage from './pages/AssetsExpensesPage';
import DocumentsPage from './pages/DocumentsPage';
import DocumentsCompliancePage from './pages/DocumentsCompliancePage';
import PoliciesPage from './pages/PoliciesPage';
import OnboardingPage from './pages/OnboardingPage';
import { toShellRole } from './lib/nav.config';
import { Shell } from './components/Shell';
import { ToastProvider } from './context/ToastContext';

function RequireAuth({ children }: { children: React.ReactNode }) {
  const token = useAuthStore((s) => s.token);
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function DocumentsRouter() {
  const user = useAuthStore(s => s.user);
  const role = toShellRole(user?.role);
  return role === 'HR Admin' || role === 'Super Admin'
    ? <DocumentsCompliancePage />
    : <DocumentsPage />;
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
    <ToastProvider>
    <BrowserRouter>
      <Routes>
        {/* Public */}
        <Route path="/login" element={<Login />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />

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
          <Route path="/attendance"   element={<AttendancePage />} />
          <Route path="/attendance/regularization/all" element={<SuperAdminRegularizationPage />} />
          <Route path="/leave"        element={<LeavePage />} />
          <Route path="/help"         element={<Phase1Stub />} />
          <Route path="/approvals"    element={<ApprovalsPage />} />
          <Route path="/requests"     element={<MyRequestsPage />} />
          <Route path="/assets"       element={<AssetsExpensesPage />} />
          <Route path="/employees"    element={<EmployeeMasterPage />} />
          {/* Route path must stay in sync with the 'exceptions' nav.config.ts entry — Shell gates rendering by matching nav item, not this route list */}
          <Route path="/exceptions"   element={<ExceptionDashboardPage />} />
          <Route path="/organization" element={<OrgSetupPage />} />
          <Route path="/access"         element={<UserManagementPage />} />
          <Route path="/masters"        element={<OrgSetupPage />} />
          <Route path="/profile"        element={<ProfilePage />} />
          <Route path="/notifications"  element={<NotificationsPage />} />
          <Route path="/directory"      element={<DirectoryPage />} />
          <Route path="/hierarchy"      element={<HierarchyPage />} />
          <Route path="/documents"      element={<DocumentsRouter />} />
          <Route path="/policies"       element={<PoliciesPage />} />
          <Route path="/onboarding"     element={<OnboardingPage />} />
        </Route>

        <Route path="/"  element={<Navigate to="/dashboard" replace />} />
        <Route path="*"  element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </BrowserRouter>
    </ToastProvider>
  );
}

/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW - NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  The summary tiles of each module page, rendered by that page's own tile component (the same ones the
 *  live pages use) with invented numbers; no login, session or API.
 *
 *    /tiles-ui-preview?page=menu (left menu by role) | masters (Org Masters header) | people (employee name cell) | headers (page title icons) | performance|access|leave|requests|my-requests|assets|policies|documents|audit|reports|api-usage
 *  Assets shows the Employee, Manager and HR Admin rows. The "Theme" swatches use the app's real
 *  accent-color setting.
 * ============================================================================
 */
import { useSearchParams } from 'react-router-dom';
import {
  Users, Sparkles, Award, AlertTriangle, Wallet, CheckCircle2, Hourglass, CalendarClock,
  Inbox, CircleDot, Clock, Lock, MessageSquareText, Zap, Activity, Gauge, CalendarCheck,
  CalendarDays, CalendarHeart, User, Ticket, ClipboardList,
  Package, Briefcase, UsersRound, ShieldCheck, FileText, Contact, ShieldAlert, BookOpen, LifeBuoy, Network,
  Bell, UserPlus, Building2, Megaphone, FolderKanban, BarChart3, ClipboardCheck, TrendingUp, Workflow, ScrollText,
  type LucideIcon,
} from 'lucide-react';
import PageTitleBlock from '../../components/PageTitleBlock';
import { PersonCell } from '../../components/PersonCell';
import { SidebarNav } from '../../components/SidebarNav';
import { NAV, type Role } from '../../lib/nav.config';
import { TabBar } from '../../components/TabBar';
import { useEffect, useState } from 'react';
import { useAccentColor, ACCENT_SWATCHES, type AccentColor } from '../../lib/accentColor';
import { KpiCard } from '../talent/DashboardTab';
import { KpiTile } from '../LeavePage';
import { AccessStatTiles } from '../UserManagementPage';
import { Kpi } from '../HelpDeskAdminPage';
import MyRequestsPage, { StatRing } from '../MyRequestsPage';
import { Tile } from '../assets/shared';
import { PolicyStatTiles } from '../PoliciesPage';
import { DocumentStatTiles } from '../DocumentsPage';
import { AuditStatCards } from '../../components/audit/AuditStatCards';
import { AuditCategoryChips } from '../../components/audit/AuditCategoryChips';
import { AuditLogTable } from '../../components/audit/AuditLogTable';
import type { AuditLogEntry } from '../../api/audit';
import ReportsPage, { KpiCard as ReportKpi, Section as ReportSection, HeadcountRows } from '../ReportsPage';
import ApiUsagePage, { KpiCard as ApiKpi } from '../ApiUsagePage';
import { useAuthStore } from '../../store/authStore';
import { HRView } from '../AssetsExpensesPage';
import BenchOpportunitiesPage from '../BenchOpportunitiesPage';
import NotificationsPage from '../NotificationsPage';
import ProfilePage from '../ProfilePage';
import UserPreferencesPage from '../UserPreferencesPage';
import ChangePasswordPage from '../ChangePasswordPage';
import { AttentionPanel, ReportsTab as MyTeamReportsTab, KpiCard as TeamKpiCard, LeaveTodayPanel, NotInYetPanel, KudosModal, EmployeeDetailModal, EmployeeDetailsModal, NotInYetListModal } from '../MyTeamPage';
import { Users as KIcTeam, CheckCircle2 as KIcOk, Clock as KIcLate, Home as KIcHome, MapPin as KIcPin } from 'lucide-react';
import type { ApprovalItem } from '../../api/approvalCenter';
import PoliciesPage from '../PoliciesPage';
import { WizardSteps } from '../user-management/WizardSteps';
import { BasicInfoStep } from '../user-management/steps/BasicInfoStep';
import { EmploymentStep } from '../user-management/steps/EmploymentStep';
import { WorkPolicyStep } from '../user-management/steps/WorkPolicyStep';
import { CompensationFollowUp } from '../user-management/CompensationFollowUp';
import { CreateEmployeeResult } from '../user-management/CreateEmployeeResult';
import type { OrgOptions } from '../user-management/orgOptionsHelpers';
import type { CreateUserPayload } from '../../api/employees';
import '../user-management/createEmployee.css';
import RolesPermissionsPage from '../RolesPermissionsPage';
import DocumentsCompliancePage from '../DocumentsCompliancePage';
import BenchPage from '../BenchPage';
import ProjectAllocationsPage from '../ProjectAllocationsPage';
import OnboardingPage from '../OnboardingPage';
import DocumentsPage from '../DocumentsPage';
import HelpDeskPage from '../HelpDeskPage';
import WorkflowStudioPage from '../WorkflowStudioPage';
import TalentPerformancePage from '../talent/TalentPerformancePage';
import LeavePage from '../LeavePage';
import ApprovalsPage from '../ApprovalsPage';
import DashboardPage from '../DashboardPage';
import DirectoryPage from '../DirectoryPage';
import UserManagementPage, { EditModal as UserEditModal } from '../UserManagementPage';
import OrgSetupPage from '../OrgSetupPage';
import HolidaysPage, { HolidayMonthCalendar } from '../HolidaysPage';
import { EditModal as EmployeeEditModal } from '../EmployeeMasterPage';
import type { EmployeeRecord } from '../../api/employees';

const PAGES: { key: string; label: string; title: string; subtitle: string }[] = [
  { key: 'menu', label: 'Left menu', title: 'Left menu by role', subtitle: 'The real sidebar for each role, with the disabled "P2" placeholder modules removed.' },
  { key: 'masters', label: 'Org Masters', title: 'Organization Masters header', subtitle: 'The search and Add controls now sit in the page header, above the tabs.' },
  { key: 'people', label: 'People cell', title: 'Employee name cell', subtitle: 'User Management, Employee Master and People Directory now show an employee the same way: photo when they have one, name, then Employee ID.' },
  { key: 'headers', label: 'Page title icons', title: 'Page title icons', subtitle: 'Every module page opens with the same header card: icon, title, subtitle and its buttons, with the theme-colour curves.' },
  { key: 'performance', label: 'Performance', title: 'Performance', subtitle: 'Org-wide skills, certificates, and learning - dashboard, search, and directory.' },
  { key: 'access', label: 'Access', title: 'User Management', subtitle: 'Create and manage user accounts and roles.' },
  { key: 'leave', label: 'Leave', title: 'Leave & Holidays', subtitle: 'Your leave balances and requests.' },
  { key: 'requests', label: 'Requests (HR)', title: 'HR Service Requests', subtitle: 'Help Desk tickets raised by employees across the organization.' },
  { key: 'my-requests', label: 'Requests (Employee)', title: 'My Requests', subtitle: 'Track your requests at a glance - click a stat to filter.' },
  { key: 'assets', label: 'Assets & Expenses', title: 'Assets & Expense Management', subtitle: 'Assigned assets, expense claims and asset requests.' },
  { key: 'policies', label: 'Policies', title: 'Policies & Announcements', subtitle: 'Publish policies and company announcements.' },
  { key: 'audit', label: 'Audit', title: 'Audit & Security', subtitle: 'Your administrative activity history.' },
  { key: 'reports', label: 'Reports', title: 'Reports & Analytics', subtitle: 'Headcount, attendance and leave summaries.' },
  { key: 'api-usage', label: 'API Usage', title: 'API Usage', subtitle: 'Assistant request volume, tokens and latency.' },
  { key: 'holidays', label: 'Holidays calendar', title: 'Holidays', subtitle: 'Month calendar with holidays highlighted, today marked and a legend.' },
  { key: 'api-page', label: 'API Usage (full page)', title: 'API Usage', subtitle: 'The whole page with sample data: billing, knowledge base, period switch and charts.' },
  { key: 'assets-page', label: 'Assets (full page)', title: 'Assets & Expenses', subtitle: 'The HR view with sample data: pill tabs, filters, inventory table and inline row actions.' },
  { key: 'opps-page', label: 'Opportunities (full page)', title: 'Internal Opportunities', subtitle: 'HR view with sample data: post form with skill chips and opportunity cards.' },
  { key: 'notifications-page', label: 'Notifications (full page)', title: 'Notifications', subtitle: 'Sample data: filter bar, grouped list and the detail pane (select a notification).' },
  { key: 'profile-page', label: 'My Profile (full page)', title: 'My Profile', subtitle: 'Sample data: About, Profile, Job and Assets tabs with the Edit popups. Use ?role=employee for the employee view.' },
  { key: 'prefs-page', label: 'User Preferences (full page)', title: 'User Preferences', subtitle: 'Appearance, Notifications and Accessibility. Changes here apply to this preview only.' },
  { key: 'change-password-page', label: 'Change Password (full page)', title: 'Change Password', subtitle: 'Add &forced=1 for the first-login version. Nothing is submitted to a server here.' },
  { key: 'onboarding-page', label: 'Onboarding (full page)', title: 'Onboarding', subtitle: 'All four tabs, the checklist detail view and its popups, with sample data. Changes here only affect this preview.' },
  { key: 'team-popups', label: 'My Team: Popups', title: 'My Team: Popups', subtitle: 'Open each popup. Sending kudos only works against a backend, so Send is a no-op here.' },
  { key: 'team-today', label: 'My Team: Today tiles & panels', title: 'My Team: Today', subtitle: 'The KPI tiles plus the Who is on leave and Not in yet panels, as the page renders them.' },
  { key: 'team-reports', label: 'My Team: Reports tab', title: 'My Team: Reports', subtitle: 'The Reports tab of My Team. Cards open the real download dialogs; nothing is sent from this preview.' },
  { key: 'attention-panel', label: 'My Team: Needs your attention', title: 'My Team: Needs your attention', subtitle: 'Sample pending requests. Approve / Reject here only change this preview.' },
  { key: 'allocations-page', label: 'Project Allocations (full page)', title: 'Project Allocations', subtitle: 'Allocate and Projects tabs with sample data. Allocating and saving only change this preview.' },
  { key: 'bench-page', label: 'Bench Management (full page)', title: 'Bench Management', subtitle: 'All five tabs with sample data (HR view). Saving, marking and ending bench only change this preview.' },
  { key: 'docs-compliance-page', label: 'Documents & Compliance (HR)', title: 'Documents & Compliance', subtitle: 'HR view with sample data: three tabs and the review dialog. Approve, reject and remind only change this preview.' },
  { key: 'employee-edit', label: 'Edit Employee popup', title: 'Employee Master: Edit popup', subtitle: 'Open the popup for an active or an inactive employee. Saving only changes this preview.' },
  { key: 'user-edit', label: 'Edit User popup (Access)', title: 'User Management: Edit popup', subtitle: 'Open the popup for an active or an inactive user. Saving only changes this preview.' },
  { key: 'roles-page', label: 'Roles & Permissions (full page)', title: 'Roles & Permissions', subtitle: 'Super Admin view with sample roles. Create, edit, deactivate and delete only change this preview.' },
  { key: 'create-employee-steps', label: 'Create Employee (all 4 steps)', title: 'Create Employee: all four steps', subtitle: 'Pick any step, and the result screen, to see the layout with sample values. Nothing is saved, and the real wizard still checks each step before moving on.' },
  { key: 'policies-page', label: 'Policies (full page)', title: 'Policies & Announcements', subtitle: 'HR view with sample data: pill tabs, filters, table and the slide-out action dock.' },
  { key: 'documents-page', label: 'My Documents (full page)', title: 'My Documents & Policies', subtitle: 'Employee view with sample data: banner, pill tabs, search, table, policy and announcement cards.' },
  { key: 'my-requests-page', label: 'My Requests (full page)', title: 'My Requests', subtitle: 'Employee view with sample requests. Click View Details on any row to see the redesigned popup (leave, regularization, WFH, partial day, overtime; pending, approved, rejected).' },
  { key: 'help-page', label: 'Help & Guidance (full page)', title: 'Help & Guidance', subtitle: 'HR view with sample data: hero search, FAQ cards, guide cards and the always-visible action buttons.' },
  { key: 'workflows-page', label: 'Workflow Studio (full page)', title: 'Workflow Studio', subtitle: 'Sample rules: themed table, stage flow pills, condition chips and the action dock.' },
  { key: 'talent-page', label: 'Performance tabs (full page)', title: 'Performance', subtitle: 'Sample data. Add &tab=search, &tab=directory or &tab=settings to the URL to open that tab.' },
  { key: 'leave-page', label: 'Leave (full page)', title: 'Leave', subtitle: 'Employee view with sample data: KPIs, balance cards, filters, history table and the Manage dock on pending requests.' },
  { key: 'approvals-page', label: 'Approval Center (full page)', title: 'Approval Center', subtitle: 'Sample queue: Employee ID under each name, a table that fits without sideways scrolling, and the shared pagination.' },
  { key: 'dashboard-page', label: 'Super Admin dashboard (full page)', title: 'Dashboard', subtitle: 'Sample data: approvals donut, account health, users by role, birthdays, notifications and recent audit events.' },
  { key: 'holidays-page', label: 'Holidays page (full)', title: 'Leave & Holidays', subtitle: 'Sample data: summary tiles, upcoming holidays, month calendar and the holiday list.' },
  { key: 'directory-page', label: 'People Directory + details drawer', title: 'People Directory', subtitle: 'Sample data. Click View (or a row) to open the details drawer; add &role=hr or &role=employee to the URL to see other roles.' },
  { key: 'user-mgmt-page', label: 'User Management + Add/Edit popups', title: 'User Management', subtitle: 'Sample data. Click Add User, or a row Manage > Edit, to see the popups.' },
  { key: 'masters-page', label: 'Org Masters + Add popups (real page)', title: 'Organization Masters', subtitle: 'Sample data. Pick a tab and click its Add button (Business Unit, Department, Designation, Location, Shift...) to see the popup.' },
  { key: 'reports-page', label: 'Reports + KPI drill-down (real page)', title: 'Reports & Analytics', subtitle: 'Sample data. Click a KPI tile (Total Headcount, Present, Late/Absent, Leave) to open its employee list.' },
  { key: 'documents', label: 'My Documents', title: 'My Documents & Policies', subtitle: 'Manage your required documents and acknowledge company policies.' },
];

const HEADERS: { icon: LucideIcon; title: string; subtitle: string }[] = [
  { icon: Clock, title: 'Attendance', subtitle: 'Your attendance, calendar and requests.' },
  { icon: ClipboardCheck, title: 'Approval Center', subtitle: 'All pending requests requiring your decision.' },
  { icon: Users, title: 'People Directory', subtitle: 'Manage and view all employees, their details and organizational information.' },
  { icon: User, title: 'User Management', subtitle: 'Manage access, roles, and account status for all users.' },
  { icon: CalendarDays, title: 'Leave', subtitle: 'View your balance, request leave, and track approvals.' },
  { icon: CalendarHeart, title: 'Company Holidays', subtitle: 'Holidays for your work location.' },
  { icon: Ticket, title: 'HR Service Requests', subtitle: 'Help Desk tickets raised by employees across the organization.' },
  { icon: ClipboardList, title: 'My Requests', subtitle: 'All your submitted leave and attendance requests in one place.' },
  { icon: TrendingUp, title: 'Performance', subtitle: 'Org-wide skills, certificates, and learning - dashboard, search, and directory.' },
  { icon: Network, title: 'Organization Hierarchy', subtitle: 'Click any card to re-center the view. Read-only - manage reporting lines in User Management.' },
  { icon: Building2, title: 'Organization Masters', subtitle: 'Departments, locations, shifts and other master data.' },
  { icon: Workflow, title: 'Workflow Studio', subtitle: 'Design and manage approval workflows.' },
  { icon: Contact, title: 'Employee Master', subtitle: 'Employee records and their details.' },
  { icon: UserPlus, title: 'Onboarding', subtitle: 'Track new joiners through onboarding.' },
  { icon: UsersRound, title: 'Bench Management', subtitle: 'See who is benched and for how long, find people by skill, and move them back to active work.' },
  { icon: Briefcase, title: 'Internal Opportunities', subtitle: 'Short-term internal roles, shown only to employees currently on bench.' },
  { icon: FolderKanban, title: 'Project Allocations', subtitle: 'Who is allocated to which project.' },
  { icon: Award, title: 'My Skills', subtitle: 'Your skills, certificates and learning.' },
  { icon: ShieldAlert, title: 'Exception Dashboard', subtitle: 'Attendance exceptions that need attention.' },
  { icon: ClipboardCheck, title: 'Super Admin Regularization', subtitle: 'Regularization requests across the organization.' },
  { icon: Package, title: 'Assets & Expense Management', subtitle: 'Manage company inventory, expense policies, and payroll-ready claims.' },
  { icon: Megaphone, title: 'Policies & Announcements', subtitle: 'Publish policies and company announcements.' },
  { icon: FileText, title: 'My Documents & Policies', subtitle: 'Manage your required documents and acknowledge company policies.' },
  { icon: ShieldCheck, title: 'Documents & Compliance', subtitle: 'Review employee documents and compliance status.' },
  { icon: ScrollText, title: 'Audit & Security', subtitle: 'Your administrative activity history.' },
  { icon: BarChart3, title: 'Reports & Analytics', subtitle: 'Headcount, attendance and leave summaries.' },
  { icon: Gauge, title: 'API Usage', subtitle: 'NORA request volume, token usage and estimated spend.' },
  { icon: Bell, title: 'Notifications', subtitle: 'Everything that needs your attention.' },
  { icon: LifeBuoy, title: 'Help & Guidance', subtitle: 'Answers, guides and how to reach HR.' },
  { icon: BookOpen, title: 'Help & Guidance Content', subtitle: 'Manage the help articles employees see.' },
];

const photo = (bg: string, fg: string) => 'data:image/svg+xml;utf8,' + encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" fill="${bg}"/><circle cx="32" cy="25" r="12" fill="${fg}"/><path d="M8 64c2-16 14-22 24-22s22 6 24 22z" fill="${fg}"/></svg>`);
const PEOPLE = [
  { id: 'u1', name: 'Abuzar Siddiqui', code: 'NF-20261004', email: 'abuzar.siddiqui@nforceone.com', role: 'MANAGER', dept: 'Finance', photo: null as string | null },
  { id: 'u2', name: 'Aanuj Reddy K', code: 'NF-20261033', email: 'aanuj.kormilla@nforceone.com', role: 'EMPLOYEE', dept: 'Engineering', photo: photo('#dfe3ea', '#6b7280') },
  { id: 'u3', name: 'Venkat Narsimha Reddy', code: 'NF-20261019', email: 'vyshnavi.guthala+6@nforceone.com', role: 'EMPLOYEE', dept: 'Sales', photo: null },
  { id: 'u4', name: 'Vandana', code: 'NF-20261096', email: 'vandana.donga@nforceone.com', role: 'EMPLOYEE', dept: 'Engineering', photo: photo('#f3d6dc', '#b4515f') },
];
const th: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const td: React.CSSProperties = { padding: '10px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

const HEADER_ACTIONS: Record<string, string[]> = {
  'Attendance': ['Export selected month', 'Request Regularization'],
  'People Directory': ['Export to Excel'],
  'User Management': ['+ Add User'],
  'Leave': ['Request Leave'],
  'Company Holidays': ['+ Add Holiday'],
  'My Requests': ['+ New Request'],
  'Organization Masters': ['Search departments…', '+ Add Department'],
  'Workflow Studio': ['+ New Workflow'],
  'Employee Master': ['+ Add Employee'],
  'Onboarding': ['+ Start Onboarding'],
  'Policies & Announcements': ['+ New Policy'],
  'Reports & Analytics': ['Export'],
  'Notifications': ['Mark all read'],
  'Help & Guidance Content': ['+ New Content'],
  'Organization Hierarchy': ['All Departments', 'Search people…'],
};

const grid = (min: number): React.CSSProperties => ({ display: 'grid', gridTemplateColumns: `repeat(auto-fit, minmax(${min}px, 1fr))`, gap: 14 });
const rowTitle: React.CSSProperties = { fontSize: 11, fontWeight: 800, letterSpacing: '.08em', textTransform: 'uppercase', color: 'var(--txt-dim)', margin: '6px 0 -4px' };
const noop = () => {};

function MastersMock() {
  const [tab, setTab] = useState('departments');
  const [q, setQ] = useState('');
  const TABS = [{ key: 'bu', label: 'Business Units' }, { key: 'departments', label: 'Departments' }, { key: 'designations', label: 'Designations' }, { key: 'locations', label: 'Locations' }];
  const label = TABS.find((t) => t.key === tab)?.label ?? '';
  const addLabel = 'Add ' + (label === 'Business Units' ? 'Business Unit' : label.replace(/s$/, ''));
  const rows = ['Engineering', 'Finance', 'Quality Engineering', 'Sales Operations'].filter((r) => r.toLowerCase().includes(q.toLowerCase()));
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 14, flexWrap: 'wrap' }}>
        <PageTitleBlock icon={Building2}>
          <h1 style={{ margin: 0, marginBottom: 4 }}>Organization Masters</h1>
          <p style={{ margin: 0 }}>Configure global master data that all modules and roles reference.</p>
        </PageTitleBlock>
        <div className="nf-org-toolbar-actions" style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 8, flexWrap: 'wrap' }}>
          <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={`Search ${label.toLowerCase()}…`} aria-label={`Search ${label}`}
            style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '5px 10px', fontSize: 12, color: 'var(--txt)', outline: 'none', width: 196 }} />
          <button style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '6px 12px', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 6, fontSize: 12, fontWeight: 600, cursor: 'pointer', whiteSpace: 'nowrap' }}>
            + {addLabel}
          </button>
        </div>
      </div>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        <div style={{ borderBottom: '1px solid var(--line)' }}>
          <TabBar ariaLabel="Organization sections" style={{ padding: '0 4px', boxShadow: 'none' }} tabs={TABS} active={tab} onChange={setTab} />
        </div>
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Name', 'Employees', 'Status'].map((h) => <th key={h} style={th}>{h}</th>)}</tr></thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={r}><td style={{ ...td, color: 'var(--txt)', fontWeight: 600 }}>{r}</td><td style={td}>{[18, 3, 13, 1][i] ?? 0}</td><td style={td}>Active</td></tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

const AUDIT_ROWS: AuditLogEntry[] = [
  ['ATTENDANCE_REGULARIZATION_APPROVED', 'ATTENDANCE', 'Veera Venkata Chandrasekhar Reddy Gandrala', '2026-10-21T19:43:00'],
  ['ATTENDANCE_REGULARIZATION_APPROVED', 'ATTENDANCE', 'Nikitha Toom', '2026-10-21T19:43:00'],
  ['HELPDESK_TICKET_STATUS_CHANGED', 'OTHER', '878af0dc...', '2026-10-19T19:25:00'],
  ['LEAVE_APPROVED', 'LEAVE', 'Arun Kumar', '2026-10-18T11:02:00'],
  ['EMPLOYEE_UPDATED', 'EMPLOYEE', 'Meera Nair', '2026-10-17T16:40:00'],
  ['USER_ROLE_CHANGED', 'ACCESS', 'Rahul Verma', '2026-10-16T09:15:00'],
].map(([action, actionGroup, targetLabel, occurredAt], i) => ({
  id: i + 1, occurredAt, actorId: null, actorName: i === 2 ? null : ['Naveen Kumar R', 'Anil Kumar Ramtenki', 'Vignesh Appani'][i % 3], actorEmail: i === 2 ? null : ['naveen@nforceone.com', 'anil@nforceone.com', 'vignesh@nforceone.com'][i % 3], action, actionCategory: action,
  actionGroup, targetId: null, targetLabel, targetEmployeeCode: null, affectedUsers: null, beforeState: null, afterState: null,
} as unknown as AuditLogEntry)).concat([{
  id: 99, occurredAt: '2026-11-11T20:00:00', actorId: null, actorName: 'Naveen Kumar R', actorEmail: 'superadmin@nforceone.com', action: 'ROLE_PERMISSIONS_UPDATED', actionCategory: 'ACCESS',
  actionGroup: 'ACCESS', targetId: null, targetLabel: 'Manager', targetEmployeeCode: null, affectedUsers: null,
  beforeState: JSON.stringify({ permissions: 'project_allocations:CREATE:TEAM,project_allocations:VIEW:TEAM,projects:CREATE:TEAM,projects:VIEW:TEAM,leave:APPROVE:TEAM,attendance:VIEW:TEAM' }),
  afterState: JSON.stringify({ permissions: 'project_allocations:CREATE:ALL,project_allocations:VIEW:ALL,projects:CREATE:ALL,projects:VIEW:ALL,leave:APPROVE:ALL,attendance:VIEW:ALL' }),
} as unknown as AuditLogEntry]);

function AuditPillsMock({ showAccess }: { showAccess: boolean }) {
  const [g, setG] = useState<'ALL' | 'EMPLOYEE' | 'ATTENDANCE' | 'LEAVE' | 'EXPENSE' | 'ASSET' | 'ACCESS' | 'OTHER'>('ALL');
  const rows = g === 'ALL' ? AUDIT_ROWS : AUDIT_ROWS.filter(r => r.actionGroup === g);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <AuditCategoryChips active={g} onChange={setG} showAccess={showAccess} />
      <div className="nf-audit-meta">
        <span className="nf-audit-count"><b>{rows.length}</b> record(s) found</span>
        {showAccess && <span className="nf-audit-note">Includes access-control events</span>}
      </div>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        <AuditLogTable rows={rows} />
      </div>
    </div>
  );
}

function ApiUsageMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: answer the page's three API calls with sample data, and act as a Super Admin,
    // then put everything back when leaving. Nothing here ships into the real page.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const day = (i: number) => new Date(Date.now() - (13 - i) * 86400000).toISOString().slice(0, 10);
    const daily = Array.from({ length: 14 }, (_, i) => ({
      date: day(i), requestCount: 40 + ((i * 37) % 120), successCount: 0, errorCount: i % 4,
      promptTokens: 120000 + ((i * 53000) % 260000), completionTokens: 4000 + ((i * 1700) % 9000), embeddingTokens: (i % 3) * 400,
    }));
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/admin/usage-stats')) {
        return json({
          from: day(0), to: day(13), totalRequests: 2426, totalTurns: 1463, successCount: 1090, errorCount: 373,
          totalPromptTokens: 8100000, totalCompletionTokens: 202100, totalEmbeddingTokens: 10900, totalTokens: 8347112, avgLatencyMs: 10753,
          daily,
          byErrorCode: [{ key: 'RATE_LIMITED', count: 241 }, { key: 'UPSTREAM_TIMEOUT', count: 96 }, { key: 'EMPTY_RESPONSE', count: 36 }],
          byResponseType: [{ key: 'HOW_TO', count: 540 }, { key: 'EXPLANATION', count: 310 }, { key: 'NAVIGATION', count: 260 }, { key: 'TROUBLESHOOTING', count: 190 }, { key: 'PERMISSION', count: 120 }, { key: 'UNKNOWN', count: 43 }],
        });
      }
      if (url.includes('/admin/billing-settings')) {
        return json({ id: '1', monthlyBudgetUsd: 10, promptCostPerMillionUsd: 0.1, completionCostPerMillionUsd: 0.3, embeddingCostPerMillionUsd: 0.1 });
      }
      if (url.includes('/admin/billing')) {
        return json({ monthStart: new Date().toISOString().slice(0, 8) + '01', today: day(13), promptTokens: 2991951, completionTokens: 55483, embeddingTokens: 3180, monthlyBudgetUsd: 10, estimatedCostUsd: 0.46, usedPercent: 4.6 });
      }
      if (url.includes('/api/ai-assistant/health')) {
        return json({ enabled: true, indexReady: true, indexedChunks: 74, lastIndexedAt: '2026-10-09T19:27:00', knowledgeSources: ['help-content', 'yaml'], llmProvider: 'mistral', embeddingProvider: 'mistral', embeddingDimensions: 1024 });
      }
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'admin@example.com', mustChangePassword: false, role: 'SUPER_ADMIN' } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <ApiUsagePage /> : null;
}

function AssetsMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: answer the page's calls with sample data, restore fetch on leave.
    const prevFetch = window.fetch;
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const mk = (id: number, assetTag: string, categoryName: string, brand: string | null, model: string | null, status: string, condition: string, custodian: string | null) => ({
      id, assetTag, categoryId: 1, categoryName, brand, model, serialNumber: null, purchaseDate: null, purchaseCost: null, warrantyExpiry: null,
      condition, status, locationId: null, locationName: null, currentCustodianName: custodian, currentCustodianUserId: custodian ? `u${id}` : null, createdAt: '', updatedAt: '',
    });
    const inventory = [
      mk(1, 'LPT-4567', 'Monitor', null, null, 'AVAILABLE', 'GOOD', null),
      mk(2, 'BADGE-001', 'Access Card', 'Badge', null, 'RETIRED', 'GOOD', null),
      mk(3, 'MBP16-001', 'Laptop', 'Apple', 'MacBook Pro 16', 'RETIRED', 'DAMAGED', null),
      mk(4, 'LT', 'Access Card', 'acess', 'XP125', 'ASSIGNED', 'GOOD', 'sai krishna challa'),
      mk(5, 'DU27-001', 'Monitor', 'Dell', 'UltraSharp 27', 'RETIRED', 'GOOD', null),
      mk(6, 'LT-24-500', 'Laptop', 'MSI', '1929000', 'ASSIGNED', 'FAIR', 'Nikitha Toom'),
      mk(7, 'hp-4543', 'Mobile Phone', null, null, 'AVAILABLE', 'GOOD', null),
    ];
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/api/assets')) return json(inventory);
      if (url.endsWith('/api/assets/tiles/hr')) return json({ totalAssigned: 8, available: 12, overdueReturns: 0 });
      if (url.endsWith('/api/assets/categories')) return json(['Laptop', 'Monitor', 'Mobile Phone', 'Access Card'].map((name, i) => ({ id: i + 1, name, active: true })));
      if (url.endsWith('/api/assets/requests/pending')) return json([]);
      if (url.includes('/api/expenses') || url.includes('/api/assets')) return json([]);
      return prevFetch(input, init);
    }) as typeof window.fetch;
    setReady(true);
    return () => { window.fetch = prevFetch; };
  }, []);
  return ready ? <HRView token="mock-token" hideTiles /> : null;
}

function OppsMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample data and an HR/Super Admin role; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const opp = (id: string, title: string, weeks: number, status: 'OPEN' | 'CLOSED', skills: string[], postedBy: string, interestCount: number, assignedEmployee: string | null) =>
      ({ id, title, durationWeeks: weeks, status, requiredSkills: skills, postedBy, createdAt: '', interestCount, interested: false, assignedEmployee });
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/api/skill-taxonomy')) return json([{ id: 's1', skill: 'Java', category: 'Backend' }, { id: 's2', skill: 'React', category: 'Frontend' }, { id: 's3', skill: 'SQL', category: 'Data' }, { id: 's4', skill: 'Python', category: 'Backend' }]);
      if (url.endsWith('/api/opportunities')) {
        return json([
          opp('o1', 'Frontend developer for dashboard revamp', 6, 'OPEN', ['React', 'SQL'], 'Narendra Reddy', 3, null),
          opp('o2', 'QA: Java developer for report automation', 4, 'CLOSED', ['Java', 'SQL'], 'Narendra Reddy', 1, 'Chandrasekhar G'),
          opp('o3', 'Backend help for payroll (QA sample)', 4, 'CLOSED', ['Java', 'SQL'], 'Suresh Kumar Hanumalla', 1, 'Suresh Kumar Hanumalla'),
        ]);
      }
      if (url.endsWith('/api/projects')) return json([{ id: 'p1', name: 'Payroll Automation', active: true }, { id: 'p2', name: 'Customer Portal Revamp', active: true }]);
      if (url.includes('/api/opportunities/') && url.endsWith('/interests')) return json([{ employeeId: 'e1', employeeName: 'Chandrasekhar G', expressedAt: '' }, { employeeId: 'e2', employeeName: 'Nikitha Toom', expressedAt: '' }]);
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'admin@example.com', mustChangePassword: false, role: 'SUPER_ADMIN' } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <BenchOpportunitiesPage /> : null;
}

function NotificationsMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample notifications and a Super Admin session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const hoursAgo = (h: number) => new Date(Date.now() - h * 3600_000).toISOString();
    const n = (id: number, type: string, title: string, message: string, h: number, read: boolean, linkPath: string | null, priority = 'MEDIUM') =>
      ({ id, type, title, message, linkPath, read, priority, createdAt: hoursAgo(h) });
    const list = [
      n(1, 'LEAVE_REQUEST_SUBMITTED', 'Leave request from Sri vidya', 'Sri vidya has requested 2 days of Casual Leave (12 Nov – 13 Nov). Please review and approve or reject.', 1, false, '/approvals', 'HIGH'),
      n(2, 'HELPDESK_TICKET_CREATED', 'New Help Desk ticket', 'Ticket HR-2026-000038 (Employee Documents) raised by Vyshnavi Guthala.', 5, false, '/help'),
      n(3, 'POLICY_PUBLISHED', 'New Policy: Test_PolicyVeeraa', 'Please review and acknowledge version 1.0.', 30, true, '/policies'),
      n(4, 'HELPDESK_TICKET_RESOLVED', 'Your ticket was resolved', 'Ticket HR-2026-000036 is now RESOLVED', 200, true, '/help'),
      n(5, 'EXPENSE_FINAL_APPROVED', 'Expense claim approved', 'Your claim EXP-1042 for 2,400 has been approved and will be paid this cycle.', 220, true, null, 'LOW'),
    ];
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/api/notifications')) return json({ content: list, totalElements: 240, totalPages: 12, number: 0, size: 20 });
      if (url.endsWith('/api/notifications/unread-count')) return json({ count: 2 });
      if (url.includes('/api/notifications/')) return json({});
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'admin@example.com', mustChangePassword: false, role: 'SUPER_ADMIN' } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <NotificationsPage /> : null;
}

function ProfileMock() {
  const [ready, setReady] = useState(false);
  const role = (new URLSearchParams(window.location.search).get('role') ?? 'super').toLowerCase();
  useEffect(() => {
    // Mock only: a sample employee profile and session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const isEmployee = role === 'employee';
    const profile = {
      userId: 'u-me', email: 'naveen.kumar@nforceone.com', fullName: 'Naveen Kumar R', role: isEmployee ? 'EMPLOYEE' : 'SUPER_ADMIN', photoDataUrl: null, coverDataUrl: null,
      phone: '9876543210', dateOfBirth: '1990-05-14', gender: 'Male', personalEmail: null, address: '12, MG Road, Bangalore', emergencyContactName: 'Asha R', emergencyContactPhone: '9812345678',
      workMode: 'HYBRID', employeeCode: 'NF-20261001', departmentName: 'Executive', designationName: 'Chief Executive', locationName: 'Bangalore', employmentType: 'FULL_TIME', joiningDate: '2021-04-01',
      managerName: 'Suresh Kumar Hanumalla', managerEmail: null, active: true, hasEmployeeRecord: true,
      firstName: 'Naveen', middleName: 'Kumar', lastName: 'R', preferredName: null, bio: null, maritalStatus: 'Married', emergencyContactRelationship: 'Spouse', permanentAddress: null,
      passportNumber: 'P*****45', passportExpiry: '2031-08-02', bankAccountNumber: '•••• •••• 5591', bankName: 'HDFC Bank', bankIfsc: 'HDFC0001234', nationalId: '•••• •••• 4821',
      jobCode: 'EX-01', probationEndDate: '2021-10-01', confirmationDate: '2021-10-01', businessUnitName: 'NForce One', shiftName: 'Morning shift', weeklyOffPolicyName: 'Sat-Sun', attendancePenalizationPolicyName: 'Standard', attendanceStatus: 'OUT',
    };
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if ((init?.method ?? 'GET') === 'GET') {
        if (url.endsWith('/api/profile')) return json(profile);
        if (url.endsWith('/api/profile/education')) return json([{ id: 'e1', institutionName: 'IIT Madras', degree: 'B.Tech', fieldOfStudy: 'Computer Science', startDate: '2008-08-01', endDate: '2012-05-30', createdAt: '', updatedAt: '' }]);
        if (url.endsWith('/api/profile/timeline')) return json([
          { type: 'JOINED_COMPANY', date: '2021-04-01', timestamp: '2021-04-01T09:00:00', description: 'Joined NForce One as Chief Executive' },
          { type: 'DESIGNATION_CHANGED', date: '2023-01-01', timestamp: '2023-01-01T09:00:00', description: 'Designation changed to Chief Executive' },
        ]);
        if (url.includes('/api/hierarchy/context/') || url.includes('/context/')) return json({ manager: null, peers: [], reports: [] });
        if (url.endsWith('/api/employees/my-reports')) return json({ directReportCount: 2, directReports: [
          { userId: 'r1', employeeCode: 'NF-20261012', fullName: 'Sri vidya', designationName: 'Product Designer', departmentName: 'Design', active: true },
          { userId: 'r2', employeeCode: 'NF-20261015', fullName: 'Praveen Gurram', designationName: 'Engineering Manager', departmentName: 'Engineering', active: true },
        ], teamJoiners: [] });
        if (url.endsWith('/api/attendance/config')) return json({ weeklyOffDays: ['SATURDAY', 'SUNDAY'] });
        if (url.endsWith('/my-location')) return json([]);
        if (url.includes('/api/assets/my') || url.includes('/api/asset')) return json([]);
        if (url.includes('/api/expenses')) return json([]);
      }
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: profile.email, mustChangePassword: false, role: profile.role as never } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, [role]);
  return ready ? <ProfilePage /> : null;
}

function ChangePasswordMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: a sample signed-in user (add &forced=1 for the first-login version); restored on leave.
    const prevAuth = useAuthStore.getState();
    const forced = new URLSearchParams(window.location.search).get('forced') === '1';
    useAuthStore.setState({ token: 'mock-token', user: { email: 'superadmin@nforceone.com', mustChangePassword: forced, role: 'SUPER_ADMIN' } });
    setReady(true);
    return () => { useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user }); };
  }, []);
  return ready ? <ChangePasswordPage /> : null;
}

function OnboardingMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample onboarding data held in memory, and an HR Admin session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const day = (n: number) => { const d = new Date(); d.setDate(d.getDate() + n); return d.toISOString().slice(0, 10); };
    const people = [
      ['Aanuj Reddy K', 'NF-20261033', 'Engineering', 'Junior Sales Engineer', -58], ['Abbas DarubaiGari', 'NF-20261028', 'Engineering', null, -66],
      ['Anil Ramtenki', 'NF-20261034', 'Engineering', 'Software Engineer', -58], ['Gbpubl Qviuldgb', 'NF-20261054', 'Engineering', 'Software Engineer III', -57],
      ['Sanjukta Rao', 'NF-20261040', 'Finance', 'Accounts Executive', -40], ['Harvey Specter', 'NF-20261012', 'Operations & Sales', 'Sales Lead', -30],
    ] as [string, string, string, string | null, number][];
    let pending = people.map(([n, c, d, g, j], i) => ({ userId: 'p' + i, employeeCode: c, fullName: n, email: n.split(' ')[0].toLowerCase() + '@nforceone.com', role: 'EMPLOYEE', departmentName: d, designationName: g, joiningDate: day(j), active: true }));
    const mkItems = (done: number) => {
      const it = (key: string, label: string, cat: string, auto: boolean, dueIn: number | null, n: number) => ({
        id: key, itemKey: key, label, category: cat, auto, source: auto ? (key === 'DOCS' ? 'Documents' : 'Assets') : null,
        dueDate: dueIn === null ? null : day(dueIn), done: n < done, doneAt: null, doneByName: n < done ? (auto ? 'System' : 'Priya HR') : null, meta: null,
      });
      return {
        preBoarding: [it('OFFER', 'Offer letter signed', 'PRE_BOARDING', false, -5, 0), it('BGV', 'Background verification started', 'PRE_BOARDING', false, -2, 1), it('WELCOME', 'Welcome email sent', 'PRE_BOARDING', false, 1, 2)],
        documentsItem: it('DOCS', 'All required documents verified', 'DOCUMENTS', true, 3, 3),
        setup: [it('EMAIL', 'Company email created', 'SETUP', false, 2, 4), it('LAPTOP_ASSIGNED', 'Laptop assigned', 'SETUP', true, 4, 5), it('ACCESS_CARD_ASSIGNED', 'Access card assigned', 'SETUP', true, 5, 6)],
      };
    };
    const flows: Record<string, { name: string; code: string; dept: string; des: string | null; join: number; done: number; active: boolean; archived: boolean }> = {
      c1: { name: 'Sri vidya', code: 'NF-20260901', dept: 'Human Resources', des: 'HR Executive', join: -20, done: 2, active: true, archived: false },
      c2: { name: 'Praveen Gurram', code: 'NF-20260877', dept: 'Engineering', des: 'Tech Lead', join: -14, done: 4, active: true, archived: false },
      c3: { name: 'Vyshnavi Guthala', code: 'NF-20260850', dept: 'Quality Engineering', des: 'QA Engineer', join: -9, done: 7, active: true, archived: false },
      c4: { name: 'Testo', code: 'NF-20260700', dept: 'Human Resources', des: 'HR Executive', join: -90, done: 7, active: true, archived: true },
      c5: { name: 'Ramesh A', code: 'NF-20260655', dept: 'Engineering', des: null, join: -75, done: 3, active: false, archived: false },
    };
    const summary = (id: string) => {
      const f = flows[id];
      return { checklistId: id, employeeUserId: 'e' + id, employeeName: f.name, employeeCode: f.code, departmentName: f.dept, designationName: f.des, joiningDate: day(f.join), active: f.active, archived: f.archived,
        status: f.archived ? 'COMPLETE' : f.done < 3 ? 'OVERDUE' : f.done < 6 ? 'ATTENTION' : 'ON_TRACK', totalItems: 7, doneItems: f.done, nextDueLabel: f.archived ? null : 'Welcome email sent', nextDueDate: f.archived ? null : day(1),
        completedDate: f.archived ? day(-12) : null, durationDays: f.archived ? 48 : null };
    };
    const detail = (id: string) => {
      const f = flows[id]; const it = mkItems(f.done);
      return { checklistId: id, employeeUserId: 'e' + id, employeeName: f.name, employeeCode: f.code, departmentName: f.dept, designationName: f.des, locationName: 'Bangalore', managerName: 'Harvey Specter',
        joiningDate: day(f.join), active: f.active, archived: f.archived, status: f.archived ? 'COMPLETE' : f.done < 3 ? 'OVERDUE' : 'ON_TRACK', completedAt: f.archived ? day(-12) : null, readyToComplete: !f.archived && f.done >= 7,
        totalItems: 7, doneItems: f.done, ...it,
        documentsBreakdown: [{ documentTypeName: 'Aadhaar Card', status: 'VERIFIED' }, { documentTypeName: 'PAN Card', status: 'PENDING_VERIFICATION' }, { documentTypeName: 'Degree Certificate', status: 'REJECTED' }, { documentTypeName: 'Experience Letter', status: 'MISSING' }],
        timeline: [{ at: day(f.join), text: 'Onboarding started', meta: 'Priya HR' }, { at: day(f.join + 2), text: 'Offer letter signed', meta: 'Priya HR' }, { at: day(f.join + 4), text: 'Laptop assigned', meta: 'Synced from Assets' }] };
    };
    const page = (rows: unknown[]) => ({ content: rows, totalElements: rows.length, totalPages: 1 });
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      const path = url.split('?')[0];
      const q = new URL(url, window.location.origin).searchParams;
      const method = init?.method ?? 'GET';
      const search = (q.get('search') ?? '').toLowerCase();
      if (path === '/api/onboarding/stats') return json({ pendingCount: pending.length, startedCount: 3, completedCount: 1, overdueCount: 2, deactivatedCount: 1, completedThisMonthCount: 1, avgCompletionDays: 48 });
      if (path === '/api/onboarding/eligible-employees') return json(page(pending.filter(p => !search || p.fullName.toLowerCase().includes(search) || p.employeeCode.toLowerCase().includes(search))));
      if (path === '/api/onboarding' && method === 'GET') {
        const st = q.get('status');
        const ids = Object.keys(flows).filter(id => st === 'COMPLETED' ? flows[id].archived : st === 'DEACTIVATED' ? !flows[id].active : flows[id].active && !flows[id].archived);
        return json(page(ids.map(summary).filter(r => !search || r.employeeName.toLowerCase().includes(search))));
      }
      if (path === '/api/onboarding' && method === 'POST') {
        const body = JSON.parse(String(init?.body ?? '{}'));
        const p = pending.find(x => x.userId === body.employeeUserId);
        const id = 'n' + Date.now();
        if (p) { flows[id] = { name: p.fullName, code: p.employeeCode, dept: p.departmentName ?? '', des: p.designationName, join: -3, done: 0, active: true, archived: false }; pending = pending.filter(x => x !== p); }
        return json(detail(id));
      }
      const mDetail = path.match(/^\/api\/onboarding\/([^/]+)$/);
      if (mDetail && method === 'GET') return json(detail(mDetail[1]));
      const mToggle = path.match(/^\/api\/onboarding\/([^/]+)\/items\/([^/]+)$/);
      if (mToggle && method === 'PATCH') { const f = flows[mToggle[1]]; f.done = Math.min(7, f.done + 1); return json(detail(mToggle[1])); }
      const mDone = path.match(/^\/api\/onboarding\/([^/]+)\/complete$/);
      if (mDone) { flows[mDone[1]].archived = true; return json(detail(mDone[1])); }
      if (/\/employee\/[^/]+$/.test(path) && path.includes('documents')) {
        return json([
          { id: 'd1', employeeUserId: 'x', employeeName: 'x', documentTypeId: 1, documentTypeName: 'Aadhaar Card', fileName: 'aadhaar.pdf', fileUrl: '', status: 'VERIFIED', uploadedAt: day(-10) },
          { id: 'd2', employeeUserId: 'x', employeeName: 'x', documentTypeId: 2, documentTypeName: 'PAN Card', fileName: 'pan.png', fileUrl: '', status: 'PENDING_VERIFICATION', uploadedAt: day(-8) },
          { id: 'd3', employeeUserId: 'x', employeeName: 'x', documentTypeId: 3, documentTypeName: 'Degree Certificate', fileName: 'degree.pdf', fileUrl: '', status: 'REJECTED', rejectionReason: 'Image is blurry, please re-upload', uploadedAt: day(-6) },
        ]);
      }
      if (path.endsWith('/api/assets/categories')) return json([{ id: 1, name: 'Laptop' }, { id: 2, name: 'Access Card' }]);
      if (path.includes('/available-by-category/')) return json([{ id: 11, assetTag: 'LAP-0042', brand: 'Dell', model: 'Latitude 5440', condition: 'GOOD' }, { id: 12, assetTag: 'LAP-0047', brand: 'Apple', model: 'MacBook Air', condition: 'NEW' }]);
      if (/\/api\/assets\/\d+\/assign$/.test(path)) return json({});
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'hr@example.com', mustChangePassword: false, role: 'HR_ADMIN' } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <OnboardingPage /> : null;
}

function TeamPopupsMock() {
  const [open, setOpen] = useState<string | null>(null);
  const close = () => setOpen(null);
  const dr = { userId: 'u1', fullName: 'Srinivas Kongara', designationName: 'QA Engineer I', employeeCode: 'NF-20261036' };
  const row = {
    dr, status: 'NOT_IN_YET', record: null,
    requests: [
      { id: '1', requestType: 'LEAVE', leaveTypeName: 'Casual Leave', leaveStartDate: '2026-11-10', leaveEndDate: '2026-11-12' },
      { id: '2', requestType: 'REGULARIZATION', attendanceDate: '2026-11-03', requestedCheckIn: '09:30', requestedCheckOut: null, regularizationReason: 'Forgot to punch' },
      { id: '3', requestType: 'EXPENSE', expenseCategoryName: 'Travel', expenseAmount: 2400 },
    ],
  } as unknown as Parameters<typeof EmployeeDetailModal>[0]['row'];
  const entry = { userId: 'u1', fullName: 'Srinivas Kongara', designationName: 'QA Engineer I', employeeCode: 'NF-20261036', email: 'srinivas@nforceone.com', departmentName: 'Quality Engineering', locationName: 'Bangalore', workMode: 'HYBRID', employmentType: 'FULL_TIME', managerName: 'Harvey Specter' } as unknown as Parameters<typeof EmployeeDetailsModal>[0]['entry'];
  const people = ['Sanjukta', 'Abuzar Siddiqui', 'Harvey Specter', 'Hr Admin', 'Hr', 'Testo'].map((n, i) => ({ userId: 'u' + i, fullName: n }));
  const btn: React.CSSProperties = { padding: '10px 16px', borderRadius: 10, border: '1px solid var(--line2)', background: 'var(--raised)', color: 'var(--txt)', fontWeight: 600, cursor: 'pointer' };
  return (
    <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
      <button style={btn} onClick={() => setOpen('kudos')}>Appreciate (kudos)</button>
      <button style={btn} onClick={() => setOpen('detail')}>View Profile (attendance and requests)</button>
      <button style={btn} onClick={() => setOpen('details')}>Employment details</button>
      <button style={btn} onClick={() => setOpen('list')}>Not in yet list</button>
      {open === 'kudos' && <KudosModal target={{ userId: 'u1', name: 'Srinivas Kongara' }} token="mock-token" onClose={close} />}
      {open === 'detail' && <EmployeeDetailModal row={row} onClose={close} />}
      {open === 'details' && <EmployeeDetailsModal entry={entry} onClose={close} />}
      {open === 'list' && <NotInYetListModal people={people} onSelect={close} onClose={close} />}
    </div>
  );
}

function TeamTodayMock() {
  const names = ['Sanjukta', 'Abuzar Siddiqui', 'Harvey Specter', 'Hr Admin', 'Hr', 'Testo', 'Ramesh A', 'Lalith Kumar Ratnala'];
  const people = names.map((n, i) => ({ userId: 'u' + i, name: n }));
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', gap: 14 }}>
        <TeamKpiCard icon={<KIcOk size={14} />} iconColor="var(--ok)" label="Employees on time" value={5} note="arrived on schedule" onClick={() => {}} />
        <TeamKpiCard icon={<KIcLate size={14} />} iconColor="var(--warn)" label="Late arrivals" value={2} note="arrived late today" onClick={() => {}} />
        <TeamKpiCard icon={<KIcHome size={14} />} iconColor="var(--info)" label="WFH / On duty" value={0} note="remote or hybrid today" />
        <TeamKpiCard icon={<KIcPin size={14} />} iconColor="var(--txt-mut)" label="Remote clock-ins" value={0} note="via Web Clock-In today" />
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: 14 }}>
        <LeaveTodayPanel emptyText="No one on your team is on leave today." rows={[
          { key: '1', userId: 'u1', name: 'Abuzar Siddiqui', leaveType: 'Casual Leave', active: true },
          { key: '2', userId: 'u3', name: 'Hr Admin', leaveType: 'Annual Leave', active: true },
        ]} />
        <NotInYetPanel variant="list" loading={false} emptyText="Everyone has checked in." people={people} onPick={() => {}} onViewAll={() => {}} />
        <LeaveTodayPanel emptyText="No one on your project team is on leave today." rows={[]} />
        <NotInYetPanel variant="chips" limit={4} loading={false} emptyText="Everyone's checked in." people={people} onPick={() => {}} onViewAll={() => {}} />
      </div>
    </div>
  );
}

function TeamReportsMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample report data so the report dialogs open, list rows and export without a backend.
    const prevFetch = window.fetch;
    const people = [['Ramesh A', 'Software Engineer'], ['Lalith Kumar Ratnala', 'Senior Engineer'], ['Abuzar Siddiqui', 'QA Engineer'], ['Sri vidya', 'Business Analyst'], ['Praveen Gurram', 'Tech Lead'], ['Sanjukta', 'Designer']];
    const day = (n: number) => { const d = new Date(); d.setDate(d.getDate() - n); return d.toISOString().slice(0, 10); };
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      if (url.includes('/reports/attendance-requests?')) {
        const type = new URL(url, window.location.origin).searchParams.get('type');
        return json(people.map(([n], i) => ({
          employeeUserId: 'u' + i, employeeCode: 'NF' + (100 + i), fullName: n, date: day(i + 1),
          checkIn: type === 'REGULARIZATION' || type === 'WEB_CLOCK_IN' ? day(i + 1) + 'T09:' + (10 + i * 4) + ':00' : null,
          checkOut: type === 'REGULARIZATION' || type === 'WEB_CLOCK_IN' ? day(i + 1) + 'T18:' + (10 + i * 3) + ':00' : null,
          reason: ['Client visit', 'Forgot to punch', 'Network issue', 'Doctor appointment', 'Release support', 'Family function'][i],
          status: ['APPROVED', 'PENDING', 'APPROVED', 'REJECTED', 'APPROVED', 'PENDING'][i],
          requestMode: type === 'PARTIAL_DAY' ? ['LATE_ARRIVE', 'LEAVING_EARLY', 'INTERVENING_TIMEOFF'][i % 3] : type === 'WFH_OD' ? ['FULL_DAY', 'FIRST_HALF', 'SECOND_HALF'][i % 3] : null,
          hours: type === 'OVERTIME' ? 1 + (i % 3) * 0.5 : type === 'PARTIAL_DAY' ? 2 : type === 'WFH_OD' ? [1, 0.5, 0.5][i % 3] : null,
        })));
      }
      if (url.includes('/attendance/team-month')) {
        return json(people.map(([n], i) => ({ id: 'a' + i, employeeUserId: 'u' + i, employeeCode: 'NF' + (100 + i), fullName: n, workDate: day(i + 1), checkInAt: day(i + 1) + 'T09:' + (10 + i * 4) + ':00', checkOutAt: day(i + 1) + 'T18:20:00', workedMinutes: 480 + i * 7, status: ['PRESENT', 'LATE', 'PRESENT', 'PRESENT', 'LATE', 'PRESENT'][i] })));
      }
      if (url.includes('/attendance/team-punctuality')) {
        return json({ leaderboard: people.map(([n, d], i) => ({ employeeUserId: 'u' + i, fullName: n, designationName: d, onTimeDays: 20 - i, expectedWorkingDays: 21, percentage: ((20 - i) / 21) * 100 })), daily: [], summary: {} });
      }
      if (url.includes('/attendance/team-negligence')) {
        return json({
          lateArrivals: people.slice(0, 4).map(([n, d], i) => ({ employeeUserId: 'u' + i, fullName: n, designationName: d, lateDays: 6 - i, activeDays: 21, latePct: ((6 - i) / 21) * 100 })),
          dailyLateCounts: [],
          leastHoursWorked: people.slice(0, 3).map(([n, d], i) => ({ employeeUserId: 'u' + i, fullName: n, designationName: d, avgHoursPerDay: 6.2 + i * 0.4, hoursWorked: 120 + i * 9 })),
          hoursHistogram: [],
          frequentBreaks: people.slice(0, 3).map(([n, d], i) => ({ employeeUserId: 'u' + i, fullName: n, designationName: d, totalBreakCount: 40 - i * 5, totalBreakHours: 14 - i, avgBreaksPerDay: 2.4 - i * 0.3 })),
          breaksTrend: [],
        });
      }
      return prevFetch(input, init);
    }) as typeof window.fetch;
    setReady(true);
    return () => { window.fetch = prevFetch; };
  }, []);
  return ready ? (
    <div>
      <div className="nf-page-hdr" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 18, flexWrap: 'wrap' }}>
        <PageTitleBlock icon={KIcTeam}>
          <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 22, fontWeight: 700, color: 'var(--txt)', margin: '0 0 4px' }}>My Team</h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', margin: 0, maxWidth: '62ch' }}>Attendance, leave, and open requests for your direct reports, in one place, so you don't have to check four separate pages to know how your team is doing today.</p>
        </PageTitleBlock>
      </div>
      <MyTeamReportsTab token="mock-token" />
    </div>
  ) : null;
}

function AttentionMock() {
  const [items, setItems] = useState<ApprovalItem[]>([]);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample pending requests; approving or rejecting just removes them from this preview.
    const prevFetch = window.fetch;
    const base = { employeeUserId: '', employeeName: '', requestType: 'LEAVE', id: '', leaveTypeName: null, leaveStartDate: null, leaveEndDate: null, leaveTotalDays: null, attendanceDate: null, requestedCheckIn: null, requestedCheckOut: null, expenseCategoryName: null, expenseAmount: null, approvalStage: null, requestedCategoryName: null, assetRequestReason: null };
    const mk = (i: number, name: string, extra: Record<string, unknown>) => ({ ...base, id: String(i), employeeUserId: 'u' + i, employeeName: name, ...extra }) as unknown as ApprovalItem;
    const names = ['Ramesh A', 'Lalith Kumar Ratnala', 'Abuzar Siddiqui', 'Sri vidya', 'Praveen Gurram', 'Sanjukta', 'Vyshnavi Guthala', 'chandu shyam'];
    const list: ApprovalItem[] = [];
    names.forEach((n, i) => list.push(mk(i + 1, n, { requestType: 'LEAVE', leaveTypeName: i % 2 ? 'Annual Leave' : 'Casual Leave', leaveStartDate: '2026-09-17', leaveEndDate: i % 3 === 0 ? '2026-09-17' : '2026-09-19', leaveTotalDays: i % 3 === 0 ? 1 : 3 })));
    list.push(mk(21, 'Sri vidya', { requestType: 'REGULARIZATION', attendanceDate: '2026-10-30', requestedCheckIn: '09:30', requestedCheckOut: null }));
    list.push(mk(22, 'Sanjukta', { requestType: 'REGULARIZATION', attendanceDate: '2026-10-29', requestedCheckIn: null, requestedCheckOut: '18:30' }));
    list.push(mk(31, 'Praveen Gurram', { requestType: 'EXPENSE', expenseCategoryName: 'Travel', expenseAmount: 2400, approvalStage: 'MANAGER' }));
    list.push(mk(41, 'Vyshnavi Guthala', { requestType: 'ASSET_REQUEST', requestedCategoryName: 'Laptop', assetRequestReason: 'Replacement for a faulty unit' }));
    setItems(list);
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      if ((init?.method ?? 'GET') !== 'GET') return Promise.resolve(new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }));
      return prevFetch(input, init);
    }) as typeof window.fetch;
    setReady(true);
    return () => { window.fetch = prevFetch; };
  }, []);
  return ready ? (
    <div style={{ maxWidth: 420 }}>
      <AttentionPanel items={items} token="mock-token" onDone={id => setItems(prev => prev.filter(i => i.id !== id))} />
    </div>
  ) : null;
}

function AllocationsMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample employees and projects held in memory, and an HR Admin session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const emp = (i: number, n: string, code: string, des: string | null, dept: string, role = 'EMPLOYEE') => ({ userId: 'u' + i, employeeCode: code, fullName: n, email: n.split(' ')[0].toLowerCase() + '@nforceone.com', role, departmentName: dept, designationName: des, joiningDate: '2026-01-10', active: true });
    const employees = [
      emp(1, 'TestUserone', 'NF-20261314', 'QA Engineer', 'Quality Engineering'), emp(2, 'Praveen Gurram', 'NF-20261023', 'Tech Lead', 'Engineering', 'MANAGER'),
      emp(3, 'Testo', 'NF-20261334', 'HR Executive', 'Human Resources'), emp(4, 'Narendra Reddy', 'NF-20261003', 'Software Engineer', 'Engineering'),
      emp(5, 'Suresh Kumar H', 'NF-20261002', null, 'Finance'), emp(6, 'praveen Gurram', 'NF-20261040', 'Designer', 'Design'),
      emp(7, 'Naveen Kumar R', 'NF-20261001', 'Software Engineer III', 'Engineering'), emp(8, 'Harvey Specter', 'NF-20261012', 'Sales Lead', 'Operations & Sales', 'HR_ADMIN'),
    ];
    let projects = [
      { id: 'p1', code: 'PRJ-001', name: 'Customer Portal Revamp', startDate: '2026-08-01', endDate: '2027-03-31', ownerEmployeeId: 'u2', ownerName: 'Praveen Gurram', departmentId: 'd1', departmentName: 'Engineering', clientId: 'c1', clientName: 'Acme Corp', status: 'ACTIVE' },
      { id: 'p2', code: 'PRJ-002', name: 'Payroll Migration', startDate: '2026-05-01', endDate: null, ownerEmployeeId: null, ownerName: null, departmentId: 'd2', departmentName: 'Finance', clientId: null, clientName: null, status: 'ON_HOLD' },
      { id: 'p3', code: 'PRJ-003', name: 'Legacy Reports', startDate: '2025-01-01', endDate: '2026-06-30', ownerEmployeeId: 'u8', ownerName: 'Harvey Specter', departmentId: null, departmentName: null, clientId: 'c1', clientName: 'Acme Corp', status: 'CLOSED' },
    ];
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      const path = url.split('?')[0];
      const method = init?.method ?? 'GET';
      if (path.endsWith('/api/employees') && method === 'GET') return json(employees);
      if (path.endsWith('/api/projects/clients')) return json([{ id: 'c1', name: 'Acme Corp' }, { id: 'c2', name: 'Globex' }]);
      if (path.endsWith('/api/org/departments')) return json([{ id: 'd1', name: 'Engineering', active: true, employeeCount: 3 }, { id: 'd2', name: 'Finance', active: true, employeeCount: 1 }]);
      if (path.endsWith('/api/projects') && method === 'GET') return json(projects);
      if (path.endsWith('/api/projects') && method === 'POST') {
        const b = JSON.parse(String(init?.body ?? '{}'));
        const created = { id: 'p' + Date.now(), ownerName: null, departmentName: null, clientName: null, ...b };
        projects = [...projects, created]; return json(created);
      }
      if (/\/api\/projects\/[^/]+$/.test(path) && method === 'PUT') {
        const b = JSON.parse(String(init?.body ?? '{}')); const id = path.split('/').pop();
        projects = projects.map(p => (p.id === id ? { ...p, ...b } : p)); return json(projects.find(p => p.id === id));
      }
      if (path.endsWith('/api/allocations/bulk')) {
        const b = JSON.parse(String(init?.body ?? '{}'));
        return json({ results: (b.employeeIds as string[]).map((id, i) => (i % 3 === 2 ? { employeeId: id, status: 'FAILED', reason: 'Total allocation would exceed 100% in this period' } : { employeeId: id, status: 'SUCCESS' })) });
      }
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'hr@example.com', mustChangePassword: false, role: 'HR_ADMIN' } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <ProjectAllocationsPage /> : null;
}

function BenchMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample bench data held in memory, and an HR Admin session with bench edit rights; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const day = (n: number) => { const d = new Date(); d.setDate(d.getDate() + n); return d.toISOString().slice(0, 10); };
    const names = ['Lalith Kumar Ratnala', 'Sanjukta Rao', 'Abuzar Siddiqui', 'Sri vidya', 'Praveen Gurram', 'Vyshnavi Guthala'];
    const emps = names.map((n, i) => ({ userId: 'u' + i, employeeCode: 'NF-2026' + (1000 + i), fullName: n, email: n.split(' ')[0].toLowerCase() + '@nforceone.com', role: 'EMPLOYEE', departmentName: 'Engineering', designationName: 'Engineer', joiningDate: '2026-01-10', active: true }));
    let benched = [
      { id: 'b1', employeeId: 'u0', employeeName: names[0], reasonCode: 'PROJECT_ENDED', startDate: day(-22), daysOnBench: 22, agingBucket: '16-30 days', matchScore: 2, skills: ['Java', 'Spring Boot'] },
      { id: 'b2', employeeId: 'u1', employeeName: names[1], reasonCode: 'NEW_JOINER_UNALLOCATED', startDate: day(-41), daysOnBench: 41, agingBucket: '31-60 days', matchScore: 1, skills: ['React'] },
      { id: 'b3', employeeId: 'u2', employeeName: names[2], reasonCode: 'ROLE_ENDED', startDate: day(-6), daysOnBench: 6, agingBucket: '0-15 days', matchScore: 0, skills: [] },
    ];
    const skills = [{ id: 's1', skill: 'Java', category: 'Engineering' }, { id: 's2', skill: 'Spring Boot', category: 'Engineering' }, { id: 's3', skill: 'React', category: 'Engineering' }, { id: 's4', skill: 'Figma', category: 'Design' }, { id: 's5', skill: 'Testing', category: 'QA' }];
    let bands = [{ id: 'k1', bandLabel: 'B1', avgCtc: 600000 }, { id: 'k2', bandLabel: 'B2', avgCtc: 950000 }];
    let aging = { buckets: [{ label: '0-15 days', minDays: 0, maxDays: 15 }, { label: '16-30 days', minDays: 16, maxDays: 30 }, { label: '31-60 days', minDays: 31, maxDays: 60 }, { label: '60+ days', minDays: 61, maxDays: null }], notifyThresholdDays: 30 };
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      const path = url.split('?')[0];
      const method = init?.method ?? 'GET';
      if (path.endsWith('/api/employees') && method === 'GET') return json(emps);
      if (path.endsWith('/api/bench-status/dashboard')) return json({ benchedCount: benched.length, totalHeadcount: 64, benchPercent: (benched.length / 64) * 100, buckets: [{ bucket: '0-15 days', count: 1 }, { bucket: '16-30 days', count: 1 }, { bucket: '31-60 days', count: 1 }, { bucket: '60+ days', count: 0 }], trend: Array.from({ length: 14 }, (_, i) => ({ date: day(i - 13), benchedCount: [0, 0, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, benched.length][i], totalHeadcount: 64 })) });
      if (path.endsWith('/api/bench-status/cost')) return json({ rows: [{ bandLabel: 'B1', benchedCount: 2, avgCtc: 600000, totalCost: 1200000 }, { bandLabel: 'B2', benchedCount: 1, avgCtc: 950000, totalCost: 950000 }], totalCost: 2150000, unbandedCount: 0 });
      if (path.endsWith('/api/bench-status/team')) return json(benched.slice(0, 2).map(b => ({ employeeId: b.employeeId, employeeName: b.employeeName, reasonCode: b.reasonCode, startDate: b.startDate, daysOnBench: b.daysOnBench, lastProject: b.id === 'b1' ? 'Customer Portal Revamp' : null })));
      if (path.endsWith('/api/bench-status/aging-config')) { if (method === 'PUT') aging = JSON.parse(String(init?.body ?? '{}')); return json(aging); }
      if (/\/api\/bench-status\/[^/]+\/allocations$/.test(path)) return json([{ id: 'a1', projectName: 'Customer Portal Revamp', capacityPercent: 100, startDate: day(1), endDate: day(120) }]);
      if (/\/api\/bench-status\/[^/]+\/end$/.test(path)) { const id = path.split('/').slice(-2)[0]; benched = benched.filter(b => b.id !== id); return json({}); }
      if (path.endsWith('/api/bench-status') && method === 'POST') {
        const b = JSON.parse(String(init?.body ?? '{}')); const e = emps.find(x => x.userId === b.employeeId);
        benched = [...benched, { id: 'b' + Date.now(), employeeId: b.employeeId, employeeName: e?.fullName ?? '', reasonCode: b.reasonCode, startDate: b.startDate, daysOnBench: 0, agingBucket: '0-15 days', matchScore: 0, skills: [] }];
        return json({ employeeName: e?.fullName ?? 'Employee' });
      }
      if (path.endsWith('/api/bench-status') && method === 'GET') return json(benched);
      if (path.endsWith('/api/skill-taxonomy') && method === 'GET') return json(skills);
      if (path.endsWith('/api/skill-taxonomy') && method === 'POST') { const b = JSON.parse(String(init?.body ?? '{}')); const c = { id: 's' + Date.now(), ...b }; skills.push(c); return json(c); }
      if (path.endsWith('/api/compensation-bands/assignments')) { if (method === 'PUT') return json(undefined); return json(emps.slice(0, 3).map((e, i) => ({ employeeId: e.userId, employeeName: e.fullName, bandId: bands[i % 2].id, bandLabel: bands[i % 2].bandLabel }))); }
      if (path.endsWith('/api/compensation-bands') && method === 'GET') return json(bands);
      if (path.endsWith('/api/compensation-bands') && method === 'POST') { const b = JSON.parse(String(init?.body ?? '{}')); const c = { id: 'k' + Date.now(), ...b }; bands = [...bands, c]; return json(c); }
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'hr@example.com', mustChangePassword: false, role: 'HR_ADMIN' }, permissions: { permissions: { bench: { EDIT: ['ALL'], VIEW: ['ALL'] } } } as never });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user, permissions: prevAuth.permissions });
    };
  }, []);
  return ready ? <BenchPage /> : null;
}

function DocsComplianceMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample documents held in memory, and an HR Admin session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const day = (n: number) => { const d = new Date(); d.setDate(d.getDate() + n); return d.toISOString().slice(0, 10); };
    const iso = (n: number) => { const d = new Date(); d.setDate(d.getDate() + n); return d.toISOString(); };
    const names = ['Nikhila Reddy Mandhadi', 'Anil Ramtenki', 'Sri vidya', 'Praveen Gurram', 'Vyshnavi Guthala', 'Abuzar Siddiqui'];
    const types = ['PAN Card', 'Identity Document', 'Employment Contract', 'Educational Certificate', 'Aadhar Card'];
    const mk = (i: number, status: string, expiryIn: number | null) => ({
      id: 'd' + i, employeeUserId: 'u' + (i % names.length), employeeName: names[i % names.length], documentTypeId: (i % types.length) + 1, documentTypeName: types[i % types.length],
      requiresVerification: true, requiresExpiryDate: expiryIn !== null, fileName: ['defect evidence.png', 'Defect evidence2.png', 'scan.pdf'][i % 3], fileUrl: '',
      issueDate: expiryIn !== null ? day(-300) : null, expiryDate: expiryIn !== null ? day(expiryIn) : null, status, verifiedBy: status === 'VERIFIED' ? 'HR' : null,
      verifiedAt: status === 'VERIFIED' ? iso(-i) : null, rejectionReason: null, uploadedAt: iso(-i - 3), updatedAt: iso(-i), versionNumber: 1, superseded: false, previousVersionId: null,
    });
    let pending = Array.from({ length: 6 }, (_, i) => mk(i, 'PENDING_VERIFICATION', [null, 700, null, 12, null, -5][i]));
    let verified = Array.from({ length: 4 }, (_, i) => mk(i + 10, 'VERIFIED', [null, 400, 20, null][i]));
    const missing = [
      { employeeUserId: 'u1', employeeName: names[1], documentTypeId: 2, documentTypeName: 'Identity Document' },
      { employeeUserId: 'u2', employeeName: names[2], documentTypeId: 5, documentTypeName: 'Aadhar Card' },
      { employeeUserId: 'u3', employeeName: names[3], documentTypeId: 1, documentTypeName: 'PAN Card' },
    ];
    // a tiny valid PNG so the preview frame shows an image
    const png = Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='), c => c.charCodeAt(0));
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      const path = url.split('?')[0];
      const method = init?.method ?? 'GET';
      if (path.endsWith('/api/documents/kpis')) return json({ pendingVerification: pending.length, employeesWithPending: 4, expiringWithin30Days: 2, totalDocuments: 48 });
      if (path.endsWith('/api/documents/pending')) return json(pending);
      if (path.endsWith('/api/documents/missing')) return json(missing);
      if (path.endsWith('/api/documents') && method === 'GET') return json([...pending, ...verified]);
      if (/\/api\/documents\/[^/]+\/history$/.test(path)) return json([{ ...pending[0], versionNumber: 2 }, { ...pending[0], id: 'old', versionNumber: 1, superseded: true, status: 'REJECTED', rejectionReason: 'Image is blurry' }]);
      if (/\/api\/documents\/[^/]+\/file$/.test(path)) return Promise.resolve(new Response(new Blob([png], { type: 'image/png' }), { status: 200 }));
      if (/\/api\/documents\/[^/]+\/verify$/.test(path)) {
        const id = path.split('/').slice(-2)[0]; const body = JSON.parse(String(init?.body ?? '{}'));
        const d = pending.find(x => x.id === id); pending = pending.filter(x => x.id !== id);
        const updated = { ...(d ?? mk(99, 'VERIFIED', null)), status: body.action === 'VERIFY' ? 'VERIFIED' : 'REJECTED', verifiedAt: iso(0), rejectionReason: body.rejectionReason ?? null };
        if (body.action === 'VERIFY') verified = [updated, ...verified];
        return json(updated);
      }
      if (/\/api\/documents\/remind\//.test(path)) return Promise.resolve(new Response(null, { status: 204 }));
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'hr@example.com', mustChangePassword: false, role: 'HR_ADMIN' } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <DocumentsCompliancePage /> : null;
}

function RolesMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample roles held in memory, and a Super Admin session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const catalogue = [
      { code: 'attendance', name: 'Attendance', configurable: true, actions: ['VIEW', 'CREATE', 'EDIT', 'APPROVE', 'EXPORT'] },
      { code: 'leave', name: 'Leave', configurable: true, actions: ['VIEW', 'CREATE', 'EDIT', 'APPROVE', 'EXPORT'] },
      { code: 'reports', name: 'Reports', configurable: true, actions: ['VIEW', 'EXPORT'] },
      { code: 'bench', name: 'Bench management', configurable: true, actions: ['VIEW', 'EDIT'] },
      { code: 'roles', name: 'Roles & permissions', configurable: true, actions: ['VIEW', 'CREATE', 'EDIT', 'DELETE', 'CONFIGURE'] },
      { code: 'payroll', name: 'Payroll', configurable: false, actions: ['VIEW'] },
    ];
    const mkRole = (id: number, code: string, name: string, description: string, system: boolean, userCount: number, permissionCount: number, active = true) => ({
      id, code, name, description, system, active, permissionsEditable: code !== 'SUPER_ADMIN', metadataEditable: !system, userCount, permissionCount,
      createdAt: '2026-10-01T10:00:00', updatedAt: '2026-10-22T10:00:00', version: 1,
    });
    let roles = [
      mkRole(1, 'EMPLOYEE', 'Employee', 'Base role held by every user: self-service features.', true, 65, 0),
      mkRole(2, 'MANAGER', 'Manager', 'Team-scoped approvals and visibility. Granted automatically when someone has direct reports.', true, 13, 29),
      mkRole(3, 'HR_ADMIN', 'HR Admin', 'Organization-wide HR administration.', true, 4, 86),
      mkRole(4, 'SUPER_ADMIN', 'Super Admin', 'Full system access, including user management and roles & permissions.', true, 2, 114),
      mkRole(5, 'PAYROLL_VIEWER', 'Payroll Viewer', 'Read-only access to payroll reports.', false, 1, 4),
    ];
    const members = [
      { userId: 'm1', fullName: 'Priya Nair', email: 'priya@nforceone.com', employeeCode: 'NF-20261001', active: true },
      { userId: 'm2', fullName: 'Rohit Sharma', email: 'rohit@nforceone.com', employeeCode: 'NF-20261002', active: true },
    ];
    const grantsFor = (id: number) => id === 5
      ? [{ module: 'reports', action: 'VIEW', scope: 'ALL' }, { module: 'reports', action: 'EXPORT', scope: 'ALL' }, { module: 'attendance', action: 'VIEW', scope: 'TEAM' }, { module: 'leave', action: 'VIEW', scope: 'TEAM' }]
      : id === 1 ? [] : [{ module: 'attendance', action: 'VIEW', scope: 'ALL' }, { module: 'leave', action: 'APPROVE', scope: 'TEAM' }, { module: 'reports', action: 'VIEW', scope: 'ALL' }];
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      const path = url.split('?')[0];
      const method = init?.method ?? 'GET';
      if (path.endsWith('/api/permissions/catalogue')) return json(catalogue);
      if (path.endsWith('/api/roles') && method === 'GET') return json(roles);
      if (path.endsWith('/api/roles') && method === 'POST') {
        const b = JSON.parse(String(init?.body ?? '{}'));
        const r = mkRole(Date.now() % 100000, 'CUSTOM_' + Date.now(), b.name, b.description ?? '', false, 0, (b.grants ?? []).length);
        roles = [...roles, r]; return json({ role: r, grants: b.grants ?? [], members: [] });
      }
      const mRole = path.match(/\/api\/roles\/(\d+)$/);
      if (mRole && method === 'GET') { const r = roles.find(x => x.id === Number(mRole[1]))!; return json({ role: r, grants: grantsFor(r.id), members: r.userCount > 0 ? members.slice(0, Math.min(2, r.userCount)) : [] }); }
      if (mRole && method === 'DELETE') { roles = roles.filter(x => x.id !== Number(mRole[1])); return json(null); }
      const mStatus = path.match(/\/api\/roles\/(\d+)\/status$/);
      if (mStatus) { const b = JSON.parse(String(init?.body ?? '{}')); roles = roles.map(x => (x.id === Number(mStatus[1]) ? { ...x, active: b.active } : x)); const r = roles.find(x => x.id === Number(mStatus[1]))!; return json({ role: r, grants: grantsFor(r.id), members: [] }); }
      if (/\/api\/roles\/\d+(\/permissions)?$/.test(path) && method === 'PUT') { const id = Number(path.split('/').slice(-1)[0] === 'permissions' ? path.split('/').slice(-2)[0] : path.split('/').pop()); const r = roles.find(x => x.id === id)!; return json({ role: r, grants: grantsFor(id), members: [] }); }
      if (path.endsWith('/users')) return json([]);
      return prevFetch(input, init);
    }) as typeof window.fetch;
    const all = ['VIEW', 'CREATE', 'EDIT', 'DELETE', 'APPROVE', 'EXPORT', 'CONFIGURE'];
    const perms: Record<string, Record<string, string[]>> = {};
    ['attendance', 'leave', 'reports', 'bench', 'roles', 'payroll'].forEach(m => { perms[m] = Object.fromEntries(all.map(a => [a, ['ALL']])); });
    perms.user_roles = { EDIT: ['ALL'] }; perms.user_management = { VIEW: ['ALL'] };
    useAuthStore.setState({ token: 'mock-token', user: { email: 'admin@example.com', mustChangePassword: false, role: 'SUPER_ADMIN' }, permissions: { primaryRole: 'SUPER_ADMIN', roles: ['SUPER_ADMIN'], permissions: perms } as never });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user, permissions: prevAuth.permissions });
    };
  }, []);
  return ready ? <RolesPermissionsPage /> : null;
}

function CreateEmployeeStepsPreview() {
  const [step, setStep] = useState(0);
  const [form, setForm] = useState<CreateUserPayload>({
    fullName: 'Jane Smith', firstName: 'Jane', lastName: 'Smith', email: 'jane.smith@nforceone.com', role: 'EMPLOYEE',
    dateOfBirth: '1994-06-12', gender: 'Female', maritalStatus: 'Single', phone: '9876543210', personalEmail: 'jane.personal@example.com',
    emergencyContactName: 'Robert Smith', emergencyContactPhone: '9812345678', emergencyContactRelationship: 'Parent',
    employeeCode: 'NF-20261305', joiningDate: new Date().toISOString().slice(0, 10), employmentType: 'FULL_TIME', workMode: 'HYBRID',
    businessUnitId: 'b1', departmentId: 'd1', designationId: 'g1', locationId: 'l1', managerId: 'm1',
    shiftId: 'sh1', effectiveFrom: new Date().toISOString().slice(0, 10), weeklyOffPolicyId: 'w1', penalisationPolicyId: 'p1',
  } as CreateUserPayload);
  const [nameTouched, setNameTouched] = useState(true);
  const [startOnboarding, setStartOnboarding] = useState(true);
  const [opts, setOpts] = useState<OrgOptions>({
    businessUnits: [{ id: 'b1', name: 'Technology', active: true }],
    departments: [{ id: 'd1', name: 'Engineering', active: true }, { id: 'd2', name: 'Finance', active: true }],
    designations: [{ id: 'g1', title: 'Software Engineer', departmentId: 'd1', active: true }, { id: 'g2', title: 'Analyst', departmentId: 'd2', active: true }],
    locations: [{ id: 'l1', name: 'Hyderabad', active: true }, { id: 'l2', name: 'Bangalore', active: true }],
    managers: [{ userId: 'm1', fullName: 'Priya Nair', email: 'priya@nforceone.com', role: 'MANAGER', active: true }, { userId: 'm2', fullName: 'Harvey Specter', email: 'harvey@nforceone.com', role: 'HR_ADMIN', active: true }] as never,
    shifts: [{ id: 'sh1', name: 'General', startTime: '09:00:00', endTime: '18:00:00', active: true }] as never,
    weeklyOffPolicies: [{ id: 'w1', name: 'Saturday and Sunday' }] as never,
    penalisationPolicies: [{ id: 'p1', name: 'Standard attendance policy' }] as never,
  });
  useEffect(() => {
    // Mock only: a small holiday calendar for the Work & Policy step.
    const prevFetch = window.fetch;
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      if (url.includes('/holidays') && url.includes('locationId=')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'h1', holidayName: 'Diwali', holidayDate: '2026-11-08', locationId: 'l1', locationName: 'Hyderabad', active: true },
          { id: 'h2', holidayName: 'Christmas', holidayDate: '2026-12-25', locationId: 'l1', locationName: 'Hyderabad', active: true },
          { id: 'h3', holidayName: 'Republic Day', holidayDate: '2027-01-26', locationId: 'l1', locationName: 'Hyderabad', active: true },
        ]), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      return prevFetch(input, init);
    }) as typeof window.fetch;
    return () => { window.fetch = prevFetch; };
  }, []);
  const labels = ['1. Basic Information', '2. Employment & Job Details', '3. Work & Policy', '4. Compensation', '5. Result'];
  const [pwdShown, setPwdShown] = useState(false);
  const [copied, setCopied] = useState(false);
  const [outcome, setOutcome] = useState<'started' | 'failed' | 'skipped' | null>('started');
  const sampleCreated = { userId: 'new1', employeeCode: 'NF-20261305', fullName: 'Jane Smith', email: 'jane.smith@nforceone.com', role: 'EMPLOYEE', departmentName: 'Engineering', designationName: 'Software Engineer', joiningDate: new Date().toISOString().slice(0, 10), tempPassword: 'Tmp#8f2Kq9xL', active: true } as never;
  return (
    <div>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 14 }}>
        {labels.map((l, i) => (
          <button key={l} type="button" onClick={() => setStep(i)} style={{ padding: '8px 16px', borderRadius: 999, cursor: 'pointer', fontWeight: 700, fontSize: 13, border: '1px solid var(--line2)', color: step === i ? '#fff' : 'var(--txt-mut)', background: step === i ? 'var(--brand)' : 'var(--raised)' }}>{l}</button>
        ))}
      </div>
      {step === 4 && (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12, flexWrap: 'wrap', fontSize: 12.5, color: 'var(--txt-mut)' }}>
          Onboarding result:
          {(['started', 'failed', 'skipped'] as const).map(o => (
            <button key={o} type="button" onClick={() => setOutcome(o)} style={{ padding: '5px 12px', borderRadius: 999, cursor: 'pointer', fontWeight: 700, fontSize: 12, border: '1px solid var(--line2)', color: outcome === o ? '#fff' : 'var(--txt-mut)', background: outcome === o ? 'var(--brand)' : 'var(--raised)' }}>{o}</button>
          ))}
        </div>
      )}
      {step === 4 ? (
        <div style={{ border: '1px solid var(--line)', borderRadius: 18, overflow: 'hidden', background: 'var(--panel)' }}>
          <CreateEmployeeResult created={sampleCreated} passwordVisible={pwdShown} onTogglePassword={() => setPwdShown(v => !v)} passwordCopied={copied} onCopyPassword={() => { setCopied(true); setTimeout(() => setCopied(false), 1500); }} onboardingOutcome={outcome} onboardingError="Network error" retryingOnboarding={false} onRetryOnboarding={() => setOutcome('started')} onDone={() => setStep(0)} />
        </div>
      ) : (
      <div className="nf-um-page" style={{ border: '1px solid var(--line)', borderRadius: 18, overflow: 'hidden', background: 'var(--panel)' }}>
        <div className="nf-ce-top" style={{ top: 0, position: 'static' }}>
          <div className="nf-ce-head">
            <span className="nf-ce-head-ico"><UserPlus size={20} /></span>
            <div className="nf-ce-head-txt">
              <div className="nf-ce-title">Create Employee</div>
              <div className="nf-ce-sub">Add a new employee in four steps.</div>
            </div>
            <button type="button" className="nf-ce-cancel">Cancel</button>
          </div>
          <WizardSteps current={step} />
        </div>
        <div className="nf-ce-body">
          {step === 0 && <BasicInfoStep form={form} setForm={setForm} nameTouched={nameTouched} setNameTouched={setNameTouched} />}
          {step === 1 && <EmploymentStep form={form} setForm={setForm} opts={opts} token="mock-token" employeeCodeConflict={false} fetchingNewId={false} onGetNewId={() => {}} previewLoading={false} />}
          {step === 2 && <WorkPolicyStep form={form} setForm={setForm} opts={opts} setOpts={setOpts} token="mock-token" startOnboarding={startOnboarding} setStartOnboarding={setStartOnboarding} />}
          {step === 3 && (
            <div className="nf-ce-sec" style={{ padding: 20 }}>
              <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--txt)', marginBottom: 12 }}>Jane Smith has been created successfully.</div>
              <CompensationFollowUp />
              <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 10 }}>
                Compensation is optional and can be configured later, it won't affect this employee's account, onboarding, or access.
              </div>
            </div>
          )}
        </div>
        <div className="nf-um-foot nf-ce-foot" style={{ position: 'static', justifyContent: step === 3 ? 'flex-end' : 'space-between' }}>
          {step !== 3 && (
            <button type="button" className="nf-ce-back" disabled={step === 0} onClick={() => setStep(s => Math.max(0, s - 1))}>Back</button>
          )}
          {step === 3 ? <button type="button" className="nf-ce-next" onClick={() => setStep(4)}>Skip for Now</button>
            : <button type="button" className="nf-ce-next" onClick={() => setStep(s => Math.min(3, s + 1))}>{step < 2 ? 'Continue' : 'Save & Continue'}</button>}
        </div>
      </div>
      )}
    </div>
  );
}

function UserEditMock() {
  const [open, setOpen] = useState<EmployeeRecord | null>(null);
  const [opts, setOpts] = useState<OrgOptions>({
    businessUnits: [{ id: 'b1', name: 'Technology', active: true }],
    departments: [{ id: 'd1', name: 'Finance', active: true }, { id: 'd2', name: 'Engineering', active: true }],
    designations: [{ id: 'g1', title: 'Backend Developer', departmentId: 'd1', active: true }, { id: 'g2', title: 'Analyst', departmentId: 'd1', active: true }, { id: 'g3', title: 'Software Engineer', departmentId: 'd2', active: true }],
    locations: [{ id: 'l1', name: 'Hyderabad', active: true }, { id: 'l2', name: 'Vishakhapatnam', active: true }],
    managers: [{ userId: 'm1', fullName: 'Priya Nair', email: 'priya@nforceone.com', role: 'MANAGER', active: true }, { userId: 'm2', fullName: 'Harvey Specter', email: 'harvey@nforceone.com', role: 'HR_ADMIN', active: true }] as never,
    shifts: [{ id: 'sh1', name: 'General', startTime: '09:00:00', endTime: '18:00:00', active: true }, { id: 'sh2', name: 'Night', startTime: '21:00:00', endTime: '06:00:00', active: true }] as never,
  });
  const base = { businessUnitId: 'b1', businessUnitName: 'Technology', departmentId: 'd1', departmentName: 'Finance', designationId: 'g1', designationName: 'Backend Developer', locationId: 'l2', locationName: 'Vishakhapatnam', shiftId: 'sh1', shiftName: 'General', employmentType: 'FULL_TIME', workMode: 'ONSITE', joiningDate: '2026-04-12', currentManager: { userId: 'm1', fullName: 'Priya Nair' } } as const;
  const people = [
    { ...base, userId: 'u1', employeeCode: 'NF-20261004', fullName: 'Abuzar Siddiqui', email: 'abuzar.siddiqui@nforceone.com', role: 'EMPLOYEE', active: true },
    { ...base, userId: 'u2', employeeCode: 'NF-20261018', fullName: 'Meera Iyer', email: 'meera.iyer@nforceone.com', role: 'MANAGER', active: false },
  ] as unknown as EmployeeRecord[];
  useEffect(() => {
    // Mock only: a Super Admin session and stubbed saves; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'PATCH' || init?.method === 'PUT') return Promise.resolve(new Response(JSON.stringify(people[0]), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'admin@example.com', fullName: 'Anil Kumar', mustChangePassword: false, role: 'SUPER_ADMIN' } });
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return (
    <div>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
        {people.map(pe => (
          <button key={pe.userId} type="button" onClick={() => setOpen(pe)} style={{ padding: '9px 16px', borderRadius: 999, cursor: 'pointer', fontWeight: 700, fontSize: 13, border: '1px solid var(--line2)', color: '#fff', background: 'var(--brand)' }}>
            {pe.active ? 'Edit an active user' : 'Edit an inactive user'}
          </button>
        ))}
      </div>
      {open && <UserEditModal user={open} token="mock-token" opts={opts} setOpts={setOpts} onClose={() => setOpen(null)} onUpdated={() => {}} />}
    </div>
  );
}

function EmployeeEditMock() {
  const [open, setOpen] = useState<EmployeeRecord | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const base = { businessUnitId: 'b1', businessUnitName: 'Technology', departmentId: 'd1', departmentName: 'Finance', designationId: 'g1', designationName: 'Backend Developer', locationId: 'l2', locationName: 'Vishakhapatnam', shiftId: null, shiftName: null, employmentType: 'FULL_TIME', workMode: 'ONSITE', joiningDate: '2026-04-12', currentManager: null } as const;
  const people: EmployeeRecord[] = [
    { ...base, userId: 'u1', employeeCode: 'NF-20261004', fullName: 'Abuzar Siddiqui', email: 'abuzar.siddiqui@nforceone.com', role: 'EMPLOYEE', active: true },
    { ...base, userId: 'u2', employeeCode: 'NF-20261018', fullName: 'Meera Iyer', email: 'meera.iyer@nforceone.com', role: 'EMPLOYEE', active: false, workMode: 'HYBRID', departmentId: 'd2', departmentName: 'Engineering' },
  ] as EmployeeRecord[];
  useEffect(() => {
    // Mock only: sample org lists and a Super Admin session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/business-units')) return json([{ id: 'b1', name: 'Technology', active: true }, { id: 'b2', name: 'Operations', active: true }]);
      if (url.endsWith('/departments')) return json([{ id: 'd1', name: 'Finance', active: true }, { id: 'd2', name: 'Engineering', active: true }, { id: 'd3', name: 'Sales', active: true }]);
      if (url.endsWith('/designations')) return json([{ id: 'g1', title: 'Backend Developer', active: true }, { id: 'g2', title: 'Analyst', active: true }, { id: 'g3', title: 'Sales Executive', active: true }]);
      if (url.endsWith('/locations')) return json([{ id: 'l1', name: 'Hyderabad', active: true }, { id: 'l2', name: 'Vishakhapatnam', active: true }, { id: 'l3', name: 'Bangalore', active: true }]);
      if (url.includes('/employees/') && init?.method === 'PATCH') return json({ ...people[0], ...JSON.parse(String(init.body ?? '{}')) });
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'admin@example.com', fullName: 'Anil Kumar', mustChangePassword: false, role: 'SUPER_ADMIN' } });
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return (
    <div>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
        {people.map(pe => (
          <button key={pe.userId} type="button" onClick={() => { setSaved(null); setOpen(pe); }} style={{ padding: '9px 16px', borderRadius: 999, cursor: 'pointer', fontWeight: 700, fontSize: 13, border: '1px solid var(--line2)', color: '#fff', background: 'var(--brand)' }}>
            {pe.active ? 'Edit an active employee' : 'Edit an inactive employee'}
          </button>
        ))}
      </div>
      {saved && <div style={{ fontSize: 13, color: 'var(--ok)', fontWeight: 600 }}>Saved {saved} (preview only).</div>}
      {open && <EmployeeEditModal emp={open} token="mock-token" onClose={() => setOpen(null)} onUpdated={u => setSaved(u.fullName)} />}
    </div>
  );
}

function PoliciesMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample data and a Super Admin session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const pol = (id: number, title: string, version: string, required: boolean, active: boolean, publishedAt: string, hasAttachment = false) => ({
      id, title, version, description: '', audience: 'EMPLOYEE,MANAGER,HR_ADMIN,SUPER_ADMIN', required, publishedAt, publishedBy: 'Admin', active,
      acknowledged: null, acknowledgedAt: null, versionNumber: 1, previousVersionId: null, hasAttachment, attachmentFileName: hasAttachment ? 'policy.pdf' : null,
    });
    const ann = (id: number, title: string, publishedAt: string | null, active: boolean) => ({
      id, title, body: '', audience: 'EMPLOYEE,MANAGER,HR_ADMIN,SUPER_ADMIN', scheduledFor: null, publishedAt, createdBy: 'Admin', createdAt: '2026-09-20T10:00:00', published: !!publishedAt, active,
    });
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/api/policies/pending-ack-count')) return json(547);
      if (/\/api\/policies\/\d+\/versions$/.test(url)) return json([pol(1, 'Test_PolicyVeeraa', '1.0', true, true, '2026-10-22T10:00:00')]);
      if (/\/api\/policies\/\d+\/acknowledgments$/.test(url)) {
        const names = ['Vyshnavi Guthala', 'Sri vidya', 'Sanjukta', 'Praveen Gurram', 'chandu shyam', 'Nikhila Reddy Mandhadi', 'Anil Ramtenki'];
        return json(names.map((n, i) => ({ id: i + 1, policyId: 1, policyTitle: 'Test_PolicyVeeraa', policyVersion: '1.0', employeeUserId: 'u' + i, employeeName: n, acknowledgedAt: i % 3 === 0 ? '2026-10-23T10:00:00' : null, pending: i % 3 !== 0 })));
      }
      if (url.endsWith('/api/policies')) {
        return json([
          pol(1, 'Test_PolicyVeeraa', '1.0', true, true, '2026-10-22T10:00:00'),
          pol(2, 'testetest-test', '1.0', true, true, '2026-09-29T10:00:00'),
          pol(3, 'Test_PolicyVeera', '1.0', false, true, '2026-09-28T10:00:00'),
          pol(4, 'Veera_NewPolicy for Test', '2.0', true, true, '2026-09-28T10:00:00', true),
          pol(5, 'Veera_NewPolicy for Test2', '1.0', true, false, '2026-09-28T10:00:00', true),
        ]);
      }
      if (url.endsWith('/api/announcements')) {
        return json([ann(1, 'Office closed for Diwali', '2026-10-18T09:00:00', true), ann(2, 'Q4 town hall', null, true), ann(3, 'Holiday calendar update', '2026-09-10T09:00:00', false)]);
      }
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'admin@example.com', mustChangePassword: false, role: 'SUPER_ADMIN' } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <PoliciesPage /> : null;
}

function DocumentsMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample data and an Employee session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const req = (documentTypeId: number, documentTypeName: string, status: string | null, requiresVerification: boolean, expiringSoon = false) =>
      ({ documentTypeId, documentTypeName, requiresVerification, requiresExpiryDate: false, uploaded: status != null, status, expiringSoon });
    const doc = (id: string, documentTypeId: number, documentTypeName: string, status: string, expiryDate: string | null, rejectionReason: string | null) => ({
      id, employeeUserId: 'me', employeeName: 'Me', documentTypeId, documentTypeName, requiresVerification: true, requiresExpiryDate: false, fileName: 'file.pdf', fileUrl: '',
      issueDate: null, expiryDate, status, verifiedBy: null, verifiedAt: null, rejectionReason, uploadedAt: '2026-10-01T10:00:00', updatedAt: '2026-10-01T10:00:00', versionNumber: 1,
    });
    const pol = (id: number, title: string, version: string, required: boolean, acknowledged: boolean | null, description: string) => ({
      id, title, version, description, audience: 'All', required, publishedAt: '2026-09-28T10:00:00', publishedBy: 'Admin', active: true,
      acknowledged, acknowledgedAt: acknowledged ? '2026-10-02T10:00:00' : null, versionNumber: 1, previousVersionId: null, hasAttachment: false, attachmentFileName: null,
    });
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/my/required')) {
        return json([req(1, 'Employment Contract', 'PENDING_VERIFICATION', true), req(2, 'Identity Document', 'PENDING_VERIFICATION', true, true), req(3, 'Address Proof', 'VERIFIED', true), req(4, 'Bank Details', null, false), req(5, 'Degree Certificate', 'REJECTED', true)]);
      }
      if (url.endsWith('/api/documents/my')) {
        return json([doc('d1', 1, 'Employment Contract', 'PENDING_VERIFICATION', null, null), doc('d2', 2, 'Identity Document', 'PENDING_VERIFICATION', '2028-05-31', null), doc('d3', 3, 'Address Proof', 'VERIFIED', null, null), doc('d5', 5, 'Degree Certificate', 'REJECTED', null, 'Image is blurry, please re-upload')]);
      }
      // Mock only: "View" opens a small sample PDF instead of calling a backend that isn't there.
      if (/\/api\/documents\/[^/]+\/file$/.test(url) || /\/api\/policies\/[^/]+\/attachment$/.test(url)) {
        const pdf = ['%PDF-1.1', '1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj', '2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj', '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 120]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj', '4 0 obj<</Length 58>>stream', 'BT /F1 16 Tf 20 60 Td (Sample document for preview) Tj ET', 'endstream', 'endobj', '5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj', 'trailer<</Root 1 0 R/Size 6>>', '%%EOF'].join(String.fromCharCode(10));
        return Promise.resolve(new Response(new Blob([pdf], { type: 'application/pdf' }), { status: 200, headers: { 'Content-Type': 'application/pdf' } }));
      }
      if (url.endsWith('/api/policies/my')) {
        return json([pol(1, 'Code of Conduct', '2.0', true, false, 'How we work together and what we expect of each other.'), pol(2, 'Leave Policy', '1.0', true, true, 'Entitlements, approvals and carry-forward rules.'), pol(3, 'Travel Guidelines', '1.0', false, null, 'Booking, limits and reimbursement.')]);
      }
      if (url.endsWith('/api/announcements/published')) {
        return json([{ id: 1, title: 'Office closed for Diwali', body: 'The office will be closed on 21 Oct for Diwali. Wishing everyone a safe and happy festival.', audience: 'All', scheduledFor: null, publishedAt: '2026-10-18T09:00:00', createdBy: 'HR', createdAt: '2026-10-17T09:00:00', published: true, active: true }]);
      }
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'employee@example.com', mustChangePassword: false, role: 'EMPLOYEE' } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <DocumentsPage /> : null;
}

function MyRequestsMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample requests for an Employee session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const base = { employeeUserId: 'me', employeeName: 'Me' };
    const items = [
      { ...base, id: 'r1', requestType: 'LEAVE', createdAt: '2026-10-12T09:00:00Z', status: 'APPROVED', decisionReason: 'Deducted automatically by attendance penalty', decidedByName: 'System', decidedAt: '2026-10-13T08:00:00Z', leaveTypeName: 'Annual Leave', leaveStartDate: '2026-10-12', leaveEndDate: '2026-10-12', leaveTotalDays: 1, leaveHalfDay: false, leaveReason: 'Attendance penalty: Work hours shortage' },
      { ...base, id: 'r2', requestType: 'LEAVE', createdAt: '2026-11-02T09:00:00Z', status: 'PENDING', leaveTypeName: 'Casual Leave', leaveStartDate: '2026-11-16', leaveEndDate: '2026-11-18', leaveTotalDays: 3, leaveHalfDay: false, leaveReason: 'Family function out of town.' },
      { ...base, id: 'r3', requestType: 'REGULARIZATION', createdAt: '2026-11-05T09:00:00Z', status: 'REJECTED', decisionReason: 'No supporting log found for that day.', decidedByName: 'Priya Nair', decidedAt: '2026-11-06T10:00:00Z', attendanceDate: '2026-11-04', requestedCheckIn: '2026-11-04T09:10:00Z', requestedCheckOut: '2026-11-04T18:05:00Z', regularizationReason: 'Forgot to check in, was in a client meeting.' },
      { ...base, id: 'r4', requestType: 'REGULARIZATION', createdAt: '2026-11-07T09:00:00Z', status: 'PARTIALLY_APPROVED', attendanceDate: '2026-11-06', requestedCheckIn: '2026-11-06T09:00:00Z', requestedCheckOut: '2026-11-06T18:00:00Z', regularizationReason: 'Biometric was down.' },
      { ...base, id: 'r5', requestType: 'WFH', createdAt: '2026-11-08T09:00:00Z', status: 'APPROVED', decidedByName: 'Priya Nair', decidedAt: '2026-11-08T12:00:00Z', attendanceDate: '2026-11-10', regularizationReason: 'Internet installation at home.' },
      { ...base, id: 'r6', requestType: 'PARTIAL_DAY', createdAt: '2026-11-09T09:00:00Z', status: 'PENDING', attendanceDate: '2026-11-12', partialDayHours: 3.5, regularizationReason: 'Doctor appointment.' },
      { ...base, id: 'r7', requestType: 'OVERTIME', createdAt: '2026-11-10T09:00:00Z', status: 'APPROVED', decidedByName: 'Priya Nair', decidedAt: '2026-11-11T09:00:00Z', attendanceDate: '2026-11-09', requestedCheckIn: '2026-11-09T18:00:00Z', requestedCheckOut: '2026-11-09T20:30:00Z', regularizationReason: 'Release deployment support.' },
    ];
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/api/my-requests')) return json(items);
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'employee@example.com', mustChangePassword: false, role: 'EMPLOYEE' } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <MyRequestsPage /> : null;
}

function HelpMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample data and an HR/Super Admin session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const item = (id: string, type: string, title: string, description: string, status: string, attachmentCount = 0) => ({
      id, type, title, description, category: null, status, featured: false, displayOrder: 0, viewCount: 0, attachmentCount, audience: [], rejectionReason: null, createdAt: '', updatedAt: '',
    });
    const all = [
      item('f1', 'FAQ', 'test faq', 'A short answer to a test question.', 'PENDING_APPROVAL'),
      item('f2', 'FAQ', "How do I respond to an employee's HR Service Request ticket?", 'Open the ticket from HR Service Requests, read the thread and reply or change the status.', 'PUBLISHED'),
      item('f3', 'FAQ', "Who approves my Help & Guidance content before it's published?", 'Super Admins approve content that HR submits for approval.', 'PENDING_APPROVAL'),
      item('g1', 'GUIDE', 'This a new fact for Test', 'This a new fact for Test', 'PENDING_APPROVAL'),
      item('g2', 'GUIDE', 'Test Guide to testdefect', 'This is a test guide', 'PUBLISHED', 1),
      item('g3', 'QUICK_HELP', "Forgot to Check In or Check Out? Here's What to Do", "Missed a punch? Don't try to log a new entry — use Attendance regularization.", 'DRAFT'),
    ];
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/api/hr/help-content') || url.endsWith('/api/help-content')) {
        return json({ content: all, totalElements: all.length, totalPages: 1, number: 0, size: 100 });
      }
      const detailId = /\/api\/(?:hr\/)?help-content\/([^/]+)$/.exec(url)?.[1];
      if (detailId && all.some(a => a.id === detailId)) {
        const it = all.find(a => a.id === detailId)!;
        return json({ ...it, body: it.type === 'FAQ' ? 'Open the item from the list, read the details and follow the steps shown. If you are still stuck, contact HR Support.' : null, publishedAt: null, createdByName: 'Anil Kumar', updatedByName: null, attachments: it.attachmentCount ? [{ id: 'at1', fileName: 'guide.pdf', contentType: 'application/pdf', sizeBytes: 20480, displayOrder: 0 }] : [] });
      }
      if (url.endsWith('/api/helpdesk/my')) return json({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 });
      if (url.includes('/api/helpdesk')) return json([]);
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'admin@example.com', mustChangePassword: false, role: 'SUPER_ADMIN' }, permissions: { permissions: { hr_help_content: { VIEW: ['ALL'], CREATE: ['ALL'], EDIT: ['ALL'], DELETE: ['ALL'] } } } as never });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user, permissions: prevAuth.permissions });
    };
  }, []);
  return ready ? <HelpDeskPage /> : null;
}

function WorkflowMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample rules and a Super Admin session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const rule = (id: string, ruleName: string, conditionField: string, operator: string, conditionValue: string, approvalStages: string[], active: boolean, updatedByName: string) =>
      ({ id, ruleName, requestType: 'EXPENSE', conditionField, operator, conditionValue, approvalStages, active, createdByName: updatedByName, createdAt: '', updatedByName, updatedAt: '' });
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/preview')) return json({ conditionField: 'AMOUNT', operator: '>', conditionValue: '500', sampleValue: 800, conditionExpression: 'AMOUNT 800 > 500', conditionResult: true, currentRouting: ['Manager'], newRouting: ['Manager', 'HR ADMIN'] });
      if (url.endsWith('/metadata')) return json({ requestTypes: ['EXPENSE', 'LEAVE'], conditionFieldsByRequestType: { EXPENSE: ['AMOUNT'], LEAVE: ['DAYS'] }, operators: ['>', '>=', '<', '<=', '='], approverRoles: ['MANAGER', 'HR_ADMIN', 'SUPER_ADMIN'], approvalRoles: ['MANAGER', 'HR_ADMIN', 'SUPER_ADMIN'] });
      if (url.includes('approval-rules')) {
        return json([
          rule('1', 'Expense claims over 20k', 'AMOUNT', '>', '20000', ['MANAGER', 'HR_ADMIN', 'SUPER_ADMIN'], false, 'MOHAN K'),
          rule('2', 'expense above 1000', 'AMOUNT', '>=', '1000', ['MANAGER'], false, 'Naveen Kumar R'),
          rule('3', 'expense above 2000', 'AMOUNT', '>=', '2000', ['MANAGER', 'HR_ADMIN'], true, 'MOHAN K'),
          rule('4', 'expense claims over 10k', 'AMOUNT', '>=', '10000', ['MANAGER', 'HR_ADMIN', 'SUPER_ADMIN'], false, 'Naveen Kumar R'),
        ]);
      }
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({
      token: 'mock-token', user: { email: 'admin@example.com', mustChangePassword: false, role: 'SUPER_ADMIN' },
      permissions: { primaryRole: 'SUPER_ADMIN', roles: ['SUPER_ADMIN'], permissions: { approval_rules: { VIEW: ['ALL'], CREATE: ['ALL'], UPDATE: ['ALL'], DELETE: ['ALL'] } } } as never,
    });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user, permissions: prevAuth.permissions });
    };
  }, []);
  return ready ? <WorkflowStudioPage /> : null;
}

function TalentMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample data and a Super Admin session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const skillNames: Array<[string, string]> = [['Java', 'Backend'], ['React', 'Frontend'], ['SQL', 'Data'], ['Python', 'Backend'], ['AWS', 'Cloud']];
    const skillOptions = skillNames.map(([name, category], i) => ({ id: `s${i}`, name, category, aliases: i === 1 ? ['ReactJS'] : [] }));
    const levels = ['Beginner', 'Intermediate', 'Advanced', 'Expert'].map((name, i) => ({ id: `l${i}`, name, sortOrder: i + 1, description: null }));
    const inDays = (n: number) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);
    const expiries = [20, 200, -5, 75];
    const cert = (id: string, name: string, k = 0) => ({ id, name, categoryId: null, categoryName: 'Cloud', issuingOrganization: 'AWS', credentialId: null, credentialUrl: null, issueDate: null, expiryDate: inDays(expiries[k % expiries.length]) });
    const people: Array<[string, string, string, string, Array<[string, string]>, number]> = [
      ['u1', 'Abuzar Siddiqui', 'NF-20261004', 'Finance', [['Java', 'Expert'], ['SQL', 'Advanced'], ['AWS', 'Intermediate']], 2],
      ['u2', 'Vandana', 'NF-20261096', 'Engineering', [['React', 'Advanced'], ['Java', 'Intermediate']], 1],
      ['u3', 'Venkat Narsimha Reddy', 'NF-20261019', 'Sales', [['Python', 'Beginner']], 0],
      ['u4', 'Ramesh A', 'NF-20261009', 'Quality Engineering', [], 0],
    ];
    const summaries = people.map(([id, employeeName, employeeCode, departmentName, sk, certs]) => ({
      employeeUserId: id, employeeName, employeeCode, departmentName, locationName: 'Hyderabad', designationName: null, managerName: 'Narendra Reddy',
      skills: sk.map(([skillName, proficiencyLevel], i) => ({ id: `${id}-${i}`, skillId: null, skillName, proficiencyLevel })),
      certificates: Array.from({ length: certs }, (_, i) => cert(`${id}-c${i}`, i === 0 ? 'AWS Cloud Practitioner' : 'Azure Fundamentals', Number(id.slice(1)) + i)), learningEntries: id === 'u1' ? [{ id: 'le1', title: 'Advanced Kubernetes', status: 'In Progress', learningType: 'Course' }, { id: 'le2', title: 'System Design', status: 'Completed', learningType: 'Book' }] : [],
    }));
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/skills/active')) return json(skillOptions);
      if (url.endsWith('/proficiency-levels/active')) return json(levels);
      if (url.endsWith('/search/saved')) return json([{ id: 'sv1', name: 'Java experts', query: {}, createdAt: '' }, { id: 'sv2', name: 'React + SQL', query: {}, createdAt: '' }]);
      if (url.endsWith('/org/talent/search')) {
        return json({ items: summaries.filter(x => x.skills.length > 0).map(x => ({ employeeUserId: x.employeeUserId, employeeName: x.employeeName, employeeCode: x.employeeCode, departmentName: x.departmentName, managerName: x.managerName, matchingSkills: x.skills.map(k => ({ skillName: k.skillName, proficiencyLevel: k.proficiencyLevel })), certificates: x.certificates, currentLearningTrack: null, matchScore: 90 })), page: 0, size: 20, totalElements: 3, totalPages: 1 });
      }
      if (url.endsWith('/org/talent/directory')) return json(summaries);
      if (url.includes('/org/talent/directory/')) { const e = summaries.find(x => url.endsWith('/' + x.employeeUserId)); return e ? json({ employeeUserId: e.employeeUserId, employeeName: e.employeeName, skills: e.skills, certificates: e.certificates, learningEntries: e.learningEntries }) : json({}); }
      if (url.endsWith('/org/talent/dashboard')) {
        return json({
          totalEmployees: summaries.length, withSkills: summaries.filter(x => x.skills.length > 0).length, withCertificates: summaries.filter(x => x.certificates.length > 0).length, withActiveLearningTrack: 1,
          certificatesExpiringIn30Days: 1, topSkills: [{ name: 'Java', count: 2 }, { name: 'SQL', count: 1 }, { name: 'React', count: 1 }], proficiencyDistribution: [{ name: 'Beginner', count: 1 }, { name: 'Intermediate', count: 2 }, { name: 'Advanced', count: 2 }, { name: 'Expert', count: 1 }],
          skillsByDepartment: { Finance: [{ name: 'Java', count: 1 }], Engineering: [{ name: 'React', count: 1 }] }, certificatesByCategory: [{ name: 'Cloud', count: 3 }], certificateExpiryBreakdown: { in30Days: 1, in60Days: 1, in90Days: 2 }, learningStatusCounts: [{ name: 'In Progress', count: 1 }, { name: 'Completed', count: 1 }],
        });
      }
      if (url.endsWith('/org/talent/skills')) return json(skillOptions.map((x, i) => ({ ...x, active: i !== 4, usageCount: 12 - i * 2 })));
      if (url.endsWith('/org/talent/proficiency-levels')) return json(levels.map((l, i) => ({ ...l, active: true, usageCount: 20 - i * 4 })));
      if (url.endsWith('/org/talent/certificate-categories')) return json([{ id: 'c1', name: 'Cloud', hasExpiry: true, active: true, usageCount: 6 }, { id: 'c2', name: 'Security', hasExpiry: false, active: true, usageCount: 2 }]);
      if (url.endsWith('/skill-requests/pending')) return json([{ id: 'r1', requestedByUserId: 'u2', requesterName: 'Vandana', name: 'Kubernetes', category: 'Cloud', note: 'Used on the payroll project', status: 'PENDING', mappedToSkillId: null, mappedSkillName: null, createdAt: '' }]);
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'admin@example.com', mustChangePassword: false, role: 'SUPER_ADMIN' } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <TalentPerformancePage /> : null;
}

function LeaveMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample leave data and an Employee session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const req = (id: string, code: string, name: string, classification: string, start: string, end: string, days: number, status: string, reason: string, decidedByName: string | null, decisionReason: string | null) => ({
      id, employeeUserId: 'me', employeeName: 'Me', employeeCode: 'NF-1', leaveTypeCode: code, leaveTypeName: name, leaveTypeClassification: classification,
      startDate: start, endDate: end, halfDay: days === 0.5, halfDaySession: days === 0.5 ? 'FIRST_HALF' : null, totalDays: days, status,
      employeeReason: reason, decisionReason, decidedByName, decidedAt: decidedByName ? '2026-09-20T10:30:00' : null, createdAt: '2026-09-10T10:00:00',
    });
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/api/leave/types')) return json([{ id: 't1', code: 'ANNUAL', name: 'Annual Leave', classification: 'PAID' }, { id: 't2', code: 'CASUAL', name: 'Casual Leave', classification: 'PAID' }, { id: 't3', code: 'LOP', name: 'Loss Of Pay', classification: 'UNPAID' }]);
      if (url.endsWith('/api/leave/balances')) return json([{ leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', year: 2026, totalDays: 15, usedDays: 5, remainingDays: 10 }, { leaveTypeCode: 'CASUAL', leaveTypeName: 'Casual Leave', year: 2026, totalDays: 6, usedDays: 5, remainingDays: 1 }]);
      if (url.endsWith('/api/leave/requests/mine')) {
        return json([
          req('r1', 'ANNUAL', 'Annual Leave', 'PAID', '2026-09-30', '2026-09-30', 0.5, 'PENDING', 'ONEHR-562 fix verification', null, null),
          req('r2', 'CASUAL', 'Casual Leave', 'PAID', '2026-09-18', '2026-09-18', 1, 'PENDING', 'q', null, null),
          req('r3', 'LOP', 'Loss Of Pay', 'UNPAID', '2026-09-17', '2026-09-17', 1, 'APPROVED', 'Family function', 'Narendra Reddy', 'Approved, enjoy'),
          req('r4', 'ANNUAL', 'Annual Leave', 'PAID', '2026-08-04', '2026-08-06', 3, 'REJECTED', 'Trip', 'Narendra Reddy', 'Release week, please reschedule'),
        ]);
      }
      if (url.includes('/api/holidays')) return json([]);
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'employee@example.com', mustChangePassword: false, role: 'EMPLOYEE' } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <LeavePage /> : null;
}

function ApprovalsMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample queue and an HR session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const people: Array<[string, string, string]> = [
      ['u1', 'Naveen Kumar R', 'NF-20261001'], ['u2', 'Ramesh A', 'NF-20261009'], ['u3', 'Lalith Kumar Ratnala', 'NF-20261131'],
      ['u4', 'Vandana', 'NF-20261096'], ['u5', 'Abuzar Siddiqui', 'NF-20261004'],
    ];
    const kinds = ['LEAVE', 'REGULARIZATION', 'EXPENSE', 'ASSET_REQUEST', 'WFH'];
    const items = Array.from({ length: 27 }, (_, i) => {
      const [employeeUserId, employeeName] = people[i % people.length];
      const requestType = kinds[i % kinds.length];
      return {
        id: `a${i}`, requestType, employeeUserId, employeeName, createdAt: `2026-09-${String(10 + (i % 18)).padStart(2, '0')}T10:00:00`,
        leaveTypeName: 'Annual Leave', leaveStartDate: `2026-09-${String(16 + (i % 9)).padStart(2, '0')}`, leaveEndDate: `2026-09-${String(16 + (i % 9)).padStart(2, '0')}`, leaveTotalDays: 1, leaveReason: i % 3 === 0 ? 'q' : 'ONEHR-562 fix verification',
        attendanceDate: `2026-09-${String(10 + (i % 15)).padStart(2, '0')}`, regularizationReason: 'Forgot to punch out',
        expenseCategoryName: 'Travel', expenseAmount: 1200 + i * 50, expenseDate: '2026-09-12', businessPurpose: 'Client visit', approvalStage: 'FINAL',
        requestedCategoryName: 'Laptop', assetRequestReason: 'Replacement', assetRequestStatus: 'PENDING',
      };
    });
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/api/approvals')) return json(items);
      if (url.endsWith('/org-dashboard')) return json({ directReportCount: people.length, teamJoiners: [], directReports: people.map(([userId, fullName, employeeCode]) => ({ userId, fullName, employeeCode, designationName: null, departmentName: null, active: true })) });
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'hr@example.com', mustChangePassword: false, role: 'HR_ADMIN' } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <ApprovalsPage /> : null;
}

function DashboardMock() {
  const [params] = useSearchParams();
  const roleParam = (params.get('role') ?? 'superadmin').toUpperCase();
  const mockRole = roleParam === 'MANAGER' ? 'MANAGER' : roleParam === 'HR' ? 'HR_ADMIN' : 'SUPER_ADMIN';
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample data and a Super Admin session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const roles = ['EMPLOYEE', 'EMPLOYEE', 'EMPLOYEE', 'EMPLOYEE', 'EMPLOYEE', 'EMPLOYEE', 'MANAGER', 'MANAGER', 'HR_ADMIN', 'SUPER_ADMIN'];
    const users = Array.from({ length: 40 }, (_, i) => ({
      userId: `u${i}`, employeeCode: `NF-2026${1000 + i}`, fullName: `Employee ${i + 1}`, email: `e${i}@example.com`, role: roles[i % roles.length],
      businessUnitId: null, businessUnitName: null, departmentId: null, departmentName: 'Engineering', designationId: null, designationName: null, locationId: null, locationName: null,
      shiftId: null, shiftName: null, employmentType: 'FULL_TIME', workMode: 'OFFICE', joiningDate: '2026-10-05', active: i % 9 !== 0, currentManager: i % 7 === 0 ? null : { userId: 'u1', fullName: 'Manager' },
    }));
    const audit = ([
      ['USER_CREATED', 'ACCESS', 'Testo', '2026-10-29T16:27:00'], ['USER_CREATED', 'ACCESS', 'Testo', '2026-10-29T16:25:00'], ['USER_CREATED', 'ACCESS', 'Testo', '2026-10-29T16:23:00'],
      ['USER_SOFT_DELETED', 'ACCESS', 'Test', '2026-10-29T16:08:00'], ['LEAVE_APPROVED', 'LEAVE', 'Arun Kumar', '2026-10-28T11:02:00'], ['ASSET_ASSIGNED', 'ASSET', 'LPT-4567', '2026-10-28T10:40:00'],
    ] as Array<[string, string, string, string]>).map(([action, actionGroup, targetLabel, occurredAt], i) => ({
      id: i + 1, occurredAt, actorId: null, actorName: 'Naveen Kumar R', actorEmail: null, action, actionCategory: action, actionGroup, targetId: null, targetLabel,
      targetEmployeeCode: null, affectedUsers: null, beforeState: null, afterState: null,
    }));
    const kinds = ['LEAVE', 'REGULARIZATION', 'EXPENSE', 'ASSET_REQUEST', 'OVERTIME', 'WFH'];
    const approvals = Array.from({ length: 24 }, (_, i) => ({ id: `a${i}`, requestType: kinds[i % kinds.length], employeeUserId: `u${i}`, employeeName: `Employee ${i + 1}`, createdAt: '2026-10-20T10:00:00' }));
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/api/users')) return json(users);
      if (url.endsWith('/api/audit-logs/stats')) return json({ totalCount: 1697, todayCount: 12, byGroup: { EMPLOYEE: 249, LEAVE: 61, ATTENDANCE: 39, ASSET: 68, EXPENSE: 20, ACCESS: 1144 } });
      if (url.endsWith('/api/audit-logs/export')) return json([audit[0], audit[1], audit[2], audit[3], audit[0], audit[1]]);
      if (url.endsWith('/api/audit-logs')) return json({ content: audit, totalElements: audit.length, totalPages: 1, number: 0, size: 8 });
      if (url.endsWith('/api/approvals')) return json(approvals);
      if (url.endsWith('/kpis')) return json({ pendingVerification: 5, employeesWithPending: 3, expiringWithin30Days: 2, totalDocuments: 120 });
      if (url.endsWith('/api/assets/count')) return json({ count: 35 });
      if (url.endsWith('/my-reports') || url.endsWith('/org-dashboard')) {
        return json({
          directReportCount: 8,
          directReports: users.slice(0, 8).map(u => ({ userId: u.userId, employeeCode: u.employeeCode, fullName: u.fullName, designationName: 'Software Engineer', departmentName: 'Engineering', active: u.active, joiningDate: u.joiningDate, roleCode: u.role })),
          teamJoiners: [{ userId: 'u3', employeeCode: 'NF-20261003', fullName: 'Employee 4', designationName: null, departmentName: null, active: true, joinedTeamOn: '2026-09-12' }, { userId: 'u5', employeeCode: 'NF-20261005', fullName: 'Employee 6', designationName: null, departmentName: null, active: true, joinedTeamOn: '2026-10-05' }, { userId: 'u6', employeeCode: 'NF-20261006', fullName: 'Employee 7', designationName: null, departmentName: null, active: true, joinedTeamOn: '2026-07-20' }],
        });
      }
      if (url.endsWith('/api/employees/birthdays')) return json([]);
      if (url.endsWith('/api/notifications/unread')) {
        return json({ content: [
          { id: 1, type: 'LEAVE_REQUEST', title: 'New leave request', message: 'Arun Kumar requested 2 days of Annual Leave', linkPath: '/approvals', read: false, priority: 'NORMAL', createdAt: new Date(Date.now() - 600000).toISOString() },
          { id: 2, type: 'ASSET', title: 'Asset request approved', message: 'A laptop request is ready for fulfilment', linkPath: '/assets', read: false, priority: 'HIGH', createdAt: new Date(Date.now() - 7200000).toISOString() },
        ], totalElements: 2, totalPages: 1, number: 0, size: 8 });
      }
      if (url.includes('/api/')) return json([]);
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'admin@example.com', fullName: 'Anil Kumar', mustChangePassword: false, role: mockRole } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, [mockRole]);
  return ready ? <DashboardPage /> : null;
}

function HolidaysMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample holidays and a Super Admin session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const rows = ([
      ['2026-01-26', 'Republic Day', 'Hyderabad'], ['2026-01-26', 'Republic Day', 'Bangalore'], ['2026-04-11', 'Dussehra', 'Bangalore'], ['2026-08-15', 'Independence Day', 'Bangalore'],
      ['2026-10-29', 'Founders Day', 'Hyderabad'], ['2026-11-08', 'Diwali', 'Bangalore'], ['2026-11-09', 'Govardhan Puja', 'Hyderabad'], ['2026-11-25', 'Guru Nanak Jayanti', 'Vishakhapatnam'],
      ['2026-12-25', 'Christmas', 'Hyderabad'], ['2026-12-25', 'Christmas', 'Bangalore'], ['2026-12-31', 'New Year Eve', 'Vishakhapatnam'],
    ] as Array<[string, string, string]>).map(([holidayDate, holidayName, locationName], i) => ({ id: `h${i}`, holidayName, holidayDate, locationId: locationName, locationName, active: i !== 6 }));
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/api/holidays') || url.endsWith('/api/holidays/my-location')) return json(rows);
      if (url.endsWith('/locations')) return json(['Hyderabad', 'Bangalore', 'Vishakhapatnam'].map(name => ({ id: name, name, active: true })));
      if (url.includes('/api/')) return json([]);
      return prevFetch(input, init);
    }) as typeof window.fetch;
    const r = new URLSearchParams(window.location.search).get('role');
    const mockRole = r === 'hr' ? 'HR_ADMIN' : r === 'employee' ? 'EMPLOYEE' : 'SUPER_ADMIN';
    useAuthStore.setState({ token: 'mock-token', user: { email: 'admin@example.com', fullName: 'Anil Kumar', mustChangePassword: false, role: mockRole } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <HolidaysPage /> : null;
}

function DirectoryMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample people and a chosen session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const names = ['Aanuj Reddy K', 'Abbas DarubaiGari', 'Abuzar Siddiqui', 'Anil Kumar Ramtenki', 'Priya Nair', 'Rohit Sharma', 'Meera Iyer', 'Karthik S'];
    const people = names.map((fullName, i) => ({
      userId: `u${i}`, employeeCode: `NF-2026${1030 - i}`, fullName, email: `${fullName.split(' ')[0].toLowerCase()}@nforceone.com`,
      departmentName: i % 3 === 0 ? 'Engineering' : i % 3 === 1 ? 'Finance' : 'Sales', designationName: i === 1 ? null : ['Junior Sales Engineer', 'Software Engineer', 'Backend Developer', 'Analyst'][i % 4],
      locationName: ['Hyderabad', 'Bangalore', 'Vishakhapatnam'][i % 3], workMode: ['ONSITE', 'HYBRID', 'REMOTE'][i % 3], employmentType: i % 4 === 3 ? 'PART_TIME' : 'FULL_TIME',
      active: i !== 5, managerName: i === 3 ? null : 'Vignesh Appani', managerEmail: 'vignesh@nforceone.com',
    }));
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/api/employees/directory')) return json(people);
      if (url.endsWith('/photo')) return Promise.resolve(new Response(null, { status: 404 }));
      if (url.includes('/api/')) return json([]);
      return prevFetch(input, init);
    }) as typeof window.fetch;
    const r = new URLSearchParams(window.location.search).get('role');
    const mockRole = r === 'hr' ? 'HR_ADMIN' : r === 'employee' ? 'EMPLOYEE' : r === 'manager' ? 'MANAGER' : 'SUPER_ADMIN';
    useAuthStore.setState({ token: 'mock-token', user: { email: 'admin@example.com', fullName: 'Anil Kumar', mustChangePassword: false, role: mockRole } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <DirectoryPage /> : null;
}

function UserMgmtMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample users and org data with a Super Admin session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const depts = ['Engineering', 'Finance', 'Sales', 'Human Resources'].map((name, i) => ({ id: `d${i}`, name, active: true, employeeCount: 5, createdAt: '', updatedAt: '' }));
    const desigs = ['Software Engineer', 'Analyst', 'Sales Executive', 'HR Executive'].map((title, i) => ({ id: `g${i}`, title, grade: null, level: null, departmentId: `d${i}`, departmentName: depts[i].name, active: true, employeeCount: 3, createdAt: '', updatedAt: '' }));
    const locs = ['Hyderabad', 'Bangalore', 'Vishakhapatnam'].map((name, i) => ({ id: `l${i}`, name, city: name, state: null, country: 'India', timezone: 'Asia/Kolkata', active: true }));
    const roles = ['EMPLOYEE', 'EMPLOYEE', 'MANAGER', 'HR_ADMIN', 'SUPER_ADMIN'];
    const users = ['Aanuj Reddy K', 'Abbas DarubaiGari', 'Abuzar Siddiqui', 'Priya Nair', 'Rohit Sharma', 'Meera Iyer'].map((fullName, i) => ({
      userId: `u${i}`, employeeCode: `NF-2026${1030 - i}`, fullName, email: `${fullName.split(' ')[0].toLowerCase()}@nforceone.com`, role: roles[i % roles.length],
      businessUnitId: null, businessUnitName: null, departmentId: `d${i % 4}`, departmentName: depts[i % 4].name, designationId: `g${i % 4}`, designationName: desigs[i % 4].title,
      locationId: `l${i % 3}`, locationName: locs[i % 3].name, shiftId: null, shiftName: null, employmentType: 'FULL_TIME', workMode: 'ONSITE', joiningDate: '2026-04-12', active: i !== 4,
      currentManager: i === 3 ? null : { userId: 'u3', fullName: 'Priya Nair' },
    }));
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/users')) return json(users);
      if (url.endsWith('/employees/potential-managers')) return json(users.filter(u => u.role !== 'EMPLOYEE'));
      if (url.endsWith('/employees/next-code')) return json({ employeeCode: 'NF-20261305' });
      if (url.endsWith('/business-units')) return json([{ id: 'b1', name: 'Technology', active: true, employeeCount: 10, createdAt: '', updatedAt: '' }]);
      if (url.endsWith('/departments')) return json(depts);
      if (url.endsWith('/designations')) return json(desigs);
      if (url.endsWith('/locations')) return json(locs);
      if (url.endsWith('/shifts')) return json([]);
      if (url.includes('/api/')) return json([]);
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'admin@example.com', fullName: 'Anil Kumar', mustChangePassword: false, role: 'SUPER_ADMIN' } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <UserManagementPage /> : null;
}

function OrgMastersMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample org data with a Super Admin session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const named = (names: string[]) => names.map((name, i) => ({ id: `x${i}`, name, active: i !== 3, employeeCount: 12 - i * 2, createdAt: '2026-01-10T00:00:00', updatedAt: '2026-01-10T00:00:00' }));
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/business-units')) return json(named(['Workday', 'Internal', 'R&D', 'Operations']));
      if (url.endsWith('/departments')) return json(named(['Engineering', 'Finance', 'Sales', 'Quality']));
      if (url.endsWith('/designations')) return json(['Software Engineer', 'Analyst'].map((title, i) => ({ id: `g${i}`, title, grade: 'L2', level: 'Mid', departmentId: 'x0', departmentName: 'Engineering', active: true, employeeCount: 4, createdAt: '', updatedAt: '' })));
      if (url.endsWith('/locations')) return json(['Hyderabad', 'Bangalore'].map((name, i) => ({ id: `l${i}`, name, city: name, state: 'Telangana', country: 'India', holidayRegion: 'TS', timezone: 'Asia/Kolkata', active: true, employeeCount: 20, createdAt: '', updatedAt: '' })));
      if (url.endsWith('/locations/timezones') || url.includes('timezones')) return json(['Asia/Kolkata', 'America/New_York', 'Europe/London']);
      if (url.includes('/api/')) return json([]);
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'admin@example.com', fullName: 'Anil Kumar', mustChangePassword: false, role: 'SUPER_ADMIN' } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <OrgSetupPage /> : null;
}

function ReportsMock() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Mock only: sample org data with a Super Admin session; everything is restored on leave.
    const prevFetch = window.fetch;
    const prevAuth = useAuthStore.getState();
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const names = ['Abuzar Siddiqui', 'Vandana', 'Venkat Narsimha Reddy', 'Venkatesh', 'Naveen Kumar R', 'Ramesh A', 'Testo', 'Priya Nair', 'Rohit Sharma', 'Meera Iyer'];
    const depts = ['Finance', 'Engineering', 'Sales', 'Quality Engineering', 'Human Resources'];
    const reports = names.map((fullName, i) => ({ userId: `u${i}`, employeeCode: `NF-2026${1004 + i}`, fullName, designationName: ['Finance Analyst', 'Senior QA Engineer', 'Software Developer', 'HR Executive'][i % 4], departmentName: depts[i % 5], active: i !== 9, roleCode: 'EMPLOYEE' }));
    const today = new Date().toISOString().slice(0, 10);
    const statuses = ['PRESENT', 'PRESENT', 'LATE', 'PRESENT', 'ABSENT', 'PRESENT', 'HALF_DAY', 'PRESENT', 'LATE', 'PRESENT'];
    const attendance = reports.map((r, i) => ({ id: `a${i}`, employeeUserId: r.userId, employeeCode: r.employeeCode, fullName: r.fullName, workDate: today, checkInAt: `${today}T0${9 + (i % 2)}:${i % 2 ? '25' : '05'}:00`, checkOutAt: null, sessionStartedAt: null, workedMinutes: 300, status: statuses[i], lateByMinutes: statuses[i] === 'LATE' ? 22 : null, source: 'SYSTEM', workMode: 'ONSITE', timezone: null }));
    const leaveRows = [0, 3, 6].map(i => ({ id: `l${i}`, employeeUserId: reports[i].userId, employeeName: reports[i].fullName, employeeCode: reports[i].employeeCode, leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', startDate: today, endDate: today, halfDay: i === 3, totalDays: i === 3 ? 0.5 : 1, status: ['APPROVED', 'PENDING', 'APPROVED'][i / 3] }));
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/org-dashboard') || url.endsWith('/my-reports')) return json({ directReportCount: reports.length, directReports: reports, teamJoiners: [] });
      if (url.endsWith('/attendance/day') || url.endsWith('/attendance/team')) return json(attendance);
      if (url.endsWith('/leave/organization') || url.endsWith('/leave/team')) return json(leaveRows);
      if (url.endsWith('/leave/approvals')) return json([]);
      if (url.endsWith('/photo')) return Promise.resolve(new Response(null, { status: 404 }));
      if (url.includes('/api/')) return json([]);
      return prevFetch(input, init);
    }) as typeof window.fetch;
    useAuthStore.setState({ token: 'mock-token', user: { email: 'admin@example.com', fullName: 'Anil Kumar', mustChangePassword: false, role: 'SUPER_ADMIN' } });
    setReady(true);
    return () => {
      window.fetch = prevFetch;
      useAuthStore.setState({ token: prevAuth.token, user: prevAuth.user });
    };
  }, []);
  return ready ? <ReportsPage /> : null;
}

function Tiles({ page }: { page: string }) {
  switch (page) {
    case 'menu':
      return (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 14, alignItems: 'start' }}>
          {(['Employee', 'Manager', 'HR Admin', 'Super Admin'] as Role[]).map((r) => (
            <div key={r} style={{ background: '#0B0C0F', border: '1px solid var(--line)', borderRadius: 14, overflow: 'hidden', display: 'flex', flexDirection: 'column', height: 640 }}>
              <div style={{ padding: '12px 16px', fontSize: 11, fontWeight: 800, letterSpacing: '.08em', textTransform: 'uppercase', color: '#9aa1ac', borderBottom: '1px solid #1c1f26' }}>{r}</div>
              <SidebarNav items={NAV[r]} currentKey="dashboard" onNavigate={() => {}} />
            </div>
          ))}
        </div>
      );
    case 'masters-page':
      return <OrgMastersMock />;
    case 'masters':
      return <MastersMock />;
    case 'people':
      return (
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead><tr>{['Name', 'Email', 'Role', 'Department'].map((h) => <th key={h} style={th}>{h}</th>)}</tr></thead>
            <tbody>
              {PEOPLE.map((p) => (
                <tr key={p.id}>
                  <td style={td}><PersonCell userId={p.id} name={p.name} code={p.code} photoDataUrl={p.photo} /></td>
                  <td style={{ ...td, color: 'var(--txt)' }}>{p.email}</td>
                  <td style={td}>{p.role}</td>
                  <td style={td}>{p.dept}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    case 'headers':
      return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {HEADERS.map((h) => {
            const actions = HEADER_ACTIONS[h.title] ?? [];
            return (
              <div key={h.title} className="nf-page-hdr" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
                <PageTitleBlock icon={h.icon}>
                  <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>{h.title}</h1>
                  <p style={{ fontSize: 13, color: 'var(--txt-mut)', margin: '3px 0 0' }}>{h.subtitle}</p>
                </PageTitleBlock>
                {actions.length > 0 && (
                  <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
                    {actions.map((a) => {
                      const primary = a.startsWith('+') || a === 'Request Regularization' || a === 'Request Leave';
                      return (
                        <button key={a} type="button" style={{
                          padding: '8px 14px', borderRadius: 8, fontSize: 12.5, fontWeight: 700, cursor: 'pointer',
                          background: primary ? 'var(--brand)' : 'var(--raised)', color: primary ? '#fff' : 'var(--brand)',
                          border: primary ? 'none' : '1px solid color-mix(in srgb, var(--brand) 40%, var(--line))',
                        }}>{a}</button>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      );
    case 'performance':
      return (
        <>
          <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 12 }}>
            <KpiCard icon={<Users size={20} />} label="Total Employees" value={262} accent="var(--info)" onClick={noop} />
            <KpiCard icon={<Sparkles size={20} />} label="With Skills Logged" value={214} note="of 262" accent="var(--ok)" onClick={noop} />
            <KpiCard icon={<Award size={20} />} label="With Certificates" value={131} note="of 262" accent="var(--om-purple)" onClick={noop} />
            <KpiCard icon={<AlertTriangle size={20} />} label="Certs Expiring (30d)" value={9} danger accent="var(--warn)" onClick={noop} />
          </div>
          <div style={rowTitle}>Certificate expiry</div>
          <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 12 }}>
            <KpiCard icon={<AlertTriangle size={20} />} label="Expiring in 30 days" value={9} danger onClick={noop} />
            <KpiCard icon={<AlertTriangle size={20} />} label="Expiring in 60 days" value={14} accent="var(--warn)" onClick={noop} />
            <KpiCard icon={<AlertTriangle size={20} />} label="Expiring in 90 days" value={22} accent="var(--info)" onClick={noop} />
          </div>
        </>
      );
    case 'access':
      return <AccessStatTiles total={262} active={249} inactive={13} roleSummary="231 Employee · 24 Manager · 5 HR Admin · 2 Super Admin" />;
    case 'leave':
      return (
        <div className="nf-kpi-2x2-mobile" style={grid(180)}>
          <KpiTile icon={Wallet} label="Available Leave" value="14d" sub="Across all leave types" accent="var(--ok)" />
          <KpiTile icon={CheckCircle2} label="Used This Year" value="6d" sub="Approved & consumed" accent="var(--info)" />
          <KpiTile icon={Hourglass} label="Pending Requests" value="1" sub="Awaiting a decision" accent="var(--warn)" />
          <KpiTile icon={CalendarClock} label="Upcoming Leave" value="Oct 28" sub="Casual Leave" accent="var(--brand)" />
        </div>
      );
    case 'requests':
      return (
        <div style={grid(170)}>
          <Kpi icon={<Inbox size={20} />} tone="tone-brand" label="Active Queue" value={18} danger />
          <Kpi icon={<CircleDot size={20} />} tone="tone-warn" label="Open" value={11} />
          <Kpi icon={<Clock size={20} />} tone="tone-indigo" label="In Progress" value={7} />
          <Kpi icon={<CheckCircle2 size={20} />} tone="tone-ok" label="Resolved" value={64} />
          <Kpi icon={<Lock size={20} />} tone="tone-mute" label="Closed" value={120} />
        </div>
      );
    case 'my-requests':
      return (
        <div className="nf-rq-ring-row">
          <StatRing value={12} total={12} pct={100} label="Total Requests" caption="All time" tone="tone-indigo" active onClick={noop} />
          <StatRing value={3} total={12} pct={25} label="Pending" caption="Awaiting action" tone="tone-warn" active={false} onClick={noop} />
          <StatRing value={8} total={12} pct={67} label="Approved" caption="Granted" tone="tone-ok" active={false} onClick={noop} />
          <StatRing value={1} total={12} pct={8} label="Needs Action" caption="Requires your attention" tone="tone-risk" active={false} onClick={noop} />
        </div>
      );
    case 'assets':
      return (
        <>
          <div style={rowTitle}>Employee</div>
          <div style={grid(200)}>
            <Tile label="My Assigned Assets" value={3} sub="Laptop, monitor, phone" clickable onClick={noop} />
            <Tile label="Open Expense Claims" value={2} sub="Rs 8,400" clickable onClick={noop} />
            <Tile label="Approved This Month" value="Rs 12,000" clickable onClick={noop} />
            <Tile label="My Asset Requests" value={1} clickable onClick={noop} />
          </div>
          <div style={rowTitle}>Manager</div>
          <div style={grid(200)}>
            <Tile label="Team Assets Assigned" value={18} sub="6 team members" clickable onClick={noop} />
            <Tile label="Pending Expense Claims" value={4} sub="Rs 21,300" clickable onClick={noop} clickHint="Review in Approval Center →" />
            <Tile label="Overdue Returns" value={1} clickable onClick={noop} />
            <Tile label="Pending Asset Requests" value={2} clickable onClick={noop} clickHint="Review in Approval Center →" />
            <Tile label="Approved This Month" value="Rs 46,500" clickable onClick={noop} />
          </div>
          <div style={rowTitle}>HR Admin</div>
          <div style={grid(200)}>
            <Tile label="Assets Assigned" value={412} clickable onClick={noop} />
            <Tile label="Available Inventory" value={119} clickable onClick={noop} />
            <Tile label="Overdue Returns" value={7} clickable onClick={noop} />
            <Tile label="Pending Expense Clearance" value={9} sub="Rs 64,200" clickable onClick={noop} clickHint="Review in Approval Center →" />
            <Tile label="Pending Asset Fulfillment" value={5} clickable onClick={noop} />
          </div>
        </>
      );
    case 'audit': {
      const stats = { totalCount: 1284, todayCount: 17, byGroup: { EMPLOYEE: 402, LEAVE: 318, ATTENDANCE: 291, ASSET: 96, EXPENSE: 77, ACCESS: 100 } };
      return (
        <>
          <div style={rowTitle}>HR Admin (5 tiles)</div>
          <AuditStatCards stats={stats} showAccess={false} />
          <div style={rowTitle}>Super Admin (6 tiles)</div>
          <AuditStatCards stats={stats} showAccess />
          <div style={rowTitle}>Category tabs + log table (Super Admin)</div>
          <AuditPillsMock showAccess />
        </>
      );
    }
    case 'reports-page':
      return <ReportsMock />;
    case 'reports':
      return (
        <>
          <div style={rowTitle}>HR Admin</div>
          <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 12 }}>
            <ReportKpi icon={<Users size={20} />} label="Total Headcount" value={262} note="249 active · 13 inactive" accent="var(--info)" />
            <ReportKpi icon={<CalendarCheck size={20} />} label="Present Today" value={203} note="of 262 for 2026-10-16" accent="var(--ok)" />
            <ReportKpi icon={<Clock size={20} />} label="Late / Absent" value="12 / 5" note="on the selected date" danger accent="var(--warn)" />
            <ReportKpi icon={<CalendarCheck size={20} />} label="Leave (period)" value={31} note="96d approved · 4 pending" accent="var(--om-purple)" />
          </div>
          <div style={rowTitle}>Manager</div>
          <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 12 }}>
            <ReportKpi icon={<Users size={20} />} label="Direct Reports" value={6} note="6 active · 0 inactive" accent="var(--info)" />
            <ReportKpi icon={<CalendarCheck size={20} />} label="Present Today" value={5} note="of 6 for 2026-10-16" accent="var(--ok)" />
            <ReportKpi icon={<Clock size={20} />} label="Late / Absent" value="1 / 0" note="on the selected date" accent="var(--warn)" />
            <ReportKpi icon={<CalendarCheck size={20} />} label="Leave (period)" value={3} note="7d approved · 1 pending" accent="var(--om-purple)" />
          </div>
          <div style={rowTitle}>Employees table</div>
          <ReportSection title="Employees" count="6">
            <HeadcountRows rows={[
              ['u1', 'NF-20261004', 'Abuzar Siddiqui', 'Backend Developer', 'Finance', true],
              ['u2', 'NF-20261096', 'Vandana', 'Senior QA Engineer', 'Engineering', true],
              ['u3', 'NF-20261019', 'Venkat Narsimha Reddy', 'Software Developer', 'Sales', true],
              ['u4', 'NF-20261011', 'Venkatesh', 'Finance Analyst', 'Finance', true],
              ['u5', 'NF-20261009', 'Ramesh A', 'Engineering Manager', 'Quality Engineering', true],
              ['u6', 'NF-20261131', 'lalith lalith-', null, null, false],
            ].map(([userId, employeeCode, fullName, designationName, departmentName, active]) => ({ userId, employeeCode, fullName, designationName, departmentName, active } as never))} />
          </ReportSection>
        </>
      );
    case 'api-usage':
      return (
        <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 12 }}>
          <ApiKpi icon={<MessageSquareText size={20} />} iconColor="var(--info)" label="Total Requests" value="18,420" note="9,310 questions asked" />
          <ApiKpi icon={<Zap size={20} />} iconColor="var(--brand-bright)" label="Total Tokens" value="4,118,902" note="3.2M prompt · 0.8M completion · 0.1M embedding" />
          <ApiKpi icon={<Activity size={20} />} iconColor="var(--ok)" label="Success Rate" value="97.8%" note="204 failed questions" />
          <ApiKpi icon={<Gauge size={20} />} iconColor="var(--warn)" label="Avg Latency" value="1240ms" note="per completed request" />
        </div>
      );
    case 'policies':
      return <PolicyStatTiles activePolicies={14} pendingAck={37} announcements={9} />;
    case 'holidays': {
      const y = new Date().getFullYear();
      const m = String(new Date().getMonth() + 1).padStart(2, '0');
      const mk = (id: string, d: string, n: string, loc = 'Hyderabad') => ({ id, holidayName: n, holidayDate: `${y}-${m}-${d}`, locationId: loc, locationName: loc, active: true });
      const rows = [mk('1', '02', 'Gandhi Jayanti'), mk('2', '12', 'Dussehra'), mk('3', '12', 'Dussehra', 'Bengaluru'), mk('4', '20', 'Diwali Eve'), mk('5', '21', 'Diwali'), mk('6', '22', 'Govardhan Puja')];
      return <div style={{ maxWidth: 400 }}><HolidayMonthCalendar holidays={rows} /></div>;
    }
    case 'api-page':
      return <ApiUsageMock />;
    case 'assets-page':
      return <AssetsMock />;
    case 'opps-page':
      return <OppsMock />;
    case 'allocations-page':
      return <AllocationsMock />;
    case 'bench-page':
      return <BenchMock />;
    case 'docs-compliance-page':
      return <DocsComplianceMock />;
    case 'roles-page':
      return <RolesMock />;
    case 'employee-edit':
      return <EmployeeEditMock />;
    case 'user-edit':
      return <UserEditMock />;
    case 'create-employee-steps':
      return <CreateEmployeeStepsPreview />;
    case 'policies-page':
      return <PoliciesMock />;
    case 'notifications-page':
      return <NotificationsMock />;
    case 'profile-page':
      return <ProfileMock />;
    case 'prefs-page':
      return <UserPreferencesPage />;
    case 'attention-panel':
      return <AttentionMock />;
    case 'onboarding-page':
      return <OnboardingMock />;
    case 'team-popups':
      return <TeamPopupsMock />;
    case 'team-today':
      return <TeamTodayMock />;
    case 'team-reports':
      return <TeamReportsMock />;
    case 'change-password-page':
      return <ChangePasswordMock />;
    case 'documents-page':
      return <DocumentsMock />;
    case 'help-page':
      return <HelpMock />;
    case 'my-requests-page':
      return <MyRequestsMock />;
    case 'workflows-page':
      return <WorkflowMock />;
    case 'talent-page':
      return <TalentMock />;
    case 'leave-page':
      return <LeaveMock />;
    case 'approvals-page':
      return <ApprovalsMock />;
    case 'user-mgmt-page':
      return <UserMgmtMock />;
    case 'directory-page':
      return <DirectoryMock />;
    case 'holidays-page':
      return <HolidaysMock />;
    case 'dashboard-page':
      return <DashboardMock />;
    case 'documents':
      return <DocumentStatTiles required={6} verified={3} pending={1} rejected={1} missing={1} />;
    default:
      return null;
  }
}

export default function TilesPreviewShell() {
  const [params, setParams] = useSearchParams();
  const { accent, setAccent } = useAccentColor();
  const active = PAGES.find((p) => p.key === params.get('page')) ?? PAGES[0];
  return (
    <div style={{ minHeight: '100vh', background: 'var(--shell)', padding: '24px clamp(16px, 4vw, 40px)', display: 'flex', flexDirection: 'column', gap: 18 }}>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
        <div role="tablist" aria-label="Preview page" style={{ display: 'inline-flex', flexWrap: 'wrap', gap: 4, padding: 4, borderRadius: 10, background: 'var(--panel)', border: '1px solid var(--line)' }}>
          {PAGES.map((p) => {
            const on = p.key === active.key;
            return (
              <button key={p.key} role="tab" aria-selected={on} onClick={() => setParams({ page: p.key })}
                style={{ padding: '6px 12px', borderRadius: 7, border: 'none', cursor: 'pointer', fontSize: 12.5, fontWeight: 700, background: on ? 'var(--brand)' : 'transparent', color: on ? '#fff' : 'var(--txt-mut)' }}>
                {p.label}
              </button>
            );
          })}
        </div>
        <div role="radiogroup" aria-label="Theme color" style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '4px 12px', borderRadius: 10, background: 'var(--panel)', border: '1px solid var(--line)', height: 40, boxSizing: 'border-box' }}>
          <span style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '.08em', color: 'var(--txt-dim)' }}>THEME</span>
          {(Object.keys(ACCENT_SWATCHES) as AccentColor[]).map((key) => {
            const sw = ACCENT_SWATCHES[key];
            const on = accent === key;
            return (
              <button key={key} role="radio" aria-checked={on} aria-label={sw.label} title={sw.label} onClick={() => setAccent(key)}
                style={{ width: 16, height: 16, borderRadius: '50%', background: sw.brandBright, border: on ? '2px solid var(--txt)' : '2px solid transparent', cursor: 'pointer', padding: 0 }} />
            );
          })}
        </div>
        <div style={{ display: 'inline-flex', padding: '5px 10px', borderRadius: 6, background: 'rgba(99,102,241,.10)', border: '1px solid rgba(99,102,241,.25)', fontSize: 11.5, fontWeight: 600, color: '#8183f4' }}>
          🔍 UI PREVIEW · Mock Data (Not Real)
        </div>
      </div>
      <div>
        <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, color: 'var(--txt)' }}>{active.title}</h1>
        <p style={{ margin: '4px 0 0', fontSize: 13, color: 'var(--txt-dim)' }}>{active.subtitle}</p>
      </div>
      <Tiles page={active.key} />
    </div>
  );
}

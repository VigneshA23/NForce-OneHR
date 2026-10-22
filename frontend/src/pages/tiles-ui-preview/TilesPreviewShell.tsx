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
import type { Role } from '../../lib/nav.config';
import { TabBar } from '../../components/TabBar';
import { useState } from 'react';
import { useAccentColor, ACCENT_SWATCHES, type AccentColor } from '../../lib/accentColor';
import { KpiCard } from '../talent/DashboardTab';
import { KpiTile } from '../LeavePage';
import { AccessStatTiles } from '../UserManagementPage';
import { Kpi } from '../HelpDeskAdminPage';
import { StatRing } from '../MyRequestsPage';
import { Tile } from '../assets/shared';
import { PolicyStatTiles } from '../PoliciesPage';
import { DocumentStatTiles } from '../DocumentsPage';
import { AuditStatCards } from '../../components/audit/AuditStatCards';
import { AuditCategoryChips } from '../../components/audit/AuditCategoryChips';
import { AuditLogTable } from '../../components/audit/AuditLogTable';
import type { AuditLogEntry } from '../../api/audit';
import { KpiCard as ReportKpi, Section as ReportSection, HeadcountRows } from '../ReportsPage';
import { KpiCard as ApiKpi } from '../ApiUsagePage';
import { HolidayMonthCalendar } from '../HolidaysPage';

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
  id: i + 1, occurredAt, actorId: null, actorName: 'Admin', actorEmail: null, action, actionCategory: action,
  actionGroup, targetId: null, targetLabel, targetEmployeeCode: null, affectedUsers: null, beforeState: null, afterState: null,
} as unknown as AuditLogEntry));

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

function Tiles({ page }: { page: string }) {
  switch (page) {
    case 'menu':
      return (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 14, alignItems: 'start' }}>
          {(['Employee', 'Manager', 'HR Admin', 'Super Admin'] as Role[]).map((r) => (
            <div key={r} style={{ background: '#0B0C0F', border: '1px solid var(--line)', borderRadius: 14, overflow: 'hidden', display: 'flex', flexDirection: 'column', height: 640 }}>
              <div style={{ padding: '12px 16px', fontSize: 11, fontWeight: 800, letterSpacing: '.08em', textTransform: 'uppercase', color: '#9aa1ac', borderBottom: '1px solid #1c1f26' }}>{r}</div>
              <SidebarNav role={r} currentKey="dashboard" onNavigate={() => {}} />
            </div>
          ))}
        </div>
      );
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

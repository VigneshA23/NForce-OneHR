/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW — NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  Standalone preview of a proposed redesign for the "Leave & Holidays"
 *  module (currently `pages/LeavePage.tsx`, mounted at the real `/leave`
 *  route inside the app's real authenticated <Shell>).
 *
 *  - Every value on this page is hard-coded mock data defined in this file.
 *  - It makes NO network requests, reads NO auth/session state (it does not
 *    import useAuthStore, any `api/*` module, or the real <Shell>), and
 *    touches NO real API, database, or business logic.
 *  - The sidebar/topbar below is a static, non-functional VISUAL REPLICA of
 *    the real <Shell> (components/Shell.tsx) + Employee nav
 *    (lib/nav.config.ts) — same widths/colors/structure — so this content
 *    can be reviewed "in place" without touching or depending on the real
 *    shell. Nothing here edits Shell.tsx, SidebarNav.tsx, or nav.config.ts.
 *  - Isolated in `pages/preview/`, wired to its own public route
 *    (`/leave-management-preview`), no auth required.
 *
 *  Once a direction is approved, the approved parts get ported into
 *  `pages/LeavePage.tsx` (rendered inside the real <Shell>, against real
 *  data) — this file is not meant to be imported by, or merged into,
 *  production code as-is.
 * ============================================================================
 */
import { useMemo, useState } from 'react';
import {
  Bell, CalendarClock, CalendarDays, CalendarPlus, CheckCircle2, ChevronLeft, ChevronRight,
  Clock, FileText, GitBranch, Home, Hourglass, HelpCircle, Menu, Moon, Package, Search,
  Sparkles, Sun, Users, Wallet, X,
} from 'lucide-react';
import logoUrl from '../../assets/nforce-logo.png';

// ─────────────────────────────────────────────────────────────────────────
// Mock data — shape mirrors the real LeaveBalance / LeaveRequestRecord /
// LeaveType / HolidayRow types in api/leave.ts and api/holidays.ts, but
// every value below is invented for this preview only.
// ─────────────────────────────────────────────────────────────────────────

type MockStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
type ModuleTab = 'leave' | 'holidays';

interface MockLeaveType { code: string; name: string; }
interface MockBalance { leaveTypeCode: string; leaveTypeName: string; totalDays: number; usedDays: number; remainingDays: number; }
interface MockRequest {
  id: string; leaveTypeCode: string; leaveTypeName: string; startDate: string; endDate: string;
  halfDay: boolean; totalDays: number; status: MockStatus; employeeReason: string;
  decisionReason: string | null; decidedByName: string | null; decidedAt: string | null;
}
interface MockHoliday { id: string; holidayName: string; holidayDate: string; locationName: string; active: boolean; }

const MOCK_TYPES: MockLeaveType[] = [
  { code: 'ANNUAL', name: 'Annual Leave' },
  { code: 'SICK', name: 'Sick Leave' },
  { code: 'CASUAL', name: 'Casual Leave' },
  { code: 'MATERNITY', name: 'Maternity & Parental Leave' },
];

// Deliberately includes edge cases: a near-exhausted balance (Casual) and a
// zero-quota type (Maternity) so both card states can be reviewed.
const MOCK_BALANCES: MockBalance[] = [
  { leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', totalDays: 18, usedDays: 6.5, remainingDays: 11.5 },
  { leaveTypeCode: 'SICK', leaveTypeName: 'Sick Leave', totalDays: 10, usedDays: 2, remainingDays: 8 },
  { leaveTypeCode: 'CASUAL', leaveTypeName: 'Casual Leave', totalDays: 6, usedDays: 5.5, remainingDays: 0.5 },
  { leaveTypeCode: 'MATERNITY', leaveTypeName: 'Maternity & Parental Leave', totalDays: 0, usedDays: 0, remainingDays: 0 },
];

const MOCK_REQUESTS: MockRequest[] = [
  { id: 'r14', leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', startDate: '2026-10-12', endDate: '2026-10-16', halfDay: false, totalDays: 5, status: 'PENDING', employeeReason: 'Family wedding out of town, travelling with dependents', decisionReason: null, decidedByName: null, decidedAt: null },
  { id: 'r13', leaveTypeCode: 'SICK', leaveTypeName: 'Sick Leave', startDate: '2026-09-25', endDate: '2026-09-25', halfDay: true, totalDays: 0.5, status: 'PENDING', employeeReason: 'Dentist appointment', decisionReason: null, decidedByName: null, decidedAt: null },
  { id: 'r12', leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', startDate: '2026-09-18', endDate: '2026-09-19', halfDay: false, totalDays: 2, status: 'APPROVED', employeeReason: 'Short personal trip', decisionReason: null, decidedByName: 'Priya Raghunathan', decidedAt: '2026-09-16T10:12:00' },
  { id: 'r11', leaveTypeCode: 'CASUAL', leaveTypeName: 'Casual Leave', startDate: '2026-09-10', endDate: '2026-09-10', halfDay: false, totalDays: 1, status: 'REJECTED', employeeReason: 'Personal work, will share details separately', decisionReason: 'Critical release week — please reschedule if possible', decidedByName: 'Arjun Mehta', decidedAt: '2026-09-08T09:40:00' },
  { id: 'r10', leaveTypeCode: 'SICK', leaveTypeName: 'Sick Leave', startDate: '2026-08-29', endDate: '2026-08-30', halfDay: false, totalDays: 2, status: 'APPROVED', employeeReason: 'Viral fever, doctor advised rest', decisionReason: null, decidedByName: 'Priya Raghunathan', decidedAt: '2026-08-28T14:05:00' },
  { id: 'r9', leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', startDate: '2026-08-14', endDate: '2026-08-21', halfDay: false, totalDays: 6, status: 'APPROVED', employeeReason: 'Annual family vacation, pre-planned for Independence Day week', decisionReason: null, decidedByName: 'Priya Raghunathan', decidedAt: '2026-07-30T11:22:00' },
  { id: 'r8', leaveTypeCode: 'CASUAL', leaveTypeName: 'Casual Leave', startDate: '2026-08-03', endDate: '2026-08-03', halfDay: true, totalDays: 0.5, status: 'APPROVED', employeeReason: 'House shifting, half day needed', decisionReason: null, decidedByName: 'Priya Raghunathan', decidedAt: '2026-08-01T09:00:00' },
  { id: 'r7', leaveTypeCode: 'SICK', leaveTypeName: 'Sick Leave', startDate: '2026-07-22', endDate: '2026-07-22', halfDay: false, totalDays: 1, status: 'REJECTED', employeeReason: 'Not feeling well', decisionReason: 'Missing supporting note — please resubmit with details', decidedByName: 'Arjun Mehta', decidedAt: '2026-07-22T16:30:00' },
  { id: 'r6', leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', startDate: '2026-07-06', endDate: '2026-07-08', halfDay: false, totalDays: 3, status: 'APPROVED', employeeReason: 'Long weekend getaway', decisionReason: null, decidedByName: 'Priya Raghunathan', decidedAt: '2026-06-28T08:15:00' },
  { id: 'r5', leaveTypeCode: 'CASUAL', leaveTypeName: 'Casual Leave', startDate: '2026-06-19', endDate: '2026-06-19', halfDay: false, totalDays: 1, status: 'APPROVED', employeeReason: 'Bank work', decisionReason: null, decidedByName: 'Priya Raghunathan', decidedAt: '2026-06-18T10:00:00' },
  { id: 'r4', leaveTypeCode: 'SICK', leaveTypeName: 'Sick Leave', startDate: '2026-06-02', endDate: '2026-06-03', halfDay: false, totalDays: 2, status: 'APPROVED', employeeReason: 'Migraine, needed rest days', decisionReason: null, decidedByName: 'Priya Raghunathan', decidedAt: '2026-06-01T13:40:00' },
  { id: 'r3', leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', startDate: '2026-05-11', endDate: '2026-05-12', halfDay: false, totalDays: 2, status: 'APPROVED', employeeReason: 'Sibling\'s graduation ceremony', decisionReason: null, decidedByName: 'Priya Raghunathan', decidedAt: '2026-05-05T09:20:00' },
  { id: 'r2', leaveTypeCode: 'CASUAL', leaveTypeName: 'Casual Leave', startDate: '2026-04-23', endDate: '2026-04-23', halfDay: true, totalDays: 0.5, status: 'APPROVED', employeeReason: 'Vehicle service appointment', decisionReason: null, decidedByName: 'Priya Raghunathan', decidedAt: '2026-04-22T08:50:00' },
  { id: 'r1', leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', startDate: '2026-03-02', endDate: '2026-03-04', halfDay: false, totalDays: 3, status: 'APPROVED', employeeReason: 'Personal travel', decisionReason: null, decidedByName: 'Priya Raghunathan', decidedAt: '2026-02-24T12:00:00' },
];

// Fields match the real HolidayRow shape (api/holidays.ts) exactly — id, holidayName,
// holidayDate, locationName, active — nothing invented beyond what the API already returns.
const MOCK_HOLIDAYS: MockHoliday[] = [
  { id: 'h1', holidayName: 'Gandhi Jayanti', holidayDate: '2026-10-02', locationName: 'Bengaluru HQ', active: true },
  { id: 'h2', holidayName: 'Dussehra', holidayDate: '2026-10-20', locationName: 'Bengaluru HQ', active: true },
  { id: 'h3', holidayName: 'Diwali', holidayDate: '2026-11-08', locationName: 'Bengaluru HQ', active: true },
  { id: 'h4', holidayName: 'Diwali (Second Day)', holidayDate: '2026-11-09', locationName: 'Bengaluru HQ', active: true },
  { id: 'h5', holidayName: 'Guru Nanak Jayanti', holidayDate: '2026-11-24', locationName: 'Bengaluru HQ', active: true },
  { id: 'h6', holidayName: 'Christmas', holidayDate: '2026-12-25', locationName: 'Bengaluru HQ', active: true },
  { id: 'h7', holidayName: "New Year's Day", holidayDate: '2027-01-01', locationName: 'Bengaluru HQ', active: true },
  { id: 'h8', holidayName: 'Makar Sankranti', holidayDate: '2027-01-14', locationName: 'Bengaluru HQ', active: true },
  { id: 'h9', holidayName: 'Republic Day', holidayDate: '2027-01-26', locationName: 'Bengaluru HQ', active: true },
  { id: 'h10', holidayName: 'Independence Day', holidayDate: '2026-08-15', locationName: 'Bengaluru HQ', active: false },
];

const PAGE_SIZE = 5;
const BALANCE_ACCENTS = ['var(--lmp-brand)', 'var(--lmp-info)', 'var(--lmp-ok)', 'var(--lmp-warn)'];
const STATUS_COLOR: Record<MockStatus, string> = { PENDING: 'var(--lmp-warn)', APPROVED: 'var(--lmp-ok)', REJECTED: 'var(--lmp-risk)' };
const STATUS_LABEL: Record<MockStatus, string> = { PENDING: 'Pending', APPROVED: 'Approved', REJECTED: 'Rejected' };
const WEEKDAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--lmp-txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--lmp-line)', whiteSpace: 'nowrap' };
const tdStyle: React.CSSProperties = { padding: '12px 14px', fontSize: 13, color: 'var(--lmp-txt-mut)', borderBottom: '1px solid var(--lmp-line)', verticalAlign: 'middle' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--lmp-shell)', border: '1px solid var(--lmp-line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--lmp-txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--lmp-txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };

// ─────────────────────────────────────────────────────────────────────────
// Shell chrome replica — static visuals only, mirrors components/Shell.tsx +
// components/SidebarNav.tsx + lib/nav.config.ts (Employee role) so the
// content below previews "in place". No auth, no routing, no API calls.
// ─────────────────────────────────────────────────────────────────────────

function MockSidebarGroup({ icon: Icon, label, expanded, children }: {
  icon: React.ComponentType<{ size?: number }>; label: string; expanded?: boolean; children?: React.ReactNode;
}) {
  return (
    <div>
      <div className="lmp-nav-row" style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '9px 14px', fontSize: 13, color: '#C8CCD2', cursor: 'pointer' }}>
        <Icon size={15} />
        <span style={{ flex: 1 }}>{label}</span>
        <ChevronRight size={12} style={{ transform: expanded ? 'rotate(90deg)' : 'none', transition: 'transform .15s', color: '#6B7280' }} />
      </div>
      {expanded && <div>{children}</div>}
    </div>
  );
}

function MockSidebarLeaf({ icon: Icon, label, active, indent }: {
  icon: React.ComponentType<{ size?: number }>; label: string; active?: boolean; indent?: boolean;
}) {
  return (
    <div
      className="lmp-nav-row"
      style={{
        display: 'flex', alignItems: 'center', gap: 10, padding: indent ? '8px 14px 8px 30px' : '9px 14px',
        fontSize: 13, cursor: 'pointer', color: active ? '#fff' : '#C8CCD2',
        background: active ? 'rgba(228,55,61,.14)' : 'transparent',
        borderLeft: active ? '2px solid var(--lmp-brand)' : '2px solid transparent',
        fontWeight: active ? 600 : 400,
      }}
    >
      <Icon size={indent ? 13 : 15} />
      <span>{label}</span>
    </div>
  );
}

function MockShellChrome({ theme, onToggleTheme, children }: { theme: 'dark' | 'light'; onToggleTheme: () => void; children: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', minHeight: '100vh' }}>
      {/* Sidebar — mirrors Shell.tsx: 236px, #0B0C0F, 56px logo row, hierarchical nav */}
      <aside style={{ width: 236, flexShrink: 0, background: '#0B0C0F', borderRight: '1px solid #23262D', display: 'flex', flexDirection: 'column' }}>
        <div style={{ height: 56, padding: '0 14px', flexShrink: 0, borderBottom: '1px solid #23262D', display: 'flex', alignItems: 'center', gap: 10 }}>
          <img src={logoUrl} alt="" style={{ width: 30, height: 30, borderRadius: 8, flexShrink: 0 }} />
          <div>
            <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 13, color: '#E8EAED' }}>NForce OneHR</div>
            <div style={{ fontSize: 8, color: '#6B7280', letterSpacing: '.12em', textTransform: 'uppercase', marginTop: 1 }}>Employee Experience</div>
          </div>
        </div>

        <div style={{ flex: 1, padding: '8px 0', overflowY: 'auto' }}>
          <MockSidebarLeaf icon={Home} label="Home" />
          <MockSidebarGroup icon={Users} label="People" />
          <MockSidebarGroup icon={Clock} label="Time & Leave" expanded>
            <MockSidebarLeaf icon={Clock} label="My Attendance" indent />
            <MockSidebarLeaf icon={CalendarDays} label="Leave & Holidays" indent active />
          </MockSidebarGroup>
          <MockSidebarLeaf icon={HelpCircle} label="My Requests" />
          <MockSidebarGroup icon={Package} label="Employee Services" />
          <MockSidebarLeaf icon={GitBranch} label="Org Hierarchy" />
          <MockSidebarLeaf icon={FileText} label="My Documents & Policies" />
          <MockSidebarLeaf icon={HelpCircle} label="Help & Guidance" />
        </div>

        <div style={{ borderTop: '1px solid #23262D', padding: 10, display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{ width: 30, height: 30, borderRadius: '50%', background: '#B11116', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 11, fontWeight: 700, flexShrink: 0 }}>AR</div>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 12, color: '#E8EAED', fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>Anil Ramtenki</div>
            <div style={{ fontSize: 10, color: '#6B7280' }}>Employee</div>
          </div>
        </div>
      </aside>

      {/* Main area */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        <header style={{
          height: 56, background: 'linear-gradient(90deg, #050506 0%, #6B0C10 40%, #A01418 100%)',
          borderBottom: '1px solid rgba(228,55,61,.22)', position: 'sticky', top: 0, zIndex: 30,
          display: 'flex', alignItems: 'center', padding: '0 18px', gap: 10,
        }}>
          <button aria-label="Open navigation menu" className="lmp-hide-desktop" style={{ background: 'transparent', border: 'none', cursor: 'pointer', padding: 7, borderRadius: 6, color: '#E8EAED', display: 'none' }}>
            <Menu size={18} />
          </button>
          <div style={{ color: '#9BA1AC', fontSize: 13 }}>
            NForce OneHR / <b style={{ color: '#E8EAED', fontWeight: 600 }}>Leave &amp; Holidays</b>
          </div>
          <div style={{ flex: 1 }} />
          <div className="lmp-hide-mobile" style={{ maxWidth: 260, width: 260, background: '#1E2128', border: '1px solid #2A2E37', borderRadius: 8, padding: '7px 11px', display: 'flex', alignItems: 'center', gap: 8, color: '#6B7280', fontSize: 12 }}>
            <Search size={13} /> Search this workspace…
          </div>
          <button onClick={onToggleTheme} aria-label="Toggle theme" style={{ background: 'transparent', border: 'none', cursor: 'pointer', padding: 7, borderRadius: 6, display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: '#E8EAED' }}>
            {theme === 'dark' ? <Sun size={15} /> : <Moon size={15} />} {theme === 'dark' ? 'Light' : 'Dark'}
          </button>
          <div style={{ position: 'relative', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 7, borderRadius: 6, color: '#E8EAED' }}>
            <Bell size={15} />
            <span style={{ position: 'absolute', top: 3, right: 3, minWidth: 14, height: 14, borderRadius: 7, background: '#e4373d', color: '#fff', fontSize: 9, fontWeight: 700, display: 'grid', placeItems: 'center', padding: '0 3px' }}>2</span>
          </div>
          <div style={{ width: 32, height: 32, borderRadius: '50%', background: '#B11116', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 12, fontWeight: 700, border: '2px solid rgba(255,255,255,0.55)', boxSizing: 'border-box' }}>AR</div>
        </header>

        <main style={{ flex: 1, padding: 26, background: 'var(--lmp-shell)', color: 'var(--lmp-txt)' }}>
          {children}
        </main>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────
// Small shared pieces
// ─────────────────────────────────────────────────────────────────────────

function StatusBadge({ status }: { status: MockStatus }) {
  const color = STATUS_COLOR[status];
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11, fontWeight: 700, color,
      background: `color-mix(in srgb, ${color} 13%, var(--lmp-panel))`, border: `1px solid color-mix(in srgb, ${color} 30%, var(--lmp-line))`,
      borderRadius: 20, padding: '3px 9px 3px 7px', textTransform: 'uppercase', letterSpacing: '.03em',
    }}>
      <span className={status === 'PENDING' ? 'lmp-status-dot' : undefined} style={{ width: 6, height: 6, borderRadius: '50%', background: color, flexShrink: 0 }} />
      {STATUS_LABEL[status]}
    </span>
  );
}

function KpiTile({ icon: Icon, label, value, sub, accent }: {
  icon: React.ComponentType<{ size?: number; style?: React.CSSProperties }>;
  label: string; value: string; sub?: string; accent: string;
}) {
  return (
    <div className="lmp-enter" style={{ background: 'var(--lmp-panel)', border: '1px solid var(--lmp-line)', borderRadius: 10, padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--lmp-txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em' }}>{label}</span>
        <div style={{ width: 26, height: 26, borderRadius: 7, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, background: `color-mix(in srgb, ${accent} 14%, var(--lmp-raised))` }}>
          <Icon size={13} style={{ color: accent }} />
        </div>
      </div>
      <div style={{ fontSize: 22, fontWeight: 700, color: 'var(--lmp-txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1 }}>{value}</div>
      {sub && <div style={{ fontSize: 11, color: 'var(--lmp-txt-mut)' }}>{sub}</div>}
    </div>
  );
}

const RING_RADIUS = 30;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

function BalanceRingCard({ balance, accent }: { balance: MockBalance; accent: string }) {
  const total = balance.totalDays;
  const available = Math.max(0, balance.remainingDays);
  const consumed = Math.max(0, total - available);
  const isEmptyQuota = total <= 0;
  const availablePct = isEmptyQuota ? 0 : Math.min(100, Math.round((available / total) * 100));
  const ringOffset = RING_CIRCUMFERENCE * (1 - availablePct / 100);
  const ringColor = isEmptyQuota ? 'var(--lmp-line2)' : availablePct <= 15 ? 'var(--lmp-risk)' : accent;

  return (
    <div className="lmp-enter lmp-hover-card" style={{
      background: `linear-gradient(160deg, var(--lmp-panel) 0%, color-mix(in srgb, ${accent} 5%, var(--lmp-panel)) 100%)`,
      border: '1px solid var(--lmp-line)', borderTop: `2px solid ${ringColor}`, borderRadius: 10,
      padding: '16px 18px', display: 'flex', alignItems: 'center', gap: 16,
    }}>
      <div style={{ position: 'relative', width: 72, height: 72, flexShrink: 0 }}>
        <svg width={72} height={72} viewBox="0 0 72 72" style={{ transform: 'rotate(-90deg)' }}>
          <circle cx={36} cy={36} r={RING_RADIUS} fill="none" stroke="var(--lmp-raised2)" strokeWidth={7} />
          {!isEmptyQuota && (
            <circle className="lmp-ring-progress" cx={36} cy={36} r={RING_RADIUS} fill="none" stroke={ringColor} strokeWidth={7} strokeLinecap="round" strokeDasharray={RING_CIRCUMFERENCE} strokeDashoffset={ringOffset} />
          )}
        </svg>
        <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', pointerEvents: 'none' }}>
          <span style={{ fontSize: 16, fontWeight: 700, color: 'var(--lmp-txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1 }}>{available}</span>
          <span style={{ fontSize: 8.5, color: 'var(--lmp-txt-dim)', marginTop: 1 }}>of {total}d</span>
        </div>
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--lmp-txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 8, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={balance.leaveTypeName}>
          {balance.leaveTypeName}
        </div>
        {isEmptyQuota ? (
          <div style={{ fontSize: 12, color: 'var(--lmp-txt-dim)' }}>No quota assigned</div>
        ) : (
          <>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--lmp-txt-mut)', marginBottom: 4 }}>
              <span style={{ width: 7, height: 7, borderRadius: 2, background: ringColor, flexShrink: 0 }} />
              Available <span style={{ marginLeft: 'auto', color: 'var(--lmp-txt)', fontWeight: 700, fontFamily: '"JetBrains Mono", monospace' }}>{available}d</span>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--lmp-txt-mut)' }}>
              <span style={{ width: 7, height: 7, borderRadius: 2, background: 'var(--lmp-line2)', flexShrink: 0 }} />
              Consumed/Reserved <span style={{ marginLeft: 'auto', color: 'var(--lmp-txt)', fontWeight: 700, fontFamily: '"JetBrains Mono", monospace' }}>{consumed}d</span>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function RequestLeaveMockModal({ onClose, onCreated }: { onClose: () => void; onCreated: (r: MockRequest) => void }) {
  const today = new Date().toISOString().slice(0, 10);
  const [typeCode, setTypeCode] = useState(MOCK_TYPES[0].code);
  const [startDate, setStartDate] = useState(today);
  const [endDate, setEndDate] = useState(today);
  const [halfDay, setHalfDay] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const balance = MOCK_BALANCES.find(b => b.leaveTypeCode === typeCode);
  const effectiveEnd = halfDay ? startDate : endDate;
  const requestedDays = halfDay ? 0.5 : (new Date(effectiveEnd).getTime() - new Date(startDate).getTime()) / 86400000 + 1;
  const exceedsBalance = !!balance && Number.isFinite(requestedDays) && requestedDays > balance.remainingDays;

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!reason.trim()) { setError('A reason is required.'); return; }
    if (exceedsBalance && balance) { setError(`This request exceeds your available ${balance.leaveTypeName} balance of ${balance.remainingDays} days.`); return; }
    setError(null);
    setSubmitting(true);
    // Mock-only "submit" — simulates a brief network delay, then adds a local row. No API call.
    setTimeout(() => {
      const type = MOCK_TYPES.find(t => t.code === typeCode)!;
      onCreated({
        id: `mock-${Date.now()}`, leaveTypeCode: type.code, leaveTypeName: type.name,
        startDate, endDate: halfDay ? startDate : endDate, halfDay, totalDays: requestedDays,
        status: 'PENDING', employeeReason: reason.trim(), decisionReason: null, decidedByName: null, decidedAt: null,
      });
      setSubmitting(false);
      onClose();
    }, 500);
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 }}>
      <div style={{ background: 'var(--lmp-panel)', border: '1px solid var(--lmp-line)', borderRadius: 12, width: '94vw', maxWidth: 520, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--lmp-line)' }}>
          <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--lmp-txt)' }}>Request Leave</span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--lmp-txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignItems: 'center' }}><X size={16} /></button>
        </div>
        <form onSubmit={handleSubmit} className="lmp-2col-collapse" style={{ padding: 24, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          {error && <div style={{ gridColumn: '1/-1', color: 'var(--lmp-risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          <div style={{ gridColumn: '1/-1' }}>
            <label style={labelStyle}>Leave Type *</label>
            <select style={inputStyle} value={typeCode} onChange={e => setTypeCode(e.target.value)}>
              {MOCK_TYPES.map(t => <option key={t.code} value={t.code}>{t.name}</option>)}
            </select>
            {balance && (
              <div style={{ fontSize: 11.5, color: exceedsBalance ? 'var(--lmp-risk)' : 'var(--lmp-txt-dim)', marginTop: 5 }}>
                Available: {balance.remainingDays} day{balance.remainingDays === 1 ? '' : 's'}
                {exceedsBalance && ' — this request exceeds your available balance'}
              </div>
            )}
          </div>
          <div>
            <label style={labelStyle}>Start Date *</label>
            <input type="date" style={inputStyle} value={startDate} onChange={e => { setStartDate(e.target.value); if (halfDay) setEndDate(e.target.value); }} />
          </div>
          <div>
            <label style={labelStyle}>End Date *</label>
            <input type="date" style={inputStyle} value={halfDay ? startDate : endDate} disabled={halfDay} min={startDate} onChange={e => setEndDate(e.target.value)} />
          </div>
          <div style={{ gridColumn: '1/-1', display: 'flex', alignItems: 'center', gap: 8 }}>
            <input id="lmp-halfDay" type="checkbox" checked={halfDay} onChange={e => { setHalfDay(e.target.checked); if (e.target.checked) setEndDate(startDate); }} />
            <label htmlFor="lmp-halfDay" style={{ fontSize: 13, color: 'var(--lmp-txt-mut)' }}>Half day</label>
          </div>
          <div style={{ gridColumn: '1/-1' }}>
            <label style={labelStyle}>Reason *</label>
            <textarea style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }} value={reason} onChange={e => setReason(e.target.value)} placeholder="Reason for leave" />
          </div>
          <div style={{ gridColumn: '1/-1', display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--lmp-raised2)', color: 'var(--lmp-txt-mut)', border: '1px solid var(--lmp-line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={submitting || exceedsBalance} style={{ background: 'var(--lmp-brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: (submitting || exceedsBalance) ? 'not-allowed' : 'pointer', opacity: (submitting || exceedsBalance) ? 0.6 : 1 }}>{submitting ? 'Submitting…' : 'Submit Request'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────
// Leave tab
// ─────────────────────────────────────────────────────────────────────────

type Scenario = 'populated' | 'loading' | 'empty';

function LeaveTab({ scenario }: { scenario: Scenario }) {
  const [requests, setRequests] = useState<MockRequest[]>(MOCK_REQUESTS);
  const [showRequest, setShowRequest] = useState(false);
  const [typeFilter, setTypeFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);

  const effectiveRequests = scenario === 'empty' ? [] : requests;
  const effectiveBalances = scenario === 'empty' ? [] : MOCK_BALANCES;

  const filtered = useMemo(() => effectiveRequests.filter(r => {
    if (typeFilter && r.leaveTypeCode !== typeFilter) return false;
    if (statusFilter && r.status !== statusFilter) return false;
    if (search.trim() && !r.employeeReason.toLowerCase().includes(search.trim().toLowerCase()) && !r.leaveTypeName.toLowerCase().includes(search.trim().toLowerCase())) return false;
    return true;
  }), [effectiveRequests, typeFilter, statusFilter, search]);

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const pageSafe = Math.min(page, totalPages);
  const pageRows = filtered.slice((pageSafe - 1) * PAGE_SIZE, pageSafe * PAGE_SIZE);

  const todayIso = new Date().toISOString().slice(0, 10);
  const totalAvailable = useMemo(() => effectiveBalances.reduce((s, b) => s + Math.max(0, b.remainingDays), 0), [effectiveBalances]);
  const totalUsed = useMemo(() => effectiveBalances.reduce((s, b) => s + Math.max(0, b.usedDays), 0), [effectiveBalances]);
  const pendingCount = useMemo(() => effectiveRequests.filter(r => r.status === 'PENDING').length, [effectiveRequests]);
  const upcoming = useMemo(
    () => effectiveRequests.filter(r => r.status === 'APPROVED' && r.startDate >= todayIso).sort((a, b) => a.startDate.localeCompare(b.startDate))[0],
    [effectiveRequests, todayIso]
  );

  function updateFilterAndResetPage(setter: (v: string) => void, value: string) { setter(value); setPage(1); }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 20, flexWrap: 'wrap' }}>
        <div>
          <p style={{ fontSize: 12.5, color: 'var(--lmp-txt-mut)', margin: 0 }}>View your leave balance, request leave, and track approvals.</p>
        </div>
        <button onClick={() => setShowRequest(true)} style={{ display: 'flex', alignItems: 'center', gap: 7, flexShrink: 0, background: 'var(--lmp-brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '10px 18px', fontSize: 13, fontWeight: 600, cursor: 'pointer', boxShadow: '0 2px 10px rgba(177,17,22,.25)' }}>
          <CalendarPlus size={14} /> Request Leave
        </button>
      </div>

      {scenario === 'loading' ? (
        <div className="lmp-kpi-2x2" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 20 }}>
          {Array.from({ length: 4 }).map((_, i) => <div key={i} className="lmp-skeleton" style={{ height: 94, borderRadius: 10 }} />)}
        </div>
      ) : (
        <div className="lmp-kpi-2x2" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 20 }}>
          <KpiTile icon={Wallet} label="Available Leave" value={`${totalAvailable}d`} sub="Across all leave types" accent="var(--lmp-ok)" />
          <KpiTile icon={CheckCircle2} label="Used This Year" value={`${totalUsed}d`} sub="Approved & consumed" accent="var(--lmp-info)" />
          <KpiTile icon={Hourglass} label="Pending Requests" value={String(pendingCount)} sub={pendingCount === 0 ? 'All caught up' : 'Awaiting a decision'} accent="var(--lmp-warn)" />
          <KpiTile
            icon={CalendarClock} label="Upcoming Leave"
            value={upcoming ? new Date(upcoming.startDate + 'T00:00:00').toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) : '—'}
            sub={upcoming ? upcoming.leaveTypeName : 'Nothing scheduled'} accent="var(--lmp-brand)"
          />
        </div>
      )}

      <div style={{ marginBottom: 22 }}>
        <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--lmp-txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 10 }}>Leave Balances</div>
        {scenario === 'loading' ? (
          <div className="lmp-autofit-safe" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 12 }}>
            {Array.from({ length: 4 }).map((_, i) => <div key={i} className="lmp-skeleton" style={{ height: 104, borderRadius: 10 }} />)}
          </div>
        ) : effectiveBalances.length === 0 ? (
          <div style={{ background: 'var(--lmp-panel)', border: '1px solid var(--lmp-line)', borderRadius: 10, padding: 40, textAlign: 'center' }}>
            <Wallet size={26} style={{ color: 'var(--lmp-line2)', display: 'block', margin: '0 auto 10px' }} />
            <div style={{ fontSize: 13, color: 'var(--lmp-txt-mut)' }}>No leave balances configured yet.</div>
          </div>
        ) : (
          <div className="lmp-autofit-safe" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 12 }}>
            {effectiveBalances.map((b, i) => <BalanceRingCard key={b.leaveTypeCode} balance={b} accent={BALANCE_ACCENTS[i % BALANCE_ACCENTS.length]} />)}
          </div>
        )}
      </div>

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
        <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--lmp-txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em' }}>Leave History</div>
        {scenario !== 'loading' && effectiveRequests.length > 0 && (
          <div className="lmp-filter-bar" style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <div style={{ position: 'relative' }}>
              <Search size={13} style={{ position: 'absolute', left: 9, top: '50%', transform: 'translateY(-50%)', color: 'var(--lmp-txt-dim)' }} />
              <input value={search} onChange={e => { setSearch(e.target.value); setPage(1); }} placeholder="Search reason or type…" style={{ background: 'var(--lmp-raised)', border: '1px solid var(--lmp-line2)', borderRadius: 6, padding: '7px 10px 7px 28px', fontSize: 12.5, color: 'var(--lmp-txt)', width: 190 }} />
            </div>
            <select value={typeFilter} onChange={e => updateFilterAndResetPage(setTypeFilter, e.target.value)} style={{ background: 'var(--lmp-raised)', border: '1px solid var(--lmp-line2)', borderRadius: 6, padding: '7px 10px', fontSize: 12.5, color: 'var(--lmp-txt)' }}>
              <option value="">All Types</option>
              {MOCK_TYPES.map(t => <option key={t.code} value={t.code}>{t.name}</option>)}
            </select>
            <select value={statusFilter} onChange={e => updateFilterAndResetPage(setStatusFilter, e.target.value)} style={{ background: 'var(--lmp-raised)', border: '1px solid var(--lmp-line2)', borderRadius: 6, padding: '7px 10px', fontSize: 12.5, color: 'var(--lmp-txt)' }}>
              <option value="">All Statuses</option>
              <option value="PENDING">Pending</option>
              <option value="APPROVED">Approved</option>
              <option value="REJECTED">Rejected</option>
            </select>
          </div>
        )}
      </div>

      <div className="lmp-enter" style={{ background: 'var(--lmp-panel)', border: '1px solid var(--lmp-line)', borderRadius: 10, overflow: 'hidden' }}>
        {scenario === 'loading' ? (
          <div style={{ padding: 18, display: 'flex', flexDirection: 'column', gap: 10 }}>
            {Array.from({ length: 5 }).map((_, i) => <div key={i} className="lmp-skeleton" style={{ height: 38, borderRadius: 6 }} />)}
          </div>
        ) : effectiveRequests.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <CalendarPlus size={28} aria-hidden="true" style={{ color: 'var(--lmp-line2)', display: 'block', margin: '0 auto 10px' }} />
            <div style={{ fontSize: 15, color: 'var(--lmp-txt-mut)', marginBottom: 8 }}>No leave requests yet</div>
            <div style={{ fontSize: 13, color: 'var(--lmp-txt-dim)' }}>Click "Request Leave" to submit your first request.</div>
          </div>
        ) : filtered.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <Search size={26} aria-hidden="true" style={{ color: 'var(--lmp-line2)', display: 'block', margin: '0 auto 10px' }} />
            <div style={{ fontSize: 14, color: 'var(--lmp-txt-mut)', marginBottom: 6 }}>No requests match your filters</div>
            <div style={{ fontSize: 12.5, color: 'var(--lmp-txt-dim)' }}>Try clearing the search or filters above.</div>
          </div>
        ) : (
          <>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>{['Type', 'Dates', 'Days', 'Status', 'Reason', 'Decision'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
                </thead>
                <tbody>
                  {pageRows.map(r => (
                    <tr key={r.id} className="lmp-row">
                      <td style={{ ...tdStyle, color: 'var(--lmp-txt)', fontWeight: 600 }}>{r.leaveTypeName}</td>
                      <td style={tdStyle}>{r.startDate}{r.startDate !== r.endDate ? ` → ${r.endDate}` : ''}{r.halfDay ? ' (half day)' : ''}</td>
                      <td style={{ ...tdStyle, fontFamily: '"JetBrains Mono", monospace' }}>{r.totalDays}</td>
                      <td style={tdStyle}><StatusBadge status={r.status} /></td>
                      <td style={{ ...tdStyle, maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={r.employeeReason}>{r.employeeReason}</td>
                      <td style={tdStyle}>
                        {r.status === 'PENDING' && <span style={{ color: 'var(--lmp-txt-dim)' }}>Awaiting decision</span>}
                        {r.status === 'APPROVED' && <span>Approved by <b style={{ color: 'var(--lmp-txt)' }}>{r.decidedByName}</b>{r.decidedAt ? ` on ${new Date(r.decidedAt).toLocaleDateString()}` : ''}</span>}
                        {r.status === 'REJECTED' && <span>Rejected by <b style={{ color: 'var(--lmp-txt)' }}>{r.decidedByName}</b>: {r.decisionReason}</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 14px', borderTop: '1px solid var(--lmp-line)', fontSize: 12, color: 'var(--lmp-txt-dim)' }}>
              <span>Showing {(pageSafe - 1) * PAGE_SIZE + 1}–{Math.min(pageSafe * PAGE_SIZE, filtered.length)} of {filtered.length}</span>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <button onClick={() => setPage(p => Math.max(1, p - 1))} disabled={pageSafe <= 1} aria-label="Previous page" style={{ background: 'var(--lmp-raised)', border: '1px solid var(--lmp-line2)', borderRadius: 5, width: 24, height: 24, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: pageSafe <= 1 ? 'not-allowed' : 'pointer', color: 'var(--lmp-txt-mut)', opacity: pageSafe <= 1 ? 0.5 : 1 }}>
                  <ChevronLeft size={13} />
                </button>
                <span style={{ fontWeight: 600, color: 'var(--lmp-txt-mut)' }}>Page {pageSafe} of {totalPages}</span>
                <button onClick={() => setPage(p => Math.min(totalPages, p + 1))} disabled={pageSafe >= totalPages} aria-label="Next page" style={{ background: 'var(--lmp-raised)', border: '1px solid var(--lmp-line2)', borderRadius: 5, width: 24, height: 24, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: pageSafe >= totalPages ? 'not-allowed' : 'pointer', color: 'var(--lmp-txt-mut)', opacity: pageSafe >= totalPages ? 0.5 : 1 }}>
                  <ChevronRight size={13} />
                </button>
              </div>
            </div>
          </>
        )}
      </div>

      {showRequest && <RequestLeaveMockModal onClose={() => setShowRequest(false)} onCreated={(r) => { setRequests(prev => [r, ...prev]); setPage(1); }} />}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────
// Holidays tab
// ─────────────────────────────────────────────────────────────────────────

function daysInMonth(year: number, month: number) { return new Date(year, month + 1, 0).getDate(); }
function toISODate(year: number, month: number, day: number) { return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`; }

function HolidayMonthCalendar({ holidays }: { holidays: MockHoliday[] }) {
  const [viewDate, setViewDate] = useState(new Date(2026, 8, 1)); // Sept 2026, matching "today" used across this mock
  const today = new Date(2026, 8, 21);
  const year = viewDate.getFullYear();
  const month = viewDate.getMonth();
  const totalDays = daysInMonth(year, month);
  const firstWeekday = new Date(year, month, 1).getDay();
  const todayIso = toISODate(today.getFullYear(), today.getMonth(), today.getDate());

  const byDate = new Map<string, MockHoliday[]>();
  for (const h of holidays) {
    const list = byDate.get(h.holidayDate);
    if (list) list.push(h); else byDate.set(h.holidayDate, [h]);
  }

  const cells: Array<{ day: number; iso: string } | null> = [];
  for (let i = 0; i < firstWeekday; i++) cells.push(null);
  for (let d = 1; d <= totalDays; d++) cells.push({ day: d, iso: toISODate(year, month, d) });
  while (cells.length % 7 !== 0) cells.push(null);

  return (
    <div className="lmp-enter" style={{ background: 'var(--lmp-panel)', border: '1px solid var(--lmp-line)', borderRadius: 10, padding: 14 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
        <button onClick={() => setViewDate(new Date(year, month - 1, 1))} aria-label="Previous month" style={{ background: 'var(--lmp-raised)', border: '1px solid var(--lmp-line2)', borderRadius: 5, width: 24, height: 24, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--lmp-txt-mut)' }}>
          <ChevronLeft size={13} />
        </button>
        <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 13, color: 'var(--lmp-txt)' }}>{viewDate.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })}</span>
        <button onClick={() => setViewDate(new Date(year, month + 1, 1))} aria-label="Next month" style={{ background: 'var(--lmp-raised)', border: '1px solid var(--lmp-line2)', borderRadius: 5, width: 24, height: 24, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--lmp-txt-mut)' }}>
          <ChevronRight size={13} />
        </button>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))', gap: 4, marginBottom: 4 }}>
        {WEEKDAY_LABELS.map(d => <div key={d} style={{ textAlign: 'center', fontSize: 10, fontWeight: 700, color: 'var(--lmp-txt-dim)', textTransform: 'uppercase', padding: '2px 0' }}>{d[0]}</div>)}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))', gap: 4 }}>
        {cells.map((c, i) => {
          if (!c) return <div key={i} />;
          const dayHolidays = byDate.get(c.iso) ?? [];
          const hasHoliday = dayHolidays.length > 0;
          const isToday = c.iso === todayIso;
          return (
            <div key={i} title={dayHolidays.map(h => h.holidayName).join(', ') || undefined} style={{
              height: 62, minWidth: 0, overflow: 'hidden', boxSizing: 'border-box', borderRadius: 8, padding: '5px 4px',
              background: hasHoliday ? 'color-mix(in srgb, var(--lmp-brand) 14%, transparent)' : 'var(--lmp-raised)',
              border: isToday ? '2px solid var(--lmp-brand)' : hasHoliday ? '1.5px solid color-mix(in srgb, var(--lmp-brand) 55%, transparent)' : '1px solid var(--lmp-line)',
              display: 'flex', flexDirection: 'column', gap: 2,
            }}>
              <span style={{ fontSize: 11.5, fontWeight: isToday ? 700 : 500, color: hasHoliday ? 'var(--lmp-brand)' : 'var(--lmp-txt)' }}>{c.day}</span>
              {dayHolidays.slice(0, 1).map(h => (
                <span key={h.id} style={{ fontSize: 8.5, fontWeight: 700, color: '#fff', background: 'var(--lmp-brand)', borderRadius: 4, padding: '1px 4px', lineHeight: 1.3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{h.holidayName}</span>
              ))}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function UpcomingHolidayCard({ holiday, daysAway }: { holiday: MockHoliday; daysAway: number }) {
  const d = new Date(holiday.holidayDate + 'T00:00:00');
  return (
    <div className="lmp-enter lmp-hover-card" style={{
      background: 'linear-gradient(160deg, var(--lmp-panel) 0%, color-mix(in srgb, var(--lmp-brand) 5%, var(--lmp-panel)) 100%)',
      border: '1px solid var(--lmp-line)', borderTop: '2px solid var(--lmp-brand)', borderRadius: 10, padding: '14px 16px',
      display: 'flex', alignItems: 'center', gap: 14,
    }}>
      <div style={{
        width: 52, height: 52, borderRadius: 10, flexShrink: 0, background: 'color-mix(in srgb, var(--lmp-brand) 14%, var(--lmp-raised))',
        display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
      }}>
        <span style={{ fontSize: 9.5, fontWeight: 700, color: 'var(--lmp-brand)', textTransform: 'uppercase' }}>{d.toLocaleDateString(undefined, { month: 'short' })}</span>
        <span style={{ fontSize: 18, fontWeight: 700, color: 'var(--lmp-txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1 }}>{d.getDate()}</span>
      </div>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--lmp-txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={holiday.holidayName}>{holiday.holidayName}</div>
        <div style={{ fontSize: 11.5, color: 'var(--lmp-txt-mut)', marginTop: 2 }}>{d.toLocaleDateString(undefined, { weekday: 'long' })}</div>
        <div style={{ fontSize: 11, color: 'var(--lmp-txt-dim)', marginTop: 3 }}>{daysAway === 0 ? 'Today' : daysAway === 1 ? 'Tomorrow' : `In ${daysAway} days`}</div>
      </div>
    </div>
  );
}

function HolidaysTab({ scenario }: { scenario: Scenario }) {
  const holidays = scenario === 'empty' ? [] : MOCK_HOLIDAYS;
  const activeHolidays = useMemo(() => holidays.filter(h => h.active), [holidays]);
  const today = new Date(2026, 8, 21);
  const todayIso = today.toISOString().slice(0, 10);

  const upcoming = useMemo(() => {
    return activeHolidays
      .filter(h => h.holidayDate >= todayIso)
      .sort((a, b) => a.holidayDate.localeCompare(b.holidayDate))
      .slice(0, 4)
      .map(h => ({ holiday: h, daysAway: Math.round((new Date(h.holidayDate + 'T00:00:00').getTime() - today.getTime()) / 86400000) }));
  }, [activeHolidays, todayIso]);

  const sorted = useMemo(() => [...holidays].sort((a, b) => a.holidayDate.localeCompare(b.holidayDate)), [holidays]);

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 20, flexWrap: 'wrap' }}>
        <p style={{ fontSize: 12.5, color: 'var(--lmp-txt-mut)', margin: 0 }}>Company holidays for your work location — Bengaluru HQ.</p>
      </div>

      {scenario === 'loading' ? (
        <div className="lmp-autofit-safe" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12, marginBottom: 22 }}>
          {Array.from({ length: 4 }).map((_, i) => <div key={i} className="lmp-skeleton" style={{ height: 84, borderRadius: 10 }} />)}
        </div>
      ) : holidays.length === 0 ? (
        <div style={{ background: 'var(--lmp-panel)', border: '1px solid var(--lmp-line)', borderRadius: 10, padding: 48, textAlign: 'center', marginBottom: 22 }}>
          <CalendarDays size={28} style={{ color: 'var(--lmp-line2)', display: 'block', margin: '0 auto 10px' }} />
          <div style={{ fontSize: 14, color: 'var(--lmp-txt-mut)' }}>No holidays have been added for your location yet.</div>
        </div>
      ) : (
        <div style={{ marginBottom: 22 }}>
          <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--lmp-txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 10 }}>Upcoming Holidays</div>
          {upcoming.length === 0 ? (
            <div style={{ background: 'var(--lmp-panel)', border: '1px solid var(--lmp-line)', borderRadius: 10, padding: 28, textAlign: 'center', fontSize: 13, color: 'var(--lmp-txt-mut)' }}>
              No upcoming holidays scheduled right now.
            </div>
          ) : (
            <div className="lmp-autofit-safe" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12 }}>
              {upcoming.map(u => <UpcomingHolidayCard key={u.holiday.id} holiday={u.holiday} daysAway={u.daysAway} />)}
            </div>
          )}
        </div>
      )}

      {scenario !== 'loading' && holidays.length > 0 && (
        <div className="lmp-grid-side-collapse" style={{ display: 'grid', gridTemplateColumns: 'minmax(280px, 340px) 1fr', gap: 18, alignItems: 'start' }}>
          <HolidayMonthCalendar holidays={holidays} />

          <div className="lmp-enter" style={{ background: 'var(--lmp-panel)', border: '1px solid var(--lmp-line)', borderRadius: 10, overflow: 'hidden' }}>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>{['Holiday', 'Date', 'Day', 'Status'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
                </thead>
                <tbody>
                  {sorted.map(h => {
                    const d = new Date(h.holidayDate + 'T00:00:00');
                    return (
                      <tr key={h.id} className="lmp-row">
                        <td style={{ ...tdStyle, color: 'var(--lmp-txt)', fontWeight: 600, maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={h.holidayName}>{h.holidayName}</td>
                        <td style={tdStyle}>{d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })}</td>
                        <td style={tdStyle}>{d.toLocaleDateString(undefined, { weekday: 'long' })}</td>
                        <td style={tdStyle}>
                          <span style={{
                            display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 8px', borderRadius: 20, fontSize: 11, fontWeight: 600,
                            background: h.active ? 'rgba(47,182,124,.15)' : 'rgba(107,114,128,.15)', color: h.active ? 'var(--lmp-ok)' : 'var(--lmp-txt-dim)',
                          }}>
                            {h.active ? 'Active' : 'Inactive'}
                          </span>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────
// Page
// ─────────────────────────────────────────────────────────────────────────

export default function LeaveManagementPreviewPage() {
  const [scenario, setScenario] = useState<Scenario>('populated');
  const [tab, setTab] = useState<ModuleTab>('leave');
  const [theme, setTheme] = useState<'dark' | 'light'>('dark');

  return (
    <div className={`lmp-root${theme === 'light' ? ' lmp-theme-light' : ''}`}>
      <style>{LMP_STYLES}</style>

      <MockShellChrome theme={theme} onToggleTheme={() => setTheme(t => t === 'dark' ? 'light' : 'dark')}>
        {/* Mock banner + scenario toolbar — mock-only dev aids, not part of the proposed design */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', background: 'color-mix(in srgb, var(--lmp-info) 12%, var(--lmp-panel))', border: '1px solid color-mix(in srgb, var(--lmp-info) 35%, var(--lmp-line))', borderRadius: 8, padding: '9px 14px', fontSize: 12.5, color: 'var(--lmp-txt-mut)', marginBottom: 14 }}>
          <span style={{ fontWeight: 700, color: 'var(--lmp-info)', textTransform: 'uppercase', letterSpacing: '.04em', fontSize: 11 }}>Preview · Mock Data</span>
          <span>Sidebar/topbar shown here is a static visual replica for context — not the real navigation. Not connected to any real leave data or API.</span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 18, flexWrap: 'wrap' }}>
          <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--lmp-txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em' }}>View state:</span>
          {(['populated', 'loading', 'empty'] as Scenario[]).map(s => (
            <button key={s} onClick={() => setScenario(s)} style={{
              background: scenario === s ? 'var(--lmp-brand)' : 'var(--lmp-raised)', color: scenario === s ? '#fff' : 'var(--lmp-txt-mut)',
              border: '1px solid ' + (scenario === s ? 'var(--lmp-brand)' : 'var(--lmp-line2)'), borderRadius: 20, padding: '5px 13px', fontSize: 12, fontWeight: 600, cursor: 'pointer', textTransform: 'capitalize',
            }}>
              {s}
            </button>
          ))}
        </div>

        {/* Module header */}
        <div className="lmp-enter" style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 8 }}>
          <div style={{ width: 38, height: 38, borderRadius: 10, flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'linear-gradient(155deg, var(--lmp-brand) 0%, var(--lmp-brand-deep) 100%)', boxShadow: '0 4px 14px rgba(177,17,22,.28)' }}>
            <Sparkles size={17} color="#fff" />
          </div>
          <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--lmp-txt)', margin: 0 }}>Leave &amp; Holidays</h1>
        </div>

        {/* Sibling tabs — Leave / Holidays, same module */}
        <div style={{ display: 'flex', gap: 4, borderBottom: '1px solid var(--lmp-line)', marginBottom: 22 }}>
          {([
            { key: 'leave' as const, label: 'Leave' },
            { key: 'holidays' as const, label: 'Holidays' },
          ]).map(t => (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className="lmp-tab-btn"
              style={{
                background: 'none', border: 'none', cursor: 'pointer', padding: '10px 16px 12px',
                fontSize: 13.5, fontWeight: 600, color: tab === t.key ? 'var(--lmp-txt)' : 'var(--lmp-txt-mut)',
                borderBottom: tab === t.key ? '2px solid var(--lmp-brand)' : '2px solid transparent',
                marginBottom: -1,
              }}
            >
              {t.label}
            </button>
          ))}
        </div>

        {tab === 'leave' ? <LeaveTab scenario={scenario} /> : <HolidaysTab scenario={scenario} />}
      </MockShellChrome>
    </div>
  );
}

// Self-contained tokens + animations, namespaced `--lmp-*` / `.lmp-*` so this preview never
// depends on (or interferes with) the real app's global index.css.
const LMP_STYLES = `
  .lmp-root {
    --lmp-shell: #0E0F12; --lmp-panel: #16181D; --lmp-raised: #1E2128; --lmp-raised2: #262A32;
    --lmp-line: #2A2E37; --lmp-line2: #353A45; --lmp-brand: #B11116; --lmp-brand-deep: #7A0C10;
    --lmp-txt: #E8EAED; --lmp-txt-mut: #9BA1AC; --lmp-txt-dim: #6B7280;
    --lmp-ok: #2FB67C; --lmp-warn: #E0A93B; --lmp-risk: #E4373D; --lmp-info: #4C8DD6;
  }
  .lmp-root.lmp-theme-light {
    --lmp-shell: #F7F8FA; --lmp-panel: #FFFFFF; --lmp-raised: #FFFFFF; --lmp-raised2: #F1F3F5;
    --lmp-line: #E3E6EA; --lmp-line2: #D3D8DE; --lmp-brand: #B11116; --lmp-brand-deep: #7A0C10;
    --lmp-txt: #1A1D23; --lmp-txt-mut: #5A616B; --lmp-txt-dim: #8A909A;
    --lmp-ok: #1A7A52; --lmp-warn: #896010; --lmp-risk: #C81A1F; --lmp-info: #1A5FAA;
  }
  .lmp-root, .lmp-root input, .lmp-root select, .lmp-root textarea, .lmp-root button {
    font-family: 'Inter', system-ui, sans-serif;
  }
  @keyframes lmp-fade-up { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }
  @keyframes lmp-ring-in { from { stroke-dashoffset: 251; } to { stroke-dashoffset: var(--lmp-ring-offset, 0); } }
  @keyframes lmp-pulse { 0%, 100% { opacity: .55 } 50% { opacity: 1 } }
  @keyframes lmp-shimmer { 0% { background-position: -200px 0; } 100% { background-position: 200px 0; } }
  @media (prefers-reduced-motion: no-preference) {
    .lmp-enter { animation: lmp-fade-up 300ms cubic-bezier(.22,.61,.36,1) both; }
    .lmp-ring-progress { animation: lmp-ring-in 700ms cubic-bezier(.22,.61,.36,1) both; }
  }
  .lmp-ring-progress { transition: stroke-dashoffset .5s cubic-bezier(.22,.61,.36,1); }
  .lmp-status-dot { animation: lmp-pulse 2.2s ease-in-out infinite; }
  .lmp-hover-card { transition: border-color .15s, transform .15s; }
  .lmp-hover-card:hover { border-color: var(--lmp-line2); transform: translateY(-1px); }
  .lmp-row { transition: background-color .12s; }
  .lmp-row:hover { background: var(--lmp-raised); }
  .lmp-nav-row { transition: background-color .12s, color .12s; }
  .lmp-nav-row:hover { background: rgba(255,255,255,.04); color: #fff !important; }
  .lmp-tab-btn { transition: color .15s, border-color .15s; }
  .lmp-tab-btn:hover { color: var(--lmp-txt) !important; }
  .lmp-skeleton { background: linear-gradient(90deg, var(--lmp-raised) 25%, var(--lmp-raised2) 37%, var(--lmp-raised) 63%); background-size: 400px 100%; animation: lmp-shimmer 1.4s ease-in-out infinite; }
  @media (max-width: 767px) {
    .lmp-kpi-2x2 { grid-template-columns: repeat(2, 1fr) !important; }
    .lmp-autofit-safe { grid-template-columns: 1fr !important; }
    .lmp-2col-collapse { grid-template-columns: 1fr !important; }
    .lmp-filter-bar { width: 100%; }
    .lmp-filter-bar > * { flex: 1 1 auto; }
    .lmp-hide-mobile { display: none !important; }
  }
  @media (max-width: 1024px) {
    .lmp-2col-collapse { grid-template-columns: 1fr !important; }
    .lmp-grid-side-collapse { grid-template-columns: 1fr !important; }
  }
`;

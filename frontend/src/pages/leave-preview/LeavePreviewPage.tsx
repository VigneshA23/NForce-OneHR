/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW — NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  Role-specific visual preview of the production Leave & Holidays UI —
 *  LeaveHolidaysPage.tsx (tab switcher) rendering LeavePage.tsx (Leave tab)
 *  and HolidaysPage.tsx (Holidays tab). One reusable component, parameterized
 *  by `role`, rendering realistic mock data.
 *
 *  IMPORTANT: LeavePage.tsx is self-service ONLY for every role — Approve/
 *  Reject and Team/Organization "Pending Approvals"/"On Leave Today" KPIs
 *  live elsewhere in the real app (ApprovalsPage.tsx "Approval Center" and
 *  DashboardPage.tsx respectively), not on the Leave page itself. This
 *  preview deliberately does NOT show those here, so it doesn't imply
 *  functionality the real Leave page doesn't have. The only thing that
 *  varies by role on this page is Holiday administration (Add/Edit/Delete),
 *  matching HolidaysPage.tsx's own `isAdmin` gate.
 *
 *  - No network requests, no auth/session state, no import of the real
 *    LeavePage.tsx, HolidaysPage.tsx, api/leave.ts, api/holidays.ts, or Shell.tsx.
 *  - The sidebar/topbar below is a static, non-functional VISUAL REPLICA of
 *    the real <Shell> so each route can be reviewed "in place" without
 *    logging in. Nothing here edits Shell.tsx, SidebarNav.tsx, nav.config.ts,
 *    or the real Leave/Holidays pages.
 *  - "Request Leave"/"Add Holiday" mutate local component state only — never
 *    a real API.
 * ============================================================================
 */
import { useMemo, useState } from 'react';
import {
  Bell, CalendarClock, CalendarDays, CalendarPlus, CheckCircle2, ChevronLeft, ChevronRight,
  Clock, FileText, GitBranch, Home, Hourglass, HelpCircle, Menu, Package, Pencil, Plus, Search,
  Sparkles, Trash2, Users, Wallet, X,
} from 'lucide-react';
import logoUrl from '../../assets/nforce-logo.png';
import {
  MOCK_HOLIDAYS, MOCK_TYPES, OWN_BALANCES, ROLE_CONFIGS, ownRequestsFor,
  type MockHoliday, type MockRequest, type MockStatus, type PreviewRole, type RoleConfig,
} from './leavePreviewData';

const PAGE_SIZE = 5;
const BALANCE_ACCENTS = ['var(--pv-brand)', 'var(--pv-info)', 'var(--pv-ok)', 'var(--pv-warn)'];
const STATUS_COLOR: Record<MockStatus, string> = { PENDING: 'var(--pv-warn)', APPROVED: 'var(--pv-ok)', REJECTED: 'var(--pv-risk)' };
const STATUS_LABEL: Record<MockStatus, string> = { PENDING: 'Pending', APPROVED: 'Approved', REJECTED: 'Rejected' };
const WEEKDAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--pv-txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--pv-line)', whiteSpace: 'nowrap' };
const tdStyle: React.CSSProperties = { padding: '12px 14px', fontSize: 13, color: 'var(--pv-txt-mut)', borderBottom: '1px solid var(--pv-line)', verticalAlign: 'middle' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--pv-shell)', border: '1px solid var(--pv-line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--pv-txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--pv-txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };

// Sidebar sub-item labels under "Time & Leave" — copied verbatim per role from the real
// lib/nav.config.ts NAV table, purely for visual fidelity of the static shell replica.
const NAV_LABELS: Record<PreviewRole, { attendance: string; leave: string }> = {
  employee: { attendance: 'My Attendance', leave: 'Leave & Holidays' },
  manager: { attendance: 'Team Attendance', leave: 'Team Leave & Holidays' },
  hr: { attendance: 'Attendance Administration', leave: 'Leave Administration' },
  superAdmin: { attendance: 'Attendance Administration', leave: 'Leave & Holidays' },
};

// ─────────────────────────────────────────────────────────────────────────
// Shell chrome replica — static visuals only, mirrors components/Shell.tsx.
// ─────────────────────────────────────────────────────────────────────────

function MockSidebarLeaf({ icon: Icon, label, active, indent }: {
  icon: React.ComponentType<{ size?: number }>; label: string; active?: boolean; indent?: boolean;
}) {
  return (
    <div className="pv-nav-row" style={{
      display: 'flex', alignItems: 'center', gap: 10, padding: indent ? '8px 14px 8px 30px' : '9px 14px',
      fontSize: 13, cursor: 'pointer', color: active ? '#fff' : '#C8CCD2',
      background: active ? 'rgba(228,55,61,.14)' : 'transparent',
      borderLeft: active ? '2px solid var(--pv-brand)' : '2px solid transparent', fontWeight: active ? 600 : 400,
    }}>
      <Icon size={indent ? 13 : 15} />
      <span>{label}</span>
    </div>
  );
}

function MockSidebarGroup({ icon: Icon, label, expanded, children }: {
  icon: React.ComponentType<{ size?: number }>; label: string; expanded?: boolean; children?: React.ReactNode;
}) {
  return (
    <div>
      <div className="pv-nav-row" style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '9px 14px', fontSize: 13, color: '#C8CCD2', cursor: 'pointer' }}>
        <Icon size={15} />
        <span style={{ flex: 1 }}>{label}</span>
        <ChevronRight size={12} style={{ transform: expanded ? 'rotate(90deg)' : 'none', transition: 'transform .15s', color: '#6B7280' }} />
      </div>
      {expanded && <div>{children}</div>}
    </div>
  );
}

function toRoleTagline(role: RoleConfig['shellRoleLabel']): string {
  switch (role) {
    case 'Super Admin': return 'Super Admin Experience';
    case 'HR Admin': return 'HR Admin Experience';
    case 'Manager': return 'Manager Experience';
    default: return 'Employee Experience';
  }
}

function MockShellChrome({ role, children }: { role: RoleConfig; children: React.ReactNode }) {
  const navLabels = NAV_LABELS[role.role];
  const showEmployeeMaster = role.role === 'hr' || role.role === 'superAdmin';
  return (
    <div style={{ display: 'flex', minHeight: '100vh' }}>
      <aside className="pv-sidebar" style={{ width: 236, flexShrink: 0, background: '#0B0C0F', borderRight: '1px solid #23262D', display: 'flex', flexDirection: 'column' }}>
        <div style={{ height: 56, padding: '0 14px', flexShrink: 0, borderBottom: '1px solid #23262D', display: 'flex', alignItems: 'center', gap: 10 }}>
          <img src={logoUrl} alt="" style={{ width: 30, height: 30, borderRadius: 8, flexShrink: 0 }} />
          <div>
            <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 13, color: '#E8EAED' }}>NForce OneHR</div>
            <div style={{ fontSize: 8, color: '#6B7280', letterSpacing: '.12em', textTransform: 'uppercase', marginTop: 1 }}>{toRoleTagline(role.shellRoleLabel)}</div>
          </div>
        </div>

        <div style={{ flex: 1, padding: '8px 0', overflowY: 'auto' }}>
          <MockSidebarLeaf icon={Home} label="Home" />
          <MockSidebarGroup icon={Users} label="People" />
          <MockSidebarGroup icon={Clock} label="Time & Leave" expanded>
            <MockSidebarLeaf icon={Clock} label={navLabels.attendance} indent />
            <MockSidebarLeaf icon={CalendarDays} label={navLabels.leave} indent active />
          </MockSidebarGroup>
          {/* Real nav item for Manager/HR Admin/Super Admin (see lib/nav.config.ts) — unrelated
              to the Leave page itself; approvals live on this separate Approval Center page. */}
          {role.role !== 'employee' && <MockSidebarLeaf icon={FileText} label="Approval Center" />}
          <MockSidebarLeaf icon={HelpCircle} label={role.isAdmin ? 'HR Service Requests' : 'My Requests'} />
          {showEmployeeMaster && <MockSidebarLeaf icon={Users} label="Employee Master" />}
          <MockSidebarGroup icon={Package} label="Employee Services" />
          <MockSidebarLeaf icon={GitBranch} label="Org Hierarchy" />
          <MockSidebarLeaf icon={FileText} label="My Documents & Policies" />
          <MockSidebarLeaf icon={HelpCircle} label="Help & Guidance" />
        </div>

        <div style={{ borderTop: '1px solid #23262D', padding: 10, display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{ width: 30, height: 30, borderRadius: '50%', background: '#B11116', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 11, fontWeight: 700, flexShrink: 0 }}>{role.mockUserInitials}</div>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 12, color: '#E8EAED', fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{role.mockUserName}</div>
            <div style={{ fontSize: 10, color: '#6B7280' }}>{role.shellRoleLabel}</div>
          </div>
        </div>
      </aside>

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        <header style={{
          height: 56, background: 'linear-gradient(90deg, #050506 0%, #6B0C10 40%, #A01418 100%)',
          borderBottom: '1px solid rgba(228,55,61,.22)', position: 'sticky', top: 0, zIndex: 30,
          display: 'flex', alignItems: 'center', padding: '0 18px', gap: 10,
        }}>
          <button aria-label="Open navigation menu" className="pv-hide-desktop" style={{ background: 'transparent', border: 'none', cursor: 'pointer', padding: 7, borderRadius: 6, color: '#E8EAED', display: 'none' }}>
            <Menu size={18} />
          </button>
          <div style={{ color: '#9BA1AC', fontSize: 13 }}>
            NForce OneHR / <b style={{ color: '#E8EAED', fontWeight: 600 }}>{navLabels.leave}</b>
          </div>
          <div style={{ flex: 1 }} />
          <div className="pv-hide-mobile" style={{ maxWidth: 260, width: 260, background: '#1E2128', border: '1px solid #2A2E37', borderRadius: 8, padding: '7px 11px', display: 'flex', alignItems: 'center', gap: 8, color: '#6B7280', fontSize: 12 }}>
            <Search size={13} /> Search this workspace…
          </div>
          <div style={{ position: 'relative', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 7, borderRadius: 6, color: '#E8EAED' }}>
            <Bell size={15} />
            <span style={{ position: 'absolute', top: 3, right: 3, minWidth: 14, height: 14, borderRadius: 7, background: '#e4373d', color: '#fff', fontSize: 9, fontWeight: 700, display: 'grid', placeItems: 'center', padding: '0 3px' }}>2</span>
          </div>
          <div style={{ width: 32, height: 32, borderRadius: '50%', background: '#B11116', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 12, fontWeight: 700, border: '2px solid rgba(255,255,255,0.55)', boxSizing: 'border-box' }}>{role.mockUserInitials}</div>
        </header>

        <main style={{ flex: 1, padding: 26, background: 'var(--pv-shell)', color: 'var(--pv-txt)' }}>
          {children}
        </main>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────
// Shared presentational pieces — same visual language as production LeavePage.tsx
// ─────────────────────────────────────────────────────────────────────────

function StatusBadge({ status }: { status: MockStatus }) {
  const color = STATUS_COLOR[status];
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11, fontWeight: 700, color,
      background: `color-mix(in srgb, ${color} 13%, var(--pv-panel))`, border: `1px solid color-mix(in srgb, ${color} 30%, var(--pv-line))`,
      borderRadius: 20, padding: '3px 9px 3px 7px', textTransform: 'uppercase', letterSpacing: '.03em',
    }}>
      <span className={status === 'PENDING' ? 'pv-status-dot' : undefined} style={{ width: 6, height: 6, borderRadius: '50%', background: color, flexShrink: 0 }} />
      {STATUS_LABEL[status]}
    </span>
  );
}

function KpiTile({ icon: Icon, label, value, sub, accent }: {
  icon: React.ComponentType<{ size?: number; style?: React.CSSProperties }>;
  label: string; value: string; sub?: string; accent: string;
}) {
  return (
    <div className="pv-enter" style={{ background: 'var(--pv-panel)', border: '1px solid var(--pv-line)', borderRadius: 10, padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 10, minWidth: 0 }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8 }}>
        {/* flex + minWidth:0 lets a long label wrap onto 2 lines instead of forcing the
            card (and its grid column) wider — the card's own width is fixed by the grid,
            never by this label's content. */}
        <span style={{ flex: '1 1 auto', minWidth: 0, fontSize: 10.5, fontWeight: 700, color: 'var(--pv-txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', lineHeight: 1.35, wordBreak: 'break-word' }}>{label}</span>
        <div style={{ width: 26, height: 26, borderRadius: 7, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, background: `color-mix(in srgb, ${accent} 14%, var(--pv-raised))` }}>
          <Icon size={13} style={{ color: accent }} />
        </div>
      </div>
      <div style={{ fontSize: 22, fontWeight: 700, color: 'var(--pv-txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1 }}>{value}</div>
      {sub && <div style={{ fontSize: 11, color: 'var(--pv-txt-mut)' }}>{sub}</div>}
    </div>
  );
}

const RING_RADIUS = 30;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

function BalanceRingCard({ leaveTypeName, totalDays, remainingDays, accent }: { leaveTypeName: string; totalDays: number; remainingDays: number; accent: string }) {
  const available = Math.max(0, remainingDays);
  const consumed = Math.max(0, totalDays - available);
  const isEmptyQuota = totalDays <= 0;
  const availablePct = isEmptyQuota ? 0 : Math.min(100, Math.round((available / totalDays) * 100));
  const ringOffset = RING_CIRCUMFERENCE * (1 - availablePct / 100);
  const ringColor = isEmptyQuota ? 'var(--pv-line2)' : availablePct <= 15 ? 'var(--pv-risk)' : accent;

  return (
    <div className="pv-enter pv-hover-card" style={{
      background: `linear-gradient(160deg, var(--pv-panel) 0%, color-mix(in srgb, ${accent} 5%, var(--pv-panel)) 100%)`,
      border: '1px solid var(--pv-line)', borderTop: `2px solid ${ringColor}`, borderRadius: 10,
      padding: '16px 18px', display: 'flex', alignItems: 'center', gap: 16,
    }}>
      <div style={{ position: 'relative', width: 72, height: 72, flexShrink: 0 }}>
        <svg width={72} height={72} viewBox="0 0 72 72" style={{ transform: 'rotate(-90deg)' }}>
          <circle cx={36} cy={36} r={RING_RADIUS} fill="none" stroke="var(--pv-raised2)" strokeWidth={7} />
          {!isEmptyQuota && (
            <circle className="pv-ring-progress" cx={36} cy={36} r={RING_RADIUS} fill="none" stroke={ringColor} strokeWidth={7} strokeLinecap="round" strokeDasharray={RING_CIRCUMFERENCE} strokeDashoffset={ringOffset} />
          )}
        </svg>
        <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', pointerEvents: 'none' }}>
          <span style={{ fontSize: 16, fontWeight: 700, color: 'var(--pv-txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1 }}>{available}</span>
          <span style={{ fontSize: 8.5, color: 'var(--pv-txt-dim)', marginTop: 1 }}>of {totalDays}d</span>
        </div>
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--pv-txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 8, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={leaveTypeName}>
          {leaveTypeName}
        </div>
        {isEmptyQuota ? (
          <div style={{ fontSize: 12, color: 'var(--pv-txt-dim)' }}>No quota assigned</div>
        ) : (
          <>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--pv-txt-mut)', marginBottom: 4 }}>
              <span style={{ width: 7, height: 7, borderRadius: 2, background: ringColor, flexShrink: 0 }} />
              Available <span style={{ marginLeft: 'auto', color: 'var(--pv-txt)', fontWeight: 700, fontFamily: '"JetBrains Mono", monospace' }}>{available}d</span>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--pv-txt-mut)' }}>
              <span style={{ width: 7, height: 7, borderRadius: 2, background: 'var(--pv-line2)', flexShrink: 0 }} />
              Consumed/Reserved <span style={{ marginLeft: 'auto', color: 'var(--pv-txt)', fontWeight: 700, fontFamily: '"JetBrains Mono", monospace' }}>{consumed}d</span>
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

  const balance = OWN_BALANCES.find(b => b.leaveTypeCode === typeCode);
  const effectiveEnd = halfDay ? startDate : endDate;
  const requestedDays = halfDay ? 0.5 : (new Date(effectiveEnd).getTime() - new Date(startDate).getTime()) / 86400000 + 1;
  const exceedsBalance = !!balance && Number.isFinite(requestedDays) && requestedDays > balance.remainingDays;

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!reason.trim()) { setError('A reason is required.'); return; }
    if (exceedsBalance && balance) { setError(`This request exceeds your available ${balance.leaveTypeName} balance of ${balance.remainingDays} days.`); return; }
    setError(null);
    setSubmitting(true);
    setTimeout(() => {
      const type = MOCK_TYPES.find(t => t.code === typeCode)!;
      onCreated({
        id: `mock-${Date.now()}`, employeeUserId: 'me', employeeName: 'You', departmentName: '',
        leaveTypeCode: type.code, leaveTypeName: type.name, startDate, endDate: halfDay ? startDate : endDate,
        halfDay, totalDays: requestedDays, status: 'PENDING', employeeReason: reason.trim(),
        decisionReason: null, decidedByName: null, decidedAt: null,
      });
      setSubmitting(false);
      onClose();
    }, 400);
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 }}>
      <div style={{ background: 'var(--pv-panel)', border: '1px solid var(--pv-line)', borderRadius: 12, width: '94vw', maxWidth: 520, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--pv-line)' }}>
          <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--pv-txt)' }}>Request Leave</span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--pv-txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignItems: 'center' }}><X size={16} /></button>
        </div>
        <form onSubmit={handleSubmit} className="pv-2col-collapse" style={{ padding: 24, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          {error && <div style={{ gridColumn: '1/-1', color: 'var(--pv-risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          <div style={{ gridColumn: '1/-1' }}>
            <label style={labelStyle}>Leave Type *</label>
            <select style={inputStyle} value={typeCode} onChange={e => setTypeCode(e.target.value)}>
              {MOCK_TYPES.map(t => <option key={t.code} value={t.code}>{t.name}</option>)}
            </select>
            {balance && (
              <div style={{ fontSize: 11.5, color: exceedsBalance ? 'var(--pv-risk)' : 'var(--pv-txt-dim)', marginTop: 5 }}>
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
            <input id="pv-halfDay" type="checkbox" checked={halfDay} onChange={e => { setHalfDay(e.target.checked); if (e.target.checked) setEndDate(startDate); }} />
            <label htmlFor="pv-halfDay" style={{ fontSize: 13, color: 'var(--pv-txt-mut)' }}>Half day</label>
          </div>
          <div style={{ gridColumn: '1/-1' }}>
            <label style={labelStyle}>Reason *</label>
            <textarea style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }} value={reason} onChange={e => setReason(e.target.value)} placeholder="Reason for leave" />
          </div>
          <div style={{ gridColumn: '1/-1', display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--pv-raised2)', color: 'var(--pv-txt-mut)', border: '1px solid var(--pv-line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={submitting || exceedsBalance} style={{ background: 'var(--pv-brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: (submitting || exceedsBalance) ? 'not-allowed' : 'pointer', opacity: (submitting || exceedsBalance) ? 0.6 : 1 }}>{submitting ? 'Submitting…' : 'Submit Request'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────
// Holidays
// ─────────────────────────────────────────────────────────────────────────

function daysInMonth(year: number, month: number) { return new Date(year, month + 1, 0).getDate(); }
function toISODate(year: number, month: number, day: number) { return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`; }

const HOLIDAY_STATUS_BADGE = (active: boolean) => (
  <span style={{
    display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 8px', borderRadius: 20, fontSize: 11, fontWeight: 600,
    background: active ? 'rgba(47,182,124,.15)' : 'rgba(107,114,128,.15)', color: active ? 'var(--pv-ok)' : 'var(--pv-txt-dim)',
  }}>
    {active ? 'Active' : 'Inactive'}
  </span>
);

function UpcomingHolidayCard({ holiday, daysAway }: { holiday: MockHoliday; daysAway: number }) {
  const d = new Date(holiday.holidayDate + 'T00:00:00');
  return (
    <div className="pv-enter pv-hover-card" style={{
      background: 'linear-gradient(160deg, var(--pv-panel) 0%, color-mix(in srgb, var(--pv-brand) 5%, var(--pv-panel)) 100%)',
      border: '1px solid var(--pv-line)', borderTop: '2px solid var(--pv-brand)', borderRadius: 10, padding: '14px 16px',
      display: 'flex', alignItems: 'center', gap: 14,
    }}>
      <div style={{ width: 52, height: 52, borderRadius: 10, flexShrink: 0, background: 'color-mix(in srgb, var(--pv-brand) 14%, var(--pv-raised))', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
        <span style={{ fontSize: 9.5, fontWeight: 700, color: 'var(--pv-brand)', textTransform: 'uppercase' }}>{d.toLocaleDateString(undefined, { month: 'short' })}</span>
        <span style={{ fontSize: 18, fontWeight: 700, color: 'var(--pv-txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1 }}>{d.getDate()}</span>
      </div>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--pv-txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={holiday.holidayName}>{holiday.holidayName}</div>
        <div style={{ fontSize: 11.5, color: 'var(--pv-txt-mut)', marginTop: 2 }}>{d.toLocaleDateString(undefined, { weekday: 'long' })}</div>
        <div style={{ fontSize: 11, color: 'var(--pv-txt-dim)', marginTop: 3 }}>{daysAway === 0 ? 'Today' : daysAway === 1 ? 'Tomorrow' : `In ${daysAway} days`}</div>
      </div>
    </div>
  );
}

function HolidayMonthCalendar({ holidays }: { holidays: MockHoliday[] }) {
  const [viewDate, setViewDate] = useState(new Date(2026, 8, 1));
  const today = new Date(2026, 8, 22);
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
    <div className="pv-enter" style={{ background: 'var(--pv-panel)', border: '1px solid var(--pv-line)', borderRadius: 10, padding: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
        <button onClick={() => setViewDate(new Date(year, month - 1, 1))} aria-label="Previous month" style={{ background: 'var(--pv-raised)', border: '1px solid var(--pv-line2)', borderRadius: 5, width: 22, height: 22, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--pv-txt-mut)' }}>
          <ChevronLeft size={12} />
        </button>
        <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 12, color: 'var(--pv-txt)' }}>{viewDate.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })}</span>
        <button onClick={() => setViewDate(new Date(year, month + 1, 1))} aria-label="Next month" style={{ background: 'var(--pv-raised)', border: '1px solid var(--pv-line2)', borderRadius: 5, width: 22, height: 22, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--pv-txt-mut)' }}>
          <ChevronRight size={12} />
        </button>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))', gap: 3, marginBottom: 4 }}>
        {WEEKDAY_LABELS.map(d => <div key={d} style={{ textAlign: 'center', fontSize: 10, fontWeight: 700, color: 'var(--pv-txt-dim)', textTransform: 'uppercase', padding: '2px 0' }}>{d[0]}</div>)}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))', gap: 3 }}>
        {cells.map((c, i) => {
          if (!c) return <div key={i} />;
          const dayHolidays = byDate.get(c.iso) ?? [];
          const hasHoliday = dayHolidays.length > 0;
          const isToday = c.iso === todayIso;
          return (
            <div key={i} title={dayHolidays.map(h => h.holidayName).join(', ') || undefined} style={{
              height: 68, minWidth: 0, overflow: 'hidden', boxSizing: 'border-box', borderRadius: 8, padding: '6px 5px',
              background: hasHoliday ? 'color-mix(in srgb, var(--pv-brand) 14%, transparent)' : 'var(--pv-raised)',
              border: isToday ? '2px solid var(--pv-brand)' : hasHoliday ? '1.5px solid color-mix(in srgb, var(--pv-brand) 55%, transparent)' : '1px solid var(--pv-line)',
              display: 'flex', flexDirection: 'column', gap: 3,
            }}>
              <span style={{ fontSize: 12, fontWeight: isToday ? 700 : 500, color: hasHoliday ? 'var(--pv-brand)' : 'var(--pv-txt)' }}>{c.day}</span>
              {dayHolidays.slice(0, 1).map(h => (
                <span key={h.id} style={{ fontSize: 9, fontWeight: 700, color: '#fff', background: 'var(--pv-brand)', borderRadius: 5, padding: '2px 5px', lineHeight: 1.4, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{h.holidayName}</span>
              ))}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function AddHolidayMockModal({ editing, onClose, onSaved }: { editing?: MockHoliday; onClose: () => void; onSaved: (h: MockHoliday) => void }) {
  const [holidayName, setHolidayName] = useState(editing?.holidayName ?? '');
  const [holidayDate, setHolidayDate] = useState(editing?.holidayDate ?? '');
  const [locationName, setLocationName] = useState(editing?.locationName ?? 'Bengaluru HQ');
  const [error, setError] = useState('');

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const name = holidayName.trim();
    if (!name) { setError('Holiday name is required'); return; }
    if (!holidayDate) { setError('Date is required'); return; }
    onSaved({ id: editing?.id ?? `mock-h-${Date.now()}`, holidayName: name, holidayDate, locationName, active: editing?.active ?? true });
    onClose();
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 }}>
      <div style={{ background: 'var(--pv-panel)', border: '1px solid var(--pv-line)', borderRadius: 12, width: '94vw', maxWidth: 480, boxShadow: '0 24px 64px rgba(0,0,0,.55)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--pv-line)' }}>
          <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--pv-txt)' }}>{editing ? 'Edit Holiday' : 'Add Holiday'}</span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--pv-txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignItems: 'center' }}><X size={16} /></button>
        </div>
        {error && <div style={{ margin: '16px 20px 0', color: 'var(--pv-risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
        <form onSubmit={submit} style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div><label style={labelStyle}>Holiday Name *</label><input style={inputStyle} value={holidayName} onChange={e => setHolidayName(e.target.value)} placeholder="e.g. Diwali" /></div>
          <div><label style={labelStyle}>Date *</label><input type="date" style={inputStyle} value={holidayDate} onChange={e => setHolidayDate(e.target.value)} /></div>
          <div>
            <label style={labelStyle}>Location *</label>
            <select style={inputStyle} value={locationName} onChange={e => setLocationName(e.target.value)}>
              <option>Bengaluru HQ</option>
              <option>Hyderabad Office</option>
            </select>
          </div>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--pv-raised2)', color: 'var(--pv-txt-mut)', border: '1px solid var(--pv-line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" style={{ background: 'var(--pv-brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}>Save</button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────
// Page
// ─────────────────────────────────────────────────────────────────────────

type TabKey = 'leave' | 'holidays';

export default function LeavePreviewPage({ role: roleKey }: { role: PreviewRole }) {
  const role = ROLE_CONFIGS[roleKey];
  const [tab, setTab] = useState<TabKey>('leave');
  const [showRequest, setShowRequest] = useState(false);
  const [myRequests, setMyRequests] = useState<MockRequest[]>(() => ownRequestsFor(role));
  const [holidays, setHolidays] = useState<MockHoliday[]>(MOCK_HOLIDAYS);
  const [showAddHoliday, setShowAddHoliday] = useState(false);
  const [editingHoliday, setEditingHoliday] = useState<MockHoliday | null>(null);

  const [typeFilter, setTypeFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);

  const totalAvailable = useMemo(() => OWN_BALANCES.reduce((s, b) => s + Math.max(0, b.remainingDays), 0), []);
  const totalUsed = useMemo(() => OWN_BALANCES.reduce((s, b) => s + Math.max(0, b.usedDays), 0), []);
  const myPendingCount = useMemo(() => myRequests.filter(r => r.status === 'PENDING').length, [myRequests]);
  const todayIso = '2026-09-22';
  const upcoming = useMemo(
    () => myRequests.filter(r => r.status === 'APPROVED' && r.startDate >= todayIso).sort((a, b) => a.startDate.localeCompare(b.startDate))[0],
    [myRequests]
  );

  const filteredRequests = useMemo(() => myRequests.filter(r => {
    if (typeFilter && r.leaveTypeCode !== typeFilter) return false;
    if (statusFilter && r.status !== statusFilter) return false;
    if (search.trim() && !r.employeeReason.toLowerCase().includes(search.trim().toLowerCase()) && !r.leaveTypeName.toLowerCase().includes(search.trim().toLowerCase())) return false;
    return true;
  }), [myRequests, typeFilter, statusFilter, search]);

  const totalPages = Math.max(1, Math.ceil(filteredRequests.length / PAGE_SIZE));
  const pageSafe = Math.min(page, totalPages);
  const pageRows = filteredRequests.slice((pageSafe - 1) * PAGE_SIZE, pageSafe * PAGE_SIZE);

  function updateFilterAndResetPage(setter: (v: string) => void, value: string) { setter(value); setPage(1); }
  function handleDeleteHoliday(h: MockHoliday) { setHolidays(prev => prev.filter(x => x.id !== h.id)); }
  function handleSavedHoliday(h: MockHoliday) {
    setHolidays(prev => (prev.some(x => x.id === h.id) ? prev.map(x => x.id === h.id ? h : x) : [h, ...prev]));
  }

  const upcomingHolidays = useMemo(() => {
    const todayMs = new Date(todayIso + 'T00:00:00').getTime();
    return holidays.filter(h => h.active && h.holidayDate >= todayIso).sort((a, b) => a.holidayDate.localeCompare(b.holidayDate)).slice(0, 4)
      .map(h => ({ holiday: h, daysAway: Math.round((new Date(h.holidayDate + 'T00:00:00').getTime() - todayMs) / 86400000) }));
  }, [holidays]);

  return (
    <div className="pv-root">
      <style>{PV_STYLES}</style>
      <MockShellChrome role={role}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', background: 'color-mix(in srgb, var(--pv-info) 12%, var(--pv-panel))', border: '1px solid color-mix(in srgb, var(--pv-info) 35%, var(--pv-line))', borderRadius: 8, padding: '9px 14px', fontSize: 12.5, color: 'var(--pv-txt-mut)', marginBottom: 18 }}>
          <span style={{ fontWeight: 700, color: 'var(--pv-info)', textTransform: 'uppercase', letterSpacing: '.04em', fontSize: 11 }}>Preview · {role.shellRoleLabel} · Mock Data</span>
          <span>Sidebar/topbar shown here is a static visual replica — not connected to any real leave data or API.</span>
        </div>

        <div className="pv-enter pv-header" style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 22,
          background: 'var(--pv-panel)', border: '1px solid var(--pv-line)', borderRadius: 12, padding: '18px 22px',
          backgroundImage: 'linear-gradient(120deg, color-mix(in srgb, var(--pv-brand) 6%, var(--pv-panel)) 0%, var(--pv-panel) 55%)',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 14, minWidth: 0 }}>
            <div style={{ width: 42, height: 42, borderRadius: 10, flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'linear-gradient(155deg, var(--pv-brand) 0%, var(--pv-brand-deep) 100%)', boxShadow: '0 4px 14px rgba(177,17,22,.28)' }}>
              <Sparkles size={19} color="#fff" />
            </div>
            <div style={{ minWidth: 0 }}>
              <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--pv-txt)', margin: 0 }}>Leave &amp; Holidays</h1>
              <p style={{ fontSize: 12.5, color: 'var(--pv-txt-mut)', marginTop: 3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>View your balance, request leave, and track approvals.</p>
            </div>
          </div>
          <button onClick={() => setShowRequest(true)} style={{ display: 'flex', alignItems: 'center', gap: 7, flexShrink: 0, background: 'var(--pv-brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '10px 18px', fontSize: 13, fontWeight: 600, cursor: 'pointer', boxShadow: '0 2px 10px rgba(177,17,22,.25)' }}>
            <CalendarPlus size={14} /> Request Leave
          </button>
        </div>

        <div style={{ display: 'flex', gap: 4, borderBottom: '1px solid var(--pv-line)', marginBottom: 22 }}>
          {([{ key: 'leave' as const, label: 'Leave' }, { key: 'holidays' as const, label: 'Holidays' }]).map(t => (
            <button key={t.key} onClick={() => setTab(t.key)} style={{
              background: 'none', border: 'none', cursor: 'pointer', padding: '10px 16px 12px',
              fontSize: 13.5, fontWeight: 600, color: tab === t.key ? 'var(--pv-txt)' : 'var(--pv-txt-mut)',
              borderBottom: tab === t.key ? '2px solid var(--pv-brand)' : '2px solid transparent', marginBottom: -1,
            }}>
              {t.label}
            </button>
          ))}
        </div>

        {tab === 'leave' ? (
          <div>
            <div className="pv-kpi-2x2" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 20 }}>
              <KpiTile icon={Wallet} label="Available Leave" value={`${totalAvailable}d`} sub="Across all leave types" accent="var(--pv-ok)" />
              <KpiTile icon={CheckCircle2} label="Used This Year" value={`${totalUsed}d`} sub="Approved & consumed" accent="var(--pv-info)" />
              <KpiTile icon={Hourglass} label="My Pending Requests" value={String(myPendingCount)} sub={myPendingCount === 0 ? 'All caught up' : 'Awaiting a decision'} accent="var(--pv-warn)" />
              <KpiTile
                icon={CalendarClock} label="Upcoming Leave"
                value={upcoming ? new Date(upcoming.startDate + 'T00:00:00').toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) : '—'}
                sub={upcoming ? upcoming.leaveTypeName : 'Nothing scheduled'} accent="var(--pv-brand)"
              />
            </div>

            <div style={{ marginBottom: 22 }}>
              <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--pv-txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 10 }}>My Leave Balances</div>
              <div className="pv-autofit-safe" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 12 }}>
                {OWN_BALANCES.map((b, i) => <BalanceRingCard key={b.leaveTypeCode} leaveTypeName={b.leaveTypeName} totalDays={b.totalDays} remainingDays={b.remainingDays} accent={BALANCE_ACCENTS[i % BALANCE_ACCENTS.length]} />)}
              </div>
            </div>

            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
              <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--pv-txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em' }}>My Leave History</div>
              {myRequests.length > 0 && (
                <div className="pv-filter-bar" style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                  <div style={{ position: 'relative' }}>
                    <Search size={13} style={{ position: 'absolute', left: 9, top: '50%', transform: 'translateY(-50%)', color: 'var(--pv-txt-dim)' }} />
                    <input value={search} onChange={e => { setSearch(e.target.value); setPage(1); }} placeholder="Search reason or type…" style={{ background: 'var(--pv-raised)', border: '1px solid var(--pv-line2)', borderRadius: 6, padding: '7px 10px 7px 28px', fontSize: 12.5, color: 'var(--pv-txt)', width: 190 }} />
                  </div>
                  <select value={typeFilter} onChange={e => updateFilterAndResetPage(setTypeFilter, e.target.value)} style={{ background: 'var(--pv-raised)', border: '1px solid var(--pv-line2)', borderRadius: 6, padding: '7px 10px', fontSize: 12.5, color: 'var(--pv-txt)' }}>
                    <option value="">All Types</option>
                    {MOCK_TYPES.map(t => <option key={t.code} value={t.code}>{t.name}</option>)}
                  </select>
                  <select value={statusFilter} onChange={e => updateFilterAndResetPage(setStatusFilter, e.target.value)} style={{ background: 'var(--pv-raised)', border: '1px solid var(--pv-line2)', borderRadius: 6, padding: '7px 10px', fontSize: 12.5, color: 'var(--pv-txt)' }}>
                    <option value="">All Statuses</option>
                    <option value="PENDING">Pending</option>
                    <option value="APPROVED">Approved</option>
                    <option value="REJECTED">Rejected</option>
                  </select>
                </div>
              )}
            </div>

            <div className="pv-enter" style={{ background: 'var(--pv-panel)', border: '1px solid var(--pv-line)', borderRadius: 10, overflow: 'hidden' }}>
              {myRequests.length === 0 ? (
                <div style={{ padding: 48, textAlign: 'center' }}>
                  <CalendarPlus size={28} style={{ color: 'var(--pv-line2)', display: 'block', margin: '0 auto 10px' }} />
                  <div style={{ fontSize: 15, color: 'var(--pv-txt-mut)', marginBottom: 8 }}>No leave requests yet</div>
                  <div style={{ fontSize: 13, color: 'var(--pv-txt-dim)' }}>Click "Request Leave" to submit your first request.</div>
                </div>
              ) : filteredRequests.length === 0 ? (
                <div style={{ padding: 40, textAlign: 'center' }}>
                  <Search size={24} style={{ color: 'var(--pv-line2)', display: 'block', margin: '0 auto 10px' }} />
                  <div style={{ fontSize: 13, color: 'var(--pv-txt-mut)' }}>No requests match your filters.</div>
                </div>
              ) : (
                <>
                  <div style={{ overflowX: 'auto' }}>
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                      <thead><tr>{['Type', 'Dates', 'Days', 'Status', 'Reason', 'Decision'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
                      <tbody>
                        {pageRows.map(r => (
                          <tr key={r.id} className="pv-row">
                            <td style={{ ...tdStyle, color: 'var(--pv-txt)', fontWeight: 600 }}>{r.leaveTypeName}</td>
                            <td style={tdStyle}>{r.startDate}{r.startDate !== r.endDate ? ` → ${r.endDate}` : ''}{r.halfDay ? ' (half day)' : ''}</td>
                            <td style={{ ...tdStyle, fontFamily: '"JetBrains Mono", monospace' }}>{r.totalDays}</td>
                            <td style={tdStyle}><StatusBadge status={r.status} /></td>
                            <td style={{ ...tdStyle, maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={r.employeeReason}>{r.employeeReason}</td>
                            <td style={tdStyle}>
                              {r.status === 'PENDING' && <span style={{ color: 'var(--pv-txt-dim)' }}>Awaiting decision</span>}
                              {r.status === 'APPROVED' && <span>Approved by <b style={{ color: 'var(--pv-txt)' }}>{r.decidedByName}</b>{r.decidedAt ? ` on ${new Date(r.decidedAt).toLocaleDateString()}` : ''}</span>}
                              {r.status === 'REJECTED' && <span>Rejected by <b style={{ color: 'var(--pv-txt)' }}>{r.decidedByName}</b>: {r.decisionReason}</span>}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 14px', borderTop: '1px solid var(--pv-line)', fontSize: 12, color: 'var(--pv-txt-dim)' }}>
                    <span>Showing {(pageSafe - 1) * PAGE_SIZE + 1}–{Math.min(pageSafe * PAGE_SIZE, filteredRequests.length)} of {filteredRequests.length}</span>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                      <button onClick={() => setPage(p => Math.max(1, p - 1))} disabled={pageSafe <= 1} aria-label="Previous page" style={{ background: 'var(--pv-raised)', border: '1px solid var(--pv-line2)', borderRadius: 5, width: 24, height: 24, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: pageSafe <= 1 ? 'not-allowed' : 'pointer', color: 'var(--pv-txt-mut)', opacity: pageSafe <= 1 ? 0.5 : 1 }}><ChevronLeft size={13} /></button>
                      <span style={{ fontWeight: 600, color: 'var(--pv-txt-mut)' }}>Page {pageSafe} of {totalPages}</span>
                      <button onClick={() => setPage(p => Math.min(totalPages, p + 1))} disabled={pageSafe >= totalPages} aria-label="Next page" style={{ background: 'var(--pv-raised)', border: '1px solid var(--pv-line2)', borderRadius: 5, width: 24, height: 24, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: pageSafe >= totalPages ? 'not-allowed' : 'pointer', color: 'var(--pv-txt-mut)', opacity: pageSafe >= totalPages ? 0.5 : 1 }}><ChevronRight size={13} /></button>
                    </div>
                  </div>
                </>
              )}
            </div>
          </div>
        ) : (
          <div>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14, flexWrap: 'wrap', gap: 10 }}>
              <p style={{ fontSize: 12.5, color: 'var(--pv-txt-mut)', margin: 0 }}>
                {role.isAdmin ? 'Holidays across the company, by location.' : 'Holidays for your work location — Bengaluru HQ.'}
              </p>
              {role.isAdmin && (
                <button onClick={() => setShowAddHoliday(true)} style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'var(--pv-brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '8px 14px', fontSize: 12.5, fontWeight: 600, cursor: 'pointer' }}>
                  <Plus size={13} /> Add Holiday
                </button>
              )}
            </div>

            {upcomingHolidays.length > 0 && (
              <div style={{ marginBottom: 20 }}>
                <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--pv-txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 10 }}>Upcoming Holidays</div>
                <div className="pv-autofit-safe" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12 }}>
                  {upcomingHolidays.map(u => <UpcomingHolidayCard key={u.holiday.id} holiday={u.holiday} daysAway={u.daysAway} />)}
                </div>
              </div>
            )}

            <div className="pv-grid-side-collapse" style={{ display: 'grid', gridTemplateColumns: 'minmax(280px, 340px) 1fr', gap: 18, alignItems: 'start' }}>
              <HolidayMonthCalendar holidays={holidays} />
              <div className="pv-enter" style={{ background: 'var(--pv-panel)', border: '1px solid var(--pv-line)', borderRadius: 10, overflow: 'hidden' }}>
                <div style={{ overflowX: 'auto' }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead><tr>{(role.isAdmin ? ['Holiday', 'Date', 'Location', 'Status', 'Actions'] : ['Holiday', 'Date', 'Status']).map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
                    <tbody>
                      {[...holidays].sort((a, b) => a.holidayDate.localeCompare(b.holidayDate)).map(h => {
                        const d = new Date(h.holidayDate + 'T00:00:00');
                        return (
                          <tr key={h.id} className="pv-row">
                            <td style={{ ...tdStyle, color: 'var(--pv-txt)', fontWeight: 600, maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={h.holidayName}>{h.holidayName}</td>
                            <td style={tdStyle}>{d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })}</td>
                            {role.isAdmin && <td style={tdStyle}>{h.locationName}</td>}
                            <td style={tdStyle}>{HOLIDAY_STATUS_BADGE(h.active)}</td>
                            {role.isAdmin && (
                              <td style={tdStyle}>
                                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                                  <button onClick={() => setEditingHoliday(h)} aria-label={`Edit ${h.holidayName}`} style={{ background: 'var(--pv-raised)', border: '1px solid var(--pv-line2)', borderRadius: 6, width: 26, height: 26, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--pv-txt-mut)' }}><Pencil size={12} /></button>
                                  <button onClick={() => handleDeleteHoliday(h)} aria-label={`Delete ${h.holidayName}`} style={{ background: 'var(--pv-raised)', border: '1px solid var(--pv-line2)', borderRadius: 6, width: 26, height: 26, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--pv-risk)' }}><Trash2 size={12} /></button>
                                </div>
                              </td>
                            )}
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          </div>
        )}
      </MockShellChrome>

      {showRequest && <RequestLeaveMockModal onClose={() => setShowRequest(false)} onCreated={(r) => { setMyRequests(prev => [r, ...prev]); setPage(1); }} />}
      {(showAddHoliday || editingHoliday) && (
        <AddHolidayMockModal editing={editingHoliday ?? undefined} onClose={() => { setShowAddHoliday(false); setEditingHoliday(null); }} onSaved={handleSavedHoliday} />
      )}
    </div>
  );
}

const PV_STYLES = `
  .pv-root {
    --pv-shell: #0E0F12; --pv-panel: #16181D; --pv-raised: #1E2128; --pv-raised2: #262A32;
    --pv-line: #2A2E37; --pv-line2: #353A45; --pv-brand: #B11116; --pv-brand-deep: #7A0C10;
    --pv-txt: #E8EAED; --pv-txt-mut: #9BA1AC; --pv-txt-dim: #6B7280;
    --pv-ok: #2FB67C; --pv-warn: #E0A93B; --pv-risk: #E4373D; --pv-info: #4C8DD6;
  }
  .pv-root, .pv-root input, .pv-root select, .pv-root textarea, .pv-root button { font-family: 'Inter', system-ui, sans-serif; }
  @keyframes pv-fade-up { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }
  @keyframes pv-ring-in { from { stroke-dashoffset: 251; } to { stroke-dashoffset: var(--pv-ring-offset, 0); } }
  @keyframes pv-pulse { 0%, 100% { opacity: .55 } 50% { opacity: 1 } }
  @media (prefers-reduced-motion: no-preference) {
    .pv-enter { animation: pv-fade-up 300ms cubic-bezier(.22,.61,.36,1) both; }
    .pv-ring-progress { animation: pv-ring-in 700ms cubic-bezier(.22,.61,.36,1) both; }
  }
  .pv-ring-progress { transition: stroke-dashoffset .5s cubic-bezier(.22,.61,.36,1); }
  .pv-status-dot { animation: pv-pulse 2.2s ease-in-out infinite; }
  .pv-hover-card { transition: border-color .15s, transform .15s; }
  .pv-hover-card:hover { border-color: var(--pv-line2); transform: translateY(-1px); }
  .pv-row { transition: background-color .12s; }
  .pv-row:hover { background: var(--pv-raised); }
  .pv-nav-row { transition: background-color .12s, color .12s; }
  .pv-nav-row:hover { background: rgba(255,255,255,.04); color: #fff !important; }
  @media (max-width: 767px) {
    .pv-kpi-2x2 { grid-template-columns: repeat(2, 1fr) !important; }
    .pv-autofit-safe { grid-template-columns: 1fr !important; }
    .pv-2col-collapse { grid-template-columns: 1fr !important; }
    .pv-header { flex-direction: column !important; align-items: stretch !important; }
    .pv-header button { width: 100%; justify-content: center; }
    .pv-filter-bar { width: 100%; }
    .pv-filter-bar > * { flex: 1 1 auto; }
    .pv-hide-mobile { display: none !important; }
    .pv-sidebar { display: none !important; }
    .pv-hide-desktop { display: flex !important; }
  }
  @media (max-width: 1024px) {
    .pv-2col-collapse { grid-template-columns: 1fr !important; }
    .pv-grid-side-collapse { grid-template-columns: 1fr !important; }
  }
`;

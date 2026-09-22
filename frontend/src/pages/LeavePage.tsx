import { useEffect, useMemo, useRef, useState } from 'react';
import {
  CalendarClock, CalendarDays, CalendarPlus, CheckCircle2, ChevronLeft, ChevronRight,
  Hourglass, Pencil, Plus, Search, Sparkles, Trash2, Users, Wallet, X,
} from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { leaveApi, type LeaveType, type LeaveBalance, type LeaveRequestRecord, type SubmitLeaveRequestPayload } from '../api/leave';
import { holidaysApi, type HolidayRow } from '../api/holidays';
import { orgApi, type LocationRow } from '../api/org';
import { useToast } from '../context/ToastContext';
import { toShellRole } from '../lib/nav.config';

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 520, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const tdStyle: React.CSSProperties = { padding: '12px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
// Holiday table only — compact, center-aligned, distinct from the Leave Requests table above.
const holidayThStyle: React.CSSProperties = { ...thStyle, textAlign: 'center', padding: '8px 10px' };
const holidayTdStyle: React.CSSProperties = { ...tdStyle, textAlign: 'center', padding: '8px 10px' };
// Mirrors CreateHolidayRequest's @Pattern on the backend: must contain at least
// one actual letter (rejects emoji-only, symbol-only, and digit-only input),
// otherwise letters (Unicode-aware — accented characters like "Deepāvali" are
// \p{L}), digits ("Independence Day 2026"), spaces, apostrophes, and hyphens
// ("New Year's Day", "Eid-ul-Fitr").
const HOLIDAY_NAME_PATTERN = /^(?=.*[\p{L}])[\p{L}\p{N} '-]+$/u;
const HOLIDAY_NAME_MAX_LENGTH = 100;
const PAGE_SIZE = 5;
const BALANCE_ACCENTS = ['var(--brand)', 'var(--info)', 'var(--ok)', 'var(--warn)'];

function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
      <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>{title}</span>
      <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignItems: 'center' }}><X size={16} /></button>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><label style={labelStyle}>{label}</label>{children}</div>;
}

// Mirrors LeaveService#isAnnualBalanceLeaveType — Annual/Sick/Casual are independently
// selectable in the dropdown below, but the balance API returns only ONE row (Annual) for the
// whole group, so a Sick/Casual selection must still resolve to that same row.
const ANNUAL_BALANCE_GROUP_CODES = new Set(['ANNUAL', 'SICK', 'CASUAL']);
function isAnnualBalanceLeaveType(code: string): boolean {
  return ANNUAL_BALANCE_GROUP_CODES.has(code);
}

const STATUS_COLOR: Record<string, string> = { PENDING: 'var(--warn)', APPROVED: 'var(--ok)', REJECTED: 'var(--risk)' };
const STATUS_LABEL: Record<string, string> = { PENDING: 'Pending', APPROVED: 'Approved', REJECTED: 'Rejected' };

function StatusBadge({ status }: { status: string }) {
  const color = STATUS_COLOR[status] ?? 'var(--txt-dim)';
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11, fontWeight: 700, color,
      background: `color-mix(in srgb, ${color} 13%, var(--raised))`, border: `1px solid color-mix(in srgb, ${color} 30%, var(--line))`,
      borderRadius: 20, padding: '3px 9px 3px 7px', textTransform: 'uppercase', letterSpacing: '.03em',
    }}>
      <span className={status === 'PENDING' ? 'nf-hero-status-dot' : undefined} style={{ width: 6, height: 6, borderRadius: '50%', background: color, flexShrink: 0 }} />
      {STATUS_LABEL[status] ?? status}
    </span>
  );
}

function KpiTile({ icon: Icon, label, value, sub, accent }: {
  icon: React.ComponentType<{ size?: number; style?: React.CSSProperties }>;
  label: string; value: string; sub?: string; accent: string;
}) {
  return (
    <div className="nf-section-enter" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em' }}>{label}</span>
        <div style={{ width: 26, height: 26, borderRadius: 7, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, background: `color-mix(in srgb, ${accent} 14%, var(--raised))` }}>
          <Icon size={13} style={{ color: accent }} />
        </div>
      </div>
      <div style={{ fontSize: 22, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1 }}>{value}</div>
      {sub && <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>{sub}</div>}
    </div>
  );
}

// Radial-progress balance card — draws an SVG circle via stroke-dasharray/offset, reusing the
// same .nf-ring-progress/.nf-section-enter entrance animation already defined in index.css for
// the Attendance "Today" ring. The backend (LeaveService#availableBalance) is the sole source of
// truth for both numbers — this component only visualizes remainingDays/totalDays as returned by
// GET /api/leave/balances; it never recomputes or re-derives the balance itself.
const RING_RADIUS = 30;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

function LeaveBalanceRingCard({ balance, accent }: { balance: LeaveBalance; accent: string }) {
  const total = Number(balance.totalDays);
  const available = Math.max(0, Number(balance.remainingDays));
  const consumed = Math.max(0, total - available);
  const isEmptyQuota = total <= 0;
  const availablePct = isEmptyQuota ? 0 : Math.min(100, Math.round((available / total) * 100));
  const ringOffset = RING_CIRCUMFERENCE * (1 - availablePct / 100);
  const ringColor = isEmptyQuota ? 'var(--line2)' : availablePct <= 15 ? 'var(--risk)' : accent;

  return (
    <div className="nf-section-enter nf-leave-balance-card" style={{
      background: `linear-gradient(160deg, var(--panel) 0%, color-mix(in srgb, ${accent} 5%, var(--panel)) 100%)`,
      border: '1px solid var(--line)', borderTop: `2px solid ${ringColor}`, borderRadius: 10,
      padding: '16px 18px', display: 'flex', alignItems: 'center', gap: 16,
    }}>
      <div style={{ position: 'relative', width: 72, height: 72, flexShrink: 0 }}>
        <svg width={72} height={72} viewBox="0 0 72 72" style={{ transform: 'rotate(-90deg)' }}>
          <circle cx={36} cy={36} r={RING_RADIUS} fill="none" stroke="var(--raised2)" strokeWidth={7} />
          {!isEmptyQuota && (
            <circle className="nf-ring-progress" cx={36} cy={36} r={RING_RADIUS} fill="none" stroke={ringColor} strokeWidth={7} strokeLinecap="round" strokeDasharray={RING_CIRCUMFERENCE} strokeDashoffset={ringOffset} />
          )}
        </svg>
        <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', pointerEvents: 'none' }}>
          <span style={{ fontSize: 16, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1 }}>{available}</span>
          <span style={{ fontSize: 8.5, color: 'var(--txt-dim)', marginTop: 1 }}>of {total}d</span>
        </div>
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 8, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={balance.leaveTypeName}>
          {balance.leaveTypeName}
        </div>
        {isEmptyQuota ? (
          <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>No quota assigned</div>
        ) : (
          <>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--txt-mut)', marginBottom: 4 }}>
              <span style={{ width: 7, height: 7, borderRadius: 2, background: ringColor, flexShrink: 0 }} />
              Available <span style={{ marginLeft: 'auto', color: 'var(--txt)', fontWeight: 700, fontFamily: '"JetBrains Mono", monospace' }}>{available}d</span>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--txt-mut)' }}>
              <span style={{ width: 7, height: 7, borderRadius: 2, background: 'var(--line2)', flexShrink: 0 }} />
              Consumed/Reserved <span style={{ marginLeft: 'auto', color: 'var(--txt)', fontWeight: 700, fontFamily: '"JetBrains Mono", monospace' }}>{consumed}d</span>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function RequestLeaveModal({ types, balances, onClose, onCreated, token }: { types: LeaveType[]; balances: LeaveBalance[]; onClose: () => void; onCreated: (r: LeaveRequestRecord) => void; token: string }) {
  const { showToast } = useToast();
  const today = new Date().toISOString().slice(0, 10);
  const [form, setForm] = useState<SubmitLeaveRequestPayload>({ leaveTypeCode: types[0]?.code ?? '', startDate: today, endDate: today, halfDay: false, reason: '' });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Early-UX only, mirroring the backend's own day-count formula (LeaveService#submitRequest) —
  // the backend independently re-validates against the same status-aware balance regardless of
  // what's computed here, so this can never be relied on to enforce the limit by itself.
  const selectedBalance = balances.find(b =>
    b.leaveTypeCode === form.leaveTypeCode
    || (isAnnualBalanceLeaveType(form.leaveTypeCode) && isAnnualBalanceLeaveType(b.leaveTypeCode)));
  const effectiveEndDate = form.halfDay ? form.startDate : form.endDate;
  const requestedDays = form.halfDay
    ? 0.5
    : (new Date(effectiveEndDate).getTime() - new Date(form.startDate).getTime()) / 86400000 + 1;
  const exceedsBalance = !!selectedBalance && Number.isFinite(requestedDays) && requestedDays > selectedBalance.remainingDays;

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!form.reason.trim()) { setError('A reason is required.'); return; }
    if (exceedsBalance && selectedBalance) {
      setError(`Leave request exceeds your available ${selectedBalance.leaveTypeName} balance of ${selectedBalance.remainingDays} days.`);
      return;
    }
    setSubmitting(true); setError(null);
    try {
      const created = await leaveApi.submit({ ...form, endDate: form.halfDay ? form.startDate : form.endDate }, token);
      onCreated(created);
      showToast('success', 'Leave request submitted');
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Submit failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <ModalHeader title="Request Leave" onClose={onClose} />
        <form onSubmit={handleSubmit} className="nf-grid-2col-collapse" style={{ padding: 24, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          {error && <div style={{ gridColumn: '1/-1', color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Leave Type *">
              <select style={inputStyle} value={form.leaveTypeCode} onChange={e => setForm(f => ({ ...f, leaveTypeCode: e.target.value }))}>
                {types.map(t => <option key={t.code} value={t.code}>{t.name}</option>)}
              </select>
            </Field>
            {selectedBalance && (
              <div style={{ fontSize: 11.5, color: exceedsBalance ? 'var(--risk)' : 'var(--txt-dim)', marginTop: 5 }}>
                Available: {selectedBalance.remainingDays} day{selectedBalance.remainingDays === 1 ? '' : 's'}
                {exceedsBalance && ' — this request exceeds your available balance'}
              </div>
            )}
          </div>
          <Field label="Start Date *">
            <input type="date" style={inputStyle} value={form.startDate} onChange={e => setForm(f => ({ ...f, startDate: e.target.value, endDate: f.halfDay ? e.target.value : f.endDate }))} />
          </Field>
          <Field label="End Date *">
            <input type="date" style={inputStyle} value={form.halfDay ? form.startDate : form.endDate} disabled={form.halfDay} min={form.startDate} onChange={e => setForm(f => ({ ...f, endDate: e.target.value }))} />
          </Field>
          <div style={{ gridColumn: '1/-1', display: 'flex', alignItems: 'center', gap: 8 }}>
            <input id="halfDay" type="checkbox" checked={form.halfDay} onChange={e => setForm(f => ({ ...f, halfDay: e.target.checked, endDate: e.target.checked ? f.startDate : f.endDate }))} />
            <label htmlFor="halfDay" style={{ fontSize: 13, color: 'var(--txt-mut)' }}>Half day</label>
          </div>
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Reason *">
              <textarea style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }} value={form.reason} onChange={e => setForm(f => ({ ...f, reason: e.target.value }))} placeholder="Reason for leave" />
            </Field>
          </div>
          <div style={{ gridColumn: '1/-1', display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={submitting || exceedsBalance} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: (submitting || exceedsBalance) ? 'not-allowed' : 'pointer', opacity: (submitting || exceedsBalance) ? 0.6 : 1 }}>{submitting ? 'Submitting…' : 'Submit Request'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────
// Approvals — Manager sees only their own direct reports' pending requests;
// HR Admin/Super Admin see all (server-side scoping, see LeaveService#listPendingApprovals).
// This panel only renders for those roles (see canApprove in the page component below).
// ─────────────────────────────────────────────────────────────────────────

function ApprovalRow({ request, token, onDecided }: { request: LeaveRequestRecord; token: string; onDecided: () => void }) {
  const { showToast } = useToast();
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  async function handleApprove() {
    setBusy(true);
    try {
      await leaveApi.approve(request.id, token);
      showToast('success', `Approved ${request.employeeName}'s request`);
      onDecided();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Approve failed');
    } finally { setBusy(false); }
  }

  async function handleReject() {
    if (!reason.trim()) return;
    setBusy(true);
    try {
      await leaveApi.reject(request.id, reason.trim(), token);
      showToast('success', `Rejected ${request.employeeName}'s request`);
      onDecided();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Reject failed');
    } finally { setBusy(false); }
  }

  return (
    <tr className="nf-leave-row">
      <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{request.employeeName}</td>
      <td style={{ ...tdStyle, color: 'var(--txt)' }}>{request.leaveTypeName}</td>
      <td style={tdStyle}>{request.startDate}{request.startDate !== request.endDate ? ` → ${request.endDate}` : ''}{request.halfDay ? ' (half day)' : ''}</td>
      <td style={{ ...tdStyle, fontFamily: '"JetBrains Mono", monospace' }}>{request.totalDays}</td>
      <td style={{ ...tdStyle, maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={request.employeeReason}>{request.employeeReason}</td>
      <td style={tdStyle}>
        {rejecting ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <input autoFocus value={reason} onChange={e => setReason(e.target.value)} placeholder="Reason for rejection" style={{ ...inputStyle, width: 160, padding: '6px 9px', fontSize: 12 }} />
            <button onClick={handleReject} disabled={busy || !reason.trim()} style={{ background: 'var(--risk)', color: '#fff', border: 'none', borderRadius: 6, padding: '6px 10px', fontSize: 12, fontWeight: 600, cursor: (busy || !reason.trim()) ? 'not-allowed' : 'pointer', opacity: (busy || !reason.trim()) ? 0.6 : 1 }}>Confirm</button>
            <button onClick={() => { setRejecting(false); setReason(''); }} disabled={busy} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 10px', fontSize: 12, cursor: 'pointer' }}>Cancel</button>
          </div>
        ) : (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <button onClick={handleApprove} disabled={busy} style={{ background: 'var(--ok)', color: '#fff', border: 'none', borderRadius: 6, padding: '6px 12px', fontSize: 12, fontWeight: 600, cursor: busy ? 'not-allowed' : 'pointer', opacity: busy ? 0.6 : 1 }}>Approve</button>
            <button onClick={() => setRejecting(true)} disabled={busy} style={{ background: 'var(--raised)', color: 'var(--risk)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 12px', fontSize: 12, fontWeight: 600, cursor: busy ? 'not-allowed' : 'pointer', opacity: busy ? 0.6 : 1 }}>Reject</button>
          </div>
        )}
      </td>
    </tr>
  );
}

// ─────────────────────────────────────────────────────────────────────────
// Holidays
// ─────────────────────────────────────────────────────────────────────────

const HOLIDAY_STATUS_BADGE = (active: boolean) => (
  <span style={{
    display: 'inline-flex', alignItems: 'center', gap: 4,
    padding: '2px 8px', borderRadius: 20, fontSize: 11, fontWeight: 600,
    background: active ? 'rgba(47,182,124,.15)' : 'rgba(107,114,128,.15)',
    color: active ? 'var(--ok)' : 'var(--txt-dim)',
  }}>
    {active ? 'Active' : 'Inactive'}
  </span>
);

function formatHolidayDate(iso: string) {
  const d = new Date(iso + 'T00:00:00');
  return d.toLocaleDateString(undefined, { weekday: 'short', year: 'numeric', month: 'short', day: 'numeric' });
}

function daysInMonth(year: number, month: number) {
  return new Date(year, month + 1, 0).getDate();
}

function toISODate(year: number, month: number, day: number) {
  return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

const WEEKDAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

function UpcomingHolidayCard({ holiday, daysAway }: { holiday: HolidayRow; daysAway: number }) {
  const d = new Date(holiday.holidayDate + 'T00:00:00');
  return (
    <div className="nf-section-enter nf-leave-balance-card" style={{
      background: 'linear-gradient(160deg, var(--panel) 0%, color-mix(in srgb, var(--brand) 5%, var(--panel)) 100%)',
      border: '1px solid var(--line)', borderTop: '2px solid var(--brand)', borderRadius: 10, padding: '14px 16px',
      display: 'flex', alignItems: 'center', gap: 14,
    }}>
      <div style={{ width: 52, height: 52, borderRadius: 10, flexShrink: 0, background: 'color-mix(in srgb, var(--brand) 14%, var(--raised))', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
        <span style={{ fontSize: 9.5, fontWeight: 700, color: 'var(--brand)', textTransform: 'uppercase' }}>{d.toLocaleDateString(undefined, { month: 'short' })}</span>
        <span style={{ fontSize: 18, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1 }}>{d.getDate()}</span>
      </div>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={holiday.holidayName}>{holiday.holidayName}</div>
        <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 2 }}>{d.toLocaleDateString(undefined, { weekday: 'long' })}</div>
        <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 3 }}>{daysAway === 0 ? 'Today' : daysAway === 1 ? 'Tomorrow' : `In ${daysAway} days`}</div>
      </div>
    </div>
  );
}

function HolidayMonthCalendar({ holidays }: { holidays: HolidayRow[] }) {
  const today = new Date();
  const [viewDate, setViewDate] = useState(new Date(today.getFullYear(), today.getMonth(), 1));

  const year = viewDate.getFullYear();
  const month = viewDate.getMonth();
  const totalDays = daysInMonth(year, month);
  const firstWeekday = new Date(year, month, 1).getDay();
  const todayIso = toISODate(today.getFullYear(), today.getMonth(), today.getDate());
  // Group by date rather than keying a single holiday per date — two or more
  // holidays can legitimately fall on the same date (e.g. an admin viewing
  // "All Locations", or duplicate entries), and a single-value Map would
  // silently drop every holiday but the last one for that date.
  const holidaysByDate = new Map<string, HolidayRow[]>();
  for (const h of holidays) {
    const list = holidaysByDate.get(h.holidayDate);
    if (list) list.push(h);
    else holidaysByDate.set(h.holidayDate, [h]);
  }

  const cells: Array<{ day: number; iso: string } | null> = [];
  for (let i = 0; i < firstWeekday; i++) cells.push(null);
  for (let d = 1; d <= totalDays; d++) cells.push({ day: d, iso: toISODate(year, month, d) });
  while (cells.length % 7 !== 0) cells.push(null);

  return (
    <div className="nf-section-enter" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
        <button
          onClick={() => setViewDate(new Date(year, month - 1, 1))}
          aria-label="Previous month"
          style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, width: 22, height: 22, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--txt-mut)' }}
        >
          <ChevronLeft size={12} />
        </button>
        <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 12, color: 'var(--txt)' }}>
          {viewDate.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })}
        </span>
        <button
          onClick={() => setViewDate(new Date(year, month + 1, 1))}
          aria-label="Next month"
          style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, width: 22, height: 22, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--txt-mut)' }}
        >
          <ChevronRight size={12} />
        </button>
      </div>

      {/* minmax(0, 1fr), not plain 1fr — a plain 1fr column will still grow past its
          fair share to fit unbroken (nowrap) content, e.g. a long holiday name,
          which is what was making columns uneven / pushing the last column off
          the edge. minmax(0, 1fr) hard-caps every track at 1fr regardless of
          content, which is also what makes the ellipsis truncation below actually
          take effect (it needs a fixed-width box to truncate against). */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))', gap: 3, marginBottom: 4 }}>
        {WEEKDAY_LABELS.map(d => (
          <div key={d} style={{ textAlign: 'center', fontSize: 10, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.03em', padding: '2px 0' }}>
            {d[0]}
          </div>
        ))}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))', gap: 3 }}>
        {cells.map((c, i) => {
          if (!c) return <div key={i} />;
          const dayHolidays = holidaysByDate.get(c.iso) ?? [];
          const hasHoliday = dayHolidays.length > 0;
          const visible = dayHolidays.slice(0, 2);
          const extraCount = dayHolidays.length - visible.length;
          const isToday = c.iso === todayIso;
          return (
            <div
              key={i}
              title={dayHolidays.map(h => h.holidayName).join(', ') || undefined}
              style={{
                // Fixed height, not minHeight — the cell must never resize based on
                // content; anything beyond what fits is summarized as "+N more"
                // and overflow:hidden is a hard backstop against the rest.
                height: 74,
                minWidth: 0,
                width: '100%',
                overflow: 'hidden',
                boxSizing: 'border-box',
                borderRadius: 8,
                padding: '6px 5px',
                background: hasHoliday ? 'color-mix(in srgb, var(--brand) 14%, transparent)' : 'var(--raised)',
                border: isToday ? '2px solid var(--brand)' : hasHoliday ? '1.5px solid color-mix(in srgb, var(--brand) 55%, transparent)' : '1px solid var(--line)',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'flex-start',
                gap: 3,
              }}
            >
              <span style={{ fontSize: 12, fontWeight: isToday ? 700 : 500, color: hasHoliday ? 'var(--brand)' : 'var(--txt)' }}>
                {c.day}
              </span>
              {visible.map(h => (
                <span key={h.id} style={{
                  fontSize: 9, fontWeight: 700, color: '#fff', background: 'var(--brand)',
                  borderRadius: 5, padding: '2px 5px', lineHeight: 1.4,
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                  maxWidth: '100%', width: '100%', boxSizing: 'border-box', flexShrink: 0,
                }}>
                  {h.holidayName}
                </span>
              ))}
              {extraCount > 0 && (
                <span style={{
                  fontSize: 8.5, fontWeight: 700, color: 'var(--brand)',
                  lineHeight: 1.3, flexShrink: 0,
                }}>
                  +{extraCount} more
                </span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function AddHolidayModal({ token, editing, onClose, onCreated }: { token: string; editing?: HolidayRow; onClose: () => void; onCreated: (locationId: string) => void }) {
  const [holidayName, setHolidayName] = useState(editing?.holidayName ?? '');
  const [holidayDate, setHolidayDate] = useState(editing?.holidayDate ?? '');
  const [locationId, setLocationId] = useState(editing?.locationId ?? '');
  const [locations, setLocations] = useState<LocationRow[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const firstRef = useRef<HTMLInputElement>(null);
  const todayIso = new Date().toISOString().slice(0, 10);

  useEffect(() => {
    firstRef.current?.focus();
    orgApi.listLocations(token).then(setLocations).catch(() => {});
  }, [token]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    const name = holidayName.trim();
    if (!name) { setError('Holiday name is required'); return; }
    if (name.length > HOLIDAY_NAME_MAX_LENGTH) { setError(`Holiday name must be ${HOLIDAY_NAME_MAX_LENGTH} characters or fewer`); return; }
    if (!HOLIDAY_NAME_PATTERN.test(name)) {
      setError('Holiday name must contain at least one letter, and only letters, numbers, spaces, apostrophes, or hyphens');
      return;
    }
    if (!holidayDate) { setError('Date is required'); return; }
    if (!editing && holidayDate < todayIso) { setError('Holiday date cannot be in the past'); return; }
    if (!locationId) { setError('Location is required'); return; }

    setLoading(true);
    try {
      if (editing) {
        await holidaysApi.updateHoliday(token, editing.id, { holidayName: name, holidayDate, locationId });
      } else {
        await holidaysApi.createHoliday(token, { holidayName: name, holidayDate, locationId });
      }
      onCreated(locationId);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={overlayStyle} onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={modalStyle}>
        <ModalHeader title={editing ? 'Edit Holiday' : 'Add Holiday'} onClose={onClose} />
        {error && <div style={{ margin: '16px 20px 0', color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
        <form onSubmit={submit} style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
          <Field label="Holiday Name *">
            <input ref={firstRef} maxLength={HOLIDAY_NAME_MAX_LENGTH} style={inputStyle} value={holidayName} onChange={e => setHolidayName(e.target.value)} placeholder="e.g. Diwali" />
          </Field>
          <Field label="Date *">
            <input type="date" min={editing ? undefined : todayIso} style={inputStyle} value={holidayDate} onChange={e => setHolidayDate(e.target.value)} />
          </Field>
          <Field label="Location *">
            <select style={inputStyle} value={locationId} onChange={e => setLocationId(e.target.value)}>
              <option value="">Select a location…</option>
              {/* Active-only for a NEW selection — a deactivated (not deleted) location shouldn't
                  be pickable going forward; `locationId` keeps this holiday's existing location
                  visible/selected if it was assigned before that location was deactivated. */}
              {locations.filter(l => l.active !== false || l.id === locationId).map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
            </select>
          </Field>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={loading} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.7 : 1 }}>{loading ? 'Saving…' : 'Save'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────
// Page — role-aware. Data fetched depends on the signed-in user's role:
//   Employee            → own balances/requests only
//   Manager              → own + pending approvals for direct reports + team-on-leave count
//   HR Admin/Super Admin → own + pending approvals org-wide + org-on-leave count + holiday admin
// All role gating below mirrors what the backend already enforces (LeaveController/
// LeaveService/HolidayController) — this page never grants a capability the API wouldn't
// also allow; hiding a control here is a UX nicety, not the security boundary.
// ─────────────────────────────────────────────────────────────────────────

type LeaveTabKey = 'leave' | 'holidays';

export default function LeavePage() {
  const token = useAuthStore(s => s.token)!;
  const user = useAuthStore(s => s.user);
  const shellRole = toShellRole(user?.role);
  const isAdmin = shellRole === 'HR Admin' || shellRole === 'Super Admin'; // matches HolidayController/organization() gate
  const isManager = shellRole === 'Manager';
  const canApprove = isManager || isAdmin; // matches LeaveService#listPendingApprovals scoping

  const [tab, setTab] = useState<LeaveTabKey>('leave');

  // Own leave (all roles)
  const [types, setTypes] = useState<LeaveType[]>([]);
  const [balances, setBalances] = useState<LeaveBalance[]>([]);
  const [requests, setRequests] = useState<LeaveRequestRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [leaveError, setLeaveError] = useState('');
  const [showRequest, setShowRequest] = useState(false);

  // My Leave History filters
  const [typeFilter, setTypeFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);

  // Approvals (Manager/HR Admin/Super Admin)
  const [approvals, setApprovals] = useState<LeaveRequestRecord[]>([]);
  const [approvalsLoading, setApprovalsLoading] = useState(canApprove);
  const [approvalsError, setApprovalsError] = useState('');
  const [approvalSearch, setApprovalSearch] = useState('');

  // Team/org "on leave today" KPI (Manager → team; HR Admin/Super Admin → organization)
  const [onLeaveToday, setOnLeaveToday] = useState<number | null>(null);

  // Holidays (all roles read; HR Admin/Super Admin also manage)
  const [holidays, setHolidays] = useState<HolidayRow[]>([]);
  const [holidayError, setHolidayError] = useState('');
  const [showAddHoliday, setShowAddHoliday] = useState(false);
  const [editingHoliday, setEditingHoliday] = useState<HolidayRow | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [adminLocations, setAdminLocations] = useState<LocationRow[]>([]);
  const [locationFilter, setLocationFilter] = useState(''); // admin only; '' = All Locations

  useEffect(() => {
    setLeaveError('');
    Promise.all([leaveApi.listTypes(token), leaveApi.listBalances(token), leaveApi.listMine(token)])
      .then(([t, b, r]) => { setTypes(t); setBalances(b); setRequests(r); })
      .catch(e => setLeaveError(e instanceof Error ? e.message : 'Failed to load leave data'))
      .finally(() => setLoading(false));
  }, [token]);

  function refreshApprovals() {
    if (!canApprove) return;
    setApprovalsError('');
    leaveApi.listApprovals(token)
      .then(setApprovals)
      .catch(e => setApprovalsError(e instanceof Error ? e.message : 'Failed to load approvals'))
      .finally(() => setApprovalsLoading(false));
  }

  useEffect(() => { refreshApprovals(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [canApprove, token]);

  useEffect(() => {
    if (!isManager && !isAdmin) return;
    const todayIso = new Date().toISOString().slice(0, 10);
    const fetchOnLeave = isAdmin ? leaveApi.organization : leaveApi.team;
    fetchOnLeave(todayIso, todayIso, token)
      .then(rows => setOnLeaveToday(new Set(rows.map(r => r.employeeUserId)).size))
      .catch(() => {});
  }, [isManager, isAdmin, token]);

  useEffect(() => {
    if (isAdmin && token) orgApi.listLocations(token).then(setAdminLocations).catch(() => {});
  }, [isAdmin, token]);

  // HR Admin/Super Admin manage holidays across locations, not just their own —
  // "my-location" would silently hide anything they create for a location that
  // isn't their own (or return nothing at all if they have no location set).
  async function fetchHolidays(overrideLocationId?: string) {
    const locId = overrideLocationId !== undefined ? overrideLocationId : locationFilter;
    setHolidayError('');
    try {
      const rows = isAdmin
        ? (locId ? await holidaysApi.listByLocation(token, locId) : await holidaysApi.listAll(token))
        : await holidaysApi.listForMyLocation(token);
      setHolidays(rows);
    } catch (e) {
      setHolidayError(e instanceof Error ? e.message : 'Failed to load holidays');
    }
  }

  useEffect(() => { if (token) fetchHolidays(); }, [token, isAdmin]);

  // An employee's tab may already be open when HR Admin edits/deletes a
  // holiday for their location — there's no push/websocket in this app, so
  // without this the tab would only pick up the change on its next full
  // navigation. Refetch when the tab regains focus/visibility instead of
  // requiring a manual reload.
  useEffect(() => {
    if (!token) return;
    function onFocus() { fetchHolidays(); }
    function onVisibility() { if (document.visibilityState === 'visible') fetchHolidays(); }
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onVisibility);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, isAdmin, locationFilter]);

  async function handleDeleteHoliday(h: HolidayRow) {
    if (!window.confirm(`Delete "${h.holidayName}"? This can't be undone from here.`)) return;
    setDeletingId(h.id);
    try {
      await holidaysApi.deleteHoliday(token, h.id);
      await fetchHolidays();
    } catch (e) {
      setHolidayError(e instanceof Error ? e.message : 'Failed to delete holiday');
    } finally {
      setDeletingId(null);
    }
  }

  function handleCreated(r: LeaveRequestRecord) {
    setRequests(prev => [r, ...prev]);
    setPage(1);
  }

  function handleApprovalDecided() {
    refreshApprovals();
  }

  const holidayScopeLabel = isAdmin
    ? (locationFilter ? adminLocations.find(l => l.id === locationFilter)?.name : 'All Locations')
    : holidays[0]?.locationName;

  // ── Derived, role-aware KPIs — all computed client-side from data already fetched above ──
  const todayIso = new Date().toISOString().slice(0, 10);
  const totalAvailable = useMemo(() => balances.reduce((s, b) => s + Math.max(0, Number(b.remainingDays)), 0), [balances]);
  const totalUsed = useMemo(() => balances.reduce((s, b) => s + Math.max(0, Number(b.usedDays)), 0), [balances]);
  const myPendingCount = useMemo(() => requests.filter(r => r.status === 'PENDING').length, [requests]);
  const upcoming = useMemo(
    () => requests.filter(r => r.status === 'APPROVED' && r.startDate >= todayIso).sort((a, b) => a.startDate.localeCompare(b.startDate))[0],
    [requests, todayIso]
  );

  const filteredApprovals = useMemo(() => {
    if (!approvalSearch.trim()) return approvals;
    const q = approvalSearch.trim().toLowerCase();
    return approvals.filter(r => r.employeeName.toLowerCase().includes(q) || r.leaveTypeName.toLowerCase().includes(q));
  }, [approvals, approvalSearch]);

  const filteredRequests = useMemo(() => requests.filter(r => {
    if (typeFilter && r.leaveTypeCode !== typeFilter) return false;
    if (statusFilter && r.status !== statusFilter) return false;
    if (search.trim() && !r.employeeReason.toLowerCase().includes(search.trim().toLowerCase()) && !r.leaveTypeName.toLowerCase().includes(search.trim().toLowerCase())) return false;
    return true;
  }), [requests, typeFilter, statusFilter, search]);

  const totalPages = Math.max(1, Math.ceil(filteredRequests.length / PAGE_SIZE));
  const pageSafe = Math.min(page, totalPages);
  const pageRows = filteredRequests.slice((pageSafe - 1) * PAGE_SIZE, pageSafe * PAGE_SIZE);

  function updateFilterAndResetPage(setter: (v: string) => void, value: string) { setter(value); setPage(1); }

  const contextDescription = isAdmin
    ? 'View your leave balance, manage organization-wide requests, and administer company holidays.'
    : isManager
      ? 'View your leave balance, request leave, and review your team\'s pending requests.'
      : 'View your leave balance, request leave, and track approvals.';

  return (
    <div>
      <div className="nf-section-enter nf-leave-header" style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 22,
        background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: '18px 22px',
        backgroundImage: 'linear-gradient(120deg, color-mix(in srgb, var(--brand) 6%, var(--panel)) 0%, var(--panel) 55%)',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, minWidth: 0 }}>
          <div style={{
            width: 42, height: 42, borderRadius: 10, flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center',
            background: 'linear-gradient(155deg, var(--brand) 0%, var(--brand-deep) 100%)', boxShadow: '0 4px 14px rgba(177,17,22,.28)',
          }}>
            <Sparkles size={19} color="#fff" />
          </div>
          <div style={{ minWidth: 0 }}>
            <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Leave &amp; Holidays</h1>
            <p style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {contextDescription}
            </p>
          </div>
        </div>
        <button onClick={() => setShowRequest(true)} disabled={types.length === 0} style={{ display: 'flex', alignItems: 'center', gap: 7, flexShrink: 0, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '10px 18px', fontSize: 13, fontWeight: 600, cursor: types.length === 0 ? 'not-allowed' : 'pointer', opacity: types.length === 0 ? 0.6 : 1, boxShadow: types.length === 0 ? 'none' : '0 2px 10px rgba(177,17,22,.25)' }}>
          <CalendarPlus size={14} /> Request Leave
        </button>
      </div>

      {leaveError && (
        <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13, marginBottom: 14 }}>
          {leaveError}
        </div>
      )}

      {/* Sibling tabs — Leave / Holidays, same "Leave & Holidays" module */}
      <div style={{ display: 'flex', gap: 4, borderBottom: '1px solid var(--line)', marginBottom: 22 }}>
        {([{ key: 'leave' as const, label: 'Leave' }, { key: 'holidays' as const, label: 'Holidays' }]).map(t => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            style={{
              background: 'none', border: 'none', cursor: 'pointer', padding: '10px 16px 12px',
              fontSize: 13.5, fontWeight: 600, color: tab === t.key ? 'var(--txt)' : 'var(--txt-mut)',
              borderBottom: tab === t.key ? '2px solid var(--brand)' : '2px solid transparent', marginBottom: -1,
            }}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'leave' ? (
        <div>
          {!loading && (
            <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 20 }}>
              <KpiTile icon={Wallet} label="Available Leave" value={`${totalAvailable}d`} sub="Across all leave types" accent="var(--ok)" />
              <KpiTile icon={CheckCircle2} label="Used This Year" value={`${totalUsed}d`} sub="Approved & consumed" accent="var(--info)" />
              <KpiTile icon={Hourglass} label="My Pending Requests" value={String(myPendingCount)} sub={myPendingCount === 0 ? 'All caught up' : 'Awaiting a decision'} accent="var(--warn)" />
              <KpiTile
                icon={CalendarClock} label="Upcoming Leave"
                value={upcoming ? new Date(upcoming.startDate + 'T00:00:00').toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) : '—'}
                sub={upcoming ? upcoming.leaveTypeName : 'Nothing scheduled'} accent="var(--brand)"
              />
            </div>
          )}

          {canApprove && (
            <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 20 }}>
              <KpiTile
                icon={Hourglass} label={isAdmin ? 'Org Pending Approvals' : 'Team Pending Approvals'}
                value={approvalsLoading ? '—' : String(approvals.length)}
                sub={isAdmin ? 'Awaiting your decision, org-wide' : "Awaiting your decision"} accent="var(--warn)"
              />
              <KpiTile
                icon={Users} label={isAdmin ? 'On Leave Today (Org)' : 'On Leave Today (Team)'}
                value={onLeaveToday === null ? '—' : String(onLeaveToday)}
                sub={isAdmin ? 'Across the organization' : 'Your direct reports'} accent="var(--info)"
              />
            </div>
          )}

          {!loading && (
            <div style={{ marginBottom: 22 }}>
              <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 10 }}>
                My Leave Balances
              </div>
              {balances.length === 0 ? (
                <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: 40, textAlign: 'center' }}>
                  <Wallet size={26} style={{ color: 'var(--line2)', display: 'block', margin: '0 auto 10px' }} />
                  <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>No leave balances configured.</div>
                </div>
              ) : (
                <div className="nf-autofit-mobile-safe" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 12 }}>
                  {balances.map((b, i) => <LeaveBalanceRingCard key={b.leaveTypeCode} balance={b} accent={BALANCE_ACCENTS[i % BALANCE_ACCENTS.length]} />)}
                </div>
              )}
            </div>
          )}

          {canApprove && (
            <div style={{ marginBottom: 22 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
                <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em' }}>
                  {isAdmin ? 'Organization — Pending Approvals' : 'Team — Pending Approvals'}
                </div>
                {approvals.length > 0 && (
                  <div style={{ position: 'relative' }}>
                    <Search size={13} style={{ position: 'absolute', left: 9, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)' }} />
                    <input value={approvalSearch} onChange={e => setApprovalSearch(e.target.value)} placeholder="Search employee or type…" style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px 7px 28px', fontSize: 12.5, color: 'var(--txt)', width: 200 }} />
                  </div>
                )}
              </div>

              {approvalsError && (
                <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13, marginBottom: 14 }}>
                  {approvalsError}
                </div>
              )}

              <div className="nf-section-enter" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
                {approvalsLoading ? (
                  <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
                ) : approvals.length === 0 ? (
                  <div style={{ padding: 40, textAlign: 'center' }}>
                    <CheckCircle2 size={26} style={{ color: 'var(--line2)', display: 'block', margin: '0 auto 10px' }} />
                    <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>No pending approvals right now.</div>
                  </div>
                ) : filteredApprovals.length === 0 ? (
                  <div style={{ padding: 40, textAlign: 'center', fontSize: 13, color: 'var(--txt-mut)' }}>No approvals match your search.</div>
                ) : (
                  <div style={{ overflowX: 'auto' }}>
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                      <thead>
                        <tr>{['Employee', 'Type', 'Dates', 'Days', 'Reason', 'Decision'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
                      </thead>
                      <tbody>
                        {filteredApprovals.map(r => <ApprovalRow key={r.id} request={r} token={token} onDecided={handleApprovalDecided} />)}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </div>
          )}

          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
            <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em' }}>My Leave History</div>
            {!loading && requests.length > 0 && (
              <div className="nf-leave-filter-bar" style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                <div style={{ position: 'relative' }}>
                  <Search size={13} style={{ position: 'absolute', left: 9, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)' }} />
                  <input value={search} onChange={e => { setSearch(e.target.value); setPage(1); }} placeholder="Search reason or type…" style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px 7px 28px', fontSize: 12.5, color: 'var(--txt)', width: 190 }} />
                </div>
                <select value={typeFilter} onChange={e => updateFilterAndResetPage(setTypeFilter, e.target.value)} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px', fontSize: 12.5, color: 'var(--txt)' }}>
                  <option value="">All Types</option>
                  {types.map(t => <option key={t.code} value={t.code}>{t.name}</option>)}
                </select>
                <select value={statusFilter} onChange={e => updateFilterAndResetPage(setStatusFilter, e.target.value)} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px', fontSize: 12.5, color: 'var(--txt)' }}>
                  <option value="">All Statuses</option>
                  <option value="PENDING">Pending</option>
                  <option value="APPROVED">Approved</option>
                  <option value="REJECTED">Rejected</option>
                </select>
              </div>
            )}
          </div>

          <div className="nf-section-enter" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
            {loading ? (
              <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
            ) : requests.length === 0 ? (
              <div style={{ padding: 48, textAlign: 'center' }}>
                <CalendarPlus size={28} aria-hidden="true" style={{ color: 'var(--line2)', display: 'block', margin: '0 auto 10px' }} />
                <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>No leave requests yet</div>
                <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>Click "Request Leave" to submit your first request.</div>
              </div>
            ) : filteredRequests.length === 0 ? (
              <div style={{ padding: 40, textAlign: 'center' }}>
                <Search size={24} style={{ color: 'var(--line2)', display: 'block', margin: '0 auto 10px' }} />
                <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>No requests match your filters.</div>
              </div>
            ) : (
              <>
                <div style={{ overflowX: 'auto' }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead>
                      <tr>
                        {['Type', 'Dates', 'Days', 'Status', 'Reason', 'Decision'].map(h => <th key={h} style={thStyle}>{h}</th>)}
                      </tr>
                    </thead>
                    <tbody>
                      {pageRows.map(r => (
                        <tr key={r.id} className="nf-leave-row">
                          <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.leaveTypeName}</td>
                          <td style={tdStyle}>{r.startDate}{r.startDate !== r.endDate ? ` → ${r.endDate}` : ''}{r.halfDay ? ' (half day)' : ''}</td>
                          <td style={{ ...tdStyle, fontFamily: '"JetBrains Mono", monospace' }}>{r.totalDays}</td>
                          <td style={tdStyle}><StatusBadge status={r.status} /></td>
                          <td style={{ ...tdStyle, maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={r.employeeReason}>{r.employeeReason}</td>
                          <td style={tdStyle}>
                            {r.status === 'PENDING' && <span style={{ color: 'var(--txt-dim)' }}>Awaiting decision</span>}
                            {r.status === 'APPROVED' && <span>Approved by <b style={{ color: 'var(--txt)' }}>{r.decidedByName}</b>{r.decidedAt ? ` on ${new Date(r.decidedAt).toLocaleDateString()}` : ''}</span>}
                            {r.status === 'REJECTED' && (
                              <span>Rejected by <b style={{ color: 'var(--txt)' }}>{r.decidedByName}</b>: {r.decisionReason}</span>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 14px', borderTop: '1px solid var(--line)', fontSize: 12, color: 'var(--txt-dim)' }}>
                  <span>Showing {(pageSafe - 1) * PAGE_SIZE + 1}–{Math.min(pageSafe * PAGE_SIZE, filteredRequests.length)} of {filteredRequests.length}</span>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <button onClick={() => setPage(p => Math.max(1, p - 1))} disabled={pageSafe <= 1} aria-label="Previous page" style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, width: 24, height: 24, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: pageSafe <= 1 ? 'not-allowed' : 'pointer', color: 'var(--txt-mut)', opacity: pageSafe <= 1 ? 0.5 : 1 }}>
                      <ChevronLeft size={13} />
                    </button>
                    <span style={{ fontWeight: 600, color: 'var(--txt-mut)' }}>Page {pageSafe} of {totalPages}</span>
                    <button onClick={() => setPage(p => Math.min(totalPages, p + 1))} disabled={pageSafe >= totalPages} aria-label="Next page" style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, width: 24, height: 24, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: pageSafe >= totalPages ? 'not-allowed' : 'pointer', color: 'var(--txt-mut)', opacity: pageSafe >= totalPages ? 0.5 : 1 }}>
                      <ChevronRight size={13} />
                    </button>
                  </div>
                </div>
              </>
            )}
          </div>
        </div>
      ) : (
        <div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14, flexWrap: 'wrap', gap: 10 }}>
            <div>
              <p style={{ fontSize: 12.5, color: 'var(--txt-mut)', margin: 0 }}>
                {isAdmin ? 'Holidays across the company, by location.' : 'Holidays for your work location.'}
                {holidayScopeLabel ? ` — ${holidayScopeLabel}` : ''}
              </p>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              {isAdmin && (
                <select
                  value={locationFilter}
                  onChange={e => { const v = e.target.value; setLocationFilter(v); fetchHolidays(v); }}
                  style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px', fontSize: 12.5, color: 'var(--txt)' }}
                >
                  <option value="">All Locations</option>
                  {adminLocations.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
                </select>
              )}
              {isAdmin && (
                <button onClick={() => setShowAddHoliday(true)} style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '8px 14px', fontSize: 12.5, fontWeight: 600, cursor: 'pointer' }}>
                  <Plus size={13} /> Add Holiday
                </button>
              )}
            </div>
          </div>

          {holidayError && (
            <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13, marginBottom: 14 }}>
              {holidayError}
            </div>
          )}

          {holidays.length > 0 && (() => {
            const todayIsoNow = new Date().toISOString().slice(0, 10);
            const todayMs = new Date(todayIsoNow + 'T00:00:00').getTime();
            const upcomingHolidays = holidays
              .filter(h => h.active && h.holidayDate >= todayIsoNow)
              .sort((a, b) => a.holidayDate.localeCompare(b.holidayDate))
              .slice(0, 4)
              .map(h => ({ holiday: h, daysAway: Math.round((new Date(h.holidayDate + 'T00:00:00').getTime() - todayMs) / 86400000) }));
            return upcomingHolidays.length > 0 ? (
              <div style={{ marginBottom: 20 }}>
                <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 10 }}>Upcoming Holidays</div>
                <div className="nf-autofit-mobile-safe" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12 }}>
                  {upcomingHolidays.map(u => <UpcomingHolidayCard key={u.holiday.id} holiday={u.holiday} daysAway={u.daysAway} />)}
                </div>
              </div>
            ) : null;
          })()}

          <div className={holidays.length > 0 ? 'nf-grid-side-collapse' : undefined} style={holidays.length > 0 ? { display: 'grid', gridTemplateColumns: 'minmax(310px, 380px) 1fr', gap: 20, alignItems: 'start' } : undefined}>
            {holidays.length > 0 && <HolidayMonthCalendar holidays={holidays} />}

            <div className="nf-section-enter" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
              {holidays.length === 0 ? (
                <div style={{ padding: 48, textAlign: 'center' }}>
                  <CalendarDays size={28} aria-hidden="true" style={{ color: 'var(--line2)', display: 'block', margin: '0 auto 10px' }} />
                  <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>
                    {isAdmin ? 'No holidays have been added yet.' : 'No holidays have been added for your location yet.'}
                  </div>
                </div>
              ) : (
                <div style={{ overflowX: 'auto' }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed' }}>
                    <colgroup>
                      {isAdmin ? (
                        <>
                          <col style={{ width: '28%' }} />
                          <col style={{ width: '22%' }} />
                          <col style={{ width: '20%' }} />
                          <col style={{ width: '15%' }} />
                          <col style={{ width: '15%' }} />
                        </>
                      ) : (
                        <>
                          <col style={{ width: '45%' }} />
                          <col style={{ width: '35%' }} />
                          <col style={{ width: '20%' }} />
                        </>
                      )}
                    </colgroup>
                    <thead>
                      <tr>
                        {(isAdmin ? ['Holiday Name', 'Date', 'Location', 'Status', 'Actions'] : ['Holiday Name', 'Date', 'Status']).map(h => <th key={h} style={holidayThStyle}>{h}</th>)}
                      </tr>
                    </thead>
                    <tbody>
                      {holidays.map(h => (
                        <tr key={h.id}>
                          <td style={{ ...holidayTdStyle, color: 'var(--txt)', fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={h.holidayName}>{h.holidayName}</td>
                          <td style={holidayTdStyle}>{formatHolidayDate(h.holidayDate)}</td>
                          {isAdmin && <td style={{ ...holidayTdStyle, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={h.locationName}>{h.locationName}</td>}
                          <td style={holidayTdStyle}>{HOLIDAY_STATUS_BADGE(h.active)}</td>
                          {isAdmin && (
                            <td style={holidayTdStyle}>
                              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6 }}>
                                <button
                                  onClick={() => setEditingHoliday(h)}
                                  aria-label={`Edit ${h.holidayName}`}
                                  style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, width: 26, height: 26, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--txt-mut)' }}
                                >
                                  <Pencil size={12} />
                                </button>
                                <button
                                  onClick={() => handleDeleteHoliday(h)}
                                  disabled={deletingId === h.id}
                                  aria-label={`Delete ${h.holidayName}`}
                                  style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, width: 26, height: 26, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: deletingId === h.id ? 'not-allowed' : 'pointer', color: 'var(--risk)', opacity: deletingId === h.id ? 0.5 : 1 }}
                                >
                                  <Trash2 size={12} />
                                </button>
                              </div>
                            </td>
                          )}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {showRequest && (
        <RequestLeaveModal types={types} balances={balances} token={token} onClose={() => setShowRequest(false)} onCreated={handleCreated} />
      )}

      {(showAddHoliday || editingHoliday) && (
        <AddHolidayModal
          token={token}
          editing={editingHoliday ?? undefined}
          onClose={() => { setShowAddHoliday(false); setEditingHoliday(null); }}
          onCreated={(locId) => { setLocationFilter(locId); fetchHolidays(locId); }}
        />
      )}
    </div>
  );
}

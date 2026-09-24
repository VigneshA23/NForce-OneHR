import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  CalendarClock, CalendarPlus, CheckCircle2, ChevronLeft, ChevronRight, Hourglass, Search,
  Sparkles, Wallet, X,
} from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { leaveApi, type LeaveType, type LeaveBalance, type LeaveRequestRecord, type SubmitLeaveRequestPayload } from '../api/leave';
import { useToast } from '../context/ToastContext';
import { subscribeToNewNotifications } from '../lib/notificationEvents';
import { roundDays } from '../utils/leaveDays';

// Notification types that mean "this employee's own leave balance/status may have changed" —
// mirrors the backend's LeaveService notification events (LEAVE_APPROVED/LEAVE_REJECTED). Every
// other type (asset, regularization, helpdesk, document, ...) is deliberately ignored here.
const LEAVE_DECISION_NOTIFICATION_TYPES = new Set(['LEAVE_APPROVED', 'LEAVE_REJECTED']);

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 520, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
// color: var(--txt-mut), not var(--txt-dim) — higher-contrast in both themes (see index.css's
// token table), so headers read as clearly bold/prominent rather than washed out. Same weight/
// size/uppercase/letter-spacing convention used app-wide (AttendancePage/ApprovalsPage/etc).
const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const tdStyle: React.CSSProperties = { padding: '12px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

// Primary add-action button for this page — "Request Leave".
const primaryButtonStyle: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' };

const PAGE_SIZE = 5;
const BALANCE_ACCENTS = ['var(--brand)', 'var(--info)', 'var(--ok)', 'var(--warn)'];

function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
      <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>{title}</span>
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

function StatusBadge({ status }: { status: string }) {
  const color = STATUS_COLOR[status] ?? 'var(--txt-dim)';
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11, fontWeight: 700, color,
      background: `color-mix(in srgb, ${color} 13%, var(--raised))`, border: `1px solid color-mix(in srgb, ${color} 30%, var(--line))`,
      borderRadius: 20, padding: '3px 9px 3px 7px', textTransform: 'uppercase', letterSpacing: '.03em',
    }}>
      <span className={status === 'PENDING' ? 'nf-hero-status-dot' : undefined} style={{ width: 6, height: 6, borderRadius: '50%', background: color, flexShrink: 0 }} />
      {status}
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
      <div style={{ fontSize: 22, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', lineHeight: 1 }}>{value}</div>
      {sub && <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>{sub}</div>}
    </div>
  );
}

// Radial-progress balance card — draws an SVG circle via stroke-dasharray/offset, reusing the
// same .nf-ring-progress/.nf-section-enter entrance animation already defined in index.css for
// the Attendance "Today" ring (both already respect prefers-reduced-motion, see index.css). The
// backend (LeaveService#availableBalance) is the sole source of truth for both numbers — this
// component only visualizes remainingDays/totalDays as returned by GET /api/leave/balances; it
// never recomputes or re-derives the balance itself (same total/available/consumed values the
// previous donut used).
const RING_RADIUS = 30;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

function LeaveBalanceRingCard({ balance, accent }: { balance: LeaveBalance; accent: string }) {
  const total = Number(balance.totalDays);
  const available = Math.max(0, Number(balance.remainingDays));
  // roundDays strips the IEEE-754 noise this subtraction can reintroduce even on two already-exact
  // BigDecimal-derived values (e.g. 15 - 13.7 rendering as 1.3000000000000007) - see utils/leaveDays.ts.
  const consumed = roundDays(Math.max(0, total - available));
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
          <span style={{ fontSize: 16, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', lineHeight: 1 }}>{available}</span>
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
              Available <span style={{ marginLeft: 'auto', color: 'var(--txt)', fontWeight: 700 }}>{available}d</span>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--txt-mut)' }}>
              <span style={{ width: 7, height: 7, borderRadius: 2, background: 'var(--line2)', flexShrink: 0 }} />
              Consumed <span style={{ marginLeft: 'auto', color: 'var(--txt)', fontWeight: 700 }}>{consumed}d</span>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function RequestLeaveModal({ types, balances, onClose, onCreated, token }: { types: LeaveType[]; balances: LeaveBalance[]; onClose: () => void; onCreated: (r: LeaveRequestRecord) => void; token: string }) {
  const { showToast } = useToast();
  // Local calendar date, not new Date().toISOString().slice(0, 10) — the ISO/UTC form can land
  // on the wrong side of midnight relative to the user's actual local day, which would let the
  // date picker's min slip a day off from what "today" really is.
  const now = new Date();
  const today = toISODate(now.getFullYear(), now.getMonth(), now.getDate());
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

  // Early-UX only, mirroring the backend's own classification-based rule (LeaveService#submitRequest)
  // — the backend independently re-validates against ALL of the employee's paid balances regardless
  // of what's checked here, so this can never be relied on to enforce the rule by itself.
  const selectedType = types.find(t => t.code === form.leaveTypeCode);
  const hasAnyPaidBalance = balances.some(b => b.remainingDays > 0);
  const blockedUnpaidWithPaidBalance = selectedType?.classification === 'UNPAID' && hasAnyPaidBalance;

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!form.reason.trim()) { setError('A reason is required.'); return; }
    // Early-UX only, same as the balance check below — the backend independently re-validates
    // this against its own (timezone-correct) notion of "today" regardless of what's checked here.
    if (form.startDate < today) { setError('Leave cannot be requested for a date before today.'); return; }
    if (exceedsBalance && selectedBalance) {
      setError(`Leave request exceeds your available ${selectedBalance.leaveTypeName} balance of ${selectedBalance.remainingDays} days.`);
      return;
    }
    if (blockedUnpaidWithPaidBalance) {
      setError('You cannot apply for unpaid leave while you have an available paid leave balance.');
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
                {types.map(t => <option key={t.code} value={t.code}>{t.name}{t.classification === 'UNPAID' ? ' (Unpaid)' : ''}</option>)}
              </select>
            </Field>
            {selectedBalance && (
              <div style={{ fontSize: 11.5, color: exceedsBalance ? 'var(--risk)' : 'var(--txt-dim)', marginTop: 5 }}>
                Available: {selectedBalance.remainingDays} day{selectedBalance.remainingDays === 1 ? '' : 's'}
                {exceedsBalance && ' — this request exceeds your available balance'}
              </div>
            )}
            {blockedUnpaidWithPaidBalance && (
              <div style={{ fontSize: 11.5, color: 'var(--risk)', marginTop: 5 }}>
                You cannot apply for unpaid leave while you have an available paid leave balance.
              </div>
            )}
          </div>
          <Field label="Start Date *">
            <input type="date" min={today} style={inputStyle} value={form.startDate} onChange={e => setForm(f => ({ ...f, startDate: e.target.value, endDate: f.halfDay ? e.target.value : f.endDate }))} />
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
            <button type="submit" disabled={submitting || exceedsBalance || blockedUnpaidWithPaidBalance} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: (submitting || exceedsBalance || blockedUnpaidWithPaidBalance) ? 'not-allowed' : 'pointer', opacity: (submitting || exceedsBalance || blockedUnpaidWithPaidBalance) ? 0.6 : 1 }}>{submitting ? 'Submitting…' : 'Submit Request'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

// r.decidedAt is an ISO instant (see LeaveRequestResponse#decidedAt) — formatted for the
// "Approved/Rejected By" column, e.g. "18 Aug 2026, 10:30 AM".
function formatDecisionTimestamp(iso: string) {
  return new Date(iso).toLocaleString(undefined, { day: '2-digit', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit', hour12: true });
}

function toISODate(year: number, month: number, day: number) {
  return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

export default function LeavePage() {
  const token = useAuthStore(s => s.token)!;
  const [types, setTypes] = useState<LeaveType[]>([]);
  const [balances, setBalances] = useState<LeaveBalance[]>([]);
  const [requests, setRequests] = useState<LeaveRequestRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [leaveError, setLeaveError] = useState('');
  const [showRequest, setShowRequest] = useState(false);

  useEffect(() => {
    setLeaveError('');
    Promise.all([leaveApi.listTypes(token), leaveApi.listBalances(token), leaveApi.listMine(token)])
      .then(([t, b, r]) => { setTypes(t); setBalances(b); setRequests(r); })
      .catch(e => setLeaveError(e instanceof Error ? e.message : 'Failed to load leave data'))
      .finally(() => setLoading(false));
  }, [token]);

  // Single source of truth for "re-fetch balances + requests without disturbing anything else
  // on the page" — used both after the employee's own submission and after a
  // LEAVE_APPROVED/LEAVE_REJECTED notification arrives for this employee (see the effect below).
  // Deliberately never touches `loading` (no full-page skeleton flash) or any holiday/filter/
  // pagination state. Overlap-safe: a call that arrives while one is already in flight is
  // coalesced into a single trailing re-run instead of firing a second concurrent request, so a
  // submit-triggered refresh and a notification-triggered refresh landing close together can
  // never race each other or the UI backwards with stale data.
  const refreshInFlightRef = useRef(false);
  const refreshQueuedRef = useRef(false);
  const refreshLeaveData = useCallback(async () => {
    if (refreshInFlightRef.current) { refreshQueuedRef.current = true; return; }
    refreshInFlightRef.current = true;
    try {
      const [freshBalances, freshRequests] = await Promise.all([leaveApi.listBalances(token), leaveApi.listMine(token)]);
      setBalances(freshBalances);
      setRequests(freshRequests);
    } catch (e) {
      setLeaveError(e instanceof Error ? e.message : 'Failed to refresh leave data');
    } finally {
      refreshInFlightRef.current = false;
      if (refreshQueuedRef.current) {
        refreshQueuedRef.current = false;
        refreshLeaveData();
      }
    }
  }, [token]);

  // React to this employee's own leave decisions as the app-wide notification poll (Shell)
  // detects them — no separate polling loop here, and no browser refresh needed. Notifications
  // for other employees never reach this listener: the backend's /api/notifications endpoints
  // are scoped to the authenticated caller (see NotificationController#resolveUserId), so every
  // item Shell publishes already belongs to this signed-in user. Unrelated notification types
  // (asset/regularization/helpdesk/document/...) are filtered out and never trigger a refresh.
  // A batch containing several LEAVE_APPROVED/REJECTED items (e.g. two requests decided within
  // the same 30s poll window) still triggers exactly one refreshLeaveData() call, not one per
  // item. Unsubscribes on unmount so remounting this page never accumulates listeners.
  useEffect(() => {
    return subscribeToNewNotifications(items => {
      if (items.some(n => LEAVE_DECISION_NOTIFICATION_TYPES.has(n.type))) {
        refreshLeaveData();
      }
    });
  }, [refreshLeaveData]);

  // A newly-submitted PENDING request is immediately reserved against the balance (see
  // LeaveService#availableBalance — it subtracts PENDING days, not just APPROVED usedDays), so
  // the pie chart must be refreshed right away instead of waiting for the next full page load.
  // The optimistic prepend shows the new request instantly; refreshLeaveData then reconciles
  // both the requests list and the balances against the server (and safely coalesces with any
  // notification-triggered refresh that happens to land around the same time — see its comment).
  async function handleCreated(r: LeaveRequestRecord) {
    setRequests(prev => [r, ...prev]);
    await refreshLeaveData();
  }

  // ── History filters — presentation-only, operate on the `requests` array already loaded
  // above via the existing leaveApi.listMine()/refreshLeaveData() calls; no new API calls. ──
  const [typeFilter, setTypeFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);

  // ── Own-leave KPIs — derived client-side from `balances`/`requests` already loaded above. ──
  const totalAvailable = useMemo(() => balances.reduce((s, b) => s + Math.max(0, Number(b.remainingDays)), 0), [balances]);
  const totalUsed = useMemo(() => balances.reduce((s, b) => s + Math.max(0, Number(b.usedDays)), 0), [balances]);
  const myPendingCount = useMemo(() => requests.filter(r => r.status === 'PENDING').length, [requests]);
  const todayIso = useMemo(() => { const n = new Date(); return toISODate(n.getFullYear(), n.getMonth(), n.getDate()); }, []);
  const upcoming = useMemo(
    () => requests.filter(r => r.status === 'APPROVED' && r.startDate >= todayIso).sort((a, b) => a.startDate.localeCompare(b.startDate))[0],
    [requests, todayIso]
  );

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
            <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Leave</h1>
            <p style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 3 }}>View your balance, request leave, and track approvals.</p>
          </div>
        </div>
        <button onClick={() => setShowRequest(true)} disabled={types.length === 0} style={{ ...primaryButtonStyle, padding: '10px 18px', cursor: types.length === 0 ? 'not-allowed' : 'pointer', opacity: types.length === 0 ? 0.6 : 1, boxShadow: types.length === 0 ? 'none' : '0 2px 10px rgba(177,17,22,.25)' }}>
          <CalendarPlus size={14} /> Request Leave
        </button>
      </div>

      {leaveError && (
        <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13, marginBottom: 14 }}>
          {leaveError}
        </div>
      )}

      {!loading && (
        <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 20 }}>
          <KpiTile icon={Wallet} label="Available Leave" value={`${totalAvailable}d`} sub="Across all leave types" accent="var(--ok)" />
          <KpiTile icon={CheckCircle2} label="Used This Year" value={`${totalUsed}d`} sub="Approved & consumed" accent="var(--info)" />
          <KpiTile icon={Hourglass} label="Pending Requests" value={String(myPendingCount)} sub={myPendingCount === 0 ? 'All caught up' : 'Awaiting a decision'} accent="var(--warn)" />
          <KpiTile
            icon={CalendarClock} label="Upcoming Leave"
            value={upcoming ? new Date(upcoming.startDate + 'T00:00:00').toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) : '—'}
            sub={upcoming ? upcoming.leaveTypeName : 'Nothing scheduled'} accent="var(--brand)"
          />
        </div>
      )}

      {!loading && (
        <div style={{ marginBottom: 22 }}>
          <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 10 }}>Leave Balances</div>
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

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
        <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em' }}>Leave History</div>
        {!loading && requests.length > 0 && (
          <div className="nf-leave-filter-bar" style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <div style={{ position: 'relative' }}>
              <Search size={13} style={{ position: 'absolute', left: 9, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)' }} />
              <input value={search} onChange={e => { setSearch(e.target.value); setPage(1); }} placeholder="Search reason or type…" style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px 7px 28px', fontSize: 12.5, color: 'var(--txt)', width: 190 }} />
            </div>
            <select value={typeFilter} onChange={e => updateFilterAndResetPage(setTypeFilter, e.target.value)} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px', fontSize: 12.5, color: 'var(--txt)' }}>
              <option value="">All Types</option>
              {types.map(t => <option key={t.code} value={t.code}>{t.name}{t.classification === 'UNPAID' ? ' (Unpaid)' : ''}</option>)}
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
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Type', 'Dates', 'Days', 'Status', 'Reason', 'Approved/Rejected By'].map(h => <th key={h} style={thStyle}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {pageRows.map(r => (
                  <tr key={r.id} className="nf-leave-row">
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>
                      {r.leaveTypeName}
                      {r.leaveTypeClassification === 'UNPAID' && (
                        <span style={{ marginLeft: 6, fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.03em' }}>
                          (Unpaid)
                        </span>
                      )}
                    </td>
                    <td style={tdStyle}>{r.startDate}{r.startDate !== r.endDate ? ` → ${r.endDate}` : ''}{r.halfDay ? ' (half day)' : ''}</td>
                    <td style={tdStyle}>{r.totalDays}</td>
                    <td style={tdStyle}><StatusBadge status={r.status} /></td>
                    <td style={{ ...tdStyle, maxWidth: 220 }}>
                      <div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={r.employeeReason}>{r.employeeReason}</div>
                    </td>
                    <td style={{ ...tdStyle, maxWidth: 240 }}>
                      {r.status === 'PENDING' ? (
                        <span style={{ color: 'var(--txt-dim)' }}>Awaiting decision</span>
                      ) : (
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                          <span style={{ fontSize: 10.5, fontWeight: 700, color: r.status === 'APPROVED' ? 'var(--ok)' : 'var(--risk)', textTransform: 'uppercase', letterSpacing: '.04em' }}>
                            {r.status === 'APPROVED' ? 'Approved by' : 'Rejected by'}
                          </span>
                          <span style={{ color: 'var(--txt)', fontWeight: 600 }}>{r.decidedByName}</span>
                          {r.decidedAt && <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>{formatDecisionTimestamp(r.decidedAt)}</span>}
                          {r.decisionReason && (
                            <span
                              style={{ fontSize: 12, color: 'var(--txt-mut)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 220, display: 'block' }}
                              title={r.decisionReason}
                            >
                              Comment: {r.decisionReason}
                            </span>
                          )}
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
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
          </div>
        )}
      </div>

      {showRequest && (
        <RequestLeaveModal types={types} balances={balances} token={token} onClose={() => setShowRequest(false)} onCreated={handleCreated} />
      )}
    </div>
  );
}

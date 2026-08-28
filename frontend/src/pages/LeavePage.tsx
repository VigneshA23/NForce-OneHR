import { useCallback, useEffect, useRef, useState } from 'react';
import { CalendarDays, CalendarPlus, ChevronLeft, ChevronRight, Pencil, Plus, Trash2, X } from 'lucide-react';
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';
import { useAuthStore } from '../store/authStore';
import { leaveApi, type LeaveType, type LeaveBalance, type LeaveRequestRecord, type SubmitLeaveRequestPayload } from '../api/leave';
import { holidaysApi, type HolidayRow } from '../api/holidays';
import { orgApi, type LocationRow } from '../api/org';
import { useToast } from '../context/ToastContext';
import { PieHoverTooltip } from '../components/PieHoverTooltip';
import { subscribeToNewNotifications } from '../lib/notificationEvents';

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

// Shared by "Request Leave" and "Add Holiday" — both are the primary add-action button for their
// section, so they should match in size/weight rather than drifting independently.
const primaryButtonStyle: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' };

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

const STATUS_COLOR: Record<string, string> = { PENDING: '#E0A93B', APPROVED: '#2FB67C', REJECTED: '#E4373D' };

function StatusBadge({ status }: { status: string }) {
  return (
    <span style={{ fontSize: 11, fontWeight: 600, color: STATUS_COLOR[status] ?? '#9BA1AC', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 4, padding: '2px 7px' }}>
      {status}
    </span>
  );
}

// Same recharts primitives as DashboardPage's LeaveBalancePanel donut (a PieChart + an
// absolutely-positioned center-label overlay), applied to a single balance: two slices
// (Available vs Consumed/Reserved) summing to that leave type's annual quota. The backend
// (LeaveService#availableBalance) is the sole source of truth for both numbers — this component
// only visualizes remainingDays/totalDays as returned by GET /api/leave/balances; it never
// recomputes or re-derives the balance itself.
//
// Dark/light brand-red pair (not the green/amber pair used elsewhere) so the chart reads as
// professional and on-brand: Available gets the darker, more prominent shade since it's the
// actionable number; Consumed/Reserved gets the lighter tint since it's already spent. The same
// two colors double as the swatches in the Available/Consumed line above the chart, so that line
// also serves as the chart's legend.
const BALANCE_DONUT_COLORS = { available: '#7A0C10', consumed: '#E8B4B6' };

function LeaveBalanceDonut({ balance }: { balance: LeaveBalance }) {
  const total = Number(balance.totalDays);
  const available = Math.max(0, Number(balance.remainingDays));
  const consumed = Math.max(0, total - available);
  const data = [
    { name: 'Available', value: available },
    { name: 'Consumed/Reserved', value: consumed },
  ];
  const isEmptyQuota = total <= 0;
  const donutRef = useRef<HTMLDivElement>(null);

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px', display: 'flex', justifyContent: 'center', boxSizing: 'border-box' }}>
      {/* Content is capped/centered, not stretched — the outer card fills its grid track (so the
          section uses the page's available width instead of leaving a blank gap), but the
          heading/legend/chart/quota stack stays compact instead of sprawling on wide screens. */}
      <div style={{ width: '100%', maxWidth: 260 }}>
        <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', textAlign: 'center', marginBottom: 10 }}>
          {balance.leaveTypeName}
        </div>

        {/* Available/Consumed — sits above the chart and doubles as its legend (color swatches
            match the Cell fills below), per the requested Available/Consumed-then-chart order. */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', flexWrap: 'wrap', columnGap: 16, rowGap: 4, marginBottom: 10, fontSize: 12, color: 'var(--txt-mut)' }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, whiteSpace: 'nowrap' }}>
            <span style={{ width: 8, height: 8, borderRadius: 2, background: BALANCE_DONUT_COLORS.available, flexShrink: 0 }} />
            Available: <b style={{ color: 'var(--txt)', fontWeight: 700 }}>{available}</b> day{available === 1 ? '' : 's'}
          </span>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, whiteSpace: 'nowrap' }}>
            <span style={{ width: 8, height: 8, borderRadius: 2, background: BALANCE_DONUT_COLORS.consumed, flexShrink: 0 }} />
            Consumed: <b style={{ color: 'var(--txt)', fontWeight: 700 }}>{consumed}</b> day{consumed === 1 ? '' : 's'}
          </span>
        </div>

        {/* aspect-ratio + ResponsiveContainer (percentage cx/cy/radii), not a fixed pixel
            PieChart — scales with the card instead of relying on a small fixed size, and the 8%
            margin between outerRadius and the container edge means the ring is never clipped. */}
        <div style={{ position: 'relative', width: '100%', maxWidth: 150, aspectRatio: '1 / 1', margin: '0 auto' }} ref={donutRef}>
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie
                data={isEmptyQuota ? [{ name: 'No quota', value: 1 }] : data}
                cx="50%"
                cy="50%"
                innerRadius="58%"
                outerRadius="92%"
                dataKey="value"
                startAngle={90}
                endAngle={-270}
                strokeWidth={0}
              >
                {isEmptyQuota
                  ? <Cell fill="var(--line2)" />
                  : data.map((d, i) => (
                      <Cell key={d.name} fill={i === 0 ? BALANCE_DONUT_COLORS.available : BALANCE_DONUT_COLORS.consumed} />
                    ))}
              </Pie>
              {!isEmptyQuota && (
                <Tooltip
                  /* See DashboardPage's LeaveBalancePanel donut — same left/right-aware custom
                     content, needed because Recharts' own positioning always offsets to the right. */
                  content={props => (
                    <PieHoverTooltip
                      {...props}
                      containerRef={donutRef}
                      formatter={(val, name) => [`${val} day${val === 1 ? '' : 's'}`, name]}
                    />
                  )}
                  allowEscapeViewBox={{ x: true, y: true }}
                />
              )}
            </PieChart>
          </ResponsiveContainer>
          <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', pointerEvents: 'none' }}>
            <span style={{ fontSize: 20, fontWeight: 700, fontFamily: '"Space Grotesk", sans-serif', color: 'var(--txt)', lineHeight: 1 }}>{available}</span>
          </div>
        </div>

        {/* Annual Quota — below the chart, set off by a divider + brand-colored value so it reads
            as distinct from the Available/Consumed legend above while matching the page's palette. */}
        <div style={{ marginTop: 10, paddingTop: 8, borderTop: '1px solid var(--line)', textAlign: 'center', fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)' }}>
          Annual Quota: <span style={{ color: 'var(--brand)', fontWeight: 700 }}>{total}</span> day{total === 1 ? '' : 's'}
        </div>
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
            <button type="submit" disabled={submitting || exceedsBalance} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: (submitting || exceedsBalance) ? 'not-allowed' : 'pointer', opacity: (submitting || exceedsBalance) ? 0.6 : 1 }}>{submitting ? 'Submitting…' : 'Submit Request'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

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

// r.decidedAt is an ISO instant (see LeaveRequestResponse#decidedAt) — formatted for the
// "Approved/Rejected By" column, e.g. "18 Aug 2026, 10:30 AM".
function formatDecisionTimestamp(iso: string) {
  return new Date(iso).toLocaleString(undefined, { day: '2-digit', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit', hour12: true });
}

function daysInMonth(year: number, month: number) {
  return new Date(year, month + 1, 0).getDate();
}

function toISODate(year: number, month: number, day: number) {
  return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

const WEEKDAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

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
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: 12 }}>
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
                background: hasHoliday ? 'color-mix(in srgb, var(--warn) 16%, transparent)' : 'var(--raised)',
                border: isToday ? '2px solid var(--brand)' : hasHoliday ? '1.5px solid var(--warn)' : '1px solid var(--line)',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'flex-start',
                gap: 3,
              }}
            >
              <span style={{ fontSize: 12, fontWeight: isToday ? 700 : 500, color: hasHoliday ? 'var(--warn)' : 'var(--txt)' }}>
                {c.day}
              </span>
              {visible.map(h => (
                <span key={h.id} style={{
                  fontSize: 9, fontWeight: 700, color: '#fff', background: 'var(--warn)',
                  borderRadius: 5, padding: '2px 5px', lineHeight: 1.4,
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                  maxWidth: '100%', width: '100%', boxSizing: 'border-box', flexShrink: 0,
                }}>
                  {h.holidayName}
                </span>
              ))}
              {extraCount > 0 && (
                <span style={{
                  fontSize: 8.5, fontWeight: 700, color: 'var(--warn)',
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

export default function LeavePage() {
  const token = useAuthStore(s => s.token)!;
  const role = useAuthStore(s => s.user?.role);
  const isAdmin = role === 'HR_ADMIN' || role === 'SUPER_ADMIN';
  const [types, setTypes] = useState<LeaveType[]>([]);
  const [balances, setBalances] = useState<LeaveBalance[]>([]);
  const [requests, setRequests] = useState<LeaveRequestRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [leaveError, setLeaveError] = useState('');
  const [showRequest, setShowRequest] = useState(false);
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

  const holidayScopeLabel = isAdmin
    ? (locationFilter ? adminLocations.find(l => l.id === locationFilter)?.name : 'All Locations')
    : holidays[0]?.locationName;

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12, marginBottom: 22 }}>
        <div>
          <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Leave & Holidays</h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>View your balance, request leave, and track approvals.</p>
        </div>
        <button onClick={() => setShowRequest(true)} disabled={types.length === 0} style={{ ...primaryButtonStyle, cursor: types.length === 0 ? 'not-allowed' : 'pointer', opacity: types.length === 0 ? 0.6 : 1 }}>
          <CalendarPlus size={14} /> Request Leave
        </button>
      </div>

      {leaveError && (
        <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13, marginBottom: 14 }}>
          {leaveError}
        </div>
      )}

      {!loading && (
        // auto-fit + 1fr (same responsive-card-row convention as AuditStatCards) — a single
        // balance card fills the row instead of leaving a blank gap beside it, and any future
        // additional balance types would wrap into an even multi-column row instead of overflowing.
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 12, marginBottom: 22 }}>
          {balances.map(b => <LeaveBalanceDonut key={b.leaveTypeCode} balance={b} />)}
        </div>
      )}

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : requests.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>No leave requests yet</div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>Click "Request Leave" to submit your first request.</div>
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
                {requests.map(r => (
                  <tr key={r.id}>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.leaveTypeName}</td>
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
          </div>
        )}
      </div>

      {showRequest && (
        <RequestLeaveModal types={types} balances={balances} token={token} onClose={() => setShowRequest(false)} onCreated={handleCreated} />
      )}

      <div style={{ marginTop: 28 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14, flexWrap: 'wrap', gap: 10 }}>
          <div>
            <h2 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 16, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>
              Company Holidays{holidayScopeLabel ? ` — ${holidayScopeLabel}` : ''}
            </h2>
            <p style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 4 }}>
              {isAdmin ? 'Holidays across the company, by location.' : 'Holidays for your work location.'}
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
              <button onClick={() => setShowAddHoliday(true)} style={primaryButtonStyle}>
                <Plus size={14} /> Add Holiday
              </button>
            )}
          </div>
        </div>

        {holidayError && (
          <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13, marginBottom: 14 }}>
            {holidayError}
          </div>
        )}

        <div className={holidays.length > 0 ? 'nf-grid-side-collapse' : undefined} style={holidays.length > 0 ? { display: 'grid', gridTemplateColumns: 'minmax(310px, 380px) 1fr', gap: 20, alignItems: 'start' } : undefined}>
          {holidays.length > 0 && <HolidayMonthCalendar holidays={holidays} />}

          <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
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

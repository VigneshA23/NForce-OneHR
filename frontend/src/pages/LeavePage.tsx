import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import PageTitleBlock from '../components/PageTitleBlock';
import DashTile from '../components/DashTile';
import {
  CalendarClock, CalendarPlus, CheckCircle2, ChevronLeft, ChevronRight, History, Hourglass, Pencil, Search,
  Wallet, X, CalendarDays, ListChecks, Paperclip
} from 'lucide-react';
import './leaveModal.css';
import { ActionDock } from '../components/ActionDock';
import { useAuthStore } from '../store/authStore';
import { leaveApi, type LeaveType, type LeaveBalance, type LeaveRequestRecord, type SubmitLeaveRequestPayload } from '../api/leave';
import { useToast } from '../context/ToastContext';
import { subscribeToNewNotifications } from '../lib/notificationEvents';
import { daysByYear, roundDays, viewableBalanceYears, yearBreakdownLabel } from '../utils/leaveDays';
import { attachmentProblem, hourlyDays, ruleLines } from './leave-config/planRules';

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
const thStyle: React.CSSProperties = { padding: '12px 16px', textAlign: 'left', fontSize: 11, fontWeight: 800, color: 'var(--brand)', textTransform: 'uppercase', letterSpacing: '.09em', whiteSpace: 'nowrap', borderBottom: '1px solid color-mix(in srgb, var(--brand) 22%, var(--line))', background: 'color-mix(in srgb, var(--brand) 7%, var(--raised))' };
const tdStyle: React.CSSProperties = { padding: '14px 16px', fontSize: 13.5, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

// Primary add-action button for this page — "Request Leave".
const primaryButtonStyle: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 7, background: 'linear-gradient(135deg, var(--brand), color-mix(in srgb, var(--brand) 70%, #000))', boxShadow: '0 6px 14px color-mix(in srgb, var(--brand) 28%, transparent)', color: '#fff', border: 'none', borderRadius: 10, padding: '9px 18px', fontSize: 13, fontWeight: 700, cursor: 'pointer' };

const PAGE_SIZE = 5;
const BALANCE_ACCENTS = ['var(--brand)', 'var(--info)', 'var(--ok)', 'var(--warn)'];

function ModalHeader({ title, subtitle, icon: Icon = CalendarPlus, onClose }: { title: string; subtitle?: string; icon?: React.ComponentType<{ size?: number }>; onClose: () => void }) {
  return (
    <div className="nf-lv-head">
      <span className="nf-lv-ico"><Icon size={20} /></span>
      <div className="nf-lv-titles">
        <div className="nf-lv-title">{title}</div>
        {subtitle && <div className="nf-lv-sub">{subtitle}</div>}
      </div>
      <button onClick={onClose} className="nf-lv-x" aria-label="Close"><X size={16} /></button>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><label style={labelStyle} className="nf-lv-lbl">{label}</label>{children}</div>;
}

// Every paid leave type has its own balance (V310) — a request always draws on its own type's row.
// Regular paid (ANNUAL) balances are the ones that count toward "available leave" and the
// use-paid-leave-first rule; unpaid rows are unlimited and Maternity-style rows are per occurrence.
function isRegularPaid(b: LeaveBalance): boolean {
  return !b.unlimited && (b.entitlementBasis ?? 'ANNUAL') === 'ANNUAL';
}

function shortDate(iso: string): string {
  return new Date(iso + 'T00:00:00').toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

const STATUS_COLOR: Record<string, string> = { PENDING: 'var(--warn)', APPROVED: 'var(--ok)', REJECTED: 'var(--risk)', PENALIZED: 'var(--risk)' };

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

// Same tile as the Home dashboard (components/DashTile); display only.
export function KpiTile({ icon: Icon, label, value, sub, accent }: {
  icon: React.ComponentType<{ size?: number; style?: React.CSSProperties }>;
  label: string; value: string; sub?: string; accent: string;
}) {
  return <DashTile className="nf-section-enter" icon={<Icon size={20} />} label={label} value={value} sub={sub} accent={accent} />;
}

function SectionHeading({ icon: Icon, title }: { icon: React.ComponentType<{ size?: number }>; title: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
      <span className="nf-opp-ico"><Icon size={18} /></span>
      <h2 style={{ fontFamily: 'Inter, sans-serif', fontSize: 18, fontWeight: 800, letterSpacing: '-.01em', color: 'var(--txt)', margin: 0 }}>{title}</h2>
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
  if (balance.unlimited) {
    return (
      <div className="nf-section-enter nf-leave-balance-card" style={{
        background: 'var(--panel)', border: '1px solid var(--line)', borderTop: '3px solid var(--line2)', borderRadius: 16,
        boxShadow: '0 6px 22px rgba(16, 24, 40, .07)', padding: '18px 20px', display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 6,
      }}>
        <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em' }}>{balance.leaveTypeName}</div>
        <div style={{ fontSize: 18, fontWeight: 800, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>Unlimited</div>
        <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>Unpaid — no balance limit, never uses your paid leave.</div>
      </div>
    );
  }
  if (balance.expected && balance.entitlementBasis !== 'PER_OCCURRENCE') {
    return <ExpectedBalanceCard balance={balance} accent={accent} />;
  }
  const perOccurrence = balance.entitlementBasis === 'PER_OCCURRENCE';
  const carried = Number(balance.carriedForwardDays ?? 0);
  const pending = Number(balance.pendingDays ?? 0);
  // Ring total = days credited so far this year + carried-forward, so "available of total" adds up.
  const total = Number(balance.totalDays) + carried;
  const balanceDays = Number(balance.remainingDays);
  const available = Math.max(0, balanceDays);
  // V314: monthly crediting. A negative balance = days taken in advance of credits still to come.
  const inAdvance = balanceDays < 0 ? roundDays(-balanceDays) : 0;
  const monthly = balance.creditFrequency === 'MONTHLY';
  const quota = balance.annualQuotaDays != null ? Number(balance.annualQuotaDays) : null;
  // roundDays strips the IEEE-754 noise this subtraction can reintroduce even on two already-exact
  // BigDecimal-derived values (e.g. 15 - 13.7 rendering as 1.3000000000000007) - see utils/leaveDays.ts.
  const consumed = roundDays(Math.max(0, total - available));
  const isEmptyQuota = total <= 0 && !(quota != null && quota > 0);
  const availablePct = isEmptyQuota ? 0 : Math.min(100, Math.round((available / total) * 100));
  const ringOffset = RING_CIRCUMFERENCE * (1 - availablePct / 100);
  const ringColor = isEmptyQuota ? 'var(--line2)' : availablePct <= 15 ? 'var(--risk)' : accent;

  return (
    <div className="nf-section-enter nf-leave-balance-card" style={{
      background: `linear-gradient(160deg, var(--panel) 0%, color-mix(in srgb, ${accent} 5%, var(--panel)) 100%)`,
      border: '1px solid var(--line)', borderTop: `3px solid ${ringColor}`, borderRadius: 16, boxShadow: '0 6px 22px rgba(16, 24, 40, .07)',
      padding: '18px 20px', display: 'flex', alignItems: 'center', gap: 18,
    }}>
      <div style={{ position: 'relative', width: 72, height: 72, flexShrink: 0 }}>
        <svg width={72} height={72} viewBox="0 0 72 72" style={{ transform: 'rotate(-90deg)' }}>
          <circle cx={36} cy={36} r={RING_RADIUS} fill="none" stroke="var(--raised2)" strokeWidth={7} />
          {!isEmptyQuota && (
            <circle className="nf-ring-progress" cx={36} cy={36} r={RING_RADIUS} fill="none" stroke={ringColor} strokeWidth={7} strokeLinecap="round" strokeDasharray={RING_CIRCUMFERENCE} strokeDashoffset={ringOffset} />
          )}
        </svg>
        <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', pointerEvents: 'none' }}>
          <span style={{ fontSize: 19, fontWeight: 800, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', lineHeight: 1 }}>{available}</span>
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
              {pending > 0 ? 'Used & pending' : 'Consumed'} <span style={{ marginLeft: 'auto', color: 'var(--txt)', fontWeight: 700 }}>{consumed}d</span>
            </div>
            {pending > 0 && (
              <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 4 }}>{roundDays(pending)}d awaiting approval</div>
            )}
            {inAdvance > 0 && (
              <div style={{ fontSize: 11, color: 'var(--warn, #b7791f)', marginTop: 4, fontWeight: 600 }}>
                {inAdvance}d taken in advance, covered by your next credits
              </div>
            )}
            {!perOccurrence && monthly && quota != null && (
              <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 4 }}>
                {roundDays(Number(balance.totalDays))}d of {roundDays(quota)}d credited this year
                {balance.nextCreditDate && balance.nextCreditDays != null
                  ? ` · next +${roundDays(Number(balance.nextCreditDays))}d on ${shortDate(balance.nextCreditDate)}` : ''}
              </div>
            )}
            {carried > 0 && (
              <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 4 }}>
                Includes {roundDays(carried)}d carried forward ({roundDays(Number(balance.carriedForwardRemainingDays ?? 0))}d left
                {balance.carriedForwardExpiresOn ? `, expires ${shortDate(balance.carriedForwardExpiresOn)}` : ''})
              </div>
            )}
            {perOccurrence && (
              <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 4 }}>
                {Number(balance.totalDays)}d per eligible occurrence
                {balance.occurrenceStartDate && balance.occurrenceWindowEndDate
                  ? ` · current occurrence ${shortDate(balance.occurrenceStartDate)} – ${shortDate(balance.occurrenceWindowEndDate)}`
                  : ' · full entitlement available'}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

// YE-04: a leave year that hasn't started. Nothing is credited yet, so the card shows the year's
// expected quota and what is left of it after leave already requested in that year. Read-only.
function ExpectedBalanceCard({ balance, accent }: { balance: LeaveBalance; accent: string }) {
  const quota = Number(balance.annualQuotaDays ?? 0);
  const pending = Number(balance.pendingDays ?? 0);
  const used = Number(balance.usedDays ?? 0);
  const available = roundDays(Math.max(0, Number(balance.availableToApplyDays ?? quota - pending - used)));
  const pct = quota > 0 ? Math.min(100, Math.round((available / quota) * 100)) : 0;
  const monthly = balance.creditFrequency === 'MONTHLY';
  return (
    <div className="nf-section-enter nf-leave-balance-card" style={{
      background: 'var(--panel)', border: '1px dashed var(--line2)', borderTop: `3px dashed ${accent}`, borderRadius: 16,
      boxShadow: '0 6px 22px rgba(16, 24, 40, .07)', padding: '18px 20px', display: 'flex', alignItems: 'center', gap: 18,
    }}>
      <div style={{ position: 'relative', width: 72, height: 72, flexShrink: 0 }}>
        <svg width={72} height={72} viewBox="0 0 72 72" style={{ transform: 'rotate(-90deg)' }}>
          <circle cx={36} cy={36} r={RING_RADIUS} fill="none" stroke="var(--raised2)" strokeWidth={7} />
          {quota > 0 && <circle cx={36} cy={36} r={RING_RADIUS} fill="none" stroke={accent} strokeOpacity={0.55} strokeWidth={7} strokeLinecap="round" strokeDasharray={RING_CIRCUMFERENCE} strokeDashoffset={RING_CIRCUMFERENCE * (1 - pct / 100)} />}
        </svg>
        <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', pointerEvents: 'none' }}>
          <span style={{ fontSize: 19, fontWeight: 800, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', lineHeight: 1 }}>{available}</span>
          <span style={{ fontSize: 8.5, color: 'var(--txt-dim)', marginTop: 1 }}>of {roundDays(quota)}d</span>
        </div>
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 8, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={balance.leaveTypeName}>
          {balance.leaveTypeName}
        </div>
        {quota <= 0 ? (
          <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>No quota assigned for {balance.year}</div>
        ) : (
          <>
            <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginBottom: 4 }}>
              Expected <b style={{ color: 'var(--txt)' }}>{roundDays(quota)}d</b> in {balance.year}
            </div>
            <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>
              {monthly ? `Credited monthly from 1 Jan ${balance.year}` : `Credited on 1 Jan ${balance.year}`}
            </div>
            {pending + used > 0 && (
              <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 4 }}>{roundDays(pending + used)}d already requested for {balance.year}</div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

/** Balance of one type in one leave year, for the request form's per-year check (YE-03/YE-04). */
type YearCheck = {
  year: number; days: number; balance: LeaveBalance | null; loading: boolean;
  /** Credited balance left (current year) or expected balance left (a year that hasn't started). */
  available: number; applyLimit: number; exceeds: boolean; advance: number;
};

/** Same wording as the backend's per-year check (LeaveBalanceService#requireAvailable). */
function notEnoughMessage(c: YearCheck): string {
  const left = roundDays(Math.max(0, c.applyLimit));
  return `Not enough ${c.year} ${c.balance?.leaveTypeName ?? 'leave'}: ${left} day${left === 1 ? '' : 's'} available, ${roundDays(c.days)} requested.`;
}

function RequestLeaveModal({ types, balances, currentYear, editingRequest, onClose, onSaved, token }: {
  types: LeaveType[]; balances: LeaveBalance[]; currentYear: number; editingRequest?: LeaveRequestRecord | null;
  onClose: () => void; onSaved: (r: LeaveRequestRecord, wasEdit: boolean) => void; token: string;
}) {
  const { showToast } = useToast();
  const isEdit = !!editingRequest;
  // Local calendar date, not new Date().toISOString().slice(0, 10) — the ISO/UTC form can land
  // on the wrong side of midnight relative to the user's actual local day, which would let the
  // date picker's min slip a day off from what "today" really is.
  const now = new Date();
  const today = toISODate(now.getFullYear(), now.getMonth(), now.getDate());
  const [form, setForm] = useState<SubmitLeaveRequestPayload>(editingRequest ? {
    leaveTypeCode: editingRequest.leaveTypeCode,
    startDate: editingRequest.startDate,
    endDate: editingRequest.endDate,
    halfDay: editingRequest.halfDay,
    halfDaySession: editingRequest.halfDaySession ?? undefined,
    hours: editingRequest.hours ?? null,
    reason: editingRequest.employeeReason,
  } : { leaveTypeCode: types[0]?.code ?? '', startDate: today, endDate: today, halfDay: false, halfDaySession: undefined, hours: null, reason: '' });
  const [file, setFile] = useState<File | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // YE-03/YE-04: a request is charged to the balance of the leave year each of its days falls in, so
  // the form checks (and shows) the balance of every year the chosen dates touch — not just the
  // current year's. Other years' balances are fetched on demand (the next year is a read-only
  // "expected" view); a year that can't be fetched is left to the backend's own check.
  const [balancesByYear, setBalancesByYear] = useState<Record<number, LeaveBalance[] | 'error'>>({ [currentYear]: balances });
  useEffect(() => { setBalancesByYear(prev => ({ ...prev, [currentYear]: balances })); }, [balances, currentYear]);
  // LV-02: the selected type's rules from the employee's plan (null = no limits).
  const rules = types.find(t => t.code === form.leaveTypeCode)?.rules ?? null;
  const hourly = form.hours != null;
  const singleDay = form.halfDay || hourly;
  const effectiveEndDate = singleDay ? form.startDate : form.endDate;
  const hoursDays = hourly ? hourlyDays(Number(form.hours), rules?.hoursPerDay ?? 8) : null;
  const segments = useMemo(() => hoursDays != null
    ? new Map([[Number(form.startDate.slice(0, 4)), hoursDays]])
    : daysByYear(form.startDate, effectiveEndDate, form.halfDay), [form.startDate, effectiveEndDate, form.halfDay, hoursDays]);
  const segmentYearsKey = [...segments.keys()].join(',');
  useEffect(() => {
    for (const y of segments.keys()) {
      if (balancesByYear[y] !== undefined) continue;
      leaveApi.listBalances(token, y)
        .then(b => setBalancesByYear(prev => ({ ...prev, [y]: b })))
        .catch(() => setBalancesByYear(prev => ({ ...prev, [y]: 'error' })));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [segmentYearsKey, token]);

  // While editing, the request being edited is still PENDING and already reserved against its own
  // balance in each year it touches — add its own days in that year back, so editing a request that
  // already uses its full balance isn't wrongly flagged as over-limit. Mirrors the backend's own
  // exclusion (LeaveService#update), keyed off the request's ORIGINAL type/days. A no-op for an
  // originally-Unpaid request, which never reserved against any balance row.
  const editingDaysByYear = useMemo(() => editingRequest && editingRequest.leaveTypeClassification === 'PAID'
    ? daysByYear(editingRequest.startDate, editingRequest.endDate, editingRequest.halfDay)
    : new Map<number, number>(), [editingRequest]);
  const balanceFor = (year: number, typeCode: string): LeaveBalance | null | undefined => {
    const list = balancesByYear[year];
    if (list === undefined) return undefined; // still loading
    if (list === 'error') return null;
    const b = list.find(x => x.leaveTypeCode === typeCode && !x.unlimited && x.remainingDays != null);
    if (!b) return null;
    const back = editingRequest && editingRequest.leaveTypeCode === typeCode ? (editingDaysByYear.get(year) ?? 0) : 0;
    return back > 0 ? {
      ...b,
      remainingDays: Number(b.remainingDays) + back,
      availableToApplyDays: b.availableToApplyDays != null ? Number(b.availableToApplyDays) + back : b.availableToApplyDays,
    } : b;
  };

  // Early-UX only, mirroring the backend's own per-year check (LeaveBalanceService#requireAvailable) —
  // the backend independently re-validates regardless, so this can never enforce the limit by itself.
  // Unlimited (unpaid) and per-occurrence types have no yearly balance and are skipped here.
  const selectedType = types.find(t => t.code === form.leaveTypeCode);
  const yearChecks: YearCheck[] = [...segments.entries()].map(([year, days]) => {
    const b = balanceFor(year, form.leaveTypeCode);
    if (!b) return { year, days, balance: null, loading: b === undefined, available: 0, applyLimit: Infinity, exceeds: false, advance: 0 };
    const remaining = Number(b.remainingDays);
    // Monthly-credited leave may use the rest of the year's quota in advance (availableToApplyDays);
    // in a year that hasn't started that is the whole expected quota less what is already requested.
    const applyLimit = Number(b.availableToApplyDays ?? b.remainingDays);
    const available = b.expected ? applyLimit : remaining;
    const exceeds = days > applyLimit;
    const advance = !b.expected && !exceeds ? roundDays(Math.max(0, days - Math.max(0, remaining))) : 0;
    return { year, days, balance: b, loading: false, available, applyLimit, exceeds, advance };
  });
  const checked = yearChecks.filter(c => c.balance && (c.balance.entitlementBasis ?? 'ANNUAL') === 'ANNUAL');
  const multiYear = yearChecks.length > 1;
  const shortYear = checked.find(c => c.exceeds);
  const exceedsBalance = !!shortYear;
  const advanceDays = roundDays(checked.reduce((s, c) => s + c.advance, 0));
  const requestedDays = [...segments.values()].reduce((s, d) => s + d, 0);

  // Mirrors the backend's "use paid leave before unpaid" rule (LeaveService#validateEntitlement), which
  // looks at the paid balances of the leave year the request STARTS in.
  const startYear = Number(form.startDate.slice(0, 4));
  const startYearBalances = balancesByYear[startYear];
  const hasAnyPaidBalance = Array.isArray(startYearBalances)
    && startYearBalances.some(b => isRegularPaid(b) && Number(b.remainingDays) > 0);
  const blockedUnpaidWithPaidBalance = selectedType?.classification === 'UNPAID' && hasAnyPaidBalance;

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!form.reason.trim()) { setError('A reason is required.'); return; }
    // Early-UX only, same as the balance check below — the backend independently re-validates
    // this against its own (timezone-correct) notion of "today" regardless of what's checked here.
    if (form.startDate < today) { setError('Leave cannot be requested for a date before today.'); return; }
    if (form.halfDay && !form.halfDaySession) { setError('Select First Half or Second Half.'); return; }
    if (hourly && !(Number(form.hours) > 0 && Number(form.hours) < (rules?.hoursPerDay ?? 8))) {
      setError(`Enter the hours: more than 0 and less than ${rules?.hoursPerDay ?? 8}.`);
      return;
    }
    const fileProblem = file && rules ? attachmentProblem(rules, selectedType?.name ?? 'This leave', file.name, file.size) : null;
    if (fileProblem) { setError(fileProblem); return; }
    if (attachmentNeeded && !file && !editingRequest?.attachmentName) {
      setError(`${selectedType?.name ?? 'This leave'} needs an attachment for this request.`);
      return;
    }
    if (shortYear && shortYear.balance) {
      setError(notEnoughMessage(shortYear));
      return;
    }
    if (blockedUnpaidWithPaidBalance) {
      setError('You cannot apply for unpaid leave while you have an available paid leave balance.');
      return;
    }
    setSubmitting(true); setError(null);
    try {
      const payload = { ...form, endDate: singleDay ? form.startDate : form.endDate, hours: hourly ? Number(form.hours) : null };
      const saved = editingRequest
        ? await leaveApi.update(editingRequest.id, payload, token, file)
        : await leaveApi.submit(payload, token, file);
      onSaved(saved, isEdit);
      showToast('success', isEdit ? 'Leave request updated' : 'Leave request submitted');
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Submit failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  const dayCount = Number.isFinite(requestedDays) && requestedDays > 0 ? requestedDays : null;
  const ruleList = rules ? ruleLines(rules) : [];
  const attachmentNeeded = rules?.attachmentRequiredOverDays != null && dayCount != null && dayCount > rules.attachmentRequiredOverDays;
  const halfDayAllowed = rules ? rules.allowHalfDay : true;
  const hourlyAllowed = rules?.allowHourly ?? false;

  // Portaled to the body: an ancestor that has finished its entrance animation keeps a resolved transform,
  // which would otherwise make this fixed overlay position against that ancestor (header clipped under the top bar).
  return createPortal(
    <div className="nf-lv-dlg" style={overlayStyle}>
      <div className="nf-lv-card" style={modalStyle}>
        <ModalHeader
          title={isEdit ? 'Edit Leave Request' : 'Request Leave'}
          subtitle={isEdit ? 'Update your pending leave request.' : 'Pick the leave type, dates and a short reason.'}
          icon={isEdit ? Pencil : CalendarPlus}
          onClose={onClose}
        />
        <form onSubmit={handleSubmit}>
          <div className="nf-lv-form">
            {error && <div className="nf-lv-err">{error}</div>}

            <section className="nf-lv-sec">
              <div className="nf-lv-sec-hd"><span className="nf-lv-sec-ico"><Wallet size={15} /></span><span className="nf-lv-sec-title">Leave type</span></div>
              <Field label="Leave Type *">
                <select style={inputStyle} value={form.leaveTypeCode} onChange={e => {
                  const next = types.find(t => t.code === e.target.value)?.rules ?? null;
                  // Drop a half day / hours the newly chosen type doesn't allow.
                  setForm(f => ({
                    ...f, leaveTypeCode: e.target.value,
                    halfDay: f.halfDay && (next ? next.allowHalfDay : true),
                    halfDaySession: f.halfDay && (next ? next.allowHalfDay : true) ? f.halfDaySession : undefined,
                    hours: f.hours != null && next?.allowHourly ? f.hours : null,
                  }));
                }}>
                  {types.map(t => <option key={t.code} value={t.code}>{t.name}{t.classification === 'UNPAID' ? ' (Unpaid)' : ''}</option>)}
                </select>
              </Field>
              {checked.map(c => (
                <div key={c.year} className={`nf-lv-bal${c.exceeds ? ' over' : ''}`}>
                  {c.year} {c.balance!.leaveTypeName}: {roundDays(Math.max(0, c.available))} day{roundDays(Math.max(0, c.available)) === 1 ? '' : 's'} {c.balance!.expected ? 'expected' : 'available'}
                  {!c.balance!.expected && c.applyLimit > Math.max(0, c.available) && (
                    <> · up to {roundDays(c.applyLimit)} including credits still to come this year</>
                  )}
                  {multiYear && <> · this request uses {roundDays(c.days)}</>}
                  {c.exceeds && ' - not enough for this request'}
                </div>
              ))}
              {yearChecks.some(c => c.loading) && <div className="nf-lv-bal">Checking balance…</div>}
              {advanceDays > 0 && (
                <div className="nf-lv-warn">
                  {advanceDays} day{advanceDays === 1 ? '' : 's'} of this request will be taken in advance and covered by your upcoming monthly credits.
                </div>
              )}
              {blockedUnpaidWithPaidBalance && (
                <div className="nf-lv-warn">
                  You cannot apply for unpaid leave while you have an available paid leave balance.
                </div>
              )}
              {ruleList.length > 0 && (
                <div className="nf-lv-bal" data-testid="leave-rules" style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
                  <ListChecks size={14} style={{ flexShrink: 0, marginTop: 2 }} />
                  <span>{ruleList.join(' · ')}</span>
                </div>
              )}
            </section>

            <section className="nf-lv-sec">
              <div className="nf-lv-sec-hd">
                <span className="nf-lv-sec-ico"><CalendarDays size={15} /></span><span className="nf-lv-sec-title">Dates</span>
                {dayCount != null && <span className="nf-lv-days">{dayCount} day{dayCount === 1 ? '' : 's'}</span>}
              </div>
              <div className="nf-lv-grid">
                <Field label="Start Date *">
                  <input type="date" min={today} style={inputStyle} value={form.startDate} onChange={e => setForm(f => ({ ...f, startDate: e.target.value, endDate: f.halfDay || f.hours != null ? e.target.value : f.endDate }))} />
                </Field>
                <Field label="End Date *">
                  <input type="date" style={inputStyle} value={singleDay ? form.startDate : form.endDate} disabled={singleDay} min={form.startDate} onChange={e => setForm(f => ({ ...f, endDate: e.target.value }))} />
                </Field>
              </div>
              {halfDayAllowed && (
                <label htmlFor="halfDay" className={`nf-lv-half${form.halfDay ? ' on' : ''}`} style={{ marginTop: 12, marginBottom: 0 }}>
                  <input id="halfDay" type="checkbox" checked={form.halfDay} onChange={e => setForm(f => ({ ...f, halfDay: e.target.checked, hours: e.target.checked ? null : f.hours, halfDaySession: e.target.checked ? f.halfDaySession : undefined, endDate: e.target.checked ? f.startDate : f.endDate }))} />
                  <span style={{ display: 'block' }}><b>Half day</b><span>Take only the first or second half of the day.</span></span>
                </label>
              )}
              {hourlyAllowed && (
                <label htmlFor="hourly" className={`nf-lv-half${hourly ? ' on' : ''}`} style={{ marginTop: 12, marginBottom: 0 }}>
                  <input id="hourly" type="checkbox" checked={hourly} onChange={e => setForm(f => ({ ...f, hours: e.target.checked ? (rules?.minHours ?? 2) : null, halfDay: e.target.checked ? false : f.halfDay, halfDaySession: e.target.checked ? undefined : f.halfDaySession, endDate: e.target.checked ? f.startDate : f.endDate }))} />
                  <span style={{ display: 'block' }}><b>By the hour</b><span>A few hours on one day ({rules?.hoursPerDay ?? 8} hours = 1 day).</span></span>
                </label>
              )}
              {hourly && (
                <div style={{ marginTop: 10, maxWidth: 160 }}>
                  <Field label="Hours *">
                    <input type="number" min={rules?.minHours ?? 0.5} max={(rules?.hoursPerDay ?? 8) - 0.5} step={0.5} style={inputStyle}
                      value={form.hours ?? ''} aria-label="Hours"
                      onChange={e => setForm(f => ({ ...f, hours: e.target.value === '' ? 0 : Number(e.target.value) }))} />
                  </Field>
                </div>
              )}
              {form.halfDay && (
                <div className="nf-lv-sessions">
                  <label>
                    <input type="radio" name="halfDaySession" checked={form.halfDaySession === 'FIRST_HALF'} onChange={() => setForm(f => ({ ...f, halfDaySession: 'FIRST_HALF' }))} />
                    First Half
                  </label>
                  <label>
                    <input type="radio" name="halfDaySession" checked={form.halfDaySession === 'SECOND_HALF'} onChange={() => setForm(f => ({ ...f, halfDaySession: 'SECOND_HALF' }))} />
                    Second Half
                  </label>
                </div>
              )}
            </section>

            <section className="nf-lv-sec">
              <div className="nf-lv-sec-hd"><span className="nf-lv-sec-ico"><Paperclip size={15} /></span><span className="nf-lv-sec-title">Attachment</span></div>
              <Field label={attachmentNeeded ? 'Attachment *' : 'Attachment (optional)'}>
                <input type="file" style={inputStyle} aria-label="Attachment"
                  accept={rules?.attachmentAllowedTypes.length ? rules.attachmentAllowedTypes.map(e => `.${e}`).join(',') : undefined}
                  onChange={e => setFile(e.target.files?.[0] ?? null)} />
              </Field>
              <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>
                {editingRequest?.attachmentName && !file ? `Attached: ${editingRequest.attachmentName}. Choose a file to replace it. ` : ''}
                {attachmentNeeded ? `Needed for ${selectedType?.name ?? 'this leave'} over ${rules!.attachmentRequiredOverDays} day(s). ` : ''}
                {rules?.attachmentAllowedTypes.length ? `${rules.attachmentAllowedTypes.map(e => e.toUpperCase()).join(', ')}, ` : ''}
                max {rules?.attachmentMaxMb ?? 10} MB.
              </div>
            </section>

            <section className="nf-lv-sec">
              <div className="nf-lv-sec-hd"><span className="nf-lv-sec-ico"><Pencil size={15} /></span><span className="nf-lv-sec-title">Reason</span></div>
              <Field label="Reason *">
                <textarea style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }} value={form.reason} onChange={e => setForm(f => ({ ...f, reason: e.target.value }))} placeholder="Reason for leave" />
              </Field>
            </section>
          </div>

          <div className="nf-lv-foot">
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={submitting || exceedsBalance || blockedUnpaidWithPaidBalance} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: (submitting || exceedsBalance || blockedUnpaidWithPaidBalance) ? 'not-allowed' : 'pointer', opacity: (submitting || exceedsBalance || blockedUnpaidWithPaidBalance) ? 0.6 : 1 }}>
              {submitting ? (isEdit ? 'Saving…' : 'Submitting…') : (isEdit ? 'Save Changes' : 'Submit Request')}
            </button>
          </div>
        </form>
      </div>
    </div>,
    document.body,
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
  const [editingRequest, setEditingRequest] = useState<LeaveRequestRecord | null>(null);
  // YE-04: the balance cards can switch to next year's (read-only, expected) balances from 1 November.
  const balanceYears = useMemo(() => viewableBalanceYears(), []);
  const currentYear = balanceYears[0];
  const [balanceYear, setBalanceYear] = useState(currentYear);
  const [otherYearBalances, setOtherYearBalances] = useState<Record<number, LeaveBalance[]>>({});
  const [otherYearError, setOtherYearError] = useState('');
  useEffect(() => {
    if (balanceYear === currentYear || otherYearBalances[balanceYear]) return;
    setOtherYearError('');
    leaveApi.listBalances(token, balanceYear)
      .then(b => setOtherYearBalances(prev => ({ ...prev, [balanceYear]: b })))
      .catch(e => setOtherYearError(e instanceof Error ? e.message : 'Failed to load balances'));
  }, [balanceYear, currentYear, otherYearBalances, token]);
  const shownBalances = balanceYear === currentYear ? balances : (otherYearBalances[balanceYear] ?? []);

  useEffect(() => {
    setLeaveError('');
    Promise.all([leaveApi.listMyTypes(token), leaveApi.listBalances(token), leaveApi.listMine(token)])
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
      setOtherYearBalances({}); // re-fetched on demand, with any new request reserved
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
  async function handleSaved(r: LeaveRequestRecord, wasEdit: boolean) {
    setRequests(prev => wasEdit ? prev.map(x => x.id === r.id ? r : x) : [r, ...prev]);
    await refreshLeaveData();
  }

  // ── History filters — presentation-only, operate on the `requests` array already loaded
  // above via the existing leaveApi.listMine()/refreshLeaveData() calls; no new API calls. ──
  const [typeFilter, setTypeFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);

  // ── Own-leave KPIs — derived client-side from `balances`/`requests` already loaded above. ──
  // Regular paid balances only: unlimited unpaid leave and per-occurrence (Maternity) entitlement aren't a yearly allowance.
  const totalAvailable = useMemo(() => roundDays(balances.filter(isRegularPaid).reduce((s, b) => s + Math.max(0, Number(b.remainingDays)), 0)), [balances]);
  const totalUsed = useMemo(() => roundDays(balances.filter(isRegularPaid).reduce((s, b) => s + Math.max(0, Number(b.usedDays)), 0)), [balances]);
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
  // The Actions column only exists when something on this page can be acted on (pending requests can be edited).
  const showActions = pageRows.some(r => r.status === 'PENDING');

  function updateFilterAndResetPage(setter: (v: string) => void, value: string) { setter(value); setPage(1); }

  return (
    <div>
      <div className="nf-section-enter nf-leave-header nf-page-hdr" style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 22,
        background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: '18px 22px',
        backgroundImage: 'linear-gradient(120deg, color-mix(in srgb, var(--brand) 6%, var(--panel)) 0%, var(--panel) 55%)',
      }}>
        <PageTitleBlock icon={CalendarDays}>
            <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Leave</h1>
            <p style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 3 }}>View your balance, request leave, and track approvals.</p>
        </PageTitleBlock>
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
            value={upcoming ? new Date(upcoming.startDate + 'T00:00:00').toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) : '–'}
            sub={upcoming ? upcoming.leaveTypeName : 'Nothing scheduled'} accent="var(--brand)"
          />
        </div>
      )}

      {!loading && (
        <div style={{ marginBottom: 22 }}>
          <div style={{ marginBottom: 14, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10, flexWrap: 'wrap' }}>
            <SectionHeading icon={Wallet} title="Leave Balances" />
            {balanceYears.length > 1 && (
              <div role="group" aria-label="Balance year" style={{ display: 'inline-flex', border: '1px solid var(--line2)', borderRadius: 8, overflow: 'hidden' }}>
                {balanceYears.map(y => (
                  <button key={y} type="button" aria-pressed={balanceYear === y} onClick={() => setBalanceYear(y)} style={{
                    border: 'none', padding: '6px 14px', fontSize: 12.5, fontWeight: 700, cursor: 'pointer',
                    background: balanceYear === y ? 'var(--brand)' : 'var(--panel)', color: balanceYear === y ? '#fff' : 'var(--txt-mut)',
                  }}>{y}</button>
                ))}
              </div>
            )}
          </div>
          {balanceYear !== currentYear && otherYearError && (
            <div role="alert" style={{ color: 'var(--risk)', fontSize: 13, marginBottom: 10 }}>{otherYearError}</div>
          )}
          {balanceYear !== currentYear && shownBalances.length > 0 && (
            <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginBottom: 10 }}>
              {balanceYear} hasn&apos;t started yet: these are the days you are expected to receive, less leave you have already requested for {balanceYear}. If your leave policy carries unused {currentYear} days forward, they are added on 1 January.
            </div>
          )}
          {shownBalances.length === 0 ? (
            <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: 40, textAlign: 'center' }}>
              <Wallet size={26} style={{ color: 'var(--line2)', display: 'block', margin: '0 auto 10px' }} />
              <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>No leave balances configured.</div>
            </div>
          ) : (
            <div className="nf-autofit-mobile-safe" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 12 }}>
              {shownBalances.map((b, i) => <LeaveBalanceRingCard key={b.leaveTypeCode} balance={b} accent={BALANCE_ACCENTS[i % BALANCE_ACCENTS.length]} />)}
            </div>
          )}
        </div>
      )}

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
        <SectionHeading icon={History} title="Leave History" />
        {!loading && requests.length > 0 && (
          <div className="nf-leave-filter-bar" style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'nowrap', flex: '1 1 auto', minWidth: 0, justifyContent: 'flex-end' }}>
            <label className="nf-filter-search" style={{ minWidth: 170, flex: '0 1 260px' }}>
              <Search size={15} />
              <input value={search} onChange={e => { setSearch(e.target.value); setPage(1); }} placeholder="Search reason or type…" />
            </label>
            <select className="nf-filter-select" style={{ flexShrink: 0 }} value={typeFilter} onChange={e => updateFilterAndResetPage(setTypeFilter, e.target.value)}>
              <option value="">All Types</option>
              {types.map(t => <option key={t.code} value={t.code}>{t.name}{t.classification === 'UNPAID' ? ' (Unpaid)' : ''}</option>)}
            </select>
            <select className="nf-filter-select" style={{ flexShrink: 0 }} value={statusFilter} onChange={e => updateFilterAndResetPage(setStatusFilter, e.target.value)}>
              <option value="">All Statuses</option>
              <option value="PENDING">Pending</option>
              <option value="APPROVED">Approved</option>
              <option value="REJECTED">Rejected</option>
              <option value="PENALIZED">Penalized</option>
            </select>
          </div>
        )}
      </div>

      <div className="nf-section-enter" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 16, boxShadow: '0 6px 22px rgba(16, 24, 40, .07)', overflow: 'hidden' }}>
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
            <table className="nf-rep-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Type', 'Dates', 'Days', 'Status', 'Reason', 'Approved/Rejected By', ...(showActions ? ['Actions'] : [])].map(h => <th key={h} style={thStyle}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {pageRows.map(r => (
                  <tr key={r.id} className="nf-leave-row">
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 700 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                        <span className="nf-opp-ico"><CalendarDays size={17} /></span>
                        <span>
                          {r.leaveTypeName}
                          {r.originalLeaveTypeName && r.originalLeaveTypeName !== r.leaveTypeName && (
                            <span style={{ display: 'block', fontSize: 11, color: 'var(--txt-dim)' }}>was {r.originalLeaveTypeName}</span>
                          )}
                          {r.leaveTypeClassification === 'UNPAID' && (
                            <span style={{ marginLeft: 6, fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.03em' }}>
                              (Unpaid)
                            </span>
                          )}
                        </span>
                      </div>
                    </td>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.startDate}{r.startDate !== r.endDate ? ` to ${r.endDate}` : ''}{r.halfDay ? ` (${r.halfDaySession === 'SECOND_HALF' ? 'second half' : 'first half'})` : ''}{r.hours != null ? ` (${r.hours} h)` : ''}</td>
                    <td style={tdStyle}>
                      <span className="nf-rep-code">{r.totalDays}</span>
                      {yearBreakdownLabel(r.startDate, r.endDate, r.halfDay) && (
                        <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 3, whiteSpace: 'nowrap' }} title="Each part is charged to that year's balance">
                          {yearBreakdownLabel(r.startDate, r.endDate, r.halfDay)}
                        </div>
                      )}
                    </td>
                    <td style={tdStyle}><StatusBadge status={r.status} /></td>
                    <td style={{ ...tdStyle, maxWidth: 220 }}>
                      <div style={{ overflowWrap: 'anywhere' }} title={r.employeeReason}>{r.employeeReason}</div>
                      {r.attachmentName && (
                        <button type="button" onClick={() => leaveApi.downloadAttachment(r.id, r.attachmentName!, token).catch(() => undefined)}
                          style={{ marginTop: 4, display: 'inline-flex', alignItems: 'center', gap: 4, background: 'none', border: 'none', padding: 0, color: 'var(--brand)', fontSize: 12, cursor: 'pointer' }}>
                          <Paperclip size={12} /> {r.attachmentName}
                        </button>
                      )}
                    </td>
                    <td style={{ ...tdStyle, maxWidth: 240 }}>
                      {r.status === 'PENDING' ? (
                        <span style={{ color: 'var(--txt-dim)' }}>Awaiting decision</span>
                      ) : (
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                          <span style={{ fontSize: 10.5, fontWeight: 700, color: r.status === 'APPROVED' ? 'var(--ok)' : 'var(--risk)', textTransform: 'uppercase', letterSpacing: '.04em' }}>
                            {r.status === 'APPROVED' ? 'Approved by' : r.status === 'PENALIZED' ? 'Deducted by' : 'Rejected by'}
                          </span>
                          <span style={{ color: 'var(--txt)', fontWeight: 600 }}>{r.decidedByName}</span>
                          {r.decidedAt && <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>{formatDecisionTimestamp(r.decidedAt)}</span>}
                          {r.decisionReason && (
                            <span
                              style={{ fontSize: 12, color: 'var(--txt-mut)', overflowWrap: 'anywhere', maxWidth: 220, display: 'block' }}
                              title={r.decisionReason}
                            >
                              Comment: {r.decisionReason}
                            </span>
                          )}
                        </div>
                      )}
                    </td>
                    {showActions && (
                      <td style={tdStyle}>
                        {r.status === 'PENDING' && (
                          <ActionDock items={[{ label: 'Edit', icon: Pencil, onClick: () => setEditingRequest(r) }]} />
                        )}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 14px', borderTop: '1px solid var(--line)', fontSize: 12, color: 'var(--txt-dim)' }}>
              <span>Showing {(pageSafe - 1) * PAGE_SIZE + 1} to {Math.min(pageSafe * PAGE_SIZE, filteredRequests.length)} of {filteredRequests.length}</span>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <button onClick={() => setPage(p => Math.max(1, p - 1))} disabled={pageSafe <= 1} aria-label="Previous page" className="nf-act-btn">
                  <ChevronLeft size={13} />
                </button>
                <span style={{ fontWeight: 600, color: 'var(--txt-mut)' }}>Page {pageSafe} of {totalPages}</span>
                <button onClick={() => setPage(p => Math.min(totalPages, p + 1))} disabled={pageSafe >= totalPages} aria-label="Next page" className="nf-act-btn">
                  <ChevronRight size={13} />
                </button>
              </div>
            </div>
          </div>
        )}
      </div>

      {showRequest && (
        <RequestLeaveModal types={types} balances={balances} currentYear={currentYear} token={token} onClose={() => setShowRequest(false)} onSaved={handleSaved} />
      )}
      {editingRequest && (
        <RequestLeaveModal types={types} balances={balances} currentYear={currentYear} editingRequest={editingRequest} token={token} onClose={() => setEditingRequest(null)} onSaved={handleSaved} />
      )}
    </div>
  );
}

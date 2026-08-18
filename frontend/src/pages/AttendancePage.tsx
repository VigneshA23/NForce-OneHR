import { Fragment, useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState, forwardRef } from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router-dom';
import * as XLSX from 'xlsx';
import { Clock, LogIn, LogOut, CheckCircle2, CalendarPlus, Pencil, ShieldCheck, X, ChevronLeft, ChevronRight, Download, Eye, Turtle, Laptop, Home, Sun, FileText, Users, User, ArrowDownLeft, ArrowUpRight, Wifi, Info } from 'lucide-react';
import {
  attendanceApi, regularizationApi,
  type AttendanceRecord,
  type AttendanceStatus,
  type TodayAttendance,
  type AttendanceConfig,
  type AttendanceStats,
  type RegularizationRecord,
  type SubmitRegularizationPayload,
  type RegularizationBalance,
  type ApproverOption,
  type Punch,
} from '../api/attendance';
import {
  attendanceRequestApi,
  type AttendanceRequestRecord,
  type AttendanceRequestType,
  type PartialDayMode,
  type SubmitAttendanceRequestPayload,
} from '../api/attendanceRequests';
import { overtimeRequestApi, type OvertimeRequestRecord } from '../api/overtimeRequests';
import { webClockInApi } from '../api/webClockIn';
import { directoryApi, type DirectoryEntry } from '../api/directory';
import { AttendancePolicyModal } from '../components/AttendancePolicyModal';
import { holidaysApi, type HolidayRow } from '../api/holidays';
import { leaveApi, type LeaveRequestRecord } from '../api/leave';
import { profileApi } from '../api/profile';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { toShellRole } from '../lib/nav.config';
import { TimeFormatProvider, useTimeFormat } from '../context/TimeFormatContext';

// ─── Formatting helpers ───────────────────────────────────────────────────────
// Server timestamps are wall-clock strings in the business timezone (no offset), so they are
// formatted by slicing rather than via `new Date()` — that would re-interpret them in the
// browser's zone and shift the displayed time.
// formatTime/formatDuration themselves now live in TimeFormatContext (12h/24h-aware) — every
// consumer on this page pulls them from useTimeFormat() instead of a plain module function.

/** Index-aligned with JS Date#getDay() (0=Sunday) → java.time.DayOfWeek name, for weeklyOffDays. */
const DOW_NAMES = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'];

/** Minutes since local midnight, parsed the same zone-less way as formatTime (no Date object). */
function minutesSinceMidnight(iso: string): number | null {
  const time = iso.slice(11, 16);
  if (time.length < 5) return null;
  const [h, m] = time.split(':').map(Number);
  return h * 60 + m;
}

function formatDay(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, {
    weekday: 'short', day: 'numeric', month: 'short', year: 'numeric',
  });
}

/** Compact "14 Aug" form — for the "View Available Balance" table's Period column. */
function formatShortDay(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

function monthStartIso(isoDate: string): string {
  const [y, m] = isoDate.split('-').map(Number);
  return isoOf(y, m - 1, 1);
}

function monthEndIso(isoDate: string): string {
  const [y, m] = isoDate.split('-').map(Number);
  return isoOf(y, m - 1, daysInMonth(y, m - 1));
}

/** Parses a zone-less server timestamp into epoch ms using the same fixed reference frame. */
function wallClockMs(iso: string): number {
  const [datePart, timePart = '00:00:00'] = iso.split('T');
  const [y, mo, d] = datePart.split('-').map(Number);
  const [h, mi, s] = timePart.split(':').map((v) => Math.floor(Number(v)));
  return Date.UTC(y, mo - 1, d, h, mi, s || 0);
}

function todayIsoDate(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * The day's required effective minutes — the employee's assigned shift duration (wrapping past
 * midnight for overnight shifts), falling back to the global fullDayMinHours target only for the
 * edge case of an employee with no Shift assigned. Same calc as ExceptionService.
 * computeEffectiveHoursPercent on the backend, kept in sync so "100% effective hours" means the
 * same thing on both sides.
 */
function fullDayTargetMinutesFor(config: AttendanceConfig | null): number | null {
  const shiftMinutes = config?.shiftEnd
    ? (() => {
        const startMin = minutesSinceMidnight(`${todayIsoDate()}T${config.shiftStart}`) ?? 0;
        const endMin = minutesSinceMidnight(`${todayIsoDate()}T${config.shiftEnd}`) ?? 0;
        return endMin <= startMin ? endMin + 1440 - startMin : endMin - startMin;
      })()
    : null;
  return shiftMinutes ?? (config ? config.fullDayMinHours * 60 : null);
}

/** Whether a day's worked minutes reached 100% of its required effective hours target. */
function hasMetFullEffectiveHours(workedMinutes: number | null | undefined, config: AttendanceConfig | null): boolean {
  const target = fullDayTargetMinutesFor(config);
  return target != null && workedMinutes != null && workedMinutes >= target;
}

// Requirement 1 (date-window restriction): Employee/Manager/HR may only pick today or one of
// the previous REGULARIZATION_LOOKBACK_DAYS-1 days (today counts as one of the allowed days —
// e.g. 3 with today=6th allows 6th/5th/4th, blocks 3rd onward). Super Admin is exempt (no
// restriction). This mirrors RegularizationService.validateLookbackWindow on the backend, which
// is the source of truth and enforces the same rule server-side — this is a UX convenience only,
// not the actual security boundary, since the API rejects out-of-window dates regardless.
const REGULARIZATION_LOOKBACK_DAYS = 3;

/** ISO date N days before today, in the browser's local calendar. */
function isoDaysAgo(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return isoOf(d.getFullYear(), d.getMonth(), d.getDate());
}

// ─── Month-calendar helpers ────────────────────────────────────────────────────
function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

function isoOf(year: number, month: number, day: number): string {
  return `${year}-${pad2(month + 1)}-${pad2(day)}`;
}

function daysInMonth(year: number, month: number): number {
  return new Date(year, month + 1, 0).getDate();
}

function calendarMonthLabel(year: number, month: number): string {
  return new Date(year, month, 1).toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
}

/** Sun-first grid of day numbers for a month, padded with nulls to full weeks. */
function buildCalendarCells(year: number, month: number): (number | null)[] {
  const firstDow = new Date(year, month, 1).getDay();
  const total = daysInMonth(year, month);
  const cells: (number | null)[] = Array(firstDow).fill(null);
  for (let d = 1; d <= total; d++) cells.push(d);
  while (cells.length % 7 !== 0) cells.push(null);
  return cells;
}

/** Expands an inclusive date range into individual ISO date strings. */
function expandDateRange(startIso: string, endIso: string): string[] {
  const dates: string[] = [];
  let cur = new Date(`${startIso}T00:00:00`);
  const end = new Date(`${endIso}T00:00:00`);
  while (cur <= end) {
    dates.push(isoOf(cur.getFullYear(), cur.getMonth(), cur.getDate()));
    cur = new Date(cur.getFullYear(), cur.getMonth(), cur.getDate() + 1);
  }
  return dates;
}

/** Regularization timestamps come back with an offset, so a plain slice is safe here. */
function fmtDateTime(dt: string | null) {
  if (!dt) return '—';
  return dt.replace('T', ' ').slice(0, 16);
}

// ─── 12-hour time text input (Partial Day "Leave at" etc.) ────────────────────
// A single free-text field — not separate hour/minute/AM-PM dropdowns. Keystrokes are
// masked into "H:MM AM/PM" as the user types, and the result is validated against a
// strict 12-hour pattern before it's allowed to become a server timestamp.

type Period = 'AM' | 'PM';
interface TimeValue { hour: string; minute: string; period: Period | ''; }

/** True only when hour, minute, and AM/PM are all present. */
function isTimeValueComplete(t: TimeValue): boolean {
  return !!(t.hour && t.minute && t.period);
}

// "9:30 AM", "09:30am", "5:45 PM" — 1-12 hour, exactly 2-digit minute, AM/PM
// case-insensitive with or without a space before it.
const TIME_TEXT_PATTERN = /^(0?[1-9]|1[0-2]):([0-5][0-9])\s?([AaPp][Mm])$/;

/** Parses free-typed text into a TimeValue, or null if it isn't (yet) a complete valid time. */
function parseTimeText(text: string): TimeValue | null {
  const match = TIME_TEXT_PATTERN.exec(text.trim());
  if (!match) return null;
  return { hour: String(parseInt(match[1], 10)), minute: match[2], period: match[3].toUpperCase() as Period };
}

/**
 * Masks raw keystrokes into "H:MM AM/PM" as the user types: auto-inserts the ':' once the
 * typed hour is unambiguous, and completes a typed "A"/"P" to "AM"/"PM" — but only while
 * characters are being added, so backspacing through the suffix still clears it.
 */
function maskTimeInput(raw: string, previous: string): string {
  const deleting = raw.length < previous.length;
  const upper = raw.toUpperCase();
  const digits = upper.replace(/[^0-9]/g, '').slice(0, 4);
  const letter = upper.match(/[AP]/)?.[0];

  let hourPart = digits;
  let minutePart = '';
  if (digits.length > 1) {
    const firstTwo = parseInt(digits.slice(0, 2), 10);
    if (firstTwo >= 1 && firstTwo <= 12) {
      hourPart = digits.slice(0, 2);
      minutePart = digits.slice(2, 4);
    } else {
      hourPart = digits.slice(0, 1);
      minutePart = digits.slice(1, 3);
    }
  }

  let out = hourPart;
  if (digits.length > hourPart.length) out += ':' + minutePart;
  if (letter) out += (out ? ' ' : '') + (deleting ? letter : letter + 'M');
  return out;
}

/** Single masked text field for a 12-hour time — replaces separate hour/minute/AM-PM dropdowns. */
function TimeTextInput({ label, value, touched, onChange, onBlur, requiredMessage }: {
  label: string; value: string; touched: boolean; onChange: (text: string) => void; onBlur: () => void;
  /** Overrides the empty-field message — e.g. Keka's exact "Time is required" wording. */
  requiredMessage?: string;
}) {
  const empty = value.trim() === '';
  const invalidFormat = !empty && !parseTimeText(value);
  const showError = touched && (empty || invalidFormat);
  return (
    <Field label={label}>
      <input
        type="text"
        inputMode="text"
        maxLength={8}
        placeholder="e.g. 09:30 AM"
        style={{ ...inputStyle, ...(showError ? { borderColor: 'var(--risk)' } : {}) }}
        value={value}
        onChange={e => onChange(maskTimeInput(e.target.value, value))}
        onBlur={onBlur}
      />
      {showError && (
        <div style={fieldErrorStyle}>
          {empty ? (requiredMessage ?? 'This field is required.') : 'Enter a valid 12-hour time, e.g. 09:30 AM or 5:45 PM.'}
        </div>
      )}
    </Field>
  );
}

/** Groups rows by attendance-date month, newest month first, rows within a month newest first. */
function groupByMonth<T extends { attendanceDate: string }>(rows: T[]): Array<[string, T[]]> {
  const map = new Map<string, T[]>();
  for (const r of rows) {
    const key = r.attendanceDate.slice(0, 7); // yyyy-MM
    (map.get(key) ?? map.set(key, []).get(key)!).push(r);
  }
  return [...map.entries()]
    .sort((a, b) => b[0].localeCompare(a[0]))
    .map(([key, group]) => [key, [...group].sort((a, b) => b.attendanceDate.localeCompare(a.attendanceDate))]);
}

function monthLabel(key: string): string {
  const [y, m] = key.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
}

// Plain month names for the My Requests month selector — filters by calendar month across
// all years, independent of the (year+month) grouping headings groupByMonth/monthLabel drive.
const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

const APPROVER_ROLE_LABELS: Record<string, string> = { MANAGER: 'Manager', HR_ADMIN: 'HR' };

const STATUS_COLORS: Record<AttendanceStatus, string> = {
  PRESENT: '#2FB67C',
  LATE: '#E0A93B',
  HALF_DAY: '#4C8DD6',
  ABSENT: '#E4373D',
  MISSING_CHECKOUT: '#E4373D',
};

const STATUS_LABELS: Record<AttendanceStatus, string> = {
  PRESENT: 'Present',
  LATE: 'Late',
  HALF_DAY: 'Half Day',
  ABSENT: 'Absent',
  MISSING_CHECKOUT: 'Missing Check-Out',
};

const REGULARIZATION_STATUS_COLOR: Record<string, string> = {
  PENDING: '#E0A93B', PARTIALLY_APPROVED: '#3B82C4', APPROVED: '#2FB67C', REJECTED: '#E4373D',
};

/**
 * A request still in flight AND actionable by the current viewer — the only combination
 * approve/reject/bulk actions can act on; APPROVED/REJECTED are always terminal.
 *
 * PENDING is the manager stage — actionable by whoever it's assigned to (or the HR/Super Admin
 * override). PARTIALLY_APPROVED is manager-stage-complete, awaiting HR/Super Admin final
 * approval — a Manager's Approve/Reject on it would be rejected server-side (see
 * RegularizationService.approve/reject, which only lets SUPER_ADMIN/HR_ADMIN act on
 * PARTIALLY_APPROVED), so those buttons are hidden for Manager rather than shown-then-failing
 * (Requirement 2.3). HR and Super Admin keep seeing them, unchanged.
 */
function isActionableRequest(r: RegularizationRecord, isManager: boolean) {
  if (r.status === 'PENDING') return true;
  if (r.status === 'PARTIALLY_APPROVED') return !isManager;
  return false;
}

const dash = <span style={{ color: 'var(--txt-dim)' }}>—</span>;

const thStyle: React.CSSProperties = {
  padding: '9px 12px', textAlign: 'left', fontSize: 10.5, fontWeight: 700,
  color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em',
  borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap',
};
const tdStyle: React.CSSProperties = {
  padding: '10px 12px', fontSize: 12.5, color: 'var(--txt-mut)',
  borderBottom: '1px solid var(--line)', verticalAlign: 'middle',
};
const panelStyle: React.CSSProperties = {
  background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, overflow: 'hidden',
};
const dateInputStyle: React.CSSProperties = {
  background: 'var(--raised)', color: 'var(--txt)', border: '1px solid var(--line2)',
  borderRadius: 6, padding: '6px 9px', fontSize: 12.5,
};

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 480, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 20px 60px rgba(0,0,0,.5)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
const fieldErrorStyle: React.CSSProperties = { fontSize: 11, color: 'var(--risk)', marginTop: 4 };

// ─── Shared bits ──────────────────────────────────────────────────────────────

function StatusPill({ status }: { status: AttendanceStatus | null }) {
  if (!status) return dash;
  const color = STATUS_COLORS[status] ?? 'var(--txt-mut)';
  return (
    <span style={{
      fontSize: 10.5, fontWeight: 600, color,
      background: 'var(--raised)', border: '1px solid var(--line)',
      borderRadius: 4, padding: '2px 6px', whiteSpace: 'nowrap',
    }}>
      {STATUS_LABELS[status] ?? status}
    </span>
  );
}

/**
 * Arrival-time lateness, shown independently of the day's overall status — a short day
 * overrides `status` to HALF_DAY (see AttendanceService.checkOut), which would otherwise hide
 * the fact that the person also arrived late. `lateByMinutes` is stored regardless of that
 * override, so this reads straight from it instead of gating on `status === 'LATE'`.
 */
/**
 * Late is grace-aware: `minutes` is stored raw (time past shift start, no grace forgiveness —
 * see AttendanceService.checkIn), so a check-in inside the grace window still has minutes > 0
 * and must NOT show as late here. `minutes > graceMinutes` is exactly the backend's own
 * `isLate` check (past shiftStart + graceMinutes) re-derived from data already on hand,
 * without needing `status` (which HALF_DAY can override — see AttendanceService.checkOut).
 */
function LateBadge({ minutes, graceMinutes, workedMinutes, config }: {
  minutes: number | null | undefined; graceMinutes: number | null | undefined;
  workedMinutes?: number | null; config?: AttendanceConfig | null;
}) {
  const { formatDuration } = useTimeFormat();
  const grace = graceMinutes ?? 10;
  if (!minutes || minutes <= grace) return null;
  const fullDay = hasMetFullEffectiveHours(workedMinutes, config ?? null);
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11.5, fontWeight: 600, color: '#E0A93B' }}>
      {!fullDay && <Turtle size={18} aria-label="Late" style={{ flexShrink: 0 }} />} Late by {formatDuration(minutes)}
    </div>
  );
}

function RegularizationStatusPill({ status }: { status: string }) {
  return (
    <span style={{ fontSize: 10.5, fontWeight: 600, color: REGULARIZATION_STATUS_COLOR[status] ?? '#9BA1AC', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 4, padding: '2px 6px' }}>
      {status}
    </span>
  );
}

/**
 * Every check-in/check-out session for a single day — shows a lunch-break gap explicitly.
 * `refreshKey` exists solely so the caller can force a re-fetch after a punch: `date`/`token`
 * alone never change when a new punch happens today, so without it this list would only ever
 * reflect whatever was on file when the panel first mounted, not the punch that just happened.
 */
function PunchHistoryList({ date, token, refreshKey }: { date: string; token: string; refreshKey?: unknown }) {
  const { formatTime } = useTimeFormat();
  const [punches, setPunches] = useState<Punch[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setPunches(null);
    setError(null);
    attendanceApi.punches(date, token)
      .then((p) => { if (!cancelled) setPunches(p); })
      .catch((err) => { if (!cancelled) setError(err instanceof Error ? err.message : 'Failed to load punch history'); });
    return () => { cancelled = true; };
  }, [date, token, refreshKey]);

  if (error) {
    return <div style={{ fontSize: 11.5, color: 'var(--risk)' }}>Punch history: {error}</div>;
  }
  if (punches === null) {
    return <div style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>Loading punch history…</div>;
  }
  if (punches.length <= 1) return null; // a single session adds nothing beyond the bookends above

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 5 }}>
        <Clock size={10.5} /> Punch History
      </div>
      {/* A grid (rather than one flex row per punch) so the Check In / arrow / Check Out cells
          line up in the same columns across every row. `minmax(0,1fr)` on the time columns lets
          them shrink instead of overflowing the panel — no horizontal scrollbar/drag ever
          appears, even in a narrow side panel — while still centering their content. */}
      <div style={{
        display: 'grid', gridTemplateColumns: 'auto auto minmax(0,1fr) auto auto minmax(0,1fr)',
        columnGap: 6, rowGap: 4, alignItems: 'center', fontSize: 12, color: 'var(--txt-mut)',
        maxWidth: '100%', overflow: 'hidden',
      }}>
        {punches.map((p, i) => (
          <Fragment key={p.id}>
            <span style={{ color: 'var(--txt-dim)', fontSize: 10.5 }}>{i + 1}.</span>
            <LogIn size={11} style={{ color: 'var(--txt-dim)' }} />
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{formatTime(p.checkInAt) ?? dash}</span>
            <span style={{ color: 'var(--txt-dim)', textAlign: 'center' }}>→</span>
            <LogOut size={11} style={{ color: 'var(--txt-dim)' }} />
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{formatTime(p.checkOutAt) ?? 'still open'}</span>
          </Fragment>
        ))}
      </div>
    </div>
  );
}

function SourceTag({ source }: { source: string | null }) {
  if (!source) return dash;
  const label = source === 'REGULARIZATION' ? 'Regularized' : source === 'WEB_REMOTE' ? 'Web Remote' : 'System';
  return <span>{label}</span>;
}

function SectionHeading({ title, hint }: { title: string; hint?: string }) {
  return (
    <div style={{ marginBottom: 10 }}>
      <h2 style={{
        fontFamily: '"Space Grotesk", sans-serif', fontSize: 14, fontWeight: 700,
        color: 'var(--txt)', margin: 0,
      }}>{title}</h2>
      {hint && <p style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 3 }}>{hint}</p>}
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><label style={labelStyle}>{label}</label>{children}</div>;
}

function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
      <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>{title}</span>
      <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignItems: 'center' }}><X size={16} /></button>
    </div>
  );
}

/**
 * Keka's exact "Oh No!!" hard-stop error card — used for every submit-time validation failure
 * across the request modals (past date, over the per-request duration ceiling, missing
 * required fields), so there's one consistent error presentation instead of a plain text line.
 * Dismissible; never blocks the fields behind it from being edited.
 */
function OhNoError({ message, onDismiss }: { message: string; onDismiss: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10, background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 9, padding: '11px 12px' }}>
      <div style={{ width: 20, height: 20, borderRadius: '50%', background: '#E4373D', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, marginTop: 1 }}>
        <X size={12} strokeWidth={3} />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontWeight: 700, color: '#E4373D', fontSize: 13, marginBottom: 2 }}>Oh No!!</div>
        <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.4 }}>{message}</div>
      </div>
      <button onClick={onDismiss} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 2, display: 'flex', flexShrink: 0 }}>
        <X size={14} />
      </button>
    </div>
  );
}

// ─── Tooltip (hover-only, portal-rendered) ─────────────────────────────────────
// No tooltip component exists in this project and no UI library (MUI/Radix/Antd/etc.) is
// installed, so this is a minimal from-scratch implementation matching the page's existing
// inline-style conventions. Rendered through a portal so it always sits above any local
// overflow/z-index context (table scroll containers, modals) rather than being clipped by them.
function Tooltip({ content, children }: { content: React.ReactNode; children: React.ReactNode }) {
  const [coords, setCoords] = useState<{ top: number; left: number; placement: 'top' | 'bottom' } | null>(null);
  const anchorRef = useRef<HTMLSpanElement>(null);
  const TOOLTIP_MAX_WIDTH = 340;
  const GAP = 8;

  function show() {
    const el = anchorRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    // Prefer above; fall back to below only when there isn't reasonably enough headroom —
    // exact fit is re-checked after render isn't needed since the tooltip is capped/wrapped.
    const placement: 'top' | 'bottom' = rect.top >= 120 + GAP ? 'top' : 'bottom';
    const maxLeft = Math.max(GAP, window.innerWidth - GAP - TOOLTIP_MAX_WIDTH);
    const left = Math.min(Math.max(rect.left, GAP), maxLeft);
    setCoords({ top: placement === 'top' ? rect.top - GAP : rect.bottom + GAP, left, placement });
  }
  function hide() {
    setCoords(null);
  }

  return (
    <>
      <span
        ref={anchorRef}
        onMouseEnter={show}
        onMouseLeave={hide}
        onFocus={show}
        onBlur={hide}
        style={{ display: 'block', minWidth: 0, maxWidth: '100%' }}
      >
        {children}
      </span>
      {coords && createPortal(
        <div
          role="tooltip"
          style={{
            position: 'fixed',
            top: coords.top,
            left: coords.left,
            transform: coords.placement === 'top' ? 'translateY(-100%)' : undefined,
            maxWidth: TOOLTIP_MAX_WIDTH,
            width: 'max-content',
            background: 'var(--raised2)',
            color: 'var(--txt)',
            border: '1px solid var(--line2)',
            borderRadius: 7,
            padding: '8px 11px',
            fontSize: 12,
            lineHeight: 1.5,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
            overflowWrap: 'anywhere',
            boxShadow: '0 8px 24px rgba(0,0,0,.35)',
            zIndex: 1000,
            pointerEvents: 'none',
          }}
        >
          {content}
        </div>,
        document.body,
      )}
    </>
  );
}

/** Single-line, ellipsis-truncated text with a hover tooltip revealing the full content. */
function TruncatedText({ text, style }: { text: string | null | undefined; style?: React.CSSProperties }) {
  if (!text) return dash;
  return (
    <Tooltip content={text}>
      <span style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: '100%', ...style }}>
        {text}
      </span>
    </Tooltip>
  );
}

// ─── Request Regularization Modal (create or edit-while-pending) ──────────────
// Matches Keka's flow: a Selected Date / Shift Timings header, check-in/check-out shown
// read-only from the day's on-file attendance record (no manual time entry at all), a fixed
// "exempt this day from penalty" statement, a remaining-balance readout with View Details, and
// a single required Note.
function RequestModal({ onClose, onSaved, token, editing, approvedDates, isSuperAdmin, initialDate }: {
  onClose: () => void;
  onSaved: (r: RegularizationRecord) => void;
  token: string;
  editing?: RegularizationRecord;
  /** Attendance dates that already have an APPROVED regularization — resubmission is blocked. */
  approvedDates: Set<string>;
  /** Super Admin is exempt from the date-window restriction below (Requirement 1). */
  isSuperAdmin: boolean;
  /** Pre-selects a date on a fresh (non-editing) request — e.g. opened from a specific day's Attendance Log entry. */
  initialDate?: string;
}) {
  const { showToast } = useToast();
  const { formatTime } = useTimeFormat();
  const today = todayIsoDate();
  // Employee/Manager/HR: earliest attendance date selectable in the calendar picker. Super
  // Admin has no lower bound — "any number of previous days" per Requirement 1.
  const minDate = isSuperAdmin ? undefined : isoDaysAgo(REGULARIZATION_LOOKBACK_DAYS - 1);
  const [attendanceDate, setAttendanceDate] = useState(editing?.attendanceDate ?? initialDate ?? today);
  const [reason, setReason] = useState(editing?.reason ?? '');
  const [managerUserId, setManagerUserId] = useState(editing?.assignedApproverId ?? '');
  const [approvers, setApprovers] = useState<ApproverOption[]>([]);
  const [existingPunch, setExistingPunch] = useState<AttendanceRecord | null>(null);
  const [loadingPunch, setLoadingPunch] = useState(false);
  const [config, setConfig] = useState<AttendanceConfig | null>(null);
  const [balance, setBalance] = useState<RegularizationBalance | null>(null);
  const [showBalanceDetails, setShowBalanceDetails] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitAttempted, setSubmitAttempted] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    regularizationApi.approvers(token).then(setApprovers).catch(() => { /* dropdown degrades to empty — Assign To is still required */ });
  }, [token]);

  useEffect(() => {
    attendanceApi.config(token).then(setConfig).catch(() => setConfig(null));
  }, [token]);

  useEffect(() => {
    regularizationApi.balance(token).then(setBalance).catch(() => setBalance(null));
  }, [token]);

  // Punch lookup: what's already on file for the chosen date — the only source for
  // requestedCheckIn/Out now (no manual time entry, matching Keka's flow). Clearing to null
  // immediately (not just when the date is blank) avoids a stale flash of the previous date's
  // values while the new lookup is in flight.
  useEffect(() => {
    setExistingPunch(null);
    if (!attendanceDate) return;
    let cancelled = false;
    setLoadingPunch(true);
    attendanceApi.punchForDate(attendanceDate, token)
      .then((punch) => { if (!cancelled) setExistingPunch(punch); })
      .catch(() => { if (!cancelled) setExistingPunch(null); })
      .finally(() => { if (!cancelled) setLoadingPunch(false); });
    return () => { cancelled = true; };
  }, [attendanceDate, token]);

  const hasAnyPunch = !!(existingPunch?.checkInAt || existingPunch?.checkOutAt);

  // A date that already has an APPROVED regularization can't be re-requested — editing that
  // same request (its own date, unchanged) is not a duplicate.
  const dateAlreadyApproved = !!attendanceDate
    && attendanceDate !== editing?.attendanceDate
    && approvedDates.has(attendanceDate);

  // Requirement 1: Employee/Manager/HR can only pick a date within the last
  // REGULARIZATION_LOOKBACK_DAYS days (today counts as one). The `min` attribute on the date
  // input below keeps this out of reach via the picker UI; this flag catches a typed/pasted
  // value that slips past it (or an older date already set while editing). Super Admin never
  // trips this check. The backend re-validates the same rule regardless — see
  // RegularizationService.validateLookbackWindow.
  const dateOutsideWindow = !isSuperAdmin && !!attendanceDate && !!minDate && attendanceDate < minDate;

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitAttempted(true);

    const dateMissing = !attendanceDate;
    const reasonMissing = !reason.trim();
    const managerMissing = !managerUserId;

    if (dateMissing || reasonMissing || managerMissing) {
      setError('Fill in every required field shown above.');
      return;
    }
    if (dateAlreadyApproved) {
      setError('Already raised regularization for this date.');
      return;
    }
    if (dateOutsideWindow) {
      setError(`Regularization requests are only allowed within the last ${REGULARIZATION_LOOKBACK_DAYS} days (including today).`);
      return;
    }
    if (!hasAnyPunch) {
      setError('No attendance record on file for this date — nothing to regularize.');
      return;
    }
    setSubmitting(true); setError(null);
    try {
      const payload: SubmitRegularizationPayload = {
        attendanceDate,
        requestedCheckIn: existingPunch?.checkInAt ?? undefined,
        requestedCheckOut: existingPunch?.checkOutAt ?? undefined,
        reason: reason.trim(),
        managerUserId,
      };
      const saved = editing
        ? await regularizationApi.update(editing.id, payload, token)
        : await regularizationApi.submit(payload, token);
      onSaved(saved);
      showToast('success', editing ? 'Regularization request updated' : 'Regularization request submitted');
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Submission failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <ModalHeader title={editing ? 'Edit Regularization Request' : 'Request Attendance Regularization'} onClose={onClose} />
        <form onSubmit={handleSubmit} style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
          {error && <div style={{ color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
            <div style={{ flex: 1, minWidth: 160 }}>
              <Field label="Selected Date *">
                <input type="date" style={inputStyle} value={attendanceDate} max={today} min={minDate}
                  onChange={e => { setAttendanceDate(e.target.value); setSubmitAttempted(false); }} />
              </Field>
              {submitAttempted && !attendanceDate && <div style={fieldErrorStyle}>Attendance Date is required.</div>}
              {dateAlreadyApproved && <div style={fieldErrorStyle}>Already raised regularization for this date.</div>}
              {dateOutsideWindow && (
                <div style={fieldErrorStyle}>Only the last {REGULARIZATION_LOOKBACK_DAYS} days (including today) are selectable.</div>
              )}
            </div>
            <div style={{ flex: 1, minWidth: 160 }}>
              <div style={labelStyle}>Shift Timings</div>
              <div style={{ fontSize: 13.5, color: 'var(--txt)', fontWeight: 600, padding: '9px 0' }}>
                {config?.shiftStart
                  ? <>{formatTime(`${attendanceDate}T${config.shiftStart}`)}{config.shiftEnd && <> – {formatTime(`${attendanceDate}T${config.shiftEnd}`)}</>}</>
                  : dash}
              </div>
            </div>
          </div>

          <div style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>
            {loadingPunch ? 'Checking attendance…' : hasAnyPunch ? (
              <>On file — Check-in: {formatTime(existingPunch?.checkInAt ?? null) ?? 'not recorded'}, Check-out: {formatTime(existingPunch?.checkOutAt ?? null) ?? 'not recorded'}.</>
            ) : (
              <span style={{ color: 'var(--risk)' }}>No attendance record on file for this date — nothing to regularize.</span>
            )}
          </div>

          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
            <input type="radio" checked readOnly style={{ marginTop: 3 }} />
            <span style={{ fontSize: 13, color: 'var(--txt)' }}>
              Raise regularization request to exempt this day from penalization policy.
            </span>
          </div>

          <div style={{ position: 'relative' }}>
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', display: 'flex', alignItems: 'center', gap: 6 }}>
              <Info size={13} />
              {balance ? (
                balance.unlimited
                  ? <>Remaining balance: <strong style={{ color: 'var(--txt)' }}>Unlimited</strong></>
                  : <>Remaining balance: <strong style={{ color: 'var(--txt)' }}>{balance.remainingCount} request{balance.remainingCount === 1 ? '' : 's'}</strong></>
              ) : 'Remaining balance: —'}
              {balance && !balance.unlimited && (
                <button type="button" onClick={() => setShowBalanceDetails((s) => !s)}
                  style={{ background: 'none', border: 'none', color: 'var(--brand)', fontSize: 12.5, fontWeight: 600, cursor: 'pointer', padding: 0 }}>
                  View Details
                </button>
              )}
            </div>
            {showBalanceDetails && balance && !balance.unlimited && (
              <div style={{ position: 'absolute', top: '100%', left: 0, marginTop: 6, zIndex: 30, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 9, boxShadow: '0 8px 24px rgba(0,0,0,.35)', minWidth: 260, overflow: 'hidden' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                  <thead>
                    <tr>
                      <th style={{ textAlign: 'left', padding: '8px 12px', color: 'var(--txt-dim)', fontWeight: 600, borderBottom: '1px solid var(--line)' }}>Period</th>
                      <th style={{ textAlign: 'right', padding: '8px 12px', color: 'var(--txt-dim)', fontWeight: 600, borderBottom: '1px solid var(--line)' }}>Balance</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <td style={{ padding: '8px 12px', color: 'var(--txt)' }}>
                        {formatShortDay(monthStartIso(attendanceDate))} - {formatShortDay(monthEndIso(attendanceDate))}
                      </td>
                      <td style={{ padding: '8px 12px', color: 'var(--txt)', textAlign: 'right' }}>
                        {balance.remainingCount}/{balance.limitCount} requests
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <Field label="Assign To *">
            <select style={inputStyle} value={managerUserId} onChange={e => setManagerUserId(e.target.value)}>
              <option value="" disabled>Select HR or Manager…</option>
              {approvers.map(a => (
                <option key={a.userId} value={a.userId}>{a.fullName} — {APPROVER_ROLE_LABELS[a.roleCode] ?? a.roleCode}</option>
              ))}
            </select>
            {submitAttempted && !managerUserId && <div style={fieldErrorStyle}>Assign To is required — select an HR or Manager approver.</div>}
          </Field>

          <Field label="Note *">
            <textarea
              style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }}
              value={reason}
              onChange={e => setReason(e.target.value)}
              placeholder="Enter note"
            />
            {submitAttempted && !reason.trim() && <div style={fieldErrorStyle}>Note is required.</div>}
          </Field>

          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={submitting || dateAlreadyApproved || dateOutsideWindow || !hasAnyPunch}
              style={{
                background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600,
                cursor: (submitting || dateAlreadyApproved || dateOutsideWindow || !hasAnyPunch) ? 'not-allowed' : 'pointer',
                opacity: (submitting || dateAlreadyApproved || dateOutsideWindow || !hasAnyPunch) ? 0.7 : 1,
              }}>
              {submitting ? 'Saving…' : editing ? 'Save Changes' : 'Request'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

/**
 * Full, unclamped text block — no ellipsis, no hover-tooltip dependency. Long text wraps
 * naturally within the modal's width. Used for Reason/Comments in the details popup, where the
 * complete content must always be visible (unlike the table cells, which still use
 * TruncatedText — this component is deliberately separate from that one).
 */
function FullText({ text, style }: { text: string | null | undefined; style?: React.CSSProperties }) {
  if (!text) return <>{dash}</>;
  return (
    <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', overflowWrap: 'anywhere', ...style }}>
      {text}
    </div>
  );
}

// ─── Request Details Modal (read-only — replaces the old Comments table column) ──────
function RequestDetailsModal({ request, onClose }: { request: RegularizationRecord; onClose: () => void }) {
  const { formatTime, formatDuration } = useTimeFormat();
  // Mirrors ReviewerCell's logic: who's responsible for the *next* action while still in
  // flight (assigned manager while PENDING, whichever HR/Super Admin queue while
  // PARTIALLY_APPROVED), or who made the final call once resolved.
  const approverLabel = request.status === 'PENDING' ? 'Current Approver'
    : request.status === 'PARTIALLY_APPROVED' ? 'Awaiting Final Approval From'
    : request.status === 'APPROVED' ? 'Approved By' : 'Rejected By';
  const approverName = request.status === 'PENDING' || request.status === 'PARTIALLY_APPROVED'
    ? request.assignedApproverName : request.reviewedByName;

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 460, overflowX: 'hidden' }}>
        <ModalHeader title="Regularization Request Details" onClose={onClose} />
        <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 16, maxWidth: '100%', overflowX: 'hidden' }}>
          <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
            <div>
              <div style={labelStyle}>Date</div>
              <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)' }}>{formatDay(request.attendanceDate)}</div>
            </div>
            <div>
              <div style={labelStyle}>Status</div>
              <RegularizationStatusPill status={request.status} />
            </div>
          </div>
          <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
            <div>
              <div style={labelStyle}>Requested In</div>
              <div style={{ fontSize: 14, color: 'var(--txt)' }}>{formatTime(request.requestedCheckIn) ?? dash}</div>
            </div>
            <div>
              <div style={labelStyle}>Requested Out</div>
              <div style={{ fontSize: 14, color: 'var(--txt)' }}>{formatTime(request.requestedCheckOut) ?? dash}</div>
            </div>
            <div>
              <div style={labelStyle}>Total Hours</div>
              <div style={{ fontSize: 14, color: 'var(--txt)' }}>{formatDuration(request.totalMinutes) ?? dash}</div>
            </div>
          </div>
          <div style={{ maxWidth: '100%', minWidth: 0 }}>
            <div style={labelStyle}>Reason</div>
            <FullText text={request.reason} style={{ fontSize: 13, color: 'var(--txt-mut)' }} />
          </div>
          <div>
            <div style={labelStyle}>{approverLabel}</div>
            <div style={{ fontSize: 14, color: 'var(--txt)' }}>{approverName ?? dash}</div>
          </div>
          {/* Two-stage audit trail — shown once each stage has actually happened, so a
              still-PENDING request shows neither and a fully-APPROVED one shows both. */}
          {request.approvedByName && (
            <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
              <div>
                <div style={labelStyle}>Approved By (Manager)</div>
                <div style={{ fontSize: 14, color: 'var(--txt)' }}>{request.approvedByName}</div>
              </div>
              <div>
                <div style={labelStyle}>Approved At</div>
                <div style={{ fontSize: 14, color: 'var(--txt)' }}>{fmtDateTime(request.approvedAt)}</div>
              </div>
            </div>
          )}
          {request.finalApprovedByName && (
            <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
              <div>
                <div style={labelStyle}>Final Approved By</div>
                <div style={{ fontSize: 14, color: 'var(--txt)' }}>{request.finalApprovedByName}</div>
              </div>
              <div>
                <div style={labelStyle}>Final Approved At</div>
                <div style={{ fontSize: 14, color: 'var(--txt)' }}>{fmtDateTime(request.finalApprovedAt)}</div>
              </div>
            </div>
          )}
          <div style={{ maxWidth: '100%', minWidth: 0 }}>
            <div style={labelStyle}>Comments</div>
            <FullText text={request.reviewComment} style={{ fontSize: 13, color: 'var(--txt-mut)' }} />
          </div>
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Close</button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Approve Confirmation Modal ────────────────────────────────────────────────
function ConfirmApproveModal({ request, onClose, onApproved, token }: {
  request: RegularizationRecord; onClose: () => void; onApproved: (r: RegularizationRecord) => void; token: string;
}) {
  const { showToast } = useToast();
  const { formatTime } = useTimeFormat();
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function handleConfirm() {
    setSubmitting(true);
    try {
      const updated = await regularizationApi.approve(request.id, token, comment.trim() || undefined);
      onApproved(updated);
      showToast('success', 'Request approved and attendance record updated');
      onClose();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Approve failed');
      setSubmitting(false);
    }
  }

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 420 }}>
        <ModalHeader title={`Approve — ${request.employeeName}`} onClose={onClose} />
        <div style={{ padding: 24 }}>
          <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 14 }}>
            {formatDay(request.attendanceDate)} · {formatTime(request.requestedCheckIn) ?? dash} → {formatTime(request.requestedCheckOut) ?? dash}
          </div>
          <Field label="Comment (optional)">
            <textarea
              style={{ ...inputStyle, minHeight: 70, resize: 'vertical', fontFamily: 'inherit' }}
              value={comment}
              onChange={e => setComment(e.target.value)}
              placeholder="Optional note for the employee"
            />
          </Field>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 16 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button onClick={handleConfirm} disabled={submitting} style={{ background: '#2FB67C', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>
              {submitting ? 'Approving…' : 'Confirm Approval'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Reject Modal ──────────────────────────────────────────────────────────────
function RejectModal({ request, onClose, onRejected, token }: { request: RegularizationRecord; onClose: () => void; onRejected: (r: RegularizationRecord) => void; token: string }) {
  const { showToast } = useToast();
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleReject() {
    if (!comment.trim()) { setError('A comment is required when rejecting a request.'); return; }
    setSubmitting(true); setError(null);
    try {
      const updated = await regularizationApi.reject(request.id, comment.trim(), token);
      onRejected(updated);
      showToast('success', 'Request rejected');
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Reject failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 420 }}>
        <ModalHeader title={`Reject — ${request.employeeName}`} onClose={onClose} />
        <div style={{ padding: 24 }}>
          {error && <div style={{ color: 'var(--risk)', marginBottom: 14, fontSize: 13 }}>{error}</div>}
          <Field label="Reason for rejection *">
            <textarea
              style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }}
              value={comment}
              onChange={e => setComment(e.target.value)}
              placeholder="Explain why this request is being rejected"
            />
          </Field>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 16 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button onClick={handleReject} disabled={submitting} style={{ background: '#C0392B', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>
              {submitting ? 'Rejecting…' : 'Reject Request'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Bulk Approve / Bulk Reject Modal ──────────────────────────────────────────
// Comment is optional for bulk-approve (mirrors ConfirmApproveModal) and required for
// bulk-reject (mirrors RejectModal) — same validation split as the single-item actions.
function BulkActionModal({ action, ids, token, onClose, onDone }: {
  action: 'APPROVE' | 'REJECT';
  ids: string[];
  token: string;
  onClose: () => void;
  onDone: () => void;
}) {
  const { showToast } = useToast();
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleConfirm() {
    if (action === 'REJECT' && !comment.trim()) {
      setError('A comment is required when rejecting requests.');
      return;
    }
    setSubmitting(true); setError(null);
    try {
      const result = action === 'APPROVE'
        ? await regularizationApi.bulkApprove(ids, token, comment.trim() || undefined)
        : await regularizationApi.bulkReject(ids, comment.trim(), token);
      const verb = action === 'APPROVE' ? 'approved' : 'rejected';
      if (result.succeededIds.length > 0) {
        showToast('success', `${result.succeededIds.length} request${result.succeededIds.length === 1 ? '' : 's'} ${verb}`);
      }
      if (result.failed.length > 0) {
        showToast('error', `${result.failed.length} failed — ${result.failed[0].reason}${result.failed.length > 1 ? ` (+${result.failed.length - 1} more)` : ''}`);
      }
      onDone();
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Bulk action failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  const isReject = action === 'REJECT';
  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 420 }}>
        <ModalHeader title={`${isReject ? 'Reject' : 'Approve'} ${ids.length} Request${ids.length === 1 ? '' : 's'}`} onClose={onClose} />
        <div style={{ padding: 24 }}>
          {error && <div style={{ color: 'var(--risk)', marginBottom: 14, fontSize: 13 }}>{error}</div>}
          <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 14 }}>
            This will {isReject ? 'reject' : 'approve'} {ids.length} selected request{ids.length === 1 ? '' : 's'}. Each is processed independently — one failure won't affect the others.
          </div>
          <Field label={isReject ? 'Reason for rejection *' : 'Comment (optional)'}>
            <textarea
              style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }}
              value={comment}
              onChange={e => setComment(e.target.value)}
              placeholder={isReject ? 'Explain why these requests are being rejected' : 'Optional note applied to every selected request'}
            />
          </Field>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 16 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button
              onClick={handleConfirm}
              disabled={submitting}
              style={{ background: isReject ? '#C0392B' : '#2FB67C', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}
            >
              {submitting ? 'Processing…' : `Confirm Bulk ${isReject ? 'Reject' : 'Approve'}`}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

/** Day roster used by both the Manager and HR views. */
function RosterTable({ rows, loading, emptyMessage }: {
  rows: AttendanceRecord[]; loading: boolean; emptyMessage: string;
}) {
  const { formatTime, formatDuration } = useTimeFormat();
  return (
    <div style={panelStyle}>
      {loading ? (
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
      ) : rows.length === 0 ? (
        <div style={{ padding: 48, textAlign: 'center' }}>
          <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>Nothing to show</div>
          <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>{emptyMessage}</div>
        </div>
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                {['Employee ID', 'Name', 'Check In', 'Check Out', 'Hours', 'Status', 'Source'].map((h) => (
                  <th key={h} style={thStyle}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.employeeUserId}>
                  <td style={{ ...tdStyle, fontFamily: 'monospace', fontSize: 12 }}>{r.employeeCode}</td>
                  <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.fullName}</td>
                  <td style={tdStyle}>{formatTime(r.checkInAt) ?? dash}</td>
                  <td style={tdStyle}>{formatTime(r.checkOutAt) ?? dash}</td>
                  <td style={tdStyle}>{formatDuration(r.workedMinutes) ?? dash}</td>
                  <td style={tdStyle}><StatusPill status={r.status} /></td>
                  <td style={tdStyle}><SourceTag source={r.source} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ─── Month summary tile ────────────────────────────────────────────────────────
function MonthStatTile({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, padding: '14px 16px' }}>
      <div style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 7 }}>
        {label}
      </div>
      <div style={{ fontSize: 23, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1 }}>
        {value}
      </div>
      <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 5 }}>{hint}</div>
    </div>
  );
}

interface DayInfo {
  iso: string;
  day: number;
  isFuture: boolean;
  /** Before the employee's own joining date — never shown, same treatment as isFuture. */
  isBeforeJoining: boolean;
  isToday: boolean;
  isWeekend: boolean;
  holidayName?: string;
  leaveTypeName?: string;
  /** Only ever set when the request is APPROVED — pending/rejected requests get no calendar mark. */
  regularization?: RegularizationRecord;
  /** Only ever set when the WFH/Partial Day request is APPROVED. */
  attendanceRequest?: AttendanceRequestRecord;
  record?: AttendanceRecord;
}

const DAY_TAG_STYLE: React.CSSProperties = {
  display: 'inline-block', fontSize: 9.5, fontWeight: 700, padding: '1px 5px', borderRadius: 4,
  textTransform: 'uppercase', letterSpacing: '.03em', marginTop: 4, whiteSpace: 'nowrap',
};

/** Renders the small tag/status indicator inside a calendar day cell. */
/** The primary per-day badge — one of these, in precedence order. */
function primaryDayBadge(info: DayInfo): React.ReactNode {
  if (info.holidayName) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(76,141,214,.15)', color: '#4C8DD6' }}>Holiday</span>;
  }
  if (info.leaveTypeName) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(47,182,124,.15)', color: '#2FB67C' }}>Leave</span>;
  }
  // Checked before the attendance-record pill: an approved regularization normally upserts
  // that same day's attendance record, so the pill would otherwise always shadow this tag.
  // info.regularization is only ever populated for APPROVED requests (see regularizationByDate),
  // so this tag is green — matching the APPROVED status color used elsewhere on the page.
  if (info.regularization) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(47,182,124,.15)', color: '#2FB67C' }}>Regularization</span>;
  }
  // Only ever set for an APPROVED WFH/Partial Day request — same reasoning as regularization above.
  if (info.attendanceRequest) {
    return info.attendanceRequest.requestType === 'WFH'
      ? <span style={{ ...DAY_TAG_STYLE, background: 'rgba(76,141,214,.15)', color: '#4C8DD6' }}>WFH</span>
      : <span style={{ ...DAY_TAG_STYLE, background: 'rgba(224,169,59,.18)', color: '#E0A93B' }}>Partial Day</span>;
  }
  if (info.record) {
    return <StatusPill status={info.record.status} />;
  }
  // Nothing else going on for a weekend day — Keka's reference UI shows this explicitly rather
  // than a blank cell/row.
  if (info.isWeekend) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(155,161,172,.15)', color: '#9BA1AC' }}>Weekly-off</span>;
  }
  return null;
}

function DayCellBadge({ info }: { info: DayInfo }) {
  return <>{primaryDayBadge(info)}</>;
}

function MonthCalendar({
  year, month, dayInfo, selectedDate, onSelect, onPrev, onNext,
}: {
  year: number; month: number;
  dayInfo: (day: number) => DayInfo;
  selectedDate: string | null;
  onSelect: (iso: string) => void;
  onPrev: () => void;
  onNext: () => void;
}) {
  const cells = useMemo(() => buildCalendarCells(year, month), [year, month]);

  return (
    <div style={panelStyle}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 16px', borderBottom: '1px solid var(--line)' }}>
        <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 14, color: 'var(--txt)' }}>
          {calendarMonthLabel(year, month)}
        </span>
        <div style={{ display: 'flex', gap: 6 }}>
          <button onClick={onPrev} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '4px 8px', cursor: 'pointer', color: 'var(--txt-mut)', display: 'flex' }}>
            <ChevronLeft size={14} />
          </button>
          <button onClick={onNext} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '4px 8px', cursor: 'pointer', color: 'var(--txt-mut)', display: 'flex' }}>
            <ChevronRight size={14} />
          </button>
        </div>
      </div>
      <div style={{ padding: 12 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 5, marginBottom: 5 }}>
          {['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map(d => (
            <div key={d} style={{ fontSize: 10, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', textAlign: 'center', letterSpacing: '.05em' }}>
              {d}
            </div>
          ))}
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 5 }}>
          {cells.map((day, i) => {
            if (day == null) return <div key={i} />;
            const info = dayInfo(day);
            const selected = selectedDate === info.iso;
            const disabled = info.isFuture || info.isBeforeJoining;
            return (
              <button
                key={i}
                onClick={() => !disabled && onSelect(info.iso)}
                disabled={disabled}
                style={{
                  minHeight: 58, borderRadius: 6, padding: '5px 5px', textAlign: 'left',
                  background: selected ? 'rgba(177,17,22,.10)' : 'var(--raised)',
                  border: info.isToday ? '1.5px solid var(--brand)' : selected ? '1px solid var(--brand)' : '1px solid var(--line)',
                  cursor: disabled ? 'default' : 'pointer',
                  opacity: disabled ? 0.45 : 1,
                  display: 'flex', flexDirection: 'column', gap: 2,
                }}
              >
                <span style={{ fontSize: 11.5, fontWeight: 600, color: info.isWeekend ? 'var(--txt-dim)' : 'var(--txt)' }}>
                  {day}
                </span>
                {!info.isBeforeJoining && <DayCellBadge info={info} />}
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}

// ─── My attendance (punch card + attendance calendar) ─────────────────────────

// Mirrors AttendanceRequestService.PARTIAL_DAY_MONTHLY_LIMIT_HOURS — the backend is the
// authoritative enforcement (across every non-rejected request in the month); this is only
// used for an early, single-request client-side check before hitting the network.
const PARTIAL_DAY_MONTHLY_LIMIT_HOURS = 2;

/**
 * Keka's "Notify" field — search and pick a specific colleague to alert about this request.
 * Purely informational (distinct from the Assign To approver above it) — per product rule, only
 * shown to plain employees, not managers/HR/Super Admin.
 */
function NotifyEmployeeField({ token, value, onChange }: {
  token: string;
  value: DirectoryEntry | null;
  onChange: (entry: DirectoryEntry | null) => void;
}) {
  const role = toShellRole(useAuthStore((s) => s.user?.role));
  const [query, setQuery] = useState('');
  const [directory, setDirectory] = useState<DirectoryEntry[]>([]);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (role !== 'Employee') return;
    directoryApi.list(token).then(setDirectory).catch(() => setDirectory([]));
  }, [token, role]);

  if (role !== 'Employee') return null;

  const matches = query.trim()
    ? directory
        .filter((d) => d.fullName.toLowerCase().includes(query.trim().toLowerCase()) || d.email.toLowerCase().includes(query.trim().toLowerCase()))
        .slice(0, 8)
    : [];

  return (
    <Field label="Notify (optional)">
      {value ? (
        <div style={{ ...inputStyle, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <span>{value.fullName}</span>
          <button onClick={() => onChange(null)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', display: 'flex' }}>
            <X size={14} />
          </button>
        </div>
      ) : (
        <div style={{ position: 'relative' }}>
          <input
            value={query}
            onChange={(e) => { setQuery(e.target.value); setOpen(true); }}
            onFocus={() => setOpen(true)}
            onBlur={() => setTimeout(() => setOpen(false), 150)}
            placeholder="Search employee…"
            style={inputStyle}
          />
          {open && matches.length > 0 && (
            <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, zIndex: 20, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, marginTop: 4, maxHeight: 180, overflowY: 'auto', boxShadow: '0 8px 24px rgba(0,0,0,.3)' }}>
              {matches.map((m) => (
                <div
                  key={m.userId}
                  onMouseDown={(e) => { e.preventDefault(); onChange(m); setQuery(''); setOpen(false); }}
                  style={{ padding: '8px 10px', fontSize: 12.5, cursor: 'pointer', color: 'var(--txt)' }}
                >
                  {m.fullName} <span style={{ color: 'var(--txt-dim)', fontSize: 11 }}>({m.email})</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </Field>
  );
}

/**
 * Adds/subtracts minutes from a "HH:mm[:ss]" time-of-day string, wrapping past midnight — same
 * crossing-midnight reasoning as TodaysTimingsPanel. Returns a fake ISO string so the existing
 * zone-less formatTime() can render it.
 */
function shiftClockTime(hhmmss: string, deltaMinutes: number): string {
  const base = minutesSinceMidnight(`2000-01-01T${hhmmss}`) ?? 0;
  const total = (((base + deltaMinutes) % 1440) + 1440) % 1440;
  const h = Math.floor(total / 60);
  const m = total % 60;
  return `2000-01-01T${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:00`;
}

const PARTIAL_DAY_MODE_OPTIONS: { value: PartialDayMode; label: string }[] = [
  { value: 'LATE_ARRIVE', label: 'Late Arrival' },
  { value: 'INTERVENING_TIMEOFF', label: 'Intervening Time-off' },
  { value: 'LEAVING_EARLY', label: 'Leaving Early' },
];

/** Keka's fixed duration presets for Intervening Time-off's "for" dropdown. */
const INTERVENING_DURATION_OPTIONS = [15, 30, 45, 60, 90, 120];

// ─── Attendance Request (WFH / Partial Day) submit modal ──────────────────────
// Reuses the regularization approver-assignment pattern (Assign To dropdown sourced from the
// existing GET /attendance/regularization/approvers endpoint) rather than duplicating it.
// Partial Day itself is one request type with three Keka-reference sub-modes (radio), not three
// separate request types — see PARTIAL_DAY_MODE_OPTIONS / AttendanceRequestService.
function AttendanceRequestModal({ presetType, onClose, onSaved, token, initialDate }: {
  presetType?: AttendanceRequestType;
  onClose: () => void;
  onSaved: (r: AttendanceRequestRecord) => void;
  token: string;
  /** Pre-selects a date — e.g. opened from a specific day's Attendance Log entry. */
  initialDate?: string;
}) {
  const { showToast } = useToast();
  const { formatTime } = useTimeFormat();
  const [requestType, setRequestType] = useState<AttendanceRequestType>(presetType ?? 'WFH');
  const [requestDate, setRequestDate] = useState(initialDate ?? todayIsoDate());
  const [partialDayMode, setPartialDayMode] = useState<PartialDayMode>('LATE_ARRIVE');
  const [partialDayMinutes, setPartialDayMinutes] = useState('60');
  // Intervening Time-off only — Keka anchors this mode to an explicit clock time ("Will leave
  // at") rather than a duration relative to the shift boundary, since the break can start
  // anywhere during the day.
  const [leaveAtText, setLeaveAtText] = useState('');
  const [leaveAtTouched, setLeaveAtTouched] = useState(false);
  const [reason, setReason] = useState('');
  const [managerUserId, setManagerUserId] = useState('');
  const [notifyEntry, setNotifyEntry] = useState<DirectoryEntry | null>(null);
  const [approvers, setApprovers] = useState<ApproverOption[]>([]);
  const [config, setConfig] = useState<AttendanceConfig | null>(null);
  const [balance, setBalance] = useState<{ usedHours: number; limitHours: number; remainingHours: number } | null>(null);
  const [showBalance, setShowBalance] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    regularizationApi.approvers(token).then(setApprovers).catch(() => { /* dropdown degrades to empty */ });
    attendanceApi.config(token).then(setConfig).catch(() => setConfig(null));
  }, [token]);

  useEffect(() => {
    if (requestType !== 'PARTIAL_DAY' || !requestDate) { setBalance(null); return; }
    attendanceRequestApi.partialDayBalance(requestDate, token).then(setBalance).catch(() => setBalance(null));
  }, [requestType, requestDate, token]);

  // Reset mode-specific fields on switch so a stale value from one mode (e.g. a free-typed
  // number) never leaks into another mode's different widget (e.g. the fixed-option dropdown).
  useEffect(() => {
    setPartialDayMinutes('60');
    setLeaveAtText('');
    setLeaveAtTouched(false);
  }, [partialDayMode]);

  const partialDayHours = Number(partialDayMinutes) / 60;
  const remainingMinutes = balance ? Math.round(balance.remainingHours * 60) : null;
  const limitMinutes = balance ? Math.round(balance.limitHours * 60) : null;

  const leaveAtValue = parseTimeText(leaveAtText);
  const timeRequiredError = partialDayMode === 'INTERVENING_TIMEOFF' && leaveAtTouched
    && (!leaveAtValue || !isTimeValueComplete(leaveAtValue));

  // Keka's blue/green computed line — only meaningful for the two edge modes, since Intervening
  // Time-off happens mid-day and doesn't shift the reported arrival/departure clock time (it gets
  // its own balance table instead — see the "View Available Balance" popover below).
  const computedMessage = useMemo(() => {
    const minutes = Number(partialDayMinutes);
    if (!config?.shiftStart || !minutes || minutes <= 0) return null;
    if (partialDayMode === 'LATE_ARRIVE') {
      return `You will have to reach office by ${formatTime(shiftClockTime(config.shiftStart, minutes))}`;
    }
    if (partialDayMode === 'LEAVING_EARLY' && config.shiftEnd) {
      return `You can leave office at ${formatTime(shiftClockTime(config.shiftEnd, -minutes))}`;
    }
    return null;
  }, [config, partialDayMode, partialDayMinutes, formatTime]);

  /** The actual API call — invoked directly when within balance, or from the confirmation
   * dialog when the employee chooses to submit anyway despite exceeding it. */
  async function doSubmit() {
    setSubmitting(true);
    setError(null);
    try {
      const payload: SubmitAttendanceRequestPayload = {
        requestType,
        requestDate,
        reason: reason.trim(),
        partialDayHours: requestType === 'PARTIAL_DAY' ? partialDayHours : undefined,
        partialDayMode: requestType === 'PARTIAL_DAY' ? partialDayMode : undefined,
        managerUserId: managerUserId || undefined,
        notifyUserId: notifyEntry?.userId || undefined,
      };
      const created = await attendanceRequestApi.submit(payload, token);
      showToast('success', `${requestType === 'WFH' ? 'Work From Home' : 'Partial Day'} request submitted for approval`);
      onSaved(created);
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to submit request';
      setError(msg);
      showToast('error', msg);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleSubmit() {
    if (!reason.trim()) { setError('Reason is required'); return; }
    // Any date is selectable while typing (no min/max on the field) — validated only now, at
    // submit time.
    if (requestDate < todayIsoDate()) {
      setError('Cannot request for past dates');
      return;
    }
    if (requestType === 'PARTIAL_DAY' && (!partialDayMinutes || Number(partialDayMinutes) <= 0)) {
      setError('Duration must be greater than zero');
      return;
    }
    // The block is based on the monthly allowance already being fully used, not on the
    // requested value itself — a request of exactly (or under) the cap is always allowed as
    // long as some balance remains; once the 120-minute allowance is fully used, any further
    // request is blocked regardless of how many minutes it asks for.
    const partialDayLimitMinutes = PARTIAL_DAY_MONTHLY_LIMIT_HOURS * 60;
    if (requestType === 'PARTIAL_DAY' && remainingMinutes != null && remainingMinutes <= 0) {
      setError(`You have used your ${partialDayLimitMinutes} minutes. You are not allowed to raise a request for more than ${partialDayLimitMinutes} minutes.`);
      return;
    }
    if (partialDayMode === 'INTERVENING_TIMEOFF' && requestType === 'PARTIAL_DAY') {
      setLeaveAtTouched(true);
      if (!leaveAtValue || !isTimeValueComplete(leaveAtValue)) {
        setError('Time is required');
        return;
      }
    }
    setError(null);
    await doSubmit();
  }

  const partialDayModeLabel = partialDayMode === 'LATE_ARRIVE' ? 'Will come late by'
    : partialDayMode === 'LEAVING_EARLY' ? 'Will leave early by'
    : 'Will leave at';

  // Balance is intentionally not part of this — insufficient balance never disables Submit,
  // it's confirmed at click time instead (see handleSubmit).
  const canSubmit = !!reason.trim() && !submitting
    && (requestType !== 'PARTIAL_DAY' || (!timeRequiredError && Number(partialDayMinutes) > 0));

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 440 }}>
        <ModalHeader title={presetType === 'WFH' ? 'Work From Home' : presetType === 'PARTIAL_DAY' ? 'Request Partial Day' : 'New Attendance Request'} onClose={onClose} />
        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {error && <OhNoError message={error} onDismiss={() => setError(null)} />}
          {!presetType && (
            <Field label="Request Type">
              <select value={requestType} onChange={(e) => setRequestType(e.target.value as AttendanceRequestType)} style={inputStyle}>
                <option value="WFH">Work From Home</option>
                <option value="PARTIAL_DAY">Partial Day</option>
              </select>
            </Field>
          )}
          <Field label="Select Date">
            <input type="date" value={requestDate} onChange={(e) => setRequestDate(e.target.value)} style={inputStyle} />
          </Field>
          {requestType === 'PARTIAL_DAY' && (
            <>
              {config?.shiftStart && (
                <div style={{ fontSize: 11.5, color: 'var(--txt-mut)' }}>
                  Shift timing: {formatTime(`${todayIsoDate()}T${config.shiftStart}`)}
                  {config.shiftEnd && <> – {formatTime(`${todayIsoDate()}T${config.shiftEnd}`)}</>}
                </div>
              )}
              <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap' }}>
                {PARTIAL_DAY_MODE_OPTIONS.map((opt) => (
                  <label key={opt.value} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5, color: 'var(--txt)', cursor: 'pointer' }}>
                    <input type="radio" name="partialDayMode" checked={partialDayMode === opt.value} onChange={() => setPartialDayMode(opt.value)} />
                    {opt.label}
                  </label>
                ))}
              </div>
              {partialDayMode === 'INTERVENING_TIMEOFF' ? (
                <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, flexWrap: 'wrap' }}>
                  <div style={{ flex: '1 1 140px' }}>
                    <TimeTextInput
                      label={partialDayModeLabel}
                      value={leaveAtText}
                      touched={leaveAtTouched}
                      onChange={setLeaveAtText}
                      onBlur={() => setLeaveAtTouched(true)}
                      requiredMessage="Time is required"
                    />
                  </div>
                  <Field label="For">
                    <select value={partialDayMinutes} onChange={(e) => setPartialDayMinutes(e.target.value)} style={{ ...inputStyle, width: 110 }}>
                      {INTERVENING_DURATION_OPTIONS.map((m) => (
                        <option key={m} value={m}>{m} minutes</option>
                      ))}
                    </select>
                  </Field>
                </div>
              ) : (
                <Field label={partialDayModeLabel}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <input type="number" min="1" step="1" value={partialDayMinutes} onChange={(e) => setPartialDayMinutes(e.target.value)} style={{ ...inputStyle, width: 100 }} />
                    <span style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>minutes</span>
                  </div>
                </Field>
              )}
              {computedMessage && (
                <div style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, padding: '8px 10px', fontSize: 12, color: 'var(--txt)' }}>
                  {computedMessage}
                </div>
              )}
              <div style={{ position: 'relative' }}>
                <button
                  onClick={() => setShowBalance((v) => !v)}
                  style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'none', border: 'none', color: 'var(--brand)', fontSize: 12, fontWeight: 600, cursor: 'pointer', padding: 0 }}
                >
                  <Info size={13} /> View Available Balance
                </button>
                {showBalance && (
                  <div style={{ position: 'absolute', top: '100%', left: 0, marginTop: 6, zIndex: 30, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 9, boxShadow: '0 8px 24px rgba(0,0,0,.35)', minWidth: 260, overflow: 'hidden' }}>
                    {!balance ? (
                      <div style={{ padding: 12, fontSize: 12, color: 'var(--txt-mut)' }}>Loading balance…</div>
                    ) : (
                      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                        <thead>
                          <tr>
                            <th style={{ textAlign: 'left', padding: '8px 12px', color: 'var(--txt-dim)', fontWeight: 600, borderBottom: '1px solid var(--line)' }}>Period</th>
                            <th style={{ textAlign: 'right', padding: '8px 12px', color: 'var(--txt-dim)', fontWeight: 600, borderBottom: '1px solid var(--line)' }}>Balance</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr>
                            <td style={{ padding: '8px 12px', color: 'var(--txt)' }}>{formatShortDay(requestDate)}</td>
                            <td style={{ padding: '8px 12px', color: 'var(--txt)', textAlign: 'right' }}>{remainingMinutes}/{limitMinutes} minutes</td>
                          </tr>
                          <tr>
                            <td style={{ padding: '8px 12px', color: 'var(--txt)' }}>{formatShortDay(monthStartIso(requestDate))} - {formatShortDay(monthEndIso(requestDate))}</td>
                            <td style={{ padding: '8px 12px', color: 'var(--txt)', textAlign: 'right' }}>{remainingMinutes}/{limitMinutes} minutes</td>
                          </tr>
                        </tbody>
                      </table>
                    )}
                  </div>
                )}
              </div>
            </>
          )}
          <Field label="Reason *">
            <textarea
              style={{ ...inputStyle, minHeight: 70, resize: 'vertical', fontFamily: 'inherit' }}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Why are you requesting this?"
              autoFocus
            />
          </Field>
          <Field label="Assign To (optional)">
            <select value={managerUserId} onChange={(e) => setManagerUserId(e.target.value)} style={inputStyle}>
              <option value="">Current manager</option>
              {approvers.map((a) => (
                <option key={a.userId} value={a.userId}>{a.fullName} ({a.roleCode})</option>
              ))}
            </select>
          </Field>
          <NotifyEmployeeField token={token} value={notifyEntry} onChange={setNotifyEntry} />
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 6 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button
              onClick={handleSubmit}
              disabled={!canSubmit}
              style={{ background: canSubmit ? 'var(--brand)' : 'var(--raised2)', color: canSubmit ? '#fff' : 'var(--txt-dim)', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: !canSubmit ? 'not-allowed' : 'pointer' }}
            >
              {submitting ? 'Submitting…' : 'Submit for Approval'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Attendance Stats: Me vs My Team ───────────────────────────────────────────
type StatsRange = 'WEEK' | 'MONTH';

function AttendanceStatsPanel({ token }: { token: string }) {
  const [range, setRange] = useState<StatsRange>('WEEK');
  const [stats, setStats] = useState<AttendanceStats | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // Guards against a slower, earlier request (e.g. a stale MONTH fetch from before the user
    // switched to WEEK) resolving after a newer one and silently overwriting it with the wrong
    // range's data — same cancellation pattern used by every other fetch effect on this page.
    let cancelled = false;
    setLoading(true);
    const to = todayIsoDate();
    const from = range === 'WEEK' ? isoDaysAgo(6) : isoDaysAgo(29);
    attendanceApi.stats(from, to, token)
      .then((s) => { if (!cancelled) setStats(s); })
      .catch(() => { if (!cancelled) setStats(null); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [range, token]);

  const rowStyle: React.CSSProperties = { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, padding: '8px 0' };

  return (
    <div style={{ ...panelStyle, padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 5 }}>
        <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt)' }}>Attendance Stats</span>
        <select value={range} onChange={(e) => setRange(e.target.value as StatsRange)} style={{ ...inputStyle, width: 'auto', padding: '3px 7px', fontSize: 11.5 }}>
          <option value="WEEK">Last Week</option>
          <option value="MONTH">Last 30 Days</option>
        </select>
      </div>
      {loading ? (
        <div style={{ color: 'var(--txt-dim)', fontSize: 12.5, padding: '10px 0' }}>Loading…</div>
      ) : !stats ? (
        <div style={{ color: 'var(--txt-dim)', fontSize: 12.5, padding: '10px 0' }}>Stats unavailable right now.</div>
      ) : (
        <>
          <div style={{ display: 'flex', fontSize: 10, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.05em' }}>
            <span style={{ flex: 1 }} />
            <span style={{ width: 84, textAlign: 'right' }}>Avg hrs/day</span>
            <span style={{ width: 100, textAlign: 'right' }}>On-time %</span>
          </div>
          <div style={{ ...rowStyle, borderTop: '1px solid var(--line)' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 12.5, color: 'var(--txt)', fontWeight: 600 }}>
              <User size={13} style={{ color: 'var(--brand)' }} /> Me
            </span>
            <span style={{ width: 84, textAlign: 'right', fontSize: 13, fontWeight: 700, color: 'var(--txt)' }}>
              {stats.me.avgHoursPerDay != null ? `${stats.me.avgHoursPerDay}h` : dash}
            </span>
            <span style={{ width: 100, textAlign: 'right', fontSize: 13, fontWeight: 700, color: 'var(--txt)' }}>
              {stats.me.onTimeArrivalPercent != null ? `${stats.me.onTimeArrivalPercent}%` : dash}
            </span>
          </div>
          <div style={{ ...rowStyle, borderTop: '1px solid var(--line)' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 12.5, color: 'var(--txt)', fontWeight: 600 }}>
              <Users size={13} style={{ color: 'var(--txt-dim)' }} /> My Team
            </span>
            <span style={{ width: 84, textAlign: 'right', fontSize: 13, fontWeight: 700, color: 'var(--txt)' }}>
              {stats.team.avgHoursPerDay != null ? `${stats.team.avgHoursPerDay}h` : dash}
            </span>
            <span style={{ width: 100, textAlign: 'right', fontSize: 13, fontWeight: 700, color: 'var(--txt)' }}>
              {stats.team.onTimeArrivalPercent != null ? `${stats.team.onTimeArrivalPercent}%` : dash}
            </span>
          </div>
          {stats.teamSize === 0 && (
            <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 4 }}>No peers on record under your current manager yet.</div>
          )}
        </>
      )}
    </div>
  );
}

// ─── Today's Timings ────────────────────────────────────────────────────────────
// No shift-end (or per-employee shift) exists yet — ONEHR-108 shift assignment is not built.
// The progress bar target is deliberately the existing fullDayMinHours config, labeled as
// "progress toward a full day" rather than "shift end", so nothing here is invented.
function TodaysTimingsPanel({ today, config, workedMinutesToday }: {
  today: TodayAttendance | null;
  config: AttendanceConfig | null;
  workedMinutesToday: number | null;
}) {
  const { formatTime, formatDuration } = useTimeFormat();

  const fullDayTargetMinutes = fullDayTargetMinutesFor(config);
  const progressPct = fullDayTargetMinutes && workedMinutesToday != null
    ? Math.min(100, Math.round((workedMinutesToday / fullDayTargetMinutes) * 100))
    : 0;
  const breakUsed = today?.breakUsedMinutes ?? 0;
  const breakBudget = today?.breakBudgetMinutes ?? config?.dailyBreakBudgetMinutes ?? 60;
  const breakPct = breakBudget > 0 ? Math.min(100, Math.round((breakUsed / breakBudget) * 100)) : 0;

  return (
    <div style={{ ...panelStyle, padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 9 }}>
      <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt)' }}>Today's Timings</span>
      {config && (
        <div style={{ fontSize: 11.5, color: 'var(--txt-mut)' }}>
          {config.shiftEnd
            ? <>Shift {formatTime(`${todayIsoDate()}T${config.shiftStart}`)} – {formatTime(`${todayIsoDate()}T${config.shiftEnd}`)} · grace {config.lateGraceMinutes}m</>
            : <>Shift starts {formatTime(`${todayIsoDate()}T${config.shiftStart}`)} · grace {config.lateGraceMinutes}m</>}
        </div>
      )}
      <div>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 10.5, color: 'var(--txt-dim)', marginBottom: 4 }}>
          <span>{config?.shiftEnd ? 'Progress toward shift end' : `Progress toward a full day${config ? ` (${config.fullDayMinHours}h)` : ''}`}</span>
          <span>{formatDuration(workedMinutesToday) ?? dash}</span>
        </div>
        <div style={{ height: 7, borderRadius: 4, background: 'var(--raised2)', overflow: 'hidden' }}>
          <div style={{ height: '100%', width: `${progressPct}%`, background: 'var(--brand)', borderRadius: 4, transition: 'width .3s' }} />
        </div>
      </div>
      <div>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 10.5, color: 'var(--txt-dim)', marginBottom: 4 }}>
          <span>Break used</span>
          <span>{breakUsed} / {breakBudget} min</span>
        </div>
        <div style={{ height: 5, borderRadius: 3, background: 'var(--raised2)', overflow: 'hidden' }}>
          <div style={{ height: '100%', width: `${breakPct}%`, background: '#E0A93B', borderRadius: 3, transition: 'width .3s' }} />
        </div>
      </div>
    </div>
  );
}

// ─── Quick Actions ──────────────────────────────────────────────────────────────
/**
 * Check-in / Check-out / Web Check-In — no manager approval needed. All three, plus the
 * calendar's own Today's-workday Check In/Check Out buttons and Today's Timings, are driven by
 * the exact same `today: TodayAttendance` (MyAttendance's state, refreshed via
 * `refreshTodayAndMonth`) so every entry point reflects one consistent attendance state — see
 * MyAttendance's `punch`/`refreshTodayAndMonth`. Checking in here calls the identical
 * attendanceApi.checkIn used by the calendar panel (so the late-arrival penalty applies the
 * same way regardless of which button was clicked); Web Check-In still goes through
 * webClockInApi.submit since it alone carries a reason, but checkout is always the one shared
 * `onCheckOut` — nothing calls webClockInApi.checkOut from this page anymore.
 */
interface CheckInStateProps {
  actionStyle: React.CSSProperties;
  today: TodayAttendance | null;
  loading: boolean;
  submitting: boolean;
  onCheckIn: () => Promise<void>;
  onCheckOut: () => Promise<void>;
}

function CheckInAction({ actionStyle, today, loading, submitting, onCheckIn, onCheckOut }: CheckInStateProps) {
  const { formatTime } = useTimeFormat();
  const [confirmingCheckout, setConfirmingCheckout] = useState(false);

  function handlePrimaryClick() {
    // Checking out gets a confirmation popup since it closes out the day; checking in
    // still happens directly, no confirmation needed.
    if (today?.canCheckOut) {
      setConfirmingCheckout(true);
      return;
    }
    if (today?.canCheckIn) {
      onCheckIn();
    }
  }

  async function handleConfirmCheckout() {
    try {
      await onCheckOut();
    } finally {
      setConfirmingCheckout(false);
    }
  }

  const dayComplete = !!today && !today.canCheckIn && !today.canCheckOut;
  const disablePrimary = loading || submitting || dayComplete;
  const label = dayComplete
    ? `Checked out${today?.record?.checkOutAt ? ` at ${formatTime(today.record.checkOutAt)}` : ''}`
    : today?.canCheckOut ? 'Check-out' : 'Check-in';

  return (
    <>
      <button
        onClick={handlePrimaryClick}
        disabled={disablePrimary}
        style={{ ...actionStyle, opacity: disablePrimary ? 0.6 : 1, cursor: disablePrimary ? 'default' : 'pointer' }}
      >
        <Laptop size={14} style={{ color: 'var(--brand)' }} /> {loading ? 'Check-in' : label}
      </button>
      {confirmingCheckout && (
        <div style={overlayStyle}>
          <div style={{ ...modalStyle, maxWidth: 380 }}>
            <ModalHeader title="Check out?" onClose={() => setConfirmingCheckout(false)} />
            <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 16 }}>
              <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.5 }}>
                Are you sure you want to check out?
              </p>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button
                  onClick={() => setConfirmingCheckout(false)}
                  disabled={submitting}
                  style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: submitting ? 'not-allowed' : 'pointer' }}
                >
                  Cancel
                </button>
                <button
                  onClick={handleConfirmCheckout}
                  disabled={submitting}
                  style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}
                >
                  {submitting ? 'Checking out…' : 'OK'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

/**
 * Distinct from the one-click Check-in above: for an ad-hoc remote/WFH day where the employee
 * wants to record why they're checking in off-site (mirrors Keka's Web Clock-In request, which
 * requires a reason). Enabled under the exact same condition as Check-in (today.canCheckIn) and,
 * once submitted, refreshes the same shared `today` — so either button immediately reflects in
 * the other, and checkout (handled solely by CheckInAction's toggle above) works no matter which
 * one opened the day.
 */
function WebCheckInAction({ token, actionStyle, today, loading, onSubmitted }: {
  token: string;
  actionStyle: React.CSSProperties;
  today: TodayAttendance | null;
  loading: boolean;
  onSubmitted: () => Promise<unknown>;
}) {
  const { showToast } = useToast();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  const disabled = loading || !today?.canCheckIn;

  async function handleSubmit() {
    const trimmed = reason.trim();
    if (!trimmed) {
      showToast('error', 'Please enter a comment for the web clock-in request');
      return;
    }
    setBusy(true);
    try {
      await webClockInApi.submit(trimmed, token);
      await onSubmitted();
      showToast('success', 'Checked in remotely');
      setOpen(false);
      setReason('');
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Action failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        disabled={disabled}
        style={{ ...actionStyle, opacity: disabled ? 0.6 : 1, cursor: disabled ? 'default' : 'pointer' }}
      >
        <Wifi size={14} style={{ color: 'var(--brand)' }} /> Web Check-In
      </button>
      {open && (
        <div style={overlayStyle}>
          <div style={{ ...modalStyle, maxWidth: 480 }}>
            <ModalHeader title='Web Clock-In Request' onClose={() => !busy && setOpen(false)} />
            <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 10 }}>
              <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.5 }}>
                Adding a comment is required for a web clock-in request.
              </p>
              <div>
                <textarea
                  value={reason}
                  onChange={(e) => setReason(e.target.value.slice(0, 1024))}
                  rows={4}
                  autoFocus
                  maxLength={1024}
                  style={{ width: '100%', resize: 'vertical', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 10px', fontSize: 13, background: 'var(--raised)', color: 'var(--txt)', fontFamily: 'inherit' }}
                />
                <div style={{ textAlign: 'right', fontSize: 11, color: 'var(--txt-mut)', marginTop: 4 }}>
                  {reason.length} / 1024
                </div>
              </div>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button
                  onClick={() => setOpen(false)}
                  disabled={busy}
                  style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: busy ? 'not-allowed' : 'pointer' }}
                >
                  Cancel
                </button>
                <button
                  onClick={handleSubmit}
                  disabled={busy || !reason.trim()}
                  style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: (busy || !reason.trim()) ? 'not-allowed' : 'pointer', opacity: (busy || !reason.trim()) ? 0.7 : 1 }}
                >
                  {busy ? 'Confirming…' : 'Confirm'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

function QuickActionsPanel({ token, today, todayLoading, submitting, onCheckIn, onCheckOut, onWebCheckInSubmitted }: {
  token: string;
  today: TodayAttendance | null;
  todayLoading: boolean;
  submitting: boolean;
  onCheckIn: () => Promise<void>;
  onCheckOut: () => Promise<void>;
  onWebCheckInSubmitted: () => Promise<unknown>;
}) {
  const [modal, setModal] = useState<'WFH' | 'PARTIAL_DAY' | 'POLICY' | null>(null);

  const actionStyle: React.CSSProperties = {
    display: 'flex', alignItems: 'center', gap: 8, background: 'var(--raised)', border: '1px solid var(--line2)',
    borderRadius: 7, padding: '8px 11px', fontSize: 12.5, color: 'var(--txt)', cursor: 'pointer', fontWeight: 600, width: '100%',
    textAlign: 'left' as const,
  };

  return (
    <div style={{ ...panelStyle, padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 7 }}>
      <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt)', marginBottom: 2 }}>Actions</span>
      <CheckInAction actionStyle={actionStyle} today={today} loading={todayLoading} submitting={submitting} onCheckIn={onCheckIn} onCheckOut={onCheckOut} />
      <WebCheckInAction token={token} actionStyle={actionStyle} today={today} loading={todayLoading} onSubmitted={onWebCheckInSubmitted} />
      <button style={actionStyle} onClick={() => setModal('WFH')}>
        <Home size={14} style={{ color: 'var(--brand)' }} /> Work From Home
      </button>
      <button style={actionStyle} onClick={() => setModal('PARTIAL_DAY')}>
        <Sun size={14} style={{ color: 'var(--brand)' }} /> Partial Day Request
      </button>
      <button style={actionStyle} onClick={() => setModal('POLICY')}>
        <FileText size={14} style={{ color: 'var(--brand)' }} /> Attendance Policy
      </button>
      {(modal === 'WFH' || modal === 'PARTIAL_DAY') && (
        <AttendanceRequestModal
          presetType={modal}
          token={token}
          onClose={() => setModal(null)}
          onSaved={() => { /* toast already shown by the modal itself */ }}
        />
      )}
      {modal === 'POLICY' && (
        <AttendancePolicyModal onClose={() => setModal(null)} />
      )}
    </div>
  );
}

// ─── Attendance Log: per-day visual timeline ───────────────────────────────────
// One 24-hour axis per day, with a clickable presence bar per punch session — supports
// multiple check-in/out blocks per day (e.g. a lunch break) when the real per-day punches
// have been fetched (see MyAttendance's punchesByDate cache); falls back to the day's single
// checkIn/checkOut pair otherwise. Never rendered for leave/holiday/weekly-off days — those
// show a plain text label instead, since a timeline would be misleading there.

/** Sum of gaps between consecutive closed punch sessions — mirrors AttendanceService's break calc. */
function computeBreakMinutesFromPunches(punches: Punch[]): number {
  let total = 0;
  for (let i = 0; i < punches.length - 1; i++) {
    const gapStart = punches[i].checkOutAt;
    const gapEnd = punches[i + 1].checkInAt;
    if (gapStart && gapEnd) {
      total += Math.round((wallClockMs(gapEnd) - wallClockMs(gapStart)) / 60000);
    }
  }
  return total;
}

interface RowMetrics {
  /** True only for today's row while still checked in — the only case worked minutes are still live. */
  openSession: boolean;
  effectiveMinutes: number | null;
  breakMinutes: number | null;
  grossMinutes: number | null;
}

/** Effective/Break/Gross for one Attendance Log row. Gross = Effective + Break (elapsed incl. breaks). */
function computeRowMetrics(info: DayInfo, punches: Punch[] | undefined, workedMinutesToday: number | null): RowMetrics {
  if (!info.record?.checkInAt) {
    return { openSession: false, effectiveMinutes: null, breakMinutes: null, grossMinutes: null };
  }
  const openSession = info.iso === todayIsoDate() && !info.record.checkOutAt;
  const effectiveMinutes = openSession ? workedMinutesToday : (info.record.workedMinutes ?? null);
  const breakMinutes = punches ? computeBreakMinutesFromPunches(punches) : 0;
  const grossMinutes = effectiveMinutes != null ? effectiveMinutes + breakMinutes : null;
  return { openSession, effectiveMinutes, breakMinutes, grossMinutes };
}

/** One presence bar — purely presentational, no interactivity (the timeline is visual-only). */
/**
 * One presence bar, proportionally positioned/sized against the 24-hour track. Hovering shows a
 * small "Logged in HH:MM – HH:MM" tooltip centered directly above the bar — no click, no extra
 * day-details content here (that lives behind the row's View button instead).
 */
function TimelineBar({ checkInAt, checkOutAt, leftPct, widthPct }: {
  checkInAt: string; checkOutAt: string | null; leftPct: number; widthPct: number;
}) {
  const { formatTime } = useTimeFormat();
  const [coords, setCoords] = useState<{ top: number; left: number } | null>(null);
  const barRef = useRef<HTMLDivElement>(null);

  function show() {
    const rect = barRef.current?.getBoundingClientRect();
    if (!rect) return;
    setCoords({ top: rect.top - 6, left: rect.left + rect.width / 2 });
  }
  function hide() {
    setCoords(null);
  }

  const label = `Logged in ${formatTime(checkInAt) ?? '—'} - ${checkOutAt ? formatTime(checkOutAt) : 'now'}`;

  return (
    <>
      <div
        ref={barRef}
        onMouseEnter={show}
        onMouseLeave={hide}
        style={{
          position: 'absolute', left: `${leftPct}%`, width: `${widthPct}%`, minWidth: 3,
          top: 0, height: '100%',
          background: checkOutAt ? 'var(--brand)' : '#E0A93B',
          borderRadius: 3,
        }}
      />
      {coords && createPortal(
        <div
          role="tooltip"
          style={{
            position: 'fixed', top: coords.top, left: coords.left, transform: 'translate(-50%, -100%)',
            background: 'var(--raised2)', color: 'var(--txt)', border: '1px solid var(--line2)', borderRadius: 6,
            padding: '5px 9px', fontSize: 11.5, fontWeight: 600, whiteSpace: 'nowrap',
            boxShadow: '0 6px 18px rgba(0,0,0,.35)', zIndex: 1000, pointerEvents: 'none',
          }}
        >
          {label}
          <div style={{
            position: 'absolute', bottom: -4, left: '50%', transform: 'translateX(-50%)',
            width: 0, height: 0, borderLeft: '4px solid transparent', borderRight: '4px solid transparent',
            borderTop: '4px solid var(--raised2)',
          }} />
        </div>,
        document.body,
      )}
    </>
  );
}

/** Compact, column-filling 24-hour presence timeline. Bars are proportional to actual login/logout duration; hovering a bar shows a small tooltip (see TimelineBar). No day-details content lives here — that's behind the row's View button. */
function AttendanceTimeline({ info, punches, punchesLoading }: {
  info: DayInfo; punches: Punch[] | undefined; punchesLoading: boolean;
}) {
  if (info.holidayName) {
    return <span style={{ fontSize: 11, color: 'var(--txt-dim)', whiteSpace: 'nowrap' }}>Company holiday — {info.holidayName}</span>;
  }
  if (info.leaveTypeName) {
    return <span style={{ fontSize: 11, color: 'var(--txt-dim)', whiteSpace: 'nowrap' }}>On leave — {info.leaveTypeName}</span>;
  }
  if (info.isWeekend && !info.record && !info.attendanceRequest) {
    return <span style={{ fontSize: 11, color: 'var(--txt-dim)', whiteSpace: 'nowrap' }}>Full day Weekly-off</span>;
  }
  const record = info.record;
  if (!record?.checkInAt) {
    return <span style={{ fontSize: 11, color: 'var(--txt-dim)' }}>—</span>;
  }
  if (punchesLoading) {
    return <span style={{ fontSize: 11, color: 'var(--txt-dim)' }}>Loading…</span>;
  }

  // Real per-session punches (supports multiple blocks/day) when the fetch has resolved;
  // otherwise fall back to the day's single check-in/out pair rather than showing nothing.
  const segments: { key: string; checkInAt: string; checkOutAt: string | null }[] =
    punches && punches.length > 0
      ? punches.map((p) => ({ key: p.id, checkInAt: p.checkInAt, checkOutAt: p.checkOutAt }))
      : [{ key: info.iso, checkInAt: record.checkInAt, checkOutAt: record.checkOutAt }];

  return (
    <div style={{ position: 'relative', height: 18, width: '100%', minWidth: 140, maxWidth: 220 }}>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 6, height: 6, background: 'var(--raised2)', borderRadius: 3 }} />
      {Array.from({ length: 25 }).map((_, i) => (
        <div key={i} style={{ position: 'absolute', left: `${(i / 24) * 100}%`, top: 3, width: 1, height: 12, background: 'var(--line2)', opacity: i % 6 === 0 ? 0.8 : 0.35 }} />
      ))}
      {segments.map((seg, i) => {
        const inMin = minutesSinceMidnight(seg.checkInAt);
        if (inMin == null) return null;
        const outMin = seg.checkOutAt ? minutesSinceMidnight(seg.checkOutAt) : null;
        const leftPct = (inMin / 1440) * 100;
        // Width is strictly proportional to the actual session duration out of the 24h track,
        // with a small floor so a very short/still-open session stays visible and hoverable.
        const widthPct = Math.max(0.6, (((outMin ?? inMin + 10) - inMin) / 1440) * 100);
        return (
          <TimelineBar key={seg.key ?? i} leftPct={leftPct} widthPct={widthPct} checkInAt={seg.checkInAt} checkOutAt={seg.checkOutAt} />
        );
      })}
    </div>
  );
}

/** Shift name/timing for the selected day + Regularize/Apply Partial Day actions, shown in the
 * existing View/details side panel (reuses RequestModal/AttendanceRequestModal — see MyAttendance's
 * regularizeDate/partialDayDate state). */
function DayShiftAndActions({ info, config, onRegularize, onApplyPartialDay }: {
  info: DayInfo; config: AttendanceConfig | null; onRegularize: () => void; onApplyPartialDay: () => void;
}) {
  const { formatTime } = useTimeFormat();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
      {config?.shiftName && (
        <div>
          <div style={{ fontSize: 10, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Shift</div>
          <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{config.shiftName}</div>
          {config.shiftStart && config.shiftEnd && (
            <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 2 }}>
              {formatTime(`${info.iso}T${config.shiftStart}`)} - {formatTime(`${info.iso}T${config.shiftEnd}`)}
            </div>
          )}
        </div>
      )}
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
        <button
          onClick={onRegularize}
          style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'none', border: 'none', padding: 0, color: 'var(--brand)', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}
        >
          <Pencil size={11} /> Regularize
        </button>
        <button
          onClick={onApplyPartialDay}
          style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'none', border: 'none', padding: 0, color: 'var(--brand)', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}
        >
          <Pencil size={11} /> Apply Partial Day
        </button>
      </div>
    </div>
  );
}

/** One source's block of sessions within DayPunchIntervals — mirrors Keka's per-source grouping
 * (its own company-name section for normal punches, a separate "Web Clock In" section). */
function PunchSourceGroup({ label, sessions }: {
  label: string;
  sessions: { key: string; checkInAt: string; checkOutAt: string | null }[];
}) {
  const { formatTime } = useTimeFormat();
  if (sessions.length === 0) return null;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
      <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em' }}>{label}</div>
      {sessions.map((s, i) => (
        <div key={s.key ?? i} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', fontSize: 12 }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 5, color: 'var(--ok)', fontWeight: 600 }}>
            <ArrowDownLeft size={12} /> {formatTime(s.checkInAt) ?? dash}
          </span>
          {s.checkOutAt ? (
            <span style={{ display: 'flex', alignItems: 'center', gap: 5, color: 'var(--txt)', fontWeight: 600 }}>
              <ArrowUpRight size={12} /> {formatTime(s.checkOutAt)}
            </span>
          ) : (
            <span style={{ fontSize: 10.5, fontWeight: 700, color: '#E4373D', letterSpacing: '.04em' }}>MISSING</span>
          )}
        </div>
      ))}
    </div>
  );
}

/**
 * Complete real punch history for the day (supports multiple IN/OUT sessions across BOTH entry
 * points), MISSING shown for any session still missing its check-out. Grouped by source — normal
 * Check-In/Check-Out and Web Check-In/Check-Out each get their own labeled section, matching the
 * Keka reference's separate "Company Name" / "Web Clock In" blocks — rather than one flat list
 * that hides which entries came from where.
 */
function DayPunchIntervals({ info, punches }: { info: DayInfo; punches: Punch[] | undefined }) {
  const record = info.record;
  const sessions: { key: string; checkInAt: string; checkOutAt: string | null; source: Punch['source'] }[] =
    punches && punches.length > 0
      ? punches.map((p) => ({ key: p.id, checkInAt: p.checkInAt, checkOutAt: p.checkOutAt, source: p.source }))
      : record?.checkInAt
        ? [{ key: info.iso, checkInAt: record.checkInAt, checkOutAt: record.checkOutAt, source: record.source === 'WEB_REMOTE' ? 'WEB_REMOTE' : 'SYSTEM' }]
        : [];

  if (sessions.length === 0) {
    return <div style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>No punches recorded for this day.</div>;
  }

  const officeSessions = sessions.filter((s) => s.source !== 'WEB_REMOTE');
  const webSessions = sessions.filter((s) => s.source === 'WEB_REMOTE');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, maxWidth: 260 }}>
      <PunchSourceGroup label="Check-In / Check-Out" sessions={officeSessions} />
      <PunchSourceGroup label="Web Check-In / Check-Out" sessions={webSessions} />
    </div>
  );
}

/**
 * Full day-details body — date header, then whichever of holiday/leave/regularization/punched-
 * record/weekend/nothing applies, including (for a punched record) DayShiftAndActions and
 * DayPunchIntervals. Shared by the calendar's side panel AND the View-button modal below, so
 * both present exactly the same information.
 */
function DayDetailsBody({ info, config, punches, onRegularize, onApplyPartialDay }: {
  info: DayInfo;
  config: AttendanceConfig | null;
  punches: Punch[] | undefined;
  onRegularize: () => void;
  onApplyPartialDay: () => void;
}) {
  const { formatTime, formatDuration } = useTimeFormat();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 11 }}>
      <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt)' }}>{formatDay(info.iso)}</div>
      {info.holidayName ? (
        <div style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>Company holiday — {info.holidayName}</div>
      ) : info.leaveTypeName ? (
        <div style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>On leave — {info.leaveTypeName}</div>
      ) : info.regularization ? (
        <>
          <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap' }}>
            <div>
              <div style={{ fontSize: 10, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Requested In</div>
              <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(info.regularization.requestedCheckIn) ?? dash}</div>
            </div>
            <div>
              <div style={{ fontSize: 10, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Requested Out</div>
              <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(info.regularization.requestedCheckOut) ?? dash}</div>
            </div>
          </div>
          <div>
            <div style={{ fontSize: 10, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Total Hours</div>
            <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)' }}>{formatDuration(info.regularization.totalMinutes) ?? dash}</div>
          </div>
          <div>
            <div style={{ fontSize: 10, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Status</div>
            <RegularizationStatusPill status={info.regularization.status} />
          </div>
          <div>
            <div style={{ fontSize: 10, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Approved By</div>
            <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)' }}>{info.regularization.reviewedByName ?? dash}</div>
          </div>
          <div>
            <div style={{ fontSize: 10, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Comments</div>
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>{info.regularization.reviewComment ?? dash}</div>
          </div>
        </>
      ) : info.record ? (
        <>
          <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap' }}>
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                <LogIn size={11} /> Check In
              </div>
              <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(info.record.checkInAt) ?? dash}</div>
            </div>
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                <LogOut size={11} /> Check Out
              </div>
              <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(info.record.checkOutAt) ?? dash}</div>
            </div>
          </div>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
              <Clock size={11} /> Hours
            </div>
            <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)' }}>{formatDuration(info.record.workedMinutes) ?? dash}</div>
          </div>
          <StatusPill status={info.record.status} />
          <LateBadge minutes={info.record.lateByMinutes} graceMinutes={config?.lateGraceMinutes} workedMinutes={info.record.workedMinutes} config={config} />
          <DayShiftAndActions info={info} config={config} onRegularize={onRegularize} onApplyPartialDay={onApplyPartialDay} />
          <DayPunchIntervals info={info} punches={punches} />
        </>
      ) : info.isWeekend ? (
        <div style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>Weekend — no attendance expected.</div>
      ) : (
        <div style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>No attendance recorded for this day.</div>
      )}
    </div>
  );
}

/**
 * Opened directly by the Attendance Log's View button — a self-contained modal (not dependent
 * on the calendar's side panel, which can be scrolled out of view) showing the exact same
 * DayDetailsBody: shift timing, Regularize, Apply Partial Day, and the full punch history.
 */
function DayDetailsModal({ info, config, punches, onClose, onRegularize, onApplyPartialDay }: {
  info: DayInfo;
  config: AttendanceConfig | null;
  punches: Punch[] | undefined;
  onClose: () => void;
  onRegularize: () => void;
  onApplyPartialDay: () => void;
}) {
  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 420 }}>
        <ModalHeader title="Attendance Details" onClose={onClose} />
        <div style={{ padding: 24 }}>
          <DayDetailsBody info={info} config={config} punches={punches} onRegularize={onRegularize} onApplyPartialDay={onApplyPartialDay} />
        </div>
      </div>
    </div>
  );
}

/** Special-day badge shown next to the date — plain PRESENT/LATE status is conveyed by the
 * Arrival/Effective Hours columns instead, so it's deliberately not repeated here. */
function InlineDayBadge({ info }: { info: DayInfo }) {
  if (info.holidayName) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(76,141,214,.15)', color: '#4C8DD6' }}>Holiday</span>;
  }
  if (info.leaveTypeName) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(139,92,246,.18)', color: '#8B5CF6' }}>LEAVE</span>;
  }
  if (info.regularization) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(47,182,124,.15)', color: '#2FB67C' }}>Regularization</span>;
  }
  if (info.attendanceRequest) {
    return info.attendanceRequest.requestType === 'WFH'
      ? <span style={{ ...DAY_TAG_STYLE, background: 'rgba(76,141,214,.15)', color: '#4C8DD6' }}>WFH</span>
      : <span style={{ ...DAY_TAG_STYLE, background: 'rgba(224,169,59,.18)', color: '#E0A93B' }}>PARTIAL DAY</span>;
  }
  if (info.isWeekend && !info.record) {
    return <span style={{ ...DAY_TAG_STYLE, background: 'rgba(155,161,172,.15)', color: '#9BA1AC' }}>W-OFF</span>;
  }
  return null;
}

function EffectiveHoursCell({ metrics }: { metrics: RowMetrics }) {
  const { formatDuration } = useTimeFormat();
  if (metrics.effectiveMinutes == null) return dash;
  const dotColor = metrics.openSession ? 'transparent' : 'var(--brand)';
  return (
    <span style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>
      <span style={{ width: 7, height: 7, borderRadius: '50%', background: dotColor, border: metrics.openSession ? '1.5px solid var(--brand)' : 'none', flexShrink: 0 }} />
      {formatDuration(metrics.effectiveMinutes)}{metrics.openSession ? ' +' : ''}
    </span>
  );
}

/** Same grace-aware rule as LateBadge — see its doc comment. */
function ArrivalCell({ record, graceMinutes, config }: {
  record: AttendanceRecord | undefined; graceMinutes: number | null | undefined; config?: AttendanceConfig | null;
}) {
  const { formatDuration } = useTimeFormat();
  if (!record?.checkInAt) return dash;
  const late = record.lateByMinutes ?? 0;
  const grace = graceMinutes ?? 10;
  if (late > grace) {
    const fullDay = hasMetFullEffectiveHours(record.workedMinutes, config ?? null);
    return (
      <span style={{ display: 'flex', alignItems: 'center', gap: 6, color: '#E0A93B', fontSize: 12, fontWeight: 600 }}>
        {!fullDay && <Turtle size={16} style={{ flexShrink: 0 }} />} {formatDuration(late)} late
      </span>
    );
  }
  return (
    <span style={{ display: 'flex', alignItems: 'center', gap: 5, color: 'var(--ok)', fontSize: 12, fontWeight: 600 }}>
      <CheckCircle2 size={12} /> On Time
    </span>
  );
}

/** Last 6 months as quick-jump pill buttons, matching the reference UI's month shortcuts row. */
function MonthShortcuts({ viewYear, viewMonth, onSelect }: {
  viewYear: number; viewMonth: number; onSelect: (year: number, month: number) => void;
}) {
  const months = useMemo(() => {
    const now = new Date();
    const out: { year: number; month: number; label: string }[] = [];
    for (let i = 0; i < 6; i++) {
      const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
      out.push({ year: d.getFullYear(), month: d.getMonth(), label: d.toLocaleDateString(undefined, { month: 'short' }) });
    }
    return out;
  }, []);

  return (
    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
      {months.map((m) => {
        const active = m.year === viewYear && m.month === viewMonth;
        return (
          <button
            key={`${m.year}-${m.month}`}
            onClick={() => onSelect(m.year, m.month)}
            style={{
              background: active ? 'var(--brand)' : 'var(--raised)',
              color: active ? '#fff' : 'var(--txt-mut)',
              border: `1px solid ${active ? 'var(--brand)' : 'var(--line2)'}`,
              borderRadius: 6, padding: '5px 11px', fontSize: 11, fontWeight: 700, cursor: 'pointer', textTransform: 'uppercase',
            }}
          >
            {m.label}
          </button>
        );
      })}
    </div>
  );
}

// ─── Logs & Requests tab bar ────────────────────────────────────────────────────
// One flat row of 4 tabs (Keka reference: nforceone.keka.com/#/me/attendance/logs) — the
// active tab gets a bordered "chip", inactive tabs stay plain text. Calendar/Attendance Log
// content lives here (inside MyAttendance, which already owns that state); Attendance
// Requests/Overtime Requests content is handed in as `otherTabContent` by the page since it
// lives in sibling components with their own state.
export type LogsTab = 'ATTENDANCE_LOG' | 'CALENDAR' | 'ATTENDANCE_REQUESTS' | 'OVERTIME';

const LOGS_TABS: { value: LogsTab; label: string }[] = [
  { value: 'ATTENDANCE_LOG', label: 'Attendance Log' },
  { value: 'CALENDAR', label: 'Calendar' },
  { value: 'ATTENDANCE_REQUESTS', label: 'Attendance Requests' },
  { value: 'OVERTIME', label: 'Overtime Requests' },
];

function LogsTabBar({ value, onChange }: { value: LogsTab; onChange: (v: LogsTab) => void }) {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
      {LOGS_TABS.map((t) => {
        const active = t.value === value;
        return (
          <button
            key={t.value}
            onClick={() => onChange(t.value)}
            style={{
              background: active ? 'var(--raised)' : 'transparent',
              border: `1px solid ${active ? 'var(--line2)' : 'transparent'}`,
              borderRadius: 6, padding: '7px 14px', fontSize: 12.5, fontWeight: 600,
              color: active ? 'var(--txt)' : 'var(--txt-mut)', cursor: 'pointer', whiteSpace: 'nowrap',
            }}
          >
            {t.label}
          </button>
        );
      })}
    </div>
  );
}

/** Relocated from Quick Actions so it sits above/right of the Logs & Requests tab bar, matching the Keka reference. Same shared preference (useTimeFormat) — just one on/off switch instead of a 12h/24h pair. */
function TimeFormatToggle() {
  const { format, toggle } = useTimeFormat();
  const checked = format === '24h';
  return (
    <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', fontSize: 12.5, color: 'var(--txt-mut)', userSelect: 'none' }}>
      <span
        role="switch"
        aria-checked={checked}
        onClick={toggle}
        style={{
          width: 36, height: 20, borderRadius: 10, position: 'relative', flexShrink: 0,
          background: checked ? 'var(--brand)' : 'var(--line2)', transition: 'background .15s',
        }}
      >
        <span style={{
          position: 'absolute', top: 2, left: checked ? 18 : 2, width: 16, height: 16, borderRadius: '50%',
          background: '#fff', transition: 'left .15s', boxShadow: '0 1px 2px rgba(0,0,0,.25)',
        }} />
      </span>
      24 hour format
    </label>
  );
}

export interface MyAttendanceHandle {
  exportMonth: () => void;
}

const MyAttendance = forwardRef<MyAttendanceHandle, {
  isSuperAdmin: boolean;
  logsTab: LogsTab;
  onLogsTabChange: (tab: LogsTab) => void;
  /** Attendance Requests / Overtime Requests content — owned by sibling components in the page, rendered in this same box when their tab is active. */
  otherTabContent: React.ReactNode;
}>(function MyAttendance({ isSuperAdmin, logsTab, onLogsTabChange, otherTabContent }, ref) {
  const token = useAuthStore((s) => s.token)!;
  const { showToast } = useToast();
  const { formatTime, formatDuration } = useTimeFormat();

  const [today, setToday] = useState<TodayAttendance | null>(null);
  const [loading, setLoading] = useState(true);
  // The Attendance Log's View button opens this modal directly — it doesn't rely on the
  // calendar's side panel, which can be scrolled out of view for rows further down the table.
  const [viewDetailsIso, setViewDetailsIso] = useState<string | null>(null);
  // Regularize/Apply Partial Day, opened from either the side panel or the View modal (see
  // DayShiftAndActions) — reuses the existing RequestModal/AttendanceRequestModal flows
  // directly rather than routing through the Regularization/WFH&Partial-Day tabs.
  const [regularizeDate, setRegularizeDate] = useState<string | null>(null);
  const [partialDayDate, setPartialDayDate] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [config, setConfig] = useState<AttendanceConfig | null>(null);

  useEffect(() => {
    attendanceApi.config(token).then(setConfig).catch(() => setConfig(null));
  }, [token]);

  // Bumped on every successful punch — PunchHistoryList's own fetch is keyed on [date, token],
  // neither of which changes on a punch, so without this it never re-fetches until something
  // else forces the whole panel to remount (e.g. a page reload).
  const [punchVersion, setPunchVersion] = useState(0);

  // Offset between the browser clock and the server's business-timezone clock, captured on
  // load, so the live elapsed counter is correct in any browser timezone.
  const serverOffsetMs = useRef(0);
  const [tick, setTick] = useState(0);

  // ── Calendar state ──
  const now = useMemo(() => new Date(), []);
  const [viewYear, setViewYear] = useState(now.getFullYear());
  const [viewMonth, setViewMonth] = useState(now.getMonth());
  const [monthRecords, setMonthRecords] = useState<AttendanceRecord[]>([]);
  const [monthLoading, setMonthLoading] = useState(true);
  const [holidays, setHolidays] = useState<HolidayRow[]>([]);
  const [leaves, setLeaves] = useState<LeaveRequestRecord[]>([]);
  const [regularizations, setRegularizations] = useState<RegularizationRecord[]>([]);
  const [attendanceRequests, setAttendanceRequests] = useState<AttendanceRequestRecord[]>([]);
  const [selectedDate, setSelectedDate] = useState<string | null>(todayIsoDate());
  // Nothing before this date is ever shown (Attendance Log rows, Calendar cells) — a day
  // before the employee joined was never expected to have attendance of any kind.
  const [joiningDate, setJoiningDate] = useState<string | null>(null);

  // Holidays / leaves / regularizations / attendance requests are fetched once — the calendar
  // filters them per month.
  useEffect(() => {
    Promise.all([
      holidaysApi.listForMyLocation(token).catch(() => []),
      leaveApi.listMine(token).catch(() => []),
      regularizationApi.mine(token).catch(() => []),
      attendanceRequestApi.mine(token).catch(() => []),
    ]).then(([h, l, r, ar]) => {
      setHolidays(h); setLeaves(l); setRegularizations(r); setAttendanceRequests(ar);
    });
    profileApi.get(token).then((p) => setJoiningDate(p.joiningDate)).catch(() => setJoiningDate(null));
  }, [token]);

  const refreshMonth = useCallback(() => {
    setMonthLoading(true);
    const from = isoOf(viewYear, viewMonth, 1);
    const to = isoOf(viewYear, viewMonth, daysInMonth(viewYear, viewMonth));
    return attendanceApi.myHistory(from, to, token)
      .then((r) => setMonthRecords(r))
      .catch(() => setMonthRecords([]))
      .finally(() => setMonthLoading(false));
  }, [viewYear, viewMonth, token]);

  // Exposed to the page header's "Export selected month" button — same XLSX pattern
  // already used by DirectoryPage.tsx, reusing the month's already-fetched records.
  useImperativeHandle(ref, () => ({
    exportMonth: () => {
      const rows = monthRecords.map((r) => ({
        Date: r.workDate,
        'Check In': formatTime(r.checkInAt) ?? '',
        'Check Out': formatTime(r.checkOutAt) ?? '',
        Worked: formatDuration(r.workedMinutes) ?? '',
        Mode: r.source === 'WEB_REMOTE' ? 'Remote' : 'Office',
        Status: r.status ?? '',
      }));
      const ws = XLSX.utils.json_to_sheet(rows);
      const wb = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(wb, ws, 'Attendance');
      XLSX.writeFile(wb, `attendance-${viewYear}-${pad2(viewMonth + 1)}.xlsx`);
    },
  }), [monthRecords, viewYear, viewMonth]);

  useEffect(() => {
    let cancelled = false;
    setMonthLoading(true);
    const from = isoOf(viewYear, viewMonth, 1);
    const to = isoOf(viewYear, viewMonth, daysInMonth(viewYear, viewMonth));
    attendanceApi.myHistory(from, to, token)
      .then((r) => { if (!cancelled) setMonthRecords(r); })
      .catch(() => { if (!cancelled) setMonthRecords([]); })
      .finally(() => { if (!cancelled) setMonthLoading(false); });
    return () => { cancelled = true; };
  }, [viewYear, viewMonth, token]);

  const recordByDate = useMemo(() => {
    const map = new Map<string, AttendanceRecord>();
    monthRecords.forEach((r) => map.set(r.workDate, r));
    return map;
  }, [monthRecords]);

  const holidayByDate = useMemo(() => {
    const map = new Map<string, string>();
    holidays.forEach((h) => { if (h.active) map.set(h.holidayDate, h.holidayName); });
    return map;
  }, [holidays]);

  const leaveByDate = useMemo(() => {
    const map = new Map<string, string>();
    leaves.filter((l) => l.status === 'APPROVED').forEach((l) => {
      expandDateRange(l.startDate, l.endDate).forEach((iso) => map.set(iso, l.leaveTypeName));
    });
    return map;
  }, [leaves]);

  // Calendar marks/details only ever reflect APPROVED requests — pending/rejected requests
  // are not shown on the calendar at all.
  const regularizationByDate = useMemo(() => {
    const map = new Map<string, RegularizationRecord>();
    regularizations.filter((r) => r.status === 'APPROVED').forEach((r) => map.set(r.attendanceDate, r));
    return map;
  }, [regularizations]);

  const attendanceRequestByDate = useMemo(() => {
    const map = new Map<string, AttendanceRequestRecord>();
    attendanceRequests.filter((r) => r.status === 'APPROVED').forEach((r) => map.set(r.requestDate, r));
    return map;
  }, [attendanceRequests]);


  const monthPrefix = `${viewYear}-${pad2(viewMonth + 1)}`;
  const presentDaysCount = monthRecords.filter((r) => r.checkInAt).length;
  const workedMinutesTotal = monthRecords.reduce((sum, r) => sum + (r.workedMinutes ?? 0), 0);
  const leaveHolidayCount = useMemo(() => {
    let count = 0;
    for (const iso of holidayByDate.keys()) if (iso.startsWith(monthPrefix)) count++;
    for (const iso of leaveByDate.keys()) if (iso.startsWith(monthPrefix)) count++;
    return count;
  }, [holidayByDate, leaveByDate, monthPrefix]);

  const getDayInfo = useCallback((day: number): DayInfo => {
    const iso = isoOf(viewYear, viewMonth, day);
    const dow = new Date(viewYear, viewMonth, day).getDay();
    // ONEHR-108: respects the employee's assigned WeeklyOffPolicy when loaded, else Sat/Sun.
    const isWeekend = config
      ? config.weeklyOffDays.includes(DOW_NAMES[dow])
      : dow === 0 || dow === 6;
    return {
      iso,
      day,
      isFuture: iso > todayIsoDate(),
      isBeforeJoining: !!joiningDate && iso < joiningDate,
      isToday: iso === todayIsoDate(),
      isWeekend,
      holidayName: holidayByDate.get(iso),
      leaveTypeName: leaveByDate.get(iso),
      regularization: regularizationByDate.get(iso),
      attendanceRequest: attendanceRequestByDate.get(iso),
      record: recordByDate.get(iso),
    };
  }, [viewYear, viewMonth, config, joiningDate, holidayByDate, leaveByDate, regularizationByDate, attendanceRequestByDate, recordByDate]);

  function goToPrevMonth() {
    setSelectedDate(null);
    if (viewMonth === 0) { setViewYear((y) => y - 1); setViewMonth(11); } else { setViewMonth((m) => m - 1); }
  }
  function goToNextMonth() {
    setSelectedDate(null);
    if (viewMonth === 11) { setViewYear((y) => y + 1); setViewMonth(0); } else { setViewMonth((m) => m + 1); }
  }
  /** Month-shortcut buttons — jumps directly to a given month/year, same reset behavior as prev/next. */
  function goToMonth(year: number, month: number) {
    setSelectedDate(null);
    setViewYear(year);
    setViewMonth(month);
  }

  const approvedRegularizationDates = useMemo(
    () => new Set(regularizationByDate.keys()),
    [regularizationByDate],
  );

  // Every non-future day of the month, newest first — reuses getDayInfo exactly as
  // MonthCalendar does, so weekends/leaves/holidays appear as rows even with no punch.
  const logRows = useMemo(() => {
    const total = daysInMonth(viewYear, viewMonth);
    const todayIso = todayIsoDate();
    const rows: DayInfo[] = [];
    for (let d = 1; d <= total; d++) {
      const info = getDayInfo(d);
      if (info.iso <= todayIso && !info.isBeforeJoining) rows.push(info);
    }
    return rows.reverse();
  }, [viewYear, viewMonth, getDayInfo]);

  // Real per-day punches (for the Attendance Log's multi-segment timeline + break calc, and for
  // DayPunchIntervals in the View/details side panel), fetched once per punched day and cached
  // across month switches via the existing per-date /attendance/punches/{date} endpoint.
  const [punchesByDate, setPunchesByDate] = useState<Map<string, Punch[]>>(new Map());
  useEffect(() => {
    const datesNeeded = logRows
      .filter((info) => info.record?.checkInAt && !punchesByDate.has(info.iso))
      .map((info) => info.iso);
    if (datesNeeded.length === 0) return;
    let cancelled = false;
    Promise.all(datesNeeded.map((iso) =>
      attendanceApi.punches(iso, token)
        .then((p) => [iso, p] as const)
        .catch(() => [iso, [] as Punch[]] as const),
    )).then((results) => {
      if (cancelled) return;
      setPunchesByDate((prev) => {
        const next = new Map(prev);
        results.forEach(([iso, p]) => next.set(iso, p));
        return next;
      });
    });
    return () => { cancelled = true; };
    // punchesByDate is read only to skip already-cached dates — omitted from deps deliberately,
    // since including it would make this effect re-run every time it updates its own state.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [logRows, token]);

  const selectedInfo = useMemo(() => {
    if (!selectedDate) return null;
    const [y, m, d] = selectedDate.split('-').map(Number);
    if (y !== viewYear || m - 1 !== viewMonth) return null;
    return getDayInfo(d);
  }, [selectedDate, viewYear, viewMonth, getDayInfo]);

  useEffect(() => {
    let cancelled = false;
    attendanceApi.today(token)
      .then((t) => {
        if (cancelled) return;
        serverOffsetMs.current = wallClockMs(t.serverNow) - Date.now();
        setToday(t);
      })
      .catch((err) => {
        if (!cancelled) showToast('error', err instanceof Error ? err.message : 'Failed to load attendance');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [token, showToast]);

  // sessionStartedAt (not checkInAt) — the currently-open session's own start, so a resumed
  // session after a break shows its own elapsed time instead of counting from the day's
  // original check-in (which would wrongly include the break in "elapsed").
  const openSince = today?.canCheckOut ? today.record?.sessionStartedAt ?? today.record?.checkInAt ?? null : null;

  useEffect(() => {
    if (!openSince) return;
    const id = setInterval(() => setTick((n) => n + 1), 60000);
    return () => clearInterval(id);
  }, [openSince]);

  const elapsed = useMemo(() => {
    if (!openSince) return null;
    void tick; // re-derive on each tick
    const minutes = Math.floor(
      (Date.now() + serverOffsetMs.current - wallClockMs(openSince)) / 60000,
    );
    return minutes >= 0 ? formatDuration(minutes) : null;
  }, [openSince, tick]);

  // Raw minutes-worked-so-far-today (same clock as `elapsed`), for the Today's Timings
  // progress bar / break panel, which need a number rather than a formatted string.
  const workedMinutesToday = useMemo(() => {
    if (openSince) {
      void tick;
      const minutes = Math.floor((Date.now() + serverOffsetMs.current - wallClockMs(openSince)) / 60000);
      return minutes >= 0 ? minutes : null;
    }
    return today?.record?.workedMinutes ?? null;
  }, [openSince, tick, today]);

  // Re-read /today so canCheckIn/canCheckOut always come from the server, never inferred — the
  // ONE shared refresh every check-in/check-out/web-check-in entry point calls afterward, so
  // every consumer of `today` (Today's Timings, the calendar's Today's-workday panel, and the
  // Actions panel) ends up looking at the exact same state instead of each keeping its own.
  const refreshTodayAndMonth = useCallback(async () => {
    const [refreshed] = await Promise.all([
      attendanceApi.today(token),
      refreshMonth(),
    ]);
    serverOffsetMs.current = wallClockMs(refreshed.serverNow) - Date.now();
    setToday(refreshed);
    setPunchVersion((v) => v + 1);
    return refreshed;
  }, [token, refreshMonth]);

  async function punch(kind: 'in' | 'out') {
    setSubmitting(true);
    try {
      const record = kind === 'in'
        ? await attendanceApi.checkIn(token)
        : await attendanceApi.checkOut(token);
      const refreshed = await refreshTodayAndMonth();

      // record.checkInAt is the day's *original* check-in (deliberately never updated on a
      // lunch-break resume, see AttendanceService.checkIn) — not what just happened on a
      // repeat check-in. checkOutAt is always the latest checkout, so it's fine as-is.
      const at = formatTime(kind === 'in' ? refreshed.serverNow : record.checkOutAt);
      showToast('success', `Checked ${kind} ${at ? `at ${at}` : 'successfully'}`);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : `Check ${kind} failed`);
    } finally {
      setSubmitting(false);
    }
  }

  const primaryButtonStyle = (disabled: boolean): React.CSSProperties => ({
    display: 'flex', alignItems: 'center', gap: 7,
    background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7,
    padding: '10px 20px', fontSize: 13, fontWeight: 600,
    cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? 0.7 : 1,
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      {/* Attendance Stats / Today's Timings / Quick Actions */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 14 }}>
        <AttendanceStatsPanel token={token} />
        <TodaysTimingsPanel today={today} config={config} workedMinutesToday={workedMinutesToday} />
        <QuickActionsPanel
          token={token}
          today={today}
          todayLoading={loading}
          submitting={submitting}
          onCheckIn={() => punch('in')}
          onCheckOut={() => punch('out')}
          onWebCheckInSubmitted={refreshTodayAndMonth}
        />
      </div>

      {/* Logs & Requests — Keka-style 4-tab bar (nforceone.keka.com/#/me/attendance/logs).
          Calendar/Attendance Log render inline below since they share this component's state;
          Attendance Requests/Overtime Requests are handed in as otherTabContent — they live in
          sibling components with their own state. */}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12, marginBottom: 12 }}>
          <h2 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 14, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Logs & Requests</h2>
          <TimeFormatToggle />
        </div>
        <LogsTabBar value={logsTab} onChange={onLogsTabChange} />

        {logsTab === 'CALENDAR' && (
        <div style={{ marginTop: 14 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 9, marginBottom: 14 }}>
          <MonthStatTile label="Present Days" value={monthLoading ? '—' : String(presentDaysCount)} hint="Selected month" />
          <MonthStatTile label="Worked Hours" value={monthLoading ? '—' : (formatDuration(workedMinutesTotal) ?? '0m')} hint="Selected month" />
          <MonthStatTile label="Leave / Holidays" value={String(leaveHolidayCount)} hint="Selected month" />
        </div>

        <div className="nf-grid-proportional-collapse" style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 16, alignItems: 'start' }}>
          <MonthCalendar
            year={viewYear}
            month={viewMonth}
            dayInfo={getDayInfo}
            selectedDate={selectedDate}
            onSelect={setSelectedDate}
            onPrev={goToPrevMonth}
            onNext={goToNextMonth}
          />

          <div style={{ ...panelStyle, padding: '16px 18px' }}>
            {!selectedInfo ? (
              <div style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>Pick a day on the calendar to see its details.</div>
            ) : selectedInfo.isToday ? (
              // Today's workday — merged from the old standalone punch card, now living
              // in the calendar's side panel with the exact same today/punch() state.
              <div style={{ display: 'flex', flexDirection: 'column', gap: 11 }}>
                <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt)' }}>Today's workday</div>
                {loading ? (
                  <div style={{ color: 'var(--txt-dim)', fontSize: 12.5 }}>Loading…</div>
                ) : !today ? (
                  <div style={{ color: 'var(--txt-dim)', fontSize: 12.5 }}>Attendance unavailable right now.</div>
                ) : (
                  <>
                    <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap' }}>
                      <div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                          <LogIn size={11} /> Check In
                        </div>
                        {/* The day's original check-in — fixed once set. A resumed session
                            after a break updates sessionStartedAt (used for the Elapsed timer
                            below) and Check Out, but never replaces this. */}
                        <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)' }}>
                          {formatTime(today.record?.checkInAt ?? null) ?? dash}
                        </div>
                      </div>
                      <div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                          <LogOut size={11} /> Check Out
                        </div>
                        <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(today.record?.checkOutAt ?? null) ?? dash}</div>
                      </div>
                    </div>
                    <div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                        <Clock size={11} /> {today.canCheckOut ? 'Elapsed' : 'Worked Today'}
                      </div>
                      <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)' }}>
                        {(today.canCheckOut ? elapsed : formatDuration(today.record?.workedMinutes ?? null)) ?? dash}
                      </div>
                    </div>
                    {today.record?.status && <StatusPill status={today.record.status} />}
                    <LateBadge minutes={today.record?.lateByMinutes} graceMinutes={config?.lateGraceMinutes} workedMinutes={workedMinutesToday} config={config} />
                    {/* The button is driven only by the server's canCheckIn / canCheckOut flags. */}
                    <div>
                      {today.canCheckIn && (
                        <button onClick={() => punch('in')} disabled={submitting} style={primaryButtonStyle(submitting)}>
                          <LogIn size={14} /> {submitting ? 'Checking in…' : 'Check In'}
                        </button>
                      )}
                      {today.canCheckOut && (
                        <button onClick={() => punch('out')} disabled={submitting} style={primaryButtonStyle(submitting)}>
                          <LogOut size={14} /> {submitting ? 'Checking out…' : 'Check Out'}
                        </button>
                      )}
                      {!today.canCheckIn && !today.canCheckOut && (
                        <div style={{ display: 'flex', alignItems: 'center', gap: 7, color: 'var(--ok)', fontSize: 12.5, fontWeight: 600 }}>
                          <CheckCircle2 size={15} /> Day complete
                        </div>
                      )}
                    </div>
                    <DayShiftAndActions
                      info={selectedInfo}
                      config={config}
                      onRegularize={() => setRegularizeDate(selectedInfo.iso)}
                      onApplyPartialDay={() => setPartialDayDate(selectedInfo.iso)}
                    />
                    {/* PunchHistoryList (not DayPunchIntervals) here specifically — it self-fetches
                        with a refreshKey, so today's list updates immediately after a punch instead
                        of showing whatever punchesByDate cached before the punch happened. */}
                    <PunchHistoryList date={selectedInfo.iso} token={token} refreshKey={punchVersion} />
                  </>
                )}
              </div>
            ) : (
              <DayDetailsBody
                info={selectedInfo}
                config={config}
                punches={punchesByDate.get(selectedInfo.iso)}
                onRegularize={() => setRegularizeDate(selectedInfo.iso)}
                onApplyPartialDay={() => setPartialDayDate(selectedInfo.iso)}
              />
            )}
          </div>
        </div>
        </div>
        )}

        {logsTab === 'ATTENDANCE_LOG' && (
        <div style={{ marginTop: 14 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
            <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt-mut)' }}>{calendarMonthLabel(viewYear, viewMonth)}</div>
            <MonthShortcuts viewYear={viewYear} viewMonth={viewMonth} onSelect={goToMonth} />
          </div>
          <div style={panelStyle}>
            {monthLoading ? (
              <div style={{ padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>Loading…</div>
            ) : logRows.length === 0 ? (
              <div style={{ padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No days to show for this month.</div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead>
                    <tr>{['Date', 'Attendance Visual', 'Effective Hours', 'Break Taken', 'Gross Hours', 'Arrival', 'Log'].map((h) => <th key={h} style={thStyle}>{h}</th>)}</tr>
                  </thead>
                  <tbody>
                    {logRows.map((info) => {
                      const punches = punchesByDate.get(info.iso);
                      const punchesLoading = !!info.record?.checkInAt && !punches;
                      const metrics = computeRowMetrics(info, punches, workedMinutesToday);
                      return (
                        <tr key={info.iso}>
                          <td style={{ ...tdStyle, whiteSpace: 'nowrap' }}>
                            <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                              <span style={{ color: 'var(--txt)', fontWeight: 600 }}>{formatDay(info.iso)}</span>
                              <InlineDayBadge info={info} />
                            </span>
                          </td>
                          <td style={tdStyle}>
                            <AttendanceTimeline info={info} punches={punches} punchesLoading={punchesLoading} />
                          </td>
                          <td style={tdStyle}><EffectiveHoursCell metrics={metrics} /></td>
                          <td style={tdStyle}>{formatDuration(metrics.breakMinutes) ?? dash}</td>
                          <td style={tdStyle}>{formatDuration(metrics.grossMinutes) ?? dash}{metrics.grossMinutes != null && metrics.openSession ? ' +' : ''}</td>
                          <td style={tdStyle}><ArrivalCell record={info.record} graceMinutes={config?.lateGraceMinutes} config={config} /></td>
                          <td style={tdStyle}>
                            <button
                              onClick={() => setViewDetailsIso(info.iso)}
                              style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, padding: '3px 9px', fontSize: 10.5, color: 'var(--txt)', cursor: 'pointer', fontWeight: 600 }}
                            >
                              <Eye size={10.5} /> View
                            </button>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
        )}

        {logsTab !== 'CALENDAR' && logsTab !== 'ATTENDANCE_LOG' && (
          <div style={{ marginTop: 14 }}>{otherTabContent}</div>
        )}
      </div>

      {viewDetailsIso && (() => {
        const viewInfo = logRows.find((r) => r.iso === viewDetailsIso);
        if (!viewInfo) return null;
        return (
          <DayDetailsModal
            info={viewInfo}
            config={config}
            punches={punchesByDate.get(viewDetailsIso)}
            onClose={() => setViewDetailsIso(null)}
            onRegularize={() => { setViewDetailsIso(null); setRegularizeDate(viewDetailsIso); }}
            onApplyPartialDay={() => { setViewDetailsIso(null); setPartialDayDate(viewDetailsIso); }}
          />
        );
      })()}

      {regularizeDate && (
        <RequestModal
          token={token}
          initialDate={regularizeDate}
          approvedDates={approvedRegularizationDates}
          isSuperAdmin={isSuperAdmin}
          onClose={() => setRegularizeDate(null)}
          onSaved={(r) => { setRegularizations((prev) => [r, ...prev]); setRegularizeDate(null); }}
        />
      )}

      {partialDayDate && (
        <AttendanceRequestModal
          presetType="PARTIAL_DAY"
          token={token}
          initialDate={partialDayDate}
          onClose={() => setPartialDayDate(null)}
          onSaved={(r) => { setAttendanceRequests((prev) => [r, ...prev]); setPartialDayDate(null); }}
        />
      )}
    </div>
  );
});

// ─── Regularization (request + my requests + pending approvals) ───────────────

/** Reviewer column: current approver while pending, who decided it once resolved. */
function ReviewerCell({ r }: { r: RegularizationRecord }) {
  if (r.status === 'PENDING') {
    return (
      <>
        <div style={{ fontSize: 10.5, color: 'var(--txt-dim)' }}>Current Approver</div>
        {r.assignedApproverName ?? dash}
      </>
    );
  }
  if (r.status === 'PARTIALLY_APPROVED') {
    return (
      <>
        <div style={{ fontSize: 10.5, color: 'var(--txt-dim)' }}>Manager Approved — Awaiting HR/Super Admin</div>
        {r.approvedByName ?? dash}
      </>
    );
  }
  return (
    <>
      <div style={{ fontSize: 10.5, color: 'var(--txt-dim)' }}>{r.status === 'APPROVED' ? 'Approved By' : 'Rejected By'}</div>
      {r.reviewedByName ?? dash}
    </>
  );
}

function MonthGroupHeading({ monthKey }: { monthKey: string }) {
  return (
    <div style={{ fontSize: 11.5, fontWeight: 700, color: 'var(--txt-dim)', margin: '12px 0 6px', textTransform: 'uppercase', letterSpacing: '.06em' }}>
      {monthLabel(monthKey)}
    </div>
  );
}

// ─── Status tabs (reusable) ─────────────────────────────────────────────────────
// Generic single-select tab strip. Used by Pending Approvals for its All/Pending/Approved/
// Rejected status filter — deliberately generic (not hardcoded to status) so any future
// single-select category filter elsewhere on this page can reuse it as-is.
type StatusFilterValue = 'ALL' | 'PENDING' | 'PARTIALLY_APPROVED' | 'APPROVED' | 'REJECTED';

const STATUS_FILTER_TABS: { value: StatusFilterValue; label: string }[] = [
  { value: 'ALL', label: 'All' },
  { value: 'PENDING', label: 'Pending' },
  { value: 'PARTIALLY_APPROVED', label: 'Partially Approved' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
];

function FilterTabs<T extends string>({ value, options, onChange }: {
  value: T; options: { value: T; label: string }[]; onChange: (next: T) => void;
}) {
  return (
    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
      {options.map((opt) => {
        const active = opt.value === value;
        return (
          <button
            key={opt.value}
            onClick={() => onChange(opt.value)}
            style={{
              background: active ? 'var(--brand)' : 'var(--raised)',
              color: active ? '#fff' : 'var(--txt-mut)',
              border: `1px solid ${active ? 'var(--brand)' : 'var(--line2)'}`,
              borderRadius: 6, padding: '6px 12px', fontSize: 12, fontWeight: 600, cursor: 'pointer',
            }}
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}

// ─── My Requests month filter ──────────────────────────────────────────────────
// The only filter on My Requests (Employee, Manager, HR alike) — defaults to "All Months"
// so every request is visible until the user narrows it down.
const ALL_MONTHS_VALUE = 'ALL';

function MonthFilter({ month, onChange }: { month: string; onChange: (v: string) => void }) {
  return (
    <label style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 11.5, color: 'var(--txt-dim)' }}>
      Month
      <select value={month} onChange={(e) => onChange(e.target.value)} style={dateInputStyle}>
        <option value={ALL_MONTHS_VALUE}>All Months</option>
        {MONTH_NAMES.map((name, i) => (
          <option key={name} value={String(i + 1).padStart(2, '0')}>{name}</option>
        ))}
      </select>
    </label>
  );
}

export interface RegularizationSectionHandle {
  openNewRequest: () => void;
}

const RegularizationSection = forwardRef<RegularizationSectionHandle, { token: string; canApprove: boolean; isSuperAdmin: boolean; isManager: boolean }>(
  function RegularizationSection({ token, canApprove, isSuperAdmin, isManager }, ref) {
  const { showToast } = useToast();
  const { formatTime, formatDuration } = useTimeFormat();
  const [myRequests, setMyRequests] = useState<RegularizationRecord[]>([]);
  const [pending, setPending] = useState<RegularizationRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showRequest, setShowRequest] = useState(false);
  const [editing, setEditing] = useState<RegularizationRecord | null>(null);
  const [rejecting, setRejecting] = useState<RegularizationRecord | null>(null);
  const [approving, setApproving] = useState<RegularizationRecord | null>(null);
  const [viewing, setViewing] = useState<RegularizationRecord | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [bulkConfirm, setBulkConfirm] = useState<'APPROVE' | 'REJECT' | null>(null);

  const loadAll = useCallback(() => {
    const calls: Promise<unknown>[] = [regularizationApi.mine(token).then(setMyRequests)];
    // Every status the reviewer can see (not just PENDING) — the All/Pending/Approved/Rejected
    // tabs below filter this same list client-side, so Approved/Rejected history is visible too.
    if (canApprove) calls.push(regularizationApi.forApprover(token).then(setPending));
    return Promise.all(calls)
      .catch((err) => showToast('error', err instanceof Error ? err.message : 'Failed to load regularization requests'))
      .finally(() => setLoading(false));
  }, [token, canApprove, showToast]);

  useEffect(() => { loadAll(); }, [loadAll]);

  // My Requests: the only filter is Month, defaulting to "All Months" (every request visible
  // until narrowed). No status tabs here — those live on Pending Approvals instead.
  const [selectedMonth, setSelectedMonth] = useState(ALL_MONTHS_VALUE);
  const filteredMyRequests = useMemo(
    () => selectedMonth === ALL_MONTHS_VALUE
      ? myRequests
      : myRequests.filter((r) => r.attendanceDate.slice(5, 7) === selectedMonth),
    [myRequests, selectedMonth],
  );
  const myRequestMonths = useMemo(() => groupByMonth(filteredMyRequests), [filteredMyRequests]);

  // Pending Approvals: status tabs (All/Pending/Approved/Rejected), defaulting to Pending to
  // match the screen's pre-existing default view. Sourced from /for-approver (every status the
  // reviewer can see), not /pending (PENDING-only) — see loadAll below.
  const [approvalStatusFilter, setApprovalStatusFilter] = useState<StatusFilterValue>('PENDING');
  const filteredPending = useMemo(
    () => approvalStatusFilter === 'ALL' ? pending : pending.filter((r) => r.status === approvalStatusFilter),
    [pending, approvalStatusFilter],
  );
  const pendingMonths = useMemo(() => groupByMonth(filteredPending), [filteredPending]);
  // Selections don't carry across an unrelated filter change — avoids acting on a row the
  // user can no longer see.
  useEffect(() => { setSelectedIds(new Set()); }, [approvalStatusFilter]);
  const approvedDates = useMemo(
    () => new Set(myRequests.filter((r) => r.status === 'APPROVED').map((r) => r.attendanceDate)),
    [myRequests],
  );

  // Exposed to the page header's "Request Regularization" button — opens the exact same
  // create-mode modal the section's own flow uses.
  useImperativeHandle(ref, () => ({
    openNewRequest: () => { setEditing(null); setShowRequest(true); },
  }), []);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10 }}>
        <SectionHeading title="Attendance Regularization" hint="Request corrections for missed or incorrect punches." />
        {isSuperAdmin && (
          <Link to="/attendance/regularization/all" style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'var(--raised)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '8px 14px', fontSize: 12.5, fontWeight: 600, textDecoration: 'none' }}>
            <ShieldCheck size={13} /> View All & Audit Trail
          </Link>
        )}
      </div>

      {/* My Regularization Requests — month filter only, grouped by month within that filter */}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
          <h3 style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', margin: 0 }}>My Requests</h3>
          <MonthFilter month={selectedMonth} onChange={setSelectedMonth} />
        </div>
        {loading ? (
          <div style={{ ...panelStyle, padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>Loading…</div>
        ) : myRequests.length === 0 ? (
          <div style={{ ...panelStyle, padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No requests submitted yet.</div>
        ) : filteredMyRequests.length === 0 ? (
          <div style={{ ...panelStyle, padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>
            No requests found for {MONTH_NAMES[parseInt(selectedMonth, 10) - 1]}.
          </div>
        ) : (
          myRequestMonths.map(([monthKey, rows]) => (
            <div key={monthKey}>
              <MonthGroupHeading monthKey={monthKey} />
              <div style={panelStyle}>
                <div style={{ overflowX: 'auto' }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead><tr>{['Date', 'Requested In', 'Requested Out', 'Total Hours', 'Reason', 'Status', 'Approver / Reviewer', 'Comments', 'Action'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
                    <tbody>
                      {rows.map(r => (
                        <tr key={r.id} onClick={() => setViewing(r)} style={{ cursor: 'pointer' }}>
                          <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.attendanceDate}</td>
                          <td style={tdStyle}>{formatTime(r.requestedCheckIn) ?? dash}</td>
                          <td style={tdStyle}>{formatTime(r.requestedCheckOut) ?? dash}</td>
                          <td style={tdStyle}>{formatDuration(r.totalMinutes) ?? dash}</td>
                          <td style={{ ...tdStyle, maxWidth: 200 }}><TruncatedText text={r.reason} /></td>
                          <td style={tdStyle}><RegularizationStatusPill status={r.status} /></td>
                          <td style={tdStyle}><ReviewerCell r={r} /></td>
                          <td style={{ ...tdStyle, maxWidth: 180 }}><TruncatedText text={r.reviewComment} /></td>
                          <td style={tdStyle}>
                            {r.status === 'PENDING' && (
                              <button
                                onClick={(e) => { e.stopPropagation(); setEditing(r); }}
                                title="Edit"
                                style={{ display: 'flex', alignItems: 'center', gap: 4, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, padding: '4px 8px', fontSize: 11.5, color: 'var(--txt-mut)', cursor: 'pointer' }}
                              >
                                <Pencil size={11} /> Edit
                              </button>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          ))
        )}
      </div>

      {/* Pending Approvals — Manager / HR Admin / Super Admin only, grouped by month.
          Status tabs filter across every status the reviewer can see (not just PENDING). */}
      {canApprove && (
        <div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
            <h3 style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', margin: 0 }}>Pending Approvals</h3>
            <FilterTabs value={approvalStatusFilter} options={STATUS_FILTER_TABS} onChange={setApprovalStatusFilter} />
          </div>
          {selectedIds.size > 0 && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 9, marginBottom: 10, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, padding: '7px 13px' }}>
              <span style={{ fontSize: 12.5, color: 'var(--txt-mut)', fontWeight: 600 }}>{selectedIds.size} selected</span>
              <button onClick={() => setBulkConfirm('APPROVE')} style={{ background: 'rgba(47,182,124,.1)', border: '1px solid rgba(47,182,124,.25)', borderRadius: 5, padding: '5px 11px', fontSize: 11.5, fontWeight: 600, color: '#2FB67C', cursor: 'pointer' }}>Bulk Approve</button>
              <button onClick={() => setBulkConfirm('REJECT')} style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 5, padding: '5px 11px', fontSize: 11.5, fontWeight: 600, color: '#E4373D', cursor: 'pointer' }}>Bulk Reject</button>
              <button onClick={() => setSelectedIds(new Set())} style={{ background: 'none', border: 'none', color: 'var(--txt-dim)', fontSize: 11.5, cursor: 'pointer', marginLeft: 'auto' }}>Clear selection</button>
            </div>
          )}
          {pending.length === 0 ? (
            <div style={{ ...panelStyle, padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No requests to review yet.</div>
          ) : filteredPending.length === 0 ? (
            <div style={{ ...panelStyle, padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>
              No {STATUS_FILTER_TABS.find((t) => t.value === approvalStatusFilter)?.label.toLowerCase()} requests.
            </div>
          ) : (
            pendingMonths.map(([monthKey, rows]) => {
              const selectableIds = rows.filter(r => isActionableRequest(r, isManager)).map(r => r.id);
              const allSelected = selectableIds.length > 0 && selectableIds.every(id => selectedIds.has(id));
              const someSelected = !allSelected && selectableIds.some(id => selectedIds.has(id));
              return (
              <div key={monthKey}>
                <MonthGroupHeading monthKey={monthKey} />
                <div style={panelStyle}>
                  <div style={{ overflowX: 'auto' }}>
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                      <thead>
                        <tr>
                          <th style={{ ...thStyle, width: 34 }}>
                            {selectableIds.length > 0 && (
                              <input
                                type="checkbox"
                                checked={allSelected}
                                ref={el => { if (el) el.indeterminate = someSelected; }}
                                onChange={() => setSelectedIds(prev => {
                                  const next = new Set(prev);
                                  if (allSelected) selectableIds.forEach(id => next.delete(id));
                                  else selectableIds.forEach(id => next.add(id));
                                  return next;
                                })}
                              />
                            )}
                          </th>
                          {['Employee', 'Date', 'Requested In', 'Requested Out', 'Total Hours', 'Reason', 'Status', 'Reviewer', 'Actions'].map(h => <th key={h} style={thStyle}>{h}</th>)}
                        </tr>
                      </thead>
                      <tbody>
                        {rows.map(r => (
                          <tr key={r.id} onClick={() => setViewing(r)} style={{ cursor: 'pointer' }}>
                            <td style={tdStyle} onClick={e => e.stopPropagation()}>
                              {isActionableRequest(r, isManager) && (
                                <input
                                  type="checkbox"
                                  checked={selectedIds.has(r.id)}
                                  onChange={() => setSelectedIds(prev => {
                                    const next = new Set(prev);
                                    if (next.has(r.id)) next.delete(r.id); else next.add(r.id);
                                    return next;
                                  })}
                                />
                              )}
                            </td>
                            <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>
                              {r.employeeName}
                              <div style={{ fontSize: 10.5, color: 'var(--txt-dim)' }}>{r.employeeEmail}</div>
                            </td>
                            <td style={tdStyle}>{r.attendanceDate}</td>
                            <td style={tdStyle}>{formatTime(r.requestedCheckIn) ?? dash}</td>
                            <td style={tdStyle}>{formatTime(r.requestedCheckOut) ?? dash}</td>
                            <td style={tdStyle}>{formatDuration(r.totalMinutes) ?? dash}</td>
                            <td style={{ ...tdStyle, maxWidth: 220 }}><TruncatedText text={r.reason} /></td>
                            <td style={tdStyle}><RegularizationStatusPill status={r.status} /></td>
                            <td style={tdStyle}><ReviewerCell r={r} /></td>
                            <td style={tdStyle}>
                              {isActionableRequest(r, isManager) ? (
                                <div style={{ display: 'flex', gap: 6 }} onClick={e => e.stopPropagation()}>
                                  <button onClick={() => setApproving(r)} style={{ background: 'rgba(47,182,124,.1)', border: '1px solid rgba(47,182,124,.25)', borderRadius: 5, padding: '4px 9px', fontSize: 11, color: '#2FB67C', cursor: 'pointer' }}>Approve</button>
                                  <button onClick={() => setRejecting(r)} style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 5, padding: '4px 9px', fontSize: 11, color: '#E4373D', cursor: 'pointer' }}>Reject</button>
                                </div>
                              ) : dash}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              </div>
              );
            })
          )}
        </div>
      )}
      {bulkConfirm && (
        <BulkActionModal
          action={bulkConfirm}
          ids={[...selectedIds]}
          token={token}
          onClose={() => setBulkConfirm(null)}
          onDone={() => { setSelectedIds(new Set()); loadAll(); }}
        />
      )}

      {showRequest && (
        <RequestModal token={token} approvedDates={approvedDates} isSuperAdmin={isSuperAdmin} onClose={() => setShowRequest(false)} onSaved={r => setMyRequests(prev => [r, ...prev])} />
      )}
      {editing && (
        <RequestModal
          token={token}
          editing={editing}
          approvedDates={approvedDates}
          isSuperAdmin={isSuperAdmin}
          onClose={() => setEditing(null)}
          onSaved={updated => setMyRequests(prev => prev.map(r => (r.id === updated.id ? updated : r)))}
        />
      )}
      {approving && (
        <ConfirmApproveModal
          request={approving}
          token={token}
          onClose={() => setApproving(null)}
          onApproved={updated => setPending(prev => prev.map(r => (r.id === updated.id ? updated : r)))}
        />
      )}
      {rejecting && (
        <RejectModal
          request={rejecting}
          token={token}
          onClose={() => setRejecting(null)}
          onRejected={updated => setPending(prev => prev.map(r => (r.id === updated.id ? updated : r)))}
        />
      )}
      {viewing && (
        <RequestDetailsModal request={viewing} onClose={() => setViewing(null)} />
      )}
    </div>
  );
});

// ─── WFH / Partial Day requests — single-stage approval (no PARTIALLY_APPROVED stage, no
// bulk actions), mirroring WebClockInService's simpler flow rather than Regularization's
// two-stage one. See AttendanceRequestService for why. ──────────────────────────
function AttendanceRequestsSection({ token, canApprove }: { token: string; canApprove: boolean }) {
  const { showToast } = useToast();
  const [myRequests, setMyRequests] = useState<AttendanceRequestRecord[]>([]);
  const [pending, setPending] = useState<AttendanceRequestRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showRequest, setShowRequest] = useState(false);
  const [month, setMonth] = useState(ALL_MONTHS_VALUE);
  const [acting, setActing] = useState<{ request: AttendanceRequestRecord; action: 'APPROVE' | 'REJECT' } | null>(null);
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const loadAll = useCallback(() => {
    const calls: Promise<unknown>[] = [attendanceRequestApi.mine(token).then(setMyRequests)];
    if (canApprove) calls.push(attendanceRequestApi.pending(token).then(setPending));
    return Promise.all(calls)
      .catch((err) => showToast('error', err instanceof Error ? err.message : 'Failed to load attendance requests'))
      .finally(() => setLoading(false));
  }, [token, canApprove, showToast]);

  useEffect(() => { loadAll(); }, [loadAll]);

  const filteredMyRequests = useMemo(
    () => month === ALL_MONTHS_VALUE ? myRequests : myRequests.filter((r) => r.requestDate.slice(5, 7) === month),
    [myRequests, month],
  );

  async function handleAct() {
    if (!acting) return;
    if (acting.action === 'REJECT' && !comment.trim()) { showToast('error', 'A comment is required when rejecting'); return; }
    setSubmitting(true);
    try {
      const updated = acting.action === 'APPROVE'
        ? await attendanceRequestApi.approve(acting.request.id, token, comment.trim() || undefined)
        : await attendanceRequestApi.reject(acting.request.id, comment.trim(), token);
      setPending((prev) => prev.filter((r) => r.id !== updated.id));
      showToast('success', acting.action === 'APPROVE' ? 'Request approved' : 'Request rejected');
      setActing(null);
      setComment('');
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Action failed');
    } finally {
      setSubmitting(false);
    }
  }

  function typeLabel(r: AttendanceRequestRecord) {
    if (r.requestType === 'WFH') return 'Work From Home';
    const modeLabel = PARTIAL_DAY_MODE_OPTIONS.find((o) => o.value === r.partialDayMode)?.label;
    return modeLabel ? `Partial Day (${modeLabel})` : 'Partial Day';
  }

  function renderTable(rows: AttendanceRequestRecord[], showActions: boolean) {
    return (
      <div style={panelStyle}>
        {rows.length === 0 ? (
          <div style={{ padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>Nothing to show.</div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>{['Date', 'Type', 'Hours', 'Reason', 'Approver', 'Status', ...(showActions ? ['Actions'] : [])].map((h) => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id}>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{formatDay(r.requestDate)}</td>
                    <td style={tdStyle}>{typeLabel(r)}</td>
                    <td style={tdStyle}>{r.partialDayHours ?? dash}</td>
                    <td style={{ ...tdStyle, maxWidth: 220 }}><TruncatedText text={r.reason} /></td>
                    <td style={tdStyle}>{r.assignedApproverName ?? dash}</td>
                    <td style={tdStyle}><RegularizationStatusPill status={r.status} /></td>
                    {showActions && (
                      <td style={tdStyle}>
                        {r.status === 'PENDING' ? (
                          <div style={{ display: 'flex', gap: 6 }}>
                            <button onClick={() => setActing({ request: r, action: 'APPROVE' })} style={{ background: 'rgba(47,182,124,.15)', border: '1px solid rgba(47,182,124,.3)', borderRadius: 5, padding: '4px 9px', fontSize: 11, color: '#2FB67C', cursor: 'pointer', fontWeight: 600 }}>Approve</button>
                            <button onClick={() => setActing({ request: r, action: 'REJECT' })} style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 5, padding: '4px 9px', fontSize: 11, color: '#E4373D', cursor: 'pointer', fontWeight: 600 }}>Reject</button>
                          </div>
                        ) : dash}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      {canApprove && (
        <div>
          <SectionHeading title="Pending Approvals — WFH & Partial Day" />
          {loading ? <div style={{ color: 'var(--txt-dim)', padding: 18, fontSize: 12.5 }}>Loading…</div> : renderTable(pending, true)}
        </div>
      )}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
          <SectionHeading title="My Requests" />
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <MonthFilter month={month} onChange={setMonth} />
            <button
              onClick={() => setShowRequest(true)}
              style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '7px 13px', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}
            >
              <CalendarPlus size={12} /> New Request
            </button>
          </div>
        </div>
        {loading ? <div style={{ color: 'var(--txt-dim)', padding: 18, fontSize: 12.5 }}>Loading…</div> : renderTable(filteredMyRequests, false)}
      </div>
      {showRequest && (
        <AttendanceRequestModal
          token={token}
          onClose={() => setShowRequest(false)}
          onSaved={(r) => setMyRequests((prev) => [r, ...prev])}
        />
      )}
      {acting && (
        <div style={overlayStyle}>
          <div style={{ ...modalStyle, maxWidth: 420 }}>
            <ModalHeader title={`${acting.action === 'APPROVE' ? 'Approve' : 'Reject'} — ${acting.request.employeeName}`} onClose={() => setActing(null)} />
            <div style={{ padding: 24 }}>
              <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 14 }}>
                {typeLabel(acting.request)} · {formatDay(acting.request.requestDate)}
              </div>
              <Field label={acting.action === 'APPROVE' ? 'Comment (optional)' : 'Reason for rejection *'}>
                <textarea style={{ ...inputStyle, minHeight: 70, resize: 'vertical', fontFamily: 'inherit' }} value={comment} onChange={(e) => setComment(e.target.value)} />
              </Field>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 16 }}>
                <button onClick={() => setActing(null)} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
                <button
                  onClick={handleAct}
                  disabled={submitting}
                  style={{ background: acting.action === 'APPROVE' ? '#2FB67C' : '#C0392B', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}
                >
                  {submitting ? 'Submitting…' : acting.action === 'APPROVE' ? 'Confirm Approval' : 'Reject Request'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Overtime requests — same single-stage shape as AttendanceRequestsSection. ──
function OvertimeRequestsSection({ token, canApprove }: { token: string; canApprove: boolean }) {
  const { showToast } = useToast();
  const { formatTime, formatDuration } = useTimeFormat();
  const [myRequests, setMyRequests] = useState<OvertimeRequestRecord[]>([]);
  const [pending, setPending] = useState<OvertimeRequestRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showRequest, setShowRequest] = useState(false);
  const [month, setMonth] = useState(ALL_MONTHS_VALUE);
  const [acting, setActing] = useState<{ request: OvertimeRequestRecord; action: 'APPROVE' | 'REJECT' } | null>(null);
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const loadAll = useCallback(() => {
    const calls: Promise<unknown>[] = [overtimeRequestApi.mine(token).then(setMyRequests)];
    if (canApprove) calls.push(overtimeRequestApi.pending(token).then(setPending));
    return Promise.all(calls)
      .catch((err) => showToast('error', err instanceof Error ? err.message : 'Failed to load overtime requests'))
      .finally(() => setLoading(false));
  }, [token, canApprove, showToast]);

  useEffect(() => { loadAll(); }, [loadAll]);

  const filteredMyRequests = useMemo(
    () => month === ALL_MONTHS_VALUE ? myRequests : myRequests.filter((r) => r.workDate.slice(5, 7) === month),
    [myRequests, month],
  );

  async function handleAct() {
    if (!acting) return;
    if (acting.action === 'REJECT' && !comment.trim()) { showToast('error', 'A comment is required when rejecting'); return; }
    setSubmitting(true);
    try {
      const updated = acting.action === 'APPROVE'
        ? await overtimeRequestApi.approve(acting.request.id, token, comment.trim() || undefined)
        : await overtimeRequestApi.reject(acting.request.id, comment.trim(), token);
      setPending((prev) => prev.filter((r) => r.id !== updated.id));
      showToast('success', acting.action === 'APPROVE' ? 'Request approved' : 'Request rejected');
      setActing(null);
      setComment('');
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Action failed');
    } finally {
      setSubmitting(false);
    }
  }

  function renderTable(rows: OvertimeRequestRecord[], showActions: boolean) {
    return (
      <div style={panelStyle}>
        {rows.length === 0 ? (
          <div style={{ padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>Nothing to show.</div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>{['Date', 'Overtime Hours', 'Reason', 'Approver', 'Status', ...(showActions ? ['Actions'] : [])].map((h) => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id}>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{formatDay(r.workDate)}</td>
                    <td style={tdStyle}>{formatDuration(r.requestedMinutes) ?? dash}</td>
                    <td style={{ ...tdStyle, maxWidth: 220 }}><TruncatedText text={r.reason} /></td>
                    <td style={tdStyle}>{r.assignedApproverName ?? dash}</td>
                    <td style={tdStyle}><RegularizationStatusPill status={r.status} /></td>
                    {showActions && (
                      <td style={tdStyle}>
                        {r.status === 'PENDING' ? (
                          <div style={{ display: 'flex', gap: 6 }}>
                            <button onClick={() => setActing({ request: r, action: 'APPROVE' })} style={{ background: 'rgba(47,182,124,.15)', border: '1px solid rgba(47,182,124,.3)', borderRadius: 5, padding: '4px 9px', fontSize: 11, color: '#2FB67C', cursor: 'pointer', fontWeight: 600 }}>Approve</button>
                            <button onClick={() => setActing({ request: r, action: 'REJECT' })} style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 5, padding: '4px 9px', fontSize: 11, color: '#E4373D', cursor: 'pointer', fontWeight: 600 }}>Reject</button>
                          </div>
                        ) : dash}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      {canApprove && (
        <div>
          <SectionHeading title="Pending Approvals — Overtime" />
          {loading ? <div style={{ color: 'var(--txt-dim)', padding: 18, fontSize: 12.5 }}>Loading…</div> : renderTable(pending, true)}
        </div>
      )}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
          <SectionHeading title="My Requests" />
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <MonthFilter month={month} onChange={setMonth} />
            <button
              onClick={() => setShowRequest(true)}
              style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '7px 13px', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}
            >
              <CalendarPlus size={12} /> New Request
            </button>
          </div>
        </div>
        {loading ? <div style={{ color: 'var(--txt-dim)', padding: 18, fontSize: 12.5 }}>Loading…</div> : renderTable(filteredMyRequests, false)}
      </div>
      {showRequest && (
        <OvertimeRequestModal
          token={token}
          existingRequests={myRequests}
          onClose={() => setShowRequest(false)}
          onSaved={(r) => setMyRequests((prev) => [r, ...prev])}
        />
      )}
      {acting && (
        <div style={overlayStyle}>
          <div style={{ ...modalStyle, maxWidth: 420 }}>
            <ModalHeader title={`${acting.action === 'APPROVE' ? 'Approve' : 'Reject'} — ${acting.request.employeeName}`} onClose={() => setActing(null)} />
            <div style={{ padding: 24 }}>
              <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 14 }}>
                {formatDay(acting.request.workDate)} · {formatTime(acting.request.requestedStart) ?? dash} → {formatTime(acting.request.requestedEnd) ?? dash}
              </div>
              <Field label={acting.action === 'APPROVE' ? 'Comment (optional)' : 'Reason for rejection *'}>
                <textarea style={{ ...inputStyle, minHeight: 70, resize: 'vertical', fontFamily: 'inherit' }} value={comment} onChange={(e) => setComment(e.target.value)} />
              </Field>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 16 }}>
                <button onClick={() => setActing(null)} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
                <button
                  onClick={handleAct}
                  disabled={submitting}
                  style={{ background: acting.action === 'APPROVE' ? '#2FB67C' : '#C0392B', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}
                >
                  {submitting ? 'Submitting…' : acting.action === 'APPROVE' ? 'Confirm Approval' : 'Reject Request'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

type OvertimeHoursMode = 'FIXED' | 'CUSTOM';

/**
 * Plain "H:mm" or "HH:mm" duration, no AM/PM — e.g. "2:15" -> 135 minutes. This is the only
 * "valid hours" check this form applies (well-formed, positive, <= 24h); per policy an employee
 * may request OT regardless of what attendance logs show as already "earned," so this never
 * checks that figure.
 */
function parseDurationHM(text: string): number | null {
  const m = text.trim().match(/^([0-9]{1,3}):([0-5][0-9])$/);
  if (!m) return null;
  const minutes = parseInt(m[1], 10) * 60 + parseInt(m[2], 10);
  return minutes > 0 && minutes <= 24 * 60 ? minutes : null;
}

function formatDurationHM(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

// ─── Overtime submit modal — Keka-style multi-day range. Overtime detected from attendance logs
// per day is only a starting suggestion (Fixed hours: one value applied to every day; Custom
// hours: an editable hh:mm per day) — never a gate. Submission is blocked only on malformed
// entered hours, never on the detected/existing balance being zero.
function OvertimeRequestModal({ onClose, onSaved, token, existingRequests }: {
  onClose: () => void;
  onSaved: (r: OvertimeRequestRecord) => void;
  token: string;
  existingRequests: OvertimeRequestRecord[];
}) {
  const { showToast } = useToast();
  const { formatDuration } = useTimeFormat();
  const [fromDate, setFromDate] = useState(todayIsoDate());
  const [toDate, setToDate] = useState(todayIsoDate());
  const [mode, setMode] = useState<OvertimeHoursMode>('FIXED');
  const [fixedHoursText, setFixedHoursText] = useState('');
  const [perDayHoursText, setPerDayHoursText] = useState<Record<string, string>>({});
  const [reason, setReason] = useState('');
  const [managerUserId, setManagerUserId] = useState('');
  const [notifyEntry, setNotifyEntry] = useState<DirectoryEntry | null>(null);
  const [approvers, setApprovers] = useState<ApproverOption[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    regularizationApi.approvers(token).then(setApprovers).catch(() => { /* dropdown degrades to empty */ });
  }, [token]);

  const [config, setConfig] = useState<AttendanceConfig | null>(null);
  useEffect(() => {
    attendanceApi.config(token).then(setConfig).catch(() => setConfig(null));
  }, [token]);

  const dateList = useMemo(
    () => (toDate >= fromDate ? expandDateRange(fromDate, toDate) : []),
    [fromDate, toDate],
  );

  // Dates already covered by a PENDING or APPROVED request of this employee's own — queried
  // client-side from the list OvertimeRequestsSection already loaded (no extra fetch needed).
  // Only a REJECTED request never blocks a new one for the same date.
  const pendingDatesByWorkDate = useMemo(() => {
    const map = new Map<string, OvertimeRequestRecord>();
    existingRequests.forEach((r) => { if (r.status === 'PENDING' || r.status === 'APPROVED') map.set(r.workDate, r); });
    return map;
  }, [existingRequests]);
  const conflictingDates = useMemo(
    () => dateList.filter((d) => pendingDatesByWorkDate.has(d)),
    [dateList, pendingDatesByWorkDate],
  );
  const hasPendingConflict = conflictingDates.length > 0;

  // Fetched purely to seed a starting suggestion and for the "fetched from attendance logs"
  // readout — never used to block submission (a day with no attendance record is fine; it just
  // suggests 0 and the employee can still enter hours).
  const [recordsByDate, setRecordsByDate] = useState<Record<string, AttendanceRecord | null>>({});
  const [loadingDays, setLoadingDays] = useState(false);
  useEffect(() => {
    if (dateList.length === 0) { setRecordsByDate({}); return; }
    let cancelled = false;
    setLoadingDays(true);
    Promise.all(dateList.map((d) =>
      attendanceApi.punchForDate(d, token).then((r) => [d, r] as const).catch(() => [d, null] as const),
    ))
      .then((results) => { if (!cancelled) setRecordsByDate(Object.fromEntries(results)); })
      .finally(() => { if (!cancelled) setLoadingDays(false); });
    return () => { cancelled = true; };
  }, [dateList, token]);

  const fullDayTargetMinutes = fullDayTargetMinutesFor(config);
  const detectedMinutesFor = useCallback((date: string) => {
    const worked = recordsByDate[date]?.workedMinutes;
    return fullDayTargetMinutes != null && worked != null ? Math.max(0, worked - fullDayTargetMinutes) : 0;
  }, [recordsByDate, fullDayTargetMinutes]);

  const totalDetectedMinutes = useMemo(
    () => dateList.reduce((sum, d) => sum + detectedMinutesFor(d), 0),
    [dateList, detectedMinutesFor],
  );

  // Custom-mode fields default to that day's detected overtime the first time it's seen, but
  // never overwrite a value already typed for that date (including one the employee cleared).
  useEffect(() => {
    setPerDayHoursText((prev) => {
      const next = { ...prev };
      let changed = false;
      for (const d of dateList) {
        if (next[d] === undefined) {
          const detected = detectedMinutesFor(d);
          next[d] = detected > 0 ? formatDurationHM(detected) : '';
          changed = true;
        }
      }
      return changed ? next : prev;
    });
  }, [dateList, detectedMinutesFor]);

  function entryFor(date: string): string {
    return mode === 'FIXED' ? fixedHoursText : (perDayHoursText[date] ?? '');
  }

  async function handleSubmit() {
    if (toDate < fromDate) { setError('To date must be on or after From date'); return; }
    if (fromDate < todayIsoDate()) { setError('Cannot request for past dates'); return; }
    if (hasPendingConflict) {
      setError('Previous Overtime request is in pending / approved for the selected dates.');
      return;
    }
    if (!reason.trim()) { setError('Reason is required'); return; }

    const entries: { date: string; minutes: number }[] = [];
    for (const d of dateList) {
      const text = entryFor(d);
      if (!text.trim()) continue;
      const minutes = parseDurationHM(text);
      if (minutes == null) { setError(`Enter a valid hh:mm value for ${formatDay(d)}`); return; }
      entries.push({ date: d, minutes });
    }
    if (entries.length === 0) { setError('Enter overtime hours for at least one day'); return; }

    setSubmitting(true);
    setError(null);
    try {
      const created = await Promise.all(entries.map(({ date, minutes }) => {
        // requestedStart/requestedEnd still back the stored request (the schema requires both
        // and checks requestedEnd > requestedStart) — a plain midnight-anchored span sized to
        // the entered minutes, since these are now claimed hours, not real clock times.
        const requestedStart = `${date}T00:00:00`;
        const endDate = new Date(requestedStart);
        endDate.setMinutes(endDate.getMinutes() + minutes);
        const requestedEnd = `${isoOf(endDate.getFullYear(), endDate.getMonth(), endDate.getDate())}T`
          + `${pad2(endDate.getHours())}:${pad2(endDate.getMinutes())}:00`;
        return overtimeRequestApi.submit({
          workDate: date, requestedStart, requestedEnd, reason: reason.trim(),
          managerUserId: managerUserId || undefined,
          notifyUserId: notifyEntry?.userId || undefined,
        }, token);
      }));
      created.forEach(onSaved);
      showToast('success', created.length > 1
        ? `${created.length} overtime requests submitted for approval`
        : 'Overtime request submitted for approval');
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to submit overtime request';
      setError(msg);
      showToast('error', msg);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 460 }}>
        <ModalHeader title="Request Overtime (OT)" onClose={onClose} />
        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {error && <OhNoError message={error} onDismiss={() => setError(null)} />}
          <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end' }}>
            <div style={{ flex: 1 }}>
              <Field label="From">
                <input type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} style={inputStyle} />
              </Field>
            </div>
            <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', paddingBottom: 10, whiteSpace: 'nowrap' }}>
              {dateList.length > 0 ? `${dateList.length} day${dateList.length > 1 ? 's' : ''}` : '—'}
            </div>
            <div style={{ flex: 1 }}>
              <Field label="To">
                <input type="date" value={toDate} min={fromDate} onChange={(e) => setToDate(e.target.value)} style={inputStyle} />
              </Field>
            </div>
          </div>
          <div style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>
            {loadingDays ? 'Checking attendance…' : (
              <>You have <strong style={{ color: 'var(--txt)' }}>{formatDuration(totalDetectedMinutes) ?? '0m'}</strong> of overtime for selected day{dateList.length > 1 ? 's' : ''} (fetched from attendance logs)</>
            )}
          </div>
          {hasPendingConflict && (
            <div style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 7, padding: '8px 10px', fontSize: 12, color: 'var(--risk)', fontWeight: 600 }}>
              Previous Overtime request is in pending / approved for the selected dates.
              {conflictingDates.length > 0 && ` (${conflictingDates.map((d) => formatDay(d)).join(', ')})`}
            </div>
          )}
          <FilterTabs
            value={mode}
            onChange={setMode}
            options={[{ value: 'FIXED', label: 'Fixed hours' }, { value: 'CUSTOM', label: 'Custom hours' }]}
          />
          {mode === 'FIXED' ? (
            <Field label="Overtime hours">
              <input value={fixedHoursText} onChange={(e) => setFixedHoursText(e.target.value)} placeholder="hh:mm" style={inputStyle} />
            </Field>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, maxHeight: 220, overflowY: 'auto' }}>
              {dateList.length === 0 ? (
                <div style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>Pick a valid date range above.</div>
              ) : dateList.map((d) => {
                const conflict = pendingDatesByWorkDate.get(d);
                return (
                  <div key={d} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10 }}>
                    <span style={{ fontSize: 12.5, color: conflict ? 'var(--risk)' : 'var(--txt-mut)' }}>
                      {formatDay(d)}{conflict && ` · ${conflict.status === 'APPROVED' ? 'Approved' : 'Pending'}`}
                    </span>
                    <input
                      value={perDayHoursText[d] ?? ''}
                      onChange={(e) => setPerDayHoursText((prev) => ({ ...prev, [d]: e.target.value }))}
                      placeholder="hh:mm"
                      disabled={!!conflict}
                      style={{ ...inputStyle, width: 110, opacity: conflict ? 0.5 : 1 }}
                    />
                  </div>
                );
              })}
            </div>
          )}
          <div style={{ background: 'rgba(224,169,59,.12)', border: '1px solid rgba(224,169,59,.35)', borderRadius: 7, padding: '8px 10px', fontSize: 12, color: 'var(--txt-mut)' }}>
            Overtime compensation requires an approved request with valid hours.
          </div>
          <Field label="Reason *">
            <textarea
              style={{ ...inputStyle, minHeight: 70, resize: 'vertical', fontFamily: 'inherit' }}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Why is overtime needed?"
            />
          </Field>
          <Field label="Assign To (optional)">
            <select value={managerUserId} onChange={(e) => setManagerUserId(e.target.value)} style={inputStyle}>
              <option value="">Current manager</option>
              {approvers.map((a) => (
                <option key={a.userId} value={a.userId}>{a.fullName} ({a.roleCode})</option>
              ))}
            </select>
          </Field>
          <NotifyEmployeeField token={token} value={notifyEntry} onChange={setNotifyEntry} />
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 6 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button
              onClick={handleSubmit}
              disabled={submitting || hasPendingConflict}
              style={{
                background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600,
                cursor: (submitting || hasPendingConflict) ? 'not-allowed' : 'pointer',
                opacity: (submitting || hasPendingConflict) ? 0.6 : 1,
              }}
            >
              {submitting ? 'Submitting…' : 'Submit for Approval'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Roster view (Manager team / HR org-wide) ─────────────────────────────────

function DayRoster({ scope }: { scope: 'team' | 'all' }) {
  const token = useAuthStore((s) => s.token)!;
  const { showToast } = useToast();

  const [date, setDate] = useState(todayIsoDate());
  const [rows, setRows] = useState<AttendanceRecord[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    const fetcher = scope === 'team' ? attendanceApi.team : attendanceApi.day;
    fetcher(date, token)
      .then((r) => { if (!cancelled) setRows(r); })
      .catch((err) => {
        if (cancelled) return;
        setRows([]);
        showToast('error', err instanceof Error ? err.message : 'Failed to load attendance');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [scope, date, token, showToast]);

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
        <SectionHeading
          title={scope === 'team' ? 'Team attendance' : 'Organization attendance'}
          hint={scope === 'team'
            ? 'Your current direct reports for the selected day.'
            : 'All active employees for the selected day.'}
        />
        <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: 'var(--txt-dim)', marginBottom: 12 }}>
          Date
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} style={dateInputStyle} />
        </label>
      </div>
      <RosterTable
        rows={rows}
        loading={loading}
        emptyMessage={scope === 'team'
          ? 'No direct reports are assigned to you yet.'
          : 'No employee records for this date.'}
      />
    </div>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

// Sub-tab inside the "Attendance Requests" main tab — Regularization and WFH & Partial Day
// used to be two of three siblings in a flat FilterTabs; they now nest one level deeper so the
// outer bar can match Keka's exact 4-tab list (Overtime Requests moved out to its own main tab).
type AttendanceRequestsSubTab = 'REGULARIZATION' | 'WFH_PARTIAL_DAY';

function AttendancePageInner() {
  // The router has no role guard, so — like Shell — the page resolves the role itself.
  const token = useAuthStore((s) => s.token)!;
  const role = toShellRole(useAuthStore((s) => s.user?.role));
  const canApprove = role === 'Manager' || role === 'HR Admin' || role === 'Super Admin';

  const subtitle = role === 'Manager'
    ? 'Review daily attendance for the selected month, and your team’s attendance for any day.'
    : role === 'HR Admin' || role === 'Super Admin'
      ? 'Review daily attendance for the selected month, and attendance across the organization.'
      : 'Review daily attendance for the selected month.';

  const myAttendanceRef = useRef<MyAttendanceHandle>(null);
  const regularizationRef = useRef<RegularizationSectionHandle>(null);
  const [logsTab, setLogsTab] = useState<LogsTab>('ATTENDANCE_LOG');
  const [requestsSubTab, setRequestsSubTab] = useState<AttendanceRequestsSubTab>('REGULARIZATION');
  const pendingOpenRequest = useRef(false);

  // The header's "Request Regularization" button may be clicked while a different tab is
  // active (RegularizationSection unmounted, ref not yet attached) — switch tabs first, then
  // open the modal once the ref attaches (refs commit before this effect runs).
  useEffect(() => {
    if (logsTab === 'ATTENDANCE_REQUESTS' && requestsSubTab === 'REGULARIZATION' && pendingOpenRequest.current) {
      pendingOpenRequest.current = false;
      regularizationRef.current?.openNewRequest();
    }
  }, [logsTab, requestsSubTab]);

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap', marginBottom: 22 }}>
        <div>
          <h1 style={{
            fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700,
            color: 'var(--txt)', margin: 0,
          }}>My Attendance</h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>{subtitle}</p>
        </div>
        <div style={{ display: 'flex', gap: 10 }}>
          <button
            onClick={() => myAttendanceRef.current?.exportMonth()}
            style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--raised)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}
          >
            <Download size={14} /> Export selected month
          </button>
          <button
            onClick={() => {
              if (logsTab === 'ATTENDANCE_REQUESTS' && requestsSubTab === 'REGULARIZATION') {
                regularizationRef.current?.openNewRequest();
              } else {
                pendingOpenRequest.current = true;
                setLogsTab('ATTENDANCE_REQUESTS');
                setRequestsSubTab('REGULARIZATION');
              }
            }}
            style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}
          >
            <CalendarPlus size={14} /> Request Regularization
          </button>
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 30 }}>
        <MyAttendance
          ref={myAttendanceRef}
          isSuperAdmin={role === 'Super Admin'}
          logsTab={logsTab}
          onLogsTabChange={setLogsTab}
          otherTabContent={
            logsTab === 'ATTENDANCE_REQUESTS' ? (
              <div>
                <div style={{ marginBottom: 16 }}>
                  <FilterTabs
                    value={requestsSubTab}
                    onChange={setRequestsSubTab}
                    options={[
                      { value: 'REGULARIZATION', label: 'Regularization' },
                      { value: 'WFH_PARTIAL_DAY', label: 'WFH & Partial Day' },
                    ]}
                  />
                </div>
                {requestsSubTab === 'REGULARIZATION' && (
                  <RegularizationSection ref={regularizationRef} token={token} canApprove={canApprove} isSuperAdmin={role === 'Super Admin'} isManager={role === 'Manager'} />
                )}
                {requestsSubTab === 'WFH_PARTIAL_DAY' && (
                  <AttendanceRequestsSection token={token} canApprove={canApprove} />
                )}
              </div>
            ) : logsTab === 'OVERTIME' ? (
              <OvertimeRequestsSection token={token} canApprove={canApprove} />
            ) : null
          }
        />

        {role === 'Manager' && <DayRoster scope="team" />}
        {(role === 'HR Admin' || role === 'Super Admin') && <DayRoster scope="all" />}
      </div>
    </div>
  );
}

export default function AttendancePage() {
  return (
    <TimeFormatProvider>
      <AttendancePageInner />
    </TimeFormatProvider>
  );
}

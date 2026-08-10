import { useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState, forwardRef } from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router-dom';
import * as XLSX from 'xlsx';
import { Clock, LogIn, LogOut, CheckCircle2, CalendarPlus, Pencil, ShieldCheck, X, ChevronLeft, ChevronRight, Download, Eye, Laptop, Home, Sun, FileText, Users, User } from 'lucide-react';
import {
  attendanceApi, regularizationApi,
  type AttendanceRecord,
  type AttendanceStatus,
  type TodayAttendance,
  type AttendanceConfig,
  type AttendanceStats,
  type AttendanceExceptionRecord,
  type RegularizationRecord,
  type SubmitRegularizationPayload,
  type ApproverOption,
  type Punch,
} from '../api/attendance';
import {
  attendanceRequestApi,
  type AttendanceRequestRecord,
  type AttendanceRequestType,
  type SubmitAttendanceRequestPayload,
} from '../api/attendanceRequests';
import { overtimeRequestApi, type OvertimeRequestRecord } from '../api/overtimeRequests';
import { WebClockInRequestModal } from '../components/WebClockInRequestModal';
import { holidaysApi, type HolidayRow } from '../api/holidays';
import { leaveApi, type LeaveRequestRecord } from '../api/leave';
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

/** Minutes between two "YYYY-MM-DDTHH:mm" strings, or null if either is missing/out of order. */
function minutesBetween(checkIn: string, checkOut: string): number | null {
  if (!checkIn || !checkOut) return null;
  const inMs = new Date(checkIn).getTime();
  const outMs = new Date(checkOut).getTime();
  if (!Number.isFinite(inMs) || !Number.isFinite(outMs) || outMs <= inMs) return null;
  return Math.round((outMs - inMs) / 60000);
}

// ─── 12-hour time text input (Corrected Check-in / Check-out) ────────────────
// A single free-text field — not separate hour/minute/AM-PM dropdowns. Keystrokes are
// masked into "H:MM AM/PM" as the user types, and the result is validated against a
// strict 12-hour pattern before it's allowed to become a server timestamp.

type Period = 'AM' | 'PM';
interface TimeValue { hour: string; minute: string; period: Period | ''; }
const EMPTY_TIME: TimeValue = { hour: '', minute: '', period: '' };

/** Server LocalDateTime string ("2026-07-29T09:30:00") -> 12-hour time parts. */
function timeValueFromIso(iso: string | null | undefined): TimeValue {
  if (!iso) return EMPTY_TIME;
  const timePart = iso.slice(11, 16);
  if (timePart.length < 5) return EMPTY_TIME;
  const [h24, m] = timePart.split(':').map(Number);
  if (!Number.isFinite(h24) || !Number.isFinite(m)) return EMPTY_TIME;
  const period: Period = h24 < 12 ? 'AM' : 'PM';
  const hour12 = h24 % 12 === 0 ? 12 : h24 % 12;
  return { hour: String(hour12), minute: String(m).padStart(2, '0'), period };
}

/** True only when hour, minute, and AM/PM are all present. */
function isTimeValueComplete(t: TimeValue): boolean {
  return !!(t.hour && t.minute && t.period);
}

/** Combines a date (YYYY-MM-DD) with a complete 12-hour time into a server LocalDateTime string. */
function isoFromTimeValue(dateStr: string, t: TimeValue): string | undefined {
  if (!dateStr || !isTimeValueComplete(t)) return undefined;
  let h24 = parseInt(t.hour, 10) % 12;
  if (t.period === 'PM') h24 += 12;
  return `${dateStr}T${String(h24).padStart(2, '0')}:${t.minute}`;
}

/** TimeValue -> display text, e.g. {hour:'9', minute:'30', period:'AM'} -> "9:30 AM". */
function formatTimeValue(t: TimeValue): string {
  return isTimeValueComplete(t) ? `${t.hour}:${t.minute} ${t.period}` : '';
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
function TimeTextInput({ label, value, touched, onChange, onBlur }: {
  label: string; value: string; touched: boolean; onChange: (text: string) => void; onBlur: () => void;
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
          {empty ? 'This field is required.' : 'Enter a valid 12-hour time, e.g. 09:30 AM or 5:45 PM.'}
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
};

const STATUS_LABELS: Record<AttendanceStatus, string> = {
  PRESENT: 'Present',
  LATE: 'Late',
  HALF_DAY: 'Half Day',
  ABSENT: 'Absent',
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
  padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700,
  color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em',
  borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap',
};
const tdStyle: React.CSSProperties = {
  padding: '12px 14px', fontSize: 13, color: 'var(--txt-mut)',
  borderBottom: '1px solid var(--line)', verticalAlign: 'middle',
};
const panelStyle: React.CSSProperties = {
  background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden',
};
const dateInputStyle: React.CSSProperties = {
  background: 'var(--raised)', color: 'var(--txt)', border: '1px solid var(--line2)',
  borderRadius: 7, padding: '7px 10px', fontSize: 13,
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
      fontSize: 11, fontWeight: 600, color,
      background: 'var(--raised)', border: '1px solid var(--line)',
      borderRadius: 4, padding: '2px 7px', whiteSpace: 'nowrap',
    }}>
      {STATUS_LABELS[status] ?? status}
    </span>
  );
}

function RegularizationStatusPill({ status }: { status: string }) {
  return (
    <span style={{ fontSize: 11, fontWeight: 600, color: REGULARIZATION_STATUS_COLOR[status] ?? '#9BA1AC', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 4, padding: '2px 7px' }}>
      {status}
    </span>
  );
}

/** Every check-in/check-out session for a single day — shows a lunch-break gap explicitly. */
function PunchHistoryList({ date, token }: { date: string; token: string }) {
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
  }, [date, token]);

  if (error) {
    return <div style={{ fontSize: 12, color: 'var(--risk)' }}>Punch history: {error}</div>;
  }
  if (punches === null) {
    return <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Loading punch history…</div>;
  }
  if (punches.length <= 1) return null; // a single session adds nothing beyond the bookends above

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 5 }}>
        <Clock size={11} /> Punch History
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {punches.map((p, i) => (
          <div key={p.id} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5, color: 'var(--txt-mut)' }}>
            <span style={{ color: 'var(--txt-dim)', fontSize: 11 }}>{i + 1}.</span>
            <LogIn size={12} style={{ color: 'var(--txt-dim)' }} />
            <span>{formatTime(p.checkInAt) ?? dash}</span>
            <span style={{ color: 'var(--txt-dim)' }}>→</span>
            <LogOut size={12} style={{ color: 'var(--txt-dim)' }} />
            <span>{formatTime(p.checkOutAt) ?? 'still open'}</span>
          </div>
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
    <div style={{ marginBottom: 12 }}>
      <h2 style={{
        fontFamily: '"Space Grotesk", sans-serif', fontSize: 15, fontWeight: 700,
        color: 'var(--txt)', margin: 0,
      }}>{title}</h2>
      {hint && <p style={{ fontSize: 12, color: 'var(--txt-dim)', marginTop: 3 }}>{hint}</p>}
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
function RequestModal({ onClose, onSaved, token, editing, approvedDates, isSuperAdmin }: {
  onClose: () => void;
  onSaved: (r: RegularizationRecord) => void;
  token: string;
  editing?: RegularizationRecord;
  /** Attendance dates that already have an APPROVED regularization — resubmission is blocked. */
  approvedDates: Set<string>;
  /** Super Admin is exempt from the date-window restriction below (Requirement 1). */
  isSuperAdmin: boolean;
}) {
  const { showToast } = useToast();
  const { formatTime, formatDuration } = useTimeFormat();
  const today = todayIsoDate();
  // Employee/Manager/HR: earliest attendance date selectable in the calendar picker. Super
  // Admin has no lower bound — "any number of previous days" per Requirement 1.
  const minDate = isSuperAdmin ? undefined : isoDaysAgo(REGULARIZATION_LOOKBACK_DAYS - 1);
  const [attendanceDate, setAttendanceDate] = useState(editing?.attendanceDate ?? today);
  const [checkInText, setCheckInText] = useState(formatTimeValue(timeValueFromIso(editing?.requestedCheckIn)));
  const [checkOutText, setCheckOutText] = useState(formatTimeValue(timeValueFromIso(editing?.requestedCheckOut)));
  const [checkInTouched, setCheckInTouched] = useState(false);
  const [checkOutTouched, setCheckOutTouched] = useState(false);
  const [reason, setReason] = useState(editing?.reason ?? '');
  const [managerUserId, setManagerUserId] = useState(editing?.assignedApproverId ?? '');
  const [approvers, setApprovers] = useState<ApproverOption[]>([]);
  const [existingPunch, setExistingPunch] = useState<AttendanceRecord | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitAttempted, setSubmitAttempted] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    regularizationApi.approvers(token).then(setApprovers).catch(() => { /* dropdown degrades to empty — Assign To is still required */ });
  }, [token]);

  // Punch auto-fill + field visibility (scenarios 1/2): look up what's already on file for
  // the chosen date. Clearing to null immediately (not just when the date is blank) avoids a
  // stale flash of the previous date's fields while the new lookup is in flight.
  useEffect(() => {
    setExistingPunch(null);
    if (!attendanceDate) return;
    let cancelled = false;
    attendanceApi.punchForDate(attendanceDate, token)
      .then((punch) => { if (!cancelled) setExistingPunch(punch); })
      .catch(() => { if (!cancelled) setExistingPunch(null); });
    return () => { cancelled = true; };
  }, [attendanceDate, token]);

  useEffect(() => {
    if (!existingPunch) return;
    setCheckInText((prev) => (prev.trim() ? prev : formatTimeValue(timeValueFromIso(existingPunch.checkInAt))));
    setCheckOutText((prev) => (prev.trim() ? prev : formatTimeValue(timeValueFromIso(existingPunch.checkOutAt))));
  }, [existingPunch]);

  // Scenario 1 (missing check-out only) / Scenario 2 (missing both): a field is hidden only
  // when we know for certain the OTHER side is already on file and this one specifically is
  // what's missing. If neither side is on file, or both already are (correcting a wrong
  // punch), both fields stay visible. Hidden fields are never rendered and never required.
  const hasCheckIn = !!existingPunch?.checkInAt;
  const hasCheckOut = !!existingPunch?.checkOutAt;
  const onlyOneSideOnFile = hasCheckIn !== hasCheckOut;
  const showCheckIn = !onlyOneSideOnFile || !hasCheckIn;
  const showCheckOut = !onlyOneSideOnFile || !hasCheckOut;

  // Only a fully-typed, valid 12-hour time converts to a server timestamp — invalid or
  // partial text must block submit rather than silently being dropped.
  const checkInValue = parseTimeText(checkInText);
  const checkOutValue = parseTimeText(checkOutText);
  const checkInIso = checkInValue ? isoFromTimeValue(attendanceDate, checkInValue) : undefined;
  const checkOutIso = checkOutValue ? isoFromTimeValue(attendanceDate, checkOutValue) : undefined;
  const totalHoursLabel = useMemo(
    () => formatDuration(minutesBetween(checkInIso ?? '', checkOutIso ?? '')),
    [checkInIso, checkOutIso],
  );

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
    setCheckInTouched(true);
    setCheckOutTouched(true);

    const dateMissing = !attendanceDate;
    const reasonMissing = !reason.trim();
    const managerMissing = !managerUserId;
    // Hidden fields are never required — only a currently-visible field can block submit.
    const checkInMissing = showCheckIn && checkInText.trim() === '';
    const checkOutMissing = showCheckOut && checkOutText.trim() === '';
    const checkInInvalid = showCheckIn && checkInText.trim() !== '' && !checkInValue;
    const checkOutInvalid = showCheckOut && checkOutText.trim() !== '' && !checkOutValue;

    if (dateMissing || reasonMissing || managerMissing || checkInMissing || checkOutMissing) {
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
    if (checkInInvalid || checkOutInvalid) {
      setError('Enter valid 12-hour times, e.g. 09:30 AM or 5:45 PM.');
      return;
    }
    setSubmitting(true); setError(null);
    try {
      const payload: SubmitRegularizationPayload = {
        attendanceDate,
        requestedCheckIn: showCheckIn ? checkInIso : undefined,
        requestedCheckOut: showCheckOut ? checkOutIso : undefined,
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
        <ModalHeader title={editing ? 'Edit Regularization Request' : 'Request Regularization'} onClose={onClose} />
        <form onSubmit={handleSubmit} style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
          {error && <div style={{ color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          <Field label="Attendance Date *">
            <input type="date" style={inputStyle} value={attendanceDate} max={today} min={minDate}
              onChange={e => {
                setAttendanceDate(e.target.value);
                setCheckInText(''); setCheckOutText('');
                setCheckInTouched(false); setCheckOutTouched(false);
                setSubmitAttempted(false);
              }} />
            {submitAttempted && !attendanceDate && <div style={fieldErrorStyle}>Attendance Date is required.</div>}
            {dateAlreadyApproved && <div style={fieldErrorStyle}>Already raised regularization for this date.</div>}
            {dateOutsideWindow && (
              <div style={fieldErrorStyle}>
                Only the last {REGULARIZATION_LOOKBACK_DAYS} days (including today) are selectable.
              </div>
            )}
          </Field>
          {existingPunch && (existingPunch.checkInAt || existingPunch.checkOutAt) && (
            <div style={{ fontSize: 12, color: 'var(--txt-dim)', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 6, padding: '8px 12px' }}>
              On file for this date — Check-in: {formatTime(existingPunch.checkInAt) ?? 'not recorded'}, Check-out: {formatTime(existingPunch.checkOutAt) ?? 'not recorded'}.
              {showCheckIn && showCheckOut
                ? ' The missing side has been pre-filled below; adjust either as needed.'
                : ' Only the missing side needs a correction below.'}
            </div>
          )}
          {showCheckIn && (
            <TimeTextInput label="Corrected Check-In *" value={checkInText} touched={checkInTouched}
              onChange={setCheckInText} onBlur={() => setCheckInTouched(true)} />
          )}
          {showCheckOut && (
            <TimeTextInput label="Corrected Check-Out *" value={checkOutText} touched={checkOutTouched}
              onChange={setCheckOutText} onBlur={() => setCheckOutTouched(true)} />
          )}
          {totalHoursLabel && (
            <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>Total Hours: <strong style={{ color: 'var(--txt)' }}>{totalHoursLabel}</strong></div>
          )}
          <Field label="Assign To *">
            <select style={inputStyle} value={managerUserId} onChange={e => setManagerUserId(e.target.value)}>
              <option value="" disabled>Select HR or Manager…</option>
              {approvers.map(a => (
                <option key={a.userId} value={a.userId}>{a.fullName} — {APPROVER_ROLE_LABELS[a.roleCode] ?? a.roleCode}</option>
              ))}
            </select>
            {submitAttempted && !managerUserId && <div style={fieldErrorStyle}>Assign To is required — select an HR or Manager approver.</div>}
          </Field>
          <Field label="Reason *">
            <textarea
              style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }}
              value={reason}
              onChange={e => setReason(e.target.value)}
              placeholder="e.g. Forgot to punch out after client meeting"
            />
            {submitAttempted && !reason.trim() && <div style={fieldErrorStyle}>Reason is required.</div>}
          </Field>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={submitting || dateAlreadyApproved || dateOutsideWindow} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: submitting || dateAlreadyApproved || dateOutsideWindow ? 'not-allowed' : 'pointer', opacity: submitting || dateAlreadyApproved || dateOutsideWindow ? 0.7 : 1 }}>
              {submitting ? 'Saving…' : editing ? 'Save Changes' : 'Submit Request'}
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
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '16px 18px' }}>
      <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 8 }}>
        {label}
      </div>
      <div style={{ fontSize: 26, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1 }}>
        {value}
      </div>
      <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 6 }}>{hint}</div>
    </div>
  );
}

interface DayInfo {
  iso: string;
  day: number;
  isFuture: boolean;
  isToday: boolean;
  isWeekend: boolean;
  holidayName?: string;
  leaveTypeName?: string;
  /** Only ever set when the request is APPROVED — pending/rejected requests get no calendar mark. */
  regularization?: RegularizationRecord;
  /** Only ever set when the WFH/Partial Day request is APPROVED. */
  attendanceRequest?: AttendanceRequestRecord;
  /** Always empty today — see AttendanceExceptionRecord's doc comment in api/attendance.ts. */
  exception?: AttendanceExceptionRecord;
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
  const primary = primaryDayBadge(info);
  // A penalty annotates a day rather than replacing its status — shown alongside, not instead of.
  return (
    <>
      {primary}
      {info.exception && (
        <span style={{ ...DAY_TAG_STYLE, background: 'rgba(228,55,61,.15)', color: '#E4373D', marginLeft: primary ? 4 : 0 }}>Penalty</span>
      )}
    </>
  );
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
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '14px 18px', borderBottom: '1px solid var(--line)' }}>
        <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>
          {calendarMonthLabel(year, month)}
        </span>
        <div style={{ display: 'flex', gap: 6 }}>
          <button onClick={onPrev} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '5px 9px', cursor: 'pointer', color: 'var(--txt-mut)', display: 'flex' }}>
            <ChevronLeft size={15} />
          </button>
          <button onClick={onNext} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '5px 9px', cursor: 'pointer', color: 'var(--txt-mut)', display: 'flex' }}>
            <ChevronRight size={15} />
          </button>
        </div>
      </div>
      <div style={{ padding: 14 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 6, marginBottom: 6 }}>
          {['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map(d => (
            <div key={d} style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', textAlign: 'center', letterSpacing: '.05em' }}>
              {d}
            </div>
          ))}
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 6 }}>
          {cells.map((day, i) => {
            if (day == null) return <div key={i} />;
            const info = dayInfo(day);
            const selected = selectedDate === info.iso;
            return (
              <button
                key={i}
                onClick={() => !info.isFuture && onSelect(info.iso)}
                disabled={info.isFuture}
                style={{
                  minHeight: 64, borderRadius: 7, padding: '6px 6px', textAlign: 'left',
                  background: selected ? 'rgba(177,17,22,.10)' : 'var(--raised)',
                  border: info.isToday ? '1.5px solid var(--brand)' : selected ? '1px solid var(--brand)' : '1px solid var(--line)',
                  cursor: info.isFuture ? 'default' : 'pointer',
                  opacity: info.isFuture ? 0.45 : 1,
                  display: 'flex', flexDirection: 'column', gap: 2,
                }}
              >
                <span style={{ fontSize: 12, fontWeight: 600, color: info.isWeekend ? 'var(--txt-dim)' : 'var(--txt)' }}>
                  {day}
                </span>
                <DayCellBadge info={info} />
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}

// ─── My attendance (punch card + attendance calendar) ─────────────────────────

// ─── Attendance Request (WFH / Partial Day) submit modal ──────────────────────
// Reuses the regularization approver-assignment pattern (Assign To dropdown sourced from the
// existing GET /attendance/regularization/approvers endpoint) rather than duplicating it.
function AttendanceRequestModal({ presetType, onClose, onSaved, token }: {
  presetType?: AttendanceRequestType;
  onClose: () => void;
  onSaved: (r: AttendanceRequestRecord) => void;
  token: string;
}) {
  const { showToast } = useToast();
  const [requestType, setRequestType] = useState<AttendanceRequestType>(presetType ?? 'WFH');
  const [requestDate, setRequestDate] = useState(todayIsoDate());
  const [partialDayHours, setPartialDayHours] = useState('4');
  const [reason, setReason] = useState('');
  const [managerUserId, setManagerUserId] = useState('');
  const [approvers, setApprovers] = useState<ApproverOption[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    regularizationApi.approvers(token).then(setApprovers).catch(() => { /* dropdown degrades to empty */ });
  }, [token]);

  async function handleSubmit() {
    if (!reason.trim()) { setError('Reason is required'); return; }
    if (requestType === 'PARTIAL_DAY' && (!partialDayHours || Number(partialDayHours) <= 0)) {
      setError('Partial day hours must be greater than zero');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const payload: SubmitAttendanceRequestPayload = {
        requestType,
        requestDate,
        reason: reason.trim(),
        partialDayHours: requestType === 'PARTIAL_DAY' ? Number(partialDayHours) : undefined,
        managerUserId: managerUserId || undefined,
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

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 440 }}>
        <ModalHeader title={presetType === 'WFH' ? 'Work From Home' : presetType === 'PARTIAL_DAY' ? 'Partial Day Request' : 'New Attendance Request'} onClose={onClose} />
        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {error && <div style={{ color: 'var(--risk)', fontSize: 13 }}>{error}</div>}
          {!presetType && (
            <Field label="Request Type">
              <select value={requestType} onChange={(e) => setRequestType(e.target.value as AttendanceRequestType)} style={inputStyle}>
                <option value="WFH">Work From Home</option>
                <option value="PARTIAL_DAY">Partial Day</option>
              </select>
            </Field>
          )}
          <Field label="Date">
            <input type="date" value={requestDate} max={todayIsoDate()} onChange={(e) => setRequestDate(e.target.value)} style={inputStyle} />
          </Field>
          {requestType === 'PARTIAL_DAY' && (
            <Field label="Hours">
              <input type="number" min="0.5" step="0.5" value={partialDayHours} onChange={(e) => setPartialDayHours(e.target.value)} style={inputStyle} />
            </Field>
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
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 6 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button
              onClick={handleSubmit}
              disabled={!reason.trim() || submitting}
              style={{ background: reason.trim() ? 'var(--brand)' : 'var(--raised2)', color: reason.trim() ? '#fff' : 'var(--txt-dim)', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: !reason.trim() || submitting ? 'not-allowed' : 'pointer' }}
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
    setLoading(true);
    const to = todayIsoDate();
    const from = range === 'WEEK' ? isoDaysAgo(6) : isoDaysAgo(29);
    attendanceApi.stats(from, to, token)
      .then(setStats)
      .catch(() => setStats(null))
      .finally(() => setLoading(false));
  }, [range, token]);

  const rowStyle: React.CSSProperties = { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 14, padding: '10px 0' };

  return (
    <div style={{ ...panelStyle, padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
        <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)' }}>Attendance Stats</span>
        <select value={range} onChange={(e) => setRange(e.target.value as StatsRange)} style={{ ...inputStyle, width: 'auto', padding: '4px 8px', fontSize: 12 }}>
          <option value="WEEK">Last Week</option>
          <option value="MONTH">Last 30 Days</option>
        </select>
      </div>
      {loading ? (
        <div style={{ color: 'var(--txt-dim)', fontSize: 13, padding: '12px 0' }}>Loading…</div>
      ) : !stats ? (
        <div style={{ color: 'var(--txt-dim)', fontSize: 13, padding: '12px 0' }}>Stats unavailable right now.</div>
      ) : (
        <>
          <div style={{ display: 'flex', fontSize: 10.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.05em' }}>
            <span style={{ flex: 1 }} />
            <span style={{ width: 90, textAlign: 'right' }}>Avg hrs/day</span>
            <span style={{ width: 110, textAlign: 'right' }}>On-time %</span>
          </div>
          <div style={{ ...rowStyle, borderTop: '1px solid var(--line)' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--txt)', fontWeight: 600 }}>
              <User size={14} style={{ color: 'var(--brand)' }} /> Me
            </span>
            <span style={{ width: 90, textAlign: 'right', fontSize: 14, fontWeight: 700, color: 'var(--txt)' }}>
              {stats.me.avgHoursPerDay != null ? `${stats.me.avgHoursPerDay}h` : dash}
            </span>
            <span style={{ width: 110, textAlign: 'right', fontSize: 14, fontWeight: 700, color: 'var(--txt)' }}>
              {stats.me.onTimeArrivalPercent != null ? `${stats.me.onTimeArrivalPercent}%` : dash}
            </span>
          </div>
          <div style={{ ...rowStyle, borderTop: '1px solid var(--line)' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--txt)', fontWeight: 600 }}>
              <Users size={14} style={{ color: 'var(--txt-dim)' }} /> My Team
            </span>
            <span style={{ width: 90, textAlign: 'right', fontSize: 14, fontWeight: 700, color: 'var(--txt)' }}>
              {stats.team.avgHoursPerDay != null ? `${stats.team.avgHoursPerDay}h` : dash}
            </span>
            <span style={{ width: 110, textAlign: 'right', fontSize: 14, fontWeight: 700, color: 'var(--txt)' }}>
              {stats.team.onTimeArrivalPercent != null ? `${stats.team.onTimeArrivalPercent}%` : dash}
            </span>
          </div>
          {stats.teamSize === 0 && (
            <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>No peers on record under your current manager yet.</div>
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

  // ONEHR-108: every employee is now seeded with an assigned Shift (start+end), so shiftEnd is
  // normally always present — fall back to the fullDayMinHours target only for the edge case of
  // an employee with no Shift assigned at all.
  const shiftMinutes = config?.shiftEnd
    ? (minutesSinceMidnight(`${todayIsoDate()}T${config.shiftEnd}`) ?? 0)
      - (minutesSinceMidnight(`${todayIsoDate()}T${config.shiftStart}`) ?? 0)
    : null;
  const fullDayTargetMinutes = shiftMinutes ?? (config ? config.fullDayMinHours * 60 : null);
  const progressPct = fullDayTargetMinutes && workedMinutesToday != null
    ? Math.min(100, Math.round((workedMinutesToday / fullDayTargetMinutes) * 100))
    : 0;
  const breakUsed = today?.breakUsedMinutes ?? 0;
  const breakBudget = today?.breakBudgetMinutes ?? config?.dailyBreakBudgetMinutes ?? 60;
  const breakPct = breakBudget > 0 ? Math.min(100, Math.round((breakUsed / breakBudget) * 100)) : 0;

  return (
    <div style={{ ...panelStyle, padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 10 }}>
      <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)' }}>Today's Timings</span>
      {config && (
        <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
          {config.shiftEnd
            ? <>Shift {formatTime(`${todayIsoDate()}T${config.shiftStart}`)} – {formatTime(`${todayIsoDate()}T${config.shiftEnd}`)} · grace {config.lateGraceMinutes}m</>
            : <>Shift starts {formatTime(`${todayIsoDate()}T${config.shiftStart}`)} · grace {config.lateGraceMinutes}m</>}
        </div>
      )}
      <div>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: 'var(--txt-dim)', marginBottom: 4 }}>
          <span>{shiftMinutes ? 'Progress toward shift end' : `Progress toward a full day${config ? ` (${config.fullDayMinHours}h)` : ''}`}</span>
          <span>{formatDuration(workedMinutesToday) ?? dash}</span>
        </div>
        <div style={{ height: 8, borderRadius: 4, background: 'var(--raised2)', overflow: 'hidden' }}>
          <div style={{ height: '100%', width: `${progressPct}%`, background: 'var(--brand)', borderRadius: 4, transition: 'width .3s' }} />
        </div>
      </div>
      <div>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: 'var(--txt-dim)', marginBottom: 4 }}>
          <span>Break used</span>
          <span>{breakUsed} / {breakBudget} min</span>
        </div>
        <div style={{ height: 6, borderRadius: 3, background: 'var(--raised2)', overflow: 'hidden' }}>
          <div style={{ height: '100%', width: `${breakPct}%`, background: '#E0A93B', borderRadius: 3, transition: 'width .3s' }} />
        </div>
      </div>
    </div>
  );
}

// ─── Quick Actions ──────────────────────────────────────────────────────────────
function QuickActionsPanel({ token }: { token: string }) {
  const { format, toggle } = useTimeFormat();
  const [modal, setModal] = useState<'WEB_CHECK_IN' | 'WFH' | 'PARTIAL_DAY' | null>(null);
  const { showToast } = useToast();

  const actionStyle: React.CSSProperties = {
    display: 'flex', alignItems: 'center', gap: 9, background: 'var(--raised)', border: '1px solid var(--line2)',
    borderRadius: 8, padding: '9px 12px', fontSize: 13, color: 'var(--txt)', cursor: 'pointer', fontWeight: 600, width: '100%',
    textAlign: 'left' as const,
  };

  return (
    <div style={{ ...panelStyle, padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 8 }}>
      <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 2 }}>Quick Actions</span>
      <button style={actionStyle} onClick={() => setModal('WEB_CHECK_IN')}>
        <Laptop size={15} style={{ color: 'var(--brand)' }} /> Web Check-in
      </button>
      <button style={actionStyle} onClick={() => setModal('WFH')}>
        <Home size={15} style={{ color: 'var(--brand)' }} /> Work From Home
      </button>
      <button style={actionStyle} onClick={() => setModal('PARTIAL_DAY')}>
        <Sun size={15} style={{ color: 'var(--brand)' }} /> Partial Day Request
      </button>
      <Link to="/policies" style={{ ...actionStyle, textDecoration: 'none' }}>
        <FileText size={15} style={{ color: 'var(--brand)' }} /> Attendance Policy
      </Link>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 4, paddingTop: 8, borderTop: '1px solid var(--line)' }}>
        <span style={{ fontSize: 12, color: 'var(--txt-mut)' }}>Time format</span>
        <button
          onClick={toggle}
          style={{ display: 'flex', background: 'var(--raised2)', border: '1px solid var(--line2)', borderRadius: 20, padding: 2, cursor: 'pointer' }}
        >
          {(['12h', '24h'] as const).map((f) => (
            <span
              key={f}
              style={{
                fontSize: 11, fontWeight: 700, padding: '4px 10px', borderRadius: 18,
                background: format === f ? 'var(--brand)' : 'transparent',
                color: format === f ? '#fff' : 'var(--txt-dim)',
              }}
            >
              {f}
            </span>
          ))}
        </button>
      </div>
      {modal === 'WEB_CHECK_IN' && (
        <WebClockInRequestModal onClose={() => setModal(null)} onSubmitted={() => showToast('success', 'Web clock-in request submitted for approval')} />
      )}
      {(modal === 'WFH' || modal === 'PARTIAL_DAY') && (
        <AttendanceRequestModal
          presetType={modal}
          token={token}
          onClose={() => setModal(null)}
          onSaved={() => { /* toast already shown by the modal itself */ }}
        />
      )}
    </div>
  );
}

// ─── Attendance Log: per-day visual timeline ───────────────────────────────────
// A coarse single-segment bar from the day's own checkInAt/checkOutAt (already on the row —
// no extra fetch, no N+1). Fine-grained multi-punch/break visualization stays in the existing
// on-demand PunchHistoryList.
function AttendanceVisualBar({ info }: { info: DayInfo }) {
  if (info.holidayName) {
    return <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>Company holiday — {info.holidayName}</span>;
  }
  if (info.leaveTypeName) {
    return <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>On leave — {info.leaveTypeName}</span>;
  }
  if (info.isWeekend && !info.record && !info.attendanceRequest) {
    return <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>Full day Weekly-off</span>;
  }
  const record = info.record;
  const inMin = record?.checkInAt ? minutesSinceMidnight(record.checkInAt) : null;
  if (inMin == null) {
    return <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>—</span>;
  }
  const outMin = record?.checkOutAt ? minutesSinceMidnight(record.checkOutAt) : null;
  const leftPct = (inMin / 1440) * 100;
  const widthPct = Math.max(1, (((outMin ?? inMin + 20) - inMin) / 1440) * 100);
  return (
    <div style={{ position: 'relative', height: 8, minWidth: 130, background: 'var(--raised2)', borderRadius: 4 }}>
      <div style={{ position: 'absolute', left: `${leftPct}%`, width: `${widthPct}%`, height: '100%', background: outMin ? 'var(--brand)' : '#E0A93B', borderRadius: 4 }} />
    </div>
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
              borderRadius: 7, padding: '6px 12px', fontSize: 11.5, fontWeight: 700, cursor: 'pointer', textTransform: 'uppercase',
            }}
          >
            {m.label}
          </button>
        );
      })}
    </div>
  );
}

export interface MyAttendanceHandle {
  exportMonth: () => void;
}

const MyAttendance = forwardRef<MyAttendanceHandle>(function MyAttendance(_props, ref) {
  const token = useAuthStore((s) => s.token)!;
  const { showToast } = useToast();
  const { formatTime, formatDuration } = useTimeFormat();

  const [today, setToday] = useState<TodayAttendance | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [config, setConfig] = useState<AttendanceConfig | null>(null);

  useEffect(() => {
    attendanceApi.config(token).then(setConfig).catch(() => setConfig(null));
  }, [token]);

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
  const [exceptions, setExceptions] = useState<AttendanceExceptionRecord[]>([]);
  const [selectedDate, setSelectedDate] = useState<string | null>(todayIsoDate());

  // Holidays / leaves / regularizations / attendance requests are fetched once — the calendar
  // filters them per month. Exceptions are ranged (always empty today — see AttendanceService).
  useEffect(() => {
    const exceptionsFrom = isoDaysAgo(365);
    const exceptionsTo = todayIsoDate();
    Promise.all([
      holidaysApi.listForMyLocation(token).catch(() => []),
      leaveApi.listMine(token).catch(() => []),
      regularizationApi.mine(token).catch(() => []),
      attendanceRequestApi.mine(token).catch(() => []),
      attendanceApi.exceptions(exceptionsFrom, exceptionsTo, token).catch(() => []),
    ]).then(([h, l, r, ar, ex]) => {
      setHolidays(h); setLeaves(l); setRegularizations(r); setAttendanceRequests(ar); setExceptions(ex);
    });
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

  const exceptionByDate = useMemo(() => {
    const map = new Map<string, AttendanceExceptionRecord>();
    exceptions.forEach((e) => map.set(e.exceptionDate, e));
    return map;
  }, [exceptions]);

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
      isToday: iso === todayIsoDate(),
      isWeekend,
      holidayName: holidayByDate.get(iso),
      leaveTypeName: leaveByDate.get(iso),
      regularization: regularizationByDate.get(iso),
      attendanceRequest: attendanceRequestByDate.get(iso),
      exception: exceptionByDate.get(iso),
      record: recordByDate.get(iso),
    };
  }, [viewYear, viewMonth, config, holidayByDate, leaveByDate, regularizationByDate, attendanceRequestByDate, exceptionByDate, recordByDate]);

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

  // Every non-future day of the month, newest first — reuses getDayInfo exactly as
  // MonthCalendar does, so weekends/leaves/holidays appear as rows even with no punch.
  const logRows = useMemo(() => {
    const total = daysInMonth(viewYear, viewMonth);
    const todayIso = todayIsoDate();
    const rows: DayInfo[] = [];
    for (let d = 1; d <= total; d++) {
      const info = getDayInfo(d);
      if (info.iso <= todayIso) rows.push(info);
    }
    return rows.reverse();
  }, [viewYear, viewMonth, getDayInfo]);

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

  const openSince = today?.canCheckOut ? today.record?.checkInAt ?? null : null;

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

  async function punch(kind: 'in' | 'out') {
    setSubmitting(true);
    try {
      const record = kind === 'in'
        ? await attendanceApi.checkIn(token)
        : await attendanceApi.checkOut(token);

      // Re-read /today so canCheckIn/canCheckOut always come from the server, never inferred.
      const [refreshed] = await Promise.all([
        attendanceApi.today(token),
        refreshMonth(),
      ]);
      serverOffsetMs.current = wallClockMs(refreshed.serverNow) - Date.now();
      setToday(refreshed);

      const at = formatTime(kind === 'in' ? record.checkInAt : record.checkOutAt);
      showToast('success', `Checked ${kind} ${at ? `at ${at}` : 'successfully'}`);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : `Check ${kind} failed`);
    } finally {
      setSubmitting(false);
    }
  }

  const primaryButtonStyle = (disabled: boolean): React.CSSProperties => ({
    display: 'flex', alignItems: 'center', gap: 8,
    background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8,
    padding: '12px 22px', fontSize: 14, fontWeight: 600,
    cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? 0.7 : 1,
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 22 }}>
      {/* Attendance Stats / Today's Timings / Quick Actions */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 16 }}>
        <AttendanceStatsPanel token={token} />
        <TodaysTimingsPanel today={today} config={config} workedMinutesToday={workedMinutesToday} />
        <QuickActionsPanel token={token} />
      </div>

      {/* Monthly attendance calendar */}
      <div>
        <SectionHeading title="My attendance calendar" hint="Present days, worked hours, and leave/holidays for the selected month." />

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 10, marginBottom: 16 }}>
          <MonthStatTile label="Present Days" value={monthLoading ? '—' : String(presentDaysCount)} hint="Selected month" />
          <MonthStatTile label="Worked Hours" value={monthLoading ? '—' : (formatDuration(workedMinutesTotal) ?? '0m')} hint="Selected month" />
          <MonthStatTile label="Leave / Holidays" value={String(leaveHolidayCount)} hint="Selected month" />
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 16, alignItems: 'start' }}>
          <MonthCalendar
            year={viewYear}
            month={viewMonth}
            dayInfo={getDayInfo}
            selectedDate={selectedDate}
            onSelect={setSelectedDate}
            onPrev={goToPrevMonth}
            onNext={goToNextMonth}
          />

          <div style={{ ...panelStyle, padding: '18px 20px' }}>
            {!selectedInfo ? (
              <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>Pick a day on the calendar to see its details.</div>
            ) : selectedInfo.isToday ? (
              // Today's workday — merged from the old standalone punch card, now living
              // in the calendar's side panel with the exact same today/punch() state.
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)' }}>Today's workday</div>
                {loading ? (
                  <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>Loading…</div>
                ) : !today ? (
                  <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>Attendance unavailable right now.</div>
                ) : (
                  <>
                    <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
                      <div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                          <LogIn size={11} /> Check In
                        </div>
                        <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(today.record?.checkInAt ?? null) ?? dash}</div>
                      </div>
                      <div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                          <LogOut size={11} /> Check Out
                        </div>
                        <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(today.record?.checkOutAt ?? null) ?? dash}</div>
                      </div>
                    </div>
                    <div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                        <Clock size={11} /> {today.canCheckOut ? 'Elapsed' : 'Worked Today'}
                      </div>
                      <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--txt)' }}>
                        {(today.canCheckOut ? elapsed : formatDuration(today.record?.workedMinutes ?? null)) ?? dash}
                      </div>
                    </div>
                    {today.record?.status && <StatusPill status={today.record.status} />}
                    {today.record?.status === 'LATE' && (today.record.lateByMinutes ?? 0) > 0 && (
                      <div style={{ fontSize: 12, color: '#E0A93B' }}>
                        Checked in {formatDuration(today.record.lateByMinutes)} past the grace period.
                      </div>
                    )}
                    {/* The button is driven only by the server's canCheckIn / canCheckOut flags. */}
                    <div>
                      {today.canCheckIn && (
                        <button onClick={() => punch('in')} disabled={submitting} style={primaryButtonStyle(submitting)}>
                          <LogIn size={15} /> {submitting ? 'Checking in…' : 'Check In'}
                        </button>
                      )}
                      {today.canCheckOut && (
                        <button onClick={() => punch('out')} disabled={submitting} style={primaryButtonStyle(submitting)}>
                          <LogOut size={15} /> {submitting ? 'Checking out…' : 'Check Out'}
                        </button>
                      )}
                      {!today.canCheckIn && !today.canCheckOut && (
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--ok)', fontSize: 13, fontWeight: 600 }}>
                          <CheckCircle2 size={16} /> Day complete
                        </div>
                      )}
                    </div>
                    <PunchHistoryList date={selectedInfo.iso} token={token} />
                  </>
                )}
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)' }}>{formatDay(selectedInfo.iso)}</div>
                {selectedInfo.holidayName ? (
                  <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>Company holiday — {selectedInfo.holidayName}</div>
                ) : selectedInfo.leaveTypeName ? (
                  <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>On leave — {selectedInfo.leaveTypeName}</div>
                ) : selectedInfo.regularization ? (
                  <>
                    <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
                      <div>
                        <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Requested In</div>
                        <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(selectedInfo.regularization.requestedCheckIn) ?? dash}</div>
                      </div>
                      <div>
                        <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Requested Out</div>
                        <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(selectedInfo.regularization.requestedCheckOut) ?? dash}</div>
                      </div>
                    </div>
                    <div>
                      <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Total Hours</div>
                      <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--txt)' }}>{formatDuration(selectedInfo.regularization.totalMinutes) ?? dash}</div>
                    </div>
                    <div>
                      <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Status</div>
                      <RegularizationStatusPill status={selectedInfo.regularization.status} />
                    </div>
                    <div>
                      <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Approved By</div>
                      <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--txt)' }}>{selectedInfo.regularization.reviewedByName ?? dash}</div>
                    </div>
                    <div>
                      <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>Comments</div>
                      <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>{selectedInfo.regularization.reviewComment ?? dash}</div>
                    </div>
                  </>
                ) : selectedInfo.record ? (
                  <>
                    <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
                      <div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                          <LogIn size={11} /> Check In
                        </div>
                        <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(selectedInfo.record.checkInAt) ?? dash}</div>
                      </div>
                      <div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                          <LogOut size={11} /> Check Out
                        </div>
                        <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--txt)' }}>{formatTime(selectedInfo.record.checkOutAt) ?? dash}</div>
                      </div>
                    </div>
                    <div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10.5, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>
                        <Clock size={11} /> Hours
                      </div>
                      <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--txt)' }}>{formatDuration(selectedInfo.record.workedMinutes) ?? dash}</div>
                    </div>
                    <StatusPill status={selectedInfo.record.status} />
                    <PunchHistoryList date={selectedInfo.iso} token={token} />
                  </>
                ) : selectedInfo.isWeekend ? (
                  <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>Weekend — no attendance expected.</div>
                ) : (
                  <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>No attendance recorded for this day.</div>
                )}
              </div>
            )}
          </div>
        </div>

        {/* Attendance Log — every day of the month (not just punched ones), with visual
            timeline/presence bar, status badges, and month shortcuts. */}
        <div style={{ marginTop: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
            <SectionHeading title={`Attendance Log — ${calendarMonthLabel(viewYear, viewMonth)}`} />
            <MonthShortcuts viewYear={viewYear} viewMonth={viewMonth} onSelect={goToMonth} />
          </div>
          <div style={panelStyle}>
            {monthLoading ? (
              <div style={{ padding: 32, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>Loading…</div>
            ) : logRows.length === 0 ? (
              <div style={{ padding: 32, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>No days to show for this month.</div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead>
                    <tr>{['Date', 'Attendance Visual', 'Check In', 'Check Out', 'Worked', 'Status', 'Details'].map((h) => <th key={h} style={thStyle}>{h}</th>)}</tr>
                  </thead>
                  <tbody>
                    {logRows.map((info) => (
                      <tr key={info.iso}>
                        <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600, whiteSpace: 'nowrap' }}>{formatDay(info.iso)}</td>
                        <td style={tdStyle}><AttendanceVisualBar info={info} /></td>
                        <td style={tdStyle}>{formatTime(info.record?.checkInAt ?? null) ?? dash}</td>
                        <td style={tdStyle}>{formatTime(info.record?.checkOutAt ?? null) ?? dash}</td>
                        <td style={tdStyle}>{formatDuration(info.record?.workedMinutes ?? null) ?? dash}</td>
                        <td style={tdStyle}><DayCellBadge info={info} /></td>
                        <td style={tdStyle}>
                          <button
                            onClick={() => setSelectedDate(info.iso)}
                            style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, padding: '4px 10px', fontSize: 11, color: 'var(--txt)', cursor: 'pointer', fontWeight: 600 }}
                          >
                            <Eye size={11} /> View
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
});

// ─── Regularization (request + my requests + pending approvals) ───────────────

/** Reviewer column: current approver while pending, who decided it once resolved. */
function ReviewerCell({ r }: { r: RegularizationRecord }) {
  if (r.status === 'PENDING') {
    return (
      <>
        <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>Current Approver</div>
        {r.assignedApproverName ?? dash}
      </>
    );
  }
  if (r.status === 'PARTIALLY_APPROVED') {
    return (
      <>
        <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>Manager Approved — Awaiting HR/Super Admin</div>
        {r.approvedByName ?? dash}
      </>
    );
  }
  return (
    <>
      <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{r.status === 'APPROVED' ? 'Approved By' : 'Rejected By'}</div>
      {r.reviewedByName ?? dash}
    </>
  );
}

function MonthGroupHeading({ monthKey }: { monthKey: string }) {
  return (
    <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--txt-dim)', margin: '14px 0 6px', textTransform: 'uppercase', letterSpacing: '.06em' }}>
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
              borderRadius: 7, padding: '7px 14px', fontSize: 12.5, fontWeight: 600, cursor: 'pointer',
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
    <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: 'var(--txt-dim)' }}>
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
          <Link to="/attendance/regularization/all" style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--raised)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, textDecoration: 'none' }}>
            <ShieldCheck size={14} /> View All & Audit Trail
          </Link>
        )}
      </div>

      {/* My Regularization Requests — month filter only, grouped by month within that filter */}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
          <h3 style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', margin: 0 }}>My Requests</h3>
          <MonthFilter month={selectedMonth} onChange={setSelectedMonth} />
        </div>
        {loading ? (
          <div style={{ ...panelStyle, padding: 32, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>Loading…</div>
        ) : myRequests.length === 0 ? (
          <div style={{ ...panelStyle, padding: 32, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>No requests submitted yet.</div>
        ) : filteredMyRequests.length === 0 ? (
          <div style={{ ...panelStyle, padding: 32, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>
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
                                style={{ display: 'flex', alignItems: 'center', gap: 4, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, padding: '5px 9px', fontSize: 12, color: 'var(--txt-mut)', cursor: 'pointer' }}
                              >
                                <Pencil size={12} /> Edit
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
            <h3 style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', margin: 0 }}>Pending Approvals</h3>
            <FilterTabs value={approvalStatusFilter} options={STATUS_FILTER_TABS} onChange={setApprovalStatusFilter} />
          </div>
          {selectedIds.size > 0 && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8, padding: '8px 14px' }}>
              <span style={{ fontSize: 13, color: 'var(--txt-mut)', fontWeight: 600 }}>{selectedIds.size} selected</span>
              <button onClick={() => setBulkConfirm('APPROVE')} style={{ background: 'rgba(47,182,124,.1)', border: '1px solid rgba(47,182,124,.25)', borderRadius: 5, padding: '6px 12px', fontSize: 12, fontWeight: 600, color: '#2FB67C', cursor: 'pointer' }}>Bulk Approve</button>
              <button onClick={() => setBulkConfirm('REJECT')} style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 5, padding: '6px 12px', fontSize: 12, fontWeight: 600, color: '#E4373D', cursor: 'pointer' }}>Bulk Reject</button>
              <button onClick={() => setSelectedIds(new Set())} style={{ background: 'none', border: 'none', color: 'var(--txt-dim)', fontSize: 12, cursor: 'pointer', marginLeft: 'auto' }}>Clear selection</button>
            </div>
          )}
          {pending.length === 0 ? (
            <div style={{ ...panelStyle, padding: 32, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>No requests to review yet.</div>
          ) : filteredPending.length === 0 ? (
            <div style={{ ...panelStyle, padding: 32, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>
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
                              <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{r.employeeEmail}</div>
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
                                  <button onClick={() => setApproving(r)} style={{ background: 'rgba(47,182,124,.1)', border: '1px solid rgba(47,182,124,.25)', borderRadius: 5, padding: '5px 10px', fontSize: 12, color: '#2FB67C', cursor: 'pointer' }}>Approve</button>
                                  <button onClick={() => setRejecting(r)} style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 5, padding: '5px 10px', fontSize: 12, color: '#E4373D', cursor: 'pointer' }}>Reject</button>
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

  function typeLabel(t: AttendanceRequestType) { return t === 'WFH' ? 'Work From Home' : 'Partial Day'; }

  function renderTable(rows: AttendanceRequestRecord[], showActions: boolean) {
    return (
      <div style={panelStyle}>
        {rows.length === 0 ? (
          <div style={{ padding: 32, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>Nothing to show.</div>
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
                    <td style={tdStyle}>{typeLabel(r.requestType)}</td>
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
          {loading ? <div style={{ color: 'var(--txt-dim)', padding: 20 }}>Loading…</div> : renderTable(pending, true)}
        </div>
      )}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
          <SectionHeading title="My Requests" />
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <MonthFilter month={month} onChange={setMonth} />
            <button
              onClick={() => setShowRequest(true)}
              style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '8px 14px', fontSize: 12.5, fontWeight: 600, cursor: 'pointer' }}
            >
              <CalendarPlus size={13} /> New Request
            </button>
          </div>
        </div>
        {loading ? <div style={{ color: 'var(--txt-dim)', padding: 20 }}>Loading…</div> : renderTable(filteredMyRequests, false)}
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
                {typeLabel(acting.request.requestType)} · {formatDay(acting.request.requestDate)}
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
          <div style={{ padding: 32, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>Nothing to show.</div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>{['Date', 'Start', 'End', 'Duration', 'Reason', 'Approver', 'Status', ...(showActions ? ['Actions'] : [])].map((h) => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id}>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{formatDay(r.workDate)}</td>
                    <td style={tdStyle}>{formatTime(r.requestedStart) ?? dash}</td>
                    <td style={tdStyle}>{formatTime(r.requestedEnd) ?? dash}</td>
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
          {loading ? <div style={{ color: 'var(--txt-dim)', padding: 20 }}>Loading…</div> : renderTable(pending, true)}
        </div>
      )}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10, marginBottom: 10 }}>
          <SectionHeading title="My Requests" />
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <MonthFilter month={month} onChange={setMonth} />
            <button
              onClick={() => setShowRequest(true)}
              style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '8px 14px', fontSize: 12.5, fontWeight: 600, cursor: 'pointer' }}
            >
              <CalendarPlus size={13} /> New Request
            </button>
          </div>
        </div>
        {loading ? <div style={{ color: 'var(--txt-dim)', padding: 20 }}>Loading…</div> : renderTable(filteredMyRequests, false)}
      </div>
      {showRequest && (
        <OvertimeRequestModal
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

// ─── Overtime submit modal — reuses the same TimeTextInput 12h masked input as regularization ──
function OvertimeRequestModal({ onClose, onSaved, token }: {
  onClose: () => void;
  onSaved: (r: OvertimeRequestRecord) => void;
  token: string;
}) {
  const { showToast } = useToast();
  const [workDate, setWorkDate] = useState(todayIsoDate());
  const [startText, setStartText] = useState('');
  const [endText, setEndText] = useState('');
  const [startTouched, setStartTouched] = useState(false);
  const [endTouched, setEndTouched] = useState(false);
  const [reason, setReason] = useState('');
  const [managerUserId, setManagerUserId] = useState('');
  const [approvers, setApprovers] = useState<ApproverOption[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    regularizationApi.approvers(token).then(setApprovers).catch(() => { /* dropdown degrades to empty */ });
  }, [token]);

  const startValue = parseTimeText(startText);
  const endValue = parseTimeText(endText);

  async function handleSubmit() {
    setStartTouched(true); setEndTouched(true);
    if (!startValue || !isTimeValueComplete(startValue) || !endValue || !isTimeValueComplete(endValue)) {
      setError('Enter both a requested start and end time'); return;
    }
    if (!reason.trim()) { setError('Reason is required'); return; }
    const requestedStart = isoFromTimeValue(workDate, startValue);
    const requestedEnd = isoFromTimeValue(workDate, endValue);
    if (!requestedStart || !requestedEnd) { setError('Invalid time'); return; }

    setSubmitting(true);
    setError(null);
    try {
      const created = await overtimeRequestApi.submit({
        workDate, requestedStart, requestedEnd, reason: reason.trim(), managerUserId: managerUserId || undefined,
      }, token);
      showToast('success', 'Overtime request submitted for approval');
      onSaved(created);
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
      <div style={{ ...modalStyle, maxWidth: 440 }}>
        <ModalHeader title="Request Overtime" onClose={onClose} />
        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {error && <div style={{ color: 'var(--risk)', fontSize: 13 }}>{error}</div>}
          <Field label="Work Date">
            <input type="date" value={workDate} max={todayIsoDate()} onChange={(e) => setWorkDate(e.target.value)} style={inputStyle} />
          </Field>
          <div style={{ display: 'flex', gap: 12 }}>
            <div style={{ flex: 1 }}>
              <TimeTextInput label="Requested Start" value={startText} touched={startTouched} onChange={setStartText} onBlur={() => setStartTouched(true)} />
            </div>
            <div style={{ flex: 1 }}>
              <TimeTextInput label="Requested End" value={endText} touched={endTouched} onChange={setEndText} onBlur={() => setEndTouched(true)} />
            </div>
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
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 6 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button
              onClick={handleSubmit}
              disabled={submitting}
              style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer' }}
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

type RequestsTab = 'REGULARIZATION' | 'ATTENDANCE_REQUESTS' | 'OVERTIME';

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
  const [requestsTab, setRequestsTab] = useState<RequestsTab>('REGULARIZATION');
  const pendingOpenRequest = useRef(false);

  // The header's "Request Regularization" button may be clicked while a different sub-tab is
  // active (RegularizationSection unmounted, ref not yet attached) — switch tabs first, then
  // open the modal once the ref attaches (refs commit before this effect runs).
  useEffect(() => {
    if (requestsTab === 'REGULARIZATION' && pendingOpenRequest.current) {
      pendingOpenRequest.current = false;
      regularizationRef.current?.openNewRequest();
    }
  }, [requestsTab]);

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
              if (requestsTab === 'REGULARIZATION') {
                regularizationRef.current?.openNewRequest();
              } else {
                pendingOpenRequest.current = true;
                setRequestsTab('REGULARIZATION');
              }
            }}
            style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}
          >
            <CalendarPlus size={14} /> Request Regularization
          </button>
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 30 }}>
        <MyAttendance ref={myAttendanceRef} />

        <div>
          <SectionHeading title="Logs & Requests" />
          <div style={{ marginBottom: 16 }}>
            <FilterTabs
              value={requestsTab}
              onChange={setRequestsTab}
              options={[
                { value: 'REGULARIZATION', label: 'Regularization' },
                { value: 'ATTENDANCE_REQUESTS', label: 'WFH & Partial Day' },
                { value: 'OVERTIME', label: 'Overtime Requests' },
              ]}
            />
          </div>
          {requestsTab === 'REGULARIZATION' && (
            <RegularizationSection ref={regularizationRef} token={token} canApprove={canApprove} isSuperAdmin={role === 'Super Admin'} isManager={role === 'Manager'} />
          )}
          {requestsTab === 'ATTENDANCE_REQUESTS' && (
            <AttendanceRequestsSection token={token} canApprove={canApprove} />
          )}
          {requestsTab === 'OVERTIME' && (
            <OvertimeRequestsSection token={token} canApprove={canApprove} />
          )}
        </div>

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

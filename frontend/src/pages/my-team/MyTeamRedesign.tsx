/**
 * My Team — redesigned presentation layer (UI only).
 *
 * Purely presentational building blocks shared by the production page (MyTeamPage.tsx) and the
 * unauthenticated mock preview (MyTeamPreviewPage.tsx), so the preview renders the real redesigned
 * UI rather than a lookalike. Nothing in here fetches data, reads the auth store, or decides who
 * may see what: every number, row and action arrives through props from the caller, which keeps
 * using the existing APIs / role rules unchanged.
 *
 * The day-status precedence in `classifyDayCell` mirrors MyTeamPage's original `classifyDay`
 * exactly (not-joined → holiday → weekly-off → leave → WFH → record → upcoming/missing); it only
 * adds *display* detail (what the attendance record says) on top of that classification.
 */
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router-dom';
import {
  ArrowRight, BarChart3, CalendarCheck, CalendarDays, Check, CheckCircle2, ChevronLeft, ChevronRight, ClipboardList,
  Clock, Contrast, HelpCircle, Laptop, LogOut, Moon, PartyPopper, Plane, Search, TreePalm, Users, UserCheck, X, Zap,
  type LucideIcon,
} from 'lucide-react';
import { NAV, isNavItemDisabled, type Role } from '../../lib/nav.config';
import { StatusBadge, inactiveDimStyle } from '../../components/EmployeeStatus';
import type { AttendanceRecord } from '../../api/attendance';
import type { LeaveRequestRecord } from '../../api/leave';
import './myTeamRedesign.css';

/* ══ Types ══ */
export type LiveStatus = 'IN' | 'OUT' | 'NOT_IN_YET' | 'LEAVE';

export interface MemberRow {
  userId: string;
  fullName: string;
  designation: string | null;
  department: string | null;
  active: boolean;
  /** ISO yyyy-MM-dd — calendar days before this are blank (same rule as before the redesign). */
  joiningDate?: string | null;
  /** Today's live status, as already derived by the caller from existing attendance/leave data. */
  status: LiveStatus;
}

export type AvatarRenderer = (userId: string, name: string, size: number) => ReactNode;

/* ══ Day classification ══ */
export type DayKind =
  | 'present' | 'late' | 'half-day' | 'absent' | 'missing-checkout' | 'leave' | 'wfh'
  | 'weekly-off' | 'holiday' | 'missing' | 'upcoming' | 'not-joined';

type CellRecord = Pick<AttendanceRecord, 'employeeUserId' | 'workDate' | 'checkInAt' | 'checkOutAt' | 'workedMinutes' | 'status' | 'lateByMinutes' | 'source' | 'penalized'>;
type CellLeave = Pick<LeaveRequestRecord, 'employeeUserId' | 'startDate' | 'endDate' | 'leaveTypeName' | 'halfDay'>;

export interface DayContext {
  today: string;
  /** holidayDate → holiday name */
  holidays: Map<string, string>;
  leaveByUser: Map<string, CellLeave[]>;
  /** `${userId}:${isoDate}` for approved WFH days */
  wfhKeys: Set<string>;
  /** `${userId}:${isoDate}` → attendance record */
  records: Map<string, CellRecord>;
}

export function buildDayContext(input: {
  today: string;
  holidays: { holidayDate: string; holidayName: string }[];
  leaves: CellLeave[];
  wfhKeys: Set<string>;
  attendance: CellRecord[];
}): DayContext {
  const leaveByUser = new Map<string, CellLeave[]>();
  input.leaves.forEach(l => {
    const arr = leaveByUser.get(l.employeeUserId) ?? [];
    arr.push(l);
    leaveByUser.set(l.employeeUserId, arr);
  });
  return {
    today: input.today,
    holidays: new Map(input.holidays.map(h => [h.holidayDate, h.holidayName])),
    leaveByUser,
    wfhKeys: input.wfhKeys,
    records: new Map(input.attendance.map(r => [`${r.employeeUserId}:${r.workDate}`, r])),
  };
}

export interface DayCell { kind: DayKind; title: string; lines: string[]; flag?: string }

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

function pad(n: number) { return String(n).padStart(2, '0'); }
export function isoOf(year: number, month: number, day: number) { return `${year}-${pad(month + 1)}-${pad(day)}`; }
export function daysIn(year: number, month: number) { return new Date(year, month + 1, 0).getDate(); }
function longDate(iso: string) {
  return new Date(iso + 'T00:00:00').toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}
// Backend LocalDateTime strings are naive wall-clock digits in the record's own zone — read them
// back verbatim (same approach as MyTeamPage's fmtTime), never re-project into the viewer's zone.
function clock(iso?: string | null) {
  if (!iso) return null;
  const d = new Date(iso + 'Z');
  if (isNaN(d.getTime())) return null;
  return d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true, timeZone: 'UTC' });
}
function hm(mins: number) { return `${Math.floor(mins / 60)}h ${pad(mins % 60)}m`; }

const KIND_LABEL: Record<DayKind, string> = {
  present: 'Present', late: 'Late', 'half-day': 'Half day', absent: 'Absent', 'missing-checkout': 'Missing check-out',
  leave: 'On leave', wfh: 'WFH / On duty', 'weekly-off': 'Weekly off', holiday: 'Holiday',
  missing: 'No attendance record', upcoming: 'No attendance yet', 'not-joined': 'Not yet joined',
};

function recordLines(r: CellRecord): string[] {
  const out: string[] = [];
  const inT = clock(r.checkInAt), outT = clock(r.checkOutAt);
  if (inT) out.push(`In: ${inT}`);
  if (outT) out.push(`Out: ${outT}`);
  if (r.workedMinutes != null && r.workedMinutes > 0) out.push(`Worked: ${hm(r.workedMinutes)}`);
  if (r.lateByMinutes != null && r.lateByMinutes > 0) out.push(`Late by ${r.lateByMinutes} min`);
  if (r.source === 'WEB_REMOTE') out.push('Web clock-in (remote)');
  if (r.source === 'REGULARIZATION') out.push('Regularized');
  return out;
}

export function classifyDayCell(ctx: DayContext, userId: string, joiningDate: string | null | undefined, iso: string, dow: number): DayCell {
  const date = longDate(iso);
  const record = ctx.records.get(`${userId}:${iso}`);
  const extra = record ? recordLines(record) : [];
  const flag = record?.penalized ? 'Penalized' : undefined;

  if (joiningDate && iso < joiningDate) return { kind: 'not-joined', title: KIND_LABEL['not-joined'], lines: [date] };

  const holiday = ctx.holidays.get(iso);
  if (holiday !== undefined) {
    return record?.checkInAt
      ? { kind: 'holiday', title: `Holiday: ${holiday}`, lines: [date, 'Worked on holiday', ...extra], flag }
      : { kind: 'holiday', title: `Holiday: ${holiday}`, lines: [date] };
  }
  if (dow === 0 || dow === 6) {
    return record?.checkInAt
      ? { kind: 'weekly-off', title: KIND_LABEL['weekly-off'], lines: [date, 'Worked on weekly off', ...extra], flag }
      : { kind: 'weekly-off', title: KIND_LABEL['weekly-off'], lines: [date] };
  }
  const leave = (ctx.leaveByUser.get(userId) ?? []).find(l => iso >= l.startDate && iso <= l.endDate);
  if (leave) {
    return { kind: 'leave', title: `On leave: ${leave.leaveTypeName}`, lines: [date, ...(leave.halfDay ? ['Half-day leave'] : [])] };
  }
  if (ctx.wfhKeys.has(`${userId}:${iso}`)) return { kind: 'wfh', title: KIND_LABEL.wfh, lines: [date, ...extra], flag };

  if (record) {
    const kind: DayKind =
      record.status === 'LATE' ? 'late'
      : record.status === 'HALF_DAY' ? 'half-day'
      : record.status === 'ABSENT' ? 'absent'
      : record.status === 'MISSING_CHECKOUT' ? 'missing-checkout'
      : record.status === 'ON_LEAVE' ? 'leave'
      : 'present';
    return { kind, title: KIND_LABEL[kind], lines: [date, ...extra], flag };
  }
  if (iso >= ctx.today) return { kind: 'upcoming', title: KIND_LABEL.upcoming, lines: [date] };
  return { kind: 'missing', title: KIND_LABEL.missing, lines: [date] };
}

/* ══ Monthly summary (counts of the cells the calendar shows — no separate rules) ══ */
export interface MonthSummary { present: number; late: number; halfDay: number; absent: number }

export function summarizeMonth(members: MemberRow[], viewDate: Date, ctx: DayContext): MonthSummary {
  const y = viewDate.getFullYear(), m = viewDate.getMonth();
  const total = daysIn(y, m);
  const s: MonthSummary = { present: 0, late: 0, halfDay: 0, absent: 0 };
  for (const mem of members) {
    for (let d = 1; d <= total; d++) {
      const { kind } = classifyDayCell(ctx, mem.userId, mem.joiningDate, isoOf(y, m, d), new Date(y, m, d).getDay());
      if (kind === 'present') s.present++;
      else if (kind === 'late') s.late++;
      else if (kind === 'half-day') s.halfDay++;
      else if (kind === 'absent' || kind === 'missing') s.absent++;
    }
  }
  return s;
}

/* ══ Small shared bits ══ */
const LIVE: Record<LiveStatus, { label: string; color: string }> = {
  IN: { label: 'In', color: 'var(--ok)' },
  OUT: { label: 'Out', color: 'var(--risk)' },
  NOT_IN_YET: { label: 'Not in yet', color: 'var(--info)' },
  LEAVE: { label: 'On leave', color: '#818CF8' },
};

export function LiveStatusPill({ status }: { status: LiveStatus }) {
  const s = LIVE[status];
  return (
    <span className="mt-pill" style={{ color: s.color, background: `color-mix(in srgb, ${s.color} 14%, transparent)` }}>
      <span className="mt-pill-dot" />{s.label}
    </span>
  );
}

export function Pill({ children, tone = 'tag' }: { children: ReactNode; tone?: 'tag' | 'neutral' | 'ok' | 'info' | 'warn' | 'leave' }) {
  const cls = tone === 'tag' ? 'mt-pill-tag' : tone === 'neutral' ? 'mt-pill-tag-neutral' : tone === 'ok' ? 'mt-pill-tag-ok' : tone === 'info' ? 'mt-pill-tag-info' : '';
  const color = tone === 'warn' ? 'var(--warn)' : tone === 'leave' ? '#818CF8' : undefined;
  return <span className={`mt-pill ${cls}`} style={color ? { color, background: `color-mix(in srgb, ${color} 15%, transparent)` } : undefined}>{children}</span>;
}

/** "FULL_TIME" → "Full time" (same underscore handling DirectoryPage uses, just title-cased). */
export function prettyEmploymentType(t?: string | null): string | null {
  if (!t) return null;
  const s = t.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/* ══ Hero ══ */
export interface TeamMetrics { total: number; checkedIn: number; onLeave: number; notIn: number; loading?: boolean }

export function MyTeamHero({ userName, subtitle, metrics, memberLabel }: {
  userName?: string | null; subtitle: string; metrics: TeamMetrics; memberLabel: string;
}) {
  const v = (n: number) => (metrics.loading ? '…' : n);
  const items = [
    { icon: <Users size={16} />, color: 'var(--brand-bright)', value: v(metrics.total), label: memberLabel },
    { icon: <UserCheck size={16} />, color: 'var(--ok)', value: v(metrics.checkedIn), label: 'Checked in today' },
    { icon: <Plane size={16} />, color: '#818CF8', value: v(metrics.onLeave), label: 'On leave' },
    { icon: <Clock size={16} />, color: 'var(--info)', value: v(metrics.notIn), label: 'Not in yet' },
  ];
  return (
    <section className="mt-hero" aria-label="My Team overview">
      <div style={{ minWidth: 0 }}>
        <p className="mt-hero-hello">Hello <strong>{userName || 'there'}</strong> <span aria-hidden>👋</span></p>
        <h1 className="mt-hero-title">My Team</h1>
        <p className="mt-hero-sub">{subtitle}</p>
      </div>
      <div className="mt-strip" role="list">
        {items.map(it => (
          <div className="mt-metric" role="listitem" key={it.label} tabIndex={0}>
            <span className="mt-metric-ico" style={{ color: it.color, background: `color-mix(in srgb, ${it.color} 14%, transparent)` }}>{it.icon}</span>
            <div>
              <div className="mt-metric-val">{it.value}</div>
              <div className="mt-metric-lbl">{it.label}</div>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

/* ══ Card 1 — Today's team status ══ */
export function TeamStatusCard({ metrics, memberLabel }: { metrics: TeamMetrics; memberLabel: string }) {
  const { total, checkedIn, onLeave, notIn, loading } = metrics;
  const R = 46, C = 2 * Math.PI * R;
  const segs = [
    { n: checkedIn, color: 'var(--ok)', label: 'Checked in' },
    { n: onLeave, color: '#818CF8', label: 'On leave' },
    { n: notIn, color: 'var(--info)', label: 'Not in yet' },
  ];
  const sum = segs.reduce((a, s) => a + s.n, 0);
  let offset = 0;
  return (
    <section className="mt-card" aria-label="Today's team status">
      <div className="mt-cardhead">
        <span className="mt-iconchip"><CalendarCheck size={15} /></span>
        <div>
          <h2 className="mt-card-title">Today's Team Status</h2>
          <div className="mt-card-sub">Live roster for today</div>
        </div>
      </div>
      <div className="mt-status-body">
        <div className="mt-donut" role="img" aria-label={`${checkedIn} of ${total} ${memberLabel.toLowerCase()} checked in`}>
          <svg width="112" height="112" viewBox="0 0 112 112">
            <circle cx="56" cy="56" r={R} fill="none" stroke="var(--raised2)" strokeWidth="11" />
            {!loading && sum > 0 && segs.map(s => {
              if (s.n === 0) return null;
              const len = (s.n / sum) * C;
              const el = (
                <circle key={s.label} cx="56" cy="56" r={R} fill="none" stroke={s.color} strokeWidth="11"
                  strokeDasharray={`${Math.max(len - (segs.filter(x => x.n > 0).length > 1 ? 2 : 0), 0.1)} ${C}`}
                  strokeDashoffset={-offset} />
              );
              offset += len;
              return el;
            })}
          </svg>
          <div className="mt-donut-center">
            <div className="mt-donut-num">{loading ? '…' : checkedIn}<span style={{ fontSize: 13, color: 'var(--txt-dim)', fontWeight: 700 }}>/{loading ? '…' : total}</span></div>
            <div className="mt-donut-cap">checked in</div>
          </div>
        </div>
        <div className="mt-legend">
          {segs.map(s => (
            <div className="mt-legend-row" key={s.label}>
              <span className="mt-dot" style={{ background: s.color }} />{s.label}<b>{loading ? '…' : s.n}</b>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

/* ══ Card 2 — Attendance summary ══ */
export function AttendanceSummaryCard({ summary, monthLabel, loading }: { summary: MonthSummary; monthLabel: string; loading?: boolean }) {
  const cells = [
    { n: summary.present, label: 'Present Days', kind: 'present' as DayKind },
    { n: summary.late, label: 'Late Arrivals', kind: 'late' as DayKind },
    { n: summary.halfDay, label: 'Half Days', kind: 'half-day' as DayKind },
    { n: summary.absent, label: 'Absent Days', kind: 'absent' as DayKind },
  ];
  return (
    <section className="mt-card" aria-label="Attendance summary">
      <div className="mt-cardhead">
        <span className="mt-iconchip"><CalendarDays size={15} /></span>
        <div>
          <h2 className="mt-card-title">Attendance Summary</h2>
          <div className="mt-card-sub">{monthLabel}</div>
        </div>
      </div>
      <div className="mt-sumgrid">
        {cells.map(c => (
          <div className="mt-sum" key={c.label}>
            <div className="mt-sum-num"><DayChip kind={c.kind} small />{loading ? '…' : c.n}</div>
            <div className="mt-sum-lbl">{c.label}</div>
          </div>
        ))}
      </div>
      <p className="mt-note">Person-days across the team, counted from the calendar below.</p>
    </section>
  );
}

/* ══ Card 3 — Quick actions ══ */
export interface QuickAction { key: string; label: string; icon: ReactNode; to?: string; onClick?: () => void }

/**
 * Candidate actions are filtered through the same per-role NAV table the sidebar uses, so a role
 * only ever sees shortcuts to destinations it already has — this adds no access of its own.
 */
export function buildQuickActions(role: Role, opts: { reportsTab: boolean; onOpenReports?: () => void }): QuickAction[] {
  const navFor = (key: string) => NAV[role].find(i => i.key === key && !isNavItemDisabled(i));
  const want: Record<string, { label: string; icon: ReactNode }> = {
    attendance: { label: 'Attendance', icon: <CalendarCheck size={20} /> },
    leave: { label: 'Leave', icon: <Plane size={20} /> },
    approvals: { label: 'Approvals', icon: <CheckCircle2 size={20} /> },
    directory: { label: 'Team Directory', icon: <Users size={20} /> },
    requests: { label: 'Requests', icon: <ClipboardList size={20} /> },
    reports: { label: 'Reports', icon: <BarChart3 size={20} /> },
  };
  const order = role === 'Employee' ? ['attendance', 'leave', 'directory', 'requests'] : ['attendance', 'leave', 'approvals', 'reports'];
  const out: QuickAction[] = [];
  for (const key of order) {
    if (key === 'reports' && opts.reportsTab && opts.onOpenReports) {
      out.push({ key, ...want[key], onClick: opts.onOpenReports });
      continue;
    }
    const item = navFor(key);
    if (item) out.push({ key, ...want[key], to: item.path });
  }
  return out;
}

export function QuickActionsCard({ actions, inert }: { actions: QuickAction[]; inert?: boolean }) {
  return (
    <section className="mt-card" aria-label="Quick actions">
      <div className="mt-cardhead">
        <span className="mt-iconchip"><Zap size={15} /></span>
        <h2 className="mt-card-title">Team Quick Actions</h2>
      </div>
      {actions.length === 0 ? (
        <div className="mt-empty" style={{ padding: '12px 0' }}>No shortcuts available for your role.</div>
      ) : (
        <div className="mt-actions">
          {actions.map(a => {
            const inner = <>{a.icon}<span>{a.label}</span></>;
            if (!inert && a.to) return <Link key={a.key} to={a.to} className="mt-action">{inner}</Link>;
            return <button key={a.key} type="button" className="mt-action" onClick={inert ? undefined : a.onClick} title={inert ? 'Preview only: shortcut disabled' : undefined}>{inner}</button>;
          })}
        </div>
      )}
    </section>
  );
}

/* ══ Calendar ══ */
/** Each status pairs a colour with its own icon, so status never relies on colour alone. */
const ICON: Partial<Record<DayKind, LucideIcon>> = {
  present: Check, late: Clock, 'half-day': Contrast, absent: X, 'missing-checkout': LogOut,
  leave: TreePalm, wfh: Laptop, holiday: PartyPopper, 'weekly-off': Moon, missing: HelpCircle,
};

export function DayChip({ kind, small }: { kind: DayKind; small?: boolean }) {
  const Icon = ICON[kind];
  return (
    <span className={`mt-day mt-d-${kind}${small ? ' mt-day-sm' : ''}`} aria-hidden>
      {Icon && <Icon size={small ? 10 : 14} strokeWidth={kind === 'weekly-off' ? 2 : 2.4} />}
    </span>
  );
}

const LEGEND: DayKind[] = ['present', 'late', 'half-day', 'absent', 'missing-checkout', 'leave', 'wfh', 'holiday', 'weekly-off', 'missing'];

interface TipState { x: number; y: number; below: boolean; cell: DayCell; width: number }

export function TeamCalendarPanel({
  title = 'Team Calendar', subtitle, memberHeader, members, viewDate, onMonthChange, ctx, renderAvatar,
  page, onPageChange, pageSize,
}: {
  title?: string; subtitle: string; memberHeader: string; members: MemberRow[];
  viewDate: Date; onMonthChange: (d: Date) => void; ctx: DayContext; renderAvatar: AvatarRenderer;
  page: number; onPageChange: (p: number) => void; pageSize: number;
}) {
  const year = viewDate.getFullYear(), month = viewDate.getMonth();
  const totalDays = daysIn(year, month);
  const days = useMemo(() => Array.from({ length: totalDays }, (_, i) => i + 1), [totalDays]);
  const totalPages = Math.max(1, Math.ceil(members.length / pageSize));
  const pageSafe = Math.min(page, totalPages - 1);
  const paged = members.slice(pageSafe * pageSize, pageSafe * pageSize + pageSize);

  const [tip, setTip] = useState<TipState | null>(null);
  const [focus, setFocus] = useState<DayKind | null>(null);
  const tipRef = useRef<HTMLDivElement>(null);

  // The tooltip is position:fixed against the cell's rect at show time, so any scroll would leave
  // it floating in the wrong place — dismiss it instead (page, calendar or any ancestor scroller).
  useEffect(() => {
    if (!tip) return;
    const hide = () => setTip(null);
    window.addEventListener('scroll', hide, true);
    window.addEventListener('resize', hide);
    return () => { window.removeEventListener('scroll', hide, true); window.removeEventListener('resize', hide); };
  }, [tip]);

  function showTip(el: HTMLElement, cell: DayCell) {
    if (cell.kind === 'not-joined') return;
    const r = el.getBoundingClientRect();
    const width = 220, gap = 8;
    const x = Math.min(Math.max(r.left + r.width / 2 - width / 2, 8), Math.max(8, window.innerWidth - width - 8));
    const below = r.top < 130;
    setTip({ x, y: below ? r.bottom + gap : r.top - gap, below, cell, width });
  }

  const legendLabel = (k: DayKind) => (k === 'missing' ? 'No record' : KIND_LABEL[k]);

  return (
    <section className="mt-card" aria-label={title}>
      <div className="mt-cal-head">
        <div>
          <h2 className="mt-card-title" style={{ fontSize: 16 }}>{title}</h2>
          <div className="mt-card-sub">{subtitle}</div>
        </div>
        <div className="mt-cal-tools">
          <div className="mt-monthnav">
            <button type="button" className="mt-iconbtn" aria-label="Previous month" onClick={() => onMonthChange(new Date(year, month - 1, 1))}><ChevronLeft size={15} /></button>
            <span className="mt-monthlbl"><CalendarDays size={13} />{viewDate.toLocaleDateString('en-US', { month: 'long', year: 'numeric' })}</span>
            <button type="button" className="mt-iconbtn" aria-label="Next month" onClick={() => onMonthChange(new Date(year, month + 1, 1))}><ChevronRight size={15} /></button>
          </div>
          <span className="mt-card-sub" style={{ margin: 0 }}>
            {totalPages > 1
              ? `${pageSafe * pageSize + 1}–${Math.min(members.length, (pageSafe + 1) * pageSize)} of ${members.length}`
              : `${members.length} ${members.length === 1 ? 'person' : 'people'}`}
          </span>
        </div>
      </div>

      <div className="mt-legend-bar" role="group" aria-label="Legend: select a status to highlight it">
        {LEGEND.map(k => (
          <button key={k} type="button" className="mt-legend-item" aria-pressed={focus === k} onClick={() => setFocus(f => (f === k ? null : k))}>
            <DayChip kind={k} small />{legendLabel(k)}
          </button>
        ))}
        {focus && <button type="button" className="mt-legend-clear" onClick={() => setFocus(null)}>Clear highlight</button>}
      </div>

      {members.length === 0 ? (
        <div className="mt-empty" style={{ borderTop: '1px solid var(--line)' }}>No team members to show.</div>
      ) : (
        <div className="mt-scroll" onScroll={() => setTip(null)}>
          <table className="mt-table">
            <thead>
              <tr>
                <th className="mt-sticky mt-th-left" style={{ minWidth: 250 }}>{memberHeader}</th>
                <th className="mt-th-left" style={{ minWidth: 104, paddingLeft: 4 }}>Team</th>
                {days.map(d => {
                  const iso = isoOf(year, month, d);
                  const monday = new Date(year, month, d).getDay() === 1 && d > 1;
                  return (
                    <th key={d} className={`${iso === ctx.today ? 'mt-today' : ''}${monday ? ' mt-wk' : ''}`.trim() || undefined} style={{ minWidth: 34 }}>
                      {d}<small>{WEEKDAYS[new Date(year, month, d).getDay()]}</small>
                    </th>
                  );
                })}
                <th style={{ minWidth: 150 }}>Month tally</th>
                <th style={{ minWidth: 104, padding: '8px 14px' }}>Today</th>
              </tr>
            </thead>
            <tbody>
              {paged.map((m, i) => {
                const cells = days.map(d => {
                  const iso = isoOf(year, month, d);
                  const dow = new Date(year, month, d).getDay();
                  return { d, iso, monday: dow === 1 && d > 1, cell: classifyDayCell(ctx, m.userId, m.joiningDate, iso, dow) };
                });
                const count = (...k: DayKind[]) => cells.filter(c => k.includes(c.cell.kind)).length;
                const tally = { present: count('present'), late: count('late'), absent: count('absent', 'missing'), leave: count('leave') };
                return (
                <tr key={m.userId} style={inactiveDimStyle(m.active)}>
                  <td className="mt-sticky">
                    <div className="mt-who">
                      <span className="mt-who-idx">{pageSafe * pageSize + i + 1}</span>
                      {renderAvatar(m.userId, m.fullName, 32)}
                      <div style={{ minWidth: 0 }}>
                        <div className="mt-who-name">{m.fullName} {!m.active && <StatusBadge active={false} />}</div>
                        <div className="mt-who-role">{m.designation ?? 'No designation'}</div>
                      </div>
                    </div>
                  </td>
                  <td style={{ paddingLeft: 4, paddingRight: 8 }}>{m.department ? <Pill>{m.department}</Pill> : <span style={{ color: 'var(--txt-dim)', fontSize: 11 }}>No team</span>}</td>
                  {cells.map(({ d, iso, cell, monday }) => (
                    <td key={d} className={`mt-cellwrap${iso === ctx.today ? ' mt-today' : ''}${monday ? ' mt-wk' : ''}`}>
                      {cell.kind === 'not-joined' ? null : (
                        <button
                          type="button" className={`mt-cell${focus && focus !== cell.kind ? ' mt-dim' : ''}`}
                          aria-label={`${m.fullName}, ${KIND_LABEL[cell.kind]}, ${longDate(iso)}`}
                          onMouseEnter={e => showTip(e.currentTarget, cell)} onMouseLeave={() => setTip(null)}
                          onFocus={e => showTip(e.currentTarget, cell)} onBlur={() => setTip(null)}
                          onKeyDown={e => { if (e.key === 'Escape') setTip(null); }}
                        >
                          <DayChip kind={cell.kind} />
                        </button>
                      )}
                    </td>
                  ))}
                  <td style={{ padding: '0 10px' }}>
                    <div className="mt-tally" aria-label="Month tally">
                      {([['present', tally.present], ['late', tally.late], ['absent', tally.absent], ['leave', tally.leave]] as [DayKind, number][]).map(([k, n]) => (
                        <span key={k} className={n === 0 ? 'mt-tally-zero' : undefined} title={`${KIND_LABEL[k]}: ${n}`}><DayChip kind={k} small />{n}</span>
                      ))}
                    </div>
                  </td>
                  <td style={{ textAlign: 'center', padding: '0 14px' }}>{m.active ? <LiveStatusPill status={m.status} /> : <StatusBadge active={false} />}</td>
                </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {totalPages > 1 && (
        <div className="mt-pager">
          <button type="button" className="mt-btn" disabled={pageSafe === 0} onClick={() => onPageChange(Math.max(0, pageSafe - 1))}>← Prev</button>
          <span>Page {pageSafe + 1} of {totalPages}</span>
          <button type="button" className="mt-btn" disabled={pageSafe >= totalPages - 1} onClick={() => onPageChange(Math.min(totalPages - 1, pageSafe + 1))}>Next →</button>
        </div>
      )}

      {tip && createPortal(
        <div
          ref={tipRef} role="tooltip" className="mt-tip"
          style={{ left: tip.x, top: tip.y, transform: tip.below ? undefined : 'translateY(-100%)' }}
        >
          <b>{tip.cell.title}</b>
          {tip.cell.lines.map((l, i) => <div key={i}>{l}</div>)}
          {tip.cell.flag && <div className="mt-tip-flag">{tip.cell.flag}</div>}
        </div>,
        document.body,
      )}
    </section>
  );
}

/* ══ Member cards ══ */
export function MemberGridSection({ title, subtitle, search, onSearch, searchPlaceholder, chips, children }: {
  title: string; subtitle?: string; search: string; onSearch: (v: string) => void; searchPlaceholder: string;
  chips?: ReactNode; children: ReactNode;
}) {
  return (
    <section className="mt-card" aria-label={title}>
      <div className="mt-sec-head">
        <div>
          <h2 className="mt-card-title" style={{ fontSize: 16 }}>{title}</h2>
          {subtitle && <div className="mt-card-sub">{subtitle}</div>}
        </div>
      </div>
      <div className="mt-filters">
        <label className="mt-search">
          <Search size={14} aria-hidden />
          <input value={search} onChange={e => onSearch(e.target.value)} placeholder={searchPlaceholder} aria-label={searchPlaceholder} />
        </label>
        {chips}
      </div>
      {children}
    </section>
  );
}

export function FilterChip({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return <button type="button" className="mt-chip" aria-pressed={active} onClick={onClick}>{children}</button>;
}

export function MemberGrid({ children, empty }: { children: ReactNode; empty?: ReactNode }) {
  return empty ? <div className="mt-empty">{empty}</div> : <div className="mt-grid">{children}</div>;
}

export function MemberCard({ avatar, name, designation, active, tags, status, extras, onViewProfile, secondaryAction }: {
  avatar: ReactNode; name: string; designation: string | null; active: boolean;
  tags?: ReactNode; status: LiveStatus; extras?: ReactNode; onViewProfile?: () => void; secondaryAction?: ReactNode;
}) {
  return (
    <article className="mt-member" style={inactiveDimStyle(active)}>
      <div className="mt-member-top">
        {avatar}
        <div style={{ minWidth: 0 }}>
          <div className="mt-member-name">{name}</div>
          <div className="mt-member-role">{designation ?? 'No designation'}</div>
        </div>
      </div>
      <div className="mt-member-tags">{tags}</div>
      {extras}
      <div className="mt-member-foot">
        {active ? <LiveStatusPill status={status} /> : <StatusBadge active={false} />}
        <div className="mt-foot-actions">
          {secondaryAction}
          {onViewProfile && <button type="button" className="mt-link" onClick={onViewProfile}>View Profile <ArrowRight size={12} /></button>}
        </div>
      </div>
    </article>
  );
}

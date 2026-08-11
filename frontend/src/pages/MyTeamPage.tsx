import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Search, Check, X, AlertTriangle, Users, CheckCircle2, Clock, Home, MapPin } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { dashboardApi, type DirectReport } from '../api/dashboard';
import {
  attendanceApi, regularizationApi, type AttendanceRecord,
  type TeamEffortEntry, type TeamNegligenceResponse,
  type TeamLateArrivalEntry, type TeamLeastHoursEntry, type TeamFrequentBreaksEntry,
} from '../api/attendance';
import { leaveApi, type LeaveRequestRecord } from '../api/leave';
import { holidaysApi, type HolidayRow } from '../api/holidays';
import { approvalCenterApi, type ApprovalItem } from '../api/approvalCenter';
import {
  employeeAssignmentsApi, type EmployeeAssignmentRow, type AssignmentLookups, type AssignmentFilters,
} from '../api/employeeAssignments';
import { reportsApi, type AttendanceRequestReportType, type AttendanceRequestReportRow } from '../api/reports';

/* ── Date helpers (local to this page, matching the codebase's per-page convention) ── */
function todayIsoDate(): string {
  return new Date().toISOString().slice(0, 10);
}
function daysInMonth(year: number, month: number) {
  return new Date(year, month + 1, 0).getDate();
}
function toISODate(year: number, month: number, day: number) {
  return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}
function mondayOf(d: Date): Date {
  const day = d.getDay();
  const diff = day === 0 ? -6 : 1 - day;
  const monday = new Date(d);
  monday.setDate(d.getDate() + diff);
  monday.setHours(0, 0, 0, 0);
  return monday;
}
function addDays(d: Date, n: number): Date {
  const copy = new Date(d);
  copy.setDate(copy.getDate() + n);
  return copy;
}
function toISO(d: Date): string {
  return toISODate(d.getFullYear(), d.getMonth(), d.getDate());
}
function fmtTime(iso?: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' });
}
function fmtDateShort(iso?: string | null) {
  if (!iso) return '—';
  return new Date(iso + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
}
function initials(name: string) {
  return name.split(' ').map(w => w[0] ?? '').join('').slice(0, 2).toUpperCase();
}

const WEEKDAY_LABELS = ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'];
const WEEK_CHIPS = ['M', 'T', 'W', 'T', 'F'];

/* ── Shared style constants (matching ApprovalsPage.tsx / LeavePage.tsx exactly) ── */
const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 480, maxHeight: '90vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const panelStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
const panelHeadStyle: React.CSSProperties = { padding: '14px 18px', borderBottom: '1px solid var(--line)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10 };
const panelTitleStyle: React.CSSProperties = { fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 14, color: 'var(--txt)' };
const panelCountStyle: React.CSSProperties = { fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', background: 'var(--raised2)', padding: '2px 8px', borderRadius: 20 };

const TYPE_LABELS: Record<'LEAVE' | 'REGULARIZATION', string> = { LEAVE: 'Leave', REGULARIZATION: 'Attendance Reg.' };
const TYPE_COLORS: Record<'LEAVE' | 'REGULARIZATION', string> = { LEAVE: 'rgba(99,102,241,.18)', REGULARIZATION: 'rgba(245,158,11,.18)' };
const TYPE_TEXT: Record<'LEAVE' | 'REGULARIZATION', string> = { LEAVE: '#818CF8', REGULARIZATION: '#F59E0B' };

function TypeBadge({ type }: { type: 'LEAVE' | 'REGULARIZATION' }) {
  return (
    <span style={{ fontSize: 10.5, fontWeight: 700, padding: '3px 8px', borderRadius: 20, background: TYPE_COLORS[type], color: TYPE_TEXT[type], whiteSpace: 'nowrap' }}>
      {TYPE_LABELS[type]}
    </span>
  );
}

function Avatar({ name, size = 34 }: { name: string; size?: number }) {
  return (
    <div style={{ width: size, height: size, borderRadius: '50%', background: 'rgba(177,17,22,.18)', color: '#e4373d', display: 'grid', placeItems: 'center', fontSize: size * 0.33, fontWeight: 700, flexShrink: 0, fontFamily: '"Space Grotesk", sans-serif' }}>
      {initials(name)}
    </div>
  );
}

type RosterStatus = 'IN' | 'OUT' | 'NOT_IN_YET' | 'LEAVE';
const STATUS_LABEL: Record<RosterStatus, string> = { IN: 'In', OUT: 'Out', NOT_IN_YET: 'Not in yet', LEAVE: 'Leave' };
const STATUS_STYLE: Record<RosterStatus, { bg: string; fg: string }> = {
  IN: { bg: 'rgba(47,182,124,.15)', fg: 'var(--ok)' },
  OUT: { bg: 'rgba(228,55,61,.15)', fg: 'var(--risk)' },
  NOT_IN_YET: { bg: 'rgba(76,141,214,.16)', fg: 'var(--info)' },
  LEAVE: { bg: 'rgba(99,102,241,.18)', fg: '#818CF8' },
};

function StatusPill({ status }: { status: RosterStatus }) {
  const s = STATUS_STYLE[status];
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11.5, fontWeight: 600, padding: '4px 9px 4px 7px', borderRadius: 20, background: s.bg, color: s.fg, whiteSpace: 'nowrap' }}>
      <span style={{ width: 6, height: 6, borderRadius: '50%', background: s.fg, flexShrink: 0 }} />
      {STATUS_LABEL[status]}
    </span>
  );
}

interface RosterRow {
  dr: DirectReport;
  record: AttendanceRecord | undefined;
  status: RosterStatus;
  isLate: boolean;
  requests: ApprovalItem[];
  leaveTypeName: string | undefined;
}

/* ── Needs your attention: one queue item, with an inline reject-reason prompt ── */
function AttentionQueueItem({ item, token, onDone }: { item: ApprovalItem; token: string; onDone: (id: string) => void }) {
  const { showToast } = useToast();
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const type = item.requestType as 'LEAVE' | 'REGULARIZATION';

  async function approve() {
    setBusy(true);
    try {
      if (type === 'LEAVE') await leaveApi.approve(item.id, token);
      else await regularizationApi.approve(item.id, token);
      showToast('success', `Approved — ${item.employeeName}`);
      onDone(item.id);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Approve failed');
      setBusy(false);
    }
  }

  async function reject() {
    if (!reason.trim()) return;
    setBusy(true);
    try {
      if (type === 'LEAVE') await leaveApi.reject(item.id, reason.trim(), token);
      else await regularizationApi.reject(item.id, reason.trim(), token);
      showToast('success', `Rejected — ${item.employeeName}`);
      onDone(item.id);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Reject failed');
      setBusy(false);
    }
  }

  const detail = type === 'LEAVE'
    ? `${item.leaveTypeName} · ${fmtDateShort(item.leaveStartDate)}${item.leaveStartDate !== item.leaveEndDate ? ` – ${fmtDateShort(item.leaveEndDate)}` : ''} (${item.leaveTotalDays} day${item.leaveTotalDays !== 1 ? 's' : ''})`
    : `${fmtDateShort(item.attendanceDate)} · missing ${item.requestedCheckIn && item.requestedCheckOut ? 'check-in & check-out' : item.requestedCheckIn ? 'check-in' : 'check-out'}`;

  return (
    <div style={{ padding: '13px 18px', borderBottom: '1px solid var(--line)', display: 'flex', flexDirection: 'column', gap: 7 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
        <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{item.employeeName}</span>
        <TypeBadge type={type} />
      </div>
      <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>{detail}</div>
      {rejecting ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 2 }}>
          <textarea
            autoFocus
            value={reason}
            onChange={e => setReason(e.target.value)}
            placeholder="Reason for rejecting…"
            style={{ ...inputStyle, minHeight: 56, resize: 'vertical', fontFamily: 'inherit' }}
          />
          <div style={{ display: 'flex', gap: 6 }}>
            <button onClick={() => { setRejecting(false); setReason(''); }} style={{ flex: 1, fontSize: 11.5, fontWeight: 600, padding: '6px 8px', borderRadius: 6, cursor: 'pointer', border: '1px solid var(--line2)', background: 'var(--raised2)', color: 'var(--txt-mut)' }}>Cancel</button>
            <button onClick={reject} disabled={!reason.trim() || busy} style={{ flex: 1, fontSize: 11.5, fontWeight: 600, padding: '6px 8px', borderRadius: 6, cursor: !reason.trim() || busy ? 'not-allowed' : 'pointer', border: '1px solid transparent', background: reason.trim() ? 'rgba(228,55,61,.18)' : 'var(--raised2)', color: reason.trim() ? 'var(--risk)' : 'var(--txt-dim)' }}>
              {busy ? 'Rejecting…' : 'Confirm reject'}
            </button>
          </div>
        </div>
      ) : (
        <div style={{ display: 'flex', gap: 6, marginTop: 2 }}>
          <button onClick={approve} disabled={busy} style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 5, fontSize: 11.5, fontWeight: 600, padding: '6px 8px', borderRadius: 6, cursor: busy ? 'not-allowed' : 'pointer', border: '1px solid transparent', background: 'rgba(47,182,124,.14)', color: 'var(--ok)' }}>
            <Check size={12} /> Approve
          </button>
          <button onClick={() => setRejecting(true)} disabled={busy} style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 5, fontSize: 11.5, fontWeight: 600, padding: '6px 8px', borderRadius: 6, cursor: busy ? 'not-allowed' : 'pointer', border: '1px solid var(--line2)', background: 'var(--raised2)', color: 'var(--txt-mut)' }}>
            <X size={12} /> Reject
          </button>
        </div>
      )}
    </div>
  );
}

/* ── Roster card "View" detail modal ── */
function EmployeeDetailModal({ row, onClose }: { row: RosterRow; onClose: () => void }) {
  const { dr, record, status, requests } = row;
  const regRequest = requests.find(r => r.requestType === 'REGULARIZATION');
  const missingFlag = regRequest
    ? (regRequest.requestedCheckIn && regRequest.requestedCheckOut ? 'Missing check-in & check-out'
      : regRequest.requestedCheckIn ? 'Missing check-in swipe' : 'Missing check-out swipe') + ` on ${fmtDateShort(regRequest.attendanceDate)}`
    : null;

  return (
    <div style={overlayStyle} onClick={onClose}>
      <div style={modalStyle} onClick={e => e.stopPropagation()}>
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, padding: 18, borderBottom: '1px solid var(--line)' }}>
          <Avatar name={dr.fullName} size={40} />
          <div style={{ flex: 1 }}>
            <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>{dr.fullName}</div>
            <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 2 }}>{dr.designationName ?? '—'}</div>
            <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', fontFamily: '"JetBrains Mono", monospace', marginTop: 2 }}>{dr.employeeCode}</div>
          </div>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', padding: 5, borderRadius: 6 }}><X size={16} /></button>
        </div>
        <div style={{ padding: 18, display: 'flex', flexDirection: 'column', gap: 18 }}>
          <div>
            <div style={labelStyle}>Today's attendance</div>
            <Row label="Status" value={STATUS_LABEL[status]} />
            <Row label="Actual check-in" value={fmtTime(record?.checkInAt)} />
            <Row label="Actual check-out" value={fmtTime(record?.checkOutAt)} />
            {!!record?.lateByMinutes && <Row label="Late by" value={`${record.lateByMinutes} minutes`} />}
            {missingFlag && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 12, fontWeight: 600, padding: '8px 10px', borderRadius: 8, marginTop: 8, background: 'rgba(228,55,61,.13)', color: 'var(--risk)' }}>
                <AlertTriangle size={14} /> {missingFlag}
              </div>
            )}
          </div>
          <div>
            <div style={labelStyle}>Open requests</div>
            {requests.length === 0 ? (
              <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>No open requests.</div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {requests.map(r => (
                  <div key={`${r.requestType}:${r.id}`} style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: '10px 12px', display: 'flex', flexDirection: 'column', gap: 5 }}>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                      <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{r.requestType === 'LEAVE' ? 'Leave request' : 'Attendance regularization'}</span>
                      <TypeBadge type={r.requestType as 'LEAVE' | 'REGULARIZATION'} />
                    </div>
                    <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
                      {r.requestType === 'LEAVE'
                        ? `${r.leaveTypeName} · ${fmtDateShort(r.leaveStartDate)}${r.leaveStartDate !== r.leaveEndDate ? ` – ${fmtDateShort(r.leaveEndDate)}` : ''}`
                        : `${fmtDateShort(r.attendanceDate)} · ${r.regularizationReason ?? ''}`}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10, padding: '7px 0', borderBottom: '1px solid var(--line)', fontSize: 12.5 }}>
      <span style={{ color: 'var(--txt-mut)' }}>{label}</span>
      <span style={{ color: 'var(--txt)', fontWeight: 600 }}>{value}</span>
    </div>
  );
}

/* ── KPI card ── */
function KpiCard({ icon, iconColor, label, value, note }: { icon: React.ReactNode; iconColor: string; label: string; value: React.ReactNode; note: string }) {
  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '16px 18px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10, color: iconColor }}>
        {icon}
        <span style={{ fontSize: 11.5, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '.05em', color: 'var(--txt-mut)' }}>{label}</span>
      </div>
      <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 30, color: 'var(--txt)', lineHeight: 1 }}>{value}</div>
      <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>{note}</div>
    </div>
  );
}

/* ── Calendar day-cell classification ── */
type DayCategory = 'holiday' | 'weekly-off' | 'leave' | 'wfh' | 'plain' | 'missing';
const DAY_COLORS: Record<Exclude<DayCategory, 'plain'>, string> = {
  holiday: '#2FA36B',
  'weekly-off': '#D4922E',
  leave: '#818CF8',
  wfh: 'var(--info)',
  missing: 'var(--risk)',
};

/* ── Shared: date-range control for the leaderboard/negligence tabs ── */
function DateRangeControl({ from, to, onFrom, onTo }: { from: string; to: string; onFrom: (v: string) => void; onTo: (v: string) => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '12px 18px', borderBottom: '1px solid var(--line)', flexWrap: 'wrap' }}>
      <span style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)' }}>Date range</span>
      <input type="date" value={from} max={to} onChange={e => onFrom(e.target.value)} style={{ ...inputStyle, width: 'auto', padding: '6px 9px' }} />
      <span style={{ color: 'var(--txt-dim)', fontSize: 12 }}>–</span>
      <input type="date" value={to} min={from} max={todayIsoDate()} onChange={e => onTo(e.target.value)} style={{ ...inputStyle, width: 'auto', padding: '6px 9px' }} />
    </div>
  );
}

function useTeamDateRange(days: number) {
  const [from, setFrom] = useState(() => toISO(addDays(new Date(), -(days - 1))));
  const [to, setTo] = useState(() => todayIsoDate());
  return { from, setFrom, to, setTo };
}

/* ══ ONEHR-106: Team Effort (Avg. Work Hours Leaderboard) ══ */
function EffortRow({ entry }: { entry: TeamEffortEntry }) {
  const fillPct = Math.min(100, entry.avgHoursPerDay * 10);
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '11px 18px', borderBottom: '1px solid var(--line)', flexWrap: 'wrap' }}>
      <Avatar name={entry.fullName} size={30} />
      <div style={{ minWidth: 150 }}>
        <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{entry.fullName}</div>
        <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>{entry.designationName ?? '—'}</div>
      </div>
      <div style={{ flex: 1, minWidth: 100 }}>
        <div style={{ height: 6, borderRadius: 4, background: 'var(--raised2)', overflow: 'hidden' }}>
          <div style={{ height: '100%', width: `${fillPct}%`, borderRadius: 4, background: 'var(--brand-bright)' }} />
        </div>
      </div>
      <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', whiteSpace: 'nowrap' }}>
        Avg. {entry.avgHoursPerDay.toFixed(1)} hrs/day · {entry.hoursWorked.toFixed(1)}/{entry.expectedHours.toFixed(0)} hrs worked
      </div>
    </div>
  );
}

function EffortTab({ token }: { token: string }) {
  const { showToast } = useToast();
  const { from, setFrom, to, setTo } = useTeamDateRange(7);
  const [entries, setEntries] = useState<TeamEffortEntry[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    attendanceApi.teamEffort(from, to, token)
      .then(setEntries)
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Failed to load team effort'))
      .finally(() => setLoading(false));
  }, [token, from, to]);

  return (
    <div style={panelStyle}>
      <div style={panelHeadStyle}>
        <span style={panelTitleStyle}>Avg. Work Hours Leaderboard</span>
        <span style={panelCountStyle}>{fmtDateShort(from)} – {fmtDateShort(to)}</span>
      </div>
      <DateRangeControl from={from} to={to} onFrom={setFrom} onTo={setTo} />
      {loading ? (
        <div style={{ padding: '16px 18px', fontSize: 12.5, color: 'var(--txt-dim)' }}>Loading…</div>
      ) : entries.length === 0 ? (
        <div style={{ padding: '16px 18px', fontSize: 12.5, color: 'var(--txt-dim)' }}>No attendance data for this range.</div>
      ) : (
        entries.map(e => <EffortRow key={e.employeeUserId} entry={e} />)
      )}
    </div>
  );
}

/* ══ ONEHR-107: Team Negligence Signals ══ */
function LateArrivalRow({ entry }: { entry: TeamLateArrivalEntry }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '11px 18px', borderBottom: '1px solid var(--line)' }}>
      <Avatar name={entry.fullName} size={30} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{entry.fullName}</div>
        <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>{entry.designationName ?? '—'}</div>
      </div>
      <div style={{ textAlign: 'right' }}>
        <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>{entry.lateDays} / {entry.activeDays} Late Arrivals</div>
        <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--risk)' }}>{entry.latePct.toFixed(0)}%</div>
      </div>
    </div>
  );
}

function DailyBarChart({ data, color }: { data: { date: string; count: number }[]; color: string }) {
  const max = Math.max(1, ...data.map(d => d.count));
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 6, height: 130, padding: '14px 6px 0' }}>
      {data.map(d => (
        <div key={d.date} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4, minWidth: 0 }}>
          <span style={{ fontSize: 9.5, color: 'var(--txt-dim)' }}>{d.count}</span>
          <div style={{
            width: '100%', maxWidth: 26, height: `${(d.count / max) * 90}%`, minHeight: d.count > 0 ? 3 : 0,
            background: color, borderRadius: '3px 3px 0 0',
          }} />
          <span style={{ fontSize: 9, color: 'var(--txt-dim)', whiteSpace: 'nowrap' }}>{fmtDateShort(d.date)}</span>
        </div>
      ))}
    </div>
  );
}

function LeastHoursRow({ entry }: { entry: TeamLeastHoursEntry }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '11px 18px', borderBottom: '1px solid var(--line)' }}>
      <Avatar name={entry.fullName} size={30} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{entry.fullName}</div>
        <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>{entry.designationName ?? '—'}</div>
      </div>
      <div style={{ textAlign: 'right' }}>
        <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt)' }}>Avg. {entry.avgHoursPerDay.toFixed(1)} hrs/day</div>
        <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>{entry.hoursWorked.toFixed(1)} hrs worked</div>
      </div>
    </div>
  );
}

const BUCKET_COLORS = ['#E4373D', '#E0A93B', '#4C8DD6', '#2FB67C', '#818CF8', '#8B5CF6'];

function HoursDonut({ buckets }: { buckets: { label: string; count: number; pct: number }[] }) {
  let acc = 0;
  const total = buckets.reduce((s, b) => s + b.count, 0);
  const stops = buckets.map((b, i) => {
    const start = acc;
    acc += b.pct;
    return `${BUCKET_COLORS[i]} ${start}% ${acc}%`;
  }).join(', ');
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 20, flexWrap: 'wrap', padding: '16px 18px' }}>
      <div style={{ width: 130, height: 130, borderRadius: '50%', flexShrink: 0, background: total > 0 ? `conic-gradient(${stops})` : 'var(--raised2)' }} />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        {buckets.map((b, i) => (
          <div key={b.label} style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 11.5, color: 'var(--txt-mut)' }}>
            <span style={{ width: 9, height: 9, borderRadius: 2, background: BUCKET_COLORS[i], flexShrink: 0 }} />
            {b.label} <b style={{ color: 'var(--txt)' }}>{b.pct.toFixed(0)}%</b>
          </div>
        ))}
      </div>
    </div>
  );
}

function FrequentBreaksRow({ entry }: { entry: TeamFrequentBreaksEntry }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '11px 18px', borderBottom: '1px solid var(--line)' }}>
      <Avatar name={entry.fullName} size={30} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{entry.fullName}</div>
        <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>{entry.designationName ?? '—'}</div>
      </div>
      <div style={{ textAlign: 'right' }}>
        <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt)' }}>{entry.totalBreakHours.toFixed(1)} hrs in {entry.totalBreakCount} breaks</div>
        <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>Avg. {entry.avgBreaksPerDay.toFixed(1)} breaks/day</div>
      </div>
    </div>
  );
}

function BreaksTrend({ data }: { data: { date: string; avgBreaks: number }[] }) {
  if (data.length === 0) return null;
  const max = Math.max(...data.map(d => d.avgBreaks), 1);
  const point = (i: number, v: number) => {
    const x = data.length === 1 ? 150 : (i / (data.length - 1)) * 280 + 10;
    const y = 130 - (v / max) * 100;
    return [x, y] as const;
  };
  const points = data.map((d, i) => point(i, d.avgBreaks).join(',')).join(' ');
  const trendingUp = data.length > 1 && data[data.length - 1].avgBreaks >= data[0].avgBreaks;
  return (
    <div style={{ padding: '14px 18px' }}>
      <svg viewBox="0 0 300 140" style={{ width: '100%', maxWidth: 360, height: 'auto', display: 'block' }}>
        <line x1="0" y1="130" x2="300" y2="130" stroke="var(--line)" strokeWidth="1" />
        <polyline points={points} fill="none" stroke="var(--info)" strokeWidth="2.5" />
        {data.map((d, i) => {
          const [x, y] = point(i, d.avgBreaks);
          return <circle key={d.date} cx={x} cy={y} r="3.5" fill="var(--info)" />;
        })}
      </svg>
      <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 6 }}>
        Avg. breaks/day across flagged employees — {trendingUp ? 'trending up' : 'trending down'} this range
      </div>
    </div>
  );
}

function NegligenceTab({ token }: { token: string }) {
  const { showToast } = useToast();
  const { from, setFrom, to, setTo } = useTeamDateRange(7);
  const [data, setData] = useState<TeamNegligenceResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    attendanceApi.teamNegligence(from, to, token)
      .then(setData)
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Failed to load negligence data'))
      .finally(() => setLoading(false));
  }, [token, from, to]);

  if (loading || !data) {
    return <div style={{ ...panelStyle, padding: '16px 18px', fontSize: 12.5, color: 'var(--txt-dim)' }}>Loading…</div>;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={panelStyle}>
        <div style={panelHeadStyle}>
          <span style={panelTitleStyle}>Late Arrivals</span>
          <span style={panelCountStyle}>{fmtDateShort(from)} – {fmtDateShort(to)}</span>
        </div>
        <DateRangeControl from={from} to={to} onFrom={setFrom} onTo={setTo} />
        {data.lateArrivals.length === 0 ? (
          <div style={{ padding: '16px 18px', fontSize: 12.5, color: 'var(--txt-dim)' }}>No late arrivals in this range.</div>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) 320px', gap: 0 }}>
            <div>{data.lateArrivals.map(e => <LateArrivalRow key={e.employeeUserId} entry={e} />)}</div>
            <div style={{ borderLeft: '1px solid var(--line)' }}>
              <DailyBarChart data={data.dailyLateCounts.map(d => ({ date: d.date, count: d.count }))} color="var(--risk)" />
            </div>
          </div>
        )}
      </div>

      <div style={panelStyle}>
        <div style={panelHeadStyle}>
          <span style={panelTitleStyle}>Least Hours Worked</span>
          <span style={panelCountStyle}>{fmtDateShort(from)} – {fmtDateShort(to)}</span>
        </div>
        {data.leastHoursWorked.length === 0 ? (
          <div style={{ padding: '16px 18px', fontSize: 12.5, color: 'var(--txt-dim)' }}>No attendance data for this range.</div>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) 320px', gap: 0 }}>
            <div>{data.leastHoursWorked.slice(0, 5).map(e => <LeastHoursRow key={e.employeeUserId} entry={e} />)}</div>
            <div style={{ borderLeft: '1px solid var(--line)' }}>
              <HoursDonut buckets={data.hoursHistogram} />
            </div>
          </div>
        )}
      </div>

      <div style={panelStyle}>
        <div style={panelHeadStyle}>
          <span style={panelTitleStyle}>Frequent Breaks</span>
          <span style={panelCountStyle}>{fmtDateShort(from)} – {fmtDateShort(to)}</span>
        </div>
        {data.frequentBreaks.length === 0 ? (
          <div style={{ padding: '16px 18px', fontSize: 12.5, color: 'var(--txt-dim)' }}>No breaks recorded in this range.</div>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) 380px', gap: 0 }}>
            <div>{data.frequentBreaks.map(e => <FrequentBreaksRow key={e.employeeUserId} entry={e} />)}</div>
            <div style={{ borderLeft: '1px solid var(--line)' }}>
              <BreaksTrend data={data.breaksTrend} />
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/* ══ ONEHR-108: Bulk-Edit Team Shift, Weekly Off & Penalisation Policy Assignments ══ */
function BulkButton({ label, disabled, onClick }: { label: string; disabled: boolean; onClick: () => void }) {
  return (
    <button onClick={onClick} disabled={disabled} style={{
      fontSize: 11.5, fontWeight: 600, padding: '7px 12px', borderRadius: 6, cursor: disabled ? 'not-allowed' : 'pointer',
      border: '1px solid var(--line2)', background: disabled ? 'var(--raised2)' : 'var(--shell)', color: disabled ? 'var(--txt-dim)' : 'var(--txt)',
    }}>
      {label}
    </button>
  );
}

const assignmentCellStyle: React.CSSProperties = { padding: '8px 12px', borderBottom: '1px solid var(--line)', fontSize: 12, color: 'var(--txt-mut)' };

interface AssignmentActionResult {
  kind: 'bulk' | 'import';
  succeeded: number;
  failures: { label: string; reason: string }[];
}

function AssignmentsTab({ token }: { token: string }) {
  const { showToast } = useToast();
  const [lookups, setLookups] = useState<AssignmentLookups | null>(null);
  const [rows, setRows] = useState<EmployeeAssignmentRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [filters, setFilters] = useState<AssignmentFilters>({});
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkModal, setBulkModal] = useState<null | 'shift' | 'weeklyOff' | 'penalisation'>(null);
  const [bulkPickerValue, setBulkPickerValue] = useState('');
  const [bulkBusy, setBulkBusy] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importBusy, setImportBusy] = useState(false);
  const [lastResult, setLastResult] = useState<AssignmentActionResult | null>(null);

  useEffect(() => {
    employeeAssignmentsApi.lookups(token).then(setLookups).catch(() => {});
  }, [token]);

  const activeFilters = useMemo<AssignmentFilters>(() => ({ ...filters, search: search.trim() || undefined }), [filters, search]);

  function reload() {
    setLoading(true);
    return employeeAssignmentsApi.team(activeFilters, token)
      .then(r => { setRows(r); setSelected(new Set()); })
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Failed to load team assignments'))
      .finally(() => setLoading(false));
  }

  useEffect(() => { reload(); }, [token, filters, search]);

  function toggleSelect(id: string) {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }
  function toggleSelectAll() {
    setSelected(prev => prev.size === rows.length ? new Set() : new Set(rows.map(r => r.employeeUserId)));
  }

  async function applyBulk() {
    if (!bulkModal || !bulkPickerValue || selected.size === 0) return;
    setBulkBusy(true);
    try {
      const ids = Array.from(selected);
      const fn = bulkModal === 'shift' ? employeeAssignmentsApi.bulkUpdateShift
        : bulkModal === 'weeklyOff' ? employeeAssignmentsApi.bulkUpdateWeeklyOff
        : employeeAssignmentsApi.bulkUpdatePenalisationPolicy;
      const result = await fn(ids, bulkPickerValue, token);
      showToast(result.failed.length === 0 ? 'success' : 'error',
        `${result.succeededIds.length} updated${result.failed.length ? `, ${result.failed.length} failed` : ''}`);
      setLastResult({
        kind: 'bulk',
        succeeded: result.succeededIds.length,
        failures: result.failed.map(f => ({
          label: rows.find(r => r.employeeUserId === f.employeeUserId)?.fullName ?? f.employeeUserId,
          reason: f.reason,
        })),
      });
      setBulkModal(null);
      setBulkPickerValue('');
      await reload();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Bulk update failed');
    } finally {
      setBulkBusy(false);
    }
  }

  async function runImport() {
    if (!importFile) return;
    setImportBusy(true);
    try {
      const result = await employeeAssignmentsApi.import(importFile, token);
      showToast(result.failed === 0 ? 'success' : 'error', `${result.succeeded}/${result.totalRows} rows imported`);
      setLastResult({
        kind: 'import',
        succeeded: result.succeeded,
        failures: result.results.filter(r => !r.success)
          .map(r => ({ label: `Row ${r.row} (${r.employeeCode || '—'})`, reason: r.error ?? 'Unknown error' })),
      });
      setImportOpen(false);
      setImportFile(null);
      await reload();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Import failed');
    } finally {
      setImportBusy(false);
    }
  }

  const pickerOptions = bulkModal === 'shift' ? lookups?.shifts
    : bulkModal === 'weeklyOff' ? lookups?.weeklyOffPolicies
    : lookups?.penalisationPolicies;

  return (
    <div style={panelStyle}>
      <div style={panelHeadStyle}>
        <span style={panelTitleStyle}>Time Assignments</span>
        <span style={panelCountStyle}>{rows.length} {rows.length === 1 ? 'person' : 'people'}</span>
      </div>

      <div style={{ padding: '12px 18px', borderBottom: '1px solid var(--line)', display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'flex-end' }}>
        <div>
          <label style={labelStyle}>Shift</label>
          <select value={filters.shiftId ?? ''} onChange={e => setFilters(f => ({ ...f, shiftId: e.target.value || undefined }))} style={{ ...inputStyle, width: 'auto' }}>
            <option value="">All shifts</option>
            {lookups?.shifts.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </div>
        <div>
          <label style={labelStyle}>Weekly off</label>
          <select value={filters.weeklyOffPolicyId ?? ''} onChange={e => setFilters(f => ({ ...f, weeklyOffPolicyId: e.target.value || undefined }))} style={{ ...inputStyle, width: 'auto' }}>
            <option value="">All policies</option>
            {lookups?.weeklyOffPolicies.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
        <div>
          <label style={labelStyle}>Penalisation policy</label>
          <select value={filters.penalisationPolicyId ?? ''} onChange={e => setFilters(f => ({ ...f, penalisationPolicyId: e.target.value || undefined }))} style={{ ...inputStyle, width: 'auto' }}>
            <option value="">All policies</option>
            {lookups?.penalisationPolicies.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
        <div>
          <label style={labelStyle}>Department</label>
          <select value={filters.department ?? ''} onChange={e => setFilters(f => ({ ...f, department: e.target.value || undefined }))} style={{ ...inputStyle, width: 'auto' }}>
            <option value="">All departments</option>
            {lookups?.departments.map(d => <option key={d} value={d}>{d}</option>)}
          </select>
        </div>
        <div>
          <label style={labelStyle}>Location</label>
          <select value={filters.location ?? ''} onChange={e => setFilters(f => ({ ...f, location: e.target.value || undefined }))} style={{ ...inputStyle, width: 'auto' }}>
            <option value="">All locations</option>
            {lookups?.locations.map(l => <option key={l} value={l}>{l}</option>)}
          </select>
        </div>
        <div style={{ flex: 1, minWidth: 160, display: 'flex', alignItems: 'center', gap: 8, background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 7, padding: '7px 10px', color: 'var(--txt-dim)' }}>
          <Search size={13} />
          <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search employee…" style={{ flex: 1, background: 'none', border: 'none', outline: 'none', color: 'var(--txt)', fontSize: 12.5 }} />
        </div>
      </div>

      <div style={{ padding: '10px 18px', borderBottom: '1px solid var(--line)', display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <BulkButton label="Update Shift" disabled={selected.size === 0} onClick={() => { setBulkModal('shift'); setBulkPickerValue(''); }} />
        <BulkButton label="Update Weekly Off" disabled={selected.size === 0} onClick={() => { setBulkModal('weeklyOff'); setBulkPickerValue(''); }} />
        <BulkButton label="Update Penalisation Policy" disabled={selected.size === 0} onClick={() => { setBulkModal('penalisation'); setBulkPickerValue(''); }} />
        <span style={{ fontSize: 11.5, color: 'var(--txt-mut)' }}>
          Total: <b style={{ color: 'var(--txt)' }}>{rows.length}</b>{selected.size > 0 && <> · {selected.size} selected</>}
        </span>
        <div style={{ flex: 1 }} />
        <button onClick={() => setImportOpen(true)} style={{ fontSize: 11.5, fontWeight: 600, padding: '7px 12px', borderRadius: 6, cursor: 'pointer', border: '1px solid transparent', background: 'var(--brand)', color: '#fff' }}>
          Import Shifts &amp; Weekly Offs
        </button>
      </div>

      {lastResult && (
        <div style={{ padding: '10px 18px', borderBottom: '1px solid var(--line)', background: lastResult.failures.length ? 'rgba(228,55,61,.08)' : 'rgba(47,182,124,.08)' }}>
          <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt)', marginBottom: lastResult.failures.length ? 6 : 0 }}>
            {lastResult.kind === 'import' ? 'Import' : 'Bulk update'} — {lastResult.succeeded} succeeded{lastResult.failures.length ? `, ${lastResult.failures.length} failed` : ''}
          </div>
          {lastResult.failures.length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
              {lastResult.failures.map((f, i) => (
                <div key={i} style={{ fontSize: 11.5, color: 'var(--risk)' }}>{f.label}: {f.reason}</div>
              ))}
            </div>
          )}
        </div>
      )}

      <div style={{ overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              <th style={{ padding: '8px 12px', textAlign: 'left' }}>
                <input type="checkbox" checked={rows.length > 0 && selected.size === rows.length} onChange={toggleSelectAll} />
              </th>
              {['Employee', 'Employee number', 'Department', 'Location', 'Shift type', 'Weekly off', 'Penalisation policy'].map(h => (
                <th key={h} style={{ padding: '8px 12px', textAlign: 'left', fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={8} style={{ padding: '16px 18px', fontSize: 12.5, color: 'var(--txt-dim)' }}>Loading…</td></tr>
            ) : rows.length === 0 ? (
              <tr><td colSpan={8} style={{ padding: '16px 18px', fontSize: 12.5, color: 'var(--txt-dim)' }}>No one matches these filters.</td></tr>
            ) : rows.map(r => (
              <tr key={r.employeeUserId}>
                <td style={{ padding: '8px 12px', borderBottom: '1px solid var(--line)' }}>
                  <input type="checkbox" checked={selected.has(r.employeeUserId)} onChange={() => toggleSelect(r.employeeUserId)} />
                </td>
                <td style={{ padding: '8px 12px', borderBottom: '1px solid var(--line)' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <Avatar name={r.fullName} size={26} />
                    <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{r.fullName}</span>
                  </div>
                </td>
                <td style={assignmentCellStyle}>{r.employeeCode}</td>
                <td style={assignmentCellStyle}>{r.departmentName ?? '—'}</td>
                <td style={assignmentCellStyle}>{r.locationName ?? '—'}</td>
                <td style={assignmentCellStyle}>{r.shiftName ?? '—'}</td>
                <td style={assignmentCellStyle}>{r.weeklyOffPolicyName ?? '—'}</td>
                <td style={assignmentCellStyle}>{r.penalisationPolicyName ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {bulkModal && (
        <div style={overlayStyle} onClick={() => !bulkBusy && setBulkModal(null)}>
          <div style={{ ...modalStyle, maxWidth: 380 }} onClick={e => e.stopPropagation()}>
            <div style={{ padding: 18, borderBottom: '1px solid var(--line)', fontWeight: 700, fontFamily: '"Space Grotesk", sans-serif', color: 'var(--txt)' }}>
              {bulkModal === 'shift' ? 'Update Shift' : bulkModal === 'weeklyOff' ? 'Update Weekly Off' : 'Update Penalisation Policy'}
            </div>
            <div style={{ padding: 18 }}>
              <label style={labelStyle}>New value for {selected.size} selected {selected.size === 1 ? 'employee' : 'employees'}</label>
              <select value={bulkPickerValue} onChange={e => setBulkPickerValue(e.target.value)} style={inputStyle}>
                <option value="">Select…</option>
                {pickerOptions?.map(o => <option key={o.id} value={o.id}>{o.name}</option>)}
              </select>
            </div>
            <div style={{ padding: 18, display: 'flex', gap: 8, borderTop: '1px solid var(--line)' }}>
              <button onClick={() => setBulkModal(null)} disabled={bulkBusy} style={{ flex: 1, fontSize: 12.5, fontWeight: 600, padding: '9px', borderRadius: 6, cursor: 'pointer', border: '1px solid var(--line2)', background: 'var(--raised2)', color: 'var(--txt-mut)' }}>Cancel</button>
              <button onClick={applyBulk} disabled={!bulkPickerValue || bulkBusy} style={{ flex: 1, fontSize: 12.5, fontWeight: 600, padding: '9px', borderRadius: 6, cursor: !bulkPickerValue || bulkBusy ? 'not-allowed' : 'pointer', border: 'none', background: 'var(--brand)', color: '#fff' }}>
                {bulkBusy ? 'Applying…' : 'Apply'}
              </button>
            </div>
          </div>
        </div>
      )}

      {importOpen && (
        <div style={overlayStyle} onClick={() => !importBusy && setImportOpen(false)}>
          <div style={{ ...modalStyle, maxWidth: 420 }} onClick={e => e.stopPropagation()}>
            <div style={{ padding: 18, borderBottom: '1px solid var(--line)', fontWeight: 700, fontFamily: '"Space Grotesk", sans-serif', color: 'var(--txt)' }}>Import Shifts &amp; Weekly Offs</div>
            <div style={{ padding: 18, display: 'flex', flexDirection: 'column', gap: 10 }}>
              <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
                CSV with columns <code>employee_code,shift_name,weekly_off_policy_name</code>. Leave a cell blank to leave that field untouched.
              </div>
              <input type="file" accept=".csv,text/csv" onChange={e => setImportFile(e.target.files?.[0] ?? null)} style={{ fontSize: 12.5, color: 'var(--txt)' }} />
            </div>
            <div style={{ padding: 18, display: 'flex', gap: 8, borderTop: '1px solid var(--line)' }}>
              <button onClick={() => setImportOpen(false)} disabled={importBusy} style={{ flex: 1, fontSize: 12.5, fontWeight: 600, padding: '9px', borderRadius: 6, cursor: 'pointer', border: '1px solid var(--line2)', background: 'var(--raised2)', color: 'var(--txt-mut)' }}>Cancel</button>
              <button onClick={runImport} disabled={!importFile || importBusy} style={{ flex: 1, fontSize: 12.5, fontWeight: 600, padding: '9px', borderRadius: 6, cursor: !importFile || importBusy ? 'not-allowed' : 'pointer', border: 'none', background: 'var(--brand)', color: '#fff' }}>
                {importBusy ? 'Importing…' : 'Import'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/* ══ ONEHR-109: Attendance Request Reports Library ══ */
interface ReportCardDef {
  key: string;
  title: string;
  description: string;
  reportType?: AttendanceRequestReportType; // present only for the 4 real, data-backed cards
  blockedReason?: string; // present only for the 6 stubbed cards — never silently empty (AC #5)
}

const REPORT_CARDS: ReportCardDef[] = [
  { key: 'reg-summary', title: 'Attendance Regularizations Summary', description: 'Summary of attendance adjustment and regularization requests made by employees.', reportType: 'REGULARIZATION' },
  { key: 'mobile-location', title: 'Mobile Location Punches', description: "Details of employees' location punches along with coordinates.", blockedReason: 'Requires GPS/coordinate punch tracking — not yet built.' },
  { key: 'overtime', title: 'Overtime Requests', description: 'Summary of overtime requests made by employees.', blockedReason: 'Requires Overtime tracking — not yet built.' },
  { key: 'partial-day', title: 'Partial Day Requests', description: 'Summary of partial day requests made by employees.', blockedReason: 'Requires Partial Day requests — not yet built.' },
  { key: 'remote-summary', title: 'Remote Clock-in Requests Summary', description: 'Summary of remote clock-ins and outs requests made by employees.', reportType: 'WEB_CLOCK_IN' },
  { key: 'remote-clockins', title: 'Remote Clock-ins', description: "Details of employees' remote punch (In/Out) along with coordinates.", reportType: 'WEB_CLOCK_IN' },
  { key: 'shift-weeklyoff', title: 'Shift & Weekly Off Requests', description: 'Summary of shift/weekly off requests made by employees.', blockedReason: 'Requires Shift & Weekly Off as a request workflow — not yet built (see the Employee Assignments tab for static assignments).' },
  { key: 'web-clockins', title: 'Web Clock-ins', description: "Details of employees' web clock-ins along with IP address.", reportType: 'WEB_CLOCK_IN' },
  { key: 'web-clockins-forgot', title: 'Web Clock-ins (includes Forgot ID requests)', description: 'Summary of web clock-ins done by employees.', blockedReason: 'Requires a Forgot ID flag on web clock-ins — not yet built.' },
  { key: 'wfh-od', title: 'Working Remotely (WFH/OD) Requests', description: 'Summary of WFH/OD requests made by employees.', blockedReason: 'Requires WFH/OD as a request workflow — not yet built (only a static work-mode profile field exists today).' },
];

const REPORT_CATEGORIES = ['Reports Home', 'Attendance Request Reports', 'Attendance Reports', 'Punctuality Reports', 'Negligence Reports', 'Scheduled reports'];

function ReportRunModal({ card, token, onClose }: { card: ReportCardDef; token: string; onClose: () => void }) {
  const { showToast } = useToast();
  const { from, setFrom, to, setTo } = useTeamDateRange(30);
  const [rows, setRows] = useState<AttendanceRequestReportRow[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);

  async function run() {
    if (!card.reportType) return;
    setLoading(true);
    try {
      setRows(await reportsApi.attendanceRequests(card.reportType, from, to, token));
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to run report');
    } finally {
      setLoading(false);
    }
  }

  async function exportCsv() {
    if (!card.reportType) return;
    setExporting(true);
    try {
      await reportsApi.exportAttendanceRequests(card.reportType, from, to, token);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Export failed');
    } finally {
      setExporting(false);
    }
  }

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { run(); }, []);

  return (
    <div style={overlayStyle} onClick={onClose}>
      <div style={{ ...modalStyle, maxWidth: 640 }} onClick={e => e.stopPropagation()}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: 18, borderBottom: '1px solid var(--line)' }}>
          <span style={{ fontWeight: 700, fontFamily: '"Space Grotesk", sans-serif', color: 'var(--txt)' }}>{card.title}</span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', padding: 5, borderRadius: 6 }}><X size={16} /></button>
        </div>
        <div style={{ padding: '12px 18px', borderBottom: '1px solid var(--line)', display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <input type="date" value={from} max={to} onChange={e => setFrom(e.target.value)} style={{ ...inputStyle, width: 'auto', padding: '6px 9px' }} />
          <span style={{ color: 'var(--txt-dim)' }}>–</span>
          <input type="date" value={to} min={from} max={todayIsoDate()} onChange={e => setTo(e.target.value)} style={{ ...inputStyle, width: 'auto', padding: '6px 9px' }} />
          <button onClick={run} disabled={loading} style={{ fontSize: 12, fontWeight: 600, padding: '7px 12px', borderRadius: 6, cursor: loading ? 'not-allowed' : 'pointer', border: '1px solid var(--line2)', background: 'var(--shell)', color: 'var(--txt)' }}>
            {loading ? 'Running…' : 'Run'}
          </button>
          <div style={{ flex: 1 }} />
          <button onClick={exportCsv} disabled={exporting || !rows || rows.length === 0} style={{ fontSize: 12, fontWeight: 600, padding: '7px 12px', borderRadius: 6, cursor: exporting || !rows?.length ? 'not-allowed' : 'pointer', border: 'none', background: 'var(--brand)', color: '#fff' }}>
            {exporting ? 'Exporting…' : 'Export CSV'}
          </button>
        </div>
        <div style={{ maxHeight: 360, overflowY: 'auto' }}>
          {loading ? (
            <div style={{ padding: 18, fontSize: 12.5, color: 'var(--txt-dim)' }}>Loading…</div>
          ) : !rows || rows.length === 0 ? (
            <div style={{ padding: 18, fontSize: 12.5, color: 'var(--txt-dim)' }}>No requests in this range.</div>
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Employee', 'Date', 'Check In', 'Check Out', 'Status'].map(h => (
                    <th key={h} style={{ padding: '8px 12px', textAlign: 'left', fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', borderBottom: '1px solid var(--line)' }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={i}>
                    <td style={assignmentCellStyle}>{r.fullName ?? '—'}</td>
                    <td style={assignmentCellStyle}>{fmtDateShort(r.date)}</td>
                    <td style={assignmentCellStyle}>{fmtTime(r.checkIn)}</td>
                    <td style={assignmentCellStyle}>{fmtTime(r.checkOut)}</td>
                    <td style={assignmentCellStyle}>{r.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}

function ReportsTab({ token }: { token: string }) {
  const { showToast } = useToast();
  const [category, setCategory] = useState('Attendance Request Reports');
  const [search, setSearch] = useState('');
  const [runningCard, setRunningCard] = useState<ReportCardDef | null>(null);

  const q = search.trim().toLowerCase();
  const filteredCards = category === 'Attendance Request Reports'
    ? REPORT_CARDS.filter(c => !q || c.title.toLowerCase().includes(q))
    : [];

  function openCard(card: ReportCardDef) {
    if (!card.reportType) {
      showToast('error', card.blockedReason ?? 'Not available yet.');
      return;
    }
    setRunningCard(card);
  }

  return (
    <div style={panelStyle}>
      <div style={{ display: 'grid', gridTemplateColumns: '220px minmax(0,1fr)' }}>
        <div style={{ borderRight: '1px solid var(--line)', padding: '14px 10px' }}>
          {REPORT_CATEGORIES.map(cat => (
            <div key={cat} onClick={() => setCategory(cat)} style={{
              padding: '9px 12px', borderRadius: 6, cursor: 'pointer', fontSize: 12.5, fontWeight: 600, marginBottom: 2,
              background: category === cat ? 'var(--brand)' : 'transparent', color: category === cat ? '#fff' : 'var(--txt-mut)',
            }}>
              {cat}
            </div>
          ))}
        </div>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10, padding: '14px 18px', borderBottom: '1px solid var(--line)', flexWrap: 'wrap' }}>
            <span style={panelTitleStyle}>{category}</span>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 7, padding: '7px 10px', color: 'var(--txt-dim)' }}>
              <Search size={13} />
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search reports…" style={{ background: 'none', border: 'none', outline: 'none', color: 'var(--txt)', fontSize: 12.5 }} />
            </div>
          </div>
          <div style={{ padding: 18 }}>
            {category !== 'Attendance Request Reports' ? (
              <div style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>Not available yet.</div>
            ) : filteredCards.length === 0 ? (
              <div style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>No reports match your search.</div>
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 12 }}>
                {filteredCards.map(c => (
                  <div key={c.key} onClick={() => openCard(c)} style={{
                    background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 10, padding: 14,
                    cursor: 'pointer', opacity: c.reportType ? 1 : 0.55, position: 'relative',
                  }}>
                    {!c.reportType && (
                      <span style={{ position: 'absolute', top: 10, right: 10, fontSize: 9.5, fontWeight: 700, padding: '2px 7px', borderRadius: 20, background: 'var(--raised2)', color: 'var(--txt-dim)', whiteSpace: 'nowrap' }}>
                        Coming soon
                      </span>
                    )}
                    <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 5, paddingRight: c.reportType ? 0 : 78 }}>{c.title}</div>
                    <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', lineHeight: 1.4 }}>{c.description}</div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
      {runningCard && <ReportRunModal card={runningCard} token={token} onClose={() => setRunningCard(null)} />}
    </div>
  );
}

export default function MyTeamPage() {
  const token = useAuthStore(s => s.token)!;
  const today = todayIsoDate();
  const [searchParams] = useSearchParams();
  const rosterRef = useRef<HTMLDivElement>(null);

  const [tab, setTab] = useState<'overview' | 'effort' | 'negligence' | 'assignments' | 'reports'>('overview');

  const [directReports, setDirectReports] = useState<DirectReport[]>([]);
  const [directReportCount, setDirectReportCount] = useState(0);
  const [todayRecords, setTodayRecords] = useState<AttendanceRecord[]>([]);
  const [todayLeave, setTodayLeave] = useState<LeaveRequestRecord[]>([]);
  const [weekLeave, setWeekLeave] = useState<LeaveRequestRecord[]>([]);
  const [pendingItems, setPendingItems] = useState<ApprovalItem[]>([]);
  const [holidays, setHolidays] = useState<HolidayRow[]>([]);
  const [loading, setLoading] = useState(true);

  const [viewDate, setViewDate] = useState(() => { const t = new Date(); return new Date(t.getFullYear(), t.getMonth(), 1); });
  const [monthAttendance, setMonthAttendance] = useState<AttendanceRecord[]>([]);
  const [monthLeave, setMonthLeave] = useState<LeaveRequestRecord[]>([]);

  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<'all' | 'IN' | 'OUT' | 'NOT_IN_YET' | 'LEAVE'>(() => {
    const s = searchParams.get('status');
    return (['all', 'IN', 'OUT', 'NOT_IN_YET', 'LEAVE'] as string[]).includes(s ?? '') ? (s as any) : 'all';
  });
  const [viewing, setViewing] = useState<RosterRow | null>(null);

  // Landed here via a dashboard link (e.g. "On Leave" KPI) — scroll straight to the roster
  // instead of leaving the pre-applied filter buried further down the page.
  useEffect(() => {
    if (searchParams.get('status') === 'LEAVE') rosterRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- run once on mount only
  }, []);

  useEffect(() => {
    dashboardApi.managerDashboard(token)
      .then(d => { setDirectReports(d.directReports.filter(r => r.active)); setDirectReportCount(d.directReportCount); })
      .catch(() => {});
  }, [token]);

  useEffect(() => {
    attendanceApi.team(today, token).then(setTodayRecords).catch(() => setTodayRecords([])).finally(() => setLoading(false));
  }, [token, today]);

  useEffect(() => {
    leaveApi.team(today, today, token).then(setTodayLeave).catch(() => setTodayLeave([]));
  }, [token, today]);

  const weekStart = useMemo(() => mondayOf(new Date()), []);
  const weekEnd = useMemo(() => addDays(weekStart, 4), [weekStart]);

  useEffect(() => {
    leaveApi.team(toISO(weekStart), toISO(weekEnd), token).then(setWeekLeave).catch(() => setWeekLeave([]));
  }, [token, weekStart, weekEnd]);

  useEffect(() => {
    approvalCenterApi.listPending(token).then(setPendingItems).catch(() => setPendingItems([]));
  }, [token]);

  useEffect(() => {
    holidaysApi.listForMyLocation(token).then(setHolidays).catch(() => setHolidays([]));
  }, [token]);

  useEffect(() => {
    const year = viewDate.getFullYear(), month = viewDate.getMonth();
    const from = toISODate(year, month, 1);
    const to = toISODate(year, month, daysInMonth(year, month));
    Promise.all([
      attendanceApi.teamMonth(from, to, token).catch(() => []),
      leaveApi.team(from, to, token).catch(() => []),
    ]).then(([att, lv]) => { setMonthAttendance(att); setMonthLeave(lv); });
  }, [token, viewDate]);

  const attendanceByEmployee = useMemo(() => new Map(todayRecords.map(r => [r.employeeUserId, r])), [todayRecords]);
  const onLeaveToday = useMemo(() => new Map(todayLeave.map(l => [l.employeeUserId, l])), [todayLeave]);
  const attentionItems = useMemo(() => pendingItems.filter(i => i.requestType === 'LEAVE' || i.requestType === 'REGULARIZATION'), [pendingItems]);
  const requestsByEmployee = useMemo(() => {
    const m = new Map<string, ApprovalItem[]>();
    attentionItems.forEach(i => { const arr = m.get(i.employeeUserId) ?? []; arr.push(i); m.set(i.employeeUserId, arr); });
    return m;
  }, [attentionItems]);

  const rosterRows: RosterRow[] = useMemo(() => directReports.map(dr => {
    const record = attendanceByEmployee.get(dr.userId);
    const onLeave = onLeaveToday.get(dr.userId);
    const status: RosterStatus = onLeave ? 'LEAVE' : !record?.checkInAt ? 'NOT_IN_YET' : !record.checkOutAt ? 'IN' : 'OUT';
    return {
      dr, record, status,
      isLate: record?.status === 'LATE',
      requests: requestsByEmployee.get(dr.userId) ?? [],
      leaveTypeName: onLeave?.leaveTypeName,
    };
  }), [directReports, attendanceByEmployee, onLeaveToday, requestsByEmployee]);

  const notInYet = rosterRows.filter(r => r.status === 'NOT_IN_YET');
  const onTimeCount = todayRecords.filter(r => r.status === 'PRESENT').length;
  const lateCount = todayRecords.filter(r => r.status === 'LATE').length;
  const remoteClockInCount = todayRecords.filter(r => r.source === 'WEB_REMOTE').length;
  const wfhOnDutyCount = new Set(todayRecords.filter(r => r.checkInAt && ((r.workMode && r.workMode !== 'ONSITE') || r.source === 'WEB_REMOTE')).map(r => r.employeeUserId)).size;

  const filteredRoster = rosterRows.filter(r => {
    const matchesFilter = statusFilter === 'all' || r.status === statusFilter;
    const q = search.trim().toLowerCase();
    const matchesSearch = !q || r.dr.fullName.toLowerCase().includes(q) || r.dr.employeeCode.toLowerCase().includes(q);
    return matchesFilter && matchesSearch;
  });

  function removeAttentionItem(id: string) {
    setPendingItems(prev => prev.filter(i => i.id !== id));
  }

  // ── Calendar data ──
  const year = viewDate.getFullYear(), month = viewDate.getMonth();
  const totalDays = daysInMonth(year, month);
  const holidaySet = useMemo(() => new Set(holidays.map(h => h.holidayDate)), [holidays]);
  const monthAttByKey = useMemo(() => {
    const m = new Map<string, AttendanceRecord>();
    monthAttendance.forEach(r => m.set(`${r.employeeUserId}:${r.workDate}`, r));
    return m;
  }, [monthAttendance]);

  function classifyDay(iso: string, dow: number, employeeUserId: string): DayCategory {
    if (holidaySet.has(iso)) return 'holiday';
    if (dow === 0 || dow === 6) return 'weekly-off';
    const onLeave = monthLeave.some(l => l.employeeUserId === employeeUserId && iso >= l.startDate && iso <= l.endDate);
    if (onLeave) return 'leave';
    const record = monthAttByKey.get(`${employeeUserId}:${iso}`);
    if (record) return (record.workMode && record.workMode !== 'ONSITE') || record.source === 'WEB_REMOTE' ? 'wfh' : 'plain';
    if (iso >= today) return 'plain';
    return 'missing';
  }

  // ── Out this week ──
  const weekDayDates = [0, 1, 2, 3, 4].map(i => addDays(weekStart, i));

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 16, marginBottom: 20, flexWrap: 'wrap' }}>
        <div>
          <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 22, fontWeight: 700, color: 'var(--txt)', margin: '0 0 4px' }}>My Team</h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', margin: 0, maxWidth: '62ch' }}>Attendance, leave, and open requests for your direct reports — one place, so you don't have to check four separate pages to know how your team is doing today.</p>
        </div>
      </div>

      {/* Sub-tabs — Overview is the original page; the rest are ONEHR-106/107/108/109. */}
      <div style={{ display: 'flex', gap: 6, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, padding: 4, width: 'fit-content', marginBottom: 20, flexWrap: 'wrap' }}>
        {([
          ['overview', 'Overview'],
          ['effort', 'Efforts / Punctuality'],
          ['negligence', 'Negligence'],
          ['assignments', 'Employee Assignments'],
          ['reports', 'Reports'],
        ] as const).map(([key, label]) => (
          <button key={key} onClick={() => setTab(key)} style={{
            padding: '8px 14px', border: 'none', borderRadius: 6, cursor: 'pointer', fontWeight: 600, fontSize: 12.5,
            background: tab === key ? 'var(--brand)' : 'transparent', color: tab === key ? '#fff' : 'var(--txt-dim)', whiteSpace: 'nowrap',
          }}>
            {label}
          </button>
        ))}
      </div>

      {tab === 'overview' && (<>
      {/* Who's on leave / Not in yet */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 20 }}>
        <div style={panelStyle}>
          <div style={panelHeadStyle}>
            <span style={panelTitleStyle}>Who's on leave today</span>
            <span style={panelCountStyle}>{todayLeave.length} {todayLeave.length === 1 ? 'person' : 'people'}</span>
          </div>
          {todayLeave.length === 0 ? (
            <div style={{ padding: '16px 18px', fontSize: 12.5, color: 'var(--txt-dim)' }}>No one on your team is on leave today.</div>
          ) : (
            todayLeave.map(l => (
              <div key={l.id} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '11px 18px', borderBottom: '1px solid var(--line)' }}>
                <Avatar name={l.employeeName} size={30} />
                <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', flex: 1 }}>{l.employeeName}</span>
                <span style={{ fontSize: 11.5, fontWeight: 600, padding: '4px 9px', borderRadius: 20, background: 'rgba(99,102,241,.18)', color: '#818CF8' }}>{l.leaveTypeName}</span>
              </div>
            ))
          )}
        </div>
        <div style={panelStyle}>
          <div style={panelHeadStyle}>
            <span style={panelTitleStyle}>Not in yet today</span>
            <span style={panelCountStyle}>{notInYet.length} {notInYet.length === 1 ? 'person' : 'people'}</span>
          </div>
          {loading ? (
            <div style={{ padding: '16px 18px', fontSize: 12.5, color: 'var(--txt-dim)' }}>Loading…</div>
          ) : notInYet.length === 0 ? (
            <div style={{ padding: '16px 18px', fontSize: 12.5, color: 'var(--txt-dim)' }}>Everyone has checked in.</div>
          ) : (
            notInYet.map(r => (
              <div key={r.dr.userId} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '11px 18px', borderBottom: '1px solid var(--line)' }}>
                <Avatar name={r.dr.fullName} size={30} />
                <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', flex: 1 }}>{r.dr.fullName}</span>
              </div>
            ))
          )}
        </div>
      </div>

      {/* KPI row */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 20 }}>
        <KpiCard icon={<Users size={14} />} iconColor="var(--brand-bright)" label="Team size" value={directReportCount} note="direct reports" />
        <KpiCard icon={<CheckCircle2 size={14} />} iconColor="var(--ok)" label="Employees on time" value={loading ? '—' : onTimeCount} note="arrived on schedule" />
        <KpiCard icon={<Clock size={14} />} iconColor="var(--warn)" label="Late arrivals" value={loading ? '—' : lateCount} note={todayRecords.find(r => r.status === 'LATE')?.fullName ?? 'none today'} />
        <KpiCard icon={<Home size={14} />} iconColor="var(--info)" label="WFH / On duty" value={loading ? '—' : wfhOnDutyCount} note="remote or hybrid today" />
        <KpiCard icon={<MapPin size={14} />} iconColor="var(--txt-mut)" label="Remote clock-ins" value={loading ? '—' : remoteClockInCount} note="via Web Clock-In today" />
        <KpiCard icon={<AlertTriangle size={14} />} iconColor="var(--brand-bright)" label="Needs your attention" value={attentionItems.length} note="pending leave & regularization requests" />
      </div>

      {/* Team calendar */}
      <div style={{ ...panelStyle, marginBottom: 16 }}>
        <div style={panelHeadStyle}>
          <span style={panelTitleStyle}>Team calendar</span>
          <span style={panelCountStyle}>{directReports.length} {directReports.length === 1 ? 'person' : 'people'}</span>
        </div>
        <div style={{ padding: '12px 18px', borderBottom: '1px solid var(--line)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 8, padding: 4 }}>
            <button onClick={() => setViewDate(new Date(year, month - 1, 1))} aria-label="Previous month" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', padding: 5, borderRadius: 6, display: 'flex' }}><ChevronLeft size={14} /></button>
            <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', padding: '0 8px', whiteSpace: 'nowrap' }}>{viewDate.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })}</span>
            <button onClick={() => setViewDate(new Date(year, month + 1, 1))} aria-label="Next month" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', padding: 5, borderRadius: 6, display: 'flex' }}><ChevronRight size={14} /></button>
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, fontSize: 10.5, color: 'var(--txt-mut)' }}>
            {(['holiday', 'weekly-off', 'leave', 'wfh', 'missing'] as const).map(cat => (
              <span key={cat} style={{ display: 'flex', alignItems: 'center', gap: 5, whiteSpace: 'nowrap' }}>
                <span style={{ width: 10, height: 10, borderRadius: 3, flexShrink: 0, background: DAY_COLORS[cat] }} />
                {cat === 'holiday' ? 'Holiday' : cat === 'weekly-off' ? 'Weekly off' : cat === 'leave' ? 'On leave' : cat === 'wfh' ? 'WFH / On duty' : 'Missing attendance'}
              </span>
            ))}
          </div>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <table style={{ borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th style={{ position: 'sticky', left: 0, zIndex: 2, textAlign: 'left', padding: '7px 18px', minWidth: 168, background: 'var(--raised)', fontSize: 9.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', borderBottom: '1px solid var(--line)' }}>Team member</th>
                {Array.from({ length: totalDays }, (_, i) => i + 1).map(d => {
                  const iso = toISODate(year, month, d);
                  const isToday = iso === today;
                  return (
                    <th key={d} style={{ padding: '7px 3px', fontSize: 9.5, fontWeight: 700, color: isToday ? 'var(--brand-bright)' : 'var(--txt-dim)', textAlign: 'center', borderBottom: '1px solid var(--line)', background: 'var(--raised)', textTransform: 'uppercase' }}>
                      {WEEKDAY_LABELS[new Date(year, month, d).getDay()]}
                    </th>
                  );
                })}
              </tr>
            </thead>
            <tbody>
              {directReports.map(dr => (
                <tr key={dr.userId}>
                  <td style={{ position: 'sticky', left: 0, background: 'var(--panel)', zIndex: 1, padding: '6px 18px', textAlign: 'left', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <Avatar name={dr.fullName} size={24} />
                      <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt)' }}>{dr.fullName}</span>
                    </div>
                  </td>
                  {Array.from({ length: totalDays }, (_, i) => i + 1).map(d => {
                    const iso = toISODate(year, month, d);
                    const dow = new Date(year, month, d).getDay();
                    const category = classifyDay(iso, dow, dr.userId);
                    const isToday = iso === today;
                    return (
                      <td key={d} style={{ padding: 3, textAlign: 'center', borderBottom: '1px solid var(--line)' }}>
                        <div style={{
                          width: 24, height: 24, borderRadius: '50%', display: 'grid', placeItems: 'center', margin: '0 auto',
                          fontSize: 10, fontWeight: 600,
                          background: category === 'plain' ? 'transparent' : DAY_COLORS[category],
                          color: category === 'plain' ? 'var(--txt-dim)' : '#fff',
                          boxShadow: isToday ? '0 0 0 2px var(--brand-bright)' : 'none',
                        }}>
                          {d}
                        </div>
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 340px', gap: 16, alignItems: 'flex-start' }}>
        {/* Roster */}
        <div ref={rosterRef} style={panelStyle}>
          <div style={panelHeadStyle}>
            <span style={panelTitleStyle}>Team roster</span>
            <span style={panelCountStyle}>{filteredRoster.length} {filteredRoster.length === 1 ? 'person' : 'people'}</span>
          </div>
          <div style={{ padding: '12px 18px', borderBottom: '1px solid var(--line)', display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
            <div style={{ flex: 1, minWidth: 160, display: 'flex', alignItems: 'center', gap: 8, background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 7, padding: '7px 10px', color: 'var(--txt-dim)' }}>
              <Search size={13} />
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search by name or employee code…" style={{ flex: 1, background: 'none', border: 'none', outline: 'none', color: 'var(--txt)', fontSize: 12.5 }} />
            </div>
            {(['all', 'IN', 'OUT', 'NOT_IN_YET', 'LEAVE'] as const).map(f => (
              <button key={f} onClick={() => setStatusFilter(f)} style={{
                fontSize: 11.5, fontWeight: 600, padding: '6px 11px', borderRadius: 20, border: '1px solid var(--line2)', cursor: 'pointer', whiteSpace: 'nowrap',
                background: statusFilter === f ? 'var(--brand)' : 'var(--shell)', color: statusFilter === f ? '#fff' : 'var(--txt-mut)',
                borderColor: statusFilter === f ? 'var(--brand)' : 'var(--line2)',
              }}>
                {f === 'all' ? 'All' : f === 'NOT_IN_YET' ? 'Not in yet' : f === 'LEAVE' ? 'On leave' : f === 'IN' ? 'In' : 'Out'}
              </button>
            ))}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 10, padding: '14px 18px' }}>
            {loading ? (
              <div style={{ padding: '20px 0', color: 'var(--txt-dim)', fontSize: 12.5 }}>Loading…</div>
            ) : filteredRoster.length === 0 ? (
              <div style={{ padding: '20px 0', color: 'var(--txt-dim)', fontSize: 12.5 }}>No one matches this filter.</div>
            ) : filteredRoster.map(row => (
              <div key={row.dr.userId} style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 10, padding: '13px 14px', display: 'flex', flexDirection: 'column', gap: 9 }}>
                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
                  <Avatar name={row.dr.fullName} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ color: 'var(--txt)', fontWeight: 600, fontSize: 13 }}>{row.dr.fullName}</div>
                    <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 1 }}>{row.dr.designationName ?? '—'}</div>
                    <div style={{ color: 'var(--txt-dim)', fontSize: 10.5, fontFamily: '"JetBrains Mono", monospace', marginTop: 2 }}>{row.dr.employeeCode}</div>
                  </div>
                </div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5, minHeight: 22 }}>
                  <StatusPill status={row.status} />
                  {row.isLate && (
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11.5, fontWeight: 600, padding: '4px 9px 4px 7px', borderRadius: 20, background: 'rgba(224,169,59,.16)', color: 'var(--warn)' }}>
                      <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--warn)' }} />Late
                    </span>
                  )}
                  {row.status === 'LEAVE' && row.leaveTypeName && (
                    <span style={{ fontSize: 11.5, fontWeight: 600, padding: '4px 9px', borderRadius: 20, background: 'rgba(99,102,241,.18)', color: '#818CF8' }}>{row.leaveTypeName}</span>
                  )}
                </div>
                {row.requests.length > 0 && (
                  <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
                    {row.requests.map(r => <TypeBadge key={`${r.requestType}:${r.id}`} type={r.requestType as 'LEAVE' | 'REGULARIZATION'} />)}
                  </div>
                )}
                <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                  <button onClick={() => setViewing(row)} style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)', background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '5px 10px', cursor: 'pointer' }}>View</button>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Right column */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div style={panelStyle}>
            <div style={panelHeadStyle}>
              <span style={panelTitleStyle}>Needs your attention</span>
              <span style={panelCountStyle}>{attentionItems.length} pending</span>
            </div>
            {attentionItems.length === 0 ? (
              <div style={{ padding: '16px 18px', fontSize: 12.5, color: 'var(--txt-dim)' }}>Nothing pending right now.</div>
            ) : (
              attentionItems.map(item => (
                <AttentionQueueItem key={`${item.requestType}:${item.id}`} item={item} token={token} onDone={removeAttentionItem} />
              ))
            )}
          </div>

          <div style={panelStyle}>
            <div style={panelHeadStyle}>
              <span style={panelTitleStyle}>Out this week</span>
            </div>
            <div style={{ padding: '14px 18px', display: 'flex', flexDirection: 'column', gap: 12 }}>
              {weekLeave.length === 0 ? (
                <div style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>No one on your team is out this week.</div>
              ) : weekLeave.map(l => (
                <div key={l.id} style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{l.employeeName}</div>
                    <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{l.leaveTypeName}</div>
                  </div>
                  <div style={{ display: 'flex', gap: 3, flexShrink: 0 }}>
                    {weekDayDates.map((d, i) => {
                      const on = toISO(d) >= l.startDate && toISO(d) <= l.endDate;
                      return (
                        <div key={i} style={{ width: 18, height: 18, borderRadius: 4, display: 'grid', placeItems: 'center', fontSize: 8.5, fontWeight: 700, background: on ? 'var(--info)' : 'var(--raised2)', color: on ? '#fff' : 'var(--txt-dim)' }}>
                          {WEEK_CHIPS[i]}
                        </div>
                      );
                    })}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {viewing && <EmployeeDetailModal row={viewing} onClose={() => setViewing(null)} />}
      </>)}

      {tab === 'effort' && <EffortTab token={token} />}
      {tab === 'negligence' && <NegligenceTab token={token} />}
      {tab === 'assignments' && <AssignmentsTab token={token} />}
      {tab === 'reports' && <ReportsTab token={token} />}
    </div>
  );
}

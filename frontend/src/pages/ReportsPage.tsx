import { useCallback, useEffect, useMemo, useState } from 'react';
import PageTitleBlock from '../components/PageTitleBlock';
import DashTile from '../components/DashTile';
import { Users, CalendarCheck, Clock, Download, ShieldAlert, BarChart3, X } from 'lucide-react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  PieChart, Pie, Cell, Legend,
} from 'recharts';
import * as XLSX from 'xlsx';
import { useAuthStore } from '../store/authStore';
import { dashboardApi, type DirectReport } from '../api/dashboard';
import { attendanceApi, type AttendanceRecord } from '../api/attendance';
import { leaveApi, type LeaveRequestRecord } from '../api/leave';
import { PersonAvatar } from '../components/PersonCell';

// ── Shared styles (matches the rest of the app — plain style objects, no CSS framework) ──

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
const thS: React.CSSProperties = { padding: '12px 16px', textAlign: 'left', fontSize: 11, fontWeight: 800, color: 'var(--brand)', textTransform: 'uppercase', letterSpacing: '.09em', whiteSpace: 'nowrap', borderBottom: '1px solid color-mix(in srgb, var(--brand) 22%, var(--line))', background: 'color-mix(in srgb, var(--brand) 7%, var(--raised))' };
const tdS: React.CSSProperties = { padding: '13px 16px', fontSize: 13.5, color: 'var(--txt)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
const inputStyle: React.CSSProperties = { padding: '7px 10px', borderRadius: 6, border: '1px solid var(--line2)', background: 'var(--shell)', color: 'var(--txt)', fontSize: 12.5, fontFamily: 'inherit' };
const labelStyle: React.CSSProperties = { fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginRight: 6 };

const PIE_COLORS = { active: '#2FB67C', inactive: 'var(--raised2)', present: '#2FB67C', late: '#E0A93B', absent: '#E4373D', notCheckedIn: 'var(--txt-dim)', onLeave: '#4C8DD6' };

// ── Date helpers ─────────────────────────────────────────

function todayIsoDate(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function monthStartIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
}

// ── Pure, testable aggregation (mirrors what SuperAdminDashboardView/TeamDashboardView already
//    compute inline on DashboardPage.tsx — extracted here so it can be unit tested and reused
//    across the cards/charts/tables/export without recomputing per consumer) ──────────────────

export interface HeadcountSummary {
  total: number;
  active: number;
  inactive: number;
  byDepartment: { name: string; count: number }[];
}

export function computeHeadcountSummary(reports: DirectReport[]): HeadcountSummary {
  const active = reports.filter(r => r.active).length;
  const byDeptMap = new Map<string, number>();
  for (const r of reports) {
    const name = r.departmentName ?? 'Unassigned';
    byDeptMap.set(name, (byDeptMap.get(name) ?? 0) + 1);
  }
  return {
    total: reports.length,
    active,
    inactive: reports.length - active,
    byDepartment: Array.from(byDeptMap.entries()).map(([name, count]) => ({ name, count }))
      .sort((a, b) => b.count - a.count),
  };
}

export interface AttendanceSummary {
  present: number;
  late: number;
  absent: number;
  onLeave: number;
  notCheckedIn: number;
}

/** headcount is the scope's total employee count for the day — anyone with no attendance row at
 *  all for the selected date falls into notCheckedIn (meaningful for today; for a past date it
 *  overlaps with the app's own NO_ATTENDANCE concept, since a completed day with no row and no
 *  ABSENT status simply never had an attendance-relevant event recorded). */
export function computeAttendanceSummary(records: AttendanceRecord[], headcount: number): AttendanceSummary {
  let present = 0, late = 0, absent = 0, onLeave = 0;
  for (const r of records) {
    switch (r.status) {
      case 'LATE': late++; break;
      case 'ABSENT': absent++; break;
      case 'ON_LEAVE': onLeave++; break;
      case 'PRESENT': case 'HALF_DAY': case 'MISSING_CHECKOUT': default: present++; break;
    }
  }
  const notCheckedIn = Math.max(0, headcount - records.length);
  return { present, late, absent, onLeave, notCheckedIn };
}

export interface LeaveSummary {
  approvedCount: number;
  totalDaysApproved: number;
  pendingCount: number;
  byType: { name: string; count: number; days: number }[];
}

/** approvedRecords: whatever leaveApi.team/organization returned — those endpoints only ever
 *  return approved leave overlapping the range (see their own doc comments), so every row here
 *  is already approved; pendingCount comes from the separate, independently-scoped
 *  leaveApi.listApprovals call. */
export function computeLeaveSummary(approvedRecords: LeaveRequestRecord[], pendingCount: number): LeaveSummary {
  const byTypeMap = new Map<string, { count: number; days: number }>();
  let totalDaysApproved = 0;
  for (const r of approvedRecords) {
    totalDaysApproved += r.totalDays;
    const existing = byTypeMap.get(r.leaveTypeName) ?? { count: 0, days: 0 };
    existing.count += 1;
    existing.days += r.totalDays;
    byTypeMap.set(r.leaveTypeName, existing);
  }
  return {
    approvedCount: approvedRecords.length,
    totalDaysApproved,
    pendingCount,
    byType: Array.from(byTypeMap.entries()).map(([name, v]) => ({ name, ...v }))
      .sort((a, b) => b.count - a.count),
  };
}

// ── Excel export — same XLSX.utils.json_to_sheet/book_new/book_append_sheet/writeFile calls as
//    DirectoryPage.tsx's exportToExcel, extended to one workbook with one sheet per dashboard
//    section so the export matches everything currently on screen, not just one table. ────────

export function buildHeadcountSheetRows(reports: DirectReport[]) {
  return reports.map(r => ({
    'Full Name': r.fullName,
    'Employee Code': r.employeeCode,
    'Department': r.departmentName ?? '',
    'Designation': r.designationName ?? '',
    'Status': r.active ? 'Active' : 'Inactive',
  }));
}

export function buildAttendanceSheetRows(records: AttendanceRecord[]) {
  return records.map(r => ({
    'Full Name': r.fullName,
    'Employee Code': r.employeeCode,
    'Date': r.workDate,
    'Status': r.status ?? '',
    'Check In': r.checkInAt ?? '',
    'Check Out': r.checkOutAt ?? '',
    'Late By (min)': r.lateByMinutes ?? '',
  }));
}

export function buildLeaveSheetRows(records: LeaveRequestRecord[]) {
  return records.map(r => ({
    'Employee Name': r.employeeName,
    'Employee Code': r.employeeCode ?? '',
    'Leave Type': r.leaveTypeName,
    'Start Date': r.startDate,
    'End Date': r.endDate,
    'Half Day': r.halfDay ? 'Yes' : 'No',
    'Total Days': r.totalDays,
    'Status': r.status,
  }));
}

function exportDashboardToExcel(opts: {
  scopeLabel: string;
  headcountRows: ReturnType<typeof buildHeadcountSheetRows>;
  attendanceRows: ReturnType<typeof buildAttendanceSheetRows>;
  leaveRows: ReturnType<typeof buildLeaveSheetRows>;
}) {
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(opts.headcountRows), 'Headcount');
  XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(opts.attendanceRows), 'Attendance');
  XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(opts.leaveRows), 'Leave');
  XLSX.writeFile(wb, `${opts.scopeLabel}-dashboard-${todayIsoDate()}.xlsx`);
}

// ── Small presentational bits ───────────────────────────────

// Same tile as the Home dashboard (components/DashTile); display only.
export function KpiCard({ icon, label, value, note, danger, accent, onClick }: {
  icon: React.ReactNode; label: string; value: string | number; note?: string; danger?: boolean; accent?: string; onClick?: () => void;
}) {
  return <DashTile icon={icon} label={label} value={String(value)} sub={note} accent={danger ? 'var(--risk)' : (accent ?? 'var(--brand)')} onClick={onClick} />;
}

// ── Stat-card drill-down modal ───────────────────────────────────────────────
// Each KPI card above only ever shows one aggregate number — clicking it opens the per-employee
// detail that number was computed from, so "Present Today: 58" or "Late/Absent: 2/0" is never a
// dead end. Reuses the exact same loaded data (filteredReports/attendanceRecords/leaveRecords/
// pendingLeaveRecords) the cards and charts above already computed from — no extra API calls.
type DetailsView = 'headcount' | 'present' | 'lateAbsent' | 'leave' | null;

function StatusPill({ label, color }: { label: string; color: string }) {
  return (
    <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20, color, background: `color-mix(in srgb, ${color} 15%, transparent)` }}>
      {label}
    </span>
  );
}

function DetailsModal({ view, onClose, reports, attendance, attendanceDate, leave, pendingLeave }: {
  view: Exclude<DetailsView, null>;
  onClose: () => void;
  reports: DirectReport[];
  attendance: AttendanceRecord[];
  attendanceDate: string;
  leave: LeaveRequestRecord[];
  pendingLeave: LeaveRequestRecord[];
}) {
  const presentStatuses = new Set(['PRESENT', 'HALF_DAY', 'MISSING_CHECKOUT']);
  const config: Record<Exclude<DetailsView, null>, { title: string; headers: string[]; rows: React.ReactNode[] }> = {
    headcount: {
      title: 'Employees',
      headers: ['Name', 'Code', 'Department', 'Designation', 'Status'],
      rows: reports.map(r => (
        <tr key={r.userId}>
          <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{r.fullName}</td>
          <td style={{ ...tdS, fontFamily: 'Inter, sans-serif' }}>{r.employeeCode}</td>
          <td style={tdS}>{r.departmentName ?? '–'}</td>
          <td style={tdS}>{r.designationName ?? '–'}</td>
          <td style={tdS}><StatusPill label={r.active ? 'Active' : 'Inactive'} color={r.active ? '#2FB67C' : 'var(--txt-dim)'} /></td>
        </tr>
      )),
    },
    present: {
      title: `Present — ${attendanceDate}`,
      headers: ['Name', 'Code', 'Check-In', 'Status'],
      rows: attendance.filter(a => presentStatuses.has(a.status ?? '')).map(a => (
        <tr key={a.employeeUserId}>
          <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{a.fullName}</td>
          <td style={{ ...tdS, fontFamily: 'Inter, sans-serif' }}>{a.employeeCode}</td>
          <td style={tdS}>{a.checkInAt ? new Date(a.checkInAt).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' }) : '–'}</td>
          <td style={tdS}><StatusPill label={a.status ?? '–'} color="#2FB67C" /></td>
        </tr>
      )),
    },
    lateAbsent: {
      title: `Late / Absent — ${attendanceDate}`,
      headers: ['Name', 'Code', 'Status', 'Late By'],
      rows: attendance.filter(a => a.status === 'LATE' || a.status === 'ABSENT').map(a => (
        <tr key={a.employeeUserId}>
          <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{a.fullName}</td>
          <td style={{ ...tdS, fontFamily: 'Inter, sans-serif' }}>{a.employeeCode}</td>
          <td style={tdS}><StatusPill label={a.status === 'LATE' ? 'Late' : 'Absent'} color={a.status === 'LATE' ? 'var(--warn)' : '#E4373D'} /></td>
          <td style={tdS}>{a.lateByMinutes ? `${a.lateByMinutes}m` : '–'}</td>
        </tr>
      )),
    },
    leave: {
      title: 'Leave in period',
      headers: ['Employee', 'Type', 'Dates', 'Days', 'Status'],
      rows: [...leave, ...pendingLeave].map(r => (
        <tr key={r.id}>
          <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{r.employeeName}</td>
          <td style={tdS}>{r.leaveTypeName}</td>
          <td style={tdS}>{r.startDate}{r.endDate !== r.startDate ? ` → ${r.endDate}` : ''}{r.halfDay ? ' (half day)' : ''}</td>
          <td style={tdS}>{r.totalDays}</td>
          <td style={tdS}><StatusPill label={r.status} color={r.status === 'APPROVED' ? '#2FB67C' : r.status === 'PENDING' ? 'var(--warn)' : '#E4373D'} /></td>
        </tr>
      )),
    },
  };
  const { title, headers, rows } = config[view];

  return (
    <div
      onClick={onClose}
      style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500, padding: 16 }}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{ ...card, width: '100%', maxWidth: 720, maxHeight: '80vh', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '14px 18px', borderBottom: '1px solid var(--line)' }}>
          <h3 style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif', flex: 1 }}>{title}</h3>
          <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>{rows.length}</span>
          <button onClick={onClose} aria-label="Close" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, display: 'flex' }}>
            <X size={16} />
          </button>
        </div>
        <div style={{ overflow: 'auto' }}>
          <table className="nf-rep-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead><tr>{headers.map(h => <th key={h} style={thS}>{h}</th>)}</tr></thead>
            <tbody>
              {rows.length === 0
                ? <tr><td colSpan={headers.length} style={{ ...tdS, textAlign: 'center', padding: 30 }}>Nothing to show.</td></tr>
                : rows}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

function ChartCard({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ ...card, padding: '16px 18px' }}>
      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', marginBottom: 12 }}>{title}</div>
      {children}
    </div>
  );
}

function AccessDenied() {
  return (
    <div style={{ ...card, padding: 40, textAlign: 'center' }}>
      <ShieldAlert size={28} style={{ color: 'var(--txt-dim)', marginBottom: 10 }} />
      <div style={{ fontSize: 15, color: 'var(--txt)', marginBottom: 6 }}>You don't have access to this dashboard</div>
      <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>Reports & Analytics is available to Managers, HR Admins and Super Admins.</div>
    </div>
  );
}

// ── Main page ────────────────────────────────────────────────

export default function ReportsPage() {
  const token = useAuthStore(s => s.token) ?? '';
  const role = useAuthStore(s => s.user?.role);
  const isHr = role === 'HR_ADMIN' || role === 'SUPER_ADMIN';
  const isManager = role === 'MANAGER';
  const authorized = isHr || isManager;

  const [directReports, setDirectReports] = useState<DirectReport[]>([]);
  const [attendanceRecords, setAttendanceRecords] = useState<AttendanceRecord[]>([]);
  const [leaveRecords, setLeaveRecords] = useState<LeaveRequestRecord[]>([]);
  const [pendingLeaveRecords, setPendingLeaveRecords] = useState<LeaveRequestRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [detailsView, setDetailsView] = useState<DetailsView>(null);

  const [attendanceDate, setAttendanceDate] = useState(todayIsoDate());
  const [leaveFrom, setLeaveFrom] = useState(monthStartIso());
  const [leaveTo, setLeaveTo] = useState(todayIsoDate());
  const [departmentFilter, setDepartmentFilter] = useState('');

  const load = useCallback(() => {
    if (!authorized) { setLoading(false); return; }
    setLoading(true);
    setError('');
    const headcountCall = isHr ? dashboardApi.hrDashboard : dashboardApi.managerDashboard;
    const attendanceCall = isHr ? attendanceApi.day : attendanceApi.team;
    const leaveCall = isHr ? leaveApi.organization : leaveApi.team;

    Promise.all([
      headcountCall(token),
      attendanceCall(attendanceDate, token),
      leaveCall(leaveFrom, leaveTo, token),
      leaveApi.listApprovals(token),
    ])
      .then(([headcount, attendance, leave, pending]) => {
        setDirectReports(headcount.directReports);
        setAttendanceRecords(attendance);
        setLeaveRecords(leave);
        setPendingLeaveRecords(pending);
      })
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load dashboard'))
      .finally(() => setLoading(false));
  }, [authorized, isHr, token, attendanceDate, leaveFrom, leaveTo]);

  // Fresh on every mount/reload — no cached data is reused across page loads, and re-running
  // `load` (not just on mount) whenever a filter changes means the data behind every filter is
  // always a live server response, never a client-side re-slice of one stale fetch.
  useEffect(() => { load(); }, [load]);

  if (!authorized) return <AccessDenied />;

  const departmentOptions = useMemo(
    () => Array.from(new Set(directReports.map(r => r.departmentName).filter((d): d is string => !!d))).sort(),
    [directReports]
  );
  const filteredReports = useMemo(
    () => departmentFilter ? directReports.filter(r => r.departmentName === departmentFilter) : directReports,
    [directReports, departmentFilter]
  );

  const headcountSummary = useMemo(() => computeHeadcountSummary(filteredReports), [filteredReports]);
  const attendanceSummary = useMemo(
    () => computeAttendanceSummary(attendanceRecords, headcountSummary.total),
    [attendanceRecords, headcountSummary.total]
  );
  const leaveSummary = useMemo(() => computeLeaveSummary(leaveRecords, pendingLeaveRecords.length), [leaveRecords, pendingLeaveRecords]);

  const headcountPieData = [
    { name: 'Active', value: headcountSummary.active },
    { name: 'Inactive', value: headcountSummary.inactive },
  ];
  const attendanceBarData = [
    { name: 'Present', count: attendanceSummary.present },
    { name: 'Late', count: attendanceSummary.late },
    { name: 'Absent', count: attendanceSummary.absent },
    { name: 'On Leave', count: attendanceSummary.onLeave },
    { name: 'Not Checked-In', count: attendanceSummary.notCheckedIn },
  ];

  function handleExport() {
    exportDashboardToExcel({
      scopeLabel: isHr ? 'company' : 'team',
      headcountRows: buildHeadcountSheetRows(filteredReports),
      attendanceRows: buildAttendanceSheetRows(attendanceRecords),
      leaveRows: buildLeaveSheetRows(leaveRecords),
    });
  }

  return (
    <div>
      <div className="nf-page-hdr" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 18, flexWrap: 'wrap' }}>
        <PageTitleBlock icon={BarChart3}>
          <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>Reports & Analytics</h1>
          <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13 }}>
            {isHr ? 'Company-wide headcount, attendance and leave.' : 'Your team’s headcount, attendance and leave.'}
          </p>
        </PageTitleBlock>
        <button
          onClick={handleExport}
          disabled={loading || !!error}
          style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '8px 16px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', cursor: loading || error ? 'not-allowed' : 'pointer', opacity: loading || error ? 0.6 : 1 }}
        >
          <Download size={13} /> Export to Excel
        </button>
      </div>

      {/* Filters */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 20, flexWrap: 'wrap', marginBottom: 18, padding: '12px 16px', ...card }}>
        <div>
          <label style={labelStyle}>Attendance date</label>
          <input type="date" value={attendanceDate} max={todayIsoDate()} onChange={e => setAttendanceDate(e.target.value)} style={inputStyle} />
        </div>
        <div>
          <label style={labelStyle}>Leave from</label>
          <input type="date" value={leaveFrom} max={leaveTo} onChange={e => setLeaveFrom(e.target.value)} style={inputStyle} />
        </div>
        <div>
          <label style={labelStyle}>Leave to</label>
          <input type="date" value={leaveTo} min={leaveFrom} max={todayIsoDate()} onChange={e => setLeaveTo(e.target.value)} style={inputStyle} />
        </div>
        {isHr && departmentOptions.length > 0 && (
          <div>
            <label style={labelStyle}>Department</label>
            <select value={departmentFilter} onChange={e => setDepartmentFilter(e.target.value)} style={inputStyle}>
              <option value="">All departments</option>
              {departmentOptions.map(d => <option key={d} value={d}>{d}</option>)}
            </select>
          </div>
        )}
      </div>

      {loading ? (
        <div style={{ ...card, padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
      ) : error ? (
        <div style={{ ...card, padding: 40, textAlign: 'center' }}>
          <span style={{ color: '#E4373D' }}>Couldn't load the dashboard ({error}).</span>{' '}
          <button onClick={load} style={{ color: 'var(--info)', background: 'none', border: 'none', textDecoration: 'underline', cursor: 'pointer', fontSize: 13, padding: 0 }}>Retry</button>
        </div>
      ) : (
        <>
          {/* KPI cards */}
          <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 12, marginBottom: 20 }}>
            <KpiCard icon={<Users size={20} />} label={isHr ? 'Total Headcount' : 'Direct Reports'} value={headcountSummary.total} accent="var(--info)" note={`${headcountSummary.active} active · ${headcountSummary.inactive} inactive`} onClick={() => setDetailsView('headcount')} />
            <KpiCard icon={<CalendarCheck size={20} />} label="Present Today" value={attendanceSummary.present} accent="var(--ok)" note={`of ${headcountSummary.total} for ${attendanceDate}`} onClick={() => setDetailsView('present')} />
            <KpiCard icon={<Clock size={20} />} label="Late / Absent" value={`${attendanceSummary.late} / ${attendanceSummary.absent}`} accent="var(--warn)" note="on the selected date" danger={attendanceSummary.absent > 0} onClick={() => setDetailsView('lateAbsent')} />
            <KpiCard icon={<CalendarCheck size={20} />} label="Leave (period)" value={leaveSummary.approvedCount} accent="var(--om-purple)" note={`${leaveSummary.totalDaysApproved}d approved · ${leaveSummary.pendingCount} pending`} onClick={() => setDetailsView('leave')} />
          </div>

          {/* Charts */}
          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1.4fr)', gap: 12, marginBottom: 20 }} className="nf-grid-side-collapse">
            <ChartCard title={isHr ? 'Headcount status' : 'Team status'}>
              {headcountSummary.total === 0 ? (
                <div style={{ padding: '30px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No employees in scope.</div>
              ) : (
                <ResponsiveContainer width="100%" height={220}>
                  {/* Legend added — this donut previously communicated Active/Inactive by slice
                      color alone with no labels, so a colorblind viewer (or a share as small as
                      Inactive often is) had no way to read it (hr-ux-audit finding UI-007). */}
                  <PieChart>
                    <Pie data={headcountPieData} cx="50%" cy="50%" innerRadius="55%" outerRadius="85%" dataKey="value" strokeWidth={0}>
                      <Cell fill={PIE_COLORS.active} />
                      <Cell fill={PIE_COLORS.inactive} />
                    </Pie>
                    <Legend verticalAlign="bottom" height={28} formatter={(value) => <span style={{ color: 'var(--txt-mut)', fontSize: 12 }}>{value}</span>} />
                    <Tooltip contentStyle={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 7, fontSize: 12, color: 'var(--txt)' }} />
                  </PieChart>
                </ResponsiveContainer>
              )}
            </ChartCard>

            <ChartCard title={`Attendance breakdown - ${attendanceDate}`}>
              {attendanceRecords.length === 0 && attendanceSummary.notCheckedIn === 0 ? (
                <div style={{ padding: '30px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No attendance data for this date.</div>
              ) : (
                <ResponsiveContainer width="100%" height={220}>
                  <BarChart data={attendanceBarData} margin={{ top: 0, right: 0, left: -20, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" vertical={false} />
                    <XAxis dataKey="name" tick={{ fill: 'var(--txt-dim)', fontSize: 10.5 }} axisLine={false} tickLine={false} />
                    <YAxis tick={{ fill: 'var(--txt-dim)', fontSize: 10.5 }} axisLine={false} tickLine={false} allowDecimals={false} />
                    <Tooltip contentStyle={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 7, fontSize: 12, color: 'var(--txt)' }} cursor={{ fill: 'rgba(177,17,22,.08)' }} />
                    <Bar dataKey="count" name="Employees" fill="#B11116" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              )}
            </ChartCard>
          </div>

          {/* Headcount table */}
          <Section title={isHr ? 'Employees' : 'Direct reports'} count={`${filteredReports.length}`}>
            <HeadcountRows rows={filteredReports} />
          </Section>

          {/* Leave table */}
          <Section title="Approved leave in period" count={`${leaveSummary.approvedCount} · ${leaveSummary.pendingCount} pending`}>
            <table className="nf-rep-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead><tr>{['Employee', 'Type', 'Dates', 'Days', 'Status'].map(h => <th key={h} style={thS}>{h}</th>)}</tr></thead>
              <tbody>
                {leaveRecords.length === 0 ? (
                  <tr><td colSpan={5} style={{ ...tdS, textAlign: 'center', padding: 30 }}>No approved leave in this period.</td></tr>
                ) : leaveRecords.map(r => (
                  <tr key={r.id}>
                    <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{r.employeeName}</td>
                    <td style={tdS}>{r.leaveTypeName}</td>
                    <td style={tdS}>{r.startDate}{r.endDate !== r.startDate ? ` → ${r.endDate}` : ''}{r.halfDay ? ' (half day)' : ''}</td>
                    <td style={tdS}>{r.totalDays}</td>
                    <td style={tdS}>{r.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Section>
        </>
      )}

      {detailsView && (
        <DetailsModal
          view={detailsView}
          onClose={() => setDetailsView(null)}
          reports={filteredReports}
          attendance={attendanceRecords}
          attendanceDate={attendanceDate}
          leave={leaveRecords}
          pendingLeave={pendingLeaveRecords}
        />
      )}
    </div>
  );
}

export function HeadcountRows({ rows }: { rows: DirectReport[] }) {
  return (
    <table className="nf-rep-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
      <thead><tr>{['Name', 'Code', 'Department', 'Designation', 'Status'].map(h => <th key={h} style={thS}>{h}</th>)}</tr></thead>
      <tbody>
        {rows.length === 0 ? (
          <tr><td colSpan={5} style={{ ...tdS, textAlign: 'center', padding: 30, color: 'var(--txt-mut)' }}>No employees to show.</td></tr>
        ) : rows.map(r => (
          <tr key={r.userId}>
            <td style={tdS}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <PersonAvatar userId={r.userId} name={r.fullName} size={34} />
                <span style={{ fontWeight: 700, color: 'var(--txt)' }}>{r.fullName}</span>
              </div>
            </td>
            <td style={tdS}><span className="nf-rep-code">{r.employeeCode}</span></td>
            <td style={{ ...tdS, color: 'var(--txt-mut)' }}>{r.departmentName ?? '–'}</td>
            <td style={{ ...tdS, color: 'var(--txt-mut)' }}>{r.designationName ?? '–'}</td>
            <td style={tdS}>
              <span className={`nf-rep-pill${r.active ? '' : ' is-off'}`}>{r.active ? 'Active' : 'Inactive'}</span>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function Section({ title, count, children }: { title: string; count?: string; children: React.ReactNode }) {
  return (
    <div style={{ ...card, marginBottom: 16, borderRadius: 14 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '14px 18px', borderBottom: '1px solid var(--line)', background: 'linear-gradient(90deg, color-mix(in srgb, var(--brand) 9%, var(--panel)), var(--panel))' }}>
        <h3 style={{ fontSize: 16, fontWeight: 800, letterSpacing: '-.01em', flex: 1, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>{title}</h3>
        {count && <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--brand)', padding: '3px 12px', borderRadius: 999, background: 'color-mix(in srgb, var(--brand) 11%, var(--panel))', border: '1px solid color-mix(in srgb, var(--brand) 28%, var(--line))' }}>{count}</span>}
      </div>
      <div style={{ overflowX: 'auto' }}>{children}</div>
    </div>
  );
}

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Users, CalendarCheck, Clock, Download, ShieldAlert } from 'lucide-react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  PieChart, Pie, Cell,
} from 'recharts';
import * as XLSX from 'xlsx';
import { useAuthStore } from '../store/authStore';
import { dashboardApi, type DirectReport } from '../api/dashboard';
import { attendanceApi, type AttendanceRecord } from '../api/attendance';
import { leaveApi, type LeaveRequestRecord } from '../api/leave';

// ── Shared styles (matches the rest of the app — plain style objects, no CSS framework) ──

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
const thS: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', background: 'var(--raised)' };
const tdS: React.CSSProperties = { padding: '11px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
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

function KpiCard({ icon, label, value, note, danger }: { icon: React.ReactNode; label: string; value: string | number; note?: string; danger?: boolean }) {
  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '16px 18px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <span style={{ color: 'var(--brand)' }}>{icon}</span>
        <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em' }}>{label}</span>
      </div>
      <div style={{ fontSize: 28, fontWeight: 700, fontFamily: 'Inter, sans-serif', color: danger ? '#E4373D' : 'var(--txt)', lineHeight: 1 }}>{value}</div>
      {note && <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 4 }}>{note}</div>}
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
  const [pendingLeaveCount, setPendingLeaveCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

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
        setPendingLeaveCount(pending.length);
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
  const leaveSummary = useMemo(() => computeLeaveSummary(leaveRecords, pendingLeaveCount), [leaveRecords, pendingLeaveCount]);

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
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 18, flexWrap: 'wrap' }}>
        <div>
          <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>Reports & Analytics</h1>
          <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13 }}>
            {isHr ? 'Company-wide headcount, attendance and leave.' : 'Your team’s headcount, attendance and leave.'}
          </p>
        </div>
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
            <KpiCard icon={<Users size={14} />} label={isHr ? 'Total Headcount' : 'Direct Reports'} value={headcountSummary.total} note={`${headcountSummary.active} active · ${headcountSummary.inactive} inactive`} />
            <KpiCard icon={<CalendarCheck size={14} />} label="Present Today" value={attendanceSummary.present} note={`of ${headcountSummary.total} for ${attendanceDate}`} />
            <KpiCard icon={<Clock size={14} />} label="Late / Absent" value={`${attendanceSummary.late} / ${attendanceSummary.absent}`} note="on the selected date" danger={attendanceSummary.absent > 0} />
            <KpiCard icon={<CalendarCheck size={14} />} label="Leave (period)" value={leaveSummary.approvedCount} note={`${leaveSummary.totalDaysApproved}d approved · ${leaveSummary.pendingCount} pending`} />
          </div>

          {/* Charts */}
          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1.4fr)', gap: 12, marginBottom: 20 }} className="nf-grid-side-collapse">
            <ChartCard title={isHr ? 'Headcount status' : 'Team status'}>
              {headcountSummary.total === 0 ? (
                <div style={{ padding: '30px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No employees in scope.</div>
              ) : (
                <ResponsiveContainer width="100%" height={220}>
                  <PieChart>
                    <Pie data={headcountPieData} cx="50%" cy="50%" innerRadius="55%" outerRadius="85%" dataKey="value" strokeWidth={0}>
                      <Cell fill={PIE_COLORS.active} />
                      <Cell fill={PIE_COLORS.inactive} />
                    </Pie>
                    <Tooltip contentStyle={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 7, fontSize: 12, color: 'var(--txt)' }} />
                  </PieChart>
                </ResponsiveContainer>
              )}
            </ChartCard>

            <ChartCard title={`Attendance breakdown — ${attendanceDate}`}>
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
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead><tr>{['Name', 'Code', 'Department', 'Designation', 'Status'].map(h => <th key={h} style={thS}>{h}</th>)}</tr></thead>
              <tbody>
                {filteredReports.length === 0 ? (
                  <tr><td colSpan={5} style={{ ...tdS, textAlign: 'center', padding: 30 }}>No employees to show.</td></tr>
                ) : filteredReports.map(r => (
                  <tr key={r.userId}>
                    <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{r.fullName}</td>
                    <td style={{ ...tdS, fontFamily: 'Inter, sans-serif' }}>{r.employeeCode}</td>
                    <td style={tdS}>{r.departmentName ?? '—'}</td>
                    <td style={tdS}>{r.designationName ?? '—'}</td>
                    <td style={tdS}>
                      <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20, color: r.active ? '#2FB67C' : 'var(--txt-dim)', background: r.active ? 'rgba(47,182,124,.15)' : 'var(--raised2)' }}>
                        {r.active ? 'Active' : 'Inactive'}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Section>

          {/* Leave table */}
          <Section title="Approved leave in period" count={`${leaveSummary.approvedCount} · ${leaveSummary.pendingCount} pending`}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
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
    </div>
  );
}

function Section({ title, count, children }: { title: string; count?: string; children: React.ReactNode }) {
  return (
    <div style={{ ...card, marginBottom: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '12px 18px', borderBottom: '1px solid var(--line)', background: 'var(--raised)' }}>
        <h3 style={{ fontSize: 13, flex: 1, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>{title}</h3>
        {count && <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>{count}</span>}
      </div>
      <div style={{ overflowX: 'auto' }}>{children}</div>
    </div>
  );
}

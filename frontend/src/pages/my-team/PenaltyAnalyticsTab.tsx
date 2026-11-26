/**
 * PN-06: penalty analytics. Penalty counts and deduction days by rule, department, month and
 * employee, with a monthly trend chart and the top offenders. The server decides the scope (HR sees
 * the whole organisation, a manager only their direct reports); this view only sends the filters.
 * Excel export reuses the shared autoSizeColumns helper.
 */
import { useEffect, useMemo, useState } from 'react';
import { Ban, Gavel, ShieldAlert, Undo2, Users } from 'lucide-react';
import { Bar, CartesianGrid, ComposedChart, Legend, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { penaltyAnalyticsApi, type PenaltyAnalytics, type PenaltyAnalyticsBucket } from '../../api/attendance';
import DashTile from '../../components/DashTile';
import { useToast } from '../../context/ToastContext';
import { autoSizeColumns } from '../../lib/xlsxColumns';
import '../attendance/myPenalties.css';

export const RULE_OPTIONS: { value: string; label: string }[] = [
  { value: 'NO_ATTENDANCE', label: 'No Attendance' },
  { value: 'LATE_ARRIVAL', label: 'Late Arrival' },
  { value: 'EARLY_DEPARTURE', label: 'Early Going' },
  { value: 'WORK_HOURS_SHORTAGE', label: 'Shortage' },
  { value: 'MISSING_PUNCH', label: 'Missing Logs' },
  { value: 'EXCESS_BREAK', label: 'Excess Break' },
];

export const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: 'All statuses' },
  { value: 'PENDING_REVIEW', label: 'In force' },
  { value: 'CANCELLED', label: 'Cancelled' },
  { value: 'REVERSED', label: 'Reversed' },
];

const panel: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 18, overflow: 'hidden', boxShadow: '0 4px 14px rgba(16, 24, 40, .05)' };
const head: React.CSSProperties = { padding: '14px 20px', borderBottom: '1px solid var(--line)', background: 'linear-gradient(135deg, color-mix(in srgb, var(--brand) 9%, var(--panel)), var(--panel))', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10, flexWrap: 'wrap' };
const title: React.CSSProperties = { fontFamily: '"Space Grotesk", Inter, sans-serif', fontWeight: 700, fontSize: 15, letterSpacing: '-.01em', color: 'var(--txt)' };
const field: React.CSSProperties = { background: 'var(--raised)', color: 'var(--txt)', border: '1px solid var(--line2)', borderRadius: 7, padding: '6px 9px', fontSize: 12.5, outline: 'none' };
const label: React.CSSProperties = { fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)' };
const th: React.CSSProperties = { padding: '9px 12px', textAlign: 'left', fontSize: 10.5, fontWeight: 700, letterSpacing: '.06em', textTransform: 'uppercase', color: 'var(--txt-dim)', background: 'transparent', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const td: React.CSSProperties = { padding: '11px 12px', fontSize: 12.5, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

const isoToday = () => new Date().toISOString().slice(0, 10);
const isoDaysAgo = (n: number) => new Date(Date.now() - n * 86400000).toISOString().slice(0, 10);
const days = (n: number) => (Math.round(n * 100) / 100).toString();

function monthLabel(key: string) {
  const [y, m] = key.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString(undefined, { month: 'short', year: 'numeric' });
}

/** Horizontal bars for one breakdown (by rule / by department). */
function BarList({ rows, testId }: { rows: PenaltyAnalyticsBucket[]; testId: string }) {
  const max = Math.max(1, ...rows.map((r) => r.total));
  if (rows.length === 0) return <div style={{ padding: '18px 20px', fontSize: 12.5, color: 'var(--txt-dim)' }}>No penalties for these filters.</div>;
  return (
    <div data-testid={testId} style={{ padding: '10px 20px 16px', display: 'flex', flexDirection: 'column', gap: 12 }}>
      {rows.map((r) => (
        <div key={r.key}>
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, fontSize: 12.5, marginBottom: 5 }}>
            <b style={{ color: 'var(--txt)', fontWeight: 600 }}>{r.label}</b>
            <span style={{ color: 'var(--txt-mut)', whiteSpace: 'nowrap' }}>{r.total} penalt{r.total === 1 ? 'y' : 'ies'} · <b style={{ color: 'var(--txt)' }}>{days(r.deductionDays)}</b> day{r.deductionDays === 1 ? '' : 's'}</span>
          </div>
          <div style={{ height: 8, borderRadius: 6, background: 'var(--raised)', border: '1px solid var(--line2)', overflow: 'hidden', display: 'flex' }}>
            <div title={`${r.standing} in force`} style={{ width: (r.standing / max) * 100 + '%', background: 'var(--brand)' }} />
            <div title={`${r.cancelled} cancelled`} style={{ width: (r.cancelled / max) * 100 + '%', background: 'var(--txt-dim)' }} />
            <div title={`${r.reversed} reversed`} style={{ width: (r.reversed / max) * 100 + '%', background: 'var(--info)' }} />
          </div>
        </div>
      ))}
    </div>
  );
}

function downloadWorkbook(data: PenaltyAnalytics, filename: string) {
  // Loaded on demand: SheetJS is large and only needed when someone exports.
  return import('xlsx').then((XLSX) => {
    const bucketRows = (rows: PenaltyAnalyticsBucket[], first: string) => rows.map((r) => ({
      [first]: r.label, 'Penalties': r.total, 'In force': r.standing, 'Cancelled': r.cancelled, 'Reversed': r.reversed, 'Deduction days (in force)': r.deductionDays,
    }));
    const sheets: { name: string; rows: Record<string, unknown>[] }[] = [
      { name: 'Summary', rows: [
        { Measure: 'Scope', Value: data.scope === 'ORGANISATION' ? 'Whole organisation' : 'My team' },
        { Measure: 'From', Value: data.from }, { Measure: 'To', Value: data.to },
        { Measure: 'Penalties', Value: data.summary.total }, { Measure: 'In force', Value: data.summary.standing },
        { Measure: 'Cancelled', Value: data.summary.cancelled }, { Measure: 'Reversed', Value: data.summary.reversed },
        { Measure: 'Deduction days (in force)', Value: data.summary.deductionDays }, { Measure: 'Employees affected', Value: data.summary.employeesAffected },
      ] },
      { name: 'By rule', rows: bucketRows(data.byRule, 'Rule') },
      { name: 'By department', rows: bucketRows(data.byDepartment, 'Department') },
      { name: 'By month', rows: bucketRows(data.byMonth.map((b) => ({ ...b, label: monthLabel(b.key) })), 'Month') },
      { name: 'By employee', rows: data.byEmployee.map((e) => ({
        'Employee': e.fullName, 'Code': e.employeeCode ?? '', 'Department': e.department, 'Penalties': e.total, 'In force': e.standing,
        'Cancelled': e.cancelled, 'Reversed': e.reversed, 'Deduction days (in force)': e.deductionDays, 'Main rule': e.topRule ?? '',
      })) },
    ];
    const wb = XLSX.utils.book_new();
    for (const sheet of sheets) {
      // An empty sheet still gets its header row so the file is never a blank tab.
      const ws = XLSX.utils.json_to_sheet(sheet.rows);
      if (sheet.rows.length > 0) autoSizeColumns(ws, sheet.rows, Object.keys(sheet.rows[0]));
      XLSX.utils.book_append_sheet(wb, ws, sheet.name);
    }
    XLSX.writeFile(wb, filename);
  });
}

export default function PenaltyAnalyticsTab({ token }: { token: string }) {
  const { showToast } = useToast();
  const [from, setFrom] = useState(() => isoDaysAgo(89));
  const [to, setTo] = useState(isoToday);
  const [rule, setRule] = useState('');
  const [department, setDepartment] = useState('');
  const [status, setStatus] = useState('');
  const [data, setData] = useState<PenaltyAnalytics | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  // Departments come from the first unfiltered response so picking one never empties the dropdown.
  const [departments, setDepartments] = useState<string[]>([]);

  useEffect(() => {
    if (!from || !to || from > to) { setError('Choose a valid date range.'); setLoading(false); return; }
    let cancelled = false;
    setLoading(true); setError(null);
    penaltyAnalyticsApi.get({ from, to, rule: rule || undefined, department: department || undefined, status: status || undefined }, token)
      .then((d) => { if (!cancelled) { setData(d); setDepartments(d.departments); } })
      .catch((e: Error) => { if (!cancelled) { setData(null); setError(e.message || 'Could not load penalty analytics.'); } })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [token, from, to, rule, department, status]);

  const trend = useMemo(() => (data?.byMonth ?? []).map((b) => ({
    month: monthLabel(b.key), 'In force': b.standing, Cancelled: b.cancelled, Reversed: b.reversed, 'Deduction days': b.deductionDays,
  })), [data]);
  const top = (data?.byEmployee ?? []).slice(0, 10);
  const s = data?.summary;

  async function exportExcel() {
    if (!data) return;
    setExporting(true);
    try { await downloadWorkbook(data, `Penalty_analytics_${data.from}_to_${data.to}.xlsx`); }
    catch (e) { showToast('error', e instanceof Error ? e.message : 'Export failed'); }
    finally { setExporting(false); }
  }

  const empty = !!data && data.summary.total === 0;

  return (
    <div data-testid="penalty-analytics" className="nf-mp" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={panel}>
        <div style={head}>
          <span style={title}>Penalty analytics</span>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}>
            {data && (
              <span data-testid="scope-badge" style={{ fontSize: 11, fontWeight: 700, letterSpacing: '.05em', textTransform: 'uppercase', padding: '4px 10px', borderRadius: 999, color: 'var(--brand)', background: 'color-mix(in srgb, var(--brand) 12%, var(--panel))', border: '1px solid color-mix(in srgb, var(--brand) 28%, var(--line))' }}>
                {data.scope === 'ORGANISATION' ? 'Whole organisation' : 'My team'}
              </span>
            )}
            <button type="button" data-testid="export-excel" onClick={exportExcel} disabled={!data || exporting}
              style={{ ...field, cursor: !data || exporting ? 'not-allowed' : 'pointer', fontWeight: 600, background: 'var(--brand)', color: '#fff', border: 'none', opacity: !data || exporting ? 0.6 : 1 }}>
              {exporting ? 'Exporting...' : 'Export to Excel'}
            </button>
          </span>
        </div>
        <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', alignItems: 'flex-end', padding: '12px 18px' }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <span style={label}>From</span>
            <input aria-label="From date" type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} style={field} />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <span style={label}>To</span>
            <input aria-label="To date" type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} style={field} />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <span style={label}>Rule</span>
            <select aria-label="Rule" value={rule} onChange={(e) => setRule(e.target.value)} style={field}>
              <option value="">All rules</option>
              {RULE_OPTIONS.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
            </select>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <span style={label}>Department</span>
            <select aria-label="Department" value={department} onChange={(e) => setDepartment(e.target.value)} style={field}>
              <option value="">All departments</option>
              {departments.map((d) => <option key={d} value={d}>{d}</option>)}
            </select>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <span style={label}>Status</span>
            <select aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)} style={field}>
              {STATUS_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>
        </div>
      </div>

      {error && <div role="alert" style={{ background: 'color-mix(in srgb, var(--risk) 10%, var(--panel))', border: '1px solid color-mix(in srgb, var(--risk) 30%, var(--line))', borderRadius: 10, padding: '10px 14px', color: 'var(--risk)', fontSize: 13 }}>{error}</div>}
      {loading && !data && <div style={{ padding: 24, color: 'var(--txt-mut)', fontSize: 13 }}>Loading...</div>}

      {data && s && (
        <>
          <div className="nf-dash-row" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12, opacity: loading ? 0.6 : 1 }}>
            <DashTile icon={<Gavel size={18} />} label="Penalties" value={String(s.total)} sub="For these filters" accent="var(--brand)" />
            <DashTile icon={<ShieldAlert size={18} />} label="In force" value={String(s.standing)} sub="Still deducting" accent="var(--risk)" />
            <DashTile icon={<Ban size={18} />} label="Deduction days" value={days(s.deductionDays)} sub="In-force penalties only" accent="var(--warn)" />
            <DashTile icon={<Undo2 size={18} />} label="Cancelled / reversed" value={String(s.cancelled + s.reversed)} sub={`${s.cancelled} cancelled, ${s.reversed} reversed`} accent="var(--ok)" />
            <DashTile icon={<Users size={18} />} label="Employees affected" value={String(s.employeesAffected)} sub="With a penalty" accent="var(--info)" />
          </div>

          {empty ? (
            <div style={{ ...panel, padding: '34px 20px', textAlign: 'center', color: 'var(--txt-mut)', fontSize: 13.5 }}>
              No penalties match these filters. Try a wider date range or clear a filter.
            </div>
          ) : (
            <>
              <div style={panel}>
                <div style={head}><span style={title}>Monthly trend</span><span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Penalties by status, with the days deducted</span></div>
                <div data-testid="trend-chart" style={{ width: '100%', height: 280, padding: '14px 8px 6px' }}>
                  <ResponsiveContainer width="100%" height="100%">
                    <ComposedChart data={trend} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" vertical={false} />
                      <XAxis dataKey="month" tick={{ fontSize: 11, fill: 'var(--txt-dim)' }} tickLine={false} axisLine={{ stroke: 'var(--line)' }} />
                      <YAxis yAxisId="count" allowDecimals={false} tick={{ fontSize: 11, fill: 'var(--txt-dim)' }} tickLine={false} axisLine={false} width={34} />
                      <YAxis yAxisId="days" orientation="right" tick={{ fontSize: 11, fill: 'var(--txt-dim)' }} tickLine={false} axisLine={false} width={34} />
                      <Tooltip contentStyle={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, fontSize: 12 }} />
                      <Legend wrapperStyle={{ fontSize: 12 }} />
                      <Bar yAxisId="count" dataKey="In force" stackId="s" fill="var(--brand)" radius={[0, 0, 0, 0]} />
                      <Bar yAxisId="count" dataKey="Cancelled" stackId="s" fill="var(--txt-dim)" />
                      <Bar yAxisId="count" dataKey="Reversed" stackId="s" fill="var(--info)" radius={[4, 4, 0, 0]} />
                      <Line yAxisId="days" type="monotone" dataKey="Deduction days" stroke="var(--warn)" strokeWidth={2.5} dot={{ r: 3 }} />
                    </ComposedChart>
                  </ResponsiveContainer>
                </div>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: 16 }}>
                <div style={panel}>
                  <div style={head}><span style={title}>By rule</span></div>
                  <BarList rows={data.byRule} testId="by-rule" />
                </div>
                <div style={panel}>
                  <div style={head}><span style={title}>{data.scope === 'ORGANISATION' ? 'By department' : 'By department / team'}</span></div>
                  <BarList rows={data.byDepartment} testId="by-department" />
                </div>
              </div>

              <div style={panel}>
                <div style={head}>
                  <span style={title}>Top offenders</span>
                  <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Top {top.length} of {data.byEmployee.length}, by days deducted. The Excel export lists everyone.</span>
                </div>
                <div className="nf-req-wrap" style={{ padding: '4px 10px 10px' }}>
                  <table className="nf-req-table" data-testid="top-offenders" style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead>
                      <tr>{['#', 'Employee', 'Department', 'Penalties', 'In force', 'Cancelled / reversed', 'Days deducted', 'Main rule'].map((h) => <th key={h} style={th}>{h}</th>)}</tr>
                    </thead>
                    <tbody>
                      {top.map((e, i) => (
                        <tr key={e.employeeUserId} data-testid="offender-row" style={{ ['--rc' as string]: e.standing > 0 ? 'var(--risk)' : 'var(--txt-dim)' }}>
                          <td data-label="Rank" style={{ ...td, color: 'var(--txt-dim)', fontWeight: 700 }}>{i + 1}</td>
                          <td data-label="Employee" style={{ ...td, color: 'var(--txt)', fontWeight: 600 }}>{e.fullName}{e.employeeCode && <span style={{ color: 'var(--txt-dim)', fontWeight: 500 }}> · {e.employeeCode}</span>}</td>
                          <td data-label="Department" style={td}>{e.department}</td>
                          <td data-label="Penalties" style={td}>{e.total}</td>
                          <td data-label="In force" style={td}>{e.standing}</td>
                          <td data-label="Cancelled / reversed" style={td}>{e.cancelled + e.reversed}</td>
                          <td data-label="Days deducted" style={{ ...td, color: 'var(--txt)', fontWeight: 700 }}>{days(e.deductionDays)}</td>
                          <td data-label="Main rule" style={td}>{e.topRule ?? '-'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </>
          )}
        </>
      )}
    </div>
  );
}

import { useEffect, useState } from 'react';
import { PieChart, Pie, Cell, Legend, Tooltip, ResponsiveContainer } from 'recharts';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayrollCostReportResponse, type PayrollRunStatus } from '../../api/payroll';
import { panelStyle, thStyle, tdStyle, fmtCurrency, EmptyRow, SectionHead, ChartCard, PIE_PALETTE, chartTooltipStyle } from './payrollShared';

/** Donut slice is each entry's share of this breakdown's own net-pay total - not of the run's
 * overall net pay - since "By Department" and "By Project" aren't mutually exclusive partitions
 * of the same whole (a payslip's cost can appear under a project without belonging to it instead
 * of a department). Mirrors the table rows it sits above one-for-one, just visualized. */
function NetPayPie({ data, nameKey, onSliceClick }: {
  data: { name: string; value: number }[];
  nameKey: string;
  onSliceClick?: (name: string) => void;
}) {
  return (
    <ResponsiveContainer width="100%" height={240}>
      <PieChart>
        <Pie
          data={data} cx="50%" cy="50%" innerRadius="50%" outerRadius="85%" dataKey="value" nameKey={nameKey}
          strokeWidth={0} cursor={onSliceClick ? 'pointer' : undefined}
          onClick={onSliceClick ? (entry: { name?: string }) => entry.name && onSliceClick(entry.name) : undefined}
        >
          {data.map((_, i) => <Cell key={i} fill={PIE_PALETTE[i % PIE_PALETTE.length]} />)}
        </Pie>
        <Legend verticalAlign="bottom" height={28} formatter={value => <span style={{ color: 'var(--txt-mut)', fontSize: 12 }}>{value}</span>} />
        <Tooltip {...chartTooltipStyle} formatter={value => fmtCurrency(Number(value))} />
      </PieChart>
    </ResponsiveContainer>
  );
}

/** US-C16 - only buildable once a run leaves DRAFT/SUBMITTED (the backend enforces this; the
 * report's own department/project splits aren't meaningful until the run's numbers are final). */
export function CostReportTab({ runId, runStatus }: { runId: string; runStatus: PayrollRunStatus }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [report, setReport] = useState<PayrollCostReportResponse | null>(null);
  const available = runStatus === 'APPROVED' || runStatus === 'DISBURSED';

  useEffect(() => {
    if (!available) { setReport(null); return; }
    payrollApi.getCostReport(runId, token).then(setReport).catch(() => showToast('error', 'Failed to load the cost report'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, token, available]);

  if (!available) {
    return (
      <div style={{ ...panelStyle, padding: '36px 24px', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>
        The cost report is available once this run is Approved or Disbursed.
      </div>
    );
  }

  if (!report) {
    return <div style={{ padding: '24px', color: 'var(--txt-mut)' }}>Loading…</div>;
  }

  // Pie slices only make sense for positive shares of the whole - a department/project with zero
  // or negative net pay (e.g. an all-LOP cycle) has no slice to draw, so it's dropped from the
  // chart but still appears in the table below with its real figure.
  const deptChartData = report.byDepartment.filter(d => d.netPay > 0).map(d => ({ name: d.departmentName, value: d.netPay }));
  const projectChartData = report.byProject.filter(p => p.netPay > 0).map(p => ({ name: p.projectName, value: p.netPay }));

  return (
    <div>
      {/* No Employees/Gross/Deductions/Net tiles here - the run console's top-level KPI row
          (PayrollRunDetailPage.tsx) already shows those same totals on every step. This tab's
          own job is the department/project breakdown below, not a restatement of the run total. */}
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr)', gap: 16, marginBottom: 20 }} className="nf-grid-side-collapse">
        <ChartCard title="Net pay by department">
          {deptChartData.length === 0 ? (
            <div style={{ padding: '30px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No department breakdown available.</div>
          ) : (
            <NetPayPie data={deptChartData} nameKey="name" />
          )}
        </ChartCard>
        <ChartCard title="Net pay by project">
          {projectChartData.length === 0 ? (
            <div style={{ padding: '30px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No project breakdown available.</div>
          ) : (
            <NetPayPie data={projectChartData} nameKey="name" />
          )}
        </ChartCard>
      </div>

      <SectionHead title="By Department" />
      <div style={{ ...panelStyle, marginBottom: 24 }}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{['Department', 'Employees', 'Gross', 'Deductions', 'Net'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {report.byDepartment.map(d => (
              <tr key={d.departmentId ?? 'unassigned'}>
                <td style={tdStyle}>{d.departmentName}</td>
                <td style={tdStyle}>{d.employeeCount}</td>
                <td style={tdStyle}>{fmtCurrency(d.grossPay)}</td>
                <td style={tdStyle}>{fmtCurrency(d.totalDeductions)}</td>
                <td style={tdStyle}>{fmtCurrency(d.netPay)}</td>
              </tr>
            ))}
            {report.byDepartment.length === 0 && <EmptyRow cols={5} msg="No department breakdown available." />}
          </tbody>
        </table>
      </div>

      <SectionHead title="By Project" />
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{['Project', 'Gross', 'Deductions', 'Net'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {report.byProject.map(p => (
              <tr key={p.projectId ?? 'unallocated'}>
                <td style={tdStyle}>{p.projectName}{p.projectCode ? ` (${p.projectCode})` : ''}</td>
                <td style={tdStyle}>{fmtCurrency(p.grossPay)}</td>
                <td style={tdStyle}>{fmtCurrency(p.totalDeductions)}</td>
                <td style={tdStyle}>{fmtCurrency(p.netPay)}</td>
              </tr>
            ))}
            {report.byProject.length === 0 && <EmptyRow cols={4} msg="No project breakdown available." />}
          </tbody>
        </table>
      </div>
    </div>
  );
}

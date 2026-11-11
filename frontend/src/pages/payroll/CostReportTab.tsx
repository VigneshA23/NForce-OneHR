import { useEffect, useState } from 'react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayrollCostReportResponse, type PayrollRunStatus } from '../../api/payroll';
import { panelStyle, thStyle, tdStyle, fmtCurrency, EmptyRow, SectionHead } from './payrollShared';

/** US-C16 — only buildable once a run leaves DRAFT/SUBMITTED (the backend enforces this; the
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

  return (
    <div>
      {/* No Employees/Gross/Deductions/Net tiles here — the run console's top-level KPI row
          (PayrollRunDetailPage.tsx) already shows those same totals on every step. This tab's
          own job is the department/project breakdown below, not a restatement of the run total. */}
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

import { useEffect, useState } from 'react';
import { PersonCellById } from '../../components/PersonCell';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayrollJoinersExitsResponse } from '../../api/payroll';
import { panelStyle, thStyle, tdStyle, fmtDate, EmptyRow, SectionHead } from './payrollShared';

/** Keka's "New Joinees & Exits" step — purely informational, who joined/left within this run's
 * own cycle; joiner/exit proration is already applied to the payslip itself at generation time. */
export function JoinersExitsTab({ runId }: { runId: string }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [data, setData] = useState<PayrollJoinersExitsResponse | null>(null);

  useEffect(() => {
    payrollApi.getJoinersExits(runId, token).then(setData).catch(() => showToast('error', 'Failed to load joiners & exits'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, token]);

  if (!data) {
    return <div style={{ padding: 24, color: 'var(--txt-mut)' }}>Loading…</div>;
  }

  return (
    <div>
      <SectionHead title="New Joinees" />
      <div style={{ ...panelStyle, marginBottom: 24 }}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Employee', 'Joining Date'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
          <tbody>
            {data.joiners.map(j => (
              <tr key={j.employeeId}>
                <td style={tdStyle}><PersonCellById userId={j.employeeId} name={j.employeeName} /></td>
                <td style={tdStyle}>{fmtDate(j.date)}</td>
              </tr>
            ))}
            {data.joiners.length === 0 && <EmptyRow cols={2} msg="No new joiners this cycle." />}
          </tbody>
        </table>
      </div>

      <SectionHead title="Exits" />
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Employee', 'Last Working Day'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
          <tbody>
            {data.exits.map(x => (
              <tr key={x.employeeId}>
                <td style={tdStyle}><PersonCellById userId={x.employeeId} name={x.employeeName} /></td>
                <td style={tdStyle}>{fmtDate(x.date)}</td>
              </tr>
            ))}
            {data.exits.length === 0 && <EmptyRow cols={2} msg="No exits this cycle." />}
          </tbody>
        </table>
      </div>
    </div>
  );
}

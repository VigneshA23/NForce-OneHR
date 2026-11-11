import { useEffect, useState } from 'react';
import { PersonCellById } from '../../components/PersonCell';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type ArrearsResponse } from '../../api/payroll';
import { panelStyle, thStyle, tdStyle, fmtCurrency, fmtDate, EmptyRow, StatusBadge, SectionHead } from './payrollShared';

const SOURCE_LABEL: Record<string, string> = {
  SALARY_REVISION: 'Salary Revision', LOP_REVERSAL: 'LOP Reversal', HOLD_RELEASE: 'Hold Release',
};

/** Keka's "Arrears" step — read-only, auto-computed from three triggers (backdated salary
 * revision, LOP reversal, salary hold release). A PENDING row becomes a real "Arrears"/"Arrears
 * Recovery" payslip line item the next time that employee's payslip is (re)generated. */
export function ArrearsTab({ runId }: { runId: string }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [rows, setRows] = useState<ArrearsResponse[]>([]);

  useEffect(() => {
    payrollApi.getArrears(runId, token).then(setRows).catch(() => showToast('error', 'Failed to load arrears'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, token]);

  const pending = rows.filter(r => r.status === 'PENDING');
  const applied = rows.filter(r => r.status === 'APPLIED');
  const pendingTotal = pending.reduce((sum, r) => sum + r.amount, 0);

  return (
    <div>
      <SectionHead
        title="Arrears"
        action={rows.length > 0 && (
          <span style={{ fontSize: 13, color: 'var(--txt-mut)' }}>
            {pending.length} pending ({fmtCurrency(pendingTotal)}) · {applied.length} already applied
          </span>
        )}
      />
      <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 14 }}>
        Auto-computed from backdated salary revisions, LOP reversals and salary hold releases — never manually entered.
        A PENDING row lands on this employee's next payslip; an APPLIED one already has.
      </div>
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Employee', 'Amount', 'Source', 'Source Cycle', 'Reason', 'Status'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
          <tbody>
            {rows.map(r => (
              <tr key={r.id}>
                <td style={tdStyle}><PersonCellById userId={r.employeeId} name={r.employeeName} /></td>
                <td style={tdStyle}>{fmtCurrency(r.amount)}{r.amount < 0 ? ' (recovery)' : ''}</td>
                <td style={tdStyle}>{SOURCE_LABEL[r.sourceType] ?? r.sourceType}</td>
                <td style={tdStyle}>{fmtDate(r.sourceCycleStart)} – {fmtDate(r.sourceCycleEnd)}</td>
                <td style={tdStyle}>{r.reason}</td>
                <td style={tdStyle}><StatusBadge status={r.status} /></td>
              </tr>
            ))}
            {rows.length === 0 && <EmptyRow cols={6} msg="No arrears for this run's employees." />}
          </tbody>
        </table>
      </div>
    </div>
  );
}

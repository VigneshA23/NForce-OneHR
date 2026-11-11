import { useEffect, useState } from 'react';
import { PersonCellById } from '../../components/PersonCell';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayslipResponse } from '../../api/payroll';
import { panelStyle, thStyle, tdStyle, EmptyRow, SectionHead, useEmployeeDirectory } from './payrollShared';

/** Keka's "Payable Units" step — the days-worked breakdown behind each payslip's proration, same
 * per-employee numbers PayslipsTab's own detail modal already shows, just surfaced as its own
 * at-a-glance list. No new backend — Payslip already stores all four fields. */
export function PayableUnitsTab({ runId }: { runId: string }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [payslips, setPayslips] = useState<PayslipResponse[]>([]);
  const { nameFor } = useEmployeeDirectory();

  useEffect(() => {
    payrollApi.listPayslips(runId, token).then(setPayslips).catch(() => showToast('error', 'Failed to load payable units'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, token]);

  return (
    <div>
      <SectionHead title="Payable Units" />
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{['Employee', 'Cycle Days', 'Employed Days', 'LOP Days', 'Payable Days', 'Proration Factor'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {payslips.map(p => (
              <tr key={p.id}>
                <td style={tdStyle}><PersonCellById userId={p.employeeId} name={nameFor(p.employeeId)} /></td>
                <td style={tdStyle}>{p.cycleDays}</td>
                <td style={tdStyle}>{p.employedDays}</td>
                <td style={tdStyle}>{p.lopDays}</td>
                <td style={tdStyle}>{(p.employedDays - p.lopDays).toFixed(2)}</td>
                <td style={tdStyle}>{p.prorationFactor}</td>
              </tr>
            ))}
            {payslips.length === 0 && <EmptyRow cols={6} msg="No payslips yet — generate the draft first." />}
          </tbody>
        </table>
      </div>
    </div>
  );
}

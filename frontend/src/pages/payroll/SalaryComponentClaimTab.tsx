import { useEffect, useState } from 'react';
import { PersonCellById } from '../../components/PersonCell';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayrollClaimResponse } from '../../api/payroll';
import { panelStyle, thStyle, tdStyle, fmtCurrency, fmtDate, EmptyRow, SectionHead, FilterBar } from './payrollShared';

/** Keka's "Salary Component Claim"/"Expenses" steps - read-only visibility into expense claims HR
 * already routed "with payroll" in the Expenses module. Doesn't feed payslip math - see the
 * backend PayrollClaimService's own Javadoc for why this stays a reporting view. */
export function SalaryComponentClaimTab({ runId }: { runId: string }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [claims, setClaims] = useState<PayrollClaimResponse[]>([]);
  const [search, setSearch] = useState('');

  useEffect(() => {
    payrollApi.getClaims(runId, token).then(setClaims).catch(() => showToast('error', 'Failed to load claims'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, token]);

  const filtered = claims.filter(c => c.employeeName.toLowerCase().includes(search.trim().toLowerCase()));

  return (
    <div>
      <SectionHead title="Claims Cleared for Payroll" />
      <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 14 }}>
        Expense claims HR has already routed "with payroll" from the Expenses module - visibility only, not a payslip input.
      </div>
      <FilterBar search={search} onSearchChange={setSearch} />
      <div style={panelStyle}>
        <table className="nf-pr-center nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Employee', 'Category', 'Amount', 'Expense Date', 'Status'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
          <tbody>
            {filtered.map(c => (
              <tr key={c.id}>
                <td style={tdStyle}><PersonCellById userId={c.employeeId} name={c.employeeName} /></td>
                <td style={tdStyle}>{c.categoryName}</td>
                <td style={tdStyle}>{fmtCurrency(c.amount)}</td>
                <td style={tdStyle}>{fmtDate(c.expenseDate)}</td>
                <td style={tdStyle}>{c.status.replace(/_/g, ' ')}</td>
              </tr>
            ))}
            {filtered.length === 0 && (
              <EmptyRow cols={5} msg={claims.length === 0 ? 'No claims cleared for payroll this cycle.' : 'No matches for your search.'} />
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

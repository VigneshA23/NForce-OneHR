import { useEffect, useState } from 'react';
import { Wallet, Download, IndianRupee } from 'lucide-react';
import PageTitleBlock from '../components/PageTitleBlock';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { payslipsApi } from '../api/payslips';
import { payrollApi, type EmployeeCompensationResponse } from '../api/payroll';
import { panelStyle, thStyle, tdStyle, fmtCurrency, fmtDate, EmptyRow, BtnGhost, SectionHead, StatTile } from './payroll/payrollShared';

function formatPeriod(period: string) {
  const [year, month] = period.split('-');
  return new Date(Number(year), Number(month) - 1, 1).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
}

/** Employee self-service — mirrors MyRequestsPage.tsx's unlisted, direct-route convention (no
 * nav.config.ts entry, no permission gate: reached via a dashboard link, same as that page). */
export default function MyFinancesPage() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [periods, setPeriods] = useState<string[]>([]);
  const [downloading, setDownloading] = useState<string | null>(null);
  const [current, setCurrent] = useState<EmployeeCompensationResponse | null>(null);
  const [history, setHistory] = useState<EmployeeCompensationResponse[]>([]);

  useEffect(() => {
    payslipsApi.listMyPeriods(token).then(setPeriods).catch(() => showToast('error', 'Failed to load your payslips'));
    payrollApi.getMyCurrentCompensation(token).then(setCurrent).catch(() => showToast('error', 'Failed to load your compensation'));
    payrollApi.getMyCompensationHistory(token).then(setHistory).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  async function download(period: string) {
    setDownloading(period);
    try {
      await payslipsApi.downloadMyPayslip(period, token);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to download payslip');
    } finally {
      setDownloading(null);
    }
  }

  return (
    <div style={{ padding: '24px 28px' }}>
      <div style={{ marginBottom: 20 }}>
        <PageTitleBlock icon={Wallet}>
          <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, letterSpacing: '-.01em', color: 'var(--txt)' }}>My Finances</h1>
          <p style={{ margin: '2px 0 0', fontSize: 13, color: 'var(--txt-mut)' }}>Your compensation and payslip history.</p>
        </PageTitleBlock>
      </div>

      {current && (
        <div className="nf-pr-tiles" style={{ marginBottom: 22 }}>
          <StatTile tone="var(--brand)" icon={<IndianRupee size={20} />} label="Current CTC" value={fmtCurrency(current.ctc)} />
          <StatTile tone="var(--info)" icon={<Wallet size={20} />} label="Salary Structure" value={current.structureName} />
          <StatTile tone="var(--ok)" icon={<IndianRupee size={20} />} label="Effective From" value={fmtDate(current.effectiveFrom)} />
        </div>
      )}

      {history.length > 0 && (
        <div style={{ marginBottom: 24 }}>
          <SectionHead title="Compensation History" />
          <div style={panelStyle}>
            <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>{['Structure', 'CTC', 'Effective From', 'Effective To'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {history.map(h => (
                  <tr key={h.id}>
                    <td style={tdStyle}>{h.structureName}</td>
                    <td style={tdStyle}>{fmtCurrency(h.ctc)}</td>
                    <td style={tdStyle}>{fmtDate(h.effectiveFrom)}</td>
                    <td style={tdStyle}>{h.effectiveTo ? fmtDate(h.effectiveTo) : 'Current'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <SectionHead title="Payslips" />
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{['Pay Period', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {periods.map(period => (
              <tr key={period}>
                <td style={tdStyle}>{formatPeriod(period)}</td>
                <td style={tdStyle}>
                  <BtnGhost onClick={() => download(period)} disabled={downloading === period}>
                    <Download size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />
                    {downloading === period ? 'Downloading…' : 'Download PDF'}
                  </BtnGhost>
                </td>
              </tr>
            ))}
            {periods.length === 0 && <EmptyRow cols={2} msg="No payslips available yet." />}
          </tbody>
        </table>
      </div>
    </div>
  );
}

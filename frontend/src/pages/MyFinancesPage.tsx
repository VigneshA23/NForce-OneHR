import { useEffect, useState, type ReactNode } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { Wallet, Download, IndianRupee, CalendarDays, FileText } from 'lucide-react';
import PageTitleBlock from '../components/PageTitleBlock';
import { TabBar, type TabDef } from '../components/TabBar';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { payslipsApi } from '../api/payslips';
import { payrollApi, type EmployeeCompensationResponse } from '../api/payroll';
import { profileApi, type ProfileData } from '../api/profile';
import { ReadField } from './profile/shared';
import { panelStyle, thStyle, tdStyle, fmtCurrency, fmtDate, EmptyRow, BtnGhost, SectionHead, StatTile } from './payroll/payrollShared';

const BASE = '/myfinances';

// US-F01: the tab structure is the same for every role, and lives in the URL so a reload or a
// pasted link opens the same view. Unknown segments fall back to Summary.
const TOP_TABS: TabDef<string>[] = [
  { key: 'summary', label: 'Summary' },
  { key: 'pay', label: 'My Pay' },
  { key: 'tax', label: 'Manage Tax' },
];
const PAY_TABS: TabDef<string>[] = [
  { key: 'salary', label: 'My Salary' },
  { key: 'payslips', label: 'Payslips' },
  { key: 'income-tax', label: 'Income Tax' },
];
const TAX_TABS: TabDef<string>[] = [
  { key: 'declaration', label: 'Declaration' },
  { key: 'previous-income', label: 'Previous Income' },
  { key: 'forms', label: 'Forms' },
  { key: 'tax-filing', label: 'Tax Filing' },
  { key: 'investments', label: 'Tax Saving Investment' },
];
const SUBTABS: Record<string, TabDef<string>[]> = { pay: PAY_TABS, tax: TAX_TABS };

function formatPeriod(period: string) {
  const [year, month] = period.split('-');
  return new Date(Number(year), Number(month) - 1, 1).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
}

/** Resolves the URL under /myfinances into [tab, subTab], or the canonical path to redirect to. */
function resolveRoute(pathname: string): { tab: string; sub: string | null } | { redirect: string } {
  const parts = pathname.slice(BASE.length).split('/').filter(Boolean);
  const tab = parts[0];
  if (!tab) return { redirect: `${BASE}/summary` };
  if (!TOP_TABS.some(t => t.key === tab)) return { redirect: `${BASE}/summary` };
  const subs = SUBTABS[tab];
  if (!subs) return parts.length === 1 ? { tab, sub: null } : { redirect: `${BASE}/${tab}` };
  if (!parts[1]) return { redirect: `${BASE}/${tab}/${subs[0].key}` };
  if (!subs.some(s => s.key === parts[1]) || parts.length > 2) return { redirect: `${BASE}/summary` };
  return { tab, sub: parts[1] };
}

interface FinanceData {
  loaded: boolean;
  current: EmployeeCompensationResponse | null;
  history: EmployeeCompensationResponse[];
  periods: string[];
  profile: ProfileData | null;
}

/**
 * Employee self-service for every role (US-F01). Always the signed-in user's own data: every call here
 * resolves the user from the login token server-side, nothing takes a user id. Viewing someone else's
 * finances is a separate Super Admin screen (US-F15), deliberately not reachable from here.
 */
export default function MyFinancesPage() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const [downloading, setDownloading] = useState<string | null>(null);
  const [data, setData] = useState<FinanceData>({ loaded: false, current: null, history: [], periods: [], profile: null });

  useEffect(() => {
    let cancelled = false;
    // A failed call is treated as "nothing on file" (a system account with no employee record gets
    // an error from these endpoints) so every tab can show the empty state instead of an error.
    Promise.allSettled([
      payrollApi.getMyCurrentCompensation(token),
      payrollApi.getMyCompensationHistory(token),
      payslipsApi.listMyPeriods(token),
      profileApi.get(token),
    ]).then(([current, history, periods, profile]) => {
      if (cancelled) return;
      setData({
        loaded: true,
        current: current.status === 'fulfilled' ? current.value : null,
        history: history.status === 'fulfilled' ? history.value : [],
        periods: periods.status === 'fulfilled' ? periods.value : [],
        profile: profile.status === 'fulfilled' ? profile.value : null,
      });
    });
    return () => { cancelled = true; };
  }, [token]);

  const route = resolveRoute(pathname);
  if ('redirect' in route) return <Navigate to={route.redirect} replace />;
  const { tab, sub } = route;

  const noPayrollRecord = data.loaded && !data.current && data.history.length === 0 && data.periods.length === 0;

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

  let body: ReactNode;
  if (!data.loaded) body = <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading your finances…</div>;
  else if (noPayrollRecord) body = <NoPayrollRecord />;
  else if (tab === 'summary') body = <SummaryPanel data={data} onRequestChange={() => navigate('/profile')} />;
  else if (sub === 'salary') body = <SalaryPanel current={data.current} history={data.history} />;
  else if (sub === 'payslips') body = <PayslipsPanel periods={data.periods} downloading={downloading} onDownload={download} />;
  else body = <ComingSoon title={[...PAY_TABS, ...TAX_TABS].find(t => t.key === sub)?.label ?? 'This section'} />;

  return (
    <div style={{ padding: '24px 28px' }}>
      <div style={{ marginBottom: 20 }}>
        <PageTitleBlock icon={Wallet}>
          <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, letterSpacing: '-.01em', color: 'var(--txt)' }}>My Finances</h1>
          <p style={{ margin: '2px 0 0', fontSize: 13, color: 'var(--txt-mut)' }}>Your pay and tax information.</p>
        </PageTitleBlock>
      </div>

      <TabBar tabs={TOP_TABS} active={tab} onChange={k => navigate(`${BASE}/${k}`)} ariaLabel="My Finances sections" style={{ marginBottom: 16 }} />
      {SUBTABS[tab] && sub && (
        <TabBar tabs={SUBTABS[tab]} active={sub} onChange={k => navigate(`${BASE}/${tab}/${k}`)} size="sm" className="nf-boxedtabs"
          ariaLabel={`${TOP_TABS.find(t => t.key === tab)?.label} sections`} style={{ marginBottom: 16 }} />
      )}
      {body}
    </div>
  );
}

function NoPayrollRecord() {
  return (
    <div style={{ ...panelStyle, padding: '40px 24px', textAlign: 'center' }}>
      <Wallet size={28} style={{ color: 'var(--txt-dim)', marginBottom: 10 }} />
      <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--txt)' }}>No payroll record linked to this account</div>
      <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>
        Your pay and tax information will appear here once payroll details exist for you.
      </div>
    </div>
  );
}

function ComingSoon({ title }: { title: ReactNode }) {
  return (
    <div style={{ ...panelStyle, padding: '40px 24px', textAlign: 'center' }}>
      <FileText size={28} style={{ color: 'var(--txt-dim)', marginBottom: 10 }} />
      <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--txt)' }}>{title}</div>
      <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>This section is not available yet.</div>
    </div>
  );
}

function SummaryPanel({ data, onRequestChange }: { data: FinanceData; onRequestChange: () => void }) {
  const { current, profile } = data;
  const hasStatutory = profile && (profile.bankAccountNumber || profile.panNumber || profile.uanNumber || profile.esiNumber);
  return (
    <>
      {current && (
        <div className="nf-pr-tiles" style={{ marginBottom: 22 }}>
          <StatTile tone="var(--brand)" icon={<IndianRupee size={20} />} label="Current CTC" value={fmtCurrency(current.ctc)} />
          <StatTile tone="var(--info)" icon={<Wallet size={20} />} label="Salary Structure" value={current.structureName} />
          <StatTile tone="var(--ok)" icon={<CalendarDays size={20} />} label="Effective From" value={fmtDate(current.effectiveFrom)} />
        </div>
      )}

      {hasStatutory && profile && (
        <div style={{ marginBottom: 24 }}>
          <SectionHead title="Bank & Statutory Details" action={<BtnGhost onClick={onRequestChange}>Request change</BtnGhost>} />
          <div style={{ ...panelStyle, padding: '18px 22px' }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 16 }}>
              <ReadField label="Bank Account Number" value={profile.bankAccountNumber} />
              <ReadField label="Bank Name & IFSC" value={profile.bankName ? `${profile.bankName}${profile.bankIfsc ? ` · IFSC: ${profile.bankIfsc}` : ''}` : null} />
              <ReadField label="PAN" value={profile.panNumber} />
              <ReadField label="UAN" value={profile.uanNumber} />
              <ReadField label="ESI Number" value={profile.esiNumber} />
            </div>
            <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginTop: 14 }}>
              Read-only and masked for privacy. Use Request change to ask for an update.
            </div>
          </div>
        </div>
      )}
    </>
  );
}

function SalaryPanel({ current, history }: { current: EmployeeCompensationResponse | null; history: EmployeeCompensationResponse[] }) {
  return (
    <>
      {current && (
        <div className="nf-pr-tiles" style={{ marginBottom: 22 }}>
          <StatTile tone="var(--brand)" icon={<IndianRupee size={20} />} label="Current CTC" value={fmtCurrency(current.ctc)} />
          <StatTile tone="var(--info)" icon={<Wallet size={20} />} label="Salary Structure" value={current.structureName} />
          <StatTile tone="var(--ok)" icon={<CalendarDays size={20} />} label="Effective From" value={fmtDate(current.effectiveFrom)} />
        </div>
      )}
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
            {history.length === 0 && <EmptyRow cols={4} msg="No compensation history yet." />}
          </tbody>
        </table>
      </div>
    </>
  );
}

function PayslipsPanel({ periods, downloading, onDownload }: { periods: string[]; downloading: string | null; onDownload: (period: string) => void }) {
  return (
    <>
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
                  <BtnGhost onClick={() => onDownload(period)} disabled={downloading === period}>
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
    </>
  );
}

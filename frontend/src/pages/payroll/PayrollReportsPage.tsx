import { useEffect, useMemo, useState } from 'react';
import { BarChart3, Search, Star, History, IndianRupee, TrendingUp, Layers, CalendarRange, Landmark } from 'lucide-react';
import PageTitleBlock from '../../components/PageTitleBlock';
import { PersonCellById } from '../../components/PersonCell';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import {
  payrollApi, type EmployeeCompensationResponse, type CurrentSalaryStructureRowResponse,
  type EmployeeFinancialDetailsResponse,
} from '../../api/payroll';
import { hasPermission } from '../../lib/permissions';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, fmtDate, EmptyRow, FilterBar, FullScreenTask,
  useEmployeeDirectory, WithFaqPanel, labelStyle, inputStyle, BtnPrimary, type FaqItem,
} from './payrollShared';
import { AllChangesHistory } from './EmployeeCompensationTab';
import './payroll.css';

// Favourites/recently-used are a per-browser convenience, same spirit as a remembered tab or
// filter - never synced, never read back by anyone else, so localStorage is the right place for
// them rather than a new backend table just to persist two small id lists.
const FAVOURITES_KEY = 'nf-payroll-report-favourites';
const RECENT_KEY = 'nf-payroll-report-recent';

function readIds(key: string): string[] {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

function writeIds(key: string, ids: string[]) {
  try { localStorage.setItem(key, JSON.stringify(ids)); } catch { /* private window / blocked storage - fine to drop */ }
}

type ReportId = 'current-ctc' | 'salary-revision' | 'current-salary-structure' | 'employee-ctc-range' | 'financial-information';

interface ReportDef {
  id: ReportId;
  title: string;
  description: string;
  category: 'compensation' | 'financial';
}

const REPORTS: ReportDef[] = [
  { id: 'current-ctc', title: 'Current CTC Report', description: "Every employee's current salary structure and CTC, as of today.", category: 'compensation' },
  { id: 'current-salary-structure', title: 'Current Salary Structure', description: "Every employee's current salary breakup, component by component.", category: 'compensation' },
  { id: 'salary-revision', title: 'Salary Revision Report', description: 'Every compensation assignment or revision ever made, across every employee.', category: 'compensation' },
  { id: 'employee-ctc-range', title: 'Employee CTC Report', description: 'Compensation details for a chosen date range, across every employee.', category: 'compensation' },
  { id: 'financial-information', title: 'Financial Information', description: "Every employee's bank, PAN, UAN and ESI details in one table.", category: 'financial' },
];

const CATEGORIES: { key: 'all' | ReportDef['category']; label: string }[] = [
  { key: 'all', label: 'Reports Home' },
  { key: 'compensation', label: 'Compensation Reports' },
  { key: 'financial', label: 'Financial Reports' },
];

function CurrentCtcReport() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { nameFor } = useEmployeeDirectory();
  const [rows, setRows] = useState<EmployeeCompensationResponse[]>([]);
  const [search, setSearch] = useState('');

  useEffect(() => {
    payrollApi.listAllCurrentCompensation(token).then(setRows).catch(() => showToast('error', 'Failed to load the current CTC report'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const filtered = rows.filter(r => nameFor(r.employeeId).toLowerCase().includes(search.trim().toLowerCase()));

  return (
    <div>
      <FilterBar search={search} onSearchChange={setSearch} />
      <div style={panelStyle}>
        <table className="nf-pr-center nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Employee', 'Structure', 'CTC', 'Effective From'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
          <tbody>
            {filtered.map(r => (
              <tr key={r.id}>
                <td style={tdStyle}><PersonCellById userId={r.employeeId} name={nameFor(r.employeeId)} /></td>
                <td style={tdStyle}>{r.structureName}</td>
                <td style={tdStyle}>{fmtCurrency(r.ctc)}</td>
                <td style={tdStyle}>{fmtDate(r.effectiveFrom)}</td>
              </tr>
            ))}
            {filtered.length === 0 && (
              <EmptyRow cols={4} msg={rows.length === 0 ? 'No current compensation assigned to anyone yet.' : 'No matches for your search.'} />
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** Component columns vary per employee (whichever SalaryStructure they're on) - the union of
 * every component name seen across all rows becomes the column set, in first-seen order, same
 * "blank where that employee has no line item" idea PayRegisterExcelService uses for its own
 * dynamic columns, just computed client-side here since this is an on-screen table, not a file. */
function CurrentSalaryStructureReport() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { nameFor } = useEmployeeDirectory();
  const [rows, setRows] = useState<CurrentSalaryStructureRowResponse[]>([]);
  const [search, setSearch] = useState('');

  useEffect(() => {
    payrollApi.listAllCurrentSalaryStructure(token).then(setRows).catch(() => showToast('error', 'Failed to load the current salary structure report'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const columns = useMemo(() => {
    const seen: string[] = [];
    for (const r of rows) for (const c of r.components) if (!seen.includes(c.componentName)) seen.push(c.componentName);
    return seen;
  }, [rows]);

  const filtered = rows.filter(r => nameFor(r.employeeId).toLowerCase().includes(search.trim().toLowerCase()));

  return (
    <div>
      <FilterBar search={search} onSearchChange={setSearch} />
      <div style={panelStyle}>
        <div style={{ overflowX: 'auto' }}>
        <table className="nf-pr-center nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Employee', 'Structure', 'CTC', 'Effective From', ...columns].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
          <tbody>
            {filtered.map(r => {
              const byName = new Map(r.components.map(c => [c.componentName, c.annualAmount]));
              return (
                <tr key={r.employeeId}>
                  <td style={tdStyle}><PersonCellById userId={r.employeeId} name={nameFor(r.employeeId)} /></td>
                  <td style={tdStyle}>{r.structureName}</td>
                  <td style={tdStyle}>{fmtCurrency(r.ctc)}</td>
                  <td style={tdStyle}>{fmtDate(r.effectiveFrom)}</td>
                  {columns.map(name => <td key={name} style={tdStyle}>{byName.has(name) ? fmtCurrency(byName.get(name)!) : '-'}</td>)}
                </tr>
              );
            })}
            {filtered.length === 0 && (
              <EmptyRow cols={4 + columns.length} msg={rows.length === 0 ? 'No current compensation assigned to anyone yet.' : 'No matches for your search.'} />
            )}
          </tbody>
        </table>
        </div>
      </div>
    </div>
  );
}

function EmployeeCtcRangeReport() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { nameFor } = useEmployeeDirectory();
  const [start, setStart] = useState(() => `${new Date().getFullYear()}-01-01`);
  const [end, setEnd] = useState(() => new Date().toISOString().slice(0, 10));
  const [rows, setRows] = useState<EmployeeCompensationResponse[]>([]);
  const [search, setSearch] = useState('');

  function load() {
    payrollApi.listCompensationBetween(start, end, token).then(setRows).catch(() => showToast('error', 'Failed to load the employee CTC report'));
  }

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { load(); }, []);

  const filtered = rows.filter(r => nameFor(r.employeeId).toLowerCase().includes(search.trim().toLowerCase()));

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <div>
          <label style={labelStyle}>From</label>
          <input type="date" value={start} onChange={e => setStart(e.target.value)} style={inputStyle} />
        </div>
        <div>
          <label style={labelStyle}>To</label>
          <input type="date" value={end} onChange={e => setEnd(e.target.value)} style={inputStyle} />
        </div>
        <BtnPrimary onClick={load}>Apply</BtnPrimary>
      </div>
      <FilterBar search={search} onSearchChange={setSearch} />
      <div style={panelStyle}>
        <table className="nf-pr-center nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Employee', 'Structure', 'CTC', 'Effective From', 'Effective To'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
          <tbody>
            {filtered.map(r => (
              <tr key={r.id}>
                <td style={tdStyle}><PersonCellById userId={r.employeeId} name={nameFor(r.employeeId)} /></td>
                <td style={tdStyle}>{r.structureName}</td>
                <td style={tdStyle}>{fmtCurrency(r.ctc)}</td>
                <td style={tdStyle}>{fmtDate(r.effectiveFrom)}</td>
                <td style={tdStyle}>{r.effectiveTo ? fmtDate(r.effectiveTo) : 'Current'}</td>
              </tr>
            ))}
            {filtered.length === 0 && (
              <EmptyRow cols={5} msg={rows.length === 0 ? 'No compensation rows in this date range.' : 'No matches for your search.'} />
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function FinancialInformationReport() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [rows, setRows] = useState<EmployeeFinancialDetailsResponse[]>([]);
  const [search, setSearch] = useState('');

  useEffect(() => {
    payrollApi.listAllFinancialDetails(token).then(setRows).catch(() => showToast('error', 'Failed to load the financial information report'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const filtered = rows.filter(r => r.employeeName.toLowerCase().includes(search.trim().toLowerCase()));

  return (
    <div>
      <FilterBar search={search} onSearchChange={setSearch} />
      <div style={panelStyle}>
        <div style={{ overflowX: 'auto' }}>
        <table className="nf-pr-center nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Employee', 'Employee Code', 'Bank Name', 'Bank Account', 'IFSC', 'PAN', 'UAN', 'ESI'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
          <tbody>
            {filtered.map(r => (
              <tr key={r.employeeId}>
                <td style={tdStyle}><PersonCellById userId={r.employeeId} name={r.employeeName} /></td>
                <td style={tdStyle}>{r.employeeCode}</td>
                <td style={tdStyle}>{r.bankName ?? '-'}</td>
                <td style={tdStyle}>{r.bankAccountNumber ?? '-'}</td>
                <td style={tdStyle}>{r.bankIfsc ?? '-'}</td>
                <td style={tdStyle}>{r.panNumber ?? '-'}</td>
                <td style={tdStyle}>{r.uanNumber ?? '-'}</td>
                <td style={tdStyle}>{r.esiNumber ?? '-'}</td>
              </tr>
            ))}
            {filtered.length === 0 && (
              <EmptyRow cols={8} msg={rows.length === 0 ? 'No employees yet.' : 'No matches for your search.'} />
            )}
          </tbody>
        </table>
        </div>
      </div>
    </div>
  );
}

const REPORT_ICON: Record<ReportId, React.ReactNode> = {
  'current-ctc': <IndianRupee size={18} />,
  'current-salary-structure': <Layers size={18} />,
  'salary-revision': <TrendingUp size={18} />,
  'employee-ctc-range': <CalendarRange size={18} />,
  'financial-information': <Landmark size={18} />,
};

/** Keka's "Reports Home" (§3.6) - scoped to the reports this app can genuinely back today (all
 * reuse existing data/endpoints, no new aggregation invented for this screen). Favourites/
 * Recently Used are a per-browser preference (localStorage), not server state. The left category
 * panel only has two real categories - "Compensation Reports" and "Financial Reports" - rather
 * than Keka's full list (Payroll Run Reports, YTD, Statutory Forms, Loan, F&F...), none of which
 * this app has data for. */
const FAQ_ITEMS: FaqItem[] = [
  { q: 'What reports are actually available today?', a: 'Five: Current CTC, Current Salary Structure (full component-by-component breakup), Salary Revision, Employee CTC for a chosen date range, and Financial Information (bank/PAN/UAN/ESI). All reuse existing compensation/employee data - nothing is a separate aggregation job.' },
  { q: 'How do Favourites and Recently Used work?', a: 'Click the star on a report to favourite it, or just open a report to have it show up under Recently Used. Both lists are remembered on this browser only - they’re not shared with other HR users or saved on the server.' },
  { q: 'Can I export a report?', a: 'Not yet - these open as an on-screen table, not a downloadable file. The Payslips tab’s "Export to Excel" is the only downloadable export in the payroll module today.' },
  { q: 'Why don’t I see Bonus, YTD, Loan, Full & Final Settlement, Perks or PF ECR reports?', a: 'This app doesn’t have the underlying data or concept for any of those yet - no bonus status/revision tracking, no org-wide YTD aggregation, no loans, no settlement workflow, no perquisites, no statutory e-filing. Rather than list them as empty placeholders, they’re simply not here until that data exists.' },
];

export default function PayrollReportsPage() {
  const permissions = useAuthStore(s => s.permissions);
  const canView = hasPermission(permissions, 'payroll', 'VIEW', 'ALL');
  const [category, setCategory] = useState<'all' | ReportDef['category']>('all');
  const [search, setSearch] = useState('');
  const [favourites, setFavourites] = useState<string[]>(() => readIds(FAVOURITES_KEY));
  const [recent, setRecent] = useState<string[]>(() => readIds(RECENT_KEY));
  const [open, setOpen] = useState<ReportId | null>(null);

  function toggleFavourite(id: string) {
    setFavourites(prev => {
      const next = prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id];
      writeIds(FAVOURITES_KEY, next);
      return next;
    });
  }

  function openReport(id: ReportId) {
    setOpen(id);
    setRecent(prev => {
      const next = [id, ...prev.filter(x => x !== id)].slice(0, 5);
      writeIds(RECENT_KEY, next);
      return next;
    });
  }

  const filteredReports = useMemo(() => REPORTS
    .filter(r => category === 'all' || r.category === category)
    .filter(r => r.title.toLowerCase().includes(search.trim().toLowerCase())), [category, search]);

  const favouriteReports = REPORTS.filter(r => favourites.includes(r.id));
  const recentReports = recent.map(id => REPORTS.find(r => r.id === id)).filter((r): r is ReportDef => !!r);

  if (!canView) {
    return <div style={{ padding: '24px 28px', color: 'var(--txt-mut)' }}>You don't have permission to view payroll reports.</div>;
  }

  const openDef = REPORTS.find(r => r.id === open);

  return (
    <div style={{ padding: '24px 28px' }}>
      <PageTitleBlock icon={BarChart3} card>
        <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, letterSpacing: '-.01em', color: 'var(--txt)' }}>Payroll Reports</h1>
        <p style={{ margin: '2px 0 0', fontSize: 13, color: 'var(--txt-mut)' }}>CTC, salary structure, revision history and financial details, across every employee.</p>
      </PageTitleBlock>

      <WithFaqPanel items={FAQ_ITEMS} title="Payroll Reports Help">
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(200px, 260px) 1fr', gap: 20, alignItems: 'start' }}>
        <div style={panelStyle}>
          {CATEGORIES.map(c => {
            const active = c.key === category;
            return (
              <button
                key={c.key} type="button" onClick={() => setCategory(c.key)}
                style={{
                  display: 'block', width: '100%', textAlign: 'left', padding: '12px 16px', border: 'none',
                  borderBottom: '1px solid var(--line)', cursor: 'pointer', font: 'inherit', fontSize: 13.5,
                  background: active ? 'color-mix(in srgb, var(--brand) 10%, var(--panel))' : 'var(--panel)',
                  color: active ? 'var(--brand)' : 'var(--txt)', fontWeight: active ? 700 : 400,
                }}
              >
                {c.label}
              </button>
            );
          })}
        </div>

        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 20, maxWidth: 420, border: '1px solid var(--line)', borderRadius: 10, padding: '0 12px', background: 'var(--panel)' }}>
            <Search size={14} style={{ color: 'var(--txt-dim)', flexShrink: 0 }} />
            <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search in all reports" style={{ flex: 1, border: 'none', background: 'none', padding: '10px 0', fontSize: 13, color: 'var(--txt)', outline: 'none' }} />
          </div>

          {category === 'all' && favouriteReports.length > 0 && (
            <div style={{ marginBottom: 22 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 10 }}>
                <Star size={14} style={{ color: 'var(--star, #F5B700)' }} /> Favourites
              </div>
              <ReportGrid reports={favouriteReports} favourites={favourites} onToggleFavourite={toggleFavourite} onOpen={openReport} />
            </div>
          )}

          {category === 'all' && recentReports.length > 0 && (
            <div style={{ marginBottom: 22 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 10 }}>
                <History size={14} style={{ color: 'var(--txt-dim)' }} /> Recently Used
              </div>
              <ReportGrid reports={recentReports} favourites={favourites} onToggleFavourite={toggleFavourite} onOpen={openReport} />
            </div>
          )}

          <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 10 }}>
            {category === 'all' ? 'All Reports' : CATEGORIES.find(c => c.key === category)?.label}
          </div>
          <ReportGrid reports={filteredReports} favourites={favourites} onToggleFavourite={toggleFavourite} onOpen={openReport} />
          {filteredReports.length === 0 && <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>No reports match your search.</div>}
        </div>
      </div>
      </WithFaqPanel>

      {open && openDef && (
        <FullScreenTask title={openDef.title} subtitle={openDef.description} onClose={() => setOpen(null)} actions={null}>
          {open === 'current-ctc' && <CurrentCtcReport />}
          {open === 'current-salary-structure' && <CurrentSalaryStructureReport />}
          {open === 'salary-revision' && <AllChangesHistory />}
          {open === 'employee-ctc-range' && <EmployeeCtcRangeReport />}
          {open === 'financial-information' && <FinancialInformationReport />}
        </FullScreenTask>
      )}
    </div>
  );
}

function ReportGrid({ reports, favourites, onToggleFavourite, onOpen }: {
  reports: ReportDef[]; favourites: string[]; onToggleFavourite: (id: string) => void; onOpen: (id: ReportId) => void;
}) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 14, marginBottom: 4 }}>
      {reports.map(r => {
        const isFavourite = favourites.includes(r.id);
        return (
          <div key={r.id} style={{ ...panelStyle, padding: '16px 18px', display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 10 }}>
            <button type="button" onClick={() => onOpen(r.id)} style={{ background: 'none', border: 'none', padding: 0, textAlign: 'left', cursor: 'pointer', font: 'inherit', display: 'flex', alignItems: 'flex-start', gap: 10 }}>
              <span style={{ flexShrink: 0, width: 32, height: 32, borderRadius: 9, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: 'var(--brand)', background: 'color-mix(in srgb, var(--brand) 12%, var(--panel))' }}>{REPORT_ICON[r.id]}</span>
              <span>
                <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--brand)', marginBottom: 4 }}>{r.title}</div>
                <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>{r.description}</div>
              </span>
            </button>
            <button
              type="button" onClick={() => onToggleFavourite(r.id)} aria-label={isFavourite ? 'Remove from favourites' : 'Add to favourites'}
              style={{ background: 'none', border: 'none', cursor: 'pointer', color: isFavourite ? 'var(--star, #F5B700)' : 'var(--txt-dim)', flexShrink: 0, padding: 2 }}
            >
              <Star size={16} fill={isFavourite ? 'currentColor' : 'none'} />
            </button>
          </div>
        );
      })}
    </div>
  );
}

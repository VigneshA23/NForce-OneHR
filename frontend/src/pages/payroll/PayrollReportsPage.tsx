import { useEffect, useMemo, useState } from 'react';
import { BarChart3, Search, Star, History, IndianRupee, TrendingUp } from 'lucide-react';
import PageTitleBlock from '../../components/PageTitleBlock';
import { PersonCellById } from '../../components/PersonCell';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type EmployeeCompensationResponse } from '../../api/payroll';
import { hasPermission } from '../../lib/permissions';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, fmtDate, EmptyRow, FilterBar, FullScreenTask,
  useEmployeeDirectory,
} from './payrollShared';
import { AllChangesHistory } from './EmployeeCompensationTab';
import './payroll.css';

// Favourites/recently-used are a per-browser convenience, same spirit as a remembered tab or
// filter — never synced, never read back by anyone else, so localStorage is the right place for
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
  try { localStorage.setItem(key, JSON.stringify(ids)); } catch { /* private window / blocked storage — fine to drop */ }
}

type ReportId = 'current-ctc' | 'salary-revision';

interface ReportDef {
  id: ReportId;
  title: string;
  description: string;
  category: 'compensation';
}

const REPORTS: ReportDef[] = [
  { id: 'current-ctc', title: 'Current CTC Report', description: "Every employee's current salary structure and CTC, as of today.", category: 'compensation' },
  { id: 'salary-revision', title: 'Salary Revision Report', description: 'Every compensation assignment or revision ever made, across every employee.', category: 'compensation' },
];

const CATEGORIES: { key: 'all' | ReportDef['category']; label: string }[] = [
  { key: 'all', label: 'Reports Home' },
  { key: 'compensation', label: 'Compensation Reports' },
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
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
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

const REPORT_ICON: Record<ReportId, React.ReactNode> = {
  'current-ctc': <IndianRupee size={18} />,
  'salary-revision': <TrendingUp size={18} />,
};

/** Keka's "Reports Home" (§3.6) — scoped to the two reports this app can genuinely back today
 * (both reuse existing data/endpoints, no new aggregation invented for this screen). Favourites/
 * Recently Used are a per-browser preference (localStorage), not server state. The left category
 * panel only has one real category — "Compensation Reports" — rather than Keka's full list
 * (Payroll Run Reports, YTD, Statutory Forms, Loan, F&F...), none of which this app has data for. */
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
      <PageTitleBlock icon={BarChart3} style={{ marginBottom: 20 }}>
        <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, letterSpacing: '-.01em', color: 'var(--txt)' }}>Payroll Reports</h1>
        <p style={{ margin: '2px 0 0', fontSize: 13, color: 'var(--txt-mut)' }}>Current CTC and salary revision history, across every employee.</p>
      </PageTitleBlock>

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

      {open && openDef && (
        <FullScreenTask title={openDef.title} subtitle={openDef.description} onClose={() => setOpen(null)} actions={null}>
          {open === 'current-ctc' && <CurrentCtcReport />}
          {open === 'salary-revision' && <AllChangesHistory />}
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

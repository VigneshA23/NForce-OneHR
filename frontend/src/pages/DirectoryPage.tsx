import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { X, Search, Users, Download, ChevronUp, ChevronDown, ChevronLeft, ChevronRight as ChevronRightIcon, MoreHorizontal } from 'lucide-react';
import * as XLSX from 'xlsx';
import { directoryApi, type DirectoryEntry } from '../api/directory';
import { useAuthStore } from '../store/authStore';
import { inactiveDimStyle } from '../components/EmployeeStatus';
import { EmployeeAvatar } from '../components/EmployeeAvatar';
import { AppreciateButton, KudosModal, type KudosTarget } from './MyTeamPage';
import './DirectoryPage.css';

const PAGE_SIZE = 25;

/* ── Helpers ──────────────────────────────────────── */
// Soft initials-disc tints, picked deterministically per employee so the same person always
// gets the same color. Translucent backgrounds read correctly on both dark and light themes.
const AVATAR_TINTS: [string, string][] = [
  ['rgba(217,72,95,.14)',  '#D9485F'],
  ['rgba(59,111,216,.14)', '#3B6FD8'],
  ['rgba(124,92,214,.15)', '#7C5CD6'],
  ['rgba(31,157,107,.14)', '#1F9D6B'],
  ['rgba(217,119,43,.15)', '#D9772B'],
  ['rgba(27,146,166,.14)', '#1B92A6'],
];

function avatarTint(key: string): [string, string] {
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) | 0;
  return AVATAR_TINTS[Math.abs(h) % AVATAR_TINTS.length];
}

function Avatar({ userId, name, size = 38 }: { userId: string; name: string; size?: number }) {
  const [background, color] = avatarTint(userId || name);
  return (
    <EmployeeAvatar
      userId={userId}
      name={name}
      size={size}
      fontSize={size * 0.36}
      background={background}
      color={color}
      style={{ fontFamily: 'Inter, sans-serif', letterSpacing: '.02em' }}
    />
  );
}

function StatusChip({ active }: { active: boolean }) {
  return (
    <span className={`nf-pd-status ${active ? 'nf-pd-status--active' : 'nf-pd-status--inactive'}`}>
      {active ? 'Active' : 'Inactive'}
    </span>
  );
}

/* Lets a long address wrap at the @ (never mid-word) when the column is narrow. */
function EmailText({ email }: { email: string }) {
  const at = email.indexOf('@');
  if (at <= 0) return <>{email}</>;
  return <>{email.slice(0, at)}<wbr />{email.slice(at)}</>;
}

/* Decorative header artwork — soft layered waves in the accent color, purely visual. */
function HeaderArt() {
  return (
    <svg className="nf-pd-header-art" viewBox="0 0 420 100" preserveAspectRatio="xMaxYMax slice" aria-hidden="true">
      <path d="M150 100 C 230 70, 270 20, 420 8 L420 100 Z" fill="currentColor" opacity=".05" />
      <path d="M230 100 C 290 78, 330 40, 420 30 L420 100 Z" fill="currentColor" opacity=".07" />
      <path d="M300 100 C 340 86, 372 62, 420 56 L420 100 Z" fill="currentColor" opacity=".10" />
    </svg>
  );
}

/* ── Detail drawer ────────────────────────────────── */
function DetailPanel({ entry, onClose, onAppreciate }: { entry: DirectoryEntry; onClose: () => void; onAppreciate?: () => void }) {
  const rows: [string, string | null | undefined][] = [
    ['Employee Code', entry.employeeCode],
    ['Work Email', entry.email],
    ['Department', entry.departmentName],
    ['Designation', entry.designationName],
    ['Location', entry.locationName],
    ['Work Mode', entry.workMode?.replace('_', ' ')],
    ['Employment Type', entry.employmentType?.replace('_', ' ')],
    ['Manager', entry.managerName],
  ];

  return (
    <div className="nf-drawer-responsive nf-drawer-panel nf-pd-drawer" style={{
      position: 'fixed', top: 0, right: 0, bottom: 0, width: 360,
      background: 'var(--panel)', borderLeft: '1px solid var(--line)',
      boxShadow: '-8px 0 32px rgba(0,0,0,.35)', zIndex: 200,
      display: 'flex', flexDirection: 'column', overflowY: 'auto',
    }}>
      <div className="nf-pd-drawer-head">
        <span className="nf-pd-drawer-title">
          Employee Details
        </span>
        <button onClick={onClose} className="nf-pd-drawer-close" aria-label="Close">
          <X size={15} />
        </button>
      </div>
      <div style={{ padding: '24px 20px', display: 'flex', flexDirection: 'column', gap: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <Avatar userId={entry.userId} name={entry.fullName} size={52} />
          <div>
            <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', marginBottom: 3 }}>{entry.fullName}</div>
            <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginBottom: 6 }}>{entry.designationName ?? '—'}</div>
            <StatusChip active={entry.active} />
          </div>
        </div>
        {onAppreciate && <AppreciateButton label="Appreciate" onClick={onAppreciate} />}
        <div style={{ borderTop: '1px solid var(--line)', paddingTop: 16, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {rows.map(([label, value]) => (
            <div key={label}>
              <div className="nf-pd-drawer-label">{label}</div>
              <div style={{ fontSize: 13, color: value ? 'var(--txt)' : 'var(--txt-dim)' }}>{value || '—'}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

/* ── Sort indicator ───────────────────────────────── */
type SortKey = 'fullName' | 'email' | 'departmentName' | 'designationName' | 'locationName' | 'workMode' | 'employmentType';

function SortIcon({ col, sortKey, dir }: { col: SortKey; sortKey: SortKey; dir: 'asc' | 'desc' }) {
  const active = col === sortKey;
  return (
    <span className="nf-pd-sort-icon" aria-hidden="true">
      <ChevronUp size={11} strokeWidth={2.5} className={active && dir === 'asc' ? 'on' : undefined} />
      <ChevronDown size={11} strokeWidth={2.5} className={active && dir === 'desc' ? 'on' : undefined} />
    </span>
  );
}

/* ── Excel export ─────────────────────────────────── */
function exportToExcel(data: DirectoryEntry[]) {
  const rows = data.map(e => ({
    'Full Name':        e.fullName,
    'Employee Code':    e.employeeCode,
    'Work Email':       e.email,
    'Department':       e.departmentName ?? '',
    'Designation':      e.designationName ?? '',
    'Location':         e.locationName ?? '',
    'Work Mode':        e.workMode ?? '',
    'Employment Type':  e.employmentType?.replace('_', ' ') ?? '',
    'Status':           e.active ? 'Active' : 'Inactive',
    'Manager':          e.managerName ?? '',
    'Manager Email':    e.managerEmail ?? '',
  }));
  const ws = XLSX.utils.json_to_sheet(rows);
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, 'Directory');
  XLSX.writeFile(wb, `people-directory-${new Date().toISOString().slice(0, 10)}.xlsx`);
}

/* ── Main page ────────────────────────────────────── */
export default function DirectoryPage() {
  const token = useAuthStore(s => s.token) ?? '';
  const user = useAuthStore(s => s.user);
  // HR/Super Admin can appreciate any active employee org-wide (KudosService#canAppreciate);
  // everyone else keeps My Team's relationship-scoped Appreciate buttons.
  const canAppreciateAnyone = user?.role === 'HR_ADMIN' || user?.role === 'SUPER_ADMIN';
  const [kudosTarget, setKudosTarget] = useState<KudosTarget | null>(null);
  const [all, setAll]         = useState<DirectoryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [search, setSearch]   = useState('');
  const [deptFilter, setDeptFilter]     = useState('');
  const [desigFilter, setDesigFilter]   = useState('');
  const [locFilter, setLocFilter]       = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [sortKey, setSortKey]   = useState<SortKey>('fullName');
  const [sortDir, setSortDir]   = useState<'asc' | 'desc'>('asc');
  const [page, setPage]         = useState(0);
  const [selected, setSelected] = useState<DirectoryEntry | null>(null);
  const [searchParams, setSearchParams] = useSearchParams();

  useEffect(() => {
    directoryApi.list(token)
      .then(data => setAll(data))
      .catch(e => setLoadError(e instanceof Error ? e.message : 'Failed to load directory'))
      .finally(() => setLoading(false));
  }, [token]);

  // Deep-link support: other pages (e.g. Dashboard's Present Today / On Leave modals) link
  // here as /directory?userId=<id> to open a specific employee's detail panel directly.
  useEffect(() => {
    const userId = searchParams.get('userId');
    if (!userId || all.length === 0) return;
    const match = all.find(e => e.userId === userId);
    if (match) setSelected(match);
  }, [searchParams, all]);

  // Deep-link support: the header/global search's "Refine in Directory" links here as
  // /directory?search=<term> to pre-fill this page's own search box (seeded once, on mount —
  // the user's further typing here is never overwritten by a stale query param).
  useEffect(() => {
    const q = searchParams.get('search');
    if (q) setSearch(q);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function closeDetail() {
    setSelected(null);
    if (searchParams.has('userId')) {
      const next = new URLSearchParams(searchParams);
      next.delete('userId');
      setSearchParams(next, { replace: true });
    }
  }

  /* Unique filter options from real data */
  const departments  = useMemo(() => [...new Set(all.map(e => e.departmentName).filter(Boolean))].sort() as string[], [all]);
  const designations = useMemo(() => [...new Set(all.map(e => e.designationName).filter(Boolean))].sort() as string[], [all]);
  const locations    = useMemo(() => [...new Set(all.map(e => e.locationName).filter(Boolean))].sort() as string[], [all]);

  /* Filter + sort */
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    let rows = all.filter(e => {
      if (q) {
        const hay = `${e.fullName} ${e.employeeCode} ${e.designationName ?? ''} ${e.departmentName ?? ''} ${e.locationName ?? ''}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      if (deptFilter  && e.departmentName  !== deptFilter)  return false;
      if (desigFilter && e.designationName !== desigFilter) return false;
      if (locFilter   && e.locationName    !== locFilter)   return false;
      if (statusFilter === 'active'   && !e.active)  return false;
      if (statusFilter === 'inactive' &&  e.active)  return false;
      return true;
    });
    rows = rows.toSorted((a, b) => {
      const av = (a[sortKey] ?? '') as string;
      const bv = (b[sortKey] ?? '') as string;
      return sortDir === 'asc' ? av.localeCompare(bv) : bv.localeCompare(av);
    });
    return rows;
  }, [all, search, deptFilter, desigFilter, locFilter, statusFilter, sortKey, sortDir]);

  /* Pagination */
  const totalPages = Math.ceil(filtered.length / PAGE_SIZE);
  const paged = filtered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);

  function handleSort(key: SortKey) {
    if (sortKey === key) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortKey(key); setSortDir('asc'); }
    setPage(0);
  }

  function clearFilters() {
    setSearch(''); setDeptFilter(''); setDesigFilter('');
    setLocFilter(''); setStatusFilter(''); setPage(0);
  }

  const hasFilters = search || deptFilter || desigFilter || locFilter || statusFilter;

  function sortableTh(col: SortKey, label: string) {
    return (
      <th
        className={`nf-pd-th-sort${sortKey === col ? ' nf-pd-th-sort--active' : ''}`}
        onClick={() => handleSort(col)}
        aria-sort={sortKey === col ? (sortDir === 'asc' ? 'ascending' : 'descending') : undefined}
      >
        <span className="nf-pd-th-inner">{label} <SortIcon col={col} sortKey={sortKey} dir={sortDir} /></span>
      </th>
    );
  }

  const tableHead = (
    <thead>
      <tr>
        {sortableTh('fullName', 'Name')}
        {sortableTh('email', 'Email')}
        {sortableTh('departmentName', 'Department')}
        {sortableTh('designationName', 'Designation')}
        {sortableTh('locationName', 'Location')}
        {sortableTh('workMode', 'Work Mode')}
        <th>Status</th>
        <th>Manager</th>
        <th style={{ width: 66 }}><span className="nf-pd-sr-only">Details</span></th>
      </tr>
    </thead>
  );

  return (
    <div className="nf-pd">
      {/* Header */}
      <div className="nf-pd-header">
        <HeaderArt />
        <div className="nf-pd-header-main">
          <div className="nf-pd-header-icon" aria-hidden="true">
            <Users size={24} strokeWidth={1.8} />
          </div>
          <div style={{ minWidth: 0 }}>
            <h1 className="nf-pd-title">
              People Directory
            </h1>
            <p className="nf-pd-subtitle">
              Manage and view all employees, their details and organizational information.
            </p>
            <div className="nf-pd-count">
              {loading ? 'Loading…' : loadError ? 'Load failed' : `${filtered.length} of ${all.length} people`}
            </div>
          </div>
        </div>
        {!loading && !loadError && all.length > 0 && (
          <button className="nf-pd-export" onClick={() => exportToExcel(filtered)}>
            <Download size={16} />
            Export to Excel
          </button>
        )}
      </div>

      {/* Load error */}
      {loadError && (
        <div role="alert" className="nf-pd-error">
          {loadError} — check that the backend is running.
        </div>
      )}

      {/* Filters + table */}
      <div className="nf-pd-card">
        <div className="nf-pd-filters">
          <div className="nf-pd-search">
            <Search size={17} />
            <input
              className="nf-pd-input"
              value={search}
              onChange={e => { setSearch(e.target.value); setPage(0); }}
              placeholder="Search name, code, designation…"
              aria-label="Search people"
            />
          </div>

          <select className={`nf-pd-select${deptFilter ? ' nf-pd-select--active' : ''}`} aria-label="Department" value={deptFilter} onChange={e => { setDeptFilter(e.target.value); setPage(0); }}>
            <option value="">All Departments</option>
            {departments.map(d => <option key={d} value={d}>{d}</option>)}
          </select>

          <select className={`nf-pd-select${desigFilter ? ' nf-pd-select--active' : ''}`} aria-label="Designation" value={desigFilter} onChange={e => { setDesigFilter(e.target.value); setPage(0); }}>
            <option value="">All Designations</option>
            {designations.map(d => <option key={d} value={d}>{d}</option>)}
          </select>

          <select className={`nf-pd-select${locFilter ? ' nf-pd-select--active' : ''}`} aria-label="Location" value={locFilter} onChange={e => { setLocFilter(e.target.value); setPage(0); }}>
            <option value="">All Locations</option>
            {locations.map(l => <option key={l} value={l}>{l}</option>)}
          </select>

          <select className={`nf-pd-select${statusFilter ? ' nf-pd-select--active' : ''}`} aria-label="Status" value={statusFilter} onChange={e => { setStatusFilter(e.target.value); setPage(0); }}>
            <option value="">All Status</option>
            <option value="active">Active</option>
            <option value="inactive">Inactive</option>
          </select>

          {hasFilters && (
            <button className="nf-pd-clear" onClick={clearFilters}>Clear</button>
          )}
        </div>

        {!loadError && (
          loading ? (
            <div className="nf-pd-table-wrap" role="status" aria-label="Loading">
              <table className="nf-pd-table">
                {tableHead}
                <tbody>
                  {Array.from({ length: 6 }, (_, i) => (
                    <tr key={i} className="nf-pd-skel-row">
                      <td>
                        <div className="nf-pd-person">
                          <span className="nf-pd-skel" style={{ width: 38, height: 38, borderRadius: '50%', flexShrink: 0 }} />
                          <div style={{ flex: 1 }}>
                            <span className="nf-pd-skel" style={{ width: '70%' }} />
                            <span className="nf-pd-skel" style={{ width: '45%', height: 8, marginTop: 7 }} />
                          </div>
                        </div>
                      </td>
                      {[70, 55, 60, 55, 45, 40, 55].map((w, j) => (
                        <td key={j}><span className="nf-pd-skel" style={{ width: `${w}%` }} /></td>
                      ))}
                      <td />
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : paged.length === 0 ? (
            <div className="nf-pd-empty">
              <div className="nf-pd-empty-icon"><Users size={24} /></div>
              <div className="nf-pd-empty-text">{hasFilters ? 'No people match your filters.' : 'No employees found.'}</div>
            </div>
          ) : (
            <>
              <div className="nf-pd-table-wrap">
                <table className="nf-pd-table">
                  {tableHead}
                  <tbody>
                    {paged.map(e => (
                      <tr
                        key={e.userId}
                        onClick={() => setSelected(e)}
                        style={inactiveDimStyle(e.active)}
                      >
                        <td>
                          <div className="nf-pd-person">
                            <Avatar userId={e.userId} name={e.fullName} />
                            <div>
                              <div className="nf-pd-name">{e.fullName}</div>
                              <div className="nf-pd-code">{e.employeeCode}</div>
                            </div>
                          </div>
                        </td>
                        <td className={e.email ? 'nf-pd-email' : 'nf-pd-dim'}>{e.email ? <EmailText email={e.email} /> : '—'}</td>
                        <td className={e.departmentName ? undefined : 'nf-pd-dim'}>{e.departmentName ?? '—'}</td>
                        <td className={e.designationName ? undefined : 'nf-pd-dim'}>{e.designationName ?? '—'}</td>
                        <td className={e.locationName ? undefined : 'nf-pd-dim'}>{e.locationName ?? '—'}</td>
                        <td className={e.workMode ? 'nf-pd-mode' : 'nf-pd-dim'}>{e.workMode?.replace('_', ' ') ?? '—'}</td>
                        <td><StatusChip active={e.active} /></td>
                        <td className={e.managerName ? undefined : 'nf-pd-dim'}>{e.managerName ?? '—'}</td>
                        <td>
                          {/* Second trigger for the same read-only details drawer the row click opens. */}
                          <button
                            className="nf-pd-more"
                            aria-label={`View details for ${e.fullName}`}
                            title="View details"
                            onClick={ev => { ev.stopPropagation(); setSelected(e); }}
                          >
                            <MoreHorizontal size={16} />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* Footer + pagination */}
              <div className="nf-pd-footer">
                <span className="nf-pd-showing">
                  Showing {page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, filtered.length)} of {filtered.length} people
                </span>
                {totalPages > 1 && (
                  <div className="nf-pd-pages nf-tab-scroll">
                    <button
                      className="nf-pd-page"
                      aria-label="Previous page"
                      disabled={page === 0}
                      onClick={() => setPage(p => p - 1)}
                    >
                      <ChevronLeft size={15} />
                    </button>
                    {Array.from({ length: Math.min(totalPages, 7) }, (_, i) => {
                      const p = totalPages <= 7 ? i : page <= 3 ? i : page >= totalPages - 4 ? totalPages - 7 + i : page - 3 + i;
                      return (
                        <button
                          key={p}
                          className={`nf-pd-page${page === p ? ' nf-pd-page--current' : ''}`}
                          aria-current={page === p ? 'page' : undefined}
                          onClick={() => setPage(p)}
                        >
                          {p + 1}
                        </button>
                      );
                    })}
                    <button
                      className="nf-pd-page"
                      aria-label="Next page"
                      disabled={page === totalPages - 1}
                      onClick={() => setPage(p => p + 1)}
                    >
                      <ChevronRightIcon size={15} />
                    </button>
                  </div>
                )}
              </div>
            </>
          )
        )}
      </div>

      {/* Detail drawer */}
      {selected && (
        <>
          <div onClick={closeDetail} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.35)', zIndex: 199 }} />
          <DetailPanel
            entry={selected}
            onClose={closeDetail}
            onAppreciate={canAppreciateAnyone && selected.active && selected.email !== user?.email
              ? () => setKudosTarget({ userId: selected.userId, name: selected.fullName })
              : undefined}
          />
        </>
      )}
      <KudosModal target={kudosTarget} token={token} onClose={() => setKudosTarget(null)} />
    </div>
  );
}

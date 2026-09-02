import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { X, Search, Users, Download, ChevronUp, ChevronDown, ChevronLeft, ChevronRight as ChevronRightIcon } from 'lucide-react';
import * as XLSX from 'xlsx';
import { directoryApi, type DirectoryEntry } from '../api/directory';
import { useAuthStore } from '../store/authStore';
import { inactiveDimStyle } from '../components/EmployeeStatus';
import { EmployeeAvatar } from '../components/EmployeeAvatar';

const PAGE_SIZE = 25;

/* ── Helpers ──────────────────────────────────────── */
function Avatar({ userId, name, size = 34 }: { userId: string; name: string; size?: number }) {
  return (
    <EmployeeAvatar
      userId={userId}
      name={name}
      size={size}
      fontSize={size * 0.33}
      background="rgba(177,17,22,.18)"
      color="#e4373d"
      style={{ fontFamily: 'Inter, sans-serif' }}
    />
  );
}

function StatusChip({ active }: { active: boolean }) {
  return (
    <span style={{
      fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20,
      background: active ? 'rgba(47,182,124,.15)' : 'rgba(107,114,128,.15)',
      color: active ? 'var(--ok)' : 'var(--txt-dim)',
    }}>
      {active ? 'Active' : 'Inactive'}
    </span>
  );
}

/* ── Detail drawer ────────────────────────────────── */
function DetailPanel({ entry, onClose }: { entry: DirectoryEntry; onClose: () => void }) {
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
    <div className="nf-drawer-responsive" style={{
      position: 'fixed', top: 0, right: 0, bottom: 0, width: 360,
      background: 'var(--panel)', borderLeft: '1px solid var(--line)',
      boxShadow: '-8px 0 32px rgba(0,0,0,.35)', zIndex: 200,
      display: 'flex', flexDirection: 'column', overflowY: 'auto',
    }}>
      <div style={{ padding: '18px 20px', borderBottom: '1px solid var(--line)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
          Employee Details
        </span>
        <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', padding: 4, borderRadius: 6, display: 'grid', placeItems: 'center' }}>
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
        <div style={{ borderTop: '1px solid var(--line)', paddingTop: 16, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {rows.map(([label, value]) => (
            <div key={label}>
              <div style={{ fontSize: 10.5, fontWeight: 600, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>{label}</div>
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
  if (col !== sortKey) return <ChevronUp size={11} style={{ opacity: 0.3 }} />;
  return dir === 'asc' ? <ChevronUp size={11} style={{ color: 'var(--brand)' }} /> : <ChevronDown size={11} style={{ color: 'var(--brand)' }} />;
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

  const SELECT: React.CSSProperties = {
    background: 'var(--raised)', border: '1px solid var(--line2)',
    borderRadius: 6, padding: '7px 10px', fontSize: 12.5,
    color: 'var(--txt)', outline: 'none', cursor: 'pointer',
  };

  function thStyle(col: SortKey): React.CSSProperties {
    return {
      padding: '10px 14px', fontSize: 10.5, fontWeight: 700,
      color: sortKey === col ? 'var(--brand)' : 'var(--txt-dim)',
      textTransform: 'uppercase', letterSpacing: '.07em',
      textAlign: 'left', background: 'var(--raised)',
      borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap',
      cursor: 'pointer', userSelect: 'none',
    };
  }

  const TD: React.CSSProperties = {
    padding: '11px 14px', fontSize: 12.5, color: 'var(--txt)',
    borderBottom: '1px solid var(--line)', verticalAlign: 'middle',
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10 }}>
        <div>
          <h1 style={{ margin: 0, marginBottom: 4, fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
            People Directory
          </h1>
          <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>
            {loading ? 'Loading…' : loadError ? 'Load failed' : `${filtered.length} of ${all.length} people`}
          </p>
        </div>
        {!loading && !loadError && all.length > 0 && (
          <button
            onClick={() => exportToExcel(filtered)}
            style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '8px 16px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', cursor: 'pointer' }}
          >
            <Download size={13} />
            Export to Excel
          </button>
        )}
      </div>

      {/* Filter bar */}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <div style={{ position: 'relative', flex: '1 1 200px', minWidth: 160 }}>
          <Search size={13} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
          <input
            value={search}
            onChange={e => { setSearch(e.target.value); setPage(0); }}
            placeholder="Search name, code, designation…"
            style={{ ...SELECT, paddingLeft: 30, width: '100%', boxSizing: 'border-box' }}
          />
        </div>

        <select value={deptFilter} onChange={e => { setDeptFilter(e.target.value); setPage(0); }} style={SELECT}>
          <option value="">All Departments</option>
          {departments.map(d => <option key={d} value={d}>{d}</option>)}
        </select>

        <select value={desigFilter} onChange={e => { setDesigFilter(e.target.value); setPage(0); }} style={SELECT}>
          <option value="">All Designations</option>
          {designations.map(d => <option key={d} value={d}>{d}</option>)}
        </select>

        <select value={locFilter} onChange={e => { setLocFilter(e.target.value); setPage(0); }} style={SELECT}>
          <option value="">All Locations</option>
          {locations.map(l => <option key={l} value={l}>{l}</option>)}
        </select>

        <select value={statusFilter} onChange={e => { setStatusFilter(e.target.value); setPage(0); }} style={SELECT}>
          <option value="">All Status</option>
          <option value="active">Active</option>
          <option value="inactive">Inactive</option>
        </select>

        {hasFilters && (
          <button onClick={clearFilters} style={{ ...SELECT, color: 'var(--txt-mut)' }}>Clear</button>
        )}
      </div>

      {/* Load error */}
      {loadError && (
        <div role="alert" style={{ background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 8, padding: '12px 16px', color: 'var(--risk)', fontSize: 13 }}>
          {loadError} — check that the backend is running.
        </div>
      )}

      {/* Table */}
      {!loadError && (
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
          {loading ? (
            <div style={{ padding: '48px 24px', textAlign: 'center', color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>
          ) : paged.length === 0 ? (
            <div style={{ padding: '56px 24px', textAlign: 'center' }}>
              <Users size={32} style={{ color: 'var(--line2)', margin: '0 auto 12px', display: 'block' }} />
              <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>{hasFilters ? 'No people match your filters.' : 'No employees found.'}</div>
            </div>
          ) : (
            <>
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead>
                    <tr>
                      <th style={thStyle('fullName')} onClick={() => handleSort('fullName')}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>Name <SortIcon col="fullName" sortKey={sortKey} dir={sortDir} /></div>
                      </th>
                      <th style={thStyle('email')} onClick={() => handleSort('email')}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>Email <SortIcon col="email" sortKey={sortKey} dir={sortDir} /></div>
                      </th>
                      <th style={thStyle('departmentName')} onClick={() => handleSort('departmentName')}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>Department <SortIcon col="departmentName" sortKey={sortKey} dir={sortDir} /></div>
                      </th>
                      <th style={thStyle('designationName')} onClick={() => handleSort('designationName')}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>Designation <SortIcon col="designationName" sortKey={sortKey} dir={sortDir} /></div>
                      </th>
                      <th style={thStyle('locationName')} onClick={() => handleSort('locationName')}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>Location <SortIcon col="locationName" sortKey={sortKey} dir={sortDir} /></div>
                      </th>
                      <th style={thStyle('workMode')} onClick={() => handleSort('workMode')}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>Work Mode <SortIcon col="workMode" sortKey={sortKey} dir={sortDir} /></div>
                      </th>
                      <th style={{ ...thStyle('fullName'), cursor: 'default', color: 'var(--txt-dim)' }}>Status</th>
                      <th style={{ ...thStyle('fullName'), cursor: 'default', color: 'var(--txt-dim)' }}>Manager</th>
                    </tr>
                  </thead>
                  <tbody>
                    {paged.map(e => (
                      <tr
                        key={e.userId}
                        onClick={() => setSelected(e)}
                        style={{ cursor: 'pointer', ...inactiveDimStyle(e.active) }}
                        onMouseEnter={ev => { ev.currentTarget.style.background = 'var(--raised)'; }}
                        onMouseLeave={ev => { ev.currentTarget.style.background = 'transparent'; }}
                      >
                        <td style={TD}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                            <Avatar userId={e.userId} name={e.fullName} />
                            <div>
                              <div style={{ fontWeight: 600, color: 'var(--txt)' }}>{e.fullName}</div>
                              <div style={{ fontSize: 10, color: 'var(--txt-dim)', fontFamily: 'Inter, sans-serif', marginTop: 1 }}>{e.employeeCode}</div>
                            </div>
                          </div>
                        </td>
                        <td style={{ ...TD, color: 'var(--txt-mut)' }}>{e.email ?? '—'}</td>
                        <td style={{ ...TD, color: 'var(--txt-mut)' }}>{e.departmentName ?? '—'}</td>
                        <td style={{ ...TD, color: 'var(--txt-mut)' }}>{e.designationName ?? '—'}</td>
                        <td style={{ ...TD, color: 'var(--txt-mut)' }}>{e.locationName ?? '—'}</td>
                        <td style={{ ...TD, color: 'var(--txt-mut)' }}>{e.workMode?.replace('_', ' ') ?? '—'}</td>
                        <td style={TD}><StatusChip active={e.active} /></td>
                        <td style={{ ...TD, color: 'var(--txt-mut)' }}>{e.managerName ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* Pagination */}
              {totalPages > 1 && (
                <div style={{ padding: '12px 14px', borderTop: '1px solid var(--line)', display: 'flex', alignItems: 'center', gap: 10, justifyContent: 'space-between', flexWrap: 'wrap' }}>
                  <span style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
                    Showing {page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, filtered.length)} of {filtered.length}
                  </span>
                  <div className="nf-tab-scroll" style={{ display: 'flex', gap: 4, maxWidth: '100%' }}>
                    <button
                      disabled={page === 0}
                      onClick={() => setPage(p => p - 1)}
                      style={{ padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, cursor: page === 0 ? 'not-allowed' : 'pointer', opacity: page === 0 ? .4 : 1, color: 'var(--txt)', display: 'flex', alignItems: 'center' }}
                    >
                      <ChevronLeft size={13} />
                    </button>
                    {Array.from({ length: Math.min(totalPages, 7) }, (_, i) => {
                      const p = totalPages <= 7 ? i : page <= 3 ? i : page >= totalPages - 4 ? totalPages - 7 + i : page - 3 + i;
                      return (
                        <button
                          key={p}
                          onClick={() => setPage(p)}
                          style={{ padding: '5px 10px', minWidth: 32, background: page === p ? 'var(--brand)' : 'var(--raised)', border: `1px solid ${page === p ? 'var(--brand)' : 'var(--line2)'}`, borderRadius: 5, cursor: 'pointer', color: page === p ? '#fff' : 'var(--txt)', fontSize: 12, fontWeight: page === p ? 700 : 400 }}
                        >
                          {p + 1}
                        </button>
                      );
                    })}
                    <button
                      disabled={page === totalPages - 1}
                      onClick={() => setPage(p => p + 1)}
                      style={{ padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, cursor: page === totalPages - 1 ? 'not-allowed' : 'pointer', opacity: page === totalPages - 1 ? .4 : 1, color: 'var(--txt)', display: 'flex', alignItems: 'center' }}
                    >
                      <ChevronRightIcon size={13} />
                    </button>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* Detail drawer */}
      {selected && (
        <>
          <div onClick={closeDetail} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.35)', zIndex: 199 }} />
          <DetailPanel entry={selected} onClose={closeDetail} />
        </>
      )}
    </div>
  );
}

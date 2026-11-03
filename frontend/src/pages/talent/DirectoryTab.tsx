import { useEffect, useMemo, useState } from 'react';
import { usePaged } from '../../components/usePaged';
import { TablePagination } from '../../components/TablePagination';
import { Search, Download, Award } from 'lucide-react';
import { PersonAvatar } from '../../components/PersonCell';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { talentDirectoryApi, type TalentEmployeeSummary } from '../../api/talentOrg';
import { DetailModalShell } from '../../components/TeamGrowthModal';
import { LEVEL_TONE, Pill, EmployeeDetailView } from './EmployeeDetailView';
import { useEmployeeDetail } from './useEmployeeDetail';

export function DirectoryTab() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [rows, setRows] = useState<TalentEmployeeSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [departmentFilter, setDepartmentFilter] = useState('');
  const [exporting, setExporting] = useState(false);
  const { detail, detailLoading, openDetail, closeDetail } = useEmployeeDetail(token);

  useEffect(() => {
    talentDirectoryApi.listAll(token)
      .then(setRows)
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Could not load the directory'))
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const departments = useMemo(
    () => Array.from(new Set(rows.map(r => r.departmentName).filter((d): d is string => !!d))).sort(),
    [rows]
  );

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return rows.filter(r => {
      if (departmentFilter && r.departmentName !== departmentFilter) return false;
      if (!q) return true;
      return r.employeeName.toLowerCase().includes(q) || r.employeeCode.toLowerCase().includes(q);
    });
  }, [rows, search, departmentFilter]);

  const dirPaged = usePaged(filtered, [search, departmentFilter]);

  if (loading) return <div style={{ color: 'var(--txt-mut)', fontSize: 13, padding: '20px 0' }}>Loading…</div>;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          <label className="nf-filter-search">
            <Search size={15} />
            <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search name or code…" />
          </label>
          <select className="nf-filter-select" value={departmentFilter} onChange={e => setDepartmentFilter(e.target.value)}>
            <option value="">All Departments</option>
            {departments.map(d => <option key={d} value={d}>{d}</option>)}
          </select>
        </div>
        <button onClick={async () => {
          setExporting(true);
          try { await talentDirectoryApi.export(token); }
          catch (e) { showToast('error', e instanceof Error ? e.message : 'Export failed'); }
          finally { setExporting(false); }
        }} disabled={exporting} className="nf-act-btn nf-act-btn--lg">
          <Download size={14} /> {exporting ? 'Exporting…' : 'Export CSV'}
        </button>
      </div>

      <div><span className="nf-audit-count"><b>{filtered.length}</b> of {rows.length} employees</span></div>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 16, boxShadow: '0 6px 22px rgba(16, 24, 40, .07)', overflow: 'hidden' }}>
        {filtered.length === 0 ? (
          <div style={{ padding: 20, color: 'var(--txt-dim)', fontSize: 13 }}>No employees match.</div>
        ) : (
          dirPaged.pageRows.map((r, i) => (
            <button key={r.employeeUserId} onClick={() => openDetail(r.employeeUserId)} className="nf-dir-row" style={{
              width: '100%', textAlign: 'left', border: 'none', font: 'inherit',
              borderTop: i === 0 ? 'none' : '1px solid var(--line)',
            }}>
              <PersonAvatar userId={r.employeeUserId} name={r.employeeName} size={40} />
              <div style={{ flex: '0 0 220px', minWidth: 0 }}>
                <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.employeeName}</div>
                <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{r.employeeCode} · {r.departmentName ?? '–'}</div>
              </div>
              <div style={{ flex: 1, display: 'flex', flexWrap: 'wrap', gap: 5, minWidth: 0 }}>
                {r.skills.slice(0, 4).map(s => (
                  <Pill key={s.id} tone={LEVEL_TONE[s.proficiencyLevel] ?? 'var(--txt-dim)'}>{s.skillName}</Pill>
                ))}
                {r.skills.length > 4 && <span style={{ fontSize: 11, color: 'var(--txt-dim)' }}>+{r.skills.length - 4} more</span>}
                {r.skills.length === 0 && <span style={{ fontSize: 11.5, color: 'var(--txt-dim)', fontStyle: 'italic' }}>No skills logged</span>}
              </div>
              <div style={{ flex: '0 0 100px', fontSize: 11.5, color: 'var(--txt-mut)', textAlign: 'right' }}>
                {r.certificates.length > 0 && <div className="nf-cert-badge"><Award size={12} /> {r.certificates.length}</div>}
              </div>
            </button>
          ))
        )}
        <TablePagination {...dirPaged.pagerProps} noun="employees" />
      </div>

      {(detail || detailLoading) && (
        <DetailModalShell title={detail?.employeeName ?? 'Loading…'} onClose={closeDetail}>
          {detailLoading || !detail ? <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div> : <EmployeeDetailView detail={detail} />}
        </DetailModalShell>
      )}
    </div>
  );
}

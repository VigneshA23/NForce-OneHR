import { useEffect, useMemo, useState } from 'react';
import * as XLSX from 'xlsx';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { auditApi, type ActionGroup, type AuditLogFilters, type AuditLogStats, type PagedAuditLogs } from '../../api/audit';
import { AuditStatCards } from './AuditStatCards';
import { AuditFilterBar } from './AuditFilterBar';
import { AuditCategoryChips } from './AuditCategoryChips';
import { AuditLogTable } from './AuditLogTable';

const PAGE_SIZE = 20;

export interface AuditLogViewConfig {
  title: string;
  subtitle: string;
  /** Super Admin only — controls chip/card visibility only. The server, not this flag, is the
   *  actual security boundary: HR Admin never receives ACCESS_CONTROL rows regardless. */
  showAccessCategory: boolean;
  exportFilenamePrefix: string;
}

export function AuditLogView({ config }: { config: AuditLogViewConfig }) {
  const token = useAuthStore(s => s.token) ?? '';
  const { showToast } = useToast();

  const [targetSearch, setTargetSearch] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [activeGroup, setActiveGroup] = useState<ActionGroup | 'ALL'>('ALL');
  const [page, setPage] = useState(0);
  const [pageData, setPageData] = useState<PagedAuditLogs | null>(null);
  const [stats, setStats] = useState<AuditLogStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [exporting, setExporting] = useState(false);

  const filters: AuditLogFilters = useMemo(() => ({
    targetSearch: targetSearch.trim() || undefined,
    group: activeGroup === 'ALL' ? undefined : activeGroup,
    from: from || undefined,
    to: to || undefined,
  }), [targetSearch, activeGroup, from, to]);

  useEffect(() => {
    setLoading(true);
    auditApi.list(filters, page, PAGE_SIZE, token)
      .then(data => { setPageData(data); setLoadError(''); })
      .catch(err => setLoadError(err instanceof Error ? err.message : 'Failed to load audit log'))
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters, page, token]);

  // Stats reflect the whole filtered corpus, independent of pagination — kept as a separate
  // effect so paging doesn't refetch counts, and a stats failure never blocks the table.
  useEffect(() => {
    auditApi.stats(filters, token).then(setStats).catch(() => { /* non-critical */ });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters, token]);

  function withPageReset<T>(setter: (v: T) => void) {
    return (v: T) => { setter(v); setPage(0); };
  }

  function clearFilters() {
    setTargetSearch(''); setFrom(''); setTo(''); setActiveGroup('ALL'); setPage(0);
  }

  async function handleExport() {
    setExporting(true);
    try {
      const rows = await auditApi.exportAll(filters, token);
      const sheetRows = rows.map(r => ({
        'Timestamp': r.occurredAt.replace('T', ' ').slice(0, 19),
        'Performed By Name': r.actorName ?? '',
        'Performed By Email': r.actorEmail ?? '',
        'Action': r.action,
        'Category': r.actionCategory,
        'Affected User': r.targetLabel,
        'Affected User ID': r.targetEmployeeCode ?? '',
      }));
      const ws = XLSX.utils.json_to_sheet(sheetRows);
      const wb = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(wb, ws, 'Audit Log');
      XLSX.writeFile(wb, `${config.exportFilenamePrefix}-${new Date().toISOString().slice(0, 10)}.xlsx`);
      showToast('success', `Exported ${rows.length} record(s) to Excel (matches current filters).`);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to export audit log');
    } finally {
      setExporting(false);
    }
  }

  const rows = pageData?.content ?? [];
  const totalElements = pageData?.totalElements ?? 0;
  const totalPages = pageData?.totalPages ?? 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div>
        <h1 style={{ margin: 0, marginBottom: 4, fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>
          {config.title}
        </h1>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>{config.subtitle}</p>
      </div>

      <AuditStatCards stats={stats} showAccess={config.showAccessCategory} />

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: 16 }}>
        <AuditFilterBar
          targetSearch={targetSearch} onTargetSearchChange={withPageReset(setTargetSearch)}
          from={from} onFromChange={withPageReset(setFrom)}
          to={to} onToChange={withPageReset(setTo)}
          onClear={clearFilters}
          onExport={handleExport}
          exporting={exporting}
        />
      </div>

      <AuditCategoryChips
        active={activeGroup}
        onChange={withPageReset(setActiveGroup)}
        showAccess={config.showAccessCategory}
      />

      <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>
        {loading ? 'Loading…' : `${totalElements} record(s) found`}
        {config.showAccessCategory && !loading && (
          <span style={{ color: 'var(--risk)', marginLeft: 6 }}>• Includes access-control events</span>
        )}
      </div>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : loadError ? (
          <div role="alert" style={{ margin: 16, background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 8, padding: '12px 16px', color: 'var(--risk)', fontSize: 13 }}>
            {loadError} — check that the backend is running.
          </div>
        ) : rows.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>No audit events match these filters</div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>Try widening the date range or clearing search filters.</div>
          </div>
        ) : (
          <>
            <AuditLogTable rows={rows} />
            {totalPages > 1 && (
              <div style={{ padding: '12px 14px', borderTop: '1px solid var(--line)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: 12, color: 'var(--txt-mut)' }}>Page {page + 1} of {totalPages}</span>
                <div style={{ display: 'flex', gap: 4 }}>
                  <button
                    disabled={page === 0}
                    onClick={() => setPage(p => p - 1)}
                    style={{ padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, cursor: page === 0 ? 'not-allowed' : 'pointer', opacity: page === 0 ? .4 : 1, color: 'var(--txt)', display: 'flex', alignItems: 'center' }}
                  >
                    <ChevronLeft size={13} />
                  </button>
                  <button
                    disabled={page >= totalPages - 1}
                    onClick={() => setPage(p => p + 1)}
                    style={{ padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, cursor: page >= totalPages - 1 ? 'not-allowed' : 'pointer', opacity: page >= totalPages - 1 ? .4 : 1, color: 'var(--txt)', display: 'flex', alignItems: 'center' }}
                  >
                    <ChevronRight size={13} />
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

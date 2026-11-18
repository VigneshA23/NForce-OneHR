import { useEffect, useMemo, useState } from 'react';
import * as XLSX from 'xlsx';
import { CalendarDays, ChevronLeft, ChevronRight, ScrollText, Shield } from 'lucide-react';
import PageTitleBlock from '../PageTitleBlock';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { auditApi, type AccessChangeFilters, type PagedAuditLogs } from '../../api/audit';
import { ACCESS_ACTION_OPTIONS, actionLabel, changeReason, daysAgo, summarizeChange } from '../../lib/accessChanges';
import { AuditFilterBar, auditInputStyle, auditLabelStyle } from './AuditFilterBar';
import { AuditLogTable } from './AuditLogTable';

const PAGE_SIZE = 20;

const PRESETS = [
  { key: 'last30', label: 'Last 30 days', days: 30 },
  { key: 'last7', label: 'Last 7 days', days: 7 },
] as const;

function pad2(n: number): string { return String(n).padStart(2, '0'); }

// "yyyy-MM-dd HH:mm:ss" in the viewer's local time zone, sortable in Excel (same shape as AuditLogView's export).
function formatTimestampForExport(iso: string): string {
  const d = new Date(iso);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}

/**
 * Super Admin's org-wide "who changed access" view: role, permission and user-account changes by anyone, with who,
 * when, what and why. Built from the same pieces as the rest of Audit & Security (title card, filter bar, category
 * pills, table, detail dialog). Defaults to the last 30 days; the server (audit_security:VIEW:ALL) is the access
 * boundary, not this page.
 */
export function AccessChangesView() {
  const token = useAuthStore(s => s.token) ?? '';
  const { showToast } = useToast();
  const [from, setFrom] = useState(() => daysAgo(30));
  const [to, setTo] = useState('');
  const [action, setAction] = useState('');
  const [actorSearch, setActorSearch] = useState('');
  const [targetSearch, setTargetSearch] = useState('');
  const [debounced, setDebounced] = useState({ actor: '', target: '' });
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PagedAuditLogs | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [exporting, setExporting] = useState(false);

  // Typing in the two search boxes waits for a short pause before querying.
  useEffect(() => {
    const t = setTimeout(() => { setDebounced({ actor: actorSearch.trim(), target: targetSearch.trim() }); setPage(0); }, 300);
    return () => clearTimeout(t);
  }, [actorSearch, targetSearch]);

  const filters: AccessChangeFilters = useMemo(() => ({
    actorSearch: debounced.actor || undefined,
    targetSearch: debounced.target || undefined,
    action: action || undefined,
    from: from || undefined,
    to: to || undefined,
  }), [debounced, action, from, to]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    auditApi.accessChanges(filters, page, PAGE_SIZE, token)
      .then(d => { if (!cancelled) { setData(d); setLoadError(''); } })
      .catch(e => { if (!cancelled) setLoadError(e instanceof Error ? e.message : 'Failed to load access changes'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    // Only the latest request for this filter/page combination may write state.
    return () => { cancelled = true; };
  }, [filters, page, token]);

  const activePreset = !to ? PRESETS.find(p => p.days && from === daysAgo(p.days))?.key : undefined;
  const isDefault = from === daysAgo(30) && !to && !action && !actorSearch && !targetSearch;

  function clearFilters() {
    setActorSearch(''); setTargetSearch(''); setAction(''); setFrom(daysAgo(30)); setTo(''); setPage(0);
  }

  async function handleExport() {
    setExporting(true);
    try {
      const rows = await auditApi.exportAccessChanges(filters, token);
      const sheetRows = rows.map(r => ({
        'Timestamp': formatTimestampForExport(r.occurredAt),
        'Performed By Name': r.actorName ?? '',
        'Performed By Email': r.actorEmail ?? '',
        'Action': actionLabel(r.action),
        'Action Code': r.action,
        'Affected': r.targetLabel,
        'Affected User ID': r.targetEmployeeCode ?? '',
        'What Changed': summarizeChange(r.action, r.beforeState, r.afterState),
        'Reason': changeReason(r.afterState),
        // Raw before/after snapshots (authorities and roles). The trail holds field names, never sensitive values.
        'Before': r.beforeState ?? '',
        'After': r.afterState ?? '',
      }));
      const ws = XLSX.utils.json_to_sheet(sheetRows);
      const wb = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(wb, ws, 'Access Changes');
      XLSX.writeFile(wb, `access-changes-${new Date().toISOString().slice(0, 10)}.xlsx`);
      showToast('success', `Exported ${rows.length} record(s) to Excel (matches current filters).`);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to export access changes');
    } finally {
      setExporting(false);
    }
  }

  const rows = data?.content ?? [];
  const totalElements = data?.totalElements ?? 0;
  const totalPages = data?.totalPages ?? 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <PageTitleBlock card style={{ marginBottom: 0 }} icon={Shield}>
        <h1 style={{ margin: 0, marginBottom: 4, fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
          Access changes
        </h1>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>
          Who changed roles, permissions and user accounts, when, and why. Showing the last 30 days by default.
        </p>
      </PageTitleBlock>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: 16 }}>
        <AuditFilterBar
          actorSearch={actorSearch} onActorSearchChange={setActorSearch}
          targetSearch={targetSearch} onTargetSearchChange={setTargetSearch}
          from={from} onFromChange={v => { setFrom(v); setPage(0); }}
          to={to} onToChange={v => { setTo(v); setPage(0); }}
          hasFilters={!isDefault}
          onClear={clearFilters}
          onExport={handleExport}
          exporting={exporting}
          extra={(
            <div>
              <label htmlFor="access-action" style={auditLabelStyle}>Action</label>
              <select id="access-action" style={{ ...auditInputStyle, minWidth: 220 }} value={action}
                onChange={e => { setAction(e.target.value); setPage(0); }}>
                <option value="">All access changes</option>
                {ACCESS_ACTION_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            </div>
          )}
        />
      </div>

      <div role="tablist" aria-label="Date range" className="nf-audit-pills">
        {PRESETS.map(p => (
          <button key={p.key} type="button" role="tab" aria-selected={activePreset === p.key}
            className={`nf-audit-pill${activePreset === p.key ? ' is-active' : ''}`}
            onClick={() => { setFrom(daysAgo(p.days)); setTo(''); setPage(0); }}>
            <CalendarDays size={15} />
            <span>{p.label}</span>
          </button>
        ))}
      </div>

      <div className="nf-audit-meta">
        <span className="nf-audit-count">
          <ScrollText size={14} />
          {loading ? 'Loading…' : <><b>{totalElements}</b> record(s) found</>}
        </span>
      </div>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : loadError ? (
          <div role="alert" style={{ margin: 16, background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 8, padding: '12px 16px', color: 'var(--risk)', fontSize: 13 }}>
            {loadError}, check that the backend is running.
          </div>
        ) : rows.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>No access changes match these filters</div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>Try widening the date range or clearing the search filters.</div>
          </div>
        ) : (
          <>
            <AuditLogTable
              rows={rows}
              extraColumn={{
                header: 'What changed',
                render: r => {
                  const what = summarizeChange(r.action, r.beforeState, r.afterState);
                  const reason = changeReason(r.afterState);
                  return (
                    <div style={{ fontSize: 12.5, color: 'var(--txt)' }}>
                      <div>{what || '-'}</div>
                      {reason && <div style={{ marginTop: 2, fontSize: 11.5, color: 'var(--txt-dim)' }}>Reason: {reason}</div>}
                    </div>
                  );
                },
              }}
            />
            {totalPages > 1 && (
              <div style={{ padding: '12px 14px', borderTop: '1px solid var(--line)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: 12, color: 'var(--txt-mut)' }}>Page {page + 1} of {totalPages}</span>
                <div style={{ display: 'flex', gap: 4 }}>
                  <button disabled={page === 0} onClick={() => setPage(p => p - 1)} aria-label="Previous page"
                    style={{ padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, cursor: page === 0 ? 'not-allowed' : 'pointer', opacity: page === 0 ? .4 : 1, color: 'var(--txt)', display: 'flex', alignItems: 'center' }}>
                    <ChevronLeft size={13} />
                  </button>
                  <button disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)} aria-label="Next page"
                    style={{ padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, cursor: page >= totalPages - 1 ? 'not-allowed' : 'pointer', opacity: page >= totalPages - 1 ? .4 : 1, color: 'var(--txt)', display: 'flex', alignItems: 'center' }}>
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

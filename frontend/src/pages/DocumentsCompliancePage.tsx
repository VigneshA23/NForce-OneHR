import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { EmpCode } from '../components/EmpCode';
import { EmployeeAvatar } from '../components/EmployeeAvatar';
import PageTitleBlock from '../components/PageTitleBlock';
import { useSearchParams } from 'react-router-dom';
import { CheckCircle, Clock, XCircle, Eye, Search, Users, History, ShieldCheck, FileText, CalendarClock, Hourglass, AlertTriangle, X } from 'lucide-react';
import './documentsCompliance.css';
import { KebabMenu } from '../components/KebabMenu';
import { TablePagination, clampPage, paginate } from '../components/TablePagination';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { TabBar } from '../components/TabBar';
import {
  listAllDocuments, listPendingDocuments, verifyDocument, getAdminKpis, fetchDocumentFile, listMissingDocuments, remindMissingDocument,
  documentHistory,
  type EmployeeDocument, type DocumentAdminKpi, type MissingDocument,
} from '../api/documents';

const fmtDate = (iso: string | null | undefined) => (iso ? new Date(iso).toLocaleDateString() : '-');

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, { label: string; tone: string }> = {
    VERIFIED: { label: 'Verified', tone: 'is-ok' },
    PENDING_VERIFICATION: { label: 'Pending Review', tone: 'is-warn' },
    REJECTED: { label: 'Rejected', tone: 'is-bad' },
    WITHDRAWN: { label: 'Withdrawn', tone: 'is-off' },
  };
  const cfg = map[status] ?? { label: status, tone: 'is-off' };
  return (
    <span className={`nf-dc-pill ${cfg.tone}`}>
      {status === 'VERIFIED' && <CheckCircle size={12} />}
      {status === 'PENDING_VERIFICATION' && <Clock size={12} />}
      {status === 'REJECTED' && <XCircle size={12} />}
      {cfg.label}
    </span>
  );
}

function PersonCell({ userId, name }: { userId: string; name: string | null }) {
  return (
    <div className="nf-dc-person">
      <EmployeeAvatar userId={userId} name={name ?? userId.slice(0, 8)} size={34} fontSize={12} background="var(--brand)" />
      <div>
        <div className="nf-dc-name">{name ?? `${userId.slice(0, 8)}...`}</div>
        <EmpCode userId={userId} />
      </div>
    </div>
  );
}

/** Expiry as a pill: red once passed, amber inside 30 days, neutral otherwise. Display only. */
function ExpiryCell({ iso }: { iso: string | null }) {
  if (!iso) return <span className="nf-dc-none">-</span>;
  const days = Math.ceil((new Date(iso).getTime() - Date.now()) / 86400000);
  const tone = days < 0 ? 'is-bad' : days <= 30 ? 'is-warn' : '';
  return <span className={`nf-dc-date ${tone}`}><CalendarClock size={12} />{fmtDate(iso)}</span>;
}

function FileCell({ name }: { name: string }) {
  return (
    <span title={name} className="nf-dc-file">
      <FileText size={13} /> <span>{name}</span>
    </span>
  );
}

// ── History version view/download ─────────────────────────

function HistoryViewButton({ docId }: { docId: string }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [loading, setLoading] = useState(false);

  async function open() {
    setLoading(true);
    try {
      const url = await fetchDocumentFile(token, docId);
      window.open(url, '_blank');
    } catch {
      showToast('error', 'Could not open file');
    } finally {
      setLoading(false);
    }
  }

  return (
    <button type="button" onClick={open} disabled={loading} className="nf-dc-mini">
      <Eye size={12} /> {loading ? '…' : 'View'}
    </button>
  );
}

// ── Document History section — retains prior submissions on re-upload ────

function DocumentHistorySection({ documentId }: { documentId: string }) {
  const token = useAuthStore(s => s.token)!;
  const [versions, setVersions] = useState<EmployeeDocument[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    documentHistory(token, documentId).then(v => { if (!cancelled) setVersions(v); }).catch(() => { if (!cancelled) setVersions([]); });
    return () => { cancelled = true; };
  }, [documentId, token]);

  if (!versions || versions.length <= 1) return null;

  return (
    <div>
      <div className="nf-dc-sec"><History size={13} /> Document History</div>
      <div className="nf-dc-hist">
        {versions.map(v => (
          <div key={v.id} className="nf-dc-ver">
            <div style={{ minWidth: 0 }}>
              <div className="nf-dc-ver-top">
                <b>Version {v.versionNumber}</b>
                {!v.superseded ? <span className="nf-dc-pill is-ok is-sm">Current</span> : <span className="nf-dc-pill is-off is-sm">Superseded</span>}
                <StatusBadge status={v.status} />
              </div>
              <div className="nf-dc-ver-sub">{v.fileName} · Submitted {new Date(v.uploadedAt).toLocaleDateString()}</div>
              {v.rejectionReason && <div className="nf-dc-ver-rej">Reason: {v.rejectionReason}</div>}
            </div>
            <HistoryViewButton docId={v.id} />
          </div>
        ))}
      </div>
    </div>
  );
}

// ── Document Detail Modal (shows file + approve/reject) ───

function DetailModal({
  doc,
  onVerify,
  onReject,
  onClose,
}: {
  doc: EmployeeDocument;
  onVerify(): Promise<void>;
  onReject(reason: string): Promise<void>;
  onClose(): void;
}) {
  const token = useAuthStore(s => s.token)!;
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [fileLoading, setFileLoading] = useState(true);
  const [fileError, setFileError] = useState(false);
  const [mode, setMode] = useState<'view' | 'reject'>('view');
  const [reason, setReason] = useState('');
  const [acting, setActing] = useState(false);

  const ext = doc.fileName.split('.').pop()?.toLowerCase() ?? '';
  const isImage = ['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(ext);
  const isPdf = ext === 'pdf';

  useEffect(() => {
    // Track the URL locally: the cleanup closure would otherwise only ever see the initial
    // null blobUrl state and never revoke the object URL.
    let url: string | null = null;
    let cancelled = false;
    fetchDocumentFile(token, doc.id)
      .then(u => { url = u; if (cancelled) URL.revokeObjectURL(u); else setBlobUrl(u); })
      .catch(() => { if (!cancelled) setFileError(true); })
      .finally(() => { if (!cancelled) setFileLoading(false); });
    return () => { cancelled = true; if (url) URL.revokeObjectURL(url); };
  }, [doc.id, token]);

  async function handleVerify() {
    setActing(true);
    try { await onVerify(); } finally { setActing(false); }
  }

  async function handleReject(e: React.FormEvent) {
    e.preventDefault();
    if (!reason.trim()) return;
    setActing(true);
    try { await onReject(reason); } finally { setActing(false); }
  }

  return createPortal(
    <div className="nf-dc-overlay">
      <div className="nf-dc-dlg">
        {/* Header */}
        <div className="nf-dc-dhead">
          <span className="nf-dc-dico"><FileText size={18} /></span>
          <div className="nf-dc-dtitles">
            <h3>{doc.documentTypeName}</h3>
            <p>{doc.employeeName ?? doc.employeeUserId.slice(0, 8)} · Uploaded {new Date(doc.uploadedAt).toLocaleDateString()} · {doc.fileName}</p>
          </div>
          <button type="button" onClick={onClose} className="nf-dc-x" aria-label="Close"><X size={16} /></button>
        </div>

        <div className="nf-dc-dbody">
          {/* Document preview */}
          <div className="nf-dc-preview">
            {fileLoading && <span className="nf-dc-pmsg">Loading document…</span>}
            {fileError && (
              <div style={{ textAlign: 'center' }}>
                <p className="nf-dc-pmsg">Preview unavailable</p>
                {blobUrl && <a href={blobUrl} target="_blank" rel="noreferrer" className="nf-dc-open"><Eye size={14} /> Open file</a>}
              </div>
            )}
            {!fileLoading && !fileError && blobUrl && (
              isPdf ? (
                <iframe src={blobUrl} style={{ width: '100%', height: 380, border: 'none' }} title={doc.fileName} />
              ) : isImage ? (
                <img src={blobUrl} alt={doc.fileName} style={{ maxWidth: '100%', maxHeight: 380, objectFit: 'contain' }} />
              ) : (
                <div style={{ textAlign: 'center' }}>
                  <p className="nf-dc-pmsg">No inline preview for this file type</p>
                  <a href={blobUrl} target="_blank" rel="noreferrer" className="nf-dc-open"><Eye size={14} /> Open file</a>
                </div>
              )
            )}
          </div>

          {/* Expiry / issue dates */}
          {(doc.issueDate || doc.expiryDate) && (
            <div className="nf-dc-facts">
              {doc.issueDate && <span>Issue date <b>{new Date(doc.issueDate).toLocaleDateString()}</b></span>}
              {doc.expiryDate && <span>Expiry <b>{new Date(doc.expiryDate).toLocaleDateString()}</b></span>}
            </div>
          )}

          {/* Status badge for already-actioned docs */}
          {doc.status !== 'PENDING_VERIFICATION' && (
            <div className="nf-dc-statusrow">
              <StatusBadge status={doc.status} />
              {doc.rejectionReason && <span className="nf-dc-rej">Reason: {doc.rejectionReason}</span>}
            </div>
          )}

          <DocumentHistorySection documentId={doc.id} />
        </div>

        {/* Actions — only for pending docs */}
        <div className="nf-dc-dfoot">
          {doc.status === 'PENDING_VERIFICATION' ? (
            mode === 'view' ? (
              <>
                <button type="button" onClick={onClose} disabled={acting} className="nf-dc-ghost">Close</button>
                <button type="button" onClick={() => setMode('reject')} disabled={acting} className="nf-dc-danger">Reject</button>
                <button type="button" onClick={handleVerify} disabled={acting || fileLoading} className="nf-dc-ok">
                  {acting ? 'Verifying…' : 'Approve'}
                </button>
              </>
            ) : (
              <form onSubmit={handleReject} className="nf-dc-rejform">
                <label>Rejection reason *</label>
                <textarea value={reason} onChange={e => setReason(e.target.value)} rows={3} required />
                <div className="nf-dc-rejbtns">
                  <button type="button" onClick={() => setMode('view')} disabled={acting} className="nf-dc-ghost">Back</button>
                  <button type="submit" disabled={acting || !reason.trim()} className="nf-dc-danger is-solid">
                    {acting ? 'Rejecting…' : 'Confirm Reject'}
                  </button>
                </div>
              </form>
            )
          ) : (
            <button type="button" onClick={onClose} className="nf-dc-ghost">Close</button>
          )}
        </div>
      </div>
    </div>,
    document.body,
  );
}

// ── Not Submitted Tab with Remind ────────────────────────

// `missing` is the current page's rows; the pagination footer is rendered inside the card.
function MissingTab({ missing, searchEmpty, footer }: { missing: MissingDocument[]; searchEmpty: boolean; footer: React.ReactNode }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [reminding, setReminding] = useState<string | null>(null);

  async function doRemind(m: MissingDocument) {
    const key = `${m.employeeUserId}-${m.documentTypeId}`;
    setReminding(key);
    try {
      await remindMissingDocument(token, m.employeeUserId, m.documentTypeId);
      showToast('success', `Reminder sent to ${m.employeeName}`);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Remind failed');
    } finally {
      setReminding(null);
    }
  }

  return (
    <div className="nf-dc-card">
      <div className="nf-doc-table-scroll">
        <table className="nf-dc-table">
          <thead>
            <tr>
              <th>Employee</th>
              <th>Missing Document</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {missing.length === 0 ? (
              <tr><td colSpan={3} className="nf-dc-cellmsg">
                {searchEmpty ? 'No results match your search or filter.' : (
                  <div className="nf-dc-allgood">
                    <Users size={26} />
                    <span>All employees have submitted their required documents.</span>
                  </div>
                )}
              </td></tr>
            ) : missing.map((m, i) => {
              const key = `${m.employeeUserId}-${m.documentTypeId}`;
              return (
                <tr key={`${key}-${i}`}>
                  <td><PersonCell userId={m.employeeUserId} name={m.employeeName} /></td>
                  <td><span className="nf-dc-type is-missing"><AlertTriangle size={12} />{m.documentTypeName}</span></td>
                  <td style={{ width: 150 }}>
                    <KebabMenu items={[{
                      label: reminding === key ? 'Sending…' : 'Send Reminder',
                      onClick: () => doRemind(m),
                    }]} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {footer}
    </div>
  );
}

// ── Main Page ─────────────────────────────────────────────

export default function DocumentsCompliancePage() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [searchParams] = useSearchParams();
  const [tab, setTab] = useState<'pending' | 'verified' | 'missing'>('pending');
  const [search, setSearch] = useState(() => searchParams.get('search') ?? '');
  const [kpis, setKpis] = useState<DocumentAdminKpi | null>(null);
  const [pending, setPending] = useState<EmployeeDocument[]>([]);
  const [verified, setVerified] = useState<EmployeeDocument[]>([]);
  const [missing, setMissing] = useState<MissingDocument[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [detailDoc, setDetailDoc] = useState<EmployeeDocument | null>(null);

  async function load(showSpinner: boolean) {
    if (showSpinner) setLoading(true);
    try {
      const [k, p, all, m] = await Promise.all([
        getAdminKpis(token),
        listPendingDocuments(token),
        listAllDocuments(token),
        listMissingDocuments(token),
      ]);
      setKpis(k);
      setPending(p);
      setVerified(all.filter(d => d.status === 'VERIFIED'));
      setMissing(m);
      setLoadError(null);
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Load failed';
      if (showSpinner) setLoadError(msg); else showToast('error', msg);
    } finally {
      if (showSpinner) setLoading(false);
    }
  }

  useEffect(() => { load(true); }, [token]);

  async function doVerify(doc: EmployeeDocument) {
    try {
      const updated = await verifyDocument(token, doc.id, 'VERIFY');
      setPending(p => p.filter(d => d.id !== doc.id));
      setVerified(v => [updated, ...v]);
      setDetailDoc(null);
      showToast('success', 'Document verified - the employee has been notified');
      getAdminKpis(token).then(setKpis).catch(() => {});
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Verify failed');
      // e.g. another admin already reviewed it, or the employee re-uploaded — resync.
      setDetailDoc(null);
      load(false);
    }
  }

  async function doReject(doc: EmployeeDocument, reason: string) {
    try {
      await verifyDocument(token, doc.id, 'REJECT', reason.trim());
      setPending(p => p.filter(d => d.id !== doc.id));
      setDetailDoc(null);
      showToast('success', 'Document rejected - the employee has been notified');
      getAdminKpis(token).then(setKpis).catch(() => {});
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Reject failed');
      setDetailDoc(null);
      load(false);
    }
  }

  const [docTypeFilter, setDocTypeFilter] = useState('');

  const allDocTypes = [...new Set([
    ...pending.map(d => d.documentTypeName),
    ...verified.map(d => d.documentTypeName),
    ...missing.map(m => m.documentTypeName),
  ])].sort();

  const q = search.trim().toLowerCase();

  const filteredPending = pending.filter(d => {
    if (docTypeFilter && d.documentTypeName !== docTypeFilter) return false;
    return !q || (d.employeeName ?? '').toLowerCase().includes(q) || d.documentTypeName.toLowerCase().includes(q) || d.fileName.toLowerCase().includes(q);
  });

  const filteredVerified = verified.filter(d => {
    if (docTypeFilter && d.documentTypeName !== docTypeFilter) return false;
    return !q || (d.employeeName ?? '').toLowerCase().includes(q) || d.documentTypeName.toLowerCase().includes(q) || d.fileName.toLowerCase().includes(q);
  });

  const filteredMissing = missing.filter(m => {
    if (docTypeFilter && m.documentTypeName !== docTypeFilter) return false;
    return !q || m.employeeName.toLowerCase().includes(q) || m.documentTypeName.toLowerCase().includes(q);
  });

  const filtering = q.length > 0 || docTypeFilter !== '';

  // Client-side pagination (the list endpoints return everything). One page/pageSize is
  // shared across the tabs; page resets to 1 when the tab, search or filter changes, and is
  // clamped at render so a verify/reject that empties the last page falls back a page.
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  useEffect(() => { setPage(1); }, [tab, q, docTypeFilter]);

  const activeRows = tab === 'pending' ? filteredPending : tab === 'verified' ? filteredVerified : filteredMissing;
  const safePage = clampPage(page, activeRows.length, pageSize);
  const pagination = (
    <TablePagination
      page={safePage} pageSize={pageSize} total={activeRows.length}
      noun={tab === 'missing' ? 'records' : 'documents'}
      onPageChange={setPage}
      onPageSizeChange={n => { setPageSize(n); setPage(1); }}
    />
  );

  if (loading) return <p style={{ color: 'var(--txt-dim)', padding: 20 }}>Loading…</p>;

  if (loadError) {
    return (
      <div className="nf-dc-card nf-dc-error">
        <p className="nf-dc-error-t">Couldn't load documents</p>
        <p className="nf-dc-error-s">{loadError}</p>
        <button type="button" onClick={() => load(true)} className="nf-dc-ok">Retry</button>
      </div>
    );
  }

  const docRows = (rows: EmployeeDocument[], dateLabel: 'uploaded' | 'verified', actionLabel: string) => paginate(rows, safePage, pageSize).map(d => (
    <tr key={d.id}>
      <td><PersonCell userId={d.employeeUserId} name={d.employeeName} /></td>
      <td><span className="nf-dc-type"><FileText size={12} />{d.documentTypeName}</span></td>
      <td><FileCell name={d.fileName} /></td>
      <td><span className="nf-dc-date">{dateLabel === 'uploaded' ? fmtDate(d.uploadedAt) : fmtDate(d.verifiedAt)}</span></td>
      <td><ExpiryCell iso={d.expiryDate} /></td>
      <td style={{ width: 150 }}>
        <KebabMenu items={[{ label: actionLabel, onClick: () => setDetailDoc(d) }]} />
      </td>
    </tr>
  ));

  const docHead = (dateHeader: string) => (
    <>
      <colgroup>
        <col style={{ width: '22%' }} />
        <col style={{ width: '17%' }} />
        <col style={{ width: '24%' }} />
        <col style={{ width: '13%' }} />
        <col style={{ width: '14%' }} />
        <col style={{ width: 150 }} />
      </colgroup>
      <thead>
        <tr>
          <th>Employee</th>
          <th>Document Type</th>
          <th>File</th>
          <th>{dateHeader}</th>
          <th>Expiry</th>
          <th></th>
        </tr>
      </thead>
    </>
  );

  return (
    <div>
      <PageTitleBlock card icon={ShieldCheck}>
        <h1 style={{ fontSize: 22, fontWeight: 800, marginBottom: 4, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>Documents & Compliance</h1>
        <p style={{ color: 'var(--txt-dim)', fontSize: 13, marginBottom: 0 }}>Verify employee documents and track compliance.</p>
      </PageTitleBlock>

      {/* KPI row */}
      {kpis && (
        <div className="nf-kpi-2x2-mobile nf-dc-tiles">
          {[
            { label: 'Pending Verification', value: pending.length, tone: 'var(--warn)', icon: <Hourglass size={20} /> },
            { label: 'Employees Pending', value: kpis.employeesWithPending, tone: '#f97316', icon: <Users size={20} /> },
            { label: 'Expiring in 30 Days', value: kpis.expiringWithin30Days, tone: 'var(--risk)', icon: <CalendarClock size={20} /> },
            { label: 'Total Documents', value: kpis.totalDocuments, tone: 'var(--ok)', icon: <FileText size={20} /> },
          ].map(k => (
            <div key={k.label} className="nf-dc-tile" style={{ ['--mk' as string]: k.tone }}>
              <div className="nf-dc-tile-top">
                <span className="nf-dc-tile-label">{k.label}</span>
                <span className="nf-dc-tile-ico">{k.icon}</span>
              </div>
              <div className="nf-dc-tile-val">{k.value}</div>
            </div>
          ))}
        </div>
      )}

      {/* Tabs */}
      <TabBar
        ariaLabel="Document verification status"
        className="nf-pilltabs"
        style={{ marginBottom: 14 }}
        active={tab}
        onChange={setTab}
        tabs={[
          { key: 'pending', label: 'Pending Verification', count: pending.length },
          { key: 'verified', label: 'Verified', count: verified.length },
          { key: 'missing', label: 'Not Submitted', count: missing.length },
        ]}
      />

      {/* Search + Filter */}
      <div className="nf-dc-toolbar">
        <div className="nf-search-full-mobile nf-dc-search">
          <Search size={14} />
          <input value={search} onChange={e => setSearch(e.target.value)}
            placeholder={tab === 'missing' ? 'Search by name, type…' : 'Search by name, type, file…'}
            className="nf-search-full-mobile-input" />
        </div>
        {allDocTypes.length > 1 && (
          <select value={docTypeFilter} onChange={e => setDocTypeFilter(e.target.value)} className="nf-dc-select">
            <option value="">All document types</option>
            {allDocTypes.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
        )}
      </div>

      {/* ── Pending Verification tab ── */}
      {tab === 'pending' && (
        <div className="nf-dc-card">
          <div className="nf-doc-table-scroll">
            <table className="nf-dc-table nf-doc-table" style={{ tableLayout: 'fixed' }}>
              {docHead('Uploaded')}
              <tbody>
                {filteredPending.length === 0 ? (
                  <tr><td colSpan={6} className="nf-dc-cellmsg">
                    {filtering ? 'No results match your search or filter.' : 'No documents pending verification.'}
                  </td></tr>
                ) : docRows(filteredPending, 'uploaded', 'Review')}
              </tbody>
            </table>
          </div>
          {pagination}
        </div>
      )}

      {/* ── Verified tab ── */}
      {tab === 'verified' && (
        <div className="nf-dc-card">
          <div className="nf-doc-table-scroll">
            <table className="nf-dc-table nf-doc-table" style={{ tableLayout: 'fixed' }}>
              {docHead('Verified At')}
              <tbody>
                {filteredVerified.length === 0 ? (
                  <tr><td colSpan={6} className="nf-dc-cellmsg">
                    {filtering ? 'No results match your search or filter.' : 'No verified documents yet.'}
                  </td></tr>
                ) : docRows(filteredVerified, 'verified', 'View Document')}
              </tbody>
            </table>
          </div>
          {pagination}
        </div>
      )}

      {/* ── Not Submitted tab ── */}
      {tab === 'missing' && (
        <MissingTab missing={paginate(filteredMissing, safePage, pageSize)} searchEmpty={filtering} footer={pagination} />
      )}

      {detailDoc && (
        <DetailModal
          key={detailDoc.id}
          doc={detailDoc}
          onVerify={() => doVerify(detailDoc)}
          onReject={reason => doReject(detailDoc, reason)}
          onClose={() => setDetailDoc(null)}
        />
      )}
    </div>
  );
}

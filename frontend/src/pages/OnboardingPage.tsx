import { useCallback, useEffect, useState } from 'react';
import {
  ChevronRight, ChevronLeft, Clock, FileText, Package, RefreshCw, Check,
  Archive, Calendar, Users, AlertTriangle, ClipboardList, X, Eye, Search,
} from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import {
  onboardingApi, type OnboardingSummary, type OnboardingDetail, type OnboardingItem,
  type OnboardingStatus, type StartOnboardingPayload, type OnboardingStats, type Paged,
} from '../api/onboarding';
import type { EmployeeRecord } from '../api/employees';
import { assetsApi, type AssetResponse } from '../api/assets';
import { documentsForEmployee, fetchDocumentFile, type EmployeeDocument } from '../api/documents';
import { EmployeeAvatar } from '../components/EmployeeAvatar';

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
const thS: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', background: 'var(--raised)' };
const tdS: React.CSSProperties = { padding: '11px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
const inputStyle: React.CSSProperties = { width: '100%', padding: '9px 10px', borderRadius: 6, border: '1px solid var(--line2)', background: 'var(--shell)', color: 'var(--txt)', fontSize: 13, fontFamily: 'inherit', boxSizing: 'border-box' };
const btnStyle: React.CSSProperties = { padding: '8px 16px', background: 'var(--raised2)', border: '1px solid var(--line)', borderRadius: 7, color: 'var(--txt)', fontSize: 13, cursor: 'pointer' };
const btnPrimaryStyle: React.CSSProperties = { ...btnStyle, background: 'var(--brand)', borderColor: 'var(--brand)', color: '#fff', fontWeight: 600 };

// ── Small shared bits ──────────────────────────────────────

function fmtDate(iso: string | null | undefined) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

function StatusPill({ status }: { status: OnboardingStatus | string }) {
  const map: Record<string, { label: string; color: string; bg: string }> = {
    ON_TRACK: { label: 'On track', color: '#4C8DD6', bg: 'rgba(76,141,214,.15)' },
    ATTENTION: { label: 'Attention', color: '#E0A93B', bg: 'rgba(224,169,59,.15)' },
    OVERDUE: { label: 'Overdue', color: '#E4373D', bg: 'rgba(228,55,61,.15)' },
    COMPLETE: { label: 'Complete', color: '#2FB67C', bg: 'rgba(47,182,124,.15)' },
  };
  const cfg = map[status] ?? { label: status, color: 'var(--txt-dim)', bg: 'var(--raised2)' };
  return (
    <span style={{ fontSize: 11.5, fontWeight: 600, padding: '3px 9px', borderRadius: 20, color: cfg.color, background: cfg.bg, whiteSpace: 'nowrap' }}>
      {cfg.label}
    </span>
  );
}

function DocStatusBadge({ status }: { status: string }) {
  const map: Record<string, { color: string; bg: string; label: string }> = {
    VERIFIED: { color: '#2FB67C', bg: 'rgba(47,182,124,.15)', label: 'Verified' },
    PENDING_VERIFICATION: { color: '#E0A93B', bg: 'rgba(224,169,59,.15)', label: 'Pending' },
    REJECTED: { color: '#E4373D', bg: 'rgba(228,55,61,.15)', label: 'Rejected' },
    MISSING: { color: '#E4373D', bg: 'rgba(228,55,61,.15)', label: 'Missing' },
  };
  const cfg = map[status] ?? { color: 'var(--txt-dim)', bg: 'var(--raised2)', label: status };
  return <span style={{ fontSize: 10.5, padding: '2px 7px', borderRadius: 20, fontWeight: 600, color: cfg.color, background: cfg.bg }}>{cfg.label}</span>;
}

function Kpi({ icon, label, value, note, danger }: { icon: React.ReactNode; label: string; value: string | number; note: string; danger?: boolean }) {
  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '16px 18px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <span style={{ color: 'var(--brand)' }}>{icon}</span>
        <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em' }}>{label}</span>
      </div>
      <div style={{ fontSize: 28, fontWeight: 700, fontFamily: 'Inter, sans-serif', color: danger ? '#E4373D' : 'var(--txt)', lineHeight: 1 }}>{value}</div>
      <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 4 }}>{note}</div>
    </div>
  );
}

function tabStyle(active: boolean): React.CSSProperties {
  return { padding: '9px 2px', color: active ? 'var(--txt)' : 'var(--txt-mut)', border: 'none', borderBottom: `2px solid ${active ? 'var(--brand)' : 'transparent'}`, background: 'none', fontSize: 13, fontWeight: active ? 600 : 400, cursor: 'pointer' };
}

function Section({ icon, title, count, children }: { icon: React.ReactNode; title: string; count?: string; children: React.ReactNode }) {
  return (
    <div style={{ ...card, marginBottom: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '12px 18px', borderBottom: '1px solid var(--line)', background: 'var(--raised)' }}>
        <span style={{ color: 'var(--brand)' }}>{icon}</span>
        <h3 style={{ fontSize: 13, flex: 1, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>{title}</h3>
        {count && <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>{count}</span>}
      </div>
      {children}
    </div>
  );
}

// ── Server-side pagination bar — shared by all three lifecycle tabs ─────────

function PaginationBar({ page, totalPages, onPrev, onNext }: { page: number; totalPages: number; onPrev: () => void; onNext: () => void }) {
  if (totalPages <= 1) return null;
  return (
    <div style={{ padding: '12px 14px', borderTop: '1px solid var(--line)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
      <span style={{ fontSize: 12, color: 'var(--txt-mut)' }}>Page {page + 1} of {totalPages}</span>
      <div style={{ display: 'flex', gap: 4 }}>
        <button
          disabled={page === 0}
          onClick={onPrev}
          style={{ padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, cursor: page === 0 ? 'not-allowed' : 'pointer', opacity: page === 0 ? .4 : 1, color: 'var(--txt)', display: 'flex', alignItems: 'center' }}
        >
          <ChevronLeft size={13} />
        </button>
        <button
          disabled={page >= totalPages - 1}
          onClick={onNext}
          style={{ padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, cursor: page >= totalPages - 1 ? 'not-allowed' : 'pointer', opacity: page >= totalPages - 1 ? .4 : 1, color: 'var(--txt)', display: 'flex', alignItems: 'center' }}
        >
          <ChevronRight size={13} />
        </button>
      </div>
    </div>
  );
}

interface ItemRowAction {
  label: string;
  onClick: () => void;
  /** Whether to keep showing the action once the item is already done (documents stay viewable; asset actions hide once assigned). */
  showWhenDone?: boolean;
}

function ItemRow({ item, onToggle, toggling, action }: { item: OnboardingItem; onToggle?: () => void; toggling?: boolean; action?: ItemRowAction }) {
  const checkable = !!onToggle;
  const todayMidnight = new Date(new Date().toDateString()).getTime();
  const dueTime = item.dueDate ? new Date(item.dueDate).getTime() : null;
  const overdue = !item.done && dueTime !== null && dueTime < todayMidnight;
  const dueSoon = !item.done && dueTime !== null && !overdue && (dueTime - todayMidnight) <= 2 * 86400000;

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '11px 18px', borderBottom: '1px solid var(--line)' }}>
      <button
        disabled={!checkable || toggling}
        onClick={onToggle}
        aria-label={item.done ? `Mark ${item.label} as not done` : `Mark ${item.label} as done`}
        style={{
          width: 19, height: 19, borderRadius: 5, flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center',
          borderWidth: 1.5, borderColor: item.done ? '#2FB67C' : 'var(--line2)', borderStyle: item.auto ? 'dashed' : 'solid',
          background: item.done ? '#2FB67C' : 'var(--shell)', color: '#fff', cursor: checkable ? 'pointer' : 'default', padding: 0,
          opacity: toggling ? 0.6 : 1,
        }}
      >
        {item.done && <Check size={12} />}
      </button>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 13, color: item.done ? 'var(--txt-mut)' : 'var(--txt)', textDecorationLine: item.done ? 'line-through' : 'none', textDecorationColor: 'var(--line2)' }}>
          {item.label}
        </div>
        {(item.auto || (item.done && item.doneByName)) && (
          <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 2, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            {item.auto && (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: '#4C8DD6', background: 'rgba(76,141,214,.12)', padding: '1px 7px', borderRadius: 20, fontSize: 10, fontWeight: 600 }}>
                <RefreshCw size={10} /> auto-synced from {item.source}
              </span>
            )}
            {item.done && item.doneByName && (
              <span>{item.doneByName === 'System' ? 'synced automatically' : `checked off by ${item.doneByName}`}{item.meta ? ` · ${item.meta}` : ''}</span>
            )}
          </div>
        )}
      </div>
      {!item.done && item.dueDate && (
        <span style={{ fontSize: 11.5, whiteSpace: 'nowrap', color: overdue ? '#E4373D' : dueSoon ? '#E0A93B' : 'var(--txt-dim)', fontWeight: overdue || dueSoon ? 600 : 400 }}>
          {overdue ? 'overdue · ' : dueSoon ? 'due soon · ' : 'due '}{fmtDate(item.dueDate)}
        </span>
      )}
      {action && (!item.done || action.showWhenDone) && (
        <button
          onClick={action.onClick}
          style={{ fontSize: 11.5, color: 'var(--info)', background: 'none', textDecoration: 'none', border: '1px dashed var(--line2)', padding: '5px 10px', borderRadius: 6, whiteSpace: 'nowrap', cursor: 'pointer' }}
        >
          {action.label} →
        </button>
      )}
    </div>
  );
}

// ── Employee-scoped documents drawer (View Documents) ────────
// Shows only this employee's documents — never navigates to the global
// Documents & Compliance page, never exposes another employee's documents.

function EmployeeDocumentsDrawer({ employeeUserId, employeeName, onClose }: { employeeUserId: string; employeeName: string; onClose: () => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [docs, setDocs] = useState<EmployeeDocument[] | null>(null);
  const [opening, setOpening] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    documentsForEmployee(token, employeeUserId)
      .then(d => { if (!cancelled) setDocs(d); })
      .catch(e => { if (!cancelled) showToast('error', e instanceof Error ? e.message : 'Failed to load documents'); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [employeeUserId, token]);

  async function open(docId: string) {
    setOpening(docId);
    try {
      const url = await fetchDocumentFile(token, docId);
      window.open(url, '_blank');
    } catch {
      showToast('error', 'Could not open file');
    } finally {
      setOpening(null);
    }
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', justifyContent: 'flex-end', zIndex: 400 }}>
      <div style={{ background: 'var(--panel)', borderLeft: '1px solid var(--line)', width: '100%', maxWidth: 440, height: '100%', overflowY: 'auto', padding: 22, boxSizing: 'border-box' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
          <h2 style={{ fontSize: 15, margin: 0, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>{employeeName}'s documents</h2>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', display: 'flex' }}><X size={16} /></button>
        </div>
        <p style={{ color: 'var(--txt-mut)', fontSize: 12, margin: '4px 0 16px' }}>Documents uploaded by this employee only.</p>

        {docs === null ? (
          <p style={{ color: 'var(--txt-dim)' }}>Loading…</p>
        ) : docs.length === 0 ? (
          <p style={{ color: 'var(--txt-mut)', fontSize: 13 }}>No documents uploaded yet.</p>
        ) : (
          docs.map(d => (
            <div key={d.id} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 0', borderBottom: '1px solid var(--line)' }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 13, color: 'var(--txt)', fontWeight: 600 }}>{d.documentTypeName}</div>
                <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 2 }}>{d.fileName} · uploaded {fmtDate(d.uploadedAt)}</div>
                {d.status === 'REJECTED' && d.rejectionReason && (
                  <div style={{ fontSize: 11, color: '#E4373D', marginTop: 2 }}>Rejected: {d.rejectionReason}</div>
                )}
              </div>
              <DocStatusBadge status={d.status} />
              <button
                onClick={() => open(d.id)}
                disabled={opening === d.id}
                title="View"
                style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--info)', display: 'flex', flexShrink: 0, padding: 4 }}
              >
                <Eye size={15} />
              </button>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

// ── In-context asset assignment (Laptop / Access Card) ───────
// Reuses the existing assign-asset API and its existing AVAILABLE-only
// filtering/backend uniqueness guarantees — no parallel assignment system.

function AssignAssetToEmployeeModal({
  employeeUserId, employeeName, categoryName, onClose, onAssigned,
}: {
  employeeUserId: string; employeeName: string; categoryName: string; onClose: () => void; onAssigned: () => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [available, setAvailable] = useState<AssetResponse[] | null>(null);
  const [assetId, setAssetId] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let cancelled = false;
    assetsApi.categories(token)
      .then(cats => {
        const cat = cats.find(c => c.name.toLowerCase() === categoryName.toLowerCase());
        if (!cat) { if (!cancelled) setAvailable([]); return; }
        return assetsApi.availableByCategory(cat.id, token).then(a => { if (!cancelled) setAvailable(a); });
      })
      .catch(e => { if (!cancelled) showToast('error', e instanceof Error ? e.message : 'Failed to load assets'); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [categoryName, token]);

  async function submit() {
    if (!assetId) return;
    setSubmitting(true);
    try {
      await assetsApi.assignAsset(Number(assetId), employeeUserId, token);
      showToast('success', `${categoryName} assigned to ${employeeName}`);
      onAssigned();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Assignment failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 400, padding: '40px 16px' }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '100%', maxWidth: 440, padding: 24, boxSizing: 'border-box' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
          <h2 style={{ fontSize: 15, margin: 0, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>Assign {categoryName}</h2>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', display: 'flex' }}><X size={16} /></button>
        </div>
        <p style={{ color: 'var(--txt-mut)', fontSize: 12.5, margin: '4px 0 18px' }}>Assigning to <b style={{ color: 'var(--txt)' }}>{employeeName}</b>. Only available, unassigned assets are shown.</p>

        {available === null ? (
          <p style={{ color: 'var(--txt-dim)' }}>Loading…</p>
        ) : available.length === 0 ? (
          <p style={{ color: '#E0A93B', fontSize: 13 }}>No available {categoryName} in inventory right now.</p>
        ) : (
          <Field label={`Available ${categoryName}`}>
            <select value={assetId} onChange={e => setAssetId(e.target.value)} style={inputStyle}>
              <option value="">Select asset…</option>
              {available.map(a => (
                <option key={a.id} value={a.id}>{a.assetTag} — {[a.brand, a.model].filter(Boolean).join(' ') || 'No details'} ({a.condition})</option>
              ))}
            </select>
          </Field>
        )}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 4 }}>
          <button onClick={onClose} style={btnStyle}>Cancel</button>
          <button onClick={submit} style={btnPrimaryStyle} disabled={!assetId || submitting}>{submitting ? 'Assigning…' : 'Assign'}</button>
        </div>
      </div>
    </div>
  );
}

// ── Detail view ─────────────────────────────────────────────

function OnboardingDetailView({ checklistId, onBack, onChanged }: { checklistId: string; onBack: () => void; onChanged: () => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [detail, setDetail] = useState<OnboardingDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [toggling, setToggling] = useState<string | null>(null);
  const [showDocsBreakdown, setShowDocsBreakdown] = useState(false);
  const [showDocsDrawer, setShowDocsDrawer] = useState(false);
  const [showAssetModal, setShowAssetModal] = useState<string | null>(null);
  const [completing, setCompleting] = useState(false);

  const reload = () =>
    onboardingApi.detail(checklistId, token)
      .then(setDetail)
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Failed to load'));

  useEffect(() => {
    setLoading(true);
    reload().finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [checklistId, token]);

  async function toggle(item: OnboardingItem) {
    if (!detail || !item.id) return;
    setToggling(item.id);
    try {
      const updated = await onboardingApi.toggleItem(detail.checklistId, item.id, token);
      setDetail(updated);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Update failed');
    } finally {
      setToggling(null);
    }
  }

  async function complete() {
    if (!detail) return;
    setCompleting(true);
    try {
      const updated = await onboardingApi.complete(detail.checklistId, token);
      setDetail(updated);
      showToast('success', `${updated.employeeName} moved to Successfully Onboarded`);
      onChanged();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Could not complete onboarding');
      // The attempt failed because the server's state has likely moved on since we last loaded
      // it (e.g. someone else just completed this same checklist) — reload so the UI reflects
      // that instead of continuing to show a stale "ready to complete" banner and button.
      reload();
    } finally {
      setCompleting(false);
    }
  }

  if (loading || !detail) return <p style={{ color: 'var(--txt-dim)', padding: 20 }}>Loading…</p>;

  const hasDocumentsToView = detail.documentsBreakdown.some(d => d.status !== 'MISSING');
  const assetCategoryForItem: Record<string, string> = {
    LAPTOP_ASSIGNED: 'Laptop',
    ACCESS_CARD_ASSIGNED: 'Access Card',
  };

  const pct = detail.totalItems > 0 ? Math.round(100 * detail.doneItems / detail.totalItems) : 0;

  return (
    <div>
      <button onClick={onBack} style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'none', border: 'none', color: 'var(--txt-mut)', fontSize: 13, cursor: 'pointer', marginBottom: 16, padding: 0 }}>
        <ChevronLeft size={14} /> Back to onboarding queue
      </button>

      {detail.archived && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, background: 'rgba(47,182,124,.12)', border: '1px solid rgba(47,182,124,.3)', color: '#2FB67C', borderRadius: 10, padding: '13px 18px', marginBottom: 16, fontSize: 13.5, fontWeight: 600 }}>
          <Archive size={16} /> Onboarding complete — archived on {fmtDate(detail.completedAt)}.
        </div>
      )}

      {!detail.archived && detail.readyToComplete && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', background: 'rgba(76,141,214,.1)', border: '1px solid rgba(76,141,214,.3)', color: '#4C8DD6', borderRadius: 10, padding: '13px 18px', marginBottom: 16, fontSize: 13.5 }}>
          <span style={{ fontWeight: 600 }}><Check size={16} style={{ verticalAlign: -2, marginRight: 6 }} />All tasks are done — review the details above, then submit to move {detail.employeeName} to Successfully Onboarded.</span>
          <button onClick={complete} disabled={completing} style={{ ...btnPrimaryStyle, flexShrink: 0 }}>
            {completing ? 'Completing…' : 'Complete onboarding'}
          </button>
        </div>
      )}

      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 20, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 22px', marginBottom: 16 }}>
        <div style={{ display: 'flex', gap: 14 }}>
          <EmployeeAvatar
            userId={detail.employeeUserId}
            name={detail.employeeName}
            size={46}
            fontSize={15}
            background="var(--brand)"
            style={{ fontFamily: 'Inter, sans-serif' }}
          />
          <div>
            <h2 style={{ fontSize: 17, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>{detail.employeeName}</h2>
            <div style={{ color: 'var(--txt-mut)', fontSize: 12.5, marginTop: 3 }}>
              {[detail.designationName, detail.departmentName, detail.locationName].filter(Boolean).join(' · ')}
              {' · '}<span style={{ fontFamily: 'Inter, sans-serif' }}>{detail.employeeCode}</span>
            </div>
            <div style={{ display: 'flex', gap: 16, marginTop: 10, fontSize: 12, color: 'var(--txt-dim)', flexWrap: 'wrap' }}>
              <span><Calendar size={12} style={{ verticalAlign: -1.5, marginRight: 3 }} /> Joining <b style={{ color: 'var(--txt)' }}>{fmtDate(detail.joiningDate)}</b></span>
              {detail.managerName && <span>Manager <b style={{ color: 'var(--txt)' }}>{detail.managerName}</b></span>}
            </div>
          </div>
        </div>
        <div style={{ textAlign: 'right', display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 8, minWidth: 150, flexShrink: 0 }}>
          <StatusPill status={detail.status} />
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span style={{ fontSize: 20, fontWeight: 700, fontFamily: 'Inter, sans-serif', color: 'var(--txt)' }}>{pct}%</span>
            <div style={{ width: 80, height: 6, borderRadius: 20, background: 'var(--raised2)', overflow: 'hidden' }}>
              <div style={{ width: `${pct}%`, height: '100%', background: pct === 100 ? '#2FB67C' : 'var(--brand)' }} />
            </div>
          </div>
          <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>{detail.doneItems} of {detail.totalItems} tasks done</span>
        </div>
      </div>

      <Section icon={<ClipboardList size={14} />} title="Pre-boarding" count={`${detail.preBoarding.filter(i => i.done).length}/${detail.preBoarding.length}`}>
        {detail.preBoarding.map(i => (
          <ItemRow key={i.itemKey} item={i} onToggle={() => toggle(i)} toggling={toggling === i.id} />
        ))}
      </Section>

      <Section icon={<FileText size={14} />} title="Document collection" count={detail.documentsItem.done ? '1/1' : '0/1'}>
        <ItemRow
          item={detail.documentsItem}
          action={hasDocumentsToView ? { label: 'View Documents', onClick: () => setShowDocsDrawer(true), showWhenDone: true } : undefined}
        />
        <div style={{ padding: '0 18px 12px' }}>
          <button onClick={() => setShowDocsBreakdown(v => !v)} style={{ fontSize: 11.5, color: 'var(--info)', background: 'none', border: 'none', textDecoration: 'underline', cursor: 'pointer', padding: 0 }}>
            {showDocsBreakdown ? 'hide breakdown' : 'show breakdown'}
          </button>
          {showDocsBreakdown && (
            <div style={{ marginTop: 8, display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px,1fr))', gap: 8 }}>
              {detail.documentsBreakdown.length === 0 ? (
                <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>No required documents apply to this employee.</span>
              ) : detail.documentsBreakdown.map((d, idx) => (
                <div key={idx} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 12.5, background: 'var(--raised)', padding: '7px 10px', borderRadius: 8 }}>
                  <span>{d.documentTypeName}</span>
                  <DocStatusBadge status={d.status} />
                </div>
              ))}
            </div>
          )}
        </div>
      </Section>

      <Section icon={<Package size={14} />} title="Setup tasks" count={`${detail.setup.filter(i => i.done).length}/${detail.setup.length}`}>
        {detail.setup.map(i => {
          const assetCategory = assetCategoryForItem[i.itemKey];
          return (
            <ItemRow
              key={i.itemKey}
              item={i}
              onToggle={i.auto ? undefined : () => toggle(i)}
              toggling={toggling === i.id}
              action={assetCategory ? { label: `Assign ${assetCategory}`, onClick: () => setShowAssetModal(assetCategory) } : undefined}
            />
          );
        })}
      </Section>

      <Section icon={<Clock size={14} />} title="Activity">
        <div style={{ padding: '6px 18px 16px' }}>
          {detail.timeline.map((t, idx) => (
            <div key={idx} style={{ display: 'flex', gap: 12, padding: '8px 0', fontSize: 12.5 }}>
              <span style={{ color: 'var(--txt-dim)', width: 60, flexShrink: 0 }}>{fmtDate(t.at)}</span>
              <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--line2)', marginTop: 5, flexShrink: 0 }} />
              <div>
                <b style={{ fontWeight: 600, color: 'var(--txt)' }}>{t.text}</b>
                <div style={{ color: 'var(--txt-dim)' }}>{t.meta}</div>
              </div>
            </div>
          ))}
        </div>
      </Section>

      {showDocsDrawer && (
        <EmployeeDocumentsDrawer
          employeeUserId={detail.employeeUserId}
          employeeName={detail.employeeName}
          onClose={() => setShowDocsDrawer(false)}
        />
      )}

      {showAssetModal && (
        <AssignAssetToEmployeeModal
          employeeUserId={detail.employeeUserId}
          employeeName={detail.employeeName}
          categoryName={showAssetModal}
          onClose={() => setShowAssetModal(null)}
          onAssigned={() => { setShowAssetModal(null); reload(); }}
        />
      )}
    </div>
  );
}

// ── Shared small form field (used by AssignAssetToEmployeeModal) ────────────

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 14 }}>
      <label style={{ display: 'block', fontSize: 11.5, color: 'var(--txt-mut)', marginBottom: 5, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '.04em' }}>{label}</label>
      {children}
    </div>
  );
}

// ── Main page ────────────────────────────────────────────────

type OnboardingTab = 'pending' | 'started' | 'completed';

const PAGE_SIZE = 20;

const STATUS_FOR_TAB: Record<'started' | 'completed', 'IN_PROGRESS' | 'COMPLETED'> = {
  started: 'IN_PROGRESS',
  completed: 'COMPLETED',
};

export default function OnboardingPage() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [tab, setTab] = useState<OnboardingTab>('pending');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);

  // Pending is Employee-shaped; Started/Completed share the OnboardingSummary/status-queue shape.
  const [pendingData, setPendingData] = useState<Paged<EmployeeRecord> | null>(null);
  const [queueData, setQueueData] = useState<Paged<OnboardingSummary> | null>(null);
  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState('');

  const [stats, setStats] = useState<OnboardingStats | null>(null);

  const [startingId, setStartingId] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);

  // Reused after starting/completing onboarding, and by each tab's Retry button — always reloads
  // whichever tab/search/page is currently active.
  const loadList = useCallback(() => {
    setListLoading(true);
    setListError('');
    const request = tab === 'pending'
      ? onboardingApi.eligibleEmployees(search, page, PAGE_SIZE, token).then(setPendingData)
      : onboardingApi.queue(STATUS_FOR_TAB[tab], search, page, PAGE_SIZE, token).then(setQueueData);
    return request
      .catch(e => {
        const message = e instanceof Error ? e.message : 'Failed to load';
        setListError(message);
        showToast('error', message);
      })
      .finally(() => setListLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, search, page, token]);

  // KPI cards reflect the whole corpus, independent of whichever tab/page/search is active — a
  // separate effect so paging/searching a tab never refetches the cards, same split AuditLogView
  // uses for its stat cards vs. its paged rows.
  const loadStats = useCallback(() => {
    return onboardingApi.stats(token)
      .then(setStats)
      .catch(e => console.error('Failed to load onboarding stats', e));
  }, [token]);

  useEffect(() => { loadList(); }, [loadList]);
  useEffect(() => { loadStats(); }, [loadStats]);

  function switchTab(next: OnboardingTab) {
    setTab(next);
    setSearch('');
    setPage(0);
  }

  if (selected) {
    return (
      <OnboardingDetailView
        checklistId={selected}
        onBack={() => { setSelected(null); loadList(); loadStats(); }}
        onChanged={() => { loadList(); loadStats(); }}
      />
    );
  }

  // Starts onboarding directly for one Pending employee — no second employee search/selection
  // step. Success moves them straight into that employee's checklist (Pending → Started); no
  // need to reconstruct where HR was afterwards.
  async function startOnboardingFor(employee: EmployeeRecord) {
    setStartingId(employee.userId);
    try {
      const payload: StartOnboardingPayload = { employeeUserId: employee.userId };
      const created = await onboardingApi.start(payload, token);
      showToast('success', `Checklist created for ${created.employeeName}`);
      loadList();
      loadStats();
      setSelected(created.checklistId);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to start onboarding');
      setStartingId(null);
    }
  }

  const pendingRows = pendingData?.content ?? [];
  const queueRows = queueData?.content ?? [];
  const totalElements = (tab === 'pending' ? pendingData : queueData)?.totalElements ?? 0;
  const totalPages = (tab === 'pending' ? pendingData : queueData)?.totalPages ?? 0;
  const overdueCount = stats?.overdueCount ?? 0;
  const completedThisMonth = stats?.completedThisMonthCount ?? 0;
  const avgDays = stats?.avgCompletionDays ?? 0;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 18 }}>
        <div>
          <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>Onboarding</h1>
          <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13 }}>Every great career starts with NForceOne.</p>
        </div>
      </div>

      <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px,1fr))', gap: 12, marginBottom: 20 }}>
        <Kpi icon={<Users size={14} />} label="Pending onboarding" value={stats?.pendingCount ?? 0} note="Not yet started" />
        <Kpi icon={<ClipboardList size={14} />} label="Onboarding started" value={stats?.startedCount ?? 0} note="In progress right now" />
        <Kpi icon={<AlertTriangle size={14} />} label="Overdue tasks" value={overdueCount} note="Flows with a missed due date" danger={overdueCount > 0} />
        <Kpi icon={<Check size={14} />} label="Completed this month" value={completedThisMonth} note="Successfully onboarded this calendar month" />
        <Kpi icon={<Clock size={14} />} label="Avg. time to complete" value={`${avgDays} d`} note="Joining date to full checklist" />
      </div>

      <div style={{ display: 'flex', gap: 18, borderBottom: '1px solid var(--line)', marginBottom: 16 }}>
        <button onClick={() => switchTab('pending')} style={tabStyle(tab === 'pending')}>Pending Onboarding ({stats?.pendingCount ?? 0})</button>
        <button onClick={() => switchTab('started')} style={tabStyle(tab === 'started')}>Onboarding Started ({stats?.startedCount ?? 0})</button>
        <button onClick={() => switchTab('completed')} style={tabStyle(tab === 'completed')}>Successfully Onboarded ({stats?.completedCount ?? 0})</button>
      </div>

      <div style={{ marginBottom: 12, maxWidth: 340, position: 'relative' }}>
        <Search size={13} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
        <input
          value={search}
          onChange={e => { setSearch(e.target.value); setPage(0); }}
          placeholder="Search by employee name or code…"
          style={{ ...inputStyle, paddingLeft: 30 }}
        />
      </div>

      <div style={{ marginBottom: 8, fontSize: 12.5, color: 'var(--txt-mut)' }}>
        {listLoading ? 'Loading…' : `${totalElements} record(s) found`}
      </div>

      {tab === 'pending' ? (
        <div style={card}>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 620 }}>
              <thead>
                <tr>
                  <th style={thS}>Employee</th>
                  <th style={thS}>Department</th>
                  <th style={thS}>Joining date</th>
                  <th style={thS}></th>
                </tr>
              </thead>
              <tbody>
                {listLoading ? (
                  <tr><td colSpan={4} style={{ ...tdS, textAlign: 'center', padding: 40 }}>Loading…</td></tr>
                ) : listError ? (
                  <tr><td colSpan={4} style={{ ...tdS, textAlign: 'center', padding: 40 }}>
                    <span style={{ color: '#E4373D' }}>Couldn't load pending employees ({listError}).</span>{' '}
                    <button onClick={loadList} style={{ color: 'var(--info)', background: 'none', border: 'none', textDecoration: 'underline', cursor: 'pointer', fontSize: 13, padding: 0 }}>Retry</button>
                  </td></tr>
                ) : pendingRows.length === 0 ? (
                  <tr><td colSpan={4} style={{ ...tdS, textAlign: 'center', padding: 40 }}>
                    {search.trim() ? `No pending employees match "${search}".` : 'Every active employee already has onboarding started or completed.'}
                  </td></tr>
                ) : pendingRows.map(e => (
                  <tr key={e.userId}>
                    <td style={tdS}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <EmployeeAvatar userId={e.userId} name={e.fullName} size={30} fontSize={11} background="var(--brand)" />
                        <div>
                          <div style={{ fontWeight: 600, color: 'var(--txt)' }}>{e.fullName}</div>
                          <div style={{ color: 'var(--txt-dim)', fontSize: 11.5, fontFamily: 'Inter, sans-serif' }}>{e.employeeCode}</div>
                        </div>
                      </div>
                    </td>
                    <td style={tdS}>
                      {e.departmentName ?? '—'}
                      {e.designationName && <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 1 }}>{e.designationName}</div>}
                    </td>
                    <td style={tdS}>{fmtDate(e.joiningDate)}</td>
                    <td style={{ ...tdS, textAlign: 'right' }}>
                      <button
                        onClick={() => startOnboardingFor(e)}
                        disabled={startingId === e.userId}
                        style={{ ...btnPrimaryStyle, padding: '6px 12px', fontSize: 12.5 }}
                      >
                        {startingId === e.userId ? 'Starting…' : 'Start Onboarding'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <PaginationBar page={page} totalPages={totalPages} onPrev={() => setPage(p => p - 1)} onNext={() => setPage(p => p + 1)} />
        </div>
      ) : (
        <div style={card}>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 740 }}>
              <thead>
                <tr>
                  <th style={thS}>New hire</th>
                  <th style={thS}>Department</th>
                  <th style={thS}>{tab === 'started' ? 'Joining date' : 'Completed on'}</th>
                  {tab === 'started' ? <th style={thS}>Progress</th> : <th style={thS}>Duration</th>}
                  <th style={thS}>Status</th>
                  {tab === 'started' && <th style={thS}>Next due</th>}
                  <th style={thS}></th>
                </tr>
              </thead>
              <tbody>
                {listLoading ? (
                  <tr><td colSpan={7} style={{ ...tdS, textAlign: 'center', padding: 40 }}>Loading…</td></tr>
                ) : listError ? (
                  <tr><td colSpan={7} style={{ ...tdS, textAlign: 'center', padding: 40 }}>
                    <span style={{ color: '#E4373D' }}>Couldn't load onboarding flows ({listError}).</span>{' '}
                    <button onClick={loadList} style={{ color: 'var(--info)', background: 'none', border: 'none', textDecoration: 'underline', cursor: 'pointer', fontSize: 13, padding: 0 }}>Retry</button>
                  </td></tr>
                ) : queueRows.length === 0 ? (
                  <tr><td colSpan={7} style={{ ...tdS, textAlign: 'center', padding: 40 }}>
                    {search.trim() ? `No results match "${search}".` : 'Nothing here yet.'}
                  </td></tr>
                ) : queueRows.map(r => {
                  const pct = r.totalItems > 0 ? Math.round(100 * r.doneItems / r.totalItems) : 0;
                  return (
                    <tr
                      key={r.checklistId}
                      onClick={() => setSelected(r.checklistId)}
                      style={{ cursor: 'pointer' }}
                      onMouseEnter={e => { (e.currentTarget as HTMLTableRowElement).style.background = 'var(--raised)'; }}
                      onMouseLeave={e => { (e.currentTarget as HTMLTableRowElement).style.background = 'transparent'; }}
                    >
                      <td style={tdS}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                          <EmployeeAvatar userId={r.employeeUserId} name={r.employeeName} size={30} fontSize={11} background="var(--brand)" />
                          <div>
                            <div style={{ fontWeight: 600, color: 'var(--txt)' }}>{r.employeeName}</div>
                            <div style={{ color: 'var(--txt-dim)', fontSize: 11.5, fontFamily: 'Inter, sans-serif' }}>{r.employeeCode}</div>
                          </div>
                        </div>
                      </td>
                      <td style={tdS}>
                        {r.departmentName ?? '—'}
                        {r.designationName && <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 1 }}>{r.designationName}</div>}
                      </td>
                      <td style={tdS}>{fmtDate(tab === 'started' ? r.joiningDate : r.completedDate)}</td>
                      {tab === 'started' ? (
                        <td style={tdS}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 150 }}>
                            <div style={{ flex: 1, height: 6, borderRadius: 20, background: 'var(--raised2)', overflow: 'hidden' }}>
                              <div style={{ width: `${pct}%`, height: '100%', background: pct === 100 ? '#2FB67C' : 'var(--brand)' }} />
                            </div>
                            <span style={{ fontSize: 11.5, color: 'var(--txt-dim)', whiteSpace: 'nowrap' }}>{r.doneItems}/{r.totalItems}</span>
                          </div>
                        </td>
                      ) : (
                        <td style={tdS}>{r.durationDays} days</td>
                      )}
                      <td style={tdS}><StatusPill status={r.status} /></td>
                      {tab === 'started' && (
                        <td style={tdS}>{r.nextDueLabel ?? '—'}{r.nextDueDate ? ` · ${fmtDate(r.nextDueDate)}` : ''}</td>
                      )}
                      <td style={{ ...tdS, color: 'var(--txt-dim)' }}><ChevronRight size={14} /></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <PaginationBar page={page} totalPages={totalPages} onPrev={() => setPage(p => p - 1)} onNext={() => setPage(p => p + 1)} />
        </div>
      )}
    </div>
  );
}

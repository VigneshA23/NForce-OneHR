import { useCallback, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import PageTitleBlock from '../components/PageTitleBlock';
import {
  ChevronRight, ChevronLeft, Clock, FileText, Package, RefreshCw, Check,
  Archive, Calendar, Users, AlertTriangle, ClipboardList, X, Eye, Search, UserPlus
} from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import {
  onboardingApi, type OnboardingSummary, type OnboardingDetail, type OnboardingItem,
  type OnboardingStatus, type StartOnboardingPayload, type OnboardingStats, type Paged,
  type OnboardingQueueStatus,
} from '../api/onboarding';
import type { EmployeeRecord } from '../api/employees';
import { assetsApi, type AssetResponse } from '../api/assets';
import { documentsForEmployee, fetchDocumentFile, type EmployeeDocument } from '../api/documents';
import { EmployeeAvatar } from '../components/EmployeeAvatar';
import { TabBar } from '../components/TabBar';
import { StatusBadge, inactiveDimStyle, InactiveEditBanner } from '../components/EmployeeStatus';
import './onboarding.css';

// ── Small shared bits ──────────────────────────────────────

function fmtDate(iso: string | null | undefined) {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '-';
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

function StatusPill({ status }: { status: OnboardingStatus | string }) {
  const map: Record<string, { label: string; color: string }> = {
    ON_TRACK: { label: 'On track', color: '#4C8DD6' },
    ATTENTION: { label: 'Attention', color: '#E0A93B' },
    OVERDUE: { label: 'Overdue', color: '#E4373D' },
    COMPLETE: { label: 'Complete', color: '#2FB67C' },
  };
  const cfg = map[status] ?? { label: status, color: 'var(--txt-dim)' };
  return (
    <span className="nf-ob-pill" style={{ ['--pc' as string]: cfg.color }}>
      <i />{cfg.label}
    </span>
  );
}

function DocStatusBadge({ status }: { status: string }) {
  const map: Record<string, { color: string; label: string }> = {
    VERIFIED: { color: '#2FB67C', label: 'Verified' },
    PENDING_VERIFICATION: { color: '#E0A93B', label: 'Pending' },
    REJECTED: { color: '#E4373D', label: 'Rejected' },
    MISSING: { color: '#E4373D', label: 'Missing' },
  };
  const cfg = map[status] ?? { color: 'var(--txt-dim)', label: status };
  return <span className="nf-ob-pill is-sm" style={{ ['--pc' as string]: cfg.color }}><i />{cfg.label}</span>;
}

function Kpi({ icon, label, value, note, danger, tone }: { icon: React.ReactNode; label: string; value: string | number; note: string; danger?: boolean; tone: string }) {
  return (
    <div className="nf-ob-kpi" style={{ ['--mk' as string]: danger ? 'var(--risk)' : tone }}>
      <div className="nf-ob-kpi-top">
        <span className="nf-ob-kpi-ico">{icon}</span>
        <span className="nf-ob-kpi-label">{label}</span>
      </div>
      <div className="nf-ob-kpi-val">{value}</div>
      <div className="nf-ob-kpi-note">{note}</div>
    </div>
  );
}

function Section({ icon, title, count, children }: { icon: React.ReactNode; title: string; count?: string; children: React.ReactNode }) {
  return (
    <div className="nf-ob-sec">
      <div className="nf-ob-sec-head">
        <span className="nf-ob-sec-ico">{icon}</span>
        <h3>{title}</h3>
        {count && <span className="nf-ob-sec-count">{count}</span>}
      </div>
      <div className="nf-ob-sec-body">{children}</div>
    </div>
  );
}

// ── Server-side pagination bar — shared by all three lifecycle tabs ─────────

function PaginationBar({ page, totalPages, onPrev, onNext }: { page: number; totalPages: number; onPrev: () => void; onNext: () => void }) {
  if (totalPages <= 1) return null;
  return (
    <div className="nf-ob-pager">
      <span>Page {page + 1} of {totalPages}</span>
      <div>
        <button type="button" disabled={page === 0} onClick={onPrev} aria-label="Previous page"><ChevronLeft size={14} /></button>
        <button type="button" disabled={page >= totalPages - 1} onClick={onNext} aria-label="Next page"><ChevronRight size={14} /></button>
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
    <div className={`nf-ob-item${item.done ? ' is-done' : ''}${overdue ? ' is-overdue' : ''}`}>
      <button
        type="button"
        disabled={!checkable || toggling}
        onClick={onToggle}
        aria-label={item.done ? `Mark ${item.label} as not done` : `Mark ${item.label} as done`}
        className={`nf-ob-check${item.done ? ' is-on' : ''}${item.auto ? ' is-auto' : ''}${checkable ? ' is-click' : ''}`}
        style={{ opacity: toggling ? 0.6 : 1 }}
      >
        {item.done && <Check size={13} />}
      </button>
      <div className="nf-ob-item-main">
        <div className="nf-ob-item-label">{item.label}</div>
        {(item.auto || (item.done && item.doneByName)) && (
          <div className="nf-ob-item-meta">
            {item.auto && (
              <span className="nf-ob-auto"><RefreshCw size={10} /> auto-synced from {item.source}</span>
            )}
            {item.done && item.doneByName && (
              <span>{item.doneByName === 'System' ? 'synced automatically' : `checked off by ${item.doneByName}`}{item.meta ? ` · ${item.meta}` : ''}</span>
            )}
          </div>
        )}
      </div>
      {!item.done && item.dueDate && (
        <span className={`nf-ob-due${overdue ? ' is-overdue' : dueSoon ? ' is-soon' : ''}`}>
          {overdue ? 'overdue · ' : dueSoon ? 'due soon · ' : 'due '}{fmtDate(item.dueDate)}
        </span>
      )}
      {action && (!item.done || action.showWhenDone) && (
        <button type="button" onClick={action.onClick} className="nf-ob-act">
          {action.label} <ChevronRight size={13} />
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

  return createPortal(
    <div className="nf-ob-overlay is-right">
      <div className="nf-ob-drawer">
        <div className="nf-ob-dhead">
          <span className="nf-ob-dico"><FileText size={18} /></span>
          <div className="nf-ob-dtitles">
            <div className="nf-ob-dtitle">{employeeName}'s documents</div>
            <div className="nf-ob-dsub">Documents uploaded by this employee only.</div>
          </div>
          <button type="button" onClick={onClose} className="nf-ob-x" aria-label="Close"><X size={16} /></button>
        </div>
        <div className="nf-ob-dbody">
          {docs === null ? (
            <div className="nf-ob-empty">Loading…</div>
          ) : docs.length === 0 ? (
            <div className="nf-ob-empty"><FileText size={22} />No documents uploaded yet.</div>
          ) : (
            docs.map(d => (
              <div key={d.id} className="nf-ob-doc">
                <div className="nf-ob-doc-main">
                  <div className="nf-ob-doc-title">{d.documentTypeName}</div>
                  <div className="nf-ob-doc-sub">{d.fileName} · uploaded {fmtDate(d.uploadedAt)}</div>
                  {d.status === 'REJECTED' && d.rejectionReason && (
                    <div className="nf-ob-doc-rej">Rejected: {d.rejectionReason}</div>
                  )}
                </div>
                <DocStatusBadge status={d.status} />
                <button
                  type="button"
                  className="nf-row-view"
                  onClick={() => open(d.id)}
                  disabled={opening === d.id}
                  title="View"
                  style={{ flexShrink: 0, opacity: opening === d.id ? 0.6 : 1 }}
                >
                  <Eye size={13} /> View
                </button>
              </div>
            ))
          )}
        </div>
      </div>
    </div>,
    document.body,
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

  return createPortal(
    <div className="nf-ob-overlay">
      <div className="nf-ob-dlg">
        <div className="nf-ob-dhead">
          <span className="nf-ob-dico"><Package size={18} /></span>
          <div className="nf-ob-dtitles">
            <div className="nf-ob-dtitle">Assign {categoryName}</div>
            <div className="nf-ob-dsub">Assigning to <b>{employeeName}</b>. Only available, unassigned assets are shown.</div>
          </div>
          <button type="button" onClick={onClose} className="nf-ob-x" aria-label="Close"><X size={16} /></button>
        </div>
        <div className="nf-ob-dbody">
          {available === null ? (
            <div className="nf-ob-empty">Loading…</div>
          ) : available.length === 0 ? (
            <div className="nf-ob-warn"><AlertTriangle size={15} />No available {categoryName} in inventory right now.</div>
          ) : (
            <Field label={`Available ${categoryName}`}>
              <select value={assetId} onChange={e => setAssetId(e.target.value)} className="nf-ob-input">
                <option value="">Select asset…</option>
                {available.map(a => (
                  <option key={a.id} value={a.id}>{a.assetTag} - {[a.brand, a.model].filter(Boolean).join(' ') || 'No details'} ({a.condition})</option>
                ))}
              </select>
            </Field>
          )}
        </div>
        <div className="nf-ob-dfoot">
          <button type="button" onClick={onClose} className="nf-ob-ghost">Cancel</button>
          <button type="button" onClick={submit} className="nf-ob-primary" disabled={!assetId || submitting}>{submitting ? 'Assigning…' : 'Assign'}</button>
        </div>
      </div>
    </div>,
    document.body,
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
      <button type="button" onClick={onBack} className="nf-ob-back">
        <ChevronLeft size={14} /> Back to onboarding queue
      </button>

      {!detail.active && (
        <div style={{ marginBottom: 16 }}>
          <InactiveEditBanner message={`${detail.employeeName} has been deactivated - this onboarding flow is kept for history only and is not counted as an active onboarding case.`} />
        </div>
      )}

      {detail.archived && (
        <div className="nf-ob-banner is-ok">
          <Archive size={17} /> Onboarding complete - archived on {fmtDate(detail.completedAt)}.
        </div>
      )}

      {!detail.archived && detail.readyToComplete && (
        <div className="nf-ob-banner is-info">
          <span><Check size={17} />All tasks are done - review the details above, then submit to move {detail.employeeName} to Successfully Onboarded.</span>
          <button type="button" onClick={complete} disabled={completing} className="nf-ob-primary">
            {completing ? 'Completing…' : 'Complete onboarding'}
          </button>
        </div>
      )}

      <div className="nf-ob-hero">
        <div className="nf-ob-hero-who">
          <EmployeeAvatar
            userId={detail.employeeUserId}
            name={detail.employeeName}
            size={54}
            fontSize={17}
            background="var(--brand)"
            style={{ fontFamily: 'Inter, sans-serif' }}
          />
          <div style={{ minWidth: 0 }}>
            <h2>{detail.employeeName}</h2>
            <div className="nf-ob-chips">
              {[detail.designationName, detail.departmentName, detail.locationName].filter(Boolean).map(t => <span key={t} className="nf-ob-chip">{t}</span>)}
              <span className="nf-ob-chip is-code">{detail.employeeCode}</span>
            </div>
            <div className="nf-ob-facts">
              <span><Calendar size={13} /> Joining <b>{fmtDate(detail.joiningDate)}</b></span>
              {detail.managerName && <span><Users size={13} /> Manager <b>{detail.managerName}</b></span>}
            </div>
          </div>
        </div>
        <div className="nf-ob-hero-side">
          {detail.active ? <StatusPill status={detail.status} /> : <StatusBadge active={false} />}
          <div className="nf-ob-pct">
            <b>{pct}%</b>
            <div className="nf-ob-bar"><i className={pct === 100 ? 'is-full' : ''} style={{ width: `${pct}%` }} /></div>
          </div>
          <small>{detail.doneItems} of {detail.totalItems} tasks done</small>
        </div>
      </div>

      <Section icon={<ClipboardList size={15} />} title="Pre-boarding" count={`${detail.preBoarding.filter(i => i.done).length}/${detail.preBoarding.length}`}>
        {detail.preBoarding.map(i => (
          <ItemRow key={i.itemKey} item={i} onToggle={() => toggle(i)} toggling={toggling === i.id} />
        ))}
      </Section>

      <Section icon={<FileText size={15} />} title="Document collection" count={detail.documentsItem.done ? '1/1' : '0/1'}>
        <ItemRow
          item={detail.documentsItem}
          action={hasDocumentsToView ? { label: 'View Documents', onClick: () => setShowDocsDrawer(true), showWhenDone: true } : undefined}
        />
        <div className="nf-ob-bd-wrap">
          <button type="button" onClick={() => setShowDocsBreakdown(v => !v)} className="nf-ob-link">
            {showDocsBreakdown ? 'Hide breakdown' : 'Show breakdown'}
          </button>
          {showDocsBreakdown && (
            <div className="nf-ob-bd">
              {detail.documentsBreakdown.length === 0 ? (
                <span className="nf-ob-none">No required documents apply to this employee.</span>
              ) : detail.documentsBreakdown.map((d, idx) => (
                <div key={idx} className="nf-ob-bd-item">
                  <span>{d.documentTypeName}</span>
                  <DocStatusBadge status={d.status} />
                </div>
              ))}
            </div>
          )}
        </div>
      </Section>

      <Section icon={<Package size={15} />} title="Setup tasks" count={`${detail.setup.filter(i => i.done).length}/${detail.setup.length}`}>
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

      <Section icon={<Clock size={15} />} title="Activity">
        <div className="nf-ob-tl">
          {detail.timeline.map((t, idx) => (
            <div key={idx} className="nf-ob-tl-row">
              <span className="nf-ob-tl-date">{fmtDate(t.at)}</span>
              <span className="nf-ob-tl-dot" />
              <div>
                <b>{t.text}</b>
                <div>{t.meta}</div>
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
    <div className="nf-ob-field">
      <label>{label}</label>
      {children}
    </div>
  );
}

// ── Main page ────────────────────────────────────────────────

// 'deactivated' holds in-progress flows whose employee was deactivated mid-onboarding — kept
// visible for history but out of 'started' and its counts (the server excludes them there).
type OnboardingTab = 'pending' | 'started' | 'completed' | 'deactivated';

const PAGE_SIZE = 20;

const STATUS_FOR_TAB: Record<Exclude<OnboardingTab, 'pending'>, OnboardingQueueStatus> = {
  started: 'IN_PROGRESS',
  completed: 'COMPLETED',
  deactivated: 'DEACTIVATED',
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
  // Deactivated rows are still-open flows, so they show joining date + frozen progress like Started.
  const inProgressTab = tab === 'started' || tab === 'deactivated';
  const totalElements = (tab === 'pending' ? pendingData : queueData)?.totalElements ?? 0;
  const totalPages = (tab === 'pending' ? pendingData : queueData)?.totalPages ?? 0;
  const overdueCount = stats?.overdueCount ?? 0;
  const completedThisMonth = stats?.completedThisMonthCount ?? 0;
  const avgDays = stats?.avgCompletionDays ?? 0;

  return (
    <div>
      <div className="nf-page-hdr" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 18 }}>
        <PageTitleBlock icon={UserPlus}>
          <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>Onboarding</h1>
          <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13 }}>Every great career starts with NForceOne.</p>
        </PageTitleBlock>
      </div>

      <div className="nf-kpi-2x2-mobile nf-ob-kpis">
        <Kpi tone="var(--brand)" icon={<Users size={15} />} label="Pending onboarding" value={stats?.pendingCount ?? 0} note="Not yet started" />
        <Kpi tone="var(--info)" icon={<ClipboardList size={15} />} label="Onboarding started" value={stats?.startedCount ?? 0} note="In progress right now" />
        <Kpi tone="var(--warn)" icon={<AlertTriangle size={15} />} label="Overdue tasks" value={overdueCount} note="Flows with a missed due date" danger={overdueCount > 0} />
        <Kpi tone="var(--ok)" icon={<Check size={15} />} label="Completed this month" value={completedThisMonth} note="Successfully onboarded this calendar month" />
        <Kpi tone="var(--txt-mut)" icon={<Clock size={15} />} label="Avg. time to complete" value={`${avgDays} d`} note="Joining date to full checklist" />
      </div>

      <TabBar
        ariaLabel="Onboarding status"
        className="nf-pilltabs"
        style={{ marginBottom: 16 }}
        active={tab}
        onChange={switchTab}
        tabs={[
          { key: 'pending', label: 'Pending Onboarding', count: stats?.pendingCount },
          { key: 'started', label: 'Onboarding Started', count: stats?.startedCount },
          { key: 'completed', label: 'Successfully Onboarded', count: stats?.completedCount },
          { key: 'deactivated', label: 'Deactivated', count: stats?.deactivatedCount },
        ]}
      />

      <div className="nf-ob-toolbar">
        <div className="nf-ob-search">
          <Search size={14} />
          <input
            value={search}
            onChange={e => { setSearch(e.target.value); setPage(0); }}
            placeholder="Search by employee name or code…"
          />
        </div>
        <span className="nf-ob-found">{listLoading ? 'Loading…' : `${totalElements} record(s) found`}</span>
      </div>

      {tab === 'pending' ? (
        <div className="nf-ob-card">
          <div style={{ overflowX: 'auto' }}>
            <table className="nf-ob-table" style={{ minWidth: 620 }}>
              <thead>
                <tr>
                  <th>Employee</th>
                  <th>Department</th>
                  <th>Joining date</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {listLoading ? (
                  <tr><td colSpan={4} className="nf-ob-cellmsg">Loading…</td></tr>
                ) : listError ? (
                  <tr><td colSpan={4} className="nf-ob-cellmsg">
                    <span style={{ color: 'var(--risk)' }}>Couldn't load pending employees ({listError}).</span>{' '}
                    <button type="button" onClick={loadList} className="nf-ob-link">Retry</button>
                  </td></tr>
                ) : pendingRows.length === 0 ? (
                  <tr><td colSpan={4} className="nf-ob-cellmsg">
                    {search.trim() ? `No pending employees match "${search}".` : 'Every active employee already has onboarding started or completed.'}
                  </td></tr>
                ) : pendingRows.map(e => (
                  <tr key={e.userId}>
                    <td>
                      <div className="nf-ob-emp">
                        <EmployeeAvatar userId={e.userId} name={e.fullName} size={34} fontSize={12} background="var(--brand)" />
                        <div>
                          <div className="nf-ob-name">{e.fullName}</div>
                          <div className="nf-ob-code">{e.employeeCode}</div>
                        </div>
                      </div>
                    </td>
                    <td>
                      {e.departmentName ?? '-'}
                      {e.designationName && <div className="nf-ob-sub">{e.designationName}</div>}
                    </td>
                    <td><span className="nf-ob-date"><Calendar size={13} />{fmtDate(e.joiningDate)}</span></td>
                    <td style={{ textAlign: 'right' }}>
                      <button
                        type="button"
                        onClick={() => startOnboardingFor(e)}
                        disabled={startingId === e.userId}
                        className="nf-ob-primary is-sm"
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
        <div className="nf-ob-card">
          <div style={{ overflowX: 'auto' }}>
            <table className="nf-ob-table" style={{ minWidth: 740 }}>
              <thead>
                <tr>
                  <th>New hire</th>
                  <th>Department</th>
                  <th>{inProgressTab ? 'Joining date' : 'Completed on'}</th>
                  {inProgressTab ? <th>Progress</th> : <th>Duration</th>}
                  <th>Status</th>
                  {tab === 'started' && <th>Next due</th>}
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {listLoading ? (
                  <tr><td colSpan={7} className="nf-ob-cellmsg">Loading…</td></tr>
                ) : listError ? (
                  <tr><td colSpan={7} className="nf-ob-cellmsg">
                    <span style={{ color: 'var(--risk)' }}>Couldn't load onboarding flows ({listError}).</span>{' '}
                    <button type="button" onClick={loadList} className="nf-ob-link">Retry</button>
                  </td></tr>
                ) : queueRows.length === 0 ? (
                  <tr><td colSpan={7} className="nf-ob-cellmsg">
                    {search.trim() ? `No results match "${search}".` : 'Nothing here yet.'}
                  </td></tr>
                ) : queueRows.map(r => {
                  const pct = r.totalItems > 0 ? Math.round(100 * r.doneItems / r.totalItems) : 0;
                  return (
                    <tr
                      key={r.checklistId}
                      className="is-click"
                      onClick={() => setSelected(r.checklistId)}
                      style={inactiveDimStyle(r.active)}
                    >
                      <td>
                        <div className="nf-ob-emp">
                          <EmployeeAvatar userId={r.employeeUserId} name={r.employeeName} size={34} fontSize={12} background="var(--brand)" />
                          <div>
                            <div className="nf-ob-name">{r.employeeName}</div>
                            <div className="nf-ob-code">{r.employeeCode}</div>
                          </div>
                        </div>
                      </td>
                      <td>
                        {r.departmentName ?? '-'}
                        {r.designationName && <div className="nf-ob-sub">{r.designationName}</div>}
                      </td>
                      <td><span className="nf-ob-date"><Calendar size={13} />{fmtDate(inProgressTab ? r.joiningDate : r.completedDate)}</span></td>
                      {inProgressTab ? (
                        <td>
                          <div className="nf-ob-prog">
                            <div className="nf-ob-bar"><i className={pct === 100 ? 'is-full' : ''} style={{ width: `${pct}%` }} /></div>
                            <span>{r.doneItems}/{r.totalItems}</span>
                          </div>
                        </td>
                      ) : (
                        <td>{r.durationDays} days</td>
                      )}
                      <td>{r.active ? <StatusPill status={r.status} /> : <StatusBadge active={false} />}</td>
                      {tab === 'started' && (
                        <td>{r.nextDueLabel ?? '-'}{r.nextDueDate ? ` · ${fmtDate(r.nextDueDate)}` : ''}</td>
                      )}
                      <td><span className="nf-ob-go"><ChevronRight size={15} /></span></td>
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

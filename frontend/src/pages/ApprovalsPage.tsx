import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  CalendarClock, CalendarDays, Check, CheckCircle2, ClipboardCheck, Clock, FileText, Hourglass, Home, Package, Search, Wallet, X,
  type LucideIcon,
} from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { EmployeeAvatar } from '../components/EmployeeAvatar';
import { approvalCenterApi, type ApprovalItem, type RequestType } from '../api/approvalCenter';
import { helpContentApprovalApi, type ApprovalDiff } from '../api/helpContentApproval';
import { AttachmentViewerModal } from '../components/helpContent/AttachmentViewerModal';
import { ReceiptViewerModal } from '../components/expenses/ReceiptViewerModal';
import { leaveApi } from '../api/leave';
import { regularizationApi } from '../api/attendance';
import { expensesApi } from '../api/expenses';
import { assetsApi } from '../api/assets';
import { attendanceRequestApi } from '../api/attendanceRequests';
import { overtimeRequestApi } from '../api/overtimeRequests';
import { useToast } from '../context/ToastContext';
import { formatDurationMinutes } from '../context/TimeFormatContext';
import './ApprovalsPage.css';

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 520, boxShadow: '0 24px 64px rgba(0,0,0,.55)', maxHeight: '90vh', overflowY: 'auto' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };

const TYPE_LABELS: Record<RequestType, string> = {
  LEAVE: 'Leave',
  REGULARIZATION: 'Attendance Reg.',
  EXPENSE: 'Expense',
  ASSET_REQUEST: 'Asset Request',
  WFH: 'Work From Home',
  PARTIAL_DAY: 'Partial Day',
  OVERTIME: 'Overtime',
  HELP_CONTENT: 'FAQs & Guides',
};

// Full-length labels for the "No pending ... requests" empty state, which has room to spell
// things out — everywhere else (tab pills, badges, aria-labels) keeps using TYPE_LABELS'
// abbreviated forms since those are space-constrained.
const EMPTY_STATE_TYPE_LABELS: Record<RequestType, string> = {
  ...TYPE_LABELS,
  REGULARIZATION: 'Attendance Regularization',
};

const TYPE_COLORS: Record<RequestType, string> = {
  LEAVE: 'rgba(99,102,241,.18)',
  REGULARIZATION: 'rgba(245,158,11,.18)',
  EXPENSE: 'rgba(16,185,129,.18)',
  ASSET_REQUEST: 'rgba(139,92,246,.18)',
  WFH: 'rgba(76,141,214,.18)',
  PARTIAL_DAY: 'rgba(224,169,59,.18)',
  OVERTIME: 'rgba(236,72,153,.18)',
  HELP_CONTENT: 'rgba(20,184,166,.18)',
};

const TYPE_TEXT: Record<RequestType, string> = {
  LEAVE: '#818CF8',
  REGULARIZATION: '#F59E0B',
  EXPENSE: '#10B981',
  ASSET_REQUEST: '#8B5CF6',
  WFH: '#4C8DD6',
  PARTIAL_DAY: '#E0A93B',
  OVERTIME: '#EC4899',
  HELP_CONTENT: '#14B8A6',
};

const TYPE_ICONS: Record<RequestType, LucideIcon> = {
  LEAVE: CalendarDays,
  REGULARIZATION: CalendarClock,
  EXPENSE: Wallet,
  ASSET_REQUEST: Package,
  WFH: Home,
  PARTIAL_DAY: Hourglass,
  OVERTIME: Clock,
  HELP_CONTENT: FileText,
};

const EMPTY_VALUE = '—';

type ReviewMode = 'approve' | 'reject';
/** 'review' = opened by clicking the row itself: details first, with both decisions available. */
type ReviewModalMode = ReviewMode | 'review';

/** Composite identity for a queue row — a plain requestType+id pair collides across types
 *  (e.g. a LEAVE id and an EXPENSE id can be numerically equal), so every place that needs to
 *  key/select/dedupe a row (table row key, checkbox selection) goes through this. */
function rowKey(item: Pick<ApprovalItem, 'id' | 'requestType'>): string {
  return `${item.requestType}:${item.id}`;
}

/** Single source of truth for "which endpoint approves this request type" — shared by the
 *  single-item ReviewModal and the bulk-action modal so the per-type dispatch is never
 *  duplicated (and never drifts) between the two. */
function approveItem(item: ApprovalItem, token: string) {
  if (item.requestType === 'LEAVE') return leaveApi.approve(item.id, token);
  if (item.requestType === 'REGULARIZATION') return regularizationApi.approve(item.id, token);
  if (item.requestType === 'EXPENSE') {
    return item.approvalStage === 'MANAGER'
      ? expensesApi.managerApprove(item.id, token)
      : expensesApi.finalApprove(item.id, token);
  }
  if (item.requestType === 'ASSET_REQUEST') return assetsApi.approveRequest(Number(item.id), token);
  if (item.requestType === 'WFH' || item.requestType === 'PARTIAL_DAY') return attendanceRequestApi.approve(item.id, token);
  if (item.requestType === 'OVERTIME') return overtimeRequestApi.approve(item.id, token);
  return helpContentApprovalApi.approve(item.id, token);
}

/** Reject-side counterpart of {@link approveItem} — same per-type dispatch, same reuse rationale. */
function rejectItem(item: ApprovalItem, reason: string, token: string) {
  if (item.requestType === 'LEAVE') return leaveApi.reject(item.id, reason, token);
  if (item.requestType === 'REGULARIZATION') return regularizationApi.reject(item.id, reason, token);
  if (item.requestType === 'EXPENSE') {
    return item.approvalStage === 'MANAGER'
      ? expensesApi.managerReject(item.id, reason, token)
      : expensesApi.finalReject(item.id, reason, token);
  }
  if (item.requestType === 'ASSET_REQUEST') return assetsApi.rejectRequest(Number(item.id), reason, token);
  if (item.requestType === 'WFH' || item.requestType === 'PARTIAL_DAY') return attendanceRequestApi.reject(item.id, reason, token);
  if (item.requestType === 'OVERTIME') return overtimeRequestApi.reject(item.id, reason, token);
  return helpContentApprovalApi.reject(item.id, reason, token);
}

function TypeBadge({ type }: { type: RequestType }) {
  return (
    <span style={{ fontSize: 10.5, fontWeight: 700, padding: '3px 8px', borderRadius: 20, background: TYPE_COLORS[type], color: TYPE_TEXT[type], whiteSpace: 'nowrap' }}>
      {TYPE_LABELS[type]}
    </span>
  );
}

function fmtCurrency(n: number) {
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(n);
}

function fmtDate(s?: string | null) {
  if (!s) return EMPTY_VALUE;
  return new Date(s).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

function fmtTime(s?: string | null) {
  if (!s) return EMPTY_VALUE;
  return new Date(s).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' });
}

/** Overtime's requested duration ("Overtime Hours") — derived from requestedCheckIn/Out rather
 * than shown as raw clock times, since the employee-facing concept is hours claimed, not when. */
function fmtOvertimeHours(startIso?: string | null, endIso?: string | null) {
  if (!startIso || !endIso) return EMPTY_VALUE;
  const minutes = Math.round((new Date(endIso).getTime() - new Date(startIso).getTime()) / 60000);
  if (minutes <= 0) return EMPTY_VALUE;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

function getRequestedDates(item: ApprovalItem) {
  if (item.requestType === 'LEAVE') {
    if (!item.leaveStartDate) return EMPTY_VALUE;
    return `${item.leaveStartDate}${item.leaveEndDate && item.leaveStartDate !== item.leaveEndDate ? ` → ${item.leaveEndDate}` : ''}${item.leaveHalfDay ? ' (half day)' : ''}`;
  }
  if (item.requestType === 'REGULARIZATION' || item.requestType === 'WFH' || item.requestType === 'PARTIAL_DAY' || item.requestType === 'OVERTIME') {
    return item.attendanceDate ?? EMPTY_VALUE;
  }
  if (item.requestType === 'EXPENSE') {
    return item.expenseDate ?? EMPTY_VALUE;
  }
  // Asset requests only ever collect Category + Reason (see RequestAssetModal) — there's no
  // separate "requested date" field to show. Rather than leave the column looking blank/broken,
  // fall back to the submission date, which is a real, already-captured date for every request
  // (item.createdAt is always populated). Sliced to a plain date to match the un-formatted
  // ISO date strings the other branches above show in this same column.
  if (item.requestType === 'ASSET_REQUEST') {
    return item.createdAt.slice(0, 10);
  }
  return EMPTY_VALUE;
}

function getReason(item: ApprovalItem) {
  if (item.requestType === 'LEAVE') return item.leaveReason ?? EMPTY_VALUE;
  if (item.requestType === 'EXPENSE') return item.businessPurpose ?? EMPTY_VALUE;
  if (item.requestType === 'ASSET_REQUEST') return item.assetRequestReason ?? EMPTY_VALUE;
  if (
    item.requestType === 'REGULARIZATION' ||
    item.requestType === 'WFH' ||
    item.requestType === 'PARTIAL_DAY' ||
    item.requestType === 'OVERTIME'
  ) {
    return item.regularizationReason ?? EMPTY_VALUE;
  }
  return EMPTY_VALUE;
}

function ActionIconButton({
  label,
  title,
  color,
  background,
  border,
  onClick,
  children,
}: {
  label: string;
  title: string;
  color: string;
  background: string;
  border: string;
  onClick: (event: React.MouseEvent<HTMLButtonElement>) => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={title}
      onClick={onClick}
      style={{
        width: 30,
        height: 30,
        borderRadius: '50%',
        border,
        background,
        color,
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        cursor: 'pointer',
        padding: 0,
        flexShrink: 0,
      }}
    >
      {children}
    </button>
  );
}

function ChangesModal({ diff, onClose }: { diff: ApprovalDiff; onClose: () => void }) {
  const changedFields = diff.fieldChanges.filter(f => f.changed);
  const attachmentChanges = diff.attachmentChanges.filter(c => c.changeType !== 'UNCHANGED');
  return (
    <div onClick={e => { if (e.target === e.currentTarget) onClose(); }} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 600 }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 620, maxHeight: '80vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
          <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>Changes vs. previous submission</span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex' }}><X size={16} /></button>
        </div>
        <div style={{ padding: 20 }}>
          {changedFields.length === 0 && attachmentChanges.length === 0 ? (
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>No changes detected versus the previous submission.</div>
          ) : (
            <>
              {changedFields.map(f => (
                <div key={f.fieldName} style={{ marginBottom: 14 }}>
                  <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 5 }}>{f.fieldName}</div>
                  <div style={{ fontSize: 13, lineHeight: 1.6 }}>
                    {f.segments.map((s, i) => (
                      <span
                        key={i}
                        style={{
                          background: s.type === 'ADDED' ? 'rgba(16,185,129,.18)' : s.type === 'REMOVED' ? 'rgba(228,55,61,.18)' : 'transparent',
                          textDecoration: s.type === 'REMOVED' ? 'line-through' : 'none',
                          color: s.type === 'REMOVED' ? 'var(--txt-dim)' : 'var(--txt)',
                        }}
                      >
                        {s.text}
                      </span>
                    ))}
                  </div>
                </div>
              ))}
              {attachmentChanges.map((c, i) => (
                <div key={i} style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginBottom: 6 }}>
                  <strong style={{ color: 'var(--txt)' }}>{c.changeType}:</strong>{' '}
                  {c.changeType === 'ADDED' && c.fileName}
                  {c.changeType === 'REMOVED' && c.previousFileName}
                  {c.changeType === 'REPLACED' && `${c.previousFileName} → ${c.fileName}`}
                  {c.changeType === 'REORDERED' && `${c.fileName} (position ${(c.previousDisplayOrder ?? 0) + 1} → ${(c.displayOrder ?? 0) + 1})`}
                </div>
              ))}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function HelpContentReviewSection({ item, token }: { item: ApprovalItem; token: string }) {
  const [diff, setDiff] = useState<ApprovalDiff | null>(null);
  const [viewingAttachments, setViewingAttachments] = useState(false);
  const [showChanges, setShowChanges] = useState(false);

  useEffect(() => { helpContentApprovalApi.getDiff(item.id, token).then(setDiff); }, [item.id, token]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 16 }}>
      <Row label="Type" value={item.helpContentType} />
      <Row label="Title" value={item.helpContentTitle} />
      {item.helpContentDescription && <Row label="Description" value={item.helpContentDescription} />}
      {item.helpContentBody && <Row label="Body" value={item.helpContentBody} />}
      {item.helpContentCategory && <Row label="Category" value={item.helpContentCategory} />}
      <Row label="Attempt" value={`#${item.helpContentAttemptNumber}`} />

      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        {(diff?.current.attachments.length ?? 0) > 0 && (
          <span style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
            This submission has {diff?.current.attachments.length} attachment{(diff?.current.attachments.length ?? 0) === 1 ? '' : 's'}. Would you like to take a look?
          </span>
        )}
        <button
          onClick={() => setViewingAttachments(true)}
          disabled={!diff || (diff?.current.attachments.length ?? 0) === 0}
          style={{ background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '5px 12px', fontSize: 11.5, fontWeight: 600, color: 'var(--brand)', cursor: diff && (diff?.current.attachments.length ?? 0) > 0 ? 'pointer' : 'not-allowed', opacity: diff && (diff?.current.attachments.length ?? 0) > 0 ? 1 : 0.5 }}
        >
          View Attachments
        </button>
        {item.helpContentModifiedSincePrevious && (
          <>
            <span style={{ fontSize: 10.5, fontWeight: 700, padding: '3px 8px', borderRadius: 20, background: 'rgba(245,158,11,.15)', color: '#F59E0B' }}>Modified since previous submission</span>
            <button onClick={() => setShowChanges(true)} disabled={!diff} style={{ background: 'none', border: 'none', color: 'var(--brand)', fontSize: 12, fontWeight: 600, cursor: diff ? 'pointer' : 'not-allowed', padding: 0 }}>View Changes</button>
          </>
        )}
      </div>

      {viewingAttachments && diff && (
        <AttachmentViewerModal
          title={item.helpContentTitle ?? 'Attachments'}
          attachments={diff.current.attachments}
          fetchBlob={attachmentId => helpContentApprovalApi.downloadAttachment(item.id, attachmentId, token)}
          onClose={() => setViewingAttachments(false)}
        />
      )}
      {showChanges && diff && <ChangesModal diff={diff} onClose={() => setShowChanges(false)} />}
    </div>
  );
}

function ReviewModal({ item, mode, onClose, onApproved, onRejected, token }: {
  item: ApprovalItem;
  mode: ReviewModalMode;
  onClose: () => void;
  onApproved: () => void;
  onRejected: () => void;
  token: string;
}) {
  const { showToast } = useToast();
  const [rejectReason, setRejectReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [viewingReceipt, setViewingReceipt] = useState(false);
  // Only a row click ('review') can step into the reject reason from inside the modal; the
  // row's own Approve/Reject icons still open straight into their single decision as before.
  const [activeMode, setActiveMode] = useState<ReviewModalMode>(mode);

  const overlayRef = useRef<HTMLDivElement>(null);

  // Escape closes only when nothing is stacked on top — the receipt / attachment / changes
  // viewers render as nested fixed overlays inside this one and have no Escape handling of
  // their own, so without this check Escape there would close the whole review.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key !== 'Escape' || submitting) return;
      if (overlayRef.current?.querySelector('div[style*="position: fixed"]')) return;
      onClose();
    }
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose, submitting]);

  async function handleApprove() {
    setSubmitting(true);
    try {
      await approveItem(item, token);
      showToast('success', `Approved ${item.employeeName}'s request`);
      onApproved();
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Approve failed');
      setSubmitting(false);
    }
  }

  async function handleReject() {
    if (!rejectReason.trim()) return;
    setSubmitting(true);
    try {
      await rejectItem(item, rejectReason.trim(), token);
      showToast('success', `Rejected ${item.employeeName}'s request`);
      onRejected();
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Reject failed');
      setSubmitting(false);
    }
  }

  const approveButton = (
    <button className="apv-btn" onClick={handleApprove} disabled={submitting} style={{ background: submitting ? 'var(--raised2)' : 'rgba(47,182,124,.15)', border: '1px solid rgba(47,182,124,.3)', color: '#2FB67C' }}>
      <Check size={14} /> {submitting ? 'Approving…' : 'Approve'}
    </button>
  );

  return (
    <div ref={overlayRef} className="apv-overlay" role="dialog" aria-modal="true" aria-label={`${TYPE_LABELS[item.requestType]} request from ${item.employeeName}`}>
      <div className="apv-modal">
        <div className="apv-modal-head">
          <EmployeeAvatar userId={item.employeeUserId} name={item.employeeName} size={38} fontSize={13} />
          <div style={{ minWidth: 0, flex: 1 }}>
            <div style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {item.employeeName}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 4, flexWrap: 'wrap' }}>
              <TypeBadge type={item.requestType} />
              <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>Submitted {fmtDate(item.createdAt)}</span>
            </div>
          </div>
          <button onClick={onClose} aria-label="Close" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignSelf: 'flex-start' }}><X size={16} /></button>
        </div>

        <div className="apv-modal-body">
          {item.requestType === 'LEAVE' && (
            <div className="apv-detail-grid">
              <Row label="Type" value={item.leaveTypeName} />
              <Row label="Days" value={String(item.leaveTotalDays)} />
              <Row label="Dates" value={`${item.leaveStartDate}${item.leaveStartDate !== item.leaveEndDate ? ` → ${item.leaveEndDate}` : ''}${item.leaveHalfDay ? ' (half day)' : ''}`} span />
              <Row label="Reason" value={item.leaveReason} span />
            </div>
          )}

          {item.requestType === 'REGULARIZATION' && (
            <div className="apv-detail-grid">
              <Row label="Attendance Date" value={item.attendanceDate} span />
              <Row label="Requested Check-in" value={item.requestedCheckIn ? fmtTime(item.requestedCheckIn) : 'Not provided'} />
              <Row label="Requested Check-out" value={item.requestedCheckOut ? fmtTime(item.requestedCheckOut) : 'Not provided'} />
              <Row label="Reason" value={item.regularizationReason} span />
            </div>
          )}

          {item.requestType === 'EXPENSE' && (
            <div className="apv-detail-grid">
              <Row label="Stage" value={item.approvalStage === 'FINAL' ? 'Pending Final Approval (HR/Admin)' : 'Pending Manager Review'} span />
              <Row label="Category" value={item.expenseCategoryName} />
              <Row label="Amount" value={fmtCurrency(item.expenseAmount ?? 0)} />
              <Row label="Expense Date" value={fmtDate(item.expenseDate)} />
              <div>
                <div style={labelStyle}>Receipt</div>
                <button
                  onClick={() => setViewingReceipt(true)}
                  style={{ background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 12px', fontSize: 12.5, color: 'var(--brand)', cursor: 'pointer' }}
                >
                  View Receipt
                </button>
              </div>
              <Row label="Business Purpose" value={item.businessPurpose} span />
            </div>
          )}

          {item.requestType === 'ASSET_REQUEST' && (
            <div className="apv-detail-grid">
              <Row label="Category Requested" value={item.requestedCategoryName} span />
              <Row label="Reason" value={item.assetRequestReason} span />
            </div>
          )}

          {(item.requestType === 'WFH' || item.requestType === 'PARTIAL_DAY') && (
            <div className="apv-detail-grid">
              <Row label="Date" value={item.attendanceDate} />
              {/* partialDayHours is stored as a decimal (e.g. 3.33 for 3h 20m) — round-trip
                  through minutes so the modal shows a precise "3h 20m" instead of that raw
                  fraction, matching the duration format used everywhere else in the app. */}
              {item.requestType === 'PARTIAL_DAY' && <Row label="Duration" value={item.partialDayHours != null ? (formatDurationMinutes(Math.round(item.partialDayHours * 60)) ?? undefined) : undefined} />}
              <Row label="Reason" value={item.regularizationReason} span />
            </div>
          )}

          {item.requestType === 'OVERTIME' && (
            <div className="apv-detail-grid">
              <Row label="Date" value={item.attendanceDate} />
              <Row label="Overtime Hours" value={fmtOvertimeHours(item.requestedCheckIn, item.requestedCheckOut)} />
              <Row label="Reason" value={item.regularizationReason} span />
            </div>
          )}

          {item.requestType === 'HELP_CONTENT' && <HelpContentReviewSection item={item} token={token} />}

          {activeMode === 'reject' && (
            <div style={{ marginTop: 18 }}>
              <label style={labelStyle}>Rejection Reason *</label>
              <textarea
                style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }}
                value={rejectReason}
                onChange={e => setRejectReason(e.target.value)}
                placeholder="Why is this request being rejected?"
                autoFocus
              />
            </div>
          )}
        </div>

        <div className="apv-modal-foot">
          {activeMode === 'reject' ? (
            <>
              {mode === 'review' && (
                <button className="apv-btn apv-btn-ghost" onClick={() => setActiveMode('review')} disabled={submitting}>Back</button>
              )}
              <button className="apv-btn" onClick={handleReject} disabled={!rejectReason.trim() || submitting} style={{ background: rejectReason.trim() ? '#C0392B' : 'var(--raised2)', color: rejectReason.trim() ? '#fff' : 'var(--txt-dim)', border: 'none' }}>
                {submitting ? 'Rejecting…' : 'Confirm Reject'}
              </button>
            </>
          ) : activeMode === 'review' ? (
            <>
              <button className="apv-btn" onClick={() => setActiveMode('reject')} disabled={submitting} style={{ background: 'rgba(228,55,61,.12)', border: '1px solid rgba(228,55,61,.28)', color: '#E4373D' }}>
                <X size={14} /> Reject
              </button>
              {approveButton}
            </>
          ) : (
            approveButton
          )}
        </div>
      </div>
      {viewingReceipt && item.requestType === 'EXPENSE' && (
        <ReceiptViewerModal claimId={item.id} token={token} onClose={() => setViewingReceipt(false)} />
      )}
    </div>
  );
}

/**
 * Confirmation for a bulk approve/reject — deliberately just the warning (+ a shared rejection
 * reason for reject) and Confirm/Cancel, no per-item detail, since the point of "select several
 * and approve/reject them" is to skip reviewing each one individually. Runs every item's
 * approve/reject concurrently via Promise.allSettled rather than aborting the whole batch on
 * the first failure — one flaky request shouldn't force re-selecting and re-submitting the
 * rest that would have succeeded.
 */
function BulkActionModal({ items, mode, onClose, onDone, token }: {
  items: ApprovalItem[];
  mode: ReviewMode;
  onClose: () => void;
  onDone: (succeeded: ApprovalItem[]) => void;
  token: string;
}) {
  const { showToast } = useToast();
  const [rejectReason, setRejectReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const verb = mode === 'approve' ? 'approve' : 'reject';

  async function handleConfirm() {
    if (mode === 'reject' && !rejectReason.trim()) return;
    setSubmitting(true);
    const results = await Promise.allSettled(
      items.map(item => (mode === 'approve' ? approveItem(item, token) : rejectItem(item, rejectReason.trim(), token)))
    );
    const succeeded = items.filter((_, i) => results[i].status === 'fulfilled');
    const failedCount = items.length - succeeded.length;

    if (succeeded.length > 0) {
      const verbed = mode === 'approve' ? 'Approved' : 'Rejected';
      showToast('success', `${verbed} ${succeeded.length} request${succeeded.length === 1 ? '' : 's'}${failedCount > 0 ? ` — ${failedCount} failed` : ''}`);
    }
    if (failedCount > 0 && succeeded.length === 0) {
      showToast('error', `Failed to ${verb} the selected requests`);
    }

    setSubmitting(false);
    if (succeeded.length > 0) {
      onDone(succeeded);
      onClose();
    }
  }

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 440 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
          <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>
            {mode === 'approve' ? 'Approve Selected Requests' : 'Reject Selected Requests'}
          </span>
          <button onClick={onClose} disabled={submitting} style={{ background: 'none', border: 'none', cursor: submitting ? 'not-allowed' : 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex' }}><X size={16} /></button>
        </div>

        <div style={{ padding: 20 }}>
          <p style={{ fontSize: 13.5, color: 'var(--txt)', margin: 0, marginBottom: mode === 'reject' ? 16 : 20, lineHeight: 1.5 }}>
            Are you sure you want to {verb} all {items.length} selected request{items.length === 1 ? '' : 's'}?
          </p>

          {mode === 'reject' && (
            <>
              <label style={labelStyle}>Rejection Reason *</label>
              <textarea
                style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit', marginBottom: 16 }}
                value={rejectReason}
                onChange={e => setRejectReason(e.target.value)}
                placeholder="This reason will be sent to every selected request being rejected."
                autoFocus
              />
            </>
          )}

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            <button
              onClick={onClose}
              disabled={submitting}
              style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: submitting ? 'not-allowed' : 'pointer' }}
            >
              Cancel
            </button>
            {mode === 'approve' ? (
              <button
                onClick={handleConfirm}
                disabled={submitting}
                style={{ display: 'flex', alignItems: 'center', gap: 5, background: submitting ? 'var(--raised2)' : 'rgba(47,182,124,.15)', border: '1px solid rgba(47,182,124,.3)', borderRadius: 7, padding: '9px 18px', fontSize: 13, color: '#2FB67C', cursor: submitting ? 'not-allowed' : 'pointer', fontWeight: 600 }}
              >
                <Check size={13} /> {submitting ? 'Approving…' : 'Confirm'}
              </button>
            ) : (
              <button
                onClick={handleConfirm}
                disabled={!rejectReason.trim() || submitting}
                style={{ background: rejectReason.trim() ? '#C0392B' : 'var(--raised2)', color: rejectReason.trim() ? '#fff' : 'var(--txt-dim)', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: !rejectReason.trim() || submitting ? 'not-allowed' : 'pointer' }}
              >
                {submitting ? 'Rejecting…' : 'Confirm'}
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function Row({ label, value, span }: { label: string; value?: string | null; span?: boolean }) {
  return (
    <div className={span ? 'apv-span' : undefined}>
      <div style={labelStyle}>{label}</div>
      <div style={{ fontSize: 13, color: 'var(--txt)' }}>{value ?? EMPTY_VALUE}</div>
    </div>
  );
}

const ALL_TYPES: RequestType[] = ['LEAVE', 'REGULARIZATION', 'EXPENSE', 'ASSET_REQUEST', 'WFH', 'PARTIAL_DAY', 'OVERTIME', 'HELP_CONTENT'];

export default function ApprovalsPage() {
  const token = useAuthStore(s => s.token)!;
  const [searchParams] = useSearchParams();
  const [items, setItems] = useState<ApprovalItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [typeFilter, setTypeFilter] = useState<RequestType | 'ALL'>(() => {
    const t = searchParams.get('type');
    return (ALL_TYPES as string[]).includes(t ?? '') ? (t as RequestType) : 'ALL';
  });
  const [employeeSearch, setEmployeeSearch] = useState('');
  const [reviewing, setReviewing] = useState<{ item: ApprovalItem; mode: ReviewModalMode } | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkAction, setBulkAction] = useState<ReviewMode | null>(null);

  // Background refresh of the pending queue — used after an approve/reject action to correct
  // `items` (and therefore the badge counts and visible list) against the true server state.
  // Deliberately silent: no loading spinner and no toast on failure, since this runs after the
  // optimistic local removal already gave the user feedback and a transient refetch failure
  // shouldn't interrupt them — the queue simply stays as optimistically updated until the next
  // successful reload (matches AssetsExpensesPage's reload()/PoliciesPage's
  // refreshPendingAckTotal() pattern of a silent best-effort refetch).
  function reloadQueue() {
    approvalCenterApi.listPending(token).then(setItems).catch(() => {});
  }

  useEffect(() => {
    approvalCenterApi.listPending(token)
      .then(setItems)
      .finally(() => setLoading(false));
  }, [token]);

  // Search narrows the whole queue by employee name first — category chips (and their counts)
  // then further narrow within whatever the search already matched, so typing a name and
  // switching categories compose instead of one silently overriding the other.
  const searchTerm = employeeSearch.trim().toLowerCase();
  const searched = searchTerm ? items.filter(i => i.employeeName.toLowerCase().includes(searchTerm)) : items;
  const filtered = typeFilter === 'ALL' ? searched : searched.filter(i => i.requestType === typeFilter);

  // Badge counts are totals over the full pending queue (`items`), not the search-narrowed
  // `searched`/`filtered` subsets — typing in the employee search box should narrow the visible
  // list below without making the "ALL"/per-type badge numbers shift.
  const counts: Record<string, number> = { ALL: items.length };
  ALL_TYPES.forEach(t => { counts[t] = items.filter(i => i.requestType === t).length; });

  // "Select all" only ever governs the currently-filtered/searched rows, not the whole queue —
  // selecting under one category filter and switching to another leaves that selection intact
  // (and out of sight) rather than silently dropping it.
  const visibleKeys = filtered.map(rowKey);
  const allVisibleSelected = visibleKeys.length > 0 && visibleKeys.every(k => selected.has(k));
  const someVisibleSelected = visibleKeys.some(k => selected.has(k));
  const selectedItems = items.filter(i => selected.has(rowKey(i)));

  // Removes the acted-on item immediately for a snappy UI, then kicks off a silent background
  // refetch of the full queue (reloadQueue) so `items` — and therefore the badge counts and
  // visible list — self-corrects if this optimistic removal turns out to be incomplete, or if
  // another approver acted on the same queue concurrently in a different session.
  function removeFromQueue(id: string, type: RequestType) {
    const key = `${type}:${id}`;
    setItems(prev => prev.filter(i => !(i.id === id && i.requestType === type)));
    setSelected(prev => { if (!prev.has(key)) return prev; const next = new Set(prev); next.delete(key); return next; });
    reloadQueue();
  }

  function toggleSelected(key: string) {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  }

  function toggleSelectAllVisible() {
    setSelected(prev => {
      const next = new Set(prev);
      if (allVisibleSelected) visibleKeys.forEach(k => next.delete(k));
      else visibleKeys.forEach(k => next.add(k));
      return next;
    });
  }

  function handleBulkDone(succeeded: ApprovalItem[]) {
    const succeededKeys = new Set(succeeded.map(rowKey));
    setItems(prev => prev.filter(i => !succeededKeys.has(rowKey(i))));
    setSelected(prev => {
      const next = new Set(prev);
      succeededKeys.forEach(k => next.delete(k));
      return next;
    });
    reloadQueue();
  }

  return (
    <div>
      <div className="apv-header">
        <div className="apv-header-icon" aria-hidden="true"><ClipboardCheck size={22} /></div>
        <div>
          <h1 className="apv-title">Approval Center</h1>
          <p className="apv-subtitle">All pending requests requiring your decision.</p>
        </div>
      </div>

      {/* Search + request-type tabs in one card. Tabs scroll horizontally on narrow viewports
         rather than wrapping into a tall block, so the table stays close to the filters. */}
      <div className="apv-toolbar">
        <div className="apv-toolbar-top">
          <div className="apv-search">
            <Search size={14} style={{ color: 'var(--txt-dim)', flexShrink: 0 }} aria-hidden="true" />
            <input
              type="text"
              value={employeeSearch}
              onChange={e => setEmployeeSearch(e.target.value)}
              placeholder="Search employee…"
              aria-label="Search by employee name"
            />
          </div>
          {!loading && (
            <span className="apv-result-count" aria-live="polite">
              {filtered.length === items.length
                ? `${items.length} pending request${items.length === 1 ? '' : 's'}`
                : `Showing ${filtered.length} of ${items.length} pending`}
            </span>
          )}
        </div>
        <div className="apv-tabs" role="group" aria-label="Filter by request type">
          {(['ALL', ...ALL_TYPES] as const).map(t => (
            <button
              key={t}
              type="button"
              className="apv-tab"
              aria-pressed={typeFilter === t}
              onClick={() => setTypeFilter(t)}
            >
              {t === 'ALL' ? 'All' : TYPE_LABELS[t]} {counts[t] > 0 && <span className="apv-tab-count">{counts[t]}</span>}
            </button>
          ))}
        </div>
      </div>

      {/* Bulk action bar — appears only once at least one row is selected, right above the
         table it acts on. Wraps to two lines (count above, buttons below) on narrow viewports
         instead of overflowing. */}
      {selectedItems.length > 0 && (
        <div className="apv-bulk">
          <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>
            {selectedItems.length} selected
          </span>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <button
              onClick={() => setBulkAction('approve')}
              style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'rgba(47,182,124,.15)', border: '1px solid rgba(47,182,124,.3)', borderRadius: 7, padding: '7px 14px', fontSize: 12.5, color: '#2FB67C', cursor: 'pointer', fontWeight: 600 }}
            >
              <Check size={13} /> Approve Selected
            </button>
            <button
              onClick={() => setBulkAction('reject')}
              style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'rgba(228,55,61,.12)', border: '1px solid rgba(228,55,61,.28)', borderRadius: 7, padding: '7px 14px', fontSize: 12.5, color: '#E4373D', cursor: 'pointer', fontWeight: 600 }}
            >
              <X size={13} /> Reject Selected
            </button>
            <button
              onClick={() => setSelected(new Set())}
              style={{ background: 'none', border: 'none', color: 'var(--txt-mut)', fontSize: 12.5, cursor: 'pointer', padding: '7px 6px' }}
            >
              Clear
            </button>
          </div>
        </div>
      )}

      <div className="apv-card">
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : filtered.length === 0 ? (
          <div className="apv-empty">
            <div className="apv-empty-icon" aria-hidden="true"><CheckCircle2 size={24} /></div>
            <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--txt)', marginBottom: 6 }}>Nothing pending</div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>
              {/* TYPE_LABELS stays abbreviated ("Attendance Reg.") for the tab pills/badges, where
                  space is tight — this empty-state message has room to spell it out in full. */}
              {typeFilter === 'ALL' && !searchTerm && 'No pending requests right now.'}
              {typeFilter !== 'ALL' && !searchTerm && `No pending ${EMPTY_STATE_TYPE_LABELS[typeFilter]} requests.`}
              {typeFilter === 'ALL' && searchTerm && `No pending requests match "${employeeSearch.trim()}".`}
              {typeFilter !== 'ALL' && searchTerm && `No pending ${EMPTY_STATE_TYPE_LABELS[typeFilter]} requests match "${employeeSearch.trim()}".`}
            </div>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table className="apv-table">
              <thead>
                <tr>
                  <th className="apv-check">
                    <input
                      type="checkbox"
                      checked={allVisibleSelected}
                      ref={el => { if (el) el.indeterminate = someVisibleSelected && !allVisibleSelected; }}
                      onChange={toggleSelectAllVisible}
                      aria-label={allVisibleSelected ? 'Deselect all requests' : 'Select all requests'}
                    />
                  </th>
                  {['Type', 'Employee', 'Requested Dates', 'Reason', 'Submitted', 'Status', 'Actions'].map(h => <th key={h}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {filtered.map(item => {
                  const requestedDates = getRequestedDates(item);
                  const reason = getReason(item);
                  const key = rowKey(item);
                  const TypeIcon = TYPE_ICONS[item.requestType];
                  const openReview = () => setReviewing({ item, mode: 'review' });

                  return (
                    // The whole row opens the request's details with Approve/Reject in one place —
                    // no separate "View" step. Checkbox and action cells stop propagation so they
                    // keep their own behaviour.
                    <tr
                      key={key}
                      className={`apv-row${selected.has(key) ? ' is-selected' : ''}`}
                      tabIndex={0}
                      aria-label={`Review ${item.employeeName}'s ${TYPE_LABELS[item.requestType]} request`}
                      onClick={openReview}
                      onKeyDown={e => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); openReview(); } }}
                    >
                      <td className="apv-check" onClick={e => e.stopPropagation()}>
                        <input
                          type="checkbox"
                          checked={selected.has(key)}
                          onChange={() => toggleSelected(key)}
                          aria-label={`Select ${item.employeeName}'s ${TYPE_LABELS[item.requestType]} request`}
                        />
                      </td>
                      <td className="apv-cell-type">
                        <div className="apv-type">
                          <span className="apv-type-icon" style={{ background: TYPE_COLORS[item.requestType], color: TYPE_TEXT[item.requestType] }} aria-hidden="true">
                            <TypeIcon size={16} />
                          </span>
                          <span className="apv-type-label">{TYPE_LABELS[item.requestType]}</span>
                        </div>
                      </td>
                      <td className="apv-cell-emp" data-label="Employee">
                        <div className="apv-emp">
                          <EmployeeAvatar userId={item.employeeUserId} name={item.employeeName} size={32} fontSize={11.5} />
                          <span className="apv-emp-name">{item.employeeName}</span>
                        </div>
                      </td>
                      <td className="apv-cell-dates apv-dates" data-label="Requested Dates">{requestedDates}</td>
                      <td className="apv-cell-reason" data-label="Reason">
                        <div className="apv-reason" title={reason}>{reason}</div>
                      </td>
                      <td className="apv-cell-submitted apv-submitted" data-label="Submitted">{fmtDate(item.createdAt)}</td>
                      <td className="apv-cell-status">
                        <span className="apv-status"><Clock size={11} aria-hidden="true" /> Pending</span>
                        {/* Expense claims now surface to HR/SA before Manager approval too (see
                            ApprovalCenterController's expense branch) — this makes that stage
                            visible at a glance in the queue itself, not just inside the detail
                            modal's "Stage" row, so admins can tell a not-yet-manager-approved
                            claim apart from one that's actually ready for their own final call. */}
                        {item.requestType === 'EXPENSE' && item.approvalStage === 'MANAGER' && (
                          <div className="apv-status-sub">Pending Manager Review</div>
                        )}
                      </td>
                      <td className="apv-cell-actions" onClick={e => e.stopPropagation()}>
                        <div className="apv-actions">
                          <ActionIconButton
                            label={`Approve ${item.employeeName}'s ${TYPE_LABELS[item.requestType]} request`}
                            title="Approve request"
                            color="#2FB67C"
                            background="rgba(47,182,124,.12)"
                            border="1px solid rgba(47,182,124,.3)"
                            onClick={event => {
                              event.stopPropagation();
                              setReviewing({ item, mode: 'approve' });
                            }}
                          >
                            <Check size={14} />
                          </ActionIconButton>
                          <ActionIconButton
                            label={`Reject ${item.employeeName}'s ${TYPE_LABELS[item.requestType]} request`}
                            title="Reject request"
                            color="#E4373D"
                            background="rgba(228,55,61,.12)"
                            border="1px solid rgba(228,55,61,.28)"
                            onClick={event => {
                              event.stopPropagation();
                              setReviewing({ item, mode: 'reject' });
                            }}
                          >
                            <X size={14} />
                          </ActionIconButton>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {reviewing && (
        <ReviewModal
          item={reviewing.item}
          mode={reviewing.mode}
          token={token}
          onClose={() => setReviewing(null)}
          onApproved={() => removeFromQueue(reviewing.item.id, reviewing.item.requestType)}
          onRejected={() => removeFromQueue(reviewing.item.id, reviewing.item.requestType)}
        />
      )}

      {bulkAction && (
        <BulkActionModal
          items={selectedItems}
          mode={bulkAction}
          token={token}
          onClose={() => setBulkAction(null)}
          onDone={handleBulkDone}
        />
      )}
    </div>
  );
}

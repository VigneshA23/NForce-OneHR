import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Check, X } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { approvalCenterApi, type ApprovalItem, type RequestType } from '../api/approvalCenter';
import { helpContentApprovalApi, type ApprovalDiff } from '../api/helpContentApproval';
import { AttachmentViewerModal } from '../components/helpContent/AttachmentViewerModal';
import { leaveApi } from '../api/leave';
import { regularizationApi } from '../api/attendance';
import { webClockInApi } from '../api/webClockIn';
import { expensesApi } from '../api/expenses';
import { assetsApi } from '../api/assets';
import { attendanceRequestApi } from '../api/attendanceRequests';
import { overtimeRequestApi } from '../api/overtimeRequests';
import { useToast } from '../context/ToastContext';

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 520, boxShadow: '0 24px 64px rgba(0,0,0,.55)', maxHeight: '90vh', overflowY: 'auto' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const tdStyle: React.CSSProperties = { padding: '11px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

const TYPE_LABELS: Record<RequestType, string> = {
  LEAVE: 'Leave',
  REGULARIZATION: 'Attendance Reg.',
  WEB_CLOCK_IN: 'Web Clock-In',
  EXPENSE: 'Expense',
  ASSET_REQUEST: 'Asset Request',
  WFH: 'Work From Home',
  PARTIAL_DAY: 'Partial Day',
  OVERTIME: 'Overtime',
  HELP_CONTENT: 'FAQs & Guides',
};

const TYPE_COLORS: Record<RequestType, string> = {
  LEAVE: 'rgba(99,102,241,.18)',
  REGULARIZATION: 'rgba(245,158,11,.18)',
  WEB_CLOCK_IN: 'rgba(76,141,214,.18)',
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
  WEB_CLOCK_IN: '#4C8DD6',
  EXPENSE: '#10B981',
  ASSET_REQUEST: '#8B5CF6',
  WFH: '#4C8DD6',
  PARTIAL_DAY: '#E0A93B',
  OVERTIME: '#EC4899',
  HELP_CONTENT: '#14B8A6',
};

const EMPTY_VALUE = '—';

type ReviewMode = 'approve' | 'reject';

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

function getRequestedDates(item: ApprovalItem) {
  if (item.requestType === 'LEAVE') {
    if (!item.leaveStartDate) return EMPTY_VALUE;
    return `${item.leaveStartDate}${item.leaveEndDate && item.leaveStartDate !== item.leaveEndDate ? ` → ${item.leaveEndDate}` : ''}${item.leaveHalfDay ? ' (half day)' : ''}`;
  }
  if (item.requestType === 'REGULARIZATION' || item.requestType === 'WEB_CLOCK_IN' || item.requestType === 'WFH' || item.requestType === 'PARTIAL_DAY' || item.requestType === 'OVERTIME') {
    return item.attendanceDate ?? EMPTY_VALUE;
  }
  if (item.requestType === 'EXPENSE') {
    return item.expenseDate ?? EMPTY_VALUE;
  }
  return EMPTY_VALUE;
}

function getReason(item: ApprovalItem) {
  if (item.requestType === 'LEAVE') return item.leaveReason ?? EMPTY_VALUE;
  if (item.requestType === 'EXPENSE') return item.businessPurpose ?? EMPTY_VALUE;
  if (item.requestType === 'ASSET_REQUEST') return item.assetRequestReason ?? EMPTY_VALUE;
  if (
    item.requestType === 'REGULARIZATION' ||
    item.requestType === 'WEB_CLOCK_IN' ||
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
          <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>Changes vs. previous submission</span>
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

function ReceiptLightbox({ src, onClose }: { src: string; onClose: () => void }) {
  return (
    <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.88)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 600, cursor: 'zoom-out' }}>
      <img src={src} alt="Receipt" onClick={e => e.stopPropagation()} style={{ maxWidth: '90vw', maxHeight: '90vh', borderRadius: 8, boxShadow: '0 8px 48px rgba(0,0,0,.8)' }} />
      <button onClick={onClose} style={{ position: 'absolute', top: 16, right: 16, background: 'rgba(255,255,255,.1)', border: 'none', borderRadius: 6, padding: '6px 10px', color: '#fff', cursor: 'pointer', fontSize: 20, lineHeight: 1 }}>✕</button>
    </div>
  );
}

function ReviewModal({ item, mode, onClose, onApproved, onRejected, token }: {
  item: ApprovalItem;
  mode: ReviewMode;
  onClose: () => void;
  onApproved: () => void;
  onRejected: () => void;
  token: string;
}) {
  const { showToast } = useToast();
  const [rejectReason, setRejectReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);

  async function handleApprove() {
    setSubmitting(true);
    try {
      if (item.requestType === 'LEAVE') {
        await leaveApi.approve(item.id, token);
      } else if (item.requestType === 'REGULARIZATION') {
        await regularizationApi.approve(item.id, token);
      } else if (item.requestType === 'WEB_CLOCK_IN') {
        await webClockInApi.approve(item.id, token);
      } else if (item.requestType === 'EXPENSE') {
        if (item.approvalStage === 'MANAGER') {
          await expensesApi.managerApprove(item.id, token);
        } else {
          await expensesApi.finalApprove(item.id, token);
        }
      } else if (item.requestType === 'ASSET_REQUEST') {
        await assetsApi.approveRequest(Number(item.id), token);
      } else if (item.requestType === 'WFH' || item.requestType === 'PARTIAL_DAY') {
        await attendanceRequestApi.approve(item.id, token);
      } else if (item.requestType === 'OVERTIME') {
        await overtimeRequestApi.approve(item.id, token);
      } else if (item.requestType === 'HELP_CONTENT') {
        await helpContentApprovalApi.approve(item.id, token);
      }
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
      if (item.requestType === 'LEAVE') {
        await leaveApi.reject(item.id, rejectReason.trim(), token);
      } else if (item.requestType === 'REGULARIZATION') {
        await regularizationApi.reject(item.id, rejectReason.trim(), token);
      } else if (item.requestType === 'WEB_CLOCK_IN') {
        await webClockInApi.reject(item.id, rejectReason.trim(), token);
      } else if (item.requestType === 'EXPENSE') {
        if (item.approvalStage === 'MANAGER') {
          await expensesApi.managerReject(item.id, rejectReason.trim(), token);
        } else {
          await expensesApi.finalReject(item.id, rejectReason.trim(), token);
        }
      } else if (item.requestType === 'ASSET_REQUEST') {
        await assetsApi.rejectRequest(Number(item.id), rejectReason.trim(), token);
      } else if (item.requestType === 'WFH' || item.requestType === 'PARTIAL_DAY') {
        await attendanceRequestApi.reject(item.id, rejectReason.trim(), token);
      } else if (item.requestType === 'OVERTIME') {
        await overtimeRequestApi.reject(item.id, rejectReason.trim(), token);
      } else if (item.requestType === 'HELP_CONTENT') {
        await helpContentApprovalApi.reject(item.id, rejectReason.trim(), token);
      }
      showToast('success', `Rejected ${item.employeeName}'s request`);
      onRejected();
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Reject failed');
      setSubmitting(false);
    }
  }

  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <TypeBadge type={item.requestType} />
            <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>
              {item.employeeName}
            </span>
          </div>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex' }}><X size={16} /></button>
        </div>

        <div style={{ padding: 20 }}>
          {item.requestType === 'LEAVE' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 16 }}>
              <Row label="Type" value={item.leaveTypeName} />
              <Row label="Dates" value={`${item.leaveStartDate}${item.leaveStartDate !== item.leaveEndDate ? ` → ${item.leaveEndDate}` : ''}${item.leaveHalfDay ? ' (half day)' : ''}`} />
              <Row label="Days" value={String(item.leaveTotalDays)} />
              <Row label="Reason" value={item.leaveReason} />
            </div>
          )}

          {item.requestType === 'REGULARIZATION' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 16 }}>
              <Row label="Attendance Date" value={item.attendanceDate} />
              <Row label="Requested Check-in" value={item.requestedCheckIn ? fmtTime(item.requestedCheckIn) : 'Not provided'} />
              <Row label="Requested Check-out" value={item.requestedCheckOut ? fmtTime(item.requestedCheckOut) : 'Not provided'} />
              <Row label="Reason" value={item.regularizationReason} />
            </div>
          )}

          {item.requestType === 'WEB_CLOCK_IN' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 16 }}>
              <Row label="Work Date" value={item.attendanceDate} />
              <Row label="Requested Check-in" value={item.requestedCheckIn ? fmtTime(item.requestedCheckIn) : 'Not provided'} />
              <Row label="Reason" value={item.regularizationReason} />
            </div>
          )}

          {item.requestType === 'EXPENSE' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 16 }}>
              <Row label="Stage" value={item.approvalStage === 'FINAL' ? 'Final clearance (HR/Admin)' : 'Manager approval'} />
              <Row label="Category" value={item.expenseCategoryName} />
              <Row label="Amount" value={fmtCurrency(item.expenseAmount ?? 0)} />
              <Row label="Expense Date" value={fmtDate(item.expenseDate)} />
              <Row label="Business Purpose" value={item.businessPurpose} />
              {item.receiptUrl && (
                <div>
                  <div style={labelStyle}>Receipt</div>
                  {item.receiptUrl.startsWith('data:image') ? (
                    <img
                      src={item.receiptUrl}
                      alt="Receipt"
                      onClick={() => setLightboxSrc(item.receiptUrl!)}
                      style={{ maxWidth: '100%', maxHeight: 200, borderRadius: 6, border: '1px solid var(--line)', cursor: 'zoom-in', display: 'block' }}
                    />
                  ) : (
                    <a href={item.receiptUrl} target="_blank" rel="noopener noreferrer" style={{ color: 'var(--brand)', fontSize: 13 }}>View receipt</a>
                  )}
                </div>
              )}
            </div>
          )}

          {item.requestType === 'ASSET_REQUEST' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 16 }}>
              <Row label="Category Requested" value={item.requestedCategoryName} />
              <Row label="Reason" value={item.assetRequestReason} />
            </div>
          )}

          {(item.requestType === 'WFH' || item.requestType === 'PARTIAL_DAY') && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 16 }}>
              <Row label="Date" value={item.attendanceDate} />
              {item.requestType === 'PARTIAL_DAY' && <Row label="Hours" value={item.partialDayHours != null ? String(item.partialDayHours) : undefined} />}
              <Row label="Reason" value={item.regularizationReason} />
            </div>
          )}

          {item.requestType === 'OVERTIME' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 16 }}>
              <Row label="Work Date" value={item.attendanceDate} />
              <Row label="Requested Start" value={item.requestedCheckIn ? fmtTime(item.requestedCheckIn) : 'Not provided'} />
              <Row label="Requested End" value={item.requestedCheckOut ? fmtTime(item.requestedCheckOut) : 'Not provided'} />
              <Row label="Reason" value={item.regularizationReason} />
            </div>
          )}

          {item.requestType === 'HELP_CONTENT' && <HelpContentReviewSection item={item} token={token} />}

          {mode === 'reject' ? (
            <>
              <label style={labelStyle}>Rejection Reason *</label>
              <textarea
                style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit', marginBottom: 12 }}
                value={rejectReason}
                onChange={e => setRejectReason(e.target.value)}
                placeholder="Why is this request being rejected?"
                autoFocus
              />
              <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                <button onClick={handleReject} disabled={!rejectReason.trim() || submitting} style={{ background: rejectReason.trim() ? '#C0392B' : 'var(--raised2)', color: rejectReason.trim() ? '#fff' : 'var(--txt-dim)', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: !rejectReason.trim() || submitting ? 'not-allowed' : 'pointer' }}>
                  {submitting ? 'Rejecting…' : 'Confirm Reject'}
                </button>
              </div>
            </>
          ) : (
            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button onClick={handleApprove} disabled={submitting} style={{ display: 'flex', alignItems: 'center', gap: 5, background: submitting ? 'var(--raised2)' : 'rgba(47,182,124,.15)', border: '1px solid rgba(47,182,124,.3)', borderRadius: 7, padding: '9px 18px', fontSize: 13, color: '#2FB67C', cursor: submitting ? 'not-allowed' : 'pointer', fontWeight: 600 }}>
                <Check size={13} /> {submitting ? 'Approving…' : 'Approve'}
              </button>
            </div>
          )}
        </div>
      </div>
      {lightboxSrc && <ReceiptLightbox src={lightboxSrc} onClose={() => setLightboxSrc(null)} />}
    </div>
  );
}

function Row({ label, value }: { label: string; value?: string | null }) {
  return (
    <div>
      <div style={labelStyle}>{label}</div>
      <div style={{ fontSize: 13, color: 'var(--txt)' }}>{value ?? EMPTY_VALUE}</div>
    </div>
  );
}

const ALL_TYPES: RequestType[] = ['LEAVE', 'REGULARIZATION', 'WEB_CLOCK_IN', 'EXPENSE', 'ASSET_REQUEST', 'WFH', 'PARTIAL_DAY', 'OVERTIME', 'HELP_CONTENT'];

export default function ApprovalsPage() {
  const token = useAuthStore(s => s.token)!;
  const [searchParams] = useSearchParams();
  const [items, setItems] = useState<ApprovalItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [typeFilter, setTypeFilter] = useState<RequestType | 'ALL'>(() => {
    const t = searchParams.get('type');
    return (ALL_TYPES as string[]).includes(t ?? '') ? (t as RequestType) : 'ALL';
  });
  const [reviewing, setReviewing] = useState<{ item: ApprovalItem; mode: ReviewMode } | null>(null);

  useEffect(() => {
    approvalCenterApi.listPending(token)
      .then(setItems)
      .finally(() => setLoading(false));
  }, [token]);

  const filtered = typeFilter === 'ALL' ? items : items.filter(i => i.requestType === typeFilter);

  const counts: Record<string, number> = { ALL: items.length };
  ALL_TYPES.forEach(t => { counts[t] = items.filter(i => i.requestType === t).length; });

  function removeFromQueue(id: string, type: RequestType) {
    setItems(prev => prev.filter(i => !(i.id === id && i.requestType === type)));
  }

  return (
    <div>
      <div style={{ marginBottom: 18 }}>
        <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Approval Center</h1>
        <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>All pending requests requiring your decision.</p>
      </div>

      <div style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
        {(['ALL', ...ALL_TYPES] as const).map(t => (
          <button
            key={t}
            onClick={() => setTypeFilter(t)}
            style={{
              padding: '6px 14px',
              borderRadius: 20,
              fontSize: 12,
              fontWeight: 600,
              cursor: 'pointer',
              border: 'none',
              background: typeFilter === t ? 'var(--brand)' : 'var(--raised)',
              color: typeFilter === t ? '#fff' : 'var(--txt-mut)',
            }}
          >
            {t === 'ALL' ? 'All' : TYPE_LABELS[t]} {counts[t] > 0 && <span style={{ marginLeft: 4, background: 'rgba(255,255,255,.2)', borderRadius: 10, padding: '0 5px' }}>{counts[t]}</span>}
          </button>
        ))}
      </div>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : filtered.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>Nothing pending</div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>
              {typeFilter === 'ALL' ? 'No pending requests right now.' : `No pending ${TYPE_LABELS[typeFilter]} requests.`}
            </div>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Type', 'Employee', 'Requested Dates', 'Reason', 'Submitted', 'Actions'].map(h => <th key={h} style={thStyle}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {filtered.map(item => {
                  const requestedDates = getRequestedDates(item);
                  const reason = getReason(item);

                  return (
                    <tr key={`${item.requestType}:${item.id}`}>
                      <td style={tdStyle}><TypeBadge type={item.requestType} /></td>
                      <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{item.employeeName}</td>
                      <td style={{ ...tdStyle, whiteSpace: 'nowrap' }}>{requestedDates}</td>
                      <td style={tdStyle}>
                        <div style={{ maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={reason}>
                          {reason}
                        </div>
                      </td>
                      <td style={{ ...tdStyle, whiteSpace: 'nowrap' }}>{fmtDate(item.createdAt)}</td>
                      <td style={{ ...tdStyle, padding: '8px 12px' }} onClick={e => e.stopPropagation()}>
                        <div style={{ display: 'flex', gap: 6 }}>
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
    </div>
  );
}

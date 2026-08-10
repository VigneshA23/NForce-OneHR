import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Check, X, ChevronDown } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { approvalCenterApi, type ApprovalItem, type RequestType } from '../api/approvalCenter';
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
};

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
  if (!s) return '—';
  return new Date(s).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

function fmtTime(s?: string | null) {
  if (!s) return '—';
  return new Date(s).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' });
}

// ── Type-aware row detail summary ─────────────────────────

function ItemDetail({ item }: { item: ApprovalItem }) {
  if (item.requestType === 'LEAVE') {
    return (
      <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
        {item.leaveTypeName} · {item.leaveStartDate}{item.leaveStartDate !== item.leaveEndDate ? ` → ${item.leaveEndDate}` : ''} · {item.leaveTotalDays} day{item.leaveTotalDays !== 1 ? 's' : ''}{item.leaveHalfDay ? ' (half)' : ''}
      </div>
    );
  }
  if (item.requestType === 'REGULARIZATION') {
    const missing = item.requestedCheckIn && item.requestedCheckOut ? 'Check-in & check-out'
      : item.requestedCheckIn ? 'Check-in' : 'Check-out';
    return (
      <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
        {item.attendanceDate} · Missing: {missing}
      </div>
    );
  }
  if (item.requestType === 'WEB_CLOCK_IN') {
    return (
      <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
        {item.attendanceDate} · Requested {item.requestedCheckIn ? fmtTime(item.requestedCheckIn) : '—'}
      </div>
    );
  }
  if (item.requestType === 'EXPENSE') {
    return (
      <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
        <span style={{ color: 'var(--txt)', fontWeight: 600 }}>{item.expenseCategoryName}</span>
        {' · '}
        <span style={{ color: 'var(--txt)', fontWeight: 600 }}>{fmtCurrency(item.expenseAmount ?? 0)}</span>
        {item.approvalStage === 'FINAL' && (
          <span style={{ marginLeft: 6, fontSize: 10, fontWeight: 700, padding: '1px 6px', borderRadius: 10, background: 'rgba(16,185,129,.15)', color: '#10B981' }}>Final clearance</span>
        )}
      </div>
    );
  }
  if (item.requestType === 'ASSET_REQUEST') {
    return (
      <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
        {item.requestedCategoryName}
      </div>
    );
  }
  if (item.requestType === 'WFH' || item.requestType === 'PARTIAL_DAY') {
    return (
      <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
        {item.attendanceDate}{item.requestType === 'PARTIAL_DAY' && item.partialDayHours != null ? ` · ${item.partialDayHours}h` : ''}
      </div>
    );
  }
  if (item.requestType === 'OVERTIME') {
    return (
      <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
        {item.attendanceDate} · {item.requestedCheckIn ? fmtTime(item.requestedCheckIn) : '—'} → {item.requestedCheckOut ? fmtTime(item.requestedCheckOut) : '—'}
      </div>
    );
  }
  return null;
}

// ── Review modal ──────────────────────────────────────────

function ReceiptLightbox({ src, onClose }: { src: string; onClose: () => void }) {
  return (
    <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.88)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 600, cursor: 'zoom-out' }}>
      <img src={src} alt="Receipt" onClick={e => e.stopPropagation()} style={{ maxWidth: '90vw', maxHeight: '90vh', borderRadius: 8, boxShadow: '0 8px 48px rgba(0,0,0,.8)' }} />
      <button onClick={onClose} style={{ position: 'absolute', top: 16, right: 16, background: 'rgba(255,255,255,.1)', border: 'none', borderRadius: 6, padding: '6px 10px', color: '#fff', cursor: 'pointer', fontSize: 20, lineHeight: 1 }}>✕</button>
    </div>
  );
}

function ReviewModal({ item, onClose, onApproved, onRejected, token }: {
  item: ApprovalItem;
  onClose: () => void;
  onApproved: () => void;
  onRejected: () => void;
  token: string;
}) {
  const { showToast } = useToast();
  const [rejectReason, setRejectReason] = useState('');
  const [mode, setMode] = useState<'view' | 'reject'>('view');
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
          {/* Type-specific details */}
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

          {mode === 'reject' ? (
            <>
              <label style={labelStyle}>Rejection Reason *</label>
              <textarea style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit', marginBottom: 12 }} value={rejectReason} onChange={e => setRejectReason(e.target.value)} placeholder="Why is this request being rejected?" autoFocus />
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button onClick={() => setMode('view')} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: 'pointer' }}>Back</button>
                <button onClick={handleReject} disabled={!rejectReason.trim() || submitting} style={{ background: rejectReason.trim() ? '#C0392B' : 'var(--raised2)', color: rejectReason.trim() ? '#fff' : 'var(--txt-dim)', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: !rejectReason.trim() || submitting ? 'not-allowed' : 'pointer' }}>
                  {submitting ? 'Rejecting…' : 'Confirm Reject'}
                </button>
              </div>
            </>
          ) : (
            <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
              <button onClick={() => setMode('reject')} style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 7, padding: '9px 16px', fontSize: 13, color: '#E4373D', cursor: 'pointer', fontWeight: 600 }}>
                <X size={13} /> Reject
              </button>
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
      <div style={{ fontSize: 13, color: 'var(--txt)' }}>{value ?? '—'}</div>
    </div>
  );
}

// ── Main page ─────────────────────────────────────────────

const ALL_TYPES: RequestType[] = ['LEAVE', 'REGULARIZATION', 'WEB_CLOCK_IN', 'EXPENSE', 'ASSET_REQUEST', 'WFH', 'PARTIAL_DAY', 'OVERTIME'];

export default function ApprovalsPage() {
  const token = useAuthStore(s => s.token)!;
  const [searchParams] = useSearchParams();
  const [items, setItems] = useState<ApprovalItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [typeFilter, setTypeFilter] = useState<RequestType | 'ALL'>(() => {
    const t = searchParams.get('type');
    return (ALL_TYPES as string[]).includes(t ?? '') ? (t as RequestType) : 'ALL';
  });
  const [reviewing, setReviewing] = useState<ApprovalItem | null>(null);

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

      {/* Type filter bar */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
        {(['ALL', ...ALL_TYPES] as const).map(t => (
          <button key={t} onClick={() => setTypeFilter(t)} style={{
            padding: '6px 14px', borderRadius: 20, fontSize: 12, fontWeight: 600,
            cursor: 'pointer', border: 'none',
            background: typeFilter === t ? 'var(--brand)' : 'var(--raised)',
            color: typeFilter === t ? '#fff' : 'var(--txt-mut)',
          }}>
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
                  {['Type', 'Employee', 'Summary', 'Submitted', 'Actions'].map(h => <th key={h} style={thStyle}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {filtered.map(item => (
                  <tr key={`${item.requestType}:${item.id}`} style={{ cursor: 'pointer' }} onClick={() => setReviewing(item)}>
                    <td style={tdStyle}><TypeBadge type={item.requestType} /></td>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{item.employeeName}</td>
                    <td style={tdStyle}><ItemDetail item={item} /></td>
                    <td style={{ ...tdStyle, whiteSpace: 'nowrap' }}>{fmtDate(item.createdAt)}</td>
                    <td style={{ ...tdStyle, padding: '8px 12px' }} onClick={e => e.stopPropagation()}>
                      <div style={{ display: 'flex', gap: 6 }}>
                        <button onClick={() => setReviewing(item)} style={{ display: 'flex', alignItems: 'center', gap: 4, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, padding: '4px 10px', fontSize: 11, color: 'var(--txt)', cursor: 'pointer', fontWeight: 600 }}>
                          Review <ChevronDown size={10} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {reviewing && (
        <ReviewModal
          item={reviewing}
          token={token}
          onClose={() => setReviewing(null)}
          onApproved={() => removeFromQueue(reviewing.id, reviewing.requestType)}
          onRejected={() => removeFromQueue(reviewing.id, reviewing.requestType)}
        />
      )}
    </div>
  );
}

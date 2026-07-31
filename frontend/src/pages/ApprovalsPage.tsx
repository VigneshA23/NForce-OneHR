import { useEffect, useState } from 'react';
import { Check, X } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { leaveApi, type LeaveRequestRecord } from '../api/leave';
import { useToast } from '../context/ToastContext';

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 460, boxShadow: '0 24px 64px rgba(0,0,0,.55)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const tdStyle: React.CSSProperties = { padding: '12px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
      <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>{title}</span>
      <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignItems: 'center' }}><X size={16} /></button>
    </div>
  );
}

function RejectModal({ request, onClose, onDecided, token }: { request: LeaveRequestRecord; onClose: () => void; onDecided: (r: LeaveRequestRecord) => void; token: string }) {
  const { showToast } = useToast();
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function confirm() {
    if (!reason.trim()) return;
    setSubmitting(true); setError(null);
    try {
      const updated = await leaveApi.reject(request.id, reason.trim(), token);
      onDecided(updated);
      showToast('success', `Rejected ${request.employeeName}'s request`);
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Reject failed';
      setError(msg);
      showToast('error', msg);
      setSubmitting(false);
    }
  }

  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <ModalHeader title="Reject Leave Request" onClose={onClose} />
        <div style={{ padding: 24 }}>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 16 }}>
            Rejecting <b style={{ color: 'var(--txt)' }}>{request.employeeName}</b>'s {request.leaveTypeName} request ({request.startDate}{request.startDate !== request.endDate ? ` → ${request.endDate}` : ''}). A reason is required — the employee will see it on their dashboard.
          </p>
          <label style={labelStyle}>Reason *</label>
          <textarea style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit', marginBottom: 12 }} value={reason} onChange={e => setReason(e.target.value)} placeholder="Why is this request being rejected?" />
          {error && <div style={{ color: 'var(--risk)', marginBottom: 12, fontSize: 13 }}>{error}</div>}
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button onClick={confirm} disabled={!reason.trim() || submitting} style={{ background: reason.trim() ? '#C0392B' : 'var(--raised2)', color: reason.trim() ? '#fff' : 'var(--txt-dim)', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: !reason.trim() || submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>
              {submitting ? 'Rejecting…' : 'Reject Request'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

export default function ApprovalsPage() {
  const token = useAuthStore(s => s.token)!;
  const [requests, setRequests] = useState<LeaveRequestRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [approvingId, setApprovingId] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState<LeaveRequestRecord | null>(null);
  const { showToast } = useToast();

  useEffect(() => {
    leaveApi.listApprovals(token).then(setRequests).finally(() => setLoading(false));
  }, [token]);

  function removeFromQueue(id: string) {
    setRequests(prev => prev.filter(r => r.id !== id));
  }

  async function handleApprove(r: LeaveRequestRecord) {
    setApprovingId(r.id);
    try {
      await leaveApi.approve(r.id, token);
      removeFromQueue(r.id);
      showToast('success', `Approved ${r.employeeName}'s request`);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Approve failed');
    } finally {
      setApprovingId(null);
    }
  }

  return (
    <div>
      <div style={{ marginBottom: 22 }}>
        <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Approval Center</h1>
        <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>Pending leave requests from your current direct reports.</p>
      </div>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : requests.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>Nothing pending</div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>You have no direct reports with a pending leave request right now.</div>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Employee', 'Type', 'Dates', 'Days', 'Reason', 'Actions'].map(h => <th key={h} style={thStyle}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {requests.map(r => (
                  <tr key={r.id}>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.employeeName}</td>
                    <td style={tdStyle}>{r.leaveTypeName}</td>
                    <td style={tdStyle}>{r.startDate}{r.startDate !== r.endDate ? ` → ${r.endDate}` : ''}{r.halfDay ? ' (half day)' : ''}</td>
                    <td style={tdStyle}>{r.totalDays}</td>
                    <td style={tdStyle}>{r.employeeReason}</td>
                    <td style={{ ...tdStyle, padding: '10px 12px' }}>
                      <div style={{ display: 'flex', gap: 6, flexWrap: 'nowrap' }}>
                        <button onClick={() => handleApprove(r)} disabled={approvingId === r.id} title="Approve"
                          style={{ display: 'flex', alignItems: 'center', gap: 4, background: 'rgba(47,182,124,.1)', border: '1px solid rgba(47,182,124,.25)', borderRadius: 5, padding: '4px 8px', fontSize: 11, color: '#2FB67C', cursor: approvingId === r.id ? 'not-allowed' : 'pointer' }}>
                          <Check size={11} /> Approve
                        </button>
                        <button onClick={() => setRejecting(r)} title="Reject"
                          style={{ display: 'flex', alignItems: 'center', gap: 4, background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 5, padding: '4px 8px', fontSize: 11, color: '#E4373D', cursor: 'pointer' }}>
                          <X size={11} /> Reject
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

      {rejecting && (
        <RejectModal request={rejecting} token={token} onClose={() => setRejecting(null)} onDecided={updated => removeFromQueue(updated.id)} />
      )}
    </div>
  );
}

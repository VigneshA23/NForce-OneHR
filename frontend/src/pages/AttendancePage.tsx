import { useEffect, useState } from 'react';
import { CalendarPlus, X } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import {
  attendanceApi, regularizationApi,
  type AttendanceRecord, type RegularizationRecord, type SubmitRegularizationPayload,
} from '../api/attendance';

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 480, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 20px 60px rgba(0,0,0,.5)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><label style={labelStyle}>{label}</label>{children}</div>;
}

function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
      <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>{title}</span>
      <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignItems: 'center' }}><X size={16} /></button>
    </div>
  );
}

const STATUS_COLOR: Record<string, string> = { PENDING: '#E0A93B', APPROVED: '#2FB67C', REJECTED: '#E4373D' };

function StatusPill({ status }: { status: string }) {
  return (
    <span style={{ fontSize: 11, fontWeight: 600, color: STATUS_COLOR[status] ?? '#9BA1AC', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 4, padding: '2px 7px' }}>
      {status}
    </span>
  );
}

function fmtDateTime(dt: string | null) {
  if (!dt) return '—';
  return dt.replace('T', ' ').slice(0, 16);
}

// ─── Request Regularization Modal ─────────────────────────────────────────────
function RequestModal({ onClose, onCreated, token }: { onClose: () => void; onCreated: (r: RegularizationRecord) => void; token: string }) {
  const { showToast } = useToast();
  const today = new Date().toISOString().slice(0, 10);
  const [attendanceDate, setAttendanceDate] = useState(today);
  const [checkIn, setCheckIn] = useState('');
  const [checkOut, setCheckOut] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!attendanceDate || !reason.trim()) { setError('Date and reason are required.'); return; }
    if (!checkIn && !checkOut) { setError('Provide a corrected check-in or check-out time.'); return; }
    setSubmitting(true); setError(null);
    try {
      const payload: SubmitRegularizationPayload = {
        attendanceDate,
        requestedCheckIn: checkIn || undefined,
        requestedCheckOut: checkOut || undefined,
        reason: reason.trim(),
      };
      const created = await regularizationApi.submit(payload, token);
      onCreated(created);
      showToast('success', 'Regularization request submitted');
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Submission failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <ModalHeader title="Request Regularization" onClose={onClose} />
        <form onSubmit={handleSubmit} style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
          {error && <div style={{ color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          <Field label="Attendance Date *">
            <input type="date" style={inputStyle} value={attendanceDate} max={today} onChange={e => setAttendanceDate(e.target.value)} />
          </Field>
          <Field label="Corrected Check-In">
            <input type="datetime-local" style={inputStyle} value={checkIn} onChange={e => setCheckIn(e.target.value)} />
          </Field>
          <Field label="Corrected Check-Out">
            <input type="datetime-local" style={inputStyle} value={checkOut} onChange={e => setCheckOut(e.target.value)} />
          </Field>
          <Field label="Reason *">
            <textarea
              style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }}
              value={reason}
              onChange={e => setReason(e.target.value)}
              placeholder="e.g. Forgot to punch out after client meeting"
            />
          </Field>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={submitting} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>
              {submitting ? 'Submitting…' : 'Submit Request'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ─── Reject Modal ──────────────────────────────────────────────────────────────
function RejectModal({ request, onClose, onRejected, token }: { request: RegularizationRecord; onClose: () => void; onRejected: (r: RegularizationRecord) => void; token: string }) {
  const { showToast } = useToast();
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleReject() {
    if (!comment.trim()) { setError('A comment is required when rejecting a request.'); return; }
    setSubmitting(true); setError(null);
    try {
      const updated = await regularizationApi.reject(request.id, comment.trim(), token);
      onRejected(updated);
      showToast('success', 'Request rejected');
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Reject failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 420 }}>
        <ModalHeader title={`Reject — ${request.employeeName}`} onClose={onClose} />
        <div style={{ padding: 24 }}>
          {error && <div style={{ color: 'var(--risk)', marginBottom: 14, fontSize: 13 }}>{error}</div>}
          <Field label="Reason for rejection *">
            <textarea
              style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }}
              value={comment}
              onChange={e => setComment(e.target.value)}
              placeholder="Explain why this request is being rejected"
            />
          </Field>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 16 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button onClick={handleReject} disabled={submitting} style={{ background: '#C0392B', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>
              {submitting ? 'Rejecting…' : 'Reject Request'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Main Page ────────────────────────────────────────────────────────────────
const CAN_APPROVE_ROLES = ['MANAGER', 'HR_ADMIN', 'SUPER_ADMIN'];

export default function AttendancePage() {
  const token = useAuthStore(s => s.token)!;
  const role = useAuthStore(s => s.user?.role);
  const canApprove = !!role && CAN_APPROVE_ROLES.includes(role);
  const { showToast } = useToast();

  const [records, setRecords] = useState<AttendanceRecord[]>([]);
  const [myRequests, setMyRequests] = useState<RegularizationRecord[]>([]);
  const [pending, setPending] = useState<RegularizationRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showRequest, setShowRequest] = useState(false);
  const [rejecting, setRejecting] = useState<RegularizationRecord | null>(null);

  function loadAll() {
    const calls: Promise<unknown>[] = [
      attendanceApi.myRecords(token).then(setRecords),
      regularizationApi.mine(token).then(setMyRequests),
    ];
    if (canApprove) calls.push(regularizationApi.pending(token).then(setPending));
    Promise.all(calls).finally(() => setLoading(false));
  }

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { loadAll(); }, [token]);

  async function handleApprove(reqId: string) {
    try {
      await regularizationApi.approve(reqId, token);
      showToast('success', 'Request approved and attendance record updated');
      loadAll();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Approve failed');
    }
  }

  const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
  const tdStyle: React.CSSProperties = { padding: '12px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      {/* Request Regularization button is always rendered here — not gated on any
          attendance data existing, so it's visible from an employee's very first day. */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div>
          <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>My Attendance</h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>View your attendance and request corrections for missed or incorrect punches.</p>
        </div>
        <button onClick={() => setShowRequest(true)} style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}>
          <CalendarPlus size={14} /> Request Regularization
        </button>
      </div>

      {/* My Attendance records */}
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : records.length === 0 ? (
          <div style={{ padding: 40, textAlign: 'center' }}>
            <div style={{ fontSize: 14, color: 'var(--txt-mut)' }}>No attendance records yet.</div>
            <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginTop: 4 }}>Missed a punch? Use "Request Regularization" above.</div>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead><tr>{['Date', 'Check-In', 'Check-Out', 'Status', 'Source'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
              <tbody>
                {records.map(r => (
                  <tr key={r.id}>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.attendanceDate}</td>
                    <td style={tdStyle}>{fmtDateTime(r.checkIn)}</td>
                    <td style={tdStyle}>{fmtDateTime(r.checkOut)}</td>
                    <td style={tdStyle}><StatusPill status={r.status} /></td>
                    <td style={tdStyle}>{r.source === 'REGULARIZATION' ? 'Regularized' : 'System'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* My Regularization Requests */}
      <div>
        <h2 style={{ fontSize: 15, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', marginBottom: 10 }}>My Regularization Requests</h2>
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
          {myRequests.length === 0 ? (
            <div style={{ padding: 32, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>No requests submitted yet.</div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead><tr>{['Date', 'Requested In', 'Requested Out', 'Reason', 'Status', 'Reviewer Note'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
                <tbody>
                  {myRequests.map(r => (
                    <tr key={r.id}>
                      <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.attendanceDate}</td>
                      <td style={tdStyle}>{fmtDateTime(r.requestedCheckIn)}</td>
                      <td style={tdStyle}>{fmtDateTime(r.requestedCheckOut)}</td>
                      <td style={{ ...tdStyle, maxWidth: 220 }}>{r.reason}</td>
                      <td style={tdStyle}><StatusPill status={r.status} /></td>
                      <td style={tdStyle}>{r.reviewComment ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Pending Approvals — Manager / HR Admin / Super Admin only */}
      {canApprove && (
        <div>
          <h2 style={{ fontSize: 15, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', marginBottom: 10 }}>Pending Regularization Approvals</h2>
          <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
            {pending.length === 0 ? (
              <div style={{ padding: 32, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>No pending requests.</div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead><tr>{['Employee', 'Date', 'Requested In', 'Requested Out', 'Reason', 'Actions'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
                  <tbody>
                    {pending.map(r => (
                      <tr key={r.id}>
                        <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>
                          {r.employeeName}
                          <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{r.employeeEmail}</div>
                        </td>
                        <td style={tdStyle}>{r.attendanceDate}</td>
                        <td style={tdStyle}>{fmtDateTime(r.requestedCheckIn)}</td>
                        <td style={tdStyle}>{fmtDateTime(r.requestedCheckOut)}</td>
                        <td style={{ ...tdStyle, maxWidth: 220 }}>{r.reason}</td>
                        <td style={tdStyle}>
                          <div style={{ display: 'flex', gap: 6 }}>
                            <button onClick={() => handleApprove(r.id)} style={{ background: 'rgba(47,182,124,.1)', border: '1px solid rgba(47,182,124,.25)', borderRadius: 5, padding: '5px 10px', fontSize: 12, color: '#2FB67C', cursor: 'pointer' }}>Approve</button>
                            <button onClick={() => setRejecting(r)} style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 5, padding: '5px 10px', fontSize: 12, color: '#E4373D', cursor: 'pointer' }}>Reject</button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}

      {showRequest && (
        <RequestModal token={token} onClose={() => setShowRequest(false)} onCreated={r => setMyRequests(prev => [r, ...prev])} />
      )}
      {rejecting && (
        <RejectModal
          request={rejecting}
          token={token}
          onClose={() => setRejecting(null)}
          onRejected={updated => setPending(prev => prev.filter(r => r.id !== updated.id))}
        />
      )}
    </div>
  );
}

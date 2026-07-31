import { useEffect, useState } from 'react';
import { CalendarPlus, X } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { leaveApi, type LeaveType, type LeaveBalance, type LeaveRequestRecord, type SubmitLeaveRequestPayload } from '../api/leave';
import { useToast } from '../context/ToastContext';

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 520, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };
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

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><label style={labelStyle}>{label}</label>{children}</div>;
}

const STATUS_COLOR: Record<string, string> = { PENDING: '#E0A93B', APPROVED: '#2FB67C', REJECTED: '#E4373D' };

function StatusBadge({ status }: { status: string }) {
  return (
    <span style={{ fontSize: 11, fontWeight: 600, color: STATUS_COLOR[status] ?? '#9BA1AC', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 4, padding: '2px 7px' }}>
      {status}
    </span>
  );
}

function RequestLeaveModal({ types, onClose, onCreated, token }: { types: LeaveType[]; onClose: () => void; onCreated: (r: LeaveRequestRecord) => void; token: string }) {
  const { showToast } = useToast();
  const today = new Date().toISOString().slice(0, 10);
  const [form, setForm] = useState<SubmitLeaveRequestPayload>({ leaveTypeCode: types[0]?.code ?? '', startDate: today, endDate: today, halfDay: false, reason: '' });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!form.reason.trim()) { setError('A reason is required.'); return; }
    setSubmitting(true); setError(null);
    try {
      const created = await leaveApi.submit({ ...form, endDate: form.halfDay ? form.startDate : form.endDate }, token);
      onCreated(created);
      showToast('success', 'Leave request submitted');
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Submit failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <ModalHeader title="Request Leave" onClose={onClose} />
        <form onSubmit={handleSubmit} style={{ padding: 24, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          {error && <div style={{ gridColumn: '1/-1', color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Leave Type *">
              <select style={inputStyle} value={form.leaveTypeCode} onChange={e => setForm(f => ({ ...f, leaveTypeCode: e.target.value }))}>
                {types.map(t => <option key={t.code} value={t.code}>{t.name}</option>)}
              </select>
            </Field>
          </div>
          <Field label="Start Date *">
            <input type="date" style={inputStyle} value={form.startDate} onChange={e => setForm(f => ({ ...f, startDate: e.target.value, endDate: f.halfDay ? e.target.value : f.endDate }))} />
          </Field>
          <Field label="End Date *">
            <input type="date" style={inputStyle} value={form.halfDay ? form.startDate : form.endDate} disabled={form.halfDay} min={form.startDate} onChange={e => setForm(f => ({ ...f, endDate: e.target.value }))} />
          </Field>
          <div style={{ gridColumn: '1/-1', display: 'flex', alignItems: 'center', gap: 8 }}>
            <input id="halfDay" type="checkbox" checked={form.halfDay} onChange={e => setForm(f => ({ ...f, halfDay: e.target.checked, endDate: e.target.checked ? f.startDate : f.endDate }))} />
            <label htmlFor="halfDay" style={{ fontSize: 13, color: 'var(--txt-mut)' }}>Half day</label>
          </div>
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Reason *">
              <textarea style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }} value={form.reason} onChange={e => setForm(f => ({ ...f, reason: e.target.value }))} placeholder="Reason for leave" />
            </Field>
          </div>
          <div style={{ gridColumn: '1/-1', display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={submitting} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>{submitting ? 'Submitting…' : 'Submit Request'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function LeavePage() {
  const token = useAuthStore(s => s.token)!;
  const [types, setTypes] = useState<LeaveType[]>([]);
  const [balances, setBalances] = useState<LeaveBalance[]>([]);
  const [requests, setRequests] = useState<LeaveRequestRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showRequest, setShowRequest] = useState(false);

  useEffect(() => {
    Promise.all([leaveApi.listTypes(token), leaveApi.listBalances(token), leaveApi.listMine(token)])
      .then(([t, b, r]) => { setTypes(t); setBalances(b); setRequests(r); })
      .finally(() => setLoading(false));
  }, [token]);

  function handleCreated(r: LeaveRequestRecord) {
    setRequests(prev => [r, ...prev]);
    setBalances(prev => prev); // balance only changes on approval
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 22 }}>
        <div>
          <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Leave & Holidays</h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>View your balance, request leave, and track approvals.</p>
        </div>
        <button onClick={() => setShowRequest(true)} disabled={types.length === 0} style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: types.length === 0 ? 'not-allowed' : 'pointer', opacity: types.length === 0 ? 0.6 : 1 }}>
          <CalendarPlus size={14} /> Request Leave
        </button>
      </div>

      {!loading && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12, marginBottom: 22 }}>
          {balances.map(b => (
            <div key={b.leaveTypeCode} style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: 16 }}>
              <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 6 }}>{b.leaveTypeName}</div>
              <div style={{ fontSize: 24, fontWeight: 700, color: 'var(--txt)' }}>{b.remainingDays}<span style={{ fontSize: 13, color: 'var(--txt-mut)', fontWeight: 400 }}> / {b.totalDays} days</span></div>
            </div>
          ))}
        </div>
      )}

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : requests.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>No leave requests yet</div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>Click "Request Leave" to submit your first request.</div>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Type', 'Dates', 'Days', 'Status', 'Reason', 'Decision'].map(h => <th key={h} style={thStyle}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {requests.map(r => (
                  <tr key={r.id}>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.leaveTypeName}</td>
                    <td style={tdStyle}>{r.startDate}{r.startDate !== r.endDate ? ` → ${r.endDate}` : ''}{r.halfDay ? ' (half day)' : ''}</td>
                    <td style={tdStyle}>{r.totalDays}</td>
                    <td style={tdStyle}><StatusBadge status={r.status} /></td>
                    <td style={tdStyle}>{r.employeeReason}</td>
                    <td style={tdStyle}>
                      {r.status === 'PENDING' && <span style={{ color: 'var(--txt-dim)' }}>Awaiting decision</span>}
                      {r.status === 'APPROVED' && <span>Approved by <b style={{ color: 'var(--txt)' }}>{r.decidedByName}</b>{r.decidedAt ? ` on ${new Date(r.decidedAt).toLocaleDateString()}` : ''}</span>}
                      {r.status === 'REJECTED' && (
                        <span>Rejected by <b style={{ color: 'var(--txt)' }}>{r.decidedByName}</b>: {r.decisionReason}</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {showRequest && (
        <RequestLeaveModal types={types} token={token} onClose={() => setShowRequest(false)} onCreated={handleCreated} />
      )}
    </div>
  );
}

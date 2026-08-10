import { useState } from 'react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { webClockInApi, type WebClockInRecord } from '../api/webClockIn';

/** Web Clock-In: separate, once-a-day, reason required, needs approval. Shared by DashboardPage and AttendancePage. */
export function WebClockInRequestModal({ onClose, onSubmitted }: { onClose: () => void; onSubmitted: (r: WebClockInRecord) => void }) {
  const token = useAuthStore(s => s.token) ?? '';
  const { showToast } = useToast();
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit() {
    if (!reason.trim()) return;
    setSubmitting(true);
    try {
      const created = await webClockInApi.submit(reason.trim(), token);
      showToast('success', 'Web clock-in request submitted for approval');
      onSubmitted(created);
      onClose();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to submit web clock-in');
      setSubmitting(false);
    }
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 440, boxShadow: '0 20px 60px rgba(0,0,0,.5)' }}>
        <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--line)', fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>
          Web Clock-In
        </div>
        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <p style={{ margin: 0, fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.5 }}>
            Adding comment is made mandatory by your HR Manager.
          </p>
          <div>
            <label style={{ display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' }}>
              Reason *
            </label>
            <textarea
              value={reason}
              onChange={e => setReason(e.target.value)}
              placeholder="e.g. Working from home today due to a family commitment"
              autoFocus
              style={{ width: '100%', minHeight: 80, resize: 'vertical', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none', fontFamily: 'inherit' }}
            />
          </div>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button
              onClick={handleSubmit}
              disabled={!reason.trim() || submitting}
              style={{ background: reason.trim() ? 'var(--brand)' : 'var(--raised2)', color: reason.trim() ? '#fff' : 'var(--txt-dim)', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: !reason.trim() || submitting ? 'not-allowed' : 'pointer' }}
            >
              {submitting ? 'Submitting…' : 'Submit for Approval'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

import { useState } from 'react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { webClockInApi, type WebClockInRecord } from '../api/webClockIn';

/**
 * Web Clock-In Request (Keka reference): a comment explaining the remote check-in, Cancel and
 * Confirm. Self-approved the moment it's submitted — no manager approval step, see
 * WebClockInService.submit. Shared by DashboardPage and AttendancePage's own Web Check-In action.
 */
export function WebClockInRequestModal({ onClose, onSubmitted }: { onClose: () => void; onSubmitted: (r: WebClockInRecord) => void }) {
  const token = useAuthStore(s => s.token) ?? '';
  const { showToast } = useToast();
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit() {
    const trimmed = reason.trim();
    if (!trimmed) {
      showToast('error', 'Please enter a comment for the web clock-in request');
      return;
    }
    setSubmitting(true);
    try {
      const created = await webClockInApi.submit(trimmed, token);
      showToast('success', 'Checked in remotely');
      onSubmitted(created);
      onClose();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to submit check-in');
      setSubmitting(false);
    }
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 480, boxShadow: '0 20px 60px rgba(0,0,0,.5)' }}>
        <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--line)', fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>
          Web Clock-In Request
        </div>
        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 10 }}>
          <p style={{ margin: 0, fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.5 }}>
            Adding a comment is required for a web clock-in request.
          </p>
          <div>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value.slice(0, 1024))}
              rows={4}
              autoFocus
              maxLength={1024}
              style={{ width: '100%', resize: 'vertical', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 10px', fontSize: 13, background: 'var(--raised)', color: 'var(--txt)', fontFamily: 'inherit' }}
            />
            <div style={{ textAlign: 'right', fontSize: 11, color: 'var(--txt-mut)', marginTop: 4 }}>
              {reason.length} / 1024
            </div>
          </div>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button onClick={onClose} disabled={submitting} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: submitting ? 'not-allowed' : 'pointer' }}>Cancel</button>
            <button
              onClick={handleSubmit}
              disabled={submitting || !reason.trim()}
              style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: (submitting || !reason.trim()) ? 'not-allowed' : 'pointer', opacity: (submitting || !reason.trim()) ? 0.7 : 1 }}
            >
              {submitting ? 'Confirming…' : 'Confirm'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

import { useRef, useState } from 'react';
import { Cake } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { birthdayWishesApi } from '../api/birthdayWishes';
import { EmployeeAvatar } from './EmployeeAvatar';

const DEFAULT_MESSAGE = 'Happy Birthday! Wishing you a wonderful year ahead! 🎉';
const MAX_LENGTH = 500;

/**
 * "Send Wishes" composer, opened from a today's-birthday row in BirthdayWidget. Modeled on
 * WebClockInRequestModal's structure (backdrop/panel/textarea/Cancel-Confirm) — the established
 * small-modal convention in this codebase — rather than inventing a new one.
 */
export function BirthdayWishModal({ toUserId, toName, department, designation, onClose, onSent }: {
  toUserId: string;
  toName: string;
  department?: string | null;
  designation?: string | null;
  onClose: () => void;
  onSent?: () => void;
}) {
  const token = useAuthStore(s => s.token) ?? '';
  const { showToast } = useToast();
  const [message, setMessage] = useState(DEFAULT_MESSAGE);
  const [sending, setSending] = useState(false);
  // Same synchronous re-entrancy guard as WebClockInRequestModal — a ref, not just the `disabled`
  // attribute, so a second rapid click can't slip through before React commits the first one.
  const inFlightRef = useRef(false);

  const firstName = toName.trim().split(/\s+/)[0] || toName;

  async function handleSend() {
    if (inFlightRef.current) return;
    const trimmed = message.trim();
    if (!trimmed) return;
    inFlightRef.current = true;
    setSending(true);
    try {
      await birthdayWishesApi.send({ toUserId, message: trimmed }, token);
      showToast('success', `🎉 Birthday wish sent to ${firstName}!`);
      onSent?.();
      onClose();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to send birthday wish');
      inFlightRef.current = false;
      setSending(false);
    }
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 440, boxShadow: '0 20px 60px rgba(0,0,0,.5)' }}>
        <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--line)', display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{
            width: 30, height: 30, borderRadius: '50%', flexShrink: 0,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            background: 'color-mix(in srgb, var(--brand) 14%, var(--panel))', color: 'var(--brand)',
          }}>
            <Cake size={15} aria-hidden="true" />
          </div>
          <div style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>
            Send Birthday Wishes to {firstName}
          </div>
        </div>
        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <EmployeeAvatar userId={toUserId} name={toName} size={36} />
            <div>
              <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{toName}</div>
              {(designation || department) && (
                <div style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>
                  {[designation, department].filter(Boolean).join(' · ')}
                </div>
              )}
            </div>
          </div>
          <div>
            <textarea
              value={message}
              onChange={(e) => setMessage(e.target.value.slice(0, MAX_LENGTH))}
              rows={4}
              autoFocus
              maxLength={MAX_LENGTH}
              placeholder="Write a birthday message…"
              style={{ width: '100%', resize: 'vertical', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 10px', fontSize: 13, background: 'var(--raised)', color: 'var(--txt)', fontFamily: 'inherit', boxSizing: 'border-box' }}
            />
            <div style={{ textAlign: 'right', fontSize: 11, color: 'var(--txt-mut)', marginTop: 4 }}>
              {message.length} / {MAX_LENGTH}
            </div>
          </div>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button onClick={onClose} disabled={sending} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: sending ? 'not-allowed' : 'pointer' }}>Cancel</button>
            <button
              onClick={handleSend}
              disabled={sending || !message.trim()}
              style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: (sending || !message.trim()) ? 'not-allowed' : 'pointer', opacity: (sending || !message.trim()) ? 0.7 : 1 }}
            >
              {sending ? 'Sending…' : 'Send Wish'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

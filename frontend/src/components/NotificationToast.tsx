import { useEffect } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { Bell, X } from 'lucide-react';
import { useAccessibilityPrefs } from '../lib/accessibilityPrefs';
import { notificationsApi } from '../api/notifications';
import { useAuthStore } from '../store/authStore';

const AUTO_DISMISS_MS = 7000;

export interface NotificationToastItem {
  id: number;
  title: string;
  message: string;
  linkPath: string | null;
}

/**
 * A real-time popup for a newly-arrived (non-KUDOS) notification — testers previously had no way
 * to notice a new one without checking the bell badge, then leaving whatever page they were on
 * for the full /notifications page just to read a single message. Mounted once in Shell.tsx, fed
 * by the same notification poll KudosCelebrationToast already uses (see its own doc comment for
 * why there's no push/SSE channel, only the 30s poll).
 *
 * Top-right, same corner as ToastContext's generic success/error toasts and
 * KudosCelebrationToast (top:72/right:1rem) — the rare case of two of these three firing in the
 * same moment stacking/overlapping visually is an existing, accepted tradeoff of that shared
 * corner, not something this component tries to solve on its own.
 */
export function NotificationToast({ items, onDismiss }: {
  items: NotificationToastItem[];
  onDismiss: (id: number) => void;
}) {
  return (
    <div
      aria-live="polite"
      style={{
        position: 'fixed', top: 72, right: '1rem', zIndex: 895,
        display: 'flex', flexDirection: 'column', gap: 10,
        maxWidth: 360, width: 'calc(100% - 2rem)', pointerEvents: 'none',
      }}
    >
      <AnimatePresence>
        {items.map(item => (
          <NotificationToastCard key={item.id} item={item} onDismiss={() => onDismiss(item.id)} />
        ))}
      </AnimatePresence>
    </div>
  );
}

function NotificationToastCard({ item, onDismiss }: { item: NotificationToastItem; onDismiss: () => void }) {
  const { reduceAnimations } = useAccessibilityPrefs();
  const navigate = useNavigate();
  const token = useAuthStore(s => s.token);

  useEffect(() => {
    const t = setTimeout(onDismiss, AUTO_DISMISS_MS);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.id]);

  function open() {
    if (token) notificationsApi.markRead(token, item.id).catch(() => {});
    onDismiss();
    if (item.linkPath) navigate(item.linkPath);
  }

  return (
    <motion.div
      role="status"
      layout
      initial={{ opacity: 0, y: 16, scale: .96 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, y: 16, scale: .96 }}
      transition={{ duration: reduceAnimations ? 0 : 0.28, ease: [0.16, 1, 0.3, 1] }}
      onClick={open}
      style={{
        pointerEvents: 'auto', cursor: item.linkPath ? 'pointer' : 'default',
        display: 'flex', alignItems: 'flex-start', gap: 10, padding: '12px 14px',
        borderRadius: 10, background: 'var(--panel)', border: '1px solid var(--line)',
        boxShadow: '0 8px 28px rgba(0,0,0,.3), 0 0 0 1px var(--line)',
      }}
    >
      <span style={{
        flexShrink: 0, width: 30, height: 30, borderRadius: '50%', marginTop: 1,
        background: 'color-mix(in srgb, var(--brand) 16%, var(--raised2))',
        color: 'var(--brand-bright)', display: 'grid', placeItems: 'center',
      }}>
        <Bell size={14} aria-hidden="true" />
      </span>

      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 2 }}>{item.title}</div>
        <div style={{
          fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.4,
          overflow: 'hidden', textOverflow: 'ellipsis', display: '-webkit-box',
          WebkitLineClamp: 2, WebkitBoxOrient: 'vertical',
        }}>
          {item.message}
        </div>
      </div>

      <button
        onClick={(e) => { e.stopPropagation(); onDismiss(); }}
        aria-label="Dismiss"
        style={{ flexShrink: 0, background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 2, marginTop: 1 }}
      >
        <X size={13} aria-hidden="true" />
      </button>
    </motion.div>
  );
}

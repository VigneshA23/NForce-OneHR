import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell, CheckCircle2 } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { notificationsApi, type NotificationItem } from '../api/notifications';
import { getPriority } from '../lib/notificationPriority';

const MAX_ROWS = 5;

const PRIORITY_DOT: Record<string, string> = { HIGH: 'var(--risk)', MEDIUM: 'var(--warn)', LOW: 'var(--txt-dim)' };

function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'Just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}

/**
 * Org-wide "Recent Notifications" card — same self-contained pattern as BirthdayWidget (reads
 * its own auth token, fetches its own data). Added because testers previously had no way to see
 * a notification without clicking the bell and leaving the page for the full /notifications
 * list — this surfaces the most recent few unread ones right on the dashboard, no click needed.
 * Shown on every role's dashboard, same as BirthdayWidget.
 */
export function RecentNotificationsWidget({ className }: { className?: string }) {
  const token = useAuthStore(s => s.token) ?? '';
  const navigate = useNavigate();
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    notificationsApi.unread(token, 0, MAX_ROWS)
      .then(data => { if (!cancelled) setItems(data.content); })
      .catch(() => { if (!cancelled) setItems([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [token]);

  function open(n: NotificationItem) {
    notificationsApi.markRead(token, n.id).catch(() => {});
    setItems(prev => prev.filter(i => i.id !== n.id));
    if (n.linkPath) navigate(n.linkPath);
  }

  return (
    <div className={className} style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 22px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Bell size={15} style={{ color: 'var(--brand)' }} />
          <span style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
            Recent Notifications
          </span>
        </div>
        <button
          type="button"
          onClick={() => navigate('/notifications')}
          style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: 11.5, fontWeight: 600, color: 'var(--brand)' }}
        >
          View all →
        </button>
      </div>

      {loading ? (
        <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '8px 0' }}>Loading…</div>
      ) : items.length === 0 ? (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 0' }}>
          <CheckCircle2 size={16} style={{ color: 'var(--ok)' }} />
          <span style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>You're all caught up.</span>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column' }}>
          {items.map((n, i) => (
            <button
              key={n.id}
              type="button"
              onClick={() => open(n)}
              style={{
                display: 'flex', alignItems: 'flex-start', gap: 10, width: '100%',
                padding: '9px 0', textAlign: 'left', font: 'inherit',
                background: 'none', border: 'none', cursor: 'pointer',
                borderTop: i === 0 ? 'none' : '1px solid var(--line)',
              }}
            >
              <span style={{
                width: 7, height: 7, borderRadius: '50%', marginTop: 5, flexShrink: 0,
                background: PRIORITY_DOT[getPriority(n)],
              }} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', marginBottom: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {n.title}
                </div>
                <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {n.message}
                </div>
              </div>
              <span style={{ fontSize: 10.5, color: 'var(--txt-dim)', whiteSpace: 'nowrap', flexShrink: 0, marginTop: 1 }}>
                {timeAgo(n.createdAt)}
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

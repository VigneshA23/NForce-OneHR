import { useEffect, useState } from 'react';
import '../pages/dashboardPolish.css';
import { useNavigate } from 'react-router-dom';
import { Bell, CheckCircle2 } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { usePreviewMode } from '../lib/previewMode';
import { previewNotifications } from '../pages/dashboard-ui-preview/dashboardPreviewData';
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
  const isPreview = usePreviewMode();
  const navigate = useNavigate();
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (isPreview) { setItems(previewNotifications()); setLoading(false); return; }
    let cancelled = false;
    notificationsApi.unread(token, 0, MAX_ROWS)
      .then(data => { if (!cancelled) setItems(data.content); })
      .catch(() => { if (!cancelled) setItems([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [token, isPreview]);

  function open(n: NotificationItem) {
    if (!isPreview) notificationsApi.markRead(token, n.id).catch(() => {});
    setItems(prev => prev.filter(i => i.id !== n.id));
    if (n.linkPath) navigate(n.linkPath);
  }

  return (
    <div className={`nf-dash-card${className ? ' ' + className : ''}`} style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 16, padding: '22px 24px' }}>
      <div className="nf-dash-title">
        <span className="nf-dash-ico"><Bell size={18} /></span>
        <h3>Recent Notifications</h3>
        <button type="button" className="nf-dash-link" onClick={() => navigate('/notifications')}>View all →</button>
      </div>

      {loading ? (
        <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '8px 0' }}>Loading…</div>
      ) : items.length === 0 ? (
        <div className="nf-dash-empty">
          <span className="nf-dash-empty-ico" style={{ background: 'color-mix(in srgb, var(--ok) 14%, var(--panel))', color: 'var(--ok)' }}><CheckCircle2 size={18} /></span>
          <div><b>You're all caught up</b><span>No unread notifications right now.</span></div>
        </div>
      ) : (
        <div className="nf-notif-list">
          {items.map(n => (
            <button
              key={n.id}
              type="button"
              onClick={() => open(n)}
              className="nf-notif-row"
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

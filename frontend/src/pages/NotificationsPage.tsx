import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell, CheckCheck, HelpCircle, KeyRound, UserPlus, Users } from 'lucide-react';
import { notificationsApi, type NotificationItem } from '../api/notifications';
import { useAuthStore } from '../store/authStore';

const TYPE_ICON: Record<string, React.ReactNode> = {
  ACCOUNT:  <UserPlus size={14} />,
  SECURITY: <KeyRound size={14} />,
  ORG_UPDATE: <Users size={14} />,
  HELPDESK_TICKET_CREATED: <HelpCircle size={14} />,
  HELPDESK_TICKET_REPLIED: <HelpCircle size={14} />,
  HELPDESK_TICKET_STATUS_CHANGED: <HelpCircle size={14} />,
  HELPDESK_TICKET_ASSIGNED: <HelpCircle size={14} />,
};

function relativeTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}

export default function NotificationsPage() {
  const token    = useAuthStore(s => s.token) ?? '';
  const navigate = useNavigate();

  const [items, setItems]         = useState<NotificationItem[]>([]);
  const [totalPages, setTotal]    = useState(0);
  const [page, setPage]           = useState(0);
  const [loading, setLoading]     = useState(true);
  const [marking, setMarking]     = useState(false);

  const load = useCallback((p: number) => {
    setLoading(true);
    notificationsApi.list(token, p, 20)
      .then(data => { setItems(data.content); setTotal(data.totalPages); setPage(data.number); })
      .finally(() => setLoading(false));
  }, [token]);

  useEffect(() => { load(0); }, [load]);

  async function handleMarkAll() {
    setMarking(true);
    await notificationsApi.markAllRead(token);
    setItems(prev => prev.map(n => ({ ...n, read: true })));
    setMarking(false);
  }

  async function handleClick(n: NotificationItem) {
    if (!n.read) {
      await notificationsApi.markRead(token, n.id);
      setItems(prev => prev.map(x => x.id === n.id ? { ...x, read: true } : x));
    }
    if (n.linkPath) navigate(n.linkPath);
  }

  const unread = items.filter(n => !n.read).length;

  return (
    <div style={{ maxWidth: 700, display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div>
          <h1 style={{ margin: 0, marginBottom: 4, fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>
            Notifications
          </h1>
          <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>
            {unread > 0 ? `${unread} unread` : 'All caught up'}
          </p>
        </div>
        {unread > 0 && (
          <button onClick={handleMarkAll} disabled={marking}
            style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>
            <CheckCheck size={14} aria-hidden />
            {marking ? 'Marking…' : 'Mark all read'}
          </button>
        )}
      </div>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: '40px 24px', textAlign: 'center', color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>
        ) : items.length === 0 ? (
          <div style={{ padding: '52px 24px', textAlign: 'center' }}>
            <Bell size={32} style={{ color: 'var(--line2)', display: 'block', margin: '0 auto 12px' }} aria-hidden />
            <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>No notifications yet.</div>
          </div>
        ) : (
          items.map((n, i) => (
            <button
              key={n.id}
              onClick={() => handleClick(n)}
              style={{
                display: 'flex', alignItems: 'flex-start', gap: 14, width: '100%',
                padding: '14px 20px', background: n.read ? 'transparent' : 'rgba(177,17,22,.05)',
                border: 'none', borderBottom: i < items.length - 1 ? '1px solid var(--line)' : 'none',
                cursor: n.linkPath ? 'pointer' : 'default', textAlign: 'left',
              }}
            >
              <div style={{
                width: 32, height: 32, borderRadius: '50%', flexShrink: 0,
                background: n.type === 'SECURITY' ? 'rgba(239,68,68,.12)' : 'rgba(177,17,22,.12)',
                color: n.type === 'SECURITY' ? '#ef4444' : '#e4373d',
                display: 'grid', placeItems: 'center',
              }}>
                {TYPE_ICON[n.type] ?? <Bell size={14} />}
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 2 }}>
                  <span style={{ fontSize: 13, fontWeight: n.read ? 500 : 700, color: 'var(--txt)' }}>{n.title}</span>
                  {!n.read && <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#e4373d', flexShrink: 0 }} />}
                </div>
                <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginBottom: 3, lineHeight: 1.5 }}>{n.message}</div>
                <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{relativeTime(n.createdAt)}</div>
              </div>
            </button>
          ))
        )}
      </div>

      {totalPages > 1 && (
        <div style={{ display: 'flex', justifyContent: 'center', gap: 8 }}>
          <button disabled={page === 0} onClick={() => load(page - 1)}
            style={{ padding: '6px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12, color: 'var(--txt-mut)', cursor: page === 0 ? 'not-allowed' : 'pointer', opacity: page === 0 ? .5 : 1 }}>
            Previous
          </button>
          <span style={{ padding: '6px 12px', fontSize: 12, color: 'var(--txt-mut)' }}>Page {page + 1} of {totalPages}</span>
          <button disabled={page >= totalPages - 1} onClick={() => load(page + 1)}
            style={{ padding: '6px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12, color: 'var(--txt-mut)', cursor: page >= totalPages - 1 ? 'not-allowed' : 'pointer', opacity: page >= totalPages - 1 ? .5 : 1 }}>
            Next
          </button>
        </div>
      )}
    </div>
  );
}

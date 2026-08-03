import { useRef, useState, useEffect, useCallback } from 'react';
import { Link, useLocation, Outlet, useNavigate } from 'react-router-dom';
import { Search, Bell, Sun, Moon, Shield, User, LogOut, Settings, CheckCheck, KeyRound, UserPlus } from 'lucide-react';
import { NAV, toShellRole, type Role } from '../lib/nav.config';
import { useTheme } from '../lib/theme';
import { useAuthStore } from '../store/authStore';
import { BrandMark } from './BrandMark';
import { notificationsApi, type NotificationItem } from '../api/notifications';
import { ComplianceBanner } from './ComplianceBanner';

function toRoleTagline(role: Role): string {
  switch (role) {
    case 'Super Admin': return 'Super Admin Experience';
    case 'HR Admin':    return 'HR Admin Experience';
    case 'Manager':     return 'Manager Experience';
    default:            return 'Employee Experience';
  }
}

function getInitials(email?: string): string {
  if (email) return email.slice(0, 2).toUpperCase();
  return 'U';
}

function ComingInPhase({ label, phase }: { label: string; phase: number }) {
  return (
    <div
      style={{
        background: 'var(--panel)',
        border: '1px dashed var(--line2)',
        borderRadius: 12,
        padding: 40,
        textAlign: 'center',
        color: 'var(--txt-mut)',
      }}
    >
      <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 17, color: 'var(--txt)', marginBottom: 6 }}>
        {label}
      </div>
      <div style={{ fontSize: 13 }}>
        Ships in Phase {phase}, per the roadmap — this is a placeholder, not a broken link.
      </div>
    </div>
  );
}

function ProfileDropdown({ name, role, initials, onClose }: {
  name: string;
  role: Role;
  initials: string;
  onClose: () => void;
}) {
  const navigate   = useNavigate();
  const clearAuth  = useAuthStore((s) => s.clearAuth);

  function handleSignOut() {
    clearAuth();
    navigate('/login', { replace: true });
  }

  return (
    <div
      style={{
        position: 'absolute', top: 'calc(100% + 6px)', right: 0, width: 220,
        background: '#16181D', border: '1px solid #2A2E37', borderRadius: 10,
        boxShadow: '0 8px 32px rgba(0,0,0,.55)', zIndex: 200, overflow: 'hidden',
      }}
      role="menu"
    >
      <div style={{ padding: '12px 14px', borderBottom: '1px solid #2A2E37' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
          <div style={{ width: 32, height: 32, borderRadius: '50%', background: '#B11116', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 12, fontWeight: 700, flexShrink: 0 }}>
            {initials}
          </div>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 12.5, color: '#E8EAED', fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{name}</div>
            <div style={{ fontSize: 11, color: '#6B7280', marginTop: 1 }}>{role}</div>
          </div>
        </div>
      </div>

      {[
        { icon: User,     label: 'My Profile',         action: () => { onClose(); navigate('/profile'); } },
        { icon: Settings, label: 'Help & Guidance',    action: () => { onClose(); navigate('/help'); } },
      ].map(({ icon: Icon, label, action }) => (
        <button
          key={label}
          role="menuitem"
          onClick={action}
          style={{ width: '100%', display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px',
            background: 'none', border: 'none', cursor: 'pointer', color: '#C8CCD2', fontSize: 13, textAlign: 'left' }}
          onMouseEnter={(e) => { (e.currentTarget as HTMLButtonElement).style.background = '#1E2128'; (e.currentTarget as HTMLButtonElement).style.color = '#fff'; }}
          onMouseLeave={(e) => { (e.currentTarget as HTMLButtonElement).style.background = 'none'; (e.currentTarget as HTMLButtonElement).style.color = '#C8CCD2'; }}
        >
          <Icon size={14} aria-hidden="true" />
          {label}
        </button>
      ))}

      <div style={{ borderTop: '1px solid #2A2E37', marginTop: 2 }}>
        <button
          role="menuitem"
          onClick={handleSignOut}
          style={{ width: '100%', display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px',
            background: 'none', border: 'none', cursor: 'pointer', color: '#E4373D', fontSize: 13, textAlign: 'left' }}
          onMouseEnter={(e) => { (e.currentTarget as HTMLButtonElement).style.background = 'rgba(228,55,61,.08)'; }}
          onMouseLeave={(e) => { (e.currentTarget as HTMLButtonElement).style.background = 'none'; }}
        >
          <LogOut size={14} aria-hidden="true" />
          Sign out
        </button>
      </div>
    </div>
  );
}

function relTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

const NOTIF_ICON: Record<string, React.ReactNode> = {
  ACCOUNT:  <UserPlus size={12} />,
  SECURITY: <KeyRound size={12} />,
};

function NotificationDropdown({ token, onClose }: { token: string; onClose: () => void }) {
  const navigate = useNavigate();
  const [items, setItems]     = useState<NotificationItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [marking, setMarking] = useState(false);

  useEffect(() => {
    notificationsApi.list(token, 0, 8)
      .then(d => setItems(d.content))
      .finally(() => setLoading(false));
  }, [token]);

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
    onClose();
    if (n.linkPath) navigate(n.linkPath);
  }

  const unread = items.filter(n => !n.read).length;

  return (
    <div style={{ position: 'absolute', top: 'calc(100% + 6px)', right: 0, width: 340, background: '#16181D', border: '1px solid #2A2E37', borderRadius: 10, boxShadow: '0 8px 32px rgba(0,0,0,.55)', zIndex: 200, overflow: 'hidden' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '11px 14px', borderBottom: '1px solid #2A2E37' }}>
        <span style={{ fontSize: 12.5, fontWeight: 700, color: '#E8EAED' }}>Notifications {unread > 0 && <span style={{ color: '#e4373d' }}>({unread})</span>}</span>
        <div style={{ display: 'flex', gap: 6 }}>
          {unread > 0 && (
            <button onClick={handleMarkAll} disabled={marking} style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, color: '#9BA1AC', background: 'none', border: 'none', cursor: 'pointer', padding: '2px 6px' }}>
              <CheckCheck size={11} /> {marking ? '…' : 'Mark all read'}
            </button>
          )}
          <button onClick={() => { onClose(); navigate('/notifications'); }} style={{ fontSize: 11, color: '#9BA1AC', background: 'none', border: 'none', cursor: 'pointer', padding: '2px 6px' }}>
            View all
          </button>
        </div>
      </div>
      {loading ? (
        <div style={{ padding: '20px 14px', fontSize: 12, color: '#6B7280', textAlign: 'center' }}>Loading…</div>
      ) : items.length === 0 ? (
        <div style={{ padding: '28px 14px', fontSize: 12, color: '#6B7280', textAlign: 'center' }}>No notifications yet.</div>
      ) : (
        items.map((n, i) => (
          <button key={n.id} onClick={() => handleClick(n)}
            style={{ width: '100%', display: 'flex', alignItems: 'flex-start', gap: 10, padding: '11px 14px', background: n.read ? 'transparent' : 'rgba(177,17,22,.06)', border: 'none', borderBottom: i < items.length - 1 ? '1px solid rgba(42,46,55,.7)' : 'none', cursor: 'pointer', textAlign: 'left' }}>
            <div style={{ width: 26, height: 26, borderRadius: '50%', flexShrink: 0, background: n.type === 'SECURITY' ? 'rgba(239,68,68,.12)' : 'rgba(177,17,22,.12)', color: n.type === 'SECURITY' ? '#ef4444' : '#e4373d', display: 'grid', placeItems: 'center', marginTop: 1 }}>
              {NOTIF_ICON[n.type] ?? <Bell size={12} />}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 1 }}>
                <span style={{ fontSize: 12.5, fontWeight: n.read ? 500 : 700, color: '#E8EAED', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>{n.title}</span>
                {!n.read && <span style={{ width: 5, height: 5, borderRadius: '50%', background: '#e4373d', flexShrink: 0 }} />}
              </div>
              <div style={{ fontSize: 11.5, color: '#9BA1AC', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', marginBottom: 2 }}>{n.message}</div>
              <div style={{ fontSize: 10.5, color: '#6B7280' }}>{relTime(n.createdAt)}</div>
            </div>
          </button>
        ))
      )}
    </div>
  );
}

export function Shell() {
  const storeUser  = useAuthStore((s) => s.user);
  const token      = useAuthStore((s) => s.token) ?? '';
  const { theme, toggleTheme } = useTheme();
  const location   = useLocation();
  const [dropdownOpen, setDropdownOpen]   = useState(false);
  const [bellOpen, setBellOpen]           = useState(false);
  const [unreadCount, setUnreadCount]     = useState(0);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const bellRef     = useRef<HTMLDivElement>(null);

  const role     = toShellRole(storeUser?.role);
  const name     = storeUser?.email || 'User';
  const initials = getInitials(storeUser?.email);

  const navItems = NAV[role];
  const current  = navItems.find((n) => location.pathname.startsWith(n.path)) ?? navItems[0];

  const refreshCount = useCallback(() => {
    if (!token) return;
    notificationsApi.unreadCount(token)
      .then(d => setUnreadCount(d.count))
      .catch(() => {});
  }, [token]);

  useEffect(() => {
    refreshCount();
    const id = setInterval(refreshCount, 30000);
    return () => clearInterval(id);
  }, [refreshCount]);

  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setDropdownOpen(false);
      }
      if (bellRef.current && !bellRef.current.contains(e.target as Node)) {
        setBellOpen(false);
      }
    }
    if (dropdownOpen || bellOpen) {
      document.addEventListener('mousedown', handleClickOutside);
      return () => document.removeEventListener('mousedown', handleClickOutside);
    }
  }, [dropdownOpen, bellOpen]);

  return (
    <div style={{ display: 'flex', minHeight: '100dvh' }}>
      {/* Sidebar */}
      <aside
        className="shell-sidebar"
        style={{
          width: 236, flexShrink: 0, background: '#0B0C0F', borderRight: '1px solid #23262D',
          display: 'flex', flexDirection: 'column', position: 'fixed', top: 0, bottom: 0, left: 0,
        }}
      >
        {/* Logo — height must match topbar exactly so the border forms one continuous line */}
        <div style={{ height: 56, padding: '0 14px', flexShrink: 0, borderBottom: '1px solid #23262D', display: 'flex', alignItems: 'center', gap: 10 }}>
          <BrandMark size="sm" />
          <div>
            <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 13, color: '#E8EAED', letterSpacing: '0.01em' }}>NForce OneHR</div>
            <div style={{ fontSize: 8, color: '#6B7280', letterSpacing: '.12em', textTransform: 'uppercase', marginTop: 1 }}>
              {toRoleTagline(role)}
            </div>
          </div>
        </div>

        {/* Nav items */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '8px 0' }}>
          {navItems.map((item) => {
            const Icon = item.icon;
            const isActive = current.key === item.key;
            const isPlaceholder = item.phase > 1;
            return (
              <Link
                key={item.key}
                to={isPlaceholder ? '#' : item.path}
                aria-current={isActive ? 'page' : undefined}
                aria-disabled={isPlaceholder}
                onClick={(e) => { if (isPlaceholder) e.preventDefault(); }}
                className="nf-sidebar-item"
                style={{
                  display: 'flex', alignItems: 'center', gap: 9, padding: '8px 12px', margin: '1px 8px',
                  borderRadius: 7, textDecoration: 'none', fontSize: 12.5, position: 'relative',
                  cursor: isPlaceholder ? 'default' : 'pointer', opacity: isPlaceholder ? 0.55 : 1,
                }}
              >
                {isActive && (
                  <span aria-hidden="true" style={{ position: 'absolute', left: -8, top: 6, bottom: 6, width: 3, background: '#E4373D', borderRadius: '0 3px 3px 0' }} />
                )}
                <Icon size={15} aria-hidden="true" />
                <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{item.label}</span>
                {isPlaceholder && (
                  <span title={`Ships in Phase ${item.phase}`} style={{ fontSize: 8.5, fontWeight: 700, letterSpacing: '.04em', color: '#6B7280', background: '#20242C', padding: '2px 5px', borderRadius: 4 }}>
                    P{item.phase}
                  </span>
                )}
              </Link>
            );
          })}
        </div>

        {/* Profile card (sidebar) */}
        <div style={{ borderTop: '1px solid #23262D', padding: 10, display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{ width: 30, height: 30, borderRadius: '50%', background: '#B11116', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 11, fontWeight: 700, flexShrink: 0 }}>
            {initials}
          </div>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 12, color: '#E8EAED', fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{name}</div>
            <div style={{ fontSize: 10, color: '#6B7280' }}>{role}</div>
          </div>
        </div>
      </aside>

      {/* Main area */}
      <div style={{ marginLeft: 236, marginTop: 0, flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        {/* Topbar */}
        <header
          style={{
            height: 56,
            background: 'linear-gradient(90deg, #050506 0%, #6B0C10 40%, #A01418 100%)',
            borderBottom: '1px solid rgba(228,55,61,.22)',
            position: 'sticky', top: 0, zIndex: 30,
            display: 'flex', alignItems: 'center', padding: '0 18px', gap: 10,
          }}
        >
          <div style={{ color: '#9BA1AC', fontSize: 13 }}>
            NForce OneHR / <b style={{ color: '#E8EAED', fontWeight: 600 }}>{current.label}</b>
          </div>
          <div style={{ flex: 1 }} />
          <div style={{ maxWidth: 260, width: 260, background: '#1E2128', border: '1px solid #2A2E37', borderRadius: 8, padding: '7px 11px', display: 'flex', alignItems: 'center', gap: 8, color: '#6B7280', fontSize: 12 }}>
            <Search size={13} aria-hidden="true" /> Search this workspace…
          </div>

          <button
            onClick={toggleTheme}
            aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
            className="nf-topbar-item"
            style={{ background: 'transparent', border: 'none', cursor: 'pointer', padding: 7, borderRadius: 6, display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}
          >
            {theme === 'dark' ? <Sun size={15} aria-hidden="true" /> : <Moon size={15} aria-hidden="true" />}
            {theme === 'dark' ? 'Light' : 'Dark'}
          </button>

          <div ref={bellRef} style={{ position: 'relative' }}>
            <button
              aria-label={`Notifications${unreadCount > 0 ? ` (${unreadCount} unread)` : ''}`}
              aria-expanded={bellOpen}
              onClick={() => { setBellOpen(v => !v); if (!bellOpen) refreshCount(); }}
              className="nf-topbar-item"
              style={{ background: 'transparent', border: 'none', cursor: 'pointer', padding: 7, borderRadius: 6, position: 'relative' }}
            >
              <Bell size={15} aria-hidden="true" />
              {unreadCount > 0 && (
                <span style={{ position: 'absolute', top: 3, right: 3, minWidth: 14, height: 14, borderRadius: 7, background: '#e4373d', color: '#fff', fontSize: 9, fontWeight: 700, display: 'grid', placeItems: 'center', padding: '0 3px', lineHeight: 1 }}>
                  {unreadCount > 99 ? '99+' : unreadCount}
                </span>
              )}
            </button>
            {bellOpen && (
              <NotificationDropdown
                token={token}
                onClose={() => { setBellOpen(false); refreshCount(); }}
              />
            )}
          </div>

          {/* Avatar-only button — name/role/sign-out live inside the dropdown */}
          <div ref={dropdownRef} style={{ position: 'relative' }}>
            <button
              aria-label="Open profile menu"
              aria-expanded={dropdownOpen}
              aria-haspopup="menu"
              onClick={() => setDropdownOpen((v) => !v)}
              style={{
                position: 'relative', background: 'transparent', border: 'none',
                cursor: 'pointer', padding: 4, borderRadius: '50%',
              }}
              onMouseEnter={(e) => { (e.currentTarget as HTMLButtonElement).style.background = 'rgba(255,255,255,.09)'; }}
              onMouseLeave={(e) => { (e.currentTarget as HTMLButtonElement).style.background = 'transparent'; }}
            >
              <div style={{ width: 32, height: 32, borderRadius: '50%', background: '#B11116', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 12, fontWeight: 700, border: '2px solid rgba(255,255,255,0.55)', boxSizing: 'border-box' }}>
                {initials}
              </div>
              {role === 'Super Admin' && (
                <span
                  aria-label="Super Admin session"
                  title="Super Admin"
                  style={{
                    position: 'absolute', bottom: 1, right: 1,
                    width: 14, height: 14, borderRadius: '50%',
                    background: '#1C0709', border: '1.5px solid #3D0D15',
                    display: 'grid', placeItems: 'center',
                  }}
                >
                  <Shield size={8} color="#E4373D" aria-hidden="true" />
                </span>
              )}
            </button>

            {dropdownOpen && (
              <ProfileDropdown
                name={name}
                role={role}
                initials={initials}
                onClose={() => setDropdownOpen(false)}
              />
            )}
          </div>
        </header>

        <main style={{ flex: 1, padding: 26, background: 'var(--shell)', color: 'var(--txt)' }}>
          <ComplianceBanner />
          {current.phase > 1 ? <ComingInPhase label={current.label} phase={current.phase} /> : <Outlet />}
        </main>
      </div>
    </div>
  );
}

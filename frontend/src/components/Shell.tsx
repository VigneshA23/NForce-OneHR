import { useRef, useState, useEffect, useCallback } from 'react';
import { useLocation, Outlet, useNavigate, Link } from 'react-router-dom';
import { Search, Bell, Sun, Moon, Shield, User, LogOut, Menu, X as CloseIcon } from 'lucide-react';
import { NAV, toShellRole, type Role } from '../lib/nav.config';
import { useTheme } from '../lib/theme';
import { useAuthStore } from '../store/authStore';
import { BrandMark } from './BrandMark';
import { notificationsApi } from '../api/notifications';
import { authApi } from '../api/auth';
import { API_ORIGIN } from '../api/config';
import { ComplianceBanner } from './ComplianceBanner';
import { SidebarNav } from './SidebarNav';
import { profileApi } from '../api/profile';

function toRoleTagline(role: Role): string {
  switch (role) {
    case 'Super Admin': return 'Super Admin Experience';
    case 'HR Admin':    return 'HR Admin Experience';
    case 'Manager':     return 'Manager Experience';
    default:            return 'Employee Experience';
  }
}

function getInitials(nameOrEmail?: string): string {
  if (!nameOrEmail) return 'U';
  if (nameOrEmail.includes('@')) return nameOrEmail.slice(0, 2).toUpperCase();
  const parts = nameOrEmail.trim().split(/\s+/);
  if (parts.length >= 2) return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  return nameOrEmail.slice(0, 2).toUpperCase();
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

function ProfileDropdown({ name, email, role, initials, onClose }: {
  name: string;
  email: string;
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
      className="nf-dropdown-panel"
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
            <div style={{ fontSize: 11, color: '#9BA1AC', marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{email}</div>
            <div style={{ fontSize: 10, color: '#6B7280', marginTop: 1 }}>{role}</div>
          </div>
        </div>
      </div>

      {[
        { icon: User, label: 'My Profile', action: () => { onClose(); navigate('/profile'); } },
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


export function Shell() {
  const storeUser  = useAuthStore((s) => s.user);
  const token      = useAuthStore((s) => s.token) ?? '';
  const clearAuth  = useAuthStore((s) => s.clearAuth);
  const navigate   = useNavigate();
  const { theme, toggleTheme } = useTheme();
  const location   = useLocation();
  const [dropdownOpen, setDropdownOpen]   = useState(false);
  const [unreadCount, setUnreadCount]     = useState(0);
  // Mobile-only off-canvas nav toggle (≤767px). Defaults closed; the CSS that
  // reads this className only exists inside the ≤767px media query, so this
  // state never affects rendering at tablet/desktop widths.
  const [navOpen, setNavOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const role     = toShellRole(storeUser?.role);
  const email    = storeUser?.email || '';
  const name     = storeUser?.fullName || email || 'User';
  const initials = getInitials(storeUser?.fullName ?? storeUser?.email);

  const navItems = NAV[role];
  const current  = navItems.find((n) => location.pathname.startsWith(n.path)) ?? navItems[0];

  // Heal stale sessions: if token exists but fullName was never populated (session predates
  // the fullName-in-login-response change), fetch it once from /api/profile and patch the store.
  const setAuth = useAuthStore((s) => s.setAuth);
  useEffect(() => {
    if (!token || storeUser?.fullName) return;
    profileApi.get(token)
      .then(p => {
        if (p.fullName && storeUser) {
          setAuth(token, { ...storeUser, fullName: p.fullName });
        }
      })
      .catch(() => {});
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

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

  // Server-initiated logout: a Super Admin changing this user's profile bumps their tokenVersion
  // and pushes a FORCE_LOGOUT event (see UserManagementService#updateUser /
  // ForceLogoutBroadcaster on the backend) so an open tab reacts within roughly a network
  // round-trip instead of waiting for its next API call to 401. Backend still enforces this
  // regardless — token_version is checked on every request — so a missed/dropped push here is
  // a UX delay, not a security gap.
  useEffect(() => {
    if (!token) return;
    let eventSource: EventSource | null = null;
    let cancelled = false;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;

    async function connect() {
      if (cancelled) return;
      try {
        const { ticket } = await authApi.issueEventsTicket(token);
        if (cancelled) return;
        eventSource = new EventSource(`${API_ORIGIN}/api/auth/events?ticket=${encodeURIComponent(ticket)}`);
        eventSource.addEventListener('FORCE_LOGOUT', () => {
          eventSource?.close();
          clearAuth();
          navigate('/login', { replace: true });
        });
        eventSource.onerror = () => {
          // The ticket is single-use — EventSource's built-in auto-retry would reuse a now-dead
          // ticket, so close it ourselves and reconnect with a freshly-issued one instead.
          eventSource?.close();
          if (!cancelled) retryTimer = setTimeout(connect, 3000);
        };
      } catch {
        if (!cancelled) retryTimer = setTimeout(connect, 3000);
      }
    }

    connect();

    return () => {
      cancelled = true;
      if (retryTimer) clearTimeout(retryTimer);
      eventSource?.close();
    };
  }, [token, clearAuth, navigate]);

  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setDropdownOpen(false);
      }
    }
    if (dropdownOpen) {
      document.addEventListener('mousedown', handleClickOutside);
      return () => document.removeEventListener('mousedown', handleClickOutside);
    }
  }, [dropdownOpen]);

  // Close the mobile off-canvas nav whenever the route changes.
  useEffect(() => {
    setNavOpen(false);
  }, [location.pathname]);

  return (
    <div style={{ display: 'flex', minHeight: '100dvh' }}>
      {/* Mobile-only scrim behind the off-canvas sidebar; no desktop/tablet equivalent */}
      {navOpen && (
        <div className="nf-nav-scrim" onClick={() => setNavOpen(false)} aria-hidden="true" />
      )}

      {/* Sidebar */}
      <aside
        className={`shell-sidebar${navOpen ? ' shell-sidebar--open' : ''}`}
        style={{
          width: 236, flexShrink: 0, background: '#0B0C0F', borderRight: '1px solid #23262D',
          display: 'flex', flexDirection: 'column', position: 'fixed', top: 0, bottom: 0, left: 0,
        }}
      >
        {/* Logo — height must match topbar exactly so the border forms one continuous line */}
        <div className="nf-sidebar-logo" style={{ height: 56, padding: '0 14px', flexShrink: 0, borderBottom: '1px solid #23262D', display: 'flex', alignItems: 'center', gap: 10 }}>
          <BrandMark size="sm" />
          <div>
            <div className="nf-sidebar-logo-title" style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 13, color: '#E8EAED', letterSpacing: '0.01em' }}>NForce OneHR</div>
            <div className="nf-sidebar-logo-sub" style={{ fontSize: 8, color: '#6B7280', letterSpacing: '.12em', textTransform: 'uppercase', marginTop: 1 }}>
              {toRoleTagline(role)}
            </div>
          </div>
        </div>

        {/* Nav items — hierarchical, click-only inline dropdowns; role visibility unchanged (see nav.config.ts) */}
        <SidebarNav role={role} currentKey={current.key} onNavigate={() => setNavOpen(false)} />

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
      <div className="nf-main-area" style={{ marginLeft: 236, marginTop: 0, flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
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
          {/* Hamburger — hidden by default, shown only ≤767px to open the off-canvas sidebar */}
          <button
            className="nf-hamburger-btn"
            onClick={() => setNavOpen((v) => !v)}
            aria-label={navOpen ? 'Close navigation menu' : 'Open navigation menu'}
            aria-expanded={navOpen}
            style={{ background: 'transparent', border: 'none', cursor: 'pointer', padding: 7, borderRadius: 6, alignItems: 'center', color: '#E8EAED' }}
          >
            {navOpen ? <CloseIcon size={18} aria-hidden="true" /> : <Menu size={18} aria-hidden="true" />}
          </button>

          <div style={{ color: '#9BA1AC', fontSize: 13 }}>
            NForce OneHR / <b style={{ color: '#E8EAED', fontWeight: 600 }}>{current.label}</b>
          </div>
          <div style={{ flex: 1 }} />
          <div className="nf-topbar-search" style={{ maxWidth: 260, width: 260, background: '#1E2128', border: '1px solid #2A2E37', borderRadius: 8, padding: '7px 11px', display: 'flex', alignItems: 'center', gap: 8, color: '#6B7280', fontSize: 12 }}>
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

          <Link
            to="/notifications"
            aria-label={`Notifications${unreadCount > 0 ? ` (${unreadCount} unread)` : ''}`}
            className="nf-topbar-item"
            style={{ position: 'relative', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 7, borderRadius: 6, color: 'inherit', textDecoration: 'none' }}
          >
            <Bell size={15} aria-hidden="true" />
            {unreadCount > 0 && (
              <span style={{ position: 'absolute', top: 3, right: 3, minWidth: 14, height: 14, borderRadius: 7, background: '#e4373d', color: '#fff', fontSize: 9, fontWeight: 700, display: 'grid', placeItems: 'center', padding: '0 3px', lineHeight: 1 }}>
                {unreadCount > 99 ? '99+' : unreadCount}
              </span>
            )}
          </Link>

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
                email={email}
                role={role}
                initials={initials}
                onClose={() => setDropdownOpen(false)}
              />
            )}
          </div>
        </header>

        <main className="nf-main-content" style={{ flex: 1, padding: 26, background: 'var(--shell)', color: 'var(--txt)' }}>
          <ComplianceBanner />
          {current.phase > 1 ? <ComingInPhase label={current.label} phase={current.phase} /> : <Outlet />}
        </main>
      </div>
    </div>
  );
}

import { useRef, useState, useEffect } from 'react';
import { Link, useLocation, Outlet, useNavigate } from 'react-router-dom';
import { Search, Bell, Sun, Moon, Shield, User, LogOut, Settings } from 'lucide-react';
import { NAV, type Role } from '../lib/nav.config';
import { useTheme } from '../lib/theme';
import { useAuthStore } from '../store/authStore';
import { BrandMark } from './BrandMark';

function toShellRole(dbRole: string | undefined): Role {
  switch (dbRole) {
    case 'SUPER_ADMIN':  return 'Super Admin';
    case 'HR_ADMIN':     return 'HR Admin';
    case 'MANAGER':      return 'Manager';
    case 'EMPLOYEE':
    case 'DELIVERY':
    case 'FINANCE':
    case 'LEADERSHIP':
    default:             return 'Employee';
  }
}

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

export function Shell() {
  const storeUser  = useAuthStore((s) => s.user);
  const { theme, toggleTheme } = useTheme();
  const location   = useLocation();
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const role     = toShellRole(storeUser?.role);
  const name     = storeUser?.email || 'User';
  const initials = getInitials(storeUser?.email);

  const navItems = NAV[role];
  const current  = navItems.find((n) => location.pathname.startsWith(n.path)) ?? navItems[0];

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

          <button aria-label="Notifications" className="nf-topbar-item" style={{ background: 'transparent', border: 'none', cursor: 'pointer', padding: 7, borderRadius: 6 }}>
            <Bell size={15} aria-hidden="true" />
          </button>

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
          {current.phase > 1 ? <ComingInPhase label={current.label} phase={current.phase} /> : <Outlet />}
        </main>
      </div>
    </div>
  );
}

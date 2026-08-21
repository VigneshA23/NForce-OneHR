import { useRef, useState, useEffect, useCallback, useMemo } from 'react';
import { useLocation, Outlet, useNavigate, Link } from 'react-router-dom';
import { Search, Bell, Sun, Moon, Shield, User, LogOut, Menu, X as CloseIcon } from 'lucide-react';
import { NAV, toShellRole, type Role, type NavItem } from '../lib/nav.config';
import { employeesApi, type EmployeeRecord } from '../api/employees';
import { useTheme } from '../lib/theme';
import { useAuthStore } from '../store/authStore';
import { BrandMark } from './BrandMark';
import { notificationsApi } from '../api/notifications';
import { publishNewNotifications } from '../lib/notificationEvents';
import { authApi } from '../api/auth';
import { API_ORIGIN } from '../api/config';
import { ComplianceBanner } from './ComplianceBanner';
import { SidebarNav } from './SidebarNav';
import { profileApi } from '../api/profile';
import { StatusBadge, inactiveDimStyle } from './EmployeeStatus';

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

// Shared circular avatar used by the sidebar profile card, the topbar trigger button, and the
// profile dropdown header. Renders the uploaded photo as a `background` image (sized/positioned
// via `cover`/`center`) when present, so it always fills whatever size this component is given —
// no separate <img> geometry to keep in sync — and falls back to the initials disc otherwise.
function AvatarCircle({ photoDataUrl, initials, size, fontSize, border }: {
  photoDataUrl?: string | null;
  initials: string;
  size: number;
  fontSize: number;
  border?: string;
}) {
  return (
    <div
      style={{
        width: size, height: size, borderRadius: '50%',
        background: photoDataUrl ? `url(${photoDataUrl}) center/cover no-repeat` : '#B11116',
        display: 'grid', placeItems: 'center', color: '#fff', fontSize, fontWeight: 700,
        flexShrink: 0, boxSizing: 'border-box',
        ...(border ? { border } : {}),
      }}
    >
      {!photoDataUrl && initials}
    </div>
  );
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

function ProfileDropdown({ name, email, role, initials, photoDataUrl, onClose }: {
  name: string;
  email: string;
  role: Role;
  initials: string;
  photoDataUrl?: string | null;
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
          <AvatarCircle photoDataUrl={photoDataUrl} initials={initials} size={32} fontSize={12} />
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

  // ── Global search ────────────────────────────────────────────────────────────
  const [searchQuery, setSearchQuery] = useState('');
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchPeople, setSearchPeople] = useState<EmployeeRecord[]>([]);
  const [searchPeopleLoaded, setSearchPeopleLoaded] = useState(false);
  const [searchIdx, setSearchIdx] = useState(-1);
  const searchRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);

  const role     = toShellRole(storeUser?.role);
  const email    = storeUser?.email || '';
  const name     = storeUser?.fullName || email || 'User';
  const initials = getInitials(storeUser?.fullName ?? storeUser?.email);

  const navItems = NAV[role];
  const current  = navItems.find((n) => location.pathname.startsWith(n.path)) ?? navItems[0];

  type SearchResultItem =
    | { kind: 'nav'; item: NavItem }
    | { kind: 'person'; record: EmployeeRecord };

  const navMatches = useMemo<NavItem[]>(() => {
    const q = searchQuery.toLowerCase().trim();
    if (!q) return [];
    return navItems.filter(n => n.label.toLowerCase().includes(q)).slice(0, 4);
  }, [searchQuery, navItems]);

  const peopleMatches = useMemo<EmployeeRecord[]>(() => {
    const q = searchQuery.toLowerCase().trim();
    if (q.length < 2) return [];
    return searchPeople.filter(p =>
      p.fullName.toLowerCase().includes(q) || p.email.toLowerCase().includes(q)
    ).slice(0, 5);
  }, [searchQuery, searchPeople]);

  const allResults = useMemo<SearchResultItem[]>(() => [
    ...navMatches.map(item => ({ kind: 'nav' as const, item })),
    ...peopleMatches.map(record => ({ kind: 'person' as const, record })),
  ], [navMatches, peopleMatches]);

  useEffect(() => { setSearchIdx(-1); }, [searchQuery]);

  useEffect(() => {
    const q = searchQuery.trim();
    if (q.length >= 2 && !searchPeopleLoaded && token) {
      employeesApi.list(token)
        .then(list => { setSearchPeople(list); setSearchPeopleLoaded(true); })
        .catch(() => {});
    }
  }, [searchQuery, searchPeopleLoaded, token]);

  useEffect(() => {
    function out(e: MouseEvent) {
      if (searchRef.current && !searchRef.current.contains(e.target as Node)) {
        setSearchOpen(false); setSearchIdx(-1);
      }
    }
    if (searchOpen) {
      document.addEventListener('mousedown', out);
      return () => document.removeEventListener('mousedown', out);
    }
  }, [searchOpen]);

  function handleResultSelect(result: SearchResultItem) {
    if (result.kind === 'nav') {
      navigate(result.item.path);
    } else {
      navigate(`/directory?q=${encodeURIComponent(result.record.fullName)}`);
    }
    setSearchOpen(false); setSearchQuery(''); setSearchIdx(-1);
  }

  function handleSearchKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Escape') { setSearchOpen(false); setSearchIdx(-1); return; }
    if (e.key === 'ArrowDown') { e.preventDefault(); setSearchIdx(i => Math.min(i + 1, allResults.length - 1)); return; }
    if (e.key === 'ArrowUp') { e.preventDefault(); setSearchIdx(i => Math.max(i - 1, -1)); return; }
    if (e.key === 'Enter' && searchIdx >= 0) {
      e.preventDefault();
      const r = allResults[searchIdx];
      if (r) handleResultSelect(r);
    }
  }

  // Heal stale sessions (fullName predating the fullName-in-login-response change) and pick
  // up the user's profile photo — the login response never carries it, so without this the
  // topbar/sidebar avatars would only ever know about a photo after visiting /profile in this
  // same session. Runs once per token; ProfilePage's own upload/load flows keep the store in
  // sync after that (see ProfilePage.tsx's setAuth calls).
  const setAuth = useAuthStore((s) => s.setAuth);
  useEffect(() => {
    if (!token || !storeUser) return;
    profileApi.get(token)
      .then(p => {
        const patch: { fullName?: string; photoDataUrl?: string | null } = {};
        if (p.fullName && !storeUser.fullName) patch.fullName = p.fullName;
        if (p.photoDataUrl !== storeUser.photoDataUrl) patch.photoDataUrl = p.photoDataUrl;
        if (Object.keys(patch).length > 0) setAuth(token, { ...storeUser, ...patch });
      })
      .catch(() => {});
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  // The one app-wide notification poll (drives the bell badge). Other mounted pages (e.g.
  // LeavePage) react to what it finds via notificationEvents instead of running their own
  // separate poll — see that module's doc comment.
  //
  // Fetches the unread list (not just the count) so this single request can both size the
  // badge (data.totalElements) and detect newly-arrived notifications to publish — one API call
  // per tick, same as before. "New" is id > the highest id seen on the previous tick; the very
  // first tick after mount only establishes that baseline and never publishes, so pre-existing
  // unread notifications from before this page load don't fire spurious refreshes elsewhere.
  const lastSeenIdRef = useRef<number | null>(null);
  const firstPollRef  = useRef(true);

  const pollNotifications = useCallback(() => {
    if (!token) return;
    notificationsApi.unread(token, 0, 20)
      .then(data => {
        setUnreadCount(data.totalElements);
        const items = data.content;
        if (firstPollRef.current) {
          firstPollRef.current = false;
        } else if (lastSeenIdRef.current !== null) {
          const fresh = items.filter(n => n.id > lastSeenIdRef.current!);
          publishNewNotifications(fresh);
        }
        if (items.length > 0) {
          lastSeenIdRef.current = Math.max(lastSeenIdRef.current ?? 0, ...items.map(n => n.id));
        }
      })
      .catch(() => {});
  }, [token]);

  // Reset the baseline whenever the signed-in user changes (Shell can persist across a
  // logout/login in the same tab) — otherwise a stale id from a previous session could either
  // suppress a genuinely new notification or spuriously publish an old one as "new".
  useEffect(() => {
    firstPollRef.current = true;
    lastSeenIdRef.current = null;
  }, [token]);

  useEffect(() => {
    pollNotifications();
    const id = setInterval(pollNotifications, 30000);
    return () => clearInterval(id);
  }, [pollNotifications]);

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
          <AvatarCircle photoDataUrl={storeUser?.photoDataUrl} initials={initials} size={30} fontSize={11} />
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
            <b style={{ color: '#E8EAED', fontWeight: 600 }}>{current.label}</b>
          </div>
          <div style={{ flex: 1 }} />
          {/* Global search — People + Navigation */}
          <div ref={searchRef} className="nf-topbar-search" style={{ position: 'relative', maxWidth: 260, width: 260 }}>
            <div style={{ background: '#1E2128', border: '1px solid #2A2E37', borderRadius: 8, padding: '7px 11px', display: 'flex', alignItems: 'center', gap: 8 }}>
              <Search size={13} aria-hidden="true" style={{ color: '#6B7280', flexShrink: 0 }} />
              <input
                ref={searchInputRef}
                value={searchQuery}
                onChange={e => { setSearchQuery(e.target.value); setSearchOpen(true); }}
                onFocus={() => setSearchOpen(true)}
                onKeyDown={handleSearchKey}
                placeholder="Search this workspace…"
                aria-label="Search people and navigation"
                style={{ background: 'none', border: 'none', outline: 'none', color: '#E8EAED', fontSize: 12, flex: 1, minWidth: 0 }}
              />
              {searchQuery && (
                <button onClick={() => { setSearchQuery(''); setSearchOpen(false); }} style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#6B7280', padding: 0, display: 'flex', alignItems: 'center' }}>
                  <CloseIcon size={12} aria-hidden="true" />
                </button>
              )}
            </div>
            {/* Dropdown */}
            {searchOpen && searchQuery.trim() && allResults.length > 0 && (
              <div style={{ position: 'absolute', top: 'calc(100% + 6px)', left: 0, right: 0, background: '#16181D', border: '1px solid #2A2E37', borderRadius: 10, boxShadow: '0 8px 32px rgba(0,0,0,.55)', zIndex: 200, overflow: 'hidden' }}>
                {navMatches.length > 0 && (
                  <>
                    <div style={{ padding: '8px 12px 4px', fontSize: 10, fontWeight: 700, color: '#6B7280', textTransform: 'uppercase', letterSpacing: '.08em' }}>Navigate</div>
                    {navMatches.map((item, i) => {
                      const Icon = item.icon;
                      return (
                        <button key={item.key} onMouseDown={() => handleResultSelect({ kind: 'nav', item })}
                          style={{ width: '100%', display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px', background: searchIdx === i ? 'rgba(255,255,255,.06)' : 'none', border: 'none', cursor: 'pointer', color: '#C8CCD2', fontSize: 13, textAlign: 'left' }}
                          onMouseEnter={() => setSearchIdx(i)} onMouseLeave={() => setSearchIdx(-1)}>
                          <Icon size={14} style={{ color: '#9BA1AC', flexShrink: 0 }} aria-hidden="true" />
                          {item.label}
                        </button>
                      );
                    })}
                  </>
                )}
                {peopleMatches.length > 0 && (
                  <>
                    <div style={{ padding: '8px 12px 4px', fontSize: 10, fontWeight: 700, color: '#6B7280', textTransform: 'uppercase', letterSpacing: '.08em', borderTop: navMatches.length > 0 ? '1px solid #23262D' : 'none' }}>People</div>
                    {peopleMatches.map((record, i) => {
                      const globalIdx = navMatches.length + i;
                      return (
                        <button key={record.userId} onMouseDown={() => handleResultSelect({ kind: 'person', record })}
                          style={{ width: '100%', display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px', background: searchIdx === globalIdx ? 'rgba(255,255,255,.06)' : 'none', border: 'none', cursor: 'pointer', color: '#C8CCD2', fontSize: 13, textAlign: 'left', ...inactiveDimStyle(record.active) }}
                          onMouseEnter={() => setSearchIdx(globalIdx)} onMouseLeave={() => setSearchIdx(-1)}>
                          <div style={{ width: 24, height: 24, borderRadius: '50%', background: '#B11116', display: 'grid', placeItems: 'center', fontSize: 9, fontWeight: 700, color: '#fff', flexShrink: 0 }}>
                            {getInitials(record.fullName)}
                          </div>
                          <div style={{ minWidth: 0, flex: 1 }}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                              <div style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{record.fullName}</div>
                              {!record.active && <StatusBadge active={record.active} />}
                            </div>
                            <div style={{ fontSize: 11, color: '#6B7280', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{record.email}</div>
                          </div>
                        </button>
                      );
                    })}
                  </>
                )}
              </div>
            )}
            {searchOpen && searchQuery.trim().length >= 2 && allResults.length === 0 && (
              <div style={{ position: 'absolute', top: 'calc(100% + 6px)', left: 0, right: 0, background: '#16181D', border: '1px solid #2A2E37', borderRadius: 10, boxShadow: '0 8px 32px rgba(0,0,0,.55)', zIndex: 200, padding: '14px 12px', fontSize: 12, color: '#6B7280', textAlign: 'center' }}>
                No results for "{searchQuery.trim()}"
              </div>
            )}
          </div>
          {/* Collapsed search icon — shown only ≤767px where the bar doesn't fit */}
          <button
            className="nf-topbar-search-icon"
            aria-label="Search this workspace"
            onClick={() => { setSearchOpen(true); setTimeout(() => searchInputRef.current?.focus(), 50); }}
            style={{ background: 'transparent', border: 'none', cursor: 'pointer', alignItems: 'center', justifyContent: 'center', padding: 7, borderRadius: 6, color: '#9BA1AC' }}
          >
            <Search size={15} aria-hidden="true" />
          </button>

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
              <AvatarCircle
                photoDataUrl={storeUser?.photoDataUrl}
                initials={initials}
                size={32}
                fontSize={12}
                border="2px solid rgba(255,255,255,0.55)"
              />
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
                photoDataUrl={storeUser?.photoDataUrl}
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

import { useRef, useState, useEffect, useCallback, useMemo } from 'react';
import { useLocation, Outlet, useNavigate, Link } from 'react-router-dom';
import {
  Search, Bell, Settings, KeyRound,
  Shield, User, LogOut, Menu, X as CloseIcon,
  Clock as ClockIcon, ArrowRight,
} from 'lucide-react';
import { NAV, toShellRole, isNavItemDisabled, navItemDisplayPhase, type Role, type NavItem } from '../lib/nav.config';
import { searchApi, type SearchResultItem as ApiSearchResultItem, type SearchGroup } from '../api/search';
import { readRecentSearches, addRecentSearch, clearRecentSearches } from '../lib/recentSearches';
import { useAuthStore } from '../store/authStore';
import { BrandMark } from './BrandMark';
import { notificationsApi } from '../api/notifications';
import { publishNewNotifications, subscribeToNewNotifications } from '../lib/notificationEvents';
import { KudosCelebrationToast, type KudosCelebrationItem } from './KudosCelebrationToast';
import { authApi } from '../api/auth';
import { stashSessionMessageForLogin, SESSION_PROFILE_UPDATED_MESSAGE } from '../lib/authFetch';
import { API_ORIGIN } from '../api/config';
import { ComplianceBanner } from './ComplianceBanner';
import { SidebarNav } from './SidebarNav';
import { profileApi } from '../api/profile';
import { EmployeeAvatar } from './EmployeeAvatar';
import { WorkAnniversaryOverlay } from './WorkAnniversaryOverlay';
import { getAnniversaryYears, hasShownAnniversaryThisYear, markAnniversaryShown } from '../lib/workAnniversary';
import { useAccentColor, ACCENT_BAND_POSITION_X } from '../lib/accentColor';
import sidebarDecoration from '../assets/sidebar-decoration.png';
import { AssistantLauncher } from './aiAssistant/AssistantLauncher';

function toRoleTagline(role: Role): string {
  switch (role) {
    case 'Super Admin': return 'Super Admin Experience';
    case 'HR Admin':    return 'HR Admin Experience';
    case 'Manager':     return 'Manager Experience';
    default:            return 'Employee Experience';
  }
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
      <div style={{ fontFamily: 'Inter, sans-serif', fontSize: 17, color: 'var(--txt)', marginBottom: 6 }}>
        {label}
      </div>
      <div style={{ fontSize: 13 }}>
        Ships in Phase {phase}, per the roadmap — this is a placeholder, not a broken link.
      </div>
    </div>
  );
}

function DropdownItem({ icon: Icon, label, onClick, trailing, danger }: {
  icon: typeof User;
  label: string;
  onClick: () => void;
  trailing?: React.ReactNode;
  danger?: boolean;
}) {
  return (
    <button
      role="menuitem"
      onClick={onClick}
      style={{
        width: '100%', display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px',
        background: 'none', border: 'none', cursor: 'pointer',
        color: danger ? '#E4373D' : '#C8CCD2', fontSize: 13, textAlign: 'left',
      }}
      onMouseEnter={(e) => {
        (e.currentTarget as HTMLButtonElement).style.background = danger ? 'rgba(228,55,61,.08)' : '#1E2128';
        if (!danger) (e.currentTarget as HTMLButtonElement).style.color = '#fff';
      }}
      onMouseLeave={(e) => {
        (e.currentTarget as HTMLButtonElement).style.background = 'none';
        if (!danger) (e.currentTarget as HTMLButtonElement).style.color = '#C8CCD2';
      }}
    >
      <Icon size={14} aria-hidden="true" style={{ flexShrink: 0 }} />
      <span style={{ flex: 1 }}>{label}</span>
      {trailing}
    </button>
  );
}

function ProfileDropdown({ name, email, role, photoDataUrl, onClose }: {
  name: string;
  email: string;
  role: Role;
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
        boxShadow: '0 8px 32px rgba(0,0,0,.55)', zIndex: 200, overflow: 'visible',
      }}
      role="menu"
    >
      <div style={{ padding: '12px 14px', borderBottom: '1px solid #2A2E37' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
          <EmployeeAvatar photoDataUrl={photoDataUrl} name={name} size={32} fontSize={12} />
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 12.5, color: '#E8EAED', fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{name}</div>
            <div style={{ fontSize: 11, color: '#9BA1AC', marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{email}</div>
            <div style={{ fontSize: 10, color: '#6B7280', marginTop: 1 }}>{role}</div>
          </div>
        </div>
      </div>

      <div style={{ padding: '4px 0' }}>
        <DropdownItem icon={User} label="My Profile" onClick={() => { onClose(); navigate('/profile'); }} />
        {/* Display mode / Theme color live only in User preferences → Appearance now — no
            duplicate controls here. */}
        <DropdownItem icon={Settings} label="User preferences" onClick={() => { onClose(); navigate('/user-preferences'); }} />
        <DropdownItem icon={KeyRound} label="Change password" onClick={() => { onClose(); navigate('/change-password'); }} />
      </div>

      <div style={{ borderTop: '1px solid #2A2E37', marginTop: 2 }}>
        <DropdownItem icon={LogOut} label="Sign out" onClick={handleSignOut} danger />
      </div>
    </div>
  );
}


export function Shell() {
  const storeUser  = useAuthStore((s) => s.user);
  const token      = useAuthStore((s) => s.token) ?? '';
  const clearAuth  = useAuthStore((s) => s.clearAuth);
  const navigate   = useNavigate();
  const location   = useLocation();
  const { accent }  = useAccentColor();
  const [dropdownOpen, setDropdownOpen]   = useState(false);
  const [unreadCount, setUnreadCount]     = useState(0);
  // Work-anniversary celebration — null means "not showing"; see the profile-sync effect below
  // for when/how this gets set, and workAnniversary.ts for the date/once-per-year logic.
  const [anniversaryYears, setAnniversaryYears] = useState<number | null>(null);
  // Small on-page "you've been appreciated" celebration — see KudosCelebrationToast. Fed by the
  // notification poll below via notificationEvents, so it's not its own network call.
  const [kudosQueue, setKudosQueue] = useState<KudosCelebrationItem[]>([]);
  // Mobile-only off-canvas nav toggle (≤767px). Defaults closed; the CSS that
  // reads this className only exists inside the ≤767px media query, so this
  // state never affects rendering at tablet/desktop widths.
  const [navOpen, setNavOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  // ── Global search ────────────────────────────────────────────────────────────
  const [searchQuery, setSearchQuery] = useState('');
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchGroups, setSearchGroups] = useState<SearchGroup[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchError, setSearchError] = useState(false);
  const [recentSearches, setRecentSearches] = useState<string[]>([]);
  const [searchIdx, setSearchIdx] = useState(-1);
  // Mobile-only: the collapsed magnifying-glass icon (≤767px, where the full bar doesn't fit —
  // see .nf-topbar-search-icon) expands into this full-width search row instead of doing nothing.
  // Never set true from anywhere reachable on desktop/tablet, since that icon is only visible/
  // clickable ≤767px in the first place.
  const [mobileSearchOpen, setMobileSearchOpen] = useState(false);
  const searchRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);

  const role     = toShellRole(storeUser?.role);
  const email    = storeUser?.email || '';
  const name     = storeUser?.fullName || email || 'User';

  const navItems = NAV[role];
  const current  = navItems.find((n) => location.pathname.startsWith(n.path)) ?? navItems[0];

  type FlatResult =
    | { kind: 'nav'; item: NavItem }
    | { kind: 'module'; result: ApiSearchResultItem };

  const trimmedQuery = searchQuery.trim();

  const navMatches = useMemo<NavItem[]>(() => {
    const q = trimmedQuery.toLowerCase();
    if (!q) return [];
    return navItems.filter(n => n.label.toLowerCase().includes(q)).slice(0, 4);
  }, [trimmedQuery, navItems]);

  const allResults = useMemo<FlatResult[]>(() => [
    ...navMatches.map(item => ({ kind: 'nav' as const, item })),
    ...searchGroups.flatMap(g => g.items.map(result => ({ kind: 'module' as const, result }))),
  ], [navMatches, searchGroups]);

  useEffect(() => { setSearchIdx(-1); }, [searchQuery]);

  // Debounced global search preview — fires 300ms after typing stops, only once the query meets
  // the backend's minimum meaningful length (see GlobalSearchService.MIN_QUERY_LENGTH).
  useEffect(() => {
    if (trimmedQuery.length < 2 || !token) {
      setSearchGroups([]); setSearchLoading(false); setSearchError(false);
      return;
    }
    setSearchLoading(true); setSearchError(false);
    const handle = setTimeout(() => {
      searchApi.preview(token, trimmedQuery)
        .then(res => setSearchGroups(res.groups))
        .catch(() => { setSearchGroups([]); setSearchError(true); })
        .finally(() => setSearchLoading(false));
    }, 300);
    return () => clearTimeout(handle);
  }, [trimmedQuery, token]);

  // Recent searches show only while the box is focused with no query — refresh from storage
  // each time that happens rather than once on mount, so a search made in another tab this
  // session still shows up.
  useEffect(() => {
    if (searchOpen && !trimmedQuery && email) {
      setRecentSearches(readRecentSearches(email));
    }
  }, [searchOpen, trimmedQuery, email]);

  useEffect(() => {
    function out(e: MouseEvent) {
      if (searchRef.current && !searchRef.current.contains(e.target as Node)) {
        setSearchOpen(false); setSearchIdx(-1);
        // Mobile's expanded search row (see mobileSearchOpen) is a stand-in for the whole topbar
        // while active — a tap anywhere else should cancel it entirely, the same as its own "✕"
        // button, not just dismiss the suggestions dropdown and leave the empty bar sitting open.
        if (mobileSearchOpen) { setMobileSearchOpen(false); setSearchQuery(''); }
      }
    }
    // Keep listening for as long as the mobile row is expanded too, even once its own dropdown
    // has already closed from an earlier outside click — otherwise a second tap elsewhere
    // wouldn't do anything.
    if (searchOpen || mobileSearchOpen) {
      document.addEventListener('mousedown', out);
      return () => document.removeEventListener('mousedown', out);
    }
  }, [searchOpen, mobileSearchOpen]);

  function closeSearch() {
    setSearchOpen(false); setSearchQuery(''); setSearchIdx(-1); setMobileSearchOpen(false);
  }

  function goToResultsPage(q: string) {
    if (email) setRecentSearches(addRecentSearch(email, q));
    navigate(`/search?q=${encodeURIComponent(q)}`);
    closeSearch();
  }

  function handleResultSelect(result: FlatResult) {
    if (result.kind === 'nav') {
      navigate(result.item.path);
    } else {
      if (email) setRecentSearches(addRecentSearch(email, trimmedQuery));
      navigate(result.result.detailUrl);
    }
    closeSearch();
  }

  function handleRecentSearchClick(q: string) {
    setSearchQuery(q);
    setSearchIdx(-1);
  }

  function handleClearRecentSearches() {
    if (!email) return;
    clearRecentSearches(email);
    setRecentSearches([]);
  }

  function handleSearchKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Escape') { closeSearch(); return; }
    if (e.key === 'ArrowDown') { e.preventDefault(); setSearchIdx(i => Math.min(i + 1, allResults.length - 1)); return; }
    if (e.key === 'ArrowUp') { e.preventDefault(); setSearchIdx(i => Math.max(i - 1, -1)); return; }
    if (e.key === 'Enter') {
      e.preventDefault();
      if (searchIdx >= 0) {
        const r = allResults[searchIdx];
        if (r) handleResultSelect(r);
      } else if (trimmedQuery.length >= 2) {
        goToResultsPage(trimmedQuery);
      }
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

        // Work-anniversary celebration — piggybacks on this same profile fetch rather than
        // making its own network call. p.hasEmployeeRecord/p.joiningDate cover the "missing or
        // invalid joining date" case safely (getAnniversaryYears returns null for either), and
        // hasShownAnniversaryThisYear gates it to once per employee per year even across
        // logout/login or a page refresh (persisted in localStorage, not just this session).
        if (p.hasEmployeeRecord) {
          const years = getAnniversaryYears(p.joiningDate, new Date());
          if (years !== null) {
            const thisYear = new Date().getFullYear();
            if (!hasShownAnniversaryThisYear(window.localStorage, p.email, thisYear)) {
              markAnniversaryShown(window.localStorage, p.email, thisYear);
              setAnniversaryYears(years);
            }
          }
        }
      })
      .catch(() => {});
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  // Manual QA hook for the work-anniversary celebration — e.g. /dashboard?anniversaryTest=5 forces
  // it open showing "5 Years of Excellence", bypassing both the real join-date check and the
  // once-per-year localStorage gate above. There's no UI path to set an arbitrary employee's join
  // date to today for testing, and this shared dev database has real teammates' data in it, so
  // this is the safe way to preview/QA the overlay without touching any employee record. Never
  // fires for a real user unless they specifically type this query param.
  useEffect(() => {
    const raw = new URLSearchParams(location.search).get('anniversaryTest');
    if (raw === null) return;
    const years = Number(raw);
    if (Number.isInteger(years) && years > 0) setAnniversaryYears(years);
  }, [location.search]);

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

  // "A teammate appreciated you" celebration — reacts to whatever the poll above just found,
  // same as AttendancePage/LeavePage/MyRequestsPage already do via this same pub/sub, except
  // this lives in Shell (the root layout) so it fires no matter which page is currently open.
  // Sending kudos already creates a KUDOS-type Notification server-side (see KudosService); there
  // is no push/SSE channel for regular notifications in this app today, only this 30s poll, so
  // the animation can appear up to ~30s after the kudos was actually sent, not instantly.
  useEffect(() => {
    return subscribeToNewNotifications((items) => {
      const kudos = items.filter(n => n.type === 'KUDOS');
      if (kudos.length === 0) return;
      setKudosQueue(prev => [...prev, ...kudos.map(n => ({ id: n.id, title: n.title, message: n.message }))]);
    });
  }, []);

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
          // Hard redirect (not the SPA navigate() used elsewhere in this file) — required so the
          // message below survives to the Login page: consumeSessionMessage (authFetch.ts) only
          // reads sessionStorage once, at module import, which a client-side route change would
          // bypass entirely since this module is already loaded.
          stashSessionMessageForLogin(SESSION_PROFILE_UPDATED_MESSAGE);
          window.location.href = '/login';
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

  // iOS Safari's dynamic bottom toolbar (the one that shrinks away on scroll, see the
  // safe-area/dvh comments elsewhere in this file and index.css) can get its layout out of
  // sync with a `position: fixed` overlay closing right after the toolbar was mid-animation —
  // opening this drawer forces the toolbar back to its full/expanded state (standard Safari
  // behavior on any tap), and once the drawer's scrim/sidebar unmount, WebKit sometimes fails
  // to repaint the page region that sits behind where the toolbar was, leaving a blank hole
  // where content used to render — until something else forces a redraw. A no-op 1px scroll
  // nudge right after the close transition (200ms, matching .shell-sidebar's own transition)
  // is the standard, imperceptible way to force that repaint.
  const wasNavOpenRef = useRef(false);
  useEffect(() => {
    const wasOpen = wasNavOpenRef.current;
    wasNavOpenRef.current = navOpen;
    if (wasOpen && !navOpen) {
      const t = setTimeout(() => {
        window.scrollBy(0, 1);
        window.scrollBy(0, -1);
      }, 220);
      return () => clearTimeout(t);
    }
  }, [navOpen]);

  // Section label + row styles shared by every group in the dropdown (recent searches, Navigate,
  // and each module group) so they stay visually identical.
  const dropdownSectionLabelStyle: React.CSSProperties = {
    padding: '8px 12px 4px', fontSize: 10, fontWeight: 700, color: '#6B7280',
    textTransform: 'uppercase', letterSpacing: '.08em',
  };
  function dropdownRowStyle(active: boolean): React.CSSProperties {
    return {
      width: '100%', display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px',
      background: active ? 'rgba(255,255,255,.06)' : 'none', border: 'none', cursor: 'pointer',
      color: '#C8CCD2', fontSize: 13, textAlign: 'left',
    };
  }

  // Running start index of each module group within the flattened `allResults` list (nav matches
  // come first — see allResults above), so a group's rows highlight in step with arrow-key nav.
  const groupStartIndex: Record<string, number> = {};
  {
    let cursor = navMatches.length;
    for (const g of searchGroups) { groupStartIndex[g.module] = cursor; cursor += g.items.length; }
  }

  const showRecent = searchOpen && !trimmedQuery && recentSearches.length > 0;
  const showResults = searchOpen && trimmedQuery.length >= 2;
  const noMatchesYet = showResults && !searchLoading && !searchError && navMatches.length === 0 && searchGroups.length === 0;

  // Results dropdown — shared by the desktop search bar and the mobile expanded-search row
  // below, so the two never drift out of sync. Only one of them is ever mounted at a time
  // (mobileSearchOpen swaps between them), so reusing this element in both branches is safe.
  const searchDropdown = (
    <>
      {showRecent && (
        <div style={{ position: 'absolute', top: 'calc(100% + 6px)', left: 0, right: 0, background: '#16181D', border: '1px solid #2A2E37', borderRadius: 10, boxShadow: '0 8px 32px rgba(0,0,0,.55)', zIndex: 200, overflow: 'hidden' }}>
          <div style={{ ...dropdownSectionLabelStyle, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <span>Recent Searches</span>
            <button
              type="button"
              onMouseDown={(e) => { e.preventDefault(); handleClearRecentSearches(); }}
              style={{ background: 'none', border: 'none', padding: 0, color: '#6B7280', fontSize: 10, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.08em', cursor: 'pointer' }}
              aria-label="Clear recent searches"
            >
              Clear
            </button>
          </div>
          {recentSearches.map(q => (
            <button key={q} onMouseDown={() => handleRecentSearchClick(q)} style={dropdownRowStyle(false)}
              onMouseEnter={(e) => { (e.currentTarget as HTMLButtonElement).style.background = 'rgba(255,255,255,.06)'; }}
              onMouseLeave={(e) => { (e.currentTarget as HTMLButtonElement).style.background = 'none'; }}>
              <ClockIcon size={13} style={{ color: '#6B7280', flexShrink: 0 }} aria-hidden="true" />
              {q}
            </button>
          ))}
        </div>
      )}

      {showResults && (
        <div style={{ position: 'absolute', top: 'calc(100% + 6px)', left: 0, right: 0, maxHeight: 440, overflowY: 'auto', background: '#16181D', border: '1px solid #2A2E37', borderRadius: 10, boxShadow: '0 8px 32px rgba(0,0,0,.55)', zIndex: 200, overflowX: 'hidden' }}>
          {navMatches.length > 0 && (
            <>
              <div style={dropdownSectionLabelStyle}>Navigate</div>
              {navMatches.map((item, i) => {
                const Icon = item.icon;
                return (
                  <button key={item.key} onMouseDown={() => handleResultSelect({ kind: 'nav', item })}
                    style={dropdownRowStyle(searchIdx === i)}
                    onMouseEnter={() => setSearchIdx(i)} onMouseLeave={() => setSearchIdx(-1)}>
                    <Icon size={14} style={{ color: '#9BA1AC', flexShrink: 0 }} aria-hidden="true" />
                    {item.label}
                  </button>
                );
              })}
            </>
          )}

          {searchLoading && (
            <div style={{ padding: '14px 12px', fontSize: 12, color: '#6B7280', textAlign: 'center' }}>Searching…</div>
          )}

          {!searchLoading && searchError && (
            <div style={{ padding: '14px 12px', fontSize: 12, color: '#E4373D', textAlign: 'center' }}>
              Couldn't load results. Try again.
            </div>
          )}

          {!searchLoading && !searchError && searchGroups.map(group => (
            <div key={group.module} style={{ borderTop: '1px solid #23262D' }}>
              <div style={dropdownSectionLabelStyle}>{group.label}</div>
              {group.items.map((item, i) => {
                const globalIdx = groupStartIndex[group.module] + i;
                return (
                  <button key={item.id} onMouseDown={() => handleResultSelect({ kind: 'module', result: item })}
                    style={dropdownRowStyle(searchIdx === globalIdx)}
                    onMouseEnter={() => setSearchIdx(globalIdx)} onMouseLeave={() => setSearchIdx(-1)}>
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <div style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{item.title}</div>
                      {item.subtitle && (
                        <div style={{ fontSize: 11, color: '#6B7280', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{item.subtitle}</div>
                      )}
                    </div>
                  </button>
                );
              })}
              {group.refineUrl && group.totalCount > group.items.length && (
                <button onMouseDown={() => navigate(group.refineUrl!)} style={{ ...dropdownRowStyle(false), color: '#E4373D', fontSize: 12, fontWeight: 600 }}
                  onMouseEnter={(e) => { (e.currentTarget as HTMLButtonElement).style.background = 'rgba(255,255,255,.06)'; }}
                  onMouseLeave={(e) => { (e.currentTarget as HTMLButtonElement).style.background = 'none'; }}>
                  See all {group.totalCount} in {group.label} →
                </button>
              )}
            </div>
          ))}

          {noMatchesYet && (
            <div style={{ padding: '14px 12px', fontSize: 12, color: '#6B7280', textAlign: 'center' }}>
              No results for "{trimmedQuery}"
            </div>
          )}

          {!searchLoading && !searchError && searchGroups.length > 0 && (
            <button onMouseDown={() => goToResultsPage(trimmedQuery)}
              style={{ ...dropdownRowStyle(false), justifyContent: 'center', borderTop: '1px solid #23262D', color: '#E8EAED', fontWeight: 600 }}
              onMouseEnter={(e) => { (e.currentTarget as HTMLButtonElement).style.background = 'rgba(255,255,255,.06)'; }}
              onMouseLeave={(e) => { (e.currentTarget as HTMLButtonElement).style.background = 'none'; }}>
              See all results for "{trimmedQuery}" <ArrowRight size={13} aria-hidden="true" />
            </button>
          )}
        </div>
      )}
    </>
  );

  return (
    <div style={{ display: 'flex', minHeight: 'var(--app-height, 100dvh)' }}>
      {/* Mobile-only scrim behind the off-canvas sidebar; no desktop/tablet equivalent */}
      {navOpen && (
        <div className="nf-nav-scrim" onClick={() => setNavOpen(false)} aria-hidden="true" />
      )}

      {/* Sidebar */}
      <aside
        className={`shell-sidebar${navOpen ? ' shell-sidebar--open' : ''}`}
        style={{
          width: 236, flexShrink: 0, background: '#0B0C0F', borderRight: '1px solid #23262D',
          display: 'flex', flexDirection: 'column', position: 'fixed', top: 0, left: 0,
          // `height: 100dvh` instead of `bottom: 0` — on iOS Safari, a `position: fixed` element
          // pinned via top/bottom:0 can end up sized against a stale snapshot of the toolbar's
          // collapsed/expanded state at the moment it's inserted, leaving a black gap below it
          // if the toolbar's state changes shortly after (e.g. opening this drawer right after
          // scrolling). `dvh` is the unit purpose-built to track the actual live viewport instead.
          height: 'var(--app-height, 100dvh)',
        }}
      >
        {/* Decorative artwork for the sidebar's lower empty area — purely visual, so it's
            absolutely positioned (removed from the flex flow entirely: adds no height, never
            pushes the logo/nav/profile card) and pointer-events:none (never intercepts clicks).
            Note this div is NOT a flex item (position:absolute children are pulled out of flex
            layout entirely per spec), so the "z-index:auto flex items paint in DOM order" rule
            does not apply to it — it paints under plain CSS stacking rules instead, where a
            positioned element needs a NEGATIVE z-index to paint behind its static in-flow
            siblings (z-index:0 would do the opposite and paint above them, hiding the Logo/nav
            text/profile-card name behind this opaque artwork — that was the bug: zIndex:-1 is
            what actually keeps it behind everything while still painting above <aside>'s own
            solid background). SidebarNav's own nav list (which can grow taller than its own
            space and scroll internally, covering this artwork as it does) then paints above
            this the same way, being static in-flow content itself.
            One 5-band sprite image (assets/sidebar-decoration.png) covers all 5 Theme colors —
            background-size stretches it to 5x this box's width, and background-position-x picks
            one 1x-wide band per accent (see ACCENT_BAND_POSITION_X) — no per-color image files,
            and the PNG itself is never cropped/stretched/distorted, only positioned.
            The mask-image (not a solid overlay box, which would itself look like a second hard
            edge) fades the image's own alpha from 0 at this box's top down to fully opaque by
            40% — so the sidebar's plain #0B0C0F background shows through smoothly at the seam
            instead of a visible boundary line, matching how index.css already fades the profile
            page's hero banner into its panel background the same way. */}
        <div
          aria-hidden="true"
          style={{
            position: 'absolute', left: 0, right: 0, bottom: 0, height: 380, zIndex: -1,
            overflow: 'hidden', pointerEvents: 'none',
            backgroundImage: `url(${sidebarDecoration})`,
            backgroundSize: '500% auto',
            backgroundPosition: `${ACCENT_BAND_POSITION_X[accent]} 100%`,
            backgroundRepeat: 'no-repeat',
            WebkitMaskImage: 'linear-gradient(to bottom, transparent 0%, rgba(0,0,0,.5) 22%, black 42%)',
            maskImage: 'linear-gradient(to bottom, transparent 0%, rgba(0,0,0,.5) 22%, black 42%)',
          }}
        />

        {/* Logo — height must match topbar exactly so the border forms one continuous line */}
        <Link to="/" className="nf-sidebar-logo" style={{ height: 56, padding: '0 14px', flexShrink: 0, borderBottom: '1px solid #23262D', display: 'flex', alignItems: 'center', gap: 10 }}>
          <BrandMark size="sm" />
          <div>
            <div className="nf-sidebar-logo-title" style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 13, color: '#E8EAED', letterSpacing: '0.01em' }}>NForce OneHR</div>
            <div className="nf-sidebar-logo-sub" style={{ fontSize: 8, color: '#6B7280', letterSpacing: '.12em', textTransform: 'uppercase', marginTop: 1 }}>
              {toRoleTagline(role)}
            </div>
          </div>
        </Link>

        {/* Nav items — hierarchical, click-only inline dropdowns; role visibility unchanged (see nav.config.ts) */}
        <SidebarNav role={role} currentKey={current.key} onNavigate={() => setNavOpen(false)} />

        {/* Profile card (sidebar) — deliberately has no background of its own, so the decorative
            artwork behind it (see above) shows through around the name/role text, same as the
            avatar/name treatment sitting directly on the artwork. The glow behind the avatar
            reuses --bm-glow/--bm-ring, the same per-accent tokens BrandMark's logo uses, so it
            follows the selected Theme color automatically with no separate color logic here. */}
        <div style={{ borderTop: '1px solid #23262D', padding: 10, display: 'flex', alignItems: 'center', gap: 8, position: 'relative' }}>
          <div style={{ position: 'relative', width: 30, height: 30, flexShrink: 0 }}>
            <span
              aria-hidden="true"
              style={{
                position: 'absolute', inset: 0, margin: 'auto', width: 52, height: 52,
                borderRadius: '50%', background: 'var(--bm-glow)', pointerEvents: 'none',
              }}
            />
            <EmployeeAvatar photoDataUrl={storeUser?.photoDataUrl} name={name} size={30} fontSize={11} />
            <span aria-hidden="true" style={{ position: 'absolute', inset: 0, borderRadius: '50%', border: '1px solid var(--bm-ring)', pointerEvents: 'none' }} />
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
            background: 'linear-gradient(90deg, #050506 0%, var(--brand-deep) 40%, var(--brand) 100%)',
            borderBottom: '1px solid color-mix(in srgb, var(--brand-bright) 22%, transparent)',
            position: 'sticky', top: 0, zIndex: 30,
            display: 'flex', alignItems: 'center', padding: '0 18px', gap: 10,
          }}
        >
          {mobileSearchOpen ? (
            /* Mobile expanded search — replaces the whole topbar row while active (≤767px only;
               nothing reachable on desktop/tablet ever sets mobileSearchOpen true). Tapping the
               collapsed magnifying-glass icon below used to try to focus an input that was
               `display: none` at this width, so nothing visible ever happened; this actually
               shows the same functional search bar/dropdown the desktop topbar uses. */
            <div style={{ display: 'flex', alignItems: 'center', width: '100%', gap: 8 }}>
              <button
                onClick={() => { setMobileSearchOpen(false); setSearchOpen(false); setSearchQuery(''); setSearchIdx(-1); }}
                aria-label="Close search"
                style={{ background: 'transparent', border: 'none', cursor: 'pointer', padding: 7, borderRadius: 6, color: '#E8EAED', display: 'flex', flexShrink: 0 }}
              >
                <CloseIcon size={18} aria-hidden="true" />
              </button>
              <div ref={searchRef} style={{ position: 'relative', flex: 1, minWidth: 0 }}>
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
                    style={{ background: 'none', border: 'none', outline: 'none', color: '#E8EAED', fontSize: 13, flex: 1, minWidth: 0 }}
                  />
                  {searchQuery && (
                    <button onClick={() => setSearchQuery('')} style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#6B7280', padding: 0, display: 'flex', alignItems: 'center' }}>
                      <CloseIcon size={12} aria-hidden="true" />
                    </button>
                  )}
                </div>
                {searchDropdown}
              </div>
            </div>
          ) : (
            <>
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

              <div style={{ color: '#9BA1AC', fontSize: 13, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flexShrink: 1 }}>
                <b style={{ color: '#E8EAED', fontWeight: 600 }}>{current.label}</b>
              </div>
              <div style={{ flex: 1, minWidth: 0 }} />
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
                {searchDropdown}
              </div>
              {/* Collapsed search icon — shown only ≤767px where the bar doesn't fit; expands
                  into the full-width mobile search row above instead of doing nothing. */}
              <button
                className="nf-topbar-search-icon"
                aria-label="Search this workspace"
                onClick={() => { setMobileSearchOpen(true); setSearchOpen(true); setTimeout(() => searchInputRef.current?.focus(), 50); }}
                style={{ background: 'transparent', border: 'none', cursor: 'pointer', alignItems: 'center', justifyContent: 'center', padding: 7, borderRadius: 6, color: '#9BA1AC' }}
              >
                <Search size={15} aria-hidden="true" />
              </button>

              <Link
                to="/notifications"
                aria-label={`Notifications${unreadCount > 0 ? ` (${unreadCount} unread)` : ''}`}
                className="nf-topbar-item"
                style={{ position: 'relative', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 7, borderRadius: 6, color: 'inherit', textDecoration: 'none' }}
              >
                <Bell size={19} aria-hidden="true" />
                {unreadCount > 0 && (
                  // Deliberately var(--risk), not the accent-following var(--brand-bright) — an
                  // unread-count badge reads as an attention/urgency signal, so it stays red
                  // regardless of the user's chosen Theme color (same reasoning as "Sign out").
                  <span style={{ position: 'absolute', top: 3, right: 3, minWidth: 14, height: 14, borderRadius: 7, background: 'var(--risk)', color: '#fff', fontSize: 9, fontWeight: 700, display: 'grid', placeItems: 'center', padding: '0 3px', lineHeight: 1 }}>
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
                  <EmployeeAvatar
                    photoDataUrl={storeUser?.photoDataUrl}
                    name={name}
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
                      <Shield size={8} color="var(--brand-bright)" aria-hidden="true" />
                    </span>
                  )}
                </button>

                {dropdownOpen && (
                  <ProfileDropdown
                    name={name}
                    email={email}
                    role={role}
                    photoDataUrl={storeUser?.photoDataUrl}
                    onClose={() => setDropdownOpen(false)}
                  />
                )}
              </div>
            </>
          )}
        </header>

        <main className="nf-main-content" style={{ flex: 1, padding: 26, background: 'var(--shell)', color: 'var(--txt)' }}>
          <ComplianceBanner />
          {isNavItemDisabled(current) ? <ComingInPhase label={current.label} phase={navItemDisplayPhase(current)} /> : <Outlet />}
        </main>
      </div>

      <WorkAnniversaryOverlay
        open={anniversaryYears !== null}
        employeeName={name}
        photoDataUrl={storeUser?.photoDataUrl}
        years={anniversaryYears ?? 0}
        onClose={() => setAnniversaryYears(null)}
      />

      <KudosCelebrationToast
        items={kudosQueue}
        onDismiss={(id) => setKudosQueue(prev => prev.filter(k => k.id !== id))}
      />

      {/* Outside <main> so the panel is not inside its 26px padding or the sticky header's
          stacking context, and last in the tree so it layers without changing anything above it.
          currentPageId reuses `current.key`, already computed for the sidebar - the assistant
          never derives the page itself, and the server re-validates whatever arrives. */}
      <AssistantLauncher currentPageId={current.key} />
    </div>
  );
}

import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import {
  GitBranch, Search, Users, User, ArrowUp, Home, ChevronRight,
  Download, FileText, X, AlertCircle, ChevronDown, Building2, Layers, Crown,
  ZoomIn, ZoomOut, Maximize2, UserCheck, Briefcase, Hash, Activity,
} from 'lucide-react';
import { hierarchyApi, type HierarchyNode, type OrgContext, type PersonCard, type BreadcrumbEntry } from '../api/hierarchy';
import { profileApi } from '../api/profile';
import { useAuthStore } from '../store/authStore';
import logoUrl from '../assets/nforce-logo.png';
import { StatusBadge, inactiveDimStyle } from '../components/EmployeeStatus';
import { EmployeeAvatar, getInitials, waitForAvatarPhotos } from '../components/EmployeeAvatar';
import './HierarchyPage.css';

const PEER_MAX = 8;
// Fluid card width — a genuine linear function of viewport width (not a plain vw%, which
// saturates at its ceiling well before typical desktop widths), anchored so phones still get a
// readable card while wide desktops get the roomier card the badges/labels need. Continuous
// across the whole range, including mid-resize.
const CARD_W = 'clamp(168px, calc(124px + 4vw), 204px)';
const CARD_GAP = 14;

const ZOOM_MIN = 0.5;
const ZOOM_MAX = 1.5;
const ZOOM_STEP = 0.1;

/* ── Role colour mapping (same hues as UserManagementPage, via the theme tokens so each one
      stays WCAG-legible in both light and dark themes) ─────────────────────────────────── */
const ROLE_COLOR: Record<string, string> = {
  SUPER_ADMIN: 'var(--risk)',
  HR_ADMIN:    'var(--warn)',
  MANAGER:     'var(--info)',
  EMPLOYEE:    'var(--ok)',
};
const ROLE_LABEL: Record<string, string> = {
  SUPER_ADMIN: 'Super Admin', HR_ADMIN: 'HR Admin', MANAGER: 'Manager', EMPLOYEE: 'Employee',
};

/* ── Department colour mapping ─────────────────────────────────────────── */
const DEPT_COLORS: Record<string, string> = {
  Engineering: 'var(--info)',
  Sales:       'var(--warn)',
  HR:          'var(--ok)',
  Marketing:   'var(--risk)',
  Finance:     'var(--om-purple)',
  Operations:  'var(--warn)',
  Design:      'var(--om-rose)',
  Product:     'var(--om-cyan)',
  Legal:       'var(--txt-mut)',
};
const DEFAULT_DEPT = 'var(--brand-bright)';

function deptColor(dept: string | null | undefined): string {
  if (!dept) return DEFAULT_DEPT;
  const key = Object.keys(DEPT_COLORS).find(k => dept.toLowerCase().includes(k.toLowerCase()));
  return key ? DEPT_COLORS[key] : DEFAULT_DEPT;
}

/* Department filter — the value used for people with no department assigned. */
const ALL_DEPTS = '';
const NO_DEPT = '\u0000none';
const deptKey = (dept: string | null | undefined) => dept ?? NO_DEPT;

/* ── Dev safeguard: warn if same person appears in multiple tiers ──────── */
function assertNoDuplicateTiers(ctx: OrgContext) {
  if (import.meta.env.PROD) return;
  const ids: string[] = [
    ...(ctx.manager ? [ctx.manager.id] : []),
    ...ctx.peers.map(p => p.id),
    ...ctx.directReports.map(d => d.id),
  ];
  const seen = new Set<string>();
  for (const id of ids) {
    if (seen.has(id)) {
      console.warn(
        `[HierarchyPage] ⚠ DUPLICATE PERSON IN VIEW: id=${id} appears in multiple tiers. ` +
        `Tiers: manager=${ctx.manager?.id}, peers=[${ctx.peers.map(p => p.id).join(',')}], ` +
        `directReports=[${ctx.directReports.map(d => d.id).join(',')}]`
      );
    }
    seen.add(id);
  }
}

/* ── Summary: number of reporting levels, read straight off the managerId links the backend
      already returns. Display-only — never used to place anyone in the chart. A manager id that
      isn't in the list ends the chain, and a cycle is cut at the first repeated person so a bad
      assignment can't hang the page. ───────────────────────────────────────────────────── */
function countHierarchyLevels(nodes: HierarchyNode[]): number {
  const managerOf = new Map(nodes.map(n => [n.userId, n.managerId]));
  const depth = new Map<string, number>();
  let max = 0;
  for (const n of nodes) {
    const path: string[] = [];
    const seen = new Set<string>();
    let cur: string | null = n.userId;
    let base = 0;
    while (cur && managerOf.has(cur)) {
      const known = depth.get(cur);
      if (known !== undefined) { base = known; break; }
      if (seen.has(cur)) break; // cycle guard
      seen.add(cur);
      path.push(cur);
      cur = managerOf.get(cur) ?? null;
    }
    for (let i = path.length - 1; i >= 0; i--) depth.set(path[i], ++base);
    max = Math.max(max, depth.get(n.userId) ?? 0);
  }
  return max;
}

/* ── Pill badge ────────────────────────────────────────────────────────── */
function Pill({ color, children }: { color: string; children: React.ReactNode }) {
  return (
    <span className="nf-hier-pill" style={{ '--pill': color } as React.CSSProperties}>
      {children}
    </span>
  );
}

function RolePill({ role }: { role?: string }) {
  if (!role) return null;
  return <Pill color={ROLE_COLOR[role] ?? 'var(--txt-mut)'}>{ROLE_LABEL[role] ?? role}</Pill>;
}

/* ── Avatar ────────────────────────────────────────────────────────────── */
function Avatar({ card, size = 42, isFocus = false }: { card: PersonCard; size?: number; isFocus?: boolean }) {
  const c = deptColor(card.department);
  return (
    <EmployeeAvatar
      userId={card.id}
      name={card.name}
      size={size}
      fontSize={size * 0.36}
      background={isFocus ? 'var(--brand)' : `color-mix(in srgb, ${c} 14%, var(--panel))`}
      color={isFocus ? '#fff' : c}
      border={isFocus ? '2px solid color-mix(in srgb, var(--brand-bright) 45%, transparent)' : `1.5px solid color-mix(in srgb, ${c} 28%, transparent)`}
      style={{ fontFamily: 'Inter, sans-serif' }}
    />
  );
}

/* ── Skeleton card ─────────────────────────────────────────────────────── */
function SkeletonCard({ wide = false }: { wide?: boolean }) {
  return (
    <div className="nf-hier-skel" style={{ width: wide ? `calc(${CARD_W} + 20px)` : CARD_W }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <div className="nf-hier-skel-bar" style={{ width: 36, height: 36, borderRadius: '50%', flexShrink: 0 }} />
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6 }}>
          <div className="nf-hier-skel-bar" style={{ width: '75%' }} />
          <div className="nf-hier-skel-bar" style={{ width: '50%', height: 7 }} />
        </div>
      </div>
      <div className="nf-hier-skel-bar" style={{ width: '40%', height: 14, borderRadius: 20 }} />
    </div>
  );
}

/* ── Detail drawer ─────────────────────────────────────────────────────── */
function DetailDrawer({ card, onClose }: { card: PersonCard; onClose: () => void }) {
  return (
    <>
      <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.3)', zIndex: 298 }} />
      <div className="nf-drawer-responsive" style={{
        position: 'fixed', top: 0, right: 0, bottom: 0, width: 320,
        background: 'var(--panel)', borderLeft: '1px solid var(--line)',
        boxShadow: '-12px 0 40px rgba(0,0,0,.28)', zIndex: 299,
        display: 'flex', flexDirection: 'column', fontFamily: 'Inter, sans-serif',
      }}>
        <div style={{ padding: '16px 18px', borderBottom: '1px solid var(--line)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)' }}>Person Details</span>
          <button onClick={onClose} aria-label="Close" className="nf-hier-search-clear" style={{ position: 'static', transform: 'none', width: 28, height: 28 }}>
            <X size={15} />
          </button>
        </div>
        <div style={{ flex: 1, overflowY: 'auto', padding: '20px 18px', display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
            <Avatar card={card} size={52} isFocus={card.isFocus} />
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--txt)', marginBottom: 3 }}>{card.name}</div>
              <div style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>{card.designation ?? '—'}</div>
            </div>
          </div>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            <RolePill role={card.primaryRole} />
            {card.department && <Pill color={deptColor(card.department)}>{card.department}</Pill>}
          </div>
          <div style={{ border: '1px solid var(--line)', borderRadius: 12, overflow: 'hidden' }}>
            {[
              ['Department', card.department],
              ['Designation', card.designation],
              ['Direct Reports', card.directReportsCount > 0 ? `${card.directReportsCount} people` : 'None'],
              ['Status', card.active ? 'Active' : 'Inactive'],
            ].map(([label, value], i) => (
              <div key={label as string} style={{ padding: '11px 14px', borderTop: i ? '1px solid var(--line)' : 'none' }}>
                <div style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', marginBottom: 3 }}>{label}</div>
                <div style={{ fontSize: 13, color: value && value !== 'None' && value !== 'Inactive' ? 'var(--txt)' : 'var(--txt-dim)' }}>{value || '—'}</div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </>
  );
}

/* ── Person card ───────────────────────────────────────────────────────── */
function OrgCard({
  card, isFocusCard = false, onRecenter, onProfile, disabled = false, myUserId = null,
}: {
  card: PersonCard;
  isFocusCard?: boolean;
  onRecenter: () => void;
  onProfile: () => void;
  disabled?: boolean;
  myUserId?: string | null;
}) {
  const isMe = card.id === myUserId;
  return (
    <div
      className={`nf-hier-card${isFocusCard ? ' is-focus' : ''}${disabled ? '' : ' is-clickable'}`}
      style={{ width: CARD_W, ...inactiveDimStyle(card.active) }}
    >
      {/* YOU / FOCUS badge */}
      {(isMe || isFocusCard) && (
        <div className="nf-hier-card-tag" style={{ background: isMe ? 'var(--ok)' : 'var(--brand)' }}>
          {isMe ? 'YOU' : 'FOCUS'}
        </div>
      )}

      {/* Card body — clicking recenters */}
      <button
        className="nf-hier-card-main"
        onClick={disabled ? undefined : onRecenter}
        aria-label={`View ${card.name}'s team`}
        tabIndex={disabled ? -1 : 0}
      >
        <div className="nf-hier-card-top">
          <Avatar card={card} size={38} isFocus={isFocusCard} />
          <div style={{ minWidth: 0, flex: 1 }}>
            <div className="nf-hier-card-name" title={card.name}>{card.name}</div>
            {card.designation && (
              <div className="nf-hier-card-desig" title={card.designation}>{card.designation}</div>
            )}
          </div>
        </div>
        {(card.primaryRole || card.department || !card.active) && (
          <div className="nf-hier-card-badges">
            <RolePill role={card.primaryRole} />
            {card.department && <Pill color={deptColor(card.department)}>{card.department}</Pill>}
            {!card.active && <StatusBadge active={card.active} />}
          </div>
        )}
        {card.directReportsCount > 0 && (
          <div className="nf-hier-card-foot">
            <Users size={12} />
            <span>{card.directReportsCount} direct report{card.directReportsCount !== 1 ? 's' : ''}</span>
          </div>
        )}
      </button>

      {/* Profile icon — separate click from recenter */}
      <button
        className="nf-hier-card-profile"
        onClick={e => { e.stopPropagation(); onProfile(); }}
        aria-label={`View ${card.name}'s profile`}
        title="View profile"
        tabIndex={0}
      >
        <User size={13} />
      </button>
    </div>
  );
}

/* ── Connector lines ──────────────────────────────────────────────────── */
function VLine({ height = 36 }: { height?: number }) {
  return <div style={{ width: 2, height, background: 'var(--hier-conn)', margin: '0 auto', borderRadius: 1 }} />;
}

/* ── Roots picker modal ───────────────────────────────────────────────── */
function RootsPicker({ roots, onPick, onClose }: {
  roots: PersonCard[];
  onPick: (id: string) => void;
  onClose: () => void;
}) {
  return (
    <>
      <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.4)', zIndex: 398 }} />
      <div className="nf-hier" style={{
        position: 'fixed', top: '50%', left: '50%', transform: 'translate(-50%,-50%)',
        background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 16,
        boxShadow: '0 24px 56px rgba(0,0,0,.4)', zIndex: 399, gap: 0,
        width: 'min(380px, calc(100vw - 32px))', maxHeight: '70vh',
      }}>
        <div style={{ padding: '16px 18px', borderBottom: '1px solid var(--line)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div>
            <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)' }}>View from the top</div>
            <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 2 }}>People with no reporting manager</div>
          </div>
          <button onClick={onClose} aria-label="Close" className="nf-hier-search-clear" style={{ position: 'static', transform: 'none', width: 28, height: 28 }}>
            <X size={15} />
          </button>
        </div>
        <div style={{ overflowY: 'auto', padding: '12px', display: 'flex', flexDirection: 'column', gap: 8 }}>
          {roots.map(r => (
            <button
              key={r.id}
              className="nf-hier-action"
              onClick={() => { onPick(r.id); onClose(); }}
              style={inactiveDimStyle(r.active)}
            >
              <Avatar card={r} size={34} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <div className="nf-hier-action-title" style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.name}</div>
                  {!r.active && <StatusBadge active={r.active} />}
                </div>
                <div className="nf-hier-action-sub">{r.designation ?? r.department ?? 'Root'}</div>
              </div>
              {r.directReportsCount > 0 && (
                <div style={{ marginLeft: 'auto', fontSize: 11.5, color: 'var(--txt-mut)', display: 'flex', alignItems: 'center', gap: 4, fontWeight: 600 }}>
                  <Users size={12} />{r.directReportsCount}
                </div>
              )}
            </button>
          ))}
          {roots.length === 0 && <div className="nf-hier-drop-empty">No top-level people found.</div>}
        </div>
      </div>
    </>
  );
}

/* ── Breadcrumb ──────────────────────────────────────────────────────────
   Never wraps internally — crumbs + separators are one nowrap run. If the whole trail is too
   wide to share a line with the toolbar buttons, the *entire* breadcrumb moves to its own line
   (handled by its parent row, see the chart toolbar) rather than splitting mid-trail. Only a
   pathologically deep chain that overflows even a full-width line falls back to a horizontal
   scroll instead of breaking the single line.
─────────────────────────────────────────────────────────────────────────── */
function Breadcrumb({ crumbs, onJump }: { crumbs: BreadcrumbEntry[]; onJump: (id: string) => void }) {
  if (crumbs.length === 0) return null;
  return (
    <nav aria-label="Reporting chain" style={{ display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'nowrap', whiteSpace: 'nowrap' }}>
      {crumbs.map((c, i) => {
        const isCurrent = i === crumbs.length - 1;
        return (
          <React.Fragment key={c.id}>
            {i > 0 && <ChevronRight size={12} style={{ color: 'var(--txt-dim)', flexShrink: 0 }} />}
            <button
              className={`nf-hier-crumb${isCurrent ? ' is-current' : ''}`}
              onClick={() => onJump(c.id)}
              aria-current={isCurrent ? 'page' : undefined}
              tabIndex={isCurrent ? -1 : 0}
            >
              {c.name}
            </button>
          </React.Fragment>
        );
      })}
    </nav>
  );
}

/* ── "+N more peers" expand button ──────────────────────────────────── */
function MoreButton({ count, onClick }: { count: number; onClick: () => void }) {
  return (
    <button className="nf-hier-more" onClick={onClick}>
      <ChevronDown size={13} />
      +{count} more peers
    </button>
  );
}

/* ── Summary card ─────────────────────────────────────────────────────── */
function StatCard({ icon, label, value, sub, accent }: {
  icon: React.ReactNode;
  label: string;
  value: number | null;
  sub: string;
  accent: string;
}) {
  return (
    <div className="nf-hier-stat" style={{ '--stat-accent': accent } as React.CSSProperties}>
      <div className="nf-hier-stat-icon">{icon}</div>
      <div style={{ minWidth: 0 }}>
        <div className="nf-hier-stat-label">{label}</div>
        <div className="nf-hier-stat-value">
          {value === null ? <span className="nf-hier-stat-skel" aria-label="Loading" /> : value.toLocaleString()}
        </div>
        <div className="nf-hier-stat-sub" title={sub}>{sub}</div>
      </div>
    </div>
  );
}

/* ── Main page ─────────────────────────────────────────────────────────── */
export default function HierarchyPage() {
  const token = useAuthStore(s => s.token)!;
  const [myUserId, setMyUserId] = useState<string | null>(null);
  const [focusId, setFocusId] = useState<string | null>(null);
  const [ctx, setCtx] = useState<OrgContext | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingCtx, setLoadingCtx] = useState(false);
  const [noEmployeeRecord, setNoEmployeeRecord] = useState(false);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<PersonCard | null>(null);
  const [roots, setRoots] = useState<PersonCard[]>([]);
  const [rootsLoaded, setRootsLoaded] = useState(false);
  const [showRoots, setShowRoots] = useState(false);
  const [search, setSearch] = useState('');
  const [searchResults, setSearchResults] = useState<PersonCard[]>([]);
  const [allNodes, setAllNodes] = useState<PersonCard[]>([]);
  // Raw list as returned by /api/org/hierarchy — only read for the summary figures.
  const [listNodes, setListNodes] = useState<HierarchyNode[] | null>(null);
  const [showSearchDrop, setShowSearchDrop] = useState(false);
  const [peersExpanded, setPeersExpanded] = useState(false);
  const [deptFilter, setDeptFilter] = useState<string>(ALL_DEPTS);
  const [showAllDepts, setShowAllDepts] = useState(false);
  const [zoom, setZoom] = useState(1);

  // Measured connector line state (computed from real DOM positions)
  const [connLeft, setConnLeft] = useState(0);
  const [connWidth, setConnWidth] = useState(0);
  // Direct-reports connector — spans first report's center to last report's center, measured
  // from the real rendered row rather than derived from CARD_W arithmetic, since CARD_W is a
  // fluid clamp() and can't be resolved to a number in JS. Same approach as the peer row above.
  const [reportsConnLeft, setReportsConnLeft] = useState(0);
  const [reportsConnWidth, setReportsConnWidth] = useState(0);

  const chartRef           = useRef<HTMLDivElement>(null);
  const searchRef          = useRef<HTMLDivElement>(null);
  const searchInputRef     = useRef<HTMLInputElement>(null);
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const zoomRef            = useRef<HTMLDivElement>(null);
  const firstPeerRef       = useRef<HTMLDivElement>(null);
  const lastPeerRef        = useRef<HTMLDivElement>(null);
  const peerRowRef         = useRef<HTMLDivElement>(null);
  const reportsRowRef      = useRef<HTMLDivElement>(null);

  /* Reset expand state when navigating */
  useEffect(() => { setPeersExpanded(false); }, [focusId]);

  /* ── Derived peer data — strictly from real backend data, no role merging */
  const sortedPeers: PersonCard[] = ctx
    ? [...ctx.peers].sort((a, b) => b.directReportsCount - a.directReportsCount)
    : [];

  const focusIdxInSorted = sortedPeers.findIndex(p => p.isFocus);

  let visiblePeers: PersonCard[];
  if (!ctx || peersExpanded || sortedPeers.length <= PEER_MAX) {
    visiblePeers = sortedPeers;
  } else {
    const first8 = sortedPeers.slice(0, PEER_MAX);
    // Guarantee focus person is always visible even if sorted past slot 8
    if (focusIdxInSorted < PEER_MAX || focusIdxInSorted < 0) {
      visiblePeers = first8;
    } else {
      visiblePeers = [...first8, sortedPeers[focusIdxInSorted]];
    }
  }
  const hiddenCount = sortedPeers.length - visiblePeers.length;

  // Stable key for useLayoutEffect — changes only when the visible set actually changes
  const connectorKey = visiblePeers.map(p => p.id).join(',') + '|' + String(peersExpanded);

  /* ── Measure peer-row + direct-reports connector lines from real DOM positions ───────────
     Card widths are a fluid clamp() of viewport width (see CARD_W), so these positions shift
     on every window resize, not just when the underlying data changes — re-measure on both a
     data-driven layout pass and on window resize, not connectorKey alone. Measured with
     offsetLeft/offsetWidth (layout px of the peer row itself) rather than bounding rects, so
     the result is independent of the chart's visual zoom level. */
  const measureConnectors = useCallback(() => {
    if (!peerRowRef.current || !firstPeerRef.current || visiblePeers.length < 2) {
      setConnLeft(0);
      setConnWidth(0);
    } else {
      const firstEl = firstPeerRef.current;
      const lastEl  = lastPeerRef.current ?? firstPeerRef.current;

      // Center-X of each endpoint, relative to peerRowRef's left edge (its offsetParent)
      const leftCenter  = firstEl.offsetLeft + firstEl.offsetWidth / 2;
      const rightCenter = lastEl.offsetLeft  + lastEl.offsetWidth  / 2;

      setConnLeft(leftCenter);
      setConnWidth(Math.max(0, rightCenter - leftCenter));
    }

    const reportCols = reportsRowRef.current?.querySelectorAll(':scope > [data-report-col]');
    if (reportCols && ctx && ctx.directReports.length >= 2 && reportCols.length >= 2) {
      const first = reportCols[0] as HTMLElement;
      const last  = reportCols[reportCols.length - 1] as HTMLElement;
      const left  = first.offsetLeft + first.offsetWidth / 2;
      setReportsConnLeft(left);
      setReportsConnWidth(Math.max(0, last.offsetLeft + last.offsetWidth / 2 - left));
    } else {
      setReportsConnLeft(0);
      setReportsConnWidth(0);
    }
  }, [visiblePeers, ctx]);

  useLayoutEffect(() => { measureConnectors(); }, [connectorKey, measureConnectors]);

  useEffect(() => {
    window.addEventListener('resize', measureConnectors);
    return () => window.removeEventListener('resize', measureConnectors);
  }, [measureConnectors]);

  /* ── Auto-scroll: center focus card in the scroll container ─────────── */
  useLayoutEffect(() => {
    const container = scrollContainerRef.current;
    if (!container) return;
    const focusEl = container.querySelector<HTMLElement>('[data-focus-peer]');
    if (!focusEl) return;
    const containerRect = container.getBoundingClientRect();
    const focusRect     = focusEl.getBoundingClientRect();
    const focusCenter   = focusRect.left - containerRect.left + container.scrollLeft + focusRect.width / 2;
    container.scrollLeft = focusCenter - container.clientWidth / 2;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connectorKey, zoom]);

  /* ── Side effects ────────────────────────────────────────────────────── */
  useEffect(() => {
    profileApi.get(token)
      .then(p => {
        setMyUserId(p.userId);
        if (p.hasEmployeeRecord) {
          setFocusId(p.userId);
        } else {
          setNoEmployeeRecord(true);
          setLoading(false);
          setShowRoots(true);
        }
      })
      .catch(() => setError('Could not load your profile'));
  }, [token]);

  useEffect(() => {
    hierarchyApi.list(token)
      .then(nodes => {
        setListNodes(nodes);
        setAllNodes(nodes.map(n => ({
          id: n.userId, name: n.fullName,
          designation: n.designationName, department: n.departmentName,
          initials: getInitials(n.fullName), directReportsCount: 0, isFocus: false, active: n.active,
        })));
      })
      .catch(() => {});
  }, [token]);

  useEffect(() => {
    hierarchyApi.getRoots(token).then(r => { setRoots(r); setRootsLoaded(true); }).catch(() => {});
  }, [token]);

  useEffect(() => {
    if (!focusId) return;
    setLoadingCtx(true);
    setError('');
    hierarchyApi.getContext(token, focusId)
      .then(data => {
        assertNoDuplicateTiers(data);
        setCtx(data);
        setNoEmployeeRecord(false);
        setLoading(false);
      })
      .catch(() => {
        setLoading(false);
        setNoEmployeeRecord(true);
        setShowRoots(true);
      })
      .finally(() => setLoadingCtx(false));
  }, [token, focusId]);

  /* Search — unchanged matching (name / designation / department, top 8). The department filter
     only narrows the pool searched; with a department picked and no query, the dropdown lists
     everyone in that department so it can be browsed directly. */
  useEffect(() => {
    const q = search.trim().toLowerCase();
    const pool = deptFilter === ALL_DEPTS ? allNodes : allNodes.filter(n => deptKey(n.department) === deptFilter);
    if (!q) {
      setSearchResults(deptFilter === ALL_DEPTS ? [] : [...pool].sort((a, b) => a.name.localeCompare(b.name)));
      return;
    }
    setSearchResults(pool.filter(n =>
      n.name.toLowerCase().includes(q) ||
      (n.designation ?? '').toLowerCase().includes(q) ||
      (n.department ?? '').toLowerCase().includes(q)
    ).slice(0, 8));
  }, [search, allNodes, deptFilter]);

  const recenter = useCallback((id: string) => {
    setFocusId(id);
    setNoEmployeeRecord(false);
    setSearch('');
    setShowSearchDrop(false);
  }, []);

  const jumpToMe = useCallback(() => {
    if (myUserId) recenter(myUserId);
  }, [myUserId, recenter]);

  const viewFromTop = () => { if (roots.length === 1) recenter(roots[0].id); else setShowRoots(true); };

  /* ── Summary figures — all derived from the existing list/roots responses ── */
  const summary = useMemo(() => {
    if (!listNodes) return null;
    const active = listNodes.filter(n => n.active).length;
    const counts = new Map<string, { name: string | null; count: number }>();
    for (const n of listNodes) {
      const k = deptKey(n.departmentName);
      const entry = counts.get(k);
      if (entry) entry.count++;
      else counts.set(k, { name: n.departmentName, count: 1 });
    }
    const departments = [...counts.entries()]
      .map(([key, v]) => ({ key, ...v }))
      .sort((a, b) => b.count - a.count || (a.name ?? '').localeCompare(b.name ?? ''));
    return {
      total: listNodes.length,
      active,
      inactive: listNodes.length - active,
      departments,
      namedDeptCount: departments.filter(d => d.name !== null).length,
      unassigned: counts.get(NO_DEPT)?.count ?? 0,
      levels: countHierarchyLevels(listNodes),
    };
  }, [listNodes]);

  const deptOptions = summary?.departments ?? [];
  const deptFilterLabel = deptFilter === NO_DEPT
    ? 'No department'
    : deptFilter;

  const applyDeptFilter = (key: string) => {
    setDeptFilter(key);
    if (key !== ALL_DEPTS) {
      setShowSearchDrop(true);
      searchInputRef.current?.focus();
    }
  };

  /* ── Zoom ─────────────────────────────────────────────────────────────── */
  const clampZoom = (z: number) => Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, Math.round(z * 100) / 100));
  const zoomBy = (delta: number) => setZoom(z => clampZoom(z + delta));
  const zoomToFit = () => {
    const container = scrollContainerRef.current;
    const content = zoomRef.current;
    if (!container || !content) return;
    const style = getComputedStyle(container);
    const available = container.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
    const natural = content.scrollWidth; // layout width, independent of the applied zoom
    if (natural <= 0) return;
    setZoom(clampZoom(Math.min(1, Math.floor((available / natural) * 20) / 20)));
  };

  /* ── Export ──────────────────────────────────────────────────────────── */
  // Breadcrumb is outside chartRef DOM — drawn from ctx data directly into canvas.
  async function buildExportCanvas(): Promise<HTMLCanvasElement> {
    await document.fonts.ready;

    // A click can land while an EmployeeAvatar mounted moments earlier is still waiting on its
    // photo fetch — capturing then would snapshot the pre-photo initials fallback, exporting a
    // blank avatar for someone who does have a photo on file. Wait for every visible person's
    // fetch to settle, then let React actually paint the result before handing the DOM to
    // html2canvas (awaiting the fetch alone doesn't guarantee the resulting state update has
    // been committed and rendered yet).
    await waitForAvatarPhotos([
      ctx?.manager?.id,
      ...(ctx?.peers.map(p => p.id) ?? []),
      ...(ctx?.directReports.map(d => d.id) ?? []),
    ]);
    await new Promise(requestAnimationFrame);
    await new Promise(requestAnimationFrame);

    const { default: html2canvas } = await import('html2canvas-pro');
    // Match whichever theme is actually on screen (light/dark/high-contrast) instead of a
    // hardcoded dark fill, so the exported chart background isn't wrong for non-dark themes.
    const panelBg = getComputedStyle(chartRef.current!).getPropertyValue('--panel').trim() || '#16181D';
    const chartCanvas = await html2canvas(chartRef.current!, {
      backgroundColor: panelBg, scale: 2, useCORS: true, logging: false,
      // The on-screen zoom is a viewing aid only — always export the chart at 100%.
      onclone: doc => {
        doc.querySelectorAll<HTMLElement>('[data-hier-zoom]').forEach(el => { el.style.zoom = '1'; });
      },
    });

    const HEADER = 96;
    const W = chartCanvas.width;
    const combined = document.createElement('canvas');
    combined.width = W;
    combined.height = chartCanvas.height + HEADER;
    const gfx = combined.getContext('2d')!;

    const grad = gfx.createLinearGradient(0, 0, W, 0);
    grad.addColorStop(0, '#050506');
    grad.addColorStop(0.4, '#6B0C10');
    grad.addColorStop(1, '#A01418');
    gfx.fillStyle = grad;
    gfx.fillRect(0, 0, W, HEADER);

    const logoImg = new Image();
    logoImg.src = logoUrl;
    await new Promise<void>(r => { logoImg.onload = () => r(); logoImg.onerror = () => r(); });
    if (logoImg.naturalWidth > 0) {
      const logoH = HEADER * 0.55;
      const logoW = logoH * (logoImg.naturalWidth / logoImg.naturalHeight);
      gfx.drawImage(logoImg, 24, (HEADER - logoH) / 2, logoW, logoH);
    }

    gfx.fillStyle = '#E8EAED';
    gfx.font = 'bold 24px "Inter", system-ui, sans-serif';
    const titleX = logoImg.naturalWidth > 0
      ? 24 + (HEADER * 0.55 * logoImg.naturalWidth / logoImg.naturalHeight) + 20
      : 24;
    gfx.fillText('NForce OneHR — Organization Hierarchy', titleX, HEADER / 2 - 4);

    const crumbText = ctx?.breadcrumb.map(b => b.name).join(' › ') ?? '';
    if (crumbText) {
      gfx.fillStyle = '#9BA1AC';
      gfx.font = '16px Inter, system-ui, sans-serif';
      gfx.fillText(crumbText, titleX, HEADER / 2 + 20);
    }

    gfx.drawImage(chartCanvas, 0, HEADER);
    return combined;
  }

  async function exportPng() {
    if (!chartRef.current) return;
    const canvas = await buildExportCanvas();
    const link = document.createElement('a');
    link.href = canvas.toDataURL('image/png');
    link.download = 'org-hierarchy.png';
    link.click();
  }

  async function exportPdf() {
    if (!chartRef.current) return;
    const canvas = await buildExportCanvas();
    const { jsPDF } = await import('jspdf');
    const W = canvas.width / 2;
    const H = canvas.height / 2;
    const pdf = new jsPDF({ orientation: W > H ? 'landscape' : 'portrait', unit: 'px', format: [W, H] });
    pdf.addImage(canvas.toDataURL('image/png'), 'PNG', 0, 0, W, H);
    pdf.save('org-hierarchy.pdf');
  }

  /* ── Direct reports tier (tier 3) ────────────────────────────────────── */
  function DirectReports() {
    if (!ctx) return null;
    if (ctx.directReports.length === 0) {
      return (
        <div className="nf-hier-noreports">
          <Users size={13} /> No direct reports
        </div>
      );
    }
    return (
      <>
        <VLine height={28} />
        {/* reportsRowRef is position:relative so the measured connector is contained within */}
        <div ref={reportsRowRef} style={{ position: 'relative', display: 'flex', gap: CARD_GAP }}>
          {ctx.directReports.length > 1 && reportsConnWidth > 0 && (
            <div style={{
              position: 'absolute', top: 0, left: reportsConnLeft, width: reportsConnWidth,
              height: 2, background: 'var(--hier-conn)', borderRadius: 1,
            }} />
          )}
          {ctx.directReports.map(dr => (
            <div key={dr.id} data-report-col style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
              <VLine height={18} />
              <OrgCard
                card={dr}
                onRecenter={() => recenter(dr.id)}
                onProfile={() => setDetail(dr)}
                myUserId={myUserId}
              />
            </div>
          ))}
        </div>
      </>
    );
  }

  const focusCard = ctx?.focusUser ?? null;
  const peerCount = ctx ? ctx.peers.filter(p => !p.isFocus).length : 0;
  const deptList = showAllDepts ? deptOptions : deptOptions.slice(0, 6);
  const deptMax = deptOptions[0]?.count ?? 1;

  /* ── Render ──────────────────────────────────────────────────────────── */
  return (
    <div className="nf-hier">

      {/* Header — title + department filter + person search */}
      <div className="nf-hier-head">
        <div>
          <h1 className="nf-hier-title">Organization Hierarchy</h1>
          <p className="nf-hier-subtitle">
            Click any card to re-center the view. Read-only — manage reporting lines in User Management.
          </p>
        </div>

        <div className="nf-hier-filters">
          <label className={`nf-hier-select${deptFilter !== ALL_DEPTS ? ' is-active' : ''}`}>
            <Building2 size={14} className="nf-hier-select-icon" />
            <select
              value={deptFilter}
              onChange={e => applyDeptFilter(e.target.value)}
              aria-label="Filter by department"
              disabled={!summary}
            >
              <option value={ALL_DEPTS}>All Departments</option>
              {deptOptions
                .filter(d => d.name !== null)
                .sort((a, b) => a.name!.localeCompare(b.name!))
                .map(d => <option key={d.key} value={d.key}>{d.name}</option>)}
              {summary && summary.unassigned > 0 && <option value={NO_DEPT}>No department</option>}
            </select>
            <ChevronDown size={14} className="nf-hier-select-caret" />
          </label>

          <div className="nf-hier-search" ref={searchRef}>
            <Search size={15} className="nf-hier-search-icon" />
            <input
              ref={searchInputRef}
              value={search}
              onChange={e => { setSearch(e.target.value); setShowSearchDrop(true); }}
              onFocus={() => setShowSearchDrop(true)}
              placeholder={deptFilter === ALL_DEPTS ? 'Jump to any person…' : `Search in ${deptFilterLabel}…`}
              aria-label="Search people"
            />
            {search && (
              <button className="nf-hier-search-clear" aria-label="Clear search" onClick={() => { setSearch(''); setShowSearchDrop(false); }}>
                <X size={13} />
              </button>
            )}
            {showSearchDrop && (searchResults.length > 0 || (deptFilter !== ALL_DEPTS && search.trim() !== '')) && (
              <div className="nf-hier-drop">
                {deptFilter !== ALL_DEPTS && (
                  <div className="nf-hier-drop-head" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {search.trim()
                        ? `Matches in ${deptFilterLabel}`
                        : `${searchResults.length} ${searchResults.length === 1 ? 'person' : 'people'} in ${deptFilterLabel}`}
                    </span>
                    <button className="nf-hier-link-btn" style={{ textTransform: 'none', letterSpacing: 0 }} onClick={() => setDeptFilter(ALL_DEPTS)}>
                      Clear filter
                    </button>
                  </div>
                )}
                <div className="nf-hier-drop-list">
                  {searchResults.map(r => (
                    <button
                      key={r.id}
                      className="nf-hier-drop-item"
                      onClick={() => recenter(r.id)}
                      style={inactiveDimStyle(r.active)}
                    >
                      <Avatar card={r} size={30} />
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.name}</div>
                          {!r.active && <StatusBadge active={r.active} />}
                        </div>
                        <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.designation ?? r.department ?? ''}</div>
                      </div>
                    </button>
                  ))}
                  {searchResults.length === 0 && <div className="nf-hier-drop-empty">No one in {deptFilterLabel} matches “{search.trim()}”.</div>}
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Summary cards */}
      <div className="nf-hier-stats">
        <StatCard
          icon={<Users size={20} />}
          accent="var(--info)"
          label="Total Employees"
          value={summary?.total ?? null}
          sub={summary ? `${summary.active.toLocaleString()} active · ${summary.inactive.toLocaleString()} inactive` : 'Loading…'}
        />
        <StatCard
          icon={<Building2 size={20} />}
          accent="var(--om-purple)"
          label="Departments"
          value={summary?.namedDeptCount ?? null}
          sub={summary ? (summary.unassigned > 0 ? `${summary.unassigned} ${summary.unassigned === 1 ? 'person' : 'people'} without a department` : 'Every employee is assigned') : 'Loading…'}
        />
        <StatCard
          icon={<Layers size={20} />}
          accent="var(--ok)"
          label="Hierarchy Levels"
          value={summary?.levels ?? null}
          sub="Reporting levels, top to bottom"
        />
        <StatCard
          icon={<Crown size={20} />}
          accent="var(--warn)"
          label="Top of Hierarchy"
          value={rootsLoaded ? roots.length : null}
          sub="People with no reporting manager"
        />
      </div>

      <div className="nf-hier-body">

        {/* ── Chart panel ─────────────────────────────────────────────────── */}
        <section className="nf-hier-panel nf-hier-chart" aria-label="Organization chart">
          {/* Breadcrumb + navigation + zoom. The breadcrumb is flexShrink:0 (never shrinks below
              its natural single-line width) — combined with the Breadcrumb component's own
              internal nowrap it can never be squeezed into wrapping mid-trail. When it and the
              buttons don't fit one row, flex-wrap moves the *entire* button block down as one
              unit. Below 767px the nav buttons use the shared .nf-hierarchy-actions 2-column
              grid (index.css) so they always fit the viewport. */}
          <div className="nf-hier-toolbar">
            <div className="nf-hier-crumbs">
              {ctx && ctx.breadcrumb.length > 1 && (
                <Breadcrumb crumbs={ctx.breadcrumb} onJump={recenter} />
              )}
            </div>
            <div className="nf-hier-toolbar-right">
              <div className="nf-hierarchy-actions" style={{ display: 'flex', gap: 8 }}>
                <button onClick={jumpToMe} className="nf-hier-btn" title="Return to your position">
                  <Home size={14} /> My Position
                </button>
                <button onClick={viewFromTop} className="nf-hier-btn">
                  <ArrowUp size={14} /> View from Top
                </button>
              </div>
              <div className="nf-hier-zoom" role="group" aria-label="Zoom">
                <button onClick={() => zoomBy(-ZOOM_STEP)} disabled={zoom <= ZOOM_MIN} aria-label="Zoom out" title="Zoom out">
                  <ZoomOut size={15} />
                </button>
                <button onClick={() => zoomBy(ZOOM_STEP)} disabled={zoom >= ZOOM_MAX} aria-label="Zoom in" title="Zoom in">
                  <ZoomIn size={15} />
                </button>
                <button className="nf-hier-zoom-pct" onClick={() => setZoom(1)} title="Reset to 100%" aria-label={`Zoom ${Math.round(zoom * 100)}%, reset to 100%`}>
                  {Math.round(zoom * 100)}%
                </button>
                <button onClick={zoomToFit} aria-label="Fit to width" title="Fit to width">
                  <Maximize2 size={14} />
                </button>
              </div>
            </div>
          </div>

          {/* ── Scrollable chart canvas ─────────────────────────────────────
              FIX for left-edge clipping: the inner wrapper has min-width:max-content so the
              scroll container can access ALL content in both directions. alignItems:center on
              the flex-column chartRef centers narrow tiers (manager card, etc.) within the
              max-content width. The zoom is applied to that same wrapper (CSS zoom scales
              layout, so scrolling keeps working at every level).
          ─────────────────────────────────────────────────────────────────── */}
          <div ref={scrollContainerRef} className="nf-hier-canvas">
            <div
              ref={zoomRef}
              data-hier-zoom
              style={{ minWidth: 'max-content', display: 'flex', justifyContent: 'center', zoom }}
            >

              {loading ? (
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 0 }}>
                  <SkeletonCard wide />
                  <VLine height={28} />
                  <div style={{ display: 'flex', gap: CARD_GAP }}>
                    {[1, 2, 3].map(i => <SkeletonCard key={i} />)}
                  </div>
                  <VLine height={28} />
                  <div style={{ display: 'flex', gap: CARD_GAP }}>
                    {[1, 2].map(i => <SkeletonCard key={i} />)}
                  </div>
                </div>
              ) : noEmployeeRecord ? (
                <div className="nf-hier-state">
                  <div className="nf-hier-state-icon"><GitBranch size={24} /></div>
                  <div className="nf-hier-state-title">Your account has no employee record</div>
                  <div className="nf-hier-state-text">Use “View from Top” to browse the org chart, or search for a person.</div>
                  <button onClick={viewFromTop} className="nf-hier-cta">
                    <ArrowUp size={15} /> View from Top
                  </button>
                </div>
              ) : error ? (
                <div className="nf-hier-state">
                  <div className="nf-hier-state-icon" style={{ color: 'var(--risk)', background: 'color-mix(in srgb, var(--risk) 10%, transparent)' }}>
                    <AlertCircle size={24} />
                  </div>
                  <div className="nf-hier-state-title" style={{ color: 'var(--risk)' }}>{error}</div>
                  <div className="nf-hier-state-text">Check that the backend is running.</div>
                </div>
              ) : ctx ? (
                <div
                  ref={chartRef}
                  style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 0, paddingBottom: 8, paddingTop: 10 }}
                >

                  {/* ── Tier 1: Manager — strictly from ctx.manager (real data) ── */}
                  {ctx.manager ? (
                    <>
                      <OrgCard
                        card={ctx.manager}
                        onRecenter={() => recenter(ctx.manager!.id)}
                        onProfile={() => setDetail(ctx.manager)}
                        myUserId={myUserId}
                      />
                      <VLine height={28} />
                    </>
                  ) : (
                    /* Focus person is a true root — tier above is completely empty */
                    <div className="nf-hier-root-label">
                      <GitBranch size={13} /> Organisation Root
                    </div>
                  )}

                  {/* ── Tier 2: Peers — all peers in ONE flat row, exactly as returned by API ── */}
                  {/* No role-based splitting. Mixed roles (SA + Manager etc.) render side-by-side. */}
                  {loadingCtx ? (
                    <div style={{ display: 'flex', gap: CARD_GAP, justifyContent: 'center' }}>
                      {[1, 2, 3].map(i => (
                        <div key={i} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
                          {ctx.manager && <VLine height={18} />}
                          <SkeletonCard />
                        </div>
                      ))}
                    </div>
                  ) : (
                    /* peerRowRef is position:relative so absolute HLine is contained within */
                    <div
                      ref={peerRowRef}
                      style={{ position: 'relative', display: 'flex', gap: CARD_GAP, alignItems: 'flex-start' }}
                    >
                      {/* Measured horizontal connector — spans first card center to last card center */}
                      {visiblePeers.length > 1 && connWidth > 0 && ctx.manager && (
                        <div style={{
                          position: 'absolute',
                          top: 0,
                          left: connLeft,
                          width: connWidth,
                          height: 2,
                          background: 'var(--hier-conn)',
                          borderRadius: 1,
                          zIndex: 0,
                        }} />
                      )}

                      {/* Peer columns — sorted by directReportsCount DESC, focus guaranteed visible */}
                      {visiblePeers.map((peer, idx) => {
                        const isFirst = idx === 0;
                        const isLast  = hiddenCount === 0 && idx === visiblePeers.length - 1;
                        return (
                          <div
                            key={peer.id}
                            ref={isFirst ? firstPeerRef : isLast ? lastPeerRef : undefined}
                            data-focus-peer={peer.isFocus ? 'true' : undefined}
                            style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', position: 'relative', zIndex: 1 }}
                          >
                            {ctx.manager && <VLine height={18} />}
                            <OrgCard
                              card={peer}
                              isFocusCard={peer.isFocus}
                              onRecenter={peer.isFocus ? () => {} : () => recenter(peer.id)}
                              onProfile={() => setDetail(peer)}
                              disabled={peer.isFocus}
                              myUserId={myUserId}
                            />
                            {/* Tier 3: direct reports drop below the focus card only */}
                            {peer.isFocus && <DirectReports />}
                          </div>
                        );
                      })}

                      {/* "+N more peers" pill — also measured as lastPeerRef */}
                      {hiddenCount > 0 && (
                        <div
                          ref={lastPeerRef}
                          style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', position: 'relative', zIndex: 1 }}
                        >
                          {ctx.manager && <VLine height={18} />}
                          <MoreButton count={hiddenCount} onClick={() => setPeersExpanded(true)} />
                        </div>
                      )}
                    </div>
                  )}

                  {/* Edge case: focus person not in visiblePeers (hidden behind +N more) */}
                  {!loadingCtx && !visiblePeers.some(p => p.isFocus) && <DirectReports />}

                </div>
              ) : null}

            </div>{/* end min-width:max-content / zoom wrapper */}
          </div>
        </section>

        {/* ── Side panels ─────────────────────────────────────────────────── */}
        <aside className="nf-hier-side">

          {/* Current focus — every value comes from the loaded context */}
          {focusCard && ctx && !noEmployeeRecord && (
            <div className="nf-hier-panel">
              <div className="nf-hier-panel-head">
                <h2 className="nf-hier-panel-title">In Focus</h2>
                <button className="nf-hier-link-btn" onClick={() => setDetail(focusCard)}>View details</button>
              </div>
              <div className="nf-hier-panel-body">
                <div className="nf-hier-focus-person" style={inactiveDimStyle(focusCard.active)}>
                  <Avatar card={focusCard} size={46} isFocus />
                  <div style={{ minWidth: 0 }}>
                    <div className="nf-hier-card-name" style={{ fontSize: 14 }}>{focusCard.name}</div>
                    <div className="nf-hier-card-desig" style={{ fontSize: 12 }}>{focusCard.designation ?? '—'}</div>
                  </div>
                </div>
                {(focusCard.primaryRole || focusCard.department) && (
                  <div className="nf-hier-card-badges" style={{ marginTop: 12 }}>
                    <RolePill role={focusCard.primaryRole} />
                    {focusCard.department && <Pill color={deptColor(focusCard.department)}>{focusCard.department}</Pill>}
                  </div>
                )}
                <ul className="nf-hier-facts">
                  <li className="nf-hier-fact">
                    <UserCheck size={14} className="nf-hier-fact-icon" />
                    <span className="nf-hier-fact-label">Reports to</span>
                    <span className="nf-hier-fact-value">
                      {ctx.manager
                        ? <button className="nf-hier-fact-link" onClick={() => recenter(ctx.manager!.id)} title={`View ${ctx.manager.name}'s team`}>{ctx.manager.name}</button>
                        : <span style={{ color: 'var(--txt-dim)', fontWeight: 500 }}>No manager</span>}
                    </span>
                  </li>
                  <li className="nf-hier-fact">
                    <Users size={14} className="nf-hier-fact-icon" />
                    <span className="nf-hier-fact-label">Direct reports</span>
                    <span className="nf-hier-fact-value">{ctx.directReports.length}</span>
                  </li>
                  <li className="nf-hier-fact">
                    <Briefcase size={14} className="nf-hier-fact-icon" />
                    <span className="nf-hier-fact-label">{ctx.manager ? 'Peers' : 'Other top-level'}</span>
                    <span className="nf-hier-fact-value">{peerCount}</span>
                  </li>
                  <li className="nf-hier-fact">
                    <Hash size={14} className="nf-hier-fact-icon" />
                    <span className="nf-hier-fact-label">Level</span>
                    <span className="nf-hier-fact-value">{Math.max(1, ctx.breadcrumb.length)}</span>
                  </li>
                  <li className="nf-hier-fact">
                    <Activity size={14} className="nf-hier-fact-icon" />
                    <span className="nf-hier-fact-label">Status</span>
                    <span className="nf-hier-fact-value" style={{ color: focusCard.active ? 'var(--ok)' : 'var(--risk)' }}>
                      {focusCard.active ? 'Active' : 'Inactive'}
                    </span>
                  </li>
                </ul>
              </div>
            </div>
          )}

          {/* Export — the page's existing PNG / PDF export */}
          <div className="nf-hier-panel">
            <div className="nf-hier-panel-head">
              <h2 className="nf-hier-panel-title">Export Org Chart</h2>
            </div>
            <div className="nf-hier-panel-body nf-hier-actions">
              <button className="nf-hier-action" onClick={exportPng}>
                <span className="nf-hier-action-icon"><Download size={16} /></span>
                <span>
                  <div className="nf-hier-action-title">Download PNG</div>
                  <div className="nf-hier-action-sub">High-resolution image of this view</div>
                </span>
              </button>
              <button className="nf-hier-action" onClick={exportPdf}>
                <span className="nf-hier-action-icon"><FileText size={16} /></span>
                <span>
                  <div className="nf-hier-action-title">Download PDF</div>
                  <div className="nf-hier-action-sub">Print-ready document of this view</div>
                </span>
              </button>
            </div>
          </div>

          {/* Department headcount — from the existing hierarchy list; click to filter */}
          {summary && deptOptions.length > 0 && (
            <div className="nf-hier-panel">
              <div className="nf-hier-panel-head">
                <h2 className="nf-hier-panel-title">Departments</h2>
                {deptFilter !== ALL_DEPTS && (
                  <button className="nf-hier-link-btn" onClick={() => setDeptFilter(ALL_DEPTS)}>Clear filter</button>
                )}
              </div>
              <div className="nf-hier-panel-body">
                <div className="nf-hier-dept-list">
                  {deptList.map(d => {
                    const color = d.name ? deptColor(d.name) : 'var(--txt-dim)';
                    const isActive = deptFilter === d.key;
                    return (
                      <button
                        key={d.key}
                        className={`nf-hier-dept${isActive ? ' is-active' : ''}`}
                        style={{ '--pill': color } as React.CSSProperties}
                        onClick={() => applyDeptFilter(isActive ? ALL_DEPTS : d.key)}
                        aria-pressed={isActive}
                        title={isActive ? 'Clear department filter' : `Show people in ${d.name ?? 'no department'}`}
                      >
                        <span className="nf-hier-dept-dot" />
                        <span className="nf-hier-dept-name" style={d.name ? undefined : { color: 'var(--txt-mut)', fontStyle: 'italic' }}>
                          {d.name ?? 'No department'}
                        </span>
                        <span className="nf-hier-dept-count">{d.count}</span>
                        <span className="nf-hier-dept-bar"><span style={{ width: `${(d.count / deptMax) * 100}%` }} /></span>
                      </button>
                    );
                  })}
                </div>
                {deptOptions.length > 6 && (
                  <button className="nf-hier-link-btn" style={{ marginTop: 10 }} onClick={() => setShowAllDepts(v => !v)}>
                    {showAllDepts ? 'Show fewer' : `Show all ${deptOptions.length}`}
                  </button>
                )}
              </div>
            </div>
          )}
        </aside>
      </div>

      {/* Modals */}
      {detail && <DetailDrawer card={detail} onClose={() => setDetail(null)} />}
      {showRoots && <RootsPicker roots={roots} onPick={recenter} onClose={() => setShowRoots(false)} />}
    </div>
  );
}

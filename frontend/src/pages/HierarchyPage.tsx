import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import {
  GitBranch, Search, Users, User, ArrowUp, Home, ChevronRight,
  Download, FileText, X, AlertCircle, ChevronDown,
} from 'lucide-react';
import { hierarchyApi, type OrgContext, type PersonCard, type BreadcrumbEntry } from '../api/hierarchy';
import { profileApi } from '../api/profile';
import { useAuthStore } from '../store/authStore';
import logoUrl from '../assets/nforce-logo.png';
import { StatusBadge, inactiveDimStyle } from '../components/EmployeeStatus';

const PEER_MAX = 8;
// Card width used for direct-reports HLine sizing
const CARD_W = 152;
const CARD_GAP = 10;

/* ── Role colour mapping (matches UserManagementPage exactly) ─────────── */
const ROLE_COLOR: Record<string, { text: string; bg: string }> = {
  SUPER_ADMIN: { text: '#E4373D', bg: 'rgba(228,55,61,.12)' },
  HR_ADMIN:    { text: '#E0A93B', bg: 'rgba(224,169,59,.12)' },
  MANAGER:     { text: '#4C8DD6', bg: 'rgba(76,141,214,.12)' },
  EMPLOYEE:    { text: '#2FB67C', bg: 'rgba(47,182,124,.12)' },
};
const ROLE_LABEL: Record<string, string> = {
  SUPER_ADMIN: 'Super Admin', HR_ADMIN: 'HR Admin', MANAGER: 'Manager', EMPLOYEE: 'Employee',
};

function roleLeftBorderColor(card: PersonCard): string {
  if (card.primaryRole && ROLE_COLOR[card.primaryRole]) return ROLE_COLOR[card.primaryRole].text;
  return '#B11116';
}

/* ── Department colour mapping ─────────────────────────────────────────── */
const DEPT_COLORS: Record<string, { border: string; bg: string; fg: string }> = {
  Engineering:  { border: '#4C8DD6', bg: 'rgba(76,141,214,.12)',  fg: '#4C8DD6'  },
  Sales:        { border: '#E0A93B', bg: 'rgba(224,169,59,.12)',  fg: '#E0A93B'  },
  HR:           { border: '#2FB67C', bg: 'rgba(47,182,124,.12)',  fg: '#2FB67C'  },
  Marketing:    { border: '#E4373D', bg: 'rgba(228,55,61,.12)',   fg: '#E4373D'  },
  Finance:      { border: '#8B5CF6', bg: 'rgba(139,92,246,.12)',  fg: '#8B5CF6'  },
  Operations:   { border: '#E0A93B', bg: 'rgba(224,169,59,.12)',  fg: '#E0A93B'  },
  Design:       { border: '#EC4899', bg: 'rgba(236,72,153,.12)',  fg: '#EC4899'  },
  Product:      { border: '#06B6D4', bg: 'rgba(6,182,212,.12)',   fg: '#06B6D4'  },
  Legal:        { border: '#9BA1AC', bg: 'rgba(155,161,172,.12)', fg: '#9BA1AC'  },
};
const DEFAULT_DEPT = { border: '#B11116', bg: 'rgba(177,17,22,.12)', fg: '#E4373D' };

function deptColor(dept: string | null | undefined) {
  if (!dept) return DEFAULT_DEPT;
  const key = Object.keys(DEPT_COLORS).find(k => dept.toLowerCase().includes(k.toLowerCase()));
  return key ? DEPT_COLORS[key] : DEFAULT_DEPT;
}

function initials(name: string) {
  return name.trim().split(/\s+/).map(w => w[0] ?? '').join('').slice(0, 2).toUpperCase();
}

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

/* ── Avatar ────────────────────────────────────────────────────────────── */
function Avatar({ card, size = 42, isFocus = false }: { card: PersonCard; size?: number; isFocus?: boolean }) {
  const c = deptColor(card.department);
  return (
    <div style={{
      width: size, height: size, borderRadius: '50%', flexShrink: 0,
      background: isFocus ? '#B11116' : c.bg,
      color: isFocus ? '#fff' : c.fg,
      display: 'grid', placeItems: 'center',
      fontSize: size * 0.35, fontWeight: 700,
      fontFamily: '"Space Grotesk", sans-serif',
      border: isFocus ? '2px solid rgba(228,55,61,.6)' : `1.5px solid ${c.border}44`,
      boxSizing: 'border-box',
    }}>
      {card.initials || initials(card.name)}
    </div>
  );
}

/* ── Skeleton card ─────────────────────────────────────────────────────── */
function SkeletonCard({ wide = false }: { wide?: boolean }) {
  return (
    <div style={{
      background: 'var(--panel)', border: '1.5px solid var(--line)',
      borderRadius: 10, padding: '10px 12px',
      width: wide ? CARD_W + 20 : CARD_W, flexShrink: 0,
      display: 'flex', flexDirection: 'column', gap: 7,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <div style={{ width: 28, height: 28, borderRadius: '50%', background: 'var(--raised2)', flexShrink: 0 }} />
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 4 }}>
          <div style={{ height: 10, background: 'var(--raised2)', borderRadius: 4, width: '70%' }} />
          <div style={{ height: 8, background: 'var(--raised2)', borderRadius: 4, width: '50%' }} />
        </div>
      </div>
      <div style={{ height: 8, background: 'var(--raised2)', borderRadius: 4, width: '40%' }} />
    </div>
  );
}

/* ── Detail drawer ─────────────────────────────────────────────────────── */
function DetailDrawer({ card, onClose }: { card: PersonCard; onClose: () => void }) {
  const c = deptColor(card.department);
  return (
    <>
      <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.3)', zIndex: 298 }} />
      <div className="nf-drawer-responsive" style={{
        position: 'fixed', top: 0, right: 0, bottom: 0, width: 300,
        background: 'var(--panel)', borderLeft: '1px solid var(--line)',
        boxShadow: '-8px 0 32px rgba(0,0,0,.4)', zIndex: 299,
        display: 'flex', flexDirection: 'column',
      }}>
        <div style={{ padding: '14px 16px', borderBottom: '1px solid var(--line)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>Person Details</span>
          <button onClick={onClose} aria-label="Close" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', display: 'grid', placeItems: 'center', padding: 4, borderRadius: 5 }}>
            <X size={14} />
          </button>
        </div>
        <div style={{ flex: 1, overflowY: 'auto', padding: '18px 16px', display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <Avatar card={card} size={48} isFocus={card.isFocus} />
            <div>
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', marginBottom: 2 }}>{card.name}</div>
              <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>{card.designation ?? '—'}</div>
            </div>
          </div>
          {[
            ['Department', card.department],
            ['Designation', card.designation],
            ['Direct Reports', card.directReportsCount > 0 ? `${card.directReportsCount} people` : 'None'],
            ['Status', card.active ? 'Active' : 'Inactive'],
          ].map(([label, value]) => (
            <div key={label as string}>
              <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', marginBottom: 3 }}>{label}</div>
              <div style={{ fontSize: 13, color: value && value !== 'None' && value !== 'Inactive' ? 'var(--txt)' : 'var(--txt-dim)' }}>{value || '—'}</div>
            </div>
          ))}
          {card.department && (
            <div style={{ marginTop: 4 }}>
              <span style={{ fontSize: 11, fontWeight: 600, padding: '3px 10px', borderRadius: 20, background: c.bg, color: c.fg }}>{card.department}</span>
            </div>
          )}
        </div>
      </div>
    </>
  );
}

/* ── Person card (compact) ─────────────────────────────────────────────── */
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
  const c = deptColor(card.department);
  const leftBorder = roleLeftBorderColor(card);
  const [hover, setHover] = useState(false);

  return (
    <div
      style={{
        position: 'relative',
        background: isFocusCard ? 'rgba(177,17,22,.09)' : hover && !disabled ? 'var(--raised)' : 'var(--panel)',
        border: isFocusCard
          ? '2px solid rgba(228,55,61,.55)'
          : `1.5px solid ${hover && !disabled ? c.border + '88' : 'var(--line)'}`,
        borderLeft: `4px solid ${leftBorder}`,
        borderRadius: 10,
        padding: '8px 10px',
        width: CARD_W,
        flexShrink: 0,
        boxShadow: isFocusCard
          ? '0 0 14px rgba(177,17,22,.14)'
          : hover && !disabled
          ? '0 3px 12px rgba(0,0,0,.18)'
          : '0 1px 3px rgba(0,0,0,.1)',
        transition: 'border-color 140ms, background 140ms, box-shadow 140ms',
        cursor: disabled ? 'default' : 'pointer',
        userSelect: 'none',
        ...inactiveDimStyle(card.active),
      }}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      {/* YOU / FOCUS badge */}
      {(card.id === myUserId || isFocusCard) && (
        <div style={{
          position: 'absolute', top: -9, left: '50%', transform: 'translateX(-50%)',
          background: card.id === myUserId ? '#2FB67C' : '#B11116', color: '#fff',
          fontSize: 8.5, fontWeight: 700, padding: '1px 7px', borderRadius: 20,
          whiteSpace: 'nowrap', letterSpacing: '.05em',
        }}>
          {card.id === myUserId ? 'YOU' : 'FOCUS'}
        </div>
      )}

      {/* Card body — clicking recenters */}
      <button
        onClick={disabled ? undefined : onRecenter}
        aria-label={`View ${card.name}'s team`}
        style={{
          display: 'flex', flexDirection: 'column', gap: 6, width: '100%',
          background: 'none', border: 'none', padding: 0,
          cursor: disabled ? 'default' : 'pointer', textAlign: 'left',
        }}
        tabIndex={disabled ? -1 : 0}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
          <Avatar card={card} size={30} isFocus={isFocusCard} />
          <div style={{ minWidth: 0, flex: 1 }}>
            <div style={{
              fontSize: 11.5, fontWeight: 700, color: 'var(--txt)',
              fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1.3,
              overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
            }}>
              {card.name}
            </div>
            {card.designation && (
              <div style={{
                fontSize: 10, color: 'var(--txt-mut)', lineHeight: 1.3, marginTop: 1,
                overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
              }}>
                {card.designation}
              </div>
            )}
          </div>
        </div>
        {(card.department || !card.active) && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 5, flexWrap: 'wrap' }}>
            {card.department && (
              <span style={{ fontSize: 9, fontWeight: 600, padding: '1px 6px', borderRadius: 20, background: c.bg, color: c.fg }}>
                {card.department}
              </span>
            )}
            {!card.active && <StatusBadge active={card.active} />}
          </div>
        )}
        {card.primaryRole && (() => {
          const rc = ROLE_COLOR[card.primaryRole] ?? { text: '#9BA1AC', bg: 'rgba(155,161,172,.12)' };
          return (
            <div>
              <span style={{ fontSize: 9, fontWeight: 700, padding: '1px 6px', borderRadius: 20, background: rc.bg, color: rc.text }}>
                {ROLE_LABEL[card.primaryRole] ?? card.primaryRole}
              </span>
            </div>
          );
        })()}
        {card.directReportsCount > 0 && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 3, color: 'var(--txt-dim)', fontSize: 10 }}>
            <Users size={9} />
            <span>{card.directReportsCount} report{card.directReportsCount !== 1 ? 's' : ''}</span>
          </div>
        )}
      </button>

      {/* Profile icon — separate click from recenter */}
      <button
        onClick={e => { e.stopPropagation(); onProfile(); }}
        aria-label={`View ${card.name}'s profile`}
        title="View profile"
        style={{
          position: 'absolute', top: 6, right: 6,
          background: 'none', border: 'none', padding: 3,
          cursor: 'pointer', color: 'var(--txt-dim)', borderRadius: 4,
          display: 'flex', alignItems: 'center',
          opacity: hover ? 1 : 0, transition: 'opacity 140ms',
        }}
        tabIndex={0}
      >
        <User size={11} />
      </button>
    </div>
  );
}

/* ── Connector lines ──────────────────────────────────────────────────── */
function VLine({ height = 36 }: { height?: number }) {
  return <div style={{ width: 2, height, background: 'var(--line2)', margin: '0 auto' }} />;
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
      <div style={{
        position: 'fixed', top: '50%', left: '50%', transform: 'translate(-50%,-50%)',
        background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12,
        boxShadow: '0 20px 48px rgba(0,0,0,.5)', zIndex: 399,
        width: 340, maxHeight: '70vh', display: 'flex', flexDirection: 'column',
      }}>
        <div style={{ padding: '14px 16px', borderBottom: '1px solid var(--line)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>View from the top</span>
          <button onClick={onClose} aria-label="Close" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', display: 'grid', placeItems: 'center', padding: 4 }}>
            <X size={14} />
          </button>
        </div>
        <div style={{ overflowY: 'auto', padding: '10px 12px', display: 'flex', flexDirection: 'column', gap: 6 }}>
          {roots.map(r => (
            <button
              key={r.id}
              onClick={() => { onPick(r.id); onClose(); }}
              style={{
                display: 'flex', alignItems: 'center', gap: 10,
                background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8,
                padding: '10px 12px', cursor: 'pointer', textAlign: 'left',
                ...inactiveDimStyle(r.active),
              }}
            >
              <Avatar card={r} size={32} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{r.name}</div>
                  {!r.active && <StatusBadge active={r.active} />}
                </div>
                <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>{r.designation ?? r.department ?? 'Root'}</div>
              </div>
              {r.directReportsCount > 0 && (
                <div style={{ marginLeft: 'auto', fontSize: 10.5, color: 'var(--txt-dim)', display: 'flex', alignItems: 'center', gap: 3 }}>
                  <Users size={10} />{r.directReportsCount}
                </div>
              )}
            </button>
          ))}
        </div>
      </div>
    </>
  );
}

/* ── Breadcrumb ──────────────────────────────────────────────────────── */
function Breadcrumb({ crumbs, onJump }: { crumbs: BreadcrumbEntry[]; onJump: (id: string) => void }) {
  if (crumbs.length === 0) return null;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
      {crumbs.map((c, i) => (
        <React.Fragment key={c.id}>
          {i > 0 && <ChevronRight size={11} style={{ color: 'var(--txt-dim)', flexShrink: 0 }} />}
          <button
            onClick={() => onJump(c.id)}
            style={{
              background: 'none', border: 'none', padding: '2px 4px',
              fontSize: 12, fontWeight: i === crumbs.length - 1 ? 700 : 400,
              color: i === crumbs.length - 1 ? 'var(--txt)' : 'var(--txt-mut)',
              cursor: i === crumbs.length - 1 ? 'default' : 'pointer',
              borderRadius: 4,
            }}
            tabIndex={i === crumbs.length - 1 ? -1 : 0}
          >
            {c.name}
          </button>
        </React.Fragment>
      ))}
    </div>
  );
}

/* ── "+N more peers" expand button ──────────────────────────────────── */
function MoreButton({ count, onClick }: { count: number; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      style={{
        display: 'flex', alignItems: 'center', gap: 4,
        background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 20,
        padding: '5px 12px', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)',
        cursor: 'pointer', whiteSpace: 'nowrap',
      }}
    >
      <ChevronDown size={11} />
      +{count} more peers
    </button>
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
  const [showRoots, setShowRoots] = useState(false);
  const [search, setSearch] = useState('');
  const [searchResults, setSearchResults] = useState<PersonCard[]>([]);
  const [allNodes, setAllNodes] = useState<PersonCard[]>([]);
  const [showSearchDrop, setShowSearchDrop] = useState(false);
  const [peersExpanded, setPeersExpanded] = useState(false);

  // Measured connector line state (computed from real DOM positions)
  const [connLeft, setConnLeft] = useState(0);
  const [connWidth, setConnWidth] = useState(0);

  const chartRef           = useRef<HTMLDivElement>(null);
  const searchRef          = useRef<HTMLDivElement>(null);
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const firstPeerRef       = useRef<HTMLDivElement>(null);
  const lastPeerRef        = useRef<HTMLDivElement>(null);
  const peerRowRef         = useRef<HTMLDivElement>(null);

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

  /* ── Measure peer-row connector line from real DOM positions ─────────── */
  useLayoutEffect(() => {
    if (!peerRowRef.current || !firstPeerRef.current || visiblePeers.length < 2) {
      setConnLeft(0);
      setConnWidth(0);
      return;
    }
    const containerRect = peerRowRef.current.getBoundingClientRect();
    const firstRect     = firstPeerRef.current.getBoundingClientRect();
    const lastEl        = lastPeerRef.current ?? firstPeerRef.current;
    const lastRect      = lastEl.getBoundingClientRect();

    // Center-X of each endpoint, relative to peerRowRef's left edge
    const leftCenter  = firstRect.left - containerRect.left + firstRect.width  / 2;
    const rightCenter = lastRect.left  - containerRect.left + lastRect.width   / 2;

    setConnLeft(leftCenter);
    setConnWidth(Math.max(0, rightCenter - leftCenter));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connectorKey]);

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
  }, [connectorKey]);

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
        setAllNodes(nodes.map(n => ({
          id: n.userId, name: n.fullName,
          designation: n.designationName, department: n.departmentName,
          initials: initials(n.fullName), directReportsCount: 0, isFocus: false, active: n.active,
        })));
      })
      .catch(() => {});
  }, [token]);

  useEffect(() => {
    hierarchyApi.getRoots(token).then(setRoots).catch(() => {});
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

  useEffect(() => {
    const q = search.trim().toLowerCase();
    if (!q) { setSearchResults([]); return; }
    setSearchResults(allNodes.filter(n =>
      n.name.toLowerCase().includes(q) ||
      (n.designation ?? '').toLowerCase().includes(q) ||
      (n.department ?? '').toLowerCase().includes(q)
    ).slice(0, 8));
  }, [search, allNodes]);

  const recenter = useCallback((id: string) => {
    setFocusId(id);
    setNoEmployeeRecord(false);
    setSearch('');
    setShowSearchDrop(false);
  }, []);

  const jumpToMe = useCallback(() => {
    if (myUserId) recenter(myUserId);
  }, [myUserId, recenter]);

  /* ── Export ──────────────────────────────────────────────────────────── */
  // Breadcrumb is outside chartRef DOM — drawn from ctx data directly into canvas.
  async function buildExportCanvas(): Promise<HTMLCanvasElement> {
    await document.fonts.ready;
    const { default: html2canvas } = await import('html2canvas');
    const chartCanvas = await html2canvas(chartRef.current!, {
      backgroundColor: '#16181D', scale: 2, useCORS: true, logging: false,
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
    gfx.font = 'bold 24px "Space Grotesk", system-ui, sans-serif';
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

  /* ── Shared button style ─────────────────────────────────────────────── */
  const BTN: React.CSSProperties = {
    display: 'flex', alignItems: 'center', gap: 5,
    background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7,
    padding: '6px 12px', fontSize: 12, fontWeight: 500, color: 'var(--txt-mut)',
    cursor: 'pointer', whiteSpace: 'nowrap',
  };

  /* ── Direct reports tier (tier 3) ────────────────────────────────────── */
  function DirectReports() {
    if (!ctx) return null;
    if (ctx.directReports.length === 0) {
      return (
        <div style={{ marginTop: 20, padding: '10px 16px', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, fontSize: 12, color: 'var(--txt-dim)', display: 'flex', alignItems: 'center', gap: 6 }}>
          <Users size={12} /> No direct reports
        </div>
      );
    }
    // HLine sized from actual card dimensions, capped at a reasonable max
    const hlineW = Math.min(
      ctx.directReports.length * (CARD_W + CARD_GAP) - CARD_GAP,
      960
    );
    return (
      <>
        <VLine height={28} />
        {ctx.directReports.length > 1 && (
          <div style={{ height: 2, width: hlineW, background: 'var(--line2)' }} />
        )}
        <div style={{ display: 'flex', gap: CARD_GAP }}>
          {ctx.directReports.map(dr => (
            <div key={dr.id} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
              <VLine height={16} />
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

  /* ── Render ──────────────────────────────────────────────────────────── */
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>

      {/* Page title */}
      <div>
        <h1 style={{ margin: 0, marginBottom: 3, fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>
          Org Hierarchy
        </h1>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>
          Click any card to re-center the view. Read-only — manage reporting lines in User Management.
        </p>
      </div>

      {/* Breadcrumb + action buttons — OUTSIDE scroll container, always visible */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          {ctx && ctx.breadcrumb.length > 1 && (
            <Breadcrumb crumbs={ctx.breadcrumb} onJump={recenter} />
          )}
        </div>
        <div style={{ display: 'flex', gap: 7, alignItems: 'center', flexWrap: 'wrap', flexShrink: 0 }}>
          <button onClick={jumpToMe} style={BTN} title="Return to your position">
            <Home size={13} /> My Position
          </button>
          <button
            onClick={() => { if (roots.length === 1) recenter(roots[0].id); else setShowRoots(true); }}
            style={BTN}
          >
            <ArrowUp size={13} /> View from Top
          </button>
          <button onClick={exportPng} style={BTN}><Download size={13} /> PNG</button>
          <button onClick={exportPdf} style={BTN}><FileText size={13} /> PDF</button>
        </div>
      </div>

      {/* Search bar */}
      <div style={{ position: 'relative', maxWidth: 360 }} ref={searchRef}>
        <Search size={13} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
        <input
          value={search}
          onChange={e => { setSearch(e.target.value); setShowSearchDrop(true); }}
          onFocus={() => setShowSearchDrop(true)}
          placeholder="Jump to any person…"
          style={{
            width: '100%', boxSizing: 'border-box',
            background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7,
            padding: '8px 12px 8px 30px', fontSize: 13, color: 'var(--txt)', outline: 'none',
          }}
        />
        {search && (
          <button onClick={() => { setSearch(''); setShowSearchDrop(false); }}
            style={{ position: 'absolute', right: 8, top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', display: 'grid', placeItems: 'center' }}>
            <X size={12} />
          </button>
        )}
        {showSearchDrop && searchResults.length > 0 && (
          <div style={{
            position: 'absolute', top: '105%', left: 0, right: 0,
            background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8,
            boxShadow: '0 12px 32px rgba(0,0,0,.4)', zIndex: 200, overflow: 'hidden',
          }}>
            {searchResults.map(r => (
              <button
                key={r.id}
                onClick={() => recenter(r.id)}
                style={{
                  display: 'flex', alignItems: 'center', gap: 9,
                  width: '100%', background: 'none', border: 'none',
                  padding: '9px 12px', cursor: 'pointer', textAlign: 'left',
                  borderBottom: '1px solid var(--line)',
                  ...inactiveDimStyle(r.active),
                }}
                onMouseEnter={e => (e.currentTarget as HTMLButtonElement).style.background = 'var(--raised)'}
                onMouseLeave={e => (e.currentTarget as HTMLButtonElement).style.background = 'none'}
              >
                <Avatar card={r} size={26} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.name}</div>
                    {!r.active && <StatusBadge active={r.active} />}
                  </div>
                  <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>{r.designation ?? r.department ?? ''}</div>
                </div>
              </button>
            ))}
          </div>
        )}
      </div>

      {/* ── Scrollable chart panel ──────────────────────────────────────────
          FIX for left-edge clipping: the inner wrapper has min-width:max-content
          so the scroll container can access ALL content in both directions.
          alignItems:center on the flex-column chartRef centers narrow tiers
          (manager card, etc.) within the max-content width.
      ─────────────────────────────────────────────────────────────────────── */}
      <div ref={scrollContainerRef} style={{
        background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12,
        padding: '24px 20px', overflowX: 'auto', minHeight: 380,
      }}>
        {/* min-width:max-content wrapper prevents alignItems:center from clipping left side */}
        <div style={{ minWidth: 'max-content', display: 'flex', justifyContent: 'center' }}>

          {loading ? (
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 0 }}>
              <SkeletonCard wide />
              <VLine height={24} />
              <div style={{ display: 'flex', gap: CARD_GAP }}>
                {[1, 2, 3].map(i => <SkeletonCard key={i} />)}
              </div>
              <VLine height={24} />
              <div style={{ display: 'flex', gap: CARD_GAP }}>
                {[1, 2].map(i => <SkeletonCard key={i} />)}
              </div>
            </div>
          ) : noEmployeeRecord ? (
            <div style={{ textAlign: 'center', padding: '48px 0' }}>
              <GitBranch size={28} style={{ color: 'var(--txt-dim)', margin: '0 auto 12px', display: 'block' }} />
              <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)', marginBottom: 6 }}>Your account has no employee record</div>
              <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 16 }}>Use "View from Top" to browse the org chart, or search for a person.</div>
              <button
                onClick={() => { if (roots.length === 1) recenter(roots[0].id); else setShowRoots(true); }}
                style={{ display: 'inline-flex', alignItems: 'center', gap: 6, background: 'rgba(177,17,22,.1)', border: '1px solid rgba(177,17,22,.3)', borderRadius: 8, padding: '8px 16px', fontSize: 13, fontWeight: 600, color: 'var(--brand)', cursor: 'pointer' }}
              >
                <ArrowUp size={14} /> View from Top
              </button>
            </div>
          ) : error ? (
            <div style={{ textAlign: 'center', padding: '48px 0' }}>
              <AlertCircle size={28} style={{ color: 'var(--risk)', margin: '0 auto 10px', display: 'block' }} />
              <div style={{ fontSize: 13, color: 'var(--risk)', marginBottom: 6 }}>{error}</div>
              <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Check that the backend is running.</div>
            </div>
          ) : ctx ? (
            <div
              ref={chartRef}
              style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 0, paddingBottom: 8 }}
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
                <div style={{ marginBottom: 8, display: 'flex', alignItems: 'center', gap: 6 }}>
                  <GitBranch size={13} style={{ color: 'var(--txt-dim)' }} />
                  <span style={{ fontSize: 12, color: 'var(--txt-dim)', fontWeight: 600 }}>Organisation Root</span>
                </div>
              )}

              {/* ── Tier 2: Peers — all peers in ONE flat row, exactly as returned by API ── */}
              {/* No role-based splitting. Mixed roles (SA + Manager etc.) render side-by-side. */}
              {loadingCtx ? (
                <div style={{ display: 'flex', gap: CARD_GAP, justifyContent: 'center' }}>
                  {[1, 2, 3].map(i => (
                    <div key={i} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
                      {ctx.manager && <VLine height={16} />}
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
                      background: 'var(--line2)',
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
                        {ctx.manager && <VLine height={16} />}
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
                      {ctx.manager && <VLine height={16} />}
                      <MoreButton count={hiddenCount} onClick={() => setPeersExpanded(true)} />
                    </div>
                  )}
                </div>
              )}

              {/* Edge case: focus person not in visiblePeers (hidden behind +N more) */}
              {!loadingCtx && !visiblePeers.some(p => p.isFocus) && <DirectReports />}

            </div>
          ) : null}

        </div>{/* end min-width:max-content wrapper */}
      </div>

      {/* Modals */}
      {detail && <DetailDrawer card={detail} onClose={() => setDetail(null)} />}
      {showRoots && <RootsPicker roots={roots} onPick={recenter} onClose={() => setShowRoots(false)} />}
    </div>
  );
}

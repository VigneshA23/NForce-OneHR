import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  GitBranch, Search, Users, User, ArrowUp, Home, ChevronRight,
  Download, FileText, X, AlertCircle,
} from 'lucide-react';
import { hierarchyApi, type OrgContext, type PersonCard, type BreadcrumbEntry } from '../api/hierarchy';
import { profileApi } from '../api/profile';
import { useAuthStore } from '../store/authStore';
import logoUrl from '../assets/nforce-logo.png';

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

/* ── Avatar ────────────────────────────────────────────────────────────── */
function Avatar({ card, size = 42, isFocus = false }: { card: PersonCard; size?: number; isFocus?: boolean }) {
  const c = isFocus ? { bg: 'rgba(177,17,22,.25)', fg: '#fff', border: '' } : deptColor(card.department);
  return (
    <div style={{
      width: size, height: size, borderRadius: '50%', flexShrink: 0,
      background: isFocus ? '#B11116' : c.bg,
      color: isFocus ? '#fff' : c.fg,
      display: 'grid', placeItems: 'center',
      fontSize: size * 0.33, fontWeight: 700,
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
      borderRadius: 12, padding: '14px 16px',
      width: wide ? 200 : 172, flexShrink: 0,
      display: 'flex', flexDirection: 'column', gap: 8,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <div style={{ width: 36, height: 36, borderRadius: '50%', background: 'var(--raised2)', flexShrink: 0 }} />
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 5 }}>
          <div style={{ height: 11, background: 'var(--raised2)', borderRadius: 4, width: '70%' }} />
          <div style={{ height: 9, background: 'var(--raised2)', borderRadius: 4, width: '50%' }} />
        </div>
      </div>
      <div style={{ height: 9, background: 'var(--raised2)', borderRadius: 4, width: '40%' }} />
    </div>
  );
}

/* ── Detail drawer ─────────────────────────────────────────────────────── */
function DetailDrawer({ card, onClose }: { card: PersonCard; onClose: () => void }) {
  const c = deptColor(card.department);
  return (
    <>
      <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.3)', zIndex: 298 }} />
      <div style={{
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
            <Avatar card={card} size={52} isFocus={card.isFocus} />
            <div>
              <div style={{ fontSize: 14.5, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', marginBottom: 2 }}>{card.name}</div>
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
  const c = deptColor(card.department);
  const [hover, setHover] = useState(false);

  return (
    <div
      style={{
        position: 'relative',
        background: isFocusCard ? 'rgba(177,17,22,.09)' : hover && !disabled ? 'var(--raised)' : 'var(--panel)',
        border: isFocusCard
          ? '2px solid rgba(228,55,61,.55)'
          : `1.5px solid ${hover && !disabled ? c.border + '88' : 'var(--line)'}`,
        borderLeft: `4px solid ${c.border}`,
        borderRadius: 12,
        padding: '12px 14px',
        width: 180,
        flexShrink: 0,
        boxShadow: isFocusCard ? '0 0 18px rgba(177,17,22,.14)' : hover && !disabled ? '0 4px 16px rgba(0,0,0,.2)' : '0 1px 4px rgba(0,0,0,.1)',
        transition: 'border-color 140ms, background 140ms, box-shadow 140ms',
        cursor: disabled ? 'default' : 'pointer',
        userSelect: 'none',
      }}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      {/* YOU / FOCUS badge */}
      {(card.id === myUserId || isFocusCard) && (
        <div style={{
          position: 'absolute', top: -10, left: '50%', transform: 'translateX(-50%)',
          background: card.id === myUserId ? '#2FB67C' : '#B11116', color: '#fff',
          fontSize: 9, fontWeight: 700, padding: '1px 8px', borderRadius: 20,
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
          display: 'flex', flexDirection: 'column', gap: 8, width: '100%',
          background: 'none', border: 'none', padding: 0, cursor: disabled ? 'default' : 'pointer', textAlign: 'left',
        }}
        tabIndex={disabled ? -1 : 0}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
          <Avatar card={card} size={36} isFocus={isFocusCard} />
          <div style={{ minWidth: 0, flex: 1 }}>
            <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1.3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {card.name}
            </div>
            {card.designation && (
              <div style={{ fontSize: 10.5, color: 'var(--txt-mut)', lineHeight: 1.3, marginTop: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {card.designation}
              </div>
            )}
          </div>
        </div>
        {card.department && (
          <div>
            <span style={{ fontSize: 9.5, fontWeight: 600, padding: '2px 7px', borderRadius: 20, background: c.bg, color: c.fg }}>
              {card.department}
            </span>
          </div>
        )}
        {card.directReportsCount > 0 && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, color: 'var(--txt-dim)', fontSize: 10.5 }}>
            <Users size={10} />
            <span>{card.directReportsCount} report{card.directReportsCount !== 1 ? 's' : ''}</span>
          </div>
        )}
      </button>

      {/* Profile icon button — separate from recenter */}
      <button
        onClick={e => { e.stopPropagation(); onProfile(); }}
        aria-label={`View ${card.name}'s profile`}
        title="View profile"
        style={{
          position: 'absolute', top: 8, right: 8,
          background: 'none', border: 'none', padding: 3,
          cursor: 'pointer', color: 'var(--txt-dim)', borderRadius: 4,
          display: 'flex', alignItems: 'center',
          opacity: hover ? 1 : 0, transition: 'opacity 140ms',
        }}
        tabIndex={0}
      >
        <User size={12} />
      </button>
    </div>
  );
}

/* ── Connector lines ──────────────────────────────────────────────────── */
function VLine({ height = 36 }: { height?: number }) {
  return <div style={{ width: 2, height, background: 'var(--line2)', margin: '0 auto' }} />;
}

function HLine({ width }: { width: number }) {
  return <div style={{ height: 2, width, background: 'var(--line2)' }} />;
}

/* ── Roots picker modal ───────────────────────────────────────────────── */
function RootsPicker({
  roots, onPick, onClose,
}: { roots: PersonCard[]; onPick: (id: string) => void; onClose: () => void }) {
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
              }}
            >
              <Avatar card={r} size={34} />
              <div>
                <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{r.name}</div>
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
    <div style={{ display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap', marginBottom: 4 }}>
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
  const chartRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  /* Load profile to get my userId */
  useEffect(() => {
    profileApi.get(token)
      .then(p => {
        setMyUserId(p.userId);
        if (p.hasEmployeeRecord) {
          setFocusId(p.userId);
        } else {
          // SA/HR Admin without an employee record — jump straight to roots
          setNoEmployeeRecord(true);
          setLoading(false);
          setShowRoots(true);
        }
      })
      .catch(() => setError('Could not load your profile'));
  }, [token]);

  /* Load all nodes for search */
  useEffect(() => {
    hierarchyApi.list(token)
      .then(nodes => {
        setAllNodes(nodes.map(n => ({
          id: n.userId, name: n.fullName,
          designation: n.designationName, department: n.departmentName,
          initials: initials(n.fullName), directReportsCount: 0, isFocus: false, active: n.active,
        })));
      })
      .catch(() => { /* search just won't work */ });
  }, [token]);

  /* Load roots */
  useEffect(() => {
    hierarchyApi.getRoots(token).then(setRoots).catch(() => { /* roots unavailable */ });
  }, [token]);

  /* Fetch context whenever focusId changes */
  useEffect(() => {
    if (!focusId) return;
    setLoadingCtx(true);
    setError('');
    hierarchyApi.getContext(token, focusId)
      .then(data => { setCtx(data); setLoading(false); })
      .catch(() => {
        // This user has no employee record — fall back to roots picker
        setLoading(false);
        setNoEmployeeRecord(true);
        setShowRoots(true);
      })
      .finally(() => setLoadingCtx(false));
  }, [token, focusId]);

  /* Search filter */
  useEffect(() => {
    const q = search.trim().toLowerCase();
    if (!q) { setSearchResults([]); return; }
    setSearchResults(allNodes.filter(n =>
      n.name.toLowerCase().includes(q) || (n.designation ?? '').toLowerCase().includes(q) || (n.department ?? '').toLowerCase().includes(q)
    ).slice(0, 8));
  }, [search, allNodes]);

  const recenter = useCallback((id: string) => {
    setFocusId(id);
    setSearch('');
    setShowSearchDrop(false);
  }, []);

  const jumpToMe = useCallback(() => {
    if (myUserId) recenter(myUserId);
  }, [myUserId, recenter]);

  /* Export */
  async function buildExportCanvas(): Promise<HTMLCanvasElement> {
    await document.fonts.ready;
    const { default: html2canvas } = await import('html2canvas');
    const chartCanvas = await html2canvas(chartRef.current!, {
      backgroundColor: '#16181D', scale: 2, useCORS: true, logging: false,
    });

    const HEADER = 96; // px in final canvas coords
    const W = chartCanvas.width;
    const combined = document.createElement('canvas');
    combined.width = W;
    combined.height = chartCanvas.height + HEADER;
    const gfx = combined.getContext('2d')!;

    // Gradient header — matches topbar: #050506 → #6B0C10 → #A01418
    const grad = gfx.createLinearGradient(0, 0, W, 0);
    grad.addColorStop(0, '#050506');
    grad.addColorStop(0.4, '#6B0C10');
    grad.addColorStop(1, '#A01418');
    gfx.fillStyle = grad;
    gfx.fillRect(0, 0, W, HEADER);

    // Logo
    const logoImg = new Image();
    logoImg.src = logoUrl;
    await new Promise<void>(r => { logoImg.onload = () => r(); logoImg.onerror = () => r(); });
    if (logoImg.naturalWidth > 0) {
      const logoH = HEADER * 0.55;
      const logoW = logoH * (logoImg.naturalWidth / logoImg.naturalHeight);
      gfx.drawImage(logoImg, 24, (HEADER - logoH) / 2, logoW, logoH);
    }

    // Title
    gfx.fillStyle = '#E8EAED';
    gfx.font = 'bold 26px "Space Grotesk", system-ui, sans-serif';
    const titleX = logoImg.naturalWidth > 0 ? 24 + (HEADER * 0.55 * logoImg.naturalWidth / logoImg.naturalHeight) + 20 : 24;
    gfx.fillText('NForce OneHR — Organization Hierarchy', titleX, HEADER / 2 - 4);

    // Breadcrumb
    const crumbText = ctx?.breadcrumb.map(b => b.name).join(' › ') ?? '';
    if (crumbText) {
      gfx.fillStyle = '#9BA1AC';
      gfx.font = '18px Inter, system-ui, sans-serif';
      gfx.fillText(crumbText, titleX, HEADER / 2 + 20);
    }

    // Chart
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

  /* ── Render ─────────────────────────────────────────────────────────── */
  const BTN: React.CSSProperties = {
    display: 'flex', alignItems: 'center', gap: 5,
    background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7,
    padding: '6px 12px', fontSize: 12, fontWeight: 500, color: 'var(--txt-mut)',
    cursor: 'pointer', whiteSpace: 'nowrap',
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>

      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10 }}>
        <div>
          <h1 style={{ margin: 0, marginBottom: 3, fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>Org Hierarchy</h1>
          <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>Click any card to re-center the view. Read-only — manage reporting lines in User Management.</p>
        </div>
        <div style={{ display: 'flex', gap: 7, alignItems: 'center', flexWrap: 'wrap' }}>
          <button onClick={jumpToMe} style={BTN} title="Return to your position">
            <Home size={13} /> My Position
          </button>
          <button onClick={() => { if (roots.length === 1) recenter(roots[0].id); else setShowRoots(true); }} style={BTN}>
            <ArrowUp size={13} /> View from Top
          </button>
          <button onClick={exportPng} style={BTN}><Download size={13} /> PNG</button>
          <button onClick={exportPdf} style={BTN}><FileText size={13} /> PDF</button>
        </div>
      </div>

      {/* Search bar */}
      <div style={{ position: 'relative', maxWidth: 360 }} ref={searchRef as any}>
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
                }}
                onMouseEnter={e => (e.currentTarget as HTMLButtonElement).style.background = 'var(--raised)'}
                onMouseLeave={e => (e.currentTarget as HTMLButtonElement).style.background = 'none'}
              >
                <Avatar card={r} size={28} />
                <div>
                  <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{r.name}</div>
                  <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>{r.designation ?? r.department ?? ''}</div>
                </div>
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Main chart panel */}
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: '28px 24px', overflowX: 'auto', minHeight: 420 }}>

        {loading ? (
          /* Initial loading skeleton */
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 0 }}>
            <SkeletonCard wide />
            <VLine />
            <div style={{ display: 'flex', gap: 12 }}>
              {[1, 2, 3].map(i => <SkeletonCard key={i} />)}
            </div>
            <VLine />
            <div style={{ display: 'flex', gap: 12 }}>
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
          <div ref={chartRef} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 0, paddingBottom: 8 }}>

            {/* Breadcrumb */}
            {ctx.breadcrumb.length > 1 && (
              <div style={{ marginBottom: 16, alignSelf: 'flex-start' }}>
                <Breadcrumb crumbs={ctx.breadcrumb} onJump={recenter} />
              </div>
            )}

            {/* Tier 1: Manager */}
            {ctx.manager ? (
              <>
                <OrgCard card={ctx.manager} onRecenter={() => recenter(ctx.manager!.id)} onProfile={() => setDetail(ctx.manager)} myUserId={myUserId} />
                <VLine height={32} />
                {/* Horizontal bar spanning peers */}
                {ctx.peers.length > 1 && (
                  <div style={{ position: 'relative', width: '100%', display: 'flex', justifyContent: 'center' }}>
                    <HLine width={Math.min(ctx.peers.length * 196, 1000)} />
                  </div>
                )}
              </>
            ) : (
              /* Root label */
              <div style={{ marginBottom: 8, display: 'flex', alignItems: 'center', gap: 6 }}>
                <GitBranch size={13} style={{ color: 'var(--txt-dim)' }} />
                <span style={{ fontSize: 12, color: 'var(--txt-dim)', fontWeight: 600 }}>Organisation Root</span>
              </div>
            )}

            {/* Tier 2+3: Peers row with focus card anchoring its direct reports */}
            {loadingCtx ? (
              <div style={{ display: 'flex', gap: 12, justifyContent: 'center' }}>
                {[1, 2, 3].map(i => (
                  <div key={i} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
                    {ctx.manager && <VLine height={20} />}
                    <SkeletonCard />
                  </div>
                ))}
              </div>
            ) : (() => {
              const fi = ctx.peers.findIndex(p => p.isFocus);
              const leftPeers = ctx.peers.slice(0, fi);
              const focusPeer = fi >= 0 ? ctx.peers[fi] : null;
              const rightPeers = ctx.peers.slice(fi + 1);

              const renderSidePeer = (peer: PersonCard) => (
                <div key={peer.id} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
                  {ctx.manager && <VLine height={20} />}
                  <OrgCard
                    card={peer}
                    onRecenter={() => recenter(peer.id)}
                    onProfile={() => setDetail(peer)}
                    myUserId={myUserId}
                  />
                </div>
              );

              return (
                <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', justifyContent: 'center' }}>
                  {leftPeers.map(renderSidePeer)}

                  {focusPeer && (
                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
                      {ctx.manager && <VLine height={20} />}
                      <OrgCard
                        card={focusPeer}
                        isFocusCard
                        onRecenter={() => {}}
                        onProfile={() => setDetail(focusPeer)}
                        disabled
                        myUserId={myUserId}
                      />
                      {ctx.directReports.length > 0 ? (
                        <>
                          <VLine height={32} />
                          {ctx.directReports.length > 1 && (
                            <HLine width={Math.min(ctx.directReports.length * 196, 960)} />
                          )}
                          <div style={{ display: 'flex', gap: 12 }}>
                            {ctx.directReports.map(dr => (
                              <div key={dr.id} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
                                <VLine height={20} />
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
                      ) : (
                        <div style={{ marginTop: 24, padding: '12px 20px', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, fontSize: 12, color: 'var(--txt-dim)', display: 'flex', alignItems: 'center', gap: 6 }}>
                          <Users size={13} /> No direct reports
                        </div>
                      )}
                    </div>
                  )}

                  {rightPeers.map(renderSidePeer)}
                </div>
              );
            })()}
          </div>
        ) : null}
      </div>

      {/* Modals */}
      {detail && <DetailDrawer card={detail} onClose={() => setDetail(null)} />}
      {showRoots && <RootsPicker roots={roots} onPick={recenter} onClose={() => setShowRoots(false)} />}
    </div>
  );
}

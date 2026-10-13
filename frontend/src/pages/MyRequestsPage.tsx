import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Link, useSearchParams } from 'react-router-dom';
import {
  CalendarDays, ClipboardList, ClockAlert, Eye, House, Hourglass, Inbox, Layers, Timer, X,
  Plus, ChevronDown, Search, ArrowRight, Send, Smile, type LucideIcon,
} from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { myRequestsApi, type MyRequestItem, type RequestType } from '../api/myRequests';
import { formatDurationMinutes } from '../context/TimeFormatContext';
import { subscribeToNewNotifications } from '../lib/notificationEvents';
import { TabBar } from '../components/TabBar';
import './RequestsPage.css';

// Every notification type the backend emits for a decision on one of this page's request types
// (see NotificationService) — mirrors the LEAVE_APPROVED/LEAVE_REJECTED pattern LeavePage already
// uses to react to the app-wide notification poll (Shell's bell) instead of running its own
// separate polling loop. WFH/PARTIAL_DAY are both decided through AttendanceRequestService, which
// emits ATTENDANCE_REQUEST_APPROVED/REJECTED for either.
const REQUEST_DECISION_NOTIFICATION_TYPES = new Set([
  'LEAVE_APPROVED', 'LEAVE_REJECTED',
  'REGULARIZATION_APPROVED', 'REGULARIZATION_PARTIALLY_APPROVED', 'REGULARIZATION_REJECTED',
  'ATTENDANCE_REQUEST_APPROVED', 'ATTENDANCE_REQUEST_REJECTED',
  'OVERTIME_APPROVED', 'OVERTIME_REJECTED',
]);

const TYPE_LABELS: Record<RequestType, string> = {
  LEAVE: 'Leave',
  REGULARIZATION: 'Attendance Reg.',
  WFH: 'Work From Home',
  PARTIAL_DAY: 'Partial Day',
  OVERTIME: 'Overtime',
};

// Presentational only — icon + tone class per request type (see RequestsPage.css).
const TYPE_ICONS: Record<RequestType, LucideIcon> = {
  LEAVE: CalendarDays,
  REGULARIZATION: ClockAlert,
  WFH: House,
  PARTIAL_DAY: Hourglass,
  OVERTIME: Timer,
};

const TYPE_TONES: Record<RequestType, string> = {
  LEAVE: 'tone-indigo',
  REGULARIZATION: 'tone-warn',
  WFH: 'tone-info',
  PARTIAL_DAY: 'tone-teal',
  OVERTIME: 'tone-rose',
};

// Second line under the Type column's main label — the request's own specific flavor. Leave has
// a real one (the leave type); the rest don't carry a further subtype in the data model, so this
// is just a fixed, short descriptor matching how the reference design labels them.
function typeSubLabel(item: MyRequestItem): string {
  if (item.requestType === 'LEAVE') return item.leaveTypeName ?? 'Leave';
  if (item.requestType === 'REGULARIZATION') return 'Regularization';
  if (item.requestType === 'WFH') return 'WFH';
  if (item.requestType === 'PARTIAL_DAY') return 'Partial Day';
  return 'Regular';
}

// Where "+ New Request" / the Quick Actions sidebar send each request type — Leave has its own
// page; WFH/Partial Day/Overtime are all submitted from the Attendance page's own insight-tile
// buttons (no deep-link query param exists for those yet, so this lands the employee on the page
// rather than auto-opening the modal).
const NEW_REQUEST_LINKS: { type: RequestType; label: string; path: string }[] = [
  { type: 'LEAVE', label: 'Apply Leave', path: '/leave' },
  { type: 'WFH', label: 'WFH Request', path: '/attendance' },
  { type: 'PARTIAL_DAY', label: 'Partial Day', path: '/attendance' },
  { type: 'OVERTIME', label: 'Overtime', path: '/attendance' },
];

function TypeIcon({ type, size = 17 }: { type: RequestType; size?: number }) {
  const Icon = TYPE_ICONS[type];
  return <span className={`nf-rq-type-icon ${TYPE_TONES[type]}`}><Icon size={size} /></span>;
}

function TypeBadge({ item }: { item: MyRequestItem }) {
  return (
    <div className="nf-rq-type">
      <TypeIcon type={item.requestType} />
      <div>
        <div className="nf-rq-type-label">{TYPE_LABELS[item.requestType]}</div>
        <div className="nf-rq-type-sub">{typeSubLabel(item)}</div>
      </div>
    </div>
  );
}

const STATUS_TONES: Record<string, string> = {
  PENDING: 'tone-warn',
  // Regularization-only: manager stage approved, awaiting HR/Super Admin final approval.
  PARTIALLY_APPROVED: 'tone-info',
  APPROVED: 'tone-ok',
  REJECTED: 'tone-risk',
};

function StatusBadge({ status }: { status: string }) {
  return (
    <span className={`nf-rq-badge ${STATUS_TONES[status] ?? 'tone-mute'}`}>
      {status.replace(/_/g, ' ')}
    </span>
  );
}

// `s` is either a plain date ("2026-10-20", from leaveStartDate/attendanceDate) or a full ISO
// instant ("2026-10-06T08:03:54...", from createdAt/decidedAt). A plain date has no timezone, so
// `new Date("2026-10-20")` parses it as UTC midnight — displaying it in a negative-offset zone
// rolls it back a day. Parsing its Y/M/D into a *local* Date avoids that; a real timestamp already
// carries its own offset/Z, so it's left to parse normally.
function fmtDate(s?: string | null) {
  if (!s) return '—';
  const d = s.includes('T')
    ? new Date(s)
    : (([y, m, day]) => new Date(y, m - 1, day))(s.split('-').map(Number));
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

function fmtTime(s?: string | null) {
  if (!s) return '—';
  return new Date(s).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' });
}

/** Overtime's requested duration ("Overtime Hours") — derived from requestedCheckIn/Out rather
 * than shown as raw clock times, since the employee-facing concept is hours claimed, not when. */
function fmtOvertimeHours(startIso?: string | null, endIso?: string | null) {
  if (!startIso || !endIso) return '—';
  const minutes = Math.round((new Date(endIso).getTime() - new Date(startIso).getTime()) / 60000);
  if (minutes <= 0) return '—';
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

// The single date that makes a request "happen on" a day — leave uses its start date, everything
// else (regularization/WFH/partial day/overtime) is already a single-day attendance record.
function itemDate(item: MyRequestItem): string | undefined {
  return item.requestType === 'LEAVE' ? item.leaveStartDate : item.attendanceDate;
}

// ── Type-aware row detail summary ─────────────────────────

function SummaryLine({ children }: { children: React.ReactNode }) {
  return (
    <div className="nf-rq-summary-line">
      <CalendarDays size={11} aria-hidden="true" />
      <span>{children}</span>
    </div>
  );
}

function ItemDetail({ item }: { item: MyRequestItem }) {
  if (item.requestType === 'LEAVE') {
    return (
      <div className="nf-rq-summary">
        <div className="nf-rq-strong">{item.leaveTypeName}</div>
        <SummaryLine>
          {item.leaveStartDate}{item.leaveStartDate !== item.leaveEndDate ? ` → ${item.leaveEndDate}` : ''}
          {' '}({item.leaveTotalDays} day{item.leaveTotalDays !== 1 ? 's' : ''}{item.leaveHalfDay ? ', half' : ''})
        </SummaryLine>
      </div>
    );
  }
  if (item.requestType === 'REGULARIZATION') {
    const missing = item.requestedCheckIn && item.requestedCheckOut ? 'Check-in & check-out'
      : item.requestedCheckIn ? 'Check-in' : 'Check-out';
    return (
      <div className="nf-rq-summary">
        <div className="nf-rq-strong">Regularization</div>
        <SummaryLine>{item.attendanceDate} · Missing: {missing}</SummaryLine>
      </div>
    );
  }
  if (item.requestType === 'WFH' || item.requestType === 'PARTIAL_DAY') {
    return (
      <div className="nf-rq-summary">
        <div className="nf-rq-strong">{TYPE_LABELS[item.requestType]}</div>
        <SummaryLine>
          {item.attendanceDate}
          {item.requestType === 'PARTIAL_DAY' && item.partialDayHours != null && ` (${formatDurationMinutes(Math.round(item.partialDayHours * 60))})`}
        </SummaryLine>
      </div>
    );
  }
  if (item.requestType === 'OVERTIME') {
    return (
      <div className="nf-rq-summary">
        <div className="nf-rq-strong">Overtime</div>
        <SummaryLine>
          {item.attendanceDate} · {item.requestedCheckIn ? fmtTime(item.requestedCheckIn) : '—'} → {item.requestedCheckOut ? fmtTime(item.requestedCheckOut) : '—'}
        </SummaryLine>
      </div>
    );
  }
  return null;
}

// ── Request Overview (ring stats) ─────────────────────────
// Clickable: picking a ring filters the table below to that status group (click the active one
// again, or "Total", to clear it) — makes the overview a real shortcut instead of a static summary.

export type StatusFilter = 'ALL' | 'PENDING' | 'APPROVED' | 'REJECTED';

function StatRing({ value, total, pct, label, caption, tone, active, onClick }: {
  value: number; total: number; pct: number; label: string; caption: string; tone: string;
  active: boolean; onClick: () => void;
}) {
  return (
    <button
      type="button"
      className={`nf-rq-ring-item ${tone}${active ? ' nf-rq-ring-item--active' : ''}`}
      onClick={onClick}
      title={`${label}: ${value} of ${total} request${total !== 1 ? 's' : ''} (${pct}%)`}
    >
      <div className={`nf-rq-ring ${tone}`} style={{ '--pct': pct } as React.CSSProperties}>
        <span className="nf-rq-ring-value">{value}</span>
      </div>
      <div>
        <div className="nf-rq-ring-label">{label}</div>
        <div className="nf-rq-ring-caption">{caption}</div>
      </div>
    </button>
  );
}

function RequestOverviewCard({ items, statusFilter, onFilter }: {
  items: MyRequestItem[]; statusFilter: StatusFilter; onFilter: (f: StatusFilter) => void;
}) {
  const total = items.length;
  const pending = items.filter(i => i.status === 'PENDING' || i.status === 'PARTIALLY_APPROVED').length;
  const approved = items.filter(i => i.status === 'APPROVED').length;
  const needsAction = items.filter(i => i.status === 'REJECTED').length;
  const pct = (n: number) => (total > 0 ? Math.round((n / total) * 100) : 0);
  const toggle = (f: StatusFilter) => onFilter(statusFilter === f ? 'ALL' : f);

  return (
    <div className="nf-rq-panel nf-rq-overview">
      <div className="nf-rq-panel-head">
        <div>
          <h3 className="nf-rq-panel-title"><ClipboardList size={16} aria-hidden="true" />Request Overview</h3>
          <div className="nf-rq-panel-meta">Track your requests at a glance — click a stat to filter</div>
        </div>
      </div>
      <div className="nf-rq-panel-body nf-rq-ring-row">
        <StatRing value={total} total={total} pct={100} label="Total Requests" caption="All time" tone="tone-indigo"
          active={statusFilter === 'ALL'} onClick={() => onFilter('ALL')} />
        <StatRing value={pending} total={total} pct={pct(pending)} label="Pending" caption="Awaiting action" tone="tone-warn"
          active={statusFilter === 'PENDING'} onClick={() => toggle('PENDING')} />
        <StatRing value={approved} total={total} pct={pct(approved)} label="Approved" caption="Granted" tone="tone-ok"
          active={statusFilter === 'APPROVED'} onClick={() => toggle('APPROVED')} />
        <StatRing value={needsAction} total={total} pct={pct(needsAction)} label="Needs Action" caption="Requires your attention" tone="tone-risk"
          active={statusFilter === 'REJECTED'} onClick={() => toggle('REJECTED')} />
      </div>
    </div>
  );
}

// ── Next Request (soonest upcoming approved request) ──────

// Small flat-style palm-tree-on-island illustration — decorative only (aria-hidden), colors are
// fixed rather than theme-driven since it's meant to read as a little vacation/time-off scene
// regardless of the user's chosen accent color.
// Carries its own sky background (a gradient tile, clipped to rounded corners) rather than
// floating bare shapes on the panel — those light pastel cloud/sun colors had no readable
// backdrop against a dark-theme panel before, just loose washed-out blobs. Self-contained like a
// little postcard, it now reads the same way in both themes regardless of --panel's own color.
function PalmIslandIllustration() {
  return (
    <svg width="104" height="84" viewBox="0 0 104 84" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id="nfRqSky" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#8FD3F4" />
          <stop offset="100%" stopColor="#D6F0FA" />
        </linearGradient>
        <clipPath id="nfRqSkyClip"><rect width="104" height="84" rx="14" /></clipPath>
      </defs>
      <g clipPath="url(#nfRqSkyClip)">
        <rect width="104" height="84" fill="url(#nfRqSky)" />
        <circle cx="34" cy="26" r="13" fill="#FFE39A" />
        <ellipse cx="78" cy="20" rx="18" ry="11" fill="#FFFFFF" fillOpacity={0.75} />
        <ellipse cx="18" cy="48" rx="16" ry="10" fill="#FFFFFF" fillOpacity={0.55} />
        <ellipse cx="52" cy="76" rx="48" ry="10" fill="#1583C7" />
        <ellipse cx="55" cy="70" rx="24" ry="9" fill="#1F9D5C" />
        <path d="M55 70 C53 50 60 36 66 26" stroke="#8B5E3C" strokeWidth="4.5" fill="none" strokeLinecap="round" />
        <g fill="#1FAE7A">
          <path d="M66 26 C57 21 50 23 45 30 C54 28 61 28 66 26 Z" />
          <path d="M66 26 C61 17 54 13 45 15 C52 19 60 23 66 26 Z" />
          <path d="M66 26 C69 15 77 11 86 15 C79 19 72 23 66 26 Z" />
          <path d="M66 26 C73 23 80 25 85 32 C78 30 72 28 66 26 Z" />
          <path d="M66 26 C63 17 65 10 72 5 C70 14 68 21 66 26 Z" />
        </g>
      </g>
    </svg>
  );
}

function computeNextRequest(items: MyRequestItem[]): MyRequestItem | null {
  // Local calendar date, not toISOString's UTC one — late evening in a negative-offset zone would
  // otherwise compare against tomorrow's UTC date and could drop today's own approved request.
  const now = new Date();
  const todayIso = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
  const upcoming = items
    .filter(i => i.status === 'APPROVED')
    .map(i => ({ item: i, date: itemDate(i) }))
    .filter((x): x is { item: MyRequestItem; date: string } => !!x.date && x.date >= todayIso)
    .sort((a, b) => a.date.localeCompare(b.date));
  return upcoming[0]?.item ?? null;
}

function NextRequestCard({ item, onView }: { item: MyRequestItem | null; onView: () => void }) {
  return (
    <div className="nf-rq-panel nf-rq-next">
      <div className="nf-rq-panel-head">
        <h3 className="nf-rq-panel-title"><CalendarDays size={16} aria-hidden="true" />Next Request</h3>
        {item && <span className="nf-rq-badge tone-brand">Upcoming</span>}
      </div>
      <div className="nf-rq-panel-body nf-rq-next-body">
        {item ? (
          <>
            <div className="nf-rq-next-info">
              <div className="nf-rq-next-title">{item.requestType === 'LEAVE' ? item.leaveTypeName : TYPE_LABELS[item.requestType]}</div>
              <div className="nf-rq-next-date">
                {fmtDate(itemDate(item))}
                {item.requestType === 'LEAVE' && item.leaveTotalDays != null && ` (${item.leaveTotalDays} day${item.leaveTotalDays !== 1 ? 's' : ''})`}
              </div>
              <div className="nf-rq-next-pill"><span className="nf-rq-next-dot" aria-hidden="true" />Upcoming</div>
              <div className="nf-rq-next-sub">Enjoy your time off!</div>
            </div>
            <PalmIslandIllustration />
            <button type="button" className="nf-rq-next-arrow" aria-label="View request details" onClick={onView}>
              <ArrowRight size={15} />
            </button>
          </>
        ) : (
          <div className="nf-rq-next-empty">No upcoming approved requests.</div>
        )}
      </div>
    </div>
  );
}

// ── "+ New Request" dropdown ──────────────────────────────

function NewRequestMenu() {
  const [open, setOpen] = useState(false);
  const [anchorRect, setAnchorRect] = useState<DOMRect | null>(null);
  const btnRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  function toggleOpen() {
    if (!open && btnRef.current) setAnchorRect(btnRef.current.getBoundingClientRect());
    setOpen(o => !o);
  }

  // Portaled to <body> (see below): .nf-rq-header is `overflow: hidden` (to round its own
  // corners), which clips a plain absolutely-positioned child the instant it extends past the
  // header's own edge — same class of bug EditModal had with .nf-profile-card's hover transform.
  // Button and menu live in different DOM subtrees once portaled, so the outside-click check
  // tests both refs rather than one ancestor `contains()`.
  useEffect(() => {
    if (!open) return;
    function onDocClick(e: MouseEvent) {
      const target = e.target as Node;
      if (btnRef.current?.contains(target)) return;
      if (menuRef.current?.contains(target)) return;
      setOpen(false);
    }
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, [open]);

  return (
    <div className="nf-rq-new-request">
      <button ref={btnRef} type="button" className="nf-rq-btn nf-rq-btn--primary nf-rq-btn--pill" onClick={toggleOpen}>
        <Plus size={15} /> New Request <ChevronDown size={14} />
      </button>
      {open && anchorRect && createPortal(
        <div
          ref={menuRef}
          className="nf-rq-new-request-menu"
          role="menu"
          style={{ position: 'fixed', top: anchorRect.bottom + 6, left: anchorRect.right - 190 }}
        >
          {NEW_REQUEST_LINKS.map(l => {
            const Icon = TYPE_ICONS[l.type];
            return (
              <Link key={l.type} to={l.path} role="menuitem" className="nf-rq-new-request-item" onClick={() => setOpen(false)}>
                <Icon size={15} /> {l.label}
              </Link>
            );
          })}
        </div>,
        document.body
      )}
    </div>
  );
}

// ── Sidebar: Quick Actions + Need Help ────────────────────

function QuickActionsPanel() {
  return (
    <div className="nf-rq-panel">
      <div className="nf-rq-panel-head">
        <div>
          <h3 className="nf-rq-panel-title">Quick Actions</h3>
          <div className="nf-rq-panel-meta">Need to raise a new request?</div>
        </div>
      </div>
      <div className="nf-rq-panel-body nf-rq-quick-grid">
        {NEW_REQUEST_LINKS.map(l => {
          const Icon = TYPE_ICONS[l.type];
          return (
            <Link key={l.type} to={l.path} className={`nf-rq-quick-tile ${TYPE_TONES[l.type]}`}>
              <span className="nf-rq-quick-tile-icon"><Icon size={17} /></span>
              <span className="nf-rq-quick-tile-label">{l.label}</span>
            </Link>
          );
        })}
      </div>
    </div>
  );
}

function NeedHelpPanel() {
  return (
    <div className="nf-rq-help-stack">
      <div className="nf-rq-panel nf-rq-help">
        <Send size={62} className="nf-rq-help-decor" aria-hidden="true" />
        <div className="nf-rq-panel-body">
          <div className="nf-rq-help-title">Need Help?</div>
          <div className="nf-rq-help-text">Check policy, FAQs or contact the HR team for support.</div>
          <Link to="/help" className="nf-rq-btn nf-rq-btn--primary nf-rq-btn--pill nf-rq-btn--block">
            Go to Help &amp; Guidance <ArrowRight size={14} />
          </Link>
        </div>
      </div>
      <div className="nf-rq-panel nf-rq-encourage">
        <div className="nf-rq-panel-body nf-rq-encourage-body">
          <span className="nf-rq-encourage-text">You&apos;re doing great!</span>
          <Smile size={28} aria-hidden="true" />
        </div>
      </div>
    </div>
  );
}

// ── Detail modal (read-only) ───────────────────────────────

function Row({ label, value, wide }: { label: string; value?: string | null; wide?: boolean }) {
  return (
    <div style={wide ? { gridColumn: '1 / -1' } : undefined}>
      <div className="nf-rq-field-label">{label}</div>
      <div className="nf-rq-field-value">{value ?? '—'}</div>
    </div>
  );
}

function RequestDetailModal({ item, onClose }: { item: MyRequestItem; onClose: () => void }) {
  // Same per-type field set as before; Reason is pulled out into its own quoted block below.
  const reason = item.requestType === 'LEAVE' ? item.leaveReason : item.regularizationReason;
  return (
    <div className="nf-rq-overlay">
      <div className="nf-rq-modal" role="dialog" aria-modal="true" aria-labelledby="nf-rq-modal-title">
        <div className="nf-rq-modal-head">
          <div style={{ display: 'flex', alignItems: 'center', gap: 14, minWidth: 0 }}>
            <TypeIcon type={item.requestType} size={19} />
            <div style={{ minWidth: 0 }}>
              <h2 id="nf-rq-modal-title" className="nf-rq-modal-title">Request Details</h2>
              <div className="nf-rq-modal-sub">{TYPE_LABELS[item.requestType]}</div>
            </div>
          </div>
          <button onClick={onClose} className="nf-rq-close" aria-label="Close"><X size={16} /></button>
        </div>

        <div className="nf-rq-modal-body">
          <div className="nf-rq-fields">
            {item.requestType === 'LEAVE' && (
              <>
                <Row label="Type" value={item.leaveTypeName} />
                <Row label="Days" value={String(item.leaveTotalDays)} />
                <Row label="Dates" wide value={`${item.leaveStartDate}${item.leaveStartDate !== item.leaveEndDate ? ` → ${item.leaveEndDate}` : ''}${item.leaveHalfDay ? ' (half day)' : ''}`} />
              </>
            )}

            {item.requestType === 'REGULARIZATION' && (
              <>
                <Row label="Attendance Date" wide value={item.attendanceDate} />
                <Row label="Requested Check-in" value={item.requestedCheckIn ? fmtTime(item.requestedCheckIn) : 'Not provided'} />
                <Row label="Requested Check-out" value={item.requestedCheckOut ? fmtTime(item.requestedCheckOut) : 'Not provided'} />
              </>
            )}

            {(item.requestType === 'WFH' || item.requestType === 'PARTIAL_DAY') && (
              <>
                <Row label="Date" value={item.attendanceDate} />
                {item.requestType === 'PARTIAL_DAY' && <Row label="Duration" value={item.partialDayHours != null ? (formatDurationMinutes(Math.round(item.partialDayHours * 60)) ?? undefined) : undefined} />}
              </>
            )}

            {item.requestType === 'OVERTIME' && (
              <>
                <Row label="Date" value={item.attendanceDate} />
                <Row label="Overtime Hours" value={fmtOvertimeHours(item.requestedCheckIn, item.requestedCheckOut)} />
              </>
            )}
          </div>

          <div className="nf-rq-section-label">Reason</div>
          <div className="nf-rq-quote">{reason ?? '—'}</div>

          <div className="nf-rq-decision">
            <div>
              <div className="nf-rq-field-label">Status</div>
              <StatusBadge status={item.status} />
            </div>
            {item.decisionReason && <Row label="Decision Reason" value={item.decisionReason} />}
            {item.decidedByName && <Row label="Decided By" value={`${item.decidedByName}${item.decidedAt ? ` on ${fmtDate(item.decidedAt)}` : ''}`} />}
          </div>
        </div>

        <div className="nf-rq-modal-foot">
          <button onClick={onClose} className="nf-rq-btn nf-rq-btn--ghost">Close</button>
        </div>
      </div>
    </div>
  );
}

// ── Main page ─────────────────────────────────────────────

const ALL_TYPES: RequestType[] = ['LEAVE', 'REGULARIZATION', 'WFH', 'PARTIAL_DAY', 'OVERTIME'];
const SKELETON_ROWS = 5;
type SortOrder = 'latest' | 'oldest';

export default function MyRequestsPage() {
  const token = useAuthStore(s => s.token)!;
  const [searchParams] = useSearchParams();
  const [items, setItems] = useState<MyRequestItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [typeFilter, setTypeFilter] = useState<RequestType | 'ALL'>(() => {
    const t = searchParams.get('type');
    return (ALL_TYPES as string[]).includes(t ?? '') ? (t as RequestType) : 'ALL';
  });
  const [search, setSearch] = useState('');
  const [sortOrder, setSortOrder] = useState<SortOrder>('latest');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [viewing, setViewing] = useState<MyRequestItem | null>(null);

  useEffect(() => {
    myRequestsApi.list(token)
      .then(setItems)
      .finally(() => setLoading(false));
  }, [token]);

  // Re-fetch without disturbing `loading` (no full-page skeleton flash on a background refresh) —
  // same pattern as LeavePage's refreshLeaveData. Overlap-safe: a refresh that arrives while one
  // is already in flight is coalesced into a single trailing re-run rather than firing a second
  // concurrent request.
  const refreshInFlightRef = useRef(false);
  const refreshQueuedRef = useRef(false);
  const refreshItems = useCallback(async () => {
    if (refreshInFlightRef.current) { refreshQueuedRef.current = true; return; }
    refreshInFlightRef.current = true;
    try {
      setItems(await myRequestsApi.list(token));
    } finally {
      refreshInFlightRef.current = false;
      if (refreshQueuedRef.current) {
        refreshQueuedRef.current = false;
        refreshItems();
      }
    }
  }, [token]);

  // React to this employee's own request decisions as the app-wide notification poll (Shell)
  // detects them, so the table/badges here don't stay stale for the rest of the session when a
  // manager acts on a request while this page remains open. Notifications for other employees
  // never reach this listener — the backend's /api/notifications endpoints are scoped to the
  // authenticated caller — and unrelated notification types (expense/asset/help-content/...) are
  // filtered out and never trigger a refresh. Unsubscribes on unmount.
  useEffect(() => {
    return subscribeToNewNotifications(items => {
      if (items.some(n => REQUEST_DECISION_NOTIFICATION_TYPES.has(n.type))) {
        refreshItems();
      }
    });
  }, [refreshItems]);

  const byType = typeFilter === 'ALL' ? items : items.filter(i => i.requestType === typeFilter);
  const byStatus = statusFilter === 'ALL' ? byType
    : statusFilter === 'PENDING' ? byType.filter(i => i.status === 'PENDING' || i.status === 'PARTIALLY_APPROVED')
    : byType.filter(i => i.status === statusFilter);

  const searched = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return byStatus;
    return byStatus.filter(i => {
      const haystack = [TYPE_LABELS[i.requestType], i.leaveTypeName, i.leaveReason, i.regularizationReason, i.attendanceDate, i.status]
        .filter(Boolean).join(' ').toLowerCase();
      return haystack.includes(q);
    });
  }, [byStatus, search]);

  const filtered = useMemo(() => {
    const sorted = [...searched].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
    if (sortOrder === 'latest') sorted.reverse();
    return sorted;
  }, [searched, sortOrder]);

  const counts: Record<string, number> = { ALL: items.length };
  ALL_TYPES.forEach(t => { counts[t] = items.filter(i => i.requestType === t).length; });

  const nextRequest = useMemo(() => computeNextRequest(items), [items]);

  return (
    <div className="nf-rq">
      <div className="nf-rq-header">
        <div className="nf-rq-header-main">
          <div className="nf-rq-header-icon"><ClipboardList size={24} /></div>
          <div>
            <h1 className="nf-rq-title">My Requests</h1>
            <p className="nf-rq-subtitle">All your submitted leave and attendance requests in one place.</p>
          </div>
        </div>
        <NewRequestMenu />
      </div>

      <div className="nf-rq-overview-row">
        <RequestOverviewCard items={items} statusFilter={statusFilter} onFilter={setStatusFilter} />
        <NextRequestCard item={nextRequest} onView={() => nextRequest && setViewing(nextRequest)} />
      </div>

      <div className="nf-rq-split">
        <div className="nf-rq-card">
          {/* Type filter bar */}
          <TabBar
            ariaLabel="Filter by request type"
            style={{ padding: '0 14px' }}
            active={typeFilter}
            onChange={setTypeFilter}
            tabs={(['ALL', ...ALL_TYPES] as const).map(t => {
              const Icon = t === 'ALL' ? Layers : TYPE_ICONS[t];
              return { key: t, label: t === 'ALL' ? 'All' : TYPE_LABELS[t], icon: <Icon size={14} />, count: counts[t] };
            })}
          />

          <div className="nf-rq-filters">
            <div className="nf-rq-search">
              <Search size={15} />
              <input
                className="nf-rq-input"
                type="text"
                placeholder="Search requests…"
                value={search}
                onChange={e => setSearch(e.target.value)}
              />
            </div>
            <select className="nf-rq-select" value={sortOrder} onChange={e => setSortOrder(e.target.value as SortOrder)}>
              <option value="latest">Sort by: Latest</option>
              <option value="oldest">Sort by: Oldest</option>
            </select>
          </div>

          {loading ? (
            <div className="nf-rq-table-wrap" style={{ borderTop: 'none' }} aria-busy="true">
              <span className="nf-rq-sr-only">Loading…</span>
              <table className="nf-rq-table">
                <tbody>
                  {Array.from({ length: SKELETON_ROWS }).map((_, i) => (
                    <tr key={i} className="nf-rq-skel-row">
                      <td className="nf-rq-td-lead"><span className="nf-rq-skel" style={{ width: 140 }} /></td>
                      <td><span className="nf-rq-skel" style={{ width: 220 }} /></td>
                      <td><span className="nf-rq-skel" style={{ width: 80 }} /></td>
                      <td><span className="nf-rq-skel" style={{ width: 90 }} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : filtered.length === 0 ? (
            <div className="nf-rq-empty">
              <div className="nf-rq-empty-icon"><Inbox size={24} /></div>
              <div className="nf-rq-empty-title">
                {items.length === 0 ? "You haven't submitted any requests yet." : 'No requests match.'}
              </div>
              <div className="nf-rq-empty-text">
                {items.length === 0 ? 'Requests you submit for leave and attendance will show up here.' : 'Try a different filter or search term.'}
              </div>
            </div>
          ) : (
            <div className="nf-rq-table-wrap" style={{ borderTop: 'none' }}>
              <table className="nf-rq-table">
                <thead>
                  <tr>
                    {['Type', 'Summary', 'Status', 'Submitted'].map(h => <th key={h}>{h}</th>)}
                    <th className="nf-rq-th-actions"><span className="nf-rq-sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {/* No row-level onClick (unlike HelpDeskAdminPage's own table, which shares this
                      CSS) — the details view opens only from "View Details" now, so the inherited
                      row cursor/hover styling is overridden to not imply the whole row is clickable. */}
                  {filtered.map(item => (
                    <tr key={`${item.requestType}:${item.id}`} style={{ cursor: 'default' }}>
                      <td className="nf-rq-td-lead" data-label="Type"><TypeBadge item={item} /></td>
                      <td data-label="Summary"><ItemDetail item={item} /></td>
                      <td data-label="Status"><StatusBadge status={item.status} /></td>
                      <td data-label="Submitted" className="nf-rq-nowrap">
                        <div className="nf-rq-strong">{fmtDate(item.createdAt)}</div>
                        <div className="nf-rq-sub">{fmtTime(item.createdAt)}</div>
                      </td>
                      <td className="nf-rq-td-actions">
                        <button
                          type="button"
                          className="nf-rq-btn nf-rq-btn--outline nf-rq-btn--sm nf-rq-view-btn"
                          onClick={() => setViewing(item)}
                        >
                          <Eye size={14} /> <span className="nf-rq-view-btn-label">View Details</span>
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <div className="nf-rq-stack">
          <QuickActionsPanel />
          <NeedHelpPanel />
        </div>
      </div>

      {viewing && (
        <RequestDetailModal item={viewing} onClose={() => setViewing(null)} />
      )}
    </div>
  );
}

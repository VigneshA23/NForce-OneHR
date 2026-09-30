import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { CalendarDays, ClipboardList, ClockAlert, Eye, House, Hourglass, Inbox, Layers, Timer, X, type LucideIcon } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { myRequestsApi, type MyRequestItem, type RequestType } from '../api/myRequests';
import { formatDurationMinutes } from '../context/TimeFormatContext';
import { subscribeToNewNotifications } from '../lib/notificationEvents';
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

function TypeIcon({ type, size = 17 }: { type: RequestType; size?: number }) {
  const Icon = TYPE_ICONS[type];
  return <span className={`nf-rq-type-icon ${TYPE_TONES[type]}`}><Icon size={size} /></span>;
}

function TypeBadge({ type }: { type: RequestType }) {
  return (
    <div className="nf-rq-type">
      <TypeIcon type={type} />
      <span className="nf-rq-type-label">{TYPE_LABELS[type]}</span>
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

function fmtDate(s?: string | null) {
  if (!s) return '—';
  return new Date(s).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
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

// ── Type-aware row detail summary ─────────────────────────

function Sep() {
  return <span className="nf-rq-summary-sep" aria-hidden="true" />;
}

function ItemDetail({ item }: { item: MyRequestItem }) {
  if (item.requestType === 'LEAVE') {
    return (
      <div className="nf-rq-summary">
        <strong>{item.leaveTypeName}</strong><Sep />
        <span>{item.leaveStartDate}{item.leaveStartDate !== item.leaveEndDate ? ` → ${item.leaveEndDate}` : ''}</span><Sep />
        <span>{item.leaveTotalDays} day{item.leaveTotalDays !== 1 ? 's' : ''}{item.leaveHalfDay ? ' (half)' : ''}</span>
      </div>
    );
  }
  if (item.requestType === 'REGULARIZATION') {
    const missing = item.requestedCheckIn && item.requestedCheckOut ? 'Check-in & check-out'
      : item.requestedCheckIn ? 'Check-in' : 'Check-out';
    return (
      <div className="nf-rq-summary">
        <strong>{item.attendanceDate}</strong><Sep />
        <span>Missing: {missing}</span>
      </div>
    );
  }
  if (item.requestType === 'WFH' || item.requestType === 'PARTIAL_DAY') {
    return (
      <div className="nf-rq-summary">
        <strong>{item.attendanceDate}</strong>
        {item.requestType === 'PARTIAL_DAY' && item.partialDayHours != null && (
          <><Sep /><span>{formatDurationMinutes(Math.round(item.partialDayHours * 60))}</span></>
        )}
      </div>
    );
  }
  if (item.requestType === 'OVERTIME') {
    return (
      <div className="nf-rq-summary">
        <strong>{item.attendanceDate}</strong><Sep />
        <span>{item.requestedCheckIn ? fmtTime(item.requestedCheckIn) : '—'} → {item.requestedCheckOut ? fmtTime(item.requestedCheckOut) : '—'}</span>
      </div>
    );
  }
  return null;
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

export default function MyRequestsPage() {
  const token = useAuthStore(s => s.token)!;
  const [searchParams] = useSearchParams();
  const [items, setItems] = useState<MyRequestItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [typeFilter, setTypeFilter] = useState<RequestType | 'ALL'>(() => {
    const t = searchParams.get('type');
    return (ALL_TYPES as string[]).includes(t ?? '') ? (t as RequestType) : 'ALL';
  });
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

  const filtered = typeFilter === 'ALL' ? items : items.filter(i => i.requestType === typeFilter);

  const counts: Record<string, number> = { ALL: items.length };
  ALL_TYPES.forEach(t => { counts[t] = items.filter(i => i.requestType === t).length; });

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
      </div>

      <div className="nf-rq-card">
        {/* Type filter bar */}
        <div className="nf-rq-tabs" role="tablist" aria-label="Filter by request type">
          {(['ALL', ...ALL_TYPES] as const).map(t => {
            const Icon = t === 'ALL' ? Layers : TYPE_ICONS[t];
            return (
              <button
                key={t}
                role="tab"
                aria-selected={typeFilter === t}
                onClick={() => setTypeFilter(t)}
                className={`nf-rq-tab${typeFilter === t ? ' nf-rq-tab--active' : ''}`}
              >
                <Icon size={15} />
                {t === 'ALL' ? 'All' : TYPE_LABELS[t]} {counts[t] > 0 && <span className="nf-rq-tab-count">{counts[t]}</span>}
              </button>
            );
          })}
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
              {items.length === 0 ? "You haven't submitted any requests yet." : 'No requests of this type.'}
            </div>
            <div className="nf-rq-empty-text">
              {typeFilter === 'ALL' ? 'Requests you submit for leave and attendance will show up here.' : `No ${TYPE_LABELS[typeFilter]} requests.`}
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
                {filtered.map(item => (
                  <tr key={`${item.requestType}:${item.id}`} onClick={() => setViewing(item)}>
                    <td className="nf-rq-td-lead" data-label="Type"><TypeBadge type={item.requestType} /></td>
                    <td data-label="Summary"><ItemDetail item={item} /></td>
                    <td data-label="Status"><StatusBadge status={item.status} /></td>
                    <td data-label="Submitted" className="nf-rq-nowrap">{fmtDate(item.createdAt)}</td>
                    <td className="nf-rq-td-actions">
                      {/* Same action as clicking the row — opens the read-only detail modal. */}
                      <button
                        type="button"
                        className="nf-rq-row-btn"
                        aria-label={`View ${TYPE_LABELS[item.requestType]} request details`}
                        title="View details"
                        onClick={e => { e.stopPropagation(); setViewing(item); }}
                      >
                        <Eye size={16} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {viewing && (
        <RequestDetailModal item={viewing} onClose={() => setViewing(null)} />
      )}
    </div>
  );
}

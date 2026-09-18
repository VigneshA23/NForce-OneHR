import { useState } from 'react';
import { Users, CalendarCheck, Clock, Package, Shield, Tag, X } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { ActionGroup, AuditLogEntry } from '../../api/audit';

// EMPLOYEE_UPDATED and USER_UPDATED both land under the Employee Management group (see
// AuditActionCategory) and represent the same kind of edit — HR Admin's path vs Super Admin's
// parallel one — so both are labeled "User Updated" for a consistent display, rather than
// surfacing the underlying action string's own naming.
const ACTION_LABEL_OVERRIDES: Record<string, string> = {
  EMPLOYEE_UPDATED: 'User Updated',
};

function humanizeAction(action: string): string {
  if (ACTION_LABEL_OVERRIDES[action]) return ACTION_LABEL_OVERRIDES[action];
  return action.toLowerCase().split('_').map(w => (w[0]?.toUpperCase() ?? '') + w.slice(1)).join(' ');
}

// Turns a snapshot map's camelCase key (e.g. "reviewComment") into a readable label
// ("Review Comment") — mirrors humanizeAction's word-splitting for the ACTION column, just
// splitting on case boundaries instead of underscores since snapshot keys are camelCase.
function humanizeKey(key: string): string {
  const spaced = key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/^./, c => c.toUpperCase());
  return spaced.replace(/\bId\b/g, 'ID');
}

function fmtDateTime(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString(undefined, { year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

/** Snapshot maps only ever hold strings/numbers/booleans/null (see AuditSnapshotSerializer) — never throws on malformed JSON, just degrades to no detail rows. */
function parseState(state: string | null): Record<string, unknown> | null {
  if (!state) return null;
  try {
    const parsed = JSON.parse(state);
    return parsed && typeof parsed === 'object' ? parsed as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

function fmtValue(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'boolean') return v ? 'Yes' : 'No';
  return String(v);
}

// Icons/colors copied verbatim from AuditStatCards.tsx's CardDef[] (that file is not touched —
// see the audit refinements plan). Labels are a shortened form for this compact row badge; the
// summary cards above keep their own longer names (e.g. "Employee Management Actions").
// `rgb` triples are the plain-rgb equivalent of the matching CSS var (--info/--ok/--warn/--brand/
// --risk/--txt-mut) since custom properties can't be interpolated into an inline rgba() string —
// same technique the previous two-variant badge already used, just extended to all 7 groups.
const CATEGORY_STYLE: Record<ActionGroup, { label: string; icon: LucideIcon; color: string; rgb: string }> = {
  EMPLOYEE:   { label: 'Employee Management', icon: Users,         color: 'var(--info)',    rgb: '76,141,214' },
  LEAVE:      { label: 'Leave',                icon: CalendarCheck, color: 'var(--ok)',      rgb: '47,182,124' },
  ATTENDANCE: { label: 'Attendance',           icon: Clock,         color: 'var(--warn)',    rgb: '224,169,59' },
  ASSET:      { label: 'Asset & Expense',      icon: Package,       color: 'var(--brand)',   rgb: '177,17,22' },
  EXPENSE:    { label: 'Asset & Expense',      icon: Package,       color: 'var(--brand)',   rgb: '177,17,22' },
  ACCESS:     { label: 'User & Access',        icon: Shield,        color: 'var(--risk)',    rgb: '228,55,61' },
  OTHER:      { label: 'Other',                 icon: Tag,           color: 'var(--txt-mut)', rgb: '155,161,172' },
};

function ActionBadge({ group }: { group: ActionGroup }) {
  const style = CATEGORY_STYLE[group] ?? CATEGORY_STYLE.OTHER;
  const Icon = style.icon;
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      fontSize: 10.5, fontWeight: 600, padding: '2px 7px', borderRadius: 4,
      color: style.color,
      background: `rgba(${style.rgb},.12)`,
      border: `1px solid rgba(${style.rgb},.25)`,
    }}>
      <Icon size={10} />
      {style.label}
    </span>
  );
}

const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap', background: 'var(--raised)' };
const tdStyle: React.CSSProperties = { padding: '11px 14px', fontSize: 12.5, color: 'var(--txt)', borderBottom: '1px solid var(--line)', verticalAlign: 'top' };

// ── Detail modal (read-only) — same overlay/modal/label shape as MyRequestsPage's
// RequestDetailModal, so an audit event opens the same way a request detail does. ─────────

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 520, boxShadow: '0 24px 64px rgba(0,0,0,.55)', maxHeight: '90vh', overflowY: 'auto' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div style={labelStyle}>{label}</div>
      <div style={{ fontSize: 13, color: 'var(--txt)' }}>{value}</div>
    </div>
  );
}

// Snapshot key excluded from the detail popup — an internal actor-id reference, not part of
// the human-readable summary (the "Performed By" row above already covers who acted).
const HIDDEN_SNAPSHOT_KEYS = new Set(['managerDecidedBy', 'decidedBy']);

function AuditDetailModal({ entry, onClose }: { entry: AuditLogEntry; onClose: () => void }) {
  const before = parseState(entry.beforeState);
  const after = parseState(entry.afterState);
  // Union of keys across both snapshots, after-first so newly-set fields lead, then narrowed to
  // just the fields that actually changed — an untouched field has the same value in both
  // snapshots (they're built from the same entity right before/after the edit) and would only
  // repeat information already implied by "nothing shown = unchanged", so it's dropped rather
  // than listed as a flat, non-arrow value.
  const keys = Array.from(new Set([...(after ? Object.keys(after) : []), ...(before ? Object.keys(before) : [])]))
    .filter(key => !HIDDEN_SNAPSHOT_KEYS.has(key))
    .filter(key => (before ? before[key] : undefined) !== (after ? after[key] : undefined));

  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <ActionBadge group={entry.actionGroup} />
            <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>
              Audit Event Details
            </span>
          </div>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex' }}><X size={16} /></button>
        </div>

        <div style={{ padding: 20 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: keys.length > 0 ? 16 : 0 }}>
            <DetailRow label="Timestamp" value={fmtDateTime(entry.occurredAt)} />
            <DetailRow label="Action" value={humanizeAction(entry.action)} />
            <DetailRow label="Performed By" value={entry.actorName ? `${entry.actorName}${entry.actorEmail ? ` (${entry.actorEmail})` : ''}` : 'System'} />
            <DetailRow label="Affected User" value={entry.targetLabel} />
          </div>

          {keys.length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, paddingTop: 16, borderTop: '1px solid var(--line)' }}>
              {keys.map(key => {
                const beforeVal = before ? before[key] : undefined;
                const afterVal = after ? after[key] : undefined;
                return <DetailRow key={key} label={humanizeKey(key)} value={`${fmtValue(beforeVal)} → ${fmtValue(afterVal)}`} />;
              })}
            </div>
          )}

          <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 16 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: 'pointer' }}>Close</button>
          </div>
        </div>
      </div>
    </div>
  );
}

interface Props {
  rows: AuditLogEntry[];
}

/** Rows are clickable — opening AuditDetailModal with the event's actor/target/timestamp plus
 *  any before/after snapshot fields the backend captured (e.g. a rejection's review comment). */
export function AuditLogTable({ rows }: Props) {
  const [viewing, setViewing] = useState<AuditLogEntry | null>(null);

  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            <th style={thStyle}>Timestamp</th>
            <th style={thStyle}>Affected User</th>
            <th style={thStyle}>Action</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(r => (
            <tr key={r.id} style={{ cursor: 'pointer' }} onClick={() => setViewing(r)}>
              <td style={{ ...tdStyle, fontFamily: 'Inter, sans-serif', fontSize: 11.5, color: 'var(--txt-mut)', whiteSpace: 'nowrap' }}>
                {fmtDateTime(r.occurredAt)}
              </td>
              <td style={{ ...tdStyle, color: 'var(--txt-mut)' }}>{r.targetLabel}</td>
              <td style={tdStyle}>
                <div style={{ marginBottom: 4 }}><ActionBadge group={r.actionGroup} /></div>
                <div style={{ color: 'var(--txt-mut)' }}>{humanizeAction(r.action)}</div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {viewing && (
        <AuditDetailModal entry={viewing} onClose={() => setViewing(null)} />
      )}
    </div>
  );
}

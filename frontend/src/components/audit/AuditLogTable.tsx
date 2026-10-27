import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Users, CalendarCheck, Clock, Package, Shield, Tag, X, ChevronRight, CalendarClock, UserCog, ArrowRightLeft } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { ActionGroup, AuditLogEntry } from '../../api/audit';
import { EmployeeAvatar } from '../EmployeeAvatar';

// EMPLOYEE_UPDATED and USER_UPDATED both land under the Employee Management group (see
// AuditActionCategory) and represent the same kind of edit — HR Admin's path vs Super Admin's
// parallel one — so both are labeled "User Updated" for a consistent display, rather than
// surfacing the underlying action string's own naming.
const ACTION_LABEL_OVERRIDES: Record<string, string> = {
  EMPLOYEE_UPDATED: 'User Updated',
};

// A penalization policy version carries ~30 rule-configuration fields (see
// PenalizationPolicyService#snapshotFields) — showing every one that changed turns a simple
// "here's what changed and when" into a wall of low-level rule toggles nobody reads. Only
// `version`/`effectiveFrom` are kept as the human-relevant summary for these two actions; every
// other action keeps showing its full changed-field diff, unrestricted, as before.
const SNAPSHOT_FIELD_ALLOWLIST: Record<string, string[]> = {
  PENALIZATION_POLICY_CREATED: ['version', 'effectiveFrom'],
  PENALIZATION_POLICY_UPDATED: ['version', 'effectiveFrom'],
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
  if (v === null || v === undefined || v === '') return '–';
  if (typeof v === 'boolean') return v ? 'Yes' : 'No';
  return String(v);
}

// "effectiveFrom" always means "the point this configuration starts governing behavior from" —
// a previous version's own effective date isn't a "before" state worth comparing against (it's
// simply when the SUPERSEDED version took effect, not something this change moved from), so this
// renders just the new date rather than the generic key's "before → after" arrow. Formatted like
// the Timestamp row above rather than left as the raw "2026-10-01T00:00:00" the backend sends.
function fmtSnapshotValue(key: string, beforeVal: unknown, afterVal: unknown): string {
  if (key === 'effectiveFrom') {
    const raw = afterVal ?? beforeVal;
    return typeof raw === 'string' ? fmtDateTime(raw) : fmtValue(raw);
  }
  return `${fmtValue(beforeVal)} → ${fmtValue(afterVal)}`;
}

// Icons/colors copied verbatim from AuditStatCards.tsx's CardDef[] (that file is not touched —
// see the audit refinements plan). Labels are a shortened form for this compact row badge; the
// summary cards above keep their own longer names (e.g. "Employee Management Actions").
// `rgb` triples are the plain-rgb equivalent of the matching CSS var (--info/--ok/--warn/--brand/
// --risk/--txt-mut) since custom properties can't be interpolated into an inline rgba() string —
// same technique the previous two-variant badge already used, just extended to all 7 groups.
const CATEGORY_STYLE: Record<ActionGroup, { label: string; icon: LucideIcon; color: string; rgb: string }> = {
  EMPLOYEE:   { label: 'Employee', icon: Users,         color: 'var(--info)',    rgb: '76,141,214' },
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


// ── Detail modal (read-only) — same overlay/modal/label shape as MyRequestsPage's
// RequestDetailModal, so an audit event opens the same way a request detail does. ─────────

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };

// For actions where "affected user" is a whole set of employees (e.g. everyone governed by a
// penalization policy whose rules just changed) rather than one — each name is clickable and
// opens that employee's Company Directory profile, the same "/directory?userId=" deep link
// DashboardPage's EmployeeCard/openProfile already use for "go look at this person" elsewhere
// in the app. Capped height + independent scroll (same pattern as MyTeamPage's "Not in yet
// today" roster) so a policy governing 100+ employees can't grow the modal without bound.
function AffectedUsersList({ users, onNavigate }: {
  users: { userId: string; fullName: string }[];
  onNavigate: () => void;
}) {
  const navigate = useNavigate();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6, maxHeight: 220, overflowY: 'auto' }}>
      {users.map(u => (
        <button
          key={u.userId}
          onClick={() => { onNavigate(); navigate(`/directory?userId=${u.userId}`); }}
          style={{
            display: 'flex', alignItems: 'center', gap: 8, background: 'var(--raised)',
            border: '1px solid var(--line)', borderRadius: 8, padding: '6px 10px',
            cursor: 'pointer', font: 'inherit', textAlign: 'left', width: '100%',
          }}
          onMouseEnter={e => { (e.currentTarget as HTMLButtonElement).style.background = 'var(--raised2)'; }}
          onMouseLeave={e => { (e.currentTarget as HTMLButtonElement).style.background = 'var(--raised)'; }}
        >
          <EmployeeAvatar userId={u.userId} name={u.fullName} size={24} fontSize={10} />
          <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{u.fullName}</span>
        </button>
      ))}
    </div>
  );
}

// Snapshot values shaped like a raw UUID (e.g. managerDecidedBy/decidedBy/finalDecidedBy/
// assignedTo, all stored as actor.getId().toString() or similar) are internal id references
// with no human-readable label of their own — whoever acted is already covered by "Performed
// By" above, and the affected user by "Affected User". Filtering by VALUE SHAPE rather than a
// hardcoded key list means this holds for any audit event's snapshot fields, present or future,
// without needing a matching frontend change every time a new id-valued field is introduced.
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const isRawId = (value: unknown): boolean => typeof value === 'string' && UUID_PATTERN.test(value);

function AuditDetailModal({ entry, onClose }: { entry: AuditLogEntry; onClose: () => void }) {
  const before = parseState(entry.beforeState);
  const after = parseState(entry.afterState);
  // Union of keys across both snapshots, after-first so newly-set fields lead, then narrowed to
  // just the fields that actually changed — an untouched field has the same value in both
  // snapshots (they're built from the same entity right before/after the edit) and would only
  // repeat information already implied by "nothing shown = unchanged", so it's dropped rather
  // than listed as a flat, non-arrow value.
  const allowlist = SNAPSHOT_FIELD_ALLOWLIST[entry.action];
  const keys = Array.from(new Set([...(after ? Object.keys(after) : []), ...(before ? Object.keys(before) : [])]))
    .filter(key => (before ? before[key] : undefined) !== (after ? after[key] : undefined))
    .filter(key => !isRawId(before ? before[key] : undefined) && !isRawId(after ? after[key] : undefined))
    .filter(key => !allowlist || allowlist.includes(key));

  const affectedUsers = entry.affectedUsers ?? [];

  const cat = CATEGORY_STYLE[entry.actionGroup] ?? CATEGORY_STYLE.OTHER;
  const CatIcon = cat.icon;

  return (
    <div style={overlayStyle}>
      <div className="nf-audit-modal" style={{ ['--pc' as string]: cat.color }}>
        <div className="nf-audit-modal-hd">
          <span className="nf-audit-modal-ico"><CatIcon size={22} /></span>
          <div style={{ minWidth: 0, flex: 1 }}>
            <div className="nf-audit-modal-kicker">Audit Event Details</div>
            <div className="nf-audit-modal-title">{humanizeAction(entry.action)}</div>
            <ActionBadge group={entry.actionGroup} />
          </div>
          <button onClick={onClose} aria-label="Close" className="nf-audit-modal-x"><X size={17} /></button>
        </div>

        <div className="nf-audit-modal-body">
          <div className="nf-audit-grid">
            <InfoCard icon={<CalendarClock size={14} />} label="Timestamp" value={fmtDateTime(entry.occurredAt)} />
            <InfoCard icon={<UserCog size={14} />} label="Performed By" value={entry.actorName ? `${entry.actorName}${entry.actorEmail ? ` (${entry.actorEmail})` : ''}` : 'System'} />
          </div>

          {affectedUsers.length > 0 ? (
            <div className="nf-audit-card">
              <div className="nf-audit-lbl"><Users size={14} />{affectedUsers.length === 1 ? 'Affected User' : `Affected Users (${affectedUsers.length})`}</div>
              <AffectedUsersList users={affectedUsers} onNavigate={onClose} />
            </div>
          ) : (
            <InfoCard icon={<Users size={14} />} label="Affected User" value={entry.targetLabel} />
          )}

          {keys.length > 0 && (
            <div className="nf-audit-card">
              <div className="nf-audit-lbl"><ArrowRightLeft size={14} />What changed</div>
              <div className="nf-audit-changes">
                {keys.map(key => {
                  const beforeVal = before ? before[key] : undefined;
                  const afterVal = after ? after[key] : undefined;
                  return (
                    <div key={key} className="nf-audit-change">
                      <span className="nf-audit-change-k">{humanizeKey(key)}</span>
                      <span className="nf-audit-change-v">{fmtSnapshotValue(key, beforeVal, afterVal)}</span>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <button onClick={onClose} className="nf-audit-modal-close">Close</button>
          </div>
        </div>
      </div>
    </div>
  );
}

function InfoCard({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="nf-audit-card">
      <div className="nf-audit-lbl">{icon}{label}</div>
      <div className="nf-audit-val">{value}</div>
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
      <table className="nf-audit-table">
        <thead>
          <tr>
            <th>Timestamp</th>
            <th>Affected User</th>
            <th>Action</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(r => {
            const cat = CATEGORY_STYLE[r.actionGroup] ?? CATEGORY_STYLE.OTHER;
            const CatIcon = cat.icon;
            const d = new Date(r.occurredAt);
            return (
              <tr key={r.id} style={{ ['--pc' as string]: cat.color }} onClick={() => setViewing(r)}>
                <td className="nf-audit-when">
                  <span className="nf-audit-date">{d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: '2-digit' })}</span>
                  <span className="nf-audit-time">{d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}</span>
                </td>
                <td className="nf-audit-user">{r.targetLabel}</td>
                <td>
                  <div className="nf-audit-act">
                    <span className="nf-audit-ico"><CatIcon size={15} /></span>
                    <div style={{ minWidth: 0 }}>
                      <div className="nf-audit-name">{humanizeAction(r.action)}</div>
                      <ActionBadge group={r.actionGroup} />
                    </div>
                    <ChevronRight size={16} className="nf-audit-go" />
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      {viewing && (
        <AuditDetailModal entry={viewing} onClose={() => setViewing(null)} />
      )}
    </div>
  );
}

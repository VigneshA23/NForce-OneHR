import { Users, CalendarCheck, Clock, Package, Shield, Tag } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { ActionGroup, AuditLogEntry } from '../../api/audit';

function humanizeAction(action: string): string {
  return action.toLowerCase().split('_').map(w => (w[0]?.toUpperCase() ?? '') + w.slice(1)).join(' ');
}

function fmtDateTime(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString(undefined, { year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit' });
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

interface Props {
  rows: AuditLogEntry[];
}

/** Clean, non-interactive table — no row expansion (removed: this feature no longer surfaces
 *  before/after diffs in the UI at all, even though the backend may still return them). */
export function AuditLogTable({ rows }: Props) {
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
            <tr key={r.id}>
              <td style={{ ...tdStyle, fontFamily: '"JetBrains Mono", monospace', fontSize: 11.5, color: 'var(--txt-mut)', whiteSpace: 'nowrap' }}>
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
    </div>
  );
}

import type { AuditLogEntry } from '../../api/audit';

function humanizeAction(action: string): string {
  return action.toLowerCase().split('_').map(w => (w[0]?.toUpperCase() ?? '') + w.slice(1)).join(' ');
}

function fmtDateTime(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString(undefined, { year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function ActionBadge({ category }: { category: string }) {
  const isAccess = category === 'ACCESS_CONTROL';
  return (
    <span style={{
      fontSize: 10.5, fontWeight: 600, padding: '2px 7px', borderRadius: 4,
      color: isAccess ? 'var(--risk)' : 'var(--info)',
      background: isAccess ? 'rgba(228,55,61,.1)' : 'rgba(76,141,214,.12)',
      border: `1px solid ${isAccess ? 'rgba(228,55,61,.25)' : 'rgba(76,141,214,.25)'}`,
    }}>
      {isAccess ? 'Access Control' : 'HR Operations'}
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
            <th style={thStyle}>Performed By</th>
            <th style={thStyle}>Action</th>
            <th style={thStyle}>Affected User</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(r => (
            <tr key={r.id}>
              <td style={{ ...tdStyle, fontFamily: '"JetBrains Mono", monospace', fontSize: 11.5, color: 'var(--txt-mut)', whiteSpace: 'nowrap' }}>
                {fmtDateTime(r.occurredAt)}
              </td>
              <td style={tdStyle}>
                <div style={{ fontWeight: 600 }}>{r.actorName ?? '—'}</div>
                <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{r.actorEmail ?? ''}</div>
              </td>
              <td style={tdStyle}>
                <div style={{ marginBottom: 4 }}><ActionBadge category={r.actionCategory} /></div>
                <div style={{ color: 'var(--txt-mut)' }}>{humanizeAction(r.action)}</div>
              </td>
              <td style={{ ...tdStyle, color: 'var(--txt-mut)' }}>{r.targetLabel}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

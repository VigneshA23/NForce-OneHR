import { Download, Search } from 'lucide-react';

export const auditInputStyle: React.CSSProperties = { background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 12, height: 40, padding: '0 12px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
export const auditLabelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };

interface Props {
  targetSearch: string;
  onTargetSearchChange: (v: string) => void;
  from: string;
  onFromChange: (v: string) => void;
  to: string;
  onToChange: (v: string) => void;
  onClear: () => void;
  /** Omit to hide the Export button (caller lacks audit:EXPORT). */
  onExport?: () => void;
  exporting: boolean;
  /** Org-wide "access changes" view only: a second search box, for who made the change. */
  actorSearch?: string;
  onActorSearchChange?: (v: string) => void;
  /** Extra controls (e.g. an action picker), shown after the date range. */
  extra?: React.ReactNode;
  /** Overrides the default "any filter set" test, for views whose date range always has a default. */
  hasFilters?: boolean;
}

export function AuditFilterBar({
  targetSearch, onTargetSearchChange,
  from, onFromChange, to, onToChange, onClear, onExport, exporting,
  actorSearch, onActorSearchChange, extra, hasFilters: hasFiltersProp,
}: Props) {
  const hasFilters = hasFiltersProp ?? Boolean(targetSearch || from || to);

  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 14, alignItems: 'end' }}>
      {onActorSearchChange && (
        <label className="nf-filter-search" style={{ flex: '0 1 300px' }}>
          <Search size={15} aria-hidden="true" />
          <input placeholder="Search who made the change…" aria-label="Search who made the change"
            value={actorSearch ?? ''} onChange={e => onActorSearchChange(e.target.value)} />
        </label>
      )}
      <label className="nf-filter-search" style={{ flex: '0 1 300px' }}>
        <Search size={15} aria-hidden="true" />
        <input placeholder="Search affected user…" aria-label="Search affected user"
          value={targetSearch} onChange={e => onTargetSearchChange(e.target.value)} />
      </label>
      <div>
        <label style={auditLabelStyle}>From</label>
        <input type="date" style={auditInputStyle} value={from} onChange={e => onFromChange(e.target.value)} />
      </div>
      <div>
        <label style={auditLabelStyle}>To</label>
        <input type="date" style={auditInputStyle} value={to} onChange={e => onToChange(e.target.value)} />
      </div>
      {extra}
      {hasFilters && (
        <button onClick={onClear} style={{ ...auditInputStyle, color: 'var(--txt-mut)', cursor: 'pointer' }}>Clear</button>
      )}
      <div style={{ flex: 1 }} />
      {onExport && <button
        onClick={onExport}
        disabled={exporting}
        style={{
          display: 'flex', alignItems: 'center', gap: 7, padding: '0 16px',
          background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 12, height: 40, boxSizing: 'border-box',
          fontSize: 13, fontWeight: 600, color: 'var(--txt)',
          cursor: exporting ? 'not-allowed' : 'pointer', opacity: exporting ? .6 : 1,
        }}
      >
        <Download size={13} />
        {exporting ? 'Exporting…' : 'Export to Excel'}
      </button>}
    </div>
  );
}

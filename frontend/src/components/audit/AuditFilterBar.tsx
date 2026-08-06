import { Download } from 'lucide-react';

const inputStyle: React.CSSProperties = { background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };

interface Props {
  actorSearch: string;
  onActorSearchChange: (v: string) => void;
  targetSearch: string;
  onTargetSearchChange: (v: string) => void;
  from: string;
  onFromChange: (v: string) => void;
  to: string;
  onToChange: (v: string) => void;
  onClear: () => void;
  onExport: () => void;
  exporting: boolean;
}

export function AuditFilterBar({
  actorSearch, onActorSearchChange, targetSearch, onTargetSearchChange,
  from, onFromChange, to, onToChange, onClear, onExport, exporting,
}: Props) {
  const hasFilters = Boolean(actorSearch || targetSearch || from || to);

  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 14, alignItems: 'end' }}>
      <div style={{ minWidth: 200 }}>
        <label style={labelStyle}>Search Performed By</label>
        <input style={{ ...inputStyle, width: '100%' }} placeholder="Name or email"
          value={actorSearch} onChange={e => onActorSearchChange(e.target.value)} />
      </div>
      <div style={{ minWidth: 200 }}>
        <label style={labelStyle}>Search Affected User</label>
        <input style={{ ...inputStyle, width: '100%' }} placeholder="Employee, doc, policy…"
          value={targetSearch} onChange={e => onTargetSearchChange(e.target.value)} />
      </div>
      <div>
        <label style={labelStyle}>From</label>
        <input type="date" style={inputStyle} value={from} onChange={e => onFromChange(e.target.value)} />
      </div>
      <div>
        <label style={labelStyle}>To</label>
        <input type="date" style={inputStyle} value={to} onChange={e => onToChange(e.target.value)} />
      </div>
      {hasFilters && (
        <button onClick={onClear} style={{ ...inputStyle, color: 'var(--txt-mut)', cursor: 'pointer' }}>Clear</button>
      )}
      <div style={{ flex: 1 }} />
      <button
        onClick={onExport}
        disabled={exporting}
        style={{
          display: 'flex', alignItems: 'center', gap: 7, padding: '8px 16px',
          background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7,
          fontSize: 12.5, fontWeight: 600, color: 'var(--txt)',
          cursor: exporting ? 'not-allowed' : 'pointer', opacity: exporting ? .6 : 1,
        }}
      >
        <Download size={13} />
        {exporting ? 'Exporting…' : 'Export to Excel'}
      </button>
    </div>
  );
}

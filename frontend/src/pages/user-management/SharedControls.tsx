import { useEffect, useRef, useState } from 'react';
import { ChevronDown } from 'lucide-react';
import type { ShiftRow } from '../../api/org';
import { ShiftFormModal, fmtShiftTime } from '../OrgSetupPage';

// Moved out of UserManagementPage.tsx so both it and the four-step employee creation wizard
// (CreateEmployeeWizard.tsx) can import these controls independently, without the wizard
// importing from the page that renders it (which would be a circular dependency). Props,
// behavior, styling, and validation are unchanged from their original definitions.

const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 9, padding: '10px 12px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };

// ─── Creatable Location Select ────────────────────────────────────────────────
export interface Location { id: string; name: string; active?: boolean; }

// Picks from existing, already-created Locations only — a new Location needs a Timezone
// (required, and constrained to a fixed supported set — see OrgService.SUPPORTED_TIMEZONES on
// the backend), which doesn't fit this inline assignment picker, so location CREATION happens
// exclusively via Organization Masters → Locations (see OrgSetupPage). `token` is accepted for
// signature compatibility with call sites, even though this component no longer calls the API.
export function CreatableLocationSelect({ locations, value, onChange, disabled }: { locations: Location[]; value: string | undefined; onChange: (id: string | undefined) => void; token: string; disabled?: boolean }) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const currentName = value ? (locations.find(l => l.id === value)?.name ?? '') : '';
  useEffect(() => { if (!open) setQuery(currentName); }, [open, currentName]);
  useEffect(() => {
    function out(e: MouseEvent) { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); }
    document.addEventListener('mousedown', out);
    return () => document.removeEventListener('mousedown', out);
  }, []);
  const filtered = query.trim() ? locations.filter(l => l.name.toLowerCase().includes(query.toLowerCase())) : locations;
  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <div style={{ position: 'relative' }}>
        <input style={{ ...inputStyle, paddingRight: 32, opacity: disabled ? 0.6 : 1, cursor: disabled ? 'not-allowed' : 'text' }} placeholder="Select a location…"
          disabled={disabled}
          value={open ? query : currentName}
          onFocus={() => { setOpen(true); setQuery(currentName); }}
          onChange={e => { setQuery(e.target.value); setOpen(true); if (!e.target.value) onChange(undefined); }} />
        <ChevronDown size={14} style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
      </div>
      {!disabled && open && (
        <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, marginTop: 4, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, boxShadow: '0 8px 24px rgba(0,0,0,.3)', zIndex: 100, maxHeight: 240, overflowY: 'auto' }}>
          {filtered.length === 0 && (
            <div style={{ padding: '10px 14px', fontSize: 13, color: 'var(--txt-dim)' }}>
              No locations found. Add one under Organization Masters → Locations.
            </div>
          )}
          {filtered.map(l => (
            <div key={l.id} onMouseDown={() => { onChange(l.id); setOpen(false); }}
              style={{ padding: '9px 14px', fontSize: 13, color: value === l.id ? 'var(--brand-bright)' : 'var(--txt)', background: value === l.id ? 'rgba(176,17,22,.12)' : 'transparent', cursor: 'pointer' }}
              onMouseEnter={e => (e.currentTarget.style.background = 'var(--raised)')}
              onMouseLeave={e => (e.currentTarget.style.background = value === l.id ? 'rgba(176,17,22,.12)' : 'transparent')}>
              {l.name}
            </div>
          ))}
          <div onMouseDown={() => { onChange(undefined); setQuery(''); setOpen(false); }}
            style={{ padding: '9px 14px', fontSize: 12, color: 'var(--txt-dim)', borderTop: '1px solid var(--line)', cursor: 'pointer' }}>
            Clear selection</div>
        </div>
      )}
    </div>
  );
}

// ─── Shift Select (with inline "+ Add New Shift") ─────────────────────────────
export function ShiftSelect({ shifts, value, onChange, onCreated, token, disabled }: {
  shifts: ShiftRow[]; value: string | undefined; onChange: (id: string | undefined) => void;
  onCreated: (shift: ShiftRow) => void; token: string; disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [showCreate, setShowCreate] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const current = shifts.find(s => s.id === value);
  useEffect(() => {
    function out(e: MouseEvent) { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); }
    document.addEventListener('mousedown', out);
    return () => document.removeEventListener('mousedown', out);
  }, []);
  const label = (s: ShiftRow) => `${s.name} - ${fmtShiftTime(s.startTime)} to ${fmtShiftTime(s.endTime)}`;
  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <div style={{ position: 'relative' }} onClick={() => !disabled && setOpen(o => !o)}>
        <input readOnly disabled={disabled} style={{ ...inputStyle, paddingRight: 32, cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? 0.6 : 1 }} placeholder="Select a shift…"
          value={current ? label(current) : ''} />
        <ChevronDown size={14} style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
      </div>
      {!disabled && open && (
        <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, marginTop: 4, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, boxShadow: '0 8px 24px rgba(0,0,0,.3)', zIndex: 100, maxHeight: 220, overflowY: 'auto' }}>
          <div onMouseDown={() => { onChange(undefined); setOpen(false); }}
            style={{ padding: '9px 14px', fontSize: 12, color: 'var(--txt-dim)', cursor: 'pointer' }}>
            None</div>
          {shifts.map(s => (
            <div key={s.id} onMouseDown={() => { onChange(s.id); setOpen(false); }}
              style={{ padding: '9px 14px', fontSize: 13, color: value === s.id ? 'var(--brand-bright)' : 'var(--txt)', background: value === s.id ? 'rgba(176,17,22,.12)' : 'transparent', cursor: 'pointer' }}
              onMouseEnter={e => (e.currentTarget.style.background = 'var(--raised)')}
              onMouseLeave={e => (e.currentTarget.style.background = value === s.id ? 'rgba(176,17,22,.12)' : 'transparent')}>
              {label(s)}
            </div>
          ))}
          <div onMouseDown={() => { setShowCreate(true); setOpen(false); }}
            style={{ padding: '9px 14px', fontSize: 13, color: '#4C8DD6', borderTop: '1px solid var(--line)', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontWeight: 700 }}>+</span> Add New Shift
          </div>
        </div>
      )}
      {showCreate && (
        <ShiftFormModal
          token={token}
          onClose={() => setShowCreate(false)}
          onSaved={(saved) => { onCreated(saved); onChange(saved.id); setShowCreate(false); }}
        />
      )}
    </div>
  );
}

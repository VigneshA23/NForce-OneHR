import { useEffect, useMemo, useState } from 'react';
import { X } from 'lucide-react';
import { leaveConfigApi, type DirectoryEmployee, type EligibilityConfig } from '../../api/leave';

/**
 * "Applies to" for a leave type (V314): gender, departments, locations, employment types, plus
 * employees always included or excluded. Empty = no restriction of that kind. Values of one kind
 * are OR'ed, kinds are AND'ed; an employee with no gender on file never matches a gender rule.
 */

export const GENDER_OPTIONS = ['Male', 'Female', 'Non-binary', 'Prefer not to say'];
export const EMPLOYMENT_TYPE_OPTIONS = ['FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERN'];

export interface NamedOption { id: string; name: string }

export const EMPTY_ELIGIBILITY: EligibilityConfig = {
  genders: [], departmentIds: [], locationIds: [], employmentTypes: [], includeEmployeeIds: [], excludeEmployeeIds: [],
};

export function employmentLabel(v: string): string {
  const s = v.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function sameEligibility(a: EligibilityConfig, b: EligibilityConfig): boolean {
  const k = (c: EligibilityConfig) => JSON.stringify([c.genders, c.departmentIds, c.locationIds, c.employmentTypes,
    c.includeEmployeeIds, c.excludeEmployeeIds].map(l => [...(l ?? [])].sort()));
  return k(a) === k(b);
}

/** "Female · Hyderabad" / "Everyone", for the leave types table. */
export function appliesToSummary(t: { eligibility?: EligibilityConfig | null }, departments: NamedOption[], locations: NamedOption[]): string {
  const e = t.eligibility;
  if (!e) return 'Everyone';
  const name = (list: NamedOption[], id: string) => list.find(x => x.id === id)?.name ?? 'Unknown';
  const parts: string[] = [];
  if (e.genders.length) parts.push(e.genders.join(' or '));
  if (e.departmentIds.length) parts.push(e.departmentIds.length > 2 ? `${e.departmentIds.length} departments` : e.departmentIds.map(id => name(departments, id)).join(' or '));
  if (e.locationIds.length) parts.push(e.locationIds.length > 2 ? `${e.locationIds.length} locations` : e.locationIds.map(id => name(locations, id)).join(' or '));
  if (e.employmentTypes.length) parts.push(e.employmentTypes.map(employmentLabel).join(' or '));
  let s = parts.length ? parts.join(' · ') : 'Everyone';
  if (e.includeEmployeeIds.length) s += ` · +${e.includeEmployeeIds.length} included`;
  if (e.excludeEmployeeIds.length) s += ` · ${e.excludeEmployeeIds.length} excluded`;
  return s;
}

const labelS: React.CSSProperties = { fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 6 };
const hintS: React.CSSProperties = { margin: '0 0 10px', fontSize: 11.5, color: 'var(--txt-dim)', lineHeight: 1.5 };
const inputS: React.CSSProperties = {
  background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px',
  fontSize: 13, color: 'var(--txt)', width: '100%', boxSizing: 'border-box',
};

function Chips({ label, options, selected, onChange, render = (v: string) => v, disabled }: {
  label: string; options: NamedOption[]; selected: string[]; onChange(next: string[]): void;
  render?: (name: string) => string; disabled?: boolean;
}) {
  return (
    <div style={{ marginBottom: 12 }}>
      <span style={labelS}>{label} <span style={{ fontWeight: 400, color: 'var(--txt-dim)' }}>{selected.length === 0 ? '(all)' : ''}</span></span>
      <div role="group" aria-label={label} style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
        {options.length === 0 && <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>None configured</span>}
        {options.map(o => {
          const on = selected.includes(o.id);
          return (
            <button key={o.id} type="button" aria-pressed={on} disabled={disabled}
              onClick={() => onChange(on ? selected.filter(x => x !== o.id) : [...selected, o.id])}
              style={{
                padding: '4px 10px', borderRadius: 16, fontSize: 12, cursor: disabled ? 'default' : 'pointer',
                border: `1px solid ${on ? 'var(--brand)' : 'var(--line2)'}`,
                background: on ? 'rgba(79,110,247,.14)' : 'var(--raised)', color: on ? 'var(--brand)' : 'var(--txt-mut)',
                fontWeight: on ? 600 : 400,
              }}>
              {render(o.name)}
            </button>
          );
        })}
      </div>
    </div>
  );
}

function EmployeePicker({ label, employees, selected, onChange, disabled }: {
  label: string; employees: DirectoryEmployee[]; selected: string[]; onChange(next: string[]): void; disabled?: boolean;
}) {
  const [query, setQuery] = useState('');
  const listId = `nf-applies-${label.replace(/\W/g, '')}`;
  const text = (e: DirectoryEmployee) => (e.employeeCode ? `${e.name} (${e.employeeCode})` : e.name);
  const byId = useMemo(() => new Map(employees.map(e => [e.userId, e])), [employees]);
  return (
    <div style={{ marginBottom: 12 }}>
      <label style={labelS} htmlFor={listId + '-in'}>{label}</label>
      {!disabled && (
        <>
          <input id={listId + '-in'} style={inputS} list={listId} value={query} placeholder="Search by name or code"
            onChange={e => {
              const match = employees.find(x => text(x) === e.target.value);
              if (match) {
                if (!selected.includes(match.userId)) onChange([...selected, match.userId]);
                setQuery('');
              } else {
                setQuery(e.target.value);
              }
            }} />
          <datalist id={listId}>{employees.map(e => <option key={e.userId} value={text(e)} />)}</datalist>
        </>
      )}
      {selected.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 8 }}>
          {selected.map(id => {
            const e = byId.get(id);
            return (
              <span key={id} style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '3px 6px 3px 10px', borderRadius: 16, fontSize: 12, background: 'var(--raised)', border: '1px solid var(--line2)', color: 'var(--txt)' }}>
                {e ? text(e) : 'Former employee'}
                {!disabled && (
                  <button type="button" aria-label={`Remove ${e ? e.name : 'employee'}`} onClick={() => onChange(selected.filter(x => x !== id))}
                    style={{ border: 'none', background: 'none', cursor: 'pointer', color: 'var(--txt-dim)', display: 'flex', padding: 2 }}>
                    <X size={12} />
                  </button>
                )}
              </span>
            );
          })}
        </div>
      )}
    </div>
  );
}

/** The editor. Loads nothing itself except the live eligible-employee count. */
export function AppliesToEditor({ value, onChange, departments, locations, employees, token, disabled }: {
  value: EligibilityConfig; onChange(next: EligibilityConfig): void;
  departments: NamedOption[]; locations: NamedOption[]; employees: DirectoryEmployee[]; token: string; disabled?: boolean;
}) {
  const [count, setCount] = useState<{ eligibleCount: number; totalCount: number } | null>(null);
  const key = JSON.stringify(value);
  useEffect(() => {
    let live = true;
    const t = setTimeout(() => {
      leaveConfigApi.previewEligibility(value, token).then(r => { if (live) setCount(r); }).catch(() => { if (live) setCount(null); });
    }, 250);
    return () => { live = false; clearTimeout(t); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, token]);

  const set = (patch: Partial<EligibilityConfig>) => onChange({ ...value, ...patch });
  return (
    <fieldset style={{ border: '1px solid var(--line)', borderRadius: 8, padding: '12px 14px 4px', margin: '0 0 14px' }}>
      <legend style={{ ...labelS, padding: '0 6px', marginBottom: 0 }}>Applies to</legend>
      <p style={hintS}>
        Leave a group empty to include everyone. An employee must match every group you set (any one value within a group).
        Employees without a gender on their profile don't match a gender rule.
      </p>
      <Chips label="Gender" disabled={disabled} options={GENDER_OPTIONS.map(g => ({ id: g, name: g }))}
        selected={value.genders} onChange={genders => set({ genders })} />
      <Chips label="Employment type" disabled={disabled} options={EMPLOYMENT_TYPE_OPTIONS.map(t => ({ id: t, name: t }))} render={employmentLabel}
        selected={value.employmentTypes} onChange={employmentTypes => set({ employmentTypes })} />
      <Chips label="Departments" disabled={disabled} options={departments} selected={value.departmentIds} onChange={departmentIds => set({ departmentIds })} />
      <Chips label="Locations" disabled={disabled} options={locations} selected={value.locationIds} onChange={locationIds => set({ locationIds })} />
      <EmployeePicker label="Always include" disabled={disabled} employees={employees} selected={value.includeEmployeeIds}
        onChange={includeEmployeeIds => set({ includeEmployeeIds, excludeEmployeeIds: value.excludeEmployeeIds.filter(id => !includeEmployeeIds.includes(id)) })} />
      <EmployeePicker label="Always exclude" disabled={disabled} employees={employees} selected={value.excludeEmployeeIds}
        onChange={excludeEmployeeIds => set({ excludeEmployeeIds, includeEmployeeIds: value.includeEmployeeIds.filter(id => !excludeEmployeeIds.includes(id)) })} />
      <p style={{ ...hintS, color: 'var(--txt-mut)', fontWeight: 600 }} aria-live="polite">
        {count ? `${count.eligibleCount} of ${count.totalCount} current employees` : ' '}
      </p>
    </fieldset>
  );
}

import { AlertTriangle } from 'lucide-react';

/**
 * Single source of truth for how a deactivated employee is represented anywhere in the app —
 * User Management's row treatment (dimmed + "Inactive" pill) is the reference implementation;
 * every other employee list (project team, org chart, directory, dashboards, assignment
 * pickers, etc.) should look identical so an inactive employee reads the same way everywhere.
 */

export function StatusBadge({ active }: { active: boolean }) {
  return (
    <span style={{ fontSize: 11, fontWeight: 600, color: active ? '#2FB67C' : '#E4373D', background: active ? 'rgba(47,182,124,.1)' : 'rgba(228,55,61,.1)', borderRadius: 4, padding: '2px 7px', whiteSpace: 'nowrap' }}>
      {active ? 'Active' : 'Inactive'}
    </span>
  );
}

/** Spread onto a row/card's style prop to dim inactive employees — matches UserManagementPage. */
export function inactiveDimStyle(active: boolean): React.CSSProperties {
  return { opacity: active ? 1 : 0.6 };
}

/**
 * Banner shown at the top of an edit form when the record being edited belongs to a
 * deactivated employee — makes it impossible to miss that this isn't an active employee.
 */
export function InactiveEditBanner({ message }: { message?: string }) {
  return (
    <div style={{ gridColumn: '1/-1', display: 'flex', alignItems: 'flex-start', gap: 10, background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 8, padding: '10px 14px', fontSize: 13, color: 'var(--txt)' }}>
      <AlertTriangle size={15} color="#E4373D" style={{ flexShrink: 0, marginTop: 1 }} />
      <span>{message ?? 'This employee is inactive. Changes will apply to their historical record.'}</span>
    </div>
  );
}

/**
 * Confirmation gate shown when editing a deactivated employee's record — unchecked by default,
 * so both the fields that imply active employment (manager, department, designation, employment
 * type, role — disabled until checked) AND the Save action itself (disabled until checked) stay
 * locked until HR explicitly confirms the edit is intentional. Fields that don't imply active
 * employment (name, contact info, location, exit details) stay editable regardless, but the save
 * still can't be submitted without this confirmation while the record is inactive.
 */
export function InactiveFieldsConfirm({ checked, onChange, fields }: { checked: boolean; onChange: (v: boolean) => void; fields: string }) {
  return (
    <label style={{ gridColumn: '1/-1', display: 'flex', alignItems: 'flex-start', gap: 8, fontSize: 12, color: 'var(--txt-mut)', cursor: 'pointer' }}>
      <input type="checkbox" checked={checked} onChange={e => onChange(e.target.checked)} style={{ marginTop: 2, width: 14, height: 14, flexShrink: 0, accentColor: 'var(--brand)' }} />
      <span>I understand this employee is inactive and want to save these changes. (Required to change {fields}.)</span>
    </label>
  );
}

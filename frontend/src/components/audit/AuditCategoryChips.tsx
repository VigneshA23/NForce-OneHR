import type { ActionGroup } from '../../api/audit';

const GROUPS: { key: ActionGroup | 'ALL'; label: string }[] = [
  { key: 'ALL', label: 'All' },
  { key: 'EMPLOYEE', label: 'Employee' },
  { key: 'ATTENDANCE', label: 'Attendance' },
  { key: 'LEAVE', label: 'Leave' },
  { key: 'EXPENSE', label: 'Expense' },
  { key: 'ASSET', label: 'Asset' },
  { key: 'ACCESS', label: 'Access' },
  { key: 'OTHER', label: 'Other' },
];

interface Props {
  active: ActionGroup | 'ALL';
  onChange: (group: ActionGroup | 'ALL') => void;
  /** HR Admin never gets the Access chip — the server enforces the real scope regardless. */
  showAccess: boolean;
}

export function AuditCategoryChips({ active, onChange, showAccess }: Props) {
  const chips = GROUPS.filter(g => g.key !== 'ACCESS' || showAccess);
  return (
    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
      {chips.map(c => {
        const isActive = active === c.key;
        return (
          <button
            key={c.key}
            onClick={() => onChange(c.key)}
            style={{
              padding: '5px 12px', borderRadius: 20, fontSize: 12, fontWeight: 600,
              border: `1px solid ${isActive ? 'var(--brand)' : 'var(--line2)'}`,
              background: isActive ? 'var(--brand)' : 'var(--raised)',
              color: isActive ? '#fff' : 'var(--txt-mut)',
              cursor: 'pointer',
            }}
          >
            {c.label}
          </button>
        );
      })}
    </div>
  );
}

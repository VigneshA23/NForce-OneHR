import type { ActionGroup } from '../../api/audit';
import { TabBar } from '../TabBar';

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

// Each category swaps the whole log below it, so it's the page's tab bar (standard underline
// tabs), not a capsule filter. No per-category counts: the API only returns the active group's total.
export function AuditCategoryChips({ active, onChange, showAccess }: Props) {
  const tabs = GROUPS.filter(g => g.key !== 'ACCESS' || showAccess);
  return <TabBar tabs={tabs} active={active} onChange={onChange} ariaLabel="Audit category" />;
}

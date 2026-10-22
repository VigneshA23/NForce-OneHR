import { useRef, type KeyboardEvent } from 'react';
import { LayoutGrid, Users, Clock, CalendarCheck, Receipt, Package, Shield, MoreHorizontal } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { ActionGroup } from '../../api/audit';

const GROUPS: { key: ActionGroup | 'ALL'; label: string; icon: LucideIcon; color: string }[] = [
  { key: 'ALL', label: 'All', icon: LayoutGrid, color: 'var(--brand)' },
  { key: 'EMPLOYEE', label: 'Employee', icon: Users, color: 'var(--info)' },
  { key: 'ATTENDANCE', label: 'Attendance', icon: Clock, color: 'var(--warn)' },
  { key: 'LEAVE', label: 'Leave', icon: CalendarCheck, color: 'var(--ok)' },
  { key: 'EXPENSE', label: 'Expense', icon: Receipt, color: 'var(--brand)' },
  { key: 'ASSET', label: 'Asset', icon: Package, color: 'var(--info)' },
  { key: 'ACCESS', label: 'Access', icon: Shield, color: 'var(--risk)' },
  { key: 'OTHER', label: 'Other', icon: MoreHorizontal, color: 'var(--txt-mut)' },
];

interface Props {
  active: ActionGroup | 'ALL';
  onChange: (group: ActionGroup | 'ALL') => void;
  /** HR Admin never gets the Access chip — the server enforces the real scope regardless. */
  showAccess: boolean;
}

// Each category swaps the whole log below it, so it stays a tablist (arrow keys move between tabs),
// drawn as coloured pills with a category icon; the active one fills with its colour.
// No per-category counts: the API only returns the active group's total.
export function AuditCategoryChips({ active, onChange, showAccess }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const tabs = GROUPS.filter(g => g.key !== 'ACCESS' || showAccess);

  function onKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    if ((e.target as HTMLElement).getAttribute('role') !== 'tab') return;
    const i = tabs.findIndex(t => t.key === active);
    let next: number | null = null;
    if (e.key === 'ArrowRight') next = (i + 1) % tabs.length;
    else if (e.key === 'ArrowLeft') next = (i - 1 + tabs.length) % tabs.length;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = tabs.length - 1;
    if (next === null) return;
    e.preventDefault();
    const key = tabs[next].key;
    onChange(key);
    ref.current?.querySelector<HTMLButtonElement>(`[data-tab-key="${key}"]`)?.focus();
  }

  return (
    <div ref={ref} role="tablist" aria-label="Audit category" className="nf-audit-pills" onKeyDown={onKeyDown}>
      {tabs.map(t => {
        const on = t.key === active;
        return (
          <button
            key={t.key}
            type="button"
            role="tab"
            data-tab-key={t.key}
            aria-selected={on}
            tabIndex={on ? 0 : -1}
            className={`nf-audit-pill${on ? ' is-active' : ''}`}
            style={{ ['--pc' as string]: t.color }}
            onClick={() => onChange(t.key)}
          >
            <t.icon size={15} />
            <span>{t.label}</span>
          </button>
        );
      })}
    </div>
  );
}

import { Calendar, Hash, Users, CalendarCheck, Clock, Package, Shield } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { AuditLogStats } from '../../api/audit';
import DashTile from '../DashTile';

interface CardDef {
  key: string;
  label: string;
  value: number;
  icon: LucideIcon;
  color: string;
}

interface Props {
  stats: AuditLogStats | null;
  /** Super Admin only — HR Admin never sees a User & Access Management count. */
  showAccess: boolean;
}

// Same tile as the Home dashboard (components/DashTile); display only.
// This is now a personal activity history (each user sees only their own actions), so the
// categories summarize meaningful administrative decisions rather than org-wide volume:
// 6 cards for HR Admin, 7 for Super Admin (the extra "User & Access Management" card covers
// account/role/security actions performed ON another user — Super Admin's exclusive domain).
export function AuditStatCards({ stats, showAccess }: Props) {
  if (!stats) return null;

  const assetAndExpense = (stats.byGroup.ASSET ?? 0) + (stats.byGroup.EXPENSE ?? 0);

  const cards: CardDef[] = [
    { key: 'today', label: "Today's Actions", value: stats.todayCount, icon: Calendar, color: 'var(--brand-bright)' },
    { key: 'total', label: 'Total Actions', value: stats.totalCount, icon: Hash, color: 'var(--txt-mut)' },
    { key: 'employee', label: 'Employee Management Actions', value: stats.byGroup.EMPLOYEE ?? 0, icon: Users, color: 'var(--info)' },
    { key: 'leave', label: 'Leave Management Actions', value: stats.byGroup.LEAVE ?? 0, icon: CalendarCheck, color: 'var(--ok)' },
    { key: 'attendance', label: 'Attendance Management Actions', value: stats.byGroup.ATTENDANCE ?? 0, icon: Clock, color: 'var(--warn)' },
    { key: 'assetExpense', label: 'Asset & Expense Actions', value: assetAndExpense, icon: Package, color: 'var(--brand)' },
    ...(showAccess ? [{ key: 'access', label: 'User & Access Management', value: stats.byGroup.ACCESS ?? 0, icon: Shield, color: 'var(--risk)' }] : []),
  ];

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))', gap: 14 }}>
      {cards.map(c => (
        <DashTile key={c.key} icon={<c.icon size={20} />} accent={c.color} label={c.label} value={String(c.value)} />
      ))}
    </div>
  );
}

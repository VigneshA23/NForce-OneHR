import { ShieldCheck } from 'lucide-react';
import PageTitleBlock from '../../components/PageTitleBlock';
import { useAuthStore } from '../../store/authStore';
import { hasPermission } from '../../lib/permissions';
import { StatutoryRulesTab } from './StatutoryRulesTab';
import './payroll.css';

/**
 * Its own sidebar destination rather than a sixth Payroll Setup tab — PF/ESI/Professional Tax
 * rules get checked and revised often enough on their own (a new wage-ceiling notification, a
 * state's PT slab change) that burying it one click deeper than Structures/Pay Groups/
 * Compensation was the wrong trade-off. Same payroll:CONFIGURE:ALL gate as the rest of setup.
 */
export default function StatutoryCompliancePage() {
  const permissions = useAuthStore(s => s.permissions);
  const canConfigure = hasPermission(permissions, 'payroll', 'CONFIGURE', 'ALL');

  if (!canConfigure) {
    return <div style={{ padding: '24px 28px', color: 'var(--txt-mut)' }}>You don't have permission to configure statutory compliance.</div>;
  }

  return (
    <div style={{ padding: '24px 28px' }}>
      <PageTitleBlock icon={ShieldCheck} style={{ marginBottom: 20 }}>
        <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, letterSpacing: '-.01em', color: 'var(--txt)' }}>Statutory Compliance</h1>
        <p style={{ margin: '2px 0 0', fontSize: 13, color: 'var(--txt-mut)' }}>PF, ESI and Professional Tax rules by country, versioned by effective date — what every payslip's statutory deductions compute from.</p>
      </PageTitleBlock>
      <StatutoryRulesTab />
    </div>
  );
}

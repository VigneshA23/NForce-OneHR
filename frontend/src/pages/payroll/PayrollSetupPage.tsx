import { useEffect, useState } from 'react';
import { Settings, Receipt, FileStack, Wallet as WalletIcon, UserCog } from 'lucide-react';
import PageTitleBlock from '../../components/PageTitleBlock';
import { TabBar, type TabDef } from '../../components/TabBar';
import { useAuthStore } from '../../store/authStore';
import { hasPermission } from '../../lib/permissions';
import {
  payrollApi, type SalaryStructureResponse, type SalaryComponentCatalogResponse, type PayGroupResponse,
} from '../../api/payroll';
import { SalaryComponentsTab } from './SalaryComponentsTab';
import { SalaryStructuresTab } from './SalaryStructuresTab';
import { PayGroupsTab } from './PayGroupsTab';
import { EmployeeCompensationTab } from './EmployeeCompensationTab';
import './payroll.css';

type SetupSection = 'components' | 'structures' | 'pay-groups' | 'compensation';

const SECTIONS: TabDef<SetupSection>[] = [
  { key: 'components', label: 'Salary Components', icon: <Receipt size={15} /> },
  { key: 'structures', label: 'Salary Structures', icon: <FileStack size={15} /> },
  { key: 'pay-groups', label: 'Pay Groups', icon: <WalletIcon size={15} /> },
  { key: 'compensation', label: 'Employee Compensation', icon: <UserCog size={15} /> },
];

/**
 * Payroll's settings area, restructured into Keka-style named sections instead of one page —
 * each section is its own file under pages/payroll/, same split as PayrollRunDetailPage's step
 * tabs. Every section here has real backend CRUD behind it (see each Create*Request/Update*Request
 * DTO); nothing is a disabled placeholder — unlike the Run console's step tabs, there's no
 * not-yet-built config surface in this epic to stub out.
 *
 * Statutory Rules used to be a sixth tab here; it's now its own sidebar destination
 * (StatutoryCompliancePage) — admins check/edit it often enough on its own that burying it one
 * click deeper than Structures/Pay Groups/Compensation was the wrong trade-off.
 */
export default function PayrollSetupPage() {
  const token = useAuthStore(s => s.token)!;
  const permissions = useAuthStore(s => s.permissions);
  const canConfigure = hasPermission(permissions, 'payroll', 'CONFIGURE', 'ALL');
  const [section, setSection] = useState<SetupSection>('components');

  const [structures, setStructures] = useState<SalaryStructureResponse[]>([]);
  const [catalog, setCatalog] = useState<SalaryComponentCatalogResponse[]>([]);
  const [payGroups, setPayGroups] = useState<PayGroupResponse[]>([]);

  function reloadStructures() {
    payrollApi.listSalaryStructures(token).then(setStructures).catch(() => {});
  }
  function reloadCatalog() {
    payrollApi.listSalaryComponents(token).then(setCatalog).catch(() => {});
  }
  function reloadPayGroups() {
    payrollApi.listPayGroups(token).then(setPayGroups).catch(() => {});
  }

  useEffect(reloadStructures, [token]);
  useEffect(reloadCatalog, [token]);
  useEffect(reloadPayGroups, [token]);

  if (!canConfigure) {
    return <div style={{ padding: '24px 28px', color: 'var(--txt-mut)' }}>You don't have permission to configure payroll setup.</div>;
  }

  // Salary Structures' own component picker only ever offers active earning/deduction/
  // reimbursement components — ad-hoc types and inactive ones aren't structure material.
  const structureableCatalog = catalog.filter(c => ['EARNING', 'REIMBURSEMENT', 'DEDUCTION'].includes(c.type) && c.active);

  return (
    <div style={{ padding: '24px 28px' }}>
      <PageTitleBlock icon={Settings} style={{ marginBottom: 20 }}>
        <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, letterSpacing: '-.01em', color: 'var(--txt)' }}>Payroll Setup</h1>
        <p style={{ margin: '2px 0 0', fontSize: 13, color: 'var(--txt-mut)' }}>Components, structures, pay groups and compensation — everything a payroll run needs before it can generate. Statutory rules live under Statutory Compliance.</p>
      </PageTitleBlock>

      <TabBar tabs={SECTIONS} active={section} onChange={setSection} ariaLabel="Payroll setup sections" className="nf-pilltabs" style={{ marginBottom: 18 }} />

      {section === 'components' && <SalaryComponentsTab catalog={catalog} onChanged={reloadCatalog} />}
      {section === 'structures' && (
        <SalaryStructuresTab
          structures={structures} catalog={structureableCatalog}
          onCreated={s => setStructures(prev => [s, ...prev])}
          onUpdated={s => setStructures(prev => prev.map(x => (x.id === s.id ? s : x)))}
        />
      )}
      {section === 'pay-groups' && <PayGroupsTab payGroups={payGroups} onChanged={reloadPayGroups} />}
      {section === 'compensation' && <EmployeeCompensationTab structures={structures} payGroups={payGroups} />}
    </div>
  );
}

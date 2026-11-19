import { useEffect, useState } from 'react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { hasPermission } from '../../lib/permissions';
import { payrollApi, type EmployeeCompensationResponse, type PayGroupResponse, type SalaryStructureResponse } from '../../api/payroll';
import { labelStyle, inputStyle, FormRow, BtnPrimary, fmtCurrency, fmtDate } from '../payroll/payrollShared';

// Rendered on Step 4 ("Compensation") of the Create Employee wizard, right after the account is
// created. Optional by design (see the "Skip for Now" action in CreateEmployeeWizard.tsx) — an
// admin without payroll:CONFIGURE:ALL (payroll is Super-Admin-only by default; see V275) can't
// assign compensation here regardless, so they get the plain informational note instead of a form
// they'd only hit a 403 on.
export function CompensationFollowUp({ employeeId, employeeName }: { employeeId: string; employeeName: string }) {
  const token = useAuthStore(s => s.token)!;
  const permissions = useAuthStore(s => s.permissions);
  const { showToast } = useToast();
  const canConfigure = hasPermission(permissions, 'payroll', 'CONFIGURE', 'ALL');

  const [payGroups, setPayGroups] = useState<PayGroupResponse[]>([]);
  const [structures, setStructures] = useState<SalaryStructureResponse[]>([]);
  const [payGroupId, setPayGroupId] = useState('');
  const [structureId, setStructureId] = useState('');
  const [ctc, setCtc] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState(new Date().toISOString().slice(0, 10));
  const [submitting, setSubmitting] = useState(false);
  const [assigned, setAssigned] = useState<EmployeeCompensationResponse | null>(null);

  useEffect(() => {
    if (!canConfigure) return;
    payrollApi.listPayGroups(token).then(gs => { setPayGroups(gs); setPayGroupId(p => p || gs[0]?.id || ''); }).catch(() => {});
    payrollApi.listSalaryStructures(token).then(ss => { setStructures(ss); setStructureId(s => s || ss[0]?.id || ''); }).catch(() => {});
  }, [canConfigure, token]);

  if (!canConfigure) {
    return (
      <div style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: 14, marginBottom: 16, fontSize: 13, color: 'var(--txt-mut)' }}>
        <div style={{ color: 'var(--txt)', fontWeight: 600, marginBottom: 4 }}>Compensation not configured</div>
        Compensation details haven't been set up for this employee yet. Assigning compensation requires
        payroll access — once granted, it can be done from Payroll &rarr; Setup &rarr; Employee Compensation.
      </div>
    );
  }

  if (assigned) {
    return (
      <div style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: 14, marginBottom: 16, fontSize: 13 }}>
        <div style={{ color: 'var(--ok)', fontWeight: 600, marginBottom: 4 }}>Compensation assigned</div>
        <span style={{ color: 'var(--txt-mut)' }}>
          {assigned.structureName}, {fmtCurrency(assigned.ctc)} CTC, effective {fmtDate(assigned.effectiveFrom)}
        </span>
      </div>
    );
  }

  const valid = !!payGroupId && !!structureId && Number(ctc) > 0 && !!effectiveFrom;

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try {
      const comp = await payrollApi.assignCompensation({ employeeId, payGroupId, structureId, ctc: Number(ctc), effectiveFrom }, token);
      setAssigned(comp);
      showToast('success', `Compensation assigned to ${employeeName}`);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to assign compensation');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, padding: 16, marginBottom: 16 }}>
      <div style={{ color: 'var(--txt)', fontWeight: 600, marginBottom: 12, fontSize: 13.5 }}>Assign compensation (optional)</div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
        <FormRow>
          <label style={labelStyle}>Pay Group *</label>
          <select value={payGroupId} onChange={e => setPayGroupId(e.target.value)} style={inputStyle}>
            {payGroups.map(pg => <option key={pg.id} value={pg.id}>{pg.name}</option>)}
          </select>
        </FormRow>
        <FormRow>
          <label style={labelStyle}>Salary Structure *</label>
          <select value={structureId} onChange={e => setStructureId(e.target.value)} style={inputStyle}>
            {structures.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </FormRow>
        <FormRow>
          <label style={labelStyle}>Annual CTC *</label>
          <input type="number" min={0} value={ctc} onChange={e => setCtc(e.target.value)} style={inputStyle} placeholder="e.g. 1200000" />
        </FormRow>
        <FormRow>
          <label style={labelStyle}>Effective From *</label>
          <input type="date" value={effectiveFrom} onChange={e => setEffectiveFrom(e.target.value)} style={inputStyle} />
        </FormRow>
      </div>
      <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Assigning…' : 'Assign Compensation'}</BtnPrimary>
    </div>
  );
}

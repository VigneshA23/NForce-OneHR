import { useEffect, useState } from 'react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type SalaryStructureResponse, type EmployeeCompensationResponse } from '../../api/payroll';
import { fmtCurrency, fmtDate, FormRow, labelStyle, inputStyle, BtnPrimary, SectionHead, EmployeePicker, useEmployeeDirectory } from './payrollShared';

/** Keka's "Salary Revisions" sub-step — a run-scoped convenience wrapper around the same
 * assignCompensation call the Settings page's Employee Compensation tab uses for the general
 * case. A revision whose effectiveFrom lands on/before an already-closed cycle automatically
 * produces an Arrears row (see ArrearsTab) — this form itself doesn't need to know that.
 *
 * Employee picker is genuinely run-scoped (this run's own payslips, same pattern as
 * AdHocDeductionsTab) — it used to offer the full org roster despite being documented as a
 * run-scoped convenience, which was misleading even though the backend validates pay group
 * membership separately regardless. */
export function SalaryRevisionTab({ runId, payGroupId, canConfigure }: { runId: string; payGroupId: string; canConfigure: boolean }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { nameFor } = useEmployeeDirectory();
  const [employeeIds, setEmployeeIds] = useState<string[]>([]);
  const [structures, setStructures] = useState<SalaryStructureResponse[]>([]);
  const [employeeId, setEmployeeId] = useState('');
  const [structureId, setStructureId] = useState('');
  const [ctc, setCtc] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [current, setCurrent] = useState<EmployeeCompensationResponse | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    payrollApi.listPayslips(runId, token).then(payslips => setEmployeeIds(payslips.map(p => p.employeeId))).catch(() => {});
  }, [runId, token]);

  useEffect(() => {
    payrollApi.listSalaryStructures(token).then(setStructures).catch(() => {});
  }, [token]);

  useEffect(() => {
    if (!structureId && structures[0]) setStructureId(structures[0].id);
  }, [structures, structureId]);

  useEffect(() => {
    if (!employeeId) { setCurrent(null); return; }
    payrollApi.getCurrentCompensation(employeeId, token).then(setCurrent).catch(() => setCurrent(null));
  }, [employeeId, token]);

  const valid = !!employeeId && !!structureId && Number(ctc) > 0 && !!effectiveFrom;

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try {
      const comp = await payrollApi.assignCompensation({ employeeId, payGroupId, structureId, ctc: Number(ctc), effectiveFrom }, token);
      setCurrent(comp);
      setCtc('');
      setEffectiveFrom('');
      showToast('success', 'Compensation revised — a backdated effective date against an already-closed cycle will show up under Arrears once processed');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to revise compensation');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, padding: '18px 22px' }}>
      <SectionHead title="Salary Revision" />
      {!canConfigure && <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>You don't have permission to revise compensation.</div>}
      {canConfigure && (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 14 }}>
            <FormRow>
              <label style={labelStyle}>Employee *</label>
              <EmployeePicker value={employeeId} onChange={setEmployeeId} options={employeeIds.map(id => ({ id, label: nameFor(id) }))} />
            </FormRow>
            <FormRow>
              <label style={labelStyle}>Salary Structure *</label>
              <select value={structureId} onChange={e => setStructureId(e.target.value)} style={inputStyle}>
                {structures.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </FormRow>
            <FormRow>
              <label style={labelStyle}>New Annual CTC *</label>
              <input type="number" min={0} value={ctc} onChange={e => setCtc(e.target.value)} style={inputStyle} placeholder="e.g. 1300000" />
            </FormRow>
            <FormRow>
              <label style={labelStyle}>Effective From *</label>
              <input type="date" value={effectiveFrom} onChange={e => setEffectiveFrom(e.target.value)} style={inputStyle} />
            </FormRow>
          </div>
          {current && (
            <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 14 }}>
              Current: <strong style={{ color: 'var(--txt)' }}>{current.structureName}</strong>, {fmtCurrency(current.ctc)} CTC, effective {fmtDate(current.effectiveFrom)}
            </div>
          )}
          <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Revising…' : 'Revise Compensation'}</BtnPrimary>
        </>
      )}
    </div>
  );
}

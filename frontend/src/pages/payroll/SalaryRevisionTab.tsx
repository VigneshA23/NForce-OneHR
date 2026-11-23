import { useEffect, useState } from 'react';
import { useAuthStore } from '../../store/authStore';
import { payrollApi, type SalaryStructureResponse, type EmployeeCompensationResponse, type PayGroupResponse } from '../../api/payroll';
import { fmtCurrency, fmtDate, labelStyle, BtnPrimary, SectionHead, EmployeePicker, useEmployeeDirectory } from './payrollShared';
import { ReviseCompensationScreen } from './EmployeeCompensationTab';

/** Keka's "Salary Revisions" sub-step - a run-scoped convenience wrapper around the same
 * ReviseCompensationScreen (and the assignCompensation call it makes) the Settings page's
 * Employee Compensation tab uses for the general case. A revision whose effectiveFrom lands on/
 * before an already-closed cycle automatically produces an Arrears row (see ArrearsTab) - this
 * form itself doesn't need to know that.
 *
 * Employee picker is genuinely run-scoped (this run's own payslips, same pattern as
 * AdHocDeductionsTab) - it used to offer the full org roster despite being documented as a
 * run-scoped convenience, which was misleading even though the backend validates pay group
 * membership separately regardless. */
export function SalaryRevisionTab({ runId, payGroupId, canConfigure }: { runId: string; payGroupId: string; canConfigure: boolean }) {
  const token = useAuthStore(s => s.token)!;
  const { employees, nameFor } = useEmployeeDirectory();
  const [employeeIds, setEmployeeIds] = useState<string[]>([]);
  const [structures, setStructures] = useState<SalaryStructureResponse[]>([]);
  const [payGroups, setPayGroups] = useState<PayGroupResponse[]>([]);
  const [employeeId, setEmployeeId] = useState('');
  const [current, setCurrent] = useState<EmployeeCompensationResponse | null>(null);
  const [showRevise, setShowRevise] = useState(false);

  useEffect(() => {
    payrollApi.listPayslips(runId, token).then(payslips => setEmployeeIds(payslips.map(p => p.employeeId))).catch(() => {});
  }, [runId, token]);

  useEffect(() => {
    payrollApi.listSalaryStructures(token).then(setStructures).catch(() => {});
  }, [token]);

  useEffect(() => {
    payrollApi.listPayGroups(token).then(setPayGroups).catch(() => {});
  }, [token]);

  useEffect(() => {
    if (!employeeId) { setCurrent(null); return; }
    payrollApi.getCurrentCompensation(employeeId, token).then(setCurrent).catch(() => setCurrent(null));
  }, [employeeId, token]);

  const employee = employees.find(e => e.userId === employeeId) ?? null;
  const thisPayGroup = payGroups.filter(pg => pg.id === payGroupId);

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, padding: '18px 22px' }}>
      <SectionHead title="Salary Revision" />
      {!canConfigure && <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>You don't have permission to revise compensation.</div>}
      {canConfigure && (
        <>
          <div style={{ maxWidth: 320, marginBottom: 14 }}>
            <label style={labelStyle}>Employee (from this run)</label>
            <EmployeePicker value={employeeId} onChange={setEmployeeId} options={employeeIds.map(id => ({ id, label: nameFor(id) }))} />
          </div>
          {employeeId && (
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 14 }}>
              <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>
                {current
                  ? <>Current: <strong style={{ color: 'var(--txt)' }}>{current.structureName}</strong>, {fmtCurrency(current.ctc)} CTC, effective {fmtDate(current.effectiveFrom)}</>
                  : 'No compensation assigned yet.'}
              </div>
              <BtnPrimary onClick={() => setShowRevise(true)} disabled={!employee || thisPayGroup.length === 0}>
                {current ? 'Revise Compensation' : 'Assign Compensation'}
              </BtnPrimary>
            </div>
          )}
        </>
      )}

      {showRevise && employee && (
        <ReviseCompensationScreen
          employee={employee} current={current} structures={structures} payGroups={thisPayGroup}
          onClose={() => setShowRevise(false)} onSaved={() => setEmployeeId('')}
        />
      )}
    </div>
  );
}

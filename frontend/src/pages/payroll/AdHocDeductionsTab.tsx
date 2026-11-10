import { useEffect, useState } from 'react';
import { Plus, Ban } from 'lucide-react';
import { ActionDock } from '../../components/ActionDock';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type AdHocDeductionResponse, type SalaryComponentCatalogResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, StatusBadge, EmptyRow, Modal, FormRow,
  labelStyle, inputStyle, BtnPrimary, BtnGhost, SectionHead, useEmployeeDirectory,
} from './payrollShared';

function CreateDeductionModal({ employeeId, components, onClose, onCreated }: {
  employeeId: string;
  components: SalaryComponentCatalogResponse[];
  onClose: () => void;
  onCreated: (d: AdHocDeductionResponse) => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [componentName, setComponentName] = useState(components[0]?.name ?? '');
  const [totalAmount, setTotalAmount] = useState('');
  const [installmentAmount, setInstallmentAmount] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const valid = !!componentName && Number(totalAmount) > 0 && Number(installmentAmount) > 0 && !!reason.trim();

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try {
      const deduction = await payrollApi.createAdHocDeduction({
        employeeId, componentName, totalAmount: Number(totalAmount), installmentAmount: Number(installmentAmount), reason: reason.trim(),
      }, token);
      onCreated(deduction);
      onClose();
      showToast('success', 'Ad-hoc deduction created');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to create deduction');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal title="Add Ad-hoc Deduction" subtitle="Applied as installments across future payroll runs until the total is recovered." onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Component *</label>
        <select value={componentName} onChange={e => setComponentName(e.target.value)} style={inputStyle}>
          {components.map(c => <option key={c.id} value={c.name}>{c.name}</option>)}
        </select>
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Total Amount *</label>
        <input type="number" min={0} step="0.01" value={totalAmount} onChange={e => setTotalAmount(e.target.value)} style={inputStyle} />
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Installment Amount *</label>
        <input type="number" min={0} step="0.01" value={installmentAmount} onChange={e => setInstallmentAmount(e.target.value)} style={inputStyle} />
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Reason *</label>
        <textarea value={reason} onChange={e => setReason(e.target.value)} style={{ ...inputStyle, minHeight: 70, resize: 'vertical' }} />
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Creating…' : 'Create Deduction'}</BtnPrimary>
      </div>
    </Modal>
  );
}

export function AdHocDeductionsTab({ runId, canConfigure }: { runId: string; canConfigure: boolean }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { nameFor } = useEmployeeDirectory();
  const [employeeIds, setEmployeeIds] = useState<string[]>([]);
  const [employeeId, setEmployeeId] = useState('');
  const [deductions, setDeductions] = useState<AdHocDeductionResponse[]>([]);
  const [components, setComponents] = useState<SalaryComponentCatalogResponse[]>([]);
  const [showAdd, setShowAdd] = useState(false);

  // This run's own employees — ad-hoc deductions are employee-scoped, not run-scoped (they're
  // applied as installments across whichever future runs are open when they're created).
  useEffect(() => {
    payrollApi.listPayslips(runId, token).then(payslips => setEmployeeIds(payslips.map(p => p.employeeId))).catch(() => {});
  }, [runId, token]);

  useEffect(() => {
    payrollApi.listSalaryComponents(token)
      .then(all => setComponents(all.filter(c => c.type === 'AD_HOC_DEDUCTION' && c.active)))
      .catch(() => {});
  }, [token]);

  function reload() {
    if (!employeeId) { setDeductions([]); return; }
    payrollApi.listAdHocDeductionsByEmployee(employeeId, token).then(setDeductions).catch(() => showToast('error', 'Failed to load deductions'));
  }

  useEffect(reload, [employeeId, token]);

  async function cancel(deductionId: string) {
    try {
      await payrollApi.cancelAdHocDeduction(deductionId, token);
      showToast('success', 'Deduction cancelled');
      reload();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to cancel deduction');
    }
  }

  return (
    <div>
      <SectionHead
        title="Ad-hoc Deductions"
        action={canConfigure && employeeId && <BtnPrimary onClick={() => setShowAdd(true)}><Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Add Deduction</BtnPrimary>}
      />
      <div style={{ marginBottom: 16, maxWidth: 320 }}>
        <label style={labelStyle}>Employee (from this run)</label>
        <select value={employeeId} onChange={e => setEmployeeId(e.target.value)} style={inputStyle}>
          <option value="">Select…</option>
          {employeeIds.map(id => <option key={id} value={id}>{nameFor(id)}</option>)}
        </select>
      </div>
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{['Component', 'Total', 'Installment', 'Remaining', 'Status', 'Reason', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {deductions.map(d => (
              <tr key={d.id}>
                <td style={tdStyle}>{d.componentName}</td>
                <td style={tdStyle}>{fmtCurrency(d.totalAmount)}</td>
                <td style={tdStyle}>{fmtCurrency(d.installmentAmount)}</td>
                <td style={tdStyle}>{fmtCurrency(d.remainingBalance)}</td>
                <td style={tdStyle}><StatusBadge status={d.status} /></td>
                <td style={tdStyle}>{d.reason}</td>
                <td style={tdStyle}>
                  {canConfigure && d.status === 'ACTIVE' && (
                    <ActionDock items={[{ label: 'Cancel', icon: Ban, danger: true, onClick: () => cancel(d.id) }]} />
                  )}
                </td>
              </tr>
            ))}
            {deductions.length === 0 && <EmptyRow cols={7} msg={employeeId ? 'No ad-hoc deductions for this employee.' : 'Select an employee to view their deductions.'} />}
          </tbody>
        </table>
      </div>
      {showAdd && employeeId && (
        <CreateDeductionModal
          employeeId={employeeId}
          components={components}
          onClose={() => setShowAdd(false)}
          onCreated={d => setDeductions(prev => [d, ...prev])}
        />
      )}
    </div>
  );
}

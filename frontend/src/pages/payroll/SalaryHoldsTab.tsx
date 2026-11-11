import { useEffect, useState } from 'react';
import { Plus, Unlock } from 'lucide-react';
import { ActionDock } from '../../components/ActionDock';
import { PersonCellById } from '../../components/PersonCell';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type SalaryHoldResponse, type SalaryHoldType } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtDateTime, StatusBadge, EmptyRow, Modal, FormRow, labelStyle,
  inputStyle, BtnPrimary, BtnGhost, SectionHead, NoPermissionNote, ConfirmDialog, EmployeePicker, useEmployeeDirectory,
} from './payrollShared';

function CreateHoldModal({ runId, fixedType, onClose, onCreated }: {
  runId: string; fixedType?: SalaryHoldType; onClose: () => void; onCreated: (h: SalaryHoldResponse) => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { employees } = useEmployeeDirectory();
  const [employeeId, setEmployeeId] = useState('');
  const [type, setType] = useState<SalaryHoldType>(fixedType ?? 'PROCESSING');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const valid = !!employeeId && !!reason.trim();

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try {
      const hold = await payrollApi.createSalaryHold(runId, { employeeId, reason: reason.trim(), type }, token);
      onCreated(hold);
      onClose();
      showToast('success', 'Hold created');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to create hold');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal
      title={fixedType === 'PAYOUT' ? 'Add Payout Hold' : fixedType === 'PROCESSING' ? 'Add Processing Hold' : 'Add Salary Hold'}
      subtitle="PROCESSING excludes this employee from this run's payslip generation entirely. PAYOUT generates their payslip as normal but withholds disbursement."
      onClose={onClose}
    >
      <FormRow>
        <label style={labelStyle}>Employee *</label>
        <EmployeePicker value={employeeId} onChange={setEmployeeId} options={employees.map(e => ({ id: e.userId, label: e.fullName }))} />
      </FormRow>
      {!fixedType && (
        <FormRow>
          <label style={labelStyle}>Type *</label>
          <select value={type} onChange={e => setType(e.target.value as SalaryHoldType)} style={inputStyle}>
            <option value="PROCESSING">Processing (exclude from this run)</option>
            <option value="PAYOUT">Payout (withhold disbursement)</option>
          </select>
        </FormRow>
      )}
      <FormRow>
        <label style={labelStyle}>Reason *</label>
        <textarea value={reason} onChange={e => setReason(e.target.value)} style={{ ...inputStyle, minHeight: 70, resize: 'vertical' }} />
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Creating…' : 'Create Hold'}</BtnPrimary>
      </div>
    </Modal>
  );
}

/** `holdType` scopes this to one of Keka's two separate steps (Salary Processing on Hold / Salary
 * Payout on Hold) — same underlying SalaryHold data, just filtered and pre-typed per step, instead
 * of a combined type column. Omit it to show both (used nowhere currently, kept for flexibility). */
export function SalaryHoldsTab({ runId, holdType, canConfigure }: { runId: string; holdType?: SalaryHoldType; canConfigure: boolean }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { nameFor } = useEmployeeDirectory();
  const [holds, setHolds] = useState<SalaryHoldResponse[]>([]);
  const [showAdd, setShowAdd] = useState(false);
  const [releasing, setReleasing] = useState<SalaryHoldResponse | null>(null);

  function reload() {
    payrollApi.listSalaryHolds(runId, token).then(setHolds).catch(() => showToast('error', 'Failed to load salary holds'));
  }

  useEffect(reload, [runId, token]);

  async function release(holdId: string) {
    try {
      await payrollApi.releaseSalaryHold(runId, holdId, token);
      showToast('success', 'Hold released');
      reload();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to release hold');
    }
  }

  const visible = holdType ? holds.filter(h => h.holdType === holdType) : holds;
  const columns = holdType ? ['Employee', 'Status', 'Reason', 'Created By', 'Released By', ''] : ['Employee', 'Type', 'Status', 'Reason', 'Created By', 'Released By', ''];

  return (
    <div>
      <SectionHead
        title={holdType === 'PAYOUT' ? 'Salary Payout on Hold' : holdType === 'PROCESSING' ? 'Salary Processing on Hold' : 'Salary Holds'}
        action={canConfigure
          ? <BtnPrimary onClick={() => setShowAdd(true)}><Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Add Employee</BtnPrimary>
          : <NoPermissionNote action="add holds" />}
      />
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{columns.map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {visible.map(h => (
              <tr key={h.id}>
                <td style={tdStyle}><PersonCellById userId={h.employeeId} name={nameFor(h.employeeId)} /></td>
                {!holdType && <td style={tdStyle}>{h.holdType}</td>}
                <td style={tdStyle}><StatusBadge status={h.status} /></td>
                <td style={tdStyle}>{h.reason}</td>
                <td style={tdStyle}>{h.createdByName} · {fmtDateTime(h.createdAt)}</td>
                <td style={tdStyle}>{h.releasedByName ? `${h.releasedByName} · ${fmtDateTime(h.releasedAt)}` : '–'}</td>
                <td style={tdStyle}>
                  {canConfigure && h.status === 'ACTIVE' && (
                    <ActionDock items={[{ label: 'Release', icon: Unlock, onClick: () => setReleasing(h) }]} />
                  )}
                </td>
              </tr>
            ))}
            {visible.length === 0 && <EmptyRow cols={columns.length} msg="No records found." />}
          </tbody>
        </table>
      </div>
      {showAdd && (
        <CreateHoldModal runId={runId} fixedType={holdType} onClose={() => setShowAdd(false)} onCreated={h => setHolds(prev => [h, ...prev])} />
      )}
      {releasing && (
        <ConfirmDialog
          title="Release Hold"
          message={`Release the ${releasing.holdType.toLowerCase()} hold for ${nameFor(releasing.employeeId)}? They'll be included again starting with the next regenerate.`}
          confirmLabel="Release"
          onClose={() => setReleasing(null)}
          onConfirm={() => release(releasing.id)}
        />
      )}
    </div>
  );
}

import { useEffect, useState } from 'react';
import { Plus, Unlock } from 'lucide-react';
import { ActionDock } from '../../components/ActionDock';
import { PersonCellById } from '../../components/PersonCell';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type SalaryHoldResponse, type SalaryHoldType } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtDateTime, StatusBadge, EmptyRow, Modal, FormRow, labelStyle,
  inputStyle, BtnPrimary, BtnGhost, SectionHead, useEmployeeDirectory,
} from './payrollShared';

function CreateHoldModal({ runId, onClose, onCreated }: { runId: string; onClose: () => void; onCreated: (h: SalaryHoldResponse) => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { employees } = useEmployeeDirectory();
  const [employeeId, setEmployeeId] = useState('');
  const [type, setType] = useState<SalaryHoldType>('PROCESSING');
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
    <Modal title="Add Salary Hold" subtitle="PROCESSING excludes this employee from this run's payslip generation entirely. PAYOUT generates their payslip as normal but withholds disbursement." onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Employee *</label>
        <select value={employeeId} onChange={e => setEmployeeId(e.target.value)} style={inputStyle}>
          <option value="">Select…</option>
          {employees.map(e => <option key={e.userId} value={e.userId}>{e.fullName}</option>)}
        </select>
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Type *</label>
        <select value={type} onChange={e => setType(e.target.value as SalaryHoldType)} style={inputStyle}>
          <option value="PROCESSING">Processing (exclude from this run)</option>
          <option value="PAYOUT">Payout (withhold disbursement)</option>
        </select>
      </FormRow>
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

export function SalaryHoldsTab({ runId, canConfigure }: { runId: string; canConfigure: boolean }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { nameFor } = useEmployeeDirectory();
  const [holds, setHolds] = useState<SalaryHoldResponse[]>([]);
  const [showAdd, setShowAdd] = useState(false);

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

  return (
    <div>
      <SectionHead
        title="Salary Holds"
        action={canConfigure && <BtnPrimary onClick={() => setShowAdd(true)}><Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Add Hold</BtnPrimary>}
      />
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{['Employee', 'Type', 'Status', 'Reason', 'Created By', 'Released By', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {holds.map(h => (
              <tr key={h.id}>
                <td style={tdStyle}><PersonCellById userId={h.employeeId} name={nameFor(h.employeeId)} /></td>
                <td style={tdStyle}>{h.holdType}</td>
                <td style={tdStyle}><StatusBadge status={h.status} /></td>
                <td style={tdStyle}>{h.reason}</td>
                <td style={tdStyle}>{h.createdByName} · {fmtDateTime(h.createdAt)}</td>
                <td style={tdStyle}>{h.releasedByName ? `${h.releasedByName} · ${fmtDateTime(h.releasedAt)}` : '–'}</td>
                <td style={tdStyle}>
                  {canConfigure && h.status === 'ACTIVE' && (
                    <ActionDock items={[{ label: 'Release', icon: Unlock, onClick: () => release(h.id) }]} />
                  )}
                </td>
              </tr>
            ))}
            {holds.length === 0 && <EmptyRow cols={7} msg="No salary holds for this run." />}
          </tbody>
        </table>
      </div>
      {showAdd && (
        <CreateHoldModal runId={runId} onClose={() => setShowAdd(false)} onCreated={h => setHolds(prev => [h, ...prev])} />
      )}
    </div>
  );
}

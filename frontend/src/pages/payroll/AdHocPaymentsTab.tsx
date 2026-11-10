import { useEffect, useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { PersonCellById } from '../../components/PersonCell';
import { ActionDock } from '../../components/ActionDock';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type AdHocPaymentResponse, type SalaryComponentCatalogResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, fmtDateTime, EmptyRow, Modal, FormRow, labelStyle,
  inputStyle, BtnPrimary, BtnGhost, SectionHead, useEmployeeDirectory,
} from './payrollShared';

function AddPaymentModal({ runId, components, onClose, onCreated }: {
  runId: string;
  components: SalaryComponentCatalogResponse[];
  onClose: () => void;
  onCreated: (p: AdHocPaymentResponse) => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { employees } = useEmployeeDirectory();
  const [employeeId, setEmployeeId] = useState('');
  const [componentName, setComponentName] = useState(components[0]?.name ?? '');
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const valid = !!employeeId && !!componentName && Number(amount) > 0 && !!reason.trim();

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try {
      const payment = await payrollApi.addAdHocPayment(runId, { employeeId, componentName, amount: Number(amount), reason: reason.trim() }, token);
      onCreated(payment);
      onClose();
      showToast('success', 'Ad-hoc payment added');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to add payment');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal title="Add Ad-hoc Payment" subtitle="A one-off amount for this run, added at face value — never prorated for LOP or joiner/exit." onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Employee *</label>
        <select value={employeeId} onChange={e => setEmployeeId(e.target.value)} style={inputStyle}>
          <option value="">Select…</option>
          {employees.map(e => <option key={e.userId} value={e.userId}>{e.fullName}</option>)}
        </select>
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Component *</label>
        <select value={componentName} onChange={e => setComponentName(e.target.value)} style={inputStyle}>
          {components.map(c => <option key={c.id} value={c.name}>{c.name}</option>)}
        </select>
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Amount *</label>
        <input type="number" min={0} step="0.01" value={amount} onChange={e => setAmount(e.target.value)} style={inputStyle} />
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Reason *</label>
        <textarea value={reason} onChange={e => setReason(e.target.value)} style={{ ...inputStyle, minHeight: 70, resize: 'vertical' }} />
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Adding…' : 'Add Payment'}</BtnPrimary>
      </div>
    </Modal>
  );
}

export function AdHocPaymentsTab({ runId, canConfigure }: { runId: string; canConfigure: boolean }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { nameFor } = useEmployeeDirectory();
  const [payments, setPayments] = useState<AdHocPaymentResponse[]>([]);
  const [components, setComponents] = useState<SalaryComponentCatalogResponse[]>([]);
  const [showAdd, setShowAdd] = useState(false);

  function reload() {
    payrollApi.listAdHocPayments(runId, token).then(setPayments).catch(() => showToast('error', 'Failed to load ad-hoc payments'));
  }

  useEffect(reload, [runId, token]);
  useEffect(() => {
    payrollApi.listSalaryComponents(token)
      .then(all => setComponents(all.filter(c => c.type === 'AD_HOC_EARNING' && c.active)))
      .catch(() => {});
  }, [token]);

  async function remove(paymentId: string) {
    try {
      await payrollApi.removeAdHocPayment(runId, paymentId, token);
      showToast('success', 'Payment removed');
      reload();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to remove payment');
    }
  }

  return (
    <div>
      <SectionHead
        title="Ad-hoc Payments"
        action={canConfigure && <BtnPrimary onClick={() => setShowAdd(true)}><Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Add Payment</BtnPrimary>}
      />
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{['Employee', 'Component', 'Amount', 'Reason', 'Added By', 'Added At', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {payments.map(p => (
              <tr key={p.id}>
                <td style={tdStyle}><PersonCellById userId={p.employeeId} name={nameFor(p.employeeId)} /></td>
                <td style={tdStyle}>{p.componentName}</td>
                <td style={tdStyle}>{fmtCurrency(p.amount)}</td>
                <td style={tdStyle}>{p.reason}</td>
                <td style={tdStyle}>{p.addedByName}</td>
                <td style={tdStyle}>{fmtDateTime(p.addedAt)}</td>
                <td style={tdStyle}>
                  {canConfigure && <ActionDock items={[{ label: 'Remove', icon: Trash2, danger: true, onClick: () => remove(p.id) }]} />}
                </td>
              </tr>
            ))}
            {payments.length === 0 && <EmptyRow cols={7} msg="No ad-hoc payments for this run." />}
          </tbody>
        </table>
      </div>
      {showAdd && (
        <AddPaymentModal
          runId={runId}
          components={components}
          onClose={() => setShowAdd(false)}
          onCreated={p => setPayments(prev => [p, ...prev])}
        />
      )}
    </div>
  );
}

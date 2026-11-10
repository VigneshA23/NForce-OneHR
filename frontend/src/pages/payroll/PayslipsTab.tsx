import { useEffect, useState } from 'react';
import { PersonCellById } from '../../components/PersonCell';
import { usePaged } from '../../components/usePaged';
import { TablePagination } from '../../components/TablePagination';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayslipResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, EmptyRow, Modal, FormRow, labelStyle, inputStyle,
  BtnPrimary, BtnGhost, SectionHead, useEmployeeDirectory,
} from './payrollShared';

function OverrideLopModal({ payGroupId, employeeId, cycleStart, onClose, onDone }: {
  payGroupId: string; employeeId: string; cycleStart: string; onClose: () => void; onDone: () => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [overrideLopDays, setOverrideLopDays] = useState('');
  const [overrideReason, setOverrideReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function submit() {
    if (!overrideReason.trim()) return;
    setSubmitting(true);
    try {
      await payrollApi.overrideLop({
        employeeId, payGroupId, referenceDate: cycleStart,
        overrideLopDays: overrideLopDays === '' ? null : Number(overrideLopDays),
        overrideReason: overrideReason.trim(),
      }, token);
      showToast('success', 'LOP override saved — recompute the run to reflect it in the payslip');
      onDone();
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to save override');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal title="Override LOP" subtitle="Leave the days field blank to clear an existing override and revert to the computed value." onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Override LOP Days</label>
        <input type="number" min={0} step="0.5" value={overrideLopDays} onChange={e => setOverrideLopDays(e.target.value)} style={inputStyle} placeholder="Leave blank to clear override" />
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Reason *</label>
        <textarea value={overrideReason} onChange={e => setOverrideReason(e.target.value)} style={{ ...inputStyle, minHeight: 70, resize: 'vertical' }} />
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!overrideReason.trim() || submitting}>{submitting ? 'Saving…' : 'Save Override'}</BtnPrimary>
      </div>
    </Modal>
  );
}

function RollbackPayslipModal({ onClose, onConfirm }: { onClose: () => void; onConfirm: (reason: string) => Promise<void> }) {
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  async function submit() {
    if (!reason.trim()) return;
    setSubmitting(true);
    try { await onConfirm(reason.trim()); onClose(); } finally { setSubmitting(false); }
  }
  return (
    <Modal title="Rollback This Employee's Payslip" subtitle="Recomputes just this employee's payslip, leaving the rest of the run untouched." onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Reason *</label>
        <textarea value={reason} onChange={e => setReason(e.target.value)} style={{ ...inputStyle, minHeight: 70, resize: 'vertical' }} />
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!reason.trim() || submitting}>{submitting ? 'Rolling back…' : 'Rollback'}</BtnPrimary>
      </div>
    </Modal>
  );
}

function PayslipDetailModal({ payslip, payGroupId, cycleStart, employeeName, canConfigure, onClose, onReload }: {
  payslip: PayslipResponse; payGroupId: string; cycleStart: string; employeeName: string; canConfigure: boolean; onClose: () => void; onReload: () => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [showOverride, setShowOverride] = useState(false);
  const [showRollback, setShowRollback] = useState(false);

  async function recompute() {
    try {
      await payrollApi.recomputeLop({ employeeId: payslip.employeeId, payGroupId, runId: payslip.payrollRunId }, token);
      showToast('success', 'LOP recomputed — regenerate the run to reflect it in the payslip');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Recompute failed');
    }
  }

  return (
    <Modal title="Payslip" subtitle={`${employeeName} · ${fmtCurrency(payslip.netPay)} net`} onClose={onClose}>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 10, marginBottom: 16 }}>
        <div><div style={labelStyle}>Cycle Days</div><div>{payslip.cycleDays}</div></div>
        <div><div style={labelStyle}>Employed Days</div><div>{payslip.employedDays}</div></div>
        <div><div style={labelStyle}>LOP Days</div><div>{payslip.lopDays}</div></div>
        <div><div style={labelStyle}>Proration Factor</div><div>{payslip.prorationFactor}</div></div>
      </div>
      <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 16 }}>
        <thead><tr>{['Component', 'Type', 'Amount', 'Prorated'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
        <tbody>
          {payslip.lineItems.map(li => (
            <tr key={li.componentId}>
              <td style={tdStyle}>{li.componentName}</td>
              <td style={tdStyle}>{li.componentType}</td>
              <td style={tdStyle}>{fmtCurrency(li.amount)}</td>
              <td style={tdStyle}>{li.prorated ? 'Yes' : 'No'}</td>
            </tr>
          ))}
          {payslip.lineItems.length === 0 && <EmptyRow cols={4} msg="No line items." />}
        </tbody>
      </table>
      {canConfigure && (
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          <BtnGhost onClick={recompute}>Recompute LOP</BtnGhost>
          <BtnGhost onClick={() => setShowOverride(true)}>Override LOP</BtnGhost>
          <BtnGhost onClick={() => setShowRollback(true)}>Rollback This Payslip</BtnGhost>
        </div>
      )}
      {showOverride && (
        <OverrideLopModal
          payGroupId={payGroupId} employeeId={payslip.employeeId} cycleStart={cycleStart}
          onClose={() => setShowOverride(false)} onDone={onReload}
        />
      )}
      {showRollback && (
        <RollbackPayslipModal
          onClose={() => setShowRollback(false)}
          onConfirm={async (reason) => {
            await payrollApi.rollbackEmployeePayslip(payslip.payrollRunId, payslip.employeeId, reason, token);
            showToast('success', 'Payslip rolled back and recomputed');
            onReload();
            onClose();
          }}
        />
      )}
    </Modal>
  );
}

export function PayslipsTab({ runId, payGroupId, cycleStart, canConfigure }: { runId: string; payGroupId: string; cycleStart: string; canConfigure: boolean }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [payslips, setPayslips] = useState<PayslipResponse[]>([]);
  const [selected, setSelected] = useState<PayslipResponse | null>(null);
  const { nameFor } = useEmployeeDirectory();

  function reload() {
    payrollApi.listPayslips(runId, token).then(setPayslips).catch(() => showToast('error', 'Failed to load payslips'));
  }

  useEffect(reload, [runId, token]);

  const { pageRows, pagerProps } = usePaged(payslips, [runId]);

  return (
    <div>
      <SectionHead title="Payslips" />
      <div style={panelStyle}>
        <table className="nf-pr-table nf-pr-table-clickable" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{['Employee', 'Gross', 'Deductions', 'Net', 'LOP Days', 'Released', 'Withheld'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {pageRows.map(p => (
              <tr key={p.id} onClick={() => setSelected(p)}>
                <td style={tdStyle}><PersonCellById userId={p.employeeId} name={nameFor(p.employeeId)} /></td>
                <td style={tdStyle}>{fmtCurrency(p.grossPay)}</td>
                <td style={tdStyle}>{fmtCurrency(p.totalDeductions)}</td>
                <td style={tdStyle}>{fmtCurrency(p.netPay)}</td>
                <td style={tdStyle}>{p.lopDays}</td>
                <td style={tdStyle}>{p.released ? 'Yes' : 'No'}</td>
                <td style={tdStyle}>{p.paymentWithheld ? 'Yes' : 'No'}</td>
              </tr>
            ))}
            {pageRows.length === 0 && <EmptyRow cols={7} msg="No payslips yet — generate the draft first." />}
          </tbody>
        </table>
        <TablePagination {...pagerProps} noun="payslips" />
      </div>
      {selected && (
        <PayslipDetailModal
          payslip={selected} payGroupId={payGroupId} cycleStart={cycleStart} employeeName={nameFor(selected.employeeId)} canConfigure={canConfigure}
          onClose={() => setSelected(null)} onReload={reload}
        />
      )}
    </div>
  );
}

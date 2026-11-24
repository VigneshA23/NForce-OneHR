import { useEffect, useState } from 'react';
import { FileText, ListChecks, Download, Send, FileSpreadsheet, Landmark } from 'lucide-react';
import { ActionDock } from '../../components/ActionDock';
import { PersonCellById } from '../../components/PersonCell';
import { usePaged } from '../../components/usePaged';
import { TablePagination } from '../../components/TablePagination';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayrollRunStatus, type PayslipResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, EmptyRow, Modal, FormRow, labelStyle, inputStyle,
  BtnPrimary, BtnGhost, SectionHead, FilterBar, useEmployeeDirectory,
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
      showToast('success', 'LOP override saved - recompute the run to reflect it in the payslip');
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
        <label style={labelStyle}>Reason</label>
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
        <label style={labelStyle}>Reason</label>
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
      showToast('success', 'LOP recomputed - regenerate the run to reflect it in the payslip');
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

/** Centralized "Distribute Payslips" - one confirm for the whole run, never a per-employee send
 * button (deliberately removed from the row-level ActionDock). Publishing to the portal is what
 * actually makes a payslip appear under My Finance - a run reaching Approved/Disbursed no longer
 * does that automatically. */
function DistributeModal({ payslipCount, onClose, onConfirm }: {
  payslipCount: number; onClose: () => void; onConfirm: (channels: ('PORTAL' | 'EMAIL')[]) => Promise<void>;
}) {
  const [portal, setPortal] = useState(true);
  const [email, setEmail] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const valid = portal || email;

  async function submit() {
    if (!valid) return;
    const channels: ('PORTAL' | 'EMAIL')[] = [...(portal ? ['PORTAL' as const] : []), ...(email ? ['EMAIL' as const] : [])];
    setSubmitting(true);
    try { await onConfirm(channels); onClose(); } finally { setSubmitting(false); }
  }

  return (
    <Modal title="Distribute Payslips" subtitle={`Applies to all ${payslipCount} payslip${payslipCount === 1 ? '' : 's'} in this run - not selectable per employee.`} onClose={onClose}>
      <FormRow>
        <label style={{ display: 'flex', alignItems: 'flex-start', gap: 10, fontSize: 13.5, color: 'var(--txt)', cursor: 'pointer', marginBottom: 12 }}>
          <input type="checkbox" checked={portal} onChange={e => setPortal(e.target.checked)} style={{ marginTop: 2 }} />
          <span>
            <div style={{ fontWeight: 600 }}>Publish to Employee Portal</div>
            <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Makes each payslip visible under the employee's own My Finance tab.</div>
          </span>
        </label>
        <label style={{ display: 'flex', alignItems: 'flex-start', gap: 10, fontSize: 13.5, color: 'var(--txt)', cursor: 'pointer', marginBottom: 12 }}>
          <input type="checkbox" checked={email} onChange={e => setEmail(e.target.checked)} style={{ marginTop: 2 }} />
          <span>
            <div style={{ fontWeight: 600 }}>Email to registered address</div>
            <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Sends the payslip PDF as an attachment to each employee's email on file.</div>
          </span>
        </label>
        <label style={{ display: 'flex', alignItems: 'flex-start', gap: 10, fontSize: 13.5, color: 'var(--txt-dim)', marginBottom: 0 }}>
          <input type="checkbox" checked={false} disabled style={{ marginTop: 2 }} />
          <span>
            <div style={{ fontWeight: 600 }}>Microsoft Teams</div>
            <div style={{ fontSize: 12 }}>Not connected - no Teams integration is configured for this workspace.</div>
          </span>
        </label>
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Distributing…' : 'Distribute'}</BtnPrimary>
      </div>
    </Modal>
  );
}

export function PayslipsTab({ runId, payGroupId, cycleStart, runStatus, canConfigure }: {
  runId: string; payGroupId: string; cycleStart: string; runStatus: PayrollRunStatus; canConfigure: boolean;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [payslips, setPayslips] = useState<PayslipResponse[]>([]);
  const [selected, setSelected] = useState<PayslipResponse | null>(null);
  const [search, setSearch] = useState('');
  const [downloading, setDownloading] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [exportingBank, setExportingBank] = useState(false);
  const [showDistribute, setShowDistribute] = useState(false);
  const { nameFor } = useEmployeeDirectory();

  // The rendered PDF (view/download-all/distribute) only exists for a payslip PayslipService
  // itself will serve - APPROVED/DISBURSED only, same gate CostReportTab already applies to its
  // own PDF-adjacent data. A DRAFT/SUBMITTED run still has real figures to review, just not yet a
  // "payslip" to hand anyone.
  const viewable = runStatus === 'APPROVED' || runStatus === 'DISBURSED';

  function reload() {
    payrollApi.listPayslips(runId, token).then(setPayslips).catch(() => showToast('error', 'Failed to load payslips'));
  }

  useEffect(reload, [runId, token]);

  async function viewPdf(employeeId: string) {
    try {
      await payrollApi.viewPayslipPdf(runId, employeeId, token);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to open payslip');
    }
  }

  async function downloadAll() {
    setDownloading(true);
    try {
      await payrollApi.downloadAllPayslips(runId, token);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to download payslips');
    } finally {
      setDownloading(false);
    }
  }

  async function exportToExcel() {
    setExporting(true);
    try {
      await payrollApi.downloadPayRegister(runId, token);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to export pay register');
    } finally {
      setExporting(false);
    }
  }

  // Bank-details ticket - bank details + net pay only, excludes Payout Hold and anyone missing
  // bank details (same "needs attention" condition the Overview's Pre-Approval Summary flags).
  async function exportBankTransfer() {
    setExportingBank(true);
    try {
      await payrollApi.downloadBankTransfer(runId, token);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to export bank transfer file');
    } finally {
      setExportingBank(false);
    }
  }

  async function distribute(channels: ('PORTAL' | 'EMAIL')[]) {
    try {
      const result = await payrollApi.distributePayslips(runId, { channels }, token);
      const parts: string[] = [];
      if (channels.includes('PORTAL')) parts.push(`Published to portal for ${result.releasedCount}`);
      if (channels.includes('EMAIL')) parts.push(`Emailed ${result.emailedCount}${result.emailFailedCount > 0 ? ` (${result.emailFailedCount} failed)` : ''}`);
      showToast('success', parts.join(' · '));
      reload();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to distribute payslips');
    }
  }

  function rowClick(p: PayslipResponse) {
    if (viewable) void viewPdf(p.employeeId);
    else setSelected(p);
  }

  const filtered = payslips.filter(p => nameFor(p.employeeId).toLowerCase().includes(search.trim().toLowerCase()));
  const { pageRows, pagerProps } = usePaged(filtered, [runId, search]);
  const totalNet = payslips.reduce((sum, p) => sum + p.netPay, 0);

  return (
    <div>
      <SectionHead
        title="Payslips"
        action={(
          <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
            {payslips.length > 0 && (
              <span style={{ fontSize: 13, color: 'var(--txt-mut)', whiteSpace: 'nowrap' }}>
                {payslips.length} payslip{payslips.length === 1 ? '' : 's'} · {fmtCurrency(totalNet)} total net
              </span>
            )}
            {canConfigure && (
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                <BtnGhost onClick={exportToExcel} disabled={exporting || payslips.length === 0}>
                  <FileSpreadsheet size={13} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} /><span style={{ whiteSpace: 'nowrap' }}>{exporting ? 'Exporting…' : 'Export to Excel'}</span>
                </BtnGhost>
                <BtnGhost onClick={exportBankTransfer} disabled={!viewable || exportingBank || payslips.length === 0}>
                  <Landmark size={13} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} /><span style={{ whiteSpace: 'nowrap' }}>{exportingBank ? 'Exporting…' : 'Download Bank File'}</span>
                </BtnGhost>
                <BtnGhost onClick={downloadAll} disabled={!viewable || downloading || payslips.length === 0}>
                  <Download size={13} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} /><span style={{ whiteSpace: 'nowrap' }}>{downloading ? 'Downloading…' : 'Download All'}</span>
                </BtnGhost>
                <BtnPrimary onClick={() => setShowDistribute(true)} disabled={!viewable || payslips.length === 0}>
                  <Send size={13} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} /><span style={{ whiteSpace: 'nowrap' }}>Distribute Payslips</span>
                </BtnPrimary>
              </div>
            )}
          </div>
        )}
      />
      {!viewable && (
        <div style={{ fontSize: 12.5, color: 'var(--txt-dim)', marginBottom: 10 }}>
          Payslip distribution unlocks once this run is Approved or Disbursed - click a row to review its figures for now.
        </div>
      )}
      <FilterBar search={search} onSearchChange={setSearch} />
      <div style={panelStyle}>
        <table className="nf-pr-center nf-pr-table nf-pr-table-clickable" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{['Employee', 'Net', 'Published', 'Withheld', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {pageRows.map(p => (
              <tr key={p.id} onClick={() => rowClick(p)}>
                <td style={tdStyle}><PersonCellById userId={p.employeeId} name={nameFor(p.employeeId)} /></td>
                <td style={tdStyle}>{fmtCurrency(p.netPay)}</td>
                <td style={tdStyle}>{p.released ? 'Yes' : 'No'}</td>
                <td style={tdStyle}>{p.paymentWithheld ? 'Yes' : 'No'}</td>
                <td style={tdStyle}>
                  <ActionDock items={[
                    { label: 'View Payslip', icon: FileText, disabled: !viewable, onClick: () => viewPdf(p.employeeId) },
                    { label: 'Figures & Overrides', icon: ListChecks, onClick: () => setSelected(p) },
                  ]} />
                </td>
              </tr>
            ))}
            {pageRows.length === 0 && (
              <EmptyRow cols={5} msg={payslips.length === 0 ? 'No payslips yet - generate the draft first.' : 'No matches for your search.'} />
            )}
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
      {showDistribute && (
        <DistributeModal payslipCount={payslips.length} onClose={() => setShowDistribute(false)} onConfirm={distribute} />
      )}
    </div>
  );
}

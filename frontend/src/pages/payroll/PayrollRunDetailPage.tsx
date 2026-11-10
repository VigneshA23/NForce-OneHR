import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  ArrowLeft, Users, IndianRupee, MinusCircle, Wallet, LayoutGrid, Receipt, CalendarCheck,
  UserX, PlusCircle, PauseCircle, Undo2,
} from 'lucide-react';
import PageTitleBlock from '../../components/PageTitleBlock';
import { TabBar, type TabDef } from '../../components/TabBar';
import { useAuthStore } from '../../store/authStore';
import { hasPermission } from '../../lib/permissions';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayrollRunResponse, type PayrollRunStatus } from '../../api/payroll';
import {
  fmtCurrency, fmtDate, fmtDateTime, StatusBadge, StatTile, Modal, FormRow, labelStyle, inputStyle,
  BtnPrimary, BtnGhost,
} from './payrollShared';
import { PayslipsTab } from './PayslipsTab';
import { LeaveAppliedReviewTab } from './LeaveAppliedReviewTab';
import { NoAttendanceReviewTab } from './NoAttendanceReviewTab';
import { AdHocPaymentsTab } from './AdHocPaymentsTab';
import { AdHocDeductionsTab } from './AdHocDeductionsTab';
import { SalaryHoldsTab } from './SalaryHoldsTab';
import './payroll.css';

export type RunAction = 'regenerate' | 'submit' | 'approve' | 'disburse' | 'rollback';

/** Which run-level actions are available for a given status — the client-side mirror of
 * PayrollRunService's own transition guards (submit requires DRAFT, approve requires SUBMITTED,
 * disburse requires APPROVED; rollback is allowed from SUBMITTED/APPROVED/DISBURSED, not DRAFT,
 * per that service's "rejected back to DRAFT while SUBMITTED, not just APPROVED/DISBURSED" note).
 * This is a hint only — the backend's own guard is the real enforcement either way. */
export function availableActions(status: PayrollRunStatus): RunAction[] {
  switch (status) {
    case 'DRAFT': return ['regenerate', 'submit'];
    case 'SUBMITTED': return ['approve', 'rollback'];
    case 'APPROVED': return ['disburse', 'rollback'];
    case 'DISBURSED': return ['rollback'];
    default: return [];
  }
}

type TabKey = 'overview' | 'payslips' | 'leave-applied' | 'no-attendance' | 'ad-hoc-payments' | 'ad-hoc-deductions' | 'holds';

const TABS: TabDef<TabKey>[] = [
  { key: 'overview', label: 'Overview', icon: <LayoutGrid size={15} /> },
  { key: 'payslips', label: 'Payslips', icon: <Receipt size={15} /> },
  { key: 'leave-applied', label: 'Leave Applied', icon: <CalendarCheck size={15} /> },
  { key: 'no-attendance', label: 'No Attendance', icon: <UserX size={15} /> },
  { key: 'ad-hoc-payments', label: 'Ad-hoc Payments', icon: <PlusCircle size={15} /> },
  { key: 'ad-hoc-deductions', label: 'Ad-hoc Deductions', icon: <MinusCircle size={15} /> },
  { key: 'holds', label: 'Holds', icon: <PauseCircle size={15} /> },
];

function RollbackModal({ onClose, onConfirm }: { onClose: () => void; onConfirm: (reason: string) => Promise<void> }) {
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function submit() {
    if (!reason.trim()) return;
    setSubmitting(true);
    try {
      await onConfirm(reason.trim());
      onClose();
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal title="Rollback Run" subtitle="Reverts this run back to DRAFT for reprocessing. Every payslip and review decision will be regenerated." onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Reason *</label>
        <textarea value={reason} onChange={e => setReason(e.target.value)} style={{ ...inputStyle, minHeight: 80, resize: 'vertical' }} />
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!reason.trim() || submitting}>{submitting ? 'Rolling back…' : 'Rollback'}</BtnPrimary>
      </div>
    </Modal>
  );
}

export default function PayrollRunDetailPage() {
  const { runId } = useParams<{ runId: string }>();
  const navigate = useNavigate();
  const token = useAuthStore(s => s.token)!;
  const permissions = useAuthStore(s => s.permissions);
  const canConfigure = hasPermission(permissions, 'payroll', 'CONFIGURE', 'ALL');
  const { showToast } = useToast();

  const [run, setRun] = useState<PayrollRunResponse | null>(null);
  const [tab, setTab] = useState<TabKey>('overview');
  const [busy, setBusy] = useState(false);
  const [showRollback, setShowRollback] = useState(false);

  const reload = useCallback(() => {
    if (!runId) return;
    payrollApi.getRun(runId, token).then(setRun).catch(() => showToast('error', 'Failed to load this payroll run'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, token]);

  useEffect(() => { reload(); }, [reload]);

  async function act(action: () => Promise<PayrollRunResponse>, successMessage: string) {
    setBusy(true);
    try {
      const updated = await action();
      setRun(updated);
      showToast('success', successMessage);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Action failed');
    } finally {
      setBusy(false);
    }
  }

  if (!run || !runId) {
    return <div style={{ padding: '24px 28px', color: 'var(--txt-mut)' }}>Loading…</div>;
  }

  const actions = canConfigure ? availableActions(run.status) : [];

  return (
    <div style={{ padding: '24px 28px' }}>
      <button onClick={() => navigate('/payroll-runs')} style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'none', border: 'none', color: 'var(--txt-mut)', fontSize: 13, cursor: 'pointer', padding: 0, marginBottom: 14 }}>
        <ArrowLeft size={15} /> Back to Payroll Runs
      </button>

      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', flexWrap: 'wrap', gap: 16, marginBottom: 20 }}>
        <PageTitleBlock icon={Wallet}>
          <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, letterSpacing: '-.01em', color: 'var(--txt)' }}>
            {fmtDate(run.cycleStart)} – {fmtDate(run.cycleEnd)}
          </h1>
          <div style={{ marginTop: 4 }}><StatusBadge status={run.status} /></div>
        </PageTitleBlock>

        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          {actions.includes('regenerate') && (
            <BtnGhost disabled={busy} onClick={() => act(() => payrollApi.generateDraft({ payGroupId: run.payGroupId, referenceDate: run.cycleStart }, token), 'Draft regenerated')}>
              Regenerate
            </BtnGhost>
          )}
          {actions.includes('submit') && (
            <BtnPrimary disabled={busy} onClick={() => act(() => payrollApi.submit(runId, token), 'Submitted for approval')}>Submit</BtnPrimary>
          )}
          {actions.includes('approve') && (
            <BtnPrimary disabled={busy} onClick={() => act(() => payrollApi.approve(runId, token), 'Approved')}>Approve</BtnPrimary>
          )}
          {actions.includes('disburse') && (
            <BtnPrimary disabled={busy} onClick={() => act(() => payrollApi.disburse(runId, token), 'Disbursed')}>Disburse</BtnPrimary>
          )}
          {actions.includes('rollback') && (
            <BtnGhost disabled={busy} onClick={() => setShowRollback(true)}><Undo2 size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Rollback</BtnGhost>
          )}
        </div>
      </div>

      <div className="nf-pr-tiles" style={{ marginBottom: 22 }}>
        <StatTile tone="var(--info)" icon={<Users size={20} />} label="Employees" value={String(run.employeeCount)} />
        <StatTile tone="var(--brand)" icon={<IndianRupee size={20} />} label="Gross Pay" value={fmtCurrency(run.totalGross)} />
        <StatTile tone="var(--warn)" icon={<MinusCircle size={20} />} label="Deductions" value={fmtCurrency(run.totalDeductions)} />
        <StatTile tone="var(--ok)" icon={<IndianRupee size={20} />} label="Net Pay" value={fmtCurrency(run.totalNet)} />
      </div>

      <TabBar tabs={TABS} active={tab} onChange={setTab} ariaLabel="Payroll run sections" className="nf-pilltabs" style={{ marginBottom: 18 }} />

      {tab === 'overview' && (
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, padding: '18px 22px' }}>
          <dl style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 16, margin: 0 }}>
            <div><dt style={labelStyle}>Submitted</dt><dd style={{ margin: 0, color: 'var(--txt)' }}>{fmtDateTime(run.submittedAt)}</dd></div>
            <div><dt style={labelStyle}>Approved</dt><dd style={{ margin: 0, color: 'var(--txt)' }}>{fmtDateTime(run.approvedAt)}</dd></div>
            <div><dt style={labelStyle}>Disbursed</dt><dd style={{ margin: 0, color: 'var(--txt)' }}>{fmtDateTime(run.disbursedAt)}</dd></div>
            <div><dt style={labelStyle}>Pending Approval Stage</dt><dd style={{ margin: 0, color: 'var(--txt)' }}>{run.pendingApprovalStage ?? '–'}</dd></div>
            {run.lastRolledBackAt && (
              <>
                <div><dt style={labelStyle}>Last Rolled Back</dt><dd style={{ margin: 0, color: 'var(--txt)' }}>{fmtDateTime(run.lastRolledBackAt)}</dd></div>
                <div><dt style={labelStyle}>Rollback Reason</dt><dd style={{ margin: 0, color: 'var(--txt)' }}>{run.lastRollbackReason}</dd></div>
              </>
            )}
          </dl>
        </div>
      )}
      {tab === 'payslips' && <PayslipsTab runId={runId} payGroupId={run.payGroupId} cycleStart={run.cycleStart} canConfigure={canConfigure} />}
      {tab === 'leave-applied' && <LeaveAppliedReviewTab runId={runId} canConfigure={canConfigure} />}
      {tab === 'no-attendance' && <NoAttendanceReviewTab runId={runId} canConfigure={canConfigure} />}
      {tab === 'ad-hoc-payments' && <AdHocPaymentsTab runId={runId} canConfigure={canConfigure} />}
      {tab === 'ad-hoc-deductions' && <AdHocDeductionsTab runId={runId} canConfigure={canConfigure} />}
      {tab === 'holds' && <SalaryHoldsTab runId={runId} canConfigure={canConfigure} />}

      {showRollback && (
        <RollbackModal
          onClose={() => setShowRollback(false)}
          onConfirm={async (reason) => { await act(() => payrollApi.rollbackRun(runId, reason, token), 'Run rolled back to DRAFT'); }}
        />
      )}
    </div>
  );
}

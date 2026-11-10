import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  ArrowLeft, Users, IndianRupee, MinusCircle, Wallet, LayoutGrid, Receipt, CalendarCheck,
  PlusCircle, PauseCircle, Undo2, UserRoundPlus, Gift, Receipt as ReceiptIcon, History,
  ShieldCheck,
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

// ── Step groups, matching Keka's own "Run Payroll" step-card layout (see the console's Javadoc-
// equivalent note below) — NOT_AVAILABLE steps/sub-steps are disabled rather than omitted, so the
// shape of the real workflow is visible even before the backend behind them exists. ──

type StepKey = 'overview' | 'payslips' | 'leave-attendance' | 'joiners-exits' | 'bonus-revisions-ot' | 'reimbursement-adhoc' | 'holds-arrears' | 'statutory-override';

const STEPS: TabDef<StepKey>[] = [
  { key: 'overview', label: 'Overview', icon: <LayoutGrid size={15} /> },
  { key: 'payslips', label: 'Payslips', icon: <Receipt size={15} /> },
  { key: 'leave-attendance', label: 'Leave, Attendance & Payable Units', icon: <CalendarCheck size={15} /> },
  { key: 'joiners-exits', label: 'New Joinees & Exits', icon: <UserRoundPlus size={15} />, disabled: true, title: 'Not available in this release' },
  { key: 'bonus-revisions-ot', label: 'Bonus, Salary Revisions & Overtime', icon: <Gift size={15} />, disabled: true, title: 'Not available in this release' },
  { key: 'reimbursement-adhoc', label: 'Reimbursement, Adhoc Payment, Deduction', icon: <ReceiptIcon size={15} /> },
  { key: 'holds-arrears', label: 'Salaries on Hold & Arrears', icon: <PauseCircle size={15} /> },
  { key: 'statutory-override', label: 'Override (PT, ESI, TDS, LWF)', icon: <ShieldCheck size={15} />, disabled: true, title: 'Not available in this release' },
];

type LeaveAttendanceSubTab = 'leave-applied' | 'no-attendance' | 'lop-summary' | 'lop-reversal' | 'payable-units';
const LEAVE_ATTENDANCE_SUBTABS: TabDef<LeaveAttendanceSubTab>[] = [
  { key: 'leave-applied', label: 'Leave Applied' },
  { key: 'no-attendance', label: 'No Attendance' },
  { key: 'lop-summary', label: 'LOP Summary', disabled: true, title: 'Not available in this release — use the Payslips tab\'s Recompute/Override LOP actions' },
  { key: 'lop-reversal', label: 'LOP Reversal', disabled: true, title: 'Not available in this release' },
  { key: 'payable-units', label: 'Payable Units', disabled: true, title: 'Not available in this release' },
];

type ReimbursementSubTab = 'salary-component-claim' | 'expenses' | 'adhoc-payments' | 'adhoc-deductions';
const REIMBURSEMENT_SUBTABS: TabDef<ReimbursementSubTab>[] = [
  { key: 'salary-component-claim', label: 'Salary Component Claim', disabled: true, title: 'Not available in this release' },
  { key: 'expenses', label: 'Expenses', disabled: true, title: 'Not available in this release' },
  { key: 'adhoc-payments', label: 'Adhoc Payments', icon: <PlusCircle size={14} /> },
  { key: 'adhoc-deductions', label: 'Adhoc Deductions', icon: <MinusCircle size={14} /> },
];

type HoldsSubTab = 'processing-hold' | 'payout-hold' | 'arrears';
const HOLDS_SUBTABS: TabDef<HoldsSubTab>[] = [
  { key: 'processing-hold', label: 'Salary Processing on Hold' },
  { key: 'payout-hold', label: 'Salary Payout on Hold' },
  { key: 'arrears', label: 'Arrears', icon: <History size={14} />, disabled: true, title: 'Not available in this release — auto-computed from backdated revisions, hold releases and LOP reversal' },
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

/**
 * The run console, structured the same way Keka's own "Run Payroll" screen is: a set of named
 * step-cards (not a flat tab bar) — Leave/Attendance/Payable Units, New Joinees & Exits, Bonus/
 * Revisions/OT, Reimbursement/Adhoc/Deduction, Salaries on Hold & Arrears, Statutory Override —
 * each its own small wizard of sub-steps. Steps/sub-steps this codebase doesn't have a backend for
 * yet (New Joinees & Exits, Bonus/Revisions/OT, Statutory Override, LOP Summary/Reversal, Payable
 * Units, Salary Component Claim, Expenses, Arrears) are shown disabled rather than omitted, so the
 * shape of the real workflow is visible now and each one only has to be wired up, not invented,
 * once its backend exists.
 */
export default function PayrollRunDetailPage() {
  const { runId } = useParams<{ runId: string }>();
  const navigate = useNavigate();
  const token = useAuthStore(s => s.token)!;
  const permissions = useAuthStore(s => s.permissions);
  const canConfigure = hasPermission(permissions, 'payroll', 'CONFIGURE', 'ALL');
  const { showToast } = useToast();

  const [run, setRun] = useState<PayrollRunResponse | null>(null);
  const [step, setStep] = useState<StepKey>('overview');
  const [leaveAttendanceSubTab, setLeaveAttendanceSubTab] = useState<LeaveAttendanceSubTab>('leave-applied');
  const [reimbursementSubTab, setReimbursementSubTab] = useState<ReimbursementSubTab>('adhoc-payments');
  const [holdsSubTab, setHoldsSubTab] = useState<HoldsSubTab>('processing-hold');
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

      <TabBar tabs={STEPS} active={step} onChange={setStep} ariaLabel="Payroll run steps" className="nf-pilltabs" style={{ marginBottom: 18 }} />

      {step === 'overview' && (
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

      {step === 'payslips' && <PayslipsTab runId={runId} payGroupId={run.payGroupId} cycleStart={run.cycleStart} canConfigure={canConfigure} />}

      {step === 'leave-attendance' && (
        <div>
          <TabBar tabs={LEAVE_ATTENDANCE_SUBTABS} active={leaveAttendanceSubTab} onChange={setLeaveAttendanceSubTab} size="sm" ariaLabel="Leave, attendance and payable units steps" style={{ marginBottom: 16 }} />
          {leaveAttendanceSubTab === 'leave-applied' && <LeaveAppliedReviewTab runId={runId} canConfigure={canConfigure} />}
          {leaveAttendanceSubTab === 'no-attendance' && <NoAttendanceReviewTab runId={runId} canConfigure={canConfigure} />}
        </div>
      )}

      {step === 'reimbursement-adhoc' && (
        <div>
          <TabBar tabs={REIMBURSEMENT_SUBTABS} active={reimbursementSubTab} onChange={setReimbursementSubTab} size="sm" ariaLabel="Reimbursement, adhoc payment and deduction steps" style={{ marginBottom: 16 }} />
          {reimbursementSubTab === 'adhoc-payments' && <AdHocPaymentsTab runId={runId} canConfigure={canConfigure} />}
          {reimbursementSubTab === 'adhoc-deductions' && <AdHocDeductionsTab runId={runId} canConfigure={canConfigure} />}
        </div>
      )}

      {step === 'holds-arrears' && (
        <div>
          <TabBar tabs={HOLDS_SUBTABS} active={holdsSubTab} onChange={setHoldsSubTab} size="sm" ariaLabel="Salaries on hold and arrears steps" style={{ marginBottom: 16 }} />
          {holdsSubTab === 'processing-hold' && <SalaryHoldsTab runId={runId} holdType="PROCESSING" canConfigure={canConfigure} />}
          {holdsSubTab === 'payout-hold' && <SalaryHoldsTab runId={runId} holdType="PAYOUT" canConfigure={canConfigure} />}
        </div>
      )}

      {showRollback && (
        <RollbackModal
          onClose={() => setShowRollback(false)}
          onConfirm={async (reason) => { await act(() => payrollApi.rollbackRun(runId, reason, token), 'Run rolled back to DRAFT'); }}
        />
      )}
    </div>
  );
}

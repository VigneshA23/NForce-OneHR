import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  ArrowLeft, Users, IndianRupee, MinusCircle, Wallet, LayoutGrid, Receipt, CalendarCheck,
  PlusCircle, PauseCircle, Undo2, UserRoundPlus, Gift, Receipt as ReceiptIcon, History,
  ShieldCheck, PieChart, AlertTriangle, CheckCircle2,
} from 'lucide-react';
import PageTitleBlock from '../../components/PageTitleBlock';
import { TabBar, type TabDef } from '../../components/TabBar';
import { useAuthStore } from '../../store/authStore';
import { hasPermission } from '../../lib/permissions';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayrollRunResponse, type PayrollRunRollbackResponse, type PayrollRunStatus, type PayGroupResponse, type PreApprovalSummaryResponse } from '../../api/payroll';
import {
  fmtCurrency, fmtDate, fmtDateTime, StatusBadge, StatTile, StepCard, SectionHead, Modal, FormRow,
  labelStyle, inputStyle, BtnPrimary, BtnGhost, WithFaqPanel, useEmployeeDirectory, panelStyle, thStyle, tdStyle, type FaqItem,
} from './payrollShared';
import { PayslipsTab } from './PayslipsTab';
import { LeaveAppliedReviewTab } from './LeaveAppliedReviewTab';
import { NoAttendanceReviewTab } from './NoAttendanceReviewTab';
import { AdHocPaymentsTab } from './AdHocPaymentsTab';
import { AdHocDeductionsTab } from './AdHocDeductionsTab';
import { SalaryHoldsTab } from './SalaryHoldsTab';
import { CostReportTab } from './CostReportTab';
import { PayableUnitsTab } from './PayableUnitsTab';
import { JoinersExitsTab } from './JoinersExitsTab';
import { SalaryRevisionTab } from './SalaryRevisionTab';
import { SalaryComponentClaimTab } from './SalaryComponentClaimTab';
import { StatutoryOverrideTab } from './StatutoryOverrideTab';
import { ArrearsTab } from './ArrearsTab';
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

type StepKey = 'overview' | 'payslips' | 'leave-attendance' | 'joiners-exits' | 'bonus-revisions-ot' | 'reimbursement-adhoc' | 'holds-arrears' | 'statutory-override' | 'cost-report';

const STEPS: TabDef<StepKey>[] = [
  { key: 'overview', label: 'Overview', icon: <LayoutGrid size={15} /> },
  { key: 'payslips', label: 'Payslips', icon: <Receipt size={15} /> },
  { key: 'leave-attendance', label: 'Leave & Attendance', icon: <CalendarCheck size={15} /> },
  { key: 'joiners-exits', label: 'Joinees & Exits', icon: <UserRoundPlus size={15} /> },
  { key: 'bonus-revisions-ot', label: 'Bonus & Revisions', icon: <Gift size={15} /> },
  { key: 'reimbursement-adhoc', label: 'Reimbursements', icon: <ReceiptIcon size={15} /> },
  { key: 'holds-arrears', label: 'Holds & Arrears', icon: <PauseCircle size={15} /> },
  { key: 'statutory-override', label: 'Statutory Override', icon: <ShieldCheck size={15} /> },
  { key: 'cost-report', label: 'Cost Report', icon: <PieChart size={15} /> },
];

// Overview's step-card grid, matching Keka's own "Run Payroll" dashboard (§3.1 of the restyle
// spec) — every other step as a clickable card instead of leaving Overview a dead end. Excludes
// 'overview' itself; tone values cycle through the app's existing semantic tokens (StatTile's own
// convention) rather than introducing new colors.
const OVERVIEW_CARDS: { key: StepKey; label: string; caption: string; icon: ReactNode; tone: string }[] = [
  { key: 'payslips', label: 'Payslips', caption: "Review and recompute every employee's payslip for this cycle.", icon: <Receipt size={20} />, tone: 'var(--info)' },
  { key: 'leave-attendance', label: 'Leave, Attendance & Payable Units', caption: 'Decide leave-applied and no-attendance cases; review payable units.', icon: <CalendarCheck size={20} />, tone: 'var(--brand)' },
  { key: 'joiners-exits', label: 'New Joinees & Exits', caption: 'Employees who joined or exited within this cycle.', icon: <UserRoundPlus size={20} />, tone: 'var(--ok)' },
  { key: 'bonus-revisions-ot', label: 'Bonus, Salary Revisions & Overtime', caption: 'Ad-hoc bonus/overtime payments and mid-cycle salary revisions.', icon: <Gift size={20} />, tone: 'var(--warn)' },
  { key: 'reimbursement-adhoc', label: 'Reimbursement, Adhoc Payment, Deduction', caption: 'Cleared expense claims, ad-hoc payments and deductions.', icon: <ReceiptIcon size={20} />, tone: 'var(--info)' },
  { key: 'holds-arrears', label: 'Salaries on Hold & Arrears', caption: 'Employees excluded from processing or payout, and pending arrears.', icon: <PauseCircle size={20} />, tone: 'var(--risk)' },
  { key: 'statutory-override', label: 'Override (PF, ESI, PT)', caption: 'Per-employee overrides to the computed statutory deductions.', icon: <ShieldCheck size={20} />, tone: 'var(--brand)' },
  { key: 'cost-report', label: 'Cost Report', caption: "This run's total cost broken down by department and project.", icon: <PieChart size={20} />, tone: 'var(--ok)' },
];

type LeaveAttendanceSubTab = 'leave-applied' | 'no-attendance' | 'lop-summary' | 'lop-reversal' | 'payable-units';
const LEAVE_ATTENDANCE_SUBTABS: TabDef<LeaveAttendanceSubTab>[] = [
  { key: 'leave-applied', label: 'Leave Applied' },
  { key: 'no-attendance', label: 'No Attendance' },
  { key: 'lop-summary', label: 'LOP Summary', disabled: true, title: 'Not available in this release — use the Payslips tab\'s Recompute/Override LOP actions' },
  { key: 'lop-reversal', label: 'LOP Reversal', disabled: true, title: 'Use the Payslips tab\'s Override LOP action on the historical cycle\'s own run — it now raises Arrears automatically when that cycle is already closed' },
  { key: 'payable-units', label: 'Payable Units' },
];

// "Salary Component Claim" and "Expenses" used to be two separate sub-tabs that rendered the
// exact same component with no difference — reads as broken ("I clicked a different tab and
// nothing changed") rather than two genuinely distinct views. Collapsed into one.
type ReimbursementSubTab = 'salary-component-claim' | 'adhoc-payments' | 'adhoc-deductions';
const REIMBURSEMENT_SUBTABS: TabDef<ReimbursementSubTab>[] = [
  { key: 'salary-component-claim', label: 'Salary Component Claim / Expenses' },
  { key: 'adhoc-payments', label: 'Adhoc Payments', icon: <PlusCircle size={14} /> },
  { key: 'adhoc-deductions', label: 'Adhoc Deductions', icon: <MinusCircle size={14} /> },
];

type BonusRevisionsOtSubTab = 'bonus-ot' | 'salary-revision';
const BONUS_REVISIONS_OT_SUBTABS: TabDef<BonusRevisionsOtSubTab>[] = [
  { key: 'bonus-ot', label: 'Bonus & Overtime' },
  { key: 'salary-revision', label: 'Salary Revision' },
];

type HoldsSubTab = 'processing-hold' | 'payout-hold' | 'arrears';
const HOLDS_SUBTABS: TabDef<HoldsSubTab>[] = [
  { key: 'processing-hold', label: 'Salary Processing on Hold' },
  { key: 'payout-hold', label: 'Salary Payout on Hold' },
  { key: 'arrears', label: 'Arrears', icon: <History size={14} /> },
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
const FAQ_ITEMS: FaqItem[] = [
  { q: 'What does Regenerate do?', a: 'Rebuilds every payslip in this run from scratch using current data — updated LOP, ad-hoc payments, statutory rules, holds and arrears. Only available in Draft; it replaces the existing draft payslips, it doesn’t add to them.' },
  { q: 'What do Submit, Approve and Disburse actually change?', a: 'They move the run through its lifecycle: Submit sends a Draft for approval, Approve locks in the numbers as final, Disburse marks it as paid out. Which buttons you see depends on the run’s current status and your permissions.' },
  { q: 'Can I undo an action after Approve or Disburse?', a: 'Use Rollback — it reverts the run back to Draft with a required reason, so you can fix something and regenerate. It’s available only at the stages where this app allows a reversal.' },
  { q: 'Where do Arrears come from?', a: 'They’re never entered directly — a backdated salary revision, an LOP reversal, or releasing a salary hold automatically creates an arrears row, which the next regenerate picks up as a line item. The Arrears tab (under Holds & Arrears) is read-only, showing what’s pending or already applied.' },
  { q: 'What’s the difference between a Processing Hold and a Payout Hold?', a: 'A Processing Hold excludes someone from this run entirely — regenerate and they’re skipped. A Payout Hold still includes them in the run’s numbers but holds back the actual disbursement for that person.' },
  { q: 'Why can’t I edit a statutory deduction amount directly on a payslip?', a: 'Payslip line items are rebuilt on every regenerate, so a direct edit would be silently lost. Use Statutory Override instead — it persists independently of any one run and survives regeneration.' },
];

export default function PayrollRunDetailPage() {
  const { runId } = useParams<{ runId: string }>();
  const navigate = useNavigate();
  const token = useAuthStore(s => s.token)!;
  const permissions = useAuthStore(s => s.permissions);
  const canConfigure = hasPermission(permissions, 'payroll', 'CONFIGURE', 'ALL');
  const { showToast } = useToast();
  const { nameFor } = useEmployeeDirectory();

  const [run, setRun] = useState<PayrollRunResponse | null>(null);
  const [payGroups, setPayGroups] = useState<PayGroupResponse[]>([]);
  const [step, setStep] = useState<StepKey>('overview');
  const [leaveAttendanceSubTab, setLeaveAttendanceSubTab] = useState<LeaveAttendanceSubTab>('leave-applied');
  const [reimbursementSubTab, setReimbursementSubTab] = useState<ReimbursementSubTab>('adhoc-payments');
  const [holdsSubTab, setHoldsSubTab] = useState<HoldsSubTab>('processing-hold');
  const [bonusRevisionsOtSubTab, setBonusRevisionsOtSubTab] = useState<BonusRevisionsOtSubTab>('bonus-ot');
  const [busy, setBusy] = useState(false);
  const [showRollback, setShowRollback] = useState(false);
  const [rollbackHistory, setRollbackHistory] = useState<PayrollRunRollbackResponse[]>([]);
  const [preApproval, setPreApproval] = useState<PreApprovalSummaryResponse | null>(null);
  const [showNeedsAttention, setShowNeedsAttention] = useState(false);

  const reload = useCallback(() => {
    if (!runId) return;
    payrollApi.getRun(runId, token).then(setRun).catch(() => showToast('error', 'Failed to load this payroll run'));
    payrollApi.rollbackHistory(runId, token).then(setRollbackHistory).catch(() => {});
    payrollApi.preApprovalSummary(runId, token).then(setPreApproval).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, token]);

  useEffect(() => { reload(); }, [reload]);

  // Pay Group name only, for the header — there's no second pay group's worth of UI to build yet,
  // just a one-time reminder of which pay group this run belongs to (today's only real gap once a
  // company has more than one).
  useEffect(() => { payrollApi.listPayGroups(token).then(setPayGroups).catch(() => {}); }, [token]);

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
  const payGroupName = payGroups.find(pg => pg.id === run.payGroupId)?.name;

  return (
    <div style={{ padding: '24px 28px' }}>
      <button onClick={() => navigate('/payroll-runs')} style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'none', border: 'none', color: 'var(--txt-mut)', fontSize: 13, cursor: 'pointer', padding: 0, marginBottom: 14 }}>
        <ArrowLeft size={15} /> Back to Payroll Runs
      </button>

      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', flexWrap: 'wrap', gap: 16, marginBottom: 20 }}>
        <PageTitleBlock icon={Wallet}>
          <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, letterSpacing: '-.01em', color: 'var(--txt)' }}>
            {(() => { const [y, m] = run.cycleEnd.split('-').map(Number); return new Date(y, m - 1, 1).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' }); })()} Payroll
          </h1>
          <div style={{ marginTop: 4, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <span style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>{fmtDate(run.cycleStart)} – {fmtDate(run.cycleEnd)}</span>
            <StatusBadge status={run.status} />
            {payGroupName && <span style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>{payGroupName}</span>}
          </div>
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

      <WithFaqPanel items={FAQ_ITEMS} title="Payroll Run Help">
      <div>
      <div className="nf-pr-tiles" style={{ marginBottom: 18 }}>
        <StatTile tone="var(--info)" icon={<Users size={20} />} label="Employees" value={String(run.employeeCount)} onClick={() => setStep('payslips')} />
        <StatTile tone="var(--brand)" icon={<IndianRupee size={20} />} label="Gross Pay" value={fmtCurrency(run.totalGross)} onClick={() => setStep('payslips')} />
        <StatTile tone="var(--warn)" icon={<MinusCircle size={20} />} label="Deductions" value={fmtCurrency(run.totalDeductions)} onClick={() => setStep('cost-report')} />
        <StatTile tone="var(--ok)" icon={<IndianRupee size={20} />} label="Net Pay" value={fmtCurrency(run.totalNet)} onClick={() => setStep('payslips')} />
      </div>

      <TabBar tabs={STEPS} active={step} onChange={setStep} ariaLabel="Payroll run steps" style={{ marginBottom: 18 }} />

      {step === 'overview' && (
        <>
          <div className="nf-pr-steps">
            {OVERVIEW_CARDS.map(c => (
              <StepCard key={c.key} icon={c.icon} tone={c.tone} label={c.label} caption={c.caption} onClick={() => setStep(c.key)} />
            ))}
          </div>
          <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 13, padding: '15px 18px' }}>
            <SectionHead title="Run Status" />
            <dl style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 16, margin: 0 }}>
            <div><dt style={labelStyle}>Submitted</dt><dd style={{ margin: 0, color: 'var(--txt)' }}>{fmtDateTime(run.submittedAt)}</dd></div>
            <div><dt style={labelStyle}>Approved</dt><dd style={{ margin: 0, color: 'var(--txt)' }}>{fmtDateTime(run.approvedAt)}</dd></div>
            <div><dt style={labelStyle}>Disbursed</dt><dd style={{ margin: 0, color: 'var(--txt)' }}>{fmtDateTime(run.disbursedAt)}</dd></div>
            <div><dt style={labelStyle}>Pending Approval Stage</dt><dd style={{ margin: 0, color: 'var(--txt)' }}>{run.pendingApprovalStage ?? '–'}</dd></div>
          </dl>
          </div>

          {preApproval && (
            <div style={{ marginTop: 18 }}>
              <SectionHead title="Pre-Approval Summary" />

              <div className="nf-pr-tiles" style={{ marginBottom: 18 }}>
                {([
                  ['Gross Pay', preApproval.current.grossPay, preApproval.previous?.grossPay, 'var(--brand)'],
                  ['Deductions', preApproval.current.totalDeductions, preApproval.previous?.totalDeductions, 'var(--warn)'],
                  ['Net Pay', preApproval.current.netPay, preApproval.previous?.netPay, 'var(--ok)'],
                  ['Employer Contributions', preApproval.current.employerContributions, preApproval.previous?.employerContributions, 'var(--info)'],
                ] as const).map(([label, value, prevValue, tone]) => {
                  const delta = prevValue != null ? value - prevValue : null;
                  const hint = delta == null ? 'No previous cycle to compare'
                    : delta === 0 ? 'No change vs last cycle'
                    : `${delta > 0 ? '▲' : '▼'} ${fmtCurrency(Math.abs(delta))} vs last cycle`;
                  return <StatTile key={label} tone={tone} icon={<IndianRupee size={20} />} label={label} value={fmtCurrency(value)} hint={hint} />;
                })}
              </div>

              <div className="nf-pr-tiles" style={{ marginBottom: 20 }}>
                <StatTile tone="var(--info)" icon={<CheckCircle2 size={20} />} label="Processed" value={String(preApproval.processedCount)} />
                <StatTile tone="var(--warn)" icon={<PauseCircle size={20} />} label="On Hold" value={String(preApproval.onHoldCount)} />
                <StatTile
                  tone="var(--risk)" icon={<AlertTriangle size={20} />} label="Needs Attention"
                  value={String(preApproval.needsAttentionCount)}
                  hint={preApproval.needsAttentionCount > 0 ? (showNeedsAttention ? 'Click to hide details' : 'Click to see who, and why') : undefined}
                  onClick={preApproval.needsAttentionCount > 0 ? () => setShowNeedsAttention(v => !v) : undefined}
                />
              </div>

              {showNeedsAttention && preApproval.needsAttentionDetails.length > 0 && (
                <div style={{ marginBottom: 20 }}>
                  <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', marginBottom: 8 }}>
                    Employees needing attention before approval
                  </div>
                  <div style={panelStyle}>
                    <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
                      <thead><tr>{['Employee', 'Reason(s)'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
                      <tbody>
                        {preApproval.needsAttentionDetails.map(item => (
                          <tr key={item.employeeId}>
                            <td style={tdStyle}>{item.employeeName}</td>
                            <td style={tdStyle}>{item.reasons.join(', ')}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}

              {preApproval.variances.length > 0 && (
                <div>
                  <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', marginBottom: 8 }}>
                    Employees whose net pay changed vs last cycle
                  </div>
                  <div style={panelStyle}>
                    <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
                      <thead><tr>{['Employee', 'Previous Net', 'Current Net', 'Change', 'Likely Reason(s)'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
                      <tbody>
                        {preApproval.variances.map(v => (
                          <tr key={v.employeeId}>
                            <td style={tdStyle}>{v.employeeName}</td>
                            <td style={tdStyle}>{fmtCurrency(v.previousNetPay)}</td>
                            <td style={tdStyle}>{fmtCurrency(v.currentNetPay)}</td>
                            <td style={{ ...tdStyle, color: v.delta >= 0 ? 'var(--ok)' : 'var(--risk)' }}>
                              {v.delta >= 0 ? '+' : ''}{fmtCurrency(v.delta)}
                            </td>
                            <td style={tdStyle}>{v.drivers.join(', ')}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}
            </div>
          )}

          {rollbackHistory.length > 0 && (
            <div style={{ marginTop: 18 }}>
              <SectionHead title="Rollback History" />
              <div style={panelStyle}>
                <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead><tr>{['When', 'By', 'Reason'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
                  <tbody>
                    {rollbackHistory.map(h => (
                      <tr key={h.id}>
                        <td style={tdStyle}>{fmtDateTime(h.rolledBackAt)}</td>
                        <td style={tdStyle}>{h.rolledBackBy ? nameFor(h.rolledBackBy) : '—'}</td>
                        <td style={tdStyle}>{h.reason}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </>
      )}

      {step === 'payslips' && <PayslipsTab runId={runId} payGroupId={run.payGroupId} cycleStart={run.cycleStart} runStatus={run.status} canConfigure={canConfigure} />}

      {step === 'joiners-exits' && <JoinersExitsTab runId={runId} />}

      {step === 'leave-attendance' && (
        <div>
          <TabBar tabs={LEAVE_ATTENDANCE_SUBTABS} active={leaveAttendanceSubTab} onChange={setLeaveAttendanceSubTab} size="sm" className="nf-boxedtabs" ariaLabel="Leave, attendance and payable units steps" style={{ marginBottom: 16 }} />
          {leaveAttendanceSubTab === 'leave-applied' && <LeaveAppliedReviewTab runId={runId} canConfigure={canConfigure} />}
          {leaveAttendanceSubTab === 'no-attendance' && <NoAttendanceReviewTab runId={runId} canConfigure={canConfigure} />}
          {leaveAttendanceSubTab === 'payable-units' && <PayableUnitsTab runId={runId} />}
        </div>
      )}

      {step === 'bonus-revisions-ot' && (
        <div>
          <TabBar tabs={BONUS_REVISIONS_OT_SUBTABS} active={bonusRevisionsOtSubTab} onChange={setBonusRevisionsOtSubTab} size="sm" className="nf-boxedtabs" ariaLabel="Bonus, salary revisions and overtime steps" style={{ marginBottom: 16 }} />
          {bonusRevisionsOtSubTab === 'bonus-ot' && <AdHocPaymentsTab runId={runId} canConfigure={canConfigure} />}
          {bonusRevisionsOtSubTab === 'salary-revision' && <SalaryRevisionTab runId={runId} payGroupId={run.payGroupId} canConfigure={canConfigure} />}
        </div>
      )}

      {step === 'reimbursement-adhoc' && (
        <div>
          <TabBar tabs={REIMBURSEMENT_SUBTABS} active={reimbursementSubTab} onChange={setReimbursementSubTab} size="sm" className="nf-boxedtabs" ariaLabel="Reimbursement, adhoc payment and deduction steps" style={{ marginBottom: 16 }} />
          {reimbursementSubTab === 'salary-component-claim' && <SalaryComponentClaimTab runId={runId} />}
          {reimbursementSubTab === 'adhoc-payments' && <AdHocPaymentsTab runId={runId} canConfigure={canConfigure} />}
          {reimbursementSubTab === 'adhoc-deductions' && <AdHocDeductionsTab runId={runId} canConfigure={canConfigure} />}
        </div>
      )}

      {step === 'holds-arrears' && (
        <div>
          <TabBar tabs={HOLDS_SUBTABS} active={holdsSubTab} onChange={setHoldsSubTab} size="sm" className="nf-boxedtabs" ariaLabel="Salaries on hold and arrears steps" style={{ marginBottom: 16 }} />
          {holdsSubTab === 'processing-hold' && <SalaryHoldsTab runId={runId} holdType="PROCESSING" canConfigure={canConfigure} />}
          {holdsSubTab === 'payout-hold' && <SalaryHoldsTab runId={runId} holdType="PAYOUT" canConfigure={canConfigure} />}
          {holdsSubTab === 'arrears' && <ArrearsTab runId={runId} />}
        </div>
      )}

      {step === 'statutory-override' && <StatutoryOverrideTab payGroupId={run.payGroupId} cycleStart={run.cycleStart} canConfigure={canConfigure} />}

      {step === 'cost-report' && <CostReportTab runId={runId} runStatus={run.status} />}
      </div>
      </WithFaqPanel>

      {showRollback && (
        <RollbackModal
          onClose={() => setShowRollback(false)}
          onConfirm={async (reason) => {
            await act(() => payrollApi.rollbackRun(runId, reason, token), 'Run rolled back to DRAFT');
            payrollApi.rollbackHistory(runId, token).then(setRollbackHistory).catch(() => {});
          }}
        />
      )}
    </div>
  );
}

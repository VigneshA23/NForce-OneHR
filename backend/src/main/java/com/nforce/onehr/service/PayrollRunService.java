package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.*;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * US-C08 (ONEHR-614), Phase 3 — the payroll run: generates a DRAFT batch of payslips for a pay
 * cycle, carries it through DRAFT → SUBMITTED → APPROVED → DISBURSED (US-C11 audits every
 * transition), reconciles before submission (US-C10), supports whole-run rollback (US-C27), and
 * routes {@link #approve} through the same {@code ApprovalRule} chain EXPENSE uses (US-C09).
 *
 * <h2>US-C09 — the approval chain</h2>
 * <p>{@link #submit} snapshots the routing decision from whichever {@code ApprovalRule} is active
 * for {@code PAYROLL_RUN} (see {@link ApprovalRuleEvaluationService#evaluatePayrollRun}) onto the
 * run's {@code approvalStages}/{@code pendingApprovalStage}, exactly like {@code ExpenseService}
 * snapshots its own routing at submission rather than re-evaluating later. {@link #approve}
 * advances {@code pendingApprovalStage} one stage at a time; status stays SUBMITTED across every
 * intermediate stage (no new status value — Decision #2's vocabulary stays settled) and only
 * flips to APPROVED once the last stage clears. A single actor who holds every remaining stage's
 * role (e.g. a Super Admin on a HR_ADMIN→SUPER_ADMIN chain) clears the whole chain in one call —
 * same multi-layer collapse as {@code ExpenseService#finalApprove}. No active rule for
 * {@code PAYROLL_RUN} means a single HR_ADMIN-only stage: the exact single-step behavior this app
 * shipped with before this story, so an un-configured deployment is unaffected.
 *
 * <h2>US-C25 — ad-hoc deduction installments</h2>
 * <p>{@link #buildPayslip} applies one {@link AdHocDeductionInstallment} per ACTIVE {@link
 * AdHocDeduction} the employee has, for whatever's left of {@link
 * AdHocDeduction#getRemainingBalance()} (capped at {@code installmentAmount}). Idempotent under
 * regeneration: an installment already recorded for this exact {@code (deduction, run)} pair is
 * reused verbatim rather than decrementing the balance a second time (see that entity's own
 * Javadoc) — only a run that has never seen this deduction before creates a new one and advances
 * the balance. {@link #rollbackWholeRun} reverses every installment tied to the rolled-back run
 * (restores the balance, deletes the row, reopens a COMPLETED plan if it was this installment that
 * completed it) so a discarded run never permanently shortchanges or overcharges the recovery.
 *
 * <h2>US-C26 — salary hold (both "Hold processing" and "Hold payout")</h2>
 * <p>{@link #generateDraft} excludes any employee with an ACTIVE {@link SalaryHoldType#PROCESSING}
 * {@link SalaryHold} on this exact run entirely from the next build — no payslip, no line items,
 * not counted in {@code employeeCount} — matching Keka's own "Hold processing" (confirmed against
 * its help docs 2026-11-05): zero statutory deductions, as if they had no effective compensation
 * this cycle. Keka's distinct "Hold payout" ({@link SalaryHoldType#PAYOUT}) is processed normally
 * — statutory deductions apply, nothing about {@link #buildPayslip} changes — and is only
 * intercepted at {@link #disburse} time, which flags that one employee's {@link
 * Payslip#isPaymentWithheld()} instead of paying them (still whole-run otherwise). See {@link
 * SalaryHold}'s own Javadoc for the full split and {@code SalaryHoldService} for both types'
 * create/release rules.
 *
 * <h2>US-C22/US-C23 — "Leave Applied" and "No Attendance" review steps</h2>
 * <p>{@link #buildPayslip} calls {@link PayrollRunReviewService#generateItemsForEmployee} right
 * after each employee's {@link PayrollLopCalculationService#recomputeWithDetails}, and {@link
 * #submit} won't let a run leave DRAFT while either step still has a PENDING item ({@link
 * PayrollRunReviewService#isStepComplete}). See {@code PayrollRunReviewService} and {@link
 * PayrollRunReviewItem} for the full design — what's genuinely new versus reused, and the
 * deliberate asymmetry between a rejected PENALTY item (durable) and a rejected LEAVE_UNPAID item
 * (review-item-only, reset on the next regenerate).
 *
 * <h2>Scope of this increment</h2>
 * <ul>
 *   <li><b>Which employees are included:</b> every {@link EmployeeCompensation} row effective on
 *       the cycle's last day, regardless of {@code payGroupId} — there is no employee-to-pay-group
 *       assignment mechanism yet, a gap already flagged when US-C07 was built. Fine while there's
 *       one pay group in practice.</li>
 * </ul>
 *
 * <h2>Payslip computation — every formula assumption, explicit</h2>
 * <p>Confirmed against a real Keka "Update Annual salary structure" screenshot (2026-11-05, the
 * org's actual 2026-27 structure) where it could be checked; still a defensible reading where it
 * couldn't (PF/ESI/TDS are deliberately out of scope — see below).
 * <ol>
 *   <li><b>Structure component values are ANNUAL, like Keka's own screen says</b> ("Update Annual
 *       salary structure") — confirmed directly: that screen shows Conveyance Allowance as a fixed
 *       {@code 19200}, which is exactly {@code 1,600/month × 12}, not 1,600 itself. So a FIXED
 *       value and every resolved PERCENT_OF_* value here is an annual figure; only the very last
 *       step (after every component is resolved) divides by {@value #MONTHS_PER_YEAR} to get the
 *       per-cycle base, before proration. Getting this backwards (treating a FIXED value as
 *       already monthly) would silently overpay a FIXED component 12×.</li>
 *   <li><b>Resolution order, confirmed against the real structure</b> (Basic {@code =[GROSS]*0.5},
 *       HRA {@code =[BASIC]*0.4}, LTA {@code =[BASIC]*0.2}, Conveyance fixed, Other Allowances the
 *       balancing remainder — "5 components," matching US-C01's own calc types exactly): FIXED
 *       components take their literal (annual) value. PERCENT_OF_COMPONENT components resolve in
 *       dependency order (iterative, handles an arbitrary non-cyclic chain; throws on a cycle,
 *       which US-C01's own validation doesn't by itself rule out across more than two components).
 *       PERCENT_OF_GROSS components compute as {@code value% × annual CTC} — "Gross" is simplified
 *       to equal annual CTC here, since no employer-only-contribution component type exists in the
 *       catalog yet (the real structure's Employer PF/Gratuity, which actually do separate Gross
 *       from CTC, live outside the structure's own component list — US-C29's gratuity provision is
 *       a separate ledger, not a payslip line item). BALANCING_REMAINDER = {@code annual CTC −
 *       sum(every other EARNING-type component computed so far)}.</li>
 *   <li><b>PF, Gratuity, Professional Tax and TDS are deliberately NOT computed here</b> — confirmed
 *       why, not just deferred on a guess: the real PF formula is conditional/capped
 *       ({@code IF CTC<180000, (CTC−HRA)×12%, else a flat capped amount}), which none of US-C01's
 *       four calc types can express, and Gratuity/PT/TDS have their own separate mechanisms. This
 *       is exactly Decision #5 (which mechanism owns each statutory deduction) still being open —
 *       this story correctly stops at the four calc types' reach rather than guessing a formula
 *       for something it structurally can't represent yet.</li>
 *   <li><b>cap/useLowerOfCap are NOT applied to the paid amount in this story.</b> They read as
 *       exemption-calculation fields for TDS (a Phase 5/US-C14 concern, unbuilt), not as a ceiling
 *       on take-home pay — applying them here risked silently underpaying someone on a guessed
 *       interpretation. Revisit once TDS computation exists and the real intent is confirmed.</li>
 *   <li><b>Proration</b> combines LOP (US-C04) and joiner/exit (US-C07) into one factor:
 *       {@code (employedDays − lopDays) / cycleDays}, clamped at 0 — not two separately multiplied
 *       fractions, which would double-penalize the overlap between "not yet employed" and
 *       "unpaid." Applied only to components with {@code SalaryComponent.prorateOnLop = true}
 *       (one flag governs both kinds of proration — there's no separate joiner/exit-proration flag
 *       in the catalog); confirmed Conveyance is the one recurring component that opts out
 *       ("all components except conveyance are reduced proportionally").</li>
 *   <li><b>Rounding:</b> every stored amount is HALF_UP to 2 decimals (Decision #10, rounding
 *       rule, is still unconfirmed).</li>
 *   <li><b>US-C24 ad-hoc payments and US-C25 ad-hoc deduction installments are never prorated</b>,
 *       regardless of the catalog component's own {@code prorateOnLop} flag — a one-off
 *       bonus/OT/referral amount or a fixed recovery installment an HR Admin explicitly set for
 *       this exact cycle isn't a recurring entitlement to scale down for a partial month; both are
 *       added/deducted at face value, same as Conveyance's own prorateOnLop=false treatment but
 *       for a different reason (deliberate exception, not a catalog property).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class PayrollRunService {

    private static final int MONTHS_PER_YEAR = 12;
    private static final Set<String> EARNING_TYPES = Set.of(
            SalaryComponentType.EARNING, SalaryComponentType.AD_HOC_EARNING, SalaryComponentType.REIMBURSEMENT);
    private static final Set<String> DEDUCTION_TYPES = Set.of(
            SalaryComponentType.DEDUCTION, SalaryComponentType.AD_HOC_DEDUCTION);

    private final PayrollRunRepository payrollRunRepository;
    private final PayslipRepository payslipRepository;
    private final PayslipLineItemRepository payslipLineItemRepository;
    private final PayGroupRepository payGroupRepository;
    private final EmployeeCompensationRepository employeeCompensationRepository;
    private final SalaryStructureComponentRepository salaryStructureComponentRepository;
    private final PayrollLopCalculationService payrollLopCalculationService;
    private final PayrollProrationService payrollProrationService;
    private final ApprovalRuleEvaluationService approvalRuleEvaluationService;
    private final AdHocPaymentRepository adHocPaymentRepository;
    private final AdHocDeductionRepository adHocDeductionRepository;
    private final AdHocDeductionInstallmentRepository adHocDeductionInstallmentRepository;
    private final SalaryHoldRepository salaryHoldRepository;
    private final PayrollRunReviewService payrollRunReviewService;
    private final SalaryStructureResolutionService salaryStructureResolutionService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public PayrollRunResponse generateDraft(GenerateDraftRequest req, String actorEmail) {
        PayGroup payGroup = payGroupRepository.findById(req.getPayGroupId())
                .orElseThrow(() -> new NoSuchElementException("Pay group not found: " + req.getPayGroupId()));
        PayCycleWindow window = payGroup.resolveCycle(req.getReferenceDate() != null ? req.getReferenceDate() : LocalDate.now());

        Optional<PayrollRun> existing = payrollRunRepository
                .findByPayGroupIdAndCycleStartAndCycleEnd(payGroup.getId(), window.start(), window.end());
        existing.ifPresent(r -> {
            if (!PayrollRunStatus.DRAFT.equals(r.getStatus())) {
                throw new IllegalStateException("Run is already " + r.getStatus() + " — roll it back before regenerating");
            }
        });

        PayrollRun run = existing.orElseGet(() -> PayrollRun.builder()
                .payGroupId(payGroup.getId())
                .cycleStart(window.start())
                .cycleEnd(window.end())
                .totalGross(BigDecimal.ZERO)
                .totalDeductions(BigDecimal.ZERO)
                .totalNet(BigDecimal.ZERO)
                .build());
        run = payrollRunRepository.save(run);

        if (existing.isPresent()) {
            payslipLineItemRepository.deleteByPayrollRunId(run.getId());
            payslipRepository.deleteByPayrollRunId(run.getId());
            // US-C22/US-C23: NOT wiped here — each included employee's review items are deleted
            // and recreated inside buildPayslip itself, AFTER that employee's LOP recompute has
            // already read the old ones (see PayrollRunReviewService#generateItemsForEmployee's
            // own Javadoc for why the ordering matters). An employee excluded from THIS regenerate
            // (see heldEmployeeIds below) never goes through buildPayslip at all, so their stale
            // review items are wiped explicitly, right below, once we know who's excluded.
        }

        List<EmployeeCompensation> compensations = employeeCompensationRepository.findAllEffectiveAt(window.end());
        if (compensations.isEmpty()) {
            throw new IllegalArgumentException("No employees have compensation effective for cycle " + window.start() + "-" + window.end());
        }

        // US-C26 "Hold processing": an employee with an ACTIVE PROCESSING-type SalaryHold on THIS
        // run is excluded entirely — no payslip, no line items, not counted — same as if they had
        // no effective compensation this cycle. Checked against run.getId(), so a hold created
        // before this regenerate takes effect now; one created after still needs the next
        // regenerate to apply. PAYOUT-type holds do NOT exclude — they're processed normally and
        // only intercepted later, at disburse() time.
        Set<UUID> heldEmployeeIds = salaryHoldRepository.findActiveEmployeeIdsByPayrollRunIdAndType(run.getId(), SalaryHoldType.PROCESSING);
        List<EmployeeCompensation> included = compensations.stream()
                .filter(comp -> !heldEmployeeIds.contains(comp.getEmployeeId()))
                .collect(Collectors.toList());

        // US-C22/US-C23: a held employee never goes through buildPayslip this regenerate, so their
        // review items (from a PRIOR regenerate, before the hold existed) would otherwise linger
        // forever as stale PENDING rows — permanently blocking submit() for a step that no longer
        // even applies to them. Wiped explicitly; harmless no-op when there's nothing to delete.
        for (UUID heldEmployeeId : heldEmployeeIds) {
            payrollRunReviewService.deleteItemsForEmployee(run.getId(), heldEmployeeId);
        }

        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalDeductions = BigDecimal.ZERO;
        for (EmployeeCompensation comp : included) {
            PayslipBuild build = buildPayslip(comp, window, payGroup.getId(), run, actorEmail);
            totalGross = totalGross.add(build.grossPay);
            totalDeductions = totalDeductions.add(build.totalDeductions);
        }

        run.setEmployeeCount(included.size());
        run.setTotalGross(totalGross);
        run.setTotalDeductions(totalDeductions);
        run.setTotalNet(totalGross.subtract(totalDeductions));
        run = payrollRunRepository.save(run);

        User actor = requireActor(actorEmail);
        auditService.log(actor.getId(), "PAYROLL_RUN_DRAFT_GENERATED", run.getId(), null, auditSnapshot.toJson(runSnapshot(run)));

        return toRunResponse(run);
    }

    @Transactional
    public PayrollRunResponse submit(UUID runId, String actorEmail) {
        PayrollRun run = requireRun(runId);
        if (!PayrollRunStatus.DRAFT.equals(run.getStatus())) {
            throw new IllegalStateException("Only a DRAFT run can be submitted (currently " + run.getStatus() + ")");
        }

        // US-C22/US-C23: both run steps must be fully decided before the run can leave DRAFT.
        if (!payrollRunReviewService.isStepComplete(runId, PayrollRunReviewStepType.LEAVE_APPLIED)) {
            throw new IllegalStateException("Cannot submit — the \"Leave Applied\" review step still has pending items");
        }
        if (!payrollRunReviewService.isStepComplete(runId, PayrollRunReviewStepType.NO_ATTENDANCE)) {
            throw new IllegalStateException("Cannot submit — the \"No Attendance\" review step still has pending items");
        }

        Map<String, BigDecimal> sums = payslipLineItemRepository.sumAmountByComponentType(runId).stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> (BigDecimal) row[1]));
        BigDecimal recomputedGross = sumByTypes(sums, EARNING_TYPES);
        BigDecimal recomputedDeductions = sumByTypes(sums, DEDUCTION_TYPES);
        if (recomputedGross.compareTo(run.getTotalGross()) != 0 || recomputedDeductions.compareTo(run.getTotalDeductions()) != 0) {
            throw new IllegalStateException("Reconciliation mismatch — line items no longer match the run's stored totals; regenerate before submitting");
        }

        // US-C09: snapshot the routing decision now, at submission — never re-evaluated later at
        // approve() time, same convention as ExpenseService#submit.
        ApprovalRuleEvaluationService.Decision decision = approvalRuleEvaluationService.evaluatePayrollRun(run.getTotalNet());
        run.setApprovalStages(ApprovalRuleEvaluationService.toCsv(decision.requiredStages()));
        run.setPendingApprovalStage(decision.requiredStages().get(0));
        run.setEvaluatedRuleId(decision.evaluatedRuleId());

        return transition(run, PayrollRunStatus.SUBMITTED, actorEmail, r -> r.setSubmittedAt(LocalDateTime.now()));
    }

    @Transactional
    public PayrollRunResponse approve(UUID runId, String actorEmail) {
        PayrollRun run = requireRun(runId);
        if (!PayrollRunStatus.SUBMITTED.equals(run.getStatus())) {
            throw new IllegalStateException("Only a SUBMITTED run can be approved (currently " + run.getStatus() + ")");
        }
        User actor = requireActor(actorEmail);
        // A run submitted before US-C09 (or against the legacy single-stage default) has no
        // chain to walk — treat it as the one HR_ADMIN-only stage this app always required.
        String currentStage = run.getPendingApprovalStage() != null
                ? run.getPendingApprovalStage() : ApprovalRuleEvaluationService.ROLE_HR_ADMIN;
        requireCanActAtApprovalStage(actor, currentStage);

        // Multi-layer collapse: an actor who also holds the role for the next stage(s) clears them
        // in this same call — same convention as ExpenseService#finalApprove.
        String next = nextApprovalStage(run, currentStage);
        while (next != null && actorHasRole(actor, next)) {
            next = nextApprovalStage(run, next);
        }
        if (next != null) {
            String before = auditSnapshot.toJson(runSnapshot(run));
            run.setPendingApprovalStage(next);
            run = payrollRunRepository.save(run);
            auditService.log(actor.getId(), "PAYROLL_RUN_STAGE_APPROVED", run.getId(), before, auditSnapshot.toJson(runSnapshot(run)));
            return toRunResponse(run);
        }

        return transition(run, PayrollRunStatus.APPROVED, actorEmail, r -> {
            r.setApprovedAt(LocalDateTime.now());
            r.setPendingApprovalStage(null);
        });
    }

    @Transactional
    public PayrollRunResponse disburse(UUID runId, String actorEmail) {
        PayrollRun run = requireRun(runId);
        if (!PayrollRunStatus.APPROVED.equals(run.getStatus())) {
            throw new IllegalStateException("Only an APPROVED run can be disbursed (currently " + run.getStatus() + ")");
        }

        // US-C26 "Hold payout": these employees' payslips were already built normally (statutory
        // deductions applied) — this is the one moment disburse() actually exists for, so flag
        // their payment as withheld rather than paid, instead of excluding them from anything.
        Set<UUID> payoutHeldEmployeeIds = salaryHoldRepository.findActiveEmployeeIdsByPayrollRunIdAndType(runId, SalaryHoldType.PAYOUT);
        if (!payoutHeldEmployeeIds.isEmpty()) {
            for (Payslip p : payslipRepository.findByPayrollRunId(runId)) {
                if (payoutHeldEmployeeIds.contains(p.getEmployeeId())) {
                    p.setPaymentWithheld(true);
                    payslipRepository.save(p);
                }
            }
        }

        return transition(run, PayrollRunStatus.DISBURSED, actorEmail, r -> r.setDisbursedAt(LocalDateTime.now()));
    }

    @Transactional
    public PayrollRunResponse rollbackWholeRun(UUID runId, RollbackRunRequest req, String actorEmail) {
        PayrollRun run = requireRun(runId);
        // US-C09: widened to also accept SUBMITTED, not just APPROVED/DISBURSED. Before this, a
        // SUBMITTED run that an approver wanted to reject (rather than advance) had no path back to
        // DRAFT at all — generateDraft refuses to regenerate anything but a DRAFT run, and this was
        // the only way back to DRAFT. Rejecting mid-chain reuses this exact mechanism (reasoned,
        // audited, reverts to DRAFT) rather than inventing a parallel reject flow.
        if (!PayrollRunStatus.APPROVED.equals(run.getStatus()) && !PayrollRunStatus.DISBURSED.equals(run.getStatus())
                && !PayrollRunStatus.SUBMITTED.equals(run.getStatus())) {
            throw new IllegalStateException("Only a SUBMITTED, APPROVED or DISBURSED run can be rolled back (currently " + run.getStatus() + ")");
        }
        if (!payrollRunRepository.findLaterLockedRuns(run.getPayGroupId(), run.getCycleStart()).isEmpty()) {
            throw new IllegalStateException("Cannot roll back: a later cycle for this pay group is already APPROVED or DISBURSED");
        }

        // US-C25: a rolled-back run is "as if it never ran" — every installment it collected has
        // to come back, or the next real attempt at this cycle would short-charge the employee by
        // whatever this run already (wrongly, since it's being discarded) took.
        for (AdHocDeductionInstallment installment : adHocDeductionInstallmentRepository.findByPayrollRunId(run.getId())) {
            AdHocDeduction deduction = installment.getAdHocDeduction();
            deduction.setRemainingBalance(deduction.getRemainingBalance().add(installment.getAmount()));
            if (AdHocDeductionStatus.COMPLETED.equals(deduction.getStatus())) {
                deduction.setStatus(AdHocDeductionStatus.ACTIVE);
            }
            adHocDeductionRepository.save(deduction);
            adHocDeductionInstallmentRepository.delete(installment);
        }

        String before = auditSnapshot.toJson(runSnapshot(run));
        run.setStatus(PayrollRunStatus.DRAFT);
        run.setPendingApprovalStage(null);
        run.setLastRolledBackAt(LocalDateTime.now());
        run.setLastRollbackReason(req.getReason());
        run = payrollRunRepository.save(run);

        User actor = requireActor(actorEmail);
        auditService.log(actor.getId(), "PAYROLL_RUN_TRANSITION", run.getId(), before, auditSnapshot.toJson(runSnapshot(run)));

        return toRunResponse(run);
    }

    @Transactional(readOnly = true)
    public PayrollRunResponse get(UUID runId) {
        return toRunResponse(requireRun(runId));
    }

    @Transactional(readOnly = true)
    public List<PayrollRunResponse> list(UUID payGroupId) {
        return payrollRunRepository.findByPayGroupIdOrderByCycleStartDesc(payGroupId).stream()
                .map(this::toRunResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PayslipResponse> listPayslips(UUID runId) {
        requireRun(runId);
        return payslipRepository.findByPayrollRunId(runId).stream()
                .map(p -> toPayslipResponse(p, payslipLineItemRepository.findByPayslipId(p.getId())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PayslipResponse getPayslip(UUID runId, UUID employeeId) {
        requireRun(runId);
        Payslip payslip = payslipRepository.findByPayrollRunIdAndEmployeeId(runId, employeeId)
                .orElseThrow(() -> new NoSuchElementException("No payslip for employee " + employeeId + " in run " + runId));
        return toPayslipResponse(payslip, payslipLineItemRepository.findByPayslipId(payslip.getId()));
    }

    /**
     * US-C27 — per-employee selective rollback: deletes and recomputes ONE employee's payslip from
     * current inputs, without disturbing the run's own status or anyone else's payslip. Unlike
     * {@link #rollbackWholeRun}, this never touches {@code status}/{@code pendingApprovalStage} —
     * the run stays exactly as locked (SUBMITTED/APPROVED/DISBURSED) as it was before the call,
     * only this one employee's figures (and the run's totals, recomputed fresh from every current
     * line item) change. There's no prior-version history to restore to — "rollback" here means
     * "delete and rebuild from whatever's true right now" (current compensation, current ad-hoc
     * payments/deductions, current proration/LOP), the exact same engine {@link #buildPayslip}
     * always uses, just scoped to one employee instead of the whole run.
     *
     * <p>Only valid for a run that ISN'T {@code DRAFT} — a DRAFT run already supports recomputing
     * everyone via {@link #generateDraft}'s regenerate path; this exists for the case that path
     * can't reach (the run is already locked, and reopening it for everyone via {@link
     * #rollbackWholeRun} would be the wrong size of hammer for one employee's mistake).
     */
    @Transactional
    public PayslipResponse rollbackEmployeePayslip(UUID runId, UUID employeeId, RollbackEmployeePayslipRequest req, String actorEmail) {
        PayrollRun run = requireRun(runId);
        if (PayrollRunStatus.DRAFT.equals(run.getStatus())) {
            throw new IllegalStateException("Use generateDraft to recompute a DRAFT run — this is for a SUBMITTED, APPROVED or DISBURSED run only");
        }
        Payslip existing = payslipRepository.findByPayrollRunIdAndEmployeeId(runId, employeeId)
                .orElseThrow(() -> new NoSuchElementException("No payslip for employee " + employeeId + " in run " + runId));
        EmployeeCompensation comp = employeeCompensationRepository.findByEmployeeIdAndEffectiveAt(employeeId, run.getCycleEnd())
                .orElseThrow(() -> new IllegalStateException("Employee " + employeeId + " has no compensation effective for this cycle — can't recompute"));

        String before = auditSnapshot.toJson(payslipSnapshot(existing));

        payslipLineItemRepository.deleteByPayrollRunIdAndEmployeeId(runId, employeeId);
        payslipRepository.deleteByPayrollRunIdAndEmployeeId(runId, employeeId);

        PayCycleWindow window = new PayCycleWindow(run.getCycleStart(), run.getCycleEnd());
        buildPayslip(comp, window, run.getPayGroupId(), run, actorEmail);

        // Recomputed fresh from every current line item in the run (everyone else's included,
        // unchanged) rather than adjusted by delta — the same reconciliation aggregation #submit
        // uses, so the run's stored totals can never silently drift from SUM(PayslipLineItem).
        Map<String, BigDecimal> sums = payslipLineItemRepository.sumAmountByComponentType(runId).stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> (BigDecimal) row[1]));
        BigDecimal totalGross = sumByTypes(sums, EARNING_TYPES);
        BigDecimal totalDeductions = sumByTypes(sums, DEDUCTION_TYPES);
        run.setTotalGross(totalGross);
        run.setTotalDeductions(totalDeductions);
        run.setTotalNet(totalGross.subtract(totalDeductions));
        payrollRunRepository.save(run);

        Payslip recomputed = payslipRepository.findByPayrollRunIdAndEmployeeId(runId, employeeId)
                .orElseThrow(() -> new IllegalStateException("Recompute did not produce a payslip for employee " + employeeId));
        User actor = requireActor(actorEmail);
        auditService.log(actor.getId(), "PAYROLL_PAYSLIP_RECOMPUTED", recomputed.getId(), before, auditSnapshot.toJson(payslipSnapshot(recomputed)));

        return toPayslipResponse(recomputed, payslipLineItemRepository.findByPayslipId(recomputed.getId()));
    }

    // ============================================================
    // Payslip computation
    // ============================================================

    private static class PayslipBuild {
        BigDecimal grossPay;
        BigDecimal totalDeductions;
    }

    private PayslipBuild buildPayslip(EmployeeCompensation comp, PayCycleWindow window, UUID payGroupId, PayrollRun run, String actorEmail) {
        RecomputeLopRequest lopReq = new RecomputeLopRequest();
        lopReq.setEmployeeId(comp.getEmployeeId());
        lopReq.setPayGroupId(payGroupId);
        lopReq.setReferenceDate(window.start());
        lopReq.setRunId(run.getId());
        PayrollLopCalculationService.RecomputeResult lopResult = payrollLopCalculationService.recomputeWithDetails(lopReq, actorEmail);
        PayrollLopCalculationResponse lop = lopResult.response();

        // US-C22/US-C23: (re)generate this employee's review items from the same unpaid-leave
        // requests, pending penalties and needsManualReviewDates just fetched — see
        // PayrollRunReviewService's own Javadoc for why these are passed through rather than
        // re-queried.
        payrollRunReviewService.generateItemsForEmployee(run, comp.getEmployeeId(), window, lop,
                lopResult.unpaidLeaveRequests(), lopResult.pendingPenalties());

        RecomputeProrationRequest proReq = new RecomputeProrationRequest();
        proReq.setEmployeeId(comp.getEmployeeId());
        proReq.setPayGroupId(payGroupId);
        proReq.setReferenceDate(window.start());
        PayrollProrationResponse pro = payrollProrationService.recompute(proReq, actorEmail);

        BigDecimal lopDays = lop.getEffectiveLopDays();
        int employedDays = pro.getEmployedDays();
        int cycleDays = pro.getCycleDays();
        BigDecimal prorationFactor = BigDecimal.valueOf(employedDays).subtract(lopDays)
                .divide(BigDecimal.valueOf(cycleDays), 4, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO);

        List<SalaryStructureComponent> components = salaryStructureComponentRepository.findByStructureId(comp.getStructure().getId());
        // Resolved in annual terms first (matching Keka's own "Update Annual salary structure"
        // convention), divided down to a monthly base only afterward — see the class javadoc.
        Map<UUID, BigDecimal> annualByComponentId = salaryStructureResolutionService.resolveAnnualValues(components, comp.getCtc());

        Payslip payslip = payslipRepository.save(Payslip.builder()
                .payrollRun(run)
                .employeeId(comp.getEmployeeId())
                .ctc(comp.getCtc())
                .cycleDays(cycleDays)
                .lopDays(lopDays)
                .employedDays(employedDays)
                .prorationFactor(prorationFactor)
                .grossPay(BigDecimal.ZERO)
                .totalDeductions(BigDecimal.ZERO)
                .netPay(BigDecimal.ZERO)
                .build());

        BigDecimal grossPay = BigDecimal.ZERO;
        BigDecimal totalDeductions = BigDecimal.ZERO;
        for (SalaryStructureComponent sc : components) {
            SalaryComponent catalog = sc.getComponent();
            BigDecimal monthlyBase = annualByComponentId.get(sc.getId())
                    .divide(BigDecimal.valueOf(MONTHS_PER_YEAR), 6, RoundingMode.HALF_UP);
            boolean prorated = catalog.isProrateOnLop();
            BigDecimal amount = (prorated ? monthlyBase.multiply(prorationFactor) : monthlyBase).setScale(2, RoundingMode.HALF_UP);

            payslipLineItemRepository.save(PayslipLineItem.builder()
                    .payslip(payslip)
                    .componentId(catalog.getId())
                    .componentName(catalog.getName())
                    .componentType(catalog.getType())
                    .amount(amount)
                    .prorated(prorated)
                    .build());

            if (EARNING_TYPES.contains(catalog.getType())) {
                grossPay = grossPay.add(amount);
            } else if (DEDUCTION_TYPES.contains(catalog.getType())) {
                totalDeductions = totalDeductions.add(amount);
            }
        }

        // US-C24: one-off ad-hoc payments added to this run for this employee — picked up on
        // every generateDraft call (including a regenerate), since they're tied to the run's own
        // id, which is stable across regeneration. Never prorated — see the class javadoc.
        for (AdHocPayment adHoc : adHocPaymentRepository.findByPayrollRunIdAndEmployeeId(run.getId(), comp.getEmployeeId())) {
            payslipLineItemRepository.save(PayslipLineItem.builder()
                    .payslip(payslip)
                    .componentId(adHoc.getComponentId())
                    .componentName(adHoc.getComponentName())
                    .componentType(adHoc.getComponentType())
                    .amount(adHoc.getAmount())
                    .prorated(false)
                    .build());
            grossPay = grossPay.add(adHoc.getAmount());
        }

        // US-C25: one installment per ACTIVE ad-hoc deduction plan, idempotent under regeneration
        // — see the class javadoc and AdHocDeductionInstallment's own.
        for (AdHocDeduction deduction : adHocDeductionRepository.findByEmployeeIdAndStatus(comp.getEmployeeId(), AdHocDeductionStatus.ACTIVE)) {
            AdHocDeductionInstallment installment = adHocDeductionInstallmentRepository
                    .findByAdHocDeductionIdAndPayrollRunId(deduction.getId(), run.getId())
                    .orElseGet(() -> applyNewInstallment(deduction, run));

            payslipLineItemRepository.save(PayslipLineItem.builder()
                    .payslip(payslip)
                    .componentId(deduction.getComponentId())
                    .componentName(deduction.getComponentName())
                    .componentType(deduction.getComponentType())
                    .amount(installment.getAmount())
                    .prorated(false)
                    .build());
            totalDeductions = totalDeductions.add(installment.getAmount());
        }

        payslip.setGrossPay(grossPay);
        payslip.setTotalDeductions(totalDeductions);
        payslip.setNetPay(grossPay.subtract(totalDeductions));
        payslipRepository.save(payslip);

        PayslipBuild result = new PayslipBuild();
        result.grossPay = grossPay;
        result.totalDeductions = totalDeductions;
        return result;
    }

    /**
     * The FIRST time a run sees this deduction plan: applies min(remainingBalance,
     * installmentAmount), decrements the plan's balance, and marks it COMPLETED once the balance
     * hits zero. Never called again for the same (deduction, run) pair — see buildPayslip's own
     * findByAdHocDeductionIdAndPayrollRunId check above, which is what makes regeneration safe.
     */
    private AdHocDeductionInstallment applyNewInstallment(AdHocDeduction deduction, PayrollRun run) {
        BigDecimal amount = deduction.getInstallmentAmount().min(deduction.getRemainingBalance());
        AdHocDeductionInstallment installment = adHocDeductionInstallmentRepository.save(AdHocDeductionInstallment.builder()
                .adHocDeduction(deduction)
                .payrollRun(run)
                .amount(amount)
                .build());

        deduction.setRemainingBalance(deduction.getRemainingBalance().subtract(amount));
        if (deduction.getRemainingBalance().signum() == 0) {
            deduction.setStatus(AdHocDeductionStatus.COMPLETED);
        }
        adHocDeductionRepository.save(deduction);

        return installment;
    }

    // ============================================================
    // Helpers
    // ============================================================

    private PayrollRunResponse transition(PayrollRun run, String newStatus, String actorEmail, java.util.function.Consumer<PayrollRun> stampTimestamp) {
        String before = auditSnapshot.toJson(runSnapshot(run));
        run.setStatus(newStatus);
        stampTimestamp.accept(run);
        run = payrollRunRepository.save(run);

        User actor = requireActor(actorEmail);
        auditService.log(actor.getId(), "PAYROLL_RUN_TRANSITION", run.getId(), before, auditSnapshot.toJson(runSnapshot(run)));

        return toRunResponse(run);
    }

    private BigDecimal sumByTypes(Map<String, BigDecimal> sums, Set<String> types) {
        return types.stream().map(t -> sums.getOrDefault(t, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The stage after {@code current} in the run's snapshotted stage list, or null when
     * {@code current} is the last one. Mirrors ExpenseService#nextFinalStage, simplified — every
     * PAYROLL_RUN stage is a "final-type" role (HR_ADMIN/SUPER_ADMIN), there's no separate
     * manager-stage tier to skip past. */
    private String nextApprovalStage(PayrollRun run, String current) {
        List<String> stages = run.getApprovalStages() != null
                ? ApprovalRuleEvaluationService.parseStages(run.getApprovalStages())
                : List.of(ApprovalRuleEvaluationService.ROLE_HR_ADMIN);
        int i = stages.indexOf(current);
        return (i >= 0 && i + 1 < stages.size()) ? stages.get(i + 1) : null;
    }

    /** Null stage never occurs in practice (approve() always resolves a concrete stage first) —
     * kept for symmetry with the same check in ExpenseService. Super Admin may act at the
     * HR_ADMIN stage too, never the reverse — same override convention used everywhere else. */
    private void requireCanActAtApprovalStage(User actor, String stage) {
        if (!actorHasRole(actor, stage) && !actorHasRole(actor, "SUPER_ADMIN")) {
            throw new AccessDeniedException("This run is awaiting " + stage + " approval");
        }
    }

    private boolean actorHasRole(User actor, String roleCode) {
        return actor.getRoles().stream().anyMatch(r -> roleCode.equals(r.getCode()));
    }

    private PayrollRun requireRun(UUID runId) {
        return payrollRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Payroll run not found: " + runId));
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Map<String, Object> runSnapshot(PayrollRun r) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", r.getStatus());
        snapshot.put("employeeCount", r.getEmployeeCount());
        snapshot.put("totalGross", r.getTotalGross());
        snapshot.put("totalDeductions", r.getTotalDeductions());
        snapshot.put("totalNet", r.getTotalNet());
        if (r.getPendingApprovalStage() != null) snapshot.put("pendingApprovalStage", r.getPendingApprovalStage());
        if (r.getLastRollbackReason() != null) snapshot.put("lastRollbackReason", r.getLastRollbackReason());
        return snapshot;
    }

    private Map<String, Object> payslipSnapshot(Payslip p) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("employeeId", p.getEmployeeId());
        snapshot.put("grossPay", p.getGrossPay());
        snapshot.put("totalDeductions", p.getTotalDeductions());
        snapshot.put("netPay", p.getNetPay());
        return snapshot;
    }

    private PayrollRunResponse toRunResponse(PayrollRun r) {
        return PayrollRunResponse.builder()
                .id(r.getId())
                .payGroupId(r.getPayGroupId())
                .cycleStart(r.getCycleStart())
                .cycleEnd(r.getCycleEnd())
                .status(r.getStatus())
                .employeeCount(r.getEmployeeCount())
                .totalGross(r.getTotalGross())
                .totalDeductions(r.getTotalDeductions())
                .totalNet(r.getTotalNet())
                .submittedAt(r.getSubmittedAt())
                .approvedAt(r.getApprovedAt())
                .disbursedAt(r.getDisbursedAt())
                .lastRolledBackAt(r.getLastRolledBackAt())
                .lastRollbackReason(r.getLastRollbackReason())
                .approvalStages(r.getApprovalStages() != null ? ApprovalRuleEvaluationService.parseStages(r.getApprovalStages()) : null)
                .pendingApprovalStage(r.getPendingApprovalStage())
                .createdAt(r.getCreatedAt())
                .build();
    }

    private PayslipResponse toPayslipResponse(Payslip p, List<PayslipLineItem> lineItems) {
        return PayslipResponse.builder()
                .id(p.getId())
                .payrollRunId(p.getPayrollRun().getId())
                .employeeId(p.getEmployeeId())
                .ctc(p.getCtc())
                .cycleDays(p.getCycleDays())
                .lopDays(p.getLopDays())
                .employedDays(p.getEmployedDays())
                .prorationFactor(p.getProrationFactor())
                .grossPay(p.getGrossPay())
                .totalDeductions(p.getTotalDeductions())
                .netPay(p.getNetPay())
                .released(p.isReleased())
                .paymentWithheld(p.isPaymentWithheld())
                .lineItems(lineItems.stream().map(li -> PayslipLineItemResponse.builder()
                        .componentId(li.getComponentId())
                        .componentName(li.getComponentName())
                        .componentType(li.getComponentType())
                        .amount(li.getAmount())
                        .prorated(li.isProrated())
                        .build()).collect(Collectors.toList()))
                .build();
    }
}

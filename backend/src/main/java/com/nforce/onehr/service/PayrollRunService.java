package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.*;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
 *   <li><b>PF, ESI and Professional Tax ARE computed, as of US-C13 Phase 3</b> — by
 *       {@link StatutoryDeductionService}, called right after the ad-hoc deduction installment loop
 *       below, reading whichever {@link StatutoryRule} version was effective for this cycle's own
 *       end date. PF Employee = {@code 12% × Basic} (this payslip's own, already-prorated Basic —
 *       read back from the structure component literally named "Basic", not re-derived from annual
 *       CTC), flat-capped at 12% of a wage ceiling that itself changed mid-2026 (₹15,000 before 17
 *       Sep 2026 per the Code on Social Security 2020 notification, ₹25,000 from the employee's
 *       October 2026 payslip onward — confirmed to the rupee against a real payslip: ₹19,058 Basic
 *       × 12% = ₹2,286.96 → ₹2,287). This supersedes an earlier, wrong {@code (CTC−HRA)×12%}
 *       reading (from a Keka screenshot, under the OLD ceiling) that real post-revision payslips
 *       disproved. The one-cycle-lag question this note used to flag as "observed but not yet
 *       HR-confirmed" IS resolved — Rohit confirmed directly it was a one-off manual override
 *       ("we did the override thats why this had happened, going forward it will consider the
 *       actual basic"), not a standing rule, so PF reads the CURRENT cycle's Basic. ESI uses an
 *       industry-standard default (0.75% employee, ₹21,000 monthly eligibility ceiling — below it,
 *       {@code grossPay × rate}; above it, not deducted at all) flagged as a best-effort default,
 *       not yet HR-confirmed. Professional Tax is country-level only, not per-state, because
 *       {@code Location.state} is null on every real location today — a real data gap, not a design
 *       choice. <b>Gratuity and TDS remain deliberately NOT computed</b>: Gratuity is an
 *       employer-side accrual that's never a payslip line item (needs its own provision ledger,
 *       US-C29, unbuilt); TDS needs full annual income-tax slab computation across other income and
 *       exemptions (Phase 5/US-C14, unbuilt) — {@code cap}/{@code useLowerOfCap} below were reserved
 *       for it and still aren't applied to anything.
 *       <p><b>None of PF/ESI/PT's numbers — rates, ceilings, the flat PT amount — are hardcoded as
 *       Java constants.</b> They live in {@link StatutoryRule} (US-C13): HR edits them (rate,
 *       ceiling, whatever else a slab needs) through that story's admin UI as effective-dated,
 *       versioned {@code slabConfig} JSON, and the computation reads the row effective for the
 *       payslip's own period — the same way a {@link com.nforce.onehr.entity.SalaryStructureComponent}'s
 *       percentage is an HR-entered value, never a constant in this class. A rate change must be a
 *       data change HR makes through the UI, never a deploy.</li>
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
    private final RecurringAdHocPaymentRuleRepository recurringAdHocPaymentRuleRepository;
    private final AdHocDeductionRepository adHocDeductionRepository;
    private final AdHocDeductionInstallmentRepository adHocDeductionInstallmentRepository;
    private final SalaryHoldRepository salaryHoldRepository;
    private final PayrollRunReviewService payrollRunReviewService;
    private final SalaryStructureResolutionService salaryStructureResolutionService;
    private final StatutoryDeductionService statutoryDeductionService;
    private final ArrearsService arrearsService;
    private final SalaryComponentRepository salaryComponentRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final PayslipService payslipService;
    private final PayRegisterExcelService payRegisterExcelService;
    private final PayrollRunRollbackRepository payrollRunRollbackRepository;
    private final ArrearsRepository arrearsRepository;
    private final PayrollRunReviewItemRepository payrollRunReviewItemRepository;
    private final BankTransferExcelService bankTransferExcelService;
    private final OtCalculationRepository otCalculationRepository;
    /** LV-05: confirmed year-end leave encashments are added to the next draft as ad-hoc payments. */
    private final LeaveEncashmentService leaveEncashmentService;

    /** Catalog component (V251) that automated overtime is posted under. */
    static final String OT_COMPONENT_NAME = "Over Time (OT)";

    // Default-timeout (30s, see application.yml) is sized for this app's normal transactions,
    // which are short and single-method. This one isn't: it loops once per included employee,
    // and each iteration makes ~10-15 sequential round trips (LOP recompute, review-item
    // generation, proration, structure/line-item writes) against the same shared Neon pooled
    // endpoint — so its real cost scales with employee count, not with "one request." Even two
    // employees were enough to trip the 30s ceiling on an ordinary (not even unusually slow)
    // round-trip pace. Overridden here rather than raising the global default, which would blunt
    // real-stall protection for every other, genuinely short, transaction in the app.
    // Bumped 180->600 (2026-11-19): even 180s was observed timing out mid-regenerate for just 11
    // employees against this endpoint on a slow day — this is a stopgap to unblock testing, not a
    // real fix. The real fix is batching this loop's per-employee round trips (bulk-fetch LOP/
    // proration/structure data for every included employee up front, outside the loop) so the
    // round-trip count stops scaling with employee count at all; worth doing if this recurs.
    @Transactional(timeout = 600)
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
        if (existing.isPresent()) {
            run.setRegenerateCount(run.getRegenerateCount() + 1);
        }
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

        // A compensation row effective-dated at window.end() doesn't guarantee the employee was
        // actually employed THROUGH this window — their joining date can sit after it (comp set up
        // ahead of a future start) or their last working day before it (comp never closed out after
        // exit). Either way buildPayslip's own PayrollProrationService#recompute call would throw
        // IllegalArgumentException for them — and since generateDraft is one @Transactional method,
        // that one employee rolled back payslip generation for EVERY employee in the run. Excluded
        // up front instead, the same way heldEmployeeIds already is, rather than letting it blow up.
        Map<UUID, Employee> employeesById = employeeRepository
                .findAllById(compensations.stream().map(EmployeeCompensation::getEmployeeId).collect(Collectors.toList()))
                .stream().collect(Collectors.toMap(Employee::getUserId, e -> e));
        List<EmployeeCompensation> included = new ArrayList<>();
        Set<UUID> excludedByWindowIds = new HashSet<>();
        for (EmployeeCompensation comp : compensations) {
            if (heldEmployeeIds.contains(comp.getEmployeeId())) continue;
            Employee employee = employeesById.get(comp.getEmployeeId());
            LocalDate joiningDate = employee == null ? null : employee.getJoiningDate();
            LocalDate exitDate = employee == null ? null : employee.getLastWorkingDay();
            boolean employedThroughWindow = employee != null
                    && !joiningDate.isAfter(window.end()) && (exitDate == null || !exitDate.isBefore(window.start()));
            if (employedThroughWindow) {
                included.add(comp);
            } else {
                excludedByWindowIds.add(comp.getEmployeeId());
            }
        }

        // US-C22/US-C23: a held or not-actually-employed-this-cycle employee never goes through
        // buildPayslip this regenerate, so their review items (from a PRIOR regenerate, before the
        // hold existed or before their exit date was recorded) would otherwise linger forever as
        // stale PENDING rows — permanently blocking submit() for a step that no longer even applies
        // to them. Wiped explicitly; harmless no-op when there's nothing to delete.
        for (UUID excludedEmployeeId : Stream.concat(heldEmployeeIds.stream(), excludedByWindowIds.stream()).collect(Collectors.toSet())) {
            payrollRunReviewService.deleteItemsForEmployee(run.getId(), excludedEmployeeId);
        }

        // Recurring ad-hoc payment rules (e.g. a standing monthly Food Allowance) — applied BEFORE
        // the buildPayslip loop below, which is what actually turns an AdHocPayment row into a
        // payslip line item by reading adHocPaymentRepository.findByPayrollRunIdAndEmployeeId per
        // employee. Safe on a regenerate: skips employees who already have this exact component in
        // this run (AdHocPayment rows aren't wiped on regenerate — see that class's own Javadoc),
        // so it can never double an employee up across repeated regenerates of the same run.
        applyActiveRecurringAdHocPaymentRules(run, payGroup.getId(),
                included.stream().map(EmployeeCompensation::getEmployeeId).collect(Collectors.toList()));
        // LV-05: year-end leave encashments waiting for payroll join this run as "Leave Encashment" ad-hoc payments
        // (once added they belong to this run, so a regenerate never adds them twice).
        leaveEncashmentService.attachPending(run,
                included.stream().map(EmployeeCompensation::getEmployeeId).collect(Collectors.toList()), requireActor(actorEmail).getId());

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

    /** One {@code findEmployeeIdsByPayrollRunIdAndComponentName} and one {@code saveAll} per
     *  active rule — not a per-employee round trip — same batching discipline as {@code
     *  AdHocPaymentService#bulkAdd}, which this mirrors (that one is actor-triggered from the Run
     *  console; this one is system-triggered from every generateDraft call). */
    private void applyActiveRecurringAdHocPaymentRules(PayrollRun run, UUID payGroupId, List<UUID> employeeIds) {
        List<RecurringAdHocPaymentRule> rules = recurringAdHocPaymentRuleRepository.findActiveForPayGroup(payGroupId);
        if (rules.isEmpty() || employeeIds.isEmpty()) return;

        LocalDateTime addedAt = LocalDateTime.now();
        List<AdHocPayment> toCreate = new ArrayList<>();
        for (RecurringAdHocPaymentRule rule : rules) {
            Set<UUID> alreadyCovered = new HashSet<>(
                    adHocPaymentRepository.findEmployeeIdsByPayrollRunIdAndComponentName(run.getId(), rule.getComponentName()));
            for (UUID employeeId : employeeIds) {
                if (alreadyCovered.contains(employeeId)) continue;
                toCreate.add(AdHocPayment.builder()
                        .payrollRun(run)
                        .employeeId(employeeId)
                        .componentId(rule.getComponentId())
                        .componentName(rule.getComponentName())
                        .componentType(rule.getComponentType())
                        .amount(rule.getAmount())
                        .reason(rule.getReason())
                        .addedBy(rule.getCreatedBy())
                        .addedAt(addedAt)
                        .build());
            }
        }
        if (!toCreate.isEmpty()) {
            adHocPaymentRepository.saveAll(toCreate);
        }
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

    /**
     * US-C10 (Keka-walkthrough amendments, 4 Nov 2026) — the human-readable pre-approval summary:
     * this cycle vs the previous finalized one, status counts, and the per-employee variance list.
     * Read-only and side-effect-free — the hard reconciliation block that actually stops a bad
     * submission already lives in {@link #submit}; this is the reviewer-facing summary layered on
     * top of it, callable at any run status (so it's just as useful to Finance reviewing a
     * SUBMITTED run as to the Payroll Admin preparing a DRAFT one).
     */
    @Transactional(readOnly = true)
    public PreApprovalSummaryResponse getPreApprovalSummary(UUID runId) {
        PayrollRun run = requireRun(runId);
        Optional<PayrollRun> previousRun = payrollRunRepository.findPreviousFinalizedRun(run.getPayGroupId(), run.getCycleStart());

        PeriodTotalsResponse current = periodTotals(run);
        PeriodTotalsResponse previous = previousRun.map(this::periodTotals).orElse(null);

        int onHoldCount = salaryHoldRepository.findActiveEmployeeIdsByPayrollRunIdAndType(runId, SalaryHoldType.PROCESSING).size();

        // "Needs attention" — every reason an employee isn't cleanly ready for approval, each
        // employee carrying the SPECIFIC reason(s) flagged, not just a bare count a reviewer can't
        // act on. (Employees excluded from the run entirely for a compensation/window problem
        // aren't tracked after generateDraft runs, so they can't be surfaced here without
        // re-running that exclusion check live.)
        Map<UUID, List<String>> needsAttentionReasons = new LinkedHashMap<>();
        for (String stepType : List.of(PayrollRunReviewStepType.LEAVE_APPLIED, PayrollRunReviewStepType.NO_ATTENDANCE)) {
            String reason = PayrollRunReviewStepType.LEAVE_APPLIED.equals(stepType)
                    ? "Pending Leave Applied review" : "Pending No Attendance review";
            payrollRunReviewItemRepository.findByPayrollRunIdAndStepType(runId, stepType).stream()
                    .filter(i -> LeaveReviewDecision.PENDING.equals(i.getDecision()))
                    .forEach(i -> needsAttentionReasons.computeIfAbsent(i.getEmployeeId(), k -> new ArrayList<>()).add(reason));
        }

        // US-C15: "missing bank details/PAN = processing error" — checked live against CURRENT
        // Employee data (not a snapshot from generation time), so adding the missing detail after
        // the fact correctly clears it here without a regenerate.
        // (Removed 2026-11-20: a "PF enabled but missing UAN" check — pfEnabled defaults off and
        // has no effect on whether PF is actually deducted, which still happens automatically via
        // Location/Country matching a Statutory Rule, same as always; the check only added
        // confusion about needing to toggle a flag nobody actually has to touch.)
        List<UUID> runEmployeeIds = payslipRepository.findByPayrollRunId(runId).stream().map(Payslip::getEmployeeId).toList();
        Map<UUID, Employee> runEmployeesById = new LinkedHashMap<>();
        for (Employee e : employeeRepository.findAllById(runEmployeeIds)) {
            runEmployeesById.put(e.getUserId(), e);
            List<String> reasons = needsAttentionReasons.computeIfAbsent(e.getUserId(), k -> new ArrayList<>());
            if (isBlank(e.getBankAccountNumber())) reasons.add("Missing bank account number");
            if (isBlank(e.getBankIfsc())) reasons.add("Missing IFSC code");
            if (isBlank(e.getPanNumber())) reasons.add("Missing PAN");
            if (reasons.isEmpty()) needsAttentionReasons.remove(e.getUserId());
        }

        List<NeedsAttentionItemResponse> needsAttentionDetails = needsAttentionReasons.entrySet().stream()
                .map(entry -> NeedsAttentionItemResponse.builder()
                        .employeeId(entry.getKey())
                        .employeeName(runEmployeesById.containsKey(entry.getKey())
                                ? runEmployeesById.get(entry.getKey()).getFullName() : "Unknown")
                        .reasons(entry.getValue())
                        .build())
                .toList();

        List<EmployeeVarianceResponse> variances = previousRun.map(prev -> buildVariances(run, prev)).orElseGet(List::of);

        return PreApprovalSummaryResponse.builder()
                .current(current)
                .previous(previous)
                .processedCount(run.getEmployeeCount())
                .onHoldCount(onHoldCount)
                .needsAttentionCount(needsAttentionDetails.size())
                .needsAttentionDetails(needsAttentionDetails)
                .variances(variances)
                .build();
    }

    private PeriodTotalsResponse periodTotals(PayrollRun run) {
        Map<String, BigDecimal> sums = payslipLineItemRepository.sumAmountByComponentType(run.getId()).stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> (BigDecimal) row[1]));
        return PeriodTotalsResponse.builder()
                .grossPay(run.getTotalGross())
                .totalDeductions(run.getTotalDeductions())
                .netPay(run.getTotalNet())
                .employerContributions(sums.getOrDefault(SalaryComponentType.CONTRIBUTION, BigDecimal.ZERO))
                .employeeCount(run.getEmployeeCount())
                .build();
    }

    // Everyone present in both this run and the previous one whose net pay actually differs, with
    // a best-effort list of likely drivers. Deliberately inclusive rather than exclusive about
    // drivers (an employee can have more than one real cause in the same cycle) — "Other" only
    // appears when nothing concrete was found, so the list is never silently empty for a real
    // change. Not a substitute for actually opening the two payslips; it's a triage aid for the
    // "why did my net change by 15 rupees" queries the team said they get routinely.
    private List<EmployeeVarianceResponse> buildVariances(PayrollRun run, PayrollRun previousRun) {
        Map<UUID, Payslip> current = payslipRepository.findByPayrollRunId(run.getId()).stream()
                .collect(Collectors.toMap(Payslip::getEmployeeId, p -> p));
        Map<UUID, Payslip> previous = payslipRepository.findByPayrollRunId(previousRun.getId()).stream()
                .collect(Collectors.toMap(Payslip::getEmployeeId, p -> p));

        Set<UUID> adHocPaymentEmployeeIds = adHocPaymentRepository.findByPayrollRunId(run.getId()).stream()
                .map(AdHocPayment::getEmployeeId).collect(Collectors.toSet());
        Set<UUID> adHocDeductionEmployeeIds = adHocDeductionInstallmentRepository.findByPayrollRunId(run.getId()).stream()
                .map(i -> i.getAdHocDeduction().getEmployeeId()).collect(Collectors.toSet());
        Set<UUID> arrearsEmployeeIds = arrearsRepository.findByAppliedToRunId(run.getId()).stream()
                .map(Arrears::getEmployeeId).collect(Collectors.toSet());

        List<UUID> commonEmployeeIds = current.keySet().stream().filter(previous::containsKey).toList();
        Map<UUID, Employee> employeesById = employeeRepository.findAllById(commonEmployeeIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, e -> e));

        List<EmployeeVarianceResponse> variances = new ArrayList<>();
        for (UUID employeeId : commonEmployeeIds) {
            Payslip cur = current.get(employeeId);
            Payslip prev = previous.get(employeeId);
            BigDecimal delta = cur.getNetPay().subtract(prev.getNetPay());
            if (delta.abs().compareTo(new BigDecimal("0.01")) < 0) continue;

            List<String> drivers = new ArrayList<>();
            if (cur.getLopDays().compareTo(prev.getLopDays()) != 0) drivers.add("LOP change");
            if (cur.getCtc().compareTo(prev.getCtc()) != 0) drivers.add("Salary revision");
            if (arrearsEmployeeIds.contains(employeeId)) drivers.add("Arrears");
            if (adHocPaymentEmployeeIds.contains(employeeId)) drivers.add("Ad-hoc payment");
            if (adHocDeductionEmployeeIds.contains(employeeId)) drivers.add("Ad-hoc deduction");
            BigDecimal curStatutory = sumLineItemsNamed(cur.getId(), "PF", "ESI", "Professional Tax");
            BigDecimal prevStatutory = sumLineItemsNamed(prev.getId(), "PF", "ESI", "Professional Tax");
            if (curStatutory.compareTo(prevStatutory) != 0) drivers.add("Statutory deduction change");
            if (drivers.isEmpty()) drivers.add("Other");

            Employee employee = employeesById.get(employeeId);
            variances.add(EmployeeVarianceResponse.builder()
                    .employeeId(employeeId)
                    .employeeName(employee != null ? employee.getFullName() : "Unknown")
                    .previousNetPay(prev.getNetPay())
                    .currentNetPay(cur.getNetPay())
                    .delta(delta)
                    .drivers(drivers)
                    .build());
        }
        variances.sort(Comparator.comparing((EmployeeVarianceResponse v) -> v.getDelta().abs()).reversed());
        return variances;
    }

    private BigDecimal sumLineItemsNamed(UUID payslipId, String... componentNames) {
        Set<String> names = Set.of(componentNames);
        return payslipLineItemRepository.findByPayslipId(payslipId).stream()
                .filter(li -> names.contains(li.getComponentName()))
                .map(PayslipLineItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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

        // Release automated OT claimed by this run so the next attempt re-picks it.
        List<OtCalculation> claimedOt = otCalculationRepository.findByPayrollRunId(run.getId());
        claimedOt.forEach(c -> c.setPayrollRunId(null));
        otCalculationRepository.saveAll(claimedOt);

        User actor = requireActor(actorEmail);
        String before = auditSnapshot.toJson(runSnapshot(run));
        run.setStatus(PayrollRunStatus.DRAFT);
        run.setPendingApprovalStage(null);
        run.setLastRolledBackAt(LocalDateTime.now());
        run.setLastRollbackReason(req.getReason());
        run.setLastRolledBackBy(actor.getId());
        run = payrollRunRepository.save(run);

        payrollRunRollbackRepository.save(PayrollRunRollback.builder()
                .payrollRunId(run.getId()).reason(req.getReason()).rolledBackBy(actor.getId())
                .rolledBackAt(run.getLastRolledBackAt()).build());

        auditService.log(actor.getId(), "PAYROLL_RUN_TRANSITION", run.getId(), before, auditSnapshot.toJson(runSnapshot(run)));

        return toRunResponse(run);
    }

    @Transactional(readOnly = true)
    public List<PayrollRunRollbackResponse> listRollbackHistory(UUID runId) {
        requireRun(runId);
        return payrollRunRollbackRepository.findByPayrollRunIdOrderByRolledBackAtDesc(runId).stream()
                .map(r -> PayrollRunRollbackResponse.builder()
                        .id(r.getId()).reason(r.getReason()).rolledBackBy(r.getRolledBackBy()).rolledBackAt(r.getRolledBackAt())
                        .build())
                .collect(Collectors.toList());
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

    /** A period is the {@code yyyy-MM} of the cycle's END date — same convention {@link
     * PayslipService} itself uses, so this run's own cycleEnd is all that's needed to find the
     * matching self-service payslip for this run's own employee. */
    @Transactional(readOnly = true)
    public PayslipService.PayslipFile getPayslipPdf(UUID runId, UUID employeeId, String actorEmail) {
        PayrollRun run = requireRun(runId);
        return payslipService.getPayslipPdf(actorEmail, employeeId, YearMonth.from(run.getCycleEnd()).toString());
    }

    /**
     * Centralized "Distribute Payslips" action on the Payslips tab — one call covers every
     * payslip in the run (or an explicit subset), never a per-employee send button. Only valid
     * once the run is APPROVED or DISBURSED — the same visibility floor {@link PayslipService}
     * itself enforces, so distribution can never run ahead of what's actually renderable.
     *
     * <p>PORTAL sets {@link Payslip#isReleased()} true in bulk — the real gate {@link
     * com.nforce.onehr.repository.PayslipRepository#findVisibleForEmployee} reads, so this is what
     * actually makes a payslip appear under My Finance (nothing does that automatically anymore).
     * EMAIL loops the same {@link PayslipService#sendPayslipEmail} a single-employee send always
     * used, one employee at a time inside its own try/catch, so one bad email address can't abort
     * the rest of the batch.
     */
    @Transactional
    public DistributePayslipsResponse distributePayslips(UUID runId, DistributePayslipsRequest req, String actorEmail) {
        PayrollRun run = requireRun(runId);
        if (!PayrollRunStatus.APPROVED.equals(run.getStatus()) && !PayrollRunStatus.DISBURSED.equals(run.getStatus())) {
            throw new IllegalStateException("Payslips can only be distributed for an Approved or Disbursed run (currently " + run.getStatus() + ")");
        }

        List<UUID> targetIds = (req.getEmployeeIds() != null && !req.getEmployeeIds().isEmpty())
                ? req.getEmployeeIds()
                : payslipRepository.findByPayrollRunId(runId).stream().map(Payslip::getEmployeeId).toList();

        int releasedCount = 0;
        if (req.getChannels().contains("PORTAL") && !targetIds.isEmpty()) {
            payslipRepository.releaseByPayrollRunIdAndEmployeeIdIn(runId, targetIds);
            releasedCount = targetIds.size();
        }

        int emailedCount = 0;
        int emailFailedCount = 0;
        if (req.getChannels().contains("EMAIL")) {
            String period = YearMonth.from(run.getCycleEnd()).toString();
            for (UUID employeeId : targetIds) {
                try {
                    if (payslipService.sendPayslipEmail(actorEmail, employeeId, period)) {
                        emailedCount++;
                    } else {
                        emailFailedCount++;
                    }
                } catch (Exception e) {
                    emailFailedCount++;
                }
            }
        }

        User actor = requireActor(actorEmail);
        auditService.log(actor.getId(), "PAYROLL_PAYSLIPS_DISTRIBUTED", run.getId(), null,
                auditSnapshot.toJson(Map.of("channels", req.getChannels(), "released", releasedCount, "emailed", emailedCount, "emailFailed", emailFailedCount)));

        return DistributePayslipsResponse.builder()
                .releasedCount(releasedCount).emailedCount(emailedCount).emailFailedCount(emailFailedCount)
                .build();
    }

    /** "Download All" on the Payslips tab — every payslip in the run, zipped, regardless of
     * distribution state (HR can always pull its own copies). Reuses {@link
     * PayslipService#getPayslipPdf} per employee, same rendering every other admin/self-service
     * download already uses, just packed into one archive instead of N separate downloads. */
    @Transactional(readOnly = true)
    public PayslipService.PayslipFile bulkPayslipPdf(UUID runId, String actorEmail) {
        PayrollRun run = requireRun(runId);
        String period = YearMonth.from(run.getCycleEnd()).toString();
        List<UUID> employeeIds = payslipRepository.findByPayrollRunId(runId).stream().map(Payslip::getEmployeeId).toList();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (UUID employeeId : employeeIds) {
                PayslipService.PayslipFile file = payslipService.getPayslipPdf(actorEmail, employeeId, period);
                zip.putNextEntry(new ZipEntry(file.fileName()));
                zip.write(file.data());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build payslips archive", e);
        }

        return new PayslipService.PayslipFile(buffer.toByteArray(), "application/zip", "payslips-" + period + ".zip");
    }

    /** "Export to Excel" on the Payslips tab — one Pay Register workbook, one row per employee in
     * the run, every distinct earning/deduction/contribution component across the whole run as its
     * own column (blank for an employee with no line item for it). Column sets are resolved here
     * (ordered by the catalog's own {@code displayOrder}, same convention {@link PayslipService
     * #lines} uses) and handed to {@link PayRegisterExcelService}, which only knows how to lay out
     * a workbook, not how to query payroll data. */
    @Transactional(readOnly = true)
    public PayslipService.PayslipFile payRegisterExcel(UUID runId) {
        PayrollRun run = requireRun(runId);
        List<Payslip> payslips = payslipRepository.findByPayrollRunId(runId);
        List<Object[]> rows = payslipLineItemRepository.findRowsByPayrollRunId(runId);

        List<UUID> employeeIds = payslips.stream().map(Payslip::getEmployeeId).toList();
        Map<UUID, Employee> employees = employeeRepository.findAllById(employeeIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, e -> e));

        Map<String, UUID> nameToComponentId = new LinkedHashMap<>();
        Map<String, String> nameToType = new LinkedHashMap<>();
        Map<UUID, Map<String, BigDecimal>> amountsByEmployee = new HashMap<>();
        for (Object[] row : rows) {
            UUID employeeId = (UUID) row[0];
            UUID componentId = (UUID) row[1];
            String name = (String) row[2];
            String type = (String) row[3];
            BigDecimal amount = (BigDecimal) row[4];
            nameToComponentId.putIfAbsent(name, componentId);
            nameToType.putIfAbsent(name, type);
            amountsByEmployee.computeIfAbsent(employeeId, k -> new HashMap<>()).merge(name, amount, BigDecimal::add);
        }

        Map<UUID, SalaryComponent> catalog = salaryComponentRepository.findAllById(nameToComponentId.values()).stream()
                .collect(Collectors.toMap(SalaryComponent::getId, c -> c));
        Comparator<String> byDisplayOrder = Comparator.<String>comparingInt(name -> {
            SalaryComponent c = catalog.get(nameToComponentId.get(name));
            return c == null ? 0 : c.getDisplayOrder();
        }).thenComparing(Comparator.naturalOrder());

        List<String> earningColumns = nameToType.entrySet().stream()
                .filter(e -> EARNING_TYPES.contains(e.getValue())).map(Map.Entry::getKey).sorted(byDisplayOrder).toList();
        List<String> deductionColumns = nameToType.entrySet().stream()
                .filter(e -> DEDUCTION_TYPES.contains(e.getValue())).map(Map.Entry::getKey).sorted(byDisplayOrder).toList();
        List<String> contributionColumns = nameToType.entrySet().stream()
                .filter(e -> SalaryComponentType.CONTRIBUTION.equals(e.getValue())).map(Map.Entry::getKey).sorted(byDisplayOrder).toList();

        int draftNumber = run.getRegenerateCount() + 1;
        byte[] xlsx = payRegisterExcelService.render(new PayRegisterExcelService.Model(
                payslips, employees, amountsByEmployee, earningColumns, deductionColumns, contributionColumns, draftNumber));
        String fileName = "pay-register-" + YearMonth.from(run.getCycleEnd()) + "-draft" + draftNumber + ".xlsx";
        return new PayslipService.PayslipFile(xlsx, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", fileName);
    }

    /**
     * Bank-details ticket (Keka-walkthrough amendments) — the "Bank bulk-upload sheet": bank
     * details + net pay only, for whoever is actually being paid this cycle. Two kinds of employee
     * never make it into the file, each a deliberate omission rather than an error:
     * <ul>
     *   <li>On an active Payout Hold for this run — nothing to transfer for them this cycle.</li>
     *   <li>Missing an account number or IFSC on file — same condition {@link
     *       #getPreApprovalSummary} already surfaces as "needs attention" before approval; this is
     *       the second place it matters, at the point money would actually move.</li>
     * </ul>
     * "Account Holder Name" uses the employee's own full name — there's no separate
     * account-holder-name field on {@link Employee} today (bank fields were unified with
     * PAN/UAN/ESI storage, not split into their own entity); revisit if a real mismatch case
     * (e.g. a joint account) ever needs one.
     */
    @Transactional(readOnly = true)
    public PayslipService.PayslipFile bankTransferExcel(UUID runId) {
        PayrollRun run = requireRun(runId);
        List<Payslip> payslips = payslipRepository.findByPayrollRunId(runId);
        Set<UUID> payoutHoldEmployeeIds = salaryHoldRepository.findActiveEmployeeIdsByPayrollRunIdAndType(runId, SalaryHoldType.PAYOUT);

        List<UUID> employeeIds = payslips.stream().map(Payslip::getEmployeeId).toList();
        Map<UUID, Employee> employees = employeeRepository.findAllById(employeeIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, e -> e));

        List<BankTransferExcelService.BankTransferRow> rows = new ArrayList<>();
        for (Payslip p : payslips) {
            if (payoutHoldEmployeeIds.contains(p.getEmployeeId())) continue;
            Employee e = employees.get(p.getEmployeeId());
            if (e == null || isBlank(e.getBankAccountNumber()) || isBlank(e.getBankIfsc())) continue;
            rows.add(new BankTransferExcelService.BankTransferRow(e.getFullName(), e.getBankAccountNumber(), e.getBankIfsc(), p.getNetPay()));
        }

        byte[] xlsx = bankTransferExcelService.render(new BankTransferExcelService.Model(rows));
        String fileName = "bank-transfer-" + YearMonth.from(run.getCycleEnd()) + ".xlsx";
        return new PayslipService.PayslipFile(xlsx, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", fileName);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
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
        // US-C13 Phase 3: PF is 12% of this payslip's own, already-prorated Basic — read back from
        // whichever structure component is literally named "Basic" (same name-matching convention
        // this class already uses throughout), not re-derived from annual CTC, so proration is
        // reflected exactly once, by construction.
        BigDecimal basicAmount = BigDecimal.ZERO;
        for (SalaryStructureComponent sc : components) {
            SalaryComponent catalog = sc.getComponent();
            BigDecimal monthlyBase = annualByComponentId.get(sc.getId())
                    .divide(BigDecimal.valueOf(MONTHS_PER_YEAR), 6, RoundingMode.HALF_UP);
            boolean prorated = catalog.isProrateOnLop();
            BigDecimal amount = (prorated ? monthlyBase.multiply(prorationFactor) : monthlyBase).setScale(2, RoundingMode.HALF_UP);

            // CONTRIBUTION-type structure components (Employer PF, Gratuity) exist here only so
            // SalaryStructureResolutionService can carve them out of Gross when resolving Basic —
            // see that service's own class doc. They never become a payslip line item: Gratuity is
            // explicitly never one (StatutoryDeductionService's own doc comment), and Employer PF
            // already gets its own, properly-prorated line item below via
            // statutoryDeductionService.computeEmployerContributions — adding this one too would
            // double it up on the payslip.
            if (!SalaryComponentType.CONTRIBUTION.equals(catalog.getType())) {
                payslipLineItemRepository.save(PayslipLineItem.builder()
                        .payslip(payslip)
                        .componentId(catalog.getId())
                        .componentName(catalog.getName())
                        .componentType(catalog.getType())
                        .amount(amount)
                        .prorated(prorated)
                        .build());
            }

            if (EARNING_TYPES.contains(catalog.getType())) {
                grossPay = grossPay.add(amount);
            } else if (DEDUCTION_TYPES.contains(catalog.getType())) {
                totalDeductions = totalDeductions.add(amount);
            }
            if ("Basic".equalsIgnoreCase(catalog.getName())) {
                basicAmount = amount;
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

        // Automated overtime: approved OT calculations inside this cycle become one separate earning
        // line. Idempotent under regeneration (payable = unclaimed or already claimed by this run).
        // Skipped when a manual "Over Time (OT)" ad-hoc payment already exists for the employee, so
        // the same overtime is never paid twice - the mismatch shows in the OT payroll summary.
        boolean manualOtPresent = adHocPaymentRepository.findByPayrollRunIdAndEmployeeId(run.getId(), comp.getEmployeeId())
                .stream().anyMatch(a -> OT_COMPONENT_NAME.equalsIgnoreCase(a.getComponentName()));
        if (!manualOtPresent) {
            List<OtCalculation> payableOt = otCalculationRepository.findPayable(
                    comp.getEmployeeId(), run.getCycleStart(), run.getCycleEnd(), run.getId());
            BigDecimal otTotal = payableOt.stream().map(OtCalculation::getOtAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
            Optional<SalaryComponent> otComponent = otTotal.signum() > 0
                    ? salaryComponentRepository.findByNameIgnoreCase(OT_COMPONENT_NAME).filter(SalaryComponent::isActive)
                    : Optional.empty();
            if (otComponent.isPresent()) {
                payslipLineItemRepository.save(PayslipLineItem.builder()
                        .payslip(payslip)
                        .componentId(otComponent.get().getId())
                        .componentName(otComponent.get().getName())
                        .componentType(otComponent.get().getType())
                        .amount(otTotal)
                        .prorated(false)
                        .build());
                grossPay = grossPay.add(otTotal);
                payableOt.forEach(c -> c.setPayrollRunId(run.getId()));
                otCalculationRepository.saveAll(payableOt);
            }
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

        // US-C13 Phase 3 — PF/ESI/Professional Tax, read from whichever StatutoryRule version was
        // effective for this cycle. Silently computes nothing for a rule type that isn't configured
        // yet (no catalog component, no effective rule, or no Location on the employee) — see
        // StatutoryDeductionService's own Javadoc for why that's deliberate, not a bug.
        Employee employee = employeeRepository.findById(comp.getEmployeeId())
                .orElseThrow(() -> new NoSuchElementException("Employee not found: " + comp.getEmployeeId()));
        for (StatutoryDeductionService.ComputedDeduction deduction : statutoryDeductionService.computeDeductions(employee, basicAmount, grossPay, window.start(), window.end())) {
            SalaryComponent catalog = deduction.component();
            payslipLineItemRepository.save(PayslipLineItem.builder()
                    .payslip(payslip)
                    .componentId(catalog.getId())
                    .componentName(catalog.getName())
                    .componentType(catalog.getType())
                    .amount(deduction.amount())
                    .prorated(false)
                    .build());
            totalDeductions = totalDeductions.add(deduction.amount());
        }

        // Employer-side contributions (e.g. Employer PF) — informational only, never subtracted
        // from gross/net pay; see StatutoryDeductionService#computeEmployerContributions for the
        // "nothing configured yet" silent-skip this shares with the employee-side loop above.
        for (StatutoryDeductionService.ComputedDeduction contribution : statutoryDeductionService.computeEmployerContributions(employee, basicAmount, window.end())) {
            SalaryComponent catalog = contribution.component();
            payslipLineItemRepository.save(PayslipLineItem.builder()
                    .payslip(payslip)
                    .componentId(catalog.getId())
                    .componentName(catalog.getName())
                    .componentType(catalog.getType())
                    .amount(contribution.amount())
                    .prorated(false)
                    .build());
        }

        // Arrears — pick up anything PENDING for this employee (see Arrears' own doc comment for
        // the three triggers that create these) and turn each into its own line item, flipping it
        // APPLIED. Left PENDING (not silently dropped) if "Arrears"/"Arrears Recovery" isn't set up
        // in the catalog yet — unlike a statutory deduction's "nothing configured" steady state,
        // this represents real money already owed from a real triggering event, so it waits for
        // the next regenerate once HR adds the component rather than vanishing.
        for (Arrears pending : arrearsService.findPendingFor(comp.getEmployeeId())) {
            boolean isRecovery = pending.getAmount().signum() < 0;
            String componentName = isRecovery ? "Arrears Recovery" : "Arrears";
            Optional<SalaryComponent> catalogOpt = salaryComponentRepository.findByNameIgnoreCase(componentName).filter(SalaryComponent::isActive);
            if (catalogOpt.isEmpty()) {
                continue;
            }
            SalaryComponent catalog = catalogOpt.get();
            BigDecimal amount = pending.getAmount().abs();
            payslipLineItemRepository.save(PayslipLineItem.builder()
                    .payslip(payslip)
                    .componentId(catalog.getId())
                    .componentName(catalog.getName())
                    .componentType(catalog.getType())
                    .amount(amount)
                    .prorated(false)
                    .build());
            if (isRecovery) {
                totalDeductions = totalDeductions.add(amount);
            } else {
                grossPay = grossPay.add(amount);
            }
            arrearsService.markApplied(pending, run.getId());
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
     * Arrears triggers 2 (backdated salary revision) and 3 (salary hold release) both need "what
     * would this ALREADY-CLOSED cycle's net pay be under {@code comp} instead of whatever was
     * actually used" — without touching the real, already-persisted {@link Payslip} for that
     * cycle. Deliberately NOT a reuse of {@link #buildPayslip}: that method recomputes LOP (a live
     * side-effecting write to {@link PayrollLopCalculation}) and regenerates US-C22/US-C23 review
     * items — both wrong here, since a closed cycle's LOP/attendance facts are frozen, not up for
     * re-derivation. This reuses the historical payslip's own already-frozen {@code
     * prorationFactor} instead, and otherwise mirrors buildPayslip's structure-component +
     * statutory-deduction math exactly (same two collaborators, same formulas) so the two never
     * drift apart. No {@link Payslip}/{@link PayslipLineItem} is created or saved.
     */
    public BigDecimal previewNetPay(EmployeeCompensation comp, LocalDate cycleStart, LocalDate cycleEnd, BigDecimal prorationFactor) {
        List<SalaryStructureComponent> components = salaryStructureComponentRepository.findByStructureId(comp.getStructure().getId());
        Map<UUID, BigDecimal> annualByComponentId = salaryStructureResolutionService.resolveAnnualValues(components, comp.getCtc());

        BigDecimal grossPay = BigDecimal.ZERO;
        BigDecimal totalDeductions = BigDecimal.ZERO;
        BigDecimal basicAmount = BigDecimal.ZERO;
        for (SalaryStructureComponent sc : components) {
            SalaryComponent catalog = sc.getComponent();
            BigDecimal monthlyBase = annualByComponentId.get(sc.getId())
                    .divide(BigDecimal.valueOf(MONTHS_PER_YEAR), 6, RoundingMode.HALF_UP);
            boolean prorated = catalog.isProrateOnLop();
            BigDecimal amount = (prorated ? monthlyBase.multiply(prorationFactor) : monthlyBase).setScale(2, RoundingMode.HALF_UP);
            if (EARNING_TYPES.contains(catalog.getType())) {
                grossPay = grossPay.add(amount);
            } else if (DEDUCTION_TYPES.contains(catalog.getType())) {
                totalDeductions = totalDeductions.add(amount);
            }
            if ("Basic".equalsIgnoreCase(catalog.getName())) {
                basicAmount = amount;
            }
        }

        Employee employee = employeeRepository.findById(comp.getEmployeeId())
                .orElseThrow(() -> new NoSuchElementException("Employee not found: " + comp.getEmployeeId()));
        for (StatutoryDeductionService.ComputedDeduction deduction : statutoryDeductionService.computeDeductions(employee, basicAmount, grossPay, cycleStart, cycleEnd)) {
            totalDeductions = totalDeductions.add(deduction.amount());
        }

        return grossPay.subtract(totalDeductions);
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
                .lastRolledBackBy(r.getLastRolledBackBy())
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

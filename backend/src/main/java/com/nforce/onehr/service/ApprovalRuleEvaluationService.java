package com.nforce.onehr.service;

import com.nforce.onehr.entity.ApprovalRule;
import com.nforce.onehr.repository.ApprovalRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Workflow Studio's rule-evaluation engine — the one place that decides which approval stages a
 * request actually needs, consumed by both {@link ExpenseService}/{@code PayrollRunService} (the
 * real production wiring) and {@code ApprovalRuleService}'s preview endpoint (so preview can
 * never drift from what production would actually do, per the story's "do not duplicate approval
 * logic" requirement).
 *
 * <p>{@code EXPENSE}/{@code AMOUNT}: MANAGER is always stage one in this codebase's Expense flow
 * (structural, not rule-configured) — a rule only ever decides whether the second/HR stage is
 * ALSO required. When no rule is active, evaluation falls back to this app's one and only
 * historical behavior: both stages, unconditionally (see {@link #legacyDefault()}) — this is what
 * keeps every existing Expense regression test passing with zero Workflow Studio configuration.
 *
 * <p><b>US-C09 (ONEHR-579):</b> {@code PAYROLL_RUN}/{@code TOTAL_NET} — unlike Expense, a payroll
 * run has no per-employee reporting manager to anchor a MANAGER stage on (it covers a whole pay
 * group), so stage one here is HR_ADMIN (whoever has {@code payroll:CONFIGURE:ALL} today) rather
 * than MANAGER; a rule only ever decides whether escalation to SUPER_ADMIN is ALSO required above
 * a total-net threshold. Decision #19/Q-07 (the real Finance approver role) is still unconfirmed
 * and no FINANCE role exists in this app's role catalog (EMPLOYEE/MANAGER/HR_ADMIN/SUPER_ADMIN
 * only) — SUPER_ADMIN is used as the escalation stage as the closest real fit, not a guess at
 * Finance's identity; revisit once Q-07 is answered. When no rule is active, evaluation falls back
 * to the single-step HR_ADMIN/SUPER_ADMIN approval this app shipped with before this story (see
 * {@link #legacyPayrollDefault()}) — either role could call {@code approve()}, no escalation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApprovalRuleEvaluationService {

    public static final String REQUEST_TYPE_EXPENSE = "EXPENSE";
    public static final String CONDITION_FIELD_AMOUNT = "AMOUNT";
    public static final String ROLE_MANAGER = "MANAGER";

    /**
     * Every stage after Manager is a "second approval" stage — HR_ADMIN, SUPER_ADMIN, or (since
     * V236) an active custom role's code. ApprovalRuleService only ever saves those values, so for
     * every pre-V236 rule this is exactly the old {HR_ADMIN, SUPER_ADMIN} membership test. Who may
     * act at each stage is ExpenseService#canActAtFinalStage's job, not this class's.
     */
    public static boolean isFinalStage(String stage) {
        return !ROLE_MANAGER.equals(stage);
    }

    public static final String REQUEST_TYPE_PAYROLL_RUN = "PAYROLL_RUN";
    public static final String CONDITION_FIELD_TOTAL_NET = "TOTAL_NET";
    public static final String ROLE_HR_ADMIN = "HR_ADMIN";

    // The only two roles a PAYROLL_RUN rule's stages may ever name — MANAGER doesn't apply (no
    // per-employee manager governs a whole-pay-group run) and there is no FINANCE role (see class
    // Javadoc, Decision #19/Q-07).
    private static final Set<String> PAYROLL_STAGE_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

    private final ApprovalRuleRepository ruleRepository;

    public record Decision(boolean secondApprovalRequired, List<String> requiredStages, UUID evaluatedRuleId) {}

    /**
     * The production entry point: reads whichever rule is currently active for EXPENSE (there
     * can be at most one — see V184) and evaluates it against {@code amount}. Never persists
     * anything; callers (ExpenseService#submit) are responsible for snapshotting the result.
     */
    @Transactional(readOnly = true)
    public Decision evaluateExpense(BigDecimal amount) {
        return ruleRepository.findByRequestTypeAndActiveTrue(REQUEST_TYPE_EXPENSE)
                .map(rule -> evaluate(rule, amount))
                .orElseGet(this::legacyDefault);
    }

    /** No active rule for this request type: preserve the app's original, unconditional behavior. */
    private Decision legacyDefault() {
        return new Decision(true, List.of(ROLE_MANAGER, "HR_ADMIN"), null);
    }

    /**
     * The production entry point for payroll runs: reads whichever rule is currently active for
     * PAYROLL_RUN (at most one — same V184 constraint as EXPENSE) and evaluates it against
     * {@code totalNet}. Never persists anything; {@code PayrollRunService#submit} snapshots the
     * result onto the run.
     */
    @Transactional(readOnly = true)
    public Decision evaluatePayrollRun(BigDecimal totalNet) {
        return ruleRepository.findByRequestTypeAndActiveTrue(REQUEST_TYPE_PAYROLL_RUN)
                .map(rule -> evaluatePayroll(rule, totalNet))
                .orElseGet(this::legacyPayrollDefault);
    }

    /** No active rule for PAYROLL_RUN: preserve the single-step HR_ADMIN/SUPER_ADMIN approval this
     * app shipped with before this story — either role could call approve(), no escalation. */
    private Decision legacyPayrollDefault() {
        return new Decision(false, List.of(ROLE_HR_ADMIN), null);
    }

    private Decision evaluatePayroll(ApprovalRule rule, BigDecimal totalNet) {
        List<String> ruleStages = parseStages(rule.getApprovalStages());
        // ApprovalRuleService rejects a PAYROLL_RUN rule naming any other role at save time; this
        // is a defensive fallback for a row saved before that validation existed, same convention
        // as evaluate()'s own no-second-approval-stage fallback below.
        if (ruleStages.stream().anyMatch(s -> !PAYROLL_STAGE_ROLES.contains(s))) {
            log.warn("Approval rule {} names a stage role invalid for PAYROLL_RUN ({}); ignoring it and applying the legacy default",
                    rule.getId(), rule.getApprovalStages());
            return legacyPayrollDefault();
        }
        boolean matches = evaluateCondition(totalNet, rule.getOperator(), new BigDecimal(rule.getConditionValue()));
        List<String> stages = matches ? ruleStages : List.of(ROLE_HR_ADMIN);
        log.info("Approval rule {} evaluated for PAYROLL_RUN: totalNet vs {} {} {} -> {}, stages={}",
                rule.getId(), rule.getOperator(), rule.getConditionValue(), matches,
                stages.size() > 1 ? "escalation required" : "single-stage", stages);
        return new Decision(stages.size() > 1, stages, rule.getId());
    }

    private Decision evaluate(ApprovalRule rule, BigDecimal amount) {
        // A rule whose own stages name no second-approval stage routes BOTH branches of its
        // condition to Manager-only, so activating it silently removed the HR final-clearance
        // stage from every expense claim (Manager-approved claims went straight to
        // CLEARED_FOR_PAYROLL and never reached HR's Approval Center queue). ApprovalRuleService
        // now rejects such rules on save; any saved before that is ignored here in favor of the
        // legacy default rather than stripping HR out of the workflow.
        if (parseStages(rule.getApprovalStages()).stream().noneMatch(ApprovalRuleEvaluationService::isFinalStage)) {
            log.warn("Approval rule {} has no second-approval stage ({}); ignoring it and applying the legacy default",
                    rule.getId(), rule.getApprovalStages());
            return legacyDefault();
        }
        boolean matches = evaluateCondition(amount, rule.getOperator(), new BigDecimal(rule.getConditionValue()));
        List<String> stages = matches ? parseStages(rule.getApprovalStages()) : List.of(ROLE_MANAGER);
        boolean secondApprovalRequired = stages.stream().anyMatch(ApprovalRuleEvaluationService::isFinalStage);
        log.info("Approval rule {} evaluated for {}: amount vs {} {} {} -> {}, stages={}",
                rule.getId(), rule.getRequestType(), rule.getOperator(), rule.getConditionValue(), matches,
                secondApprovalRequired ? "second approval required" : "manager only", stages);
        return new Decision(secondApprovalRequired, stages, rule.getId());
    }

    /** Evaluates a plain numeric condition — shared by production evaluation and preview alike. */
    public static boolean evaluateCondition(BigDecimal left, String operator, BigDecimal right) {
        int cmp = left.compareTo(right);
        return switch (operator) {
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            case "=" -> cmp == 0;
            default -> throw new IllegalArgumentException("Unsupported operator: " + operator);
        };
    }

    public static List<String> parseStages(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public static String toCsv(List<String> stages) {
        return String.join(",", stages);
    }
}

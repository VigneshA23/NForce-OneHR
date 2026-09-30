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
 * request actually needs, consumed by both {@link ExpenseService} (the real production wiring)
 * and {@code ApprovalRuleService}'s preview endpoint (so preview can never drift from what
 * production would actually do, per the story's "do not duplicate approval logic" requirement).
 *
 * <p>Phase 1 scope: {@code EXPENSE}/{@code AMOUNT} only. MANAGER is always stage one in this
 * codebase's Expense flow (structural, not rule-configured) — a rule only ever decides whether
 * the second/HR stage is ALSO required. When no rule is active for a request type, evaluation
 * falls back to this app's one and only historical behavior: both stages, unconditionally (see
 * {@link #legacyDefault()}) — this is what keeps every existing Expense regression test passing
 * with zero Workflow Studio configuration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApprovalRuleEvaluationService {

    public static final String REQUEST_TYPE_EXPENSE = "EXPENSE";
    public static final String CONDITION_FIELD_AMOUNT = "AMOUNT";
    public static final String ROLE_MANAGER = "MANAGER";

    // Either role satisfies the "second approval" stage — same convention ExpenseService's own
    // FINAL_APPROVER_ROLES already uses for who may act at that stage.
    private static final Set<String> FINAL_STAGE_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

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

    private Decision evaluate(ApprovalRule rule, BigDecimal amount) {
        boolean matches = evaluateCondition(amount, rule.getOperator(), new BigDecimal(rule.getConditionValue()));
        List<String> stages = matches ? parseStages(rule.getApprovalStages()) : List.of(ROLE_MANAGER);
        boolean secondApprovalRequired = stages.stream().anyMatch(FINAL_STAGE_ROLES::contains);
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

package com.nforce.onehr.service;

import com.nforce.onehr.dto.workflow.*;
import com.nforce.onehr.entity.ApprovalRule;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.ApprovalRuleRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Workflow Studio: CRUD, validation, activation lifecycle and preview for {@link ApprovalRule}.
 * Authorization (SUPER_ADMIN only) is enforced at the controller via {@code @PreAuthorize} — same
 * convention as every other admin-only controller in this codebase (e.g.
 * PenalisationPolicyManagementController) — this service assumes the caller is already authorized
 * and only needs the actor's identity for the createdBy/updatedBy audit trail.
 *
 * <p>Extensibility seam for Phase 2 (Leave/Regularization/Asset): add the new request type to
 * {@link #SUPPORTED_REQUEST_TYPES} and its allowed condition fields to
 * {@link #CONDITION_FIELDS_BY_REQUEST_TYPE} — every validation/metadata/preview path already
 * reads from these two maps, nothing else in this class is EXPENSE-specific except the
 * evaluation engine itself ({@link ApprovalRuleEvaluationService#evaluateExpense}, which a new
 * request type would need its own equivalent method for).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApprovalRuleService {

    public static final Set<String> SUPPORTED_REQUEST_TYPES = Set.of(ApprovalRuleEvaluationService.REQUEST_TYPE_EXPENSE);

    public static final Map<String, Set<String>> CONDITION_FIELDS_BY_REQUEST_TYPE = Map.of(
            ApprovalRuleEvaluationService.REQUEST_TYPE_EXPENSE, Set.of(ApprovalRuleEvaluationService.CONDITION_FIELD_AMOUNT)
    );

    public static final Set<String> SUPPORTED_OPERATORS = Set.of(">", ">=", "<", "<=", "=");

    public static final Set<String> SUPPORTED_APPROVAL_ROLES = Set.of("MANAGER", "HR_ADMIN", "SUPER_ADMIN");

    private final ApprovalRuleRepository ruleRepository;
    private final ApprovalRuleEvaluationService evaluationService;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;

    // ── Metadata (drives the rule-builder form's dropdowns) ──

    public ApprovalRuleMetadataResponse metadata() {
        return ApprovalRuleMetadataResponse.builder()
                .requestTypes(List.copyOf(SUPPORTED_REQUEST_TYPES))
                .conditionFieldsByRequestType(CONDITION_FIELDS_BY_REQUEST_TYPE.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue()))))
                .operators(List.copyOf(SUPPORTED_OPERATORS))
                .approvalRoles(List.copyOf(SUPPORTED_APPROVAL_ROLES))
                .build();
    }

    // ── CRUD ──

    @Transactional(readOnly = true)
    public List<ApprovalRuleResponse> listAll() {
        return ruleRepository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ApprovalRuleResponse getById(UUID id) {
        return toResponse(requireRule(id));
    }

    @Transactional
    public ApprovalRuleResponse create(ApprovalRuleRequest req, String actorEmail) {
        User actor = requireUser(actorEmail);
        validate(req, null);

        ApprovalRule rule = ApprovalRule.builder()
                .ruleName(req.getRuleName().trim())
                .requestType(req.getRequestType())
                .conditionField(req.getConditionField())
                .operator(req.getOperator())
                .conditionValue(req.getConditionValue().trim())
                .approvalStages(ApprovalRuleEvaluationService.toCsv(normalizeStages(req.getApprovalStages())))
                .active(false)
                .createdBy(actor.getId())
                .build();
        rule = ruleRepository.save(rule);
        log.info("Approval rule created: id={} requestType={} by={}", rule.getId(), rule.getRequestType(), actor.getId());
        return toResponse(rule);
    }

    @Transactional
    public ApprovalRuleResponse update(UUID id, ApprovalRuleRequest req, String actorEmail) {
        User actor = requireUser(actorEmail);
        ApprovalRule rule = requireRule(id);
        validate(req, id);

        // Editing an ACTIVE rule is allowed and takes effect immediately for any request
        // submitted from this point on — ExpenseService#submit always reads the live active rule
        // at submission time. Anything already in flight snapshotted its own decision already
        // (see ExpenseClaim.requiresSecondApproval) and is unaffected by this update.
        rule.setRuleName(req.getRuleName().trim());
        rule.setRequestType(req.getRequestType());
        rule.setConditionField(req.getConditionField());
        rule.setOperator(req.getOperator());
        rule.setConditionValue(req.getConditionValue().trim());
        rule.setApprovalStages(ApprovalRuleEvaluationService.toCsv(normalizeStages(req.getApprovalStages())));
        rule.setUpdatedBy(actor.getId());
        rule = ruleRepository.save(rule);
        log.info("Approval rule updated: id={} by={}", rule.getId(), actor.getId());
        return toResponse(rule);
    }

    @Transactional
    public void delete(UUID id) {
        ApprovalRule rule = requireRule(id);
        if (rule.isActive()) {
            throw new IllegalStateException("Cannot delete an active rule — deactivate it first");
        }
        ruleRepository.delete(rule);
        log.info("Approval rule deleted: id={}", id);
    }

    // ── Activation lifecycle ──

    /**
     * Activates {@code id} and, if another rule for the same requestType is currently active,
     * deactivates it in the same transaction — this (not a rejection) is the documented resolution
     * for "multiple active rules": the newly-activated rule always wins, and there is never a
     * moment where two rules are simultaneously active for one requestType (V184's unique index
     * would reject that anyway; this swap is what makes activation a one-click action instead of
     * requiring "deactivate the old one first" as a separate manual step).
     */
    @Transactional
    public ApprovalRuleResponse activate(UUID id, String actorEmail) {
        User actor = requireUser(actorEmail);
        ApprovalRule rule = requireRule(id);
        String requestType = rule.getRequestType();

        ruleRepository.findByRequestTypeAndActiveTrue(requestType)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    other.setActive(false);
                    other.setUpdatedBy(actor.getId());
                    ruleRepository.save(other);
                    log.info("Approval rule {} auto-deactivated: superseded by newly-activated rule {} for requestType={}",
                            other.getId(), id, requestType);
                });

        rule.setActive(true);
        rule.setUpdatedBy(actor.getId());
        rule = ruleRepository.save(rule);
        log.info("Approval rule activated: id={} requestType={} by={}", rule.getId(), rule.getRequestType(), actor.getId());
        return toResponse(rule);
    }

    @Transactional
    public ApprovalRuleResponse deactivate(UUID id, String actorEmail) {
        User actor = requireUser(actorEmail);
        ApprovalRule rule = requireRule(id);
        rule.setActive(false);
        rule.setUpdatedBy(actor.getId());
        rule = ruleRepository.save(rule);
        log.info("Approval rule deactivated: id={} requestType={} by={} — requests of this type now fall back to the legacy default routing",
                rule.getId(), rule.getRequestType(), actor.getId());
        return toResponse(rule);
    }

    // ── Preview (read-only, never persists) ──

    @Transactional(readOnly = true)
    public ApprovalRulePreviewResponse preview(ApprovalRulePreviewRequest req) {
        validateCore(req.getRequestType(), req.getConditionField(), req.getOperator(), req.getConditionValue(), req.getApprovalStages());
        List<String> stages = normalizeStages(req.getApprovalStages());
        BigDecimal conditionValue = new BigDecimal(req.getConditionValue().trim());
        BigDecimal sampleValue = req.getSampleValue() != null ? req.getSampleValue() : conditionValue;

        boolean conditionResult = ApprovalRuleEvaluationService.evaluateCondition(sampleValue, req.getOperator(), conditionValue);
        List<String> newRouting = conditionResult ? stages : List.of(ApprovalRuleEvaluationService.ROLE_MANAGER);

        List<String> currentRouting = ApprovalRuleEvaluationService.REQUEST_TYPE_EXPENSE.equals(req.getRequestType())
                ? currentExpenseRouting(sampleValue)
                : List.of(ApprovalRuleEvaluationService.ROLE_MANAGER);

        return ApprovalRulePreviewResponse.builder()
                .conditionField(req.getConditionField())
                .operator(req.getOperator())
                .conditionValue(conditionValue.toPlainString())
                .sampleValue(sampleValue)
                .conditionExpression(sampleValue.toPlainString() + " " + req.getOperator() + " " + conditionValue.toPlainString())
                .conditionResult(conditionResult)
                .currentRouting(currentRouting)
                .newRouting(newRouting)
                .build();
    }

    /** Reuses the exact same production evaluation logic — preview can never drift from reality. */
    private List<String> currentExpenseRouting(BigDecimal sampleValue) {
        return evaluationService.evaluateExpense(sampleValue).requiredStages();
    }

    // ── Validation ──

    private void validate(ApprovalRuleRequest req, UUID excludeId) {
        validateCore(req.getRequestType(), req.getConditionField(), req.getOperator(), req.getConditionValue(), req.getApprovalStages());

        boolean duplicate = ruleRepository.existsByRequestTypeAndConditionFieldAndOperatorAndConditionValueAndIdNot(
                req.getRequestType(), req.getConditionField(), req.getOperator(), req.getConditionValue().trim(),
                excludeId != null ? excludeId : new UUID(0, 0));
        if (duplicate) {
            throw new IllegalArgumentException(
                    "A rule with the same request type, condition field, operator and value already exists");
        }
    }

    private void validateCore(String requestType, String conditionField, String operator, String conditionValue,
                               List<String> approvalStages) {
        if (!SUPPORTED_REQUEST_TYPES.contains(requestType)) {
            throw new IllegalArgumentException("Unsupported request type: " + requestType);
        }
        Set<String> allowedFields = CONDITION_FIELDS_BY_REQUEST_TYPE.getOrDefault(requestType, Set.of());
        if (!allowedFields.contains(conditionField)) {
            throw new IllegalArgumentException("Invalid condition field '" + conditionField + "' for request type " + requestType);
        }
        if (!SUPPORTED_OPERATORS.contains(operator)) {
            throw new IllegalArgumentException("Invalid operator: " + operator);
        }
        if (conditionValue == null || conditionValue.isBlank()) {
            throw new IllegalArgumentException("Condition value is required");
        }
        // Phase 1: AMOUNT is the only condition field, and it's always numeric — this branch is
        // structural (not an if-this-field-then check), matching CONDITION_FIELDS_BY_REQUEST_TYPE
        // having exactly one entry per request type today.
        BigDecimal parsedValue;
        try {
            parsedValue = new BigDecimal(conditionValue.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Condition value must be a valid number: " + conditionValue);
        }
        if (parsedValue.signum() < 0) {
            throw new IllegalArgumentException("Condition value cannot be negative");
        }

        List<String> stages = normalizeStages(approvalStages);
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("At least one approval stage is required");
        }
        for (String stage : stages) {
            if (!SUPPORTED_APPROVAL_ROLES.contains(stage)) {
                throw new IllegalArgumentException("Invalid approval role: " + stage);
            }
        }
        if (!stages.get(0).equals(ApprovalRuleEvaluationService.ROLE_MANAGER)) {
            throw new IllegalArgumentException("Manager approval is always the first stage and must be included");
        }
    }

    private List<String> normalizeStages(List<String> stages) {
        return stages.stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    // ── Helpers ──

    private ApprovalRule requireRule(UUID id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Approval rule not found: " + id));
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private ApprovalRuleResponse toResponse(ApprovalRule rule) {
        return ApprovalRuleResponse.builder()
                .id(rule.getId())
                .ruleName(rule.getRuleName())
                .requestType(rule.getRequestType())
                .conditionField(rule.getConditionField())
                .operator(rule.getOperator())
                .conditionValue(rule.getConditionValue())
                .approvalStages(ApprovalRuleEvaluationService.parseStages(rule.getApprovalStages()))
                .active(rule.isActive())
                .createdByName(userDisplayName(rule.getCreatedBy()))
                .createdAt(rule.getCreatedAt())
                .updatedByName(rule.getUpdatedBy() != null ? userDisplayName(rule.getUpdatedBy()) : null)
                .updatedAt(rule.getUpdatedAt())
                .build();
    }

    private String userDisplayName(UUID userId) {
        if (userId == null) return null;
        return employeeRepository.findById(userId).map(Employee::getFullName)
                .orElseGet(() -> userRepository.findById(userId).map(User::getEmail).orElse("Unknown"));
    }
}

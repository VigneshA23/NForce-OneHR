package com.nforce.onehr.service;

import com.nforce.onehr.dto.workflow.ApprovalRulePreviewRequest;
import com.nforce.onehr.dto.workflow.ApprovalRulePreviewResponse;
import com.nforce.onehr.dto.workflow.ApprovalRuleRequest;
import com.nforce.onehr.dto.workflow.ApprovalRuleResponse;
import com.nforce.onehr.entity.ApprovalRule;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.ApprovalRuleRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApprovalRuleServiceTest {

    @Mock private ApprovalRuleRepository ruleRepository;
    @Mock private ApprovalRuleEvaluationService evaluationService;
    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;

    @InjectMocks
    private ApprovalRuleService service;

    private final String actorEmail = "superadmin@test.com";
    private User actor;

    @BeforeEach
    void setUp() {
        actor = User.builder().id(UUID.randomUUID()).email(actorEmail).build();
    }

    private ApprovalRuleRequest validExpenseRuleRequest() {
        ApprovalRuleRequest req = new ApprovalRuleRequest();
        req.setRuleName("Second approval above $500");
        req.setRequestType("EXPENSE");
        req.setConditionField("AMOUNT");
        req.setOperator(">");
        req.setConditionValue("500");
        req.setApprovalStages(List.of("MANAGER", "HR_ADMIN"));
        return req;
    }

    // ── Rule creation ─────────

    @Test
    void create_validRequest_savesInactiveRule() {
        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        when(ruleRepository.existsByRequestTypeAndConditionFieldAndOperatorAndConditionValueAndIdNot(
                any(), any(), any(), any(), any())).thenReturn(false);
        when(ruleRepository.save(any(ApprovalRule.class))).thenAnswer(inv -> {
            ApprovalRule r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        lenient().when(employeeRepository.findById(actor.getId())).thenReturn(Optional.empty());

        ApprovalRuleResponse res = service.create(validExpenseRuleRequest(), actorEmail);

        assertNotNull(res.getId());
        assertFalse(res.isActive(), "a newly created rule must start inactive (Save Draft, not Activate)");
        assertEquals(List.of("MANAGER", "HR_ADMIN"), res.getApprovalStages());
        verify(ruleRepository).save(any(ApprovalRule.class));
    }

    // ── Rule validation — invalid configurations must be rejected, never silently saved ─────────

    // "Missing rule name" is rejected by @NotBlank on ApprovalRuleRequest.ruleName at the
    // controller boundary (jakarta validation -> MethodArgumentNotValidException -> 400, same as
    // every other @Valid-annotated request DTO in this codebase) — a service-layer unit test
    // would only be exercising Mockito, not real validation, so that case belongs to a
    // controller-level/@WebMvcTest, not here.

    @Test
    void create_unsupportedRequestType_rejected() {
        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        ApprovalRuleRequest req = validExpenseRuleRequest();
        req.setRequestType("LEAVE");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(req, actorEmail));
        assertTrue(ex.getMessage().contains("Unsupported request type"));
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void create_invalidConditionField_rejected() {
        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        ApprovalRuleRequest req = validExpenseRuleRequest();
        req.setConditionField("InvalidCategoryName");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(req, actorEmail));
        assertTrue(ex.getMessage().contains("Invalid condition field"));
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void create_invalidOperator_rejected() {
        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        ApprovalRuleRequest req = validExpenseRuleRequest();
        req.setOperator("!=");

        assertThrows(IllegalArgumentException.class, () -> service.create(req, actorEmail));
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void create_nonNumericConditionValue_rejected() {
        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        ApprovalRuleRequest req = validExpenseRuleRequest();
        req.setConditionValue("not-a-number");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(req, actorEmail));
        assertTrue(ex.getMessage().contains("valid number"));
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void create_negativeConditionValue_rejected() {
        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        ApprovalRuleRequest req = validExpenseRuleRequest();
        req.setConditionValue("-100");

        assertThrows(IllegalArgumentException.class, () -> service.create(req, actorEmail));
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void create_invalidApprovalRole_rejected() {
        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        ApprovalRuleRequest req = validExpenseRuleRequest();
        req.setApprovalStages(List.of("MANAGER", "FINANCE_DIRECTOR"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(req, actorEmail));
        assertTrue(ex.getMessage().contains("Invalid approval role"));
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void create_approvalStagesMissingManagerFirst_rejected() {
        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        ApprovalRuleRequest req = validExpenseRuleRequest();
        req.setApprovalStages(List.of("HR_ADMIN"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(req, actorEmail));
        assertTrue(ex.getMessage().contains("Manager approval is always the first stage"));
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void create_duplicateRule_rejected() {
        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        when(ruleRepository.existsByRequestTypeAndConditionFieldAndOperatorAndConditionValueAndIdNot(
                eq("EXPENSE"), eq("AMOUNT"), eq(">"), eq("500"), any())).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(validExpenseRuleRequest(), actorEmail));
        assertTrue(ex.getMessage().contains("already exists"));
        verify(ruleRepository, never()).save(any());
    }

    // ── Rule update ─────────

    @Test
    void update_existingRule_appliesChanges() {
        ApprovalRule existing = ApprovalRule.builder()
                .id(UUID.randomUUID()).ruleName("Old").requestType("EXPENSE").conditionField("AMOUNT")
                .operator(">").conditionValue("500").approvalStages("MANAGER,HR_ADMIN").active(true)
                .createdBy(actor.getId()).build();

        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        when(ruleRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(ruleRepository.existsByRequestTypeAndConditionFieldAndOperatorAndConditionValueAndIdNot(
                any(), any(), any(), any(), any())).thenReturn(false);
        when(ruleRepository.save(any(ApprovalRule.class))).thenAnswer(inv -> inv.getArgument(0));

        ApprovalRuleRequest req = validExpenseRuleRequest();
        req.setConditionValue("1000");

        ApprovalRuleResponse res = service.update(existing.getId(), req, actorEmail);

        assertEquals("1000", res.getConditionValue());
        assertTrue(res.isActive(), "editing an active rule must not implicitly deactivate it");
    }

    @Test
    void update_nonExistentRule_throwsNoSuchElement() {
        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        UUID missingId = UUID.randomUUID();
        when(ruleRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThrows(java.util.NoSuchElementException.class,
                () -> service.update(missingId, validExpenseRuleRequest(), actorEmail));
    }

    // ── Activation / deactivation ─────────

    @Test
    void activate_noOtherActiveRule_activatesCleanly() {
        ApprovalRule rule = ApprovalRule.builder()
                .id(UUID.randomUUID()).requestType("EXPENSE").conditionField("AMOUNT")
                .operator(">").conditionValue("500").approvalStages("MANAGER,HR_ADMIN").active(false).build();

        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(ruleRepository.findByRequestTypeAndActiveTrue("EXPENSE")).thenReturn(Optional.empty());
        when(ruleRepository.save(any(ApprovalRule.class))).thenAnswer(inv -> inv.getArgument(0));

        ApprovalRuleResponse res = service.activate(rule.getId(), actorEmail);

        assertTrue(res.isActive());
        verify(ruleRepository, times(1)).save(any(ApprovalRule.class));
    }

    @Test
    void activate_anotherRuleAlreadyActiveForSameType_autoDeactivatesIt() {
        UUID otherId = UUID.randomUUID();
        ApprovalRule other = ApprovalRule.builder()
                .id(otherId).requestType("EXPENSE").conditionField("AMOUNT")
                .operator(">").conditionValue("200").approvalStages("MANAGER,HR_ADMIN").active(true).build();
        ApprovalRule toActivate = ApprovalRule.builder()
                .id(UUID.randomUUID()).requestType("EXPENSE").conditionField("AMOUNT")
                .operator(">").conditionValue("500").approvalStages("MANAGER,HR_ADMIN").active(false).build();

        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        when(ruleRepository.findById(toActivate.getId())).thenReturn(Optional.of(toActivate));
        when(ruleRepository.findByRequestTypeAndActiveTrue("EXPENSE")).thenReturn(Optional.of(other));
        when(ruleRepository.saveAndFlush(other)).thenReturn(other);
        when(ruleRepository.save(any(ApprovalRule.class))).thenAnswer(inv -> inv.getArgument(0));

        ApprovalRuleResponse res = service.activate(toActivate.getId(), actorEmail);

        assertTrue(res.isActive());
        assertFalse(other.isActive(), "the previously active rule for the same requestType must be auto-deactivated, never left active alongside the new one");
        // The deactivation must hit the DB before the activation, or the one-active-per-type
        // partial unique index rejects the activation.
        InOrder inOrder = inOrder(ruleRepository);
        inOrder.verify(ruleRepository).saveAndFlush(other);
        inOrder.verify(ruleRepository).save(toActivate);
    }

    @Test
    void deactivate_setsActiveFalse() {
        ApprovalRule rule = ApprovalRule.builder()
                .id(UUID.randomUUID()).requestType("EXPENSE").conditionField("AMOUNT")
                .operator(">").conditionValue("500").approvalStages("MANAGER,HR_ADMIN").active(true).build();

        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(ApprovalRule.class))).thenAnswer(inv -> inv.getArgument(0));

        ApprovalRuleResponse res = service.deactivate(rule.getId(), actorEmail);

        assertFalse(res.isActive());
    }

    // ── Delete ─────────

    @Test
    void delete_activeRule_rejected() {
        ApprovalRule rule = ApprovalRule.builder().id(UUID.randomUUID()).active(true).build();
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        assertThrows(IllegalStateException.class, () -> service.delete(rule.getId()));
        verify(ruleRepository, never()).delete(any());
    }

    @Test
    void delete_inactiveRule_succeeds() {
        ApprovalRule rule = ApprovalRule.builder().id(UUID.randomUUID()).active(false).build();
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        service.delete(rule.getId());

        verify(ruleRepository).delete(rule);
    }

    // ── Preview — read-only, must never persist ─────────

    @Test
    void preview_conditionTrue_returnsBothStages_andNeverPersists() {
        when(evaluationService.evaluateExpense(any())).thenReturn(
                new ApprovalRuleEvaluationService.Decision(true, List.of("MANAGER", "HR_ADMIN"), null));

        ApprovalRulePreviewRequest req = new ApprovalRulePreviewRequest();
        req.setRequestType("EXPENSE");
        req.setConditionField("AMOUNT");
        req.setOperator(">");
        req.setConditionValue("500");
        req.setApprovalStages(List.of("MANAGER", "HR_ADMIN"));
        req.setSampleValue(new BigDecimal("750"));

        ApprovalRulePreviewResponse res = service.preview(req);

        assertTrue(res.isConditionResult());
        assertEquals(List.of("MANAGER", "HR_ADMIN"), res.getNewRouting());
        assertEquals("750 > 500", res.getConditionExpression());
        verify(ruleRepository, never()).save(any());
        verify(ruleRepository, never()).delete(any());
    }

    @Test
    void preview_conditionFalse_returnsManagerOnly() {
        when(evaluationService.evaluateExpense(any())).thenReturn(
                new ApprovalRuleEvaluationService.Decision(true, List.of("MANAGER", "HR_ADMIN"), null));

        ApprovalRulePreviewRequest req = new ApprovalRulePreviewRequest();
        req.setRequestType("EXPENSE");
        req.setConditionField("AMOUNT");
        req.setOperator(">");
        req.setConditionValue("500");
        req.setApprovalStages(List.of("MANAGER", "HR_ADMIN"));
        req.setSampleValue(new BigDecimal("300"));

        ApprovalRulePreviewResponse res = service.preview(req);

        assertFalse(res.isConditionResult());
        assertEquals(List.of("MANAGER"), res.getNewRouting());
    }

    @Test
    void preview_invalidDraft_rejectedJustLikeCreate() {
        ApprovalRulePreviewRequest req = new ApprovalRulePreviewRequest();
        req.setRequestType("EXPENSE");
        req.setConditionField("InvalidCategoryName");
        req.setOperator(">");
        req.setConditionValue("500");
        req.setApprovalStages(List.of("MANAGER"));

        assertThrows(IllegalArgumentException.class, () -> service.preview(req));
    }
}

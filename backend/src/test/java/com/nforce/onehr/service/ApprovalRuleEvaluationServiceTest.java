package com.nforce.onehr.service;

import com.nforce.onehr.entity.ApprovalRule;
import com.nforce.onehr.repository.ApprovalRuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalRuleEvaluationServiceTest {

    @Mock private ApprovalRuleRepository ruleRepository;

    @InjectMocks
    private ApprovalRuleEvaluationService evaluationService;

    private ApprovalRule ruleAbove500(String approvalStages) {
        return ApprovalRule.builder()
                .id(UUID.randomUUID())
                .requestType("EXPENSE")
                .conditionField("AMOUNT")
                .operator(">")
                .conditionValue("500")
                .approvalStages(approvalStages)
                .active(true)
                .build();
    }

    // ── Condition evaluation — boundary cases ─────────

    @Test
    void amountBelowThreshold_conditionFalse() {
        assertFalse(ApprovalRuleEvaluationService.evaluateCondition(
                new BigDecimal("300"), ">", new BigDecimal("500")));
    }

    @Test
    void amountEqualToThreshold_greaterThan_conditionFalse() {
        assertFalse(ApprovalRuleEvaluationService.evaluateCondition(
                new BigDecimal("500"), ">", new BigDecimal("500")));
    }

    @Test
    void amountJustAboveThreshold_conditionTrue() {
        assertTrue(ApprovalRuleEvaluationService.evaluateCondition(
                new BigDecimal("501"), ">", new BigDecimal("500")));
    }

    @Test
    void amountWellAboveThreshold_conditionTrue() {
        assertTrue(ApprovalRuleEvaluationService.evaluateCondition(
                new BigDecimal("750"), ">", new BigDecimal("500")));
    }

    @Test
    void greaterThanOrEqual_atThreshold_conditionTrue() {
        assertTrue(ApprovalRuleEvaluationService.evaluateCondition(
                new BigDecimal("500"), ">=", new BigDecimal("500")));
    }

    @Test
    void greaterThanOrEqual_belowThreshold_conditionFalse() {
        assertFalse(ApprovalRuleEvaluationService.evaluateCondition(
                new BigDecimal("499.99"), ">=", new BigDecimal("500")));
    }

    @ParameterizedTest
    @CsvSource({
            "100, <, 200, true",
            "200, <, 200, false",
            "300, <, 200, false",
            "100, <=, 100, true",
            "101, <=, 100, false",
            "50, '=', 50, true",
            "50.00, '=', 50, true",
            "50.01, '=', 50, false",
    })
    void operatorMatrix(String left, String operator, String right, boolean expected) {
        assertEquals(expected, ApprovalRuleEvaluationService.evaluateCondition(
                new BigDecimal(left), operator, new BigDecimal(right)));
    }

    @Test
    void decimalAmounts_evaluateCorrectly() {
        assertTrue(ApprovalRuleEvaluationService.evaluateCondition(
                new BigDecimal("500.01"), ">", new BigDecimal("500.00")));
        assertFalse(ApprovalRuleEvaluationService.evaluateCondition(
                new BigDecimal("499.99"), ">", new BigDecimal("500.00")));
    }

    @Test
    void zeroAndNegativeAmounts_evaluateAgainstPositiveThreshold_conditionFalse() {
        assertFalse(ApprovalRuleEvaluationService.evaluateCondition(
                BigDecimal.ZERO, ">", new BigDecimal("500")));
        assertFalse(ApprovalRuleEvaluationService.evaluateCondition(
                new BigDecimal("-50"), ">", new BigDecimal("500")));
    }

    @Test
    void unsupportedOperator_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                ApprovalRuleEvaluationService.evaluateCondition(BigDecimal.ONE, "!=", BigDecimal.TEN));
    }

    // ── Stage parsing ─────────

    @Test
    void parseStages_splitsAndTrims() {
        assertEquals(List.of("MANAGER", "HR_ADMIN"), ApprovalRuleEvaluationService.parseStages("MANAGER, HR_ADMIN"));
    }

    @Test
    void toCsv_roundTrips() {
        assertEquals("MANAGER,HR_ADMIN", ApprovalRuleEvaluationService.toCsv(List.of("MANAGER", "HR_ADMIN")));
    }

    // ── Stage resolution against an active rule ─────────

    @Test
    void activeRule_conditionTrue_requiresSecondApprovalAndBothStages() {
        when(ruleRepository.findByRequestTypeAndActiveTrue("EXPENSE"))
                .thenReturn(Optional.of(ruleAbove500("MANAGER,HR_ADMIN")));

        ApprovalRuleEvaluationService.Decision decision = evaluationService.evaluateExpense(new BigDecimal("750"));

        assertTrue(decision.secondApprovalRequired());
        assertEquals(List.of("MANAGER", "HR_ADMIN"), decision.requiredStages());
        assertNotNull(decision.evaluatedRuleId());
    }

    @Test
    void activeRule_conditionFalse_managerOnly_noSecondApproval() {
        when(ruleRepository.findByRequestTypeAndActiveTrue("EXPENSE"))
                .thenReturn(Optional.of(ruleAbove500("MANAGER,HR_ADMIN")));

        ApprovalRuleEvaluationService.Decision decision = evaluationService.evaluateExpense(new BigDecimal("300"));

        assertFalse(decision.secondApprovalRequired());
        assertEquals(List.of("MANAGER"), decision.requiredStages());
    }

    @Test
    void noActiveRule_fallsBackToLegacyDefault_bothStagesAlwaysRequired() {
        when(ruleRepository.findByRequestTypeAndActiveTrue("EXPENSE")).thenReturn(Optional.empty());

        ApprovalRuleEvaluationService.Decision decision = evaluationService.evaluateExpense(new BigDecimal("1"));

        assertTrue(decision.secondApprovalRequired());
        assertNull(decision.evaluatedRuleId());
    }

    @Test
    void inactiveRuleIsNeverConsulted_onlyActiveTrueIsQueried() {
        // findByRequestTypeAndActiveTrue itself excludes inactive rows by construction — this
        // just documents/pins that evaluateExpense never widens that query.
        when(ruleRepository.findByRequestTypeAndActiveTrue("EXPENSE")).thenReturn(Optional.empty());

        ApprovalRuleEvaluationService.Decision decision = evaluationService.evaluateExpense(new BigDecimal("9999"));

        assertTrue(decision.secondApprovalRequired());
    }
}

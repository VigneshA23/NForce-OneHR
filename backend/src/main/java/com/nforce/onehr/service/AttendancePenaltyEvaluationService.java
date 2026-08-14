package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.PolicyDecision;
import com.nforce.onehr.dto.attendance.PolicyDecisionType;
import com.nforce.onehr.dto.attendance.PolicyEvaluationContext;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Bridges one discrepancy evaluation to a persisted {@link AttendancePenalty} row: calls the
 * configured {@link AttendancePolicyEngine}, and persists a row only when the decision is
 * {@code APPLY_PENALTY} — {@code NO_MATCH}, {@code EXEMPT}, and {@code CONFIGURATION_REQUIRED}
 * all produce nothing. The persisted row snapshots the policy id/version/evaluation time so a
 * later policy change never rewrites history.
 *
 * <p>Production caller: {@code ExceptionService.upsertException} invokes this the moment a
 * discrepancy is first detected (same "first detection only" gating already used for employee
 * notification emails) — the existing Exception Dashboard load is the trigger, not a scheduler.
 * The {@link #existsByEmployeeUserIdAndIncidentDateAndDiscrepancyType} guard below is a second,
 * defensive line against duplicate rows: even if a future caller re-evaluates the same
 * employee/date/discrepancy (e.g. a caller that doesn't gate on first-detection), at most one
 * {@link AttendancePenalty} row is ever created for it.
 */
@Service
@RequiredArgsConstructor
public class AttendancePenaltyEvaluationService {

    private final AttendancePolicyEngine policyEngine;
    private final AttendancePenaltyRepository attendancePenaltyRepository;

    @Transactional
    public Optional<AttendancePenalty> evaluate(PolicyEvaluationContext context) {
        PolicyDecision decision = policyEngine.evaluate(context);
        if (decision.getType() != PolicyDecisionType.APPLY_PENALTY) {
            return Optional.empty();
        }
        if (attendancePenaltyRepository.existsByEmployeeUserIdAndIncidentDateAndDiscrepancyType(
                context.getEmployeeUserId(), context.getAttendanceDate(), context.getDiscrepancyType())) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now();
        AttendancePenalty penalty = AttendancePenalty.builder()
                .employeeUserId(context.getEmployeeUserId())
                .incidentDate(context.getAttendanceDate())
                .discrepancyType(context.getDiscrepancyType())
                .status(AttendancePenaltyStatus.PENDING_REVIEW)
                .policyId(decision.getPolicyId())
                .policyVersion(decision.getPolicyVersion())
                .deductionDays(decision.getDeductionDays())
                .evaluatedAt(now)
                .penalizedOn(now)
                .build();
        return Optional.of(attendancePenaltyRepository.save(penalty));
    }
}

package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.PolicyDecision;
import com.nforce.onehr.dto.attendance.PolicyEvaluationContext;

/**
 * Seam between attendance discrepancy detection ({@link ExceptionService}) and attendance
 * penalties. The production implementation, {@link ConfiguredAttendancePolicyEngine}, reads the
 * currently effective {@link com.nforce.onehr.entity.PenalizationPolicyVersion} (configured via
 * Organization Masters → Penalization Policy) and evaluates it against the facts on
 * {@link com.nforce.onehr.dto.attendance.PolicyEvaluationContext} — {@code NO_MATCH} whenever no
 * version is effective for the discrepancy's date or its section is disabled. Nothing upstream
 * (attendance/exception detection) or downstream ({@link AttendancePenaltyEvaluationService})
 * needs to change if a future engine implementation replaces this one.
 */
public interface AttendancePolicyEngine {

    PolicyDecision evaluate(PolicyEvaluationContext context);
}

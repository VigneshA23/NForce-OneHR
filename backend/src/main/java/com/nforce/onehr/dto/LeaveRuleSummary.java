package com.nforce.onehr.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * LV-02: one leave type's rules from the employee's plan, as shown on the apply form before submission.
 * Null limits are "no limit". {@code hoursPerDay} converts hourly leave to days. LV-07 {@code sandwich*}: whether
 * weekly offs / holidays between leave days count (HOLIDAYS, WEEK_OFFS or BOTH; from N working days; across requests).
 */
public record LeaveRuleSummary(String entitlementMode, BigDecimal minDaysPerRequest, BigDecimal maxDaysPerRequest,
                               Integer maxRequestsPerMonth, Integer maxRequestsPerYear, BigDecimal maxConsecutiveDays,
                               Integer noticeDays, BigDecimal noticeAppliesOverDays, boolean allowHalfDay,
                               boolean allowHourly, BigDecimal minHours, BigDecimal attachmentRequiredOverDays,
                               List<String> attachmentAllowedTypes, Integer attachmentMaxMb,
                               List<String> notNextTo, BigDecimal hoursPerDay, boolean sandwichEnabled,
                               String sandwichAppliesTo, BigDecimal sandwichMinDays, boolean sandwichAcrossRequests) {}

package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Single-row (id always 1, enforced by the table's CHECK constraint — see
 * V151__create_wfh_partial_leave_policy.sql) org-wide policy: the monthly WFH-days allowance and
 * Partial Day monthly-minutes allowance, both Super Admin editable from the UI instead of
 * hardcoded — see AttendanceRequestService, which reads this on every submit/balance check
 * rather than caching it, so a saved change takes effect immediately with no redeploy.
 */
@Entity
@Table(name = "wfh_partial_leave_policy")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WfhPartialLeavePolicy {

    @Id
    private Short id;

    @Column(name = "wfh_monthly_limit_days", nullable = false)
    private Integer wfhMonthlyLimitDays;

    @Column(name = "partial_leave_monthly_limit_minutes", nullable = false)
    private Integer partialLeaveMonthlyLimitMinutes;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

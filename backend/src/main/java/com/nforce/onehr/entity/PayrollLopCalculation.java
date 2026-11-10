package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C04/US-C05 (ONEHR-579, Phase 2) — Loss-of-Pay days for one employee over one {@link
 * PayGroup#resolveCycle} window, aggregated from Attendance/AttendancePenalty/LeaveRequest/
 * LeaveBalance. See {@link com.nforce.onehr.service.PayrollLopCalculationService} for exactly how
 * {@code unpaidLeaveDays}/{@code penaltyLopDays}/{@code autoLopNoAttendanceDays} are derived —
 * sourced from a Keka product walkthrough (2026-11-03/04), not yet confirmed with the BA, so the
 * breakdown is kept visible (not collapsed into one opaque total) specifically so a human can spot
 * a wrong number before it reaches a payslip.
 *
 * <p>Unlike {@link EmployeeCompensation}/{@link StatutoryRule}, {@code computed*} fields here ARE
 * updated in place on recompute — they're reproducible from source data, not a legal record of a
 * decision. {@code overrideLopDays}/{@code overrideReason} ARE the decision (US-C05's own AC:
 * "computedLopDays is never overwritten" by an override), audited on every change.
 *
 * <p>"Override blocked once the run is finalized" (US-C05's own AC) isn't enforced here —
 * {@code PayrollRun} doesn't exist yet (Phase 3), same deferral as EmployeeCompensation's
 * backdating guard.
 */
@Entity
@Table(name = "payroll_lop_calculations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayrollLopCalculation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "cycle_start", nullable = false)
    private LocalDate cycleStart;

    @Column(name = "cycle_end", nullable = false)
    private LocalDate cycleEnd;

    // Approved LeaveType.classification=UNPAID leave, attributed to whichever cycle contains the
    // request's startDate (a request spanning a cycle boundary is simplified to its start cycle —
    // see the service for why).
    @Column(name = "unpaid_leave_days", nullable = false)
    private BigDecimal unpaidLeaveDays;

    // Sum of AttendancePenalty.lopDays for PENDING_REVIEW penalties with incidentDate in range.
    @Column(name = "penalty_lop_days", nullable = false)
    private BigDecimal penaltyLopDays;

    // Expected-working days (WorkingDayService) with no Attendance row, no penalty, no leave,
    // auto-counted as LOP only when the employee has zero available paid-leave balance (Keka: "if
    // an employee has no paid leave balance, the system automatically treats the absence as
    // unpaid"). Otherwise the date goes into needsManualReviewDates instead, pending US-C23's "No
    // Attendance" review step.
    @Column(name = "auto_lop_no_attendance_days", nullable = false)
    private BigDecimal autoLopNoAttendanceDays;

    // unpaidLeaveDays + penaltyLopDays + autoLopNoAttendanceDays — stored (not just derived) so
    // the audit log's before/after always matches exactly what was computed at that moment.
    @Column(name = "computed_lop_days", nullable = false)
    private BigDecimal computedLopDays;

    // JSON array of ISO dates — no-attendance working days where paid leave balance exists, so
    // auto-LOP wasn't applied; a human (US-C23) must choose deduct-paid-leave / mark-LOP / mark-present.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "needs_manual_review_dates", columnDefinition = "jsonb", nullable = false)
    private String needsManualReviewDates;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    @Column(name = "override_lop_days")
    private BigDecimal overrideLopDays;

    @Column(name = "override_reason", columnDefinition = "TEXT")
    private String overrideReason;

    @Column(name = "overridden_by")
    private UUID overriddenBy;

    @Column(name = "overridden_at")
    private LocalDateTime overriddenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

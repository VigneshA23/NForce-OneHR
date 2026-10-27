package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One Weekly Off Policy an employee has ever been assigned to, effective from a given calendar
 * date — the same effective-dated model {@link EmployeeShiftAssignment} already applies to WHICH
 * Shift governs an employee, applied here to WHICH Weekly Off Policy does. Immutable once
 * created — reassigning an employee always creates a NEW row rather than mutating an existing
 * one; replacing a still-pending (not-yet-effective) assignment is implemented as deleting it and
 * inserting a replacement (see {@code EmployeeAssignmentService#bulkUpdateWeeklyOff}), never an
 * in-place field update. This is what lets {@code EmployeeWeeklyOffPolicyAssignmentResolver} —
 * the one centralized place every date-aware consumer goes through — resolve "which Policy
 * governed this employee on date D" correctly and permanently, independent of whichever Policy
 * the employee is on today.
 *
 * <p>{@code Employee.weeklyOffPolicy} remains in the schema as a best-effort, non-authoritative
 * display cache (rosters, dropdowns, bulk filters) — it is never the source of truth for a
 * date-aware calculation once this table exists; every correctness-critical read resolves
 * through here instead, exactly mirroring {@code Employee.shiftId}'s identical relationship to
 * {@link EmployeeShiftAssignment}.
 *
 * <p>No effective assignment at all (a brand-new employee, or any employee predating this table's
 * backfill reach) is a valid, explicit state — see {@code WorkingDayService}/{@code
 * ExceptionService}'s existing Saturday/Sunday fallback, preserved unchanged by this model, never
 * replaced by a silently-assigned default policy (D1).
 *
 * <p>{@code effectiveFrom} means "the first calendar date this assignment governs" — a plain
 * date, matching {@link EmployeeShiftAssignment#getEffectiveFrom()}'s identical convention,
 * deliberately NOT a timestamp, for the same reason.
 */
@Entity
@Table(name = "employee_weekly_off_policy_assignments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployeeWeeklyOffPolicyAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "weekly_off_policy_id", nullable = false)
    private WeeklyOffPolicy weeklyOffPolicy;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Who made this assignment (a Manager/HR Admin/Super Admin acting via
    // EmployeeAssignmentService, or the system for the initial backfill) — audit trail only,
    // never read for resolution. Mirrors EmployeeShiftAssignment.createdBy.
    @Column(name = "created_by")
    private UUID createdBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

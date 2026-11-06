package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C07 (ONEHR-613), Phase 2 — new-joiner/exit pro-ration for one employee over one {@link
 * PayGroup#resolveCycle} window, plus exit-cycle leave encashment. Per the Keka walkthrough that
 * also informed {@link PayrollLopCalculation} (2026-11-03/04): "[joiner/exit proration] is
 * proration rather than LOP, but it uses the same mechanism" — {@code employedDays / cycleDays} is
 * the same shape as US-C04's {@code (cycleDays − lopDays) / cycleDays}, just with a different
 * source for the excluded days (not employed yet / no longer employed, vs. unpaid). Same caveat as
 * {@link PayrollLopCalculation}: this formula isn't BA-confirmed, and nothing here applies the
 * factor to an actual payslip yet — that's Phase 3.
 *
 * <p>{@code employedDays}/{@code cycleDays} are updated in place on recompute (reproducible from
 * {@code Employee.joiningDate}/{@code lastWorkingDay}), same treatment as
 * {@code PayrollLopCalculation.computedLopDays}. There is no override here — US-C07 has no
 * corresponding "US-C05" override story.
 */
@Entity
@Table(name = "payroll_prorations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayrollProration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "cycle_start", nullable = false)
    private LocalDate cycleStart;

    @Column(name = "cycle_end", nullable = false)
    private LocalDate cycleEnd;

    @Column(name = "cycle_days", nullable = false)
    private int cycleDays;

    // Calendar days in [cycleStart, cycleEnd] during which the employee was actually employed
    // (joiningDate..lastWorkingDay, clamped to the cycle window).
    @Column(name = "employed_days", nullable = false)
    private int employedDays;

    // employedDays / cycleDays, scale 4 — what Phase 3 would multiply each prorateOnLop-eligible
    // SalaryComponent by. 1.0000 for FULL_CYCLE.
    @Column(name = "proration_factor", nullable = false)
    private BigDecimal prorationFactor;

    @Column(name = "proration_type", nullable = false, length = 20)
    private String prorationType;

    // Only set when lastWorkingDay falls inside this cycle — available PAID leave balance at
    // exit, carried for payout. Null for a non-exit cycle.
    @Column(name = "leave_encashment_days")
    private BigDecimal leaveEncashmentDays;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * LV-05: one employee's leave type in a year-end run: the closing unused days and how they are split. The
 * suggested figures follow the plan (carry up to its cap → encash up to its cap → lapse); HR may adjust carry
 * and encash while the run is a preview (carry + encash ≤ closing; lapse is the rest).
 */
@Entity
@Table(name = "leave_year_end_run_lines")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveYearEndRunLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "leave_type_id", nullable = false)
    private UUID leaveTypeId;

    @Column(name = "closing_days", nullable = false)
    private BigDecimal closingDays;

    @Column(name = "suggested_carry", nullable = false)
    private BigDecimal suggestedCarry;

    @Column(name = "suggested_encash", nullable = false)
    private BigDecimal suggestedEncash;

    @Column(name = "carry_days", nullable = false)
    private BigDecimal carryDays;

    @Column(name = "encash_days", nullable = false)
    private BigDecimal encashDays;

    @Column(name = "lapse_days", nullable = false)
    private BigDecimal lapseDays;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(name = "encash_basis", length = 8)
    private String encashBasis;

    @Column(name = "encash_divisor")
    private Integer encashDivisor;

    /** Monthly Basic (or Basic + DA) on the last day of the year; null when the employee has no salary structure. */
    @Column(name = "monthly_base")
    private BigDecimal monthlyBase;

    @Column(name = "encash_amount")
    private BigDecimal encashAmount;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(nullable = false)
    @Builder.Default
    private boolean adjusted = false;

    @Column(name = "adjusted_by")
    private UUID adjustedBy;

    @Column(name = "adjusted_at")
    private LocalDateTime adjustedAt;
}

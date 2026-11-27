package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * LV-04: one regular paid leave type's position for an employee who leaves, in the leave year of their last
 * working day: the entitlement recomputed up to that day, carried-forward days still usable, days used, and the
 * result — days that can be encashed, or days taken beyond the entitlement, to recover in the final settlement.
 */
@Entity
@Table(name = "leave_exit_settlements")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveExitSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "leave_type_id", nullable = false)
    private UUID leaveTypeId;

    @Column(nullable = false)
    private Integer year;

    @Column(name = "last_working_day", nullable = false)
    private LocalDate lastWorkingDay;

    @Column(name = "entitled_days", nullable = false)
    private BigDecimal entitledDays;

    @Column(name = "carried_forward_days", nullable = false)
    private BigDecimal carriedForwardDays;

    @Column(name = "used_days", nullable = false)
    private BigDecimal usedDays;

    @Column(name = "encashable_days", nullable = false)
    private BigDecimal encashableDays;

    @Column(name = "recovery_days", nullable = false)
    private BigDecimal recoveryDays;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;
}

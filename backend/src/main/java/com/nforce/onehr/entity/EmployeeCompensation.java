package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C02 (Epic ONEHR-579) — an employee's CTC and {@link SalaryStructure} assignment, effective-dated
 * the same way as {@link EmployeeManagerHistory}: effectiveTo IS NULL marks the current row, a revision
 * is an INSERT + one UPDATE to close the prior row's effectiveTo, never an UPDATE in place.
 */
@Entity
@Table(name = "employee_compensation")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployeeCompensation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "structure_id", nullable = false)
    private SalaryStructure structure;

    @Column(nullable = false)
    private BigDecimal ctc;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

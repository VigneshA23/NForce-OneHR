package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Keka restyle (payroll module) — "Import Investment Declaration" (§3.4): a declared amount under
 * one tax section, for one employee, for one financial year. Bookkeeping only — see this
 * migration's own comment for why nothing reads this into an actual TDS computation yet.
 */
@Entity
@Table(name = "investment_declarations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InvestmentDeclaration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "financial_year", nullable = false, length = 10)
    private String financialYear;

    @Column(nullable = false, length = 30)
    private String section;

    @Column(name = "declared_amount", nullable = false)
    private BigDecimal declaredAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

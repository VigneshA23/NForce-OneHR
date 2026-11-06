package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C01 — a configurable pay-component template (Basic, HRA, allowances, deductions), assigned
 * to an employee together with a CTC (US-C02, {@code EmployeeCompensation}) rather than storing
 * pay rules per-employee. Grade is a free-text label for now (e.g. "L2", "Manager") — not a FK to
 * an org-level grade master, since none exists yet in this codebase.
 *
 * <p>Modeled on the org's current payroll system (Keka): a structure is scoped to a financial
 * year (e.g. "2026-27") and an older year's structure is kept, not deleted, once a newer one
 * exists — past payslips keep computing against whatever structure was effective for them. This
 * story only stores the label; which structure is "current" for new assignments is a later
 * story's (US-C02) concern.
 */
@Entity
@Table(name = "salary_structures")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SalaryStructure {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50)
    private String grade;

    // E.g. "2026-27" — free-text, not parsed/validated as a date range at this story.
    @Column(name = "financial_year", length = 10)
    private String financialYear;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

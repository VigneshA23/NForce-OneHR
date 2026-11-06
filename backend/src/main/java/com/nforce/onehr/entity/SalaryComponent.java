package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * US-C20 (ONEHR-?) — a master catalog entry (e.g. "Basic", "HRA", "Over Time", "Asset Damage
 * Recovery"): the single, org-wide list every Salary Structure (US-C01) selects components from,
 * rather than each structure defining its own independent "Basic"/"HRA"/etc. Describes what KIND
 * of thing a component fundamentally is (taxable? shown on a payslip? counted toward CTC? does
 * LOP reduce it?) — HOW a specific structure computes its value (fixed amount, % of gross, % of
 * another component, cap, ...) lives on {@link SalaryStructureComponent}, the join between a
 * structure and one catalog entry.
 *
 * <p>Never hard-deleted once referenced by a structure or a processed payroll run — see
 * {@code active} — matching Keka's own "deactivate, don't delete" rule for this catalog.
 */
@Entity
@Table(name = "salary_component_catalog")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SalaryComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    // EARNING, AD_HOC_EARNING, DEDUCTION, AD_HOC_DEDUCTION or REIMBURSEMENT — see
    // SalaryComponentType.
    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false)
    @Builder.Default
    private boolean taxable = true;

    // E.g. "10(13A)" for HRA — which exemption section applies, when taxable is false (or
    // partially exempt). Null until Finance/Payroll Admin fills it in via the admin UI — not
    // hardcoded here, since exact section applicability is a tax-compliance call this story
    // doesn't make.
    @Column(name = "tax_section", length = 50)
    private String taxSection;

    // Whether this component counts toward the employee's CTC figure.
    @Column(name = "included_in_ctc", nullable = false)
    @Builder.Default
    private boolean includedInCtc = true;

    @Column(name = "shown_on_payslip", nullable = false)
    @Builder.Default
    private boolean shownOnPayslip = true;

    // Whether Loss-of-Pay proration (US-C04) reduces this component by default. A structure can't
    // override this per the current model — Keka treats proration as a property of the component
    // itself (e.g. Conveyance is "fixed, never prorated" everywhere it's used), not something that
    // varies structure to structure.
    @Column(name = "prorate_on_lop", nullable = false)
    @Builder.Default
    private boolean prorateOnLop = true;

    // Deactivated, never deleted, once a structure or processed payroll references it.
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

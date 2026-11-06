package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A {@link SalaryStructure}'s use of one {@link SalaryComponent} catalog entry — the join between
 * "what kind of thing is this" (the catalog) and "how does THIS structure compute it" (here).
 * Renamed from this story's original {@code SalaryComponent} entity once US-C20 introduced the
 * shared catalog those details now live in — see {@link SalaryComponent}'s own doc comment.
 */
@Entity
@Table(name = "salary_structure_components")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SalaryStructureComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "structure_id", nullable = false)
    private SalaryStructure structure;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_id", nullable = false)
    private SalaryComponent component;

    // FIXED, PERCENT_OF_GROSS, PERCENT_OF_COMPONENT or BALANCING_REMAINDER — see
    // SalaryCalculationType.
    @Column(name = "calc_type", nullable = false, length = 20)
    private String calcType;

    // A currency amount when FIXED, a percentage (e.g. 40 for 40%) when PERCENT_OF_GROSS or
    // PERCENT_OF_COMPONENT. Null when calcType is BALANCING_REMAINDER — see SalaryStructureService
    // for why value and calcType=BALANCING_REMAINDER are mutually exclusive.
    @Column(precision = 12, scale = 2)
    private BigDecimal value;

    // Set only when calcType is PERCENT_OF_COMPONENT — which OTHER SalaryStructureComponent
    // (within the same structure) this one's percentage is taken against, e.g. HRA referencing
    // Basic. Points at a sibling row in THIS table, not at a SalaryComponent catalog entry — the
    // same catalog component ("Basic") could appear in many structures, so the reference has to
    // resolve to this specific structure's instance of it.
    @Column(name = "reference_structure_component_id")
    private UUID referenceStructureComponentId;

    // A currency ceiling on the calculated amount, e.g. PF capped at the statutory wage ceiling.
    // Null means uncapped.
    @Column(precision = 12, scale = 2)
    private BigDecimal cap;

    // "Whichever is lower" — when true and cap is set, the component pays min(calculated, cap)
    // instead of the calculated amount outright. Meaningless (and validated against) when cap is
    // null.
    @Column(name = "use_lower_of_cap", nullable = false)
    @Builder.Default
    private boolean useLowerOfCap = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * US-C08 — one component's resolved amount on one {@link Payslip}. {@code componentName}/{@code
 * componentType} are snapshotted at generation time (not read live from the catalog) so a past
 * payslip's line items never change if the catalog entry is later renamed or retyped.
 */
@Entity
@Table(name = "payslip_line_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayslipLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payslip_id", nullable = false)
    private Payslip payslip;

    @Column(name = "component_id", nullable = false)
    private UUID componentId;

    @Column(name = "component_name", nullable = false, length = 50)
    private String componentName;

    @Column(name = "component_type", nullable = false, length = 20)
    private String componentType;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    @Builder.Default
    private boolean prorated = false;
}

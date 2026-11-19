package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Keka restyle (payroll module) — one physical Professional Tax registration, under one state,
 * for one pay group (§3.8 "PT Registered states"/"Registered PT Locations for &lt;state&gt;").
 * {@code stateName} is denormalized onto the row rather than living on a separate states table —
 * the state list the UI shows is just the distinct {@code stateName} values already here.
 */
@Entity
@Table(name = "pt_registered_locations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PtRegisteredLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "pay_group_id", nullable = false)
    private UUID payGroupId;

    @Column(name = "state_name", nullable = false, length = 100)
    private String stateName;

    @Column(name = "location_name", nullable = false, length = 100)
    private String locationName;

    @Column(name = "establishment_id", length = 50)
    private String establishmentId;

    @Column(name = "registration_date")
    private LocalDate registrationDate;

    @Column(length = 150)
    private String signatory;

    @Column(name = "signatory_designation", length = 150)
    private String signatoryDesignation;

    @Column(name = "signatory_father_name", length = 150)
    private String signatoryFatherName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

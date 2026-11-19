package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Keka restyle (payroll module) — one row per pay group, the Income Tax filing info (PAN/TAN/
 * Form 130 signatory) and PF/ESI establishment ids (§3.8 "Filing Details"). Admin-entered only;
 * {@code payGroupId} IS the primary key (true one-to-one, no surrogate id needed — a pay group
 * never has more than one filing-details row and never had one before this existed).
 */
@Entity
@Table(name = "pay_group_filing_details")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayGroupFilingDetails {

    @Id
    @Column(name = "pay_group_id")
    private UUID payGroupId;

    @Column(length = 10)
    private String pan;

    @Column(length = 10)
    private String tan;

    @Column(name = "tan_circle_number", length = 50)
    private String tanCircleNumber;

    @Column(name = "cit_tds_location", length = 150)
    private String citTdsLocation;

    @Column(name = "form130_signatory", length = 150)
    private String form130Signatory;

    @Column(name = "signatory_designation", length = 150)
    private String signatoryDesignation;

    @Column(name = "signatory_father_name", length = 150)
    private String signatoryFatherName;

    @Column(name = "pf_establishment_id", length = 50)
    private String pfEstablishmentId;

    @Column(name = "esi_establishment_id", length = 50)
    private String esiEstablishmentId;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
    }
}

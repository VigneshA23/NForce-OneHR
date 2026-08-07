package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "document_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DocumentType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true, length = 80)
    private String name;

    @Column(name = "requires_verification", nullable = false)
    @Builder.Default
    private boolean requiresVerification = true;

    @Column(name = "requires_expiry_date", nullable = false)
    @Builder.Default
    private boolean requiresExpiryDate = false;

    @Column(name = "applicable_employment_types", length = 200)
    private String applicableEmploymentTypes;

    @Column(name = "applicable_locations", length = 200)
    private String applicableLocations;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();
}

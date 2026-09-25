package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "policies")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Policy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 20)
    private String version;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, name = "scope", length = 200)
    @Builder.Default
    private String audience = "All Employees";

    @Column(nullable = false)
    @Builder.Default
    private boolean required = true;

    @Column(name = "published_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant publishedAt = Instant.now();

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    // Version chain, for the explicit "Publish New Version" action (distinct from the
    // metadata-only edit) — every version is its own row; previousVersionId links to the row it
    // superseded. Every existing/first-published policy is version 1 of its own chain.
    @Column(name = "version_number", nullable = false)
    @Builder.Default
    private Integer versionNumber = 1;

    @Column(name = "previous_version_id")
    private Long previousVersionId;

    @Column(name = "attachment_file_name", length = 255)
    private String attachmentFileName;

    @Column(name = "attachment_data", columnDefinition = "BYTEA")
    private byte[] attachmentData;
}

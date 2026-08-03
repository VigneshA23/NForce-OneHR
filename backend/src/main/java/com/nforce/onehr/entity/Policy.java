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
}

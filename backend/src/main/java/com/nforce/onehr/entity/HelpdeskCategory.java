package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Topic master for Help Desk tickets (Attendance, Leave, Payroll, ...). Mirrors
 * {@link DocumentType} exactly — same master-data shape (name, active flag, created_at) —
 * so a new topic can be added by an HR Admin without any code change.
 */
@Entity
@Table(name = "helpdesk_categories")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HelpdeskCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true, length = 80)
    private String name;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();
}

package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One Help & Guidance content item — an FAQ, a quick-help tip, a guide, or a downloadable
 * document — managed by HR Admin/Super Admin and read-only for employees. {@code type} stays a
 * plain String (see {@link com.nforce.onehr.service.HelpContentType}), matching this codebase's
 * established convention for status/discriminator fields (see {@link HelpdeskTicket#getStatus()},
 * {@link DocumentType}). Attachments live in {@link HelpContentAttachment} (multiple, ordered) —
 * see that class for the byte-in-Postgres storage convention.
 *
 * <p>{@code status} is the sole lifecycle field — DRAFT | PENDING_APPROVAL | APPROVED |
 * PUBLISHED | UNPUBLISHED | ARCHIVED (see {@code HelpContentService} for the transition rules).
 * It replaces the old {@code published}/{@code active} booleans, which couldn't express the
 * six-state lifecycle without ambiguity.
 */
@Entity
@Table(name = "help_content")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HelpContent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // FAQ | QUICK_HELP | GUIDE | DOCUMENT
    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 500)
    private String description;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(length = 80)
    private String category;

    // DRAFT | PENDING_APPROVAL | APPROVED | PUBLISHED | UNPUBLISHED | ARCHIVED
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "published_at", columnDefinition = "TIMESTAMPTZ")
    private Instant publishedAt;

    // Set only when this row is a draft revision of a still-PUBLISHED row — editing published
    // content forks a new row rather than mutating the employee-visible one directly (see
    // HelpContentService.prepareForEdit). Publishing this row archives the row it supersedes.
    @Column(name = "supersedes_id")
    private UUID supersedesId;

    // Mirrors the latest rejection so the HR author sees why on the Draft — cleared on any
    // fresh submission or approval. Same convention as RegularizationRequest.reviewComment.
    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "is_featured", nullable = false)
    @Builder.Default
    private boolean featured = false;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    @Column(name = "view_count", nullable = false)
    @Builder.Default
    private long viewCount = 0L;

    // Reserved for future role/department targeting — not filtered on yet.
    @Column(nullable = false, length = 40)
    @Builder.Default
    private String audience = "ALL";

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}

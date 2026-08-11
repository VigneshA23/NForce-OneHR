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
 * {@link DocumentType}). The optional attachment mirrors {@link HelpdeskReply}'s byte-in-Postgres
 * storage exactly — no new file-storage mechanism.
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

    @Column(name = "attachment_name", length = 255)
    private String attachmentName;

    @Column(name = "attachment_type", length = 100)
    private String attachmentType;

    @Column(name = "attachment_size")
    private Long attachmentSize;

    @Column(name = "attachment_data", columnDefinition = "BYTEA")
    private byte[] attachmentData;

    @Column(nullable = false)
    @Builder.Default
    private boolean published = false;

    @Column(name = "published_at", columnDefinition = "TIMESTAMPTZ")
    private Instant publishedAt;

    // Archive = false. Kept separate from `published` so unpublishing (draft) and archiving
    // (retired/hidden for good) are distinct actions, matching Policy/Announcement's precedent.
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

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

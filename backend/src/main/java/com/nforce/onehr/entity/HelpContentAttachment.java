package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One attachment on a {@link HelpContent} row — ordered, multiple per content. Same
 * byte-in-Postgres storage convention as {@code HelpdeskReply}/{@code EmployeeDocument}, just
 * split into its own table (rather than a single column on {@code help_content}) so a content
 * item can carry more than one file and so attachments can be reordered independently.
 * {@code checksum} (sha-256 of {@code fileData}) lets the approval-attempt comparison detect
 * "removed and re-added the exact same file" without re-reading the bytes.
 */
@Entity
@Table(name = "help_content_attachment")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HelpContentAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_type", length = 100)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "file_data", nullable = false, columnDefinition = "BYTEA")
    private byte[] fileData;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();
}

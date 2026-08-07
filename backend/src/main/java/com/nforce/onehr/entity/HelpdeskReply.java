package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A single message in a ticket's conversation thread. {@code isInternal} notes are written by
 * HR and must never be surfaced on the employee-facing endpoints (enforced in HelpdeskService,
 * not at the entity level). The optional attachment reuses EmployeeDocument's byte-in-Postgres
 * storage mechanism rather than a separate join table, since a reply carries at most one file.
 */
@Entity
@Table(name = "helpdesk_replies")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HelpdeskReply {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    // EMPLOYEE | HR — resolved server-side from the caller's role, never client-supplied
    @Column(name = "sender_role", nullable = false, length = 20)
    private String senderRole;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "is_internal", nullable = false)
    @Builder.Default
    private boolean internal = false;

    @Column(name = "attachment_name", length = 255)
    private String attachmentName;

    @Column(name = "attachment_type", length = 100)
    private String attachmentType;

    @Column(name = "attachment_size")
    private Long attachmentSize;

    @Column(name = "attachment_data", columnDefinition = "BYTEA")
    private byte[] attachmentData;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();
}

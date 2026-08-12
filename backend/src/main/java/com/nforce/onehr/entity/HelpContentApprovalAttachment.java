package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Attachment snapshot for one {@link HelpContentApproval} attempt — a byte-for-byte copy taken
 * at submission time, so an approver reviewing "View Changes" can open the *previous* attempt's
 * actual file, not just its recorded name/size. Same shape as {@link HelpContentAttachment}.
 */
@Entity
@Table(name = "help_content_approval_attachment")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HelpContentApprovalAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "approval_id", nullable = false)
    private UUID approvalId;

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
}

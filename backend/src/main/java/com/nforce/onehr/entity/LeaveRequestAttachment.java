package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** LV-02: the file attached to a leave request (one per request; replacing it overwrites the row). */
@Entity
@Table(name = "leave_request_attachments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveRequestAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "leave_request_id", nullable = false, unique = true)
    private UUID leaveRequestId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Basic(fetch = FetchType.LAZY)
    @Column(name = "data", nullable = false)
    private byte[] data;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        uploadedAt = LocalDateTime.now();
    }
}

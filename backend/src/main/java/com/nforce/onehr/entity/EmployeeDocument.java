package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "employee_documents",
       uniqueConstraints = @UniqueConstraint(columnNames = {"employee_user_id", "document_type_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployeeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_type_id", nullable = false)
    private DocumentType documentType;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_url", nullable = false, length = 500)
    private String fileUrl;

    @Column(name = "file_data", nullable = false, columnDefinition = "BYTEA")
    private byte[] fileData;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "PENDING_VERIFICATION";

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "verified_at", columnDefinition = "TIMESTAMPTZ")
    private Instant verifiedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "uploaded_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant uploadedAt = Instant.now();

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}

package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "employee_learning_entries")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployeeLearningEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "learning_type", nullable = false, length = 30)
    private String learningType;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "provider", length = 150)
    private String provider;

    @Column(name = "start_date")
    private LocalDate startDate;

    // Completion date. Nullable: a Planned or In Progress entry has none yet.
    @Column(name = "entry_date")
    private LocalDate completedDate;

    // Comma-separated skill names (see EmployeeLearningEntryService#toCsv/fromCsv).
    @Column(name = "skills_developed", length = 500)
    private String skillsDeveloped;

    @Column(name = "certificate_name", length = 255)
    private String certificateName;

    @Column(name = "certificate_issue_date")
    private LocalDate certificateIssueDate;

    @Column(name = "certificate_expiry_date")
    private LocalDate certificateExpiryDate;

    @Column(name = "certificate_url", length = 500)
    private String certificateUrl;

    @Column(name = "verification_status", nullable = false, length = 25)
    private String verificationStatus;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}

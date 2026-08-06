package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A manually-tracked onboarding task (pre-boarding, or a setup task with no
 * existing system of record). Items that are derivable from Documents/Assets
 * — required docs verified, laptop assigned, access card assigned — are never
 * stored as rows here; they're computed live by OnboardingService.
 */
@Entity
@Table(name = "onboarding_checklist_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OnboardingChecklistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "checklist_id", nullable = false)
    private UUID checklistId;

    // PRE_BOARDING | SETUP
    @Column(name = "category", nullable = false, length = 20)
    private String category;

    @Column(name = "item_key", nullable = false, length = 50)
    private String itemKey;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "done", nullable = false)
    @Builder.Default
    private boolean done = false;

    @Column(name = "done_at", columnDefinition = "TIMESTAMPTZ")
    private Instant doneAt;

    @Column(name = "done_by")
    private UUID doneBy;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}

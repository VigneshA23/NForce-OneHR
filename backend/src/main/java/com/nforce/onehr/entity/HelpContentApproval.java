package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One approval attempt on a {@link HelpContent} row — created on every Submit for Approval and
 * never deleted, mirroring {@code RegularizationApproval}'s "immutable audit row" convention.
 * Unlike RegularizationApproval, this row is mutated exactly once after creation (to record the
 * Approve/Reject/Withdraw outcome) rather than staying forever immutable, because the approver
 * and the outcome both belong to *this* attempt, not a separate row — the outer
 * {@code help_content.status} already carries the "current" pointer, so nothing else needs an
 * append-only decision log the way Regularization's multi-stage flow does.
 *
 * <p>The snapshot_* fields are exactly what was submitted, so a later attempt on the same
 * content can be diffed against this one even after {@code help_content}'s live fields have
 * moved on (further edits, or after this attempt was rejected/withdrawn).
 */
@Entity
@Table(name = "help_content_approval")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HelpContentApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "submitted_by", nullable = false)
    private UUID submittedBy;

    @Column(name = "submitted_at", nullable = false)
    @Builder.Default
    private Instant submittedAt = Instant.now();

    @Column(name = "approver_id", nullable = false)
    private UUID approverId;

    // PENDING | APPROVED | REJECTED | WITHDRAWN — the outcome of this one attempt, not a
    // content-level status; help_content.status is the only content lifecycle field.
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "withdrawal_reason", columnDefinition = "TEXT")
    private String withdrawalReason;

    @Column(name = "snapshot_title", nullable = false, length = 200)
    private String snapshotTitle;

    @Column(name = "snapshot_description", length = 500)
    private String snapshotDescription;

    @Column(name = "snapshot_body", columnDefinition = "TEXT")
    private String snapshotBody;

    @Column(name = "snapshot_category", length = 80)
    private String snapshotCategory;

    @Column(name = "snapshot_featured", nullable = false)
    @Builder.Default
    private boolean snapshotFeatured = false;

    @Column(name = "snapshot_display_order", nullable = false)
    @Builder.Default
    private int snapshotDisplayOrder = 0;
}

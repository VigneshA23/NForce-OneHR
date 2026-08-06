package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One immutable row per approve/reject decision on a {@link RegularizationRequest} — the
 * permanent audit trail. Never updated or deleted; RegularizationRequest itself only keeps
 * the *latest* decision inline (reviewed_by/reviewed_at/review_comment) for convenience.
 */
@Entity
@Table(name = "regularization_approvals")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RegularizationApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "action_by", nullable = false)
    private UUID actionBy;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    // The authority actually exercised for this action (MANAGER | HR_ADMIN | SUPER_ADMIN) —
    // not necessarily every role the actor holds, e.g. a Super Admin bypass always records
    // SUPER_ADMIN even if that user also holds EMPLOYEE/MANAGER.
    @Column(name = "actor_role")
    private String actorRole;

    @Column(columnDefinition = "TEXT")
    private String comments;

    @Column(name = "action_date", nullable = false)
    @Builder.Default
    private LocalDateTime actionDate = LocalDateTime.now();
}

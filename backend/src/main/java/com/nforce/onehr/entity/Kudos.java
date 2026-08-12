package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A one-way peer recognition note ("Appreciate your lead" / "🎉 Appreciate" on the My Team:
 * Peers view — ONEHR-73). Deliberately minimal: no approval workflow, no visibility settings —
 * sending one just creates a row and notifies the recipient.
 */
@Entity
@Table(name = "kudos")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Kudos {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_user_id", nullable = false)
    private UUID fromUserId;

    @Column(name = "to_user_id", nullable = false)
    private UUID toUserId;

    /** Free-text category chosen from the composer's fixed chips — e.g. Great Work, Teamwork,
     * Leadership, Extra Mile. Not an enum: HR may want to add categories without a migration. */
    @Column(name = "category", nullable = false, length = 30)
    private String category;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();
}

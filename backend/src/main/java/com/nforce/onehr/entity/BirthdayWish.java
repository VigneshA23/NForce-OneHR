package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** A short message a teammate sends someone on their actual birthday. See {@link Kudos} for the
 * closest sibling in this codebase — deliberately a separate table, not a reuse of it (see
 * V194__create_birthday_wishes.sql for why). */
@Entity
@Table(name = "birthday_wishes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BirthdayWish {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_user_id", nullable = false)
    private UUID fromUserId;

    @Column(name = "to_user_id", nullable = false)
    private UUID toUserId;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();
}

package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(name = "target_id")
    private UUID targetId;

    // JSONB columns — @JdbcTypeCode ensures Hibernate binds null as SQL NULL, not null::varchar
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private String beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb")
    private String afterState;

    // Instant, not LocalDateTime — a zone-naive LocalDateTime bound to this TIMESTAMPTZ column
    // goes through java.sql.Timestamp, which resolves via the JVM's default zone (IST on dev
    // boxes) unless Hibernate's per-connection "SET TIME ZONE" pinning happens to have taken
    // effect on whichever pooled physical connection this request got — with Neon's pooled
    // endpoint that's not guaranteed every time, which is exactly why timestamps were coming
    // out right on some inserts and off by the IST offset (5:30) on others. Instant carries its
    // own explicit UTC semantics through the JDBC binding, so there's no zone/pooling race to
    // land on the wrong side of.
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @PrePersist
    protected void onInsert() {
        occurredAt = Instant.now();
    }
}

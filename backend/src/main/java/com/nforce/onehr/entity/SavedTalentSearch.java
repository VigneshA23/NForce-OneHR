package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saved_talent_search")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SavedTalentSearch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    // The whole Talent Search form's state (skill AND/OR groups, proficiency/department/location/
    // learning-status filters, ...) as one opaque JSON blob — same JSONB pass-through as
    // AuditLog.beforeState/afterState, so the search UI's filter shape can evolve without another
    // migration. @JdbcTypeCode ensures Hibernate binds null as SQL NULL, not null::varchar.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "query_json", nullable = false, columnDefinition = "jsonb")
    private String queryJson;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();
}

package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "bench_status")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BenchStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 40)
    private BenchReasonCode reasonCode;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /** US-A04: denormalized current aging bucket label, refreshed nightly (and set on creation). */
    @Column(name = "aging_bucket", length = 40)
    private String agingBucket;

    /** US-A05: set once the threshold-crossing notification has fired, so it never repeats. */
    @Column(name = "notified_at", columnDefinition = "TIMESTAMPTZ")
    private Instant notifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 40)
    private BenchCloseReason closeReason;

    @Column(name = "close_note", length = 500)
    private String closeNote;

    @Column(name = "allocation_id")
    private UUID allocationId;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();
}

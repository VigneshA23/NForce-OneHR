package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * US-B02: a client / cost-center name a Project can be tagged with, purely so reporting can slice
 * by it. Deliberately just a lookup — no rates, invoices or revenue (non-billable scope this phase).
 */
@Entity
@Table(name = "clients")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, columnDefinition = "citext")
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();
}

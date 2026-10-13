package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** US-A04: one HR-configurable aging bucket. maxDays null = open-ended (the last bucket). */
@Entity
@Table(name = "bench_aging_bucket_config")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BenchAgingBucket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bucket_label", nullable = false, length = 40)
    private String bucketLabel;

    @Column(name = "min_days", nullable = false)
    private int minDays;

    @Column(name = "max_days")
    private Integer maxDays;
}

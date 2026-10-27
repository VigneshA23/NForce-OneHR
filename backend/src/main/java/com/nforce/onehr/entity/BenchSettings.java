package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

/** US-A05: the single org-wide settings row (id is always 1). */
@Entity
@Table(name = "bench_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BenchSettings {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    @Column(name = "notify_threshold_days", nullable = false)
    private int notifyThresholdDays;
}

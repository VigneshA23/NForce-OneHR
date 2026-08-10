package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "weekly_off_policies")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WeeklyOffPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    // Comma-separated java.time.DayOfWeek names, e.g. "SATURDAY,SUNDAY".
    @Column(name = "off_days", nullable = false)
    private String offDays;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

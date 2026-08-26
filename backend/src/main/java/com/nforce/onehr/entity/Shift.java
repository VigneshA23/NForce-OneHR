package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "shifts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(unique = true, length = 30)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String description;

    // @Convert to LocalTimeTextConverter — see its own Javadoc for why: Hibernate's default
    // java.sql.Time/Calendar-mediated binding for this column is NOT actually independent of
    // the writing/reading JVM's own default timezone despite hibernate.jdbc.time_zone: UTC,
    // which silently corrupted this value whenever a non-UTC-default JVM (e.g. any local dev
    // machine) saved it. A plain string has no timezone semantics for any JVM to skew.
    @Column(name = "start_time", nullable = false)
    @Convert(converter = LocalTimeTextConverter.class)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    @Convert(converter = LocalTimeTextConverter.class)
    private LocalTime endTime;

    @Column(nullable = false)
    @Builder.Default
    private boolean flexible = false;

    @Column(name = "break_minutes")
    private Integer breakMinutes;

    // Comma-separated java.time.DayOfWeek names, e.g. "MONDAY,TUESDAY" — same convention as
    // WeeklyOffPolicy.offDays. Null when this shift doesn't specify working days itself (the
    // employee's assigned WeeklyOffPolicy is the source of truth in that case).
    @Column(name = "working_days", length = 60)
    private String workingDays;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

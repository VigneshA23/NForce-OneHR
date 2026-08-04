package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "attendance_exceptions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceException {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "exception_date", nullable = false)
    private LocalDate exceptionDate;

    // Plain string, not a JPA enum — matches the codebase convention (role codes, work mode).
    @Column(name = "exception_type", nullable = false, length = 30)
    private String exceptionType;

    @Column(name = "expected_time")
    private LocalTime expectedTime;

    @Column(name = "actual_time")
    private LocalTime actualTime;

    @Column(name = "minutes_late")
    private Integer minutesLate;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "OPEN";

    @Column(name = "source", nullable = false, length = 30)
    @Builder.Default
    private String source = "SYSTEM";

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        if (detectedAt == null) detectedAt = now;
    }
}

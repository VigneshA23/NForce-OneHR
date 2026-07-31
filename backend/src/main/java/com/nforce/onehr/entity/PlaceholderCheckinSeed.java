package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * TEMPORARY — NOT PART OF REAL ATTENDANCE INFRASTRUCTURE.
 * Stands in for real check-in data until FR-004 (Attendance Management) ships.
 * Delete this class (and its repository/controller/DTOs) in the same PR that
 * lands FR-004, once ExceptionService's detection query reads real attendance data.
 */
@Entity
@Table(name = "placeholder_checkin_seed")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PlaceholderCheckinSeed {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "shift_start_time", nullable = false)
    @Builder.Default
    private LocalTime shiftStartTime = LocalTime.of(9, 30);

    @Column(name = "checkin_time", nullable = false)
    private LocalTime checkinTime;

    @Column(name = "late_threshold_minutes", nullable = false)
    @Builder.Default
    private Integer lateThresholdMinutes = 15;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

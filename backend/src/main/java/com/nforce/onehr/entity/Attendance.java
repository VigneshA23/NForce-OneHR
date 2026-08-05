package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "attendance_records")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Raw UUID rather than a @ManyToOne, matching EmployeeManagerHistory.employeeUserId.
    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "check_in_at", nullable = false)
    private LocalDateTime checkInAt;

    @Column(name = "check_out_at")
    private LocalDateTime checkOutAt;

    @Column(name = "worked_minutes")
    private Integer workedMinutes;

    // When the currently-open session began — only meaningful while checkOutAt is null.
    // Lets a later session in the same day (e.g. after a lunch break) compute its own
    // duration without disturbing checkInAt, which stays the day's first check-in.
    @Column(name = "session_started_at")
    private LocalDateTime sessionStartedAt;

    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "PRESENT";

    @Column(name = "late_by_minutes", nullable = false)
    @Builder.Default
    private Integer lateByMinutes = 0;

    // SYSTEM for a normal punch, REGULARIZATION for a row created/edited via an approved
    // regularization request. See V18 migration and RegularizationService.
    @Column(name = "source", nullable = false)
    @Builder.Default
    private String source = "SYSTEM";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

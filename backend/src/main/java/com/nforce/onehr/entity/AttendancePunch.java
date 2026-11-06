package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** One check-in/check-out session within a day. See {@link Attendance} for the daily aggregate. */
@Entity
@Table(name = "attendance_punches")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendancePunch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "attendance_record_id", nullable = false)
    private UUID attendanceRecordId;

    @Column(name = "check_in_at", nullable = false)
    private LocalDateTime checkInAt;

    @Column(name = "check_out_at")
    private LocalDateTime checkOutAt;

    // Office-network check (record-only). *InOffice null = location's office network not configured.
    @Column(name = "check_in_ip", length = 45)
    private String checkInIp;

    @Column(name = "check_in_in_office")
    private Boolean checkInInOffice;

    @Column(name = "check_out_ip", length = 45)
    private String checkOutIp;

    @Column(name = "check_out_in_office")
    private Boolean checkOutInOffice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

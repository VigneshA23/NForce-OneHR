package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "employees")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Employee {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "employee_code", nullable = false, unique = true)
    private String employeeCode;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "designation_id")
    private Designation designation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private Location location;

    @Column(name = "employment_type", nullable = false)
    @Builder.Default
    private String employmentType = "FULL_TIME";

    @Column(name = "work_mode", nullable = false)
    @Builder.Default
    private String workMode = "ONSITE";

    @Column(name = "joining_date", nullable = false)
    private LocalDate joiningDate;

    // Self-service profile fields — employee updates these themselves
    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "gender", length = 20)
    private String gender;

    @Column(name = "personal_email", length = 255)
    private String personalEmail;

    @Column(name = "address")
    private String address;

    @Column(name = "emergency_contact_name", length = 200)
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone", length = 30)
    private String emergencyContactPhone;

    @Column(name = "profile_photo", columnDefinition = "BYTEA")
    private byte[] profilePhoto;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

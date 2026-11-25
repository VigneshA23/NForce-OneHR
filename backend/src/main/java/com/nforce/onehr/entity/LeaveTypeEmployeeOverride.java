package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** A hand-picked employee always included in, or excluded from, a leave type regardless of its rules (V314). */
@Entity
@Table(name = "leave_type_employee_overrides")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveTypeEmployeeOverride {

    public static final String INCLUDE = "INCLUDE";
    public static final String EXCLUDE = "EXCLUDE";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "leave_type_id", nullable = false)
    private UUID leaveTypeId;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(nullable = false, length = 10)
    private String mode;
}

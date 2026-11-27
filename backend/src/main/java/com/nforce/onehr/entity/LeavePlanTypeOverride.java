package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** LV-01: an employee hand-picked into or out of a plan's leave type (was {@code leave_type_employee_overrides}). */
@Entity
@Table(name = "leave_plan_type_overrides")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeavePlanTypeOverride {

    public static final String INCLUDE = "INCLUDE";
    public static final String EXCLUDE = "EXCLUDE";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(nullable = false, length = 10)
    private String mode;
}
